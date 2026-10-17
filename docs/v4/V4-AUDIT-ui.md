# V4 Audit — UI Layer & App Architecture

> AUDIT ONLY. No implementation. Branch: `v4` (commit d06038b).
> Base package: `com.codeair.mhe`, source root `app/src/main/java/com/codeair/mhe/`.
> NOTE (repo hygiene): this file is a working note. Per repo rules it must NOT be committed to the repo; keep it local or move to `~/workspace/goals/*/hidden_files/`.

---

## 1. UI stack summary

- **Framework:** 100% Jetpack Compose. No XML layouts for screens, no Fragments.
- **Design system:** Material3 via custom `PocketTheme` (`ui/theme/Theme.kt`, 88 lines). `MaterialTheme` with `darkColorScheme` / `lightColorScheme`; brand color `PocketOrange = 0xFFF28C52` as primary. `AppThemeMode { SYSTEM, DARK, LIGHT }`, persisted in `AppUiState.themeMode`.
- **Navigation:** **No Navigation-Compose. No NavHost, no NavController, no route graph.** Navigation is hand-rolled state:
  - Root tabs: `private enum class RootScreen` in `ui/PocketDevApp.kt:303` — `PROJECTS, AGENT, SKILLS, MARKETPLACE, SETTINGS` (bottom nav).
  - In-project tabs: `private enum class WorkspaceTab` (`PocketDevApp.kt:309`) — `HOME, CHAT, FILES, TERMINAL, CHANGES, PREVIEW`.
  - Full-screen overlays: `ui/navigation/PocketDevNavigator.kt` (86 lines) — `mutableStateOf<OverlayScreen?>`, single overlay at a time. `OverlayScreen` enum: `APP_FACTORY, TEAM_STUDIO, CLOUD_HYBRID, MARKETPLACE, PEN_TESTING, DIAGNOSTICS, ALL_PROJECTS`. Opened via `navigator.open*()`; back handling via `goBack()`. The navigator is `remember`ed at the top of `PocketDevApp` and threaded down as a parameter (a prior CI failure proved `navigator` is not in scope inside child screens — callbacks must be passed explicitly).
- **Edge-to-edge:** `enableEdgeToEdge()` in `MainActivity.onCreate`. `Theme.kt` syncs system-bar icon colors with the actual app theme via `WindowCompat.getInsetsController` in a `SideEffect` (needed because the XML theme is `Theme.Material.Light` while the app renders dark by default).
- **Insets:** established pattern of `statusBarsPadding()`, `navigationBarsPadding()`, `imePadding()` on screens/dialogs that need them (e.g. `agent/team/ui/TeamStudioScreen.kt:164,908,1493`, `auth/ui/AuthScreen.kt:131`, `ui/GlobalSearchDialog.kt:197`).
- **State observation:** `collectAsStateWithLifecycle()` on `StateFlow`s in `MainActivity` and `PocketDevApp`.
- **DI:** **None.** No Hilt, Koin, or Dagger anywhere (verified by repo-wide grep). `MainViewModel` is obtained via `ViewModelProvider(this)[MainViewModel::class.java]` in `MainActivity`, then passed as a constructor/composable parameter (`PocketDevApp(vm)`). Coordinators receive what they need via constructor (`CoordinatorHost`). Manual wiring throughout.

## 2. Screen inventory

19 top-level `*Screen` composables found by grep:

