package com.coppermux.cutools.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.core.console.ConsoleCapabilityStatus
import com.coppermux.cutools.core.console.ConsoleInspector
import com.coppermux.cutools.core.console.ConsoleInspectorListener
import com.coppermux.cutools.core.console.ConsoleLifecyclePhase
import com.coppermux.cutools.core.console.ConsoleSeverity
import com.coppermux.cutools.core.console.ConsoleState
import com.coppermux.cutools.core.console.SelectedElementPhase
import com.coppermux.cutools.core.runtime.EvaluationOutcome
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.runtime.ExecutionEngineListener
import com.coppermux.cutools.core.runtime.ExecutionState
import com.coppermux.cutools.databinding.ActivityConsoleBinding

/**
 * Complete Phase 4 Console UI.
 *
 * Displays the state of the [ConsoleInspector]: genuine Chrome console
 * messages, runtime exceptions, and CuTools JavaScript evaluation / query
 * results with their severity, message, timestamp, source/stack information,
 * and an explicit developer-tool feature-origin label.
 *
 * Phase 4D UI: console search and severity filtering (CuTools presentation
 * conveniences over the structured Console state) that compose deterministically
 * into the rendered entry set. The UI only renders tool state; it never owns
 * browser truth and never talks to raw CDP.
 */
class ConsoleActivity : AppCompatActivity(), ConsoleInspectorListener, ExecutionEngineListener {

    private lateinit var binding: ActivityConsoleBinding
    private lateinit var console: ConsoleInspector
    private lateinit var engine: ExecutionEngine
    private lateinit var adapter: ConsoleEntryAdapter

    /** Guards against feedback loops when syncing input fields. */
    private var syncingInput = false
    private var syncingSearch = false
    private var syncingFilter = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        console = (application as CuToolsApp).consoleInspector
        engine = (application as CuToolsApp).executionEngine
        console.addListener(this)
        engine.addListener(this)

        adapter = ConsoleEntryAdapter()
        binding.consoleRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.consoleRecyclerView.adapter = adapter

        binding.consoleRunButton.setOnClickListener { submitInput() }
        binding.consoleHistoryBack.setOnClickListener { console.historyBack() }
        binding.consoleHistoryForward.setOnClickListener { console.historyForward() }
        binding.consoleUseDollarButton.setOnClickListener { insertDollarZero() }
        binding.consoleQuerySelectorButton.setOnClickListener { runQuery(false) }
        binding.consoleQuerySelectorAllButton.setOnClickListener { runQuery(true) }
        binding.consoleSearchClearButton.setOnClickListener {
            binding.consoleSearchInput.text?.clear()
            console.clearSearch()
        }

