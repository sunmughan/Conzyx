package com.codeair.mhe.cloud

import com.codeair.mhe.cloud.exec.ArtifactDownloadResult
import com.codeair.mhe.cloud.exec.CloudExecutionClient
import com.codeair.mhe.cloud.exec.CloudHttpTransport
import com.codeair.mhe.cloud.exec.CloudJobRequest
import com.codeair.mhe.cloud.exec.CloudTransportRequest
import com.codeair.mhe.cloud.exec.CloudTransportResponse
import com.codeair.mhe.cloud.exec.RemoteJobStatus
import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.core.result.OperationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake transport: routes (method + path) to scripted responses; records the last request. */
class FakeCloudTransport(
    private val handler: (CloudTransportRequest) -> CloudTransportResponse = { CloudTransportResponse(404, "{}") }
) : CloudHttpTransport {
    val seen = mutableListOf<CloudTransportRequest>()
    override fun send(baseUrl: String, request: CloudTransportRequest): CloudTransportResponse {
        seen.add(request)
        return handler(request)
    }
}

private fun authedWorker() = CloudWorkerSpec(
    name = "Test Worker",
    endpointUrl = "https://worker.test",
    region = "test-1",
    authKey = "secret-key"
)

class CloudExecutionClientTest {

    private fun submitRequest() = CloudJobRequest(
        title = "Build app",
        workloadType = "HEAVY_GRADLE_BUILD",
        command = "./gradlew assembleDebug",
        manifestHash = "abc123"
    )

    @Test
    fun submitJobSuccessReturnsRemoteId() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(201, """{"jobId":"remote-1"}""")
        })
        val res = client.submitJob(authedWorker(), submitRequest())
        assertEquals(OperationStatus.SUCCESS, res.status)
        assertEquals("remote-1", res.artifacts.firstOrNull())
        assertTrue(res.verificationEvidence.orEmpty().contains("remote-1"))
    }

    @Test
    fun submitJobRejectsBlankAuthKeyWithoutSending() {
        val transport = FakeCloudTransport()
        val client = CloudExecutionClient(transport)
        val res = client.submitJob(authedWorker().copy(authKey = ""), submitRequest())
        assertEquals(OperationStatus.NOT_CONFIGURED, res.status)
        assertTrue(transport.seen.isEmpty())
    }

    @Test
    fun submitJobHttpErrorIsHonestFailure() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(401, """{"error":"bad token"}""")
        })
        val res = client.submitJob(authedWorker(), submitRequest())
        assertEquals(OperationStatus.FAILED, res.status)
        assertEquals(401, res.exitCode)
        assertTrue(res.error.orEmpty().contains("401"))
    }

    @Test
    fun submitJobTransportErrorIsHonestFailure() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(-1, "transport error: timeout")
        })
        val res = client.submitJob(authedWorker(), submitRequest())
        assertEquals(OperationStatus.FAILED, res.status)
        assertTrue(res.error.orEmpty().contains("transport error"))
    }

    @Test
    fun submitJobMissingJobIdIsFailure() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(201, """{"nope":true}""")
        })
        val res = client.submitJob(authedWorker(), submitRequest())
        assertEquals(OperationStatus.FAILED, res.status)
        assertTrue(res.error.orEmpty().contains("no jobId"))
    }

    @Test
    fun pollStatusParsesTerminalState() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(200, """{"status":"SUCCEEDED","exitCode":0}""")
        })
        val res = client.pollStatus(authedWorker(), "remote-1")
        assertEquals(OperationStatus.SUCCESS, res.status)
        assertEquals(RemoteJobStatus.SUCCEEDED.name, res.artifacts.firstOrNull())
        assertEquals(0, res.exitCode)
    }

    @Test
    fun pollStatusUnknownMapsToUnknown() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(200, """{"status":"WEIRD"}""")
        })
        val res = client.pollStatus(authedWorker(), "remote-1")
        assertEquals(OperationStatus.SUCCESS, res.status)
        assertEquals(RemoteJobStatus.UNKNOWN.name, res.artifacts.firstOrNull())
    }

    @Test
    fun cancelJobConfirmed() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(200, """{"cancelled":true}""")
        })
        val res = client.cancelJob(authedWorker(), "remote-1")
        assertEquals(OperationStatus.SUCCESS, res.status)
    }

    @Test
    fun cancelJobUnconfirmedIsFailure() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(200, """{"cancelled":false}""")
        })
        val res = client.cancelJob(authedWorker(), "remote-1")
        assertEquals(OperationStatus.FAILED, res.status)
    }

    @Test
    fun fetchLogsReturnsFramesAndNextSeq() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(200, """{"logs":[{"seq":5,"line":"hello"},{"seq":6,"line":"world"}],"nextSeq":7}""")
        })
        val res = client.fetchLogs(authedWorker(), "remote-1", 5L)
        assertEquals(OperationStatus.SUCCESS, res.status)
        assertEquals(listOf("hello", "world"), res.logs)
        assertEquals("nextSeq=7", res.artifacts.firstOrNull())
    }

    @Test
    fun fetchRemoteManifest404MeansFullUpload() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(404, """{"error":"unknown"}""")
        })
        val res = client.fetchRemoteManifest(authedWorker(), "abc123")
        assertEquals(OperationStatus.SUCCESS, res.result.status)
        assertTrue(res.knownFiles.isEmpty())
    }

    @Test
    fun fetchRemoteManifest200ReturnsKnownFiles() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(200, """{"files":[{"path":"a.kt","sha256":"deadbeef"}]}""")
        })
        val res = client.fetchRemoteManifest(authedWorker(), "abc123")
        assertEquals(OperationStatus.SUCCESS, res.result.status)
        assertEquals(mapOf("a.kt" to "deadbeef"), res.knownFiles)
    }

    @Test
    fun downloadArtifactBytesReturnsBytesOnSuccess() {
        val bytes = "binary-payload".toByteArray()
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(200, "ok", bytes)
        })
        val res: ArtifactDownloadResult = client.downloadArtifactBytes(authedWorker(), "remote-1", "art-1")
        assertEquals(OperationStatus.SUCCESS, res.result.status)
        assertNotNull(res.bytes)
        assertEquals("binary-payload", String(res.bytes!!))
    }

    @Test
    fun downloadArtifactBytesNullBodyIsFailure() {
        val client = CloudExecutionClient(FakeCloudTransport {
            CloudTransportResponse(200, "ok", null)
        })
        val res = client.downloadArtifactBytes(authedWorker(), "remote-1", "art-1")
        assertEquals(OperationStatus.FAILED, res.result.status)
        assertNull(res.bytes)
    }

    @Test
    fun authHeaderIsBearer() {
        val transport = FakeCloudTransport {
            CloudTransportResponse(201, """{"jobId":"r"}""")
        }
        CloudExecutionClient(transport).submitJob(authedWorker(), submitRequest())
        assertEquals("Bearer secret-key", transport.seen.first().headers["Authorization"])
    }
}
