package com.jarves.mh.factory.build

import com.jarves.mh.factory.model.KeystoreConfig
import com.jarves.mh.factory.model.VersionBumpType
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Real signing and versioning helpers for App Factory projects (v3).
 *
 * [createKeystore] genuinely invokes `keytool`; [bumpVersion] really reads,
 * increments and persists the project's version. Nothing is simulated —
 * failures throw with the actual cause.
 */
object SigningAndVersionManager {

    private const val VERSION_FILE = "app-version.properties"

    fun createKeystore(
        projectDir: File,
        keyAlias: String,
        storePassword: String,
        keyPassword: String
    ): KeystoreConfig {
        require(keyAlias.isNotBlank()) { "Key alias must not be blank." }
        require(storePassword.isNotBlank() && keyPassword.isNotBlank()) {
            "Keystore and key passwords must not be blank."
        }
        val keystoreFile = File(projectDir, "release.keystore")
        val cmd = listOf(
            "keytool", "-genkeypair",
            "-keystore", keystoreFile.absolutePath,
            "-storetype", "JKS",
            "-storepass", storePassword,
            "-keypass", keyPassword,
            "-alias", keyAlias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=Mobile Harness, OU=App Factory, O=Codeair Software Solutions"
        )
        try {
            val process = ProcessBuilder(cmd)
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(2, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("keytool timed out while generating the keystore.")
            }
            if (process.exitValue() != 0 || !keystoreFile.isFile) {
                throw IllegalStateException("keytool failed (exit ${process.exitValue()}): $output")
            }
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            throw IllegalStateException(
                "Could not run keytool — is a JDK installed in this environment? Cause: ${e.message}", e
            )
        }
        return KeystoreConfig(
            keystoreFile = keystoreFile,
            storePassword = storePassword,
            keyAlias = keyAlias,
            keyPassword = keyPassword,
            validityDays = 10000
        )
    }

    /**
     * Bumps the project's version, persisted in `app-version.properties`
     * inside [projectDir]. Returns the new (versionCode, versionName).
     */
    fun bumpVersion(projectDir: File, bumpType: VersionBumpType): Pair<Int, String> {
        val propsFile = File(projectDir, VERSION_FILE)
        val props = Properties()
        if (propsFile.isFile) {
            propsFile.inputStream().use { props.load(it) }
        }
        val currentCode = props.getProperty("versionCode")?.toIntOrNull() ?: 1
        val currentName = props.getProperty("versionName") ?: "1.0.0"
        val parts = currentName.split(".").map { it.toIntOrNull() ?: 0 }.toMutableList()
        while (parts.size < 3) parts += 0

        val newCode = currentCode + 1
        val newName = when (bumpType) {
            VersionBumpType.PATCH -> "${parts[0]}.${parts[1]}.${parts[2] + 1}"
            VersionBumpType.MINOR -> "${parts[0]}.${parts[1] + 1}.0"
            VersionBumpType.MAJOR -> "${parts[0] + 1}.0.0"
        }
        props.setProperty("versionCode", newCode.toString())
        props.setProperty("versionName", newName)
        propsFile.outputStream().use { props.store(it, "Managed by Mobile Harness App Factory") }
        return newCode to newName
    }
}
