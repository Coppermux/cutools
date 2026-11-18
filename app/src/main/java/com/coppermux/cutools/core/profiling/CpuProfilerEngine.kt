package com.coppermux.cutools.core.profiling

import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.profiling.models.CpuProfileModel
import com.coppermux.cutools.core.profiling.models.CpuProfileTiming
import com.coppermux.cutools.core.profiling.models.CpuProfilerActionResult
import com.coppermux.cutools.core.profiling.models.CpuProfilerError
import com.coppermux.cutools.core.profiling.models.CpuProfilerStatus
import com.coppermux.cutools.core.profiling.models.CpuNodeTiming
import com.coppermux.cutools.core.profiling.models.ProfileNodeModel
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Derives call-tree self-time / total-time from a genuine CDP
 * [CpuProfileModel]. Pure and stateless so it is trivially unit-testable.
 *
 * Computation (matching the Sub-Phase 7B spec):
 * - The **total duration** is `endTime - startTime` of the profile window.
 * - A node's **self-time** is the sum of `timeDeltas` for every sample whose
 *   leaf node id equals that node's id.
 * - A node's **total-time** is its self-time plus the total-time of every
 *   descendant node (the inclusive cost of the subtree rooted at the node).
 *
 * All values are derived from genuine Chrome profile data; no timing is ever
 * fabricated (No-Fake-Capability rule).
 */
object CpuProfileAnalyzer {

    /**
     * Processes a parsed [profile] into a [CpuProfileTiming] carrying the total
     * duration and per-node self-time / total-time.
     */
    fun analyze(profile: CpuProfileModel, sessionId: String? = null): CpuProfileTiming {
        val duration = (profile.endTime ?: 0.0) - (profile.startTime ?: 0.0)

        val selfTimes = HashMap<Long, Double>()
        for (i in profile.samples.indices) {
            val nodeId = profile.samples[i]
            val delta = profile.timeDeltas.getOrNull(i) ?: 0.0
            selfTimes.merge(nodeId, delta, Double::plus)
        }

        val childrenOf = HashMap<Long, List<Long>>()
        for (node in profile.nodes) {
            childrenOf[node.id] = node.children
        }

        val totalTimes = HashMap<Long, Double>()
        fun total(id: Long): Double = totalTimes.getOrPut(id) {
            val self = selfTimes[id] ?: 0.0
            val childSum = (childrenOf[id] ?: emptyList()).sumOf { total(it) }
            self + childSum
        }

        val timings = profile.nodes.map { node ->
            CpuNodeTiming(
                nodeId = node.id,
                selfTime = selfTimes[node.id] ?: 0.0,
                totalTime = total(node.id),
            )
        }

        return CpuProfileTiming(
            sessionId = sessionId,
            status = CpuProfilerStatus.STOPPED,
            duration = duration,
            profile = profile,
            timings = timings,
        )
    }

