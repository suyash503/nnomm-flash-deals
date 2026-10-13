package com.nnomm.deals

import com.nnomm.deals.TestEnv.claimAsync
import com.nnomm.deals.TestEnv.claimCount
import com.nnomm.deals.TestEnv.createLiveDeal
import com.nnomm.deals.TestEnv.customerToken
import com.nnomm.deals.TestEnv.instances
import com.nnomm.deals.TestEnv.stockState
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrencyTest {

    @Test
    fun `250 concurrent claims across both instances never oversell a 50-unit deal`() {
        val dealId = createLiveDeal(stock = 50)

        val responses = (0 until 250).map { i ->
            claimAsync(instances[i % 2], dealId, customerToken(), UUID.randomUUID().toString())
        }.map { it.join() }

        val created = responses.count { it.status == 201 }
        val soldOut = responses.count { it.status == 409 && it.errorCode == "SOLD_OUT" }
        assertEquals(50, created, "exactly the stock should be sold")
        assertEquals(200, soldOut, "everyone else should get SOLD_OUT, got: ${responses.filter { it.status != 201 }.groupBy { it.status }.keys}")

        val state = stockState(dealId)
        assertEquals(0, state.stockAvailable)
        assertEquals(50, state.unitsHeld)
        assertEquals(state.stockTotal, state.stockAvailable + state.unitsHeld)
    }

    @Test
    fun `concurrent claims with mixed quantities keep stock_available plus units held equal to stock_total`() {
        val dealId = createLiveDeal(stock = 50)

        val responses = (0 until 200).map { i ->
            claimAsync(instances[i % 2], dealId, customerToken(), UUID.randomUUID().toString(), quantity = 1 + i % 3)
        }.map { it.join() }

        assertTrue(responses.all { it.status == 201 || it.errorCode == "SOLD_OUT" })
        val state = stockState(dealId)
        assertTrue(state.stockAvailable >= 0)
        assertTrue(state.unitsHeld <= state.stockTotal)
        assertEquals(state.stockTotal, state.stockAvailable + state.unitsHeld)
        // With quantities of 1-3 and 200 requests, the deal must end fully sold or with fewer than 3 units left.
        assertTrue(state.stockAvailable < 3, "left ${state.stockAvailable} units unsold")
    }

    @Test
    fun `20 concurrent requests with one Idempotency-Key create exactly one claim`() {
        val dealId = createLiveDeal(stock = 50)
        val customer = UUID.randomUUID()
        val token = customerToken(customer)
        val key = "retry-" + UUID.randomUUID()

        val responses = (0 until 20).map { i -> claimAsync(instances[i % 2], dealId, token, key) }.map { it.join() }

        assertEquals(1, claimCount(dealId, customer))
        // Every response is either the original 201 (first or replayed) or "still in progress".
        assertTrue(responses.all { it.status == 201 || it.errorCode == "REQUEST_IN_PROGRESS" }, responses.map { it.status }.toString())
        val claimIds = responses.filter { it.status == 201 }.map { it.str("id") }.toSet()
        assertEquals(1, claimIds.size, "all 201s must describe the same claim")
        assertEquals(1, responses.count { it.status == 201 && it.header("Idempotent-Replayed") == null })
        assertEquals(49, stockState(dealId).stockAvailable)
    }

    @Test
    fun `one customer claiming concurrently with different keys gets exactly one claim`() {
        val dealId = createLiveDeal(stock = 50)
        val customer = UUID.randomUUID()
        val token = customerToken(customer)

        val responses = (0 until 20).map { i ->
            claimAsync(instances[i % 2], dealId, token, "key-$i-" + UUID.randomUUID())
        }.let { CompletableFuture.allOf(*it.toTypedArray()).join(); it.map { f -> f.join() } }

        assertEquals(1, responses.count { it.status == 201 })
        assertEquals(19, responses.count { it.status == 409 && it.errorCode == "ALREADY_CLAIMED" })
        assertEquals(1, claimCount(dealId, customer))
        assertEquals(49, stockState(dealId).stockAvailable)
    }
}
