package com.coppermux.cutools.ui.accessibility

import com.coppermux.cutools.core.accessibility.AccessibilityEngine
import com.coppermux.cutools.core.accessibility.models.AXNodeModel
import com.coppermux.cutools.core.accessibility.models.AXTreeModel
import com.coppermux.cutools.core.accessibility.models.AccessibilityActionResult
import com.coppermux.cutools.core.accessibility.models.AccessibilityCapabilityStatus
import com.coppermux.cutools.core.accessibility.models.AccessibilityError
import com.coppermux.cutools.core.target.SessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for the Phase 9C Accessibility panel.
 *
 * Binds to the shared [AccessibilityEngine] (Phase 9A) and exposes a single
 * [state] `StateFlow` consumed by the Compose UI. It issues genuine engine
 * commands — `getFullAXTree`, session bind/detach — and propagates explicit
 * engine-resolved outcomes into [AccessibilityUiState]: an engine failure
 * (Unsupported / Unavailable / ExecutionFailed / TargetDisconnected) surfaces
 * as an explicit [AccessibilityUiState.errorMessage] banner and a matching
 * [AccessibilityUiState.status], never as a synthetic or fabricated tree
 * (No-Fake-Capability rule).
 *
 * Tree filtering is pure and deterministic: [filterTree] keeps nodes whose
 * role / name / value match the query, plus every ancestor required to
 * preserve the genuine tree structure. [filteredTree] exposes the filtered
 * view of the currently loaded tree for the UI.
 *
 * Threading: [state] is a thread-safe [StateFlow]; all engine reads are
 * non-blocking volatile reads, and engine callbacks are delivered on the
 * dispatcher's callback thread.
 */
