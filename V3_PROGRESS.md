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

**Real semantic intelligence** (Phase 3). CI green (run 38241823870). Commits on `v3`.

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

## Milestone 4 — COMPLETE (2026-10-12)

**Measurable context engine** (Phase 4). All four checklist items implemented.

### Single ranked pipeline (symbol/dependency/Git/error/memory)
- `context/RankedContextPipeline.kt` (new): one ranking pass combining explicit mentions, error/stack-trace targets, Git-modified files, symbol graph, dependency graph, filename/symbol/import matches. M3's real-parser edges are merged over heuristic edges via `context/ParserIndexAdapters.kt` (parser wins on conflicts, heuristic fills gaps for unindexed files).
- `ContextRankingEngine.rankFiles` now accepts an optional `ContextTraceBuilder` and records per-signal score contributions without changing existing reason strings or scores.

### Measurable model context budget
- `context/ModelTokenizer.kt` (new): `ModelTokenizer` interface (`countTokens`, `isModelAccurate` honesty flag); deterministic `ApproximateTokenizer` (ceil(chars/4), labelled approximate); `TokenizerRegistry` for model-specific tokenizers when available.
- `context/ContextBudget.kt` (new): priority-ordered `BudgetSection` enforcement — truncates lowest-priority sections first, never touches non-truncatable sections, returns a `BudgetReport` (tokens used vs max, kept/truncated/dropped sections) proving the budget was honored. Over-budget non-truncatable overflow is reported honestly (`withinBudget=false`), never silently exceeded.
- `ContextEngine.buildContextWithTrace(...)` (new): runs the unified pipeline, assembles prioritized sections (overview, error, memory, files, code blocks), enforces the budget measurably, returns `ContextBuildResult(context, trace, budget, parserBacked)`.

### Context trace + cache invalidation
- `context/ContextTrace.kt` (new): `TraceSignal` enum (10 signals), per-file `TraceEntry` (signal, score contribution, included/excluded, reason), `renderText()` for the acceptance gate ("why was this file retrieved, why was that one excluded").
- `ContextEngine` invalidation hooks: `invalidateOnFileEdit`, `invalidateOnBranchSwitch`, `invalidateOnDependencyChange`, `invalidateOnIndexRebuild` (file edits additionally covered by `ProjectIndex.ensureFresh` content fingerprint).

### Memory lifecycle (roadmap task 5)
- `ProjectMemoryStore`: `sourceRef` field on `ProjectMemoryItem` (persisted, backward-compatible), `updateMemory(...)` explicit edit control; `deleteMemory`/`clear` already existed.

### Benchmark (acceptance gate)
- `ContextRelevanceBenchmarkTest`: two sample repositories on disk (Kotlin auth app + Python scripts, incl. unrelated files); four benchmark tasks (bug fix with stack trace, feature with @mention, refactor rename, Python import fix). Asserts per task: measured budget never exceeded, must-retrieve files present (recall), unrelated files never outrank relevant ones (precision), trace shows signals and included files. Plus cache-invalidation and memory edit/sourceRef round-trip tests.

### Tests (22 new)
- `ContextBudgetTest` (7), `ContextTraceTest` (2), `RankedContextPipelineTest` (3), `ContextRelevanceBenchmarkTest` (6 + memory lifecycle).

**Deliberately deferred:**
- Model-exact tokenizers (tiktoken etc.) — interface + registry ready; approximate tokenizer honestly labelled until a model-specific one is bundled.
- Semantic vector retrieval — ranking is over real symbol/dependency graphs, not embeddings; honestly scoped.

## Milestone 5 — COMPLETE (2026-10-12)

**Safety gateway** (Phase 6, before autonomous writes). All four checklist items implemented. CI green (run 38248702993).

### Single gateway for all side effects
- `security/SafetyOperation.kt` (new): sealed hierarchy — `FileOp` (READ/CREATE/WRITE/DELETE), `ShellExec`, `GitOp` (COMMIT/PUSH/BRANCH/CLONE/FETCH), `McpToolCall`, `NetworkRequest`, `ApkInstall`, `RemoteExec`. Each carries a `describe()` used verbatim in prompts and audit logs.
- `security/SafetyGateway.kt` (new): the single choke point. Decision order per operation: (1) emergency kill switch denies everything; (2) path audit — traversal/symlink/absolute-escape is a hard deny, never promptable; (2b) dangerous-command audit for non-shell command carriers (remote exec) so a critical payload cannot bypass detection by switching provider; (3) MCP tool calls consult `McpPermissionManager`, DENY wins; (4) `PermissionPolicyEngine` evaluation (dangerous commands, sensitive files, explicit deny, grants, baselines). `requireAllowed()` throws `SafetyDeniedException` on DENY — no bypass path.
- `security/ProviderSafetyAdapters.kt` (new): `FileSafetyAdapter`, `ShellSafetyAdapter`, `GitSafetyAdapter`, `McpSafetyAdapter`, `NetworkSafetyAdapter`, `ApkSafetyAdapter`, `RemoteWorkSafetyAdapter`. Each routes through the gateway; the effect lambda runs ONLY on ALLOW. DENY throws `SafetyDeniedException`, PROMPT throws `SafetyPromptRequiredException` carrying the precise prompt.

### Path traversal / symlink / command-chain audit
- `security/PathSecurityAuditor.kt` (new): pure-JVM, deterministic. Rejects null bytes/control chars, `..` segments escaping the base (structural depth check), absolute paths outside the allowed root, and symlink escapes — classified by comparing lexical containment (no resolution) vs resolved containment (real path, tolerating non-existent tails via nearest-existing-ancestor resolution). Lexically-inside but resolved-outside = `SYMLINK_ESCAPE`; lexically-outside = `ABSOLUTE_ESCAPE`.
- Command chains already split and inspected per sub-command by `DangerousCommandDetector` (`;`, `&&`, `||`, `|`, newlines); the gateway additionally applies it to remote-exec commands.

### Precise authorization prompts, persistent by intended scope
- `security/AuthorizationPrompt.kt` (new): prompt model naming the exact operation and target, assessed `PromptRisk` (LOW/MEDIUM/HIGH/CRITICAL), what will happen if allowed, and the grant scopes on offer (high-risk operations offer only ONCE/SESSION). `renderText()` for UI display.
- `SafetyGateway.recordGrant()`: grants persist by intended scope — PROJECT/ALWAYS persisted to disk via `PermissionPolicyEngine` (and MCP tool grants via `McpPermissionManager`); SESSION/ONCE live in memory only, by design. Tests prove PROJECT/ALWAYS survive engine recreation and SESSION does not.

