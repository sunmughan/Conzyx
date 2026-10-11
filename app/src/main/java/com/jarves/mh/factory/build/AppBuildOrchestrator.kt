package com.jarves.mh.factory.build

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.jarves.mh.factory.model.AppBlueprint
import com.jarves.mh.factory.model.BuildArtifactType
import com.jarves.mh.factory.model.BuildMode
import com.jarves.mh.factory.model.BuildResult
import com.jarves.mh.workspace.SafeFileOps
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class BuildArtifactMetadata(
    val artifactType: BuildArtifactType,
    val sizeBytes: Long,
    val sha256Hex: String,
)

/**
 * Executes local builds when a usable Gradle wrapper is available, or generates
 * an explicit GitHub Actions workflow. Workflow generation is not represented as
 * a completed remote build.
 */
object AppBuildOrchestrator {
    private const val BUILD_TIMEOUT_MINUTES = 30L
    private const val MAX_LOG_LINES = 200

    fun runBuild(
        projectDir: File,
        blueprint: AppBlueprint,
        artifactType: BuildArtifactType,
        buildMode: BuildMode,
    ): BuildResult {
        val startedAt = System.currentTimeMillis()
        if (buildMode == BuildMode.GITHUB_ACTIONS) {
            return runCatching {
                val workflow = generateGitHubActionsWorkflow(projectDir, blueprint, artifactType)
                BuildResult(
                    success = true,
                    artifactType = artifactType,
                    buildMode = buildMode,
                    ciWorkflowFile = workflow,
                    logs = listOf(
                        "Generated ${workflow.relativeTo(projectDir).invariantSeparatorsPath}.",
                        "Commit and push the workflow to run the remote build; no remote build has run yet.",
                    ),
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }.getOrElse { error ->
                BuildResult(
                    success = false,
                    artifactType = artifactType,
                    buildMode = buildMode,
                    logs = listOf("Could not generate GitHub Actions workflow: ${error.message}"),
                    durationMs = System.currentTimeMillis() - startedAt,
                    errorMessage = error.message,
                )
            }
        }

        if (!projectDir.isDirectory) {
            return failure(artifactType, buildMode, "Project directory does not exist: ${projectDir.path}", startedAt)
        }
        val wrapper = File(projectDir, "gradlew")
        if (!wrapper.isFile) {
            return failure(artifactType, buildMode, "Gradle wrapper (gradlew) was not found in the project.", startedAt)
        }

        val logFile = File(projectDir, ".app-factory-build.log")
        val task = when (artifactType) {
            BuildArtifactType.APK_DEBUG -> "assembleDebug"
            BuildArtifactType.APK_RELEASE -> "assembleRelease"
            BuildArtifactType.AAB_BUNDLE -> "bundleRelease"
        }
        val command = if (wrapper.canExecute()) {
            listOf(wrapper.absolutePath, task, "--no-daemon", "--stacktrace", "--console=plain")
        } else {
            listOf("sh", wrapper.absolutePath, task, "--no-daemon", "--stacktrace", "--console=plain")
        }

        return runCatching {
            val process = ProcessBuilder(command)
                .directory(projectDir)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()
            val finished = process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                return@runCatching failure(
                    artifactType,
                    buildMode,
                    "Build timed out after $BUILD_TIMEOUT_MINUTES minutes.",
                    startedAt,
                    readLogLines(logFile),
                )
            }

            val logs = readLogLines(logFile)
            val artifact = discoverOutputFile(projectDir, artifactType)
            val success = process.exitValue() == 0 && artifact?.isFile == true
            BuildResult(
                success = success,
                artifactType = artifactType,
                buildMode = buildMode,
                outputFile = artifact.takeIf { success },
                logs = logs,
                durationMs = System.currentTimeMillis() - startedAt,
                errorMessage = if (success) null else when {
                    process.exitValue() != 0 -> "Gradle exited with code ${process.exitValue()}."
                    else -> "Gradle reported success, but the expected output artifact was not found."
                },
            )
        }.getOrElse { error ->
            failure(artifactType, buildMode, error.message ?: "Unable to start Gradle build.", startedAt)
        }
    }

    fun generateGitHubActionsWorkflow(
        projectDir: File,
        blueprint: AppBlueprint,
        artifactType: BuildArtifactType,
    ): File {
        val task = when (artifactType) {
            BuildArtifactType.APK_DEBUG -> "assembleDebug"
            BuildArtifactType.APK_RELEASE -> "assembleRelease"
            BuildArtifactType.AAB_BUNDLE -> "bundleRelease"
        }
        val artifactPath = when (artifactType) {
            BuildArtifactType.APK_DEBUG -> "app/build/outputs/apk/debug/*.apk"
            BuildArtifactType.APK_RELEASE -> "app/build/outputs/apk/release/*.apk"
            BuildArtifactType.AAB_BUNDLE -> "app/build/outputs/bundle/release/*.aab"
        }
        val workflow = File(projectDir, ".github/workflows/android-build.yml")
        val safeName = blueprint.name.replace(Regex("[^A-Za-z0-9 _-]"), "").take(40)
        val yaml = """
            name: Build $safeName Artifact

            on:
              workflow_dispatch:
              push:
                branches: [ main ]

            permissions:
              contents: read

            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - name: Checkout
                    uses: actions/checkout@v4
                  - name: Set up Java 17
                    uses: actions/setup-java@v4
                    with:
                      distribution: 'temurin'
                      java-version: '17'
                  - name: Build artifact
                    shell: bash
                    run: |
                      chmod +x ./gradlew
                      ./gradlew $task --no-daemon --stacktrace
                  - name: Upload artifact
                    uses: actions/upload-artifact@v4
                    with:
                      name: app-factory-${artifactType.name.lowercase()}
                      path: $artifactPath
                      if-no-files-found: error
        """.trimIndent() + "\n"
        check(SafeFileOps.atomicWriteText(workflow, yaml)) { "Could not write GitHub Actions workflow" }
        return workflow
    }

    fun discoverOutputFile(projectDir: File, artifactType: BuildArtifactType): File? {
        val outputRoot = File(projectDir, "app/build/outputs")
        if (!outputRoot.isDirectory) return null
        val expectedDirectory = when (artifactType) {
            BuildArtifactType.APK_DEBUG -> File(outputRoot, "apk/debug")
            BuildArtifactType.APK_RELEASE -> File(outputRoot, "apk/release")
            BuildArtifactType.AAB_BUNDLE -> File(outputRoot, "bundle/release")
        }
        val extension = artifactType.extension
        return expectedDirectory.walkTopDown()
            .filter { it.isFile && it.extension.equals(extension, ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .firstOrNull()
    }

    fun extractMetadata(file: File, artifactType: BuildArtifactType): BuildArtifactMetadata {
        require(file.isFile) { "Build artifact does not exist: ${file.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return BuildArtifactMetadata(artifactType, file.length(), sha)
    }

    fun installApk(context: Context, apkFile: File) {
        require(apkFile.isFile && apkFile.extension.equals("apk", ignoreCase = true)) {
            "A valid APK file is required"
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun readLogLines(file: File): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines().takeLast(MAX_LOG_LINES) }.getOrDefault(emptyList())
    }

    private fun failure(
        artifactType: BuildArtifactType,
        buildMode: BuildMode,
        message: String,
        startedAt: Long,
        logs: List<String> = emptyList(),
    ) = BuildResult(
        success = false,
        artifactType = artifactType,
        buildMode = buildMode,
        logs = logs + message,
        durationMs = System.currentTimeMillis() - startedAt,
        errorMessage = message,
    )
}
