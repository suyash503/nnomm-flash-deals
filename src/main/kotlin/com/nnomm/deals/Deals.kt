package com.nnomm.deals

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID

fun Route.dealRoutes(db: Db, auth: Auth) {
    post("/v1/deals") {
        val caller = auth.authenticate(call)
        caller.requireRole(Role.RESTAURANT_ADMIN)

        val body = parseJsonObject(call.receiveLimitedBytes())
        val title = body.requiredString("title")
        if (title.isEmpty() || title.codePointCount(0, title.length) > 100) {
            fail(ErrorCode.VALIDATION_FAILED, "title must be 1 to 100 characters")
        }
        val pricePaise = body.requiredLong("price_paise").requireIn(1L..1_000_000L, "price_paise")
        val stockTotal = body.requiredLong("stock_total").requireIn(1L..10_000L, "stock_total").toInt()
        val startsAt = body.requiredInstant("starts_at")
        val endsAt = body.requiredInstant("ends_at")
        if (!startsAt.isBefore(endsAt)) fail(ErrorCode.VALIDATION_FAILED, "starts_at must be before ends_at")

        val deal = db.tx { conn ->
            // restaurant_id always comes from the token, never the body: admins can only create for their own restaurant.
            conn.queryOne(
                """
                INSERT INTO deals (id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING $DEAL_COLUMNS
                """,
                UUID.randomUUID(), caller.restaurantId, title, pricePaise, stockTotal, stockTotal, startsAt, endsAt,
            ) { it.toDeal() }!!
        }
        call.respond(HttpStatusCode.Created, deal)
    }

    get("/v1/deals/{dealId}") {
        auth.authenticate(call)
        val dealId = call.parameters["dealId"]?.toUuidOrNull() ?: fail(ErrorCode.NOT_FOUND, "Deal not found")
        val deal = db.tx { conn ->
            conn.queryOne("SELECT $DEAL_COLUMNS FROM deals WHERE id = ?", dealId) { it.toDeal() }
        } ?: fail(ErrorCode.NOT_FOUND, "Deal not found")
        call.respond(deal)
    }
}
