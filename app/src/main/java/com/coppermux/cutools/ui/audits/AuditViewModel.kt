package com.coppermux.cutools.ui.audits

import com.coppermux.cutools.core.audits.AuditEngine
import com.coppermux.cutools.core.audits.models.AuditActionResult
import com.coppermux.cutools.core.audits.models.AuditCapabilityStatus
import com.coppermux.cutools.core.audits.models.AuditError
import com.coppermux.cutools.core.audits.models.AuditIssueModel
import com.coppermux.cutools.core.audits.models.ContrastCheckResultModel
import com.coppermux.cutools.core.target.SessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for the Phase 11B Audits panel.
 *
 * Binds to the shared [AuditEngine] (Phase 10) and exposes a single [state]
 * `StateFlow` consumed by the Compose UI. It issues genuine engine commands —
 * `enableAudits`, `disableAudits`, `checkContrast` — and propagates explicit
 * engine-resolved outcomes into [AuditUiState]: an engine failure (Unsupported /
 * Unavailable / ExecutionFailed / TargetDisconnected) surfaces as an explicit
 * [AuditUiState.errorMessage] banner and a matching [AuditUiState.status], never
 * as a synthetic or fabricated issue set (No-Fake-Capability rule).
 *
 * Real-time issue streaming is delegated directly to the engine: [engineIssues]
 * exposes the engine's live `Audits.issueAdded` [StateFlow] so the UI can react
 * to each genuine issue as it arrives, while [filteredIssues] applies the active
 * [AuditUiState.searchQuery] filter over that genuine stream. Filtering is pure
 * and deterministic — it never fabricates an issue.
 *
 * Threading: [state] is a thread-safe [StateFlow]; all engine reads are
 * non-blocking volatile reads, and engine callbacks are delivered on the
 * dispatcher's callback thread.
 */
class AuditViewModel(
    private val engine: AuditEngine,
) {

    private val _state = MutableStateFlow(AuditUiState())
    val state: StateFlow<AuditUiState> = _state

    /** The active attached target session, or null when detached. */
    @Volatile
    var attachedSession: SessionInfo? = null
        private set

    val isAttached: Boolean get() = attachedSession != null

    /**
     * The live real-time `Audits.issueAdded` stream, delegated to the engine.
     * The UI collects this [StateFlow] to render issues as they arrive.
     */
    val engineIssues: StateFlow<List<AuditIssueModel>> get() = engine.issues

    /** Whether `Audits` domain notifications are currently enabled. */
    val isEnabled: Boolean get() = engine.enabled

    /**
     * The genuine issue stream filtered by the active search query. When the
     * query is blank the full real-time stream is returned unchanged.
     */
    val filteredIssues: List<AuditIssueModel>
        get() = filterIssues(engine.issues.value, _state.value.searchQuery)

    // ================================================================
    // Target lifecycle
    // ================================================================

    /** Binds the audit engine to a newly-attached target session. */
    fun attachTarget(session: SessionInfo) {
        engine.bindSession(session)
        attachedSession = session
        publish(
            AuditUiState(
                status = engine.capability ?: AuditCapabilityStatus.UNAVAILABLE,
                enabled = engine.enabled,
                issues = engine.issues.value,
            )
        )
    }

    /** Detaches the audit engine, clearing all target-scoped state. */
    fun detachTarget() {
        engine.detach()
        attachedSession = null
        publish(AuditUiState())
    }

    /** Handles connection loss across the audit engine. */
    fun onConnectionLost() {
        engine.onConnectionLost()
        attachedSession = null
        publish(AuditUiState())
    }

    /** Releases the audit engine's event subscriptions. */
    fun shutdown() {
        engine.shutdown()
    }

    // ================================================================
    // Domain lifecycle
    // ================================================================

    /** Enables `Audits` domain notifications via the engine. */
    fun enableAudits(callback: (AuditActionResult<Unit>) -> Unit = {}) {
        engine.enable { result ->
            when (result) {
                is AuditActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = AuditCapabilityStatus.SUPPORTED,
                            enabled = true,
                            errorMessage = null,
                        )
                    )
                }
                is AuditActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: AuditCapabilityStatus.FAILED,
                            enabled = false,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /** Disables `Audits` domain notifications via the engine. */
    fun disableAudits(callback: (AuditActionResult<Unit>) -> Unit = {}) {
        engine.disable { result ->
            when (result) {
                is AuditActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = AuditCapabilityStatus.SUPPORTED,
                            enabled = false,
                            errorMessage = null,
                        )
                    )
                }
                is AuditActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: AuditCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    // ================================================================
    // Commands
    // ================================================================

    /**
     * Runs an accessibility contrast check via `Audits.checkContrast` and
     * publishes the genuine [ContrastCheckResultModel] on success or an
     * explicit error banner on failure.
     */
    fun checkContrast(
        reportSlowLayout: Boolean = true,
        callback: (AuditActionResult<ContrastCheckResultModel>) -> Unit = {},
    ) {
        publish(_state.value.copy(isLoading = true, errorMessage = null))
        engine.checkContrast(reportSlowLayout) { result ->
            when (result) {
                is AuditActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = AuditCapabilityStatus.SUPPORTED,
                            contrastResult = result.data,
                            errorMessage = null,
                        )
                    )
                }
                is AuditActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: AuditCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Updates the live search query. The issue stream itself is untouched;
     * filtering is derived lazily via [filteredIssues].
     */
    fun updateSearchQuery(query: String) {
        publish(_state.value.copy(searchQuery = query))
    }

    /**
     * Mirrors the current genuine engine issue stream into [AuditUiState.issues]
     * (used to refresh the snapshot after target/domain changes).
     */
    fun refreshIssues() {
        publish(_state.value.copy(issues = engine.issues.value))
    }

    // ================================================================
    // Pure issue filtering
    // ================================================================

    /**
     * Filters a genuine issue stream by a case-insensitive substring query over
     * each issue's code name and detail values. An empty query returns the
     * stream unchanged. Never fabricates an issue.
     */
    fun filterIssues(issues: List<AuditIssueModel>, query: String): List<AuditIssueModel> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return issues
        val needle = trimmed.lowercase()
        return issues.filter { issue ->
            issue.code.name.lowercase().contains(needle) ||
                issue.details.values.any { it.lowercase().contains(needle) }
        }
    }

    // ================================================================
    // State & error helpers
    // ================================================================

    /**
     * Maps an explicit [AuditError] to a renderable banner string
     * (No-Fake-Capability: the banner is derived from the genuine engine
     * failure, never synthesized success).
     */
    fun describeError(error: AuditError): String = when (error) {
        is AuditError.Unsupported -> "Unsupported: the Audits domain is not available on this target."
        is AuditError.Unavailable -> "Unavailable: no attached target session."
        is AuditError.TargetDisconnected -> "Failed: target disconnected."
        is AuditError.ExecutionFailed -> "Failed: ${error.message.ifEmpty { "execution failed" }}"
    }

    private fun publish(next: AuditUiState) {
        _state.value = next
    }
}