package com.coppermux.cutools.core.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for [ConsoleSeverityFilter] and [ConsoleEntryFilter]:
 * severity filtering, search matching, search/filter composition, and
 * preservation of the underlying entry collection.
 */
class ConsoleFilterTest {

    private fun entry(
        id: Long = 1,
        kind: ConsoleEntryKind = ConsoleEntryKind.CONSOLE_MESSAGE,
        severity: ConsoleSeverity = ConsoleSeverity.LOG,
        text: String? = null,
        source: String? = null,
        helperLabel: String? = null,
        evaluation: com.coppermux.cutools.core.runtime.EvaluationOutcome? = null,
    ): ConsoleEntry = ConsoleEntry(
        id = id,
        kind = kind,
        severity = severity,
        type = ConsoleMessageType.LOG,
        text = text,
        source = source,
        helperLabel = helperLabel,
        evaluation = evaluation,
        sessionId = "session-1",
    )

    private fun runtimeException(id: Long = 1, description: String? = null): ConsoleEntry = ConsoleEntry(
        id = id,
        kind = ConsoleEntryKind.RUNTIME_EXCEPTION,
        severity = ConsoleSeverity.ERROR,
        type = ConsoleMessageType.ERROR,
        text = "Uncaught",
        exceptionDescription = description,
        sessionId = "session-1",
    )

    // ============ severity filter model ============

    @Test
    fun severityFilter_defaultIsAll() {
        val f = ConsoleSeverityFilter.all()
        assertTrue(f.isAll)
        assertFalse(f.isActive)
    }

    @Test
    fun severityFilter_withSeverityToggles() {
        val f = ConsoleSeverityFilter().withSeverity(ConsoleSeverity.ERROR, true)
        assertEquals(setOf(ConsoleSeverity.ERROR), f.severities)
        assertTrue(f.isActive)
        val back = f.withSeverity(ConsoleSeverity.ERROR, false)
        assertTrue(back.severities.isEmpty())
    }

    @Test
    fun severityFilter_withRuntimeExceptionsToggles() {
        val f = ConsoleSeverityFilter().withRuntimeExceptions(false)
        assertFalse(f.includeRuntimeExceptions)
        assertTrue(f.isActive)
    }

    @Test
    fun severityFilter_clearRestoresAll() {
        val f = ConsoleSeverityFilter(
            severities = setOf(ConsoleSeverity.ERROR),
            includeRuntimeExceptions = false,
        ).clear()
        assertTrue(f.isAll)
    }

    // ============ severity pass ============

    @Test
    fun severityPasses_allFilter_passesEverything() {
        val filter = ConsoleSeverityFilter()
        assertTrue(ConsoleEntryFilter.severityPasses(entry(severity = ConsoleSeverity.LOG), filter))
        assertTrue(ConsoleEntryFilter.severityPasses(entry(severity = ConsoleSeverity.INFO), filter))
        assertTrue(ConsoleEntryFilter.severityPasses(entry(severity = ConsoleSeverity.DEBUG), filter))
        assertTrue(ConsoleEntryFilter.severityPasses(entry(severity = ConsoleSeverity.WARNING), filter))
        assertTrue(ConsoleEntryFilter.severityPasses(entry(severity = ConsoleSeverity.ERROR), filter))
        assertTrue(ConsoleEntryFilter.severityPasses(runtimeException(), filter))
    }

