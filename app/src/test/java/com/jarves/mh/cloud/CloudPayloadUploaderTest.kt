package com.jarves.mh.cloud

import com.jarves.mh.cloud.exec.CloudExecutionClient
import com.jarves.mh.cloud.exec.CloudPayloadUploader
import com.jarves.mh.cloud.exec.CloudTransportRequest
import com.jarves.mh.cloud.exec.CloudTransportResponse
import com.jarves.mh.cloud.model.CloudWorkerSpec
import com.jarves.mh.cloud.sync.CloudArtifactSyncEngine
import com.jarves.mh.core.result.OperationStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class CloudPayloadUploaderTest {

    private lateinit var tempDir: File
    private val worker = CloudWorkerSpec(
        name = "Test Worker",
        endpointUrl = "https://worker.test",
        region = "test-1",
        authKey = "secret-key"
    )

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "delta_test_" + System.nanoTime())
        tempDir.mkdirs()
        File(tempDir, "a.kt").writeText("class A")
        File(tempDir, "b.kt").writeText("class B")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun manifestResponse(known: Map<String, String>, code: Int = 200): CloudTransportResponse {
        val files = known.entries.joinToString(",") { (p, h) -> """{"path":"$p","sha256":"$h"}""" }
        return CloudTransportResponse(code, """{"files":[$files]}""")
    }

    @Test
    fun unchangedFilesAreSkippedChangedFilesUploaded() {
        val known = mapOf("a.kt" to sha256Of(File(tempDir, "a.kt")))
        val uploaded = mutableListOf<String>()
        val transport = FakeCloudTransport { req: CloudTransportRequest ->
            when {
                req.path.startsWith("/api/v1/payloads/") && req.method == "GET" -> manifestResponse(known)
                req.path.endsWith("/files") && req.method == "POST" -> {
                    uploaded.add(req.path)
                    CloudTransportResponse(201, "{}")
                }
                else -> CloudTransportResponse(404, "{}")
            }
        }
        val uploader = CloudPayloadUploader(CloudExecutionClient(transport), CloudArtifactSyncEngine())
        val result = uploader.uploadWorkspaceDelta(worker, tempDir)
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(1, result.filesUploaded)   // only b.kt
        assertEquals(1, result.filesSkipped)    // a.kt already on worker
        assertTrue(result.bytesUploaded > 0)
        assertEquals(1, uploaded.size)
    }

    @Test
    fun unknownSnapshotUploadsEverything() {
        val transport = FakeCloudTransport { req: CloudTransportRequest ->
            when {
                req.method == "GET" -> CloudTransportResponse(404, "{}")
                req.method == "POST" -> CloudTransportResponse(201, "{}")
                else -> CloudTransportResponse(404, "{}")
            }
        }
        val uploader = CloudPayloadUploader(CloudExecutionClient(transport), CloudArtifactSyncEngine())
        val result = uploader.uploadWorkspaceDelta(worker, tempDir)
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(2, result.filesUploaded)
        assertEquals(0, result.filesSkipped)
    }

    @Test
    fun blankAuthKeyIsNotConfigured() {
        val uploader = CloudPayloadUploader(
            CloudExecutionClient(FakeCloudTransport()),
            CloudArtifactSyncEngine()
        )
        val result = uploader.uploadWorkspaceDelta(worker.copy(authKey = ""), tempDir)
        assertEquals(OperationStatus.NOT_CONFIGURED, result.result.status)
        assertEquals(0, result.filesUploaded)
    }

    @Test
    fun uploadFailureAbortsHonestly() {
        val transport = FakeCloudTransport { req: CloudTransportRequest ->
            when {
                req.method == "GET" -> CloudTransportResponse(404, "{}")
                else -> CloudTransportResponse(500, "boom")
            }
        }
        val uploader = CloudPayloadUploader(CloudExecutionClient(transport), CloudArtifactSyncEngine())
        val result = uploader.uploadWorkspaceDelta(worker, tempDir)
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error.orEmpty().contains("Delta upload failed"))
    }
}
