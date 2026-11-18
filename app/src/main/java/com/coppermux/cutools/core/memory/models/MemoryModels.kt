package com.coppermux.cutools.core.memory.models

/**
 * Immutable snapshot of a genuine `Memory.getDOMCounters` response.
 *
 * Mirrors the CDP `DOMCounters` `documents` / `nodes` / `jsEventListeners`
 * fields Chrome reports. No counter is ever fabricated (No-Fake-Capability rule).
 */
data class DomCountersModel(
    val documents: Int,
    val nodes: Int,
    val jsEventListeners: Int,
)

/**
 * Immutable wrapper for a node in a CDP `SamplingHeapProfileNode` (the head of
 * a `HeapProfiler.stopSampling` result).
 *
 * [id] is the node id, [callFrameFunction] / [scriptId] / [url] /
 * [lineNumber] / [columnNumber] are the call-frame details Chrome supplies,
 * [selfSize] is the bytes attributed directly to the node, and [children]
 * recurses into the sampled call tree. Only fields Chrome actually supplies are
 * surfaced (No-Fake-Capability rule).
 */
data class HeapSamplingNodeModel(
    val id: Int,
    val callFrameFunction: String? = null,
    val scriptId: String? = null,
    val url: String? = null,
    val lineNumber: Int? = null,
    val columnNumber: Int? = null,
    val selfSize: Long = 0L,
    val children: List<HeapSamplingNodeModel> = emptyList(),
)

/**
 * Immutable wrapper for a CDP `SamplingHeapProfile` returned by
 * `HeapProfiler.stopSampling`.
 *
 * [head] is the root of the sampled allocation tree, [samplesCount] the number
 * of samples Chrome reported in the profile's `samples` array, and [timestamp]
 * the local observation timestamp (ms epoch).
 */
data class HeapSamplingProfileModel(
    val head: HeapSamplingNodeModel? = null,
    val samplesCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Outcome of a `HeapProfiler` / `Memory` domain operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with a
 * value or resolves to an explicit [MemoryError] (`Unsupported`, `Unavailable`,
 * `TargetDisconnected`, `ExecutionFailed`). No mock or hardcoded memory state is
 * ever produced (No-Fake-Capability rule).
 *
 * @param T The value carried on success.
 */
sealed class MemoryActionResult<out T> {

    /** The memory operation succeeded, carrying [data]. */
    data class Success<out T>(val data: T) : MemoryActionResult<T>()

    /** The memory operation failed, carrying an explicit [error]. */
    data class Failure(val error: MemoryError) : MemoryActionResult<Nothing>()
}

/**
 * Explicit, non-fabricated error for a failed `HeapProfiler` / `Memory` operation.
 */
sealed class MemoryError {

    /** A `HeapProfiler` / `Memory` method is not available on the attached target. */
    object Unsupported : MemoryError()

    /** The `HeapProfiler` / `Memory` domain cannot be reached (e.g., no bound session). */
    object Unavailable : MemoryError()

    /** The attached target disconnected while the operation was in flight. */
    object TargetDisconnected : MemoryError()

    /** The command failed (protocol error, parsing error, ...). */
    data class ExecutionFailed(val message: String) : MemoryError()
}
