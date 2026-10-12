package com.codeair.mhe.agent.team.runtime

import com.codeair.mhe.agent.team.model.ArtifactType
import com.codeair.mhe.agent.team.model.ReviewVerdict
import com.codeair.mhe.agent.team.model.TeamArtifact
import com.codeair.mhe.agent.team.model.TeamRole
import com.codeair.mhe.agent.team.model.TeamTask
import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.core.result.OperationStatus
import com.codeair.mhe.model.AgentKind
import com.codeair.mhe.model.ProjectKind
import com.codeair.mhe.model.ProviderProfile
import com.codeair.mhe.model.RuntimeEvent
import com.codeair.mhe.runtime.RuntimeBridge
import com.codeair.mhe.runtime.RuntimeInstaller
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Genuine per-role agent executor for the Multi-Agent Engineering Studio.
 *
 * Each team task is REALLY executed: the role's prompt (system prompt + task +
 * dependency outputs + blackboard summary) is dispatched through the app's real
 * agent runtime ([RuntimeBridge.startSession]), the streamed assistant output is
 * captured, and the outcome is mapped from the real terminal events
 * ([RuntimeEvent.SessionCompleted] / [RuntimeEvent.SessionFailed]).
 *
 * Integrity contract (hard): SUCCESS is reported only when the agent genuinely
 * ran and the bridge emitted [RuntimeEvent.SessionCompleted]. Missing runtime,
 * missing provider/secret, failures and timeouts surface as FAILED or
 * NOT_CONFIGURED. Output is never fabricated.
 *
 * Notes and honest limitations:
 * - Runtime bridges are single-active-session; executions are serialized per
 *   [AgentKind] with a mutex, so concurrent DAG stages run sequentially.
 * - The agent works in a dedicated team workspace under [workspaceBaseDir]
 *   (one directory per team goal). A projectDir bound on the orchestrator is
 *   currently always null; binding an existing project directory is future work.
 * - A user-initiated chat session on the same bridge while a team run is
 *   active will clobber bridge state; team runs should not overlap manual chats.
 */
