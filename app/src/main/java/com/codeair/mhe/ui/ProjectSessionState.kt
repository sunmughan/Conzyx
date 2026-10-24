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

fun EngineeringState.isTerminalBannerState(): Boolean =
    this == EngineeringState.COMPLETED ||
        this == EngineeringState.FAILED ||
        this == EngineeringState.ROLLED_BACK

private fun EngineeringState.isWorkingBannerState(): Boolean =
    this != EngineeringState.IDLE &&
        this != EngineeringState.ANALYZING &&
        !isTerminalBannerState()

/**
 * Stable identity keys for the completion event the banner would currently
 * show, or an empty set when no completion event is on display (a live,
 * still-working state, or nothing to show).
 *
 * One completion produces several keys (stored outcome, shared finish
 * timestamp, latched live snapshot) because the same event is visible
 * through more than one source: dismissing the banner acknowledges ALL of
 * them, so the event can never resurface through a different source after
 * it has been dismissed — not on scroll, tab switch, session reopen, or
 * (the keys are persisted) app restart. A later task finishes with
 * different timestamps, produces different keys, and shows normally.
 */
fun completionEventKeys(
    live: OrchestratorSnapshot?,
    outcome: TaskOutcome?,
    taskFinishedAtMillis: Long?,
): Set<String> {
    if (live != null && live.state.isWorkingBannerState()) return emptySet()
    val keys = LinkedHashSet<String>()
    if (outcome != null) {
        keys += "outcome:${outcome.state}:${outcome.finishedAtMillis}"
        if (taskFinishedAtMillis != null) keys += "finished:${outcome.state}:$taskFinishedAtMillis"
    }
    if (live != null && live.state.isTerminalBannerState()) {
        val historyTs = live.history.lastOrNull { it.state == live.state }?.timestampMillis
            ?: live.history.lastOrNull()?.timestampMillis
        keys += "live:${live.state}:${historyTs ?: 0L}:${live.currentTaskGoal ?: ""}"
        if (taskFinishedAtMillis != null) keys += "finished:${live.state}:$taskFinishedAtMillis"
    }
    return keys
}

fun bannerSnapshotFor(
    live: OrchestratorSnapshot?,
    outcome: TaskOutcome?,
    taskFinishedAtMillis: Long? = null,
    acknowledgedEventKeys: Set<String> = emptySet(),
): OrchestratorSnapshot? {
    // A live, still-working state always wins: the banner is the progress UI.
    if (live != null && live.state.isWorkingBannerState()) {
        return live
    }
    // A completion event shows unless it was already dismissed/acknowledged.
    // The stored outcome is preferred over a latched terminal live snapshot:
    // both describe the same finished task, and only the outcome carries the
    // canonical finish timestamp the auto-dismissal is keyed to.
    val eventKeys = completionEventKeys(live, outcome, taskFinishedAtMillis)
    if (eventKeys.isNotEmpty() && eventKeys.none { it in acknowledgedEventKeys }) {
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
        if (live != null && live.state.isTerminalBannerState()) return live
    }
    // No unacknowledged completion event: only a non-terminal live snapshot
    // may show (IDLE/ANALYZING render nothing in the banner composable). A
    // terminal live snapshot whose event was acknowledged must NOT fall
    // through to here — that was the v3.4.9 pinning bug.
    return if (live != null && !live.state.isTerminalBannerState()) live else null
}

/**
 * Per-chat live state for multi-chat concurrency (2026-10-24).
 *
 * Slices ([ProjectSessionState]) are keyed by PROJECT, but a user can now
 * run agents in several chats of one project at once. The active chat's live
 * fields live in [AppUiState]; when the user leaves a chat, its live fields
 * are stashed under its chatId (which is a UUID, globally unique) and
 * restored when the user returns, so background chats keep running and keep
 * their transcripts, progress, and outcomes without leaking into other chats.
 * Only chat-scoped fields are stashed: projectChats, preview, terminal, and
 * orchestrator-transition state stay with the project, never the chat.
 */
fun applyChatSliceToState(base: AppUiState, slice: ProjectSessionState, chatId: String): AppUiState =
    base.copy(
        activeChatId = chatId,
        messages = slice.messages,
        pendingApproval = slice.pendingApproval,
        changes = slice.changes,
        activity = slice.activity,
        liveProcess = slice.liveProcess,
        liveThinking = slice.liveThinking,
        activeThinkingBlockId = slice.activeThinkingBlockId,
        taskStartedAtMillis = slice.taskStartedAtMillis,
        taskFinishedAtMillis = slice.taskFinishedAtMillis,
        workSegmentStartedAtMillis = slice.workSegmentStartedAtMillis,
        currentTaskRequest = slice.currentTaskRequest,
        isRunning = slice.isRunning,
        streamingMessageId = slice.streamingMessageId,
        activeSessionId = slice.activeSessionId,
        interruptedSession = slice.interruptedSession,
        runtimeRecoveryStatus = slice.runtimeRecoveryStatus,
        runtimeRecoveryAttempt = slice.runtimeRecoveryAttempt,
        runtimeRecoveryCanResume = slice.runtimeRecoveryCanResume,
        activeRoadmap = slice.activeRoadmap,
        scratchpadItems = slice.scratchpadItems,
        lastTaskOutcome = slice.lastTaskOutcome,
    )

fun chatSliceFromState(projectId: String, chatId: String, state: AppUiState): ProjectSessionState =
    ProjectSessionState(
        projectId = projectId,
        activeChatId = chatId,
        messages = state.messages,
        pendingApproval = state.pendingApproval,
        changes = state.changes,
        activity = state.activity,
        liveProcess = state.liveProcess,
        liveThinking = state.liveThinking,
        activeThinkingBlockId = state.activeThinkingBlockId,
        taskStartedAtMillis = state.taskStartedAtMillis,
        taskFinishedAtMillis = state.taskFinishedAtMillis,
        workSegmentStartedAtMillis = state.workSegmentStartedAtMillis,
        currentTaskRequest = state.currentTaskRequest,
        isRunning = state.isRunning,
        streamingMessageId = state.streamingMessageId,
        activeSessionId = state.activeSessionId,
        interruptedSession = state.interruptedSession,
        runtimeRecoveryStatus = state.runtimeRecoveryStatus,
        runtimeRecoveryAttempt = state.runtimeRecoveryAttempt,
        runtimeRecoveryCanResume = state.runtimeRecoveryCanResume,
        activeRoadmap = state.activeRoadmap,
        scratchpadItems = state.scratchpadItems,
        lastTaskOutcome = state.lastTaskOutcome,
    )

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
