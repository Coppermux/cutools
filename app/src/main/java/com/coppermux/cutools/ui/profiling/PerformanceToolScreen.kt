package com.coppermux.cutools.ui.profiling

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.R
import com.coppermux.cutools.core.profiling.models.CpuProfilerStatus
import com.coppermux.cutools.core.profiling.models.TracingStatus
import com.coppermux.cutools.databinding.ActivityPerformanceBinding
import com.coppermux.cutools.tools.profiling.models.ProfilingMode

/**
 * Sub-Phase 7D — Performance Tool UI.
 *
 * Renders the unified [PerformanceToolViewModel] snapshot: a mode selector
 * (Timeline / CPU Profile / Heap Snapshot), mode-specific action controls
 * (Record / Stop / Snapshot / Sample), a status line, and an
 * `UNSUPPORTED` / `UNAVAILABLE` / `FAILED` banner derived from genuine
 * engine-resolved errors (No-Fake-Capability rule).
 *
 * The activity is a pure renderer: it displays the snapshot and forwards user
 * actions to the shared ViewModel, which delegates to the profiling engines. It
 * never talks to raw CDP and never owns browser truth.
 */
class PerformanceToolScreen : AppCompatActivity() {

    private lateinit var binding: ActivityPerformanceBinding
    private lateinit var adapter: PerformanceRowAdapter

    private val app get() = application as CuToolsApp
    private val viewModel get() = app.performanceToolViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPerformanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = PerformanceRowAdapter()
        binding.performanceRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.performanceRecyclerView.adapter = adapter

        setupModeSelector()
        setupActions()
        refresh()
    }

    // ================================================================
    // Mode selector
    // ================================================================

    private fun setupModeSelector() {
        binding.performanceModeGroup.setOnCheckedStateChangeListener { _, _ ->
            viewModel.selectMode(checkedMode())
            refresh()
        }
        when (viewModel.mode) {
            ProfilingMode.TIMELINE -> binding.performanceModeTimeline.isChecked = true
            ProfilingMode.CPU_PROFILE -> binding.performanceModeCpu.isChecked = true
            ProfilingMode.HEAP_SNAPSHOT -> binding.performanceModeHeap.isChecked = true
        }
    }

    private fun checkedMode(): ProfilingMode = when (binding.performanceModeGroup.checkedChipId) {
        R.id.performanceModeCpu -> ProfilingMode.CPU_PROFILE
        R.id.performanceModeHeap -> ProfilingMode.HEAP_SNAPSHOT
        else -> ProfilingMode.TIMELINE
    }

    // ================================================================
    // Action controls
    // ================================================================

    private fun setupActions() {
        binding.performancePrimaryButton.setOnClickListener { onPrimaryAction() }
        binding.performanceSecondaryButton.setOnClickListener { onSecondaryAction() }
    }

    private fun onPrimaryAction() {
        if (!viewModel.isAttached) {
            binding.performanceStatusText.text = "Attach a target before recording."
            return
        }
        when (viewModel.mode) {
            ProfilingMode.TIMELINE ->
                viewModel.startTracing { runOnUiThread { refresh() } }

            ProfilingMode.CPU_PROFILE ->
                viewModel.enableCpuProfiler { runOnUiThread { refresh() } }

            ProfilingMode.HEAP_SNAPSHOT ->
                viewModel.takeHeapSnapshot { runOnUiThread { refresh() } }
        }
    }

    private fun onSecondaryAction() {
        when (viewModel.mode) {
            ProfilingMode.TIMELINE ->
                viewModel.stopTracing { runOnUiThread { refresh() } }

            ProfilingMode.CPU_PROFILE ->
                viewModel.stopCpuProfile { runOnUiThread { refresh() } }

            ProfilingMode.HEAP_SNAPSHOT ->
                viewModel.startHeapSampling { runOnUiThread { refresh() } }
        }
    }

    // ================================================================
    // Rendering
    // ================================================================

    private fun refresh() {
        binding.performanceStatusText.text = PerformancePresentation.statusText(viewModel.state)
        binding.performanceTargetText.text = viewModel.state.targetId?.let { "Target: $it" } ?: "Target: —"

        val banner = PerformancePresentation.banner(viewModel.state)
        binding.performanceBannerText.text = banner ?: ""
        binding.performanceBannerText.visibility = if (banner == null) View.GONE else View.VISIBLE

        val rows = PerformancePresentation.rows(viewModel.state)
        adapter.submit(rows)
        binding.performanceSummaryText.text = "${rows.size} entries"
        updateButtons()
    }

    private fun updateButtons() {
        val snapshot = viewModel.state
        when (snapshot.mode) {
            ProfilingMode.TIMELINE -> {
                val recording = snapshot.tracingStatus == TracingStatus.RECORDING ||
                    snapshot.tracingStatus == TracingStatus.PROCESSING
                binding.performancePrimaryButton.text = if (recording) "Recording…" else "Record"
                binding.performanceSecondaryButton.text = "Stop"
                binding.performanceSecondaryButton.isEnabled = recording
            }

            ProfilingMode.CPU_PROFILE -> {
                val profiling = snapshot.cpuStatus == CpuProfilerStatus.PROFILING ||
                    snapshot.cpuStatus == CpuProfilerStatus.PROCESSING
                binding.performancePrimaryButton.text = if (profiling) "Profiling…" else "Profile"
                binding.performanceSecondaryButton.text = "Stop"
                binding.performanceSecondaryButton.isEnabled = profiling
            }

            ProfilingMode.HEAP_SNAPSHOT -> {
                binding.performancePrimaryButton.text = "Snapshot"
                binding.performanceSecondaryButton.text = "Sample"
                binding.performanceSecondaryButton.isEnabled = snapshot.heapStatus == com.coppermux.cutools.core.profiling.models.HeapProfilerStatus.IDLE
            }
        }
    }
}