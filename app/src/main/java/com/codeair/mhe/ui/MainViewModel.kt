package com.codeair.mhe.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import android.os.Build
import android.system.Os
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.codeair.mhe.BuildConfig
import com.codeair.mhe.AppCrashLogger
import com.codeair.mhe.data.ApiKeyVault
import com.codeair.mhe.data.ApiKeyInfo
import com.codeair.mhe.data.AppPreferences
import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.core.result.OperationStatus
import com.codeair.mhe.model.ActivityItem
import com.codeair.mhe.model.AgentKind
import com.codeair.mhe.model.ChangeItem
import com.codeair.mhe.model.ChatMessage
import com.codeair.mhe.model.ChatAttachment
import com.codeair.mhe.model.ExecutionMode
import com.codeair.mhe.model.ActiveRoadmap
import com.codeair.mhe.model.RoadmapComment
import com.codeair.mhe.model.RoadmapStep
import com.codeair.mhe.model.ScratchpadItem
import com.codeair.mhe.model.StepStatus
import com.codeair.mhe.model.TaskStatus
import com.codeair.mhe.model.DevStack
import com.codeair.mhe.model.Project
import com.codeair.mhe.model.ProjectKind
import com.codeair.mhe.model.ProjectChat
import com.codeair.mhe.model.ProviderKind
import com.codeair.mhe.model.ProviderProfile
import com.codeair.mhe.model.RuntimeEvent
import com.codeair.mhe.model.ToolRequest
import com.codeair.mhe.model.WorkspaceEntry
import com.codeair.mhe.model.projectSlug
import com.codeair.mhe.model.generateQuickChatIdentity
import com.codeair.mhe.model.providerProtocolForAgent
import com.codeair.mhe.network.ConnectionValidation
import com.codeair.mhe.network.ModelDiscoveryResult
import com.codeair.mhe.network.ProviderApiClient
import com.codeair.mhe.network.GitHubRepository
import com.codeair.mhe.runtime.ClaudeRuntimeBridge
import com.codeair.mhe.runtime.CloudCodeRuntimeBridge
import com.codeair.mhe.runtime.DshRuntimeBridge
import com.codeair.mhe.runtime.AgentRegistry
import com.codeair.mhe.runtime.AgentUpdateInfo
import com.codeair.mhe.runtime.AntigravityAuthController
import com.codeair.mhe.runtime.AntigravityAuthState
import com.codeair.mhe.runtime.AntigravityAuthStatus
import com.codeair.mhe.runtime.AntigravityRuntimeBridge
import com.codeair.mhe.runtime.NativeSpawnProcess
import com.codeair.mhe.runtime.RuntimeInstallProgress
import com.codeair.mhe.runtime.RuntimeInstallEvent
import com.codeair.mhe.runtime.RuntimeInstaller
import com.codeair.mhe.runtime.RuntimeSetupController
import com.codeair.mhe.runtime.RuntimeSetupService
import com.codeair.mhe.runtime.readTailText
import com.codeair.mhe.runtime.truncateHeadAndTail
import com.codeair.mhe.runtime.supportsArm64Runtime
import com.codeair.mhe.runtime.RuntimeFailureClassifier
import com.codeair.mhe.runtime.WorkspaceCheckpoints
import com.codeair.mhe.runtime.ProjectIndex
import com.codeair.mhe.runtime.ContextEngine
import com.codeair.mhe.runtime.RuntimeRetryPolicy
import com.codeair.mhe.runtime.SkillInfo
import com.codeair.mhe.runtime.SkillManager
import com.codeair.mhe.runtime.SkillSearchResult
import com.codeair.mhe.runtime.AndroidAppInstaller
import com.codeair.mhe.runtime.NotificationCoordinator
import com.codeair.mhe.runtime.RuntimeExecutionService
import com.codeair.mhe.runtime.formatDurationText
import com.codeair.mhe.MobileHarnessApplication
import com.codeair.mhe.update.AppUpdateInfo
import com.codeair.mhe.update.AppUpdater
import java.io.File
import java.io.RandomAccessFile
import java.net.UnknownHostException
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.codeair.mhe.ui.coordinators.isClaudeRuntimeMetadata
import com.codeair.mhe.ui.coordinators.guestRootFor
import org.json.JSONArray
import org.json.JSONObject

enum class StartupStage { CHECKING, SETUP_REQUIRED, INSTALLING, MODEL_SETUP, INITIALIZING, READY, ERROR }

enum class ApiPingStatus { IDLE, PINGING, OK, FAILED }
enum class AppUpdateStatus { AVAILABLE, PERMISSION_REQUIRED, DOWNLOADING, INSTALLING, ERROR }
enum class GitHubAuthStatus { DISCONNECTED, STARTING, AWAITING_USER, CONNECTED, ERROR }

data class TerminalOutputLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val command: String,
    val output: String,
    val exitCode: Int = 0,
)

data class TerminalSessionTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val isRunning: Boolean = false,
    val cwd: String = "/workspace",
    val lines: List<TerminalOutputLine> = emptyList(),
)

private val ANSI_TERMINAL_SEQUENCE = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")

