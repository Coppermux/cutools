package com.coppermux.cutools.ui.network

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import com.coppermux.cutools.R
import com.coppermux.cutools.core.network.MockAction
import com.coppermux.cutools.core.network.NetworkFailureReason

/**
 * Interactive mock-rule editor dialog.
 *
 * Edits a [MockRuleDraft] (create or edit), validates it, and hands the result
 * back through [onSave]. When editing an existing rule, [onDelete] is offered.
 * Rule enablement toggling is handled by the parent's rule list, not this form.
 */
class MockRuleDialog(
    context: Context,
    private val draft: MockRuleDraft,
    private val editing: Boolean,
    private val onSave: (MockRuleDraft) -> Unit,
    private val onDelete: (() -> Unit)? = null,
) : Dialog(context) {

    private lateinit var urlPattern: EditText
    private lateinit var method: EditText
    private lateinit var priority: EditText
    private lateinit var statusCode: EditText
    private lateinit var failureReason: EditText
    private lateinit var headers: EditText
    private lateinit var body: EditText
    private lateinit var bodyBase64: View
    private lateinit var enabled: View
    private lateinit var actionFulfill: RadioButton
    private lateinit var actionFail: RadioButton
    private lateinit var validation: TextView

    private var current: MockRuleDraft = draft

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_mock_rule)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        findViewById<View>(R.id.ruleTitle)

        urlPattern = findViewById(R.id.ruleUrlPattern)
        method = findViewById(R.id.ruleMethod)
        priority = findViewById(R.id.rulePriority)
        statusCode = findViewById(R.id.ruleStatusCode)
        failureReason = findViewById(R.id.ruleFailureReason)
        headers = findViewById(R.id.ruleHeaders)
        body = findViewById(R.id.ruleBody)
        bodyBase64 = findViewById(R.id.ruleBodyBase64)
        enabled = findViewById(R.id.ruleEnabled)
        actionFulfill = findViewById(R.id.ruleActionFulfill)
        actionFail = findViewById(R.id.ruleActionFail)
        validation = findViewById(R.id.ruleValidationText)

        prefill()

        findViewById<View>(R.id.ruleActionFulfill).setOnClickListener { onActionChange(MockAction.FULFILL) }
        findViewById<View>(R.id.ruleActionFail).setOnClickListener { onActionChange(MockAction.FAIL) }

        findViewById<View>(R.id.ruleEnabled).setOnClickListener {
            current = current.copy(enabled = (it as android.widget.CheckBox).isChecked)
        }
        findViewById<View>(R.id.ruleBodyBase64).setOnClickListener {
            current = current.copy(bodyIsBase64 = (it as android.widget.CheckBox).isChecked)
        }

        findViewById<View>(R.id.ruleSaveButton).setOnClickListener { save() }
        findViewById<View>(R.id.ruleDeleteButton).setOnClickListener { delete() }
        findViewById<View>(R.id.ruleCancelButton).setOnClickListener { dismiss() }
        findViewById<View>(R.id.ruleDeleteButton).isEnabled = editing && onDelete != null
    }

    /** Saves the form state into a fresh draft and hands it to [onSave]. */
    fun save() {
        val draft = readDraft()
        val problems = draft.validationErrors()
        if (problems.isNotEmpty()) {
            validation.text = problems.joinToString("\n")
            validation.visibility = View.VISIBLE
            return
        }
        onSave(draft)
        dismiss()
    }

    /** Deletes the edited rule via [onDelete] (editing only). */
    fun delete() {
        onDelete?.invoke()
        dismiss()
    }

    private fun prefill() {
        urlPattern.setText(draft.urlPattern)
        method.setText(draft.method ?: "")
        priority.setText(draft.priority.toString())
        statusCode.setText(draft.statusCode.toString())
        failureReason.setText(draft.failureReason.cdpValue)
        headers.setText(renderHeaders(draft.responseHeaders))
        body.setText(draft.body ?: "")
        (bodyBase64 as android.widget.CheckBox).isChecked = draft.bodyIsBase64
        (enabled as android.widget.CheckBox).isChecked = draft.enabled
        val fulfill = draft.action == MockAction.FULFILL
        actionFulfill.isChecked = fulfill
        actionFail.isChecked = !fulfill
        current = draft
    }

    private fun onActionChange(action: MockAction) {
        current = current.copy(action = action)
    }

    private fun readDraft(): MockRuleDraft {
        val status = statusCode.text.toString().trim().toIntOrNull() ?: current.statusCode
        val base = current.copy(
            action = if (actionFulfill.isChecked) MockAction.FULFILL else MockAction.FAIL,
            urlPattern = urlPattern.text?.toString() ?: "",
            method = method.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            priority = priority.text?.toString()?.trim()?.toIntOrNull() ?: 0,
            statusCode = status,
            statusText = null,
            responseHeaders = parseHeaders(headers.text?.toString() ?: ""),
            body = body.text?.toString()?.takeIf { it.isNotEmpty() },
            failureReason = NetworkFailureReason.fromCdp(failureReason.text?.toString()?.trim() ?: "Failed"),
        )
        return base
    }

    private fun renderHeaders(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { (k, v) -> "$k: $v" }

    private fun parseHeaders(input: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        input.lines().filter { it.isNotBlank() }.forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                result[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        return result
    }
}