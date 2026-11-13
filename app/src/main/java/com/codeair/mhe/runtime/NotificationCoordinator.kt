package com.codeair.mhe.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.codeair.mhe.MainActivity
import com.codeair.mhe.R
import com.codeair.mhe.data.AppPreferences
import com.codeair.mhe.mcp.McpApprovalReceiver
import java.util.Calendar

/**
 * Single Android notification policy for Conzyx.
 *
 * Runtime events originate locally from the Android/Linux execution stack, so
 * system notifications do not depend on Firebase or a network connection.
 * FCM can be layered on later for genuinely remote events.
 */
object NotificationCoordinator {
    const val RUNNING_CHANNEL_ID = "runtime"
    const val RESULT_CHANNEL_ID = "task-results"
    const val REMOTE_CHANNEL_ID = "remote-events"
    const val RUNNING_NOTIFICATION_ID = 41
    const val RESULT_NOTIFICATION_ID = 42
    const val RESULT_GROUP_KEY = "conzyx-task-results"
    const val RESULT_GROUP_SUMMARY_ID = 43
    const val REMOTE_GROUP_KEY = "conzyx-remote-events"
    const val REMOTE_GROUP_SUMMARY_ID = 44
    const val RESUME_REMINDER_NOTIFICATION_ID = 45
    private const val REMOTE_NOTIFICATION_BASE_ID = 10_000
    private const val APPROVAL_NOTIFICATION_BASE_ID = 20_000

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                RUNNING_CHANNEL_ID,
                "Running coding tasks",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress while Conzyx is working in the background"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                "Task results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifies you when a coding task finishes or needs attention"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                REMOTE_CHANNEL_ID,
                "Conzyx updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Remote updates for Conzyx projects and agent sessions"
            },
        )
    }

    fun running(
        context: Context,
        title: String,
        detail: String,
        canStop: Boolean,
        sessionId: String? = null,
    ): Notification {
        val builder = NotificationCompat.Builder(context, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openAppIntent(context))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (canStop) {
            val stopIntent = PendingIntent.getService(
                context,
                sessionId?.hashCode() ?: 2,
                Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_STOP)
                    .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, sessionId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop task", stopIntent)
        }
        return builder.build()
    }

    fun result(
        context: Context,
        title: String,
        detail: String,
        failed: Boolean,
        silent: Boolean = false,
    ): Notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(detail)
        .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
        .setContentIntent(openAppIntent(context))
        .setAutoCancel(true)
        .setGroup(RESULT_GROUP_KEY)
        .setSilent(silent)
        .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
        .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
        .build()

    fun postRemote(
        context: Context,
        title: String,
        detail: String,
        messageId: String?,
        route: String?,
    ) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val silent = isQuietNow(context)
        val notificationId = REMOTE_NOTIFICATION_BASE_ID + stableNotificationOffset(messageId, title, detail)
        manager.notify(
            notificationId,
            NotificationCompat.Builder(context, REMOTE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(openAppIntent(context, route))
                .setAutoCancel(true)
                .setGroup(REMOTE_GROUP_KEY)
                .setSilent(silent)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
        manager.notify(REMOTE_GROUP_SUMMARY_ID, remoteSummary(context))
    }

    fun postResult(context: Context, title: String, detail: String, failed: Boolean) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val quiet = isQuietNow(context)
        val silent = NotificationPolicy.deliveryFor(failed, quiet) == NotificationPolicy.Delivery.SILENT
        manager.notify(RESULT_NOTIFICATION_ID, result(context, title, detail, failed, silent))
        manager.notify(RESULT_GROUP_SUMMARY_ID, resultSummary(context))
    }

    /**
     * Phase 5: an MCP tool that needs the user's approval posts an
     * actionable notification. Approve grants a durable one-time approval
     * via McpApprovalReceiver; Deny dismisses only. Approvals always
     * alert, even during quiet hours, because the run is waiting on them.
     */
    fun postApprovalRequired(
        context: Context,
        serverId: String,
        toolName: String,
        projectId: String?,
        title: String,
        detail: String,
    ) {
        ensureChannels(context)
        val key = "${projectId.orEmpty()}|$serverId::$toolName"
        val notificationId = APPROVAL_NOTIFICATION_BASE_ID + key.hashCode().and(0x0FFF)
        val approveIntent = Intent(context, McpApprovalReceiver::class.java).apply {
            action = McpApprovalReceiver.ACTION_APPROVE
            putExtra(McpApprovalReceiver.EXTRA_SERVER_ID, serverId)
            putExtra(McpApprovalReceiver.EXTRA_TOOL_NAME, toolName)
            projectId?.let { putExtra(McpApprovalReceiver.EXTRA_PROJECT_ID, it) }
            putExtra(McpApprovalReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val denyIntent = Intent(context, McpApprovalReceiver::class.java).apply {
            action = McpApprovalReceiver.ACTION_DENY
            putExtra(McpApprovalReceiver.EXTRA_SERVER_ID, serverId)
            putExtra(McpApprovalReceiver.EXTRA_TOOL_NAME, toolName)
            projectId?.let { putExtra(McpApprovalReceiver.EXTRA_PROJECT_ID, it) }
            putExtra(McpApprovalReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val approve = PendingIntent.getBroadcast(
            context,
            notificationId,
            approveIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deny = PendingIntent.getBroadcast(
            context,
            -notificationId,
            denyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(NotificationManager::class.java).notify(
            notificationId,
            NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .setGroup(RESULT_GROUP_KEY)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(0, "Approve", approve)
                .addAction(0, "Deny", deny)
                .build(),
        )
    }

    private fun resultSummary(context: Context): Notification =
        NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Conzyx task results")
            .setContentText("Task updates from Conzyx")
            .setContentIntent(openAppIntent(context))
            .setGroup(RESULT_GROUP_KEY)
            .setGroupSummary(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

    private fun remoteSummary(context: Context): Notification =
        NotificationCompat.Builder(context, REMOTE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Conzyx updates")
            .setContentText("Remote updates for Conzyx")
            .setContentIntent(openAppIntent(context))
            .setGroup(REMOTE_GROUP_KEY)
            .setGroupSummary(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

    private fun isQuietNow(context: Context): Boolean {
        val prefs = AppPreferences(context)
        if (!prefs.notificationQuietHoursEnabled) return false
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return NotificationPolicy.isQuietHour(hour)
    }

    /**
     * Phase 5: the session watchdog's resume reminder. A distinct id from
     * the single result slot (42), so it never overwrites the latest task
     * result. Silent during quiet hours like any other non-failure result.
     */
    fun postResumeReminder(context: Context, interruptedCount: Int, queuedCount: Int) {
        ensureChannels(context)
        val silent = isQuietNow(context)
        val detail = buildString {
            if (interruptedCount > 0) {
                append("$interruptedCount interrupted session${if (interruptedCount == 1) "" else "s"}")
            }
            if (queuedCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("$queuedCount queued prompt${if (queuedCount == 1) "" else "s"}")
            }
            append(" waiting to resume")
        }
        context.getSystemService(NotificationManager::class.java).notify(
            RESUME_REMINDER_NOTIFICATION_ID,
            NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Sessions waiting to resume")
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .setGroup(RESULT_GROUP_KEY)
                .setSilent(silent)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    private fun openAppIntent(context: Context, route: String? = null): PendingIntent = PendingIntent.getActivity(
        context,
        route?.hashCode() ?: 1,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_ROUTE, it) }
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stableNotificationOffset(messageId: String?, title: String, detail: String): Int =
        (messageId ?: "$title\u0000$detail").hashCode().and(0x0FFF)

    private const val EXTRA_ROUTE = "notification_route"
}