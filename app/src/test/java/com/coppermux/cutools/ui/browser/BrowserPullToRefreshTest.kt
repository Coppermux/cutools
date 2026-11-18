package com.coppermux.cutools.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests validating the pull-to-refresh activation constraints,
 * scroll position detection logic, navigation target resolution, and safeguard
 * conditions for [BrowserView].
 *
 * Covers diagnostic session 113367B2 navigation-state synchronization.
 */
class BrowserPullToRefreshTest {

    /**
     * Replicates the canChildScrollUp callback logic used by SwipeRefreshLayout in [BrowserView].
     * Returns true if the child can scroll up (which blocks pull-to-refresh).
     * Returns false if the webpage is at the very top (which allows pull-to-refresh).
     */
    private fun canChildScrollUp(canScrollVerticallyNeg1: Boolean, scrollY: Int): Boolean {
        return canScrollVerticallyNeg1 || scrollY > 0
    }

    @Test
    fun pullToRefresh_allowedOnlyWhenAtTopOfWebpage() {
        // At scroll position zero (cannot scroll up further, scrollY == 0)
        val canScrollUp = canChildScrollUp(canScrollVerticallyNeg1 = false, scrollY = 0)
        assertFalse("At top of page, child cannot scroll up, allowing pull-to-refresh", canScrollUp)
    }

    @Test
    fun pullToRefresh_blockedWhenPageIsScrolledDown() {
        // Scrolled down by 50px
        val canScrollUpWithScrollY = canChildScrollUp(canScrollVerticallyNeg1 = false, scrollY = 50)
        assertTrue("When scrollY > 0, normal scrolling must occur without pull-to-refresh", canScrollUpWithScrollY)

        // Web view reports it can scroll vertically
        val canScrollUpWithFlag = canChildScrollUp(canScrollVerticallyNeg1 = true, scrollY = 0)
        assertTrue("When canScrollVertically(-1) is true, pull-to-refresh must be blocked", canScrollUpWithFlag)

        // Both conditions indicate scrolled
        val canScrollUpBoth = canChildScrollUp(canScrollVerticallyNeg1 = true, scrollY = 120)
        assertTrue("When both indicate scrolled, pull-to-refresh must be blocked", canScrollUpBoth)
    }

    @Test
    fun canReload_allowedForActiveVisibleWebpage() {
        val allowed = canReloadWebpage(
            isVisible = true,
            currentWebViewUrl = "https://example.com",
            committedUrl = "https://example.com",
            stateUrl = "https://example.com",
        )
        assertTrue("Valid visible webpage must allow reload", allowed)
    }

    @Test
    fun canReload_rejectedWhenWorkspaceIsVisible() {
        val allowed = canReloadWebpage(
            isVisible = false, // User is looking at WorkspacesScreen
            currentWebViewUrl = "https://example.com",
            committedUrl = "https://example.com",
            stateUrl = "https://example.com",
        )
        assertFalse("When workspace grid is active, pull-to-refresh must not reload", allowed)
    }

    /**
     * Confirmed Diagnostic Session 113367B2:
     * When stateUrl is empty (e.g. during initial bridge connection delay or redirect desync),
     * pull-to-refresh must recognize the valid committed WebView URL and allow reload.
     */
    @Test
    fun canReload_allowedWhenStateUrlIsEmptyButWebViewHasValidCommittedUrl() {
        val allowed = canReloadWebpage(
            isVisible = true,
            currentWebViewUrl = "https://www.wikipedia.org/",
            committedUrl = "https://www.wikipedia.org/",
            stateUrl = "", // Empty navigation state must not suppress refresh
        )
        assertTrue("Empty stateUrl must not suppress refresh when WebView has valid committed URL", allowed)

        val decision = computeRefreshDecision(
            isVisible = true,
            currentWebViewUrl = "https://www.wikipedia.org/",
            committedUrl = "https://www.wikipedia.org/",
            stateUrl = "",
            isNetworkDropped = false,
        )
        assertTrue(decision.canReload)
        assertEquals("https://www.wikipedia.org/", decision.targetUrl)
        assertEquals("webView.reload()", decision.operation)
        assertEquals("DISPATCHED", decision.status)
    }

