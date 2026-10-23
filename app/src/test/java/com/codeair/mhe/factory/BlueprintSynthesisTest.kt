package com.codeair.mhe.factory

import com.codeair.mhe.factory.blueprint.BlueprintJsonParser
import com.codeair.mhe.factory.blueprint.BlueprintModel
import com.codeair.mhe.factory.blueprint.BlueprintModelUnavailableException
import com.codeair.mhe.factory.blueprint.BlueprintSynthesisException
import com.codeair.mhe.factory.blueprint.BlueprintSynthesizer
import com.codeair.mhe.factory.build.AppBuildOrchestrator
import com.codeair.mhe.factory.build.GradleBuildCommand
import com.codeair.mhe.factory.build.GuestBuildSession
import com.codeair.mhe.factory.build.GuestCommandResult
import com.codeair.mhe.factory.generators.AndroidComposeGenerator
import com.codeair.mhe.factory.model.AppFramework
import com.codeair.mhe.factory.model.BuildArtifactType
import com.codeair.mhe.factory.model.BuildMode
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The rebuilt App Factory contract: blueprints are designed by the AI from
 * the user's prompt (never archetype templates), scaffolding follows the
 * blueprint, and builds run through a runtime guest session with honest
 * outcomes.
 */
class BlueprintSynthesisTest {

    companion object {
        /** The user's own recorded case: a lead-generation prompt. */
        const val LEAD_PROMPT =
            "build a lead generation app capturing details from google maps and displaying in grids layout"

        val LEAD_JSON = """
            {
              "appName": "LeadGrid",
              "tagline": "Capture leads from Google Maps and browse them in a grid",
              "category": "Business",
              "fullDescription": "LeadGrid helps field sales teams capture business leads from Google Maps, save contact details, and browse them in a clean grid.",
              "entities": [
                {"name": "Lead", "tableName": "leads", "fields": [
                  {"name": "id", "type": "String", "primaryKey": true},
                  {"name": "businessName", "type": "String"},
                  {"name": "phone", "type": "String"},
                  {"name": "address", "type": "String"},
                  {"name": "rating", "type": "Double"},
                  {"name": "category", "type": "String"},
                  {"name": "capturedAt", "type": "Long"}
                ]},
                {"name": "Campaign", "tableName": "campaigns", "fields": [
                  {"name": "id", "type": "String", "primaryKey": true},
                  {"name": "name", "type": "String"},
                  {"name": "city", "type": "String"}
                ]}
              ],
              "endpoints": [
                {"method": "GET", "path": "/api/leads", "summary": "List captured leads", "responseModel": "List<Lead>"},
                {"method": "POST", "path": "/api/leads", "summary": "Save a captured lead", "requestBodyModel": "Lead", "responseModel": "Lead"},
                {"method": "GET", "path": "/api/leads/{id}", "summary": "Get one lead", "responseModel": "Lead"}
              ],
              "screens": [
                {"name": "LeadGridScreen", "route": "leads", "title": "Leads", "description": "Captured leads in a grid", "components": ["Lead grid", "Search bar"]},
                {"name": "LeadDetailScreen", "route": "lead_detail", "title": "Lead Details", "description": "Full lead info with call and WhatsApp actions", "components": ["Contact actions"]},
                {"name": "CaptureScreen", "route": "capture", "title": "Capture", "description": "Pick a place on the map to capture as a lead", "components": ["Map picker"]}
              ]
            }
        """.trimIndent()
    }

    private class FakeModel(
        private val replies: List<String>,
        override val label: String = "Fake provider (test-model)",
    ) : BlueprintModel {
        var calls = 0
        override suspend fun complete(systemPrompt: String, userPrompt: String): String {
            val reply = replies[minOf(calls, replies.size - 1)]
            calls++
            if (reply == UNAVAILABLE) throw BlueprintModelUnavailableException("no provider in test")
            return reply
        }
        companion object { const val UNAVAILABLE = "<unavailable>" }
    }

    private class FakeSession(
        private val result: GuestCommandResult,
        override val buildToolsVersion: String? = "35.0.0",
    ) : GuestBuildSession {
        val commands = mutableListOf<String>()
        override suspend fun run(command: String, timeoutMs: Long): GuestCommandResult {
            commands += command
            return result
        }
    }

    // ---------- Parser ----------

