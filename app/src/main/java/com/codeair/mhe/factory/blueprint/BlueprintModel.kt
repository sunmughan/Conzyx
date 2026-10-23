package com.codeair.mhe.factory.blueprint

import com.codeair.mhe.factory.model.AppBlueprint
import com.codeair.mhe.factory.model.AppFramework
import com.codeair.mhe.factory.model.BuildMode

/**
 * One-shot completion seam used for blueprint synthesis — the same provider
 * stack the chat agents use (Gemini API route or a saved API provider),
 * minus the interactive agent session. Tests inject fakes.
 */
interface BlueprintModel {
    /** Human-readable route label, e.g. "Gemini API (gemini-3.1-pro)". */
    val label: String

    /** Runs one completion; throws with a user-readable reason on failure. */
    suspend fun complete(systemPrompt: String, userPrompt: String): String
}

/** No usable AI provider is configured — synthesis must refuse, not fake. */
class BlueprintModelUnavailableException(message: String) : Exception(message)

/** The model answered, but its answer could not be used as a blueprint. */
class BlueprintSynthesisException(message: String) : Exception(message)

/**
 * Drives [BlueprintModel] with the synthesis prompt contract and parses the
 * reply via [BlueprintJsonParser]. One repair retry is attempted when the
 * first reply is unusable JSON; after that the failure is reported honestly
 * instead of substituting any template.
 */
class BlueprintSynthesizer(private val model: BlueprintModel) {

    suspend fun synthesize(
        prompt: String,
        framework: AppFramework,
        buildMode: BuildMode,
        archetypeHint: AppFactoryBlueprintEngine.Archetype? = null,
    ): AppBlueprint {
        require(prompt.isNotBlank()) { "Describe the app you want to build first." }
        val system = BlueprintPrompts.systemPrompt()
        val user = BlueprintPrompts.userPrompt(prompt, framework, archetypeHint)
        val first = model.complete(system, user)
        return try {
            BlueprintJsonParser.parse(first, prompt, framework, buildMode)
        } catch (e: BlueprintParseException) {
            val repaired = model.complete(system, user + BlueprintPrompts.repairSuffix(e.message ?: "invalid JSON"))
            try {
                BlueprintJsonParser.parse(repaired, prompt, framework, buildMode)
            } catch (e2: BlueprintParseException) {
                throw BlueprintSynthesisException(
                    "The AI reply could not be turned into a blueprint (${e2.message}). " +
                        "Try again, or describe the app in a little more detail."
                )
            }
        }
    }
}
