package com.codeair.mhe.cloud.routing

import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.model.DeviceResourceMetrics
import com.codeair.mhe.cloud.model.ExecutionTarget
import com.codeair.mhe.cloud.model.RoutingDecision
import com.codeair.mhe.cloud.model.WorkerHealthStatus
import com.codeair.mhe.cloud.model.WorkloadCharacteristics
import com.codeair.mhe.cloud.model.WorkloadRoutingPolicy
import com.codeair.mhe.cloud.model.WorkloadType

/**
 * Multi-Factor Algorithmic Workload Routing Engine (Phase 12).
 * Determines optimal execution location (Local PRoot vs Cloud Worker)
 * by evaluating hardware metrics, task complexity, network cost, and policy constraints.
 */
class WorkloadDecisionEngine {

    /**
     * Computes the execution destination with clear reasoning and score breakdown.
     */
    fun evaluate(
        workloadType: WorkloadType,
        characteristics: WorkloadCharacteristics = WorkloadCharacteristics.defaultFor(workloadType),
        policy: WorkloadRoutingPolicy = WorkloadRoutingPolicy.HYBRID_AUTO,
        deviceMetrics: DeviceResourceMetrics = DeviceResourceMetrics(),
        availableWorkers: List<CloudWorkerSpec> = emptyList()
    ): RoutingDecision {
        val scores = mutableMapOf<String, Double>()
        val healthyWorkers = availableWorkers.filter {
            (it.healthStatus == WorkerHealthStatus.HEALTHY || it.healthStatus == WorkerHealthStatus.DEGRADED) &&
                it.isAvailable &&
                it.supportedWorkloads.contains(workloadType)
        }

        val bestWorker = selectBestWorker(healthyWorkers, characteristics)

        // 1. Strict Policy Overrides
        if (policy == WorkloadRoutingPolicy.OFFLINE_ONLY) {
            scores["policy_offline_forced"] = 1.0
            return RoutingDecision(
                target = ExecutionTarget.LOCAL,
                reason = "Offline Only policy is active; remote execution disabled.",
                scoreBreakdown = scores
            )
        }

        if (policy == WorkloadRoutingPolicy.CLOUD_OFFLOAD) {
            scores["policy_cloud_forced"] = 1.0
            return if (bestWorker != null) {
                RoutingDecision(
                    target = ExecutionTarget.CLOUD,
                    reason = "Cloud Offload policy aggressively routes task to remote runner '${bestWorker.name}'.",
                    scoreBreakdown = scores,
                    recommendedWorker = bestWorker
                )
            } else {
                RoutingDecision(
                    target = ExecutionTarget.LOCAL,
                    reason = "Cloud Offload policy requested, but no healthy cloud worker is available. Graceful fallback to local.",
                    scoreBreakdown = scores
                )
            }
        }

        // 2. Hardware Hard Requirements
        if (characteristics.requiresGpu) {
            val gpuWorker = healthyWorkers.firstOrNull { it.gpuAvailable }
            scores["gpu_required"] = 1.0
            return if (gpuWorker != null) {
                RoutingDecision(
                    target = ExecutionTarget.CLOUD,
                    reason = "Workload requires dedicated GPU acceleration (found ${gpuWorker.name} in ${gpuWorker.region}).",
                    scoreBreakdown = scores,
                    recommendedWorker = gpuWorker
                )
            } else {
                RoutingDecision(
                    target = ExecutionTarget.LOCAL,
                    reason = "GPU acceleration required but no GPU worker online. Attempting CPU fallback locally.",
                    scoreBreakdown = scores
                )
            }
        }

        if (characteristics.requiresDocker) {
            scores["docker_required"] = 1.0
            return if (bestWorker != null) {
                RoutingDecision(
                    target = ExecutionTarget.CLOUD,
                    reason = "Workload requires Docker container daemon, offloaded to cloud host.",
                    scoreBreakdown = scores,
                    recommendedWorker = bestWorker
                )
            } else {
                RoutingDecision(
                    target = ExecutionTarget.LOCAL,
                    reason = "Docker required but no cloud runner available. Attempting local PRoot execution.",
                    scoreBreakdown = scores
                )
            }
        }

        // 3. Device Physical Limits & Thermal Safety
        var cloudScore = 0.0

        // RAM Headroom Check
        val ramDeficit = characteristics.estimatedRamMb - deviceMetrics.availableRamMb
        if (ramDeficit > 0) {
            cloudScore += 0.45
            scores["ram_pressure"] = 0.45
        } else if (deviceMetrics.availableRamMb < 1500L && workloadType.defaultHeavy) {
            cloudScore += 0.30
            scores["ram_low"] = 0.30
        }

        // Battery & Thermal Health Check
        if (deviceMetrics.thermalThrottling && workloadType.defaultHeavy) {
            cloudScore += 0.35
            scores["thermal_throttling"] = 0.35
        }

        if (deviceMetrics.batteryLevelPercent < 20 && !deviceMetrics.isCharging && workloadType.defaultHeavy) {
            cloudScore += 0.30
            scores["battery_critical"] = 0.30
        }

        // Heavy Workload Check
        if (workloadType.defaultHeavy) {
            cloudScore += 0.25
            scores["heavy_workload"] = 0.25
        }

        // Network Penalty
        if (deviceMetrics.isMeteredNetwork && !deviceMetrics.isWifiConnected) {
            cloudScore -= 0.30
            scores["metered_network_penalty"] = -0.30
        }

        // Policy Biasing
        when (policy) {
            WorkloadRoutingPolicy.PERFORMANCE_FIRST -> {
                cloudScore += 0.30
                scores["perf_policy_boost"] = 0.30
            }
            WorkloadRoutingPolicy.COST_OPTIMIZED -> {
                cloudScore -= 0.35
                scores["cost_policy_penalty"] = -0.35
            }
            WorkloadRoutingPolicy.LOCAL_FIRST -> {
                cloudScore -= 0.40
                scores["local_first_penalty"] = -0.40
            }
            WorkloadRoutingPolicy.HYBRID_AUTO -> {
                scores["hybrid_balanced"] = 0.0
            }
            else -> {}
        }

        scores["final_cloud_score"] = cloudScore

        val offloadThreshold = 0.40
        val shouldOffload = cloudScore >= offloadThreshold && bestWorker != null

        return if (shouldOffload) {
            val dominantFactor = scores.filter { it.value > 0.2 && it.key != "final_cloud_score" }.keys.joinToString(", ")
            RoutingDecision(
                target = ExecutionTarget.CLOUD,
                reason = "Offloaded to ${bestWorker?.name} (score: ${(cloudScore * 100).toInt()}%, factors: $dominantFactor).",
                scoreBreakdown = scores,
                recommendedWorker = bestWorker
            )
        } else {
            val localReason = if (bestWorker == null && cloudScore >= offloadThreshold) {
                "Cloud runners unreachable; running locally."
            } else if (scores["metered_network_penalty"] != null) {
                "Executed locally to save cellular mobile data."
            } else {
                "Device has sufficient resources (RAM: ${deviceMetrics.availableRamMb}MB, battery: ${deviceMetrics.batteryLevelPercent}%)."
            }
            RoutingDecision(
                target = ExecutionTarget.LOCAL,
                reason = localReason,
                scoreBreakdown = scores
            )
        }
    }

    private fun selectBestWorker(
        workers: List<CloudWorkerSpec>,
        characteristics: WorkloadCharacteristics
    ): CloudWorkerSpec? {
        if (workers.isEmpty()) return null

        return workers.minByOrNull { worker ->
            var penalty = worker.pingLatencyMs.toDouble()
            if (characteristics.requiresGpu && !worker.gpuAvailable) penalty += 10_000
            if (worker.cpuCores < characteristics.estimatedCpuCores) penalty += 500
            if (worker.healthStatus == WorkerHealthStatus.DEGRADED) penalty += 200
            penalty
        }
    }
}
