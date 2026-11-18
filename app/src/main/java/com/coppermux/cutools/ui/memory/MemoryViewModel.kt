package com.coppermux.cutools.ui.memory

import com.coppermux.cutools.core.memory.MemoryEngine
import com.coppermux.cutools.core.memory.MemoryEngineStatus
import com.coppermux.cutools.core.memory.models.DomCountersModel
import com.coppermux.cutools.core.memory.models.HeapSamplingProfileModel
import com.coppermux.cutools.core.memory.models.MemoryActionResult
import com.coppermux.cutools.core.memory.models.MemoryCapabilityStatus
import com.coppermux.cutools.core.memory.models.MemoryError
import com.coppermux.cutools.core.target.SessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for the Phase 11B Memory profiler panel.
 *
 * Binds to the shared [MemoryEngine] (Phase 11A) and exposes a single [state]
 * `StateFlow` consumed by the Compose UI. It issues genuine engine commands —
 * `triggerGarbageCollection`, `fetchDomCounters`, `startSampling`,
 * `stopSampling`, `takeHeapSnapshot` — and propagates explicit engine-resolved
 * outcomes into [MemoryUiState]: an engine failure (Unsupported / Unavailable /
 * ExecutionFailed / TargetDisconnected) surfaces as an explicit
 * [MemoryUiState.errorMessage] banner and a matching [MemoryUiState.status],
 * never as synthetic memory state (No-Fake-Capability rule).
 *
 * Real-time heap snapshot chunk streaming is delegated directly to the engine:
 * [heapSnapshotStream] exposes the engine's accumulating `StateFlow<String>` so
 * the UI can render progress as genuine chunks arrive. [samplingActive] and
 * [heapSnapshotActive] derive from the engine's genuine [MemoryEngineStatus].
 *
 * Threading: [state] is a thread-safe [StateFlow]; all engine reads are
 * non-blocking volatile reads, and engine callbacks are delivered on the
 * dispatcher's callback thread.
 */
