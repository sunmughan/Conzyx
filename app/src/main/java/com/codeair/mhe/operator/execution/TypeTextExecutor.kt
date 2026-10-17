package com.codeair.mhe.operator.execution

import com.codeair.mhe.operator.observation.ObservationCoordinator
import com.codeair.mhe.security.SafetyOperation
import com.codeair.mhe.security.UiTypeText
import java.util.UUID

class TypeTextExecutor : OperatorStepExecutor {

    override fun handles(operation: SafetyOperation): Boolean = operation is UiTypeText

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
            val coordinator: ObservationCoordinator = context.observation
            val before = coordinator.capture()
            val op = operation as UiTypeText
            val node = coordinator.findNode(before, op.targetId)
                ?: return finish(false, "target not found", before.observationId, null)
            val ok = context.deviceControl.setText(node, op.text)
            val after = coordinator.capture()
            finish(ok, "", before.observationId, after.observationId)
        }
        return outcome.getOrElse { e ->
            finish(false, e.message ?: "unexpected error", null, null)
        }
    }
}
