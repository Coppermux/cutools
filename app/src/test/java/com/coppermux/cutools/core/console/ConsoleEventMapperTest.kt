package com.coppermux.cutools.core.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for [ConsoleEventMapper] — the pure mapping of
 * Chrome CDP console events to structured [ConsoleEntry] models. Verifies the
 * levels required by Phase 4A (log/info/debug/warn/error), runtime exceptions,
 * timestamp handling, stack-trace mapping, and malformed-payload tolerance.
 */
class ConsoleEventMapperTest {

    private val session = "session-1"

    // ============ Runtime.consoleAPICalled ============

    private fun apiParams(type: String, ts: Double, args: List<Map<String, Any?>>): Map<String, Any?> =
        mapOf("type" to type, "timestamp" to ts, "executionContextId" to 3, "args" to args)

    @Test
    fun apiCalled_log_mapsSeverityTypeTimestampAndArgs() {
        val entry = ConsoleEventMapper.mapConsoleApiCalled(
            apiParams("log", 1000.0, listOf(mapOf("type" to "string", "value" to "hello"))),
            session, "t1",
        )!!
        assertEquals(ConsoleEntryKind.CONSOLE_MESSAGE, entry.kind)
        assertEquals(ConsoleSeverity.LOG, entry.severity)
        assertEquals(ConsoleMessageType.LOG, entry.type)
        assertEquals(1000.0, entry.timestamp!!, 0.0001)
        assertEquals(3, entry.executionContextId)
        assertEquals(1, entry.args.size)
        assertEquals("hello", entry.args[0].value)
        assertEquals(session, entry.sessionId)
        assertEquals("t1", entry.targetId)
    }

    @Test
    fun apiCalled_requiredLevels_mapToDistinctSeverities() {
        for (type in listOf("log", "debug", "info", "warning", "error")) {
            val entry = ConsoleEventMapper.mapConsoleApiCalled(
                apiParams(type, 1000.0, emptyList()), session, null,
            )!!
            val expected = when (type) {
                "log" -> ConsoleSeverity.LOG
                "debug" -> ConsoleSeverity.DEBUG
                "info" -> ConsoleSeverity.INFO
                "warning" -> ConsoleSeverity.WARNING
                else -> ConsoleSeverity.ERROR
            }
            assertEquals("severity for $type", expected, entry.severity)
        }
    }

    @Test
    fun apiCalled_didNotSupplyText_structuredArgsPreserved() {
        val entry = ConsoleEventMapper.mapConsoleApiCalled(
            apiParams(
                "log", 1000.0,
                listOf(
                    mapOf("type" to "string", "value" to "x"),
                    mapOf("type" to "object", "subtype" to "array", "className" to "Array", "description" to "Array(2)", "objectId" to "obj1"),
                ),
            ), session, null,
        )!!
        assertNull(entry.text)
        assertEquals(2, entry.args.size)
        assertEquals("array", entry.args[1].subtype)
        assertEquals("Array(2)", entry.args[1].description)
        assertEquals("obj1", entry.args[1].objectId)
    }

    @Test
    fun apiCalled_stackTrace_mapsCallFramesAndLocation() {
        val params = apiParams("log", 1000.0, emptyList()) +
            mapOf(
                "stackTrace" to mapOf(
                    "callFrames" to listOf(
                        mapOf("functionName" to "outer", "scriptId" to "5", "url" to "app.js", "lineNumber" to 10, "columnNumber" to 2),
                        mapOf("functionName" to "", "scriptId" to "5", "url" to "app.js", "lineNumber" to 3, "columnNumber" to 1),
                    ),
                ),
            )
        val entry = ConsoleEventMapper.mapConsoleApiCalled(params, session, null)!!
        val trace = entry.stackTrace!!
        assertEquals(2, trace.callFrames.size)
        assertEquals("outer", trace.callFrames[0].functionName)
        assertEquals("app.js", trace.callFrames[0].url)
        assertEquals(10, trace.callFrames[0].line)
        assertEquals(2, trace.callFrames[0].column)
        // The location is the top call frame the browser reported.
        assertEquals("app.js", entry.location?.url)
        assertEquals(10, entry.location?.line)
    }