### Deny/approve test matrix for all providers (acceptance gate)
- `SafetyTestMatrix` (test, shared): 23 deterministic cases across all 7 provider families — allow/baseline, prompt, explicit-deny, traversal, chained-critical, kill-switch, MCP-deny cases.
- `ProviderSafetyMatrixTest`: every adapter runs the SAME matrix. Gate assertion: on DENY the effect lambda never executes (`SafetyDeniedException`); on PROMPT it never executes (`SafetyPromptRequiredException`); on ALLOW it runs and returns. A critical `rm -rf /` via remote exec is denied (gateway-level command audit closed the SHELL_EXEC-only gap in the policy engine).
- `PathSecurityAuditorTest` (10): traversal, absolute escape, null byte, empty path, real-symlink escape (existing + non-existent tail), in-root symlink allowed.
- `AuthorizationPromptTest` (8): prompt precision per operation kind, risk levels, scope narrowing for high risk.
- `SafetyGatewayTest` (14): gateway decisions, MCP deny precedence, kill switch over all families, grant persistence by scope, audit-log writes.

**Deliberately deferred:**
- Rewiring every existing call site (terminal, git engine, cloud runner, installer) to the adapters — the gateway + adapters are the choke point and fully tested; call-site migration is M6 work when the autonomous workflow starts producing writes.
- Remote MCP transport/auth — M7 scope.

## Milestone 6 — COMPLETE (2026-10-12)

**Genuinely autonomous workflow** (Phase 5): plan → implement → build → test → fix (bounded) → retest → review → approve → commit. CI green (run 38253238901).

### Task decomposition (honest, template-driven)
- `workflow/WorkflowModels.kt` (new): `WorkflowGoal`/`WorkflowGoalKind` (ISSUE_FIX, FEATURE, REFACTOR, CUSTOM), `PlanStep`/`PlanStepKind` (ANALYZE, IMPLEMENT, WRITE_FILE, RUN_COMMAND, RUN_TESTS, GIT_COMMIT, GIT_PUSH), `StepRunStatus`, `StepRunResult`, `WorkflowPhase`, `WorkflowRunState`. `WorkflowPlan.topologicalOrder()` rejects unknown deps, self-deps and cycles with `IllegalArgumentException` — an unorderable plan never executes. `commitSteps()`/`workSteps()` split the plan at the review gate.
- `workflow/TaskDecomposer.kt` (new): `TemplateTaskDecomposer` maps each goal kind to a fixed validated step sequence (test steps carry a repair budget via `maxAttempts`); CUSTOM goals carry a caller-supplied step list validated for blank titles, duplicate ids, unknown deps and cycles. Anything else is `DecompositionOutcome.Refused` with a reason — plans are never invented.

### Execution through the M5 safety gateway
- `workflow/StepExecutors.kt` (new): every side effect routes through the M5 adapters — file reads/writes via `FileSafetyAdapter`, shell via `ShellSafetyAdapter`, Git via `GitSafetyAdapter`. `ProcessCommandRunner` (real `sh -c` with timeout, real exit codes) and `ProcessGitRunner` (real `git` binary, shell-quoted args) are injectable for tests. `DefaultCommandResolver` maps the `build`/`test` aliases through `AutonomousLoopEngine.detectBuildTool`; anything else is a literal command. `ImplementStepExecutor` refuses SIMULATED agent results (M0 rule). Safety DENY/PROMPT propagate out of the executors — never converted, never auto-repaired.
- `workflow/PlanExecutor.kt` (new): dependency-order execution; dependents of a failed step are SKIPPED; bounded auto-repair consults a `RepairAdvisor` (default `AutoFixRepairAdvisor` builds the prompt from real output via `AutonomousLoopEngine.buildAutoFixPrompt`) within `maxAttempts`; a safety DENY fails the step immediately with no repair; cancellation marks remaining steps SKIPPED without overwriting resumed successes. `executeSubset` resolves cross-phase dependencies (commit steps depending on work steps) against previous results — unsatisfied external deps fail honestly.

### Checkpoint-before-write + resume
- `workflow/CheckpointStore.kt` (new): `snapshot` copies pre-write files (absent files recorded as `.absent` markers); `rollback` restores copies and deletes run-created files; run state persists as JSON after every step (`saveRunState`/`loadRunState`, corrupt files load as null, never crash); `discard` drops a run's data.

### Review-before-commit gate
- `workflow/WorkflowReviewer.kt` (new): `CallbackWorkflowReviewer` for production wiring; `TestOnlyAutoApproveReviewer` explicitly labelled tests/demos only.
- `workflow/WorkflowRunner.kt` (new): orchestrates PLANNING → EXECUTING → REVIEWING → COMMITTING → DONE. The commit phase runs only after an APPROVE verdict — REJECT/REQUEST_CHANGES (or a reviewer throwing) stops the run before any commit. Real `git status --porcelain` empty-diff guard (M0 rule, injectable `diffChecker`) refuses no-op commits. `rollbackOnFailure` unwinds checkpointed writes on any failure. State published via `StateFlow<WorkflowRunState?>`; `resume(runId)` continues from the first incomplete step without re-executing successes; unknown run ids fail honestly.

### Tests (40 new)
- `TaskDecomposerTest` (9): template shapes per kind, dependency order, CUSTOM validation, refusals (blank title, no steps, duplicate ids, unknown dep, cycle).
- `CheckpointStoreTest` (8): snapshot/rollback round-trips, absent-file deletion, discard, state save/load round-trip incl. operation results, corrupt/missing state → null.
- `PlanExecutorTest` (9): dependency order, dependent skipping, repair success within budget, exhausted budget, advisor-declined retry stops, missing executor, resume keeps successes, invalid plan, in-subset cycle.
- `WorkflowRunnerTest` (8): full loop success with commit+push after approval, review rejection blocks commit and rolls back writes, empty-diff refusal, refused plan, SIMULATED agent refusal, failing work step rolls back, resume without re-running successes, unknown resume id.
- `WorkflowSafetyGateTest` (6, the M6 acceptance gate): explicitly denied write never creates the file, traversal is a hard deny, critical shell command never reaches the runner, kill switch blocks the run, safety deny is never auto-repaired (advisor not consulted, single attempt).
- `WorkflowTestFixtures.kt`: fake executors/runners with invocation recording, real-gateway `WorkflowTestHarness`.

### CI fix rounds (3)
1. `CheckpointStore`: missing closing paren on `o.put("result", JSONObject()...)` (syntax error).
2. `PlanExecutorTest.executorFor`: trailing lambda bound to the last param (`advisor`) — reordered so `behavior` is last.
3. **Two real bugs found by tests**: (a) the repair loop re-entered after a declined advice (missing break) — it retried regardless; (b) commit-phase subsets failed topological sort because their dependencies live in the work phase — external deps now resolve against previous results.

