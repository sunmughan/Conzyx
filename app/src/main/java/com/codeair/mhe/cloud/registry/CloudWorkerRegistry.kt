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
 *
 * NO preset workers: the registry starts EMPTY. Every listed worker is one the
 * user explicitly added (Add Runner) or that was registered by a real backend.
 * Fabricated endpoints, latencies and prices are never shown as real workers.
 */
class CloudWorkerRegistry {

    private val workersMap = ConcurrentHashMap<String, CloudWorkerSpec>()
    private val _workersFlow = MutableStateFlow<List<CloudWorkerSpec>>(emptyList())
    val workersFlow: StateFlow<List<CloudWorkerSpec>> = _workersFlow.asStateFlow()

    init {
        syncFlow()
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
        // No fabricated presets: reset means an empty fleet.
        workersMap.clear()
        syncFlow()
    }

    private fun syncFlow() {
        _workersFlow.value = workersMap.values.sortedBy { it.pingLatencyMs }
    }
}
