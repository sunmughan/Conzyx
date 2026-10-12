package com.jarves.mh.collab.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConflictResolverTest {

    private fun field(value: Any?, versions: Map<String, Long>, at: Long = 1_000L, by: String = "a") =
        VersionedField(value = value, versions = versions, updatedAt = at, updatedBy = by)

    @Test
    fun `disjoint fields are unioned`() {
        val merged = SyncConflictResolver.merge(
            VersionedDocument(mapOf("x" to field(1, mapOf("a" to 1L)))),
            VersionedDocument(mapOf("y" to field(2, mapOf("b" to 1L))))
        )
        assertEquals(setOf("x", "y"), merged.fields.keys)
        assertEquals(1, merged.fields["x"]!!.value)
        assertEquals(2, merged.fields["y"]!!.value)
    }

    @Test
    fun `dominating write wins without tie-break`() {
        // "a" wrote twice (counter 2); "b" only saw the first write.
        val local = VersionedDocument(mapOf("x" to field("new", mapOf("a" to 2L), at = 1_000L, by = "a")))
        val remote = VersionedDocument(mapOf("x" to field("old", mapOf("a" to 1L), at = 2_000L, by = "b")))
        val merged = SyncConflictResolver.merge(local, remote)
        assertEquals("new", merged.fields["x"]!!.value)
    }

    @Test
    fun `concurrent writes fall back to last-writer-wins`() {
        val local = VersionedDocument(mapOf("x" to field("from-a", mapOf("a" to 1L), at = 1_000L, by = "a")))
        val remote = VersionedDocument(mapOf("x" to field("from-b", mapOf("b" to 1L), at = 2_000L, by = "b")))
        val merged = SyncConflictResolver.merge(local, remote)
        assertEquals("from-b", merged.fields["x"]!!.value)
        // Merged vector clock keeps both writers' history.
        assertEquals(mapOf("a" to 1L, "b" to 1L), merged.fields["x"]!!.versions)
    }

    @Test
    fun `timestamp tie breaks on smaller uid`() {
        val local = VersionedDocument(mapOf("x" to field("from-b", mapOf("b" to 1L), at = 1_000L, by = "b")))
        val remote = VersionedDocument(mapOf("x" to field("from-a", mapOf("a" to 1L), at = 1_000L, by = "a")))
        val merged = SyncConflictResolver.merge(local, remote)
        assertEquals("from-a", merged.fields["x"]!!.value)
    }

    @Test
    fun `winning tombstone deletes the field`() {
        val local = VersionedDocument(
            mapOf("x" to VersionedField(value = null, versions = mapOf("a" to 2L), updatedAt = 2_000L, updatedBy = "a", tombstone = true))
        )
        val remote = VersionedDocument(mapOf("x" to field("old", mapOf("a" to 1L), at = 1_000L, by = "a")))
        val merged = SyncConflictResolver.merge(local, remote)
        assertFalse(merged.fields.containsKey("x"))
    }

    @Test
    fun `losing tombstone keeps the value`() {
        val local = VersionedDocument(
            mapOf("x" to VersionedField(value = null, versions = mapOf("a" to 1L), updatedAt = 1_000L, updatedBy = "a", tombstone = true))
        )
        val remote = VersionedDocument(mapOf("x" to field("kept", mapOf("a" to 2L), at = 2_000L, by = "a")))
        val merged = SyncConflictResolver.merge(local, remote)
        assertEquals("kept", merged.fields["x"]!!.value)
    }

    @Test
    fun `merge is idempotent`() {
        val local = VersionedDocument(mapOf("x" to field("v", mapOf("a" to 1L, "b" to 1L))))
        val remote = VersionedDocument(mapOf("x" to field("v2", mapOf("a" to 1L, "b" to 1L), at = 2_000L, by = "b")))
        val once = SyncConflictResolver.merge(local, remote)
        val twice = SyncConflictResolver.merge(once, remote)
        assertEquals(once, twice)
    }

    @Test
    fun `versioned field round-trips through map`() {
        val f = VersionedField(value = "v", versions = mapOf("a" to 3L), updatedAt = 42L, updatedBy = "a", tombstone = true)
        val restored = VersionedField.fromMap(f.toMap())
        assertEquals(f, restored)
        assertTrue(VersionedDocument.fromMap(VersionedDocument(mapOf("k" to f)).toMap()).fields.containsKey("k"))
    }
}
