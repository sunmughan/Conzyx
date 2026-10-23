package com.codeair.mhe.factory.build

import com.codeair.mhe.model.DevStack
import com.codeair.mhe.runtime.NativeSpawnProcess
import com.codeair.mhe.runtime.RuntimeInstaller
import com.codeair.mhe.runtime.readTailText
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Production [GuestBuildSessionFactory] over [RuntimeInstaller.process] —
 * the same proot spawn the terminals and agent bridges use, with the
 * project workspace bound into the guest and the Android dev stack's
 * toolchain on PATH (exported by the installer when the stack is present).
 */
class RuntimeGuestBuildSessionFactory(
    private val installer: RuntimeInstaller,
) : GuestBuildSessionFactory {

    override fun create(hostWorkspace: File, guestWorkspacePath: String): GuestBuildSession {
        check(installer.isInstalled()) {
            "The Conzyx runtime is not set up yet. Open the terminal once so setup can finish, then run the build again."
        }
        check(installer.isStackInstalled(DevStack.ANDROID)) {
            "Android build tools are not installed in the runtime. Add the Android stack in " +
                "Settings → Development stacks (it downloads the JDK, Gradle and Android SDK), then run the build again."
        }
        val installed = installer.installedRuntime()
        val buildToolsVersion = discoverBuildToolsVersion(installed.rootfs)
        return object : GuestBuildSession {
            override val buildToolsVersion: String? = buildToolsVersion

            override suspend fun run(command: String, timeoutMs: Long): GuestCommandResult =
                withContext(Dispatchers.IO) {
                    val process = installer.process(
                        proot = installed.proot,
                        rootfs = installed.rootfs,
                        workspace = hostWorkspace,
                        environment = emptyMap(),
                        guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
                        guestWorkspacePath = guestWorkspacePath,
                    )
                    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        GuestCommandResult(
                            exitCode = -1,
                            output = readOutput(process),
                            timedOut = true,
                        )
                    } else {
                        GuestCommandResult(
                            exitCode = process.exitValue(),
                            output = readOutput(process),
                        )
                    }
                }
        }
    }

    private fun readOutput(process: Process): String =
        (process as? NativeSpawnProcess)?.outputFile?.readTailText(MAX_OUTPUT_BYTES).orEmpty()

    companion object {
        private const val MAX_OUTPUT_BYTES = 512 * 1024

        /** Newest build-tools directory containing aapt2, read from the host-side rootfs. */
        fun discoverBuildToolsVersion(rootfs: File): String? {
            val buildToolsDir = File(rootfs, "root/android-sdk/build-tools")
            val candidates = buildToolsDir.listFiles { file ->
                file.isDirectory && File(file, "aapt2").isFile
            }.orEmpty().map { it.name }
            return GradleBuildCommand.latestVersionedName(candidates)
        }
    }
}

/**
 * Runs a command list inside a [GuestBuildSession] so host-oriented tools
 * (keytool via [SigningAndVersionManager]) execute in the runtime guest,
 * where a JDK actually exists. Each argument is shell-quoted; the caller
 * must translate host paths in the arguments to guest paths beforehand.
 *
 * [CommandRunner.run] is a blocking interface, so this bridges with
 * [runBlocking] — callers must invoke it off the main thread.
 */
class GuestCommandRunner(
    private val session: GuestBuildSession,
) : CommandRunner {
    override fun run(command: List<String>, workingDir: File, timeout: Duration): CommandResult {
        val shell = command.joinToString(" ") { GradleBuildCommand.shellQuote(it) }
        val result = runBlocking { session.run(shell, timeout.inWholeMilliseconds) }
        return CommandResult(
            command = command,
            exitCode = result.exitCode,
            output = result.output,
            timedOut = result.timedOut,
        )
    }
}
