package com.coppermux.cutools.ui.securitystorage

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.R
import com.coppermux.cutools.core.storage.models.StorageTypeModel
import com.coppermux.cutools.databinding.ActivitySecuritystorageBinding
import com.coppermux.cutools.tools.securitystorage.models.SecurityStorageTab

/**
 * Sub-Phase 8D — Security & Storage Tool UI.
 *
 * Renders the unified [com.coppermux.cutools.tools.securitystorage.viewmodel.SecurityStorageToolViewModel]
 * snapshot. A top tab bar (Security / Storage Quota / IndexedDB /
 * Cache Storage), an origin input field for the storage actions, a status line,
 * an `UNSUPPORTED` / `UNAVAILABLE` / `FAILED` banner derived from genuine
 * engine-resolved errors (No-Fake-Capability rule), and a tab-specific row list.
 *
 * The activity is a pure renderer: it displays the snapshot and forwards user
 * actions to the shared ViewModel, which delegates to the security/storage
 * engines. It never talks to raw CDP and never owns browser truth.
 */
class SecurityStorageToolScreen : AppCompatActivity() {

    private lateinit var binding: ActivitySecuritystorageBinding
    private lateinit var adapter: SecurityStorageRowAdapter

    private val app get() = application as CuToolsApp
    private val viewModel get() = app.securityStorageToolViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecuritystorageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SecurityStorageRowAdapter()
        binding.securityStorageRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.securityStorageRecyclerView.adapter = adapter

        setupTabSelector()
        setupActions()
        refresh()
    }

    // ================================================================
    // Tab selector
    // ================================================================

    private fun setupTabSelector() {
        binding.securityStorageTabGroup.setOnCheckedStateChangeListener { _, _ ->
            viewModel.selectTab(checkedTab())
            refresh()
        }
        when (viewModel.tab) {
            SecurityStorageTab.SECURITY -> binding.securityStorageTabSecurity.isChecked = true
            SecurityStorageTab.STORAGE_QUOTA -> binding.securityStorageTabQuota.isChecked = true
            SecurityStorageTab.INDEXED_DB -> binding.securityStorageTabIndexedDb.isChecked = true
            SecurityStorageTab.CACHE_STORAGE -> binding.securityStorageTabCache.isChecked = true
        }
    }

    private fun checkedTab(): SecurityStorageTab = when (binding.securityStorageTabGroup.checkedChipId) {
        R.id.securityStorageTabQuota -> SecurityStorageTab.STORAGE_QUOTA
        R.id.securityStorageTabIndexedDb -> SecurityStorageTab.INDEXED_DB
        R.id.securityStorageTabCache -> SecurityStorageTab.CACHE_STORAGE
        else -> SecurityStorageTab.SECURITY
    }

    // ================================================================
    // Action controls
    // ================================================================

    private fun setupActions() {
        binding.securityStoragePrimaryButton.setOnClickListener { onPrimaryAction() }
        binding.securityStorageSecondaryButton.setOnClickListener { onSecondaryAction() }
    }

    private fun origin(): String =
        binding.securityStorageOriginInput.text?.toString() ?: ""

    private fun onPrimaryAction() {
        if (!viewModel.isAttached) {
            binding.securityStorageStatusText.text = "Attach a target before running actions."
            return
        }
        when (viewModel.tab) {
            SecurityStorageTab.SECURITY ->
                viewModel.enableSecurity { runOnUiThread { refresh() } }

            SecurityStorageTab.STORAGE_QUOTA ->
                viewModel.refreshQuota(origin()) { runOnUiThread { refresh() } }

            SecurityStorageTab.INDEXED_DB ->
                viewModel.requestDatabaseNames(origin()) { runOnUiThread { refresh() } }

            SecurityStorageTab.CACHE_STORAGE ->
                viewModel.requestCacheNames(origin()) { runOnUiThread { refresh() } }
        }
    }

    private fun onSecondaryAction() {
        if (!viewModel.isAttached) return
        when (viewModel.tab) {
            SecurityStorageTab.SECURITY ->
                viewModel.disableSecurity { runOnUiThread { refresh() } }

            SecurityStorageTab.STORAGE_QUOTA ->
                viewModel.clearDataForOrigin(origin(), listOf(StorageTypeModel.ALL)) { runOnUiThread { refresh() } }

            SecurityStorageTab.INDEXED_DB -> {}
            SecurityStorageTab.CACHE_STORAGE -> {}
        }
    }

    // ================================================================
    // Rendering
    // ================================================================

    private fun refresh() {
        binding.securityStorageStatusText.text = SecurityStoragePresentation.statusText(viewModel.state)
        binding.securityStorageTargetText.text = viewModel.state.targetId?.let { "Target: $it" } ?: "Target: —"

        val banner = SecurityStoragePresentation.banner(viewModel.state)
        binding.securityStorageBannerText.text = banner ?: ""
        binding.securityStorageBannerText.visibility = if (banner == null) View.GONE else View.VISIBLE

        val rows = SecurityStoragePresentation.rows(viewModel.state)
        adapter.submit(rows)
        binding.securityStorageSummaryText.text = "${rows.size} entries"
        updateButtons()
    }

    private fun updateButtons() {
        val snapshot = viewModel.state
        when (snapshot.tab) {
            SecurityStorageTab.SECURITY -> {
                binding.securityStoragePrimaryButton.text = if (snapshot.securityEnabled) "Enabled" else "Enable"
                binding.securityStorageSecondaryButton.text = "Disable"
            }
            SecurityStorageTab.STORAGE_QUOTA -> {
                binding.securityStoragePrimaryButton.text = "Refresh Quota"
                binding.securityStorageSecondaryButton.text = "Clear All Data"
            }
            SecurityStorageTab.INDEXED_DB -> {
                binding.securityStoragePrimaryButton.text = "Refresh Databases"
                binding.securityStorageSecondaryButton.text = "—"
                binding.securityStorageSecondaryButton.isEnabled = false
            }
            SecurityStorageTab.CACHE_STORAGE -> {
                binding.securityStoragePrimaryButton.text = "Refresh Buckets"
                binding.securityStorageSecondaryButton.text = "—"
                binding.securityStorageSecondaryButton.isEnabled = false
            }
        }
        binding.securityStorageSecondaryButton.isEnabled =
            snapshot.tab == SecurityStorageTab.SECURITY || snapshot.tab == SecurityStorageTab.STORAGE_QUOTA
    }
}