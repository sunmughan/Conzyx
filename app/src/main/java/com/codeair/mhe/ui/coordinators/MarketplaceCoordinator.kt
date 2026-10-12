package com.codeair.mhe.ui.coordinators

import com.codeair.mhe.marketplace.MarketplaceInstallManager
import com.codeair.mhe.marketplace.MarketplaceInstallResult
import com.codeair.mhe.marketplace.MarketplaceItem
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
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class MarketplaceCoordinator(
    private val host: CoordinatorHost,
    private val installManager: MarketplaceInstallManager,
) {
    private val _installedIds = MutableStateFlow(installManager.installedIds())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

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
