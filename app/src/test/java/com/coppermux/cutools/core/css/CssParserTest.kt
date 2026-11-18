package com.coppermux.cutools.core.css

import com.coppermux.cutools.core.error.CssException
import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic unit tests for [CssParser] — CDP CSS payload parsing.
 *
 * These never touch Chrome; they verify that stylesheet headers, CSS rules,
 * declarations, matched rules, and full `getMatchedStylesForNode` responses are
 * parsed into the CuTools models, and that malformed payloads degrade safely.
 */
class CssParserTest {

    private fun mapOf(vararg pairs: Pair<String, Any?>) = buildMap { pairs.forEach { put(it.first, it.second) } }

    // =====================================================================
    // Stylesheet headers
    // =====================================================================

    @Test
    fun parseStyleSheetHeader_fullFields() {
        val sheet = CssParser.parseStyleSheetHeader(
            mapOf(
                "styleSheetId" to "s1",
                "origin" to "regular",
                "sourceURL" to "https://example.com/app.css",
                "title" to "main",
                "disabled" to true,
                "isInline" to false,
                "isConstructed" to false,
                "frameId" to "frame-1",
                "startLine" to 0,
                "startColumn" to 0,
                "endLine" to 10,
                "endColumn" to 4,
            )
        )
        assertEquals("s1", sheet.styleSheetId)
        assertEquals(CssStyleSheetOrigin.REGULAR, sheet.origin)
        assertEquals("https://example.com/app.css", sheet.sourceUrl)
        assertEquals("main", sheet.title)
        assertTrue(sheet.disabled)
        assertFalse(sheet.isInline)
        assertEquals("frame-1", sheet.frameId)
        assertEquals(10, sheet.endLine)
        assertEquals("app.css", sheet.displayName)
    }

    @Test
    fun parseStyleSheetHeader_missingId_throws() {
        assertThrows(CssException::class.java) {
            CssParser.parseStyleSheetHeader(mapOf("origin" to "regular"))
        }
    }

    @Test
    fun parseStyleSheetHeader_notObject_throws() {
        assertThrows(CssException::class.java) {
            CssParser.parseStyleSheetHeader("garbage")
        }
    }

    @Test
    fun parseStyleSheetHeader_unknownOrigin_mapsToUnknown() {
        val sheet = CssParser.parseStyleSheetHeader(mapOf("styleSheetId" to "s1", "origin" to "something-else"))
        assertEquals(CssStyleSheetOrigin.UNKNOWN, sheet.origin)
    }

    @Test
    fun parseStyleSheetHeader_userAgent() {
        val sheet = CssParser.parseStyleSheetHeader(mapOf("styleSheetId" to "s1", "origin" to "user-agent"))
        assertEquals(CssStyleSheetOrigin.USER_AGENT, sheet.origin)
        assertTrue(sheet.isUserAgent)
        assertEquals("user agent stylesheet", sheet.displayName)
    }

    @Test
    fun parseStyleSheetHeader_inline() {
        val sheet = CssParser.parseStyleSheetHeader(mapOf("styleSheetId" to "s1", "origin" to "regular", "isInline" to true))
        assertTrue(sheet.isInline)
        assertEquals("(inline style)", sheet.displayName)
    }

    // =====================================================================
    // Source ranges
    // =====================================================================

    @Test
    fun parseSourceRange() {
        val range = CssParser.parseSourceRange(
            mapOf("startLine" to 2, "startColumn" to 4, "endLine" to 2, "endColumn" to 20)
        )
        assertNotNull(range)
        assertEquals(2, range!!.startLine)
        assertEquals(4, range.startColumn)
        assertEquals(20, range.endColumn)
    }

    @Test
    fun parseSourceRange_missing_returnsNull() {
        assertNull(CssParser.parseSourceRange(null))
        assertNull(CssParser.parseSourceRange("garbage"))
    }

    // =====================================================================
    // Declarations / style blocks
    // =====================================================================

