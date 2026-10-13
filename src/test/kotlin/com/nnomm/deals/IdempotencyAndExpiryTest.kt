package com.nnomm.deals

import com.nnomm.deals.TestEnv.bearer
import com.nnomm.deals.TestEnv.claim
import com.nnomm.deals.TestEnv.claimCount
import com.nnomm.deals.TestEnv.createLiveDeal
import com.nnomm.deals.TestEnv.customerToken
import com.nnomm.deals.TestEnv.eventually
import com.nnomm.deals.TestEnv.instances
import com.nnomm.deals.TestEnv.request
import com.nnomm.deals.TestEnv.shortTtlInstances
import com.nnomm.deals.TestEnv.stockState
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class IdempotencyAndExpiryTest {

    @Test
    fun `a retry replays the original response with Idempotent-Replayed, on either instance`() {
        val dealId = createLiveDeal()
        val token = customerToken()
        val first = claim(instances[0], dealId, token, key = "order-1", quantity = 2)
        val retry = claim(instances[1], dealId, token, key = "order-1", quantity = 2)

        assertEquals(201, first.status)
        assertNull(first.header("Idempotent-Replayed"))
        assertEquals(201, retry.status)
        assertEquals("true", retry.header("Idempotent-Replayed"))
        assertEquals(first.body, retry.body)
        assertEquals(19800, first.json["amount_paise"].toString().toLong())
        assertEquals(48, stockState(dealId).stockAvailable)
    }

    @Test
    fun `the same key for a different quantity or deal is 422`() {
        val dealId = createLiveDeal()
        val otherDeal = createLiveDeal()
        val token = customerToken()
        claim(instances[0], dealId, token, key = "k1", quantity = 1)

        assertEquals("IDEMPOTENCY_KEY_REUSED", claim(instances[0], dealId, token, key = "k1", quantity = 2).errorCode)
        assertEquals("IDEMPOTENCY_KEY_REUSED", claim(instances[1], otherDeal, token, key = "k1", quantity = 1).errorCode)
        assertEquals(422, claim(instances[1], otherDeal, token, key = "k1", quantity = 1).status)
    }

    @Test
    fun `keys are scoped to the customer`() {
        val dealId = createLiveDeal()
        val a = claim(instances[0], dealId, customerToken(), key = "shared-key")
        val b = claim(instances[1], dealId, customerToken(), key = "shared-key")
        assertEquals(201, a.status)
        assertEquals(201, b.status)
        assertNotEquals(a.str("id"), b.str("id"))
    }

    @Test
    fun `missing or malformed keys are 400`() {
        val dealId = createLiveDeal()
        val token = customerToken()
        val noKey = request("POST", "${instances[0]}/v1/deals/$dealId/claims", """{"quantity":1}""", bearer(token))
        assertEquals(400, noKey.status)
        assertEquals("IDEMPOTENCY_KEY_INVALID", noKey.errorCode)
        for (bad in listOf("", "a".repeat(65), "has space", "semi;colon", "dot.key")) {
            assertEquals("IDEMPOTENCY_KEY_INVALID", claim(instances[0], dealId, token, key = bad).errorCode, "key '$bad'")
        }
        assertEquals(201, claim(instances[0], dealId, token, key = "A-z_09-" + "x".repeat(57)).status) // 64 chars is fine
    }

    @Test
    fun `a 400 is not stored, so the key can still be used`() {
        val dealId = createLiveDeal()
        val token = customerToken()
        assertEquals("VALIDATION_FAILED", claim(instances[0], dealId, token, key = "k", quantity = 4).errorCode)
        val ok = claim(instances[0], dealId, token, key = "k", quantity = 1)
        assertEquals(201, ok.status)
        assertNull(ok.header("Idempotent-Replayed"))
    }

    @Test
    fun `a replayed SOLD_OUT stays SOLD_OUT after units free up, and a new key can succeed`() {
        val dealId = createLiveDeal(stock = 1)
        claim(shortTtlInstances[0], dealId, customerToken()) // takes the only unit, expires in 2 s
        val token = customerToken()
        val soldOut = claim(instances[0], dealId, token, key = "late")
        assertEquals("SOLD_OUT", soldOut.errorCode)

        eventually { stockState(dealId).stockAvailable == 1 }
        val replay = claim(instances[1], dealId, token, key = "late")
        assertEquals(409, replay.status)
        assertEquals("SOLD_OUT", replay.errorCode)
        assertEquals("true", replay.header("Idempotent-Replayed"))
        assertEquals(201, claim(instances[1], dealId, token, key = "late-2").status)
    }

    @Test
    fun `claims only succeed while the deal is live`() {
        val future = createLiveDeal(startsAt = Instant.now().plusSeconds(3600), endsAt = Instant.now().plusSeconds(7200))
        val ended = createLiveDeal(startsAt = Instant.now().minusSeconds(7200), endsAt = Instant.now().minusSeconds(1))
        assertEquals("DEAL_NOT_LIVE", claim(instances[0], future, customerToken()).errorCode)
        assertEquals("DEAL_NOT_LIVE", claim(instances[1], ended, customerToken()).errorCode)
        assertEquals(0, claimCount(future) + claimCount(ended))
    }

    @Test
    fun `an expired claim shows EXPIRED, its units come back, and the customer may claim again`() {
        val dealId = createLiveDeal(stock = 5)
        val customer = UUID.randomUUID()
        val token = customerToken(customer)
        val created = claim(shortTtlInstances[0], dealId, token, quantity = 3)
        assertEquals("RESERVED", created.str("status"))
        assertEquals(2, stockState(dealId).stockAvailable)
        assertEquals("ALREADY_CLAIMED", claim(instances[0], dealId, token).errorCode)

        val expiresAt = Instant.parse(created.str("expires_at"))
        Thread.sleep(maxOf(0, expiresAt.toEpochMilli() - System.currentTimeMillis() + 50))

        // From expires_at on, the API never says RESERVED, even before the sweeper gets to it.
        val shown = request("GET", "${instances[1]}/v1/claims/${created.str("id")}", headers = bearer(token))
        assertEquals("EXPIRED", shown.str("status"))
        eventually(timeoutMillis = 10_000) { stockState(dealId).stockAvailable == 5 }

        assertEquals(201, claim(instances[1], dealId, token, quantity = 1).status)
        assertEquals(4, stockState(dealId).stockAvailable)
    }
}
