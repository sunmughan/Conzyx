package com.codeair.mhe.cloud

import com.codeair.mhe.cloud.model.CloudArtifact
import com.codeair.mhe.cloud.sync.CloudArtifactSyncEngine
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

    @Test
    fun testUnpackRemoteArtifactRejectsTraversalFilenames() {
        val destDir = File(tempDir, "downloads")
        destDir.mkdirs()
        val content = "binary".toByteArray()
        val checksum = syncEngine.calculateChecksum(content)
        val unsafe = listOf(
            "../evil.apk",
            "../../evil",
            "..\\evil.apk",
            "/abs/path.apk",
            "sub/dir.apk",
            "a\\b.apk",
            "..",
            ".",
            "",
            "   "
        )
        for (name in unsafe) {
            val artifact = CloudArtifact(id = "evil-$name", filename = name, checksumSha256 = checksum)
            assertFalse("expected rejection for '$name'", syncEngine.unpackRemoteArtifact(artifact, destDir, content))
        }
        assertTrue("no strays may be written outside the destination", File(tempDir, "evil.apk").exists().not())
        assertTrue("no strays may be written outside the destination", File(tempDir, "evil").exists().not())
        assertTrue("destination must stay empty", (destDir.listFiles() ?: emptyArray()).isEmpty())
        assertEquals(null, syncEngine.getCachedArtifact("evil-../evil.apk"))
    }

    @Test
    fun testUnpackRemoteArtifactLeavesNoTempFilesBehind() {
        val destDir = File(tempDir, "downloads")
        val good = "good bytes".toByteArray()
        val artifact = CloudArtifact(
            id = "good-1",
            filename = "app.apk",
            checksumSha256 = syncEngine.calculateChecksum(good)
        )
        assertTrue(syncEngine.unpackRemoteArtifact(artifact, destDir, good))
        val files = destDir.listFiles() ?: emptyArray()
        assertEquals(1, files.size)
        assertEquals("app.apk", files[0].name)
        assertTrue(files.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun testUnpackRemoteArtifactChecksumMismatchLeavesNothing() {
        val destDir = File(tempDir, "downloads")
        val artifact = CloudArtifact(
            id = "bad-1",
            filename = "app.apk",
            checksumSha256 = "0".repeat(64)
        )
        assertFalse(syncEngine.unpackRemoteArtifact(artifact, destDir, "tampered".toByteArray()))
        val files = destDir.listFiles() ?: emptyArray()
        assertTrue("neither final nor temp file may remain", files.isEmpty())
        assertEquals(null, syncEngine.getCachedArtifact("bad-1"))
    }

    @Test
    fun testUnpackRemoteArtifactOverwritesExistingFileSafely() {
        val destDir = File(tempDir, "downloads")
        destDir.mkdirs()
        File(destDir, "app.apk").writeText("stale content")
        val fresh = "fresh binary".toByteArray()
        val artifact = CloudArtifact(
            id = "overwrite-1",
            filename = "app.apk",
            checksumSha256 = syncEngine.calculateChecksum(fresh)
        )
        assertTrue(syncEngine.unpackRemoteArtifact(artifact, destDir, fresh))
        assertEquals("fresh binary", File(destDir, "app.apk").readText())
        val cached = syncEngine.getCachedArtifact("overwrite-1")
        assertNotNull(cached)
        assertEquals(File(destDir, "app.apk").absolutePath, cached!!.localCachePath)
    }
}
