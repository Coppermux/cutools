package com.coppermux.cutools.core.model

import com.coppermux.cutools.core.error.ParsingException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import okio.BufferedSource
import okio.Okio

/**
 * Result of classifying an incoming CDP message.
 */
sealed interface MessageClassification {
    /** The message is a response to a previously sent command. */
    data class ResponseMessage(val response: Response) : MessageClassification

    /** The message is an event fired by Chrome. */
    data class EventMessage(val event: Event) : MessageClassification

    /**
     * The message could not be classified (e.g., malformed JSON).
     * Contains the parsing error.
     */
    data class Unclassified(val rawJson: String, val error: ParsingException) : MessageClassification
}

/**
 * Classifies raw CDP JSON messages as either command responses or events.
 *
 * Classification rules:
 * - If the JSON object contains an "id" field → it is a [Response] (command response).
 * - If the JSON object contains a "method" field and no "id" field → it is an [Event].
 *
 * Unknown fields are preserved in the parsed models via the JsonValue types.
 */
class CdpMessageClassifier(private val moshi: Moshi = Protocol.moshi) {

    private val responseAdapter = moshi.adapter(Response::class.java).lenient()
    private val eventAdapter = moshi.adapter(Event::class.java).lenient()

    /**
     * Classifies a raw JSON string as a Response or Event.
     *
     * @throws ParsingException if the message cannot be parsed as JSON.
     */
    fun classify(rawJson: String): MessageClassification {
        // First, peek at the raw JSON to check for "id" presence.
        val source = okio.Buffer().writeUtf8(rawJson)
        val reader = JsonReader.of(source)
        reader.isLenient = true

        var hasId = false
        var hasMethod = false
        try {
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> {
                        hasId = true
                        reader.skipValue()
                    }
                    "method" -> {
                        hasMethod = true
                        reader.skipValue()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        } catch (e: Exception) {
            return MessageClassification.Unclassified(
                rawJson,
                ParsingException("Malformed CDP message: $rawJson", cause = e)
            )
        }

        return if (hasId) {
            // Responses have an "id" field — this is a command response.
            try {
                val response = responseAdapter.fromJson(rawJson)
                    ?: throw ParsingException("Response parsed to null: $rawJson")
                MessageClassification.ResponseMessage(response)
            } catch (e: Exception) {
                MessageClassification.Unclassified(
                    rawJson,
                    ParsingException("Failed to parse CDP response: $rawJson", cause = e)
                )
            }
        } else if (hasMethod) {
            // Events have a "method" field (and no "id").
            try {
                val event = eventAdapter.fromJson(rawJson)
                    ?: throw ParsingException("Event parsed to null: $rawJson")
                MessageClassification.EventMessage(event)
            } catch (e: Exception) {
                MessageClassification.Unclassified(
                    rawJson,
                    ParsingException("Failed to parse CDP event: $rawJson", cause = e)
                )
            }
        } else {
            MessageClassification.Unclassified(
                rawJson,
                ParsingException("Message is neither a response nor an event: $rawJson")
            )
        }
    }

    /**
     * Classifies a buffered source (useful for streaming).
     */
    fun classify(source: BufferedSource): MessageClassification {
        val rawJson = try {
            source.readUtf8()
        } catch (e: Exception) {
            return MessageClassification.Unclassified("", ParsingException("Failed to read message", cause = e))
        }
        return classify(rawJson)
    }
}
