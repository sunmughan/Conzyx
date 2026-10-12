package com.codeair.mhe.agent.team.runtime

import com.codeair.mhe.agent.team.model.ReviewVerdict
import com.codeair.mhe.agent.team.model.TeamArtifact
import com.codeair.mhe.agent.team.model.TeamRole
import com.codeair.mhe.agent.team.model.TeamTask
import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.model.AgentKind
import com.codeair.mhe.model.ProjectKind
import com.codeair.mhe.model.ProviderProfile
import com.codeair.mhe.model.RuntimeEvent
import com.codeair.mhe.runtime.RuntimeBridge
import com.codeair.mhe.runtime.RuntimeInstaller
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Execution state of one agent task, observable for the Antigravity-style live UI. */
enum class LiveTaskStatus { RUNNING, COMPLETED, FAILED }

/**
 * Live, observable snapshot of a single agent execution.
 * [outputTail] holds the last ~2000 chars of streamed assistant output and is
 * updated while the agent runs, so the UI can drill into a live task.
 */
data class LiveTaskInfo(
    val taskId: String,
    val role: TeamRole,
    val taskTitle: String,
    val taskDescription: String,
    val status: LiveTaskStatus = LiveTaskStatus.RUNNING,
    val outputTail: String = "",
    val tokensUsed: Long? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
)

/** Maximum live-task entries retained (running entries are never pruned). */
private const val MAX_LIVE_TASKS = 30

/** How much streamed output is kept per live task for the drill-down view. */
private const val OUTPUT_TAIL_CHARS = 2000

/** Minimum interval between live-output UI updates (ms). */
private const val LIVE_EMIT_THROTTLE_MS = 400L

/**
 * Genuine per-role agent executor for the Multi-Agent Engineering Studio with
 * TRUE PARALLEL execution (Antigravity-style).
 *
 * Parallelism model: every [execute] call builds its OWN [RuntimeBridge]
 * instance via [bridgeFactory]. Bridges hold all session state
 * (activeProcess, activeSessionId, streamed text) as instance fields, so
 * separate instances mean separate OS processes that genuinely run at the
 * same time. There is no shared bridge and no per-kind mutex anymore; the
 * only bound is [ConcurrentTeamDispatcher]'s semaphore (default 3 agents)
 * plus per-file mutexes that keep overlapping file writes from colliding.
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
 * Honest limitations:
 * - Phones are thermally and memory bound: 3 concurrent agent CLIs is the
 *   sane default; raising it risks OOM kills and thermal throttling.
 * - Each parallel agent spawns its own CLI process (node/python), so expect
 *   roughly N x single-agent memory/CPU while a stage runs.
 * - The agent works in a dedicated team workspace under [workspaceBaseDir]
 *   (one directory per team goal). A projectDir bound on the orchestrator is
 *   currently always null; binding an existing project directory is future work.
 * - A user-initiated chat session uses the shared MainViewModel bridges, which
 *   are different instances from team bridges, so manual chats no longer
 *   clobber team runs (and vice versa).
 */
