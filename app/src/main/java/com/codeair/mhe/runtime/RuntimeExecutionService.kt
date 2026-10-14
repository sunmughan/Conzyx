package com.codeair.mhe.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

internal object RuntimeTaskController {
    @Volatile var stopAction: (() -> Unit)? = null

    fun requestStop() {
        stopAction?.invoke()
    }
}

class RuntimeExecutionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var projectName: String = "your project"
    private var notificationTitle: String = "Conzyx is working"
    private var agentName: String = "Coding agent"
    private var canStop: Boolean = true
    private var taskRunning: Boolean = false

    override fun onCreate() {
        super.onCreate()
        NotificationCoordinator.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_PROJECT_NAME)?.takeIf(String::isNotBlank)?.let { projectName = it }
        intent?.getStringExtra(EXTRA_AGENT_NAME)?.takeIf(String::isNotBlank)?.let { agentName = it }
        intent?.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank)?.let { notificationTitle = it }
        if (intent?.hasExtra(EXTRA_CAN_STOP) == true) canStop = intent.getBooleanExtra(EXTRA_CAN_STOP, true)
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                RuntimeTaskController.requestStop()
                getSystemService(android.app.NotificationManager::class.java).notify(
                    NotificationCoordinator.RUNNING_NOTIFICATION_ID,
                    NotificationCoordinator.running(this, notificationTitle, "Stopping safely…", false),
                )
            }
            ACTION_PROGRESS -> {
                // Live step updates only matter while a task is actually running.
                if (!taskRunning) return START_STICKY
                val detail = intent?.getStringExtra(EXTRA_DETAIL)?.takeIf { it.isNotBlank() }
                    ?: "$agentName is working in $projectName"
                getSystemService(android.app.NotificationManager::class.java).notify(
                    NotificationCoordinator.RUNNING_NOTIFICATION_ID,
                    NotificationCoordinator.running(this, notificationTitle, detail, canStop),
                )
            }
            ACTION_COMPLETE -> finishTask(
                title = "Task completed",
                detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "$agentName finished working in $projectName.",
                failed = false,
            )
            ACTION_FAILED -> finishTask(
                title = "Task needs attention",
                detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "$agentName could not finish the task.",
                failed = true,
            )
            ACTION_CANCELLED -> {
                taskRunning = false
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                taskRunning = true
                val startDetail = intent?.getStringExtra(EXTRA_DETAIL)?.takeIf { it.isNotBlank() }
                    ?: "$agentName is working in $projectName"
                startForeground(
                    NotificationCoordinator.RUNNING_NOTIFICATION_ID,
                    NotificationCoordinator.running(
                        context = this,
                        title = notificationTitle,
                        detail = startDetail,
                        canStop = canStop,
                    ),
                )
                acquireLocks()
            }
        }
        return START_STICKY
    }

    private fun finishTask(title: String, detail: String, failed: Boolean) {
        taskRunning = false
        releaseLocks()
        NotificationCoordinator.postResult(this, title, detail, failed)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.codeair.mhe:active-coding-task")
                .apply { acquire(MAX_WAKE_LOCK_MS) }
        }
        if (wifiLock?.isHeld != true) {
            runCatching {
                val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val wifiLockMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(
                    wifiLockMode,
                    "com.codeair.mhe:active-coding-wifi",
                )?.apply { acquire() }
                // WifiLock has no acquire(timeout): emulate the wake lock's
                // cap so a missed completion path can't hold high-perf wifi
                // forever. The delayed release is a no-op if the lock was
                // already released normally.
                wifiLockHandler.removeCallbacks(wifiLockTimeoutRelease)
                wifiLockHandler.postDelayed(wifiLockTimeoutRelease, MAX_WAKE_LOCK_MS)
            }
        }
    }

    private val wifiLockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val wifiLockTimeoutRelease = Runnable {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLockHandler.removeCallbacks(wifiLockTimeoutRelease)
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.codeair.mhe.START_RUNTIME"
        const val ACTION_STOP = "com.codeair.mhe.STOP_RUNTIME"
        const val ACTION_PROGRESS = "com.codeair.mhe.PROGRESS_RUNTIME"
        const val ACTION_COMPLETE = "com.codeair.mhe.COMPLETE_RUNTIME"
        const val ACTION_FAILED = "com.codeair.mhe.FAIL_RUNTIME"
        const val ACTION_CANCELLED = "com.codeair.mhe.CANCEL_RUNTIME"
        const val EXTRA_PROJECT_NAME = "project_name"
        const val EXTRA_AGENT_NAME = "agent_name"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CAN_STOP = "can_stop"

        private const val MAX_WAKE_LOCK_MS = 90 * 60 * 1_000L
    }
}
