package com.codeair.mhe.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionWatchdogWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `empty chats dir counts zero`() {
        val chats = tempFolder.newFolder("chats")
        assertEquals(0, countQueuedPrompts(chats))
    }

    @Test
    fun `missing chats dir counts zero`() {
        assertEquals(0, countQueuedPrompts(File(tempFolder.root, "nope")))
    }

    @Test
    fun `counts prompts across projects, ignores empty queues and other files`() {
        val chats = tempFolder.newFolder("chats")
        val p1 = File(chats, "proj-1").apply { mkdirs() }
        File(p1, "chat-a.queued.json").writeText(
            """[{"id":"1","text":"a","mode":"AGENT","createdAtMillis":1}]""",
        )
        File(p1, "chat-b.queued.json").writeText("[]")
        File(p1, "chat-a.json").writeText("[]")
        val p2 = File(chats, "proj-2").apply { mkdirs() }
        File(p2, "chat-c.queued.json").writeText(
            """[{"id":"1","text":"a","mode":"AGENT","createdAtMillis":1},{"id":"2","text":"b","mode":"AGENT","createdAtMillis":2}]""",
        )
        File(p2, "chat-c.queued.json.tmp").writeText(
            """[{"id":"9","text":"x","mode":"AGENT","createdAtMillis":9}]""",
        )
        assertEquals(3, countQueuedPrompts(chats))
    }

    @Test
    fun `shouldRemind only when something waits`() {
        assertFalse(shouldRemind(0, 0))
        assertTrue(shouldRemind(1, 0))
        assertTrue(shouldRemind(0, 2))
        assertTrue(shouldRemind(1, 1))
    }
}
