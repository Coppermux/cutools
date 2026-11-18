package com.coppermux.cutools.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.coppermux.cutools.R
import com.coppermux.cutools.core.console.ConsoleEntry
import com.coppermux.cutools.core.console.ConsoleSeverity
import com.coppermux.cutools.core.runtime.EvaluationResultKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter that renders a list of [ConsoleEntry].
 *
 * Genuine Chrome console messages/runtime exceptions (Phase 4A) are shown with
 * their severity, message text, timestamp, and source/stack information.
 * CuTools evaluation results (Phase 4B) are shown distinctly as
 * [ConsoleEntryKind.EVALUATION_RESULT] entries with their structured outcome
 * (success / evaluation exception / protocol error) and preserved RemoteObject
 * information.
 *
 * It intentionally does not provide search, filtering, history, or evaluation
 * controls.
 */
class ConsoleEntryAdapter : RecyclerView.Adapter<ConsoleEntryAdapter.EntryViewHolder>() {

    private val rows = mutableListOf<ConsoleEntry>()

    fun submit(entries: List<ConsoleEntry>) {
        rows.clear()
        rows.addAll(entries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_console_entry, parent, false)
        return EntryViewHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val severity: TextView = view.findViewById(R.id.consoleEntrySeverity)
        private val time: TextView = view.findViewById(R.id.consoleEntryTime)
        private val kind: TextView = view.findViewById(R.id.consoleEntryKind)
        private val origin: TextView = view.findViewById(R.id.consoleEntryOrigin)
        private val message: TextView = view.findViewById(R.id.consoleEntryMessage)
        private val source: TextView = view.findViewById(R.id.consoleEntrySource)
        private val stack: TextView = view.findViewById(R.id.consoleEntryStack)

        fun bind(entry: ConsoleEntry) {
            severity.text = entry.severity.displayLabel
            severity.setTextColor(severityColor(entry.severity))
            time.text = entry.timestamp?.let { TIME_FORMAT.format(Date(it.toLong())) } ?: "—"
            message.text = entry.displayText

            if (entry.isEvaluationResult) {
                bindEvaluation(entry)
            } else {
                origin.text = ""
                bindConsoleEvent(entry)
            }
        }

        private fun bindConsoleEvent(entry: ConsoleEntry) {
            kind.text = if (entry.isRuntimeException) entry.kind.displayLabel else ""
            val location = entry.location
            source.text = when {
                location?.url != null -> buildString {
                    append(location.url)
                    location.line?.let { append(':').append(it) }
                    location.column?.let { append(':').append(it) }
                }
                else -> entry.source ?: ""
            }
            stack.text = entry.stackTrace?.let { renderStack(it) } ?: ""
        }

        private fun bindEvaluation(entry: ConsoleEntry) {
            // Developer-tool convenience origin is shown explicitly (never
            // mislabeled as native Chrome when it is CDP-backed/CuTools-only).
            origin.text = entry.origin.displayLabel
            val outcome = entry.evaluation ?: run {
                kind.text = entry.helperLabel ?: "evaluation"
                source.text = ""
                stack.text = ""
                return
            }
            when (val r = outcome.result) {
                is EvaluationResultKind.Success -> {
                    kind.text = entry.helperLabel ?: "evaluation"
                    val ro = r.value
                    source.text = buildString {
                        ro.type?.let { append("type: ").append(it) }
                        ro.subtype?.let { append(" · subtype: ").append(it) }
                        ro.className?.let { append(" · class: ").append(it) }
                        ro.objectId?.let { append("\nobjectId: ").append(it) }
                    }.trim()
                    stack.text = ""
                }
                is EvaluationResultKind.Exception -> {
                    kind.text = (entry.helperLabel ?: "evaluation") + " exception"
                    val d = r.details
                    source.text = buildString {
                        d.url?.let {
                            append(it)
                            d.line?.let { l -> append(':').append(l) }
                            d.column?.let { c -> append(':').append(c) }
                        }
                    }.toString()
                    stack.text = d.stackTrace?.let { renderStack(it) } ?: ""
                }
                is EvaluationResultKind.ProtocolError -> {
                    kind.text = (entry.helperLabel ?: "evaluation") + " error"
                    source.text = ""
                    stack.text = ""
                }
            }
        }

        private fun renderStack(trace: com.coppermux.cutools.core.console.ConsoleStackTrace): String =
            renderFrames(
                description = trace.description,
                frames = trace.callFrames.map {
                    com.coppermux.cutools.core.runtime.EvaluationCallFrame(it.functionName, it.url, it.scriptId, it.line, it.column)
                },
            )

        private fun renderStack(trace: com.coppermux.cutools.core.runtime.EvaluationStackTrace): String =
            renderFrames(trace.description, trace.callFrames)

        private fun renderFrames(
            description: String?,
            frames: List<com.coppermux.cutools.core.runtime.EvaluationCallFrame>,
        ): String = buildString {
            description?.let {
                append(it)
                append('\n')
            }
            frames.forEach { frame ->
                append("    at ")
                append(frame.functionName?.takeIf { it.isNotBlank() } ?: "<anonymous>")
                append(" (")
                append(frame.url?.takeIf { it.isNotBlank() } ?: "<unknown>")
                frame.line?.let { append(':').append(it) }
                frame.column?.let { append(':').append(it) }
                append(')')
                append('\n')
            }
        }.trimEnd()

        private fun severityColor(severity: ConsoleSeverity): Int = when (severity) {
            ConsoleSeverity.INFO -> Color.parseColor("#1565C0")
            ConsoleSeverity.DEBUG -> Color.parseColor("#00695C")
            ConsoleSeverity.WARNING -> Color.parseColor("#E65100")
            ConsoleSeverity.ERROR -> Color.parseColor("#C62828")
            ConsoleSeverity.LOG -> Color.parseColor("#212121")
        }

        companion object {
            private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
        }
    }
}
