package com.coppermux.cutools.core.console

import com.coppermux.cutools.core.model.JsonValue
import com.coppermux.cutools.core.runtime.EvaluationOutcome

/**
 * The exact CDP `Runtime.consoleAPICalled.type` value (or the Console-domain
 * `Console.messageAdded.level` projection) reported by Chrome for a console
 * entry. [UNKNOWN] preserves types this protocol version does not enumerate.
 *
 * [cdpValue] is the raw Chrome string; nothing is fabricated. Severity is a
 * derived display grouping ([ConsoleSeverity]) layered on top of this raw type.
 */
enum class ConsoleMessageType(val cdpValue: String?) {
    LOG("log"),
    DEBUG("debug"),
    INFO("info"),
    WARNING("warning"),
    ERROR("error"),
    ASSERT("assert"),
    DIR("dir"),
    DIRXML("dirxml"),
    TABLE("table"),
    TRACE("trace"),
    CLEAR("clear"),
    START_GROUP("startGroup"),
    START_GROUP_COLLAPSED("startGroupCollapsed"),
    END_GROUP("endGroup"),
    COUNT("count"),
    TIME_END("timeEnd"),
    PROFILE("profile"),
    PROFILE_END("profileEnd"),
    UNKNOWN(null);

    companion object {
        /** Maps a raw CDP type string to the enumerated type, preserving unknowns. */
        fun fromCdp(value: String?): ConsoleMessageType =
            entries.firstOrNull { it.cdpValue == value } ?: UNKNOWN
    }
}

/**
 * Display severity of a console entry, derived from Chrome's reported type/level.
 *
 * The authoritative raw Chrome value is preserved in [ConsoleEntry.type] /
 * [ConsoleEntry.source]; this is the UI-facing grouping. Phase 4A recognizes
 * exactly LOG / INFO / DEBUG / WARNING / ERROR.
 */
enum class ConsoleSeverity(val displayLabel: String) {
    LOG("log"),
    INFO("info"),
    DEBUG("debug"),
    WARNING("warning"),
    ERROR("error");

    companion object {
        /** Derives severity from a `Runtime.consoleAPICalled.type` value. */
        fun fromApiType(type: ConsoleMessageType): ConsoleSeverity = when (type) {
            ConsoleMessageType.INFO -> INFO
            ConsoleMessageType.DEBUG -> DEBUG
            ConsoleMessageType.WARNING -> WARNING
            ConsoleMessageType.ERROR, ConsoleMessageType.ASSERT -> ERROR
            // dir / dirxml / table / trace / count / timeEnd / clear / profile …
            // are informational; the raw type is preserved on the entry.
            else -> LOG
        }

        /** Derives severity from a `Console.messageAdded.level` value. */
        fun fromConsoleLevel(level: String?): ConsoleSeverity = when (level) {
            "info" -> INFO
            "debug", "verbose" -> DEBUG
            "warning" -> WARNING
            "error" -> ERROR
            "log" -> LOG
            else -> LOG
        }
    }
}

/**
 * Kind of a console entry. Runtime exceptions ([RUNTIME_EXCEPTION]) are
 * Chrome-reported `Runtime.exceptionThrown` events and are distinguished from
 * regular console messages in the UI.
 */
enum class ConsoleEntryKind(val displayLabel: String) {
    CONSOLE_MESSAGE("console"),
    RUNTIME_EXCEPTION("runtime exception"),

    /**
     * A CuTools-executed JavaScript evaluation result (Phase 4B). This is NOT a
     * genuine Chrome console event — it is a CuTools execution result whose
     * origin is explicit via [ConsoleEntry.evaluation]. It remains
     * distinguishable from genuine Chrome console messages/exceptions.
     */
    EVALUATION_RESULT("evaluation"),

    /**
     * A user-submitted JavaScript command entered through the console input.
     */
    COMMAND("command"),
}

/**
 * A structured argument/value from a `Runtime.consoleAPICalled.args` or
 * `Console.messageAdded` payload. Preserves the browser-provided RemoteObject
 * surface without resolving remote objects (resolution belongs to a later
 * phase). [value] keeps the primitive JSON value; [description] / [className]
 * are Chrome-provided for objects.
 */
data class ConsoleArg(
    val type: String? = null,
    val subtype: String? = null,
    val value: JsonValue = null,
    val description: String? = null,
    val className: String? = null,
    val objectId: String? = null,
)

