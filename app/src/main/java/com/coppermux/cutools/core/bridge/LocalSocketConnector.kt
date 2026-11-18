package com.coppermux.cutools.core.bridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream

/**
 * Interface for establishing connections to a Linux domain socket.
 *
 * Decouples the concrete Android framework classes ([LocalSocket], [LocalSocketAddress])
 * so protocol handling, proxying, and lifecycles can be validated deterministically in JVM tests.
 */
interface LocalSocketConnector {
    /**
     * Connects to the given abstract UNIX domain socket name.
     *
     * @param abstractSocketName The socket name in the Linux abstract namespace (without leading null or '@').
     * @return A connected [ByteStreamPair] providing access to the raw input and output streams.
     * @throws java.io.IOException if the connection fails or is refused.
     */
    fun connect(abstractSocketName: String): ByteStreamPair
}

/**
 * Production implementation using Android's native [LocalSocket] connected to
 * [LocalSocketAddress.Namespace.ABSTRACT].
 */
class AndroidLocalSocketConnector : LocalSocketConnector {
    override fun connect(abstractSocketName: String): ByteStreamPair {
        val socket = LocalSocket()
        val address = LocalSocketAddress(abstractSocketName, LocalSocketAddress.Namespace.ABSTRACT)
        socket.connect(address)
        return object : ByteStreamPair {
            override val inputStream: InputStream
                get() = socket.inputStream
            override val outputStream: OutputStream
                get() = socket.outputStream

            override fun close() {
                runCatching { socket.close() }
            }
        }
    }
}
