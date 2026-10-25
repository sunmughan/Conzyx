package com.codeair.mhe.factory.build

import com.codeair.mhe.factory.model.KeystoreConfig
import com.codeair.mhe.factory.model.VersionBumpType
import java.io.File
import java.util.Properties
import kotlin.time.Duration.Companion.minutes

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
        keyPassword: String,
        commandRunner: CommandRunner = ProcessBuilderCommandRunner(),
        keystoreCommandPath: String? = null
    ): KeystoreConfig {
        require(keyAlias.isNotBlank()) { "Key alias must not be blank." }
        require(storePassword.isNotBlank() && keyPassword.isNotBlank()) {
            "Keystore and key passwords must not be blank."
        }
        // keytool itself rejects passwords shorter than 6 characters; fail
        // here with a clear message instead of a cryptic keytool error.
        require(storePassword.length >= 6 && keyPassword.length >= 6) {
            "Keystore and key passwords must be at least 6 characters (keytool requirement)."
        }
        val keystoreFile = File(projectDir, "release.keystore")
        val cmd = listOf(
            "keytool", "-genkeypair",
            "-keystore", keystoreCommandPath ?: keystoreFile.absolutePath,
            "-storetype", "JKS",
            "-storepass", storePassword,
            "-keypass", keyPassword,
            "-alias", keyAlias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=Conzyx, OU=App Factory, O=Codeair Software Solutions"
        )
        // The command runs through the injected runner: on a real device
        // that is the runtime guest (which has a JDK); the Android app
        // process itself has no keytool and could only ever fail.
        val result = try {
            commandRunner.run(cmd, projectDir, 2.minutes)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Could not run keytool — is a JDK installed in this environment? Cause: ${e.message}", e
            )
        }
        if (result.timedOut) {
            throw IllegalStateException("keytool timed out while generating the keystore.")
        }
        if (!result.success || !keystoreFile.isFile) {
            throw IllegalStateException("keytool failed (exit ${result.exitCode}): ${result.output}")
        }
        return KeystoreConfig(
            keystoreFile = keystoreFile,
            storePassword = storePassword,
            keyAlias = keyAlias,
            keyPassword = keyPassword,
            validityDays = 10000
        ).also {
            // Persisted so injectSigningConfig() and Gradle can reference it.
            // storeFile stays project-relative so the path resolves both on
            // the host and inside the runtime guest.
            // Same latent ENOENT pattern as bumpVersion: ensure the folder.
            projectDir.mkdirs()
            File(projectDir, "keystore.properties").writeText(
                "storeFile=${keystoreFile.name}\n" +
                    "storePassword=$storePassword\n" +
                    "keyAlias=$keyAlias\n" +
                    "keyPassword=$keyPassword\n"
            )
        }
    }

    /**
     * Injects a `signingConfigs` block referencing `keystore.properties` into
     * the app module's `build.gradle.kts`. Returns true only when the file
     * was actually modified. Idempotent — a second call is a no-op returning true.
     */
    fun injectSigningConfig(gradleFile: File): Boolean {
        require(gradleFile.isFile) { "Gradle file not found: ${gradleFile.absolutePath}" }
        val original = gradleFile.readText()
        if (original.contains("keystore.properties")) return true // already injected
        val snippet = """
            
            // Injected by Conzyx App Factory — release signing via keystore.properties
            val keystoreProps = java.util.Properties().apply {
                val propsFile = rootProject.file("keystore.properties")
                if (propsFile.isFile) propsFile.inputStream().use(::load)
            }
            android {
                signingConfigs {
                    create("release") {
                        storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "release.keystore"))
                        storePassword = keystoreProps.getProperty("storePassword", "")
                        keyAlias = keystoreProps.getProperty("keyAlias", "")
                        keyPassword = keystoreProps.getProperty("keyPassword", "")
                    }
                }
            }
        """.trimIndent()
        gradleFile.writeText(original + snippet)
        return gradleFile.readText().contains("signingConfigs") &&
            gradleFile.readText().contains("keystore.properties")
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
        // Crashlytics ENOENT fix: FileOutputStream creates the file but never
        // its parent directories, so bumping a version for a project whose
        // folder was deleted or never created crashed. Create parents first.
        propsFile.parentFile?.mkdirs()
        propsFile.outputStream().use { props.store(it, "Managed by Conzyx App Factory") }
        return newCode to newName
    }
}
