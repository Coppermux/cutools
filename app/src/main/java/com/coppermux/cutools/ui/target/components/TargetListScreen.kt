package com.coppermux.cutools.ui.target.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coppermux.cutools.core.target.models.TargetCapabilityStatus
import com.coppermux.cutools.core.target.models.TargetInfoModel
import com.coppermux.cutools.ui.target.TargetViewModel

/**
 * Phase 13 — Target discovery list screen.
 *
 * Renders the genuine real-time target discovery stream through the shared
 * [TargetViewModel], with a discovery toggle (`Target.setDiscoverTargets`),
 * refresh (`Target.getTargets`), a title/type/url filter over the live stream,
 * and interactive Attach / Detach actions (`Target.attachToTarget` /
 * `Target.detachFromTarget`) reflecting the active CDP session context. An
 * explicit capability banner surfaces every engine failure; no target state is
 * ever fabricated (No-Fake-Capability rule).
 */
@Composable
fun TargetListScreen(
    viewModel: TargetViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineTargets by viewModel.engineTargets.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = viewModel.filterTargets(engineTargets, search)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        capabilityBanner(state.status, state.errorMessage)?.let {
            TargetBanner(text = it)
        }

        SectionHeader("Target discovery")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Discover targets", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.discoveryEnabled,
                enabled = viewModel.isAttached,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        viewModel.enableTargetDiscovery()
                    } else {
                        viewModel.disableTargetDiscovery()
                    }
                },
            )
        }
        OutlinedButton(
            onClick = { viewModel.refreshTargets() },
            enabled = viewModel.isAttached && !state.isLoading,
        ) {
            Text(if (state.isLoading) "Refreshing…" else "Refresh targets")
        }

        HorizontalDivider()

        SectionHeader("Filter targets")
        OutlinedTextField(
            value = search,
            onValueChange = { newQuery ->
                search = newQuery
                viewModel.updateSearchQuery(newQuery)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Title, type, or URL") },
            singleLine = true,
        )

        HorizontalDivider()

        SectionHeader("Targets (${filtered.size})")
        if (filtered.isEmpty()) {
            Text(
                text = if (engineTargets.isEmpty()) {
                    "No targets discovered yet. Enable discovery to stream real-time targets."
                } else {
                    "No targets match the current filter."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            filtered.forEach { target ->
                TargetRow(
                    viewModel = viewModel,
                    target = target,
                    attachedTargetId = state.attachedTargetId,
                    isLoading = state.isLoading,
                )
            }
        }
    }
}

@Composable
private fun TargetRow(
    viewModel: TargetViewModel,
    target: TargetInfoModel,
    attachedTargetId: String?,
    isLoading: Boolean,
) {
    val isAttached = target.targetId == attachedTargetId
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isAttached) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = target.title.ifEmpty { "(no title)" },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "${target.type} · ${target.targetId}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = target.url.ifEmpty { "(no URL)" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
            if (isAttached) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.detachFromTarget(target.targetId) },
                        enabled = !isLoading,
                    ) {
                        Text("Detach")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.attachToTarget(target.targetId) },
                        enabled = viewModel.isAttached && !isLoading,
                    ) {
                        Text("Attach")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun TargetBanner(text: String) {
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
 * Derives an explicit banner text for a target capability status, or null when
 * the domain is genuinely supported and no error is pending.
 */
internal fun capabilityBanner(
    status: TargetCapabilityStatus,
    errorMessage: String?,
): String? {
    val banner = errorMessage ?: when (status) {
        TargetCapabilityStatus.UNSUPPORTED ->
            "Unsupported: the Target domain is not available on this target."
        TargetCapabilityStatus.UNAVAILABLE ->
            "Unavailable: attach a target to manage discovery and sessions."
        TargetCapabilityStatus.FAILED ->
            "Failed: the Target domain reported an error."
        TargetCapabilityStatus.SUPPORTED -> null
    }
    return banner
}