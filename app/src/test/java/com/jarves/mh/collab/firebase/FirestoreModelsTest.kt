package com.jarves.mh.collab.firebase

import com.jarves.mh.collab.model.TeamMemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreModelsTest {

    @Test
    fun `workspace document round-trips through map`() {
        val doc = WorkspaceDocument(id = "ws1", name = "Team", ownerUid = "uid1")
        val restored = WorkspaceDocument.fromMap(doc.toMap())
        assertEquals(doc, restored)
    }

    @Test
    fun `member document round-trips and defaults role`() {
        val doc = WorkspaceMemberDocument(uid = "u1", displayName = "A", email = "a@x.com", role = TeamMemberRole.OWNER)
        assertEquals(doc, WorkspaceMemberDocument.fromMap(doc.toMap()))
        val minimal = WorkspaceMemberDocument.fromMap(mapOf("uid" to "u2"))
        assertEquals(TeamMemberRole.CONTRIBUTOR, minimal.role)
    }

    @Test
    fun `member document tolerates unknown role string`() {
        val doc = WorkspaceMemberDocument.fromMap(mapOf("uid" to "u", "role" to "SUPERADMIN"))
        assertEquals(TeamMemberRole.CONTRIBUTOR, doc.role)
    }

    @Test
    fun `invite document round-trips through map`() {
        val invite = InviteDocument(
            code = "ABC123", workspaceId = "ws1", workspaceName = "Team",
            email = "b@x.com", role = TeamMemberRole.MAINTAINER,
            invitedByUid = "owner", invitedByName = "Owner"
        )
        val restored = InviteDocument.fromMap(invite.toMap())
        assertEquals(invite, restored)
        assertEquals(InviteStatus.PENDING, restored.status)
    }

    @Test
    fun `presence document round-trips through map`() {
        val doc = PresenceDocument(uid = "u1", displayName = "A", deviceLabel = "Pixel", lastHeartbeat = 123L)
        assertEquals(doc, PresenceDocument.fromMap(doc.toMap()))
    }

    @Test
    fun `path helpers build expected document paths`() {
        assertEquals("workspaces/ws1/members/u1", FirestorePaths.member("ws1", "u1"))
        assertEquals("workspaces/ws1/invites/C1", FirestorePaths.invite("ws1", "C1"))
        assertEquals("workspaces/ws1/presence/u1", FirestorePaths.presenceDoc("ws1", "u1"))
        assertEquals("workspaces/ws1/sync/doc1", FirestorePaths.syncDoc("ws1", "doc1"))
    }
}
