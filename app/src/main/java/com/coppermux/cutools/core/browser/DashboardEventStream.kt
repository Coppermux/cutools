package com.coppermux.cutools.core.browser

import com.coppermux.cutools.core.kernel.DiagnosticKernel
import com.coppermux.cutools.core.kernel.KernelState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 18 — listener for [DashboardEventStream] state changes.
 */
interface DashboardEventStreamListener {
    fun onDashboardStateChanged(state: DashboardStreamState)
}

/**
 * Phase 18 — immutable snapshot of the dashboard event stream.
 *
 * [events] hold the chronological [DashboardEvent] models intercepted by the
 * embedded WebView engine (oldest first). [filter] and [searchQuery] are
 * presentation state (CuTools-wide UI preferences). [visibleEvents] is the
 * derived intersection of the filter and search over the underlying [events] —
 * the underlying collection is never mutated or deleted by filtering.
 *
 * Deterministic and testable: the UI renders this state and never owns
 * browser truth.
 */
data class DashboardStreamState(
    val events: List<DashboardEvent> = emptyList(),
    val filter: DashboardFilter = DashboardFilter.ALL,
    val searchQuery: String = "",
    val isRecording: Boolean = true,
) {
    /** The visible event set under the active filter + search (oldest first). */
    val visibleEvents: List<DashboardEvent> get() = DashboardEventFilter.apply(events, filter, searchQuery)

    /** Total intercepted events (before filtering). */
    val eventCount: Int get() = events.size

    /** Number of events visible under the active filter/search. */
    val visibleCount: Int get() = visibleEvents.size

    /** Whether the feed is currently restricted by a filter or search query. */
    val isFiltered: Boolean get() = DashboardEventFilter.isActive(filter, searchQuery)

    companion object {
        /** Maximum number of events retained in the bounded feed. */
        const val DEFAULT_MAX_ENTRIES = 500

        fun empty(): DashboardStreamState = DashboardStreamState()
    }
}

/**
 * Phase 18 — centralized, reactive interceptor event stream.
 *
 * The single conduit connecting the embedded [WebViewEngine] interception
 * callbacks to the UI layer ([com.coppermux.cutools.ui.shell.ShellViewModel]).
 * Every intercepted event is appended here in arrival (chronological) order;
 * the same event stream also owns the presentation filter/search preferences,
 * mirroring how [com.coppermux.cutools.core.console.ConsoleState] separates
 * browser truth from presentation state.
 *
 * Thread-safe: appends, filter updates, and reads may happen from any thread;
 * listeners and the callback-instant [state] snapshot are kept consistent via
 * synchronization. The feed is bounded to [maxEntries] (oldest events evict
 * first) so long-lived browsing stays memory-friendly.
 */
