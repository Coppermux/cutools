package com.coppermux.cutools.ui.target

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.ui.target.components.TargetListScreen

/**
 * Phase 13 — Target discovery tool host.
 *
 * Mounts the [TargetListScreen] Compose view backed by the shared
 * [TargetViewModel] from [CuToolsApp]. The activity is a pure renderer: it never
 * talks to raw CDP and never owns browser truth — all state and actions flow
 * through the ViewModel to the target engine.
 */
class TargetActivity : AppCompatActivity() {

    private val viewModel get() = (application as CuToolsApp).targetViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this).apply {
            setContent { TargetListScreen(viewModel = viewModel) }
        }
        setContentView(composeView)
    }
}