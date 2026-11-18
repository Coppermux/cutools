package com.coppermux.cutools.core.runtime

import com.coppermux.cutools.core.model.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for [EvaluationResultMapper] — the pure mapping of
 * CDP `Runtime.evaluate` responses to structured outcomes. Verifies success,
 * evaluation-exception, and protocol-failure classification, RemoteObject and
 * ExceptionDetails parsing, and malformed-payload tolerance.
 */
class EvaluationResultMapperTest {

    private val request = EvaluationRequest("1 + 1")
    private val session = "session-1"

    private fun responseWithResult(result: Map<String, Any?>): Response =
        Response(id = 1, result = result, sessionId = session)

    // ============ Successful evaluations ============

    @Test
    fun mapSuccess_number() {
        val outcome = EvaluationResultMapper.mapSuccess(
            responseWithResult(mapOf("result" to mapOf("type" to "number", "value" to 2, "description" to "2"), "executionContextId" to 1)),
            request, session, "t1",
        )
        assertTrue(outcome.isSuccess)
        val value = (outcome.result as EvaluationResultKind.Success).value
        assertEquals("number", value.type)
        assertEquals(2, value.value)
        assertEquals("2", outcome.displayText)
        assertEquals(session, outcome.sessionId)
        assertEquals("t1", outcome.targetId)
    }

    @Test
    fun mapSuccess_string() {
        val outcome = EvaluationResultMapper.mapSuccess(
            responseWithResult(mapOf("result" to mapOf("type" to "string", "value" to "hello"))),
            request, session, null,
        )
        val value = (outcome.result as EvaluationResultKind.Success).value
        assertEquals("hello", value.value)
    }

    @Test
    fun mapSuccess_boolean() {
        val outcome = EvaluationResultMapper.mapSuccess(
            responseWithResult(mapOf("result" to mapOf("type" to "boolean", "value" to true))),
            request, session, null,
        )
        assertTrue((outcome.result as EvaluationResultKind.Success).value.value == true)
    }

    @Test
    fun mapSuccess_null() {
        val outcome = EvaluationResultMapper.mapSuccess(
            responseWithResult(mapOf("result" to mapOf("type" to "object", "subtype" to "null"))),
            request, session, null,
        )
        val value = (outcome.result as EvaluationResultKind.Success).value
        assertEquals("null", value.subtype)
        assertNull(value.value)
    }

    @Test
    fun mapSuccess_undefined() {
        val outcome = EvaluationResultMapper.mapSuccess(
            responseWithResult(mapOf("result" to mapOf("type" to "undefined"))),
            request, session, null,
        )
        assertEquals("undefined", (outcome.result as EvaluationResultKind.Success).value.type)
    }

    @Test
    fun mapSuccess_object_preservesRemoteObject() {
        val outcome = EvaluationResultMapper.mapSuccess(
            responseWithResult(mapOf(
                "result" to mapOf(
                    "type" to "object",
                    "subtype" to "array",
                    "className" to "Array",
                    "description" to "Array(3)",
                    "objectId" to "arr-1",
                ),
            )),
            request, session, null,
        )
        val value = (outcome.result as EvaluationResultKind.Success).value
        assertEquals("Array(3)", value.description)
        assertEquals("array", value.subtype)
        assertEquals("Array", value.className)
        assertEquals("arr-1", value.objectId)
    }

    @Test
    fun mapSuccess_unserializableValue_preserved() {
        val outcome = EvaluationResultMapper.mapSuccess(
            responseWithResult(mapOf("result" to mapOf("type" to "number", "unserializableValue" to "NaN"))),
            request, session, null,
        )
        assertEquals("NaN", (outcome.result as EvaluationResultKind.Success).value.unserializableValue)
    }

    @Test
    fun mapSuccess_previewAndCustomPreview_preserved() {
        val outcome = EvaluationResultMapper.mapSuccess(
            responseWithResult(mapOf(
                "result" to mapOf(
                    "type" to "object",
                    "description" to "Object",
                    "objectId" to "o-1",
                    "preview" to mapOf("type" to "object", "properties" to listOf<Any>()),
                    "customPreview" to mapOf("header" to "Custom"),
                ),
            )),
            request, session, null,
        )
        val value = (outcome.result as EvaluationResultKind.Success).value
        assertNotNull(value.preview)
        assertNotNull(value.customPreview)
    }

