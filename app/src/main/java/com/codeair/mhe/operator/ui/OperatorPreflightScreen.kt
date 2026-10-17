package com.codeair.mhe.operator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OperatorPreflightScreen(
    accessibilityReady: Boolean,
    onFixAccessibility: () -> Unit,
    captureReady: Boolean,
    onFixCapture: () -> Unit,
    targetAppLabel: String,
    onChangeTargetApp: () -> Unit,
    allReady: Boolean,
    onStart: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Preflight", style = MaterialTheme.typography.headlineSmall)
        PreflightRow(
            name = "Accessibility service",
            ready = accessibilityReady,
            readyText = "Ready",
            notReadyText = "Not enabled",
            fixLabel = "Fix",
            onFix = onFixAccessibility
        )
        PreflightRow(
            name = "Screen capture",
            ready = captureReady,
            readyText = "Ready",
            notReadyText = "Consent not granted",
            fixLabel = "Fix",
            onFix = onFixCapture
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Target app", style = MaterialTheme.typography.titleSmall)
                Text(
                    targetAppLabel.ifBlank { "Not set" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onChangeTargetApp) {
                Text("Change")
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onStart, enabled = allReady, modifier = Modifier.fillMaxWidth()) {
            Text("Start")
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Close")
        }
    }
}

@Composable
private fun PreflightRow(
    name: String,
    ready: Boolean,
    readyText: String,
    notReadyText: String,
    fixLabel: String,
    onFix: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                if (ready) readyText else notReadyText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (ready) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        if (!ready) {
            OutlinedButton(onClick = onFix) {
                Text(fixLabel)
            }
        }
    }
}
