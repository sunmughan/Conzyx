package com.codeair.mhe.operator.execution

import android.content.Context
import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.memory.ActionEffect
import com.codeair.mhe.memory.ApprovalRecord
import com.codeair.mhe.memory.ExecutionRun
import com.codeair.mhe.memory.MemoryDatabase
import com.codeair.mhe.memory.ObservationRef
import com.codeair.mhe.memory.TaskRecord
import com.codeair.mhe.operator.OperatorAccessibilityService
import com.codeair.mhe.operator.guard.StaleTargetPolicy
import com.codeair.mhe.operator.observation.ObservationCoordinator
import com.codeair.mhe.operator.observation.UiNode
import com.codeair.mhe.operator.observation.UiObservation
import com.codeair.mhe.operator.registry.InstalledAppRegistry
import com.codeair.mhe.operator.registry.ResolvedTarget
import com.codeair.mhe.operator.registry.TargetAppResolver
import com.codeair.mhe.operator.ui.OperatorTimelineEvent
import com.codeair.mhe.operator.ui.OperatorViewModel
import com.codeair.mhe.operator.ui.approvalBindingId
import com.codeair.mhe.security.PermissionLevel
import com.codeair.mhe.security.SafetyGateway
import com.codeair.mhe.security.SafetyOperation
import com.codeair.mhe.security.SafetyVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class OperatorOrchestrator(
    private val appContext: Context,
    private val gateway: SafetyGateway,
    private val db: MemoryDatabase,
    private val viewModel: OperatorViewModel
) {
    companion object {
        const val APPROVAL_EXPIRY_MILLIS = 5L * 60L * 1000L

        const val STATUS_CREATED = "created"
        const val STATUS_VALIDATED = "validated"
        const val STATUS_APP_RESOLVED = "app_resolved"
        const val STATUS_OBSERVED = "observed"
        const val STATUS_APPROVAL_PENDING = "approval_pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_DISPATCHED = "dispatched"
        const val STATUS_VERIFIED = "verified"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED_VALIDATION = "failed_validation"
        const val STATUS_FAILED_NO_APP = "failed_no_app"
        const val STATUS_FAILED_AMBIGUOUS = "failed_ambiguous"
        const val STATUS_FAILED_NO_OBSERVATION = "failed_no_observation"
        const val STATUS_FAILED_LAUNCH = "failed_launch"
        const val STATUS_FAILED_DENIED = "failed_denied"
        const val STATUS_FAILED_APPROVAL_DENIED = "failed_approval_denied"
        const val STATUS_FAILED_APPROVAL_INVALID = "failed_approval_invalid"
        const val STATUS_FAILED_STALE_TARGET = "failed_stale_target"
        const val STATUS_FAILED_WRONG_PACKAGE = "failed_wrong_package"
        const val STATUS_FAILED_DISPATCH = "failed_dispatch"
        const val STATUS_FAILED_POSTCONDITION = "failed_postcondition"
        const val STATUS_CANCELLED = "cancelled"
    }

    private val observation = ObservationCoordinator.get(appContext)
    private val registry = InstalledAppRegistry(appContext)
    private val resolver = TargetAppResolver(registry)

    private val taskExecutor: OperatorTaskExecutor by lazy {
        val service = OperatorAccessibilityService.instance
            ?: throw IllegalStateException("accessibility unavailable")
        OperatorTaskExecutor(
            gateway = gateway,
            deviceControl = service,
            observation = observation,
            executors = listOf(
                TapExecutor(),
                LongPressExecutor(),
                ScrollExecutor(),
                BackExecutor(),
                TypeTextExecutor(),
                LaunchAppExecutor(),
                WaitExecutor()
            )
        )
    }

    private val cancelledTasks = ConcurrentHashMap<String, AtomicBoolean>()

    @Volatile
    private var lastVerification: Pair<Boolean, String>? = null

    fun cancelTask(taskId: String) {
        cancelledTasks[taskId]?.set(true)
    }

    suspend fun runTask(taskId: String, goalText: String): String = withContext(Dispatchers.IO) {
        val cancelFlag = cancelledTasks.computeIfAbsent(taskId) { AtomicBoolean(false) }
        try {
            reconcileUnresolved()
            executeRun(taskId, goalText, cancelFlag)
        } finally {
            cancelledTasks.remove(taskId)
        }
    }

    suspend fun reconcileUnresolved(): Int = withContext(Dispatchers.IO) {
        val unresolved = db.actionEffectDao().listUnreconciled()
        var count = 0
        for (effect in unresolved) {
            db.actionEffectDao().upsert(effect.copy(outcomeStatus = "abandoned"))
            count++
        }
        count
    }

    suspend fun observeForWorkflow(): OperationResult = withContext(Dispatchers.IO) {
        val obs = observation.capture()
        db.observationRefDao().upsert(
            ObservationRef(
                observationId = obs.observationId,
                packageName = obs.packageName,
                generation = obs.generation,
                timestampMillis = obs.capturedAtMillis
            )
        )
        if (!obs.fresh) {
            OperationResult.failure("observation unavailable: accessibility service not connected")
        } else {
            OperationResult.success(
                verificationEvidence = "observationId=${obs.observationId} package=${obs.packageName} generation=${obs.generation}"
            )
        }
    }

    suspend fun actForWorkflow(goalHint: String): OperationResult = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        val status = runTask(taskId, goalHint)
        if (status == STATUS_COMPLETED) {
            OperationResult.success(verificationEvidence = "taskId=$taskId status=$status")
        } else {
            OperationResult.failure("operator act failed: $status (taskId=$taskId)")
        }
    }

    suspend fun verifyForWorkflow(): OperationResult = withContext(Dispatchers.IO) {
        val (passed, detail) = lastVerification
            ?: return@withContext OperationResult.failure("no verified operator action recorded yet")
        if (passed) {
            OperationResult.success(verificationEvidence = detail)
        } else {
            OperationResult.failure("last operator action failed verification: $detail")
        }
    }

    private sealed interface StepOutcome {
        data class Dispatched(
            val result: OperatorActionResult,
            val beforeObs: UiObservation,
            val effectId: String,
            val approvalId: String?
        ) : StepOutcome

        data class Failed(val status: String) : StepOutcome
    }

    private suspend fun executeRun(taskId: String, goalText: String, cancelled: AtomicBoolean): String {
        val runId = UUID.randomUUID().toString()

        suspend fun fail(status: String, detail: String): String {
            persistTask(taskId, goalText, status, null, runId)
            persistRun(runId, taskId, status)
            pushEvent("Run $status", detail, false)
            return status
        }

        if (cancelled.get()) return fail(STATUS_CANCELLED, "cancelled before start")
        if (goalText.isBlank()) return fail(STATUS_FAILED_VALIDATION, "goal is blank")
        persistTask(taskId, goalText, STATUS_VALIDATED, null, runId)
        persistRun(runId, taskId, STATUS_VALIDATED)
        pushEvent("Goal validated", goalText.take(120), true)

        registry.refresh()
        val candidates = resolver.resolveAll(goalText)
        if (candidates.isEmpty()) {
            return fail(STATUS_FAILED_NO_APP, "no installed app matches goal")
        }
        val top = candidates.first()
        if (candidates.size > 1 && top.confidence < 0.95f) {
            val names = candidates.take(3).joinToString { it.appLabel }
            return fail(STATUS_FAILED_AMBIGUOUS, "ambiguous app: $names")
        }
        val target = top
        persistTask(taskId, goalText, STATUS_APP_RESOLVED, target.packageName, runId)
        pushEvent("App resolved", "${target.appLabel} (${target.packageName})", true)

        var currentObs = observation.capture()
        persistObservation(currentObs)
        if (!currentObs.fresh) {
            return fail(STATUS_FAILED_NO_OBSERVATION, "accessibility observation unavailable")
        }
        persistTask(taskId, goalText, STATUS_OBSERVED, target.packageName, runId)

        if (currentObs.packageName != target.packageName) {
            val launchOp = SafetyOperation.UiLaunchApp(target.packageName, target.appLabel)
            when (val launchOut = dispatchWithApproval(taskId, runId, "launch", launchOp, target, cancelled)) {
                is StepOutcome.Failed -> return launchOut.status
                is StepOutcome.Dispatched -> {
                    if (!launchOut.result.success) {
                        return fail(STATUS_FAILED_LAUNCH, launchOut.result.message)
                    }
                }
            }
            currentObs = observation.capture()
            persistObservation(currentObs)
            if (currentObs.packageName != target.packageName) {
                return fail(
                    STATUS_FAILED_LAUNCH,
                    "expected ${target.packageName}, foreground is ${currentObs.packageName}"
                )
            }
            pushEvent("App launched", target.appLabel, true)
        }

        val tapObs = observation.capture()
        persistObservation(tapObs)
        if (!tapObs.fresh) {
            return fail(STATUS_FAILED_NO_OBSERVATION, "observation went stale before tap")
        }
        val tapNode = findFirstClickable(tapObs.root)
            ?: return fail(STATUS_FAILED_NO_APP, "no tappable node in ${target.appLabel}")
        val tapLabel = tapNode.text?.take(40) ?: tapNode.contentDescription?.take(40) ?: tapNode.nodeId
        val tapOp = SafetyOperation.UiTap(tapNode.nodeId, tapLabel)
        pushEvent("Tap target", tapLabel, true)

        val dispatchOut = when (val out = dispatchWithApproval(taskId, runId, "tap", tapOp, target, cancelled)) {
            is StepOutcome.Failed -> return out.status
            is StepOutcome.Dispatched -> out
        }
        if (!dispatchOut.result.success) {
            db.actionEffectDao().upsert(
                ActionEffect(
                    effectId = dispatchOut.effectId,
                    runId = runId,
                    stepId = "tap",
                    actionType = "UiTap",
                    dispatchStatus = "failed",
                    outcomeStatus = "failed",
                    approvalId = dispatchOut.approvalId,
                    detailJson = dispatchOut.result.message.take(500)
                )
            )
            return fail(STATUS_FAILED_DISPATCH, dispatchOut.result.message)
        }

        val afterObs = observation.capture()
        persistObservation(afterObs)
        val passed = verifyTapPostcondition(dispatchOut.beforeObs, afterObs, tapNode.nodeId)
        lastVerification = passed to "tap ${tapLabel.take(40)} verified=$passed"
        db.actionEffectDao().upsert(
            ActionEffect(
                effectId = dispatchOut.effectId,
                runId = runId,
                stepId = "tap",
                actionType = "UiTap",
                dispatchStatus = "dispatched",
                outcomeStatus = if (passed) STATUS_VERIFIED else "failed",
                approvalId = dispatchOut.approvalId,
                detailJson = "postcondition=$passed".take(500)
            )
        )
        if (!passed) {
            return fail(STATUS_FAILED_POSTCONDITION, "tap produced no verifiable UI change")
        }
        pushEvent("Tap verified", tapLabel, true)

        persistTask(taskId, goalText, STATUS_COMPLETED, target.packageName, runId)
        persistRun(runId, taskId, STATUS_COMPLETED)
        pushEvent("Run complete", goalText.take(120), true)
        return STATUS_COMPLETED
    }

    private suspend fun dispatchWithApproval(
        taskId: String,
        runId: String,
        stepId: String,
        operation: SafetyOperation,
        target: ResolvedTarget,
        cancelled: AtomicBoolean
    ): StepOutcome {
        suspend fun terminal(status: String, detail: String): StepOutcome.Failed {
            persistTask(taskId, "", status, target.packageName, runId)
            persistRun(runId, taskId, status)
            pushEvent("Run $status", detail, false)
            return StepOutcome.Failed(status)
        }

        if (cancelled.get()) return terminal(STATUS_CANCELLED, "cancelled before dispatch")

        val preDecision = gateway.authorize(operation, sessionId = runId)
        when (preDecision.verdict) {
            SafetyVerdict.DENY -> {
                db.actionEffectDao().upsert(
                    ActionEffect(
                        effectId = UUID.randomUUID().toString(),
                        runId = runId,
                        stepId = stepId,
                        actionType = operation.javaClass.simpleName,
                        dispatchStatus = "denied",
                        outcomeStatus = "failed",
                        detailJson = preDecision.reason.take(500)
                    )
                )
                return terminal(STATUS_FAILED_DENIED, preDecision.reason)
            }
            SafetyVerdict.PROMPT -> Unit
            SafetyVerdict.ALLOW -> Unit
        }

        var approvalId: String? = null
        if (preDecision.verdict == SafetyVerdict.PROMPT) {
            val prompt = preDecision.prompt
                ?: return terminal(STATUS_FAILED_DENIED, "prompt required but no prompt supplied")
            persistTask(taskId, "", STATUS_APPROVAL_PENDING, target.packageName, runId)
            val bindingId = approvalBindingId(runId, stepId, operation)
            val described = redactedDescribe(operation)
            val canonical = canonicalDescribe(operation, target.packageName)
            val now = System.currentTimeMillis()
            db.approvalRecordDao().upsert(
                ApprovalRecord(
                    approvalId = bindingId,
                    runId = runId,
                    stepId = stepId,
                    operationDescribe = described,
                    parametersHash = sha256Hex(canonical),
                    targetPackage = target.packageName,
                    expiresAtMillis = now + APPROVAL_EXPIRY_MILLIS,
                    consumed = false,
                    createdAtMillis = now
                )
            )
            approvalId = bindingId
            viewModel.setPendingApproval(prompt, bindingId)
            pushEvent("Approval requested", described.take(120), true)
            val granted = withTimeoutOrNull(APPROVAL_EXPIRY_MILLIS + 60_000L) {
                viewModel.awaitApproval(bindingId)
            } ?: false
            viewModel.clearPendingApproval()
            if (cancelled.get()) return terminal(STATUS_CANCELLED, "cancelled during approval")
            if (!granted) return terminal(STATUS_FAILED_APPROVAL_DENIED, "user denied approval")
            val record = db.approvalRecordDao().getById(bindingId)
            val hashNow = sha256Hex(canonicalDescribe(operation, target.packageName))
            if (record == null || record.consumed || System.currentTimeMillis() >= record.expiresAtMillis || record.parametersHash != hashNow) {
                return terminal(STATUS_FAILED_APPROVAL_INVALID, "approval invalid, expired, or replayed")
            }
            db.approvalRecordDao().markConsumed(bindingId)
            gateway.recordGrant(operation, PermissionLevel.ONCE, sessionId = runId)
            persistTask(taskId, "", STATUS_APPROVED, target.packageName, runId)
            pushEvent("Approval granted", described.take(120), true)
        }

        if (cancelled.get()) return terminal(STATUS_CANCELLED, "cancelled before dispatch")

        val effectId = UUID.randomUUID().toString()
        db.actionEffectDao().upsert(
            ActionEffect(
                effectId = effectId,
                runId = runId,
                stepId = stepId,
                actionType = operation.javaClass.simpleName,
                dispatchStatus = "prepared",
                outcomeStatus = "unknown",
                approvalId = approvalId
            )
        )

        val beforeObs = observation.capture()
        var dispatchOp = operation
        val tapOp = operation as? SafetyOperation.UiTap
        if (tapOp != null) {
            val path = tapOp.targetId.substringAfter(":p", missingDelimiterValue = "")
            val freshNode = findNodeByPath(beforeObs.root, path)
            if (path.isEmpty() || freshNode == null || !freshNode.clickable) {
                db.actionEffectDao().upsert(
                    ActionEffect(
                        effectId = effectId,
                        runId = runId,
                        stepId = stepId,
                        actionType = "UiTap",
                        dispatchStatus = "prepared",
                        outcomeStatus = "failed",
                        approvalId = approvalId,
                        detailJson = "stale target"
                    )
                )
                return terminal(STATUS_FAILED_STALE_TARGET, "target node is stale")
            }
            val freshId = "g${beforeObs.generation}:p$path"
            if (StaleTargetPolicy.isStale(freshId, beforeObs.generation)) {
                return terminal(STATUS_FAILED_STALE_TARGET, "target id incoherent")
            }
            val rebuilt = tapOp.copy(targetId = freshId)
            if (approvalId != null) {
                val stored = db.approvalRecordDao().getById(approvalId)
                val canonicalNow = sha256Hex(canonicalDescribe(rebuilt, target.packageName))
                if (stored == null || stored.parametersHash != canonicalNow) {
                    return terminal(STATUS_FAILED_STALE_TARGET, "target changed after approval")
                }
            }
            dispatchOp = rebuilt
        }
        if (beforeObs.packageName != target.packageName) {
            return terminal(
                STATUS_FAILED_WRONG_PACKAGE,
                "foreground ${beforeObs.packageName} != target ${target.packageName}"
            )
        }

        val result = runCatching {
            taskExecutor.executeStep(runId, stepId, dispatchOp)
        }.getOrElse { throwable ->
            return terminal(STATUS_FAILED_DISPATCH, throwable.message ?: "dispatch threw")
        }
        db.actionEffectDao().upsert(
            ActionEffect(
                effectId = effectId,
                runId = runId,
                stepId = stepId,
                actionType = operation.javaClass.simpleName,
                dispatchStatus = if (result.success) "dispatched" else "failed",
                outcomeStatus = "unknown",
                approvalId = approvalId,
                detailJson = result.message.take(500)
            )
        )
        persistTask(taskId, "", STATUS_DISPATCHED, target.packageName, runId)
        return StepOutcome.Dispatched(result, beforeObs, effectId, approvalId)
    }

    private suspend fun persistTask(
        taskId: String,
        title: String,
        status: String,
        targetApp: String?,
        runId: String?
    ) {
        val existing = db.taskRecordDao().getById(taskId)
        db.taskRecordDao().upsert(
            TaskRecord(
                taskId = taskId,
                projectId = existing?.projectId,
                conversationId = existing?.conversationId,
                targetApp = targetApp ?: existing?.targetApp ?: "",
                title = title.ifBlank { existing?.title ?: "" }.take(120),
                status = status,
                currentRunId = runId ?: existing?.currentRunId,
                createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    private suspend fun persistRun(runId: String, taskId: String, state: String) {
        val existing = db.executionRunDao().getById(runId)
        db.executionRunDao().upsert(
            ExecutionRun(
                runId = runId,
                taskId = taskId,
                state = state,
                checkpointJson = existing?.checkpointJson ?: "",
                attempt = existing?.attempt ?: 1,
                startedAtMillis = existing?.startedAtMillis ?: System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis(),
                finishedAtMillis = if (state == STATUS_COMPLETED || state.startsWith("failed") || state == STATUS_CANCELLED) {
                    System.currentTimeMillis()
                } else {
                    existing?.finishedAtMillis
                },
                createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis()
            )
        )
    }

    private suspend fun persistObservation(obs: UiObservation) {
        db.observationRefDao().upsert(
            ObservationRef(
                observationId = obs.observationId,
                packageName = obs.packageName,
                generation = obs.generation,
                timestampMillis = obs.capturedAtMillis
            )
        )
    }

    private fun pushEvent(title: String, detail: String, success: Boolean) {
        viewModel.pushTimelineEvent(
            OperatorTimelineEvent(
                id = UUID.randomUUID().toString(),
                title = title,
                detail = detail,
                timestampMillis = System.currentTimeMillis(),
                success = success
            )
        )
    }

    private fun findFirstClickable(root: UiNode?): UiNode? {
        if (root == null) return null
        if (root.clickable) return root
        for (child in root.children) {
            val found = findFirstClickable(child)
            if (found != null) return found
        }
        return null
    }

    private fun redactedDescribe(operation: SafetyOperation): String {
        return when (operation) {
            is SafetyOperation.UiTypeText ->
                "UI type text into ${operation.targetId.ifBlank { "focused field" }} [text redacted]"
            else -> operation.describe()
        }
    }

    private fun canonicalDescribe(operation: SafetyOperation, targetPackage: String): String {
        return when (operation) {
            is SafetyOperation.UiTap -> {
                val path = operation.targetId.substringAfter(":p", missingDelimiterValue = "")
                "UiTap package=$targetPackage path=$path label=${operation.targetLabel}"
            }
            is SafetyOperation.UiLaunchApp ->
                "UiLaunchApp package=${operation.packageName}"
            is SafetyOperation.UiTypeText ->
                "UiTypeText package=$targetPackage target=${operation.targetId.substringAfter(":p", missingDelimiterValue = "")} [text redacted]"
            else -> redactedDescribe(operation)
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
