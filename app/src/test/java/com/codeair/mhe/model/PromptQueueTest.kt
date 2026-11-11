package com.codeair.mhe.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 prompt queue (2026-11-11): pure queue state machine for the per-chat
 * prompt queue. The queue is the data shape everything else hangs off, so
 * its rules are pinned here before any ViewModel/UI code touches them.
 */
class PromptQueueTest {

    private fun prompt(text: String) = QueuedPrompt(id = "q-$text", text = text)

    @Test
    fun `enqueue appends in order and keeps existing entries`() {
        val queue = enqueuePrompt(emptyList(), prompt("first"))
        val grown = enqueuePrompt(queue, prompt("second"))
        assertEquals(listOf("first", "second"), grown.map { it.text })
    }

    @Test
    fun `dequeue returns the head and the rest in order`() {
        val queue = listOf(prompt("first"), prompt("second"), prompt("third"))
        val (head, rest) = dequeuePrompt(queue)
        assertEquals("first", head?.text)
        assertEquals(listOf("second", "third"), rest.map { it.text })
    }

    @Test
    fun `dequeue on an empty queue returns null and an empty list`() {
        val (head, rest) = dequeuePrompt(emptyList())
        assertNull(head)
        assertTrue(rest.isEmpty())
    }

    @Test
    fun `remove drops only the matching id`() {
        val queue = listOf(prompt("first"), prompt("second"), prompt("third"))
        val kept = removeQueuedPrompt(queue, "q-second")
        assertEquals(listOf("first", "third"), kept.map { it.text })
    }

    @Test
    fun `remove with an unknown id leaves the queue untouched`() {
        val queue = listOf(prompt("first"), prompt("second"))
        assertEquals(queue, removeQueuedPrompt(queue, "q-nope"))
    }

    @Test
    fun `move reorders to the target index`() {
        val queue = listOf(prompt("first"), prompt("second"), prompt("third"))
        val moved = moveQueuedPrompt(queue, "q-first", 2)
        assertEquals(listOf("second", "third", "first"), moved.map { it.text })
        val movedBack = moveQueuedPrompt(moved, "q-first", 0)
        assertEquals(listOf("first", "second", "third"), movedBack.map { it.text })
    }

    @Test
    fun `move clamps out of range targets`() {
        val queue = listOf(prompt("first"), prompt("second"))
        assertEquals(listOf("second", "first"), moveQueuedPrompt(queue, "q-first", 99).map { it.text })
        assertEquals(listOf("second", "first"), moveQueuedPrompt(queue, "q-second", -5).map { it.text })
    }

    @Test
    fun `move with an unknown id leaves the queue untouched`() {
        val queue = listOf(prompt("first"), prompt("second"))
        assertEquals(queue, moveQueuedPrompt(queue, "q-nope", 0))
    }

    @Test
    fun `drain gate opens only on a clean finish with a waiting prompt`() {
        assertTrue(shouldDrainPromptQueue(isRunning = false, hasCompletedOutcome = true, hasPendingApproval = false, canResume = false, queue = listOf(prompt("next"))))
    }

    @Test
    fun `drain gate stays shut while running`() {
        assertFalse(shouldDrainPromptQueue(isRunning = true, hasCompletedOutcome = false, hasPendingApproval = false, canResume = false, queue = listOf(prompt("next"))))
    }

    @Test
    fun `drain gate stays shut without a completed outcome`() {
        // A failed or user-stopped task never auto-starts the next prompt:
        // the user decides what happens next.
        assertFalse(shouldDrainPromptQueue(isRunning = false, hasCompletedOutcome = false, hasPendingApproval = false, canResume = false, queue = listOf(prompt("next"))))
    }

    @Test
    fun `drain gate stays shut with a pending approval`() {
        assertFalse(shouldDrainPromptQueue(isRunning = false, hasCompletedOutcome = true, hasPendingApproval = true, canResume = false, queue = listOf(prompt("next"))))
    }

    @Test
    fun `drain gate stays shut while a resume is possible`() {
        assertFalse(shouldDrainPromptQueue(isRunning = false, hasCompletedOutcome = true, hasPendingApproval = false, canResume = true, queue = listOf(prompt("next"))))
    }

    @Test
    fun `drain gate stays shut on an empty queue`() {
        assertFalse(shouldDrainPromptQueue(isRunning = false, hasCompletedOutcome = true, hasPendingApproval = false, canResume = false, queue = emptyList()))
    }
}
