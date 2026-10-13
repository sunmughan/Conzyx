package com.codeair.mhe.agent.team

import com.codeair.mhe.agent.team.blackboard.TeamBlackboard
import com.codeair.mhe.agent.team.budget.TeamBudgetTracker
import com.codeair.mhe.agent.team.comm.TeamCommunicationBus
import com.codeair.mhe.agent.team.model.ArtifactType
import com.codeair.mhe.agent.team.model.ConflictDetectionResult
import com.codeair.mhe.agent.team.model.ReviewRecord
import com.codeair.mhe.agent.team.model.ReviewVerdict
import com.codeair.mhe.agent.team.model.RoleBudgetUsage
import com.codeair.mhe.agent.team.model.TeamArtifact
import com.codeair.mhe.agent.team.model.TeamBudget
import com.codeair.mhe.agent.team.model.TeamCollaborationEvent
import com.codeair.mhe.agent.team.model.TeamMessage
import com.codeair.mhe.agent.team.model.TeamMessageType
import com.codeair.mhe.agent.team.model.TeamRole
import com.codeair.mhe.agent.team.model.TeamRoleConfig
import com.codeair.mhe.agent.team.model.TeamRolePermissions
import com.codeair.mhe.agent.team.model.TeamSessionState
import com.codeair.mhe.agent.team.model.TeamTask
import com.codeair.mhe.agent.team.model.TeamTaskPriority
import com.codeair.mhe.agent.team.model.TeamTaskStatus
import com.codeair.mhe.agent.team.review.TeamReviewGatekeeper
import com.codeair.mhe.agent.team.runtime.ConcurrentTeamDispatcher
import com.codeair.mhe.agent.team.runtime.AdaptiveConcurrencyController
import com.codeair.mhe.agent.team.runtime.LiveTaskInfo
import com.codeair.mhe.agent.team.runtime.LiveTaskStatus
import com.codeair.mhe.agent.team.runtime.RealTeamAgentExecutor
import com.codeair.mhe.agent.team.runtime.TeamAgentExecutor
import com.codeair.mhe.agent.team.runtime.TeamRoleValidator
import com.codeair.mhe.agent.team.runtime.TeamRunReport
import com.codeair.mhe.agent.team.scheduler.TeamTaskScheduler
import com.codeair.mhe.model.AgentKind
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Typealiases for backward compatibility with existing codebase
typealias TeamRole = com.codeair.mhe.agent.team.model.TeamRole
typealias TeamTaskStatus = com.codeair.mhe.agent.team.model.TeamTaskStatus
typealias TeamTask = com.codeair.mhe.agent.team.model.TeamTask
typealias TeamCollaborationEvent = com.codeair.mhe.agent.team.model.TeamCollaborationEvent
typealias TeamSessionState = com.codeair.mhe.agent.team.model.TeamSessionState

/**
 * Multi-Agent Engineering Team Coordinator (Phase 11 v2.2).
 * Orchestrates an autonomous 10-role engineering squad:
 * - Manager: Task decomposition, progress tracking, budget oversight
 * - Architect: Architecture design, interface contracts, ADRs
 * - Developer: Core logic implementation
 * - UI Engineer: Compose screens & UX
 * - Backend Engineer: API endpoints & data pipelines
 * - Database Engineer: Schemas, queries, caching
 * - Tester: Automated test suites & QA validation
 * - Security Reviewer: Vulnerability scans & risk audits
 * - Code Reviewer: Code quality gates & peer reviews
 * - DevOps Engineer: CI/CD, Gradle scripts, packaging
 */
