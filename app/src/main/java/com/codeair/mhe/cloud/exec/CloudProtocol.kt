package com.codeair.mhe.cloud.exec

import com.codeair.mhe.network.HttpRetry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Wire protocol for the Mobile Harness Extended cloud worker API (Milestone 11).
 *
 * Base URL: the worker's `endpointUrl` (e.g. https://worker.example.com).
 * Auth: `Authorization: Bearer <authKey>` on every call. A blank auth key is
 * never sent — the client refuses with NOT_CONFIGURED instead.
 *
 * Endpoints (all JSON unless noted):
 * - POST   /api/v1/jobs                      -> { "jobId": "..." } (201)
 * - GET    /api/v1/jobs/{id}                 -> { "status": "...", "exitCode": n|null, "message": "..." } (200)
 * - GET    /api/v1/jobs/{id}/logs?since={n}  -> { "logs": [ { "seq": n, "line": "..." } ], "nextSeq": n } (200)
 * - POST   /api/v1/jobs/{id}/cancel          -> { "cancelled": true } (200)
 * - GET    /api/v1/jobs/{id}/artifacts       -> { "artifacts": [ { "id","filename","sizeBytes","sha256" } ] } (200)
 * - GET    /api/v1/jobs/{id}/artifacts/{aid} -> raw bytes (200)
 * - GET    /api/v1/payloads/{manifestHash}   -> { "known": true, "files": [ { "path","sha256" } ] } (200/404)
 * - POST   /api/v1/payloads/{manifestHash}/files -> multipart-ish JSON { "path": "...", "bytesBase64": "..." } (201)
 */
enum class RemoteJobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN;

    companion object {
        fun parse(raw: String?): RemoteJobStatus = when (raw?.uppercase()) {
            "QUEUED" -> QUEUED
            "RUNNING" -> RUNNING
            "SUCCEEDED", "SUCCESS", "COMPLETED" -> SUCCEEDED
            "FAILED", "ERROR" -> FAILED
            "CANCELLED", "CANCELED" -> CANCELLED
            else -> UNKNOWN
        }
    }

    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

/** A single line of streamed remote log output. */
data class CloudLogFrame(val seq: Long, val line: String)

/** Artifact descriptor as reported by the worker (downloaded bytes verified separately). */
data class CloudArtifactDescriptor(
    val id: String,
    val filename: String,
    val sizeBytes: Long,
    val sha256: String
)

/** What we ask the worker to run. */
data class CloudJobRequest(
    val title: String,
    val workloadType: String,
    val command: String,
    val manifestHash: String,
    val env: Map<String, String> = emptyMap(),
    val timeoutSec: Int = 1800
)

/** Minimal HTTP request/response pair used by the injectable transport seam. */
data class CloudTransportRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

data class CloudTransportResponse(
    val statusCode: Int,
    val body: String,
    val rawBytes: ByteArray? = null
)

/**
 * Outcome of a streamed file download: the HTTP status, an error message
 * (null on success), and how many body bytes were actually written.
 */
data class StreamedDownload(
    val statusCode: Int,
    val error: String?,
    val bytesWritten: Long
)

/**
 * Injectable HTTP transport seam. Production implementation uses HttpURLConnection;
 * tests use a fake. Never throws for transport errors — encodes them in the response
 * (statusCode = -1) so callers stay honest.
 */
interface CloudHttpTransport {
    fun send(baseUrl: String, request: CloudTransportRequest): CloudTransportResponse

    /**
     * Streams a response body straight to [dest] without holding it in
     * memory. Only the initial response is retried (429/5xx before any
     * body byte flows); a failure mid-stream is reported as-is and the
     * partial file is deleted.
     */
    fun downloadToFile(baseUrl: String, request: CloudTransportRequest, dest: File): StreamedDownload
}

/** Production transport over HttpURLConnection. Pure JVM, no Android dependency. */
class UrlConnectionCloudTransport(
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 30000
) : CloudHttpTransport {

    override fun send(baseUrl: String, request: CloudTransportRequest): CloudTransportResponse {
        // QA Medium: one transient 429/5xx used to be final. Retry with
        // backoff, honoring the server's Retry-After header.
        var attempt = 0
        while (true) {
            try {
                val url = java.net.URL(baseUrl.trimEnd('/') + request.path)
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    requestMethod = request.method
                    instanceFollowRedirects = true
                    request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (request.body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
                    }
                    connect()
                }
                val code = conn.responseCode
                if (HttpRetry.isRetryable(code) &&
                    attempt < HttpRetry.MAX_ATTEMPTS - 1
                ) {
                    val retryAfter = conn.getHeaderField("Retry-After")
                    conn.disconnect()
                    HttpRetry.sleepBeforeRetry(attempt, retryAfter)
                    attempt++
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream?.readBytes()
                conn.disconnect()
                return CloudTransportResponse(code, bytes?.toString(Charsets.UTF_8).orEmpty(), bytes)
            } catch (e: Exception) {
                return CloudTransportResponse(-1, "transport error: ${e.message}")
            }
        }
    }

    override fun downloadToFile(
        baseUrl: String,
        request: CloudTransportRequest,
        dest: File
    ): StreamedDownload {
        // QA Medium: artifact downloads used to materialize the whole body
        // (raw bytes + a UTF-8 String copy) — a large artifact OOM'd the app.
        // The body now streams to disk in 8 KB chunks; memory stays flat.
        var attempt = 0
        while (true) {
            try {
                val url = java.net.URL(baseUrl.trimEnd('/') + request.path)
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    requestMethod = request.method
                    instanceFollowRedirects = true
                    request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    connect()
                }
                val code = conn.responseCode
                if (HttpRetry.isRetryable(code) &&
                    attempt < HttpRetry.MAX_ATTEMPTS - 1
                ) {
                    val retryAfter = conn.getHeaderField("Retry-After")
                    conn.disconnect()
                    HttpRetry.sleepBeforeRetry(attempt, retryAfter)
                    attempt++
                    continue
                }
                if (code !in 200..299) {
                    val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    conn.disconnect()
                    return StreamedDownload(code, "HTTP $code: ${errBody.take(500)}", 0)
                }
                var written = 0L
                conn.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            written += n
                        }
                    }
                }
                conn.disconnect()
                return StreamedDownload(code, null, written)
            } catch (e: Exception) {
                runCatching { dest.delete() }
                return StreamedDownload(-1, "transport error: ${e.message}", 0)
            }
        }
    }
}

/** Parse helpers shared by client and tests. */
internal fun parseLogFrames(body: String): Pair<List<CloudLogFrame>, Long> {
    val obj = JSONObject(body)
    val frames = mutableListOf<CloudLogFrame>()
    val arr: JSONArray = obj.optJSONArray("logs") ?: JSONArray()
    for (i in 0 until arr.length()) {
        val e = arr.getJSONObject(i)
        frames.add(CloudLogFrame(e.optLong("seq", 0L), e.optString("line", "")))
    }
    return frames to obj.optLong("nextSeq", frames.lastOrNull()?.seq?.plus(1) ?: 0L)
}

internal fun parseArtifactDescriptors(body: String): List<CloudArtifactDescriptor> {
    val obj = JSONObject(body)
    val arr: JSONArray = obj.optJSONArray("artifacts") ?: JSONArray()
    return (0 until arr.length()).map { i ->
        val e = arr.getJSONObject(i)
        CloudArtifactDescriptor(
            id = e.optString("id", ""),
            filename = e.optString("filename", ""),
            sizeBytes = e.optLong("sizeBytes", 0L),
            sha256 = e.optString("sha256", "")
        )
    }
}
