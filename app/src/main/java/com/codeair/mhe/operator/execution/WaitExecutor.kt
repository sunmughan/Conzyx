package com.codeair.mhe.operator.execution

import com.codeair.mhe.security.SafetyOperation
import com.codeair.mhe.security.UiWait
import java.util.UUID

class WaitExecutor : OperatorStepExecutor {

    override fun handles(operation: SafetyOperation): Boolean = operation is UiWait

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
            val op = operation as UiWait
            val before = context.observation.capture()
            val capped = op.millis.coerceIn(0, 10000)
            Thread.sleep(capped)
            val after = context.observation.capture()
            finish(true, "waited ${capped}ms", before.observationId, after.observationId)
        }
        return outcome.getOrElse { e ->
            finish(false, e.message ?: "unexpected error", null, null)
        }
    }
}
