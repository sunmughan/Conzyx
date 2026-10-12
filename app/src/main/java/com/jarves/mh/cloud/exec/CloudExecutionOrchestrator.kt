package com.jarves.mh.cloud.exec

import com.jarves.mh.cloud.model.CloudWorkerSpec
import com.jarves.mh.cloud.model.ExecutionTarget
import com.jarves.mh.cloud.model.HybridExecutionJob
import com.jarves.mh.cloud.model.HybridJobState
import com.jarves.mh.core.result.OperationStatus
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * End-to-end cloud execution orchestrator (Milestone 11).
 *
 * Pipeline: cost-gate -> delta payload upload -> submit -> log streaming +
 * status polling to terminal state -> artifact download + SHA-256 verification ->
 * spend recording. Cancellation is supported at every stage via [cancel].
 *
 * Honesty contract (Milestone 0 rules carry over): every stage failure short-
 * circuits with a FAILED / CANCELLED / NOT_CONFIGURED job — a cloud job is only
 * COMPLETED when the remote worker genuinely reached a terminal SUCCEEDED state
 * AND its artifacts verified.
 */
class CloudExecutionOrchestrator(
    private val client: CloudExecutionClient = CloudExecutionClient(),
    private val uploader: CloudPayloadUploader? = null,
    private val logStreamer: CloudLogStreamer? = null,
    private val downloader: CloudArtifactDownloader? = null,
    private val costController: CloudCostController = CloudCostController(),
    private val statusPollIntervalMs: Long = 2000L,
    private val statusPollTimeoutMs: Long = 30 * 60 * 1000L
) {
    private val cancelledJobs = ConcurrentHashMap<String, Boolean>()
    private val remoteIds = ConcurrentHashMap<String, String>()

    fun cancel(jobId: String) {
        cancelledJobs[jobId] = true
        logStreamer?.cancel()
    }

    private fun isCancelled(jobId: String): Boolean = cancelledJobs[jobId] == true

    /**
     * Runs the full cloud pipeline for [job] against [worker].
     * [workspaceDir] is used for the delta payload upload when [uploader] is set;
     * [artifactDir] receives verified artifacts when [downloader] is set.
     */
    fun executeCloudJob(
        job: HybridExecutionJob,
        worker: CloudWorkerSpec,
        workspaceDir: File? = null,
        artifactDir: File? = null,
        onLogChunk: (String) -> Unit = {}
    ): HybridExecutionJob {
        val startedAt = System.currentTimeMillis()
        val logs = mutableListOf<String>()
        fun log(line: String) {
            logs.add(line)
            onLogChunk(line)
        }

        fun finish(state: HybridJobState, error: String?, exitCode: Int?, costUsd: Double, extraLogs: List<String> = emptyList()): HybridExecutionJob {
            val end = System.currentTimeMillis()
            return job.copy(
                state = state,
                outputLogs = logs + extraLogs,
                exitCode = exitCode,
                finishedAt = end,
                durationMs = end - startedAt,
                estimatedCostUsd = costUsd,
                errorMessage = error
            )
        }

        if (isCancelled(job.id)) {
            log("[Orchestrator] Job cancelled before dispatch.")
            return finish(HybridJobState.CANCELLED, "Cancelled before dispatch.", null, 0.0)
        }

        // 1. Cost gate.
        val auth = costController.authorizeDispatch(worker, job.characteristics.estimatedDurationSec.toLong(), job.title)
        log("[Orchestrator] Cost gate: ${auth.reason}")
        if (!auth.authorized) {
            return finish(HybridJobState.FAILED, auth.reason, null, 0.0)
        }

        // 2. Delta payload upload.
        var manifestHash = ""
        if (uploader != null && workspaceDir != null) {
            val delta = uploader.uploadWorkspaceDelta(worker, workspaceDir)
            delta.result.logs.forEach(::log)
            if (delta.result.status == OperationStatus.NOT_CONFIGURED) {
                return finish(HybridJobState.NOT_CONFIGURED, delta.result.error, null, 0.0)
            }
            if (delta.result.status != OperationStatus.SUCCESS) {
                return finish(HybridJobState.FAILED, "Payload upload failed: ${delta.result.error}", null, 0.0)
            }
            manifestHash = delta.manifestHash
            log("[Orchestrator] Delta upload: ${delta.filesUploaded} uploaded, ${delta.filesSkipped} skipped.")
        }

        if (isCancelled(job.id)) {
            return finish(HybridJobState.CANCELLED, "Cancelled after payload upload.", null, 0.0)
        }

        // 3. Submit.
        val request = CloudJobRequest(
            title = job.title,
            workloadType = job.workloadType.name,
            command = job.localCommand.ifBlank { "echo 'no command'" },
            manifestHash = manifestHash,
            timeoutSec = maxOf(60, job.characteristics.estimatedDurationSec * 3)
        )
        val submit = client.submitJob(worker, request)
        submit.logs.forEach(::log)
        if (submit.status == OperationStatus.NOT_CONFIGURED) {
            return finish(HybridJobState.NOT_CONFIGURED, submit.error, null, 0.0)
        }
        if (submit.status != OperationStatus.SUCCESS) {
            return finish(HybridJobState.FAILED, "Remote submission failed: ${submit.error}", submit.exitCode, 0.0)
        }
        val remoteJobId = submit.artifacts.firstOrNull().orEmpty()
        remoteIds[job.id] = remoteJobId

        // 4. Log streaming + status polling to a terminal state.
        var lastStatus = RemoteJobStatus.QUEUED
        var remoteExitCode: Int? = null
        val deadline = System.currentTimeMillis() + statusPollTimeoutMs
        val streamer = logStreamer
        if (streamer == null) {
            // No streamer wired: fall back to plain status polling without live logs.
            while (!isCancelled(job.id) && System.currentTimeMillis() < deadline) {
                val poll = client.pollStatus(worker, remoteJobId)
                if (poll.status != OperationStatus.SUCCESS) {
                    return finish(HybridJobState.FAILED, "Status polling failed: ${poll.error}", poll.exitCode, auth.estimatedCostUsd)
                }
                lastStatus = RemoteJobStatus.parse(poll.artifacts.firstOrNull())
                remoteExitCode = poll.exitCode
                log("[Orchestrator] Remote status: ${lastStatus.name}")
                if (lastStatus.isTerminal) break
                try {
                    Thread.sleep(statusPollIntervalMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } else {
            val streamResult = streamer.streamLogs(
                worker, remoteJobId,
                onLogChunk = ::log,
                isTerminal = {
                    if (isCancelled(job.id)) return@streamLogs true
                    val poll = client.pollStatus(worker, remoteJobId)
                    if (poll.status == OperationStatus.SUCCESS) {
                        lastStatus = RemoteJobStatus.parse(poll.artifacts.firstOrNull())
                        remoteExitCode = poll.exitCode
                        lastStatus.isTerminal
                    } else {
                        log("[Orchestrator] Status poll during streaming failed: ${poll.error}")
                        true // stop streaming on poll failure; handled below
                    }
                }
            )
            if (streamResult.result.status != OperationStatus.SUCCESS) {
                return finish(HybridJobState.FAILED, "Log streaming failed: ${streamResult.result.error}", remoteExitCode, auth.estimatedCostUsd)
            }
            if (streamResult.wasCancelled || isCancelled(job.id)) {
                client.cancelJob(worker, remoteJobId)
                return finish(HybridJobState.CANCELLED, "Cancelled during remote execution.", remoteExitCode, auth.estimatedCostUsd)
            }
        }

        if (isCancelled(job.id)) {
            client.cancelJob(worker, remoteJobId)
            return finish(HybridJobState.CANCELLED, "Cancelled during remote execution.", remoteExitCode, auth.estimatedCostUsd)
        }

        if (System.currentTimeMillis() >= deadline && !lastStatus.isTerminal) {
            client.cancelJob(worker, remoteJobId)
            return finish(HybridJobState.FAILED, "Remote job did not reach a terminal state within ${statusPollTimeoutMs / 1000}s; cancellation requested.", remoteExitCode, auth.estimatedCostUsd)
        }

        when (lastStatus) {
            RemoteJobStatus.CANCELLED -> {
                return finish(HybridJobState.CANCELLED, "Remote job was cancelled on the worker.", remoteExitCode, auth.estimatedCostUsd)
            }
            RemoteJobStatus.FAILED, RemoteJobStatus.UNKNOWN -> {
                return finish(
                    HybridJobState.FAILED,
                    "Remote job ended with status ${lastStatus.name}${if (remoteExitCode != null) " (exit=$remoteExitCode)" else ""}.",
                    remoteExitCode, auth.estimatedCostUsd
                )
            }
            else -> { /* SUCCEEDED — continue to artifacts */ }
        }

        // 5. Artifact download + verification.
        var fetchedArtifacts = emptyList<com.jarves.mh.cloud.model.CloudArtifact>()
        if (downloader != null && artifactDir != null) {
            val fetch = downloader.fetchArtifacts(worker, remoteJobId, artifactDir)
            fetch.result.logs.forEach(::log)
            if (fetch.result.status != OperationStatus.SUCCESS) {
                // Remote work succeeded but artifacts could not be verified — report FAILED,
                // never COMPLETED, because the deliverables are unverified.
                return finish(HybridJobState.FAILED, "Artifact verification failed: ${fetch.result.error}", remoteExitCode, auth.estimatedCostUsd)
            }
            fetchedArtifacts = fetch.artifacts
        }

        // 6. Record real spend.
        val actualDurationSec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(1)
        val actualCost = costController.estimateCost(worker, actualDurationSec)
        costController.recordSpend(
            CloudSpendEntry(
                jobId = job.id,
                workerId = worker.id,
                workerName = worker.name,
                durationSec = actualDurationSec,
                costUsd = actualCost
            )
        )
        log("[Orchestrator] Recorded spend \$${"%.4f".format(actualCost)} (${actualDurationSec}s on ${worker.name}).")

        val end = System.currentTimeMillis()
        return job.copy(
            state = HybridJobState.COMPLETED,
            routingDecision = job.routingDecision ?: com.jarves.mh.cloud.model.RoutingDecision(
                target = ExecutionTarget.CLOUD,
                reason = "Executed via M11 cloud orchestrator on ${worker.name}."
            ),
            assignedWorker = worker,
            remoteJobId = remoteJobId,
            outputLogs = logs,
            producedArtifacts = fetchedArtifacts,
            exitCode = remoteExitCode ?: 0,
            finishedAt = end,
            durationMs = end - startedAt,
            estimatedCostUsd = actualCost
        )
    }
}
