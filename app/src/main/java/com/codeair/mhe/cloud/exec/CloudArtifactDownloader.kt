package com.codeair.mhe.cloud.exec

import com.codeair.mhe.cloud.model.CloudArtifact
import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.sync.CloudArtifactSyncEngine
import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.core.result.OperationStatus
import java.io.File
import java.security.MessageDigest

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
 * Lists descriptors for a finished remote job, downloads each one's bytes, and
 * verifies the SHA-256 against the descriptor BEFORE writing anything to disk.
 * A checksum mismatch fails the whole fetch honestly — the mismatched bytes are
 * never written, never reported as an artifact.
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
            val dl = client.downloadArtifactBytes(worker, remoteJobId, descriptor.id)
            if (dl.result.status != OperationStatus.SUCCESS || dl.bytes == null) {
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
            val computed = sha256Hex(dl.bytes)
            if (descriptor.sha256.isNotBlank() && !computed.equals(descriptor.sha256, ignoreCase = true)) {
                return ArtifactFetchResult(
                    OperationResult.failure(
                        error = "Artifact '${descriptor.filename}' checksum mismatch: " +
                            "expected ${descriptor.sha256.take(16)}..., got ${computed.take(16)}.... " +
                            "Bytes were NOT written to disk.",
                        startedAt = startedAt,
                        logs = logs
                    ),
                    downloaded, verified, fetched
                )
            }
            val artifact = CloudArtifact(
                filename = descriptor.filename,
                remoteUrl = "${worker.endpointUrl.trimEnd('/')}/api/v1/jobs/$remoteJobId/artifacts/${descriptor.id}",
                sizeBytes = dl.bytes.size.toLong(),
                checksumSha256 = computed.ifBlank { descriptor.sha256 }
            )
            val written = syncEngine.unpackRemoteArtifact(artifact, destinationDir, dl.bytes)
            if (!written) {
                return ArtifactFetchResult(
                    OperationResult.failure(
                        error = "Artifact '${descriptor.filename}' verified but could not be written to ${destinationDir.absolutePath}.",
                        startedAt = startedAt,
                        logs = logs
                    ),
                    downloaded, verified, fetched
                )
            }
            verified++
            fetched.add(artifact)
            logs.add("Verified + wrote ${descriptor.filename} (${dl.bytes.size} bytes, sha256 ${computed.take(12)}...).")
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

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
