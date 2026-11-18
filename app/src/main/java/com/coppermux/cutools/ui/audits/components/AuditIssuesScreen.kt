package com.coppermux.cutools.ui.audits.components

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coppermux.cutools.core.audits.models.AuditCapabilityStatus
import com.coppermux.cutools.core.audits.models.AuditIssueCode
import com.coppermux.cutools.core.audits.models.AuditIssueModel
import com.coppermux.cutools.ui.audits.AuditViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 11B — Audits issue screen.
 *
 * Renders the genuine real-time `Audits.issueAdded` stream from the audit engine
 * grouped by issue code, with severity tags, timestamps, and a details breakdown,
 * plus an explicit capability status banner and action controls (enable/disable
 * the domain, run a contrast check, filter the stream). Pure renderer: all state
 * and actions are delegated to the shared [AuditViewModel] — never synthetic
 * (No-Fake-Capability rule).
 */
@Composable
fun AuditIssuesScreen(
    viewModel: AuditViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val issues by viewModel.engineIssues.collectAsStateWithLifecycle()
    val visible = viewModel.filterIssues(issues, state.searchQuery)

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
                OutlinedButton(onClick = { viewModel.disableAudits() }) {
                    Text("Disable Audits")
                }
            } else {
                Button(onClick = { viewModel.enableAudits() }) {
                    Text("Enable Audits")
                }
            }
            OutlinedButton(
                onClick = { viewModel.checkContrast() },
                enabled = !state.isLoading,
            ) {
                Text(if (state.isLoading) "Checking…" else "Check Contrast")
            }
        }

        if (state.contrastResult != null) {
            ContrastResultCard(result = state.contrastResult!!)
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::updateSearchQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Filter by issue code / detail") },
            singleLine = true,
        )

        IssueList(issues = visible, total = issues.size)
    }
}

@Composable
private fun ContrastResultCard(result: com.coppermux.cutools.core.audits.models.ContrastCheckResultModel) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Contrast: ratio ${"%.2f".format(result.contrastRatio)}  AA ${pass(result.passesAA)}  AAA ${pass(result.passesAAA)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Nodes: ${result.nodeIds.joinToString() }",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun pass(value: Boolean): String = if (value) "PASS" else "FAIL"

@Composable
private fun IssueList(issues: List<AuditIssueModel>, total: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Issues: $total (${issues.size} shown)",
            style = MaterialTheme.typography.titleSmall,
        )
        if (issues.isEmpty()) {
            Text(
                text = "No audit issues observed. Enable Audits and interact with the page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val grouped = issues.groupBy { it.code }
            val orderedCodes = grouped.keys.sortedBy { it.ordinal }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                orderedCodes.forEach { code ->
                    val codeIssues = grouped[code] ?: emptyList()
                    item(key = "header-$code") {
                        Text(
                            text = "${code.name} (${codeIssues.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(codeIssues, key = { "${code}-${it.timestamp}-${it.details.hashCode()}" }) { issue ->
                        IssueRow(issue = issue)
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueRow(issue: AuditIssueModel) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SeverityTag(code = issue.code)
                Text(
                    text = formatTimestamp(issue.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (issue.details.isEmpty()) {
                Text(
                    text = "No details reported",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                issue.details.forEach { (key, value) ->
                    Text(
                        text = "$key: $value",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** Derives a coarse severity for display purposes from the genuine issue code. */
internal fun severityOf(code: AuditIssueCode): String = when (code) {
    AuditIssueCode.SAME_SITE_COOKIE -> "Warning"
    AuditIssueCode.MIXED_CONTENT -> "Error"
    AuditIssueCode.BLOCKED_BY_RESPONSE -> "Error"
    AuditIssueCode.HEAVY_AD -> "Error"
    AuditIssueCode.CONTENT_SECURITY_POLICY -> "Warning"
    AuditIssueCode.SHARED_ARRAY_BUFFER -> "Info"
    AuditIssueCode.ATTRIBUTION_REPORTING -> "Info"
    AuditIssueCode.GENERIC_ISSUE -> "Info"
    AuditIssueCode.UNKNOWN -> "Info"
}

@Composable
private fun SeverityTag(code: AuditIssueCode) {
    val label = "${severityOf(code)} · ${code.name}"
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
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
    status: AuditCapabilityStatus,
    errorMessage: String?,
): String? {
    val banner = errorMessage ?: when (status) {
        AuditCapabilityStatus.UNSUPPORTED ->
            "Unsupported: the Audits domain is not available on this target."
        AuditCapabilityStatus.UNAVAILABLE ->
            "Unavailable: attach a target to inspect audit issues."
        AuditCapabilityStatus.FAILED ->
            "Failed: the Audits domain reported an error."
        AuditCapabilityStatus.SUPPORTED -> null
    }
    return banner
}

private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

internal fun formatTimestamp(timestamp: Long): String = timeFormatter.format(Date(timestamp))