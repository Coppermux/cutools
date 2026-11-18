package com.coppermux.cutools.ui.profiling

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.coppermux.cutools.R

/**
 * RecyclerView adapter rendering the [PerformanceRow]s of the active
 * [ProfilingMode] tab. Each row binds its primary [PerformanceRow.label],
 * secondary [PerformanceRow.detail], and trailing [PerformanceRow.caption].
 */
class PerformanceRowAdapter : RecyclerView.Adapter<PerformanceRowAdapter.RowViewHolder>() {

    private val rows = mutableListOf<PerformanceRow>()

    fun submit(rows: List<PerformanceRow>) {
        this.rows.clear()
        this.rows.addAll(rows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_performance_row, parent, false)
        return RowViewHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    inner class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.performanceRowLabel)
        private val detail: TextView = view.findViewById(R.id.performanceRowDetail)
        private val caption: TextView = view.findViewById(R.id.performanceRowCaption)

        fun bind(row: PerformanceRow) {
            label.text = row.label
            detail.text = row.detail
            caption.text = row.caption
            caption.visibility = if (row.caption.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}