package com.jarves.mh.marketplace

import java.util.UUID

enum class MarketplaceCategory {
    AGENTS,
    SKILLS,
    MCP_SERVERS,
    TEMPLATES
}

data class MarketplaceItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: MarketplaceCategory,
    val author: String,
    val description: String,
    val rating: Double = 4.8,
    val downloads: Int = 1200,
    val installUrl: String,
    val isInstalled: Boolean = false
)

/**
 * AI Developer Marketplace Catalog (Phase 14).
 * Provides a discovery and 1-click installation registry for Agents, Skills, MCP Servers, and Templates.
 */
object DeveloperMarketplaceCatalog {

    val curatedCatalog: List<MarketplaceItem> = listOf(
        // MCP Servers
        MarketplaceItem(
            name = "PostgreSQL MCP Server",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "Model Context Protocol",
            description = "Read, inspect schemas, and query local & remote Postgres databases securely.",
            rating = 4.9,
            downloads = 3400,
            installUrl = "npx -y @modelcontextprotocol/server-postgres"
        ),
        MarketplaceItem(
            name = "Puppeteer Browser MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "Model Context Protocol",
            description = "Automate Chromium browser, inspect DOM, take screenshots, and scrape web pages.",
            rating = 4.8,
            downloads = 5100,
            installUrl = "npx -y @modelcontextprotocol/server-puppeteer"
        ),
        MarketplaceItem(
            name = "GitHub Context MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "GitHub",
            description = "Fetch PRs, issues, diffs, and repository metadata directly into agent context.",
            rating = 4.9,
            downloads = 8900,
            installUrl = "npx -y @modelcontextprotocol/server-github"
        ),

        // Specialized Skills
        MarketplaceItem(
            name = "Jetpack Compose Architect",
            category = MarketplaceCategory.SKILLS,
            author = "Android Dev Community",
            description = "Best practices, performance guidelines, and state hoisting for modern Android UI.",
            rating = 4.9,
            downloads = 4200,
            installUrl = "https://github.com/pocketdev/skills-compose-architect"
        ),
        MarketplaceItem(
            name = "DevOps & Docker Packager",
            category = MarketplaceCategory.SKILLS,
            author = "DevOps Labs",
            description = "Multi-stage Dockerfiles, GitHub Actions workflows, and Linux container automation.",
            rating = 4.7,
            downloads = 2900,
            installUrl = "https://github.com/pocketdev/skills-devops-packager"
        ),

        // Templates
        MarketplaceItem(
            name = "Android Kotlin Clean Starter",
            category = MarketplaceCategory.TEMPLATES,
            author = "Mobile Harness",
            description = "Jetpack Compose + Material 3 + Coroutines production starter with Gradle kts.",
            rating = 5.0,
            downloads = 7400,
            installUrl = "template://android-compose"
        ),
        MarketplaceItem(
            name = "FastAPI Backend Microservice",
            category = MarketplaceCategory.TEMPLATES,
            author = "Python Squad",
            description = "FastAPI with Pydantic v2, async endpoints, SQLAlchemy 2.0, and pytest suite.",
            rating = 4.8,
            downloads = 3800,
            installUrl = "template://python-fastapi"
        )
    )

    fun filterByCategory(category: MarketplaceCategory): List<MarketplaceItem> {
        return curatedCatalog.filter { it.category == category }
    }

    fun search(query: String): List<MarketplaceItem> {
        if (query.isBlank()) return curatedCatalog
        val q = query.trim().lowercase()
        return curatedCatalog.filter {
            it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
    }
}
