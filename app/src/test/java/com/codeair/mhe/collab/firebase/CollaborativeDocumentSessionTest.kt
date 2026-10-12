package com.codeair.mhe.collab.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollaborativeDocumentSessionTest {

    @Test
    fun `local edit bumps own vector counter`() {
        val s = CollaborativeDocumentSession(localUid = "a")
        s.applyLocalEdit("title", "hello")
        s.applyLocalEdit("title", "hello again")
        val field = s.current().fields["title"]!!
        assertEquals("hello again", field.value)
        assertEquals(2L, field.versions["a"])
    }

    @Test
    fun `remote snapshot merges without losing local fields`() {
        val s = CollaborativeDocumentSession(localUid = "a")
        s.applyLocalEdit("title", "local title")
        val remote = VersionedDocument(
            mapOf("body" to VersionedField("remote body", mapOf("b" to 1L), updatedAt = 2_000L, updatedBy = "b"))
        )
        val merged = s.applyRemoteSnapshot(remote)
        assertEquals("local title", merged.fields["title"]!!.value)
        assertEquals("remote body", merged.fields["body"]!!.value)
    }

    @Test
    fun `concurrent edit on same field resolves deterministically`() {
        var now = 1_000L
        val s = CollaborativeDocumentSession(localUid = "a", clock = { now })
        s.applyLocalEdit("title", "from-a") // at=1000
        now = 2_000L
        val remote = VersionedDocument(
            mapOf("title" to VersionedField("from-b", mapOf("b" to 1L), updatedAt = 2_000L, updatedBy = "b"))
        )
        val merged = s.applyRemoteSnapshot(remote)
        assertEquals("from-b", merged.fields["title"]!!.value)
    }

    @Test
    fun `local delete propagates as tombstone`() {
        val s = CollaborativeDocumentSession(localUid = "a")
        s.applyLocalEdit("title", "hello")
        s.applyLocalDelete("title")
        val merged = s.applyRemoteSnapshot(VersionedDocument())
        assertFalse(merged.fields.containsKey("title"))
    }

    @Test
    fun `document flow emits on every change`() {
        val s = CollaborativeDocumentSession(localUid = "a")
        s.applyLocalEdit("x", 1)
        s.applyLocalEdit("y", 2)
        assertEquals(setOf("x", "y"), s.documentFlow.value.fields.keys)
    }

    @Test
    fun `blank field name is rejected`() {
        val s = CollaborativeDocumentSession(localUid = "a")
        try {
            s.applyLocalEdit("  ", "v")
            assertTrue("Expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }
}
