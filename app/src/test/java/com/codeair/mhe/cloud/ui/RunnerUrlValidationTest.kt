package com.codeair.mhe.cloud.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA Medium: the Add Runner card used to prefill a placeholder endpoint
 * that likely does not exist, and Register accepted it. The field now
 * starts empty and Register requires a format-valid http(s) URL.
 */
class RunnerUrlValidationTest {

    @Test
    fun `https url with port is valid`() {
        assertTrue(isValidRunnerUrl("https://your-vps:8080"))
    }

    @Test
    fun `plain http url is valid`() {
        assertTrue(isValidRunnerUrl("http://10.0.0.5:8080"))
    }

    @Test
    fun `empty url is invalid`() {
        assertFalse(isValidRunnerUrl(""))
        assertFalse(isValidRunnerUrl("   "))
    }

    @Test
    fun `bare hostname without scheme is invalid`() {
        assertFalse(isValidRunnerUrl("vps.example.com:8080"))
    }

    @Test
    fun `non-http scheme is invalid`() {
        assertFalse(isValidRunnerUrl("ftp://vps.example.com"))
    }

    @Test
    fun `scheme without host is invalid`() {
        assertFalse(isValidRunnerUrl("https://"))
    }
}
