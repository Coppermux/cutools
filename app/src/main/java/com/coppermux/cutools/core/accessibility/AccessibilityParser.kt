package com.coppermux.cutools.core.accessibility

import com.coppermux.cutools.core.accessibility.models.AXNodeModel
import com.coppermux.cutools.core.accessibility.models.AXPropertyModel
import com.coppermux.cutools.core.accessibility.models.AXTreeModel
import com.coppermux.cutools.core.accessibility.models.AXValueModel
import com.coppermux.cutools.core.accessibility.models.AXValueSourceModel

/**
 * Pure, stateless, fail-safe parser for genuine CDP `Accessibility` domain wire
 * payloads.
 *
 * Parsing never deserializes into the typed models (avoiding class-loading /
 * overflow risk on oversized payloads) and never throws on missing or malformed
 * fields: mandatory `nodeId` is required for a node to be produced, while every
 * optional field (role, name, description, value, properties, ignoredReasons,
 * childIds, backendDOMNodeId) falls back to a safe default when absent or
 * malformed. No accessibility state is ever fabricated (No-Fake-Capability).
 *
 * Threading: the parser is an immutable, stateless singleton; all methods are
 * pure so it is trivially safe to call from any thread.
 */
object AccessibilityParser {

    /**
     * Parses the raw result object of `Accessibility.getFullAXTree` /
     * `Accessibility.getPartialAXTree` (a JSON map carrying a `nodes` array)
     * into an [AXTreeModel]. Returns null when [raw] is not a map. The derived
     * [AXTreeModel.rootNodeId] is the single node not referenced by any child,
     * or null when it cannot be determined.
     */
    fun parseTree(raw: Any?): AXTreeModel? {
        if (raw !is Map<*, *>) return null
        val nodes = parseNodeList(raw["nodes"])
        return AXTreeModel(nodes, findRootNodeId(nodes))
    }

    /**
     * Parses a raw `nodes` array into a list of [AXNodeModel]. Non-map or
     * nodeId-less entries are skipped. Never throws.
     */
    fun parseNodeList(raw: Any?): List<AXNodeModel> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { parseNode(it) }
    }

    /**
     * Parses a single CDP `AXNode` map into an [AXNodeModel]. Returns null when
     * [raw] is not a map or carries no genuine `nodeId` (mandatory). Optional
     * fields fall back to safe defaults.
     */
    fun parseNode(raw: Any?): AXNodeModel? {
        if (raw !is Map<*, *>) return null
        val nodeId = raw["nodeId"] as? String ?: return null
        val ignored = (raw["ignored"] as? Boolean) ?: false
        return AXNodeModel(
            nodeId = nodeId,
            ignored = ignored,
            ignoredReasons = parsePropertyList(raw["ignoredReasons"]),
            role = parseValue(raw["role"]),
            name = parseValue(raw["name"]),
            description = parseValue(raw["description"]),
            value = parseValue(raw["value"]),
            properties = parsePropertyList(raw["properties"]),
            childIds = parseStringList(raw["childIds"]),
            backendDOMNodeId = (raw["backendDOMNodeId"] as? Number)?.toInt(),
        )
    }

    /**
     * Parses a CDP `AXValue` map into an [AXValueModel]. Returns null when [raw]
     * is not a map. Missing `type`/`value`/`sources` default safely.
     */
    fun parseValue(raw: Any?): AXValueModel? {
        if (raw !is Map<*, *>) return null
        return AXValueModel(
            type = (raw["type"] as? String) ?: "",
            value = scalarToString(raw["value"]),
            sources = parseSourceList(raw["sources"]),
        )
    }

    /**
     * Parses a single CDP `AXProperty` map into an [AXPropertyModel]. Returns
     * null when [raw] is not a map or carries no `name`. A missing `value`
     * defaults to an empty [AXValueModel].
     */
    fun parseProperty(raw: Any?): AXPropertyModel? {
        if (raw !is Map<*, *>) return null
        val name = raw["name"] as? String ?: return null
        return AXPropertyModel(
            name = name,
            value = parseValue(raw["value"]) ?: AXValueModel(type = "", value = null),
        )
    }

    /**
     * Parses a single CDP `AXValueSource` map into an [AXValueSourceModel].
     * Returns null when [raw] is not a map.
     */
    fun parseSource(raw: Any?): AXValueSourceModel? {
        if (raw !is Map<*, *>) return null
        return AXValueSourceModel(
            type = (raw["type"] as? String) ?: "",
            value = scalarToString(raw["value"]),
            attribute = raw["attribute"] as? String,
            attributeValue = raw["attributeValue"] as? String,
            superseded = (raw["superseded"] as? Boolean) ?: false,
            invalid = (raw["invalid"] as? Boolean) ?: false,
        )
    }

    private fun parsePropertyList(raw: Any?): List<AXPropertyModel> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { parseProperty(it) }
    }

    private fun parseSourceList(raw: Any?): List<AXValueSourceModel> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { parseSource(it) }
    }

    private fun parseStringList(raw: Any?): List<String> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { it as? String }
    }

    private fun findRootNodeId(nodes: List<AXNodeModel>): String? {
        if (nodes.isEmpty()) return null
        val referenced = mutableSetOf<String>()
        nodes.forEach { node -> referenced.addAll(node.childIds) }
        val roots = nodes.filter { it.nodeId !in referenced }
        return if (roots.size == 1) roots[0].nodeId else null
    }

    private fun scalarToString(raw: Any?): String? =
        raw as? String ?: raw?.toString()
}