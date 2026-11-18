package com.coppermux.cutools.core.css

import com.coppermux.cutools.core.error.CssException

/**
 * Parses raw CDP CSS domain payloads (Moshi `Map<*, *>` objects) into the
 * CuTools [CssNodeStyles] model.
 *
 * Chrome's CSS domain uses the following relevant shapes:
 * - `CSSStyleSheetHeader` (stylesheet metainformation)
 * - `CSSRule` (selector list + style block + origin)
 * - `CSSStyle` (list of `CSSProperty` declarations)
 * - `CSSProperty` (name/value/important/disabled/range)
 * - `RuleMatch` (`rule` + `matchingSelectors`)
 * - `CSS.getMatchedStylesForNode` result (`inlineStyle`, `attributesStyle`,
 *   `matchedCSSRules`, `inherited`, ...)
 *
 * Parsing is defensive: unknown fields are ignored, malformed entries are
 * skipped, and a malformed top-level payload throws [CssException] rather than
 * crashing elsewhere. No cascade resolution or local rule computation is ever
 * performed — Chrome is the source of truth.
 */
object CssParser {

    /** Parses a `CSSStyleSheetHeader` object into a [CssStyleSheet]. */
    fun parseStyleSheetHeader(raw: Any?): CssStyleSheet {
        val map = raw as? Map<*, *>
            ?: throw CssException("CSS stylesheet header is not an object: $raw")
        val id = map["styleSheetId"] as? String
            ?: throw CssException("CSS stylesheet header missing styleSheetId: $raw")
        return CssStyleSheet(
            styleSheetId = id,
            origin = CssStyleSheetOrigin.fromCode(map["origin"] as? String),
            sourceUrl = map["sourceURL"] as? String,
            title = map["title"] as? String,
            disabled = (map["disabled"] as? Boolean) ?: false,
            isInline = (map["isInline"] as? Boolean) ?: false,
            isConstructed = (map["isConstructed"] as? Boolean) ?: false,
            frameId = map["frameId"] as? String,
            startLine = asInt(map["startLine"]) ?: 0,
            startColumn = asInt(map["startColumn"]) ?: 0,
            endLine = asInt(map["endLine"]) ?: 0,
            endColumn = asInt(map["endColumn"]) ?: 0,
        )
    }

    /** Parses a `SourceRange` object, or null when absent/malformed. */
    fun parseSourceRange(raw: Any?): CssSourceRange? {
        val map = raw as? Map<*, *> ?: return null
        return CssSourceRange(
            startLine = asInt(map["startLine"]) ?: 0,
            startColumn = asInt(map["startColumn"]) ?: 0,
            endLine = asInt(map["endLine"]) ?: 0,
            endColumn = asInt(map["endColumn"]) ?: 0,
        )
    }

    /** Parses a `CSSProperty` declaration, or null when malformed. */
    fun parseDeclaration(raw: Any?): CssDeclaration? {
        val map = raw as? Map<*, *> ?: return null
        val name = map["name"] as? String ?: return null
        val value = map["value"] as? String ?: return null
        return CssDeclaration(
            name = name,
            value = value,
            important = (map["important"] as? Boolean) ?: false,
            disabled = (map["disabled"] as? Boolean) ?: false,
            implicit = (map["implicit"] as? Boolean) ?: false,
            parsedOk = map["parsedOk"] as? Boolean,
            range = parseSourceRange(map["range"]),
        )
    }

    /** Parses a `CSSStyle` object into a [CssStyleBlock]. */
    fun parseStyle(raw: Any?): CssStyleBlock {
        val map = raw as? Map<*, *> ?: return CssStyleBlock()
        val props = (map["cssProperties"] as? List<*>).orEmpty()
        return CssStyleBlock(
            styleSheetId = map["styleSheetId"] as? String,
            declarations = props.mapNotNull { parseDeclaration(it) },
        )
    }

    /** Parses a `CSSRule` object into a [CssRule]. */
    fun parseRule(raw: Any?): CssRule {
        val map = raw as? Map<*, *>
            ?: throw CssException("CSS rule is not an object: $raw")
        val selectorList = map["selectorList"] as? Map<*, *>
        val selectors = (selectorList?.get("selectors") as? List<*>).orEmpty().mapNotNull { sel ->
            val sm = sel as? Map<*, *> ?: return@mapNotNull null
            val text = sm["text"] as? String ?: return@mapNotNull null
            CssSelector(text = text, range = parseSourceRange(sm["range"]))
        }
        val selectorText = (selectorList?.get("text") as? String)
            ?: selectors.joinToString(", ") { it.text }
        val style = parseStyle(map["style"])
        val media = (map["media"] as? List<*>).orEmpty().mapNotNull { m ->
            (m as? Map<*, *>)?.get("text") as? String
        }
        val layers = (map["layers"] as? List<*>).orEmpty().mapNotNull { layer ->
            val lm = layer as? Map<*, *> ?: return@mapNotNull null
            val text = lm["text"] as? String ?: return@mapNotNull null
            CssLayer(
                text = text,
                range = parseSourceRange(lm["range"]),
                styleSheetId = lm["styleSheetId"] as? String,
            )
        }
        return CssRule(
            selectorText = selectorText,
            selectors = selectors,
            origin = CssStyleSheetOrigin.fromCode(map["origin"] as? String),
            styleSheetId = (map["styleSheetId"] as? String) ?: style.styleSheetId,
            sourceRange = parseSourceRange(styleRangeFrom(map["style"])),
            declarations = style.declarations,
            mediaText = media,
            layers = layers,
            containerQueries = parseContainerQueries(map["containerQueries"]),
        )
    }

