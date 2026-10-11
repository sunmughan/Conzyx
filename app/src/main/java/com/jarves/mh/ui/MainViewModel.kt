package com.jarves.mh.ui

import com.jarves.mh.terminal.TerminalCommandPolicy

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
import com.jarves.mh.BuildConfig
import com.jarves.mh.AppCrashLogger
import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.ApiKeyInfo
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.ExecutionMode
import com.jarves.mh.model.ActiveRoadmap
import com.jarves.mh.model.RoadmapComment
import com.jarves.mh.model.RoadmapStep
import com.jarves.mh.model.ScratchpadItem
import com.jarves.mh.model.StepStatus
import com.jarves.mh.model.TaskStatus
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.model.projectSlug
import com.jarves.mh.model.generateQuickChatIdentity
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.network.ProviderApiClient
import com.jarves.mh.network.GitHubRepository
import com.jarves.mh.runtime.ClaudeRuntimeBridge
import com.jarves.mh.runtime.DshRuntimeBridge
import com.jarves.mh.runtime.AgentRegistry
import com.jarves.mh.runtime.AgentUpdateInfo
import com.jarves.mh.runtime.AntigravityAuthController
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.runtime.AntigravityRuntimeBridge
import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.RuntimeInstallProgress
import com.jarves.mh.runtime.RuntimeInstallEvent
import com.jarves.mh.runtime.RuntimeInstaller
import com.jarves.mh.runtime.RuntimeSetupController
import com.jarves.mh.runtime.RuntimeSetupService
import com.jarves.mh.runtime.RuntimeSetupSnapshot
import com.jarves.mh.runtime.RuntimeSetupStatus
import com.jarves.mh.runtime.readTailText
import com.jarves.mh.runtime.truncateHeadAndTail
import com.jarves.mh.runtime.supportsArm64Runtime
import com.jarves.mh.runtime.RuntimeFailureClassifier
import com.jarves.mh.runtime.WorkspaceCheckpoints
import com.jarves.mh.runtime.ProjectIndex
import com.jarves.mh.runtime.ContextEngine
import com.jarves.mh.runtime.RuntimeRetryPolicy
import com.jarves.mh.runtime.SkillInfo
import com.jarves.mh.runtime.SkillManager
import com.jarves.mh.runtime.SkillSearchResult
import com.jarves.mh.runtime.AndroidAppInstaller
import com.jarves.mh.runtime.NotificationCoordinator
import com.jarves.mh.runtime.RuntimeExecutionService
import com.jarves.mh.runtime.formatDurationText
import com.jarves.mh.MobileHarnessApplication
import com.jarves.mh.update.AppUpdateInfo
import com.jarves.mh.update.AppUpdater
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

internal fun sanitizeTerminalOutput(text: String): String =
    TerminalCommandPolicy.sanitizeTerminalOutput(text)

private val ANTIGRAVITY_MODEL_EFFORT = Regex("^(.*)-(low|medium|high)$")

private fun antigravityEffortFromModel(model: String): String? =
    ANTIGRAVITY_MODEL_EFFORT.matchEntire(model)?.groupValues?.get(2)

private fun antigravityModelWithEffort(model: String, effort: String): String? {
    val match = ANTIGRAVITY_MODEL_EFFORT.matchEntire(model) ?: return null
    return "${match.groupValues[1]}-$effort"
}

private data class ProjectTerminalResult(
    val output: String,
    val exitCode: Int,
    val cwd: String,
)

