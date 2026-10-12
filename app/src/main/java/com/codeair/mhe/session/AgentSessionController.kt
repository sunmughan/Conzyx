package com.codeair.mhe.session

import android.content.Context
import com.codeair.mhe.AppCrashLogger
import com.codeair.mhe.model.ActiveRoadmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AgentSessionDomainState(
    val activeSession: PersistedAgentSession? = null,
    val isRunning: Boolean = false,
    val interruptedSession: PersistedAgentSession? = null,
    val activeRoadmap: ActiveRoadmap? = null,
    val lastError: String? = null,
)

/**
 * Domain controller for managing Agent sessions, persistence across app lifecycles,
 * cancellation, timeouts, and crash recovery.
 */
class AgentSessionController(
    private val context: Context,
    private val sessionManager: AgentSessionManager = AgentSessionManager(context),
) {
    private val _state = MutableStateFlow(AgentSessionDomainState())
    val state: StateFlow<AgentSessionDomainState> = _state.asStateFlow()

    init {
        // Detect interrupted session upon controller initialization
        val interrupted = sessionManager.detectInterruptedSession()
        if (interrupted != null) {
            _state.update { it.copy(interruptedSession = interrupted) }
        }
    }

    fun startSession(
        sessionId: String,
        projectId: String,
        projectTitle: String,
        chatId: String,
        prompt: String,
        roadmap: ActiveRoadmap? = null,
    ) {
        sessionManager.recordSessionStart(
            sessionId = sessionId,
            projectId = projectId,
            projectTitle = projectTitle,
            chatId = chatId,
            prompt = prompt,
            roadmap = roadmap,
        )
        val session = PersistedAgentSession(
            sessionId = sessionId,
            projectId = projectId,
            projectTitle = projectTitle,
            chatId = chatId,
            initialPrompt = prompt,
            startedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            status = AgentSessionStatus.RUNNING,
            activeRoadmap = roadmap,
        )
        _state.update {
            it.copy(
                activeSession = session,
                isRunning = true,
                interruptedSession = null,
                activeRoadmap = roadmap,
                lastError = null,
            )
        }
    }

    fun updateProgress(roadmap: ActiveRoadmap?, summary: String? = null) {
        sessionManager.updateSessionProgress(roadmap, summary)
        _state.update {
            it.copy(
                activeRoadmap = roadmap ?: it.activeRoadmap,
                activeSession = it.activeSession?.copy(
                    updatedAt = System.currentTimeMillis(),
                    activeRoadmap = roadmap ?: it.activeRoadmap,
                    summary = summary ?: it.activeSession?.summary,
                ),
            )
        }
    }

    fun completeSession(summary: String? = null) {
        sessionManager.recordSessionCompleted(summary)
        _state.update {
            it.copy(
                activeSession = null,
                isRunning = false,
                lastError = null,
            )
        }
    }

    fun failSession(reason: String) {
        sessionManager.recordSessionFailed(reason)
        _state.update {
            it.copy(
                activeSession = null,
                isRunning = false,
                lastError = reason,
            )
        }
    }

    fun cancelSession() {
        sessionManager.recordSessionCancelled()
        _state.update {
            it.copy(
                activeSession = null,
                isRunning = false,
                lastError = "Cancelled by user",
            )
        }
    }

    fun timeoutSession(timeoutMs: Long) {
        sessionManager.recordSessionTimeout(timeoutMs)
        _state.update {
            it.copy(
                activeSession = null,
                isRunning = false,
                lastError = "Session timed out",
            )
        }
    }

    fun dismissInterruptedSession() {
        sessionManager.clearActiveSession()
        _state.update { it.copy(interruptedSession = null) }
    }

    fun listHistory(): List<PersistedAgentSession> = sessionManager.listSessionHistory()

    fun cleanupOldSessions(days: Int = 14): Int = sessionManager.cleanupOldSessions(days)
}
