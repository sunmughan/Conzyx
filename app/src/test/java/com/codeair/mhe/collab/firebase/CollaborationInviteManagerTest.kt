package com.codeair.mhe.collab.firebase

import com.codeair.mhe.collab.model.TeamMemberRole
import com.codeair.mhe.core.result.OperationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollaborationInviteManagerTest {

    private fun manager(
        gateway: InMemoryFirestoreGateway,
        uid: String?,
        clock: () -> Long = { 1_000L }
    ) = CollaborationInviteManager(
        gateway = gateway,
        identity = FirebaseCollaborationIdentity(uidProvider = { uid }),
        clock = clock,
        codeGenerator = { "CODE1234" }
    )

    private fun seedMember(gateway: InMemoryFirestoreGateway, ws: String, uid: String, role: TeamMemberRole) {
        gateway.seed(
            FirestorePaths.member(ws, uid),
            WorkspaceMemberDocument(uid = uid, displayName = uid, email = "$uid@x.com", role = role).toMap()
        )
    }

    @Test
    fun `create invite succeeds for owner and stores pending invite`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "owner1", TeamMemberRole.OWNER)
        val result = manager(gw, "owner1").createInvite("ws1", "Team", "new@x.com", TeamMemberRole.CONTRIBUTOR, "Owner")
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(listOf("CODE1234"), result.artifacts)
        val stored = gw.getDocument(FirestorePaths.invite("ws1", "CODE1234"))!!
        assertEquals(InviteStatus.PENDING.name, stored["status"])
        assertEquals("new@x.com", stored["email"])
    }

    @Test
    fun `create invite refuses when signed out`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        val result = manager(gw, null).createInvite("ws1", "Team", "new@x.com", TeamMemberRole.CONTRIBUTOR, "Nobody")
        assertEquals(OperationStatus.NOT_CONFIGURED, result.status)
        assertTrue(gw.listCollection(FirestorePaths.invites("ws1")).isEmpty())
    }

    @Test
    fun `create invite refuses contributor without invite permission`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "dev1", TeamMemberRole.CONTRIBUTOR)
        val result = manager(gw, "dev1").createInvite("ws1", "Team", "new@x.com", TeamMemberRole.CONTRIBUTOR, "Dev")
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("cannot invite"))
    }

    @Test
    fun `create invite refuses non-member`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        val result = manager(gw, "stranger").createInvite("ws1", "Team", "new@x.com", TeamMemberRole.CONTRIBUTOR, "X")
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("not a member"))
    }

    @Test
    fun `create invite refuses owner role`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "owner1", TeamMemberRole.OWNER)
        val result = manager(gw, "owner1").createInvite("ws1", "Team", "new@x.com", TeamMemberRole.OWNER, "Owner")
        assertEquals(OperationStatus.FAILED, result.status)
    }

    @Test
    fun `accept invite creates membership and marks invite accepted`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "owner1", TeamMemberRole.OWNER)
        manager(gw, "owner1").createInvite("ws1", "Team", "new@x.com", TeamMemberRole.CONTRIBUTOR, "Owner")
        val result = manager(gw, "newbie").acceptInvite("ws1", "code1234", "Newbie")
        assertEquals(OperationStatus.SUCCESS, result.status)
        val member = gw.getDocument(FirestorePaths.member("ws1", "newbie"))!!
        assertEquals(TeamMemberRole.CONTRIBUTOR.name, member["role"])
        assertEquals(InviteStatus.ACCEPTED.name, gw.getDocument(FirestorePaths.invite("ws1", "CODE1234"))!!["status"])
    }

    @Test
    fun `accept invite refuses expired invite`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        gw.seed(
            FirestorePaths.invite("ws1", "OLD"),
            InviteDocument(
                code = "OLD", workspaceId = "ws1", workspaceName = "T", email = "e@x.com",
                invitedByUid = "o", invitedByName = "O", createdAt = 0L, expiresAt = 500L
            ).toMap()
        )
        val result = manager(gw, "newbie", clock = { 1_000L }).acceptInvite("ws1", "OLD", "Newbie")
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("expired"))
    }

    @Test
    fun `accept invite refuses revoked invite`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "owner1", TeamMemberRole.OWNER)
        manager(gw, "owner1").createInvite("ws1", "Team", "n@x.com", TeamMemberRole.CONTRIBUTOR, "Owner")
        manager(gw, "owner1").revokeInvite("ws1", "CODE1234")
        val result = manager(gw, "newbie").acceptInvite("ws1", "CODE1234", "Newbie")
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("revoked"))
    }

    @Test
    fun `revoke invite succeeds for maintainer`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "m1", TeamMemberRole.MAINTAINER)
        seedMember(gw, "ws1", "owner1", TeamMemberRole.OWNER)
        manager(gw, "owner1").createInvite("ws1", "Team", "n@x.com", TeamMemberRole.CONTRIBUTOR, "Owner")
        val result = manager(gw, "m1").revokeInvite("ws1", "CODE1234")
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(InviteStatus.REVOKED.name, gw.getDocument(FirestorePaths.invite("ws1", "CODE1234"))!!["status"])
    }

    @Test
    fun `change role succeeds for owner`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "owner1", TeamMemberRole.OWNER)
        seedMember(gw, "ws1", "dev1", TeamMemberRole.CONTRIBUTOR)
        val result = manager(gw, "owner1").changeRole("ws1", "dev1", TeamMemberRole.MAINTAINER)
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(TeamMemberRole.MAINTAINER.name, gw.getDocument(FirestorePaths.member("ws1", "dev1"))!!["role"])
    }

    @Test
    fun `change role refuses maintainer caller`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "m1", TeamMemberRole.MAINTAINER)
        seedMember(gw, "ws1", "dev1", TeamMemberRole.CONTRIBUTOR)
        val result = manager(gw, "m1").changeRole("ws1", "dev1", TeamMemberRole.MAINTAINER)
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("Only the workspace Owner"))
    }

    @Test
    fun `change role refuses demoting the owner`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "owner1", TeamMemberRole.OWNER)
        val result = manager(gw, "owner1").changeRole("ws1", "owner1", TeamMemberRole.CONTRIBUTOR)
        assertEquals(OperationStatus.FAILED, result.status)
    }

    @Test
    fun `remove member succeeds for owner`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "owner1", TeamMemberRole.OWNER)
        seedMember(gw, "ws1", "dev1", TeamMemberRole.CONTRIBUTOR)
        val result = manager(gw, "owner1").removeMember("ws1", "dev1")
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(null, gw.getDocument(FirestorePaths.member("ws1", "dev1")))
    }

    @Test
    fun `member can leave on their own`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "owner1", TeamMemberRole.OWNER)
        seedMember(gw, "ws1", "dev1", TeamMemberRole.CONTRIBUTOR)
        val result = manager(gw, "dev1").removeMember("ws1", "dev1")
        assertEquals(OperationStatus.SUCCESS, result.status)
    }

    @Test
    fun `owner cannot be removed`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "owner1", TeamMemberRole.OWNER)
        val result = manager(gw, "owner1").removeMember("ws1", "owner1")
        assertEquals(OperationStatus.FAILED, result.status)
        assertNotNull(gw.getDocument(FirestorePaths.member("ws1", "owner1")))
    }

    @Test
    fun `transport failure is reported honestly`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        seedMember(gw, "ws1", "owner1", TeamMemberRole.OWNER)
        gw.failWith = RuntimeException("network down")
        val result = manager(gw, "owner1").createInvite("ws1", "Team", "n@x.com", TeamMemberRole.CONTRIBUTOR, "O")
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("network down"))
    }
}
