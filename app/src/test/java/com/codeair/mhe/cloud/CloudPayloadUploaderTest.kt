package com.codeair.mhe.cloud

import com.codeair.mhe.cloud.exec.CloudExecutionClient
import com.codeair.mhe.cloud.exec.CloudPayloadUploader
import com.codeair.mhe.cloud.exec.CloudTransportRequest
import com.codeair.mhe.cloud.exec.CloudTransportResponse
import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.sync.CloudArtifactSyncEngine
import com.codeair.mhe.core.result.OperationStatus
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

    @Test
    fun `oversized file aborts with a clear limit error instead of OOM`() {
        val bigDir = File(tempDir, "bigonly").apply { mkdirs() }
        // 33 MB file: over the 32 MB single-file cap.
        val big = File(bigDir, "big.bin")
        val chunk = ByteArray(1024 * 1024)
        big.outputStream().use { out -> repeat(33) { out.write(chunk) } }
        val transport = FakeCloudTransport { req: CloudTransportRequest ->
            when {
                req.method == "GET" -> CloudTransportResponse(404, "{}")
                else -> CloudTransportResponse(201, "{}")
            }
        }
        val uploader = CloudPayloadUploader(CloudExecutionClient(transport), CloudArtifactSyncEngine())
        val result = uploader.uploadWorkspaceDelta(worker, bigDir)
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error.orEmpty().contains("big.bin"))
        assertTrue(result.result.error.orEmpty().contains("32 MB"))
        assertEquals(0, result.filesUploaded)
    }

    @Test
    fun secretFilesAreNeverUploaded() {
        val secrets = mapOf(
            ".env" to "API_KEY=supersecret-value",
            ".env.production" to "DB_PASSWORD=hunter2",
            "id_rsa" to "PRIVATE RSA KEY MATERIAL",
            "release.jks" to "keystore-bytes",
            "cert.pem" to "PRIVATE KEY PEM",
            "server.key" to "PRIVATE KEY",
            "app.keystore" to "keystore",
            "app.p12" to "pkcs12",
            "tls.crt" to "CERT",
            ".npmrc" to "_authToken=npm-secret",
            "known_hosts" to "ssh-host-keys",
            "authorized_keys" to "ssh-pub",
            "prod-service-account.json" to "service-account-secret",
            "oauth_credentials.json" to "oauth-secret"
        )
        secrets.forEach { (name, content) -> File(tempDir, name).writeText(content) }
        val bodies = mutableListOf<String>()
        val transport = FakeCloudTransport { req: CloudTransportRequest ->
            when {
                req.method == "GET" -> CloudTransportResponse(404, "{}")
                req.method == "POST" -> {
                    bodies.add(req.body.orEmpty())
                    CloudTransportResponse(201, "{}")
                }
                else -> CloudTransportResponse(404, "{}")
            }
        }
        val uploader = CloudPayloadUploader(CloudExecutionClient(transport), CloudArtifactSyncEngine())
        val result = uploader.uploadWorkspaceDelta(worker, tempDir)
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error.orEmpty().contains("secret file"))
        for ((_, content) in secrets) {
            assertTrue(
                "secret content must never leave the device",
                bodies.none { it.contains(content) }
            )
        }
    }

    @Test
    fun googleServicesJsonIsAllowedAsAppAsset() {
        File(tempDir, "google-services.json").writeText("{\"project\": \"demo\"}")
        val uploadedPaths = mutableListOf<String>()
        val transport = FakeCloudTransport { req: CloudTransportRequest ->
            when {
                req.method == "GET" -> CloudTransportResponse(404, "{}")
                req.method == "POST" -> {
                    uploadedPaths.add(req.body.orEmpty())
                    CloudTransportResponse(201, "{}")
                }
                else -> CloudTransportResponse(404, "{}")
            }
        }
        val uploader = CloudPayloadUploader(CloudExecutionClient(transport), CloudArtifactSyncEngine())
        val result = uploader.uploadWorkspaceDelta(worker, tempDir)
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertTrue(uploadedPaths.any { it.contains("google-services.json") })
    }

    @Test
    fun symlinksAreRejected() {
        val target = File(tempDir, "real.kt")
        target.writeText("class Real")
        java.nio.file.Files.createSymbolicLink(
            File(tempDir, "link.kt").toPath(),
            target.toPath()
        )
        val transport = FakeCloudTransport { req: CloudTransportRequest ->
            when {
                req.method == "GET" -> CloudTransportResponse(404, "{}")
                else -> CloudTransportResponse(201, "{}")
            }
        }
        val uploader = CloudPayloadUploader(CloudExecutionClient(transport), CloudArtifactSyncEngine())
        val result = uploader.uploadWorkspaceDelta(worker, tempDir)
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error.orEmpty().contains("symlink"))
    }

    @Test
    fun sensitiveFileRequiresApprovalWithoutGateway() {
        File(tempDir, "db-password.txt").writeText("s3cr3t")
        val transport = FakeCloudTransport { req: CloudTransportRequest ->
            when {
                req.method == "GET" -> CloudTransportResponse(404, "{}")
                else -> CloudTransportResponse(201, "{}")
            }
        }
        val uploader = CloudPayloadUploader(CloudExecutionClient(transport), CloudArtifactSyncEngine())
        val result = uploader.uploadWorkspaceDelta(worker, tempDir)
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error.orEmpty().contains("explicit user approval"))
    }

    @Test
    fun sensitiveFileUploadsAfterExplicitGrant() {
        File(tempDir, "db-password.txt").writeText("s3cr3t")
        val transport = FakeCloudTransport { req: CloudTransportRequest ->
            when {
                req.method == "GET" -> CloudTransportResponse(404, "{}")
                else -> CloudTransportResponse(201, "{}")
            }
        }
        val gateway = com.codeair.mhe.security.SafetyGateway(
            policyEngine = com.codeair.mhe.security.PermissionPolicyEngine()
        )
        gateway.recordGrant(
            com.codeair.mhe.security.SafetyOperation.RemoteExec(
                target = worker.name,
                command = "upload db-password.txt"
            ),
            com.codeair.mhe.security.PermissionLevel.SESSION,
            sessionId = "test-session"
        )
        val uploader = CloudPayloadUploader(
            CloudExecutionClient(transport),
            CloudArtifactSyncEngine(),
            safetyGateway = gateway,
            sessionId = "test-session"
        )
        val result = uploader.uploadWorkspaceDelta(worker, tempDir)
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertTrue(result.filesUploaded >= 1)
    }

    @Test
    fun sensitiveFileWithoutGrantAbortsEvenWithGateway() {
        File(tempDir, "api-token.txt").writeText("tok123")
        val transport = FakeCloudTransport { req: CloudTransportRequest ->
            when {
                req.method == "GET" -> CloudTransportResponse(404, "{}")
                else -> CloudTransportResponse(201, "{}")
            }
        }
        val gateway = com.codeair.mhe.security.SafetyGateway(
            policyEngine = com.codeair.mhe.security.PermissionPolicyEngine()
        )
        val uploader = CloudPayloadUploader(
            CloudExecutionClient(transport),
            CloudArtifactSyncEngine(),
            safetyGateway = gateway,
            sessionId = "test-session"
        )
        val result = uploader.uploadWorkspaceDelta(worker, tempDir)
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error.orEmpty().contains("explicit user approval"))
        assertTrue(result.result.error.orEmpty().contains("Allow remote execution?"))
    }
}
