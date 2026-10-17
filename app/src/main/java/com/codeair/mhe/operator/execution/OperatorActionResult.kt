package com.codeair.mhe.operator.execution

import com.codeair.mhe.security.SafetyOperation

data class OperatorActionResult(
    val actionId: String,
    val operation: SafetyOperation,
    val success: Boolean,
    val message: String = "",
    val observationBeforeId: String? = null,
    val observationAfterId: String? = null,
    val dispatchedAtMillis: Long,
    val completedAtMillis: Long
)
