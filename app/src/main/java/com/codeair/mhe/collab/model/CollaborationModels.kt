package com.codeair.mhe.collab.model

import java.util.UUID

/**
 * Role hierarchy for workspace team members.
 */
enum class TeamMemberRole(val displayName: String, val description: String) {
    OWNER("Owner", "Full workspace ownership and billing authority"),
    MAINTAINER("Maintainer", "Can merge changes, trigger builds, and invite contributors"),
    CONTRIBUTOR("Contributor", "Can edit code, submit review comments, and run agents"),
    VIEWER("Viewer", "Read-only access to code, reviews, and execution logs")
}

/**
 * Online activity status of a team member.
 */
enum class MemberStatus {
    ACTIVE,
    INVITED,
    OFFLINE,
    SUSPENDED
}

/**
 * Granular Role-Based Access Control (RBAC) permissions.
 */
data class TeamPermissions(
    val canEditCode: Boolean = true,
    val canRunTerminal: Boolean = false,
    val canTriggerAgent: Boolean = true,
    val canReviewCode: Boolean = true,
    val canManageCheckpoints: Boolean = false,
    val canInviteMembers: Boolean = false,
    val canManageOrganization: Boolean = false
) {
    companion object {
        fun forRole(role: TeamMemberRole): TeamPermissions = when (role) {
            TeamMemberRole.OWNER -> TeamPermissions(
                canEditCode = true,
                canRunTerminal = true,
                canTriggerAgent = true,
                canReviewCode = true,
                canManageCheckpoints = true,
                canInviteMembers = true,
                canManageOrganization = true
            )
            TeamMemberRole.MAINTAINER -> TeamPermissions(
                canEditCode = true,
                canRunTerminal = true,
                canTriggerAgent = true,
                canReviewCode = true,
                canManageCheckpoints = true,
                canInviteMembers = true,
                canManageOrganization = false
            )
            TeamMemberRole.CONTRIBUTOR -> TeamPermissions(
                canEditCode = true,
                canRunTerminal = false,
                canTriggerAgent = true,
                canReviewCode = true,
                canManageCheckpoints = false,
                canInviteMembers = false,
                canManageOrganization = false
            )
            TeamMemberRole.VIEWER -> TeamPermissions(
                canEditCode = false,
                canRunTerminal = false,
                canTriggerAgent = false,
                canReviewCode = true,
                canManageCheckpoints = false,
                canInviteMembers = false,
                canManageOrganization = false
            )
        }
    }
}

/**
 * Collaborator in a shared workspace.
 */
data class TeamMember(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val email: String,
    val role: TeamMemberRole = TeamMemberRole.CONTRIBUTOR,
    val status: MemberStatus = MemberStatus.ACTIVE,
    val avatarUrl: String? = null,
    val joinedAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
    val permissions: TeamPermissions = TeamPermissions.forRole(role)
)

/**
 * Plan tiers for team accounts.
 */
enum class OrganizationPlan(val displayName: String, val maxSeats: Int, val maxSharedProjects: Int) {
    FREE("Community Free", 3, 2),
    PRO("Pro Team", 15, 20),
    ENTERPRISE("Enterprise", 100, 1000)
}

/**
 * Organization holding shared team workspaces.
 */
data class OrganizationAccount(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val slug: String,
    val plan: OrganizationPlan = OrganizationPlan.PRO,
    val totalMembersCount: Int = 1,
    val sharedProjectsCount: Int = 1,
    val ownerEmail: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Threaded reply to an inline code review comment.
 */
data class CommentReply(
    val id: String = UUID.randomUUID().toString(),
    val author: String,
    val replyText: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Line-specific code review comment thread.
 */
data class CodeComment(
    val id: String = UUID.randomUUID().toString(),
    val author: String,
    val filePath: String,
    val lineNumber: Int,
    val commentText: String,
    val resolved: Boolean = false,
    val resolvedBy: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val replies: List<CommentReply> = emptyList()
)

/**
 * Shared workspace checkpoint snapshot.
 */
data class SharedCheckpoint(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String,
    val description: String = "",
    val commitSha: String? = null,
    val changedFilesCount: Int = 0,
    val manifestHash: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val restoreCount: Int = 0
)

/**
 * Type of collaborative activity logged in session audit trail.
 */
enum class CollaborationEventType {
    SESSION_CREATED,
    MEMBER_JOINED,
    MEMBER_LEFT,
    ROLE_CHANGED,
    COMMENT_ADDED,
    COMMENT_RESOLVED,
    CHECKPOINT_CREATED,
    CHECKPOINT_RESTORED,
    AGENT_JOB_TRIGGERED,
    LIVE_SYNC_TOGGLED
}

/**
 * Audit log entry tracking actions within a session.
 */
data class CollaborationEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: CollaborationEventType,
    val actorName: String,
    val description: String,
    val targetId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Multi-user AI agent job shared across the session.
 */
data class SharedAgentJob(
    val id: String = UUID.randomUUID().toString(),
    val sessionShareId: String,
    val initiatedBy: String,
    val assignedRole: String = "Full-Stack Dev",
    val prompt: String,
    val status: String = "QUEUED",
    val logs: List<String> = emptyList(),
    val pendingApprovalAction: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Active collaborative project session.
 */
data class SharedProjectSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val projectId: String,
    val projectName: String = "",
    val shareUrl: String = "",
    val shareToken: String = UUID.randomUUID().toString().take(8).uppercase(),
    val isLiveSyncEnabled: Boolean = false,
    val members: List<TeamMember> = emptyList(),
    val comments: List<CodeComment> = emptyList(),
    val checkpoints: List<SharedCheckpoint> = emptyList(),
    val agentJobs: List<SharedAgentJob> = emptyList(),
    val history: List<CollaborationEvent> = emptyList(),
    val organization: OrganizationAccount = OrganizationAccount(name = "Default Org", slug = "default-org"),
    val createdAt: Long = System.currentTimeMillis(),
    val lastActivityAt: Long = System.currentTimeMillis()
)

/**
 * Aggregate state of workspace collaboration.
 */
data class CollaborationSessionState(
    val activeSession: SharedProjectSession? = null,
    val organization: OrganizationAccount = OrganizationAccount(name = "Mobile Harness Extended Workspace", slug = "local-workspace"),
    val availableCheckpoints: List<SharedCheckpoint> = emptyList(),
    val pendingReviewComments: List<CodeComment> = emptyList(),
    val activeAgentJobs: List<SharedAgentJob> = emptyList(),
    val recentEvents: List<CollaborationEvent> = emptyList(),
    val isLiveSyncActive: Boolean = false,
    val networkLatencyMs: Long = 32L
)
