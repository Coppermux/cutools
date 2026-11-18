package com.coppermux.cutools.core.audits

import com.coppermux.cutools.core.audits.models.AuditActionResult
import com.coppermux.cutools.core.audits.models.AuditCapabilityStatus
import com.coppermux.cutools.core.audits.models.AuditError
import com.coppermux.cutools.core.audits.models.AuditIssueCode
import com.coppermux.cutools.core.audits.models.ContrastCheckResultModel
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Phase 10 Audits Engine.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → router → dispatcher → engine, proving
 * `Audits.enable/disable/checkContrast` serialization, genuine
 * `Audits.issueAdded` event routing into the real-time `issues` StateFlow, and
 * strict issue / contrast-result parsing in the same pipeline the app uses
 * against Chrome. No-Fake-Capability is enforced: protocol failures, missing
 * sessions, unavailable methods, and connection loss map to explicit
 * UNSUPPORTED / UNAVAILABLE / FAILED / TARGET_DISCONNECTED outcomes, never
 * fabricated audit state.
 */
class AuditEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: AuditEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = AuditEngine(dispatcher, router)
    }

    private fun bind() {
        engine.bindSession(session)
        val id = parseId("Audits.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.enabled })
    }

    private fun parseId(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun deliverIssue(method: String, code: String?, extra: String = "{}") {
        val codePart = if (code == null) "" else "\"code\":\"$code\""
        val extraPart = if (extra == "{}") "" else "\"details\":$extra"
        val body = listOf(codePart, extraPart).filter { it.isNotEmpty() }.joinToString(",")
        transport.deliverMessage(
            """{"method":"$method","sessionId":"session-1","params":{"issue":{$body}}}"""
        )
    }

    // ============ payload generation ============

    @Test
    fun bind_sendsAuditsEnableOnSession() {
        engine.bindSession(session)
        val raw = transport.sentMessages.last { it.contains("Audits.enable") }
        assertTrue(raw.contains("\"method\":\"Audits.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun disable_serializesDisableAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<AuditActionResult<Unit>>()
        engine.disable { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Audits.disable") }
        assertTrue(raw.contains("\"method\":\"Audits.disable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun checkContrast_true_serializesReportSlowLayoutAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<AuditActionResult<*>>()
        engine.checkContrast(true) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Audits.checkContrast") }
        assertTrue(raw.contains("\"method\":\"Audits.checkContrast\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"reportSlowLayout\":true"))
    }

    @Test
    fun checkContrast_false_serializesReportSlowLayoutFalse() {
        bind()
        val results = CopyOnWriteArrayList<AuditActionResult<*>>()
        engine.checkContrast(false) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Audits.checkContrast") }
        assertTrue(raw.contains("\"reportSlowLayout\":false"))
    }

    // ============ event parsing & StateFlow updates ============

    @Test
    fun issueAdded_parsesIssueIntoStateFlow() {
        bind()
        deliverIssue("Audits.issueAdded", "MixedContentIssue", """{"url":"https://x/","severity":"WARNING"}""")
        assertTrue(waitFor { engine.issues.value.size == 1 })
        val issue = engine.issues.value[0]
        assertEquals(AuditIssueCode.MIXED_CONTENT, issue.code)
        assertEquals("https://x/", issue.details["url"])
        assertEquals("WARNING", issue.details["severity"])
        assertTrue(issue.timestamp > 0)
    }

    @Test
    fun issueAdded_unknownCode_mapsToGenericIssue() {
        bind()
        deliverIssue("Audits.issueAdded", "SomeFutureIssue", """{"url":"https://y/"}""")
        assertTrue(waitFor { engine.issues.value.size == 1 })
        assertEquals(AuditIssueCode.GENERIC_ISSUE, engine.issues.value[0].code)
    }

    @Test
    fun issueAdded_missingCode_isTolerated() {
        bind()
        deliverIssue("Audits.issueAdded", null)
        Thread.sleep(150)
        assertTrue(engine.issues.value.isEmpty())
    }

    @Test
    fun issueAdded_malformedParams_isTolerated() {
        bind()
        transport.deliverMessage("""{"method":"Audits.issueAdded","sessionId":"session-1","params":"not-a-map"}""")
        Thread.sleep(150)
        assertTrue(engine.issues.value.isEmpty())
    }

    @Test
    fun issueAdded_forOtherSession_isIgnored() {
        bind()
        transport.deliverMessage(
            """{"method":"Audits.issueAdded","sessionId":"other-session","params":{"issue":{"code":"MixedContentIssue","details":{}}}}"""
        )
        Thread.sleep(200)
        assertTrue(engine.issues.value.isEmpty())
    }

    @Test
    fun issueAdded_multipleIssuesAccumulateInOrder() {
        bind()
        deliverIssue("Audits.issueAdded", "HeavyAdIssue", """{"url":"https://a/"}""")
        deliverIssue("Audits.issueAdded", "ContentSecurityPolicyViolation", """{"url":"https://b/"}""")
        assertTrue(waitFor { engine.issues.value.size == 2 })
        assertEquals(AuditIssueCode.HEAVY_AD, engine.issues.value[0].code)
        assertEquals(AuditIssueCode.CONTENT_SECURITY_POLICY, engine.issues.value[1].code)
    }

    // ============ contrast result parsing ============

    @Test
    fun checkContrast_success_parsesContrastResult() {
        bind()
        val results = CopyOnWriteArrayList<AuditActionResult<*>>()
        engine.checkContrast(true) { results.add(it) }
        val id = parseId("Audits.checkContrast")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"nodeIds":[1,2],"contrastRatio":4.5,"passesAA":false,"passesAAA":false}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AuditActionResult.Success<*>)
        val data = (results[0] as AuditActionResult.Success<*>).data as ContrastCheckResultModel
        assertEquals(listOf(1, 2), data.nodeIds)
        assertEquals(4.5, data.contrastRatio, 0.0)
        assertTrue(!data.passesAA)
        assertTrue(!data.passesAAA)
        assertEquals(AuditCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun checkContrast_success_singletonNodeId_wraps() {
        bind()
        val results = CopyOnWriteArrayList<AuditActionResult<*>>()
        engine.checkContrast(true) { results.add(it) }
        val id = parseId("Audits.checkContrast")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"nodeId":7,"contrastRatio":7.0,"passesAA":true,"passesAAA":false}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val data = (results[0] as AuditActionResult.Success<*>).data as ContrastCheckResultModel
        assertEquals(listOf(7), data.nodeIds)
        assertEquals(7.0, data.contrastRatio, 0.0)
        assertTrue(data.passesAA)
    }

    @Test
    fun checkContrast_nonObjectResult_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<AuditActionResult<*>>()
        engine.checkContrast(true) { results.add(it) }
        val id = parseId("Audits.checkContrast")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":42}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as AuditActionResult.Failure
        assertTrue(failure.error is AuditError.ExecutionFailed)
        assertEquals(AuditCapabilityStatus.FAILED, engine.capability)
    }

    // ============ error mapping ============

    @Test
    fun enable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<AuditActionResult<Unit>>()
        engine.enable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is AuditActionResult.Failure)
        assertTrue((results[0] as AuditActionResult.Failure).error === AuditError.Unavailable)
        assertEquals(AuditCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun disable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<AuditActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as AuditActionResult.Failure).error === AuditError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun checkContrast_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<AuditActionResult<*>>()
        engine.checkContrast(true) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as AuditActionResult.Failure).error === AuditError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun enable_methodUnavailable_mapsToUnsupported() {
        engine.bindSession(session)
        val id = parseId("Audits.enable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { engine.capability == AuditCapabilityStatus.UNSUPPORTED })
        assertEquals(AuditCapabilityStatus.UNSUPPORTED, engine.capability)
        assertTrue(!engine.enabled)
    }

    @Test
    fun checkContrast_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<AuditActionResult<*>>()
        engine.checkContrast(true) { results.add(it) }
        val id = parseId("Audits.checkContrast")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"contrast broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as AuditActionResult.Failure
        assertTrue(failure.error is AuditError.ExecutionFailed)
        assertTrue((failure.error as AuditError.ExecutionFailed).message.contains("contrast broke"))
        assertEquals(AuditCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun disable_afterConnectionLost_mapsToTargetDisconnected() {
        bind()
        transport.disconnect()
        val results = CopyOnWriteArrayList<AuditActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as AuditActionResult.Failure).error === AuditError.TargetDisconnected)
    }

    // ============ domain success ============

    @Test
    fun enable_success_setsEnabledAndCapability() {
        engine.bindSession(session)
        val results = CopyOnWriteArrayList<AuditActionResult<Unit>>()
        engine.enable { results.add(it) }
        val id = parseId("Audits.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AuditActionResult.Success)
        assertEquals(AuditCapabilityStatus.SUPPORTED, engine.capability)
        assertTrue(engine.enabled)
    }

    @Test
    fun disable_success_disablesDomain() {
        bind()
        val results = CopyOnWriteArrayList<AuditActionResult<Unit>>()
        engine.disable { results.add(it) }
        val id = parseId("Audits.disable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AuditActionResult.Success)
        assertTrue(waitFor { !engine.enabled })
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsStateAndUnsubscribes() {
        bind()
        deliverIssue("Audits.issueAdded", "MixedContentIssue")
        assertTrue(waitFor { engine.issues.value.size == 1 })
        assertEquals("one listener", 1, router.subscriptionCount("Audits.issueAdded"))

        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
        assertTrue(engine.issues.value.isEmpty())
        assertEquals("no event-listener leak", 0, router.subscriptionCount("Audits.issueAdded"))
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        deliverIssue("Audits.issueAdded", "HeavyAdIssue")
        assertTrue(waitFor { engine.issues.value.size == 1 })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
        assertTrue(engine.issues.value.isEmpty())
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        deliverIssue("Audits.issueAdded", "HeavyAdIssue")
        assertTrue(waitFor { engine.issues.value.size == 1 })
        engine.onConnectionLost()
        assertNull(engine.session)
        assertTrue(engine.issues.value.isEmpty())
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<AuditActionResult<*>>()
        engine.checkContrast(true) { results.add(it) }
        val id = parseId("Audits.checkContrast")

        engine.detach()
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"nodeIds":[],"contrastRatio":1.0,"passesAA":false,"passesAAA":false}}"""
        )
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
    }

    @Test
    fun lateIssueAfterDetach_isIgnored() {
        bind()
        engine.detach()
        transport.deliverMessage(
            """{"method":"Audits.issueAdded","sessionId":"session-1","params":{"issue":{"code":"MixedContentIssue","details":{}}}}"""
        )
        Thread.sleep(200)
        assertTrue(engine.issues.value.isEmpty())
    }

    @Test
    fun rebind_doesNotAccumulateSubscriptions() {
        engine.bindSession(session)
        val id1 = parseId("Audits.enable")
        transport.deliverMessage("""{"id":$id1,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.enabled })

        val reconnected = SessionInfo("session-3", "target-3", "ws://test")
        engine.bindSession(reconnected)
        val id2 = parseId("Audits.enable")
        transport.deliverMessage("""{"id":$id2,"sessionId":"session-3","result":{}}""")
        assertTrue(waitFor { engine.enabled && engine.session == reconnected })

        assertEquals("rebinding must not leak extra listeners", 1, router.subscriptionCount("Audits.issueAdded"))
    }

    @Test
    fun shutdown_releasesSubscriptions() {
        engine.bindSession(session)
        assertEquals(1, router.subscriptionCount("Audits.issueAdded"))
        engine.shutdown()
        assertEquals(0, router.subscriptionCount("Audits.issueAdded"))
    }

    // ============ model-level mapping ============

    @Test
    fun auditIssueCode_fromWire_mapsKnownCodes() {
        assertEquals(AuditIssueCode.SAME_SITE_COOKIE, AuditIssueCode.fromWire("SameSiteCookieIssue"))
        assertEquals(AuditIssueCode.MIXED_CONTENT, AuditIssueCode.fromWire("MixedContentIssue"))
        assertEquals(AuditIssueCode.BLOCKED_BY_RESPONSE, AuditIssueCode.fromWire("BlockedByResponseIssue"))
        assertEquals(AuditIssueCode.HEAVY_AD, AuditIssueCode.fromWire("HeavyAdIssue"))
        assertEquals(AuditIssueCode.CONTENT_SECURITY_POLICY, AuditIssueCode.fromWire("ContentSecurityPolicyViolation"))
        assertEquals(AuditIssueCode.SHARED_ARRAY_BUFFER, AuditIssueCode.fromWire("SharedArrayBufferIssue"))
        assertEquals(AuditIssueCode.ATTRIBUTION_REPORTING, AuditIssueCode.fromWire("AttributionReportingIssue"))
        assertEquals(AuditIssueCode.GENERIC_ISSUE, AuditIssueCode.fromWire("SomethingElseIssue"))
        assertEquals(AuditIssueCode.UNKNOWN, AuditIssueCode.fromWire(null))
    }

    @Test
    fun auditActionResult_and_error_factories_produceExpectedValues() {
        val success = AuditActionResult.Success(42)
        assertTrue((success as AuditActionResult.Success<Int>).data == 42)

        val failure = AuditActionResult.Failure(AuditError.Unsupported)
        assertTrue(failure.error === AuditError.Unsupported)

        val exec = AuditError.ExecutionFailed("boom")
        assertEquals("boom", exec.message)
        assertTrue(AuditError.Unavailable === AuditError.Unavailable)
        assertTrue(AuditError.TargetDisconnected === AuditError.TargetDisconnected)
    }

    @Test
    fun parser_parseIssue_missingFields_parseSafely() {
        val issue = AuditParser.parseIssue(mapOf<String, Any?>("code" to "HeavyAdIssue"))
        assertNotNull(issue)
        assertEquals(AuditIssueCode.HEAVY_AD, issue!!.code)
        assertTrue(issue!!.details.isEmpty())

        assertNull(AuditParser.parseIssue(mapOf<String, Any?>()))
        assertNull(AuditParser.parseIssue("not-a-map"))
        assertNull(AuditParser.parseIssue(null))
    }

    @Test
    fun parser_parseContrastResult_safe() {
        val result = AuditParser.parseContrastResult(
            mapOf<String, Any?>("nodeIds" to listOf(1, 2), "contrastRatio" to 4.5, "passesAA" to true, "passesAAA" to false)
        )
        assertNotNull(result)
        assertEquals(listOf(1, 2), result!!.nodeIds)
        assertEquals(4.5, result!!.contrastRatio, 0.0)
        assertTrue(result!!.passesAA)
        assertTrue(!result!!.passesAAA)

        assertTrue(AuditParser.parseContrastResult(mapOf<String, Any?>())!!.nodeIds.isEmpty())
        assertNull(AuditParser.parseContrastResult("not-a-map"))
        assertNull(AuditParser.parseContrastResult(null))
    }
}