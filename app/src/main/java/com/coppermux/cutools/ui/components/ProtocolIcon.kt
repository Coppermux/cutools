package com.coppermux.cutools.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Protocol glyph for the URL bar leading edge: a Lock for HTTPS targets and a
 * Public (http) icon otherwise.
 */
@Composable
fun ProtocolIcon(isHttps: Boolean) {
    Icon(
        imageVector = if (isHttps) Icons.Default.Lock else Icons.Default.Public,
        contentDescription = if (isHttps) "HTTPS" else "HTTP",
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}