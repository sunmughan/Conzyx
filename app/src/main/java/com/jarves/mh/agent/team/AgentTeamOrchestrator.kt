package com.jarves.mh.agent.team

import com.jarves.mh.model.AgentKind
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Roles in a Multi-Agent Engineering Team (Phase 11).
 */
enum class TeamRole(val title: String, val description: String) {
    MANAGER("Engineering Manager", "Decomposes high-level requirements into subtasks, delegates, and oversees team velocity."),
    ARCHITECT("Software Architect", "Designs system blueprints, interfaces, data schemas, and technical contracts."),
    DEVELOPER("Fullstack Developer", "Implements business logic, APIs, UI components, and integrations."),
    UI_ENGINEER("UI/UX Specialist", "Refines user interfaces, Compose views, animations, and responsive layouts."),
    BACKEND_ENGINEER("Backend Specialist", "Constructs endpoints, server logic, database interactions, and middleware."),
    DATABASE_ENGINEER("Database Architect", "Creates schemas, queries, migrations, and caching layers."),
    TESTER("QA & Test Engineer", "Designs unit tests, integration suites, edge cases, and verifies execution."),
    SECURITY_REVIEWER("Security Auditor", "Inspects code for vulnerabilities, secret leaks, permission violations, and risk levels."),
    CODE_REVIEWER("Code Reviewer", "Enforces architecture consistency, SOLID design patterns, and code clarity."),
    DEVOPS_ENGINEER("DevOps & CI/CD", "Manages build scripts, Gradle configurations, GitHub workflows, and container runtimes.")
}

enum class TeamTaskStatus {
    PENDING,
    IN_PROGRESS,
    UNDER_REVIEW,
    COMPLETED,
    FAILED
}

data class TeamTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val assignedRole: TeamRole,
    val status: TeamTaskStatus = TeamTaskStatus.PENDING,
    val output: String = "",
    val reviewFeedback: String = "",
    val dependencies: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class TeamCollaborationEvent(
    val id: String = UUID.randomUUID().toString(),
    val fromRole: TeamRole,
    val toRole: TeamRole?,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class TeamSessionState(
    val activeGoal: String = "",
    val isRunning: Boolean = false,
    val activeRole: TeamRole? = null,
    val tasks: List<TeamTask> = emptyList(),
    val events: List<TeamCollaborationEvent> = emptyList()
)

/**
 * Multi-Agent Engineering Team Coordinator (Phase 11).
 * Decomposes software engineering requests across specialized agents with shared workspace context.
 */
class AgentTeamOrchestrator {

    private val _sessionState = MutableStateFlow(TeamSessionState())
    val sessionState: StateFlow<TeamSessionState> = _sessionState.asStateFlow()

    private val roleInstances = ConcurrentHashMap<TeamRole, AgentKind>()

    init {
        // Register default agent engines for specialized roles
        TeamRole.values().forEach { role ->
            roleInstances[role] = when (role) {
                TeamRole.MANAGER, TeamRole.ARCHITECT, TeamRole.CODE_REVIEWER -> AgentKind.CLAUDE_CODE
                TeamRole.SECURITY_REVIEWER, TeamRole.DEVOPS_ENGINEER -> AgentKind.ANTIGRAVITY
                else -> AgentKind.ANTIGRAVITY
            }
        }
    }

    /**
     * Initializes a multi-agent engineering workflow for a given user request.
     */
    fun planProject(userGoal: String): List<TeamTask> {
        val tasks = listOf(
            TeamTask(
                title = "System Architecture & Contract Design",
                description = "Define data models, component interactions, and requirements breakdown for: $userGoal",
                assignedRole = TeamRole.ARCHITECT
            ),
            TeamTask(
                title = "Core Feature Implementation",
                description = "Develop business logic and core components fulfilling the architecture specification.",
                assignedRole = TeamRole.DEVELOPER
            ),
            TeamTask(
                title = "UI & User Experience Polish",
                description = "Ensure clean user interface, responsiveness, and state handling.",
                assignedRole = TeamRole.UI_ENGINEER
            ),
            TeamTask(
                title = "Automated Test Suite Creation",
                description = "Write unit tests and verify build sanity against edge cases.",
                assignedRole = TeamRole.TESTER
            ),
            TeamTask(
                title = "Security & Vulnerability Audit",
                description = "Scan for sensitive leaks, improper permissions, and dangerous commands.",
                assignedRole = TeamRole.SECURITY_REVIEWER
            ),
            TeamTask(
                title = "Code Quality & Architectural Review",
                description = "Final peer review before user approval and git commit.",
                assignedRole = TeamRole.CODE_REVIEWER
            )
        )

        _sessionState.value = TeamSessionState(
            activeGoal = userGoal,
            isRunning = true,
            activeRole = TeamRole.MANAGER,
            tasks = tasks,
            events = listOf(
                TeamCollaborationEvent(
                    fromRole = TeamRole.MANAGER,
                    toRole = null,
                    message = "Decomposed goal into ${tasks.size} engineering tasks across specialized roles."
                )
            )
        )
        return tasks
    }

    fun updateTaskStatus(taskId: String, status: TeamTaskStatus, output: String = "", feedback: String = "") {
        val current = _sessionState.value
        val updatedTasks = current.tasks.map { task ->
            if (task.id == taskId) {
                task.copy(status = status, output = output.ifBlank { task.output }, reviewFeedback = feedback)
            } else task
        }

        val allDone = updatedTasks.all { it.status == TeamTaskStatus.COMPLETED }
        val nextPending = updatedTasks.firstOrNull { it.status == TeamTaskStatus.PENDING || it.status == TeamTaskStatus.IN_PROGRESS }

        _sessionState.value = current.copy(
            tasks = updatedTasks,
            isRunning = !allDone,
            activeRole = nextPending?.assignedRole ?: if (allDone) null else current.activeRole
        )
    }

    fun broadcastMessage(from: TeamRole, to: TeamRole?, message: String) {
        val event = TeamCollaborationEvent(fromRole = from, toRole = to, message = message)
        _sessionState.value = _sessionState.value.let { it.copy(events = it.events + event) }
    }

    fun cancelSession() {
        _sessionState.value = _sessionState.value.copy(isRunning = false, activeRole = null)
    }
}
