package com.codeair.mhe.operator.execution

import com.codeair.mhe.security.SafetyOperation
import java.util.UUID

class BackExecutor : OperatorStepExecutor {

    override fun handles(operation: SafetyOperation): Boolean =
        operation is SafetyOperation.UiPressBack

    override fun execute(
        operation: SafetyOperation,
        context: OperatorExecutionContext
    ): OperatorActionResult {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            if (operation !is SafetyOperation.UiPressBack) {
                return failure(operation, "unsupported operation", null, startedAt)
            }
            val before = context.observation.capture()
            val dispatchedAt = System.currentTimeMillis()
            val ok = context.deviceControl.pressBack()
            val after = context.observation.capture()
            OperatorActionResult(
                actionId = UUID.randomUUID().toString(),
                operation = operation,
                success = ok,
                message = if (ok) "pressed back" else "back dispatch failed",
                observationBeforeId = before.observationId,
                observationAfterId = after.observationId,
                dispatchedAtMillis = dispatchedAt,
                completedAtMillis = System.currentTimeMillis()
            )
        }.getOrElse { t -> failure(operation, t.message ?: t.javaClass.simpleName, null, startedAt) }
    }

    private fun failure(
        operation: SafetyOperation,
        message: String,
        observationBeforeId: String?,
        startedAt: Long
    ): OperatorActionResult = OperatorActionResult(
        actionId = UUID.randomUUID().toString(),
        operation = operation,
        success = false,
        message = message,
        observationBeforeId = observationBeforeId,
        dispatchedAtMillis = startedAt,
        completedAtMillis = System.currentTimeMillis()
    )
}
