package com.coppermux.cutools.core.kernel

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CuTools/DiagnosticKernel"

private fun logTrace(tag: String, message: String) {
    try {
        Log.d(tag, message)
    } catch (_: Throwable) {
        // Fallback for JVM unit tests where android.util.Log is not mocked
    }
    println("[$tag] $message")
}

/**
 * Phase 1 DiagnosticKernel — the central authority for diagnostic session
 * identity and lifecycle state.
 *
 * ## Responsibilities (Phase 1)
 * - Owns the single active [DiagnosticSession] and the monotonically
 *   increasing session epoch.
 * - Runs the deterministic state machine in [KernelReducer]
 *   (IDLE / CONNECTING / ATTACHED / RESETTING / ERROR).
 * - Publishes immutable [KernelSnapshot]s through [state].
 * - Provides a session-scoped cancellation boundary ([launchInSession]).
 * - Provides the kernel-level reset bracket ([beginReset] / [completeReset] /
 *   [reset]) for future atomic reset coordination.
 *
 * It does NOT (yet) own the CDP transport, domain engines, diagnostic stores or
 * the WebView. ConnectionManager/TargetManager remain operational owners and
 * feed facts into the kernel via [KernelCdpAdapter].
 *
 * ## Serialization and backpressure
 * Events are applied synchronously on the caller's thread under a single
 * monitor; each event is reduced by a pure function and the resulting snapshot
 * is committed with a single StateFlow write. There is no event queue and no
 * thread per event: producers (the CDP transport/dispatcher callbacks, the UI
 * thread) are naturally backpressured by the lock, whose critical section is
 * O(1) and never suspends, blocks on I/O, or calls out to listeners. Because
 * the commit is a single assignment inside a non-suspending critical section,
 * coroutine cancellation can never leave a partially committed transition.
 *
 * Observers must collect [state]; StateFlow conflation means a slow collector
 * sees the latest snapshot rather than every intermediate one. Callers that
 * need per-transition results use the [TransitionResult] returned by [dispatch].
 *
 * ## Structured concurrency
 * The kernel owns a root [SupervisorJob]. Each diagnostic session gets a child
 * [SupervisorJob]; work launched via [launchInSession] is cancelled as soon as
 * the session's epoch is superseded (detach, replacement, loss, reset,
 * invalidation). Job cancellation happens after the lock is released.
 *
 * ## Stale events (explicitly NOT solved in Phase 1)
 * Epochs make staleness *detectable* ([isCurrent]); they do not by themselves
 * prevent stale logs. CDP events from a previous page that arrive after a reset
 * or navigation are still delivered to the existing domain engines and stores
 * unchanged. Rejecting them requires stamping events with the epoch at
 * ingestion and checking [isCurrent] at the store boundary — a later phase.
 */
