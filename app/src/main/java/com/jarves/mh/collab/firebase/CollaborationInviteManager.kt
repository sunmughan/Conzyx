package com.jarves.mh.collab.firebase

import com.jarves.mh.collab.model.TeamMemberRole
import com.jarves.mh.collab.model.TeamPermissions
import com.jarves.mh.core.result.OperationResult
import java.util.UUID

/**
 * Invite / role / revocation management backed by Firestore (Milestone 12).
 *
 * Permission model mirrors [TeamPermissions]: only members whose role grants
 * `canInviteMembers` (OWNER, MAINTAINER) may invite, revoke, or change roles;
 * only OWNER may change roles. Every mutating call requires a signed-in UID
 * (see [requireSignedIn]) — anonymous writes are refused as NOT_CONFIGURED.
 *
 * All persistence goes through the injected [FirestoreGateway], so unit tests
 * verify the permission logic against an in-memory fake without a network.
 */
open class CollaborationInviteManager(
    private val gateway: FirestoreGateway,
    private val identity: FirebaseCollaborationIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
    private val codeGenerator: () -> String = { UUID.randomUUID().toString().take(8).uppercase() }
) {

    private suspend fun callerMember(workspaceId: String): WorkspaceMemberDocument? {
        val uid = identity.currentUid() ?: return null
        return gateway.getDocument(FirestorePaths.member(workspaceId, uid))
            ?.let { WorkspaceMemberDocument.fromMap(it) }
    }

    private fun callerCanInvite(member: WorkspaceMemberDocument?): OperationResult? {
        if (member == null) {
            return OperationResult.failure("You are not a member of this workspace, so you cannot manage invites.")
        }
        return if (!TeamPermissions.forRole(member.role).canInviteMembers) {
            OperationResult.failure(
                "Role '${member.role.displayName}' cannot invite members. " +
                    "Ask an Owner or Maintainer to send the invite."
            )
        } else null
    }

    /**
     * Creates a pending invite for [email] with [role]. The invite code is
     * returned in [OperationResult.artifacts] so the caller can share it.
     */
    suspend fun createInvite(
        workspaceId: String,
        workspaceName: String,
        email: String,
        role: TeamMemberRole = TeamMemberRole.CONTRIBUTOR,
        inviterName: String,
        ttlMs: Long = 7L * 24 * 60 * 60 * 1000
    ): OperationResult {
        val uid = identity.currentUid()
        requireSignedIn(uid, "create an invite")?.let { return it }
        if (email.isBlank()) return OperationResult.failure("Invite email must not be blank.")
        if (role == TeamMemberRole.OWNER) {
            return OperationResult.failure("Invites cannot grant the Owner role directly.")
        }
        callerCanInvite(callerMember(workspaceId))?.let { return it }

        val now = clock()
        val invite = InviteDocument(
            code = codeGenerator(),
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            email = email.trim(),
            role = role,
            invitedByUid = uid!!,
            invitedByName = inviterName,
            createdAt = now,
            expiresAt = now + ttlMs
        )
        return runCatching {
            gateway.setDocument(FirestorePaths.invite(workspaceId, invite.code), invite.toMap())
            OperationResult.success(
                logs = listOf("Invite ${invite.code} created for ${invite.email} as ${role.name}."),
                artifacts = listOf(invite.code),
                verificationEvidence = "Invite document written at ${FirestorePaths.invite(workspaceId, invite.code)}."
            )
        }.getOrElse { e ->
            OperationResult.failure("Failed to create invite: ${e.message}")
        }
    }

    /**
     * Accepts a pending invite: marks it ACCEPTED and creates the caller's
     * membership record (document id = their Auth UID). Refuses expired,
     * revoked, or already-consumed invites honestly.
     */
    suspend fun acceptInvite(workspaceId: String, code: String, displayName: String): OperationResult {
        val uid = identity.currentUid()
        requireSignedIn(uid, "accept an invite")?.let { return it }
        val callerUid = uid!!
        if (code.isBlank()) return OperationResult.failure("Invite code must not be blank.")

        val path = FirestorePaths.invite(workspaceId, code.trim().uppercase())
        val raw = runCatching { gateway.getDocument(path) }.getOrElse { e ->
            return OperationResult.failure("Failed to read invite: ${e.message}")
        } ?: return OperationResult.failure("Invite code '$code' was not found for this workspace.")
        val invite = InviteDocument.fromMap(raw)
        val now = clock()
        if (invite.status != InviteStatus.PENDING) {
            return OperationResult.failure("Invite '$code' is ${invite.status.name.lowercase()}, not pending — it cannot be accepted.")
        }
        if (now > invite.expiresAt) {
            return OperationResult.failure("Invite '$code' expired and can no longer be accepted.")
        }

        return runCatching {
            gateway.updateDocument(path, mapOf("status" to InviteStatus.ACCEPTED.name))
            val member = WorkspaceMemberDocument(
                uid = callerUid,
                displayName = displayName,
                email = invite.email,
                role = invite.role,
                joinedAt = now,
                lastActiveAt = now
            )
            gateway.setDocument(FirestorePaths.member(workspaceId, callerUid), member.toMap())
            OperationResult.success(
                logs = listOf("Invite $code accepted; ${member.displayName} joined as ${invite.role.name}."),
                verificationEvidence = "Membership document written at ${FirestorePaths.member(workspaceId, callerUid)}."
            )
        }.getOrElse { e ->
            OperationResult.failure("Failed to accept invite: ${e.message}")
        }
    }

    /** Marks an invite REVOKED. Inviter must hold invite permission. */
    suspend fun revokeInvite(workspaceId: String, code: String): OperationResult {
        val uid = identity.currentUid()
        requireSignedIn(uid, "revoke an invite")?.let { return it }
        callerCanInvite(callerMember(workspaceId))?.let { return it }

        val path = FirestorePaths.invite(workspaceId, code.trim().uppercase())
        val raw = runCatching { gateway.getDocument(path) }.getOrElse { e ->
            return OperationResult.failure("Failed to read invite: ${e.message}")
        } ?: return OperationResult.failure("Invite code '$code' was not found for this workspace.")
        val invite = InviteDocument.fromMap(raw)
        if (invite.status != InviteStatus.PENDING) {
            return OperationResult.failure("Invite '$code' is already ${invite.status.name.lowercase()} — nothing to revoke.")
        }
        return runCatching {
            gateway.updateDocument(path, mapOf("status" to InviteStatus.REVOKED.name))
            OperationResult.success(
                logs = listOf("Invite $code revoked."),
                verificationEvidence = "Invite status updated to REVOKED at $path."
            )
        }.getOrElse { e ->
            OperationResult.failure("Failed to revoke invite: ${e.message}")
        }
    }

    /** Changes a member's role. Only OWNER may do this; the workspace must keep an owner. */
    suspend fun changeRole(workspaceId: String, memberUid: String, newRole: TeamMemberRole): OperationResult {
        val uid = identity.currentUid()
        requireSignedIn(uid, "change a member role")?.let { return it }
        val caller = callerMember(workspaceId)
            ?: return OperationResult.failure("You are not a member of this workspace.")
        if (caller.role != TeamMemberRole.OWNER) {
            return OperationResult.failure("Only the workspace Owner can change member roles.")
        }
        if (newRole == TeamMemberRole.OWNER) {
            return OperationResult.failure("Ownership transfer is not supported by role change; use the transfer flow.")
        }
        val targetPath = FirestorePaths.member(workspaceId, memberUid)
        val target = runCatching { gateway.getDocument(targetPath) }.getOrElse { e ->
            return OperationResult.failure("Failed to read member: ${e.message}")
        } ?: return OperationResult.failure("Member '$memberUid' was not found in this workspace.")
        val targetDoc = WorkspaceMemberDocument.fromMap(target)
        if (targetDoc.role == TeamMemberRole.OWNER) {
            return OperationResult.failure("The workspace Owner's role cannot be changed this way.")
        }
        return runCatching {
            gateway.updateDocument(targetPath, mapOf("role" to newRole.name))
            OperationResult.success(
                logs = listOf("Member ${targetDoc.displayName} role changed to ${newRole.name}."),
                verificationEvidence = "Member role updated at $targetPath."
            )
        }.getOrElse { e ->
            OperationResult.failure("Failed to change role: ${e.message}")
        }
    }

    /**
     * Removes a member. Allowed for OWNER/MAINTAINER, or the member themselves
     * (leaving). The workspace owner cannot be removed.
     */
    suspend fun removeMember(workspaceId: String, memberUid: String): OperationResult {
        val uid = identity.currentUid()
        requireSignedIn(uid, "remove a member")?.let { return it }
        val caller = callerMember(workspaceId)
            ?: return OperationResult.failure("You are not a member of this workspace.")
        val isSelf = memberUid == uid
        val callerPerms = TeamPermissions.forRole(caller.role)
        if (!isSelf && !callerPerms.canInviteMembers) {
            return OperationResult.failure("Role '${caller.role.displayName}' cannot remove other members.")
        }
        val targetPath = FirestorePaths.member(workspaceId, memberUid)
        val target = runCatching { gateway.getDocument(targetPath) }.getOrElse { e ->
            return OperationResult.failure("Failed to read member: ${e.message}")
        } ?: return OperationResult.failure("Member '$memberUid' was not found in this workspace.")
        if (WorkspaceMemberDocument.fromMap(target).role == TeamMemberRole.OWNER) {
            return OperationResult.failure("The workspace Owner cannot be removed.")
        }
        return runCatching {
            gateway.deleteDocument(targetPath)
            OperationResult.success(
                logs = listOf("Member '$memberUid' removed from workspace."),
                verificationEvidence = "Membership document deleted at $targetPath."
            )
        }.getOrElse { e ->
            OperationResult.failure("Failed to remove member: ${e.message}")
        }
    }

    /** Lists invites in [status] (default PENDING) for a workspace. */
    suspend fun listInvites(
        workspaceId: String,
        status: InviteStatus = InviteStatus.PENDING
    ): List<InviteDocument> = gateway
        .queryEqual(FirestorePaths.invites(workspaceId), "status", status.name)
        .map { (_, data) -> InviteDocument.fromMap(data) }

    /** Lists all members of a workspace. */
    suspend fun listMembers(workspaceId: String): List<WorkspaceMemberDocument> = gateway
        .listCollection(FirestorePaths.members(workspaceId))
        .map { (_, data) -> WorkspaceMemberDocument.fromMap(data) }
}
