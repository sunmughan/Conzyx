package com.codeair.mhe.cloud

import com.codeair.mhe.cloud.exec.ArtifactFileDownloadResult
import com.codeair.mhe.cloud.exec.CloudExecutionClient
import com.codeair.mhe.cloud.exec.CloudHttpTransport
import com.codeair.mhe.cloud.exec.CloudJobRequest
import com.codeair.mhe.cloud.exec.CloudTransportRequest
import com.codeair.mhe.cloud.exec.CloudTransportResponse
import com.codeair.mhe.cloud.exec.RemoteJobStatus
import com.codeair.mhe.cloud.exec.StreamedDownload
import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.core.result.OperationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Fake transport: routes (method + path) to scripted responses; records the last request. */
class FakeCloudTransport(
    private val downloadHandler: (CloudTransportRequest, File) -> StreamedDownload =
        { _, dest -> dest.writeBytes(ByteArray(0)); StreamedDownload(200, null, 0) },
    private val handler: (CloudTransportRequest) -> CloudTransportResponse = { CloudTransportResponse(404, "{}") },
) : CloudHttpTransport {
    val seen = mutableListOf<CloudTransportRequest>()
    override fun send(baseUrl: String, request: CloudTransportRequest): CloudTransportResponse {
        seen.add(request)
        return handler(request)
    }
    override fun downloadToFile(
        baseUrl: String,
        request: CloudTransportRequest,
        dest: File,
        maxBytes: Long
    ): StreamedDownload {
        seen.add(request)
        return downloadHandler(request, dest)
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

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tempDest(): File =
        File.createTempFile("artifact_dl_test", ".bin").also { it.deleteOnExit() }

    @Test
    fun downloadArtifactToFileStreamsToDiskAndVerifiesChecksum() {
        val bytes = "binary-payload".toByteArray()
        val sha = sha256Hex(bytes)
        val client = CloudExecutionClient(
            FakeCloudTransport(
                downloadHandler = { _, dest ->
                    dest.writeBytes(bytes)
                    StreamedDownload(200, null, bytes.size.toLong())
                }
            )
        )
        val dest = tempDest()
        val res: ArtifactFileDownloadResult =
            client.downloadArtifactToFile(authedWorker(), "remote-1", "art-1", dest, sha)
        assertEquals(OperationStatus.SUCCESS, res.result.status)
        assertEquals(bytes.size.toLong(), res.bytesWritten)
        assertEquals(sha, res.sha256)
        assertTrue("success must mean the digest was verified", res.digestVerified)
        assertTrue(
            res.result.verificationEvidence.orEmpty().contains("verified against the expected digest")
        )
        assertEquals("binary-payload", dest.readText())
    }

    @Test
    fun downloadArtifactToFileDeletesOnChecksumMismatch() {
        val bytes = "binary-payload".toByteArray()
        val client = CloudExecutionClient(
            FakeCloudTransport(
                downloadHandler = { _, dest ->
                    dest.writeBytes(bytes)
                    StreamedDownload(200, null, bytes.size.toLong())
                }
            )
        )
        val dest = tempDest()
        val res = client.downloadArtifactToFile(authedWorker(), "remote-1", "art-1", dest, "00".repeat(32))
        assertEquals(OperationStatus.FAILED, res.result.status)
        assertTrue(res.result.error.orEmpty().contains("checksum mismatch"))
        assertFalse("mismatched file must be deleted", dest.exists())
    }

    @Test
    fun downloadArtifactToFileHttpErrorIsHonestFailure() {
        val client = CloudExecutionClient(
            FakeCloudTransport(
                downloadHandler = { _, _ -> StreamedDownload(500, "HTTP 500: boom", 0) }
            )
        )
        val dest = tempDest()
        val res = client.downloadArtifactToFile(authedWorker(), "remote-1", "art-1", dest, "00".repeat(32))
        assertEquals(OperationStatus.FAILED, res.result.status)
        assertTrue(res.result.error.orEmpty().contains("500"))
        assertFalse("failed download must leave no file", dest.exists())
    }

    @Test
    fun authHeaderIsBearer() {
        val transport = FakeCloudTransport {
            CloudTransportResponse(201, """{"jobId":"r"}""")
        }
        CloudExecutionClient(transport).submitJob(authedWorker(), submitRequest())
        assertEquals("Bearer secret-key", transport.seen.first().headers["Authorization"])
    }

    @Test
    fun downloadArtifactRejectsMissingChecksumWithoutDownloading() {
        val bytes = "binary-payload".toByteArray()
        val transport = FakeCloudTransport(
            downloadHandler = { _, dest ->
                dest.writeBytes(bytes)
                StreamedDownload(200, null, bytes.size.toLong())
            }
        )
        val client = CloudExecutionClient(transport)
        val dest = tempDest()
        val res = client.downloadArtifactToFile(authedWorker(), "remote-1", "art-1", dest, "")
        assertEquals(OperationStatus.FAILED, res.result.status)
        assertTrue(res.result.error.orEmpty().contains("no expected SHA-256"))
        assertFalse(res.digestVerified)
        assertTrue("no download must be attempted without a digest", transport.seen.isEmpty())
    }

    @Test
    fun downloadArtifactRejectsMalformedChecksumWithoutDownloading() {
        val bytes = "binary-payload".toByteArray()
        val transport = FakeCloudTransport(
            downloadHandler = { _, dest ->
                dest.writeBytes(bytes)
                StreamedDownload(200, null, bytes.size.toLong())
            }
        )
        val client = CloudExecutionClient(transport)
        for (bad in listOf("xyz", "00".repeat(32) + "zz", "00".repeat(31), "00".repeat(33))) {
            val dest = tempDest()
            val res = client.downloadArtifactToFile(authedWorker(), "remote-1", "art-1", dest, bad)
            assertEquals(OperationStatus.FAILED, res.result.status)
            assertTrue(res.result.error.orEmpty().contains("malformed"))
            assertFalse(res.digestVerified)
        }
        assertTrue("no download must be attempted with a malformed digest", transport.seen.isEmpty())
    }

    @Test
    fun downloadArtifactEnforcesSizeCapAndDeletesFile() {
        val bytes = "binary-payload".toByteArray()
        val sha = sha256Hex(bytes)
        val client = CloudExecutionClient(
            FakeCloudTransport(
                downloadHandler = { _, dest ->
                    dest.writeBytes(bytes)
                    StreamedDownload(200, null, bytes.size.toLong())
                }
            )
        )
        val dest = tempDest()
        val res = client.downloadArtifactToFile(
            authedWorker(), "remote-1", "art-1", dest, sha,
            expectedSizeBytes = -1L, maxSizeBytes = 4L
        )
        assertEquals(OperationStatus.FAILED, res.result.status)
        assertTrue(res.result.error.orEmpty().contains("size cap"))
        assertFalse("oversized download must be deleted", dest.exists())
        assertFalse(res.digestVerified)
    }

    @Test
    fun downloadArtifactEnforcesDeclaredSizeAndDeletesFile() {
        val bytes = "binary-payload".toByteArray()
        val sha = sha256Hex(bytes)
        val client = CloudExecutionClient(
            FakeCloudTransport(
                downloadHandler = { _, dest ->
                    dest.writeBytes(bytes)
                    StreamedDownload(200, null, bytes.size.toLong())
                }
            )
        )
        val dest = tempDest()
        val res = client.downloadArtifactToFile(
            authedWorker(), "remote-1", "art-1", dest, sha,
            expectedSizeBytes = bytes.size.toLong() + 100L
        )
        assertEquals(OperationStatus.FAILED, res.result.status)
        assertTrue(res.result.error.orEmpty().contains("size mismatch"))
        assertFalse("size-mismatched download must be deleted", dest.exists())
        assertFalse(res.digestVerified)
    }

    @Test
    fun downloadArtifactAcceptsMatchingDeclaredSize() {
        val bytes = "binary-payload".toByteArray()
        val sha = sha256Hex(bytes)
        val client = CloudExecutionClient(
            FakeCloudTransport(
                downloadHandler = { _, dest ->
                    dest.writeBytes(bytes)
                    StreamedDownload(200, null, bytes.size.toLong())
                }
            )
        )
        val dest = tempDest()
        val res = client.downloadArtifactToFile(
            authedWorker(), "remote-1", "art-1", dest, sha,
            expectedSizeBytes = bytes.size.toLong()
        )
        assertEquals(OperationStatus.SUCCESS, res.result.status)
        assertTrue(res.digestVerified)
    }
}
