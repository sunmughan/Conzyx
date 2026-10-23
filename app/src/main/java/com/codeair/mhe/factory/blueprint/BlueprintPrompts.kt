package com.codeair.mhe.factory.blueprint

import com.codeair.mhe.factory.model.AppFramework

/**
 * Prompt contract for AI blueprint synthesis.
 *
 * The model is asked to DESIGN an app from the user's own words — data
 * models, screens and API surface specific to that request. Archetypes, when
 * the user picked one, are passed strictly as a starting hint; the output
 * must follow the prompt, never a canned template.
 */
object BlueprintPrompts {

    fun systemPrompt(): String = """
        You are the software architect inside Conzyx App Factory. Given a user's
        app idea, design a concrete, buildable app blueprint for THAT idea.

        Reply with ONLY a single JSON object — no markdown fences, no prose,
        no explanations. The JSON must match this schema exactly:

        {
          "appName": "Short product name, 2-4 words",
          "tagline": "One sentence describing what the app does (max 80 chars)",
          "category": "Google Play category, e.g. Business, Productivity, Shopping",
          "fullDescription": "3-5 sentences describing the app, its users and its key features, written for a store listing",
          "entities": [
            {
              "name": "PascalCase entity name",
              "tableName": "snake_case_table_name",
              "fields": [
                {"name": "camelCaseField", "type": "String|Int|Long|Double|Boolean", "primaryKey": true|false, "nullable": true|false}
              ]
            }
          ],
          "endpoints": [
            {"method": "GET|POST|PUT|DELETE", "path": "/api/...", "summary": "what it does", "requestBodyModel": "EntityName or null", "responseModel": "EntityName or List<EntityName> or null"}
          ],
          "screens": [
            {"name": "PascalCaseNameScreen", "route": "lowercase/route", "title": "Screen title", "description": "What the user does on this screen", "components": ["ComponentName", "..."]}
          ]
        }

        Rules:
        - Design 2-5 entities that capture the real data of THIS app idea,
          each with 3-8 meaningful fields. Every entity needs exactly one
          primary key field (use "id" of type "String" when unsure).
        - Design 3-6 screens the app actually needs (list/grid, detail,
          create/edit, and any idea-specific screens). Screen names must end
          with "Screen". Routes are unique lowercase paths.
        - Design the API endpoints the screens need, using the entity names
          as models. Paths start with /api/.
        - Everything must be specific to the user's idea: its nouns, its
          workflow, its vocabulary. Never fall back to a generic task manager,
          to-do list, or notes app unless the user literally asked for one.
        - Do not invent a contact email, website, company, or privacy policy
          URL. Those are filled in by the developer later.
    """.trimIndent()

    fun userPrompt(
        prompt: String,
        framework: AppFramework,
        archetypeHint: AppFactoryBlueprintEngine.Archetype?,
    ): String = buildString {
        appendLine("App idea from the user:")
        appendLine(prompt.trim())
        appendLine()
        appendLine("Target framework: ${framework.displayName} (${framework.description})")
        if (archetypeHint != null) {
            appendLine(
                "The user picked this starting archetype as a hint: " +
                    "${archetypeHint.title} — ${archetypeHint.description}. " +
                    "Treat it only as inspiration; if the app idea above points somewhere " +
                    "different, follow the idea, not the archetype."
            )
        }
        appendLine()
        append("Design the blueprint JSON for this app now.")
    }

    /** Follow-up used when the first reply could not be parsed. */
    fun repairSuffix(problem: String): String =
        "\n\nIMPORTANT: Your previous reply could not be used ($problem). " +
            "Reply again with ONLY the corrected JSON object — no markdown fences, no prose."
}
