package com.jarves.mh.cloud.runner

import com.jarves.mh.cloud.model.CloudWorkerSpec
import com.jarves.mh.cloud.model.ExecutionTarget
import com.jarves.mh.cloud.model.HybridExecutionJob
import com.jarves.mh.cloud.model.HybridJobState
import com.jarves.mh.core.result.OperationResult
import com.jarves.mh.core.result.OperationStatus
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Cloud & Local Hybrid Job Execution Runner (Phase 12).
 * Dispatches execution to remote cloud endpoints or the local PRoot environment.
 *
 * Milestone 0 integrity rules:
 * - The cloud path NEVER claims success without a real remote execution. When no
 *   reachable worker endpoint is configured, the job returns [HybridJobState.NOT_CONFIGURED]
 *   with a clear message. All canned logs, hardcoded checksums and hardcoded
 *   `exitCode = 0` outputs were removed.
 * - The local fallback runs the job's real [HybridExecutionJob.localCommand] and reports
 *   the REAL process exit code. A blank command is NOT_CONFIGURED, not success.
 *
 * Real remote dispatch (authenticated job API, log streaming, artifact download and
 * verification) is Milestone 11 work; until then the cloud path honestly reports
 * NOT_CONFIGURED.
 *
 * Milestone 11 update: pass a [cloudOrchestrator] (M11 protocol) to enable real
 * remote dispatch. When it is null, the cloud path keeps reporting NOT_CONFIGURED.
 */
