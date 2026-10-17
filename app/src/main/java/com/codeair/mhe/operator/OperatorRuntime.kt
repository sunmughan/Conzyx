package com.codeair.mhe.operator

import com.codeair.mhe.operator.execution.OperatorOrchestrator

object OperatorRuntime {
    @Volatile
    var orchestrator: OperatorOrchestrator? = null
}
