package com.coppermux.cutools.ui.memory.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coppermux.cutools.core.memory.models.DomCountersModel
import com.coppermux.cutools.core.memory.models.HeapSamplingProfileModel
import com.coppermux.cutools.core.memory.models.MemoryCapabilityStatus
import com.coppermux.cutools.ui.memory.MemoryViewModel
import com.coppermux.cutools.ui.memory.SamplingRow

/**
 * Phase 11B — Memory profiler screen.
 *
 * Renders controls for triggering a manual GC, viewing live DOM counters
 * (documents, nodes, event listeners), starting/stopping heap sampling, and
 * inspecting the genuine sampled allocation tree, plus the streaming heap
 * snapshot progress and an explicit capability status banner. Pure renderer: all
 * state and actions are delegated to the shared [MemoryViewModel] — never
 * synthetic (No-Fake-Capability rule).
 */
@Composable
fun MemoryProfilerScreen(
    viewModel: MemoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshotChunk by viewModel.heapSnapshotStream.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val banner = capabilityBanner(state.status, state.errorMessage)
        if (banner != null) {
            CapabilityBanner(text = banner)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.triggerGarbageCollection() },
                enabled = !state.isLoading,
            ) {
                Text("Run GC")
            }
            OutlinedButton(
                onClick = { viewModel.fetchDomCounters() },
                enabled = !state.isLoading,
            ) {
                Text(if (state.isLoading) "Fetching…" else "Fetch DOM Counters")
            }
        }

        DomCountersCard(counters = state.domCounters)

        SamplingControl(
            samplingActive = state.samplingActive,
            isLoading = state.isLoading,
            onStart = { viewModel.startSampling() },
            onStop = { viewModel.stopSampling() },
        )

        if (state.samplingProfile != null) {
            SamplingProfileView(profile = state.samplingProfile!!, viewModel = viewModel)
        }

        SnapshotControl(
            active = state.heapSnapshotActive,
            isLoading = state.isLoading,
            chunk = snapshotChunk,
            onTake = { viewModel.takeHeapSnapshot() },
        )
    }
}

@Composable
private fun DomCountersCard(counters: DomCountersModel?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "DOM Counters",
                style = MaterialTheme.typography.titleSmall,
            )
            if (counters == null) {
                Text(
                    text = "No counters fetched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Documents: ${counters.documents}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "Nodes: ${counters.nodes}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "JS Event Listeners: ${counters.jsEventListeners}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun SamplingControl(
    samplingActive: Boolean,
    isLoading: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (samplingActive) "Sampling active" else "Sampling idle",
            style = MaterialTheme.typography.bodyMedium,
            color = if (samplingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (samplingActive) {
                OutlinedButton(onClick = onStop, enabled = !isLoading) {
                    Text("Stop Sampling")
                }
            } else {
                Button(onClick = onStart, enabled = !isLoading) {
                    Text("Start Sampling")
                }
            }
        }
    }
}

@Composable
private fun SamplingProfileView(profile: HeapSamplingProfileModel, viewModel: MemoryViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Sampling profile · ${profile.samplesCount} samples",
            style = MaterialTheme.typography.titleSmall,
        )
        val rows = viewModel.flattenProfile(profile)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(rows, key = { it.id }) { row ->
                SamplingRowItem(row = row)
            }
            if (rows.isEmpty()) {
                item { Text("No sampled allocations reported.") }
            }
        }
    }
}

@Composable
private fun SamplingRowItem(row: SamplingRow) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 16).dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = row.function,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "${row.url}${row.line?.let { ":$it" } ?: ""} · ${row.selfSize} bytes",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun SnapshotControl(
    active: Boolean,
    isLoading: Boolean,
    chunk: String,
    onTake: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (active) "Streaming heap snapshot…" else "Heap snapshot idle",
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onTake, enabled = !isLoading && !active) {
                Text(if (isLoading) "Taking…" else "Take Snapshot")
            }
        }
        if (chunk.isNotEmpty()) {
            Text(
                text = "Stream: ${chunk.length} chars",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CapabilityBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Derives an explicit banner text for a capability status, or null when the
 * domain is genuinely supported and no error is pending.
 */
internal fun capabilityBanner(
    status: MemoryCapabilityStatus,
    errorMessage: String?,
): String? {
    val banner = errorMessage ?: when (status) {
        MemoryCapabilityStatus.UNSUPPORTED ->
            "Unsupported: the HeapProfiler/Memory domain is not available on this target."
        MemoryCapabilityStatus.UNAVAILABLE ->
            "Unavailable: attach a target to profile memory."
        MemoryCapabilityStatus.FAILED ->
            "Failed: the HeapProfiler/Memory domain reported an error."
        MemoryCapabilityStatus.SUPPORTED -> null
    }
    return banner
}