package com.codeair.mhe.network

import com.codeair.mhe.model.ProviderProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiscoveredModel(val id: String, val displayName: String = id, val isFree: Boolean = false)

sealed interface ModelDiscoveryResult {
    data class Success(val models: List<DiscoveredModel>, val endpoint: String) : ModelDiscoveryResult
    data class Failure(val message: String, val providerMessage: String? = null) : ModelDiscoveryResult
}

sealed interface ConnectionValidation {
    data class Success(val message: String) : ConnectionValidation
    data class Failure(
        val message: String,
        val providerMessage: String? = null,
        val label: String = "Failed",
    ) : ConnectionValidation
}

class ProviderApiClient {
    suspend fun discoverModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext ModelDiscoveryResult.Failure("Enter a base URL first.")
        }

        var authError = false
        var lastMessage = "This provider did not expose a model list. You can enter a custom model name."
        var lastProviderMessage: String? = null
        for (endpoint in modelEndpoints(baseUrl, protocol)) {
            // OpenRouter's complete catalog is public. Fetch it anonymously even when
            // OpenRouter is configured through Custom API so an account-scoped key does
            // not reduce discovery to the models allowed by that key's preferences.
            // The saved key is still used for validation and all inference requests.
            val discoveryKey = if (isOpenRouterCatalogEndpoint(endpoint)) "" else apiKey
            val response = request(endpoint, "GET", discoveryKey, protocol = protocol)
            when {
                response.code == 401 || response.code == 403 -> {
                    authError = true
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.code in 200..299 -> {
                    val models = ModelResponseParser.parse(response.body)
                    if (models.isNotEmpty()) return@withContext ModelDiscoveryResult.Success(models, endpoint)
                    lastMessage = "The provider replied, but its model list was empty or unsupported."
                }
                response.code > 0 && response.code != 404 -> {
                    lastMessage = friendlyHttpError(response.code)
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.error != null -> lastMessage = response.error
            }
        }
        ModelDiscoveryResult.Failure(
            if (authError) "Check the saved API key, then try refreshing again." else lastMessage,
            lastProviderMessage,
        )
    }

    suspend fun validate(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        discoveredModels: List<DiscoveredModel>,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): ConnectionValidation = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            return@withContext ConnectionValidation.Failure("Base URL, model, and API key are required.")
        }
        val endpoint = messagesEndpoint(baseUrl, protocol)
        val body = validationBody(model, protocol, openRouterProviderOrder, openRouterAllowFallbacks)
        // Gateways may need to cold-start a model before returning the first token.
        // A ten-second validation timeout produced false "network" failures even
        // though discovery and the endpoint itself were healthy.
        val response = request(endpoint, "POST", apiKey, body, protocol, connectTimeoutMs = 12_000, readTimeoutMs = 45_000)
        when {
            response.code in 200..299 -> ConnectionValidation.Success(
                if (protocol == ProviderProtocol.ANTHROPIC || protocol == ProviderProtocol.ANTHROPIC_GATEWAY || protocol == ProviderProtocol.OPENROUTER) {
                    "Anthropic Messages endpoint verified. Claude Code settings are ready."
                } else {
                    "Connection successful. Claude Code settings are ready."
                },
            )
            response.code == 401 || response.code == 403 -> ConnectionValidation.Failure(
                "Check this API key or select another saved key.",
                providerErrorMessage(response.body),
                "Rejected",
            )
            response.code == 404 -> ConnectionValidation.Failure(
                "Check the Base URL and selected gateway protocol.",
                providerErrorMessage(response.body),
                "Endpoint error",
            )
            response.code == 400 && response.body.contains("model", ignoreCase = true) ->
                ConnectionValidation.Failure(
                    "Refresh the model list or select a different model.",
                    providerErrorMessage(response.body),
                    "Model error",
                )
            response.code == 429 -> ConnectionValidation.Failure(
                "Wait a moment, then retry or use another API key.",
                providerErrorMessage(response.body),
                "Rate limited",
            )
            response.code in 500..599 -> ConnectionValidation.Failure(
                "The provider is temporarily unavailable. Try again shortly.",
                providerErrorMessage(response.body),
                "Provider error",
            )
            response.code > 0 -> ConnectionValidation.Failure(
                "Review the model, protocol, and endpoint settings.",
                providerErrorMessage(response.body),
                "Request failed",
            )
            response.error?.contains("timeout", ignoreCase = true) == true ||
                response.error?.contains("timed out", ignoreCase = true) == true ->
                ConnectionValidation.Failure("Check your connection and try again.", response.error, "Timed out")
            else -> ConnectionValidation.Failure(
                "Check your internet connection and provider settings.",
                response.error,
                "Network error",
            )
        }
    }

    /**
     * One-shot text completion against the configured provider (system +
     * user prompt in, model text out) — the non-agent counterpart of the
     * chat bridges, for features like App Factory blueprint synthesis that
     * need a single structured answer. Throws IllegalStateException with a
     * user-readable reason on any failure; never fabricates a reply.
     */
    suspend fun complete(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        systemPrompt: String,
        userPrompt: String,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        require(baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()) {
            "Base URL, model, and API key are required."
        }
        require(protocol != ProviderProtocol.CLAUDE_LOGIN) {
            "Subscription login providers do not expose a completion API."
        }
        val endpoint = messagesEndpoint(baseUrl, protocol)
        val body = completionBody(model, protocol, systemPrompt, userPrompt, openRouterProviderOrder, openRouterAllowFallbacks)
        val response = request(endpoint, "POST", apiKey, body, protocol, connectTimeoutMs = 15_000, readTimeoutMs = 120_000)
        when {
            response.code in 200..299 -> parseCompletionText(response.body, protocol)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("The provider returned an empty response. Try again.")
            response.code == 401 || response.code == 403 ->
                throw IllegalStateException("The provider rejected the saved API key. Check it in Settings.")
            response.code == 429 ->
                throw IllegalStateException("Provider rate limit reached. Wait a moment and try again.")
            response.code > 0 -> throw IllegalStateException(
                "Provider request failed (HTTP ${response.code})." +
                    (providerErrorMessage(response.body)?.let { " $it" } ?: "")
            )
            else -> throw IllegalStateException(
                "Could not reach the provider. ${response.error ?: "Check your internet connection."}"
            )
        }
    }

    internal fun completionBody(
        model: String,
        protocol: ProviderProtocol,
        systemPrompt: String,
        userPrompt: String,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): String = when (protocol) {
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            .put("temperature", 0.4)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt)),
            )
            .toString()
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put("instructions", systemPrompt)
            .put("input", userPrompt)
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", 8192)
            .put("system", systemPrompt)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)),
            )
            .also { body ->
                val providers = openRouterProviderOrder.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                if (protocol == ProviderProtocol.OPENROUTER && providers.isNotEmpty()) {
                    body.put(
                        "provider",
                        JSONObject()
                            .put("order", JSONArray(providers))
                            .put("allow_fallbacks", openRouterAllowFallbacks),
                    )
                }
            }
            .toString()
    }

    /** Extracts the assistant text from a completion response per [protocol]. */
    internal fun parseCompletionText(body: String, protocol: ProviderProtocol): String? = runCatching {
        val root = JSONObject(body)
        when (protocol) {
            ProviderProtocol.OPENAI_CHAT -> root.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ProviderProtocol.OPENAI_RESPONSES -> {
                root.optString("output_text").takeIf { it.isNotBlank() } ?: run {
                    val out = StringBuilder()
                    val output = root.optJSONArray("output") ?: return@run null
                    for (i in 0 until output.length()) {
                        val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                        for (j in 0 until content.length()) {
                            content.optJSONObject(j)?.optString("text")?.let { out.append(it) }
                        }
                    }
                    out.toString().takeIf { it.isNotEmpty() }
                }
            }
            else -> {
                val out = StringBuilder()
                val content = root.optJSONArray("content") ?: return@runCatching null
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text" || part.has("text")) {
                        out.append(part.optString("text"))
                    }
                }
                out.toString().takeIf { it.isNotEmpty() }
            }
        }
    }.getOrNull()

    private fun request(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String? = null,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResult {
        // QA Medium: one transient 429/5xx used to be final. Retry with
        // backoff, honoring the server's Retry-After header.
        var attempt = 0
        while (true) {
            val result = runCatching {
                attemptRequest(endpoint, method, apiKey, body, protocol, connectTimeoutMs, readTimeoutMs)
            }.getOrElse {
                return HttpResult(0, "", it.message ?: "Network connection failed")
            }
            if (!HttpRetry.isRetryable(result.code) || attempt >= HttpRetry.MAX_ATTEMPTS - 1) {
                return HttpResult(result.code, result.body)
            }
            HttpRetry.sleepBeforeRetry(attempt, result.retryAfter)
            attempt++
        }
    }

    private data class AttemptResult(val code: Int, val body: String, val retryAfter: String?)

    private fun attemptRequest(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String?,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): AttemptResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            if (endpoint.startsWith("https://opencode.ai/zen/")) {
                // OpenCode Zen expects requests to identify the OpenCode client and session.
                setRequestProperty("User-Agent", "opencode/1.18.20")
                setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
            }
            if (apiKey.isNotBlank() && protocol != ProviderProtocol.OPENROUTER && protocol != ProviderProtocol.OPENAI_CHAT && protocol != ProviderProtocol.OPENAI_RESPONSES) {
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            }
            if (body != null) doOutput = true
        }
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.use { readCappedBody(it) }.orEmpty()
        val retryAfter = connection.getHeaderField("Retry-After")
        connection.disconnect()
        return AttemptResult(code, responseBody, retryAfter)
    }

    private fun modelEndpoints(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        val withoutAnthropic = base.removeSuffix("/anthropic")
        val candidates = when (protocol) {
            ProviderProtocol.OPENROUTER -> listOf("$base/v1/models")
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> listOf("$base/models")
            else -> listOf("$base/v1/models", "$base/models", "$withoutAnthropic/models", "$withoutAnthropic/v1/models")
        }
        return candidates.distinct()
    }

    private fun messagesEndpoint(baseUrl: String, protocol: ProviderProtocol): String {
        val base = baseUrl.trim().trimEnd('/')
        return when (protocol) {
            ProviderProtocol.OPENROUTER -> "$base/v1/messages"
            ProviderProtocol.OPENAI_CHAT -> "$base/chat/completions"
            ProviderProtocol.OPENAI_RESPONSES -> "$base/responses"
            else -> if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        }
    }

    private fun isOpenRouterCatalogEndpoint(endpoint: String): Boolean = runCatching {
        val url = URL(endpoint)
        url.host.equals("openrouter.ai", ignoreCase = true) &&
            url.path.trimEnd('/').endsWith("/models")
    }.getOrDefault(false)

    internal fun validationBody(
        model: String,
        protocol: ProviderProtocol,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): String = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", "Hello, reply with 1 word."),
                            ),
                        ),
                ),
            )
            .toString()
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            .put("max_tokens", 1)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", 1)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .also { body ->
                val providers = openRouterProviderOrder.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                if (protocol == ProviderProtocol.OPENROUTER && providers.isNotEmpty()) {
                    body.put(
                        "provider",
                        JSONObject()
                            .put("order", JSONArray(providers))
                            .put("allow_fallbacks", openRouterAllowFallbacks),
                    )
                }
            }
            .toString()
    }

    private fun friendlyHttpError(code: Int): String = when (code) {
        429 -> "The provider rate limit was reached. Wait a moment and try again."
        in 500..599 -> "The provider is temporarily unavailable (HTTP $code)."
        else -> "The provider returned HTTP $code. Check the URL and account access."
    }

    private fun providerErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val extracted = runCatching {
            val root = JSONObject(body)
            when (val error = root.opt("error")) {
                is JSONObject -> error.optString("message").ifBlank { error.optString("detail") }
                is String -> error
                else -> root.optString("message").ifBlank { root.optString("detail") }
            }
        }.getOrNull().orEmpty()
        if (extracted.isBlank()) return null
        return extracted
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private data class HttpResult(val code: Int, val body: String, val error: String? = null)

    private fun readCappedBody(stream: java.io.InputStream): String {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val sb = StringBuilder()
        val buffer = CharArray(8192)
        var total = 0
        while (total < MAX_RESPONSE_CHARS) {
            val read = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - total))
            if (read <= 0) break
            sb.append(buffer, 0, read)
            total += read
        }
        return sb.toString()
    }

    companion object {
        const val MAX_RESPONSE_CHARS = 10 * 1024 * 1024
    }
}

object ModelResponseParser {
    fun parse(json: String): List<DiscoveredModel> = runCatching {
        val trimmed = json.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            }
        }
        buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> add(DiscoveredModel(item))
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isNotBlank()) {
                            val label = item.optString("display_name").ifBlank { item.optString("displayName") }.ifBlank { id }
                            val pricing = item.optJSONObject("pricing")
                            val free = id.endsWith(":free", ignoreCase = true) || pricing?.let {
                                listOf("prompt", "completion", "request").all { field ->
                                    it.optString(field, "0").toDoubleOrNull() == 0.0
                                }
                            } == true
                            add(DiscoveredModel(id, label, free))
                        }
                    }
                }
            }
        }.distinctBy { it.id }.sortedWith(compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase() })
    }.getOrDefault(emptyList())
}
