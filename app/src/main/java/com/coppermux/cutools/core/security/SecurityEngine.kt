package com.coppermux.cutools.core.security

import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.security.models.CertificateDetailsModel
import com.coppermux.cutools.core.security.models.CertificateErrorModel
import com.coppermux.cutools.core.security.models.CertificateModel
import com.coppermux.cutools.core.security.models.SecurityActionResult
import com.coppermux.cutools.core.security.models.SecurityCapabilityStatus
import com.coppermux.cutools.core.security.models.SecurityError
import com.coppermux.cutools.core.security.models.SecurityExplanationModel
import com.coppermux.cutools.core.security.models.SecurityStateModel
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates real-time TLS security inspection for the currently attached page
 * target via the CDP `Security` domain.
 *
 * Sub-Phase 8A — Security Domain Engine & Certificate Audit:
 * - [enable] / [disable] manage `Security` domain notifications.
 * - [setIgnoreCertificateErrors] controls certificate-error suppression via
 *   `Security.setIgnoreCertificateErrors`.
 * - The engine registers `Security.securityStateChanged` and
 *   `Security.certificateError` listeners, parsing genuine TLS security state,
 *   explanation lists, certificate-chain metadata, and certificate validation
 *   errors — never fabricating any of it (No-Fake-Capability rule).
 * - Session attach/detach lifecycle resets all target-scoped security state and
 *   re-binds event subscriptions; a generation counter rejects stale async
 *   results/events delivered after detach.
 * - Protocol failures, missing sessions, unavailable methods, and connection
 *   loss each resolve to an explicit [SecurityActionResult.Failure] carrying a
 *   [SecurityError] (`Unsupported` / `Unavailable` / `ExecutionFailed` /
 *   `TargetDisconnected`).
 *
 * Threading: all shared state is `@Volatile`; the certificate error log is a
 * [CopyOnWriteArrayList] so concurrent event streams never lose an update. All
 * Chrome communication reuses the shared [CommandDispatcher] — no second
 * transport or dispatcher is created.
 */
class SecurityEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `Security` domain capability for the current session/target. */
    @Volatile
    var capability: SecurityCapabilityStatus? = null
        private set

    /** Whether `Security` domain notifications are currently enabled. */
    @Volatile
    var enabled: Boolean = false
        private set

    /** The active TLS security state reported by Chrome, or null. */
    @Volatile
    var securityState: SecurityStateModel? = null
        private set

    /** Real-time `Security.securityStateChanged` stream of the active state. */
    private val _securityStateFlow = MutableStateFlow<SecurityStateModel?>(null)
    val securityStateFlow: StateFlow<SecurityStateModel?> = _securityStateFlow

    /** Whether the current scheme is cryptographic, per the last event. */
    @Volatile
    var schemeIsCryptographic: Boolean = false
        private set

    /** The most recent `Security.securityStateChanged` explanations, or empty. */
    @Volatile
    var explanations: List<SecurityExplanationModel> = emptyList()
        private set

    /** The most recent active certificate details, or null when none. */
    @Volatile
    var certificateDetails: CertificateDetailsModel? = null
        private set

    /** The optional summary string from the last `securityStateChanged`. */
    @Volatile
    var summary: String? = null
        private set

    /** The genuine `Security.certificateError` reports, in observation order. */
    @Volatile
    var certificateErrors: List<CertificateErrorModel> = emptyList()
        private set

    /** Guards stale async results/events after detach / re-bind. */
    private val generation = AtomicInteger(0)

    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    private val errorLog = CopyOnWriteArrayList<CertificateErrorModel>()

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

    /** Detaches, clearing all target-scoped security state and subscriptions. */
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
     * Enables `Security` domain notifications via `Security.enable`. Protocol
     * failures resolve to an explicit [SecurityActionResult.Failure].
     */
    fun enable(callback: (SecurityActionResult<Unit>) -> Unit = {}) {
        sendEnable(callback)
    }

    /**
     * Disables `Security` domain notifications via `Security.disable`.
     */
    fun disable(callback: (SecurityActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = SecurityCapabilityStatus.UNAVAILABLE
            callback(SecurityActionResult.Failure(SecurityError.Unavailable("Security domain is not bound to an active session")))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Security.disable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    enabled = false
                    callback(SecurityActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(SecurityActionResult.Failure(classifyFailure(result.error, "Security.disable")))
                }
            }
        }
    }

    /**
     * Configures whether the browser ignores certificate errors via
     * `Security.setIgnoreCertificateErrors`.
     */
    fun setIgnoreCertificateErrors(
        ignore: Boolean,
        callback: (SecurityActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = SecurityCapabilityStatus.UNAVAILABLE
            callback(SecurityActionResult.Failure(SecurityError.Unavailable("Security domain is not bound to an active session")))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Security.setIgnoreCertificateErrors",
            params = mapOf("ignore" to ignore),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = SecurityCapabilityStatus.SUPPORTED
                    callback(SecurityActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(SecurityActionResult.Failure(classifyFailure(result.error, "Security.setIgnoreCertificateErrors")))
                }
            }
        }
    }

    /**
     * Fetches the certificate chain for [origin] via `Security.getCertificate`.
     *
     * A protocol failure or a non-object response resolves to an explicit
     * [SecurityActionResult.Failure] — no certificate is ever fabricated
     * (No-Fake-Capability rule).
     */
    fun getCertificate(
        origin: String,
        callback: (SecurityActionResult<CertificateModel>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = SecurityCapabilityStatus.UNAVAILABLE
            callback(SecurityActionResult.Failure(SecurityError.Unavailable("Security domain is not bound to an active session")))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Security.getCertificate",
            params = mapOf("origin" to origin),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val certificate = SecurityParser.parseGetCertificate(result.response.result)
                    if (certificate == null) {
                        capability = SecurityCapabilityStatus.FAILED
                        callback(SecurityActionResult.Failure(
                            SecurityError.ExecutionFailed(RuntimeException("Security.getCertificate returned a non-object result")),
                        ))
                    } else {
                        capability = SecurityCapabilityStatus.SUPPORTED
                        callback(SecurityActionResult.Success(certificate))
                    }
                }
                is CommandResult.Failure -> {
                    callback(SecurityActionResult.Failure(classifyFailure(result.error, "Security.getCertificate")))
                }
            }
        }
    }

    private fun sendEnable(callback: (SecurityActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = SecurityCapabilityStatus.UNAVAILABLE
            callback(SecurityActionResult.Failure(SecurityError.Unavailable("Security domain is not bound to an active session")))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Security.enable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = SecurityCapabilityStatus.SUPPORTED
                    enabled = true
                    callback(SecurityActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(SecurityActionResult.Failure(classifyFailure(result.error, "Security.enable")))
                }
            }
        }
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("Security.securityStateChanged") { event -> onSecurityStateChanged(event) }
        subscriptions += eventRouter.subscribe("Security.certificateError") { event -> onCertificateError(event) }
    }

    /** Applies a `Security.securityStateChanged` event if it belongs to the active session. */
    private fun onSecurityStateChanged(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        val params = event.params as? Map<*, *> ?: return
        val state = SecurityStateModel.fromWire(params["securityState"] as? String)
        securityState = state
        _securityStateFlow.value = state
        schemeIsCryptographic = (params["schemeIsCryptographic"] as? Boolean) ?: false
        summary = params["summary"] as? String
        explanations = parseExplanations(params["explanations"])
        certificateDetails = SecurityParser.parseCertificateDetails(params["certificateSecurityState"])
    }

    /** Records a `Security.certificateError` event for the active session. */
    private fun onCertificateError(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        val error = SecurityParser.parseCertificateError(event.params) ?: return
        errorLog.add(error)
        certificateErrors = errorLog.toList()
    }

    private fun parseExplanations(raw: Any?): List<SecurityExplanationModel> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { SecurityParser.parseExplanation(it) }
    }

    // ================================================================
    // Error mapping & state helpers
    // ================================================================

    /**
     * Maps a CDP command failure to an explicit [SecurityError]. A
     * method-not-found error (`-32601`) is [SecurityError.Unsupported]; a
     * connection failure is [SecurityError.TargetDisconnected]; everything else
     * is [SecurityError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): SecurityError {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = SecurityCapabilityStatus.UNSUPPORTED
            return SecurityError.Unsupported("$method is not available on the attached target")
        }
        if (error is ConnectionException) {
            capability = SecurityCapabilityStatus.UNAVAILABLE
            return SecurityError.TargetDisconnected()
        }
        capability = SecurityCapabilityStatus.FAILED
        return SecurityError.ExecutionFailed(error)
    }

    private fun resetState() {
        capability = null
        enabled = false
        securityState = null
        _securityStateFlow.value = null
        schemeIsCryptographic = false
        explanations = emptyList()
        certificateDetails = null
        summary = null
        certificateErrors = emptyList()
        errorLog.clear()
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }
}