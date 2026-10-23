package com.codeair.mhe.factory

import com.codeair.mhe.factory.blueprint.BlueprintJsonParser
import com.codeair.mhe.factory.build.AppBuildOrchestrator
import com.codeair.mhe.factory.build.PlayStorePreparationEngine
import com.codeair.mhe.factory.build.SigningAndVersionManager
import com.codeair.mhe.factory.generators.AndroidComposeGenerator
import com.codeair.mhe.factory.generators.ApiIntegrationGenerator
import com.codeair.mhe.factory.generators.BackendServiceGenerator
import com.codeair.mhe.factory.generators.DatabaseSetupEngine
import com.codeair.mhe.factory.generators.FlutterAppGenerator
import com.codeair.mhe.factory.generators.ReactNativeAppGenerator
import com.codeair.mhe.factory.model.AppBlueprint
import com.codeair.mhe.factory.model.AppFramework
import com.codeair.mhe.factory.model.BuildArtifactType
import com.codeair.mhe.factory.model.BuildMode
import com.codeair.mhe.factory.model.VersionBumpType
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Generator/engine coverage. Blueprints come from [BlueprintJsonParser]
 * (the same path production synthesis uses) over representative model
 * replies — the old archetype template engine is gone.
 */
class AppFactoryTest {

    companion object {
        private fun blueprintFromJson(
            json: String,
            prompt: String,
            framework: AppFramework = AppFramework.ANDROID_COMPOSE,
            buildMode: BuildMode = BuildMode.ON_DEVICE,
        ): AppBlueprint = BlueprintJsonParser.parse(json, prompt, framework, buildMode)

        val FOOD_JSON = """
            {"appName": "BiteDash", "tagline": "Order food from nearby restaurants",
             "category": "Food & Drink",
             "entities": [
               {"name": "Restaurant", "tableName": "restaurants", "fields": [
                 {"name": "id", "type": "String", "primaryKey": true},
                 {"name": "name", "type": "String"}, {"name": "cuisine", "type": "String"},
                 {"name": "rating", "type": "Double"}]},
               {"name": "MenuItem", "tableName": "menu_items", "fields": [
                 {"name": "id", "type": "String", "primaryKey": true},
                 {"name": "name", "type": "String"}, {"name": "price", "type": "Double"},
                 {"name": "restaurantId", "type": "String"}]},
               {"name": "Order", "tableName": "orders", "fields": [
                 {"name": "id", "type": "String", "primaryKey": true},
                 {"name": "status", "type": "String"}, {"name": "total", "type": "Double"}]}],
             "endpoints": [
               {"method": "GET", "path": "/api/restaurants", "summary": "List restaurants", "responseModel": "List<Restaurant>"},
               {"method": "POST", "path": "/api/orders", "summary": "Place an order", "requestBodyModel": "Order", "responseModel": "Order"}],
             "screens": [
               {"name": "ExploreScreen", "route": "explore", "title": "Explore", "description": "Browse restaurants"},
               {"name": "RestaurantDetailScreen", "route": "restaurant_detail", "title": "Restaurant", "description": "Menu and reviews"},
               {"name": "CartScreen", "route": "cart", "title": "Cart", "description": "Your order"}]}
        """.trimIndent()

        val SHOP_JSON = """
            {"appName": "StyleKart", "tagline": "Shop clothes with cart and orders",
             "category": "Shopping",
             "entities": [
               {"name": "Product", "tableName": "products", "fields": [
                 {"name": "id", "type": "String", "primaryKey": true},
                 {"name": "name", "type": "String"}, {"name": "price", "type": "Double"}]},
               {"name": "CartItem", "tableName": "cart_items", "fields": [
                 {"name": "id", "type": "String", "primaryKey": true},
                 {"name": "productId", "type": "String"}, {"name": "quantity", "type": "Int"}]}],
             "endpoints": [
               {"method": "GET", "path": "/api/products", "summary": "List products", "responseModel": "List<Product>"}],
             "screens": [
               {"name": "CatalogScreen", "route": "catalog", "title": "Catalog", "description": "All products"},
               {"name": "CartScreen", "route": "cart", "title": "Cart", "description": "Your cart"}]}
        """.trimIndent()

        val CHAT_JSON = """
            {"appName": "PingChat", "tagline": "Chat with friends in real time",
             "category": "Social",
             "entities": [
               {"name": "Message", "tableName": "messages", "fields": [
                 {"name": "id", "type": "String", "primaryKey": true},
                 {"name": "text", "type": "String"}, {"name": "senderId", "type": "String"},
                 {"name": "sentAt", "type": "Long"}]},
               {"name": "Conversation", "tableName": "conversations", "fields": [
                 {"name": "id", "type": "String", "primaryKey": true},
                 {"name": "title", "type": "String"}]}],
             "endpoints": [
               {"method": "GET", "path": "/api/messages", "summary": "List messages", "responseModel": "List<Message>"}],
             "screens": [
               {"name": "ConversationsScreen", "route": "conversations", "title": "Chats", "description": "All conversations"},
               {"name": "ChatRoomScreen", "route": "chat_room", "title": "Chat", "description": "One conversation"}]}
        """.trimIndent()

        val NOTES_JSON = """
            {"appName": "NoteVault", "tagline": "Notes and knowledge vault",
             "entities": [
               {"name": "Note", "tableName": "notes", "fields": [
                 {"name": "id", "type": "String", "primaryKey": true},
                 {"name": "title", "type": "String"}, {"name": "body", "type": "String"}]}],
             "endpoints": [
               {"method": "GET", "path": "/api/notes", "summary": "List notes", "responseModel": "List<Note>"},
               {"method": "POST", "path": "/api/notes", "summary": "Create a note", "requestBodyModel": "Note", "responseModel": "Note"}],
             "screens": [
               {"name": "NotesScreen", "route": "notes", "title": "Notes", "description": "All notes"},
               {"name": "NoteEditorScreen", "route": "note_editor", "title": "Editor", "description": "Edit a note"}]}
        """.trimIndent()

        val FINANCE_JSON = """
            {"appName": "MoneyTrail", "tagline": "Personal finance and budget tracking",
             "category": "Finance",
             "entities": [
               {"name": "Transaction", "tableName": "transactions", "fields": [
                 {"name": "id", "type": "String", "primaryKey": true},
                 {"name": "title", "type": "String"}, {"name": "amount", "type": "Double"},
                 {"name": "type", "type": "String"}, {"name": "timestamp", "type": "Long"}]}],
             "endpoints": [
               {"method": "GET", "path": "/api/transactions", "summary": "List transactions", "responseModel": "List<Transaction>"}],
             "screens": [
               {"name": "DashboardScreen", "route": "dashboard", "title": "Dashboard", "description": "Money overview"},
               {"name": "TransactionsScreen", "route": "transactions", "title": "Transactions", "description": "All transactions"}]}
        """.trimIndent()
    }

