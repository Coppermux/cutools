package com.coppermux.cutools.ui.accessibility.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coppermux.cutools.core.accessibility.models.AXNodeModel
import com.coppermux.cutools.core.accessibility.models.AXTreeModel
import com.coppermux.cutools.core.accessibility.models.AccessibilityCapabilityStatus
import com.coppermux.cutools.ui.accessibility.AccessibilityViewModel

/**
 * Phase 9C — Accessibility tree screen.
 *
 * Renders the genuine [AXTreeModel] loaded from the accessibility engine with
 * an interactive expandable tree, a real-time search bar (filtered via
 * [AccessibilityViewModel.filteredTree]), an explicit capability status banner
 * for UNSUPPORTED / UNAVAILABLE / FAILED engine outcomes, and a node detail
 * panel ([AccessibilityNodeDetail]) for the selected node. Pure renderer: all
 * state and actions are delegated to the shared [AccessibilityViewModel] —
 * never synthetic (No-Fake-Capability rule).
 */
@Composable
fun AccessibilityTreeScreen(
    viewModel: AccessibilityViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var expandedNodeIds by rememberSaveable { mutableStateOf(setOf<String>()) }

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

        SearchAndActions(
            query = state.searchQuery,
            onQueryChange = viewModel::updateSearchQuery,
            isLoading = state.isLoading,
            hasTree = state.axTree != null,
            onLoad = { viewModel.loadFullTree() },
            onRefresh = { viewModel.refresh() },
        )

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.axTree == null -> {
                EmptyTreeHint()
            }

            state.selectedNode != null -> {
                AccessibilityNodeDetail(
                    node = state.selectedNode!!,
                    onClose = viewModel::clearSelection,
                )
            }

            else -> {
                TreeList(
                    tree = viewModel.filteredTree,
                    expandedNodeIds = expandedNodeIds,
                    onToggleExpanded = { id ->
                        expandedNodeIds = if (id in expandedNodeIds) {
                            expandedNodeIds - id
                        } else {
                            expandedNodeIds + id
                        }
                    },
                    onSelectNode = viewModel::selectNode,
                )
            }
        }
    }
}

@Composable
private fun SearchAndActions(
    query: String,
    onQueryChange: (String) -> Unit,
    isLoading: Boolean,
    hasTree: Boolean,
    onLoad: () -> Unit,
    onRefresh: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Search role / name / value") },
        singleLine = true,
        enabled = hasTree,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hasTree) {
            Button(onClick = onLoad, enabled = !isLoading) {
                Text(if (isLoading) "Loading…" else "Load accessibility tree")
            }
        } else {
            OutlinedButton(onClick = onRefresh, enabled = !isLoading) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun EmptyTreeHint() {
    Text(
        text = "No accessibility tree loaded. Attach a target and load the full AX tree.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Flattens a tree for vertical rendering: root nodes first, then each expanded
 * node's children in document order, tagged with their visual depth. Expansion
 * state is UI-local; the tree structure itself is genuine.
 */
internal fun flattenTree(tree: AXTreeModel, expandedNodeIds: Set<String>): List<TreeRow> {
    val byId = tree.nodes.associateBy { it.nodeId }
    val referenced = tree.nodes.flatMap { it.childIds }.toSet()
    val roots = tree.rootNodeId?.let { listOf(it) }
        ?: tree.nodes.map { it.nodeId }.filter { it !in referenced }
    val rows = mutableListOf<TreeRow>()

    fun visit(nodeId: String, depth: Int) {
        val node = byId[nodeId] ?: return
        val children = node.childIds.filter { it in byId }
        rows.add(TreeRow(node = node, depth = depth, hasChildren = children.isNotEmpty()))
        if (nodeId in expandedNodeIds) {
            children.forEach { visit(it, depth + 1) }
        }
    }

    roots.forEach { visit(it, 0) }
    return rows
}

@Composable
private fun TreeList(
    tree: AXTreeModel?,
    expandedNodeIds: Set<String>,
    onToggleExpanded: (String) -> Unit,
    onSelectNode: (String) -> Unit,
) {
    val rows = if (tree == null) emptyList() else flattenTree(tree, expandedNodeIds)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(rows, key = { it.node.nodeId }) { row ->
            TreeRowItem(
                row = row,
                expanded = row.node.nodeId in expandedNodeIds,
                onToggleExpanded = onToggleExpanded,
                onSelectNode = onSelectNode,
            )
        }
        if (rows.isEmpty()) {
            item { Text("No matching nodes.", modifier = Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun TreeRowItem(
    row: TreeRow,
    expanded: Boolean,
    onToggleExpanded: (String) -> Unit,
    onSelectNode: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 16).dp),
        color = if (expanded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectNode(row.node.nodeId) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val toggle = if (row.hasChildren) {
                if (expanded) "▾" else "▸"
            } else {
                "·"
            }
            Text(
                text = toggle,
                modifier = Modifier
                    .clickable(enabled = row.hasChildren) { onToggleExpanded(row.node.nodeId) }
                    .padding(end = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.node.name?.value ?: row.node.role?.value ?: row.node.nodeId,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.node.role?.value ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
    status: AccessibilityCapabilityStatus,
    errorMessage: String?,
): String? {
    val banner = errorMessage ?: when (status) {
        AccessibilityCapabilityStatus.UNSUPPORTED ->
            "Unsupported: the Accessibility domain is not available on this target."
        AccessibilityCapabilityStatus.UNAVAILABLE ->
            "Unavailable: attach a target to inspect the accessibility tree."
        AccessibilityCapabilityStatus.FAILED ->
            "Failed: the Accessibility domain reported an error."
        AccessibilityCapabilityStatus.SUPPORTED -> null
    }
    return banner
}

/** A flattened tree row: the genuine node plus its computed visual depth. */
internal data class TreeRow(
    val node: AXNodeModel,
    val depth: Int,
    val hasChildren: Boolean,
)