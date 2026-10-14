package com.codeair.mhe.cloud.exec

import com.codeair.mhe.cloud.model.CloudArtifact
import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.sync.CloudArtifactSyncEngine
import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.core.result.OperationStatus
import java.io.File

/** Honest accounting of an artifact download session. */
data class ArtifactFetchResult(
    val result: OperationResult,
    val downloaded: Int,
    val verified: Int,
    val artifacts: List<CloudArtifact>
)

/**
 * Cloud artifact downloader with SHA-256 verification (Milestone 11).
 *
 * Lists descriptors for a finished remote job, streams each artifact to a
 * temp file, and verifies the SHA-256 incrementally BEFORE it lands at its
 * final name. A checksum mismatch fails the whole fetch honestly — the
 * mismatched file is deleted, never kept and never reported as an artifact.
 */
class CloudArtifactDownloader(
    private val client: CloudExecutionClient,
    private val syncEngine: CloudArtifactSyncEngine = CloudArtifactSyncEngine()
) {

    fun fetchArtifacts(
        worker: CloudWorkerSpec,
        remoteJobId: String,
        destinationDir: File
    ): ArtifactFetchResult {
        val startedAt = System.currentTimeMillis()
        if (worker.authKey.isBlank()) {
            return ArtifactFetchResult(
                OperationResult.notConfigured(
                    "Cloud worker '${worker.name}' has no auth key configured. No artifacts fetched."
                ),
                0, 0, emptyList()
            )
        }
        val listRes = client.listArtifacts(worker, remoteJobId)
        if (listRes.status != OperationStatus.SUCCESS) {
            return ArtifactFetchResult(
                OperationResult.failure(
                    error = "Artifact fetch failed: could not list artifacts: ${listRes.error}",
                    startedAt = startedAt,
                    logs = listRes.logs
                ),
                0, 0, emptyList()
            )
        }
        val logs = mutableListOf<String>()
        val fetched = mutableListOf<CloudArtifact>()
        var downloaded = 0
        var verified = 0

        val typed = client.listArtifactDescriptors(worker, remoteJobId)
        if (typed.result.status != OperationStatus.SUCCESS) {
            return ArtifactFetchResult(
                OperationResult.failure(
                    error = "Artifact fetch failed: descriptor parse failed: ${typed.result.error}",
                    startedAt = startedAt,
                    logs = listOfNotNull(typed.result.error)
                ),
                0, 0, emptyList()
            )
        }

        for (descriptor in typed.descriptors) {
            if (descriptor.id.isBlank() || descriptor.filename.isBlank()) {
                logs.add("Skipping artifact with blank id/filename.")
                continue
            }
            // QA Medium: artifact bytes used to be held fully in memory
            // (plus a UTF-8 String copy in the transport) — a large
            // artifact OOM'd the app. The body now streams to a temp file
            // and is SHA-256-verified incrementally before it lands.
            val tmp = File(destinationDir, descriptor.filename + ".part")
            val dl = client.downloadArtifactToFile(worker, remoteJobId, descriptor.id, tmp, descriptor.sha256)
            if (dl.result.status != OperationStatus.SUCCESS || dl.sha256 == null) {
                runCatching { tmp.delete() }
                return ArtifactFetchResult(
                    OperationResult.failure(
                        error = "Artifact fetch failed downloading '${descriptor.filename}': ${dl.result.error}",
                        startedAt = startedAt,
                        logs = logs
                    ),
                    downloaded, verified, fetched
                )
            }
            downloaded++
            val target = File(destinationDir, descriptor.filename)
            if (!moveFile(tmp, target)) {
                return ArtifactFetchResult(
                    OperationResult.failure(
                        error = "Artifact '${descriptor.filename}' verified but could not be written to ${destinationDir.absolutePath}.",
                        startedAt = startedAt,
                        logs = logs
                    ),
                    downloaded, verified, fetched
                )
            }
            val artifact = CloudArtifact(
                filename = descriptor.filename,
                remoteUrl = "${worker.endpointUrl.trimEnd('/')}/api/v1/jobs/$remoteJobId/artifacts/${descriptor.id}",
                sizeBytes = target.length(),
                checksumSha256 = dl.sha256.ifBlank { descriptor.sha256 }
            )
            syncEngine.recordCachedArtifact(artifact, target.absolutePath)
            verified++
            fetched.add(artifact)
            logs.add("Verified + wrote ${descriptor.filename} (${target.length()} bytes, sha256 ${dl.sha256.take(12)}...).")
        }

        return ArtifactFetchResult(
            OperationResult.success(
                logs = logs + "Artifact fetch complete: $downloaded downloaded, $verified verified.",
                startedAt = startedAt,
                artifacts = fetched.map { it.filename },
                verificationEvidence = "$verified artifacts SHA-256 verified and written"
            ),
            downloaded, verified, fetched
        )
    }

    /**
     * renameTo is atomic but fails across filesystems; fall back to
     * copy+delete so a verified artifact is never lost to a rename quirk.
     */
    private fun moveFile(src: File, dest: File): Boolean =
        runCatching {
            dest.parentFile?.mkdirs()
            if (src.renameTo(dest)) return true
            src.copyTo(dest, overwrite = true)
            src.delete()
            true
        }.getOrDefault(false)
}
