package com.codeair.mhe.collab.agent

import com.codeair.mhe.collab.model.SharedAgentJob
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Multi-User Collaborative AI Agent Job Dispatcher (Phase 13).
 * Broadcasts agent reasoning steps, tool calls, and human approvals across team session members.
 */
class SharedAgentJobDispatcher {

    private val jobsMap = ConcurrentHashMap<String, SharedAgentJob>()
    private val _jobsFlow = MutableStateFlow<List<SharedAgentJob>>(emptyList())
    val jobsFlow: StateFlow<List<SharedAgentJob>> = _jobsFlow.asStateFlow()

    fun dispatchJob(
        sessionShareId: String,
        initiatedBy: String,
        prompt: String,
        assignedRole: String = "Full-Stack Dev"
    ): SharedAgentJob {
        val job = SharedAgentJob(
            id = UUID.randomUUID().toString(),
            sessionShareId = sessionShareId,
            initiatedBy = initiatedBy,
            assignedRole = assignedRole,
            prompt = prompt,
            status = "RUNNING",
            logs = listOf("Job initiated by $initiatedBy with role $assignedRole", "Analyzing collaborative context...")
        )
        jobsMap[job.id] = job
        syncFlow()
        return job
    }

    fun appendLog(jobId: String, logLine: String) {
        val current = jobsMap[jobId] ?: return
        jobsMap[jobId] = current.copy(logs = current.logs + logLine)
        syncFlow()
    }

    fun requestApproval(jobId: String, actionDescription: String): Boolean {
        val current = jobsMap[jobId] ?: return false
        jobsMap[jobId] = current.copy(
            status = "REQUIRES_APPROVAL",
            pendingApprovalAction = actionDescription,
            logs = current.logs + "[Approval Required] $actionDescription"
        )
        syncFlow()
        return true
    }

    fun approveJob(jobId: String, approvedBy: String): Boolean {
        val current = jobsMap[jobId] ?: return false
        if (current.status != "REQUIRES_APPROVAL") return false
        jobsMap[jobId] = current.copy(
            status = "RUNNING",
            pendingApprovalAction = null,
            logs = current.logs + "[Approved by $approvedBy] Executing gated action..."
        )
        syncFlow()
        return true
    }

    fun rejectJob(jobId: String, rejectedBy: String): Boolean {
        val current = jobsMap[jobId] ?: return false
        jobsMap[jobId] = current.copy(
            status = "CANCELLED",
            pendingApprovalAction = null,
            logs = current.logs + "[Rejected by $rejectedBy] Operation cancelled."
        )
        syncFlow()
        return true
    }

    fun completeJob(jobId: String): Boolean {
        val current = jobsMap[jobId] ?: return false
        jobsMap[jobId] = current.copy(
            status = "COMPLETED",
            logs = current.logs + "[Success] Job completed successfully."
        )
        syncFlow()
        return true
    }

    fun failJob(jobId: String, errorMessage: String): Boolean {
        val current = jobsMap[jobId] ?: return false
        jobsMap[jobId] = current.copy(
            status = "FAILED",
            logs = current.logs + "[Error] $errorMessage"
        )
        syncFlow()
        return true
    }

    fun getJobById(jobId: String): SharedAgentJob? = jobsMap[jobId]

    fun getAllJobs(): List<SharedAgentJob> = jobsMap.values.sortedByDescending { it.timestamp }

    fun getActiveJobs(): List<SharedAgentJob> =
        jobsMap.values.filter { it.status == "RUNNING" || it.status == "REQUIRES_APPROVAL" }

    fun loadJobs(jobs: List<SharedAgentJob>) {
        jobsMap.clear()
        jobs.forEach { jobsMap[it.id] = it }
        syncFlow()
    }

    fun clearJobs() {
        jobsMap.clear()
        syncFlow()
    }

    private fun syncFlow() {
        _jobsFlow.value = jobsMap.values.sortedByDescending { it.timestamp }
    }
}
