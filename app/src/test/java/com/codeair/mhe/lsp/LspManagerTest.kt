package com.codeair.mhe.lsp

import com.codeair.mhe.runtime.WorkspaceSemanticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LspManagerTest {

    @Test
    fun `server status honestly distinguishes real server from built-in fallback`() {
        val manager = LspManager()
        // Milestone 0: no real server is running at init — only the fallback.
        // isRunning must never be true when only the fallback is active.
        val status = manager.getServerStatus("Kotlin")
        assertFalse(status.isRunning)
        assertFalse(status.isRealServer)
        assertTrue(status.isBuiltInFallback)

        manager.stopServer("Kotlin")
        val stoppedStatus = manager.getServerStatus("Kotlin")
        assertFalse(stoppedStatus.isRunning)
        assertFalse(stoppedStatus.isRealServer)

        // No language-server binary is installed in the test environment, so
        // startServer must honestly return false and keep isRunning=false.
        val started = manager.startServer("Kotlin", "/workspace")
        assertFalse(started)
        val restartedStatus = manager.getServerStatus("Kotlin")
        assertFalse(restartedStatus.isRunning)
        assertFalse(restartedStatus.isRealServer)
        assertTrue(restartedStatus.isBuiltInFallback)
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
