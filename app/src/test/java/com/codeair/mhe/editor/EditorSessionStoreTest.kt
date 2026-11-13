package com.codeair.mhe.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 5: the offline-first flush (EditorCoordinator.flushSessionPersist)
 * relies on this store round-tripping a dirty-buffer session exactly, so
 * a backgrounded app restores tabs, unsaved edits, cursor and split state.
 */
class EditorSessionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `dirty buffer session survives a store round trip`() {
        val store = EditorSessionStore(tempFolder.newFolder("sessions"))
        val session = EditorSession(
            projectId = "proj-1",
            tabs = listOf("Main.kt", "README.md"),
            activePath = "Main.kt",
            buffers = listOf(
                EditorBuffer(
                    path = "Main.kt",
                    currentContent = "fun main() {} // edited",
                    savedContent = "fun main() {}",
                    viewState = EditorViewState(cursorOffset = 12, selectionEnd = 12, scrollLine = 3),
                    lastModifiedAt = 123L,
                ),
            ),
            viewStates = mapOf("README.md" to EditorViewState(cursorOffset = 5, scrollLine = 1)),
            splitState = EditorSplitState(secondaryPath = "README.md", vertical = false),
        )
        store.saveSession(session)

        val loaded = store.loadSession("proj-1")
        assertTrue(loaded != null)
        loaded!!
        assertEquals(listOf("Main.kt", "README.md"), loaded.tabs)
        assertEquals("Main.kt", loaded.activePath)
        assertEquals(1, loaded.buffers.size)
        assertEquals("fun main() {} // edited", loaded.buffers[0].currentContent)
        assertEquals("fun main() {}", loaded.buffers[0].savedContent)
        assertTrue(loaded.buffers[0].isDirty)
        assertEquals(12, loaded.buffers[0].viewState.cursorOffset)
        assertEquals(3, loaded.buffers[0].viewState.scrollLine)
        assertEquals(123L, loaded.buffers[0].lastModifiedAt)
        assertEquals(5, loaded.viewStates["README.md"]?.cursorOffset)
        assertEquals(1, loaded.viewStates["README.md"]?.scrollLine)
        assertEquals("README.md", loaded.splitState.secondaryPath)
        assertEquals(false, loaded.splitState.vertical)
    }

    @Test
    fun `unknown project loads as null and clear drops the session`() {
        val store = EditorSessionStore(tempFolder.newFolder("sessions"))
        assertNull(store.loadSession("nope"))
        store.saveSession(EditorSession(projectId = "proj-1", tabs = listOf("Main.kt")))
        assertTrue(store.loadSession("proj-1") != null)
        store.clearSession("proj-1")
        assertNull(store.loadSession("proj-1"))
    }

    @Test
    fun `corrupt session file loads as null`() {
        val dir = tempFolder.newFolder("sessions")
        File(dir, "proj-2.json").writeText("not json at all")
        val store = EditorSessionStore(dir)
        assertNull(store.loadSession("proj-2"))
    }
}
