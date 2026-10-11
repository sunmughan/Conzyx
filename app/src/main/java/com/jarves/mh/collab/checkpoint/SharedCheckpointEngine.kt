package com.jarves.mh.collab.checkpoint

import com.jarves.mh.collab.model.SharedCheckpoint
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared Workspace Checkpoint & Snapshot Engine (Phase 13).
 * Captures collaborative state milestones, tracks author history, and supports milestone restorations.
 */
class SharedCheckpointEngine {

    private val checkpointsMap = ConcurrentHashMap<String, SharedCheckpoint>()
    private val _checkpointsFlow = MutableStateFlow<List<SharedCheckpoint>>(emptyList())
    val checkpointsFlow: StateFlow<List<SharedCheckpoint>> = _checkpointsFlow.asStateFlow()

    fun createCheckpoint(
        title: String,
        author: String,
        description: String = "",
        changedFilesCount: Int = 0,
        commitSha: String? = null,
        manifestHash: String = ""
    ): SharedCheckpoint {
        val checkpoint = SharedCheckpoint(
            id = UUID.randomUUID().toString(),
            title = title,
            author = author,
            description = description,
            commitSha = commitSha,
            changedFilesCount = changedFilesCount,
            manifestHash = manifestHash.ifBlank { UUID.randomUUID().toString().take(12) },
            timestamp = System.currentTimeMillis()
        )
        checkpointsMap[checkpoint.id] = checkpoint
        syncFlow()
        return checkpoint
    }

    fun restoreCheckpoint(checkpointId: String): SharedCheckpoint? {
        val current = checkpointsMap[checkpointId] ?: return null
        val updated = current.copy(restoreCount = current.restoreCount + 1)
        checkpointsMap[checkpointId] = updated
        syncFlow()
        return updated
    }

    fun deleteCheckpoint(checkpointId: String): Boolean {
        val removed = checkpointsMap.remove(checkpointId) != null
        if (removed) {
            syncFlow()
        }
        return removed
    }

    fun getCheckpointById(checkpointId: String): SharedCheckpoint? = checkpointsMap[checkpointId]

    fun getAllCheckpoints(): List<SharedCheckpoint> =
        checkpointsMap.values.sortedByDescending { it.timestamp }

    fun loadCheckpoints(checkpoints: List<SharedCheckpoint>) {
        checkpointsMap.clear()
        checkpoints.forEach { checkpointsMap[it.id] = it }
        syncFlow()
    }

    fun clearCheckpoints() {
        checkpointsMap.clear()
        syncFlow()
    }

    private fun syncFlow() {
        _checkpointsFlow.value = checkpointsMap.values.sortedByDescending { it.timestamp }
    }
}
