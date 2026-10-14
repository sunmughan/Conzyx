package com.codeair.mhe.cloud.exec

import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.core.result.OperationStatus
import java.util.concurrent.atomic.AtomicBoolean

/** Outcome of a bounded log-streaming session. */
data class LogStreamResult(
    val result: OperationResult,
    val framesReceived: Int,
    val finalSeq: Long,
    val wasCancelled: Boolean
)

/**
 * Incremental remote log streamer (Milestone 11).
 *
 * Polls GET /api/v1/jobs/{id}/logs?since={seq} with a sequence cursor and
 * forwards new frames to [onLogChunk]. Bounded: stops at [maxFrames],
 * [timeoutMs], or when [cancel] is called. Never spins silently — every
 * failure is reported in the result.
 */
class CloudLogStreamer(
    private val client: CloudExecutionClient,
    private val pollIntervalMs: Long = 1500L,
    private val maxFrames: Int = 5000,
    private val timeoutMs: Long = 30 * 60 * 1000L,
    // QA Medium: one transient poll failure used to abort the whole stream.
    // A few consecutive failures are now retried with backoff; the stream
    // fails only when [maxConsecutiveFailures] polls fail in a row.
    private val maxConsecutiveFailures: Int = 3,
    private val retryDelayMs: Long = 1000L,
) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun streamLogs(
        worker: CloudWorkerSpec,
        remoteJobId: String,
        onLogChunk: (String) -> Unit = {},
        isTerminal: () -> Boolean = { false }
    ): LogStreamResult {
        val startedAt = System.currentTimeMillis()
        if (worker.authKey.isBlank()) {
            return LogStreamResult(
                OperationResult.notConfigured("Cloud worker '${worker.name}' has no auth key configured. No logs streamed."),
                0, 0L, wasCancelled = false
            )
        }
        var seq = 0L
        var frames = 0
        var consecutiveFailures = 0
        val deadline = startedAt + timeoutMs
        while (!cancelled.get() && frames < maxFrames && System.currentTimeMillis() < deadline) {
            val res = client.fetchLogs(worker, remoteJobId, seq)
            if (res.status != OperationStatus.SUCCESS) {
                consecutiveFailures++
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    return LogStreamResult(
                        OperationResult.failure(
                            error = "Log streaming failed at seq $seq after $consecutiveFailures attempts: ${res.error}",
                            startedAt = startedAt,
                            logs = res.logs
                        ),
                        frames, seq, wasCancelled = cancelled.get()
                    )
                }
                // Transient poll failure: keep the cursor, back off
                // briefly, and retry instead of aborting the stream.
                try {
                    Thread.sleep(retryDelayMs * consecutiveFailures)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }
            consecutiveFailures = 0
            val nextSeq = res.artifacts.firstOrNull()
                ?.removePrefix("nextSeq=")?.toLongOrNull() ?: (seq + res.logs.size)
            for (line in res.logs) {
                onLogChunk(line)
                frames++
                if (frames >= maxFrames) break
            }
            seq = nextSeq
            if (isTerminal()) break
            if (res.logs.isEmpty()) {
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        val wasCancelled = cancelled.get()
        return LogStreamResult(
            OperationResult.success(
                logs = listOf("Log stream ended: $frames frames, finalSeq=$seq${if (wasCancelled) " (cancelled)" else ""}."),
                startedAt = startedAt,
                verificationEvidence = "$frames frames fetched via sequenced log API"
            ),
            frames, seq, wasCancelled
        )
    }
}
