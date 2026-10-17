package com.codeair.mhe.operator.execution

import com.codeair.mhe.security.SafetyOperation
import java.util.UUID

class TapExecutor : OperatorStepExecutor {

    override fun handles(operation: SafetyOperation): Boolean =
        operation is SafetyOperation.UiTap

    override fun execute(
        operation: SafetyOperation,
        context: OperatorExecutionContext
    ): OperatorActionResult {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val op = operation as? SafetyOperation.UiTap
                ?: return failure(operation, "unsupported operation", null, startedAt)
            val before = context.observation.capture()
            val node = context.observation.findNode(before, op.targetId)
                ?: return failure(
                    operation,
                    "target not found: ${op.targetId}",
                    before.observationId,
                    startedAt
                )
            val dispatchedAt = System.currentTimeMillis()
            val ok = context.deviceControl.tapAt(
                node.bounds.centerX().toFloat(),
                node.bounds.centerY().toFloat()
            )
            val after = context.observation.capture()
            val label = op.targetLabel.ifBlank { op.targetId }
            OperatorActionResult(
                actionId = UUID.randomUUID().toString(),
                operation = operation,
                success = ok,
                message = if (ok) "tapped $label" else "tap dispatch failed",
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
