package com.coppermux.cutools.core.console

import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomNode
import com.coppermux.cutools.core.dom.DomState
import com.coppermux.cutools.core.model.Command
import com.coppermux.cutools.core.model.Protocol
import com.coppermux.cutools.core.runtime.EvaluationResultKind
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic unit tests for the Phase 4C Console developer utilities:
 * $0 selected-element tracking/resolution, querySelector helpers, command
 * history / input / submission, and lifecycle/stale-reference handling.
 */
class ConsoleHelpersTest {

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

    /** Extracts the expression from the most recent Runtime.evaluate command. */
    private fun lastEvaluateExpression(): String? {
        val raw = transport.sentMessages.last { it.contains("Runtime.evaluate") }
        val command: Command? = Protocol.commandAdapter.fromJson(raw)
        return (command?.params as? Map<*, *>)?.get("expression") as? String
    }

    private fun waitFor(timeoutMs: Long = 3000, message: String = "", condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun awaitPhase(phase: ConsoleLifecyclePhase) {
        assertTrue("console phase $phase", waitFor { console.state.phase == phase })
    }

    private fun awaitEntryCount(count: Int) {
        assertTrue("entries >= $count", waitFor { console.state.entries.size >= count })
    }

    private fun awaitDomReady() {
        assertTrue("dom ready", waitFor { domInspector.state() == DomState.READY })
    }

    /** Binds the Console to the session and enables Runtime + Console domains. */
    private fun bindConsole() {
        console.bindSession(session)
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-1","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-1","result":{}}""")
        awaitPhase(ConsoleLifecyclePhase.READY)
    }

