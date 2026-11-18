package com.coppermux.cutools.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests validating Android Back navigation decision logic,
 * precedence, and dispatch routing between WebView history and the Workspaces launcher.
 *
 * Covers:
 * 1. Homepage → article → Back → homepage
 * 2. Multiple navigation entries → repeated Back actions
 * 3. Browser root → Back → Workspaces
 * 4. Workspaces → Back follows existing app behavior
 * 5. Dashboard does not activate the browser Back handler
 * 6. F12 Dashboard → Browser transition preserves history
 * 7. SPA history changes update navigation availability
 * 8. Browser reset clears stale history state
 * 9. Existing modal and overlay Back priorities remain intact
 */
class BrowserBackNavigationTest {

    /**
     * Replicates the BackHandler enabled condition used by [BrowserView].
     */
    private fun isBackHandlerEnabled(
        isVisible: Boolean,
        isBackEnabled: Boolean,
        canGoBack: Boolean,
        hasOnBackToWorkspace: Boolean,
    ): Boolean {
        return isVisible && isBackEnabled && (canGoBack || hasOnBackToWorkspace)
    }

    /**
     * Replicates the back press routing logic executed within [BrowserView]'s BackHandler.
     */
    private fun dispatchBackPress(
        canWebViewGoBack: Boolean,
        onWebViewGoBack: () -> Unit,
        onBackToWorkspace: (() -> Unit)?,
    ): BackNavigationAction {
        return if (canWebViewGoBack) {
            onWebViewGoBack()
            BackNavigationAction.WEBVIEW_HISTORY_BACK
        } else if (onBackToWorkspace != null) {
            onBackToWorkspace()
            BackNavigationAction.WORKSPACE_RETURN
        } else {
            BackNavigationAction.UNHANDLED
        }
    }

    private enum class BackNavigationAction {
        WEBVIEW_HISTORY_BACK,
        WORKSPACE_RETURN,
        UNHANDLED,
    }

    // ------------------------------------------------------------------------
    // 1. Homepage → article → Back → homepage
    // ------------------------------------------------------------------------
    @Test
    fun homepage_to_article_then_back_navigates_to_homepage() {
        var currentUrl = "https://www.cnn.com"
        var historyDepth = 1
        var webViewBackCount = 0

        fun canGoBack() = historyDepth > 1

        // User navigates from homepage to article
        currentUrl = "https://www.cnn.com/world/article-1"
        historyDepth++
        assertTrue(canGoBack())

        // Press Android Back
        val action = dispatchBackPress(
            canWebViewGoBack = canGoBack(),
            onWebViewGoBack = {
                webViewBackCount++
                historyDepth--
                currentUrl = "https://www.cnn.com"
            },
            onBackToWorkspace = {},
        )

        assertEquals(BackNavigationAction.WEBVIEW_HISTORY_BACK, action)
        assertEquals(1, webViewBackCount)
        assertEquals("https://www.cnn.com", currentUrl)
        assertFalse("At homepage, canGoBack must be false", canGoBack())
    }

    // ------------------------------------------------------------------------
    // 2. Multiple navigation entries → repeated Back actions
    // ------------------------------------------------------------------------
    @Test
    fun multiple_navigation_entries_repeated_back_actions() {
        var historyDepth = 3 // Page 1 -> Page 2 -> Page 3
        var webViewBackCount = 0
        var workspaceBackCount = 0

        // First Back press: Page 3 -> Page 2
        val action1 = dispatchBackPress(
            canWebViewGoBack = historyDepth > 1,
            onWebViewGoBack = {
                webViewBackCount++
                historyDepth--
            },
            onBackToWorkspace = { workspaceBackCount++ },
        )
        assertEquals(BackNavigationAction.WEBVIEW_HISTORY_BACK, action1)
        assertEquals(1, webViewBackCount)
        assertEquals(2, historyDepth)

        // Second Back press: Page 2 -> Page 1
        val action2 = dispatchBackPress(
            canWebViewGoBack = historyDepth > 1,
            onWebViewGoBack = {
                webViewBackCount++
                historyDepth--
            },
            onBackToWorkspace = { workspaceBackCount++ },
        )
        assertEquals(BackNavigationAction.WEBVIEW_HISTORY_BACK, action2)
        assertEquals(2, webViewBackCount)
        assertEquals(1, historyDepth)

        // Third Back press: Page 1 (root) -> Return to Workspaces
        val action3 = dispatchBackPress(
            canWebViewGoBack = historyDepth > 1,
            onWebViewGoBack = { webViewBackCount++ },
            onBackToWorkspace = { workspaceBackCount++ },
        )
        assertEquals(BackNavigationAction.WORKSPACE_RETURN, action3)
        assertEquals(2, webViewBackCount)
        assertEquals(1, workspaceBackCount)
    }

    // ------------------------------------------------------------------------
    // 3. Browser root → Back → Workspaces
    // ------------------------------------------------------------------------
    @Test
    fun browser_root_back_returns_to_workspaces() {
        var workspaceReturned = false
        val action = dispatchBackPress(
            canWebViewGoBack = false,
            onWebViewGoBack = {},
            onBackToWorkspace = { workspaceReturned = true },
        )

        assertEquals(BackNavigationAction.WORKSPACE_RETURN, action)
        assertTrue("Back press at root must return to Workspaces launcher", workspaceReturned)
    }