class RealTeamAgentExecutor(
    private val workspaceBaseDir: File,
    private val bridgeFactory: (AgentKind) -> RuntimeBridge,
    private val installer: RuntimeInstaller,
    private val providerFor: (AgentKind) -> ProviderProfile?,
    private val secretFor: (ProviderProfile) -> String?,
) : TeamAgentExecutor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _liveTasks = MutableStateFlow<Map<String, LiveTaskInfo>>(emptyMap())

    /** Observable live/completed task snapshots for the roster drill-down UI. */
    val liveTasks: StateFlow<Map<String, LiveTaskInfo>> = _liveTasks.asStateFlow()

    override suspend fun execute(task: TeamTask, context: TeamExecutionContext): TeamExecutionResult {
        val startedAt = System.currentTimeMillis()
        val kind = context.agentKind

        fun failFast(reason: String): TeamExecutionResult {
            val result = TeamExecutionResult(
                result = OperationResult.notConfigured(reason),
                outputText = "",
                durationMs = System.currentTimeMillis() - startedAt,
            )
            registerLive(task, context, startedAt, LiveTaskStatus.FAILED, reason, 0L, null)
            return result
        }

        if (!installer.isAgentInstalled(kind)) {
            return failFast("The '${kind.title}' runtime is not installed. Install it from Agent settings first.")
        }
        val provider = providerFor(kind)
            ?: return failFast("No provider configured for ${kind.title}.")
        if (secretFor(provider).isNullOrBlank()) {
            return failFast("No API key or token saved for ${provider.kind.title}. Add one from Agent settings first.")
        }

        // Fresh bridge per execution: separate instance = separate OS process =
        // true parallelism with the other agents running right now.
        val bridge = bridgeFactory(kind)

        val goalHash = sha1Hex(context.goal).take(12)
        val projectId = "team-$goalHash"
        val projectSlug = "team-${goalHash.take(8)}"
        val workspace = File(workspaceBaseDir, projectId).apply { mkdirs() }
        val prompt = buildPrompt(task, context)

        registerLive(task, context, startedAt, LiveTaskStatus.RUNNING, "", 0L, null)

        return runSession(
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

    private fun registerLive(
        task: TeamTask,
        context: TeamExecutionContext,
        startedAt: Long,
        status: LiveTaskStatus,
        outputTail: String,
        durationMs: Long,
        tokensUsed: Long?,
    ) {
        _liveTasks.update { map ->
            val pruned = if (map.size >= MAX_LIVE_TASKS) {
                map.entries
                    .filter { it.value.status != LiveTaskStatus.RUNNING }
                    .minByOrNull { it.value.startedAtMs }
                    ?.let { map - it.key } ?: map
            } else map
            pruned + (task.id to LiveTaskInfo(
                taskId = task.id,
                role = context.role,
                taskTitle = task.title,
                taskDescription = task.description,
                status = status,
                outputTail = outputTail,
                tokensUsed = tokensUsed,
                startedAtMs = startedAt,
                durationMs = durationMs,
            ))
        }
    }

    private fun updateLiveTail(taskId: String, tail: String) {
        _liveTasks.update { map ->
            map[taskId]?.let { map + (taskId to it.copy(outputTail = tail)) } ?: map
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
        val outputBuilder = StringBuilder()
        var lastEmitMs = 0L

        fun emitLive(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastEmitMs >= LIVE_EMIT_THROTTLE_MS) {
                lastEmitMs = now
                val tail = synchronized(outputBuilder) {
                    val s = outputBuilder.toString()
                    if (s.length > OUTPUT_TAIL_CHARS) s.takeLast(OUTPUT_TAIL_CHARS) else s
                }
                updateLiveTail(task.id, tail)
            }
        }

        // This bridge instance is private to this execution, so every event it
        // emits belongs to our session: safe to stream without session filtering.
        val collector = scope.launch {
            bridge.events.collect { event ->
                captured.add(event)
                if (event is RuntimeEvent.AssistantDelta) {
                    synchronized(outputBuilder) { outputBuilder.append(event.text) }
                    emitLive()
                }
            }
        }

        // startSession blocks until the agent process ends and always emits a
        // terminal event (SessionCompleted / SessionFailed) before returning.
        // It runs NonCancellable internally, so a timeout cannot interrupt it
        // directly: on timeout we stop the active session (destroys the
        // process), which lets startSession wind down promptly.
        val sessionDeferred = scope.async {
            bridge.startSession(
                projectId = projectId,
                projectSlug = projectSlug,
                projectKind = ProjectKind.PROJECT,
                prompt = prompt,
                conversationHistory = emptyList(),
                provider = provider,
            )
        }
        val sessionId: String?
        try {
            sessionId = withTimeoutOrNull(timeoutMs) { sessionDeferred.await() }
        } catch (e: Exception) {
            collector.cancel()
            val durationMs = System.currentTimeMillis() - startedAt
            registerLive(task, context, startedAt, LiveTaskStatus.FAILED, "Failed to start: ${e.message}", durationMs, null)
            return TeamExecutionResult(
                result = OperationResult.failure(
                    error = "Agent '${context.role.title}' failed to start: ${e.message}",
                    startedAt = startedAt,
                ),
                outputText = "",
                durationMs = durationMs,
            )
        }
        if (sessionId == null) {
            runCatching { bridge.stopActiveSession() }
            // Give the bridge a short grace period to wind down; never hang.
            withTimeoutOrNull(20_000L) { sessionDeferred.await() }
            collector.cancel()
            val durationMs = System.currentTimeMillis() - startedAt
            val reason = "Timed out after ${timeoutMs / 1000}s."
            registerLive(task, context, startedAt, LiveTaskStatus.FAILED, reason, durationMs, null)
            return TeamExecutionResult(
                result = OperationResult.failure(
                    error = "Agent '${context.role.title}' timed out after ${timeoutMs / 1000}s on '${task.title}'.",
                    logs = listOf("timeoutMs=$timeoutMs"),
                    startedAt = startedAt,
                ),
                outputText = "",
                durationMs = durationMs,
            )
        }
        collector.cancel()
        emitLive(force = true)

        val ours = captured.filter { it.sessionId == sessionId }
        val outputText = ours
            .filterIsInstance<RuntimeEvent.AssistantDelta>()
            .joinToString(separator = "") { it.text }
            .trim()
        val failed = ours.filterIsInstance<RuntimeEvent.SessionFailed>().firstOrNull()
        val completed = ours.filterIsInstance<RuntimeEvent.SessionCompleted>().firstOrNull()
        val durationMs = System.currentTimeMillis() - startedAt
        // Tokens are not reported by the CLI bridges; leave null (never invent).
        val tokensUsed: Long? = null

        if (failed != null) {
            registerLive(task, context, startedAt, LiveTaskStatus.FAILED, outputText.takeLast(OUTPUT_TAIL_CHARS), durationMs, tokensUsed)
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
            registerLive(task, context, startedAt, LiveTaskStatus.FAILED, outputText.takeLast(OUTPUT_TAIL_CHARS), durationMs, tokensUsed)
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

        registerLive(task, context, startedAt, LiveTaskStatus.COMPLETED, outputText.takeLast(OUTPUT_TAIL_CHARS), durationMs, tokensUsed)

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
