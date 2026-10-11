package com.jarves.mh.runtime

import com.jarves.mh.workspace.SafeFileOps

import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.DiffLine
import com.jarves.mh.model.DiffLineType
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray

/**
 * Workspace checkpoint / snapshot / diff store shared by agent bridges.
 *
 * Each project workspace gets a baseline copy before a session runs; after the
 * run the baseline is diffed to produce reviewable [ChangeItem]s with
 * per-file Undo/Keep. Semantics mirror the original Claude bridge store so
 * both agents behave identically in the Changes tab.
 */
class WorkspaceCheckpoints(private val filesDir: File) {
    private val projectRoots = ConcurrentHashMap<String, String>()

    fun ensureWorkspace(projectId: String): File {
        validateProjectId(projectId)
        val storageRoot = filesDir.canonicalFile
        val workspacesRoot = File(storageRoot, "workspaces")
        require(!Files.isSymbolicLink(workspacesRoot.toPath())) {
            "Workspace storage root cannot be a symbolic link"
        }
        check(workspacesRoot.mkdirs() || workspacesRoot.isDirectory) { "Workspace storage is unavailable" }
        val canonicalWorkspacesRoot = workspacesRoot.canonicalFile
        require(canonicalWorkspacesRoot.toPath().startsWith(storageRoot.toPath())) {
            "Workspace storage escapes app storage"
        }

        val projectDirectory = File(canonicalWorkspacesRoot, projectId)
        require(!Files.isSymbolicLink(projectDirectory.toPath())) { "Project workspace cannot be a symbolic link" }
        check(projectDirectory.mkdirs() || projectDirectory.isDirectory) { "Could not create project workspace" }
        val base = projectDirectory.canonicalFile
        require(base.toPath().startsWith(canonicalWorkspacesRoot.toPath())) { "Project workspace escapes storage" }

        val configuredRoot = projectRoots[projectId].orEmpty()
        if (configuredRoot.isBlank()) return base
        var selectedPath = base
        val segments = configuredRoot.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe project root" }
        for (segment in segments) {
            selectedPath = File(selectedPath, segment)
            require(!Files.isSymbolicLink(selectedPath.toPath())) { "Project root cannot traverse symbolic links" }
        }
        val selected = selectedPath.canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        check(selected.mkdirs() || selected.isDirectory) { "Could not create selected project root" }
        require(selected.canonicalFile.toPath().startsWith(base.toPath())) { "Project root escapes workspace" }
        return selected
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        validateProjectId(projectId)
        val normalized = rootPath.trim().trim('/')
        require(normalized.isBlank() || (!normalized.contains("..") && !normalized.startsWith('/'))) {
            "Unsafe project root"
        }
        val previous = projectRoots.put(projectId, normalized).orEmpty()
        if (previous != normalized) checkpointDir(projectId).deleteRecursively()
    }

    fun checkpointDir(projectId: String): File {
        validateProjectId(projectId)
        val storageRoot = filesDir.canonicalFile.toPath()
        val checkpointsRootFile = File(filesDir, "checkpoints")
        require(!Files.isSymbolicLink(checkpointsRootFile.toPath())) {
            "Checkpoint storage root cannot be a symbolic link"
        }
        val checkpointsRoot = checkpointsRootFile.canonicalFile
        require(checkpointsRoot.toPath().startsWith(storageRoot)) { "Checkpoint storage escapes app storage" }
        val projectRootFile = File(checkpointsRoot, projectId)
        require(!Files.isSymbolicLink(projectRootFile.toPath())) {
            "Checkpoint project directory cannot be a symbolic link"
        }
        val projectRoot = projectRootFile.canonicalFile
        require(projectRoot.toPath().startsWith(checkpointsRoot.toPath())) { "Checkpoint project path escapes storage" }
        val latest = File(projectRoot, "latest")
        require(!Files.isSymbolicLink(latest.toPath())) { "Checkpoint path must not be a symbolic link" }
        if (latest.exists()) {
            require(latest.canonicalFile.toPath().startsWith(projectRoot.toPath())) {
                "Checkpoint path escapes project storage"
            }
        }
        return latest
    }

