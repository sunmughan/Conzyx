package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeDirectoryTransactionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun validRuntime(directory: File): Boolean =
        File(directory, "runtime.marker").isFile

    @Test
    fun `activation replaces target only after staged directory validates`() {
        val parent = tempFolder.newFolder("runtime")
        val target = File(parent, "ubuntu").apply { mkdirs() }
        File(target, "runtime.marker").writeText("old")
        val staging = File(parent, "ubuntu.installing").apply { mkdirs() }
        File(staging, "runtime.marker").writeText("new")
        val backup = File(parent, "ubuntu.previous")

        RuntimeDirectoryTransaction.activate(
            staging = staging,
            target = target,
            backup = backup,
            isTargetValid = ::validRuntime,
            validateActivated = { activated ->
                assertTrue(validRuntime(activated))
                assertEquals("new", File(activated, "runtime.marker").readText())
            },
        )

        assertEquals("new", File(target, "runtime.marker").readText())
        assertFalse(backup.exists())
        assertFalse(staging.exists())
    }

    @Test
    fun `failed activation restores previous runtime`() {
        val parent = tempFolder.newFolder("runtime-failure")
        val target = File(parent, "ubuntu").apply { mkdirs() }
        File(target, "runtime.marker").writeText("known-good")
        val staging = File(parent, "ubuntu.installing").apply { mkdirs() }
        File(staging, "runtime.marker").writeText("candidate")
        val backup = File(parent, "ubuntu.previous")

        val thrown = runCatching {
            RuntimeDirectoryTransaction.activate(
                staging = staging,
                target = target,
                backup = backup,
                isTargetValid = ::validRuntime,
                validateActivated = { error("simulated validation failure") },
            )
        }

        assertTrue(thrown.isFailure)
        assertEquals("known-good", File(target, "runtime.marker").readText())
        assertFalse(backup.exists())
    }

    @Test
    fun `startup recovery restores backup when target is missing`() {
        val parent = tempFolder.newFolder("runtime-recovery")
        val target = File(parent, "ubuntu")
        val backup = File(parent, "ubuntu.previous").apply { mkdirs() }
        File(backup, "runtime.marker").writeText("previous")

        assertTrue(RuntimeDirectoryTransaction.recoverPrevious(target, backup, ::validRuntime))

        assertTrue(validRuntime(target))
        assertEquals("previous", File(target, "runtime.marker").readText())
        assertFalse(backup.exists())
    }

    @Test
    fun `startup recovery removes stale backup when target is already valid`() {
        val parent = tempFolder.newFolder("runtime-stale-backup")
        val target = File(parent, "ubuntu").apply { mkdirs() }
        File(target, "runtime.marker").writeText("active")
        val backup = File(parent, "ubuntu.previous").apply { mkdirs() }
        File(backup, "runtime.marker").writeText("old")

        assertFalse(RuntimeDirectoryTransaction.recoverPrevious(target, backup, ::validRuntime))

        assertEquals("active", File(target, "runtime.marker").readText())
        assertFalse(backup.exists())
    }
}
