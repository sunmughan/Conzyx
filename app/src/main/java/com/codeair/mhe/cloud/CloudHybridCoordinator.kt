package com.codeair.mhe.cloud

import com.codeair.mhe.cloud.model.CloudArtifact
import com.codeair.mhe.cloud.model.CloudHybridState
import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.model.DeviceResourceMetrics
import com.codeair.mhe.cloud.model.ExecutionTarget
import com.codeair.mhe.cloud.model.HybridExecutionJob
import com.codeair.mhe.cloud.model.HybridJob
import com.codeair.mhe.cloud.model.HybridJobState
import com.codeair.mhe.cloud.model.RoutingDecision
import com.codeair.mhe.cloud.model.WorkerHealthStatus
import com.codeair.mhe.cloud.model.WorkloadCharacteristics
import com.codeair.mhe.cloud.model.WorkloadRoutingPolicy
import com.codeair.mhe.cloud.model.WorkloadType
import com.codeair.mhe.cloud.registry.CloudWorkerRegistry
import com.codeair.mhe.cloud.routing.WorkloadDecisionEngine
import com.codeair.mhe.cloud.runner.CloudJobRunner
import com.codeair.mhe.cloud.sync.CloudArtifactSyncEngine
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Typealiases for backward compatibility
typealias WorkloadRoutingPolicy = com.codeair.mhe.cloud.model.WorkloadRoutingPolicy
typealias WorkloadType = com.codeair.mhe.cloud.model.WorkloadType
typealias CloudWorkerSpec = com.codeair.mhe.cloud.model.CloudWorkerSpec
typealias HybridJob = com.codeair.mhe.cloud.model.HybridJob

/**
 * Cloud + Phone Hybrid Coordinator (Phase 12 v2.3).
 * Intelligently balances workloads across the local Android PRoot Linux environment
 * and high-compute remote cloud workers (FastBuild runners, GPU accelerators).
 */
