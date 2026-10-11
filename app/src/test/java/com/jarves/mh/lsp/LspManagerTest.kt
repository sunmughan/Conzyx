package com.jarves.mh.lsp

import com.jarves.mh.runtime.WorkspaceSemanticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LspManagerTest {

    @Test
    fun `server lifecycle management starts and stops language servers`() {
        val manager = LspManager()
        val status = manager.getServerStatus("Kotlin")
        assertTrue(status.isRunning)
        assertTrue(status.isBuiltInFallback)

        manager.stopServer("Kotlin")
        val stoppedStatus = manager.getServerStatus("Kotlin")
        assertFalse(stoppedStatus.isRunning)

        manager.startServer("Kotlin", "/workspace")
        val restartedStatus = manager.getServerStatus("Kotlin")
        assertTrue(restartedStatus.isRunning)
    }

    @Test
    fun `requestHover returns formatted markdown docs for symbols`() {
        val index = WorkspaceSemanticIndex(null)
        val lsp = LspManager(workspaceDir = null, semanticIndex = index)

        val code = """
            package com.example

            /**
             * Primary service for processing orders.
             */
            class OrderService {
                fun execute() {}
            }
        """.trimIndent()

        index.updateFile("src/OrderService.kt", code)

        // Line 6 is "class OrderService {"
        val hover = lsp.requestHover("Kotlin", "src/OrderService.kt", 6, 10, code)
        assertNotNull(hover)
        assertTrue(hover!!.contents.contains("OrderService"))
        assertTrue(hover.contents.contains("Primary service for processing orders"))
    }

    @Test
    fun `requestCodeActions provides contextual quickfixes for errors and extract refactoring`() {
        val index = WorkspaceSemanticIndex(null)
        val lsp = LspManager(workspaceDir = null, semanticIndex = index)

        // Content with unclosed delimiter error
        val brokenCode = """
            fun broken() {
                val x = 10
        """.trimIndent()

        val actions = lsp.requestCodeActions("Kotlin", "src/Broken.kt", 2, 1, brokenCode)
        assertTrue(actions.isNotEmpty())
        assertTrue(actions.any { it.title.contains("Insert matching closing delimiter") })
        assertTrue(actions.any { it.kind.startsWith("refactor") })
    }

    @Test
    fun `requestCompletions returns symbols and keywords`() {
        val index = WorkspaceSemanticIndex(null)
        val lsp = LspManager(workspaceDir = null, semanticIndex = index)

        index.updateFile("src/Utils.kt", "class MathUtils { fun add() {} }")

        val code = "val x = Math"
        val completions = lsp.requestCompletions("Kotlin", "src/Test.kt", 1, 12, code)
        assertTrue(completions.isNotEmpty())
        assertTrue(completions.any { it.label.contains("MathUtils") })
    }
}
