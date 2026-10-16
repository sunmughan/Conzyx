package com.codeair.mhe.cloud.sync

import com.codeair.mhe.cloud.model.CloudArtifact
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class SyncPayload(
    val manifestHash: String,
    val fileCount: Int,
    val totalBytes: Long,
    val relativePaths: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Bi-Directional Workspace State & Artifact Sync Engine (Phase 12).
 * Bundles local workspace diffs for remote execution and restores build outputs (APKs, test reports)
 * with SHA-256 checksum verification.
 */
class CloudArtifactSyncEngine {

    private val payloadCache = ConcurrentHashMap<String, SyncPayload>()
    private val artifactCache = ConcurrentHashMap<String, CloudArtifact>()

    /**
     * Builds a differential deployment payload from a project directory.
     */
    fun createWorkspacePayload(
        workspaceDir: File,
        excludedDirs: Set<String> = setOf(".git", ".gradle", "build", "node_modules", ".idea", "bin", "obj")
    ): SyncPayload {
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) {
            val emptyHash = calculateChecksum("empty".toByteArray())
            return SyncPayload(manifestHash = emptyHash, fileCount = 0, totalBytes = 0L, relativePaths = emptyList())
        }

        val relativePaths = mutableListOf<String>()
        var totalBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")

        workspaceDir.walkTopDown()
            .onEnter { dir -> !excludedDirs.contains(dir.name) }
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(workspaceDir).path
                relativePaths.add(rel)
                totalBytes += file.length()
                digest.update(rel.toByteArray())
                digest.update(file.length().toString().toByteArray())
            }

        val manifestHash = digest.digest().joinToString("") { "%02x".format(it) }
        val payload = SyncPayload(
            manifestHash = manifestHash,
            fileCount = relativePaths.size,
            totalBytes = totalBytes,
            relativePaths = relativePaths
        )

        payloadCache[manifestHash] = payload
        return payload
    }

    /**
     * Simulates or executes writing of a remote build output artifact into the local project workspace.
     */
    fun unpackRemoteArtifact(
        artifact: CloudArtifact,
        destinationDir: File,
        artifactBytes: ByteArray? = null
    ): Boolean {
        val targetFile = ArtifactFilenameValidator.resolveTarget(destinationDir, artifact.filename)
            ?: return false

        runCatching {
            if (!destinationDir.exists()) {
                destinationDir.mkdirs()
            }
            if (artifactBytes != null) {
                val tmp = ArtifactFilenameValidator.newTempFile(destinationDir)
                try {
                    tmp.writeBytes(artifactBytes)
                    if (artifact.checksumSha256.isNotBlank()) {
                        val computed = calculateChecksum(tmp.readBytes())
                        if (!computed.equals(artifact.checksumSha256, ignoreCase = true)) {
                            return false
                        }
                    }
                    if (!ArtifactFilenameValidator.moveVerifiedFile(tmp, targetFile)) {
                        return false
                    }
                } finally {
                    runCatching { tmp.delete() }
                }
            } else {
                // If mocked or remote URL, ensure stub or metadata written
                if (!targetFile.exists()) {
                    targetFile.writeText("// Downloaded from ${artifact.remoteUrl}\n// SHA-256: ${artifact.checksumSha256}")
                }
            }
            artifactCache[artifact.id] = artifact.copy(localCachePath = targetFile.absolutePath)
        }.onFailure { return false }

        return true
    }

    fun getCachedArtifact(id: String): CloudArtifact? = artifactCache[id]

    /**
     * Records an artifact whose file was written directly (the streamed
     * download path writes the file itself instead of going through
     * [unpackRemoteArtifact]).
     */
    fun recordCachedArtifact(artifact: CloudArtifact, localPath: String) {
        artifactCache[artifact.id] = artifact.copy(localCachePath = localPath)
    }

    fun isPayloadCached(manifestHash: String): Boolean = payloadCache.containsKey(manifestHash)

    fun calculateChecksum(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun clearCache() {
        payloadCache.clear()
        artifactCache.clear()
    }
}
