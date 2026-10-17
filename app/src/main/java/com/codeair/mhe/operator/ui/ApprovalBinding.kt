package com.codeair.mhe.operator.ui

import com.codeair.mhe.security.SafetyOperation
import java.security.MessageDigest

fun approvalBindingId(runId: String, stepId: String, operation: SafetyOperation): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = "$runId|$stepId|${operation.describe()}".toByteArray(Charsets.UTF_8)
    return digest.digest(input).joinToString("") { "%02x".format(it) }
}
