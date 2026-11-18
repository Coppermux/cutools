package com.coppermux.cutools.core.dom

import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.error.DomException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Overall state of the DOM inspector.
 */
enum class DomState {
    /** Not bound to any target/session. */
    INACTIVE,

    /** Enabling the DOM domain / loading the document. */
    LOADING,

    /** Document loaded and ready to inspect. */
    READY,

    /** Target is attached but DOM could not be enabled/loaded. */
    ERROR,
}

/**
 * Result of a DOM operation executed against the session.
 */
sealed interface DomOperationResult<out T> {
    data class Success<T>(val value: T) : DomOperationResult<T>
    data class Failure(val error: Throwable) : DomOperationResult<Nothing>
}

/** Listener for DOM inspector state / tree changes. */
interface DomInspectorListener {
    fun onStateChanged(state: DomState, error: Throwable?)
    fun onDocumentLoaded(document: DomNode?)
    fun onNodesChanged(changedNodeIds: Set<Int>)
    fun onSelectionChanged(node: DomNode?)
    fun onInspectionStateChanged(state: DomInspectionState)
    fun onSearchStateChanged(state: DomSearchState)
    fun onMutationStateChanged(state: DomMutationState) {}
    fun onError(error: Throwable)
}

/**
 * Orchestrates DOM inspection for the currently attached Chrome page target.
 *
 * - Enables the DOM domain on the active CDP session.
 * - Retrieves the document root with lazy child loading.
 * - Maintains a [DomTree] and applies CDP DOM events.
 * - Expands nodes lazily via `DOM.requestChildNodes`.
 * - Tracks a single selected node.
 * - Read-only inspection: structured attributes, node metadata, outer HTML
 *   via `DOM.getOuterHTML`, and DOM search with result navigation (Phase 2B).
 * - Handles target/session changes and connection loss.
 *
 * All Chrome communication reuses the Phase 1 [CommandDispatcher], sending
 * commands on the attached target's session ID. No second WebSocket is created.
 *
 * Thread-safety: DOM state is mutated on a single-thread executor; listeners are
 * invoked on that executor thread, so UI callers must marshal onto the UI thread.
 */
