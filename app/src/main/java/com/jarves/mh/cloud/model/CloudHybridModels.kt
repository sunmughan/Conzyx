package com.jarves.mh.cloud.model

import java.util.UUID

/**
 * Workload routing policy controlling whether tasks run locally or offload to cloud.
 */
enum class WorkloadRoutingPolicy(val displayName: String, val description: String) {
    HYBRID_AUTO("Hybrid Auto", "Dynamically routes tasks based on device RAM, battery, thermal limits, and task size."),
    LOCAL_FIRST("Local First", "Prefers executing on-device in PRoot Linux container, only offloading if impossible."),
    CLOUD_OFFLOAD("Cloud Offload", "Aggressively offloads compute-heavy workloads to remote cloud runners."),
    COST_OPTIMIZED("Cost Optimized", "Minimizes cloud execution spend by preferring local execution or free tiers."),
    PERFORMANCE_FIRST("Performance First", "Maximal velocity: routes heavy builds and tests to highest-core cloud workers."),
    OFFLINE_ONLY("Offline Only", "Strictly disables network offloading; runs only locally or queues tasks.")
}

/**
 * Supported categories of workloads for hybrid dispatch.
 */
enum class WorkloadType(val displayName: String, val defaultHeavy: Boolean) {
    LOCAL_EDIT("Local Code Edit", false),
    LIGHT_SCRIPT("Lightweight Script / Lint", false),
    HEAVY_GRADLE_BUILD("Heavy Android Gradle Build", true),
    GPU_MODEL_INFERENCE("GPU Model Inference / LLM", true),
    LARGE_TEST_SUITE("Full Integration Test Suite", true),
    DOCKER_CONTAINER_RUN("Docker Container Run", true),
    AI_CODE_SYNTHESIS("Multi-File AI Code Synthesis", true),
    KNOWLEDGE_DISTILLATION("Knowledge Base Distillation", true),
    CROSS_COMPILATION("Native C/Rust Cross Compilation", true)
}

/**
 * Compute and memory characteristics of an individual task.
 */
data class WorkloadCharacteristics(
    val estimatedCpuCores: Int = 1,
    val estimatedRamMb: Long = 512L,
    val requiresGpu: Boolean = false,
    val requiresDocker: Boolean = false,
    val estimatedDurationSec: Int = 15,
    val payloadSizeBytes: Long = 0L,
    val cacheable: Boolean = true
) {
    companion object {
        fun defaultFor(type: WorkloadType): WorkloadCharacteristics = when (type) {
            WorkloadType.LOCAL_EDIT -> WorkloadCharacteristics(1, 256L, false, false, 2, 0L)
            WorkloadType.LIGHT_SCRIPT -> WorkloadCharacteristics(1, 512L, false, false, 5, 0L)
            WorkloadType.HEAVY_GRADLE_BUILD -> WorkloadCharacteristics(8, 4096L, false, false, 120, 10_000_000L)
            WorkloadType.GPU_MODEL_INFERENCE -> WorkloadCharacteristics(4, 8192L, true, false, 30, 1_000_000L)
            WorkloadType.LARGE_TEST_SUITE -> WorkloadCharacteristics(4, 3072L, false, false, 90, 5_000_000L)
            WorkloadType.DOCKER_CONTAINER_RUN -> WorkloadCharacteristics(4, 4096L, false, true, 60, 20_000_000L)
            WorkloadType.AI_CODE_SYNTHESIS -> WorkloadCharacteristics(2, 2048L, true, false, 45, 500_000L)
            WorkloadType.KNOWLEDGE_DISTILLATION -> WorkloadCharacteristics(4, 3072L, false, false, 80, 8_000_000L)
            WorkloadType.CROSS_COMPILATION -> WorkloadCharacteristics(8, 4096L, false, false, 100, 15_000_000L)
        }
    }
}

/**
 * Health and availability status of a registered cloud worker.
 */
enum class WorkerHealthStatus {
    HEALTHY,
    DEGRADED,
    OFFLINE,
    DRAINING,
    AUTHENTICATION_ERROR
}

/**
 * Specification and capabilities of a cloud worker node.
 */
data class CloudWorkerSpec(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val endpointUrl: String,
    val region: String,
    val authKey: String = "",
    val isAvailable: Boolean = true,
    val costPerHourUsd: Double = 0.0,
    val gpuAvailable: Boolean = false,
    val cpuCores: Int = 8,
    val ramGb: Int = 16,
    val maxConcurrentJobs: Int = 4,
    val supportedWorkloads: Set<WorkloadType> = WorkloadType.entries.toSet(),
    val pingLatencyMs: Long = 45L,
    val healthStatus: WorkerHealthStatus = WorkerHealthStatus.HEALTHY
)