    @Test
    fun parseDeclaration_fullFields() {
        val decl = CssParser.parseDeclaration(
            mapOf(
                "name" to "color",
                "value" to "red",
                "important" to true,
                "disabled" to true,
                "implicit" to false,
                "parsedOk" to true,
                "range" to mapOf("startLine" to 1, "startColumn" to 2, "endLine" to 1, "endColumn" to 3),
            )
        )
        assertNotNull(decl)
        assertEquals("color", decl!!.name)
        assertEquals("red", decl.value)
        assertTrue(decl.important)
        assertTrue(decl.disabled)
        assertEquals("color: red !important", decl.displayText)
        assertNotNull(decl.range)
    }

    @Test
    fun parseDeclaration_defaultFlags() {
        val decl = CssParser.parseDeclaration(mapOf("name" to "color", "value" to "red"))
        assertNotNull(decl)
        assertFalse(decl!!.important)
        assertFalse(decl.disabled)
        assertEquals("color: red", decl.displayText)
    }

    @Test
    fun parseDeclaration_missingName_returnsNull() {
        assertNull(CssParser.parseDeclaration(mapOf("value" to "red")))
        assertNull(CssParser.parseDeclaration("garbage"))
    }

    @Test
    fun parseStyle_parsesDeclarations() {
        val style = CssParser.parseStyle(
            mapOf(
                "styleSheetId" to "inline",
                "cssProperties" to listOf(
                    mapOf("name" to "color", "value" to "red"),
                    mapOf("name" to "margin", "value" to "0"),
                    "malformed-entry",
                ),
            )
        )
        assertEquals("inline", style.styleSheetId)
        assertEquals(2, style.declarations.size)
        assertEquals("red", style.declarations[0].value)
    }

    @Test
    fun parseStyle_missing_returnsEmptyBlock() {
        val style = CssParser.parseStyle(null)
        assertTrue(style.declarations.isEmpty())
    }

    // =====================================================================
    // Rules / matched rules
    // =====================================================================

    @Test
    fun parseRule_fullFields() {
        val rule = CssParser.parseRule(
            mapOf(
                "selectorList" to mapOf(
                    "text" to "h1, .hero",
                    "selectors" to listOf(
                        mapOf("text" to "h1", "range" to mapOf("startLine" to 0, "startColumn" to 0, "endLine" to 0, "endColumn" to 2)),
                        mapOf("text" to ".hero"),
                    ),
                ),
                "origin" to "regular",
                "styleSheetId" to "s1",
                "style" to mapOf(
                    "range" to mapOf("startLine" to 0, "startColumn" to 3, "endLine" to 0, "endColumn" to 20),
                    "cssProperties" to listOf(
                        mapOf("name" to "color", "value" to "blue"),
                        mapOf("name" to "font-size", "value" to "14px", "important" to true),
                    ),
                ),
            )
        )
        assertEquals("h1, .hero", rule.selectorText)
        assertEquals(2, rule.selectors.size)
        assertEquals("h1", rule.selectors[0].text)
        assertEquals(CssStyleSheetOrigin.REGULAR, rule.origin)
        assertEquals("s1", rule.styleSheetId)
        assertNotNull(rule.sourceRange)
        assertEquals(0, rule.sourceRange!!.startLine)
        assertEquals(2, rule.declarations.size)
        assertEquals("font-size", rule.declarations[1].name)
        assertTrue(rule.declarations[1].important)
    }

    @Test
    fun parseRule_withoutSelectorList_hasEmptySelectorText() {
        val rule = CssParser.parseRule(
            mapOf(
                "origin" to "user-agent",
                "style" to mapOf("cssProperties" to listOf(mapOf("name" to "display", "value" to "block"))),
            )
        )
        assertEquals(CssStyleSheetOrigin.USER_AGENT, rule.origin)
        assertEquals("", rule.selectorText)
        assertEquals(1, rule.declarations.size)
    }

    @Test
    fun parseRule_withMedia() {
        val rule = CssParser.parseRule(
            mapOf(
                "selectorList" to mapOf("text" to ".box", "selectors" to listOf(mapOf("text" to ".box"))),
                "origin" to "regular",
                "style" to mapOf("cssProperties" to emptyList<Any>()),
                "media" to listOf(mapOf("text" to "(min-width: 600px)")),
            )
        )
        assertEquals(listOf("(min-width: 600px)"), rule.mediaText)
    }

