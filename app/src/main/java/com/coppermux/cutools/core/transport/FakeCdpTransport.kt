package com.coppermux.cutools.core.transport

import com.coppermux.cutools.core.error.TransportException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory fake [CdpTransport] for deterministic unit tests.
 *
 * Does NOT touch the network. Messages delivered via [deliverMessage] simulate
 * incoming messages from Chrome. Sent messages are recorded in [sentMessages]
 * for test verification.
 *
 * Thread-safe: call [connect], [send], [deliverMessage], [deliverError],
 * and [disconnect] from any thread.
 */
class FakeCdpTransport : CdpTransport {

    private val _sentMessages = CopyOnWriteArrayList<String>()
    val sentMessages: List<String> get() = _sentMessages

    private val _listeners = CopyOnWriteArrayList<CdpTransport.Listener>()
    val listeners: List<CdpTransport.Listener> get() = _listeners

    private val _connected = AtomicBoolean(false)
    private val urlRef = AtomicReference<String?>(null)

    override val isConnected: Boolean get() = _connected.get()

    /** The URL the transport was last connected to. */
    val connectedUrl: String? get() = urlRef.get()

    override fun connect(url: String, listener: CdpTransport.Listener) {
        urlRef.set(url)
        // Reconnects must not double-register the same listener (leak-free).
        if (!_listeners.contains(listener)) {
            _listeners.add(listener)
        }
        _connected.set(true)
        listener.onConnected()
    }
    override fun send(rawMessage: String) {
        if (!_connected.get()) {
            throw TransportException("Cannot send: transport is not connected")
        }
        _sentMessages.add(rawMessage)
    }

    override fun disconnect() {
        _connected.set(false)
        val listenersCopy = _listeners.toList()
        _listeners.clear()
        listenersCopy.forEach { it.onClosed("Transport disconnected") }
    }

    /** Simulates Chrome sending a message to the client. */
    fun deliverMessage(rawJson: String) {
        if (!_connected.get()) return
        _listeners.forEach { it.onMessage(rawJson) }
    }

    /** Simulates a transport error from Chrome. */
    fun deliverError(error: TransportException) {
        _connected.set(false)
        _listeners.forEach {
            it.onError(error)
            it.onClosed(error.message ?: "Transport error")
        }
    }

    /** Simulates Chrome closing the connection. */
    fun deliverClosed(reason: String) {
        _connected.set(false)
        _listeners.forEach { it.onClosed(reason) }
    }

    /** Clears all recorded state (does not change connection state). */
    fun resetSentMessages() {
        _sentMessages.clear()
    }
}
