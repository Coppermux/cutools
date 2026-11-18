package com.coppermux.cutools.ui.network

import com.coppermux.cutools.core.network.MockAction
import com.coppermux.cutools.core.network.MockResponseRuleSpec
import com.coppermux.cutools.core.network.NetworkFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for Phase 5D mock-rule editor state flow
 * ([MockRuleEditorState], [MockRuleDraft]).
 */
class MockRuleEditorViewModelTest {

    private fun valid(name: String) = MockRuleDraft(
        urlPattern = "https://api.example.com/$name*",
        statusCode = 200,
        body = "{\"ok\":true}",
    )

    // ============ draft validation ============

    @Test
    fun blankPattern_isInvalid() {
        val draft = MockRuleDraft()
        assertFalse(draft.isValid)
        assertTrue(draft.validationErrors().any { it.contains("URL pattern") })
    }

    @Test
    fun outOfRangeStatus_isInvalid() {
        val draft = valid("a").copy(statusCode = 999)
        assertFalse(draft.isValid)
        assertTrue(draft.validationErrors().any { it.contains("status") })
    }

    @Test
    fun boundaryStatuses_areValid() {
        assertTrue(valid("a").copy(statusCode = 100).isValid)
        assertTrue(valid("a").copy(statusCode = 599).isValid)
    }

    @Test
    fun validDraft_isValid() {
        assertTrue(valid("a").isValid)
        assertEquals(0, valid("a").validationErrors().size)
    }

    @Test
    fun toRule_trimsPatternAndMethod() {
        val rule = valid("a")
            .copy(method = "  get  ", urlPattern = "  https://x/*  ")
            .toRule("r1")
        assertEquals("https://x/*", rule.urlPattern)
        assertEquals("get", rule.method)
    }

    @Test
    fun toRule_blankMethodBecomesNull() {
        val rule = valid("a").copy(method = "   ").toRule("r1")
        assertNull(rule.method)
    }

    @Test
    fun fromRule_roundTrips() {
        val rule = MockResponseRuleSpec(
            id = "r1",
            urlPattern = "https://x/*",
            method = "POST",
            enabled = false,
            priority = 7,
            action = MockAction.FAIL,
            statusCode = 503,
            statusText = "Unavailable",
            responseHeaders = mapOf("x-1" to "a"),
            body = "boom",
            failureReason = NetworkFailureReason.TimedOut,
        )
        val draft = MockRuleDraft.fromRule(rule)
        assertEquals(rule, draft.toRule("r1"))
    }

    // ============ create flow ============

    @Test
    fun beginCreate_clearsDraftAndEditingState() {
        val state = MockRuleEditorState().beginCreate()
        assertTrue(state.isCreating)
        assertFalse(state.isEditing)
        assertNull(state.editingId)
        assertTrue(state.draft.urlPattern.isBlank())
    }

    @Test
    fun commitDraft_createsRuleWithGeneratedId() {
        val state = MockRuleEditorState()
            .updateDraft { valid("a") }
            .commitDraft()
        assertEquals(1, state.rules.size)
        assertEquals("rule-1", state.rules.first().id)
        assertEquals("https://api.example.com/a*", state.rules.first().urlPattern)
        assertEquals(0, state.rules.first().priority)
        assertNull(state.editingId)
        assertTrue(state.draft.urlPattern.isBlank())
        assertNull(state.lastError)
    }

    @Test
    fun commitDraft_incrementsIds() {
        val afterFirst = MockRuleEditorState()
            .updateDraft { valid("a") }
            .commitDraft()
        val afterSecond = afterFirst
            .beginCreate()
            .updateDraft { valid("b") }
            .commitDraft()
        assertEquals(listOf("rule-1", "rule-2"), afterSecond.rules.map { it.id })
    }

    @Test
    fun commitDraft_invalidKeepsRuleSetAndSetsError() {
        val state = MockRuleEditorState()
            .updateDraft { MockRuleDraft(urlPattern = "", statusCode = 200) }
            .commitDraft()
        assertEquals(0, state.rules.size)
        assertNotNull(state.lastError)
        assertTrue(state.lastError!!.contains("URL pattern"))
    }

