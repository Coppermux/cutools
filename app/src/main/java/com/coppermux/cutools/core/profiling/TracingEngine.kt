package com.coppermux.cutools.core.profiling

import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.profiling.models.TraceEvent
import com.coppermux.cutools.core.profiling.models.TraceRecord
import com.coppermux.cutools.core.profiling.models.TraceTimeline
import com.coppermux.cutools.core.profiling.models.TracingActionResult
import com.coppermux.cutools.core.profiling.models.TracingCategory
import com.coppermux.cutools.core.profiling.models.TracingError
import com.coppermux.cutools.core.profiling.models.TracingStatus
import com.coppermux.cutools.core.profiling.models.UserTimingMark
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Classifies raw [TraceEvent]s into the performance buckets exposed by a
 * [TraceTimeline]. Pure and stateless so it is trivially unit-testable.
 *
 * Classification rules (matching the Phase 7A spec):
 * - A **long task** is any event whose [TraceEvent.dur] exceeds 50ms
 *   (50,000 trace-clock microseconds).
 * - **Rendering blocks** come from [TracingCategory.BLINK_RENDERING] events;
 *   a name containing "layout" additionally classifies as a layout block and a
 *   name containing "paint" as a paint block.
 * - **Script executions** come from [TracingCategory.V8] events.
 * - **User Timing marks** come from [TracingCategory.USER_TIMING] events.
 */
object TraceAnalyzer {

    /** Long-task threshold in trace-clock microseconds (50ms). */
    const val LONG_TASK_THRESHOLD_MICROS = 50_000L

    /**
     * Classifies [events] into a fully-populated [TraceTimeline].
     *
     * @param events Raw trace events to classify (may be empty).
     * @param sessionId The session these events belong to, for provenance.
     */
    fun analyze(events: List<TraceEvent>, sessionId: String? = null): TraceTimeline {
        val longTasks = ArrayList<TraceRecord>()
        val rendering = ArrayList<TraceRecord>()
        val layout = ArrayList<TraceRecord>()
        val paint = ArrayList<TraceRecord>()
        val scripts = ArrayList<TraceRecord>()
        val marks = ArrayList<UserTimingMark>()

        var started = 0L
        var ended = 0L
        for (event in events) {
            val ts = event.ts ?: 0L
            val dur = event.dur ?: 0L
            if (started == 0L || ts < started) started = ts
            if (ts + dur > ended) ended = ts + dur

            val category = TracingCategory.fromWire(event.cat)
            when (category) {
                TracingCategory.V8 -> scripts += TraceRecord(
                    name = event.name ?: "script",
                    category = category,
                    startMicros = ts,
                    durationMicros = dur,
                )

                TracingCategory.BLINK_RENDERING -> {
                    rendering += TraceRecord(
                        name = event.name ?: "render",
                        category = category,
                        startMicros = ts,
                        durationMicros = dur,
                    )
                    val lower = event.name?.lowercase().orEmpty()
                    if (lower.contains("layout")) {
                        layout += TraceRecord(
                            name = event.name ?: "layout",
                            category = category,
                            startMicros = ts,
                            durationMicros = dur,
                        )
                    }
                    if (lower.contains("paint")) {
                        paint += TraceRecord(
                            name = event.name ?: "paint",
                            category = category,
                            startMicros = ts,
                            durationMicros = dur,
                        )
                    }
                }

                TracingCategory.USER_TIMING -> marks += UserTimingMark(
                    name = event.name ?: "mark",
                    tsMicros = ts,
                )

                TracingCategory.TIMELINE,
                TracingCategory.OTHER,
                -> {
                    // No dedicated bucket; may still qualify as a long task below.
                }
            }

            if (dur > LONG_TASK_THRESHOLD_MICROS) {
                longTasks += TraceRecord(
                    name = event.name ?: "long-task",
                    category = category,
                    startMicros = ts,
                    durationMicros = dur,
                )
            }
        }

        return TraceTimeline(
            sessionId = sessionId,
            status = TracingStatus.STOPPED,
            eventCount = events.size,
            longTasks = longTasks,
            renderingBlocks = rendering,
            layoutBlocks = layout,
            paintBlocks = paint,
            scriptExecutions = scripts,
            userTimingMarks = marks,
            startedAtMicros = started,
            endedAtMicros = ended,
        )
    }

