package com.codeair.mhe.ui

import com.codeair.mhe.model.ActivityItem
import com.codeair.mhe.model.ActiveRoadmap
import com.codeair.mhe.model.ChangeItem
import com.codeair.mhe.model.ChatMessage
import com.codeair.mhe.model.ProjectChat
import com.codeair.mhe.model.ScratchpadItem
import com.codeair.mhe.model.ToolRequest
import com.codeair.mhe.session.EngineeringState
import com.codeair.mhe.session.OrchestratorSnapshot
import com.codeair.mhe.session.PersistedAgentSession

data class OrchestratorTransition(
    val state: EngineeringState,
    val stepTitle: String,
    val details: String? = null,
)

data class TaskOutcome(
    val state: EngineeringState,
    val title: String,
    val finishedAtMillis: Long,
)

fun bannerSnapshotFor(
    live: OrchestratorSnapshot?,
    outcome: TaskOutcome?,
): OrchestratorSnapshot? {
    if (live != null && live.state != EngineeringState.IDLE && live.state != EngineeringState.ANALYZING) {
        return live
    }
    if (outcome != null) {
        return OrchestratorSnapshot(
            state = outcome.state,
            currentIteration = 0,
            maxIterations = 0,
            planSteps = emptyList(),
            currentStepIndex = 0,
            history = emptyList(),
            currentTaskGoal = outcome.title,
        )
    }
    return live
}

data class ProjectSessionState(
    val projectId: String,
    val messages: List<ChatMessage> = emptyList(),
    val projectChats: List<ProjectChat> = emptyList(),
    val activeChatId: String? = null,
    val pendingApproval: ToolRequest? = null,
    val changes: List<ChangeItem> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val liveProcess: List<ActivityItem> = emptyList(),
    val liveThinking: Boolean = false,
    val activeThinkingBlockId: Long? = null,
    val taskStartedAtMillis: Long? = null,
    val taskFinishedAtMillis: Long? = null,
    val workSegmentStartedAtMillis: Long? = null,
    val currentTaskRequest: String? = null,
    val previewReady: Boolean = false,
    val previewUrl: String? = null,
    val isRunning: Boolean = false,
    val streamingMessageId: String? = null,
    val activeSessionId: String? = null,
    val interruptedSession: PersistedAgentSession? = null,
    val runtimeRecoveryStatus: String? = null,
    val runtimeRecoveryAttempt: Int = 0,
    val runtimeRecoveryCanResume: Boolean = false,
    val activeRoadmap: ActiveRoadmap? = null,
    val scratchpadItems: List<ScratchpadItem> = emptyList(),
    val pendingOrchestratorTransition: OrchestratorTransition? = null,
    val lastTaskOutcome: TaskOutcome? = null,
)
