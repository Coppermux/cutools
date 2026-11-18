package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.console.ConsoleCapabilityStatus
import com.coppermux.cutools.core.console.ConsoleEntry
import com.coppermux.cutools.core.console.ConsoleEntryKind
import com.coppermux.cutools.core.console.ConsoleInspector
import com.coppermux.cutools.core.console.ConsoleLifecyclePhase
import com.coppermux.cutools.core.console.ConsoleSeverity
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetInfo
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.WebSocketCdpTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 4A — Console Foundation real-Chrome validation.
 *
 * Proves against REAL Chrome that genuine console events reach the Console
 * subsystem through the Phase 1 foundation (no second WebSocket):
 *
 *  - console.log / console.info / console.debug / console.warn / console.error
 *  - a genuine runtime exception
 *  - browser-provided severity (DEBUG is distinguished from LOG), message
 *    content, timestamps, exception information, and stack/source information
 *    where Chrome exposes them
 *  - session association of every entry
 *
 * Skips when Chrome is not reachable at http://127.0.0.1:9224.
 */
class ConsoleChromeIntegrationTest {

    private lateinit var browserWsUrl: String

    @Before
    fun discoverChrome() {
        val env = ChromeEnvironment()
        if (!env.isReachable) {
            env.startHeadlessChrome()
        }
        browserWsUrl = env.discoverWebSocketUrl()
        assumeTrue("Real Chrome not reachable at http://127.0.0.1:9224 — skipping", browserWsUrl.isNotBlank())
    }

    private fun newManager(): ConnectionManager =
        ConnectionManager.create(WebSocketCdpTransport(), DispatcherConfig(defaultTimeoutMs = 5000))

    private fun await(latch: CountDownLatch, what: String) {
        assertTrue("Timed out waiting for: $what", latch.await(20, TimeUnit.SECONDS))
    }

    private fun connectAttachPage(cm: ConnectionManager): SessionInfo {
        val connected = CountDownLatch(1)
        cm.connect(url = browserWsUrl, onConnected = { connected.countDown() }, onError = { connected.countDown() })
        await(connected, "connection")

        val disc = CountDownLatch(1)
        val targets = AtomicReference<List<TargetInfo>>()
        cm.discoverTargets(callback = { t -> targets.set(t); disc.countDown() }, onError = { fail("discover: ${it.message}") })
        await(disc, "target discovery")
        val page = targets.get().first { it.isPage }
        cm.selectTarget(page.targetId)

        val attached = CountDownLatch(1)
        val sessionRef = AtomicReference<SessionInfo?>()
        val attachError = AtomicReference<Throwable?>()
        cm.attachToTarget(
            onAttached = { s -> sessionRef.set(s); attached.countDown() },
            onError = { e -> attachError.set(e); attached.countDown() },
        )
        await(attached, "target attach")
        assertNull("Attach error: ${attachError.get()}", attachError.get())
        return sessionRef.get()!!
    }

