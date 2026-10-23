package com.codeair.mhe.factory.build

import android.content.Context
import com.codeair.mhe.factory.model.AppBlueprint
import com.codeair.mhe.factory.model.AppFramework
import com.codeair.mhe.factory.model.BuildArtifactType
import com.codeair.mhe.factory.model.BuildMode
import com.codeair.mhe.factory.model.BuildResult
import java.io.File

/**
 * Real build orchestration for App Factory scaffolded projects.
 *
 * On-device builds run Gradle inside the app's Linux runtime through a
 * [GuestBuildSession] (JDK + Gradle + Android SDK from the Android dev
 * stack) and report the genuine exit code — the previous implementation
 * spawned the scaffold's stub `gradlew` in the Android app process, where
 * no Gradle exists, so every build died with exit 127. [installApk] uses
 * the platform [PackageInstaller] API to install a real APK file.
 *
 * The GitHub Actions mode generates the CI workflow file into the project
 * and says so; it does not claim a build happened — the build runs on
 * GitHub after the user pushes the project.
 */
object AppBuildOrchestrator {

    private const val BUILD_TIMEOUT_MS = 30 * 60 * 1000L

    suspend fun runBuild(
        projectDir: File,
        blueprint: AppBlueprint,
        artifactType: BuildArtifactType,
        buildMode: BuildMode,
        session: GuestBuildSession? = null,
        guestProjectPath: String = "/workspace",
    ): BuildResult {
        val startedAt = System.currentTimeMillis()
        val logs = mutableListOf<String>()
        logs += "Building ${blueprint.name} (${artifactType.displayName}) via ${buildMode.displayName}..."

        fun failure(message: String, ciWorkflowFile: File? = null) = BuildResult(
            success = false,
            artifactType = artifactType,
            buildMode = buildMode,
            ciWorkflowFile = ciWorkflowFile,
            logs = logs.apply { this += "ERROR: $message" },
            durationMs = System.currentTimeMillis() - startedAt,
            errorMessage = message
        )

        if (blueprint.framework != AppFramework.ANDROID_COMPOSE) {
            return failure(
                "Builds currently target Android (Jetpack Compose) projects only. " +
                    "This blueprint is ${blueprint.framework.displayName}; switch the framework " +
                    "to Android in the Blueprint tab to build it here."
            )
        }

        if (buildMode == BuildMode.GITHUB_ACTIONS) {
            val workflow = try {
                generateGitHubActionsWorkflow(projectDir, blueprint, artifactType)
            } catch (e: Exception) {
                return failure("Could not write the CI workflow: ${e.message}")
            }
            val msg = "CI workflow written to .github/workflows/${workflow.name}. Nothing was built " +
                "in the app — push this project to GitHub to run the build there, then download " +
                "the ${artifactType.extension.uppercase()} from the Actions run's artifacts."
            logs += msg
            return BuildResult(
                success = false,
                artifactType = artifactType,
                buildMode = buildMode,
                ciWorkflowFile = workflow,
                logs = logs,
                durationMs = System.currentTimeMillis() - startedAt,
                errorMessage = msg
            )
        }

        if (session == null) {
            return failure(
                "The on-device build session is unavailable, so no build was run. " +
                    "Builds run inside the Conzyx runtime; reopen App Factory and try again."
            )
        }

        val hasSettings = File(projectDir, "settings.gradle.kts").isFile ||
            File(projectDir, "settings.gradle").isFile
        if (!hasSettings) {
            return failure(
                "No Gradle project found in ${projectDir.absolutePath} — scaffold the project first (Code & DB tab)."
            )
        }

        val command = GradleBuildCommand.command(guestProjectPath, artifactType, session.buildToolsVersion)
        logs += "Runtime command: $command"
        val result = try {
            session.run(command, BUILD_TIMEOUT_MS)
        } catch (e: Exception) {
            return failure("Build failed to start in the runtime: ${e.message}")
        }
        logs += result.output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(120)

        if (result.timedOut) {
            return failure("Gradle build timed out after 30 minutes.")
        }
        val artifact = findBuiltArtifact(projectDir, artifactType)
        return if (result.exitCode == 0 && artifact != null) {
            logs += "Build succeeded: ${artifact.absolutePath}"
            BuildResult(
                success = true, artifactType = artifactType, buildMode = buildMode,
                outputFile = artifact, logs = logs,
                durationMs = System.currentTimeMillis() - startedAt
            )
        } else {
            val msg = if (result.exitCode != 0) {
                "Gradle exited with code ${result.exitCode} inside the runtime. See the console log for the failing task."
            } else {
                "Gradle finished but no ${artifactType.extension.uppercase()} artifact was produced."
            }
            failure(msg)
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
     * Discovers the built artifact of [artifactType] under [projectDir]'s
     * standard Gradle output layout. Returns null when nothing was produced.
     */
    fun discoverOutputFile(projectDir: File, artifactType: BuildArtifactType): File? =
        findBuiltArtifact(projectDir, artifactType)

    /**
     * Computes real metadata (size + SHA-256) for a built artifact.
     * Throws when [file] does not exist — never reports "unknown".
     */
    fun extractMetadata(file: File, artifactType: BuildArtifactType): ArtifactMetadata {
        require(file.isFile) { "Artifact not found: ${file.absolutePath}" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
        }
        val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
        return ArtifactMetadata(
            artifactType = artifactType,
            sizeBytes = file.length(),
            sha256Hex = sha256Hex
        )
    }

    /**
     * Generates a real GitHub Actions workflow (`android-build.yml`) that
     * compiles [artifactType] for the scaffolded project in [projectDir].
     * Returns the written workflow file.
     */
    fun generateGitHubActionsWorkflow(
        projectDir: File,
        blueprint: AppBlueprint,
        artifactType: BuildArtifactType
    ): File {
        val gradleTask = when (artifactType) {
            BuildArtifactType.APK_DEBUG -> "assembleDebug"
            BuildArtifactType.APK_RELEASE -> "assembleRelease"
            BuildArtifactType.AAB_BUNDLE -> "bundleRelease"
        }
        val workflowsDir = File(projectDir, ".github/workflows").apply { mkdirs() }
        val workflowFile = File(workflowsDir, "android-build.yml")
        workflowFile.writeText(
            """
            name: Android Build — ${blueprint.name}

            on:
              push:
                branches: [ main ]
              workflow_dispatch:

            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - name: Set up JDK 17
                    uses: actions/setup-java@v4
                    with:
                      distribution: 'temurin'
                      java-version: '17'
                  - name: Build ${artifactType.displayName}
                    run: ./gradlew $gradleTask --stacktrace
                  - name: Upload artifact
                    uses: actions/upload-artifact@v4
                    with:
                      name: ${blueprint.name.lowercase().replace(Regex("[^a-z0-9]"), "-")}-${artifactType.extension}
                      path: app/build/outputs/**/**/*.${artifactType.extension}
            """.trimIndent()
        )
        return workflowFile
    }

    /**
     * Installs [apkFile] via the app's real installer ([AndroidAppInstaller]).
     * Throws on any failure — the caller reports the real error.
     */
    fun installApk(context: Context, apkFile: File) {
        require(apkFile.isFile) { "APK not found: ${apkFile.absolutePath}" }
        com.codeair.mhe.runtime.AndroidAppInstaller.install(context, apkFile)
    }
}

/** Real, computed metadata for a built artifact — never "unknown". */
data class ArtifactMetadata(
    val artifactType: BuildArtifactType,
    val sizeBytes: Long,
    val sha256Hex: String
)
