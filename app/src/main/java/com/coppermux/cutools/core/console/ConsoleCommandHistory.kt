package com.coppermux.cutools.core.console

/**
 * Deterministic, UI-independent command history for JavaScript expressions
 * entered through the CuTools Console.
 *
 * - [entries] holds executed expressions, oldest first.
 * - [position] is the index of the history entry currently shown in the input
 *   during navigation; `-1` means the input is live (not navigating history).
 * - [draft] preserves the live input captured when the user entered history
 *   navigation, restored when navigating forward past the newest entry.
 *
 * History is session state (never persisted) and is owned by the Console tool.
 * Navigating history only changes the input; it never executes an expression.
 *
 * Duplicate handling: a consecutive repeat of the most recent entry is not
 * recorded again (matching common console behavior); a non-consecutive repeat
 * is recorded normally.
 */
data class ConsoleCommandHistory(
    val entries: List<String> = emptyList(),
    val position: Int = -1,
    val draft: String? = null,
) {
    /** Whether the input is live (not positioned on a history entry). */
    val isLive: Boolean get() = position == -1

    /** Whether navigation can move to an older entry. */
    fun canGoBack(): Boolean = entries.isNotEmpty() && position != 0

    /** Whether navigation can move to a newer entry (or back to the live input). */
    fun canGoForward(): Boolean = position != -1

    /**
     * Records an executed expression, resetting navigation to the live input.
     * Consecutive duplicates of the most recent entry are not recorded.
     */
    fun record(expression: String): ConsoleCommandHistory {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return copy(position = -1, draft = null)
        return copy(
            entries = if (entries.lastOrNull() == trimmed) entries else entries + trimmed,
            position = -1,
            draft = null,
        )
    }

    /**
     * Moves backward through history. Captures the current live input as the
     * [draft] when entering navigation. A no-op at the oldest entry.
     */
    fun back(currentInput: String): ConsoleCommandHistory {
        if (entries.isEmpty()) return this
        return when {
            position == -1 -> copy(position = entries.lastIndex, draft = currentInput)
            position > 0 -> copy(position = position - 1)
            else -> this
        }
    }

    /**
     * Moves forward through history (toward the live input). A no-op while at
     * the live input. Past the newest entry, navigation ends and the [draft]
     * is restored.
     */
    fun forward(currentInput: String): ConsoleCommandHistory {
        if (entries.isEmpty()) return this
        return when {
            position == -1 -> this
            position < entries.lastIndex -> copy(position = position + 1)
            else -> copy(position = -1)
        }
    }

    /** The input the field should show for the current position. */
    fun displayInput(): String = when {
        position in entries.indices -> entries[position]
        else -> draft ?: ""
    }

    companion object {
        fun empty(): ConsoleCommandHistory = ConsoleCommandHistory()
    }
}
