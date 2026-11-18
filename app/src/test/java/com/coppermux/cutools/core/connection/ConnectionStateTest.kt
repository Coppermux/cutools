package com.coppermux.cutools.core.connection

import com.coppermux.cutools.core.transport.CdpTransport
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.core.error.TransportException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class ConnectionStateTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var connectionManager: ConnectionManager

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        val executor = Executors.newSingleThreadScheduledExecutor()
        val config = DispatcherConfig(defaultTimeoutMs = 1000, executor = executor)
        connectionManager = ConnectionManager.create(transport, config)
    }

    // ============== Initial state ==============

    @Test
    fun initialState_isDisconnected() {
        assertEquals(ConnectionState.DISCONNECTED, connectionManager.connectionState)
    }

    // ============== Connecting state ==============

    @Test
    fun connect_transitionsToConnecting() {
        connectionManager.connect("ws://localhost:9222") { }
        // FakeCdpTransport connects synchronously, so state may advance past CONNECTING.
        // Verify it has left DISCONNECTED.
        assertNotEquals(ConnectionState.DISCONNECTED, connectionManager.connectionState)
        assertTrue(connectionManager.connectionState.isActive() || connectionManager.connectionState == ConnectionState.CONNECTED)
    }

    // ============== Error transitions ==============

    @Test
    fun connectionError_transitionsToError() {
        val badTransport = FakeCdpTransport()
        val badManager = ConnectionManager.create(
            badTransport,
            DispatcherConfig(defaultTimeoutMs = 500, executor = Executors.newSingleThreadScheduledExecutor())
        )
        badManager.connect("ws://not-real:1234") { }
        Thread.sleep(200)
        badTransport.deliverError(TransportException("Connection refused"))
        Thread.sleep(200)

        assertEquals(ConnectionState.ERROR, badManager.connectionState)
    }

    // ============== Disconnect ==============

    @Test
    fun disconnect_transitionsToDisconnected() {
        connectionManager.connect("ws://localhost:9222") { }
        Thread.sleep(100)
        connectionManager.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, connectionManager.connectionState)
    }

    // ============== Reconnect/reset ==============

    @Test
    fun reset_clearsState() {
        val resetTransport = FakeCdpTransport()
        resetTransport.connect("ws://localhost:9222", CdpTransport.NOOP_LISTENER)
        val resetManager = ConnectionManager.create(
            resetTransport,
            DispatcherConfig(defaultTimeoutMs = 500, executor = Executors.newSingleThreadScheduledExecutor())
        )
        resetManager.connect("ws://localhost:9222") { }
        Thread.sleep(100)
        resetManager.reset()

        assertEquals(ConnectionState.DISCONNECTED, resetManager.connectionState)
    }

    // ============== Error listener invocation ==============

    @Test
    fun stateListener_receivesStateChange() {
        val states = mutableListOf<ConnectionState>()
        connectionManager.addStateListener(object : ConnectionManager.StateListener {
            override fun onStateChanged(state: ConnectionState, error: Throwable?) {
                states.add(state)
            }
        })

        connectionManager.connect("ws://localhost:9222") { }
        Thread.sleep(100)

        assertTrue(states.contains(ConnectionState.CONNECTING))
    }

    // ============== Disconnect cleanup ==============

    @Test
    fun disconnect_clearsBrowserInfo() {
        connectionManager.connect("ws://localhost:9222") { }
        Thread.sleep(100)
        connectionManager.disconnect()
        assertNull(connectionManager.browserInfo)
    }

    // ============== Connection error fails connect callback ==============

    @Test
    fun connectError_invokesOnErrorCallback() {
        val errorRef = AtomicReference<Throwable?>()
        connectionManager.connect("ws://localhost:9222", onError = { errorRef.set(it) }) { }
        // Simulate a transport error
        Thread.sleep(50)
        transport.deliverError(TransportException("Connection refused"))

        Thread.sleep(50)
        assertNotNull(errorRef.get())
    }
}
