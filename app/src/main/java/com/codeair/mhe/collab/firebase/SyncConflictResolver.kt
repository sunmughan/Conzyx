package com.codeair.mhe.collab.firebase

/**
 * Per-field versioned value for collaborative documents (Milestone 12).
 *
 * @param value the field payload (must be Firestore-serializable: primitives,
 *        lists, maps — never arbitrary JVM objects).
 * @param versions vector clock: writer UID -> number of writes that writer made
 *        to this field. Lets the merger distinguish domination from true
 *        concurrency.
 * @param updatedAt epoch millis of the winning write (tie-break only).
 * @param updatedBy UID of the last writer (tie-break only).
 * @param tombstone when true, the field is deleted in the merged document.
 */
data class VersionedField(
    val value: Any?,
    val versions: Map<String, Long> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
    val updatedBy: String = "",
    val tombstone: Boolean = false
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "value" to value,
        "versions" to versions,
        "updatedAt" to updatedAt,
        "updatedBy" to updatedBy,
        "tombstone" to tombstone
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): VersionedField = VersionedField(
            value = map["value"],
            versions = (map["versions"] as? Map<String, Any?>)
                ?.mapValues { (it.value as? Number)?.toLong() ?: 0L } ?: emptyMap(),
            updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L,
            updatedBy = map["updatedBy"] as? String ?: "",
            tombstone = map["tombstone"] as? Boolean ?: false
        )
    }
}

/** A collaborative document: field name -> versioned field. */
data class VersionedDocument(
    val fields: Map<String, VersionedField> = emptyMap()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "fields" to fields.mapValues { it.value.toMap() }
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): VersionedDocument {
            val fields = (map["fields"] as? Map<String, Any?>)
                ?.mapValues { (_, v) -> VersionedField.fromMap(v as Map<String, Any?>) }
                ?: emptyMap()
            return VersionedDocument(fields)
        }
    }
}

/**
 * Cross-device conflict resolver for collaborative documents (Milestone 12).
 *
 * Pure JVM, no Firestore dependency — merge semantics are verified in unit
 * tests against scripted concurrent edits.
 *
 * Merge rule per field:
 * 1. Field present on one side only → kept (or dropped if it is a tombstone).
 * 2. One side's vector clock dominates the other → the dominating write wins.
 * 3. Truly concurrent (neither dominates) → deterministic last-writer-wins:
 *    higher [VersionedField.updatedAt] wins; ties break on [VersionedField.updatedBy]
 *    (lexicographically smaller UID wins). The merged vector clock is the
 *    element-wise max, so a third device can still tell the merge happened.
 * Tombstone wins are applied as deletions in the merged document.
 */
object SyncConflictResolver {

    fun merge(local: VersionedDocument, remote: VersionedDocument): VersionedDocument {
        val keys = local.fields.keys + remote.fields.keys
        val merged = mutableMapOf<String, VersionedField>()
        for (key in keys) {
            val l = local.fields[key]
            val r = remote.fields[key]
            val winner = when {
                l == null -> r!!
                r == null -> l
                else -> mergeField(l, r)
            }
            if (!winner.tombstone) merged[key] = winner
        }
        return VersionedDocument(merged)
    }

    private fun mergeField(local: VersionedField, remote: VersionedField): VersionedField {
        val localDominates = dominates(local.versions, remote.versions)
        val remoteDominates = dominates(remote.versions, local.versions)
        val winner = when {
            localDominates && !remoteDominates -> local
            remoteDominates && !localDominates -> remote
            else -> tieBreak(local, remote)
        }
        // Element-wise max keeps the causal history of both writers.
        val mergedVersions = (local.versions.keys + remote.versions.keys).associateWith { uid ->
            maxOf(local.versions[uid] ?: 0L, remote.versions[uid] ?: 0L)
        }
        return winner.copy(versions = mergedVersions)
    }

    /** True when every counter in [a] is >= the matching counter in [b]. */
    private fun dominates(a: Map<String, Long>, b: Map<String, Long>): Boolean {
        if (a == b) return true
        for ((uid, counter) in b) {
            if ((a[uid] ?: 0L) < counter) return false
        }
        return true
    }

    private fun tieBreak(local: VersionedField, remote: VersionedField): VersionedField =
        when {
            remote.updatedAt != local.updatedAt ->
                if (remote.updatedAt > local.updatedAt) remote else local
            else -> if (remote.updatedBy < local.updatedBy) remote else local
        }
}