class DashboardEventStream(
    private val maxEntries: Int = DashboardStreamState.DEFAULT_MAX_ENTRIES,
    @Volatile
    var kernel: DiagnosticKernel? = null,
) {
    private val listeners = CopyOnWriteArrayList<DashboardEventStreamListener>()

    /** Monotonically increasing arrival-order sequence for [DashboardEvent.id]. */
    private val nextEventId = AtomicLong(0)

    @Volatile
    private var current: DashboardStreamState = DashboardStreamState.empty()

    /** The latest immutable stream snapshot (thread-safe read). */
    val state: DashboardStreamState get() = current

    /** Whether the event stream is actively recording incoming events. */
    val isRecording: Boolean get() = current.isRecording

    fun addListener(listener: DashboardEventStreamListener) { listeners.add(listener) }
    fun removeListener(listener: DashboardEventStreamListener) { listeners.remove(listener) }

    /**
     * Stamps [event] with the next arrival ID, appends it in chronological
     * order, and publishes a fresh snapshot to all listeners.
     *
     * Ingress barrier (Phase 2):
     * - Dropped if kernel is currently [KernelState.RESETTING].
     * - Dropped if event carries an epoch belonging to an invalidated session.
     *
     * When recording is paused ([isRecording] is false), incoming events are
     * dropped to freeze the feed without mutating state or triggering listeners.
     * Returns the event for convenient chaining.
     */
    @Synchronized
    fun append(event: DashboardEvent): DashboardEvent {
        if (!current.isRecording) {
            return event
        }
        val k = kernel
        if (k != null) {
            val snapshot = k.snapshot
            if (snapshot.state == KernelState.RESETTING) {
                return event
            }
            if (event.kernelEpoch != null && event.kernelEpoch != snapshot.epoch) {
                return event
            }
        }
        val enriched = if (k != null && event.kernelEpoch == null && k.hasActiveSession) {
            event.copy(
                kernelEpoch = k.currentEpoch,
                sessionKey = k.currentSession?.sessionKey,
            )
        } else {
            event
        }
        val timestamp = if (enriched.timestamp <= 0L) System.currentTimeMillis() else enriched.timestamp
        val stamped = enriched.copy(id = nextEventId.incrementAndGet(), timestamp = timestamp)
        val next = current.copy(events = (current.events + stamped).takeLast(maxEntries))
        updateState(next)
        return stamped
    }

    /**
     * Atomically inserts or updates an event identified by [requestId].
     *
     * If an event with [requestId] is already present in the stream, [transform] is
     * invoked on the existing event to produce the updated state, preserving the
     * existing [DashboardEvent.id] and sequence position.
     *
     * If no event with [requestId] is present, [create] is invoked to produce the initial
     * event, stamped with the next arrival ID, and appended to the feed (bounded to [maxEntries]).
     *
     * When recording is paused ([isRecording] is false), new events are dropped, but in-flight
     * events already tracked in the stream may still complete their lifecycle updates.
     */
    @Synchronized
    fun upsertByRequestId(
        requestId: String,
        create: () -> DashboardEvent,
        transform: (DashboardEvent) -> DashboardEvent,
    ): DashboardEvent? {
        val k = kernel
        if (k != null && k.snapshot.state == KernelState.RESETTING) {
            return null
        }
        val existingIndex = current.events.indexOfFirst { it.requestId == requestId }
        if (existingIndex >= 0) {
            val existing = current.events[existingIndex]
            val updated = transform(existing).copy(id = existing.id, requestId = requestId)
            if (k != null && updated.kernelEpoch != null && updated.kernelEpoch != k.currentEpoch) {
                return null
            }
            val newEvents = current.events.toMutableList()
            newEvents[existingIndex] = updated
            updateState(current.copy(events = newEvents))
            return updated
        } else {
            if (!current.isRecording) {
                return null
            }
            val initial = create().copy(requestId = requestId)
            if (k != null) {
                if (initial.kernelEpoch != null && initial.kernelEpoch != k.currentEpoch) {
                    return null
                }
            }
            val enriched = if (k != null && initial.kernelEpoch == null && k.hasActiveSession) {
                initial.copy(
                    kernelEpoch = k.currentEpoch,
                    sessionKey = k.currentSession?.sessionKey,
                )
            } else {
                initial
            }
            val timestamp = if (enriched.timestamp <= 0L) System.currentTimeMillis() else enriched.timestamp
            val stamped = enriched.copy(id = nextEventId.incrementAndGet(), timestamp = timestamp)
            val next = current.copy(events = (current.events + stamped).takeLast(maxEntries))
            updateState(next)
            return stamped
        }
    }

    /** Replaces the active filter chip (presentation state only). */
    fun setFilter(filter: DashboardFilter) {
        if (current.filter != filter) {
            updateState(current.copy(filter = filter))
        }
    }

    /** Updates the live search query (presentation state only). */
    fun setSearchQuery(query: String) {
        if (current.searchQuery != query) {
            updateState(current.copy(searchQuery = query))
        }
    }

    /** Enables or pauses recording in the event ingestion stream. */
    fun setRecording(recording: Boolean) {
        if (current.isRecording != recording) {
            updateState(current.copy(isRecording = recording))
        }
    }

    /** Toggles recording between active and paused. Returns the new recording state. */
    fun toggleRecording(): Boolean {
        val next = !current.isRecording
        setRecording(next)
        return next
    }

    /** Clears the intercepted event feed without touching presentation state or recording status. */
    fun clear() {
        if (current.events.isNotEmpty()) {
            updateState(current.copy(events = emptyList()))
        }
    }

    /**
     * Resets the event feed, restores presentation filter and search query to defaults,
     * and ensures recording is enabled.
     */
    @Synchronized
    fun reset() {
        updateState(
            DashboardStreamState(
                events = emptyList(),
                filter = DashboardFilter.ALL,
                searchQuery = "",
                isRecording = true,
            )
        )
    }

    private fun updateState(next: DashboardStreamState) {
        current = next
        listeners.forEach { it.onDashboardStateChanged(next) }
    }
}