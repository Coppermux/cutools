package com.coppermux.cutools.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coppermux.cutools.core.browser.DashboardFilter
import com.coppermux.cutools.ui.theme.dashboardActionContainer
import com.coppermux.cutools.ui.theme.recordingIndicator

/**
 * Phase 18 / Redesign — symmetrical 3-part static action toolbar.
 *
 * Replaces the scrollable filter chip row with a static action toolbar:
 *  - Left Button: Filter Dropdown ("All ▾", shape CircleShape, weight 1f)
 *  - Center Button: Record ("Record", shape CircleShape, weight 1f)
 *  - Right Button: Clear ("Clear", shape CircleShape, weight 1f)
 */
@Composable
fun DashboardFilterBar(
    selectedFilter: DashboardFilter = DashboardFilter.ALL,
    searchQuery: String = "",
    isRecording: Boolean = true,
    onFilterSelected: (DashboardFilter) -> Unit = {},
    onSearchQueryChanged: (String) -> Unit = {},
    onClearFeed: () -> Unit = {},
    toggleRecording: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var filterMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left Button (Filter Dropdown)
        Box(modifier = Modifier.weight(1f)) {
            Button(
                onClick = { filterMenuOpen = !filterMenuOpen },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.dashboardActionContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text(
                    text = if (selectedFilter == DashboardFilter.ALL) "All ▾" else "${selectedFilter.displayLabel} ▾",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                )
            }
            DropdownMenu(
                expanded = filterMenuOpen,
                onDismissRequest = { filterMenuOpen = false },
            ) {
                DashboardFilter.entries.forEach { filter ->
                    DropdownMenuItem(
                        text = { Text(filter.displayLabel) },
                        onClick = {
                            onFilterSelected(filter)
                            filterMenuOpen = false
                        },
                    )
                }
            }
        }

        // Center Button (Record / Pause)
        Button(
            onClick = toggleRecording,
            modifier = Modifier.weight(1f),
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.dashboardActionContainer,
                contentColor = if (isRecording) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Filled.FiberManualRecord else Icons.Filled.Pause,
                contentDescription = if (isRecording) "Record" else "Paused",
                modifier = Modifier.size(16.dp),
                tint = if (isRecording) MaterialTheme.colorScheme.recordingIndicator else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isRecording) "Record" else "Paused",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isRecording) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
        }

        // Right Button (Clear)
        Button(
            onClick = onClearFeed,
            modifier = Modifier.weight(1f),
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.dashboardActionContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Clear",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Clear",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
        }
    }
}