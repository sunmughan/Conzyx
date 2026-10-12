package com.jarves.mh.cloud.exec

import com.jarves.mh.cloud.model.CloudWorkerSpec
import com.jarves.mh.core.result.OperationResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Verdict of the cost gate before dispatch. */
data class CostAuthorization(
    val authorized: Boolean,
    val estimatedCostUsd: Double,
    val reason: String
)

/** Immutable ledger entry for one finished (or refused) cloud job. */
data class CloudSpendEntry(
    val jobId: String,
    val workerId: String,
    val workerName: String,
    val durationSec: Long,
    val costUsd: Double,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Cloud cost controller (Milestone 11).
 *
 * Enforces spend discipline BEFORE anything is dispatched:
 * - per-job estimated cost cap ([maxCostPerJobUsd])
 * - rolling 30-day budget cap ([monthlyBudgetUsd])
 *
 * Cost model: durationSec/3600 * worker.costPerHourUsd. Estimated cost uses the
 * job's estimated duration; actual cost is recorded on completion. A refusal is
 * an honest FAILED-style refusal (never silent, never "success").
 */
class CloudCostController(
    private val monthlyBudgetUsd: Double = 25.0,
    private val maxCostPerJobUsd: Double = 5.0,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val ledger = ConcurrentHashMap<String, CloudSpendEntry>()
    private val refusedCount = AtomicLong(0)

    fun estimateCost(worker: CloudWorkerSpec, durationSec: Long): Double =
        (durationSec.coerceAtLeast(0) / 3600.0) * worker.costPerHourUsd.coerceAtLeast(0.0)

    fun authorizeDispatch(worker: CloudWorkerSpec, estimatedDurationSec: Long, jobTitle: String): CostAuthorization {
        val estimated = estimateCost(worker, estimatedDurationSec)
        if (estimated > maxCostPerJobUsd) {
            refusedCount.incrementAndGet()
            return CostAuthorization(
                authorized = false,
                estimatedCostUsd = estimated,
                reason = "Refused: estimated cost \$${"%.4f".format(estimated)} for '$jobTitle' exceeds " +
                    "per-job cap \$${"%.2f".format(maxCostPerJobUsd)}. Nothing was dispatched."
            )
        }
        val spent30d = spendLast30Days()
        if (spent30d + estimated > monthlyBudgetUsd) {
            refusedCount.incrementAndGet()
            return CostAuthorization(
                authorized = false,
                estimatedCostUsd = estimated,
                reason = "Refused: 30-day spend \$${"%.4f".format(spent30d)} + estimated " +
                    "\$${"%.4f".format(estimated)} would exceed monthly budget " +
                    "\$${"%.2f".format(monthlyBudgetUsd)}. Nothing was dispatched."
            )
        }
        return CostAuthorization(
            authorized = true,
            estimatedCostUsd = estimated,
            reason = "Authorized: estimated \$${"%.4f".format(estimated)} within caps " +
                "(30-day spend \$${"%.4f".format(spent30d)} of \$${"%.2f".format(monthlyBudgetUsd)})."
        )
    }

    fun recordSpend(entry: CloudSpendEntry) {
        ledger[entry.jobId] = entry
    }

    fun spendLast30Days(nowMs: Long = clock()): Double {
        val cutoff = nowMs - 30L * 24 * 60 * 60 * 1000
        return ledger.values.filter { it.timestampMs >= cutoff }.sumOf { it.costUsd }
    }

    fun totalSpend(): Double = ledger.values.sumOf { it.costUsd }

    fun jobCount(): Int = ledger.size

    fun refusedDispatches(): Long = refusedCount.get()

    fun ledgerSnapshot(): List<CloudSpendEntry> = ledger.values.sortedByDescending { it.timestampMs }

    fun checkBudgetState(): OperationResult =
        OperationResult.success(
            logs = listOf(
                "30-day cloud spend: \$${"%.4f".format(spendLast30Days())} of \$${"%.2f".format(monthlyBudgetUsd)} budget.",
                "Jobs recorded: ${jobCount()}, refused dispatches: ${refusedDispatches()}."
            ),
            verificationEvidence = "ledger holds ${jobCount()} entries"
        )
}
