package com.coppermux.cutools.core.runtime

import com.coppermux.cutools.core.error.ParsingException
import com.coppermux.cutools.core.model.Response

/**
 * Pure, deterministic mapping of a CDP `Runtime.evaluate` response to an
 * [EvaluationOutcome]. No Chrome communication occurs here, making the mapping
 * directly unit-testable.
 *
 * Only fields Chrome actually exposes are populated. A successful protocol
 * response may still contain a genuine JavaScript evaluation exception
 * (`exceptionDetails`), which is mapped to
 * [EvaluationResultKind.Exception] — distinct from a protocol/command failure.
 */
object EvaluationResultMapper {

    /**
     * Maps a protocol-successful `Runtime.evaluate` response to an outcome.
     *
     * @param response the protocol-successful response (a protocol error would
     *   already have been surfaced as a dispatcher failure).
     */
    fun mapSuccess(
        response: Response,
        request: EvaluationRequest,
        sessionId: String,
        targetId: String?,
    ): EvaluationOutcome {
        val result = response.result
        if (result !is Map<*, *>) {
            return EvaluationOutcome(
                id = 0,
                request = request,
                result = EvaluationResultKind.ProtocolError(
                    ParsingException("Runtime.evaluate returned no result payload")
                ),
                sessionId = sessionId,
                targetId = targetId,
            )
        }

        val exceptionDetailsRaw = result["exceptionDetails"]
        if (result.containsKey("exceptionDetails")) {
            if (exceptionDetailsRaw !is Map<*, *>) {
                return EvaluationOutcome(
                    id = 0,
                    request = request,
                    result = EvaluationResultKind.ProtocolError(
                        ParsingException("Malformed exceptionDetails in Runtime.evaluate response")
                    ),
                    sessionId = sessionId,
                    targetId = targetId,
                )
            }
            val details = parseExceptionDetails(exceptionDetailsRaw)
            if (details == null) {
                return EvaluationOutcome(
                    id = 0,
                    request = request,
                    result = EvaluationResultKind.ProtocolError(
                        ParsingException("Malformed exceptionDetails in Runtime.evaluate response")
                    ),
                    sessionId = sessionId,
                    targetId = targetId,
                )
            }
            return EvaluationOutcome(
                id = 0,
                request = request,
                result = EvaluationResultKind.Exception(details),
                sessionId = sessionId,
                targetId = targetId,
            )
        }

        val remoteObject = parseRemoteObject(result["result"])
        val executionContextId = (result["executionContextId"] as? Number)?.toInt()
        return EvaluationOutcome(
            id = 0,
            request = request,
            result = EvaluationResultKind.Success(remoteObject, executionContextId),
            sessionId = sessionId,
            targetId = targetId,
        )
    }

    /** Parses a Chrome `Runtime.RemoteObject`, preserving every exposed field. */
    fun parseRemoteObject(raw: Any?): EvaluationRemoteObject {
        if (raw !is Map<*, *>) return EvaluationRemoteObject()
        return EvaluationRemoteObject(
            type = raw["type"] as? String,
            subtype = raw["subtype"] as? String,
            className = raw["className"] as? String,
            value = raw["value"],
            unserializableValue = raw["unserializableValue"] as? String,
            description = raw["description"] as? String,
            objectId = raw["objectId"] as? String,
            preview = raw["preview"],
            customPreview = raw["customPreview"],
        )
    }

    /** Parses a Chrome `Runtime.ExceptionDetails`, or null when malformed. */
    fun parseExceptionDetails(raw: Any?): EvaluationExceptionDetails? {
        if (raw !is Map<*, *>) return null
        val exception = raw["exception"]
        return EvaluationExceptionDetails(
            exceptionId = (raw["exceptionId"] as? Number)?.toInt(),
            text = raw["text"] as? String,
            line = (raw["lineNumber"] as? Number)?.toInt(),
            column = (raw["columnNumber"] as? Number)?.toInt(),
            scriptId = raw["scriptId"] as? String,
            url = raw["url"] as? String,
            exception = if (exception is Map<*, *>) parseRemoteObject(exception) else null,
            stackTrace = parseStackTrace(raw["stackTrace"]),
            executionContextId = (raw["executionContextId"] as? Number)?.toInt(),
        )
    }

    /** Parses a Chrome `Runtime.StackTrace`, including optional async [parent]. */
    fun parseStackTrace(raw: Any?): EvaluationStackTrace? {
        if (raw !is Map<*, *>) return null
        val frames = (raw["callFrames"] as? List<*>)?.mapNotNull { frame ->
            if (frame !is Map<*, *>) return@mapNotNull null
            EvaluationCallFrame(
                functionName = frame["functionName"] as? String,
                url = frame["url"] as? String,
                scriptId = frame["scriptId"] as? String,
                line = (frame["lineNumber"] as? Number)?.toInt(),
                column = (frame["columnNumber"] as? Number)?.toInt(),
            )
        } ?: emptyList()
        return EvaluationStackTrace(
            description = raw["description"] as? String,
            callFrames = frames,
            parent = parseStackTrace(raw["parent"]),
        )
    }
}