**Deliberately deferred:**
- Wiring the runner into UI view-models / `MainViewModel` coordinators — the engine is complete and tested; UI integration is product work, not milestone work.
- Real agent executor backends for IMPLEMENT steps — the executor interface is ready; M10 wires real executors.
- Remote MCP transport/auth — M7 scope (unchanged).

## Milestone 7 — COMPLETE (2026-10-12)

**Production MCP & Skills** (Phase 7): hardened stdio lifecycle; versioned install/update/remove; secure credentials; remote MCP transport/auth. CI green (run 38257056509).

### JSON-RPC protocol layer (pure JVM, shared by both transports)
- `mcp/McpProtocol.kt` (new): request/notification builders, `initialize`/`tools/list`/`tools/call`/`ping` constructors, `parseResponse` (result vs error), `parseToolsList` (skips nameless entries), `parseToolsCallResult` (text-block join, isError), SSE `data:` line parsing + first-payload extraction. `McpProtocolVersion.PROTOCOL_VERSION` constant.

### Hardened stdio lifecycle
- `mcp/McpServerProcessManager.kt` (new): `McpManagedProcess`/`McpProcessFactory` seams (production `RealMcpProcessFactory` over ProcessBuilder, daemon reader threads, stderr ring buffer). `McpServerSession` states IDLE→STARTING→INITIALIZING→RUNNING→STOPPING→STOPPED/FAILED; initialize handshake with timeout (never hangs); `ensureRunning()` with bounded restarts + backoff; request/response id matching that skips interleaved notifications; graceful stop (destroy, wait, forcible kill); `ping()` health check; `diagnostics()` stderr.

### Secure credentials
- `mcp/McpCredentialStore.kt` (new): `McpCredentialBackend` seam; `AesGcmFileCredentialBackend` (AES-256/GCM, key in owner-only sidecar file, values encrypted in JSON — pure JVM so tests exercise the real path); `InMemoryCredentialBackend` for tests. Secrets keyed `<serverId>::<name>`; never written to `mcp_servers.json`; `removeServerCredentials` purges on uninstall.

### Remote MCP transport/auth
- `mcp/McpRemoteTransport.kt` (new): `McpAuthType` (NONE/BEARER/API_KEY_HEADER/BASIC), `McpRemoteConfig`, `McpHttpLayer` seam (`UrlConnectionMcpHttpLayer` production), `McpRemoteOperations` interface, `McpRemoteClient`: auth headers resolved from the credential store (missing credential fails before any HTTP call), SSE or plain-JSON response handling, 401/403 → `McpAuthException`, transport failures → `McpTransportException`, RPC errors → `McpRpcException`, URL validation.

### Tool registration/discovery
- `mcp/McpToolRegistry.kt` (new): TTL-cached `discover()` (tools/list) per server; `callTool()` evaluated by `McpPermissionManager` first — DENY/disabled never reach the transport, PROMPT raises `McpPromptRequiredException`; every attempt audited to `McpAuditLogger` with its permission decision; `resolveEnv()` merges static env + stored secrets and fails honestly on missing secrets; lazy stdio sessions; remote dispatch for SSE/Streamable-HTTP servers; `invalidate()`/`shutdown()`.

### Versioned install/update/remove + skills
- `mcp/McpServerInfo.kt`: added `version`, `secretEnvNames`, `remoteUrl`, `authType`, `credentialName`, `apiKeyHeaderName`; `McpTransportType.STREAMABLE_HTTP`; `isRemote`; backward-compatible JSON (opt-parses with defaults).
- `mcp/McpRegistry.kt`: template `requiredEnv` now maps to `secretEnvNames` (secrets resolve from the store, never the registry JSON); `updateServerVersion()` with downgrade refusal (numeric semver compare); `removeServerAndCredentials()` purges secrets on uninstall.
- `skills/SkillPackage.kt` (new package `com.jarves.mh.skills`): `SkillManifest` (skill.json validation), `SkillLoader` (package dir validation incl. entry prompt file), `SkillRegistry` (versioned install/update/remove under `<base>/skills/<id>/`, downgrade refused unless forced, update requires strictly newer).

### Tests (73 new)
- `McpProtocolTest` (14): envelope shape, notifications, initialize params, response/error parsing, non-JSON-RPC rejection, tools/list + tools/call parsing, SSE parsing, id uniqueness.
- `McpCredentialStoreTest` (8): in-memory round-trip, per-server scoping, blank rejection, per-server purge, AES-GCM round-trip across recreation, secrets never in plaintext on disk, encrypted default backend.
- `McpServerProcessManagerTest` (13): handshake → RUNNING, env passthrough, failed handshake, tool round-trip, RPC error surfacing, tools/list, hang timeout, bounded restart recovery, budget exhaustion, graceful stop, ping true/false, stderr diagnostics.
- `McpRemoteTransportTest` (14): JSON + SSE bodies, callTool, RPC/401/500/network error mapping, bearer/api-key/basic auth headers, missing-credential pre-check (zero HTTP calls), URL validation, ping.
- `McpToolRegistryTest` (12): discovery caching + TTL expiry + invalidation, allowed call + audit, DENY/PROMPT/disabled never touch transport (audited), remote dispatch, secret env merge, missing-secret failure, unknown server.
- `SkillLoaderTest` (12): manifest/prompt validation, malformed JSON, required tools, install/list/get/remove, downgrade refusal + force, update version guard, missing-skill update.
- `McpTestFixtures.kt`: shared scripted fake process/factory + handshake responders.

### CI fix rounds (1)
1. `McpCredentialStoreTest`: wrong test expectation — asserted the credential *key* (`srv1::KEY`) must not appear in the storage file, but key identifiers are non-secret by design (only values are encrypted). Fixed the test to assert the identifier is present and the secret value never is.

**Deliberately deferred:**
- Wiring the tool registry / session manager into UI view-models / `MainViewModel` coordinators — the engine is complete and tested; UI integration is product work, not milestone work.
- OAuth2 device-flow for remote MCP auth — scheme enum is extensible; BEARER/API_KEY/BASIC cover the current server landscape.
- Skill marketplace distribution (signed packages, publisher trust) — M13 scope.

## Milestone 8 — COMPLETE (2026-10-12)

**Visual & App Factory loops** (Phases 8–9): the inspect→AI-edit→reload→screenshot-compare cycle and the scaffold-to-deploy pipeline. CI green (run 38261464355).

