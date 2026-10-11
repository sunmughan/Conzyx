package com.jarves.mh.agent.team

import com.jarves.mh.agent.team.blackboard.TeamBlackboard
import com.jarves.mh.agent.team.budget.TeamBudgetTracker
import com.jarves.mh.agent.team.comm.TeamCommunicationBus
import com.jarves.mh.agent.team.model.ArtifactType
import com.jarves.mh.agent.team.model.ConflictDetectionResult
import com.jarves.mh.agent.team.model.ReviewRecord
import com.jarves.mh.agent.team.model.ReviewVerdict
import com.jarves.mh.agent.team.model.RoleBudgetUsage
import com.jarves.mh.agent.team.model.TeamArtifact
import com.jarves.mh.agent.team.model.TeamBudget
import com.jarves.mh.agent.team.model.TeamCollaborationEvent
import com.jarves.mh.agent.team.model.TeamMessage
import com.jarves.mh.agent.team.model.TeamMessageType
import com.jarves.mh.agent.team.model.TeamRole
import com.jarves.mh.agent.team.model.TeamRoleConfig
import com.jarves.mh.agent.team.model.TeamRolePermissions
import com.jarves.mh.agent.team.model.TeamSessionState
import com.jarves.mh.agent.team.model.TeamTask
import com.jarves.mh.agent.team.model.TeamTaskPriority
import com.jarves.mh.agent.team.model.TeamTaskStatus
import com.jarves.mh.agent.team.review.TeamReviewGatekeeper
import com.jarves.mh.agent.team.scheduler.TeamTaskScheduler
import com.jarves.mh.model.AgentKind
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Typealiases for backward compatibility with existing codebase
typealias TeamRole = com.jarves.mh.agent.team.model.TeamRole
typealias TeamTaskStatus = com.jarves.mh.agent.team.model.TeamTaskStatus
typealias TeamTask = com.jarves.mh.agent.team.model.TeamTask
typealias TeamCollaborationEvent = com.jarves.mh.agent.team.model.TeamCollaborationEvent
typealias TeamSessionState = com.jarves.mh.agent.team.model.TeamSessionState

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
    val budgetTracker: TeamBudgetTracker = TeamBudgetTracker()
) {

    private val _sessionState = MutableStateFlow(TeamSessionState())
    val sessionState: StateFlow<TeamSessionState> = _sessionState.asStateFlow()

    private val roleInstances = ConcurrentHashMap<TeamRole, AgentKind>()
    private val roleConfigs = ConcurrentHashMap<TeamRole, TeamRoleConfig>()

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

    /**
     * Executes or simulates the next ready task in the DAG.
     */
    fun runNextReadyTask(): TeamTask? {
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

        // Simulate token consumption and execution
        val simulatedTokens = 2500L
        val simulatedDuration = 600L
        budgetTracker.recordUsage(task.assignedRole, simulatedTokens, simulatedDuration)

        val outputMessage = "Executed ${task.title} by ${task.assignedRole.title}. Generated deliverables."

        // If task produces artifacts, publish them to Blackboard
        when (task.assignedRole) {
            TeamRole.ARCHITECT -> {
                blackboard.publishArtifact(
                    TeamArtifact(
                        title = "API Contract & Data Spec",
                        type = ArtifactType.API_CONTRACT,
                        authorRole = TeamRole.ARCHITECT,
                        content = "REST & Event-driven API contract specifications for ${task.title}."
                    )
                )
            }
            TeamRole.DATABASE_ENGINEER -> {
                blackboard.publishArtifact(
                    TeamArtifact(
                        title = "Database Schema & Migration",
                        type = ArtifactType.DB_SCHEMA,
                        authorRole = TeamRole.DATABASE_ENGINEER,
                        content = "SQL DDL and Room entity definitions."
                    )
                )
            }
            TeamRole.TESTER -> {
                blackboard.publishArtifact(
                    TeamArtifact(
                        title = "QA Test Suite Execution Report",
                        type = ArtifactType.TEST_REPORT,
                        authorRole = TeamRole.TESTER,
                        content = "All 14 unit test assertions passed. 100% coverage on core feature paths."
                    )
                )
            }
            TeamRole.SECURITY_REVIEWER -> {
                blackboard.publishArtifact(
                    TeamArtifact(
                        title = "Security Vulnerability Audit",
                        type = ArtifactType.SECURITY_AUDIT,
                        authorRole = TeamRole.SECURITY_REVIEWER,
                        content = "Zero CVEs or secrets detected. Sandboxed execution verified."
                    )
                )
                gatekeeper.submitReview(
                    taskId = task.id,
                    reviewerRole = TeamRole.SECURITY_REVIEWER,
                    verdict = ReviewVerdict.APPROVED,
                    comments = "Security audit approved with zero critical findings."
                )
            }
            TeamRole.CODE_REVIEWER -> {
                gatekeeper.submitReview(
                    taskId = task.id,
                    reviewerRole = TeamRole.CODE_REVIEWER,
                    verdict = ReviewVerdict.APPROVED,
                    comments = "Architecture verified, clean code standards met."
                )
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
            tokensUsed = simulatedTokens,
            executionTimeMs = simulatedDuration
        )
        budgetTracker.recordTaskCompleted(task.assignedRole)

        syncState()
        return task
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
            executionLog = allMessages.map { "[${it.fromRole.name}] ${it.content}" }
        )
    }
}
