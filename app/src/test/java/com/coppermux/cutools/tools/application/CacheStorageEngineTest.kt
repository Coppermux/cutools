package com.coppermux.cutools.tools.application

import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.tools.application.engine.CacheStorageEngine
import com.coppermux.cutools.tools.application.models.CacheActionStatus
import com.coppermux.cutools.tools.application.models.CacheActionResult
import com.coppermux.cutools.tools.application.models.CacheEntryModel
import com.coppermux.cutools.tools.application.models.CacheModel
import com.coppermux.cutools.tools.application.models.CacheStorageCapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Sub-Phase 6D Cache Storage inspection.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → dispatcher → engine, proving `CacheStorage.requestCacheNames`,
 * `requestEntries`, `deleteCache`, and `deleteEntry` serialization and genuine
 * response parsing in the same pipeline the app uses against Chrome.
 * No-Fake-Capability is enforced: protocol failures, missing sessions, and
 * unavailable methods map to explicit UNSUPPORTED / UNAVAILABLE / FAILED
 * outcomes, never fabricated cache buckets or entries.
 */
class CacheStorageEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: CacheStorageEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        val router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = CacheStorageEngine(dispatcher)
    }

    private fun bind() {
        engine.bindSession(session)
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    // ============ payload generation ============

    @Test
    fun getCacheNames_serializesOriginAndSessionId() {
        bind()
        engine.getCacheNames("https://example.com/") {}
        val raw = transport.sentMessages.last { it.contains("CacheStorage.requestCacheNames") }
        assertTrue(raw.contains("\"method\":\"CacheStorage.requestCacheNames\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com/\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun getCacheEntries_serializesCacheIdSkipLimitAndSessionId() {
        bind()
        engine.getCacheEntries("cache-9", 10, 25) {}
        val raw = transport.sentMessages.last { it.contains("CacheStorage.requestEntries") }
        assertTrue(raw.contains("\"method\":\"CacheStorage.requestEntries\""))
        assertTrue(raw.contains("\"cacheId\":\"cache-9\""))
        assertTrue(raw.contains("\"skipCount\":10"))
        assertTrue(raw.contains("\"pageSize\":25"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun deleteCache_serializesCacheIdAndSessionId() {
        bind()
        engine.deleteCache("cache-9") {}
        val raw = transport.sentMessages.last { it.contains("CacheStorage.deleteCache") }
        assertTrue(raw.contains("\"method\":\"CacheStorage.deleteCache\""))
        assertTrue(raw.contains("\"cacheId\":\"cache-9\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun deleteCacheEntry_serializesCacheIdRequestUrlAndSessionId() {
        bind()
        engine.deleteCacheEntry("cache-9", "https://example.com/app.js") {}
        val raw = transport.sentMessages.last { it.contains("CacheStorage.deleteEntry") }
        assertTrue(raw.contains("\"method\":\"CacheStorage.deleteEntry\""))
        assertTrue(raw.contains("\"cacheId\":\"cache-9\""))
        assertTrue(raw.contains("\"requestUrl\":\"https://example.com/app.js\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ parsing ============

    @Test
    fun getCacheNames_success_parsesBuckets() {
        bind()
        val results = CopyOnWriteArrayList<CacheActionResult<List<CacheModel>>>()
        engine.getCacheNames("https://example.com/") { results.add(it) }
        val id = parseIdFor("CacheStorage.requestCacheNames")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"caches":[
                {"origin":"https://example.com","cacheId":"cache-1","cacheName":"my-app-shell"},
                {"origin":"https://example.com","cacheId":"cache-2","cacheName":"my-data"}
            ]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CacheActionStatus.SUCCESS, results[0].status)
        val buckets = results[0].value!!
        assertEquals(2, buckets.size)

        assertEquals("cache-1", buckets[0].cacheId)
        assertEquals("https://example.com", buckets[0].securityOrigin)
        assertEquals("my-app-shell", buckets[0].cacheName)

        assertEquals("cache-2", buckets[1].cacheId)
        assertEquals("my-data", buckets[1].cacheName)
        assertEquals(CacheStorageCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun getCacheEntries_success_parsesEntriesAndHeaders() {
        bind()
        val results = CopyOnWriteArrayList<CacheActionResult<List<CacheEntryModel>>>()
        engine.getCacheEntries("cache-9", 0, 50) { results.add(it) }
        val id = parseIdFor("CacheStorage.requestEntries")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"entries":[
                {"requestURL":"https://example.com/app.js","requestMethod":"GET","responseStatus":200,"responseHeaders":[
                    {"name":"content-type","value":"application/javascript"},
                    {"name":"cache-control","value":"max-age=3600"}
                ]},
                {"requestURL":"https://example.com/data.json","requestMethod":"POST","responseStatus":404,"responseHeaders":[]}
            ]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CacheActionStatus.SUCCESS, results[0].status)
        val entries = results[0].value!!
        assertEquals(2, entries.size)

        assertEquals("https://example.com/app.js", entries[0].requestUrl)
        assertEquals("GET", entries[0].requestMethod)
        assertEquals(listOf("content-type", "cache-control"), entries[0].responseHeaderNames)

        assertEquals("https://example.com/data.json", entries[1].requestUrl)
        assertEquals("POST", entries[1].requestMethod)
        assertTrue(entries[1].responseHeaderNames.isEmpty())
    }

    @Test
    fun getCacheNames_missingCaches_returnsEmptyList() {
        bind()
        val results = CopyOnWriteArrayList<CacheActionResult<List<CacheModel>>>()
        engine.getCacheNames("https://example.com/") { results.add(it) }
        val id = parseIdFor("CacheStorage.requestCacheNames")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CacheActionStatus.SUCCESS, results[0].status)
        assertTrue(results[0].value!!.isEmpty())
    }

    @Test
    fun getCacheNames_missingResultObject_failsExplicitly() {
        bind()
        val results = CopyOnWriteArrayList<CacheActionResult<List<CacheModel>>>()
        engine.getCacheNames("https://example.com/") { results.add(it) }
        val id = parseIdFor("CacheStorage.requestCacheNames")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":"nope"}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CacheActionStatus.FAILED, results[0].status)
        assertNull(results[0].value)
        assertEquals(CacheStorageCapabilityStatus.FAILED, engine.capability)
    }

    // ============ error mapping ============

    @Test
    fun getCacheNames_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CacheActionResult<List<CacheModel>>>()
        engine.getCacheNames("https://example.com/") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(CacheActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(CacheStorageCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getCacheEntries_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CacheActionResult<List<CacheEntryModel>>>()
        engine.getCacheEntries("cache-9", 0, 10) { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(CacheActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun deleteCache_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CacheActionResult<Unit>>()
        engine.deleteCache("cache-9") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(CacheActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun deleteCacheEntry_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CacheActionResult<Unit>>()
        engine.deleteCacheEntry("cache-9", "https://example.com/app.js") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(CacheActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getCacheNames_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<CacheActionResult<List<CacheModel>>>()
        engine.getCacheNames("https://example.com/") { results.add(it) }
        val id = parseIdFor("CacheStorage.requestCacheNames")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"cache read failed"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CacheActionStatus.FAILED, results[0].status)
        assertTrue(results[0].message.orEmpty().contains("cache read failed"))
        assertEquals(CacheStorageCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun getCacheEntries_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<CacheActionResult<List<CacheEntryModel>>>()
        engine.getCacheEntries("cache-9", 0, 10) { results.add(it) }
        val id = parseIdFor("CacheStorage.requestEntries")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CacheActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(CacheStorageCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    @Test
    fun deleteCache_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<CacheActionResult<Unit>>()
        engine.deleteCache("cache-9") { results.add(it) }
        val id = parseIdFor("CacheStorage.deleteCache")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"delete cache broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CacheActionStatus.FAILED, results[0].status)
        assertEquals(CacheStorageCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun deleteCacheEntry_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<CacheActionResult<Unit>>()
        engine.deleteCacheEntry("cache-9", "https://example.com/app.js") { results.add(it) }
        val id = parseIdFor("CacheStorage.deleteEntry")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CacheActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(CacheStorageCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    // ============ mutation success ============

    @Test
    fun deleteCache_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<CacheActionResult<Unit>>()
        engine.deleteCache("cache-9") { results.add(it) }
        val id = parseIdFor("CacheStorage.deleteCache")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CacheActionStatus.SUCCESS, results[0].status)
        assertEquals(CacheStorageCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun deleteCacheEntry_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<CacheActionResult<Unit>>()
        engine.deleteCacheEntry("cache-9", "https://example.com/app.js") { results.add(it) }
        val id = parseIdFor("CacheStorage.deleteEntry")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CacheActionStatus.SUCCESS, results[0].status)
        assertEquals(CacheStorageCapabilityStatus.SUPPORTED, engine.capability)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsSessionAndCapability() {
        bind()
        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        engine.getCacheNames("https://example.com/") {}
        val id = parseIdFor("CacheStorage.requestCacheNames")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"caches":[]}}""")
        assertTrue(waitFor { engine.capability == CacheStorageCapabilityStatus.SUPPORTED })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        engine.onConnectionLost()
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<CacheActionResult<List<CacheModel>>>()
        engine.getCacheNames("https://example.com/") { results.add(it) }
        val id = parseIdFor("CacheStorage.requestCacheNames")

        engine.detach()
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"caches":[{"origin":"x","cacheId":"c1","cacheName":"n"}]}}"""
        )
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    // ============ model-level mapping ============

    @Test
    fun cacheModel_holdsGenuineFields() {
        val bucket = CacheModel(cacheId = "c1", securityOrigin = "https://example.com", cacheName = "shell")
        assertEquals("c1", bucket.cacheId)
        assertEquals("https://example.com", bucket.securityOrigin)
        assertEquals("shell", bucket.cacheName)
    }

    @Test
    fun cacheEntryModel_defaultsAreSafe() {
        val entry = CacheEntryModel()
        assertEquals("", entry.requestUrl)
        assertEquals("", entry.requestMethod)
        assertTrue(entry.responseHeaderNames.isEmpty())
    }
}