package com.coppermux.cutools.ui.target

import com.coppermux.cutools.core.target.TargetEngine
import com.coppermux.cutools.core.target.models.TargetActionResult
import com.coppermux.cutools.core.target.models.TargetCapabilityStatus
import com.coppermux.cutools.core.target.models.TargetError
import com.coppermux.cutools.core.target.models.TargetInfoModel
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.core.target.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for the Phase 13 Target ViewModel.
 *
 * The real [TargetEngine] (Phase 12) is constructed against the fake transport
 * and driven through raw CDP JSON, then the ViewModel's state transitions,
 * command dispatching, real-time target discovery stream propagation, and error
 * propagation into explicit banners are verified in the same pipeline the app
 * uses against Chrome. No-Fake-Capability is enforced: engine failures surface
 * as explicit `errorMessage` banners with matching status, never synthetic
 * target state.
 */
class TargetViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: TargetEngine
    private lateinit var viewModel: TargetViewModel

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = TargetEngine(dispatcher, router)
        viewModel = TargetViewModel(engine)
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

    private fun deliverTargetCreated(targetId: String, type: String = "page", title: String = "", url: String = "") {
        transport.deliverMessage(
            """{"method":"Target.targetCreated","sessionId":"session-1","params":{"targetInfo":{"targetId":"$targetId","type":"$type","title":"$title","url":"$url"}}}"""
        )
    }

    // ============ target attach / detach coordination ============

    @Test
    fun attachTarget_bindsEngineAndPublishesState() {
        viewModel.attachTarget(session)
        assertEquals(session, engine.session)
        assertEquals(session, viewModel.attachedSession)
        assertTrue(viewModel.isAttached)
        assertEquals(TargetCapabilityStatus.UNAVAILABLE, viewModel.state.value.status)
    }

    @Test
    fun detachTarget_clearsEngineAndResetsState() {
        viewModel.attachTarget(session)
        viewModel.detachTarget()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertFalse(viewModel.isAttached)
        val state = viewModel.state.value
        assertEquals(TargetCapabilityStatus.UNAVAILABLE, state.status)
        assertNull(state.errorMessage)
        assertTrue(state.targets.isEmpty())
        assertFalse(state.discoveryEnabled)
    }

    @Test
    fun onConnectionLost_clearsEngineAndResetsState() {
        viewModel.attachTarget(session)
        viewModel.onConnectionLost()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertEquals(TargetCapabilityStatus.UNAVAILABLE, viewModel.state.value.status)
    }

    // ============ state transitions / command dispatching ============

    @Test
    fun enableTargetDiscovery_success_marksSupportedAndDiscoveryEnabled() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        viewModel.enableTargetDiscovery { results.add(it) }
        val id = parseIdFor("Target.setDiscoverTargets")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is TargetActionResult.Success)
        val state = viewModel.state.value
        assertEquals(TargetCapabilityStatus.SUPPORTED, state.status)
        assertTrue(state.discoveryEnabled)
        assertTrue(viewModel.isDiscoveryEnabled)
        assertNull(state.errorMessage)
    }

    @Test
    fun enableTargetDiscovery_dispatchesDiscoverTrueWithSession() {
        viewModel.attachTarget(session)
        transport.resetSentMessages()
        viewModel.enableTargetDiscovery {}
        val raw = transport.sentMessages.last { it.contains("Target.setDiscoverTargets") }
        assertTrue(raw.contains("\"method\":\"Target.setDiscoverTargets\""))
        assertTrue(raw.contains("\"discover\":true"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun disableTargetDiscovery_success_marksDiscoveryDisabled() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        viewModel.disableTargetDiscovery { results.add(it) }
        val id = parseIdFor("Target.setDiscoverTargets")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is TargetActionResult.Success)
        assertFalse(viewModel.state.value.discoveryEnabled)
        assertFalse(viewModel.isDiscoveryEnabled)
    }

    @Test
    fun attachToTarget_success_recordsAttachedTargetId() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<TargetActionResult<String>>()
        viewModel.attachToTarget("target-7") { results.add(it) }
        val id = parseIdFor("Target.attachToTarget")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"sessionId":"sub-session"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is TargetActionResult.Success)
        assertTrue(waitFor { viewModel.state.value.attachedTargetId == "target-7" })
        assertNull(viewModel.state.value.errorMessage)
        assertEquals(TargetCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    @Test
    fun detachFromTarget_success_clearsAttachedTargetId() {
        viewModel.attachTarget(session)
        viewModel.attachToTarget("target-7") {}
        val attachId = parseIdFor("Target.attachToTarget")
        transport.deliverMessage("""{"id":$attachId,"sessionId":"session-1","result":{"sessionId":"sub-session"}}""")
        assertTrue(waitFor { viewModel.state.value.attachedTargetId == "target-7" })

        val results = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        viewModel.detachFromTarget("sub-session") { results.add(it) }
        val id = parseIdFor("Target.detachFromTarget")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.attachedTargetId == null })
        assertEquals(TargetCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    @Test
    fun refreshTargets_success_recordsGenuineList() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<TargetActionResult<List<TargetInfoModel>>>()
        viewModel.refreshTargets { results.add(it) }
        val id = parseIdFor("Target.getTargets")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"targetInfos":[{"targetId":"t1","type":"page","title":"A","url":"https://a.example/"},{"targetId":"t2","type":"browser"}]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is TargetActionResult.Success)
        assertTrue(waitFor { viewModel.state.value.targets.size == 2 })
        assertEquals("A", viewModel.state.value.targets[0].title)
        assertNull(viewModel.state.value.errorMessage)
    }

    // ============ real-time target discovery stream ============

    @Test
    fun targetStream_propagatesThroughEngineToViewModel() {
        viewModel.attachTarget(session)
        deliverTargetCreated("t1", "page", "Page One", "https://a.example/")
        deliverTargetCreated("t2", "service_worker", "Worker", "https://a.example/worker.js")
        assertTrue(waitFor { viewModel.engineTargets.value.size == 2 })
        assertEquals("t1", viewModel.engineTargets.value[0].targetId)
        assertEquals("service_worker", viewModel.engineTargets.value[1].type)
        assertEquals(2, viewModel.filteredTargets.size)

        viewModel.refreshTargetSnapshot()
        assertEquals(2, viewModel.state.value.targets.size)
    }

    @Test
    fun filteredTargets_matchesByTitleTypeAndUrl() {
        viewModel.attachTarget(session)
        deliverTargetCreated("t1", "page", "Login", "https://a.example/")
        deliverTargetCreated("t2", "service_worker", "Worker", "https://b.example/worker.js")
        assertTrue(waitFor { viewModel.engineTargets.value.size == 2 })

        viewModel.updateSearchQuery("login")
        assertEquals("login", viewModel.state.value.searchQuery)
        assertEquals(1, viewModel.filteredTargets.size)
        assertEquals("t1", viewModel.filteredTargets[0].targetId)

        viewModel.updateSearchQuery("b.example")
        assertEquals(1, viewModel.filteredTargets.size)
        assertEquals("t2", viewModel.filteredTargets[0].targetId)

        viewModel.updateSearchQuery("service_worker")
        assertEquals(1, viewModel.filteredTargets.size)

        viewModel.updateSearchQuery("zzz")
        assertTrue(viewModel.filteredTargets.isEmpty())
    }

    @Test
    fun filterTargets_blankQueryIsIdentity() {
        val targets = listOf(
            TargetInfoModel(targetId = "t1", type = "page", title = "A"),
            TargetInfoModel(targetId = "t2", type = "browser"),
        )
        assertEquals(2, viewModel.filterTargets(targets, "").size)
        assertEquals(2, viewModel.filterTargets(targets, "   ").size)
    }

    // ============ error handling (No-Fake-Capability) ============

    @Test
    fun enableTargetDiscovery_withoutSession_resolvesUnavailableBanner() {
        val results = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        viewModel.enableTargetDiscovery { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is TargetActionResult.Failure)
        val state = viewModel.state.value
        assertEquals(TargetCapabilityStatus.UNAVAILABLE, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unavailable"))
        assertFalse(state.discoveryEnabled)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun enableTargetDiscovery_methodUnavailable_resolvesUnsupportedBanner() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        viewModel.enableTargetDiscovery { results.add(it) }
        val id = parseIdFor("Target.setDiscoverTargets")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertEquals(TargetCapabilityStatus.UNSUPPORTED, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unsupported"))
        assertFalse(state.discoveryEnabled)
    }

    @Test
    fun refreshTargets_protocolFailure_resolvesFailedBanner() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<TargetActionResult<List<TargetInfoModel>>>()
        viewModel.refreshTargets { results.add(it) }
        val id = parseIdFor("Target.getTargets")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"getTargets broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertTrue(state.errorMessage.orEmpty().contains("getTargets broke"))
        assertEquals(TargetCapabilityStatus.FAILED, state.status)
    }

    @Test
    fun attachToTarget_successAfterError_clearsBanner() {
        viewModel.attachTarget(session)
        viewModel.attachToTarget("target-7") {}
        val failId = parseIdFor("Target.attachToTarget")
        transport.deliverMessage("""{"id":$failId,"sessionId":"session-1","error":{"code":-32000,"message":"broke"}}""")
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })

        viewModel.attachToTarget("target-8") {}
        val okId = parseIdFor("Target.attachToTarget")
        transport.deliverMessage("""{"id":$okId,"sessionId":"session-1","result":{"sessionId":"s"}}""")
        assertTrue(waitFor { viewModel.state.value.attachedTargetId == "target-8" })
        assertNull(viewModel.state.value.errorMessage)
        assertEquals(TargetCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    // ============ error description helpers ============

    @Test
    fun describeError_mapsEveryErrorVariantToRenderableBanner() {
        assertTrue(viewModel.describeError(TargetError.Unsupported).contains("Unsupported"))
        assertTrue(viewModel.describeError(TargetError.Unavailable).contains("Unavailable"))
        assertTrue(viewModel.describeError(TargetError.TargetDisconnected).contains("disconnected"))
        assertTrue(viewModel.describeError(TargetError.ExecutionFailed("boom")).contains("boom"))
        assertTrue(viewModel.describeError(TargetError.ExecutionFailed("")).contains("execution failed"))
    }
}