class AccessibilityViewModel(
    private val engine: AccessibilityEngine,
) {

    private val _state = MutableStateFlow(AccessibilityUiState())
    val state: StateFlow<AccessibilityUiState> = _state

    /** The active attached target session, or null when detached. */
    @Volatile
    var attachedSession: SessionInfo? = null
        private set

    val isAttached: Boolean get() = attachedSession != null

    /**
     * The filtered view of the loaded tree for the current query. When no
     * query is active the full genuine tree is returned unchanged.
     */
    val filteredTree: AXTreeModel?
        get() = filterTree(_state.value.axTree, _state.value.searchQuery)

    // ================================================================
    // Target lifecycle
    // ================================================================

    /** Binds the accessibility engine to a newly-attached target session. */
    fun attachTarget(session: SessionInfo) {
        engine.bindSession(session)
        attachedSession = session
        publish(
            AccessibilityUiState(
                status = engine.capability ?: AccessibilityCapabilityStatus.UNAVAILABLE,
            )
        )
    }

    /** Detaches the accessibility engine, clearing all target-scoped state. */
    fun detachTarget() {
        engine.detach()
        attachedSession = null
        publish(AccessibilityUiState())
    }

    /** Handles connection loss across the accessibility engine. */
    fun onConnectionLost() {
        engine.onConnectionLost()
        attachedSession = null
        publish(AccessibilityUiState())
    }

    /** Releases the accessibility engine's state (command-driven domain). */
    fun shutdown() {
        engine.shutdown()
    }

    // ================================================================
    // Commands
    // ================================================================

    /**
     * Triggers `Accessibility.getFullAXTree` against the attached target and
     * publishes the genuine parsed tree on success or an explicit error banner
     * on failure.
     */
    fun loadFullTree(callback: (AccessibilityActionResult<AXTreeModel>) -> Unit = {}) {
        val sess = attachedSession ?: engine.session
        if (sess == null) {
            publish(
                _state.value.copy(
                    isLoading = false,
                    status = AccessibilityCapabilityStatus.UNAVAILABLE,
                    errorMessage = describeError(AccessibilityError.Unavailable),
                )
            )
            callback(AccessibilityActionResult.Failure(AccessibilityError.Unavailable))
            return
        }
        publish(_state.value.copy(isLoading = true, errorMessage = null))
        engine.getFullAXTree { result ->
            when (result) {
                is AccessibilityActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = AccessibilityCapabilityStatus.SUPPORTED,
                            axTree = result.data,
                            selectedNode = null,
                            errorMessage = null,
                        )
                    )
                }
                is AccessibilityActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: AccessibilityCapabilityStatus.FAILED,
                            axTree = null,
                            selectedNode = null,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Selects a node by [nodeId] from the loaded tree for the detail view. When
     * the id is unknown the selection is cleared; the selection always resolves
     * against the genuine loaded tree (never fabricated).
     */
    fun selectNode(nodeId: String) {
        val node = _state.value.axTree?.nodes?.firstOrNull { it.nodeId == nodeId }
        publish(_state.value.copy(selectedNode = node))
    }

    /**
     * Clears the current detail selection.
     */
    fun clearSelection() {
        publish(_state.value.copy(selectedNode = null))
    }

    /**
     * Updates the live search query. The tree itself is untouched; filtering is
     * derived lazily via [filteredTree].
     */
    fun updateSearchQuery(query: String) {
        publish(_state.value.copy(searchQuery = query))
    }

    /**
     * Clears the loaded tree state and re-fetches from the active target
     * session, exactly like `loadFullTree` but resetting prior tree state.
     */
    fun refresh(callback: (AccessibilityActionResult<AXTreeModel>) -> Unit = {}) {
        publish(
            _state.value.copy(
                axTree = null,
                selectedNode = null,
                isLoading = true,
                errorMessage = null,
            )
        )
        loadFullTree(callback)
    }

    // ================================================================
    // Pure tree filtering
    // ================================================================

    /**
     * Filters an AX tree by a case-insensitive substring query over each node's
     * role, name, and value. A node is retained when it matches directly or has
     * a retained descendant; every ancestor chain is preserved so the result
     * remains a genuine connected subtree. An empty query returns the tree
     * unchanged.
     */
    fun filterTree(tree: AXTreeModel?, query: String): AXTreeModel? {
        if (tree == null) return null
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return tree
        val byId = tree.nodes.associateBy { it.nodeId }
        val retained = mutableSetOf<String>()

        fun matches(node: AXNodeModel): Boolean {
            val needle = trimmed.lowercase()
            val haystacks = listOf(
                node.role?.value,
                node.name?.value,
                node.value?.value,
            )
            return haystacks.any { haystack -> haystack?.lowercase()?.contains(needle) == true }
        }

        fun retain(nodeId: String): Boolean {
            val node = byId[nodeId] ?: return false
            if (retained.contains(nodeId)) return true
            val selfMatches = matches(node)
            val childRetained = node.childIds.any { child -> retain(child) }
            if (selfMatches || childRetained) retained.add(nodeId)
            return selfMatches || childRetained
        }

        val roots = if (tree.rootNodeId != null && byId.containsKey(tree.rootNodeId)) {
            listOf(tree.rootNodeId)
        } else {
            val referenced = tree.nodes.flatMap { it.childIds }.toSet()
            tree.nodes.map { it.nodeId }.filter { it !in referenced }
        }
        roots.forEach { retain(it) }

        val keptNodes = tree.nodes
            .filter { it.nodeId in retained }
            .map { it.copy(childIds = it.childIds.filter { child -> child in retained }) }

        return AXTreeModel(
            nodes = keptNodes,
            rootNodeId = tree.rootNodeId?.takeIf { it in retained },
        )
    }

    // ================================================================
    // State & error helpers
    // ================================================================

    /**
     * Maps an explicit [AccessibilityError] to a renderable banner string
     * (No-Fake-Capability: the banner is derived from the genuine engine
     * failure, never synthesized success).
     */
    fun describeError(error: AccessibilityError): String = when (error) {
        is AccessibilityError.Unsupported -> "Unsupported: the Accessibility domain is not available on this target."
        is AccessibilityError.Unavailable -> "Unavailable: no attached target session."
        is AccessibilityError.TargetDisconnected -> "Failed: target disconnected."
        is AccessibilityError.ExecutionFailed -> "Failed: ${error.message.ifEmpty { "execution failed" }}"
    }

    private fun publish(next: AccessibilityUiState) {
        _state.value = next
    }
}