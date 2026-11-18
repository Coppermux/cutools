package com.coppermux.cutools.ui.network

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.coppermux.cutools.R
import com.coppermux.cutools.core.network.NetworkRequestModel

/**
 * RecyclerView adapter for the real-time request list.
 *
 * Renders each [NetworkRequestModel] with its color-coded status indicator
 * ([NetworkStatusStyle] applies the 2xx/3xx/4xx/5xx/pending/failed color
 * mapping), method, resource type, and URL. Tap callbacks are routed through
 * [onRequestClicked].
 */
class NetworkRequestAdapter(
    private val onRequestClicked: (NetworkRequestModel) -> Unit = {},
) : RecyclerView.Adapter<NetworkRequestAdapter.RequestViewHolder>() {

    private val rows = mutableListOf<NetworkRequestModel>()

    fun submit(requests: List<NetworkRequestModel>) {
        rows.clear()
        rows.addAll(requests)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_request, parent, false)
        return RequestViewHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RequestViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    inner class RequestViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val status: TextView = view.findViewById(R.id.networkRequestStatus)
        private val method: TextView = view.findViewById(R.id.networkRequestMethod)
        private val type: TextView = view.findViewById(R.id.networkRequestType)
        private val timing: TextView = view.findViewById(R.id.networkRequestTiming)
        private val url: TextView = view.findViewById(R.id.networkRequestUrl)

        fun bind(model: NetworkRequestModel) {
            status.text = NetworkStatusStyle.statusLabel(model)
            status.setTextColor(NetworkStatusStyle.colorArgb(model))
            method.text = model.method ?: "—"
            type.text = model.resourceType ?: ""
            timing.text = model.timing?.let {
                com.coppermux.cutools.core.network.ResourceTimingFormatter.format(it)
                    .removePrefix("Timing: ")
            } ?: ""
            url.text = model.url ?: "—"
            itemView.setOnClickListener { onRequestClicked(model) }
        }
    }
}