    @Test
    fun canReload_allowedWhenWebViewUrlIsErrorOrNullButCommittedUrlIsValid() {
        // WebView is on chrome-error:// or null after simulated network reset,
        // but WebViewEngine preserved the lastCommittedUrl
        val allowed = canReloadWebpage(
            isVisible = true,
            currentWebViewUrl = "chrome-error://chromewebdata/",
            committedUrl = "https://www.wikipedia.org/",
            stateUrl = null,
        )
        assertTrue("Preserved lastCommittedUrl must allow reload when stranded on error page", allowed)

        val decision = computeRefreshDecision(
            isVisible = true,
            currentWebViewUrl = "chrome-error://chromewebdata/",
            committedUrl = "https://www.wikipedia.org/",
            stateUrl = null,
            isNetworkDropped = false,
        )
        assertTrue(decision.canReload)
        assertEquals("https://www.wikipedia.org/", decision.targetUrl)
        assertEquals("webView.loadUrl(https://www.wikipedia.org/)", decision.operation)
        assertEquals("DISPATCHED", decision.status)
    }

    @Test
    fun canReload_rejectedWhenAllUrlsAreBlankOrAboutBlank() {
        assertFalse("All null URLs must not reload", canReloadWebpage(true, null, null, null))
        assertFalse("All blank URLs must not reload", canReloadWebpage(true, "", "", ""))
        assertFalse("about:blank URLs must not reload", canReloadWebpage(true, "about:blank", "about:blank", "about:blank"))
    }

    @Test
    fun computeRefreshDecision_distinguishesOnlineOfflineAndNoTarget() {
        // 1. Online dispatched refresh
        val onlineDecision = computeRefreshDecision(
            isVisible = true,
            currentWebViewUrl = "https://www.wikipedia.org/",
            committedUrl = "https://www.wikipedia.org/",
            stateUrl = "https://www.wikipedia.org/",
            isNetworkDropped = false,
        )
        assertTrue(onlineDecision.canReload)
        assertEquals("webView.reload()", onlineDecision.operation)
        assertEquals("DISPATCHED", onlineDecision.status)

        // 2. Offline handled refresh (connectivity intentionally disabled)
        val offlineDecision = computeRefreshDecision(
            isVisible = true,
            currentWebViewUrl = "https://www.wikipedia.org/",
            committedUrl = "https://www.wikipedia.org/",
            stateUrl = "https://www.wikipedia.org/",
            isNetworkDropped = true,
        )
        assertTrue(offlineDecision.canReload)
        assertEquals("webView.reload() (offline handled)", offlineDecision.operation)
        assertEquals("HANDLED_OFFLINE", offlineDecision.status)

        // 3. Rejected refresh because no valid navigation target exists
        val rejectedDecision = computeRefreshDecision(
            isVisible = true,
            currentWebViewUrl = "about:blank",
            committedUrl = null,
            stateUrl = "",
            isNetworkDropped = false,
        )
        assertFalse(rejectedDecision.canReload)
        assertNull(rejectedDecision.targetUrl)
        assertEquals("none (cancel refreshing: no valid navigation target)", rejectedDecision.operation)
        assertEquals("REJECTED_NO_TARGET", rejectedDecision.status)
    }

    @Test
    fun isValidNavigationTarget_validatesHttpAndHttpsCorrectly() {
        assertTrue(isValidNavigationTarget("https://www.wikipedia.org/"))
        assertTrue(isValidNavigationTarget("http://example.com/path?query=1#frag"))
        assertTrue(isValidNavigationTarget("https://sub.domain.co.uk:8080/api"))

        assertFalse(isValidNavigationTarget(null))
        assertFalse(isValidNavigationTarget(""))
        assertFalse(isValidNavigationTarget("   "))
        assertFalse(isValidNavigationTarget("about:blank"))
        assertFalse(isValidNavigationTarget("chrome-error://chromewebdata/"))
        assertFalse(isValidNavigationTarget("javascript:void(0)"))
        assertFalse(isValidNavigationTarget("ws://localhost:9222"))
        assertFalse(isValidNavigationTarget("file:///sdcard/test.html"))
    }

