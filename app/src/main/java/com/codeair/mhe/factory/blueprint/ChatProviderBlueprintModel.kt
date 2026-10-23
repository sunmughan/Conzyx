package com.codeair.mhe.factory.blueprint

import android.content.Context
import com.codeair.mhe.data.ApiKeyVault
import com.codeair.mhe.data.AppPreferences
import com.codeair.mhe.data.GeminiApiStore
import com.codeair.mhe.model.AgentKind
import com.codeair.mhe.model.ProviderKind
import com.codeair.mhe.model.providerProtocolForAgent
import com.codeair.mhe.network.ProviderApiClient
import com.codeair.mhe.runtime.GeminiApiClient

/**
 * Production [BlueprintModel] over the app's existing chat provider stack:
 *
 * 1. The Gemini API route (same store the Antigravity agent's API mode
 *    uses): when API mode is enabled and a Gemini key is saved, synthesis
 *    runs through [GeminiApiClient].
 * 2. Otherwise the app's saved API provider profile (Settings → provider):
 *    Anthropic, OpenRouter, OpenAI, DeepSeek, custom endpoints, etc. —
 *    through [ProviderApiClient].
 *
 * Subscription-login routes (Claude login, Antigravity OAuth) run
 * interactive agent sessions, not one-shot completions, so they are not
 * used here. When neither route is configured, [complete] throws
 * [BlueprintModelUnavailableException] and the UI says exactly what to
 * connect — no blueprint is ever fabricated.
 */
class ChatProviderBlueprintModel(context: Context) : BlueprintModel {

    private val appContext = context.applicationContext
    private val geminiStore by lazy { GeminiApiStore(appContext) }
    private val preferences by lazy { AppPreferences(appContext) }
    private val vault by lazy { ApiKeyVault(appContext) }
    private val geminiClient by lazy { GeminiApiClient() }
    private val providerClient by lazy { ProviderApiClient() }

    override val label: String
        get() = describeRoute() ?: "AI provider"

    /** The route [complete] would take right now, or null when none is usable. */
    fun describeRoute(): String? {
        if (geminiStore.apiModeEnabled && geminiStore.hasApiKey()) {
            return "Gemini API (${geminiStore.apiModel})"
        }
        val profile = preferences.loadProvider(vault, null)
        if (profile.hasSecret &&
            profile.kind != ProviderKind.CLAUDE &&
            profile.resolvedBaseUrl.isNotBlank() &&
            profile.model.isNotBlank()
        ) {
            return "${profile.kind.title} (${profile.model})"
        }
        return null
    }

    override suspend fun complete(systemPrompt: String, userPrompt: String): String {
        // Route 1: Gemini API (Antigravity agent's API mode).
        if (geminiStore.apiModeEnabled) {
            val key = geminiStore.getApiKey()
            if (!key.isNullOrBlank()) {
                return geminiClient.generateText(key, geminiStore.apiModel, systemPrompt, userPrompt)
            }
        }
        // Route 2: the saved API provider profile.
        val profile = preferences.loadProvider(vault, null)
        val key = if (profile.hasSecret) vault.get(profile.kind.name) else null
        if (!key.isNullOrBlank() &&
            profile.kind != ProviderKind.CLAUDE &&
            profile.resolvedBaseUrl.isNotBlank() &&
            profile.model.isNotBlank()
        ) {
            // Mirror the Custom agent bridge: a CUSTOM profile's wire
            // protocol comes from its saved dshApi selection.
            val protocol = if (profile.kind == ProviderKind.CUSTOM) {
                when (profile.dshApi) {
                    "openai-completions" -> com.codeair.mhe.model.ProviderProtocol.OPENAI_CHAT
                    "openai-responses" -> com.codeair.mhe.model.ProviderProtocol.OPENAI_RESPONSES
                    else -> com.codeair.mhe.model.ProviderProtocol.ANTHROPIC_GATEWAY
                }
            } else {
                providerProtocolForAgent(profile, AgentKind.CUSTOM)
            }
            return providerClient.complete(
                baseUrl = profile.resolvedBaseUrl,
                model = profile.model,
                apiKey = key,
                protocol = protocol,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                openRouterProviderOrder = profile.openRouterProviderOrder,
                openRouterAllowFallbacks = profile.openRouterAllowFallbacks,
            )
        }
        throw BlueprintModelUnavailableException(NOT_CONFIGURED_MESSAGE)
    }

    companion object {
        const val NOT_CONFIGURED_MESSAGE =
            "No AI provider is connected for blueprint synthesis. Connect a Gemini API key " +
                "(Agents → Antigravity → API mode) or save an API provider with a key in Settings, " +
                "then try again. Blueprints are designed by the AI from your prompt — without a " +
                "provider there is nothing honest to generate."
    }
}
