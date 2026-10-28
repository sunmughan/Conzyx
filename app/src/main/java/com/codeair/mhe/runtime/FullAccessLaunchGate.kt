package com.codeair.mhe.runtime

import com.codeair.mhe.model.RiskLevel
import com.codeair.mhe.model.RuntimeEvent
import com.codeair.mhe.model.ToolRequest
import com.codeair.mhe.security.PermissionLevel
import com.codeair.mhe.security.SafetyGateway
import com.codeair.mhe.security.SafetyOperation
import com.codeair.mhe.security.SafetyVerdict
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

internal class FullAccessLaunchGate(
    private val safetyGateway: SafetyGateway,
) {
    private data class PendingLaunch(
        val operation: SafetyOperation,
        val sessionId: String,
        val projectId: String?,
        /** Conversation key (project slug + chat id) or null when unknown. */
        val chatId: String?,
        val gate: CompletableDeferred<Boolean>,
    )

    private val pending = ConcurrentHashMap<String, PendingLaunch>()

    suspend fun authorizeLaunch(
        agentLabel: String,
        sessionId: String,
        projectId: String?,
        operation: SafetyOperation,
        explanation: String,
        affectedPaths: List<String>,
        interactive: Boolean,
        emit: suspend (RuntimeEvent) -> Unit,
        chatId: String? = null,
    ): Boolean {
        val decision = safetyGateway.authorize(operation, sessionId = sessionId, projectId = projectId, chatId = chatId)
        if (decision.verdict == SafetyVerdict.DENY) {
            emit(RuntimeEvent.SessionFailed(sessionId, "$agentLabel launch refused: ${decision.reason}"))
            return false
        }
        if (decision.verdict == SafetyVerdict.ALLOW && decision.level != null) {
            return true
        }
        if (!interactive) {
            emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "$agentLabel runs with full access and needs your acknowledgment. Launch it from the main chat instead.",
                ),
            )
            return false
        }
        val request = ToolRequest(
            sessionId = sessionId,
            toolName = agentLabel,
            explanation = explanation,
            affectedPaths = affectedPaths,
            commandPreview = operation.describe(),
            risk = RiskLevel.HIGH,
        )
        val gate = CompletableDeferred<Boolean>()
        pending[request.approvalId] = PendingLaunch(operation, sessionId, projectId, chatId, gate)
        emit(RuntimeEvent.ToolRequested(sessionId, request))
        val approved = try {
            gate.await()
        } finally {
            pending.remove(request.approvalId)
        }
        emit(
            if (approved) RuntimeEvent.ToolApproved(sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(sessionId, request.approvalId),
        )
        if (!approved) {
            emit(RuntimeEvent.SessionFailed(sessionId, "$agentLabel launch was not approved."))
        }
        return approved
    }

    /**
     * Completes a pending launch. The recorded grant keeps the level the
     * user actually picked: a Chat approval records a CHAT grant (it must
     * NOT be downgraded to a plain SESSION grant, or the next task run in
     * the same conversation would prompt again). Defaults to SESSION for
     * callers that do not carry a level (e.g. other bridges' tool paths).
     */
    fun respondToApproval(approvalId: String, approved: Boolean, level: PermissionLevel = PermissionLevel.SESSION): Boolean {
        val launch = pending.remove(approvalId) ?: return false
        if (approved) {
            // Bind the grant to exactly the approved launch operation's
            // target (e.g. its workspace), never to a wildcard: a later
            // launch outside that scope must show the launch card again.
            safetyGateway.recordGrant(
                launch.operation,
                level,
                sessionId = launch.sessionId,
                projectId = launch.projectId,
                targetPattern = safetyGateway.narrowPatternFor(launch.operation),
                chatId = launch.chatId,
            )
        }
        launch.gate.complete(approved)
        return true
    }

    private data class PendingCommand(
        val operation: SafetyOperation.ShellExec,
        val sessionId: String,
        val projectId: String?,
        val chatId: String?,
        val gate: CompletableDeferred<Boolean>,
    )

    private val pendingCommands = ConcurrentHashMap<String, PendingCommand>()

    /**
     * Per-command approval for a single model shell command. Unlike the
     * launch gate, a baseline ALLOW executes without a card: only a PROMPT
     * verdict (e.g. a HIGH-risk command) surfaces a card, and a non-
     * interactive session fails closed instead of executing.
     * Returns true only when the command may execute.
     */
    suspend fun authorizeShellCommand(
        operation: SafetyOperation.ShellExec,
        sessionId: String,
        projectId: String?,
        emit: suspend (RuntimeEvent) -> Unit,
        chatId: String? = null,
        interactive: Boolean = true,
    ): Boolean {
        val decision = safetyGateway.authorize(operation, sessionId = sessionId, projectId = projectId, chatId = chatId)
        if (decision.verdict == SafetyVerdict.DENY) return false
        if (decision.verdict == SafetyVerdict.ALLOW) return true
        if (!interactive) return false
        val request = ToolRequest(
            sessionId = sessionId,
            toolName = "run_shell",
            explanation = decision.reason,
            affectedPaths = emptyList(),
            commandPreview = operation.describe(),
            risk = RiskLevel.HIGH,
        )
        val gate = CompletableDeferred<Boolean>()
        pendingCommands[request.approvalId] = PendingCommand(operation, sessionId, projectId, chatId, gate)
        emit(RuntimeEvent.ToolRequested(sessionId, request))
        val approved = try {
            gate.await()
        } finally {
            pendingCommands.remove(request.approvalId)
        }
        emit(
            if (approved) RuntimeEvent.ToolApproved(sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(sessionId, request.approvalId),
        )
        return approved
    }

    /**
     * Completes a pending per-command approval. An approval records a grant
     * bound to exactly this command string, so it never widens to other
     * commands in the session or chat.
     */
    fun respondToCommandApproval(approvalId: String, approved: Boolean, level: PermissionLevel = PermissionLevel.SESSION): Boolean {
        val pendingCommand = pendingCommands.remove(approvalId) ?: return false
        if (approved) {
            safetyGateway.recordGrant(
                pendingCommand.operation,
                level,
                sessionId = pendingCommand.sessionId,
                projectId = pendingCommand.projectId,
                targetPattern = safetyGateway.narrowPatternFor(pendingCommand.operation),
                chatId = pendingCommand.chatId,
            )
        }
        pendingCommand.gate.complete(approved)
        return true
    }

    fun abortPending() {
        val stale = pending.values.toList()
        pending.clear()
        stale.forEach { it.gate.complete(false) }
        val staleCommands = pendingCommands.values.toList()
        pendingCommands.clear()
        staleCommands.forEach { it.gate.complete(false) }
    }

    /** Aborts only [sessionId]'s pending launch approvals (multi-session
     * isolation: stopping one chat's task must not deny another chat's
     * pending approval). */
    fun abortPendingForSession(sessionId: String) {
        val stale = pending.entries.filter { it.value.sessionId == sessionId }
        stale.forEach { (key, launch) ->
            if (pending.remove(key, launch)) launch.gate.complete(false)
        }
        val staleCommands = pendingCommands.entries.filter { it.value.sessionId == sessionId }
        staleCommands.forEach { (key, command) ->
            if (pendingCommands.remove(key, command)) command.gate.complete(false)
        }
    }
}