    // ============ Evaluation exceptions ============

    @Test
    fun mapSuccess_exceptionDetails_mapsException() {
        val outcome = EvaluationResultMapper.mapSuccess(
            responseWithResult(mapOf(
                "result" to mapOf("type" to "object", "subtype" to "error", "description" to "Error: boom"),
                "exceptionDetails" to mapOf(
                    "exceptionId" to 1,
                    "text" to "Uncaught",
                    "lineNumber" to 0,
                    "columnNumber" to 7,
                    "url" to null,
                    "exception" to mapOf("type" to "object", "subtype" to "error", "description" to "Error: boom"),
                    "stackTrace" to mapOf(
                        "callFrames" to listOf(
                            mapOf("functionName" to "<anonymous>", "scriptId" to "1", "url" to "", "lineNumber" to 0, "columnNumber" to 14),
                        ),
                    ),
                ),
            )),
            request, session, "t1",
        )
        assertTrue(outcome.isException)
        val details = (outcome.result as EvaluationResultKind.Exception).details
        assertEquals("Uncaught", details.text)
        assertEquals(0, details.line)
        assertEquals(7, details.column)
        assertEquals(1, details.exceptionId)
        assertEquals("Error: boom", details.exception?.description)
        assertNotNull(details.stackTrace)
        assertEquals(1, details.stackTrace!!.callFrames.size)
        assertEquals("t1", outcome.targetId)
    }

    @Test
    fun mapSuccess_syntaxError_emptyStackTrace_isException() {
        val outcome = EvaluationResultMapper.mapSuccess(
            responseWithResult(mapOf(
                "result" to mapOf("type" to "object", "subtype" to "error"),
                "exceptionDetails" to mapOf(
                    "exceptionId" to 2,
                    "text" to "Uncaught",
                    "exception" to mapOf("type" to "object", "subtype" to "error", "description" to "SyntaxError: Unexpected end of input"),
                    "stackTrace" to mapOf("callFrames" to emptyList<Any>()),
                ),
            )),
            request, session, null,
        )
        assertTrue(outcome.isException)
        val details = (outcome.result as EvaluationResultKind.Exception).details
        assertEquals(0, details.stackTrace?.callFrames?.size)
    }

    // ============ Protocol / malformed ============

    @Test
    fun mapSuccess_noResultPayload_isProtocolError() {
        val outcome = EvaluationResultMapper.mapSuccess(
            Response(id = 1, result = null, sessionId = session),
            request, session, null,
        )
        assertTrue(outcome.isProtocolError)
    }

    @Test
    fun mapSuccess_resultNotObject_isProtocolError() {
        val outcome = EvaluationResultMapper.mapSuccess(
            Response(id = 1, result = "not-an-object", sessionId = session),
            request, session, null,
        )
        assertTrue(outcome.isProtocolError)
    }

    @Test
    fun mapSuccess_malformedExceptionDetails_isProtocolError() {
        val outcome = EvaluationResultMapper.mapSuccess(
            responseWithResult(mapOf(
                "result" to mapOf("type" to "undefined"),
                "exceptionDetails" to "malformed",
            )),
            request, session, null,
        )
        assertTrue(outcome.isProtocolError)
    }

    // ============ RemoteObject / ExceptionDetails parsing ============

    @Test
    fun parseRemoteObject_unknownValue_emptyObject() {
        assertEquals(EvaluationRemoteObject(), EvaluationResultMapper.parseRemoteObject(null))
        assertEquals(EvaluationRemoteObject(), EvaluationResultMapper.parseRemoteObject("nope"))
    }

    @Test
    fun parseStackTrace_parentAndFrames() {
        val trace = EvaluationResultMapper.parseStackTrace(
            mapOf(
                "callFrames" to listOf(
                    mapOf("functionName" to "a", "url" to "u.js", "scriptId" to "1", "lineNumber" to 1, "columnNumber" to 2),
                ),
                "parent" to mapOf(
                    "callFrames" to listOf(
                        mapOf("functionName" to "b", "url" to "v.js", "lineNumber" to 4, "columnNumber" to 5),
                    ),
                ),
            ),
        )!!
        assertEquals(1, trace.callFrames.size)
        assertEquals("b", trace.parent?.callFrames?.firstOrNull()?.functionName)
        assertNull(EvaluationResultMapper.parseStackTrace(null))
    }
}