    @Test
    fun testParsedBlueprintsCarryThePromptsDomain() {
        val foodBlueprint = blueprintFromJson(
            FOOD_JSON, "I need an app for ordering food and restaurant meals"
        )
        assertEquals("Android Jetpack Compose", foodBlueprint.framework.displayName)
        assertTrue("Expected Restaurant entity", foodBlueprint.entities.any { it.name == "Restaurant" })
        assertTrue("Expected MenuItem entity", foodBlueprint.entities.any { it.name == "MenuItem" })
        assertTrue("Expected ExploreScreen", foodBlueprint.screens.any { it.name == "ExploreScreen" })
        assertTrue(foodBlueprint.endpoints.isNotEmpty())
        assertTrue("Expected valid package name", foodBlueprint.packageName.startsWith("com.pocketdev."))
        assertEquals(35, foodBlueprint.buildConfig.targetSdk)

        val shopBlueprint = blueprintFromJson(
            SHOP_JSON, "Build a modern shopping store for clothes with cart and orders",
            framework = AppFramework.FLUTTER, buildMode = BuildMode.GITHUB_ACTIONS
        )
        assertEquals(AppFramework.FLUTTER, shopBlueprint.framework)
        assertEquals(BuildMode.GITHUB_ACTIONS, shopBlueprint.buildMode)
        assertTrue(shopBlueprint.entities.any { it.name == "Product" })
        assertTrue(shopBlueprint.entities.any { it.name == "CartItem" })
        assertTrue(shopBlueprint.screens.any { it.name == "CatalogScreen" })

        val chatBlueprint = blueprintFromJson(
            CHAT_JSON, "A chat messenger application with real-time messages and conversations",
            framework = AppFramework.REACT_NATIVE
        )
        assertTrue(chatBlueprint.entities.any { it.name == "Message" })
        assertTrue(chatBlueprint.screens.any { it.name == "ChatRoomScreen" })
    }

