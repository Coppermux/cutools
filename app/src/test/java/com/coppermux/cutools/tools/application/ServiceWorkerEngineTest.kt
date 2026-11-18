package com.coppermux.cutools.tools.application

import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.tools.application.engine.ServiceWorkerEngine
import com.coppermux.cutools.tools.application.models.ServiceWorkerActionStatus
import com.coppermux.cutools.tools.application.models.ServiceWorkerActionResult
import com.coppermux.cutools.tools.application.models.ServiceWorkerCapabilityStatus
import com.coppermux.cutools.tools.application.models.ServiceWorkerRunningStatus
import com.coppermux.cutools.tools.application.models.ServiceWorkerVersionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Sub-Phase 6E Service Workers.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → router → dispatcher → engine, proving
 * `ServiceWorker.enable/disable/unregister/updateRegistration/stopWorker`
 * serialization, genuine `workerRegistrationUpdated` / `workerVersionUpdated` /
 * `workerErrorReported` event routing, and atomic store updates in the same
 * pipeline the app uses against Chrome. No-Fake-Capability is enforced:
 * protocol failures, missing sessions, and unavailable methods map to explicit
 * UNSUPPORTED / UNAVAILABLE / FAILED outcomes, never fabricated worker state.
 */
class ServiceWorkerEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: ServiceWorkerEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = ServiceWorkerEngine(dispatcher, router)
    }

    private fun bind() {
        engine.bindSession(session)
        val id = parseIdFor("ServiceWorker.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.enabled })
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
    fun bind_sendsServiceWorkerEnableOnSession() {
        engine.bindSession(session)
        val raw = transport.sentMessages.last { it.contains("ServiceWorker.enable") }
        assertTrue(raw.contains("\"method\":\"ServiceWorker.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun disable_serializesDisableAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.disable { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("ServiceWorker.disable") }
        assertTrue(raw.contains("\"method\":\"ServiceWorker.disable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun unregister_serializesScopeUrlAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.unregister("https://app.example/sw.js") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("ServiceWorker.unregister") }
        assertTrue(raw.contains("\"method\":\"ServiceWorker.unregister\""))
        assertTrue(raw.contains("\"scopeURL\":\"https://app.example/sw.js\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun updateRegistration_serializesScopeUrlAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.updateRegistration("https://app.example.com/") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("ServiceWorker.updateRegistration") }
        assertTrue(raw.contains("\"method\":\"ServiceWorker.updateRegistration\""))
        assertTrue(raw.contains("\"scopeURL\":\"https://app.example.com/\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun stopWorker_serializesVersionIdAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.stopWorker("version-7") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("ServiceWorker.stopWorker") }
        assertTrue(raw.contains("\"method\":\"ServiceWorker.stopWorker\""))
        assertTrue(raw.contains("\"versionId\":\"version-7\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ event parsing & store updates ============

    @Test
    fun event_registrationUpdated_populatesRegistrations() {
        bind()
        deliverEvent(
            "ServiceWorker.workerRegistrationUpdated",
            """{"registrations":[{"registrationId":"reg-1","scopeURL":"https://a.example/","isDeleted":false},{"registrationId":"reg-2","scopeURL":"https://b.example/","isDeleted":true}]}"""
        )
        assertTrue(waitFor { engine.state.registrationCount == 2 })
        assertEquals("https://a.example/", engine.state.registrations["reg-1"]!!.scopeURL)
        assertTrue(!engine.state.registrations["reg-1"]!!.isDeleted)
        assertTrue(engine.state.registrations["reg-2"]!!.isDeleted)
    }

    @Test
    fun event_registrationUpdated_upsertsByRegistrationId() {
        bind()
        deliverEvent(
            "ServiceWorker.workerRegistrationUpdated",
            """{"registrations":[{"registrationId":"reg-1","scopeURL":"https://a.example/","isDeleted":false}]}"""
        )
        assertTrue(waitFor { engine.state.registrationCount == 1 })
        deliverEvent(
            "ServiceWorker.workerRegistrationUpdated",
            """{"registrations":[{"registrationId":"reg-1","scopeURL":"https://a.example/","isDeleted":true}]}"""
        )
        assertTrue(waitFor { engine.state.registrations["reg-1"]!!.isDeleted })
        assertEquals(1, engine.state.registrationCount)
    }

    @Test
    fun event_versionUpdated_populatesVersions() {
        bind()
        deliverEvent(
            "ServiceWorker.workerVersionUpdated",
            """{"versions":[{"versionId":"v-1","registrationId":"reg-1","scriptURL":"https://a.example/sw.js","runningStatus":"running","status":"activated","scriptLastModifiedTime":"2026-01-01T00:00:00Z"}]}"""
        )
        assertTrue(waitFor { engine.state.versionCount == 1 })
        val version = engine.state.versions["v-1"]!!
        assertEquals("reg-1", version.registrationId)
        assertEquals("https://a.example/sw.js", version.scriptURL)
        assertEquals(ServiceWorkerRunningStatus.RUNNING, version.runningStatus)
        assertEquals(ServiceWorkerVersionStatus.ACTIVATED, version.status)
        assertEquals("2026-01-01T00:00:00Z", version.scriptLastModified)
    }

    @Test
    fun event_versionUpdated_upsertsByVersionId() {
        bind()
        deliverEvent(
            "ServiceWorker.workerVersionUpdated",
            """{"versions":[{"versionId":"v-1","runningStatus":"stopped","status":"stopped"}]}"""
        )
        assertTrue(waitFor { engine.state.versionCount == 1 })
        deliverEvent(
            "ServiceWorker.workerVersionUpdated",
            """{"versions":[{"versionId":"v-1","runningStatus":"running","status":"activated"}]}"""
        )
        assertTrue(waitFor { engine.state.versions["v-1"]!!.runningStatus == ServiceWorkerRunningStatus.RUNNING })
        assertEquals(1, engine.state.versionCount)
    }

    @Test
    fun event_errorReported_appendsErrorLog() {
        bind()
        deliverEvent(
            "ServiceWorker.workerErrorReported",
            """{"versionId":"v-1","registrationId":"reg-1","errorType":"RuntimeError","errorMessage":"boom"}"""
        )
        assertTrue(waitFor { engine.state.errorCount == 1 })
        val error = engine.state.errorLogs[0]
        assertEquals("v-1", error.versionId)
        assertEquals("reg-1", error.registrationId)
        assertEquals("RuntimeError", error.errorType)
        assertEquals("boom", error.errorMessage)
    }

    @Test
    fun event_forOtherSession_isIgnored() {
        bind()
        transport.deliverMessage(
            """{"method":"ServiceWorker.workerRegistrationUpdated","sessionId":"other-session","params":{"registrations":[{"registrationId":"reg-1","scopeURL":"https://a.example/"}]}}"""
        )
        Thread.sleep(200)
        assertEquals(0, engine.state.registrationCount)
    }

    @Test
    fun event_malformedPayload_isTolerated() {
        bind()
        deliverEvent("ServiceWorker.workerRegistrationUpdated", """{"registrations":[{"scopeURL":"https://x.example/"}]}""")
        deliverEvent("ServiceWorker.workerErrorReported", "{}")
        deliverEvent("ServiceWorker.workerVersionUpdated", "{}")
        Thread.sleep(200)
        assertEquals(0, engine.state.registrationCount)
        assertEquals(0, engine.state.versionCount)
        assertEquals(0, engine.state.errorCount)
    }

    // ============ error mapping ============

    @Test
    fun unregister_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.unregister("https://a.example/") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(ServiceWorkerActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(ServiceWorkerCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun updateRegistration_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.updateRegistration("https://a.example/") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(ServiceWorkerActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun stopWorker_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.stopWorker("v-1") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(ServiceWorkerActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun enable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.enable { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(ServiceWorkerActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun disable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(ServiceWorkerActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun unregister_blankScopeUrl_isFailed() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.unregister("   ") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(ServiceWorkerActionStatus.FAILED, results[0].status)
        assertTrue(results[0].message.orEmpty().contains("scope URL"))
    }

    @Test
    fun stopWorker_blankVersionId_isFailed() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.stopWorker("") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(ServiceWorkerActionStatus.FAILED, results[0].status)
    }

    @Test
    fun unregister_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.unregister("https://a.example/") { results.add(it) }
        val id = parseIdFor("ServiceWorker.unregister")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"unregister broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ServiceWorkerActionStatus.FAILED, results[0].status)
        assertTrue(results[0].message.orEmpty().contains("unregister broke"))
        assertTrue(results[0].throwable != null)
        assertEquals(ServiceWorkerCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun updateRegistration_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.updateRegistration("https://a.example/") { results.add(it) }
        val id = parseIdFor("ServiceWorker.updateRegistration")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ServiceWorkerActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(ServiceWorkerCapabilityStatus.UNSUPPORTED, engine.capability)
        assertEquals(ServiceWorkerCapabilityStatus.UNSUPPORTED, engine.state.capability)
    }

    @Test
    fun stopWorker_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.stopWorker("v-1") { results.add(it) }
        val id = parseIdFor("ServiceWorker.stopWorker")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"stop broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ServiceWorkerActionStatus.FAILED, results[0].status)
        assertEquals(ServiceWorkerCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun enable_methodUnavailable_mapsToUnsupported() {
        engine.bindSession(session)
        val id = parseIdFor("ServiceWorker.enable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { engine.capability == ServiceWorkerCapabilityStatus.UNSUPPORTED })
        assertEquals(ServiceWorkerCapabilityStatus.UNSUPPORTED, engine.state.capability)
        assertTrue(!engine.state.enabled)
    }

    @Test
    fun enable_protocolFailure_mapsToFailed() {
        engine.bindSession(session)
        val id = parseIdFor("ServiceWorker.enable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"nope"}}"""
        )
        assertTrue(waitFor { engine.capability == ServiceWorkerCapabilityStatus.FAILED })
        assertEquals(ServiceWorkerCapabilityStatus.FAILED, engine.state.capability)
    }

    // ============ mutation / domain success ============

    @Test
    fun unregister_success_returnsSuccessAndCapability() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.unregister("https://a.example/") { results.add(it) }
        val id = parseIdFor("ServiceWorker.unregister")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ServiceWorkerActionStatus.SUCCESS, results[0].status)
        assertEquals(ServiceWorkerCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun updateRegistration_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.updateRegistration("https://a.example/") { results.add(it) }
        val id = parseIdFor("ServiceWorker.updateRegistration")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ServiceWorkerActionStatus.SUCCESS, results[0].status)
    }

    @Test
    fun stopWorker_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.stopWorker("v-1") { results.add(it) }
        val id = parseIdFor("ServiceWorker.stopWorker")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ServiceWorkerActionStatus.SUCCESS, results[0].status)
    }

    @Test
    fun disable_success_disablesDomain() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.disable { results.add(it) }
        val id = parseIdFor("ServiceWorker.disable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ServiceWorkerActionStatus.SUCCESS, results[0].status)
        assertTrue(waitFor { !engine.state.enabled })
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsStateAndUnsubscribes() {
        bind()
        deliverEvent(
            "ServiceWorker.workerRegistrationUpdated",
            """{"registrations":[{"registrationId":"reg-1","scopeURL":"https://a.example/"}]}"""
        )
        deliverEvent(
            "ServiceWorker.workerVersionUpdated",
            """{"versions":[{"versionId":"v-1","runningStatus":"running","status":"activated"}]}"""
        )
        deliverEvent(
            "ServiceWorker.workerErrorReported",
            """{"versionId":"v-1","errorType":"E","errorMessage":"m"}"""
        )
        assertTrue(waitFor { engine.state.errorCount == 1 })
        for (method in listOf(
            "ServiceWorker.workerRegistrationUpdated",
            "ServiceWorker.workerVersionUpdated",
            "ServiceWorker.workerErrorReported",
        )) {
            assertEquals(1, router.subscriptionCount(method))
        }

        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
        assertEquals(0, engine.state.registrationCount)
        assertEquals(0, engine.state.versionCount)
        assertEquals(0, engine.state.errorCount)
        for (method in listOf(
            "ServiceWorker.workerRegistrationUpdated",
            "ServiceWorker.workerVersionUpdated",
            "ServiceWorker.workerErrorReported",
        )) {
            assertEquals("no event-listener leak for $method", 0, router.subscriptionCount(method))
        }
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        deliverEvent(
            "ServiceWorker.workerRegistrationUpdated",
            """{"registrations":[{"registrationId":"reg-1","scopeURL":"https://a.example/"}]}"""
        )
        assertTrue(waitFor { engine.state.registrationCount == 1 })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
        assertEquals(0, engine.state.registrationCount)
        assertEquals(0, engine.state.versionCount)
        assertEquals(0, engine.state.errorCount)
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        deliverEvent(
            "ServiceWorker.workerVersionUpdated",
            """{"versions":[{"versionId":"v-1","runningStatus":"running"}]}"""
        )
        assertTrue(waitFor { engine.state.versionCount == 1 })
        engine.onConnectionLost()
        assertNull(engine.session)
        assertEquals(0, engine.state.versionCount)
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<ServiceWorkerActionResult<Unit>>()
        engine.unregister("https://a.example/") { results.add(it) }
        val id = parseIdFor("ServiceWorker.unregister")

        engine.detach()
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{}}"""
        )
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
    }

    @Test
    fun lateEventAfterDetach_isIgnored() {
        bind()
        engine.detach()
        transport.deliverMessage(
            """{"method":"ServiceWorker.workerRegistrationUpdated","sessionId":"session-1","params":{"registrations":[{"registrationId":"reg-1","scopeURL":"https://a.example/"}]}}"""
        )
        Thread.sleep(200)
        assertEquals(0, engine.state.registrationCount)
    }

    @Test
    fun rebind_doesNotAccumulateSubscriptions() {
        engine.bindSession(session)
        val id1 = parseIdFor("ServiceWorker.enable")
        transport.deliverMessage("""{"id":$id1,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.enabled })

        val reconnected = SessionInfo("session-3", "target-3", "ws://test")
        engine.bindSession(reconnected)
        val id2 = parseIdFor("ServiceWorker.enable")
        transport.deliverMessage("""{"id":$id2,"sessionId":"session-3","result":{}}""")
        assertTrue(waitFor { engine.state.enabled && engine.session == reconnected })

        assertEquals(
            "rebinding must not leak extra listeners",
            1,
            router.subscriptionCount("ServiceWorker.workerRegistrationUpdated"),
        )
    }

    // ============ model-level mapping ============

    @Test
    fun runningStatus_fromWire_mapsKnownValues() {
        assertEquals(ServiceWorkerRunningStatus.STARTING, ServiceWorkerRunningStatus.fromWire("starting"))
        assertEquals(ServiceWorkerRunningStatus.RUNNING, ServiceWorkerRunningStatus.fromWire("running"))
        assertEquals(ServiceWorkerRunningStatus.STOPPING, ServiceWorkerRunningStatus.fromWire("stopping"))
        assertEquals(ServiceWorkerRunningStatus.STOPPED, ServiceWorkerRunningStatus.fromWire("stopped"))
        assertNull(ServiceWorkerRunningStatus.fromWire("bogus"))
        assertNull(ServiceWorkerRunningStatus.fromWire(null))
    }

    @Test
    fun versionStatus_fromWire_mapsKnownValues() {
        assertEquals(ServiceWorkerVersionStatus.NEW, ServiceWorkerVersionStatus.fromWire("new"))
        assertEquals(ServiceWorkerVersionStatus.INSTALLING, ServiceWorkerVersionStatus.fromWire("installing"))
        assertEquals(ServiceWorkerVersionStatus.INSTALLED, ServiceWorkerVersionStatus.fromWire("installed"))
        assertEquals(ServiceWorkerVersionStatus.ACTIVATING, ServiceWorkerVersionStatus.fromWire("activating"))
        assertEquals(ServiceWorkerVersionStatus.ACTIVATED, ServiceWorkerVersionStatus.fromWire("activated"))
        assertEquals(ServiceWorkerVersionStatus.RUNNING, ServiceWorkerVersionStatus.fromWire("running"))
        assertEquals(ServiceWorkerVersionStatus.REDUNDANT, ServiceWorkerVersionStatus.fromWire("redundant"))
        assertEquals(ServiceWorkerVersionStatus.DEACTIVATING, ServiceWorkerVersionStatus.fromWire("deactivating"))
        assertEquals(ServiceWorkerVersionStatus.STOPPED, ServiceWorkerVersionStatus.fromWire("stopped"))
        assertEquals(ServiceWorkerVersionStatus.UNINSTALLING, ServiceWorkerVersionStatus.fromWire("uninstalling"))
        assertNull(ServiceWorkerVersionStatus.fromWire("bogus"))
        assertNull(ServiceWorkerVersionStatus.fromWire(null))
    }

    @Test
    fun serviceWorkerActionResult_factories_produceExpectedStatuses() {
        val success = ServiceWorkerActionResult.success(Unit)
        assertEquals(ServiceWorkerActionStatus.SUCCESS, success.status)
        assertTrue(success.isSuccess)

        val unsupported = ServiceWorkerActionResult.unsupported<String>("nope", RuntimeException("cause"))
        assertEquals(ServiceWorkerActionStatus.UNSUPPORTED, unsupported.status)
        assertEquals("nope", unsupported.message)
        assertTrue(unsupported.throwable != null)

        val unavailable = ServiceWorkerActionResult.unavailable<Int>("not bound")
        assertEquals(ServiceWorkerActionStatus.UNAVAILABLE, unavailable.status)

        val failed = ServiceWorkerActionResult.failed<Long>("boom")
        assertEquals(ServiceWorkerActionStatus.FAILED, failed.status)
        assertTrue(!failed.isSuccess)
    }
}