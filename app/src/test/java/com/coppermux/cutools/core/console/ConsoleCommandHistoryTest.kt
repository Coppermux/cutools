package com.coppermux.cutools.core.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for [ConsoleCommandHistory] — recording, duplicate
 * handling, backward/forward navigation, boundary behavior, and input display.
 */
class ConsoleCommandHistoryTest {

    @Test
    fun emptyHistory_noNavigationPossible() {
        val h = ConsoleCommandHistory.empty()
        assertTrue(h.isLive)
        assertFalse(h.canGoBack())
        assertFalse(h.canGoForward())
        assertEquals("", h.displayInput())
    }

    @Test
    fun record_appendsChronologically() {
        val h = ConsoleCommandHistory.empty()
            .record("a")
            .record("b")
            .record("c")
        assertEquals(listOf("a", "b", "c"), h.entries)
        assertTrue(h.isLive)
    }

    @Test
    fun record_trimsExpressions() {
        val h = ConsoleCommandHistory.empty().record("  a  ")
        assertEquals(listOf("a"), h.entries)
    }

    @Test
    fun record_emptyExpression_isIgnored() {
        val h = ConsoleCommandHistory.empty().record("   ")
        assertTrue(h.entries.isEmpty())
    }

    @Test
    fun record_dedupesConsecutiveDuplicates() {
        val h = ConsoleCommandHistory.empty()
            .record("a")
            .record("a")
        assertEquals(listOf("a"), h.entries)
    }

    @Test
    fun record_nonConsecutiveDuplicate_isKept() {
        val h = ConsoleCommandHistory.empty()
            .record("a")
            .record("b")
            .record("a")
        assertEquals(listOf("a", "b", "a"), h.entries)
    }

    @Test
    fun record_resetsNavigationToLive() {
        val h = ConsoleCommandHistory.empty()
            .record("a")
            .record("b")
            .back("draft")
        assertTrue(h.position != -1)
        val reset = h.record("c")
        assertTrue(reset.isLive)
        assertEquals(listOf("a", "b", "c"), reset.entries)
    }

    @Test
    fun back_fromLive_landsOnNewestAndSavesDraft() {
        val h = ConsoleCommandHistory.empty()
            .record("a")
            .record("b")
            .back("draft-input")
        assertEquals(1, h.position)
        assertEquals("b", h.displayInput())
        assertEquals("draft-input", h.draft)
    }

    @Test
    fun back_boundary_atOldest_isNoOp() {
        val h = ConsoleCommandHistory.empty().record("a")
        val back1 = h.back("")
        assertEquals(0, back1.position)
        val back2 = back1.back("")
        assertEquals(back1, back2)
    }

    @Test
    fun back_walksBackwardsThroughHistory() {
        val h = ConsoleCommandHistory.empty()
            .record("a")
            .record("b")
            .record("c")
            .back("")
        assertEquals("c", h.displayInput())
        val b = h.back("")
        assertEquals("b", b.displayInput())
        val a = b.back("")
        assertEquals("a", a.displayInput())
    }

    @Test
    fun forward_fromNavigation_movesTowardLive() {
        val h = ConsoleCommandHistory.empty()
            .record("a")
            .record("b")
            .back("draft")
        assertEquals("b", h.displayInput())
        val live = h.forward("")
        assertTrue(live.isLive)
        assertEquals("draft", live.displayInput())
    }

    @Test
    fun forward_pastNewest_returnsToLiveRestoringDraft() {
        val h = ConsoleCommandHistory.empty()
            .record("a")
            .record("b")
            .back("draft")
        val live = h.forward("")
        assertTrue(live.isLive)
        assertEquals("draft", live.displayInput())
        // forward at live is a no-op
        assertEquals(live, live.forward(""))
    }

    @Test
    fun forward_boundary_atLive_isNoOp() {
        val h = ConsoleCommandHistory.empty().record("a")
        assertEquals(h, h.forward(""))
    }

    @Test
    fun canGoBackAndForward_reflectPosition() {
        val h = ConsoleCommandHistory.empty().record("a").record("b")
        assertTrue(h.canGoBack())      // live, entries exist
        assertFalse(h.canGoForward())  // live
        val atNewest = h.back("")
        assertTrue(atNewest.canGoBack())
        assertTrue(atNewest.canGoForward())
        val atOldest = atNewest.back("")
        assertFalse(atOldest.canGoBack())
        assertTrue(atOldest.canGoForward())
    }

    @Test
    fun displayInput_returnsHistoryEntryAtPosition() {
        val h = ConsoleCommandHistory.empty().record("x").record("y").back("")
        assertEquals("y", h.displayInput())
        val older = h.back("")
        assertEquals("x", older.displayInput())
    }
}
