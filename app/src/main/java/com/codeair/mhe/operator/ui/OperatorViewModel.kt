package com.codeair.mhe.operator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codeair.mhe.memory.MemoryDatabase
import com.codeair.mhe.operator.OperatorAccessibilityService
import com.codeair.mhe.operator.OperatorRuntime
import com.codeair.mhe.operator.capture.ScreenCaptureService
import com.codeair.mhe.operator.execution.OperatorOrchestrator
import com.codeair.mhe.security.AuthorizationPrompt
import com.codeair.mhe.security.PermissionLevel
import com.codeair.mhe.security.PermissionPolicyEngine
import com.codeair.mhe.security.SafetyGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class OperatorViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(OperatorUiState())
    val state: StateFlow<OperatorUiState> = _state.asStateFlow()

    private val approvalGates = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val orchestrator: OperatorOrchestrator by lazy {
        val app = getApplication<Application>()
        val gateway = SafetyGateway(policyEngine = PermissionPolicyEngine(app))
        val db = MemoryDatabase.get(app)
        OperatorOrchestrator(app, gateway, db, this).also {
            OperatorRuntime.orchestrator = it
        }
    }

    fun refreshCapabilities() {
        val context = getApplication<Application>()
        val accessibilityReady = OperatorAccessibilityService.isEnabled(context)
        val captureReady = ScreenCaptureService.isActive()
        _state.update { it.copy(accessibilityReady = accessibilityReady, captureReady = captureReady) }
    }

    fun setTargetApp(label: String) {
        _state.update { it.copy(targetAppLabel = label) }
    }

    fun pushTimelineEvent(e: OperatorTimelineEvent) {
        _state.update { it.copy(timeline = it.timeline + e) }
    }

    fun setPendingApproval(prompt: AuthorizationPrompt, bindingId: String) {
        _state.update { it.copy(pendingPrompt = prompt, pendingBindingId = bindingId, error = "") }
    }

    fun clearPendingApproval() {
        _state.update { it.copy(pendingPrompt = null, pendingBindingId = "") }
    }

    fun setRunning(running: Boolean) {
        _state.update { it.copy(isRunning = running) }
    }

    fun submitGoal(goalText: String) {
        if (goalText.isBlank()) {
            _state.update { it.copy(error = "Goal is empty") }
            return
        }
        if (_state.value.isRunning) return
        _state.update { it.copy(error = "") }
        setRunning(true)
        val taskId = UUID.randomUUID().toString()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { orchestrator.runTask(taskId, goalText) }
            _state.update { it.copy(isRunning = false) }
        }
    }

    fun cancelCurrent(taskId: String) {
        orchestrator.cancelTask(taskId)
    }

    suspend fun awaitApproval(bindingId: String): Boolean {
        val gate = CompletableDeferred<Boolean>()
        approvalGates[bindingId] = gate
        return try {
            gate.await()
        } finally {
            approvalGates.remove(bindingId)
        }
    }

    fun grantApproval(bindingId: String, level: PermissionLevel) {
        if (bindingId.isBlank()) return
        pushTimelineEvent(
            OperatorTimelineEvent(
                id = UUID.randomUUID().toString(),
                title = "Approval granted",
                detail = "scope=${level.name} binding=${bindingId.take(12)}",
                timestampMillis = System.currentTimeMillis(),
                success = true
            )
        )
        approvalGates[bindingId]?.complete(true)
    }

    fun denyApproval(bindingId: String) {
        if (bindingId.isBlank()) return
        pushTimelineEvent(
            OperatorTimelineEvent(
                id = UUID.randomUUID().toString(),
                title = "Approval denied",
                detail = "binding=${bindingId.take(12)}",
                timestampMillis = System.currentTimeMillis(),
                success = false
            )
        )
        approvalGates[bindingId]?.complete(false)
    }
}
