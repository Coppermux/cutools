package com.coppermux.cutools.core.dom

/**
 * In-memory representation of the inspected DOM tree.
 *
 * Holds a map of all known nodes keyed by their CDP [DomNode.nodeId]. Nodes are
 * immutable; the tree applies updates by replacing affected nodes with copies.
 *
 * Phase 2A is read-only, but the tree still supports the operations required to
 * build and browse the DOM foundation (spec §8): root insertion, node lookup by
 * nodeId, parent lookup, child lookup, child-collection insertion/replacement
 * (used for lazy expansion via `DOM.requestChildNodes`), and clearing.
 *
 * Mutating operations that modify the live page (attribute editing, node
 * insertion/removal wrappers, character-data edits) belong to later Phase 2
 * work and are intentionally not implemented here.
 *
 * This class is NOT thread-safe. The owning [DomInspector] serializes access by
 * executing all mutation/event handling through a single-threaded executor.
 */
class DomTree {

    // All known nodes keyed by nodeId.
    private val nodesById = LinkedHashMap<Int, DomNode>()

    // parentId lookup, maintained alongside node.parentId.
    private val parentById = HashMap<Int, Int>()

    /** The root document node ID, or null until the document is loaded. */
    @get:Synchronized
    var documentNodeId: Int? = null
        private set

    /** Whether a document has been loaded. */
    @get:Synchronized
    val isLoaded: Boolean get() = documentNodeId != null

    /** Number of known nodes. */
    @get:Synchronized
    val size: Int get() = nodesById.size

    /** All known nodes. */
    @get:Synchronized
    val allNodes: List<DomNode> get() = nodesById.values.toList()

    /** The root document node, if loaded. */
    @get:Synchronized
    val document: DomNode? get() = documentNodeId?.let { nodesById[it] }

    @Synchronized
    operator fun get(nodeId: Int): DomNode? = nodesById[nodeId]

    @Synchronized
    fun contains(nodeId: Int): Boolean = nodesById.containsKey(nodeId)

    @Synchronized
    fun parentOf(nodeId: Int): DomNode? = parentById[nodeId]?.let { nodesById[it] }

    /** Whether the given node has children that have not yet been fetched from Chrome. */
    @Synchronized
    fun hasUnloadedChildren(nodeId: Int): Boolean {
        val node = nodesById[nodeId] ?: return false
        return node.childNodeCount > node.children.size
    }

    /** Replaces the whole tree with the given root document and its descendants. */
    @Synchronized
    fun setRoot(root: DomNode) {
        nodesById.clear()
        parentById.clear()
        insertNodeRecursive(root, parentId = null)
        documentNodeId = root.nodeId
    }

    /**
     * Replaces the child collection of a parent with the given nodes.
     *
     * Used to merge the result of `DOM.requestChildNodes` (delivered via the
     * `DOM.setChildNodes` event) into the tree. Nodes no longer under the parent
     * are removed — including their loaded subtrees, so no stale orphans remain.
     * Replacing a parent's already-loaded children with an identical set is
     * idempotent and never duplicates nodes.
     */
    @Synchronized
    fun setChildren(parentId: Int, children: List<DomNode>) {
        val parent = nodesById[parentId] ?: return
        // Remove stale children (and their loaded subtrees) so no orphans remain.
        val oldIds = parent.children.map { it.nodeId }.toSet()
        val newIds = children.map { it.nodeId }.toSet()
        oldIds.minus(newIds).forEach { removeSubtree(it) }
        nodesById[parentId] = parent.withChildren(children)
        children.forEach { child ->
            parentById[child.nodeId] = parentId
            nodesById[child.nodeId] = child
            registerDescendants(child)
        }
    }

