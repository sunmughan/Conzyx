package com.codeair.mhe.operator.execution

import com.codeair.mhe.security.SafetyOperation
import com.codeair.mhe.security.SafetyOperation.UiLaunchApp
import java.util.UUID

class LaunchAppExecutor : OperatorStepExecutor {

    override fun handles(operation: SafetyOperation): Boolean = operation is UiLaunchApp

    override fun execute(
        operation: SafetyOperation,
        context: OperatorExecutionContext,
    ): OperatorActionResult {
        val actionId = UUID.randomUUID().toString()
        val dispatchedAt = System.currentTimeMillis()
        fun finish(
            success: Boolean,
            message: String,
            beforeId: String?,
            afterId: String?,
        ): OperatorActionResult = OperatorActionResult(
            actionId = actionId,
            operation = operation,
            success = success,
            message = message,
            observationBeforeId = beforeId,
            observationAfterId = afterId,
            dispatchedAtMillis = dispatchedAt,
            completedAtMillis = System.currentTimeMillis(),
        )
        val outcome = runCatching {
            val op = operation as UiLaunchApp
            val before = context.observation.capture()
            val ok = context.deviceControl.launchApp(op.packageName)
            val after = context.observation.capture()
            finish(ok, "", before.observationId, after.observationId)
        }
        return outcome.getOrElse { e ->
            finish(false, e.message ?: "unexpected error", null, null)
        }
    }
}
