package com.coppermux.cutools.core.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for the Console model layer: severity/type mapping,
 * display-text derivation, and entry semantics.
 */
class ConsoleModelsTest {

    // ============ severity mapping (Runtime.consoleAPICalled type) ============

    @Test
    fun severity_fromApiType_mapsRequiredLevels() {
        assertEquals(ConsoleSeverity.LOG, ConsoleSeverity.fromApiType(ConsoleMessageType.LOG))
        assertEquals(ConsoleSeverity.INFO, ConsoleSeverity.fromApiType(ConsoleMessageType.INFO))
        assertEquals(ConsoleSeverity.DEBUG, ConsoleSeverity.fromApiType(ConsoleMessageType.DEBUG))
        assertEquals(ConsoleSeverity.WARNING, ConsoleSeverity.fromApiType(ConsoleMessageType.WARNING))
        assertEquals(ConsoleSeverity.ERROR, ConsoleSeverity.fromApiType(ConsoleMessageType.ERROR))
    }

    @Test
    fun severity_fromApiType_assertIsError() {
        assertEquals(ConsoleSeverity.ERROR, ConsoleSeverity.fromApiType(ConsoleMessageType.ASSERT))
    }

    @Test
    fun severity_fromApiType_unknownIsLogButRawTypePreserved() {
        assertEquals(ConsoleSeverity.LOG, ConsoleSeverity.fromApiType(ConsoleMessageType.UNKNOWN))
    }

    // ============ severity mapping (Console.messageAdded level) ============

    @Test
    fun severity_fromConsoleLevel_mapsLevels() {
        assertEquals(ConsoleSeverity.LOG, ConsoleSeverity.fromConsoleLevel("log"))
        assertEquals(ConsoleSeverity.INFO, ConsoleSeverity.fromConsoleLevel("info"))
        assertEquals(ConsoleSeverity.DEBUG, ConsoleSeverity.fromConsoleLevel("debug"))
        assertEquals(ConsoleSeverity.DEBUG, ConsoleSeverity.fromConsoleLevel("verbose"))
        assertEquals(ConsoleSeverity.WARNING, ConsoleSeverity.fromConsoleLevel("warning"))
        assertEquals(ConsoleSeverity.ERROR, ConsoleSeverity.fromConsoleLevel("error"))
    }

    @Test
    fun severity_fromConsoleLevel_nullMapsToLog() {
        assertEquals(ConsoleSeverity.LOG, ConsoleSeverity.fromConsoleLevel(null))
    }

    // ============ type mapping ============

    @Test
    fun type_fromCdp_preservesKnownAndUnknown() {
        assertEquals(ConsoleMessageType.LOG, ConsoleMessageType.fromCdp("log"))
        assertEquals(ConsoleMessageType.WARNING, ConsoleMessageType.fromCdp("warning"))
        assertEquals(ConsoleMessageType.UNKNOWN, ConsoleMessageType.fromCdp(null))
        assertEquals(ConsoleMessageType.UNKNOWN, ConsoleMessageType.fromCdp("something-new"))
    }

    // ============ display text ============

    @Test
    fun displayText_prefersBrowserText() {
        val entry = ConsoleEntry(
            id = 1,
            kind = ConsoleEntryKind.CONSOLE_MESSAGE,
            severity = ConsoleSeverity.LOG,
            type = ConsoleMessageType.LOG,
            text = "browser text",
            args = listOf(ConsoleArg(type = "string", value = "arg")),
            sessionId = "s",
        )
        assertEquals("browser text", entry.displayText)
    }

    @Test
    fun displayText_derivesFromArgsWhenChromeSuppliedNoText() {
        val entry = ConsoleEntry(
            id = 1,
            kind = ConsoleEntryKind.CONSOLE_MESSAGE,
            severity = ConsoleSeverity.LOG,
            type = ConsoleMessageType.LOG,
            text = null,
            args = listOf(
                ConsoleArg(type = "string", value = "hello"),
                ConsoleArg(type = "number", value = 42.0),
                ConsoleArg(type = "boolean", value = true),
                ConsoleArg(type = "object", description = "Array(2)", className = "Array"),
            ),
            sessionId = "s",
        )
        assertEquals("hello 42 true Array(2)", entry.displayText)
        // Structured args must remain available — never flattened away.
        assertEquals(4, entry.args.size)
    }

