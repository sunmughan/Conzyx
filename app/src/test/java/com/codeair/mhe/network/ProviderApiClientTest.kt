package com.codeair.mhe.network

import com.codeair.mhe.model.ProviderProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiClientTest {
    @Test
    fun openAiResponsesProbeUsesStructuredInputWithoutOutputCap() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "muse-spark-1.3-contributor-free",
                protocol = ProviderProtocol.OPENAI_RESPONSES,
            ),
        )

        assertEquals("muse-spark-1.3-contributor-free", body.getString("model"))
        val message = body.getJSONArray("input").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val content = message.getJSONArray("content").getJSONObject(0)
        assertEquals("input_text", content.getString("type"))
        assertEquals("Hello, reply with 1 word.", content.getString("text"))
        assertEquals(false, body.has("max_output_tokens"))
    }

    @Test
    fun notFoundForDiscoveredModelMeansWrongPathNotMissingModel() {
        // v3.5.11: the model came from discovery, so it exists. A 404
        // for it means the request path is not served for this protocol.
        val failure = ProviderApiClient().classifyNotFound(
            model = "deepseek-ai/deepseek-v4.1-flash",
            discoveredModels = listOf(DiscoveredModel("deepseek-ai/deepseek-v4.1-flash")),
            responseBody = "{\"detail\":\"Not Found\"}",
        )
        assertEquals("Endpoint error", failure.label)
        assertTrue(failure.message.contains("gateway protocol"))
        assertFalse(failure.message.contains("not found on this provider"))
    }

    @Test
    fun notFoundWithModelComplaintMeansUnknownModel() {
        // v3.5.11: the model was never discovered and the provider body
        // complains about the model, so the model itself is unknown.
        val failure = ProviderApiClient().classifyNotFound(
            model = "made-up-model",
            discoveredModels = listOf(DiscoveredModel("real-model")),
            responseBody = "{\"error\":{\"message\":\"model not found: made-up-model\"}}",
        )
        assertEquals("Model error", failure.label)
        assertTrue(failure.message.contains("available models list"))
    }

    @Test
    fun notFoundMessagesDifferBetweenPathAndModel() {
        val pathFailure = ProviderApiClient().classifyNotFound(
            model = "known-model",
            discoveredModels = listOf(DiscoveredModel("known-model")),
            responseBody = "",
        )
        val modelFailure = ProviderApiClient().classifyNotFound(
            model = "unknown-model",
            discoveredModels = emptyList(),
            responseBody = "{\"error\":\"model not found\"}",
        )
        assertFalse(pathFailure.message == modelFailure.message)
        assertFalse(pathFailure.label == modelFailure.label)
    }

    @Test
    fun openRouterProbeIncludesRequestedProviderOrder() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "anthropic/claude-sonnet-4.6",
                protocol = ProviderProtocol.OPENROUTER,
                openRouterProviderOrder = " anthropic, amazon-bedrock, anthropic ",
                openRouterAllowFallbacks = false,
            ),
        )

        val routing = body.getJSONObject("provider")
        assertEquals(listOf("anthropic", "amazon-bedrock"), routing.getJSONArray("order").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertFalse(routing.getBoolean("allow_fallbacks"))
    }
}