| Screen | File |
|---|---|
| `AgentScreen` | `ui/AgentScreen.kt` |
| `AllProjectsScreen` | `ui/` (overlay, opened via `navigator.openAllProjects()`) |
| `AntigravityOnboardingScreen` | `ui/` (step-based auth/setup flow) |
| `BackgroundTaskSetupScreen` | `ui/` (background-execution permission setup) |
| `CodeEditorScreen` | `ui/CodeEditorScreen.kt` |
| `MarketplaceScreen` | `ui/MarketplaceScreen.kt` |
| `PrivacyPolicyScreen` | `ui/PrivacyPolicyScreen.kt` |
| `ProductOnboardingScreen` | `ui/ProductOnboardingScreen.kt` |
| `ProjectsScreen` | `ui/` |
| `ProviderSetupScreen` | `ui/` |
| `ReadOnlyProjectScreen` | `ui/` |
| `RuntimeSetupPromptScreen` | `ui/` |
| `SettingsScreen` | `ui/SettingsScreenModern.kt` (~1900 lines) |
| `SkillsScreen` | `ui/SkillsScreen.kt` |
| `StartupErrorScreen` | `ui/` |
| `TerminalScreen` | `ui/TerminalScreen.kt` |
| `WebBrowserScreen` | `ui/WebBrowserScreen.kt` |
| `WorkspaceScreen` | `ui/` |
| `RootScreen` / `RootScreenHost` | `ui/PocketDevApp.kt` (hosts root tabs) |

Feature-area screens in subpackages: `agent/team/ui/TeamStudioScreen.kt`, `git/ui/GitHubStudioScreen.kt`, `diagnostics/ui/DiagnosticsLogScreen.kt`, `auth/ui/AuthScreen.kt`, `visual/ui/VisualComparisonViewer.kt`, `mcp/` screens.

**Relevant existing screens for V4:** `AgentScreen` (natural-language goal entry + agent activity feed — closest to "operator entry"), `BackgroundTaskSetupScreen` + `AntigravityOnboardingScreen` (step-based readiness/setup — closest to "preflight"), `DiagnosticsLogScreen` (6 log sources — closest to "capability status"), `SettingsScreenModern` (PERMISSIONS section — closest to permission-fix hub).

## 3. ViewModel / architecture analysis

### MainViewModel — confirmed god object
- `ui/MainViewModel.kt`: **5,567 lines**, `AndroidViewModel`.
- `AppUiState` data class (`MainViewModel.kt:220-407`): **~188 fields** covering startup, providers/API keys, projects, chats, editor, terminal, git/GitHub, MCP, skills, runtime install, updates, security policies, agent execution.
- **248 public functions**, spanning: git ops (~20), settings/prefs (~16), terminal sessions, MCP servers/tools, API-key vault, runtime setup/install, skills, projects, chat, file ops, backup/restore, updates, security audit, team agents, workflow runs.
- State exposure: single `val state: StateFlow<AppUiState>` (+ a few small auxiliary flows like `marketplaceInstalledIds`).
- **Decomposition in progress (Milestone 1):** `ui/coordinators/` package — `CoordinatorHost` (scoped access: appContext, coroutine scope, state read/update, toasts) plus domain coordinators: `GitCoordinator`, `TeamCoordinator`, `McpCoordinator`, `RuntimeCoordinator`, `TerminalCoordinator`, `EditorCoordinator`, `ProjectsCoordinator`, `SkillsCoordinator`, `SecurityCoordinator`, `MarketplaceCoordinator`, `AppFactoryCoordinator`, `VisualCoordinator`, `GitHubAuthCoordinator`. `MainViewModel` delegates to these but keeps a stable public API so existing UI callers/tests keep compiling. The coordinators are a partial extraction — `MainViewModel` is still the single state owner and still 5.5k lines.

### PocketDevApp — the other god object
- `ui/PocketDevApp.kt`: **7,653 lines**. Owns root-tab switching, overlay rendering, `ApprovalCard`, chat UI, workspace UI, and hundreds of small private composables. Any new V4 screen added here would worsen this; V4 UI should live in the roadmap's proposed `operator/ui/` package as separate files.

### Implication for V4
The roadmap proposes `operator/ui/OperatorViewModel.kt` — a dedicated ViewModel. This is the right call and matches the in-flight coordinator-decomposition direction. Do NOT hang operator state off `MainViewModel`/`AppUiState`; the 188-field state class is already unmaintainable. A dedicated `OperatorViewModel` (or a coordinator under `CoordinatorHost`) owning a small `OperatorUiState` is the viable integration path.

