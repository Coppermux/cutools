package com.coppermux.cutools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.ui.theme.CuToolsTheme
import com.coppermux.cutools.ui.theme.success

/** Small circular reachability indicator colored by the session's connection status. */
@Composable
fun StatusDot(status: ConnectionStatus, size: Dp = 8.dp) {
    val color = when (status) {
        ConnectionStatus.ACTIVE -> MaterialTheme.colorScheme.success
        ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionStatus.INACTIVE -> MaterialTheme.colorScheme.outline
        ConnectionStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun StatusDotPreview() {
    CuToolsTheme {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(ConnectionStatus.ACTIVE, 10.dp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "ACTIVE", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.width(12.dp))
            StatusDot(ConnectionStatus.CONNECTING, 10.dp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "CONNECTING", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.width(12.dp))
            StatusDot(ConnectionStatus.INACTIVE, 10.dp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "INACTIVE", style = MaterialTheme.typography.labelSmall)
        }
    }
}