    @Test
    fun apiCalled_unknownType_preservedNotCrashed() {
        val entry = ConsoleEventMapper.mapConsoleApiCalled(
            apiParams("table", 1000.0, emptyList()), session, null,
        )!!
        assertEquals(ConsoleMessageType.TABLE, entry.type)
        assertEquals(ConsoleSeverity.LOG, entry.severity)
    }

    @Test
    fun apiCalled_malformedParams_returnsNull() {
        assertNull(ConsoleEventMapper.mapConsoleApiCalled(null, session, null))
        assertNull(ConsoleEventMapper.mapConsoleApiCalled("not-a-map", session, null))
    }

    @Test
    fun apiCalled_emptyParams_preservedAsUnknownTypeEntry() {
        val entry = ConsoleEventMapper.mapConsoleApiCalled(emptyMap<String, Any?>(), session, null)!!
        assertEquals(ConsoleMessageType.UNKNOWN, entry.type)
        assertNull(entry.timestamp)
        assertTrue(entry.args.isEmpty())
    }

    @Test
    fun apiCalled_missingTimestamp_returnsNullTimestamp() {
        val entry = ConsoleEventMapper.mapConsoleApiCalled(
            mapOf("type" to "log", "args" to emptyList<Any>()), session, null,
        )!!
        assertNull(entry.timestamp)
    }

    // ============ Runtime.exceptionThrown ============

    @Test
    fun exceptionThrown_mapsRuntimeExceptionEntry() {
        val exception = mapOf(
            "type" to "object",
            "subtype" to "error",
            "className" to "Error",
            "description" to "Error: boom\n    at foo (app.js:1:2)",
        )
        val params = mapOf(
            "timestamp" to 2000.0,
            "exceptionDetails" to mapOf(
                "exceptionId" to 7,
                "text" to "Uncaught",
                "lineNumber" to 4,
                "columnNumber" to 22,
                "scriptId" to "9",
                "url" to null,
                "executionContextId" to 1,
                "exception" to exception,
                "stackTrace" to mapOf(
                    "callFrames" to listOf(
                        mapOf("functionName" to "f", "scriptId" to "9", "url" to "<anonymous>", "lineNumber" to 4, "columnNumber" to 22),
                    ),
                ),
            ),
        )
        val entry = ConsoleEventMapper.mapExceptionThrown(params, session, "t1")!!
        assertEquals(ConsoleEntryKind.RUNTIME_EXCEPTION, entry.kind)
        assertEquals(ConsoleSeverity.ERROR, entry.severity)
        assertEquals("Uncaught", entry.text)
        assertEquals(2000.0, entry.timestamp!!, 0.0001)
        assertEquals(7, entry.exceptionId)
        assertEquals("Error: boom\n    at foo (app.js:1:2)", entry.exceptionDescription)
        assertEquals(4, entry.location?.line)
        assertEquals(22, entry.location?.column)
        assertNotNull(entry.stackTrace)
        assertEquals("f", entry.stackTrace!!.callFrames[0].functionName)
    }

    @Test
    fun exceptionThrown_noStack_leavesStackAbsent() {
        val params = mapOf(
            "timestamp" to 2000.0,
            "exceptionDetails" to mapOf(
                "exceptionId" to 1,
                "text" to "Uncaught",
                "lineNumber" to 0,
                "columnNumber" to 0,
            ),
        )
        val entry = ConsoleEventMapper.mapExceptionThrown(params, session, null)!!
        assertNull(entry.stackTrace)
        assertNull(entry.exceptionDescription)
    }

