package com.coppermux.cutools.core.audits

import com.coppermux.cutools.core.audits.models.AuditActionResult
import com.coppermux.cutools.core.audits.models.AuditCapabilityStatus
import com.coppermux.cutools.core.audits.models.AuditError
import com.coppermux.cutools.core.audits.models.AuditIssueModel
import com.coppermux.cutools.core.audits.models.ContrastCheckResultModel
import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates real-time Lighthouse-level issue tracking and contrast checking
 * for the currently attached page target via the CDP `Audits` domain.
 *
 * Phase 10 — Audits Engine:
 * - [enable] / [disable] manage `Audits` domain notifications.
 * - [checkContrast] runs an accessibility contrast check via
 *   `Audits.checkContrast(reportSlowLayout)`.
 * - The engine registers an `Audits.issueAdded` listener, parsing genuine
 *   Lighthouse-level issue payloads into [AuditIssueModel] and exposing them as
 *   a real-time [issues] `StateFlow` — never fabricating an issue
 *   (No-Fake-Capability rule).
 * - Session attach/detach lifecycle resets all target-scoped state and
 *   re-binds the event subscription; a generation counter rejects stale async
 *   results / events delivered after detach / re-bind.
 * - Protocol failures, missing sessions, unavailable methods, and connection
 *   loss each resolve to an explicit [AuditActionResult.Failure] carrying an
 *   [AuditError] (`Unsupported` / `Unavailable` / `TargetDisconnected` /
 *   `ExecutionFailed`).
 *
 * Threading: all shared state is `@Volatile`; the issue log is a
 * [CopyOnWriteArrayList] and [issues] is a thread-safe [StateFlow] so concurrent
 * event streams never lose an update. All Chrome communication reuses the
 * shared [CommandDispatcher] — no second transport or dispatcher is created.
 */
class AuditEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `Audits` domain capability for the current session/target. */
    @Volatile
    var capability: AuditCapabilityStatus? = null
        private set

    /** Whether `Audits` domain notifications are currently enabled. */
    @Volatile
    var enabled: Boolean = false
        private set

    /** Real-time `Audits.issueAdded` issue stream, in observation order. */
    private val _issues = MutableStateFlow<List<AuditIssueModel>>(emptyList())
    val issues: StateFlow<List<AuditIssueModel>> = _issues

    /** Guards stale async results/events after detach / re-bind. */
    private val generation = AtomicInteger(0)

    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    private val issueLog = CopyOnWriteArrayList<AuditIssueModel>()

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session, resets prior target state, and enables the domain. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        resetState()
        clearSubscriptions()
        registerSubscriptions()
        sendEnable()
    }

    /** Detaches, clearing all target-scoped audit state and subscriptions. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        resetState()
        clearSubscriptions()
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    /** Releases all event subscriptions (no listener leaks). */
    fun shutdown() {
        clearSubscriptions()
    }

    // ================================================================
    // Domain lifecycle
    // ================================================================

    /**
     * Enables `Audits` domain notifications via `Audits.enable`. Protocol
     * failures resolve to an explicit [AuditActionResult.Failure].
     */
    fun enable(callback: (AuditActionResult<Unit>) -> Unit = {}) {
        sendEnable(callback)
    }

    /**
     * Disables `Audits` domain notifications via `Audits.disable`.
     */
    fun disable(callback: (AuditActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = AuditCapabilityStatus.UNAVAILABLE
            callback(AuditActionResult.Failure(AuditError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Audits.disable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    enabled = false
                    callback(AuditActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(AuditActionResult.Failure(classifyFailure(result.error, "Audits.disable")))
                }
            }
        }
    }

    /**
     * Runs an accessibility contrast check via `Audits.checkContrast`,
     * serializing the genuine [reportSlowLayout] flag. On success the genuine
     * [ContrastCheckResultModel] is resolved; a non-object result resolves to an
     * explicit [AuditError.ExecutionFailed].
     */
    fun checkContrast(
        reportSlowLayout: Boolean,
        callback: (AuditActionResult<ContrastCheckResultModel>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = AuditCapabilityStatus.UNAVAILABLE
            callback(AuditActionResult.Failure(AuditError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Audits.checkContrast",
            params = mapOf("reportSlowLayout" to reportSlowLayout),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val parsed = AuditParser.parseContrastResult(result.response.result)
                    if (parsed == null) {
                        capability = AuditCapabilityStatus.FAILED
                        callback(AuditActionResult.Failure(AuditError.ExecutionFailed("Audits.checkContrast returned a non-object result")))
                    } else {
                        capability = AuditCapabilityStatus.SUPPORTED
                        callback(AuditActionResult.Success(parsed))
                    }
                }
                is CommandResult.Failure -> {
                    callback(AuditActionResult.Failure(classifyFailure(result.error, "Audits.checkContrast")))
                }
            }
        }
    }

    private fun sendEnable(callback: (AuditActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = AuditCapabilityStatus.UNAVAILABLE
            callback(AuditActionResult.Failure(AuditError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Audits.enable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = AuditCapabilityStatus.SUPPORTED
                    enabled = true
                    callback(AuditActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(AuditActionResult.Failure(classifyFailure(result.error, "Audits.enable")))
                }
            }
        }
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("Audits.issueAdded") { event -> onIssueAdded(event) }
    }

    /** Appends a genuine `Audits.issueAdded` issue if it belongs to the active session. */
    private fun onIssueAdded(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        val params = event.params as? Map<*, *> ?: return
        val issue = AuditParser.parseIssue(params["issue"]) ?: return
        issueLog.add(issue)
        _issues.value = issueLog.toList()
    }

    // ================================================================
    // Error mapping & state helpers
    // ================================================================

    /**
     * Maps a CDP command failure to an explicit [AuditError]. A method-not-found
     * error (`-32601`) is [AuditError.Unsupported]; a connection failure is
     * [AuditError.TargetDisconnected]; everything else is
     * [AuditError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): AuditError {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = AuditCapabilityStatus.UNSUPPORTED
            return AuditError.Unsupported
        }
        if (error is ConnectionException) {
            capability = AuditCapabilityStatus.UNAVAILABLE
            return AuditError.TargetDisconnected
        }
        capability = AuditCapabilityStatus.FAILED
        return AuditError.ExecutionFailed(message.ifEmpty { "Unknown audit error" })
    }

    private fun resetState() {
        capability = null
        enabled = false
        issueLog.clear()
        _issues.value = emptyList()
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }
}