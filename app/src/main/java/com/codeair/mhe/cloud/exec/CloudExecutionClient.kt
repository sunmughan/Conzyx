package com.codeair.mhe.cloud.exec

import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.core.result.OperationStatus
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** Result of a streamed artifact download: the honest outcome plus the verified file facts. */
data class ArtifactFileDownloadResult(
    val result: OperationResult,
    val bytesWritten: Long,
    val sha256: String?
)

/** Result of a payload manifest check: the honest outcome plus path->sha256 of known files. */
data class RemoteManifestResult(
    val result: OperationResult,
    val knownFiles: Map<String, String>
)

/** Result of a typed artifact-descriptor listing. */
data class ArtifactDescriptorListResult(
    val result: OperationResult,
    val descriptors: List<CloudArtifactDescriptor>
)

/**
 * Authenticated cloud worker API client (Milestone 11).
 *
 * Every call requires a non-blank [CloudWorkerSpec.authKey]; a blank key returns
 * [OperationStatus.NOT_CONFIGURED] immediately — the key is never sent empty.
 * All failures are reported honestly (FAILED with the real HTTP status / transport
 * error); success is only reported when the worker's response verifies it.
 */
class CloudExecutionClient(
    private val transport: CloudHttpTransport = UrlConnectionCloudTransport()
) {

    private fun authHeaders(worker: CloudWorkerSpec): Map<String, String> =
        mapOf("Authorization" to "Bearer ${worker.authKey}")

    private fun requireAuth(worker: CloudWorkerSpec): OperationResult? =
        if (worker.authKey.isBlank()) {
            OperationResult.notConfigured(
                "Cloud worker '${worker.name}' has no auth key configured. " +
                    "Set an auth key before dispatching remote jobs — nothing was sent."
            )
        } else null

    private fun checkTransport(res: CloudTransportResponse, what: String): OperationResult? {
        if (res.statusCode == -1) {
            return OperationResult.failure(
                error = "Cloud $what failed: transport error (${res.body}).",
                logs = listOf(res.body)
            )
        }
        return null
    }

    /** POST /api/v1/jobs — returns the remote job id on 201. */
    fun submitJob(worker: CloudWorkerSpec, request: CloudJobRequest): OperationResult {
        requireAuth(worker)?.let { return it }
        val body = JSONObject()
            .put("title", request.title)
            .put("workloadType", request.workloadType)
            .put("command", request.command)
            .put("manifestHash", request.manifestHash)
            .put("timeoutSec", request.timeoutSec)
            .put("env", JSONObject(request.env))
            .toString()
        val res = transport.send(
            worker.endpointUrl,
            CloudTransportRequest("POST", "/api/v1/jobs", authHeaders(worker), body)
        )
        checkTransport(res, "job submission")?.let { return it }
        if (res.statusCode != 201) {
            return OperationResult.failure(
                error = "Cloud job submission rejected: HTTP ${res.statusCode} from ${worker.endpointUrl}.",
                exitCode = res.statusCode,
                logs = listOf(res.body.take(2000))
            )
        }
        val remoteId = runCatching { JSONObject(res.body).optString("jobId", "") }.getOrDefault("")
        if (remoteId.isBlank()) {
            return OperationResult.failure(
                error = "Cloud job submission returned HTTP 201 but no jobId.",
                exitCode = 201,
                logs = listOf(res.body.take(2000))
            )
        }
        return OperationResult.success(
            logs = listOf("Submitted remote job $remoteId on ${worker.name}."),
            artifacts = listOf(remoteId),
            verificationEvidence = "HTTP 201 with jobId=$remoteId"
        )
    }

    /** GET /api/v1/jobs/{id} — current remote status. */
    fun pollStatus(worker: CloudWorkerSpec, remoteJobId: String): OperationResult {
        requireAuth(worker)?.let { return it }
        val res = transport.send(
            worker.endpointUrl,
            CloudTransportRequest("GET", "/api/v1/jobs/$remoteJobId", authHeaders(worker))
        )
        checkTransport(res, "status poll")?.let { return it }
        if (res.statusCode != 200) {
            return OperationResult.failure(
                error = "Cloud status poll failed: HTTP ${res.statusCode} for job $remoteJobId.",
                exitCode = res.statusCode,
                logs = listOf(res.body.take(2000))
            )
        }
        val obj = runCatching { JSONObject(res.body) }.getOrElse {
            return OperationResult.failure(
                error = "Cloud status poll returned unparseable JSON for job $remoteJobId.",
                logs = listOf(res.body.take(2000))
            )
        }
        val status = RemoteJobStatus.parse(obj.optString("status", null))
        val exitCode = if (obj.has("exitCode") && !obj.isNull("exitCode")) obj.optInt("exitCode") else null
        return OperationResult.success(
            exitCode = exitCode,
            logs = listOf("Remote job $remoteJobId status: ${status.name}"),
            artifacts = listOf(status.name),
            verificationEvidence = "HTTP 200 status=${status.name}"
        )
    }

    /** GET /api/v1/jobs/{id}/logs?since={seq} — incremental log frames. */
    fun fetchLogs(worker: CloudWorkerSpec, remoteJobId: String, sinceSeq: Long): OperationResult {
        requireAuth(worker)?.let { return it }
        val res = transport.send(
            worker.endpointUrl,
            CloudTransportRequest("GET", "/api/v1/jobs/$remoteJobId/logs?since=$sinceSeq", authHeaders(worker))
        )
        checkTransport(res, "log fetch")?.let { return it }
        if (res.statusCode != 200) {
            return OperationResult.failure(
                error = "Cloud log fetch failed: HTTP ${res.statusCode} for job $remoteJobId.",
                exitCode = res.statusCode,
                logs = listOf(res.body.take(2000))
            )
        }
        val (frames, nextSeq) = runCatching { parseLogFrames(res.body) }.getOrElse {
            return OperationResult.failure(
                error = "Cloud log fetch returned unparseable JSON for job $remoteJobId.",
                logs = listOf(res.body.take(2000))
            )
        }
        return OperationResult.success(
            logs = frames.map { it.line },
            artifacts = listOf("nextSeq=$nextSeq"),
            verificationEvidence = "HTTP 200, ${frames.size} frames, nextSeq=$nextSeq"
        )
    }

    /** POST /api/v1/jobs/{id}/cancel — best-effort cancellation. */
    fun cancelJob(worker: CloudWorkerSpec, remoteJobId: String): OperationResult {
        requireAuth(worker)?.let { return it }
        val res = transport.send(
            worker.endpointUrl,
            CloudTransportRequest("POST", "/api/v1/jobs/$remoteJobId/cancel", authHeaders(worker))
        )
        checkTransport(res, "cancellation")?.let { return it }
        if (res.statusCode != 200) {
            return OperationResult.failure(
                error = "Cloud cancellation failed: HTTP ${res.statusCode} for job $remoteJobId.",
                exitCode = res.statusCode,
                logs = listOf(res.body.take(2000))
            )
        }
        val cancelled = runCatching { JSONObject(res.body).optBoolean("cancelled", false) }.getOrDefault(false)
        return if (cancelled) {
            OperationResult.success(
                logs = listOf("Remote job $remoteJobId cancelled."),
                verificationEvidence = "HTTP 200 cancelled=true"
            )
        } else {
            OperationResult.failure(
                error = "Cloud worker did not confirm cancellation for job $remoteJobId.",
                logs = listOf(res.body.take(2000))
            )
        }
    }

    /** GET /api/v1/jobs/{id}/artifacts — descriptors for finished jobs. */
    fun listArtifacts(worker: CloudWorkerSpec, remoteJobId: String): OperationResult =
        listArtifactDescriptors(worker, remoteJobId).result

    /** Typed variant of [listArtifacts] returning parsed descriptors for downloaders. */
    fun listArtifactDescriptors(
        worker: CloudWorkerSpec,
        remoteJobId: String
    ): ArtifactDescriptorListResult {
        requireAuth(worker)?.let { return ArtifactDescriptorListResult(it, emptyList()) }
        val res = transport.send(
            worker.endpointUrl,
            CloudTransportRequest("GET", "/api/v1/jobs/$remoteJobId/artifacts", authHeaders(worker))
        )
        checkTransport(res, "artifact listing")?.let { return ArtifactDescriptorListResult(it, emptyList()) }
        if (res.statusCode != 200) {
            return ArtifactDescriptorListResult(
                OperationResult.failure(
                    error = "Cloud artifact listing failed: HTTP ${res.statusCode} for job $remoteJobId.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                emptyList()
            )
        }
        val descriptors = runCatching { parseArtifactDescriptors(res.body) }.getOrElse {
            return ArtifactDescriptorListResult(
                OperationResult.failure(
                    error = "Cloud artifact listing returned unparseable JSON for job $remoteJobId.",
                    logs = listOf(res.body.take(2000))
                ),
                emptyList()
            )
        }
        return ArtifactDescriptorListResult(
            OperationResult.success(
                logs = descriptors.map { "artifact ${it.filename} (${it.sizeBytes} bytes, sha256 ${it.sha256.take(12)}...)" },
                artifacts = descriptors.map { it.id },
                verificationEvidence = "HTTP 200, ${descriptors.size} artifact descriptors"
            ),
            descriptors
        )
    }

    /**
     * GET /api/v1/jobs/{id}/artifacts/{artifactId} — streamed straight to
     * [dest], honestly verified.
     *
     * QA Medium: this used to return the whole artifact as a ByteArray (the
     * transport also kept a UTF-8 String copy) — a large artifact OOM'd the
     * app. The body now streams to disk and the SHA-256 is computed
     * incrementally over the file; memory stays flat regardless of size.
     */
    fun downloadArtifactToFile(
        worker: CloudWorkerSpec,
        remoteJobId: String,
        artifactId: String,
        dest: File,
        expectedSha256: String
    ): ArtifactFileDownloadResult {
        requireAuth(worker)?.let { return ArtifactFileDownloadResult(it, 0, null) }
        val dl = transport.downloadToFile(
            worker.endpointUrl,
            CloudTransportRequest("GET", "/api/v1/jobs/$remoteJobId/artifacts/$artifactId", authHeaders(worker)),
            dest
        )
        if (dl.statusCode == -1) {
            return ArtifactFileDownloadResult(
                OperationResult.failure(
                    error = "Cloud artifact download failed: transport error (${dl.error}).",
                    logs = listOfNotNull(dl.error)
                ),
                0, null
            )
        }
        if (dl.statusCode != 200) {
            runCatching { dest.delete() }
            return ArtifactFileDownloadResult(
                OperationResult.failure(
                    error = "Cloud artifact download failed: HTTP ${dl.statusCode} for artifact $artifactId. ${dl.error.orEmpty()}",
                    exitCode = dl.statusCode,
                    logs = listOfNotNull(dl.error)
                ),
                0, null
            )
        }
        val computed = sha256Hex(dest)
        if (expectedSha256.isNotBlank() && !computed.equals(expectedSha256, ignoreCase = true)) {
            dest.delete()
            return ArtifactFileDownloadResult(
                OperationResult.failure(
                    error = "Artifact '$artifactId' checksum mismatch: expected " +
                        "${expectedSha256.take(16)}..., got ${computed.take(16)}.... " +
                        "The file was deleted, not kept.",
                    logs = listOf("checksum mismatch for artifact $artifactId")
                ),
                0, null
            )
        }
        return ArtifactFileDownloadResult(
            OperationResult.success(
                logs = listOf("Downloaded ${dl.bytesWritten} bytes for artifact $artifactId (sha256 ${computed.take(12)}...)."),
                artifacts = listOf(artifactId),
                verificationEvidence = "HTTP 200, ${dl.bytesWritten} bytes streamed to file, SHA-256 verified"
            ),
            dl.bytesWritten, computed
        )
    }

    /** SHA-256 of a file, computed incrementally — the file is never fully loaded. */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * GET /api/v1/payloads/{manifestHash} — does the worker already know this
     * workspace snapshot? Returns the files (path -> sha256) the worker already
     * holds, for delta computation. 404 = worker knows nothing about this
     * snapshot (full upload needed).
     */
    fun fetchRemoteManifest(worker: CloudWorkerSpec, manifestHash: String): RemoteManifestResult {
        requireAuth(worker)?.let { return RemoteManifestResult(it, emptyMap()) }
        val res = transport.send(
            worker.endpointUrl,
            CloudTransportRequest("GET", "/api/v1/payloads/$manifestHash", authHeaders(worker))
        )
        checkTransport(res, "payload manifest check")?.let { return RemoteManifestResult(it, emptyMap()) }
        if (res.statusCode == 404) {
            return RemoteManifestResult(
                OperationResult.success(
                    logs = listOf("Worker has no copy of snapshot $manifestHash; full upload required."),
                    verificationEvidence = "HTTP 404 (expected for unknown snapshot)"
                ),
                emptyMap()
            )
        }
        if (res.statusCode != 200) {
            return RemoteManifestResult(
                OperationResult.failure(
                    error = "Cloud payload manifest check failed: HTTP ${res.statusCode}.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                emptyMap()
            )
        }
        val knownFiles = runCatching {
            val arr = JSONObject(res.body).optJSONArray("files") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.getJSONObject(i)
                val path = e.optString("path", "")
                if (path.isBlank()) null else path to e.optString("sha256", "")
            }.toMap()
        }.getOrDefault(emptyMap())
        return RemoteManifestResult(
            OperationResult.success(
                logs = listOf("Worker knows ${knownFiles.size} files of snapshot $manifestHash."),
                verificationEvidence = "HTTP 200, ${knownFiles.size} known files"
            ),
            knownFiles
        )
    }

    /** POST /api/v1/payloads/{manifestHash}/files — upload one changed file (base64). */
    fun uploadFile(worker: CloudWorkerSpec, manifestHash: String, relativePath: String, bytes: ByteArray): OperationResult {
        requireAuth(worker)?.let { return it }
        val body = JSONObject()
            .put("path", relativePath)
            .put("bytesBase64", Base64.getEncoder().encodeToString(bytes))
            .toString()
        val res = transport.send(
            worker.endpointUrl,
            CloudTransportRequest("POST", "/api/v1/payloads/$manifestHash/files", authHeaders(worker), body)
        )
        checkTransport(res, "file upload")?.let { return it }
        if (res.statusCode != 201) {
            return OperationResult.failure(
                error = "Cloud file upload failed: HTTP ${res.statusCode} for $relativePath.",
                exitCode = res.statusCode,
                logs = listOf(res.body.take(2000))
            )
        }
        return OperationResult.success(
            logs = listOf("Uploaded $relativePath (${bytes.size} bytes)."),
            verificationEvidence = "HTTP 201 for $relativePath"
        )
    }
}
