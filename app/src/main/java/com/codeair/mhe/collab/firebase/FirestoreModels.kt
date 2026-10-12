package com.codeair.mhe.collab.firebase

import com.codeair.mhe.collab.model.TeamMemberRole

/**
 * Firestore document models for Firebase-backed collaboration (Milestone 12).
 *
 * Collection layout under `workspaces/{workspaceId}`:
 * - `workspaces/{workspaceId}`               — workspace metadata
 * - `workspaces/{workspaceId}/members/{uid}` — membership records, keyed by Firebase Auth UID
 * - `workspaces/{workspaceId}/invites/{code}` — pending/revoked invitations
 * - `workspaces/{workspaceId}/presence/{uid}` — live presence heartbeats, keyed by UID
 * - `workspaces/{workspaceId}/sync/{docId}`  — versioned collaborative documents
 *
 * Every model round-trips through [toMap]/[fromMap] so the real Firestore SDK
 * and the unit-test fakes share one serialization contract.
 */
object FirestorePaths {
    fun workspace(workspaceId: String) = "workspaces/$workspaceId"
    fun members(workspaceId: String) = "workspaces/$workspaceId/members"
    fun member(workspaceId: String, uid: String) = "workspaces/$workspaceId/members/$uid"
    fun invites(workspaceId: String) = "workspaces/$workspaceId/invites"
    fun invite(workspaceId: String, code: String) = "workspaces/$workspaceId/invites/$code"
    fun presence(workspaceId: String) = "workspaces/$workspaceId/presence"
    fun presenceDoc(workspaceId: String, uid: String) = "workspaces/$workspaceId/presence/$uid"
    fun sync(workspaceId: String) = "workspaces/$workspaceId/sync"
    fun syncDoc(workspaceId: String, docId: String) = "workspaces/$workspaceId/sync/$docId"
}

/** Metadata document for a shared workspace. */
data class WorkspaceDocument(
    val id: String,
    val name: String,
    val ownerUid: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): WorkspaceDocument = WorkspaceDocument(
            id = map["id"] as String,
            name = map["name"] as String,
            ownerUid = map["ownerUid"] as String,
            createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L,
            updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L
        )
    }
}

/** Membership record. The document id IS the Firebase Auth UID of the member. */
data class WorkspaceMemberDocument(
    val uid: String,
    val displayName: String,
    val email: String,
    val role: TeamMemberRole = TeamMemberRole.CONTRIBUTOR,
    val joinedAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "uid" to uid,
        "displayName" to displayName,
        "email" to email,
        "role" to role.name,
        "joinedAt" to joinedAt,
        "lastActiveAt" to lastActiveAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): WorkspaceMemberDocument = WorkspaceMemberDocument(
            uid = map["uid"] as String,
            displayName = map["displayName"] as? String ?: "",
            email = map["email"] as? String ?: "",
            role = (map["role"] as? String)?.let { runCatching { TeamMemberRole.valueOf(it) }.getOrNull() }
                ?: TeamMemberRole.CONTRIBUTOR,
            joinedAt = (map["joinedAt"] as? Number)?.toLong() ?: 0L,
            lastActiveAt = (map["lastActiveAt"] as? Number)?.toLong() ?: 0L
        )
    }
}

/** Invitation lifecycle states. */
enum class InviteStatus { PENDING, ACCEPTED, REVOKED, EXPIRED }

/** Invitation document. The document id is the short invite code shared out-of-band. */
data class InviteDocument(
    val code: String,
    val workspaceId: String,
    val workspaceName: String,
    val email: String,
    val role: TeamMemberRole = TeamMemberRole.CONTRIBUTOR,
    val invitedByUid: String,
    val invitedByName: String,
    val status: InviteStatus = InviteStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "code" to code,
        "workspaceId" to workspaceId,
        "workspaceName" to workspaceName,
        "email" to email,
        "role" to role.name,
        "invitedByUid" to invitedByUid,
        "invitedByName" to invitedByName,
        "status" to status.name,
        "createdAt" to createdAt,
        "expiresAt" to expiresAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): InviteDocument = InviteDocument(
            code = map["code"] as String,
            workspaceId = map["workspaceId"] as String,
            workspaceName = map["workspaceName"] as? String ?: "",
            email = map["email"] as? String ?: "",
            role = (map["role"] as? String)?.let { runCatching { TeamMemberRole.valueOf(it) }.getOrNull() }
                ?: TeamMemberRole.CONTRIBUTOR,
            invitedByUid = map["invitedByUid"] as? String ?: "",
            invitedByName = map["invitedByName"] as? String ?: "",
            status = (map["status"] as? String)?.let { runCatching { InviteStatus.valueOf(it) }.getOrNull() }
                ?: InviteStatus.PENDING,
            createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L,
            expiresAt = (map["expiresAt"] as? Number)?.toLong() ?: 0L
        )
    }
}

/** Live presence heartbeat for one device of one member. */
data class PresenceDocument(
    val uid: String,
    val displayName: String,
    val deviceLabel: String,
    val lastHeartbeat: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "uid" to uid,
        "displayName" to displayName,
        "deviceLabel" to deviceLabel,
        "lastHeartbeat" to lastHeartbeat
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): PresenceDocument = PresenceDocument(
            uid = map["uid"] as String,
            displayName = map["displayName"] as? String ?: "",
            deviceLabel = map["deviceLabel"] as? String ?: "",
            lastHeartbeat = (map["lastHeartbeat"] as? Number)?.toLong() ?: 0L
        )
    }
}
