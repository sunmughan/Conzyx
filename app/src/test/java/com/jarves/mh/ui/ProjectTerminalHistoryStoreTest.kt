package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectTerminalHistoryStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `terminal history survives a new store instance`() {
        val filesDir = tempFolder.newFolder("files")
        val guestRoot = "/workspace/demo"
        val store = ProjectTerminalHistoryStore(filesDir)
        val lines = listOf(
            TerminalOutputLine(id = "line-1", command = "pwd", output = guestRoot, exitCode = 0),
            TerminalOutputLine(id = "line-2", command = "git status", output = "clean", exitCode = 0),
        )

        assertTrue(store.save("project-1", guestRoot, guestRoot, lines))
        val restored = ProjectTerminalHistoryStore(filesDir).load("project-1", guestRoot)

        assertEquals(lines, restored.lines)
        assertEquals(guestRoot, restored.cwd)
    }

    @Test
    fun `terminal history rejects cwd outside project root`() {
        val filesDir = tempFolder.newFolder("cwd-files")
        val store = ProjectTerminalHistoryStore(filesDir)

        assertFalse(store.save("project-1", "/workspace/other", "/workspace/project-1", emptyList()))
        assertFalse(File(filesDir, "terminal-history/project-1.json").exists())
    }

    @Test
    fun `corrupt terminal history falls back to safe defaults`() {
        val filesDir = tempFolder.newFolder("corrupt-files")
        val history = File(filesDir, "terminal-history/project-1.json").apply {
            parentFile!!.mkdirs()
            writeText("{ broken")
        }

        val restored = ProjectTerminalHistoryStore(filesDir).load("project-1", "/workspace/project-1")

        assertTrue(history.isFile)
        assertTrue(restored.lines.isEmpty())
        assertEquals("/workspace/project-1", restored.cwd)
    }

    @Test
    fun `invalid project id cannot escape history directory`() {
        val store = ProjectTerminalHistoryStore(tempFolder.newFolder("safe-files"))
        assertFalse(runCatching {
            store.save("../outside", "/workspace/demo", "/workspace/demo", emptyList())
        }.isSuccess)
    }
}
