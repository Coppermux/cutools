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

/**
 * Detail inspector dialog for a single network request.
 *
 * Presents three panes — Headers (General/'Request Headers'/'Response Headers'),
 * Response Body (pretty text, binary, or an explicit evicted/missing capability
 * message), and Timing (DNS/SSL/TTFB/Content Download breakdown). The dialog is
 * a pure renderer: it displays [NetworkDetailViewModel.state] and forwards a
 * body-fetch request to [onRequestBody] when the BODY tab is shown without a
 * resolved body.
 */
class NetworkDetailDialog(
    context: Context,
    private var viewModel: NetworkDetailViewModel,
    private val onRequestBody: (String) -> Unit,
) : Dialog(context) {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_network_detail)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        content = findViewById(R.id.detailContent)

        findViewById<View>(R.id.detailTabHeaders).setOnClickListener { switch(NetworkDetailTab.HEADERS) }
        findViewById<View>(R.id.detailTabBody).setOnClickListener {
            switch(NetworkDetailTab.BODY)
            ensureBody()
        }
        findViewById<View>(R.id.detailTabTiming).setOnClickListener { switch(NetworkDetailTab.TIMING) }

        render()
    }

    /** Reflects a refreshed view-model in the dialog. */
    fun update(viewModel: NetworkDetailViewModel) {
        this.viewModel = viewModel
        render()
    }

    private fun ensureBody() {
        val request = viewModel.state.request ?: return
        if (!viewModel.state.bodyResolved && !viewModel.bodyLoading) {
            onRequestBody(request.requestId)
        }
    }

    private fun switch(tab: NetworkDetailTab) {
        viewModel = viewModel.setTab(tab)
        render()
    }

    private fun render() {
        syncTabButtons()
        content.removeAllViews()
        val state = viewModel.state
        val title = findViewById<TextView>(R.id.detailTitle)
        title.text = state.request?.let { "Request Detail · ${it.requestId}" } ?: "Request Detail"
        when (state.tab) {
            NetworkDetailTab.HEADERS -> renderHeaders(state)
            NetworkDetailTab.BODY -> renderBody(state)
            NetworkDetailTab.TIMING -> renderTiming(state)
        }
    }

    private fun renderHeaders(state: NetworkDetailState) {
        section("General")
        state.generalRows.forEach { addRow(it) }
        section("Request Headers")
        state.requestHeaderRows.forEach { addRow(it) }
        section("Response Headers")
        state.responseHeaderRows.forEach { addRow(it) }
    }

    private fun renderBody(state: NetworkDetailState) {
        if (viewModel.bodyLoading && !state.bodyResolved) {
            addParagraph("Loading response body…")
            return
        }
        addParagraph(state.bodyDisplay)
    }

    private fun renderTiming(state: NetworkDetailState) {
        state.timingRows.forEach { addRow(it) }
    }

    // ================================================================
    // Widget helpers
    // ================================================================

    private fun section(label: String) {
        val heading = TextView(context)
        heading.text = label
        heading.textSize = 13f
        heading.setTypeface(null, Typeface.BOLD)
        heading.setTextColor(0xFF616161.toInt())
        heading.setPadding(0, 8, 0, 2)
        content.addView(heading)
    }

    private fun addParagraph(text: String) {
        val tv = TextView(context)
        tv.text = text
        tv.textSize = 12f
        tv.setTypeface(Typeface.MONOSPACE)
        tv.setPadding(0, 4, 0, 4)
        content.addView(tv)
    }

    private fun addRow(row: NameValue) {
        val line = LinearLayout(context)
        line.orientation = LinearLayout.HORIZONTAL
        line.setPadding(0, 2, 0, 2)

        val label = TextView(context)
        label.text = row.label + ": "
        label.textSize = 12f
        label.setTypeface(null, Typeface.BOLD)
        line.addView(label)

        val value = TextView(context)
        value.text = row.value
        value.textSize = 12f
        value.setTypeface(Typeface.MONOSPACE)
        line.addView(value)

        content.addView(line)
    }

    private fun syncTabButtons() {
        findViewById<View>(R.id.detailTabHeaders).isEnabled = viewModel.state.tab != NetworkDetailTab.HEADERS
        findViewById<View>(R.id.detailTabBody).isEnabled = viewModel.state.tab != NetworkDetailTab.BODY
        findViewById<View>(R.id.detailTabTiming).isEnabled = viewModel.state.tab != NetworkDetailTab.TIMING
    }
}