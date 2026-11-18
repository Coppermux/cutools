package com.coppermux.cutools.core.diagnostic

import android.content.Context
import android.content.pm.ApplicationInfo
import com.coppermux.cutools.core.browser.DashboardEventClassifier
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.console.ConsoleEntry
import com.coppermux.cutools.core.console.ConsoleEntryKind
import com.coppermux.cutools.core.console.ConsoleMessageType
import com.coppermux.cutools.core.console.ConsoleSeverity
import com.coppermux.cutools.ui.browser.computeRefreshDecision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/**
 * SEC-02 Automated Verification Tests:
 * 1. Development diagnostic tracing remains available in debug builds.
 * 2. Development-only disk tracing is disabled in release builds.
 * 3. Development-only logcat tracing is disabled in release builds.
 * 4. Normal JavaScript Console messages remain functional.
 * 5. Normal DevTools Dashboard events remain functional.
 * 6. Browser navigation is unaffected.
 * 7. Existing diagnostic lifecycle operations remain safe when tracing is disabled.
 */
class DiagnosticTracerTest {

    @Before
    fun setUp() {
        DiagnosticTracer.resetForTesting(forceEnabled = true)
    }

    @After
    fun tearDown() {
        DiagnosticTracer.resetForTesting(forceEnabled = null)
    }

    @Test
    fun test01_debugMode_diagnosticTracingAvailable() {
        DiagnosticTracer.resetForTesting(forceEnabled = true)
        assertTrue(DiagnosticTracer.isEnabled)

        DiagnosticTracer.trace(
            category = "DEBUG_CAT",
            event = "DEBUG_EVENT",
            "testKey" to "testValue456",
        )

        assertEquals(1, DiagnosticTracer.getLogCount())
        val export = DiagnosticTracer.generateExportText()
        assertTrue(export.contains("DEBUG_CAT"))
        assertTrue(export.contains("DEBUG_EVENT"))
        assertTrue(export.contains("testKey=testValue456"))
    }

    @Test
    fun test02_releaseMode_diagnosticTracingDisabled() {
        DiagnosticTracer.resetForTesting(forceEnabled = false)
        assertFalse(DiagnosticTracer.isEnabled)

        DiagnosticTracer.trace(
            category = "SENSITIVE_CAT",
            event = "SENSITIVE_URL_EVENT",
            "url" to "https://example.com/api?token=secret123",
        )

        assertEquals(0, DiagnosticTracer.getLogCount())
        val export = DiagnosticTracer.generateExportText()
        assertEquals("Diagnostic tracing is disabled in release builds.", export)
        assertFalse(export.contains("secret123"))
        assertFalse(export.contains("SENSITIVE_CAT"))
    }

    @Test
    fun test03_releaseMode_diskTracingDisabled() {
        DiagnosticTracer.resetForTesting(forceEnabled = false)
        assertNull("Log file must not be bound in release mode", DiagnosticTracer.getLogFileForTesting())

        DiagnosticTracer.trace("DISK_TEST", "EVENT_WILL_NOT_WRITE", "data" to "abc")
        assertEquals(0, DiagnosticTracer.getLogCount())
        assertNull(DiagnosticTracer.getLogFileForTesting())
    }

    @Test
    fun test04_init_respectsDebuggableFlag() {
        val mockContext = mock(Context::class.java)
        val appInfo = ApplicationInfo().apply {
            flags = 0 // Not debuggable (Release)
        }
        `when`(mockContext.applicationInfo).thenReturn(appInfo)
        `when`(mockContext.applicationContext).thenReturn(mockContext)

        DiagnosticTracer.resetForTesting(forceEnabled = null)
        DiagnosticTracer.init(mockContext)

        assertFalse("Tracer must be disabled when context is not debuggable in release", DiagnosticTracer.isEnabled)
        assertNull("Log file must be null when disabled", DiagnosticTracer.getLogFileForTesting())
    }

    @Test
    fun test05_lifecycleOperationsSafeWhenDisabled() {
        DiagnosticTracer.resetForTesting(forceEnabled = false)

        // All lifecycle operations must execute without throwing
        DiagnosticTracer.clear()
        assertEquals(0, DiagnosticTracer.getLogCount())

        DiagnosticTracer.trace("TEST", "EVENT")
        assertEquals(0, DiagnosticTracer.getLogCount())

        val export = DiagnosticTracer.generateExportText()
        assertEquals("Diagnostic tracing is disabled in release builds.", export)

        DiagnosticTracer.clear()
        assertEquals(0, DiagnosticTracer.getLogCount())
    }

    @Test
    fun test06_normalDevToolsDashboardEventsRemainFunctional() {
        DiagnosticTracer.resetForTesting(forceEnabled = false)

        val stream = DashboardEventStream()
        val event = DashboardEventClassifier.buildNetworkRequestEvent(
            url = "https://example.com/api/data",
            method = "GET",
            isForMainFrame = false,
            timestamp = 1000L,
        )

        val appended = stream.append(event)
        assertEquals(1L, appended.id)
        assertEquals(1, stream.state.eventCount)
        assertEquals("https://example.com/api/data", stream.state.events.first().url)
    }

    @Test
    fun test07_normalConsoleEntriesRemainFunctional() {
        DiagnosticTracer.resetForTesting(forceEnabled = false)

        val entry = ConsoleEntry(
            id = 1L,
            kind = ConsoleEntryKind.CONSOLE_MESSAGE,
            severity = ConsoleSeverity.INFO,
            type = ConsoleMessageType.LOG,
            text = "User console.log message",
            timestamp = 2000.0,
            sessionId = "test-session-1",
        )

        assertEquals("User console.log message", entry.text)
        assertEquals(ConsoleSeverity.INFO, entry.severity)
        assertEquals(ConsoleMessageType.LOG, entry.type)
        assertEquals(ConsoleEntryKind.CONSOLE_MESSAGE, entry.kind)
    }

    @Test
    fun test08_browserNavigationUnaffected() {
        DiagnosticTracer.resetForTesting(forceEnabled = false)

        val decision = computeRefreshDecision(
            isVisible = true,
            currentWebViewUrl = "https://example.com/home",
            committedUrl = "https://example.com/home",
            stateUrl = "https://example.com/home",
            isNetworkDropped = false,
        )

        assertTrue(decision.canReload)
        assertEquals("webView.reload()", decision.operation)
        assertEquals("DISPATCHED", decision.status)
        assertEquals("https://example.com/home", decision.targetUrl)
    }
}
