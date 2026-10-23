package com.codeair.mhe.ui

import com.codeair.mhe.session.EngineeringState
import com.codeair.mhe.session.OrchestratorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BannerSnapshotTest {

    private fun liveSnapshot(state: EngineeringState) = OrchestratorSnapshot(
        state = state,
        currentIteration = 1,
        maxIterations = 3,
        planSteps = emptyList(),
        currentStepIndex = 0,
        history = emptyList(),
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
    fun `live terminal state wins over a stored outcome`() {
        val live = liveSnapshot(EngineeringState.FAILED)
        assertSame(live, bannerSnapshotFor(live, completedOutcome))
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
