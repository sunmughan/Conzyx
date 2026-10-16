package com.codeair.mhe.cloud.runner

import com.codeair.mhe.cloud.model.HybridExecutionJob
import com.codeair.mhe.cloud.model.HybridJobState
import com.codeair.mhe.cloud.model.WorkloadType
import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.security.PermissionCategory
import com.codeair.mhe.security.PermissionLevel
import com.codeair.mhe.security.PermissionPolicyEngine
import com.codeair.mhe.security.SafetyGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CloudJobRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun gatewayDenyingShell(workDir: File): SafetyGateway {
        val policyDir = tempFolder.newFolder("policy-deny-${System.nanoTime()}")
        val engine = PermissionPolicyEngine(policyDir = policyDir)
        engine.grantPermission(PermissionCategory.SHELL_EXEC, PermissionLevel.DENY)
        return SafetyGateway(policyEngine = engine, workspaceRoot = workDir)
    }

    private fun gatewayAllowingShell(workDir: File): SafetyGateway {
        val policyDir = tempFolder.newFolder("policy-allow-${System.nanoTime()}")
        val engine = PermissionPolicyEngine(policyDir = policyDir)
        engine.grantPermission(PermissionCategory.SHELL_EXEC, PermissionLevel.ALWAYS)
        return SafetyGateway(policyEngine = engine, workspaceRoot = workDir)
    }

    private fun localJob(command: String) = HybridExecutionJob(
        title = "test job",
        workloadType = WorkloadType.LIGHT_SCRIPT,
        localCommand = command
    )

    @Test
    fun deniedCommandNeverReachesProcessBoundary() {
        val workDir = tempFolder.newFolder("work")
        var runnerInvoked = false
        val runner = CloudJobRunner(
            localCommandRunner = {
                runnerInvoked = true
                OperationResult.success()
            },
            safetyGateway = gatewayDenyingShell(workDir)
        )

        val finished = runner.execute(localJob("echo hello"))

        assertFalse("denied command must never reach the process-launch boundary", runnerInvoked)
        assertEquals(HybridJobState.FAILED, finished.state)
        assertTrue(finished.errorMessage.orEmpty().contains("safety gateway", ignoreCase = true))
    }

    @Test
    fun deniedCommandIsBlockedBeforeAnyOutputIsProduced() {
        val workDir = tempFolder.newFolder("work")
        val seenCommands = mutableListOf<String>()
        val runner = CloudJobRunner(
            localCommandRunner = { command ->
                seenCommands.add(command)
                OperationResult.success(logs = listOf("should never appear"))
            },
            safetyGateway = gatewayDenyingShell(workDir)
        )

        val finished = runner.execute(localJob("rm -rf /"))

        assertTrue(seenCommands.isEmpty())
        assertTrue(finished.outputLogs.none { it.contains("should never appear") })
        assertEquals(HybridJobState.FAILED, finished.state)
    }

    @Test
    fun allowedCommandReachesProcessBoundary() {
        val workDir = tempFolder.newFolder("work")
        var runnerInvoked = false
        val runner = CloudJobRunner(
            localCommandRunner = {
                runnerInvoked = true
                OperationResult.success(logs = listOf("done"))
            },
            safetyGateway = gatewayAllowingShell(workDir)
        )

        val finished = runner.execute(localJob("echo hello"))

        assertTrue(runnerInvoked)
        assertEquals(HybridJobState.COMPLETED, finished.state)
    }

    @Test
    fun noGatewayPreservesPreviousBehavior() {
        var runnerInvoked = false
        val runner = CloudJobRunner(
            localCommandRunner = {
                runnerInvoked = true
                OperationResult.success()
            }
        )

        val finished = runner.execute(localJob("echo hello"))

        assertTrue(runnerInvoked)
        assertEquals(HybridJobState.COMPLETED, finished.state)
    }

    @Test
    fun logsDoNotIncludeSecrets() {
        val workDir = tempFolder.newFolder("work")
        val runner = CloudJobRunner(
            localCommandRunner = {
                OperationResult.success(logs = listOf("response ok", "using token=hunter2-token"))
            },
            safetyGateway = gatewayAllowingShell(workDir)
        )

        val finished = runner.execute(localJob("deploy --api_key=supersecret123"))

        val allLogs = finished.outputLogs.joinToString("\n")
        assertFalse(allLogs.contains("supersecret123"))
        assertFalse(allLogs.contains("hunter2-token"))
        assertTrue(allLogs.contains("[REDACTED]"))
        assertEquals(HybridJobState.COMPLETED, finished.state)
    }

    @Test
    fun blockedJobExplainsTheVerdict() {
        val workDir = tempFolder.newFolder("work")
        val runner = CloudJobRunner(
            localCommandRunner = { OperationResult.success() },
            safetyGateway = gatewayDenyingShell(workDir)
        )

        val finished = runner.execute(localJob("echo hello"))

        assertEquals(HybridJobState.FAILED, finished.state)
        assertTrue(finished.outputLogs.any { it.contains("Blocked by the safety gateway") })
    }
}
