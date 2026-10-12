package com.jarves.mh.session

import com.jarves.mh.model.ActiveRoadmap
import com.jarves.mh.model.RoadmapStep
import com.jarves.mh.model.StepStatus
import com.jarves.mh.workspace.WorkspaceCheckpointManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies app restart recovery following simulated process termination (crash, force close, OOM).
 * Tests state restoration across distinct manager instances sharing the persistent storage directory.
 */
class AppRestartRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `simulates process crash and restores session state in fresh manager instance`() {
        val sessionsDir = tempFolder.newFolder("persisted_sessions")

        // Phase 1: Process 1 is running an agent session
        val process1Manager = AgentSessionManager(sessionsDir)
        val roadmap = ActiveRoadmap(
            id = "roadmap-crash-test",
            title = "Refactoring Auth Module",
            summary = "Refactoring Auth Module",
            steps = listOf(
                RoadmapStep(id = "step-1", title = "Extract interface", description = "Separate API", status = StepStatus.COMPLETED),
                RoadmapStep(id = "step-2", title = "Implement repository", description = "Wire Retrofit", status = StepStatus.IN_PROGRESS),
                RoadmapStep(id = "step-3", title = "Write unit tests", description = "100% coverage", status = StepStatus.PENDING),
            ),
        )

        process1Manager.recordSessionStart(
            sessionId = "session-pid-101",
            projectId = "project-alpha",
            projectTitle = "Alpha Mobile Client",
            chatId = "chat-main",
            prompt = "Refactor the auth module with clean architecture",
            roadmap = roadmap,
        )

        process1Manager.updateSessionProgress(roadmap, summary = "Completed step 1, working on step 2")

        // Phase 2: Process 1 dies abruptly (e.g. SIGKILL, Android OS memory reclamation)
        // No graceful completeSession() or clean teardown is called.

        // Phase 3: App cold restarts. Fresh manager instance created on the same storage directory.
        val process2Manager = AgentSessionManager(sessionsDir)
        val restoredSession = process2Manager.detectInterruptedSession()

        assertNotNull("Process 2 must detect the interrupted session from disk", restoredSession)
        assertEquals("session-pid-101", restoredSession?.sessionId)
        assertEquals("project-alpha", restoredSession?.projectId)
        assertEquals("Alpha Mobile Client", restoredSession?.projectTitle)
        assertEquals("chat-main", restoredSession?.chatId)
        assertEquals("Refactor the auth module with clean architecture", restoredSession?.initialPrompt)
        assertEquals(AgentSessionStatus.INTERRUPTED, restoredSession?.status)
        assertEquals("Completed step 1, working on step 2", restoredSession?.summary)

