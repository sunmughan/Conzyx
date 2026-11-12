package com.codeair.mhe.runtime

import com.codeair.mhe.model.ChatMessage
import com.codeair.mhe.model.ProjectKind
import com.codeair.mhe.model.ProviderKind
import com.codeair.mhe.model.ProviderProfile
import com.codeair.mhe.model.RuntimeEvent
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4: CustomAgentBridge rotates to the next healthy key when the
 * provider rejects the current one (HTTP 429), instead of failing the
 * session. A raw-socket server (com.sun.net.httpserver is not on the
 * unit-test classpath) answers 429 to key1 and 200 to key2.
 */
class CustomAgentBridgeFailoverTest {

    @Test
    fun `rotates to the next key on 429 and completes`() {
        val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val seenKeys = mutableListOf<String>()
        thread(isDaemon = true, name = "failover-test-server") {
            try {
                repeat(2) {
                    serverSocket.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        var auth = ""
                        var contentLength = 0
                        var line: String?
                        do {
                            line = reader.readLine()
                            if (line != null && line.startsWith("Authorization: Bearer ")) {
                                auth = line.removePrefix("Authorization: Bearer ")
                            }
                            if (line != null && line.startsWith("Content-Length: ")) {
                                contentLength = line.removePrefix("Content-Length: ").trim().toIntOrNull() ?: 0
                            }
                        } while (line != null && line.isNotEmpty())
                        // Consume the request body so the client is not blocked.
                        var remaining = contentLength
                        val buf = CharArray(1024)
                        while (remaining > 0) {
                            val read = reader.read(buf, 0, minOf(buf.size, remaining))
                            if (read < 0) break
                            remaining -= read
                        }
                        synchronized(seenKeys) { seenKeys.add(auth) }
                        val out = socket.getOutputStream()
                        if (auth == "key1") {
                            val body = "{\"error\":{\"message\":\"rate limit exceeded\"}}"
                            out.write(
                                ("HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                            )
                        } else {
                            val body = "{\"choices\":[{\"message\":{\"content\":\"hello-key2\"}}]}"
                            out.write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                            )
                        }
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                // Client went away: nothing to do.
            }
        }
        try {
            val provider = ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = "http://127.0.0.1:$port",
                model = "test-model",
                dshApi = "openai-completions",
            )
            val bridge = CustomAgentBridge(
                secretFor = { "key1" },
                rotateKey = { _, _ -> "key2" },
            )
            val terminalRef = AtomicReference<RuntimeEvent>()
            val text = StringBuilder()
            val doneLatch = CountDownLatch(1)
            thread(isDaemon = true) {
                runBlocking {
                    bridge.events.collect { event ->
                        when (event) {
                            is RuntimeEvent.AssistantDelta -> text.append(event.text)
                            is RuntimeEvent.SessionCompleted,
                            is RuntimeEvent.SessionFailed -> {
                                terminalRef.set(event)
                                doneLatch.countDown()
                            }
                            else -> Unit
                        }
                    }
                }
            }
            val sessionThread = thread {
                runBlocking {
                    bridge.startSession(
                        projectId = "p1",
                        projectSlug = "p1",
                        projectKind = ProjectKind.PROJECT,
                        prompt = "hi",
                        conversationHistory = listOf(ChatMessage(fromUser = true, text = "hi")),
                        provider = provider,
                    )
                }
            }
            assertTrue("session never finished", doneLatch.await(15, TimeUnit.SECONDS))
            assertTrue(
                "expected SessionCompleted, got ${terminalRef.get()}",
                terminalRef.get() is RuntimeEvent.SessionCompleted,
            )
            assertTrue("expected the key2 reply, got '$text'", text.toString().contains("hello-key2"))
            assertEquals(listOf("key1", "key2"), synchronized(seenKeys) { seenKeys.toList() })
            sessionThread.join(10_000)
            assertTrue("session thread still alive", !sessionThread.isAlive)
        } finally {
            runCatching { serverSocket.close() }
        }
    }

    @Test
    fun `fails the session when there is no key to rotate to`() {
        val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        // The server answers 429 to every request: the bridge's same-key
        // retry then terminates on its own (3 attempts, ~3s of backoff)
        // instead of hanging on a dead connection. A server that accepts
        // once and stops reading leaves the listening socket open, so the
        // retry's connection sits in the backlog until the 120s read
        // timeout — that is a test-server defect, not a bridge defect.
        thread(isDaemon = true, name = "failover-no-key-server") {
            try {
                while (!serverSocket.isClosed) {
                    serverSocket.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        var contentLength = 0
                        var line: String?
                        do {
                            line = reader.readLine()
                            if (line != null && line.startsWith("Content-Length: ")) {
                                contentLength = line.removePrefix("Content-Length: ").trim().toIntOrNull() ?: 0
                            }
                        } while (line != null && line.isNotEmpty())
                        var remaining = contentLength
                        val buf = CharArray(1024)
                        while (remaining > 0) {
                            val read = reader.read(buf, 0, minOf(buf.size, remaining))
                            if (read < 0) break
                            remaining -= read
                        }
                        val body = "{\"error\":{\"message\":\"rate limit exceeded\"}}"
                        val out = socket.getOutputStream()
                        out.write(
                            ("HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                        )
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                // Client went away or socket closed: nothing to do.
            }
        }
        try {
            val provider = ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = "http://127.0.0.1:$port",
                model = "test-model",
                dshApi = "openai-completions",
            )
            val bridge = CustomAgentBridge(
                secretFor = { "key1" },
                rotateKey = { _, _ -> null },
            )
            val terminalRef = AtomicReference<RuntimeEvent>()
            val doneLatch = CountDownLatch(1)
            thread(isDaemon = true) {
                runBlocking {
                    bridge.events.collect { event ->
                        when (event) {
                            is RuntimeEvent.SessionCompleted,
                            is RuntimeEvent.SessionFailed -> {
                                terminalRef.set(event)
                                doneLatch.countDown()
                            }
                            else -> Unit
                        }
                    }
                }
            }
            val sessionThread = thread {
                runBlocking {
                    bridge.startSession(
                        projectId = "p1",
                        projectSlug = "p1",
                        projectKind = ProjectKind.PROJECT,
                        prompt = "hi",
                        conversationHistory = listOf(ChatMessage(fromUser = true, text = "hi")),
                        provider = provider,
                    )
                }
            }
            assertTrue("session never finished", doneLatch.await(15, TimeUnit.SECONDS))
            assertTrue(
                "expected SessionFailed, got ${terminalRef.get()}",
                terminalRef.get() is RuntimeEvent.SessionFailed,
            )
            sessionThread.join(10_000)
            assertTrue("session thread still alive", !sessionThread.isAlive)
        } finally {
            runCatching { serverSocket.close() }
        }
    }
}