internal fun sanitizeTerminalOutput(text: String): String = text
    .replace(ANSI_TERMINAL_SEQUENCE, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

private val ANTIGRAVITY_MODEL_EFFORT = Regex("^(.*)-(low|medium|high)$")

private fun antigravityEffortFromModel(model: String): String? =
    ANTIGRAVITY_MODEL_EFFORT.matchEntire(model)?.groupValues?.get(2)

private fun antigravityModelWithEffort(model: String, effort: String): String? {
    val match = ANTIGRAVITY_MODEL_EFFORT.matchEntire(model) ?: return null
    return "${match.groupValues[1]}-$effort"
}

private data class RuntimeRetryRequest(
    val runtime: com.codeair.mhe.runtime.RuntimeBridge,
    val project: Project,
    val prompt: String,
    val history: List<ChatMessage>,
    val provider: ProviderProfile,
)

private data class TranscriptWrite(
    val projectId: String,
    val chatId: String,
    val messages: List<ChatMessage>,
)

private data class ImportedZipProject(
    val project: Project,
    val sourceAttachment: ChatAttachment,
)

data class AppUiState(
    val startupStage: StartupStage = StartupStage.CHECKING,
    val startupProgress: Float = 0f,
    val startupMessage: String = "Checking this device…",
    val startupBytes: Pair<Long, Long>? = null,
    val startupLogs: List<String> = emptyList(),
    val startupIndeterminate: Boolean = false,
    val startupError: String? = null,
    val startupErrorIsOffline: Boolean = false,
    val showDetailedSetupProgress: Boolean = false,
    val onboardingComplete: Boolean = false,
    val backgroundSetupComplete: Boolean = false,
    val appTourCompleted: Boolean = false,
    val showAppTour: Boolean = false,
    val provider: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),
    val activeApiKeyName: String? = null,
    val themeMode: com.codeair.mhe.ui.theme.AppThemeMode = com.codeair.mhe.ui.theme.AppThemeMode.DARK,
    val apiPingStatus: ApiPingStatus = ApiPingStatus.IDLE,
    val apiPingMessage: String? = null,
    val projects: List<Project> = emptyList(),
    val projectImporting: Boolean = false,
    val projectImportMessage: String? = null,
    val gitCloneRunning: Boolean = false,
    val gitCloneMessage: String? = null,
    val githubAuthStatus: GitHubAuthStatus = GitHubAuthStatus.DISCONNECTED,
    val githubLogin: String? = null,
    val githubUserCode: String? = null,
    val githubVerificationUri: String? = null,
    val githubMessage: String? = null,
    val githubRepositories: List<GitHubRepository> = emptyList(),
    val githubRepositoriesLoading: Boolean = false,
    val activeProject: Project? = null,
    val workspaceVisible: Boolean = false,
    val readOnlyProject: Project? = null,
    val readOnlyProjectChats: List<ProjectChat> = emptyList(),
    val readOnlyChatId: String? = null,
    val readOnlyMessages: List<ChatMessage> = emptyList(),
    val projectChats: List<ProjectChat> = emptyList(),
    val activeChatId: String? = null,
    val workspaceFiles: List<WorkspaceEntry> = emptyList(),
    val androidProjectDetected: Boolean = false,
    val filesLoading: Boolean = false,
    val openedFilePath: String? = null,
    val openedFileContent: String? = null,
    val openEditorTabs: List<String> = emptyList(),
    val fileContentLoading: Boolean = false,
    // Milestone 2 — persistent editor buffers: restored cursor/scroll per file,
    // split-editor state, and the set of tabs with unsaved changes.
    val editorViewStates: Map<String, com.codeair.mhe.editor.EditorViewState> = emptyMap(),
    val editorSplitState: com.codeair.mhe.editor.EditorSplitState =
        com.codeair.mhe.editor.EditorSplitState(),
    val editorDirtyPaths: Set<String> = emptySet(),
    val messages: List<ChatMessage> = listOf(
        ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."),
    ),
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val pendingApproval: ToolRequest? = null,
    val changes: List<ChangeItem> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val liveProcess: List<ActivityItem> = emptyList(),
    val liveThinking: Boolean = false,
    val activeThinkingBlockId: Long? = null,
    val taskStartedAtMillis: Long? = null,
    val taskFinishedAtMillis: Long? = null,
    val workSegmentStartedAtMillis: Long? = null,
    val currentTaskRequest: String? = null,
    val previewReady: Boolean = false,
    val previewUrl: String? = null,
    val isRunning: Boolean = false,
    val activeSessionId: String? = null,
    val interruptedSession: com.codeair.mhe.session.PersistedAgentSession? = null,
    val runtimeRecoveryStatus: String? = null,
    val runtimeRecoveryAttempt: Int = 0,
    val runtimeRecoveryCanResume: Boolean = false,
    val skills: List<SkillInfo> = emptyList(),
    val skillSearchResults: List<SkillSearchResult> = emptyList(),
    val skillsBusy: Boolean = false,
    val skillsMessage: String? = null,
    val skillUpdates: Set<String> = emptySet(),
    val toastMessage: String? = null,
    val projectTerminalLines: List<TerminalOutputLine> = emptyList(),
    val projectTerminalLiveOutput: String = "",
    val projectTerminalRunning: Boolean = false,
    val projectTerminalCwd: String = "/workspace",
    val projectTerminalCommand: String? = null,
    val projectTerminalDraft: String? = null,
    val pendingTerminalCommand: String? = null,
    val activeTerminalSessionId: String = "term-1",
    val terminalSessions: List<TerminalSessionTab> = listOf(TerminalSessionTab(id = "term-1", title = "Terminal 1")),
    val suggestedProjectRoot: String? = null,
    val selectedDevStacks: Set<DevStack> = emptySet(),
    val installedDevStacks: Set<DevStack> = emptySet(),
    val autoUpdateToolsAndSkills: Boolean = true,
    val autoUpdatingToolsOrSkills: Boolean = false,
    val toolsAutoUpdateMessage: String? = null,
    val devStackInstalling: DevStack? = null,
    val devStackRemoving: Boolean = false,
    val devStackMessage: String? = null,
    val devStackProgress: Float = 0f,
    val devStackBytes: Pair<Long, Long>? = null,
    val devStackBytesPerSecond: Long? = null,
    val agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val primaryAgentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val installedAgentVersions: Map<AgentKind, String> = emptyMap(),
    val agentInstalling: AgentKind? = null,
    val agentMessage: String? = null,
    val agentProgress: Float = 0f,
    val agentDownloadedBytes: Long? = null,
    val agentTotalBytes: Long? = null,
    val agentBytesPerSecond: Long? = null,
    val agentUpdates: Map<AgentKind, AgentUpdateInfo> = emptyMap(),
    val agentUpdatesChecking: Boolean = false,
    val agentUpdating: AgentKind? = null,
    val agentUpdateMessage: String? = null,
    val agentUpdateProgress: Float = 0f,
    val agentUpdateDownloadedBytes: Long? = null,
    val agentUpdateTotalBytes: Long? = null,
    val agentUpdateBytesPerSecond: Long? = null,
    val antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    val antigravityModel: String = "",
    val antigravityEffort: String = "high",
    val antigravityModels: List<String> = emptyList(),
    val antigravityModelsLoading: Boolean = false,
    val androidBuildRunning: Boolean = false,
    val androidBuildMessage: String? = null,
    val appUpdate: AppUpdateInfo? = null,
    val appUpdateStatus: AppUpdateStatus? = null,
    val appUpdateDownloadedBytes: Long = 0L,
    val appUpdateTotalBytes: Long = -1L,
    val appUpdateError: String? = null,
    val executionMode: ExecutionMode = ExecutionMode.UNIFIED,
    val activeRoadmap: ActiveRoadmap? = null,
    val scratchpadItems: List<ScratchpadItem> = emptyList(),
    val scratchpadExpanded: Boolean = false,
    val orchestratorSnapshot: com.codeair.mhe.session.OrchestratorSnapshot? = null,
    val emergencyKillSwitchActive: Boolean = false,
    val allowShellExec: Boolean = true,
    val allowNetworkAccess: Boolean = true,
    val allowFileDeletion: Boolean = false,
    val allowGitPush: Boolean = false,
    val allowApkInstall: Boolean = false,
    val allowExternalServices: Boolean = false,
    val allowAccessEnv: Boolean = false,
    val securityAuditLogs: List<com.codeair.mhe.security.SecurityAuditEntry> = emptyList(),
    val mcpServers: List<com.codeair.mhe.mcp.McpServerInfo> = emptyList(),
    val mcpCatalog: List<com.codeair.mhe.mcp.McpCatalogTemplate> = emptyList(),
    val mcpTools: List<com.codeair.mhe.runtime.McpToolDefinition> = emptyList(),
    val mcpAuditLogs: List<com.codeair.mhe.mcp.McpAuditEntry> = emptyList(),
    val mcpSelectedServerId: String? = null,
    val mcpBusy: Boolean = false,
    val mcpMessage: String? = null,
    val appFactoryBlueprint: com.codeair.mhe.factory.model.AppBlueprint? = null,
    val appFactoryGeneratingBlueprint: Boolean = false,
    val appFactoryScaffolding: Boolean = false,
    val appFactoryScaffoldFiles: List<java.io.File> = emptyList(),
    val appFactoryBuilding: Boolean = false,
    val appFactoryBuildResult: com.codeair.mhe.factory.model.BuildResult? = null,
    val appFactoryLogs: List<String> = emptyList(),
    val appFactoryKeystoreConfig: com.codeair.mhe.factory.model.KeystoreConfig? = null,
    val appFactoryStoreBundle: com.codeair.mhe.factory.model.StoreListingBundle? = null,
    val appFactoryMessage: String? = null,
    val gitStatusInfo: com.codeair.mhe.git.model.GitStatusInfo = com.codeair.mhe.git.model.GitStatusInfo(),
    val gitBranches: List<com.codeair.mhe.git.model.GitBranchInfo> = emptyList(),
    val gitCommits: List<com.codeair.mhe.git.model.GitCommitInfo> = emptyList(),
    val gitRawDiff: String = "",
    val gitWorkflowState: com.codeair.mhe.git.workflow.WorkflowExecutionState = com.codeair.mhe.git.workflow.WorkflowExecutionState(),
    val gitWorkflowRuns: List<com.codeair.mhe.network.GitHubWorkflowRun> = emptyList(),
    val gitReleases: List<com.codeair.mhe.network.GitHubRelease> = emptyList(),
    val gitIssues: List<com.codeair.mhe.network.GitHubIssue> = emptyList(),
    val gitPullRequests: List<com.codeair.mhe.network.GitHubPullRequest> = emptyList(),
    val gitConnectedRepo: String? = null,
    val teamSessionState: com.codeair.mhe.agent.team.model.TeamSessionState = com.codeair.mhe.agent.team.model.TeamSessionState(),
    val cloudHybridState: com.codeair.mhe.cloud.model.CloudHybridState = com.codeair.mhe.cloud.model.CloudHybridState(),
    val collaborationSessionState: com.codeair.mhe.collab.model.CollaborationSessionState = com.codeair.mhe.collab.model.CollaborationSessionState(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = ApiKeyVault(application)
    private val preferences = AppPreferences(application)
    private val claudeRuntime = ClaudeRuntimeBridge(application) { profile -> vault.get(profile.kind.name) }
    private val dshRuntime = DshRuntimeBridge(application) { profile -> vault.get(profile.kind.name) }
    private val installer = RuntimeInstaller(application)
    private val antigravityRuntime = AntigravityRuntimeBridge(
        application,
        model = { _state.value.antigravityModel },
        effort = { _state.value.antigravityEffort },
        conversationId = { projectId ->
            _state.value.activeChatId?.let { preferences.loadAgentConversation(AgentKind.ANTIGRAVITY, projectId, it) }
        },
        saveConversationId = { projectId, id ->
            _state.value.activeChatId?.let { preferences.saveAgentConversation(AgentKind.ANTIGRAVITY, projectId, it, id) }
        },
    )
    private val cloudCodeRuntime = CloudCodeRuntimeBridge(application) { profile -> vault.get(profile.kind.name) }
    private val agentRegistry = AgentRegistry.builtIns(claudeRuntime, dshRuntime, antigravityRuntime, cloudCodeRuntime)
    private fun activeRuntime(): com.codeair.mhe.runtime.RuntimeBridge = agentRegistry.require(_state.value.agentKind).runtime
    private val providerApi = ProviderApiClient()
    private val skillManager = SkillManager(application)
    private val editorCheckpoints = WorkspaceCheckpoints(application.filesDir)
    val sessionManager = com.codeair.mhe.session.AgentSessionManager(application)
    val checkpointManager = com.codeair.mhe.workspace.WorkspaceCheckpointManager(application.filesDir)
    val runtimeHealthController = com.codeair.mhe.runtime.RuntimeHealthController(application)
    val agentSessionController = com.codeair.mhe.session.AgentSessionController(application, sessionManager)
    val terminalSessionController = com.codeair.mhe.terminal.TerminalSessionController()
    val agentChatController = com.codeair.mhe.agent.AgentChatController()
    val gitDomainController = com.codeair.mhe.workspace.GitDomainController { args, workingDir, guestPath ->
        if (!installer.isInstalled()) return@GitDomainController 1 to "Linux runtime is not ready."
        val installed = installer.installedRuntime()
        val outputFile = File(getApplication<Application>().cacheDir, "git-op-${System.nanoTime()}.log")
        try {
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workingDir,
                mapOf("GIT_TERMINAL_PROMPT" to "0"),
                listOf("git") + args,
                guestWorkspacePath = guestPath,
                outputFile = outputFile,
            )
            val exitCode = process.waitFor()
            val out = outputFile.readTailText(MAX_PROCESS_OUTPUT_BYTES).trim()
            exitCode to out
        } finally {
            outputFile.delete()
        }
    }
    val appErrorCoordinator = com.codeair.mhe.runtime.AppErrorCoordinator()
    val projectWorkspaceController = com.codeair.mhe.workspace.ProjectWorkspaceController(application)
    val fileOperationsController = com.codeair.mhe.workspace.FileOperationsController()
    val projectMemoryStore = com.codeair.mhe.runtime.ProjectMemoryStore(application.filesDir)
    val agentOrchestrator = com.codeair.mhe.session.AgentOrchestrator(checkpointManager, projectMemoryStore)
    val permissionPolicyEngine = com.codeair.mhe.security.PermissionPolicyEngine(application)
    val mcpRegistry = com.codeair.mhe.mcp.McpRegistry(application)
    val mcpAuditLogger = com.codeair.mhe.mcp.McpAuditLogger(application)
    val mcpPermissionManager = com.codeair.mhe.mcp.McpPermissionManager(File(application.filesDir, "mcp"), permissionPolicyEngine)
    val mcpClientRunner = com.codeair.mhe.runtime.McpClientRunner()
    val gitHubClient = com.codeair.mhe.network.GitHubClient()
    val gitWorkflowEngine = com.codeair.mhe.git.workflow.GitHubWorkflowEngine(gitDomainController, gitHubClient)
    val marketplaceInstallManager = com.codeair.mhe.marketplace.MarketplaceInstallManager(application)
    val teamOrchestrator = com.codeair.mhe.agent.team.AgentTeamOrchestrator()
    val cloudHybridCoordinator = com.codeair.mhe.cloud.CloudHybridCoordinator()
    val workspaceCollaborationManager = com.codeair.mhe.collab.WorkspaceCollaborationManager()
    private val projectIndex = ProjectIndex(application.filesDir)
    private val contextEngine = ContextEngine(projectIndex, projectMemoryStore)
    private val runtimeRetryPolicy = RuntimeRetryPolicy()
    private var runtimeRecoveryJob: Job? = null
    private var runtimeRecoveryAttempt = 0
    private var runtimeRecoveryStartedAtMillis: Long? = null
    private fun appUpdater(): AppUpdater = AppUpdater(
        context = getApplication(),
        manifestUrlOverride = preferences.updateManifestUrlOverride.ifBlank {
            if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else ""
        },
        authToken = preferences.updateAuthToken,
    )
    @Volatile private var lastOpenedAntigravityAuthUrl: String? = null
    private var activeRuntimeRequest: RuntimeRetryRequest? = null
    private var lastInterruptedRequest: RuntimeRetryRequest? = null
    private val failedApiKeyIds = mutableSetOf<String>()
    private val transcriptWrites = Channel<TranscriptWrite>(Channel.UNLIMITED)
    private val initialAgentKind = AgentKind.fromStored(preferences.agentKind)
    private val initialPrimaryAgentKind = preferences.primaryAgentKind
        .takeIf(String::isNotBlank)
        ?.let(AgentKind::fromStored)
        ?: initialAgentKind

    // Startup must never die before Compose can render. Android Keystore state can
    // become temporarily unavailable/corrupt after OS upgrades, restore operations,
    // or an interrupted app reinstall. Treat provider/secret hydration as recoverable
    // state and let the UI continue into setup instead of crashing in MainViewModel's
    // constructor during the system splash screen.
    private val initialProvider: ProviderProfile = runCatching {
        preferences.loadProvider(vault, initialAgentKind)
    }.getOrElse {
        ProviderProfile(
            kind = if (initialAgentKind == AgentKind.DEEPSEEK_HARNESS) {
                ProviderKind.DEEPSEEK
            } else {
                ProviderKind.ANTHROPIC
            },
            hasSecret = false,
        )
    }

    private val initialActiveApiKeyName: String? = runCatching {
        vault.list(initialProvider.kind.name).firstOrNull(ApiKeyInfo::isActive)?.name
    }.getOrNull()

    private val antigravityAuthController = AntigravityAuthController(
        application,
        preferences.antigravitySignedIn,
        preferences.antigravityAccountEmail,
    ) { signedIn, email ->
        preferences.antigravitySignedIn = signedIn
        preferences.antigravityAccountEmail = email.orEmpty()
        if (!signedIn) preferences.clearAgentConversations(AgentKind.ANTIGRAVITY)
    }
    private val isWarmReady = preferences.runtimeSetupComplete && preferences.onboardingComplete
    private val initialProjects = preferences.loadProjects()
    private val initialActiveProject = if (isWarmReady) {
        preferences.lastActiveProjectId?.let { lastId -> initialProjects.firstOrNull { it.id == lastId } }
    } else null
    private val initialChats = initialActiveProject?.let { proj ->
        preferences.loadProjectChats(proj.id).ifEmpty {
            listOf(ProjectChat(title = "Main chat"))
        }
    } ?: emptyList()
    private val initialActiveChatId = initialActiveProject?.let { proj ->
        val lastChatId = preferences.getLastActiveChatId(proj.id)
        (initialChats.firstOrNull { it.id == lastChatId } ?: initialChats.firstOrNull())?.id
    }
    private val initialMessages = (if (initialActiveProject != null && initialActiveChatId != null) {
        preferences.loadMessages(initialActiveProject.id, initialActiveChatId).ifEmpty {
            listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."))
        }
    } else {
        listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."))
    }).map { msg ->
        if (!msg.fromUser && msg.roadmap == null && msg.text.isNotBlank()) {
            val parsed = RoadmapParser.parseFromText(msg.text, ExecutionMode.fromStored(preferences.executionMode))
            if (parsed != null) msg.copy(roadmap = parsed) else msg
        } else {
            msg
        }
    }
    private val initialActiveRoadmap = initialMessages.mapNotNull { it.roadmap }.lastOrNull()
    private val initialScratchpad = deriveScratchpadItems(initialActiveRoadmap, emptyList(), false)

    private val _state = MutableStateFlow(
        AppUiState(
            startupStage = if (isWarmReady) StartupStage.READY else StartupStage.CHECKING,
            startupProgress = if (isWarmReady) 1f else 0f,
            onboardingComplete = preferences.onboardingComplete,
            backgroundSetupComplete = preferences.backgroundSetupComplete,
            appTourCompleted = preferences.appTourCompleted,
            showAppTour = isWarmReady && !preferences.appTourCompleted,
            agentKind = initialAgentKind,
            primaryAgentKind = initialPrimaryAgentKind,
            provider = initialProvider,
            activeApiKeyName = initialActiveApiKeyName,
            antigravityAuth = AntigravityAuthState(
                status = if (preferences.antigravitySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
                message = preferences.antigravityAccountEmail.takeIf(String::isNotBlank)?.let { "Connected as $it" },
                accountEmail = preferences.antigravityAccountEmail.takeIf(String::isNotBlank),
            ),
            antigravityModel = preferences.antigravityModel,
            antigravityEffort = preferences.antigravityEffort,
            themeMode = runCatching { com.codeair.mhe.ui.theme.AppThemeMode.valueOf(preferences.themeMode.uppercase()) }
                .getOrDefault(com.codeair.mhe.ui.theme.AppThemeMode.DARK),
            projects = initialProjects,
            activeProject = initialActiveProject,
            workspaceVisible = isWarmReady && initialActiveProject != null,
            projectChats = initialChats,
            activeChatId = initialActiveChatId,
            messages = initialMessages,
            activeRoadmap = initialActiveRoadmap,
            scratchpadItems = initialScratchpad,
            githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
            githubLogin = preferences.githubLogin.takeIf(String::isNotBlank),
            skills = skillManager.installed(),
            autoUpdateToolsAndSkills = preferences.autoUpdateToolsAndSkills,
            selectedDevStacks = preferences.selectedDevStacks.mapNotNull { name ->
                runCatching { DevStack.valueOf(name) }.getOrNull()
            }.toSet() + DevStack.WEB,
            executionMode = ExecutionMode.fromStored(preferences.executionMode),
        ),
    )

    // ---- Milestone 1: moved above init — the init block deletes stale terminal history. ----
    private val coordinatorHost = com.codeair.mhe.ui.coordinators.CoordinatorHost(
        appContext = getApplication(),
        scope = viewModelScope,
        state = { _state.value },
        updateState = { transform -> _state.update(transform) },
        showToast = { message -> showToast(message) },
    )

    private val terminalCoordinator =
        com.codeair.mhe.ui.coordinators.TerminalCoordinator(
            host = coordinatorHost,
            installer = installer,
            terminalSessionController = terminalSessionController,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            refreshProjectFiles = { refreshProjectFiles() },
        )
    // ---- Moved above init: refreshGitHubConnection() is launched from init via
    // ---- Dispatchers.Main.immediate, which runs synchronously before later properties initialize.
    private val gitHubAuthCoordinator =
        com.codeair.mhe.ui.coordinators.github.GitHubAuthCoordinator(
            host = coordinatorHost,
            gitHubClient = gitHubClient,
            installer = installer,
            preferences = preferences,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            openProject = { project -> openProject(project) },
        )
    init {
        AppCrashLogger.log("MainViewModel.init entered")
        // GitHub's official CLI owns its OAuth credential. Remove credentials from
        // the retired custom OAuth implementation and discover the real CLI status.
        runCatching { vault.remove(LEGACY_GITHUB_TOKEN_KEY) }
        viewModelScope.launch { refreshGitHubConnection() }
        viewModelScope.launch {
            gitWorkflowEngine.state.collect { wfState ->
                _state.update { it.copy(gitWorkflowState = wfState) }
            }
        }
        viewModelScope.launch {
            teamOrchestrator.sessionState.collect { teamState ->
                _state.update { it.copy(teamSessionState = teamState) }
            }
        }
        viewModelScope.launch {
            cloudHybridCoordinator.state.collect { chState ->
                _state.update { it.copy(cloudHybridState = chState) }
            }
        }
        viewModelScope.launch {
            workspaceCollaborationManager.sessionState.collect { collabState ->
                _state.update { it.copy(collaborationSessionState = collabState) }
            }
        }
        runCatching { RuntimeSetupController.restore(application) }
        val storageHealth = runtimeHealthController.checkStorageHealth()
        if (storageHealth is com.codeair.mhe.runtime.StorageHealthResult.Critical) {
            _state.update { it.copy(toastMessage = storageHealth.message) }
        }
        val rootfsIntegrity = runtimeHealthController.verifyRootfsIntegrity()
        if (rootfsIntegrity is com.codeair.mhe.runtime.RootfsIntegrityResult.Corrupted) {
            AppCrashLogger.log("Rootfs corruption detected: ${rootfsIntegrity.reason}")
            _state.update { it.copy(toastMessage = "Runtime warning: ${rootfsIntegrity.reason}") }
        }
        val interrupted = sessionManager.checkInterruptedSession()
        if (interrupted != null) {
            _state.update { it.copy(interruptedSession = interrupted) }
        }
        refreshPermissionPolicies()
        refreshMcpServers()
        loadMcpAuditLogs()
        viewModelScope.launch {
            agentOrchestrator.snapshot.collect { orchSnapshot ->
                _state.update { it.copy(orchestratorSnapshot = orchSnapshot) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runtimeHealthController.cleanupOrphanArtifacts()
            com.codeair.mhe.workspace.SafeFileOps.cleanStaleTempFiles(application.filesDir)
            for (write in transcriptWrites) {
                preferences.saveMessages(write.projectId, write.chatId, write.messages)
            }
        }
        viewModelScope.launch { dshRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { antigravityRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch {
            antigravityAuthController.state.collect { auth ->
                _state.update { it.copy(antigravityAuth = auth) }
                auth.authorizationUrl?.takeIf { it != lastOpenedAntigravityAuthUrl }?.let { url ->
                    lastOpenedAntigravityAuthUrl = url
                    runCatching {
                        getApplication<Application>().startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        _state.update { state -> state.copy(toastMessage = "Could not open the browser. Copy the sign-in URL instead.") }
                    }
                }
            }
        }
        if (antigravityAuthController.hasOfficialCredential() &&
            (!preferences.antigravitySignedIn || preferences.antigravityAccountEmail.isBlank())
        ) {
            viewModelScope.launch { antigravityAuthController.beginLogin() }
        }
        if (!preferences.legacySeededCredentialRemoved) {
            vault.remove(ProviderKind.CUSTOM.name)
            preferences.legacySeededCredentialRemoved = true
            _state.update { current ->
                if (current.provider.kind == ProviderKind.CUSTOM) {
                    current.copy(provider = current.provider.copy(hasSecret = false))
                } else current
            }
        }
        if (
            BuildConfig.TEST_OPENROUTER_API_KEY.isNotBlank() &&
            preferences.testProviderDefaultsVersion < TEST_PROVIDER_DEFAULTS_VERSION
        ) {
            val testProvider = ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = TEST_OPENROUTER_BASE_URL,
                model = TEST_OPENROUTER_MODEL,
                hasSecret = true,
            )
            vault.put(ProviderKind.CUSTOM.name, BuildConfig.TEST_OPENROUTER_API_KEY)
            preferences.saveProvider(testProvider, _state.value.agentKind)
            preferences.testProviderDefaultsVersion = TEST_PROVIDER_DEFAULTS_VERSION
            _state.update { it.copy(provider = testProvider) }
        }

        val loadedProjects = preferences.loadProjects()
        val cleanedProjects = loadedProjects.filter { project ->
            if (project.kind == ProjectKind.QUICK_PROJECT) {
                val chats = preferences.loadProjectChats(project.id)
                val userMessages = chats.sumOf { preferences.loadMessages(project.id, it.id).count { m -> m.fromUser } }
                val workspaceDir = File(application.filesDir, "workspaces/${project.id}")
                val userFiles = if (workspaceDir.isDirectory) {
                    workspaceDir.walkTopDown().filter { file ->
                        file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                    }.count()
                } else 0
                val keep = userMessages > 0 || userFiles > 0
                if (!keep) {
                    workspaceDir.deleteRecursively()
                    terminalCoordinator.deleteTerminalHistory(project.id)
                    preferences.deleteProjectChats(project.id)
                }
                keep
            } else true
        }
        if (cleanedProjects.size != loadedProjects.size) {
            preferences.saveProjects(cleanedProjects)
            _state.update { it.copy(projects = cleanedProjects) }
        }
        if (isWarmReady && initialActiveProject != null && cleanedProjects.any { it.id == initialActiveProject.id }) {
            configureBridgeRoots(initialActiveProject.id, initialActiveProject.rootPath)
            val terminal = loadProjectTerminal(initialActiveProject)
            _state.update {
                it.copy(
                    projectTerminalLines = terminal.lines,
                    projectTerminalCwd = terminal.cwd,
                )
            }
            refreshProjectFiles()
        }
    }

    val state: StateFlow<AppUiState> = _state.asStateFlow()

    // ---- Milestone 1: domain coordinators extracted from this ViewModel. ----
    // Each coordinator owns one domain's operations and state machine;
    // MainViewModel keeps a stable public API by delegating to them.
    private val marketplaceCoordinator =
        com.codeair.mhe.ui.coordinators.MarketplaceCoordinator(coordinatorHost, marketplaceInstallManager)
    private val teamCoordinator =
        com.codeair.mhe.ui.coordinators.TeamCoordinator(teamOrchestrator)
    private val cloudCoordinator =
        com.codeair.mhe.ui.coordinators.CloudCoordinator(cloudHybridCoordinator)
    private val collaborationCoordinator =
        com.codeair.mhe.ui.coordinators.CollaborationCoordinator(workspaceCollaborationManager)
    private val visualCoordinator =
        com.codeair.mhe.ui.coordinators.VisualCoordinator(
            host = coordinatorHost,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            sendPrompt = { prompt -> sendPrompt(prompt) },
        )
    private val securityCoordinator =
        com.codeair.mhe.ui.coordinators.SecurityCoordinator(
            host = coordinatorHost,
            permissionPolicyEngine = permissionPolicyEngine,
            stopTask = { stopTask() },
        )
    private val mcpCoordinator =
        com.codeair.mhe.ui.coordinators.McpCoordinator(
            host = coordinatorHost,
            mcpRegistry = mcpRegistry,
            mcpAuditLogger = mcpAuditLogger,
            mcpPermissionManager = mcpPermissionManager,
            mcpClientRunner = mcpClientRunner,
        )
    private val skillsCoordinator =
        com.codeair.mhe.ui.coordinators.SkillsCoordinator(
            host = coordinatorHost,
            skillManager = skillManager,
            installer = installer,
            preferences = preferences,
        )
    private val appFactoryCoordinator =
        com.codeair.mhe.ui.coordinators.AppFactoryCoordinator(
            host = coordinatorHost,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            refreshProjectFiles = { refreshProjectFiles() },
        )
    private val gitCoordinator =
        com.codeair.mhe.ui.coordinators.GitCoordinator(
            host = coordinatorHost,
            gitDomainController = gitDomainController,
            gitHubClient = gitHubClient,
            installer = installer,
            preferences = preferences,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            refreshProjectFiles = { refreshProjectFiles() },
            openProject = { project -> openProject(project) },
        )
    private val projectsCoordinator =
        com.codeair.mhe.ui.coordinators.ProjectsCoordinator(
            host = coordinatorHost,
            preferences = preferences,
            projectWorkspaceController = projectWorkspaceController,
            fileOperationsController = fileOperationsController,
            editorCheckpoints = editorCheckpoints,
            checkpointManager = checkpointManager,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            openProject = { project -> openProject(project) },
            refreshProjectFiles = { refreshProjectFiles() },
        )
    private val editorCoordinator =
        com.codeair.mhe.ui.coordinators.EditorCoordinator(
            host = coordinatorHost,
            preferences = preferences,
            fileOperationsController = fileOperationsController,
            editorCheckpoints = editorCheckpoints,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
        )
    private val runtimeCoordinator =
        com.codeair.mhe.ui.coordinators.RuntimeCoordinator(
            host = coordinatorHost,
            installer = installer,
            preferences = preferences,
            vault = vault,
            appUpdater = { appUpdater() },
            pingApi = { pingApi() },
            autoUpdateToolsAndSkills = { autoUpdateToolsAndSkills() },
            openProject = { project -> openProject(project) },
            selectAgent = { kind -> selectAgent(kind) },
            refreshActiveApiKey = { kind -> refreshActiveApiKey(kind) },
        )

    val marketplaceInstalledIds: StateFlow<Set<String>> = marketplaceCoordinator.installedIds
    val visualConsoleLogs: StateFlow<List<com.codeair.mhe.visual.ConsoleLogEntry>> = visualCoordinator.visualConsoleLogs
    val visualNetworkLogs: StateFlow<List<com.codeair.mhe.visual.NetworkLogEntry>> = visualCoordinator.visualNetworkLogs
    val inspectedDomElement: StateFlow<com.codeair.mhe.visual.DomElementInfo?> = visualCoordinator.inspectedDomElement
    val visualSnapshots: StateFlow<List<com.codeair.mhe.visual.VisualSnapshot>> = visualCoordinator.visualSnapshots
    val visualComparisonPair: StateFlow<com.codeair.mhe.visual.VisualComparisonPair?> = visualCoordinator.visualComparisonPair
    val activeViewportPreset: StateFlow<com.codeair.mhe.visual.ViewportPreset> = visualCoordinator.activeViewportPreset
    val detectedPortInfos: StateFlow<List<com.codeair.mhe.visual.LocalhostPortInfo>> = visualCoordinator.detectedPortInfos
    val detectedLocalhostPorts: StateFlow<List<Int>> = visualCoordinator.detectedLocalhostPorts
    val terminalLines: StateFlow<List<TerminalOutputLine>> = terminalCoordinator.terminalLines
    val isTerminalRunning: StateFlow<Boolean> = terminalCoordinator.isTerminalRunning
    val terminalLiveOutput: StateFlow<String> = terminalCoordinator.terminalLiveOutput
    val terminalCurrentCommand: StateFlow<String?> = terminalCoordinator.terminalCurrentCommand

    /**
     * Milestone 1: checks a project's workspace against the supported resource
     * budgets (file count, total size). Returns SUCCESS when within budget,
     * FAILED with an actionable message naming the exceeded budget otherwise.
     */
    fun checkProjectResources(projectRoot: File): com.codeair.mhe.core.result.OperationResult {
        return com.codeair.mhe.core.resources.ResourceGate().checkProject(
            projectRoot,
            com.codeair.mhe.core.resources.AndroidResourceProbe(getApplication()),
        )
    }

    // ---- Milestone 1: terminal domain moved to TerminalCoordinator. ----
    fun runTerminalCommand(cmd: String) =
        terminalCoordinator.runTerminalCommand(cmd)

    fun sendTerminalInput(text: String) =
        terminalCoordinator.sendTerminalInput(text)

    fun interruptTerminalCommand() =
        terminalCoordinator.interruptTerminalCommand()

    fun clearTerminal() =
        terminalCoordinator.clearTerminal()

    fun requestProjectTerminalCommand(command: String) =
        terminalCoordinator.requestProjectTerminalCommand(command)

    fun prepareProjectTerminalCommand(command: String) =
        terminalCoordinator.prepareProjectTerminalCommand(command)

    fun consumeProjectTerminalDraft() =
        terminalCoordinator.consumeProjectTerminalDraft()

    fun openProjectTerminal() =
        terminalCoordinator.openProjectTerminal()

    fun confirmProjectTerminalCommand() =
        terminalCoordinator.confirmProjectTerminalCommand()

    fun cancelProjectTerminalCommand() =
        terminalCoordinator.cancelProjectTerminalCommand()

    fun stopProjectTerminalCommand() =
        terminalCoordinator.stopProjectTerminalCommand()

    fun sendProjectTerminalInput(text: String) =
        terminalCoordinator.sendProjectTerminalInput(text)

    fun interruptProjectTerminalCommand() =
        terminalCoordinator.interruptProjectTerminalCommand()

    fun clearProjectTerminal() =
        terminalCoordinator.clearProjectTerminal()

    fun createTerminalSession() =
        terminalCoordinator.createTerminalSession()

    fun selectTerminalSession(sessionId: String) =
        terminalCoordinator.selectTerminalSession(sessionId)

    fun closeTerminalSession(sessionId: String) =
        terminalCoordinator.closeTerminalSession(sessionId)

    private fun loadProjectTerminal(project: Project) =
        terminalCoordinator.loadProjectTerminal(project)
    fun activeProjectWorkspaceRoot(): File? = _state.value.activeProject?.let { projectWorkspaceRoot(it) }

    fun projectWorkspaceRoot(project: Project): File {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
            .apply { mkdirs() }
            .canonicalFile
        if (project.rootPath.isBlank()) return base
        val selected = File(base, project.rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    fun buildAndRunAndroidApp() {
        val project = _state.value.activeProject ?: return
        if (_state.value.androidBuildRunning) return
        if (!installer.isStackInstalled(DevStack.ANDROID)) {
            _state.update {
                it.copy(toastMessage = "Android build tools are not installed. Add Android in Settings → Development stacks.")
            }
            return
        }
        val storage = runtimeHealthController.checkStorageHealth(com.codeair.mhe.runtime.RuntimeHealthController.MIN_BUILD_STORAGE_BYTES)
        if (storage is com.codeair.mhe.runtime.StorageHealthResult.Critical) {
            _state.update { it.copy(toastMessage = storage.message) }
            return
        }
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Wait for Claude to finish creating the project before building.") }
            return
        }
        if (_state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Wait for the project terminal command to finish before building.") }
            return
        }
        _state.update { it.copy(androidBuildRunning = true, androidBuildMessage = "Building debug APK…", toastMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val installed = installer.installedRuntime()
                val workspace = findAndroidGradleProjectRoot(projectWorkspaceRoot(project))
                    ?: error("No Android Gradle project found yet. Ask Claude to create it, then wait for the task to finish.")
                val process = installer.process(
                    installed.proot, installed.rootfs, workspace, emptyMap(),
                    listOf(
                        "/usr/bin/bash", "-lc",
                        "gradle --init-script /root/.gradle/init.d/pocketdev-android.gradle " +
                            "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
                            "--no-daemon assembleDebug --console=plain",
                    ),
                    guestRootFor(project),
                )
                val exitCode = process.waitFor()
                val rawBuildOutput = (process as? NativeSpawnProcess)?.outputFile?.readTailText(MAX_PROCESS_OUTPUT_BYTES).orEmpty()
                val buildOutput = truncateHeadAndTail(rawBuildOutput, maxHeadLines = 10, maxTailLines = 30, maxTotalLines = 50)
                check(exitCode == 0) {
                    buildOutput.trim().takeLast(2_000).ifBlank { "Gradle build failed (exit code $exitCode)" }
                }
                val apk = workspace.walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) && it.path.contains("/outputs/apk/debug/") }
                    .maxByOrNull(File::lastModified)
                    ?: error("Gradle finished but no debug APK was found")
                AndroidAppInstaller.install(getApplication(), apk)
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            androidBuildRunning = false,
                            androidBuildMessage = "APK sent to Android installer",
                            toastMessage = "APK built. Complete Android's install prompt.",
                        )
                    }
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(androidBuildRunning = false, androidBuildMessage = null, toastMessage = error.message ?: "Could not build APK") }
                }
            }
        }
    }

    private fun findAndroidGradleProjectRoot(workspace: File): File? {
        val settingsNames = setOf("settings.gradle", "settings.gradle.kts", "settings.gradle.dcl")
        return workspace.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.name in settingsNames }
            .mapNotNull(File::getParentFile)
            .sortedBy { it.absolutePath.length }
            .firstOrNull { root ->
                root.walkTopDown()
                    .maxDepth(4)
                    .any { it.isFile && it.invariantSeparatorsPath.endsWith("src/main/AndroidManifest.xml") }
            }
    }

    private fun requiresAndroidToolchain(command: String): Boolean =
        Regex("(?m)(^|[;&|]\\s*)(?:\\./)?gradle(?:w)?(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(command)


    fun toggleTheme() {
        val next = if (_state.value.themeMode == com.codeair.mhe.ui.theme.AppThemeMode.DARK) {
            com.codeair.mhe.ui.theme.AppThemeMode.LIGHT
        } else {
            com.codeair.mhe.ui.theme.AppThemeMode.DARK
        }
        setThemeMode(next)
    }

    fun setThemeMode(mode: com.codeair.mhe.ui.theme.AppThemeMode) {
        preferences.themeMode = mode.name.lowercase()
        _state.update { it.copy(themeMode = mode) }
    }

    // ---- Milestone 1: security domain moved to SecurityCoordinator. ----
    fun engageEmergencyKillSwitch(reason: String = "User requested emergency stop") =
        securityCoordinator.engageEmergencyKillSwitch(reason)

    fun resetEmergencyKillSwitch() =
        securityCoordinator.resetEmergencyKillSwitch()

    fun isEmergencyKillSwitchEngaged(): Boolean =
        securityCoordinator.isEmergencyKillSwitchEngaged()

    fun refreshPermissionPolicies() =
        securityCoordinator.refreshPermissionPolicies()

    fun updatePermissionPolicy(category: com.codeair.mhe.security.PermissionCategory, allowed: Boolean) =
        securityCoordinator.updatePermissionPolicy(category, allowed)

    fun loadSecurityAuditLogs(limit: Int = 200) =
        securityCoordinator.loadSecurityAuditLogs(limit)

    fun searchSecurityAuditLogs(
        query: String = "",
        category: com.codeair.mhe.security.PermissionCategory? = null,
        decision: com.codeair.mhe.security.AuditDecision? = null,
        limit: Int = 200
    ) = securityCoordinator.searchSecurityAuditLogs(query, category, decision, limit)

    fun clearSecurityAuditLogs() =
        securityCoordinator.clearSecurityAuditLogs()
    // ---- Milestone 1: MCP domain moved to McpCoordinator. ----
    fun refreshMcpServers(projectId: String? = _state.value.activeProject?.id) =
        mcpCoordinator.refreshMcpServers(projectId)

    fun installMcpServer(server: com.codeair.mhe.mcp.McpServerInfo) =
        mcpCoordinator.installMcpServer(server)

    fun installMcpTemplate(templateId: String, envOverrides: Map<String, String> = emptyMap(), projectId: String? = _state.value.activeProject?.id) =
        mcpCoordinator.installMcpTemplate(templateId, envOverrides, projectId)

    fun toggleMcpServer(serverId: String, enabled: Boolean) =
        mcpCoordinator.toggleMcpServer(serverId, enabled)

    fun removeMcpServer(serverId: String) =
        mcpCoordinator.removeMcpServer(serverId)

    fun updateMcpConfig(server: com.codeair.mhe.mcp.McpServerInfo) =
        mcpCoordinator.updateMcpConfig(server)

    fun discoverMcpTools(server: com.codeair.mhe.mcp.McpServerInfo) =
        mcpCoordinator.discoverMcpTools(server)

    suspend fun executeMcpTool(
        server: com.codeair.mhe.mcp.McpServerInfo,
        toolName: String,
        arguments: org.json.JSONObject,
        projectId: String? = _state.value.activeProject?.id
    ): com.codeair.mhe.runtime.McpToolCallResult =
        mcpCoordinator.executeMcpTool(server, toolName, arguments, projectId)

    fun setMcpProjectPermission(projectId: String, serverId: String, policy: com.codeair.mhe.mcp.McpPermissionPolicy) =
        mcpCoordinator.setMcpProjectPermission(projectId, serverId, policy)

    fun setMcpGlobalPermission(serverId: String, policy: com.codeair.mhe.mcp.McpPermissionPolicy) =
        mcpCoordinator.setMcpGlobalPermission(serverId, policy)

    fun loadMcpAuditLogs(limit: Int = 100) =
        mcpCoordinator.loadMcpAuditLogs(limit)

    fun clearMcpAuditLogs() =
        mcpCoordinator.clearMcpAuditLogs()
    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()

    fun getSavedApiKeys(kind: ProviderKind): List<ApiKeyInfo> = vault.list(kind.name)

    fun addApiKey(kind: ProviderKind, name: String, secret: String): List<ApiKeyInfo> {
        vault.add(kind.name, name, secret)
        val keys = vault.list(kind.name)
        refreshActiveApiKey(kind)
        return keys
    }

    fun activateApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> {
        vault.activate(kind.name, keyId)
        refreshActiveApiKey(kind)
        return vault.list(kind.name)
    }

    fun removeApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> {
        vault.remove(kind.name, keyId)
        refreshActiveApiKey(kind)
        return vault.list(kind.name)
    }

    private fun refreshActiveApiKey(kind: ProviderKind) {
        if (_state.value.provider.kind != kind) return
        val keys = vault.list(kind.name)
        _state.update { current ->
            current.copy(
                activeApiKeyName = keys.firstOrNull(ApiKeyInfo::isActive)?.name,
                provider = current.provider.copy(hasSecret = keys.isNotEmpty()),
            )
        }
    }

    /** Keeps both agent bridges mapped to the same workspace root; the active one is used. */
    private fun configureBridgeRoots(projectId: String, rootPath: String) {
        claudeRuntime.configureProjectRoot(projectId, rootPath)
        dshRuntime.configureProjectRoot(projectId, rootPath)
        antigravityRuntime.configureProjectRoot(projectId, rootPath)
    }

    init {
        runtimeCoordinator.startSetupSnapshotCollection()
        viewModelScope.launch { claudeRuntime.events.collect(::onRuntimeEvent) }
        runtimeCoordinator.startBootstrap()
    }

    // ---- Milestone 1: runtime domain moved to RuntimeCoordinator. ----
    // (bootstrap is now started via runtimeCoordinator.startBootstrap() from init)

    fun startRuntimeSetup() =
        runtimeCoordinator.startRuntimeSetup()

    fun retryStartup() =
        runtimeCoordinator.retryStartup()

    fun backToSetup() =
        runtimeCoordinator.backToSetup()




    fun checkForAppUpdate(force: Boolean = false) =
        runtimeCoordinator.checkForAppUpdate(force)

    /** Set or update the in-app update channel / manifest URL. */
    fun setUpdateManifestUrl(url: String) =
        runtimeCoordinator.setUpdateManifestUrl(url)

    /** Reset the in-app update manifest URL to the default release channel. */
    fun clearUpdateManifestUrl() =
        runtimeCoordinator.clearUpdateManifestUrl()

    /** Set or update the private GitHub Personal Access Token or Deploy Token. */
    fun setUpdateAuthToken(token: String) =
        runtimeCoordinator.setUpdateAuthToken(token)

    fun updateManifestUrl(): String =
        runtimeCoordinator.updateManifestUrl()
    fun updateAuthToken(): String =
        runtimeCoordinator.updateAuthToken()

    /** Debug builds only: persist a manifest URL override and re-check immediately. */
    fun setDebugUpdateManifestUrl(url: String) =
        runtimeCoordinator.setDebugUpdateManifestUrl(url)

    /** Debug builds only: clear the manifest URL override and re-check the default channel. */
    fun clearDebugUpdateManifestUrl() =
        runtimeCoordinator.clearDebugUpdateManifestUrl()

    /** Debug builds only: the currently-active manifest URL override (empty = default). */
    fun debugUpdateManifestUrl(): String =
        runtimeCoordinator.debugUpdateManifestUrl()

    fun installAppUpdate() =
        runtimeCoordinator.installAppUpdate()

    fun dismissAppUpdateError() =
        runtimeCoordinator.dismissAppUpdateError()



    fun finishOnboarding(profile: ProviderProfile, secret: String) =
        runtimeCoordinator.finishOnboarding(profile, secret)

    fun finishAntigravityOnboarding() =
        runtimeCoordinator.finishAntigravityOnboarding()

    /** Lets first-run users escape a provider/login failure without losing saved credentials. */
    fun chooseOnboardingAgent(kind: AgentKind) =
        runtimeCoordinator.chooseOnboardingAgent(kind)

    fun updateProvider(profile: ProviderProfile, secret: String) =
        runtimeCoordinator.updateProvider(profile, secret)

    fun finishBackgroundSetup() =
        runtimeCoordinator.finishBackgroundSetup()

    fun finishAppTour() =
        runtimeCoordinator.finishAppTour()

    fun replayAppTour() =
        runtimeCoordinator.replayAppTour()

    /** Called from the first-launch setup screen; persists the agent choice for setup and Settings. */
    fun refreshInstalledAgents() {
        viewModelScope.launch(Dispatchers.IO) {
            val versions = runCatching { installer.installedAgentVersions() }.getOrDefault(emptyMap())
            _state.update { it.copy(installedAgentVersions = versions) }
        }
    }

    fun selectAgent(kind: AgentKind) {
        if (_state.value.agentKind == kind) return
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Stop the current agent before switching.") }
            return
        }
        val selectingInitialAgent = !preferences.runtimeSetupComplete
        preferences.agentKind = kind.stableId
        if (selectingInitialAgent) preferences.primaryAgentKind = kind.stableId
        _state.update { current ->
            preferences.saveProvider(current.provider, current.agentKind)
            val provider = preferences.loadProvider(vault, kind)
            current.copy(
                agentKind = kind,
                primaryAgentKind = if (selectingInitialAgent) kind else current.primaryAgentKind,
                provider = provider,
                activeApiKeyName = vault.list(provider.kind.name).firstOrNull(ApiKeyInfo::isActive)?.name,
                // Ping results belong to the previous agent; never leak them across.
                apiPingStatus = ApiPingStatus.IDLE,
                apiPingMessage = null,
            )
        }
        refreshInstalledAgents()
    }

    /** Installs the other agent on demand (Settings) with live progress, then switches to it. */
    fun installAgent(kind: AgentKind) {
        if (_state.value.agentInstalling != null) return
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Stop the current agent before switching.") }
            return
        }
        if (installer.isAgentInstalled(kind)) {
            selectAgent(kind)
            return
        }
        _state.update {
            it.copy(
                agentInstalling = kind,
                agentMessage = "Preparing ${kind.title}…",
                agentProgress = 0f,
                agentDownloadedBytes = null,
                agentTotalBytes = null,
                agentBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    agentRegistry.require(kind).install(installer) { progress ->
                        val now = SystemClock.elapsedRealtime()
                        val bytes = progress.downloadedBytes
                        val elapsed = now - sampleAt
                        val speed = if (bytes != null && elapsed >= 500L) {
                            ((bytes - sampleBytes).coerceAtLeast(0L) * 1_000L / elapsed.coerceAtLeast(1L)).also {
                                sampleBytes = bytes
                                sampleAt = now
                            }
                        } else _state.value.agentBytesPerSecond
                        _state.update { current ->
                            current.copy(
                                agentMessage = progress.message,
                                agentProgress = progress.fraction.coerceIn(0f, 1f),
                                agentDownloadedBytes = bytes ?: current.agentDownloadedBytes,
                                agentTotalBytes = progress.totalBytes ?: current.agentTotalBytes,
                                agentBytesPerSecond = speed,
                            )
                        }
                    }
                }
            }
            result.onSuccess {
                if (kind == AgentKind.DEEPSEEK_HARNESS) preferences.dshVersion = installer.dshVersion
                selectAgent(kind)
            }
            _state.update { current ->
                current.copy(
                    installedAgentVersions = installer.installedAgentVersions(),
                    agentInstalling = null,
                    agentProgress = 0f,
                    agentDownloadedBytes = null,
                    agentTotalBytes = null,
                    agentBytesPerSecond = null,
                    agentMessage = result.fold(
                        onSuccess = { "${kind.title} is ready" },
                        onFailure = { _ -> result.exceptionOrNull()?.message?.take(200) ?: "Could not install ${kind.title}" },
                    ),
                )
            }
        }
    }

    fun checkAgentUpdates() {
        if (_state.value.agentUpdatesChecking || _state.value.agentUpdating != null || _state.value.isRunning) return
        _state.update { it.copy(agentUpdatesChecking = true, agentUpdateMessage = "Checking official agent releases…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { installer.checkAgentUpdates() } }
            _state.update {
                it.copy(
                    agentUpdates = result.getOrDefault(emptyMap()),
                    agentUpdatesChecking = false,
                    agentUpdateMessage = result.fold(
                        onSuccess = { updates -> if (updates.isEmpty()) "All installed agents are up to date" else "${updates.size} agent update${if (updates.size == 1) "" else "s"} available" },
                        onFailure = { error -> error.message?.take(200) ?: "Could not check agent updates" },
                    ),
                )
            }
        }
    }

    fun updateAgent(kind: AgentKind) {
        val update = _state.value.agentUpdates[kind] ?: return
        if (_state.value.agentUpdating != null || _state.value.agentInstalling != null || _state.value.isRunning) return
        _state.update {
            it.copy(
                agentUpdating = kind,
                agentUpdateMessage = "Preparing ${kind.title} ${update.latestVersion}…",
                agentUpdateProgress = 0f,
                agentUpdateDownloadedBytes = null,
                agentUpdateTotalBytes = null,
                agentUpdateBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleAt = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    installer.updateAgent(kind, update.latestVersion) { progress ->
                        val now = SystemClock.elapsedRealtime()
                        val bytes = progress.downloadedBytes
                        val elapsed = now - sampleAt
                        val speed = if (bytes != null && elapsed >= 500L) {
                            ((bytes - sampleBytes).coerceAtLeast(0L) * 1_000L / elapsed.coerceAtLeast(1L)).also {
                                sampleBytes = bytes
                                sampleAt = now
                            }
                        } else _state.value.agentUpdateBytesPerSecond
                        _state.update {
                            it.copy(
                                agentUpdateMessage = progress.message,
                                agentUpdateProgress = progress.fraction.coerceIn(0f, 1f),
                                agentUpdateDownloadedBytes = bytes ?: it.agentUpdateDownloadedBytes,
                                agentUpdateTotalBytes = progress.totalBytes ?: it.agentUpdateTotalBytes,
                                agentUpdateBytesPerSecond = speed,
                            )
                        }
                    }
                }
            }
            _state.update { current ->
                current.copy(
                    installedAgentVersions = installer.installedAgentVersions(),
                    agentUpdates = if (result.isSuccess) current.agentUpdates - kind else current.agentUpdates,
                    agentUpdating = null,
                    agentUpdateMessage = result.fold(
                        onSuccess = { "${kind.title} updated to ${update.latestVersion}" },
                        onFailure = { error -> error.message?.take(220) ?: "Could not update ${kind.title}" },
                    ),
                    agentUpdateProgress = if (result.isSuccess) 1f else 0f,
                    agentUpdateDownloadedBytes = null,
                    agentUpdateTotalBytes = null,
                    agentUpdateBytesPerSecond = null,
                )
            }
        }
    }

    fun startAntigravityLogin() {
        if (_state.value.agentInstalling != null || _state.value.isRunning) return
        lastOpenedAntigravityAuthUrl = null
        viewModelScope.launch { antigravityAuthController.beginLogin() }
    }

    fun submitAntigravityCode(code: String) {
        runCatching { antigravityAuthController.submitCode(code) }
            .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not submit the code") } }
    }

    fun logoutAntigravity() {
        viewModelScope.launch {
            runCatching { antigravityAuthController.logout() }
                .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not sign out") } }
        }
    }

    fun startAntigravityAddAccount() {
        if (_state.value.agentInstalling != null || _state.value.isRunning) return
        lastOpenedAntigravityAuthUrl = null
        viewModelScope.launch { antigravityAuthController.beginAddAccount() }
    }

    fun switchAntigravityAccount(email: String) {
        val success = antigravityAuthController.activateAccount(email)
        if (success) {
            _state.update { it.copy(toastMessage = "Switched to $email") }
        }
    }

    fun removeAntigravityAccount(email: String) {
        viewModelScope.launch {
            antigravityAuthController.removeAccount(email)
            _state.update { it.copy(toastMessage = "Removed $email") }
        }
    }

    fun toggleAntigravityAutoRoundRobin(enabled: Boolean) {
        preferences.antigravityAutoRoundRobin = enabled
        antigravityAuthController.setAutoRoundRobin(enabled)
    }

    fun setAntigravityModel(model: String) {
        preferences.antigravityModel = model
        val modelEffort = antigravityEffortFromModel(model)
        if (modelEffort != null) preferences.antigravityEffort = modelEffort
        _state.update {
            it.copy(
                antigravityModel = model,
                antigravityEffort = modelEffort ?: it.antigravityEffort,
            )
        }
    }

    fun setAntigravityEffort(effort: String) {
        if (effort !in setOf("low", "medium", "high")) return
        val current = _state.value
        val matchingModel = antigravityModelWithEffort(current.antigravityModel, effort)
            ?.takeIf { candidate -> current.antigravityModels.isEmpty() || candidate in current.antigravityModels }
        if (current.antigravityModel.isNotBlank() &&
            antigravityEffortFromModel(current.antigravityModel) != null &&
            matchingModel == null
        ) {
            _state.update { it.copy(toastMessage = "This model does not offer ${effort.replaceFirstChar(Char::uppercase)} reasoning") }
            return
        }
        preferences.antigravityEffort = effort
        matchingModel?.let { preferences.antigravityModel = it }
        _state.update {
            it.copy(
                antigravityEffort = effort,
                antigravityModel = matchingModel ?: it.antigravityModel,
            )
        }
    }

    fun refreshAntigravityModels() {
        if (_state.value.antigravityModelsLoading || !installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) return
        _state.update { it.copy(antigravityModelsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val runtime = installer.installedRuntime()
                val workspace = File(getApplication<Application>().filesDir, "workspaces/antigravity-models").apply { mkdirs() }
                val process = installer.process(
                    runtime.proot,
                    runtime.rootfs,
                    workspace,
                    emptyMap(),
                    listOf(com.codeair.mhe.runtime.RuntimeInstaller.AGY_GUEST_PATH, "models"),
                    guestWorkspacePath = "/workspace/antigravity-models",
                    emulateHardLinks = false,
                )
                while (process.isAlive) delay(50)
                check(process.waitFor() == 0) { "Could not list Antigravity models" }
                val output = (process as? NativeSpawnProcess)?.outputFile?.readTailText(MAX_PROCESS_OUTPUT_BYTES).orEmpty()
                output.lineSequence()
                    .map { sanitizeTerminalOutput(it).trim() }
                    .mapNotNull { line -> line.split(Regex("\\s+"), limit = 2).firstOrNull() }
                    .filter { it.matches(Regex("[a-z0-9][a-z0-9._-]+")) }
                    .distinct()
                    .toList()
                    .also { check(it.isNotEmpty()) { "Antigravity returned no models" } }
            }
            withContext(Dispatchers.Main) {
                _state.update { current ->
                    result.fold(
                        onSuccess = { models ->
                            val preferred = antigravityModelWithEffort(
                                current.antigravityModel,
                                current.antigravityEffort,
                            )?.takeIf(models::contains)
                            val selected = preferred
                                ?: current.antigravityModel.takeIf(models::contains)
                                ?: models.first()
                            val selectedEffort = antigravityEffortFromModel(selected) ?: current.antigravityEffort
                            preferences.antigravityModel = selected
                            preferences.antigravityEffort = selectedEffort
                            current.copy(
                                antigravityModelsLoading = false,
                                antigravityModels = models,
                                antigravityModel = selected,
                                antigravityEffort = selectedEffort,
                            )
                        },
                        onFailure = { error -> current.copy(
                            antigravityModelsLoading = false,
                            toastMessage = error.message ?: "Could not load Antigravity models",
                        ) },
                    )
                }
            }
        }
    }

    /** Called from the first-launch tool picker; persists the choice for setup and Settings. */
    fun toggleDevStack(stack: DevStack) =
        runtimeCoordinator.toggleDevStack(stack)

    /** Installs one development stack on demand (Settings) with live progress. */
    fun installDevStack(stack: DevStack) =
        runtimeCoordinator.installDevStack(stack)

    /** Updates an installed optional toolchain in place. */
    fun updateDevStack(stack: DevStack) =
        runtimeCoordinator.updateDevStack(stack)

    /** Removes an optional toolchain after the Settings confirmation dialog. */
    fun removeDevStack(stack: DevStack) =
        runtimeCoordinator.removeDevStack(stack)

    suspend fun discoverModels(profile: ProviderProfile, secret: String): ModelDiscoveryResult {
        val key = secret.ifBlank { vault.get(profile.kind.name).orEmpty() }
        return providerApi.discoverModels(profile.baseUrl, key, providerProtocolForAgent(profile, _state.value.agentKind))
    }

    suspend fun validateProvider(
        profile: ProviderProfile,
        secret: String,
        models: List<com.codeair.mhe.network.DiscoveredModel>,
    ): ConnectionValidation {
        val key = secret.ifBlank { vault.get(profile.kind.name).orEmpty() }
        return providerApi.validate(
            profile.baseUrl,
            profile.model,
            key,
            providerProtocolForAgent(profile, _state.value.agentKind),
            models,
            profile.openRouterProviderOrder,
            profile.openRouterAllowFallbacks,
        )
    }

    fun pingApi() {
        if (_state.value.agentKind == AgentKind.ANTIGRAVITY) {
            testAntigravityConnection()
            return
        }
        val profile = _state.value.provider
        if (profile.baseUrl.isBlank() || profile.model.isBlank()) return
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = "Sending a minimal test request…") }
        viewModelScope.launch {
            val key = vault.get(profile.kind.name).orEmpty()
            val result = providerApi.validate(
                profile.baseUrl,
                profile.model,
                key,
                providerProtocolForAgent(profile, _state.value.agentKind),
                emptyList(),
                profile.openRouterProviderOrder,
                profile.openRouterAllowFallbacks,
            )
            when (result) {
                is ConnectionValidation.Success -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.OK, apiPingMessage = "API responded successfully")
                }
                is ConnectionValidation.Failure -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.FAILED, apiPingMessage = result.message)
                }
            }
        }
    }

    /** Sends a tiny hello to the agy CLI to prove it actually answers. Silent timeout inside. */
    fun testAntigravityConnection() {
        if (_state.value.agentKind != AgentKind.ANTIGRAVITY) return
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        if (_state.value.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update {
                it.copy(
                    apiPingStatus = ApiPingStatus.FAILED,
                    apiPingMessage = "Antigravity needs Google sign-in",
                )
            }
            return
        }
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = "Saying hello to Antigravity…") }
        viewModelScope.launch {
            val result = runCatching { antigravityRuntime.hello() }
            result.onSuccess {
                _state.update {
                    it.copy(
                        apiPingStatus = ApiPingStatus.OK,
                        apiPingMessage = "Antigravity is Working!",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.FAILED, apiPingMessage = helloFailureMessage(error.message.orEmpty()))
                }
            }
        }
    }

    private fun helloFailureMessage(raw: String): String {
        val value = raw.replace(Regex("\\s+"), " ").trim()
        return when {
            value.contains("not installed", true) -> "Antigravity CLI is not installed. Install it from the Coding agent section."
            value.contains("sign-in", true) || value.contains("not signed in", true) ||
                value.contains("authentication", true) -> "Antigravity needs Google sign-in. Reconnect from the Google connection section."
            value.contains("did not answer", true) -> "Antigravity did not answer. Try again."
            value.isBlank() -> "Antigravity did not answer. Try again."
            else -> value.take(200)
        }
    }

    fun openProject(project: Project) {
        val current = _state.value
        if (current.activeProject?.id == project.id) {
            _state.update {
                it.copy(
                    workspaceVisible = true,
                    readOnlyProject = null,
                    readOnlyProjectChats = emptyList(),
                    readOnlyChatId = null,
                    readOnlyMessages = emptyList(),
                )
            }
            return
        }
        if (current.isRunning || current.projectTerminalRunning) {
            val chats = preferences.loadProjectChats(project.id).ifEmpty {
                listOf(ProjectChat(title = "Main chat"))
            }
            val chat = chats.first()
            _state.update {
                it.copy(
                    readOnlyProject = project,
                    readOnlyProjectChats = chats,
                    readOnlyChatId = chat.id,
                    readOnlyMessages = preferences.loadMessages(project.id, chat.id),
                )
            }
            return
        }
        preferences.lastActiveProjectId = project.id
        configureBridgeRoots(project.id, project.rootPath)
        val terminal = loadProjectTerminal(project)
        // Milestone 2: restore the persisted editor session (dirty buffers,
        // cursor/scroll, split state) before the UI reads tab state.
        editorCoordinator.restoreEditorSession(project)
        val savedTabs = preferences.getOpenEditorTabs(project.id)
        val initialOpenedPath = savedTabs.firstOrNull()
        val suggestedRoot = if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null
        val chats = preferences.loadProjectChats(project.id).ifEmpty {
            listOf(ProjectChat(title = "Main chat")).also { preferences.saveProjectChats(project.id, it) }
        }
        val lastChatId = preferences.getLastActiveChatId(project.id)
        val activeChat = chats.firstOrNull { it.id == lastChatId } ?: chats.first()
        preferences.setLastActiveChatId(project.id, activeChat.id)
        val saved = preferences.loadMessages(project.id, activeChat.id)
        val msgs = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) }
        val loadedRoadmap = msgs.mapNotNull { it.roadmap }.lastOrNull()
        val loadedScratchpad = deriveScratchpadItems(loadedRoadmap, emptyList(), false)
        _state.update {
            it.copy(
                activeProject = project,
                workspaceVisible = true,
                readOnlyProject = null,
                readOnlyProjectChats = emptyList(),
                readOnlyChatId = null,
                readOnlyMessages = emptyList(),
                projectChats = chats,
                activeChatId = activeChat.id,
                messages = msgs,
                activeRoadmap = loadedRoadmap,
                scratchpadItems = loadedScratchpad,
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                openedFilePath = initialOpenedPath,
                openedFileContent = null,
                openEditorTabs = savedTabs,
                fileContentLoading = initialOpenedPath != null,
                projectTerminalLines = terminal.lines,
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = terminal.cwd,
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                // Milestone 2: restore persisted terminal session tabs.
                terminalSessions = terminal.sessions.ifEmpty {
                    listOf(TerminalSessionTab(id = "term-1", title = "Terminal 1"))
                },
                activeTerminalSessionId = terminal.activeSessionId
                    ?: terminal.sessions.firstOrNull()?.id ?: "term-1",
                suggestedProjectRoot = suggestedRoot,
                previewReady = false,
                previewUrl = null,
                pendingAttachments = emptyList(),
            )
        }
        if (initialOpenedPath != null) {
            loadFileContent(project, initialOpenedPath)
        }
        refreshProjectFiles()
        viewModelScope.launch {
            val pending = activeRuntime().loadPendingChanges(project.id)
            if (_state.value.activeProject?.id == project.id) _state.update { it.copy(changes = pending) }
        }
    }

    fun closeProject() {
        preferences.lastActiveProjectId = null
        val active = _state.value.activeProject
        persistMessages()
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update {
                it.copy(
                    workspaceVisible = false,
                    toastMessage = if (it.isRunning) {
                        "Task continues in the background"
                    } else {
                        "Terminal command continues in the background"
                    },
                )
            }
            return
        }

        if (active != null) {
            val chats = preferences.loadProjectChats(active.id)
            val userMessages = chats.sumOf { preferences.loadMessages(active.id, it.id).count { m -> m.fromUser } }
            val workspaceDir = File(getApplication<Application>().filesDir, "workspaces/${active.id}")
            val userFiles = if (workspaceDir.isDirectory) {
                workspaceDir.walkTopDown().filter { file ->
                    file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                }.count()
            } else 0

            if (userMessages == 0 && userFiles == 0 && !_state.value.isRunning && !_state.value.projectTerminalRunning) {
                // Unused empty project; delete immediately so it does not clutter the project list.
                _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == active.id }) }
                preferences.saveProjects(_state.value.projects)
                viewModelScope.launch(Dispatchers.IO) {
                    workspaceDir.deleteRecursively()
                    terminalCoordinator.deleteTerminalHistory(active.id)
                    preferences.deleteProjectChats(active.id)
                }
            }
        }

        _state.update {
            it.copy(
                activeProject = null,
                workspaceVisible = false,
                projectChats = emptyList(),
                activeChatId = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = false,
                openedFilePath = null,
                openedFileContent = null,
                openEditorTabs = emptyList(),
                fileContentLoading = false,
                isRunning = false,
                activeSessionId = null,
                pendingApproval = null,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = "/workspace",
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun closeReadOnlyProject() {
        _state.update {
            it.copy(
                readOnlyProject = null,
                readOnlyProjectChats = emptyList(),
                readOnlyChatId = null,
                readOnlyMessages = emptyList(),
            )
        }
    }

    fun switchReadOnlyChat(chatId: String) {
        val project = _state.value.readOnlyProject ?: return
        if (_state.value.readOnlyProjectChats.none { it.id == chatId }) return
        _state.update {
            it.copy(
                readOnlyChatId = chatId,
                readOnlyMessages = preferences.loadMessages(project.id, chatId),
            )
        }
    }

    fun activateReadOnlyProject() {
        if (_state.value.isRunning || _state.value.projectTerminalRunning) return
        val project = _state.value.readOnlyProject ?: return
        closeReadOnlyProject()
        openProject(project)
    }

    /** Routes a notification tap into the existing project workspace instead of creating a second navigation stack. */
    fun handleNotificationRoute(route: String?) {
        val normalized = route?.trim().orEmpty()
        if (normalized.isBlank()) return

        if (normalized.startsWith("browser:") || normalized.startsWith("http://") || normalized.startsWith("https://")) {
            val targetUrl = normalized.removePrefix("browser:").trim()
            if (targetUrl.isNotBlank()) {
                val hasProject = _state.value.activeProject != null
                if (!hasProject) {
                    val defaultProj = _state.value.projects.firstOrNull()
                    if (defaultProj != null) {
                        openProject(defaultProj)
                    } else {
                        createQuickProject()
                    }
                }
                _state.update {
                    it.copy(
                        previewReady = true,
                        previewUrl = targetUrl,
                        workspaceVisible = true,
                    )
                }
            }
            return
        }

        val segments = normalized.trim('/').split('/').filter(String::isNotBlank)
        val slug = segments.getOrNull(1).takeIf { segments.firstOrNull() == "project" }
        if (slug == null) return
        val project = _state.value.projects.firstOrNull { it.slug == slug }
        if (project == null) {
            _state.update { it.copy(toastMessage = "The project from this notification is no longer available") }
            return
        }
        openProject(project)
    }
    fun consumeToast() = _state.update { it.copy(toastMessage = null) }
    fun showToast(message: String) = _state.update { it.copy(toastMessage = message) }

    fun createProject(name: String, folderUri: Uri? = null) {
        if (name.isBlank() && folderUri == null) return
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop the background task before creating another project") }
            return
        }
        if (folderUri != null) {
            _state.update { it.copy(projectImporting = true, projectImportMessage = "Creating project and binding folder…") }
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val app = getApplication<Application>()
                        val documentFile = DocumentFile.fromTreeUri(app, folderUri)
                        val folderName = documentFile?.name?.trim()?.takeIf { it.isNotBlank() } ?: "Project"
                        val effectiveName = if (name.isNotBlank()) name.trim() else folderName
                        val baseSlug = projectSlug(effectiveName)
                        val usedSlugs = _state.value.projects.mapTo(mutableSetOf()) { it.slug }
                        val slug = generateSequence(1) { it + 1 }
                            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
                            .first { it !in usedSlugs }
                        val projectId = UUID.randomUUID().toString()
                        val destination = File(app.filesDir, "workspaces/$projectId").apply { mkdirs() }
                        val copiedCount = copyTreeToDirectory(folderUri, destination)
                        val preliminary = Project(
                            id = projectId,
                            name = effectiveName,
                            description = "Imported from device storage",
                            language = "General",
                            slug = slug,
                        )
                        val nestedRoot = detectNestedProjectRoot(preliminary)
                        val projectRoot = nestedRoot?.let { File(destination, it) } ?: destination
                        val metadata = detectImportedProjectMetadata(projectRoot)
                        val project = preliminary.copy(
                            description = metadata.first.ifBlank { "Local device repository" },
                            language = metadata.second.ifBlank { "General" },
                            rootPath = nestedRoot.orEmpty(),
                        )
                        project to copiedCount
                    }
                }
                result.onSuccess { (project, count) ->
                    val firstChat = ProjectChat(title = "New chat")
                    preferences.saveProjectChats(project.id, listOf(firstChat))
                    _state.update { current ->
                        current.copy(
                            projects = listOf(project) + current.projects,
                            projectImporting = false,
                            projectImportMessage = null,
                            toastMessage = "${project.name} created ($count files bound)",
                        )
                    }
                    preferences.saveProjects(_state.value.projects)
                    openProject(project)
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            projectImporting = false,
                            projectImportMessage = null,
                            toastMessage = "Project creation failed: ${error.message?.take(180) ?: "Unknown error"}",
                        )
                    }
                }
            }
            return
        }
        val baseSlug = projectSlug(name)
        val usedSlugs = _state.value.projects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = name.trim(),
            description = "Starter web project",
            language = "TypeScript",
            slug = slug,
        )
        configureBridgeRoots(project.id, project.rootPath)
        val guestRoot = guestRootFor(project)
        _state.update {
            it.copy(
                projects = listOf(project) + it.projects,
                activeProject = project,
                workspaceVisible = true,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = guestRoot,
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
            )
        }
        preferences.saveProjects(_state.value.projects)
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        val firstChat = ProjectChat(title = "New chat")
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projectChats = listOf(firstChat), activeChatId = firstChat.id) }
        refreshProjectFiles()
    }

    // ---- Milestone 1: project lifecycle moved to ProjectsCoordinator. ----
    fun createQuickProject() =
        projectsCoordinator.createQuickProject()

    fun importZipProject(uri: Uri) {
        if (_state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning) return
        _state.update { it.copy(projectImporting = true, projectImportMessage = "Reading project archive…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { extractImportedProject(uri) } }
            result.onSuccess { imported ->
                val project = imported.project
                val firstChat = ProjectChat(title = "New chat")
                preferences.saveProjectChats(project.id, listOf(firstChat))
                _state.update { current ->
                    current.copy(
                        projects = listOf(project) + current.projects,
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "${project.name} imported successfully",
                    )
                }
                preferences.saveProjects(_state.value.projects)
                openProject(project)
                _state.update { it.copy(pendingAttachments = listOf(imported.sourceAttachment)) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "Import failed: ${error.message?.take(180) ?: "Invalid ZIP archive"}",
                    )
                }
            }
        }
    }

    private fun extractImportedProject(uri: Uri): ImportedZipProject {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        var archiveName = "Imported project.zip"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                    archiveName = cursor.getString(index) ?: archiveName
                }
            }
        }
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val projectId = UUID.randomUUID().toString()
        val destination = File(app.filesDir, "workspaces/$projectId")
        destination.mkdirs()
        val destinationPath = destination.canonicalFile.toPath()
        val availableLimit = (destination.usableSpace * 8L / 10L).coerceAtMost(MAX_IMPORTED_PROJECT_BYTES)
        var extractedBytes = 0L
        var entries = 0
        try {
            val source = resolver.openInputStream(uri) ?: error("The selected ZIP could not be opened")
            source.buffered().use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_IMPORTED_ZIP_ENTRIES) { "The ZIP contains too many files" }
                        val entryName = entry.name.replace('\\', '/').trimStart('/')
                        require(entryName.isNotBlank() && '\u0000' !in entryName) { "The ZIP contains an invalid path" }
                        if (entryName.startsWith("__MACOSX/") || entryName.endsWith("/.DS_Store") || entryName == ".DS_Store") {
                            zip.closeEntry()
                            continue
                        }
                        val target = File(destination, entryName).canonicalFile
                        require(target.toPath().startsWith(destinationPath)) { "The ZIP contains an unsafe path" }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    extractedBytes += count
                                    require(extractedBytes <= availableLimit) { "The extracted project is too large for available storage" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (entry.time > 0) target.setLastModified(entry.time)
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(entries > 0 && destination.walkTopDown().any { it.isFile }) { "The ZIP does not contain project files" }
            val preliminary = Project(
                id = projectId,
                name = identity.displayName,
                description = "Imported project workspace",
                language = "General",
                slug = identity.slug,
                kind = ProjectKind.QUICK_PROJECT,
            )
            val nestedRoot = detectNestedProjectRoot(preliminary)
            val projectRoot = nestedRoot?.let { File(destination, it) } ?: destination
            val metadata = detectImportedProjectMetadata(projectRoot)
            val safeArchiveName = sanitizeAttachmentName(archiveName).let { name ->
                if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
            }
            val archiveFolder = File(projectRoot, ".pocketdev/imports").apply { mkdirs() }
            val archivedSource = File(archiveFolder, safeArchiveName)
            var sourceBytes = 0L
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                archivedSource.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sourceBytes += count
                        require(extractedBytes + sourceBytes <= availableLimit) { "The imported project is too large for available storage" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("The selected ZIP could not be preserved")
            val project = preliminary.copy(
                description = metadata.first,
                language = metadata.second,
                rootPath = nestedRoot.orEmpty(),
            )
            return ImportedZipProject(
                project = project,
                sourceAttachment = ChatAttachment(
                    displayName = archiveName.take(120),
                    relativePath = archivedSource.relativeTo(projectRoot).invariantSeparatorsPath,
                    mimeType = "application/zip",
                    sizeBytes = sourceBytes,
                ),
            )
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
    }

    fun importFolderProject(uri: Uri) {
        if (_state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning) return
        _state.update { it.copy(projectImporting = true, projectImportMessage = "Importing folder from device…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { copyFolderToWorkspace(uri) } }
            result.onSuccess { project ->
                val firstChat = ProjectChat(title = "New chat")
                preferences.saveProjectChats(project.id, listOf(firstChat))
                _state.update { current ->
                    current.copy(
                        projects = listOf(project) + current.projects,
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "${project.name} imported from device storage",
                    )
                }
                preferences.saveProjects(_state.value.projects)
                openProject(project)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "Folder import failed: ${error.message?.take(180) ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    private fun copyTreeToDirectory(treeUri: Uri, destinationDir: File): Int {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val documentFile = DocumentFile.fromTreeUri(app, treeUri)
            ?: error("Cannot access the selected device folder")
        var copiedCount = 0
        val maxFiles = 3000

        fun copyTree(doc: DocumentFile, targetDir: File) {
            targetDir.mkdirs()
            val files = doc.listFiles()
            for (f in files) {
                val name = f.name ?: continue
                if (name in setOf(".git", ".gradle", "build", "node_modules", ".idea", ".DS_Store", "__pycache__", ".next")) {
                    continue
                }
                val destFile = File(targetDir, name)
                if (f.isDirectory) {
                    copyTree(f, destFile)
                } else if (f.isFile) {
                    if (copiedCount++ > maxFiles) break
                    app.contentResolver.openInputStream(f.uri)?.use { inStream ->
                        destFile.outputStream().use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }
            }
        }

        copyTree(documentFile, destinationDir)
        return copiedCount
    }

    private fun copyFolderToWorkspace(treeUri: Uri): Project {
        val app = getApplication<Application>()
        val documentFile = DocumentFile.fromTreeUri(app, treeUri)
            ?: error("Cannot access the selected device folder")
        val rawName = documentFile.name
        val folderName = if (rawName.isNullOrBlank()) "Device Project" else rawName.trim()
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val projectId = UUID.randomUUID().toString()
        val destination = File(app.filesDir, "workspaces/$projectId").apply { mkdirs() }

        copyTreeToDirectory(treeUri, destination)

        val preliminary = Project(
            id = projectId,
            name = folderName,
            description = "Imported from device storage",
            language = "General",
            slug = identity.slug,
            kind = ProjectKind.PROJECT,
        )
        val nestedRoot = detectNestedProjectRoot(preliminary)
        val projectRoot = nestedRoot?.let { File(destination, it) } ?: destination
        val metadata = detectImportedProjectMetadata(projectRoot)

        return preliminary.copy(
            description = metadata.first.ifBlank { "Local device repository" },
            language = metadata.second.ifBlank { "General" },
            rootPath = nestedRoot.orEmpty(),
        )
    }

    fun bindFolderToActiveProject(treeUri: Uri) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.projectImporting || current.isRunning || current.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Wait for active tasks to finish before binding a folder") }
            return
        }
        _state.update { it.copy(projectImporting = true, projectImportMessage = "Binding device folder to ${project.name}…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val destination = File(app.filesDir, "workspaces/${project.id}").apply { mkdirs() }
                    val copiedCount = copyTreeToDirectory(treeUri, destination)
                    val nestedRoot = detectNestedProjectRoot(project)
                    val projectRoot = nestedRoot?.let { File(destination, it) } ?: destination
                    val metadata = detectImportedProjectMetadata(projectRoot)
                    val updated = project.copy(
                        description = if (project.description == "Starter web project") metadata.first.ifBlank { project.description } else project.description,
                        language = if (project.language == "TypeScript" && metadata.second != "TypeScript") metadata.second else project.language,
                        rootPath = nestedRoot.orEmpty(),
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    updated to copiedCount
                }
            }
            result.onSuccess { (updated, count) ->
                configureBridgeRoots(updated.id, updated.rootPath)
                val projects = _state.value.projects.map { if (it.id == updated.id) updated else it }
                preferences.saveProjects(projects)
                val guestRoot = guestRootFor(updated)
                _state.update { state ->
                    state.copy(
                        projects = projects,
                        activeProject = updated,
                        projectImporting = false,
                        projectImportMessage = null,
                        projectTerminalCwd = guestRoot,
                        toastMessage = "Bound folder to ${updated.name} ($count files copied)",
                    )
                }
                refreshProjectFiles()
            }.onFailure { error ->
                _state.update { state ->
                    state.copy(
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "Failed to bind folder: ${error.message?.take(180) ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    fun setProjectRootDirectory(relativePath: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.projectTerminalRunning) return
        val normalized = relativePath.trim().removePrefix("/").removeSuffix("/")
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
        val target = if (normalized.isBlank()) base else File(base, normalized)
        if (!target.isDirectory || !target.canonicalFile.toPath().startsWith(base.canonicalFile.toPath())) {
            _state.update { it.copy(toastMessage = "Invalid directory path") }
            return
        }
        val updated = project.copy(rootPath = normalized, updatedAtMillis = System.currentTimeMillis())
        configureBridgeRoots(updated.id, updated.rootPath)
        val projects = current.projects.map { if (it.id == updated.id) updated else it }
        val guestRoot = guestRootFor(updated)
        preferences.saveProjects(projects)
        terminalCoordinator.saveProjectTerminal(updated.id, guestRoot, current.projectTerminalLines)
        _state.update {
            it.copy(
                projects = projects,
                activeProject = updated,
                suggestedProjectRoot = null,
                projectTerminalCwd = guestRoot,
                changes = emptyList(),
                toastMessage = if (normalized.isBlank()) "Workspace root is now the project root" else "$normalized is now the project working directory",
            )
        }
        refreshProjectFiles()
    }

    // ---- Milestone 1: repository clone moved to GitHubAuthCoordinator. ----
    private fun detectImportedProjectMetadata(root: File): Pair<String, String> {
        val names = root.walkTopDown().maxDepth(3).filter(File::isFile).map { it.name.lowercase() }.toSet()
        return when {
            names.any { it == "settings.gradle.kts" || it == "build.gradle.kts" } -> "Imported Gradle project" to "Kotlin"
            names.any { it == "settings.gradle" || it == "build.gradle" } -> "Imported Gradle project" to "Java"
            "package.json" in names && names.any { it == "tsconfig.json" || it.endsWith(".ts") || it.endsWith(".tsx") } -> "Imported web project" to "TypeScript"
            "package.json" in names -> "Imported web project" to "JavaScript"
            names.any { it == "pyproject.toml" || it == "requirements.txt" || it.endsWith(".py") } -> "Imported Python project" to "Python"
            names.any { it == "cargo.toml" || it.endsWith(".rs") } -> "Imported Rust project" to "Rust"
            names.any { it == "go.mod" || it.endsWith(".go") } -> "Imported Go project" to "Go"
            else -> "Imported ZIP project" to "General"
        }
    }

    fun clonePublicGitRepository(url: String) =
        gitHubAuthCoordinator.clonePublicGitRepository(url)

    fun cloneGitHubRepository(repository: GitHubRepository) =
        gitHubAuthCoordinator.cloneGitHubRepository(repository)
    // ---- Milestone 1: GitHub auth moved to GitHubAuthCoordinator. ----
    fun startGitHubLogin() =
        gitHubAuthCoordinator.startGitHubLogin()

    fun generateNewGitHubCode() =
        gitHubAuthCoordinator.generateNewGitHubCode()

    fun refreshGitHubRepositories() =
        gitHubAuthCoordinator.refreshGitHubRepositories()

    fun disconnectGitHub() =
        gitHubAuthCoordinator.disconnectGitHub()

    suspend fun refreshGitHubConnection() =
        gitHubAuthCoordinator.refreshGitHubConnection()
    fun renameProject(projectId: String, newName: String) =
        projectsCoordinator.renameProject(projectId, newName)

    fun deleteProject(projectId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        if (_state.value.activeProject?.id == projectId || _state.value.isRunning || _state.value.projectTerminalRunning) return
        _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == projectId }) }
        preferences.saveProjects(_state.value.projects)
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<Application>().filesDir
            File(filesDir, "workspaces/${project.id}").deleteRecursively()
            terminalCoordinator.deleteTerminalHistory(project.id)
            preferences.deleteProjectChats(project.id)
        }
    }

    private fun detectNestedProjectRoot(project: Project): String? {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
        if (!base.isDirectory) return null
        val visible = base.listFiles().orEmpty().filterNot { file ->
            file.name == ".claude" || file.name == ".claude.json"
        }
        val onlyDirectory = visible.singleOrNull()?.takeIf(File::isDirectory) ?: return null
        val containsProjectFiles = onlyDirectory.walkTopDown()
            .maxDepth(2)
            .any { it.isFile && it.name !in setOf(".DS_Store", ".claude.json") }
        return onlyDirectory.name.takeIf { containsProjectFiles && !it.contains("..") }
    }

    fun useSuggestedProjectRoot() {
        val current = _state.value
        val project = current.activeProject ?: return
        val root = current.suggestedProjectRoot ?: return
        if (current.isRunning || current.projectTerminalRunning) return
        val updated = project.copy(rootPath = root)
        configureBridgeRoots(updated.id, updated.rootPath)
        val projects = current.projects.map { if (it.id == updated.id) updated else it }
        val guestRoot = guestRootFor(updated)
        preferences.saveProjects(projects)
        terminalCoordinator.saveProjectTerminal(updated.id, guestRoot, current.projectTerminalLines)
        _state.update {
            it.copy(
                projects = projects,
                activeProject = updated,
                suggestedProjectRoot = null,
                projectTerminalCwd = guestRoot,
                changes = emptyList(),
                toastMessage = "$root is now the project root",
            )
        }
        refreshProjectFiles()
    }

    fun exportActiveProject(uri: Uri) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop the running task before exporting") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = projectWorkspaceRoot(project)
                    val rootPath = root.canonicalFile.toPath()
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("The selected location could not be opened")
                    output.buffered().use { stream ->
                        ZipOutputStream(stream).use { zip ->
                            zip.putNextEntry(ZipEntry("${project.slug}/"))
                            zip.closeEntry()
                            root.walkTopDown()
                                .onEnter { directory ->
                                    if (directory == root) {
                                        true
                                    } else {
                                        val relative = directory.relativeTo(root).invariantSeparatorsPath
                                        !isExportExcludedPath(relative) &&
                                            !Files.isSymbolicLink(directory.toPath()) &&
                                            runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                                    }
                                }
                                .drop(1)
                                .filter { file ->
                                    !Files.isSymbolicLink(file.toPath()) &&
                                        runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false) &&
                                        !isExportExcludedPath(file.relativeTo(root).invariantSeparatorsPath)
                                }
                                .forEach { file ->
                                    val relative = file.relativeTo(root).invariantSeparatorsPath
                                    val entryName = "${project.slug}/$relative" + if (file.isDirectory) "/" else ""
                                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                                    if (file.isFile) file.inputStream().buffered().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    toastMessage = result.fold(
                        onSuccess = { "${project.slug}.zip exported" },
                        onFailure = { error -> "Export failed: ${error.message ?: "Unknown error"}" },
                    ),
                )
            }
        }
    }

    fun setExecutionMode(mode: ExecutionMode) {
        preferences.executionMode = mode.name
        _state.update { it.copy(executionMode = mode) }
    }

    fun toggleScratchpadExpanded() {
        _state.update { it.copy(scratchpadExpanded = !it.scratchpadExpanded) }
    }

    fun addRoadmapComment(roadmapId: String, text: String, stepId: String? = null) {
        if (text.isBlank()) return
        val comment = RoadmapComment(
            id = UUID.randomUUID().toString(),
            author = "User",
            text = text.trim(),
            timestampMillis = System.currentTimeMillis(),
            stepId = stepId,
        )
        _state.update { current ->
            val updatedMessages = current.messages.map { msg ->
                if (msg.roadmap?.id == roadmapId) {
                    msg.copy(roadmap = msg.roadmap.copy(comments = msg.roadmap.comments + comment))
                } else {
                    msg
                }
            }
            val updatedActive = if (current.activeRoadmap?.id == roadmapId) {
                current.activeRoadmap.copy(comments = current.activeRoadmap.comments + comment)
            } else {
                current.activeRoadmap
            }
            current.copy(
                messages = updatedMessages,
                activeRoadmap = updatedActive,
            )
        }
        persistMessages()
    }

    fun approveAndBuildRoadmap(roadmap: ActiveRoadmap, additionalInstructions: String? = null) {
        val approved = roadmap.copy(isApproved = true)
        _state.update { current ->
            val updatedMessages = current.messages.map { msg ->
                if (msg.roadmap?.id == roadmap.id) {
                    msg.copy(roadmap = approved)
                } else {
                    msg
                }
            }
            current.copy(
                messages = updatedMessages,
                activeRoadmap = approved,
            )
        }
        persistMessages()

        val prompt = buildString {
            appendLine("<execution_mode name=\"UNIFIED_BUILD\">")
            appendLine("The user has APPROVED the roadmap and requested to begin building.")
            appendLine("Roadmap Title: ${roadmap.title}")
            if (roadmap.summary.isNotBlank()) {
                appendLine("Summary: ${roadmap.summary}")
            }
            appendLine("Approved Steps to execute:")
            roadmap.steps.forEachIndexed { idx, step ->
                appendLine("${idx + 1}. [ ] ${step.title}")
                if (step.description.isNotBlank()) {
                    appendLine("   Details: ${step.description}")
                }
                if (step.filesAffected.isNotEmpty()) {
                    appendLine("   Files: ${step.filesAffected.joinToString(", ")}")
                }
            }
            val allComments = roadmap.comments + (additionalInstructions?.takeIf(String::isNotBlank)?.let {
                listOf(RoadmapComment(text = it))
            } ?: emptyList())
            if (allComments.isNotEmpty()) {
                appendLine()
                appendLine("User Guidance & Instructions to strictly follow:")
                allComments.forEach { c ->
                    appendLine("- [${c.author}]: ${c.text}")
                }
            }
            appendLine("Start building now end-to-end. Implement code changes, build, verify, and complete all steps.")
            appendLine("Once finished, report verification details and conclude directly. Do NOT output a new roadmap or ask the user to Approve & Build again.")
            appendLine("</execution_mode>")
        }

        sendPrompt(prompt)
    }

    fun rollbackToBaseline(): Boolean {
        val proj = state.value.activeProject ?: return false
        val workspaceDir = File(getApplication<Application>().filesDir, "projects/${proj.id}")
        val success = agentOrchestrator.rollbackToBaseline(workspaceDir)
        if (success) {
            _state.update { it.copy(toastMessage = "Rolled back workspace to pre-task baseline") }
            refreshProjectFiles()
        } else {
            _state.update { it.copy(toastMessage = "Rollback failed: No baseline checkpoint available") }
        }
        return success
    }

    private fun deriveScratchpadItems(
        roadmap: ActiveRoadmap?,
        liveProcess: List<ActivityItem>,
        isRunning: Boolean,
    ): List<ScratchpadItem> = agentChatController.deriveScratchpadItems(roadmap, liveProcess, isRunning)

    fun createChat() {
        val project = _state.value.activeProject ?: return
        if (_state.value.isRunning) return
        persistMessages()
        val chat = ProjectChat()
        val chats = listOf(chat) + _state.value.projectChats
        preferences.saveProjectChats(project.id, chats)
        preferences.setLastActiveChatId(project.id, chat.id)
        _state.update {
            it.copy(
                projectChats = chats,
                activeChatId = chat.id,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                activeRoadmap = null,
                scratchpadItems = emptyList(),
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun switchChat(chatId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.activeChatId == chatId) return
        val chat = current.projectChats.firstOrNull { it.id == chatId } ?: return
        persistMessages()
        val saved = preferences.loadMessages(project.id, chat.id)
        val msgs = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) }
        val loadedRoadmap = msgs.mapNotNull { it.roadmap }.lastOrNull()
        val loadedScratchpad = deriveScratchpadItems(loadedRoadmap, emptyList(), false)
        preferences.setLastActiveChatId(project.id, chat.id)
        _state.update {
            it.copy(
                activeChatId = chat.id,
                messages = msgs,
                activeRoadmap = loadedRoadmap,
                scratchpadItems = loadedScratchpad,
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun renameChat(chatId: String, newTitle: String) {
        val project = _state.value.activeProject ?: return
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        preferences.renameProjectChat(project.id, chatId, trimmed)
        _state.update { current ->
            val updatedChats = current.projectChats.map {
                if (it.id == chatId) it.copy(title = trimmed) else it
            }
            current.copy(projectChats = updatedChats)
        }
    }

    fun deleteChat(chatId: String) {
        val project = _state.value.activeProject ?: return
        if (_state.value.isRunning || _state.value.projectChats.size <= 1) return
        preferences.deleteProjectChat(project.id, chatId)
        val remainingChats = preferences.loadProjectChats(project.id)
        if (_state.value.activeChatId == chatId) {
            val nextChat = remainingChats.firstOrNull() ?: return
            val saved = preferences.loadMessages(project.id, nextChat.id)
            val msgs = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) }
            val loadedRoadmap = msgs.mapNotNull { it.roadmap }.lastOrNull()
            val loadedScratchpad = deriveScratchpadItems(loadedRoadmap, emptyList(), false)
            preferences.setLastActiveChatId(project.id, nextChat.id)
            _state.update {
                it.copy(
                    projectChats = remainingChats,
                    activeChatId = nextChat.id,
                    messages = msgs,
                    activeRoadmap = loadedRoadmap,
                    scratchpadItems = loadedScratchpad,
                )
            }
        } else {
            _state.update { it.copy(projectChats = remainingChats) }
        }
    }

    fun refreshProjectFiles() {
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(filesLoading = true) }
        viewModelScope.launch {
            val (entries, suggestedRoot, androidProjectDetected) = withContext(Dispatchers.IO) {
                Triple(
                    readWorkspace(project),
                    if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null,
                    findAndroidGradleProjectRoot(projectWorkspaceRoot(project)) != null,
                )
            }
            if (_state.value.activeProject?.id == project.id) {
                _state.update {
                    it.copy(
                        workspaceFiles = entries,
                        filesLoading = false,
                        suggestedProjectRoot = suggestedRoot,
                        androidProjectDetected = androidProjectDetected,
                    )
                }
            }
        }
    }

    // ---- Milestone 1: editor state machine moved to EditorCoordinator. ----
    fun openFile(entry: WorkspaceEntry) =
        editorCoordinator.openFile(entry)

    fun openFileByPath(path: String) =
        editorCoordinator.openFileByPath(path)

    fun selectEditorTab(path: String) =
        editorCoordinator.selectEditorTab(path)

    fun closeEditorTab(path: String) =
        editorCoordinator.closeEditorTab(path)

    fun closeOtherEditorTabs(keepPath: String) =
        editorCoordinator.closeOtherEditorTabs(keepPath)

    fun closeAllEditorTabs() =
        editorCoordinator.closeAllEditorTabs()

    // Milestone 2 — dirty-buffer tracking and rename workflow delegates.
    fun onEditorContentChanged(path: String, content: String) =
        editorCoordinator.onBufferContentChanged(path, content)

    fun onEditorViewStateChanged(path: String, viewState: com.codeair.mhe.editor.EditorViewState) =
        editorCoordinator.onBufferViewStateChanged(path, viewState)

    fun onEditorSplitStateChanged(splitState: com.codeair.mhe.editor.EditorSplitState) =
        editorCoordinator.onSplitStateChanged(splitState)

    fun planRenameSymbol(oldName: String, newName: String): com.codeair.mhe.workspace.ReplacePreview? =
        editorCoordinator.planRenameSymbol(oldName, newName)

    fun applyRenameSymbol(
        preview: com.codeair.mhe.workspace.ReplacePreview,
        includeFiles: Set<String>? = null,
    ): String = editorCoordinator.applyRenameSymbol(preview, includeFiles)

    fun undoLastRename(): Boolean = editorCoordinator.undoLastRename()

    fun closeFile() =
        editorCoordinator.closeFile()

    fun createFile(relativePath: String, initialContent: String = "") {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.createFile(root, relativePath, initialContent)
        if (result.isSuccess) {
            refreshProjectFiles()
            openFileByPath(relativePath)
        } else {
            AppCrashLogger.log("Failed to create file: ${result.exceptionOrNull()?.message}")
        }
    }

    fun createFolder(relativePath: String) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.createDirectory(root, relativePath)
        if (result.isSuccess) {
            refreshProjectFiles()
        } else {
            AppCrashLogger.log("Failed to create directory: ${result.exceptionOrNull()?.message}")
        }
    }

    fun renameEntry(oldRelativePath: String, newRelativePath: String) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.renameEntry(root, oldRelativePath, newRelativePath)
        if (result.isSuccess) {
            val currentTabs = _state.value.openEditorTabs
            if (oldRelativePath in currentTabs) {
                val updatedTabs = currentTabs.map { if (it == oldRelativePath) newRelativePath else it }
                preferences.setOpenEditorTabs(project.id, updatedTabs)
                _state.update {
                    it.copy(
                        openEditorTabs = updatedTabs,
                        openedFilePath = if (it.openedFilePath == oldRelativePath) newRelativePath else it.openedFilePath,
                    )
                }
            }
            refreshProjectFiles()
        } else {
            AppCrashLogger.log("Failed to rename: ${result.exceptionOrNull()?.message}")
        }
    }

    fun deleteEntry(relativePath: String) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.deleteEntry(root, relativePath)
        if (result.isSuccess) {
            if (relativePath in _state.value.openEditorTabs) {
                closeEditorTab(relativePath)
            }
            refreshProjectFiles()
        } else {
            AppCrashLogger.log("Failed to delete: ${result.exceptionOrNull()?.message}")
        }
    }

    fun duplicateEntry(relativePath: String) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.duplicateEntry(root, relativePath)
        if (result.isSuccess) {
            val duplicate = result.getOrNull()
            refreshProjectFiles()
            if (duplicate != null) {
                val rel = duplicate.relativeTo(root).invariantSeparatorsPath
                openFileByPath(rel)
            }
        } else {
            AppCrashLogger.log("Failed to duplicate: ${result.exceptionOrNull()?.message}")
        }
    }

    fun moveEntry(sourceRelativePath: String, targetRelativePath: String) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.moveEntry(root, sourceRelativePath, targetRelativePath)
        if (result.isSuccess) {
            val moved = result.getOrNull()
            if (sourceRelativePath in _state.value.openEditorTabs && moved != null) {
                val newRel = moved.relativeTo(root).invariantSeparatorsPath
                val updatedTabs = _state.value.openEditorTabs.map { if (it == sourceRelativePath) newRel else it }
                val newOpenedPath = if (_state.value.openedFilePath == sourceRelativePath) newRel else _state.value.openedFilePath
                preferences.setOpenEditorTabs(project.id, updatedTabs)
                _state.update { it.copy(openEditorTabs = updatedTabs, openedFilePath = newOpenedPath) }
            }
            refreshProjectFiles()
        } else {
            AppCrashLogger.log("Failed to move: ${result.exceptionOrNull()?.message}")
            _state.update { it.copy(toastMessage = "Move failed: ${result.exceptionOrNull()?.message}") }
        }
    }

    // ---- Milestone 1: workspace Git commands moved to GitCoordinator. ----
    fun runWorkspaceGitCommand(args: List<String>, onResult: (Boolean, String) -> Unit = { _, _ -> }) =
        gitCoordinator.runWorkspaceGitCommand(args, onResult)

    fun gitStatus(onResult: (String) -> Unit) =
        gitCoordinator.gitStatus(onResult)

    fun gitDiff(onResult: (String) -> Unit) =
        gitCoordinator.gitDiff(onResult)

    fun gitBranchList(onResult: (List<String>) -> Unit) =
        gitCoordinator.gitBranchList(onResult)

    fun gitCheckout(branch: String, createNew: Boolean = false, onResult: (Boolean, String) -> Unit = { _, _ -> }) =
        gitCoordinator.gitCheckout(branch, createNew, onResult)

    fun gitCommit(message: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) =
        gitCoordinator.gitCommit(message, onResult)

    fun gitPush(remote: String = "origin", branch: String = "", onResult: (Boolean, String) -> Unit = { _, _ -> }) =
        gitCoordinator.gitPush(remote, branch, onResult)

    fun gitPull(remote: String = "origin", branch: String = "", onResult: (Boolean, String) -> Unit = { _, _ -> }) =
        gitCoordinator.gitPull(remote, branch, onResult)
    fun scaffoldProjectTemplate(template: com.codeair.mhe.workspace.ProjectTemplate) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.scaffoldTemplate(root, template)
        if (result.isSuccess) {
            _state.update { it.copy(toastMessage = "Scaffolded ${template.displayName} starter template") }
            refreshProjectFiles()
        }
    }

    // ---- Milestone 1: port-detection state moved to VisualCoordinator. ----


    private fun loadFileContent(project: Project, path: String) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                val file = File(projectWorkspaceRoot(project), path)
                runCatching {
                    if (file.length() > 512_000L) {
                        file.inputStream().use { stream ->
                            val buf = ByteArray(512_000)
                            val read = stream.read(buf)
                            String(buf, 0, read)
                        } + "\n\n[File truncated — too large to display fully]"
                    } else {
                        file.readText()
                    }
                }.getOrElse { "Could not read file: ${it.message}" }
            }
            if (_state.value.openedFilePath == path) {
                _state.update { it.copy(openedFileContent = content, fileContentLoading = false) }
            }
        }
    }

    /**
     * Persists an edited workspace file as an explicit, reviewable change.
     * A checkpoint is created before the first manual edit so the existing
     * Changes tab can Undo/Keep the edit using the same durable mechanism as
     * agent-generated changes.
     */
    suspend fun saveFile(path: String, content: String): Boolean =
        editorCoordinator.saveFile(path, content)

    private fun readWorkspace(project: Project): List<WorkspaceEntry> {
        val root = projectWorkspaceRoot(project)
        if (!root.isDirectory) return emptyList()
        val rootPath = root.canonicalFile.toPath()
        return root.walkTopDown()
            .maxDepth(12)
            .onEnter { directory ->
                val relative = if (directory == root) "" else directory.relativeTo(root).invariantSeparatorsPath
                directory == root || (!isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(directory.toPath()) &&
                    runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .drop(1)
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                !isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .take(MAX_VISIBLE_WORKSPACE_ENTRIES)
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                WorkspaceEntry(
                    path = relative,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    depth = relative.count { it == '/' },
                    sizeBytes = if (file.isFile) file.length() else 0,
                )
            }
            .sortedWith(compareBy<WorkspaceEntry> { it.path.lowercase() }.thenByDescending { it.isDirectory })
            .toList()
    }

    // Milestone 1: shared with EditorCoordinator — see coordinators package.

    private fun isExportExcludedPath(relativePath: String): Boolean {
        val excludedNames = setOf(
            ".git", ".claude", ".gradle", ".idea", ".next", ".cache",
            "node_modules", ".venv", "venv", "__pycache__", "build",
        )
        return relativePath.split('/').any { it in excludedNames } || isClaudeRuntimeMetadata(relativePath)
    }

    fun addChatAttachments(uris: List<Uri>) {
        val current = _state.value
        val project = current.activeProject ?: return
        val chatId = current.activeChatId ?: return
        if (current.isRunning || uris.isEmpty()) return
        val remaining = (MAX_ATTACHMENTS_PER_MESSAGE - current.pendingAttachments.size).coerceAtLeast(0)
        if (remaining == 0) {
            _state.update { it.copy(toastMessage = "You can attach up to $MAX_ATTACHMENTS_PER_MESSAGE files per message") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val added = mutableListOf<ChatAttachment>()
                val errors = mutableListOf<String>()
                uris.take(remaining).forEach { uri ->
                    runCatching { copyChatAttachment(project, chatId, uri) }
                        .onSuccess(added::add)
                        .onFailure { errors += (it.message ?: "Could not attach file") }
                }
                added to errors
            }
            val (added, errors) = result
            _state.update { state ->
                state.copy(
                    pendingAttachments = state.pendingAttachments + added,
                    toastMessage = errors.firstOrNull() ?: if (uris.size > remaining) "Only $remaining more file${if (remaining == 1) "" else "s"} could be added" else null,
                )
            }
            if (added.isNotEmpty()) refreshProjectFiles()
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        val attachment = current.pendingAttachments.firstOrNull { it.id == attachmentId } ?: return
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { item -> item.id == attachmentId }) }
        viewModelScope.launch(Dispatchers.IO) {
            val root = projectWorkspaceRoot(project)
            val file = File(root, attachment.relativePath).canonicalFile
            if (file.toPath().startsWith(root.canonicalFile.toPath())) file.delete()
        }
    }

    fun openChatAttachment(attachment: ChatAttachment) {
        val project = _state.value.activeProject ?: return
        runCatching {
            val root = projectWorkspaceRoot(project).canonicalFile
            val file = File(root, attachment.relativePath).canonicalFile
            require(file.isFile && file.toPath().startsWith(root.toPath())) { "Attachment is unavailable" }
            val app = getApplication<Application>()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mimeType.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure { error ->
            _state.update { it.copy(toastMessage = error.message ?: "No app can open this attachment") }
        }
    }

    private fun copyChatAttachment(project: Project, chatId: String, uri: Uri): ChatAttachment {
        val resolver = getApplication<Application>().contentResolver
        var displayName = "attachment"
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { displayName = cursor.getString(it) ?: displayName }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { declaredSize = cursor.getLong(it) }
            }
        }
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val supportedTextExtensions = setOf(
            "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "log",
            "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm",
            "css", "scss", "sass", "less", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "zsh",
            "gradle", "properties", "toml", "ini", "conf", "sql", "pdf", "out", "err", "env",
        )
        val isPdf = mimeType == "application/pdf" || extension == "pdf"
        val isNoExtension = !displayName.contains('.') || extension.isBlank()
        val isLogOrText = mimeType.startsWith("text/") || extension in supportedTextExtensions ||
            isNoExtension || mimeType == "application/octet-stream" || mimeType.contains("log") || extension.contains("log")
        val supported = mimeType.startsWith("image/") || isPdf ||
            mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "application/xml" ||
            mimeType.endsWith("+json") || mimeType.endsWith("+xml") || isLogOrText
        require(supported) { "Only images, PDF documents, and text or log files are supported" }
        require(declaredSize <= MAX_ATTACHMENT_BYTES || declaredSize < 0) { "$displayName is larger than 25 MB" }
        val safeName = sanitizeAttachmentName(displayName)
        val root = projectWorkspaceRoot(project).canonicalFile
        val folder = File(root, "attachments/$chatId").apply { mkdirs() }.canonicalFile
        require(folder.toPath().startsWith(root.toPath())) { "Unsafe attachment folder" }
        val stem = safeName.substringBeforeLast('.', safeName)
        val safeExtension = safeName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var destination = File(folder, safeName)
        var suffix = 2
        while (destination.exists()) destination = File(folder, "$stem-${suffix++}$safeExtension")
        var copied = 0L
        try {
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_ATTACHMENT_BYTES) { "$displayName is larger than 25 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Could not read $displayName")
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        return ChatAttachment(
            displayName = displayName.take(120),
            relativePath = destination.relativeTo(root).invariantSeparatorsPath,
            mimeType = mimeType,
            sizeBytes = copied,
        )
    }

    private fun sanitizeAttachmentName(name: String): String =
        agentChatController.sanitizeAttachmentName(name)

    fun sendPrompt(prompt: String) {
        val project = state.value.activeProject ?: return
        if (_state.value.agentKind == AgentKind.ANTIGRAVITY &&
            _state.value.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update { it.copy(toastMessage = "Sign in to Antigravity from Settings before starting a task.") }
            return
        }
        if (_state.value.agentKind == AgentKind.DEEPSEEK_HARNESS && _state.value.provider.kind == ProviderKind.CLAUDE) {
            _state.update { it.copy(toastMessage = "Claude subscription login is not supported by DeepSeek Harness — pick a key-based provider in Settings.") }
            return
        }
        val attachments = state.value.pendingAttachments
        if ((prompt.isBlank() && attachments.isEmpty()) || state.value.isRunning) return
        val requestText = prompt.trim().ifBlank { "Please review the attached files." }
        val mentionedPaths = resolveMentionedWorkspacePaths(prompt, project)
        updateActiveChatTitle(requestText)
        val mode = _state.value.executionMode
        val modeInstruction = agentChatController.buildModeInstruction(mode, requestText)
        _state.update {
            val startedAt = System.currentTimeMillis()
            val thinkItem = ActivityItem("Think", requestPlanningSummary(requestText, it.agentKind), false)
            val initialScratchpad = deriveScratchpadItems(it.activeRoadmap, listOf(thinkItem), true)
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = prompt.trim(), attachments = attachments),
                pendingAttachments = emptyList(),
                isRunning = true,
                activity = listOf(ActivityItem("Understanding your request", "Preparing a safe plan", false)) + it.activity,
                liveProcess = listOf(thinkItem),
                scratchpadItems = initialScratchpad,
                liveThinking = true,
                activeThinkingBlockId = null,
                taskStartedAtMillis = startedAt,
                taskFinishedAtMillis = null,
                workSegmentStartedAtMillis = startedAt,
                currentTaskRequest = requestText,
            )
        }
        val activeProj = state.value.activeProject
        if (activeProj != null) {
            val workspaceDir = File(getApplication<Application>().filesDir, "projects/${activeProj.id}")
            agentOrchestrator.startTask(
                projectId = activeProj.id,
                workspaceDir = workspaceDir,
                userGoal = requestText,
                plan = emptyList(),
            )
        }
        agentSessionController.startSession(
            sessionId = UUID.randomUUID().toString(),
            projectId = project.id,
            projectTitle = project.name,
            chatId = _state.value.activeChatId.orEmpty(),
            prompt = requestText,
            roadmap = _state.value.activeRoadmap,
        )
        touchProject(project.id)
        persistMessages()
        val history = state.value.messages // includes all messages up to now
        val runtimePrompt = agentChatController.buildEnrichedPrompt(
            requestText = requestText,
            modeInstruction = modeInstruction,
            mentionedPaths = mentionedPaths,
            attachments = attachments,
            guestRoot = guestRootFor(project),
            skillContext = skillManager.buildPromptContext(state.value.agentKind),
        )
        failedApiKeyIds.clear()
        activeRuntimeRequest = RuntimeRetryRequest(
            runtime = activeRuntime(),
            project = project,
            prompt = runtimePrompt,
            history = history,
            provider = state.value.provider,
        )
        lastInterruptedRequest = activeRuntimeRequest
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                getApplication<Application>(),
                Intent(getApplication<Application>(), RuntimeExecutionService::class.java).apply {
                    action = RuntimeExecutionService.ACTION_START
                    putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                    putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, _state.value.agentKind.title)
                    putExtra(RuntimeExecutionService.EXTRA_DETAIL, "${_state.value.agentKind.title} is starting work in ${project.name}")
                },
            )
        }
        MobileHarnessApplication.appScope.launch {
            val request = activeRuntimeRequest ?: return@launch
            val workspace = withContext(Dispatchers.IO) {
                val root = projectWorkspaceRoot(request.project)
                skillManager.syncToWorkspace(root)
                root
            }
            val enrichedPrompt = withContext(Dispatchers.IO) {
                val context = contextEngine.buildPromptContext(
                    projectId = request.project.id,
                    workspace = workspace,
                    request = requestText,
                    mentionedPaths = mentionedPaths,
                    maxReferences = 12,
                )
                if (context.isBlank()) request.prompt else request.prompt + "\n\n" + context
            }
            val enriched = request.copy(prompt = enrichedPrompt)
            activeRuntimeRequest = enriched
            lastInterruptedRequest = enriched
            runtimeRecoveryStartedAtMillis = null
            runtimeRecoveryAttempt = 0
            enriched.runtime.startSession(
                enriched.project.id,
                enriched.project.slug,
                enriched.project.kind,
                enriched.prompt,
                enriched.history,
                enriched.provider,
            )
        }
    }

    private fun resolveMentionedWorkspacePaths(prompt: String, project: Project): List<String> =
        agentChatController.resolveMentionedWorkspacePaths(prompt, projectWorkspaceRoot(project))
    fun answerApproval(approved: Boolean) {
        answerApprovalWithLevel(com.codeair.mhe.security.PermissionLevel.ONCE, approved)
    }

    fun answerApprovalWithLevel(level: com.codeair.mhe.security.PermissionLevel, approved: Boolean) {
        val request = state.value.pendingApproval ?: return
        val project = _state.value.activeProject
        val target = request.affectedPaths.firstOrNull() ?: request.toolName
        val category = when {
            request.toolName.contains("bash", true) || request.toolName.contains("exec", true) -> com.codeair.mhe.security.PermissionCategory.SHELL_EXEC
            request.toolName.contains("write", true) || request.toolName.contains("edit", true) -> com.codeair.mhe.security.PermissionCategory.FILE_WRITE
            request.toolName.contains("delete", true) || request.toolName.contains("remove", true) -> com.codeair.mhe.security.PermissionCategory.FILE_DELETE
            else -> com.codeair.mhe.security.PermissionCategory.FILE_READ
        }
        if (approved) {
            permissionPolicyEngine.grantPermission(
                category = category,
                level = level,
                targetPattern = target,
                projectId = project?.slug ?: project?.id,
                sessionId = _state.value.activeSessionId
            )
        }
        _state.update { it.copy(pendingApproval = null) }
        MobileHarnessApplication.appScope.launch { activeRuntime().respondToApproval(request, approved) }
    }

    fun stopTask() {
        if (!_state.value.isRunning && !_state.value.runtimeRecoveryCanResume) return
        runtimeRecoveryJob?.cancel()
        runtimeRecoveryJob = null
        runtimeRecoveryStartedAtMillis = null
        runtimeRecoveryAttempt = 0
        activeRuntimeRequest = null
        lastInterruptedRequest = null
        _state.update { it.copy(isRunning = false, runtimeRecoveryStatus = null, runtimeRecoveryAttempt = 0, runtimeRecoveryCanResume = false) }
        MobileHarnessApplication.appScope.launch { activeRuntime().stopActiveSession() }
    }

    fun undoLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val restored = activeRuntime().undoLastChanges(project.id)
            _state.update { current ->
                current.copy(
                    changes = if (restored) emptyList() else current.changes,
                    activity = listOf(
                        ActivityItem(
                            if (restored) "Changes undone" else "Undo unavailable",
                            if (restored) "Restored files to their state before the task" else "No restorable checkpoint was found",
                        ),
                    ) + current.activity,
                )
            }
            if (restored) refreshProjectFiles()
        }
    }

    fun keepLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            activeRuntime().acceptLastChanges(project.id)
            _state.update {
                it.copy(
                    changes = emptyList(),
                    activity = listOf(ActivityItem("Changes kept", "Accepted the task's file changes")) + it.activity,
                )
            }
        }
    }

    fun undoFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (activeRuntime().undoFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
                refreshProjectFiles()
            }
        }
    }

    fun keepFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (activeRuntime().acceptFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
            }
        }
    }

    private fun isNoisyRuntimeItem(item: ActivityItem): Boolean =
        agentChatController.isNoisyRuntimeItem(item)

    private fun toolPlanSummary(toolName: String, detail: String): String =
        agentChatController.toolPlanSummary(toolName, detail)

    private fun requestPlanningSummary(
        request: String,
        agentKind: AgentKind,
        toolName: String? = null,
        detail: String = "",
    ): String = agentChatController.requestPlanningSummary(request, agentKind.title, toolName, detail)

    private fun finishWorkSegment(current: AppUiState, finishedAt: Long = System.currentTimeMillis()): AppUiState {
        val (updatedMessages, _) = agentChatController.finishWorkSegment(
            messages = current.messages,
            liveProcess = current.liveProcess,
            liveThinking = current.liveThinking,
            workSegmentStartedAtMillis = current.workSegmentStartedAtMillis,
            taskStartedAtMillis = current.taskStartedAtMillis,
            finishedAt = finishedAt,
        )
        return current.copy(
            messages = updatedMessages,
            liveProcess = emptyList(),
            liveThinking = false,
            activeThinkingBlockId = null,
            workSegmentStartedAtMillis = null,
        )
    }

    /** Adds the complete request duration to the response produced after the latest user message. */
    private fun attachTaskDuration(current: AppUiState, finishedAt: Long): AppUiState =
        current.copy(messages = agentChatController.attachTaskDuration(current.messages, current.taskStartedAtMillis, finishedAt))

    private fun appendWorkItem(current: AppUiState, item: ActivityItem): AppUiState {
        if (isNoisyRuntimeItem(item)) return current
        return current.copy(
            liveProcess = current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } + item,
            liveThinking = false,
            workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
        )
    }

    private fun onRuntimeEvent(event: RuntimeEvent) {
        val lastUserIdx = _state.value.messages.indexOfLast { it.fromUser }
        val hasResponseAlready = _state.value.messages.indices.any {
            it > lastUserIdx && !_state.value.messages[it].fromUser && _state.value.messages[it].text.isNotBlank()
        }
        if (event is RuntimeEvent.SessionFailed && retryAntigravityWithNextAccount(event)) return
        if (event is RuntimeEvent.SessionFailed && _state.value.agentKind == AgentKind.ANTIGRAVITY &&
            (event.reason.contains("sign-in", true) || event.reason.contains("authentication", true) || event.reason.contains("401", true))) {
            antigravityAuthController.invalidateSession(event.reason)
        }
        if (event is RuntimeEvent.SessionFailed && !hasResponseAlready && retryWithNextApiKey(event)) return
        if (event is RuntimeEvent.SessionFailed && !hasResponseAlready && recoverTransientRuntimeFailure(event)) return
        _state.update { current ->
            if (!current.isRunning) {
                current
            } else if (current.activeSessionId != null && current.activeSessionId != event.sessionId) {
                current
            } else when (event) {
                is RuntimeEvent.SessionStarted -> {
                    runtimeRecoveryJob?.cancel()
                    runtimeRecoveryJob = null
                    runtimeRecoveryAttempt = 0
                    current.copy(
                        activeSessionId = event.sessionId,
                        runtimeRecoveryStatus = null,
                        runtimeRecoveryCanResume = false,
                        activity = current.activity.mapIndexed { index, item -> if (index == 0) item.copy(isComplete = true) else item },
                    )
                }
                is RuntimeEvent.AssistantDelta -> {
                    val timeline = if (current.liveThinking || current.liveProcess.any { !isNoisyRuntimeItem(it) }) {
                        finishWorkSegment(current)
                    } else {
                        current
                    }
                    timeline.copy(messages = agentChatController.appendAssistantDelta(timeline.messages, event.text))
                }
                is RuntimeEvent.ReasoningProgress -> {
                    val existingIndex = current.liveProcess.indexOfLast { it.title == "Think" }
                    // The request-level Think summary is seeded once in sendPrompt.
                    // After that segment has been committed to the timeline, later
                    // agent turns must not repeat the same request summary.
                    if (existingIndex < 0) return@update current
                    val reasoning = ActivityItem(
                        title = "Think",
                        detail = current.liveProcess.getOrNull(existingIndex)?.detail
                            ?: requestPlanningSummary(current.currentTaskRequest.orEmpty(), current.agentKind),
                        isComplete = false,
                    )
                    val process = if (existingIndex >= 0) {
                        current.liveProcess.toMutableList().also { it[existingIndex] = reasoning }
                    } else {
                        current.liveProcess + reasoning
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = true,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ReasoningSummary -> {
                    val summary = event.summary.trim()
                    val process = current.liveProcess.toMutableList()
                    val existingIndex = process.indexOfLast { !it.isComplete && it.title == "Think" }
                    if (event.startsNewBlock) {
                        process.indices.forEach { index ->
                            if (!process[index].isComplete) process[index] = process[index].copy(isComplete = true)
                        }
                        val initial = summary.ifBlank { "Thinking…" }
                        val replaceFallback = current.activeThinkingBlockId == null &&
                            process.size == 1 && process.first().title == "Think"
                        if (replaceFallback) {
                            process[0] = ActivityItem("Think", initial, event.isFinal)
                        } else {
                            process += ActivityItem("Think", initial, event.isFinal)
                        }
                    } else if (current.activeThinkingBlockId == event.blockId && existingIndex >= 0 && summary.isNotBlank()) {
                        process[existingIndex] = process[existingIndex].copy(
                            detail = summary,
                            isComplete = event.isFinal,
                        )
                    } else {
                        return@update current
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = !event.isFinal,
                        activeThinkingBlockId = if (event.isFinal) null else event.blockId,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ToolStarted -> {
                    val process = current.liveProcess.map { item ->
                        if (!item.isComplete) item.copy(isComplete = true) else item
                    }
                    val toolItem = ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash")
                    val updatedProcess = process + toolItem
                    val scratchpad = deriveScratchpadItems(current.activeRoadmap, updatedProcess, true)
                    val planned = current.copy(
                        liveProcess = process,
                        scratchpadItems = scratchpad,
                        liveThinking = false,
                        activeThinkingBlockId = null,
                        activity = listOf(
                            ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                        ) + current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                    )
                    appendWorkItem(
                        planned,
                        toolItem,
                    )
                }
                is RuntimeEvent.RuntimeLog -> appendWorkItem(
                    current.copy(activity = listOf(ActivityItem(event.title, event.detail)) + current.activity),
                    ActivityItem(event.title, event.detail),
                )
                is RuntimeEvent.ToolRequested -> appendWorkItem(current.copy(
                    pendingApproval = event.request,
                    activity = listOf(ActivityItem("Waiting for approval", event.request.explanation, false)) + current.activity,
                ), ActivityItem("Waiting for approval", event.request.explanation, false))
                is RuntimeEvent.ToolApproved -> appendWorkItem(current.copy(
                    pendingApproval = null,
                    activity = listOf(ActivityItem("Applying approved changes", "Editing project files", false)) + current.activity,
                ), ActivityItem("Action approved", "${current.agentKind.title} is continuing the task", false))
                is RuntimeEvent.ToolRejected -> appendWorkItem(current.copy(
                    pendingApproval = null,
                ), ActivityItem("Action rejected", "${current.agentKind.title} will continue without this action"))
                is RuntimeEvent.ToolCompleted -> {
                    val runningIndex = current.liveProcess.indexOfLast {
                        !it.isComplete && it.title == "Running ${event.toolName}"
                    }
                    val process = if (runningIndex >= 0) {
                        current.liveProcess.toMutableList().also { items ->
                            val runningItem = items[runningIndex]
                            items[runningIndex] = ActivityItem(
                                "${event.toolName} completed",
                                runningItem.detail.ifBlank { event.summary },
                                isCommand = event.toolName == "Bash",
                            )
                        }
                    } else {
                        current.liveProcess + ActivityItem(
                            "${event.toolName} completed",
                            event.summary,
                            isCommand = event.toolName == "Bash",
                        )
                    }
                    val scratchpad = deriveScratchpadItems(current.activeRoadmap, process, true)
                    current.copy(
                        activity = listOf(ActivityItem(event.summary, event.toolName)) + current.activity,
                        liveProcess = process,
                        scratchpadItems = scratchpad,
                        liveThinking = false,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.FilesChanged -> current.copy(
                    changes = event.changes,
                    liveThinking = false,
                    liveProcess = if (event.paths.isEmpty()) current.liveProcess else current.liveProcess +
                        ActivityItem(
                            "Files changed",
                            event.paths.take(4).joinToString(", ") + if (event.paths.size > 4) " +${event.paths.size - 4} more" else "",
                        ),
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.PreviewStarted -> current.copy(
                    previewReady = true,
                    previewUrl = event.url,
                    activity = listOf(ActivityItem("Preview ready", event.url)) + current.activity,
                    liveProcess = current.liveProcess + ActivityItem("Preview ready", event.url),
                    liveThinking = false,
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.SessionCompleted -> {
                    val finishedAt = System.currentTimeMillis()
                    val totalDurationText = current.taskStartedAtMillis?.let { start ->
                        formatDurationText(((finishedAt - start) / 1000L).coerceAtLeast(1L))
                    } ?: "a few seconds"
                    runtimeRecoveryStartedAtMillis = null
                    val segmentFinishedState = finishWorkSegment(current, finishedAt)
                    val stateWithResponse = ensureCompletionMessage(segmentFinishedState, totalDurationText)
                    val completedActiveRoadmap = current.activeRoadmap?.let { rm ->
                        if (rm.isApproved) {
                            rm.copy(steps = rm.steps.map { if (it.status != StepStatus.SKIPPED) it.copy(status = StepStatus.COMPLETED) else it })
                        } else {
                            rm
                        }
                    }
                    val messagesWithRoadmap = stateWithResponse.messages.map { msg ->
                        if (msg.roadmap?.id == current.activeRoadmap?.id && completedActiveRoadmap != null) {
                            msg.copy(roadmap = completedActiveRoadmap)
                        } else if (!msg.fromUser && msg.roadmap == null && msg.text.isNotBlank() &&
                            current.executionMode != ExecutionMode.BUILD) {
                            val parsed = RoadmapParser.parseFromText(msg.text, stateWithResponse.executionMode)
                            if (parsed != null) msg.copy(roadmap = parsed) else msg
                        } else {
                            msg
                        }
                    }
                    val newlyParsedRoadmap = messagesWithRoadmap.mapNotNull { it.roadmap }.lastOrNull()
                    val latestRoadmap = if (current.executionMode != ExecutionMode.BUILD && newlyParsedRoadmap != null && newlyParsedRoadmap.id != current.activeRoadmap?.id) {
                        newlyParsedRoadmap
                    } else {
                        completedActiveRoadmap ?: newlyParsedRoadmap ?: current.activeRoadmap
                    }
                    val scratchpad = deriveScratchpadItems(latestRoadmap, emptyList(), false)
                    agentSessionController.completeSession("Task completed successfully")
                    agentOrchestrator.transition(com.codeair.mhe.session.EngineeringState.COMPLETED, "Task completed successfully")
                    attachTaskDuration(stateWithResponse.copy(messages = messagesWithRoadmap), finishedAt).copy(
                        isRunning = false,
                        activeSessionId = null,
                        activeRoadmap = latestRoadmap,
                        scratchpadItems = scratchpad,
                        activity = listOf(ActivityItem("Task completed", "${current.agentKind.title} finished successfully in $totalDurationText")) +
                            current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                        taskFinishedAtMillis = finishedAt,
                        currentTaskRequest = null,
                        runtimeRecoveryStatus = null,
                        runtimeRecoveryAttempt = 0,
                        runtimeRecoveryCanResume = false,
                    )
                }
                is RuntimeEvent.SessionFailed -> {
                    agentSessionController.failSession(event.reason)
                    agentOrchestrator.transition(com.codeair.mhe.session.EngineeringState.FAILED, "Task failed", event.reason)
                    val lastUserIndex = current.messages.indexOfLast { it.fromUser }
                    val hasResponseForLastUser = current.messages.indices.any {
                        it > lastUserIndex && !current.messages[it].fromUser && current.messages[it].text.isNotBlank()
                    }
                    if (hasResponseForLastUser && !event.reason.contains("Stopped by user", ignoreCase = true)) {
                        // The assistant already completed and delivered its response for the current user turn.
                        // Trailing exit code or socket teardown must not fail a completed task or append "Task stopped".
                        val finishedAt = System.currentTimeMillis()
                        val totalDurationText = current.taskStartedAtMillis?.let { start ->
                            formatDurationText(((finishedAt - start) / 1000L).coerceAtLeast(1L))
                        } ?: "a few seconds"
                        runtimeRecoveryStartedAtMillis = null
                        val segmentFinishedState = finishWorkSegment(current, finishedAt)
                        val completedActiveRoadmap = current.activeRoadmap?.let { rm ->
                            if (rm.isApproved) {
                                rm.copy(steps = rm.steps.map { if (it.status != StepStatus.SKIPPED) it.copy(status = StepStatus.COMPLETED) else it })
                            } else {
                                rm
                            }
                        }
                        val messagesWithRoadmap = segmentFinishedState.messages.map { msg ->
                            if (msg.roadmap?.id == current.activeRoadmap?.id && completedActiveRoadmap != null) {
                                msg.copy(roadmap = completedActiveRoadmap)
                            } else if (!msg.fromUser && msg.roadmap == null && msg.text.isNotBlank() &&
                                current.executionMode != ExecutionMode.BUILD) {
                                val parsed = RoadmapParser.parseFromText(msg.text, segmentFinishedState.executionMode)
                                if (parsed != null) msg.copy(roadmap = parsed) else msg
                            } else {
                                msg
                            }
                        }
                        val failedNewlyParsedRoadmap = messagesWithRoadmap.mapNotNull { it.roadmap }.lastOrNull()
                        val latestRoadmap = if (current.executionMode != ExecutionMode.BUILD && failedNewlyParsedRoadmap != null && failedNewlyParsedRoadmap.id != current.activeRoadmap?.id) {
                            failedNewlyParsedRoadmap
                        } else {
                            completedActiveRoadmap ?: failedNewlyParsedRoadmap ?: current.activeRoadmap
                        }
                        val scratchpad = deriveScratchpadItems(latestRoadmap, emptyList(), false)
                        attachTaskDuration(segmentFinishedState.copy(messages = messagesWithRoadmap), finishedAt).copy(
                            isRunning = false,
                            activeSessionId = null,
                            activeRoadmap = latestRoadmap,
                            scratchpadItems = scratchpad,
                            activity = listOf(ActivityItem("Task completed", "${current.agentKind.title} finished successfully in $totalDurationText")) +
                                current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                            taskFinishedAtMillis = finishedAt,
                            currentTaskRequest = null,
                            runtimeRecoveryStatus = null,
                            runtimeRecoveryAttempt = 0,
                            runtimeRecoveryCanResume = false,
                        )
                    } else {
                        val finishedAt = System.currentTimeMillis()
                        runtimeRecoveryStartedAtMillis = null
                        val scratchpad = deriveScratchpadItems(current.activeRoadmap, emptyList(), false)
                        attachTaskDuration(
                            finishWorkSegment(
                                appendWorkItem(current, ActivityItem("Task stopped", event.reason)),
                                finishedAt,
                            ),
                            finishedAt,
                        ).copy(
                            isRunning = false,
                            activeSessionId = null,
                            pendingApproval = null,
                            scratchpadItems = scratchpad,
                            toastMessage = sanitizeSessionFailedToast(event.reason),
                            activity = listOf(ActivityItem("Task stopped", event.reason)) + current.activity,
                            taskFinishedAtMillis = finishedAt,
                            currentTaskRequest = null,
                            runtimeRecoveryStatus = null,
                            runtimeRecoveryAttempt = 0,
                            runtimeRecoveryCanResume = false,
                        )
                    }
                }
            }
        }
        if (event is RuntimeEvent.SessionCompleted || (event is RuntimeEvent.SessionFailed && !_state.value.runtimeRecoveryCanResume)) {
            activeRuntimeRequest = null
            if (event is RuntimeEvent.SessionCompleted) {
                lastInterruptedRequest = null
            }
            failedApiKeyIds.clear()
        }
        if (event is RuntimeEvent.FilesChanged || event is RuntimeEvent.SessionCompleted ||
            (event is RuntimeEvent.SessionFailed && hasResponseAlready)) {
            _state.value.activeProject?.id?.let { touchProject(it) }
            refreshProjectFiles()
        }
        // Save every visible reasoning/tool transition, not only assistant text and
        // final results. If Android kills the process, the last displayed timeline
        // is restored as an interrupted work block rather than disappearing.
        persistMessages(includeLiveProcess = true)
    }

    private fun ensureCompletionMessage(state: AppUiState, totalDurationText: String): AppUiState =
        state.copy(messages = agentChatController.ensureCompletionMessage(state.messages, totalDurationText))

    private fun recoverTransientRuntimeFailure(event: RuntimeEvent.SessionFailed): Boolean {
        val current = _state.value
        val request = activeRuntimeRequest ?: lastInterruptedRequest ?: return false
        if (!current.isRunning || (current.activeSessionId != null && current.activeSessionId != event.sessionId)) return false
        if (!RuntimeFailureClassifier.isTransientNetworkFailure(event.reason)) return false

        val now = System.currentTimeMillis()
        val recoveryStartedAt = runtimeRecoveryStartedAtMillis ?: now.also { runtimeRecoveryStartedAtMillis = it }
        val elapsedMillis = (now - recoveryStartedAt).coerceAtLeast(0L)
        val maxDurationMillis = runtimeRetryPolicy.maxTotalRetryDurationMillis

        if (elapsedMillis >= maxDurationMillis || runtimeRecoveryAttempt >= runtimeRetryPolicy.maxAutomaticRetries) {
            val friendlyError = "Network connection could not be restored after 3 minutes of retries. Your task and changes are preserved. Please check your internet connection and tap Resume."
            runtimeRecoveryJob?.cancel()
            runtimeRecoveryJob = null
            runtimeRecoveryStartedAtMillis = null
            runtimeRecoveryAttempt = 0
            val finishedAt = System.currentTimeMillis()
            _state.update { state ->
                attachTaskDuration(
                    finishWorkSegment(
                        appendWorkItem(state, ActivityItem("Task stopped", friendlyError)),
                        finishedAt,
                    ),
                    finishedAt,
                ).copy(
                    isRunning = false,
                    activeSessionId = null,
                    runtimeRecoveryStatus = "Connection lost (3m timeout)",
                    runtimeRecoveryCanResume = true,
                    activity = listOf(ActivityItem("Task stopped", friendlyError)) + state.activity,
                    taskFinishedAtMillis = finishedAt,
                    currentTaskRequest = null,
                )
            }
            persistMessages(includeLiveProcess = true)
            NotificationCoordinator.postResult(
                getApplication<Application>(),
                "Task paused · Network error",
                friendlyError,
                true,
            )
            runCatching {
                val intent = Intent(getApplication<Application>(), RuntimeExecutionService::class.java).apply {
                    action = RuntimeExecutionService.ACTION_CANCELLED
                }
                getApplication<Application>().startService(intent)
            }
            return true
        }

        runtimeRecoveryJob?.cancel()
        runtimeRecoveryAttempt += 1
        val attempt = runtimeRecoveryAttempt
        val retryDelay = runtimeRetryPolicy.delayMillis(attempt, kotlin.random.Random.nextDouble())
            .coerceAtMost((maxDurationMillis - elapsedMillis).coerceAtLeast(1_000L))
        val remainingSeconds = (((maxDurationMillis - elapsedMillis) / 1000L).coerceAtLeast(1L))
        val statusText = "Reconnecting… (${remainingSeconds}s remaining)"

        _state.update { it.copy(
            activeSessionId = null,
            runtimeRecoveryStatus = statusText,
            runtimeRecoveryAttempt = attempt,
            runtimeRecoveryCanResume = false,
            liveProcess = it.liveProcess + ActivityItem(
                "Connection interrupted",
                "Network disturbance detected. Reconnecting… (${remainingSeconds}s remaining, attempt $attempt)",
            ),
        ) }
        persistMessages(includeLiveProcess = true)

        runCatching {
            val progressIntent = Intent(getApplication<Application>(), RuntimeExecutionService::class.java).apply {
                action = RuntimeExecutionService.ACTION_PROGRESS
                putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, request.project.slug)
                putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, current.agentKind.title)
                putExtra(RuntimeExecutionService.EXTRA_DETAIL, "${current.agentKind.title} reconnecting network… (${remainingSeconds}s remaining)")
            }
            getApplication<Application>().startService(progressIntent)
        }

        runtimeRecoveryJob = MobileHarnessApplication.appScope.launch {
            delay(retryDelay)
            if (!_state.value.isRunning || (activeRuntimeRequest !== request && lastInterruptedRequest !== request)) return@launch
            val updatedElapsed = System.currentTimeMillis() - recoveryStartedAt
            val currentRemaining = (((maxDurationMillis - updatedElapsed) / 1000L).coerceAtLeast(1L))
            _state.update { it.copy(
                runtimeRecoveryStatus = "Reconnecting… (${currentRemaining}s remaining)",
                liveProcess = it.liveProcess + ActivityItem("Reconnecting", "Attempting to re-establish connection…"),
            ) }
            runCatching {
                request.runtime.startSession(request.project.id, request.project.slug, request.project.kind, request.prompt, request.history, request.provider)
            }.onFailure { error ->
                val failureReason = RuntimeFailureClassifier.friendlyNetworkErrorMessage(error.message.orEmpty())
                onRuntimeEvent(RuntimeEvent.SessionFailed(event.sessionId, failureReason))
            }
        }
        return true
    }

    fun resumeInterruptedTask() {
        val prompt = activeRuntimeRequest?.prompt
            ?: lastInterruptedRequest?.prompt
            ?: _state.value.currentTaskRequest
            ?: _state.value.messages.lastOrNull { it.fromUser }?.text
            ?: return
        resumeTaskExecution(prompt = prompt, roadmap = _state.value.activeRoadmap)
    }

    private fun resumeTaskExecution(prompt: String, roadmap: ActiveRoadmap? = null) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning) return

        runtimeRecoveryJob?.cancel()
        runtimeRecoveryJob = null
        runtimeRecoveryAttempt = 0
        runtimeRecoveryStartedAtMillis = null

        val lastUserMsg = current.messages.lastOrNull { it.fromUser }
        val resumePrompt = prompt.ifBlank {
            current.currentTaskRequest ?: lastUserMsg?.text.orEmpty()
        }
        if (resumePrompt.isBlank()) return

        // Strip trailing stopped/error messages so chat resumes cleanly
        val cleanedMessages = current.messages.filterNot { msg ->
            !msg.fromUser && msg.text.contains("Task stopped", ignoreCase = true)
        }

        val targetRoadmap = roadmap ?: current.activeRoadmap
        val isRoadmapBuild = targetRoadmap != null && targetRoadmap.isApproved &&
            targetRoadmap.steps.any { it.status == StepStatus.PENDING || it.status == StepStatus.IN_PROGRESS }

        val effectivePrompt = if (isRoadmapBuild && !resumePrompt.contains("<execution_mode")) {
            buildString {
                appendLine("<execution_mode name=\"UNIFIED_BUILD\">")
                appendLine("Resuming previously interrupted roadmap execution.")
                appendLine("Roadmap Title: ${targetRoadmap.title}")
                if (targetRoadmap.summary.isNotBlank()) {
                    appendLine("Summary: ${targetRoadmap.summary}")
                }
                appendLine("Steps to execute:")
                targetRoadmap.steps.forEachIndexed { idx, step ->
                    val statusMark = if (step.status == StepStatus.COMPLETED) "[x]" else "[ ]"
                    appendLine("${idx + 1}. $statusMark ${step.title}")
                    if (step.description.isNotBlank()) {
                        appendLine("   Details: ${step.description}")
                    }
                    if (step.filesAffected.isNotEmpty()) {
                        appendLine("   Files: ${step.filesAffected.joinToString(", ")}")
                    }
                }
                appendLine("Continue building now end-to-end. Implement remaining code changes, build, verify, and complete all steps.")
                appendLine("Once finished, report verification details and conclude directly. Do NOT output a new roadmap or ask the user to Approve & Build again.")
                appendLine("</execution_mode>")
            }
        } else {
            resumePrompt
        }

        val startedAt = System.currentTimeMillis()
        val thinkItem = ActivityItem("Think", "Resuming task: " + requestPlanningSummary(effectivePrompt, current.agentKind), false)
        val initialScratchpad = deriveScratchpadItems(targetRoadmap, listOf(thinkItem), true)

        _state.update { state ->
            state.copy(
                messages = cleanedMessages,
                isRunning = true,
                liveThinking = true,
                activeSessionId = null,
                runtimeRecoveryStatus = null,
                runtimeRecoveryAttempt = 0,
                runtimeRecoveryCanResume = false,
                interruptedSession = null,
                liveProcess = listOf(thinkItem),
                scratchpadItems = initialScratchpad,
                activeThinkingBlockId = null,
                taskStartedAtMillis = startedAt,
                taskFinishedAtMillis = null,
                workSegmentStartedAtMillis = startedAt,
                currentTaskRequest = effectivePrompt,
                activity = listOf(ActivityItem("Resuming task", "Reconnecting and continuing work…", false)) + state.activity,
            )
        }

        val activeProj = state.value.activeProject
        if (activeProj != null) {
            val workspaceDir = File(getApplication<Application>().filesDir, "projects/${activeProj.id}")
            agentOrchestrator.startTask(
                projectId = activeProj.id,
                workspaceDir = workspaceDir,
                userGoal = effectivePrompt,
                plan = targetRoadmap?.steps?.map { it.title } ?: emptyList(),
            )
        }
        agentSessionController.startSession(
            sessionId = UUID.randomUUID().toString(),
            projectId = project.id,
            projectTitle = project.name,
            chatId = _state.value.activeChatId.orEmpty(),
            prompt = effectivePrompt,
            roadmap = targetRoadmap,
        )
        touchProject(project.id)
        persistMessages(includeLiveProcess = true)

        val mode = _state.value.executionMode
        val modeInstruction = when {
            mode == ExecutionMode.PLAN -> """
<execution_mode name="PLAN">
You are running in PLAN mode.
Your objective: Inspect the project workspace and formulate a comprehensive, actionable execution plan without making any changes to files or code yet.
</execution_mode>
""".trimIndent()
            mode == ExecutionMode.BUILD -> """
<execution_mode name="BUILD">
You are running in BUILD mode.
Your objective: Directly understand the user request, plan internally, and immediately begin implementing all required changes end-to-end.
- Read relevant files, edit code, execute build/test commands, verify changes, and report progress.
- Once finished, summarize what was accomplished with full verification details.
</execution_mode>
""".trimIndent()
            mode == ExecutionMode.UNIFIED -> """
<execution_mode name="UNIFIED">
You are running in UNIFIED mode (Phase 1: Roadmap & Planning).
Your objective: Analyze the request and workspace, formulate the plan, and provide an interactive roadmap for user review and approval before any implementation begins.
</execution_mode>
""".trimIndent()
            else -> ""
        }

        val attachments = lastUserMsg?.attachments.orEmpty()
        val mentionedPaths = resolveMentionedWorkspacePaths(effectivePrompt, project)

        val runtimePrompt = buildString {
            appendLine(effectivePrompt)
            if (modeInstruction.isNotBlank() && !effectivePrompt.contains("<execution_mode")) {
                appendLine()
                appendLine(modeInstruction)
            }
            if (mentionedPaths.isNotEmpty() && !effectivePrompt.contains("<mentioned_files>")) {
                appendLine()
                appendLine("<mentioned_files>")
                appendLine("The user explicitly referenced these workspace files with @mentions. Inspect the referenced files before deciding what to change; they are context pointers, not permission to modify other files.")
                mentionedPaths.forEach { relativePath ->
                    appendLine("- " + guestRootFor(project) + "/" + relativePath)
                }
                appendLine("</mentioned_files>")
            }
            if (attachments.isNotEmpty() && !effectivePrompt.contains("<attached_files>")) {
                appendLine()
                appendLine("<attached_files>")
                attachments.forEach { attachment ->
                    appendLine("- ${attachment.displayName}: ${guestRootFor(project)}/${attachment.relativePath} (${attachment.mimeType})")
                }
                appendLine("These files were explicitly attached by the user. Inspect them only as needed for the request.")
                appendLine("</attached_files>")
            }
            val skillContext = skillManager.buildPromptContext(_state.value.agentKind)
            if (skillContext.isNotBlank() && !effectivePrompt.contains(skillContext.take(30))) {
                appendLine()
                appendLine(skillContext)
            }
        }

        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                getApplication<Application>(),
                Intent(getApplication<Application>(), RuntimeExecutionService::class.java).apply {
                    action = RuntimeExecutionService.ACTION_START
                    putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                    putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, _state.value.agentKind.title)
                    putExtra(RuntimeExecutionService.EXTRA_DETAIL, "${_state.value.agentKind.title} is resuming task in ${project.name}")
                },
            )
        }

        MobileHarnessApplication.appScope.launch {
            val workspace = withContext(Dispatchers.IO) {
                val root = projectWorkspaceRoot(project)
                skillManager.syncToWorkspace(root)
                root
            }
            val enrichedPrompt = withContext(Dispatchers.IO) {
                val context = contextEngine.buildPromptContext(
                    projectId = project.id,
                    workspace = workspace,
                    request = effectivePrompt,
                    mentionedPaths = mentionedPaths,
                    maxReferences = 12,
                )
                if (context.isBlank()) runtimePrompt else runtimePrompt + "\n\n" + context
            }
            val runtime = activeRuntime()
            val request = RuntimeRetryRequest(
                runtime = runtime,
                project = project,
                prompt = enrichedPrompt,
                history = cleanedMessages,
                provider = _state.value.provider,
            )
            activeRuntimeRequest = request
            lastInterruptedRequest = request
            runCatching {
                runtime.startSession(
                    project.id,
                    project.slug,
                    project.kind,
                    enrichedPrompt,
                    cleanedMessages,
                    _state.value.provider,
                )
            }.onFailure { error ->
                val failureReason = RuntimeFailureClassifier.friendlyNetworkErrorMessage(error.message.orEmpty())
                onRuntimeEvent(RuntimeEvent.SessionFailed(UUID.randomUUID().toString(), failureReason))
            }
        }
    }
    private fun retryWithNextApiKey(event: RuntimeEvent.SessionFailed): Boolean {
        val current = _state.value
        if (current.agentKind == AgentKind.ANTIGRAVITY) return false
        if (!current.isRunning || current.activeSessionId != event.sessionId) return false
        if (!isApiKeyFailure(event.reason)) return false
        val request = activeRuntimeRequest ?: return false
        val credentials = vault.credentials(request.provider.kind.name)
        val active = credentials.firstOrNull { it.isActive } ?: return false
        failedApiKeyIds += active.id
        val next = credentials.firstOrNull { it.id !in failedApiKeyIds } ?: return false
        if (!vault.activate(request.provider.kind.name, next.id)) return false
        _state.update {
            it.copy(
                activeSessionId = null,
                runtimeRecoveryStatus = null,
                runtimeRecoveryAttempt = 0,
                runtimeRecoveryCanResume = false,
                activeApiKeyName = next.name,
                toastMessage = "${active.name} failed. Switched to ${next.name}.",
                liveProcess = it.liveProcess + ActivityItem("API key switched", "Using ${next.name}", true),
            )
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            request.runtime.startSession(
                request.project.id,
                request.project.slug,
                request.project.kind,
                request.prompt,
                request.history,
                request.provider,
            )
        }
        return true
    }

    private fun isApiKeyFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "api key" in value || "authentication" in value || "user not found" in value ||
            "http 401" in value || "http 403" in value || "http 429" in value ||
            "expired" in value || "quota" in value || "rate limit" in value
    }

    private fun sanitizeSessionFailedToast(reason: String): String? {
        val value = reason.lowercase()
        return when {
            "unauthenticated" in value || "401" in value || "invalid authentication credentials" in value ||
                "oauth 2 access token" in value || "token expired" in value ->
                "Google session expired (401). Reconnect your account in Settings → Google accounts."
            "user not found" in value ->
                "Account not found. Please sign in again."
            "api key" in value ->
                "API key authentication error. Check credentials in Settings."
            "out of credits" in value || "quota" in value || "429" in value ->
                "Usage quota limit reached. Auto-switching or check plan limits."
            "authentication" in value || "sign-in" in value ->
                "Authentication failed. Reconnect your account in Settings."
            else -> null
        }
    }

    private fun isAntigravityAuthFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "unauthenticated" in value ||
            "401" in value ||
            "invalid authentication credentials" in value ||
            "oauth 2 access token" in value ||
            "login cookie" in value ||
            "authentication required" in value ||
            "authentication failed" in value ||
            "auth expired" in value ||
            "sign-in" in value ||
            "not signed in" in value ||
            "token expired" in value
    }

    private fun isAntigravityQuotaFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "quota" in value ||
            "rate limit" in value ||
            "ratelimit" in value ||
            "resource_exhausted" in value ||
            "resource exhausted" in value ||
            "429" in value ||
            "out of credits" in value ||
            "credits" in value ||
            "limit exceeded" in value ||
            "exhausted" in value ||
            "too many requests" in value ||
            "60 minute" in value ||
            "minute limit" in value ||
            "hourly limit" in value ||
            "hour limit" in value ||
            "daily limit" in value ||
            "day limit" in value ||
            "weekly limit" in value ||
            "week limit" in value ||
            "usage limit" in value ||
            "plan limit" in value ||
            "account limit" in value ||
            "capacity" in value ||
            "overloaded" in value ||
            "quota_exceeded" in value ||
            "rate_limit_exceeded" in value ||
            "reached your limit" in value ||
            "hit your limit" in value ||
            "exceeded your limit" in value ||
            "exceeded your current quota" in value ||
            "insufficient quota" in value ||
            "free tier limit" in value ||
            "tokens per minute" in value ||
            "requests per minute" in value ||
            "requests per day" in value ||
            "requests per week" in value ||
            "billing" in value ||
            "credit balance" in value ||
            "quota limit" in value
    }

    private fun isAntigravityFailoverEligible(reason: String): Boolean {
        return isAntigravityQuotaFailure(reason) || isAntigravityAuthFailure(reason)
    }

    private fun retryAntigravityWithNextAccount(event: RuntimeEvent.SessionFailed): Boolean {
        val current = _state.value
        if (current.agentKind != AgentKind.ANTIGRAVITY) return false
        if (!current.isRunning || current.activeSessionId != event.sessionId) return false
        if (!antigravityAuthController.isAutoRoundRobin()) return false
        if (!isAntigravityFailoverEligible(event.reason)) return false
        val request = activeRuntimeRequest ?: return false

        val currentEmail = antigravityAuthController.state.value.accountEmail.orEmpty()
        val isAuthError = isAntigravityAuthFailure(event.reason)
        if (isAuthError) {
            antigravityAuthController.markCurrentAccountAuthExpired()
        } else {
            antigravityAuthController.markCurrentAccountQuotaExhausted()
        }
        val nextEmail = antigravityAuthController.switchToNextAccount() ?: return false

        // Clean up any trailing/interrupted assistant message chunk from the failed session
        val cleanedMessages = _state.value.messages.let { msgs ->
            val lastUserIdx = msgs.indexOfLast { it.fromUser }
            if (lastUserIdx >= 0 && msgs.size > lastUserIdx + 1 && !msgs.last().fromUser) {
                msgs.dropLast(1)
            } else msgs
        }

        val reasonLabel = if (isAuthError) "session expired" else "usage limit reached"
        val failoverLabel = if (isAuthError) "auth expired failover" else "quota limit failover"
        _state.update {
            it.copy(
                activeSessionId = null,
                runtimeRecoveryStatus = null,
                runtimeRecoveryAttempt = 0,
                runtimeRecoveryCanResume = false,
                messages = cleanedMessages,
                toastMessage = if (currentEmail.isNotBlank()) "Account $currentEmail $reasonLabel. Smartly switched to $nextEmail." else "Smartly switched to account $nextEmail.",
                liveProcess = it.liveProcess + ActivityItem("Google account switched", "Auto round-robin to $nextEmail ($failoverLabel)", true),
            )
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            request.runtime.startSession(
                request.project.id,
                request.project.slug,
                request.project.kind,
                request.prompt,
                request.history,
                request.provider,
            )
        }
        return true
    }

    // ---- Milestone 1: skills domain moved to SkillsCoordinator. ----
    fun refreshSkills() =
        skillsCoordinator.refreshSkills()

    fun checkSkillUpdates() =
        skillsCoordinator.checkSkillUpdates()

    fun updateSkill(name: String) =
        skillsCoordinator.updateSkill(name)

    fun searchSkills(query: String) =
        skillsCoordinator.searchSkills(query)

    fun importSkillFromGitHub(url: String) =
        skillsCoordinator.importSkillFromGitHub(url)

    fun importSkillFromUri(uri: Uri) =
        skillsCoordinator.importSkillFromUri(uri)

    fun removeSkill(name: String) =
        skillsCoordinator.removeSkill(name)

    // Milestone 1: restored — these were accidentally dropped by the
    // Security/MCP/Skills extraction (commit b4cd244); bodies verbatim
    // from the pre-extraction file.
    private fun touchProject(projectId: String) {
        val now = System.currentTimeMillis()
        _state.update { current ->
            val updatedProjects = current.projects.map { p ->
                if (p.id == projectId) p.copy(updatedAtMillis = now) else p
            }
            val active = if (current.activeProject?.id == projectId) current.activeProject?.copy(updatedAtMillis = now) else current.activeProject
            current.copy(projects = updatedProjects, activeProject = active)
        }
        preferences.saveProjects(_state.value.projects)
    }

    private fun persistMessages(includeLiveProcess: Boolean = true) {
        val current = _state.value
        val project = current.activeProject ?: return
        val chatId = current.activeChatId ?: return
        val liveItems = if (includeLiveProcess) current.liveProcess.filterNot(::isNoisyRuntimeItem) else emptyList()
        val messages = if (liveItems.isEmpty() && !current.liveThinking) {
            current.messages
        } else {
            val startedAt = current.workSegmentStartedAtMillis ?: current.taskStartedAtMillis ?: System.currentTimeMillis()
            current.messages + ChatMessage(
                id = "interrupted-${current.activeSessionId ?: chatId}",
                fromUser = false,
                text = "",
                workItems = liveItems.map { it.copy(isComplete = true) } + ActivityItem(
                    "Task interrupted",
                    "The agent process stopped before reporting completion. Continue this chat to resume its official session.",
                ),
                workedMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            )
        }
        transcriptWrites.trySend(TranscriptWrite(project.id, chatId, messages))
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            _state.update { state -> state.copy(toastMessage = "Could not open the browser. Copy the URL instead.") }
        }
    }

    private fun updateActiveChatTitle(prompt: String) {
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId ?: return
        val now = System.currentTimeMillis()
        val title = prompt.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= 42) it else it.take(39).trimEnd() + "…"
        }
        _state.update { current ->
            val chats = current.projectChats.map { chat ->
                if (chat.id == chatId) {
                    chat.copy(
                        title = if (chat.title == "New chat") title else chat.title,
                        updatedAtMillis = now,
                    )
                } else chat
            }.sortedByDescending { it.updatedAtMillis }
            current.copy(projectChats = chats)
        }
        preferences.saveProjectChats(project.id, _state.value.projectChats)
    }

    fun toggleAutoUpdateToolsAndSkills(enabled: Boolean) =
        skillsCoordinator.toggleAutoUpdateToolsAndSkills(enabled)

    fun autoUpdateToolsAndSkills(force: Boolean = false) =
        skillsCoordinator.autoUpdateToolsAndSkills(force)
    fun dismissInterruptedSession() {
        sessionManager.clearActiveSession()
        _state.update { it.copy(interruptedSession = null) }
    }

    fun resumeInterruptedSession() {
        val session = _state.value.interruptedSession ?: return
        val project = _state.value.projects.firstOrNull { it.id == session.projectId } ?: return
        openProject(project)
        if (session.chatId.isNotBlank()) {
            switchChat(session.chatId)
        }
        _state.update { it.copy(interruptedSession = null) }
        sessionManager.clearActiveSession()

        val prompt = session.initialPrompt.ifBlank {
            _state.value.messages.lastOrNull { it.fromUser }?.text.orEmpty()
        }
        if (prompt.isNotBlank()) {
            resumeTaskExecution(prompt = prompt, roadmap = session.activeRoadmap)
        }
    }

    fun createWorkspaceCheckpoint(label: String): com.codeair.mhe.workspace.WorkspaceCheckpoint? =
        projectsCoordinator.createWorkspaceCheckpoint(label)

    fun rollbackWorkspaceCheckpoint(checkpointId: String): Boolean =
        projectsCoordinator.rollbackWorkspaceCheckpoint(checkpointId)

    fun listWorkspaceCheckpoints(): List<com.codeair.mhe.workspace.WorkspaceCheckpoint> =
        projectsCoordinator.listWorkspaceCheckpoints()

    fun backupAllWorkspaces(): Int =
        projectsCoordinator.backupAllWorkspaces()

    fun restoreAllWorkspaces(): Int =
        projectsCoordinator.restoreAllWorkspaces()

    fun reloadProjects() =
        projectsCoordinator.reloadProjects()

    fun runAutonomousVerification() {
        val project = _state.value.activeProject ?: return
        val workspace = projectWorkspaceRoot(project)
        val tool = com.codeair.mhe.agent.AutonomousLoopEngine.detectBuildTool(workspace)
        if (tool == null) {
            _state.update { it.copy(toastMessage = "No recognized build tool (Gradle/npm/Cargo/Go/Python) found in project") }
            return
        }
        _state.update { it.copy(toastMessage = "Running autonomous verification with ${tool.name}...") }
        terminalCoordinator.requestProjectTerminalCommand(tool.verificationCommand)
    }

    // ---- Milestone 1: visual-inspection state moved to VisualCoordinator. ----
    // ---- Milestone 1: visual-inspection domain moved to VisualCoordinator. ----
    fun recordVisualConsoleLog(entry: com.codeair.mhe.visual.ConsoleLogEntry) =
        visualCoordinator.recordVisualConsoleLog(entry)

    fun recordVisualNetworkLog(entry: com.codeair.mhe.visual.NetworkLogEntry) =
        visualCoordinator.recordVisualNetworkLog(entry)

    fun setInspectedDomElement(element: com.codeair.mhe.visual.DomElementInfo?) =
        visualCoordinator.setInspectedDomElement(element)

    fun clearVisualLogs() =
        visualCoordinator.clearVisualLogs()

    fun setViewportPreset(preset: com.codeair.mhe.visual.ViewportPreset) =
        visualCoordinator.setViewportPreset(preset)

    fun scanActiveLocalhostPorts(onComplete: (List<Int>) -> Unit = {}) =
        visualCoordinator.scanActiveLocalhostPorts(onComplete)

    fun scanLocalhostPortsWithHeuristics(
        customPorts: List<Int> = emptyList(),
        onComplete: (List<com.codeair.mhe.visual.LocalhostPortInfo>) -> Unit = {},
    ) = visualCoordinator.scanLocalhostPortsWithHeuristics(customPorts, onComplete)

    fun saveVisualSnapshot(
        bitmap: android.graphics.Bitmap,
        title: String,
        url: String,
        onSaved: (com.codeair.mhe.visual.VisualSnapshot) -> Unit = {},
    ) = visualCoordinator.saveVisualSnapshot(bitmap, title, url, onSaved)

    fun setBaselineComparisonSnapshot(snapshot: com.codeair.mhe.visual.VisualSnapshot) =
        visualCoordinator.setBaselineComparisonSnapshot(snapshot)

    fun setAfterComparisonSnapshot(snapshot: com.codeair.mhe.visual.VisualSnapshot) =
        visualCoordinator.setAfterComparisonSnapshot(snapshot)

    fun dispatchAiVisualBugReport(
        url: String,
        userNotes: String = "",
        snapshot: com.codeair.mhe.visual.VisualSnapshot? = null,
        onDispatched: () -> Unit = {},
    ) = visualCoordinator.dispatchAiVisualBugReport(url, userNotes, snapshot, onDispatched)

    fun attachVisualInspectionToAgent(url: String, note: String = "") =
        visualCoordinator.attachVisualInspectionToAgent(url, note)
    // ---- Milestone 1: App Factory domain moved to AppFactoryCoordinator. ----
    fun generateAppFactoryBlueprint(
        prompt: String,
        framework: com.codeair.mhe.factory.model.AppFramework = com.codeair.mhe.factory.model.AppFramework.ANDROID_COMPOSE,
        buildMode: com.codeair.mhe.factory.model.BuildMode = com.codeair.mhe.factory.model.BuildMode.ON_DEVICE,
        archetype: com.codeair.mhe.factory.blueprint.AppFactoryBlueprintEngine.Archetype? = null,
    ) = appFactoryCoordinator.generateAppFactoryBlueprint(prompt, framework, buildMode, archetype)

    fun scaffoldAppFactoryProject(
        targetDirectory: File? = null,
        blueprint: com.codeair.mhe.factory.model.AppBlueprint? = _state.value.appFactoryBlueprint
    ) = appFactoryCoordinator.scaffoldAppFactoryProject(targetDirectory, blueprint)

    fun buildAppFactoryApp(
        artifactType: com.codeair.mhe.factory.model.BuildArtifactType = com.codeair.mhe.factory.model.BuildArtifactType.APK_DEBUG,
        buildMode: com.codeair.mhe.factory.model.BuildMode = com.codeair.mhe.factory.model.BuildMode.ON_DEVICE,
        targetDirectory: File? = null
    ) = appFactoryCoordinator.buildAppFactoryApp(artifactType, buildMode, targetDirectory)

    fun installBuiltApk(apkFile: File) =
        appFactoryCoordinator.installBuiltApk(apkFile)

    fun generateReleaseKeystore(
        targetDirectory: File? = null,
        alias: String = "releaseKey",
        pass: String = "pocketdev123"
    ) = appFactoryCoordinator.generateReleaseKeystore(targetDirectory, alias, pass)

    fun bumpAppVersion(
        targetDirectory: File? = null,
        bumpType: com.codeair.mhe.factory.model.VersionBumpType = com.codeair.mhe.factory.model.VersionBumpType.PATCH
    ) = appFactoryCoordinator.bumpAppVersion(targetDirectory, bumpType)

    fun preparePlayStoreListing(
        targetDirectory: File? = null,
        blueprint: com.codeair.mhe.factory.model.AppBlueprint? = _state.value.appFactoryBlueprint
    ) = appFactoryCoordinator.preparePlayStoreListing(targetDirectory, blueprint)
    // ---- Milestone 1: Git Studio moved to GitCoordinator. ----
    fun refreshGitStudio(targetDirectory: File? = null) =
        gitCoordinator.refreshGitStudio(targetDirectory)

    fun gitStageFile(path: String, targetDirectory: File? = null) =
        gitCoordinator.gitStageFile(path, targetDirectory)

    fun gitStageAll(targetDirectory: File? = null) =
        gitCoordinator.gitStageAll(targetDirectory)

    fun gitUnstageFile(path: String, targetDirectory: File? = null) =
        gitCoordinator.gitUnstageFile(path, targetDirectory)

    fun gitUnstageAll(targetDirectory: File? = null) =
        gitCoordinator.gitUnstageAll(targetDirectory)

    fun gitDiscardFile(path: String, targetDirectory: File? = null) =
        gitCoordinator.gitDiscardFile(path, targetDirectory)

    fun gitDiscardAll(targetDirectory: File? = null) =
        gitCoordinator.gitDiscardAll(targetDirectory)

    fun gitCommitDetailed(message: String, amend: Boolean = false, signOff: Boolean = false, targetDirectory: File? = null) =
        gitCoordinator.gitCommitDetailed(message, amend, signOff, targetDirectory)

    fun gitCreateBranch(name: String, targetDirectory: File? = null) =
        gitCoordinator.gitCreateBranch(name, targetDirectory)

    fun gitCheckoutBranch(name: String, targetDirectory: File? = null) =
        gitCoordinator.gitCheckoutBranch(name, targetDirectory)

    fun gitDeleteBranch(name: String, targetDirectory: File? = null) =
        gitCoordinator.gitDeleteBranch(name, targetDirectory)

    fun gitMergeBranch(branch: String, targetDirectory: File? = null) =
        gitCoordinator.gitMergeBranch(branch, targetDirectory)

    fun gitRebaseBranch(upstream: String, targetDirectory: File? = null) =
        gitCoordinator.gitRebaseBranch(upstream, targetDirectory)

    fun gitResetBranch(hash: String, mode: com.codeair.mhe.git.model.GitResetMode, targetDirectory: File? = null) =
        gitCoordinator.gitResetBranch(hash, mode, targetDirectory)
    // ---- Milestone 1: GitHub issues/PRs moved to GitCoordinator. ----
    fun createGitHubIssue(title: String, body: String = "") =
        gitCoordinator.createGitHubIssue(title, body)

    fun mergeGitHubPullRequest(prNumber: Int, method: com.codeair.mhe.network.PullRequestMergeMethod = com.codeair.mhe.network.PullRequestMergeMethod.SQUASH) =
        gitCoordinator.mergeGitHubPullRequest(prNumber, method)
    private fun resolveGitHubToken(): String {
        return try {
            val (exit, output) = gitHubAuthCoordinator.runGitHubCli(listOf("auth", "token"))
            output.trim().takeIf { exit == 0 && it.isNotBlank() }.orEmpty()
        } catch (e: Exception) {
            AppCrashLogger.log("MainViewModel.resolveGitHubToken: ${e.message}")
            ""
        }
    }

    /**
     * Sends a prompt through the real agent pipeline and suspends until that
     * request genuinely ends (completed, failed, or timed out). This replaces the
     * old fire-and-forget callback: the returned [OperationResult] completes only
     * when the agent request ends, and it is FAILED unless the run verifiably
     * completed.
     */
    private suspend fun runAgentPromptAwaitingResult(prompt: String): OperationResult {
        val startedAt = System.currentTimeMillis()
        if (_state.value.activeProject == null) {
            return OperationResult.failure(
                error = "No active project for the autonomous agent run.",
                startedAt = startedAt,
            )
        }
        if (_state.value.isRunning) {
            return OperationResult.failure(
                error = "Another agent task is already running; refusing to overlap autonomous runs.",
                startedAt = startedAt,
            )
        }
        sendPrompt(prompt)
        // sendPrompt() silently no-ops when its guards fail; verify the task started.
        delay(1500)
        val launched = _state.value.isRunning || _state.value.currentTaskRequest != null
        if (!launched) {
            return OperationResult.failure(
                error = "Agent task did not start (agent sign-in or provider key missing?).",
                startedAt = startedAt,
            )
        }
        val timeoutMs = 30L * 60L * 1000L
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshot = _state.value
            val finishedAt = snapshot.taskFinishedAtMillis
            if (!snapshot.isRunning && finishedAt != null && finishedAt >= startedAt) break
            val approval = snapshot.pendingApproval
            if (approval != null) {
                return OperationResult.failure(
                    error = "Autonomous run paused for user approval (tool: ${approval.toolName}). " +
                        "Autonomous mode cannot approve on its own.",
                    startedAt = startedAt,
                )
            }
            delay(2000)
        }
        val end = _state.value
        val finishedAt = end.taskFinishedAtMillis
        if (end.isRunning || finishedAt == null || finishedAt < startedAt) {
            return OperationResult.failure(
                error = "Agent task timed out after ${timeoutMs / 60000} minutes without completing.",
                startedAt = startedAt,
            )
        }
        val headline = end.activity.firstOrNull()
        return if (headline != null && headline.title.equals("Task completed", ignoreCase = true)) {
            OperationResult.success(
                verificationEvidence = "Agent task finished (${headline.detail.ifBlank { "completed" }})",
                startedAt = startedAt,
            )
        } else {
            OperationResult.failure(
                error = "Agent task ended without success: ${headline?.title ?: "unknown"} " +
                    (headline?.detail?.take(300) ?: ""),
                startedAt = startedAt,
            )
        }
    }

    /**
     * Runs a REAL Gradle build + unit tests against the workspace using the
     * installed Linux runtime. Returns the genuine exit code and captured logs —
     * never a canned success.
     */
    private suspend fun runRealGradleBuildAndTest(
        workingDir: File,
        guestWorkspacePath: String,
    ): OperationResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        if (!installer.isInstalled()) {
            return@withContext OperationResult.notConfigured(
                "Linux runtime is not installed; cannot run Gradle build/test."
            )
        }
        if (!installer.isStackInstalled(DevStack.ANDROID)) {
            return@withContext OperationResult.notConfigured(
                "Android build tools are not installed (Settings -> Development stacks); cannot run Gradle build/test."
            )
        }
        val gradleRoot = findAndroidGradleProjectRoot(workingDir)
            ?: return@withContext OperationResult.failure(
                error = "No Android Gradle project found under ${workingDir.path}; cannot validate build/test.",
                startedAt = startedAt,
            )
        val installed = installer.installedRuntime()
        val outputFile = File(
            getApplication<Application>().cacheDir,
            "workflow-build-${System.nanoTime()}.log"
        )
        try {
            val command = "gradle --init-script /root/.gradle/init.d/pocketdev-android.gradle " +
                "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
                "--no-daemon assembleDebug testDebugUnitTest --console=plain"
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                gradleRoot,
                emptyMap(),
                listOf("/usr/bin/bash", "-lc", command),
                guestWorkspacePath = guestWorkspacePath,
                outputFile = outputFile,
            )
            val exitCode = process.waitFor()
            val rawOutput = (process as? com.codeair.mhe.runtime.NativeSpawnProcess)
                ?.outputFile?.readTailText(MAX_PROCESS_OUTPUT_BYTES).orEmpty()
            val tail = truncateHeadAndTail(rawOutput, maxHeadLines = 10, maxTailLines = 40, maxTotalLines = 60)
            val logs = listOf(
                "Command: $command",
                "Working dir: ${gradleRoot.path}",
                "Exit code: $exitCode",
                tail,
            )
            return@withContext if (exitCode == 0) {
                OperationResult.success(
                    exitCode = exitCode,
                    logs = logs,
                    verificationEvidence = "gradle assembleDebug testDebugUnitTest exit=0 in ${gradleRoot.path}",
                    startedAt = startedAt,
                )
            } else {
                OperationResult.failure(
                    error = "Gradle build/test failed (exit code $exitCode). " +
                        tail.trim().takeLast(2000).ifBlank { "See logs." },
                    exitCode = exitCode,
                    logs = logs,
                    startedAt = startedAt,
                )
            }
        } catch (e: Exception) {
            return@withContext OperationResult.failure(
                error = "Gradle invocation crashed: ${e.message}",
                startedAt = startedAt,
            )
        } finally {
            runCatching { outputFile.delete() }
        }
    }

    fun startAutonomousIssueWorkflow(issue: com.codeair.mhe.network.GitHubIssue) {
        val project = _state.value.activeProject ?: return
        val dir = projectWorkspaceRoot(project)
        val repo = _state.value.gitConnectedRepo ?: project.name
        val guestPath = guestRootFor(project)

        viewModelScope.launch {
            // Explicit, validated token retrieval: blank token fails the workflow fast (engine-side).
            val token = withContext(Dispatchers.IO) { resolveGitHubToken() }
            val result = gitWorkflowEngine.runAutonomousWorkflow(
                issue = issue,
                token = token,
                repoFullName = repo,
                workingDir = dir,
                agentExecutor = { prompt -> runAgentPromptAwaitingResult(prompt) },
                buildAndTestValidator = { gradleDir -> runRealGradleBuildAndTest(gradleDir, guestPath) },
            )
            if (!result.isSuccess) {
                _state.update {
                    it.copy(toastMessage = "Autonomous workflow failed: ${result.error ?: result.status}")
                }
            }
            refreshGitStudio(dir)
        }
    }

    /**
     * Installs a marketplace item through the real [MarketplaceInstallManager]:
     * the package is materialized on disk and recorded durably. Failures are
     * reported honestly instead of a toast-only fake install (Milestone 0).
     */
    // ---- Milestone 1: delegated to domain coordinators (public API unchanged). ----
    fun installMarketplaceItem(item: com.codeair.mhe.marketplace.MarketplaceItem) =
        marketplaceCoordinator.installMarketplaceItem(item)

    fun uninstallMarketplaceItem(item: com.codeair.mhe.marketplace.MarketplaceItem) =
        marketplaceCoordinator.uninstallMarketplaceItem(item)

    fun planTeamProject(goal: String) =
        teamCoordinator.planTeamProject(goal)

    fun stepTeamTask(): com.codeair.mhe.agent.team.model.TeamTask? =
        teamCoordinator.stepTeamTask()

    fun autoRunTeamTasks(): Int =
        teamCoordinator.autoRunTeamTasks()

    fun cancelTeamSession() =
        teamCoordinator.cancelTeamSession()

    fun setCloudRoutingPolicy(policy: com.codeair.mhe.cloud.model.WorkloadRoutingPolicy) =
        cloudCoordinator.setCloudRoutingPolicy(policy)

    fun submitHybridWorkload(title: String, type: com.codeair.mhe.cloud.model.WorkloadType): com.codeair.mhe.cloud.model.HybridExecutionJob =
        cloudCoordinator.submitHybridWorkload(title, type)

    fun createCollaborationSession(projectId: String, projectName: String): com.codeair.mhe.collab.model.SharedProjectSession =
        collaborationCoordinator.createCollaborationSession(projectId, projectName)

    fun joinCollaborationSession(shareToken: String, name: String, email: String): com.codeair.mhe.collab.model.SharedProjectSession? =
        collaborationCoordinator.joinCollaborationSession(shareToken, name, email)

    override fun onCleared() {
        super.onCleared()
        AppCrashLogger.log("MainViewModel.onCleared: cleaning up running processes and sessions")
        terminalCoordinator.onCleared()
        gitHubAuthCoordinator.onCleared()
        if (_state.value.isRunning) {
            sessionManager.recordSessionFailed("App process terminated unexpectedly")
        }
    }

    companion object {
        private const val MAX_VISIBLE_WORKSPACE_ENTRIES = 2_000
        private const val MAX_ATTACHMENTS_PER_MESSAGE = 5
        private const val MAX_PROCESS_OUTPUT_BYTES = 512 * 1024
        private const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L
        private const val MAX_EDITABLE_FILE_CHARS = 512_000
        private const val MAX_IMPORTED_PROJECT_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MAX_IMPORTED_ZIP_ENTRIES = 100_000
        private const val LEGACY_GITHUB_TOKEN_KEY = "GITHUB_APP"
        private const val GITHUB_DEVICE_URL = "https://github.com/login/device"
        private val GITHUB_DEVICE_CODE = Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b")
        private const val TEST_PROVIDER_DEFAULTS_VERSION = 1
        private const val TEST_OPENROUTER_BASE_URL = "https://openrouter.ai/api"
        private const val TEST_OPENROUTER_MODEL = "stealth/ox-alpha"
    }
}
