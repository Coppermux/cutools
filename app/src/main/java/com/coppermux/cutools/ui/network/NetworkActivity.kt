package com.coppermux.cutools.ui.network

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.R
import com.coppermux.cutools.core.network.FetchInterceptionListener
import com.coppermux.cutools.core.network.FetchInterceptionState
import com.coppermux.cutools.core.network.MockResponseRuleSpec
import com.coppermux.cutools.core.network.NetworkEngineListener
import com.coppermux.cutools.core.network.NetworkRequestModel
import com.coppermux.cutools.core.network.NetworkState
import com.coppermux.cutools.core.network.ResponseBodyResult
import com.coppermux.cutools.databinding.ActivityNetworkBinding

/**
 * Phase 5D — UI Network Monitor, Detail Inspector and Interception Rules.
 *
 * Renders the structured [NetworkState] reported by the Network engine with
 * color-coded status indicators (2xx green / 3xx blue / 4xx–5xx red / pending
 * grey / failed dark red), resource-type chips and a live search filter, and a
 * tap-to-inspect detail dialog (Headers / Response Body / Timing). The
 * Interception Rules editor operates on the shared [MockResponseRuleManager].
 *
 * The activity is a pure renderer: it displays view-models and forwards user
 * actions to the engines it never owns browser truth and never talks to raw
 * CDP. Every engine callback is marshalled onto the UI thread.
 */
