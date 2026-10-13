package com.codeair.mhe.cloud

import com.codeair.mhe.cloud.model.DeviceResourceMetrics
import com.codeair.mhe.cloud.model.ExecutionTarget
import com.codeair.mhe.cloud.model.HybridJobState
import com.codeair.mhe.cloud.model.WorkloadRoutingPolicy
import com.codeair.mhe.cloud.model.WorkloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudHybridCoordinatorTest {

    private lateinit var coordinator: CloudHybridCoordinator

    @Before
    fun setUp() {
        coordinator = CloudHybridCoordinator()
    }

    @Test
    fun testInitialCoordinatorState() {
        val state = coordinator.state.value
        assertEquals(WorkloadRoutingPolicy.HYBRID_AUTO, state.routingPolicy)
        // No fabricated workers: the fleet starts empty.
        assertTrue(state.registeredWorkers.isEmpty())
        assertTrue(state.activeJobs.isEmpty())
        assertTrue(state.completedJobs.isEmpty())
        assertEquals(0, state.totalOffloadedJobs)
        assertEquals(0, state.totalLocalJobs)
    }

    @Test
    fun testUpdatePolicyFlows() {
        coordinator.setPolicy(WorkloadRoutingPolicy.PERFORMANCE_FIRST)

        assertEquals(WorkloadRoutingPolicy.PERFORMANCE_FIRST, coordinator.routingPolicy.value)
        assertEquals(WorkloadRoutingPolicy.PERFORMANCE_FIRST, coordinator.state.value.routingPolicy)
    }

    @Test
    fun testShouldOffloadToCloudWithLowRam() {
        coordinator.setPolicy(WorkloadRoutingPolicy.HYBRID_AUTO)

        // Low RAM device should offload heavy Gradle build
        val shouldOffload = coordinator.shouldOffloadToCloud(
            workloadType = WorkloadType.HEAVY_GRADLE_BUILD,
            deviceAvailableRamMb = 512L
        )
        assertTrue(shouldOffload)

        // Offline only policy prevents offloading even with 1MB RAM
        coordinator.setPolicy(WorkloadRoutingPolicy.OFFLINE_ONLY)
        val shouldOffloadOffline = coordinator.shouldOffloadToCloud(
            workloadType = WorkloadType.HEAVY_GRADLE_BUILD,
            deviceAvailableRamMb = 512L
        )
        assertFalse(shouldOffloadOffline)
    }

    @Test
    fun testSubmitLegacyJob() {
        val job = coordinator.submitJob("Build Debug APK", WorkloadType.HEAVY_GRADLE_BUILD)

        assertNotNull(job)
        assertEquals("Build Debug APK", job.title)
        assertEquals(WorkloadType.HEAVY_GRADLE_BUILD, job.workloadType)
        assertTrue(job.logs.isNotBlank())

        val state = coordinator.state.value
        assertEquals(1, state.completedJobs.size)
        assertTrue(state.totalOffloadedJobs + state.totalLocalJobs >= 1)
    }

    @Test
    fun testSubmitExecutionJobLifecycle() {
        coordinator.setPolicy(WorkloadRoutingPolicy.CLOUD_OFFLOAD)
        // A real, user-registered healthy worker pointing at an unreachable
        // endpoint: the cloud path must report NOT_CONFIGURED with a clear
        // message, never a fake COMPLETED with canned logs.
        coordinator.workerRegistry.registerWorker(
            com.codeair.mhe.cloud.model.CloudWorkerSpec(
                id = "ci-worker-1",
                name = "CI Worker",
                endpointUrl = "https://ci.example.invalid",
                region = "eu-central-1",
                healthStatus = com.codeair.mhe.cloud.model.WorkerHealthStatus.HEALTHY
            )
        )

        val job = coordinator.submitExecutionJob(
            title = "Run Integration Tests",
            workloadType = WorkloadType.LARGE_TEST_SUITE,
            runImmediately = true
        )

        // Milestone 0 honesty: no real cloud worker endpoint is reachable in
        // this build, so the cloud path must report NOT_CONFIGURED with a clear
        // message, never a fake COMPLETED with canned logs.
        assertEquals(HybridJobState.NOT_CONFIGURED, job.state)
        assertEquals(ExecutionTarget.CLOUD, job.routingDecision?.target)
        assertTrue(job.errorMessage?.isNotBlank() == true)
        assertTrue(job.outputLogs.isNotEmpty())

        val state = coordinator.state.value
        assertEquals(1, state.totalOffloadedJobs)
        assertEquals(1, state.completedJobs.size)
    }

    @Test
    fun testRegisterCustomWorker() {
        val worker = coordinator.registerCustomWorker(
            name = "Dedicated CI Worker",
            endpointUrl = "https://ci.mycompany.com",
            region = "eu-central-1",
            gpu = false,
            costPerHour = 0.10,
            cores = 32
        )

        assertNotNull(worker)
        assertEquals("Dedicated CI Worker", worker.name)
        val knownWorkers = coordinator.knownWorkers.value
        assertTrue(knownWorkers.any { it.name == "Dedicated CI Worker" })
    }

    @Test
    fun testPingWorkersMarksUnreachableOffline() {
        // A worker pointing at a port nothing listens on: the real /health
        // probe must fail and mark it OFFLINE, never a simulated latency.
        coordinator.workerRegistry.registerWorker(
            com.codeair.mhe.cloud.model.CloudWorkerSpec(
                id = "dead-worker-1",
                name = "Dead Worker",
                endpointUrl = "http://127.0.0.1:1",
                region = "local",
                healthStatus = com.codeair.mhe.cloud.model.WorkerHealthStatus.HEALTHY
            )
        )
        coordinator.pingWorkers()

        // pingWorkers probes asynchronously; poll briefly for the real result.
        var observed: com.codeair.mhe.cloud.model.WorkerHealthStatus? = null
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            observed = coordinator.state.value.registeredWorkers
                .find { it.id == "dead-worker-1" }?.healthStatus
            if (observed == com.codeair.mhe.cloud.model.WorkerHealthStatus.OFFLINE) break
            Thread.sleep(200)
        }
        assertEquals(com.codeair.mhe.cloud.model.WorkerHealthStatus.OFFLINE, observed)
    }

    @Test
    fun testUpdateDeviceMetrics() {
        val newMetrics = DeviceResourceMetrics(
            availableRamMb = 1200L,
            batteryLevelPercent = 45,
            isCharging = false,
            thermalThrottling = true
        )
        coordinator.updateDeviceMetrics(newMetrics)

        assertEquals(1200L, coordinator.state.value.deviceMetrics.availableRamMb)
        assertTrue(coordinator.state.value.deviceMetrics.thermalThrottling)
    }
}
