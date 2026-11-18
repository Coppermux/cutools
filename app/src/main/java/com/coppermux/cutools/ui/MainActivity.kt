package com.coppermux.cutools.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import com.coppermux.cutools.ui.pager.CuToolsPager
import dagger.hilt.android.AndroidEntryPoint

/**
 * Phase C — main entry host. Renders the two-page CuTools pager
 * (Workspaces / Browser) behind the shared global URL bar.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setContent {
                CuToolsPager()
            }
        })
    }
}