    /**
     * Parses a single wire `ProfileNode` payload (a JSON map) into a
     * [ProfileNodeModel]. Returns null when [raw] is not a map or has no valid id.
     */
    internal fun parseNode(raw: Any?): ProfileNodeModel? {
        if (raw !is Map<*, *>) return null
        val id = (raw["id"] as? Number)?.toLong() ?: return null
        val children = (raw["children"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toLong() }
            ?: emptyList()
        return ProfileNodeModel(
            id = id,
            functionName = raw["functionName"] as? String,
            scriptId = raw["scriptId"] as? String,
            url = raw["url"] as? String,
            lineNumber = (raw["lineNumber"] as? Number)?.toInt(),
            columnNumber = (raw["columnNumber"] as? Number)?.toInt(),
            hitCount = (raw["hitCount"] as? Number)?.toInt(),
            children = children,
        )
    }

    /**
     * Parses a wire `Profiler.Profile` result payload (a JSON map) into a
     * [CpuProfileModel]. Returns null when [raw] is not a map (or lacks a valid
     * `nodes` array), so a malformed result can be surfaced as a failure rather
     * than fabricating a profile.
     */
    internal fun parseProfile(raw: Any?): CpuProfileModel? {
        if (raw !is Map<*, *>) return null
        val rawNodes = raw["nodes"]
        if (rawNodes !is List<*>) return null
        val nodes = rawNodes.mapNotNull { parseNode(it) }
        val samples = (raw["samples"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toLong() }
            ?: emptyList()
        val timeDeltas = (raw["timeDeltas"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toDouble() }
            ?: emptyList()
        return CpuProfileModel(
            nodes = nodes,
            startTime = (raw["startTime"] as? Number)?.toDouble(),
            endTime = (raw["endTime"] as? Number)?.toDouble(),
            samples = samples,
            timeDeltas = timeDeltas,
        )
    }
}

/**
 * Orchestrates a JavaScript CPU profiling session on the currently attached page
 * target via the CDP `Profiler` domain.
 *
 * Sub-Phase 7B — JS CPU Profiler:
 * - [enable] / [disable] manage the `Profiler` domain.
 * - [startProfiling] issues `Profiler.start` and transitions to
 *   [CpuProfilerStatus.PROFILING]; [stopProfiling] issues `Profiler.stop`,
 *   parses the genuine `Profile` payload, and derives the call-tree
 *   self-time / total-time via [CpuProfileAnalyzer].
 * - `Profiler.consoleProfileStarted` / `Profiler.consoleProfileFinished`
 *   events are tracked to reflect console-driven profiling activity.
 * - Session attach/detach lifecycle clears target-scoped profile state and
 *   unsubscribes every listener (no leaks); a generation counter rejects stale
 *   async results delivered after detach / target switch.
 * - Protocol failures resolve to explicit [CpuProfilerError] outcomes
 *   (`Unsupported` / `Unavailable` / `ExecutionFailed` / `ProfilingInProgress`)
 *   — never to fabricated profile data (No-Fake-Capability rule).
 *
 * Threading: shared state is `@Volatile` or backed by `CopyOnWriteArrayList`,
 * so concurrent events and command callbacks are safe.
 */
class CpuProfilerEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The current profiling lifecycle state. */
    @Volatile
    var status: CpuProfilerStatus = CpuProfilerStatus.IDLE
        private set

    /** The most recent processed profile, or null when none. */
    @Volatile
    var profile: CpuProfileTiming? = null
        private set

    /** Whether a console-driven profile is currently active. */
    @Volatile
    var consoleProfilingActive: Boolean = false
        private set

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    /** Active event subscriptions for the current session. */
    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session and resets any prior profiling state. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        clearSubscriptions()
        registerSubscriptions()
        status = CpuProfilerStatus.IDLE
        profile = null
        consoleProfilingActive = false
    }

    /** Detaches, clearing all target-scoped profiling state and subscriptions. */
    fun detach() {
        generation.incrementAndGet()
        clearSubscriptions()
        session = null
        status = CpuProfilerStatus.IDLE
        profile = null
        consoleProfilingActive = false
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

    /** Enables the `Profiler` domain via `Profiler.enable`. */
    fun enable(callback: (CpuProfilerActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(CpuProfilerActionResult.Failure(CpuProfilerError.Unavailable("Profiler domain is not bound to an active session")))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Profiler.enable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> callback(CpuProfilerActionResult.Success(Unit))
                is CommandResult.Failure -> callback(classifyFailure(result.error, "Profiler.enable"))
            }
        }
    }

    /** Disables the `Profiler` domain via `Profiler.disable`. */
    fun disable(callback: (CpuProfilerActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(CpuProfilerActionResult.Failure(CpuProfilerError.Unavailable("Profiler domain is not bound to an active session")))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Profiler.disable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> callback(CpuProfilerActionResult.Success(Unit))
                is CommandResult.Failure -> callback(classifyFailure(result.error, "Profiler.disable"))
            }
        }
    }

    // ================================================================
    // Start / stop profiling
    // ================================================================

