package com.codeair.mhe.cloud

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Typealiases for backward compatibility
typealias WorkloadRoutingPolicy = com.codeair.mhe.cloud.model.WorkloadRoutingPolicy
typealias WorkloadType = com.codeair.mhe.cloud.model.WorkloadType
typealias CloudWorkerSpec = com.codeair.mhe.cloud.model.CloudWorkerSpec
typealias HybridJob = com.codeair.mhe.cloud.model.HybridJob

/**
 * Cloud + Phone Hybrid Coordinator (Phase 12 v2.3).
 * Intelligently balances workloads across the local Android PRoot Linux environment
 * and high-compute remote cloud workers (FastBuild runners, GPU accelerators).
 *
 * Telemetry shown on screen is collected from the real device via [application]
 * (ActivityManager / BatteryManager / ConnectivityManager). No plausible-looking
 * default numbers are ever displayed as readings.
 */
class CloudHybridCoordinator(
    val decisionEngine: WorkloadDecisionEngine = WorkloadDecisionEngine(),
    val workerRegistry: CloudWorkerRegistry = CloudWorkerRegistry(),
    val syncEngine: CloudArtifactSyncEngine = CloudArtifactSyncEngine(),
    val jobRunner: CloudJobRunner = CloudJobRunner(),
    private val application: Application? = null
) {

    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        refreshDeviceMetrics()
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
     * Collects REAL device telemetry from the OS. CPU temperature has no public
     * Android API, so it stays null (UI shows "Not available") instead of a
     * fabricated number. With no Application, metrics stay uncollected.
     */
    fun refreshDeviceMetrics() {
        val app = application ?: return
        val metrics = collectRealDeviceMetrics(app)
        _state.value = _state.value.copy(deviceMetrics = metrics)
    }

    private fun collectRealDeviceMetrics(app: Application): DeviceResourceMetrics {
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val batteryManager = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 } ?: 0
        val batteryIntent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = plugged != 0

        val connectivityManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isMetered = try {
            connectivityManager.isActiveNetworkMetered
        } catch (e: Exception) {
            false
        }

        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isLowPowerMode = try {
            powerManager.isPowerSaveMode
        } catch (e: Exception) {
            false
        }

        return DeviceResourceMetrics(
            availableRamMb = (memInfo.availMem / (1024L * 1024L)),
            totalRamMb = (memInfo.totalMem / (1024L * 1024L)),
            batteryLevelPercent = batteryLevel,
            isCharging = isCharging,
            isLowPowerMode = isLowPowerMode,
            isWifiConnected = isWifi,
            isMeteredNetwork = isMetered,
            collected = true
        )
    }

    /**
     * Backward-compatible evaluation function. With no explicit RAM override,
     * the real collected reading is used, never a hardcoded default.
     */
    fun shouldOffloadToCloud(workloadType: WorkloadType, deviceAvailableRamMb: Long = -1L): Boolean {
        val ram = if (deviceAvailableRamMb >= 0) deviceAvailableRamMb else _state.value.deviceMetrics.availableRamMb
        val currentMetrics = _state.value.deviceMetrics.copy(availableRamMb = ram)
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
     * Pings all registered workers with a REAL reachability probe: HTTP GET to
     * each worker's /health endpoint, measuring actual latency. Unreachable
     * workers are marked UNREACHABLE with their last known latency. Nothing is
     * simulated: a dead or fictional endpoint reports as dead.
     */
    fun pingWorkers() {
        val workers = workerRegistry.getAllWorkers()
        if (workers.isEmpty()) return
        coordinatorScope.launch {
            workers.forEach { worker ->
                val (reachable, latencyMs) = probeWorkerHealth(worker.endpointUrl)
                val status = if (reachable) WorkerHealthStatus.HEALTHY else WorkerHealthStatus.OFFLINE
                workerRegistry.updateWorkerHealth(worker.id, status, latencyMs)
            }
            syncState()
        }
    }

    private fun probeWorkerHealth(endpointUrl: String): Pair<Boolean, Long> {
        val startedAt = System.currentTimeMillis()
        return try {
            val url = URL(endpointUrl.trimEnd('/') + "/health")
            val code = (url.openConnection() as HttpURLConnection).run {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
                instanceFollowRedirects = true
                connect()
                val responseCode = responseCode
                disconnect()
                responseCode
            }
            val latency = System.currentTimeMillis() - startedAt
            (code in 200..399) to latency
        } catch (e: Exception) {
            false to -1L
        }
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
