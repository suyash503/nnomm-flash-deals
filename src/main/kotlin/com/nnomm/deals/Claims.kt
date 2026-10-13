package com.nnomm.deals

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9_-]{1,64}$")

/** The response to a claim request, exactly as stored for replay. */
data class ClaimOutcome(val statusCode: Int, val body: String, val replayed: Boolean)

class ClaimService(private val db: Db, private val expiry: Expiry, private val ttlSeconds: Long) {

    suspend fun claim(customerId: UUID, dealId: UUID, quantity: Int, key: String): ClaimOutcome {
        // 404s are not stored against the key, so check the deal exists before touching idempotency_keys.
        db.tx { conn -> conn.queryOne("SELECT 1 FROM deals WHERE id = ?", dealId) { true } }
            ?: fail(ErrorCode.NOT_FOUND, "Deal not found")

        // Put back units from this deal's claims that are already past expires_at, in its own short transaction.
        // Without this, a customer whose claim just expired would hit ALREADY_CLAIMED until the sweeper runs.
        expiry.expireDueForDeal(dealId)

        return try {
            db.tx { conn -> claimInTransaction(conn, customerId, dealId, quantity, key) }
        } catch (e: SQLException) {
            // lock_timeout fired while waiting for another request holding the same key.
            if (e.sqlState == SqlState.LOCK_NOT_AVAILABLE) {
                fail(ErrorCode.REQUEST_IN_PROGRESS, "A request with this Idempotency-Key is still running")
            }
            throw e
        }
    }

    private fun claimInTransaction(conn: Connection, customerId: UUID, dealId: UUID, quantity: Int, key: String): ClaimOutcome {
        // Keys are honoured for 24 hours. An older record is forgotten, so the key starts fresh.
        conn.update(
            "DELETE FROM idempotency_keys WHERE customer_id = ? AND key = ? AND created_at < clock_timestamp() - interval '24 hours'",
            customerId, key,
        )

        // Claim the key. If another transaction inserted the same (customer, key) and hasn't finished,
        // Postgres makes this INSERT wait for it: once it commits we get 0 rows and replay its answer;
        // if it rolled back (crash, error) our insert goes through. We wait at most 5 s, then answer 409.
        conn.update("SET LOCAL lock_timeout = '5s'")
        val keyIsNew = conn.update(
            "INSERT INTO idempotency_keys (customer_id, key, deal_id, quantity) VALUES (?, ?, ?, ?) ON CONFLICT (customer_id, key) DO NOTHING",
            customerId, key, dealId, quantity,
        ) == 1
        conn.update("SET LOCAL lock_timeout = 0")

        if (!keyIsNew) return replay(conn, customerId, dealId, quantity, key)

        val outcome = attemptClaim(conn, customerId, dealId, quantity)
        conn.update(
            "UPDATE idempotency_keys SET response_code = ?, response_body = ? WHERE customer_id = ? AND key = ?",
            outcome.statusCode, outcome.body, customerId, key,
        )
        return outcome
    }

