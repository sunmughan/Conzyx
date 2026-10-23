package com.codeair.mhe.factory.blueprint

import com.codeair.mhe.factory.model.ApiEndpointSpec
import com.codeair.mhe.factory.model.AppBlueprint
import com.codeair.mhe.factory.model.AppFramework
import com.codeair.mhe.factory.model.BuildConfigSpec
import com.codeair.mhe.factory.model.BuildMode
import com.codeair.mhe.factory.model.DataEntityField
import com.codeair.mhe.factory.model.DataEntitySpec
import com.codeair.mhe.factory.model.PlayStoreMetadataSpec
import com.codeair.mhe.factory.model.ScreenSpec
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Thrown when a model reply cannot be turned into a valid blueprint. */
class BlueprintParseException(message: String) : Exception(message)

/**
 * Turns a model's JSON reply into a validated, sanitized [AppBlueprint].
 *
 * Pure and deterministic: every name is normalized into a legal Kotlin /
 * Android identifier, field types are restricted to the set the generators
 * support, and anything the model must not invent (contact email, privacy
 * URL) is left blank so downstream compliance checks report it honestly as
 * a TODO instead of rubber-stamping a fabricated address.
 */
object BlueprintJsonParser {

    private val FIELD_TYPES = setOf("String", "Int", "Long", "Double", "Boolean")
    private val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH")

    private val PLAY_CATEGORIES = setOf(
        "Art & Design", "Auto & Vehicles", "Beauty", "Books & Reference", "Business",
        "Comics", "Communication", "Dating", "Education", "Entertainment", "Events",
        "Finance", "Food & Drink", "Health & Fitness", "House & Home", "Libraries & Demo",
        "Lifestyle", "Maps & Navigation", "Medical", "Music & Audio", "News & Magazines",
        "Parenting", "Personalization", "Photography", "Productivity", "Shopping",
        "Social", "Sports", "Tools", "Travel & Local", "Video Players & Editors", "Weather",
    )

