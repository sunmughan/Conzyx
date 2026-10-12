package com.jarves.mh.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Transport type for Model Context Protocol communication.
 */
enum class McpTransportType {
    STDIO,
    SSE,
    STREAMABLE_HTTP
}

/**
 * Definition of an installed or configured MCP server.
 *
 * Secrets are NEVER stored in [env]: names listed in [secretEnvNames] resolve from
 * [McpCredentialStore] at process-start time. Remote servers additionally carry a
 * [remoteUrl] and [authType] whose credentials also live in the credential store.
 */
data class McpServerInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workingDir: String? = null,
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val projectId: String? = null,
    val transport: McpTransportType = McpTransportType.STDIO,
    val installedAt: Long = System.currentTimeMillis(),
    val version: String = "1.0.0",
    val secretEnvNames: List<String> = emptyList(),
    val remoteUrl: String? = null,
    val authType: McpAuthType = McpAuthType.NONE,
    val credentialName: String? = null,
    val apiKeyHeaderName: String = "X-API-Key"
) {
    /** True when this server speaks to a remote endpoint instead of a local process. */
    val isRemote: Boolean
        get() = transport == McpTransportType.SSE || transport == McpTransportType.STREAMABLE_HTTP

    fun remoteConfig(): McpRemoteConfig? {
        val url = remoteUrl ?: return null
        return McpRemoteConfig(
            url = url,
            authType = authType,
            credentialName = credentialName,
            apiKeyHeaderName = apiKeyHeaderName
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("command", command)
        put("args", JSONArray(args))
        val envObj = JSONObject()
        env.forEach { (k, v) -> envObj.put(k, v) }
        put("env", envObj)
        workingDir?.let { put("workingDir", it) }
        put("enabled", enabled)
        put("isBuiltIn", isBuiltIn)
        projectId?.let { put("projectId", it) }
        put("transport", transport.name)
        put("installedAt", installedAt)
        put("version", version)
        put("secretEnvNames", JSONArray(secretEnvNames))
        remoteUrl?.let { put("remoteUrl", it) }
        put("authType", authType.name)
        credentialName?.let { put("credentialName", it) }
        put("apiKeyHeaderName", apiKeyHeaderName)
    }

    companion object {
        fun fromJson(json: JSONObject): McpServerInfo {
            val argsList = mutableListOf<String>()
            val argsArr = json.optJSONArray("args")
            if (argsArr != null) {
                for (i in 0 until argsArr.length()) {
                    argsList.add(argsArr.getString(i))
                }
            }

            val envMap = mutableMapOf<String, String>()
            val envObj = json.optJSONObject("env")
            envObj?.keys()?.forEach { k ->
                envMap[k] = envObj.getString(k)
            }

            val secretNames = mutableListOf<String>()
            val secretArr = json.optJSONArray("secretEnvNames")
            if (secretArr != null) {
                for (i in 0 until secretArr.length()) {
                    secretNames.add(secretArr.getString(i))
                }
            }

            val transportStr = json.optString("transport", McpTransportType.STDIO.name)
            val transport = runCatching { McpTransportType.valueOf(transportStr) }
                .getOrDefault(McpTransportType.STDIO)

            val authStr = json.optString("authType", McpAuthType.NONE.name)
            val authType = runCatching { McpAuthType.valueOf(authStr) }
                .getOrDefault(McpAuthType.NONE)

            return McpServerInfo(
                id = json.getString("id"),
                name = json.getString("name"),
                description = json.optString("description", ""),
                command = json.getString("command"),
                args = argsList,
                env = envMap,
                workingDir = json.optString("workingDir").takeIf { it.isNotBlank() },
                enabled = json.optBoolean("enabled", true),
                isBuiltIn = json.optBoolean("isBuiltIn", false),
                projectId = json.optString("projectId").takeIf { it.isNotBlank() },
                transport = transport,
                installedAt = json.optLong("installedAt", System.currentTimeMillis()),
                version = json.optString("version", "1.0.0").takeIf { it.isNotBlank() } ?: "1.0.0",
                secretEnvNames = secretNames,
                remoteUrl = json.optString("remoteUrl").takeIf { it.isNotBlank() },
                authType = authType,
                credentialName = json.optString("credentialName").takeIf { it.isNotBlank() },
                apiKeyHeaderName = json.optString("apiKeyHeaderName", "X-API-Key")
            )
        }
    }
}

/**
 * Curated catalog template for discovery and one-click installation.
 */
data class McpCatalogTemplate(
    val id: String,
    val name: String,
    val description: String,
    val defaultCommand: String,
    val defaultArgs: List<String> = emptyList(),
    val requiredEnv: List<String> = emptyList(),
    val category: String = "Utility"
)