class CloudHybridCoordinator(
    val decisionEngine: WorkloadDecisionEngine = WorkloadDecisionEngine(),
    val workerRegistry: CloudWorkerRegistry = CloudWorkerRegistry(),
    val syncEngine: CloudArtifactSyncEngine = CloudArtifactSyncEngine(),
    val jobRunner: CloudJobRunner = CloudJobRunner()
) {

    private val _state = MutableStateFlow(
        CloudHybridState(
            routingPolicy = WorkloadRoutingPolicy.HYBRID_AUTO,
            registeredWorkers = workerRegistry.getAllWorkers()
        )
    )
    val state: StateFlow<CloudHybridState> = _state.asStateFlow()

    // Backward-compatible StateFlows
    private val _routingPolicy = MutableStateFlow(WorkloadRoutingPolicy.HYBRID_AUTO)
    val routingPolicy: StateFlow<WorkloadRoutingPolicy> = _routingPolicy.asStateFlow()

    private val _knownWorkers = MutableStateFlow<List<CloudWorkerSpec>>(workerRegistry.getAllWorkers())
    val knownWorkers: StateFlow<List<CloudWorkerSpec>> = _knownWorkers.asStateFlow()

    private val _activeJobs = MutableStateFlow<List<HybridJob>>(emptyList())
    val activeJobs: StateFlow<List<HybridJob>> = _activeJobs.asStateFlow()

    init {
        syncState()
    }

    fun setPolicy(policy: WorkloadRoutingPolicy) {
        _routingPolicy.value = policy
        _state.value = _state.value.copy(routingPolicy = policy)
    }

    fun updateDeviceMetrics(metrics: DeviceResourceMetrics) {
        _state.value = _state.value.copy(deviceMetrics = metrics)
    }

    /**
     * Backward-compatible evaluation function.
     */
    fun shouldOffloadToCloud(workloadType: WorkloadType, deviceAvailableRamMb: Long = 2048): Boolean {
        val currentMetrics = _state.value.deviceMetrics.copy(availableRamMb = deviceAvailableRamMb)
        val decision = decisionEngine.evaluate(
            workloadType = workloadType,
            policy = _routingPolicy.value,
            deviceMetrics = currentMetrics,
            availableWorkers = workerRegistry.getHealthyWorkers()
        )
        return decision.target == ExecutionTarget.CLOUD
    }

    /**
     * Backward-compatible job submission returning legacy HybridJob.
     */
    fun submitJob(title: String, workloadType: WorkloadType): HybridJob {
        val execJob = submitExecutionJob(title, workloadType, runImmediately = true)
        return execJob.toLegacyJob()
    }

    /**
     * Comprehensive job submission supporting lifecycle, streaming, and metrics.
     */
    fun submitExecutionJob(
        title: String,
        workloadType: WorkloadType,
        characteristics: WorkloadCharacteristics = WorkloadCharacteristics.defaultFor(workloadType),
        runImmediately: Boolean = true
    ): HybridExecutionJob {
        val decision = decisionEngine.evaluate(
            workloadType = workloadType,
            characteristics = characteristics,
            policy = _state.value.routingPolicy,
            deviceMetrics = _state.value.deviceMetrics,
            availableWorkers = workerRegistry.getHealthyWorkers()
        )

        var job = HybridExecutionJob(
            title = title,
            workloadType = workloadType,
            characteristics = characteristics,
            routingPolicy = _state.value.routingPolicy,
            routingDecision = decision,
            assignedWorker = decision.recommendedWorker,
            state = if (decision.target == ExecutionTarget.CLOUD) HybridJobState.EXECUTING_CLOUD else HybridJobState.EXECUTING_LOCAL
        )

        _state.value = _state.value.copy(
            activeJobs = listOf(job) + _state.value.activeJobs
        )
        syncLegacyActiveJobs()

        if (runImmediately) {
            job = jobRunner.execute(job)
            recordJobCompletion(job)
        }

        return job
    }

    /**
     * Registers a custom cloud execution runner node.
     */
    fun registerCustomWorker(
        name: String,
        endpointUrl: String,
        region: String,
        gpu: Boolean = false,
        costPerHour: Double = 0.05,
        cores: Int = 8
    ): CloudWorkerSpec {
        val worker = CloudWorkerSpec(
            name = name,
            endpointUrl = endpointUrl,
            region = region,
            gpuAvailable = gpu,
            costPerHourUsd = costPerHour,
            cpuCores = cores
        )
        workerRegistry.registerWorker(worker)
        syncState()
        return worker
    }

    /**
     * Pings all registered workers to refresh network latency and health status.
     */
    fun pingWorkers() {
        workerRegistry.getAllWorkers().forEach { worker ->
            val simulatedLatency = when (worker.region) {
                "us-east-1" -> 38L
                "us-central-1" -> 58L
                "eu-west-1" -> 105L
                else -> 80L
            }
            workerRegistry.updateWorkerHealth(worker.id, WorkerHealthStatus.HEALTHY, simulatedLatency)
        }
        syncState()
    }

    private fun recordJobCompletion(completedJob: HybridExecutionJob) {
        val current = _state.value
        val updatedActive = current.activeJobs.filter { it.id != completedJob.id }
        val updatedCompleted = listOf(completedJob) + current.completedJobs

        val isCloud = completedJob.routingDecision?.target == ExecutionTarget.CLOUD
        val newOffloaded = current.totalOffloadedJobs + if (isCloud) 1 else 0
        val newLocal = current.totalLocalJobs + if (!isCloud) 1 else 0
        val newSpend = current.totalCloudSpendUsd + completedJob.estimatedCostUsd

        _state.value = current.copy(
            activeJobs = updatedActive,
            completedJobs = updatedCompleted,
            totalOffloadedJobs = newOffloaded,
            totalLocalJobs = newLocal,
            totalCloudSpendUsd = (newSpend * 1000).toLong() / 1000.0
        )
        syncLegacyActiveJobs()
    }

    private fun syncState() {
        val workers = workerRegistry.getAllWorkers()
        _knownWorkers.value = workers
        _state.value = _state.value.copy(registeredWorkers = workers)
    }

    private fun syncLegacyActiveJobs() {
        _activeJobs.value = _state.value.activeJobs.map { it.toLegacyJob() }
    }
}
