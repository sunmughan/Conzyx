package com.codeair.mhe.operator.execution

import com.codeair.mhe.operator.OperatorDeviceControl
import com.codeair.mhe.operator.observation.ObservationCoordinator
import com.codeair.mhe.security.SafetyGateway
import com.codeair.mhe.security.SafetyOperation
import com.codeair.mhe.security.SafetyVerdict
import java.security.MessageDigest
import java.util.UUID

class OperatorTaskExecutor(
    private val gateway: SafetyGateway,
    private val deviceControl: OperatorDeviceControl,
    private val observation: ObservationCoordinator,
    private val executors: List<OperatorStepExecutor>
) {

    fun executeStep(runId: String, stepId: String, operation: SafetyOperation): OperatorActionResult {
        val observationBeforeId = observation.capture().observationId
        val dispatchedAtMillis = System.currentTimeMillis()
        val decision = gateway.authorize(operation, sessionId = runId)
        when (decision.verdict) {
            SafetyVerdict.DENY -> return failedResult(
                message = "Denied: ${decision.reason}",
                operation = operation,
                observationBeforeId = observationBeforeId,
                dispatchedAtMillis = dispatchedAtMillis
            )
            SafetyVerdict.PROMPT -> return failedResult(
                message = "Approval required (binding ${approvalBindingId(runId, stepId, operation)}): ${decision.prompt?.title ?: decision.reason}",
                operation = operation,
                observationBeforeId = observationBeforeId,
                dispatchedAtMillis = dispatchedAtMillis
            )
            SafetyVerdict.ALLOW -> Unit
        }
        val executor = executors.firstOrNull { it.handles(operation) }
            ?: return failedResult(
                message = "No executor handles ${operation::class.simpleName}",
                operation = operation,
                observationBeforeId = observationBeforeId,
                dispatchedAtMillis = dispatchedAtMillis
            )
        val inner = runCatching {
            executor.execute(operation, OperatorExecutionContext(runId, stepId, gateway, deviceControl, observation))
        }.getOrElse { throwable ->
            return failedResult(
                message = throwable.message ?: "Executor failed",
                operation = operation,
                observationBeforeId = observationBeforeId,
                dispatchedAtMillis = dispatchedAtMillis
            )
        }
        val completedAtMillis = System.currentTimeMillis()
        return inner.copy(
            actionId = UUID.randomUUID().toString(),
            operation = operation,
            observationBeforeId = observationBeforeId,
            observationAfterId = observation.capture().observationId,
            dispatchedAtMillis = dispatchedAtMillis,
            completedAtMillis = completedAtMillis
        )
    }

    fun isDeviceReady(): Boolean = deviceControl.isAvailable()

    private fun approvalBindingId(runId: String, stepId: String, operation: SafetyOperation): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$runId|$stepId|${operation.describe()}".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun failedResult(
        message: String,
        operation: SafetyOperation,
        observationBeforeId: String?,
        dispatchedAtMillis: Long
    ): OperatorActionResult {
        return OperatorActionResult(
            actionId = UUID.randomUUID().toString(),
            operation = operation,
            success = false,
            message = message,
            observationBeforeId = observationBeforeId,
            dispatchedAtMillis = dispatchedAtMillis,
            completedAtMillis = System.currentTimeMillis()
        )
    }
}
