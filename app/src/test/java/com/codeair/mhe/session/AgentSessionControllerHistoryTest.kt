package com.codeair.mhe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 5 — the command center's history flow.
 *
 * The controller's history must track real session outcomes (completed,
 * failed, cancelled) and stay newest-first, because the command center
 * renders it directly. No Android Context is needed: the testable
 * constructor takes the file-based manager straight.
 */
class AgentSessionControllerHistoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun controller(dirName: String): AgentSessionController =
        AgentSessionController(AgentSessionManager(tempFolder.newFolder(dirName)))

    @Test
    fun historyIsEmptyBeforeAnySessionFinishes() {
        val controller = controller("history_empty")
        assertTrue(controller.history.value.isEmpty())
    }

    @Test
    fun completedSessionAppearsInHistory() {
        val controller = controller("history_completed")
        controller.startSession("s-1", "p-1", "Project One", "c-1", "Build feature")
        controller.completeSession("s-1", "Done")
        val history = controller.history.value
        assertEquals(1, history.size)
        assertEquals("s-1", history[0].sessionId)
        assertEquals(AgentSessionStatus.COMPLETED, history[0].status)
        assertEquals("Done", history[0].summary)
    }

    @Test
    fun failedSessionAppearsNewestFirst() {
        val controller = controller("history_failed")
        controller.startSession("s-1", "p-1", "Project One", "c-1", "First task")
        controller.completeSession("s-1")
        controller.startSession("s-2", "p-1", "Project One", "c-1", "Second task")
        controller.failSession("s-2", "Network down")
        val history = controller.history.value
        assertEquals(2, history.size)
        assertEquals("s-2", history[0].sessionId)
        assertEquals(AgentSessionStatus.FAILED, history[0].status)
        assertEquals("s-1", history[1].sessionId)
    }

    @Test
    fun cancelledSessionAppearsAsInterrupted() {
        val controller = controller("history_cancelled")
        controller.startSession("s-1", "p-1", "Project One", "c-1", "Work in progress")
        controller.cancelSession("s-1")
        val history = controller.history.value
        assertEquals(1, history.size)
        assertEquals(AgentSessionStatus.INTERRUPTED, history[0].status)
    }
}