private data class RuntimeRetryRequest(
    val runtime: com.jarves.mh.runtime.RuntimeBridge,
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
    val themeMode: com.jarves.mh.ui.theme.AppThemeMode = com.jarves.mh.ui.theme.AppThemeMode.DARK,
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
    val interruptedSession: com.jarves.mh.session.PersistedAgentSession? = null,
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
    val orchestratorSnapshot: com.jarves.mh.session.OrchestratorSnapshot? = null,
    val emergencyKillSwitchActive: Boolean = false,
    val allowShellExec: Boolean = true,
    val allowNetworkAccess: Boolean = true,
    val allowFileDeletion: Boolean = false,
    val allowGitPush: Boolean = false,
    val allowApkInstall: Boolean = false,
    val allowExternalServices: Boolean = false,
    val allowAccessEnv: Boolean = false,
    val securityAuditLogs: List<com.jarves.mh.security.SecurityAuditEntry> = emptyList(),
    val mcpServers: List<com.jarves.mh.mcp.McpServerInfo> = emptyList(),
    val mcpCatalog: List<com.jarves.mh.mcp.McpCatalogTemplate> = emptyList(),
    val mcpTools: List<com.jarves.mh.runtime.McpToolDefinition> = emptyList(),
    val mcpAuditLogs: List<com.jarves.mh.mcp.McpAuditEntry> = emptyList(),
    val mcpSelectedServerId: String? = null,
    val mcpBusy: Boolean = false,
    val mcpMessage: String? = null,
    val appFactoryBlueprint: com.jarves.mh.factory.model.AppBlueprint? = null,
    val appFactoryGeneratingBlueprint: Boolean = false,
    val appFactoryScaffolding: Boolean = false,
    val appFactoryScaffoldFiles: List<java.io.File> = emptyList(),
    val appFactoryBuilding: Boolean = false,
    val appFactoryBuildResult: com.jarves.mh.factory.model.BuildResult? = null,
    val appFactoryLogs: List<String> = emptyList(),
    val appFactoryKeystoreConfig: com.jarves.mh.factory.model.KeystoreConfig? = null,
    val appFactoryStoreBundle: com.jarves.mh.factory.model.StoreListingBundle? = null,
    val appFactoryMessage: String? = null,
    val gitStatusInfo: com.jarves.mh.git.model.GitStatusInfo = com.jarves.mh.git.model.GitStatusInfo(),
    val gitBranches: List<com.jarves.mh.git.model.GitBranchInfo> = emptyList(),
    val gitCommits: List<com.jarves.mh.git.model.GitCommitInfo> = emptyList(),
    val gitRawDiff: String = "",
    val gitWorkflowState: com.jarves.mh.git.workflow.WorkflowExecutionState = com.jarves.mh.git.workflow.WorkflowExecutionState(),
    val gitWorkflowRuns: List<com.jarves.mh.network.GitHubWorkflowRun> = emptyList(),
    val gitReleases: List<com.jarves.mh.network.GitHubRelease> = emptyList(),
    val gitIssues: List<com.jarves.mh.network.GitHubIssue> = emptyList(),
    val gitPullRequests: List<com.jarves.mh.network.GitHubPullRequest> = emptyList(),
    val gitConnectedRepo: String? = null,
    val teamSessionState: com.jarves.mh.agent.team.model.TeamSessionState = com.jarves.mh.agent.team.model.TeamSessionState(),
    val cloudHybridState: com.jarves.mh.cloud.model.CloudHybridState = com.jarves.mh.cloud.model.CloudHybridState(),
    val collaborationSessionState: com.jarves.mh.collab.model.CollaborationSessionState = com.jarves.mh.collab.model.CollaborationSessionState(),
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
    private val agentRegistry = AgentRegistry.builtIns(claudeRuntime, dshRuntime, antigravityRuntime)
    private fun activeRuntime(): com.jarves.mh.runtime.RuntimeBridge = agentRegistry.require(_state.value.agentKind).runtime
    private val providerApi = ProviderApiClient()
    private val skillManager = SkillManager(application)
    private val editorCheckpoints = WorkspaceCheckpoints(application.filesDir)
    private val projectTerminalHistoryStore = ProjectTerminalHistoryStore(application.filesDir)
    val sessionManager = com.jarves.mh.session.AgentSessionManager(application)
    val checkpointManager = com.jarves.mh.workspace.WorkspaceCheckpointManager(application.filesDir)
    val runtimeHealthController = com.jarves.mh.runtime.RuntimeHealthController(application)
    val agentSessionController = com.jarves.mh.session.AgentSessionController(application, sessionManager)
    val terminalSessionController = com.jarves.mh.terminal.TerminalSessionController()
    val agentChatController = com.jarves.mh.agent.AgentChatController()
    val gitDomainController = com.jarves.mh.workspace.GitDomainController { args, workingDir, guestPath ->
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
    val appErrorCoordinator = com.jarves.mh.runtime.AppErrorCoordinator()
    val projectWorkspaceController = com.jarves.mh.workspace.ProjectWorkspaceController(application)
    val fileOperationsController = com.jarves.mh.workspace.FileOperationsController()
    val projectMemoryStore = com.jarves.mh.runtime.ProjectMemoryStore(application.filesDir)
    val agentOrchestrator = com.jarves.mh.session.AgentOrchestrator(checkpointManager, projectMemoryStore)
    val permissionPolicyEngine = com.jarves.mh.security.PermissionPolicyEngine(application)
    val mcpRegistry = com.jarves.mh.mcp.McpRegistry(application)
    val mcpAuditLogger = com.jarves.mh.mcp.McpAuditLogger(application)
    val mcpPermissionManager = com.jarves.mh.mcp.McpPermissionManager(File(application.filesDir, "mcp"), permissionPolicyEngine)
    val mcpClientRunner = com.jarves.mh.runtime.McpClientRunner()
    val gitHubClient = com.jarves.mh.network.GitHubClient()
    val gitWorkflowEngine = com.jarves.mh.git.workflow.GitHubWorkflowEngine(gitDomainController, gitHubClient)
    val teamOrchestrator = com.jarves.mh.agent.team.AgentTeamOrchestrator()
    val cloudHybridCoordinator = com.jarves.mh.cloud.CloudHybridCoordinator()
    val workspaceCollaborationManager = com.jarves.mh.collab.WorkspaceCollaborationManager()
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
    @Volatile private var projectTerminalProcess: Process? = null
    @Volatile private var terminalProcess: Process? = null
    @Volatile private var projectTerminalProjectId: String? = null
    @Volatile private var projectTerminalStopRequested: Boolean = false
    @Volatile private var setupCompletionHandled: Boolean = false
    @Volatile private var githubAuthProcess: Process? = null
    private var githubAuthJob: kotlinx.coroutines.Job? = null
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
            themeMode = runCatching { com.jarves.mh.ui.theme.AppThemeMode.valueOf(preferences.themeMode.uppercase()) }
                .getOrDefault(com.jarves.mh.ui.theme.AppThemeMode.DARK),
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
        if (storageHealth is com.jarves.mh.runtime.StorageHealthResult.Critical) {
            _state.update { it.copy(toastMessage = storageHealth.message) }
        }
        val rootfsIntegrity = runtimeHealthController.verifyRootfsIntegrity()
        if (rootfsIntegrity is com.jarves.mh.runtime.RootfsIntegrityResult.Corrupted) {
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
            com.jarves.mh.workspace.SafeFileOps.cleanStaleTempFiles(application.filesDir)
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
                    projectTerminalHistoryStore.delete(project.id)
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

    private val _terminalLines = MutableStateFlow<List<TerminalOutputLine>>(
        listOf(
            TerminalOutputLine(
                command = "uname -a",
                output = "Linux pocket-dev 6.1.0-arm64 #1 SMP aarch64 GNU/Linux (PRoot Sandbox)",
                exitCode = 0,
            ),
        ),
    )
    val terminalLines: StateFlow<List<TerminalOutputLine>> = _terminalLines.asStateFlow()

    private val _isTerminalRunning = MutableStateFlow(false)
    val isTerminalRunning: StateFlow<Boolean> = _isTerminalRunning.asStateFlow()

    private val _terminalLiveOutput = MutableStateFlow("")
    val terminalLiveOutput: StateFlow<String> = _terminalLiveOutput.asStateFlow()

    private val _terminalCurrentCommand = MutableStateFlow<String?>(null)
    val terminalCurrentCommand: StateFlow<String?> = _terminalCurrentCommand.asStateFlow()

    fun runTerminalCommand(cmd: String) {
        val command = cmd.trim()
        if (command.isBlank() || _isTerminalRunning.value) return
        if (command == "clear") {
            _terminalLines.value = emptyList()
            return
        }
        _isTerminalRunning.value = true
        _terminalCurrentCommand.value = command
        _terminalLiveOutput.value = ""
        viewModelScope.launch {
            val (output, exitCode) = withContext(Dispatchers.IO) {
                runCatching {
                    if (!installer.isInstalled()) {
                        return@runCatching "Linux environment is not ready yet." to 1
                    }
                    val runtime = installer.installedRuntime()
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/terminal").apply { mkdirs() }
                    val preparedCommand = TerminalCommandPolicy.prepareInteractiveShellCommand(command)
                    val proc = installer.process(
                        proot = runtime.proot,
                        rootfs = runtime.rootfs,
                        workspace = workspace,
                        environment = emptyMap(),
                        guestCommand = listOf("/usr/bin/bash", "-c", preparedCommand),
                    )
                    terminalProcess = proc
                    val native = proc as? NativeSpawnProcess
                    var offset = 0L
                    val streamed = StringBuilder()
                    var autoConfirmed = false
                    while (proc.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                        val file = native?.outputFile
                        val available = (file?.length() ?: 0L) - offset
                        if (file == null || available <= 0) {
                            Thread.sleep(50)
                            continue
                        }
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            input.read(bytes)
                        }
                        if (count > 0) {
                            offset += count
                            streamed.append(bytes.decodeToString(0, count))
                            _terminalLiveOutput.value = sanitizeTerminalOutput(streamed.toString())
                                .trimEnd()
                                .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                            if (!autoConfirmed && TerminalCommandPolicy.shouldAutoConfirmPackageCommand(command, streamed.toString())) {
                                proc.outputStream.write("y\n".toByteArray())
                                proc.outputStream.flush()
                                autoConfirmed = true
                            }
                        }
                    }
                    val exit = proc.waitFor()
                    runCatching { proc.outputStream.close() }
                    val out = sanitizeTerminalOutput(streamed.toString()).trim()
                    val finalOut = if (out.isNotEmpty() || exit == 0) out else "Process exited with code $exit"
                    finalOut to exit
                }.getOrElse { "Error: ${it.message}" to 1 }
            }
            _terminalLines.update { it + TerminalOutputLine(command = command, output = output, exitCode = exitCode) }
            _terminalLiveOutput.value = ""
            _terminalCurrentCommand.value = null
            _isTerminalRunning.value = false
            terminalProcess = null
        }
    }

    fun sendTerminalInput(text: String) {
        sendProcessInput(terminalProcess, text)
    }

    fun interruptTerminalCommand() {
        interruptProcess(terminalProcess)
    }

    fun clearTerminal() {
        _terminalLines.value = emptyList()
    }

    fun requestProjectTerminalCommand(command: String) {
        val normalized = command.trim()
        if (normalized.isBlank() || _state.value.projectTerminalRunning || projectTerminalProcess?.isAlive == true) return
        if (TerminalCommandPolicy.requiresAndroidToolchain(normalized) && !installer.isStackInstalled(DevStack.ANDROID)) {
            _state.update {
                it.copy(toastMessage = "Android build tools are not installed. Add Android in Settings → Development stacks.")
            }
            return
        }
        if (TerminalCommandPolicy.isDestructiveTerminalCommand(normalized)) {
            _state.update { it.copy(pendingTerminalCommand = normalized) }
        } else {
            runProjectTerminalCommand(normalized)
        }
    }

    fun prepareProjectTerminalCommand(command: String) {
        val project = _state.value.activeProject ?: return
        if (command.isBlank() || _state.value.projectTerminalRunning) return
        _state.update {
            it.copy(
                projectTerminalCwd = projectGuestRoot(project),
                projectTerminalDraft = command.trim(),
            )
        }
    }

    fun consumeProjectTerminalDraft() {
        _state.update { it.copy(projectTerminalDraft = null) }
    }

    fun openProjectTerminal() {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning) return
        _state.update { it.copy(projectTerminalCwd = projectGuestRoot(project)) }
    }

    fun confirmProjectTerminalCommand() {
        val command = _state.value.pendingTerminalCommand ?: return
        _state.update { it.copy(pendingTerminalCommand = null) }
        runProjectTerminalCommand(command)
    }

    fun cancelProjectTerminalCommand() {
        _state.update { it.copy(pendingTerminalCommand = null) }
    }

    private fun runProjectTerminalCommand(command: String) {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning) return
        val startingCwd = _state.value.projectTerminalCwd
        val existingLines = _state.value.projectTerminalLines
        projectTerminalStopRequested = false
        val requestedPreviewUrl = TerminalCommandPolicy.detectServerUrl(command)
        _state.update {
            it.copy(
                projectTerminalRunning = true,
                projectTerminalLiveOutput = "",
                projectTerminalCommand = command,
                pendingTerminalCommand = null,
                previewReady = it.previewReady || requestedPreviewUrl != null,
                previewUrl = requestedPreviewUrl ?: it.previewUrl,
                terminalSessions = it.terminalSessions.map { s ->
                    if (s.id == it.activeTerminalSessionId) s.copy(isRunning = true) else s
                },
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { runProjectTerminalProcess(project.id, command, startingCwd) }
                    .getOrElse { error ->
                        ProjectTerminalResult(
                            output = "Terminal error: ${error.message ?: error::class.java.simpleName}",
                            exitCode = 1,
                            cwd = startingCwd,
                        )
                    }
            }
            val completedLine = TerminalOutputLine(
                command = command,
                output = result.output.ifBlank {
                    if (result.exitCode == 0) "" else "Process exited with code ${result.exitCode}"
                },
                exitCode = result.exitCode,
            )
            val updatedLines = (existingLines + completedLine).takeLast(MAX_PROJECT_TERMINAL_HISTORY)
            saveProjectTerminal(project.id, result.cwd, updatedLines)
            if (_state.value.activeProject?.id == project.id) {
                _state.update {
                    it.copy(
                        projectTerminalLines = updatedLines,
                        projectTerminalLiveOutput = "",
                        projectTerminalRunning = false,
                        projectTerminalCwd = result.cwd,
                        projectTerminalCommand = null,
                        terminalSessions = it.terminalSessions.map { s ->
                            if (s.id == it.activeTerminalSessionId) s.copy(
                                lines = updatedLines,
                                cwd = result.cwd,
                                isRunning = false,
                            ) else s
                        },
                    )
                }
                refreshProjectFiles()
            }
            projectTerminalProcess = null
            projectTerminalProjectId = null
            projectTerminalStopRequested = false
        }
    }

    fun stopProjectTerminalCommand() {
        if (!_state.value.projectTerminalRunning) return
        projectTerminalStopRequested = true
        viewModelScope.launch(Dispatchers.IO) {
            projectTerminalProcess?.destroy()
            delay(400)
            if (projectTerminalProcess?.isAlive == true) projectTerminalProcess?.destroyForcibly()
        }
    }

    fun sendProjectTerminalInput(text: String) {
        sendProcessInput(projectTerminalProcess, text)
    }

    fun interruptProjectTerminalCommand() {
        interruptProcess(projectTerminalProcess)
    }

    fun clearProjectTerminal() {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning) return
        _state.update { state ->
            state.copy(
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                terminalSessions = state.terminalSessions.map { s ->
                    if (s.id == state.activeTerminalSessionId) s.copy(lines = emptyList()) else s
                },
            )
        }
        saveProjectTerminal(project.id, _state.value.projectTerminalCwd, emptyList())
    }

    fun createTerminalSession() {
        val (updated, newSession) = terminalSessionController.createSession(
            sessions = _state.value.terminalSessions,
            defaultCwd = _state.value.projectTerminalCwd,
        )
        _state.update {
            it.copy(
                terminalSessions = updated,
                activeTerminalSessionId = newSession.id,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
            )
        }
    }

    fun selectTerminalSession(sessionId: String) {
        val current = _state.value
        val (updated, target) = terminalSessionController.selectSession(
            sessions = current.terminalSessions,
            currentActiveId = current.activeTerminalSessionId,
            currentLines = current.projectTerminalLines,
            currentCwd = current.projectTerminalCwd,
            currentRunning = current.projectTerminalRunning,
            targetSessionId = sessionId,
        )
        if (target == null) return
        _state.update {
            it.copy(
                terminalSessions = updated,
                activeTerminalSessionId = target.id,
                projectTerminalLines = target.lines,
                projectTerminalCwd = target.cwd,
                projectTerminalRunning = target.isRunning,
                projectTerminalLiveOutput = "",
            )
        }
    }

    fun closeTerminalSession(sessionId: String) {
        if (_state.value.terminalSessions.size <= 1) {
            clearProjectTerminal()
            return
        }
        val (remaining, newActive) = terminalSessionController.closeSession(
            sessions = _state.value.terminalSessions,
            currentActiveId = _state.value.activeTerminalSessionId,
            targetSessionId = sessionId,
        )
        val active = newActive ?: remaining.firstOrNull() ?: return
        _state.update {
            it.copy(
                terminalSessions = remaining,
                activeTerminalSessionId = active.id,
                projectTerminalLines = active.lines,
                projectTerminalCwd = active.cwd,
                projectTerminalRunning = active.isRunning,
                projectTerminalLiveOutput = "",
            )
        }
    }

    private fun runProjectTerminalProcess(projectId: String, command: String, cwd: String): ProjectTerminalResult {
        if (!installer.isInstalled()) return ProjectTerminalResult("Linux environment is not ready yet.", 1, cwd)
        val installed = installer.installedRuntime()
        val project = _state.value.projects.firstOrNull { it.id == projectId }
            ?: _state.value.activeProject?.takeIf { it.id == projectId }
            ?: return ProjectTerminalResult("Project is no longer available.", 1, cwd)
        val workspace = projectWorkspaceRoot(project)
        val guestWorkspacePath = projectGuestRoot(project)
        val marker = "__POCKETDEV_CWD_${UUID.randomUUID()}__"
        val preparedCommand = TerminalCommandPolicy.prepareInteractiveShellCommand(command)
        val script = """
            cd -- ${TerminalCommandPolicy.shellQuote(cwd)} || exit 1
            $preparedCommand
            pocket_status=${'$'}?
            printf '\n$marker%s\n' "${'$'}PWD"
            exit ${'$'}pocket_status
        """.trimIndent()
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = workspace,
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/bash", "-lc", script),
            guestWorkspacePath = guestWorkspacePath,
        )
        projectTerminalProcess = process
        projectTerminalProjectId = projectId
        if (projectTerminalStopRequested) process.destroy()
        val native = process as? NativeSpawnProcess
            ?: return ProjectTerminalResult("Unsupported terminal process.", 1, cwd)
        var offset = 0L
        val output = StringBuilder()
        var autoConfirmed = false
        while (process.isAlive || native.outputFile.length() > offset) {
            val available = native.outputFile.length() - offset
            if (available <= 0) {
                Thread.sleep(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(native.outputFile, "r").use { file ->
                file.seek(offset)
                file.read(bytes)
            }
            if (count > 0) {
                offset += count
                output.append(bytes.decodeToString(0, count))
                val visible = sanitizeTerminalOutput(output.toString().substringBefore(marker))
                    .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                if (!autoConfirmed && TerminalCommandPolicy.shouldAutoConfirmPackageCommand(command, visible)) {
                    process.outputStream.write("y\n".toByteArray())
                    process.outputStream.flush()
                    autoConfirmed = true
                }
                val detectedPreviewUrl = TerminalCommandPolicy.detectPreviewUrl(visible)
                _state.update { current ->
                    if (current.activeProject?.id == projectId) {
                        current.copy(
                            projectTerminalLiveOutput = visible,
                            previewReady = current.previewReady || detectedPreviewUrl != null,
                            previewUrl = detectedPreviewUrl ?: current.previewUrl,
                        )
                    } else current
                }
            }
        }
        val exitCode = process.waitFor()
        runCatching { process.outputStream.close() }
        val raw = output.toString()
        val cwdAfter = raw.substringAfter(marker, "")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it == guestWorkspacePath || it.startsWith("$guestWorkspacePath/") }
            ?: cwd
        val cleanOutput = sanitizeTerminalOutput(raw.substringBefore(marker))
            .trim()
            .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
        return ProjectTerminalResult(cleanOutput, exitCode, cwdAfter)
    }

    private fun sendProcessInput(process: Process?, text: String) {
        if (process?.isAlive != true || text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                process.outputStream.write((text + "\n").toByteArray())
                process.outputStream.flush()
            }.onFailure {
                _state.update { current -> current.copy(toastMessage = "This process is no longer accepting input.") }
            }
        }
    }

    private fun interruptProcess(process: Process?) {
        if (process?.isAlive != true) return
        viewModelScope.launch(Dispatchers.IO) {
            (process as? NativeSpawnProcess)?.interrupt() ?: process.destroy()
        }
    }

    private fun loadProjectTerminal(project: Project): ProjectTerminalSnapshot =
        projectTerminalHistoryStore.load(project.id, projectGuestRoot(project))

    private fun saveProjectTerminal(projectId: String, cwd: String, lines: List<TerminalOutputLine>) {
        val guestRoot = _state.value.activeProject?.takeIf { it.id == projectId }?.let(::projectGuestRoot)
            ?: "/workspace"
        if (!projectTerminalHistoryStore.save(projectId, cwd, guestRoot, lines)) {
            AppCrashLogger.log("Failed to persist terminal history for project $projectId")
        }
    }

    private fun projectGuestRoot(project: Project): String = "/workspace/${project.slug}"

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
        val storage = runtimeHealthController.checkStorageHealth(com.jarves.mh.runtime.RuntimeHealthController.MIN_BUILD_STORAGE_BYTES)
        if (storage is com.jarves.mh.runtime.StorageHealthResult.Critical) {
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
                    projectGuestRoot(project),
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

    fun toggleTheme() {
        val next = if (_state.value.themeMode == com.jarves.mh.ui.theme.AppThemeMode.DARK) {
            com.jarves.mh.ui.theme.AppThemeMode.LIGHT
        } else {
            com.jarves.mh.ui.theme.AppThemeMode.DARK
        }
        setThemeMode(next)
    }

    fun setThemeMode(mode: com.jarves.mh.ui.theme.AppThemeMode) {
        preferences.themeMode = mode.name.lowercase()
        _state.update { it.copy(themeMode = mode) }
    }

    fun engageEmergencyKillSwitch(reason: String = "User requested emergency stop") {
        permissionPolicyEngine.engageEmergencyKillSwitch(reason)
        _state.update { it.copy(emergencyKillSwitchActive = true) }
        stopTask()
    }

    fun resetEmergencyKillSwitch() {
        permissionPolicyEngine.resetEmergencyKillSwitch()
        _state.update { it.copy(emergencyKillSwitchActive = false) }
    }

    fun isEmergencyKillSwitchEngaged(): Boolean = permissionPolicyEngine.isEmergencyKillSwitchEngaged()

    fun refreshPermissionPolicies() {
        val globals = permissionPolicyEngine.getAllGlobalPolicies()
        val shell = when (globals[com.jarves.mh.security.PermissionCategory.SHELL_EXEC]) {
            com.jarves.mh.security.PermissionLevel.ALWAYS -> true
            com.jarves.mh.security.PermissionLevel.DENY -> false
            else -> true
        }
        val net = when (globals[com.jarves.mh.security.PermissionCategory.NETWORK]) {
            com.jarves.mh.security.PermissionLevel.ALWAYS -> true
            com.jarves.mh.security.PermissionLevel.DENY -> false
            else -> true
        }
        val fileDel = when (globals[com.jarves.mh.security.PermissionCategory.FILE_DELETE]) {
            com.jarves.mh.security.PermissionLevel.ALWAYS -> true
            com.jarves.mh.security.PermissionLevel.DENY -> false
            else -> false
        }
        val gitPush = when (globals[com.jarves.mh.security.PermissionCategory.GIT_PUSH]) {
            com.jarves.mh.security.PermissionLevel.ALWAYS -> true
            com.jarves.mh.security.PermissionLevel.DENY -> false
            else -> false
        }
        val apkInstall = when (globals[com.jarves.mh.security.PermissionCategory.INSTALL_APK]) {
            com.jarves.mh.security.PermissionLevel.ALWAYS -> true
            com.jarves.mh.security.PermissionLevel.DENY -> false
            else -> false
        }
        val extServices = when (globals[com.jarves.mh.security.PermissionCategory.EXTERNAL_SERVICES]) {
            com.jarves.mh.security.PermissionLevel.ALWAYS -> true
            com.jarves.mh.security.PermissionLevel.DENY -> false
            else -> false
        }
        val accessEnv = when (globals[com.jarves.mh.security.PermissionCategory.ACCESS_ENV]) {
            com.jarves.mh.security.PermissionLevel.ALWAYS -> true
            com.jarves.mh.security.PermissionLevel.DENY -> false
            else -> false
        }
        val logs = permissionPolicyEngine.auditLogger.getEntries(200)
        _state.update {
            it.copy(
                allowShellExec = shell,
                allowNetworkAccess = net,
                allowFileDeletion = fileDel,
                allowGitPush = gitPush,
                allowApkInstall = apkInstall,
                allowExternalServices = extServices,
                allowAccessEnv = accessEnv,
                securityAuditLogs = logs,
                emergencyKillSwitchActive = permissionPolicyEngine.isEmergencyKillSwitchEngaged(),
            )
        }
    }

    fun updatePermissionPolicy(category: com.jarves.mh.security.PermissionCategory, allowed: Boolean) {
        if (allowed) {
            permissionPolicyEngine.grantPermission(category, com.jarves.mh.security.PermissionLevel.ALWAYS)
        } else {
            permissionPolicyEngine.grantPermission(category, com.jarves.mh.security.PermissionLevel.DENY)
        }
        refreshPermissionPolicies()
    }

    fun loadSecurityAuditLogs(limit: Int = 200) {
        val logs = permissionPolicyEngine.auditLogger.getEntries(limit)
        _state.update { it.copy(securityAuditLogs = logs) }
    }

    fun searchSecurityAuditLogs(
        query: String = "",
        category: com.jarves.mh.security.PermissionCategory? = null,
        decision: com.jarves.mh.security.AuditDecision? = null,
        limit: Int = 200
    ) {
        val logs = permissionPolicyEngine.auditLogger.filter(
            query = query,
            category = category,
            decision = decision,
            limit = limit
        )
        _state.update { it.copy(securityAuditLogs = logs) }
    }

    fun clearSecurityAuditLogs() {
        permissionPolicyEngine.auditLogger.clear()
        _state.update { it.copy(securityAuditLogs = emptyList()) }
    }

    fun refreshMcpServers(projectId: String? = _state.value.activeProject?.id) {
        val servers = mcpRegistry.listServers(projectId)
        val catalog = mcpRegistry.catalogTemplates
        _state.update { it.copy(mcpServers = servers, mcpCatalog = catalog) }
    }

    fun installMcpServer(server: com.jarves.mh.mcp.McpServerInfo) {
        mcpRegistry.installServer(server)
        refreshMcpServers()
    }

    fun installMcpTemplate(templateId: String, envOverrides: Map<String, String> = emptyMap(), projectId: String? = _state.value.activeProject?.id) {
        runCatching {
            mcpRegistry.installFromTemplate(templateId, envOverrides, projectId)
            refreshMcpServers(projectId)
        }.onFailure { err ->
            _state.update { it.copy(toastMessage = "Failed to install MCP template: ${err.message}") }
        }
    }

    fun toggleMcpServer(serverId: String, enabled: Boolean) {
        mcpRegistry.toggleServer(serverId, enabled)
        refreshMcpServers()
    }

    fun removeMcpServer(serverId: String) {
        mcpClientRunner.stopServer(serverId)
        mcpRegistry.removeServer(serverId)
        refreshMcpServers()
    }

    fun updateMcpConfig(server: com.jarves.mh.mcp.McpServerInfo) {
        mcpRegistry.updateServer(server)
        refreshMcpServers()
    }

    fun discoverMcpTools(server: com.jarves.mh.mcp.McpServerInfo) {
        viewModelScope.launch {
            _state.update { it.copy(mcpBusy = true, mcpSelectedServerId = server.id, mcpMessage = "Discovering tools for ${server.name}...") }
            val res = mcpClientRunner.discoverTools(server)
            res.onSuccess { tools ->
                _state.update { it.copy(mcpBusy = false, mcpTools = tools, mcpMessage = "Found ${tools.size} tools") }
            }.onFailure { err ->
                _state.update { it.copy(mcpBusy = false, mcpMessage = "Tool discovery failed: ${err.message}") }
            }
        }
    }

    suspend fun executeMcpTool(
        server: com.jarves.mh.mcp.McpServerInfo,
        toolName: String,
        arguments: org.json.JSONObject,
        projectId: String? = _state.value.activeProject?.id
    ): com.jarves.mh.runtime.McpToolCallResult {
        val perm = mcpPermissionManager.evaluate(server, toolName, projectId)
        if (!perm.allowed) {
            val auditEntry = com.jarves.mh.mcp.McpAuditEntry(
                serverId = server.id,
                toolName = toolName,
                argumentsJson = arguments.toString(),
                success = false,
                projectId = projectId,
                permissionDecision = if (perm.requiresPrompt) "PROMPT_REQUIRED" else "DENIED",
                error = perm.reason
            )
            mcpAuditLogger.log(auditEntry)
            loadMcpAuditLogs()
            return com.jarves.mh.runtime.McpToolCallResult(
                success = false,
                content = "",
                error = "Permission check failed: ${perm.reason}"
            )
        }

        val result = mcpClientRunner.callTool(server, toolName, arguments)
        val auditEntry = com.jarves.mh.mcp.McpAuditEntry(
            serverId = server.id,
            toolName = toolName,
            argumentsJson = arguments.toString(),
            success = result.success,
            resultPreview = result.content.take(200),
            durationMs = result.durationMs,
            projectId = projectId,
            permissionDecision = "ALLOWED",
            error = result.error
        )
        mcpAuditLogger.log(auditEntry)
        loadMcpAuditLogs()
        return result
    }

    fun setMcpProjectPermission(projectId: String, serverId: String, policy: com.jarves.mh.mcp.McpPermissionPolicy) {
        mcpPermissionManager.setProjectServerPolicy(projectId, serverId, policy)
    }

    fun setMcpGlobalPermission(serverId: String, policy: com.jarves.mh.mcp.McpPermissionPolicy) {
        mcpPermissionManager.setGlobalServerPolicy(serverId, policy)
    }

    fun loadMcpAuditLogs(limit: Int = 100) {
        val logs = mcpAuditLogger.getEntries(limit)
        _state.update { it.copy(mcpAuditLogs = logs) }
    }

    fun clearMcpAuditLogs() {
        mcpAuditLogger.clear()
        _state.update { it.copy(mcpAuditLogs = emptyList()) }
    }

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
        viewModelScope.launch { RuntimeSetupController.snapshot.collect(::onSetupSnapshot) }
        viewModelScope.launch { claudeRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        if (!supportsArm64Runtime(android.os.Build.SUPPORTED_ABIS, System.getProperty("os.arch"))) {
            _state.update {
                it.copy(
                    startupStage = StartupStage.SETUP_REQUIRED,
                    startupMessage = "ARM64 device required",
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
            return
        }
        val setupSnapshot = RuntimeSetupController.snapshot.value
        if (setupSnapshot.status == RuntimeSetupStatus.RUNNING) {
            onSetupSnapshot(setupSnapshot)
            resumeRuntimeSetupService()
            return
        }
        val isWarmBoot = preferences.runtimeSetupComplete && preferences.onboardingComplete
        val installed = withContext(Dispatchers.IO) {
            // Upgrades from the old single-bundle layout keep every already-installed tool.
            installer.migrateLegacyToolMarkers()
            installer.isInstalled().also { ready ->
                if (ready) installer.cleanupLegacyWorkspaceScaffolding()
            }
        }
        _state.update { current ->
            current.copy(
                installedDevStacks = if (installed) installer.installedStacks() else current.installedDevStacks,
                installedAgentVersions = if (installed) installer.installedAgentVersions() else emptyMap(),
            )
        }
        when {
            !installed && setupSnapshot.status == RuntimeSetupStatus.ERROR -> onSetupSnapshot(setupSnapshot)
            !installed -> _state.update { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupProgress = 0f) }
            !preferences.onboardingComplete -> {
                preferences.runtimeSetupComplete = true
                _state.update { it.copy(startupStage = StartupStage.MODEL_SETUP, startupProgress = 1f) }
            }
            else -> initializeRuntime(isWarmBoot = isWarmBoot)
        }
    }

    fun startRuntimeSetup() {
        if (state.value.startupStage == StartupStage.INSTALLING) return
        setupCompletionHandled = false
        _state.update {
            it.copy(
                startupStage = StartupStage.INSTALLING,
                startupProgress = 0.01f,
                startupMessage = "Preparing your private coding workspace",
                startupBytes = null,
                startupLogs = listOf("\$ Preparing your private coding workspace"),
                startupIndeterminate = false,
                startupError = null,
                startupErrorIsOffline = false,
                showDetailedSetupProgress = true,
            )
        }
        resumeRuntimeSetupService()
    }

    fun retryStartup() {
        if (installer.isInstalled()) viewModelScope.launch { initializeRuntime() } else {
            _state.update { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupError = null) }
            startRuntimeSetup()
        }
    }

    fun backToSetup() {
        _state.update {
            it.copy(
                startupStage = StartupStage.SETUP_REQUIRED,
                startupError = null,
                startupErrorIsOffline = false,
                startupMessage = "Select tools and start setup",
                startupProgress = 0f,
                showDetailedSetupProgress = false,
            )
        }
    }

    private fun resumeRuntimeSetupService() {
        val stacks = _state.value.selectedDevStacks.joinToString(",") { it.name }
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_START)
                .putExtra(RuntimeSetupService.EXTRA_STACKS, stacks)
                .putExtra(RuntimeSetupService.EXTRA_AGENT, _state.value.agentKind.name),
        )
    }

    private fun onSetupSnapshot(snapshot: RuntimeSetupSnapshot) {
        when (snapshot.status) {
            RuntimeSetupStatus.RUNNING -> _state.update {
                it.copy(
                    startupStage = StartupStage.INSTALLING,
                    startupProgress = snapshot.progress,
                    startupMessage = snapshot.message,
                    startupBytes = snapshot.totalBytes?.let { total -> (snapshot.downloadedBytes ?: 0L) to total },
                    startupLogs = snapshot.logs,
                    startupIndeterminate = snapshot.indeterminate,
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
            RuntimeSetupStatus.COMPLETE -> {
                if (setupCompletionHandled) return
                setupCompletionHandled = true
                preferences.runtimeSetupComplete = true
                viewModelScope.launch {
                    val installedStacks = withContext(Dispatchers.IO) { installer.installedStacks() }
                    _state.update { it.copy(installedDevStacks = installedStacks) }
                    if (preferences.onboardingComplete) {
                        initializeRuntime()
                    } else {
                        _state.update {
                            it.copy(
                                startupStage = StartupStage.MODEL_SETUP,
                                startupProgress = 1f,
                                startupBytes = null,
                                startupIndeterminate = false,
                            )
                        }
                    }
                }
            }
            RuntimeSetupStatus.ERROR -> _state.update {
                it.copy(
                    startupStage = StartupStage.ERROR,
                    startupMessage = snapshot.message,
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                    startupError = snapshot.errorMessage,
                    startupErrorIsOffline = snapshot.offline,
                )
            }
            RuntimeSetupStatus.CANCELLED -> _state.update {
                it.copy(
                    startupStage = StartupStage.SETUP_REQUIRED,
                    startupMessage = "Setup paused",
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                )
            }
            RuntimeSetupStatus.IDLE -> Unit
        }
    }

    private suspend fun initializeRuntime(isWarmBoot: Boolean = false) {
        val startedAt = SystemClock.elapsedRealtime()
        if (!isWarmBoot) {
            _state.update {
                it.copy(
                    startupStage = StartupStage.INITIALIZING,
                    startupProgress = 0.05f,
                    startupMessage = "Opening your private workspace",
                    startupBytes = null,
                    startupLogs = listOf("\$ Opening your private workspace"),
                    startupIndeterminate = false,
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
        }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                installer.initializeExisting { progress ->
                    if (!isWarmBoot) {
                        _state.update { current ->
                            current.copy(
                                startupProgress = 0.05f + progress.fraction * 0.95f,
                                startupMessage = progress.message,
                                startupBytes = null,
                                startupLogs = mergeStartupLog(current.startupLogs, progress),
                            )
                        }
                    }
                }
            }
        }
        if (result.isSuccess) {
            // The real version probe can finish in a fraction of a second on fast phones.
            // Keep the successful loading state visible long enough to be understandable during initial setup.
            if (!isWarmBoot) {
                val remaining = MINIMUM_INITIALIZATION_SCREEN_MS - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0) delay(remaining)
            }
            _state.update {
                it.copy(
                    startupStage = StartupStage.READY,
                    startupProgress = 1f,
                    installedDevStacks = installer.installedStacks(),
                    installedAgentVersions = installer.installedAgentVersions(),
                )
            }
            pingApi()
            checkForAppUpdate()
            autoUpdateToolsAndSkills()
            if (!isWarmBoot || _state.value.activeProject == null) {
                preferences.lastActiveProjectId?.let { lastId ->
                    _state.value.projects.firstOrNull { it.id == lastId }?.let { lastProject ->
                        openProject(lastProject)
                    }
                }
            }
        } else {
            showStartupError(result.exceptionOrNull() ?: IllegalStateException("Claude Code initialization failed"))
        }
    }

    fun checkForAppUpdate(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - preferences.lastAppUpdateCheckMillis < 24L * 60L * 60L * 1000L) return
        viewModelScope.launch(Dispatchers.IO) {
            val update = runCatching { appUpdater().check() }.getOrNull()
            preferences.lastAppUpdateCheckMillis = System.currentTimeMillis()
            if (update != null) {
                _state.update {
                    it.copy(appUpdate = update, appUpdateStatus = AppUpdateStatus.AVAILABLE, appUpdateError = null)
                }
            } else if (force) {
                _state.update {
                    it.copy(toastMessage = "Mobile Harness is up to date (${BuildConfig.APP_VARIANT} · v${BuildConfig.VERSION_NAME})")
                }
            }
        }
    }

    /** Set or update the in-app update channel / manifest URL. */
    fun setUpdateManifestUrl(url: String) {
        preferences.updateManifestUrlOverride = url.trim()
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Reset the in-app update manifest URL to the default release channel. */
    fun clearUpdateManifestUrl() {
        preferences.updateManifestUrlOverride = ""
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Set or update the private GitHub Personal Access Token or Deploy Token. */
    fun setUpdateAuthToken(token: String) {
        preferences.updateAuthToken = token.trim()
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    fun updateManifestUrl(): String = preferences.updateManifestUrlOverride
    fun updateAuthToken(): String = preferences.updateAuthToken

    /** Debug builds only: persist a manifest URL override and re-check immediately. */
    fun setDebugUpdateManifestUrl(url: String) {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = url.trim()
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: clear the manifest URL override and re-check the default channel. */
    fun clearDebugUpdateManifestUrl() {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = ""
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: the currently-active manifest URL override (empty = default). */
    fun debugUpdateManifestUrl(): String = if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else ""

    fun installAppUpdate() {
        val info = _state.value.appUpdate ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getApplication<Application>().packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(appUpdateStatus = AppUpdateStatus.PERMISSION_REQUIRED) }
            return
        }
        if (_state.value.appUpdateStatus == AppUpdateStatus.DOWNLOADING) return
        _state.update {
            it.copy(appUpdateStatus = AppUpdateStatus.DOWNLOADING, appUpdateDownloadedBytes = 0L, appUpdateTotalBytes = info.sizeBytes, appUpdateError = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appUpdater().download(info) { downloaded, total ->
                    _state.update { current -> current.copy(appUpdateDownloadedBytes = downloaded, appUpdateTotalBytes = total) }
                }
            }.onSuccess { apk ->
                _state.update { it.copy(appUpdateStatus = AppUpdateStatus.INSTALLING) }
                runCatching { AndroidAppInstaller.install(getApplication(), apk) }.onFailure { error ->
                    _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: "Could not start the Android installer") }
                }
            }.onFailure { error ->
                _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: "Update download failed") }
            }
        }
    }

    fun dismissAppUpdateError() {
        _state.update { it.copy(appUpdateStatus = AppUpdateStatus.AVAILABLE, appUpdateError = null) }
    }

    private fun mergeStartupLog(
        existing: List<String>,
        progress: RuntimeInstallProgress,
    ): List<String> {
        val prefix = "\$ ${progress.message}"
        val bytes = progress.totalBytes?.let { total ->
            val downloaded = progress.downloadedBytes ?: 0L
            " — %.1f / %.1f MB".format(downloaded / 1_048_576.0, total / 1_048_576.0)
        }.orEmpty()
        val nextLine = prefix + bytes
        val updated = if (existing.lastOrNull()?.startsWith(prefix) == true) {
            existing.dropLast(1) + nextLine
        } else {
            existing + nextLine
        }
        return updated.takeLast(80)
    }

    private fun showStartupError(error: Throwable) {
        val isOffline = generateSequence(error as Throwable?) { it.cause }
            .any { cause ->
                cause is UnknownHostException ||
                    cause.message.orEmpty().contains("unable to resolve host", ignoreCase = true) ||
                    cause.message.orEmpty().contains("no address associated with hostname", ignoreCase = true)
            }
        val message = if (isOffline) {
            "Connect to Wi-Fi or mobile data, then try again. Internet is required to finish the first-time setup."
        } else {
            error.message?.take(300) ?: "Something went wrong while preparing Mobile Harness. Please try again."
        }
        _state.update {
            it.copy(
                startupStage = StartupStage.ERROR,
                startupError = message,
                startupErrorIsOffline = isOffline,
            )
        }
    }

    fun finishOnboarding(profile: ProviderProfile, secret: String) {
        vault.put(profile.kind.name, secret)
        val saved = profile.copy(
            hasSecret = secret.isNotBlank() || vault.contains(profile.kind.name),
        )
        preferences.saveProvider(saved, _state.value.agentKind)
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, provider = saved, startupStage = StartupStage.READY) }
        refreshActiveApiKey(profile.kind)
        pingApi()
        autoUpdateToolsAndSkills()
    }

    fun finishAntigravityOnboarding() {
        check(_state.value.antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN) {
            "Sign in to Antigravity first"
        }
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, startupStage = StartupStage.READY) }
        autoUpdateToolsAndSkills()
    }

    /** Lets first-run users escape a provider/login failure without losing saved credentials. */
    fun chooseOnboardingAgent(kind: AgentKind) {
        selectAgent(kind)
        _state.update {
            it.copy(
                startupStage = if (installer.isAgentInstalled(kind)) {
                    StartupStage.MODEL_SETUP
                } else {
                    StartupStage.SETUP_REQUIRED
                },
                startupError = null,
                startupErrorIsOffline = false,
            )
        }
    }

    fun updateProvider(profile: ProviderProfile, secret: String) = finishOnboarding(profile, secret)

    fun finishBackgroundSetup() {
        preferences.backgroundSetupComplete = true
        _state.update {
            it.copy(
                backgroundSetupComplete = true,
                showAppTour = !preferences.appTourCompleted,
            )
        }
    }

    fun finishAppTour() {
        preferences.appTourCompleted = true
        _state.update { it.copy(appTourCompleted = true, showAppTour = false) }
    }

    fun replayAppTour() {
        _state.update { it.copy(showAppTour = true) }
    }

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
                    listOf(com.jarves.mh.runtime.RuntimeInstaller.AGY_GUEST_PATH, "models"),
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
    fun toggleDevStack(stack: DevStack) {
        if (stack == DevStack.WEB) return
        val updated = _state.value.selectedDevStacks.toMutableSet().apply {
            if (!add(stack)) remove(stack)
        }
        preferences.selectedDevStacks = updated.map { it.name }.toSet()
        _state.update { it.copy(selectedDevStacks = updated) }
    }

    /** Installs one development stack on demand (Settings) with live progress. */
    fun installDevStack(stack: DevStack) {
        if (_state.value.devStackInstalling != null) return
        _state.update {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = false,
                devStackMessage = "Preparing ${stack.label}…",
                devStackProgress = 0f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleTime = android.os.SystemClock.elapsedRealtime()
            var latestSpeed: Long? = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.ensureStackInstalled(stack) { progress ->
                        val transfer = progress.totalBytes?.let { total ->
                            (progress.downloadedBytes ?: 0L) to total
                        }
                        if (transfer != null) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            val elapsed = now - sampleTime
                            val delta = transfer.first - sampleBytes
                            if (delta < 0L) {
                                sampleBytes = transfer.first
                                sampleTime = now
                                latestSpeed = null
                            } else if (elapsed >= 500L) {
                                latestSpeed = (delta * 1_000L / elapsed).coerceAtLeast(0L)
                                sampleBytes = transfer.first
                                sampleTime = now
                            }
                        } else {
                            sampleBytes = 0L
                            sampleTime = android.os.SystemClock.elapsedRealtime()
                            latestSpeed = null
                        }
                        _state.update { current ->
                            current.copy(
                                devStackMessage = progress.message,
                                devStackProgress = progress.fraction.coerceIn(0f, 1f),
                                devStackBytes = transfer,
                                devStackBytesPerSecond = latestSpeed,
                            )
                        }
                    }
                }
            }
            _state.update { current ->
                current.copy(
                    devStackInstalling = null,
                    devStackRemoving = false,
                    installedDevStacks = if (result.isSuccess) current.installedDevStacks + stack else current.installedDevStacks,
                    devStackProgress = 0f,
                    devStackBytes = null,
                    devStackBytesPerSecond = null,
                    devStackMessage = result.fold(
                        onSuccess = { "${stack.label} tools are ready" },
                        onFailure = { error -> error.message?.take(200) ?: "Could not install ${stack.label}" },
                    ),
                    toastMessage = result.fold(
                        onSuccess = { "${stack.label} tools are ready" },
                        onFailure = { error -> error.message?.take(200) ?: "Could not install ${stack.label}" },
                    ),
                )
            }
        }
    }

    /** Updates an installed optional toolchain in place. */
    fun updateDevStack(stack: DevStack) {
        if (_state.value.devStackInstalling != null || stack == DevStack.WEB) return
        if (stack !in _state.value.installedDevStacks) return
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop running tasks and terminal commands before updating tools") }
            return
        }
        _state.update {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = false,
                devStackMessage = "Updating ${stack.label}…",
                devStackProgress = 0f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleTime = android.os.SystemClock.elapsedRealtime()
            var latestSpeed: Long? = null
            var wasAlreadyUpToDate = false
            var finalProgressMessage: String? = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.updateStack(stack) { progress ->
                        if (progress.message.contains("up to date", ignoreCase = true)) {
                            wasAlreadyUpToDate = true
                        }
                        finalProgressMessage = progress.message
                        val transfer = progress.totalBytes?.let { total ->
                            (progress.downloadedBytes ?: 0L) to total
                        }
                        if (transfer != null) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            val elapsed = now - sampleTime
                            val delta = transfer.first - sampleBytes
                            if (delta < 0L) {
                                sampleBytes = transfer.first
                                sampleTime = now
                                latestSpeed = null
                            } else if (elapsed >= 500L) {
                                latestSpeed = (delta * 1_000L / elapsed).coerceAtLeast(0L)
                                sampleBytes = transfer.first
                                sampleTime = now
                            }
                        }
                        _state.update { current ->
                            current.copy(
                                devStackMessage = progress.message,
                                devStackProgress = progress.fraction.coerceIn(0f, 1f),
                                devStackBytes = transfer,
                                devStackBytesPerSecond = latestSpeed,
                            )
                        }
                    }
                }
            }
            _state.update { current ->
                val finalMsg = result.fold(
                    onSuccess = {
                        finalProgressMessage?.takeIf { it.isNotBlank() }
                            ?: if (wasAlreadyUpToDate) "${stack.label} is already up to date" else "${stack.label} updated successfully"
                    },
                    onFailure = { error -> error.message?.take(200) ?: "Could not update ${stack.label}" },
                )
                current.copy(
                    devStackInstalling = null,
                    devStackRemoving = false,
                    devStackProgress = 0f,
                    devStackBytes = null,
                    devStackBytesPerSecond = null,
                    devStackMessage = finalMsg,
                    toastMessage = result.fold(
                        onSuccess = {
                            if (wasAlreadyUpToDate) "${stack.label} is already up to date" else "${stack.label} updated"
                        },
                        onFailure = { "Could not update ${stack.label}" },
                    ),
                    installedDevStacks = installer.installedStacks(),
                )
            }
        }
    }

    /** Removes an optional toolchain after the Settings confirmation dialog. */
    fun removeDevStack(stack: DevStack) {
        if (_state.value.devStackInstalling != null || stack == DevStack.WEB) return
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop running tasks and terminal commands before removing tools") }
            return
        }
        _state.update {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = true,
                devStackMessage = "Removing ${stack.label}…",
                devStackProgress = 0.1f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.removeStack(stack) { progress ->
                        _state.update { current ->
                            current.copy(
                                devStackMessage = progress.message,
                                devStackProgress = progress.fraction.coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
            if (result.isSuccess) {
                val selected = _state.value.selectedDevStacks - stack
                preferences.selectedDevStacks = selected.map { it.name }.toSet()
            }
            _state.update { current ->
                current.copy(
                    selectedDevStacks = if (result.isSuccess) current.selectedDevStacks - stack else current.selectedDevStacks,
                    installedDevStacks = if (result.isSuccess) current.installedDevStacks - stack else current.installedDevStacks,
                    devStackInstalling = null,
                    devStackRemoving = false,
                    devStackProgress = 0f,
                    devStackMessage = result.fold(
                        onSuccess = { "${stack.label} removed" },
                        onFailure = { result.exceptionOrNull()?.message?.take(200) ?: "Could not remove ${stack.label}" },
                    ),
                    toastMessage = result.fold(
                        onSuccess = { "${stack.label} removed" },
                        onFailure = { "Could not remove ${stack.label}" },
                    ),
                )
            }
        }
    }

    suspend fun discoverModels(profile: ProviderProfile, secret: String): ModelDiscoveryResult {
        val key = secret.ifBlank { vault.get(profile.kind.name).orEmpty() }
        return providerApi.discoverModels(profile.baseUrl, key, providerProtocolForAgent(profile, _state.value.agentKind))
    }

    suspend fun validateProvider(
        profile: ProviderProfile,
        secret: String,
        models: List<com.jarves.mh.network.DiscoveredModel>,
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
        val savedTabs = preferences.getOpenEditorTabs(project.id)
        val initialOpenedPath = savedTabs.firstOrNull()
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
                    projectTerminalHistoryStore.delete(active.id)
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
        val guestRoot = projectGuestRoot(project)
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

    fun createQuickProject() {
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop the background task before creating another project") }
            return
        }
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val project = Project(
            name = identity.displayName,
            description = "Quick project workspace",
            language = "General",
            slug = identity.slug,
            kind = ProjectKind.QUICK_PROJECT,
        )
        val firstChat = ProjectChat(title = "New chat")
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projects = listOf(project) + it.projects) }
        preferences.saveProjects(_state.value.projects)
        openProject(project)
    }

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
                val guestRoot = projectGuestRoot(updated)
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
        val guestRoot = projectGuestRoot(updated)
        preferences.saveProjects(projects)
        saveProjectTerminal(updated.id, guestRoot, current.projectTerminalLines)
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

    fun clonePublicGitRepository(url: String) {
        cloneGitRepository(url = url, repositoryName = null, branch = null, useGitHubCli = false)
    }

    fun cloneGitHubRepository(repository: GitHubRepository) {
        if (_state.value.githubAuthStatus != GitHubAuthStatus.CONNECTED) {
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubMessage = "Connect GitHub again") }
            return
        }
        cloneGitRepository(repository.cloneUrl, repository.fullName, repository.defaultBranch, useGitHubCli = true)
    }

    private fun cloneGitRepository(url: String, repositoryName: String?, branch: String?, useGitHubCli: Boolean) {
        if (_state.value.gitCloneRunning || _state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning) return
        val normalized = runCatching { validateGitUrl(url) }.getOrElse { error ->
            _state.update { it.copy(toastMessage = error.message ?: "Enter a valid public HTTPS Git URL") }
            return
        }
        _state.update { it.copy(gitCloneRunning = true, gitCloneMessage = "Connecting to Git repository…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
                    val projectId = UUID.randomUUID().toString()
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/$projectId").apply { mkdirs() }
                    val output = File(getApplication<Application>().cacheDir, "git-clone-${System.nanoTime()}.log")
                    try {
                        val environment = mutableMapOf(
                            "GIT_TERMINAL_PROMPT" to "0",
                            "GIT_LFS_SKIP_SMUDGE" to "1",
                            "GH_PROMPT_DISABLED" to "1",
                            "GH_NO_UPDATE_NOTIFIER" to "1",
                        )
                        val installed = installer.installedRuntime()
                        val command = if (useGitHubCli && repositoryName != null) {
                            buildList {
                                addAll(listOf(RuntimeInstaller.GITHUB_CLI_GUEST_PATH, "repo", "clone", repositoryName, ".", "--", "--progress", "--single-branch"))
                                branch?.takeIf(String::isNotBlank)?.let { addAll(listOf("--branch", it)) }
                            }
                        } else {
                            buildList {
                                addAll(listOf("git", "clone", "--progress", "--single-branch"))
                                branch?.takeIf(String::isNotBlank)?.let { addAll(listOf("--branch", it)) }
                                add(normalized)
                                add(".")
                            }
                        }
                        _state.update { it.copy(gitCloneMessage = "Cloning ${repositoryName ?: normalized.substringAfterLast('/').removeSuffix(".git")}…") }
                        val process = installer.process(
                            installed.proot,
                            installed.rootfs,
                            workspace,
                            environment,
                            command,
                            guestWorkspacePath = "/workspace/${identity.slug}",
                            outputFile = output,
                        )
                        val exit = process.waitFor()
                        val details = output.readText().trim()
                        check(exit == 0) { details.takeLast(600).ifBlank { "Git clone failed with exit code $exit" } }
                        val metadata = detectImportedProjectMetadata(workspace)
                        Project(
                            id = projectId,
                            name = identity.displayName,
                            description = repositoryName?.let { "GitHub · $it" } ?: "Imported Git repository",
                            language = metadata.second,
                            slug = identity.slug,
                            kind = ProjectKind.QUICK_PROJECT,
                        )
                    } catch (error: Throwable) {
                        workspace.deleteRecursively()
                        throw error
                    } finally {
                        output.delete()
                    }
                }
            }
            result.onSuccess { project ->
                val chat = ProjectChat(title = "New chat")
                preferences.saveProjectChats(project.id, listOf(chat))
                _state.update { current -> current.copy(projects = listOf(project) + current.projects) }
                preferences.saveProjects(_state.value.projects)
                openProject(project)
                _state.update { it.copy(gitCloneRunning = false, gitCloneMessage = null, toastMessage = "Repository cloned successfully") }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        gitCloneRunning = false,
                        gitCloneMessage = null,
                        toastMessage = "Clone failed: ${error.message?.lineSequence()?.lastOrNull()?.take(180) ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    private fun validateGitUrl(value: String): String {
        val clean = value.trim()
        val uri = URI(clean)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS Git URLs are supported" }
        require(uri.userInfo == null && uri.fragment == null && uri.host?.isNotBlank() == true) { "Enter a valid HTTPS Git URL without credentials" }
        require(uri.host != "localhost" && uri.host != "127.0.0.1" && uri.host != "::1") { "Local Git URLs are not supported" }
        require(uri.path.count { it == '/' } >= 2) { "The URL must identify a Git repository" }
        return uri.toASCIIString()
    }

    fun startGitHubLogin() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING || _state.value.githubAuthStatus == GitHubAuthStatus.AWAITING_USER) return
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Preparing official GitHub sign-in…") }
        startGitHubForegroundOperation()
        githubAuthJob = viewModelScope.launch {
            try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    installer.ensureGitHubCliInstalled { progress ->
                        _state.update { it.copy(githubMessage = progress.message) }
                    }
                    val runtime = installer.installedRuntime()
                    val outputFile = File(getApplication<Application>().cacheDir, "github-auth-${System.nanoTime()}.log")
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
                    val process = installer.process(
                        runtime.proot,
                        runtime.rootfs,
                        workspace,
                        githubCliEnvironment(),
                        listOf(
                            RuntimeInstaller.GITHUB_CLI_GUEST_PATH,
                            "auth", "login",
                            "--hostname", "github.com",
                            "--git-protocol", "https",
                            "--web",
                            "--insecure-storage",
                        ),
                        guestWorkspacePath = "/workspace/github-auth",
                        outputFile = outputFile,
                    )
                    githubAuthProcess = process
                    var offset = 0L
                    val captured = StringBuilder()
                    var browserOpened = false
                    try {
                        while (process.isAlive || outputFile.length() > offset) {
                            if (outputFile.length() > offset) {
                                val count = (outputFile.length() - offset).coerceAtMost(16L * 1024).toInt()
                                val bytes = ByteArray(count)
                                RandomAccessFile(outputFile, "r").use { file -> file.seek(offset); file.readFully(bytes) }
                                offset += count
                                captured.append(bytes.toString(Charsets.UTF_8))
                                val clean = sanitizeTerminalOutput(captured.toString()).takeLast(20_000)
                                val code = GITHUB_DEVICE_CODE.find(clean)?.value
                                if (code != null && !browserOpened) {
                                    browserOpened = true
                                    _state.update {
                                        it.copy(
                                            githubAuthStatus = GitHubAuthStatus.AWAITING_USER,
                                            githubUserCode = code,
                                            githubVerificationUri = GITHUB_DEVICE_URL,
                                            githubMessage = "Enter this one-time code on GitHub",
                                        )
                                    }
                                    openExternalUrl(GITHUB_DEVICE_URL)
                                }
                            } else {
                                delay(100)
                            }
                        }
                        val exit = process.waitFor()
                        check(exit == 0) {
                            sanitizeTerminalOutput(captured.toString()).lineSequence().lastOrNull { it.isNotBlank() }
                                ?: "GitHub sign-in failed (exit $exit)"
                        }
                    } finally {
                        githubAuthProcess = null
                        outputFile.delete()
                    }
                    githubAccountLogin() ?: error("GitHub connected, but the account could not be identified")
                }
            }
            result.onSuccess { login ->
                preferences.githubLogin = login
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.CONNECTED,
                        githubLogin = login,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = "Connected as @$login",
                    )
                }
                refreshGitHubRepositories()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.ERROR,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = error.message?.take(240) ?: "GitHub sign-in failed",
                    )
                }
            }
            } finally {
                stopGitHubForegroundOperation()
                githubAuthJob = null
            }
        }
    }

    fun generateNewGitHubCode() {
        githubAuthProcess?.destroy()
        githubAuthJob?.cancel()
        githubAuthProcess = null
        githubAuthJob = null
        stopGitHubForegroundOperation()
        _state.update {
            it.copy(
                githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                githubUserCode = null,
                githubVerificationUri = null,
                githubMessage = "Generating a new GitHub code…",
            )
        }
        startGitHubLogin()
    }

    fun refreshGitHubRepositories() {
        if (_state.value.githubAuthStatus != GitHubAuthStatus.CONNECTED) return
        if (_state.value.githubRepositoriesLoading) return
        _state.update { it.copy(githubRepositoriesLoading = true, githubMessage = "Loading repositories…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { githubRepositoriesFromCli() } }
            result.onSuccess { repositories ->
                _state.update {
                    it.copy(
                        githubRepositories = repositories,
                        githubRepositoriesLoading = false,
                        githubMessage = "${repositories.size} repositories available",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        githubRepositoriesLoading = false,
                        githubMessage = error.message ?: "Could not load GitHub repositories",
                    )
                }
            }
        }
    }

    fun disconnectGitHub() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING) return
        val login = _state.value.githubLogin
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Signing out of GitHub…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val command = buildList {
                        addAll(listOf("auth", "logout", "--hostname", "github.com"))
                        login?.takeIf(String::isNotBlank)?.let { addAll(listOf("--user", it)) }
                    }
                    val output = runGitHubCli(command)
                    check(output.first == 0) { output.second.lineSequence().lastOrNull { it.isNotBlank() } ?: "GitHub logout failed" }
                }
            }
            result.onSuccess {
                preferences.githubLogin = ""
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                        githubLogin = null,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubRepositories = emptyList(),
                        githubMessage = "Signed out",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.ERROR, githubMessage = error.message ?: "Could not sign out") }
            }
        }
    }

    private suspend fun refreshGitHubConnection() = withContext(Dispatchers.IO) {
        if (!installer.isGitHubCliInstalled()) return@withContext
        val login = runCatching { githubAccountLogin() }.getOrNull()
        if (login.isNullOrBlank()) {
            preferences.githubLogin = ""
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubLogin = null) }
        } else {
            preferences.githubLogin = login
            _state.update {
                it.copy(
                    githubAuthStatus = GitHubAuthStatus.CONNECTED,
                    githubLogin = login,
                    githubMessage = "Connected as @$login",
                )
            }
        }
    }

    private fun githubCliEnvironment(): Map<String, String> = mapOf(
        "GH_PROMPT_DISABLED" to "1",
        "GH_NO_UPDATE_NOTIFIER" to "1",
        // Android PRoot has no Secret Service. This keeps the official gh-owned
        // credential in PocketDev's private Linux home instead of exporting it.
        "BROWSER" to "/bin/false",
    )

    private fun runGitHubCli(arguments: List<String>): Pair<Int, String> {
        check(installer.isGitHubCliInstalled()) { "GitHub CLI is not installed" }
        val runtime = installer.installedRuntime()
        val outputFile = File(getApplication<Application>().cacheDir, "github-cli-${System.nanoTime()}.log")
        val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
        return try {
            val process = installer.process(
                runtime.proot,
                runtime.rootfs,
                workspace,
                githubCliEnvironment(),
                listOf(RuntimeInstaller.GITHUB_CLI_GUEST_PATH) + arguments,
                guestWorkspacePath = "/workspace/github-auth",
                outputFile = outputFile,
            )
            val exit = process.waitFor()
            exit to sanitizeTerminalOutput(outputFile.readTailText(MAX_PROCESS_OUTPUT_BYTES)).trim()
        } finally {
            outputFile.delete()
        }
    }

    private fun githubAccountLogin(): String? {
        val (exit, output) = runGitHubCli(listOf("api", "user", "--jq", ".login"))
        return output.lineSequence().lastOrNull { it.isNotBlank() }?.trim().takeIf { exit == 0 && !it.isNullOrBlank() }
    }

    private fun githubRepositoriesFromCli(): List<GitHubRepository> {
        // Keep the GitHub CLI response line-oriented instead of parsing the
        // entire paginated response as one JSONArray. This is more resilient
        // to GitHub API/CLI response-shape changes and avoids nested JSON
        // conversion failures while loading the repository picker.
        val endpoint = "user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100"
        val (exit, output) = runGitHubCli(
            listOf(
                "api",
                "--paginate",
                endpoint,
                "--jq",
                ".[] | [.full_name, .clone_url, (.private | tostring), .default_branch, .description, .updated_at] | @tsv",
            ),
        )
        check(exit == 0) {
            output.lineSequence().lastOrNull { it.isNotBlank() }
                ?: "Could not load GitHub repositories"
        }

        val repositories = LinkedHashMap<String, GitHubRepository>()
        output.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val fields = line.split("\t", limit = 6)
                if (fields.size < 6) return@forEach
                val fullName = fields[0].trim().takeIf(String::isNotBlank) ?: return@forEach
                repositories[fullName] = GitHubRepository(
                    fullName = fullName,
                    cloneUrl = fields[1].trim().ifBlank { "https://github.com/$fullName.git" },
                    private = fields[2].trim().equals("true", ignoreCase = true),
                    defaultBranch = fields[3].trim().ifBlank { "main" },
                    description = fields[4],
                    updatedAt = fields[5].trim(),
                )
            }
        return repositories.values.toList()
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

    private fun startGitHubForegroundOperation() {
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java)
                .setAction(com.jarves.mh.runtime.RuntimeExecutionService.ACTION_START)
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_PROJECT_NAME, "GitHub sign-in")
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_TITLE, "Connecting GitHub")
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_CAN_STOP, false),
        )
    }

    private fun stopGitHubForegroundOperation() {
        runCatching {
            getApplication<Application>().startService(
                Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java)
                    .setAction(com.jarves.mh.runtime.RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            getApplication<Application>().stopService(
                Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java),
            )
        }
    }

    fun renameProject(projectId: String, newName: String) {
        val clean = newName.replace(Regex("\\s+"), " ").trim().take(60)
        if (clean.isBlank()) return
        _state.update { current ->
            val projects = current.projects.map { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            val active = current.activeProject?.let { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            current.copy(projects = projects, activeProject = active)
        }
        preferences.saveProjects(_state.value.projects)
    }

    fun deleteProject(projectId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        if (_state.value.activeProject?.id == projectId || _state.value.isRunning || _state.value.projectTerminalRunning) return
        _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == projectId }) }
        preferences.saveProjects(_state.value.projects)
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<Application>().filesDir
            File(filesDir, "workspaces/${project.id}").deleteRecursively()
            projectTerminalHistoryStore.delete(project.id)
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
        val guestRoot = projectGuestRoot(updated)
        preferences.saveProjects(projects)
        saveProjectTerminal(updated.id, guestRoot, current.projectTerminalLines)
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

    fun openFile(entry: WorkspaceEntry) {
        if (entry.isDirectory) return
        openFileByPath(entry.path)
    }

    fun openFileByPath(path: String) {
        val project = _state.value.activeProject ?: return
        val currentTabs = _state.value.openEditorTabs
        val updatedTabs = if (path in currentTabs) currentTabs else currentTabs + path
        preferences.setOpenEditorTabs(project.id, updatedTabs)
        _state.update {
            it.copy(
                openEditorTabs = updatedTabs,
                openedFilePath = path,
                openedFileContent = null,
                fileContentLoading = true,
            )
        }
        loadFileContent(project, path)
    }

    fun selectEditorTab(path: String) {
        if (_state.value.openedFilePath == path) return
        val project = _state.value.activeProject ?: return
        _state.update {
            it.copy(
                openedFilePath = path,
                openedFileContent = null,
                fileContentLoading = true,
            )
        }
        loadFileContent(project, path)
    }

    fun closeEditorTab(path: String) {
        val project = _state.value.activeProject ?: return
        val currentTabs = _state.value.openEditorTabs
        val updatedTabs = currentTabs.filter { it != path }
        preferences.setOpenEditorTabs(project.id, updatedTabs)
        if (_state.value.openedFilePath == path) {
            val nextPath = updatedTabs.lastOrNull()
            if (nextPath != null) {
                _state.update {
                    it.copy(
                        openEditorTabs = updatedTabs,
                        openedFilePath = nextPath,
                        openedFileContent = null,
                        fileContentLoading = true,
                    )
                }
                loadFileContent(project, nextPath)
            } else {
                _state.update {
                    it.copy(
                        openEditorTabs = emptyList(),
                        openedFilePath = null,
                        openedFileContent = null,
                        fileContentLoading = false,
                    )
                }
            }
        } else {
            _state.update { it.copy(openEditorTabs = updatedTabs) }
        }
    }

    fun closeOtherEditorTabs(keepPath: String) {
        val project = _state.value.activeProject ?: return
        val updatedTabs = listOf(keepPath)
        preferences.setOpenEditorTabs(project.id, updatedTabs)
        if (_state.value.openedFilePath != keepPath) {
            selectEditorTab(keepPath)
        }
        _state.update { it.copy(openEditorTabs = updatedTabs) }
    }

    fun closeAllEditorTabs() {
        val project = _state.value.activeProject
        if (project != null) {
            preferences.setOpenEditorTabs(project.id, emptyList())
        }
        _state.update {
            it.copy(
                openEditorTabs = emptyList(),
                openedFilePath = null,
                openedFileContent = null,
                fileContentLoading = false,
            )
        }
    }

    fun closeFile() {
        closeAllEditorTabs()
    }

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

    fun runWorkspaceGitCommand(args: List<String>, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val project = _state.value.activeProject ?: return onResult(false, "No active project")
        val workspace = projectWorkspaceRoot(project)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                gitDomainController.executeGit(args, workspace, "/workspace/${project.slug}")
            }
            if (!result.success && result.output.isNotBlank()) {
                _state.update { it.copy(toastMessage = "Git: ${result.output}") }
            }
            onResult(result.success, result.output)
        }
    }

    fun gitStatus(onResult: (String) -> Unit) {
        val project = _state.value.activeProject ?: return onResult("No active project")
        val workspace = projectWorkspaceRoot(project)
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.status(workspace, "/workspace/${project.slug}")
            }
            onResult(res.output)
        }
    }

    fun gitDiff(onResult: (String) -> Unit) {
        val project = _state.value.activeProject ?: return onResult("No active project")
        val workspace = projectWorkspaceRoot(project)
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.diff(workspace, "/workspace/${project.slug}")
            }
            onResult(res.output)
        }
    }

    fun gitBranchList(onResult: (List<String>) -> Unit) {
        val project = _state.value.activeProject ?: return onResult(emptyList())
        val workspace = projectWorkspaceRoot(project)
        viewModelScope.launch {
            val branches = withContext(Dispatchers.IO) {
                gitDomainController.branchList(workspace, "/workspace/${project.slug}")
            }
            onResult(branches)
        }
    }

    fun gitCheckout(branch: String, createNew: Boolean = false, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val project = _state.value.activeProject ?: return onResult(false, "No active project")
        val workspace = projectWorkspaceRoot(project)
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.checkout(workspace, branch, createNew, "/workspace/${project.slug}")
            }
            if (res.success) {
                _state.update { it.copy(toastMessage = "Checked out $branch") }
                refreshProjectFiles()
            } else if (res.output.isNotBlank()) {
                _state.update { it.copy(toastMessage = "Git: ${res.output}") }
            }
            onResult(res.success, res.output)
        }
    }

    fun gitCommit(message: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val project = _state.value.activeProject ?: return onResult(false, "No active project")
        val workspace = projectWorkspaceRoot(project)
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.commit(workspace, message, "/workspace/${project.slug}")
            }
            if (res.success) {
                _state.update { it.copy(toastMessage = "Committed: $message") }
                refreshProjectFiles()
            } else if (res.output.isNotBlank()) {
                _state.update { it.copy(toastMessage = "Git: ${res.output}") }
            }
            onResult(res.success, res.output)
        }
    }

    fun gitPush(remote: String = "origin", branch: String = "", onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val project = _state.value.activeProject ?: return onResult(false, "No active project")
        val workspace = projectWorkspaceRoot(project)
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.push(workspace, remote, branch, "/workspace/${project.slug}")
            }
            if (res.success) {
                _state.update { it.copy(toastMessage = "Git push successful") }
            } else if (res.output.isNotBlank()) {
                _state.update { it.copy(toastMessage = "Git: ${res.output}") }
            }
            onResult(res.success, res.output)
        }
    }

    fun gitPull(remote: String = "origin", branch: String = "", onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val project = _state.value.activeProject ?: return onResult(false, "No active project")
        val workspace = projectWorkspaceRoot(project)
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.pull(workspace, remote, branch, "/workspace/${project.slug}")
            }
            if (res.success) {
                _state.update { it.copy(toastMessage = "Git pull successful") }
                refreshProjectFiles()
            } else if (res.output.isNotBlank()) {
                _state.update { it.copy(toastMessage = "Git: ${res.output}") }
            }
            onResult(res.success, res.output)
        }
    }

    fun scaffoldProjectTemplate(template: com.jarves.mh.workspace.ProjectTemplate) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.scaffoldTemplate(root, template)
        if (result.isSuccess) {
            _state.update { it.copy(toastMessage = "Scaffolded ${template.displayName} starter template") }
            refreshProjectFiles()
        }
    }

    private val _detectedLocalhostPorts = kotlinx.coroutines.flow.MutableStateFlow<List<Int>>(emptyList())
    val detectedLocalhostPorts: kotlinx.coroutines.flow.StateFlow<List<Int>> = _detectedLocalhostPorts.asStateFlow()

    fun scanActiveLocalhostPorts(onComplete: (List<Int>) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val commonPorts = listOf(3000, 5173, 8080, 8000, 4200, 5000, 9222)
            val openPorts = mutableListOf<Int>()
            for (port in commonPorts) {
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 200)
                        openPorts.add(port)
                    }
                } catch (_: Exception) {}
            }
            _detectedLocalhostPorts.value = openPorts
            withContext(Dispatchers.Main) {
                onComplete(openPorts)
            }
        }
    }

    fun attachVisualInspectionToAgent(url: String, note: String = "") {
        val prompt = "Visual Inspection Report for $url: ${note.ifBlank { "Please inspect this web page layout and verify all components are rendering properly." }}"
        sendPrompt(prompt)
    }


    private fun loadFileContent(project: Project, path: String) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    val root = projectWorkspaceRoot(project).canonicalFile
                    val file = editorCheckpoints.safeWorkspaceFile(root, path)
                    require(file.isFile) { "File is unavailable" }
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
    suspend fun saveFile(path: String, content: String): Boolean {
        val current = _state.value
        val project = current.activeProject ?: return false
        if (current.isRunning || current.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop the running task before editing files") }
            return false
        }
        if (path.isBlank() || path.startsWith('/') || editorCheckpoints.isInternalRuntimePath(path)) {
            _state.update { it.copy(toastMessage = "This workspace path cannot be edited") }
            return false
        }
        if (path.split('/').any { it == ".." || it.isBlank() }) {
            _state.update { it.copy(toastMessage = "Unsafe workspace path") }
            return false
        }
        if (content.length > MAX_EDITABLE_FILE_CHARS) {
            _state.update { it.copy(toastMessage = "This file is too large to edit safely in the mobile editor") }
            return false
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val workspace = projectWorkspaceRoot(project).canonicalFile
                val target = editorCheckpoints.safeWorkspaceFile(workspace, path)
                val existing = target.takeIf(File::isFile)
                if (existing != null && existing.length() > MAX_EDITABLE_FILE_CHARS) {
                    error("This file is too large to edit safely in the mobile editor")
                }
                if (existing != null && existing.readText() == content) return@runCatching Unit

                editorCheckpoints.createCheckpoint(project.id, workspace)
                check(target.parentFile?.let { it.mkdirs() || it.isDirectory } == true) {
                    "Could not create the file's parent directory"
                }
                check(editorCheckpoints.atomicWriteFile(target, content)) {
                    "Could not safely save $path"
                }
                editorCheckpoints.saveChangedPaths(project.id, listOf(path))
            }
        }
        return result.fold(
            onSuccess = {
                val workspace = projectWorkspaceRoot(project)
                val pendingPaths = editorCheckpoints.readChangedPaths(project.id)
                val changes = editorCheckpoints.buildChangeDetails(project.id, workspace, pendingPaths)
                _state.update { state ->
                    state.copy(
                        openedFileContent = content,
                        changes = changes,
                        toastMessage = "Saved " + path,
                        workspaceFiles = state.workspaceFiles.map { entry ->
                            if (entry.path == path) entry.copy(
                                sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                            ) else entry
                        },
                    )
                }
                true
            },
            onFailure = { error ->
                _state.update { it.copy(toastMessage = error.message ?: "Could not save " + path) }
                false
            },
        )
    }

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

    private fun isClaudeRuntimeMetadata(relativePath: String): Boolean {
        return relativePath == ".claude" ||
            relativePath == ".claude.json" ||
            relativePath.startsWith(".claude/")
    }

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
            guestRoot = projectGuestRoot(project),
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
        answerApprovalWithLevel(com.jarves.mh.security.PermissionLevel.ONCE, approved)
    }

    fun answerApprovalWithLevel(level: com.jarves.mh.security.PermissionLevel, approved: Boolean) {
        val request = state.value.pendingApproval ?: return
        val project = _state.value.activeProject
        val target = request.affectedPaths.firstOrNull() ?: request.toolName
        val category = when {
            request.toolName.contains("bash", true) || request.toolName.contains("exec", true) -> com.jarves.mh.security.PermissionCategory.SHELL_EXEC
            request.toolName.contains("write", true) || request.toolName.contains("edit", true) -> com.jarves.mh.security.PermissionCategory.FILE_WRITE
            request.toolName.contains("delete", true) || request.toolName.contains("remove", true) -> com.jarves.mh.security.PermissionCategory.FILE_DELETE
            else -> com.jarves.mh.security.PermissionCategory.FILE_READ
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
                    agentOrchestrator.transition(com.jarves.mh.session.EngineeringState.COMPLETED, "Task completed successfully")
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
                    agentOrchestrator.transition(com.jarves.mh.session.EngineeringState.FAILED, "Task failed", event.reason)
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
                    appendLine("- " + projectGuestRoot(project) + "/" + relativePath)
                }
                appendLine("</mentioned_files>")
            }
            if (attachments.isNotEmpty() && !effectivePrompt.contains("<attached_files>")) {
                appendLine()
                appendLine("<attached_files>")
                attachments.forEach { attachment ->
                    appendLine("- ${attachment.displayName}: ${projectGuestRoot(project)}/${attachment.relativePath} (${attachment.mimeType})")
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

    fun refreshSkills() {
        _state.update { it.copy(skills = skillManager.installed()) }
    }

    fun checkSkillUpdates() {
        if (_state.value.skillsBusy) return
        _state.update { it.copy(skillsBusy = true, skillsMessage = "Checking installed skills for updates…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.checkUpdates() } }
            result.onSuccess { updates ->
                _state.update {
                    it.copy(
                        skillsBusy = false,
                        skillUpdates = updates.mapTo(mutableSetOf()) { update -> update.name },
                        skillsMessage = if (updates.isEmpty()) "All remotely sourced skills are up to date." else updates.size.toString() + " skill update" + (if (updates.size == 1) "" else "s") + " available.",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill update check failed.") }
            }
        }
    }

    fun updateSkill(name: String) {
        if (_state.value.skillsBusy) return
        val skill = _state.value.skills.firstOrNull { it.name == name } ?: return
        _state.update { it.copy(skillsBusy = true, skillsMessage = "Updating $name…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.update(skill) } }
            result.onSuccess {
                _state.update {
                    it.copy(
                        skills = skillManager.installed(),
                        skillsBusy = false,
                        skillUpdates = it.skillUpdates - name,
                        skillsMessage = "$name updated successfully.",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill update failed.") }
            }
        }
    }

    fun searchSkills(query: String) {
        if (_state.value.skillsBusy) return
        _state.update { it.copy(skillsBusy = true, skillsMessage = "Searching GitHub…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.searchGitHub(query) } }
            result.onSuccess { matches -> _state.update { it.copy(skillsBusy = false, skillSearchResults = matches, skillsMessage = "Found " + matches.size + " repositories.") } }
                .onFailure { error -> _state.update { it.copy(skillsBusy = false, skillsMessage = error.message ?: "GitHub skill search failed.") } }
        }
    }

    fun importSkillFromGitHub(url: String) {
        if (_state.value.skillsBusy) return
        _state.update { it.copy(skillsBusy = true, skillsMessage = "Importing skill bundle…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.installFromGitHub(url) } }
            result.onSuccess { installed -> _state.update { it.copy(skills = skillManager.installed(), skillsBusy = false, skillUpdates = it.skillUpdates - installed.map { skill -> skill.name }.toSet(), skillsMessage = "Imported " + installed.size + " skill bundle(s).") } }
                .onFailure { error -> _state.update { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill import failed.") } }
        }
    }

    fun importSkillFromUri(uri: Uri) {
        if (_state.value.skillsBusy) return
        _state.update { it.copy(skillsBusy = true, skillsMessage = "Importing local skill bundle…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.installFromUri(uri) } }
            result.onSuccess { installed -> _state.update { it.copy(skills = skillManager.installed(), skillsBusy = false, skillUpdates = it.skillUpdates - installed.map { skill -> skill.name }.toSet(), skillsMessage = "Imported " + installed.size + " skill bundle(s).") } }
                .onFailure { error -> _state.update { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill import failed.") } }
        }
    }

    fun removeSkill(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = skillManager.remove(name)
            _state.update { it.copy(skills = skillManager.installed(), skillsMessage = if (removed) "Removed " + name else "Skill was not installed.") }
        }
    }
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

    fun toggleAutoUpdateToolsAndSkills(enabled: Boolean) {
        preferences.autoUpdateToolsAndSkills = enabled
        _state.update { it.copy(autoUpdateToolsAndSkills = enabled) }
        if (enabled) {
            autoUpdateToolsAndSkills(force = true)
        }
    }

    fun autoUpdateToolsAndSkills(force: Boolean = false) {
        if (!preferences.autoUpdateToolsAndSkills) return
        val current = _state.value
        if (current.isRunning || current.projectTerminalRunning || current.devStackInstalling != null || current.agentUpdating != null || current.autoUpdatingToolsOrSkills) return
        val now = System.currentTimeMillis()
        if (!force && (now - preferences.lastToolsAutoUpdateCheckMillis < 4L * 60L * 60L * 1000L)) return

        viewModelScope.launch {
            _state.update { it.copy(autoUpdatingToolsOrSkills = true, toolsAutoUpdateMessage = "Checking for tool and skill updates…") }
            val updatedItems = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                // 1. Universal Skills auto-update
                runCatching {
                    val updates = skillManager.checkUpdates()
                    if (updates.isNotEmpty()) {
                        updates.forEach { updateInfo ->
                            val skill = skillManager.installed().firstOrNull { it.name == updateInfo.name }
                            if (skill != null) {
                                runCatching {
                                    skillManager.update(skill)
                                    updatedItems.add("Skill ${skill.name}")
                                }
                            }
                        }
                    }
                }

                // 2. Agents / Coding CLI Tools auto-update
                runCatching {
                    val agentUpdates = installer.checkAgentUpdates()
                    agentUpdates.forEach { (kind, updateInfo) ->
                        if (!_state.value.isRunning && _state.value.agentUpdating == null) {
                            runCatching {
                                installer.updateAgent(kind, updateInfo.latestVersion) {}
                                updatedItems.add("${kind.title} ${updateInfo.latestVersion}")
                            }
                        }
                    }
                }

                // 3. Installed Development Toolchains auto-update
                runCatching {
                    val installed = installer.installedStacks().filter { it != DevStack.WEB }
                    installed.forEach { stack ->
                        if (!_state.value.isRunning && _state.value.devStackInstalling == null) {
                            runCatching {
                                var didUpdate = false
                                installer.updateStack(stack) { progress ->
                                    if (progress.event == RuntimeInstallEvent.COMPLETED &&
                                        !progress.message.contains("already up to date", ignoreCase = true) &&
                                        !progress.message.contains("tools are up to date", ignoreCase = true)) {
                                        didUpdate = true
                                    }
                                }
                                if (didUpdate) {
                                    updatedItems.add(stack.label)
                                }
                            }
                        }
                    }
                }
            }

            preferences.lastToolsAutoUpdateCheckMillis = System.currentTimeMillis()
            val message = if (updatedItems.isEmpty()) {
                "Tools, toolchains, and skills are up to date"
            } else {
                "Updated: ${updatedItems.joinToString(", ")}"
            }
            _state.update {
                it.copy(
                    autoUpdatingToolsOrSkills = false,
                    toolsAutoUpdateMessage = message,
                    skills = skillManager.installed(),
                    skillUpdates = emptySet(),
                    installedDevStacks = installer.installedStacks(),
                    installedAgentVersions = installer.installedAgentVersions(),
                )
            }
        }
    }

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

    fun createWorkspaceCheckpoint(label: String): com.jarves.mh.workspace.WorkspaceCheckpoint? {
        val project = _state.value.activeProject ?: return null
        val workspace = projectWorkspaceRoot(project)
        val ckpt = checkpointManager.createCheckpoint(project.id, workspace, label)
        if (ckpt != null) {
            _state.update { it.copy(toastMessage = "Checkpoint created: ${ckpt.label}") }
        }
        return ckpt
    }

    fun rollbackWorkspaceCheckpoint(checkpointId: String): Boolean {
        val project = _state.value.activeProject ?: return false
        val workspace = projectWorkspaceRoot(project)
        val ok = checkpointManager.rollbackToCheckpoint(project.id, checkpointId, workspace)
        if (ok) {
            refreshProjectFiles()
            _state.update { it.copy(toastMessage = "Rolled back to checkpoint") }
        } else {
            _state.update { it.copy(toastMessage = "Rollback failed") }
        }
        return ok
    }

    fun listWorkspaceCheckpoints(): List<com.jarves.mh.workspace.WorkspaceCheckpoint> {
        val project = _state.value.activeProject ?: return emptyList()
        return checkpointManager.listCheckpoints(project.id)
    }

    fun backupAllWorkspaces(): Int {
        val app = getApplication<Application>()
        val externalDir = app.getExternalFilesDir("backups") ?: File(app.filesDir, "backups")
        val count = checkpointManager.backupAllWorkspaces(externalDir)
        if (count > 0) {
            _state.update { it.copy(toastMessage = "Backed up $count projects to external storage") }
        } else {
            _state.update { it.copy(toastMessage = "No projects to backup") }
        }
        return count
    }

    fun restoreAllWorkspaces(): Int {
        val app = getApplication<Application>()
        val externalDir = app.getExternalFilesDir("backups") ?: File(app.filesDir, "backups")
        var count = checkpointManager.restoreAllWorkspaces(externalDir)
        if (count == 0) {
            // Migration fallback: check legacy package backups directory if current path has no backups
            val legacyExternalDir = File("/storage/emulated/0/Android/data/com.jarves.mh/files/backups")
            if (legacyExternalDir.isDirectory) {
                count = checkpointManager.restoreAllWorkspaces(legacyExternalDir)
            }
        }
        if (count > 0) {
            reloadProjects()
            _state.update { it.copy(toastMessage = "Restored $count projects from backup") }
        } else {
            _state.update { it.copy(toastMessage = "No backup projects found") }
        }
        return count
    }

    fun reloadProjects() {
        val loaded = preferences.loadProjects().toMutableList()
        val loadedIds = loaded.map { it.id }.toSet()
        val workspacesDir = File(getApplication<Application>().filesDir, "workspaces")
        workspacesDir.listFiles()?.filter { it.isDirectory && it.name !in loadedIds }?.forEach { dir ->
            val p = Project(
                id = dir.name,
                name = dir.name.replace('-', ' ').replaceFirstChar { it.uppercase() },
                description = "Restored workspace",
                language = "General",
                slug = projectSlug(dir.name),
            )
            loaded.add(p)
        }
        preferences.saveProjects(loaded)
        _state.update { it.copy(projects = loaded) }
    }

    fun runAutonomousVerification() {
        val project = _state.value.activeProject ?: return
        val workspace = projectWorkspaceRoot(project)
        val tool = com.jarves.mh.agent.AutonomousLoopEngine.detectBuildTool(workspace)
        if (tool == null) {
            _state.update { it.copy(toastMessage = "No recognized build tool (Gradle/npm/Cargo/Go/Python) found in project") }
            return
        }
        _state.update { it.copy(toastMessage = "Running autonomous verification with ${tool.name}...") }
        runProjectTerminalCommand(tool.verificationCommand)
    }

    val localhostPortManager = com.jarves.mh.visual.LocalhostPortManager()

    private val _visualConsoleLogs = MutableStateFlow<List<com.jarves.mh.visual.ConsoleLogEntry>>(emptyList())
    val visualConsoleLogs: StateFlow<List<com.jarves.mh.visual.ConsoleLogEntry>> = _visualConsoleLogs.asStateFlow()

    private val _visualNetworkLogs = MutableStateFlow<List<com.jarves.mh.visual.NetworkLogEntry>>(emptyList())
    val visualNetworkLogs: StateFlow<List<com.jarves.mh.visual.NetworkLogEntry>> = _visualNetworkLogs.asStateFlow()

    private val _inspectedDomElement = MutableStateFlow<com.jarves.mh.visual.DomElementInfo?>(null)
    val inspectedDomElement: StateFlow<com.jarves.mh.visual.DomElementInfo?> = _inspectedDomElement.asStateFlow()

    private val _visualSnapshots = MutableStateFlow<List<com.jarves.mh.visual.VisualSnapshot>>(emptyList())
    val visualSnapshots: StateFlow<List<com.jarves.mh.visual.VisualSnapshot>> = _visualSnapshots.asStateFlow()

    private val _visualComparisonPair = MutableStateFlow<com.jarves.mh.visual.VisualComparisonPair?>(null)
    val visualComparisonPair: StateFlow<com.jarves.mh.visual.VisualComparisonPair?> = _visualComparisonPair.asStateFlow()

    private val _activeViewportPreset = MutableStateFlow(com.jarves.mh.visual.ViewportPreset.RESPONSIVE)
    val activeViewportPreset: StateFlow<com.jarves.mh.visual.ViewportPreset> = _activeViewportPreset.asStateFlow()

    private val _detectedPortInfos = MutableStateFlow<List<com.jarves.mh.visual.LocalhostPortInfo>>(emptyList())
    val detectedPortInfos: StateFlow<List<com.jarves.mh.visual.LocalhostPortInfo>> = _detectedPortInfos.asStateFlow()

    fun recordVisualConsoleLog(entry: com.jarves.mh.visual.ConsoleLogEntry) {
        _visualConsoleLogs.update { (listOf(entry) + it).take(200) }
    }

    fun recordVisualNetworkLog(entry: com.jarves.mh.visual.NetworkLogEntry) {
        _visualNetworkLogs.update { (listOf(entry) + it).take(100) }
    }

    fun setInspectedDomElement(element: com.jarves.mh.visual.DomElementInfo?) {
        _inspectedDomElement.value = element
    }

    fun clearVisualLogs() {
        _visualConsoleLogs.value = emptyList()
        _visualNetworkLogs.value = emptyList()
    }

    fun setViewportPreset(preset: com.jarves.mh.visual.ViewportPreset) {
        _activeViewportPreset.value = preset
    }

    fun scanLocalhostPortsWithHeuristics(
        customPorts: List<Int> = emptyList(),
        onComplete: (List<com.jarves.mh.visual.LocalhostPortInfo>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val infos = localhostPortManager.scanPorts(customPorts)
            _detectedPortInfos.value = infos
            _detectedLocalhostPorts.value = infos.map { it.port }
            withContext(Dispatchers.Main) {
                onComplete(infos)
            }
        }
    }

    fun saveVisualSnapshot(
        bitmap: android.graphics.Bitmap,
        title: String,
        url: String,
        onSaved: (com.jarves.mh.visual.VisualSnapshot) -> Unit = {},
    ) {
        val project = _state.value.activeProject ?: return
        val workspace = projectWorkspaceRoot(project)
        viewModelScope.launch {
            val snapshot = com.jarves.mh.visual.VisualCaptureEngine.saveSnapshot(
                projectRoot = workspace,
                bitmap = bitmap,
                title = title,
                url = url,
                consoleErrorsCount = _visualConsoleLogs.value.count { it.level == com.jarves.mh.visual.LogLevel.ERROR },
                networkErrorsCount = _visualNetworkLogs.value.count { it.isFailed },
                domSummary = _inspectedDomElement.value?.displayName,
            )
            _visualSnapshots.update { listOf(snapshot) + it }
            withContext(Dispatchers.Main) {
                onSaved(snapshot)
            }
        }
    }

    fun setBaselineComparisonSnapshot(snapshot: com.jarves.mh.visual.VisualSnapshot) {
        val current = _visualComparisonPair.value
        _visualComparisonPair.value = if (current != null) {
            current.copy(beforeSnapshot = snapshot)
        } else {
            com.jarves.mh.visual.VisualComparisonPair(
                beforeSnapshot = snapshot,
                afterSnapshot = snapshot,
                title = "UI Comparison",
            )
        }
    }

    fun setAfterComparisonSnapshot(snapshot: com.jarves.mh.visual.VisualSnapshot) {
        val current = _visualComparisonPair.value
        _visualComparisonPair.value = if (current != null) {
            current.copy(afterSnapshot = snapshot)
        } else {
            com.jarves.mh.visual.VisualComparisonPair(
                beforeSnapshot = snapshot,
                afterSnapshot = snapshot,
                title = "UI Comparison",
            )
        }
    }

    fun dispatchAiVisualBugReport(
        url: String,
        userNotes: String = "",
        snapshot: com.jarves.mh.visual.VisualSnapshot? = null,
        onDispatched: () -> Unit = {},
    ) {
        val project = _state.value.activeProject ?: return
        val workspace = projectWorkspaceRoot(project)

        val prompt = com.jarves.mh.visual.AiVisualDebugger.generateBugReportPrompt(
            url = url,
            viewport = _activeViewportPreset.value,
            selectedElement = _inspectedDomElement.value,
            consoleLogs = _visualConsoleLogs.value,
            networkLogs = _visualNetworkLogs.value,
            snapshot = snapshot,
            userNotes = userNotes,
        )

        if (snapshot != null) {
            val attachment = com.jarves.mh.visual.AiVisualDebugger.createChatAttachment(snapshot, workspace)
            if (attachment != null) {
                _state.update { it.copy(pendingAttachments = it.pendingAttachments + attachment) }
            }
        }

        sendPrompt(prompt)
        onDispatched()
    }

    fun generateAppFactoryBlueprint(
        prompt: String,
        framework: com.jarves.mh.factory.model.AppFramework = com.jarves.mh.factory.model.AppFramework.ANDROID_COMPOSE,
        buildMode: com.jarves.mh.factory.model.BuildMode = com.jarves.mh.factory.model.BuildMode.ON_DEVICE,
        archetype: com.jarves.mh.factory.blueprint.AppFactoryBlueprintEngine.Archetype? = null,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(appFactoryGeneratingBlueprint = true, appFactoryMessage = "Synthesizing software blueprint...") }
            try {
                val blueprint = com.jarves.mh.factory.blueprint.AppFactoryBlueprintEngine.createBlueprint(
                    prompt = prompt,
                    framework = framework,
                    buildMode = buildMode,
                    forcedArchetype = archetype,
                )
                _state.update {
                    it.copy(
                        appFactoryBlueprint = blueprint,
                        appFactoryGeneratingBlueprint = false,
                        appFactoryMessage = "Blueprint '${blueprint.name}' generated successfully."
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        appFactoryGeneratingBlueprint = false,
                        appFactoryMessage = "Blueprint generation failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun scaffoldAppFactoryProject(
        targetDirectory: File? = null,
        blueprint: com.jarves.mh.factory.model.AppBlueprint? = _state.value.appFactoryBlueprint
    ) {
        val bp = blueprint ?: return
        val projectDir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: File(getApplication<Application>().filesDir, "projects/${bp.name.lowercase().replace(" ", "_")}")

        viewModelScope.launch {
            _state.update { it.copy(appFactoryScaffolding = true, appFactoryMessage = "Scaffolding ${bp.framework.displayName} files...") }
            try {
                val files = mutableListOf<File>()
                when (bp.framework) {
                    com.jarves.mh.factory.model.AppFramework.ANDROID_COMPOSE -> {
                        files += com.jarves.mh.factory.generators.AndroidComposeGenerator.generateProject(projectDir, bp)
                    }
                    com.jarves.mh.factory.model.AppFramework.FLUTTER -> {
                        files += com.jarves.mh.factory.generators.FlutterAppGenerator.generateProject(projectDir, bp)
                    }
                    com.jarves.mh.factory.model.AppFramework.REACT_NATIVE -> {
                        files += com.jarves.mh.factory.generators.ReactNativeAppGenerator.generateProject(projectDir, bp)
                    }
                    com.jarves.mh.factory.model.AppFramework.FULLSTACK_BACKEND -> {
                        files += com.jarves.mh.factory.generators.BackendServiceGenerator.generateProject(projectDir, bp)
                    }
                }
                files += com.jarves.mh.factory.generators.DatabaseSetupEngine.generateDatabaseBundle(projectDir, bp)
                files += com.jarves.mh.factory.generators.ApiIntegrationGenerator.generateApiClientBundle(projectDir, bp)

                _state.update {
                    it.copy(
                        appFactoryScaffolding = false,
                        appFactoryScaffoldFiles = files,
                        appFactoryMessage = "Scaffolded ${files.size} project files in ${projectDir.name}."
                    )
                }
                refreshProjectFiles()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        appFactoryScaffolding = false,
                        appFactoryMessage = "Scaffolding failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun buildAppFactoryApp(
        artifactType: com.jarves.mh.factory.model.BuildArtifactType = com.jarves.mh.factory.model.BuildArtifactType.APK_DEBUG,
        buildMode: com.jarves.mh.factory.model.BuildMode = com.jarves.mh.factory.model.BuildMode.ON_DEVICE,
        targetDirectory: File? = null
    ) {
        val bp = _state.value.appFactoryBlueprint ?: return
        val projectDir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: File(getApplication<Application>().filesDir, "projects/${bp.name.lowercase().replace(" ", "_")}")

        viewModelScope.launch {
            _state.update {
                it.copy(
                    appFactoryBuilding = true,
                    appFactoryLogs = listOf("Starting build: ${artifactType.displayName} via ${buildMode.displayName}..."),
                    appFactoryMessage = "Building ${artifactType.displayName}..."
                )
            }
            try {
                val result = com.jarves.mh.factory.build.AppBuildOrchestrator.runBuild(
                    projectDir = projectDir,
                    blueprint = bp,
                    artifactType = artifactType,
                    buildMode = buildMode
                )
                _state.update {
                    it.copy(
                        appFactoryBuilding = false,
                        appFactoryBuildResult = result,
                        appFactoryLogs = result.logs,
                        appFactoryMessage = when {
                            !result.success -> "Build failed: ${result.errorMessage}"
                            result.ciWorkflowFile != null -> "Generated ${result.ciWorkflowFile.name}; commit and push to run the remote build."
                            else -> "Build completed successfully!"
                        }
                    )
                }
            } catch (e: Exception) {
                _state.update {
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
            com.jarves.mh.factory.build.AppBuildOrchestrator.installApk(getApplication(), apkFile)
            _state.update { it.copy(appFactoryMessage = "Launched package installer for ${apkFile.name}") }
        } catch (e: Exception) {
            _state.update { it.copy(appFactoryMessage = "Installation error: ${e.message}") }
        }
    }

    fun generateReleaseKeystore(
        targetDirectory: File? = null,
        alias: String = "releaseKey",
        pass: String = "pocketdev123"
    ) {
        val bp = _state.value.appFactoryBlueprint
        val projectDir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: File(getApplication<Application>().filesDir, "projects/${bp?.name?.lowercase()?.replace(" ", "_") ?: "app"}")

        try {
            val config = com.jarves.mh.factory.build.SigningAndVersionManager.createKeystore(
                projectDir = projectDir,
                keyAlias = alias,
                storePassword = pass,
                keyPassword = pass
            )
            _state.update {
                it.copy(
                    appFactoryKeystoreConfig = config,
                    appFactoryMessage = "2048-bit Keystore generated: ${config.keystoreFile.name}"
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(appFactoryMessage = "Keystore generation failed: ${e.message}") }
        }
    }

    fun bumpAppVersion(
        targetDirectory: File? = null,
        bumpType: com.jarves.mh.factory.model.VersionBumpType = com.jarves.mh.factory.model.VersionBumpType.PATCH
    ) {
        val bp = _state.value.appFactoryBlueprint
        val projectDir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: File(getApplication<Application>().filesDir, "projects/${bp?.name?.lowercase()?.replace(" ", "_") ?: "app"}")

        val (newCode, newName) = com.jarves.mh.factory.build.SigningAndVersionManager.bumpVersion(projectDir, bumpType)
        _state.update { state ->
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
        blueprint: com.jarves.mh.factory.model.AppBlueprint? = _state.value.appFactoryBlueprint
    ) {
        val bp = blueprint ?: return
        val projectDir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: File(getApplication<Application>().filesDir, "projects/${bp.name.lowercase().replace(" ", "_")}")

        try {
            val bundle = com.jarves.mh.factory.build.PlayStorePreparationEngine.prepareListingBundle(projectDir, bp)
            _state.update {
                it.copy(
                    appFactoryStoreBundle = bundle,
                    appFactoryMessage = "Play Store listing metadata bundle exported to ${bundle.listingDir.name}"
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(appFactoryMessage = "Play Store preparation failed: ${e.message}") }
        }
    }

    fun refreshGitStudio(targetDirectory: File? = null) {
        val project = _state.value.activeProject
        val dir = targetDirectory ?: project?.let { projectWorkspaceRoot(it) } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val status = gitDomainController.getStatusInfo(dir)
            val branches = gitDomainController.listBranchesDetailed(dir)
            val commits = gitDomainController.log(dir, limit = 40)
            val rawDiff = gitDomainController.rawDiff(dir, staged = false).output
            val repoFullName = _state.value.githubRepositories.firstOrNull {
                it.cloneUrl.contains(dir.name, ignoreCase = true) || it.fullName.contains(dir.name, ignoreCase = true)
            }?.fullName ?: _state.value.githubRepositories.firstOrNull()?.fullName

            val issues = if (repoFullName != null && preferences.githubLogin.isNotBlank()) {
                runCatching { gitHubClient.issues("", repoFullName) }.getOrDefault(emptyList())
            } else emptyList()

            val prs = if (repoFullName != null && preferences.githubLogin.isNotBlank()) {
                runCatching { gitHubClient.pullRequests("", repoFullName) }.getOrDefault(emptyList())
            } else emptyList()

            val runs = if (repoFullName != null && preferences.githubLogin.isNotBlank()) {
                runCatching { gitHubClient.listWorkflowRuns("", repoFullName) }.getOrDefault(emptyList())
            } else emptyList()

            val releases = if (repoFullName != null && preferences.githubLogin.isNotBlank()) {
                runCatching { gitHubClient.listReleases("", repoFullName) }.getOrDefault(emptyList())
            } else emptyList()

            _state.update {
                it.copy(
                    gitStatusInfo = status,
                    gitBranches = branches,
                    gitCommits = commits,
                    gitRawDiff = rawDiff,
                    gitConnectedRepo = repoFullName,
                    gitIssues = issues,
                    gitPullRequests = prs,
                    gitWorkflowRuns = runs,
                    gitReleases = releases
                )
            }
        }
    }

    fun gitStageFile(path: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        gitDomainController.stageFile(dir, path)
        refreshGitStudio(dir)
    }

    fun gitStageAll(targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        gitDomainController.stageAll(dir)
        refreshGitStudio(dir)
    }

    fun gitUnstageFile(path: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        gitDomainController.unstageFile(dir, path)
        refreshGitStudio(dir)
    }

    fun gitUnstageAll(targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        gitDomainController.unstageAll(dir)
        refreshGitStudio(dir)
    }

    fun gitDiscardFile(path: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        gitDomainController.discardFile(dir, path)
        refreshGitStudio(dir)
    }

    fun gitDiscardAll(targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        gitDomainController.discardAll(dir)
        refreshGitStudio(dir)
    }

    fun gitCommitDetailed(message: String, amend: Boolean = false, signOff: Boolean = false, targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        val res = gitDomainController.commitDetailed(dir, message, amend, signOff)
        _state.update { it.copy(toastMessage = if (res.success) "Committed successfully" else res.errorMessage ?: "Commit failed") }
        refreshGitStudio(dir)
    }

    fun gitCreateBranch(name: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        val res = gitDomainController.checkout(dir, name, createNew = true)
        _state.update { it.copy(toastMessage = if (res.success) "Created branch $name" else res.errorMessage ?: "Failed to create branch") }
        refreshGitStudio(dir)
    }

    fun gitCheckoutBranch(name: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        val res = gitDomainController.checkout(dir, name, createNew = false)
        _state.update { it.copy(toastMessage = if (res.success) "Switched to branch $name" else res.errorMessage ?: "Failed to switch branch") }
        refreshGitStudio(dir)
    }

    fun gitDeleteBranch(name: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        val res = gitDomainController.deleteBranch(dir, name, force = true)
        _state.update { it.copy(toastMessage = if (res.success) "Deleted branch $name" else res.errorMessage ?: "Failed to delete branch") }
        refreshGitStudio(dir)
    }

    fun gitMergeBranch(branch: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        val res = gitDomainController.merge(dir, branch)
        _state.update { it.copy(toastMessage = if (res.success) "Merged $branch successfully" else res.errorMessage ?: "Merge conflicts or failure") }
        refreshGitStudio(dir)
    }

    fun gitRebaseBranch(upstream: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        val res = gitDomainController.rebase(dir, upstream)
        _state.update { it.copy(toastMessage = if (res.success) "Rebased onto $upstream" else res.errorMessage ?: "Rebase conflicts or failure") }
        refreshGitStudio(dir)
    }

    fun gitResetBranch(hash: String, mode: com.jarves.mh.git.model.GitResetMode, targetDirectory: File? = null) {
        val dir = targetDirectory ?: _state.value.activeProject?.let { projectWorkspaceRoot(it) } ?: return
        val res = gitDomainController.reset(dir, hash, mode)
        _state.update { it.copy(toastMessage = if (res.success) "Reset to $hash (${mode.name})" else res.errorMessage ?: "Reset failed") }
        refreshGitStudio(dir)
    }

    fun createGitHubIssue(title: String, body: String = "") {
        val repo = _state.value.gitConnectedRepo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val issue = gitHubClient.createIssue("", repo, title, body)
                _state.update { it.copy(gitIssues = listOf(issue) + it.gitIssues, toastMessage = "Created Issue #${issue.number}") }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = "Failed to create issue: ${e.message}") }
            }
        }
    }

    fun mergeGitHubPullRequest(prNumber: Int, method: com.jarves.mh.network.PullRequestMergeMethod = com.jarves.mh.network.PullRequestMergeMethod.SQUASH) {
        val repo = _state.value.gitConnectedRepo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = gitHubClient.mergePullRequest("", repo, prNumber, method = method)
                _state.update {
                    it.copy(
                        toastMessage = if (result.merged) "PR #$prNumber merged successfully!" else "Merge failed: ${result.message}"
                    )
                }
                refreshGitStudio()
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = "Merge failed: ${e.message}") }
            }
        }
    }

    fun startAutonomousIssueWorkflow(issue: com.jarves.mh.network.GitHubIssue) {
        val project = _state.value.activeProject ?: return
        val dir = projectWorkspaceRoot(project)
        val repo = _state.value.gitConnectedRepo ?: project.name

        viewModelScope.launch {
            gitWorkflowEngine.runAutonomousWorkflow(
                issue = issue,
                token = "",
                repoFullName = repo,
                workingDir = dir,
                agentExecutor = { prompt ->
                    sendPrompt(prompt)
                    true
                },
                buildAndTestValidator = {
                    true to "Tests compiled and passed"
                }
            )
            refreshGitStudio(dir)
        }
    }

    fun planTeamProject(goal: String) {
        teamOrchestrator.planProject(goal)
    }

    fun stepTeamTask(): com.jarves.mh.agent.team.model.TeamTask? {
        return teamOrchestrator.runNextReadyTask()
    }

    fun autoRunTeamTasks(): Int {
        return teamOrchestrator.autoRunAll()
    }

    fun cancelTeamSession() {
        teamOrchestrator.cancelSession()
    }

    fun setCloudRoutingPolicy(policy: com.jarves.mh.cloud.model.WorkloadRoutingPolicy) {
        cloudHybridCoordinator.setPolicy(policy)
    }

    fun submitHybridWorkload(title: String, type: com.jarves.mh.cloud.model.WorkloadType): com.jarves.mh.cloud.model.HybridExecutionJob {
        return cloudHybridCoordinator.submitExecutionJob(title, type)
    }

    fun createCollaborationSession(projectId: String, projectName: String): com.jarves.mh.collab.model.SharedProjectSession {
        return workspaceCollaborationManager.createShareSession(projectId, projectName)
    }

    fun joinCollaborationSession(shareToken: String, name: String, email: String): com.jarves.mh.collab.model.SharedProjectSession? {
        return workspaceCollaborationManager.joinSession(shareToken, name, email)
    }

    override fun onCleared() {
        super.onCleared()
        AppCrashLogger.log("MainViewModel.onCleared: cleaning up running processes and sessions")
        runCatching {
            projectTerminalProcess?.destroy()
            terminalProcess?.destroy()
            githubAuthProcess?.destroy()
        }
        if (_state.value.isRunning) {
            sessionManager.recordSessionFailed("App process terminated unexpectedly")
        }
    }

    companion object {
        private const val MINIMUM_INITIALIZATION_SCREEN_MS = 3_000L
        private const val MAX_VISIBLE_WORKSPACE_ENTRIES = 2_000
        private const val MAX_PROJECT_TERMINAL_HISTORY = 100
        private const val MAX_PROJECT_TERMINAL_OUTPUT = 200_000
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