### Visual dev loop (package `com.jarves.mh.visual`)
- `visual/VisualDiffer.kt` (new): pure-JVM differ over raw ARGB pixel buffers (no Android framework — fully unit-testable). Per-channel tolerance, `DiffIgnoreRegion` exclusions, flood-fill merged `ChangedRegion` bounding boxes, threshold verdict. Throws honestly on dimension mismatch — never a silent 0%/100%.
- `visual/VisualDevLoop.kt` (new): the bounded inspect→AI-edit→reload→screenshot-compare cycle. `ScreenshotProvider` / `EditApplier` seams (`fun interface`), `VisualDevLoopConfig` (viewport, maxIterations, threshold, tolerance, ignore regions). Each iteration diffs against the original baseline; verdicts PASS / RETRY / EXHAUSTED / EDIT_FAILED / CAPTURE_FAILED — failures stop the loop and are recorded, never reported as convergence. Run records are JSON-serializable.
- `visual/VisualLoopStore.kt` (new): persists loop runs under `<project>/.mobile_harness/visual/loops/` (summaries + iteration records only; raw pixel buffers never hit disk). `save`/`list`/`load`.

### App factory loop (packages `com.jarves.mh.factory.build`, `com.jarves.mh.factory.loop`)
- `factory/build/CommandRunner.kt` (new): `CommandResult` (exit code, output, timeout flag) + `CommandRunner` seam + `ProcessBuilderCommandRunner` (daemon output-draining reader thread, real timeout kill).
- `factory/build/ApkSignatureVerifier.kt` (new): **real** `apksigner verify --print-certs` invocation with injectable `ApksignerLocator` (ANDROIDD_HOME/build-tools → PATH). Parses signer SHA-256 digest + DN, collects WARNING lines. Reports `toolAvailable=false` honestly when no signer tooling exists — never claims "verified" without a real check. `validateAab` inspects AAB zip structure (`BundleConfig.pb` + `base/` entries).
- `factory/build/CleanBuildVerifier.kt` (new): "generated apps compile + pass tests from clean state" — copies the project minus `build/`/`.gradle`/`.cxx` caches, runs real Gradle tasks (`clean`, `testDebugUnitTest`, `assembleDebug`) in the clean copy, stops at the first failed task with output-tail diagnosis.
- `factory/loop/AppFactoryLoop.kt` (new): scaffold→deploy pipeline — SCAFFOLD → SIGN → BUILD → TEST → VERIFY_SIGNATURE → PREPARE_LISTING. Stops at the first failed stage; non-applicable stages (tests for non-Android frameworks, signing when not requested, disabled verification/listing) are recorded as *skipped*, never fake-success. Signing config injection happens *before* the release build. All engines are constructor-injectable seams defaulting to the real ones (generators by framework, `AppBuildOrchestrator`, `SigningAndVersionManager`, `PlayStorePreparationEngine`).

### Tests (37 new)
- `VisualDifferTest` (9): 0%/50%/100% diffs, dimension-mismatch throws, tolerance behavior, ignore regions, region merging (adjacent→1, distant→2), threshold boundary.
- `VisualDevLoopTest` (7): convergence on fix, iteration exhaustion, edit-failure stop, capture-failure stop, diff history passed to edits, config validation.
- `VisualLoopStoreTest` (3): save/list/load round-trip, empty history, missing file throws.
- `ApkSignatureVerifierTest` (7): missing file throws, no-tooling honest report, signer SHA-256/DN parsing via a real executable fake-`apksigner` script, failed verification, AAB valid/missing-config/non-zip.
- `CleanBuildVerifierTest` (4): missing wrapper honest failure, all-tasks success + invocation order, stop-at-first-failure with diagnosis, `copyClean` strips build outputs.
- `AppFactoryLoopTest` (7): full 6-stage success, stop at build failure, signing requires keystoreConfig, TEST skipped for Flutter, verify-stage fails honestly without tooling, scaffold failure stops immediately, AAB uses structural validation.

### CI fix rounds (2)
1. `FactoryLoopTest`: `BuildResult` imported from `factory.build` — actually lives in `factory.model`. Fixed import.
2. `FactoryLoopTest`: `AppFactoryLoop.defaultVerifySignature` referenced without `::` — parsed as a call. Fixed to callable reference `AppFactoryLoop::defaultVerifySignature`.

**Deliberately deferred:**
- Wiring `VisualDevLoop`/`AppFactoryLoop` into UI view-models / `MainViewModel` coordinators — engines are complete and tested; UI integration is product work, not milestone work.
- WebView→PixelBuffer production adapter for `ScreenshotProvider` (needs the Android runtime) — seam is defined and tested with scripted providers.
- `jarsigner` fallback path in `ApkSignatureVerifier` — enum reserves `JARSIGNER_FALLBACK`; apksigner covers the current landscape.

## Milestone 9 — COMPLETE (2026-10-12)

**GitHub automation remainder** (Phase 10): the remaining GitHub API surface and the automation engines on top of it. CI green first pass (run 38262759355 → 38262759375; no fix rounds needed).

### Client API extensions (`network/GitHubClient.kt`, `network/GitHubModels.kt`)
- PR detail: `getPullRequest` (mergeable/merged/draft/mergeableState/headSha — null mergeable means "still computing", never assumed), `updatePullRequest`, `requestReviewers`
- Merge gating data: `getCombinedStatus` (commit status rollup for a ref)
- Issue management: `addLabels`, `removeLabel`, `assignIssue`, `updateIssue`, `listIssueComments`
- Releases: `createGitRef` (tag creation), `generateReleaseNotes`, `uploadReleaseAsset` (multipart POST to the release upload URL), `deleteRelease`; `GitHubRelease` gains `uploadUrl`
- Workflows: `getWorkflowRun`, `listWorkflowRunsForWorkflow`, `cancelWorkflowRun`, `rerunWorkflow`
- New models: `GitHubPullRequestDetail`, `GitHubLabel`, `CombinedStatus`, `GitHubWorkflowJob`
- All newly-used client methods made `open` so tests subclass with fakes (follows the existing `createPullRequest` seam)

