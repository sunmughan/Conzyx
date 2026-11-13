package com.codeair.mhe.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.codeair.mhe.AppCrashLogger
import com.codeair.mhe.session.AgentSessionManager
import org.json.JSONArray
import java.io.File

/**
 * Phase 5: background watchdog for agent sessions.
 *
 * Runs every 15 minutes via WorkManager (the only periodic background
 * mechanism that survives app kill and Doze). It marks sessions that died
 * with the process as INTERRUPTED and reminds the user when interrupted
 * sessions or queued prompts are waiting to resume. It never starts an
 * agent run itself: resuming stays a foreground, user-confirmed action.
 * A failure in one cycle is logged and skipped; the next 15-minute tick
 * tries again instead of retrying in a loop.
 */
class SessionWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val interrupted = AgentSessionManager(applicationContext).detectInterruptedSessions().size
            val queued = countQueuedPrompts(File(applicationContext.filesDir, "chats"))
            if (shouldRemind(interrupted, queued)) {
                NotificationCoordinator.postResumeReminder(applicationContext, interrupted, queued)
            }
            Result.success()
        } catch (e: Exception) {
            AppCrashLogger.checkpoint("SessionWatchdogFailed", e.message ?: e.toString())
            Result.success()
        }
    }
}

/** True when interrupted sessions or queued prompts are waiting to resume. */
fun shouldRemind(interruptedCount: Int, queuedPromptCount: Int): Boolean =
    interruptedCount > 0 || queuedPromptCount > 0

/**
 * Counts queued prompts under [chatsDir] (filesDir/chats/<projectId>/<chatId>.queued.json).
 * Reads the arrays, not the files: a drained queue still leaves an empty
 * `[]` file behind, and mid-write `.tmp` files are ignored.
 */
fun countQueuedPrompts(chatsDir: File): Int {
    if (!chatsDir.isDirectory) return 0
    var total = 0
    chatsDir.walkTopDown().forEach { file ->
        if (file.isFile && file.name.endsWith(".queued.json")) {
            total += runCatching { JSONArray(file.readText()).length() }.getOrDefault(0)
        }
    }
    return total
}
