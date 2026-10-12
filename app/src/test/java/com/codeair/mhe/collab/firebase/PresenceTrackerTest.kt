package com.codeair.mhe.collab.firebase

import com.codeair.mhe.core.result.OperationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTrackerTest {

    private var now = 10_000L
    private fun tracker(gw: InMemoryFirestoreGateway, uid: String?) = PresenceTracker(
        gateway = gw,
        identity = FirebaseCollaborationIdentity(uidProvider = { uid }),
        clock = { now }
    )

    @Test
    fun `heartbeat writes presence document`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        val result = tracker(gw, "u1").heartbeat("ws1", "Ada", "Pixel 8")
        assertEquals(OperationStatus.SUCCESS, result.status)
        val stored = gw.getDocument(FirestorePaths.presenceDoc("ws1", "u1"))!!
        assertEquals("Ada", stored["displayName"])
        assertEquals(10_000L, stored["lastHeartbeat"])
    }

    @Test
    fun `heartbeat refuses when signed out`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        val result = tracker(gw, null).heartbeat("ws1", "Ada", "Pixel 8")
        assertEquals(OperationStatus.NOT_CONFIGURED, result.status)
        assertTrue(gw.listCollection(FirestorePaths.presence("ws1")).isEmpty())
    }

    @Test
    fun `listActive returns only fresh heartbeats`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        val t = tracker(gw, "u1")
        t.heartbeat("ws1", "Ada", "Pixel")
        now = 10_000L + 60_000L // 1 minute later: still fresh (staleAfter = 2 min)
        tracker(gw, "u2").heartbeat("ws1", "Bo", "OnePlus")
        now = 150_000L // Ada's heartbeat now 140s old (stale), Bo's 80s old (fresh)
        val active = t.listActive("ws1")
        assertEquals(1, active.size)
        assertEquals("u2", active[0].uid)
    }

    @Test
    fun `clearPresence removes own heartbeat`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        val t = tracker(gw, "u1")
        t.heartbeat("ws1", "Ada", "Pixel")
        val result = t.clearPresence("ws1")
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(0, t.listActive("ws1").size)
    }

    @Test
    fun `listActive sorts by most recent heartbeat`() = runBlocking {
        val gw = InMemoryFirestoreGateway()
        tracker(gw, "u1").heartbeat("ws1", "Ada", "Pixel")
        now += 5_000L
        tracker(gw, "u2").heartbeat("ws1", "Bo", "OnePlus")
        val active = tracker(gw, "u1").listActive("ws1")
        assertEquals(listOf("u2", "u1"), active.map { it.uid })
    }
}
