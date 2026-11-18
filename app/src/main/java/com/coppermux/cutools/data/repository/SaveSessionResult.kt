package com.coppermux.cutools.data.repository

import com.coppermux.cutools.data.model.WorkspaceSession

/**
 * Result of saving a workspace session in the repository.
 *
 * Distinguishes creation of a new session, update of an identified existing session,
 * and rejection when maximum workspace capacity is reached.
 */
sealed interface SaveSessionResult {
    data class Created(val session: WorkspaceSession) : SaveSessionResult
    data class Updated(val session: WorkspaceSession) : SaveSessionResult
    data object CapacityReached : SaveSessionResult
    data class Failure(val cause: Throwable) : SaveSessionResult
}
