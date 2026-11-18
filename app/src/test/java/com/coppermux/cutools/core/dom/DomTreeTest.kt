package com.coppermux.cutools.core.dom

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DomTreeTest {

    private fun node(id: Int, parentId: Int? = null, type: DomNodeType = DomNodeType.ELEMENT, name: String = "NODE", children: List<DomNode> = emptyList(), attrs: List<DomAttribute> = emptyList(), childCount: Int = children.size, nodeValue: String? = null) =
        DomNode(
            nodeId = id,
            parentId = parentId,
            nodeType = type,
            nodeName = name,
            nodeValue = nodeValue,
            attributes = attrs,
            children = children,
            childNodeCount = childCount,
            childrenLoaded = true,
        )

    private lateinit var tree: DomTree

    @Before
    fun setUp() {
        tree = DomTree()
    }

    @Test
    fun setRoot_buildsTreeWithParentLinks() {
        val doc = node(1, null, DomNodeType.DOCUMENT, "#document",
            children = listOf(node(2, 1, name = "HTML",
                children = listOf(node(3, 2, name = "BODY")))))
        tree.setRoot(doc)
        assertTrue(tree.isLoaded)
        assertEquals(1, tree.documentNodeId)
        assertEquals(3, tree.size)
        assertEquals(3, tree.allNodes.size)
        assertEquals(1, tree.parentOf(2)?.nodeId)
        assertEquals(2, tree.parentOf(3)?.nodeId)
    }

    @Test
    fun setRoot_atomicReplacesPreviousTree() {
        tree.setRoot(node(1, null, name = "OLD", children = listOf(node(2, 1))))
        tree.setRoot(node(10, null, name = "NEW", children = listOf(node(11, 10))))
        assertEquals(10, tree.documentNodeId)
        assertFalse(tree.contains(2))
        assertTrue(tree.contains(10))
        assertTrue(tree.contains(11))
    }

    @Test
    fun nodeLookup_findsAndMissingReturnsNull() {
        tree.setRoot(node(1, null, children = listOf(node(2, 1))))
        assertNotNull(tree[1])
        assertNotNull(tree[2])
        assertNull(tree[999])
        assertFalse(tree.contains(999))
    }

    @Test
    fun hasUnloadedChildren_detectsLazyChildren() {
        // childNodeCount=3 but only 1 child loaded
        tree.setRoot(node(1, null, name = "ROOT", children = listOf(node(2, 1)), childCount = 3))
        assertTrue(tree.hasUnloadedChildren(1))
        assertFalse(tree.hasUnloadedChildren(2))
    }

    @Test
    fun setChildren_replacesAndRelinks() {
        tree.setRoot(node(1, null, children = listOf(node(2, 1), node(3, 1))))
        tree.setChildren(1, listOf(node(3, 1), node(4, 1)))
        assertFalse(tree.contains(2))
        assertTrue(tree.contains(3))
        assertTrue(tree.contains(4))
        assertEquals(1, tree.parentOf(3)?.nodeId)
        assertEquals(1, tree.parentOf(4)?.nodeId)
        assertEquals(2, tree[1]?.children?.size)
        assertTrue(tree[1]!!.childrenLoaded)
    }

    @Test
    fun setChildren_idempotent_noDuplicates() {
        tree.setRoot(node(1, null, childCount = 2))
        val children = listOf(node(2, 1), node(3, 1))
        tree.setChildren(1, children)
        tree.setChildren(1, children)
        assertEquals(2, tree[1]?.children?.size)
        assertEquals(2, tree.allNodes.count { it.nodeId == 2 || it.nodeId == 3 })
    }

    @Test
    fun setChildren_unknownParent_isNoOp() {
        tree.setRoot(node(1, null))
        tree.setChildren(999, listOf(node(2, 1)))
        assertFalse(tree.contains(2))
    }

    @Test
    fun clear_emptiesEverything() {
        tree.setRoot(node(1, null, children = listOf(node(2, 1))))
        tree.clear()
        assertEquals(0, tree.size)
        assertFalse(tree.isLoaded)
        assertNull(tree.document)
        assertNull(tree[1])
    }
}
