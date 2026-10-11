package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RuntimeOverlayTransactionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `overlay atomically updates files and preserves symlink targets`() {
        val parent = tempFolder.newFolder("overlay")
        val staging = File(parent, "staging").apply { mkdirs() }
        val rootfs = File(parent, "rootfs").apply { mkdirs() }
        val backup = File(parent, "backup")
        File(staging, "usr/bin").mkdirs()
        File(staging, "usr/bin/tool").writeText("new tool")
        File(staging, "usr/bin/current").toPath().also {
            Files.createSymbolicLink(it, java.nio.file.Paths.get("tool"))
        }
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/tool").writeText("old tool")

        assertTrue(RuntimeOverlayTransaction.apply(staging, rootfs, backup))

        assertEquals("new tool", File(rootfs, "usr/bin/tool").readText())
        assertTrue(Files.isSymbolicLink(File(rootfs, "usr/bin/current").toPath()))
        assertEquals("tool", Files.readSymbolicLink(File(rootfs, "usr/bin/current").toPath()).toString())
        assertFalse(backup.exists())
    }

    @Test
    fun `overlay rejects escaping symlink before changing existing files`() {
        val parent = tempFolder.newFolder("unsafe-overlay")
        val staging = File(parent, "staging").apply { mkdirs() }
        val rootfs = File(parent, "rootfs").apply { mkdirs() }
        val backup = File(parent, "backup")
        File(staging, "tool").writeText("new")
        Files.createSymbolicLink(File(staging, "escape").toPath(), java.nio.file.Paths.get("../../outside"))
        val existing = File(rootfs, "tool").apply { writeText("known good") }

        assertFalse(RuntimeOverlayTransaction.apply(staging, rootfs, backup))
        assertEquals("known good", existing.readText())
        assertFalse(backup.exists())
    }
}
