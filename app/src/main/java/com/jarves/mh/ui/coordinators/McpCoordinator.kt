package com.jarves.mh.ui.coordinators

import com.jarves.mh.mcp.McpAuditLogger
import com.jarves.mh.mcp.McpPermissionManager
import com.jarves.mh.mcp.McpRegistry
import com.jarves.mh.mcp.McpServerInfo
import com.jarves.mh.runtime.McpClientRunner
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * MCP (Model Context Protocol) domain coordinator (Milestone 1).
 *
 * Explicit owner of the MCP server state machine: install/toggle/remove,
 * tool discovery, permission-gated tool execution and the audit log. Tool
 * calls are denied before execution when the permission manager refuses
 * them, and every call is audit-logged.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class McpCoordinator(
    private val host: CoordinatorHost,
    private val mcpRegistry: McpRegistry,
    private val mcpAuditLogger: McpAuditLogger,
    private val mcpPermissionManager: McpPermissionManager,
    private val mcpClientRunner: McpClientRunner,
) {
    fun refreshMcpServers(projectId: String? = host.state().activeProject?.id) {
        val servers = mcpRegistry.listServers(projectId)
        val catalog = mcpRegistry.catalogTemplates
        host.updateState { it.copy(mcpServers = servers, mcpCatalog = catalog) }
    }

    fun installMcpServer(server: McpServerInfo) {
        mcpRegistry.installServer(server)
        refreshMcpServers()
    }

    fun installMcpTemplate(templateId: String, envOverrides: Map<String, String> = emptyMap(), projectId: String? = host.state().activeProject?.id) {
        runCatching {
            mcpRegistry.installFromTemplate(templateId, envOverrides, projectId)
            refreshMcpServers(projectId)
        }.onFailure { err ->
            host.updateState { it.copy(toastMessage = "Failed to install MCP template: ${err.message}") }
        }
    }

    fun toggleMcpServer(serverId: String, enabled: Boolean) {
        mcpRegistry.toggleServer(serverId, enabled)
        refreshMcpServers()
    }

    fun removeMcpServer(serverId: String) {
        mcpClientRunner.stopServer(serverId)
        mcpRegistry.removeServer(serverId)
        refreshMcpServers()
    }

    fun updateMcpConfig(server: McpServerInfo) {
        mcpRegistry.updateServer(server)
        refreshMcpServers()
    }

    fun discoverMcpTools(server: McpServerInfo) {
        host.scope.launch {
            host.updateState { it.copy(mcpBusy = true, mcpSelectedServerId = server.id, mcpMessage = "Discovering tools for ${server.name}...") }
            val res = mcpClientRunner.discoverTools(server)
            res.onSuccess { tools ->
                host.updateState { it.copy(mcpBusy = false, mcpTools = tools, mcpMessage = "Found ${tools.size} tools") }
            }.onFailure { err ->
                host.updateState { it.copy(mcpBusy = false, mcpMessage = "Tool discovery failed: ${err.message}") }
            }
        }
    }

    suspend fun executeMcpTool(
        server: McpServerInfo,
        toolName: String,
        arguments: JSONObject,
        projectId: String? = host.state().activeProject?.id
    ): com.jarves.mh.runtime.McpToolCallResult {
        val perm = mcpPermissionManager.evaluate(server, toolName, projectId)
        if (!perm.allowed) {
            val auditEntry = com.jarves.mh.mcp.McpAuditEntry(
                serverId = server.id,
                toolName = toolName,
                argumentsJson = arguments.toString(),
                success = false,
                projectId = projectId,
                permissionDecision = if (perm.requiresPrompt) "PROMPT_REQUIRED" else "DENIED",
                error = perm.reason
            )
            mcpAuditLogger.log(auditEntry)
            loadMcpAuditLogs()
            return com.jarves.mh.runtime.McpToolCallResult(
                success = false,
                content = "",
                error = "Permission check failed: ${perm.reason}"
            )
        }

        val result = mcpClientRunner.callTool(server, toolName, arguments)
        val auditEntry = com.jarves.mh.mcp.McpAuditEntry(
            serverId = server.id,
            toolName = toolName,
            argumentsJson = arguments.toString(),
            success = result.success,
            resultPreview = result.content.take(200),
            durationMs = result.durationMs,
            projectId = projectId,
            permissionDecision = "ALLOWED",
            error = result.error
        )
        mcpAuditLogger.log(auditEntry)
        loadMcpAuditLogs()
        return result
    }

    fun setMcpProjectPermission(projectId: String, serverId: String, policy: com.jarves.mh.mcp.McpPermissionPolicy) {
        mcpPermissionManager.setProjectServerPolicy(projectId, serverId, policy)
    }

    fun setMcpGlobalPermission(serverId: String, policy: com.jarves.mh.mcp.McpPermissionPolicy) {
        mcpPermissionManager.setGlobalServerPolicy(serverId, policy)
    }

    fun loadMcpAuditLogs(limit: Int = 100) {
        val logs = mcpAuditLogger.getEntries(limit)
        host.updateState { it.copy(mcpAuditLogs = logs) }
    }

    fun clearMcpAuditLogs() {
        mcpAuditLogger.clear()
        host.updateState { it.copy(mcpAuditLogs = emptyList()) }
    }
}
