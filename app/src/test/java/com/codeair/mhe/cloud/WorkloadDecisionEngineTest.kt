package com.codeair.mhe.cloud

import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.model.DeviceResourceMetrics
import com.codeair.mhe.cloud.model.ExecutionTarget
import com.codeair.mhe.cloud.model.WorkerHealthStatus
import com.codeair.mhe.cloud.model.WorkloadCharacteristics
import com.codeair.mhe.cloud.model.WorkloadRoutingPolicy
import com.codeair.mhe.cloud.model.WorkloadType
import com.codeair.mhe.cloud.routing.WorkloadDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkloadDecisionEngineTest {

    private lateinit var engine: WorkloadDecisionEngine
    private lateinit var sampleWorkers: List<CloudWorkerSpec>

    @Before
    fun setUp() {
        engine = WorkloadDecisionEngine()
        sampleWorkers = listOf(
            CloudWorkerSpec(
                id = "worker-fast",
                name = "FastBuild Runner",
                endpointUrl = "https://fast.pocketdev.io",
                region = "us-east-1",
                cpuCores = 16,
                ramGb = 32,
                pingLatencyMs = 35L,
                gpuAvailable = false,
                healthStatus = WorkerHealthStatus.HEALTHY
            ),
            CloudWorkerSpec(
                id = "worker-gpu",
                name = "GPU Cloud Node",
                endpointUrl = "https://gpu.pocketdev.io",
                region = "us-central-1",
                cpuCores = 8,
                ramGb = 64,
                pingLatencyMs = 60L,
                gpuAvailable = true,
                healthStatus = WorkerHealthStatus.HEALTHY
            )
        )
    }

    @Test
    fun testOfflineOnlyPolicyAlwaysForcesLocal() {
        val decision = engine.evaluate(
            workloadType = WorkloadType.HEAVY_GRADLE_BUILD,
            policy = WorkloadRoutingPolicy.OFFLINE_ONLY,
            deviceMetrics = DeviceResourceMetrics(availableRamMb = 512L),
            availableWorkers = sampleWorkers
        )

        assertEquals(ExecutionTarget.LOCAL, decision.target)
        assertTrue(decision.reason.contains("Offline Only", ignoreCase = true))
    }

    @Test
    fun testCloudOffloadPolicyForcesCloudWhenWorkersAvailable() {
        val decision = engine.evaluate(
            workloadType = WorkloadType.LIGHT_SCRIPT,
            policy = WorkloadRoutingPolicy.CLOUD_OFFLOAD,
            deviceMetrics = DeviceResourceMetrics(availableRamMb = 4096L),
            availableWorkers = sampleWorkers
        )

        assertEquals(ExecutionTarget.CLOUD, decision.target)
        assertNotNull(decision.recommendedWorker)
    }

    @Test
    fun testCloudOffloadPolicyFallsBackToLocalWhenNoWorkers() {
        val decision = engine.evaluate(
            workloadType = WorkloadType.LIGHT_SCRIPT,
            policy = WorkloadRoutingPolicy.CLOUD_OFFLOAD,
            deviceMetrics = DeviceResourceMetrics(availableRamMb = 4096L),
            availableWorkers = emptyList()
        )

        assertEquals(ExecutionTarget.LOCAL, decision.target)
        assertTrue(decision.reason.contains("fallback", ignoreCase = true))
    }

    @Test
    fun testWorkloadRequiringGpuRoutesToGpuWorker() {
        val decision = engine.evaluate(
            workloadType = WorkloadType.GPU_MODEL_INFERENCE,
            policy = WorkloadRoutingPolicy.HYBRID_AUTO,
            deviceMetrics = DeviceResourceMetrics(),
            availableWorkers = sampleWorkers
        )

        assertEquals(ExecutionTarget.CLOUD, decision.target)
        assertNotNull(decision.recommendedWorker)
        assertTrue(decision.recommendedWorker!!.gpuAvailable)
        assertEquals("worker-gpu", decision.recommendedWorker!!.id)
    }

    @Test
    fun testWorkloadRequiringDockerRoutesToCloudRunner() {
        val decision = engine.evaluate(
            workloadType = WorkloadType.DOCKER_CONTAINER_RUN,
            policy = WorkloadRoutingPolicy.HYBRID_AUTO,
            deviceMetrics = DeviceResourceMetrics(),
            availableWorkers = sampleWorkers
        )

        assertEquals(ExecutionTarget.CLOUD, decision.target)
        assertNotNull(decision.recommendedWorker)
    }

    @Test
    fun testHighRamDeficitTriggersCloudOffload() {
        val deviceMetrics = DeviceResourceMetrics(
            availableRamMb = 1024L,
            totalRamMb = 4096L
        )
        val characteristics = WorkloadCharacteristics(
            estimatedCpuCores = 8,
            estimatedRamMb = 4096L // Deficit of 3072 MB
        )

        val decision = engine.evaluate(
            workloadType = WorkloadType.HEAVY_GRADLE_BUILD,
            characteristics = characteristics,
            policy = WorkloadRoutingPolicy.HYBRID_AUTO,
            deviceMetrics = deviceMetrics,
            availableWorkers = sampleWorkers
        )

        assertEquals(ExecutionTarget.CLOUD, decision.target)
        assertTrue(decision.scoreBreakdown.containsKey("ram_pressure"))
    }

    @Test
    fun testCriticalBatteryUnpluggedOffloadsHeavyWorkload() {
        val deviceMetrics = DeviceResourceMetrics(
            availableRamMb = 3000L,
            batteryLevelPercent = 12,
            isCharging = false
        )

        val decision = engine.evaluate(
            workloadType = WorkloadType.LARGE_TEST_SUITE,
            policy = WorkloadRoutingPolicy.HYBRID_AUTO,
            deviceMetrics = deviceMetrics,
            availableWorkers = sampleWorkers
        )

        assertEquals(ExecutionTarget.CLOUD, decision.target)
        assertTrue(decision.scoreBreakdown.containsKey("battery_critical"))
    }

    @Test
    fun testMeteredCellularPenalizesCloudAndExecutesLocally() {
        val deviceMetrics = DeviceResourceMetrics(
            availableRamMb = 4096L,
            isWifiConnected = false,
            isMeteredNetwork = true,
            batteryLevelPercent = 90
        )

        val decision = engine.evaluate(
            workloadType = WorkloadType.LIGHT_SCRIPT,
            policy = WorkloadRoutingPolicy.HYBRID_AUTO,
            deviceMetrics = deviceMetrics,
            availableWorkers = sampleWorkers
        )

        assertEquals(ExecutionTarget.LOCAL, decision.target)
        assertTrue(decision.scoreBreakdown.containsKey("metered_network_penalty"))
    }

    @Test
    fun testLightweightScriptRunsLocallyWithAdequateResources() {
        val deviceMetrics = DeviceResourceMetrics(
            availableRamMb = 4096L,
            batteryLevelPercent = 85,
            isCharging = true
        )

        val decision = engine.evaluate(
            workloadType = WorkloadType.LIGHT_SCRIPT,
            policy = WorkloadRoutingPolicy.HYBRID_AUTO,
            deviceMetrics = deviceMetrics,
            availableWorkers = sampleWorkers
        )

        assertEquals(ExecutionTarget.LOCAL, decision.target)
    }
}
