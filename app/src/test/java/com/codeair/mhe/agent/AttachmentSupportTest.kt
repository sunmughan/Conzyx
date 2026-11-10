package com.codeair.mhe.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attachment type gate: archives (zip/rar/7z) must be attachable so project
 * patches can be picked from internal storage and handed to the agent,
 * while images, PDFs and text/log files keep working and executables stay out.
 */
class AttachmentSupportTest {

    private val controller = AgentChatController()

    @Test
    fun `zip archive by mime type is supported`() {
        assertTrue(controller.isSupportedAttachment("application/zip", "production-patch-frontend-pages.zip"))
    }

    @Test
    fun `zip archive by extension is supported`() {
        assertTrue(controller.isSupportedAttachment("application/octet-stream", "patch.zip"))
    }

    @Test
    fun `zip with x-zip-compressed mime is supported`() {
        assertTrue(controller.isSupportedAttachment("application/x-zip-compressed", "patch.zip"))
    }

    @Test
    fun `rar archive by mime type is supported`() {
        assertTrue(controller.isSupportedAttachment("application/vnd.rar", "archive.rar"))
        assertTrue(controller.isSupportedAttachment("application/x-rar-compressed", "archive.rar"))
    }

    @Test
    fun `rar archive by extension is supported`() {
        assertTrue(controller.isSupportedAttachment("application/octet-stream", "archive.rar"))
    }

    @Test
    fun `7z archive by mime type is supported`() {
        assertTrue(controller.isSupportedAttachment("application/x-7z-compressed", "archive.7z"))
    }

    @Test
    fun `7z archive by extension is supported`() {
        assertTrue(controller.isSupportedAttachment("application/octet-stream", "archive.7z"))
    }

    @Test
    fun `archive extension match is case-insensitive`() {
        assertTrue(controller.isSupportedAttachment("application/octet-stream", "Patch.ZIP"))
        assertTrue(controller.isSupportedAttachment("application/octet-stream", "Patch.RAR"))
        assertTrue(controller.isSupportedAttachment("application/octet-stream", "Patch.7Z"))
    }

    @Test
    fun `images pdf and text stay supported`() {
        assertTrue(controller.isSupportedAttachment("image/png", "photo.png"))
        assertTrue(controller.isSupportedAttachment("application/pdf", "doc.pdf"))
        assertTrue(controller.isSupportedAttachment("text/plain", "notes.txt"))
        assertTrue(controller.isSupportedAttachment("application/octet-stream", "MainActivity.kt"))
    }

    @Test
    fun `executable files stay rejected`() {
        assertFalse(controller.isSupportedAttachment("application/x-msdownload", "setup.exe"))
        assertFalse(controller.isSupportedAttachment("application/x-executable", "tool.bin"))
        assertFalse(controller.isSupportedAttachment("application/vnd.android.package-archive", "app.apk"))
    }
}
