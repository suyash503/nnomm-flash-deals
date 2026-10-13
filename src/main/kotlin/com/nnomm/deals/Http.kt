package com.nnomm.deals

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

const val MAX_BODY_BYTES = 16 * 1024

/**
 * Reads the raw request body, refusing anything over 16 KB with 413.
 * Checks Content-Length up front, and also counts bytes so a chunked body can't sneak past.
 */
suspend fun ApplicationCall.receiveLimitedBytes(): ByteArray {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > MAX_BODY_BYTES) tooLarge()
    val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) tooLarge()
    return bytes
}

private fun tooLarge(): Nothing = fail(ErrorCode.PAYLOAD_TOO_LARGE, "Body is over 16 KB")

fun parseJsonObject(bytes: ByteArray): JsonObject {
    val element = try {
        Json.parseToJsonElement(bytes.decodeToString())
    } catch (e: SerializationException) {
        fail(ErrorCode.VALIDATION_FAILED, "Body is not valid JSON")
    }
    return element as? JsonObject ?: fail(ErrorCode.VALIDATION_FAILED, "Body must be a JSON object")
}

// Strict field readers: a number sent as a string, or a fraction, is a validation error, not a coercion.

private fun JsonObject.field(name: String): JsonPrimitive {
    val value: JsonElement = this[name] ?: fail(ErrorCode.VALIDATION_FAILED, "$name is required")
    return value as? JsonPrimitive ?: fail(ErrorCode.VALIDATION_FAILED, "$name has the wrong type")
}

fun JsonObject.requiredString(name: String): String {
    val p = field(name)
    if (!p.isString) fail(ErrorCode.VALIDATION_FAILED, "$name must be a string")
    return p.content
}

fun JsonObject.requiredLong(name: String): Long {
    val p = field(name)
    if (p.isString) fail(ErrorCode.VALIDATION_FAILED, "$name must be an integer")
    return p.longOrNull ?: fail(ErrorCode.VALIDATION_FAILED, "$name must be an integer")
}

fun JsonObject.requiredInstant(name: String): Instant {
    val text = requiredString(name)
    return try {
        OffsetDateTime.parse(text).toInstant()
    } catch (e: DateTimeParseException) {
        fail(ErrorCode.VALIDATION_FAILED, "$name must be an ISO 8601 timestamp with a zone, e.g. 2026-10-20T12:00:00Z")
    }
}

fun Long.requireIn(range: LongRange, name: String): Long {
    if (this !in range) fail(ErrorCode.VALIDATION_FAILED, "$name must be between ${range.first} and ${range.last}")
    return this
}