class DiagnosticKernel(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    private val _state = MutableStateFlow(KernelSnapshot.INITIAL)

    /** Observable, immutable kernel state. */
    val state: StateFlow<KernelSnapshot> = _state.asStateFlow()

    /** The current snapshot (equivalent to `state.value`). */
    val snapshot: KernelSnapshot get() = _state.value

    /** The current epoch. */
    val currentEpoch: Long get() = _state.value.epoch

    /** The active diagnostic session, or null when not ATTACHED. */
    val currentSession: DiagnosticSession? get() = _state.value.let { if (it.hasActiveSession) it.session else null }

    /** Whether a diagnostic session is currently active. */
    val hasActiveSession: Boolean get() = _state.value.hasActiveSession

    /** Authoritative network drop state. */
    val isNetworkDropped: Boolean get() = _state.value.isNetworkDropped

    private val rootJob = SupervisorJob()
    private val rootScope = CoroutineScope(rootJob + dispatcher + CoroutineName("DiagnosticKernel"))

    // Guarded by [lock].
    private var sessionJob: Job? = null
    private var nextResetTicket = 1L

    @Volatile
    private var isShutdown = false

    /** Reset coordinator owned by this kernel (Phase 2). */
    val resetCoordinator: ResetCoordinator = ResetCoordinator(this)

    /** Historical session registry mapping cdpSessionId to DiagnosticSession. */
    private val sessionHistory = java.util.concurrent.ConcurrentHashMap<String, DiagnosticSession>()

    fun getSessionForCdpSessionId(cdpSessionId: String): DiagnosticSession? =
        sessionHistory[cdpSessionId]

    /**
     * Resolves event provenance for [sessionId] against historical and active sessions.
     * Prevents relabeling: events originating in prior epochs retain their historical epoch.
     */
    fun resolveProvenance(sessionId: String?, connectionId: String? = null): EventProvenance? {
        if (sessionId == null) return null
        val sess = sessionHistory[sessionId] ?: return null
        return EventProvenance(
            connectionId = connectionId,
            targetId = sess.targetId,
            cdpSessionId = sessionId,
            kernelEpoch = sess.epoch,
            isSessionScoped = true,
        )
    }

    /**
     * Executes kernel-coordinated Reset App sequence across all registered subsystems.
     */
    fun performReset(reason: String = "Reset App"): ResetResult =
        resetCoordinator.performReset(reason)

    /**
     * Submits [event] to the state machine. Thread-safe; transitions are
     * serialized and committed atomically. Never throws for any event.
     */
    fun dispatch(event: KernelEvent): TransitionResult {
        val jobToCancel: Job?
        val before: KernelSnapshot
        val reduction: KernelReducer.Reduction
        synchronized(lock) {
            before = _state.value
            if (isShutdown) {
                return TransitionResult.Rejected("kernel is shut down")
            }
            reduction = KernelReducer.reduce(before, event, clock())
            val after = reduction.next
            jobToCancel = if (after !== before && before.session?.epoch != after.session?.epoch) {
                val old = sessionJob
                sessionJob = if (after.session != null) SupervisorJob(rootJob) else null
                old
            } else {
                null
            }
            if (after !== before) {
                _state.value = after
                after.session?.let { sessionHistory[it.cdpSessionId] = it }
            }
        }
        jobToCancel?.cancel(
            CancellationException("Diagnostic session epoch ${before.session?.epoch} superseded"),
        )
        logResult(event, before, reduction)
        return reduction.result
    }

    /** Whether [epoch] is the current epoch AND a session is active at it. */
    fun isCurrent(epoch: Long): Boolean {
        val s = _state.value
        return s.hasActiveSession && s.epoch == epoch
    }

    /**
     * Explicitly invalidates the active session identity. If the underlying
     * CDP attachment is still live, a successor session is established at a
     * new epoch; otherwise nothing happens.
     */
    fun invalidateSession(reason: String): TransitionResult =
        dispatch(KernelEvent.InvalidateSession(reason))

    /**
     * Authoritatively sets the network drop state.
     */
    fun setNetworkDrop(dropped: Boolean): TransitionResult =
        dispatch(KernelEvent.SetNetworkDrop(dropped))

    // ------------------------------------------------------------------
    // Reset bracket
    // ------------------------------------------------------------------

    /**
     * Begins a kernel-level reset: moves to RESETTING, advances the epoch and
     * drops the session identity (cancelling session-scoped work). Returns a
     * ticket that must be passed to [completeReset], or null if a reset is
     * already in progress or the kernel is shut down.
     *
     * Phase 1: this does NOT clear any diagnostic store or browser state; the
     * existing Reset App cleanup remains the operational reset.
     */
    fun beginReset(reason: String): ResetTicket? {
        val ticketId = synchronized(lock) { nextResetTicket++ }
        val result = dispatch(KernelEvent.BeginReset(ticketId, reason))
        return if (result is TransitionResult.Applied) ResetTicket(ticketId, result.epochAfter) else null
    }

    /**
     * Completes the reset identified by [ticket], resolving to the runtime state
     * implied by the observed CDP link: ATTACHED with a successor session if a
     * target attachment is still live, otherwise CONNECTING, ERROR or IDLE.
     * Non-suspending so it is safe to call from `finally` blocks during
     * cancellation.
     */
    fun completeReset(
        ticket: ResetTicket,
        confirmedAttachment: CdpAttachment? = null,
        useExplicitAttachment: Boolean = false,
    ): TransitionResult =
        dispatch(KernelEvent.CompleteReset(ticket.id, confirmedAttachment, useExplicitAttachment))

    /**
     * Runs [block] inside a reset bracket. The reset is always completed, even if
     * [block] throws or the calling coroutine is cancelled.
     *
     * @throws IllegalStateException if a reset is already in progress.
     */
    suspend fun <T> reset(reason: String, block: suspend () -> T): T {
        val ticket = beginReset(reason)
            ?: throw IllegalStateException("DiagnosticKernel reset rejected (already resetting or shut down)")
        try {
            return block()
        } finally {
            completeReset(ticket)
        }
    }

    // ------------------------------------------------------------------
    // Session-scoped work
    // ------------------------------------------------------------------

    /**
     * Launches [block] in the session scope for [expectedEpoch]. Returns null
     * (and launches nothing) if that epoch is not the current active session.
     * The job is cancelled automatically when the session is superseded.
     */
    fun launchInSession(
        expectedEpoch: Long,
        block: suspend CoroutineScope.(DiagnosticSession) -> Unit,
    ): Job? = synchronized(lock) {
        val s = _state.value
        val session = s.session
        val job = sessionJob
        if (isShutdown || !s.hasActiveSession || session == null || session.epoch != expectedEpoch || job == null) {
            null
        } else {
            rootScope.launch(job) { block(session) }
        }
    }

    /** Cancels all kernel-owned work and rejects further events. */
    fun shutdown() {
        synchronized(lock) {
            isShutdown = true
            sessionJob = null
        }
        rootScope.cancel(CancellationException("DiagnosticKernel shut down"))
    }

    private fun logResult(event: KernelEvent, before: KernelSnapshot, reduction: KernelReducer.Reduction) {
        when (val r = reduction.result) {
            is TransitionResult.Applied -> logTrace(
                TAG,
                "event=${event.describe()} ${r.from}->${r.to} epoch=${r.epochBefore}->${r.epochAfter} " +
                    "session=${reduction.next.session?.sessionKey} cause=${reduction.next.lastCause}",
            )
            is TransitionResult.Rejected -> logTrace(
                TAG,
                "REJECTED event=${event.describe()} state=${before.state} epoch=${before.epoch} reason=${r.reason}",
            )
            is TransitionResult.Ignored -> Unit
        }
    }

    private fun KernelEvent.describe(): String = when (this) {
        KernelEvent.ConnectRequested -> "ConnectRequested"
        KernelEvent.ConnectionEstablished -> "ConnectionEstablished"
        else -> toString()
    }
}
