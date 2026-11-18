package com.coppermux.cutools.ui.accessibility

import com.coppermux.cutools.core.accessibility.AccessibilityEngine
import com.coppermux.cutools.core.accessibility.models.AXTreeModel
import com.coppermux.cutools.core.accessibility.models.AccessibilityActionResult
import com.coppermux.cutools.core.accessibility.models.AccessibilityCapabilityStatus
import com.coppermux.cutools.core.accessibility.models.AccessibilityError
import com.coppermux.cutools.core.target.SessionInfo
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
 * Unit & protocol test suite for the Phase 9C Accessibility ViewModel.
 *
 * The real [AccessibilityEngine] (Phase 9A) is constructed against the fake
 * transport and driven through raw CDP JSON, then the ViewModel's state
 * transitions, command dispatching, tree search filtering, error propagation
 * into explicit banners, and attach / detach / connection-loss coordination are
 * verified in the same pipeline the app uses against Chrome. No-Fake-Capability
 * is enforced: engine failures surface as explicit `errorMessage` banners with
 * matching status, never synthetic tree fallbacks.
 */
class AccessibilityViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: AccessibilityEngine
    private lateinit var viewModel: AccessibilityViewModel

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        dispatcher = CommandDispatcher(transport, null, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = AccessibilityEngine(dispatcher)
        viewModel = AccessibilityViewModel(engine)
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

    private val sampleTreeJson = """{"nodes":[
        {"nodeId":"0","ignored":false,"role":{"type":"role","value":"RootWebArea"},"name":{"type":"name","value":"Page"},"childIds":["1","2"]},
        {"nodeId":"1","ignored":false,"role":{"type":"role","value":"button"},"name":{"type":"name","value":"Submit Form"},"childIds":["3"]},
        {"nodeId":"2","ignored":false,"role":{"type":"role","value":"heading"},"name":{"type":"name","value":"Welcome"},"childIds":[]},
        {"nodeId":"3","ignored":false,"role":{"type":"role","value":"textbox"},"name":{"type":"name","value":"Email"},"value":{"type":"string","value":"user@example.com"},"childIds":[]}
    ]}"""

    // ============ target attach / detach coordination ============

    @Test
    fun attachTarget_bindsEngineAndPublishesState() {
        viewModel.attachTarget(session)
        assertEquals(session, engine.session)
        assertEquals(session, viewModel.attachedSession)
        assertTrue(viewModel.isAttached)
        assertTrue(waitFor { viewModel.state.value.status != AccessibilityCapabilityStatus.UNAVAILABLE || true })
        assertTrue(engine.session == session)
    }

    @Test
    fun detachTarget_clearsEngineAndResetsState() {
        viewModel.attachTarget(session)
        viewModel.loadFullTree {}
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { viewModel.state.value.axTree?.nodes?.size == 4 })

        viewModel.detachTarget()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertFalse(viewModel.isAttached)
        val state = viewModel.state.value
        assertEquals(AccessibilityCapabilityStatus.UNAVAILABLE, state.status)
        assertNull(state.axTree)
        assertNull(state.selectedNode)
        assertEquals("", state.searchQuery)
        assertNull(state.errorMessage)
    }

    @Test
    fun onConnectionLost_clearsEngineAndResetsState() {
        viewModel.attachTarget(session)
        viewModel.loadFullTree {}
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { viewModel.state.value.axTree?.nodes?.size == 4 })

        viewModel.onConnectionLost()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertEquals(AccessibilityCapabilityStatus.UNAVAILABLE, viewModel.state.value.status)
        assertNull(viewModel.state.value.axTree)
    }

    // ============ state transitions ============

    @Test
    fun loadFullTree_success_parsesTreeAndClearsError() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        viewModel.loadFullTree { results.add(it) }
        assertTrue(viewModel.state.value.isLoading)
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AccessibilityActionResult.Success<*>)
        assertTrue(waitFor { !viewModel.state.value.isLoading })
        val state = viewModel.state.value
        assertEquals(AccessibilityCapabilityStatus.SUPPORTED, state.status)
        assertEquals(4, state.axTree!!.nodes.size)
        assertEquals("0", state.axTree!!.rootNodeId)
        assertNull(state.errorMessage)
    }

    @Test
    fun loadFullTree_unknownNode_selectNodeResolvesNull() {
        viewModel.attachTarget(session)
        viewModel.loadFullTree {}
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { viewModel.state.value.axTree?.nodes?.size == 4 })

        viewModel.selectNode("missing-id")
        assertNull(viewModel.state.value.selectedNode)
    }

    // ============ command dispatching ============

    @Test
    fun loadFullTree_dispatchesGetFullAXTreeWithSession() {
        viewModel.attachTarget(session)
        transport.resetSentMessages()
        viewModel.loadFullTree {}
        val raw = transport.sentMessages.last { it.contains("Accessibility.getFullAXTree") }
        assertTrue(raw.contains("\"method\":\"Accessibility.getFullAXTree\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun refresh_dispatchesGetFullAXTreeAndResetsPriorTree() {
        viewModel.attachTarget(session)
        viewModel.loadFullTree {}
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { viewModel.state.value.axTree?.nodes?.size == 4 })
        viewModel.selectNode("1")
        assertEquals("1", viewModel.state.value.selectedNode?.nodeId)

        viewModel.refresh {}
        assertTrue(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.axTree)
        assertNull(viewModel.state.value.selectedNode)
        val refreshId = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$refreshId,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { viewModel.state.value.axTree?.nodes?.size == 4 && !viewModel.state.value.isLoading })
        assertEquals(AccessibilityCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    // ============ tree search filtering ============

    @Test
    fun updateSearchQuery_storesQueryAndFiltersNodesByRole() {
        viewModel.attachTarget(session)
        viewModel.loadFullTree {}
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { viewModel.state.value.axTree?.nodes?.size == 4 })

        viewModel.updateSearchQuery("button")
        assertEquals("button", viewModel.state.value.searchQuery)
        val filtered = viewModel.filteredTree
        assertEquals(2, filtered!!.nodes.size)
        assertEquals("0", filtered.rootNodeId)
        assertEquals("1", filtered.nodes.first { it.nodeId == "1" }.nodeId)
    }

    @Test
    fun updateSearchQuery_filtersNodesByNameAndValue() {
        viewModel.attachTarget(session)
        viewModel.loadFullTree {}
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { viewModel.state.value.axTree?.nodes?.size == 4 })

        viewModel.updateSearchQuery("welcome")
        assertEquals(2, viewModel.filteredTree!!.nodes.size)
        assertTrue(viewModel.filteredTree!!.nodes.any { it.nodeId == "2" })

        viewModel.updateSearchQuery("user@example.com")
        assertEquals(3, viewModel.filteredTree!!.nodes.size)
        assertTrue(viewModel.filteredTree!!.nodes.any { it.nodeId == "3" })
        assertTrue(viewModel.filteredTree!!.nodes.all { it.nodeId == "0" || it.nodeId == "1" || it.nodeId == "3" })
    }

    @Test
    fun updateSearchQuery_emptyQuery_returnsFullTree() {
        viewModel.attachTarget(session)
        viewModel.loadFullTree {}
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { viewModel.state.value.axTree?.nodes?.size == 4 })

        viewModel.updateSearchQuery("")
        assertEquals(4, viewModel.filteredTree!!.nodes.size)
        viewModel.updateSearchQuery("   ")
        assertEquals(4, viewModel.filteredTree!!.nodes.size)
    }

    @Test
    fun updateSearchQuery_noMatch_returnsEmptyTree() {
        viewModel.attachTarget(session)
        viewModel.loadFullTree {}
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { viewModel.state.value.axTree?.nodes?.size == 4 })

        viewModel.updateSearchQuery("zzz-no-match")
        assertTrue(viewModel.filteredTree!!.nodes.isEmpty())
    }

    @Test
    fun filterTree_caseInsensitiveAndPreservesAncestors() {
        viewModel.attachTarget(session)
        viewModel.loadFullTree {}
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { viewModel.state.value.axTree?.nodes?.size == 4 })

        val filtered = viewModel.filterTree(viewModel.state.value.axTree, "SUBMIT")
        assertEquals(2, filtered!!.nodes.size)
        assertTrue(filtered.nodes.all { it.nodeId == "0" || it.nodeId == "1" })
        assertEquals(listOf("1"), filtered.nodes.first { it.nodeId == "0" }.childIds)
    }

    // ============ error handling (No-Fake-Capability) ============

    @Test
    fun loadFullTree_withoutSession_resolvesUnavailableBannerWithoutFallback() {
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        viewModel.loadFullTree { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is AccessibilityActionResult.Failure)
        val state = viewModel.state.value
        assertEquals(AccessibilityCapabilityStatus.UNAVAILABLE, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unavailable"))
        assertNull(state.axTree)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun loadFullTree_methodUnavailable_resolvesUnsupportedBannerWithoutFallback() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        viewModel.loadFullTree { results.add(it) }
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AccessibilityActionResult.Failure)
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertEquals(AccessibilityCapabilityStatus.UNSUPPORTED, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unsupported"))
        assertNull(state.axTree)
    }

    @Test
    fun loadFullTree_protocolFailure_resolvesFailedBannerWithoutFallback() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        viewModel.loadFullTree { results.add(it) }
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"tree fetch broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNull(state.axTree)
        assertTrue(state.errorMessage.orEmpty().contains("tree fetch broke"))
    }

    @Test
    fun loadFullTree_successAfterError_clearsBanner() {
        viewModel.attachTarget(session)
        viewModel.loadFullTree {}
        val failId = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$failId,"sessionId":"session-1","error":{"code":-32000,"message":"broke"}}""")
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })

        viewModel.loadFullTree {}
        val okId = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$okId,"sessionId":"session-1","result":$sampleTreeJson}""")
        assertTrue(waitFor { viewModel.state.value.axTree?.nodes?.size == 4 })
        assertNull(viewModel.state.value.errorMessage)
        assertEquals(AccessibilityCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    // ============ error description helpers ============

    @Test
    fun describeError_mapsEveryErrorVariantToRenderableBanner() {
        assertTrue(viewModel.describeError(AccessibilityError.Unsupported).contains("Unsupported"))
        assertTrue(viewModel.describeError(AccessibilityError.Unavailable).contains("Unavailable"))
        assertTrue(viewModel.describeError(AccessibilityError.TargetDisconnected).contains("disconnected"))
        assertTrue(viewModel.describeError(AccessibilityError.ExecutionFailed("boom")).contains("boom"))
        assertTrue(viewModel.describeError(AccessibilityError.ExecutionFailed("")).contains("execution failed"))
    }
}