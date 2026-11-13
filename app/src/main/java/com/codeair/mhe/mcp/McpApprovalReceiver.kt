package com.codeair.mhe.mcp

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Phase 5: notification actions for MCP tool approvals.
 *
 * Approve grants a durable one-time approval for the named tool, so the
 * coordinator's own manager instance sees it on the next evaluate()
 * (one-time approvals are persisted in mcp_permissions.json). Deny
 * grants nothing. Either way the notification is dismissed.
 * Manifest-registered, exported=false; no activity launch.
 */
class McpApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: return
        val toolName = intent.getStringExtra(EXTRA_TOOL_NAME) ?: return
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (intent.action == ACTION_APPROVE) {
            runCatching { McpPermissionManager(context).grantOneTimeApproval(serverId, toolName, projectId) }
        }
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(notificationId) }
    }

    companion object {
        const val ACTION_APPROVE = "com.codeair.mhe.APPROVE_MCP_TOOL"
        const val ACTION_DENY = "com.codeair.mhe.DENY_MCP_TOOL"
        const val EXTRA_SERVER_ID = "mcp_server_id"
        const val EXTRA_TOOL_NAME = "mcp_tool_name"
        const val EXTRA_PROJECT_ID = "mcp_project_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
