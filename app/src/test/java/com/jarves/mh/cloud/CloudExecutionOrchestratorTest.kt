package com.jarves.mh.cloud

import com.jarves.mh.cloud.exec.CloudArtifactDownloader
import com.jarves.mh.cloud.exec.CloudCostController
import com.jarves.mh.cloud.exec.CloudExecutionClient
import com.jarves.mh.cloud.exec.CloudExecutionOrchestrator
import com.jarves.mh.cloud.exec.CloudPayloadUploader
import com.jarves.mh.cloud.exec.CloudTransportResponse
import com.jarves.mh.cloud.model.CloudWorkerSpec
import com.jarves.mh.cloud.model.ExecutionTarget
import com.jarves.mh.cloud.model.HybridExecutionJob
import com.jarves.mh.cloud.model.HybridJobState
import com.jarves.mh.cloud.model.RoutingDecision
import com.jarves.mh.cloud.model.WorkloadType
import com.jarves.mh.cloud.sync.CloudArtifactSyncEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class CloudExecutionOrchestratorTest {

    private lateinit var workspaceDir: File
    private lateinit var artifactDir: File
    private val worker = CloudWorkerSpec(
        name = "Test Worker",
        endpointUrl = "https://worker.test",
        region = "test-1",
        authKey = "secret-key",
        costPerHourUsd = 0.05
    )
    private val payload = "fake-apk-bytes".toByteArray()
    private val payloadSha256: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    }

    @Before
    fun setUp() {
        workspaceDir = File(System.getProperty("java.io.tmpdir"), "orch_ws_" + System.nanoTime())
        workspaceDir.mkdirs()
        File(workspaceDir, "Main.kt").writeText("fun main() {}")
        artifactDir = File(System.getProperty("java.io.tmpdir"), "orch_art_" + System.nanoTime())
        artifactDir.mkdirs()
    }

    @After
    fun tearDown() {
        workspaceDir.deleteRecursively()
        artifactDir.deleteRecursively()
    }

    private fun cloudJob() = HybridExecutionJob(
        title = "Remote build",
        workloadType = WorkloadType.HEAVY_GRADLE_BUILD,
        routingDecision = RoutingDecision(ExecutionTarget.CLOUD, "test routes to cloud"),
        assignedWorker = worker,
        localCommand = "./gradlew assembleDebug"
    )

    /** Scripted happy-path transport: upload -> submit -> RUNNING -> SUCCEEDED -> artifact. */
    private fun happyTransport(): FakeCloudTransport {
        val statusCalls = AtomicInteger(0)
        return FakeCloudTransport { req ->
            val p = req.path
            when {
                p.startsWith("/api/v1/payloads/") && req.method == "GET" -> CloudTransportResponse(404, "{}")
                p.endsWith("/files") && req.method == "POST" -> CloudTransportResponse(201, "{}")
                p == "/api/v1/jobs" && req.method == "POST" -> CloudTransportResponse(201, """{"jobId":"remote-1"}""")
                p == "/api/v1/jobs/remote-1" && req.method == "GET" -> {
                    val n = statusCalls.incrementAndGet()
                    if (n == 1) CloudTransportResponse(200, """{"status":"RUNNING"}""")
                    else CloudTransportResponse(200, """{"status":"SUCCEEDED","exitCode":0}""")
                }
                p == "/api/v1/jobs/remote-1/artifacts" && req.method == "GET" ->
                    CloudTransportResponse(
                        200,
                        """{"artifacts":[{"id":"art-1","filename":"app.apk","sizeBytes":${payload.size},"sha256":"$payloadSha256"}]}"""
                    )
                p == "/api/v1/jobs/remote-1/artifacts/art-1" && req.method == "GET" ->
                    CloudTransportResponse(200, "ok", payload)
                else -> CloudTransportResponse(404, "{}")
            }
        }
    }

    private fun orchestratorWith(transport: FakeCloudTransport, costController: CloudCostController = CloudCostController()): CloudExecutionOrchestrator {
        val client = CloudExecutionClient(transport)
        return CloudExecutionOrchestrator(
            client = client,
            uploader = CloudPayloadUploader(client, CloudArtifactSyncEngine()),
            logStreamer = null, // plain status polling in tests (fast)
            downloader = CloudArtifactDownloader(client, CloudArtifactSyncEngine()),
            costController = costController,
            statusPollIntervalMs = 10L,
            statusPollTimeoutMs = 10_000L
        )
    }

    @Test
    fun fullPipelineCompletesHonestly() {
        val cost = CloudCostController()
        val orch = orchestratorWith(happyTransport(), cost)
        val result = orch.executeCloudJob(cloudJob(), worker, workspaceDir, artifactDir)
        assertEquals(HybridJobState.COMPLETED, result.state)
        assertEquals("remote-1", result.remoteJobId)
        assertEquals(0, result.exitCode)
        assertEquals(1, result.producedArtifacts.size)
        assertEquals("app.apk", result.producedArtifacts.first().filename)
        assertTrue(File(artifactDir, "app.apk").isFile)
        assertTrue(result.estimatedCostUsd >= 0.0)
        assertEquals(1, cost.jobCount())
        assertTrue(result.outputLogs.any { it.contains("Delta upload") })
        assertNotNull(result.finishedAt)
    }

    @Test
    fun costRefusalStopsBeforeDispatch() {
        val transport = happyTransport()
        val cost = CloudCostController(monthlyBudgetUsd = 1000.0, maxCostPerJobUsd = 0.000001)
        val orch = orchestratorWith(transport, cost)
        val result = orch.executeCloudJob(cloudJob(), worker, workspaceDir, artifactDir)
        assertEquals(HybridJobState.FAILED, result.state)
        assertTrue(result.errorMessage.orEmpty().contains("per-job cap"))
        // Nothing was ever submitted: no POST /api/v1/jobs in the transport log.
        assertTrue(transport.seen.none { it.path == "/api/v1/jobs" && it.method == "POST" })
    }

    @Test
    fun remoteFailurePropagatesAsFailed() {
        val statusCalls = AtomicInteger(0)
        val transport = FakeCloudTransport { req ->
            val p = req.path
            when {
                p.startsWith("/api/v1/payloads/") && req.method == "GET" -> CloudTransportResponse(404, "{}")
                p.endsWith("/files") && req.method == "POST" -> CloudTransportResponse(201, "{}")
                p == "/api/v1/jobs" && req.method == "POST" -> CloudTransportResponse(201, """{"jobId":"remote-9"}""")
                p == "/api/v1/jobs/remote-9" && req.method == "GET" -> {
                    val n = statusCalls.incrementAndGet()
                    if (n == 1) CloudTransportResponse(200, """{"status":"RUNNING"}""")
                    else CloudTransportResponse(200, """{"status":"FAILED","exitCode":1}""")
                }
                else -> CloudTransportResponse(404, "{}")
            }
        }
        val orch = orchestratorWith(transport)
        val result = orch.executeCloudJob(cloudJob(), worker, workspaceDir, artifactDir)
        assertEquals(HybridJobState.FAILED, result.state)
        assertTrue(result.errorMessage.orEmpty().contains("FAILED"))
        assertEquals(1, result.exitCode)
    }

    @Test
    fun cancelBeforeDispatchYieldsCancelled() {
        val orch = orchestratorWith(happyTransport())
        val job = cloudJob()
        orch.cancel(job.id)
        val result = orch.executeCloudJob(job, worker, workspaceDir, artifactDir)
        assertEquals(HybridJobState.CANCELLED, result.state)
    }

    @Test
    fun blankAuthKeyYieldsNotConfigured() {
        val orch = orchestratorWith(happyTransport())
        val result = orch.executeCloudJob(cloudJob(), worker.copy(authKey = ""), workspaceDir, artifactDir)
        assertEquals(HybridJobState.NOT_CONFIGURED, result.state)
    }

    @Test
    fun artifactChecksumMismatchFailsJob() {
        val statusCalls = AtomicInteger(0)
        val transport = FakeCloudTransport { req ->
            val p = req.path
            when {
                p.startsWith("/api/v1/payloads/") && req.method == "GET" -> CloudTransportResponse(404, "{}")
                p.endsWith("/files") && req.method == "POST" -> CloudTransportResponse(201, "{}")
                p == "/api/v1/jobs" && req.method == "POST" -> CloudTransportResponse(201, """{"jobId":"remote-2"}""")
                p == "/api/v1/jobs/remote-2" && req.method == "GET" -> {
                    val n = statusCalls.incrementAndGet()
                    if (n == 1) CloudTransportResponse(200, """{"status":"RUNNING"}""")
                    else CloudTransportResponse(200, """{"status":"SUCCEEDED","exitCode":0}""")
                }
                p == "/api/v1/jobs/remote-2/artifacts" && req.method == "GET" ->
                    CloudTransportResponse(
                        200,
                        """{"artifacts":[{"id":"art-1","filename":"app.apk","sizeBytes":${payload.size},"sha256":"${"ff".repeat(32)}"}]}"""
                    )
                p == "/api/v1/jobs/remote-2/artifacts/art-1" && req.method == "GET" ->
                    CloudTransportResponse(200, "ok", payload)
                else -> CloudTransportResponse(404, "{}")
            }
        }
        val orch = orchestratorWith(transport)
        val result = orch.executeCloudJob(cloudJob(), worker, workspaceDir, artifactDir)
        // Remote succeeded but the artifact could not be verified: never COMPLETED.
        assertEquals(HybridJobState.FAILED, result.state)
        assertTrue(result.errorMessage.orEmpty().contains("checksum mismatch"))
    }
}