    /**
     * Inserts [child] into [parentId]'s child collection at the given [index],
     * establishing the parent relationship and preserving sibling ordering.
     *
     * Used for `DOM.childNodeInserted` events. If the node is already a child of
     * the same parent the operation is a no-op (reconcile, never duplicate). If
     * the node is already known under a different parent (a move), it is detached
     * from the old parent first.
     *
     * Returns true when the child is present under the parent after the call
     * (whether newly inserted or already present), false when the parent is
     * unknown.
     */
    @Synchronized
    fun insertChild(parentId: Int, index: Int, child: DomNode): Boolean {
        val parent = nodesById[parentId] ?: return false
        if (parent.children.any { it.nodeId == child.nodeId }) return true
        if (nodesById.containsKey(child.nodeId)) {
            // Already known (e.g., a moved node) — detach from its old parent first.
            removeNode(child.nodeId)
        }
        val children = parent.children.toMutableList()
        children.add(index.coerceIn(0, children.size), child)
        nodesById[parentId] = parent.copy(
            children = children,
            childNodeCount = maxOf(parent.childNodeCount, children.size),
            childrenLoaded = true,
        )
        parentById[child.nodeId] = parentId
        nodesById[child.nodeId] = child
        registerDescendants(child)
        return true
    }

    // ============== local mutation reconciliation (Phase 2C) ==============
    //
    // These methods update the LOCAL tree ONLY after Chrome has confirmed a
    // mutation. Chrome remains authoritative; they are never used for
    // optimistic updates. Each returns the nodeId(s) that changed so the
    // inspector can notify listeners.

    /**
     * Updates the value of an existing attribute. Returns the nodeId on success
     * or null if the node is unknown. Leaves the tree unchanged when the node is
     * absent.
     */
    @Synchronized
    fun updateAttributeValue(nodeId: Int, name: String, value: String?): Int? {
        val node = nodesById[nodeId] ?: return null
        val updated = node.attributes.map {
            if (it.name == name) it.copy(value = value) else it
        }
        nodesById[nodeId] = node.copy(attributes = updated)
        return nodeId
    }

    /**
     * Adds or replaces an attribute (preserving all unrelated attributes).
     * Returns the nodeId on success or null if the node is unknown.
     */
    @Synchronized
    fun setAttribute(nodeId: Int, name: String, value: String?): Int? {
        val node = nodesById[nodeId] ?: return null
        val updated = if (node.attributes.any { it.name == name }) {
            node.attributes.map { if (it.name == name) it.copy(value = value) else it }
        } else {
            node.attributes + DomAttribute(name, value)
        }
        nodesById[nodeId] = node.copy(attributes = updated)
        return nodeId
    }

    /**
     * Removes an attribute by name. Returns the nodeId on success or null if the
     * node is unknown. Preserves all unrelated attributes.
     */
    @Synchronized
    fun removeAttribute(nodeId: Int, name: String): Int? {
        val node = nodesById[nodeId] ?: return null
        nodesById[nodeId] = node.copy(attributes = node.attributes.filterNot { it.name == name })
        return nodeId
    }

    /** Updates a text/comment node's value. Returns nodeId or null if unknown. */
    @Synchronized
    fun updateNodeValue(nodeId: Int, value: String): Int? {
        val node = nodesById[nodeId] ?: return null
        nodesById[nodeId] = node.copy(nodeValue = value)
        return nodeId
    }

    /**
     * Removes a node (and its subtree) from the tree, updating the parent's
     * child collection. Returns the removed node's parent nodeId, or null if the
     * node is unknown or is the document root (never deleted).
     */
    @Synchronized
    fun removeNode(nodeId: Int): Int? {
        val node = nodesById[nodeId] ?: return null
        val parentId = parentById[nodeId] ?: return null
        if (parentId < 0) return null // document root — never removed
        removeSubtree(nodeId)
        val parent = nodesById[parentId]
        if (parent != null) {
            val remaining = parent.children.filterNot { it.nodeId == nodeId }
            nodesById[parentId] = parent.copy(children = remaining, childNodeCount = remaining.size)
        }
        return parentId
    }

    // ============== internal helpers ==============

    private fun insertNodeRecursive(node: DomNode, parentId: Int?) {
        nodesById[node.nodeId] = node
        parentById[node.nodeId] = parentId ?: -1
        registerDescendants(node)
    }

    private fun removeSubtree(nodeId: Int) {
        val node = nodesById.remove(nodeId) ?: return
        parentById.remove(nodeId)
        node.children.forEach { removeSubtree(it.nodeId) }
    }

    private fun registerDescendants(node: DomNode) {
        node.children.forEach { child ->
            parentById[child.nodeId] = node.nodeId
            nodesById[child.nodeId] = child
            registerDescendants(child)
        }
    }

    /** Clears all state. */
    @Synchronized
    fun clear() {
        nodesById.clear()
        parentById.clear()
        documentNodeId = null
    }
}
