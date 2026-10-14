package com.codeair.mhe.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * QA H36: per-tool project DENY policies were silently lost on restart
 * (persist() never wrote projectToolPolicies), so a built-in server fell
 * back to ALLOW_ALWAYS. A denial must survive a manager restart.
 */
class McpPermissionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun builtInServer(id: String = "builtin-1") = McpServerInfo(
        id = id,
        name = "Built-in",
        command = "npx",
        isBuiltIn = true
    )

    @Test
    fun `per-tool project deny survives restart`() {
        val dir = tempFolder.newFolder("perm")
        val first = McpPermissionManager(dir)
        // Built-in default is ALLOW_ALWAYS; deny one tool in one project.
        assertTrue(first.evaluate(builtInServer(), "dangerousTool", "proj-1").allowed)
        first.setProjectToolPolicy("proj-1", "builtin-1", "dangerousTool", McpPermissionPolicy.DENY)
        assertFalse(first.evaluate(builtInServer(), "dangerousTool", "proj-1").allowed)

        // Simulate restart: a fresh manager on the same directory.
        val restarted = McpPermissionManager(dir)
        assertFalse(restarted.evaluate(builtInServer(), "dangerousTool", "proj-1").allowed)
        // Other tools in the same project are still allowed by the default.
        assertTrue(restarted.evaluate(builtInServer(), "safeTool", "proj-1").allowed)
        // Other projects are unaffected.
        assertTrue(restarted.evaluate(builtInServer(), "dangerousTool", "proj-2").allowed)
    }

    @Test
    fun `per-tool project allow survives restart`() {
        val dir = tempFolder.newFolder("perm2")
        val custom = McpServerInfo(id = "custom-1", name = "Custom", command = "node", isBuiltIn = false)
        val first = McpPermissionManager(dir)
        first.setProjectToolPolicy("proj-1", "custom-1", "someTool", McpPermissionPolicy.ALLOW_ALWAYS)
        val restarted = McpPermissionManager(dir)
        assertTrue(restarted.evaluate(custom, "someTool", "proj-1").allowed)
    }

    @Test
    fun `one-time approval allows exactly one prompt-required call`() {
        val dir = tempFolder.newFolder("perm3")
        val manager = McpPermissionManager(dir)
        val custom = McpServerInfo(id = "custom-1", name = "Custom", command = "node", isBuiltIn = false)

        val before = manager.evaluate(custom, "someTool", "proj-1")
        assertFalse(before.allowed)
        assertTrue(before.requiresPrompt)

        manager.grantOneTimeApproval("custom-1", "someTool", "proj-1")
        val granted = manager.evaluate(custom, "someTool", "proj-1")
        assertTrue(granted.allowed)
        assertFalse(granted.requiresPrompt)

        val after = manager.evaluate(custom, "someTool", "proj-1")
        assertFalse(after.allowed)
        assertTrue(after.requiresPrompt)
    }

    @Test
    fun `one-time approval does not override deny`() {
        val dir = tempFolder.newFolder("perm4")
        val manager = McpPermissionManager(dir)
        val custom = McpServerInfo(id = "custom-1", name = "Custom", command = "node", isBuiltIn = false)
        manager.setProjectToolPolicy("proj-1", "custom-1", "evilTool", McpPermissionPolicy.DENY)

        manager.grantOneTimeApproval("custom-1", "evilTool", "proj-1")
        val decision = manager.evaluate(custom, "evilTool", "proj-1")
        assertFalse(decision.allowed)
        assertFalse(decision.requiresPrompt)
    }

    @Test
    fun `one-time approval is scoped to project and tool`() {
        val dir = tempFolder.newFolder("perm5")
        val manager = McpPermissionManager(dir)
        val custom = McpServerInfo(id = "custom-1", name = "Custom", command = "node", isBuiltIn = false)

        manager.grantOneTimeApproval("custom-1", "someTool", "proj-1")
        assertFalse(manager.evaluate(custom, "someTool", "proj-2").allowed)
        assertFalse(manager.evaluate(custom, "otherTool", "proj-1").allowed)
        assertTrue(manager.evaluate(custom, "someTool", "proj-1").allowed)
    }
}
