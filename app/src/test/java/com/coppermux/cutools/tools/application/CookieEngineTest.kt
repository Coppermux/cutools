package com.coppermux.cutools.tools.application

import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.tools.application.engine.CookieEngine
import com.coppermux.cutools.tools.application.models.CookieActionStatus
import com.coppermux.cutools.tools.application.models.CookieActionResult
import com.coppermux.cutools.tools.application.models.CookieCapabilityStatus
import com.coppermux.cutools.tools.application.models.CookieModel
import com.coppermux.cutools.tools.application.models.CookiePriority
import com.coppermux.cutools.tools.application.models.SameSitePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Sub-Phase 6C Cookie Inspector & Manager.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → dispatcher → engine, proving
 * `Network.getCookies/setCookie/deleteCookies` and `Storage.clearCookies`
 * serialization and genuine response parsing in the same pipeline the app uses
 * against Chrome. No-Fake-Capability is enforced: protocol failures, rejected
 * setCookie results, and missing sessions map to explicit UNSUPPORTED /
 * UNAVAILABLE / FAILED outcomes, never fabricated cookie values.
 */
class CookieEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: CookieEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        val router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = CookieEngine(dispatcher)
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
    fun getCookies_serializesUrlsAndSessionId() {
        bind()
        engine.getCookies(listOf("https://example.com/")) {}
        val raw = transport.sentMessages.last { it.contains("Network.getCookies") }
        assertTrue(raw.contains("\"method\":\"Network.getCookies\""))
        assertTrue(raw.contains("\"urls\":[\"https://example.com/\"]"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun setCookie_serializesNameValueUrlAndSessionId() {
        bind()
        engine.setCookie(CookieModel(name = "theme", value = "dark"), "https://example.com/") {}
        val raw = transport.sentMessages.last { it.contains("Network.setCookie") }
        assertTrue(raw.contains("\"method\":\"Network.setCookie\""))
        assertTrue(raw.contains("\"name\":\"theme\""))
        assertTrue(raw.contains("\"value\":\"dark\""))
        assertTrue(raw.contains("\"url\":\"https://example.com/\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun setCookie_serializesSafetyAttributes() {
        bind()
        engine.setCookie(
            CookieModel(
                name = "auth",
                value = "abc",
                domain = "example.com",
                path = "/secure",
                expires = 1_700_000_000L,
                httpOnly = true,
                secure = true,
                sameSite = SameSitePolicy.STRICT,
                priority = CookiePriority.HIGH,
            ),
            "https://example.com/",
        ) {}
        val raw = transport.sentMessages.last { it.contains("Network.setCookie") }
        assertTrue(raw.contains("\"domain\":\"example.com\""))
        assertTrue(raw.contains("\"path\":\"/secure\""))
        assertTrue(raw.contains("\"httpOnly\":true"))
        assertTrue(raw.contains("\"secure\":true"))
        assertTrue(raw.contains("\"sameSite\":\"Strict\""))
        assertTrue(raw.contains("\"priority\":\"High\""))
        assertTrue(raw.contains("\"expires\":1700000000"))
    }

    @Test
    fun setCookie_sessionCookie_omitsExpires() {
        bind()
        engine.setCookie(
            CookieModel(name = "sid", value = "x", session = true, expires = 1_700_000_000L),
            "https://example.com/",
        ) {}
        val raw = transport.sentMessages.last { it.contains("Network.setCookie") }
        assertTrue(!raw.contains("\"expires\""))
    }

    @Test
    fun setCookie_omitsBlankDomainAndPath() {
        bind()
        engine.setCookie(CookieModel(name = "k", value = "v"), "https://example.com/") {}
        val raw = transport.sentMessages.last { it.contains("Network.setCookie") }
        assertTrue(!raw.contains("\"domain\""))
        assertTrue(!raw.contains("\"path\""))
    }

    @Test
    fun deleteCookie_serializesNameDomainPathUrlAndSessionId() {
        bind()
        engine.deleteCookie("theme", "example.com", "/", "https://example.com/") {}
        val raw = transport.sentMessages.last { it.contains("Network.deleteCookies") }
        assertTrue(raw.contains("\"method\":\"Network.deleteCookies\""))
        assertTrue(raw.contains("\"name\":\"theme\""))
        assertTrue(raw.contains("\"domain\":\"example.com\""))
        assertTrue(raw.contains("\"path\":\"/\""))
        assertTrue(raw.contains("\"url\":\"https://example.com/\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun deleteCookie_withBlankScope_omitsOptionalParams() {
        bind()
        engine.deleteCookie("theme", "", "", "https://example.com/") {}
        val raw = transport.sentMessages.last { it.contains("Network.deleteCookies") }
        assertTrue(!raw.contains("\"domain\""))
        assertTrue(!raw.contains("\"path\""))
        assertTrue(raw.contains("\"url\":\"https://example.com/\""))
    }

    @Test
    fun clearCookies_serializesMethodAndSessionId() {
        bind()
        engine.clearCookies() {}
        val raw = transport.sentMessages.last { it.contains("Storage.clearCookies") }
        assertTrue(raw.contains("\"method\":\"Storage.clearCookies\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun clearCookies_withBrowserContextId_serializesContext() {
        bind()
        engine.clearCookies("browser-context-9") {}
        val raw = transport.sentMessages.last { it.contains("Storage.clearCookies") }
        assertTrue(raw.contains("\"browserContextId\":\"browser-context-9\""))
    }

    // ============ parsing ============

    @Test
    fun getCookies_success_parsesCookiesAndAttributes() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<List<CookieModel>>>()
        engine.getCookies(listOf("https://example.com/")) { results.add(it) }
        val id = parseIdFor("Network.getCookies")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"cookies":[
                {"name":"auth","value":"abc","domain":".example.com","path":"/","expires":1700000000,"size":29,"httpOnly":true,"secure":true,"session":false,"sameSite":"Lax","priority":"High"},
                {"name":"theme","value":"dark","domain":".example.com","path":"/","expires":-1,"size":11,"httpOnly":false,"secure":false,"session":true,"sameSite":"None","priority":"Low"}
            ]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.SUCCESS, results[0].status)
        val cookies = results[0].value!!
        assertEquals(2, cookies.size)

        val persistent = cookies[0]
        assertEquals("auth", persistent.name)
        assertEquals("abc", persistent.value)
        assertEquals(".example.com", persistent.domain)
        assertEquals("/", persistent.path)
        assertEquals(1_700_000_000L, persistent.expires)
        assertEquals(29, persistent.size)
        assertTrue(persistent.httpOnly)
        assertTrue(persistent.secure)
        assertTrue(!persistent.session)
        assertEquals(SameSitePolicy.LAX, persistent.sameSite)
        assertEquals(CookiePriority.HIGH, persistent.priority)

        val sessionCookie = cookies[1]
        assertEquals("theme", sessionCookie.name)
        assertEquals("dark", sessionCookie.value)
        assertTrue(sessionCookie.session)
        assertNull(sessionCookie.expires)
        assertEquals(SameSitePolicy.NONE, sessionCookie.sameSite)
        assertEquals(CookiePriority.LOW, sessionCookie.priority)

        assertEquals(cookies, engine.latestCookies)
        assertEquals(CookieCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun getCookies_sessionFlagMissing_derivesSessionFromExpires() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<List<CookieModel>>>()
        engine.getCookies(listOf("https://example.com/")) { results.add(it) }
        val id = parseIdFor("Network.getCookies")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"cookies":[
                {"name":"s","value":"v","domain":"x","path":"/","expires":-1,"size":1},
                {"name":"p","value":"v","domain":"x","path":"/","expires":2000000000,"size":1}
            ]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val cookies = results[0].value!!
        assertTrue(cookies[0].session)
        assertNull(cookies[0].expires)
        assertTrue(!cookies[1].session)
        assertEquals(2_000_000_000L, cookies[1].expires)
    }

    @Test
    fun getCookies_success_missingCookies_returnsEmptyList() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<List<CookieModel>>>()
        engine.getCookies(listOf("https://example.com/")) { results.add(it) }
        val id = parseIdFor("Network.getCookies")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.SUCCESS, results[0].status)
        assertTrue(results[0].value!!.isEmpty())
        assertTrue(engine.latestCookies!!.isEmpty())
    }

    @Test
    fun getCookies_unknownSameSiteAndPriority_mapToNull() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<List<CookieModel>>>()
        engine.getCookies(listOf("https://example.com/")) { results.add(it) }
        val id = parseIdFor("Network.getCookies")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"cookies":[
                {"name":"x","value":"v","domain":"d","path":"/","sameSite":"Bogus","priority":"Bogus"}
            ]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val cookie = results[0].value!![0]
        assertNull(cookie.sameSite)
        assertNull(cookie.priority)
    }

    @Test
    fun getCookies_missingResultObject_failsExplicitly() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<List<CookieModel>>>()
        engine.getCookies(listOf("https://example.com/")) { results.add(it) }
        val id = parseIdFor("Network.getCookies")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":42}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.FAILED, results[0].status)
        assertNull(results[0].value)
        assertEquals(CookieCapabilityStatus.FAILED, engine.capability)
    }

    // ============ error mapping ============

    @Test
    fun getCookies_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CookieActionResult<List<CookieModel>>>()
        engine.getCookies(listOf("https://example.com/")) { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(CookieActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(CookieCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun setCookie_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.setCookie(CookieModel(name = "k", value = "v"), "https://example.com/") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(CookieActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun deleteCookie_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.deleteCookie("k", "d", "/", "https://example.com/") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(CookieActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun clearCookies_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.clearCookies() { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(CookieActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getCookies_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<List<CookieModel>>>()
        engine.getCookies(listOf("https://example.com/")) { results.add(it) }
        val id = parseIdFor("Network.getCookies")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"cookie read failed"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.FAILED, results[0].status)
        assertTrue(results[0].message.orEmpty().contains("cookie read failed"))
        assertTrue(results[0].throwable != null)
        assertNull(engine.latestCookies)
        assertEquals(CookieCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun getCookies_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<List<CookieModel>>>()
        engine.getCookies(listOf("https://example.com/")) { results.add(it) }
        val id = parseIdFor("Network.getCookies")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(CookieCapabilityStatus.UNSUPPORTED, engine.capability)
        assertNull(engine.latestCookies)
    }

    @Test
    fun setCookie_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.setCookie(CookieModel(name = "k", value = "v"), "https://example.com/") { results.add(it) }
        val id = parseIdFor("Network.setCookie")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"set broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.FAILED, results[0].status)
        assertEquals(CookieCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun setCookie_rejectedByBrowser_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.setCookie(CookieModel(name = "k", value = "v"), "https://example.com/") { results.add(it) }
        val id = parseIdFor("Network.setCookie")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"success":false}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.FAILED, results[0].status)
        assertEquals(CookieCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun deleteCookie_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.deleteCookie("k", "d", "/", "https://example.com/") { results.add(it) }
        val id = parseIdFor("Network.deleteCookies")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"delete broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.FAILED, results[0].status)
        assertEquals(CookieCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun deleteCookie_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.deleteCookie("k", "d", "/", "https://example.com/") { results.add(it) }
        val id = parseIdFor("Network.deleteCookies")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(CookieCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    @Test
    fun clearCookies_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.clearCookies() { results.add(it) }
        val id = parseIdFor("Storage.clearCookies")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"clear broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.FAILED, results[0].status)
        assertEquals(CookieCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun clearCookies_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.clearCookies() { results.add(it) }
        val id = parseIdFor("Storage.clearCookies")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(CookieCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    // ============ mutation success ============

    @Test
    fun setCookie_success_returnsSuccessAndUpdatesCapability() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.setCookie(CookieModel(name = "k", value = "v"), "https://example.com/") { results.add(it) }
        val id = parseIdFor("Network.setCookie")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"success":true}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.SUCCESS, results[0].status)
        assertEquals(CookieCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun deleteCookie_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.deleteCookie("k", "d", "/", "https://example.com/") { results.add(it) }
        val id = parseIdFor("Network.deleteCookies")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.SUCCESS, results[0].status)
        assertEquals(CookieCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun clearCookies_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<Unit>>()
        engine.clearCookies() { results.add(it) }
        val id = parseIdFor("Storage.clearCookies")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(CookieActionStatus.SUCCESS, results[0].status)
        assertEquals(CookieCapabilityStatus.SUPPORTED, engine.capability)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsCookieState() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<List<CookieModel>>>()
        engine.getCookies(listOf("https://example.com/")) { results.add(it) }
        val id = parseIdFor("Network.getCookies")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"cookies":[{"name":"a","value":"b","domain":"x","path":"/"}]}}"""
        )
        assertTrue(waitFor { engine.latestCookies != null })

        engine.detach()
        assertNull(engine.session)
        assertNull(engine.latestCookies)
        assertNull(engine.capability)
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<List<CookieModel>>>()
        engine.getCookies(listOf("https://example.com/")) { results.add(it) }
        val id = parseIdFor("Network.getCookies")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"cookies":[{"name":"a","value":"b","domain":"x","path":"/"}]}}"""
        )
        assertTrue(waitFor { engine.latestCookies != null })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.latestCookies)
        assertNull(engine.capability)
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        engine.onConnectionLost()
        assertNull(engine.session)
        assertNull(engine.latestCookies)
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<CookieActionResult<List<CookieModel>>>()
        engine.getCookies(listOf("https://example.com/")) { results.add(it) }
        val id = parseIdFor("Network.getCookies")

        engine.detach()
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"cookies":[{"name":"z","value":"9","domain":"x","path":"/"}]}}"""
        )
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.latestCookies)
        assertNull(engine.session)
    }

    // ============ model-level mapping ============

    @Test
    fun sameSitePolicy_fromWire_mapsKnownValuesAndUnknownToNull() {
        assertEquals(SameSitePolicy.STRICT, SameSitePolicy.fromWire("Strict"))
        assertEquals(SameSitePolicy.LAX, SameSitePolicy.fromWire("Lax"))
        assertEquals(SameSitePolicy.NONE, SameSitePolicy.fromWire("None"))
        assertNull(SameSitePolicy.fromWire("Bogus"))
        assertNull(SameSitePolicy.fromWire(null))
    }

    @Test
    fun cookiePriority_fromWire_mapsKnownValuesAndUnknownToNull() {
        assertEquals(CookiePriority.LOW, CookiePriority.fromWire("Low"))
        assertEquals(CookiePriority.MEDIUM, CookiePriority.fromWire("Medium"))
        assertEquals(CookiePriority.HIGH, CookiePriority.fromWire("High"))
        assertEquals(CookiePriority.NONE, CookiePriority.fromWire("None"))
        assertNull(CookiePriority.fromWire("Bogus"))
        assertNull(CookiePriority.fromWire(null))
    }

    @Test
    fun cookieActionResult_factories_produceExpectedStatuses() {
        val success = CookieActionResult.success(Unit)
        assertEquals(CookieActionStatus.SUCCESS, success.status)
        assertTrue(success.isSuccess)

        val unsupported = CookieActionResult.unsupported<String>("nope", RuntimeException("cause"))
        assertEquals(CookieActionStatus.UNSUPPORTED, unsupported.status)
        assertEquals("nope", unsupported.message)
        assertTrue(unsupported.throwable != null)

        val unavailable = CookieActionResult.unavailable<Int>("not bound")
        assertEquals(CookieActionStatus.UNAVAILABLE, unavailable.status)

        val failed = CookieActionResult.failed<Long>("boom")
        assertEquals(CookieActionStatus.FAILED, failed.status)
        assertTrue(!failed.isSuccess)
    }
}