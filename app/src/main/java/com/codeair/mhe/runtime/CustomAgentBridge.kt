package com.codeair.mhe.runtime

import com.codeair.mhe.model.ChangeItem
import com.codeair.mhe.model.ChatMessage
import com.codeair.mhe.model.ProjectKind
import com.codeair.mhe.model.ProviderKind
import com.codeair.mhe.model.ProviderProfile
import com.codeair.mhe.model.RuntimeEvent
import com.codeair.mhe.model.ToolRequest
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bring-your-own-API agent: talks directly to an OpenAI-compatible
 * (`/chat/completions`) or Claude-compatible (`/messages`) endpoint using the
 * user's own API key. No binary install, no runtime updates.
 *
 * This is direct API chat — it answers via the configured model but does not
 * edit files or run tools. For full agentic code execution, install one of the
 * harness agents (Claude Code, DeepSeek, Cloud Code).
 */
class CustomAgentBridge(
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus

    private enum class ApiType { OPENAI, ANTHROPIC }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        val secret = secretFor(provider).orEmpty()
        if (secret.isBlank()) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "No API key is saved. Add your key in Agent → Custom.",
                ),
            )
            return@withContext sessionId
        }
        val baseUrl = provider.resolvedBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "No API endpoint is configured. Set the base URL in Agent → Custom.",
                ),
            )
            return@withContext sessionId
        }
        val model = provider.model.trim()
        if (model.isBlank()) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "No model is configured. Set the model in Agent → Custom.",
                ),
            )
            return@withContext sessionId
        }
        try {
            val text = when (resolveApiType(provider)) {
                ApiType.ANTHROPIC -> callAnthropic(baseUrl, secret, model, prompt, conversationHistory)
                ApiType.OPENAI -> callOpenAi(baseUrl, secret, model, prompt, conversationHistory)
            }
            if (text.isBlank()) {
                eventBus.emit(RuntimeEvent.SessionFailed(sessionId, "The API returned an empty response."))
            } else {
                text.chunked(600).forEach { eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it)) }
                eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            }
        } catch (e: Exception) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "Custom API error: ${e.message?.take(200) ?: "unknown error"}",
                ),
            )
        }
        sessionId
    }

    private fun resolveApiType(provider: ProviderProfile): ApiType = when (provider.kind) {
        ProviderKind.ANTHROPIC -> ApiType.ANTHROPIC
        // OpenRouter and other OpenAI-protocol providers use chat completions.
        ProviderKind.LLM_ROUTER -> ApiType.OPENAI
        // Custom API: the API type is stored in dshApi ("anthropic-messages" | "openai-completions").
        else -> if (provider.dshApi == "anthropic-messages") ApiType.ANTHROPIC else ApiType.OPENAI
    }

    private fun historyMessages(history: List<ChatMessage>): JSONArray {
        val messages = JSONArray()
        history.takeLast(20).forEach { message ->
            if (message.text.isNotBlank()) {
                messages.put(
                    JSONObject()
                        .put("role", if (message.fromUser) "user" else "assistant")
                        .put("content", message.text),
                )
            }
        }
        return messages
    }

    private fun callOpenAi(
        baseUrl: String,
        secret: String,
        model: String,
        prompt: String,
        history: List<ChatMessage>,
    ): String {
        val messages = historyMessages(history)
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", 4096)
            .toString()
        val response = post("$baseUrl/chat/completions", secret, body, anthropic = false)
        val json = JSONObject(response)
        if (json.has("error")) {
            throw IllegalStateException(json.getJSONObject("error").optString("message", "API error"))
        }
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    private fun callAnthropic(
        baseUrl: String,
        secret: String,
        model: String,
        prompt: String,
        history: List<ChatMessage>,
    ): String {
        val messages = historyMessages(history)
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("messages", messages)
            .toString()
        val response = post("$baseUrl/messages", secret, body, anthropic = true)
        val json = JSONObject(response)
        if (json.has("error")) {
            val error = json.optJSONObject("error")
            throw IllegalStateException(error?.optString("message", "API error") ?: "API error")
        }
        val content = json.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                sb.append(block.optString("text"))
            }
        }
        return sb.toString()
    }

    private fun post(url: String, secret: String, body: String, anthropic: Boolean): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (anthropic) {
                setRequestProperty("x-api-key", secret)
                setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                setRequestProperty("Authorization", "Bearer $secret")
            }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException(friendlyError(response, code).take(200))
            }
            return response
        } finally {
            conn.disconnect()
        }
    }

    private fun friendlyError(response: String, code: Int): String {
        val parsed = runCatching {
            val json = JSONObject(response)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        return parsed ?: "HTTP $code"
    }

    // The Custom agent is direct-API chat: no tools, no file operations.
    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = Unit
    override suspend fun stopSession(sessionId: String) = Unit
    override suspend fun stopActiveSession() = Unit
    override suspend fun undoLastChanges(projectId: String): Boolean = false
    override suspend fun acceptLastChanges(projectId: String) = Unit
    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = emptyList()
    override suspend fun undoFileChange(projectId: String, path: String): Boolean = false
    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = false
}
