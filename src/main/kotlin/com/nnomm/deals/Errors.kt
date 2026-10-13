package com.nnomm.deals

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** Every error the API returns, with the status code the brief assigns to it. */
enum class ErrorCode(val status: HttpStatusCode) {
    VALIDATION_FAILED(HttpStatusCode.BadRequest),
    IDEMPOTENCY_KEY_INVALID(HttpStatusCode.BadRequest),
    UNAUTHENTICATED(HttpStatusCode.Unauthorized),
    FORBIDDEN(HttpStatusCode.Forbidden),
    NOT_FOUND(HttpStatusCode.NotFound),
    SOLD_OUT(HttpStatusCode.Conflict),
    ALREADY_CLAIMED(HttpStatusCode.Conflict),
    DEAL_NOT_LIVE(HttpStatusCode.Conflict),
    REQUEST_IN_PROGRESS(HttpStatusCode.Conflict),
    PAYLOAD_TOO_LARGE(HttpStatusCode.PayloadTooLarge),
    IDEMPOTENCY_KEY_REUSED(HttpStatusCode.UnprocessableEntity),
    INTERNAL(HttpStatusCode.InternalServerError),
}

/** Thrown anywhere in a request; StatusPages turns it into the standard error body. */
class ApiException(val code: ErrorCode, override val message: String) : RuntimeException(message)

fun fail(code: ErrorCode, message: String): Nothing = throw ApiException(code, message)

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String)

fun errorBody(code: ErrorCode, message: String) = ErrorBody(ErrorDetail(code.name, message))
