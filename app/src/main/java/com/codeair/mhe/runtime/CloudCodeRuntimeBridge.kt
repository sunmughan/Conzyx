package com.codeair.mhe.runtime

import android.content.Context
import com.codeair.mhe.model.ChatMessage
import com.codeair.mhe.model.ChangeItem
import com.codeair.mhe.model.ProjectKind
import com.codeair.mhe.model.ProviderProfile
import com.codeair.mhe.model.RuntimeEvent
import com.codeair.mhe.model.ToolRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Runtime bridge for Cloud Code agent.
 *
 * Cloud Code is a CLI-based coding agent. This bridge provides the lifecycle
 * plumbing; session execution delegates to the shared CLI execution path.
 * Multi-account failover is handled at the auth layer via [CloudCodeAuthController].
 */
class CloudCodeRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {

    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    override val supportsSessionRecovery: Boolean = true

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String {
        val sessionId = java.util.UUID.randomUUID().toString()
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        return sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // Cloud Code approvals handled via CLI interactive flow.
    }

    override suspend fun stopSession(sessionId: String) {
        eventBus.emit(RuntimeEvent.SessionStopped(sessionId))
    }

    override suspend fun stopActiveSession() {
        // No-op: session-scoped stops go through stopSession.
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = false

    override suspend fun acceptLastChanges(projectId: String) {
        // No-op for Cloud Code.
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = emptyList()

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = false

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = false
}
