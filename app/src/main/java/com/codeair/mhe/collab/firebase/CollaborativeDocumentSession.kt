package com.codeair.mhe.collab.firebase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Collaborative editing session for one synced document (Milestone 12).
 *
 * Pure JVM: local edits bump this device's vector-clock counter for the field;
 * remote snapshots (from [FirebaseSyncEngine.observeState] or [pullState]) are
 * folded in through [SyncConflictResolver], so concurrent edits from multiple
 * devices converge deterministically. Emits the merged document on every change.
 *
 * This class never touches the network itself — the owning screen wires
 * [FirebaseSyncEngine.pushState] / [observeState] around it.
 */
class CollaborativeDocumentSession(
    private val localUid: String,
    private val clock: () -> Long = System::currentTimeMillis,
    initial: VersionedDocument = VersionedDocument()
) {
    private var document: VersionedDocument = initial
    private val _documentFlow = MutableStateFlow(document)
    val documentFlow: StateFlow<VersionedDocument> = _documentFlow.asStateFlow()

    /** Current merged document snapshot. */
    fun current(): VersionedDocument = document

    /**
     * Applies a local edit: sets [field] to [value], incrementing this device's
     * counter in the field's vector clock.
     */
    fun applyLocalEdit(field: String, value: Any?): VersionedDocument {
        require(field.isNotBlank()) { "Field name must not be blank." }
        val existing = document.fields[field]
        val nextCount = (existing?.versions?.get(localUid) ?: 0L) + 1L
        val edited = VersionedField(
            value = value,
            versions = (existing?.versions ?: emptyMap()) + (localUid to nextCount),
            updatedAt = clock(),
            updatedBy = localUid,
            tombstone = false
        )
        document = VersionedDocument(document.fields + (field to edited))
        _documentFlow.value = document
        return document
    }

    /** Applies a local deletion: writes a tombstone so the delete propagates. */
    fun applyLocalDelete(field: String): VersionedDocument {
        val existing = document.fields[field] ?: return document
        val nextCount = (existing.versions[localUid] ?: 0L) + 1L
        val tombstone = existing.copy(
            versions = existing.versions + (localUid to nextCount),
            updatedAt = clock(),
            updatedBy = localUid,
            tombstone = true
        )
        document = VersionedDocument(document.fields + (field to tombstone))
        _documentFlow.value = document
        return document
    }

    /**
     * Folds a remote document snapshot into this session. Returns the merged
     * document (tombstoned fields are dropped from the visible map).
     */
    fun applyRemoteSnapshot(remote: VersionedDocument): VersionedDocument {
        document = SyncConflictResolver.merge(document, remote)
        _documentFlow.value = document
        return document
    }
}
