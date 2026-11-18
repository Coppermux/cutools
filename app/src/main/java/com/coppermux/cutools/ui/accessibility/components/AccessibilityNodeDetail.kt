package com.coppermux.cutools.ui.accessibility.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coppermux.cutools.core.accessibility.models.AXNodeModel
import com.coppermux.cutools.core.accessibility.models.AXPropertyModel
import com.coppermux.cutools.core.accessibility.models.AXValueModel

/**
 * Phase 9C — Accessibility node detail panel.
 *
 * Renders the genuine [AXNodeModel] properties resolved from Chrome: role,
 * name, description, value, property list, ignored reasons, child references,
 * and the backend DOM node id. Pure presentation — every field maps strictly
 * from the parsed node; nothing is fabricated (No-Fake-Capability rule).
 */
@Composable
fun AccessibilityNodeDetail(
    node: AXNodeModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = node.role?.value ?: "Unlabeled node",
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = onClose) {
                Text("Close")
            }
        }

        Text(
            text = "Node: ${node.nodeId}${node.backendDOMNodeId?.let { " (backend #$it)" } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        DetailRow(label = "Name", value = node.name?.value)
        DetailRow(label = "Description", value = node.description?.value)
        DetailRow(label = "Value", value = node.value?.value)

        if (node.ignored) {
            Text(
                text = "Ignored node",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (node.ignoredReasons.isNotEmpty()) {
            SectionTitle("Ignored reasons")
            node.ignoredReasons.forEach { property ->
                PropertyRow(property)
            }
        }

        if (node.properties.isNotEmpty()) {
            SectionTitle("Properties")
            node.properties.forEach { property ->
                PropertyRow(property)
            }
        }

        if (node.childIds.isNotEmpty()) {
            SectionTitle("Children")
            Text(
                text = node.childIds.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
    )
    HorizontalDivider()
}

@Composable
private fun DetailRow(label: String, value: String?) {
    Text(
        text = "$label: ${value ?: "—"}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PropertyRow(property: AXPropertyModel) {
    val display = property.value.value
        ?: when (property.value.type) {
            "boolean" -> "true"
            else -> "—"
        }
    Text(
        text = "${property.name}: $display",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Helper resolving a genuinely-parsed [AXValueModel] into a display string
 * without ever fabricating a value for a node that Chrome did not report.
 */
fun AXValueModel.displayValue(): String = value ?: "—"