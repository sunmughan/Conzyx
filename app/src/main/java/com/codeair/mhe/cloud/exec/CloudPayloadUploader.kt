package com.codeair.mhe.cloud.exec

import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.sync.CloudArtifactSyncEngine
import com.codeair.mhe.cloud.sync.SyncPayload
import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.core.result.OperationStatus
import java.io.File
import java.security.MessageDigest

/** Honest accounting of a delta upload. */
data class DeltaUploadResult(
    val result: OperationResult,
    val manifestHash: String,
    val filesUploaded: Int,
    val filesSkipped: Int,
    val bytesUploaded: Long
)

/**
 * Delta workspace payload uploader (Milestone 11).
 *
 * Computes the workspace snapshot via [CloudArtifactSyncEngine], asks the worker
 * which files it already holds for that snapshot hash, and uploads only changed /
 * new files. Files the worker already has (same path + same content hash) are
 * skipped — never re-uploaded, and the skip count is reported honestly.
 */
class CloudPayloadUploader(
    private val client: CloudExecutionClient,
    private val syncEngine: CloudArtifactSyncEngine = CloudArtifactSyncEngine()
) {

    fun uploadWorkspaceDelta(
        worker: CloudWorkerSpec,
        workspaceDir: File,
        excludedDirs: Set<String> = setOf(".git", ".gradle", "build", "node_modules", ".idea", "bin", "obj")
    ): DeltaUploadResult {
        val startedAt = System.currentTimeMillis()
        if (worker.authKey.isBlank()) {
            return DeltaUploadResult(
                OperationResult.notConfigured(
                    "Cloud worker '${worker.name}' has no auth key configured. Nothing was uploaded."
                ),
                manifestHash = "", filesUploaded = 0, filesSkipped = 0, bytesUploaded = 0L
            )
        }
        val payload: SyncPayload = syncEngine.createWorkspacePayload(workspaceDir, excludedDirs)
        if (payload.fileCount == 0) {
            return DeltaUploadResult(
                OperationResult.failure(
                    error = "Workspace payload is empty (no files under ${workspaceDir.absolutePath}). Nothing to upload.",
                    startedAt = startedAt
                ),
                manifestHash = payload.manifestHash, filesUploaded = 0, filesSkipped = 0, bytesUploaded = 0L
            )
        }

        val remoteManifest = client.fetchRemoteManifest(worker, payload.manifestHash)
        if (remoteManifest.result.status == OperationStatus.NOT_CONFIGURED) {
            return DeltaUploadResult(remoteManifest.result, payload.manifestHash, 0, 0, 0L)
        }
        if (remoteManifest.result.status == OperationStatus.FAILED) {
            return DeltaUploadResult(remoteManifest.result, payload.manifestHash, 0, 0, 0L)
        }
        // knownFiles: path -> sha256 the worker already holds for this snapshot.
        val knownFiles: Map<String, String> = remoteManifest.knownFiles

        var uploaded = 0
        var skipped = 0
        var bytes = 0L
        val logs = mutableListOf<String>()
        logs.add("Snapshot ${payload.manifestHash.take(12)}: ${payload.fileCount} files; worker already holds ${knownFiles.size}.")

        val localHashes = computeLocalFileHashes(workspaceDir, payload.relativePaths)
        for (relativePath in payload.relativePaths) {
            val file = File(workspaceDir, relativePath)
            if (!file.isFile) continue
            val localHash = localHashes[relativePath]
            val remoteHash = knownFiles[relativePath]
            if (localHash != null && remoteHash != null &&
                remoteHash.isNotBlank() && localHash.equals(remoteHash, ignoreCase = true)
            ) {
                // Worker holds identical content — skip the upload honestly.
                skipped++
                continue
            }
            val content = runCatching { file.readBytes() }.getOrElse {
                return DeltaUploadResult(
                    OperationResult.failure(
                        error = "Delta upload aborted: could not read local file $relativePath.",
                        startedAt = startedAt,
                        logs = logs
                    ),
                    payload.manifestHash, uploaded, skipped, bytes
                )
            }
            val up = client.uploadFile(worker, payload.manifestHash, relativePath, content)
            if (up.status != OperationStatus.SUCCESS) {
                return DeltaUploadResult(
                    OperationResult.failure(
                        error = "Delta upload failed on $relativePath: ${up.error}",
                        startedAt = startedAt,
                        logs = logs + up.logs
                    ),
                    payload.manifestHash, uploaded, skipped, bytes
                )
            }
            uploaded++
            bytes += content.size
            logs.add("Uploaded $relativePath (${content.size} bytes).")
        }

        return DeltaUploadResult(
            OperationResult.success(
                logs = logs + "Delta upload complete: $uploaded uploaded, $skipped skipped, $bytes bytes.",
                startedAt = startedAt,
                verificationEvidence = "HTTP 201 per uploaded file; skipped files already on worker"
            ),
            manifestHash = payload.manifestHash,
            filesUploaded = uploaded,
            filesSkipped = skipped,
            bytesUploaded = bytes
        )
    }

    private fun computeLocalFileHashes(workspaceDir: File, relativePaths: List<String>): Map<String, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        return relativePaths.mapNotNull { rel ->
            val file = File(workspaceDir, rel)
            if (!file.isFile) return@mapNotNull null
            val hash = runCatching {
                file.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    digest.reset()
                    while (input.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }.getOrNull() ?: return@mapNotNull null
            rel to hash
        }.toMap()
    }
}
