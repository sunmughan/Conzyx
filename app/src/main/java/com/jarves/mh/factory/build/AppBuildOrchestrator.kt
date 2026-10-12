package com.jarves.mh.factory.build

import android.content.Context
import com.jarves.mh.factory.model.AppBlueprint
import com.jarves.mh.factory.model.BuildArtifactType
import com.jarves.mh.factory.model.BuildMode
import com.jarves.mh.factory.model.BuildResult
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real build orchestration for App Factory scaffolded projects (v3).
 *
 * [runBuild] actually executes Gradle inside [projectDir] and reports the
 * genuine outcome — no simulated success. [installApk] uses the platform
 * [PackageInstaller] API to install a real APK file.
 */
object AppBuildOrchestrator {

    fun runBuild(
        projectDir: File,
        blueprint: AppBlueprint,
        artifactType: BuildArtifactType,
        buildMode: BuildMode
    ): BuildResult {
        val startedAt = System.currentTimeMillis()
        val logs = mutableListOf<String>()
        logs += "Building ${blueprint.name} (${artifactType.displayName}) via ${buildMode.displayName}..."

        if (buildMode == BuildMode.GITHUB_ACTIONS) {
            val msg = "GitHub Actions CI build is not wired yet — push the project to a repo " +
                "with the Mobile Harness CI workflow, or use On-Device build."
            logs += "ERROR: $msg"
            return BuildResult(
                success = false,
                artifactType = artifactType,
                buildMode = buildMode,
                logs = logs,
                durationMs = System.currentTimeMillis() - startedAt,
                errorMessage = msg
            )
        }

        val gradleTask = when (artifactType) {
            BuildArtifactType.APK_DEBUG -> "assembleDebug"
            BuildArtifactType.APK_RELEASE -> "assembleRelease"
            BuildArtifactType.AAB_BUNDLE -> "bundleRelease"
        }

        val gradlew = File(projectDir, "gradlew")
        if (!gradlew.isFile) {
            val msg = "No Gradle wrapper found in ${projectDir.absolutePath} — scaffold the project first."
            logs += "ERROR: $msg"
            return BuildResult(
                success = false,
                artifactType = artifactType,
                buildMode = buildMode,
                logs = logs,
                durationMs = System.currentTimeMillis() - startedAt,
                errorMessage = msg
            )
        }

        return try {
            gradlew.setExecutable(true)
            val process = ProcessBuilder("sh", gradlew.absolutePath, gradleTask, "--stacktrace")
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.MINUTES)
            logs += output.lines().takeLast(120)
            if (!finished) {
                process.destroyForcibly()
                val msg = "Gradle build timed out after 30 minutes."
                logs += "ERROR: $msg"
                return BuildResult(
                    success = false, artifactType = artifactType, buildMode = buildMode,
                    logs = logs, durationMs = System.currentTimeMillis() - startedAt,
                    errorMessage = msg
                )
            }
            val exitCode = process.exitValue()
            val artifact = findBuiltArtifact(projectDir, artifactType)
            if (exitCode == 0 && artifact != null) {
                logs += "Build succeeded: ${artifact.absolutePath}"
                BuildResult(
                    success = true, artifactType = artifactType, buildMode = buildMode,
                    outputFile = artifact, logs = logs,
                    durationMs = System.currentTimeMillis() - startedAt
                )
            } else {
                val msg = if (exitCode != 0) "Gradle exited with code $exitCode."
                else "Gradle succeeded but no ${artifactType.extension.uppercase()} artifact was produced."
                logs += "ERROR: $msg"
                BuildResult(
                    success = false, artifactType = artifactType, buildMode = buildMode,
                    logs = logs, durationMs = System.currentTimeMillis() - startedAt,
                    errorMessage = msg
                )
            }
        } catch (e: Exception) {
            val msg = "Build failed to start: ${e.message}"
            logs += "ERROR: $msg"
            BuildResult(
                success = false, artifactType = artifactType, buildMode = buildMode,
                logs = logs, durationMs = System.currentTimeMillis() - startedAt,
                errorMessage = msg
            )
        }
    }

    private fun findBuiltArtifact(projectDir: File, type: BuildArtifactType): File? {
        val ext = type.extension
        val outputs = File(projectDir, "app/build/outputs")
        if (!outputs.isDirectory) return null
        return outputs.walkTopDown()
            .filter { it.isFile && it.extension.equals(ext, ignoreCase = true) }
            .maxByOrNull { it.lastModified() }
    }

    /**
     * Installs [apkFile] via the app's real installer ([AndroidAppInstaller]).
     * Throws on any failure — the caller reports the real error.
     */
    fun installApk(context: Context, apkFile: File) {
        require(apkFile.isFile) { "APK not found: ${apkFile.absolutePath}" }
        com.jarves.mh.runtime.AndroidAppInstaller.install(context, apkFile)
    }
}
