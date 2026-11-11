package com.codeair.mhe

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeair.mhe.ui.MainViewModel
import com.codeair.mhe.ui.PocketDevApp
import com.codeair.mhe.ui.theme.PocketTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppCrashLogger.log("MainActivity.onCreate entered")
        try {
            super.onCreate(savedInstanceState)
            AppCrashLogger.log("MainActivity.onCreate: super.onCreate completed")

            enableEdgeToEdge()
            AppCrashLogger.log("MainActivity.onCreate: edge-to-edge enabled")

            val notificationRoute = savedInstanceState?.getString(EXTRA_NOTIFICATION_ROUTE)
                ?: intent.getStringExtra(EXTRA_NOTIFICATION_ROUTE)
            AppCrashLogger.log("MainActivity.onCreate: notification route resolved=\${notificationRoute != null}")

            val vm = ViewModelProvider(this)[MainViewModel::class.java]
            AppCrashLogger.log("MainActivity.onCreate: MainViewModel obtained")

            vm.handleNotificationRoute(notificationRoute)
            AppCrashLogger.log("MainActivity.onCreate: notification route handled")

            // P2c: a share from another app stages into the active chat.
            vm.handleSharedIntent(intent)
            AppCrashLogger.log("MainActivity.onCreate: shared intent handled")

            setContent {
                val state by vm.state.collectAsStateWithLifecycle()
                PocketTheme(themeMode = state.themeMode) {
                    PocketDevApp(vm)
                }
            }
            AppCrashLogger.log("MainActivity.onCreate completed successfully")
        } catch (throwable: Throwable) {
            AppCrashLogger.logThrowable("MainActivity.onCreate FAILED", throwable)
            throw throwable
        }
    }

    override fun onNewIntent(intent: Intent) {
        AppCrashLogger.log("MainActivity.onNewIntent entered")
        try {
            super.onNewIntent(intent)
            setIntent(intent)
            intent.getStringExtra(EXTRA_NOTIFICATION_ROUTE)?.let { route ->
                val vm = ViewModelProvider(this)[MainViewModel::class.java]
                vm.handleNotificationRoute(route)
            }
            // P2c: a share arriving while the app is already running.
            ViewModelProvider(this)[MainViewModel::class.java].handleSharedIntent(intent)
            AppCrashLogger.log("MainActivity.onNewIntent completed")
        } catch (throwable: Throwable) {
            AppCrashLogger.logThrowable("MainActivity.onNewIntent FAILED", throwable)
            throw throwable
        }
    }

    override fun onDestroy() {
        runCatching {
            (application as? MobileHarnessApplication)?.markCleanExit()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NOTIFICATION_ROUTE = "notification_route"
    }
}
