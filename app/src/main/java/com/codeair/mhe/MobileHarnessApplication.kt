package com.codeair.mhe

import android.app.Application

import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide bootstrap. Installed before activities/services so fatal Java/Kotlin
 * startup exceptions are persisted before Android terminates the process.
 *
 * Firebase Crashlytics is enabled here so every uncaught exception is uploaded
 * automatically — no manual log collection needed.
 */
class MobileHarnessApplication : Application() {
    companion object {
        val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        super.onCreate()
        AppCrashLogger.initialize(this)
        // Crashlytics: auto-capture all uncaught exceptions to Firebase console.
        runCatching {
            FirebaseApp.initializeApp(this)
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = true
        }
        AppCrashLogger.log("Application.onCreate completed")
    }
}
