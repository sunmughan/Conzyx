package com.jarves.mh.agent.team

import com.jarves.mh.agent.team.model.ArtifactType
import com.jarves.mh.agent.team.model.ReviewVerdict
import com.jarves.mh.agent.team.model.TeamRole
import com.jarves.mh.agent.team.model.TeamTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentTeamOrchestratorTest {

    private lateinit var orchestrator: AgentTeamOrchestrator

    @Before
    fun setUp() {
        orchestrator = AgentTeamOrchestrator()
    }

    @Test
    fun testPlanProjectDecomposesIntoTenSpecializedTasks() {
        val goal = "Implement offline SQLite database layer with UI and tests"
        val tasks = orchestrator.planProject(goal)

        assertEquals(10, tasks.size)
        val sessionState = orchestrator.sessionState.value

        assertEquals(goal, sessionState.activeGoal)
        assertTrue(sessionState.isRunning)
        assertEquals(TeamRole.MANAGER, sessionState.activeRole)

        // All 10 roles should have tasks assigned
        val assignedRoles = tasks.map { it.assignedRole }.toSet()
        assertEquals(10, assignedRoles.size)

        // Verify DAG has no cycles
        assertFalse(orchestrator.scheduler.hasCycle())

        // Blackboard should contain initial blueprint
        val artifacts = orchestrator.blackboard.getAllArtifacts()
        assertTrue(artifacts.any { it.type == ArtifactType.ADR })

        // Comm bus should have kickoff broadcast
        val messages = orchestrator.commBus.getMessages()
        assertTrue(messages.any { it.fromRole == TeamRole.MANAGER && it.content.contains(goal) })
    }

    @Test
    fun testRunNextReadyTaskProgression() {
        orchestrator.planProject("Build sample feature")

        // First ready task should be Manager's planning task
        val task1 = orchestrator.runNextReadyTask()
        assertNotNull(task1)
        assertEquals(TeamRole.MANAGER, task1?.assignedRole)

        // Next ready task should be Architect's architecture task
        val task2 = orchestrator.runNextReadyTask()
        assertNotNull(task2)
        assertEquals(TeamRole.ARCHITECT, task2?.assignedRole)

        // Verify artifacts published
        val artifacts = orchestrator.blackboard.getAllArtifacts()
        assertTrue(artifacts.any { it.type == ArtifactType.API_CONTRACT })
    }

    @Test
    fun testAutoRunAllExecutesPipelineToCompletion() {
        orchestrator.planProject("Build complete mobile app")

        val completedCount = orchestrator.autoRunAll()
        assertEquals(10, completedCount)

        val sessionState = orchestrator.sessionState.value
        assertFalse(sessionState.isRunning)
        assertTrue(sessionState.tasks.all { it.status == TeamTaskStatus.COMPLETED })

        // Check budget tracked tokens
        assertTrue(sessionState.budget.totalTokensUsed > 0L)
        assertTrue(sessionState.budget.totalCostUsd > 0.0)

        // Demo-mode integrity (Milestone 0): simulated reviews are NEVER recorded
        // as gatekeeper approvals. No review verdicts may exist in demo mode.
        assertTrue(sessionState.reviews.none { it.reviewerRole == TeamRole.SECURITY_REVIEWER })
        assertTrue(sessionState.reviews.none { it.reviewerRole == TeamRole.CODE_REVIEWER })
    }

    @Test
    fun testNonDemoModeRecordsReviewsThroughGatekeeper() {
        // Gatekeeper mechanics test: with demo mode explicitly off, the review
        // pipeline records approvals. (Real agent executors are Milestone 10 work;
        // this exercises the gatekeeper path, not real reviews.)
        val realModeOrchestrator = AgentTeamOrchestrator(isDemoMode = false)
        realModeOrchestrator.planProject("Build complete mobile app")

        val completedCount = realModeOrchestrator.autoRunAll()
        assertEquals(10, completedCount)

        val sessionState = realModeOrchestrator.sessionState.value
        assertTrue(sessionState.tasks.all { it.status == TeamTaskStatus.COMPLETED })
        assertTrue(sessionState.reviews.any { it.reviewerRole == TeamRole.SECURITY_REVIEWER })
        assertTrue(sessionState.reviews.any { it.reviewerRole == TeamRole.CODE_REVIEWER })
    }

    @Test
    fun testSubmitReviewAndBroadcasting() {
        orchestrator.planProject("Build authentication module")

        val record = orchestrator.submitReview(
            taskId = "auth-task-1",
            reviewerRole = TeamRole.CODE_REVIEWER,
            verdict = ReviewVerdict.APPROVED,
            comments = "SOLID principles followed."
        )

        assertEquals(ReviewVerdict.APPROVED, record.verdict)
        val sessionState = orchestrator.sessionState.value
        assertEquals(1, sessionState.reviews.size)
        assertTrue(sessionState.messages.any { it.content.contains("SOLID principles followed.") })

        orchestrator.broadcastMessage(TeamRole.MANAGER, TeamRole.DEVELOPER, "Sprint review scheduled.")
        assertTrue(orchestrator.sessionState.value.messages.any { it.content.contains("Sprint review scheduled.") })
    }

    @Test
    fun testCancelSession() {
        orchestrator.planProject("Long running task")
        assertTrue(orchestrator.sessionState.value.isRunning)

        orchestrator.cancelSession()
        assertFalse(orchestrator.sessionState.value.isRunning)
    }
}