    @Test
    fun testAndroidComposeGenerator() {
        val tempDir = Files.createTempDirectory("android_factory_test").toFile()
        try {
            val blueprint = blueprintFromJson(
                BlueprintSynthesisTest.LEAD_JSON,
                BlueprintSynthesisTest.LEAD_PROMPT
            )

            val generatedFiles = AndroidComposeGenerator.generateProject(tempDir, blueprint)
            assertTrue("Expected multiple generated files", generatedFiles.size >= 12)

            assertTrue(File(tempDir, "settings.gradle.kts").exists())
            assertTrue(File(tempDir, "build.gradle.kts").exists())
            assertTrue(File(tempDir, "gradlew").exists())
            assertTrue(File(tempDir, "app/build.gradle.kts").exists())
            assertTrue(File(tempDir, "app/src/main/AndroidManifest.xml").exists())

            val appBuildContent = File(tempDir, "app/build.gradle.kts").readText()
            assertTrue(appBuildContent.contains("androidx.compose.material3:material3"))
            assertTrue(appBuildContent.contains("androidx.room:room-runtime"))
            assertTrue(appBuildContent.contains("compileSdk = 35"))

            val manifestContent = File(tempDir, "app/src/main/AndroidManifest.xml").readText()
            assertTrue(manifestContent.contains("android.permission.INTERNET"))
            assertTrue(manifestContent.contains(".MainActivity"))

            val packagePath = blueprint.packageName.replace('.', '/')
            val javaDir = File(tempDir, "app/src/main/java/$packagePath")
            assertTrue(File(javaDir, "MainActivity.kt").exists())
            assertTrue(File(javaDir, "data/local/AppDatabase.kt").exists())
            assertTrue(File(javaDir, "data/remote/ApiService.kt").exists())
            assertTrue(File(javaDir, "ui/theme/Theme.kt").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFlutterAppGenerator() {
        val tempDir = Files.createTempDirectory("flutter_factory_test").toFile()
        try {
            val blueprint = blueprintFromJson(
                SHOP_JSON, "Shopping store app", framework = AppFramework.FLUTTER
            )

            val files = FlutterAppGenerator.generateProject(tempDir, blueprint)
            assertTrue(files.isNotEmpty())

            val pubspec = File(tempDir, "pubspec.yaml")
            assertTrue(pubspec.exists())
            val pubspecContent = pubspec.readText()
            assertTrue(pubspecContent.contains("dio: ^5.4.3"))
            assertTrue(pubspecContent.contains("provider: ^6.1.2"))

            val mainDart = File(tempDir, "lib/main.dart")
            assertTrue(mainDart.exists())
            assertTrue(mainDart.readText().contains("runApp(const PocketApp());"))

            assertTrue(File(tempDir, "lib/services/api_service.dart").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testReactNativeAppGenerator() {
        val tempDir = Files.createTempDirectory("rn_factory_test").toFile()
        try {
            val blueprint = blueprintFromJson(
                CHAT_JSON, "Chat messenger", framework = AppFramework.REACT_NATIVE
            )

            val files = ReactNativeAppGenerator.generateProject(tempDir, blueprint)
            assertTrue(files.isNotEmpty())

            val packageJson = File(tempDir, "package.json")
            assertTrue(packageJson.exists())
            val pkgContent = packageJson.readText()
            assertTrue(pkgContent.contains("\"react-native\""))
            assertTrue(pkgContent.contains("\"axios\""))

            val appTsx = File(tempDir, "App.tsx")
            assertTrue(appTsx.exists())
            assertTrue(appTsx.readText().contains("export default function App()"))

            assertTrue(File(tempDir, "src/services/api.ts").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testBackendServiceGenerator() {
        val nodeDir = Files.createTempDirectory("node_backend_test").toFile()
        val pythonDir = Files.createTempDirectory("python_backend_test").toFile()
        try {
            val blueprint = blueprintFromJson(
                NOTES_JSON, "Note taking and knowledge vault app",
                framework = AppFramework.FULLSTACK_BACKEND
            )

            val nodeFiles = BackendServiceGenerator.generateProject(
                nodeDir,
                blueprint,
                BackendServiceGenerator.BackendFlavor.NODE_EXPRESS
            )
            assertTrue(nodeFiles.any { it.name == "server.js" })
            val serverJsContent = File(nodeDir, "server.js").readText()
            assertTrue(serverJsContent.contains("express()"))
            assertTrue(serverJsContent.contains("better-sqlite3"))
            assertTrue(serverJsContent.contains("CREATE TABLE IF NOT EXISTS"))
            assertTrue(serverJsContent.contains("app.get('/api/notes'"))

            val pyFiles = BackendServiceGenerator.generateProject(
                pythonDir,
                blueprint,
                BackendServiceGenerator.BackendFlavor.FASTAPI_PYTHON
            )
            assertTrue(pyFiles.any { it.name == "main.py" })
            assertTrue(File(pythonDir, "requirements.txt").exists())
            assertTrue(File(pythonDir, "main.py").readText().contains("FastAPI("))
        } finally {
            nodeDir.deleteRecursively()
            pythonDir.deleteRecursively()
        }
    }

    @Test
    fun testDatabaseSetupEngine() {
        val tempDir = Files.createTempDirectory("db_setup_test").toFile()
        try {
            val blueprint = blueprintFromJson(FINANCE_JSON, "Personal finance expense budget app")
            val files = DatabaseSetupEngine.generateDatabaseBundle(tempDir, blueprint)

            assertTrue("Expected database schema files", files.isNotEmpty())
            val schemaSql = File(tempDir, "database/migrations/001_initial_schema.sql")
            assertTrue(schemaSql.exists())
            val ddl = schemaSql.readText()
            assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS transactions"))
            assertTrue(ddl.contains("PRIMARY KEY"))

            val seedJson = File(tempDir, "database/seed_data.json")
            assertTrue(seedJson.exists())
            assertTrue(seedJson.readText().contains("\"transactions\": ["))

            val seedSql = File(tempDir, "database/migrations/002_seed_data.sql")
            assertTrue(seedSql.exists())
            assertTrue(seedSql.readText().contains("INSERT INTO transactions"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testApiIntegrationGenerator() {
        val tempDir = Files.createTempDirectory("api_integration_test").toFile()
        try {
            val androidBp = blueprintFromJson(FOOD_JSON, "E-Commerce store")
            val androidApiFiles = ApiIntegrationGenerator.generateApiClientBundle(tempDir, androidBp)
            assertTrue(androidApiFiles.any { it.name == "ApiClient.kt" })
            assertTrue(androidApiFiles.any { it.name == "NetworkResult.kt" })

            val flutterBp = blueprintFromJson(
                CHAT_JSON, "Chat messenger", framework = AppFramework.FLUTTER
            )
            val flutterApiFiles = ApiIntegrationGenerator.generateApiClientBundle(tempDir, flutterBp)
            assertTrue(flutterApiFiles.any { it.name == "api_client.dart" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testGitHubActionsWorkflowGeneration() {
        val tempDir = Files.createTempDirectory("ci_workflow_test").toFile()
        try {
            val blueprint = blueprintFromJson(
                FOOD_JSON, "Modern app", buildMode = BuildMode.GITHUB_ACTIONS
            )

            val workflowFile = AppBuildOrchestrator.generateGitHubActionsWorkflow(
                tempDir,
                blueprint,
                BuildArtifactType.APK_DEBUG
            )

            assertTrue(workflowFile.exists())
            assertEquals("android-build.yml", workflowFile.name)
            val yaml = workflowFile.readText()
            assertTrue(yaml.contains("actions/setup-java@v4"))
            assertTrue(yaml.contains("distribution: 'temurin'"))
            assertTrue(yaml.contains("java-version: '17'"))
            assertTrue(yaml.contains("./gradlew assembleDebug"))
            assertTrue(yaml.contains("actions/upload-artifact@v4"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOutputDiscoveryAndMetadata() {
        val tempDir = Files.createTempDirectory("output_discovery_test").toFile()
        try {
            val debugApkDir = File(tempDir, "app/build/outputs/apk/debug").apply { mkdirs() }
            val dummyApk = File(debugApkDir, "app-debug.apk")
            dummyApk.writeText("DUMMY_APK_PAYLOAD_FOR_TESTING")

            val discovered = AppBuildOrchestrator.discoverOutputFile(tempDir, BuildArtifactType.APK_DEBUG)
            assertNotNull(discovered)
            assertEquals("app-debug.apk", discovered?.name)

            val meta = AppBuildOrchestrator.extractMetadata(discovered!!, BuildArtifactType.APK_DEBUG)
            assertEquals(BuildArtifactType.APK_DEBUG, meta.artifactType)
            assertTrue(meta.sizeBytes > 0)
            assertNotEquals("unknown", meta.sha256Hex)
            assertEquals(64, meta.sha256Hex.length)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSigningAndVersionManager() {
        val tempDir = Files.createTempDirectory("signing_version_test").toFile()
        try {
            val keystoreConfig = SigningAndVersionManager.createKeystore(
                projectDir = tempDir,
                keyAlias = "testAlias",
                storePassword = "password123",
                keyPassword = "password123"
            )
            assertTrue(keystoreConfig.keystoreFile.exists())
            assertTrue(File(tempDir, "keystore.properties").exists())
            val propsContent = File(tempDir, "keystore.properties").readText()
            assertTrue(propsContent.contains("keyAlias=testAlias"))
            // storeFile is project-relative so guest Gradle builds resolve it.
            assertTrue(propsContent.contains("storeFile=release.keystore"))

            val appDir = File(tempDir, "app").apply { mkdirs() }
            val gradleFile = File(appDir, "build.gradle.kts")
            gradleFile.writeText("""
                plugins { id("com.android.application") }
                android {
                    compileSdk = 35
                    buildTypes {
                        release {
                            isMinifyEnabled = false
                        }
                    }
                }
            """.trimIndent())

            val injected = SigningAndVersionManager.injectSigningConfig(gradleFile)
            assertTrue(injected)
            val updatedGradle = gradleFile.readText()
            assertTrue(updatedGradle.contains("signingConfigs"))
            assertTrue(updatedGradle.contains("keystore.properties"))

            val gradleWithVersion = File(appDir, "build.gradle.kts")
            gradleWithVersion.writeText("""
                android {
                    defaultConfig {
                        versionCode = 1
                        versionName = "1.0.0"
                    }
                }
            """.trimIndent())

            val (code1, name1) = SigningAndVersionManager.bumpVersion(tempDir, VersionBumpType.PATCH)
            assertEquals(2, code1)
            assertEquals("1.0.1", name1)

            val (code2, name2) = SigningAndVersionManager.bumpVersion(tempDir, VersionBumpType.MINOR)
            assertEquals(3, code2)
            assertEquals("1.1.0", name2)

            val (code3, name3) = SigningAndVersionManager.bumpVersion(tempDir, VersionBumpType.MAJOR)
            assertEquals(4, code3)
            assertEquals("2.0.0", name3)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPlayStorePreparationEngine() {
        val tempDir = Files.createTempDirectory("play_store_test").toFile()
        try {
            val blueprint = blueprintFromJson(FOOD_JSON, "Food delivery app named BiteDash")
            val bundle = PlayStorePreparationEngine.prepareListingBundle(tempDir, blueprint)

            assertTrue(bundle.titleFile.exists())
            assertTrue(bundle.titleFile.readText().length <= 30)

            assertTrue(bundle.shortDescFile.exists())
            assertTrue(bundle.shortDescFile.readText().length <= 80)

            assertTrue(bundle.fullDescFile.exists())
            assertTrue(bundle.fullDescFile.readText().length <= 4000)

            assertTrue(bundle.privacyPolicyFile.exists())
            val privacyPolicy = bundle.privacyPolicyFile.readText()
            assertTrue(privacyPolicy.contains("Privacy Policy for BiteDash"))
            assertTrue(privacyPolicy.contains("Contact Us"))
            // No fabricated contact details leak into the policy.
            assertFalse(privacyPolicy.contains("support@bitedash.app"))
            assertTrue(privacyPolicy.contains("[TODO: add support email]"))

            assertTrue(bundle.complianceReportFile.exists())
            val report = bundle.complianceReportFile.readText()
            assertTrue(report.contains("Target SDK 34+"))
            assertTrue(report.contains("PASS"))

            // The synthesized blueprint leaves contact/privacy blank, so the
            // audit must FAIL those items — never rubber-stamp them.
            val auditItems = PlayStorePreparationEngine.runComplianceAudit(blueprint)
            assertFalse(auditItems.first { it.name == "Contact email present" }.passed)
            assertFalse(auditItems.first { it.name == "Privacy policy URL set" }.passed)

            // With the developer's real details supplied, everything passes.
            val complete = blueprint.copy(
                playStoreMetadata = blueprint.playStoreMetadata.copy(
                    contactEmail = "support@bitedash.example",
                    privacyPolicyUrl = "https://bitedash.example/privacy"
                )
            )
            assertTrue(PlayStorePreparationEngine.runComplianceAudit(complete).all { it.passed })
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
