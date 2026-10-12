package com.jarves.mh.ui.coordinators

import com.jarves.mh.agent.team.AgentTeamOrchestrator
import com.jarves.mh.agent.team.model.TeamTask

/**
 * Multi-agent team domain coordinator (Milestone 1).
 *
 * Explicit owner of the team-session state machine, delegating to the real
 * [AgentTeamOrchestrator]. The orchestrator runs in demo mode by default
 * (Milestone 0 integrity: simulated reviews can never count as approvals);
 * wiring real per-role executors is Milestone 10 work.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class TeamCoordinator(
    private val orchestrator: AgentTeamOrchestrator,
) {
    fun planTeamProject(goal: String) {
        orchestrator.planProject(goal)
    }

    fun stepTeamTask(): TeamTask? {
        return orchestrator.runNextReadyTask()
    }

    fun autoRunTeamTasks(): Int {
        return orchestrator.autoRunAll()
    }

    fun cancelTeamSession() {
        orchestrator.cancelSession()
    }
}
