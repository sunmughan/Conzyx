package com.codeair.mhe.factory.build

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

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
 * Pure Kotlin + javax.xml: unit-testable on the JVM and usable on device.
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
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Reports are Gradle-generated, but never trust a DTD in a file
            // the agent workspace could have produced.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(file)
        val suites = doc.getElementsByTagName("testsuite")
        var total = 0
        var failed = 0
        var skipped = 0
        var errors = 0
        var durationMs = 0L
        val failures = mutableListOf<TestFailure>()
        for (i in 0 until suites.length) {
            val suite = suites.item(i) as? Element ?: continue
            total += suite.getAttribute("tests").toIntOrNull() ?: 0
            failed += suite.getAttribute("failures").toIntOrNull() ?: 0
            errors += suite.getAttribute("errors").toIntOrNull() ?: 0
            skipped += suite.getAttribute("skipped").toIntOrNull() ?: 0
            durationMs += ((suite.getAttribute("time").toDoubleOrNull() ?: 0.0) * 1000).toLong()
            val cases = suite.getElementsByTagName("testcase")
            for (j in 0 until cases.length) {
                val case = cases.item(j) as? Element ?: continue
                val failureNode = firstChildElement(case, "failure")
                    ?: firstChildElement(case, "error")
                    ?: continue
                val message = failureNode.getAttribute("message").ifBlank {
                    failureNode.textContent?.trim().orEmpty()
                }
                failures += TestFailure(
                    className = case.getAttribute("classname").ifBlank { suite.getAttribute("name") },
                    testName = case.getAttribute("name"),
                    message = message,
                )
            }
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

    private fun firstChildElement(parent: Element, tag: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.tagName == tag) return child
        }
        return null
    }
}
