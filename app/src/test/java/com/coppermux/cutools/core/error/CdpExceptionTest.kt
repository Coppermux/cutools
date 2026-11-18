package com.coppermux.cutools.core.error

import org.junit.Assert.*
import org.junit.Test

class CdpExceptionTest {

    @Test
    fun transportException_hasCorrectCategory() {
        val e = CdpException.transport("Transport failed")
        assertEquals(CdpException.Category.TRANSPORT, e.category)
        assertEquals("TRANSPORT_ERROR", e.code)
    }

    @Test
    fun connectionException_hasCorrectCategory() {
        val e = CdpException.connection("Connection lost")
        assertEquals(CdpException.Category.CONNECTION, e.category)
        assertEquals("CONNECTION_ERROR", e.code)
    }

    @Test
    fun protocolException_hasCorrectCategory() {
        val e = CdpException.protocol("Bad protocol")
        assertEquals(CdpException.Category.PROTOCOL, e.category)
        assertEquals("PROTOCOL_ERROR", e.code)
    }

    @Test
    fun timeoutException_hasCorrectCategory() {
        val e = CdpException.timeout("Timed out")
        assertEquals(CdpException.Category.COMMAND_TIMEOUT, e.category)
        assertEquals("COMMAND_TIMEOUT", e.code)
    }

    @Test
    fun targetException_hasCorrectCategory() {
        val e = CdpException.target("Target error")
        assertEquals(CdpException.Category.TARGET, e.category)
        assertEquals("TARGET_ERROR", e.code)
    }

    @Test
    fun sessionException_hasCorrectCategory() {
        val e = CdpException.session("Session error")
        assertEquals(CdpException.Category.SESSION, e.category)
        assertEquals("SESSION_ERROR", e.code)
    }

    @Test
    fun parsingException_hasCorrectCategory() {
        val e = CdpException.parsing("Parse error")
        assertEquals(CdpException.Category.PARSING, e.category)
        assertEquals("PARSING_ERROR", e.code)
    }

    @Test
    fun unsupportedException_hasCorrectCategory() {
        val e = CdpException.unsupported("Unsupported")
        assertEquals(CdpException.Category.UNSUPPORTED_OPERATION, e.category)
        assertEquals("UNSUPPORTED_OPERATION", e.code)
    }

    @Test
    fun commandException_hasCorrectCategory() {
        val e = CdpException.command("Command failed")
        assertEquals(CdpException.Category.COMMAND, e.category)
        assertEquals("COMMAND_ERROR", e.code)
    }

    @Test
    fun factoryMethods_createCorrectCategories() {
        assertEquals(CdpException.Category.TRANSPORT, CdpException.transport("msg").category)
        assertEquals(CdpException.Category.CONNECTION, CdpException.connection("msg").category)
        assertEquals(CdpException.Category.PROTOCOL, CdpException.protocol("msg").category)
        assertEquals(CdpException.Category.COMMAND_TIMEOUT, CdpException.timeout("msg").category)
        assertEquals(CdpException.Category.TARGET, CdpException.target("msg").category)
        assertEquals(CdpException.Category.SESSION, CdpException.session("msg").category)
        assertEquals(CdpException.Category.PARSING, CdpException.parsing("msg").category)
        assertEquals(CdpException.Category.UNSUPPORTED_OPERATION, CdpException.unsupported("msg").category)
        assertEquals(CdpException.Category.COMMAND, CdpException.command("msg").category)
    }

    @Test
    fun exception_preservesCause() {
        val cause = RuntimeException("Root cause")
        val e = TransportException("Wrapper", cause)
        assertEquals(cause, e.cause)
    }

    @Test
    fun exception_preservesMessage() {
        val e = ProtocolException("Detailed protocol message")
        assertEquals("Detailed protocol message", e.message)
    }

    @Test
    fun exception_customCode_preserved() {
        val e = TransportException("msg", code = "CUSTOM_42")
        assertEquals("CUSTOM_42", e.code)
    }

    @Test
    fun allExceptions_areRuntimeExceptions() {
        val exceptions = listOf(
            CdpException.transport("a"),
            CdpException.connection("b"),
            CdpException.protocol("c"),
            CdpException.timeout("d"),
            CdpException.target("e"),
            CdpException.session("f"),
            CdpException.parsing("g"),
            CdpException.unsupported("h"),
            CdpException.command("i"),
        )
        exceptions.forEach { assertTrue(it is RuntimeException) }
    }
}
