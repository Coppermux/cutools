package com.coppermux.cutools.core.runtime

import com.coppermux.cutools.core.error.ConnectionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for the JavaScript execution models: request
 * serialization, RemoteObject display, outcome classification, and execution
 * state.
 */
class EvaluationModelsTest {

    // ============ EvaluationRequest ============

    @Test
    fun request_toParams_includesSupportedOptions() {
        val params = EvaluationRequest("1 + 1", returnByValue = false, generatePreview = true).toParams()
        assertEquals("1 + 1", params["expression"])
        assertEquals(false, params["returnByValue"])
        assertEquals(true, params["generatePreview"])
    }

    @Test
    fun request_defaultsAreMinimalAndSupported() {
        val params = EvaluationRequest("42").toParams()
        assertEquals("42", params["expression"])
        assertEquals(false, params["returnByValue"])
        assertEquals(false, params["generatePreview"])
        assertEquals(false, params["awaitPromise"])
    }

    @Test
    fun request_toParams_serializesAwaitPromise() {
        val params = EvaluationRequest("Promise.resolve(42)", awaitPromise = true).toParams()
        assertEquals(true, params["awaitPromise"])
    }

    // ============ EvaluationRemoteObject.displayText ============

    @Test
    fun remoteObject_display_primitiveNumber() {
        val ro = EvaluationRemoteObject(type = "number", value = 2.0, description = "2")
        assertEquals("2", ro.displayText)
    }

    @Test
    fun remoteObject_display_decimalNumber() {
        val ro = EvaluationRemoteObject(type = "number", value = 1.5, description = "1.5")
        assertEquals("1.5", ro.displayText)
    }

    @Test
    fun remoteObject_display_string() {
        val ro = EvaluationRemoteObject(type = "string", value = "hello world")
        assertEquals("hello world", ro.displayText)
    }

    @Test
    fun remoteObject_display_boolean() {
        val ro = EvaluationRemoteObject(type = "boolean", value = true)
        assertEquals("true", ro.displayText)
    }

    @Test
    fun remoteObject_display_null() {
        val ro = EvaluationRemoteObject(type = "object", subtype = "null", value = null)
        assertEquals("null", ro.displayText)
    }

    @Test
    fun remoteObject_display_undefined() {
        val ro = EvaluationRemoteObject(type = "undefined")
        assertEquals("undefined", ro.displayText)
    }

    @Test
    fun remoteObject_display_unserializable() {
        val ro = EvaluationRemoteObject(type = "number", value = null, unserializableValue = "NaN", description = "NaN")
        assertEquals("NaN", ro.displayText)
    }

    @Test
    fun remoteObject_display_objectUsesDescription() {
        val ro = EvaluationRemoteObject(type = "object", className = "Object", description = "Object", objectId = "obj-1")
        assertEquals("Object", ro.displayText)
        assertEquals("obj-1", ro.objectId)
    }

    @Test
    fun remoteObject_display_arrayUsesDescription() {
        val ro = EvaluationRemoteObject(type = "object", subtype = "array", className = "Array", description = "Array(3)", objectId = "arr-1")
        assertEquals("Array(3)", ro.displayText)
        assertEquals("array", ro.subtype)
    }

    @Test
    fun remoteObject_display_fallsBackToType() {
        val ro = EvaluationRemoteObject(type = "symbol")
        assertEquals("(symbol)", ro.displayText)
    }

    // ============ EvaluationOutcome classification / display ============

    @Test
    fun outcome_success_classifiesAndDisplays() {
        val outcome = EvaluationOutcome(
            id = 1,
            request = EvaluationRequest("1+1"),
            result = EvaluationResultKind.Success(EvaluationRemoteObject(type = "number", value = 2.0), executionContextId = 1),
            sessionId = "s",
        )
        assertTrue(outcome.isSuccess)
        assertFalse(outcome.isException)
        assertFalse(outcome.isProtocolError)
        assertNull(outcome.error)
        assertEquals("2", outcome.displayText)
    }

    @Test
    fun outcome_exception_classifiesAndDisplays() {
        val details = EvaluationExceptionDetails(
            text = "Uncaught",
            exception = EvaluationRemoteObject(type = "object", subtype = "error", description = "Error: boom"),
        )
        val outcome = EvaluationOutcome(
            id = 2,
            request = EvaluationRequest("x"),
            result = EvaluationResultKind.Exception(details),
            sessionId = "s",
        )
        assertTrue(outcome.isException)
        assertEquals("Uncaught Error: boom", outcome.displayText)
    }

    @Test
    fun outcome_protocolError_classifiesAndDisplays() {
        val err = ConnectionException("Session not found")
        val outcome = EvaluationOutcome(
            id = 3,
            request = EvaluationRequest("1"),
            result = EvaluationResultKind.ProtocolError(err),
            sessionId = "s",
        )
        assertTrue(outcome.isProtocolError)
        assertEquals(err, outcome.error)
        assertEquals("Evaluation failed: Session not found", outcome.displayText)
    }

    @Test
    fun exceptionDetails_display_usesTextAndDescription() {
        val details = EvaluationExceptionDetails(
            text = "Uncaught",
            exception = EvaluationRemoteObject(type = "object", subtype = "error", description = "ReferenceError: x is not defined\n    at foo (a.js:1:2)"),
        )
        assertEquals("Uncaught ReferenceError: x is not defined", details.displayText)
    }

    @Test
    fun exceptionDetails_display_withoutDescription_usesText() {
        assertEquals("Uncaught", EvaluationExceptionDetails(text = "Uncaught").displayText)
    }

    // ============ ExecutionState ============

    @Test
    fun executionState_defaultsInactive() {
        val state = ExecutionState.empty()
        assertEquals(ExecutionPhase.INACTIVE, state.phase)
        assertFalse(state.isReady)
        assertNull(state.sessionId)
        assertEquals(0, state.pendingEvaluations)
    }

    @Test
    fun executionState_readyFlags() {
        val state = ExecutionState(sessionId = "s", targetId = "t", phase = ExecutionPhase.READY)
        assertTrue(state.isReady)
        assertEquals("s", state.sessionId)
        assertEquals("t", state.targetId)
    }
}
