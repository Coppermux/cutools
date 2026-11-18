package com.coppermux.cutools.ui.security

import com.coppermux.cutools.core.security.SecurityEngine
import com.coppermux.cutools.core.security.models.CertificateModel
import com.coppermux.cutools.core.security.models.MixedContentType
import com.coppermux.cutools.core.security.models.SecurityActionResult
import com.coppermux.cutools.core.security.models.SecurityCapabilityStatus
import com.coppermux.cutools.core.security.models.SecurityError
import com.coppermux.cutools.core.security.models.SecurityStateModel
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
 * Unit & protocol test suite for the Phase 13 Security ViewModel.
 *
 * The real [SecurityEngine] (Phase 8A / Phase 12) is constructed against the
 * fake transport and driven through raw CDP JSON, then the ViewModel's state
 * transitions, command dispatch, real-time security state streaming
 * propagation, certificate fetch, and error propagation into explicit banners
 * are verified in the same pipeline the app uses against Chrome. No-Fake-
 * Capability is enforced: engine failures surface as explicit `errorMessage`
 * banners with matching status, never synthetic TLS / certificate state.
 */
class SecurityViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: SecurityEngine
    private lateinit var viewModel: SecurityViewModel

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = SecurityEngine(dispatcher, router)
        viewModel = SecurityViewModel(engine)
    }

    private fun bind() {
        viewModel.attachTarget(session)
        val id = parseIdFor("Security.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.enabled })
    }

    private fun parseIdFor(method: String): Long {
        val id = Regex("\"id\":(\\d+)").find(transport.sentMessages.last { it.contains(method) })!!.groupValues[1]
        return id.toLong()
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun deliverSecurityState(state: String, explanations: String = "[]") {
        transport.deliverMessage(
            """{"method":"Security.securityStateChanged","sessionId":"session-1","params":{"securityState":"$state","schemeIsCryptographic":true,"explanations":$explanations}}"""
        )
    }

    // ============ target lifecycle ============

    @Test
    fun attachTarget_bindsEngineAndPublishesState() {
        viewModel.attachTarget(session)
        assertEquals(session, engine.session)
        assertEquals(session, viewModel.attachedSession)
        assertTrue(viewModel.isAttached)
        assertEquals(SecurityCapabilityStatus.UNAVAILABLE, viewModel.state.value.status)
    }

    @Test
    fun detachTarget_clearsEngineAndResetsState() {
        bind()
        viewModel.detachTarget()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertFalse(viewModel.isAttached)
        val state = viewModel.state.value
        assertEquals(SecurityCapabilityStatus.UNAVAILABLE, state.status)
        assertNull(state.errorMessage)
        assertNull(state.securityState)
        assertTrue(state.explanations.isEmpty())
    }

    @Test
    fun onConnectionLost_clearsEngineAndResetsState() {
        bind()
        viewModel.onConnectionLost()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertEquals(SecurityCapabilityStatus.UNAVAILABLE, viewModel.state.value.status)
        assertNull(viewModel.state.value.securityState)
    }

    // ============ domain / command dispatch ============

    @Test
    fun enableSecurity_success_marksSupportedAndEnabled() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        viewModel.enableSecurity { results.add(it) }
        val id = parseIdFor("Security.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is SecurityActionResult.Success)
        assertTrue(viewModel.state.value.enabled)
        assertTrue(viewModel.isEnabled)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun disableSecurity_success_marksSupportedAndDisabled() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        viewModel.disableSecurity { results.add(it) }
        val id = parseIdFor("Security.disable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is SecurityActionResult.Success)
        assertFalse(viewModel.state.value.enabled)
        assertFalse(viewModel.isEnabled)
    }

    @Test
    fun fetchCertificate_success_recordsCertificateAndOrigin() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<CertificateModel>>()
        viewModel.fetchCertificate("https://example.com") { results.add(it) }
        val id = parseIdFor("Security.getCertificate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"subjectName":"example.com","issuer":"CA","validFrom":1700000000,"validTo":1800000000,"certificateChain":["PEM"]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is SecurityActionResult.Success)
        assertTrue(waitFor { viewModel.state.value.certificate != null })
        val certificate = viewModel.state.value.certificate!!
        assertEquals("example.com", certificate.subjectName)
        assertEquals(1, certificate.certificateChain.size)
        assertNull(viewModel.state.value.errorMessage)
        assertEquals("https://example.com", viewModel.state.value.origin)
        assertEquals(SecurityCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    @Test
    fun fetchCertificate_dispatchesOriginAndSession() {
        bind()
        viewModel.fetchCertificate("https://example.com") {}
        val raw = transport.sentMessages.last { it.contains("Security.getCertificate") }
        assertTrue(raw.contains("\"method\":\"Security.getCertificate\""))
        assertTrue(raw.contains("\"origin\":\"https://example.com\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun updateOrigin_recordsOriginInState() {
        viewModel.updateOrigin("https://a.example")
        assertEquals("https://a.example", viewModel.state.value.origin)
    }

    // ============ real-time security state streaming ============

    @Test
    fun securityStateStream_propagatesThroughEngineToViewModel() {
        bind()
        deliverSecurityState("secure")
        assertTrue(waitFor { viewModel.securityStateStream.value == SecurityStateModel.SECURE })
        assertEquals(SecurityStateModel.SECURE, viewModel.securityStateStream.value)

        deliverSecurityState("insecure")
        assertTrue(waitFor { viewModel.securityStateStream.value == SecurityStateModel.INSECURE })
    }

    @Test
    fun refreshSecurityState_mirrorsGenuineEngineSnapshot() {
        bind()
        transport.deliverMessage(
            """{"method":"Security.securityStateChanged","sessionId":"session-1","params":{"securityState":"insecure","schemeIsCryptographic":false,"explanations":[{"securityState":"insecure","title":"Mixed Content","summary":"Mixed content present.","mixedContentType":"blockable","recommendations":["Load over HTTPS"]}]}}"""
        )
        assertTrue(waitFor { engine.explanations.size == 1 })

        viewModel.refreshSecurityState()
        val state = viewModel.state.value
        assertEquals(SecurityStateModel.INSECURE, state.securityState)
        assertFalse(state.schemeIsCryptographic)
        assertEquals(1, state.explanations.size)
        assertEquals("Mixed Content", state.explanations[0].title)
        assertEquals(MixedContentType.BLOCKABLE, state.explanations[0].mixedContentType)
        assertEquals("Load over HTTPS", state.explanations[0].recommendations[0])
    }

    // ============ error handling (No-Fake-Capability) ============

    @Test
    fun enableSecurity_withoutSession_resolvesUnavailableBanner() {
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        viewModel.enableSecurity { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is SecurityActionResult.Failure)
        val state = viewModel.state.value
        assertEquals(SecurityCapabilityStatus.UNAVAILABLE, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unavailable"))
        assertFalse(state.enabled)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun fetchCertificate_methodUnavailable_resolvesUnsupportedBanner() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<CertificateModel>>()
        viewModel.fetchCertificate("https://example.com") { results.add(it) }
        val id = parseIdFor("Security.getCertificate")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertEquals(SecurityCapabilityStatus.UNSUPPORTED, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unsupported"))
        assertNull(state.certificate)
    }

    @Test
    fun fetchCertificate_protocolFailure_resolvesFailedBanner() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<CertificateModel>>()
        viewModel.fetchCertificate("https://example.com") { results.add(it) }
        val id = parseIdFor("Security.getCertificate")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"getCertificate broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNull(state.certificate)
        assertTrue(state.errorMessage.orEmpty().contains("getCertificate broke"))
    }

    @Test
    fun fetchCertificate_successAfterError_clearsBanner() {
        bind()
        viewModel.fetchCertificate("https://example.com") {}
        val failId = parseIdFor("Security.getCertificate")
        transport.deliverMessage("""{"id":$failId,"sessionId":"session-1","error":{"code":-32000,"message":"broke"}}""")
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })

        viewModel.fetchCertificate("https://example.com") {}
        val okId = parseIdFor("Security.getCertificate")
        transport.deliverMessage(
            """{"id":$okId,"sessionId":"session-1","result":{"subjectName":"example.com","issuer":"CA","validFrom":1700000000,"validTo":1800000000,"certificateChain":["PEM"]}}"""
        )
        assertTrue(waitFor { viewModel.state.value.certificate != null })
        assertNull(viewModel.state.value.errorMessage)
        assertEquals(SecurityCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    // ============ error description helpers ============

    @Test
    fun describeError_mapsEveryErrorVariantToRenderableBanner() {
        assertTrue(viewModel.describeError(SecurityError.Unsupported("nope")).contains("Unsupported"))
        assertTrue(viewModel.describeError(SecurityError.Unavailable("no session")).contains("Unavailable"))
        assertTrue(viewModel.describeError(SecurityError.TargetDisconnected()).contains("disconnected"))
        assertTrue(viewModel.describeError(SecurityError.ExecutionFailed(RuntimeException("boom"))).contains("boom"))
        assertTrue(viewModel.describeError(SecurityError.ExecutionFailed(RuntimeException(""))).contains("execution failed"))
    }
}