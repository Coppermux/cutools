package com.coppermux.cutools.core.profiling.models

/**
 * Immutable wrapper for a single fragment streamed by the CDP
 * `HeapProfiler.addHeapSnapshotChunk` event.
 *
 * Chrome serializes a heap snapshot incrementally and delivers it to the client
 * as a stream of raw string fragments in `params.chunk`. The engine aggregates
 * these fragments (in arrival order) into the full snapshot text, so a snapshot
 * is never parsed incrementally — only reassembled. [sessionId] records the CDP
 * session that produced the fragment for cross-session filtering.
 */
data class HeapSnapshotChunkModel(
    val chunk: String = "",
    val sessionId: String? = null,
)

/**
 * Lifecycle state of a `HeapProfiler` session on the attached target.
 */
enum class HeapProfilerStatus {
    /** No snapshot or sampling activity is in progress. */
    IDLE,

    /** `HeapProfiler.takeHeapSnapshot` succeeded; chunks are being streamed in. */
    TAKING_SNAPSHOT,

    /** `HeapProfiler.startSampling` succeeded; heap sampling is active. */
    SAMPLING,

    /** The snapshot / sampling is complete; the final result is available. */
    STOPPED,
}

/**
 * Outcome of a `HeapProfiler` operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with a
 * value or resolves to an explicit [HeapProfilerError] (`Unsupported`,
 * `Unavailable`, `Failed`, `SnapshotInProgress`, `SamplingInProgress`). No mock
 * or hardcoded snapshot / sampling data is ever produced (No-Fake-Capability
 * rule).
 */
sealed interface HeapProfilerActionResult<out T> {

    /** The heap-profiler operation succeeded, carrying [value]. */
    data class Success<T>(val value: T) : HeapProfilerActionResult<T>

    /** The heap-profiler operation failed, carrying an explicit [error]. */
    data class Failure(val error: HeapProfilerError) : HeapProfilerActionResult<Nothing>
}

/**
 * Explicit, non-fabricated error for a failed `HeapProfiler` operation.
 */
sealed interface HeapProfilerError {

    /** The `HeapProfiler` method is not available on the attached target. */
    data class Unsupported(val reason: String) : HeapProfilerError

    /** The `HeapProfiler` domain cannot be reached (e.g., no bound session). */
    data class Unavailable(val detail: String) : HeapProfilerError

    /** The heap-profiler command failed (protocol error, connection loss, ...). */
    data class Failed(val cause: Throwable) : HeapProfilerError

    /** A snapshot is already being taken and cannot be started again. */
    data class SnapshotInProgress(val detail: String) : HeapProfilerError

    /** Heap sampling is already active and cannot be started again. */
    data class SamplingInProgress(val detail: String) : HeapProfilerError
}

/**
 * The fully reassembled result of a completed heap snapshot.
 *
 * Chrome streams the snapshot as raw text fragments via
 * `HeapProfiler.addHeapSnapshotChunk`; the engine joins every genuine fragment
 * (in arrival order) into [snapshotJson]. [chunkCount] records how many
 * fragments were aggregated and [byteSize] the total reassembled size, so callers
 * can reason about snapshot volume without re-scanning the text.
 */
data class HeapSnapshotResult(
    val sessionId: String? = null,
    val status: HeapProfilerStatus = HeapProfilerStatus.STOPPED,
    val snapshotJson: String = "",
    val chunkCount: Int = 0,
    val byteSize: Int = 0,
)

/**
 * Immutable wrapper for a node in a CDP `SamplingHeapProfileNode` (the head of
 * a `HeapProfiler.stopSampling` result).
 *
 * [selfSize] / [totalSize] are the bytes attributed to the node and to its whole
 * subtree respectively; [children] recurses into the sampled call tree. Only
 * fields Chrome actually supplies are surfaced (No-Fake-Capability rule).
 */
data class SamplingHeapProfileNode(
    val selfSize: Double = 0.0,
    val totalSize: Double = 0.0,
    val functionName: String? = null,
    val scriptId: String? = null,
    val url: String? = null,
    val lineNumber: Int? = null,
    val columnNumber: Int? = null,
    val children: List<SamplingHeapProfileNode> = emptyList(),
)

/**
 * Immutable wrapper for a CDP `SamplingHeapProfile` returned by
 * `HeapProfiler.stopSampling`.
 *
 * [head] is the root of the sampled allocation tree; [nodes] is a flattened,
 * depth-first list of every node reachable from [head] for convenient traversal.
 */
data class SamplingHeapProfileModel(
    val head: SamplingHeapProfileNode? = null,
    val nodes: List<SamplingHeapProfileNode> = emptyList(),
)
