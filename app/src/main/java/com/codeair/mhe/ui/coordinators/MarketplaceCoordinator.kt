package com.codeair.mhe.ui.coordinators

import com.codeair.mhe.marketplace.DeveloperMarketplaceCatalog
import com.codeair.mhe.marketplace.MarketplaceCategory
import com.codeair.mhe.marketplace.MarketplaceInstallManager
import com.codeair.mhe.marketplace.MarketplaceInstallResult
import com.codeair.mhe.marketplace.MarketplaceItem
import com.codeair.mhe.marketplace.MarketplacePublishedItemsStore
import com.codeair.mhe.marketplace.validateMarketplaceItem
import com.codeair.mhe.mcp.McpRegistry
import com.codeair.mhe.mcp.McpServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Marketplace domain coordinator (Milestone 1).
 *
 * Explicit owner of the marketplace install state machine. All installs go
 * through the real [MarketplaceInstallManager] (durable records + on-disk
 * package materialization); results are honest (`Installed` / `Failed(reason)`
 * / `AlreadyInstalled`), never fake success.
 *
 * It also owns publishing: a published item is validated, persisted in
 * [MarketplacePublishedItemsStore], and added to the catalog — so it
 * survives restart instead of living only in the catalog's in-memory list.
 * Publishing is local-only: no registry is configured in the app, so a
 * published item is saved on this device, not shared (the dialog says so).
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class MarketplaceCoordinator(
    private val host: CoordinatorHost,
    private val installManager: MarketplaceInstallManager,
    private val publishedStore: MarketplacePublishedItemsStore,
    private val mcpRegistry: McpRegistry,
    /** Refreshes the MCP server list UI after a marketplace (un)install registers one. */
    private val onMcpServersChanged: () -> Unit = {},
) {
    private val _installedIds = MutableStateFlow(installManager.installedIds())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    init {
        // Reload persisted published items into the catalog (dedupe by id
        // in addCustomItem keeps restarts from duplicating them).
        publishedStore.load().forEach { DeveloperMarketplaceCatalog.addCustomItem(it) }
    }

    /**
     * Publishes [item]: validates it, persists it durably, and adds it to
     * the catalog. Synchronous on purpose: everything here is in-memory
     * plus a SharedPreferences apply() (async disk write), so the screen's
     * recompose after this call already sees the new item.
     */
    fun publishMarketplaceItem(item: MarketplaceItem) {
        val validation = validateMarketplaceItem(item)
        val outcome = if (!validation.isValid) {
            "Could not publish: ${validation.errors.joinToString("; ")}"
        } else {
            publishedStore.add(item)
            DeveloperMarketplaceCatalog.addCustomItem(item)
            "Saved ${item.name} on this device"
        }
        host.updateState { it.copy(toastMessage = outcome) }
    }

    fun installMarketplaceItem(item: MarketplaceItem) {
        host.scope.launch(Dispatchers.IO) {
            val outcome = when (val result = installManager.install(item)) {
                is MarketplaceInstallResult.Installed -> {
                    val mcpNote = registerMarketplaceMcpServer(item)
                    "Installed ${item.name}$mcpNote"
                }
                is MarketplaceInstallResult.Failed ->
                    "Install failed: ${result.reason}"
                MarketplaceInstallResult.AlreadyInstalled ->
                    "${item.name} is already installed"
            }
            _installedIds.value = installManager.installedIds()
            withContext(Dispatchers.Main) {
                host.updateState { it.copy(toastMessage = outcome) }
            }
        }
    }

    /**
     * Registers an installed MCP marketplace item as a usable MCP server.
     * Returns the toast suffix describing what happened. Non-MCP items
     * return "" — nothing to register.
     */
    private fun registerMarketplaceMcpServer(item: MarketplaceItem): String {
        if (item.category != MarketplaceCategory.MCP_SERVERS) return ""
        val server = marketplaceItemToMcpServer(item)
            ?: return " (MCP server not registered: install value is not a command)"
        mcpRegistry.installServer(server)
        onMcpServersChanged()
        return " (MCP server registered)"
    }

    fun uninstallMarketplaceItem(item: MarketplaceItem) {
        host.scope.launch(Dispatchers.IO) {
            val removed = installManager.uninstall(item.id)
            if (item.category == MarketplaceCategory.MCP_SERVERS) {
                // Symmetric with install: an uninstalled MCP item must not
                // leave a registered server behind.
                if (mcpRegistry.removeServer("marketplace-${item.id}")) {
                    onMcpServersChanged()
                }
            }
            _installedIds.value = installManager.installedIds()
            val outcome = if (removed) "Uninstalled ${item.name}" else "${item.name} was not installed"
            withContext(Dispatchers.Main) {
                host.updateState { it.copy(toastMessage = outcome) }
            }
        }
    }
}

/**
 * Maps a marketplace MCP item to the [McpServerInfo] installing it
 * registers. Returns null when the item's install value cannot become a
 * server command (blank, or an http(s) URL — a URL is a download, not a
 * command to run). Server ids are namespaced `marketplace-<itemId>` so a
 * marketplace server never collides with a built-in template id.
 * Pure function, unit-tested.
 */
internal fun marketplaceItemToMcpServer(item: MarketplaceItem): McpServerInfo? {
    val commandLine = item.installUrl.trim()
    if (commandLine.isBlank()) return null
    if (commandLine.startsWith("http://") || commandLine.startsWith("https://")) return null
    val parts = commandLine.split(Regex("\\s+"))
    return McpServerInfo(
        id = "marketplace-${item.id}",
        name = item.name,
        description = item.description,
        command = parts.first(),
        args = parts.drop(1),
        enabled = true,
        isBuiltIn = false,
        projectId = null,
    )
}