class CloudJobRunner(
    /**
     * Probes whether a worker endpoint is genuinely reachable. Defaults to a real
     * HTTP health check with a short timeout. Injectable for tests.
     */
    private val workerReachability: (CloudWorkerSpec) -> Boolean = ::defaultWorkerReachability,
    /**
     * Runs a local shell command and returns its real result. Injectable for tests
     * and for wiring the PRoot runtime environment.
     */
    private val localCommandRunner: (command: String) -> OperationResult = ::defaultLocalCommandRunner,
    /**
     * M11 real cloud execution orchestrator. When set, cloud-routed jobs are
     * dispatched through the authenticated worker API (delta upload, log
     * streaming, artifact verification, cost controls). When null, the cloud
     * path honestly reports NOT_CONFIGURED.
     */
    private val cloudOrchestrator: com.jarves.mh.cloud.exec.CloudExecutionOrchestrator? = null,
) {

    private val runningJobs = ConcurrentHashMap<String, Boolean>()

    /**
     * Executes the job according to its routing decision.
     */
    fun execute(
        job: HybridExecutionJob,
        onLogChunk: (String) -> Unit = {}
    ): HybridExecutionJob {
        val jobId = job.id
        runningJobs[jobId] = true

        val logs = mutableListOf<String>()
        fun appendLog(line: String) {
            logs.add(line)
            onLogChunk(line)
        }

        val startTime = System.currentTimeMillis()
        val wantsCloud = job.routingDecision?.target == ExecutionTarget.CLOUD

        if (wantsCloud) {
            val worker = job.assignedWorker
            if (worker == null) {
                appendLog("[CloudRunner] No cloud worker assigned for job ${job.title}.")
                return finishNotConfigured(
                    job, jobId, logs, startTime,
                    "No cloud worker is assigned to this job. Cloud execution is not configured."
                )
            }
            appendLog("[CloudRunner] Probing worker ${worker.name} (${worker.region}) at ${worker.endpointUrl} ...")
            if (!isRunning(jobId)) {
                return job.copy(state = HybridJobState.CANCELLED, finishedAt = System.currentTimeMillis())
            }
            val orchestrator = cloudOrchestrator
            if (orchestrator != null) {
                appendLog("[CloudRunner] Dispatching '${job.title}' to ${worker.name} via the M11 cloud execution protocol ...")
                runningJobs.remove(jobId)
                return orchestrator.executeCloudJob(job, worker, onLogChunk = ::appendLog)
            }
            if (!runCatching { workerReachability(worker) }.getOrDefault(false)) {
                appendLog("[CloudRunner] Worker endpoint is not reachable: ${worker.endpointUrl}")
                return finishNotConfigured(
                    job, jobId, logs, startTime,
                    "No reachable cloud worker endpoint is configured " +
                        "(tried ${worker.name} at ${worker.endpointUrl}). " +
                        "Cloud execution is unavailable; configure a real worker API first."
                )
            }
            // The endpoint answers, but this build has no job-dispatch protocol
            // (authenticated API, log streaming, artifact download/verification).
            // Claiming success here would be a false success, so report honestly.
            appendLog("[CloudRunner] Worker ${worker.name} is reachable, but no job-dispatch protocol is implemented in this build.")
            return finishNotConfigured(
                job, jobId, logs, startTime,
                "Cloud worker ${worker.name} (${worker.endpointUrl}) is reachable, but this build " +
                    "implements no remote job dispatch / log streaming / artifact verification protocol. " +
                    "Cloud execution is not yet available."
            )
        }

        // Local execution: run the real command, report the real exit code.
        appendLog("[LocalRunner] Executing task locally: ${job.title}")
        val command = job.localCommand.trim()
        if (command.isEmpty()) {
            appendLog("[LocalRunner] No local command was provided for this job.")
            return finishNotConfigured(
                job, jobId, logs, startTime,
                "Local execution requested but the job carries no command to run. Nothing was executed."
            )
        }
        appendLog("[LocalRunner] Running: $command")
        val result = runCatching { localCommandRunner(command) }
            .getOrElse { e -> OperationResult.failure("Local command runner crashed: ${e.message}") }
        result.logs.forEach(::appendLog)

        val endTime = System.currentTimeMillis()
        runningJobs.remove(jobId)
        return when (result.status) {
            OperationStatus.SUCCESS -> job.copy(
                state = HybridJobState.COMPLETED,
                outputLogs = logs,
                exitCode = result.exitCode,
                finishedAt = endTime,
                durationMs = endTime - startTime,
                estimatedCostUsd = 0.0,
            )
            OperationStatus.CANCELLED -> job.copy(
                state = HybridJobState.CANCELLED,
                outputLogs = logs,
                exitCode = result.exitCode,
                finishedAt = endTime,
                durationMs = endTime - startTime,
                errorMessage = result.error,
            )
            OperationStatus.NOT_CONFIGURED -> finishNotConfigured(
                job, jobId, logs, startTime, result.error ?: "Local command runner is not configured."
            )
            else -> job.copy(
                state = HybridJobState.FAILED,
                outputLogs = logs,
                exitCode = result.exitCode,
                finishedAt = endTime,
                durationMs = endTime - startTime,
                errorMessage = result.error ?: "Local command failed.",
            )
        }
    }

    private fun finishNotConfigured(
        job: HybridExecutionJob,
        jobId: String,
        logs: List<String>,
        startTime: Long,
        message: String,
    ): HybridExecutionJob {
        val endTime = System.currentTimeMillis()
        runningJobs.remove(jobId)
        return job.copy(
            state = HybridJobState.NOT_CONFIGURED,
            outputLogs = logs,
            finishedAt = endTime,
            durationMs = endTime - startTime,
            errorMessage = message,
        )
    }

    fun cancel(jobId: String) {
        runningJobs[jobId] = false
        cloudOrchestrator?.cancel(jobId)
    }

    fun isRunning(jobId: String): Boolean = runningJobs[jobId] ?: false
}

/**
 * Real reachability probe: HTTP GET against the worker's /health endpoint with a
 * short timeout. Returns false on any failure. Fictional or dead endpoints
 * (e.g. the shipped pocketdev.io presets) correctly report unreachable.
 */
private fun defaultWorkerReachability(worker: CloudWorkerSpec): Boolean {
    return try {
        val url = URL(worker.endpointUrl.trimEnd('/') + "/health")
        (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "GET"
            instanceFollowRedirects = true
            connect()
            val code = responseCode
            disconnect()
            code in 200..399
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Runs a shell command locally and reports its REAL exit code and output.
 * Never fabricates success.
 */
private fun defaultLocalCommandRunner(command: String): OperationResult {
    val startedAt = System.currentTimeMillis()
    return try {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        val logs = output.lines().takeLast(200)
        if (exitCode == 0) {
            OperationResult.success(
                exitCode = exitCode,
                logs = logs,
                verificationEvidence = "local command exit=0",
                startedAt = startedAt,
            )
        } else {
            OperationResult.failure(
                error = "Local command failed with exit code $exitCode.",
                exitCode = exitCode,
                logs = logs,
                startedAt = startedAt,
            )
        }
    } catch (e: Exception) {
        OperationResult.failure(
            error = "Local command execution crashed: ${e.message}",
            startedAt = startedAt,
        )
    }
}
