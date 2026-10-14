package com.codeair.mhe.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.codeair.mhe.ui.theme.PocketGreen
import com.codeair.mhe.ui.theme.PocketOrange
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeair.mhe.marketplace.DeveloperMarketplaceCatalog
import com.codeair.mhe.marketplace.MarketplaceCategory
import com.codeair.mhe.marketplace.MarketplaceItem

/**
 * AI Developer Marketplace (Phase 14).
 * Discover, 1-click install, and publish custom AI agents, MCP servers, skills, and templates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(
    onBack: (() -> Unit)? = null,
    onInstallItem: (MarketplaceItem) -> Unit = {},
    onUninstallItem: (MarketplaceItem) -> Unit = {},
    /**
     * Publish handler: validates, persists durably, and adds to the catalog.
     * Defaults to the in-memory catalog add (previews). Production passes
     * the ViewModel's publish, which persists the item across restarts.
     */
    onPublishItem: (MarketplaceItem) -> Unit = { DeveloperMarketplaceCatalog.addCustomItem(it) },
    /**
     * Installed package IDs from the real [MarketplaceInstallManager], so the
     * screen reflects durable install state (not just in-memory session state).
     */
    initiallyInstalledIds: Set<String> = emptySet(),
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<MarketplaceCategory?>(null) }
    var showOnlyInstalled by rememberSaveable { mutableStateOf(false) }
    // Install state is owned by MarketplaceInstallManager (durable). The screen never
    // mutates install state locally: it renders the manager's set and delegates
    // install/uninstall to the callbacks, which report real results.
    val installedSet = initiallyInstalledIds
    var showPublishDialog by rememberSaveable { mutableStateOf(false) }
    var catalogVersion by remember { mutableStateOf(0) }

    val allItems = remember(searchQuery, selectedCategory, showOnlyInstalled, catalogVersion, installedSet) {
        val base = if (selectedCategory == null) {
            DeveloperMarketplaceCatalog.getAllItems()
        } else {
            DeveloperMarketplaceCatalog.filterByCategory(selectedCategory!!)
        }
        val searched = if (searchQuery.isBlank()) {
            base
        } else {
            val q = searchQuery.trim().lowercase()
            base.filter {
                it.name.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.author.lowercase().contains(q) ||
                    it.tags.any { tag -> tag.lowercase().contains(q) }
            }
        }
        if (showOnlyInstalled) {
            searched.filter { installedSet.contains(it.id) }
        } else {
            searched
        }
    }

    if (showPublishDialog) {
        PublishMarketplaceItemDialog(
            onDismiss = { showPublishDialog = false },
            onPublish = { newItem ->
                onPublishItem(newItem)
                catalogVersion++
                showPublishDialog = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text("Marketplace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Agents, MCP, Skills & Templates", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showPublishDialog = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Publish custom item",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search catalog by name, author, or tags...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            // Category & Installed Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedCategory == null && !showOnlyInstalled,
                    onClick = {
                        selectedCategory = null
                        showOnlyInstalled = false
                    },
                    label = { Text("All (${DeveloperMarketplaceCatalog.getAllItems().size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
                FilterChip(
                    selected = showOnlyInstalled,
                    onClick = { showOnlyInstalled = !showOnlyInstalled },
                    label = {
                        val installedCount = installedSet.size
                        Text("Installed ($installedCount)")
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
                MarketplaceCategory.entries.forEach { cat ->
                    val isSelected = selectedCategory == cat && !showOnlyInstalled
                    val count = DeveloperMarketplaceCatalog.filterByCategory(cat).size
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedCategory = if (isSelected) null else cat
                            showOnlyInstalled = false
                        },
                        label = {
                            Text(
                                when (cat) {
                                    MarketplaceCategory.AGENTS -> "Agents ($count)"
                                    MarketplaceCategory.MCP_SERVERS -> "MCP Servers ($count)"
                                    MarketplaceCategory.SKILLS -> "Skills ($count)"
                                    MarketplaceCategory.TEMPLATES -> "Templates ($count)"
                                }
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }

            // Items List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Medium: no empty state — a search with no matches used to
                // show a blank list with no explanation.
                if (allItems.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = when {
                                    searchQuery.isNotBlank() -> "No items match \"$searchQuery\""
                                    showOnlyInstalled -> "Nothing installed yet"
                                    else -> "No items in this category yet"
                                },
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = when {
                                    searchQuery.isNotBlank() -> "Try a different search."
                                    showOnlyInstalled -> "Browse the catalog and tap Install on anything you want."
                                    else -> "Try another category."
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                items(allItems, key = { it.id }) { item ->
                    val isInstalled = installedSet.contains(item.id)
                    MarketplaceItemCard(
                        item = item,
                        isInstalled = isInstalled,
                        onInstall = { onInstallItem(item) },
                        onUninstall = { onUninstallItem(item) },
                        onDeleteCustom = if (item.isCustom) {
                            {
                                DeveloperMarketplaceCatalog.removeCustomItem(item.id)
                                catalogVersion++
                            }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketplaceItemCard(
    item: MarketplaceItem,
    isInstalled: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit = {},
    onDeleteCustom: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (item.isCustom) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    text = "Custom",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                    Text(
                        text = "by " + item.author,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Category pill
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when (item.category) {
                            MarketplaceCategory.AGENTS -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            MarketplaceCategory.MCP_SERVERS -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                            MarketplaceCategory.SKILLS -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                            MarketplaceCategory.TEMPLATES -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        },
                    ) {
                        Text(
                            text = item.category.name.replace('_', ' '),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    if (onDeleteCustom != null) {
                        IconButton(
                            onClick = onDeleteCustom,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete custom item",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
            )

            if (item.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        ) {
                            Text(
                                text = "#$tag",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (item.rating > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFFFFB300),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = item.rating.toString(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = "·  ${item.downloads} installs",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // No rating system exists: never show a fabricated score.
                        Text(
                            text = "No ratings yet",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isInstalled) {
                    OutlinedButton(
                        onClick = onUninstall,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = PocketGreen,
                            )
                            Text(
                                text = "Installed · Remove",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = onInstall,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "Install",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog to add/publish a custom item into the AI Developer Marketplace.
 */
@Composable
private fun PublishMarketplaceItemDialog(
    onDismiss: () -> Unit,
    onPublish: (MarketplaceItem) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var selectedCat by rememberSaveable { mutableStateOf(MarketplaceCategory.SKILLS) }
    var author by rememberSaveable { mutableStateOf("Local Developer") }
    var description by rememberSaveable { mutableStateOf("") }
    var installUrl by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Publish to Marketplace") },
        text = {
            // Medium: dialog content scrolls and lifts above the keyboard —
            // the Publish button used to be pushed off-screen on small phones.
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )

                // Category Selector
                Text("Category", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MarketplaceCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCat == cat,
                            onClick = { selectedCat = cat },
                            label = { Text(cat.name.replace('_', ' '), fontSize = 11.sp) },
                        )
                    }
                }

                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description *") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )

                OutlinedTextField(
                    value = installUrl,
                    onValueChange = { installUrl = it },
                    label = { Text("Install Command or URL *") },
                    placeholder = { Text("e.g. npx -y ... or https://github.com/...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )

                Text(
                    "Saved on this device. No registry is configured, so this item is not shared with other devices.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // Medium: the Install Command field is labelled required (*)
            // but the button used to enable without it, and a blank
            // installUrl was accepted. All three *-fields gate the button.
            val canPublish = name.isNotBlank() && description.isNotBlank() && installUrl.isNotBlank()
            Button(
                onClick = {
                    if (canPublish) {
                        onPublish(
                            MarketplaceItem(
                                name = name.trim(),
                                category = selectedCat,
                                author = author.trim().ifBlank { "Local Developer" },
                                description = description.trim(),
                                installUrl = installUrl.trim(),
                                isCustom = true,
                            )
                        )
                    }
                },
                enabled = canPublish,
            ) {
                Text("Save Item")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
