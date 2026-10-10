package com.jarves.mh.cloud

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WorkloadRoutingPolicy {
    LOCAL_FIRST,
    CLOUD_OFFLOAD,
    HYBRID_AUTO
}

enum class WorkloadType {
    LOCAL_EDIT,
    LIGHT_SCRIPT,
    HEAVY_GRADLE_BUILD,
    GPU_MODEL_INFERENCE,
    LARGE_TEST_SUITE,
    DOCKER_CONTAINER_RUN
}

data class CloudWorkerSpec(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val endpointUrl: String,
    val region: String,
    val isAvailable: Boolean = true,
    val costPerHourUsd: Double = 0.0,
    val gpuAvailable: Boolean = false
)

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
 * Cloud + Phone Hybrid Coordinator (Phase 12).
 * Determines whether to execute builds, tests, or AI reasoning on-device (PRoot)
 * or offload to high-power cloud workers.
 */
class CloudHybridCoordinator {

    private val _routingPolicy = MutableStateFlow(WorkloadRoutingPolicy.HYBRID_AUTO)
    val routingPolicy: StateFlow<WorkloadRoutingPolicy> = _routingPolicy.asStateFlow()

    private val _knownWorkers = MutableStateFlow(
        listOf(
            CloudWorkerSpec(
                name = "FastBuild Runner (x86_64 16-Core)",
                endpointUrl = "https://build-cloud.pocketdev.io",
                region = "us-east-1",
                isAvailable = true,
                costPerHourUsd = 0.05,
                gpuAvailable = false
            ),
            CloudWorkerSpec(
                name = "AI GPU Accelerator (NVIDIA L40S)",
                endpointUrl = "https://gpu-cloud.pocketdev.io",
                region = "us-central-1",
                isAvailable = true,
                costPerHourUsd = 0.20,
                gpuAvailable = true
            )
        )
    )
    val knownWorkers: StateFlow<List<CloudWorkerSpec>> = _knownWorkers.asStateFlow()

    private val _activeJobs = MutableStateFlow<List<HybridJob>>(emptyList())
    val activeJobs: StateFlow<List<HybridJob>> = _activeJobs.asStateFlow()

    fun setPolicy(policy: WorkloadRoutingPolicy) {
        _routingPolicy.value = policy
    }

    /**
     * Determines whether [workloadType] should be offloaded to cloud given device capabilities.
     */
    fun shouldOffloadToCloud(workloadType: WorkloadType, deviceAvailableRamMb: Long = 2048): Boolean {
        return when (_routingPolicy.value) {
            WorkloadRoutingPolicy.CLOUD_OFFLOAD -> true
            WorkloadRoutingPolicy.LOCAL_FIRST -> false
            WorkloadRoutingPolicy.HYBRID_AUTO -> {
                when (workloadType) {
                    WorkloadType.HEAVY_GRADLE_BUILD -> deviceAvailableRamMb < 3000
                    WorkloadType.GPU_MODEL_INFERENCE -> true
                    WorkloadType.LARGE_TEST_SUITE -> deviceAvailableRamMb < 2500
                    WorkloadType.DOCKER_CONTAINER_RUN -> true
                    WorkloadType.LOCAL_EDIT, WorkloadType.LIGHT_SCRIPT -> false
                }
            }
        }
    }

    fun submitJob(title: String, workloadType: WorkloadType): HybridJob {
        val offload = shouldOffloadToCloud(workloadType)
        val worker = if (offload) _knownWorkers.value.firstOrNull { it.isAvailable } else null
        val job = HybridJob(
            title = title,
            workloadType = workloadType,
            routedToCloud = offload,
            assignedWorker = worker,
            status = if (offload) "DISPATCHED_TO_CLOUD" else "RUNNING_LOCALLY"
        )
        _activeJobs.value = listOf(job) + _activeJobs.value
        return job
    }
}
