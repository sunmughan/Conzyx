package com.codeair.mhe.marketplace

import java.util.UUID

enum class MarketplaceCategory {
    AGENTS,
    SKILLS,
    MCP_SERVERS,
    TEMPLATES
}

data class MarketplaceItem(
    /**
     * Stable identity. Curated items MUST pass an explicit `catalog-*` id:
     * the install manager keys its durable records by id, so a regenerated
     * id would orphan installs on every restart. Custom (user-published)
     * items get a random UUID.
     */
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: MarketplaceCategory,
    val author: String,
    val description: String,
    /**
     * No ratings exist: there is no rating/review submission anywhere in
     * the app, so the default is 0.0 (shown as "No ratings yet"), never a
     * fabricated 4.8.
     */
    val rating: Double = 0.0,
    /** No download counting exists; the default is 0, never a fabricated 1200. */
    val downloads: Int = 0,
    val installUrl: String,
    val isInstalled: Boolean = false,
    val isCustom: Boolean = false,
    val tags: List<String> = emptyList(),
    /**
     * Expected SHA-256 of the downloadable artifact (hex, lowercase).
     * Blank = the listing provides no hash, so nothing is verified.
     * When non-blank, [MarketplaceInstallManager] refuses a download
     * whose bytes do not hash to this value.
     */
    val sha256: String = "",
)

/**
 * AI Developer Marketplace Catalog (Phase 14).
 * Provides a discovery, 1-click installation, and custom registry for Agents, Skills, MCP Servers, and Templates.
 */
object DeveloperMarketplaceCatalog {

