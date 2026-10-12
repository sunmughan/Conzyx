package com.codeair.mhe.agent.team.runtime

import com.codeair.mhe.agent.team.blackboard.TeamBlackboard
import com.codeair.mhe.agent.team.budget.TeamBudgetTracker
import com.codeair.mhe.agent.team.comm.TeamCommunicationBus
import com.codeair.mhe.agent.team.model.ReviewRecord
import com.codeair.mhe.agent.team.model.ReviewVerdict
import com.codeair.mhe.agent.team.model.TeamMessageType
import com.codeair.mhe.agent.team.model.TeamRole
import com.codeair.mhe.agent.team.model.TeamRoleConfig
import com.codeair.mhe.agent.team.model.TeamTask
import com.codeair.mhe.agent.team.model.TeamTaskStatus
import com.codeair.mhe.agent.team.review.TeamReviewGatekeeper
import com.codeair.mhe.agent.team.scheduler.TeamTaskScheduler
import com.codeair.mhe.model.AgentKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Safe concurrent DAG dispatcher for the real multi-agent runtime (Milestone 10).
 *
 * Executes each dependency stage concurrently (bounded by [maxConcurrentAgents]
 * and each role's [TeamRoleConfig.maxConcurrentTasks]) while guaranteeing:
 * - tasks whose `affectedFiles` overlap never run at the same time (per-file
 *   mutexes, derived from the same normalization as
 *   [TeamReviewGatekeeper.detectFileConflicts]);
 * - the team budget quota is checked before every launch and halts dispatch;
 * - a failed task blocks its dependents via the scheduler (never silently skipped);
 * - cancellation between stages stops new launches; in-flight agent runs are
 *   left to finish rather than being unsafely killed.
 *
 * All task results, artifacts, messages and reviews produced here are genuine
 * (never simulated). The dispatcher itself performs no demo-mode simulation.
 */
class ConcurrentTeamDispatcher(
    private val scheduler: TeamTaskScheduler,
    private val blackboard: TeamBlackboard,
    private val commBus: TeamCommunicationBus,
    private val gatekeeper: TeamReviewGatekeeper,
    private val budgetTracker: TeamBudgetTracker,
    private val executor: TeamAgentExecutor,
    private val validator: TeamRoleValidator = TeamRoleValidator(),
    private val roleConfigs: Map<TeamRole, TeamRoleConfig> = emptyMap(),
    private val roleAgents: Map<TeamRole, AgentKind> = emptyMap(),
    private val projectDir: File? = null,
    private val goal: String = "",
    private val maxConcurrentAgents: Int = 3,
) {
    private val cancelled = AtomicBoolean(false)
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Executes one READY task through the real runtime (used by the
     * orchestrator's single-step path). Returns the aggregated outcome.
     */
    suspend fun runReadyTask(task: TeamTask): TeamTaskOutcome {
        return executeOne(task).outcome
    }

    /**
     * Runs the full DAG to completion: stage by stage, tasks within a stage
     * concurrently. Returns the aggregated [TeamRunReport].
     */
    suspend fun runAll(): TeamRunReport = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val outcomes = mutableListOf<TeamTaskOutcome>()
        val reviewRecords = mutableListOf<ReviewRecord>()

        val stages = scheduler.getExecutionStages()
        if (stages.isEmpty() && scheduler.getTasks().isNotEmpty()) {
            commBus.escalateToManager(
                fromRole = TeamRole.MANAGER,
                reason = "Task graph has a dependency cycle or is unorderable; refusing to dispatch.",
            )
            return@coroutineScope TeamRunReport(
                goal = goal,
                outcomes = scheduler.getTasks().map {
                    TeamTaskOutcome(it, it.status, "Not dispatched: unorderable task graph.", 0L, null)
                },
                reviews = emptyList(),
                totalDurationMs = System.currentTimeMillis() - startedAt,
                cancelled = true,
            )
        }

        val semaphore = Semaphore(maxConcurrentAgents.coerceAtLeast(1))

        for (stage in stages) {
            if (cancelled.get()) break
            if (budgetTracker.isQuotaExceeded()) {
                commBus.escalateToManager(
                    fromRole = TeamRole.MANAGER,
                    reason = "Token quota exceeded; halting dispatch before stage of ${stage.size} tasks.",
                )
                break
            }
            val stageOutcomes = stage
                // NOTE: stage lists snapshot task state at computation time; re-check
                // liveness against the scheduler so tasks unlocked by the previous
                // stage are actually dispatched.
                .filter { scheduler.getTask(it.id)?.status == TeamTaskStatus.READY }
                .map { task ->
                    async(Dispatchers.Default) {
                        semaphore.withPermit { executeOne(task) }
                    }
                }
                .awaitAll()
            stageOutcomes.forEach { (outcome, reviews) ->
                outcomes += outcome
                reviewRecords += reviews
            }
        }

        TeamRunReport(
            goal = goal,
            outcomes = outcomes,
            reviews = reviewRecords,
            totalDurationMs = System.currentTimeMillis() - startedAt,
            cancelled = cancelled.get(),
        )
    }

    private data class ExecutedPair(val outcome: TeamTaskOutcome, val reviews: List<ReviewRecord>)

    /**
     * Executes a single task with per-file locking, real execution, genuine
     * artifact publishing, real validation for TESTER/SECURITY_REVIEWER roles,
     * and inter-agent messaging (status updates + handoffs to dependents).
     */
    private suspend fun executeOne(task: TeamTask): ExecutedPair {
        val taskStartedAt = System.currentTimeMillis()
        val reviews = mutableListOf<ReviewRecord>()

        if (cancelled.get()) {
            return ExecutedPair(
                TeamTaskOutcome(task, TeamTaskStatus.PENDING, "Skipped: run cancelled.", 0L, null),
                emptyList(),
            )
        }

        scheduler.updateTaskStatus(task.id, TeamTaskStatus.IN_PROGRESS)
        commBus.sendMessage(
            fromRole = task.assignedRole,
            toRole = null,
            type = TeamMessageType.STATUS_UPDATE,
            content = "${task.title} started.",
            taskId = task.id,
        )

        // Acquire per-file locks so overlapping affectedFiles never run concurrently.
        val locks = task.affectedFiles
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { fileLocks.getOrPut(it) { Mutex() } }

        val execResult = try {
            locks.forEach { it.lock() }
            try {
                val context = TeamExecutionContext(
                    projectDir = projectDir,
                    goal = goal,
                    role = task.assignedRole,
                    roleConfig = roleConfigs[task.assignedRole] ?: TeamRoleConfig(task.assignedRole),
                    agentKind = roleAgents[task.assignedRole] ?: AgentKind.ANTIGRAVITY,
                    dependencyOutputs = dependencyOutputsOf(task),
                    blackboardSummary = summarizeBlackboard(),
                    timeoutMs = 20L * 60L * 1000L,
                )
                executor.execute(task, context)
            } finally {
                locks.forEach { runCatching { it.unlock() } }
            }
        } catch (e: Exception) {
            scheduler.updateTaskStatus(task.id, TeamTaskStatus.FAILED, output = "Dispatcher error: ${e.message}")
            commBus.escalateToManager(task.assignedRole, "Dispatcher error on '${task.title}': ${e.message}", task.id)
            return ExecutedPair(
                TeamTaskOutcome(task, TeamTaskStatus.FAILED, "Dispatcher error: ${e.message}", System.currentTimeMillis() - taskStartedAt, null),
                emptyList(),
            )
        }

        val durationMs = System.currentTimeMillis() - taskStartedAt

        if (!execResult.result.isSuccess) {
            scheduler.updateTaskStatus(
                taskId = task.id,
                status = TeamTaskStatus.FAILED,
                output = execResult.outputText,
                tokensUsed = execResult.tokensUsed ?: 0L,
                executionTimeMs = durationMs,
                isSimulated = false,
            )
            budgetTracker.recordUsage(task.assignedRole, execResult.tokensUsed ?: 0L, durationMs)
            commBus.escalateToManager(
                task.assignedRole,
                "'${task.title}' failed: ${execResult.result.error ?: execResult.result.status}.",
                task.id,
            )
            return ExecutedPair(
                TeamTaskOutcome(task, TeamTaskStatus.FAILED, execResult.outputText.take(500), durationMs, execResult.tokensUsed),
                emptyList(),
            )
        }

        // Genuine artifacts onto the blackboard.
        execResult.artifacts.forEach { blackboard.publishArtifact(it) }

        // Real validation for TESTER / SECURITY_REVIEWER roles.
        var reviewVerdict: ReviewVerdict? = execResult.reviewVerdict
        when (task.assignedRole) {
            TeamRole.TESTER -> {
                val validation = validator.validateTester(task, projectDir)
                blackboard.publishArtifact(validation.artifact)
                reviewVerdict = validation.verdict
                val record = gatekeeper.submitReview(task.id, TeamRole.TESTER, validation.verdict, validation.summary, isSimulated = false)
                reviews += record
                commBus.sendMessage(task.assignedRole, null, TeamMessageType.STATUS_UPDATE, validation.summary, task.id)
            }
            TeamRole.SECURITY_REVIEWER -> {
                val validation = validator.validateSecurity(task, projectDir)
                blackboard.publishArtifact(validation.artifact)
                reviewVerdict = validation.verdict
                val record = gatekeeper.submitReview(task.id, TeamRole.SECURITY_REVIEWER, validation.verdict, validation.summary, isSimulated = false)
                reviews += record
                commBus.sendMessage(task.assignedRole, null, TeamMessageType.STATUS_UPDATE, validation.summary, task.id)
            }
            TeamRole.CODE_REVIEWER -> {
                // A code-review approval is recorded only when the agent emitted an explicit verdict marker.
                if (reviewVerdict != null) {
                    val record = gatekeeper.submitReview(
                        taskId = task.id,
                        reviewerRole = TeamRole.CODE_REVIEWER,
                        verdict = reviewVerdict,
                        comments = execResult.outputText.take(2000),
                        isSimulated = false,
                    )
                    reviews += record
                } else {
                    commBus.sendMessage(
                        task.assignedRole, null, TeamMessageType.STATUS_UPDATE,
                        "Code review produced no explicit verdict marker; no approval recorded.", task.id,
                    )
                }
            }
            else -> {}
        }

        val taskSucceeded = when (task.assignedRole) {
            TeamRole.TESTER, TeamRole.SECURITY_REVIEWER -> reviewVerdict == ReviewVerdict.APPROVED
            else -> true
        }

        val finalStatus = if (taskSucceeded) TeamTaskStatus.COMPLETED else TeamTaskStatus.FAILED
        scheduler.updateTaskStatus(
            taskId = task.id,
            status = finalStatus,
            output = execResult.outputText,
            tokensUsed = execResult.tokensUsed ?: 0L,
            executionTimeMs = durationMs,
            isSimulated = false,
        )
        budgetTracker.recordUsage(task.assignedRole, execResult.tokensUsed ?: 0L, durationMs)
        budgetTracker.recordTaskCompleted(task.assignedRole)

        commBus.sendMessage(
            fromRole = task.assignedRole,
            toRole = null,
            type = TeamMessageType.STATUS_UPDATE,
            content = "${task.title} ${finalStatus.name.lowercase()} in ${durationMs}ms.",
            taskId = task.id,
        )
        // Handoff: notify dependents with the real output summary.
        scheduler.getTasks()
            .filter { it.dependencies.contains(task.id) }
            .forEach { dependent ->
                commBus.sendMessage(
                    fromRole = task.assignedRole,
                    toRole = dependent.assignedRole,
                    type = TeamMessageType.HANDOFF,
                    content = "Handoff for '${dependent.title}': prerequisite '${task.title}' ${finalStatus.name.lowercase()}. " +
                        "Output: ${execResult.outputText.take(1000)}",
                    taskId = dependent.id,
                )
            }

        return ExecutedPair(
            TeamTaskOutcome(task, finalStatus, execResult.outputText.take(500), durationMs, execResult.tokensUsed, reviewVerdict),
            reviews,
        )
    }

    private fun dependencyOutputsOf(task: TeamTask): Map<String, String> {
        val linked = LinkedHashMap<String, String>()
        task.dependencies.forEach { depId ->
            scheduler.getTask(depId)?.let { linked[depId] = it.output }
        }
        return linked
    }

    private fun summarizeBlackboard(): String {
        val artifacts = blackboard.getAllArtifacts().takeLast(12)
        if (artifacts.isEmpty()) return ""
        return artifacts.joinToString("\n") { "- [${it.type.label}] ${it.title}: ${it.content.take(300)}" }
    }
}