    /** Binds the DOM inspector and loads a small document with nodes 4 (DIV#box) and 5 (P#para). */
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
                 "children":[{"nodeId":3,"nodeType":1,"nodeName":"BODY","childNodeCount":2,
                   "children":[
                     {"nodeId":4,"backendNodeId":104,"nodeType":1,"nodeName":"DIV","attributes":["id","box"],"childNodeCount":0},
                     {"nodeId":5,"backendNodeId":105,"nodeType":1,"nodeName":"P","attributes":["id","para"],"childNodeCount":0}
                   ]}]}]}}}"""
        )
        awaitDomReady()
    }

    private fun selectNode(nodeId: Int) {
        domInspector.selectNode(nodeId)
    }

    private fun selectedNodeFromTree(nodeId: Int): DomNode? = domInspector.tree[nodeId]

    /** Responds to the most recent DOM.resolveNode command. */
    private fun respondResolve(remoteObjectJson: String) {
        val id = parseIdFor("DOM.resolveNode")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"object":$remoteObjectJson}}""")
    }

    private fun respondResolveError(code: Int, message: String) {
        val id = parseIdFor("DOM.resolveNode")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":$code,"message":"$message"}}""")
    }

    // ================================================================
    // $0 selected-element reference
    // ================================================================

    @Test
    fun selectionChange_tracksAndResolvesSelectedElement() {
        bindConsole()
        bindDomLoaded()
        transport.resetSentMessages()
        selectNode(4)
        // A DOM.resolveNode command is sent for the selected node.
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        respondResolve("""{"type":"object","subtype":"node","className":"HTMLDivElement","description":"div#box","objectId":"node-4"}""")
        assertTrue(waitFor { console.state.selectedElement?.phase == SelectedElementPhase.LOADED })
        val ref = console.state.selectedElement!!
        assertEquals(4, ref.nodeId)
        assertEquals(104, ref.backendNodeId)
        assertEquals("DIV#box", ref.label)
        assertEquals(session.sessionId, ref.sessionId)
        assertTrue(ref.isAvailable)
        assertEquals("node-4", ref.remoteObject?.objectId)
        assertEquals("HTMLDivElement", ref.remoteObject?.className)
    }

    @Test
    fun selectionChange_resolvesAgainstNewSelection() {
        bindConsole()
        bindDomLoaded()
        selectNode(4)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        respondResolve("""{"type":"object","subtype":"node","className":"HTMLDivElement","description":"div#box","objectId":"node-4"}""")
        assertTrue(waitFor { console.state.selectedElement?.phase == SelectedElementPhase.LOADED })

        transport.resetSentMessages()
        selectNode(5)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        respondResolve("""{"type":"object","subtype":"node","className":"HTMLParagraphElement","description":"p#para","objectId":"node-5"}""")
        assertTrue(waitFor { console.state.selectedElement?.nodeId == 5 && console.state.selectedElement?.phase == SelectedElementPhase.LOADED })
        assertEquals("node-5", console.state.selectedElement?.remoteObject?.objectId)
        assertFalse(console.state.selectedElement?.label == "DIV#box")
    }

    @Test
    fun selectionCleared_removesReference() {
        bindConsole()
        bindDomLoaded()
        selectNode(4)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        respondResolve("""{"type":"object","subtype":"node","description":"div#box","objectId":"node-4"}""")
        assertTrue(waitFor { console.state.selectedElement?.phase == SelectedElementPhase.LOADED })
        domInspector.clearSelection()
        assertTrue(waitFor { console.state.selectedElement == null })
    }

    @Test
    fun resolveFailure_unsupported_mapsToUnsupported() {
        bindConsole()
        bindDomLoaded()
        selectNode(4)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        respondResolveError(-32601, "Method not found")
        assertTrue(waitFor { console.state.selectedElement?.phase == SelectedElementPhase.UNSUPPORTED })
    }

    @Test
    fun resolveFailure_stale_mapsToStale() {
        bindConsole()
        bindDomLoaded()
        selectNode(4)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        respondResolveError(-32000, "Could not find node with given id")
        assertTrue(waitFor { console.state.selectedElement?.phase == SelectedElementPhase.STALE })
    }

    @Test
    fun resolveFailure_other_mapsToFailed() {
        bindConsole()
        bindDomLoaded()
        selectNode(4)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        respondResolveError(-32000, "Unexpected problem")
        assertTrue(waitFor { console.state.selectedElement?.phase == SelectedElementPhase.FAILED })
    }

    @Test
    fun noSelection_resolveSelectedElement_remainsNull() {
        bindConsole()
        bindDomLoaded()
        console.resolveSelectedElement()
        Thread.sleep(100)
        assertNull(console.state.selectedElement)
    }

    @Test
    fun detach_clearsSelectionReference() {
        bindConsole()
        bindDomLoaded()
        selectNode(4)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("DOM.resolveNode") } })
        respondResolve("""{"type":"object","subtype":"node","objectId":"node-4"}""")
        assertTrue(waitFor { console.state.selectedElement?.phase == SelectedElementPhase.LOADED })
        console.detach()
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.INACTIVE })
        assertNull(console.state.selectedElement)
    }

    // ================================================================
    // Query helpers
    // ================================================================

    private fun deliverEvaluateSuccess(rawResultObj: String) {
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":$rawResultObj}}"""
        )
    }

    @Test
    fun runQuerySelector_sendsPageExpression_andRecordsCdpBackedEntry() {
        bindConsole()
        transport.resetSentMessages()
        console.runQuerySelector("#box")
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Runtime.evaluate") } })
        assertEquals("document.querySelector(\"#box\")", lastEvaluateExpression())
        assertTrue(transport.sentMessages.last().contains("\"sessionId\":\"session-1\""))
        assertTrue(waitFor { console.state.queryHelperState.running })

        deliverEvaluateSuccess("""{"type":"object","subtype":"node","className":"HTMLDivElement","description":"div#box","objectId":"node-4"}""")
        awaitEntryCount(1)
        assertTrue(waitFor { !console.state.queryHelperState.running })
        val entry = console.state.entries[0]
        assertEquals(ConsoleEntryKind.EVALUATION_RESULT, entry.kind)
        assertEquals(ConsoleFeatureOrigin.CDP_BACKED, entry.origin)
        assertEquals("querySelector", entry.helperLabel)
        assertEquals("div#box", entry.displayText)
        assertNotNull(entry.evaluation)
        assertTrue(entry.evaluation!!.isSuccess)
        assertEquals("node-4", (entry.evaluation!!.result as EvaluationResultKind.Success).value.objectId)
        assertEquals("querySelector", console.state.queryHelperState.activeHelper?.displayLabel)
        assertEquals("#box", console.state.queryHelperState.selector)
    }

    @Test
    fun runQuerySelector_noMatch_returnsNullEntry() {
        bindConsole()
        console.runQuerySelector("#missing")
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Runtime.evaluate") } })
        deliverEvaluateSuccess("""{"type":"object","subtype":"null"}""")
        awaitEntryCount(1)
        val entry = console.state.entries[0]
        assertTrue(entry.evaluation!!.isSuccess)
        assertEquals("null", entry.displayText)
        assertEquals("querySelector", entry.helperLabel)
    }

    @Test
    fun runQuerySelector_invalidSelector_recordsExceptionEntry() {
        bindConsole()
        console.runQuerySelector("bad(")
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Runtime.evaluate") } })
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"object","subtype":"error"},
               "exceptionDetails":{"exceptionId":1,"text":"Uncaught","exception":{"type":"object","subtype":"error","description":"SyntaxError: Failed to execute 'querySelector'"}}}}"""
        )
        awaitEntryCount(1)
        val entry = console.state.entries[0]
        assertTrue(entry.evaluation!!.isException)
        assertEquals("querySelector", entry.helperLabel)
        assertEquals(ConsoleFeatureOrigin.CDP_BACKED, entry.origin)
    }

    @Test
    fun runQuerySelector_protocolError_recordsErrorEntry() {
        bindConsole()
        console.runQuerySelector("#x")
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Runtime.evaluate") } })
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32001,"message":"Session not found"}}""")
        awaitEntryCount(1)
        val entry = console.state.entries[0]
        assertTrue(entry.evaluation!!.isProtocolError)
        assertEquals("querySelector", entry.helperLabel)
        assertEquals(ConsoleFeatureOrigin.CDP_BACKED, entry.origin)
        assertTrue(entry.displayText.contains("Session not found"))
    }

    @Test
    fun runQuerySelectorAll_sendsPluralQuery() {
        bindConsole()
        transport.resetSentMessages()
        console.runQuerySelectorAll(".item")
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Runtime.evaluate") } })
        assertEquals("document.querySelectorAll(\".item\")", lastEvaluateExpression())
        deliverEvaluateSuccess("""{"type":"object","subtype":"array","className":"NodeList","description":"NodeList(2)","objectId":"nl-1"}""")
        awaitEntryCount(1)
        assertEquals("querySelectorAll", console.state.entries[0].helperLabel)
        assertTrue(console.state.entries[0].displayText.contains("NodeList"))
    }

    @Test
    fun queryResult_afterDetach_isDropped() {
        bindConsole()
        console.runQuerySelector("#box")
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Runtime.evaluate") } })
        val id = parseIdFor("Runtime.evaluate")
        console.detach()
        // The response for the detached session must not be recorded.
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"object","subtype":"null"}}}"""
        )
        Thread.sleep(200)
        assertEquals(0, console.state.entries.size)
    }

    // ================================================================
    // Input / command history
    // ================================================================

    private fun deliverEvaluatePrimitive(valueJson: String) {
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"result":{"type":"number","value":$valueJson,"description":"$valueJson"}}}""")
    }

    @Test
    fun submitInput_recordsHistory_evaluatesAndClearsInput() {
        bindConsole()
        transport.resetSentMessages()
        console.setInput("1 + 1")
        console.submitInput()
        awaitEntryCount(1)
        assertTrue(waitFor { console.state.historyEntries == listOf("1 + 1") })
        assertEquals("", console.state.input)
        assertTrue(console.state.history.isLive)
        // Evaluation uses Chrome's command-line API and awaitPromise so promises resolve natively.
        val sent = transport.sentMessages.first { it.contains("Runtime.evaluate") }
        assertTrue(sent.contains("\"includeCommandLineAPI\":true"))
        assertTrue(sent.contains("\"expression\":\"1 + 1\""))
        assertTrue(sent.contains("\"awaitPromise\":true"))
        assertEquals(ConsoleFeatureOrigin.NATIVE_CHROME, console.state.entries[0].origin)
        assertEquals(ConsoleEntryKind.COMMAND, console.state.entries[0].kind)
        assertEquals("1 + 1", console.state.entries[0].text)
    }

    @Test
    fun submitInput_multilineCommand_preservesMultilineText() {
        bindConsole()
        val multiline = "const a = 1;\nconst b = 2;\na + b;"
        console.setInput(multiline)
        console.submitInput()
        awaitEntryCount(1)
        val commandEntry = console.state.entries.first { it.isCommand }
        assertEquals(multiline, commandEntry.text)
        assertEquals(multiline, commandEntry.displayText)
    }

    @Test
    fun submitInput_blankExpression_isNoop() {
        bindConsole()
        transport.resetSentMessages()
        console.setInput("   ")
        console.submitInput()
        Thread.sleep(100)
        assertTrue(console.state.historyEntries.isEmpty())
        assertFalse(transport.sentMessages.any { it.contains("Runtime.evaluate") })
    }

    @Test
    fun history_dedupesConsecutiveDuplicates() {
        bindConsole()
        console.setInput("a")
        console.submitInput()
        awaitEntryCount(1)
        // Wait until history reflects the record.
        assertTrue(waitFor { console.state.historyEntries == listOf("a") })
        console.setInput("a")
        console.submitInput()
        awaitEntryCount(2)
        assertTrue(waitFor { console.state.historyEntries == listOf("a") })
    }

    @Test
    fun historyNavigation_changesInputOnly_noEvaluation() {
        bindConsole()
        console.setInput("1 + 1")
        console.submitInput()
        awaitEntryCount(1)
        assertTrue(waitFor { console.state.historyEntries == listOf("1 + 1") })
        console.setInput("2 + 2")
        console.submitInput()
        awaitEntryCount(2)
        assertTrue(waitFor { console.state.historyEntries == listOf("1 + 1", "2 + 2") })

        transport.resetSentMessages()

        console.historyBack()
        assertTrue(waitFor { console.state.historyPosition == 1 })
        assertEquals("2 + 2", console.state.input)

        console.historyBack()
        assertTrue(waitFor { console.state.input == "1 + 1" && console.state.historyPosition == 0 })

        // At the oldest entry: further back is a no-op.
        console.historyBack()
        Thread.sleep(100)
        assertEquals("1 + 1", console.state.input)

        console.historyForward()
        assertTrue(waitFor { console.state.input == "2 + 2" })

        // Forward past the newest returns to the live input.
        console.historyForward()
        assertTrue(waitFor { console.state.historyPosition == -1 && console.state.input == "" })

        // Navigation never executes an expression.
        assertFalse(transport.sentMessages.any { it.contains("Runtime.evaluate") })
    }

    @Test
    fun editingWhileNavigating_returnsToLive() {
        bindConsole()
        console.setInput("a")
        console.submitInput()
        awaitEntryCount(1)
        assertTrue(waitFor { console.state.historyEntries == listOf("a") })
        console.setInput("b")
        console.submitInput()
        awaitEntryCount(2)
        assertTrue(waitFor { console.state.historyEntries == listOf("a", "b") })

        console.historyBack()
        assertTrue(waitFor { console.state.input == "b" && console.state.historyPosition == 1 })
        console.setInput("custom")
        assertTrue(waitFor { console.state.historyPosition == -1 && console.state.input == "custom" })
    }

    @Test
    fun targetSwitch_clearsHistoryInputAndSelection() {
        bindConsole()
        bindDomLoaded()
        selectNode(4)
        assertTrue(waitFor { console.state.selectedElement != null })
        console.setInput("1")
        console.submitInput()
        awaitEntryCount(1)
        assertTrue(waitFor { console.state.historyEntries.isNotEmpty() })

        console.detach()
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.INACTIVE })
        assertNull(console.state.selectedElement)
        assertTrue(console.state.historyEntries.isEmpty())
        assertEquals("", console.state.input)
        assertEquals(0, console.state.entries.size)
    }
}