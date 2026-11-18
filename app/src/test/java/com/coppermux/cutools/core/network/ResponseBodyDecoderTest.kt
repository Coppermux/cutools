package com.coppermux.cutools.core.network

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ResponseBodyDecoder] — text and base64/binary response body
 * decoding, plus explicit status handling for missing/undecodable bodies.
 */
class ResponseBodyDecoderTest {

    private fun b64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun plainTextBody_isReturnedAsSuccess() {
        val result = ResponseBodyDecoder.decode("hello world", base64Encoded = false)
        assertEquals(ResponseBodyStatus.SUCCESS, result.status)
        assertEquals("hello world", result.text)
        assertNull(result.bytes)
        assertNull(result.error)
    }

    @Test
    fun base64TextBody_isDecodedToUtf8() {
        val result = ResponseBodyDecoder.decode(b64("{\"name\":\"cu\"}"), base64Encoded = true)
        assertEquals(ResponseBodyStatus.SUCCESS, result.status)
        assertEquals("{\"name\":\"cu\"}", result.text)
    }

    @Test
    fun base64BinaryBody_isReportedAsBinary() {
        val raw = byteArrayOf(0xFF.toByte(), 0x00.toByte(), 0xFE.toByte())
        val encoded = Base64.getEncoder().encodeToString(raw)
        val result = ResponseBodyDecoder.decode(encoded, base64Encoded = true)
        assertEquals(ResponseBodyStatus.BINARY, result.status)
        assertNull(result.text)
        assertTrue(result.bytes != null)
        assertArrayEquals(raw, result.bytes!!)
    }

    @Test
    fun missingBody_isUnavailable() {
        val result = ResponseBodyDecoder.decode(null, base64Encoded = false)
        assertEquals(ResponseBodyStatus.UNAVAILABLE, result.status)
        assertNull(result.text)
        assertTrue(result.error != null)
    }

    @Test
    fun emptyBase64Body_isUnavailable() {
        val result = ResponseBodyDecoder.decode(null, base64Encoded = true)
        assertEquals(ResponseBodyStatus.UNAVAILABLE, result.status)
    }

    @Test
    fun invalidBase64_isUnsupportedNotThrown() {
        val result = ResponseBodyDecoder.decode("!!!not-base64!!!", base64Encoded = true)
        assertEquals(ResponseBodyStatus.UNSUPPORTED, result.status)
        assertNull(result.text)
        assertTrue(result.error != null)
    }
}