class AgentTeamOrchestrator(
    val blackboard: TeamBlackboard = TeamBlackboard(),
    val scheduler: TeamTaskScheduler = TeamTaskScheduler(),
    val commBus: TeamCommunicationBus = TeamCommunicationBus(),
    val gatekeeper: TeamReviewGatekeeper = TeamReviewGatekeeper(),
    val budgetTracker: TeamBudgetTracker = TeamBudgetTracker(),
    /**
     * Demo mode (default true): tasks are SIMULATED, not executed by real agents.
     * Simulated artifacts, reviews and approvals are explicitly labelled and can
     * never count as real test/security approval. Set to false only when every
     * role is wired to a real agent executor (Milestone 10) — [requireRealExecution]
     * enforces this: real mode without an executor fails loudly instead of
     * silently simulating.
     */
    val isDemoMode: Boolean = true,
    /**
     * Real per-role agent executor (Milestone 10). Null means no real execution
     * backend is wired; [isDemoMode] must then stay true.
     */
    val teamExecutor: TeamAgentExecutor? = null,
    /**
     * Real test/security validator used in real mode. Defaults to the genuine
     * Gradle + heuristic-scan implementation.
     */
    val teamValidator: TeamRoleValidator = TeamRoleValidator(),
    /** Project directory the real agents work in; null when no project is bound. */
    val projectDir: File? = null,
    /** Upper bound on concurrently executing agents in real mode. */
    val maxConcurrentAgents: Int = 3,
    /**
     * Adaptive resource-aware scheduler. When present, the hardware baseline
     * caps [maxConcurrentAgents] and thermal/memory/battery pressure throttles
     * it dynamically during runs.
     */
    val concurrencyController: AdaptiveConcurrencyController? = null,
) {

    private val _sessionState = MutableStateFlow(TeamSessionState())
    val sessionState: StateFlow<TeamSessionState> = _sessionState.asStateFlow()

    private val roleInstances = ConcurrentHashMap<TeamRole, AgentKind>()
    private val roleConfigs = ConcurrentHashMap<TeamRole, TeamRoleConfig>()

    /**
     * Live per-agent execution snapshots from the real executor (Antigravity-style
     * observability). Empty when no real executor is wired.
     */
    val liveTasks: StateFlow<Map<String, LiveTaskInfo>> =
        (teamExecutor as? RealTeamAgentExecutor)?.liveTasks
            ?: MutableStateFlow<Map<String, LiveTaskInfo>>(emptyMap())

    /**
     * Adaptive throttle state for the UI header
     * ("2 agents running (thermal throttle)"). Never throttled without a controller.
     */
    val throttleState: StateFlow<AdaptiveConcurrencyController.ThrottleState> =
        concurrencyController?.state
            ?: MutableStateFlow(
                AdaptiveConcurrencyController.ThrottleState(
                    effectiveMaxAgents = maxConcurrentAgents,
                    baselineMaxAgents = maxConcurrentAgents,
                    throttleReason = null,
                )
            )

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Lazily-built real dispatcher (Milestone 10). Null until a [teamExecutor]
     * is wired; [requireRealExecution] guarantees real mode never runs without it.
     */
    private val realDispatcher: ConcurrentTeamDispatcher? by lazy {
        val executor = teamExecutor ?: return@lazy null
        ConcurrentTeamDispatcher(
            scheduler = scheduler,
            blackboard = blackboard,
            commBus = commBus,
            gatekeeper = gatekeeper,
            budgetTracker = budgetTracker,
            executor = executor,
            validator = teamValidator,
            roleConfigs = roleConfigs.toMap(),
            roleAgents = roleInstances.toMap(),
            projectDir = projectDir,
            goal = _sessionState.value.activeGoal,
            // Hardware baseline caps the configured maximum; dynamic pressure
            // throttles it further per stage inside the dispatcher.
            maxConcurrentAgents = minOf(
                maxConcurrentAgents,
                concurrencyController?.baselineMaxAgents ?: maxConcurrentAgents
            ),
            concurrencyController = concurrencyController,
        )
    }

    /**
     * Enforces the Milestone 10 contract: real mode ([isDemoMode] = false) is
     * only legal when a real [TeamAgentExecutor] is wired. Fails loudly instead
     * of silently simulating.
     */
    fun requireRealExecution() {
        if (!isDemoMode && teamExecutor == null) {
            throw IllegalStateException(
                "Real multi-agent mode requires a TeamAgentExecutor: isDemoMode=false was set " +
                    "without wiring teamExecutor. Pass a real executor or keep isDemoMode=true."
            )
        }
    }

    init {
        // Register default agent engines and configurations for all 10 roles
        TeamRole.entries.forEach { role ->
            val kind = when (role) {
                TeamRole.MANAGER, TeamRole.ARCHITECT, TeamRole.CODE_REVIEWER -> AgentKind.CLAUDE_CODE
                TeamRole.SECURITY_REVIEWER, TeamRole.DEVOPS_ENGINEER -> AgentKind.ANTIGRAVITY
                else -> AgentKind.ANTIGRAVITY
            }
            roleInstances[role] = kind
            roleConfigs[role] = TeamRoleConfig(
                role = role,
                defaultAgentKind = kind,
                permissions = TeamRolePermissions.forRole(role)
            )
        }

        // Synchronize Blackboard changes into session state
        blackboard.subscribe { _ ->
            syncState()
        }

        // Synchronize CommBus messages into session state
        commBus.subscribe { _ ->
            syncState()
        }

        // Mirror live parallel-execution state into the session: the UI reads
        // activeTaskCount to show "N agents running".
        observerScope.launch {
            liveTasks.collect { tasks ->
                val running = tasks.count { it.value.status == LiveTaskStatus.RUNNING }
                _sessionState.update { it.copy(activeTaskCount = running) }
            }
        }
    }

    /**
     * Decomposes a user goal into an interconnected 10-role engineering DAG.
     */
    fun planProject(userGoal: String): List<TeamTask> {
        blackboard.clear()
        commBus.clear()
        gatekeeper.clear()
        budgetTracker.reset()

        val managerTaskId = UUID.randomUUID().toString()
        val architectTaskId = UUID.randomUUID().toString()
        val dbTaskId = UUID.randomUUID().toString()
        val backendTaskId = UUID.randomUUID().toString()
        val uiTaskId = UUID.randomUUID().toString()
        val devTaskId = UUID.randomUUID().toString()
        val testerTaskId = UUID.randomUUID().toString()
        val secTaskId = UUID.randomUUID().toString()
        val codeReviewTaskId = UUID.randomUUID().toString()
        val devopsTaskId = UUID.randomUUID().toString()

        val tasks = listOf(
            TeamTask(
                id = managerTaskId,
                title = "Sprint Planning & Goal Decomposition",
                description = "Define scope, milestones, and deliverable targets for: $userGoal",
                assignedRole = TeamRole.MANAGER,
                priority = TeamTaskPriority.CRITICAL,
                dependencies = emptyList(),
                affectedFiles = listOf("docs/sprint_plan.md")
            ),
            TeamTask(
                id = architectTaskId,
                title = "System Architecture & API Contracts",
                description = "Produce system blueprint, module boundaries, and interface contracts.",
                assignedRole = TeamRole.ARCHITECT,
                priority = TeamTaskPriority.CRITICAL,
                dependencies = listOf(managerTaskId),
                affectedFiles = listOf("docs/adr_001.md", "core/contract.kt")
            ),
            TeamTask(
                id = dbTaskId,
                title = "Database Schemas & Data Model Design",
                description = "Construct database schemas, Room entities, and migration definitions.",
                assignedRole = TeamRole.DATABASE_ENGINEER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(architectTaskId),
                affectedFiles = listOf("db/schema.sql", "db/entity.kt")
            ),
            TeamTask(
                id = backendTaskId,
                title = "Backend Service & Data Pipeline Logic",
                description = "Develop business endpoints, repository layer, and network services.",
                assignedRole = TeamRole.BACKEND_ENGINEER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(architectTaskId, dbTaskId),
                affectedFiles = listOf("backend/service.kt", "backend/repository.kt")
            ),
            TeamTask(
                id = uiTaskId,
                title = "Compose Screen UI & Responsive Layouts",
                description = "Build Material 3 user interfaces, design tokens, and state bindings.",
                assignedRole = TeamRole.UI_ENGINEER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(architectTaskId),
                affectedFiles = listOf("ui/screen.kt", "ui/components.kt")
            ),
            TeamTask(
                id = devTaskId,
                title = "Fullstack Integration & Feature Implementation",
                description = "Integrate UI components with backend repositories and state models.",
                assignedRole = TeamRole.DEVELOPER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(backendTaskId, uiTaskId),
                affectedFiles = listOf("app/feature.kt", "app/viewmodel.kt")
            ),
            TeamTask(
                id = testerTaskId,
                title = "Automated Unit & Integration Test Suite",
                description = "Author test cases covering happy paths, error states, and edge conditions.",
                assignedRole = TeamRole.TESTER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(devTaskId),
                affectedFiles = listOf("test/FeatureUnitTest.kt")
            ),
            TeamTask(
                id = secTaskId,
                title = "Security Audit & Risk Assessment",
                description = "Scan source code for leaks, unsafe inputs, and permission flaws.",
                assignedRole = TeamRole.SECURITY_REVIEWER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(devTaskId),
                affectedFiles = listOf("docs/security_audit.md")
            ),
            TeamTask(
                id = codeReviewTaskId,
                title = "Code Quality & Architectural Review Gate",
                description = "Perform comprehensive code review, SOLID enforcement, and peer sign-off.",
                assignedRole = TeamRole.CODE_REVIEWER,
                priority = TeamTaskPriority.CRITICAL,
                dependencies = listOf(testerTaskId, secTaskId),
                affectedFiles = listOf("docs/code_review.md")
            ),
            TeamTask(
                id = devopsTaskId,
                title = "CI/CD & Release Build Packaging",
                description = "Configure build sanity scripts, verify Gradle tasks, and package artifact.",
                assignedRole = TeamRole.DEVOPS_ENGINEER,
                priority = TeamTaskPriority.MEDIUM,
                dependencies = listOf(codeReviewTaskId),
                affectedFiles = listOf("build.gradle.kts", ".github/workflows/ci.yml")
            )
        )

        scheduler.setTasks(tasks)

        // Publish project blueprint into Shared Blackboard
        blackboard.publishArtifact(
            TeamArtifact(
                title = "System Architecture Blueprint: $userGoal",
                type = ArtifactType.ADR,
                authorRole = TeamRole.MANAGER,
                content = "High-level architectural blueprint decomposing '$userGoal' into 10 synchronized roles.",
                tags = setOf("architecture", "init", "sprint-1")
            )
        )

        // Publish kickoff announcement into Communication Bus
        commBus.sendMessage(
            fromRole = TeamRole.MANAGER,
            toRole = null,
            type = TeamMessageType.BROADCAST,
            content = "Project goal accepted: '$userGoal'. Initialized 10 engineering tasks across team DAG."
        )

        // Record initial planning budget
        budgetTracker.recordUsage(TeamRole.MANAGER, tokens = 1250L, durationMs = 450L)

        syncState(userGoal = userGoal, isRunning = true, activeRole = TeamRole.MANAGER)
        return tasks
    }

    private fun demoPrefix(demo: Boolean): String = if (demo) "[SIMULATED DEMO] " else ""

    private fun demoSuffix(demo: Boolean): String =
        if (demo) " [This content was simulated in demo mode.]" else ""

    private fun logDemoNote(task: TeamTask, note: String) {
        commBus.sendMessage(
            fromRole = TeamRole.MANAGER,
            toRole = null,
            type = TeamMessageType.STATUS_UPDATE,
            content = "[SIMULATED DEMO] ${task.title}: $note",
            taskId = task.id
        )
    }

    /**
     * Executes (or, in demo mode, simulates) the next ready task in the DAG.
     *
     * This is the demo-mode stepping API. In real mode ([isDemoMode] = false)
     * it refuses to run: silent simulation would violate the integrity
     * contract, so callers must use the suspend [executeNextReadyTask] or
     * [autoRunAllReal] instead.
     *
     * In demo mode ([isDemoMode] = true, the default) no real agent executes:
     * artifacts and reviews are labelled SIMULATED and simulated reviews are never
     * submitted as approvals to the review gatekeeper.
     */
    fun runNextReadyTask(): TeamTask? {
        if (!isDemoMode) {
            throw IllegalStateException(
                "runNextReadyTask() is the demo-mode stepping API; in real mode use the suspend " +
                    "executeNextReadyTask() or autoRunAllReal() instead."
            )
        }
        if (budgetTracker.isQuotaExceeded()) {
            commBus.escalateToManager(
                fromRole = TeamRole.MANAGER,
                reason = "Token quota exceeded (${budgetTracker.getBudgetSummary().totalTokensUsed} tokens). Halting team execution."
            )
            _sessionState.value = _sessionState.value.copy(isRunning = false)
            return null
        }

        val readyTasks = scheduler.getReadyTasks()
        if (readyTasks.isEmpty()) {
            if (scheduler.isAllCompleted()) {
                _sessionState.value = _sessionState.value.copy(isRunning = false, activeRole = null)
            }
            return null
        }

        val task = readyTasks.first()
        scheduler.updateTaskStatus(task.id, TeamTaskStatus.IN_PROGRESS)
        _sessionState.value = _sessionState.value.copy(activeRole = task.assignedRole)

        // Demo mode: NOTHING here executes a real agent. Every artifact, review and
        // metric below is illustrative. Simulated results are explicitly labelled and
        // can never count as real test or security approval (Milestone 0 integrity gate).
        // Real per-role agent executors are Milestone 10 work.
        val demo = isDemoMode
        val simulatedTokens = 2500L
        val simulatedDuration = 600L
        if (demo) {
            budgetTracker.recordUsage(task.assignedRole, simulatedTokens, simulatedDuration)
        }

        val outputMessage = if (demo) {
            "[SIMULATED DEMO] ${task.title} by ${task.assignedRole.title}: illustrative output only, no real agent executed."
        } else {
            "Executed ${task.title} by ${task.assignedRole.title}. Generated deliverables."
        }

        // If task produces artifacts, publish them to Blackboard
        when (task.assignedRole) {
            TeamRole.ARCHITECT -> {
                blackboard.publishArtifact(
                    TeamArtifact(
                        title = demoPrefix(demo) + "API Contract & Data Spec",
                        type = ArtifactType.API_CONTRACT,
                        authorRole = TeamRole.ARCHITECT,
                        content = "REST & Event-driven API contract specifications for ${task.title}." +
                            demoSuffix(demo),
                        isSimulated = demo
                    )
                )
            }
            TeamRole.DATABASE_ENGINEER -> {
                blackboard.publishArtifact(
                    TeamArtifact(
                        title = demoPrefix(demo) + "Database Schema & Migration",
                        type = ArtifactType.DB_SCHEMA,
                        authorRole = TeamRole.DATABASE_ENGINEER,
                        content = "SQL DDL and Room entity definitions." + demoSuffix(demo),
                        isSimulated = demo
                    )
                )
            }
            TeamRole.TESTER -> {
                blackboard.publishArtifact(
                    TeamArtifact(
                        title = demoPrefix(demo) + "QA Test Suite Execution Report",
                        type = ArtifactType.TEST_REPORT,
                        authorRole = TeamRole.TESTER,
                        content = if (demo) {
                            "SIMULATED: no tests were executed. This report is illustrative only " +
                                "and must never be treated as passing tests."
                        } else {
                            "All 14 unit test assertions passed. 100% coverage on core feature paths."
                        },
                        isSimulated = demo
                    )
                )
            }
            TeamRole.SECURITY_REVIEWER -> {
                blackboard.publishArtifact(
                    TeamArtifact(
                        title = demoPrefix(demo) + "Security Vulnerability Audit",
                        type = ArtifactType.SECURITY_AUDIT,
                        authorRole = TeamRole.SECURITY_REVIEWER,
                        content = if (demo) {
                            "SIMULATED: no vulnerability scan ran. Zero-CVE claims here are illustrative " +
                                "only and must never be treated as a real security audit."
                        } else {
                            "Zero CVEs or secrets detected. Sandboxed execution verified."
                        },
                        isSimulated = demo
                    )
                )
                if (demo) {
                    // Demo reviews are NEVER submitted as approvals to the gatekeeper.
                    logDemoNote(task, "Security review simulated: no real audit performed, no approval recorded.")
                } else {
                    gatekeeper.submitReview(
                        taskId = task.id,
                        reviewerRole = TeamRole.SECURITY_REVIEWER,
                        verdict = ReviewVerdict.APPROVED,
                        comments = "Security audit approved with zero critical findings."
                    )
                }
            }
            TeamRole.CODE_REVIEWER -> {
                if (demo) {
                    // Demo reviews are NEVER submitted as approvals to the gatekeeper.
                    logDemoNote(task, "Code review simulated: no real review performed, no approval recorded.")
                } else {
                    gatekeeper.submitReview(
                        taskId = task.id,
                        reviewerRole = TeamRole.CODE_REVIEWER,
                        verdict = ReviewVerdict.APPROVED,
                        comments = "Architecture verified, clean code standards met."
                    )
                }
            }
            else -> {}
        }

        commBus.sendMessage(
            fromRole = task.assignedRole,
            toRole = null,
            type = TeamMessageType.STATUS_UPDATE,
            content = outputMessage,
            taskId = task.id
        )

        // Complete the task and record metrics
        scheduler.updateTaskStatus(
            taskId = task.id,
            status = TeamTaskStatus.COMPLETED,
            output = outputMessage,
            tokensUsed = if (demo) simulatedTokens else 0L,
            executionTimeMs = if (demo) simulatedDuration else 0L,
            isSimulated = demo
        )
        budgetTracker.recordTaskCompleted(task.assignedRole)

        syncState()
        return task
    }

    /**
     * Executes the next READY task through the real agent runtime (Milestone 10).
     *
     * The task genuinely executes via [teamExecutor]: real output, genuine
     * artifacts on the blackboard, real validation + review records for
     * TESTER/SECURITY_REVIEWER/CODE_REVIEWER roles, and handoff messages to
     * dependents. Failed tasks are marked FAILED (dependents block via the
     * scheduler) — never silently completed.
     */
    suspend fun executeNextReadyTask(): TeamTask? {
        requireRealExecution()
        val dispatcher = realDispatcher
            ?: throw IllegalStateException("Real dispatcher unavailable without a TeamAgentExecutor.")
        if (budgetTracker.isQuotaExceeded()) {
            commBus.escalateToManager(
                fromRole = TeamRole.MANAGER,
                reason = "Token quota exceeded (${budgetTracker.getBudgetSummary().totalTokensUsed} tokens). Halting team execution."
            )
            _sessionState.value = _sessionState.value.copy(isRunning = false)
            return null
        }

        val readyTasks = scheduler.getReadyTasks()
        if (readyTasks.isEmpty()) {
            if (scheduler.isAllCompleted()) {
                _sessionState.value = _sessionState.value.copy(isRunning = false, activeRole = null)
            }
            return null
        }

        val task = readyTasks.first()
        _sessionState.value = _sessionState.value.copy(activeRole = task.assignedRole)
        dispatcher.runReadyTask(task)
        syncState()
        return scheduler.getTask(task.id)
    }

    /**
     * Runs the whole task DAG through the real runtime with safe concurrent
     * stage dispatch (Milestone 10). Returns the aggregated [TeamRunReport].
     */
    suspend fun autoRunAllReal(): TeamRunReport {
        requireRealExecution()
        val dispatcher = realDispatcher
            ?: throw IllegalStateException("Real dispatcher unavailable without a TeamAgentExecutor.")
        val report = dispatcher.runAll()
        syncState()
        return report
    }

    /** Requests cancellation of a real run; in-flight agent runs finish rather than being killed. */
    fun cancelRealRun() {
        realDispatcher?.cancel()
        _sessionState.value = _sessionState.value.copy(isRunning = false, activeRole = null)
    }

    /**
     * Automatically processes all stages until either complete or blocked.
     */
    fun autoRunAll(): Int {
        var completedCount = 0
        while (_sessionState.value.isRunning && !budgetTracker.isQuotaExceeded()) {
            val task = runNextReadyTask() ?: break
            completedCount++
        }
        return completedCount
    }

    fun updateTaskStatus(taskId: String, status: TeamTaskStatus, output: String = "", feedback: String = "") {
        scheduler.updateTaskStatus(taskId, status, output, feedback)
        syncState()
    }

    fun broadcastMessage(from: TeamRole, to: TeamRole?, message: String) {
        commBus.sendMessage(
            fromRole = from,
            toRole = to,
            type = if (to == null) TeamMessageType.BROADCAST else TeamMessageType.STATUS_UPDATE,
            content = message
        )
        syncState()
    }

    fun submitReview(taskId: String, reviewerRole: TeamRole, verdict: ReviewVerdict, comments: String): ReviewRecord {
        val record = gatekeeper.submitReview(taskId, reviewerRole, verdict, comments)
        commBus.sendMessage(
            fromRole = reviewerRole,
            toRole = null,
            type = TeamMessageType.FEEDBACK,
            content = "[Review ${verdict.name}]: $comments",
            taskId = taskId
        )
        syncState()
        return record
    }

    fun cancelSession() {
        _sessionState.value = _sessionState.value.copy(isRunning = false, activeRole = null)
    }

    private fun syncState(
        userGoal: String = _sessionState.value.activeGoal,
        isRunning: Boolean = _sessionState.value.isRunning,
        activeRole: TeamRole? = _sessionState.value.activeRole
    ) {
        val allTasks = scheduler.getTasks()
        val allMessages = commBus.getMessages()
        val allArtifacts = blackboard.getAllArtifacts()
        val budget = budgetTracker.getBudgetSummary()
        val reviews = gatekeeper.getAllReviews()

        // Check for file conflicts
        val conflictResult = gatekeeper.detectFileConflicts(allTasks)
        val conflictWarnings = if (conflictResult.hasConflict) {
            conflictResult.conflictingFiles.map { "Potential write conflict detected on: $it" }
        } else {
            emptyList()
        }

        // Maintain backward-compatible events list
        val legacyEvents = allMessages.map {
            TeamCollaborationEvent(
                id = it.id,
                fromRole = it.fromRole,
                toRole = it.toRole,
                message = it.content,
                timestamp = it.timestamp
            )
        }

        val allDone = scheduler.isAllCompleted()
        val nextActiveRole = if (allDone) null else (scheduler.getReadyTasks().firstOrNull()?.assignedRole ?: activeRole)

        _sessionState.value = TeamSessionState(
            activeGoal = userGoal,
            isRunning = isRunning && !allDone,
            activeRole = nextActiveRole,
            tasks = allTasks,
            events = legacyEvents,
            messages = allMessages,
            artifacts = allArtifacts,
            budget = budget,
            reviews = reviews,
            conflictWarnings = conflictWarnings,
            executionLog = allMessages.map { "[${it.fromRole.name}] ${it.content}" },
            isDemoMode = isDemoMode
        )
    }
}