    @Test
    fun updateDraft_preservesOtherEditorState() {
        val state = MockRuleEditorState()
            .beginCreate()
            .updateDraft { draft ->
                draft.copy(statusCode = 418)
            }
        assertEquals(418, state.draft.statusCode)
        assertTrue(state.isCreating)
    }

    // ---------- edit flow ----------

    @Test
    fun beginEdit_loadsRuleIntoDraft() {
        val state = MockRuleEditorState(rules = listOf(
            MockResponseRuleSpec(id = "r1", urlPattern = "https://x/*", method = "GET", statusCode = 404),
        )).beginEdit("r1")
        assertTrue(state.isEditing)
        assertEquals("https://x/*", state.draft.urlPattern)
        assertEquals(404, state.draft.statusCode)
        assertEquals("GET", state.draft.method)
    }

    @Test
    fun beginEdit_unknownIdSetsError() {
        val state = MockRuleEditorState(rules = listOf(ruleSpec("r1"))).beginEdit("nope")
        assertFalse(state.isEditing)
        assertTrue(state.lastError!!.contains("not found"))
    }

    @Test
    fun commitDraft_editingKeepsIdAndUpdates() {
        val state = MockRuleEditorState(rules = listOf(ruleSpec("r1", status = 200)))
            .beginEdit("r1")
            .updateDraft { it.copy(statusCode = 418) }
            .commitDraft()
        assertEquals(1, state.rules.size)
        assertEquals("r1", state.rules.first().id)
        assertEquals(418, state.rules.first().statusCode)
    }

    @Test
    fun commitDraft_editDoesNotConsumeId() {
        val after = MockRuleEditorState(rules = listOf(ruleSpec("r1")))
            .beginEdit("r1")
            .updateDraft { it.copy(statusCode = 500) }
            .commitDraft()
            .beginCreate()
            .updateDraft { valid("new") }
            .commitDraft()
        assertEquals(listOf("r1", "rule-1"), after.rules.map { it.id })
    }

    // ---------- delete / toggle ----------

    @Test
    fun deleteRule_removesRule() {
        val state = MockRuleEditorState(rules = listOf(ruleSpec("r1"), ruleSpec("r2")))
            .deleteRule("r1")
        assertEquals(listOf("r2"), state.rules.map { it.id })
    }

    @Test
    fun deleteUnknownRule_setsError() {
        val state = MockRuleEditorState(rules = listOf(ruleSpec("r1"))).deleteRule("zzz")
        assertNotNull(state.lastError)
    }

    @Test
    fun deleteRule_clearsEditingDraftIfDeletingThatRule() {
        val state = MockRuleEditorState(rules = listOf(ruleSpec("r1")))
            .beginEdit("r1")
            .deleteRule("r1")
        assertNull(state.editingId)
        assertTrue(state.draft.urlPattern.isBlank())
    }

    @Test
    fun toggleRule_flipsEnabled() {
        val state = MockRuleEditorState(rules = listOf(ruleSpec("r1", enabled = true)))
            .toggleRule("r1")
        assertFalse(state.rules.first().enabled)
        val back = state.toggleRule("r1")
        assertTrue(back.rules.first().enabled)
    }

    @Test
    fun toggleUnknownRule_setsError() {
        val state = MockRuleEditorState(rules = listOf(ruleSpec("r1"))).toggleRule("missing")
        assertNotNull(state.lastError)
    }

    @Test
    fun clear_resetsEverything() {
        val state = MockRuleEditorState(rules = listOf(ruleSpec("r1")))
            .beginEdit("r1")
            .clear()
        assertEquals(0, state.rules.size)
        assertNull(state.editingId)
        assertNull(state.lastError)
    }

    private fun ruleSpec(id: String, status: Int = 200, enabled: Boolean = true) =
        MockResponseRuleSpec(id = id, urlPattern = "https://x/*", statusCode = status, enabled = enabled)
}