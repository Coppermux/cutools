package com.coppermux.cutools.ui.securitystorage

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.coppermux.cutools.R

/**
 * RecyclerView adapter rendering the [SecurityStorageRow]s of the active
 * [com.coppermux.cutools.tools.securitystorage.models.SecurityStorageTab] tab.
 * Each row binds its primary [SecurityStorageRow.label], secondary
 * [SecurityStorageRow.detail], and trailing [SecurityStorageRow.caption].
 */
class SecurityStorageRowAdapter : RecyclerView.Adapter<SecurityStorageRowAdapter.RowViewHolder>() {

    private val rows = mutableListOf<SecurityStorageRow>()

    fun submit(rows: List<SecurityStorageRow>) {
        this.rows.clear()
        this.rows.addAll(rows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_securitystorage_row, parent, false)
        return RowViewHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    inner class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.securityStorageRowLabel)
        private val detail: TextView = view.findViewById(R.id.securityStorageRowDetail)
        private val caption: TextView = view.findViewById(R.id.securityStorageRowCaption)

        fun bind(row: SecurityStorageRow) {
            label.text = row.label
            detail.text = row.detail
            caption.text = row.caption
            caption.visibility = if (row.caption.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}