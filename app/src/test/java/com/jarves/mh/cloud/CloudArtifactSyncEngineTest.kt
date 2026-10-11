package com.jarves.mh.cloud

import com.jarves.mh.cloud.model.CloudArtifact
import com.jarves.mh.cloud.sync.CloudArtifactSyncEngine
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudArtifactSyncEngineTest {

    private lateinit var syncEngine: CloudArtifactSyncEngine
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        syncEngine = CloudArtifactSyncEngine()
        tempDir = File(System.getProperty("java.io.tmpdir"), "sync_test_" + System.currentTimeMillis())
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testCreateWorkspacePayloadWithSampleFiles() {
        val srcDir = File(tempDir, "src")
        srcDir.mkdirs()
        File(srcDir, "MainActivity.kt").writeText("package com.example\nclass MainActivity")
        File(srcDir, "Utils.kt").writeText("package com.example\nobject Utils")

        val buildDir = File(tempDir, "build")
        buildDir.mkdirs()
        File(buildDir, "output.class").writeText("binary data")

        val payload = syncEngine.createWorkspacePayload(tempDir)
        assertEquals(2, payload.fileCount) // "build" directory was excluded by default
        assertTrue(payload.totalBytes > 0)
        assertTrue(payload.manifestHash.isNotBlank())
        assertTrue(payload.relativePaths.any { it.contains("MainActivity.kt") })
        assertFalse(payload.relativePaths.any { it.contains("output.class") })
    }

    @Test
    fun testCreateWorkspacePayloadForNonExistentDirectory() {
        val ghostDir = File(tempDir, "does_not_exist")
        val payload = syncEngine.createWorkspacePayload(ghostDir)

        assertEquals(0, payload.fileCount)
        assertEquals(0L, payload.totalBytes)
        assertTrue(payload.relativePaths.isEmpty())
    }

    @Test
    fun testCalculateChecksumSha256() {
        val content = "hello world".toByteArray()
        val checksum = syncEngine.calculateChecksum(content)

        // Known SHA-256 for "hello world"
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", checksum.lowercase())
    }

    @Test
    fun testUnpackRemoteArtifactSuccess() {
        val content = "simulated apk binary content".toByteArray()
        val checksum = syncEngine.calculateChecksum(content)

        val artifact = CloudArtifact(
            id = "artifact-apk-1",
            filename = "app-release.apk",
            remoteUrl = "https://cloud.com/app-release.apk",
            sizeBytes = content.size.toLong(),
            checksumSha256 = checksum,
            artifactType = "APK_BINARY"
        )

        val destDir = File(tempDir, "downloads")
        val success = syncEngine.unpackRemoteArtifact(artifact, destDir, content)

        assertTrue(success)
        val targetFile = File(destDir, "app-release.apk")
        assertTrue(targetFile.exists())
        assertEquals(content.size, targetFile.readBytes().size)

        val cached = syncEngine.getCachedArtifact("artifact-apk-1")
        assertNotNull(cached)
        assertEquals(targetFile.absolutePath, cached!!.localCachePath)
    }

    @Test
    fun testUnpackRemoteArtifactChecksumMismatchFails() {
        val actualContent = "corrupted or tampered content".toByteArray()
        val wrongChecksum = "0000000000000000000000000000000000000000000000000000000000000000"

        val artifact = CloudArtifact(
            id = "artifact-tampered",
            filename = "bad.apk",
            checksumSha256 = wrongChecksum
        )

        val destDir = File(tempDir, "downloads")
        val success = syncEngine.unpackRemoteArtifact(artifact, destDir, actualContent)

        assertFalse(success)
        val targetFile = File(destDir, "bad.apk")
        assertFalse(targetFile.exists())
    }

    @Test
    fun testClearCacheEmptiesStorage() {
        val artifact = CloudArtifact(id = "cached-1", filename = "file.txt")
        syncEngine.unpackRemoteArtifact(artifact, tempDir, "text".toByteArray())

        assertNotNull(syncEngine.getCachedArtifact("cached-1"))
        syncEngine.clearCache()
        assertEquals(null, syncEngine.getCachedArtifact("cached-1"))
    }
}
