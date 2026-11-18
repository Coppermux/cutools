package com.coppermux.cutools.core.transport

import com.coppermux.cutools.core.transport.CdpTransport
import com.coppermux.cutools.core.error.TransportException
import org.junit.Assert.*
import org.junit.Test

class FakeCdpTransportTest {

    @Test
    fun connect_setsConnectedTrue() {
        val transport = FakeCdpTransport()
        var connected = false
        transport.connect("ws://localhost:9222", object : CdpTransport.Listener {
            override fun onConnected() { connected = true }
            override fun onMessage(rawJson: String) {}
            override fun onClosed(reason: String) {}
            override fun onError(error: TransportException) {}
        })
        assertTrue(connected)
        assertTrue(transport.isConnected)
    }

    @Test
    fun connect_deliversConnectedCallback() {
        val transport = FakeCdpTransport()
        var callbackCalled = false
        transport.connect("ws://localhost:9222", object : CdpTransport.Listener {
            override fun onConnected() { callbackCalled = true }
            override fun onMessage(rawJson: String) {}
            override fun onClosed(reason: String) {}
            override fun onError(error: TransportException) {}
        })
        assertTrue(callbackCalled)
    }

    @Test
    fun disconnect_setsConnectedFalse() {
        val transport = FakeCdpTransport()
        transport.connect("ws://localhost:9222", CdpTransport.NOOP_LISTENER)
        transport.disconnect()
        assertFalse(transport.isConnected)
    }

    @Test
    fun send_recordsMessage() {
        val transport = FakeCdpTransport()
        transport.connect("ws://localhost:9222", CdpTransport.NOOP_LISTENER)
        transport.send("{\"id\":1,\"method\":\"Browser.getVersion\"}")
        assertEquals(1, transport.sentMessages.size)
        assertEquals("{\"id\":1,\"method\":\"Browser.getVersion\"}", transport.sentMessages[0])
    }

    @Test
    fun send_whenDisconnected_throwsTransportException() {
        val transport = FakeCdpTransport()
        try {
            transport.send("test")
            fail("Expected TransportException")
        } catch (e: TransportException) {
            assertTrue(e.message?.contains("not connected") == true)
        }
    }

    @Test
    fun deliverMessage_callsOnMessage() {
        val transport = FakeCdpTransport()
        var received = ""
        transport.connect("ws://localhost:9222", object : CdpTransport.Listener {
            override fun onConnected() {}
            override fun onMessage(rawJson: String) { received = rawJson }
            override fun onClosed(reason: String) {}
            override fun onError(error: TransportException) {}
        })
        transport.deliverMessage("{\"id\":1,\"result\":{}}")
        assertEquals("{\"id\":1,\"result\":{}}", received)
    }

    @Test
    fun deliverError_callsOnErrorAndOnClosed() {
        val transport = FakeCdpTransport()
        var errorReceived: TransportException? = null
        var closedReceived = false
        transport.connect("ws://localhost:9222", object : CdpTransport.Listener {
            override fun onConnected() {}
            override fun onMessage(rawJson: String) {}
            override fun onClosed(reason: String) { closedReceived = true }
            override fun onError(error: TransportException) { errorReceived = error }
        })
        val testError = TransportException("Test error")
        transport.deliverError(testError)
        assertNotNull(errorReceived)
        assertEquals("Test error", errorReceived!!.message)
        assertTrue(closedReceived)
        assertFalse(transport.isConnected)
    }

    @Test
    fun deliverClosed_callsOnClosed() {
        val transport = FakeCdpTransport()
        var closedReason = ""
        transport.connect("ws://localhost:9222", object : CdpTransport.Listener {
            override fun onConnected() {}
            override fun onMessage(rawJson: String) {}
            override fun onClosed(reason: String) { closedReason = reason }
            override fun onError(error: TransportException) {}
        })
        transport.deliverClosed("Normal closure")
        assertEquals("Normal closure", closedReason)
        assertFalse(transport.isConnected)
    }

    @Test
    fun connectedUrl_isCorrect() {
        val transport = FakeCdpTransport()
        transport.connect("ws://192.168.1.1:9222/devtools/browser", CdpTransport.NOOP_LISTENER)
        assertEquals("ws://192.168.1.1:9222/devtools/browser", transport.connectedUrl)
    }
}