/**
 * A single stack call frame (matching Chrome's `Runtime.CallFrame`).
 *
 * All fields originate from Chrome/CDP. A frame Chrome does not describe is
 * left absent; CuTools never fabricates frames.
 */
data class ConsoleCallFrame(
    val functionName: String? = null,
    val url: String? = null,
    val scriptId: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

/**
 * A Chrome stack trace (matching `Runtime.StackTrace`), with its optional
 * parent (async) trace preserved.
 */
data class ConsoleStackTrace(
    val description: String? = null,
    val callFrames: List<ConsoleCallFrame> = emptyList(),
    val parent: ConsoleStackTrace? = null,
)

/**
 * Browser-provided source location for a console entry. For
 * `Runtime.consoleAPICalled` this is the top call frame of the reported stack
 * trace; for `Console.messageAdded` and `Runtime.exceptionThrown` it is the
 * url/line/column Chrome reports directly.
 */
data class ConsoleSourceLocation(
    val url: String? = null,
    val scriptId: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

/**
 * Structured console entry — the model for one genuine Chrome console message
 * or runtime exception.
 *
 * Preserves browser-provided information where Chrome exposes it:
 * severity/type, message text, timestamp, arguments/values, execution context,
 * source information, and stack trace. Missing browser information remains
 * absent (null) rather than being fabricated.
 *
 * [timestamp] is the browser-provided value (milliseconds since epoch as a
 * Double). It is NEVER replaced with local event-reception time. UI formatting
 * is applied only at display time and never destroys this value.
 *
 * [id] is a local, monotonically increasing sequence number assigned by the
 * [ConsoleInspector] to preserve arrival order for display.
 *
 * For [ConsoleEntryKind.EVALUATION_RESULT] entries, [evaluation] carries the
 * structured CuTools evaluation outcome; such an entry is not a Chrome console
 * event and no Chrome console event is fabricated for it.
 */
data class ConsoleEntry(
    val id: Long,
    val kind: ConsoleEntryKind,
    val severity: ConsoleSeverity,
    val type: ConsoleMessageType,
    val text: String? = null,
    val timestamp: Double? = null,
    val args: List<ConsoleArg> = emptyList(),
    val executionContextId: Int? = null,
    val source: String? = null,
    val location: ConsoleSourceLocation? = null,
    val stackTrace: ConsoleStackTrace? = null,
    val exceptionDescription: String? = null,
    val exceptionId: Int? = null,
    val evaluation: EvaluationOutcome? = null,
    // Phase 4C — developer-tool convenience classification
    val origin: ConsoleFeatureOrigin = ConsoleFeatureOrigin.NATIVE_CHROME,
    val helperLabel: String? = null,
    val sessionId: String,
    val targetId: String? = null,
) {
    val isRuntimeException: Boolean get() = kind == ConsoleEntryKind.RUNTIME_EXCEPTION
    val isEvaluationResult: Boolean get() = kind == ConsoleEntryKind.EVALUATION_RESULT
    val isCommand: Boolean get() = kind == ConsoleEntryKind.COMMAND

    /**
     * Display text derived from browser-provided data.
     *
     * For `Console.messageAdded`/`Runtime.exceptionThrown` the browser supplies
     * [text] directly and it is used verbatim. For `Runtime.consoleAPICalled`
     * Chrome supplies structured [args] instead of a formatted string, so the
     * text is derived by joining the primitive values / descriptions the browser
     * already provided. The structured [args] remain available; this is a
     * display convenience, not a replacement for the structured model.
     */
    val displayText: String
        get() = when {
            evaluation != null -> evaluation.displayText
            isRuntimeException -> {
                val prefix = text?.takeIf { it.isNotBlank() } ?: "Uncaught"
                val summary = exceptionDescription?.lineSequence()?.firstOrNull()
                summary?.let { "$prefix $it" } ?: prefix
            }
            !text.isNullOrEmpty() -> text!!
            else -> derivedTextFromArgs()
        }

    private fun derivedTextFromArgs(): String =
        args.joinToString(" ") { arg ->
            when {
                arg.value is String -> arg.value
                arg.value is Number -> formatNumber(arg.value as Number)
                arg.value is Boolean -> arg.value.toString()
                else -> arg.description ?: arg.className ?: "(${arg.type ?: "value"})"
            }
        }

    /** Formats a browser-provided numeric value without an artificial trailing ".0". */
    private fun formatNumber(value: Number): String = when (value) {
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Float -> if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }
}
