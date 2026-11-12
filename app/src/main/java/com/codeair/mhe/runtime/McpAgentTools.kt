package com.codeair.mhe.runtime

import org.json.JSONObject

/**
 * Phase 4: the agent loop's view of the MCP tool registry.
 *
 * The loop only ever sees MCP tools through this interface; permission
 * evaluation and audit logging stay inside
 * [com.codeair.mhe.mcp.McpToolRegistry]. All failures are returned as
 * plain error text by the implementation, never thrown into the loop — an
 * MCP failure must never crash an agent turn.
 */
interface McpAgentTools {
    /** One line per discovered tool: "serverId: toolName — description". */
    fun listTools(projectId: String?): String

    /** Calls one tool; returns its text output, or an "Error: ..." string. */
    fun callTool(serverId: String, toolName: String, arguments: JSONObject, projectId: String?): String
}
