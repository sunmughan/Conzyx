package com.codeair.mhe.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootfsIntegrityVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootfsDir: File

    @Before
    fun setUp() {
        rootfsDir = tempFolder.newFolder("mock_rootfs")
    }

    @Test
    fun `verify returns invalid when essential directories and files are missing`() {
        val report = RootfsIntegrityVerifier.verify(rootfsDir)
        assertFalse(report.isValid)
        assertTrue(report.missingPaths.contains("tmp"))
        assertTrue(report.missingPaths.contains("etc/passwd"))
        assertTrue(report.missingPaths.contains("etc/resolv.conf"))
    }

    @Test
    fun `verifyAndHeal recreates missing directories and configuration files`() {
        // Run verify and heal on empty mock rootfs
        val report = RootfsIntegrityVerifier.verifyAndHeal(rootfsDir)

        assertTrue(report.isHealed)
        assertTrue(report.healedPaths.contains("tmp"))
        assertTrue(report.healedPaths.contains("etc/passwd"))
        assertTrue(report.healedPaths.contains("etc/resolv.conf"))

        // Check file existence on disk
        assertTrue(File(rootfsDir, "tmp").isDirectory)
        assertTrue(File(rootfsDir, "etc/passwd").isFile)
        assertTrue(File(rootfsDir, "etc/resolv.conf").isFile)
        assertTrue(File(rootfsDir, "etc/resolv.conf").readText().contains("nameserver"))
    }

    @Test
    fun `verify returns valid when all required paths exist`() {
        // Pre-create all required files and dirs
        listOf("tmp", "dev", "proc", "etc", "bin", "usr/bin", "var/tmp").forEach {
            File(rootfsDir, it).mkdirs()
        }
        File(rootfsDir, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
        File(rootfsDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\n")
        File(rootfsDir, "bin/sh").writeText("#!/bin/sh\n")

        val report = RootfsIntegrityVerifier.verify(rootfsDir)
        assertTrue(report.isValid)
        assertEquals(0, report.missingPaths.size)
    }
}