    @Test
    fun severityPasses_subset_onlyMatchingSeverityPasses() {
        val filter = ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.ERROR))
        assertFalse(ConsoleEntryFilter.severityPasses(entry(severity = ConsoleSeverity.LOG), filter))
        assertTrue(ConsoleEntryFilter.severityPasses(entry(severity = ConsoleSeverity.ERROR), filter))
        // Runtime exceptions carry ERROR severity and pass the ERROR filter.
        assertTrue(ConsoleEntryFilter.severityPasses(runtimeException(), filter))
    }

    @Test
    fun severityPasses_runtimeExceptionsHiddenIndependently() {
        val filter = ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.ERROR), includeRuntimeExceptions = false)
        assertTrue(ConsoleEntryFilter.severityPasses(entry(severity = ConsoleSeverity.ERROR), filter))
        assertFalse("runtime exceptions must be excluded independently of severity", ConsoleEntryFilter.severityPasses(runtimeException(), filter))
    }

    @Test
    fun severityPasses_usesStructuredSeverity_notDisplayText() {
        // An ERROR-severity entry whose text is generic must still be classified
        // by its structured severity.
        val filter = ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.ERROR))
        val err = entry(severity = ConsoleSeverity.ERROR, text = "something failed")
        assertTrue(ConsoleEntryFilter.severityPasses(err, filter))
        assertFalse(ConsoleEntryFilter.severityPasses(err.copy(severity = ConsoleSeverity.INFO), filter))
    }

    // ============ search pass ============

    @Test
    fun searchPasses_emptyQuery_matchesAll() {
        assertTrue(ConsoleEntryFilter.searchPasses(entry(text = "hello"), ""))
        assertTrue(ConsoleEntryFilter.searchPasses(entry(text = "hello"), "   "))
    }

    @Test
    fun searchPasses_matchesMessageText() {
        assertTrue(ConsoleEntryFilter.searchPasses(entry(text = "Failed to load resource"), "load"))
        assertFalse(ConsoleEntryFilter.searchPasses(entry(text = "Failed to load resource"), "render"))
    }

    @Test
    fun searchPasses_caseInsensitive() {
        assertTrue(ConsoleEntryFilter.searchPasses(entry(text = "Failed to load"), "FAILED"))
    }

    @Test
    fun searchPasses_matchesSourceLocationAndHelper() {
        val e = entry(text = "x", source = "network", helperLabel = "querySelector")
        assertTrue(ConsoleEntryFilter.searchPasses(e, "network"))
        assertTrue(ConsoleEntryFilter.searchPasses(e, "querySelector"))
    }

    @Test
    fun searchPasses_matchesEvaluationExpression() {
        val outcome = com.coppermux.cutools.core.runtime.EvaluationOutcome(
            id = 1,
            request = com.coppermux.cutools.core.runtime.EvaluationRequest("1 + 1"),
            result = com.coppermux.cutools.core.runtime.EvaluationResultKind.Success(
                com.coppermux.cutools.core.runtime.EvaluationRemoteObject(type = "number", value = 2),
            ),
            sessionId = "s",
        )
        val e = entry(kind = ConsoleEntryKind.EVALUATION_RESULT, evaluation = outcome)
        assertTrue(ConsoleEntryFilter.searchPasses(e, "1 + 1"))
    }

    @Test
    fun searchableText_containsMeaningfulContent() {
        val message = entry(id = 1, kind = ConsoleEntryKind.CONSOLE_MESSAGE, text = "boom", source = "javascript", helperLabel = "querySelector")
        val eval = entry(
            id = 2,
            kind = ConsoleEntryKind.EVALUATION_RESULT,
            evaluation = com.coppermux.cutools.core.runtime.EvaluationOutcome(
                id = 1,
                request = com.coppermux.cutools.core.runtime.EvaluationRequest("document.querySelector(\"#x\")"),
                result = com.coppermux.cutools.core.runtime.EvaluationResultKind.Success(
                    com.coppermux.cutools.core.runtime.EvaluationRemoteObject(type = "object", objectId = "o-1"),
                ),
                sessionId = "s",
            ),
        )
        val messageText = ConsoleEntryFilter.searchableText(message)
        assertTrue(messageText.contains("boom"))
        assertTrue(messageText.contains("querySelector"))
        assertTrue(messageText.contains("javascript"))

        val evalText = ConsoleEntryFilter.searchableText(eval)
        assertTrue(evalText.contains("document.querySelector"))
    }

    // ============ composition ============

    @Test
    fun matches_requiresSearchAndSeverity() {
        val filter = ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.ERROR))
        val err = entry(severity = ConsoleSeverity.ERROR, text = "boom here")
        val log = entry(severity = ConsoleSeverity.LOG, text = "boom here")
        assertTrue(ConsoleEntryFilter.matches(err, "boom", filter))
        assertFalse("wrong severity must fail", ConsoleEntryFilter.matches(log, "boom", filter))
        assertFalse("non-matching search must fail", ConsoleEntryFilter.matches(err, "nope", filter))
    }

    @Test
    fun apply_returnsIntersectionPreservingOrder() {
        val entries = listOf(
            entry(id = 1, severity = ConsoleSeverity.LOG, text = "hello world"),
            entry(id = 2, severity = ConsoleSeverity.ERROR, text = "boom world"),
            entry(id = 3, severity = ConsoleSeverity.INFO, text = "hello you"),
            entry(id = 4, severity = ConsoleSeverity.ERROR, text = "hello world"),
        )
        val filter = ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.ERROR))
        val visible = ConsoleEntryFilter.apply(entries, "world", filter)
        assertEquals(listOf(2L, 4L), visible.map { it.id })
        assertEquals("underlying collection must not be mutated", 4, entries.size)
    }

    @Test
    fun apply_searchOnly_andFilterOnly() {
        val entries = listOf(
            entry(id = 1, severity = ConsoleSeverity.LOG, text = "alpha"),
            entry(id = 2, severity = ConsoleSeverity.ERROR, text = "beta"),
            entry(id = 3, severity = ConsoleSeverity.ERROR, text = "alpha"),
        )
        assertEquals(listOf(1L, 3L), ConsoleEntryFilter.apply(entries, "alpha", ConsoleSeverityFilter()).map { it.id })
        assertEquals(listOf(2L, 3L), ConsoleEntryFilter.apply(entries, "", ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.ERROR))).map { it.id })
    }

    @Test
    fun apply_emptyEntries_isEmpty() {
        assertTrue(ConsoleEntryFilter.apply(emptyList(), "x", ConsoleSeverityFilter()).isEmpty())
    }

    @Test
    fun searchableText_doesNotCrashOnRuntimeExceptionEntry() {
        val e = runtimeException(1, "Error: nope\n    at f (a.js:1:2)")
        val text = ConsoleEntryFilter.searchableText(e)
        assertTrue(text.contains("Error: nope"))
        assertNotNull(text)
    }
}