        binding.consoleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncingInput) return
                console.setInput(s?.toString() ?: "")
            }
        })
        binding.consoleInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitInput()
                true
            } else false
        }

        binding.consoleSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncingSearch) return
                console.setSearchQuery(s?.toString().orEmpty())
            }
        })

        binding.consoleQueryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                runQuery(false)
                true
            } else false
        }

        binding.consoleFilterLog.setOnCheckedChangeListener { _, checked -> onSeverityToggle(ConsoleSeverity.LOG, checked) }
        binding.consoleFilterInfo.setOnCheckedChangeListener { _, checked -> onSeverityToggle(ConsoleSeverity.INFO, checked) }
        binding.consoleFilterDebug.setOnCheckedChangeListener { _, checked -> onSeverityToggle(ConsoleSeverity.DEBUG, checked) }
        binding.consoleFilterWarn.setOnCheckedChangeListener { _, checked -> onSeverityToggle(ConsoleSeverity.WARNING, checked) }
        binding.consoleFilterError.setOnCheckedChangeListener { _, checked -> onSeverityToggle(ConsoleSeverity.ERROR, checked) }
        binding.consoleFilterRuntime.setOnCheckedChangeListener { _, checked ->
            if (syncingFilter) return@setOnCheckedChangeListener
            console.setRuntimeExceptionsVisible(checked)
        }

        render(console.state)
        renderExecution(engine.state)
    }

    private fun submitInput() {
        console.submitInput()
    }

    private fun onSeverityToggle(severity: ConsoleSeverity, checked: Boolean) {
        if (syncingFilter) return
        console.setSeverityEnabled(severity, checked)
    }

    /** Inserts the `$0` selected-element reference into the input. */
    private fun insertDollarZero() {
        console.setInput("$0")
        syncingInput = true
        binding.consoleInput.setText("$0")
        binding.consoleInput.setSelection(binding.consoleInput.length())
        syncingInput = false
    }

    private fun runQuery(querySelectorAll: Boolean) {
        val selector = binding.consoleQueryInput.text?.toString().orEmpty()
        if (selector.isBlank()) return
        if (querySelectorAll) console.runQuerySelectorAll(selector) else console.runQuerySelector(selector)
    }

    override fun onConsoleStateChanged(state: ConsoleState) {
        runOnUiThread { render(state) }
    }

    override fun onError(error: Throwable) {
        runOnUiThread {
            binding.consoleStateText.text = "Error: ${error.message}"
        }
    }

    override fun onExecutionStateChanged(state: ExecutionState) {
        runOnUiThread { renderExecution(state) }
    }

    override fun onEvaluationCompleted(outcome: EvaluationOutcome) {
        // Results are integrated into Console state by the tool layer; nothing
        // to do here beyond the state listeners already rendering.
    }

    private fun render(state: ConsoleState) {
        // The visible set is the deterministic intersection of search + filter
        // over the underlying entries (never the underlying entries themselves).
        adapter.submit(state.visibleEntries)
        val filtered = state.visibleCount < state.entries.size
        binding.consoleEmptyText.text = if (filtered) {
            "No console messages match the current search/filter."
        } else {
            "No console messages yet."
        }
        binding.consoleEmptyText.visibility = if (state.visibleEntries.isEmpty()) View.VISIBLE else View.GONE

        binding.consoleTargetText.text = state.targetId?.let { "Target: $it" } ?: "Target: —"

        binding.consoleStateText.text = when (state.phase) {
            ConsoleLifecyclePhase.INACTIVE -> "Not attached to a target"
            ConsoleLifecyclePhase.ENABLING -> "Enabling console…"
            ConsoleLifecyclePhase.READY -> if (filtered) {
                "Ready · ${state.visibleCount}/${state.entries.size} shown"
            } else {
                "Ready · ${state.entries.size} message(s)"
            }
            ConsoleLifecyclePhase.UNSUPPORTED -> "Console unsupported for this target"
            ConsoleLifecyclePhase.FAILED -> "Console failed: ${state.error?.message}"
        }
        val cap = when (state.capability) {
            ConsoleCapabilityStatus.SUPPORTED -> ""
            ConsoleCapabilityStatus.UNKNOWN -> "· capability: probing…"
            ConsoleCapabilityStatus.UNSUPPORTED -> "· capability: unsupported"
            ConsoleCapabilityStatus.FAILED -> "· capability: failed"
        }
        binding.consoleStateText.text = binding.consoleStateText.text.toString() + cap

        // JavaScript evaluation is only meaningful against a ready, attached target.
        binding.consoleRunButton.isEnabled = state.isReady
        binding.consoleInput.isEnabled = state.isReady
        binding.consoleQuerySelectorButton.isEnabled = state.isReady
        binding.consoleQuerySelectorAllButton.isEnabled = state.isReady

        // Command history (CuTools-only): enable navigation within bounds.
        binding.consoleHistoryBack.isEnabled = state.history.canGoBack()
        binding.consoleHistoryForward.isEnabled = state.history.canGoForward()

        // $0 selected-element reference (CDP-backed CuTools convenience).
        val sel = state.selectedElement
        binding.consoleSelectionText.text = when (sel) {
            null -> "Selected element: — ($0 unavailable)"
            else -> when (sel.phase) {
                SelectedElementPhase.NO_SELECTION -> "Selected element: — ($0 unavailable)"
                SelectedElementPhase.RESOLVING -> "Selected element: ${sel.label ?: "node ${sel.nodeId}"} (resolving…)"
                SelectedElementPhase.LOADED -> "Selected element: ${sel.label ?: "node ${sel.nodeId}"} · ${sel.remoteObject?.objectId?.let { "objectId $it" } ?: sel.remoteObject?.displayText ?: ""}"
                SelectedElementPhase.STALE -> "Selected element stale (reference not usable)"
                SelectedElementPhase.UNSUPPORTED -> "Selected-element reference unsupported"
                SelectedElementPhase.FAILED -> "Selected element: ${sel.error?.message}"
            }
        }

        // Query-helper state.
        val qs = state.queryHelperState
        if (qs.running) {
            binding.consoleStateText.text = "Querying… (${qs.activeHelper?.displayLabel ?: "query"})"
        }

        syncPresentationControls(state)
    }

    /** Syncs the search/filter controls from state without feedback loops. */
    private fun syncPresentationControls(state: ConsoleState) {
        syncingSearch = true
        if (binding.consoleSearchInput.text?.toString() != state.searchQuery) {
            binding.consoleSearchInput.setText(state.searchQuery)
        }
        syncingSearch = false

        val filter = state.severityFilter
        syncingFilter = true
        binding.consoleFilterLog.isChecked = ConsoleSeverity.LOG in filter.severities
        binding.consoleFilterInfo.isChecked = ConsoleSeverity.INFO in filter.severities
        binding.consoleFilterDebug.isChecked = ConsoleSeverity.DEBUG in filter.severities
        binding.consoleFilterWarn.isChecked = ConsoleSeverity.WARNING in filter.severities
        binding.consoleFilterError.isChecked = ConsoleSeverity.ERROR in filter.severities
        binding.consoleFilterRuntime.isChecked = filter.includeRuntimeExceptions
        syncingFilter = false

        // Keep the JS input field in sync with tool-owned input state.
        val currentInput = binding.consoleInput.text?.toString() ?: ""
        if (currentInput != state.input) {
            syncingInput = true
            binding.consoleInput.setText(state.input)
            binding.consoleInput.setSelection(binding.consoleInput.length())
            syncingInput = false
        }
    }

    private fun renderExecution(state: ExecutionState) {
        if (state.pendingEvaluations > 0) {
            binding.consoleStateText.text = "Evaluating… (${state.pendingEvaluations} pending)"
        }
    }

    override fun onDestroy() {
        console.removeListener(this)
        engine.removeListener(this)
        super.onDestroy()
    }
}
