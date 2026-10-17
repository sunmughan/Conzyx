package com.codeair.mhe.operator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OperatorEntryScreen(
    state: OperatorUiState,
    onSubmitGoal: (String) -> Unit,
    onOpenPreflight: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onClose: () -> Unit
) {
    var goal by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("App Operator", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            label = { Text("Goal") },
            placeholder = { Text("What should the operator do?") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4
        )
        Text(
            "Target app: ${state.targetAppLabel.ifBlank { "Not set" }}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.error.isNotBlank()) {
            Text(
                state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = { onSubmitGoal(goal) },
            modifier = Modifier.fillMaxWidth(),
            enabled = goal.isNotBlank() && !state.isRunning
        ) {
            Text(if (state.isRunning) "Running..." else "Run")
        }
        Button(onClick = onOpenPreflight, modifier = Modifier.fillMaxWidth()) {
            Text("Preflight checks")
        }
        Button(onClick = onOpenTimeline, modifier = Modifier.fillMaxWidth()) {
            Text("Run timeline")
        }
        Button(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
            Text("Run history")
        }
        Button(onClick = onOpenCapabilities, modifier = Modifier.fillMaxWidth()) {
            Text("Capabilities")
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Close")
        }
    }
}
