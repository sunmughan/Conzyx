package com.codeair.mhe.memory

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey val projectId: String,
    val title: String,
    val instructions: String = "",
    val status: String = "active",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["projectId"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["projectId", "updatedAtMillis"]),
    ],
)
data class Conversation(
    @PrimaryKey val conversationId: String,
    val projectId: String?,
    val title: String,
    val summary: String = "",
    val summaryRevision: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "conversation_events",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["conversationId"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversationId", "sequence"], unique = true),
        Index(value = ["conversationId", "timestampMillis"]),
    ],
)
data class ConversationEvent(
    @PrimaryKey val eventId: String,
    val conversationId: String,
    val sequence: Long,
    val role: String,
    val eventType: String = "message",
    val payloadJson: String = "",
    val timestampMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "memory_records",
    indices = [
        Index(value = ["scopeType", "scopeId", "updatedAtMillis"]),
    ],
)
data class MemoryRecord(
    @PrimaryKey val recordId: String,
    val scopeType: String,
    val scopeId: String,
    val content: String,
    val provenance: String = "",
    val sensitivity: String = "internal",
    val revision: Int = 1,
    val supersedesId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["projectId"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["conversationId"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["projectId", "createdAtMillis"]),
        Index(value = ["conversationId", "createdAtMillis"]),
    ],
)
data class TaskRecord(
    @PrimaryKey val taskId: String,
    val projectId: String?,
    val conversationId: String?,
    val targetApp: String = "",
    val title: String = "",
    val status: String = "pending",
    val currentRunId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "execution_runs",
    foreignKeys = [
        ForeignKey(
            entity = TaskRecord::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskId", "createdAtMillis"]),
    ],
)
data class ExecutionRun(
    @PrimaryKey val runId: String,
    val taskId: String,
    val state: String = "created",
    val checkpointJson: String = "",
    val attempt: Int = 1,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val finishedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "action_effects",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionRun::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["runId", "stepId"]),
    ],
)
data class ActionEffect(
    @PrimaryKey val effectId: String,
    val runId: String,
    val stepId: String = "",
    val actionType: String = "",
    val dispatchStatus: String = "pending",
    val outcomeStatus: String = "unknown",
    val approvalId: String? = null,
    val detailJson: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "app_knowledge_versions",
    indices = [
        Index(value = ["packageName", "appVersion", "status"]),
    ],
)
data class AppKnowledgeVersion(
    @PrimaryKey val appKnowledgeId: String,
    val packageName: String,
    val appVersion: String = "",
    val schemaVersion: Int = 1,
    val status: String = "draft",
    val publishedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "screen_nodes",
    foreignKeys = [
        ForeignKey(
            entity = AppKnowledgeVersion::class,
            parentColumns = ["appKnowledgeId"],
            childColumns = ["appKnowledgeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["appKnowledgeId", "screenKey"], unique = true),
    ],
)
data class ScreenNode(
    @PrimaryKey val nodeId: String,
    val appKnowledgeId: String,
    val screenKey: String,
    val semanticFingerprint: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "feature_capabilities",
    foreignKeys = [
        ForeignKey(
            entity = AppKnowledgeVersion::class,
            parentColumns = ["appKnowledgeId"],
            childColumns = ["appKnowledgeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["appKnowledgeId", "featureKey"]),
    ],
)
data class FeatureCapability(
    @PrimaryKey val capabilityId: String,
    val appKnowledgeId: String,
    val featureKey: String,
    val operationClass: String = "",
    val executionMode: String = "direct",
    val confidence: Double = 0.0,
    val evidenceIdsJson: String = "[]",
    val verificationState: String = "unknown",
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "action_recipes",
    foreignKeys = [
        ForeignKey(
            entity = AppKnowledgeVersion::class,
            parentColumns = ["appKnowledgeId"],
            childColumns = ["appKnowledgeId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["appKnowledgeId"]),
    ],
)
data class ActionRecipe(
    @PrimaryKey val recipeId: String,
    val appKnowledgeId: String?,
    val title: String = "",
    val preconditionsJson: String = "[]",
    val stepsJson: String = "[]",
    val postconditionsJson: String = "[]",
    val risk: String = "low",
    val version: Int = 1,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "installed_apps")
data class InstalledApp(
    @PrimaryKey val packageName: String,
    val label: String = "",
    val versionName: String = "",
    val versionCode: Long = 0L,
    val launchable: Boolean = false,
    val registryGeneration: Long = 0L,
    val lastSeenAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "observation_refs",
    indices = [
        Index(value = ["packageName", "timestampMillis"]),
    ],
)
data class ObservationRef(
    @PrimaryKey val observationId: String,
    val packageName: String = "",
    val window: String = "",
    val generation: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val modality: String = "accessibility",
    val redactionNote: String = "",
    val evidenceRef: String = "",
)

@Entity(
    tableName = "approval_records",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionRun::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["runId", "createdAtMillis"]),
    ],
)
data class ApprovalRecord(
    @PrimaryKey val approvalId: String,
    val runId: String,
    val stepId: String = "",
    val operationDescribe: String = "",
    val parametersHash: String = "",
    val targetPackage: String = "",
    val expiresAtMillis: Long = 0L,
    val consumed: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
