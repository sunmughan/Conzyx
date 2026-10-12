package com.codeair.mhe.collab.firebase

import com.codeair.mhe.collab.model.TeamMemberRole
import com.codeair.mhe.core.result.OperationStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FirebaseSyncEngineTest {

    private fun engine(gw: InMemoryFirestoreGateway, uid: String?) = FirebaseSyncEngine(
        gateway = gw,
        identity = FirebaseCollaborationIdentity(uidProvider = { uid })
    )

    private fun seedMember(gw: InMemoryFirestoreGateway, ws: String, uid: String, role: TeamMemberRole) {
        gw.seed(
            FirestorePaths.member(ws, uid),
            WorkspaceMemberDocument(uid = uid, displayName = uid, email = "$uid@x.com", role = role).toMap()
        )
    }

    private fun doc(vararg pairs: Pair<String, Any?>) = VersionedDocument(
        pairs.associate { (k, v) -> k to VersionedField(value = v, versions = mapOf("dev" to 1L), updatedBy = "dev") }
    )

    @Test
    fun `push writes merged document and reports success`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "dev", TeamMemberRole.CONTRIBUTOR)
        val result = engine(gw, "dev").pushState("ws1", "notes", doc("title" to "hello"))
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertTrue(result.verificationEvidence!!.contains("workspaces/ws1/sync/notes"))
        val stored = VersionedDocument.fromMap(gw.getDocument(FirestorePaths.syncDoc("ws1", "notes"))!!)
        assertEquals("hello", stored.fields["title"]!!.value)
    }

    @Test
    fun `push merges concurrent remote edits instead of clobbering`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "dev", TeamMemberRole.CONTRIBUTOR)
        val e = engine(gw, "dev")
        // Remote side already has field "b" from another device.
        e.pushState("ws1", "notes", doc("a" to "local"))
        gw.setDocument(
            FirestorePaths.syncDoc("ws1", "notes"),
            VersionedDocument(
                mapOf(
                    "a" to VersionedField("local", mapOf("dev" to 1L), updatedBy = "dev"),
                    "b" to VersionedField("remote", mapOf("other" to 1L), updatedBy = "other")
                )
            ).toMap()
        )
        val result = e.pushState("ws1", "notes", doc("a" to "local"))
        assertEquals(OperationStatus.SUCCESS, result.status)
        val stored = VersionedDocument.fromMap(gw.getDocument(FirestorePaths.syncDoc("ws1", "notes"))!!)
        assertEquals("local", stored.fields["a"]!!.value)
        assertEquals("remote", stored.fields["b"]!!.value)
    }

    @Test
    fun `push refuses viewer role`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "v1", TeamMemberRole.VIEWER)
        val result = engine(gw, "v1").pushState("ws1", "notes", doc("a" to "x"))
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("read-only"))
    }

    @Test
    fun `push refuses non-member`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        val result = engine(gw, "stranger").pushState("ws1", "notes", doc("a" to "x"))
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("not a member"))
    }

    @Test
    fun `push refuses when signed out`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        val result = engine(gw, null).pushState("ws1", "notes", doc("a" to "x"))
        assertEquals(OperationStatus.NOT_CONFIGURED, result.status)
    }

    @Test
    fun `pull returns empty document when nothing synced yet`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "dev", TeamMemberRole.CONTRIBUTOR)
        assertTrue(engine(gw, "dev").pullState("ws1", "missing").fields.isEmpty())
    }

    @Test
    fun `pull throws when signed out`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        try {
            engine(gw, null).pullState("ws1", "notes")
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("sign-in"))
        }
    }

    @Test
    fun `observe emits remote updates`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "dev", TeamMemberRole.CONTRIBUTOR)
        val e = engine(gw, "dev")
        e.pushState("ws1", "notes", doc("a" to "v1"))
        val seen = e.observeState("ws1", "notes").first()
        assertEquals("v1", seen.fields["a"]!!.value)
        e.pushState("ws1", "notes", doc("a" to "v2"))
        val seen2 = e.observeState("ws1", "notes").first()
        assertEquals("v2", seen2.fields["a"]!!.value)
    }

    @Test
    fun `delete removes document for maintainer`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "m1", TeamMemberRole.MAINTAINER)
        val e = engine(gw, "m1")
        e.pushState("ws1", "notes", doc("a" to "x"))
        val result = e.deleteState("ws1", "notes")
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(null, gw.getDocument(FirestorePaths.syncDoc("ws1", "notes")))
    }

    @Test
    fun `delete refuses contributor`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "dev", TeamMemberRole.CONTRIBUTOR)
        val e = engine(gw, "dev")
        e.pushState("ws1", "notes", doc("a" to "x"))
        val result = e.deleteState("ws1", "notes")
        assertEquals(OperationStatus.FAILED, result.status)
    }

    @Test
    fun `delete reports missing document honestly`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "m1", TeamMemberRole.MAINTAINER)
        val result = engine(gw, "m1").deleteState("ws1", "ghost")
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("does not exist"))
    }
}
