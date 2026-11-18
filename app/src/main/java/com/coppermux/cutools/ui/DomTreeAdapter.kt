package com.coppermux.cutools.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.coppermux.cutools.R
import com.coppermux.cutools.core.dom.DomNode

/**
 * Adapter that renders a flat list of [DomNode] (the current visible tree
 * rows). Expansion state and indentation are computed by the caller via
 * [FlatNode].
 */
class DomTreeAdapter(
    private val onNodeClick: ((DomNode) -> Unit)? = null,
    private val onExpandToggle: ((DomNode) -> Unit)? = null,
) : RecyclerView.Adapter<DomTreeAdapter.NodeViewHolder>() {

    private val rows = mutableListOf<FlatNode>()

    /** A DOM tree row with its computed indentation depth. */
    data class FlatNode(val node: DomNode, val depth: Int)

    fun submit(nodes: List<FlatNode>) {
        rows.clear()
        rows.addAll(nodes)
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = rows.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dom_node, parent, false)
        return NodeViewHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
        holder.bind(rows[position], onNodeClick, onExpandToggle)
    }

    class NodeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.domNodeLabel)
        private val hasChildren: TextView = view.findViewById(R.id.domNodeHasChildren)

        fun bind(
            row: FlatNode,
            onNodeClick: ((DomNode) -> Unit)?,
            onExpandToggle: ((DomNode) -> Unit)?,
        ) {
            val node = row.node
            val indent = row.depth * 16
            label.text = node.displayLabel
            label.setPadding(indent, 0, 0, 0)
            hasChildren.text = if (node.childNodeCount > 0) "${node.childNodeCount}▸" else ""

            itemView.setOnClickListener {
                onNodeClick?.invoke(node)
                onExpandToggle?.invoke(node)
            }
        }
    }
}
