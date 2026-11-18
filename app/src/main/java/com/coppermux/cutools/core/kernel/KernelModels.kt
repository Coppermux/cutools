package com.coppermux.cutools.core.kernel

/**
 * Phase 1 DiagnosticKernel — immutable runtime models.
 *
 * Everything in this file is a plain immutable value. The kernel publishes a
 * new [KernelSnapshot] on every committed transition; nothing is mutated in
 * place, so observers of the kernel's StateFlow always see a coherent,
 * internally consistent view.
 */

/**
 * Explicit kernel runtime state.
 *
 * - [IDLE]: no CDP connection exists or is being attempted.
 * - [CONNECTING]: a CDP connection was requested or is established, but no
 *   diagnostic session is attached yet (includes "connected, awaiting target").
 * - [ATTACHED]: exactly one diagnostic session is active (see [KernelSnapshot.session]).
 * - [RESETTING]: a kernel-level reset is in progress; no session exists until
 *   the reset completes.
 * - [ERROR]: the connection reported a failure; no session exists.
 */
enum class KernelState {
    IDLE,
    CONNECTING,
    ATTACHED,
    RESETTING,
    ERROR,
}

/**
 * Kernel's record of the observed CDP link (transport level), tracked
 * separately from session identity so a reset can resolve to the correct
 * post-reset state without re-querying other components.
 */
enum class LinkStatus {
    /** No transport connection. */
    DOWN,

    /** Connection requested but not yet confirmed. */
    CONNECTING,

    /** Transport connected and able to carry CDP traffic. */
    UP,

    /** Transport/connection reported a failure. */
    FAILED,
}

/**
 * The observed CDP-level target attachment (from TargetManager). This is a
 * *fact about the CDP link*, not a diagnostic session. A diagnostic session
 * is the kernel's epoch-stamped identity built on top of an attachment.
 */
data class CdpAttachment(
    val targetId: String,
    val cdpSessionId: String,
)

/**
 * Kernel-owned diagnostic session identity.
 *
 * @property sessionKey Kernel-generated identity, unique within the process
 *   (derived from the monotonically increasing epoch).
 * @property epoch The epoch this session belongs to. A session is current
 *   if and only if `epoch == KernelSnapshot.epoch` and the kernel is ATTACHED.
 * @property targetId The CDP target this session is bound to.
 * @property cdpSessionId The flattened CDP session id this session is bound to.
 * @property establishedAtMillis Wall-clock time the session was established.
 */
data class DiagnosticSession(
    val sessionKey: String,
    val epoch: Long,
    val targetId: String,
    val cdpSessionId: String,
    val establishedAtMillis: Long,
) {
    val attachment: CdpAttachment get() = CdpAttachment(targetId, cdpSessionId)
}

/**
 * Immutable, observable kernel state.
 *
 * Invariants (enforced by [KernelReducer], verified by tests):
 * - `session != null` if and only if `state == ATTACHED`.
 * - When `session != null`: `session.epoch == epoch`, `link == UP`,
 *   `attachment == session.attachment`.
 * - `resetTicket != null` if and only if `state == RESETTING`.
 * - `epoch` never decreases.
 *
 * @property transitionSeq Count of committed transitions; useful for tests and
 *   diagnostics to detect that a transition happened even when the state enum
 *   did not change.
 */
data class KernelSnapshot(
    val state: KernelState,
    val epoch: Long,
    val session: DiagnosticSession?,
    val link: LinkStatus,
    val attachment: CdpAttachment?,
    val lastError: String?,
    val resetTicket: Long?,
    val lastCause: String?,
    val transitionSeq: Long,
    val isNetworkDropped: Boolean = false,
) {
    /** Whether a diagnostic session is currently active. */
    val hasActiveSession: Boolean get() = state == KernelState.ATTACHED && session != null

    companion object {
        val INITIAL = KernelSnapshot(
            state = KernelState.IDLE,
            epoch = 0L,
            session = null,
            link = LinkStatus.DOWN,
            attachment = null,
            lastError = null,
            resetTicket = null,
            lastCause = null,
            transitionSeq = 0L,
            isNetworkDropped = false,
        )
    }
}

