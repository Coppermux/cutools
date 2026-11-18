package com.coppermux.cutools.ui.shell

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.core.browser.WebViewEngine
import com.coppermux.cutools.core.diagnostic.DiagnosticTracer
import dagger.hilt.android.AndroidEntryPoint

/**
 * Phase 17/18 — the unified shell host activity.
 *
 * The direct launch experience: renders the Active Dev Workspace through
 * [MainContainer], backed by the shared [ShellViewModel] from [CuToolsApp] and
 * driving the embedded [WebViewEngine] for the Phase 18 browser viewport.
 * Pure renderer — all state and actions flow through the ViewModel to the
 * genuine application engines.
 */
@AndroidEntryPoint
class ShellActivity : AppCompatActivity() {

    private val viewModel get() = (application as CuToolsApp).shellViewModel
    private val webViewEngine: WebViewEngine get() = (application as CuToolsApp).webViewEngine

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticTracer.trace("ACTIVITY", "ON_CREATE")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        val composeView = ComposeView(this).apply {
            setContent {
                MainContainer(
                    viewModel = viewModel,
                    webViewEngine = webViewEngine,
                )
            }
        }

        setContentView(composeView)
    }

    override fun onStart() {
        super.onStart()
        DiagnosticTracer.trace("ACTIVITY", "ON_START")
    }

    override fun onResume() {
        super.onResume()
        DiagnosticTracer.trace("ACTIVITY", "ON_RESUME")
    }

    override fun onPause() {
        super.onPause()
        DiagnosticTracer.trace("ACTIVITY", "ON_PAUSE")
    }

    override fun onStop() {
        super.onStop()
        DiagnosticTracer.trace("ACTIVITY", "ON_STOP")
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticTracer.trace("ACTIVITY", "ON_DESTROY")
    }
}
