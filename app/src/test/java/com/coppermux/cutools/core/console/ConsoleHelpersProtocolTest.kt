package com.coppermux.cutools.core.console

import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomState
import com.coppermux.cutools.core.model.Command
import com.coppermux.cutools.core.model.Protocol
import com.coppermux.cutools.core.runtime.ExecutionEngine
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

/**
 * Protocol-level tests for the Phase 4C helpers: `DOM.resolveNode` and query
 * `Runtime.evaluate` command serialization, end-to-end response parsing through
 * the raw transport, and target/session routing of stale responses.
 */
class ConsoleHelpersProtocolTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var domInspector: DomInspector
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var console: ConsoleInspector

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        domInspector = DomInspector(dispatcher, router)
        executionEngine = ExecutionEngine(dispatcher)
        executionEngine.bindSession(session)
        console = ConsoleInspector(dispatcher, router, domInspector, executionEngine)
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

    private fun bindConsole() {
        console.bindSession(session)
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-1","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.READY })
    }

    private fun bindDomLoaded() {
        domInspector.bindSession(session)
        val enableId = parseIdFor("DOM.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.getDocument") } })
        val docId = parseIdFor("DOM.getDocument")
        transport.deliverMessage(
            """{"id":$docId,"sessionId":"session-1","result":{"root":{
               "nodeId":1,"nodeType":9,"nodeName":"#document","childNodeCount":1,
               "children":[{"nodeId":2,"nodeType":1,"nodeName":"HTML","childNodeCount":1,
                 "children":[{"nodeId":3,"nodeType":1,"nodeName":"BODY","childNodeCount":1,
                   "children":[{"nodeId":4,"nodeType":1,"nodeName":"DIV","attributes":["id","box"],"childNodeCount":0}]}]}]}}}"""
        )
        assertTrue(waitFor { domInspector.state() == DomState.READY })
    }

    @Test
    fun resolveNode_serializesWithNodeIdAndSessionId() {
        bindConsole()
        bindDomLoaded()
        domInspector.selectNode(4)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        val raw = transport.sentMessages.last { it.contains("DOM.resolveNode") }
        assertTrue(raw.contains("\"method\":\"DOM.resolveNode\""))
        assertTrue(raw.contains("\"nodeId\":4"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun resolveResponse_parsedEndToEnd_intoSelectedElement() {
        bindConsole()
        bindDomLoaded()
        domInspector.selectNode(4)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        val id = parseIdFor("DOM.resolveNode")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"object":{"type":"object","subtype":"node","className":"HTMLDivElement","description":"div#box","objectId":"-123.1"}}}"""
        )
        assertTrue(waitFor { console.state.selectedElement?.phase == SelectedElementPhase.LOADED })
        val ref = console.state.selectedElement!!
        assertEquals("-123.1", ref.remoteObject?.objectId)
        assertEquals("HTMLDivElement", ref.remoteObject?.className)
        assertEquals("session-1", ref.sessionId)
    }

    @Test
    fun querySelector_eval_serializesPageExpressionOnSession() {
        bindConsole()
        console.runQuerySelector("#box")
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Runtime.evaluate") } })
        val raw = transport.sentMessages.last { it.contains("Runtime.evaluate") }
        assertTrue(raw.contains("\"method\":\"Runtime.evaluate\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))

        val command: Command? = Protocol.commandAdapter.fromJson(raw)
        assertNotNull(command)
        assertEquals("Runtime.evaluate", command!!.method)
        val params = command.params as? Map<*, *>
        assertNotNull(params)
        assertEquals("document.querySelector(\"#box\")", params!!["expression"])
    }

    @Test
    fun staleResolveResponse_afterDetach_isDropped() {
        bindConsole()
        bindDomLoaded()
        domInspector.selectNode(4)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        val id = parseIdFor("DOM.resolveNode")
        console.detach()
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"object":{"type":"object","subtype":"node","objectId":"stale"}}}"""
        )
        Thread.sleep(200)
        assertNull(console.state.selectedElement)
        assertEquals(ConsoleLifecyclePhase.INACTIVE, console.state.phase)
    }

    @Test
    fun staleResolveResponse_afterTargetSwitch_isNotAttributed() {
        bindConsole()
        bindDomLoaded()
        domInspector.selectNode(4)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        val id = parseIdFor("DOM.resolveNode")

        // Switch to a new session before the response arrives.
        console.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-2","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-2","result":{}}""")
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.READY })

        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"object":{"type":"object","subtype":"node","objectId":"old-element"}}}"""
        )
        Thread.sleep(200)
        // The old session's stale resolution must never be adopted: the reference
        // belongs to the CURRENT target/session (and still lacks a resolved object).
        assertEquals("session-2", console.state.selectedElement?.sessionId)
        assertTrue(console.state.selectedElement?.targetId == "target-2")
        assertTrue("old-element" != console.state.selectedElement?.remoteObject?.objectId)
        assertEquals("session-2", console.state.sessionId)
    }
}