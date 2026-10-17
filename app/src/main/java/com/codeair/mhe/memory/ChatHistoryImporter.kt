package com.codeair.mhe.memory

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

object ChatHistoryImporter {
    private const val PREFS_NAME = "conzyx_memory"
    private const val KEY_IMPORT_DONE = "chat_import_v1_done"

    data class ImportResult(
        val alreadyDone: Boolean,
        val projects: Int = 0,
        val conversations: Int = 0,
        val events: Int = 0,
    )

    suspend fun importOnce(context: Context): ImportResult = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IMPORT_DONE, false)) {
            return@withContext ImportResult(alreadyDone = true)
        }
        val db = MemoryDatabase.get(context)
        val projectTitles = readProjectTitles(context)
        val chatsDir = File(context.filesDir, "chats")
        var projectCount = 0
        var conversationCount = 0
        var eventCount = 0
        db.withTransaction {
            if (chatsDir.isDirectory) {
                chatsDir.listFiles()?.forEach { projectDir ->
                    if (projectDir.isDirectory) {
                        val imported = importProjectDir(
                            db,
                            projectDir,
                            projectTitles[projectDir.name] ?: projectDir.name,
                        )
                        projectCount += 1
                        conversationCount += imported.first
                        eventCount += imported.second
                    } else if (projectDir.name.endsWith(".json") && !projectDir.name.startsWith(".")) {
                        val projectId = projectDir.name.removeSuffix(".json")
                        val imported = importLegacyProjectFile(
                            db,
                            projectId,
                            projectDir,
                            projectTitles[projectId] ?: projectId,
                        )
                        projectCount += 1
                        conversationCount += imported.first
                        eventCount += imported.second
                    }
                }
            }
        }
        prefs.edit().putBoolean(KEY_IMPORT_DONE, true).apply()
        ImportResult(
            alreadyDone = false,
            projects = projectCount,
            conversations = conversationCount,
            events = eventCount,
        )
    }

    private suspend fun importProjectDir(
        db: MemoryDatabase,
        projectDir: File,
        projectTitle: String,
    ): Pair<Int, Int> {
        val projectId = projectDir.name
        ensureProject(db, projectId, projectTitle)
        var conversationCount = 0
        var eventCount = 0
        val indexFile = File(projectDir, "index.json")
        val indexed = readIndex(indexFile)
        indexed.forEach { meta ->
            db.conversationDao().upsert(
                Conversation(
                    conversationId = meta.id,
                    projectId = projectId,
                    title = meta.title,
                    createdAtMillis = meta.createdAtMillis,
                    updatedAtMillis = meta.updatedAtMillis,
                ),
            )
            conversationCount += 1
            eventCount += importMessageFile(db, File(projectDir, "${meta.id}.json"), meta.id)
        }
        val indexedIds = indexed.map { it.id }.toSet()
        projectDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name != "index.json" && !it.name.startsWith(".") }
            ?.forEach { messageFile ->
                val chatId = messageFile.name.removeSuffix(".json")
                if (chatId !in indexedIds) {
                    db.conversationDao().upsert(
                        Conversation(
                            conversationId = chatId,
                            projectId = projectId,
                            title = chatId,
                        ),
                    )
                    conversationCount += 1
                    eventCount += importMessageFile(db, messageFile, chatId)
                }
            }
        return conversationCount to eventCount
    }

    private suspend fun importLegacyProjectFile(
        db: MemoryDatabase,
        projectId: String,
        file: File,
        projectTitle: String,
    ): Pair<Int, Int> {
        ensureProject(db, projectId, projectTitle)
        db.conversationDao().upsert(
            Conversation(
                conversationId = "main",
                projectId = projectId,
                title = "Main chat",
            ),
        )
        val events = importMessageFile(db, file, "main")
        return 1 to events
    }

    private suspend fun ensureProject(db: MemoryDatabase, projectId: String, title: String) {
        if (db.projectDao().getById(projectId) == null) {
            db.projectDao().upsert(Project(projectId = projectId, title = title))
        }
    }

    private suspend fun importMessageFile(db: MemoryDatabase, file: File, conversationId: String): Int {
        if (!file.isFile) return 0
        val events = runCatching {
            val arr = JSONArray(file.readText())
            val baseSequence = db.conversationEventDao().maxSequence(conversationId) + 1
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val fromUser = obj.optBoolean("fromUser", false)
                val createdAt = obj.optString("createdAt")
                val timestamp = runCatching { Instant.parse(createdAt).toEpochMilli() }
                    .getOrDefault(file.lastModified())
                ConversationEvent(
                    eventId = id,
                    conversationId = conversationId,
                    sequence = baseSequence + i,
                    role = if (fromUser) "user" else "assistant",
                    eventType = "message",
                    payloadJson = obj.toString(),
                    timestampMillis = timestamp,
                )
            }
        }.getOrDefault(emptyList())
        if (events.isEmpty()) return 0
        db.conversationEventDao().insertAll(events)
        return events.size
    }

    private data class IndexMeta(
        val id: String,
        val title: String,
        val createdAtMillis: Long,
        val updatedAtMillis: Long,
    )

    private fun readIndex(indexFile: File): List<IndexMeta> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            val now = System.currentTimeMillis()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").ifBlank { return@mapNotNull null }
                IndexMeta(
                    id = id,
                    title = obj.optString("title", "Chat"),
                    createdAtMillis = obj.optLong("createdAtMillis", now),
                    updatedAtMillis = obj.optLong("updatedAtMillis", now),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun readProjectTitles(context: Context): Map<String, String> {
        return runCatching {
            val prefs = context.getSharedPreferences("pocket_preferences", Context.MODE_PRIVATE)
            val raw = prefs.getString("projects_json", null) ?: return@runCatching emptyMap<String, String>()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").ifBlank { return@mapNotNull null }
                id to obj.optString("name", id)
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
