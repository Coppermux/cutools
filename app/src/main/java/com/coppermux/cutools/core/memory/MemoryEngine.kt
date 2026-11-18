package com.coppermux.cutools.core.memory

import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.memory.models.DomCountersModel
import com.coppermux.cutools.core.memory.models.HeapSamplingProfileModel
import com.coppermux.cutools.core.memory.models.MemoryActionResult
import com.coppermux.cutools.core.memory.models.MemoryCapabilityStatus
import com.coppermux.cutools.core.memory.models.MemoryError
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle state of the `HeapProfiler` / `Memory` session on the attached target.
 */
enum class MemoryEngineStatus {
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
 * Orchestrates memory & heap profiling on the currently attached page target via
 * the CDP `HeapProfiler` and `Memory` domains.
 *
 * Phase 11A — Memory & Heap Profiler Core Engine:
 * - [enable] / [disable] manage the `HeapProfiler` domain.
 * - [collectGarbage] issues `HeapProfiler.collectGarbage`; [takeHeapSnapshot]
 *   issues `HeapProfiler.takeHeapSnapshot` (with an optional `reportProgress`)
 *   and accumulates `HeapProfiler.addHeapSnapshotChunk` fragments into the
 *   [heapSnapshotStream] `StateFlow`, resolving once
 *   `HeapProfiler.reportHeapSnapshotProgress` reports `finished: true`.
 * - [startSampling] / [stopSampling] manage `HeapProfiler.startSampling` /
 *   `HeapProfiler.stopSampling`; the latter parses the genuine
 *   `SamplingHeapProfile` via [MemoryParser].
 * - [getDOMCounters] issues `Memory.getDOMCounters` and [prepareForLeakDetection]
 *   issues `Memory.prepareForLeakDetection`.
 * - Session attach/detach lifecycle clears target-scoped state and unsubscribes
 *   every listener (no leaks); a generation counter rejects stale async results
 *   delivered after detach / target switch.
 * - Protocol failures resolve to explicit [MemoryError] outcomes (`Unsupported`
 *   / `Unavailable` / `TargetDisconnected` / `ExecutionFailed`) — never to
 *   fabricated heap data (No-Fake-Capability).
 *
 * Threading: shared state is `@Volatile` or guarded by a lock; the snapshot
 * fragment buffer is accumulated under [bufferLock] so concurrent `chunk` events
 * and lifecycle calls are safe. [heapSnapshotStream] is a thread-safe [StateFlow].
 */
class MemoryEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `HeapProfiler` / `Memory` domain capability for the current session/target. */
    @Volatile
    var capability: MemoryCapabilityStatus? = null
        private set

    /** The current memory profiling lifecycle state. */
    @Volatile
    var status: MemoryEngineStatus = MemoryEngineStatus.IDLE
        private set

    /** Accumulating heap snapshot chunk stream (raw snapshot text). */
    private val _heapSnapshotStream = MutableStateFlow<String>("")
    val heapSnapshotStream: StateFlow<String> = _heapSnapshotStream

    /** Guards stale async results / events after detach / re-bind. */
    private val generation = AtomicInteger(0)

    /** Active event subscriptions for the current session. */
    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    /** Incoming chunk text fragments for the in-progress snapshot. */
    private val chunkBuffer = StringBuilder()

