package com.codeair.mhe.collab.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeair.mhe.collab.WorkspaceCollaborationManager
import com.codeair.mhe.collab.model.CodeComment
import com.codeair.mhe.collab.model.CollaborationEvent
import com.codeair.mhe.collab.model.SharedAgentJob
import com.codeair.mhe.collab.model.SharedCheckpoint
import com.codeair.mhe.collab.model.TeamMember
import com.codeair.mhe.collab.model.TeamMemberRole

enum class CollabTab(val title: String) {
    OVERVIEW("Overview"),
    TEAM_RBAC("Team & RBAC"),
    CODE_REVIEWS("Code Reviews"),
    CHECKPOINTS_JOBS("Checkpoints & AI")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollaborationStudioScreen(
    manager: WorkspaceCollaborationManager,
    projectName: String = "Mobile-Harness-Project",
    projectId: String = "project-current",
    onClose: () -> Unit
) {
    val sessionState by manager.sessionState.collectAsState()
    val activeSession = sessionState.activeSession
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current

    // Dialog States
    var showInviteDialog by remember { mutableStateOf(false) }
    var showNewCommentDialog by remember { mutableStateOf(false) }
    var showNewCheckpointDialog by remember { mutableStateOf(false) }
    var showNewAgentJobDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header Bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Groups,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Team Collaboration Studio",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                if (activeSession != null) "${activeSession.members.size} Collaborator(s) • LiveSync ${if (activeSession.isLiveSyncEnabled) "ON" else "OFF"}"
                                else "No active sharing session",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            // Tab Navigation
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                CollabTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.title, fontSize = 13.sp) }
                    )
                }
            }

            // Content Body
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                when (CollabTab.entries[selectedTab]) {
                    CollabTab.OVERVIEW -> OverviewTabContent(
                        manager = manager,
                        sessionState = sessionState,
                        projectId = projectId,
                        projectName = projectName,
                        onOpenJoinDialog = { showJoinDialog = true }
                    )
                    CollabTab.TEAM_RBAC -> TeamRbacTabContent(
                        manager = manager,
                        sessionState = sessionState,
                        onOpenInviteDialog = { showInviteDialog = true }
                    )
                    CollabTab.CODE_REVIEWS -> CodeReviewsTabContent(
                        manager = manager,
                        comments = sessionState.pendingReviewComments,
                        onOpenNewComment = { showNewCommentDialog = true }
                    )
                    CollabTab.CHECKPOINTS_JOBS -> CheckpointsAndJobsTabContent(
                        manager = manager,
                        checkpoints = sessionState.availableCheckpoints,
                        agentJobs = sessionState.activeAgentJobs,
                        onOpenNewCheckpoint = { showNewCheckpointDialog = true },
                        onOpenNewAgentJob = { showNewAgentJobDialog = true }
                    )
                }
            }
        }
    }

    // Join Session Dialog
    if (showJoinDialog) {
        var tokenInput by remember { mutableStateOf("") }
        var nameInput by remember { mutableStateOf("") }
        var emailInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("Join Collaboration Session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Session Join Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Your Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Your Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tokenInput.isNotBlank() && nameInput.isNotBlank()) {
                            manager.joinSession(tokenInput, nameInput, emailInput)
                            showJoinDialog = false
                        }
                    }
                ) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { showJoinDialog = false }) { Text("Cancel") } }
        )
    }

    // Invite Member Dialog
    if (showInviteDialog) {
        var inviteeName by remember { mutableStateOf("") }
        var inviteeEmail by remember { mutableStateOf("") }
        var selectedRole by remember { mutableStateOf(TeamMemberRole.CONTRIBUTOR) }
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Invite Collaborator") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = inviteeName,
                        onValueChange = { inviteeName = it },
                        label = { Text("Full Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = inviteeEmail,
                        onValueChange = { inviteeEmail = it },
                        label = { Text("Email Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Select Role & Permissions:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TeamMemberRole.entries.forEach { role ->
                            FilterChip(
                                selected = selectedRole == role,
                                onClick = { selectedRole = role },
                                label = { Text(role.displayName, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inviteeName.isNotBlank() && inviteeEmail.isNotBlank()) {
                            manager.inviteMember(inviteeName, inviteeEmail, selectedRole)
                            showInviteDialog = false
                        }
                    }
                ) { Text("Send Invite") }
            },
            dismissButton = { TextButton(onClick = { showInviteDialog = false }) { Text("Cancel") } }
        )
    }

    // New Comment Dialog
    if (showNewCommentDialog) {
        var filePath by remember { mutableStateOf("MainActivity.kt") }
        var lineNum by remember { mutableStateOf("1") }
        var commentText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewCommentDialog = false },
            title = { Text("Add Code Review Comment") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = filePath,
                        onValueChange = { filePath = it },
                        label = { Text("File Path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = lineNum,
                        onValueChange = { lineNum = it },
                        label = { Text("Line Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        label = { Text("Comment / Review Feedback") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (commentText.isNotBlank()) {
                            val line = lineNum.toIntOrNull() ?: 1
                            manager.addComment(filePath, line, "Reviewer", commentText)
                            showNewCommentDialog = false
                        }
                    }
                ) { Text("Post Comment") }
            },
            dismissButton = { TextButton(onClick = { showNewCommentDialog = false }) { Text("Cancel") } }
        )
    }

    // New Checkpoint Dialog
    if (showNewCheckpointDialog) {
        var checkpointTitle by remember { mutableStateOf("") }
        var checkpointDesc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewCheckpointDialog = false },
            title = { Text("Create Collaborative Checkpoint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = checkpointTitle,
                        onValueChange = { checkpointTitle = it },
                        label = { Text("Checkpoint Milestone Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = checkpointDesc,
                        onValueChange = { checkpointDesc = it },
                        label = { Text("Description / Scope") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (checkpointTitle.isNotBlank()) {
                            manager.createCheckpoint(checkpointTitle, "Developer", checkpointDesc)
                            showNewCheckpointDialog = false
                        }
                    }
                ) { Text("Save Milestone") }
            },
            dismissButton = { TextButton(onClick = { showNewCheckpointDialog = false }) { Text("Cancel") } }
        )
    }

    // New Agent Job Dialog
    if (showNewAgentJobDialog) {
        var agentPrompt by remember { mutableStateOf("") }
        var agentRole by remember { mutableStateOf("Full-Stack Dev") }
        AlertDialog(
            onDismissRequest = { showNewAgentJobDialog = false },
            title = { Text("Dispatch Shared AI Agent") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = agentPrompt,
                        onValueChange = { agentPrompt = it },
                        label = { Text("Task Prompt / Instructions") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = agentRole,
                        onValueChange = { agentRole = it },
                        label = { Text("Specialized Agent Role") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (agentPrompt.isNotBlank()) {
                            manager.dispatchSharedAgentJob(agentPrompt, "Collaborator", agentRole)
                            showNewAgentJobDialog = false
                        }
                    }
                ) { Text("Dispatch") }
            },
            dismissButton = { TextButton(onClick = { showNewAgentJobDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun OverviewTabContent(
    manager: WorkspaceCollaborationManager,
    sessionState: com.codeair.mhe.collab.model.CollaborationSessionState,
    projectId: String,
    projectName: String,
    onOpenJoinDialog: () -> Unit
) {
    val session = sessionState.activeSession
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (session == null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No Active Collaboration Session", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Start a shared workspace to collaborate in real-time, share AI sessions, review code, and manage team checkpoints.",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { manager.createShareSession(projectId, projectName) }) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Start Shared Session")
                            }
                            OutlinedButton(onClick = onOpenJoinDialog) {
                                Text("Join with Token")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Shared Project: ${session.projectName}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("Token: ${session.shareToken}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            Button(
                                onClick = { manager.toggleLiveSync(!session.isLiveSyncEnabled) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (session.isLiveSyncEnabled) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(if (session.isLiveSyncEnabled) "LiveSync: Active" else "LiveSync: Paused", fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(session.shareUrl)) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy Link", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(session.shareToken)) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy Token", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { manager.endShareSession() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("End", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            item {
                // Metric stats row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricMiniCard("Members", "${session.members.size}", Modifier.weight(1f))
                    MetricMiniCard("Comments", "${session.comments.size}", Modifier.weight(1f))
                    MetricMiniCard("Milestones", "${session.checkpoints.size}", Modifier.weight(1f))
                    MetricMiniCard("AI Jobs", "${session.agentJobs.size}", Modifier.weight(1f))
                }
            }

            item {
                Text("Session Activity Audit Log", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }

            items(session.history.take(15)) { event ->
                AuditLogItem(event)
            }
        }
    }
}

@Composable
private fun TeamRbacTabContent(
    manager: WorkspaceCollaborationManager,
    sessionState: com.codeair.mhe.collab.model.CollaborationSessionState,
    onOpenInviteDialog: () -> Unit
) {
    val session = sessionState.activeSession
    val org = sessionState.organization

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(org.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Plan: ${org.plan.displayName} (Max ${org.plan.maxSeats} seats)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        CompactActionButton("Invite Member", onClick = onOpenInviteDialog, icon = Icons.Default.Add)
                    }
                }
            }
        }

        item {
            Text("Active Team Members & Roles", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        val members = session?.members ?: emptyList()
        if (members.isEmpty()) {
            item {
                Text("No one's here yet. Invite a teammate to review code together.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(members) { member ->
                MemberRowItem(
                    member = member,
                    onRoleChange = { newRole -> manager.setMemberRole(member.id, newRole) },
                    onRemove = { manager.removeMember(member.id) }
                )
            }
        }
    }
}

@Composable
private fun MemberRowItem(
    member: TeamMember,
    onRoleChange: (TeamMemberRole) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(member.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(member.name, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Text(member.email, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Permissions: " + listOfNotNull(
                            if (member.permissions.canEditCode) "Edit" else null,
                            if (member.permissions.canRunTerminal) "Terminal" else null,
                            if (member.permissions.canTriggerAgent) "AI Agent" else null
                        ).joinToString(", "),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when (member.role) {
                        TeamMemberRole.OWNER -> Color(0xFFF59E0B)
                        TeamMemberRole.MAINTAINER -> Color(0xFF3B82F6)
                        TeamMemberRole.CONTRIBUTOR -> Color(0xFF10B981)
                        TeamMemberRole.VIEWER -> Color(0xFF6B7280)
                    }
                ) {
                    Text(
                        member.role.displayName,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (member.role != TeamMemberRole.OWNER) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeReviewsTabContent(
    manager: WorkspaceCollaborationManager,
    comments: List<CodeComment>,
    onOpenNewComment: () -> Unit
) {
    var replyTextMap by remember { mutableStateOf(mapOf<String, String>()) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Inline Code Review Threads (${comments.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                CompactActionButton("Add Comment", onClick = onOpenNewComment, icon = Icons.Default.Add)
            }
        }

        if (comments.isEmpty()) {
            item {
                Text("Code review threads appear here when teammates comment on changes. Start the first thread to request a review.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(comments) { comment ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${comment.filePath} : Line ${comment.lineNumber}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Button(
                                onClick = { manager.resolveComment(comment.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Resolve", fontSize = 10.sp)
                            }
                        }
                        Text(
                            "${comment.author}: ${comment.commentText}",
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )

                        // Threaded replies
                        comment.replies.forEach { reply ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, top = 4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "${reply.author}: ${reply.replyText}",
                                    fontSize = 11.5.sp,
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                        }

                        // Reply input row
                        val currentDraft = replyTextMap[comment.id] ?: ""
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = currentDraft,
                                onValueChange = { replyTextMap = replyTextMap + (comment.id to it) },
                                placeholder = { Text("Reply...", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Spacer(Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    if (currentDraft.isNotBlank()) {
                                        manager.addCommentReply(comment.id, "Collaborator", currentDraft)
                                        replyTextMap = replyTextMap + (comment.id to "")
                                    }
                                }
                            ) {
                                Text("Reply", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckpointsAndJobsTabContent(
    manager: WorkspaceCollaborationManager,
    checkpoints: List<SharedCheckpoint>,
    agentJobs: List<SharedAgentJob>,
    onOpenNewCheckpoint: () -> Unit,
    onOpenNewAgentJob: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Collaborative Checkpoints (${checkpoints.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                CompactActionButton("New Checkpoint", onClick = onOpenNewCheckpoint, icon = Icons.Default.Add)
            }
        }

        if (checkpoints.isEmpty()) {
            item {
                Text("Checkpoints save the team's shared workspace state. Create one before a risky change so anyone can restore it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(checkpoints) { cp ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cp.title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            Text("By ${cp.author} • Restores: ${cp.restoreCount}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (cp.description.isNotBlank()) {
                                Text(cp.description, fontSize = 11.5.sp)
                            }
                        }
                        OutlinedButton(onClick = { manager.restoreCheckpoint(cp.id) }) {
                            Text("Restore", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Shared AI Agent Sessions (${agentJobs.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                CompactActionButton("Dispatch Agent", onClick = onOpenNewAgentJob, icon = Icons.Default.SmartToy)
            }
        }

        if (agentJobs.isEmpty()) {
            item {
                Text("Shared agent sessions let the whole team watch an AI work on the same task. Dispatch one to get started.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(agentJobs) { job ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Role: ${job.assignedRole} • Initiator: ${job.initiatedBy}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = when (job.status) {
                                    "COMPLETED" -> Color(0xFF10B981)
                                    "REQUIRES_APPROVAL" -> Color(0xFFF59E0B)
                                    "FAILED" -> Color(0xFFEF4444)
                                    else -> Color(0xFF3B82F6)
                                }
                            ) {
                                Text(
                                    job.status,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(job.prompt, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 4.dp))
                        if (job.status == "REQUIRES_APPROVAL") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = { manager.approveAgentJob(job.id, "Owner") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                                ) {
                                    Text("Approve Action", fontSize = 11.sp)
                                }
                            }
                        }
                        if (job.logs.isNotEmpty()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                color = Color(0xFF14171E),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    job.logs.takeLast(4).forEach { line ->
                                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFFE2E8F0))
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
private fun CompactActionButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(42.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, maxLines = 1, fontSize = 12.sp)
    }
}

@Composable
private fun MetricMiniCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
            Text(title, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AuditLogItem(event: CollaborationEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            modifier = Modifier.size(8.dp)
        ) {}
        Spacer(Modifier.width(10.dp))
        Text(
            "${event.actorName}: ${event.description}",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
