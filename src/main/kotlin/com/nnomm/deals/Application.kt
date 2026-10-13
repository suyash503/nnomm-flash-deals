package com.nnomm.deals

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.nnomm.deals")

fun main() {
    val config = Config.fromEnv()
    val db = Db.connect(config)
    embeddedServer(Netty, port = config.port) { module(config, db) }.start(wait = true)
}

fun Application.module(config: Config, db: Db) {
    val auth = Auth(config.jwtSecret)

    install(ContentNegotiation) { json() }
    install(CallLogging) {
        // Method, path and status only: never headers, so tokens and webhook signatures stay out of logs.
        format { call -> "${call.response.status()?.value} ${call.request.httpMethod.value} ${call.request.path()}" }
    }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.code.status, errorBody(e.code, e.message))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on ${call.request.httpMethod.value} ${call.request.path()}", e)
            call.respond(HttpStatusCode.InternalServerError, errorBody(ErrorCode.INTERNAL, "Something went wrong"))
        }
        status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respond(HttpStatusCode.NotFound, errorBody(ErrorCode.NOT_FOUND, "No such endpoint"))
        }
    }

    val expiry = Expiry(db)
    val claims = ClaimService(db, expiry, config.claimTtlSeconds)
    val payments = Payments(db, config.webhookSecret)

    routing {
        dealRoutes(db, auth)
        claimRoutes(db, auth, claims)
        webhookRoutes(payments)
    }

    // Each instance sweeps expired claims every second. Runs in the application's scope, so it stops with it.
    launch {
        while (isActive) {
            try {
                while (expiry.sweepAll() == Expiry.BATCH_SIZE) { /* more due claims waiting: go again */ }
            } catch (e: CancellationException) {
                throw e // the server is stopping
            } catch (e: Exception) {
                log.warn("Expiry sweep failed; retrying next tick", e)
            }
            delay(config.sweepIntervalMillis)
        }
    }
}