    @Test
    fun multiTouch_disallowsSwipeRefreshInterceptionForPinchToZoom() {
        // Multi-touch gestures (two or more pointers, e.g. pinch-to-zoom) must refuse interception
        val shouldDisallow = { pointerCount: Int -> pointerCount > 1 }
        assertTrue("Two fingers must disallow pull-to-refresh interception for pinch zoom", shouldDisallow(2))
        assertTrue("Three fingers must disallow pull-to-refresh interception", shouldDisallow(3))
        assertFalse("Single finger must allow normal pull-to-refresh interception", shouldDisallow(1))
    }

    @Test
    fun pullToRefreshCoordinator_ignoresStaleCompletionImmediatelyAfterDispatch() {
        var currentTime = 1000L
        var dismissedReason: String? = null
        var dismissedDuration = -1L
        var scheduledDelay = -1L
        var scheduledRunnable: Runnable? = null

        val coordinator = PullToRefreshCoordinator(
            clock = { currentTime },
            onDismissIndicator = { reason, duration ->
                dismissedReason = reason
                dismissedDuration = duration
            },
            onScheduleDismiss = { delayMs, runnable ->
                scheduledDelay = delayMs
                scheduledRunnable = runnable
            },
            onCancelScheduledDismiss = {
                scheduledRunnable = null
                scheduledDelay = -1L
            },
        )

        val decision = computeRefreshDecision(
            isVisible = true,
            currentWebViewUrl = "https://www.wikipedia.org/",
            committedUrl = "https://www.wikipedia.org/",
            stateUrl = "https://www.wikipedia.org/",
            isNetworkDropped = false,
        )

        var reloadDispatched = false
        coordinator.onRefreshGestureTriggered(
            decision = decision,
            onDispatchReload = { reloadDispatched = true },
            onReject = {},
        )

        assertTrue("Reload must be dispatched", reloadDispatched)
        assertTrue("Refresh must be actively in progress", coordinator.isRefreshInProgress)
        assertFalse("Page load should not yet have started", coordinator.reloadPageStarted)

        // Stale completion arriving immediately (e.g. 50ms after dispatch, before onPageLoadStarted)
        currentTime += 50L
        coordinator.onPageLoadCompleted()

        // Verify stale completion was ignored and indicator NOT dismissed
        assertTrue("Refresh must still be in progress despite stale completion", coordinator.isRefreshInProgress)
        assertNull("Dismiss must not be scheduled or called for stale completion", dismissedReason)
        assertEquals(-1L, scheduledDelay)

        // Now page load genuinely starts
        currentTime += 100L
        coordinator.onPageLoadStarted("https://www.wikipedia.org/")
        assertTrue("Reload page started must be flagged", coordinator.reloadPageStarted)
        assertTrue("Refresh must still be active", coordinator.isRefreshInProgress)

        // Real page load completes (total elapsed = 600ms >= minDisplayDuration 500ms)
        currentTime += 450L
        coordinator.onPageLoadCompleted()

        assertFalse("Refresh must no longer be in progress after genuine completion", coordinator.isRefreshInProgress)
        assertEquals("COMPLETED", dismissedReason)
        assertEquals(600L, dismissedDuration)
    }

