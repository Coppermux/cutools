package com.coppermux.cutools.ui.audits

import com.coppermux.cutools.core.audits.AuditEngine
import com.coppermux.cutools.core.audits.models.AuditActionResult
import com.coppermux.cutools.core.audits.models.AuditCapabilityStatus
import com.coppermux.cutools.core.audits.models.AuditError
import com.coppermux.cutools.core.audits.models.AuditIssueCode
import com.coppermux.cutools.core.audits.models.AuditIssueModel
import com.coppermux.cutools.core.audits.models.ContrastCheckResultModel
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for the Phase 11B Audits ViewModel.
 *
 * The real [AuditEngine] (Phase 10) is constructed against the fake transport
 * and driven through raw CDP JSON, then the ViewModel's state transitions,
 * command dispatching, real-time issue streaming, search filtering, and error
 * propagation into explicit banners are verified in the same pipeline the app
 * uses against Chrome. No-Fake-Capability is enforced: engine failures surface
 * as explicit `errorMessage` banners with matching status, never synthetic issue
 * fallbacks.
 */
class AuditViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: AuditEngine
    private lateinit var viewModel: AuditViewModel

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = AuditEngine(dispatcher, router)
        viewModel = AuditViewModel(engine)
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

    private fun deliverIssue(code: String, details: String = "{}") {
        val detailsPart = if (details == "{}") "" else "\"details\":$details"
        val body = listOf("\"code\":\"$code\"", detailsPart).filter { it.isNotEmpty() }.joinToString(",")
        transport.deliverMessage(
            """{"method":"Audits.issueAdded","sessionId":"session-1","params":{"issue":{$body}}}"""
        )
    }

    // ============ target attach / detach coordination ============

    @Test
    fun attachTarget_bindsEngineAndPublishesState() {
        viewModel.attachTarget(session)
        assertEquals(session, engine.session)
        assertEquals(session, viewModel.attachedSession)
        assertTrue(viewModel.isAttached)
    }

    @Test
    fun detachTarget_clearsEngineAndResetsState() {
        viewModel.attachTarget(session)
        viewModel.detachTarget()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertFalse(viewModel.isAttached)
        val state = viewModel.state.value
        assertEquals(AuditCapabilityStatus.UNAVAILABLE, state.status)
        assertNull(state.errorMessage)
        assertTrue(state.issues.isEmpty())
    }

    @Test
    fun onConnectionLost_clearsEngineAndResetsState() {
        viewModel.attachTarget(session)
        viewModel.onConnectionLost()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertEquals(AuditCapabilityStatus.UNAVAILABLE, viewModel.state.value.status)
    }

    // ============ state transitions / command dispatching ============

    @Test
    fun enableAudits_success_marksSupportedAndEnabled() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<AuditActionResult<Unit>>()
        viewModel.enableAudits { results.add(it) }
        val id = parseIdFor("Audits.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AuditActionResult.Success)
        val state = viewModel.state.value
        assertEquals(AuditCapabilityStatus.SUPPORTED, state.status)
        assertTrue(state.enabled)
        assertTrue(viewModel.isEnabled)
        assertNull(state.errorMessage)
    }

    @Test
    fun enableAudits_dispatchesEnableWithSession() {
        viewModel.attachTarget(session)
        transport.resetSentMessages()
        viewModel.enableAudits {}
        val raw = transport.sentMessages.last { it.contains("Audits.enable") }
        assertTrue(raw.contains("\"method\":\"Audits.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun disableAudits_success_marksSupportedAndDisabled() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<AuditActionResult<Unit>>()
        viewModel.disableAudits { results.add(it) }
        val id = parseIdFor("Audits.disable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AuditActionResult.Success)
        assertFalse(viewModel.state.value.enabled)
        assertFalse(viewModel.isEnabled)
    }

    @Test
    fun checkContrast_success_recordsGenuineResult() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<AuditActionResult<ContrastCheckResultModel>>()
        viewModel.checkContrast { results.add(it) }
        assertTrue(viewModel.state.value.isLoading)
        val id = parseIdFor("Audits.checkContrast")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"nodeIds":[1,2],"contrastRatio":4.5,"passesAA":true,"passesAAA":false}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AuditActionResult.Success)
        assertTrue(waitFor { !viewModel.state.value.isLoading })
        val result = (results[0] as AuditActionResult.Success).data
        assertEquals(4.5, result.contrastRatio, 0.001)
        assertTrue(result.passesAA)
        assertFalse(result.passesAAA)
        assertEquals(listOf(1, 2), result.nodeIds)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun checkContrast_dispatchesReportSlowLayout() {
        viewModel.attachTarget(session)
        viewModel.checkContrast(reportSlowLayout = true) {}
        val raw = transport.sentMessages.last { it.contains("Audits.checkContrast") }
        assertTrue(raw.contains("\"reportSlowLayout\":true"))
    }

    // ============ real-time issue streaming propagation ============

    @Test
    fun issueStream_propagatesThroughEngineToViewModel() {
        viewModel.attachTarget(session)
        deliverIssue("MixedContentIssue")
        deliverIssue("HeavyAdIssue")
        assertTrue(waitFor { viewModel.engineIssues.value.size == 2 })
        assertEquals(AuditIssueCode.MIXED_CONTENT, viewModel.engineIssues.value[0].code)
        assertEquals(AuditIssueCode.HEAVY_AD, viewModel.engineIssues.value[1].code)
        assertEquals(2, viewModel.filteredIssues.size)

        viewModel.refreshIssues()
        assertEquals(2, viewModel.state.value.issues.size)
    }

    @Test
    fun filteredIssues_emptyQuery_returnsAll() {
        viewModel.attachTarget(session)
        deliverIssue("MixedContentIssue")
        assertTrue(waitFor { viewModel.engineIssues.value.size == 1 })
        assertEquals(1, viewModel.filteredIssues.size)
    }

    @Test
    fun filteredIssues_matchesByCodeAndDetail() {
        viewModel.attachTarget(session)
        deliverIssue("MixedContentIssue", """{"url":"https://insecure"}""")
        deliverIssue("HeavyAdIssue", """{"tag":"ad"}""")
        assertTrue(waitFor { viewModel.engineIssues.value.size == 2 })

        viewModel.updateSearchQuery("mixed")
        assertEquals("mixed", viewModel.state.value.searchQuery)
        assertEquals(1, viewModel.filteredIssues.size)
        assertEquals(AuditIssueCode.MIXED_CONTENT, viewModel.filteredIssues[0].code)

        viewModel.updateSearchQuery("insecure")
        assertEquals(1, viewModel.filteredIssues.size)

        viewModel.updateSearchQuery("zzz")
        assertTrue(viewModel.filteredIssues.isEmpty())
    }

    @Test
    fun filterIssues_blankQueryIsIdentity() {
        val issues = listOf(AuditIssueModel(code = AuditIssueCode.GENERIC_ISSUE, details = mapOf("k" to "v")))
        assertEquals(1, viewModel.filterIssues(issues, "").size)
        assertEquals(1, viewModel.filterIssues(issues, "   ").size)
    }

    // ============ error handling (No-Fake-Capability) ============

    @Test
    fun checkContrast_withoutSession_resolvesUnavailableBanner() {
        val results = CopyOnWriteArrayList<AuditActionResult<ContrastCheckResultModel>>()
        viewModel.checkContrast { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is AuditActionResult.Failure)
        val state = viewModel.state.value
        assertEquals(AuditCapabilityStatus.UNAVAILABLE, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unavailable"))
        assertNull(state.contrastResult)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun enableAudits_methodUnavailable_resolvesUnsupportedBanner() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<AuditActionResult<Unit>>()
        viewModel.enableAudits { results.add(it) }
        val id = parseIdFor("Audits.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertEquals(AuditCapabilityStatus.UNSUPPORTED, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unsupported"))
        assertFalse(state.enabled)
    }

    @Test
    fun checkContrast_protocolFailure_resolvesFailedBanner() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<AuditActionResult<ContrastCheckResultModel>>()
        viewModel.checkContrast { results.add(it) }
        val id = parseIdFor("Audits.checkContrast")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"contrast broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNull(state.contrastResult)
        assertTrue(state.errorMessage.orEmpty().contains("contrast broke"))
    }

    @Test
    fun checkContrast_successAfterError_clearsBanner() {
        viewModel.attachTarget(session)
        viewModel.checkContrast {}
        val failId = parseIdFor("Audits.checkContrast")
        transport.deliverMessage("""{"id":$failId,"sessionId":"session-1","error":{"code":-32000,"message":"broke"}}""")
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })

        viewModel.checkContrast {}
        val okId = parseIdFor("Audits.checkContrast")
        transport.deliverMessage(
            """{"id":$okId,"sessionId":"session-1","result":{"nodeIds":[1],"contrastRatio":7.0,"passesAA":true,"passesAAA":true}}"""
        )
        assertTrue(waitFor { viewModel.state.value.contrastResult != null })
        assertNull(viewModel.state.value.errorMessage)
        assertEquals(AuditCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    // ============ error description helpers ============

    @Test
    fun describeError_mapsEveryErrorVariantToRenderableBanner() {
        assertTrue(viewModel.describeError(AuditError.Unsupported).contains("Unsupported"))
        assertTrue(viewModel.describeError(AuditError.Unavailable).contains("Unavailable"))
        assertTrue(viewModel.describeError(AuditError.TargetDisconnected).contains("disconnected"))
        assertTrue(viewModel.describeError(AuditError.ExecutionFailed("boom")).contains("boom"))
        assertTrue(viewModel.describeError(AuditError.ExecutionFailed("")).contains("execution failed"))
    }
}