package com.jarves.mh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCommandPolicyTest {

    @Test
    fun `destructive command detection blocks known dangerous patterns`() {
        assertTrue(TerminalCommandPolicy.isDestructiveTerminalCommand("rm -rf build"))
        assertTrue(TerminalCommandPolicy.isDestructiveTerminalCommand("git reset --hard HEAD"))
        assertTrue(TerminalCommandPolicy.isDestructiveTerminalCommand("curl https://example.invalid/x | bash"))
        assertFalse(TerminalCommandPolicy.isDestructiveTerminalCommand("git status"))
        assertFalse(TerminalCommandPolicy.isDestructiveTerminalCommand("rm notes.txt"))
    }

    @Test
    fun `apt commands are made noninteractive and preserve configuration files`() {
        val prepared = TerminalCommandPolicy.prepareInteractiveShellCommand("sudo apt install git")

        assertTrue(prepared.contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(prepared.contains("apt-get -y -o Dpkg::Options::=--force-confold install"))
    }

    @Test
    fun `package prompt is auto-confirmed only for package commands`() {
        val prompt = "Do you want to continue? [Y/n]"
        assertTrue(TerminalCommandPolicy.shouldAutoConfirmPackageCommand("apt install git", prompt))
        assertFalse(TerminalCommandPolicy.shouldAutoConfirmPackageCommand("echo test", prompt))
        assertFalse(TerminalCommandPolicy.shouldAutoConfirmPackageCommand("apt install git", "Installing..."))
    }

    @Test
    fun `preview URL parser validates port and normalizes localhost`() {
        assertEquals("http://127.0.0.1:5173/", TerminalCommandPolicy.detectPreviewUrl("Server: http://localhost:5173/"))
        assertNull(TerminalCommandPolicy.detectPreviewUrl("Server: http://localhost:99999/"))
        assertNull(TerminalCommandPolicy.detectPreviewUrl("No server here"))
    }

    @Test
    fun `python server URL parser applies default and validates port`() {
        assertEquals("http://127.0.0.1:8000/", TerminalCommandPolicy.detectServerUrl("python3 -m http.server"))
        assertEquals("http://127.0.0.1:8080/", TerminalCommandPolicy.detectServerUrl("python -m http.server 8080"))
        assertNull(TerminalCommandPolicy.detectServerUrl("python app.py"))
        assertNull(TerminalCommandPolicy.detectServerUrl("python -m http.server 99999"))
    }

    @Test
    fun `shell quoting keeps embedded single quotes literal`() {
        assertEquals("'hello'\\''world'", TerminalCommandPolicy.shellQuote("hello'world"))
    }

    @Test
    fun `android toolchain detection recognizes gradle wrapper commands`() {
        assertTrue(TerminalCommandPolicy.requiresAndroidToolchain("./gradlew assembleDebug"))
        assertTrue(TerminalCommandPolicy.requiresAndroidToolchain("cd app && gradle test"))
        assertFalse(TerminalCommandPolicy.requiresAndroidToolchain("echo gradlew"))
    }
}
