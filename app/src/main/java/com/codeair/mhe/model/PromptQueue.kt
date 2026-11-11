package com.codeair.mhe.model

import java.util.UUID

/**
 * P1 prompt queue (2026-11-11): one queued follow-up prompt in a chat.
 *
 * The queue is per-chat, never global: a prompt queued in chat A starts only
 * in chat A, after chat A's own running task finishes cleanly. The old
 * global sessionStartQueue was deliberately removed in Oct 2026 and must
 * never come back. Attachments travel with the prompt (staged while the
 * task runs, consumed when the prompt starts). [mode] is captured at queue
 * time so a mode change mid-run does not rewrite what the user asked for.
 */
data class QueuedPrompt(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val attachments: List<ChatAttachment> = emptyList(),
    val mode: ExecutionMode = ExecutionMode.UNIFIED,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

/** Append a prompt to the end of the queue. */
fun enqueuePrompt(queue: List<QueuedPrompt>, prompt: QueuedPrompt): List<QueuedPrompt> =
    queue + prompt

/** Take the head of the queue (the next prompt to start) and the rest. */
fun dequeuePrompt(queue: List<QueuedPrompt>): Pair<QueuedPrompt?, List<QueuedPrompt>> =
    if (queue.isEmpty()) null to emptyList() else queue.first() to queue.drop(1)

/** Drop the queued prompt with this id; unknown ids leave the queue alone. */
fun removeQueuedPrompt(queue: List<QueuedPrompt>, promptId: String): List<QueuedPrompt> =
    queue.filterNot { it.id == promptId }

/**
 * Move the queued prompt with this id to [toIndex] (clamped into range).
 * Unknown ids and no-op moves leave the queue alone.
 */
fun moveQueuedPrompt(queue: List<QueuedPrompt>, promptId: String, toIndex: Int): List<QueuedPrompt> {
    val fromIndex = queue.indexOfFirst { it.id == promptId }
    if (fromIndex < 0) return queue
    val target = toIndex.coerceIn(0, queue.lastIndex)
    if (target == fromIndex) return queue
    val mutable = queue.toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(target, item)
    return mutable.toList()
}

/**
 * The drain gate: a queued prompt may start only on a CLEAN finish.
 *
 * AntiGravity shipped this exact bug in 2.30.0 (Nov 9, 2026): a queued
 * follow-up fired early and started a new turn while subagents or background
 * tasks from the previous turn were still running. So the gate is the
 * finished state itself, not the finish event: nothing running, a COMPLETED
 * outcome latched (a failed or user-stopped task never auto-starts the next
 * prompt), no approval waiting on the user, no resume pending, and a prompt
 * actually waiting.
 */
fun shouldDrainPromptQueue(
    isRunning: Boolean,
    hasCompletedOutcome: Boolean,
    hasPendingApproval: Boolean,
    canResume: Boolean,
    queue: List<QueuedPrompt>,
): Boolean =
    !isRunning && hasCompletedOutcome && !hasPendingApproval && !canResume && queue.isNotEmpty()
