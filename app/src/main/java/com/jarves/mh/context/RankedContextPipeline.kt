package com.jarves.mh.context

import com.jarves.mh.lsp.ParserProjectIndex
import com.jarves.mh.runtime.ContextRankingEngine
import com.jarves.mh.runtime.DependencyGraph
import com.jarves.mh.runtime.ErrorContext
import com.jarves.mh.runtime.ErrorContextExtractor
import com.jarves.mh.runtime.IndexedFile
import com.jarves.mh.runtime.RankedContextFile
import com.jarves.mh.runtime.SymbolGraph

/**
 * Milestone 4 — the single ranked context pipeline.
 *
 * Combines every retrieval signal into one ranking pass:
 * explicit mentions, error/stack-trace targets, Git-modified files, the
 * heuristic symbol graph, the dependency graph (M3 real-parser edges merged
 * over heuristic edges — parser wins on conflicts), filename/symbol/import
 * matches. Every decision is recorded in a [ContextTrace].
 */
data class PipelineInput(
    val files: List<IndexedFile>,
    val request: String,
    val mentionedPaths: List<String> = emptyList(),
    val dependencyGraph: DependencyGraph? = null,
    val symbolGraph: SymbolGraph? = null,
    val parserProjectIndex: ParserProjectIndex? = null,
    val gitModifiedPaths: List<String> = emptyList(),
    val errorContext: ErrorContext? = null,
    val limit: Int = 15,
)

data class PipelineResult(
    val ranked: List<RankedContextFile>,
    val trace: ContextTrace,
    /** The dependency graph actually used for ranking (parser-merged when available). */
    val dependencyGraphUsed: DependencyGraph?,
    /** True when M3 real-parser edges contributed to the ranking. */
    val parserBacked: Boolean,
    val errorContext: ErrorContext?,
)

object RankedContextPipeline {

    fun run(
        input: PipelineInput,
        traceBuilder: ContextTraceBuilder = ContextTraceBuilder(),
    ): PipelineResult {
        val errorContext = input.errorContext
            ?: ErrorContextExtractor.extractErrorContext(input.request)

        // M3 real-parser dependency edges, merged over heuristic edges.
        val parserGraph = input.parserProjectIndex?.let { ParserIndexAdapters.toDependencyGraph(it) }
        val mergedGraph = if (parserGraph != null) {
            ParserIndexAdapters.mergeDependencyGraphs(parserGraph, input.dependencyGraph)
        } else {
            input.dependencyGraph
        }
        val parserBacked = parserGraph != null &&
            parserGraph.imports.isNotEmpty()

        val ranked = ContextRankingEngine.rankFiles(
            files = input.files,
            request = input.request,
            mentionedPaths = input.mentionedPaths,
            dependencyGraph = mergedGraph,
            symbolGraph = input.symbolGraph,
            gitModifiedPaths = input.gitModifiedPaths,
            errorContext = errorContext,
            limit = input.limit,
            trace = traceBuilder,
        )

        return PipelineResult(
            ranked = ranked,
            trace = traceBuilder.build(),
            dependencyGraphUsed = mergedGraph,
            parserBacked = parserBacked,
            errorContext = errorContext,
        )
    }
}
