package com.codeair.mhe.operator.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeair.mhe.operator.capture.ScreenCaptureService

@Composable
fun OperatorOverlayHost(
    viewModel: OperatorViewModel,
    onClose: () -> Unit
) {
    var destination by rememberSaveable { mutableStateOf(0) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    when (destination) {
        0 -> OperatorEntryScreen(
            state = state,
            onSubmitGoal = { viewModel.submitGoal(it) },
            onOpenPreflight = { destination = 1 },
            onOpenTimeline = { destination = 2 },
            onOpenHistory = { destination = 3 },
            onOpenCapabilities = { destination = 4 },
            onClose = onClose
        )
        1 -> OperatorPreflightScreen(
            accessibilityReady = state.accessibilityReady,
            onFixAccessibility = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            captureReady = state.captureReady,
            onFixCapture = { ScreenCaptureService.requestCapture(context) },
            targetAppLabel = state.targetAppLabel,
            onChangeTargetApp = { destination = 0 },
            allReady = state.accessibilityReady && state.captureReady && state.targetAppLabel.isNotBlank(),
            onStart = { viewModel.setRunning(true) },
            onClose = onClose
        )
        2 -> OperatorTimelineScreen(
            events = state.timeline,
            onClose = onClose
        )
        3 -> OperatorHistoryScreen(
            runs = emptyList(),
            onClose = onClose
        )
        4 -> OperatorCapabilityScreen(
            states = listOf(
                "Accessibility service" to if (state.accessibilityReady) CapabilityState.READY else CapabilityState.NEEDS_PERMISSION,
                "Screen capture" to if (state.captureReady) CapabilityState.READY else CapabilityState.NEEDS_PERMISSION,
                "Target app" to if (state.targetAppLabel.isNotBlank()) CapabilityState.READY else CapabilityState.NEEDS_PERMISSION
            ),
            onClose = onClose
        )
    }

    val pendingPrompt = state.pendingPrompt
    if (pendingPrompt != null) {
        OperatorApprovalSheet(
            prompt = pendingPrompt,
            bindingId = state.pendingBindingId,
            onGrant = { level -> viewModel.grantApproval(state.pendingBindingId, level) },
            onDeny = { viewModel.denyApproval(state.pendingBindingId) }
        )
    }
}
