package com.coppermux.cutools.ui.network

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.coppermux.cutools.R
import com.coppermux.cutools.core.network.MockResponseRuleSpec

/**
 * Rule-list dialog for the Interception Rules editor.
 *
 * Lists the authored [MockResponseRuleSpec]s with their enablement flag and
 * offers per-rule Toggle/Edit/Delete actions plus a Create button. All state
 * changes are reported back through callbacks so the parent owns the rule set.
 */
class MockRulesDialog(
    context: Context,
    private var rules: List<MockResponseRuleSpec>,
    private val onToggle: (String) -> Unit,
    private val onEdit: (MockResponseRuleSpec) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onCreate: () -> Unit,
) : Dialog(context) {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_mock_rules)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        list = findViewById(R.id.rulesContent)

        findViewById<View>(R.id.rulesCreateButton).setOnClickListener {
            onCreate()
            dismiss()
        }

        render()
    }

    /** Refreshes the listed rules after a parent-side change. */
    fun update(rules: List<MockResponseRuleSpec>) {
        this.rules = rules
        render()
    }

    private fun render() {
        list.removeAllViews()
        if (rules.isEmpty()) {
            addEmpty()
            return
        }
        rules.forEach { rule -> addRuleRow(rule) }
    }

    private fun addEmpty() {
        val tv = TextView(context)
        tv.text = "No interception rules yet. Create one with the button above."
        tv.textSize = 13f
        tv.setPadding(0, 8, 0, 8)
        list.addView(tv)
    }

    private fun addRuleRow(rule: MockResponseRuleSpec) {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, 6, 0, 6)

        val title = LinearLayout(context)
        title.orientation = LinearLayout.HORIZONTAL
        row.addView(title)

        val status = TextView(context)
        status.text = if (rule.enabled) "●" else "○"
        status.textSize = 16f
        status.setTextColor(if (rule.enabled) 0xFF2E7D32.toInt() else 0xFF9E9E9E.toInt())
        title.addView(status)

        val pattern = TextView(context)
        pattern.text = rule.urlPattern
        pattern.textSize = 13f
        pattern.setTypeface(Typeface.MONOSPACE)
        pattern.setPadding(8, 0, 0, 0)
        title.addView(pattern)

        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        row.addView(actions)

        actions.addView(actionButton("Toggle") { onToggle(rule.id) })
        actions.addView(actionButton("Edit") { onEdit(rule) })
        actions.addView(actionButton("Delete") { onDelete(rule.id) })

        val meta = TextView(context)
        val method = rule.method ?: "any"
        val action = rule.action.name
        meta.text = "$method · $action · status ${rule.statusCode} · priority ${rule.priority}"
        meta.textSize = 11f
        meta.setTextColor(0xFF616161.toInt())
        meta.setPadding(0, 2, 0, 0)
        row.addView(meta)

        list.addView(row)
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView {
        val btn = TextView(context)
        btn.text = label
        btn.textSize = 12f
        btn.setTypeface(null, Typeface.BOLD)
        btn.setTextColor(0xFF1565C0.toInt())
        btn.setPadding(0, 6, 12, 0)
        btn.setOnClickListener { onClick() }
        return btn
    }
}