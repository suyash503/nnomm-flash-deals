package com.nnomm.deals

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import java.util.UUID

enum class Role(val claim: String) { CUSTOMER("customer"), RESTAURANT_ADMIN("restaurant_admin") }

/** Who is calling, taken from a verified JWT. restaurantId is set only for admins. */
data class Caller(val userId: UUID, val role: Role, val restaurantId: UUID?)

class Auth(jwtSecret: String) {
    // java-jwt checks the token's "alg" header equals HS256 before checking the signature,
    // so "none", RS256 and every other algorithm fail here. It also rejects tokens past "exp".
    private val verifier = JWT.require(Algorithm.HMAC256(jwtSecret)).build()

    fun authenticate(call: ApplicationCall): Caller {
        val header = call.request.headers[HttpHeaders.Authorization]
            ?: fail(ErrorCode.UNAUTHENTICATED, "Missing bearer token")
        if (!header.startsWith("Bearer ")) fail(ErrorCode.UNAUTHENTICATED, "Missing bearer token")

        val jwt = try {
            verifier.verify(header.removePrefix("Bearer ").trim())
        } catch (e: JWTVerificationException) {
            fail(ErrorCode.UNAUTHENTICATED, "Invalid or expired token")
        }
        // java-jwt accepts a token with no exp at all; we don't want tokens that never expire.
        if (jwt.expiresAtAsInstant == null) fail(ErrorCode.UNAUTHENTICATED, "Token has no expiry")

        val userId = jwt.subject?.toUuidOrNull() ?: fail(ErrorCode.UNAUTHENTICATED, "Token sub must be a UUID")
        val role = Role.entries.firstOrNull { it.claim == jwt.getClaim("role").asString() }
            ?: fail(ErrorCode.UNAUTHENTICATED, "Token role is not recognised")
        val restaurantId = if (role == Role.RESTAURANT_ADMIN) {
            jwt.getClaim("restaurant_id").asString()?.toUuidOrNull()
                ?: fail(ErrorCode.UNAUTHENTICATED, "Admin token needs a restaurant_id")
        } else {
            null
        }
        return Caller(userId, role, restaurantId)
    }
}

fun Caller.requireRole(role: Role) {
    if (this.role != role) fail(ErrorCode.FORBIDDEN, "This endpoint is not available to your role")
}

fun String.toUuidOrNull(): UUID? = try {
    UUID.fromString(this).takeIf { it.toString() == this.lowercase() }
} catch (e: IllegalArgumentException) {
    null
}
