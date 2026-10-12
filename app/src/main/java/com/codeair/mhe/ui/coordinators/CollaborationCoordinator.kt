package com.codeair.mhe.ui.coordinators

import com.codeair.mhe.collab.WorkspaceCollaborationManager
import com.codeair.mhe.collab.model.SharedProjectSession

/**
 * Collaboration domain coordinator (Milestone 1).
 *
 * Explicit owner of the collaboration session state machine, delegating to
 * the real [WorkspaceCollaborationManager]. Firebase-backed identity, invites
 * and cross-device sync are Milestone 12 work.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class CollaborationCoordinator(
    private val manager: WorkspaceCollaborationManager,
) {
    fun createCollaborationSession(projectId: String, projectName: String): SharedProjectSession {
        return manager.createShareSession(projectId, projectName)
    }

    fun joinCollaborationSession(shareToken: String, name: String, email: String): SharedProjectSession? {
        return manager.joinSession(shareToken, name, email)
    }
}