/**
 * Real-time device physical and hardware telemetry.
 */
data class DeviceResourceMetrics(
    val availableRamMb: Long = 2048L,
    val totalRamMb: Long = 8192L,
    val batteryLevelPercent: Int = 85,
    val isCharging: Boolean = true,
    val isLowPowerMode: Boolean = false,
    val isWifiConnected: Boolean = true,
    val isMeteredNetwork: Boolean = false,
    val cpuTemperatureCelsius: Double = 36.5,
    val thermalThrottling: Boolean = false
) {
    val ramUsagePercent: Int
        get() = if (totalRamMb > 0) (((totalRamMb - availableRamMb).toDouble() / totalRamMb) * 100).toInt() else 0
}

/**
 * Execution destination for a task.
 */
enum class ExecutionTarget {
    LOCAL,
    CLOUD
}

/**
 * Routing decision explaining why a task executes locally vs on cloud.
 */
data class RoutingDecision(
    val target: ExecutionTarget,
    val reason: String,
    val scoreBreakdown: Map<String, Double> = emptyMap(),
    val recommendedWorker: CloudWorkerSpec? = null
)

/**
 * Downloadable artifact produced by cloud execution (e.g. built APK, test reports).
 */
data class CloudArtifact(
    val id: String = UUID.randomUUID().toString(),
    val filename: String,
    val remoteUrl: String = "",
    val localCachePath: String = "",
    val sizeBytes: Long = 0L,
    val checksumSha256: String = "",
    val artifactType: String = "BUILD_OUTPUT"
)

/**
 * Stages in the lifecycle of a hybrid job.
 */
enum class HybridJobState {
    QUEUED,
    ROUTING,
    SYNCING_PAYLOAD,
    EXECUTING_LOCAL,
    EXECUTING_CLOUD,
    SYNCING_ARTIFACTS,
    COMPLETED,
    FAILED,
    CANCELLED,
    /** No reachable worker endpoint / backend is configured: nothing executed (Milestone 0). */
    NOT_CONFIGURED
}

/**
 * Full lifecycle tracking of an executed hybrid job.
 */
data class HybridExecutionJob(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val workloadType: WorkloadType,
    val characteristics: WorkloadCharacteristics = WorkloadCharacteristics.defaultFor(workloadType),
    val routingPolicy: WorkloadRoutingPolicy = WorkloadRoutingPolicy.HYBRID_AUTO,
    val routingDecision: RoutingDecision? = null,
    val assignedWorker: CloudWorkerSpec? = null,
    val state: HybridJobState = HybridJobState.QUEUED,
    val localCommand: String = "",
    val remotePayloadUri: String? = null,
    val remoteJobId: String? = null,
    val outputLogs: List<String> = emptyList(),
    val producedArtifacts: List<CloudArtifact> = emptyList(),
    val exitCode: Int? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val durationMs: Long = 0L,
    val estimatedCostUsd: Double = 0.0,
    val errorMessage: String? = null
) {
    /**
     * Backward-compatible representation matching existing HybridJob.
     */
    fun toLegacyJob(): HybridJob = HybridJob(
        id = id,
        title = title,
        workloadType = workloadType,
        routedToCloud = routingDecision?.target == ExecutionTarget.CLOUD,
        assignedWorker = assignedWorker,
        status = state.name,
        logs = outputLogs.joinToString("\n"),
        timestamp = startedAt
    )
}

/**
 * Backward-compatible Job definition for existing UI and orchestrator callers.
 */
data class HybridJob(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val workloadType: WorkloadType,
    val routedToCloud: Boolean,
    val assignedWorker: CloudWorkerSpec? = null,
    val status: String = "QUEUED",
    val logs: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Aggregate state of the Cloud Hybrid Coordinator.
 */
data class CloudHybridState(
    val routingPolicy: WorkloadRoutingPolicy = WorkloadRoutingPolicy.HYBRID_AUTO,
    val registeredWorkers: List<CloudWorkerSpec> = emptyList(),
    val deviceMetrics: DeviceResourceMetrics = DeviceResourceMetrics(),
    val activeJobs: List<HybridExecutionJob> = emptyList(),
    val completedJobs: List<HybridExecutionJob> = emptyList(),
    val totalCloudSpendUsd: Double = 0.0,
    val totalOffloadedJobs: Int = 0,
    val totalLocalJobs: Int = 0,
    val isAutoSyncEnabled: Boolean = true,
    val statusMessage: String? = null
)
