package com.jarves.mh.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafeFileOpsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `atomicWriteText writes content cleanly and creates parent directories`() {
        val target = File(tempFolder.root, "nested/dir/config.json")
        val content = """{"name": "test-project"}"""

        assertTrue(SafeFileOps.atomicWriteText(target, content))
        assertTrue(target.exists())
        assertEquals(content, target.readText())
    }

    @Test
    fun `atomic write preserves Unicode and replaces existing content`() {
        val target = File(tempFolder.root, "nested/notes.txt")
        assertTrue(SafeFileOps.atomicWriteText(target, "initial"))
        val content = "Hello, नमस्ते, 🌍"

        assertTrue(SafeFileOps.atomicWriteText(target, content))
        assertEquals(content, target.readText(Charsets.UTF_8))
        assertFalse(target.parentFile!!.listFiles().orEmpty().any { it.name.contains(".tmp_") })
    }

    @Test
    fun `atomicWriteBytes writes binary byte array accurately`() {
        val target = File(tempFolder.root, "binary.bin")
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x7F, 0x80.toByte(), 0xFF.toByte())

        assertTrue(SafeFileOps.atomicWriteBytes(target, bytes))
        assertTrue(target.readBytes().contentEquals(bytes))
    }


    @Test
    fun `atomicCopy replaces destination from source and leaves no temp file`() {
        val source = File(tempFolder.root, "source.bin").apply {
            writeBytes(ByteArray(32_768) { (it % 251).toByte() })
        }
        val target = File(tempFolder.root, "destination.bin").apply { writeText("old destination") }

        assertTrue(SafeFileOps.atomicCopy(source, target))
        assertTrue(source.readBytes().contentEquals(target.readBytes()))
        assertFalse(tempFolder.root.listFiles().orEmpty().any { it.name.contains(".tmp_") })
    }


    @Test
    fun `atomic copy preserves executable permissions on supported filesystems`() {
        val source = File(tempFolder.root, "executable.sh").apply {
            writeText("#!/bin/sh\\necho ok\\n")
            setExecutable(true, false)
        }
        val target = File(tempFolder.root, "copied.sh")

        assertTrue(SafeFileOps.atomicCopy(source, target))
        if (source.canExecute()) assertTrue(target.canExecute())
    }

    @Test
    fun `atomicCopy failure does not change existing destination`() {
        val target = File(tempFolder.root, "destination.txt").apply { writeText("known-good") }

        assertFalse(SafeFileOps.atomicCopy(File(tempFolder.root, "missing.txt"), target))
        assertEquals("known-good", target.readText())
    }

    @Test
    fun `safeReadText returns null for non-existent or unreadable file`() {
        assertNull(SafeFileOps.safeReadText(File(tempFolder.root, "non_existent.txt")))
        val dir = File(tempFolder.root, "subfolder").apply { mkdirs() }
        assertNull(SafeFileOps.safeReadText(dir))
    }

    @Test
    fun `safeReadText returns empty string for an existing empty file`() {
        val target = File(tempFolder.root, "empty.txt").apply { writeText("") }
        assertEquals("", SafeFileOps.safeReadText(target))
    }

    @Test
    fun `cleanStaleTempFiles removes old tmp files and preserves active files`() {
        val baseDir = tempFolder.newFolder("staging")
        val oldTmp = File(baseDir, "data.json.tmp_old123").apply {
            writeText("stale temp")
            setLastModified(System.currentTimeMillis() - 600_000L)
        }
        val freshTmp = File(baseDir, "data.json.tmp_fresh456").apply {
            writeText("fresh temp")
            setLastModified(System.currentTimeMillis())
        }
        val regularFile = File(baseDir, "data.json").apply { writeText("regular") }

        assertEquals(1, SafeFileOps.cleanStaleTempFiles(baseDir, maxAgeMs = 300_000L))
        assertFalse(oldTmp.exists())
        assertTrue(freshTmp.exists())
        assertTrue(regularFile.exists())
    }

    @Test
    fun `cleanStaleTempFiles safely ignores missing directories`() {
        assertEquals(0, SafeFileOps.cleanStaleTempFiles(File(tempFolder.root, "missing")))
    }
}
