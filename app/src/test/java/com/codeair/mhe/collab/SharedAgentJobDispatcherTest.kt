package com.codeair.mhe.collab

import com.codeair.mhe.collab.agent.SharedAgentJobDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedAgentJobDispatcherTest {

    private lateinit var dispatcher: SharedAgentJobDispatcher

    @Before
    fun setUp() {
        dispatcher = SharedAgentJobDispatcher()
    }

    @Test
    fun testDispatchJob() {
        val job = dispatcher.dispatchJob(
            sessionShareId = "session-abc",
            initiatedBy = "Alice",
            prompt = "Scaffold authentication screen with biometric login",
            assignedRole = "Security Engineer"
        )

        assertNotNull(job.id)
        assertEquals("session-abc", job.sessionShareId)
        assertEquals("Alice", job.initiatedBy)
        assertEquals("Security Engineer", job.assignedRole)
        assertEquals("RUNNING", job.status)
        assertTrue(job.logs.isNotEmpty())

        val retrieved = dispatcher.getJobById(job.id)
        assertNotNull(retrieved)
        assertEquals(job.id, retrieved!!.id)
    }

    @Test
    fun testAppendLog() {
        val job = dispatcher.dispatchJob("sess", "Bob", "Build app")
        val initialLogCount = job.logs.size

        dispatcher.appendLog(job.id, "Generated BiometricPrompt.kt")
        val updated = dispatcher.getJobById(job.id)

        assertNotNull(updated)
        assertEquals(initialLogCount + 1, updated!!.logs.size)
        assertTrue(updated.logs.last().contains("BiometricPrompt.kt"))
    }

    @Test
    fun testRequestApprovalAndApprove() {
        val job = dispatcher.dispatchJob("sess", "Charlie", "Delete test directory")

        val requested = dispatcher.requestApproval(job.id, "Delete /workspace/tests folder")
        assertTrue(requested)

        val gated = dispatcher.getJobById(job.id)
        assertEquals("REQUIRES_APPROVAL", gated?.status)
        assertEquals("Delete /workspace/tests folder", gated?.pendingApprovalAction)

        val approved = dispatcher.approveJob(job.id, "Admin Owner")
        assertTrue(approved)

        val resumed = dispatcher.getJobById(job.id)
        assertEquals("RUNNING", resumed?.status)
        assertNull(resumed?.pendingApprovalAction)
    }

    @Test
    fun testRejectApprovalCancelsJob() {
        val job = dispatcher.dispatchJob("sess", "David", "Dangerous action")
        dispatcher.requestApproval(job.id, "Force rewrite git history")

        val rejected = dispatcher.rejectJob(job.id, "Security Reviewer")
        assertTrue(rejected)

        val cancelled = dispatcher.getJobById(job.id)
        assertEquals("CANCELLED", cancelled?.status)
        assertNull(cancelled?.pendingApprovalAction)
    }

    @Test
    fun testCompleteAndFailJob() {
        val j1 = dispatcher.dispatchJob("sess", "User", "Task 1")
        dispatcher.completeJob(j1.id)
        assertEquals("COMPLETED", dispatcher.getJobById(j1.id)?.status)

        val j2 = dispatcher.dispatchJob("sess", "User", "Task 2")
        dispatcher.failJob(j2.id, "Out of disk space")
        assertEquals("FAILED", dispatcher.getJobById(j2.id)?.status)
        assertTrue(dispatcher.getJobById(j2.id)?.logs?.last()?.contains("Out of disk space") == true)
    }

    @Test
    fun testGetActiveJobs() {
        val j1 = dispatcher.dispatchJob("sess", "User", "Task 1")
        val j2 = dispatcher.dispatchJob("sess", "User", "Task 2")
        dispatcher.requestApproval(j2.id, "Action")

        val j3 = dispatcher.dispatchJob("sess", "User", "Task 3")
        dispatcher.completeJob(j3.id)

        val active = dispatcher.getActiveJobs()
        assertEquals(2, active.size)
        assertTrue(active.any { it.id == j1.id })
        assertTrue(active.any { it.id == j2.id })
        assertFalse(active.any { it.id == j3.id })
    }
}
