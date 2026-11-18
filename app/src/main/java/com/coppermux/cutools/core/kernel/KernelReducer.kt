package com.coppermux.cutools.core.kernel

import com.coppermux.cutools.core.kernel.KernelEvent.BeginReset
import com.coppermux.cutools.core.kernel.KernelEvent.CompleteReset
import com.coppermux.cutools.core.kernel.KernelEvent.ConnectRequested
import com.coppermux.cutools.core.kernel.KernelEvent.ConnectionEstablished
import com.coppermux.cutools.core.kernel.KernelEvent.ConnectionFailed
import com.coppermux.cutools.core.kernel.KernelEvent.ConnectionLost
import com.coppermux.cutools.core.kernel.KernelEvent.InvalidateSession
import com.coppermux.cutools.core.kernel.KernelEvent.SetNetworkDrop
import com.coppermux.cutools.core.diagnostic.DiagnosticTracer
import com.coppermux.cutools.core.kernel.KernelEvent.SessionsCleared
import com.coppermux.cutools.core.kernel.KernelEvent.TargetAttached
import com.coppermux.cutools.core.kernel.KernelEvent.TargetDetached
import com.coppermux.cutools.core.kernel.KernelState.ATTACHED
import com.coppermux.cutools.core.kernel.KernelState.CONNECTING
import com.coppermux.cutools.core.kernel.KernelState.ERROR
import com.coppermux.cutools.core.kernel.KernelState.IDLE
import com.coppermux.cutools.core.kernel.KernelState.RESETTING

/**
 * Pure, deterministic state machine for the [DiagnosticKernel].
 *
 * `reduce(snapshot, event, now)` has no side effects and never throws for any
 * (state, event) pair, so every transition is unit-testable in isolation. The
 * kernel serializes calls and commits the returned snapshot atomically.
 *
 * ## Epoch advancement rules
 * The epoch advances (by exactly 1 per step) when:
 * 1. A new diagnostic session is established (CONNECTING/ERROR → ATTACHED).
 * 2. The attached target/CDP session is replaced by a different one.
 * 3. The active session is invalidated by target detachment or TargetManager clear.
 * 4. The connection is lost or fails while CONNECTING, ATTACHED or RESETTING.
 * 5. A reset begins (always), and again when the reset completes if a
 *    successor session is established on a still-live attachment.
 * 6. A session is explicitly invalidated (successor established in the same step
 *    when the CDP attachment is still live).
 *
 * The epoch does NOT advance for idempotent/duplicate events, for connection
 * requests/establishment without a session, or for loss/failure reported while
 * already IDLE/ERROR (those states have already invalidated any session).
 */
internal object KernelReducer {

    data class Reduction(val next: KernelSnapshot, val result: TransitionResult)

    fun reduce(s: KernelSnapshot, event: KernelEvent, now: Long): Reduction = when (event) {
        ConnectRequested -> onConnectRequested(s)
        ConnectionEstablished -> onConnectionEstablished(s)
        is ConnectionLost -> onConnectionLost(s, event)
        is ConnectionFailed -> onConnectionFailed(s, event)
        is TargetAttached -> onTargetAttached(s, event, now)
        is TargetDetached -> onTargetDetached(s, event)
        is SessionsCleared -> onSessionsCleared(s, event)
        is InvalidateSession -> onInvalidateSession(s, event, now)
        is SetNetworkDrop -> onSetNetworkDrop(s, event)
        is BeginReset -> onBeginReset(s, event)
        is CompleteReset -> onCompleteReset(s, event, now)
    }

    // ------------------------------------------------------------------
    // Connection events
    // ------------------------------------------------------------------

    private fun onConnectRequested(s: KernelSnapshot): Reduction = when (s.state) {
        IDLE, ERROR -> commit(
            s, s.copy(state = CONNECTING, link = LinkStatus.CONNECTING, attachment = null, lastError = null),
            "connect requested",
        )
        CONNECTING -> ignore(s, "already connecting")
        // ConnectionManager disconnects before reconnecting, so this is unexpected;
        // handle explicitly: a new connection invalidates the current session.
        ATTACHED -> commit(
            s, invalidated(s, CONNECTING).copy(link = LinkStatus.CONNECTING, attachment = null),
            "connect requested while attached; session invalidated",
        )
        // Record the link fact only; the reset resolves the final state.
        RESETTING -> commit(
            s, s.copy(link = LinkStatus.CONNECTING, attachment = null, lastError = null),
            "connect requested during reset",
        )
    }

