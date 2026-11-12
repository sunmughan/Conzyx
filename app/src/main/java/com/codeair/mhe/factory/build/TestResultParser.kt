package com.codeair.mhe.factory.build

import java.io.File

/**
 * Phase 3 — structured test results.
 *
 * Gradle writes one JUnit XML report per test class under
 * `<module>/build/test-results/<task>/TEST-*.xml`. The live build path used
 * to keep only a raw log tail (10 head + 40 tail lines), so a failing run
 * never said *which* tests failed or how many passed. This parser turns
 * those reports into counts plus the failing test names, with no fake data:
 * it returns null when there is nothing to parse, and a malformed file is
 * skipped instead of inventing results.
 *
 * Deliberately NOT a JAXP/DOM parser: `DocumentBuilderFactory` resolves its
 * provider from the runtime classpath, and that lookup behaves differently
 * across environments (it failed on the CI unit-test runtime for reports
 * that parse fine locally on JDK 17 and 21). Gradle's JUnit XML is a
 * simple machine-generated format, so a small deterministic reader over
 * the text parses it identically everywhere — and, as a side effect, has
 * no XML-parser attack surface at all (no DTDs, no entity expansion).
 *
 * Pure Kotlin: unit-testable on the JVM and usable on device.
 */
data class TestFailure(
    val className: String,
    val testName: String,
    val message: String,
)

data class StructuredTestResults(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val errors: Int,
    val durationMs: Long,
    val failures: List<TestFailure>,
) {
    /** e.g. "Tests: 14 passed, 2 failed, 0 skipped (12.4s)". */
    fun summaryLine(): String {
        val seconds = durationMs / 1000.0
        return "Tests: $passed passed, $failed failed, $skipped skipped " +
            "(${String.format("%.1f", seconds)}s)"
    }

    /** One line per failing test, for the failure prompt and the log. */
    fun failureLines(): List<String> = failures.map { failure ->
        val detail = failure.message.lineSequence().firstOrNull()?.trim().orEmpty()
        "- ${failure.className}.${failure.testName}" +
            if (detail.isNotBlank()) ": ${detail.take(200)}" else ""
    }
}

object TestResultParser {

    private val TESTSUITE_OPEN = Regex("""<testsuite\b([^>]*)>""")
    private val ATTR = Regex("""([\w:.-]+)="([^"]*)"""")
    // The opening tag must NOT be self-closing (<testcase .../>): a lazy
    // body match starting at a self-closing tag would span into the NEXT
    // testcase and misattribute its failure. The [^/>] guard forces the
    // char before '>' to be a real attribute char.
    private val TESTCASE_WITH_BODY =
        Regex("""<testcase\b([^>]*?[^/>])>(.*?)</testcase>""", RegexOption.DOT_MATCHES_ALL)
    private val FAILURE_ELEMENT =
        Regex("""<(failure|error)\b([^>]*?)>(.*?)</(?:failure|error)>""", RegexOption.DOT_MATCHES_ALL)
    private val FAILURE_SELF_CLOSING = Regex("""<(failure|error)\b([^>]*?)/>""")
    private val ENTITY_REF = Regex("""&(#\d+|#x[0-9a-fA-F]+|amp|lt|gt|quot|apos);""")

    /**
     * Parses every `TEST-*.xml` under [resultsDir] (the Gradle
     * `build/test-results/<task>` directory) and aggregates them.
     * Returns null when the directory does not exist, holds no reports, or
     * every report is unreadable — callers must treat null as "no test
     * data", never as zero tests.
     */
    fun parseResultsDir(resultsDir: File): StructuredTestResults? {
        if (!resultsDir.isDirectory) return null
        val reports = resultsDir.listFiles { file ->
            file.isFile && file.name.startsWith("TEST-") && file.name.endsWith(".xml")
        }.orEmpty()
        if (reports.isEmpty()) return null
        var total = 0
        var failed = 0
        var skipped = 0
        var errors = 0
        var durationMs = 0L
        val failures = mutableListOf<TestFailure>()
        var parsedAny = false
        for (report in reports) {
            val parsed = runCatching { parseReport(report) }.getOrNull() ?: continue
            parsedAny = true
            total += parsed.total
            failed += parsed.failed
            skipped += parsed.skipped
            errors += parsed.errors
            durationMs += parsed.durationMs
            failures += parsed.failures
        }
        if (!parsedAny) return null
        return StructuredTestResults(
            total = total,
            passed = (total - failed - skipped - errors).coerceAtLeast(0),
            failed = failed,
            skipped = skipped,
            errors = errors,
            durationMs = durationMs,
            failures = failures,
        )
    }

    private fun parseReport(file: File): StructuredTestResults {
        val text = file.readText()
        // Well-formedness bar: a real Gradle report always closes its
        // testsuite element. A truncated or garbage file (no closing tag)
        // is not a report and must be skipped, not counted as zero tests.
        if (!text.contains("</testsuite>")) error("No closing </testsuite> in ${file.name}")
        // A report without a testsuite element is not a JUnit report at all.
        val suiteMatch = TESTSUITE_OPEN.find(text) ?: error("No <testsuite> in ${file.name}")
        val attrs = parseAttrs(suiteMatch.groupValues[1])
        val total = attrs["tests"]?.toIntOrNull() ?: 0
        val failed = attrs["failures"]?.toIntOrNull() ?: 0
        val errors = attrs["errors"]?.toIntOrNull() ?: 0
        val skipped = attrs["skipped"]?.toIntOrNull() ?: 0
        val durationMs = ((attrs["time"]?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        val failures = mutableListOf<TestFailure>()
        for (caseMatch in TESTCASE_WITH_BODY.findAll(text)) {
            val caseAttrs = parseAttrs(caseMatch.groupValues[1])
            val body = caseMatch.groupValues[2]
            val failure = FAILURE_ELEMENT.find(body)
                ?: FAILURE_SELF_CLOSING.find(body)
                ?: continue
            val failureAttrs = parseAttrs(failure.groupValues[2])
            val message = failureAttrs["message"]?.ifBlank { null }
                ?: failure.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
                ?: ""
            failures += TestFailure(
                className = caseAttrs["classname"].orEmpty(),
                testName = caseAttrs["name"].orEmpty(),
                message = unescapeXml(message),
            )
        }
        return StructuredTestResults(
            total = total,
            passed = (total - failed - skipped - errors).coerceAtLeast(0),
            failed = failed,
            skipped = skipped,
            errors = errors,
            durationMs = durationMs,
            failures = failures,
        )
    }

    private fun parseAttrs(attrText: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        for (match in ATTR.findAll(attrText)) {
            attrs[match.groupValues[1]] = unescapeXml(match.groupValues[2])
        }
        return attrs
    }

    /** Unescapes the five predefined XML entities plus numeric references. */
    private fun unescapeXml(text: String): String =
        ENTITY_REF.replace(text) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val codePoint = when {
                        entity.startsWith("#x") ->
                            entity.substring(2).toIntOrNull(16)
                        entity.startsWith("#") ->
                            entity.substring(1).toIntOrNull()
                        else -> null
                    }
                    codePoint?.let { runCatching { Character.toString(it) }.getOrNull() }
                        ?: match.value
                }
            }
        }
}
