package com.coppermux.cutools.core.kernel

import android.util.Log
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.browser.FaultInterceptorEngine
import com.coppermux.cutools.core.browser.WebViewEngine
import com.coppermux.cutools.core.console.ConsoleInspector
import com.coppermux.cutools.core.css.CssInspector
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.network.FetchInterceptionEngine
import com.coppermux.cutools.core.network.NetworkEngine
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.target.TargetManager
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "CuTools/ResetCoordinator"

private fun logTrace(tag: String, message: String) {
    try {
        Log.d(tag, message)
    } catch (_: Throwable) {
        // Fallback for JVM unit tests where android.util.Log is not mocked
    }
    println("[$tag] $message")
}

/**
 * Phase 2 — Central reset coordinator owned by [DiagnosticKernel].
 *
 * Implements a deterministic, reliable reset barrier and coordinates:
 * 1. Reset initiation (entering RESETTING).
 * 2. Session invalidation and epoch advancement.
 * 3. Diagnostic event ingress suspension.
 * 4. Cancellation of session-scoped operations and pending commands.
 * 5. Safe resolution of outstanding CDP Fetch interceptions and fault rules.
 * 6. Coordinated cleanup of all diagnostic stores and interceptor engines.
 * 7. Active CDP target and browser execution context reconciliation.
 * 8. Successor session establishment only when readiness is confirmed.
 * 9. Diagnostic ingestion resumption.
 * 10. Completion of the reset transition.
 *
 * Prevents pre-reset diagnostic events from contaminating newly reset stores,
 * eliminates dual reset authorities, and handles failures/timeouts safely.
 */
