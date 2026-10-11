package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceCheckpointsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `checkpoint preserves the first baseline while pending changes exist`() {
        val filesDir = tempFolder.newFolder("app-files")
        val workspace = tempFolder.newFolder("workspace")
        val source = File(workspace, "src/App.kt").apply {
            parentFile!!.mkdirs()
            writeText("fun main() = println(1)")
        }
        val checkpoints = WorkspaceCheckpoints(filesDir)

        checkpoints.createCheckpoint("project-1", workspace)
        checkpoints.saveChangedPaths("project-1", listOf("src/App.kt"))
        source.writeText("fun main() = println(2)")
        checkpoints.createCheckpoint("project-1", workspace)

        val baseline = File(checkpoints.checkpointDir("project-1"), "project/src/App.kt")
        assertEquals("fun main() = println(1)", baseline.readText())
        assertEquals(listOf("src/App.kt"), checkpoints.readChangedPaths("project-1"))
    }


    @Test
    fun `checkpoint recovers changed paths after a crash before manifest update`() {
        val filesDir = tempFolder.newFolder("crash-recovery-files")
        val workspace = tempFolder.newFolder("crash-recovery-workspace")
        val source = File(workspace, "src/App.kt").apply {
            parentFile!!.mkdirs()
            writeText("fun main() = println(1)")
        }
        val firstProcess = WorkspaceCheckpoints(filesDir)
        firstProcess.createCheckpoint("project-crash", workspace)
        assertTrue(File(firstProcess.checkpointDir("project-crash"), "changes.json").isFile)

        // Simulate an agent changing a file, then process death before saveChangedPaths().
        source.writeText("fun main() = println(2)")
        val restarted = WorkspaceCheckpoints(filesDir)
        restarted.createCheckpoint("project-crash", workspace)

        assertEquals(listOf("src/App.kt"), restarted.readChangedPaths("project-crash"))
        assertEquals(
            "fun main() = println(1)",
            File(restarted.checkpointDir("project-crash"), "project/src/App.kt").readText(),
        )
    }

    @Test
    fun `changed path manifest merges unique safe paths and persists across instances`() {
        val filesDir = tempFolder.newFolder("app-files")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        checkpoints.saveChangedPaths("project-1", listOf("src/App.kt", "src/App.kt", ".git/config", "build/out.bin"))
        checkpoints.saveChangedPaths("project-1", listOf("README.md", "src/App.kt"))

        val reloaded = WorkspaceCheckpoints(filesDir)
        assertEquals(listOf("README.md", "src/App.kt"), reloaded.readChangedPaths("project-1"))
    }

    @Test
    fun `removing final changed path clears checkpoint directory`() {
        val filesDir = tempFolder.newFolder("app-files")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        checkpoints.saveChangedPaths("project-1", listOf("src/App.kt"))

        checkpoints.removeChangedPath("project-1", "src/App.kt")

        assertTrue(checkpoints.readChangedPaths("project-1").isEmpty())
        assertFalse(checkpoints.checkpointDir("project-1").exists())
    }

    @Test
    fun `workspace path helper rejects traversal outside project root`() {
        val root = tempFolder.newFolder("workspace")
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder("app-files"))
        assertThrows(IllegalArgumentException::class.java) {
            checkpoints.safeWorkspaceFile(root, "../outside.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkpoints.ensureWorkspace("../outside")
        }
    }

    @Test
    fun `failed baseline rebuild preserves the last known-good snapshot`() {
        val filesDir = tempFolder.newFolder("transactional-checkpoint-files")
        val workspace = tempFolder.newFolder("transactional-checkpoint-workspace")
        val source = File(workspace, "src/App.kt").apply {
            parentFile!!.mkdirs()
            writeText("known-good")
        }
        val checkpoints = WorkspaceCheckpoints(filesDir)
        checkpoints.createCheckpoint("project-transaction", workspace)
        source.writeText("modified")
        val baseline = File(checkpoints.checkpointDir("project-transaction"), "project/src/App.kt")
        assertEquals("known-good", baseline.readText())

        assertThrows(IllegalArgumentException::class.java) {
            checkpoints.createCheckpoint("project-transaction", File(filesDir, "missing-workspace"))
        }

        assertEquals("known-good", baseline.readText())
        assertEquals("modified", source.readText())
        assertTrue(checkpoints.checkpointDir("project-transaction").isDirectory)
        assertTrue(checkpoints.checkpointDir("project-transaction").parentFile
            .listFiles().orEmpty().none { it.name.contains(".staging_") || it.name.contains(".previous_") })
    }

    @Test
    fun `workspace path helper rejects symlink escape`() {
        val root = tempFolder.newFolder("symlink-workspace")
        val outside = tempFolder.newFolder("symlink-outside")
        File(outside, "secret.txt").writeText("outside")
        java.nio.file.Files.createSymbolicLink(
            File(root, "linked").toPath(),
            outside.toPath(),
        )
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder("symlink-files"))

        assertThrows(IllegalArgumentException::class.java) {
            checkpoints.safeWorkspaceFile(root, "linked/secret.txt")
        }
    }

    @Test
    fun `checkpoint storage rejects symlinked project and latest directories`() {
        val filesDir = tempFolder.newFolder("checkpoint-storage-symlinks")
        val checkpointsRoot = File(filesDir, "checkpoints").apply { mkdirs() }
        val outside = tempFolder.newFolder("checkpoint-storage-outside")
        java.nio.file.Files.createSymbolicLink(
            File(checkpointsRoot, "project-link").toPath(),
            outside.toPath(),
        )
        val manager = WorkspaceCheckpoints(filesDir)

        assertThrows(IllegalArgumentException::class.java) {
            manager.checkpointDir("project-link")
        }

        val projectRoot = File(checkpointsRoot, "project-latest").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(projectRoot, "latest").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            manager.checkpointDir("project-latest")
        }
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `snapshot ignores symlinked files and directories`() {
        val root = tempFolder.newFolder("snapshot-symlink-root")
        val outside = tempFolder.newFolder("snapshot-symlink-outside")
        File(outside, "secret.txt").writeText("do not hash outside content")
        File(root, "normal.txt").writeText("safe")
        java.nio.file.Files.createSymbolicLink(
            File(root, "linked-file.txt").toPath(),
            File(outside, "secret.txt").toPath(),
        )
        java.nio.file.Files.createSymbolicLink(
            File(root, "linked-dir").toPath(),
            outside.toPath(),
        )

        val snapshot = WorkspaceCheckpoints(tempFolder.newFolder("snapshot-files")).snapshot(root)

        assertEquals(setOf("normal.txt"), snapshot.keys)
    }

    @Test
    fun `ensureWorkspace rejects project directory symlinks escaping app storage`() {
        val filesDir = tempFolder.newFolder("workspace-storage-symlink")
        val outside = tempFolder.newFolder("workspace-storage-outside")
        File(outside, "keep.txt").writeText("untouched")
        val workspaces = File(filesDir, "workspaces").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(workspaces, "project-link").toPath(), outside.toPath())
        val checkpoints = WorkspaceCheckpoints(filesDir)

        assertThrows(IllegalArgumentException::class.java) {
            checkpoints.ensureWorkspace("project-link")
        }
        assertEquals("untouched", File(outside, "keep.txt").readText())
        assertFalse(File(outside, "linked-file").exists())
    }
}
