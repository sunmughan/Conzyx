package com.codeair.mhe.runtime

import org.json.JSONObject

/**
 * Phase 4: routes the agent loop's MCP tool calls to [McpAgentTools].
 *
 * Every failure is returned as plain error text so the model can report
 * it; an MCP failure never throws into the agent turn. A null delegate
 * means no MCP servers are wired into this session — fail closed, never
 * pretend a tool ran.
 *
 * Pure routing (no Android dependencies) so it is unit-testable; the
 * [McpAgentTools] implementation owns the registry, permissions and audit.
 */
class McpToolRouter(
    private val tools: McpAgentTools?,
    private val projectId: String?,
) {
    fun listTools(): String {
        val delegate = tools ?: return "Error: MCP tools are not available in this session."
        return runCatching { delegate.listTools(projectId) }
            .getOrElse { e -> "Error: could not list MCP tools: ${e.message}" }
            .take(MAX_MCP_TOOL_TEXT_BYTES)
    }

    fun callTool(serverId: String, toolName: String, arguments: JSONObject): String {
        val delegate = tools ?: return "Error: MCP tools are not available in this session."
        if (serverId.isBlank() || toolName.isBlank()) {
            return "Error: call_mcp_tool needs a server_id and a tool_name."
        }
        return runCatching { delegate.callTool(serverId, toolName, arguments, projectId) }
            .getOrElse { e -> "Error: MCP tool call failed: ${e.message}" }
            .take(MAX_MCP_TOOL_TEXT_BYTES)
    }

    private companion object {
        const val MAX_MCP_TOOL_TEXT_BYTES = 32 * 1024
    }
}
