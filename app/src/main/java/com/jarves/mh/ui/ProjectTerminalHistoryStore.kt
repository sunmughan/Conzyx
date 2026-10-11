package com.jarves.mh.ui

import com.jarves.mh.workspace.SafeFileOps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class ProjectTerminalSnapshot(
    val lines: List<TerminalOutputLine> = emptyList(),
    val cwd: String = "/workspace",
)

/**
 * Owns the durable terminal history boundary, separate from the root ViewModel.
 */
internal class ProjectTerminalHistoryStore(
    private val filesDir: File,
    private val maxHistory: Int = 100,
    private val maxOutputChars: Int = 200_000,
) {
    fun load(projectId: String, guestRoot: String): ProjectTerminalSnapshot {
        val file = historyFile(projectId)
        if (!file.isFile) return ProjectTerminalSnapshot(cwd = guestRoot)
        return runCatching {
            val content = SafeFileOps.safeReadText(file) ?: return ProjectTerminalSnapshot(cwd = guestRoot)
            val root = JSONObject(content)
            val array = root.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TerminalOutputLine(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    command = item.optString("command"),
                    output = item.optString("output"),
                    exitCode = item.optInt("exitCode"),
                )
            }
            ProjectTerminalSnapshot(
                lines = lines.takeLast(maxHistory),
                cwd = root.optString("cwd", guestRoot).takeIf {
                    it == guestRoot || it.startsWith("$guestRoot/")
                } ?: guestRoot,
            )
        }.getOrDefault(ProjectTerminalSnapshot(cwd = guestRoot))
    }

    fun save(projectId: String, cwd: String, guestRoot: String, lines: List<TerminalOutputLine>): Boolean {
        if (cwd != guestRoot && !cwd.startsWith("$guestRoot/")) return false
        val file = historyFile(projectId)
        val array = JSONArray()
        lines.takeLast(maxHistory).forEach { line ->
            array.put(
                JSONObject()
                    .put("id", line.id)
                    .put("command", line.command)
                    .put("output", line.output.takeLast(maxOutputChars))
                    .put("exitCode", line.exitCode),
            )
        }
        return SafeFileOps.atomicWriteText(file, JSONObject().put("cwd", cwd).put("lines", array).toString())
    }

    fun delete(projectId: String): Boolean {
        val file = historyFile(projectId)
        return !file.exists() || file.delete()
    }

    private fun historyFile(projectId: String): File {
        require(
            projectId.isNotBlank() &&
                !projectId.contains('/') &&
                !projectId.contains('\\') &&
                projectId != "." &&
                projectId != "..",
        ) { "Invalid project ID" }
        return File(filesDir, "terminal-history/$projectId.json")
    }
}
