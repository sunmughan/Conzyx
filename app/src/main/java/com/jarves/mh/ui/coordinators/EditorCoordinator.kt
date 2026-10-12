package com.jarves.mh.ui.coordinators

import com.jarves.mh.model.Project
import com.jarves.mh.model.WorkspaceEntry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor domain coordinator (Milestone 1).
 *
 * Explicit owner of the editor state machine: open editor tabs, the opened
 * file (path/content/loading), file content loading, file saves (with editor
 * checkpoints), and workspace file listing. All file mutations go through the
 * real [com.jarves.mh.workspace.FileOperationsController]; editor saves go
 * through the real [com.jarves.mh.runtime.WorkspaceCheckpoints].
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 *
 * Wiring notes (Milestone 1):
 * - `refreshProjectFiles`, `createFile`, `createFolder`, `renameEntry`,
 *   `deleteEntry`, `duplicateEntry` and `moveEntry` were intentionally NOT
 *   moved here. `refreshProjectFiles` needs `detectNestedProjectRoot` and
 *   `findAndroidGradleProjectRoot` (private in `MainViewModel`, not in this
 *   coordinator's constructor), and the six file-mutation functions call
 *   `refreshProjectFiles()`. They stay in the ViewModel until that dependency
 *   is provided (e.g. a `refreshProjectFiles: () -> Unit` constructor lambda,
 *   as `GitCoordinator` does).
 * - `MAX_EDITABLE_FILE_CHARS` / `MAX_VISIBLE_WORKSPACE_ENTRIES` are
 *   duplicated below with identical values because they are `private const`
 *   in `MainViewModel`'s companion object and cannot be referenced externally.
 * - `readWorkspace` calls `isClaudeRuntimeMetadata`, which stays private in
 *   `MainViewModel` per the extraction rules; the parent wiring must supply it
 *   (move the tiny helper or pass an equivalent predicate).
 */
class EditorCoordinator(
    private val host: CoordinatorHost,
    private val preferences: com.jarves.mh.data.AppPreferences,
    private val fileOperationsController: com.jarves.mh.workspace.FileOperationsController,
    private val editorCheckpoints: com.jarves.mh.runtime.WorkspaceCheckpoints,
    private val workspaceRootFor: (com.jarves.mh.model.Project) -> java.io.File,
) {
    companion object {
        // Duplicated from MainViewModel's private companion object (identical
        // values); see the class KDoc. Needed because the originals are
        // private to MainViewModel and this file must not modify it.
        private const val MAX_EDITABLE_FILE_CHARS = 512_000
        private const val MAX_VISIBLE_WORKSPACE_ENTRIES = 2_000
    }

    fun openFile(entry: WorkspaceEntry) {
        if (entry.isDirectory) return
        openFileByPath(entry.path)
    }

    fun openFileByPath(path: String) {
        val project = host.state().activeProject ?: return
        val currentTabs = host.state().openEditorTabs
        val updatedTabs = if (path in currentTabs) currentTabs else currentTabs + path
        preferences.setOpenEditorTabs(project.id, updatedTabs)
        host.updateState {
            it.copy(
                openEditorTabs = updatedTabs,
                openedFilePath = path,
                openedFileContent = null,
                fileContentLoading = true,
            )
        }
        loadFileContent(project, path)
    }

    fun selectEditorTab(path: String) {
        if (host.state().openedFilePath == path) return
        val project = host.state().activeProject ?: return
        host.updateState {
            it.copy(
                openedFilePath = path,
                openedFileContent = null,
                fileContentLoading = true,
            )
        }
        loadFileContent(project, path)
    }

    fun closeEditorTab(path: String) {
        val project = host.state().activeProject ?: return
        val currentTabs = host.state().openEditorTabs
        val updatedTabs = currentTabs.filter { it != path }
        preferences.setOpenEditorTabs(project.id, updatedTabs)
        if (host.state().openedFilePath == path) {
            val nextPath = updatedTabs.lastOrNull()
            if (nextPath != null) {
                host.updateState {
                    it.copy(
                        openEditorTabs = updatedTabs,
                        openedFilePath = nextPath,
                        openedFileContent = null,
                        fileContentLoading = true,
                    )
                }
                loadFileContent(project, nextPath)
            } else {
                host.updateState {
                    it.copy(
                        openEditorTabs = emptyList(),
                        openedFilePath = null,
                        openedFileContent = null,
                        fileContentLoading = false,
                    )
                }
            }
        } else {
            host.updateState { it.copy(openEditorTabs = updatedTabs) }
        }
    }

    fun closeOtherEditorTabs(keepPath: String) {
        val project = host.state().activeProject ?: return
        val updatedTabs = listOf(keepPath)
        preferences.setOpenEditorTabs(project.id, updatedTabs)
        if (host.state().openedFilePath != keepPath) {
            selectEditorTab(keepPath)
        }
        host.updateState { it.copy(openEditorTabs = updatedTabs) }
    }

    fun closeAllEditorTabs() {
        val project = host.state().activeProject
        if (project != null) {
            preferences.setOpenEditorTabs(project.id, emptyList())
        }
        host.updateState {
            it.copy(
                openEditorTabs = emptyList(),
                openedFilePath = null,
                openedFileContent = null,
                fileContentLoading = false,
            )
        }
    }

    fun closeFile() {
        closeAllEditorTabs()
    }

    private fun loadFileContent(project: Project, path: String) {
        host.scope.launch {
            val content = withContext(Dispatchers.IO) {
                val file = File(workspaceRootFor(project), path)
                runCatching {
                    if (file.length() > 512_000L) {
                        file.inputStream().use { stream ->
                            val buf = ByteArray(512_000)
                            val read = stream.read(buf)
                            String(buf, 0, read)
                        } + "\n\n[File truncated — too large to display fully]"
                    } else {
                        file.readText()
                    }
                }.getOrElse { "Could not read file: ${it.message}" }
            }
            if (host.state().openedFilePath == path) {
                host.updateState { it.copy(openedFileContent = content, fileContentLoading = false) }
            }
        }
    }

    /**
     * Persists an edited workspace file as an explicit, reviewable change.
     * A checkpoint is created before the first manual edit so the existing
     * Changes tab can Undo/Keep the edit using the same durable mechanism as
     * agent-generated changes.
     */
    suspend fun saveFile(path: String, content: String): Boolean {
        val current = host.state()
        val project = current.activeProject ?: return false
        if (current.isRunning || current.projectTerminalRunning) {
            host.updateState { it.copy(toastMessage = "Stop the running task before editing files") }
            return false
        }
        if (path.isBlank() || path.startsWith('/') || editorCheckpoints.isInternalRuntimePath(path)) {
            host.updateState { it.copy(toastMessage = "This workspace path cannot be edited") }
            return false
        }
        if (path.split('/').any { it == ".." || it.isBlank() }) {
            host.updateState { it.copy(toastMessage = "Unsafe workspace path") }
            return false
        }
        if (content.length > MAX_EDITABLE_FILE_CHARS) {
            host.updateState { it.copy(toastMessage = "This file is too large to edit safely in the mobile editor") }
            return false
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val workspace = workspaceRootFor(project).canonicalFile
                val rootPath = workspace.toPath()
                val target = File(workspace, path).canonicalFile
                require(target.parentFile?.canonicalFile?.toPath()?.startsWith(rootPath) == true) { "Workspace path escapes project" }
                val existing = target.takeIf(File::isFile)
                if (existing != null && existing.length() > MAX_EDITABLE_FILE_CHARS) {
                    error("This file is too large to edit safely in the mobile editor")
                }
                if (existing != null && existing.readText() == content) return@runCatching Unit

                editorCheckpoints.createCheckpoint(project.id, workspace)
                target.parentFile?.mkdirs()
                val temporary = File(
                    target.parentFile,
                    ".${target.name}.mh-edit-${UUID.randomUUID()}.tmp",
                )
                try {
                    temporary.writeText(content)
                    runCatching {
                        Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    }.getOrElse {
                        Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                } finally {
                    temporary.delete()
                }
                editorCheckpoints.saveChangedPaths(project.id, listOf(path))
            }
        }
        return result.fold(
            onSuccess = {
                val workspace = workspaceRootFor(project)
                val pendingPaths = editorCheckpoints.readChangedPaths(project.id)
                val changes = editorCheckpoints.buildChangeDetails(project.id, workspace, pendingPaths)
                host.updateState { state ->
                    state.copy(
                        openedFileContent = content,
                        changes = changes,
                        toastMessage = "Saved " + path,
                        workspaceFiles = state.workspaceFiles.map { entry ->
                            if (entry.path == path) entry.copy(
                                sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                            ) else entry
                        },
                    )
                }
                true
            },
            onFailure = { error ->
                host.updateState { it.copy(toastMessage = error.message ?: "Could not save " + path) }
                false
            },
        )
    }

    private fun readWorkspace(project: Project): List<WorkspaceEntry> {
        val root = workspaceRootFor(project)
        if (!root.isDirectory) return emptyList()
        val rootPath = root.canonicalFile.toPath()
        return root.walkTopDown()
            .maxDepth(12)
            .onEnter { directory ->
                val relative = if (directory == root) "" else directory.relativeTo(root).invariantSeparatorsPath
                directory == root || (!isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(directory.toPath()) &&
                    runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .drop(1)
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                !isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .take(MAX_VISIBLE_WORKSPACE_ENTRIES)
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                WorkspaceEntry(
                    path = relative,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    depth = relative.count { it == '/' },
                    sizeBytes = if (file.isFile) file.length() else 0,
                )
            }
            .sortedWith(compareBy<WorkspaceEntry> { it.path.lowercase() }.thenByDescending { it.isDirectory })
            .toList()
    }
}
