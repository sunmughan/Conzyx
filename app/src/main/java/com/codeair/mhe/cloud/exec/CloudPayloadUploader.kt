package com.codeair.mhe.cloud.exec

import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.sync.CloudArtifactSyncEngine
import com.codeair.mhe.cloud.sync.SyncPayload
import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.core.result.OperationStatus
import com.codeair.mhe.security.SafetyDeniedException
import com.codeair.mhe.security.SafetyGateway
import com.codeair.mhe.security.SafetyOperation
import com.codeair.mhe.security.SafetyVerdict
import java.io.File
import java.nio.file.Files
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
 * QA Medium: the uploader used to read each whole file into a ByteArray
 * and base64 it into one JSON string (file + 33% overhead in memory,
 * OOM risk on large files). The wire protocol has no chunked-upload
 * endpoint, so a single file over this size is refused with a clear
 * error instead of crashing the app.
 */
const val MAX_SINGLE_FILE_UPLOAD_BYTES = 32L * 1024 * 1024

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
    private val syncEngine: CloudArtifactSyncEngine = CloudArtifactSyncEngine(),
    private val safetyGateway: SafetyGateway? = null,
    private val sessionId: String? = null,
    private val projectId: String? = null
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
            guardUploadFile(workspaceDir, file, relativePath, worker)?.let { guardResult ->
                return DeltaUploadResult(
                    guardResult.copy(logs = logs + listOfNotNull(guardResult.error)),
                    payload.manifestHash, uploaded, skipped, bytes
                )
            }
            val localHash = localHashes[relativePath]
            val remoteHash = knownFiles[relativePath]
            if (localHash != null && remoteHash != null &&
                remoteHash.isNotBlank() && localHash.equals(remoteHash, ignoreCase = true)
            ) {
                // Worker holds identical content — skip the upload honestly.
                skipped++
                continue
            }
            if (file.length() > MAX_SINGLE_FILE_UPLOAD_BYTES) {
                return DeltaUploadResult(
                    OperationResult.failure(
                        error = "Delta upload aborted: $relativePath is ${file.length()} bytes, over the " +
                            "${MAX_SINGLE_FILE_UPLOAD_BYTES / 1024 / 1024} MB single-file upload limit. " +
                            "Split the file or exclude it — the upload API has no chunked endpoint.",
                        startedAt = startedAt,
                        logs = logs
                    ),
                    payload.manifestHash, uploaded, skipped, bytes
                )
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

    private fun guardUploadFile(
        workspaceDir: File,
        file: File,
        relativePath: String,
        worker: CloudWorkerSpec
    ): OperationResult? {
        if (Files.isSymbolicLink(file.toPath())) {
            return OperationResult.failure(
                error = "Delta upload aborted: '$relativePath' is a symlink. Symlinks are never uploaded to cloud workers."
            )
        }
        val insideWorkspace = runCatching {
            val root = workspaceDir.canonicalFile
            val target = file.canonicalFile
            target == root || target.path.startsWith(root.path + File.separator)
        }.getOrDefault(false)
        if (!insideWorkspace) {
            return OperationResult.failure(
                error = "Delta upload aborted: '$relativePath' resolves outside the workspace root."
            )
        }
        val classification = CloudUploadSecretPolicy.classify(relativePath)
        if (classification.sensitivity == UploadFileSensitivity.BLOCKED) {
            return OperationResult.failure(
                error = "Delta upload aborted: refusing to upload secret file '$relativePath' (${classification.reason})."
            )
        }
        if (classification.sensitivity == UploadFileSensitivity.SENSITIVE) {
            val gateway = safetyGateway
                ?: return OperationResult.failure(
                    error = "Delta upload aborted: '$relativePath' looks sensitive (${classification.reason}) " +
                        "and requires explicit user approval, but no safety gateway is wired."
                )
            val operation = SafetyOperation.RemoteExec(
                target = worker.name,
                command = "upload $relativePath"
            )
            val decision = try {
                gateway.requireAllowed(operation, sessionId, projectId)
            } catch (denied: SafetyDeniedException) {
                return OperationResult.failure(
                    error = "Delta upload aborted: upload of '$relativePath' was denied: ${denied.message}"
                )
            }
            if (decision.verdict != SafetyVerdict.ALLOW) {
                val promptText = decision.prompt?.renderText()
                return OperationResult.failure(
                    error = "Delta upload aborted: '$relativePath' looks sensitive (${classification.reason}) " +
                        "and requires explicit user approval." +
                        (promptText?.let { "\n$it" } ?: "")
                )
            }
        }
        return null
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
