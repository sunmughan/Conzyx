package com.codeair.mhe

import android.app.Application

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.codeair.mhe.runtime.KeepAliveService
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
        runCatching {
            FirebaseApp.initializeApp(this)
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled =
                AppCrashLogger.isCrashReportingEnabled
        }
        // (FCM needs no local token persistence: the PushTokenStore that
        // used to save the registration token here was removed — nothing
        // ever read it and no server ever received it. QA Medium.)
        AppCrashLogger.log("Application.onCreate completed")
        // Keep-alive: when the app goes to the background, start a lightweight
        // foreground service so aggressive OEM task killers do not freeze the
        // process. When the app returns to the foreground the service stops,
        // so no persistent notification shows while the app is in use.
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) {
                        runCatching { KeepAliveService.start(this@MobileHarnessApplication) }
                    }

                    override fun onStart(owner: LifecycleOwner) {
                        runCatching { KeepAliveService.stop(this@MobileHarnessApplication) }
                    }
                }
            )
        }
    }
}
