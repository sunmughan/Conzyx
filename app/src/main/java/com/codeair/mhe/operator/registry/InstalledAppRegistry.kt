package com.codeair.mhe.operator.registry

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class DiscoveredApp(
    val packageName: String,
    val appLabel: String
)

class InstalledAppRegistry(private val appContext: Context) {

    private var cached: List<DiscoveredApp> = emptyList()

    fun refresh(): List<DiscoveredApp> {
        val discovered = runCatching {
            val packageManager = appContext.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = packageManager.queryIntentActivities(launcherIntent, 0)
            activities
                .mapNotNull { info ->
                    val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                    val label = runCatching {
                        info.loadLabel(packageManager)?.toString()
                    }.getOrNull()?.ifBlank { null } ?: packageName
                    DiscoveredApp(packageName, label)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.appLabel.lowercase() }
        }.getOrElse {
            runCatching {
                val packageManager = appContext.packageManager
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { appInfo ->
                        appInfo.packageName != null &&
                            packageManager.getLaunchIntentForPackage(appInfo.packageName) != null
                    }
                    .map { appInfo ->
                        val label = runCatching {
                            appInfo.loadLabel(packageManager)?.toString()
                        }.getOrNull()?.ifBlank { null } ?: appInfo.packageName
                        DiscoveredApp(appInfo.packageName, label)
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.appLabel.lowercase() }
            }.getOrDefault(emptyList())
        }
        cached = discovered
        return discovered
    }

    fun launchableApps(): List<DiscoveredApp> = cached
}
