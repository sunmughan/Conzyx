package com.codeair.mhe.operator.execution

import com.codeair.mhe.security.SafetyOperation
import com.codeair.mhe.security.ScrollDirection
import java.util.UUID

class ScrollExecutor : OperatorStepExecutor {

    override fun handles(operation: SafetyOperation): Boolean =
        operation is SafetyOperation.UiScroll

    override fun execute(
        operation: SafetyOperation,
        context: OperatorExecutionContext
    ): OperatorActionResult {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val op = operation as? SafetyOperation.UiScroll
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
            val ok = if (op.direction == ScrollDirection.UP || op.direction == ScrollDirection.LEFT) {
                context.deviceControl.scrollBackward(node)
            } else {
                context.deviceControl.scrollForward(node)
            }
            val after = context.observation.capture()
            val label = op.targetLabel.ifBlank { op.targetId }
            OperatorActionResult(
                actionId = UUID.randomUUID().toString(),
                operation = operation,
                success = ok,
                message = if (ok) "scrolled $label" else "scroll dispatch failed",
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
