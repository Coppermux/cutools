package com.coppermux.cutools.core.model

import com.coppermux.cutools.core.error.ParsingException
import org.junit.Assert.*
import org.junit.Test

class CdpMessageClassifierTest {

    private val classifier = CdpMessageClassifier()

    // ============== Command serialization ==============

    @Test
    fun command_serialization_producesValidJson() {
        val cmd = Command(id = 1, method = "Browser.getVersion")
        val json = Protocol.commandAdapter.toJson(cmd)
        assertNotNull(json)
        assertTrue(json.contains("\"id\":1"))
        assertTrue(json.contains("\"method\":\"Browser.getVersion\""))
    }

    @Test
    fun command_withParams_serializesCorrectly() {
        val cmd = Command(id = 2, method = "Runtime.evaluate", params = mapOf("expression" to "1+1"))
        val json = Protocol.commandAdapter.toJson(cmd)
        assertTrue(json.contains("\"expression\":\"1+1\""))
    }

    @Test
    fun command_withSessionId_includesSessionId() {
        val cmd = Command(id = 3, method = "Runtime.evaluate", sessionId = "session-1")
        val json = Protocol.commandAdapter.toJson(cmd)
        assertTrue(json.contains("\"sessionId\":\"session-1\""))
    }

    // ============== Response parsing ==============

    @Test
    fun classify_responseMessage_hasId() {
        val json = """{"id":1,"result":{"browser":"Chrome 120","protocolVersion":"1.3"}}"""
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.ResponseMessage)
        val response = (result as MessageClassification.ResponseMessage).response
        assertEquals(1L, response.id)
        assertTrue(response.isSuccess)
    }

    @Test
    fun classify_responseWithResult_parsesResult() {
        val json = """{"id":42,"result":{"sessionId":"ABC123"}}"""
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.ResponseMessage)
        val response = (result as MessageClassification.ResponseMessage).response
        assertEquals(42L, response.id)
        val resultMap = response.result as Map<*, *>
        assertEquals("ABC123", resultMap["sessionId"])
    }

    @Test
    fun classify_protocolErrorResponse_parsesError() {
        val json = """{"id":1,"error":{"code":-32602,"message":"Invalid parameters"}}"""
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.ResponseMessage)
        val response = (result as MessageClassification.ResponseMessage).response
        assertFalse(response.isSuccess)
        assertNotNull(response.error)
        assertEquals(-32602, response.error!!.code)
        assertEquals("Invalid parameters", response.error!!.message)
    }

    // ============== Event parsing ==============

    @Test
    fun classify_eventMessage_noId() {
        val json = """{"method":"Target.targetCreated","params":{"targetInfo":{"targetId":"abc","type":"page"}}}"""
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.EventMessage)
        val event = (result as MessageClassification.EventMessage).event
        assertEquals("Target.targetCreated", event.method)
    }

    @Test
    fun classify_event_withSessionId() {
        val json = """{"method":"Runtime.consoleAPICalled","params":{},"sessionId":"S1"}"""
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.EventMessage)
        val event = (result as MessageClassification.EventMessage).event
        assertEquals("Runtime.consoleAPICalled", event.method)
        assertEquals("S1", event.sessionId)
    }

    @Test
    fun classify_event_parsesParams() {
        val json = """{"method":"Target.targetCreated","params":{"targetInfo":{"targetId":"t1","type":"page","title":"Test","url":"http://example.com"}}}"""
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.EventMessage)
        val event = (result as MessageClassification.EventMessage).event
        val params = event.params as Map<*, *>
        val targetInfo = params["targetInfo"] as Map<*, *>
        assertEquals("t1", targetInfo["targetId"])
        assertEquals("page", targetInfo["type"])
        assertEquals("Test", targetInfo["title"])
        assertEquals("http://example.com", targetInfo["url"])
    }

    // ============== Protocol error parsing ==============

    @Test
    fun classify_protocolError_message() {
        val json = """{"id":1,"error":{"code":-32000,"message":"Server error","data":"details"}}"""
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.ResponseMessage)
        val response = (result as MessageClassification.ResponseMessage).response
        assertEquals(-32000, response.error!!.code)
        assertEquals("Server error", response.error!!.message)
        assertEquals("details", response.error!!.data)
    }

    // ============== Unknown fields ==============

    @Test
    fun parseMessage_unknownFields_notLost() {
        // A message with unknown fields should still parse
        val json = """{"id":1,"result":{"browser":"Chrome"},"unknownField":"value","anotherUnknown":42}"""
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.ResponseMessage)
        val response = (result as MessageClassification.ResponseMessage).response
        assertEquals(1L, response.id)
        // The result is parsed as a Map; unknown fields in result are preserved
        val resultMap = response.result as Map<*, *>
        assertEquals("Chrome", resultMap["browser"])
    }

    // ============== Malformed messages ==============

    @Test
    fun classify_malformedJson_returnsUnclassified() {
        val json = "not valid json {{{"
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.Unclassified)
    }

    @Test
    fun classify_emptyObject_returnsUnclassified() {
        val json = "{}"
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.Unclassified)
    }

    @Test
    fun classify_messageWithOnlyId_isResponse() {
        val json = """{"id":5,"result":{}}"""
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.ResponseMessage)
    }

    @Test
    fun classify_messageWithOnlyMethod_isEvent() {
        val json = """{"method":"Runtime.consoleAPICalled","params":{}}"""
        val result = classifier.classify(json)
        assertTrue(result is MessageClassification.EventMessage)
    }
}
