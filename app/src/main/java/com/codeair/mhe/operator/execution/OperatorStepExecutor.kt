package com.codeair.mhe.operator.execution

import com.codeair.mhe.security.SafetyOperation

interface OperatorStepExecutor {
    fun handles(operation: SafetyOperation): Boolean
    fun execute(operation: SafetyOperation, context: OperatorExecutionContext): OperatorActionResult
}

data class OperatorExecutionContext(
    val runId: String,
    val stepId: String,
    val gateway: com.codeair.mhe.security.SafetyGateway,
    val deviceControl: com.codeair.mhe.operator.OperatorDeviceControl,
    val observation: com.codeair.mhe.operator.observation.ObservationCoordinator
)
