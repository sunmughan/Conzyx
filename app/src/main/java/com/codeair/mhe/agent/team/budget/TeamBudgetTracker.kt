package com.codeair.mhe.agent.team.budget

import com.codeair.mhe.agent.team.model.RoleBudgetUsage
import com.codeair.mhe.agent.team.model.TeamBudget
import com.codeair.mhe.agent.team.model.TeamRole
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-Agent Team Budget & Quota Tracker (Phase 11).
 * Tracks real-time token consumption, per-role cost distribution, execution latency,
 * and enforces safety quotas to prevent runaway LLM costs.
 */
class TeamBudgetTracker(
    initialTokenQuota: Long = 100_000L,
    initialMaxExecutionTimeMs: Long = 600_000L,
    private var costPer1kTokens: Double = 0.003
) {
    private val tokenQuota = AtomicLong(initialTokenQuota)
    private val maxExecutionTimeMs = AtomicLong(initialMaxExecutionTimeMs)
    private val totalTokensUsed = AtomicLong(0L)
    private val totalDurationMs = AtomicLong(0L)
    /** Runs whose token usage was unknown (executors report null): their
     * cost cannot be counted, so the elapsed-time limit is the binding
     * constraint for them, and the count is surfaced in the summary. */
    private val unknownTokenRuns = AtomicLong(0L)

    private val roleTokens = ConcurrentHashMap<TeamRole, AtomicLong>()
    private val roleTasks = ConcurrentHashMap<TeamRole, AtomicLong>()

    init {
        TeamRole.entries.forEach { role ->
            roleTokens[role] = AtomicLong(0L)
            roleTasks[role] = AtomicLong(0L)
        }
    }

    fun setTokenQuota(quota: Long) {
        tokenQuota.set(quota)
    }

    fun setMaxExecutionTime(timeMs: Long) {
        maxExecutionTimeMs.set(timeMs)
    }

    fun setCostPer1kTokens(cost: Double) {
        costPer1kTokens = cost
    }

    /**
     * Records one run. [tokens] is null when the executor genuinely does
     * not report token usage: the run is counted as unmeasurable (never
     * invented as 0 cost) while its duration still accrues toward the
     * time limit, so an unmeasured run cannot run unbounded.
     */
    fun recordUsage(role: TeamRole, tokens: Long?, durationMs: Long = 0L) {
        if (tokens != null && tokens > 0) {
            totalTokensUsed.addAndGet(tokens)
            roleTokens[role]?.addAndGet(tokens)
        } else if (tokens == null) {
            unknownTokenRuns.incrementAndGet()
        }
        if (durationMs > 0) {
            totalDurationMs.addAndGet(durationMs)
        }
    }

    fun recordTaskCompleted(role: TeamRole) {
        roleTasks[role]?.incrementAndGet()
    }

    /**
     * Trips on either limit: tokens consumed, or elapsed execution time.
     * Previously only tokens were compared, and real executors report null
     * tokens, so the quota could never trip; the time limit stored here
     * was never evaluated at all.
     */
    fun isQuotaExceeded(): Boolean {
        return totalTokensUsed.get() >= tokenQuota.get() ||
            totalDurationMs.get() >= maxExecutionTimeMs.get()
    }

    fun getBudgetSummary(): TeamBudget {
        val usedTokens = totalTokensUsed.get()
        val quota = tokenQuota.get()
        val cost = (usedTokens.toDouble() / 1000.0) * costPer1kTokens

        return TeamBudget(
            tokenQuota = quota,
            totalTokensUsed = usedTokens,
            maxExecutionTimeMs = maxExecutionTimeMs.get(),
            elapsedTimeMs = totalDurationMs.get(),
            costPer1kTokens = costPer1kTokens,
            totalCostUsd = (cost * 1000).toLong() / 1000.0, // rounded to 3 decimals
            isQuotaExceeded = isQuotaExceeded(),
            unknownTokenRuns = unknownTokenRuns.get().toInt()
        )
    }

    fun getRoleUsage(role: TeamRole): RoleBudgetUsage {
        val tokens = roleTokens[role]?.get() ?: 0L
        val tasks = roleTasks[role]?.get()?.toInt() ?: 0
        val cost = (tokens.toDouble() / 1000.0) * costPer1kTokens

        return RoleBudgetUsage(
            role = role,
            tokensUsed = tokens,
            tasksCompleted = tasks,
            costUsd = (cost * 1000).toLong() / 1000.0
        )
    }

    fun getAllRoleUsages(): Map<TeamRole, RoleBudgetUsage> {
        return TeamRole.entries.associateWith { getRoleUsage(it) }
    }

    fun reset() {
        totalTokensUsed.set(0L)
        totalDurationMs.set(0L)
        unknownTokenRuns.set(0L)
        TeamRole.entries.forEach { role ->
            roleTokens[role]?.set(0L)
            roleTasks[role]?.set(0L)
        }
    }
}
