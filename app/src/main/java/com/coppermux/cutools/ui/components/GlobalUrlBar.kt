package com.coppermux.cutools.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coppermux.cutools.data.model.ConnectionStatus

/**
 * Global address bar. Collapses to the bare domain when unfocused and expands
 * to the full URL on focus. Submit normalizes the query (prepending https://
 * when no scheme is present) and forwards it to the caller.
 */
@Composable
fun GlobalUrlBar(
    url: String,
    connectionStatus: ConnectionStatus,
    isHttps: Boolean,
    onUrlSubmit: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isFocused = remember { mutableStateOf(false) }
    val text = remember { mutableStateOf(url) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status = connectionStatus, size = 8.dp)
        Spacer(modifier = Modifier.width(8.dp))
        ProtocolIcon(isHttps = isHttps)
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (text.value.isEmpty() && isFocused.value == false) {
                Text(
                    text = "Enter URL",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = if (isFocused.value) url else extractDomain(url),
                onValueChange = { text.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                singleLine = true,
                maxLines = 1,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val trimmed = text.value.trim()
                    val normalized = if (trimmed.contains("://")) trimmed else "https://$trimmed"
                    onUrlSubmit(normalized)
                }),
            )
        }
        if (text.value.isEmpty() == false && isFocused.value) {
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Clear",
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

private fun extractDomain(url: String): String {
    val withoutScheme = url.removePrefix("https://").removePrefix("http://")
    return withoutScheme.substringBefore("/")
}

@Composable
fun GlobalUrlBarPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Column {
            GlobalUrlBar(
                url = "https://developer.android.com/jetpack/compose",
                connectionStatus = ConnectionStatus.ACTIVE,
                isHttps = true,
                onUrlSubmit = {},
                onClear = {},
            )
            GlobalUrlBar(
                url = "http://example.com",
                connectionStatus = ConnectionStatus.CONNECTING,
                isHttps = false,
                onUrlSubmit = {},
                onClear = {},
            )
        }
    }
}