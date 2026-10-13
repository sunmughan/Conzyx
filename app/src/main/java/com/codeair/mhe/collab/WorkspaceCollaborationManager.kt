package com.codeair.mhe.collab

import com.codeair.mhe.collab.agent.SharedAgentJobDispatcher
import com.codeair.mhe.collab.checkpoint.SharedCheckpointEngine
import com.codeair.mhe.collab.model.CodeComment
import com.codeair.mhe.collab.model.CollaborationEvent
import com.codeair.mhe.collab.model.CollaborationEventType
import com.codeair.mhe.collab.model.CollaborationSessionState
import com.codeair.mhe.collab.model.CommentReply
import com.codeair.mhe.collab.model.MemberStatus
import com.codeair.mhe.collab.model.OrganizationAccount
import com.codeair.mhe.collab.model.OrganizationPlan
import com.codeair.mhe.collab.model.SharedAgentJob
import com.codeair.mhe.collab.model.SharedCheckpoint
import com.codeair.mhe.collab.model.SharedProjectSession
import com.codeair.mhe.collab.model.TeamMember
import com.codeair.mhe.collab.model.TeamMemberRole
import com.codeair.mhe.collab.model.TeamPermissions
import com.codeair.mhe.collab.review.CodeReviewCommentEngine
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Backward-compatible typealiases
typealias TeamMemberRole = com.codeair.mhe.collab.model.TeamMemberRole
typealias TeamMember = com.codeair.mhe.collab.model.TeamMember
typealias CodeComment = com.codeair.mhe.collab.model.CodeComment
typealias SharedProjectSession = com.codeair.mhe.collab.model.SharedProjectSession

/**
 * Workspace Collaboration & Team Sharing Manager (Phase 13 v2.4).
 * Coordinates multi-user workspace sharing, live synchronization, inline code review discussions,
 * milestone checkpoints, and collaborative AI agent execution with RBAC permissions.
 */
