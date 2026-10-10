package com.jarves.mh.collab

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TeamMemberRole {
    OWNER,
    MAINTAINER,
    CONTRIBUTOR,
    VIEWER
}

data class TeamMember(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val email: String,
    val role: TeamMemberRole = TeamMemberRole.CONTRIBUTOR,
    val avatarUrl: String? = null
)

data class CodeComment(
    val id: String = UUID.randomUUID().toString(),
    val author: String,
    val filePath: String,
    val lineNumber: Int,
    val commentText: String,
    val resolved: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class SharedProjectSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val projectId: String,
    val shareUrl: String,
    val isLiveSyncEnabled: Boolean = false,
    val members: List<TeamMember> = emptyList(),
    val comments: List<CodeComment> = emptyList()
)

/**
 * Workspace Collaboration & Team Sharing Manager (Phase 13).
 * Manages project invitations, shared checkpoints, review comments, and multi-user sync.
 */
class WorkspaceCollaborationManager {

    private val _activeSession = MutableStateFlow<SharedProjectSession?>(null)
    val activeSession: StateFlow<SharedProjectSession?> = _activeSession.asStateFlow()

    fun createShareSession(projectId: String, projectName: String): SharedProjectSession {
        val session = SharedProjectSession(
            projectId = projectId,
            shareUrl = "https://share.pocketdev.io/join/$projectId",
            isLiveSyncEnabled = true,
            members = listOf(
                TeamMember(
                    name = "Project Owner",
                    email = "owner@pocketdev.local",
                    role = TeamMemberRole.OWNER
                )
            )
        )
        _activeSession.value = session
        return session
    }

    fun addComment(filePath: String, lineNumber: Int, author: String, text: String): CodeComment {
        val comment = CodeComment(
            author = author,
            filePath = filePath,
            lineNumber = lineNumber,
            commentText = text
        )
        val current = _activeSession.value
        if (current != null) {
            _activeSession.value = current.copy(comments = current.comments + comment)
        }
        return comment
    }

    fun toggleLiveSync(enabled: Boolean) {
        _activeSession.value = _activeSession.value?.copy(isLiveSyncEnabled = enabled)
    }

    fun endShareSession() {
        _activeSession.value = null
    }
}