        // Verify roadmap integrity was restored
        val restoredRoadmap = restoredSession?.activeRoadmap
        assertNotNull(restoredRoadmap)
        assertEquals(3, restoredRoadmap?.steps?.size)
        assertEquals("step-1", restoredRoadmap?.steps?.get(0)?.id)
        assertEquals(StepStatus.COMPLETED, restoredRoadmap?.steps?.get(0)?.status)
        assertEquals("step-2", restoredRoadmap?.steps?.get(1)?.id)
        assertEquals(StepStatus.IN_PROGRESS, restoredRoadmap?.steps?.get(1)?.status)
    }

    @Test
    fun `recovers workspace checkpoint alongside agent session on restart`() {
        val rootDir = tempFolder.newFolder("app_storage")
        val sessionsDir = File(rootDir, "sessions")
        val checkpointsDir = File(rootDir, "checkpoints")
        val workspaceDir = tempFolder.newFolder("workspace_alpha")

        // Create sample project files
        val srcFile = File(workspaceDir, "App.kt").apply { writeText("fun main() = println(1)") }

        // Process 1: Create checkpoint and start session
        val checkpointManager1 = WorkspaceCheckpointManager(checkpointsDir)
        val checkpoint = checkpointManager1.createCheckpoint("project-alpha", workspaceDir, "pre-task baseline")
        assertNotNull(checkpoint)

        val sessionManager1 = AgentSessionManager(sessionsDir)
        sessionManager1.recordSessionStart(
            sessionId = "session-ckpt-202",
            projectId = "project-alpha",
            projectTitle = "Alpha Mobile Client",
            chatId = "chat-main",
            prompt = "Modify App.kt",
        )

        // File modified by agent before crash
        srcFile.writeText("fun main() = println(2)")

        // Process dies and restarts
        val checkpointManager2 = WorkspaceCheckpointManager(checkpointsDir)
        val sessionManager2 = AgentSessionManager(sessionsDir)

        val restoredSession = sessionManager2.detectInterruptedSession()
        assertNotNull(restoredSession)
        assertEquals("session-ckpt-202", restoredSession?.sessionId)

        // Restore pre-task baseline checkpoint
        val latestCheckpoint = checkpointManager2.listCheckpoints("project-alpha").firstOrNull()
        assertNotNull(latestCheckpoint)
        val rollbackSuccess = checkpointManager2.rollbackToCheckpoint("project-alpha", latestCheckpoint!!.id, workspaceDir)
        assertTrue(rollbackSuccess)
        assertEquals("fun main() = println(1)", srcFile.readText())
    }

    @Test
    fun `handles corrupted session file gracefully on restart without throwing`() {
        val sessionsDir = tempFolder.newFolder("corrupted_sessions")
        val activeFile = File(sessionsDir, "active_session.json")
        activeFile.writeText("{ corrupted_json_truncate... ")

        val manager = AgentSessionManager(sessionsDir)
        val detected = manager.detectInterruptedSession()
        assertNull("Corrupted json must fail safely and return null", detected)
    }


    @Test
    fun `terminal session remains recoverable when archive write fails`() {
        val sessionsDir = tempFolder.newFolder("archive_failure_sessions")
        val manager = AgentSessionManager(sessionsDir)
        manager.recordSessionStart(
            sessionId = "session-archive-retry",
            projectId = "project-archive",
            projectTitle = "Archive retry",
            chatId = "chat-archive",
            prompt = "Test archive failure",
        )

        // Block creation of the history directory to simulate storage/path failure.
        File(sessionsDir, "history").writeText("not-a-directory")
        manager.recordSessionCompleted("completed before archive failure")

        val activeFile = File(sessionsDir, "active_session.json")
        assertTrue("Active metadata must be retained if archive fails", activeFile.isFile)
        assertEquals("COMPLETED", JSONObject(activeFile.readText()).getString("status"))

        // Once storage is available again, a fresh manager retries the archive.
        File(sessionsDir, "history").delete()
        val restarted = AgentSessionManager(sessionsDir)
        assertNull(restarted.detectInterruptedSession())
        assertFalse(activeFile.exists())
        assertEquals(1, restarted.listSessionHistory().size)
        assertEquals(AgentSessionStatus.COMPLETED, restarted.listSessionHistory().single().status)
    }

    @Test
    fun `session IDs cannot escape the history directory`() {
        val sessionsDir = tempFolder.newFolder("unsafe_session_id")
        val manager = AgentSessionManager(sessionsDir)

        assertTrue(runCatching {
            manager.recordSessionStart(
                sessionId = "../outside",
                projectId = "p1",
                projectTitle = "Unsafe",
                chatId = "c1",
                prompt = "Must reject path traversal",
            )
        }.isFailure)
        assertFalse(File(tempFolder.root, "outside.json").exists())
    }

    @Test
    fun `session history symlink is rejected and active session remains recoverable`() {
        val sessionsDir = tempFolder.newFolder("symlinked_history_sessions")
        val outside = tempFolder.newFolder("symlinked_history_outside")
        val manager = AgentSessionManager(sessionsDir)
        manager.recordSessionStart(
            sessionId = "session-safe",
            projectId = "p1",
            projectTitle = "Safe session",
            chatId = "c1",
            prompt = "Retain active metadata",
        )
        java.nio.file.Files.createSymbolicLink(File(sessionsDir, "history").toPath(), outside.toPath())

        manager.recordSessionCompleted("finished")

        assertTrue(File(sessionsDir, "active_session.json").isFile)
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `clearing restored session deletes active session file cleanly`() {
        val sessionsDir = tempFolder.newFolder("cleanup_sessions")
        val manager1 = AgentSessionManager(sessionsDir)
        manager1.recordSessionStart(
            sessionId = "session-to-clear",
            projectId = "p1",
            projectTitle = "P1",
            chatId = "c1",
            prompt = "Quick task",
        )

        val manager2 = AgentSessionManager(sessionsDir)
        assertNotNull(manager2.detectInterruptedSession())

        manager2.clearActiveSession()
        assertNull(manager2.checkInterruptedSession())
    }
}
