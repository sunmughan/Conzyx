package com.codeair.mhe.session

import android.content.Context
import com.codeair.mhe.AppCrashLogger
import com.codeair.mhe.model.ActiveRoadmap
import com.codeair.mhe.model.RoadmapStep
import com.codeair.mhe.model.StepStatus
import com.codeair.mhe.workspace.SafeFileOps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class AgentSessionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    INTERRUPTED,
}

data class PersistedAgentSession(
    val sessionId: String,
    val projectId: String,
    val projectTitle: String,
    val chatId: String,
    val initialPrompt: String,
    val startedAt: Long,
    val updatedAt: Long,
    val status: AgentSessionStatus,
    val activeRoadmap: ActiveRoadmap? = null,
    val summary: String? = null,
)

class AgentSessionManager(private val baseSessionsDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "agent_sessions"))

    private val sessionsDir: File
        get() = baseSessionsDir.apply { mkdirs() }

    private val activeSessionFile: File
        get() = File(sessionsDir, "active_session.json")

    /**
     * Records the start of an agent session, persisting initial metadata to disk.
     */
    fun recordSessionStart(
        sessionId: String,
        projectId: String,
        projectTitle: String,
        chatId: String,
        prompt: String,
        roadmap: ActiveRoadmap? = null,
    ) {
        val session = PersistedAgentSession(
            sessionId = sessionId,
            projectId = projectId,
            projectTitle = projectTitle,
            chatId = chatId,
            initialPrompt = prompt,
            startedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            status = AgentSessionStatus.RUNNING,
            activeRoadmap = roadmap,
        )
        writeActiveSession(session)
        AppCrashLogger.checkpoint("AgentSessionStarted", "Session $sessionId in project $projectTitle")
    }

    /**
     * Updates ongoing roadmap progress and metadata for the active session.
     */
    fun updateSessionProgress(roadmap: ActiveRoadmap?, summary: String? = null) {
        val current = readActiveSession() ?: return
        val updated = current.copy(
            updatedAt = System.currentTimeMillis(),
            activeRoadmap = roadmap ?: current.activeRoadmap,
            summary = summary ?: current.summary,
        )
        writeActiveSession(updated)
    }

    /**
     * Marks session as successfully completed and archives it.
     */
    fun recordSessionCompleted(summary: String? = null) {
        val current = readActiveSession() ?: return
        val completed = current.copy(
            updatedAt = System.currentTimeMillis(),
            status = AgentSessionStatus.COMPLETED,
            summary = summary,
        )
        archiveSession(completed)
        activeSessionFile.delete()
        AppCrashLogger.checkpoint("AgentSessionCompleted", "Session ${current.sessionId}")
    }

    /**
     * Marks session as failed and archives it.
     */
    fun recordSessionFailed(reason: String) {
        val current = readActiveSession() ?: return
        val failed = current.copy(
            updatedAt = System.currentTimeMillis(),
            status = AgentSessionStatus.FAILED,
            summary = reason,
        )
        archiveSession(failed)
        activeSessionFile.delete()
        AppCrashLogger.checkpoint("AgentSessionFailed", "Session ${current.sessionId}: $reason")
    }

    /**
     * Detects if a previous session was interrupted by an app crash, force stop, or Android OOM.
     */
    fun detectInterruptedSession(): PersistedAgentSession? {
        val current = readActiveSession() ?: return null
        if (current.status == AgentSessionStatus.RUNNING) {
            val marked = current.copy(status = AgentSessionStatus.INTERRUPTED)
            writeActiveSession(marked)
            AppCrashLogger.checkpoint("AgentSessionInterruptedDetected", "Detected interrupted session: ${marked.sessionId}")
            return marked
        }
        return if (current.status == AgentSessionStatus.INTERRUPTED) current else null
    }

    fun checkInterruptedSession(): PersistedAgentSession? = detectInterruptedSession()

    /**
     * Marks session as cancelled by user.
     */
    fun recordSessionCancelled() {
        val current = readActiveSession() ?: return
        val cancelled = current.copy(
            updatedAt = System.currentTimeMillis(),
            status = AgentSessionStatus.INTERRUPTED,
            summary = "Cancelled by user",
        )
        archiveSession(cancelled)
        activeSessionFile.delete()
        AppCrashLogger.checkpoint("AgentSessionCancelled", "Session ${current.sessionId}")
    }

    /**
     * Marks session as timed out.
     */
    fun recordSessionTimeout(timeoutMs: Long) {
        val current = readActiveSession() ?: return
        val timeoutMinutes = timeoutMs / 60_000
        val timedOut = current.copy(
            updatedAt = System.currentTimeMillis(),
            status = AgentSessionStatus.FAILED,
            summary = "Session timed out after $timeoutMinutes minutes",
        )
        archiveSession(timedOut)
        activeSessionFile.delete()
        AppCrashLogger.checkpoint("AgentSessionTimeout", "Session ${current.sessionId}")
    }

    /**
     * Clears the current active session file.
     */
    fun clearActiveSession() {
        activeSessionFile.delete()
    }

    /**
     * Lists all archived historical sessions, newest first.
     */
    fun listSessionHistory(): List<PersistedAgentSession> {
        val historyDir = File(sessionsDir, "history")
        if (!historyDir.isDirectory) return emptyList()
        val files = historyDir.listFiles() ?: return emptyList()
        val list = mutableListOf<PersistedAgentSession>()
        for (file in files) {
            if (file.isFile && file.name.startsWith("session_") && file.extension == "json") {
                val content = SafeFileOps.safeReadText(file) ?: continue
                val session = runCatching { deserializeSession(JSONObject(content)) }.getOrNull()
                if (session != null) {
                    list.add(session)
                }
            }
        }
        return list.sortedByDescending { it.updatedAt }
    }

    /**
     * Prunes session history older than [retentionDays].
     */
    fun cleanupOldSessions(retentionDays: Int = 14): Int {
        val historyDir = File(sessionsDir, "history")
        if (!historyDir.isDirectory) return 0
        val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24 * 60 * 60 * 1000L)
        var cleaned = 0
        runCatching {
            historyDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    if (file.delete()) cleaned++
                }
            }
        }
        return cleaned
    }

    private fun writeActiveSession(session: PersistedAgentSession) {
        runCatching {
            val json = serializeSession(session)
            SafeFileOps.atomicWriteText(activeSessionFile, json.toString(2))
        }.onFailure {
            AppCrashLogger.log("Failed to write active session: ${it.message}")
        }
    }

    private fun readActiveSession(): PersistedAgentSession? {
        if (!activeSessionFile.exists()) return null
        return runCatching {
            val content = SafeFileOps.safeReadText(activeSessionFile) ?: return null
            val json = JSONObject(content)
            deserializeSession(json)
        }.getOrNull()
    }

    private fun archiveSession(session: PersistedAgentSession) {
        runCatching {
            val historyDir = File(sessionsDir, "history").apply { mkdirs() }
            val archiveFile = File(historyDir, "session_${session.sessionId}.json")
            val json = serializeSession(session)
            SafeFileOps.atomicWriteText(archiveFile, json.toString(2))
        }
    }

    private fun serializeSession(session: PersistedAgentSession): JSONObject {
        return JSONObject().apply {
            put("sessionId", session.sessionId)
            put("projectId", session.projectId)
            put("projectTitle", session.projectTitle)
            put("chatId", session.chatId)
            put("initialPrompt", session.initialPrompt)
            put("startedAt", session.startedAt)
            put("updatedAt", session.updatedAt)
            put("status", session.status.name)
            put("summary", session.summary)
            session.activeRoadmap?.let { rm ->
                put("roadmap", JSONObject().apply {
                    put("id", rm.id)
                    put("title", rm.title)
                    put("steps", JSONArray().apply {
                        rm.steps.forEach { s ->
                            put(JSONObject().apply {
                                put("id", s.id)
                                put("title", s.title)
                                put("description", s.description)
                                put("status", s.status.name)
                                put("files", JSONArray(s.filesAffected))
                            })
                        }
                    })
                })
            }
        }
    }

    private fun deserializeSession(json: JSONObject): PersistedAgentSession {
        val roadmapJson = json.optJSONObject("roadmap")
        val roadmap = roadmapJson?.let { rm ->
            val stepsArr = rm.optJSONArray("steps") ?: JSONArray()
            val steps = mutableListOf<RoadmapStep>()
            for (i in 0 until stepsArr.length()) {
                val sObj = stepsArr.getJSONObject(i)
                val filesArr = sObj.optJSONArray("files") ?: JSONArray()
                val files = (0 until filesArr.length()).map { filesArr.getString(it) }
                val statusStr = sObj.optString("status", StepStatus.PENDING.name)
                val status = runCatching { StepStatus.valueOf(statusStr) }.getOrDefault(StepStatus.PENDING)
                steps.add(
                    RoadmapStep(
                        id = sObj.getString("id"),
                        title = sObj.getString("title"),
                        description = sObj.optString("description", ""),
                        status = status,
                        filesAffected = files,
                    )
                )
            }
            ActiveRoadmap(
                id = rm.getString("id"),
                title = rm.getString("title"),
                summary = rm.optString("summary", ""),
                steps = steps,
            )
        }

        val statusStr = json.optString("status", AgentSessionStatus.RUNNING.name)
        val status = runCatching { AgentSessionStatus.valueOf(statusStr) }.getOrDefault(AgentSessionStatus.RUNNING)

        return PersistedAgentSession(
            sessionId = json.getString("sessionId"),
            projectId = json.getString("projectId"),
            projectTitle = json.optString("projectTitle", "Project"),
            chatId = json.optString("chatId", ""),
            initialPrompt = json.optString("initialPrompt", ""),
            startedAt = json.optLong("startedAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            status = status,
            activeRoadmap = roadmap,
            summary = json.optString("summary").takeIf { it.isNotBlank() },
        )
    }
}
