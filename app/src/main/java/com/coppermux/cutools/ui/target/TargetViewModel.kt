package com.coppermux.cutools.ui.target

import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetEngine
import com.coppermux.cutools.core.target.models.TargetActionResult
import com.coppermux.cutools.core.target.models.TargetCapabilityStatus
import com.coppermux.cutools.core.target.models.TargetError
import com.coppermux.cutools.core.target.models.TargetInfoModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for the Phase 13 Target discovery panel.
 *
 * Binds to the shared [TargetEngine] (Phase 12) and exposes a single [state]
 * `StateFlow` consumed by the Compose UI. It issues genuine engine commands —
 * `Target.setDiscoverTargets`, `Target.getTargets`, `Target.attachToTarget`,
 * `Target.detachFromTarget` — and propagates explicit engine-resolved outcomes
 * into [TargetUiState]: an engine failure (Unsupported / Unavailable /
 * TargetDisconnected / ExecutionFailed) surfaces as an explicit
 * [TargetUiState.errorMessage] banner and a matching [TargetUiState.status],
 * never as synthetic or fabricated target state (No-Fake-Capability rule).
 *
 * Real-time target discovery is delegated directly to the engine: [engineTargets]
 * exposes the engine's live `Target.targetCreated` /
 * `Target.targetDestroyed` / `Target.targetInfoChanged` [StateFlow], while
 * [filteredTargets] applies the active [TargetUiState.searchQuery] filter over
 * that genuine stream. Filtering is pure and deterministic — it never
 * fabricates a target.
 *
 * Threading: [state] is a thread-safe [StateFlow]; all engine reads are
 * non-blocking volatile reads, and engine callbacks are delivered on the
 * dispatcher's callback thread.
 */
