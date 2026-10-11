package com.jarves.mh.workspace

import java.io.File
import java.nio.file.Files
import java.util.regex.Pattern

data class SearchMatch(
    val filePath: String,
    val lineNumber: Int,
    val lineContent: String,
    val matchStart: Int,
    val matchEnd: Int,
    val matchText: String,
)

data class SearchResult(
    val query: String,
    val matches: List<SearchMatch>,
    val totalFilesSearched: Int,
    val totalMatchesCount: Int,
    val isTruncated: Boolean = false,
)

/**
 * High-performance project-wide search and replace engine.
 */
object GlobalSearchEngine {

    private const val MAX_MATCHES_LIMIT = 500
    private const val MAX_FILE_SIZE_BYTES = 2L * 1024L * 1024L

    private val IGNORED_DIRECTORIES = setOf(
        ".git", ".gradle", ".idea", "build", "node_modules", "target", "dist", ".agents",
    )

    fun search(
        workspaceDir: File,
        query: String,
        isRegex: Boolean = false,
        matchCase: Boolean = false,
        wholeWord: Boolean = false,
        fileFilter: String? = null,
    ): SearchResult {
        if (query.isEmpty() || !workspaceDir.isDirectory) {
            return SearchResult(query, emptyList(), 0, 0)
        }

        val pattern = buildRegexPattern(query, isRegex, matchCase, wholeWord)
            ?: return SearchResult(query, emptyList(), 0, 0)
        val filterRegex = fileFilter?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }

        val canonicalRoot = workspaceDir.canonicalFile
        val matches = mutableListOf<SearchMatch>()
        var filesSearched = 0
        var isTruncated = false

        val rootPath = canonicalRoot.toPath()
        canonicalRoot.walkTopDown()
            .onEnter { dir ->
                dir == canonicalRoot || (
                    dir.name !in IGNORED_DIRECTORIES &&
                        !Files.isSymbolicLink(dir.toPath()) &&
                        runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .filter { file ->
                file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                    file.length() <= MAX_FILE_SIZE_BYTES &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false) &&
                    (filterRegex == null || filterRegex.matches(file.name))
            }
            .forEach { file ->
                if (matches.size >= MAX_MATCHES_LIMIT) {
                    isTruncated = true
                    return@forEach
                }

                filesSearched++
                val relPath = file.relativeTo(canonicalRoot).invariantSeparatorsPath

                runCatching {
                    file.useLines { lines ->
                        var lineNum = 1
                        for (line in lines) {
                            if (matches.size >= MAX_MATCHES_LIMIT) {
                                isTruncated = true
                                break
                            }
                            val matcher = pattern.matcher(line)
                            while (matcher.find()) {
                                matches.add(
                                    SearchMatch(
                                        filePath = relPath,
                                        lineNumber = lineNum,
                                        lineContent = line.trim(),
                                        matchStart = matcher.start(),
                                        matchEnd = matcher.end(),
                                        matchText = matcher.group(),
                                    )
                                )
                                if (matches.size >= MAX_MATCHES_LIMIT) {
                                    isTruncated = true
                                    break
                                }
                            }
                            lineNum++
                        }
                    }
                }
            }

        return SearchResult(
            query = query,
            matches = matches,
            totalFilesSearched = filesSearched,
            totalMatchesCount = matches.size,
            isTruncated = isTruncated,
        )
    }

    /** Replaces occurrences of [query] in a specific file. */
    fun replaceInFile(
        file: File,
        query: String,
        replacement: String,
        isRegex: Boolean = false,
        matchCase: Boolean = false,
        wholeWord: Boolean = false,
    ): Int {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || query.isEmpty()) return 0
        val pattern = buildRegexPattern(query, isRegex, matchCase, wholeWord) ?: return 0
        val original = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return 0
        val matcher = pattern.matcher(original)
        var count = 0
        val sb = StringBuffer()
        while (matcher.find()) {
            count++
            matcher.appendReplacement(sb, MatcherQuoteReplacement(replacement))
        }
        matcher.appendTail(sb)

        if (count > 0 && !SafeFileOps.atomicWriteText(file, sb.toString())) return 0
        return count
    }

    /**
     * Replaces matches in every eligible workspace file. This deliberately does not
     * use the UI search result list because that list is capped for responsiveness;
     * a capped preview must never silently cap a destructive replace operation.
     */
    fun replaceAll(
        workspaceDir: File,
        query: String,
        replacement: String,
        isRegex: Boolean = false,
        matchCase: Boolean = false,
        wholeWord: Boolean = false,
        fileFilter: String? = null,
    ): Int {
        if (query.isEmpty() || !workspaceDir.isDirectory ||
            buildRegexPattern(query, isRegex, matchCase, wholeWord) == null
        ) return 0

        val canonicalRoot = workspaceDir.canonicalFile
        val filterRegex = fileFilter?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        var totalReplaced = 0
        val rootPath = canonicalRoot.toPath()
        canonicalRoot.walkTopDown()
            .onEnter { dir ->
                dir == canonicalRoot || (
                    dir.name !in IGNORED_DIRECTORIES &&
                        !Files.isSymbolicLink(dir.toPath()) &&
                        runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .filter { file ->
                file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                    file.length() <= MAX_FILE_SIZE_BYTES &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false) &&
                    (filterRegex == null || filterRegex.matches(file.name))
            }
            .forEach { file ->
                totalReplaced += replaceInFile(file, query, replacement, isRegex, matchCase, wholeWord)
            }
        return totalReplaced
    }

    private fun buildRegexPattern(
        query: String,
        isRegex: Boolean,
        matchCase: Boolean,
        wholeWord: Boolean,
    ): Pattern? = runCatching {
        val baseRegex = if (isRegex) query else Pattern.quote(query)
        val wordRegex = if (wholeWord) "\\b$baseRegex\\b" else baseRegex
        val flags = if (matchCase) 0 else Pattern.CASE_INSENSITIVE
        Pattern.compile(wordRegex, flags)
    }.getOrNull()

    private fun globToRegex(glob: String): Regex {
        val pattern = glob
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return Regex("^$pattern$", RegexOption.IGNORE_CASE)
    }

    private fun MatcherQuoteReplacement(s: String): String =
        java.util.regex.Matcher.quoteReplacement(s)
}
