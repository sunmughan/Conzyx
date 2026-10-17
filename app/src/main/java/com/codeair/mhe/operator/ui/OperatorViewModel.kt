package com.codeair.mhe.operator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.codeair.mhe.operator.OperatorAccessibilityService
import com.codeair.mhe.operator.capture.ScreenCaptureService
import com.codeair.mhe.security.AuthorizationPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class OperatorViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(OperatorUiState())
    val state: StateFlow<OperatorUiState> = _state.asStateFlow()

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
        _state.update { it.copy(pendingPrompt = prompt, pendingBindingId = bindingId) }
    }

    fun clearPendingApproval() {
        _state.update { it.copy(pendingPrompt = null, pendingBindingId = "") }
    }

    fun setRunning(running: Boolean) {
        _state.update { it.copy(isRunning = running) }
    }
}
