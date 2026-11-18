package com.coppermux.cutools.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.coppermux.cutools.R
import com.coppermux.cutools.core.target.TargetInfo

/**
 * Adapter for displaying discovered Chrome targets in the validation UI.
 */
class TargetAdapter(
    private val onItemClick: ((TargetInfo) -> Unit)? = null,
) : ListAdapter<TargetInfo, TargetAdapter.TargetViewHolder>(DiffCallback) {

    private val targets = mutableMapOf<String, TargetInfo>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TargetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_target, parent, false)
        return TargetViewHolder(view)
    }

    override fun onBindViewHolder(holder: TargetViewHolder, position: Int) {
        val target = getItem(position)
        holder.bind(target, onItemClick)
    }

    fun addOrUpdate(target: TargetInfo) {
        targets[target.targetId] = target
        submitList(targets.values.toList())
    }

    fun remove(targetId: String) {
        targets.remove(targetId)
        submitList(targets.values.toList())
    }

    fun submitNewList(list: List<TargetInfo>) {
        targets.clear()
        list.forEach { targets[it.targetId] = it }
        super.submitList(list)
    }

    fun clear() {
        targets.clear()
        submitList(emptyList())
    }

    class TargetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleText: TextView = view.findViewById(R.id.targetTitle)
        private val typeText: TextView = view.findViewById(R.id.targetType)
        private val urlText: TextView = view.findViewById(R.id.targetUrl)
        private val attachedText: TextView = view.findViewById(R.id.targetAttached)

        fun bind(target: TargetInfo, onItemClick: ((TargetInfo) -> Unit)?) {
            titleText.text = target.title ?: "(no title)"
            typeText.text = target.type
            urlText.text = target.url ?: ""
            attachedText.text = if (target.attached) "attached" else ""
            attachedText.visibility = if (target.attached) View.VISIBLE else View.GONE

            if (onItemClick != null) {
                itemView.setOnClickListener { onItemClick(target) }
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<TargetInfo>() {
            override fun areItemsTheSame(oldItem: TargetInfo, newItem: TargetInfo): Boolean {
                return oldItem.targetId == newItem.targetId
            }

            override fun areContentsTheSame(oldItem: TargetInfo, newItem: TargetInfo): Boolean {
                return oldItem == newItem
            }
        }
    }
}
