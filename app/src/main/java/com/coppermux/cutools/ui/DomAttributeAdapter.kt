package com.coppermux.cutools.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.coppermux.cutools.R
import com.coppermux.cutools.core.dom.DomAttribute

/**
 * Adapter that displays the attributes of the selected DOM node.
 *
 * Phase 2C: each attribute exposes an Edit (setAttributeValue) and a Remove
 * (removeAttribute) action. The adapter only reports the user intent; the
 * actual mutation runs through [com.coppermux.cutools.core.dom.DomInspector].
 */
class DomAttributeAdapter(
    private val onEdit: ((DomAttribute) -> Unit)? = null,
    private val onRemove: ((DomAttribute) -> Unit)? = null,
) : RecyclerView.Adapter<DomAttributeAdapter.AttrViewHolder>() {

    private val attrs = mutableListOf<DomAttribute>()

    /** Whether mutation controls should be enabled (selected node is editable). */
    var editable: Boolean = true

    fun submit(list: List<DomAttribute>, editable: Boolean = this.editable) {
        attrs.clear()
        attrs.addAll(list)
        this.editable = editable
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttrViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dom_attribute, parent, false)
        return AttrViewHolder(view)
    }

    override fun getItemCount(): Int = attrs.size

    override fun onBindViewHolder(holder: AttrViewHolder, position: Int) {
        holder.bind(attrs[position], editable, onEdit, onRemove)
    }

    class AttrViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameText: TextView = view.findViewById(R.id.attrName)
        private val valueText: TextView = view.findViewById(R.id.attrValueText)
        private val editButton: View = view.findViewById(R.id.attrEditButton)
        private val removeButton: View = view.findViewById(R.id.attrRemoveButton)

        fun bind(attr: DomAttribute, editable: Boolean, onEdit: ((DomAttribute) -> Unit)?, onRemove: ((DomAttribute) -> Unit)?) {
            nameText.text = attr.name
            valueText.text = attr.value ?: ""
            editButton.visibility = if (editable && onEdit != null) View.VISIBLE else View.GONE
            removeButton.visibility = if (editable && onRemove != null) View.VISIBLE else View.GONE
            editButton.setOnClickListener { onEdit?.invoke(attr) }
            removeButton.setOnClickListener { onRemove?.invoke(attr) }
        }
    }
}
