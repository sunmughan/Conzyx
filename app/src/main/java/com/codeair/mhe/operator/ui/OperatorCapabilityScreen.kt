package com.codeair.mhe.operator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun OperatorCapabilityScreen(
    states: List<Pair<String, CapabilityState>>,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Capabilities", style = MaterialTheme.typography.headlineSmall)
        states.forEach { (name, state) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(capabilityColor(state), shape = CircleShape)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        capabilityDescription(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Close")
        }
    }
}

private fun capabilityColor(state: CapabilityState): Color = when (state) {
    CapabilityState.READY -> Color(0xFF2E7D32)
    CapabilityState.NEEDS_PERMISSION -> Color(0xFFEF6C00)
    CapabilityState.UNAVAILABLE -> Color(0xFF9E9E9E)
    CapabilityState.UNSUPPORTED -> Color(0xFF616161)
    CapabilityState.ERROR -> Color(0xFFC62828)
}

private fun capabilityDescription(state: CapabilityState): String = when (state) {
    CapabilityState.READY -> "Ready"
    CapabilityState.NEEDS_PERMISSION -> "Needs permission"
    CapabilityState.UNAVAILABLE -> "Unavailable on this device"
    CapabilityState.UNSUPPORTED -> "Not supported on this device"
    CapabilityState.ERROR -> "Error"
}