    /** Extracts the JSON object text from a raw model reply (fences/prose tolerant). */
    fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        val unfenced = trimmed
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw BlueprintParseException("the reply contained no JSON object")
        }
        return unfenced.substring(start, end + 1)
    }

    fun parse(
        rawReply: String,
        userPrompt: String,
        framework: AppFramework,
        buildMode: BuildMode,
    ): AppBlueprint {
        val jsonText = extractJsonObject(rawReply)
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            throw BlueprintParseException("the reply was not valid JSON (${e.message?.take(80)})")
        }

        val appName = sanitizeAppName(root.optString("appName"))
            ?: throw BlueprintParseException("the blueprint JSON has no usable appName")
        val packageName = AppFactoryBlueprintEngine.sanitizePackageName(appName)
        val tagline = root.optString("tagline").trim().take(300)
        val description = tagline.ifBlank { userPrompt.trim().take(300) }

        val entities = parseEntities(root.optJSONArray("entities"))
        if (entities.isEmpty()) {
            throw BlueprintParseException("the blueprint JSON defines no data entities")
        }
        val screens = parseScreens(root.optJSONArray("screens"))
        if (screens.isEmpty()) {
            throw BlueprintParseException("the blueprint JSON defines no screens")
        }
        val endpoints = parseEndpoints(root.optJSONArray("endpoints"), entities)

        val category = root.optString("category").trim()
            .takeIf { it in PLAY_CATEGORIES } ?: "Productivity"
        val fullDescription = root.optString("fullDescription").trim()
            .ifBlank { deriveFullDescription(appName, description, screens, entities) }

        return AppBlueprint(
            id = UUID.randomUUID().toString(),
            name = appName,
            packageName = packageName,
            description = description,
            framework = framework,
            buildMode = buildMode,
            entities = entities,
            endpoints = endpoints,
            screens = screens,
            buildConfig = BuildConfigSpec(
                applicationId = packageName,
                appName = appName,
                versionCode = 1,
                versionName = "1.0.0",
                minSdk = 26,
                targetSdk = 35,
                compileSdk = 35,
            ),
            playStoreMetadata = PlayStoreMetadataSpec(
                title = appName.take(30),
                shortDescription = description.take(80),
                fullDescription = fullDescription.take(4000),
                category = category,
                // Never fabricated: the developer supplies these before a
                // real submission; the compliance audit flags them as TODO.
                contactEmail = "",
                privacyPolicyUrl = "",
                targetSdkCompliant = true,
                contentRating = "Everyone",
            ),
        )
    }

    private fun deriveFullDescription(
        appName: String,
        description: String,
        screens: List<ScreenSpec>,
        entities: List<DataEntitySpec>,
    ): String = buildString {
        appendLine("$appName — $description")
        appendLine()
        appendLine("What you can do:")
        screens.forEach { appendLine("• ${it.title}: ${it.description}") }
        appendLine()
        append("Your data (${entities.joinToString(", ") { it.name.lowercase() }}) is stored " +
            "on your device, so the app keeps working offline.")
    }.trim()

    private fun parseEntities(array: JSONArray?): List<DataEntitySpec> {
        if (array == null) return emptyList()
        val result = mutableListOf<DataEntitySpec>()
        val usedNames = mutableSetOf<String>()
        for (i in 0 until minOf(array.length(), 8)) {
            val obj = array.optJSONObject(i) ?: continue
            val name = sanitizePascalCase(obj.optString("name")) ?: continue
            if (!usedNames.add(name)) continue
            val fields = parseFields(obj.optJSONArray("fields"))
            if (fields.isEmpty()) continue
            val withPk = if (fields.any { it.primaryKey }) {
                // Exactly one primary key: keep the first flagged, unflag the rest.
                var seen = false
                fields.map { f ->
                    if (f.primaryKey && !seen) { seen = true; f } else f.copy(primaryKey = false)
                }
            } else {
                listOf(DataEntityField("id", "String", primaryKey = true)) + fields
            }
            val table = sanitizeTableName(obj.optString("tableName"), name)
            result += DataEntitySpec(name = name, tableName = table, fields = withPk)
        }
        return result
    }

    private fun parseFields(array: JSONArray?): List<DataEntityField> {
        if (array == null) return emptyList()
        val result = mutableListOf<DataEntityField>()
        val used = mutableSetOf<String>()
        for (i in 0 until minOf(array.length(), 16)) {
            val obj = array.optJSONObject(i) ?: continue
            val name = sanitizeCamelCase(obj.optString("name")) ?: continue
            if (!used.add(name)) continue
            val type = obj.optString("type").trim()
                .replaceFirstChar { it.uppercase() }
                .takeIf { it in FIELD_TYPES } ?: "String"
            result += DataEntityField(
                name = name,
                type = type,
                primaryKey = obj.optBoolean("primaryKey", false),
                nullable = obj.optBoolean("nullable", false),
                defaultValue = sanitizeDefaultValue(type, obj.opt("defaultValue")),
            )
        }
        return result
    }

    /**
     * Normalizes a model-supplied default into a Kotlin literal the
     * generators can emit verbatim (strings become double-quoted literals —
     * the old templates emitted `'PENDING'` single-quoted chars that would
     * not compile against a String field).
     */
    private fun sanitizeDefaultValue(type: String, raw: Any?): String? {
        if (raw == null || raw == JSONObject.NULL) return null
        val text = raw.toString().trim().trim('"', '\'') 
        if (text.isBlank() || text.equals("null", ignoreCase = true)) return null
        return when (type) {
            "Boolean" -> when (text.lowercase()) {
                "true" -> "true"
                "false" -> "false"
                else -> null
            }
            "Int", "Long" -> text.toLongOrNull()?.let { if (type == "Long") "${it}L" else it.toString() }
            "Double" -> text.toDoubleOrNull()?.let { it.toString() }
            else -> "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    private fun parseEndpoints(array: JSONArray?, entities: List<DataEntitySpec>): List<ApiEndpointSpec> {
        if (array == null) return emptyList()
        val entityNames = entities.map { it.name }.toSet()
        val result = mutableListOf<ApiEndpointSpec>()
        for (i in 0 until minOf(array.length(), 12)) {
            val obj = array.optJSONObject(i) ?: continue
            val method = obj.optString("method").trim().uppercase().takeIf { it in HTTP_METHODS } ?: continue
            var path = obj.optString("path").trim().replace(" ", "")
            if (path.isBlank()) continue
            if (!path.startsWith("/")) path = "/$path"
            result += ApiEndpointSpec(
                method = method,
                path = path,
                summary = obj.optString("summary").trim().take(160),
                requestBodyModel = obj.optString("requestBodyModel").trim()
                    .takeIf { it in entityNames },
                responseModel = sanitizeResponseModel(obj.optString("responseModel"), entityNames),
            )
        }
        return result
    }

    private fun sanitizeResponseModel(raw: String, entityNames: Set<String>): String? {
        val text = raw.trim()
        if (text.isBlank() || text.equals("null", ignoreCase = true)) return null
        val listMatch = Regex("""List<\s*([A-Za-z0-9_]+)\s*>""").matchEntire(text)
        if (listMatch != null) {
            val inner = listMatch.groupValues[1]
            return if (inner in entityNames) "List<$inner>" else null
        }
        return text.takeIf { it in entityNames }
    }

    private fun parseScreens(array: JSONArray?): List<ScreenSpec> {
        if (array == null) return emptyList()
        val result = mutableListOf<ScreenSpec>()
        val usedNames = mutableSetOf<String>()
        val usedRoutes = mutableSetOf<String>()
        for (i in 0 until minOf(array.length(), 8)) {
            val obj = array.optJSONObject(i) ?: continue
            var name = sanitizePascalCase(obj.optString("name")) ?: continue
            if (!name.endsWith("Screen")) name += "Screen"
            if (!usedNames.add(name)) continue
            var route = sanitizeRoute(obj.optString("route"))
            if (route.isBlank() || !usedRoutes.add(route)) {
                route = name.removeSuffix("Screen").lowercase()
                if (!usedRoutes.add(route)) route = "screen${result.size + 1}"
            }
            val title = obj.optString("title").trim()
                .ifBlank { humanize(name.removeSuffix("Screen")) }
            val components = mutableListOf<String>()
            val comps = obj.optJSONArray("components")
            if (comps != null) {
                for (j in 0 until minOf(comps.length(), 8)) {
                    val c = comps.optString(j).trim().replace(Regex("[^A-Za-z0-9 ]"), "")
                    if (c.isNotBlank()) components += c
                }
            }
            result += ScreenSpec(
                name = name,
                route = route,
                title = title.take(60),
                description = obj.optString("description").trim().take(240),
                components = components,
            )
        }
        return result
    }

    internal fun sanitizeAppName(raw: String): String? {
        val cleaned = raw.trim().trim('"', '\'')
            .replace(Regex("\\s+"), " ")
            .take(40)
        return cleaned.takeIf { it.length >= 2 && it.any { c -> c.isLetter() } }
    }

    internal fun sanitizePascalCase(raw: String): String? {
        val parts = raw.trim().split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val joined = parts.joinToString("") { part ->
            part.replaceFirstChar { it.uppercase() }
        }
        val candidate = if (joined.first().isDigit()) "App$joined" else joined
        return candidate.take(48).takeIf { it.isNotBlank() }
    }

    internal fun sanitizeCamelCase(raw: String): String? {
        val pascal = sanitizePascalCase(raw) ?: return null
        return pascal.replaceFirstChar { it.lowercase() }
    }

    internal fun sanitizeTableName(raw: String, entityName: String): String {
        val fromRaw = raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (fromRaw.isNotBlank() && fromRaw.first().isLetter()) return fromRaw.take(48)
        val derived = entityName
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
        return (if (derived.isBlank()) "items" else derived).take(48)
    }

    internal fun sanitizeRoute(raw: String): String =
        raw.trim().lowercase()
            .replace(Regex("[^a-z0-9/_{}-]+"), "")
            .trim('/')
            .replace(Regex("/{2,}"), "/")

    private fun humanize(name: String): String =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").trim()
}
