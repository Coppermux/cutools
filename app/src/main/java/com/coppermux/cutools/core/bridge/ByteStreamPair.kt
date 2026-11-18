package com.coppermux.cutools.core.bridge

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Encapsulates an active bidirectional byte stream (input and output)
 * connected to a DevTools endpoint.
 */
interface ByteStreamPair : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
}
