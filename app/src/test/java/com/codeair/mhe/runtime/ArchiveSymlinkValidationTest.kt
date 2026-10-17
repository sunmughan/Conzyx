package com.codeair.mhe.runtime

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ArchiveSymlinkValidationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun root(): File = tempFolder.newFolder("extract_root")

    private fun assertRejected(root: File, linkFile: File, linkName: String, messagePart: String) {
        try {
            resolveArchiveSymlinkTarget(root, linkFile, linkName)
            fail("symlink target '$linkName' must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "rejection reason '${e.message}' should mention '$messagePart'",
                e.message?.contains(messagePart) == true
            )
        }
    }

    @Test
    fun absoluteLinkTargetIsRewrittenInsideRoot() {
        val root = root()
        val rewritten = resolveArchiveSymlinkTarget(root, File(root, "evil"), "/etc/passwd")
        assertTrue("absolute target should be contained, got '$rewritten'", rewritten == "etc/passwd")
        val resolved = File(root, rewritten).canonicalFile.toPath()
        assertTrue(resolved.startsWith(root.canonicalFile.toPath()))
    }

    @Test
    fun absoluteLinkTargetWithDoubleSlashIsRewrittenInsideRoot() {
        val root = root()
        val rewritten = resolveArchiveSymlinkTarget(root, File(root, "evil"), "//etc/passwd")
        assertTrue("double-slash target should be contained, got '$rewritten'", rewritten == "etc/passwd")
    }

    @Test
    fun absoluteLinkTargetFromSubdirStaysRelativeToLinkDir() {
        val root = root()
        File(root, "bin").mkdirs()
        val rewritten = resolveArchiveSymlinkTarget(root, File(root, "bin/tool"), "/usr/bin/python3")
        assertTrue("expected ../usr/bin/python3, got '$rewritten'", rewritten == "../usr/bin/python3")
    }

    @Test
    fun absoluteTraversalPastRootIsRejected() {
        val root = root()
        assertRejected(root, File(root, "evil"), "/../outside", "escapes")
    }

    @Test
    fun emptyLinkTargetIsRejected() {
        val root = root()
        assertRejected(root, File(root, "empty"), "", "empty")
        assertRejected(root, File(root, "blank"), "   ", "empty")
    }

    @Test
    fun relativeTraversalLinkIsRejected() {
        val root = root()
        File(root, "sub").mkdirs()
        assertRejected(root, File(root, "sub/evil"), "../../outside", "escapes")
    }

    @Test
    fun dotDotSlashTraversalIsRejected() {
        val root = root()
        assertRejected(root, File(root, "evil"), "../outside.txt", "escapes")
    }

    @Test
    fun linkChainEscapeIsRejected() {
        val root = root()
        val real = File(root, "real").apply { mkdirs() }
        val alias = File(root, "alias")
        resolveArchiveSymlinkTarget(root, alias, "real")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        assertRejected(root, File(alias, "chained"), "../../escape", "escapes")
    }

    @Test
    fun forwardLinkChainToEscapingLinkIsRejected() {
        val root = root()
        val first = File(root, "first")
        resolveArchiveSymlinkTarget(root, first, "second")
        Files.createSymbolicLink(first.toPath(), File(root, "second").toPath())
        assertRejected(root, File(root, "second"), "../escape", "escapes")
    }

    @Test
    fun linkThroughPreExistingEscapingDirLinkIsRejected() {
        val root = root()
        val outer = tempFolder.newFolder("outer")
        val dirLink = File(root, "dirlink")
        Files.createSymbolicLink(dirLink.toPath(), outer.toPath())
        assertRejected(root, File(dirLink, "evil"), "payload", "escapes")
    }

    @Test
    fun validRelativeLinksAreAccepted() {
        val root = root()
        File(root, "bin").mkdirs()
        File(root, "sub").mkdirs()
        resolveArchiveSymlinkTarget(root, File(root, "link1"), "bin/tool")
        resolveArchiveSymlinkTarget(root, File(root, "sub/link2"), "../bin/tool")
        resolveArchiveSymlinkTarget(root, File(root, "self"), "self")
        resolveArchiveSymlinkTarget(root, File(root, "dot"), "./bin/tool")
    }
}