    /** Evaluates JS against the session via the test harness's dispatcher. */
    private fun evaluate(cm: ConnectionManager, session: SessionInfo, expression: String): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        cm.dispatcher.sendCommand(
            "Runtime.evaluate",
            mapOf("expression" to expression),
            sessionId = session.sessionId,
            callback = { result ->
                success = result is CommandResult.Success
                latch.countDown()
            },
        )
        await(latch, "Runtime.evaluate")
        return success
    }

    private fun waitForEntries(inspector: ConsoleInspector, timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(message, condition())
    }

    private fun waitForPhase(inspector: ConsoleInspector, phase: ConsoleLifecyclePhase) {
        val deadline = System.currentTimeMillis() + 15000
        while (inspector.state.phase != phase && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(phase, inspector.state.phase)
    }

    @Test
    fun phase4a_consoleEvents_againstRealChrome() {
        val cm = newManager()
        val session = connectAttachPage(cm)
        val dom = DomInspector(cm.dispatcher, cm.eventRouter)
        dom.bindSession(session)
        val engine = ExecutionEngine(cm.dispatcher)
        val console = ConsoleInspector(cm.dispatcher, cm.eventRouter, dom, engine)
        console.bindSession(session)
        waitForPhase(console, ConsoleLifecyclePhase.READY)
        assertEquals(ConsoleCapabilityStatus.SUPPORTED, console.state.capability)

        // Record the wall-clock window around the trigger so we can verify the
        // browser-provided timestamps of the entries this run triggers. NOTE:
        // Chrome replays its renderer console buffer when Runtime.enable is
        // issued, so earlier runs' messages may also arrive; a unique run token
        // makes content matching unambiguous and window checks confirm the
        // timestamps are browser values for THIS run's events.
        val run = "r" + System.nanoTime()
        val before = System.currentTimeMillis()

        // Trigger all five console levels plus a genuine runtime exception.
        val triggered = evaluate(
            cm, session,
            "(function(){" +
                "console.log('phase4a-log-$run', 1, 'two');" +
                "console.info('phase4a-info-$run');" +
                "console.debug('phase4a-debug-$run');" +
                "console.warn('phase4a-warn-$run');" +
                "console.error('phase4a-error-$run', new Error('expected-error'));" +
                "setTimeout(function(){ throw new Error('runtime-boom-$run'); }, 200);" +
                "})()",
        )
        assertTrue("console calls must evaluate", triggered)
        val after = System.currentTimeMillis()

        // All five severities arrive from Chrome.
        waitForEntries(console, 15000, "all required severities must arrive") {
            val sevs = console.state.entries.map { it.severity }.toSet()
            setOf(
                ConsoleSeverity.LOG, ConsoleSeverity.INFO, ConsoleSeverity.DEBUG,
                ConsoleSeverity.WARNING, ConsoleSeverity.ERROR,
            ).all { it in sevs }
        }

        // Our entries are matched by content (uniquely tagged for this run).
        fun entryWith(severity: ConsoleSeverity, text: String): ConsoleEntry =
            console.state.entries.first {
                it.severity == severity &&
                    it.args.firstOrNull()?.value == text &&
                    it.kind == ConsoleEntryKind.CONSOLE_MESSAGE
            }

        // LOG: content preserved as structured args.
        val logEntry = entryWith(ConsoleSeverity.LOG, "phase4a-log-$run")
        assertEquals(3, logEntry.args.size)
        assertEquals("phase4a-log-$run", logEntry.args[0].value)
        assertEquals("two", logEntry.args[2].value)
        assertEquals("phase4a-log-$run 1 two", logEntry.displayText)

        // INFO / DEBUG / WARNING content.
        assertNotNull(entryWith(ConsoleSeverity.INFO, "phase4a-info-$run"))
        assertNotNull(entryWith(ConsoleSeverity.DEBUG, "phase4a-debug-$run"))
        assertNotNull(entryWith(ConsoleSeverity.WARNING, "phase4a-warn-$run"))

        // DEBUG must be distinguishable from LOG.
        assertTrue(console.state.entries.any { it.severity == ConsoleSeverity.DEBUG && it.args.firstOrNull()?.value == "phase4a-debug-$run" })

        // ERROR carries the Error argument description Chrome provided.
        val errorEntry = entryWith(ConsoleSeverity.ERROR, "phase4a-error-$run")
        val errArg = errorEntry.args.getOrNull(1)
        assertEquals("error", errArg?.subtype)
        assertTrue("error description must contain message", errArg?.description?.contains("expected-error") == true)

        // A genuine runtime exception arrives as a distinct RUNTIME_EXCEPTION entry.
        waitForEntries(console, 15000, "runtime exception must arrive") {
            console.state.entries.any {
                it.kind == ConsoleEntryKind.RUNTIME_EXCEPTION &&
                    it.exceptionDescription?.contains("runtime-boom-$run") == true
            }
        }
        val exc = console.state.entries.first {
            it.kind == ConsoleEntryKind.RUNTIME_EXCEPTION && it.exceptionDescription?.contains("runtime-boom-$run") == true
        }
        assertEquals(ConsoleSeverity.ERROR, exc.severity)
        assertNotNull("exception id must be preserved", exc.exceptionId)
        assertTrue("exception description must come from Chrome", exc.exceptionDescription?.contains("runtime-boom") == true)

        // Browser-provided timestamps for the entries we triggered, within the
        // observed wall-clock window (a real browser timestamp, not a local one).
        listOf(logEntry, errorEntry, exc).forEach { entry ->
            assertNotNull("entry timestamp must be present", entry.timestamp)
            assertTrue(
                "timestamp ${entry.timestamp} must fall in the observed window [$before, $after]",
                entry.timestamp!! >= before - 10_000 && entry.timestamp!! <= after + 60_000,
            )
        }
        assertTrue(console.state.entries.any { it.timestamp != null })

        // Stack/source information where Chrome exposes it.
        val excTrace = exc.stackTrace
        assertNotNull("Chrome must expose an exception stack trace", excTrace)
        assertFalse("stack trace must carry call frames", excTrace!!.callFrames.isEmpty())
        assertTrue("call frames must expose a function/url/line", excTrace.callFrames.any { it.url != null && it.line != null })

        // console.error's Error argument yields a stack trace too.
        assertTrue(
            "console.error stack must be exposed",
            errorEntry.stackTrace?.callFrames?.isNotEmpty() == true,
        )

        // Every entry is associated with the bound session.
        console.state.entries.forEach { assertEquals(session.sessionId, it.sessionId) }

        // Entry order is deterministic and strictly increasing.
        val ids = console.state.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.zipWithNext().all { (a, b) -> a < b })

        // Lifecycle cleanup: detach clears everything.
        console.detach()
        assertEquals(ConsoleLifecyclePhase.INACTIVE, console.state.phase)
        assertEquals(0, console.state.entries.size)
        assertNull(console.session)

        cm.disconnect()
    }
}
