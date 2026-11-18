package com.coppermux.cutools.ui.network

import com.coppermux.cutools.core.network.MockAction
import com.coppermux.cutools.core.network.MockResponseRuleSpec
import com.coppermux.cutools.core.network.NetworkFailureReason

/**
 * The editable fields of an interception rule, mirroring a
 * [MockResponseRuleSpec] while allowing an in-progress (possibly invalid) draft.
 *
 * Validation is explicit and testable: [validationErrors] reports every
 * problem, and [toRule] only produces a usable rule when the draft is valid.
 */
data class MockRuleDraft(
    val urlPattern: String = "",
    val method: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val action: MockAction = MockAction.FULFILL,
    val statusCode: Int = 200,
    val statusText: String? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bodyIsBase64: Boolean = false,
    val failureReason: NetworkFailureReason = NetworkFailureReason.Failed,
) {
    /** Human-readable validation problems, empty when the draft is valid. */
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        if (urlPattern.isBlank()) {
            errors.add("URL pattern is required.")
        }
        if (statusCode !in 100..599) {
            errors.add("HTTP status must be between 100 and 599.")
        }
        return errors
    }

    /** Whether the draft is structurally valid. */
    val isValid: Boolean get() = validationErrors().isEmpty()

    /**
     * Converts the draft to a usable [MockResponseRuleSpec] with the given
     * [id]. Callers guard [isValid] first; an invalid draft yields a rule with
     * a blank pattern (the rule manager simply will not match it).
     */
    fun toRule(id: String): MockResponseRuleSpec {
        val normalizedMethod = method?.trim()?.takeIf { it.isNotEmpty() }
        return MockResponseRuleSpec(
            id = id,
            urlPattern = urlPattern.trim(),
            method = normalizedMethod,
            enabled = enabled,
            priority = priority,
            action = action,
            statusCode = statusCode,
            statusText = statusText?.trim()?.takeIf { it.isNotEmpty() },
            responseHeaders = responseHeaders,
            body = body,
            bodyIsBase64 = bodyIsBase64,
            failureReason = failureReason,
        )
    }

    companion object {
        /** Builds a draft from an existing authored rule (for editing). */
        fun fromRule(rule: MockResponseRuleSpec): MockRuleDraft = MockRuleDraft(
            urlPattern = rule.urlPattern,
            method = rule.method,
            enabled = rule.enabled,
            priority = rule.priority,
            action = rule.action,
            statusCode = rule.statusCode,
            statusText = rule.statusText,
            responseHeaders = rule.responseHeaders,
            body = rule.body,
            bodyIsBase64 = rule.bodyIsBase64,
            failureReason = rule.failureReason,
        )
    }
}

/**
 * Immutable state flow for the mock-rule editor: the authored rule list, the
 * rule currently being edited (or created), and the modifiable [draft].
 *
 * Every user action returns a new state (add/edit/delete/toggle) so the flow
 * is deterministic and testable. Rules are keyed by stable ids; ids are
 * generated monotonically and never recycled.
 */
data class MockRuleEditorState(
    val rules: List<MockResponseRuleSpec> = emptyList(),
    val editingId: String? = null,
    val draft: MockRuleDraft = MockRuleDraft(),
    val lastError: String? = null,
    private val nextId: Int = 1,
) {
    /** Whether an existing rule is being edited (vs a new rule). */
    val isEditing: Boolean get() = editingId != null

    val isCreating: Boolean get() = editingId == null

    /** Validation problems of the current draft. */
    val validationErrors: List<String> get() = draft.validationErrors()

    val draftIsValid: Boolean get() = draft.isValid

    /** Begins creating a new rule, clearing the editable draft. */
    fun beginCreate(): MockRuleEditorState = copy(
        editingId = null,
        draft = MockRuleDraft(),
        lastError = null,
    )

    /** Begins editing [ruleId], loading its fields into the draft. */
    fun beginEdit(ruleId: String): MockRuleEditorState {
        val rule = rules.find { it.id == ruleId } ?: return copy(lastError = "Rule not found: $ruleId")
        return copy(
            editingId = ruleId,
            draft = MockRuleDraft.fromRule(rule),
            lastError = null,
        )
    }

    /** Applies the draft: validates, then adds or updates the rule set. */
    fun commitDraft(): MockRuleEditorState {
        if (!draft.isValid) {
            return copy(lastError = validationErrors.joinToString(" "))
        }
        val editing = editingId
        val creating = editing == null
        val rule = if (creating) draft.toRule(ruleId()) else draft.toRule(editing)
        val updated = if (creating) rules + rule else rules.map { if (it.id == editing) rule else it }
        return copy(
            rules = updated,
            editingId = null,
            draft = MockRuleDraft(),
            lastError = null,
            nextId = if (creating) nextId + 1 else nextId,
        )
    }

    /** Updates the draft copy (without committing to the rule set). */
    fun updateDraft(transform: (MockRuleDraft) -> MockRuleDraft): MockRuleEditorState =
        copy(draft = transform(draft), lastError = null)

    /** Deletes [ruleId] from the rule set. */
    fun deleteRule(ruleId: String): MockRuleEditorState {
        if (rules.none { it.id == ruleId }) {
            return copy(lastError = "Rule not found: $ruleId")
        }
        return copy(
            rules = rules.filter { it.id != ruleId },
            editingId = if (editingId == ruleId) null else editingId,
            draft = if (editingId == ruleId) MockRuleDraft() else draft,
            lastError = null,
        )
    }

    /** Toggles the enablement flag of [ruleId]. */
    fun toggleRule(ruleId: String): MockRuleEditorState {
        val rule = rules.find { it.id == ruleId } ?: return copy(lastError = "Rule not found: $ruleId")
        val toggled = rule.copy(enabled = !rule.enabled)
        return copy(rules = rules.map { if (it.id == ruleId) toggled else it })
    }

    /** Clears the editor back to its initial state. */
    fun clear(): MockRuleEditorState = MockRuleEditorState()

    private fun ruleId(): String = "rule-$nextId"
}