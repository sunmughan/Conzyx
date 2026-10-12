package com.codeair.mhe.cloud.registry

import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.model.WorkerHealthStatus
import com.codeair.mhe.cloud.model.WorkloadType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-Safe Cloud Worker Fleet Registry (Phase 12).
 * Manages registered cloud execution nodes, tracks real-time heartbeat latency,
 * and maintains active capacity states.
 */
class CloudWorkerRegistry {

    private val workersMap = ConcurrentHashMap<String, CloudWorkerSpec>()
    private val _workersFlow = MutableStateFlow<List<CloudWorkerSpec>>(emptyList())
    val workersFlow: StateFlow<List<CloudWorkerSpec>> = _workersFlow.asStateFlow()

    init {
        loadDefaultPresets()
    }

    fun registerWorker(worker: CloudWorkerSpec): CloudWorkerSpec {
        workersMap[worker.id] = worker
        syncFlow()
        return worker
    }

    fun unregisterWorker(workerId: String): Boolean {
        val removed = workersMap.remove(workerId) != null
        if (removed) {
            syncFlow()
        }
        return removed
    }

    fun updateWorkerHealth(
        workerId: String,
        status: WorkerHealthStatus,
        latencyMs: Long = -1L
    ): CloudWorkerSpec? {
        val current = workersMap[workerId] ?: return null
        val updated = current.copy(
            healthStatus = status,
            isAvailable = status == WorkerHealthStatus.HEALTHY || status == WorkerHealthStatus.DEGRADED,
            pingLatencyMs = if (latencyMs >= 0) latencyMs else current.pingLatencyMs
        )
        workersMap[workerId] = updated
        syncFlow()
        return updated
    }

    fun getWorkerById(workerId: String): CloudWorkerSpec? = workersMap[workerId]

    fun getAllWorkers(): List<CloudWorkerSpec> = workersMap.values.sortedBy { it.pingLatencyMs }

    fun getHealthyWorkers(): List<CloudWorkerSpec> {
        return workersMap.values
            .filter { (it.healthStatus == WorkerHealthStatus.HEALTHY || it.healthStatus == WorkerHealthStatus.DEGRADED) && it.isAvailable }
            .sortedBy { it.pingLatencyMs }
    }

    fun getWorkersForWorkload(workloadType: WorkloadType): List<CloudWorkerSpec> {
        return getHealthyWorkers().filter { it.supportedWorkloads.contains(workloadType) }
    }

    fun resetToDefaults() {
        workersMap.clear()
        loadDefaultPresets()
    }

    private fun loadDefaultPresets() {
        val defaults = listOf(
            CloudWorkerSpec(
                id = "worker-fastbuild-us-east",
                name = "FastBuild Runner (x86_64 16-Core)",
                endpointUrl = "https://build-cloud.pocketdev.io",
                region = "us-east-1",
                isAvailable = true,
                costPerHourUsd = 0.05,
                gpuAvailable = false,
                cpuCores = 16,
                ramGb = 32,
                pingLatencyMs = 42L,
                healthStatus = WorkerHealthStatus.HEALTHY,
                supportedWorkloads = setOf(
                    WorkloadType.HEAVY_GRADLE_BUILD,
                    WorkloadType.LARGE_TEST_SUITE,
                    WorkloadType.CROSS_COMPILATION,
                    WorkloadType.DOCKER_CONTAINER_RUN
                )
            ),
            CloudWorkerSpec(
                id = "worker-gpu-us-central",
                name = "AI GPU Accelerator (NVIDIA L40S)",
                endpointUrl = "https://gpu-cloud.pocketdev.io",
                region = "us-central-1",
                isAvailable = true,
                costPerHourUsd = 0.20,
                gpuAvailable = true,
                cpuCores = 12,
                ramGb = 48,
                pingLatencyMs = 65L,
                healthStatus = WorkerHealthStatus.HEALTHY,
                supportedWorkloads = setOf(
                    WorkloadType.GPU_MODEL_INFERENCE,
                    WorkloadType.AI_CODE_SYNTHESIS,
                    WorkloadType.KNOWLEDGE_DISTILLATION
                )
            ),
            CloudWorkerSpec(
                id = "worker-eu-highperf",
                name = "EU High-Performance Node (32-Core)",
                endpointUrl = "https://eu-cloud.pocketdev.io",
                region = "eu-west-1",
                isAvailable = true,
                costPerHourUsd = 0.08,
                gpuAvailable = false,
                cpuCores = 32,
                ramGb = 64,
                pingLatencyMs = 110L,
                healthStatus = WorkerHealthStatus.HEALTHY,
                supportedWorkloads = WorkloadType.entries.toSet()
            )
        )
        defaults.forEach { workersMap[it.id] = it }
        syncFlow()
    }

    private fun syncFlow() {
        _workersFlow.value = workersMap.values.sortedBy { it.pingLatencyMs }
    }
}
