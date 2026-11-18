package com.coppermux.cutools.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Placeholder browser page. Stub for a future real browser engine — Phase C
 * only reserves the pager slot.
 */
@Composable
fun BrowserScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Browser",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}