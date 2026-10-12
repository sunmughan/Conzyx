package com.codeair.mhe.cloud

import com.codeair.mhe.cloud.exec.CloudArtifactDownloader
import com.codeair.mhe.cloud.exec.CloudExecutionClient
import com.codeair.mhe.cloud.exec.CloudTransportResponse
import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.sync.CloudArtifactSyncEngine
import com.codeair.mhe.core.result.OperationStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class CloudArtifactDownloaderTest {

    private lateinit var destDir: File
    private val worker = CloudWorkerSpec(
        name = "Test Worker",
        endpointUrl = "https://worker.test",
        region = "test-1",
        authKey = "secret-key"
    )
    private val payload = "fake-apk-bytes".toByteArray()
    private val payloadSha256: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    }

    @Before
    fun setUp() {
        destDir = File(System.getProperty("java.io.tmpdir"), "artifact_test_" + System.nanoTime())
        destDir.mkdirs()
    }

    @After
    fun tearDown() {
        destDir.deleteRecursively()
    }

    private fun transportWith(descriptorSha: String) = FakeCloudTransport { req ->
        when {
            req.path.endsWith("/artifacts") && req.method == "GET" ->
                CloudTransportResponse(
                    200,
                    """{"artifacts":[{"id":"art-1","filename":"app.apk","sizeBytes":${payload.size},"sha256":"$descriptorSha"}]}"""
                )
            req.path.endsWith("/artifacts/art-1") && req.method == "GET" ->
                CloudTransportResponse(200, "ok", payload)
            else -> CloudTransportResponse(404, "{}")
        }
    }

    @Test
    fun matchingChecksumWritesArtifact() {
        val downloader = CloudArtifactDownloader(
            CloudExecutionClient(transportWith(payloadSha256)),
            CloudArtifactSyncEngine()
        )
        val result = downloader.fetchArtifacts(worker, "remote-1", destDir)
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(1, result.verified)
        val written = File(destDir, "app.apk")
        assertTrue(written.isFile)
        assertEquals("fake-apk-bytes", written.readText())
    }

    @Test
    fun checksumMismatchFailsAndWritesNothing() {
        val downloader = CloudArtifactDownloader(
            CloudExecutionClient(transportWith("00".repeat(32))),
            CloudArtifactSyncEngine()
        )
        val result = downloader.fetchArtifacts(worker, "remote-1", destDir)
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error.orEmpty().contains("checksum mismatch"))
        assertFalse(File(destDir, "app.apk").exists())
    }

    @Test
    fun blankAuthKeyIsNotConfigured() {
        val downloader = CloudArtifactDownloader(
            CloudExecutionClient(transportWith(payloadSha256)),
            CloudArtifactSyncEngine()
        )
        val result = downloader.fetchArtifacts(worker.copy(authKey = ""), "remote-1", destDir)
        assertEquals(OperationStatus.NOT_CONFIGURED, result.result.status)
        assertEquals(0, result.downloaded)
    }

    @Test
    fun listingFailureFailsHonestly() {
        val transport = FakeCloudTransport { CloudTransportResponse(500, "down") }
        val downloader = CloudArtifactDownloader(
            CloudExecutionClient(transport),
            CloudArtifactSyncEngine()
        )
        val result = downloader.fetchArtifacts(worker, "remote-1", destDir)
        assertEquals(OperationStatus.FAILED, result.result.status)
    }
}
