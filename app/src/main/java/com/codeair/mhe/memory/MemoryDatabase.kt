package com.codeair.mhe.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Project::class,
        Conversation::class,
        ConversationEvent::class,
        MemoryRecord::class,
        TaskRecord::class,
        ExecutionRun::class,
        ActionEffect::class,
        AppKnowledgeVersion::class,
        ScreenNode::class,
        FeatureCapability::class,
        ActionRecipe::class,
        InstalledApp::class,
        ObservationRef::class,
        ApprovalRecord::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun conversationDao(): ConversationDao
    abstract fun conversationEventDao(): ConversationEventDao
    abstract fun memoryRecordDao(): MemoryRecordDao
    abstract fun taskRecordDao(): TaskRecordDao
    abstract fun executionRunDao(): ExecutionRunDao
    abstract fun actionEffectDao(): ActionEffectDao
    abstract fun appKnowledgeVersionDao(): AppKnowledgeVersionDao
    abstract fun screenNodeDao(): ScreenNodeDao
    abstract fun featureCapabilityDao(): FeatureCapabilityDao
    abstract fun actionRecipeDao(): ActionRecipeDao
    abstract fun installedAppDao(): InstalledAppDao
    abstract fun observationRefDao(): ObservationRefDao
    abstract fun approvalRecordDao(): ApprovalRecordDao

    companion object {
        private const val DB_NAME = "conzyx_memory.db"

        @Volatile
        private var instance: MemoryDatabase? = null

        fun get(context: Context): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    DB_NAME,
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
