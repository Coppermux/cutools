package com.coppermux.cutools.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM unit tests for [BrowserNavigationState], ensuring duplicate loadUrl
 * invocations during recomposition and redundant about:blank navigations are prevented.
 */
class BrowserNavigationStateTest {

    private lateinit var navState: BrowserNavigationState

    @Before
    fun setUp() {
        navState = BrowserNavigationState()
    }

    @Test
    fun initialNavigation_returnsTrueAndUpdatesLastRequestedUrl() {
        val shouldLoad = navState.shouldLoad("https://www.example.com", currentWebViewUrl = null)

        assertTrue("Initial valid URL must trigger loadUrl", shouldLoad)
        assertEquals("https://www.example.com", navState.lastRequestedUrl)
    }

    @Test
    fun recompositionWithSameUrl_returnsFalse() {
        navState.shouldLoad("https://www.example.com", currentWebViewUrl = null)

        val shouldLoadAgain = navState.shouldLoad("https://www.example.com", currentWebViewUrl = "https://www.example.com")

        assertFalse("Recomposition with unchanged URL must NOT trigger duplicate loadUrl", shouldLoadAgain)
        assertEquals("https://www.example.com", navState.lastRequestedUrl)
    }

    @Test
    fun subsequentNavigationToDifferentUrl_returnsTrue() {
        navState.shouldLoad("https://www.cnn.com", currentWebViewUrl = null)

        val shouldLoadGumtree = navState.shouldLoad("https://www.gumtree.co.za", currentWebViewUrl = "https://www.cnn.com")

        assertTrue("Navigation to a different URL must trigger loadUrl", shouldLoadGumtree)
        assertEquals("https://www.gumtree.co.za", navState.lastRequestedUrl)
    }

    @Test
    fun blankTargetUrl_whenWebViewHasActiveContent_navigatesToAboutBlank() {
        navState.shouldLoad("https://www.cnn.com", currentWebViewUrl = null)

        // Reset App or return to workspace passes blank URL while WebView was on CNN
        val shouldLoadBlank = navState.shouldLoad("", currentWebViewUrl = "https://www.cnn.com")

        assertTrue("Blank URL when page has content must load about:blank to clear viewport", shouldLoadBlank)
        assertEquals("about:blank", navState.lastRequestedUrl)
    }

    @Test
    fun repeatedBlankTargetUrl_doesNotTriggerLoop() {
        navState.shouldLoad("https://www.cnn.com", currentWebViewUrl = null)
        navState.shouldLoad("", currentWebViewUrl = "https://www.cnn.com")

        // Recomposition while already on about:blank
        val shouldLoadBlankAgain = navState.shouldLoad("", currentWebViewUrl = "about:blank")

        assertFalse("Repeated blank URL must NOT re-trigger about:blank load", shouldLoadBlankAgain)
    }

    @Test
    fun blankTargetUrl_whenWebViewNeverLoadedContent_doesNotTriggerLoad() {
        val shouldLoadBlank = navState.shouldLoad("", currentWebViewUrl = null)

        assertFalse("Blank URL on fresh unloaded WebView must NOT trigger loadUrl", shouldLoadBlank)
    }

    @Test
    fun resetWhileWebViewAlreadyAtAboutBlank_allowsSubsequentNavigationToSameUrl() {
        // Initial navigation to example.com
        assertTrue(navState.shouldLoad("https://www.example.com", currentWebViewUrl = null, navigationId = 1L))
        assertEquals("https://www.example.com", navState.lastRequestedUrl)

        // Reset occurs when WebView is already at about:blank
        val shouldLoadBlank = navState.shouldLoad("", currentWebViewUrl = "about:blank", navigationId = 2L)
        assertFalse("Reset while already on about:blank must not trigger redundant loadUrl", shouldLoadBlank)
        assertEquals("about:blank", navState.lastRequestedUrl)

        // Navigating back to the same URL must not be suppressed
        val shouldLoadSame = navState.shouldLoad("https://www.example.com", currentWebViewUrl = "about:blank", navigationId = 3L)
        assertTrue("Navigating to same URL after reset must trigger loadUrl", shouldLoadSame)
        assertEquals("https://www.example.com", navState.lastRequestedUrl)
    }

    @Test
    fun retrySameUrlAfterFailedOrInterruptedNavigation_triggersLoad() {
        // Initial navigation attempt
        assertTrue(navState.shouldLoad("https://www.example.com", currentWebViewUrl = null, navigationId = 1L))

        // Navigation fails or is interrupted: user explicitly retries the same URL (new navigationId)
        val shouldLoadRetry = navState.shouldLoad("https://www.example.com", currentWebViewUrl = null, navigationId = 2L)
        assertTrue("Explicit retry with updated navigationId must trigger loadUrl even for same URL", shouldLoadRetry)

        // Explicit invalidation also allows retrying even with same navigationId
        navState.invalidate()
        val shouldLoadAfterInvalidate = navState.shouldLoad("https://www.example.com", currentWebViewUrl = null)
        assertTrue("Navigation after explicit invalidate must trigger loadUrl", shouldLoadAfterInvalidate)
    }

    @Test
    fun recompositionWithHealthyNavigation_doesNotTriggerDuplicateLoad() {
        // Navigation initiated with navigationId = 1L
        assertTrue(navState.shouldLoad("https://www.example.com", currentWebViewUrl = null, navigationId = 1L))

        // Normal Compose recomposition with unchanged URL and navigationId
        val shouldLoadRecomposition = navState.shouldLoad(
            "https://www.example.com",
            currentWebViewUrl = "https://www.example.com",
            navigationId = 1L,
        )
        assertFalse("Recomposition with unchanged navigationId must NOT trigger loadUrl", shouldLoadRecomposition)
    }

    @Test
    fun resetMethod_synchronizesStateToAboutBlank() {
        navState.shouldLoad("https://www.example.com", currentWebViewUrl = null, navigationId = 1L)
        navState.reset()
        assertEquals("about:blank", navState.lastRequestedUrl)
        org.junit.Assert.assertNull(navState.lastNavigationId)

        // Navigating to the previous URL must succeed
        assertTrue(navState.shouldLoad("https://www.example.com", currentWebViewUrl = "about:blank", navigationId = 2L))
    }
}
