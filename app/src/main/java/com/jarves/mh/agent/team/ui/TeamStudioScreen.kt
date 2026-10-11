package com.jarves.mh.agent.team.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.agent.team.AgentTeamOrchestrator
import com.jarves.mh.agent.team.model.ArtifactType
import com.jarves.mh.agent.team.model.ReviewVerdict
import com.jarves.mh.agent.team.model.TeamArtifact
import com.jarves.mh.agent.team.model.TeamMessage
import com.jarves.mh.agent.team.model.TeamMessageType
import com.jarves.mh.agent.team.model.TeamRole
import com.jarves.mh.agent.team.model.TeamRolePermissions
import com.jarves.mh.agent.team.model.TeamTask
import com.jarves.mh.agent.team.model.TeamTaskPriority
import com.jarves.mh.agent.team.model.TeamTaskStatus

/**
 * Multi-Agent Engineering Studio Dashboard (Phase 11 v2.2).
 * 5-tab visual studio covering Roster, Task DAG, Inter-Agent Comms, Shared Blackboard, and Review/Budget.
 */
@Composable
fun TeamStudioScreen(
    orchestrator: AgentTeamOrchestrator,
    onClose: () -> Unit = {},
    onDispatchToAgent: (String) -> Unit = {}
) {
    val sessionState by orchestrator.sessionState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var inputGoal by remember { mutableStateOf(sessionState.activeGoal.ifBlank { "Build robust offline mobile feature with testing and CI" }) }

    val tabs = listOf("Roster", "Task DAG", "Comms", "Blackboard", "Review & Budget")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Surface(
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Groups,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Multi-Agent Engineering Studio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Phase 11 — 10 Specialized Autonomous Agents",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Goal Input & Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputGoal,
                        onValueChange = { inputGoal = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Enter engineering goal...", fontSize = 12.sp) },
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = {
                            if (inputGoal.isNotBlank()) {
                                orchestrator.planProject(inputGoal)
                                onDispatchToAgent("Multi-Agent Team Goal: $inputGoal")
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Plan", fontSize = 12.sp)
                    }

                    if (sessionState.isRunning) {
                        Button(
                            onClick = { orchestrator.runNextReadyTask() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Step", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { orchestrator.cancelSession() },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text("Stop", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Navigation Tabs
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // Tab Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            when (selectedTab) {
                0 -> TeamRosterTab(orchestrator = orchestrator)
                1 -> TeamDagTab(orchestrator = orchestrator)
                2 -> TeamCommsTab(orchestrator = orchestrator)
                3 -> TeamBlackboardTab(orchestrator = orchestrator)
                4 -> TeamReviewBudgetTab(orchestrator = orchestrator)
            }
        }
    }
}

/**
 * Tab 1: Team Roster with all 10 specialized agent roles.
 */
@Composable
private fun TeamRosterTab(orchestrator: AgentTeamOrchestrator) {
    val sessionState by orchestrator.sessionState.collectAsState()
    val usages = orchestrator.budgetTracker.getAllRoleUsages()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(TeamRole.entries) { role ->
            val isActive = sessionState.activeRole == role
            val permissions = TeamRolePermissions.forRole(role)
            val usage = usages[role]

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    width = if (isActive) 1.5.dp else 1.dp,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = when (role) {
                                            TeamRole.MANAGER -> Icons.Default.Groups
                                            TeamRole.ARCHITECT -> Icons.Default.AccountTree
                                            TeamRole.SECURITY_REVIEWER -> Icons.Default.Security
                                            else -> Icons.Default.Engineering
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = role.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = role.name,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (isActive) "ACTIVE" else "IDLE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = role.description,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))
                    // Permission Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (permissions.canWriteCode) ChipTag("Code Edit")
                        if (permissions.canRunTerminal) ChipTag("Terminal")
                        if (permissions.canAccessNetwork) ChipTag("Network")
                        if (permissions.canCommitGit) ChipTag("Git Release")
                        if (permissions.canApproveReviews) ChipTag("Review Sign-off")
                    }

                    if (usage != null && usage.tokensUsed > 0L) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Used: ${usage.tokensUsed} tokens • Tasks: ${usage.tasksCompleted} • Cost: $${usage.costUsd}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tab 2: Task Dependency DAG & Kanban State.
 */
@Composable
private fun TeamDagTab(orchestrator: AgentTeamOrchestrator) {
    val sessionState by orchestrator.sessionState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Summary Header & Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DAG Execution Pipeline (${sessionState.tasks.count { it.status == TeamTaskStatus.COMPLETED }}/${sessionState.tasks.size} Completed)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { orchestrator.runNextReadyTask() },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Next Ready", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { orchestrator.autoRunAll() },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Auto Run", fontSize = 11.sp)
                }
            }
        }

        // Conflict Warning Banner if any
        if (sessionState.conflictWarnings.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFFF3E0),
                border = BorderStroke(1.dp, Color(0xFFFFB74D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF57C00), modifier = Modifier.size(18.dp))
                    Text(
                        text = sessionState.conflictWarnings.first(),
                        fontSize = 11.sp,
                        color = Color(0xFFE65100)
                    )
                }
            }
        }

        // Task List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sessionState.tasks, key = { it.id }) { task ->
                TaskDagCard(
                    task = task,
                    onToggleStatus = {
                        val next = when (task.status) {
                            TeamTaskStatus.PENDING -> TeamTaskStatus.READY
                            TeamTaskStatus.READY -> TeamTaskStatus.IN_PROGRESS
                            TeamTaskStatus.IN_PROGRESS -> TeamTaskStatus.COMPLETED
                            TeamTaskStatus.UNDER_REVIEW -> TeamTaskStatus.COMPLETED
                            TeamTaskStatus.COMPLETED -> TeamTaskStatus.READY
                            TeamTaskStatus.BLOCKED -> TeamTaskStatus.READY
                            TeamTaskStatus.FAILED -> TeamTaskStatus.READY
                        }
                        orchestrator.updateTaskStatus(task.id, next)
                    }
                )
            }
        }
    }
}