    private fun onConnectionEstablished(s: KernelSnapshot): Reduction = when (s.state) {
        IDLE, ERROR -> commit(
            s, s.copy(state = CONNECTING, link = LinkStatus.UP, lastError = null),
            "connection established",
        )
        CONNECTING, RESETTING ->
            if (s.link == LinkStatus.UP) ignore(s, "link already up")
            else commit(s, s.copy(link = LinkStatus.UP), "connection established")
        // Attachment authority comes from TargetManager events, not connection state.
        ATTACHED -> ignore(s, "link already up while attached")
    }

    private fun onConnectionLost(s: KernelSnapshot, e: ConnectionLost): Reduction = when (s.state) {
        IDLE -> ignore(s, "already idle")
        // ERROR already invalidated the session; settle to IDLE without a new epoch.
        ERROR -> commit(
            s, s.copy(state = IDLE, link = LinkStatus.DOWN, attachment = null),
            "connection closed after error: ${e.reason}",
        )
        CONNECTING, ATTACHED -> commit(
            s, invalidated(s, IDLE).copy(link = LinkStatus.DOWN, attachment = null),
            "connection lost: ${e.reason}",
        )
        RESETTING -> commit(
            s, s.copy(epoch = s.epoch + 1, link = LinkStatus.DOWN, attachment = null),
            "connection lost during reset: ${e.reason}",
        )
    }

    private fun onConnectionFailed(s: KernelSnapshot, e: ConnectionFailed): Reduction = when (s.state) {
        IDLE -> commit(
            s, s.copy(state = ERROR, link = LinkStatus.FAILED, attachment = null, lastError = e.message),
            "connection failed: ${e.message}",
        )
        ERROR ->
            if (s.lastError == e.message) ignore(s, "already in error")
            else commit(s, s.copy(lastError = e.message), "connection error updated: ${e.message}")
        CONNECTING, ATTACHED -> commit(
            s, invalidated(s, ERROR).copy(link = LinkStatus.FAILED, attachment = null, lastError = e.message),
            "connection failed: ${e.message}",
        )
        RESETTING -> commit(
            s, s.copy(epoch = s.epoch + 1, link = LinkStatus.FAILED, attachment = null, lastError = e.message),
            "connection failed during reset: ${e.message}",
        )
    }

    // ------------------------------------------------------------------
    // Target events
    // ------------------------------------------------------------------

    private fun onTargetAttached(s: KernelSnapshot, e: TargetAttached, now: Long): Reduction {
        val attachment = CdpAttachment(e.targetId, e.cdpSessionId)
        return when (s.state) {
            // No connection was requested or established: a late/stale attachment.
            IDLE -> reject(s, "attachment ${e.targetId}/${e.cdpSessionId} received with no connection")
            // A successful attachment is direct proof of a live link (mirrors
            // ConnectionManager, which also moves to ATTACHED from ERROR).
            CONNECTING, ERROR -> commit(
                s, established(s.copy(link = LinkStatus.UP, lastError = null), attachment, now),
                "session established on ${e.targetId}",
            )
            ATTACHED ->
                if (s.session?.attachment == attachment) ignore(s, "duplicate attachment")
                else commit(
                    s, established(s, attachment, now),
                    "session replaced: ${s.session?.targetId} -> ${e.targetId}",
                )
            // Record only; the session is (re-)established when the reset completes.
            RESETTING -> commit(
                s, s.copy(link = LinkStatus.UP, attachment = attachment),
                "attachment recorded during reset: ${e.targetId}",
            )
        }
    }

    private fun onTargetDetached(s: KernelSnapshot, e: TargetDetached): Reduction = when (s.state) {
        ATTACHED ->
            if (s.session?.targetId == e.targetId) commit(
                s, invalidated(s, CONNECTING).copy(attachment = null),
                "session detached from ${e.targetId}",
            )
            else ignore(s, "detached target ${e.targetId} is not the active session target")
        RESETTING ->
            if (s.attachment?.targetId == e.targetId) commit(
                s, s.copy(attachment = null), "attachment removed during reset: ${e.targetId}",
            )
            else ignore(s, "detached target ${e.targetId} is not the recorded attachment")
        IDLE, CONNECTING, ERROR -> ignore(s, "no active session")
    }

