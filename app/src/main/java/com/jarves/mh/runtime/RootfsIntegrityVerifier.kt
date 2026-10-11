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
 * All paths are canonicalized under the rootfs before inspection or repair, so a
 * malicious or corrupted symlink cannot make recovery write outside the guest root.
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
                details = "Rootfs directory does not exist: ${rootfsDir.path}",
            )
        }

        val missing = mutableListOf<String>()
        val corrupted = mutableListOf<String>()
        for (dirRel in ESSENTIAL_DIRECTORIES) {
            val dir = safeRootfsPath(rootfsDir, dirRel)
            when {
                dir == null -> corrupted.add(dirRel)
                !dir.exists() || !dir.isDirectory -> missing.add(dirRel)
            }
        }

        for (fileRel in ESSENTIAL_FILES) {
            val file = safeRootfsPath(rootfsDir, fileRel)
            when {
                file == null -> corrupted.add(fileRel)
                !file.exists() || !file.isFile -> missing.add(fileRel)
            }
        }

        val shellCandidates = listOf("bin/sh", "bin/bash", "usr/bin/bash")
        val shellPaths = shellCandidates.map { it to safeRootfsPath(rootfsDir, it) }
        shellPaths.filter { it.second == null }.forEach { (relative, _) -> corrupted.add(relative) }
        val hasShell = shellPaths.any { (_, file) -> file != null && file.isFile }
        if (!hasShell && shellPaths.none { it.second == null }) missing.add("bin/sh")

        val isValid = missing.isEmpty() && corrupted.isEmpty()
        val details = when {
            isValid -> "Rootfs structure verified healthy."
            corrupted.isNotEmpty() -> "Rootfs contains unsafe or escaping paths: ${corrupted.distinct().joinToString()}. "
            else -> ""
        } + if (missing.isEmpty()) "" else "Missing ${missing.size} critical guest paths: ${missing.joinToString()}"
        return RootfsIntegrityReport(
            isValid = isValid,
            missingPaths = missing.distinct(),
            corruptedPaths = corrupted.distinct(),
            details = details,
        )
    }

    fun verifyAndHeal(rootfsDir: File): RootfsIntegrityReport {
        val initialReport = verify(rootfsDir)
        if (initialReport.isValid) return initialReport
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) return initialReport

        val healed = mutableListOf<String>()
        for (dirRel in ESSENTIAL_DIRECTORIES) {
            val dir = safeRootfsPath(rootfsDir, dirRel) ?: continue
            if (!dir.exists() && dir.mkdirs()) healed.add(dirRel)
        }

        val resolv = safeRootfsPath(rootfsDir, "etc/resolv.conf")
        if (resolv != null && !resolv.exists()) {
            resolv.parentFile?.let { parent ->
                if (parent.isDirectory || parent.mkdirs()) {
                    runCatching {
                        if (safeRootfsPath(rootfsDir, "etc/resolv.conf") == resolv) {
                            resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                            healed.add("etc/resolv.conf")
                        }
                    }
                }
            }
        }

        val passwd = safeRootfsPath(rootfsDir, "etc/passwd")
        if (passwd != null && !passwd.exists()) {
            passwd.parentFile?.let { parent ->
                if (parent.isDirectory || parent.mkdirs()) {
                    runCatching {
                        if (safeRootfsPath(rootfsDir, "etc/passwd") == passwd) {
                            passwd.writeText("root:x:0:0:root:/root:/bin/bash\n")
                            healed.add("etc/passwd")
                        }
                    }
                }
            }
        }

        val hosts = safeRootfsPath(rootfsDir, "etc/hosts")
        if (hosts != null && !hosts.exists()) {
            hosts.parentFile?.let { parent ->
                if (parent.isDirectory || parent.mkdirs()) {
                    runCatching {
                        if (safeRootfsPath(rootfsDir, "etc/hosts") == hosts) {
                            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
                            healed.add("etc/hosts")
                        }
                    }
                }
            }
        }

        val recheck = verify(rootfsDir)
        val madeProgress = healed.isNotEmpty() &&
            (recheck.missingPaths.size < initialReport.missingPaths.size ||
                recheck.corruptedPaths.size < initialReport.corruptedPaths.size)
        val remaining = (recheck.missingPaths + recheck.corruptedPaths).distinct()
        return RootfsIntegrityReport(
            isValid = recheck.isValid,
            missingPaths = recheck.missingPaths,
            corruptedPaths = recheck.corruptedPaths,
            healedPaths = healed,
            isHealed = madeProgress,
            details = "Auto-healing completed. Repaired ${healed.size} items (${healed.joinToString()}). Remaining unsafe or missing paths: ${remaining.joinToString()}",
        )
    }

    private fun safeRootfsPath(rootfsDir: File, relativePath: String): File? = runCatching {
        val root = rootfsDir.canonicalFile.toPath().normalize()
        val candidate = File(rootfsDir, relativePath).canonicalFile.toPath().normalize()
        if (candidate.startsWith(root)) candidate.toFile() else null
    }.getOrNull()
}
