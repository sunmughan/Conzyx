package com.jarves.mh.runtime

import com.jarves.mh.workspace.SafeFileOps
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Applies an extracted runtime overlay only after all paths and type conflicts have
 * been validated. Existing files and symlinks are backed up and restored if a write
 * fails, so a storage error cannot leave a half-applied overlay in the current rootfs.
 */
internal object RuntimeOverlayTransaction {

    private data class Entry(
        val source: File,
        val relativePath: String,
        val destination: File,
        val isSymlink: Boolean,
        val isDirectory: Boolean,
        val symlinkTarget: java.nio.file.Path? = null,
    )

    private data class OriginalState(
        val destination: File,
        val existed: Boolean,
        val symlinkTarget: java.nio.file.Path? = null,
        val backupFile: File? = null,
    )

    fun apply(staging: File, rootfs: File, backupRoot: File): Boolean {
        if (!staging.isDirectory || !rootfs.isDirectory) return false
        val root = runCatching { rootfs.canonicalFile }.getOrNull() ?: return false
        val rootPath = root.toPath()
        val entries = runCatching {
            staging.walkTopDown()
                .filter { it != staging }
                .map { source ->
                    val relative = source.relativeTo(staging).invariantSeparatorsPath
                    require(relative.isNotBlank() && !relative.startsWith('/') &&
                        relative.split('/').none { it == ".." }) { "Unsafe overlay path: $relative" }
                    val destination = File(root, relative)
                    val parentPath = (destination.parentFile ?: root).canonicalFile.toPath()
                    require(parentPath.startsWith(rootPath)) { "Overlay path escapes rootfs: $relative" }
                    val symlink = Files.isSymbolicLink(source.toPath())
                    val target = if (symlink) Files.readSymbolicLink(source.toPath()) else null
                    if (target != null) {
                        val resolvedTarget = if (target.isAbsolute) {
                            rootPath.resolve(target.toString().trimStart('/')).normalize()
                        } else {
                            parentPath.resolve(target).normalize()
                        }
                        require(resolvedTarget.startsWith(rootPath)) { "Overlay symlink escapes rootfs: $relative" }
                    }
                    val directory = source.isDirectory && !symlink
                    if (directory) {
                        require(!destination.exists() || (destination.isDirectory && !Files.isSymbolicLink(destination.toPath()))) {
                            "Overlay directory conflicts with an existing file: $relative"
                        }
                    } else {
                        require(!destination.isDirectory || Files.isSymbolicLink(destination.toPath())) {
                            "Overlay file conflicts with an existing directory: $relative"
                        }
                    }
                    Entry(source, relative, destination, symlink, directory, target)
                }
                .sortedWith(compareBy<Entry>({ it.relativePath.count { char -> char == '/' } }, { it.relativePath }))
                .toList()
        }.getOrElse { return false }

        if (runCatching { backupRoot.deleteRecursively() }.getOrDefault(false).not() && backupRoot.exists()) return false
        if (!backupRoot.mkdirs() && !backupRoot.isDirectory) return false

        val originals = mutableListOf<OriginalState>()
        val createdDirectories = mutableListOf<File>()
        return try {
            entries.filter { it.isDirectory }.forEach { entry ->
                if (!entry.destination.exists()) {
                    check(entry.destination.mkdirs() || entry.destination.isDirectory) {
                        "Could not create overlay directory ${entry.relativePath}"
                    }
                    createdDirectories += entry.destination
                }
            }

            entries.filterNot { it.isDirectory }.forEach { entry ->
                val destination = entry.destination
                val existed = destination.exists() || Files.isSymbolicLink(destination.toPath())
                var oldLink: java.nio.file.Path? = null
                var backupFile: File? = null
                if (existed && Files.isSymbolicLink(destination.toPath())) {
                    oldLink = Files.readSymbolicLink(destination.toPath())
                } else if (existed) {
                    backupFile = File(backupRoot, entry.relativePath)
                    check(backupFile.parentFile?.mkdirs() != false) { "Could not create overlay backup directory" }
                    check(SafeFileOps.atomicCopy(destination, backupFile)) {
                        "Could not back up ${entry.relativePath} before overlay update"
                    }
                }
                originals += OriginalState(destination, existed, oldLink, backupFile)

                if (entry.isSymlink) {
                    replaceWithSymlink(destination, requireNotNull(entry.symlinkTarget))
                } else {
                    check(SafeFileOps.atomicCopy(entry.source, destination)) {
                        "Could not atomically apply overlay file ${entry.relativePath}"
                    }
                }
            }

            check(backupRoot.deleteRecursively() || !backupRoot.exists()) { "Could not clean overlay backup" }
            true
        } catch (_: Exception) {
            originals.asReversed().forEach { state ->
                runCatching {
                    when {
                        !state.existed -> {
                            if (Files.isSymbolicLink(state.destination.toPath()) || state.destination.isFile) {
                                state.destination.delete()
                            }
                        }
                        state.symlinkTarget != null -> replaceWithSymlink(state.destination, state.symlinkTarget)
                        state.backupFile?.isFile == true -> SafeFileOps.atomicCopy(state.backupFile, state.destination)
                    }
                }
            }
            createdDirectories.asReversed().forEach { directory ->
                runCatching { if (directory.isDirectory && directory.listFiles().isNullOrEmpty()) directory.delete() }
            }
            runCatching { backupRoot.deleteRecursively() }
            false
        }
    }

    private fun replaceWithSymlink(destination: File, target: java.nio.file.Path) {
        val parent = destination.parentFile ?: error("Symlink destination has no parent")
        check(parent.isDirectory || parent.mkdirs()) { "Could not create symlink parent" }
        val temporary = File(parent, ".overlay-link-${UUID.randomUUID().toString().take(8)}")
        try {
            Files.createSymbolicLink(temporary.toPath(), target)
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
        }
    }
}