class TargetViewModel(
    private val engine: TargetEngine,
) {

    private val _state = MutableStateFlow(TargetUiState())
    val state: StateFlow<TargetUiState> = _state

    /** The active attached target session, or null when detached. */
    @Volatile
    var attachedSession: SessionInfo? = null
        private set

    val isAttached: Boolean get() = attachedSession != null

    /**
     * The live real-time `Target` discovery stream, delegated to the engine.
     * The UI collects this [StateFlow] to render targets as they arrive.
     */
    val engineTargets: StateFlow<List<TargetInfoModel>> get() = engine.targets

    /** Whether `Target` discovery notifications are currently enabled. */
    val isDiscoveryEnabled: Boolean get() = _state.value.discoveryEnabled

    // ================================================================
    // Target lifecycle
    // ================================================================

    /** Binds the target engine to a newly-attached target session. */
    fun attachTarget(session: SessionInfo) {
        engine.bindSession(session)
        attachedSession = session
        publish(
            TargetUiState(
                status = engine.capability ?: TargetCapabilityStatus.UNAVAILABLE,
                targets = engine.targets.value,
            )
        )
    }

    /** Detaches the target engine, clearing all target-scoped state. */
    fun detachTarget() {
        engine.detach()
        attachedSession = null
        publish(TargetUiState())
    }

    /** Handles connection loss across the target engine. */
    fun onConnectionLost() {
        engine.onConnectionLost()
        attachedSession = null
        publish(TargetUiState())
    }

    /** Releases the target engine's event subscriptions. */
    fun shutdown() {
        engine.shutdown()
    }

    // ================================================================
    // Commands
    // ================================================================

    /** Enables target discovery notifications via `Target.setDiscoverTargets`. */
    fun enableTargetDiscovery(callback: (TargetActionResult<Unit>) -> Unit = {}) {
        engine.setDiscoverTargets(true) { result ->
            when (result) {
                is TargetActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = TargetCapabilityStatus.SUPPORTED,
                            discoveryEnabled = true,
                            errorMessage = null,
                        )
                    )
                }
                is TargetActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: TargetCapabilityStatus.FAILED,
                            discoveryEnabled = false,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /** Disables target discovery notifications via `Target.setDiscoverTargets`. */
    fun disableTargetDiscovery(callback: (TargetActionResult<Unit>) -> Unit = {}) {
        engine.setDiscoverTargets(false) { result ->
            when (result) {
                is TargetActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = TargetCapabilityStatus.SUPPORTED,
                            discoveryEnabled = false,
                            errorMessage = null,
                        )
                    )
                }
                is TargetActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: TargetCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Attaches to [targetId] via `Target.attachToTarget`, recording the genuine
     * session id as the active attached target on success.
     */
    fun attachToTarget(
        targetId: String,
        callback: (TargetActionResult<String>) -> Unit = {},
    ) {
        publish(_state.value.copy(isLoading = true, errorMessage = null))
        engine.attachToTarget(targetId) { result ->
            when (result) {
                is TargetActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = TargetCapabilityStatus.SUPPORTED,
                            attachedTargetId = targetId,
                            errorMessage = null,
                        )
                    )
                }
                is TargetActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: TargetCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Detaches from [sessionId] via `Target.detachFromTarget`, clearing the
     * attached target on success.
     */
    fun detachFromTarget(
        sessionId: String,
        callback: (TargetActionResult<Unit>) -> Unit = {},
    ) {
        publish(_state.value.copy(isLoading = true, errorMessage = null))
        engine.detachFromTarget(sessionId) { result ->
            when (result) {
                is TargetActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = TargetCapabilityStatus.SUPPORTED,
                            attachedTargetId = null,
                            errorMessage = null,
                        )
                    )
                }
                is TargetActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: TargetCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Requests the current targets via `Target.getTargets` and mirrors the
     * genuine list into [TargetUiState.targets] on success.
     */
    fun refreshTargets(callback: (TargetActionResult<List<TargetInfoModel>>) -> Unit = {}) {
        publish(_state.value.copy(isLoading = true, errorMessage = null))
        engine.getTargets { result ->
            when (result) {
                is TargetActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = TargetCapabilityStatus.SUPPORTED,
                            targets = result.data,
                            errorMessage = null,
                        )
                    )
                }
                is TargetActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: TargetCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Updates the live search query. The target stream itself is untouched;
     * filtering is derived lazily via [filteredTargets].
     */
    fun updateSearchQuery(query: String) {
        publish(_state.value.copy(searchQuery = query))
    }

    /**
     * Mirrors the current genuine engine target stream into [TargetUiState.targets]
     * (used to refresh the snapshot after target/domain changes).
     */
    fun refreshTargetSnapshot() {
        publish(_state.value.copy(targets = engine.targets.value))
    }

    /**
     * The genuine target stream filtered by the active search query. When the
     * query is blank the full real-time stream is returned unchanged.
     */
    val filteredTargets: List<TargetInfoModel>
        get() = filterTargets(engine.targets.value, _state.value.searchQuery)

    // ================================================================
    // Pure target filtering
    // ================================================================

    /**
     * Filters a genuine target stream by a case-insensitive substring query over
     * each target's title, type, and URL. An empty query returns the stream
     * unchanged. Never fabricates a target.
     */
    fun filterTargets(targets: List<TargetInfoModel>, query: String): List<TargetInfoModel> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return targets
        val needle = trimmed.lowercase()
        return targets.filter { target ->
            target.title.lowercase().contains(needle) ||
                target.type.lowercase().contains(needle) ||
                target.url.lowercase().contains(needle)
        }
    }

    // ================================================================
    // State & error helpers
    // ================================================================

    /**
     * Maps an explicit [TargetError] to a renderable banner string
     * (No-Fake-Capability: the banner is derived from the genuine engine
     * failure, never synthesized success).
     */
    fun describeError(error: TargetError): String = when (error) {
        is TargetError.Unsupported -> "Unsupported: the Target domain is not available on this target."
        is TargetError.Unavailable -> "Unavailable: no attached target session."
        is TargetError.TargetDisconnected -> "Failed: target disconnected."
        is TargetError.ExecutionFailed -> "Failed: ${error.message.ifEmpty { "execution failed" }}"
    }

    private fun publish(next: TargetUiState) {
        _state.value = next
    }
}