    /** Parses a list of `RuleMatch` objects into matched [CssRule]s. */
    fun parseMatchedRules(raw: Any?): List<CssRule> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val ruleRaw = map["rule"] ?: return@mapNotNull null
            val rule = try {
                parseRule(ruleRaw)
            } catch (e: Exception) {
                return@mapNotNull null
            }
            val matching = (map["matchingSelectors"] as? List<*>).orEmpty()
                .mapNotNull { (it as? Number)?.toInt() }
            rule.copy(matchingSelectors = matching)
        }
    }

    /**
     * Parses the full `CSS.getMatchedStylesForNode` result for [nodeId].
     *
     * Unknown / malformed sub-structures degrade gracefully; the top-level
     * response must be an object.
     */
    fun parseNodeStyles(raw: Any?, nodeId: Int): CssNodeStyles {
        val map = raw as? Map<*, *>
            ?: throw CssException("CSS node styles response is not an object: $raw")
        val inline = parseStyle(map["inlineStyle"])
        val attributes = parseStyle(map["attributesStyle"])
        val matched = parseMatchedRules(map["matchedCSSRules"])
        val inherited = (map["inherited"] as? List<*>).orEmpty().mapNotNull { entry ->
            val em = entry as? Map<*, *> ?: return@mapNotNull null
            CssInheritedStyles(
                matchedRules = parseMatchedRules(em["matchedCSSRules"]),
                inlineDeclarations = parseStyle(em["inlineStyle"]).declarations,
            )
        }
        return CssNodeStyles(
            nodeId = nodeId,
            inlineDeclarations = inline.declarations,
            attributesDeclarations = attributes.declarations,
            matchedRules = matched,
            inherited = inherited,
        )
    }

    private fun styleRangeFrom(raw: Any?): Any? {
        val map = raw as? Map<*, *> ?: return null
        return map["range"]
    }

    // ================================================================
    // Phase 3B — computed styles, cascade layers, box model
    // ================================================================

    /**
     * Parses the `computedStyle` array of `CSS.getComputedStyleForNode` into
     * name/value pairs.
     */
    fun parseComputedStyle(raw: Any?): List<CssComputedStyleProperty> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"] as? String ?: return@mapNotNull null
            val value = map["value"] as? String ?: return@mapNotNull null
            CssComputedStyleProperty(name = name, value = value)
        }
    }

    /** Parses the `CSS.getComputedStyleForNode` result object. */
    fun parseComputedStyleResponse(raw: Any?): List<CssComputedStyleProperty> {
        val map = raw as? Map<*, *> ?: return emptyList()
        return parseComputedStyle(map["computedStyle"])
    }

    /** Parses a `CSSLayerData` object into a [CssLayerData] tree. */
    fun parseLayerData(raw: Any?): CssLayerData? {
        val map = raw as? Map<*, *> ?: return null
        val name = map["name"] as? String ?: return null
        val order = (map["order"] as? Number)?.toInt() ?: 0
        val subLayers = (map["subLayers"] as? List<*>).orEmpty().mapNotNull { parseLayerData(it) }
        return CssLayerData(name = name, order = order, subLayers = subLayers)
    }

    /** Parses the `CSS.getLayersForNode` result object. */
    fun parseLayersResponse(raw: Any?): CssLayerData? =
        parseLayerData((raw as? Map<*, *>)?.get("rootLayer"))

    /** Parses the `containerQueries` array of a `CSSRule`. */
    fun parseContainerQueries(raw: Any?): List<CssContainerQuery> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val conditionText = map["conditionText"] as? String ?: return@mapNotNull null
            CssContainerQuery(
                text = map["text"] as? String ?: conditionText,
                conditionText = conditionText,
                name = map["name"] as? String,
                physicalAxes = map["physicalAxes"] as? String,
                logicalAxes = map["logicalAxes"] as? String,
                styleSheetId = map["styleSheetId"] as? String,
                range = parseSourceRange(map["range"]),
            )
        }
    }

    /** Parses a `DOM.Quad` (8-number array) into a [CssQuad]. */
    fun parseQuad(raw: Any?): CssQuad {
        val values = (raw as? List<*>).orEmpty().map { value ->
            (value as? Number)?.toDouble() ?: 0.0
        }
        return CssQuad(values = values)
    }

    /** Parses the `DOM.getBoxModel` result into a [CssBoxModel]. */
    fun parseBoxModel(raw: Any?): CssBoxModel {
        val map = raw as? Map<*, *> ?: return CssBoxModel()
        val model = map["model"] as? Map<*, *> ?: return CssBoxModel()
        return CssBoxModel(
            content = parseQuad(model["content"]),
            padding = parseQuad(model["padding"]),
            border = parseQuad(model["border"]),
            margin = parseQuad(model["margin"]),
            width = asInt(model["width"]),
            height = asInt(model["height"]),
        )
    }

    /** Parses the `quads` array of `DOM.getContentQuads`. */
    fun parseContentQuads(raw: Any?): List<CssQuad> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { q -> parseQuad(q).takeUnless { it.isEmpty } }
    }

    /** Parses the `nodeId` of `DOM.getRelayoutBoundary`. */
    fun parseRelayoutBoundary(raw: Any?): Int? =
        asInt((raw as? Map<*, *>)?.get("nodeId"))

    /** Parses the (optional) `nodeId` of `DOM.getContainerForNode`. */
    fun parseContainerForNode(raw: Any?): Int? =
        asInt((raw as? Map<*, *>)?.get("nodeId"))

    /** Parses the `nodeIds` array of `DOM.getQueryingDescendantsForContainer`. */
    fun parseQueryingDescendants(raw: Any?): List<Int> {
        val list = (raw as? Map<*, *>)?.get("nodeIds") as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? Number)?.toInt() }
    }

    private fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        null -> null
        else -> v.toString().toIntOrNull()
    }
}