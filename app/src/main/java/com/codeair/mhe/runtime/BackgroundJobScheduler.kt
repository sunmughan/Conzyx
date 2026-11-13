package com.codeair.mhe.runtime

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Phase 5: enqueues the app's periodic background jobs.
 * KEEP policy: a second enqueue (app update, re-install) never resets
 * the 15-minute watchdog clock.
 */
object BackgroundJobScheduler {
    const val WATCHDOG_WORK_NAME = "session-watchdog"

    fun enqueueAll(context: Context) {
        val watchdog = PeriodicWorkRequestBuilder<SessionWatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            watchdog,
        )
    }
}
