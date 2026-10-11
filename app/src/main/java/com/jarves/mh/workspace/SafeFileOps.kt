package com.jarves.mh.workspace

import com.jarves.mh.AppCrashLogger
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * File operations that stage writes before replacing their target, reducing the risk
 * of leaving partially written project files after process death or storage errors.
 */
object SafeFileOps {

    /** Writes UTF-8 text through a staging file before replacing [target]. */
    fun atomicWriteText(target: File, content: String): Boolean =
        atomicWriteBytes(target, content.toByteArray(Charsets.UTF_8))

    /**
     * Writes bytes to a sibling staging file, syncs it, and then moves it into place.
     * The existing target is never explicitly deleted before replacement.
     */
    fun atomicWriteBytes(target: File, bytes: ByteArray): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) return false

        val tempFile = File(parent, "${target.name}.tmp_${UUID.randomUUID().toString().take(8)}")
        return try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                runCatching { fos.fd.sync() }
            }

            replaceFromTemp(tempFile, target)
            true
        } catch (e: Exception) {
            AppCrashLogger.log("SafeFileOps atomic write failed for ${target.path}: ${e.message}")
            runCatching { tempFile.delete() }
            false
        }
    }

    /**
     * Copies a file through a sibling staging file, keeping large files streaming
     * and preserving the old destination if copying fails.
     */
    fun atomicCopy(source: File, target: File): Boolean {
        if (!source.isFile) return false
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) return false

        val tempFile = File(parent, "${target.name}.tmp_${UUID.randomUUID().toString().take(8)}")
        return try {
            source.inputStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                    runCatching { output.fd.sync() }
                }
            }
            replaceFromTemp(tempFile, target)
            true
        } catch (e: Exception) {
            AppCrashLogger.log("SafeFileOps atomic copy failed for ${target.path}: ${e.message}")
            runCatching { tempFile.delete() }
            false
        }
    }

    private fun replaceFromTemp(tempFile: File, target: File) {
        try {
            Files.move(
                tempFile.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Do not delete the current target before asking the filesystem to replace it.
            Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Reads UTF-8 text from an existing file. Returns null if the file is missing
     * or cannot be read; an empty file is returned as an empty string.
     */
    fun safeReadText(target: File): String? {
        if (!target.isFile) return null
        return runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
    }

    /** Removes stale staging files left by interrupted writes. */
    fun cleanStaleTempFiles(directory: File, maxAgeMs: Long = 300_000L): Int {
        if (!directory.isDirectory) return 0
        var cleaned = 0
        val cutoff = System.currentTimeMillis() - maxAgeMs
        runCatching {
            directory.listFiles()?.forEach { file ->
                if (file.name.contains(".tmp_") && file.lastModified() < cutoff) {
                    if (file.delete()) cleaned++
                }
            }
        }
        return cleaned
    }
}
