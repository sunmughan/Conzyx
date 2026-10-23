package com.codeair.mhe.factory.build

import com.codeair.mhe.factory.model.BuildArtifactType
import java.io.File

/** Honest outcome of one command run inside the app's Linux runtime. */
data class GuestCommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
)

/**
 * A build session bound to one project workspace inside the runtime
 * (proot) guest. Commands run with the guest's real toolchain on PATH —
 * JDK, Gradle, Android SDK — unlike the Android app process, which has
 * none of those and can only ever exit 127.
 */
interface GuestBuildSession {
    /** Guest build-tools version (e.g. "35.0.0") when an SDK is present. */
    val buildToolsVersion: String?

    suspend fun run(command: String, timeoutMs: Long): GuestCommandResult
}

/**
 * Creates [GuestBuildSession]s bound to a host workspace directory.
 * Implementations throw [IllegalStateException] with a user-facing message
 * when a precondition (runtime installed, Android dev stack present) is
 * not met, so the UI reports the real blocker instead of a phantom build.
 */
fun interface GuestBuildSessionFactory {
    fun create(hostWorkspace: File, guestWorkspacePath: String): GuestBuildSession
}

/**
 * Pure construction of the Gradle invocation used for on-device builds.
 * Mirrors the proven command shape of the workspace "Build & Run" action
 * (MainViewModel.buildAndRunAndroidApp): system Gradle from the runtime's
 * Android stack, the pocketdev init script (local Maven repo + native
 * aapt2), the aapt2 override, no daemon, plain console.
 */
object GradleBuildCommand {

    const val INIT_SCRIPT_PATH = "/root/.gradle/init.d/pocketdev-android.gradle"
    const val GUEST_ANDROID_HOME = "/root/android-sdk"

    fun taskFor(artifactType: BuildArtifactType): String = when (artifactType) {
        BuildArtifactType.APK_DEBUG -> "assembleDebug"
        BuildArtifactType.APK_RELEASE -> "assembleRelease"
        BuildArtifactType.AAB_BUNDLE -> "bundleRelease"
    }

    /** Picks the newest version-looking directory name (e.g. "36.0.0" over "35.0.0"). */
    fun latestVersionedName(names: List<String>): String? =
        names.filter { it.isNotBlank() }
            .maxWithOrNull(Comparator { left, right ->
                val l = versionParts(left)
                val r = versionParts(right)
                var result = 0
                repeat(maxOf(l.size, r.size)) { index ->
                    val c = (l.getOrElse(index) { 0 }).compareTo(r.getOrElse(index) { 0 })
                    if (c != 0 && result == 0) result = c
                }
                if (result != 0) result else left.compareTo(right)
            })

    private fun versionParts(name: String): List<Int> =
        name.split(Regex("[^0-9]+")).filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }

    /** POSIX single-quote escaping for embedding a path in a bash -lc string. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun command(
        guestProjectPath: String,
        artifactType: BuildArtifactType,
        buildToolsVersion: String?,
    ): String = buildString {
        append("cd ").append(shellQuote(guestProjectPath))
        append(" && gradle --init-script ").append(INIT_SCRIPT_PATH)
        if (!buildToolsVersion.isNullOrBlank()) {
            append(" -Pandroid.aapt2FromMavenOverride=")
            append(GUEST_ANDROID_HOME).append("/build-tools/").append(buildToolsVersion).append("/aapt2")
        }
        append(" --no-daemon ").append(taskFor(artifactType)).append(" --console=plain")
    }

    /** keytool invocation for release keystore creation inside the guest. */
    fun keytoolCommand(
        guestKeystorePath: String,
        keyAlias: String,
        storePassword: String,
        keyPassword: String,
    ): String = buildString {
        append("keytool -genkeypair -v")
        append(" -keystore ").append(shellQuote(guestKeystorePath))
        append(" -storetype JKS")
        append(" -storepass ").append(shellQuote(storePassword))
        append(" -keypass ").append(shellQuote(keyPassword))
        append(" -alias ").append(shellQuote(keyAlias))
        append(" -keyalg RSA -keysize 2048 -validity 10000")
        append(" -dname ").append(shellQuote("CN=Conzyx, OU=App Factory, O=Codeair Software Solutions"))
    }
}