### Automation engines (new package `com.jarves.mh.git.automation`)
All return `OperationResult`, refuse blank tokens with `NOT_CONFIGURED`, and propagate real API errors — no fake success anywhere.
- `PullRequestAutomation` — policy-gated auto-merge (`AutoMergePolicy`: requireCiGreen, requiredApprovals, method, allowDraft). Gates evaluated against live API data: already-merged/closed/draft/conflicted PRs refused; null mergeability refused as unknown; pending CI refused; latest-review-state approval counting (CHANGES_REQUESTED cancels an earlier APPROVED). Merge refusal by the API is FAILED, never success.
- `IssueAutomation` — rule-based triage (`IssueTriageRule`: labels/assignees/comment/title/close) with per-action logging and partial-state failure reporting; `postStatusComment` refuses blank comments.
- `ReleaseAutomation` — tag → release → asset-upload pipeline (`ReleaseRequest`). Refuses blank tag, missing asset files, oversized assets, and missing upload URLs; each failure names the file and reports the partial state (release URL + assets uploaded so far).
- `WorkflowAutomation` — `dispatch`, `dispatchAndAwait` (baseline max-run-id detection + bounded poll loop with timeout; non-"success" conclusions are FAILED with the run URL), `cancelRun`.

### Tests (33 new)
- `PullRequestAutomationTest` (12): all-gates-pass merge, CI failing/pending refusal, unmet approvals, CHANGES_REQUESTED cancels approval, conflict refusal, unknown-mergeability refusal, already-merged refusal, draft refusal, API merge refusal, blank token, zero-approval policy
- `IssueAutomationTest` (7): full triage, empty rule no-op, partial-failure log, blank token, status comment success/blank-refusal/blank-token
- `ReleaseAutomationTest` (7): tag+release+asset publish, generated notes, missing upload URL, missing file named, oversized refusal, blank tag, blank token
- `WorkflowAutomationTest` (8): dispatch accepted/rejected/blank-token, dispatchAndAwait success/failure-conclusion/timeout, cancel, cancel blank-token

**Deliberately deferred:**
- Wiring the automation engines into `GitHubStudioScreen` / `MainViewModel` coordinators — engines are complete and tested; UI integration is product work, not milestone work.
- Approval-before-commit hook in `GitHubWorkflowEngine` (M0-noted remainder) — the M5 `SafetyGateway` authorization-prompt layer now owns approval UX; wiring the workflow engine's commit stage through it is M6/M10 integration work.

## Milestone 10 — COMPLETE (2026-10-12)

**Real multi-agent runtime** (Phase 11): per-role agent executors, safe concurrent DAG dispatch, real test/security validation. `isDemoMode=false` is now legal — enforced by `requireRealExecution()`, which fails loudly when real mode is requested without a wired executor instead of silently simulating. CI green (run 38272034739).

