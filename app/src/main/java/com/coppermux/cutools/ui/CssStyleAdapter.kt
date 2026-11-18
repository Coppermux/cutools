package com.coppermux.cutools.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.coppermux.cutools.R
import com.coppermux.cutools.core.css.CssComputedStyleProperty
import com.coppermux.cutools.core.css.CssDeclaration
import com.coppermux.cutools.core.css.CssDeclarationStatus
import com.coppermux.cutools.core.css.CssRule
import com.coppermux.cutools.core.css.CssStyleSheet

/**
 * Adapter that renders the flat CSS inspection rows: section headers, rules,
 * declarations, box-model lines, computed-style rows, and plain text notices.
 *
 * The rule/source association is computed by the caller (which owns the
 * stylesheet map); the adapter only renders structured rows.
 */
class CssStyleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** A single rendered row in the CSS panel. */
    sealed interface Row {
        data class Section(val title: String) : Row
        data class Rule(val rule: CssRule, val stylesheet: CssStyleSheet?) : Row
        data class Declaration(val decl: CssDeclaration, val indent: Boolean) : Row
        data class Text(val message: String) : Row
        data class Computed(val prop: CssComputedStyleProperty) : Row
        data class BoxLine(val label: String, val value: String) : Row
    }

    private val rows = mutableListOf<Row>()

    fun submit(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Section -> TYPE_SECTION
        is Row.Rule -> TYPE_RULE
        is Row.Declaration -> TYPE_DECLARATION
        is Row.Computed -> TYPE_COMPUTED
        is Row.BoxLine -> TYPE_BOX
        is Row.Text -> TYPE_TEXT
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_SECTION -> SectionViewHolder(inflate(parent, R.layout.item_css_section))
            TYPE_RULE -> RuleViewHolder(inflate(parent, R.layout.item_css_rule))
            TYPE_DECLARATION -> DeclarationViewHolder(inflate(parent, R.layout.item_css_declaration))
            TYPE_COMPUTED -> ComputedViewHolder(inflate(parent, R.layout.item_css_computed))
            TYPE_BOX -> BoxLineViewHolder(inflate(parent, R.layout.item_css_computed))
            else -> TextViewHolder(inflate(parent, R.layout.item_css_section))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Section -> (holder as SectionViewHolder).bind(row.title)
            is Row.Rule -> (holder as RuleViewHolder).bind(row.rule, row.stylesheet)
            is Row.Declaration -> (holder as DeclarationViewHolder).bind(row.decl, row.indent)
            is Row.Computed -> (holder as ComputedViewHolder).bind(row.prop)
            is Row.BoxLine -> (holder as BoxLineViewHolder).bind(row.label, row.value)
            is Row.Text -> (holder as TextViewHolder).bind(row.message)
        }
    }

    private fun inflate(parent: ViewGroup, layout: Int): View =
        LayoutInflater.from(parent.context).inflate(layout, parent, false)

    class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.cssSectionTitle)
        fun bind(text: String) {
            title.text = text
        }
    }

    class RuleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val selector: TextView = view.findViewById(R.id.cssRuleSelector)
        private val meta: TextView = view.findViewById(R.id.cssRuleMeta)
        fun bind(rule: CssRule, stylesheet: CssStyleSheet?) {
            selector.text = rule.selectorText
            val origin = rule.origin.displayLabel
            val source = stylesheet?.displayName ?: "unknown stylesheet"
            val location = rule.sourceRange?.let { "${it.startLine + 1}:${it.startColumn}" }
            val layer = rule.layers.firstOrNull()?.text
            val container = rule.containerQueries.firstOrNull()?.conditionText
            meta.text = buildString {
                if (origin.isNotEmpty()) append(origin)
                if (source.isNotEmpty() && source != origin) {
                    if (isNotEmpty()) append(" · ")
                    append(source)
                }
                if (location != null) append(":$location")
                if (layer != null) {
                    if (isNotEmpty()) append(" · ")
                    append("@layer $layer")
                }
                if (container != null) {
                    if (isNotEmpty()) append(" · ")
                    append("@container $container")
                }
            }.ifEmpty { " " }
        }
    }

    class DeclarationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.cssDeclName)
        private val value: TextView = view.findViewById(R.id.cssDeclValue)
        private val status: TextView = view.findViewById(R.id.cssDeclStatus)
        fun bind(decl: CssDeclaration, indent: Boolean) {
            name.text = decl.name
            value.text = if (decl.important) "${decl.value} !important" else decl.value
            val pad = if (indent) 24 else 12
            name.setPadding(pad, 0, 0, 0)
            when (decl.status) {
                CssDeclarationStatus.ACTIVE -> {
                    status.visibility = View.VISIBLE
                    status.text = "✓"
                    status.setTextColor(0xFF2E7D32.toInt())
                }
                CssDeclarationStatus.OVERRIDDEN -> {
                    status.visibility = View.VISIBLE
                    status.text = "✕"
                    status.setTextColor(0xFF9E9E9E.toInt())
                }
                else -> {
                    status.visibility = View.VISIBLE
                    status.text = ""
                    status.setTextColor(0xFF9E9E9E.toInt())
                }
            }
        }
    }

    class ComputedViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.cssComputedLabel)
        private val value: TextView = view.findViewById(R.id.cssComputedValue)
        fun bind(prop: CssComputedStyleProperty) {
            label.text = prop.name
            value.text = prop.value
        }
    }

    class BoxLineViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.cssComputedLabel)
        private val value: TextView = view.findViewById(R.id.cssComputedValue)
        fun bind(rowLabel: String, rowValue: String) {
            label.text = rowLabel
            value.text = rowValue
        }
    }

    class TextViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.cssSectionTitle)
        fun bind(message: String) {
            text.text = message
            text.setPadding(12, 2, 12, 2)
        }
    }

    companion object {
        const val TYPE_SECTION = 0
        const val TYPE_RULE = 1
        const val TYPE_DECLARATION = 2
        const val TYPE_TEXT = 3
        const val TYPE_COMPUTED = 4
        const val TYPE_BOX = 5
    }
}