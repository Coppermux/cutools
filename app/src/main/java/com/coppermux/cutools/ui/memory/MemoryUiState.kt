package com.coppermux.cutools.ui.memory

import com.coppermux.cutools.core.memory.models.DomCountersModel
import com.coppermux.cutools.core.memory.models.HeapSamplingProfileModel
import com.coppermux.cutools.core.memory.models.MemoryCapabilityStatus

/**
 * Immutable UI snapshot for the Memory profiler panel (Phase 11B).
 *
 * Mirrors genuine engine truth only — [status] is derived from the
 * [com.coppermux.cutools.core.memory.MemoryEngine] capability, [domCounters] the
 * last genuine `Memory.getDOMCounters` outcome, [samplingActive] / 
 * [samplingProfile] reflect the genuine `HeapProfiler` sampling lifecycle,
 * [heapSnapshot] / [heapSnapshotActive] reflect the streaming
 * `HeapProfiler.takeHeapSnapshot` progress, and [errorMessage] carries an
 * explicit renderable error resolved from an engine failure. No mock or
 * synthetic memory state is ever produced (No-Fake-Capability rule).
 *
 * @property status The explicit `Memory` / `HeapProfiler` capability (SUPPORTED
 *   / UNSUPPORTED / UNAVAILABLE / FAILED).
 * @property domCounters The last genuine DOM counter snapshot, or null.
 * @property samplingActive Whether heap sampling is currently active.
 * @property samplingProfile The last genuine sampling profile, or null.
 * @property heapSnapshotActive Whether a heap snapshot is being streamed.
 * @property heapSnapshot The accumulated genuine heap snapshot chunk text.
 * @property isLoading True while a command is in flight.
 * @property errorMessage A renderable banner string, or null when no error.
 */
data class MemoryUiState(
    val status: MemoryCapabilityStatus = MemoryCapabilityStatus.UNAVAILABLE,
    val domCounters: DomCountersModel? = null,
    val samplingActive: Boolean = false,
    val samplingProfile: HeapSamplingProfileModel? = null,
    val heapSnapshotActive: Boolean = false,
    val heapSnapshot: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)