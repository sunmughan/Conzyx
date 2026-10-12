package com.codeair.mhe.collab.firebase

import com.codeair.mhe.core.result.OperationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live presence tracking backed by Firestore (Milestone 12).
 *
 * Each device writes a heartbeat to `workspaces/{ws}/presence/{uid}`; readers
 * treat any record older than [staleAfterMs] as offline. The [clock] seam makes
 * staleness deterministic in unit tests. Writes are keyed by Auth UID, so
 * `firestore.rules` can restrict each user to their own presence document.
 */
open class PresenceTracker(
    private val gateway: FirestoreGateway,
    private val identity: FirebaseCollaborationIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleAfterMs: Long = 2L * 60 * 1000
) {
    private val _activeMembers = MutableStateFlow<List<PresenceDocument>>(emptyList())
    val activeMembers: StateFlow<List<PresenceDocument>> = _activeMembers.asStateFlow()

    /** Writes (or refreshes) this device's heartbeat. Requires sign-in. */
    suspend fun heartbeat(
        workspaceId: String,
        displayName: String,
        deviceLabel: String
    ): OperationResult {
        val uid = identity.currentUid()
        requireSignedIn(uid, "publish presence")?.let { return it }
        val doc = PresenceDocument(
            uid = uid!!,
            displayName = displayName,
            deviceLabel = deviceLabel,
            lastHeartbeat = clock()
        )
        return runCatching {
            gateway.setDocument(FirestorePaths.presenceDoc(workspaceId, uid), doc.toMap())
            OperationResult.success(
                logs = listOf("Presence heartbeat published for $displayName ($deviceLabel)."),
                verificationEvidence = "Presence document written at ${FirestorePaths.presenceDoc(workspaceId, uid)}."
            )
        }.getOrElse { e ->
            OperationResult.failure("Failed to publish presence: ${e.message}")
        }
    }

    /** Removes this device's heartbeat (e.g. on sign-out). Requires sign-in. */
    suspend fun clearPresence(workspaceId: String): OperationResult {
        val uid = identity.currentUid()
        requireSignedIn(uid, "clear presence")?.let { return it }
        val callerUid = uid!!
        return runCatching {
            gateway.deleteDocument(FirestorePaths.presenceDoc(workspaceId, callerUid))
            OperationResult.success(
                logs = listOf("Presence cleared."),
                verificationEvidence = "Presence document deleted at ${FirestorePaths.presenceDoc(workspaceId, callerUid)}."
            )
        }.getOrElse { e ->
            OperationResult.failure("Failed to clear presence: ${e.message}")
        }
    }

    /**
     * Returns members whose heartbeat is fresher than [staleAfterMs]. Reading
     * presence requires no sign-in (members-only read is enforced by rules).
     */
    suspend fun listActive(workspaceId: String): List<PresenceDocument> {
        val now = clock()
        return gateway.listCollection(FirestorePaths.presence(workspaceId))
            .map { (_, data) -> PresenceDocument.fromMap(data) }
            .filter { now - it.lastHeartbeat <= staleAfterMs }
            .sortedByDescending { it.lastHeartbeat }
            .also { _activeMembers.value = it }
    }
}
