package com.coppermux.cutools.core.dom

/**
 * The lifecycle phase of a DOM mutation operation.
 *
 * The UI must distinguish read-only inspection, active editing, a pending
 * (submitted) mutation, a successful mutation, and a failed mutation so it
 * never claims success when Chrome rejected the operation.
 */
enum class DomMutationPhase {
    /** No mutation is in progress. */
    IDLE,

    /** A mutation command has been submitted and is awaiting a Chrome response. */
    SUBMITTING,

    /** The last mutation completed successfully and local state was reconciled. */
    SUCCESS,

    /** The last mutation failed; local state was NOT modified. */
    FAILURE,
}

/**
 * Read-only state of the most recent DOM mutation.
 *
 * Owned by the [DomInspector]. Tracks the current phase, the operation kind,
 * and (on failure) the error. Cleared/invalidated on target change and
 * connection loss so no stale mutation result leaks across targets.
 */
data class DomMutationState(
    val phase: DomMutationPhase = DomMutationPhase.IDLE,
    val operation: DomMutationOperation? = null,
    val error: Throwable? = null,
) {
    val isSubmitting: Boolean get() = phase == DomMutationPhase.SUBMITTING
    val hasError: Boolean get() = error != null

    /** A copy in the given [DomMutationPhase] for the given operation (no error). */
    fun forPhase(newPhase: DomMutationPhase, op: DomMutationOperation? = operation): DomMutationState =
        DomMutationState(newPhase, op)

    companion object {
        fun empty(): DomMutationState = DomMutationState()
    }
}

/**
 * The kind of DOM mutation being performed. Tied to a target/session, node,
 * and operation so stale asynchronous results are never applied elsewhere.
 */
enum class DomMutationOperation {
    SET_ATTRIBUTE,
    ADD_ATTRIBUTE,
    REMOVE_ATTRIBUTE,
    SET_TEXT,
    SET_OUTER_HTML,
    REMOVE_NODE,
}
