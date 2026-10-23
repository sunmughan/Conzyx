package com.codeair.mhe.ui

import com.codeair.mhe.session.EngineeringState
import com.codeair.mhe.session.EngineeringStepEvent
import com.codeair.mhe.session.OrchestratorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerSnapshotTest {

    private fun liveSnapshot(
        state: EngineeringState,
        history: List<EngineeringStepEvent> = emptyList(),
        goal: String? = null,
    ) = OrchestratorSnapshot(
        state = state,
        currentIteration = 1,
        maxIterations = 3,
        planSteps = emptyList(),
        currentStepIndex = 0,
        history = history,
        currentTaskGoal = goal,
    )

    private fun completedEvent(atMillis: Long, goal: String? = "Task completed successfully") =
        EngineeringStepEvent(
            state = EngineeringState.COMPLETED,
            stepTitle = "Task completed successfully",
            timestampMillis = atMillis,
        )

    private val completedOutcome = TaskOutcome(
        state = EngineeringState.COMPLETED,
        title = "Task completed successfully",
        finishedAtMillis = 1234L,
    )

    @Test
    fun `live working state wins over a stored outcome`() {
        val live = liveSnapshot(EngineeringState.IMPLEMENTING)
        assertSame(live, bannerSnapshotFor(live, completedOutcome))
    }

    @Test
    fun `stored outcome wins over a latched live terminal snapshot`() {
        // The orchestrator snapshot stays latched at COMPLETED after a task
        // finishes; the banner must still be the outcome event (the source
        // the dismissal is keyed to), not the raw latched snapshot.
        val live = liveSnapshot(
            EngineeringState.COMPLETED,
            history = listOf(completedEvent(1234L)),
        )
        val banner = bannerSnapshotFor(live, completedOutcome, taskFinishedAtMillis = 1234L)
        assertEquals(EngineeringState.COMPLETED, banner?.state)
        assertEquals("Task completed successfully", banner?.currentTaskGoal)
    }

    @Test
    fun `latched live terminal snapshot shows when no outcome was stored`() {
        val live = liveSnapshot(
            EngineeringState.COMPLETED,
            history = listOf(completedEvent(1234L)),
        )
        assertSame(live, bannerSnapshotFor(live, null, taskFinishedAtMillis = 1234L))
    }

    @Test
    fun `acknowledged completion never resurfaces through the latched live snapshot`() {
        // The exact v3.4.9 device bug: the outcome is dismissed, but the
        // orchestrator snapshot remains latched at COMPLETED and the banner
        // re-renders from it forever. Dismissal acknowledges every event key,
        // so after dismissal neither source may produce the banner again.
        val live = liveSnapshot(
            EngineeringState.COMPLETED,
            history = listOf(completedEvent(1234L)),
        )
        val keys = completionEventKeys(live, completedOutcome, taskFinishedAtMillis = 1234L)
        assertTrue(keys.isNotEmpty())
        // Before dismissal the banner shows (as the outcome).
        assertEquals(
            EngineeringState.COMPLETED,
            bannerSnapshotFor(live, completedOutcome, 1234L, emptySet())?.state,
        )
        // After dismissal the outcome is cleared (null) — the latched live
        // snapshot must not bring the banner back.
        assertNull(bannerSnapshotFor(live, null, 1234L, acknowledgedEventKeys = keys))
        assertNull(bannerSnapshotFor(live, completedOutcome, 1234L, acknowledgedEventKeys = keys))
    }

    @Test
    fun `acknowledged outcome with idle live snapshot renders nothing`() {
        val keys = completionEventKeys(null, completedOutcome, taskFinishedAtMillis = 1234L)
        val banner = bannerSnapshotFor(
            liveSnapshot(EngineeringState.IDLE),
            completedOutcome,
            1234L,
            acknowledgedEventKeys = keys,
        )
        // The acknowledged completion must not render: the result is either
        // no snapshot or the IDLE snapshot, both of which the banner UI hides.
        assertTrue(banner == null || banner.state == EngineeringState.IDLE)
    }

    @Test
    fun `a later completion shows even after an earlier one was acknowledged`() {
        val acknowledged = completionEventKeys(null, completedOutcome, taskFinishedAtMillis = 1234L)
        val later = TaskOutcome(EngineeringState.COMPLETED, "Task completed successfully", 9876L)
        val banner = bannerSnapshotFor(null, later, 9876L, acknowledgedEventKeys = acknowledged)
        assertEquals(EngineeringState.COMPLETED, banner?.state)
    }

    @Test
    fun `no completion event keys while a task is working`() {
        val live = liveSnapshot(EngineeringState.IMPLEMENTING)
        assertTrue(completionEventKeys(live, completedOutcome, 1234L).isEmpty())
        assertTrue(completionEventKeys(null, null, null).isEmpty())
    }

    @Test
    fun `stored outcome shows when the live snapshot went idle`() {
        val banner = bannerSnapshotFor(liveSnapshot(EngineeringState.IDLE), completedOutcome)
        assertEquals(EngineeringState.COMPLETED, banner?.state)
        assertEquals("Task completed successfully", banner?.currentTaskGoal)
    }

    @Test
    fun `stored outcome shows when the live snapshot is analyzing the next turn`() {
        val banner = bannerSnapshotFor(liveSnapshot(EngineeringState.ANALYZING), completedOutcome)
        assertEquals(EngineeringState.COMPLETED, banner?.state)
    }

    @Test
    fun `stored failed outcome renders the failed state`() {
        val failed = TaskOutcome(EngineeringState.FAILED, "Task failed", 99L)
        val banner = bannerSnapshotFor(null, failed)
        assertEquals(EngineeringState.FAILED, banner?.state)
    }

    @Test
    fun `no outcome and no live state renders nothing`() {
        assertNull(bannerSnapshotFor(null, null))
        assertEquals(
            EngineeringState.IDLE,
            bannerSnapshotFor(liveSnapshot(EngineeringState.IDLE), null)?.state,
        )
    }
}
