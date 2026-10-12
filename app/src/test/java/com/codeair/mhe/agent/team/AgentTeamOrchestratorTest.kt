package com.codeair.mhe.agent.team

import com.codeair.mhe.agent.team.model.ArtifactType
import com.codeair.mhe.agent.team.model.ReviewVerdict
import com.codeair.mhe.agent.team.model.TeamRole
import com.codeair.mhe.agent.team.model.TeamTaskStatus
import com.codeair.mhe.agent.team.runtime.SecurityScanResult
import com.codeair.mhe.agent.team.runtime.SecurityScanner
import com.codeair.mhe.agent.team.runtime.TeamAgentExecutor
import com.codeair.mhe.agent.team.runtime.TeamExecutionResult
import com.codeair.mhe.agent.team.runtime.TeamRoleValidator
import com.codeair.mhe.agent.team.runtime.TestSuiteResult
import com.codeair.mhe.agent.team.runtime.TestSuiteRunner
import com.codeair.mhe.core.result.OperationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentTeamOrchestratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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
    fun testNonDemoModeRecordsReviewsThroughGatekeeper() = runBlocking {
        // M10: non-demo mode is only legal with a real executor wired. The review
        // pipeline records genuine approvals through the gatekeeper.
        val executor = TeamAgentExecutor { task, context ->
            // Faithfully simulates PromptBasedTeamAgentExecutor: the VERDICT marker
            // in reviewer output becomes the structured reviewVerdict.
            val verdict = if (context.role == TeamRole.CODE_REVIEWER) ReviewVerdict.APPROVED else null
            TeamExecutionResult(
                result = OperationResult.success(verificationEvidence = "agent completed"),
                outputText = "Real output for ${task.title}." + (verdict?.let { "\nVERDICT: $it" } ?: ""),
                reviewVerdict = verdict,
            )
        }
        val validator = TeamRoleValidator(
            testSuiteRunner = TestSuiteRunner {
                TestSuiteResult(ran = true, passed = 5, failed = 0, skipped = 0, note = "exit=0")
            },
            securityScanner = SecurityScanner { SecurityScanResult(filesScanned = 10) },
        )
        val realModeOrchestrator = AgentTeamOrchestrator(
            isDemoMode = false,
            teamExecutor = executor,
            teamValidator = validator,
            projectDir = tempFolder.root,
        )
        realModeOrchestrator.planProject("Build complete mobile app")

        val report = realModeOrchestrator.autoRunAllReal()
        assertEquals(10, report.succeeded)

        val sessionState = realModeOrchestrator.sessionState.value
        assertTrue(sessionState.tasks.all { it.status == TeamTaskStatus.COMPLETED })
        assertTrue(sessionState.reviews.any { it.reviewerRole == TeamRole.SECURITY_REVIEWER })
        assertTrue(sessionState.reviews.any { it.reviewerRole == TeamRole.CODE_REVIEWER })
        // M10 integrity: real-mode reviews are genuine, never simulated.
        assertTrue(sessionState.reviews.none { it.isSimulated })
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