## 4. Existing reusable UI patterns for V4

### 4a. Approval / confirmation UI — EXISTS, two layers
1. **Inline `ApprovalCard`** (`ui/PocketDevApp.kt:7392`, private composable): renders a `ToolRequest` — warning icon, "Review this action", `request.explanation`, affected paths list, three buttons: **Reject / Session / Once**, wired to `PermissionLevel.DENY/SESSION/ONCE`. Shown inline in the agent chat when `state.pendingApproval != null` (`PocketDevApp.kt:5417`, state set from `session/AgentOrchestrator.kt:111`). This is the closest existing artifact to V4's "approval gate UI" — but it is chat-inline, tool-request-shaped (file/shell/MCP), not a bottom sheet, and not bound to run/step/action identity.
2. **Authorization model without UI:** `security/AuthorizationPrompt.kt` (116 lines) — data class with `operation: SafetyOperation`, `risk: PromptRisk`, `whatHappens: String`, `scopes: List<PermissionLevel>`, and `renderText()` producing a precise human-readable prompt naming the exact operation and target. **Zero references from the UI layer** — the model exists, no Compose renderer exists. V4's `OperatorApprovalSheet` would essentially be the missing renderer for this model, extended with run/step/action binding.

Supporting policy layer (all in `security/`, 1,759 lines total):
- `SafetyGateway.kt` (277 lines): single choke point; `authorize()` returns `SafetyDecision(verdict, reason, prompt, category, level)` with verdicts `ALLOW/PROMPT/DENY`. Decision order: emergency kill switch → path audit (hard deny on traversal) → MCP permissions → `PermissionPolicyEngine`.
- `SafetyOperation.kt` (81 lines): sealed ops — `FileOp, ShellExec, GitOp, McpToolCall, NetworkRequest, ApkInstall, RemoteExec`.
- `PermissionPolicyEngine.kt` (547 lines): dangerous-command detection, sensitive files, grants/baselines, audit logging.
- `AgentOrchestrator.requestApproval / approveCurrentStep / rejectCurrentStep / awaitApprovalDecision(timeoutMs)` (`session/AgentOrchestrator.kt:94-160`): orchestrator-side blocking approval with timeout — the "no action is dispatched until approval is recorded" primitive already exists at the engine layer.

### 4b. Timeline / progress UI — PARTIAL
- `ui/RoadmapComponents.kt`: `RoadmapStepRow` renders plan steps with `StepStatus` (`COMPLETED`/`IN_PROGRESS`/pending) — the closest visual pattern for a step timeline. Backed by `model/RoadmapStep`.
- `workflow/WorkflowModels.kt`: `WorkflowGoal` → `WorkflowPlan` → `PlanStep` (`PlanStepKind`: ANALYZE, IMPLEMENT, WRITE_FILE, RUN_COMMAND, RUN_TESTS, GIT_COMMIT, GIT_PUSH), executed via `WorkflowRunner`/`PlanExecutor` through the M5 safety gateway. A plan-as-validated-object foundation already exists (roadmap §8.3 asks for exactly this).
- Agent activity feed: `AppUiState.activity: List<ActivityItem>` + `liveProcess` rendered in `AgentScreen` — a scrolling event list, not a structured step timeline.
- Setup progress: `StartupStage` enum + `startupProgress/startupMessage/startupLogs` — linear progress-with-logs pattern used by onboarding/setup screens.
- **Gap:** no dedicated run-timeline composable, no persisted run-event rendering, no run-history screen at all (grep for run-history: none).

### 4c. Settings screens — EXISTS
- `SettingsScreenModern.kt`: sectioned settings via `private enum class SettingsSection { ACCOUNT, APPEARANCE, TOOLS, RUNTIME, ENVIRONMENT, CONTRIBUTORS, UPDATE_CHANNEL, PERMISSIONS }`, collapsible sections, `AlertDialog` for destructive confirmations (e.g. "Remove toolchain?", line ~230). Long parameter list of callbacks (lines 165-191) — screens take lambdas, not a ViewModel, as the calling convention.
- V4's "capability and limitations view" has no direct counterpart; the PERMISSIONS section is the nearest hub.

