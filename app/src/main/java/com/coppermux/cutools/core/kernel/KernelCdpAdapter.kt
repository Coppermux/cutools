package com.coppermux.cutools.core.kernel

import com.coppermux.cutools.core.connection.ConnectionState
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetInfo
import com.coppermux.cutools.core.target.TargetManagerListener

/**
 * Translates existing ConnectionManager / TargetManager callbacks into
 * [KernelEvent]s. This is the only path through which CDP lifecycle facts
 * reach the [DiagnosticKernel] in Phase 1.
 *
 * Ordering: ConnectionManager registers this adapter as the FIRST
 * TargetManager listener and invokes [onConnectionStateChanged] before
 * notifying its own state listeners, so every downstream observer (CuToolsApp
 * inspector wiring, ShellViewModel) already sees the updated kernel state.
 *
 * Mapping:
 * - CONNECTING → [KernelEvent.ConnectRequested]
 * - CONNECTED / DISCOVERING_TARGETS / TARGET_SELECTED / ATTACHING → [KernelEvent.ConnectionEstablished]
 * - ATTACHED → ignored (identity comes from TargetManager.onAttached, which carries ids)
 * - DISCONNECTING / DISCONNECTED → [KernelEvent.ConnectionLost]
 * - ERROR → [KernelEvent.ConnectionFailed]
 * - TargetManager.onAttached / onDetached / onCleared → TargetAttached / TargetDetached / SessionsCleared
 *
 * Target discovery/selection callbacks are operational and do not affect
 * session identity, so they are ignored.
 */
class KernelCdpAdapter(private val kernel: DiagnosticKernel) : TargetManagerListener {

    fun onConnectionStateChanged(state: ConnectionState, error: Throwable?) {
        when (state) {
            ConnectionState.CONNECTING -> kernel.dispatch(KernelEvent.ConnectRequested)
            ConnectionState.CONNECTED,
            ConnectionState.DISCOVERING_TARGETS,
            ConnectionState.TARGET_SELECTED,
            ConnectionState.ATTACHING -> kernel.dispatch(KernelEvent.ConnectionEstablished)
            ConnectionState.ATTACHED -> Unit
            ConnectionState.DISCONNECTING,
            ConnectionState.DISCONNECTED -> kernel.dispatch(
                KernelEvent.ConnectionLost(error?.message ?: state.name),
            )
            ConnectionState.ERROR -> kernel.dispatch(
                KernelEvent.ConnectionFailed(error?.message ?: "Unknown connection error"),
            )
        }
    }

    override fun onAttached(session: SessionInfo) {
        kernel.dispatch(KernelEvent.TargetAttached(session.targetId, session.sessionId))
    }

    override fun onDetached(targetId: String) {
        kernel.dispatch(KernelEvent.TargetDetached(targetId))
    }

    override fun onCleared() {
        kernel.dispatch(KernelEvent.SessionsCleared("TargetManager cleared"))
    }

    override fun onTargetCreated(target: TargetInfo) = Unit
    override fun onTargetUpdated(target: TargetInfo) = Unit
    override fun onTargetDestroyed(targetId: String) = Unit
    override fun onTargetSelected(target: TargetInfo) = Unit
    override fun onTargetsUpdated(targets: List<TargetInfo>) = Unit
    override fun onError(error: Throwable) = Unit
}