    @Test
    fun exceptionThrown_missingDetails_returnsNull() {
        assertNull(ConsoleEventMapper.mapExceptionThrown(null, session, null))
        assertNull(ConsoleEventMapper.mapExceptionThrown(mapOf("timestamp" to 1.0), session, null))
    }

    // ============ Console.messageAdded ============

    private fun messageAddedParams(
        source: String,
        level: String,
        text: String,
        timestamp: Number? = null,
    ): Map<String, Any?> {
        val message = mutableMapOf<String, Any?>("source" to source, "level" to level, "text" to text)
        if (timestamp != null) message["timestamp"] = timestamp
        return mapOf("message" to message)
    }

    @Test
    fun messageAdded_mapsBrowserTextSourceAndLevel() {
        val entry = ConsoleEventMapper.mapMessageAdded(
            messageAddedParams("network", "error", "Failed to load resource", null),
            session, null,
        )!!
        assertEquals(ConsoleSeverity.ERROR, entry.severity)
        assertEquals("Failed to load resource", entry.text)
        assertEquals("network", entry.source)
        assertEquals(session, entry.sessionId)
    }

    @Test
    fun messageAdded_missingTimestampStaysNull_notReplaced() {
        val entry = ConsoleEventMapper.mapMessageAdded(
            messageAddedParams("console-api", "log", "L", null), session, null,
        )!!
        assertNull(entry.timestamp)
    }

    @Test
    fun messageAdded_withTimestamp_preservesIt() {
        val entry = ConsoleEventMapper.mapMessageAdded(
            messageAddedParams("security", "warning", "blocked", 1234.5), session, null,
        )!!
        assertEquals(1234.5, entry.timestamp!!, 0.0001)
    }

    @Test
    fun messageAdded_malformed_returnsNull() {
        assertNull(ConsoleEventMapper.mapMessageAdded(null, session, null))
        assertNull(ConsoleEventMapper.mapMessageAdded(mapOf("message" to "not-a-map"), session, null))
        assertNull(ConsoleEventMapper.mapMessageAdded(emptyMap<String, Any?>(), session, null))
    }

    // ============ stack trace ============

    @Test
    fun stackTrace_asyncParentIsPreserved() {
        val trace = ConsoleEventMapper.parseStackTrace(
            mapOf(
                "description" to "async",
                "callFrames" to listOf(
                    mapOf("functionName" to "a", "url" to "u.js", "lineNumber" to 1, "columnNumber" to 2),
                ),
                "parent" to mapOf(
                    "callFrames" to listOf(
                        mapOf("functionName" to "b", "url" to "v.js", "lineNumber" to 4, "columnNumber" to 5),
                    ),
                ),
            ),
        )!!
        assertEquals("async", trace.description)
        assertEquals(1, trace.callFrames.size)
        assertEquals("b", trace.parent?.callFrames?.firstOrNull()?.functionName)
    }

    @Test
    fun stackTrace_malformedCallFrames_ignored() {
        val trace = ConsoleEventMapper.parseStackTrace(mapOf("callFrames" to listOf("not-a-map", null)))!!
        assertTrue(trace.callFrames.isEmpty())
    }

    @Test
    fun stackTrace_unknownValue_returnsNull() {
        assertNull(ConsoleEventMapper.parseStackTrace(null))
        assertNull(ConsoleEventMapper.parseStackTrace("nope"))
    }

    @Test
    fun args_preservesAllBrowserFields() {
        val args = ConsoleEventMapper.parseArgs(
            listOf(
                mapOf("type" to "number", "value" to 1),
                mapOf("type" to "object", "subtype" to "null"),
            ),
        )
        assertEquals(2, args.size)
        assertEquals(1, args[0].value)
        assertEquals("null", args[1].subtype)
        assertNull(args[1].objectId)
        assertFalse(ConsoleEventMapper.parseArgs("not-a-list").isNotEmpty())
    }
}