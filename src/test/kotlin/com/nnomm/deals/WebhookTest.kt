package com.nnomm.deals

import com.nnomm.deals.TestEnv.bearer
import com.nnomm.deals.TestEnv.claim
import com.nnomm.deals.TestEnv.claimStatusInDb
import com.nnomm.deals.TestEnv.createLiveDeal
import com.nnomm.deals.TestEnv.customerToken
import com.nnomm.deals.TestEnv.eventually
import com.nnomm.deals.TestEnv.instances
import com.nnomm.deals.TestEnv.paymentEvent
import com.nnomm.deals.TestEnv.request
import com.nnomm.deals.TestEnv.requestAsync
import com.nnomm.deals.TestEnv.shortTtlInstances
import com.nnomm.deals.TestEnv.sign
import com.nnomm.deals.TestEnv.stockState
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebhookTest {

    private fun webhook(body: String, signature: String? = sign(body), base: String = instances[0]) =
        request("POST", "$base/v1/webhooks/payments", body, signature?.let { mapOf("X-Payment-Signature" to it) } ?: emptyMap())

    /** A fresh RESERVED claim for one unit on a 50-unit deal priced 9900. Returns (dealId, claimId). */
    private fun reservedClaim(base: String = instances[0]): Pair<String, String> {
        val dealId = createLiveDeal(stock = 50, pricePaise = 9900)
        val resp = claim(base, dealId, customerToken())
        assertEquals(201, resp.status, resp.body)
        return dealId to resp.str("id")
    }

    @Test
    fun `a matching payment marks the claim PAID and keeps its units`() {
        val (dealId, claimId) = reservedClaim()
        val resp = webhook(paymentEvent(claimId, 9900))
        assertEquals(200, resp.status)
        assertEquals("PAID", claimStatusInDb(claimId))
        assertEquals(49, stockState(dealId).stockAvailable)
    }

    @Test
    fun `bad signatures are rejected with 401`() {
        val (_, claimId) = reservedClaim()
        val body = paymentEvent(claimId, 9900)
        val now = Instant.now().epochSecond

        val cases = mapOf(
            "missing header" to null,
            "malformed header" to "garbage",
            "wrong secret" to sign(body, secret = "not-the-secret"),
            "stale timestamp" to sign(body, t = now - 301),
            "future timestamp" to sign(body, t = now + 301),
            "signature of a different body" to sign(body.replace("9900", "1")),
            "v1 not hex" to "t=$now,v1=" + "z".repeat(64),
        )
        for ((name, signature) in cases) {
            val resp = webhook(body, signature)
            assertEquals(401, resp.status, name)
            assertEquals("UNAUTHENTICATED", resp.errorCode, name)
        }
        assertEquals("RESERVED", claimStatusInDb(claimId))
    }

    @Test
    fun `the same event delivered many times at once is applied exactly once`() {
        val (dealId, claimId) = reservedClaim()
        val body = paymentEvent(claimId, 9900)

        val responses = (0 until 20).map { i ->
            requestAsync("POST", "${instances[i % 2]}/v1/webhooks/payments", body, mapOf("X-Payment-Signature" to sign(body)))
        }.map { it.join() }

        assertTrue(responses.all { it.status == 200 })
        assertEquals(1, responses.count { it.str("outcome") == "PAID" })
        assertEquals(19, responses.count { it.str("outcome") == "DUPLICATE_EVENT" })
        assertEquals("PAID", claimStatusInDb(claimId))
        assertEquals(49, stockState(dealId).stockAvailable)
    }

    @Test
    fun `a failed event arriving after success does not undo the payment`() {
        val (_, claimId) = reservedClaim()
        assertEquals(200, webhook(paymentEvent(claimId, 9900, type = "payment.succeeded")).status)
        assertEquals(200, webhook(paymentEvent(claimId, 9900, type = "payment.failed")).status)
        assertEquals("PAID", claimStatusInDb(claimId))
    }

    @Test
    fun `a failed payment changes nothing and the customer can still pay`() {
        val (_, claimId) = reservedClaim()
        assertEquals("NO_CHANGE", webhook(paymentEvent(claimId, 9900, type = "payment.failed")).str("outcome"))
        assertEquals("RESERVED", claimStatusInDb(claimId))
        assertEquals("PAID", webhook(paymentEvent(claimId, 9900)).str("outcome"))
    }

    @Test
    fun `a wrong amount sends the claim to NEEDS_REVIEW and keeps the units held`() {
        val (dealId, claimId) = reservedClaim()
        assertEquals("NEEDS_REVIEW", webhook(paymentEvent(claimId, 100)).str("outcome"))
        assertEquals("NEEDS_REVIEW", claimStatusInDb(claimId))
        assertEquals(49, stockState(dealId).stockAvailable)
    }

    @Test
    fun `a second successful payment with a new event id leaves a settled claim alone`() {
        val (_, claimId) = reservedClaim()
        webhook(paymentEvent(claimId, 9900))
        val second = webhook(paymentEvent(claimId, 9900))
        assertEquals(200, second.status)
        assertEquals("EXTRA_PAYMENT_TO_REFUND", second.str("outcome"))
        assertEquals("PAID", claimStatusInDb(claimId))
    }

    @Test
    fun `an event for an unknown claim is acknowledged and recorded`() {
        val resp = webhook(paymentEvent(UUID.randomUUID().toString(), 9900))
        assertEquals(200, resp.status)
        assertEquals("UNKNOWN_CLAIM", resp.str("outcome"))
    }

    @Test
    fun `a payment after expiry ends REFUND_PENDING and never takes the units back`() {
        val dealId = createLiveDeal(stock = 50)
        val claimId = claim(shortTtlInstances[0], dealId, customerToken()).str("id")
        eventually { stockState(dealId).stockAvailable == 50 } // swept: units are back on sale

        val resp = webhook(paymentEvent(claimId, 9900))
        assertEquals("REFUND_PENDING", resp.str("outcome"))
        assertEquals("REFUND_PENDING", claimStatusInDb(claimId))
        assertEquals(50, stockState(dealId).stockAvailable)
    }

    @Test
    fun `a payment past expires_at that beats the sweeper also returns the units exactly once`() {
        val dealId = createLiveDeal(stock = 50)
        val created = claim(shortTtlInstances[0], dealId, customerToken())
        val expiresAt = Instant.parse(created.str("expires_at"))
        // Send the payment the moment the claim is due; the sweeper may or may not have run yet.
        Thread.sleep(maxOf(0, expiresAt.toEpochMilli() - System.currentTimeMillis() + 20))

        val resp = webhook(paymentEvent(created.str("id"), 9900))
        assertEquals("REFUND_PENDING", resp.str("outcome"))
        Thread.sleep(600) // let the sweepers run a couple of times
        val state = stockState(dealId)
        assertEquals(50, state.stockAvailable)
        assertEquals(0, state.unitsHeld)
    }

    @Test
    fun `payments racing expiry leave every claim PAID or REFUND_PENDING and stock exact`() {
        val dealId = createLiveDeal(stock = 50)
        val claims = (0 until 40).map { i -> claim(shortTtlInstances[i % 2], dealId, customerToken()) }
        assertTrue(claims.all { it.status == 201 })
        val expiresAt = claims.maxOf { Instant.parse(it.str("expires_at")) }

        // Aim the payments at the expiry instant: spread from 200 ms before to 200 ms after it, while both
        // instances' sweepers run. Each event is also sent twice, concurrently, to both instances.
        Thread.sleep(maxOf(0, expiresAt.toEpochMilli() - System.currentTimeMillis() - 200))
        val futures = claims.flatMapIndexed { i, c ->
            Thread.sleep(10)
            val body = paymentEvent(c.str("id"), 9900)
            instances.map { base ->
                requestAsync("POST", "$base/v1/webhooks/payments", body, mapOf("X-Payment-Signature" to sign(body)))
            }
        }
        assertTrue(futures.map { it.join() }.all { it.status == 200 })

        eventually {
            claims.none { claimStatusInDb(it.str("id")) == "RESERVED" }
        }
        val statuses = claims.map { claimStatusInDb(it.str("id")) }
        assertTrue(statuses.all { it == "PAID" || it == "REFUND_PENDING" }, statuses.toString())

        val paid = statuses.count { it == "PAID" }
        val state = stockState(dealId)
        assertEquals(paid, state.unitsHeld)
        assertEquals(50 - paid, state.stockAvailable)
        assertEquals(state.stockTotal, state.stockAvailable + state.unitsHeld)
    }

    @Test
    fun `the webhook needs no JWT but a customer token does not replace the signature`() {
        val (_, claimId) = reservedClaim()
        val body = paymentEvent(claimId, 9900)
        val resp = request("POST", "${instances[0]}/v1/webhooks/payments", body, bearer(customerToken()))
        assertEquals(401, resp.status)
    }
}