class DomInspector(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
) {

    /** The in-memory DOM tree model. */
    val tree = DomTree()

    /** Read-only selected-node inspection state (metadata + outer HTML). */
    @Volatile
    var inspectionState: DomInspectionState = DomInspectionState.empty()
        private set

    /** Read-only search state (query, results, active index, loading/error). */
    @Volatile
    var searchState: DomSearchState = DomSearchState.empty()
        private set

    /** Read-only mutation state (idle / submitting / success / failure). */
    @Volatile
    var mutationState: DomMutationState = DomMutationState.empty()
        private set

    /** Guards against duplicate concurrent submissions of mutations. */
    private val mutationInProgress = AtomicBoolean(false)

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** Generation counter to drop stale async results after detach/re-bind. */
    private val generation = AtomicInteger(0)

    @Volatile
    private var state = DomState.INACTIVE

    private val listeners = CopyOnWriteArrayList<DomInspectorListener>()
    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()
    private val expandedNodeIds = HashSet<Int>()

    /**
     * Tracks a selected node that was removed by a `DOM.childNodeRemoved` event
     * so the selection can be restored if the SAME node (same backendNodeId) is
     * re-inserted (i.e. the node moved). A replacement node with a different
     * backendNodeId is never treated as the old node.
     */
    private var pendingMovedSelection: PendingMove? = null

    private data class PendingMove(val nodeId: Int, val backendNodeId: Int?)

    @Volatile
    private var selectedNodeId: Int? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cutools-dom").apply { isDaemon = true }
    }

    /** Current DOM inspector state. */
    fun state(): DomState = state

    /** The currently selected node, or null. */
    fun selectedNode(): DomNode? = selectedNodeId?.let { tree[it] }

    /** The bound target's ID, or null. */
    fun targetId(): String? = session?.targetId

    fun addListener(listener: DomInspectorListener) { listeners.add(listener) }
    fun removeListener(listener: DomInspectorListener) { listeners.remove(listener) }

    fun isExpanded(nodeId: Int): Boolean = expandedNodeIds.contains(nodeId)
    fun expandedIds(): Set<Int> = expandedNodeIds.toSet()

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session and initializes DOM inspection. */
    fun bindSession(newSession: SessionInfo) {
        val gen = generation.incrementAndGet()
        session = newSession
        clearSubscriptions()
        registerDomSubscriptions()
        state = DomState.LOADING
        notifyStateChanged(DomState.LOADING, null)
        tree.clear()
        clearSelectionInternal()
        resetInspectionState()
        resetSearchState()
        resetMutationState()
        expandedNodeIds.clear()
        pendingMovedSelection = null

        send("DOM.enable", emptyMap(), gen) { result ->
            when (result) {
                is CommandResult.Success -> getDocument(gen)
                is CommandResult.Failure -> {
                    setError(result.error)
                    notifyError(result.error)
                }
            }
        }
    }

    /** Detaches, clearing all DOM state without issuing further commands. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        clearSubscriptions()
        clear()
    }

    /** Clears all cached DOM tree nodes, selections, search and inspection state. */
    fun clear() {
        generation.incrementAndGet()
        tree.clear()
        clearSelectionInternal()
        resetInspectionState()
        resetSearchState()
        resetMutationState()
        expandedNodeIds.clear()
        pendingMovedSelection = null
        setState(DomState.INACTIVE, null)
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    // ================================================================
    // Document / tree loading
    // ================================================================

    private fun getDocument(gen: Int) {
        if (!isCurrent(gen)) return
        send("DOM.getDocument", mapOf("depth" to 1, "pierce" to false), gen) { result ->
            if (!isCurrent(gen)) return@send
            when (result) {
                is CommandResult.Success -> {
                    val root = (result.response.result as? Map<*, *>)?.get("root")
                    if (root == null) {
                        setError(DomException("DOM.getDocument returned no root"))
                        notifyError(DomException("DOM.getDocument returned no root"))
                        return@send
                    }
                    val parsedRoot = try {
                        DomParser.parseNode(root)
                    } catch (e: Exception) {
                        setError(e)
                        notifyError(e)
                        return@send
                    }
                    executor.execute {
                        if (!isCurrent(gen)) return@execute
                        tree.setRoot(parsedRoot)
                        // A selection referencing a node that no longer exists after
                        // the rebuild must be cleared (never left dangling).
                        if (selectedNodeId != null && tree[selectedNodeId!!] == null) {
                            clearSelection()
                        }
                        setState(DomState.READY, null)
                        notifyDocumentLoaded(tree.document)
                        notifyNodesChanged(setOf(parsedRoot.nodeId) + parsedRoot.children.map { it.nodeId })
                    }
                }
                is CommandResult.Failure -> {
                    setError(result.error)
                    notifyError(result.error)
                }
            }
        }
    }

    /** Expands a node, fetching its children lazily via `DOM.requestChildNodes` when not yet loaded. */
    fun expandNode(nodeId: Int) {
        if (session == null) return
        val node = tree[nodeId] ?: return
        expandedNodeIds.add(nodeId)
        // If all children are already loaded, no fetch is required.
        if (!tree.hasUnloadedChildren(nodeId)) {
            notifyNodesChanged(setOf(nodeId))
            return
        }
        val gen = generation.get()
        send("DOM.requestChildNodes", mapOf("nodeId" to nodeId, "depth" to 1), gen) { result ->
            if (!isCurrent(gen)) return@send
            if (result is CommandResult.Failure) notifyError(result.error)
            // Children arrive via the DOM.setChildNodes event.
        }
    }

    /** Collapses a node. */
    fun collapseNode(nodeId: Int) {
        expandedNodeIds.remove(nodeId)
        notifyNodesChanged(setOf(nodeId))
    }

    /** Toggles expansion of a node. */
    fun toggleExpand(nodeId: Int) {
        if (expandedNodeIds.contains(nodeId)) collapseNode(nodeId) else expandNode(nodeId)
    }

    // ================================================================
    // Selection
    // ================================================================

    /** Selects a node by ID and reflects it into Chrome via `DOM.setInspectedNode`. */
    fun selectNode(nodeId: Int) {
        if (session == null) return
        val node = tree[nodeId] ?: run {
            notifyError(DomException("Cannot select unknown node $nodeId"))
            return
        }
        // An explicit selection supersedes any pending move-restoration.
        pendingMovedSelection = null
        selectedNodeId = nodeId
        inspectionState = inspectionState.forNode(node)
        notifySelectionChanged(node)
        notifyInspectionStateChanged()
        val gen = generation.get()
        send("DOM.setInspectedNode", mapOf("nodeId" to nodeId), gen) { }
    }

    fun clearSelection() {
        clearSelectionInternal()
        notifySelectionChanged(null)
    }

    private fun clearSelectionInternal() {
        selectedNodeId = null
        inspectionState = DomInspectionState.empty()
    }

    private fun resetInspectionState() {
        inspectionState = DomInspectionState.empty()
        notifyInspectionStateChanged()
    }

    private fun resetSearchState() {
        searchState = DomSearchState.empty()
        notifySearchStateChanged()
    }

    private fun resetMutationState() {
        mutationInProgress.set(false)
        mutationState = DomMutationState.empty()
        notifyMutationStateChanged()
    }

    // ================================================================
    // Basic node inspection (read-only)
    // ================================================================

    /**
     * Retrieves the selected node's authoritative outer HTML via `DOM.getOuterHTML`
     * and publishes the result into [inspectionState].
     *
     * Phase 2B: handles success, empty result, protocol failure, and only reports
     * results that are current to the active selection/generation. Never
     * fabricates HTML locally when Chrome can provide it.
     */
    fun refreshOuterHTML() {
        val nodeId = selectedNodeId
        if (nodeId == null) {
            updateInspectionState(inspectionState.copy(outerHtmlLoading = false, outerHtml = null, outerHtmlError = null))
            return
        }
        if (session == null) {
            updateInspectionState(inspectionState.copy(outerHtmlLoading = false, outerHtmlError = ConnectionException("Not attached to a target")))
            return
        }
        // Mark loading.
        updateInspectionState(inspectionState.copy(outerHtmlLoading = true, outerHtml = null, outerHtmlError = null))
        val gen = generation.get()
        sendOnSelectedNode("DOM.getOuterHTML", emptyMap()) { result ->
            if (!isCurrent(gen)) return@sendOnSelectedNode // stale response after target change
            when (result) {
                is DomOperationResult.Success -> {
                    val outer = (result.value as? Map<*, *>)?.get("outerHTML") as? String
                    updateInspectionState(
                        inspectionState.copy(outerHtmlLoading = false, outerHtml = outer, outerHtmlError = null)
                    )
                }
                is DomOperationResult.Failure -> {
                    updateInspectionState(
                        inspectionState.copy(outerHtmlLoading = false, outerHtml = null, outerHtmlError = result.error)
                    )
                    notifyError(result.error)
                }
            }
        }
    }

    /**
     * One-shot outer HTML retrieval (kept for callers that need the string via
     * callback). Publishes the current loading/result state as a side effect.
     */
    fun getOuterHTML(callback: (DomOperationResult<String>) -> Unit) {
        val nodeId = selectedNodeId
        if (nodeId == null) {
            callback(DomOperationResult.Failure(DomException("No node selected")))
            return
        }
        if (session == null) {
            callback(DomOperationResult.Failure(ConnectionException("Not attached to a target")))
            return
        }
        val gen = generation.get()
        sendOnSelectedNode("DOM.getOuterHTML", emptyMap()) { result ->
            if (!isCurrent(gen)) return@sendOnSelectedNode
            when (result) {
                is DomOperationResult.Success -> {
                    val outer = (result.value as? Map<*, *>)?.get("outerHTML") as? String
                    updateInspectionState(inspectionState.copy(outerHtmlLoading = false, outerHtml = outer, outerHtmlError = null))
                    if (outer != null) callback(DomOperationResult.Success(outer))
                    else callback(DomOperationResult.Failure(DomException("DOM.getOuterHTML returned no outerHTML")))
                }
                is DomOperationResult.Failure -> {
                    updateInspectionState(inspectionState.copy(outerHtmlLoading = false, outerHtml = null, outerHtmlError = result.error))
                    notifyError(result.error)
                    callback(result)
                }
            }
        }
    }

    /** Re-fetches the inspected document root. */
    fun refresh() {
        if (session == null) return
        setState(DomState.LOADING, null)
        val gen = generation.get()
        getDocument(gen)
    }

    // ================================================================
    // Search (read-only DOM search with result navigation)
    // ================================================================

    /**
     * Runs a DOM search over the currently loaded DOM state.
     *
     * Supports tag, #id, .class, attr=value, attr (presence), and text.
     * Results reference real Chrome nodes (never fabricated). Search state is
     * published to [searchState] on completion.
     */
    fun performSearch(query: String) {
        if (session == null) {
            updateSearchState(DomSearchState(query = query, error = ConnectionException("Not attached to a target")))
            return
        }
        val q = query.trim()
        if (q.isEmpty()) {
            updateSearchState(DomSearchState.empty())
            return
        }
        updateSearchState(DomSearchState(query = q, loading = true))
        val gen = generation.get()
        executor.execute {
            if (!isCurrent(gen)) return@execute
            val matching = tree.allNodes.filter { nodeMatches(it, q) }
            if (!isCurrent(gen)) return@execute
            val active = if (matching.isEmpty()) -1 else 0
            updateSearchState(DomSearchState(query = q, results = matching, activeIndex = active, loading = false))
        }
    }

    /** Backward-compatible one-shot search returning results via callback. */
    fun search(query: String, callback: (DomOperationResult<List<DomNode>>) -> Unit) {
        if (session == null) {
            callback(DomOperationResult.Failure(ConnectionException("Not attached to a target")))
            return
        }
        val q = query.trim()
        if (q.isEmpty()) {
            callback(DomOperationResult.Success(emptyList()))
            return
        }
        callback(DomOperationResult.Success(tree.allNodes.filter { nodeMatches(it, q) }))
    }

    /** Advances to the next search result (wraps), selecting it. */
    fun searchNext() {
        val s = searchState
        if (s.results.isEmpty()) return
        val next = s.withNext()
        updateSearchState(next)
        next.activeResult?.let { ensureAndSelectResult(it.nodeId) }
    }

    /** Moves to the previous search result (wraps), selecting it. */
    fun searchPrevious() {
        val s = searchState
        if (s.results.isEmpty()) return
        val prev = s.withPrevious()
        updateSearchState(prev)
        prev.activeResult?.let { ensureAndSelectResult(it.nodeId) }
    }

    /** Selects a specific search result by index (ensures it is expanded/loaded). */
    fun selectSearchResult(index: Int) {
        val s = searchState
        if (index !in s.results.indices) return
        updateSearchState(s.copy(activeIndex = index))
        s.results[index].let { ensureAndSelectResult(it.nodeId) }
    }

    private fun ensureAndSelectResult(nodeId: Int) {
        // If the node isn't loaded yet, attempt to load its ancestor path where
        // practical before selecting. For already-loaded nodes we select directly.
        if (tree[nodeId] == null) {
            // Load the path by expanding ancestors that have unloaded children.
            val path = ancestorPathToLoad(nodeId)
            path.forEach { expandNode(it) }
        }
        selectNode(nodeId)
    }

    /** Returns the ancestor nodeIds (leaf→root, minus the node) that need expansion. */
    private fun ancestorPathToLoad(nodeId: Int): List<Int> {
        // If we don't know the node or its ancestors, we cannot fabricate a path
        // (Phase 2B never creates fake nodes). Return empty — selection will
        // simply fail gracefully if the node is genuinely absent.
        if (tree[nodeId] == null) return emptyList()
        val path = ArrayList<Int>()
        var current = tree.parentOf(nodeId)
        var guard = 0
        while (current != null && guard < 256) {
            if (tree.hasUnloadedChildren(current.nodeId)) path.add(current.nodeId)
            current = tree.parentOf(current.nodeId)
            guard++
        }
        return path
    }

    fun clearSearch() {
        resetSearchState()
    }

    // ================================================================
    // Mutation (Phase 2C — DOM Live Editing)
    // ================================================================
    //
    // All mutations execute against the REAL Chrome target via CDP. Local
    // state is updated ONLY after Chrome confirms success. Each operation is
    // tied to the active session/generation, the node ID, and the operation so
    // a stale result is never applied to a different node/target.

    /**
     * Changes the value of an existing attribute on the given node.
     * Local state is updated only on Chrome success.
     */
    fun setAttributeValue(nodeId: Int, name: String, value: String, callback: (DomOperationResult<Unit>) -> Unit) {
        runMutation(nodeId, DomMutationOperation.SET_ATTRIBUTE, "DOM.setAttributeValue",
            params = { mapOf("name" to name, "value" to value) },
            onSuccess = {
                tree.updateAttributeValue(nodeId, name, value)
                notifyNodesChanged(setOf(nodeId))
                resyncSelectedNodeInspection(nodeId)
            },
            callback = callback,
        )
    }

    /**
     * Adds (or replaces) an attribute on the given element.
     * Uses `DOM.setAttributesAsText`, passing the serialized text that includes
     * all existing attributes so unrelated attributes are preserved.
     */
    fun addAttribute(nodeId: Int, name: String, value: String, callback: (DomOperationResult<Unit>) -> Unit) {
        runMutation(nodeId, DomMutationOperation.ADD_ATTRIBUTE, "DOM.setAttributesAsText",
            params = {
                val node = tree[nodeId]
                val text = buildString {
                    node?.attributes?.forEach { append(it.name).append('=').append(quote(it.value ?: "")).append(' ') }
                    append(name).append('=').append(quote(value))
                }
                mapOf("name" to name, "text" to text)
            },
            onSuccess = {
                tree.setAttribute(nodeId, name, value)
                notifyNodesChanged(setOf(nodeId))
                resyncSelectedNodeInspection(nodeId)
            },
            callback = callback,
        )
    }

    /** Removes the named attribute from the given element. */
    fun removeAttribute(nodeId: Int, name: String, callback: (DomOperationResult<Unit>) -> Unit) {
        runMutation(nodeId, DomMutationOperation.REMOVE_ATTRIBUTE, "DOM.removeAttribute",
            params = { mapOf("name" to name) },
            onSuccess = {
                tree.removeAttribute(nodeId, name)
                notifyNodesChanged(setOf(nodeId))
                resyncSelectedNodeInspection(nodeId)
            },
            callback = callback,
        )
    }

    /** Sets the value of a text-like node (text or comment). */
    fun setTextNodeValue(nodeId: Int, value: String, callback: (DomOperationResult<Unit>) -> Unit) {
        val node = tree[nodeId]
        if (node == null) {
            callback(DomOperationResult.Failure(DomException("Cannot edit unknown node $nodeId")))
            return
        }
        if (!node.isText && !node.isComment) {
            callback(DomOperationResult.Failure(DomException("Node $nodeId is not a text node; text editing is not valid here")))
            return
        }
        runMutation(nodeId, DomMutationOperation.SET_TEXT, "DOM.setNodeValue",
            params = { mapOf("value" to value) },
            onSuccess = {
                tree.updateNodeValue(nodeId, value)
                notifyNodesChanged(setOf(nodeId))
                resyncSelectedNodeInspection(nodeId)
            },
            callback = callback,
        )
    }

    /**
     * Replaces the selected node's outer HTML via `DOM.setOuterHTML`.
     *
     * The original nodeId may become invalid after replacement, so we do NOT
     * attempt to reconstruct the replacement subtree locally. Instead we
     * re-resolve the affected DOM through Chrome (a document refresh) and clear
     * selection if the node no longer exists.
     */
    fun setOuterHTML(nodeId: Int, html: String, callback: (DomOperationResult<Unit>) -> Unit) {
        if (html.isBlank()) {
            callback(DomOperationResult.Failure(DomException("Outer HTML must not be empty")))
            return
        }
        runMutation(nodeId, DomMutationOperation.SET_OUTER_HTML, "DOM.setOuterHTML",
            params = { mapOf("outerHTML" to html) },
            onSuccess = {
                // The node id may be stale after replacement — re-resolve from Chrome.
                refreshAfterOuterHtml(nodeId)
            },
            callback = callback,
        )
    }

    /** Deletes the given node from the live page. The document root is never deleted. */
    fun removeNode(nodeId: Int, callback: (DomOperationResult<Unit>) -> Unit) {
        val node = tree[nodeId]
        if (node == null) {
            callback(DomOperationResult.Failure(DomException("Cannot delete unknown node $nodeId")))
            return
        }
        if (nodeId == tree.documentNodeId || node.isDocument) {
            callback(DomOperationResult.Failure(DomException("The document root cannot be deleted")))
            return
        }
        runMutation(nodeId, DomMutationOperation.REMOVE_NODE, "DOM.removeNode",
            params = { emptyMap() },
            onSuccess = {
                val parentId = tree.removeNode(nodeId)
                if (parentId != null) notifyNodesChanged(setOf(parentId))
                // Selection must be cleared if the selected node was deleted.
                if (selectedNodeId == nodeId) clearSelection()
                resyncSelectedNodeInspection(nodeId)
            },
            callback = callback,
        )
    }

    /**
     * Shared mutation runner. Validates session + node, prevents duplicate
     * concurrent submissions, sends the CDP command tied to the current
     * generation/node, and reconciles local state only on Chrome success.
     */
    private fun runMutation(
        nodeId: Int,
        operation: DomMutationOperation,
        method: String,
        params: () -> Map<String, Any?>,
        onSuccess: () -> Unit,
        callback: (DomOperationResult<Unit>) -> Unit,
    ) {
        if (session == null) {
            callback(DomOperationResult.Failure(ConnectionException("Not attached to a target")))
            return
        }
        if (tree[nodeId] == null) {
            callback(DomOperationResult.Failure(DomException("Node $nodeId is not present in the inspected DOM")))
            return
        }
        if (!mutationInProgress.compareAndSet(false, true)) {
            callback(DomOperationResult.Failure(DomException("A mutation is already in progress")))
            return
        }
        val gen = generation.get()
        updateMutationState(DomMutationState(DomMutationPhase.SUBMITTING, operation))
        send(method, mapOf("nodeId" to nodeId) + params(), gen) { result ->
            // Reconcile local state on the single-thread executor so tree access is
            // serialized with DOM event processing. `send` already dropped stale
            // responses (generation mismatch) so only current-session results run.
            executor.execute {
                when (result) {
                    is CommandResult.Success -> {
                        mutationInProgress.set(false)
                        updateMutationState(DomMutationState(DomMutationPhase.SUCCESS, operation))
                        onSuccess()
                        callback(DomOperationResult.Success(Unit))
                    }
                    is CommandResult.Failure -> {
                        mutationInProgress.set(false)
                        updateMutationState(DomMutationState(DomMutationPhase.FAILURE, operation, result.error))
                        notifyError(result.error)
                        callback(DomOperationResult.Failure(result.error))
                    }
                }
            }
        }
    }

    /** Refreshes the document from Chrome after an outer-HTML replacement. */
    private fun refreshAfterOuterHtml(originalNodeId: Int) {
        if (session == null) return
        setState(DomState.LOADING, null)
        val gen = generation.get()
        getDocument(gen)
        // If the original selected node is no longer present after refresh, clear selection.
        if (selectedNodeId != null && tree[selectedNodeId!!] == null) {
            clearSelection()
        }
    }

    /** Refreshes the inspection detail if the mutated node is still the selection. */
    private fun resyncSelectedNodeInspection(nodeId: Int) {
        if (selectedNodeId == nodeId) {
            val node = tree[nodeId]
            if (node != null) {
                inspectionState = inspectionState.forNode(node)
                notifySelectionChanged(node)
                notifyInspectionStateChanged()
            } else if (selectedNodeId == nodeId) {
                clearSelection()
            }
        }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun nodeMatches(node: DomNode, query: String): Boolean {
        val q = query
        if (q.startsWith("#")) {
            return node.attribute("id") == q.substring(1)
        }
        if (q.startsWith(".")) {
            val cls = q.substring(1)
            return node.attribute("class")?.split(Regex("\\s+"))?.contains(cls) == true
        }
        if (q.contains("=")) {
            val (name, value) = q.split("=", limit = 2)
            val attr = node.attributes.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            if (attr != null) {
                return value.isEmpty() || attr.value?.contains(value.trim(), ignoreCase = true) == true
            }
            return false
        }
        // Tag name match (case-insensitive)
        if (node.tagName?.equals(q, ignoreCase = true) == true) return true
        // Attribute presence match when query equals an attribute name
        if (node.attributes.any { it.name.equals(q, ignoreCase = true) }) return true
        // Otherwise: text / combined haystack search
        val haystack = buildString {
            append(node.nodeName ?: "")
            append(' ')
            append(node.nodeValue ?: "")
            node.attributes.forEach { append(' ').append(it.name).append('=').append(it.value ?: "") }
        }.lowercase()
        return haystack.contains(q.lowercase()) && q.isNotEmpty()
    }

    // ================================================================
    // DOM event subscription
    // ================================================================

    private fun registerDomSubscriptions() {
        subscriptions += eventRouter.subscribe("DOM.documentUpdated") { event ->
            onEvent(event) { params -> handleDocumentUpdated() }
        }
        subscriptions += eventRouter.subscribe("DOM.setChildNodes") { event ->
            onEvent(event) { params -> handleSetChildNodes(params) }
        }
        subscriptions += eventRouter.subscribe("DOM.childNodeInserted") { event ->
            onEvent(event) { params -> handleChildNodeInserted(params) }
        }
        subscriptions += eventRouter.subscribe("DOM.childNodeRemoved") { event ->
            onEvent(event) { params -> handleChildNodeRemoved(params) }
        }
        subscriptions += eventRouter.subscribe("DOM.attributeModified") { event ->
            onEvent(event) { params -> handleAttributeModified(params) }
        }
        subscriptions += eventRouter.subscribe("DOM.attributeRemoved") { event ->
            onEvent(event) { params -> handleAttributeRemoved(params) }
        }
        subscriptions += eventRouter.subscribe("DOM.characterDataModified") { event ->
            onEvent(event) { params -> handleCharacterDataModified(params) }
        }
    }

    // ================================================================
    // DOM event handlers (Phase 2D — live DOM synchronization)
    // ================================================================
    //
    // Each handler runs on the single-thread executor (serialized tree access),
    // tolerates unknown/missing nodes, and never crashes on malformed payloads.
    // All handlers reconcile selection, inspection, and search state so external
    // page mutations become visible without a manual refresh.

    /**
     * `DOM.setChildNodes` — replaces the parent's children with Chrome's
     * authoritative list, establishing parent relationships and removing stale
     * children (and their subtrees).
     */
    private fun handleSetChildNodes(params: Map<*, *>) {
        val parentId = intParam(params, "parentId") ?: return
        val rawNodes = params["nodes"] as? List<*> ?: return
        val children = rawNodes.mapNotNull { n -> if (n is Map<*, *>) DomParser.parseNode(n) else null }
        if (rawNodes.isNotEmpty() && children.isEmpty()) return // malformed payload
        tree.setChildren(parentId, children)
        notifyNodesChanged(children.map { it.nodeId }.toSet() + parentId)
        reconcileSelectionAndSearch()
    }

    /**
     * `DOM.childNodeInserted` — inserts the new node at Chrome's specified index
     * (computed from `previousNodeId`), preserving sibling ordering and never
     * duplicating an existing node.
     */
    private fun handleChildNodeInserted(params: Map<*, *>) {
        val parentId = intParam(params, "parentNodeId") ?: return
        val rawNode = params["node"] as? Map<*, *> ?: return
        val child = try {
            DomParser.parseLeafNode(rawNode, parentId)
        } catch (e: Exception) {
            return
        }
        val previousId = (params["previousNodeId"] as? Number)?.toInt() ?: 0
        val current = tree[parentId]
        val index = when {
            previousId <= 0 -> 0
            else -> {
                val prevIdx = current?.children?.indexOfFirst { it.nodeId == previousId } ?: -1
                if (prevIdx >= 0) prevIdx + 1 else current?.children?.size ?: 0
            }
        }
        if (!tree.insertChild(parentId, index, child)) return
        notifyNodesChanged(setOf(parentId, child.nodeId))
        restoreMovedSelection(child)
        reconcileSelectionAndSearch()
    }

    /**
     * `DOM.childNodeRemoved` — removes the child (and its whole subtree) from the
     * tree. Clears the selection if the removed node was selected (tracking a
     * possible move), and drops stale search results.
     */
    private fun handleChildNodeRemoved(params: Map<*, *>) {
        val childId = intParam(params, "nodeId") ?: return
        val wasSelected = selectedNodeId == childId
        if (wasSelected) {
            val removed = tree[childId]
            pendingMovedSelection = if (removed?.backendNodeId != null) {
                PendingMove(removed.nodeId, removed.backendNodeId)
            } else {
                null
            }
            clearSelection()
        }
        val parentId = tree.removeNode(childId)
        if (parentId != null) notifyNodesChanged(setOf(parentId))
        reconcileSearch()
    }

    /**
     * `DOM.attributeModified` — updates exactly the affected attribute, preserving
     * all unrelated attributes. Refreshes selected-node inspection state and
     * invalidates cached outer HTML when the selected node is affected.
     */
    private fun handleAttributeModified(params: Map<*, *>) {
        val nodeId = intParam(params, "nodeId") ?: return
        val name = params["name"] as? String ?: return
        val value = (params["value"] as? String) ?: ""
        tree.setAttribute(nodeId, name, value)
        notifyNodesChanged(setOf(nodeId))
        refreshSelectedNodeAfterChange(nodeId)
        reconcileSearch()
    }

    /**
     * `DOM.attributeRemoved` — removes exactly the affected attribute, preserving
     * all unrelated attributes.
     */
    private fun handleAttributeRemoved(params: Map<*, *>) {
        val nodeId = intParam(params, "nodeId") ?: return
        val name = params["name"] as? String ?: return
        tree.removeAttribute(nodeId, name)
        notifyNodesChanged(setOf(nodeId))
        refreshSelectedNodeAfterChange(nodeId)
        reconcileSearch()
    }

    /**
     * `DOM.characterDataModified` — updates the text/comment node value and
     * invalidates cached outer HTML for the affected selected node.
     */
    private fun handleCharacterDataModified(params: Map<*, *>) {
        val nodeId = intParam(params, "nodeId") ?: return
        val data = (params["characterData"] as? String) ?: return
        tree.updateNodeValue(nodeId, data)
        notifyNodesChanged(setOf(nodeId))
        refreshSelectedNodeAfterChange(nodeId)
        reconcileSearch()
    }

    /**
     * `DOM.documentUpdated` — discards the entire old tree, clears selection,
     * search, and inspection state, then retrieves a fresh document from Chrome.
     * No node from the previous document survives.
     */
    private fun handleDocumentUpdated() {
        tree.clear()
        expandedNodeIds.clear()
        pendingMovedSelection = null
        clearSelectionInternal()
        notifySelectionChanged(null)
        resetSearchState()
        setState(DomState.LOADING, null)
        val gen = generation.get()
        getDocument(gen)
    }

    // ================================================================
    // Selection / inspection / search reconciliation
    // ================================================================

    /** Refreshes selected-node inspection metadata if [nodeId] is selected. */
    private fun refreshSelectedNodeAfterChange(nodeId: Int) {
        if (selectedNodeId != nodeId) return
        val updated = tree[nodeId]
        if (updated != null) {
            updateInspectionState(inspectionState.copy(node = updated, outerHtml = null))
            notifySelectionChanged(updated)
        } else {
            clearSelection()
        }
    }

    /**
     * Restores the selection when a removed selected node is re-inserted with the
     * SAME backendNodeId (a move). A node with a different backendNodeId is a
     * replacement and is never treated as the old node.
     */
    private fun restoreMovedSelection(child: DomNode) {
        val pending = pendingMovedSelection ?: return
        if (pending.backendNodeId != null && pending.backendNodeId == child.backendNodeId) {
            pendingMovedSelection = null
            selectedNodeId = child.nodeId
            updateInspectionState(inspectionState.forNode(child))
            notifySelectionChanged(child)
            notifyInspectionStateChanged()
        }
    }

    /**
     * Reconciles selection + search after any tree change. Clears selection when
     * the selected node disappears; refreshes inspection metadata when it changes;
     * re-evaluates search results against the live tree (dropping deleted nodes,
     * re-adding changed matches, never selecting deleted nodes).
     */
    private fun reconcileSelectionAndSearch() {
        reconcileSelection()
        reconcileSearch()
    }

    private fun reconcileSelection() {
        val sid = selectedNodeId ?: return
        val node = tree[sid]
        if (node == null) {
            clearSelection()
            return
        }
        val cached = inspectionState.node
        val childrenChanged = cached != null &&
            cached.children.map { it.nodeId } != node.children.map { it.nodeId }
        val contentChanged = cached == null ||
            cached.attributes != node.attributes ||
            cached.nodeValue != node.nodeValue ||
            cached.childNodeCount != node.childNodeCount ||
            childrenChanged
        if (contentChanged) {
            updateInspectionState(inspectionState.copy(node = node, outerHtml = null))
            notifySelectionChanged(node)
        }
    }

    private fun reconcileSearch() {
        val s = searchState
        val q = s.query
        if (q.isBlank()) return
        val previousActiveId = s.activeResult?.nodeId
        val matching = tree.allNodes.filter { nodeMatches(it, q) }
        val activeIndex = when {
            matching.isEmpty() -> -1
            previousActiveId != null -> {
                val idx = matching.indexOfFirst { it.nodeId == previousActiveId }
                if (idx >= 0) idx else 0
            }
            else -> 0
        }
        val newResults = matching.map { it.nodeId }
        val oldResults = s.results.map { it.nodeId }
        if (newResults == oldResults && s.activeIndex == activeIndex) return
        updateSearchState(DomSearchState(query = q, results = matching, activeIndex = activeIndex, loading = false))
    }

    /** Runs an event handler on the executor if the event belongs to the active session. */
    private fun onEvent(event: Event, handler: (Map<*, *>) -> Unit) {
        executor.execute {
            if (event.sessionId != session?.sessionId) return@execute
            val params = event.params
            if (params !is Map<*, *>) return@execute
            try {
                handler(params)
            } catch (e: Exception) {
                notifyError(DomException("Failed to apply DOM event ${event.method}: ${e.message}", cause = e))
            }
        }
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }

    // ================================================================
    // Command helpers
    // ================================================================

    private fun sendOnSelectedNode(method: String, params: Map<String, Any?>, callback: (DomOperationResult<Any?>) -> Unit) {
        val nodeId = selectedNodeId
        if (nodeId == null) {
            callback(DomOperationResult.Failure(DomException("No node selected for $method")))
            return
        }
        val gen = generation.get()
        if (session == null) {
            callback(DomOperationResult.Failure(ConnectionException("Not attached to a target")))
            return
        }
        send(method, mapOf("nodeId" to nodeId) + params, gen) { result ->
            when (result) {
                is CommandResult.Success -> callback(DomOperationResult.Success(result.response.result))
                is CommandResult.Failure -> {
                    notifyError(result.error)
                    callback(DomOperationResult.Failure(result.error))
                }
            }
        }
    }

    private fun send(method: String, params: Map<String, Any?>, gen: Int, callback: (CommandResult) -> Unit) {
        val s = session ?: run {
            callback(CommandResult.Failure(ConnectionException("No active DOM session")))
            return
        }
        dispatcher.sendCommand(method, params, sessionId = s.sessionId) { result ->
            if (isCurrent(gen)) callback(result)
        }
    }

    private fun isCurrent(gen: Int): Boolean = generation.get() == gen

    private fun setState(newState: DomState, error: Throwable?) {
        state = newState
        notifyStateChanged(newState, error)
    }

    private fun setError(error: Throwable) {
        state = DomState.ERROR
        notifyStateChanged(DomState.ERROR, error)
    }

    // ================================================================
    // Test hooks / helpers
    // ================================================================

    /** Applies a DOM event synchronously (used by deterministic unit tests). */
    internal fun applyEventSynchronous(method: String, params: Map<*, *>, sessionId: String? = null) {
        val event = Event(method = method, params = params, sessionId = sessionId ?: session?.sessionId)
        if (event.sessionId != session?.sessionId) return
        try {
            when (method) {
                "DOM.setChildNodes" -> handleSetChildNodes(params)
                "DOM.childNodeInserted" -> handleChildNodeInserted(params)
                "DOM.childNodeRemoved" -> handleChildNodeRemoved(params)
                "DOM.attributeModified" -> handleAttributeModified(params)
                "DOM.attributeRemoved" -> handleAttributeRemoved(params)
                "DOM.characterDataModified" -> handleCharacterDataModified(params)
                "DOM.documentUpdated" -> handleDocumentUpdated()
            }
        } catch (e: Exception) {
            notifyError(DomException("Failed to apply DOM event $method: ${e.message}", cause = e))
        }
    }

    private fun intParam(params: Map<*, *>, key: String): Int? =
        (params[key] as? Number)?.toInt()

    private fun notifyStateChanged(newState: DomState, error: Throwable?) {
        listeners.forEach { it.onStateChanged(newState, error) }
    }

    private fun notifyDocumentLoaded(document: DomNode?) {
        listeners.forEach { it.onDocumentLoaded(document) }
    }

    private fun notifyNodesChanged(ids: Set<Int>) {
        if (ids.isEmpty()) return
        listeners.forEach { it.onNodesChanged(ids) }
    }

    private fun notifySelectionChanged(node: DomNode?) {
        listeners.forEach { it.onSelectionChanged(node) }
    }

    private fun notifyInspectionStateChanged() {
        listeners.forEach { it.onInspectionStateChanged(inspectionState) }
    }

    private fun notifySearchStateChanged() {
        listeners.forEach { it.onSearchStateChanged(searchState) }
    }

    private fun notifyMutationStateChanged() {
        listeners.forEach { it.onMutationStateChanged(mutationState) }
    }

    private fun updateMutationState(state: DomMutationState) {
        mutationState = state
        notifyMutationStateChanged()
    }

    private fun updateInspectionState(state: DomInspectionState) {
        inspectionState = state
        notifyInspectionStateChanged()
    }

    private fun updateSearchState(state: DomSearchState) {
        searchState = state
        notifySearchStateChanged()
    }

    private fun notifyError(error: Throwable) {
        listeners.forEach { it.onError(error) }
    }

    /** Shuts down the internal executor. */
    fun shutdown() {
        executor.shutdownNow()
    }
}
