package com.coppermux.cutools.core.security

import com.coppermux.cutools.core.security.models.CertificateDetailsModel
import com.coppermux.cutools.core.security.models.CertificateErrorModel
import com.coppermux.cutools.core.security.models.CertificateModel
import com.coppermux.cutools.core.security.models.MixedContentType
import com.coppermux.cutools.core.security.models.SecurityActionResult
import com.coppermux.cutools.core.security.models.SecurityCapabilityStatus
import com.coppermux.cutools.core.security.models.SecurityError
import com.coppermux.cutools.core.security.models.SecurityExplanationModel
import com.coppermux.cutools.core.security.models.SecurityStateModel
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
 * Unit & protocol test suite for Sub-Phase 8A Security Domain Engine.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → router → dispatcher → engine, proving
 * `Security.enable/disable/setIgnoreCertificateErrors` serialization, genuine
 * `Security.securityStateChanged` / `Security.certificateError` event routing,
 * and strict security-state / explanation / certificate parsing in the same
 * pipeline the app uses against Chrome. No-Fake-Capability is enforced:
 * protocol failures, missing sessions, unavailable methods, and connection loss
 * map to explicit UNSUPPORTED / UNAVAILABLE / FAILED / TARGET_DISCONNECTED
 * outcomes, never fabricated TLS or certificate state.
 */
class SecurityEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: SecurityEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = SecurityEngine(dispatcher, router)
    }

    private fun bind() {
        engine.bindSession(session)
        val id = parseIdFor("Security.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.enabled })
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

    private fun deliverEvent(method: String, paramsJson: String) {
        transport.deliverMessage("""{"method":"$method","sessionId":"session-1","params":$paramsJson}""")
    }

    // ============ payload generation ============

    @Test
    fun bind_sendsSecurityEnableOnSession() {
        engine.bindSession(session)
        val raw = transport.sentMessages.last { it.contains("Security.enable") }
        assertTrue(raw.contains("\"method\":\"Security.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun disable_serializesDisableAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.disable { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Security.disable") }
        assertTrue(raw.contains("\"method\":\"Security.disable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun setIgnoreCertificateErrors_true_serializesIgnoreAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.setIgnoreCertificateErrors(true) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Security.setIgnoreCertificateErrors") }
        assertTrue(raw.contains("\"method\":\"Security.setIgnoreCertificateErrors\""))
        assertTrue(raw.contains("\"ignore\":true"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun setIgnoreCertificateErrors_false_serializesIgnoreFalse() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.setIgnoreCertificateErrors(false) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Security.setIgnoreCertificateErrors") }
        assertTrue(raw.contains("\"ignore\":false"))
    }

    // ============ event parsing & state ingestion ============

    @Test
    fun securityStateChanged_ingestsStateAndSummary() {
        bind()
        deliverEvent(
            "Security.securityStateChanged",
            """{"securityState":"secure","schemeIsCryptographic":true,"explanations":[],"summary":"Connection is secure"}"""
        )
        assertTrue(waitFor { engine.securityState == SecurityStateModel.SECURE })
        assertEquals(SecurityStateModel.SECURE, engine.securityState)
        assertTrue(engine.schemeIsCryptographic)
        assertEquals("Connection is secure", engine.summary)
        assertTrue(engine.explanations.isEmpty())
    }

    @Test
    fun securityStateChanged_parsesExplanationsAndCertificateChain() {
        bind()
        deliverEvent(
            "Security.securityStateChanged",
            """{"securityState":"insecure","schemeIsCryptographic":true,"explanations":[
                {"securityState":"secure","title":"Certificate Valid","summary":"The server's certificate is valid.","description":"certificate is valid","hasCertificate":true,"mixedContentType":"none","certificate":["-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----"],"recommendations":[]},
                {"securityState":"insecure","title":"Mixed Content","summary":"Mixed content present.","description":"page includes mixed content","hasCertificate":false,"mixedContentType":"blockable","certificate":[],"recommendations":["Load over HTTPS"]}
            ]}"""
        )
        assertTrue(waitFor { engine.explanations.size == 2 })
        val first = engine.explanations[0]
        assertEquals(SecurityStateModel.SECURE, first.securityState)
        assertEquals("Certificate Valid", first.title)
        assertEquals("The server's certificate is valid.", first.summary)
        assertEquals("certificate is valid", first.description)
        assertTrue(first.hasCertificate)
        assertEquals(MixedContentType.NONE, first.mixedContentType)
        assertEquals(1, first.certificate.size)
        assertTrue(first.certificate[0].contains("BEGIN CERTIFICATE"))

        val second = engine.explanations[1]
        assertEquals(SecurityStateModel.INSECURE, second.securityState)
        assertEquals(MixedContentType.BLOCKABLE, second.mixedContentType)
        assertTrue(!second.hasCertificate)
        assertTrue(second.certificate.isEmpty())
        assertEquals("Load over HTTPS", second.recommendations[0])
    }

    @Test
    fun securityStateChanged_parsesCertificateDetails() {
        bind()
        deliverEvent(
            "Security.securityStateChanged",
            """{"securityState":"secure","schemeIsCryptographic":true,"explanations":[],"certificateSecurityState":{
                "subjectName":"example.com","issuer":"CA Issuer","validFrom":1700000000.0,"validTo":1800000000.0,
                "protocol":"TLS 1.3","cipher":"AES_128_GCM","keyExchange":"ECDHE_RSA","keyExchangeGroup":"P-256",
                "certificate":["-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----"]
            }}"""
        )
        assertTrue(waitFor { engine.certificateDetails != null })
        val cert = engine.certificateDetails!!
        assertEquals("example.com", cert.subjectName)
        assertEquals("CA Issuer", cert.issuer)
        assertEquals(1700000000.0, cert.validFrom, 0.0)
        assertEquals(1800000000.0, cert.validTo, 0.0)
        assertEquals("TLS 1.3", cert.protocol)
        assertEquals("AES_128_GCM", cert.cipher)
        assertEquals("ECDHE_RSA", cert.keyExchange)
        assertEquals("P-256", cert.keyExchangeGroup)
        assertEquals(1, cert.certificate.size)
    }

    @Test
    fun securityStateChanged_unknownSecurityState_mapsToNull() {
        bind()
        deliverEvent(
            "Security.securityStateChanged",
            """{"securityState":"mystery","schemeIsCryptographic":false,"explanations":[]}"""
        )
        Thread.sleep(200)
        assertNull(engine.securityState)
        assertTrue(engine.explanations.isEmpty())
    }

    @Test
    fun securityStateChanged_malformedParams_isTolerated() {
        bind()
        transport.deliverMessage("""{"method":"Security.securityStateChanged","sessionId":"session-1","params":"not-a-map"}""")
        Thread.sleep(150)
        assertNull(engine.securityState)
        assertTrue(engine.explanations.isEmpty())
        assertNull(engine.certificateDetails)
    }

    @Test
    fun securityStateChanged_forOtherSession_isIgnored() {
        bind()
        transport.deliverMessage(
            """{"method":"Security.securityStateChanged","sessionId":"other-session","params":{"securityState":"secure","schemeIsCryptographic":true,"explanations":[]}}"""
        )
        Thread.sleep(200)
        assertNull(engine.securityState)
    }

    @Test
    fun certificateError_appendsErrorLog() {
        bind()
        deliverEvent(
            "Security.certificateError",
            """{"eventId":7,"errorType":"authorityInvalid","requestURL":"https://bad.example/"}"""
        )
        assertTrue(waitFor { engine.certificateErrors.size == 1 })
        val error = engine.certificateErrors[0]
        assertEquals(7L, error.eventId)
        assertEquals("authorityInvalid", error.errorType)
        assertEquals("https://bad.example/", error.requestURL)
        assertTrue(error.reportedAt > 0)
    }

    @Test
    fun certificateError_malformed_isTolerated() {
        bind()
        deliverEvent("Security.certificateError", """{"eventId":1}""")
        deliverEvent("Security.certificateError", "{}")
        Thread.sleep(200)
        assertTrue(engine.certificateErrors.isEmpty())
    }

    @Test
    fun certificateError_forOtherSession_isIgnored() {
        bind()
        transport.deliverMessage(
            """{"method":"Security.certificateError","sessionId":"other-session","params":{"eventId":3,"errorType":"x","requestURL":"https://a.example/"}}"""
        )
        Thread.sleep(200)
        assertTrue(engine.certificateErrors.isEmpty())
    }

    // ============ error mapping ============

    @Test
    fun enable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.enable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is SecurityActionResult.Failure)
        assertTrue((results[0] as SecurityActionResult.Failure).error is SecurityError.Unavailable)
        assertEquals(SecurityCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun disable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as SecurityActionResult.Failure).error is SecurityError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun setIgnoreCertificateErrors_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.setIgnoreCertificateErrors(true) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as SecurityActionResult.Failure).error is SecurityError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun enable_methodUnavailable_mapsToUnsupported() {
        engine.bindSession(session)
        val id = parseIdFor("Security.enable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { engine.capability == SecurityCapabilityStatus.UNSUPPORTED })
        assertEquals(SecurityCapabilityStatus.UNSUPPORTED, engine.capability)
        assertTrue(!engine.enabled)
    }

    @Test
    fun disable_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.disable { results.add(it) }
        val id = parseIdFor("Security.disable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"disable broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as SecurityActionResult.Failure
        assertTrue(failure.error is SecurityError.ExecutionFailed)
        assertTrue((failure.error as SecurityError.ExecutionFailed).cause.message.orEmpty().contains("disable broke"))
        assertEquals(SecurityCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun setIgnoreCertificateErrors_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.setIgnoreCertificateErrors(true) { results.add(it) }
        val id = parseIdFor("Security.setIgnoreCertificateErrors")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"nope"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as SecurityActionResult.Failure).error is SecurityError.ExecutionFailed)
        assertEquals(SecurityCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun disable_afterConnectionLost_mapsToTargetDisconnected() {
        bind()
        transport.disconnect()
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as SecurityActionResult.Failure).error is SecurityError.TargetDisconnected)
    }

    // ============ mutation / domain success ============

    @Test
    fun enable_success_setsEnabledAndCapability() {
        engine.bindSession(session)
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.enable { results.add(it) }
        val id = parseIdFor("Security.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is SecurityActionResult.Success)
        assertEquals(SecurityCapabilityStatus.SUPPORTED, engine.capability)
        assertTrue(engine.enabled)
    }

    @Test
    fun disable_success_disablesDomain() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.disable { results.add(it) }
        val id = parseIdFor("Security.disable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is SecurityActionResult.Success)
        assertTrue(waitFor { !engine.enabled })
    }

    @Test
    fun setIgnoreCertificateErrors_success_returnsSuccessAndCapability() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.setIgnoreCertificateErrors(true) { results.add(it) }
        val id = parseIdFor("Security.setIgnoreCertificateErrors")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is SecurityActionResult.Success)
        assertEquals(SecurityCapabilityStatus.SUPPORTED, engine.capability)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsStateAndUnsubscribes() {
        bind()
        deliverEvent(
            "Security.securityStateChanged",
            """{"securityState":"secure","schemeIsCryptographic":true,"explanations":[{"title":"t","summary":"s","description":"d"}]}"""
        )
        deliverEvent("Security.certificateError", """{"eventId":2,"errorType":"expired","requestURL":"https://x.example/"}""")
        assertTrue(waitFor { engine.certificateErrors.size == 1 })
        for (method in listOf("Security.securityStateChanged", "Security.certificateError")) {
            assertEquals("one listener per method", 1, router.subscriptionCount(method))
        }

        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
        assertNull(engine.securityState)
        assertNull(engine.certificateDetails)
        assertTrue(engine.explanations.isEmpty())
        assertTrue(engine.certificateErrors.isEmpty())
        for (method in listOf("Security.securityStateChanged", "Security.certificateError")) {
            assertEquals("no event-listener leak for $method", 0, router.subscriptionCount(method))
        }
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        deliverEvent(
            "Security.securityStateChanged",
            """{"securityState":"secure","schemeIsCryptographic":true,"explanations":[{"title":"T"}]}"""
        )
        deliverEvent("Security.certificateError", """{"eventId":1,"errorType":"x","requestUrl":"https://x.example/"}""")
        assertTrue(waitFor { engine.certificateErrors.size == 1 })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
        assertNull(engine.securityState)
        assertNull(engine.certificateDetails)
        assertTrue(engine.explanations.isEmpty())
        assertTrue(engine.certificateErrors.isEmpty())
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        deliverEvent(
            "Security.securityStateChanged",
            """{"securityState":"insecure","schemeIsCryptographic":false,"explanations":[]}"""
        )
        assertTrue(waitFor { engine.securityState == SecurityStateModel.INSECURE })
        engine.onConnectionLost()
        assertNull(engine.session)
        assertNull(engine.securityState)
        assertTrue(engine.explanations.isEmpty())
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        engine.setIgnoreCertificateErrors(true) { results.add(it) }
        val id = parseIdFor("Security.setIgnoreCertificateErrors")

        engine.detach()
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
    }

    @Test
    fun lateEventAfterDetach_isIgnored() {
        bind()
        engine.detach()
        deliverEvent(
            "Security.securityStateChanged",
            """{"securityState":"secure","schemeIsCryptographic":true,"explanations":[]}"""
        )
        Thread.sleep(200)
        assertNull(engine.securityState)
        assertEquals(0, engine.certificateErrors.size)
    }

    @Test
    fun rebind_doesNotAccumulateSubscriptions() {
        engine.bindSession(session)
        val id1 = parseIdFor("Security.enable")
        transport.deliverMessage("""{"id":$id1,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.enabled })

        val reconnected = SessionInfo("session-3", "target-3", "ws://test")
        engine.bindSession(reconnected)
        val id2 = parseIdFor("Security.enable")
        transport.deliverMessage("""{"id":$id2,"sessionId":"session-3","result":{}}""")
        assertTrue(waitFor { engine.enabled && engine.session == reconnected })

        assertEquals(
            "rebinding must not leak extra listeners",
            1,
            router.subscriptionCount("Security.securityStateChanged"),
        )
    }

    @Test
    fun shutdown_releasesSubscriptions() {
        engine.bindSession(session)
        assertEquals(1, router.subscriptionCount("Security.securityStateChanged"))
        engine.shutdown()
        assertEquals(0, router.subscriptionCount("Security.securityStateChanged"))
        assertEquals(0, router.subscriptionCount("Security.certificateError"))
    }

    // ============ model-level mapping ============

    @Test
    fun securityStateModel_fromWire_mapsAllValues() {
        assertEquals(SecurityStateModel.UNKNOWN, SecurityStateModel.fromWire("unknown"))
        assertEquals(SecurityStateModel.NEUTRAL, SecurityStateModel.fromWire("neutral"))
        assertEquals(SecurityStateModel.INSECURE, SecurityStateModel.fromWire("insecure"))
        assertEquals(SecurityStateModel.SECURE, SecurityStateModel.fromWire("secure"))
        assertEquals(SecurityStateModel.INFO, SecurityStateModel.fromWire("info"))
        assertNull(SecurityStateModel.fromWire("bogus"))
        assertNull(SecurityStateModel.fromWire(null))
    }

    @Test
    fun securityActionResult_and_error_factories_produceExpectedValues() {
        val success = SecurityActionResult.Success(Unit)
        assertTrue(success.value === Unit)

        val unsupported = SecurityError.Unsupported("nope")
        assertEquals("nope", (unsupported as SecurityError.Unsupported).reason)

        val unavailable = SecurityError.Unavailable("not bound")
        assertEquals("not bound", (unavailable as SecurityError.Unavailable).detail)

        val failed = SecurityError.ExecutionFailed(RuntimeException("boom"))
        assertEquals("boom", (failed as SecurityError.ExecutionFailed).cause.message)

        val failure = SecurityActionResult.Failure(unsupported)
        assertTrue(failure.error === unsupported)
    }

    @Test
    fun parser_missingFields_parseSafely() {
        val explanation = SecurityParser.parseExplanation(mapOf<String, Any?>("title" to "T"))
        assertNotNull(explanation)
        assertEquals("T", explanation!!.title)
        assertNull(explanation!!.securityState)
        assertTrue(explanation!!.certificate.isEmpty())
        assertNull(SecurityParser.parseExplanation("not-a-map"))
        assertNull(SecurityParser.parseExplanation(null))

        val cert = SecurityParser.parseCertificateDetails(mapOf<String, Any?>("subjectName" to "example.com"))
        assertNotNull(cert)
        assertEquals("example.com", cert!!.subjectName)
        assertEquals("", cert!!.issuer)
        assertTrue(cert!!.certificate.isEmpty())
        assertNull(cert!!.keyExchangeGroup)
        assertNull(SecurityParser.parseCertificateDetails("not-a-map"))
        assertNull(SecurityParser.parseCertificateDetails(null))
    }

    // ============ Phase 12: StateFlow streaming & getCertificate ============

    @Test
    fun securityStateFlow_publishesOnStateChangedEvent() {
        bind()
        deliverEvent(
            "Security.securityStateChanged",
            """{"securityState":"secure","schemeIsCryptographic":true,"explanations":[]}"""
        )
        assertTrue(waitFor { engine.securityStateFlow.value == SecurityStateModel.SECURE })
        assertEquals(SecurityStateModel.SECURE, engine.securityStateFlow.value)
    }

    @Test
    fun securityStateFlow_isClearedOnDetach() {
        bind()
        deliverEvent(
            "Security.securityStateChanged",
            """{"securityState":"insecure","schemeIsCryptographic":false,"explanations":[]}"""
        )
        assertTrue(waitFor { engine.securityStateFlow.value == SecurityStateModel.INSECURE })
        engine.detach()
        assertNull(engine.securityStateFlow.value)
    }

    @Test
    fun getCertificate_serializesOriginAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<CertificateModel>>()
        engine.getCertificate("https://example.com") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Security.getCertificate") }
        assertTrue(raw.contains("\"method\":\"Security.getCertificate\""))
        assertTrue(raw.contains("\"origin\":\"https://example.com\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun getCertificate_success_parsesCertificateChain() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<CertificateModel>>()
        engine.getCertificate("https://example.com") { results.add(it) }
        val id = parseIdFor("Security.getCertificate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"subjectName":"example.com","issuer":"CA Issuer","validFrom":1700000000,"validTo":1800000000,"certificateChain":["-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----"]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is SecurityActionResult.Success)
        val certificate = (results[0] as SecurityActionResult.Success).value
        assertEquals("example.com", certificate.subjectName)
        assertEquals("CA Issuer", certificate.issuer)
        assertEquals(1700000000L, certificate.validFrom)
        assertEquals(1800000000L, certificate.validTo)
        assertEquals(1, certificate.certificateChain.size)
        assertTrue(certificate.certificateChain[0].contains("BEGIN CERTIFICATE"))
        assertEquals(SecurityCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun getCertificate_nonObjectResult_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<CertificateModel>>()
        engine.getCertificate("https://example.com") { results.add(it) }
        val id = parseIdFor("Security.getCertificate")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":[]}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is SecurityActionResult.Failure)
        assertTrue((results[0] as SecurityActionResult.Failure).error is SecurityError.ExecutionFailed)
        assertEquals(SecurityCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun getCertificate_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<CertificateModel>>()
        engine.getCertificate("https://example.com") { results.add(it) }
        val id = parseIdFor("Security.getCertificate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"getCertificate broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as SecurityActionResult.Failure).error is SecurityError.ExecutionFailed)
        assertEquals(SecurityCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun getCertificate_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<SecurityActionResult<CertificateModel>>()
        engine.getCertificate("https://example.com") { results.add(it) }
        val id = parseIdFor("Security.getCertificate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as SecurityActionResult.Failure).error is SecurityError.Unsupported)
        assertEquals(SecurityCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    @Test
    fun getCertificate_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<SecurityActionResult<CertificateModel>>()
        engine.getCertificate("https://example.com") { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as SecurityActionResult.Failure).error is SecurityError.Unavailable)
        assertEquals(SecurityCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun parser_parseSecurityState_and_getCertificate_parseSafely() {
        assertEquals(
            SecurityStateModel.SECURE,
            SecurityParser.parseSecurityState(mapOf<String, Any?>("securityState" to "secure")),
        )
        assertNull(SecurityParser.parseSecurityState(mapOf<String, Any?>("securityState" to "bogus")))
        assertNull(SecurityParser.parseSecurityState("not-a-map"))

        val cert = SecurityParser.parseGetCertificate(mapOf<String, Any?>("subjectName" to "example.com", "certificate" to listOf("PEM")))
        assertNotNull(cert)
        assertEquals("example.com", cert!!.subjectName)
        assertEquals(1, cert!!.certificateChain.size)
        assertNull(SecurityParser.parseGetCertificate(listOf("x")))
        assertNull(SecurityParser.parseGetCertificate(null))
    }
}