package com.codeair.mhe

import android.app.Application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide bootstrap. Installed before activities/services so fatal Java/Kotlin
 * startup exceptions are persisted before Android terminates the process.
 */
class MobileHarnessApplication : Application() {
    companion object {
        val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        super.onCreate()
        AppCrashLogger.initialize(this)
        AppCrashLogger.log("Application.onCreate completed")
    }
}
