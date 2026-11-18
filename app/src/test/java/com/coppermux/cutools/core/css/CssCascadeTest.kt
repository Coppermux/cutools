package com.coppermux.cutools.core.css

import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic tests for [CssCascade] — derivation of active/overridden /
 * inherited / inline / user-agent declaration statuses from the cascade order
 * Chrome exposes in `CSS.getMatchedStylesForNode`.
 */
class CssCascadeTest {

    private fun decl(name: String, value: String, important: Boolean = false, implicit: Boolean = false) =
        CssDeclaration(name = name, value = value, important = important, implicit = implicit)

    private fun rule(
        selector: String,
        declarations: List<CssDeclaration>,
    ) = CssRule(selectorText = selector, declarations = declarations)

    private fun rule(
        selector: String,
        origin: CssStyleSheetOrigin,
        declarations: List<CssDeclaration>,
    ) = CssRule(selectorText = selector, origin = origin, declarations = declarations)

    private fun stylesOf(
        inline: List<CssDeclaration> = emptyList(),
        matched: List<CssRule> = emptyList(),
        attributes: List<CssDeclaration> = emptyList(),
        inherited: List<CssInheritedStyles> = emptyList(),
    ) = CssNodeStyles(
        nodeId = 1,
        inlineDeclarations = inline,
        attributesDeclarations = attributes,
        matchedRules = matched,
        inherited = inherited,
    )

    @Test
    fun laterAuthorRule_wins_sameProperty() {
        val styles = stylesOf(
            matched = listOf(
                rule("h1", declarations = listOf(decl("color", "blue"), decl("margin", "0"))),
                rule("h1", declarations = listOf(decl("color", "green"))),
            )
        )
        val resolved = CssCascade.resolve(styles)
        val colors = resolved.matchedRules.flatMap { it.declarations }.filter { it.name == "color" }
        assertEquals(2, colors.size)
        val winner = colors.first { it.status == CssDeclarationStatus.ACTIVE }
        assertEquals("green", winner.value)
        assertTrue(colors.filter { it.status == CssDeclarationStatus.OVERRIDDEN }.all { it.value == "blue" })
        // Unrelated property wins within its own rule.
        assertEquals(
            CssDeclarationStatus.ACTIVE,
            resolved.matchedRules.first().declarations.first { it.name == "margin" }.status
        )
    }

    @Test
    fun important_beatsInline() {
        val resolved = CssCascade.resolve(
            stylesOf(
                inline = listOf(decl("color", "red")),
                matched = listOf(rule("h1", listOf(decl("color", "blue", important = true)))),
            )
        )
        assertTrue(
            resolved.matchedRules.first().declarations.first().status == CssDeclarationStatus.ACTIVE
        )
        assertTrue(resolved.inlineDeclarations.first().status == CssDeclarationStatus.OVERRIDDEN)
    }

    @Test
    fun inline_wins_overNormalAuthorRules() {
        val resolved = CssCascade.resolve(
            stylesOf(
                inline = listOf(decl("color", "red")),
                matched = listOf(rule("h1", listOf(decl("color", "blue")))),
            )
        )
        assertEquals(CssDeclarationStatus.INLINE, resolved.inlineDeclarations.first().status)
        assertEquals(
            CssDeclarationStatus.OVERRIDDEN,
            resolved.matchedRules.first().declarations.first().status
        )
    }

    @Test
    fun userAgent_declarations_areUserAgent() {
        val resolved = CssCascade.resolve(
            stylesOf(
                matched = listOf(
                    rule("h1", CssStyleSheetOrigin.USER_AGENT, listOf(decl("display", "block"))),
                    rule("h1", listOf(decl("display", "flex"))),
                )
            )
        )
        val ua = resolved.matchedRules.first { it.origin == CssStyleSheetOrigin.USER_AGENT }
        assertEquals(CssDeclarationStatus.USER_AGENT, ua.declarations.first().status)
    }

    @Test
    fun inherited_declarations_areInherited() {
        val resolved = CssCascade.resolve(
            stylesOf(
                inherited = listOf(
                    CssInheritedStyles(matchedRules = listOf(rule("body", listOf(decl("font-family", "serif")))))
                ),
                matched = listOf(rule("h1", listOf(decl("color", "red")))),
            )
        )
        assertEquals(CssDeclarationStatus.INHERITED, resolved.inherited.first().matchedRules.first().declarations.first().status)
        // The node's own declaration still wins its property.
        assertEquals(CssDeclarationStatus.ACTIVE, resolved.matchedRules.first().declarations.first().status)
    }

    @Test
    fun inherited_neverBeatsOwnDeclarations() {
        val resolved = CssCascade.resolve(
            stylesOf(
                inherited = listOf(
                    CssInheritedStyles(matchedRules = listOf(rule("div", listOf(decl("color", "grey")))))
                ),
                inline = listOf(decl("color", "lime")),
            )
        )
        assertEquals(CssDeclarationStatus.INLINE, resolved.inlineDeclarations.first().status)
        assertEquals(CssDeclarationStatus.INHERITED, resolved.inherited.first().matchedRules.first().declarations.first().status)
    }

    @Test
    fun attributes_areAuthorLevel() {
        val resolved = CssCascade.resolve(
            stylesOf(
                attributes = listOf(decl("fill", "none")),
                matched = listOf(rule("rect", listOf(decl("fill", "red")))),
            )
        )
        val winner = resolved.matchedRules.first().declarations.first()
        assertEquals(CssDeclarationStatus.ACTIVE, winner.status)
        assertEquals(CssDeclarationStatus.OVERRIDDEN, resolved.attributesDeclarations.first().status)
    }

    @Test
    fun emptyStyles_unchanged() {
        val r = CssCascade.resolve(stylesOf())
        assertTrue(r.isEmpty)
    }
}