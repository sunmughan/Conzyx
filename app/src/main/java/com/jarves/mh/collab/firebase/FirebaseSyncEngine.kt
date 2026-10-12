package com.jarves.mh.collab.firebase

import com.jarves.mh.collab.model.TeamMemberRole
import com.jarves.mh.collab.model.TeamPermissions
import com.jarves.mh.core.result.OperationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Cross-device sync engine for collaborative documents (Milestone 12).
 *
 * Documents live at `workspaces/{ws}/sync/{docId}`. Writes require sign-in AND
 * workspace membership with edit permission (mirroring `firestore.rules`);
 * reads require sign-in only. Push merges the local document against the remote
 * one with [SyncConflictResolver] before writing, so concurrent device edits
 * converge instead of clobbering each other.
 */
open class FirebaseSyncEngine(
    private val gateway: FirestoreGateway,
    private val identity: FirebaseCollaborationIdentity,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private suspend fun checkWriteAccess(workspaceId: String, action: String): OperationResult? {
        val uid = identity.currentUid()
        requireSignedIn(uid, action)?.let { return it }
        val memberRaw = runCatching {
            gateway.getDocument(FirestorePaths.member(workspaceId, uid!!))
        }.getOrElse { e ->
            return OperationResult.failure("Failed to verify workspace membership: ${e.message}")
        } ?: return OperationResult.failure("You are not a member of this workspace.")
        val member = WorkspaceMemberDocument.fromMap(memberRaw)
        return if (!TeamPermissions.forRole(member.role).canEditCode) {
            OperationResult.failure(
                "Role '${member.role.displayName}' is read-only and cannot $action."
            )
        } else null
    }

    /**
     * Pushes [local] to Firestore: reads the remote document, merges with
     * [SyncConflictResolver], writes the merged result back. Reports how many
     * fields the merge produced; writes are acknowledged by the SDK, not assumed.
     */
    suspend fun pushState(
        workspaceId: String,
        docId: String,
        local: VersionedDocument
    ): OperationResult {
        checkWriteAccess(workspaceId, "push sync state")?.let { return it }
        val path = FirestorePaths.syncDoc(workspaceId, docId)
        return runCatching {
            val remoteRaw = gateway.getDocument(path)
            val remote = remoteRaw?.let { VersionedDocument.fromMap(it) } ?: VersionedDocument()
            val merged = SyncConflictResolver.merge(local, remote)
            gateway.setDocument(path, merged.toMap())
            OperationResult.success(
                logs = listOf(
                    "Synced '$docId': merged ${merged.fields.size} fields " +
                        "(local ${local.fields.size}, remote ${remote.fields.size})."
                ),
                verificationEvidence = "Merged document acknowledged by Firestore at $path."
            )
        }.getOrElse { e ->
            OperationResult.failure("Failed to push sync state: ${e.message}")
        }
    }

    /** Reads the remote document; returns an empty document when none exists yet. */
    suspend fun pullState(workspaceId: String, docId: String): VersionedDocument {
        val uid = identity.currentUid()
        if (uid.isNullOrBlank()) {
            throw IllegalStateException(
                "Firebase sign-in is required to pull sync state. " +
                    "Sign in with Firebase Auth and retry."
            )
        }
        val path = FirestorePaths.syncDoc(workspaceId, docId)
        return runCatching { gateway.getDocument(path) }
            .getOrNull()
            ?.let { VersionedDocument.fromMap(it) }
            ?: VersionedDocument()
    }

    /** Emits the remote document on every change (real-time sync). */
    fun observeState(workspaceId: String, docId: String): Flow<VersionedDocument> =
        gateway.listenDocument(FirestorePaths.syncDoc(workspaceId, docId))
            .map { raw -> raw?.let { VersionedDocument.fromMap(it) } ?: VersionedDocument() }

    /**
     * Deletes a synced document. Owner/Maintainer only (destructive action);
     * a missing document is reported honestly, not as success.
     */
    suspend fun deleteState(workspaceId: String, docId: String): OperationResult {
        val uid = identity.currentUid()
        requireSignedIn(uid, "delete sync state")?.let { return it }
        val memberRaw = runCatching {
            gateway.getDocument(FirestorePaths.member(workspaceId, uid!!))
        }.getOrElse { e ->
            return OperationResult.failure("Failed to verify workspace membership: ${e.message}")
        } ?: return OperationResult.failure("You are not a member of this workspace.")
        val role = WorkspaceMemberDocument.fromMap(memberRaw).role
        if (role != TeamMemberRole.OWNER && role != TeamMemberRole.MAINTAINER) {
            return OperationResult.failure("Only Owner or Maintainer can delete synced documents.")
        }
        val path = FirestorePaths.syncDoc(workspaceId, docId)
        return runCatching {
            val exists = gateway.getDocument(path) != null
            if (!exists) return OperationResult.failure("Document '$docId' does not exist — nothing was deleted.")
            gateway.deleteDocument(path)
            OperationResult.success(
                logs = listOf("Synced document '$docId' deleted."),
                verificationEvidence = "Delete acknowledged by Firestore at $path."
            )
        }.getOrElse { e ->
            OperationResult.failure("Failed to delete sync state: ${e.message}")
        }
    }
}