class NetworkActivity :
    AppCompatActivity(),
    NetworkEngineListener,
    FetchInterceptionListener {

    private lateinit var binding: ActivityNetworkBinding
    private lateinit var adapter: NetworkRequestAdapter

    private var monitor = NetworkMonitorViewModel.empty()
    private var detail = NetworkDetailViewModel()
    private var nextRuleNumber = 1

    private var detailDialog: NetworkDetailDialog? = null
    private var rulesDialog: MockRulesDialog? = null

    private val app get() = application as CuToolsApp
    private val networkEngine get() = app.networkEngine
    private val fetchEngine get() = app.fetchInterceptionEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = NetworkRequestAdapter { onRequestClicked(it) }
        binding.networkRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.networkRecyclerView.adapter = adapter

        setupSearch()
        setupChips()

        binding.networkInspectButton.setOnClickListener { inspectSelection() }
        binding.networkAddRuleButton.setOnClickListener { showRulesDialog() }

        networkEngine.addListener(this)
        fetchEngine.addListener(this)

        refresh()
    }

    // ================================================================
    // Engine listeners
    // ================================================================

    override fun onNetworkStateChanged(state: NetworkState) = runOnUiThread { refresh() }
    override fun onFetchStateChanged(state: FetchInterceptionState) = runOnUiThread { refresh() }
    override fun onError(error: Throwable) = runOnUiThread {
        binding.networkStatusText.text = "Error: ${error.message}"
    }

    // ================================================================
    // Filter controls
    // ================================================================

    private fun setupSearch() {
        binding.networkSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncingSearch) return
                monitor = monitor.withFilter(monitor.filter.copy(searchText = s?.toString() ?: ""))
                refresh()
            }
        })
        binding.networkSearchClearButton.setOnClickListener {
            binding.networkSearchInput.text?.clear()
            monitor = monitor.withFilter(NetworkRequestFilter())
            refresh()
        }
        binding.networkFilterGroup.setOnCheckedStateChangeListener { _, _ ->
            monitor = monitor.withFilter(monitor.filter.copy(resourceType = checkedType()))
            refresh()
        }
    }

    private fun checkedType(): NetworkResourceType = when (binding.networkFilterGroup.checkedChipId) {
        R.id.networkTypeFetchXhr -> NetworkResourceType.FETCH_XHR
        R.id.networkTypeJs -> NetworkResourceType.JS
        R.id.networkTypeCss -> NetworkResourceType.CSS
        R.id.networkTypeImg -> NetworkResourceType.IMG
        R.id.networkTypeMedia -> NetworkResourceType.MEDIA
        R.id.networkTypeDoc -> NetworkResourceType.DOC
        R.id.networkTypeOther -> NetworkResourceType.OTHER
        else -> NetworkResourceType.ALL
    }

    private fun setupChips() {
        binding.networkTypeAll.isChecked = true
    }

    // ================================================================
    // Rendering
    // ================================================================

    private fun refresh() {
        monitor = monitor.copy(network = networkEngine.state, interception = fetchEngine.state)
        render()
    }

    private fun render() {
        adapter.submit(monitor.visibleRequests)
        binding.networkStatusText.text = monitor.statusText()
        binding.networkTargetText.text = monitor.network.targetId?.let { "Target: $it" } ?: "Target: —"
        binding.networkSummaryText.text = when {
            monitor.isInactive -> "Attach a target to start monitoring."
            monitor.visibleCount == 0 -> "No requests match the current filter."
            else -> "${monitor.visibleCount}/${monitor.totalCount} request(s)"
        }
        binding.networkEmptyText.text = if (monitor.visibleCount == 0) {
            if (monitor.filter.isActive) "No requests match the current filter." else "No requests yet."
        } else {
            ""
        }
        binding.networkEmptyText.visibility = if (monitor.visibleCount == 0) View.VISIBLE else View.GONE

        detail = detail.refresh(monitor.selectedRequest)
        detailDialog?.update(detail)
    }

    @Volatile
    private var syncingSearch = false

    // ================================================================
    // Detail inspector
    // ================================================================

    private fun onRequestClicked(request: NetworkRequestModel) {
        monitor = monitor.select(request.requestId)
        detail = detail.select(request)
        showDetailDialog()
    }

    private fun inspectSelection() {
        val request = monitor.selectedRequest
        if (request == null) {
            binding.networkStatusText.text = "Inspect: select a request first."
            return
        }
        detail = detail.select(request)
        showDetailDialog()
    }

    private fun showDetailDialog() {
        if (detailDialog == null) {
            detailDialog = NetworkDetailDialog(this, detail, ::fetchBody)
        } else {
            detailDialog?.update(detail)
        }
        detailDialog?.show()
    }

    private fun fetchBody(requestId: String) {
        detail = detail.loadingBody()
        detailDialog?.update(detail)
        networkEngine.getResponseBody(requestId) { result: ResponseBodyResult ->
            runOnUiThread {
                detail = detail.applyBody(result)
                detailDialog?.update(detail)
            }
        }
    }

    // ================================================================
    // Interception rules
    // ================================================================

    private fun showRulesDialog() {
        if (rulesDialog == null) {
            rulesDialog = MockRulesDialog(
                context = this,
                rules = rules(),
                onToggle = ::toggleRule,
                onEdit = ::editRule,
                onDelete = ::deleteRule,
                onCreate = ::createRule,
            )
        } else {
            rulesDialog?.update(rules())
        }
        rulesDialog?.show()
    }

    private fun rules() = fetchEngine.ruleManager.all()

    private fun createRule() {
        val dialog = MockRuleDialog(
            context = this,
            draft = MockRuleDraft(),
            editing = false,
            onSave = { draft -> commitRule(draft.toRule(nextRuleId())) },
            onDelete = null,
        )
        dialog.show()
    }

    private fun editRule(rule: MockResponseRuleSpec) {
        val dialog = MockRuleDialog(
            context = this,
            draft = MockRuleDraft.fromRule(rule),
            editing = true,
            onSave = { draft -> commitRule(draft.toRule(rule.id)) },
            onDelete = { deleteRule(rule.id) },
        )
        dialog.show()
    }

    private fun commitRule(rule: MockResponseRuleSpec) {
        fetchEngine.ruleManager.upsert(rule)
        rulesDialog?.update(rules())
    }

    private fun deleteRule(ruleId: String) {
        if (fetchEngine.ruleManager.remove(ruleId)) {
            rulesDialog?.update(rules())
        }
    }

    private fun toggleRule(ruleId: String) {
        val rule = fetchEngine.ruleManager.find(ruleId)
        if (rule != null) commitRule(rule.copy(enabled = !rule.enabled))
    }

    private fun nextRuleId(): String {
        val id = "rule-$nextRuleNumber"
        nextRuleNumber++
        return id
    }

    override fun onDestroy() {
        networkEngine.removeListener(this)
        fetchEngine.removeListener(this)
        rulesDialog?.dismiss()
        detailDialog?.dismiss()
        super.onDestroy()
    }
}