package com.nnomm.deals

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.deals.TestEnv.adminToken
import com.nnomm.deals.TestEnv.bearer
import com.nnomm.deals.TestEnv.claim
import com.nnomm.deals.TestEnv.createLiveDeal
import com.nnomm.deals.TestEnv.customerToken
import com.nnomm.deals.TestEnv.instances
import com.nnomm.deals.TestEnv.request
import com.nnomm.deals.TestEnv.token
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthAndValidationTest {
    private val base = instances[0]

    private fun getDeal(dealId: String, authorization: String?) =
        request("GET", "$base/v1/deals/$dealId", headers = authorization?.let { mapOf("Authorization" to it) } ?: emptyMap())

    @Test
    fun `bad tokens are rejected with 401`() {
        val dealId = createLiveDeal()
        val user = UUID.randomUUID()
        val good = customerToken(user)
        val tampered = good.split('.').let { (h, _, s) ->
            val payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("""{"sub":"$user","role":"restaurant_admin","restaurant_id":"${UUID.randomUUID()}","exp":9999999999}""".toByteArray())
            "$h.$payload.$s"
        }
        val noExp = JWT.create().withSubject(user.toString()).withClaim("role", "customer").sign(Algorithm.HMAC256(TestEnv.JWT_SECRET))

        val cases = mapOf(
            "no header" to null,
            "not bearer" to "Basic abc",
            "garbage" to "Bearer not.a.jwt",
            "expired" to "Bearer " + token(user, "customer", expiresAt = Instant.now().minusSeconds(5)),
            "wrong secret" to "Bearer " + token(user, "customer", algorithm = Algorithm.HMAC256("some-other-secret-that-is-long-enough")),
            "alg none" to "Bearer " + token(user, "customer", algorithm = Algorithm.none()),
            "HS512 with our secret" to "Bearer " + token(user, "customer", algorithm = Algorithm.HMAC512(TestEnv.JWT_SECRET)),
            "tampered payload" to "Bearer $tampered",
            "no exp" to "Bearer $noExp",
            "unknown role" to "Bearer " + token(user, "superuser"),
            "admin without restaurant" to "Bearer " + token(user, "restaurant_admin"),
        )
        for ((name, header) in cases) {
            val resp = getDeal(dealId, header)
            assertEquals(401, resp.status, name)
            assertEquals("UNAUTHENTICATED", resp.errorCode, name)
        }
        assertEquals(200, getDeal(dealId, "Bearer $good").status)
    }

    @Test
    fun `roles are enforced with 403`() {
        val dealId = createLiveDeal()
        val asCustomer = request(
            "POST", "$base/v1/deals",
            """{"title":"x","price_paise":100,"stock_total":1,"starts_at":"2026-10-20T12:00:00Z","ends_at":"2026-10-20T14:00:00Z"}""",
            bearer(customerToken()),
        )
        assertEquals(403, asCustomer.status)
        assertEquals("FORBIDDEN", asCustomer.errorCode)
        assertEquals("FORBIDDEN", claim(base, dealId, adminToken(UUID.randomUUID())).errorCode)
    }

    @Test
    fun `a deal is created for the restaurant in the token, not the body`() {
        val restaurant = UUID.randomUUID()
        val resp = request(
            "POST", "$base/v1/deals",
            """{"title":"Chicken biryani box","price_paise":9900,"stock_total":50,"restaurant_id":"${UUID.randomUUID()}",
               "starts_at":"2026-10-20T12:00:00Z","ends_at":"2026-10-20T14:00:00Z"}""",
            bearer(adminToken(restaurant)),
        )
        assertEquals(201, resp.status)
        assertEquals(restaurant.toString(), resp.str("restaurant_id"))
        assertEquals("50", resp.str("stock_available"))
        assertEquals("2026-10-20T12:00:00Z", resp.str("starts_at"))
    }

    @Test
    fun `claims are visible only to their customer and their restaurant's admin`() {
        val restaurant = UUID.randomUUID()
        val dealId = createLiveDeal(restaurantId = restaurant)
        val owner = UUID.randomUUID()
        val claimId = claim(base, dealId, customerToken(owner)).str("id")
        fun get(token: String) = request("GET", "${instances[1]}/v1/claims/$claimId", headers = bearer(token))

        assertEquals(200, get(customerToken(owner)).status)
        assertEquals(200, get(adminToken(restaurant)).status)
        assertEquals("NOT_FOUND", get(customerToken()).errorCode)
        assertEquals(404, get(adminToken(UUID.randomUUID())).status)
        assertEquals(404, request("GET", "$base/v1/claims/${UUID.randomUUID()}", headers = bearer(customerToken(owner))).status)
    }

    @Test
    fun `deal input limits are validated`() {
        val admin = bearer(adminToken(UUID.randomUUID()))
        fun create(title: String = "\"ok\"", price: String = "100", stock: String = "10",
                   starts: String = "\"2026-10-20T12:00:00Z\"", ends: String = "\"2026-10-20T14:00:00Z\"") =
            request("POST", "$base/v1/deals",
                """{"title":$title,"price_paise":$price,"stock_total":$stock,"starts_at":$starts,"ends_at":$ends}""", admin)

        val bad = mapOf(
            "empty title" to create(title = "\"\""),
            "101-char title" to create(title = "\"${"a".repeat(101)}\""),
            "price 0" to create(price = "0"),
            "price over limit" to create(price = "1000001"),
            "price as string" to create(price = "\"100\""),
            "fractional price" to create(price = "99.5"),
            "stock 0" to create(stock = "0"),
            "stock over limit" to create(stock = "10001"),
            "starts after ends" to create(starts = "\"2026-10-20T15:00:00Z\""),
            "starts equals ends" to create(starts = "\"2026-10-20T14:00:00Z\""),
            "not a timestamp" to create(starts = "\"tomorrow\""),
        )
        for ((name, resp) in bad) {
            assertEquals(400, resp.status, "$name: ${resp.body}")
            assertEquals("VALIDATION_FAILED", resp.errorCode, name)
        }
        assertEquals(201, create(title = "\"${"a".repeat(100)}\"", price = "1000000", stock = "10000").status)
        assertEquals("VALIDATION_FAILED", request("POST", "$base/v1/deals", "not json", admin).errorCode)
    }

    @Test
    fun `bodies over 16 KB are 413`() {
        val big = """{"title":"${"a".repeat(17 * 1024)}"}"""
        val resp = request("POST", "$base/v1/deals", big, bearer(adminToken(UUID.randomUUID())))
        assertEquals(413, resp.status)
        assertEquals("PAYLOAD_TOO_LARGE", resp.errorCode)
    }

    @Test
    fun `unknown deals and endpoints are 404 in the standard shape`() {
        assertEquals("NOT_FOUND", getDeal(UUID.randomUUID().toString(), "Bearer " + customerToken()).errorCode)
        assertEquals("NOT_FOUND", getDeal("not-a-uuid", "Bearer " + customerToken()).errorCode)
        assertEquals("NOT_FOUND", claim(base, UUID.randomUUID().toString(), customerToken()).errorCode)
        assertEquals("NOT_FOUND", request("GET", "$base/v1/nothing-here").errorCode)
    }
}
