package com.jarves.mh.workspace

import android.content.Context
import android.content.ContextWrapper
import com.jarves.mh.model.Project
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectWorkspaceControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
    }

    @Test
    fun `project workspace root rejects traversal and absolute paths`() {
        val root = tempFolder.newFolder("workspace-controller-root")
        val controller = ProjectWorkspaceController(TestContext(root))
        val project = Project(
            id = "project-safe",
            name = "Safe",
            description = "",
            language = "Kotlin",
        )

        assertTrue(runCatching {
            controller.projectWorkspaceRoot(project.copy(rootPath = "../outside"))
        }.isFailure)
        assertTrue(runCatching {
            controller.projectWorkspaceRoot(project.copy(rootPath = "/tmp/outside"))
        }.isFailure)
        assertTrue(runCatching {
            controller.projectWorkspaceRoot(project.copy(rootPath = "nested\\\\outside"))
        }.isFailure)
    }

    @Test
    fun `delete project workspace rejects traversal IDs without touching outside files`() {
        val root = tempFolder.newFolder("workspace-controller-delete")
        val outside = File(root, "outside.txt").apply { writeText("keep") }
        val controller = ProjectWorkspaceController(TestContext(root))

        assertFalse(controller.deleteProjectWorkspace("../outside.txt"))
        assertTrue(outside.isFile)
        assertTrue(outside.readText() == "keep")
    }

    @Test
    fun `project workspace root rejects a symlinked project directory`() {
        val root = tempFolder.newFolder("workspace-controller-symlink")
        val workspaces = File(root, "workspaces").apply { mkdirs() }
        val outside = tempFolder.newFolder("workspace-controller-outside")
        java.nio.file.Files.createSymbolicLink(File(workspaces, "linked-project").toPath(), outside.toPath())
        val controller = ProjectWorkspaceController(TestContext(root))
        val project = Project(
            id = "linked-project",
            name = "Linked",
            description = "",
            language = "Kotlin",
        )

        assertTrue(runCatching { controller.projectWorkspaceRoot(project) }.isFailure)
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }
}