    @Test
    fun parseRule_notObject_throws() {
        assertThrows(CssException::class.java) { CssParser.parseRule("nope") }
    }

    @Test
    fun parseMatchedRules_parsesMatchingSelectors() {
        val rules = CssParser.parseMatchedRules(
            listOf(
                mapOf(
                    "rule" to mapOf(
                        "selectorList" to mapOf("text" to "h1", "selectors" to listOf(mapOf("text" to "h1"))),
                        "origin" to "regular",
                        "style" to mapOf("cssProperties" to listOf(mapOf("name" to "color", "value" to "red"))),
                    ),
                    "matchingSelectors" to listOf(0),
                ),
                "malformed",
            )
        )
        assertEquals(1, rules.size)
        assertEquals(listOf(0), rules[0].matchingSelectors)
    }

    // =====================================================================
    // Full getMatchedStylesForNode responses
    // =====================================================================

    @Test
    fun parseNodeStyles_fullResponse() {
        val styles = CssParser.parseNodeStyles(
            mapOf(
                "inlineStyle" to mapOf(
                    "cssProperties" to listOf(mapOf("name" to "color", "value" to "green")),
                ),
                "attributesStyle" to mapOf(
                    "cssProperties" to listOf(mapOf("name" to "fill", "value" to "none")),
                ),
                "matchedCSSRules" to listOf(
                    mapOf(
                        "rule" to mapOf(
                            "selectorList" to mapOf("text" to ".hero", "selectors" to listOf(mapOf("text" to ".hero"))),
                            "origin" to "regular",
                            "styleSheetId" to "s1",
                            "style" to mapOf("cssProperties" to listOf(mapOf("name" to "display", "value" to "flex"))),
                        ),
                        "matchingSelectors" to listOf(0),
                    )
                ),
                "inherited" to listOf(
                    mapOf(
                        "matchedCSSRules" to listOf(
                            mapOf(
                                "rule" to mapOf(
                                    "selectorList" to mapOf("text" to "body", "selectors" to listOf(mapOf("text" to "body"))),
                                    "origin" to "regular",
                                    "style" to mapOf("cssProperties" to listOf(mapOf("name" to "font-family", "value" to "sans-serif"))),
                                ),
                                "matchingSelectors" to listOf(0),
                            )
                        )
                    )
                ),
            ),
            nodeId = 42,
        )
        assertEquals(42, styles.nodeId)
        assertFalse(styles.isEmpty)
        assertEquals(1, styles.inlineDeclarations.size)
        assertEquals("green", styles.inlineDeclarations[0].value)
        assertEquals(1, styles.attributesDeclarations.size)
        assertEquals(1, styles.matchedRules.size)
        assertEquals(".hero", styles.matchedRules[0].selectorText)
        assertEquals("flex", styles.matchedRules[0].declarations[0].value)
        assertEquals(1, styles.inherited.size)
        assertEquals("body", styles.inherited[0].matchedRules[0].selectorText)
    }

    @Test
    fun parseNodeStyles_emptyResponse_isEmpty() {
        val styles = CssParser.parseNodeStyles(mapOf<String, Any?>(), nodeId = 1)
        assertEquals(1, styles.nodeId)
        assertTrue(styles.isEmpty)
    }

    @Test
    fun parseNodeStyles_nullTopLevel_throws() {
        assertThrows(CssException::class.java) { CssParser.parseNodeStyles(null, nodeId = 1) }
    }

    @Test
    fun parseNodeStyles_notObject_throws() {
        assertThrows(CssException::class.java) { CssParser.parseNodeStyles("garbage", nodeId = 1) }
    }

@Test
    fun parseNodeStyles_malformedSubstructures_degradeGracefully() {
        val styles = CssParser.parseNodeStyles(
            mapOf(
                "inlineStyle" to "garbage",
                "matchedCSSRules" to "garbage",
                "inherited" to listOf("garbage", mapOf("matchedCSSRules" to "garbage")),
            ),
            nodeId = 1,
        )
        assertTrue(styles.isEmpty)
    }

