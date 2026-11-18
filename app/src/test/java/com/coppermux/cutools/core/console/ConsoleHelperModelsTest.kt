package com.coppermux.cutools.core.console

import com.coppermux.cutools.core.runtime.EvaluationRemoteObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Phase 4C console helper models: feature-origin
 * classification, $0 selected-element reference, query-helper expression
 * building, and JS string literal escaping.
 */
class ConsoleHelperModelsTest {

    // ============ feature origin ============

    @Test
    fun featureOrigin_labelsAreExplicit() {
        assertEquals("native Chrome", ConsoleFeatureOrigin.NATIVE_CHROME.displayLabel)
        assertEquals("CDP-backed", ConsoleFeatureOrigin.CDP_BACKED.displayLabel)
        assertEquals("CuTools-only", ConsoleFeatureOrigin.CUTOOLS_ONLY.displayLabel)
    }

    @Test
    fun queryHelpers_areClassifiedAsCdpBacked() {
        assertEquals(ConsoleFeatureOrigin.CDP_BACKED, QuerySelector.origin)
        assertEquals(ConsoleFeatureOrigin.CDP_BACKED, QuerySelectorAll.origin)
    }

    @Test
    fun queryHelper_fromName_resolves() {
        assertEquals(QuerySelector, ConsoleQueryHelper.fromName("querySelector"))
        assertEquals(QuerySelectorAll, ConsoleQueryHelper.fromName("querySelectorAll"))
        assertNull(ConsoleQueryHelper.fromName("bogus"))
    }

    // ============ selected element reference ============

    @Test
    fun selectedElement_isAvailableOnlyWhenLoaded() {
        val ref = SelectedElementReference(
            nodeId = 5,
            sessionId = "s",
            phase = SelectedElementPhase.LOADED,
            remoteObject = EvaluationRemoteObject(type = "object", objectId = "node-5"),
        )
        assertTrue(ref.isAvailable)
        assertEquals(5, ref.nodeId)
        assertEquals("node-5", ref.remoteObject?.objectId)
        assertFalse(ref.copy(phase = SelectedElementPhase.RESOLVING).isAvailable)
        assertFalse(ref.copy(phase = SelectedElementPhase.STALE).isAvailable)
    }

    @Test
    fun selectedElement_preservesChromeRemoteObject() {
        val ro = EvaluationRemoteObject(type = "object", subtype = "node", className = "HTMLDivElement", objectId = "o-1")
        val ref = SelectedElementReference(nodeId = 7, sessionId = "s", phase = SelectedElementPhase.LOADED, remoteObject = ro)
        assertEquals("HTMLDivElement", ref.remoteObject?.className)
    }

    // ============ query expression building ============

    @Test
    fun querySelector_buildsDocumentQuery() {
        assertEquals("document.querySelector(\"#box\")", QuerySelector.buildExpression("#box"))
    }

    @Test
    fun querySelectorAll_buildsDocumentQueryAll() {
        assertEquals("document.querySelectorAll(\"#box\")", QuerySelectorAll.buildExpression("#box"))
        assertEquals("document.querySelectorAll(\".a.b\")", QuerySelectorAll.buildExpression(".a.b"))
    }

    @Test
    fun querySelectorAll_isPlural() {
        assertTrue(QuerySelectorAll.isPlural)
        assertFalse(QuerySelector.isPlural)
    }

    // ============ JS string literal escaping ============

    @Test
    fun jsStringLiteral_escapesQuotesAndBackslashes() {
        assertEquals("\"a\\\"b\"", jsStringLiteral("a\"b"))
        assertEquals("\"a\\\\b\"", jsStringLiteral("a\\b"))
    }

    @Test
    fun jsStringLiteral_escapesControlChars() {
        assertEquals("\"a\\nb\"", jsStringLiteral("a\nb"))
        assertEquals("\"a\\tb\"", jsStringLiteral("a\tb"))
        assertEquals("\"a\\rb\"", jsStringLiteral("a\rb"))
    }

    @Test
    fun jsStringLiteral_plainTextUnchanged() {
        assertEquals("\"#box\"", jsStringLiteral("#box"))
    }

    // ============ query helper state ============

    @Test
    fun queryHelperState_defaults() {
        val qs = QueryHelperState.empty()
        assertNull(qs.activeHelper)
        assertFalse(qs.running)
        assertNull(qs.lastOutcome)
    }

    @Test
    fun queryHelperState_isExplicit() {
        val qs = QueryHelperState(activeHelper = QuerySelector, selector = "#x", running = true)
        assertEquals("#x", qs.selector)
        assertTrue(qs.running)
    }
}