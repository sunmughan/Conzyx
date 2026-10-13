package com.codeair.mhe.cloud

import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.model.WorkerHealthStatus
import com.codeair.mhe.cloud.model.WorkloadType
import com.codeair.mhe.cloud.registry.CloudWorkerRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudWorkerRegistryTest {

    private lateinit var registry: CloudWorkerRegistry

    @Before
    fun setUp() {
        registry = CloudWorkerRegistry()
    }

    @Test
    fun testRegistryStartsEmpty() {
        // No fabricated preset workers: the fleet starts empty. Only workers
        // the user explicitly adds may ever appear.
        val workers = registry.getAllWorkers()
        assertTrue(workers.isEmpty())
        assertNull(registry.getWorkerById("worker-fastbuild-us-east"))
        assertNull(registry.getWorkerById("worker-gpu-us-central"))
        assertNull(registry.getWorkerById("worker-eu-highperf"))
    }

    @Test
    fun testRegisterAndUnregisterWorker() {
        val custom = CloudWorkerSpec(
            id = "custom-node-1",
            name = "Private Lab Worker",
            endpointUrl = "https://private.lab.local",
            region = "ap-south-1",
            cpuCores = 32,
            ramGb = 128
        )

        registry.registerWorker(custom)
        val retrieved = registry.getWorkerById("custom-node-1")
        assertNotNull(retrieved)
        assertEquals("Private Lab Worker", retrieved!!.name)

        val unregistered = registry.unregisterWorker("custom-node-1")
        assertTrue(unregistered)
        assertNull(registry.getWorkerById("custom-node-1"))

        val secondUnregister = registry.unregisterWorker("custom-node-1")
        assertFalse(secondUnregister)
    }

    @Test
    fun testUpdateWorkerHealthAndAvailability() {
        val worker = CloudWorkerSpec(
            id = "worker-probe-1",
            name = "Probe Worker",
            endpointUrl = "https://probe.example.invalid",
            region = "us-east-1",
            cpuCores = 16,
            ramGb = 32,
            healthStatus = WorkerHealthStatus.HEALTHY
        )
        registry.registerWorker(worker)
        val workerId = "worker-probe-1"

        val updated = registry.updateWorkerHealth(
            workerId = workerId,
            status = WorkerHealthStatus.DEGRADED,
            latencyMs = 120L
        )
        assertNotNull(updated)
        assertEquals(WorkerHealthStatus.DEGRADED, updated!!.healthStatus)
        assertEquals(120L, updated.pingLatencyMs)
        assertTrue(updated.isAvailable)

        val offline = registry.updateWorkerHealth(
            workerId = workerId,
            status = WorkerHealthStatus.OFFLINE,
            latencyMs = 999L
        )
        assertNotNull(offline)
        assertEquals(WorkerHealthStatus.OFFLINE, offline!!.healthStatus)
        assertFalse(offline.isAvailable)

        // Non-existent worker returns null
        val nonExistent = registry.updateWorkerHealth("unknown-id", WorkerHealthStatus.HEALTHY)
        assertNull(nonExistent)
    }

    @Test
    fun testGetHealthyWorkersExcludesOfflineNodes() {
        val healthy = CloudWorkerSpec(
            id = "healthy-node-1",
            name = "Healthy Node",
            endpointUrl = "https://healthy.example.invalid",
            region = "us-east-1",
            healthStatus = WorkerHealthStatus.HEALTHY
        )
        registry.registerWorker(healthy)
        val healthyBefore = registry.getHealthyWorkers()
        val firstId = healthyBefore.first().id

        registry.updateWorkerHealth(firstId, WorkerHealthStatus.OFFLINE)
        val healthyAfter = registry.getHealthyWorkers()

        assertFalse(healthyAfter.any { it.id == firstId })
    }

    @Test
    fun testGetWorkersForSpecificWorkload() {
        registry.registerWorker(
            CloudWorkerSpec(
                id = "gpu-node-1",
                name = "GPU Node",
                endpointUrl = "https://gpu.example.invalid",
                region = "us-central-1",
                gpuAvailable = true,
                healthStatus = WorkerHealthStatus.HEALTHY,
                supportedWorkloads = setOf(WorkloadType.GPU_MODEL_INFERENCE)
            )
        )
        registry.registerWorker(
            CloudWorkerSpec(
                id = "gradle-node-1",
                name = "Gradle Node",
                endpointUrl = "https://gradle.example.invalid",
                region = "us-east-1",
                healthStatus = WorkerHealthStatus.HEALTHY,
                supportedWorkloads = setOf(WorkloadType.HEAVY_GRADLE_BUILD)
            )
        )

        val gpuWorkers = registry.getWorkersForWorkload(WorkloadType.GPU_MODEL_INFERENCE)
        assertTrue(gpuWorkers.isNotEmpty())
        assertTrue(gpuWorkers.all { it.supportedWorkloads.contains(WorkloadType.GPU_MODEL_INFERENCE) })

        val gradleWorkers = registry.getWorkersForWorkload(WorkloadType.HEAVY_GRADLE_BUILD)
        assertTrue(gradleWorkers.isNotEmpty())
        assertTrue(gradleWorkers.all { it.supportedWorkloads.contains(WorkloadType.HEAVY_GRADLE_BUILD) })
    }

    @Test
    fun testResetToDefaultsClearsFleet() {
        val custom = CloudWorkerSpec(id = "temporary-node", name = "Temp", endpointUrl = "http://localhost", region = "local")
        registry.registerWorker(custom)
        assertNotNull(registry.getWorkerById("temporary-node"))

        // Reset means an empty fleet: no fabricated presets come back.
        registry.resetToDefaults()
        assertNull(registry.getWorkerById("temporary-node"))
        assertTrue(registry.getAllWorkers().isEmpty())
    }
}
