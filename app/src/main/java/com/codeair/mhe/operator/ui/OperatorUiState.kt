package com.codeair.mhe.operator.ui

import com.codeair.mhe.security.AuthorizationPrompt

data class OperatorUiState(
    val accessibilityReady: Boolean = false,
    val captureReady: Boolean = false,
    val targetAppLabel: String = "",
    val timeline: List<OperatorTimelineEvent> = emptyList(),
    val pendingPrompt: AuthorizationPrompt? = null,
    val pendingBindingId: String = "",
    val isRunning: Boolean = false,
    val error: String = ""
)

data class OperatorTimelineEvent(
    val id: String,
    val title: String,
    val detail: String,
    val timestampMillis: Long,
    val success: Boolean
)

enum class CapabilityState {
    READY,
    NEEDS_PERMISSION,
    UNAVAILABLE,
    UNSUPPORTED,
    ERROR
}
