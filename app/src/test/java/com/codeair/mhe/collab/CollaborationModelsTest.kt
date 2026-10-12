package com.codeair.mhe.collab

import com.codeair.mhe.collab.model.CodeComment
import com.codeair.mhe.collab.model.CollaborationEvent
import com.codeair.mhe.collab.model.CollaborationEventType
import com.codeair.mhe.collab.model.CommentReply
import com.codeair.mhe.collab.model.MemberStatus
import com.codeair.mhe.collab.model.OrganizationAccount
import com.codeair.mhe.collab.model.OrganizationPlan
import com.codeair.mhe.collab.model.SharedAgentJob
import com.codeair.mhe.collab.model.SharedCheckpoint
import com.codeair.mhe.collab.model.SharedProjectSession
import com.codeair.mhe.collab.model.TeamMember
import com.codeair.mhe.collab.model.TeamMemberRole
import com.codeair.mhe.collab.model.TeamPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollaborationModelsTest {

    @Test
    fun testTeamMemberRolesExist() {
        val roles = TeamMemberRole.entries
        assertEquals(4, roles.size)

        val names = roles.map { it.name }.toSet()
        assertTrue(names.contains("OWNER"))
        assertTrue(names.contains("MAINTAINER"))
        assertTrue(names.contains("CONTRIBUTOR"))
        assertTrue(names.contains("VIEWER"))

        roles.forEach { role ->
            assertTrue(role.displayName.isNotBlank())
            assertTrue(role.description.isNotBlank())
        }
    }

    @Test
    fun testRbacPermissionsMatrix() {
        val ownerPerms = TeamPermissions.forRole(TeamMemberRole.OWNER)
        assertTrue(ownerPerms.canEditCode)
        assertTrue(ownerPerms.canRunTerminal)
        assertTrue(ownerPerms.canTriggerAgent)
        assertTrue(ownerPerms.canReviewCode)
        assertTrue(ownerPerms.canManageCheckpoints)
        assertTrue(ownerPerms.canInviteMembers)
        assertTrue(ownerPerms.canManageOrganization)

        val maintainerPerms = TeamPermissions.forRole(TeamMemberRole.MAINTAINER)
        assertTrue(maintainerPerms.canEditCode)
        assertTrue(maintainerPerms.canRunTerminal)
        assertTrue(maintainerPerms.canTriggerAgent)
        assertTrue(maintainerPerms.canReviewCode)
        assertTrue(maintainerPerms.canManageCheckpoints)
        assertTrue(maintainerPerms.canInviteMembers)
        assertFalse(maintainerPerms.canManageOrganization)

        val contributorPerms = TeamPermissions.forRole(TeamMemberRole.CONTRIBUTOR)
        assertTrue(contributorPerms.canEditCode)
        assertFalse(contributorPerms.canRunTerminal)
        assertTrue(contributorPerms.canTriggerAgent)
        assertTrue(contributorPerms.canReviewCode)
        assertFalse(contributorPerms.canManageCheckpoints)
        assertFalse(contributorPerms.canInviteMembers)

        val viewerPerms = TeamPermissions.forRole(TeamMemberRole.VIEWER)
        assertFalse(viewerPerms.canEditCode)
        assertFalse(viewerPerms.canRunTerminal)
        assertFalse(viewerPerms.canTriggerAgent)
        assertTrue(viewerPerms.canReviewCode)
        assertFalse(viewerPerms.canManageCheckpoints)
    }

    @Test
    fun testTeamMemberDefaults() {
        val member = TeamMember(
            name = "Alice Engineer",
            email = "alice@example.com",
            role = TeamMemberRole.CONTRIBUTOR
        )
        assertNotNull(member.id)
        assertEquals("Alice Engineer", member.name)
        assertEquals("alice@example.com", member.email)
        assertEquals(TeamMemberRole.CONTRIBUTOR, member.role)
        assertEquals(MemberStatus.ACTIVE, member.status)
        assertTrue(member.permissions.canEditCode)
        assertFalse(member.permissions.canRunTerminal)
    }

    @Test
    fun testOrganizationAccountAndPlans() {
        val org = OrganizationAccount(
            name = "Engineering Org",
            slug = "eng-org",
            plan = OrganizationPlan.ENTERPRISE
        )
        assertEquals("Engineering Org", org.name)
        assertEquals(OrganizationPlan.ENTERPRISE, org.plan)
        assertEquals(100, org.plan.maxSeats)
        assertEquals(1000, org.plan.maxSharedProjects)
    }

    @Test
    fun testCodeCommentAndReplies() {
        val reply = CommentReply(author = "Bob", replyText = "Fixed in commit 123")
        val comment = CodeComment(
            author = "Alice",
            filePath = "MainActivity.kt",
            lineNumber = 42,
            commentText = "Missing null safety check",
            replies = listOf(reply)
        )

        assertEquals("MainActivity.kt", comment.filePath)
        assertEquals(42, comment.lineNumber)
        assertFalse(comment.resolved)
        assertEquals(1, comment.replies.size)
        assertEquals("Bob", comment.replies.first().author)
    }

    @Test
    fun testSharedCheckpointModel() {
        val cp = SharedCheckpoint(
            title = "v1.0 Milestone Release",
            author = "Alice",
            description = "Production ready build",
            commitSha = "a1b2c3d4",
            changedFilesCount = 14
        )
        assertEquals("v1.0 Milestone Release", cp.title)
        assertEquals(0, cp.restoreCount)
        assertEquals(14, cp.changedFilesCount)
        assertEquals("a1b2c3d4", cp.commitSha)
    }

    @Test
    fun testSharedAgentJobModel() {
        val job = SharedAgentJob(
            sessionShareId = "session-123",
            initiatedBy = "Alice",
            assignedRole = "DevOps Engineer",
            prompt = "Run Android unit tests and generate release APK",
            status = "RUNNING",
            logs = listOf("Build started", "Compiling Kotlin...")
        )
        assertEquals("session-123", job.sessionShareId)
        assertEquals("DevOps Engineer", job.assignedRole)
        assertEquals("RUNNING", job.status)
        assertEquals(2, job.logs.size)
    }

    @Test
    fun testSharedProjectSessionDefaults() {
        val session = SharedProjectSession(
            projectId = "project-xyz",
            projectName = "Android App"
        )
        assertNotNull(session.sessionId)
        assertEquals("project-xyz", session.projectId)
        assertEquals("Android App", session.projectName)
        assertTrue(session.members.isEmpty())
        assertTrue(session.comments.isEmpty())
        assertTrue(session.checkpoints.isEmpty())
        assertFalse(session.isLiveSyncEnabled)
    }
}
