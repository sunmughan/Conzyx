package com.codeair.mhe.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeveloperMarketplaceCatalogTest {

    @Before
    fun setUp() {
        // Clean any custom items or test state
        DeveloperMarketplaceCatalog.getAllItems().filter { it.isCustom }.forEach {
            DeveloperMarketplaceCatalog.removeCustomItem(it.id)
        }
    }

    @Test
    fun testCuratedCatalogHasAllFourCategories() {
        val allItems = DeveloperMarketplaceCatalog.curatedCatalog
        assertTrue("Curated catalog should contain items", allItems.isNotEmpty())

        val agents = DeveloperMarketplaceCatalog.filterByCategory(MarketplaceCategory.AGENTS)
        val skills = DeveloperMarketplaceCatalog.filterByCategory(MarketplaceCategory.SKILLS)
        val mcp = DeveloperMarketplaceCatalog.filterByCategory(MarketplaceCategory.MCP_SERVERS)
        val templates = DeveloperMarketplaceCatalog.filterByCategory(MarketplaceCategory.TEMPLATES)

        assertTrue("Should have agents", agents.isNotEmpty())
        assertTrue("Should have skills", skills.isNotEmpty())
        assertTrue("Should have MCP servers", mcp.isNotEmpty())
        assertTrue("Should have starter templates", templates.isNotEmpty())
    }

    @Test
    fun testSearchByNameAndDescriptionAndTags() {
        val postgresResults = DeveloperMarketplaceCatalog.search("PostgreSQL")
        assertTrue("Should find PostgreSQL item", postgresResults.any { it.name.contains("PostgreSQL") })

        val dockerResults = DeveloperMarketplaceCatalog.search("docker")
        assertTrue("Should find Docker item via description or tag", dockerResults.isNotEmpty())

        val kotlinTagResults = DeveloperMarketplaceCatalog.search("kotlin")
        assertTrue("Should find Kotlin tagged items", kotlinTagResults.isNotEmpty())

        val blankResults = DeveloperMarketplaceCatalog.search("")
        assertEquals(DeveloperMarketplaceCatalog.getAllItems().size, blankResults.size)
    }

    @Test
    fun testInstallationTracking() {
        val firstItem = DeveloperMarketplaceCatalog.curatedCatalog.first()
        val itemId = firstItem.id

        // Initially not installed
        DeveloperMarketplaceCatalog.uninstallItem(itemId)
        assertFalse(DeveloperMarketplaceCatalog.isInstalled(itemId))

        // Mark installed
        DeveloperMarketplaceCatalog.markInstalled(itemId)
        assertTrue(DeveloperMarketplaceCatalog.isInstalled(itemId))
        assertTrue(DeveloperMarketplaceCatalog.getInstalledItems().any { it.id == itemId })

        // Toggle install off
        val toggledOff = DeveloperMarketplaceCatalog.toggleInstall(itemId)
        assertFalse(toggledOff)
        assertFalse(DeveloperMarketplaceCatalog.isInstalled(itemId))

        // Toggle install on
        val toggledOn = DeveloperMarketplaceCatalog.toggleInstall(itemId)
        assertTrue(toggledOn)
        assertTrue(DeveloperMarketplaceCatalog.isInstalled(itemId))

        // Clean up
        DeveloperMarketplaceCatalog.uninstallItem(itemId)
        assertFalse(DeveloperMarketplaceCatalog.isInstalled(itemId))
    }

    @Test
    fun testCustomItemLifecycle() {
        val customItem = MarketplaceItem(
            name = "My Custom Rust Linter",
            category = MarketplaceCategory.SKILLS,
            author = "Dev Team",
            description = "Custom clippy integration skill",
            installUrl = "https://github.com/myteam/rust-linter",
            tags = listOf("rust", "linter", "custom"),
        )

        DeveloperMarketplaceCatalog.addCustomItem(customItem)
        val found = DeveloperMarketplaceCatalog.search("Custom Rust Linter")
        assertTrue("Should find custom item", found.isNotEmpty())
        assertTrue("Custom flag should be set", found.first().isCustom)

        DeveloperMarketplaceCatalog.removeCustomItem(found.first().id)
        val afterRemoval = DeveloperMarketplaceCatalog.search("Custom Rust Linter")
        assertTrue("Should no longer find removed custom item", afterRemoval.isEmpty())
    }
}