    private fun validateProjectId(projectId: String) {
        require(
            projectId.isNotBlank() &&
                !projectId.contains('/') &&
                !projectId.contains('\\') &&
                projectId != "." &&
                projectId != "..",
        ) { "Invalid project ID" }
    }

    fun createCheckpoint(projectId: String, workspace: File) {
        require(workspace.isDirectory) { "Workspace is unavailable; preserving the last known-good checkpoint" }
        val checkpoint = checkpointDir(projectId)
        // Keep the original baseline until every pending file is accepted or undone.
        if (File(checkpoint, "project").isDirectory && File(checkpoint, "changes.json").isFile) {
            if (readChangedPaths(projectId).isEmpty()) {
                val baseline = snapshot(File(checkpoint, "project"))
                val recoveredChanges = changedFiles(workspace, baseline)
                if (recoveredChanges.isNotEmpty()) saveChangedPaths(projectId, recoveredChanges)
            }
            return
        }

        val parent = checkpoint.parentFile ?: error("Checkpoint parent is unavailable")
        check(parent.mkdirs() || parent.isDirectory) { "Could not create checkpoint parent" }
        val staging = File(parent, "${checkpoint.name}.staging_${UUID.randomUUID()}")
        val previous = File(parent, "${checkpoint.name}.previous_${UUID.randomUUID()}")
        var previousMoved = false
        try {
            val backup = File(staging, "project")
            check(backup.mkdirs()) { "Could not create checkpoint staging directory" }
            val canonicalWorkspace = workspace.canonicalFile
            val workspacePath = canonicalWorkspace.toPath()
            canonicalWorkspace.walkTopDown()
                .onEnter { directory ->
                    val relative = if (directory == canonicalWorkspace) "" else directory.relativeTo(canonicalWorkspace).invariantSeparatorsPath
                    directory == canonicalWorkspace || (!isInternalRuntimePath(relative) &&
                        !Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false))
                }
                .filter {
                    it.isFile &&
                        !isInternalRuntimePath(it.relativeTo(canonicalWorkspace).invariantSeparatorsPath) &&
                        !Files.isSymbolicLink(it.toPath())
                }
                .forEach { source ->
                    val relative = source.relativeTo(canonicalWorkspace).invariantSeparatorsPath
                    val destination = safeWorkspaceFile(backup, relative)
                    check(destination.parentFile?.let { it.mkdirs() || it.isDirectory } == true) {
                        "Could not create checkpoint directory for $relative"
                    }
                    check(SafeFileOps.atomicCopy(source, destination)) { "Could not safely copy ${source.path}" }
                }
            check(SafeFileOps.atomicWriteText(File(staging, "changes.json"), "[]")) {
                "Could not initialize checkpoint change manifest"
            }

            // Publish only a complete snapshot. The previous baseline is retained until
            // the staged directory has been moved into place successfully.
            if (checkpoint.exists() || Files.isSymbolicLink(checkpoint.toPath())) {
                moveDirectory(checkpoint, previous)
                previousMoved = true
            }
            try {
                moveDirectory(staging, checkpoint)
            } catch (publishFailure: Exception) {
                if (previousMoved && !checkpoint.exists()) moveDirectory(previous, checkpoint)
                previousMoved = false
                throw publishFailure
            }
            if (previousMoved) {
                check(previous.deleteRecursively() || !previous.exists()) {
                    "New checkpoint published, but stale previous checkpoint could not be removed"
                }
                previousMoved = false
            }
        } finally {
            staging.deleteRecursively()
            if (previousMoved && !checkpoint.exists() && previous.exists()) {
                runCatching { moveDirectory(previous, checkpoint) }
            }
        }
    }

    private fun moveDirectory(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    fun saveChangedPaths(projectId: String, paths: List<String>) {
        val manifest = File(checkpointDir(projectId), "changes.json")
        manifest.parentFile?.mkdirs()
        val merged = (readChangedPaths(projectId) + paths)
            .filterNot(::isInternalRuntimePath)
            .distinct()
            .sorted()
        check(SafeFileOps.atomicWriteText(manifest, JSONArray(merged).toString())) { "Unable to persist checkpoint change manifest" }
    }

    fun readChangedPaths(projectId: String): List<String> {
        val manifest = File(checkpointDir(projectId), "changes.json")
        if (!manifest.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())
    }

    fun removeChangedPath(projectId: String, path: String) {
        val remaining = readChangedPaths(projectId).filterNot { it == path }
        if (remaining.isEmpty()) {
            checkpointDir(projectId).deleteRecursively()
        } else {
            check(SafeFileOps.atomicWriteText(File(checkpointDir(projectId), "changes.json"), JSONArray(remaining).toString())) { "Unable to persist checkpoint change manifest" }
        }
    }

    fun buildChangeDetails(projectId: String, workspace: File, paths: List<String>): List<ChangeItem> {
        val backup = File(checkpointDir(projectId), "project")
        return paths.map { path ->
            val beforeFile = safeWorkspaceFile(backup, path).takeIf(File::isFile)
            val afterFile = safeWorkspaceFile(workspace, path).takeIf(File::isFile)
            if (listOfNotNull(beforeFile, afterFile).any { it.length() > MAX_DIFF_FILE_BYTES }) {
                return@map ChangeItem(
                    path = path,
                    additions = 0,
                    deletions = 0,
                    diffLines = listOf(
                        DiffLine(
                            DiffLineType.INFO,
                            "File is too large to preview safely. Undo and Keep still work.",
                        ),
                    ),
                )
            }
            val before = beforeFile?.readBytes() ?: ByteArray(0)
            val after = afterFile?.readBytes() ?: ByteArray(0)
            val binary = before.any { it == 0.toByte() } || after.any { it == 0.toByte() }
            val (additions, deletions) = lineChanges(before, after)
            ChangeItem(
                path = path,
                additions = additions,
                deletions = deletions,
                diffLines = buildDiffLines(before, after),
                binary = binary,
            )
        }
    }

    fun buildDiffLines(beforeBytes: ByteArray, afterBytes: ByteArray): List<DiffLine> {
        if (beforeBytes.any { it == 0.toByte() } || afterBytes.any { it == 0.toByte() }) {
            return listOf(DiffLine(DiffLineType.INFO, "Binary file changed"))
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_RENDERED_DIFF_LINES || after.size > MAX_RENDERED_DIFF_LINES) {
            return listOf(
                DiffLine(
                    DiffLineType.INFO,
                    "Diff is too large to display (${before.size} → ${after.size} lines). Undo and Keep still work.",
                ),
            )
        }

        val lcs = Array(before.size + 1) { IntArray(after.size + 1) }
        for (oldIndex in before.lastIndex downTo 0) {
            for (newIndex in after.lastIndex downTo 0) {
                lcs[oldIndex][newIndex] = if (before[oldIndex] == after[newIndex]) {
                    lcs[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                }
            }
        }

        val result = mutableListOf<DiffLine>()
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < before.size || newIndex < after.size) {
            when {
                oldIndex < before.size && newIndex < after.size && before[oldIndex] == after[newIndex] -> {
                    result += DiffLine(DiffLineType.CONTEXT, before[oldIndex], oldIndex + 1, newIndex + 1)
                    oldIndex++
                    newIndex++
                }
                newIndex < after.size && (oldIndex == before.size || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex]) -> {
                    result += DiffLine(DiffLineType.ADDITION, after[newIndex], null, newIndex + 1)
                    newIndex++
                }
                oldIndex < before.size -> {
                    result += DiffLine(DiffLineType.DELETION, before[oldIndex], oldIndex + 1, null)
                    oldIndex++
                }
            }
        }
        return collapseUnchangedLines(result)
    }

    private fun collapseUnchangedLines(lines: List<DiffLine>): List<DiffLine> {
        val changedIndexes = lines.indices.filter { lines[it].type != DiffLineType.CONTEXT }
        if (changedIndexes.isEmpty()) return lines
        val visible = BooleanArray(lines.size)
        changedIndexes.forEach { changed ->
            for (index in maxOf(0, changed - DIFF_CONTEXT_LINES)..minOf(lines.lastIndex, changed + DIFF_CONTEXT_LINES)) {
                visible[index] = true
            }
        }
        val result = mutableListOf<DiffLine>()
        var index = 0
        while (index < lines.size) {
            if (visible[index]) {
                result += lines[index++]
            } else {
                val start = index
                while (index < lines.size && !visible[index]) index++
                result += DiffLine(DiffLineType.INFO, "… ${index - start} unchanged lines …")
            }
        }
        return result
    }

    private fun lineChanges(beforeBytes: ByteArray, afterBytes: ByteArray): Pair<Int, Int> {
        if (beforeBytes.any { it == 0.toByte() } || afterBytes.any { it == 0.toByte() }) {
            return (if (afterBytes.isNotEmpty()) 1 else 0) to (if (beforeBytes.isNotEmpty()) 1 else 0)
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_DIFF_LINES || after.size > MAX_DIFF_LINES) {
            return maxOf(0, after.size - before.size) to maxOf(0, before.size - after.size)
        }
        var previous = IntArray(after.size + 1)
        before.forEach { oldLine ->
            val current = IntArray(after.size + 1)
            after.forEachIndexed { index, newLine ->
                current[index + 1] = if (oldLine == newLine) {
                    previous[index] + 1
                } else {
                    maxOf(previous[index + 1], current[index])
                }
            }
            previous = current
        }
        val common = previous[after.size]
        return (after.size - common) to (before.size - common)
    }

    private fun textLines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val lines = bytes.decodeToString().split('\n')
        return if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    }

    fun safeWorkspaceFile(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.contains('\\')) {
            "Unsafe workspace path"
        }
        val segments = relative.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe workspace path" }
        val rootPath = root.canonicalFile.toPath()
        var current = root
        for (segment in segments) {
            current = File(current, segment)
            require(!Files.isSymbolicLink(current.toPath())) { "Workspace path traverses a symbolic link" }
        }
        val filePath = current.canonicalFile.toPath()
        require(filePath.startsWith(rootPath)) { "Workspace path escapes project" }
        return current
    }

    fun snapshot(root: File): Map<String, String> {
        if (!root.isDirectory) return emptyMap()
        val canonicalRoot = root.canonicalFile
        val rootPath = canonicalRoot.toPath()
        return canonicalRoot.walkTopDown()
            .onEnter { directory ->
                val relative = if (directory == canonicalRoot) "" else directory.relativeTo(canonicalRoot).invariantSeparatorsPath
                directory == canonicalRoot || (
                    !isInternalRuntimePath(relative) &&
                        !Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .filter { file ->
                file.isFile &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    !isInternalRuntimePath(file.relativeTo(canonicalRoot).invariantSeparatorsPath) &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .associate { it.relativeTo(canonicalRoot).invariantSeparatorsPath to digest(it) }
    }

    fun changedFiles(root: File, before: Map<String, String>): List<String> {
        val after = snapshot(root)
        return (before.keys + after.keys).distinct().filter { before[it] != after[it] }.sorted()
    }

    fun isInternalRuntimePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized == ".claude.json" || IGNORED_DIRECTORY_NAMES.any { directory ->
            normalized == directory || normalized.startsWith("$directory/") || normalized.contains("/$directory/")
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_DIFF_LINES = 2_000
        private const val MAX_RENDERED_DIFF_LINES = 600
        private const val MAX_DIFF_FILE_BYTES = 1_000_000L
        private const val DIFF_CONTEXT_LINES = 3
        private val IGNORED_DIRECTORY_NAMES = setOf(
            ".claude",
            ".git",
            ".gradle",
            ".idea",
            ".next",
            ".dart_tool",
            ".venv",
            "build",
            "dist",
            "node_modules",
            "target",
            "venv",
        )
    }
}
