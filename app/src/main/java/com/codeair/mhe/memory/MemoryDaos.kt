package com.codeair.mhe.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: Project)

    @Query("SELECT * FROM projects WHERE projectId = :projectId")
    suspend fun getById(projectId: String): Project?

    @Query("SELECT * FROM projects ORDER BY updatedAtMillis DESC")
    suspend fun listAll(): List<Project>

    @Query("DELETE FROM projects WHERE projectId = :projectId")
    suspend fun deleteById(projectId: String)
}

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: Conversation)

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId")
    suspend fun getById(conversationId: String): Conversation?

    @Query("SELECT * FROM conversations WHERE projectId = :projectId ORDER BY updatedAtMillis DESC")
    suspend fun listByProject(projectId: String): List<Conversation>

    @Query("DELETE FROM conversations WHERE conversationId = :conversationId")
    suspend fun deleteById(conversationId: String)
}

@Dao
interface ConversationEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ConversationEvent): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<ConversationEvent>): List<Long>

    @Query("SELECT * FROM conversation_events WHERE conversationId = :conversationId ORDER BY sequence ASC")
    suspend fun listByConversation(conversationId: String): List<ConversationEvent>

    @Query("SELECT * FROM conversation_events WHERE conversationId = :conversationId ORDER BY sequence DESC LIMIT :limit")
    suspend fun latestByConversation(conversationId: String, limit: Int): List<ConversationEvent>

    @Query("SELECT COALESCE(MAX(sequence), -1) FROM conversation_events WHERE conversationId = :conversationId")
    suspend fun maxSequence(conversationId: String): Long

    @Query("SELECT COUNT(*) FROM conversation_events WHERE conversationId = :conversationId")
    suspend fun countByConversation(conversationId: String): Int
}

@Dao
interface MemoryRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: MemoryRecord)

    @Query("SELECT * FROM memory_records WHERE recordId = :recordId")
    suspend fun getById(recordId: String): MemoryRecord?

    @Query("SELECT * FROM memory_records WHERE scopeType = :scopeType AND scopeId = :scopeId ORDER BY updatedAtMillis DESC")
    suspend fun listByScope(scopeType: String, scopeId: String): List<MemoryRecord>

    @Query("DELETE FROM memory_records WHERE recordId = :recordId")
    suspend fun deleteById(recordId: String)
}

@Dao
interface TaskRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskRecord)

    @Update
    suspend fun update(task: TaskRecord)

    @Query("SELECT * FROM tasks WHERE taskId = :taskId")
    suspend fun getById(taskId: String): TaskRecord?

    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY createdAtMillis DESC")
    suspend fun listByProject(projectId: String): List<TaskRecord>

    @Query("SELECT * FROM tasks WHERE conversationId = :conversationId ORDER BY createdAtMillis DESC")
    suspend fun listByConversation(conversationId: String): List<TaskRecord>

    @Query("SELECT * FROM tasks WHERE status NOT IN ('completed', 'failed', 'cancelled') ORDER BY updatedAtMillis DESC")
    suspend fun listOpen(): List<TaskRecord>
}

@Dao
interface ExecutionRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: ExecutionRun)

    @Update
    suspend fun update(run: ExecutionRun)

    @Query("SELECT * FROM execution_runs WHERE runId = :runId")
    suspend fun getById(runId: String): ExecutionRun?

    @Query("SELECT * FROM execution_runs WHERE taskId = :taskId ORDER BY attempt DESC, createdAtMillis DESC")
    suspend fun listByTask(taskId: String): List<ExecutionRun>

    @Query("SELECT * FROM execution_runs WHERE taskId = :taskId ORDER BY attempt DESC LIMIT 1")
    suspend fun latestByTask(taskId: String): ExecutionRun?
}