    /**
     * Starts a CPU sampling session via `Profiler.start`.
     *
     * A missing bound session resolves to [CpuProfilerError.Unavailable]; a
     * session already profiling to [CpuProfilerError.ProfilingInProgress].
     */
    fun startProfiling(callback: (CpuProfilerActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(CpuProfilerActionResult.Failure(CpuProfilerError.Unavailable("Profiler domain is not bound to an active session")))
            return
        }
        if (status == CpuProfilerStatus.PROFILING || status == CpuProfilerStatus.PROCESSING) {
            callback(CpuProfilerActionResult.Failure(CpuProfilerError.ProfilingInProgress("A CPU profiling session is already active")))
            return
        }
        val gen = generation.get()
        status = CpuProfilerStatus.PROFILING
        dispatcher.sendCommand(
            method = "Profiler.start",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    status = CpuProfilerStatus.PROFILING
                    callback(CpuProfilerActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    status = CpuProfilerStatus.IDLE
                    callback(classifyFailure(result.error, "Profiler.start"))
                }
            }
        }
    }

    /**
     * Stops the active sampling session via `Profiler.stop`.
     *
     * The genuine `Profile` payload is parsed and handed to [CpuProfileAnalyzer]
     * to derive the call-tree self-time / total-time, surfaced as a
     * [CpuProfileTiming] under [profile] with [CpuProfilerStatus.STOPPED].
     */
    fun stopProfiling(callback: (CpuProfilerActionResult<CpuProfileTiming>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(CpuProfilerActionResult.Failure(CpuProfilerError.Unavailable("Profiler domain is not bound to an active session")))
            return
        }
        if (status != CpuProfilerStatus.PROFILING && status != CpuProfilerStatus.PROCESSING) {
            callback(CpuProfilerActionResult.Failure(
                CpuProfilerError.ExecutionFailed(IllegalStateException("No active CPU profiling session to stop"))
            ))
            return
        }
        val gen = generation.get()
        status = CpuProfilerStatus.PROCESSING
        dispatcher.sendCommand(
            method = "Profiler.stop",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result as? Map<*, *>
                    val parsed = payload?.get("profile")?.let { CpuProfileAnalyzer.parseProfile(it) }
                    if (parsed == null) {
                        status = CpuProfilerStatus.IDLE
                        callback(CpuProfilerActionResult.Failure(
                            CpuProfilerError.ExecutionFailed(IllegalStateException("Profiler.stop returned a malformed profile"))
                        ))
                        return@sendCommand
                    }
                    status = CpuProfilerStatus.STOPPED
                    profile = CpuProfileAnalyzer.analyze(parsed, sess.sessionId)
                    callback(CpuProfilerActionResult.Success(profile!!))
                }
                is CommandResult.Failure -> {
                    status = CpuProfilerStatus.IDLE
                    callback(classifyFailure(result.error, "Profiler.stop"))
                }
            }
        }
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("Profiler.consoleProfileStarted") { event -> onConsoleProfileStarted(event) }
        subscriptions += eventRouter.subscribe("Profiler.consoleProfileFinished") { event -> onConsoleProfileFinished(event) }
    }

    /** Tracks a console-driven profiling start for the active session. */
    private fun onConsoleProfileStarted(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        consoleProfilingActive = true
    }

    /** Tracks a console-driven profiling finish for the active session. */
    private fun onConsoleProfileFinished(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        consoleProfilingActive = false
        if (status == CpuProfilerStatus.PROFILING) {
            status = CpuProfilerStatus.PROCESSING
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }

    /**
     * Maps a CDP command failure to an explicit [CpuProfilerError]. A
     * method-not-found error (`-32601`) is [CpuProfilerError.Unsupported];
     * everything else is [CpuProfilerError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): CpuProfilerActionResult<Nothing> =
        if (error.message.orEmpty().contains("-32601")) {
            CpuProfilerActionResult.Failure(
                CpuProfilerError.Unsupported("$method is not available on the attached target")
            )
        } else {
            CpuProfilerActionResult.Failure(CpuProfilerError.ExecutionFailed(error))
        }
}
