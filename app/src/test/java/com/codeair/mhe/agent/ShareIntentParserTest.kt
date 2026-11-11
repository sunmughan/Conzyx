package com.codeair.mhe.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Share-sheet parsing contract: only ACTION_SEND / ACTION_SEND_MULTIPLE
 * count, and a share with neither text nor files is nothing to stage.
 */
class ShareIntentParserTest {

    @Test
    fun `send with text only keeps the text`() {
        val parsed = ShareIntentParser.parse(
            ShareIntentParser.ACTION_SEND,
            "  Fix the login crash  ",
            emptyList(),
        )
        assertEquals(SharedContent("Fix the login crash", emptyList()), parsed)
    }

    @Test
    fun `send with one file keeps the file uri`() {
        val parsed = ShareIntentParser.parse(
            ShareIntentParser.ACTION_SEND,
            null,
            listOf("content://com.example.provider/photos/1"),
        )
        assertEquals(
            SharedContent("", listOf("content://com.example.provider/photos/1")),
            parsed,
        )
    }

    @Test
    fun `send multiple keeps text and every file uri`() {
        val parsed = ShareIntentParser.parse(
            ShareIntentParser.ACTION_SEND_MULTIPLE,
            "Screenshots",
            listOf("content://a/1", "content://a/2"),
        )
        assertEquals(
            SharedContent("Screenshots", listOf("content://a/1", "content://a/2")),
            parsed,
        )
    }

    @Test
    fun `blank text and blank uris parse to nothing`() {
        assertNull(
            ShareIntentParser.parse(ShareIntentParser.ACTION_SEND, "   ", listOf("", "  ")),
        )
    }

    @Test
    fun `unknown action parses to nothing`() {
        assertNull(
            ShareIntentParser.parse(
                "android.intent.action.VIEW",
                "hello",
                listOf("content://a/1"),
            ),
        )
    }

    @Test
    fun `duplicate uris are kept once`() {
        val parsed = ShareIntentParser.parse(
            ShareIntentParser.ACTION_SEND_MULTIPLE,
            null,
            listOf("content://a/1", "content://a/1"),
        )
        assertEquals(SharedContent("", listOf("content://a/1")), parsed)
    }
}
