package com.coppermux.cutools.core.network

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Availability of a decoded response body, used to report explicit results for
 * `Network.getResponseBody` instead of throwing uncaught exceptions.
 */
enum class ResponseBodyStatus {
    /** Body decoded successfully and is representable as UTF-8 text. */
    SUCCESS,

    /** Body decoded successfully but is binary, not UTF-8 text. */
    BINARY,

    /** The body cannot be decoded (invalid base64 / unsupported encoding). */
    UNSUPPORTED,

    /** The body is missing or has been evicted by Chrome. */
    UNAVAILABLE,

    /** A generic protocol failure occurred while retrieving the body. */
    FAILED,
}

/**
 * The outcome of a response-body retrieval, carrying either decoded text,
 * raw binary bytes, or an explicit non-success status.
 */
data class ResponseBodyResult(
    val status: ResponseBodyStatus,
    val text: String? = null,
    val bytes: ByteArray? = null,
    val error: String? = null,
)

/**
 * Decodes a `Network.getResponseBody` result payload.
 *
 * Chrome returns `{ body, base64Encoded }`. When `base64Encoded` is false the
 * body is already plain text. When true the body is base64-encoded: if the
 * decoded bytes are valid UTF-8 the result is [ResponseBodyStatus.SUCCESS]
 * text; otherwise it is reported as [ResponseBodyStatus.BINARY]. A missing
 * body yields [ResponseBodyStatus.UNAVAILABLE], and decoding failures yield
 * [ResponseBodyStatus.UNSUPPORTED] rather than an uncaught exception.
 */
object ResponseBodyDecoder {

    fun decode(body: String?, base64Encoded: Boolean): ResponseBodyResult {
        if (body == null) {
            return ResponseBodyResult(
                status = ResponseBodyStatus.UNAVAILABLE,
                error = "Response body is missing",
            )
        }
        if (!base64Encoded) {
            return ResponseBodyResult(status = ResponseBodyStatus.SUCCESS, text = body)
        }
        return try {
            val bytes = Base64.getDecoder().decode(body)
            decodeBinary(bytes)
        } catch (e: Exception) {
            ResponseBodyResult(
                status = ResponseBodyStatus.UNSUPPORTED,
                error = "Invalid base64 response body: ${e.message}",
            )
        }
    }

    private fun decodeBinary(bytes: ByteArray): ResponseBodyResult {
        val decoded = String(bytes, StandardCharsets.UTF_8)
        val isText = !decoded.contains("\uFFFD")
        return if (isText) {
            ResponseBodyResult(status = ResponseBodyStatus.SUCCESS, text = decoded)
        } else {
            ResponseBodyResult(status = ResponseBodyStatus.BINARY, bytes = bytes)
        }
    }
}