package com.coppermux.cutools.core.runtime

import com.coppermux.cutools.core.model.JsonValue

/**
 * Phase of the JavaScript execution subsystem relative to the active target/session.
 */
enum class ExecutionPhase {
    /** Not bound to any target/session. */
    INACTIVE,

    /** Bound to the active session and ready to evaluate. */
    READY,
}

/**
 * Structured request to execute a JavaScript expression against the actual
 * selected Chrome page context via the CDP `Runtime.evaluate` command.
 *
 * Only protocol-supported options actually used by the implementation are
 * exposed:
 * - [expression] — the JavaScript source to execute.
 * - [returnByValue] — whether Chrome should return the value by value instead
 *   of a RemoteObject. Defaults to false so Chrome's RemoteObject (including
 *   objectId for browser-owned objects) is preserved.
 * - [generatePreview] — whether Chrome should generate a preview for the
 *   returned RemoteObject. Defaults to false.
 * - [includeCommandLineAPI] — whether Chrome's native console command-line API
 *   (`$0`, `$`, `$$`, `$x`, `$_`) is available in the evaluated expression.
 *   Defaults to false; the Console enables it for its own submissions.
 * - [awaitPromise] — whether execution should await for resulting value and return
 *   once the promise is resolved. Defaults to false.
 */
data class EvaluationRequest(
    val expression: String,
    val returnByValue: Boolean = false,
    val generatePreview: Boolean = false,
    val includeCommandLineAPI: Boolean = false,
    val awaitPromise: Boolean = false,
) {
    /** Serializes this request into the CDP `Runtime.evaluate` params object. */
    fun toParams(): Map<String, Any?> = mapOf(
        "expression" to expression,
        "returnByValue" to returnByValue,
        "generatePreview" to generatePreview,
        "includeCommandLineAPI" to includeCommandLineAPI,
        "awaitPromise" to awaitPromise,
    )
}

/**
 * A browser-owned RemoteObject returned by `Runtime.evaluate`, preserving
 * Chrome's protocol representation without flattening it into a display string.
 *
 * [objectId] is a Chrome/CDP reference to a browser-owned object — it is NOT the
 * object's value, and CuTools never pretends otherwise. [value] / 
 * [unserializableValue] are only present when Chrome provides a directly
 * serializable primitive. [preview] / [customPreview] are preserved verbatim as
 * the raw protocol representation.
 */
data class EvaluationRemoteObject(
    val type: String? = null,
    val subtype: String? = null,
    val className: String? = null,
    val value: JsonValue = null,
    val unserializableValue: String? = null,
    val description: String? = null,
    val objectId: String? = null,
    val preview: JsonValue = null,
    val customPreview: JsonValue = null,
) {
    /**
     * A display string derived from Chrome's own data. The structured fields
     * above remain authoritative; this is only a UI convenience.
     */
    val displayText: String
        get() = when {
            type == "undefined" -> "undefined"
            subtype == "null" -> "null"
            !unserializableValue.isNullOrEmpty() -> unserializableValue
            value is String -> value
            value is Boolean -> value.toString()
            value is Number -> formatNumber(value as Number)
            !description.isNullOrEmpty() -> description
            else -> "(${type ?: "value"})"
        }

    private fun formatNumber(value: Number): String = when (value) {
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Float -> if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }
}

/**
 * A single stack call frame from Chrome's `Runtime.CallFrame`. All fields
 * originate from Chrome/CDP; CuTools never fabricates frames or locations.
 */
data class EvaluationCallFrame(
    val functionName: String? = null,
    val url: String? = null,
    val scriptId: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

/**
 * A Chrome stack trace (matching `Runtime.StackTrace`), with optional async
 * [parent] preserved.
 */
data class EvaluationStackTrace(
    val description: String? = null,
    val callFrames: List<EvaluationCallFrame> = emptyList(),
    val parent: EvaluationStackTrace? = null,
)

/**
 * Chrome's `Runtime.ExceptionDetails` for a JavaScript evaluation exception.
 * Preserves exception text, source line/column/url, script/source info, the
 * exception RemoteObject, and the stack trace where Chrome exposes them.
 */
data class EvaluationExceptionDetails(
    val exceptionId: Int? = null,
    val text: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val scriptId: String? = null,
    val url: String? = null,
    val exception: EvaluationRemoteObject? = null,
    val stackTrace: EvaluationStackTrace? = null,
    val executionContextId: Int? = null,
) {
    /** Display string derived from Chrome's exception text/description. */
    val displayText: String
        get() {
            val prefix = text?.takeIf { it.isNotBlank() } ?: "Uncaught"
            val summary = exception?.description?.lineSequence()?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: exception?.displayText?.takeIf { it.isNotBlank() }
            return summary?.let { "$prefix $it" } ?: prefix
        }
}

/**
 * The classified outcome of a single evaluation, distinguishing:
 * - [EvaluationResultKind.Success] — Chrome returned a result RemoteObject.
 * - [EvaluationResultKind.Exception] — a genuine JavaScript evaluation
 *   exception (a browser execution result, not a transport failure).
 * - [EvaluationResultKind.ProtocolError] — a CDP command/protocol failure.
 */
sealed interface EvaluationResultKind {
    data class Success(val value: EvaluationRemoteObject, val executionContextId: Int? = null) : EvaluationResultKind
    data class Exception(val details: EvaluationExceptionDetails) : EvaluationResultKind
    data class ProtocolError(val error: Throwable) : EvaluationResultKind
}

/**
 * A completed evaluation tied to its request and to the session/target it ran
 * against. The session/target association is explicit so an evaluation can
 * never appear as though it originated from a different target.
 */
data class EvaluationOutcome(
    val id: Long,
    val request: EvaluationRequest,
    val result: EvaluationResultKind,
    val sessionId: String,
    val targetId: String? = null,
) {
    val isSuccess: Boolean get() = result is EvaluationResultKind.Success
    val isException: Boolean get() = result is EvaluationResultKind.Exception
    val isProtocolError: Boolean get() = result is EvaluationResultKind.ProtocolError

    /** The protocol error, or null when the evaluation succeeded or threw. */
    val error: Throwable? get() = (result as? EvaluationResultKind.ProtocolError)?.error

    /** Display string derived from Chrome's result/exception data. */
    val displayText: String
        get() = when (val r = result) {
            is EvaluationResultKind.Success -> r.value.displayText
            is EvaluationResultKind.Exception -> r.details.displayText
            is EvaluationResultKind.ProtocolError -> "Evaluation failed: ${r.error.message ?: "unknown protocol error"}"
        }
}

/**
 * Explicit, UI-independent JavaScript execution state, owned by the
 * [ExecutionEngine]. Tracks the session/target association, pending evaluation
 * count, and the last outcome. Deterministic and testable.
 */
data class ExecutionState(
    val sessionId: String? = null,
    val targetId: String? = null,
    val phase: ExecutionPhase = ExecutionPhase.INACTIVE,
    val pendingEvaluations: Int = 0,
    val lastOutcome: EvaluationOutcome? = null,
) {
    val isReady: Boolean get() = phase == ExecutionPhase.READY

    companion object {
        fun empty(): ExecutionState = ExecutionState()
    }
}
