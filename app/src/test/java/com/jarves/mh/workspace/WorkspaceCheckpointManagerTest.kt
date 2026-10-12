package com.jarves.mh.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceCheckpointManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCreateCheckpointAndList() {
        val filesDir = tempFolder.newFolder("filesDir")
        val workspaceDir = tempFolder.newFolder("workspace")

        val file1 = File(workspaceDir, "src/Main.kt").apply {
            parentFile?.mkdirs()
            writeText("fun main() { println(\"Hello\") }")
        }
        val file2 = File(workspaceDir, "README.md").apply {
            writeText("# My Project")
        }

        val manager = WorkspaceCheckpointManager(filesDir)
        val checkpoint = manager.createCheckpoint("proj-1", workspaceDir, "Initial setup")

        assertNotNull(checkpoint)
        assertEquals("Initial setup", checkpoint?.label)
        assertEquals(2, checkpoint?.fileCount)

        val list = manager.listCheckpoints("proj-1")
        assertEquals(1, list.size)
        assertEquals("Initial setup", list[0].label)
    }

    @Test
    fun testRollbackToCheckpoint() {
        val filesDir = tempFolder.newFolder("filesDir2")
        val workspaceDir = tempFolder.newFolder("workspace2")

        val targetFile = File(workspaceDir, "config.json").apply {
            writeText("""{"version": "1.0"}""")
        }

        val manager = WorkspaceCheckpointManager(filesDir)
        val ckpt = manager.createCheckpoint("proj-2", workspaceDir, "v1.0 baseline")
        assertNotNull(ckpt)

        // Modify the file and add a post-checkpoint file.
        targetFile.writeText("""{"version": "2.0-corrupted"}""")
        val addedFile = File(workspaceDir, "new-file.txt").apply { writeText("new data") }
        assertEquals("""{"version": "2.0-corrupted"}""", targetFile.readText())

        // Rollback
        val success = manager.rollbackToCheckpoint("proj-2", ckpt!!.id, workspaceDir)
        assertTrue(success)

        // Verify restoration
        assertEquals("""{"version": "1.0"}""", targetFile.readText())
        assertTrue("Files created after the checkpoint should be removed", !addedFile.exists())
    }


    @Test
    fun testRollbackRejectsCorruptedCheckpointWithoutDeletingWorkspaceFiles() {
        val filesDir = tempFolder.newFolder("corruptCheckpointFiles")
        val workspaceDir = tempFolder.newFolder("corruptCheckpointWorkspace")
        val first = File(workspaceDir, "first.txt").apply { writeText("baseline one") }
        File(workspaceDir, "second.txt").writeText("baseline two")

        val manager = WorkspaceCheckpointManager(filesDir)
        val checkpoint = manager.createCheckpoint("proj-corrupt", workspaceDir, "baseline")
        assertNotNull(checkpoint)

        first.writeText("important current data")
        val backupDir = File(filesDir, "checkpoints/proj-corrupt/snapshots/${checkpoint!!.id}/files")
        assertTrue(File(backupDir, "second.txt").delete())
        assertFalse(manager.verifyCheckpointIntegrity("proj-corrupt", checkpoint.id))

        val success = manager.rollbackToCheckpoint("proj-corrupt", checkpoint.id, workspaceDir)

        assertFalse(success)
        assertEquals("important current data", first.readText())
        assertEquals("baseline two", File(workspaceDir, "second.txt").readText())
    }

    @Test
    fun testRollbackRejectsSameCountCheckpointContentTampering() {
        val filesDir = tempFolder.newFolder("hashCheckpointFiles")
        val workspaceDir = tempFolder.newFolder("hashCheckpointWorkspace")
        val current = File(workspaceDir, "config.json").apply { writeText("baseline") }
        val manager = WorkspaceCheckpointManager(filesDir)
        val checkpoint = manager.createCheckpoint("proj-hash", workspaceDir, "hash baseline")
        assertNotNull(checkpoint)

        current.writeText("current data")
        val backupFile = File(filesDir, "checkpoints/proj-hash/snapshots/${checkpoint!!.id}/files/config.json")
        backupFile.writeText("tampered") // Same file count, different content.

        assertFalse(manager.verifyCheckpointIntegrity("proj-hash", checkpoint.id))
        assertFalse(manager.rollbackToCheckpoint("proj-hash", checkpoint.id, workspaceDir))
        assertEquals("current data", current.readText())
    }

    @Test
    fun testRollbackRejectsTraversalCheckpointIdWithoutTouchingWorkspace() {
        val filesDir = tempFolder.newFolder("traversalCheckpointFiles")
        val workspaceDir = tempFolder.newFolder("traversalCheckpointWorkspace")
        val current = File(workspaceDir, "keep.txt").apply { writeText("keep me") }
        val manager = WorkspaceCheckpointManager(filesDir)

        assertFalse(manager.rollbackToCheckpoint("proj-safe", "../../traversalCheckpointWorkspace", workspaceDir))
        assertEquals("keep me", current.readText())
    }

    @Test
    fun testWriteSafely() {
        val filesDir = tempFolder.newFolder("filesDir3")
        val target = File(filesDir, "atomic_file.txt")

        val manager = WorkspaceCheckpointManager(filesDir)
        val ok = manager.writeSafely(target, "Safe atomic content")

        assertTrue(ok)
        assertTrue(target.exists())
        assertEquals("Safe atomic content", target.readText())
    }

    @Test
    fun testBackupAndRestoreWorkspaces() {
        val filesDir = tempFolder.newFolder("filesDir4")
        val externalDir = tempFolder.newFolder("externalBackups")
        val workspacesDir = File(filesDir, "workspaces/proj-abc").apply { mkdirs() }
        File(workspacesDir, "index.js").writeText("console.log('hello world');")

        val manager = WorkspaceCheckpointManager(filesDir)
        val backupCount = manager.backupAllWorkspaces(externalDir)
        assertEquals(1, backupCount)
        assertTrue(File(externalDir, "workspaces_mirror/proj-abc/index.js").exists())

        // Wipe internal workspaces
        workspacesDir.deleteRecursively()
        assertEquals(false, File(filesDir, "workspaces/proj-abc/index.js").exists())

        // Restore
        val restoreCount = manager.restoreAllWorkspaces(externalDir)
        assertEquals(1, restoreCount)
        assertTrue(File(filesDir, "workspaces/proj-abc/index.js").exists())
        assertEquals("console.log('hello world');", File(filesDir, "workspaces/proj-abc/index.js").readText())
    }

    @Test
    fun `workspace restore overwrites stale existing files from backup`() {
        val filesDir = tempFolder.newFolder("restore-overwrite-files")
        val externalDir = tempFolder.newFolder("restore-overwrite-external")
        val project = File(filesDir, "workspaces/project-restore")
        val file = File(project, "src/config.json").apply {
            parentFile?.mkdirs()
            writeText("""{"version":1}""")
        }
        val manager = WorkspaceCheckpointManager(filesDir)

        assertEquals(1, manager.backupAllWorkspaces(externalDir))
        file.writeText("""{"version":2}""")

        assertEquals(1, manager.restoreAllWorkspaces(externalDir))
        assertEquals("""{"version":1}""", file.readText())
    }

    @Test
    fun `checkpoint storage rejects a symlinked root`() {
        val filesDir = tempFolder.newFolder("symlink-checkpoint-storage")
        val outside = tempFolder.newFolder("symlink-checkpoint-outside")
        val workspace = tempFolder.newFolder("symlink-checkpoint-workspace")
        File(workspace, "keep.txt").writeText("workspace data")
        java.nio.file.Files.createSymbolicLink(
            File(filesDir, "checkpoints").toPath(),
            outside.toPath(),
        )

        val manager = WorkspaceCheckpointManager(filesDir)

        assertEquals(null, manager.createCheckpoint("project", workspace, "baseline"))
        assertTrue("Symlink target must remain untouched", outside.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `rollback rejects symlink escape before changing any workspace file`() {
        val filesDir = tempFolder.newFolder("symlink-rollback-files")
        val workspace = tempFolder.newFolder("symlink-rollback-workspace")
        val current = File(workspace, "a.txt").apply { writeText("baseline") }
        val linkedDir = File(workspace, "linked").apply { mkdirs() }
        File(linkedDir, "inside.txt").writeText("baseline inside")
        val outside = tempFolder.newFolder("symlink-rollback-outside")
        File(outside, "inside.txt").writeText("outside must remain unchanged")

        val manager = WorkspaceCheckpointManager(filesDir)
        val checkpoint = manager.createCheckpoint("project-symlink", workspace, "baseline")
        assertNotNull(checkpoint)

        current.writeText("current user data")
        assertTrue(linkedDir.deleteRecursively())
        java.nio.file.Files.createSymbolicLink(linkedDir.toPath(), outside.toPath())

        assertFalse(manager.rollbackToCheckpoint("project-symlink", checkpoint!!.id, workspace))
        assertEquals("current user data", current.readText())
        assertEquals("outside must remain unchanged", File(outside, "inside.txt").readText())
    }

    @Test
    fun `checkpoint creation ignores symlinks that escape the workspace`() {
        val filesDir = tempFolder.newFolder("symlink-source-files")
        val workspace = tempFolder.newFolder("symlink-source-workspace")
        val outside = tempFolder.newFolder("symlink-source-outside")
        File(workspace, "safe.txt").writeText("safe")
        File(outside, "secret.txt").writeText("outside data")
        java.nio.file.Files.createSymbolicLink(
            File(workspace, "linked-file.txt").toPath(),
            File(outside, "secret.txt").toPath(),
        )
        java.nio.file.Files.createSymbolicLink(
            File(workspace, "linked-directory").toPath(),
            outside.toPath(),
        )

        val manager = WorkspaceCheckpointManager(filesDir)
        val checkpoint = manager.createCheckpoint("symlink-source", workspace, "safe baseline")

        assertNotNull(checkpoint)
        assertEquals(1, checkpoint!!.fileCount)
        val snapshot = File(filesDir, "checkpoints/symlink-source/snapshots/${checkpoint.id}/files")
        assertEquals(setOf("safe.txt"), snapshot.walkTopDown().filter { it.isFile }.map { it.name }.toSet())
        assertEquals("outside data", File(outside, "secret.txt").readText())
    }

    @Test
    fun `rollback cleanup does not traverse symlinked directories added after checkpoint`() {
        val filesDir = tempFolder.newFolder("symlink-cleanup-files")
        val workspace = tempFolder.newFolder("symlink-cleanup-workspace")
        val original = File(workspace, "original.txt").apply { writeText("baseline") }
        val outside = tempFolder.newFolder("symlink-cleanup-outside")
        val outsideFile = File(outside, "keep.txt").apply { writeText("outside data") }

        val manager = WorkspaceCheckpointManager(filesDir)
        val checkpoint = manager.createCheckpoint("symlink-cleanup", workspace, "baseline")
        assertNotNull(checkpoint)

        original.writeText("changed")
        java.nio.file.Files.createSymbolicLink(File(workspace, "linked-outside").toPath(), outside.toPath())

        assertTrue(manager.rollbackToCheckpoint("symlink-cleanup", checkpoint!!.id, workspace))
        assertEquals("baseline", original.readText())
        assertEquals("outside data", outsideFile.readText())
    }

    @Test
    fun `new checkpoint creation removes only abandoned staging directories older than one day`() {
        val filesDir = tempFolder.newFolder("stale-staging-files")
        val workspace = tempFolder.newFolder("stale-staging-workspace")
        File(workspace, "safe.txt").writeText("safe")
        val snapshots = File(filesDir, "checkpoints/project-stale/snapshots").apply { mkdirs() }
        val stale = File(snapshots, ".ckpt_old.staging_abandoned").apply {
            mkdirs()
            File(this, "partial.txt").writeText("partial")
            setLastModified(System.currentTimeMillis() - 48L * 60L * 60L * 1000L)
        }
        val recent = File(snapshots, ".ckpt_recent.staging_active").apply {
            mkdirs()
            File(this, "partial.txt").writeText("keep until stale")
            setLastModified(System.currentTimeMillis())
        }

        val manager = WorkspaceCheckpointManager(filesDir)
        assertNotNull(manager.createCheckpoint("project-stale", workspace, "new baseline"))

        assertFalse(stale.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `failed checkpoint creation leaves no partial snapshot behind`() {
        val filesDir = tempFolder.newFolder("failed-checkpoint-files")
        val manager = WorkspaceCheckpointManager(filesDir)
        val unavailableWorkspace = File(tempFolder.root, "workspace-that-does-not-exist")

        assertEquals(null, manager.createCheckpoint("failed-project", unavailableWorkspace, "should fail"))
        val snapshots = File(filesDir, "checkpoints/failed-project/snapshots")
        assertTrue(!snapshots.exists() || snapshots.listFiles().orEmpty().isEmpty())
        assertTrue(manager.listCheckpoints("failed-project").isEmpty())
    }

}