    private fun replay(conn: Connection, customerId: UUID, dealId: UUID, quantity: Int, key: String): ClaimOutcome {
        val stored = conn.queryOne(
            "SELECT deal_id, quantity, response_code, response_body FROM idempotency_keys WHERE customer_id = ? AND key = ?",
            customerId, key,
        ) { rs -> Triple(rs.uuid("deal_id") to rs.getInt("quantity"), rs.getInt("response_code"), rs.getString("response_body")) }
            ?: error("Idempotency record vanished between insert and select")

        val (request, code, body) = stored
        if (request != (dealId to quantity)) {
            fail(ErrorCode.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was used for a different request")
        }
        return ClaimOutcome(code, body, replayed = true)
    }

    private fun attemptClaim(conn: Connection, customerId: UUID, dealId: UUID, quantity: Int): ClaimOutcome {
        val pricePaise = conn.queryOne("SELECT price_paise FROM deals WHERE id = ?", dealId) { it.getLong(1) }!!
        if (!isLive(conn, dealId)) return conflict(ErrorCode.DEAL_NOT_LIVE, "The deal is not live")

        // A savepoint lets us undo the claim insert alone and still commit the 409 into idempotency_keys.
        val savepoint = conn.setSavepoint()
        val claimId = UUID.randomUUID()
        try {
            // The partial unique index claims_one_active_per_customer rejects a second active claim.
            // If another request of this customer is mid-insert, this waits for it, then fails or succeeds.
            conn.update(
                """
                INSERT INTO claims (id, deal_id, customer_id, quantity, amount_paise, status, expires_at)
                VALUES (?, ?, ?, ?, ?, 'RESERVED', clock_timestamp() + make_interval(secs => ?))
                """,
                claimId, dealId, customerId, quantity, pricePaise * quantity, ttlSeconds.toDouble(),
            )
        } catch (e: SQLException) {
            if (e.sqlState != SqlState.UNIQUE_VIOLATION) throw e
            conn.rollback(savepoint)
            return conflict(ErrorCode.ALREADY_CLAIMED, "You already hold an active claim on this deal")
        }

        // The oversell guard. UPDATE locks the deal row, so concurrent claims (from either instance)
        // queue here. Each re-checks the WHERE clause against the latest committed stock once it gets
        // the lock, and only succeeds if enough units are still there and the deal is still live.
        val took = conn.update(
            """
            UPDATE deals SET stock_available = stock_available - ?
            WHERE id = ? AND stock_available >= ?
              AND starts_at <= clock_timestamp() AND clock_timestamp() < ends_at
            """,
            quantity, dealId, quantity,
        )
        if (took == 0) {
            conn.rollback(savepoint)
            return if (isLive(conn, dealId)) {
                conflict(ErrorCode.SOLD_OUT, "Not enough units left for this quantity")
            } else {
                conflict(ErrorCode.DEAL_NOT_LIVE, "The deal is not live")
            }
        }

        val claim = conn.queryOne("SELECT $CLAIM_COLUMNS FROM claims c WHERE c.id = ?", claimId) { it.toClaim() }!!
        return ClaimOutcome(HttpStatusCode.Created.value, Json.encodeToString(ClaimResponse.serializer(), claim), replayed = false)
    }

    private fun isLive(conn: Connection, dealId: UUID): Boolean = conn.queryOne(
        "SELECT starts_at <= clock_timestamp() AND clock_timestamp() < ends_at FROM deals WHERE id = ?",
        dealId,
    ) { it.getBoolean(1) }!!

    private fun conflict(code: ErrorCode, message: String) = ClaimOutcome(
        code.status.value,
        Json.encodeToString(ErrorBody.serializer(), errorBody(code, message)),
        replayed = false,
    )
}

fun Route.claimRoutes(db: Db, auth: Auth, claims: ClaimService) {
    post("/v1/deals/{dealId}/claims") {
        val caller = auth.authenticate(call)
        caller.requireRole(Role.CUSTOMER)

        val key = call.request.headers["Idempotency-Key"]
        if (key == null || !IDEMPOTENCY_KEY.matches(key)) {
            fail(ErrorCode.IDEMPOTENCY_KEY_INVALID, "Idempotency-Key must be 1-64 characters of A-Z, a-z, 0-9, - or _")
        }
        val body = parseJsonObject(call.receiveLimitedBytes())
        val quantity = body.requiredLong("quantity").requireIn(1L..3L, "quantity").toInt()
        val dealId = call.parameters["dealId"]?.toUuidOrNull() ?: fail(ErrorCode.NOT_FOUND, "Deal not found")

        val outcome = claims.claim(caller.userId, dealId, quantity, key)
        if (outcome.replayed) call.response.header("Idempotent-Replayed", "true")
        call.respondText(outcome.body, ContentType.Application.Json, HttpStatusCode.fromValue(outcome.statusCode))
    }

    get("/v1/claims/{claimId}") {
        val caller = auth.authenticate(call)
        val claimId = call.parameters["claimId"]?.toUuidOrNull() ?: fail(ErrorCode.NOT_FOUND, "Claim not found")

        val (claim, restaurantId) = db.tx { conn ->
            conn.queryOne(
                "SELECT $CLAIM_COLUMNS, d.restaurant_id FROM claims c JOIN deals d ON d.id = c.deal_id WHERE c.id = ?",
                claimId,
            ) { it.toClaim() to it.uuid("restaurant_id") }
        } ?: fail(ErrorCode.NOT_FOUND, "Claim not found")

        // Anyone who may not see the claim gets the same 404 as for a missing one, so IDs can't be probed.
        val visible = when (caller.role) {
            Role.CUSTOMER -> claim.customerId == caller.userId.toString()
            Role.RESTAURANT_ADMIN -> restaurantId == caller.restaurantId
        }
        if (!visible) fail(ErrorCode.NOT_FOUND, "Claim not found")
        call.respond(claim)
    }
}
