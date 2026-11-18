package com.coppermux.cutools.core.profiling

import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.profiling.models.HeapProfilerActionResult
import com.coppermux.cutools.core.profiling.models.HeapProfilerError
import com.coppermux.cutools.core.profiling.models.HeapProfilerStatus
import com.coppermux.cutools.core.profiling.models.HeapSnapshotResult
import com.coppermux.cutools.core.profiling.models.SamplingHeapProfileModel
import com.coppermux.cutools.core.profiling.models.SamplingHeapProfileNode
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Parses / reassembles genuine `HeapProfiler` wire payloads. Pure and stateless
 * so it is trivially unit-testable.
 *
 * Reassembly & parsing rules (matching the Sub-Phase 7C spec):
 * - A heap snapshot is reassembled by joining the raw `chunk` text fragments in
 *   arrival order; the snapshot is never fabricated or interpreted — [assemble]
 *   simply concatenates the genuine chunks and reports the byte size / count.
 * - A `SamplingHeapProfile` (the `stopSampling` result) is parsed from its
 *   `head` node into a [SamplingHeapProfileModel]; `selfSize` / `totalSize` and
 *   `callFrame` fields are mapped exactly as Chrome supplies them.
 */
object HeapProfilerAnalyzer {

    /**
     * Reassembles [chunks] (in order) into a [HeapSnapshotResult]. The joined
     * text is preserved verbatim so callers can verify structural integrity;
     * nothing is synthesized (No-Fake-Capability rule).
     */
    fun assemble(chunks: List<String>, sessionId: String? = null): HeapSnapshotResult {
        val joined = chunks.joinToString(separator = "")
        return HeapSnapshotResult(
            sessionId = sessionId,
            status = HeapProfilerStatus.STOPPED,
            snapshotJson = joined,
            chunkCount = chunks.size,
            byteSize = joined.length,
        )
    }

    /**
     * Parses a wire `SamplingHeapProfile` result payload (a JSON map) into a
     * [SamplingHeapProfileModel]. Returns null when [raw] is not a map or has no
     * valid `head` node, so a malformed result surfaces as a failure rather than
     * fabricated profile data.
     */
    internal fun parseSamplingProfile(raw: Any?): SamplingHeapProfileModel? {
        if (raw !is Map<*, *>) return null
        val head = parseNode(raw["head"]) ?: return null
        val flat = ArrayList<SamplingHeapProfileNode>()
        fun walk(node: SamplingHeapProfileNode) {
            flat.add(node)
            node.children.forEach { walk(it) }
        }
        walk(head)
        return SamplingHeapProfileModel(head = head, nodes = flat)
    }

    /** Parses a single wire `SamplingHeapProfileNode` (a JSON map). */
    internal fun parseNode(raw: Any?): SamplingHeapProfileNode? {
        if (raw !is Map<*, *>) return null
        val callFrame = raw["callFrame"] as? Map<*, *>
        val children = (raw["children"] as? List<*>)
            ?.mapNotNull { parseNode(it) }
            ?: emptyList()
        return SamplingHeapProfileNode(
            selfSize = (raw["selfSize"] as? Number)?.toDouble() ?: 0.0,
            totalSize = (raw["totalSize"] as? Number)?.toDouble() ?: 0.0,
            functionName = callFrame?.get("functionName") as? String,
            scriptId = callFrame?.get("scriptId") as? String,
            url = callFrame?.get("url") as? String,
            lineNumber = (callFrame?.get("lineNumber") as? Number)?.toInt(),
            columnNumber = (callFrame?.get("columnNumber") as? Number)?.toInt(),
            children = children,
        )
    }
}

/**
 * Orchestrates heap snapshotting and heap sampling on the currently attached
 * page target via the CDP `HeapProfiler` domain.
 *
 * Sub-Phase 7C — Heap Snapshot & Memory Engine:
 * - [enable] / [disable] manage the `HeapProfiler` domain.
 * - [takeHeapSnapshot] issues `HeapProfiler.takeHeapSnapshot` (with `reportProgress`)
 *   and registers `HeapProfiler.addHeapSnapshotChunk` / `HeapProfiler.reportHeapSnapshotProgress`
 *   listeners; raw text chunks are aggregated into a full snapshot and the
 *   pending completion callback is resolved when `reportHeapSnapshotProgress`
 *   reports `finished: true`.
 * - [startSampling] / [stopSampling] manage `HeapProfiler.startSampling` /
 *   `HeapProfiler.stopSampling`; the latter parses the genuine
 *   `SamplingHeapProfile` via [HeapProfilerAnalyzer].
 * - Session attach/detach lifecycle clears target-scoped snapshot/sampling state
 *   and unsubscribes every listener (no leaks); a generation counter rejects
 *   stale async results delivered after detach / target switch.
 * - Protocol failures resolve to explicit [HeapProfilerError] outcomes
 *   (`Unsupported` / `Unavailable` / `Failed` / `SnapshotInProgress` /
 *   `SamplingInProgress`) — never to fabricated heap data (No-Fake-Capability).
 *
 * Threading: shared state is `@Volatile` or guarded by a lock; the snapshot
 * fragment buffer is accumulated under [bufferLock] so concurrent `chunk` events
 * and lifecycle calls are safe.
 */
class HeapProfilerEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The current heap-profiler lifecycle state. */
    @Volatile
    var status: HeapProfilerStatus = HeapProfilerStatus.IDLE
        private set

    /** The most recent reassembled heap snapshot, or null when none. */
    @Volatile
    var snapshot: HeapSnapshotResult? = null
        private set

    /** The most recent sampling profile, or null when none. */
    @Volatile
    var samplingProfile: SamplingHeapProfileModel? = null
        private set

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    /** Active event subscriptions for the current session. */
    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    /** Pending snapshot completion callback, resolved when progress reports `finished`. */
    private val snapshotCompletion = AtomicReference<((HeapProfilerActionResult<HeapSnapshotResult>) -> Unit)?>(null)

    /** Incoming chunk text fragments for the in-progress snapshot. */
    private val chunkBuffer = StringBuilder()

    /** Number of chunks aggregated for the in-progress snapshot. */
    private val chunkCount = AtomicInteger(0)

    /** Guards access to the snapshot buffer. */
    private val bufferLock = Any()

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session and resets any prior heap-profiler state. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        clearSubscriptions()
        registerSubscriptions()
        clearBuffer()
        snapshotCompletion.set(null)
        status = HeapProfilerStatus.IDLE
        snapshot = null
        samplingProfile = null
    }

    /** Detaches, clearing all target-scoped state and subscriptions. */
    fun detach() {
        generation.incrementAndGet()
        clearSubscriptions()
        clearBuffer()
        snapshotCompletion.set(null)
        session = null
        status = HeapProfilerStatus.IDLE
        snapshot = null
        samplingProfile = null
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
    // Domain enable / disable
    // ================================================================

    /** Enables the `HeapProfiler` domain via `HeapProfiler.enable`. */
    fun enable(callback: (HeapProfilerActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(HeapProfilerActionResult.Failure(HeapProfilerError.Unavailable("HeapProfiler domain is not bound to an active session")))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "HeapProfiler.enable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> callback(HeapProfilerActionResult.Success(Unit))
                is CommandResult.Failure -> callback(classifyFailure(result.error, "HeapProfiler.enable"))
            }
        }
    }

    /** Disables the `HeapProfiler` domain via `HeapProfiler.disable`. */
    fun disable(callback: (HeapProfilerActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(HeapProfilerActionResult.Failure(HeapProfilerError.Unavailable("HeapProfiler domain is not bound to an active session")))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "HeapProfiler.disable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> callback(HeapProfilerActionResult.Success(Unit))
                is CommandResult.Failure -> callback(classifyFailure(result.error, "HeapProfiler.disable"))
            }
        }
    }

    // ================================================================
    // Heap snapshot
    // ================================================================

    /**
     * Takes a full heap snapshot via `HeapProfiler.takeHeapSnapshot`.
     *
     * The snapshot is streamed to the client as `HeapProfiler.addHeapSnapshotChunk`
     * fragments. This method registers the chunk / progress listeners, resets the
     * fragment buffer, and stores [callback] to be resolved with the reassembled
     * [HeapSnapshotResult] once `HeapProfiler.reportHeapSnapshotProgress` reports
     * `finished: true`. A snapshot already in progress resolves to
     * [HeapProfilerError.SnapshotInProgress]; a missing bound session to
     * [HeapProfilerError.Unavailable].
     */
    fun takeHeapSnapshot(
        reportProgress: Boolean = true,
        callback: (HeapProfilerActionResult<HeapSnapshotResult>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            callback(HeapProfilerActionResult.Failure(HeapProfilerError.Unavailable("HeapProfiler domain is not bound to an active session")))
            return
        }
        if (status == HeapProfilerStatus.TAKING_SNAPSHOT || status == HeapProfilerStatus.SAMPLING) {
            callback(HeapProfilerActionResult.Failure(HeapProfilerError.SnapshotInProgress("A heap snapshot is already in progress")))
            return
        }
        val gen = generation.get()
        clearBuffer()
        snapshotCompletion.set(callback)
        status = HeapProfilerStatus.TAKING_SNAPSHOT
        clearSubscriptions()
        registerSubscriptions()
        dispatcher.sendCommand(
            method = "HeapProfiler.takeHeapSnapshot",
            params = mapOf("reportProgress" to reportProgress),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    status = HeapProfilerStatus.TAKING_SNAPSHOT
                }
                is CommandResult.Failure -> {
                    clearBuffer()
                    snapshotCompletion.set(null)
                    status = HeapProfilerStatus.IDLE
                    callback(classifyFailure(result.error, "HeapProfiler.takeHeapSnapshot"))
                }
            }
        }
    }

    // ================================================================
    // Heap sampling
    // ================================================================

    /**
     * Starts heap sampling via `HeapProfiler.startSampling`.
     *
     * [samplingIntervalBytes] (when supplied) is passed as the `samplingInterval`
     * parameter. A missing bound session resolves to
     * [HeapProfilerError.Unavailable]; sampling already active to
     * [HeapProfilerError.SamplingInProgress].
     */
    fun startSampling(
        samplingIntervalBytes: Int? = null,
        callback: (HeapProfilerActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            callback(HeapProfilerActionResult.Failure(HeapProfilerError.Unavailable("HeapProfiler domain is not bound to an active session")))
            return
        }
        if (status == HeapProfilerStatus.SAMPLING || status == HeapProfilerStatus.TAKING_SNAPSHOT) {
            callback(HeapProfilerActionResult.Failure(HeapProfilerError.SamplingInProgress("Heap sampling is already active")))
            return
        }
        val gen = generation.get()
        status = HeapProfilerStatus.SAMPLING
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
                    status = HeapProfilerStatus.SAMPLING
                    callback(HeapProfilerActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    status = HeapProfilerStatus.IDLE
                    callback(classifyFailure(result.error, "HeapProfiler.startSampling"))
                }
            }
        }
    }

    /**
     * Stops the active sampling session via `HeapProfiler.stopSampling`.
     *
     * The genuine `SamplingHeapProfile` payload is parsed by
     * [HeapProfilerAnalyzer] and surfaced as a [SamplingHeapProfileModel] under
     * [samplingProfile] with [HeapProfilerStatus.STOPPED].
     */
    fun stopSampling(callback: (HeapProfilerActionResult<SamplingHeapProfileModel>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(HeapProfilerActionResult.Failure(HeapProfilerError.Unavailable("HeapProfiler domain is not bound to an active session")))
            return
        }
        if (status != HeapProfilerStatus.SAMPLING) {
            callback(HeapProfilerActionResult.Failure(
                HeapProfilerError.Failed(IllegalStateException("No active heap sampling session to stop"))
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
                    val parsed = payload?.get("profile")?.let { HeapProfilerAnalyzer.parseSamplingProfile(it) }
                    if (parsed == null) {
                        status = HeapProfilerStatus.IDLE
                        callback(HeapProfilerActionResult.Failure(
                            HeapProfilerError.Failed(IllegalStateException("HeapProfiler.stopSampling returned a malformed profile"))
                        ))
                        return@sendCommand
                    }
                    status = HeapProfilerStatus.STOPPED
                    samplingProfile = parsed
                    callback(HeapProfilerActionResult.Success(parsed))
                }
                is CommandResult.Failure -> {
                    status = HeapProfilerStatus.IDLE
                    callback(classifyFailure(result.error, "HeapProfiler.stopSampling"))
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
        if (event.params !is Map<*, *>) return
        val chunk = event.params["chunk"] as? String ?: return
        if (status != HeapProfilerStatus.TAKING_SNAPSHOT) return
        synchronized(bufferLock) {
            chunkBuffer.append(chunk)
        }
        chunkCount.incrementAndGet()
    }

    /** Resolves the pending snapshot when progress reports `finished: true`. */
    private fun onProgress(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        if (event.params !is Map<*, *>) return
        val finished = (event.params["finished"] as? Boolean) ?: false
        if (!finished) return
        if (status != HeapProfilerStatus.TAKING_SNAPSHOT) return
        val chunks: List<String>
        synchronized(bufferLock) {
            chunks = listOf(chunkBuffer.toString())
        }
        val count = chunkCount.get()
        status = HeapProfilerStatus.STOPPED
        snapshot = HeapProfilerAnalyzer.assemble(chunks, active.sessionId).copy(chunkCount = count)
        val cb = snapshotCompletion.getAndSet(null)
        if (cb != null) {
            cb(HeapProfilerActionResult.Success(snapshot!!))
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun clearBuffer() {
        synchronized(bufferLock) {
            chunkBuffer.setLength(0)
        }
        chunkCount.set(0)
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }

    /**
     * Maps a CDP command failure to an explicit [HeapProfilerError]. A
     * method-not-found error (`-32601`) is [HeapProfilerError.Unsupported];
     * everything else is [HeapProfilerError.Failed].
     */
    private fun classifyFailure(error: Throwable, method: String): HeapProfilerActionResult<Nothing> =
        if (error.message.orEmpty().contains("-32601")) {
            HeapProfilerActionResult.Failure(
                HeapProfilerError.Unsupported("$method is not available on the attached target")
            )
        } else {
            HeapProfilerActionResult.Failure(HeapProfilerError.Failed(error))
        }
}
