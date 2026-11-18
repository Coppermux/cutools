package com.coppermux.cutools.ui.accessibility

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.ui.accessibility.components.AccessibilityTreeScreen

/**
 * Phase 9C — Accessibility tool host.
 *
 * Mounts the [AccessibilityTreeScreen] Compose view backed by the shared
 * [AccessibilityViewModel] from [CuToolsApp]. The activity is a pure renderer:
 * it never talks to raw CDP and never owns browser truth — all state and
 * actions flow through the ViewModel to the accessibility engine.
 */
class AccessibilityActivity : AppCompatActivity() {

    private val viewModel get() = (application as CuToolsApp).accessibilityViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this).apply {
            setContent { AccessibilityTreeScreen(viewModel = viewModel) }
        }
        setContentView(composeView)
    }
}