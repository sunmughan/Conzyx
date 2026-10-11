package com.jarves.mh.runtime

import java.io.File

/**
 * Safely activates a staged directory while retaining the previous directory until
 * the newly activated directory passes validation.
 *
 * All directories must be siblings so renames stay on the same filesystem.
 */
internal object RuntimeDirectoryTransaction {

    fun recoverPrevious(
        target: File,
        backup: File,
        isTargetValid: (File) -> Boolean,
    ): Boolean {
        requireSameParent(target, backup)
        if (!backup.exists()) return false

        if (target.exists() && runCatching { isTargetValid(target) }.getOrDefault(false)) {
            check(backup.deleteRecursively() || !backup.exists()) {
                "Could not remove stale runtime backup"
            }
            return false
        }

        if (target.exists()) {
            check(target.deleteRecursively() || !target.exists()) {
                "Could not remove incomplete runtime directory"
            }
        }
        check(backup.renameTo(target)) { "Could not restore previous runtime directory" }
        return true
    }

    fun activate(
        staging: File,
        target: File,
        backup: File,
        validateActivated: (File) -> Unit,
        isTargetValid: (File) -> Boolean,
    ) {
        requireSameParent(staging, target)
        requireSameParent(target, backup)
        check(staging.isDirectory) { "Staged runtime directory is missing" }

        recoverPrevious(target, backup, isTargetValid)
        val hadTarget = target.exists()
        if (hadTarget) {
            check(target.renameTo(backup)) { "Could not preserve the current runtime before activation" }
        }

        try {
            check(staging.renameTo(target)) { "Could not activate the staged runtime directory" }
            validateActivated(target)
        } catch (failure: Throwable) {
            if (target.exists()) {
                check(target.deleteRecursively() || !target.exists()) {
                    "Activation failed and the incomplete runtime could not be removed"
                }
            }
            if (hadTarget && backup.exists()) {
                check(backup.renameTo(target)) {
                    "Activation failed and the previous runtime could not be restored"
                }
            }
            throw failure
        }

        if (hadTarget && backup.exists()) {
            // A leftover backup is harmless after successful activation. The next
            // install attempt will clean it after validating the active directory.
            runCatching { backup.deleteRecursively() }
        }
    }

    private fun requireSameParent(first: File, second: File) {
        val firstParent = first.absoluteFile.parentFile?.canonicalFile
        val secondParent = second.absoluteFile.parentFile?.canonicalFile
        require(firstParent != null && firstParent == secondParent) {
            "Runtime transaction paths must be sibling directories"
        }
    }
}
