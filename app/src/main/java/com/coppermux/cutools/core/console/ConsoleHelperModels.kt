package com.coppermux.cutools.core.console

import com.coppermux.cutools.core.runtime.EvaluationOutcome
import com.coppermux.cutools.core.runtime.EvaluationRemoteObject

/**
 * Explicit classification of a developer-tool feature's origin, so CuTools
 * never labels CuTools-only functionality as native Chrome functionality.
 *
 * - [NATIVE_CHROME] — browser functionality performed through CDP (e.g. page
 *   JavaScript execution via `Runtime.evaluate`).
 * - [CDP_BACKED] — CuTools functionality whose result is obtained from Chrome
 *   through CDP (e.g. the querySelector helper, and $0 resolved via
 *   `DOM.resolveNode`).
 * - [CUTOOLS_ONLY] — CuTools-only convenience state with no browser backing
 *   (e.g. command history).
 */
enum class ConsoleFeatureOrigin(val displayLabel: String) {
    NATIVE_CHROME("native Chrome"),
    CDP_BACKED("CDP-backed"),
    CUTOOLS_ONLY("CuTools-only"),
}

/**
 * Lifecycle of the $0 selected-element reference.
 */
enum class SelectedElementPhase {
    /** No element is selected; $0 is unavailable. */
    NO_SELECTION,

    /** A node is selected and is being resolved against the page via CDP. */
    RESOLVING,

    /** The selected element resolved to a genuine Chrome RemoteObject. */
    LOADED,

    /** The selected node became stale/invalid; the reference must not be used. */
    STALE,

    /** Resolving is not supported by this target/protocol version. */
    UNSUPPORTED,

    /** Resolution failed with an error. */
    FAILED,
}

/**
 * The $0 selected-element reference, sourced from the existing Elements tool
 * selection (the [com.coppermux.cutools.core.dom.DomInspector]) — never a
 * second, independent element-selection system.
 *
 * When [LOADED], [remoteObject] is a genuine Chrome RemoteObject obtained via
 * CDP `DOM.resolveNode` for the selected node. CuTools never fabricates an
 * element or creates a local DOM object and presents it as $0.
 */
data class SelectedElementReference(
    val nodeId: Int,
    val backendNodeId: Int? = null,
    val label: String? = null,
    val sessionId: String,
    val targetId: String? = null,
    val phase: SelectedElementPhase = SelectedElementPhase.RESOLVING,
    val remoteObject: EvaluationRemoteObject? = null,
    val error: Throwable? = null,
) {
    /** Whether $0 currently resolves to a genuine Chrome RemoteObject. */
    val isAvailable: Boolean get() = phase == SelectedElementPhase.LOADED
}

/**
 * A page-context query helper. The browser performs the query; CuTools composes
 * the page expression and evaluates it through the existing Phase 4B
 * execution/RemoteObject infrastructure, so results are genuine Chrome
 * RemoteObjects. These are CDP-backed CuTools functionality.
 */
sealed interface ConsoleQueryHelper {
    val displayLabel: String
    val origin: ConsoleFeatureOrigin
    val isPlural: Boolean

    /** Builds the page-context expression for [selector]. */
    fun buildExpression(selector: String): String

    companion object {
        val ALL: List<ConsoleQueryHelper> = listOf(QuerySelector, QuerySelectorAll)

        fun fromName(name: String?): ConsoleQueryHelper? = ALL.firstOrNull { it.displayLabel == name }
    }
}

/** `document.querySelector(selector)` — returns the first matching element or null. */
object QuerySelector : ConsoleQueryHelper {
    override val displayLabel = "querySelector"
    override val origin = ConsoleFeatureOrigin.CDP_BACKED
    override val isPlural = false
    override fun buildExpression(selector: String): String =
        "document.querySelector(${jsStringLiteral(selector)})"
}

/** `document.querySelectorAll(selector)` — returns a NodeList of matches. */
object QuerySelectorAll : ConsoleQueryHelper {
    override val displayLabel = "querySelectorAll"
    override val origin = ConsoleFeatureOrigin.CDP_BACKED
    override val isPlural = true
    override fun buildExpression(selector: String): String =
        "document.querySelectorAll(${jsStringLiteral(selector)})"
}

/** Encodes [value] as a JavaScript string literal for embedding in a page expression. */
fun jsStringLiteral(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u0000' -> append("\\u0000")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

/**
 * Explicit query-helper state: which helper is active, the selector, whether a
 * query is running, and the last genuine Chrome evaluation outcome for it.
 */
data class QueryHelperState(
    val activeHelper: ConsoleQueryHelper? = null,
    val selector: String = "",
    val running: Boolean = false,
    val lastOutcome: EvaluationOutcome? = null,
) {
    companion object {
        fun empty(): QueryHelperState = QueryHelperState()
    }
}