@Composable
private fun TaskDagCard(
    task: TeamTask,
    onToggleStatus: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (task.status) {
                TeamTaskStatus.COMPLETED -> Color(0xFFE8F5E9)
                TeamTaskStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                TeamTaskStatus.READY -> Color(0xFFE3F2FD)
                TeamTaskStatus.BLOCKED, TeamTaskStatus.FAILED -> Color(0xFFFFEBEE)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = task.assignedRole.name,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    if (task.priority == TeamTaskPriority.CRITICAL) {
                        Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFD32F2F)) {
                            Text("CRITICAL", fontSize = 8.sp, color = Color.White, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }

                IconButton(onClick = onToggleStatus, modifier = Modifier.size(24.dp)) {
                    when (task.status) {
                        TeamTaskStatus.COMPLETED -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                        TeamTaskStatus.IN_PROGRESS -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        TeamTaskStatus.READY -> Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF1976D2))
                        else -> Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(text = task.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(text = task.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (task.affectedFiles.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    task.affectedFiles.forEach { file ->
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(file, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab 3: Inter-Agent Communication Bus.
 */
@Composable
private fun TeamCommsTab(orchestrator: AgentTeamOrchestrator) {
    val sessionState by orchestrator.sessionState.collectAsState()
    var filterRole by remember { mutableStateOf<TeamRole?>(null) }
    var inputMessage by remember { mutableStateOf("") }

    val filteredMessages = sessionState.messages.filter { msg ->
        filterRole == null || msg.fromRole == filterRole || msg.toRole == filterRole
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Role Filter Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = filterRole == null,
                onClick = { filterRole = null },
                label = { Text("All Messages", fontSize = 11.sp) }
            )
            TeamRole.entries.forEach { role ->
                FilterChip(
                    selected = filterRole == role,
                    onClick = { filterRole = if (filterRole == role) null else role },
                    label = { Text(role.title.substringBefore(' '), fontSize = 11.sp) }
                )
            }
        }

        // Messages List
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredMessages, key = { it.id }) { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${msg.fromRole.name} → ${msg.toRole?.name ?: "TEAM"}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(msg.type.name, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(text = msg.content, fontSize = 11.sp)
                    }
                }
            }
        }

        // Send Message Field
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputMessage,
                onValueChange = { inputMessage = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Broadcast note to team...", fontSize = 11.sp) },
                shape = RoundedCornerShape(8.dp)
            )
            IconButton(
                onClick = {
                    if (inputMessage.isNotBlank()) {
                        orchestrator.broadcastMessage(TeamRole.MANAGER, null, inputMessage)
                        inputMessage = ""
                    }
                }
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Tab 4: Centralized Shared Blackboard Artifacts.
 */
@Composable
private fun TeamBlackboardTab(orchestrator: AgentTeamOrchestrator) {
    val sessionState by orchestrator.sessionState.collectAsState()
    var filterType by remember { mutableStateOf<ArtifactType?>(null) }

    val filteredArtifacts = sessionState.artifacts.filter {
        filterType == null || it.type == filterType
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Artifact Filter Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = filterType == null,
                onClick = { filterType = null },
                label = { Text("All Artifacts", fontSize = 11.sp) }
            )
            ArtifactType.entries.forEach { type ->
                FilterChip(
                    selected = filterType == type,
                    onClick = { filterType = if (filterType == type) null else type },
                    label = { Text(type.name, fontSize = 11.sp) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredArtifacts, key = { it.id }) { artifact ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = artifact.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text("v${artifact.version}", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Author: ${artifact.authorRole.title} • Type: ${artifact.type.label}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = artifact.content,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab 5: Review Gatekeeper & Budget Quota Controls.
 */
@Composable
private fun TeamReviewBudgetTab(orchestrator: AgentTeamOrchestrator) {
    val sessionState by orchestrator.sessionState.collectAsState()
    val budget = sessionState.budget

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Budget & Quota Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Resource Quota & Token Budget",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                val progress = if (budget.tokenQuota > 0) (budget.totalTokensUsed.toFloat() / budget.tokenQuota).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = if (budget.isQuotaExceeded) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${budget.totalTokensUsed} / ${budget.tokenQuota} tokens", fontSize = 11.sp)
                    Text("Total Cost: $${budget.totalCostUsd}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text("Elapsed: ${budget.elapsedTimeMs / 1000}s • Limit: ${budget.maxExecutionTimeMs / 1000}s", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Review Sign-Offs Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Review Gatekeeper Sign-offs (${sessionState.reviews.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                if (sessionState.reviews.isEmpty()) {
                    Text("No review records logged yet. Gates unlock upon peer review.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    sessionState.reviews.forEach { rev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${rev.reviewerRole.title} (${rev.reviewerRole.name})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(rev.comments, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = when (rev.verdict) {
                                    ReviewVerdict.APPROVED -> Color(0xFF2E7D32)
                                    ReviewVerdict.CHANGES_REQUESTED -> Color(0xFFF57C00)
                                    ReviewVerdict.REJECTED -> Color(0xFFD32F2F)
                                    ReviewVerdict.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = rev.verdict.name,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipTag(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
