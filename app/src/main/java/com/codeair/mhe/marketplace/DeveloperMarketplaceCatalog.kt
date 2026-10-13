package com.codeair.mhe.marketplace

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
    val isInstalled: Boolean = false,
    val isCustom: Boolean = false,
    val tags: List<String> = emptyList(),
)

/**
 * AI Developer Marketplace Catalog (Phase 14).
 * Provides a discovery, 1-click installation, and custom registry for Agents, Skills, MCP Servers, and Templates.
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
            installUrl = "npx -y @modelcontextprotocol/server-postgres",
            tags = listOf("sql", "database", "postgres", "mcp"),
        ),
        MarketplaceItem(
            name = "Puppeteer Browser MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "Model Context Protocol",
            description = "Automate Chromium browser, inspect DOM, take screenshots, and scrape web pages.",
            rating = 4.8,
            downloads = 5100,
            installUrl = "npx -y @modelcontextprotocol/server-puppeteer",
            tags = listOf("browser", "automation", "dom", "headless"),
        ),
        MarketplaceItem(
            name = "GitHub Context MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "GitHub",
            description = "Fetch PRs, issues, diffs, and repository metadata directly into agent context.",
            rating = 4.9,
            downloads = 8900,
            installUrl = "npx -y @modelcontextprotocol/server-github",
            tags = listOf("github", "git", "pull-request", "ci"),
        ),
        MarketplaceItem(
            name = "Filesystem Extended MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "Anthropic",
            description = "High-speed safe directory traversal, file search, and sandbox manipulation.",
            rating = 4.8,
            downloads = 6400,
            installUrl = "npx -y @modelcontextprotocol/server-filesystem",
            tags = listOf("files", "storage", "io"),
        ),
        MarketplaceItem(
            name = "Brave Search & Web Research MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "Brave Software",
            description = "Real-time web search and content distillation for grounded factual coding.",
            rating = 4.7,
            downloads = 4100,
            installUrl = "npx -y @modelcontextprotocol/server-brave-search",
            tags = listOf("web", "search", "research"),
        ),

        // Specialized Skills
        MarketplaceItem(
            name = "Jetpack Compose Architect",
            category = MarketplaceCategory.SKILLS,
            author = "Android Dev Community",
            description = "Best practices, performance guidelines, and state hoisting for modern Android UI.",
            rating = 4.9,
            downloads = 4200,
            installUrl = "https://github.com/pocketdev/skills-compose-architect",
            tags = listOf("android", "compose", "ui", "kotlin"),
        ),
        MarketplaceItem(
            name = "DevOps & Docker Packager",
            category = MarketplaceCategory.SKILLS,
            author = "DevOps Labs",
            description = "Multi-stage Dockerfiles, GitHub Actions workflows, and Linux container automation.",
            rating = 4.7,
            downloads = 2900,
            installUrl = "https://github.com/pocketdev/skills-devops-packager",
            tags = listOf("docker", "devops", "ci-cd", "containers"),
        ),
        MarketplaceItem(
            name = "Next.js 15 Full-Stack Wizard",
            category = MarketplaceCategory.SKILLS,
            author = "React Core Community",
            description = "Server components, server actions, route handlers, and SEO optimization for Next.js.",
            rating = 4.9,
            downloads = 6800,
            installUrl = "https://github.com/pocketdev/skills-nextjs-wizard",
            tags = listOf("react", "nextjs", "typescript", "fullstack"),
        ),
        MarketplaceItem(
            name = "Rust Memory & Systems Optimization",
            category = MarketplaceCategory.SKILLS,
            author = "Rust Foundation Contribs",
            description = "Borrow checker mastery, zero-cost abstractions, and unsafe code auditing.",
            rating = 5.0,
            downloads = 3200,
            installUrl = "https://github.com/pocketdev/skills-rust-systems",
            tags = listOf("rust", "systems", "memory", "concurrency"),
        ),

        // Templates
        MarketplaceItem(
            name = "Android Kotlin Clean Starter",
            category = MarketplaceCategory.TEMPLATES,
            author = "Mobile Harness Extended",
            description = "Jetpack Compose + Material 3 + Coroutines production starter with Gradle kts.",
            rating = 5.0,
            downloads = 7400,
            installUrl = "template://android-compose",
            tags = listOf("android", "kotlin", "template", "starter"),
        ),
        MarketplaceItem(
            name = "FastAPI Backend Microservice",
            category = MarketplaceCategory.TEMPLATES,
            author = "Python Squad",
            description = "FastAPI with Pydantic v2, async endpoints, SQLAlchemy 2.0, and pytest suite.",
            rating = 4.8,
            downloads = 3800,
            installUrl = "template://python-fastapi",
            tags = listOf("python", "fastapi", "backend", "api"),
        ),
        MarketplaceItem(
            name = "React Native Expo Mobile Starter",
            category = MarketplaceCategory.TEMPLATES,
            author = "Mobile Harness Extended",
            description = "TypeScript Expo router starter with NativeWind styling and offline SQLite caching.",
            rating = 4.8,
            downloads = 5300,
            installUrl = "template://react-native-expo",
            tags = listOf("react-native", "expo", "mobile", "typescript"),
        ),
        MarketplaceItem(
            name = "Rust Axum High-Performance API",
            category = MarketplaceCategory.TEMPLATES,
            author = "Systems Lab",
            description = "Blazing fast Tokio + Axum async HTTP server with Tower middleware and tracing.",
            rating = 4.9,
            downloads = 2600,
            installUrl = "template://rust-axum",
            tags = listOf("rust", "axum", "tokio", "api"),
        ),

        // Specialized Agents
        MarketplaceItem(
            name = "Claude 3.7 Sonnet Engineer",
            category = MarketplaceCategory.AGENTS,
            author = "Anthropic",
            description = "Hybrid reasoning and coding model with extended thinking for complex engineering.",
            rating = 4.9,
            downloads = 14200,
            installUrl = "agent://claude-3-7-sonnet",
            tags = listOf("claude", "reasoning", "coding", "agent"),
        ),
        MarketplaceItem(
            name = "DeepSeek Coder R1",
            category = MarketplaceCategory.AGENTS,
            author = "DeepSeek AI",
            description = "Open reasoning model optimized for mathematical logic, algorithms, and debugging.",
            rating = 4.8,
            downloads = 9800,
            installUrl = "agent://deepseek-r1",
            tags = listOf("deepseek", "reasoning", "algorithms"),
        ),
        MarketplaceItem(
            name = "Gemini 2.5 Pro Agent",
            category = MarketplaceCategory.AGENTS,
            author = "Google DeepMind",
            description = "Long context window (1M+ tokens) with multimodal reasoning and deep refactoring.",
            rating = 4.9,
            downloads = 11300,
            installUrl = "agent://gemini-2-5-pro",
            tags = listOf("gemini", "google", "multimodal", "agent"),
        ),
        MarketplaceItem(
            name = "Llama 3.3 70B Code Specialist",
            category = MarketplaceCategory.AGENTS,
            author = "Meta AI",
            description = "State-of-the-art open-weights model fine-tuned for syntax precision and speed.",
            rating = 4.7,
            downloads = 7600,
            installUrl = "agent://llama-3-3-70b",
            tags = listOf("llama", "meta", "open-source"),
        )
    )

    private val installedItemIds = mutableSetOf<String>()
    private val customItems = mutableListOf<MarketplaceItem>()

    /**
     * @deprecated Install state is owned by [MarketplaceInstallManager] (durable,
     * real installation). This in-memory set is kept only for backward
     * compatibility and must not be treated as the source of truth.
     */
    @Deprecated(
        "Install state is owned by MarketplaceInstallManager; do not use the catalog's in-memory set.",
        ReplaceWith("MarketplaceInstallManager.isInstalled(itemId)")
    )
    fun markInstalled(itemId: String) {
        installedItemIds.add(itemId)
    }

    @Deprecated(
        "Install state is owned by MarketplaceInstallManager; do not use the catalog's in-memory set.",
        ReplaceWith("MarketplaceInstallManager.uninstall(itemId)")
    )
    fun uninstallItem(itemId: String) {
        installedItemIds.remove(itemId)
    }

    @Deprecated(
        "Install state is owned by MarketplaceInstallManager; do not use the catalog's in-memory set."
    )
    fun toggleInstall(itemId: String): Boolean {
        return if (installedItemIds.contains(itemId)) {
            installedItemIds.remove(itemId)
            false
        } else {
            installedItemIds.add(itemId)
            true
        }
    }

    @Deprecated(
        "Install state is owned by MarketplaceInstallManager; do not use the catalog's in-memory set.",
        ReplaceWith("MarketplaceInstallManager.isInstalled(itemId)")
    )
    fun isInstalled(itemId: String): Boolean {
        return installedItemIds.contains(itemId)
    }

    fun getInstalledItems(): List<MarketplaceItem> {
        return getAllItems().filter { isInstalled(it.id) }
    }

    fun getAllItems(): List<MarketplaceItem> {
        return curatedCatalog + customItems
    }

    fun addCustomItem(item: MarketplaceItem) {
        // Dedupe by id: persisted items are reloaded into the catalog on
        // every coordinator start, and must not duplicate on re-add.
        customItems.removeAll { it.id == item.id }
        customItems.add(item.copy(isCustom = true))
    }

    fun removeCustomItem(itemId: String) {
        customItems.removeAll { it.id == itemId }
        installedItemIds.remove(itemId)
    }

    fun filterByCategory(category: MarketplaceCategory): List<MarketplaceItem> {
        return getAllItems().filter { it.category == category }
    }

    fun search(query: String): List<MarketplaceItem> {
        if (query.isBlank()) return getAllItems()
        val q = query.trim().lowercase()
        return getAllItems().filter {
            it.name.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.author.lowercase().contains(q) ||
                it.tags.any { tag -> tag.lowercase().contains(q) }
        }
    }
}