class ResetCoordinator(
    private val kernel: DiagnosticKernel,
) {
    private val resetLock = Any()

    @Volatile
    var commandDispatcher: CommandDispatcher? = null

    @Volatile
    var targetManagerProvider: (() -> TargetManager?)? = null

    @Volatile
    var fetchInterceptionEngine: FetchInterceptionEngine? = null

    @Volatile
    var faultInterceptorEngine: FaultInterceptorEngine? = null

    @Volatile
    var networkEngine: NetworkEngine? = null

    @Volatile
    var consoleInspector: ConsoleInspector? = null

    @Volatile
    var dashboardEventStream: DashboardEventStream? = null

    @Volatile
    var executionEngine: ExecutionEngine? = null

    @Volatile
    var domInspector: DomInspector? = null

    @Volatile
    var cssInspector: CssInspector? = null

    @Volatile
    var webViewEngine: WebViewEngine? = null

    private val customParticipants = CopyOnWriteArrayList<ResetParticipant>()

    fun registerParticipant(participant: ResetParticipant) {
        if (!customParticipants.contains(participant)) {
            customParticipants.add(participant)
        }
    }

    fun unregisterParticipant(participant: ResetParticipant) {
        customParticipants.remove(participant)
    }

    /**
     * Binds multiple diagnostic engines at once for convenience.
     */
    fun bindSubsystems(
        dispatcher: CommandDispatcher? = null,
        targetManagerProvider: (() -> TargetManager?)? = null,
        fetchEngine: FetchInterceptionEngine? = null,
        faultEngine: FaultInterceptorEngine? = null,
        networkEngine: NetworkEngine? = null,
        consoleInspector: ConsoleInspector? = null,
        dashboardEventStream: DashboardEventStream? = null,
        executionEngine: ExecutionEngine? = null,
        domInspector: DomInspector? = null,
        cssInspector: CssInspector? = null,
        webViewEngine: WebViewEngine? = null,
    ) {
        dispatcher?.let { this.commandDispatcher = it }
        targetManagerProvider?.let { this.targetManagerProvider = it }
        fetchEngine?.let { this.fetchInterceptionEngine = it }
        faultEngine?.let { this.faultInterceptorEngine = it }
        networkEngine?.let { this.networkEngine = it }
        consoleInspector?.let { this.consoleInspector = it }
        dashboardEventStream?.let { this.dashboardEventStream = it }
        executionEngine?.let { this.executionEngine = it }
        domInspector?.let { this.domInspector = it }
        cssInspector?.let { this.cssInspector = it }
        webViewEngine?.let { this.webViewEngine = it }
    }

    /**
     * Executes the authoritative 10-step reset sequence.
     * Thread-safe and non-reentrant.
     */
    fun performReset(reason: String = "Reset App"): ResetResult {
        synchronized(resetLock) {
            val oldSession = kernel.currentSession
            logTrace(TAG, "performReset initiated: reason='$reason', currentSession=${oldSession?.sessionKey}")

            // Step 1 & 2: Enter RESETTING, advance epoch, invalidate session identity
            val ticket = kernel.beginReset(reason) ?: run {
                logTrace(TAG, "performReset rejected: reset already in progress or kernel shut down")
                return ResetResult.Rejected("Reset already in progress or kernel shut down")
            }

            try {
                // Step 3: Diagnostic event ingress suspension
                // Ingress filter in CdpEventRouter and DashboardEventStream rejects diagnostic events
                // while kernel is in RESETTING state.

                // Step 4: Cancel or invalidate pending session-scoped operations & CDP commands
                oldSession?.let { sess ->
                    commandDispatcher?.cancelCommandsForSession(sess.cdpSessionId)
                }

                // Step 5: Safely resolve outstanding CDP Fetch interceptions and fault rules
                try {
                    fetchInterceptionEngine?.resolveOutstandingInterceptions()
                } catch (t: Throwable) {
                    logTrace(TAG, "Error resolving Fetch interceptions: ${t.message}")
                }
                faultInterceptorEngine?.clear()

                // Step 6: Diagnostic store cleanup
                clearDiagnosticStores()

                // Custom participants
                customParticipants.forEach { p ->
                    runCatching { p.onResetStores() }.onFailure {
                        logTrace(TAG, "Custom participant onResetStores failed: ${it.message}")
                    }
                }

                // Step 7: Reconcile active CDP target and browser execution context
                val confirmedAttachment = reconcileAttachment(oldSession)
                logTrace(TAG, "Reconciled attachment for successor session: $confirmedAttachment")

                // Step 8, 9, 10: Establish valid successor session only when readiness confirmed,
                // resume diagnostic ingestion, and complete reset transition.
                val result = kernel.completeReset(
                    ticket = ticket,
                    confirmedAttachment = confirmedAttachment,
                    useExplicitAttachment = true,
                )

                // Repair FetchInterceptionEngine session binding after application reset
                if (confirmedAttachment != null) {
                    val activeSession = targetManagerProvider?.invoke()?.activeSession
                    if (activeSession != null) {
                        try {
                            if (fetchInterceptionEngine?.session?.sessionId != activeSession.sessionId) {
                                fetchInterceptionEngine?.bindSession(activeSession)
                            } else if (faultInterceptorEngine?.activeDropRules()?.isNotEmpty() == true) {
                                fetchInterceptionEngine?.syncFaultRules()
                            }
                        } catch (t: Throwable) {
                            logTrace(TAG, "Error re-binding FetchInterceptionEngine: ${t.message}")
                        }
                    }
                }

                logTrace(TAG, "performReset completed: transition=$result, finalState=${kernel.snapshot.state}")
                return ResetResult.Success(kernel.snapshot)
            } catch (t: Throwable) {
                logTrace(TAG, "performReset encountered error: ${t.message}; recovering to safe state")
                // Recovery: ensure kernel never remains permanently in RESETTING
                kernel.completeReset(
                    ticket = ticket,
                    confirmedAttachment = null,
                    useExplicitAttachment = true,
                )
                return ResetResult.Failed(t, kernel.snapshot)
            }
        }
    }

    private fun clearDiagnosticStores() {
        if (networkEngine != null) {
            networkEngine?.clear()
        } else {
            dashboardEventStream?.reset()
        }
        consoleInspector?.clear()
        executionEngine?.clear()
        domInspector?.clear()
        cssInspector?.clear()
        webViewEngine?.reset()
    }

    private fun reconcileAttachment(oldSession: DiagnosticSession?): CdpAttachment? {
        val link = kernel.snapshot.link
        if (link != LinkStatus.UP) {
            logTrace(TAG, "reconcileAttachment: link is $link (not UP); no attachment confirmed")
            return null
        }

        val tm = targetManagerProvider?.invoke()
        if (tm != null) {
            val activeSession = tm.activeSession
            if (activeSession == null) {
                logTrace(TAG, "reconcileAttachment: TargetManager has no active session")
                return null
            }
            val target = tm.discoveredTargets.firstOrNull { it.targetId == activeSession.targetId }
            if (target == null || !target.isPage) {
                logTrace(TAG, "reconcileAttachment: Target ${activeSession.targetId} is not a valid discovered page target")
                return null
            }
            return CdpAttachment(targetId = activeSession.targetId, cdpSessionId = activeSession.sessionId)
        }

        // When no TargetManager is wired (e.g. standalone test harness), confirm old attachment if link was UP
        return oldSession?.attachment
    }
}

/** Optional participant interface for additional engines that participate in coordinated reset. */
fun interface ResetParticipant {
    fun onResetStores()
}
