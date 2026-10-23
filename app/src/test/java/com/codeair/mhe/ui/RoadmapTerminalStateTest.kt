package com.codeair.mhe.ui

import com.codeair.mhe.model.ActiveRoadmap
import com.codeair.mhe.model.ChatMessage
import com.codeair.mhe.model.ExecutionMode
import com.codeair.mhe.model.RoadmapStep
import com.codeair.mhe.model.StepStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadmapTerminalStateTest {

    private val planText = """
        Here is the plan.

        ### Implementation Roadmap
        1. [ ] Create the module
           - Files: `src/Module.kt`
        2. [ ] Wire the UI
           - Files: `src/Ui.kt`

        Please review and click 'Approve & Build' when ready.
    """.trimIndent()

    private val summaryText = """
        All done. Here is what was delivered:

        1. **Created the module**: the core module is in place
        2. **Wired the UI**: the screens are connected

        Everything is verified and working.
    """.trimIndent()

    private fun roadmap(isApproved: Boolean, vararg statuses: StepStatus) = ActiveRoadmap(
        title = "Implementation Roadmap",
        summary = "",
        steps = statuses.mapIndexed { index, status -> RoadmapStep(title = "Step ${index + 1}", status = status) },
        isApproved = isApproved,
    )

    private fun user(text: String) = ChatMessage(fromUser = true, text = text)

    private fun assistant(text: String, roadmap: ActiveRoadmap? = null) =
        ChatMessage(fromUser = false, text = text, roadmap = roadmap)

    @Test
    fun `terminal means approved or fully completed`() {
        assertTrue(RoadmapParser.isTerminal(roadmap(true, StepStatus.PENDING)))
        assertTrue(RoadmapParser.isTerminal(roadmap(false, StepStatus.COMPLETED, StepStatus.COMPLETED)))
        assertFalse(RoadmapParser.isTerminal(roadmap(false, StepStatus.COMPLETED, StepStatus.PENDING)))
        assertFalse(RoadmapParser.isTerminal(roadmap(false, StepStatus.PENDING)))
    }

    @Test
    fun `finished task summary does not regain a roadmap on reload`() {
        val finished = roadmap(true, StepStatus.COMPLETED, StepStatus.COMPLETED)
        val messages = listOf(
            user("Build the thing"),
            assistant(planText, finished),
            assistant(summaryText),
        )
        val result = RoadmapParser.attachParsedRoadmaps(messages, ExecutionMode.UNIFIED)
        assertSame(finished, result[1].roadmap)
        assertNull(result[2].roadmap)
    }

    @Test
    fun `completed but unapproved roadmap also closes its segment`() {
        val completed = roadmap(false, StepStatus.COMPLETED, StepStatus.COMPLETED)
        val messages = listOf(
            user("Build the thing"),
            assistant(planText, completed),
            assistant(summaryText),
        )
        assertTrue(RoadmapParser.segmentHasTerminalRoadmap(messages, 2))
        val result = RoadmapParser.attachParsedRoadmaps(messages, ExecutionMode.UNIFIED)
        assertNull(result[2].roadmap)
    }

    @Test
    fun `awaiting approval plan still receives its roadmap`() {
        val messages = listOf(
            user("Build the thing"),
            assistant(planText),
        )
        assertFalse(RoadmapParser.segmentHasTerminalRoadmap(messages, 1))
        val result = RoadmapParser.attachParsedRoadmaps(messages, ExecutionMode.UNIFIED)
        assertNotNull(result[1].roadmap)
        assertFalse(result[1].roadmap!!.isApproved)
        assertEquals(2, result[1].roadmap!!.steps.size)
    }

    @Test
    fun `plan of a later task still receives its roadmap after an earlier task finished`() {
        val finished = roadmap(true, StepStatus.COMPLETED, StepStatus.COMPLETED)
        val messages = listOf(
            user("Build the thing"),
            assistant(planText, finished),
            assistant(summaryText),
            user("Now build another thing"),
            assistant(planText),
        )
        assertFalse(RoadmapParser.segmentHasTerminalRoadmap(messages, 4))
        val result = RoadmapParser.attachParsedRoadmaps(messages, ExecutionMode.UNIFIED)
        assertNull(result[2].roadmap)
        assertNotNull(result[4].roadmap)
        assertFalse(result[4].roadmap!!.isApproved)
    }

    @Test
    fun `roadmap actions stay enabled while its task is still open`() {
        val pending = roadmap(false, StepStatus.PENDING, StepStatus.PENDING)
        val messages = listOf(
            user("Build the thing"),
            assistant(planText, pending),
        )
        assertTrue(RoadmapParser.roadmapActionsEnabled(messages, 1, outcomeFinishedAtMillis = null))
    }

    @Test
    fun `roadmap actions die when a newer user turn closes the segment`() {
        val pending = roadmap(false, StepStatus.PENDING, StepStatus.PENDING)
        val messages = listOf(
            user("Build the thing"),
            assistant(planText, pending),
            assistant(summaryText),
            user("Now do another thing"),
        )
        assertFalse(RoadmapParser.roadmapActionsEnabled(messages, 1, outcomeFinishedAtMillis = null))
    }

    @Test
    fun `roadmap actions die once the producing task records an outcome`() {
        // The finished-task resurfacing bug: the task completed, but its
        // roadmap was never approved and its steps were not all marked
        // completed, so the card kept offering Approve & Build forever.
        val pending = roadmap(false, StepStatus.PENDING, StepStatus.PENDING)
        val planMessage = ChatMessage(
            fromUser = false,
            text = planText,
            createdAt = Instant.ofEpochMilli(1_000L),
            roadmap = pending,
        )
        val messages = listOf(user("Build the thing"), planMessage, assistant(summaryText))
        assertFalse(RoadmapParser.roadmapActionsEnabled(messages, 1, outcomeFinishedAtMillis = 2_000L))
        // An outcome from an EARLIER task must not disable a newer plan.
        assertTrue(RoadmapParser.roadmapActionsEnabled(messages, 1, outcomeFinishedAtMillis = 500L))
    }

    @Test
    fun `terminal roadmaps never offer actions`() {
        val approved = roadmap(true, StepStatus.PENDING)
        val completed = roadmap(false, StepStatus.COMPLETED, StepStatus.COMPLETED)
        val messages = listOf(
            user("Build the thing"),
            assistant(planText, approved),
            assistant(planText, completed),
        )
        assertFalse(RoadmapParser.roadmapActionsEnabled(messages, 1, outcomeFinishedAtMillis = null))
        assertFalse(RoadmapParser.roadmapActionsEnabled(messages, 2, outcomeFinishedAtMillis = null))
    }

    @Test
    fun `segment scan ignores pending roadmaps and stops at user boundaries`() {
        val pending = roadmap(false, StepStatus.PENDING, StepStatus.PENDING)
        val messages = listOf(
            user("Build the thing"),
            assistant(planText, pending),
            assistant(summaryText),
        )
        assertFalse(RoadmapParser.segmentHasTerminalRoadmap(messages, 1))
        assertFalse(RoadmapParser.segmentHasTerminalRoadmap(messages, 2))
    }
}
