package com.coppermux.cutools.core.dom

/**
 * DOM node type, matching the standard Chrome NodeType integers used by the
 * CDP DOM domain. Only the types relevant to inspection are named explicitly;
 * unknown/negative values are preserved in [OTHER].
 */
enum class DomNodeType(val code: Int) {
    ELEMENT(1),
    ATTRIBUTE(2),
    TEXT(3),
    CDATA(4),
    ENTITY_REFERENCE(5),
    ENTITY(6),
    PROCESSING_INSTRUCTION(7),
    COMMENT(8),
    DOCUMENT(9),
    DOCUMENT_TYPE(10),
    DOCUMENT_FRAGMENT(11),
    NOTATION(12),
    OTHER(-1),
    ;

    companion object {
        fun fromCode(code: Int): DomNodeType =
            entries.firstOrNull { it.code == code } ?: OTHER
    }
}

/** A single DOM attribute (name/value pair) preserved from Chrome. */
data class DomAttribute(
    val name: String,
    val value: String?,
)

/**
 * A node in the inspected DOM tree.
 *
 * The node is immutable; mutations produce copies. Children are kept in
 * [children] only when they have been retrieved from Chrome (lazy loading).
 * A node with [childNodeCount] > children.size has more children not yet
 * fetched.
 *
 * Unknown protocol fields are intentionally not stored destructively — the
 * node retains all fields Chrome reports that are relevant to the inspector.
 */
data class DomNode(
    val nodeId: Int,
    val backendNodeId: Int? = null,
    val parentId: Int? = null,
    val nodeType: DomNodeType = DomNodeType.OTHER,
    val nodeName: String? = null,
    val localName: String? = null,
    val nodeValue: String? = null,
    val attributes: List<DomAttribute> = emptyList(),
    val childNodeCount: Int = 0,
    val children: List<DomNode> = emptyList(),
    val documentUrl: String? = null,
    val baseUrl: String? = null,
    val publicId: String? = null,
    val systemId: String? = null,
    val internalSubset: String? = null,
    val frameId: String? = null,
    val shadowRootType: String? = null,
    val pseudoType: String? = null,
    val isSvg: Boolean? = null,
    val childrenLoaded: Boolean = false,
) {
    val isElement: Boolean get() = nodeType == DomNodeType.ELEMENT
    val isText: Boolean get() = nodeType == DomNodeType.TEXT
    val isComment: Boolean get() = nodeType == DomNodeType.COMMENT
    val isDocument: Boolean get() = nodeType == DomNodeType.DOCUMENT

    /** Element tag name, e.g. "DIV", "BODY", derived from [nodeName]. */
    val tagName: String? get() = if (isElement) nodeName ?: localName else null

    /** Whether the node has a meaningful display name (element/document). */
    val hasDisplayName: Boolean get() = isElement || isDocument

    /** A human readable short label used by the tree UI. */
    val displayLabel: String
        get() = when {
            isElement -> "${nodeName ?: localName ?: "element"}${attrsPreview}"
            isText -> "\"${nodeValue?.take(32) ?: ""}\""
            isComment -> "<!--${nodeValue?.take(32) ?: ""}-->"
            isDocument -> "#document"
            else -> nodeName ?: nodeType.name
        }

    private val attrsPreview: String
        get() {
            if (attributes.isEmpty()) return ""
            val id = attributes.firstOrNull { it.name == "id" }?.value
            val cls = attributes.firstOrNull { it.name == "class" }?.value
            return buildString {
                if (id != null) append("#$id")
                if (cls != null) append(".$cls")
            }
        }

    fun attribute(name: String): String? = attributes.firstOrNull { it.name == name }?.value

    /** A copy of this node with the given child list. */
    fun withChildren(newChildren: List<DomNode>): DomNode =
        copy(children = newChildren, childrenLoaded = true, childNodeCount = newChildren.size)

    override fun toString(): String = displayLabel
}