/**
 * Inputs to the kernel state machine. Connection/target events are produced by
 * [KernelCdpAdapter] from ConnectionManager / TargetManager callbacks; reset
 * and invalidation events are produced by [DiagnosticKernel]'s public API.
 */
sealed interface KernelEvent {
    /** A CDP connection attempt started (ConnectionState.CONNECTING). */
    data object ConnectRequested : KernelEvent

    /** The CDP transport is connected (CONNECTED / DISCOVERING / TARGET_SELECTED / ATTACHING). */
    data object ConnectionEstablished : KernelEvent

    /** The CDP connection closed or is being closed (DISCONNECTING / DISCONNECTED). */
    data class ConnectionLost(val reason: String) : KernelEvent

    /** The CDP connection reported an error (ConnectionState.ERROR). */
    data class ConnectionFailed(val message: String) : KernelEvent

    /** TargetManager attached a CDP session to a target. */
    data class TargetAttached(val targetId: String, val cdpSessionId: String) : KernelEvent

    /** TargetManager detached (or destroyed) a target. */
    data class TargetDetached(val targetId: String) : KernelEvent

    /** TargetManager cleared all target/session state (disconnect or rebind). */
    data class SessionsCleared(val reason: String) : KernelEvent

    /**
     * Explicitly invalidate the active session identity. If the underlying CDP
     * attachment is still live, a successor session is established at a new epoch.
     */
    data class InvalidateSession(val reason: String) : KernelEvent

    /** Begin a kernel-level reset. Constructed only by [DiagnosticKernel.beginReset]. */
    data class BeginReset internal constructor(val ticket: Long, val reason: String) : KernelEvent

    /** Authoritatively toggle network drop state. */
    data class SetNetworkDrop(val dropped: Boolean) : KernelEvent

    /** Complete a kernel-level reset. Constructed only by [DiagnosticKernel.completeReset]. */
    data class CompleteReset internal constructor(
        val ticket: Long,
        val confirmedAttachment: CdpAttachment? = null,
        val useExplicitAttachment: Boolean = false,
    ) : KernelEvent
}

/** Outcome of submitting a [KernelEvent] to the kernel. */
sealed interface TransitionResult {
    /** A new snapshot was committed. */
    data class Applied(
        val from: KernelState,
        val to: KernelState,
        val epochBefore: Long,
        val epochAfter: Long,
    ) : TransitionResult

    /** The event was valid but had no effect (idempotent / irrelevant). */
    data class Ignored(val reason: String) : TransitionResult

    /** The event is not permitted in the current state; nothing was committed. */
    data class Rejected(val reason: String) : TransitionResult
}

/** Opaque handle returned by [DiagnosticKernel.beginReset]; required to complete it. */
data class ResetTicket internal constructor(
    val id: Long,
    /** The epoch the kernel advanced to when the reset began. */
    val resetEpoch: Long,
)

/**
 * Diagnostic event provenance tracking (Phase 2).
 *
 * Explicitly associates CDP and WebView diagnostic events with their originating
 * connection, target, session, and kernel epoch to prevent stale-session events
 * from contaminating newly reset diagnostic stores.
 *
 * Invariants:
 * - Session-scoped events carry a non-null [cdpSessionId] and [kernelEpoch].
 * - Connection-level events (such as Target discovery) have `isSessionScoped == false`.
 */
data class EventProvenance(
    val connectionId: String? = null,
    val targetId: String? = null,
    val cdpSessionId: String? = null,
    val kernelEpoch: Long? = null,
    val isSessionScoped: Boolean = cdpSessionId != null,
)

/**
 * Outcome of executing a kernel-coordinated reset operation via [ResetCoordinator].
 */
sealed interface ResetResult {
    /** Coordinated reset successfully executed and settled to [snapshot]. */
    data class Success(val snapshot: KernelSnapshot) : ResetResult

    /** Reset was rejected (e.g. another reset was already in progress or kernel is shut down). */
    data class Rejected(val reason: String) : ResetResult

    /** Coordinated reset failed with [error], but safely recovered to [snapshot]. */
    data class Failed(val error: Throwable, val snapshot: KernelSnapshot) : ResetResult
}