class RealTeamAgentExecutor(
    private val workspaceBaseDir: File,
    private val bridges: Map<AgentKind, RuntimeBridge>,
    private val installer: RuntimeInstaller,
    private val providerFor: (AgentKind) -> ProviderProfile?,
    private val secretFor: (ProviderProfile) -> String?,
) : TeamAgentExecutor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bridgeLocks = ConcurrentHashMap<AgentKind, Mutex>()

    override suspend fun execute(task: TeamTask, context: TeamExecutionContext): TeamExecutionResult {
        val startedAt = System.currentTimeMillis()
        val kind = context.agentKind

        val bridge = bridges[kind]
            ?: return TeamExecutionResult(
                result = OperationResult.notConfigured("No runtime bridge registered for ${kind.title}."),
                outputText = "",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        if (!installer.isAgentInstalled(kind)) {
            return TeamExecutionResult(
                result = OperationResult.notConfigured(
                    "The '${kind.title}' runtime is not installed. Install it from Agent settings first."
                ),
                outputText = "",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
        val provider = providerFor(kind)
            ?: return TeamExecutionResult(
                result = OperationResult.notConfigured("No provider configured for ${kind.title}."),
                outputText = "",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        if (secretFor(provider).isNullOrBlank()) {
            return TeamExecutionResult(
                result = OperationResult.notConfigured(
                    "No API key or token saved for ${provider.kind.title}. Add one from Agent settings first."
                ),
                outputText = "",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

        val goalHash = sha1Hex(context.goal).take(12)
        val projectId = "team-$goalHash"
        val projectSlug = "team-${goalHash.take(8)}"
        val workspace = File(workspaceBaseDir, projectId).apply { mkdirs() }
        val prompt = buildPrompt(task, context)

        val lock = bridgeLocks.getOrPut(kind) { Mutex() }
        return lock.withLock {
            runSession(
                bridge = bridge,
                projectId = projectId,
                projectSlug = projectSlug,
                prompt = prompt,
                provider = provider,
                timeoutMs = context.timeoutMs.coerceAtLeast(60_000L),
                startedAt = startedAt,
                task = task,
                context = context,
                workspace = workspace,
            )
        }
    }

    private suspend fun runSession(
        bridge: RuntimeBridge,
        projectId: String,
        projectSlug: String,
        prompt: String,
        provider: ProviderProfile,
        timeoutMs: Long,
        startedAt: Long,
        task: TeamTask,
        context: TeamExecutionContext,
        workspace: File,
    ): TeamExecutionResult {
        val captured = ConcurrentLinkedQueue<RuntimeEvent>()
        val collector = scope.launch {
            bridge.events.collect { captured.add(it) }
        }

        val sessionId: String
        try {
            val result = withTimeout(timeoutMs) {
                bridge.startSession(
                    projectId = projectId,
                    projectSlug = projectSlug,
                    projectKind = ProjectKind.PROJECT,
                    prompt = prompt,
                    conversationHistory = emptyList(),
                    provider = provider,
                )
            }
            sessionId = result
        } catch (e: TimeoutCancellationException) {
            collector.cancel()
            runCatching { bridge.stopActiveSession() }
            return TeamExecutionResult(
                result = OperationResult.failure(
                    error = "Agent '${context.role.title}' timed out after ${timeoutMs / 1000}s on '${task.title}'.",
                    logs = listOf("timeoutMs=$timeoutMs"),
                    startedAt = startedAt,
                ),
                outputText = "",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } catch (e: Exception) {
            collector.cancel()
            return TeamExecutionResult(
                result = OperationResult.failure(
                    error = "Agent '${context.role.title}' failed to start: ${e.message}",
                    startedAt = startedAt,
                ),
                outputText = "",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
        collector.cancel()

        val ours = captured.filter { it.sessionId == sessionId }
        val outputText = ours
            .filterIsInstance<RuntimeEvent.AssistantDelta>()
            .joinToString(separator = "") { it.text }
            .trim()
        val failed = ours.filterIsInstance<RuntimeEvent.SessionFailed>().firstOrNull()
        val completed = ours.filterIsInstance<RuntimeEvent.SessionCompleted>().firstOrNull()
        val durationMs = System.currentTimeMillis() - startedAt

        if (failed != null) {
            return TeamExecutionResult(
                result = OperationResult.failure(
                    error = "Agent '${context.role.title}' reported failure on '${task.title}': ${failed.reason}",
                    logs = outputText.lines().takeLast(20),
                    startedAt = startedAt,
                ),
                outputText = outputText,
                durationMs = durationMs,
            )
        }
        if (completed == null) {
            return TeamExecutionResult(
                result = OperationResult.failure(
                    error = "Agent '${context.role.title}' ended without a completion signal on '${task.title}'.",
                    logs = outputText.lines().takeLast(20),
                    startedAt = startedAt,
                ),
                outputText = outputText,
                durationMs = durationMs,
            )
        }

        val artifacts = if (outputText.isNotBlank()) {
            listOf(
                TeamArtifact(
                    id = UUID.randomUUID().toString(),
                    title = "${context.role.title}: ${task.title}",
                    type = defaultArtifactTypeForRole(context.role),
                    authorRole = context.role,
                    content = outputText,
                    tags = setOf("team-run", projectId),
                    metadata = mapOf(
                        "workspace" to workspace.canonicalPath,
                        "agentKind" to context.agentKind.stableId,
                    ),
                    isSimulated = false,
                )
            )
        } else {
            emptyList()
        }

        return TeamExecutionResult(
            result = OperationResult.success(
                logs = outputText.lines().takeLast(20),
                verificationEvidence = "RuntimeEvent.SessionCompleted for session $sessionId",
                startedAt = startedAt,
            ),
            outputText = outputText.ifBlank { "Task completed with no text output." },
            artifacts = artifacts,
            reviewVerdict = parseReviewVerdict(outputText, context.role),
            durationMs = durationMs,
        )
    }

    private fun buildPrompt(task: TeamTask, context: TeamExecutionContext): String {
        val sb = StringBuilder()
        val systemPrompt = context.roleConfig.systemPrompt.ifBlank {
            defaultSystemPromptFor(context.role)
        }
        sb.appendLine(systemPrompt.trim())
        sb.appendLine()
        sb.appendLine("You are the ${context.role.title} on an autonomous engineering team.")
        sb.appendLine("Team goal: ${context.goal}")
        sb.appendLine("Your task: ${task.title}")
        if (task.description.isNotBlank()) sb.appendLine("Details: ${task.description}")
        if (task.affectedFiles.isNotEmpty()) {
            sb.appendLine("Files in scope: ${task.affectedFiles.joinToString(", ")}")
        }
        if (context.dependencyOutputs.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Outputs from dependency tasks you must build on:")
            context.dependencyOutputs.entries.forEachIndexed { index, (id, output) ->
                sb.appendLine("--- Dependency ${index + 1} ($id) ---")
                sb.appendLine(output.take(4000))
            }
        }
        if (context.blackboardSummary.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("Shared blackboard summary:")
            sb.appendLine(context.blackboardSummary.take(4000))
        }
        sb.appendLine()
        sb.appendLine(
            "Work directly in the project workspace: write real files and run real commands. " +
                "Keep chat output concise; put substantive deliverables in files. " +
                "When finished, summarize what you did and which files you changed. " +
                "If you are a reviewer role, end your output with an explicit verdict line: " +
                "VERDICT: APPROVED or VERDICT: CHANGES_REQUESTED or VERDICT: REJECTED."
        )
        return sb.toString()
    }

    private fun defaultSystemPromptFor(role: TeamRole): String = when (role) {
        TeamRole.MANAGER -> "You are an engineering manager. Break goals into concrete deliverables and keep scope tight."
        TeamRole.ARCHITECT -> "You are a software architect. Design clean module boundaries, interfaces, and data schemas."
        TeamRole.DEVELOPER -> "You are a fullstack developer. Write clean, working code with sensible structure."
        TeamRole.UI_ENGINEER -> "You are a UI/UX specialist. Build polished, accessible interfaces."
        TeamRole.BACKEND_ENGINEER -> "You are a backend engineer. Build reliable APIs and data pipelines."
        TeamRole.DATABASE_ENGINEER -> "You are a database engineer. Design correct schemas, queries, and caching."
        TeamRole.TESTER -> "You are a QA engineer. Write and run real automated tests; report genuine results only."
        TeamRole.SECURITY_REVIEWER -> "You are a security reviewer. Audit for real vulnerabilities; report only verified findings."
        TeamRole.CODE_REVIEWER -> "You are a code reviewer. Review diffs for correctness, style, and risk."
        TeamRole.DEVOPS_ENGINEER -> "You are a DevOps engineer. Keep builds, CI, and packaging reliable."
    }

    private fun parseReviewVerdict(output: String, role: TeamRole): ReviewVerdict? {
        if (role != TeamRole.CODE_REVIEWER && role != TeamRole.SECURITY_REVIEWER && role != TeamRole.TESTER) {
            return null
        }
        val marker = Regex("(?im)^\\s*VERDICT\\s*:\\s*(APPROVED|CHANGES_REQUESTED|REJECTED)\\s*$")
            .find(output)?.groupValues?.getOrNull(1) ?: return null
        return when (marker.uppercase()) {
            "APPROVED" -> ReviewVerdict.APPROVED
            "CHANGES_REQUESTED" -> ReviewVerdict.CHANGES_REQUESTED
            else -> ReviewVerdict.REJECTED
        }
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
