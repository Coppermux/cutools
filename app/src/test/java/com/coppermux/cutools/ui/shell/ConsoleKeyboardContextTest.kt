package com.coppermux.cutools.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests validating context-aware software keyboard (IME) positioning
 * and focus decoupling between the Browser URL bar and the JavaScript Console pill.
 */
class ConsoleKeyboardContextTest {

    @Test
    fun shouldConsoleRespondToIme_whenUrlBarFocused_alwaysReturnsFalse() {
        // When URL bar is focused, the console pill MUST NOT move above the keyboard,
        // even if console flags were somehow still set.
        assertFalse(
            "Console must not respond to IME when URL bar is focused and console is idle",
            shouldConsoleRespondToIme(
                isUrlBarFocused = true,
                isConsoleFocused = false,
                isExpanded = false,
                isSyntaxMenuVisible = false,
            ),
        )
        assertFalse(
            "Console must not respond to IME when URL bar is focused even if isConsoleFocused is true",
            shouldConsoleRespondToIme(
                isUrlBarFocused = true,
                isConsoleFocused = true,
                isExpanded = false,
                isSyntaxMenuVisible = false,
            ),
        )
        assertFalse(
            "Console must not respond to IME when URL bar is focused even if isExpanded is true",
            shouldConsoleRespondToIme(
                isUrlBarFocused = true,
                isConsoleFocused = false,
                isExpanded = true,
                isSyntaxMenuVisible = false,
            ),
        )
        assertFalse(
            "Console must not respond to IME when URL bar is focused even if syntax menu was visible",
            shouldConsoleRespondToIme(
                isUrlBarFocused = true,
                isConsoleFocused = false,
                isExpanded = false,
                isSyntaxMenuVisible = true,
            ),
        )
    }

    @Test
    fun shouldConsoleRespondToIme_whenConsoleFocusedAndUrlBarNotFocused_returnsTrue() {
        // When Console is focused and URL bar is not focused, the pill must respond to IME
        // and elevate above the keyboard.
        assertTrue(
            "Console must respond to IME when console is focused",
            shouldConsoleRespondToIme(
                isUrlBarFocused = false,
                isConsoleFocused = true,
                isExpanded = false,
                isSyntaxMenuVisible = false,
            ),
        )
    }

    @Test
    fun shouldConsoleRespondToIme_whenConsoleExpandedAndUrlBarNotFocused_returnsTrue() {
        // When Console history overlay is expanded and URL bar is not focused,
        // it must respond to IME so history and pill sit above the keyboard.
        assertTrue(
            "Console must respond to IME when console history is expanded",
            shouldConsoleRespondToIme(
                isUrlBarFocused = false,
                isConsoleFocused = false,
                isExpanded = true,
                isSyntaxMenuVisible = false,
            ),
        )
    }

    @Test
    fun shouldConsoleRespondToIme_whenSyntaxMenuVisibleAndUrlBarNotFocused_returnsTrue() {
        // When syntax action menu is visible, it must respond to IME to stay above the keyboard.
        assertTrue(
            "Console must respond to IME when syntax menu is active",
            shouldConsoleRespondToIme(
                isUrlBarFocused = false,
                isConsoleFocused = false,
                isExpanded = false,
                isSyntaxMenuVisible = true,
            ),
        )
    }

    @Test
    fun shouldConsoleRespondToIme_whenBothIdle_returnsFalse() {
        // When neither input is active, console pill rests at normal screen bottom
        assertFalse(
            "Console must not respond to IME when completely idle",
            shouldConsoleRespondToIme(
                isUrlBarFocused = false,
                isConsoleFocused = false,
                isExpanded = false,
                isSyntaxMenuVisible = false,
            ),
        )
    }

    @Test
    fun focusTransition_fromConsoleToUrlBar_switchesImeResponse() {
        // Step 1: User types in console
        var urlFocused = false
        var consoleFocused = true
        assertTrue(
            "Console responds to IME while console has focus",
            shouldConsoleRespondToIme(urlFocused, consoleFocused),
        )

        // Step 2: User taps URL bar -> URL bar gains focus, console loses focus
        urlFocused = true
        consoleFocused = false
        assertFalse(
            "Console stops responding to IME when URL bar gains focus",
            shouldConsoleRespondToIme(urlFocused, consoleFocused),
        )

        // Step 3: User taps Console pill again -> Console gains focus, URL bar loses focus
        urlFocused = false
        consoleFocused = true
        assertTrue(
            "Console resumes responding to IME when Console regains focus",
            shouldConsoleRespondToIme(urlFocused, consoleFocused),
        )
    }

    @Test
    fun consoleSubmission_preservesImeResponse_andRetainsFocusAboveKeyboard() {
        // Step 1: User is focused and typing a command in console
        val urlFocused = false
        var consoleFocused = true
        var expanded = false
        var syntaxMenuVisible = false

        assertTrue(
            "Console responds to IME before submission while user types",
            shouldConsoleRespondToIme(
                isUrlBarFocused = urlFocused,
                isConsoleFocused = consoleFocused,
                isExpanded = expanded,
                isSyntaxMenuVisible = syntaxMenuVisible,
            ),
        )

        // Step 2: User taps execute arrow -> command executes, console expands, input retains focus
        expanded = true
        consoleFocused = true
        syntaxMenuVisible = false

        assertTrue(
            "Console must continue responding to IME immediately after submission",
            shouldConsoleRespondToIme(
                isUrlBarFocused = urlFocused,
                isConsoleFocused = consoleFocused,
                isExpanded = expanded,
                isSyntaxMenuVisible = syntaxMenuVisible,
            ),
        )

        // Step 3: User enters and executes consecutive commands without dismissing keyboard
        assertTrue(
            "Console retains IME response for consecutive command entries",
            shouldConsoleRespondToIme(
                isUrlBarFocused = urlFocused,
                isConsoleFocused = consoleFocused,
                isExpanded = expanded,
                isSyntaxMenuVisible = syntaxMenuVisible,
            ),
        )

        // Step 4: User explicitly collapses console via back button, scrim, or collapse button
        expanded = false
        consoleFocused = false

        assertFalse(
            "Console stops responding to IME when explicitly collapsed and focus is cleared",
            shouldConsoleRespondToIme(
                isUrlBarFocused = urlFocused,
                isConsoleFocused = consoleFocused,
                isExpanded = expanded,
                isSyntaxMenuVisible = syntaxMenuVisible,
            ),
        )
    }

    @Test
    fun consoleSubmission_retainedFocusPreventsImeDismissal_evenIfExpandedStateToggles() {
        // With retained focus (isConsoleFocused = true), the console pill always responds to IME
        // even if expanded panel was collapsed, unlike the legacy behavior which cleared focus.
        val urlFocused = false
        val consoleFocused = true

        assertTrue(
            "Retained console focus ensures IME response whether expanded or not",
            shouldConsoleRespondToIme(
                isUrlBarFocused = urlFocused,
                isConsoleFocused = consoleFocused,
                isExpanded = false,
            ),
        )
        assertTrue(
            "Retained console focus ensures IME response when expanded",
            shouldConsoleRespondToIme(
                isUrlBarFocused = urlFocused,
                isConsoleFocused = consoleFocused,
                isExpanded = true,
            ),
        )
    }
}
