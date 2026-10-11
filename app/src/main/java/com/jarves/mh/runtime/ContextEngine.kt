package com.jarves.mh.runtime

import com.jarves.mh.workspace.GitDomainController
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ContextReference(
    val path: String,
    val language: String,
    val symbols: List<String>,
    val imports: List<String>,
    val reason: String,
)

/**
 * AI Context Engine 2.0.
 * Implements the 10-step context pipeline:
 * Request -> Intent -> Project Overview -> Symbol Retrieval -> Dependency Retrieval ->
 * Git Context -> Relevant Files & Code Blocks -> Context Ranking -> Token Budget Allocation -> Agent Context.
 */
class ContextEngine(
    private val projectIndex: ProjectIndex,
    val memoryStore: ProjectMemoryStore? = null,
    private val workspaceSemanticIndex: WorkspaceSemanticIndex? = null,
    private val gitDomainController: GitDomainController? = null,
) {
    companion object {
        private val CONVERSATIONAL_KEYWORDS = setOf(
            "yes", "no", "ok", "okay", "continue", "proceed", "go ahead",
            "approve", "approved", "done", "next", "thanks", "thank you",
            "start", "run", "build", "stop", "cancel", "help",
        )
    }

    private data class CacheKey(
        val projectId: String,
        val workspaceFingerprint: String,
        val requestHash: Int,
    )

    private val cache = ConcurrentHashMap<CacheKey, Pair<Long, String>>()
    private val cacheTtlMillis = 60_000L // 60 seconds

    fun clearCache() {
        cache.clear()
    }

    private fun isConversationalOrBrief(request: String): Boolean {
        val trimmed = request.trim().lowercase()
        if (trimmed.length < 3) return true
        if (trimmed in CONVERSATIONAL_KEYWORDS) return true
        val words = trimmed.split("\\s+".toRegex())
        return words.size <= 2 && words.all { it in CONVERSATIONAL_KEYWORDS }
    }

    fun buildPromptContext(
        projectId: String,
        workspace: File,
        request: String,
        mentionedPaths: List<String> = emptyList(),
        gitModifiedPaths: List<String> = emptyList(),
        maxReferences: Int = 12,
        maxTokenBudget: Int = 3000,
    ): String {
        if (isConversationalOrBrief(request) && mentionedPaths.isEmpty()) return ""

        val snapshot = projectIndex.ensureFresh(projectId, workspace)

        // Cache lookup
        val cacheKey = CacheKey(
            projectId = projectId,
            workspaceFingerprint = snapshot.rootFingerprint,
            requestHash = (request.trim().lowercase() + mentionedPaths.joinToString(",")).hashCode(),
        )
        val cached = cache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.first) < cacheTtlMillis) {
            return cached.second
        }

        // 1. Intent Detection
        val intent = ContextRankingEngine.detectIntent(request)

        // 2. Error & Stack Trace Extraction
        val errorContext = ErrorContextExtractor.extractErrorContext(request)

        // 3. Project Overview & Architectural Understanding
        val projectOverview = workspaceSemanticIndex?.getProjectOverview()

        // 4. Git-aware Context Retrieval
        val activeGitModified = if (gitModifiedPaths.isNotEmpty()) {
            gitModifiedPaths
        } else if (gitDomainController != null) {
            val statusRes = gitDomainController.status(workspace)
            if (statusRes.success) {
                statusRes.output.lines()
                    .filter { it.length > 3 }
                    .map { it.substring(3).trim() }
            } else emptyList()
        } else emptyList()

        // 5. Symbol & Dependency Graph Retrieval
        val dependencyGraph = workspaceSemanticIndex?.getDependencyGraph()
        val symbolGraph = workspaceSemanticIndex?.getSymbolGraph()

        // 6. Project & Task Memory Retrieval (all 7 categories + query search)
        val allMemories = memoryStore?.getMemories(projectId).orEmpty()
        val searchedMemories = memoryStore?.search(projectId, request, limit = 6).orEmpty()
        val combinedMemories = (searchedMemories + allMemories).distinctBy { it.id }

        // 7. Multi-Factor File Ranking
        val ranked = ContextRankingEngine.rankFiles(
            files = snapshot.files,
            request = request,
            mentionedPaths = mentionedPaths,
            dependencyGraph = dependencyGraph,
            symbolGraph = symbolGraph,
            gitModifiedPaths = activeGitModified,
            errorContext = errorContext,
            limit = maxReferences,
        )

        // 8. Relevant Code Block Extraction
        val codeBlocks = ContextRankingEngine.extractRelevantCodeBlocks(
            workspaceDir = workspace,
            rankedFiles = ranked,
            maxTotalChars = (maxTokenBudget * 2),
        )

        // 9. Context Compression & Token Budget Enforcement
        val compressed = ContextRankingEngine.compressContext(
            intent = intent,
            ranked = ranked,
            memories = combinedMemories,
            codeBlocks = codeBlocks,
            projectOverview = projectOverview,
            errorContext = errorContext,
            maxTokenBudget = maxTokenBudget,
        )

        // 10. Store in cache
        cache[cacheKey] = now to compressed

        return compressed
    }
}
