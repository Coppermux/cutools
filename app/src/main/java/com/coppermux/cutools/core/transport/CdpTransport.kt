package com.coppermux.cutools.core.transport

import com.coppermux.cutools.core.error.TransportException

/**
 * Asynchronous CDP transport abstraction.
 *
 * Implementations must:
 * - Execute all network I/O off the Android UI thread.
 * - Deliver incoming messages via [CdpTransportListener].
 * - Report connection lifecycle and transport failures via [CdpTransportListener].
 * - Be replaceable with a fake/in-memory transport for unit testing.
 */
interface CdpTransport {

    /**
     * Listener for transport events. All callbacks must be invoked on a non-UI thread
     * (unless the caller explicitly marshals to UI).
     */
    interface Listener {
        /** Called when the WebSocket connection is established. */
        fun onConnected()

        /** Called when a complete CDP message is received from the server. */
        fun onMessage(rawJson: String)

        /** Called when the connection is closed (cleanly or due to error). */
        fun onClosed(reason: String)

        /** Called when a transport-level error occurs (e.g., connection failure, I/O error). */
        fun onError(error: TransportException)
    }

    /**
     * Establish a WebSocket connection to the given [url].
     * Must be asynchronous — must not block the calling thread.
     *
     * @param url The WebSocket URL for the CDP debugging endpoint.
     * @param listener The listener to receive events.
     * @throws TransportException if the connection parameters are invalid (before attempting connect).
     */
    fun connect(url: String, listener: Listener)

    /**
     * Establish a WebSocket connection with an optional auto-reconnect policy.
     *
     * The base contract does not mandate reconnect behavior; implementations
     * that support resilience (e.g., [CdpSession]) drive exponential-backoff
     * reconnection from [ReconnectPolicy]. This overload delegates to
     * [connect(url, listener)] by default so existing transports keep working
     * unchanged.
     *
     * @param url The WebSocket URL for the CDP debugging endpoint.
     * @param listener The listener to receive events.
     * @param reconnect The auto-reconnect policy, or `null` for none.
     */
    fun connect(url: String, listener: Listener, reconnect: ReconnectPolicy? = null) {
        connect(url, listener)
    }

    /**
     * Send a serialized CDP message through the transport.
     * Must be asynchronous or safe to call from any thread.
     *
     * @throws TransportException if not connected or the send fails.
     */
    fun send(rawMessage: String)

    /**
     * Close the transport and release all resources.
     * Must not block.
     */
    fun disconnect()

    /** Current connection state. */
    val isConnected: Boolean

    companion object {
        /** No-op listener for cases where only specific callbacks are needed. */
        val NOOP_LISTENER: Listener = object : Listener {
            override fun onConnected() {}
            override fun onMessage(rawJson: String) {}
            override fun onClosed(reason: String) {}
            override fun onError(error: TransportException) {}
        }
    }
}
