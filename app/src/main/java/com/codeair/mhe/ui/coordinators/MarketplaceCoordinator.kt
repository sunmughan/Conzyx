package com.codeair.mhe.ui.coordinators

import com.codeair.mhe.marketplace.DeveloperMarketplaceCatalog
import com.codeair.mhe.marketplace.MarketplaceInstallManager
import com.codeair.mhe.marketplace.MarketplaceInstallResult
import com.codeair.mhe.marketplace.MarketplaceItem
import com.codeair.mhe.marketplace.MarketplacePublishedItemsStore
import com.codeair.mhe.marketplace.validateMarketplaceItem
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
                is MarketplaceInstallResult.Installed ->
                    "Installed ${item.name}"
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

    fun uninstallMarketplaceItem(item: MarketplaceItem) {
        host.scope.launch(Dispatchers.IO) {
            val removed = installManager.uninstall(item.id)
            _installedIds.value = installManager.installedIds()
            val outcome = if (removed) "Uninstalled ${item.name}" else "${item.name} was not installed"
            withContext(Dispatchers.Main) {
                host.updateState { it.copy(toastMessage = outcome) }
            }
        }
    }
}
