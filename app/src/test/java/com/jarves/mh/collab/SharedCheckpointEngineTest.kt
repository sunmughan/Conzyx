package com.jarves.mh.collab

import com.jarves.mh.collab.checkpoint.SharedCheckpointEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedCheckpointEngineTest {

    private lateinit var engine: SharedCheckpointEngine

    @Before
    fun setUp() {
        engine = SharedCheckpointEngine()
    }

    @Test
    fun testCreateCheckpoint() {
        val cp = engine.createCheckpoint(
            title = "Alpha Release Milestone",
            author = "Alice Lead",
            description = "Feature complete before QA review",
            changedFilesCount = 8,
            commitSha = "c0ffee12"
        )

        assertNotNull(cp.id)
        assertEquals("Alpha Release Milestone", cp.title)
        assertEquals("Alice Lead", cp.author)
        assertEquals("Feature complete before QA review", cp.description)
        assertEquals(8, cp.changedFilesCount)
        assertEquals("c0ffee12", cp.commitSha)
        assertEquals(0, cp.restoreCount)

        val retrieved = engine.getCheckpointById(cp.id)
        assertNotNull(retrieved)
        assertEquals(cp.id, retrieved!!.id)
    }

    @Test
    fun testRestoreCheckpointIncrementsCounter() {
        val cp = engine.createCheckpoint("Test Checkpoint", "Author")
        assertEquals(0, cp.restoreCount)

        val restored1 = engine.restoreCheckpoint(cp.id)
        assertNotNull(restored1)
        assertEquals(1, restored1!!.restoreCount)

        val restored2 = engine.restoreCheckpoint(cp.id)
        assertNotNull(restored2)
        assertEquals(2, restored2!!.restoreCount)
    }

    @Test
    fun testRestoreNonExistentCheckpointReturnsNull() {
        val result = engine.restoreCheckpoint("invalid-id")
        assertNull(result)
    }

    @Test
    fun testGetAllCheckpointsSorted() {
        val cp1 = engine.createCheckpoint("First", "User")
        val cp2 = engine.createCheckpoint("Second", "User")

        val all = engine.getAllCheckpoints()
        assertEquals(2, all.size)
        // Ensure sorted descending
        assertTrue(all[0].timestamp >= all[1].timestamp)
    }

    @Test
    fun testDeleteCheckpoint() {
        val cp = engine.createCheckpoint("Temp", "User")
        assertEquals(1, engine.getAllCheckpoints().size)

        val deleted = engine.deleteCheckpoint(cp.id)
        assertTrue(deleted)
        assertTrue(engine.getAllCheckpoints().isEmpty())
    }

    @Test
    fun testClearCheckpoints() {
        engine.createCheckpoint("CP1", "User")
        engine.createCheckpoint("CP2", "User")
        assertEquals(2, engine.getAllCheckpoints().size)

        engine.clearCheckpoints()
        assertTrue(engine.getAllCheckpoints().isEmpty())
    }
}
