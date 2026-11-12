package com.codeair.mhe.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4: McpToolRouter is the pure routing layer between the agent loop
 * and McpAgentTools — every failure is error text, never a throw.
 */
class McpToolRouterTest {

    private fun fakeTools(
        listResult: String = "server1: search — Search the web",
        callResult: String = "tool output",
        fail: Boolean = false,
    ): McpAgentTools = object : McpAgentTools {
        override fun listTools(projectId: String?): String {
            if (fail) throw IllegalStateException("discovery exploded")
            return listResult
        }

        override fun callTool(
            serverId: String,
            toolName: String,
            arguments: JSONObject,
            projectId: String?,
        ): String {
            if (fail) throw IllegalStateException("call exploded")
            return callResult
        }
    }

    @Test
    fun `lists tools from the delegate`() {
        val router = McpToolRouter(fakeTools(), "proj-1")
        assertEquals("server1: search — Search the web", router.listTools())
    }

    @Test
    fun `calls a tool through the delegate`() {
        val router = McpToolRouter(fakeTools(), "proj-1")
        assertEquals("tool output", router.callTool("server1", "search", JSONObject().put("q", "x")))
    }

    @Test
    fun `null delegate fails closed`() {
        val router = McpToolRouter(null, "proj-1")
        assertTrue(router.listTools().startsWith("Error:"))
        assertTrue(router.callTool("server1", "search", JSONObject()).startsWith("Error:"))
    }

    @Test
    fun `delegate failure becomes error text, never a throw`() {
        val router = McpToolRouter(fakeTools(fail = true), "proj-1")
        assertTrue(router.listTools().startsWith("Error:"))
        assertTrue(router.callTool("server1", "search", JSONObject()).startsWith("Error:"))
    }

    @Test
    fun `blank server or tool is an error`() {
        val router = McpToolRouter(fakeTools(), "proj-1")
        assertTrue(router.callTool("", "search", JSONObject()).startsWith("Error:"))
        assertTrue(router.callTool("server1", "", JSONObject()).startsWith("Error:"))
    }
}
