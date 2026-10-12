package com.codeair.mhe.collab

import com.codeair.mhe.collab.model.MemberStatus
import com.codeair.mhe.collab.model.TeamMemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceCollaborationManagerTest {

    private lateinit var manager: WorkspaceCollaborationManager

    @Before
    fun setUp() {
        manager = WorkspaceCollaborationManager()
    }

    @Test
    fun testCreateShareSessionInitializesOwner() {
        val session = manager.createShareSession("proj-1", "My Project")

        assertNotNull(session)
        assertEquals("proj-1", session.projectId)
        assertEquals("My Project", session.projectName)
        assertTrue(session.shareToken.isNotBlank())
        assertTrue(session.isLiveSyncEnabled)
        assertEquals(1, session.members.size)

        val owner = session.members.first()
        assertEquals(TeamMemberRole.OWNER, owner.role)
        assertEquals(MemberStatus.ACTIVE, owner.status)
        assertTrue(owner.permissions.canManageOrganization)

        assertEquals(session, manager.activeSession.value)
    }

    @Test
    fun testJoinSessionWithValidToken() {
        val session = manager.createShareSession("proj-1", "My Project")
        val token = session.shareToken

        val joined = manager.joinSession(token, "Bob Developer", "bob@example.com")
        assertNotNull(joined)
        assertEquals(2, joined!!.members.size)

        val newMember = joined.members.find { it.name == "Bob Developer" }
        assertNotNull(newMember)
        assertEquals(TeamMemberRole.CONTRIBUTOR, newMember!!.role)
        assertTrue(newMember.permissions.canEditCode)
        assertFalse(newMember.permissions.canRunTerminal)
    }

    @Test
    fun testJoinSessionWithInvalidTokenFails() {
        manager.createShareSession("proj-1", "My Project")

        val result = manager.joinSession("WRONG_TOKEN", "Attacker", "hacker@evil.com")
        assertNull(result)
        assertEquals(1, manager.activeSession.value?.members?.size)
    }

    @Test
    fun testInviteMemberAddsInvitedStatus() {
        manager.createShareSession("proj-1", "My Project")

        val invited = manager.inviteMember("Charlie", "charlie@test.com", TeamMemberRole.MAINTAINER)
        assertEquals(TeamMemberRole.MAINTAINER, invited.role)
        assertEquals(MemberStatus.INVITED, invited.status)
        assertTrue(invited.permissions.canManageCheckpoints)

        val current = manager.activeSession.value
        assertEquals(2, current?.members?.size)
    }

    @Test
    fun testSetMemberRoleUpdatesPermissions() {
        manager.createShareSession("proj-1", "My Project")
        val contributor = manager.inviteMember("David", "david@test.com", TeamMemberRole.CONTRIBUTOR)

        assertFalse(contributor.permissions.canManageCheckpoints)

        val success = manager.setMemberRole(contributor.id, TeamMemberRole.MAINTAINER)
        assertTrue(success)

        val updated = manager.activeSession.value?.members?.find { it.id == contributor.id }
        assertNotNull(updated)
        assertEquals(TeamMemberRole.MAINTAINER, updated!!.role)
        assertTrue(updated.permissions.canManageCheckpoints)
    }

    @Test
    fun testRemoveMemberPreventsOwnerRemoval() {
        manager.createShareSession("proj-1", "My Project")
        val ownerId = manager.activeSession.value!!.members.first().id

        // Attempting to remove owner must fail
        val ownerRemoved = manager.removeMember(ownerId)
        assertFalse(ownerRemoved)
        assertEquals(1, manager.activeSession.value!!.members.size)

        // Adding and removing contributor must succeed
        val contributor = manager.inviteMember("Eve", "eve@test.com")
        assertEquals(2, manager.activeSession.value!!.members.size)

        val contributorRemoved = manager.removeMember(contributor.id)
        assertTrue(contributorRemoved)
        assertEquals(1, manager.activeSession.value!!.members.size)
    }

    @Test
    fun testToggleLiveSync() {
        manager.createShareSession("proj-1", "My Project")
        assertTrue(manager.activeSession.value!!.isLiveSyncEnabled)

        manager.toggleLiveSync(false)
        assertFalse(manager.activeSession.value!!.isLiveSyncEnabled)

        manager.toggleLiveSync(true)
        assertTrue(manager.activeSession.value!!.isLiveSyncEnabled)
    }

    @Test
    fun testEndShareSessionClearsState() {
        manager.createShareSession("proj-1", "My Project")
        manager.addComment("Main.kt", 10, "Author", "Note")

        assertNotNull(manager.activeSession.value)

        manager.endShareSession()
        assertNull(manager.activeSession.value)
        assertTrue(manager.sessionState.value.availableCheckpoints.isEmpty())
        assertTrue(manager.sessionState.value.pendingReviewComments.isEmpty())
    }

    @Test
    fun testAuditLogCapturesEvents() {
        val session = manager.createShareSession("proj-1", "My Project")
        manager.inviteMember("Member", "mem@test.com")
        manager.addComment("File.kt", 5, "Author", "Comment")

        val history = manager.activeSession.value?.history ?: emptyList()
        assertTrue(history.isNotEmpty())
        assertTrue(history.any { it.description.contains("Created shared workspace") })
        assertTrue(history.any { it.description.contains("Invited Member") })
    }
}
