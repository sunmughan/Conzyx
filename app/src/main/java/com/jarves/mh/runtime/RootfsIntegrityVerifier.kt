package com.jarves.mh.runtime

import java.io.File

data class RootfsIntegrityReport(
    val isValid: Boolean,
    val missingPaths: List<String>,
    val corruptedPaths: List<String> = emptyList(),
    val healedPaths: List<String> = emptyList(),
    val isHealed: Boolean = false,
    val details: String = "",
)

/**
 * Rootfs & Runtime Corruption Auto-Healing Verifier (Phase 1 Foundation Hardening).
 * Verifies critical PRoot Linux guest directories, symlinks, and configuration files,
 * and automatically heals recoverable corruption without requiring a full re-download.
 */
object RootfsIntegrityVerifier {

    private val ESSENTIAL_DIRECTORIES = listOf(
        "tmp",
        "dev",
        "proc",
        "etc",
        "bin",
        "usr/bin",
        "var/tmp",
    )

    private val ESSENTIAL_FILES = listOf(
        "etc/passwd",
        "etc/resolv.conf",
    )

    fun verify(rootfsDir: File): RootfsIntegrityReport {
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) {
            return RootfsIntegrityReport(
                isValid = false,
                missingPaths = listOf("rootfs_root"),
                details = "Rootfs directory does not exist: ${rootfsDir.path}"
            )
        }

        val missing = mutableListOf<String>()

        for (dirRel in ESSENTIAL_DIRECTORIES) {
            val dir = File(rootfsDir, dirRel)
            if (!dir.exists() || !dir.isDirectory) {
                missing.add(dirRel)
            }
        }

        for (fileRel in ESSENTIAL_FILES) {
            val file = File(rootfsDir, fileRel)
            if (!file.exists() || !file.isFile) {
                missing.add(fileRel)
            }
        }

        // Verify shell binary (/bin/sh or /usr/bin/sh or /bin/bash)
        val shFile = File(rootfsDir, "bin/sh")
        val bashFile = File(rootfsDir, "bin/bash")
        val usrBashFile = File(rootfsDir, "usr/bin/bash")
        if (!shFile.exists() && !bashFile.exists() && !usrBashFile.exists()) {
            missing.add("bin/sh")
        }

        val isValid = missing.isEmpty()
        return RootfsIntegrityReport(
            isValid = isValid,
            missingPaths = missing,
            details = if (isValid) "Rootfs structure verified healthy." else "Missing ${missing.size} critical guest paths: ${missing.joinToString()}"
        )
    }

    fun verifyAndHeal(rootfsDir: File): RootfsIntegrityReport {
        val initialReport = verify(rootfsDir)
        if (initialReport.isValid) return initialReport

        if (!rootfsDir.exists()) {
            return initialReport
        }

        val healed = mutableListOf<String>()

        // 1. Recreate missing essential directories
        for (dirRel in ESSENTIAL_DIRECTORIES) {
            val dir = File(rootfsDir, dirRel)
            if (!dir.exists()) {
                if (dir.mkdirs()) {
                    healed.add(dirRel)
                }
            }
        }

        // 2. Recreate missing /etc/resolv.conf
        val resolv = File(rootfsDir, "etc/resolv.conf")
        if (!resolv.exists()) {
            resolv.parentFile?.mkdirs()
            runCatching {
                resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                healed.add("etc/resolv.conf")
            }
        }

        // 3. Recreate missing /etc/passwd
        val passwd = File(rootfsDir, "etc/passwd")
        if (!passwd.exists()) {
            passwd.parentFile?.mkdirs()
            runCatching {
                passwd.writeText("root:x:0:0:root:/root:/bin/bash\n")
                healed.add("etc/passwd")
            }
        }

        // 4. Recreate missing /etc/hosts
        val hosts = File(rootfsDir, "etc/hosts")
        if (!hosts.exists()) {
            hosts.parentFile?.mkdirs()
            runCatching {
                hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
                healed.add("etc/hosts")
            }
        }

        val recheck = verify(rootfsDir)
        val successfullyHealed = recheck.missingPaths.size < initialReport.missingPaths.size

        return RootfsIntegrityReport(
            isValid = recheck.isValid,
            missingPaths = recheck.missingPaths,
            healedPaths = healed,
            isHealed = successfullyHealed,
            details = "Auto-healing completed. Healed ${healed.size} items (${healed.joinToString()}). Remaining missing: ${recheck.missingPaths.joinToString()}"
        )
    }
}
