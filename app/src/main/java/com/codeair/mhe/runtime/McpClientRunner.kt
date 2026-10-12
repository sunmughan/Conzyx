package com.codeair.mhe.runtime

import com.codeair.mhe.mcp.McpServerInfo
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String = "{}",
    val serverId: String? = null
)

data class McpToolCallResult(
    val success: Boolean,
    val content: String,
    val error: String? = null,
    val durationMs: Long = 0L
)

/**
 * Live JSON-RPC MCP Server Stdio Client Runner (Phase 7).
 * Connects to local MCP server processes over stdio to list tools and execute JSON-RPC calls.
 */
class McpClientRunner {

    private val requestId = AtomicInteger(1)
    private val activeServers = ConcurrentHashMap<String, Process>()

    /**
     * Initializes an MCP session and lists available tools for an McpServerInfo.
     */
    suspend fun discoverTools(server: McpServerInfo): Result<List<McpToolDefinition>> = withContext(Dispatchers.IO) {
        runCatching {
            val workingDir = server.workingDir?.let { File(it) }
            val process = startProcess(server.command, server.args, server.env, workingDir)
            val writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))

            // 1. Send JSON-RPC initialize request
            val initReq = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", requestId.getAndIncrement())
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("clientInfo", JSONObject().apply {
                        put("name", "MobileHarness")
                        put("version", "1.7.0")
                    })
                })
            }
            sendJson(writer, initReq)
            readJson(reader) // consume initialize response

            // 2. Send tools/list request
            val listReq = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", requestId.getAndIncrement())
                put("method", "tools/list")
                put("params", JSONObject())
            }
            sendJson(writer, listReq)
            val listResp = readJson(reader)

            val toolsArray = listResp.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
            val tools = mutableListOf<McpToolDefinition>()
            for (i in 0 until toolsArray.length()) {
                val t = toolsArray.getJSONObject(i)
                tools.add(
                    McpToolDefinition(
                        name = t.getString("name"),
                        description = t.optString("description", ""),
                        inputSchemaJson = t.optJSONObject("inputSchema")?.toString() ?: "{}",
                        serverId = server.id
                    )
                )
            }
            runCatching { process.destroy() }
            tools
        }
    }

    /**
     * Initializes an MCP session and lists available tools (legacy overload).
     */
    suspend fun discoverTools(serverCommand: String, workingDir: File? = null): Result<List<McpToolDefinition>> = withContext(Dispatchers.IO) {
        val server = McpServerInfo(
            id = "legacy-disc",
            name = "Legacy",
            command = serverCommand,
            workingDir = workingDir?.absolutePath
        )
        discoverTools(server)
    }

    /**
     * Calls a tool on the MCP server via JSON-RPC `tools/call`.
     */
    suspend fun callTool(
        server: McpServerInfo,
        toolName: String,
        arguments: JSONObject
    ): McpToolCallResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val workingDir = server.workingDir?.let { File(it) }
            val process = activeServers.getOrPut(server.id) {
                startProcess(server.command, server.args, server.env, workingDir)
            }
            val writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))

            val callReq = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", requestId.getAndIncrement())
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments)
                })
            }
            sendJson(writer, callReq)
            val response = readJson(reader)
            val duration = System.currentTimeMillis() - start

            val result = response.optJSONObject("result")
            if (result != null) {
                val contentArray = result.optJSONArray("content")
                val textOutput = if (contentArray != null && contentArray.length() > 0) {
                    contentArray.getJSONObject(0).optString("text", result.toString())
                } else {
                    result.toString()
                }
                McpToolCallResult(success = true, content = textOutput, durationMs = duration)
            } else {
                val err = response.optJSONObject("error")?.optString("message") ?: "Unknown MCP error"
                McpToolCallResult(success = false, content = "", error = err, durationMs = duration)
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            McpToolCallResult(success = false, content = "", error = e.message ?: "Failed to execute MCP tool", durationMs = duration)
        }
    }

    /**
     * Calls a tool on the MCP server via JSON-RPC `tools/call` (legacy command overload).
     */
    suspend fun callTool(
        serverCommand: String,
        toolName: String,
        arguments: JSONObject,
        workingDir: File? = null,
    ): McpToolCallResult = withContext(Dispatchers.IO) {
        val server = McpServerInfo(
            id = serverCommand,
            name = serverCommand,
            command = serverCommand,
            workingDir = workingDir?.absolutePath
        )
        callTool(server, toolName, arguments)
    }

    private fun startProcess(
        command: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        workingDir: File? = null
    ): Process {
        val parts = (command.split("\\s+".toRegex()).filter { it.isNotBlank() } + args)
        val pb = ProcessBuilder(parts)
        if (workingDir != null) pb.directory(workingDir)
        if (env.isNotEmpty()) {
            pb.environment().putAll(env)
        }
        return pb.start()
    }

    private fun sendJson(writer: OutputStreamWriter, json: JSONObject) {
        val payload = json.toString()
        writer.write("Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n\r\n$payload")
        writer.flush()
    }

    private fun readJson(reader: BufferedReader): JSONObject {
        var line = reader.readLine()
        var contentLength = -1
        while (!line.isNullOrBlank()) {
            if (line.startsWith("Content-Length: ", ignoreCase = true)) {
                contentLength = line.substring(16).trim().toIntOrNull() ?: -1
            }
            line = reader.readLine()
        }
        if (contentLength > 0) {
            val buffer = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = reader.read(buffer, read, contentLength - read)
                if (count == -1) break
                read += count
            }
            return JSONObject(String(buffer, 0, read))
        }
        val fallback = reader.readLine().orEmpty()
        return if (fallback.isNotBlank()) JSONObject(fallback) else JSONObject()
    }

    fun stopServer(serverId: String) {
        activeServers.remove(serverId)?.let { runCatching { it.destroy() } }
    }

    fun stopAll() {
        activeServers.values.forEach { runCatching { it.destroy() } }
        activeServers.clear()
    }
}