    private fun onSessionsCleared(s: KernelSnapshot, e: SessionsCleared): Reduction = when (s.state) {
        ATTACHED -> commit(
            s, invalidated(s, CONNECTING).copy(attachment = null),
            "session cleared: ${e.reason}",
        )
        RESETTING ->
            if (s.attachment == null) ignore(s, "no recorded attachment")
            else commit(s, s.copy(attachment = null), "attachment cleared during reset: ${e.reason}")
        IDLE, CONNECTING, ERROR -> ignore(s, "no active session")
    }

    // ------------------------------------------------------------------
    // Kernel-initiated lifecycle operations
    // ------------------------------------------------------------------

    private fun onInvalidateSession(s: KernelSnapshot, e: InvalidateSession, now: Long): Reduction {
        if (s.state != ATTACHED) return ignore(s, "no active session to invalidate")
        val attachment = s.session?.attachment ?: return ignore(s, "no active session to invalidate")
        // Single epoch step: the predecessor is invalid because the epoch moved,
        // and the successor (same CDP attachment) is bound to the new epoch.
        return commit(s, established(s, attachment, now), "session invalidated: ${e.reason}")
    }

    private fun onSetNetworkDrop(s: KernelSnapshot, e: SetNetworkDrop): Reduction {
        DiagnosticTracer.trace(
            category = "KERNEL",
            event = "KERNEL_SET_NETWORK_DROP_REDUCER",
            "targetDropped" to e.dropped,
            "currentDropped" to s.isNetworkDropped,
            "epoch" to s.epoch,
            "state" to s.state.name,
            "sessionKey" to s.session?.sessionKey,
        )
        if (s.isNetworkDropped == e.dropped) return ignore(s, "network drop already ${e.dropped}")
        return commit(s, s.copy(isNetworkDropped = e.dropped), "network drop set to ${e.dropped}")
    }

    private fun onBeginReset(s: KernelSnapshot, e: BeginReset): Reduction {
        if (s.state == RESETTING) return reject(s, "reset already in progress (ticket ${s.resetTicket})")
        return commit(
            s,
            s.copy(state = RESETTING, epoch = s.epoch + 1, session = null, resetTicket = e.ticket),
            "reset begun: ${e.reason}",
        )
    }

    private fun onCompleteReset(s: KernelSnapshot, e: CompleteReset, now: Long): Reduction {
        if (s.state != RESETTING) return reject(s, "no reset in progress")
        if (s.resetTicket != e.ticket) return reject(s, "stale reset ticket ${e.ticket} (active ${s.resetTicket})")
        val base = s.copy(resetTicket = null)
        val attachment = if (e.useExplicitAttachment) e.confirmedAttachment else s.attachment
        val next = when {
            attachment != null && s.link == LinkStatus.UP -> established(base, attachment, now)
            s.link == LinkStatus.UP || s.link == LinkStatus.CONNECTING -> base.copy(state = CONNECTING, attachment = null)
            s.link == LinkStatus.FAILED -> base.copy(state = ERROR, attachment = null)
            else -> base.copy(state = IDLE, attachment = null)
        }
        return commit(s, next, "reset completed -> ${next.state}")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Advance the epoch and drop the session, moving to [to]. */
    private fun invalidated(s: KernelSnapshot, to: KernelState): KernelSnapshot =
        s.copy(state = to, epoch = s.epoch + 1, session = null)

    /** Advance the epoch and establish a new session on [attachment]. */
    private fun established(s: KernelSnapshot, attachment: CdpAttachment, now: Long): KernelSnapshot {
        val epoch = s.epoch + 1
        return s.copy(
            state = ATTACHED,
            epoch = epoch,
            link = LinkStatus.UP,
            attachment = attachment,
            session = DiagnosticSession(
                sessionKey = "dk-$epoch",
                epoch = epoch,
                targetId = attachment.targetId,
                cdpSessionId = attachment.cdpSessionId,
                establishedAtMillis = now,
            ),
        )
    }

    private fun commit(before: KernelSnapshot, after: KernelSnapshot, cause: String): Reduction {
        val next = after.copy(lastCause = cause, transitionSeq = before.transitionSeq + 1)
        return Reduction(
            next,
            TransitionResult.Applied(before.state, next.state, before.epoch, next.epoch),
        )
    }

    private fun ignore(s: KernelSnapshot, reason: String) = Reduction(s, TransitionResult.Ignored(reason))

    private fun reject(s: KernelSnapshot, reason: String) = Reduction(s, TransitionResult.Rejected(reason))
}