### 4d. Permission-request flows — EXISTS, matches V4's "Fix" requirement
- Pattern: status row + "Fix"/"Open" button → `context.startActivity(...)` with the real Settings intent. Examples in `SettingsScreenModern.kt`:
  - `:753` — `BackgroundProtectionHelper.getAppDetailsSettingsIntent(context)` (battery-optimization / app-details settings).
  - `:782-783` — `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS` with fallback to `Settings.ACTION_SETTINGS`.
- `BackgroundTaskSetupScreen` and `AntigravityOnboardingScreen`: multi-step setup wizards with per-step granted/pending state (`currentStep`, `currentGranted` in `PocketDevApp.kt:839-964` region).
- This is exactly the pattern roadmap §8.2 requires ("Each 'Fix' action must open the real Android settings/consent flow") — reuse it for Accessibility / screen-capture preflight rows.

### 4e. Dialogs inventory
- `AlertDialog` (Material3): destructive confirmations in Settings; `YouTubeVideoDialog` (`ui/components/`).
- Custom full-screen dialogs: `GlobalSearchDialog`, `GitHubIssuesDialog`, `MultiAgentTeamDialog`, `agent/team/ui/CustomAgentEditor.kt` (create/edit form with dropdowns, switches, text fields — the most complex form dialog; shows the app's form-dialog idiom).
- `ModalBottomSheet`: used in `TeamStudioScreen`, `GitHubStudioScreen`, `VisualDevToolsSheet` — bottom-sheet idiom exists for the approval-sheet shape.

### 4f. Pause / resume / cancel — FRAGMENTED
- Scattered `cancel()`s: `ConcurrentTeamDispatcher.cancel()`, `AgentTeamOrchestrator.cancelRealRun()/cancelSession()`, `GeminiApiClient.cancel()`, `RuntimeSetupService`, download managers. No unified pause/resume API for agent runs.
- `session/EngineeringStateMachine.kt`: `saveState/restoreActiveSession` with `PendingApprovalRequest` persistence — a checkpoint foundation, but scoped to the engineering workflow, not a general operator run.
- Emergency kill switch: `MainViewModel.engageEmergencyKillSwitch()`; `SafetyGateway` hard-denies everything while engaged — the "stop everything" primitive exists.
- **Gap:** no `WAITING_FOR_TAKEOVER` / lease / resume-from-verified-checkpoint concepts anywhere; these are V4-new (roadmap §9.6).

## 5. Build, CI, tests

- **Gradle:** Kotlin DSL (`app/build.gradle.kts`). Plugins: `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`, `com.google.gms.google-services`, `com.google.firebase.crashlytics`.
- **Flavors:** `flavorDimensions += "runtimeDelivery"` with `online` / `offline` product flavors; `debug`/`release` build types; version `3.4.0` via `appVersionName` gradle property. Source sets: `main`, `online` (contains a `runtime` package override), `test`.
- **CI:** `.github/workflows/android.yml` — jobs: `verify` (`testOnlineDebugUnitTest` + `assembleOnlineDebug`), offline build (`assembleOfflineDebug`), `release` (requires permanent Codeair signing key from `MH_RELEASE_KEYSTORE_BASE64` secret — CI verifies cert SHA-256; refuses temp keys). Release notes are generated from `git log PREV_TAG..HEAD` (only that build's changes).
- **Tests:** `app/src/test/` — **172 unit-test `.kt` files**. **No `androidTest/` source set — zero instrumentation tests.** CI runs only JVM unit tests (`testOnlineDebugUnitTest`). Roadmap §9.7/§13 demand real-device instrumentation and fault-injection tests; that infrastructure does not exist and must be created for V4.
- **Signing:** release APKs signed in CI with the permanent keystore; fingerprints verifiable from the APK's v2 signing block (see AGENTS.md release-signing-key note).

## 6. V4 UI integration points (roadmap §8 → existing code)

| §8 need | Status | Reuse / gap |
|---|---|---|
| 1. Operator entry (NL goal + target app + constraints) | PARTIAL | `AgentScreen` chat input is the NL-entry pattern. No target-app picker, no constraint input ("do not submit/send/publish") exists. |
| 2. Preflight screen (Accessibility, capture, target-app, model/provider, policy + Fix→settings) | PARTIAL | `BackgroundTaskSetupScreen` + step-wizard idiom; `SettingsScreenModern` PERMISSIONS section with Fix→`startActivity(Settings…)` pattern (§4d). No Accessibility-service or MediaProjection rows exist. |
| 3. Plan & risk review (real steps, target, effects, confirmations) | PARTIAL | `workflow/WorkflowModels` (validated plan objects) + `RoadmapComponents.RoadmapStepRow` (step rendering). No risk-annotated plan review screen. |
| 4. Live execution timeline (persisted events, step statuses, observation source, retries, blockers) | GAP | `ActivityItem` feed is unstructured. No persisted-event timeline composable; roadmap proposes `operator/ui/OperatorTimeline.kt` — greenfield, fed by the new run store. |
| 5. Approval gate (app, target, operation, message/form summary; no dispatch before recorded approval) | PARTIAL | `ApprovalCard` (inline, `ToolRequest`) + `AuthorizationPrompt` (model, no UI) + `SafetyGateway` + `AgentOrchestrator.awaitApprovalDecision`. V4 needs: a bottom-sheet renderer for `AuthorizationPrompt`-shaped data bound to run/step/action identity (§9.4 binding), replacing/augmenting the inline card. |
| 6. Pause / resume / cancel | GAP | Only scattered `cancel()`s + emergency kill switch. No pause, no resume-from-checkpoint, no takeover lease. |
| 7. Evidence-based result | GAP | No verified/unverified step result screen. |
| 8. Run history | GAP | Nothing exists. Greenfield; needs the `OperatorRunStore` first. |
| 9. Capability & limitations view (READY / NEEDS_PERMISSION / UNAVAILABLE / UNSUPPORTED / ERROR) | GAP | `DiagnosticsLogScreen` shows logs, not capability states. The 5-state taxonomy is new. |
| 10. A11y / edge-to-edge / IME / recreation | EXISTS | `PocketTheme`, `enableEdgeToEdge`, `imePadding`/`navigationBarsPadding`/`statusBarsPadding` idiom, `rememberSaveable` usage, `collectAsStateWithLifecycle`. Follow these; don't invent new ones. |

### Structural recommendations for the V4 UI work
1. **New package `operator/ui/`** per roadmap §10 (`OperatorScreen`, `OperatorViewModel`, `OperatorTimeline`, `OperatorApprovalSheet`, `OperatorCapabilityStatus`) — do not add to `PocketDevApp.kt` (7,653 lines) or `MainViewModel` (5,567 lines).
2. **Dedicated `OperatorViewModel`** (or a coordinator under `CoordinatorHost`), owning a small operator state — do not extend the 188-field `AppUiState`.
3. **Navigation:** add an `OPERATOR` entry to the existing `OverlayScreen` enum + `PocketDevNavigator.openOperator()` — consistent with the established overlay pattern, no NavHost introduction needed.
4. **Approval sheet:** render the existing `AuthorizationPrompt` model in a `ModalBottomSheet` (idiom exists), extended with run/step/action binding and payload digest — do not build a parallel policy engine; `SafetyGateway` stays the single source of truth.
5. **Preflight rows:** copy the `SettingsScreenModern` Fix→`startActivity(real Settings intent)` pattern for Accessibility (`Settings.ACTION_ACCESSIBILITY_SETTINGS`) and capture consent.
6. **Tests:** V4 needs the first `androidTest/` infrastructure (fault-injection, stale-target, approval-replay scenarios from §9.7); JVM unit tests alone cannot cover them.
