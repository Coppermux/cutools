package com.coppermux.cutools.core.console

/**
 * Availability of the Console/Runtime CDP domains on the active target.
 *
 * Console event reception requires the `Runtime` domain (it supplies the
 * severity-distinguishing `Runtime.consoleAPICalled` events and browser
 * timestamps). The `Console` domain is enabled as the supplementary console
 * event source and tracked independently.
 */
enum class ConsoleCapabilityStatus {
    /** Not yet probed. */
    UNKNOWN,

    /** The required domains are enabled and functional. */
    SUPPORTED,

    /** The required domain is not available (method not found). */
    UNSUPPORTED,

    /** Domain enablement failed with an error. */
    FAILED,
}

/**
 * Lifecycle phase of the Console subsystem relative to the active target/session.
 */
enum class ConsoleLifecyclePhase {
    /** Not bound to any target/session. */
    INACTIVE,

    /** Binding and enabling the Runtime/Console domains. */
    ENABLING,

    /** The Console is bound, domains enabled, and receiving events. */
    READY,

    /** The required Chrome capability is unavailable for this target. */
    UNSUPPORTED,

    /** Binding/enablement failed with an error. */
    FAILED,
}

/**
 * Explicit, UI-independent Console state, owned by the [ConsoleInspector].
 *
 * [entries] are structured [ConsoleEntry] models originating from Chrome or
 * from CuTools evaluations/helpers — never UI-generated. The state is
 * invalidated (emptied) on target switch, detach, and connection loss so a
 * previous target's messages never appear as belonging to a new target.
 *
 * Phase 4C also carries the console developer-tool state: the current [input],
 * the [history] (CuTools-only state), the $0 [selectedElement] reference, and
 * the [queryHelperState].
 *
 * Phase 4D adds the presentation state: [searchQuery] and [severityFilter]
 * (CuTools-wide, session-independent UI preferences). [visibleEntries] is the
 * derived intersection of the search and severity-filter over the underlying
 * [entries] — the underlying collection is never mutated or deleted by search
 * or filtering.
 *
 * Deterministic and testable: the UI renders this state and never owns browser
 * truth.
 */
data class ConsoleState(
    val entries: List<ConsoleEntry> = emptyList(),
    val sessionId: String? = null,
    val targetId: String? = null,
    val phase: ConsoleLifecyclePhase = ConsoleLifecyclePhase.INACTIVE,
    val capability: ConsoleCapabilityStatus = ConsoleCapabilityStatus.UNKNOWN,
    val runtimeCapability: ConsoleCapabilityStatus = ConsoleCapabilityStatus.UNKNOWN,
    val consoleDomainCapability: ConsoleCapabilityStatus = ConsoleCapabilityStatus.UNKNOWN,
    val capabilityError: Throwable? = null,
    val error: Throwable? = null,
    // Phase 4C — developer-tool state
    val input: String = "",
    val history: ConsoleCommandHistory = ConsoleCommandHistory.empty(),
    val selectedElement: SelectedElementReference? = null,
    val queryHelperState: QueryHelperState = QueryHelperState.empty(),
    // Phase 4D — presentation state (CuTools-wide UI preferences)
    val searchQuery: String = "",
    val severityFilter: ConsoleSeverityFilter = ConsoleSeverityFilter(),
) {
    val isReady: Boolean get() = phase == ConsoleLifecyclePhase.READY
    val isEnabling: Boolean get() = phase == ConsoleLifecyclePhase.ENABLING
    val isInactive: Boolean get() = phase == ConsoleLifecyclePhase.INACTIVE
    val hasEntries: Boolean get() = entries.isNotEmpty()

    /** History entries, oldest first. */
    val historyEntries: List<String> get() = history.entries

    /** The history position during navigation; -1 means live input. */
    val historyPosition: Int get() = history.position

    /**
     * The visible entry set: the deterministic intersection of the active
     * search query and severity filter over the underlying [entries].
     */
    val visibleEntries: List<ConsoleEntry>
        get() = ConsoleEntryFilter.apply(entries, searchQuery, severityFilter)

    /** Number of visible entries under the active search/filter. */
    val visibleCount: Int get() = visibleEntries.size

    /** A copy that preserves the CuTools-wide presentation state. */
    fun preservingPresentation(): ConsoleState =
        ConsoleState(searchQuery = searchQuery, severityFilter = severityFilter)

    companion object {
        fun empty(): ConsoleState = ConsoleState()
    }
}
