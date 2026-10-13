package com.codeair.mhe.runtime

/**
 * MCP tool shapes shared by the UI state and the MCP coordinator.
 *
 * These lived in McpClientRunner.kt; that runner used the wrong wire
 * protocol (LSP framing instead of MCP NDJSON) and was deleted in the QA
 * C10 fix. The types survive because the UI state and the coordinator's
 * public API are expressed in them, while the live implementation is
 * [com.codeair.mhe.mcp.McpToolRegistry].
 */
data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String = "{}",
    val serverId: String? = null
)

data class McpToolCallResult(
    val success: Boolean,
    val content: String,
    val error: String? = null,
    val durationMs: Long = 0L
)
