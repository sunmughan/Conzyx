package com.codeair.mhe.factory.build

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 — structured test results: Gradle JUnit XML must aggregate into
 * counts plus failing test names, and must never invent data when there is
 * nothing (or nothing readable) to parse.
 */
class TestResultParserTest {

    private fun writeReport(dir: File, name: String, xml: String): File =
        File(dir, name).apply { writeText(xml) }

    private val sampleXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.example.WidgetTest" tests="3" skipped="1" failures="1" errors="0" time="2.5">
          <testcase classname="com.example.WidgetTest" name="addsNumbers" time="0.1"/>
          <testcase classname="com.example.WidgetTest" name="rejectsNegative" time="0.2">
            <failure message="expected:&lt;5&gt; but was:&lt;-1&gt;" type="java.lang.AssertionError">expected:&lt;5&gt; but was:&lt;-1&gt;
	at com.example.WidgetTest.rejectsNegative(WidgetTest.kt:42)</failure>
          </testcase>
          <testcase classname="com.example.WidgetTest" name="skippedCase" time="0.0">
            <skipped/>
          </testcase>
        </testsuite>
    """.trimIndent()

    @Test
    fun parsesCountsAndFailingTest() {
        val dir = Files.createTempDirectory("test-results").toFile()
        try {
            writeReport(dir, "TEST-com.example.WidgetTest.xml", sampleXml)
            val results = TestResultParser.parseResultsDir(dir)!!
            assertEquals(3, results.total)
            assertEquals(1, results.passed)
            assertEquals(1, results.failed)
            assertEquals(1, results.skipped)
            assertEquals(0, results.errors)
            assertEquals(2500L, results.durationMs)
            assertEquals(1, results.failures.size)
            assertEquals("com.example.WidgetTest", results.failures[0].className)
            assertEquals("rejectsNegative", results.failures[0].testName)
            assertTrue(results.failures[0].message.contains("expected:<5>"))
            assertEquals(
                "Tests: 1 passed, 1 failed, 1 skipped (2.5s)",
                results.summaryLine(),
            )
            assertEquals(1, results.failureLines().size)
            assertTrue(results.failureLines()[0].contains("rejectsNegative"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aggregatesMultipleReports() {
        val dir = Files.createTempDirectory("test-results").toFile()
        try {
            writeReport(dir, "TEST-a.xml", sampleXml)
            writeReport(
                dir, "TEST-b.xml",
                """<testsuite name="com.example.OtherTest" tests="2" skipped="0" failures="0" errors="1" time="1.0">
                  <testcase classname="com.example.OtherTest" name="crashes" time="1.0">
                    <error message="boom" type="java.lang.RuntimeException">boom</error>
                  </testcase>
                  <testcase classname="com.example.OtherTest" name="ok" time="0.0"/>
                </testsuite>""",
            )
            val results = TestResultParser.parseResultsDir(dir)!!
            assertEquals(5, results.total)
            assertEquals(2, results.passed)
            assertEquals(1, results.failed)
            assertEquals(1, results.errors)
            assertEquals(2, results.failures.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingDirectoryReturnsNull() {
        assertNull(
            TestResultParser.parseResultsDir(File("/tmp/definitely-not-a-results-dir-xyz")),
        )
    }

    @Test
    fun directoryWithoutReportsReturnsNull() {
        val dir = Files.createTempDirectory("test-results").toFile()
        try {
            writeReport(dir, "notes.txt", "not a report")
            assertNull(TestResultParser.parseResultsDir(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun malformedReportIsSkippedNotFatal() {
        val dir = Files.createTempDirectory("test-results").toFile()
        try {
            writeReport(dir, "TEST-bad.xml", "<testsuite><testcase><unclosed>")
            assertNull(TestResultParser.parseResultsDir(dir))
            writeReport(dir, "TEST-good.xml", sampleXml)
            val results = TestResultParser.parseResultsDir(dir)!!
            assertEquals(3, results.total)
        } finally {
            dir.deleteRecursively()
        }
    }
}
