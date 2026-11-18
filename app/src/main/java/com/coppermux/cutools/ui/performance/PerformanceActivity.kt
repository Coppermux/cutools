package com.coppermux.cutools.ui.performance

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.ui.performance.components.PerformanceMetricsScreen

/**
 * Phase 11B — Performance metrics tool host.
 *
 * Mounts the [PerformanceMetricsScreen] Compose view backed by the shared
 * [PerformanceViewModel] from [CuToolsApp]. The activity is a pure renderer: it
 * never talks to raw CDP and never owns browser truth — all state and actions
 * flow through the ViewModel to the performance engine.
 */
class PerformanceActivity : AppCompatActivity() {

    private val viewModel get() = (application as CuToolsApp).performanceViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this).apply {
            setContent { PerformanceMetricsScreen(viewModel = viewModel) }
        }
        setContentView(composeView)
    }
}