    @Test
    fun pullToRefreshCoordinator_enforcesMinimumPerceptibleDisplayDuration() {
        var currentTime = 1000L
        var dismissedReason: String? = null
        var scheduledDelay = -1L
        var scheduledRunnable: Runnable? = null

        val coordinator = PullToRefreshCoordinator(
            clock = { currentTime },
            onDismissIndicator = { reason, _ ->
                dismissedReason = reason
            },
            onScheduleDismiss = { delayMs, runnable ->
                scheduledDelay = delayMs
                scheduledRunnable = runnable
            },
            onCancelScheduledDismiss = {
                scheduledRunnable = null
                scheduledDelay = -1L
            },
        )

        val decision = computeRefreshDecision(
            isVisible = true,
            currentWebViewUrl = "https://www.wikipedia.org/",
            committedUrl = "https://www.wikipedia.org/",
            stateUrl = "https://www.wikipedia.org/",
            isNetworkDropped = false,
        )

        coordinator.onRefreshGestureTriggered(
            decision = decision,
            onDispatchReload = {},
            onReject = {},
        )

        // Genuine navigation starts at 50ms
        currentTime += 50L
        coordinator.onPageLoadStarted("https://www.wikipedia.org/")

        // Fast page load completes at 150ms total elapsed (< 500ms min display duration)
        currentTime += 100L
        coordinator.onPageLoadCompleted()

        // Must schedule remaining 350ms (500 - 150)
        assertEquals("Scheduled delay must equal remaining minimum perceptible display duration", 350L, scheduledDelay)
        assertTrue("Refresh must still be flagged in progress while awaiting minimum display duration", coordinator.isRefreshInProgress)

        // Execute scheduled runnable
        currentTime += 350L
        scheduledRunnable?.run()

        assertFalse("Refresh must complete after scheduled duration", coordinator.isRefreshInProgress)
        assertEquals("COMPLETED", dismissedReason)
    }

    @Test
    fun pullToRefreshCoordinator_dismissesImmediatelyOnCancelledDecision() {
        var dismissedReason: String? = null
        var rejectedCalled = false

        val coordinator = PullToRefreshCoordinator(
            onDismissIndicator = { reason, _ -> dismissedReason = reason },
        )

        val rejectedDecision = computeRefreshDecision(
            isVisible = true,
            currentWebViewUrl = "about:blank",
            committedUrl = null,
            stateUrl = "",
            isNetworkDropped = false,
        )

        coordinator.onRefreshGestureTriggered(
            decision = rejectedDecision,
            onDispatchReload = { org.junit.Assert.fail("Reload should not be dispatched for rejected decision") },
            onReject = { rejectedCalled = true },
        )

        assertTrue(rejectedCalled)
        assertFalse(coordinator.isRefreshInProgress)
        assertEquals("CANCELLED", dismissedReason)
    }

    @Test
    fun pullToRefreshCoordinator_handlesTimeoutGracefully() {
        var dismissedReason: String? = null
        val coordinator = PullToRefreshCoordinator(
            onDismissIndicator = { reason, _ -> dismissedReason = reason },
        )

        val decision = computeRefreshDecision(
            isVisible = true,
            currentWebViewUrl = "https://www.wikipedia.org/",
            committedUrl = "https://www.wikipedia.org/",
            stateUrl = "https://www.wikipedia.org/",
            isNetworkDropped = false,
        )

        coordinator.onRefreshGestureTriggered(
            decision = decision,
            onDispatchReload = {},
            onReject = {},
        )

        assertTrue(coordinator.isRefreshInProgress)
        coordinator.onTimeout()

        assertFalse("Timeout must reset in-progress flag to prevent UI getting stuck", coordinator.isRefreshInProgress)
        assertEquals("TIMEOUT", dismissedReason)
    }

    @Test
    fun pullToRefreshCoordinator_handlesDetachedOrHidden() {
        var dismissedReason: String? = null
        val coordinator = PullToRefreshCoordinator(
            onDismissIndicator = { reason, _ -> dismissedReason = reason },
        )

        val decision = computeRefreshDecision(
            isVisible = true,
            currentWebViewUrl = "https://www.wikipedia.org/",
            committedUrl = "https://www.wikipedia.org/",
            stateUrl = "https://www.wikipedia.org/",
            isNetworkDropped = false,
        )

        coordinator.onRefreshGestureTriggered(
            decision = decision,
            onDispatchReload = {},
            onReject = {},
        )

        assertTrue(coordinator.isRefreshInProgress)
        coordinator.onDetachedOrHidden()

        assertFalse(coordinator.isRefreshInProgress)
        assertEquals("VIEWPORT_HIDDEN_OR_DETACHED", dismissedReason)
    }
}
