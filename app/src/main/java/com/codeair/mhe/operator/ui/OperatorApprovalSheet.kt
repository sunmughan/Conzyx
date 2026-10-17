@file:OptIn(ExperimentalMaterial3Api::class)

package com.codeair.mhe.operator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codeair.mhe.security.AuthorizationPrompt
import com.codeair.mhe.security.PermissionLevel

@Composable
fun OperatorApprovalSheet(
    prompt: AuthorizationPrompt,
    bindingId: String,
    onGrant: (PermissionLevel) -> Unit,
    onDeny: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDeny) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(prompt.title, style = MaterialTheme.typography.headlineSmall)
            Text(prompt.operation.describe(), style = MaterialTheme.typography.bodyMedium)
            Text("Risk: ${prompt.risk.label}", style = MaterialTheme.typography.bodyMedium)
            Text(prompt.whatHappens, style = MaterialTheme.typography.bodySmall)
            Text(
                "Binding: $bindingId",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            prompt.scopes.forEach { scope ->
                Button(
                    onClick = { onGrant(scope) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant ${scope.name.lowercase().replace('_', ' ')}")
                }
            }
            OutlinedButton(onClick = onDeny, modifier = Modifier.fillMaxWidth()) {
                Text("Deny")
            }
        }
    }
}