    /**
     * Parses a single wire `TraceEvent` payload (a JSON map) into a [TraceEvent].
     * Returns null when [raw] is not a map.
     */
    internal fun parse(raw: Any?): TraceEvent? {
        if (raw !is Map<*, *>) return null
        val ts = (raw["ts"] as? Number)?.toLong()
        val dur = (raw["dur"] as? Number)?.toLong()
        val pid = (raw["pid"] as? Number)?.toLong()
        val tid = (raw["tid"] as? Number)?.toLong()
        val rawArgs = raw["args"]
        val args = if (rawArgs is Map<*, *>) {
            rawArgs.mapKeys { it.key.toString() }
        } else {
            null
        }
        return TraceEvent(
            name = raw["name"] as? String,
            cat = raw["cat"] as? String,
            ph = raw["ph"] as? String,
            ts = ts,
            dur = dur,
            args = args,
            pid = pid,
            tid = tid,
        )
    }
}

/**
 * Orchestrates a `Tracing` session on the currently attached page target via the
 * CDP `Tracing` domain.
 *
 * Sub-Phase 7A — Tracing Engine & Performance Timeline:
 * - [startTracing] issues `Tracing.start` (streaming buffering) and registers
 *   listeners for `Tracing.dataCollected` and `Tracing.tracingComplete`.
 * - Raw trace event arrays delivered across `dataCollected` chunks (plus the
 *   final `Tracing.end` flush) are aggregated by a thread-safe, append-only
 *   chunk buffer that never blocks the main thread.
 * - [endTracing] issues `Tracing.end` and hands the aggregated events to
 *   [TraceAnalyzer] to produce the classified [TraceTimeline] (long tasks,
 *   rendering/layout/paint blocks, script executions, user timing marks).
 * - Session attach/detach lifecycle clears the buffer, unsubscribes every
 *   listener (no leaks), and a generation counter rejects stale async results
 *   delivered after detach / target switch.
 * - Protocol failures resolve to explicit [TracingError] outcomes
 *   (`Unsupported` / `Unavailable` / `ExecutionFailed` / `RecordingInProgress`)
 *   — never to fabricated trace data (No-Fake-Capability rule).
 *
 * Threading: all shared state is `@Volatile` or backed by a `CopyOnWriteArrayList`
 * buffer, so concurrent `dataCollected` events and command callbacks are safe.
 */
class TracingEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The current tracing lifecycle state. */
    @Volatile
    var status: TracingStatus = TracingStatus.IDLE
        private set

    /** The most recent classified timeline, or an empty default when none. */
    @Volatile
    var timeline: TraceTimeline = TraceTimeline()
        private set

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    /** Active event subscriptions for the current tracing session. */
    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    /** Thread-safe aggregate of every trace event received across all chunks. */
    private val buffer = CopyOnWriteArrayList<TraceEvent>()

    /** Whether `Tracing.tracingComplete` was received for the current session. */
    private val completionReceived = AtomicBoolean(false)

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session and resets any prior tracing state. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        clearSubscriptions()
        clearBuffer()
        completionReceived.set(false)
        status = TracingStatus.IDLE
        timeline = TraceTimeline()
    }

    /** Detaches, clearing all target-scoped tracing state and subscriptions. */
    fun detach() {
        generation.incrementAndGet()
        clearSubscriptions()
        clearBuffer()
        completionReceived.set(false)
        session = null
        status = TracingStatus.IDLE
        timeline = TraceTimeline()
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
    // Tracing start / end
    // ================================================================

    /**
     * Starts a streaming trace via `Tracing.start`.
     *
     * Registers `Tracing.dataCollected` / `Tracing.tracingComplete` listeners and
     * transitions to [TracingStatus.RECORDING] on success. A recording already
     * active resolves to [TracingError.RecordingInProgress]; a missing bound
     * session to [TracingError.Unavailable].
     */
    fun startTracing(callback: (TracingActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(TracingActionResult.Failure(TracingError.Unavailable("Tracing domain is not bound to an active session")))
            return
        }
        if (status == TracingStatus.RECORDING || status == TracingStatus.PROCESSING) {
            callback(TracingActionResult.Failure(TracingError.RecordingInProgress()))
            return
        }
        val gen = generation.get()
        clearSubscriptions()
        registerSubscriptions()
        status = TracingStatus.RECORDING
        dispatcher.sendCommand(
            method = "Tracing.start",
            params = mapOf("buffering" to "streaming"),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    status = TracingStatus.RECORDING
                    callback(TracingActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    clearBuffer()
                    status = TracingStatus.IDLE
                    callback(TracingActionResult.Failure(classifyFailure(result.error, "Tracing.start")))
                }
            }
        }
    }

    /**
     * Ends an active trace via `Tracing.end`.
     *
     * The response carries the final `dataCollected` flush; it is aggregated into
     * the buffer, classified by [TraceAnalyzer], and surfaced as a [TraceTimeline]
     * under [timeline] with [TracingStatus.STOPPED].
     */
    fun endTracing(callback: (TracingActionResult<TraceTimeline>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(TracingActionResult.Failure(TracingError.Unavailable("Tracing domain is not bound to an active session")))
            return
        }
        if (status != TracingStatus.RECORDING && status != TracingStatus.PROCESSING) {
            callback(TracingActionResult.Failure(
                TracingError.ExecutionFailed(IllegalStateException("No active tracing session to end"))
            ))
            return
        }
        val gen = generation.get()
        status = TracingStatus.PROCESSING
        dispatcher.sendCommand(
            method = "Tracing.end",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val flush = (result.response.result as? Map<*, *>)?.get("dataCollected")
                    appendChunk(flush)
                    status = TracingStatus.STOPPED
                    timeline = TraceAnalyzer.analyze(buffer.toList(), sess.sessionId)
                    callback(TracingActionResult.Success(timeline))
                }
                is CommandResult.Failure -> {
                    status = TracingStatus.IDLE
                    callback(TracingActionResult.Failure(classifyFailure(result.error, "Tracing.end")))
                }
            }
        }
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("Tracing.dataCollected") { event -> onDataCollected(event) }
        subscriptions += eventRouter.subscribe("Tracing.tracingComplete") { event -> onTracingComplete(event) }
    }

    /** Aggregates a streamed `Tracing.dataCollected` chunk for the active session. */
    private fun onDataCollected(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        if (event.params !is Map<*, *>) return
        val chunk = event.params["value"]
        if (status == TracingStatus.RECORDING) {
            appendChunk(chunk)
        }
    }

    /** Records that tracing has completed for the active session. */
    private fun onTracingComplete(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        completionReceived.set(true)
        if (status == TracingStatus.RECORDING) {
            status = TracingStatus.PROCESSING
        }
    }

    // ================================================================
    // Buffer & helpers
    // ================================================================

    /** Appends a `dataCollected` payload (a list of wire events) to the buffer. */
    private fun appendChunk(chunk: Any?) {
        if (chunk !is List<*>) return
        val parsed = chunk.mapNotNull { TraceAnalyzer.parse(it) }
        if (parsed.isNotEmpty()) {
            buffer.addAll(parsed)
        }
    }

    private fun clearBuffer() {
        buffer.clear()
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }

    /**
     * Maps a CDP command failure to an explicit [TracingError]. A method-not-found
     * error (`-32601`) is [TracingError.Unsupported]; everything else is
     * [TracingError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): TracingError =
        if (error.message.orEmpty().contains("-32601")) {
            TracingError.Unsupported("$method is not available on the attached target")
        } else {
            TracingError.ExecutionFailed(error)
        }
}