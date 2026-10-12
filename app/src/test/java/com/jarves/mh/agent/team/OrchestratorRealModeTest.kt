package com.jarves.mh.agent.team

import com.jarves.mh.agent.team.model.ArtifactType
import com.jarves.mh.agent.team.model.ReviewVerdict
import com.jarves.mh.agent.team.model.TeamArtifact
import com.jarves.mh.agent.team.model.TeamRole
import com.jarves.mh.agent.team.model.TeamTaskStatus
import com.jarves.mh.agent.team.runtime.SecurityScanResult
import com.jarves.mh.agent.team.runtime.TeamAgentExecutor
import com.jarves.mh.agent.team.runtime.TeamExecutionResult
import com.jarves.mh.agent.team.runtime.TeamRoleValidator
import com.jarves.mh.agent.team.runtime.TestSuiteResult
import com.jarves.mh.core.result.OperationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrchestratorRealModeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun approvingValidator() = TeamRoleValidator(
        testSuiteRunner = { TestSuiteResult(ran = true, passed = 10, failed = 0, skipped = 0, note = "exit=0") },
        securityScanner = { SecurityScanResult(filesScanned = 50) },
    )

    private fun fakeExecutor(): TeamAgentExecutor = TeamAgentExecutor { task, context ->
        val marker = if (context.role == TeamRole.CODE_REVIEWER) "\nVERDICT: APPROVED" else ""
        TeamExecutionResult(
            result = OperationResult.success(verificationEvidence = "agent completed"),
            outputText = "Real output for ${task.title}.$marker",
            artifacts = listOf(
                TeamArtifact(
                    title = "${context.role.title}: ${task.title}",
                    type = ArtifactType.SOURCE_PATCH,
                    authorRole = context.role,
                    content = "Real output for ${task.title}.",
                    metadata = mapOf("taskId" to task.id),
                    isSimulated = false,
                )
            ),
        )
    }

    @Test
    fun testRealModeWithoutExecutorFailsLoudly() {
        val orchestrator = AgentTeamOrchestrator(isDemoMode = false)
        try {
            orchestrator.requireRealExecution()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("TeamAgentExecutor"))
        }
    }

    @Test
    fun testRealModeWithExecutorPassesGuard() {
        val orchestrator = AgentTeamOrchestrator(isDemoMode = false, teamExecutor = fakeExecutor())
        orchestrator.requireRealExecution() // must not throw
    }

    @Test
    fun testDemoModeNeedsNoExecutor() {
        val orchestrator = AgentTeamOrchestrator(isDemoMode = true)
        orchestrator.requireRealExecution() // must not throw
    }

    @Test
    fun testSyncSteppingRefusesInRealMode() {
        val orchestrator = AgentTeamOrchestrator(isDemoMode = false, teamExecutor = fakeExecutor())
        orchestrator.planProject("goal")
        try {
            orchestrator.runNextReadyTask()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("executeNextReadyTask"))
        }
    }

    @Test
    fun testExecuteNextReadyTaskRunsGenuinely() = runBlocking {
        val orchestrator = AgentTeamOrchestrator(
            isDemoMode = false,
            teamExecutor = fakeExecutor(),
            teamValidator = approvingValidator(),
            projectDir = tempFolder.root,
        )
        orchestrator.planProject("Build a notes app")
        val executed = orchestrator.executeNextReadyTask()
        assertNotNull(executed)
        val stored = orchestrator.scheduler.getTask(executed!!.id)!!
        assertEquals(TeamTaskStatus.COMPLETED, stored.status)
        assertFalse(stored.isSimulated)
        assertTrue(stored.output.contains("Real output"))
        // Genuine artifact on the blackboard, never simulated.
        val artifacts = orchestrator.blackboard.getAllArtifacts()
            .filter { it.metadata["taskId"] == executed.id }
        assertEquals(1, artifacts.size)
        assertFalse(artifacts[0].isSimulated)
    }

    @Test
    fun testAutoRunAllRealCompletesFullDagWithRealReviews() = runBlocking {
        val orchestrator = AgentTeamOrchestrator(
            isDemoMode = false,
            teamExecutor = fakeExecutor(),
            teamValidator = approvingValidator(),
            projectDir = tempFolder.root,
            maxConcurrentAgents = 3,
        )
        orchestrator.planProject("Build a notes app")
        val report = orchestrator.autoRunAllReal()
        val outcomeDetail = report.outcomes.joinToString("; ") { "${it.task.title}=${it.status}" }
        assertEquals("outcomes: [$outcomeDetail]", 10, report.succeeded)
        assertEquals(0, report.failed)
        assertTrue(report.isSuccess)
        // Real reviews were recorded for the reviewer roles.
        val reviews = orchestrator.gatekeeper.getAllReviews()
        val reviewerRoles = reviews.map { it.reviewerRole }.toSet()
        assertTrue(reviewerRoles.contains(TeamRole.TESTER))
        assertTrue(reviewerRoles.contains(TeamRole.SECURITY_REVIEWER))
        assertTrue(reviewerRoles.contains(TeamRole.CODE_REVIEWER))
        assertTrue(reviews.none { it.isSimulated })
        assertTrue(reviews.all { it.verdict == ReviewVerdict.APPROVED })
        // Session state reflects completion.
        assertFalse(orchestrator.sessionState.value.isRunning)
        assertFalse(orchestrator.sessionState.value.isDemoMode)
    }

    @Test
    fun testRealFailureMarksTaskFailedNotCompleted() = runBlocking {
        val failing = TeamAgentExecutor { task, _ ->
            TeamExecutionResult(
                result = OperationResult.failure(error = "agent exploded"),
                outputText = "no output",
            )
        }
        val orchestrator = AgentTeamOrchestrator(
            isDemoMode = false,
            teamExecutor = failing,
            teamValidator = approvingValidator(),
        )
        orchestrator.planProject("goal")
        val executed = orchestrator.executeNextReadyTask()
        assertNotNull(executed)
        assertEquals(TeamTaskStatus.FAILED, orchestrator.scheduler.getTask(executed!!.id)!!.status)
    }
}
