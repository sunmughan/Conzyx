package com.jarves.mh.mcp

import android.content.Context
import com.jarves.mh.workspace.SafeFileOps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry and lifecycle manager for Model Context Protocol (MCP) servers.
 * Handles persistence, discovery, catalog templates, enable/disable toggling, and removal.
 */
class McpRegistry(
    baseDir: File
) {
    constructor(context: Context) : this(File(context.filesDir, "mcp"))

    private val root = baseDir.apply { mkdirs() }
    private val configFile = File(root, "mcp_servers.json")
    private val servers = ConcurrentHashMap<String, McpServerInfo>()
    private val lock = Any()

    init {
        loadPersisted()
    }

    /**
     * Curated list of discoverable MCP servers available for installation.
     */
    val catalogTemplates: List<McpCatalogTemplate> = listOf(
        McpCatalogTemplate(
            id = "sqlite",
            name = "SQLite Server",
            description = "Interact with and query SQLite databases directly via MCP tools.",
            defaultCommand = "npx -y @modelcontextprotocol/server-sqlite",
            defaultArgs = listOf("--db", "workspace.db"),
            category = "Database"
        ),
        McpCatalogTemplate(
            id = "filesystem",
            name = "Filesystem Explorer",
            description = "Read, write, and inspect local project directories with secure boundaries.",
            defaultCommand = "npx -y @modelcontextprotocol/server-filesystem",
            defaultArgs = listOf("/workspace"),
            category = "Storage"
        ),
        McpCatalogTemplate(
            id = "github",
            name = "GitHub Platform",
            description = "Search repos, create issues, view pull requests, and commit files via GitHub API.",
            defaultCommand = "npx -y @modelcontextprotocol/server-github",
            requiredEnv = listOf("GITHUB_PERSONAL_ACCESS_TOKEN"),
            category = "Development"
        ),
        McpCatalogTemplate(
            id = "postgres",
            name = "PostgreSQL Server",
            description = "Inspect database schemas, query tables, and execute SQL statements.",
            defaultCommand = "npx -y @modelcontextprotocol/server-postgres",
            requiredEnv = listOf("POSTGRES_CONNECTION_STRING"),
            category = "Database"
        ),
        McpCatalogTemplate(
            id = "fetch",
            name = "Fetch & Markdown",
            description = "Fetch web pages, extract content, and convert HTML to readable Markdown for agents.",
            defaultCommand = "npx -y @modelcontextprotocol/server-fetch",
            category = "Web"
        ),
        McpCatalogTemplate(
            id = "puppeteer",
            name = "Puppeteer Automation",
            description = "Automate browser interactions, capture screenshots, and scrape dynamic pages.",
            defaultCommand = "npx -y @modelcontextprotocol/server-puppeteer",
            category = "Web Automation"
        ),
        McpCatalogTemplate(
            id = "memory",
            name = "Knowledge Graph Memory",
            description = "Persistent graph-based memory for entities, relationships, and context recall.",
            defaultCommand = "npx -y @modelcontextprotocol/server-memory",
            category = "Agent Memory"
        ),
        McpCatalogTemplate(
            id = "brave-search",
            name = "Brave Search",
            description = "Perform private web searches and retrieve up-to-date web answers.",
            defaultCommand = "npx -y @modelcontextprotocol/server-brave-search",
            requiredEnv = listOf("BRAVE_API_KEY"),
            category = "Search"
        ),
        McpCatalogTemplate(
            id = "sequential-thinking",
            name = "Sequential Thinking",
            description = "Structured step-by-step reasoning and hypothesis refinement server.",
            defaultCommand = "npx -y @modelcontextprotocol/server-sequential-thinking",
            category = "Reasoning"
        )
    )

    fun listServers(projectId: String? = null): List<McpServerInfo> {
        return servers.values
            .filter { it.projectId == null || it.projectId == projectId }
            .sortedBy { it.name.lowercase() }
    }

    fun getServer(id: String): McpServerInfo? = servers[id]

    fun installServer(server: McpServerInfo): McpServerInfo {
        servers[server.id] = server
        persist()
        return server
    }

    fun installFromTemplate(
        templateId: String,
        envOverrides: Map<String, String> = emptyMap(),
        projectId: String? = null
    ): McpServerInfo {
        val template = catalogTemplates.find { it.id == templateId }
            ?: throw IllegalArgumentException("Template with ID '$templateId' not found.")

        val server = McpServerInfo(
            id = template.id,
            name = template.name,
            description = template.description,
            command = template.defaultCommand,
            args = template.defaultArgs,
            env = envOverrides,
            enabled = true,
            isBuiltIn = true,
            projectId = projectId,
            // Template-declared secrets resolve from McpCredentialStore at start time,
            // never from the persisted registry JSON.
            secretEnvNames = template.requiredEnv
        )
        return installServer(server)
    }

    fun updateServer(server: McpServerInfo): Boolean {
        if (!servers.containsKey(server.id)) return false
        servers[server.id] = server
        persist()
        return true
    }

    fun toggleServer(id: String, enabled: Boolean): Boolean {
        val existing = servers[id] ?: return false
        servers[id] = existing.copy(enabled = enabled)
        persist()
        return true
    }

    fun removeServer(id: String): Boolean {
        val removed = servers.remove(id) != null
        if (removed) persist()
        return removed
    }

    /**
     * Versioned removal: drops the server from the registry AND purges its stored
     * secrets so credentials do not linger after uninstall.
     */
    fun removeServerAndCredentials(id: String, credentialStore: McpCredentialStore): Boolean {
        val server = servers[id] ?: return false
        val names = (server.secretEnvNames + listOfNotNull(server.credentialName)).distinct()
        credentialStore.removeServerCredentials(id, names)
        return removeServer(id)
    }

    /**
     * Versioned update: records a new installed version. Downgrades are refused
     * unless [force] is true; the comparison is a best-effort numeric semver check.
     */
    fun updateServerVersion(id: String, newVersion: String, force: Boolean = false): Boolean {
        val existing = servers[id] ?: return false
        require(newVersion.isNotBlank()) { "Version cannot be blank" }
        if (!force && compareVersions(newVersion, existing.version) < 0) {
            return false
        }
        servers[id] = existing.copy(version = newVersion)
        persist()
        return true
    }

    companion object {
        /**
         * Best-effort numeric version comparison ("1.10.2" > "1.9.0").
         * Non-numeric segments compare lexicographically. Returns negative when a < b.
         */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split(".", "-")
            val pb = b.split(".", "-")
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val sa = pa.getOrElse(i) { "0" }
                val sb = pb.getOrElse(i) { "0" }
                val na = sa.toIntOrNull()
                val nb = sb.toIntOrNull()
                val cmp = if (na != null && nb != null) na.compareTo(nb) else sa.compareTo(sb)
                if (cmp != 0) return cmp
            }
            return 0
        }
    }

    fun searchCatalog(query: String): List<McpCatalogTemplate> {
        if (query.isBlank()) return catalogTemplates
        val q = query.lowercase().trim()
        return catalogTemplates.filter {
            it.name.lowercase().contains(q) ||
            it.description.lowercase().contains(q) ||
            it.category.lowercase().contains(q) ||
            it.id.lowercase().contains(q)
        }
    }

    private fun loadPersisted() {
        synchronized(lock) {
            if (!configFile.exists()) return
            runCatching {
                val text = configFile.readText()
                if (text.isNotBlank()) {
                    val array = JSONArray(text)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val server = McpServerInfo.fromJson(obj)
                        servers[server.id] = server
                    }
                }
            }
        }
    }

    private fun persist() {
        synchronized(lock) {
            runCatching {
                val array = JSONArray()
                servers.values.forEach { array.put(it.toJson()) }
                SafeFileOps.atomicWriteText(configFile, array.toString())
            }
        }
    }
}