    val curatedCatalog: List<MarketplaceItem> = listOf(
        // MCP Servers
        MarketplaceItem(
            id = "catalog-mcp-postgres",
            name = "PostgreSQL MCP Server",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "Model Context Protocol",
            description = "Read, inspect schemas, and query local & remote Postgres databases securely.",
            installUrl = "npx -y @modelcontextprotocol/server-postgres",
            tags = listOf("sql", "database", "postgres", "mcp"),
        ),
        MarketplaceItem(
            id = "catalog-mcp-puppeteer",
            name = "Puppeteer Browser MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "Model Context Protocol",
            description = "Automate Chromium browser, inspect DOM, take screenshots, and scrape web pages.",
            installUrl = "npx -y @modelcontextprotocol/server-puppeteer",
            tags = listOf("browser", "automation", "dom", "headless"),
        ),
        MarketplaceItem(
            id = "catalog-mcp-github",
            name = "GitHub Context MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "GitHub",
            description = "Fetch PRs, issues, diffs, and repository metadata directly into agent context.",
            installUrl = "npx -y @modelcontextprotocol/server-github",
            tags = listOf("github", "git", "pull-request", "ci"),
        ),
        MarketplaceItem(
            id = "catalog-mcp-filesystem",
            name = "Filesystem Extended MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "Anthropic",
            description = "High-speed safe directory traversal, file search, and sandbox manipulation.",
            installUrl = "npx -y @modelcontextprotocol/server-filesystem",
            tags = listOf("files", "storage", "io"),
        ),
        MarketplaceItem(
            id = "catalog-mcp-brave-search",
            name = "Brave Search & Web Research MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "Brave Software",
            description = "Real-time web search and content distillation for grounded factual coding.",
            installUrl = "npx -y @modelcontextprotocol/server-brave-search",
            tags = listOf("web", "search", "research"),
        ),

        // Specialized Skills
        MarketplaceItem(
            id = "catalog-skill-compose-architect",
            name = "Jetpack Compose Architect",
            category = MarketplaceCategory.SKILLS,
            author = "Android Dev Community",
            description = "Best practices, performance guidelines, and state hoisting for modern Android UI.",
            installUrl = "https://github.com/pocketdev/skills-compose-architect",
            tags = listOf("android", "compose", "ui", "kotlin"),
        ),
        MarketplaceItem(
            id = "catalog-skill-devops-packager",
            name = "DevOps & Docker Packager",
            category = MarketplaceCategory.SKILLS,
            author = "DevOps Labs",
            description = "Multi-stage Dockerfiles, GitHub Actions workflows, and Linux container automation.",
            installUrl = "https://github.com/pocketdev/skills-devops-packager",
            tags = listOf("docker", "devops", "ci-cd", "containers"),
        ),
        MarketplaceItem(
            id = "catalog-skill-nextjs-wizard",
            name = "Next.js 15 Full-Stack Wizard",
            category = MarketplaceCategory.SKILLS,
            author = "React Core Community",
            description = "Server components, server actions, route handlers, and SEO optimization for Next.js.",
            installUrl = "https://github.com/pocketdev/skills-nextjs-wizard",
            tags = listOf("react", "nextjs", "typescript", "fullstack"),
        ),
        MarketplaceItem(
            id = "catalog-skill-rust-systems",
            name = "Rust Memory & Systems Optimization",
            category = MarketplaceCategory.SKILLS,
            author = "Rust Foundation Contribs",
            description = "Borrow checker mastery, zero-cost abstractions, and unsafe code auditing.",
            installUrl = "https://github.com/pocketdev/skills-rust-systems",
            tags = listOf("rust", "systems", "memory", "concurrency"),
        ),

        // Templates
        MarketplaceItem(
            id = "catalog-template-android-compose",
            name = "Android Kotlin Clean Starter",
            category = MarketplaceCategory.TEMPLATES,
            author = "Mobile Harness Extended",
            description = "Jetpack Compose + Material 3 + Coroutines production starter with Gradle kts.",
            installUrl = "template://android-compose",
            tags = listOf("android", "kotlin", "template", "starter"),
        ),
        MarketplaceItem(
            id = "catalog-template-python-fastapi",
            name = "FastAPI Backend Microservice",
            category = MarketplaceCategory.TEMPLATES,
            author = "Python Squad",
            description = "FastAPI with Pydantic v2, async endpoints, SQLAlchemy 2.0, and pytest suite.",
            installUrl = "template://python-fastapi",
            tags = listOf("python", "fastapi", "backend", "api"),
        ),
        MarketplaceItem(
            id = "catalog-template-react-native-expo",
            name = "React Native Expo Mobile Starter",
            category = MarketplaceCategory.TEMPLATES,
            author = "Mobile Harness Extended",
            description = "TypeScript Expo router starter with NativeWind styling and offline SQLite caching.",
            installUrl = "template://react-native-expo",
            tags = listOf("react-native", "expo", "mobile", "typescript"),
        ),
        MarketplaceItem(
            id = "catalog-template-rust-axum",
            name = "Rust Axum High-Performance API",
            category = MarketplaceCategory.TEMPLATES,
            author = "Systems Lab",
            description = "Blazing fast Tokio + Axum async HTTP server with Tower middleware and tracing.",
            installUrl = "template://rust-axum",
            tags = listOf("rust", "axum", "tokio", "api"),
        ),

        // Specialized Agents
        MarketplaceItem(
            id = "catalog-agent-claude-3-7-sonnet",
            name = "Claude 3.7 Sonnet Engineer",
            category = MarketplaceCategory.AGENTS,
            author = "Anthropic",
            description = "Hybrid reasoning and coding model with extended thinking for complex engineering.",
            installUrl = "agent://claude-3-7-sonnet",
            tags = listOf("claude", "reasoning", "coding", "agent"),
        ),
        MarketplaceItem(
            id = "catalog-agent-deepseek-r1",
            name = "DeepSeek Coder R1",
            category = MarketplaceCategory.AGENTS,
            author = "DeepSeek AI",
            description = "Open reasoning model optimized for mathematical logic, algorithms, and debugging.",
            installUrl = "agent://deepseek-r1",
            tags = listOf("deepseek", "reasoning", "algorithms"),
        ),
        MarketplaceItem(
            id = "catalog-agent-gemini-2-5-pro",
            name = "Gemini 2.5 Pro Agent",
            category = MarketplaceCategory.AGENTS,
            author = "Google DeepMind",
            description = "Long context window (1M+ tokens) with multimodal reasoning and deep refactoring.",
            installUrl = "agent://gemini-2-5-pro",
            tags = listOf("gemini", "google", "multimodal", "agent"),
        ),
        MarketplaceItem(
            id = "catalog-agent-llama-3-3-70b",
            name = "Llama 3.3 70B Code Specialist",
            category = MarketplaceCategory.AGENTS,
            author = "Meta AI",
            description = "State-of-the-art open-weights model fine-tuned for syntax precision and speed.",
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