    @Test
    fun leadPromptJsonYieldsLeadCentricBlueprint() {
        val bp = BlueprintJsonParser.parse(LEAD_JSON, LEAD_PROMPT, AppFramework.ANDROID_COMPOSE, BuildMode.ON_DEVICE)
        assertEquals("LeadGrid", bp.name)
        assertEquals("com.pocketdev.leadgrid", bp.packageName)
        assertTrue(bp.entities.any { it.name == "Lead" })
        assertTrue(bp.entities.none { it.name == "TaskItem" })
        assertTrue(bp.screens.any { it.name == "LeadGridScreen" })
        assertTrue(bp.endpoints.any { it.path == "/api/leads" })
        assertEquals("Business", bp.playStoreMetadata.category)
        // Contact details are never fabricated.
        assertEquals("", bp.playStoreMetadata.contactEmail)
        assertEquals("", bp.playStoreMetadata.privacyPolicyUrl)
        // Exactly one primary key per entity.
        bp.entities.forEach { entity ->
            assertEquals("entity ${entity.name}", 1, entity.fields.count { it.primaryKey })
        }
    }

    @Test
    fun parserStripsMarkdownFencesAndProse() {
        val wrapped = "Here is your blueprint:\n```json\n$LEAD_JSON\n```\nHope that helps!"
        val bp = BlueprintJsonParser.parse(wrapped, LEAD_PROMPT, AppFramework.ANDROID_COMPOSE, BuildMode.ON_DEVICE)
        assertEquals("LeadGrid", bp.name)
    }

    @Test
    fun parserRejectsGarbageAndEmptyDesigns() {
        assertThrowsParse("not json at all")
        assertThrowsParse("""{"appName": "X"}""") // no entities/screens
        assertThrowsParse("""{"appName": "", "entities": [], "screens": []}""")
    }

    @Test
    fun parserAddsPrimaryKeyWhenModelOmitsIt() {
        val json = """
            {"appName": "MeterLog", "tagline": "Track meter readings",
             "entities": [{"name": "Reading", "tableName": "readings", "fields": [
                {"name": "value", "type": "Double"}, {"name": "note", "type": "String"}]}],
             "screens": [{"name": "ReadingsScreen", "route": "readings", "title": "Readings", "description": "All readings"}],
             "endpoints": [{"method": "GET", "path": "/api/readings", "summary": "List readings"}]}
        """.trimIndent()
        val bp = BlueprintJsonParser.parse(json, "meter readings", AppFramework.ANDROID_COMPOSE, BuildMode.ON_DEVICE)
        val reading = bp.entities.single()
        assertEquals(1, reading.fields.count { it.primaryKey })
        assertEquals("id", reading.fields.first { it.primaryKey }.name)
    }

    @Test
    fun parserNormalizesStringDefaultsToKotlinLiterals() {
        val json = """
            {"appName": "Habitual", "tagline": "Habits",
             "entities": [{"name": "Habit", "tableName": "habits", "fields": [
                {"name": "id", "type": "String", "primaryKey": true},
                {"name": "status", "type": "String", "defaultValue": "PENDING"},
                {"name": "streak", "type": "Int", "defaultValue": 3}]}],
             "screens": [{"name": "HabitsScreen", "route": "habits", "title": "Habits", "description": "All habits"}],
             "endpoints": []}
        """.trimIndent()
        val bp = BlueprintJsonParser.parse(json, "habits", AppFramework.ANDROID_COMPOSE, BuildMode.ON_DEVICE)
        val habit = bp.entities.single()
        assertEquals("\"PENDING\"", habit.fields.first { it.name == "status" }.defaultValue)
        assertEquals("3", habit.fields.first { it.name == "streak" }.defaultValue)
    }

    private fun assertThrowsParse(raw: String) {
        try {
            BlueprintJsonParser.parse(raw, "prompt", AppFramework.ANDROID_COMPOSE, BuildMode.ON_DEVICE)
            fail("Expected BlueprintParseException for: $raw")
        } catch (expected: com.codeair.mhe.factory.blueprint.BlueprintParseException) {
            // expected
        }
    }

    // ---------- Synthesizer ----------

    @Test
    fun synthesizerReturnsBlueprintDesignedByModel() = runBlocking {
        val model = FakeModel(listOf(LEAD_JSON))
        val bp = BlueprintSynthesizer(model).synthesize(LEAD_PROMPT, AppFramework.ANDROID_COMPOSE, BuildMode.ON_DEVICE)
        assertEquals("LeadGrid", bp.name)
        assertTrue(bp.entities.any { it.name == "Lead" })
        assertEquals(1, model.calls)
    }

    @Test
    fun synthesizerRetriesOnceWithRepairPrompt() = runBlocking {
        val model = FakeModel(listOf("total garbage", LEAD_JSON))
        val bp = BlueprintSynthesizer(model).synthesize(LEAD_PROMPT, AppFramework.ANDROID_COMPOSE, BuildMode.ON_DEVICE)
        assertEquals("LeadGrid", bp.name)
        assertEquals(2, model.calls)
    }