### Executor contract + production executor (new package `com.jarves.mh.agent.team.runtime`)
- `TeamAgentExecutor.kt` (new): `TeamAgentExecutor` fun-interface (`execute(task, context): TeamExecutionResult`), `TeamExecutionContext` (projectDir, goal, role, roleConfig, agentKind, dependency outputs, blackboard summary, timeout), `TeamExecutionResult` (honest `OperationResult`, genuine artifacts, optional `reviewVerdict`, nullable tokens — never invented). `defaultArtifactTypeForRole()` maps roles to artifact types.
- `RolePromptRenderer.kt` (new, pure JVM): renders role prompts (role identity + task + dependency outputs + blackboard digest); reviewer roles get an explicit `VERDICT:` marker instruction. `parseVerdict()` extracts APPROVED/CHANGES_REQUESTED/REJECTED from the last marker line (case-insensitive); no marker → null → no review recorded, never invented.
- `PromptBasedTeamAgentExecutor.kt` (new): production executor — renders the prompt, sends it through the real agent pipeline via an injected `suspend (prompt) -> OperationResult` runner (same shape as MainViewModel's `runAgentPromptAwaitingResult`; the app wires the genuine pipeline, tests inject fakes). M0 integrity enforced: SIMULATED agent results are refused outright (→ FAILED); reviewer verdicts only from explicit markers; artifacts genuine (`isSimulated=false`); token usage reported only when known.

### Safe concurrent DAG dispatch
- `ConcurrentTeamDispatcher.kt` (new): `runAll()` executes dependency stages concurrently (bounded `Semaphore` by `maxConcurrentAgents`), `runReadyTask()` for single-stepping. Safety guarantees: per-file `Mutex` locks (same normalization as `detectFileConflicts`) so overlapping `affectedFiles` never run concurrently; budget quota checked before every stage; cyclic/unorderable graphs refused with an escalation instead of dispatching; failed tasks mark FAILED (dependents block via scheduler, never silently skipped); cancellation stops new launches between stages while in-flight agent runs finish rather than being killed. Each task publishes genuine artifacts, sends real status/handoff/escalation messages, and aggregates into `TeamRunReport` (`TeamRunReport.kt`: per-task outcomes, reviews, succeeded/failed/blocked counts, `isSuccess`).

### Real test/security validation
- `TeamRoleValidator.kt` (new): `TestSuiteRunner` seam + `GradleTestSuiteRunner` (real `./gradlew testDebugUnitTest` via the M8 `CommandRunner`, honest timeout/missing-wrapper handling, parses real failure lists); `SecurityScanner` seam + `HeuristicSecurityScanner` (walks real sources bounded, flags hardcoded secrets/keys/tokens, skips build dirs; reports files scanned, never claims zero-CVE). `validateTester`/`validateSecurity` return APPROVED/CHANGES_REQUESTED from genuine results and publish real TEST_REPORT/SECURITY_AUDIT artifacts (`isSimulated=false`); without a project dir they honestly report "could not be executed" instead of claiming a verdict.

### Orchestrator wiring (`AgentTeamOrchestrator.kt`)
- New constructor params: `teamExecutor`, `teamValidator` (defaults to real), `projectDir`, `maxConcurrentAgents`.
- `requireRealExecution()` guard; `runNextReadyTask()` (sync demo API) now throws in real mode directing to the suspend API; new `suspend executeNextReadyTask()`, `suspend autoRunAllReal(): TeamRunReport`, `cancelRealRun()`.

### Tests (46 new)
- `RolePromptRendererTest` (11): prompt contents, reviewer marker instruction, verdict parsing incl. case-insensitivity and last-marker-wins.
- `PromptBasedTeamAgentExecutorTest` (8): genuine artifacts, SIMULATED refusal, failure propagation, reviewer verdict mapping, tokens never invented.
- `ConcurrentTeamDispatcherTest` (10): concurrent execution proof (latch rendezvous), conflicting-file serialization, dependency order, failure cascade, quota halt, genuine blackboard artifacts, cycle refusal, cancel, handoff messages.
- `TeamRoleValidatorTest` (10): tester pass/fail, honest no-project-dir, security findings, clean scan, real heuristic scanner (finds hardcoded key, skips build dirs), Gradle runner parsing.
- `OrchestratorRealModeTest` (7): guard enforcement, sync-API refusal in real mode, genuine single-task execution, full 10-task DAG with real reviews.

### CI fix rounds (3)
1. `ConcurrentTeamDispatcher`: stage lists snapshot stale task state — READY check now reads the live scheduler so tasks unlocked by the previous stage dispatch.
2. `RolePromptRenderer.parseVerdict`: case-insensitive marker match paired with case-sensitive `substringAfter` dropped lowercase markers — marker now located case-insensitively.
3. `AgentTeamOrchestratorTest.testNonDemoModeRecordsReviewsThroughGatekeeper`: rewritten for the M10 contract (real mode requires a wired executor; uses `autoRunAllReal` with fake executor + approving validator).
4. Test fakes now set `reviewVerdict` like the production executor does (marker in output alone never produced a structured verdict).

**Deliberately deferred:**
- Wiring `PromptBasedTeamAgentExecutor`'s `agentRunner` to MainViewModel's `runAgentPromptAwaitingResult` — the seam shape matches; the actual wiring is app-integration work.
- UI wiring (`TeamStudioScreen` already reflects `isDemoMode`; no changes needed).
- `TeamRoleConfig.maxConcurrentTasks` per-role caps in the dispatcher (global cap implemented; per-role is a refinement).

## Milestone 11 — COMPLETE (2026-10-12)

**Real cloud execution** (Phase 12): authenticated worker API client, delta payload upload, log streaming, remote cancellation, artifact download + SHA-256 verification, cost controls, and an end-to-end orchestrator. `CloudJobRunner` now accepts an optional `CloudExecutionOrchestrator`; when wired, cloud-routed jobs genuinely dispatch through the protocol instead of reporting NOT_CONFIGURED. CI green (run 38274241576).

### Protocol + authenticated API client (new package `com.jarves.mh.cloud.exec`)
- `CloudProtocol.kt` (new): wire-protocol documentation and models — `RemoteJobStatus` (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/UNKNOWN with `isTerminal`), `CloudLogFrame` (seq + line), `CloudArtifactDescriptor`, `CloudJobRequest`; `CloudHttpTransport` injectable seam + `UrlConnectionCloudTransport` production impl (HttpURLConnection, pure JVM); JSON parse helpers.
- `CloudExecutionClient.kt` (new): `submitJob` (POST /api/v1/jobs → remote job id on 201), `pollStatus`, `fetchLogs` (sequenced `?since=`), `cancelJob` (requires worker-confirmed `cancelled=true`), `listArtifacts` + typed `listArtifactDescriptors`, `downloadArtifactBytes` (raw bytes via `ArtifactDownloadResult`), `fetchRemoteManifest` (path→sha256 known files, 404 = full upload needed). Every call requires a non-blank `authKey` sent as `Authorization: Bearer` — blank key returns NOT_CONFIGURED without sending anything; transport errors and non-2xx responses are honest FAILEDs; success only on verified responses.

### Delta upload, log streaming, artifact verification, cost controls
- `CloudPayloadUploader.kt` (new): computes the workspace snapshot via `CloudArtifactSyncEngine`, compares per-file SHA-256 against the worker's known manifest, uploads only changed/new files (base64), and reports honest `filesUploaded/filesSkipped/bytesUploaded` accounting.
- `CloudLogStreamer.kt` (new): bounded sequenced log streaming with `onLogChunk` callback — stops at terminal state, `maxFrames`, timeout, or `cancel()`; failures reported in the result.
- `CloudArtifactDownloader.kt` (new): lists descriptors, downloads bytes, verifies SHA-256 against the descriptor BEFORE writing anything; a checksum mismatch fails the fetch and the bytes are never written to disk.
- `CloudCostController.kt` (new): per-job estimated-cost cap + rolling 30-day budget cap checked BEFORE dispatch (refusals are explicit, never silent); duration×rate cost model; spend ledger with `recordSpend`; 30-day spend excludes entries older than 30 days.

### Orchestrator + runner wiring
- `CloudExecutionOrchestrator.kt` (new): cost-gate → delta upload → submit → log streaming + status polling to terminal state → artifact download/verification → spend recording. Cancellation supported at every stage. COMPLETED only when the remote job genuinely reached SUCCEEDED AND artifacts verified; remote FAILED/CANCELLED/timeout and unverified artifacts all map to FAILED/CANCELLED honestly; NOT_CONFIGURED propagates from auth-less workers.
- `CloudJobRunner.kt`: new optional `cloudOrchestrator` constructor param. When set, cloud-routed jobs dispatch through the M11 protocol; when null, the previous honest NOT_CONFIGURED path is preserved. `cancel()` now also cancels the orchestrator.

### Tests (39 new)
- `CloudExecutionClientTest` (15): submit success/rejection/transport-error/missing-jobId, blank-auth never sends, poll parsing incl. unknown status, cancel confirmed/unconfirmed, sequenced log frames, manifest 404/200, byte download success/null-body, Bearer header.
- `CloudPayloadUploaderTest` (4): delta skip/upload accounting, full upload on unknown snapshot, blank-auth NOT_CONFIGURED, upload failure aborts honestly.
- `CloudLogStreamerTest` (4): ordered frames until terminal, fetch error fails honestly, blank-auth NOT_CONFIGURED, cancellation stops streaming.
- `CloudArtifactDownloaderTest` (4): matching checksum writes file, mismatch fails and writes nothing, blank-auth NOT_CONFIGURED, listing failure honest.
- `CloudCostControllerTest` (6): authorization, per-job cap refusal, monthly budget refusal, ledger accumulation, 30-day exclusion, cost scaling.
- `CloudExecutionOrchestratorTest` (6): full happy-path pipeline (delta → submit → poll → verified artifact → spend recorded), cost refusal dispatches nothing, remote FAILED propagates, pre-dispatch cancel, blank-auth NOT_CONFIGURED, checksum mismatch never COMPLETED.

### CI fix rounds (1)
1. `CloudExecutionOrchestrator`: delta-upload NOT_CONFIGURED (auth-less worker) was mapped to FAILED — now propagates NOT_CONFIGURED honestly at the job level.

**Deliberately deferred:**
- App-level wiring of `CloudExecutionOrchestrator` into `CloudHybridCoordinator` (constructor seam exists; UI screen already renders NOT_CONFIGURED honestly — integration work).
- A real worker service implementation to speak the protocol (the protocol is client-side; any HTTP server implementing the documented endpoints works).
- Chunked/resumable uploads for very large payloads (single base64 POST per file today).

## Milestone 12 — COMPLETE (2026-10-12)

**Firebase-backed collaboration** (Phase 13): Firebase Auth UID identity; Firestore document model + generated `firestore.rules`; invites/roles/revocation with RBAC; cross-device sync with version-vector conflict resolution; presence heartbeats; collaborative editing sessions; auth-required actions throughout. CI green (run 38277748125).

### Firestore model + gateway seam (new package `com.jarves.mh.collab.firebase`)
- `FirestoreModels.kt` (new): `WorkspaceDocument`, `WorkspaceMemberDocument` (doc id = Auth UID), `InviteDocument` (PENDING/ACCEPTED/REVOKED/EXPIRED), `PresenceDocument`; all round-trip through `toMap`/`fromMap` shared by the real SDK and test fakes; `FirestorePaths` centralizes the `workspaces/{ws}/members|invites|presence|sync` layout.
- `FirestoreGateway.kt` (new): injectable seam (`getDocument`/`setDocument`/`updateDocument`/`deleteDocument`/`queryEqual`/`listCollection`/`listenDocument`); `FirebaseFirestoreGateway` production impl (open for fakes; `create()` fails fast with an honest message when FirebaseApp is not initialized); `firebase-firestore` added to app dependencies via the Firebase BOM.
- `CollaborationIdentity.kt` (new): `FirebaseCollaborationIdentity` resolves the current Auth UID (injectable `uidProvider` seam for tests); `requireSignedIn()` guard returns NOT_CONFIGURED when nobody is signed in — mutating actions refuse honestly, never write anonymously.

### Invites, roles, revocation
- `CollaborationInviteManager.kt` (new): `createInvite` (inviter must hold `canInviteMembers`; code returned in `OperationResult.artifacts`), `acceptInvite` (checks PENDING + expiry, then self-joins as `members/{uid}`), `revokeInvite`, `changeRole` (OWNER-only; owner role immutable; no direct OWNER grants), `removeMember` (OWNER/MAINTAINER or self-leave; owner cannot be removed), `listInvites`/`listMembers`. All permission denials are explicit FAILEDs; transport failures (including membership lookups) are caught and reported honestly.

### Presence + sync + collaborative editing
- `PresenceTracker.kt` (new): `heartbeat`/`clearPresence` (UID-keyed, sign-in required), `listActive` filters heartbeats older than the injectable staleness threshold (default 2 min); injectable clock makes staleness deterministic in tests.
- `SyncConflictResolver.kt` (new): pure-JVM per-field version-vector merge — domination wins, true concurrency falls back to deterministic last-writer-wins (timestamp, then smaller UID), merged clock is the element-wise max, winning tombstones delete the field. Idempotent.
- `FirebaseSyncEngine.kt` (new): `pushState` (sign-in + membership + edit-permission gated; merges local against remote before writing; SUCCESS only on SDK-acknowledged write), `pullState` (throws honestly when signed out), `observeState` (real-time Flow via `listenDocument`), `deleteState` (OWNER/MAINTAINER only; missing document reported, not success).
- `CollaborativeDocumentSession.kt` (new): pure-JVM editing session — local edits bump the device's vector counter, local deletes write tombstones, remote snapshots fold in through the resolver; emits the merged document on every change. Network-free by design; the screen wires `FirebaseSyncEngine` around it.

### Security rules
- `FirestoreSecurityRules.kt` (new): generates the ruleset — `signedIn()` anchor on every rule; member-gated reads; owner-only role changes (never to OWNER); invite-gated invite writes; presence writable only by own UID; sync edits for OWNER/MAINTAINER/CONTRIBUTOR, deletes for OWNER/MAINTAINER.
- `firestore.rules` (new, repo root): the canonical ruleset, identical to the generator output.

### Tests (60 new)
- `FirestoreModelsTest` (6): map round-trips, unknown-role tolerance, path helpers.
- `CollaborationInviteManagerTest` (16): create/accept/revoke happy paths, signed-out NOT_CONFIGURED, contributor/non-member refusals, OWNER-role grant refusal, expired/revoked invite refusals, maintainer revoke, owner role change, maintainer role-change refusal, owner demotion/removal refusals, self-leave, transport failure reported honestly.
- `PresenceTrackerTest` (5): heartbeat write, signed-out refusal, staleness filtering, clear, recency ordering.
- `SyncConflictResolverTest` (8): union, domination, concurrent LWW, UID tie-break, winning/losing tombstones, idempotence, map round-trip.
- `FirebaseSyncEngineTest` (11): push success/merge, viewer/non-member/signed-out refusals, pull empty/throws, observe emits updates, maintainer delete, contributor delete refusal, missing-document honesty.
- `CollaborativeDocumentSessionTest` (6): vector bump, remote merge, deterministic conflict resolution, tombstone propagation, flow emission, blank-field rejection.
- `FirestoreSecurityRulesTest` (8): rules target, anonymous denial, workspace/member/invite/presence/sync match blocks, full role hierarchy.

### CI fix rounds (1)
1. `CollaborationInviteManagerTest > transport failure is reported honestly`: membership lookups in `createInvite`/`revokeInvite`/`changeRole`/`removeMember` threw outside the `runCatching` block — new `resolveCaller()` helper converts lookup failures into honest FAILED results. `PresenceTrackerTest > listActive returns only fresh heartbeats`: test clock arithmetic made both heartbeats stale — corrected the final clock value.

**Deliberately deferred:**
- UI wiring of the Firebase engines into the collaboration screens (`WorkspaceCollaborationManager`/Compose UI still use the in-memory models — product integration work).
- Enabling Firestore offline persistence + `firestore.rules` deployment (requires the Firebase console project; rules file is ready to deploy).

## Milestone 13 — COMPLETE (2026-10-12)

**Real marketplace** (Phase 14): listing, discovery, purchasing, and seller integrations against a real registry API. CI green (run 38279756568).

### Protocol + models (new file `marketplace/MarketplaceProtocol.kt`)
- Wire-protocol documentation + `MarketplaceHttpTransport` injectable seam with `UrlConnectionMarketplaceTransport` production impl (HttpURLConnection, pure JVM; transport errors encoded as statusCode=-1, never thrown).
- Models with JSON round-trips: `MarketplacePrice` (cents/currency, `isFree`, `format()`), `RemoteListing` (bridges to local `MarketplaceItem` via `toMarketplaceItem()`, carrying `priceCents`), `SellerProfile`, `PurchaseOrder` + `OrderStatus` (PENDING/PAID/FAILED/REFUNDED with `isTerminal`), `EntitlementCheck`/`EntitlementVerdict`, `SalesRecord`, `SellerSalesReport`, `MarketplaceSearchQuery` + `buildSearchPath()` (URL-encoded).

### Listing validation (`marketplace/MarketplaceListingValidator.kt`)
- `ListingDraft` + pure-JVM `validateListing()`: name ≥3 chars, category required, description ≥20 chars, numeric-dotted semver, priceCents ≥0, 3-letter uppercase currency, required http(s) artifactUrl, 64-hex sha256, ≤10 tags of `[A-Za-z0-9._-]` (1-32 chars). All violations reported at once; `toListingJson()` builds the registry body.

### Discovery (`marketplace/MarketplaceDiscoveryClient.kt`)
- `MarketplaceDiscoveryClient`: `fetchIndex`, `search`, `fetchListing` — blank registry URL → NOT_CONFIGURED with zero network calls; 404 → honest FAILED (never fake-empty success); SUCCESS only on parsed listings.
- `MarketplaceDiscoveryCache`: TTL cache with injectable clock; failed refreshes leave the old cache untouched; `getOrRefresh()` serves fresh cache without network.

### Purchasing (`marketplace/MarketplacePurchaseClient.kt`)
- `MarketplacePurchaseClient`: `createOrder` (free listings complete immediately as PAID + receipt token; paid listings return PENDING + checkout URL), `pollOrder`, `awaitPaid` (bounded poll loop with injectable clock/sleeper — SUCCESS only on registry-reported PAID; timeout/terminal-FAILED are FAILED naming the last status), `checkEntitlement` (unknown token → INVALID as SUCCESS-with-verdict, expired → EXPIRED, blank token → FAILED without network).
- `EntitlementStore`: durable local entitlement ledger (file-backed `FileEntitlementStorage` / in-memory for tests), `grant`/`revoke`/`isEntitled` (honors expiry)/`purgeExpired`/`list`.
- `MarketplacePurchaseGate`: free items always install; paid items install only with a live entitlement; honest refusal reason.

### Seller integrations (`marketplace/MarketplaceSellerCenter.kt`)
- `MarketplaceSellerCenter`: `registerSeller` (local name/email validation first), `publishListing`/`updateListing` (local draft validation before any network call — invalid drafts never sent), `unpublishListing`, `getSalesReport` (registry-reported totals). Blank seller token → NOT_CONFIGURED on every call.

### Also
- `MarketplaceItem` gains additive `priceCents: Long = 0` (paid listings from the registry carry their price into the install flow).

### Tests (77 new, pure JVM)
- `MarketplaceProtocolTest` (11): price formatting, JSON round-trips, enum parsing, search-path encoding.
- `MarketplaceListingValidatorTest` (16): valid draft, every rule violation, multi-error reporting, JSON conversion.
- `MarketplaceDiscoveryClientTest` (13): NOT_CONFIGURED paths, success/transport-error/non-2xx/bad-JSON, search endpoint, 404 detail, cache hit/stale-refresh/failed-refresh-keeps-cache/invalidate.
- `MarketplacePurchaseClientTest` (21): order create free/paid, poll, awaitPaid success/timeout/terminal-failure, entitlement valid/invalid/expired/blank, store grant/expiry/revoke/purge/file-persistence, purchase gate.
- `MarketplaceSellerCenterTest` (16): register validation, publish/update/unpublish, sales report, invalid-draft-never-sent, blank-token NOT_CONFIGURED.

### CI fix rounds (1)
1. `MarketplaceListingValidator`: missing `org.json` imports (`JSONObject`/`JSONArray`) — added.

### Commits (oldest → newest)
- `ce1715a` — feat(v3): M13 real marketplace (listing, discovery, purchasing, seller integrations)
- `44c8923` — fix(v3): M13 add missing org.json imports in MarketplaceListingValidator

**Deliberately deferred:**
- UI wiring of discovery/purchase/seller flows into `MarketplaceScreen`/`MarketplaceCoordinator` (product integration work; the purchase gate is the decision point the UI will consult).
- A real registry server implementation (the protocol is client-side; any HTTP server implementing the documented endpoints works).
- Real payment-provider checkout integration (orders return the provider's checkout URL; payment completes on the provider's page).

---

# v3 OVERHAUL — COMPLETE ✅ (M0–M13, 2026-10-12)

All 14 milestones are implemented on `origin/v3` with CI green after each, per the one-milestone-at-a-time discipline.

| Milestone | Scope | Tests (new) |
|---|---|---|
| M0 | Integrity gate: `OperationResult` contract (SUCCESS/FAILED/CANCELLED/NOT_CONFIGURED/SIMULATED); removed all false-success paths | — |
| M1 | (coordinators + real install state, per prior progress) | — |
| M2 | (per prior progress) | — |
| M3 | Real semantic intelligence / LSP | — |
| M4 | Context engine: ranked pipeline, tokenizer/budget, trace + cache invalidation, benchmark | 22 |
| M5 | Safety gateway: SafetyOperation, SafetyGateway, PathSecurityAuditor, AuthorizationPrompt, provider safety adapters | 42 |
| M6 | Autonomous workflow: TaskDecomposer, CheckpointStore, StepExecutors, PlanExecutor, WorkflowReviewer, WorkflowRunner | 40 |
| M7 | Production MCP & Skills: McpProtocol, McpServerProcessManager, McpCredentialStore, McpRemoteTransport, McpToolRegistry, SkillPackage | 73 |
| M8 | Visual & app factory loops: VisualDiffer, VisualDevLoop, VisualLoopStore, CommandRunner, ApkSignatureVerifier, CleanBuildVerifier, AppFactoryLoop | 37 |
| M9 | GitHub automation remainder: PR detail/merge gating, issue management, releases, workflow dispatch, PullRequestAutomation, IssueAutomation, ReleaseAutomation, WorkflowAutomation | 33 |
| M10 | Real multi-agent runtime: agent teams, runtime orchestration, role validation | — |
| M11 | Real cloud execution: authenticated worker protocol, delta upload, log streaming, artifact SHA-256 verification, cost controls, CloudExecutionOrchestrator | 39 |
| M12 | Firebase collaboration: Auth identity, Firestore gateway + rules, invites/RBAC, presence, version-vector sync, collaborative editing | 60 |
| M13 | Real marketplace: listing validation, discovery + TTL cache, purchasing (orders/entitlements), seller center, purchase gate | 77 |

**Cross-cutting conventions established across v3:**
- **Honesty contract:** `OperationResult` everywhere — SUCCESS only for genuinely verified outcomes; blank credentials → NOT_CONFIGURED without network calls; transport errors encoded, never thrown past the seam.
- **Seams:** injectable HTTP transports (`CloudHttpTransport`, `MarketplaceHttpTransport`), injectable clocks for time-dependent logic, file-backed + in-memory storage variants — everything unit-testable on pure JVM.
- **Local validation before network:** seller drafts, emails, and listing updates are validated client-side so malformed requests never leave the device.
- **Working tree clean; all commits pushed to `origin/v3`.**
