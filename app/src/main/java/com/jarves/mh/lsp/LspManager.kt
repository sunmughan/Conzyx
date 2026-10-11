package com.jarves.mh.lsp

import com.jarves.mh.editor.CodeCompletionEngine
import com.jarves.mh.editor.CompletionItemKind
import com.jarves.mh.editor.EditorDiagnosticsEngine
import com.jarves.mh.runtime.CodeSymbol
import com.jarves.mh.runtime.SemanticCodeEngine
import com.jarves.mh.runtime.SymbolKind
import com.jarves.mh.runtime.WorkspaceSemanticIndex
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Language Server Protocol (LSP) Manager.
 * Orchestrates language server process lifecycles and provides unified
 * Hover, Definition, References, Completions, and Code Actions.
 * Features an intelligent in-process fallback provider using SemanticCodeEngine.
 */
class LspManager(
    private val workspaceDir: File? = null,
    private val semanticIndex: WorkspaceSemanticIndex = WorkspaceSemanticIndex(workspaceDir),
) {

    private val serverStatuses = ConcurrentHashMap<String, LspServerStatus>()
    private val activeProcesses = ConcurrentHashMap<String, Process>()

    init {
        // Initialize supported language statuses
        listOf("Kotlin", "Java", "TypeScript", "JavaScript", "Python", "C++", "Go", "Rust", "PHP", "Dart").forEach { lang ->
            serverStatuses[lang] = LspServerStatus(
                language = lang,
                isRunning = true,
                isBuiltInFallback = true,
            )
        }
    }

    fun getServerStatus(language: String): LspServerStatus {
        return serverStatuses[language] ?: LspServerStatus(
            language = language,
            isRunning = true,
            isBuiltInFallback = true,
        )
    }

    fun startServer(language: String, rootPath: String): Boolean {
        // Record active server status
        serverStatuses[language] = LspServerStatus(
            language = language,
            isRunning = true,
            isBuiltInFallback = true,
        )
        return true
    }

    fun stopServer(language: String): Boolean {
        activeProcesses.remove(language)?.destroy()
        serverStatuses[language] = LspServerStatus(
            language = language,
            isRunning = false,
            isBuiltInFallback = false,
        )
        return true
    }

    fun restartServer(language: String, rootPath: String): Boolean {
        stopServer(language)
        return startServer(language, rootPath)
    }

    // --- LSP Query Surface with Built-In Fallback Provider ---

    /**
     * Computes Hover documentation and signature for symbol under cursor.
     */
    fun requestHover(
        language: String,
        filePath: String,
        line: Int, // 1-indexed
        character: Int,
        content: String,
    ): LspHover? {
        val word = getWordAt(content, line, character)
        if (word.isBlank()) return null

        val summary = SemanticCodeEngine.parseFile(filePath, content)
        val symbol = summary.symbols.firstOrNull { it.name.equals(word, ignoreCase = true) }
            ?: semanticIndex.getSymbolGraph().findNodesByName(word).firstOrNull()?.let {
                CodeSymbol(it.name, it.kind, it.line, signature = it.signature, docComment = it.docComment, superTypes = it.superTypes)
            }

        if (symbol != null) {
            val docs = buildString {
                appendLine("```$language")
                appendLine(symbol.signature ?: "${symbol.kind.name.lowercase()} ${symbol.name}")
                appendLine("```")
                if (!symbol.docComment.isNullOrBlank()) {
                    appendLine("---")
                    appendLine(symbol.docComment)
                }
                if (symbol.superTypes.isNotEmpty()) {
                    appendLine("Inherits/Implements: ${symbol.superTypes.joinToString(", ")}")
                }
            }.trimEnd()
            return LspHover(contents = docs)
        }

        return LspHover(contents = "`$word` ($language identifier)")
    }

    /**
     * Resolves Go to Definition targets.
     */
    fun requestDefinition(
        language: String,
        filePath: String,
        line: Int,
        character: Int,
        content: String,
    ): List<LspLocation> {
        val word = getWordAt(content, line, character)
        if (word.isBlank()) return emptyList()

        val summaries = semanticIndex.getSummaries().values
        val defs = SemanticCodeEngine.findDefinitions(word, summaries, exactMatch = true)

        return defs.map { (path, sym) ->
            LspLocation(
                uri = path,
                range = LspRange(
                    start = LspPosition(sym.line - 1, sym.characterOffset),
                    end = LspPosition(sym.line - 1, sym.characterOffset + sym.name.length),
                ),
            )
        }
    }

    /**
     * Resolves Find References usages.
     */
    fun requestReferences(
        language: String,
        filePath: String,
        line: Int,
        character: Int,
        content: String,
    ): List<LspLocation> {
        val word = getWordAt(content, line, character)
        if (word.isBlank()) return emptyList()

        val summaries = semanticIndex.getSummaries().values
        val refs = SemanticCodeEngine.findReferences(word, summaries)

        return refs.map { ref ->
            LspLocation(
                uri = ref.sourceFilePath,
                range = LspRange(
                    start = LspPosition(ref.line - 1, 0),
                    end = LspPosition(ref.line - 1, word.length),
                ),
            )
        }
    }

    /**
     * Computes completions at cursor position.
     */
    fun requestCompletions(
        language: String,
        filePath: String,
        line: Int,
        character: Int,
        content: String,
    ): List<LspCompletionItem> {
        val cursorOffset = getOffsetForPosition(content, line, character)
        val projectSymbols = semanticIndex.getSymbolGraph().nodes.values.map {
            CodeSymbol(it.name, it.kind, it.line, signature = it.signature)
        }

        val items = CodeCompletionEngine.getCompletions(content, cursorOffset, language, projectSymbols)
        return items.map { item ->
            val kind = when (item.kind) {
                CompletionItemKind.KEYWORD -> LspCompletionItemKind.KEYWORD
                CompletionItemKind.FUNCTION -> LspCompletionItemKind.FUNCTION
                CompletionItemKind.CLASS -> LspCompletionItemKind.CLASS
                CompletionItemKind.VARIABLE -> LspCompletionItemKind.VARIABLE
                CompletionItemKind.PROPERTY -> LspCompletionItemKind.PROPERTY
                CompletionItemKind.SNIPPET -> LspCompletionItemKind.SNIPPET
                else -> LspCompletionItemKind.TEXT
            }
            LspCompletionItem(
                label = item.label,
                kind = kind,
                detail = item.detail,
                insertText = item.insertText,
            )
        }
    }

    /**
     * Computes contextual Code Actions (quick fixes & refactorings).
     */
    fun requestCodeActions(
        language: String,
        filePath: String,
        line: Int,
        character: Int,
        content: String,
    ): List<LspCodeAction> {
        val actions = mutableListOf<LspCodeAction>()
        val diagnostics = EditorDiagnosticsEngine.analyzeDocument(content, filePath)
        val lineDiags = diagnostics.filter { it.lineNumber == line }.ifEmpty { diagnostics }

        for (diag in lineDiags) {
            when (diag.ruleId) {
                "syntax.unclosed_delimiter" -> {
                    actions.add(
                        LspCodeAction(
                            title = "Insert matching closing delimiter",
                            kind = "quickfix",
                            isPreferred = true,
                        )
                    )
                }
                "linter.duplicate_import" -> {
                    actions.add(
                        LspCodeAction(
                            title = "Remove duplicate import statement",
                            kind = "quickfix",
                            isPreferred = true,
                        )
                    )
                }
                "linter.empty_catch" -> {
                    actions.add(
                        LspCodeAction(
                            title = "Add logger or rethrow in empty catch block",
                            kind = "quickfix",
                        )
                    )
                }
            }
        }

        // Generic refactoring actions
        actions.add(LspCodeAction(title = "Extract variable / constant", kind = "refactor.extract"))
        actions.add(LspCodeAction(title = "Extract function / method", kind = "refactor.extract"))

        return actions
    }

    private fun getWordAt(content: String, lineNum: Int, charNum: Int): String {
        val lines = content.lines()
        if (lineNum !in 1..lines.size) return ""
        val line = lines[lineNum - 1]
        val col = charNum.coerceIn(0, line.length)
        if (line.isEmpty()) return ""

        var start = (col - 1).coerceAtLeast(0)
        while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) start--
        var end = col
        while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++

        return if (start < end) line.substring(start, end).trim() else ""
    }

    private fun getOffsetForPosition(content: String, lineNum: Int, charNum: Int): Int {
        val lines = content.lines()
        var offset = 0
        for (i in 0 until (lineNum - 1).coerceAtMost(lines.size)) {
            offset += lines[i].length + 1
        }
        return (offset + charNum).coerceIn(0, content.length)
    }
}
