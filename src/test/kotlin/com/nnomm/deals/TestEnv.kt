package com.nnomm.deals

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One real Postgres (Testcontainers) shared by all tests, and two pairs of real servers on it:
 * [instances] use the normal 10-minute TTL, [shortTtlInstances] a 2-second TTL for expiry tests.
 * Requests alternate between the two servers of a pair, so every test also exercises two instances.
 */
object TestEnv {
    const val JWT_SECRET = "test-jwt-secret-that-is-long-enough-for-hs256"
    const val WEBHOOK_SECRET = "test-webhook-secret"
    const val SHORT_TTL_SECONDS = 2L

    private val postgres = PostgreSQLContainer("postgres:16")
        .withCommand("postgres", "-c", "max_connections=300")
        .also { it.start() }

    val db: Db
    val instances: List<String>
    val shortTtlInstances: List<String>
    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    init {
        val base = Config(
            databaseUrl = postgres.jdbcUrl,
            databaseUser = postgres.username,
            databasePassword = postgres.password,
            jwtSecret = JWT_SECRET,
            webhookSecret = WEBHOOK_SECRET,
            claimTtlSeconds = 600,
            port = 0,
            sweepIntervalMillis = 250,
        )
        db = Db.connect(base)
        instances = List(2) { startServer(base) }
        shortTtlInstances = List(2) { startServer(base.copy(claimTtlSeconds = SHORT_TTL_SECONDS)) }
        Runtime.getRuntime().addShutdownHook(Thread { servers.forEach { it.stop(100, 1000) } })
    }

    private fun startServer(config: Config): String {
        val port = ServerSocket(0).use { it.localPort }
        // Each server gets its own pool, just like a separate process would.
        val serverDb = Db.connect(config.copy(port = port))
        servers += embeddedServer(Netty, port = port) { module(config, serverDb) }.start(wait = false)
        return "http://localhost:$port"
    }

    // ---- tokens ----

    fun token(
        sub: UUID,
        role: String,
        restaurantId: UUID? = null,
        expiresAt: Instant = Instant.now().plusSeconds(3600),
        algorithm: Algorithm = Algorithm.HMAC256(JWT_SECRET),
    ): String = JWT.create()
        .withSubject(sub.toString())
        .withClaim("role", role)
        .apply { if (restaurantId != null) withClaim("restaurant_id", restaurantId.toString()) }
        .withExpiresAt(expiresAt)
        .sign(algorithm)

    fun customerToken(customerId: UUID = UUID.randomUUID()) = token(customerId, "customer")
    fun adminToken(restaurantId: UUID, adminId: UUID = UUID.randomUUID()) = token(adminId, "restaurant_admin", restaurantId)

    // ---- webhooks ----

    fun sign(body: String, t: Long = Instant.now().epochSecond, secret: String = WEBHOOK_SECRET): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return "t=$t,v1=" + HexFormat.of().formatHex(mac.doFinal("$t.$body".toByteArray()))
    }

    fun paymentEvent(claimId: String, amountPaise: Long, type: String = "payment.succeeded", eventId: String = "evt_" + UUID.randomUUID()) =
        """{"event_id":"$eventId","type":"$type","claim_id":"$claimId","amount_paise":$amountPaise,"occurred_at":"${Instant.now()}"}"""

    // ---- HTTP ----

    val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    data class Resp(val status: Int, val body: String, val headers: Map<String, List<String>>) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
        fun str(field: String) = json[field]!!.jsonPrimitive.content
        val errorCode: String get() = json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    fun requestAsync(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): CompletableFuture<Resp> {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60))
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply { Resp(it.statusCode(), it.body(), it.headers().map()) }
    }

    fun request(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()): Resp =
        requestAsync(method, url, body, headers).join()

    fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

    // ---- fixtures ----

    /** Creates a deal that is live right now, through the API, and returns its id. */
    fun createLiveDeal(
        stock: Int = 50,
        pricePaise: Long = 9900,
        restaurantId: UUID = UUID.randomUUID(),
        startsAt: Instant = Instant.now().minusSeconds(60),
        endsAt: Instant = Instant.now().plusSeconds(3600),
        base: String = instances[0],
    ): String {
        val resp = request(
            "POST", "$base/v1/deals",
            """{"title":"Chicken biryani box","price_paise":$pricePaise,"stock_total":$stock,"starts_at":"$startsAt","ends_at":"$endsAt"}""",
            bearer(adminToken(restaurantId)),
        )
        check(resp.status == 201) { "Deal creation failed: ${resp.status} ${resp.body}" }
        return resp.str("id")
    }

    fun claimAsync(base: String, dealId: String, token: String, key: String, quantity: Int = 1) =
        requestAsync("POST", "$base/v1/deals/$dealId/claims", """{"quantity":$quantity}""", bearer(token) + ("Idempotency-Key" to key))

    fun claim(base: String, dealId: String, token: String, key: String = UUID.randomUUID().toString(), quantity: Int = 1) =
        claimAsync(base, dealId, token, key, quantity).join()

    // ---- direct database reads, to check invariants from the source of truth ----

    data class StockState(val stockTotal: Int, val stockAvailable: Int, val unitsHeld: Int, val activeClaims: Int)

    fun stockState(dealId: String): StockState = runBlocking {
        db.tx { conn ->
            conn.queryOne(
                """
                SELECT d.stock_total, d.stock_available,
                       COALESCE(sum(c.quantity) FILTER (WHERE c.status IN ('RESERVED','PAID','NEEDS_REVIEW')), 0) AS held,
                       count(c.id) FILTER (WHERE c.status IN ('RESERVED','PAID','NEEDS_REVIEW')) AS active
                FROM deals d LEFT JOIN claims c ON c.deal_id = d.id
                WHERE d.id = ? GROUP BY d.id
                """,
                UUID.fromString(dealId),
            ) { StockState(it.getInt("stock_total"), it.getInt("stock_available"), it.getInt("held"), it.getInt("active")) }!!
        }
    }

    fun claimCount(dealId: String, customerId: UUID? = null): Int = runBlocking {
        db.tx { conn ->
            conn.queryOne(
                "SELECT count(*) FROM claims WHERE deal_id = ? AND (CAST(? AS uuid) IS NULL OR customer_id = CAST(? AS uuid))",
                UUID.fromString(dealId), customerId, customerId,
            ) { it.getInt(1) }!!
        }
    }

    fun claimStatusInDb(claimId: String): String = runBlocking {
        db.tx { conn -> conn.queryOne("SELECT status FROM claims WHERE id = ?", UUID.fromString(claimId)) { it.getString(1) }!! }
    }

    /** Polls [check] until it's true or [timeoutMillis] passes. */
    fun eventually(timeoutMillis: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(100)
        }
        throw AssertionError("Condition not met within $timeoutMillis ms")
    }
}
