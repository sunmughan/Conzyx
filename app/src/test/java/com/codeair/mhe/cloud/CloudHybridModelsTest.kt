package com.codeair.mhe.cloud

import com.codeair.mhe.cloud.model.CloudArtifact
import com.codeair.mhe.cloud.model.CloudHybridState
import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.model.DeviceResourceMetrics
import com.codeair.mhe.cloud.model.ExecutionTarget
import com.codeair.mhe.cloud.model.HybridExecutionJob
import com.codeair.mhe.cloud.model.HybridJobState
import com.codeair.mhe.cloud.model.RoutingDecision
import com.codeair.mhe.cloud.model.WorkerHealthStatus
import com.codeair.mhe.cloud.model.WorkloadCharacteristics
import com.codeair.mhe.cloud.model.WorkloadRoutingPolicy
import com.codeair.mhe.cloud.model.WorkloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudHybridModelsTest {

    @Test
    fun testWorkloadRoutingPolicyDefinitions() {
        val policies = WorkloadRoutingPolicy.entries
        assertEquals(6, policies.size)

        val names = policies.map { it.name }.toSet()
        assertTrue(names.contains("HYBRID_AUTO"))
        assertTrue(names.contains("LOCAL_FIRST"))
        assertTrue(names.contains("CLOUD_OFFLOAD"))
        assertTrue(names.contains("COST_OPTIMIZED"))
        assertTrue(names.contains("PERFORMANCE_FIRST"))
        assertTrue(names.contains("OFFLINE_ONLY"))

        policies.forEach { policy ->
            assertTrue(policy.displayName.isNotBlank())
            assertTrue(policy.description.isNotBlank())
        }
    }

    @Test
    fun testWorkloadTypesAndDefaultHeavyClassification() {
        val types = WorkloadType.entries
        assertEquals(9, types.size)

        assertFalse(WorkloadType.LOCAL_EDIT.defaultHeavy)
        assertFalse(WorkloadType.LIGHT_SCRIPT.defaultHeavy)

        assertTrue(WorkloadType.HEAVY_GRADLE_BUILD.defaultHeavy)
        assertTrue(WorkloadType.GPU_MODEL_INFERENCE.defaultHeavy)
        assertTrue(WorkloadType.LARGE_TEST_SUITE.defaultHeavy)
        assertTrue(WorkloadType.DOCKER_CONTAINER_RUN.defaultHeavy)
        assertTrue(WorkloadType.AI_CODE_SYNTHESIS.defaultHeavy)
        assertTrue(WorkloadType.KNOWLEDGE_DISTILLATION.defaultHeavy)
        assertTrue(WorkloadType.CROSS_COMPILATION.defaultHeavy)
    }

    @Test
    fun testWorkloadCharacteristicsDefaults() {
        val localEditChars = WorkloadCharacteristics.defaultFor(WorkloadType.LOCAL_EDIT)
        assertEquals(1, localEditChars.estimatedCpuCores)
        assertEquals(256L, localEditChars.estimatedRamMb)
        assertFalse(localEditChars.requiresGpu)
        assertFalse(localEditChars.requiresDocker)

        val gradleChars = WorkloadCharacteristics.defaultFor(WorkloadType.HEAVY_GRADLE_BUILD)
        assertEquals(8, gradleChars.estimatedCpuCores)
        assertEquals(4096L, gradleChars.estimatedRamMb)
        assertFalse(gradleChars.requiresGpu)

        val gpuChars = WorkloadCharacteristics.defaultFor(WorkloadType.GPU_MODEL_INFERENCE)
        assertTrue(gpuChars.requiresGpu)
        assertEquals(8192L, gpuChars.estimatedRamMb)

        val dockerChars = WorkloadCharacteristics.defaultFor(WorkloadType.DOCKER_CONTAINER_RUN)
        assertTrue(dockerChars.requiresDocker)
    }

    @Test
    fun testDeviceResourceMetricsCalculations() {
        val metrics = DeviceResourceMetrics(
            availableRamMb = 2048L,
            totalRamMb = 8192L,
            batteryLevelPercent = 75,
            isCharging = false,
            thermalThrottling = false
        )
        // (8192 - 2048) / 8192 * 100 = 75%
        assertEquals(75, metrics.ramUsagePercent)

        val emptyMetrics = DeviceResourceMetrics(totalRamMb = 0L)
        assertEquals(0, emptyMetrics.ramUsagePercent)
    }

    @Test
    fun testCloudWorkerSpecDefaults() {
        val worker = CloudWorkerSpec(
            name = "Test Worker",
            endpointUrl = "https://test.example.invalid",
            region = "eu-west-1",
            costPerHourUsd = 0.08,
            cpuCores = 16,
            ramGb = 32
        )
        assertTrue(worker.id.isNotBlank())
        assertEquals("Test Worker", worker.name)
        // Unknown until a real /health probe runs: never HEALTHY by default.
        assertEquals(WorkerHealthStatus.OFFLINE, worker.healthStatus)
        assertEquals(-1L, worker.pingLatencyMs)
        assertTrue(worker.isAvailable)
        assertFalse(worker.gpuAvailable)
        assertEquals(9, worker.supportedWorkloads.size)
    }

    @Test
    fun testHybridExecutionJobLegacyConversion() {
        val worker = CloudWorkerSpec(
            name = "Remote Node",
            endpointUrl = "https://node.example.com",
            region = "us-east-1"
        )
        val decision = RoutingDecision(
            target = ExecutionTarget.CLOUD,
            reason = "High memory requirement",
            recommendedWorker = worker
        )
        val execJob = HybridExecutionJob(
            title = "Build Release APK",
            workloadType = WorkloadType.HEAVY_GRADLE_BUILD,
            routingDecision = decision,
            assignedWorker = worker,
            state = HybridJobState.COMPLETED,
            outputLogs = listOf("Line 1", "Line 2")
        )

        val legacy = execJob.toLegacyJob()
        assertEquals(execJob.id, legacy.id)
        assertEquals("Build Release APK", legacy.title)
        assertEquals(WorkloadType.HEAVY_GRADLE_BUILD, legacy.workloadType)
        assertTrue(legacy.routedToCloud)
        assertEquals(worker, legacy.assignedWorker)
        assertEquals("COMPLETED", legacy.status)
        assertEquals("Line 1\nLine 2", legacy.logs)
    }

    @Test
    fun testCloudArtifactModel() {
        val artifact = CloudArtifact(
            filename = "app-debug.apk",
            remoteUrl = "https://storage.cloud.com/app-debug.apk",
            localCachePath = "/data/local/app-debug.apk",
            sizeBytes = 12500000L,
            checksumSha256 = "abc123def456",
            artifactType = "APK_BINARY"
        )
        assertEquals("app-debug.apk", artifact.filename)
        assertEquals(12500000L, artifact.sizeBytes)
        assertEquals("abc123def456", artifact.checksumSha256)
        assertEquals("APK_BINARY", artifact.artifactType)
    }

    @Test
    fun testCloudHybridStateDefaults() {
        val state = CloudHybridState()
        assertEquals(WorkloadRoutingPolicy.HYBRID_AUTO, state.routingPolicy)
        assertTrue(state.registeredWorkers.isEmpty())
        assertTrue(state.activeJobs.isEmpty())
        assertTrue(state.completedJobs.isEmpty())
        assertEquals(0.0, state.totalCloudSpendUsd, 0.001)
        assertEquals(0, state.totalOffloadedJobs)
        assertEquals(0, state.totalLocalJobs)
        assertTrue(state.isAutoSyncEnabled)
    }
}