    // =====================================================================
    // Phase 3B — computed styles
    // =====================================================================

    @Test
    fun parseComputedStyle_parsesNameValuePairs() {
        val props = CssParser.parseComputedStyleResponse(
            mapOf(
                "computedStyle" to listOf(
                    mapOf("name" to "color", "value" to "rgb(0, 128, 0)"),
                    mapOf("name" to "width", "value" to "300px"),
                    "junk",
                )
            )
        )
        assertEquals(2, props.size)
        assertEquals("color", props[0].name)
        assertEquals("rgb(0, 128, 0)", props[0].value)
        assertEquals("width", props[1].name)
    }

    @Test
    fun parseComputedStyle_malformed_returnsEmpty() {
        assertTrue(CssParser.parseComputedStyleResponse("garbage").isEmpty())
        assertTrue(CssParser.parseComputedStyleResponse(mapOf<String, Any?>()).isEmpty())
        assertTrue(CssParser.parseComputedStyle("not-a-list").isEmpty())
    }

    // =====================================================================
    // Phase 3B — cascade layers
    // =====================================================================

    @Test
    fun parseLayersResponse_parsesLayerTree() {
        val root = CssParser.parseLayersResponse(
            mapOf(
                "rootLayer" to mapOf(
                    "name" to "implicit outer layer",
                    "order" to 2,
                    "subLayers" to listOf(
                        mapOf("name" to "base", "order" to 0),
                        mapOf("name" to "theme", "order" to 1, "subLayers" to emptyList<Any>()),
                    ),
                )
            )
        )
        assertNotNull(root)
        assertEquals("implicit outer layer", root!!.name)
        assertEquals(2, root.order)
        assertEquals(2, root.subLayers.size)
        assertEquals("base", root.subLayers[0].name)
        assertEquals(1, root.subLayers[1].order)
    }

    @Test
    fun parseLayersResponse_missing_returnsNull() {
        assertNull(CssParser.parseLayersResponse(mapOf<String, Any?>()))
        assertNull(CssParser.parseLayersResponse("garbage"))
    }

    @Test
    fun parseRule_includesLayers() {
        val rule = CssParser.parseRule(
            mapOf(
                "selectorList" to mapOf("text" to ".dark h1", "selectors" to listOf(mapOf("text" to ".dark h1"))),
                "origin" to "regular",
                "style" to mapOf("cssProperties" to listOf(mapOf("name" to "color", "value" to "purple"))),
                "layers" to listOf(
                    mapOf(
                        "text" to "theme",
                        "range" to mapOf("startLine" to 3, "startColumn" to 9, "endLine" to 3, "endColumn" to 14),
                        "styleSheetId" to "s1",
                    )
                ),
            )
        )
        assertEquals(1, rule.layers.size)
        assertEquals("theme", rule.layers[0].text)
        assertNotNull(rule.layers[0].range)
        assertEquals("s1", rule.layers[0].styleSheetId)
    }

    // =====================================================================
    // Phase 3B — box model
    // =====================================================================

    @Test
    fun parseQuad_andBoxModel() {
        val quad = CssParser.parseQuad(listOf(8.0, 82.0, 208.0, 82.0, 208.0, 162.0, 8.0, 162.0))
        assertEquals(8, quad.values.size)
        assertEquals(8.0, quad.left!!, 0.001)
        assertEquals(208.0, quad.right!!, 0.001)
        assertEquals(82.0, quad.top!!, 0.001)
        assertEquals(162.0, quad.bottom!!, 0.001)

        val box = CssParser.parseBoxModel(
            mapOf(
                "model" to mapOf(
                    "content" to listOf(0.0, 0.0, 100.0, 0.0, 100.0, 50.0, 0.0, 50.0),
                    "padding" to listOf(0.0, 0.0, 100.0, 0.0, 100.0, 50.0, 0.0, 50.0),
                    "border" to listOf(0.0, 0.0, 100.0, 0.0, 100.0, 50.0, 0.0, 50.0),
                    "margin" to listOf(0.0, 0.0, 100.0, 0.0, 100.0, 50.0, 0.0, 50.0),
                    "width" to 100,
                    "height" to 50,
                )
            )
        )
        assertFalse(box.isEmpty)
        assertEquals(100, box.width)
        assertEquals(50, box.height)
        assertEquals("(0, 0) 100×50", box.content?.summary)
        assertNotNull(box.border)
    }

