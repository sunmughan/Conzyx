package com.codeair.mhe.ui.coordinators

import com.codeair.mhe.factory.blueprint.AppFactoryBlueprintEngine
import com.codeair.mhe.factory.blueprint.BlueprintModel
import com.codeair.mhe.factory.blueprint.BlueprintModelUnavailableException
import com.codeair.mhe.factory.blueprint.BlueprintSynthesizer
import com.codeair.mhe.factory.blueprint.ChatProviderBlueprintModel
import com.codeair.mhe.factory.build.AppBuildOrchestrator
import com.codeair.mhe.factory.build.GuestBuildSessionFactory
import com.codeair.mhe.factory.build.GuestCommandRunner
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
import com.codeair.mhe.model.Project
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App Factory domain coordinator.
 *
 * Explicit owner of the app-factory state machine:
 * - Blueprint synthesis is genuinely agentic: [BlueprintSynthesizer] asks
 *   the app's chat AI provider stack to design the app from the user's
 *   prompt. With no provider configured it refuses honestly — there is no
 *   template fallback.
 * - Scaffolding generates the project from that blueprint.
 * - Builds run Gradle inside the Conzyx runtime via a
 *   [GuestBuildSessionFactory] (the app process itself has no toolchain),
 *   and keystore creation runs keytool in the same guest.
 * Every failure surfaces as an honest message, never fake success.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class AppFactoryCoordinator(
    private val host: CoordinatorHost,
    private val workspaceRootFor: (Project) -> File,
    private val refreshProjectFiles: () -> Unit,
    private val buildSessionFactory: GuestBuildSessionFactory? = null,
    private val blueprintModelProvider: () -> BlueprintModel = {
        ChatProviderBlueprintModel(host.appContext)
    },
) {
    fun generateAppFactoryBlueprint(
        prompt: String,
        framework: AppFramework = AppFramework.ANDROID_COMPOSE,
        buildMode: BuildMode = BuildMode.ON_DEVICE,
        archetype: AppFactoryBlueprintEngine.Archetype? = null,
    ) {
        host.scope.launch {
            val model = blueprintModelProvider()
            if (model is ChatProviderBlueprintModel && model.describeRoute() == null) {
                host.updateState {
                    it.copy(
                        appFactoryGeneratingBlueprint = false,
                        appFactoryMessage = ChatProviderBlueprintModel.NOT_CONFIGURED_MESSAGE
                    )
                }
                return@launch
            }
            host.updateState {
                it.copy(
                    appFactoryGeneratingBlueprint = true,
                    appFactoryMessage = "Designing blueprint with ${model.label} from your prompt..."
                )
            }
            try {
                val blueprint = BlueprintSynthesizer(model).synthesize(
                    prompt = prompt,
                    framework = framework,
                    buildMode = buildMode,
                    archetypeHint = archetype,
                )
                host.updateState {
                    it.copy(
                        appFactoryBlueprint = blueprint,
                        appFactoryGeneratingBlueprint = false,
                        appFactoryMessage = "Blueprint '${blueprint.name}' designed by ${model.label} from your prompt."
                    )
                }
            } catch (e: BlueprintModelUnavailableException) {
                host.updateState {
                    it.copy(
                        appFactoryGeneratingBlueprint = false,
                        appFactoryMessage = e.message ?: "No AI provider is connected."
                    )
                }
            } catch (e: Exception) {
                host.updateState {
                    it.copy(
                        appFactoryGeneratingBlueprint = false,
                        appFactoryMessage = "Blueprint synthesis failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun scaffoldAppFactoryProject(
        targetDirectory: File? = null,
        blueprint: AppBlueprint? = host.state().appFactoryBlueprint
    ) {
        val bp = blueprint ?: return
        val projectDir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: File(host.appContext.filesDir, "projects/${bp.name.lowercase().replace(" ", "_")}")

        host.scope.launch {
            host.updateState { it.copy(appFactoryScaffolding = true, appFactoryMessage = "Scaffolding ${bp.framework.displayName} files...") }
            try {
                val files = mutableListOf<File>()
                when (bp.framework) {
                    AppFramework.ANDROID_COMPOSE -> {
                        files += AndroidComposeGenerator.generateProject(projectDir, bp)
                    }
                    AppFramework.FLUTTER -> {
                        files += FlutterAppGenerator.generateProject(projectDir, bp)
                    }
                    AppFramework.REACT_NATIVE -> {
                        files += ReactNativeAppGenerator.generateProject(projectDir, bp)
                    }
                    AppFramework.FULLSTACK_BACKEND -> {
                        files += BackendServiceGenerator.generateProject(projectDir, bp)
                    }
                }
                files += DatabaseSetupEngine.generateDatabaseBundle(projectDir, bp)
                files += ApiIntegrationGenerator.generateApiClientBundle(projectDir, bp)

                host.updateState {
                    it.copy(
                        appFactoryScaffolding = false,
                        appFactoryScaffoldFiles = files,
                        appFactoryMessage = "Scaffolded ${files.size} project files in ${projectDir.name}."
                    )
                }
                refreshProjectFiles()
            } catch (e: Exception) {
                host.updateState {
                    it.copy(
                        appFactoryScaffolding = false,
                        appFactoryMessage = "Scaffolding failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun buildAppFactoryApp(
        artifactType: BuildArtifactType = BuildArtifactType.APK_DEBUG,
        buildMode: BuildMode = BuildMode.ON_DEVICE,
        targetDirectory: File? = null
    ) {
        val bp = host.state().appFactoryBlueprint ?: return
        val projectDir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: File(host.appContext.filesDir, "projects/${bp.name.lowercase().replace(" ", "_")}")

        host.scope.launch {
            host.updateState {
                it.copy(
                    appFactoryBuilding = true,
                    appFactoryLogs = listOf("Starting build: ${artifactType.displayName} via ${buildMode.displayName}..."),
                    appFactoryMessage = "Building ${artifactType.displayName}..."
                )
            }
            try {
                // The project directory is bound into the runtime guest as
                // its workspace root, so the project lives at /workspace
                // inside the guest in both the bound-project and the
                // standalone cases.
                val session = if (buildMode == BuildMode.ON_DEVICE) {
                    val factory = buildSessionFactory
                        ?: throw IllegalStateException(
                            "The on-device build session is not wired in this build of the app."
                        )
                    factory.create(hostWorkspace = projectDir, guestWorkspacePath = GUEST_WORKSPACE)
                } else {
                    null
                }
                val result = AppBuildOrchestrator.runBuild(
                    projectDir = projectDir,
                    blueprint = bp,
                    artifactType = artifactType,
                    buildMode = buildMode,
                    session = session,
                    guestProjectPath = GUEST_WORKSPACE
                )
                host.updateState {
                    it.copy(
                        appFactoryBuilding = false,
                        appFactoryBuildResult = result,
                        appFactoryLogs = result.logs,
                        appFactoryMessage = if (result.success) {
                            "Build completed — ${result.outputFile?.name} is ready to install."
                        } else {
                            "Build failed: ${result.errorMessage}"
                        }
                    )
                }
            } catch (e: Exception) {
                host.updateState {
                    it.copy(
                        appFactoryBuilding = false,
                        appFactoryMessage = "Build error: ${e.message}",
                        appFactoryLogs = it.appFactoryLogs + "Error: ${e.message}"
                    )
                }
            }
        }
    }

    fun installBuiltApk(apkFile: File) {
        try {
            AppBuildOrchestrator.installApk(host.appContext, apkFile)
            host.updateState { it.copy(appFactoryMessage = "Launched package installer for ${apkFile.name}") }
        } catch (e: Exception) {
            host.updateState { it.copy(appFactoryMessage = "Installation error: ${e.message}") }
        }
    }

    fun generateReleaseKeystore(
        targetDirectory: File? = null,
        alias: String = "releaseKey",
        pass: String
    ) {
        val bp = host.state().appFactoryBlueprint
        val projectDir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: File(host.appContext.filesDir, "projects/${bp?.name?.lowercase()?.replace(" ", "_") ?: "app"}")

        host.scope.launch {
            try {
                // keytool exists only inside the runtime guest (the JDK
                // lives there); run it through a guest session.
                val factory = buildSessionFactory
                    ?: throw IllegalStateException(
                        "Keystore creation needs the Conzyx runtime session, which is not wired in this build of the app."
                    )
                val session = factory.create(hostWorkspace = projectDir, guestWorkspacePath = GUEST_WORKSPACE)
                val config = withContext(Dispatchers.IO) {
                    SigningAndVersionManager.createKeystore(
                        projectDir = projectDir,
                        keyAlias = alias,
                        storePassword = pass,
                        keyPassword = pass,
                        commandRunner = GuestCommandRunner(session),
                        keystoreCommandPath = "$GUEST_WORKSPACE/release.keystore"
                    )
                }
                host.updateState {
                    it.copy(
                        appFactoryKeystoreConfig = config,
                        appFactoryMessage = "2048-bit Keystore generated: ${config.keystoreFile.name}"
                    )
                }
            } catch (e: Exception) {
                host.updateState { it.copy(appFactoryMessage = "Keystore generation failed: ${e.message}") }
            }
        }
    }

    fun bumpAppVersion(
        targetDirectory: File? = null,
        bumpType: VersionBumpType = VersionBumpType.PATCH
    ) {
        val bp = host.state().appFactoryBlueprint
        val projectDir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: File(host.appContext.filesDir, "projects/${bp?.name?.lowercase()?.replace(" ", "_") ?: "app"}")

        val (newCode, newName) = SigningAndVersionManager.bumpVersion(projectDir, bumpType)
        host.updateState { state ->
            val updatedBp = state.appFactoryBlueprint?.let {
                it.copy(buildConfig = it.buildConfig.copy(versionCode = newCode, versionName = newName))
            }
            state.copy(
                appFactoryBlueprint = updatedBp,
                appFactoryMessage = "Bumped version to $newName (code $newCode)"
            )
        }
    }

    fun preparePlayStoreListing(
        targetDirectory: File? = null,
        blueprint: AppBlueprint? = host.state().appFactoryBlueprint
    ) {
        val bp = blueprint ?: return
        val projectDir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: File(host.appContext.filesDir, "projects/${bp.name.lowercase().replace(" ", "_")}")

        try {
            val bundle = PlayStorePreparationEngine.prepareListingBundle(projectDir, bp)
            host.updateState {
                it.copy(
                    appFactoryStoreBundle = bundle,
                    appFactoryMessage = "Play Store listing metadata bundle exported to ${bundle.listingDir.name}"
                )
            }
        } catch (e: Exception) {
            host.updateState { it.copy(appFactoryMessage = "Play Store preparation failed: ${e.message}") }
        }
    }

    private companion object {
        /** Guest path the project directory is bound to inside the runtime. */
        const val GUEST_WORKSPACE = "/workspace"
    }
}
