package com.jarves.mh.workspace

import java.io.File

/**
 * Result of a Git domain command.
 */
data class GitCommandResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
    val errorMessage: String? = null,
)

/**
 * Domain controller for Git operations (status, diff, branch, checkout, commit, push, pull).
 * Decoupled from MainViewModel to ensure single-responsibility and full unit testability.
 */
class GitDomainController(
    private val processRunner: (args: List<String>, workingDir: File, guestWorkspacePath: String) -> Pair<Int, String> = { _, _, _ -> 0 to "" }
) {

    fun executeGit(
        args: List<String>,
        workingDir: File,
        guestWorkspacePath: String = "",
    ): GitCommandResult {
        if (!workingDir.exists()) {
            return GitCommandResult(
                success = false,
                exitCode = 1,
                output = "",
                errorMessage = "Workspace directory does not exist: ${workingDir.path}",
            )
        }
        return try {
            val (exit, out) = processRunner(args, workingDir, guestWorkspacePath)
            GitCommandResult(
                success = exit == 0,
                exitCode = exit,
                output = out.trim(),
                errorMessage = if (exit != 0) out.trim().ifBlank { "Git command failed with code $exit" } else null,
            )
        } catch (e: Exception) {
            GitCommandResult(
                success = false,
                exitCode = 1,
                output = "",
                errorMessage = e.message ?: "Git execution error",
            )
        }
    }

    fun status(workingDir: File, guestWorkspacePath: String = ""): GitCommandResult {
        return executeGit(listOf("status", "--short"), workingDir, guestWorkspacePath)
    }

    fun diff(workingDir: File, guestWorkspacePath: String = ""): GitCommandResult {
        return executeGit(listOf("diff", "--stat"), workingDir, guestWorkspacePath)
    }

    fun branchList(workingDir: File, guestWorkspacePath: String = ""): List<String> {
        val res = executeGit(listOf("branch", "--list"), workingDir, guestWorkspacePath)
        if (!res.success) return emptyList()
        return res.output.lineSequence()
            .map { it.trim().removePrefix("*").trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    fun checkout(
        workingDir: File,
        branch: String,
        createNew: Boolean = false,
        guestWorkspacePath: String = "",
    ): GitCommandResult {
        val args = if (createNew) listOf("checkout", "-b", branch) else listOf("checkout", branch)
        return executeGit(args, workingDir, guestWorkspacePath)
    }

    fun commit(
        workingDir: File,
        message: String,
        guestWorkspacePath: String = "",
    ): GitCommandResult {
        val addRes = executeGit(listOf("add", "-A"), workingDir, guestWorkspacePath)
        if (!addRes.success) {
            return addRes.copy(errorMessage = "Failed to stage files: ${addRes.errorMessage}")
        }
        return executeGit(listOf("commit", "-m", message), workingDir, guestWorkspacePath)
    }

    fun push(
        workingDir: File,
        remote: String = "origin",
        branch: String = "",
        guestWorkspacePath: String = "",
    ): GitCommandResult {
        val args = if (branch.isNotBlank()) listOf("push", remote, branch) else listOf("push")
        return executeGit(args, workingDir, guestWorkspacePath)
    }

    fun pull(
        workingDir: File,
        remote: String = "origin",
        branch: String = "",
        guestWorkspacePath: String = "",
    ): GitCommandResult {
        val args = if (branch.isNotBlank()) listOf("pull", remote, branch) else listOf("pull")
        return executeGit(args, workingDir, guestWorkspacePath)
    }
}