class WorkspaceCollaborationManager(
    val reviewEngine: CodeReviewCommentEngine = CodeReviewCommentEngine(),
    val checkpointEngine: SharedCheckpointEngine = SharedCheckpointEngine(),
    val agentDispatcher: SharedAgentJobDispatcher = SharedAgentJobDispatcher()
) {

    private val _activeSession = MutableStateFlow<SharedProjectSession?>(null)
    val activeSession: StateFlow<SharedProjectSession?> = _activeSession.asStateFlow()

    private val _sessionState = MutableStateFlow(CollaborationSessionState())
    val sessionState: StateFlow<CollaborationSessionState> = _sessionState.asStateFlow()

    private val eventsList = mutableListOf<CollaborationEvent>()

    /**
     * Initializes or shares a project workspace session.
     */
    fun createShareSession(projectId: String, projectName: String): SharedProjectSession {
        val shareToken = UUID.randomUUID().toString().take(8).uppercase()
        // On-device session only: there is no collaboration server, so no
        // share URL is generated. The token is a LOCAL join code that works
        // only on this device. Never fabricate a share link.
        val owner = TeamMember(
            name = "You",
            email = "",
            role = TeamMemberRole.OWNER,
            status = MemberStatus.ACTIVE,
            permissions = TeamPermissions.forRole(TeamMemberRole.OWNER)
        )

        val session = SharedProjectSession(
            projectId = projectId,
            projectName = projectName,
            shareUrl = "",
            shareToken = shareToken,
            isLiveSyncEnabled = true,
            members = listOf(owner),
            organization = _sessionState.value.organization
        )

        _activeSession.value = session
        logEvent(CollaborationEventType.SESSION_CREATED, owner.name, "Created shared workspace session for '$projectName'")
        syncAggregateState()
        return _activeSession.value ?: session
    }

    /**
     * Connects to an existing shared project session using a share token.
     */
    fun joinSession(
        shareToken: String,
        memberName: String,
        memberEmail: String
    ): SharedProjectSession? {
        val current = _activeSession.value
        if (current == null || current.shareToken != shareToken.trim().uppercase()) {
            return null
        }

        val newMember = TeamMember(
            name = memberName,
            email = memberEmail,
            role = TeamMemberRole.CONTRIBUTOR,
            status = MemberStatus.ACTIVE,
            permissions = TeamPermissions.forRole(TeamMemberRole.CONTRIBUTOR)
        )

        val updatedMembers = current.members + newMember
        val updated = current.copy(
            members = updatedMembers,
            lastActivityAt = System.currentTimeMillis()
        )
        _activeSession.value = updated
        logEvent(CollaborationEventType.MEMBER_JOINED, memberName, "$memberName joined the workspace")
        syncAggregateState()
        return updated
    }

    /**
     * Invites a new team member with assigned role.
     */
    fun inviteMember(
        name: String,
        email: String,
        role: TeamMemberRole = TeamMemberRole.CONTRIBUTOR
    ): TeamMember {
        val member = TeamMember(
            name = name,
            email = email,
            role = role,
            status = MemberStatus.INVITED,
            permissions = TeamPermissions.forRole(role)
        )

        val current = _activeSession.value
        if (current != null) {
            _activeSession.value = current.copy(
                members = current.members + member,
                lastActivityAt = System.currentTimeMillis()
            )
            logEvent(CollaborationEventType.MEMBER_JOINED, "Owner", "Invited $name ($email) as ${role.displayName}")
            syncAggregateState()
        }
        return member
    }

    /**
     * Updates an existing member's role and associated RBAC permissions.
     */
    fun setMemberRole(memberId: String, newRole: TeamMemberRole): Boolean {
        val current = _activeSession.value ?: return false
        val member = current.members.find { it.id == memberId } ?: return false

        val updatedMember = member.copy(
            role = newRole,
            permissions = TeamPermissions.forRole(newRole)
        )
        val updatedMembers = current.members.map { if (it.id == memberId) updatedMember else it }
        _activeSession.value = current.copy(
            members = updatedMembers,
            lastActivityAt = System.currentTimeMillis()
        )
        logEvent(CollaborationEventType.ROLE_CHANGED, "Admin", "Changed ${member.name}'s role to ${newRole.displayName}")
        syncAggregateState()
        return true
    }

    /**
     * Removes a collaborator from the session.
     */
    fun removeMember(memberId: String): Boolean {
        val current = _activeSession.value ?: return false
        val member = current.members.find { it.id == memberId } ?: return false
        if (member.role == TeamMemberRole.OWNER) return false // Cannot remove owner

        val updatedMembers = current.members.filter { it.id != memberId }
        _activeSession.value = current.copy(
            members = updatedMembers,
            lastActivityAt = System.currentTimeMillis()
        )
        logEvent(CollaborationEventType.MEMBER_LEFT, "Admin", "Removed ${member.name} from the session")
        syncAggregateState()
        return true
    }

    /**
     * Adds an inline code review comment.
     */
    fun addComment(
        filePath: String,
        lineNumber: Int,
        author: String,
        text: String
    ): CodeComment {
        val comment = reviewEngine.addComment(filePath, lineNumber, author, text)
        val current = _activeSession.value
        if (current != null) {
            _activeSession.value = current.copy(
                comments = reviewEngine.getAllComments(),
                lastActivityAt = System.currentTimeMillis()
            )
            logEvent(CollaborationEventType.COMMENT_ADDED, author, "Commented on $filePath:L$lineNumber")
            syncAggregateState()
        }
        return comment
    }

    /**
     * Appends a threaded reply to an existing comment.
     */
    fun addCommentReply(commentId: String, author: String, text: String): CommentReply? {
        val reply = reviewEngine.addReply(commentId, author, text)
        if (reply != null) {
            val current = _activeSession.value
            if (current != null) {
                _activeSession.value = current.copy(comments = reviewEngine.getAllComments())
                syncAggregateState()
            }
        }
        return reply
    }

    /**
     * Marks a code review comment thread as resolved.
     */
    fun resolveComment(commentId: String, resolvedBy: String = "Reviewer"): Boolean {
        val resolved = reviewEngine.resolveComment(commentId, resolvedBy)
        if (resolved) {
            val current = _activeSession.value
            if (current != null) {
                _activeSession.value = current.copy(comments = reviewEngine.getAllComments())
                logEvent(CollaborationEventType.COMMENT_RESOLVED, resolvedBy, "Resolved comment #$commentId")
                syncAggregateState()
            }
        }
        return resolved
    }

    /**
     * Creates a collaborative milestone checkpoint.
     */
    fun createCheckpoint(
        title: String,
        author: String,
        description: String = "",
        changedFilesCount: Int = 0
    ): SharedCheckpoint {
        val checkpoint = checkpointEngine.createCheckpoint(
            title = title,
            author = author,
            description = description,
            changedFilesCount = changedFilesCount
        )
        val current = _activeSession.value
        if (current != null) {
            _activeSession.value = current.copy(
                checkpoints = checkpointEngine.getAllCheckpoints(),
                lastActivityAt = System.currentTimeMillis()
            )
            logEvent(CollaborationEventType.CHECKPOINT_CREATED, author, "Created checkpoint '$title'")
            syncAggregateState()
        }
        return checkpoint
    }

    /**
     * Restores workspace to a shared checkpoint.
     */
    fun restoreCheckpoint(checkpointId: String): Boolean {
        val restored = checkpointEngine.restoreCheckpoint(checkpointId) ?: return false
        val current = _activeSession.value
        if (current != null) {
            _activeSession.value = current.copy(
                checkpoints = checkpointEngine.getAllCheckpoints(),
                lastActivityAt = System.currentTimeMillis()
            )
            logEvent(CollaborationEventType.CHECKPOINT_RESTORED, "User", "Restored checkpoint '${restored.title}'")
            syncAggregateState()
        }
        return true
    }

    /**
     * Dispatches a collaborative AI agent job visible to all team members.
     */
    fun dispatchSharedAgentJob(
        prompt: String,
        initiatedBy: String,
        assignedRole: String = "Full-Stack Dev"
    ): SharedAgentJob {
        val sessionId = _activeSession.value?.sessionId ?: UUID.randomUUID().toString()
        val job = agentDispatcher.dispatchJob(sessionId, initiatedBy, prompt, assignedRole)
        val current = _activeSession.value
        if (current != null) {
            _activeSession.value = current.copy(
                agentJobs = agentDispatcher.getAllJobs(),
                lastActivityAt = System.currentTimeMillis()
            )
            logEvent(CollaborationEventType.AGENT_JOB_TRIGGERED, initiatedBy, "Dispatched shared agent task: '${prompt.take(40)}...'")
            syncAggregateState()
        }
        return job
    }

    /**
     * Approves a gated collaborative agent action.
     */
    fun approveAgentJob(jobId: String, approvedBy: String): Boolean {
        val approved = agentDispatcher.approveJob(jobId, approvedBy)
        if (approved) {
            val current = _activeSession.value
            if (current != null) {
                _activeSession.value = current.copy(agentJobs = agentDispatcher.getAllJobs())
                syncAggregateState()
            }
        }
        return approved
    }

    /**
     * Toggles live differential synchronization across collaborators.
     */
    fun toggleLiveSync(enabled: Boolean) {
        val current = _activeSession.value ?: return
        _activeSession.value = current.copy(isLiveSyncEnabled = enabled)
        logEvent(
            CollaborationEventType.LIVE_SYNC_TOGGLED,
            "System",
            if (enabled) "Real-time LiveSync enabled" else "LiveSync paused"
        )
        syncAggregateState()
    }

    /**
     * Terminates the current collaborative sharing session.
     */
    fun endShareSession() {
        _activeSession.value = null
        reviewEngine.clearComments()
        checkpointEngine.clearCheckpoints()
        agentDispatcher.clearJobs()
        syncAggregateState()
    }

    private fun logEvent(
        type: CollaborationEventType,
        actor: String,
        description: String,
        targetId: String? = null
    ) {
        val event = CollaborationEvent(
            type = type,
            actorName = actor,
            description = description,
            targetId = targetId
        )
        eventsList.add(0, event)
        val current = _activeSession.value
        if (current != null) {
            _activeSession.value = current.copy(history = eventsList.take(50))
        }
    }

    private fun syncAggregateState() {
        val session = _activeSession.value
        _sessionState.value = _sessionState.value.copy(
            activeSession = session,
            availableCheckpoints = checkpointEngine.getAllCheckpoints(),
            pendingReviewComments = reviewEngine.getUnresolvedComments(),
            activeAgentJobs = agentDispatcher.getAllJobs(),
            recentEvents = eventsList.take(30),
            isLiveSyncActive = session?.isLiveSyncEnabled ?: false
        )
    }
}
