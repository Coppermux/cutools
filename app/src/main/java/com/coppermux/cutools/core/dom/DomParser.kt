package com.coppermux.cutools.core.dom

import com.coppermux.cutools.core.error.DomException

/**
 * Parses raw CDP DOM protocol payloads (Moshi `Map<*, *>` objects) into the
 * CuTools [DomNode] model.
 *
 * Chrome's DOM Node has attributes encoded as a flat alternating array
 * `[name0, value0, name1, value1, ...]`. This parser converts that to a list
 * of [DomAttribute]. Unknown fields are ignored without failing the parse.
 */
object DomParser {

    /**
     * Parses a single CDP Node object (from `DOM.getDocument` / `DOM.setChildNodes` /
     * `DOM.childNodeInserted`) into a [DomNode], recursively parsing any inline
     * [children]. When [parseChildrenInline] is true (the default for document
     * roots), inline children are parsed; the resulting node has
     * [DomNode.childrenLoaded] = true.
     */
    fun parseNode(raw: Any?, parentId: Int? = null, parseChildrenInline: Boolean = true): DomNode {
        val map = raw as? Map<*, *>
            ?: throw DomException("DOM node payload is not an object: $raw")
        val nodeId = asInt(map["nodeId"]) ?: throw DomException("DOM node missing 'nodeId': $raw")
        val attributes = when (val attrs = map["attributes"]) {
            null -> emptyList()
            is List<*> -> parseAttributeArray(attrs)
            else -> emptyList()
        }
        val children = if (parseChildrenInline) {
            (map["children"] as? List<*>).orEmpty().mapNotNull { child ->
                if (child is Map<*, *>) parseNode(child, nodeId) else null
            }
        } else {
            emptyList()
        }
        return DomNode(
            nodeId = nodeId,
            backendNodeId = asInt(map["backendNodeId"]),
            parentId = parentId ?: asInt(map["parentId"]),
            nodeType = DomNodeType.fromCode(asInt(map["nodeType"]) ?: 0),
            nodeName = map["nodeName"] as? String,
            localName = map["localName"] as? String,
            nodeValue = map["nodeValue"] as? String,
            attributes = attributes,
            childNodeCount = asInt(map["childNodeCount"]) ?: children.size,
            children = children,
            documentUrl = map["documentURL"] as? String,
            baseUrl = map["baseURL"] as? String,
            frameId = map["frameId"] as? String,
            shadowRootType = map["shadowRootType"] as? String,
            pseudoType = map["pseudoType"] as? String,
            isSvg = map["isSVG"] as? Boolean,
            childrenLoaded = parseChildrenInline,
        )
    }

    /**
     * Parses a single live node without descending into children (used for
     * `DOM.childNodeInserted` events where children come separately, and for
     * `DOM.describeNode`-style responses).
     */
    fun parseLeafNode(raw: Any?, parentId: Int? = null): DomNode =
        parseNode(raw, parentId, parseChildrenInline = false)

    /** Parses Chrome's flat `[name, value, name, value, ...]` attribute array. */
    fun parseAttributeArray(values: List<*>): List<DomAttribute> {
        val result = ArrayList<DomAttribute>(values.size / 2)
        var i = 0
        while (i + 1 <= values.size - 1) {
            val name = values[i]?.toString() ?: return result
            val value = values[i + 1]?.toString()
            result.add(DomAttribute(name, value))
            i += 2
        }
        return result
    }

    private fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        null -> null
        else -> v.toString().toIntOrNull()
    }
}
