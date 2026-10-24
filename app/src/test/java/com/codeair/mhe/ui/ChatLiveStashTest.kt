package com.codeair.mhe.ui

import com.codeair.mhe.model.ChatMessage
import com.codeair.mhe.model.Project
import com.codeair.mhe.model.ProjectChat
import com.codeair.mhe.session.EngineeringState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-chat concurrency (2026-10-24): per-chat live state must round-trip
 * through the stash without leaking into other chats or project-level state.
 */
class ChatLiveStashTest {

    private fun stateFor(
        messages: List<ChatMessage>,
        isRunning: Boolean = false,
        sessionId: String? = null,
        outcome: TaskOutcome? = null,
    ): AppUiState = AppUiState(
        messages = messages,
        isRunning = isRunning,
        activeSessionId = sessionId,
        lastTaskOutcome = outcome,
        liveProcess = emptyList(),
        activity = emptyList(),
    )

    @Test
    fun `stash preserves chat fields`() {
        val messages = listOf(
            ChatMessage(fromUser = true, text = "build it"),
            ChatMessage(fromUser = false, text = "working"),
        )
        val base = stateFor(messages, isRunning = true, sessionId = "s1")
        val slice = chatSliceFromState("p1", "chat-a", base)
        assertEquals("p1", slice.projectId)
        assertEquals("chat-a", slice.activeChatId)
        assertEquals(messages, slice.messages)
        assertTrue(slice.isRunning)
        assertEquals("s1", slice.activeSessionId)
    }

    @Test
    fun `restore does not touch project-level state`() {
        val project = Project(name = "Demo", description = "", language = "Kotlin")
        val chats = listOf(ProjectChat(title = "Main chat"), ProjectChat(title = "Second"))
        val base = AppUiState(activeProject = project, projectChats = chats)
        val slice = chatSliceFromState(
            "p1",
            "chat-a",
            stateFor(listOf(ChatMessage(fromUser = true, text = "hi"))),
        )
        val restored = applyChatSliceToState(base, slice, "chat-a")
        assertEquals(project, restored.activeProject)
        assertEquals(chats, restored.projectChats)
        assertEquals("chat-a", restored.activeChatId)
        assertEquals(1, restored.messages.size)
    }

    @Test
    fun `different chats stashes do not leak into each other`() {
        val sliceA = chatSliceFromState(
            "p1",
            "chat-a",
            stateFor(
                listOf(ChatMessage(fromUser = true, text = "task A")),
                isRunning = true,
                sessionId = "sA",
            ),
        )
        val sliceB = chatSliceFromState(
            "p1",
            "chat-b",
            stateFor(
                listOf(ChatMessage(fromUser = true, text = "task B")),
                isRunning = true,
                sessionId = "sB",
            ),
        )
        val base = AppUiState()
        val restoredB = applyChatSliceToState(base, sliceB, "chat-b")
        assertEquals("chat-b", restoredB.activeChatId)
        assertEquals("task B", restoredB.messages.single().text)
        assertEquals("sB", restoredB.activeSessionId)
        val restoredA = applyChatSliceToState(restoredB, sliceA, "chat-a")
        assertEquals("chat-a", restoredA.activeChatId)
        assertEquals("task A", restoredA.messages.single().text)
        assertEquals("sA", restoredA.activeSessionId)
    }

    @Test
    fun `one chats outcome does not appear in another chats restored state`() {
        val outcome = TaskOutcome(EngineeringState.COMPLETED, "Task A done", 123L)
        val sliceA = chatSliceFromState(
            "p1",
            "chat-a",
            stateFor(
                listOf(ChatMessage(fromUser = true, text = "task A")),
                outcome = outcome,
            ),
        )
        val sliceB = chatSliceFromState(
            "p1",
            "chat-b",
            stateFor(listOf(ChatMessage(fromUser = true, text = "task B"))),
        )
        val restoredB = applyChatSliceToState(AppUiState(), sliceB, "chat-b")
        assertNull(restoredB.lastTaskOutcome)
        val restoredA = applyChatSliceToState(restoredB, sliceA, "chat-a")
        assertEquals(outcome, restoredA.lastTaskOutcome)
    }
}
