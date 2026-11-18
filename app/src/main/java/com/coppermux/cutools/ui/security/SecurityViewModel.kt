package com.coppermux.cutools.ui.security

import com.coppermux.cutools.core.security.SecurityEngine
import com.coppermux.cutools.core.security.models.CertificateModel
import com.coppermux.cutools.core.security.models.SecurityActionResult
import com.coppermux.cutools.core.security.models.SecurityCapabilityStatus
import com.coppermux.cutools.core.security.models.SecurityError
import com.coppermux.cutools.core.security.models.SecurityStateModel
import com.coppermux.cutools.core.target.SessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for the Phase 13 Security inspection panel.
 *
 * Binds to the shared [SecurityEngine] (Phase 8A / Phase 12) and exposes a
 * single [state] `StateFlow` consumed by the Compose UI. It issues genuine
 * engine commands — `Security.enable`, `Security.disable`,
 * `Security.getCertificate` — and propagates explicit engine-resolved outcomes
 * into [SecurityUiState]: an engine failure (Unsupported / Unavailable /
 * ExecutionFailed / TargetDisconnected) surfaces as an explicit
 * [SecurityUiState.errorMessage] banner and a matching [SecurityUiState.status],
 * never as synthetic or fabricated TLS / certificate state (No-Fake-Capability
 * rule).
 *
 * Real-time security state streaming is delegated directly to the engine:
 * [securityStateStream] exposes the engine's live `Security.securityStateChanged`
 * [StateFlow], while [refreshSecurityState] mirrors the engine's genuine
 * security snapshot (state, explanations, certificate details, certificate
 * errors) into the UI state. No security state is ever fabricated.
 *
 * Threading: [state] is a thread-safe [StateFlow]; all engine reads are
 * non-blocking volatile reads, and engine callbacks are delivered on the
 * dispatcher's callback thread.
 */
class SecurityViewModel(
    private val engine: SecurityEngine,
) {

    private val _state = MutableStateFlow(SecurityUiState())
    val state: StateFlow<SecurityUiState> = _state

    /** The active attached target session, or null when detached. */
    @Volatile
    var attachedSession: SessionInfo? = null
        private set

    val isAttached: Boolean get() = attachedSession != null

    /**
     * The live real-time `Security.securityStateChanged` stream, delegated to
     * the engine. The UI collects this [StateFlow] to render the security
     * banner as it changes.
     */
    val securityStateStream: StateFlow<SecurityStateModel?> get() = engine.securityStateFlow

    /** Whether `Security` domain notifications are currently enabled. */
    val isEnabled: Boolean get() = engine.enabled

    // ================================================================
    // Target lifecycle
    // ================================================================

    /** Binds the security engine to a newly-attached target session. */
    fun attachTarget(session: SessionInfo) {
        engine.bindSession(session)
        attachedSession = session
        publish(
            SecurityUiState(
                status = engine.capability ?: SecurityCapabilityStatus.UNAVAILABLE,
                enabled = engine.enabled,
                securityState = engine.securityState,
                schemeIsCryptographic = engine.schemeIsCryptographic,
                explanations = engine.explanations,
                certificateDetails = engine.certificateDetails,
                certificateErrors = engine.certificateErrors,
            )
        )
    }

    /** Detaches the security engine, clearing all target-scoped state. */
    fun detachTarget() {
        engine.detach()
        attachedSession = null
        publish(SecurityUiState())
    }

    /** Handles connection loss across the security engine. */
    fun onConnectionLost() {
        engine.onConnectionLost()
        attachedSession = null
        publish(SecurityUiState())
    }

    /** Releases the security engine's event subscriptions. */
    fun shutdown() {
        engine.shutdown()
    }

    // ================================================================
    // Domain lifecycle & commands
    // ================================================================

    /** Enables `Security` domain notifications via the engine. */
    fun enableSecurity(callback: (SecurityActionResult<Unit>) -> Unit = {}) {
        engine.enable { result ->
            when (result) {
                is SecurityActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = SecurityCapabilityStatus.SUPPORTED,
                            enabled = true,
                            errorMessage = null,
                        )
                    )
                }
                is SecurityActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: SecurityCapabilityStatus.FAILED,
                            enabled = false,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /** Disables `Security` domain notifications via the engine. */
    fun disableSecurity(callback: (SecurityActionResult<Unit>) -> Unit = {}) {
        engine.disable { result ->
            when (result) {
                is SecurityActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = SecurityCapabilityStatus.SUPPORTED,
                            enabled = false,
                            errorMessage = null,
                        )
                    )
                }
                is SecurityActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: SecurityCapabilityStatus.FAILED,
                            enabled = false,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Fetches the certificate chain for [origin] via `Security.getCertificate`
     * and records the genuine [CertificateModel] on success.
     */
    fun fetchCertificate(
        origin: String,
        callback: (SecurityActionResult<CertificateModel>) -> Unit = {},
    ) {
        publish(_state.value.copy(isLoading = true, errorMessage = null, origin = origin))
        engine.getCertificate(origin) { result ->
            when (result) {
                is SecurityActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = SecurityCapabilityStatus.SUPPORTED,
                            certificate = result.value,
                            errorMessage = null,
                        )
                    )
                }
                is SecurityActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: SecurityCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Mirrors the engine's current genuine security snapshot (state,
     * explanations, certificate details, certificate errors) into
     * [SecurityUiState]. Used to refresh after domain/event changes.
     */
    fun refreshSecurityState() {
        publish(
            _state.value.copy(
                status = engine.capability ?: SecurityCapabilityStatus.UNAVAILABLE,
                enabled = engine.enabled,
                securityState = engine.securityState,
                schemeIsCryptographic = engine.schemeIsCryptographic,
                explanations = engine.explanations,
                certificateDetails = engine.certificateDetails,
                certificateErrors = engine.certificateErrors,
            )
        )
    }

    /** Updates the origin used for the next certificate fetch. */
    fun updateOrigin(origin: String) {
        publish(_state.value.copy(origin = origin))
    }

    // ================================================================
    // State & error helpers
    // ================================================================

    /**
     * Maps an explicit [SecurityError] to a renderable banner string
     * (No-Fake-Capability: the banner is derived from the genuine engine
     * failure, never synthesized success).
     */
    fun describeError(error: SecurityError): String = when (error) {
        is SecurityError.Unsupported -> "Unsupported: the Security domain is not available on this target."
        is SecurityError.Unavailable -> "Unavailable: no attached target session."
        is SecurityError.TargetDisconnected -> "Failed: target disconnected."
        is SecurityError.ExecutionFailed -> "Failed: ${error.cause.message.orEmpty().ifEmpty { "execution failed" }}"
    }

    private fun publish(next: SecurityUiState) {
        _state.value = next
    }
}