class MemoryViewModel(
    private val engine: MemoryEngine,
) {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state

    /** The active attached target session, or null when detached. */
    @Volatile
    var attachedSession: SessionInfo? = null
        private set

    val isAttached: Boolean get() = attachedSession != null

    /**
     * The live accumulating heap snapshot chunk stream, delegated to the engine.
     * The UI collects this [StateFlow] to render snapshot progress in real time.
     */
    val heapSnapshotStream: StateFlow<String> get() = engine.heapSnapshotStream

    /** The engine's genuine `HeapProfiler` / `Memory` lifecycle status. */
    val engineStatus: MemoryEngineStatus get() = engine.status

    // ================================================================
    // Target lifecycle
    // ================================================================

    /** Binds the memory engine to a newly-attached target session. */
    fun attachTarget(session: SessionInfo) {
        engine.bindSession(session)
        attachedSession = session
        publish(
            MemoryUiState(
                status = engine.capability ?: MemoryCapabilityStatus.UNAVAILABLE,
                heapSnapshot = engine.heapSnapshotStream.value,
            )
        )
    }

    /** Detaches the memory engine, clearing all target-scoped state. */
    fun detachTarget() {
        engine.detach()
        attachedSession = null
        publish(MemoryUiState())
    }

    /** Handles connection loss across the memory engine. */
    fun onConnectionLost() {
        engine.onConnectionLost()
        attachedSession = null
        publish(MemoryUiState())
    }

    /** Releases the memory engine's event subscriptions. */
    fun shutdown() {
        engine.shutdown()
    }

    // ================================================================
    // Commands
    // ================================================================

    /**
     * Triggers a garbage collection via `HeapProfiler.collectGarbage`.
     */
    fun triggerGarbageCollection(callback: (MemoryActionResult<Unit>) -> Unit = {}) {
        publish(_state.value.copy(isLoading = true, errorMessage = null))
        engine.collectGarbage { result ->
            when (result) {
                is MemoryActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = MemoryCapabilityStatus.SUPPORTED,
                            errorMessage = null,
                        )
                    )
                }
                is MemoryActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: MemoryCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Fetches the current DOM counters via `Memory.getDOMCounters` and records
     * the genuine [DomCountersModel] on success.
     */
    fun fetchDomCounters(callback: (MemoryActionResult<DomCountersModel>) -> Unit = {}) {
        publish(_state.value.copy(isLoading = true, errorMessage = null))
        engine.getDOMCounters { result ->
            when (result) {
                is MemoryActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = MemoryCapabilityStatus.SUPPORTED,
                            domCounters = result.data,
                            errorMessage = null,
                        )
                    )
                }
                is MemoryActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: MemoryCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Starts heap sampling via `HeapProfiler.startSampling` and reflects the
     * genuine active status on success.
     */
    fun startSampling(
        samplingIntervalBytes: Double? = null,
        callback: (MemoryActionResult<Unit>) -> Unit = {},
    ) {
        publish(_state.value.copy(isLoading = true, errorMessage = null))
        engine.startSampling(samplingIntervalBytes) { result ->
            when (result) {
                is MemoryActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = MemoryCapabilityStatus.SUPPORTED,
                            samplingActive = true,
                            errorMessage = null,
                        )
                    )
                }
                is MemoryActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: MemoryCapabilityStatus.FAILED,
                            samplingActive = engine.status == MemoryEngineStatus.SAMPLING,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Stops the active heap sampling via `HeapProfiler.stopSampling` and records
     * the genuine sampling profile on success.
     */
    fun stopSampling(callback: (MemoryActionResult<HeapSamplingProfileModel>) -> Unit = {}) {
        publish(_state.value.copy(isLoading = true, errorMessage = null))
        engine.stopSampling { result ->
            when (result) {
                is MemoryActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = MemoryCapabilityStatus.SUPPORTED,
                            samplingActive = false,
                            samplingProfile = result.data,
                            errorMessage = null,
                        )
                    )
                }
                is MemoryActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: MemoryCapabilityStatus.FAILED,
                            samplingActive = engine.status == MemoryEngineStatus.SAMPLING,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Takes a full heap snapshot via `HeapProfiler.takeHeapSnapshot`; genuine
     * chunks stream through [heapSnapshotStream] and [heapSnapshotActive] reflects
     * the in-progress state.
     */
    fun takeHeapSnapshot(
        reportProgress: Boolean = true,
        callback: (MemoryActionResult<Unit>) -> Unit = {},
    ) {
        publish(_state.value.copy(isLoading = true, errorMessage = null))
        engine.takeHeapSnapshot(reportProgress) { result ->
            when (result) {
                is MemoryActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = MemoryCapabilityStatus.SUPPORTED,
                            heapSnapshotActive = engine.status == MemoryEngineStatus.TAKING_SNAPSHOT,
                            heapSnapshot = engine.heapSnapshotStream.value,
                            errorMessage = null,
                        )
                    )
                }
                is MemoryActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: MemoryCapabilityStatus.FAILED,
                            heapSnapshotActive = false,
                            heapSnapshot = engine.heapSnapshotStream.value,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Mirrors the current genuine engine heap snapshot chunk text into
     * [MemoryUiState.heapSnapshot].
     */
    fun refreshHeapSnapshot() {
        publish(
            _state.value.copy(
                heapSnapshot = engine.heapSnapshotStream.value,
                heapSnapshotActive = engine.status == MemoryEngineStatus.TAKING_SNAPSHOT,
                samplingActive = engine.status == MemoryEngineStatus.SAMPLING,
            )
        )
    }

    // ================================================================
    // Pure helpers
    // ================================================================

    /**
     * Flattens a genuine [HeapSamplingProfileModel] tree into a depth-tagged
     * list for vertical rendering. Never fabricates a node.
     */
    fun flattenProfile(profile: HeapSamplingProfileModel?): List<SamplingRow> {
        val root = profile?.head ?: return emptyList()
        val rows = mutableListOf<SamplingRow>()
        fun visit(
            node: com.coppermux.cutools.core.memory.models.HeapSamplingNodeModel,
            depth: Int,
        ) {
            rows.add(
                SamplingRow(
                    id = node.id,
                    function = node.callFrameFunction ?: "(anonymous)",
                    url = node.url ?: "",
                    line = node.lineNumber,
                    selfSize = node.selfSize,
                    depth = depth,
                )
            )
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return rows
    }

    // ================================================================
    // State & error helpers
    // ================================================================

    /**
     * Maps an explicit [MemoryError] to a renderable banner string
     * (No-Fake-Capability: the banner is derived from the genuine engine
     * failure, never synthesized success).
     */
    fun describeError(error: MemoryError): String = when (error) {
        is MemoryError.Unsupported -> "Unsupported: the HeapProfiler/Memory domain is not available on this target."
        is MemoryError.Unavailable -> "Unavailable: no attached target session."
        is MemoryError.TargetDisconnected -> "Failed: target disconnected."
        is MemoryError.ExecutionFailed -> "Failed: ${error.message.ifEmpty { "execution failed" }}"
    }

    private fun publish(next: MemoryUiState) {
        _state.value = next
    }
}

/** A depth-tagged row in the flattened genuine sampling profile tree. */
data class SamplingRow(
    val id: Int,
    val function: String,
    val url: String,
    val line: Int?,
    val selfSize: Long,
    val depth: Int,
)