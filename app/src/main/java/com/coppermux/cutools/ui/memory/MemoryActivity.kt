package com.coppermux.cutools.ui.memory

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.ui.memory.components.MemoryProfilerScreen

/**
 * Phase 11B — Memory profiler tool host.
 *
 * Mounts the [MemoryProfilerScreen] Compose view backed by the shared
 * [MemoryViewModel] from [CuToolsApp]. The activity is a pure renderer: it never
 * talks to raw CDP and never owns browser truth — all state and actions flow
 * through the ViewModel to the memory engine.
 */
class MemoryActivity : AppCompatActivity() {

    private val viewModel get() = (application as CuToolsApp).memoryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this).apply {
            setContent { MemoryProfilerScreen(viewModel = viewModel) }
        }
        setContentView(composeView)
    }
}