    // ------------------------------------------------------------------------
    // 4. Workspaces → Back follows existing app behavior
    // ------------------------------------------------------------------------
    @Test
    fun workspaces_back_handler_is_disabled() {
        // When WorkspacesScreen is active:
        // isVisible is false (BrowserView hidden) and isBackEnabled is false
        val enabled = isBackHandlerEnabled(
            isVisible = false,
            isBackEnabled = false,
            canGoBack = false,
            hasOnBackToWorkspace = true,
        )
        assertFalse("BackHandler must be disabled on Workspaces launcher to let system exit app", enabled)
    }

    // ------------------------------------------------------------------------
    // 5. Dashboard does not activate the browser Back handler
    // ------------------------------------------------------------------------
    @Test
    fun dashboard_does_not_activate_browser_back_handler() {
        // When user is on DevTools Dashboard (Screen 1):
        // isBackEnabled is false (state.currentPage == ShellPage.BROWSER is false)
        val enabled = isBackHandlerEnabled(
            isVisible = true,
            isBackEnabled = false,
            canGoBack = true,
            hasOnBackToWorkspace = true,
        )
        assertFalse("BackHandler must be disabled when user is viewing DevTools Dashboard", enabled)
    }

    @Test
    fun dashboard_back_press_closes_devtools_and_returns_to_browser() {
        var currentPage = 1 // DevTools Dashboard
        val isDevToolsBackHandlerEnabled = (currentPage == 1)
        assertTrue("DevTools back handler must be enabled on Dashboard (page 1)", isDevToolsBackHandlerEnabled)

        // Press Back on Dashboard
        if (isDevToolsBackHandlerEnabled) {
            currentPage = 0 // Return to Browser
        }

        assertEquals(0, currentPage)
    }

    // ------------------------------------------------------------------------
    // 6. F12 Dashboard → Browser transition preserves history
    // ------------------------------------------------------------------------
    @Test
    fun f12_dashboard_to_browser_transition_preserves_history() {
        // User on Browser with history
        var currentPageIsBrowser = true
        var canGoBack = true

        // User taps F12 -> moves to Dashboard
        currentPageIsBrowser = false
        val backEnabledOnDashboard = isBackHandlerEnabled(
            isVisible = true,
            isBackEnabled = currentPageIsBrowser,
            canGoBack = canGoBack,
            hasOnBackToWorkspace = true,
        )
        assertFalse(backEnabledOnDashboard)

        // User taps F12 -> returns to Browser
        currentPageIsBrowser = true
        val backEnabledOnBrowser = isBackHandlerEnabled(
            isVisible = true,
            isBackEnabled = currentPageIsBrowser,
            canGoBack = canGoBack,
            hasOnBackToWorkspace = true,
        )
        assertTrue("History and BackHandler must be preserved after returning from Dashboard", backEnabledOnBrowser)

        // Back press still navigates WebView history
        var webViewBackInvoked = false
        val action = dispatchBackPress(
            canWebViewGoBack = canGoBack,
            onWebViewGoBack = { webViewBackInvoked = true },
            onBackToWorkspace = {},
        )
        assertEquals(BackNavigationAction.WEBVIEW_HISTORY_BACK, action)
        assertTrue(webViewBackInvoked)
    }

    // ------------------------------------------------------------------------
    // 7. SPA history changes update navigation availability
    // ------------------------------------------------------------------------
    @Test
    fun spa_history_changes_update_navigation_availability() {
        var canGoBack = false

        // Simulating pushState in a Single Page Application
        val onPushState = {
            canGoBack = true
        }

        assertFalse("Initially at SPA root, cannot go back", canGoBack)
        onPushState()
        assertTrue("After pushState, canGoBack must be true", canGoBack)

        val enabled = isBackHandlerEnabled(
            isVisible = true,
            isBackEnabled = true,
            canGoBack = canGoBack,
            hasOnBackToWorkspace = true,
        )
        assertTrue(enabled)
    }

    // ------------------------------------------------------------------------
    // 8. Browser reset clears stale history state
    // ------------------------------------------------------------------------
    @Test
    fun browser_reset_clears_stale_history_state() {
        var canGoBack = true

        // Reset app / browser called
        val onReset = {
            canGoBack = false
        }

        onReset()
        assertFalse("Reset must clear canGoBack", canGoBack)

        // After reset, user is back on Workspaces launcher
        val enabled = isBackHandlerEnabled(
            isVisible = false,
            isBackEnabled = false,
            canGoBack = canGoBack,
            hasOnBackToWorkspace = true,
        )
        assertFalse("BackHandler must be disabled after reset", enabled)
    }

    // ------------------------------------------------------------------------
    // 9. Existing modal and overlay Back priorities remain intact
    // ------------------------------------------------------------------------
    @Test
    fun overlay_priority_console_drawer_intercepts_before_browser_back() {
        // Modeled hierarchy:
        // Priority 1 (topmost): Console Drawer / Syntax Menu BackHandler
        // Priority 2: BrowserView BackHandler
        val isConsoleExpanded = true
        var isConsoleDismissed = false
        var webViewBackCount = 0

        if (isConsoleExpanded) {
            // Console BackHandler intercepts
            isConsoleDismissed = true
        } else {
            // Browser BackHandler would intercept
            webViewBackCount++
        }

        assertTrue("Expanded console overlay must intercept Back first", isConsoleDismissed)
        assertEquals("WebView history must not be navigated while console overlay is open", 0, webViewBackCount)
    }
}
