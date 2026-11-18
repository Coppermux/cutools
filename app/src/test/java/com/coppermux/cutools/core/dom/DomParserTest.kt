package com.coppermux.cutools.core.dom

import com.coppermux.cutools.core.error.DomException
import org.junit.Assert.*
import org.junit.Test

class DomParserTest {

    private val attributeArray = listOf("id", "main", "class", "container hero", "data-x", "1")

    @Test
    fun parseAttributeArray_handlesAlternatingPairs() {
        val attrs = DomParser.parseAttributeArray(attributeArray)
        assertEquals(3, attrs.size)
        assertEquals("id", attrs[0].name)
        assertEquals("main", attrs[0].value)
        assertEquals("class", attrs[1].name)
        assertEquals("container hero", attrs[1].value)
        assertEquals("data-x", attrs[2].name)
        assertEquals("1", attrs[2].value)
    }

    @Test
    fun parseAttributeArray_empty_returnsEmpty() {
        assertTrue(DomParser.parseAttributeArray(emptyList<Any>()).isEmpty())
    }

    @Test
    fun parseAttributeArray_handlesEmptyValues() {
        // A boolean attribute (e.g. "hidden") has an empty string value.
        val attrs = DomParser.parseAttributeArray(listOf("hidden", "", "id", "x", "class", ""))
        assertEquals(3, attrs.size)
        assertEquals("hidden", attrs[0].name)
        assertEquals("", attrs[0].value)
        assertEquals("x", attrs[1].value)
        assertEquals("", attrs[2].value)
    }

    @Test
    fun parseAttributeArray_irregularOddLength_doesNotCrash() {
        // Odd length: last element has no value pair — must not crash.
        val attrs = DomParser.parseAttributeArray(listOf("id", "main", "class"))
        assertEquals(1, attrs.size)
        assertEquals("id", attrs[0].name)
        assertEquals("main", attrs[0].value)
    }

    @Test
    fun parseNode_parsesDocumentNode() {
        val raw = mapOf<String, Any?>(
            "nodeId" to 1,
            "nodeType" to 9,
            "nodeName" to "#document",
            "documentURL" to "https://example.com/",
            "childNodeCount" to 2,
            "children" to listOf(
                mapOf("nodeId" to 2, "nodeType" to 1, "nodeName" to "HTML", "childNodeCount" to 2)
            ),
        )
        val node = DomParser.parseNode(raw)
        assertEquals(1, node.nodeId)
        assertEquals(DomNodeType.DOCUMENT, node.nodeType)
        assertEquals("#document", node.nodeName)
        assertEquals("https://example.com/", node.documentUrl)
        assertEquals(2, node.childNodeCount)
        assertEquals(1, node.children.size)
        assertTrue(node.childrenLoaded)
    }

    @Test
    fun parseNode_parsesElementWithAttributes() {
        val raw = mapOf<String, Any?>(
            "nodeId" to 5,
            "backendNodeId" to 100,
            "parentId" to 2,
            "nodeType" to 1,
            "nodeName" to "DIV",
            "localName" to "div",
            "attributes" to attributeArray,
        )
        val node = DomParser.parseNode(raw)
        assertEquals(DomNodeType.ELEMENT, node.nodeType)
        assertEquals("DIV", node.tagName)
        assertEquals("main", node.attribute("id"))
        assertEquals("container hero", node.attribute("class"))
        assertTrue(node.isElement)
        assertEquals("DIV#main.container hero", node.displayLabel)
    }

    @Test
    fun parseNode_missingNodeId_throwsDomException() {
        try {
            DomParser.parseNode(mapOf("nodeType" to 1))
            fail("Expected DomException")
        } catch (e: DomException) {
            assertTrue(e.message?.contains("nodeId") == true)
        }
    }

    @Test
    fun parseNode_nonObject_throwsDomException() {
        try {
            DomParser.parseNode("not a map")
            fail("Expected DomException")
        } catch (e: DomException) {
            // expected
        }
    }

    @Test
    fun parseLeafNode_doesNotParseChildren() {
        val raw = mapOf<String, Any?>(
            "nodeId" to 3,
            "nodeType" to 1,
            "children" to listOf(mapOf("nodeId" to 4, "nodeType" to 1)),
        )
        val node = DomParser.parseLeafNode(raw)
        assertEquals(3, node.nodeId)
        assertTrue(node.children.isEmpty())
    }

    @Test
    fun nodeType_fromCode_mapsKnownAndUnknown() {
        assertEquals(DomNodeType.ELEMENT, DomNodeType.fromCode(1))
        assertEquals(DomNodeType.TEXT, DomNodeType.fromCode(3))
        assertEquals(DomNodeType.COMMENT, DomNodeType.fromCode(8))
        assertEquals(DomNodeType.DOCUMENT, DomNodeType.fromCode(9))
        assertEquals(DomNodeType.OTHER, DomNodeType.fromCode(99))
    }
}