    @Test
    fun synthesizerFailsHonestlyWhenModelKeepsFailing() = runBlocking {
        val model = FakeModel(listOf("junk", "still junk"))
        try {
            BlueprintSynthesizer(model).synthesize(LEAD_PROMPT, AppFramework.ANDROID_COMPOSE, BuildMode.ON_DEVICE)
            fail("Expected BlueprintSynthesisException")
        } catch (expected: BlueprintSynthesisException) {
            assertTrue(expected.message!!.contains("could not be turned into a blueprint"))
        }
    }

    @Test
    fun synthesizerPropagatesProviderUnavailableWithoutFaking() = runBlocking {
        val model = FakeModel(listOf(FakeModel.UNAVAILABLE))
        try {
            BlueprintSynthesizer(model).synthesize(LEAD_PROMPT, AppFramework.ANDROID_COMPOSE, BuildMode.ON_DEVICE)
            fail("Expected BlueprintModelUnavailableException")
        } catch (expected: BlueprintModelUnavailableException) {
            // No blueprint was produced — the UI reports the missing provider.
        }
    }

    // ---------- Scaffold from blueprint ----------

    @Test
    fun scaffoldFollowsTheSynthesizedBlueprint() {
        val tempDir = Files.createTempDirectory("lead_scaffold_test").toFile()
        try {
            val bp = BlueprintJsonParser.parse(LEAD_JSON, LEAD_PROMPT, AppFramework.ANDROID_COMPOSE, BuildMode.ON_DEVICE)
            val files = AndroidComposeGenerator.generateProject(tempDir, bp)
            assertTrue(files.size >= 12)

            val packagePath = bp.packageName.replace('.', '/')
            val javaDir = File(tempDir, "app/src/main/java/$packagePath")

            // Lead-centric files exist; nothing from the old task template.
            assertTrue(File(javaDir, "data/local/LeadEntity.kt").exists())
            assertTrue(File(javaDir, "data/local/LeadDao.kt").exists())
            assertTrue(File(javaDir, "data/remote/LeadDto.kt").exists())
            assertTrue(File(javaDir, "ui/screens/LeadGridScreen.kt").exists())
            assertFalse(File(javaDir, "data/local/TaskItemEntity.kt").exists())
            files.forEach { file ->
                if (file.isFile && file.extension in setOf("kt", "kts", "xml")) {
                    assertFalse("${file.name} leaks the task template", file.readText().contains("TaskItem"))
                }
            }

            // The screen renders real stored leads, not a placeholder card.
            val screen = File(javaDir, "ui/screens/LeadGridScreen.kt").readText()
            assertTrue(screen.contains("leadDao().getAll()"))
            assertTrue(screen.contains("collectAsState"))
            assertTrue(screen.contains("item.businessName"))
            assertFalse(screen.contains("Interactive component ready"))

            // The API service is typed against the blueprint, not Response<Any>.
            val api = File(javaDir, "data/remote/ApiService.kt").readText()
            assertTrue(api.contains("List<LeadDto>"))
            assertTrue(api.contains("@Body body: LeadDto"))
            assertTrue(api.contains("@Path(\"id\")"))
            assertFalse(api.contains("Response<Any>"))

            // Manifest theme and themes.xml agree (AAPT link must succeed).
            val manifest = File(tempDir, "app/src/main/AndroidManifest.xml").readText()
            val themes = File(tempDir, "app/src/main/res/values/themes.xml").readText()
            val manifestTheme = Regex("@style/(Theme\\.[A-Za-z0-9_]+)").find(manifest)!!.groupValues[1]
            val definedTheme = Regex("<style name=\"(Theme\\.[A-Za-z0-9_]+)\"").find(themes)!!.groupValues[1]
            assertEquals(manifestTheme, definedTheme)

            // gradlew locates the runtime toolchain instead of a bare exec.
            val gradlew = File(tempDir, "gradlew").readText()
            assertTrue(gradlew.contains("/opt/gradle"))
            assertTrue(gradlew.contains("command -v gradle"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---------- Build command construction ----------

    @Test
    fun gradleCommandTargetsRuntimeToolchain() {
        val command = GradleBuildCommand.command("/workspace", BuildArtifactType.APK_DEBUG, "35.0.0")
        assertTrue(command.contains("cd '/workspace'"))
        assertTrue(command.contains("gradle --init-script /root/.gradle/init.d/pocketdev-android.gradle"))
        assertTrue(command.contains("-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2"))
        assertTrue(command.contains("--no-daemon assembleDebug --console=plain"))
        assertEquals("bundleRelease", GradleBuildCommand.taskFor(BuildArtifactType.AAB_BUNDLE))
        assertEquals("assembleRelease", GradleBuildCommand.taskFor(BuildArtifactType.APK_RELEASE))
    }

    @Test
    fun latestVersionedNamePicksNewestBuildTools() {
        assertEquals("36.0.0", GradleBuildCommand.latestVersionedName(listOf("35.0.0", "36.0.0", "34.0.0")))
        assertEquals("35.0.1", GradleBuildCommand.latestVersionedName(listOf("35.0.0", "35.0.1")))
        assertEquals(null, GradleBuildCommand.latestVersionedName(emptyList()))
    }

    @Test
    fun keytoolCommandIsGuestReady() {
        val command = GradleBuildCommand.keytoolCommand(
            guestKeystorePath = "/workspace/release.keystore",
            keyAlias = "releaseKey",
            storePassword = "secret1",
            keyPassword = "secret1",
        )
        assertTrue(command.startsWith("keytool -genkeypair"))
        assertTrue(command.contains("-keystore '/workspace/release.keystore'"))
        assertTrue(command.contains("-alias 'releaseKey'"))
    }

    // ---------- Orchestrator behavior with a fake guest session ----------

    private fun scaffoldedProjectDir(): File {
        val dir = Files.createTempDirectory("orchestrator_test").toFile()
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"LeadGrid\"\n")
        return dir
    }

    private fun leadBlueprint(framework: AppFramework = AppFramework.ANDROID_COMPOSE) =
        BlueprintJsonParser.parse(LEAD_JSON, LEAD_PROMPT, framework, BuildMode.ON_DEVICE)

    @Test
    fun orchestratorSucceedsOnlyWithExitZeroAndArtifact() = runBlocking {
        val dir = scaffoldedProjectDir()
        try {
            val apkDir = File(dir, "app/build/outputs/apk/debug").apply { mkdirs() }
            File(apkDir, "app-debug.apk").writeText("PK fake apk bytes")
            val session = FakeSession(GuestCommandResult(exitCode = 0, output = "BUILD SUCCESSFUL"))
            val result = AppBuildOrchestrator.runBuild(
                projectDir = dir,
                blueprint = leadBlueprint(),
                artifactType = BuildArtifactType.APK_DEBUG,
                buildMode = BuildMode.ON_DEVICE,
                session = session,
                guestProjectPath = "/workspace",
            )
            assertTrue(result.success)
            assertNotNull(result.outputFile)
            assertEquals(1, session.commands.size)
            assertTrue(session.commands.single().contains("assembleDebug"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun orchestratorReportsRealGradleFailure() = runBlocking {
        val dir = scaffoldedProjectDir()
        try {
            val session = FakeSession(GuestCommandResult(exitCode = 1, output = "Compilation failed"))
            val result = AppBuildOrchestrator.runBuild(
                projectDir = dir,
                blueprint = leadBlueprint(),
                artifactType = BuildArtifactType.APK_DEBUG,
                buildMode = BuildMode.ON_DEVICE,
                session = session,
                guestProjectPath = "/workspace",
            )
            assertFalse(result.success)
            assertTrue(result.errorMessage!!.contains("code 1"))
            assertTrue(result.logs.any { it.contains("Compilation failed") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun orchestratorWithoutSessionRefusesToFakeABuild() = runBlocking {
        val dir = scaffoldedProjectDir()
        try {
            val result = AppBuildOrchestrator.runBuild(
                projectDir = dir,
                blueprint = leadBlueprint(),
                artifactType = BuildArtifactType.APK_DEBUG,
                buildMode = BuildMode.ON_DEVICE,
                session = null,
            )
            assertFalse(result.success)
            assertTrue(result.errorMessage!!.contains("no build was run", ignoreCase = true) ||
                result.errorMessage!!.contains("unavailable", ignoreCase = true))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun orchestratorGitHubModeWritesWorkflowAndClaimsNoBuild() = runBlocking {
        val dir = scaffoldedProjectDir()
        try {
            val result = AppBuildOrchestrator.runBuild(
                projectDir = dir,
                blueprint = leadBlueprint(),
                artifactType = BuildArtifactType.APK_DEBUG,
                buildMode = BuildMode.GITHUB_ACTIONS,
                session = null,
            )
            assertFalse("workflow generation must not masquerade as a build", result.success)
            assertNotNull(result.ciWorkflowFile)
            assertTrue(result.ciWorkflowFile!!.exists())
            assertTrue(result.errorMessage!!.contains("Nothing was built"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun orchestratorRefusesNonAndroidFrameworksHonestly() = runBlocking {
        val dir = scaffoldedProjectDir()
        try {
            val session = FakeSession(GuestCommandResult(exitCode = 0, output = ""))
            val result = AppBuildOrchestrator.runBuild(
                projectDir = dir,
                blueprint = leadBlueprint(AppFramework.FLUTTER),
                artifactType = BuildArtifactType.APK_DEBUG,
                buildMode = BuildMode.ON_DEVICE,
                session = session,
                guestProjectPath = "/workspace",
            )
            assertFalse(result.success)
            assertTrue(result.errorMessage!!.contains("Android"))
            assertTrue("no gradle command may run for a Flutter blueprint", session.commands.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