    /** Guards access to the snapshot buffer. */
    private val bufferLock = Any()

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session and resets any prior memory-profiling state. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        clearSubscriptions()
        registerSubscriptions()
        clearBuffer()
        status = MemoryEngineStatus.IDLE
        capability = null
        _heapSnapshotStream.value = ""
    }

    /** Detaches, clearing all target-scoped state and subscriptions. */
    fun detach() {
        generation.incrementAndGet()
        clearSubscriptions()
        clearBuffer()
        session = null
        status = MemoryEngineStatus.IDLE
        capability = null
        _heapSnapshotStream.value = ""
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
    // HeapProfiler domain
    // ================================================================

    /** Enables the `HeapProfiler` domain via `HeapProfiler.enable`. */
    fun enable(callback: (MemoryActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = MemoryCapabilityStatus.UNAVAILABLE
            callback(MemoryActionResult.Failure(MemoryError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "HeapProfiler.enable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = MemoryCapabilityStatus.SUPPORTED
                    callback(MemoryActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(MemoryActionResult.Failure(classifyFailure(result.error, "HeapProfiler.enable")))
                }
            }
        }
    }

    /** Disables the `HeapProfiler` domain via `HeapProfiler.disable`. */
    fun disable(callback: (MemoryActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = MemoryCapabilityStatus.UNAVAILABLE
            callback(MemoryActionResult.Failure(MemoryError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "HeapProfiler.disable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    callback(MemoryActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(MemoryActionResult.Failure(classifyFailure(result.error, "HeapProfiler.disable")))
                }
            }
        }
    }

    /** Triggers a garbage collection via `HeapProfiler.collectGarbage`. */
    fun collectGarbage(callback: (MemoryActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = MemoryCapabilityStatus.UNAVAILABLE
            callback(MemoryActionResult.Failure(MemoryError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "HeapProfiler.collectGarbage",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = MemoryCapabilityStatus.SUPPORTED
                    callback(MemoryActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(MemoryActionResult.Failure(classifyFailure(result.error, "HeapProfiler.collectGarbage")))
                }
            }
        }
    }

    /**
     * Starts heap sampling via `HeapProfiler.startSampling`.
     *
     * [samplingIntervalBytes] (when supplied) is serialized as the
     * `samplingInterval` parameter. A missing bound session resolves to
     * [MemoryError.Unavailable]; sampling already active to
     * [MemoryError.ExecutionFailed].
     */
    fun startSampling(
        samplingIntervalBytes: Double? = null,
        callback: (MemoryActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = MemoryCapabilityStatus.UNAVAILABLE
            callback(MemoryActionResult.Failure(MemoryError.Unavailable))
            return
        }
        if (status == MemoryEngineStatus.SAMPLING || status == MemoryEngineStatus.TAKING_SNAPSHOT) {
            callback(MemoryActionResult.Failure(
                MemoryError.ExecutionFailed("Heap sampling is already active")
            ))
            return
        }
        val gen = generation.get()
        status = MemoryEngineStatus.SAMPLING
        val params = if (samplingIntervalBytes != null) {
            mapOf("samplingInterval" to samplingIntervalBytes)
        } else {
            null
        }
        dispatcher.sendCommand(
            method = "HeapProfiler.startSampling",
            params = params,
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = MemoryCapabilityStatus.SUPPORTED
                    status = MemoryEngineStatus.SAMPLING
                    callback(MemoryActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    status = MemoryEngineStatus.IDLE
                    callback(MemoryActionResult.Failure(classifyFailure(result.error, "HeapProfiler.startSampling")))
                }
            }
        }
    }

    /**
     * Stops the active sampling session via `HeapProfiler.stopSampling`.
     *
     * The genuine `SamplingHeapProfile` payload is parsed by [MemoryParser] and
     * surfaced as a [HeapSamplingProfileModel] with [MemoryEngineStatus.STOPPED].
     */
    fun stopSampling(callback: (MemoryActionResult<HeapSamplingProfileModel>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = MemoryCapabilityStatus.UNAVAILABLE
            callback(MemoryActionResult.Failure(MemoryError.Unavailable))
            return
        }
        if (status != MemoryEngineStatus.SAMPLING) {
            callback(MemoryActionResult.Failure(
                MemoryError.ExecutionFailed("No active heap sampling session to stop")
            ))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "HeapProfiler.stopSampling",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result as? Map<*, *>
                    val parsed = payload?.get("profile")?.let { MemoryParser.parseSamplingProfile(it) }
                    if (parsed == null) {
                        status = MemoryEngineStatus.IDLE
                        callback(MemoryActionResult.Failure(
                            MemoryError.ExecutionFailed("HeapProfiler.stopSampling returned a malformed profile")
                        ))
                        return@sendCommand
                    }
                    status = MemoryEngineStatus.STOPPED
                    callback(MemoryActionResult.Success(parsed))
                }
                is CommandResult.Failure -> {
                    status = MemoryEngineStatus.IDLE
                    callback(MemoryActionResult.Failure(classifyFailure(result.error, "HeapProfiler.stopSampling")))
                }
            }
        }
    }

    /**
     * Takes a full heap snapshot via `HeapProfiler.takeHeapSnapshot`.
     *
     * The snapshot is streamed as `HeapProfiler.addHeapSnapshotChunk` fragments
     * and accumulated into [heapSnapshotStream]; the callback resolves once
     * `HeapProfiler.reportHeapSnapshotProgress` reports `finished: true`. A
     * snapshot already in progress resolves to [MemoryError.ExecutionFailed]; a
     * missing bound session to [MemoryError.Unavailable].
     */
    fun takeHeapSnapshot(
        reportProgress: Boolean? = null,
        callback: (MemoryActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = MemoryCapabilityStatus.UNAVAILABLE
            callback(MemoryActionResult.Failure(MemoryError.Unavailable))
            return
        }
        if (status == MemoryEngineStatus.TAKING_SNAPSHOT || status == MemoryEngineStatus.SAMPLING) {
            callback(MemoryActionResult.Failure(
                MemoryError.ExecutionFailed("A heap snapshot is already in progress")
            ))
            return
        }
        val gen = generation.get()
        clearBuffer()
        _heapSnapshotStream.value = ""
        status = MemoryEngineStatus.TAKING_SNAPSHOT
        clearSubscriptions()
        registerSubscriptions()
        val params = if (reportProgress != null) mapOf("reportProgress" to reportProgress) else null
        dispatcher.sendCommand(
            method = "HeapProfiler.takeHeapSnapshot",
            params = params,
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = MemoryCapabilityStatus.SUPPORTED
                    status = MemoryEngineStatus.TAKING_SNAPSHOT
                    callback(MemoryActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    clearBuffer()
                    _heapSnapshotStream.value = ""
                    status = MemoryEngineStatus.IDLE
                    callback(MemoryActionResult.Failure(classifyFailure(result.error, "HeapProfiler.takeHeapSnapshot")))
                }
            }
        }
    }

    // ================================================================
    // Memory domain
    // ================================================================

    /**
     * Requests the current DOM counters via `Memory.getDOMCounters`. On success
     * the genuine [DomCountersModel] is resolved; a non-object result resolves
     * to [MemoryError.ExecutionFailed].
     */
    fun getDOMCounters(callback: (MemoryActionResult<DomCountersModel>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = MemoryCapabilityStatus.UNAVAILABLE
            callback(MemoryActionResult.Failure(MemoryError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Memory.getDOMCounters",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val parsed = MemoryParser.parseDomCounters(result.response.result)
                    if (parsed == null) {
                        capability = MemoryCapabilityStatus.FAILED
                        callback(MemoryActionResult.Failure(
                            MemoryError.ExecutionFailed("Memory.getDOMCounters returned a non-object result")
                        ))
                    } else {
                        capability = MemoryCapabilityStatus.SUPPORTED
                        callback(MemoryActionResult.Success(parsed))
                    }
                }
                is CommandResult.Failure -> {
                    callback(MemoryActionResult.Failure(classifyFailure(result.error, "Memory.getDOMCounters")))
                }
            }
        }
    }

    /** Prepares for leak detection via `Memory.prepareForLeakDetection`. */
    fun prepareForLeakDetection(callback: (MemoryActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = MemoryCapabilityStatus.UNAVAILABLE
            callback(MemoryActionResult.Failure(MemoryError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Memory.prepareForLeakDetection",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = MemoryCapabilityStatus.SUPPORTED
                    callback(MemoryActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(MemoryActionResult.Failure(classifyFailure(result.error, "Memory.prepareForLeakDetection")))
                }
            }
        }
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("HeapProfiler.addHeapSnapshotChunk") { event -> onChunk(event) }
        subscriptions += eventRouter.subscribe("HeapProfiler.reportHeapSnapshotProgress") { event -> onProgress(event) }
    }

    /** Aggregates a streamed snapshot chunk fragment for the active session. */
    private fun onChunk(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        val chunk = MemoryParser.parseHeapSnapshotChunk(event.params) ?: return
        if (status != MemoryEngineStatus.TAKING_SNAPSHOT) return
        val appended: String
        synchronized(bufferLock) {
            chunkBuffer.append(chunk)
            appended = chunkBuffer.toString()
        }
        _heapSnapshotStream.value = appended
    }

    /** Marks the in-progress snapshot complete when progress reports `finished: true`. */
    private fun onProgress(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        if (event.params !is Map<*, *>) return
        val finished = (event.params["finished"] as? Boolean) ?: false
        if (!finished) return
        if (status != MemoryEngineStatus.TAKING_SNAPSHOT) return
        status = MemoryEngineStatus.STOPPED
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun clearBuffer() {
        synchronized(bufferLock) {
            chunkBuffer.setLength(0)
        }
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }

    /**
     * Maps a CDP command failure to an explicit [MemoryError]. A
     * method-not-found error (`-32601`) is [MemoryError.Unsupported]; a
     * connection failure is [MemoryError.TargetDisconnected]; everything else
     * is [MemoryError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): MemoryError {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = MemoryCapabilityStatus.UNSUPPORTED
            return MemoryError.Unsupported
        }
        if (error is ConnectionException) {
            capability = MemoryCapabilityStatus.UNAVAILABLE
            return MemoryError.TargetDisconnected
        }
        capability = MemoryCapabilityStatus.FAILED
        return MemoryError.ExecutionFailed(message.ifEmpty { "Unknown memory error for $method" })
    }
}
