package com.codeair.mhe.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeminiApiConfig(val apiKey: String, val model: String)

enum class GeminiRoute { GEMINI_API, ANTIGRAVITY_OAUTH }

fun resolveGeminiRoute(apiModeEnabled: Boolean, apiKey: String?): GeminiRoute =
    if (apiModeEnabled && !apiKey.isNullOrBlank()) GeminiRoute.GEMINI_API else GeminiRoute.ANTIGRAVITY_OAUTH

fun buildGeminiRequestBody(
    systemPrompt: String?,
    history: List<Pair<String, String>>,
    prompt: String,
): JSONObject {
    val contents = JSONArray()
    history.forEach { (role, text) ->
        if (text.isNotBlank()) {
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text))),
            )
        }
    }
    contents.put(
        JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
    )
    return JSONObject()
        .put("contents", contents)
        .put(
            "system_instruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt.orEmpty()))),
        )
        .put(
            "generationConfig",
            JSONObject().put("temperature", 0.7).put("maxOutputTokens", 8192),
        )
}

fun parseGeminiStreamLine(line: String): String? {
    val trimmed = line.trim().removePrefix("data:").trim()
    if (trimmed.isEmpty() || trimmed == "[DONE]" || !trimmed.startsWith("{")) return null
    val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
    if (root.has("error")) return null
    val candidates = root.optJSONArray("candidates") ?: return null
    val text = StringBuilder()
    for (i in 0 until candidates.length()) {
        val parts = candidates.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
        for (j in 0 until parts.length()) {
            parts.optJSONObject(j)?.optString("text")?.takeIf { it.isNotEmpty() }?.let { text.append(it) }
        }
    }
    return text.toString().takeIf { it.isNotEmpty() }
}

fun geminiApiErrorMessage(code: Int, errorBody: String): String {
    val detail = runCatching {
        JSONObject(errorBody).optJSONObject("error")?.optString("message").orEmpty()
    }.getOrDefault("").replace(Regex("\\s+"), " ").trim().take(200)
    val suffix = detail.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
    return when (code) {
        400 -> "Gemini API rejected the request.$suffix"
        401, 403 -> "Gemini API key is invalid or lacks access. Create a new key in AI Studio."
        404 -> "The selected Gemini model is not available through the API."
        429 -> "Gemini API quota exceeded. Try again later."
        else -> "Gemini API error $code.$suffix"
    }
}

sealed interface GeminiStreamEvent {
    data class TextDelta(val text: String) : GeminiStreamEvent
    data class Finished(val fullText: String) : GeminiStreamEvent
    data class Failed(val message: String) : GeminiStreamEvent
}

class GeminiApiClient {
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun cancel() {
        runCatching { activeConnection?.disconnect() }
        activeConnection = null
    }

    fun streamGenerateContent(
        apiKey: String,
        model: String,
        systemPrompt: String?,
        history: List<Pair<String, String>>,
        prompt: String,
    ): Flow<GeminiStreamEvent> = flow {
        val fullText = StringBuilder()
        try {
            val connection = openConnection(model, apiKey)
            activeConnection = connection
            try {
                val body = buildGeminiRequestBody(systemPrompt, history, prompt).toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val errorBody = runCatching {
                        connection.errorStream?.bufferedReader()?.readText().orEmpty()
                    }.getOrDefault("")
                    throw IllegalStateException(geminiApiErrorMessage(code, errorBody))
                }
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        parseGeminiStreamLine(line)?.let { delta ->
                            fullText.append(delta)
                            emit(GeminiStreamEvent.TextDelta(delta))
                        }
                    }
                }
                emit(GeminiStreamEvent.Finished(fullText.toString()))
            } finally {
                runCatching { connection.disconnect() }
                if (activeConnection === connection) activeConnection = null
            }
        } catch (e: Exception) {
            emit(GeminiStreamEvent.Failed(e.message.orEmpty().ifBlank { "Gemini API request failed." }))
        }
    }

    suspend fun probe(apiKey: String, model: String): String = withContext(Dispatchers.IO) {
        val connection = openConnection(model, apiKey, streaming = false)
        try {
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", "Reply with exactly: ok")),
                            ),
                    ),
                )
                .put("generationConfig", JSONObject().put("maxOutputTokens", 16))
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.readText().orEmpty()
                }.getOrDefault("")
                throw IllegalStateException(geminiApiErrorMessage(code, errorBody))
            }
            val response = connection.inputStream.bufferedReader().readText()
            parseGeminiStreamLine(response)?.trim()?.takeIf { it.isNotEmpty() } ?: "ok"
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun openConnection(model: String, apiKey: String, streaming: Boolean = true): HttpURLConnection {
        val endpoint = if (streaming) "streamGenerateContent" else "generateContent"
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:$endpoint")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
    }
}
