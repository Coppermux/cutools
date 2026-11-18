package com.coppermux.cutools.core.model

import com.coppermux.cutools.core.kernel.EventProvenance
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Type alias for arbitrary JSON values. Moshi deserializes JSON objects as
 * `Map<String, Any?>`, arrays as `List<Any?>`, and primitives as their boxed types.
 * Using this type preserves unknown protocol fields rather than discarding them.
 */
typealias JsonValue = Any?

/**
 * Represents an outgoing CDP command, which will be serialized and sent to Chrome.
 *
 * @property id Unique command/request ID assigned by [CommandDispatcher].
 * @property method CDP method (e.g., "Runtime.evaluate").
 * @property params Method parameters as a JSON object. May be null if the method has no params.
 * @property sessionId Optional session ID for commands sent to an attached target session.
 */
data class Command(
    @Json(name = "id") val id: Long,
    @Json(name = "method") val method: String,
    @Json(name = "params") val params: JsonValue = null,
    @Json(name = "sessionId") val sessionId: String? = null,
)

/**
 * Represents a CDP response to a command. Correlated by [id].
 *
 * If [error] is non-null, this is a protocol-level error response.
 *
 * @property id The command ID being responded to.
 * @property result The result payload (only present on success).
 * @property error The error payload (only present on failure).
 * @property sessionId Optional session ID if the command was sent on a specific session.
 */
data class Response(
    @Json(name = "id") val id: Long,
    @Json(name = "result") val result: JsonValue = null,
    @Json(name = "error") val error: ProtocolError? = null,
    @Json(name = "sessionId") val sessionId: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * Represents a protocol error returned by Chrome.
 */
data class ProtocolError(
    @Json(name = "code") val code: Int,
    @Json(name = "message") val message: String,
    @Json(name = "data") val data: JsonValue = null,
)

/**
 * Represents a CDP event fired by Chrome.
 *
 * @property method The event method (e.g., "Target.targetCreated").
 * @property params The event parameters as a JSON object.
 * @property sessionId Optional session ID associated with the event.
 * @property targetId Optional target ID associated with the event (some events include this).
 */
data class Event(
    @Json(name = "method") val method: String,
    @Json(name = "params") val params: JsonValue = null,
    @Json(name = "sessionId") val sessionId: String? = null,
    @Json(name = "targetId") val targetId: String? = null,
    val provenance: EventProvenance? = null,
)

/**
 * Result of dispatching a command — either a successful [Response] or a [CdpException].
 */
sealed interface CommandResult {
    data class Success(val response: Response) : CommandResult
    data class Failure(val error: com.coppermux.cutools.core.error.CdpException) : CommandResult {
        constructor(throwable: Throwable) : this(
            (throwable as? com.coppermux.cutools.core.error.CdpException)
                ?: com.coppermux.cutools.core.error.CommandException(
                    throwable.message ?: "Unknown command error",
                    cause = throwable
                )
        )
    }
}

/**
 * Central Moshi instance and type adapters for CDP message models.
 * Configured to preserve unknown JSON fields.
 */
internal object Protocol {
    val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    val commandAdapter: JsonAdapter<Command> = moshi.adapter(Command::class.java).lenient()
    val responseAdapter: JsonAdapter<Response> = moshi.adapter(Response::class.java).lenient()
    val eventAdapter: JsonAdapter<Event> = moshi.adapter(Event::class.java).lenient()
    val protocolErrorAdapter: JsonAdapter<ProtocolError> = moshi.adapter(ProtocolError::class.java).lenient()

    // Generic type adapters for parsing JSON into specific param types
    inline fun <reified T> adapter(): JsonAdapter<T> = moshi.adapter(T::class.java).lenient()

    /** Parses a JSON string into the given type. */
    inline fun <reified T> fromJson(json: String): T? = moshi.adapter(T::class.java).lenient().fromJson(json)

    /** Parses a JSON value (String or structured) into the given type. */
    inline fun <reified T> fromJsonValue(value: JsonValue): T? {
        if (value == null) return null
        val adapter = moshi.adapter(T::class.java).lenient()
        return if (value is String) {
            adapter.fromJson(value)
        } else {
            val jsonString = moshi.adapter(Any::class.java).toJson(value as Any)
            adapter.fromJson(jsonString)
        }
    }
}
