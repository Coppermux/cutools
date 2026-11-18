package com.coppermux.cutools.ui.pager

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.coppermux.cutools.ui.browser.BrowserScreen
import com.coppermux.cutools.ui.components.GlobalUrlBar
import com.coppermux.cutools.ui.homescreen.WorkspacesScreen
import kotlinx.coroutines.launch

/**
 * Phase C — the main app pager.
 *
 * Screen 0 renders the Workspaces grid; tapping a session loads it into the
 * shared [UrlBarState] and swipes to Screen 1 (the browser stub). Swiping is
 * the only navigation chrome — no bottom nav, tab labels, buttons, or page
 * indicators.
 */
@Composable
fun CuToolsPager(modifier: Modifier = Modifier) {
    val urlBarState = remember { UrlBarState() }
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })

    Scaffold(
        topBar = {
            GlobalUrlBar(
                url = urlBarState.url,
                connectionStatus = urlBarState.status,
                isHttps = urlBarState.isHttps,
                onUrlSubmit = { urlBarState.url = it },
                onClear = { urlBarState.clear() },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { page ->
            when (page) {
                0 -> WorkspacesScreen(
                    onNavigateToBrowser = { session ->
                        urlBarState.loadSession(session)
                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                    },
                )
                1 -> BrowserScreen()
            }
        }
    }
}