@Dao
interface ActionEffectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(effect: ActionEffect)

    @Query("SELECT * FROM action_effects WHERE effectId = :effectId")
    suspend fun getById(effectId: String): ActionEffect?

    @Query("SELECT * FROM action_effects WHERE runId = :runId ORDER BY createdAtMillis ASC")
    suspend fun listByRun(runId: String): List<ActionEffect>

    @Query("SELECT * FROM action_effects WHERE outcomeStatus = 'unknown' ORDER BY createdAtMillis ASC")
    suspend fun listUnreconciled(): List<ActionEffect>
}

@Dao
interface AppKnowledgeVersionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(version: AppKnowledgeVersion)

    @Query("SELECT * FROM app_knowledge_versions WHERE appKnowledgeId = :appKnowledgeId")
    suspend fun getById(appKnowledgeId: String): AppKnowledgeVersion?

    @Query("SELECT * FROM app_knowledge_versions WHERE packageName = :packageName AND status = 'published' ORDER BY publishedAtMillis DESC LIMIT 1")
    suspend fun latestPublished(packageName: String): AppKnowledgeVersion?

    @Query("SELECT * FROM app_knowledge_versions WHERE packageName = :packageName ORDER BY createdAtMillis DESC")
    suspend fun listByPackage(packageName: String): List<AppKnowledgeVersion>
}

@Dao
interface ScreenNodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: ScreenNode)

    @Query("SELECT * FROM screen_nodes WHERE appKnowledgeId = :appKnowledgeId AND screenKey = :screenKey")
    suspend fun getByKey(appKnowledgeId: String, screenKey: String): ScreenNode?

    @Query("SELECT * FROM screen_nodes WHERE appKnowledgeId = :appKnowledgeId")
    suspend fun listByVersion(appKnowledgeId: String): List<ScreenNode>
}

@Dao
interface FeatureCapabilityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(capability: FeatureCapability)

    @Query("SELECT * FROM feature_capabilities WHERE appKnowledgeId = :appKnowledgeId")
    suspend fun listByVersion(appKnowledgeId: String): List<FeatureCapability>

    @Query("SELECT * FROM feature_capabilities WHERE appKnowledgeId = :appKnowledgeId AND featureKey = :featureKey")
    suspend fun getByKey(appKnowledgeId: String, featureKey: String): FeatureCapability?
}

@Dao
interface ActionRecipeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recipe: ActionRecipe)

    @Query("SELECT * FROM action_recipes WHERE recipeId = :recipeId")
    suspend fun getById(recipeId: String): ActionRecipe?

    @Query("SELECT * FROM action_recipes WHERE appKnowledgeId = :appKnowledgeId ORDER BY version DESC")
    suspend fun listByVersion(appKnowledgeId: String): List<ActionRecipe>
}

@Dao
interface InstalledAppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: InstalledApp)

    @Query("SELECT * FROM installed_apps WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): InstalledApp?

    @Query("SELECT * FROM installed_apps ORDER BY label ASC")
    suspend fun listAll(): List<InstalledApp>

    @Query("UPDATE installed_apps SET registryGeneration = :generation WHERE registryGeneration < :generation")
    suspend fun markStaleGenerations(generation: Long)

    @Query("DELETE FROM installed_apps WHERE registryGeneration < :generation")
    suspend fun deleteStaleGenerations(generation: Long)
}

@Dao
interface ObservationRefDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ref: ObservationRef)

    @Query("SELECT * FROM observation_refs WHERE observationId = :observationId")
    suspend fun getById(observationId: String): ObservationRef?

    @Query("SELECT * FROM observation_refs WHERE packageName = :packageName ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun latestByPackage(packageName: String, limit: Int): List<ObservationRef>
}

@Dao
interface ApprovalRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ApprovalRecord)

    @Query("SELECT * FROM approval_records WHERE approvalId = :approvalId")
    suspend fun getById(approvalId: String): ApprovalRecord?

    @Query("UPDATE approval_records SET consumed = 1 WHERE approvalId = :approvalId")
    suspend fun markConsumed(approvalId: String)

    @Query("SELECT * FROM approval_records WHERE runId = :runId ORDER BY createdAtMillis ASC")
    suspend fun listByRun(runId: String): List<ApprovalRecord>
}