    @Test
    fun displayText_runtimeException_combinesChromeTextAndDescription() {
        val entry = ConsoleEntry(
            id = 1,
            kind = ConsoleEntryKind.RUNTIME_EXCEPTION,
            severity = ConsoleSeverity.ERROR,
            type = ConsoleMessageType.ERROR,
            text = "Uncaught",
            exceptionDescription = "Error: boom\n    at foo (x.js:1:2)",
            sessionId = "s",
        )
        assertEquals("Uncaught Error: boom", entry.displayText)
        assertTrue(entry.isRuntimeException)
    }

    @Test
    fun displayText_runtimeException_withoutDescription_usesText() {
        val entry = ConsoleEntry(
            id = 1,
            kind = ConsoleEntryKind.RUNTIME_EXCEPTION,
            severity = ConsoleSeverity.ERROR,
            type = ConsoleMessageType.ERROR,
            text = "Uncaught",
            exceptionDescription = null,
            sessionId = "s",
        )
        assertEquals("Uncaught", entry.displayText)
    }

    // ============ entry semantics ============

    @Test
    fun runtimeException_isFlagged() {
        val entry = ConsoleEntry(
            id = 1,
            kind = ConsoleEntryKind.RUNTIME_EXCEPTION,
            severity = ConsoleSeverity.ERROR,
            type = ConsoleMessageType.ERROR,
            sessionId = "s",
        )
        assertTrue(entry.isRuntimeException)
        assertFalse(ConsoleEntry(
            id = 2,
            kind = ConsoleEntryKind.CONSOLE_MESSAGE,
            severity = ConsoleSeverity.LOG,
            type = ConsoleMessageType.LOG,
            sessionId = "s",
        ).isRuntimeException)
    }

    @Test
    fun entry_timestampPreservedAsBrowserValue() {
        val entry = ConsoleEntry(
            id = 1,
            kind = ConsoleEntryKind.CONSOLE_MESSAGE,
            severity = ConsoleSeverity.LOG,
            type = ConsoleMessageType.LOG,
            timestamp = 1787035116304.446,
            sessionId = "s",
        )
        assertEquals(1787035116304.446, entry.timestamp!!, 0.0001)
    }

    @Test
    fun entry_missingTimestampStaysAbsent() {
        val entry = ConsoleEntry(
            id = 1,
            kind = ConsoleEntryKind.CONSOLE_MESSAGE,
            severity = ConsoleSeverity.LOG,
            type = ConsoleMessageType.LOG,
            sessionId = "s",
        )
        assertNull(entry.timestamp)
    }

    @Test
    fun entry_retainsSessionAndTargetAssociation() {
        val entry = ConsoleEntry(
            id = 1,
            kind = ConsoleEntryKind.CONSOLE_MESSAGE,
            severity = ConsoleSeverity.LOG,
            type = ConsoleMessageType.LOG,
            sessionId = "session-1",
            targetId = "target-1",
        )
        assertEquals("session-1", entry.sessionId)
        assertEquals("target-1", entry.targetId)
    }

    @Test
    fun entry_commandKind_displayTextReturnsExpression() {
        val entry = ConsoleEntry(
            id = 1,
            kind = ConsoleEntryKind.COMMAND,
            severity = ConsoleSeverity.LOG,
            type = ConsoleMessageType.UNKNOWN,
            text = "console.log(42)",
            sessionId = "s",
        )
        assertTrue(entry.isCommand)
        assertFalse(entry.isEvaluationResult)
        assertFalse(entry.isRuntimeException)
        assertEquals("console.log(42)", entry.displayText)
        assertEquals("command", ConsoleEntryKind.COMMAND.displayLabel)
    }
}
