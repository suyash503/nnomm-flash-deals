package com.nnomm.deals

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val log = LoggerFactory.getLogger(Payments::class.java)

data class PaymentEvent(val eventId: String, val type: String, val claimId: UUID, val amountPaise: Long, val occurredAt: Instant)

/** What a webhook did; stored in payment_events.outcome and returned in the 200 body. */
enum class PaymentOutcome {
    PAID, NEEDS_REVIEW, REFUND_PENDING, NO_CHANGE, DUPLICATE_EVENT,
    UNKNOWN_CLAIM,           // money arrived for a claim we never created: recorded for reconciliation
    EXTRA_PAYMENT_TO_REFUND, // a second successful payment for a claim that is already settled
    IGNORED_EVENT_TYPE,      // signed, but not a type we act on; not stored
}

@Serializable
data class WebhookResponse(val received: Boolean, val outcome: String)

class Payments(private val db: Db, webhookSecret: String) {
    private val key = SecretKeySpec(webhookSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    /**
     * Checks X-Payment-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.rawBody">.
     * The HMAC covers the raw bytes we received, before any JSON parsing.
     */
    fun verifySignature(header: String?, rawBody: ByteArray, now: Instant = Instant.now()) {
        fun reject(): Nothing = fail(ErrorCode.UNAUTHENTICATED, "Invalid webhook signature")
        if (header == null) reject()

        val parts = header.split(',').map { it.trim().split('=', limit = 2) }
        if (parts.any { it.size != 2 }) reject()
        val t = parts.singleOrNull { it[0] == "t" }?.get(1)?.takeIf { it.matches(Regex("^\\d{1,12}$")) }?.toLong() ?: reject()
        val v1 = parts.singleOrNull { it[0] == "v1" }?.get(1)?.takeIf { it.matches(Regex("^[0-9a-fA-F]{64}$")) } ?: reject()

        // Freshness: a captured request can't be replayed more than 5 minutes later.
        if (kotlin.math.abs(now.epochSecond - t) > 300) reject()

        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        mac.update("$t.".toByteArray(Charsets.UTF_8))
        val expected = mac.doFinal(rawBody)
        // Constant-time compare: the time taken doesn't reveal how many leading bytes matched.
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(v1))) reject()
    }

    suspend fun apply(event: PaymentEvent): PaymentOutcome = db.tx { conn ->
        // The event_id primary key is the dedupe. A concurrent copy of this event waits here for us;
        // when we commit it inserts nothing and returns DUPLICATE_EVENT.
        val firstTime = conn.update(
            """
            INSERT INTO payment_events (event_id, type, claim_id, amount_paise, occurred_at)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
            """,
            event.eventId, event.type, event.claimId, event.amountPaise, event.occurredAt,
        ) == 1
        if (!firstTime) return@tx PaymentOutcome.DUPLICATE_EVENT

        val outcome = decide(conn, event)
        conn.update("UPDATE payment_events SET outcome = ? WHERE event_id = ?", outcome.name, event.eventId)
        if (outcome == PaymentOutcome.UNKNOWN_CLAIM || outcome == PaymentOutcome.EXTRA_PAYMENT_TO_REFUND) {
            log.warn("Payment event {} for claim {} needs reconciliation: {}", event.eventId, event.claimId, outcome)
        }
        outcome
    }

    private fun decide(conn: Connection, event: PaymentEvent): PaymentOutcome {
        // Lock the claim row first. The expiry sweeper needs the same lock, so a payment and an expiry
        // on one claim run one after the other; the second sees what the first did.
        conn.queryOne("SELECT id FROM claims WHERE id = ? FOR UPDATE", event.claimId) { true }
            ?: return PaymentOutcome.UNKNOWN_CLAIM

        // Read the state only after we hold the lock, so "past expires_at" is judged at processing time.
        val claim = conn.queryOne(
            "SELECT status, deal_id, quantity, amount_paise, expires_at <= clock_timestamp() AS due FROM claims WHERE id = ?",
            event.claimId,
        ) { rs -> LockedClaim(rs.getString("status"), rs.uuid("deal_id"), rs.getInt("quantity"), rs.getLong("amount_paise"), rs.getBoolean("due")) }!!

        // payment.failed never changes a claim: the customer may retry payment until expiry.
        if (event.type == "payment.failed") return PaymentOutcome.NO_CHANGE

        return when (claim.status) {
            "RESERVED" -> when {
                claim.due -> {
                    // Paid too late, and the sweeper hasn't run yet: expire it here, returning the units
                    // exactly once, and flag the money for refund.
                    setStatus(conn, event.claimId, "REFUND_PENDING")
                    conn.update("UPDATE deals SET stock_available = stock_available + ? WHERE id = ?", claim.quantity, claim.dealId)
                    PaymentOutcome.REFUND_PENDING
                }
                event.amountPaise == claim.amountPaise -> setStatus(conn, event.claimId, "PAID").let { PaymentOutcome.PAID }
                // Wrong amount: keep the units held and let a human sort it out.
                else -> setStatus(conn, event.claimId, "NEEDS_REVIEW").let { PaymentOutcome.NEEDS_REVIEW }
            }
            // Already expired, units already back on sale: never take them again, just refund.
            "EXPIRED" -> setStatus(conn, event.claimId, "REFUND_PENDING").let { PaymentOutcome.REFUND_PENDING }
            // PAID, NEEDS_REVIEW, REFUND_PENDING are settled. Don't flip them on a second payment;
            // record it so finance can refund the extra charge.
            else -> PaymentOutcome.EXTRA_PAYMENT_TO_REFUND
        }
    }

    private fun setStatus(conn: Connection, claimId: UUID, status: String) {
        conn.update("UPDATE claims SET status = ?, updated_at = clock_timestamp() WHERE id = ?", status, claimId)
    }

    private data class LockedClaim(val status: String, val dealId: UUID, val quantity: Int, val amountPaise: Long, val due: Boolean)
}

private val KNOWN_EVENT_TYPES = setOf("payment.succeeded", "payment.failed")

fun Route.webhookRoutes(payments: Payments) {
    // No JWT here: the signature is the authentication.
    post("/v1/webhooks/payments") {
        val raw = call.receiveLimitedBytes()
        payments.verifySignature(call.request.headers["X-Payment-Signature"], raw)

        val body = parseJsonObject(raw)
        val type = body.requiredString("type")
        // A signed event of a type we don't handle is genuine, so acknowledge it; a 4xx would make the
        // provider retry it forever.
        if (type !in KNOWN_EVENT_TYPES) {
            return@post call.respond(WebhookResponse(received = true, outcome = PaymentOutcome.IGNORED_EVENT_TYPE.name))
        }
        val event = PaymentEvent(
            eventId = body.requiredString("event_id").also {
                if (it.isEmpty() || it.length > 255) fail(ErrorCode.VALIDATION_FAILED, "event_id must be 1-255 characters")
            },
            type = type,
            claimId = body.requiredString("claim_id").toUuidOrNull() ?: fail(ErrorCode.VALIDATION_FAILED, "claim_id must be a UUID"),
            amountPaise = body.requiredLong("amount_paise").requireIn(0L..Long.MAX_VALUE, "amount_paise"),
            occurredAt = body.requiredInstant("occurred_at"),
        )
        val outcome = payments.apply(event)
        call.respond(WebhookResponse(received = true, outcome = outcome.name))
    }
}
