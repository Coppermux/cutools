package com.coppermux.cutools.core.dom

/**
 * Read-only state of a DOM search over the inspected page.
 *
 * Holds the query, the real result nodes (always real Chrome [DomNode]s — never
 * fabricated), the active result index, and loading/error flags.
 *
 * Phase 2B: search results reference real DOM nodes and support next/previous
 * navigation and result selection. The state is owned by the [DomInspector] and
 * is invalidated on target change / connection loss.
 */
data class DomSearchState(
    val query: String = "",
    val results: List<DomNode> = emptyList(),
    val activeIndex: Int = -1,
    val loading: Boolean = false,
    val error: Throwable? = null,
) {
    /** The currently active search result, or null when no result is active. */
    val activeResult: DomNode? get() = if (activeIndex in results.indices) results[activeIndex] else null

    /** Number of results. */
    val resultCount: Int get() = results.size

    /** Whether the search has any results. */
    val hasResults: Boolean get() = results.isNotEmpty()

    val hasError: Boolean get() = error != null

    /** A copy with the active index advanced/wrapped. */
    fun withNext(): DomSearchState {
        if (results.isEmpty()) return copy(activeIndex = -1)
        return copy(activeIndex = if (activeIndex + 1 >= results.size) 0 else activeIndex + 1)
    }

    /** A copy with the active index rewound/wrapped. */
    fun withPrevious(): DomSearchState {
        if (results.isEmpty()) return copy(activeIndex = -1)
        return copy(activeIndex = if (activeIndex - 1 < 0) results.size - 1 else activeIndex - 1)
    }

    /** Convenience: invalidated/empty search state. */
    companion object {
        fun empty(): DomSearchState = DomSearchState()
    }
}

/**
 * Read-only state of the selected-node inspection detail.
 *
 * Tracks the selected node's structured metadata and the state of the outer
 * HTML retrieval (loading / result / error). Kept separate from [DomTree]
 * state; cleared on target change / connection loss so no stale data from a
 * previous Chrome target can be shown.
 */
data class DomInspectionState(
    val node: DomNode? = null,
    val outerHtmlLoading: Boolean = false,
    val outerHtml: String? = null,
    val outerHtmlError: Throwable? = null,
) {
    val isSelected: Boolean get() = node != null

    /** Whether an outer HTML result is currently available. */
    val hasOuterHtml: Boolean get() = outerHtml != null

    val hasOuterHtmlError: Boolean get() = outerHtmlError != null

    /** A copy for a new selection (clears the previous outer HTML state). */
    fun forNode(newNode: DomNode): DomInspectionState =
        DomInspectionState(node = newNode)

    companion object {
        fun empty(): DomInspectionState = DomInspectionState()
    }
}