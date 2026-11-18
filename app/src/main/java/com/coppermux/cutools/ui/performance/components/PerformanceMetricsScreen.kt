package com.coppermux.cutools.ui.performance.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coppermux.cutools.core.performance.models.PerformanceCapabilityStatus
import com.coppermux.cutools.core.performance.models.PerformanceMetricsSnapshotModel
import com.coppermux.cutools.ui.performance.PerformanceViewModel

/**
 * Phase 11B — Performance metrics screen.
 *
 * Renders a key-value grid and historical line visualization of the genuine
 * Chrome `Performance` metrics (e.g. JSHeapUsedSize, LayoutCount,
 * RecalcStyleCount) streamed from the performance engine, plus an explicit
 * capability status banner and action controls (enable/disable the domain,
 * refresh metrics, filter the metric set). Pure renderer: all state and actions
 * are delegated to the shared [PerformanceViewModel] — never synthetic
 * (No-Fake-Capability rule).
 */
@Composable
fun PerformanceMetricsScreen(
    viewModel: PerformanceViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current by viewModel.metrics.collectAsStateWithLifecycle()

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
            if (state.enabled) {
                OutlinedButton(onClick = { viewModel.disablePerformance() }) {
                    Text("Disable")
                }
            } else {
                Button(onClick = { viewModel.enablePerformance() }) {
                    Text("Enable")
                }
            }
            OutlinedButton(
                onClick = { viewModel.refreshMetrics() },
                enabled = !state.isLoading,
            ) {
                Text(if (state.isLoading) "Refreshing…" else "Refresh")
            }
        }

        OutlinedTextField(
            value = state.metricFilter,
            onValueChange = viewModel::updateMetricFilter,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Filter metric names") },
            singleLine = true,
        )

        val snapshot = current ?: state.currentMetrics
        if (snapshot == null) {
            Text(
                text = "No metrics observed yet. Enable the Performance domain and interact with the page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val names = viewModel.filteredMetricNames(snapshot)
            MetricGrid(snapshot = snapshot, names = names)
            if (state.timeline.isNotEmpty()) {
                MetricTimeline(
                    timeline = state.timeline,
                    names = names,
                )
            }
        }
    }
}

@Composable
private fun MetricGrid(snapshot: PerformanceMetricsSnapshotModel, names: List<String>) {
    val byName = snapshot.metrics.associateBy { it.name }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Metrics (${names.size})",
            style = MaterialTheme.typography.titleSmall,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(names) { name ->
                val value = byName[name]
                MetricRow(name = name, value = value?.value)
            }
        }
    }
}

@Composable
private fun MetricRow(name: String, value: Double?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = value?.let { formatMetric(it) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Draws a compact line visualization for each selected metric across the
 * genuine timeline history. This is a pure presentation of genuine values —
 * never fabricated data.
 */
@Composable
private fun MetricTimeline(timeline: List<PerformanceMetricsSnapshotModel>, names: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "History (${timeline.size} snapshots)",
            style = MaterialTheme.typography.titleSmall,
        )
        names.take(6).forEach { name ->
            val series = timeline.mapNotNull { snap ->
                snap.metrics.firstOrNull { it.name == name }?.value
            }
            if (series.isNotEmpty()) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                MetricSparkline(series = series, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MetricSparkline(series: List<Double>, color: Color) {
    val max = series.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
    val min = series.minOrNull() ?: 0.0
    val span = (max - min).takeIf { it > 0.0 } ?: 1.0
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(vertical = 4.dp),
    ) {
        if (series.size < 2) return@Canvas
        val stepX = size.width / (series.size - 1)
        val points = series.mapIndexed { index, value ->
            val x = index * stepX
            val normalized = ((value - min) / span).toFloat().coerceIn(0f, 1f)
            val y = size.height - normalized * size.height
            Offset(x, y)
        }
        drawLine(
            color = color,
            start = points.first(),
            end = points.last(),
        )
        for (i in 0 until points.size - 1) {
            drawLine(
                color = color,
                start = points[i],
                end = points[i + 1],
            )
        }
    }
}

internal fun formatMetric(value: Double): String =
    if (value >= 1000.0) {
        "%.1fK".format(value / 1000.0)
    } else {
        "%.0f".format(value)
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
    status: PerformanceCapabilityStatus,
    errorMessage: String?,
): String? {
    val banner = errorMessage ?: when (status) {
        PerformanceCapabilityStatus.UNSUPPORTED ->
            "Unsupported: the Performance domain is not available on this target."
        PerformanceCapabilityStatus.UNAVAILABLE ->
            "Unavailable: attach a target to inspect performance metrics."
        PerformanceCapabilityStatus.FAILED ->
            "Failed: the Performance domain reported an error."
        PerformanceCapabilityStatus.SUPPORTED -> null
    }
    return banner
}