package com.jarves.mh.factory.build

import com.jarves.mh.factory.model.KeystoreConfig
import com.jarves.mh.factory.model.VersionBumpType
import com.jarves.mh.workspace.SafeFileOps
import java.io.File
import java.util.concurrent.TimeUnit

object SigningAndVersionManager {

    fun createKeystore(
        projectDir: File,
        keyAlias: String,
        storePassword: String,
        keyPassword: String,
        validityDays: Int = 10_000,
    ): KeystoreConfig {
        require(keyAlias.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Key alias contains unsupported characters" }
        require(storePassword.length >= 6 && keyPassword.length >= 6) { "Keystore passwords must be at least 6 characters" }
        check(projectDir.isDirectory || projectDir.mkdirs()) { "Could not create project directory" }

        val keystoreFile = File(projectDir, "release-key.jks")
        val javaHomeKeytool = File(System.getProperty("java.home"), "bin/keytool")
        val command = listOf(
            if (javaHomeKeytool.isFile) javaHomeKeytool.absolutePath else "keytool",
            "-genkeypair",
            "-alias", keyAlias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", validityDays.toString(),
            "-dname", "CN=$keyAlias, OU=Mobile App, O=Developer, C=IN",
            "-storetype", "JKS",
            "-keystore", keystoreFile.absolutePath,
            "-storepass:env", "MHE_STORE_PASSWORD",
            "-keypass:env", "MHE_KEY_PASSWORD",
            "-noprompt",
        )
        val process = ProcessBuilder(command).directory(projectDir).redirectErrorStream(true).apply {
            environment()["MHE_STORE_PASSWORD"] = storePassword
            environment()["MHE_KEY_PASSWORD"] = keyPassword
        }.start()
        val completed = process.waitFor(60, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            error("keytool timed out while generating the keystore")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0 && keystoreFile.isFile && keystoreFile.length() > 0L) {
            "Keystore generation failed: ${output.takeLast(1_000)}"
        }

        val properties = listOf(
            "storeFile=${keystoreFile.absolutePath}",
            "storePassword=$storePassword",
            "keyAlias=$keyAlias",
            "keyPassword=$keyPassword",
        ).joinToString("\n", postfix = "\n")
        check(SafeFileOps.atomicWriteText(File(projectDir, "keystore.properties"), properties)) {
            "Keystore was created, but signing properties could not be saved"
        }
        ensureSigningFilesIgnored(projectDir)
        return KeystoreConfig(keystoreFile, storePassword, keyAlias, keyPassword, validityDays)
    }

    fun injectSigningConfig(gradleFile: File): Boolean {
        if (!gradleFile.isFile) return false
        val original = gradleFile.readText()
        if (original.contains("keystore.properties") && original.contains("signingConfigs")) return true
        val androidMatch = Regex("(?m)^\\s*android\\s*\\{").find(original) ?: return false
        val openBrace = original.indexOf('{', androidMatch.range.first)
        if (openBrace < 0) return false

        val propertiesBlock = """
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = java.util.Properties()
            if (keystorePropertiesFile.exists()) {
                keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
            }

        """.trimIndent() + "\n\n"
        val signingBlock = """
                signingConfigs {
                    create("release") {
                        val keyFile = keystoreProperties["storeFile"] as String?
                        if (!keyFile.isNullOrBlank()) storeFile = file(keyFile)
                        storePassword = keystoreProperties["storePassword"] as String?
                        keyAlias = keystoreProperties["keyAlias"] as String?
                        keyPassword = keystoreProperties["keyPassword"] as String?
                    }
                }
        """.trimIndent()

        var updated = original.substring(0, androidMatch.range.first) +
            propertiesBlock +
            original.substring(androidMatch.range.first, openBrace + 1) +
            "\n" + signingBlock + "\n" +
            original.substring(openBrace + 1)

        val releaseMatch = Regex("(?m)^(\\s*)release\\s*\\{").find(updated)
        if (releaseMatch != null) {
            val insertAt = releaseMatch.range.last + 1
            updated = updated.substring(0, insertAt) +
                "\n" + releaseMatch.groupValues[1] + "    signingConfig = signingConfigs.getByName(\"release\")" +
                updated.substring(insertAt)
        } else {
            val androidBlockStart = updated.indexOf("android")
            val closing = updated.lastIndexOf('}')
            if (androidBlockStart >= 0 && closing > androidBlockStart) {
                updated = updated.substring(0, closing) +
                    "\n    buildTypes { release { signingConfig = signingConfigs.getByName(\"release\") } }\n" +
                    updated.substring(closing)
            }
        }
        return SafeFileOps.atomicWriteText(gradleFile, updated)
    }

    fun bumpVersion(projectDir: File, bumpType: VersionBumpType): Pair<Int, String> {
        val candidates = listOf(
            File(projectDir, "app/build.gradle.kts"),
            File(projectDir, "app/build.gradle"),
            File(projectDir, "build.gradle.kts"),
            File(projectDir, "build.gradle"),
        )
        val gradleFile = candidates.firstOrNull(File::isFile)
            ?: error("No Gradle build file containing version information was found")
        val original = gradleFile.readText()
        val codeMatch = Regex("""versionCode\s*=\s*(\d+)""").find(original)
            ?: error("versionCode was not found in ${gradleFile.path}")
        val nameMatch = Regex("""versionName\s*=\s*["'](\d+)\.(\d+)\.(\d+)["']""").find(original)
            ?: error("Semantic versionName (major.minor.patch) was not found in ${gradleFile.path}")

        val currentCode = codeMatch.groupValues[1].toInt()
        val major = nameMatch.groupValues[1].toInt()
        val minor = nameMatch.groupValues[2].toInt()
        val patch = nameMatch.groupValues[3].toInt()
        val (newMajor, newMinor, newPatch) = when (bumpType) {
            VersionBumpType.PATCH -> Triple(major, minor, patch + 1)
            VersionBumpType.MINOR -> Triple(major, minor + 1, 0)
            VersionBumpType.MAJOR -> Triple(major + 1, 0, 0)
        }
        val newCode = currentCode + 1
        val newName = "$newMajor.$newMinor.$newPatch"
        var updated = original.replaceRange(codeMatch.range, codeMatch.value.replace(currentCode.toString(), newCode.toString()))
        val updatedNameMatch = Regex("""versionName\s*=\s*["'](\d+)\.(\d+)\.(\d+)["']""").find(updated)
            ?: error("Could not locate versionName after versionCode update")
        updated = updated.replaceRange(updatedNameMatch.range, updatedNameMatch.value.replace(updatedNameMatch.groupValues.drop(1).take(3).joinToString("."), newName))
        check(SafeFileOps.atomicWriteText(gradleFile, updated)) { "Could not persist version update" }
        return newCode to newName
    }

    private fun ensureSigningFilesIgnored(projectDir: File) {
        val ignoreFile = File(projectDir, ".gitignore")
        val existing = ignoreFile.takeIf(File::isFile)?.readText().orEmpty()
        val entries = listOf("keystore.properties", "*.jks", "*.keystore")
        val missing = entries.filterNot { entry -> existing.lineSequence().any { it.trim() == entry } }
        if (missing.isNotEmpty()) {
            val updated = existing.trimEnd() + if (existing.isBlank()) "" else "\n"
            check(SafeFileOps.atomicWriteText(ignoreFile, updated + missing.joinToString("\n", postfix = "\n"))) {
                "Could not update .gitignore for signing secrets"
            }
        }
    }
}
