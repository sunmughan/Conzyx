package com.jarves.mh.git

import com.jarves.mh.core.result.OperationResult
import com.jarves.mh.git.workflow.GitHubWorkflowEngine
import com.jarves.mh.git.workflow.WorkflowStage
import com.jarves.mh.git.workflow.WorkflowStepStatus
import com.jarves.mh.network.GitHubClient
import com.jarves.mh.network.GitHubIssue
import com.jarves.mh.network.GitHubPullRequest
import com.jarves.mh.workspace.GitDomainController
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeGitHubClient : GitHubClient() {
    override fun createPullRequest(
token: String,
        repoFullName: String,
        title: String,
        head: String,
        base: String,
        body: String,
        draft: Boolean
    ): GitHubPullRequest = GitHubPullRequest(
        number = 7,
        title = title,
        state = "open",
        user = "tester",
        headBranch = head,
        baseBranch = base,
        htmlUrl = "https://github.com/$repoFullName/pull/7",
        body = body
    )
}

class GitHubWorkflowEngineTest {

    private fun engine(gitController: GitDomainController) =
        GitHubWorkflowEngine(gitController, FakeGitHubClient())

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "workflow_test_repo").apply { mkdirs() }

    @Test
    fun autonomousWorkflowOfflineSuccess() = runBlocking {
        val executedCommands = mutableListOf<List<String>>()
        val gitController = GitDomainController { args, _, _ ->
            executedCommands += args
            0 to "ok"
        }
        val workflowEngine = engine(gitController)

        val issue = GitHubIssue(
            number = 42,
            title = "Crash on App Launch",
            state = "open",
            user = "developer",
            comments = 3,
            htmlUrl = "https://github.com/example/repo/issues/42",
            body = "App crashes when initializing database."
        )

        var agentExecutedPrompt = ""
        val result = workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "", // Offline test
            repoFullName = "example/repo",
            workingDir = tempDir,
            baseBranch = "main",
            agentExecutor = { prompt ->
                agentExecutedPrompt = prompt
                OperationResult.success()
            },
            buildAndTestValidator = { OperationResult.success(logs = listOf("All tests pass")) }
        )

        assertTrue(result.isSuccess)
        val state = workflowEngine.state.value

        assertEquals(WorkflowStage.COMPLETED, state.currentStage)
        assertEquals(42, state.issueNumber)
        assertEquals("Crash on App Launch", state.issueTitle)
        assertEquals("fix/issue-42-crash-on-app-launch", state.branchName)
        assertFalse(state.isRunning)
        assertNull(state.errorMessage)

        // Verify agent received prompt with issue details
        assertTrue(agentExecutedPrompt.contains("Crash on App Launch"))
        assertTrue(agentExecutedPrompt.contains("App crashes when initializing database."))

        // Verify steps status
        val steps = state.steps
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.FETCHING_ISSUE }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.UNDERSTANDING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.CREATING_BRANCH }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.IMPLEMENTING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.BUILDING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.TESTING }?.status)
        assertEquals(WorkflowStepStatus.SKIPPED, steps.find { it.stage == WorkflowStage.FIXING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.COMMITTING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.PUSHING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.CREATING_PR }?.status)

        // Verify git commands executed
        assertTrue(executedCommands.any { it.contains("checkout") && it.contains("fix/issue-42-crash-on-app-launch") })
        assertTrue(executedCommands.any { it.contains("add") && it.contains("-A") })
        assertTrue(executedCommands.any { it.contains("commit") })
    }

    @Test
    fun autonomousWorkflowSelfRepairCycle() = runBlocking {
        val gitController = GitDomainController { _, _, _ -> 0 to "ok" }
        val workflowEngine = engine(gitController)

        val issue = GitHubIssue(
            number = 99,
            title = "Fix typo in config",
            state = "open",
            user = "developer",
            comments = 0,
            htmlUrl = "https://github.com/example/repo/issues/99"
        )

        var buildAttempt = 0
        var repairAgentCalled = false

        val result = workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "",
            repoFullName = "example/repo",
            workingDir = tempDir,
            agentExecutor = { prompt ->
                if (prompt.contains("Build/test failed")) {
                    repairAgentCalled = true
                }
                OperationResult.success()
            },
            buildAndTestValidator = {
                buildAttempt++
                if (buildAttempt == 1) {
                    OperationResult.failure("Syntax error: unresolved reference")
                } else {
                    OperationResult.success(logs = listOf("Build succeeded on retry"))
                }
            }
        )

        assertTrue(result.isSuccess)
        assertTrue(repairAgentCalled)
        val state = workflowEngine.state.value

        assertEquals(WorkflowStage.COMPLETED, state.currentStage)
        val fixingStep = state.steps.find { it.stage == WorkflowStage.FIXING }
        assertEquals(WorkflowStepStatus.SUCCESS, fixingStep?.status)
        assertTrue(fixingStep?.detail?.startsWith("Repair successful") == true)
    }

    @Test
    fun autonomousWorkflowFailsWhenAgentFails() = runBlocking {
        val gitController = GitDomainController { _, _, _ -> 0 to "ok" }
        val workflowEngine = engine(gitController)

        val issue = GitHubIssue(
            number = 101,
            title = "Impossible task",
            state = "open",
            user = "user",
            comments = 1,
            htmlUrl = ""
        )

        val result = workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "",
            repoFullName = "example/repo",
            workingDir = tempDir,
            agentExecutor = { OperationResult.failure("Agent failed") } // Agent fails
        )

        assertFalse(result.isSuccess)
        val state = workflowEngine.state.value

        assertEquals(WorkflowStage.FAILED, state.currentStage)
        assertFalse(state.isRunning)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Agent implementation did not complete"))
    }

    @Test
    fun workflowResetClearsState() = runBlocking {
        val gitController = GitDomainController { _, _, _ -> 0 to "ok" }
        val workflowEngine = engine(gitController)

        val issue = GitHubIssue(number = 1, title = "Bug", state = "open", user = "u", comments = 0, htmlUrl = "")
        workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "",
            repoFullName = "repo",
            workingDir = tempDir,
            agentExecutor = { OperationResult.success() }
        )

        assertEquals(WorkflowStage.COMPLETED, workflowEngine.state.value.currentStage)
        workflowEngine.reset()

        assertEquals(WorkflowStage.IDLE, workflowEngine.state.value.currentStage)
        assertEquals(0, workflowEngine.state.value.issueNumber)
        assertTrue(workflowEngine.state.value.steps.isEmpty())
    }
}
