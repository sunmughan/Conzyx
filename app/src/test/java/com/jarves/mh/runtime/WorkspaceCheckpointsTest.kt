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
    }
}
