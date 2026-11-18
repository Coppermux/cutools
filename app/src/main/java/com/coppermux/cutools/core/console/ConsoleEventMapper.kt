package com.coppermux.cutools.core.console

/**
 * Pure, deterministic mapping of Chrome CDP console events to [ConsoleEntry]
 * models. No Chrome communication occurs here, making the mapping directly
 * unit-testable.
 *
 * Only fields Chrome actually exposes are populated; missing browser
 * information remains null. Malformed payloads are tolerated and return null
 * rather than crashing the event pipeline.
 */
object ConsoleEventMapper {

    /**
     * Maps a `Runtime.consoleAPICalled` event payload to a [ConsoleEntry].
     *
     * Chrome provides `type`, `args` (structured RemoteObjects), timestamp,
     * executionContextId, and (where available) a stack trace. Chrome does NOT
     * provide a preformatted text string for this event, so [ConsoleEntry.text]
     * stays null and the structured [ConsoleEntry.args] are preserved.
     */
    fun mapConsoleApiCalled(params: Any?, sessionId: String, targetId: String?): ConsoleEntry? {
        if (params !is Map<*, *>) return null
        val type = ConsoleMessageType.fromCdp(params["type"] as? String)
        val stackTrace = parseStackTrace(params["stackTrace"])
        val location = stackTrace?.callFrames?.firstOrNull()?.let {
            ConsoleSourceLocation(it.url, it.scriptId, it.line, it.column)
        }
        return ConsoleEntry(
            id = 0L,
            kind = ConsoleEntryKind.CONSOLE_MESSAGE,
            severity = ConsoleSeverity.fromApiType(type),
            type = type,
            text = null,
            timestamp = (params["timestamp"] as? Number)?.toDouble(),
            args = parseArgs(params["args"]),
            executionContextId = (params["executionContextId"] as? Number)?.toInt(),
            source = "console-api",
            location = location,
            stackTrace = stackTrace,
            sessionId = sessionId,
            targetId = targetId,
        )
    }

    /**
     * Maps a `Runtime.exceptionThrown` event payload to a [ConsoleEntry] of
     * kind [ConsoleEntryKind.RUNTIME_EXCEPTION].
     *
     * Chrome reports `exceptionDetails` with text ("Uncaught" / "Unhandled
     * promise rejection"), source url/line/column, stack trace, execution
     * context, and the exception RemoteObject whose description carries the
     * error message. Only Chrome-supplied fields are preserved.
     */
    fun mapExceptionThrown(params: Any?, sessionId: String, targetId: String?): ConsoleEntry? {
        if (params !is Map<*, *>) return null
        val details = params["exceptionDetails"] as? Map<*, *> ?: return null
        val stackTrace = parseStackTrace(details["stackTrace"])
        val exception = details["exception"] as? Map<*, *>
        val exceptionDescription = exception?.get("description") as? String
            ?: exception?.get("value")?.toString()
        return ConsoleEntry(
            id = 0L,
            kind = ConsoleEntryKind.RUNTIME_EXCEPTION,
            severity = ConsoleSeverity.ERROR,
            type = ConsoleMessageType.ERROR,
            text = details["text"] as? String,
            timestamp = (params["timestamp"] as? Number)?.toDouble(),
            executionContextId = (details["executionContextId"] as? Number)?.toInt(),
            location = ConsoleSourceLocation(
                url = details["url"] as? String,
                scriptId = details["scriptId"] as? String,
                line = (details["lineNumber"] as? Number)?.toInt(),
                column = (details["columnNumber"] as? Number)?.toInt(),
            ),
            stackTrace = stackTrace,
            exceptionDescription = exceptionDescription,
            exceptionId = (details["exceptionId"] as? Number)?.toInt(),
            sessionId = sessionId,
            targetId = targetId,
        )
    }

    /**
     * Maps a `Console.messageAdded` event payload to a [ConsoleEntry].
     *
     * The Console domain supplies a browser-formatted [ConsoleEntry.text],
     * source, level, and (where exposed by the Chrome version) url/line/column.
     * Some Chrome versions do not populate timestamp/args/stack on this event;
     * those fields stay null when absent — CuTools never substitutes local
     * reception time for a missing browser timestamp.
     */
    fun mapMessageAdded(params: Any?, sessionId: String, targetId: String?): ConsoleEntry? {
        if (params !is Map<*, *>) return null
        val message = params["message"] as? Map<*, *> ?: return null
        val level = message["level"] as? String
        val stackTrace = parseStackTrace(message["stackTrace"])
        return ConsoleEntry(
            id = 0L,
            kind = ConsoleEntryKind.CONSOLE_MESSAGE,
            severity = ConsoleSeverity.fromConsoleLevel(level),
            type = typeFromLevel(level),
            text = message["text"] as? String,
            timestamp = (message["timestamp"] as? Number)?.toDouble(),
            args = parseArgs(message["args"]),
            executionContextId = (message["context"] as? Number)?.toInt(),
            source = message["source"] as? String,
            location = ConsoleSourceLocation(
                url = message["url"] as? String,
                line = (message["line"] as? Number)?.toInt(),
                column = (message["column"] as? Number)?.toInt(),
            ),
            stackTrace = stackTrace,
            sessionId = sessionId,
            targetId = targetId,
        )
    }

    /** Parses the `args` array of RemoteObject into structured [ConsoleArg]s. */
    fun parseArgs(raw: Any?): List<ConsoleArg> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            ConsoleArg(
                type = item["type"] as? String,
                subtype = item["subtype"] as? String,
                value = item["value"],
                description = item["description"] as? String,
                className = item["className"] as? String,
                objectId = item["objectId"] as? String,
            )
        }
    }

    /** Parses a Chrome `StackTrace` (including optional async [parent]). */
    fun parseStackTrace(raw: Any?): ConsoleStackTrace? {
        if (raw !is Map<*, *>) return null
        val frames = (raw["callFrames"] as? List<*>)?.mapNotNull { frame ->
            if (frame !is Map<*, *>) return@mapNotNull null
            ConsoleCallFrame(
                functionName = frame["functionName"] as? String,
                url = frame["url"] as? String,
                scriptId = frame["scriptId"] as? String,
                line = (frame["lineNumber"] as? Number)?.toInt(),
                column = (frame["columnNumber"] as? Number)?.toInt(),
            )
        } ?: emptyList()
        return ConsoleStackTrace(
            description = raw["description"] as? String,
            callFrames = frames,
            parent = parseStackTrace(raw["parent"]),
        )
    }

    private fun typeFromLevel(level: String?): ConsoleMessageType = when (level) {
        "log" -> ConsoleMessageType.LOG
        "debug", "verbose" -> ConsoleMessageType.DEBUG
        "info" -> ConsoleMessageType.INFO
        "warning" -> ConsoleMessageType.WARNING
        "error" -> ConsoleMessageType.ERROR
        else -> ConsoleMessageType.UNKNOWN
    }
}
