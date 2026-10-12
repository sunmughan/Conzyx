package com.jarves.mh.workspace

import android.content.Context
import com.jarves.mh.model.Project
import java.io.File
import java.nio.file.Files

/**
 * Domain controller for project workspace detection, metadata inference,
 * and workspace filesystem organization.
 */
class ProjectWorkspaceController(private val context: Context) {

    private val workspacesDir: File
        get() {
            val directory = File(context.filesDir, "workspaces")
            require(!Files.isSymbolicLink(directory.toPath())) { "Workspace storage cannot be a symbolic link" }
            check(directory.mkdirs() || directory.isDirectory) { "Workspace storage is unavailable" }
            return directory.canonicalFile
        }

    private fun projectDirectory(projectId: String): File {
        require(
            projectId.isNotBlank() &&
                !projectId.contains('/') &&
                !projectId.contains('\\\\') &&
                projectId != "." &&
                projectId != "..",
        ) { "Invalid project ID" }
        val root = workspacesDir
        val directory = File(root, projectId)
        require(!Files.isSymbolicLink(directory.toPath())) { "Project workspace cannot be a symbolic link" }
        require(directory.canonicalFile.toPath().startsWith(root.toPath())) {
            "Project workspace escapes app storage"
        }
        return directory
    }

    fun projectWorkspaceRoot(project: Project): File {
        val base = projectDirectory(project.id).canonicalFile
        if (project.rootPath.isBlank()) return base
        val relative = project.rootPath
        require(!relative.startsWith('/') && !relative.contains('\\\\')) { "Invalid project root path" }
        val segments = relative.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Invalid project root path" }
        var candidate = base
        for (segment in segments) {
            candidate = File(candidate, segment)
            require(!Files.isSymbolicLink(candidate.toPath())) { "Project root cannot traverse a symbolic link" }
        }
        require(candidate.canonicalFile.toPath().startsWith(base.toPath())) {
            "Project root escapes its workspace"
        }
        return candidate
    }

    fun detectNestedProjectRoot(projectId: String): String? {
        val base = projectDirectory(projectId)
        if (!base.isDirectory) return null
        val visible = base.listFiles().orEmpty().filterNot { file ->
            file.name == ".claude" || file.name == ".claude.json"
        }
        val onlyDirectory = visible.singleOrNull()?.takeIf(File::isDirectory) ?: return null
        val containsProjectFiles = onlyDirectory.walkTopDown()
            .maxDepth(2)
            .any { it.isFile && it.name !in setOf(".DS_Store", ".claude.json") }
        return onlyDirectory.name.takeIf { containsProjectFiles && !it.contains("..") }
    }

    fun detectImportedProjectMetadata(root: File): Pair<String, String> {
        val names = root.walkTopDown().maxDepth(3).filter(File::isFile).map { it.name.lowercase() }.toSet()
        return when {
            names.any { it == "settings.gradle.kts" || it == "build.gradle.kts" } -> "Imported Gradle project" to "Kotlin"
            names.any { it == "settings.gradle" || it == "build.gradle" } -> "Imported Gradle project" to "Java"
            "package.json" in names && names.any { it == "tsconfig.json" || it.endsWith(".ts") || it.endsWith(".tsx") } -> "Imported web project" to "TypeScript"
            "package.json" in names -> "Imported Node.js project" to "JavaScript"
            "pubspec.yaml" in names -> "Imported Flutter project" to "Dart"
            names.any { it == "cargo.toml" } -> "Imported Rust project" to "Rust"
            names.any { it == "go.mod" } -> "Imported Go project" to "Go"
            names.any { it == "pyproject.toml" || it == "requirements.txt" || it.endsWith(".py") } -> "Imported Python project" to "Python"
            names.any { it == "cmakelists.txt" || it.endsWith(".cpp") || it.endsWith(".c") } -> "Imported C/C++ project" to "C++"
            else -> "Imported device project" to "General"
        }
    }

    fun deleteProjectWorkspace(projectId: String): Boolean {
        return runCatching {
            val root = workspacesDir.canonicalFile
            val dir = projectDirectory(projectId)
            require(dir.canonicalFile.toPath().startsWith(root.toPath())) {
                "Project workspace escapes app storage"
            }
            if (dir.exists()) dir.deleteRecursively() else true
        }.getOrDefault(false)
    }
}
