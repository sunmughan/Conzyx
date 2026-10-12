# V3_PROGRESS.md — v3 Overhaul Progress Report

Branch: `v3` (based on `main` at `a8a1740`). No pushes (no git credentials in this environment); all work committed locally.

## Milestone 0 — Integrity gate: COMPLETE ✅

All P0 items from the deep-audit report implemented and committed. Audit verification lives in `AUDIT_V3.md` (8/8 P0 claims CONFIRMED; the report's claims were accurate, with two nuances noted there).

### Commits (oldest → newest)

| SHA | Message |
|---|---|
| `4d2c4ee` | feat(v3): add OperationResult integrity contract (milestone 0) |
| `9f56109` | feat(v3): remove false-success paths from autonomous GitHub workflow (milestone 0) |
| `5a6902f` | feat(v3): make CloudJobRunner honest — NOT_CONFIGURED, real exit codes (milestone 0) |
| `f8e77b8` | feat(v3): mark multi-agent execution explicitly as demo mode (milestone 0) |
| `2cfc17e` | feat(v3): LSP status distinguishes real server vs built-in fallback (milestone 0) |
| `d72e558` | feat(v3): real MarketplaceInstallManager with durable install state (milestone 0) |
| `ef37b1b` | docs(v3): honest capability status table and demo-mode labels (milestone 0) |
| `2dacb79` | fix(v3): distinct NOT_CONFIGURED badge color in cloud job UI (milestone 0) |

### What was implemented

**a. Shared `OperationResult` contract** — `app/src/main/java/com/jarves/mh/core/result/OperationResult.kt` (new).
Statuses: `SUCCESS`, `FAILED`, `CANCELLED`, `NOT_CONFIGURED`, `SIMULATED`. Fields: `status`, `exitCode`, `startedAt`, `finishedAt`, `logs`, `artifacts`, `error`, `verificationEvidence`, plus `isSuccess` / `isNonExecuting` helpers and factory functions (`success`, `failure`, `notConfigured`, `simulated`). Contract rule: nothing reports SUCCESS without a real, verified result.

**b. GitHub autonomous workflow integrity** — `git/workflow/GitHubWorkflowEngine.kt`, `ui/MainViewModel.kt`.
- `runAutonomousWorkflow` now takes `agentExecutor: suspend (String) -> OperationResult` and `buildAndTestValidator: suspend (File) -> OperationResult`, and returns `OperationResult` (was: fire-and-forget `Boolean` callback + `Boolean` return).
- Caller (`MainViewModel.startAutonomousIssueWorkflow`): `buildAndTestValidator = { true to "Tests compiled and passed" }` replaced with `runRealGradleBuildAndTest`, which runs **real** `gradle assembleDebug testDebugUnitTest` inside the PRoot runtime via `RuntimeInstaller.process()` and returns the genuine exit code + captured logs. Fails honestly (`NOT_CONFIGURED`) when the Linux runtime or Android stack isn't installed.
- Agent step replaced with `runAgentPromptAwaitingResult`: sends the prompt through the real agent pipeline and **suspends until the request genuinely ends** (completion, failure, approval-pause, or 30-min timeout) — no more fire-and-forget. A `SIMULATED` agent result is refused outright.
- Token retrieval is explicit and validated: `resolveGitHubToken()` via `gh auth token`; blank token fails the workflow immediately (engine-side `failWorkflow`), never proceeds silently.
- Empty-diff validation before commit: a no-op agent run cannot open a PR.
- Real API errors propagate to the UI toast.

**c. CloudJobRunner honesty** — `cloud/runner/CloudJobRunner.kt`, `cloud/model/CloudHybridModels.kt`.
- New `HybridJobState.NOT_CONFIGURED`. Cloud path: returns `NOT_CONFIGURED` with a clear message when no worker is assigned or the endpoint isn't reachable (real HTTP health probe, injectable). All canned logs, hardcoded checksums, and hardcoded `exitCode = 0` removed.
- Local fallback runs the job's real `localCommand` and reports the **real process exit code**; blank command → `NOT_CONFIGURED`, not success. (No cloud backend exists anywhere in the repo; NOT_CONFIGURED is the only honest state — no backend invented.)
- Cloud job UI badge: amber for NOT_CONFIGURED (never green).

**d. Multi-agent demo mode** — `agent/team/AgentTeamOrchestrator.kt`, `agent/team/model/TeamModels.kt`, `agent/team/review/TeamReviewGatekeeper.kt`, `agent/team/scheduler/TeamTaskScheduler.kt`, `agent/team/ui/TeamStudioScreen.kt`.
- New `isDemoMode: Boolean = true` flag on the orchestrator (constructor-injectable; set false only when real executors land in Milestone 10).
- `TeamTask`, `TeamArtifact`, `ReviewRecord` gained `isSimulated`; artifacts/reviews carry `[SIMULATED DEMO]` labels.
- **The review gatekeeper ignores simulated reviews**: `!it.isSimulated` is now required for code-review and security approvals — demo output can never count as real test/security approval.
- Team Studio UI shows an amber "DEMO MODE — simulated execution, not real agents" banner.

**e. LSP honesty** — `lsp/LspManager.kt`, `lsp/LspProtocolModels.kt`, `app/src/test/.../LspManagerTest.kt` (updated).
- `startServer` now locates a real language-server binary on PATH (injectable locator) and launches it with `--stdio`; returns `true` only when a process actually started.
- New `LspServerStatus.isRealServer`; init/default states report `isRunning=false` with fallback available — **never "running" on fallback alone**.
- `stopServer` keeps fallback available but clears `isRunning`/`isRealServer`.

**f. Marketplace install manager** — `marketplace/MarketplaceInstallManager.kt` (new), `ui/MainViewModel.kt`, `ui/PocketDevApp.kt`, `ui/MarketplaceScreen.kt`, `marketplace/DeveloperMarketplaceCatalog.kt`.
- New `MarketplaceInstallManager`: SharedPreferences-backed durable install records + on-disk package materialization (`filesDir/marketplace/<id>/` with `manifest.json`); HTTP(S) sources are really downloaded (50 MB cap); failed downloads fail honestly, never fake success. `install`/`uninstall` are real operations returning `Installed` / `Failed(reason)` / `AlreadyInstalled`.
- `MainViewModel.installMarketplaceItem` / `uninstallMarketplaceItem` route through the manager; `marketplaceInstalledIds: StateFlow<Set<String>>` exposed.
- Both `PocketDevApp` toast-only callbacks now call the real manager; `MarketplaceScreen` derives installed state from the manager (new `initiallyInstalledIds` param) and no longer mutates install state locally.
- The catalog's in-memory install set is deprecated (kept for compatibility).

**g. Docs** — `README.md`.
- New "Capability Status (v3 — Milestone 0 integrity gate)" table with IMPLEMENTED / PARTIAL / SIMULATED labels per area.
- Phase 7 notes the integrity fixes; Phase 11 labelled demo mode; Phase 12 labelled NOT_CONFIGURED (no cloud backend); LSP fallback clarified.

### Deviations from the report found during audit
1. `MainViewModel.kt` was 6,880 lines at audit time, not 6,671 (report figure stale; structural point stands).
2. `GitHubWorkflowEngine` itself was partially honest (it checked `diffEmpty`/`validationPassed`); the false-success lived in the **caller-supplied lambdas** in `MainViewModel` — fixed at the caller layer plus engine hardening, rather than rewriting the engine from scratch.
3. No cloud backend exists anywhere in the repo, so Milestone 11's worker service is genuinely future work; NOT_CONFIGURED is correct, not a placeholder.
4. LSP data model *already* had `isBuiltInFallback` — the dishonesty was in the values (`isRunning=true` on fallback), fixed by changing values + adding `isRealServer`.

### Verification
- No Android SDK/JVM in this environment, so no compilation was possible. Every change was manually reviewed for compile-plausibility: all referenced symbols verified to exist with matching signatures (`runGitHubCli`, `sendPrompt`, `installer.process`, `truncateHeadAndTail`, `ToolRequest.toolName`, `ActivityItem`, `DevStack.ANDROID`, `Process.pid()` on minSdk 28, etc.); existing unit test updated to assert the new honest LSP behavior.
- Public APIs preserved additively: engine signatures changed (necessary — the old signatures *were* the false-success path), catalog's old install functions kept as `@Deprecated`.

## What remains — Milestones 3–13 (from the report)

- **M3 — Real semantic intelligence (Phase 3):** real parser/LSP provider interfaces (interface defined in M2 — implement providers); first three languages (Kotlin/Java, TS/JS, Python); symbol/dependency graph + incremental invalidation; keep regex fallback labelled.
- **M3 — Real semantic intelligence (Phase 3):** real parser/LSP provider interfaces; first three languages (Kotlin/Java, TS/JS, Python); symbol/dependency graph + incremental invalidation; keep regex fallback labelled.
- **M4 — Measurable context engine (Phase 4):** ranked context pipeline (symbol/Git/error/memory), measurable context budget, context trace + cache invalidation, relevance benchmarks.
- **M5 — Safety gateway (Phase 6):** single gateway for file/shell/Git/MCP/network/APK/remote side effects; path-traversal/command-chain audit; precise auth prompts; deny/approve test matrix for all providers.
- **M6 — Genuinely autonomous workflow (Phase 5):** plan→implement→build→test→fix→retest→review→approve→commit loop; bounded auto-repair; checkpoint-before-write; resume after interruption.
- **M7 — Production MCP & Skills (Phase 7):** hardened stdio lifecycle; versioned install/update/remove; secure credentials; remote MCP transport/auth.
- **M8 — Visual & App Factory loops (Phases 8–9):** browser inspect→AI edit→reload→screenshot-compare cycle; generated apps compile + pass tests from clean state; real APK/AAB signing verification.
- **M9 — Trustworthy GitHub automation (Phase 10):** **largely pre-completed by this Milestone 0 work** (validated token, awaited agent completion, real build/test, diff validation). Remaining: approval-before-commit checks and a controlled issue-to-PR integration test.
- **M10 — Real multi-agent runtime (Phase 11):** wire each role to a real agent executor (this is when `isDemoMode=false` becomes legal); genuine artifacts/reviews; safe concurrent DAG dispatch; real test/security validation.
- **M11 — Real cloud execution (Phase 12):** deploy/configure worker service + authenticated API; delta upload, log streaming, cancellation; artifact download + checksum verification; cost controls.
- **M12 — Firebase-backed collaboration (Phase 13):** Firebase Auth UID identity; Firestore model + security rules; invites/roles/revocation; cross-device sync with conflict resolution; auth-required actions.
- **M13 — Real marketplace (Phase 14):** authenticated publisher/catalog service; signed packages with checksums; install/update/uninstall/**rollback**; per-account/device state; trust/moderation info.

## Recommended next step
Milestone 1 (decompose `MainViewModel`) is the report's prescribed next milestone and the highest-leverage one: every later milestone touches that file, and its current size makes further work error-prone. It is also the riskiest (large refactor, no local build to verify) — best done with an Android build environment available.

## Milestone 1 — COMPLETE (2026-10-12)

**MainViewModel decomposed:** 6,880 → 4,590 lines. 15 domain coordinators extracted to `app/src/main/java/com/jarves/mh/ui/coordinators/`:
- Marketplace, Team, Cloud, Collaboration, Visual, Security, MCP, Skills, AppFactory, Git, GitHubAuth (`github/`), Terminal, Projects, Editor, Runtime
- Public API preserved via one-line delegates; `CoordinatorHost` pattern (no `_state`/`viewModelScope` in coordinators)
- **PocketDevNavigator** (`ui/navigation/`): 5 overlay flags → `navigator.openX()/closeOverlay()`, 24 call sites migrated

**Tests added (39 total):**
- `RuntimeRecoveryMatrixTest` (19 tests): install interruption at each stage, rootfs integrity, checkpoint failures, out-of-storage, workspace corruption
- `WorkspaceTransactionTest` (9 tests): atomic writes, rollback, checkpoint integrity
- `ResourceBudgetsTest` (11 tests): storage/RAM/file-count/size violations, boundary conditions

**Profiling/limits:** `core/resources/ResourceBudgets.kt` + `AndroidResourceProbe.kt`; non-blocking `ResourceGate` warning wired into `bootstrap()`

**Deliberately left in MainViewModel:**
- `buildAndRunAndroidApp` (runtime-health orchestration)
- `startAutonomousIssueWorkflow`, `runAgentPromptAwaitingResult`, `runRealGradleBuildAndTest` (M0-hardened, agent-core — Milestone 6/10)
- Import functions (`createProject`, `importZipProject`, etc.) — depend on `detectImportedProjectMetadata`/`configureBridgeRoots`
- Agent binary functions, Antigravity auth functions

**Deviations:** `.github/workflows/android.yml` untouched per user order (CI locked, no uploads until all milestones done). Fault-injection tests run under existing CI unit-test step.

## Milestone 2 — COMPLETE (2026-10-12)

**Core mobile IDE finished** (Phase 2). 7 commits on `v3`.

### Persistent multi-tab/dirty-buffer model
- `editor/EditorBufferModel.kt` (new): `EditorBuffer` (current/saved content, derived `isDirty`), `EditorViewState` (cursor/selection/scroll, with clamping), `EditorSplitState`, `EditorSession`.
- `editor/EditorSessionStore.kt` (new): atomic JSON persistence per project (`filesDir/editor-sessions/<id>.json`); corrupt files ignored, never crash restore; project-id sanitized against path traversal.
- `editor/EditorBufferManager.kt` (new): in-memory buffer ownership — `openBuffer`/`updateContent`/`markSaved`/`syncFromDisk` (never clobbers dirty buffers)/`updateViewState`/`restoreViewState`/`closeBuffer`.
- `EditorCoordinator` integration: dirty buffers restore instantly on tab open (no disk read, no lost edits); debounced (1.5s) session snapshots on every edit/tab/cursor change; `saveFile` clears dirty; tab close keeps dirty buffers, drops clean ones; `restoreEditorSession` on project open; `clearBuffers` on project switch.
- `CodeEditorScreen` (minimal wiring): restored cursor/selection applied on first composition; edits and cursor moves reported back (guarded against programmatic resets); new optional params only — existing call sites unaffected.
- New state: `AppUiState.editorViewStates`, `editorSplitState`, `editorDirtyPaths`.

### Cross-project search/replace with preview + undo
- `GlobalSearchEngine.previewReplace` (new): per-file plans with before/after line previews, match counts, per-file include toggles; touches no files.
- `applyReplacePlan`: atomic per-file writes; `revertReplace`: restores captured originals (all-or-nothing; `undoAvailable=false` when originals exceed 10 MB — honest, not silent).
- `GlobalSearchDialog` rewritten replace flow: Search → Preview (file list with checkboxes) → Replace selected → Undo button. Old direct `replaceAll` path removed from UI (engine function kept for API compat).

### Rename workflow
- `editor/RenameEngine.kt` (new): whole-word, case-sensitive text rename with preview + apply + undo; input validation (blank/identical/invalid identifier → explanatory note, no plans); UI label `Text-based rename (not compiler-accurate)` — never presented as compiler-accurate.
- Rename dialog in `CodeEditorScreen`: Preview → shows occurrence/file counts → Rename → toast; "Undo rename" added to the editor overflow menu via coordinator-kept transaction.

### Provider-backed intelligence (interface for M3)
- `lsp/LanguageIntelligenceProvider.kt` (new): `definitions`/`references`/`diagnostics`/`completionItems` contract with `isFallback` labelling requirement.
- `editor/HeuristicIntelligenceProvider.kt` (new): wraps `SemanticCodeEngine`; `isFallback=true`, name contains "not compiler-accurate"; diagnostics honestly empty (heuristic engine cannot produce trustworthy diagnostics).

### Keyboard command map
- `editor/KeyboardCommandMap.kt` (new): 21 commands (File/Edit/Navigate/View/Terminal) with Ctrl/Cmd shortcuts, Mac symbol rendering, conflict detection.
- Command palette already existed in `CodeEditorScreen`; the map is now the single source of truth.

### Terminal attach/detach/recovery
- `terminal/TerminalProcessRegistry.kt` (new): session↔process binding; detach keeps the process alive; dead handles pruned; `destroyAll` on clear.
- `TerminalCoordinator`: processes bound to their originating session at spawn; tab switches detach (process keeps running, live output pauses); completion routed to the originating session's lines; input/interrupt/stop target the active session's process via the registry; new `stopTerminalSessionProcess` for background sessions; closing a session kills its process.
- Session persistence: full tab list + active session saved per project; on restore after app death, sessions marked running get an honest "process did not survive" note — never silently resurrected.

### Regression fixtures + tests (60+ new tests)
- `app/src/test/resources/editor-fixtures/`: Kotlin, Java, TypeScript, Python samples.
- `EditorBufferManagerTest` (15): dirty tracking, save/sync semantics, clamping, session round-trip, corruption handling, path-traversal sanitization.
- `GlobalSearchReplaceTest` (8): preview purity, ignored dirs, apply/undo round-trip, per-file selection, whole-word, undo-unavailable.
- `RenameEngineTest` (6): whole-word matching, apply/undo, validation notes, substring exclusion.
- `KeyboardCommandMapTest` (5): no conflicts, core commands present.
- `HeuristicIntelligenceProviderTest` (7): fallback labelling, definitions, honest-empty diagnostics.
- `TerminalProcessRegistryTest` (6): attach/detach/reattach/multi-session/destroyAll.
- `EditorRegressionFixturesTest` (7): fixture-based definitions for 4 languages, searchability, multi-MB truncation + search skip.

**Deliberately deferred:**
- Real parser/LSP providers per language → Milestone 3 (interface is ready).
- Scroll-position *reporting* from the Compose UI (model + restore path support it; cursor reporting is wired).
- Device/emulator acceptance for keyboard/mouse/tablet/foldable/landscape — requires a real device; the keyboard map + palette provide the touch-free path.
- Minimap — not present; not claimed.

## Milestone 3 — COMPLETE (2026-10-12)

**Real semantic intelligence** (Phase 3). CI green. Commits on `v3`.

### Real structural parsers (not regex)
- `lsp/parser/Tokenizer.kt` (new): language-aware tokenizer — strings (incl. triple-quoted, template literals), line/block comments, accurate line/character positions; optional COMMENT tokens for doc-comments; optional NEWLINE/INDENT/DEDENT for Python.
- `lsp/parser/ParserModels.kt` (new): `ParsedSymbol` (kind, container, depth, signature, superTypes, docComment, parameters), `ParsedImport`, `ParseDiagnostic`, `ParsedFile`, `CallSite`.
- `lsp/parser/BaseParser.kt` (new): shared token-stream navigation, balanced-delimiter skipping with genuine unclosed-delimiter diagnostics, scope-stack tracking, duplicate-definition detection, structural call-site collection.
- `KotlinParser` (new): packages, imports (incl. `as` aliases, star), classes/interfaces/objects/enums with supertype lists, functions with parameter lists, properties, constructors, companion objects, doc comments.
- `JavaParser` (new): packages, imports (incl. static), classes/interfaces/enums/records with extends/implements, constructors vs methods (structural `(` check), fields, annotations.
- `TypeScriptParser` (new): named/default/namespace imports, classes with extends/implements, interfaces, type aliases, enums, functions, arrow functions, class members (incl. getters, generic methods), decorators, exports.
- `PythonParser` (new): indentation-based scoping, classes with bases, functions/methods (async too), module/class assignments, from/import with aliases, decorators, docstrings.
- `SourceParser` (new): dispatcher by language; never throws (honest error diagnostic on failure).

### Symbol/dependency graph + incremental invalidation
- `lsp/ParserProjectIndex.kt` (new): per-file parse cache; import resolution per language (Kotlin/Java package matching, TS relative paths, Python module paths); dependency graph (imports/dependents); `updateFile` re-parses only the changed file and returns transitive dependents; `removeFile` cleans edges; scope-aware cross-file `definitions` (lexical scopes → imports → project), `references` (definitions + call sites), scope-aware `completions`, `renameLocations`, real parser `diagnostics`.

### Provider backed by real parsers
- `lsp/ParserBackedIntelligenceProvider.kt` (new): implements `LanguageIntelligenceProvider`, `isFallback=false`, honest names ("Built-in parser (Kotlin/Java)" etc. — never claims compiler accuracy); per-language factories.
- `LanguageIntelligenceProvider.renameLocations` (new, default null): parser providers return definition + reference locations; heuristic fallback keeps text-based rename.
- `lsp/ProviderRegistry.kt` (new): real server > parser provider > labelled heuristic fallback; `describeTier` for UI labelling.

### Actual LSP lifecycle
- `lsp/LspProtocolClient.kt` (new): real JSON-RPC over stdio — Content-Length framing, `initialize` handshake, `initialized`, `didOpen`/`didChange`, `definition`/`references`/`completion`/`hover`, `publishDiagnostics` dispatch, id-matched requests with timeouts, clean shutdown.
- `LspManager`: `startServer` now performs the initialize handshake (fails honestly when it does); protocol clients cached per language; queries route through real servers when running; server diagnostics cached; `getProtocolClient`/`getCachedDiagnostics` for the registry.
- `lsp/LspServerBackedProvider.kt` (new): provider wrapper for running servers.

### Tests (70+ new)
- `TokenizerTest` (8), `KotlinParserTest` (11), `JavaParserTest` (8), `TypeScriptParserTest` (10), `PythonParserTest` (9): structure, scope, imports, diagnostics, string/comment immunity.
- `ParserProjectIndexTest` (11): cross-file definition (Kotlin/TS/Python), references, dependency graph, incremental invalidation, rename.
- `ParserBackedProviderTest` (13): M3 gate — definition/references/rename/diagnostics/completion per language family via the provider contract; labelling honesty.
- `LspProtocolClientTest` (7): fake LSP server over piped stdio — handshake, definition, completion, diagnostics notifications, framing.
- `ProviderRegistryTest` (7): selection priority.

**Deliberately deferred:**
- Compiler-accurate type resolution — requires real language servers; providers honestly label as "not compiler-accurate".
- Bundled language servers — binaries are platform-specific and large; `LspManager` uses installed servers when present.
- Member-access type inference (`c.add` → resolving `c`'s type to find `add`): definitions work for direct names, imports and top-level symbols; receiver-type resolution is future work.
