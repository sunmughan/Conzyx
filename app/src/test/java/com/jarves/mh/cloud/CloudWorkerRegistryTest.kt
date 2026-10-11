package com.jarves.mh.cloud

import com.jarves.mh.cloud.model.CloudWorkerSpec
import com.jarves.mh.cloud.model.WorkerHealthStatus
import com.jarves.mh.cloud.model.WorkloadType
import com.jarves.mh.cloud.registry.CloudWorkerRegistry
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
    fun testDefaultPresetsLoadedOnInit() {
        val workers = registry.getAllWorkers()
        assertTrue(workers.isNotEmpty())

        val fastBuild = registry.getWorkerById("worker-fastbuild-us-east")
        assertNotNull(fastBuild)
        assertEquals("us-east-1", fastBuild!!.region)
        assertEquals(16, fastBuild.cpuCores)
        assertEquals(32, fastBuild.ramGb)

        val gpuWorker = registry.getWorkerById("worker-gpu-us-central")
        assertNotNull(gpuWorker)
        assertTrue(gpuWorker!!.gpuAvailable)
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
        val workerId = "worker-fastbuild-us-east"

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
        val healthyBefore = registry.getHealthyWorkers()
        val firstId = healthyBefore.first().id

        registry.updateWorkerHealth(firstId, WorkerHealthStatus.OFFLINE)
        val healthyAfter = registry.getHealthyWorkers()

        assertFalse(healthyAfter.any { it.id == firstId })
    }

    @Test
    fun testGetWorkersForSpecificWorkload() {
        val gpuWorkers = registry.getWorkersForWorkload(WorkloadType.GPU_MODEL_INFERENCE)
        assertTrue(gpuWorkers.isNotEmpty())
        assertTrue(gpuWorkers.all { it.supportedWorkloads.contains(WorkloadType.GPU_MODEL_INFERENCE) })

        val gradleWorkers = registry.getWorkersForWorkload(WorkloadType.HEAVY_GRADLE_BUILD)
        assertTrue(gradleWorkers.isNotEmpty())
        assertTrue(gradleWorkers.all { it.supportedWorkloads.contains(WorkloadType.HEAVY_GRADLE_BUILD) })
    }

    @Test
    fun testResetToDefaultsRestoresInitialFleet() {
        val custom = CloudWorkerSpec(id = "temporary-node", name = "Temp", endpointUrl = "http://localhost", region = "local")
        registry.registerWorker(custom)
        assertNotNull(registry.getWorkerById("temporary-node"))

        registry.resetToDefaults()
        assertNull(registry.getWorkerById("temporary-node"))
        assertNotNull(registry.getWorkerById("worker-fastbuild-us-east"))
    }
}