    @Test
    fun parseBoxModel_missingModel_returnsEmpty() {
        val box = CssParser.parseBoxModel("garbage")
        assertTrue(box.isEmpty)
    }

@Test
    fun quadSummary_malformed_isNA() {
        val quad = CssParser.parseQuad(listOf(1.0, 2.0))
        assertEquals("n/a", quad.summary)
        assertTrue(quad.isEmpty)
    }

    // =====================================================================
    // Phase 3D — layout / container queries
    // =====================================================================

    @Test
    fun parseContentQuads_parsesQuadList() {
        val quads = CssParser.parseContentQuads(
            listOf(
                listOf(8.0, 8.0, 100.0, 8.0, 100.0, 26.0, 8.0, 26.0),
                listOf(0.0, 0.0, 50.0, 0.0, 50.0, 20.0, 0.0, 20.0),
                "garbage",
            )
        )
        assertEquals(2, quads.size)
        assertEquals(8.0, quads[0].left!!, 0.001)
        assertEquals(20.0, quads[1].bottom!!, 0.001)
    }

    @Test
    fun parseContentQuads_malformed_returnsEmpty() {
        assertTrue(CssParser.parseContentQuads("garbage").isEmpty())
        assertTrue(CssParser.parseContentQuads(listOf("bad")).isEmpty())
    }

    @Test
    fun parseRelayoutBoundary_parsesNodeId() {
        assertEquals(2, CssParser.parseRelayoutBoundary(mapOf("nodeId" to 2)))
        assertNull(CssParser.parseRelayoutBoundary(mapOf<String, Any?>()))
        assertNull(CssParser.parseRelayoutBoundary("garbage"))
    }

    @Test
    fun parseContainerForNode_optionalNodeId() {
        assertEquals(5, CssParser.parseContainerForNode(mapOf("nodeId" to 5)))
        assertNull(CssParser.parseContainerForNode(mapOf<String, Any?>()))
    }

    @Test
    fun parseQueryingDescendants_parsesNodeIds() {
        val ids = CssParser.parseQueryingDescendants(mapOf("nodeIds" to listOf(6, 7)))
        assertEquals(listOf(6, 7), ids)
        assertTrue(CssParser.parseQueryingDescendants(mapOf<String, Any?>()).isEmpty())
    }

    @Test
    fun parseContainerQueries_parsesDescriptors() {
        val queries = CssParser.parseContainerQueries(
            listOf(
                mapOf(
                    "text" to "(min-width: 100px)",
                    "conditionText" to "min-width: 100px",
                    "name" to "card",
                    "physicalAxes" to "Horizontal",
                    "styleSheetId" to "s1",
                ),
                "garbage",
            )
        )
        assertEquals(1, queries.size)
        assertEquals("(min-width: 100px)", queries[0].text)
        assertEquals("min-width: 100px", queries[0].conditionText)
        assertEquals("card", queries[0].name)
        assertEquals("Horizontal", queries[0].physicalAxes)
    }

    @Test
    fun parseRule_includesContainerQueries() {
        val rule = CssParser.parseRule(
            mapOf(
                "selectorList" to mapOf("text" to ".child", "selectors" to listOf(mapOf("text" to ".child"))),
                "origin" to "regular",
                "style" to mapOf("cssProperties" to emptyList<Any>()),
                "containerQueries" to listOf(
                    mapOf("text" to "(min-width: 100px)", "conditionText" to "min-width: 100px")
                ),
            )
        )
        assertEquals(1, rule.containerQueries.size)
        assertEquals("(min-width: 100px)", rule.containerQueries[0].text)
    }
}