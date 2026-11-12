package com.codeair.mhe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeair.mhe.model.Project
import com.codeair.mhe.session.AgentSessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 5 — mobile command center.
 *
 * One glanceable home for everything running or waiting in the app: the
 * live task, the interrupted session (resume/dismiss), the prompts queued
 * behind the running task in this chat, and recent session history.
 * Every row is backed by real state — nothing here is a placeholder.
 */
@Composable
fun CommandCenterScreen(
    state: AppUiState,
    onStop: () -> Unit,
    onResumeInterruptedSession: () -> Unit,
    onDismissInterruptedSession: () -> Unit,
    onCancelQueuedPrompt: (String) -> Unit,
    onSendQueuedPromptNow: (String) -> Unit,
    onOpenProject: (Project) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Command center",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        item {
            CommandSectionCard(title = "Running task") {
                if (state.isRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                state.currentTaskRequest ?: "Working…",
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            state.taskStartedAtMillis?.let { started ->
                                Text(
                                    "Running for ${formatElapsed(started)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(
                            onClick = onStop,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text("Stop", fontSize = 12.sp, maxLines = 1)
                        }
                    }
                } else {
                    Text(
                        state.lastTaskOutcome ?: "No task running.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.interruptedSession != null) {
            item {
                val interrupted = state.interruptedSession
                CommandSectionCard(title = "Interrupted session") {
                    Text(
                        interrupted.projectTitle,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        interrupted.initialPrompt,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Button(
                            onClick = onResumeInterruptedSession,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text("Resume", fontSize = 12.sp, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onDismissInterruptedSession) {
                            Text("Dismiss", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item {
            CommandSectionCard(title = "Queued in this chat (${state.queuedPrompts.size})") {
                if (state.queuedPrompts.isEmpty()) {
                    Text(
                        "No queued prompts. Send a message while a task runs and it waits here.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.queuedPrompts.forEach { queued ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    queued.text,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(
                                    onClick = { onSendQueuedPromptNow(queued.id) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Send now")
                                }
                                IconButton(
                                    onClick = { onCancelQueuedPrompt(queued.id) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel queued prompt")
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            CommandSectionCard(title = "Recent sessions") {
                val history = state.sessionHistory.take(8)
                if (history.isEmpty()) {
                    Text(
                        "No finished sessions yet. Completed and failed tasks appear here.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        history.forEach { session ->
                            val project = state.projects.firstOrNull { it.id == session.projectId }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (project != null) {
                                            Modifier.clickable { onOpenProject(project) }
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        session.projectTitle,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${sessionStatusLabel(session.status)} · ${formatSessionTime(session.updatedAt)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (session.summary != null) {
                                        Text(
                                            session.summary,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandSectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

private fun sessionStatusLabel(status: AgentSessionStatus): String = when (status) {
    AgentSessionStatus.RUNNING -> "Running"
    AgentSessionStatus.COMPLETED -> "Completed"
    AgentSessionStatus.FAILED -> "Failed"
    AgentSessionStatus.INTERRUPTED -> "Interrupted"
}

private fun formatSessionTime(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatElapsed(startedAtMillis: Long): String {
    val seconds = ((System.currentTimeMillis() - startedAtMillis) / 1000L).coerceAtLeast(0L)
    val minutes = seconds / 60
    return if (minutes > 0) "${minutes}m ${seconds % 60}s" else "${seconds}s"
}
