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
    fun `overlay refuses to overwrite a retained recovery backup`() {
        val parent = tempFolder.newFolder("retained-overlay-backup")
        val staging = File(parent, "staging").apply { mkdirs() }
        val rootfs = File(parent, "rootfs").apply { mkdirs() }
        val backup = File(parent, "backup").apply { mkdirs() }
        File(staging, "tool").writeText("new")
        File(rootfs, "tool").writeText("old")
        File(backup, "recovery.txt").writeText("keep me")

        assertFalse(RuntimeOverlayTransaction.apply(staging, rootfs, backup))
        assertEquals("old", File(rootfs, "tool").readText())
        assertEquals("keep me", File(backup, "recovery.txt").readText())
    }

    @Test
    fun `overlay restores earlier files when a later write fails`() {
        val parent = tempFolder.newFolder("rollback-overlay")
        val staging = File(parent, "staging").apply { mkdirs() }
        val rootfs = File(parent, "rootfs").apply { mkdirs() }
        val backup = File(parent, "backup")
        File(staging, "a.txt").writeText("new A")
        File(staging, "b.txt").writeText("new B")
        File(rootfs, "a.txt").writeText("old A")
        File(rootfs, "b.txt").writeText("old B")
        var writes = 0

        val success = RuntimeOverlayTransaction.apply(staging, rootfs, backup) { source, destination ->
            writes++
            if (writes == 2) false else com.jarves.mh.workspace.SafeFileOps.atomicCopy(source, destination)
        }

        assertFalse(success)
        assertEquals("old A", File(rootfs, "a.txt").readText())
        assertEquals("old B", File(rootfs, "b.txt").readText())
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
