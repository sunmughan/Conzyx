# Conzyx Phase 4 run log — 2026-11-12

Authority: `docs/AGENTIC_MOBILE_IDE_ARCHITECTURE.md`, "### Phase 4 — AI ecosystem".
Repo: `sunmughan/Conzyx`, branch `v3`, base `acbe0cf9` (Phase 3 done, CI green, 1371/1371).
No version bump (user order: one bump after all 5 phases).

Note on phase count: the architecture doc defines SIX phases; the user's
standing order speaks of 5 phases (P1, P2, Phase 3, Phase 4, Phase 5). This
run implements Phase 4 exactly as the architecture doc defines it ("AI
ecosystem"). Whether the user's "Phase 5" is the doc's Phase 5
(Mobile-first capabilities) or Phase 6 (Advanced IDE) is unresolved and
needs his word; it does not change this run's scope.

## Audit (verified by grep/read before building)

- **Portable Skills — LIVE except export.** `SkillManager` (1088 lines):
  install from GitHub/URI/folder/zip, updates, enable/disable,
  `syncToWorkspace`, `buildPromptContext`, portable-structure
  validate/ensure. `SkillsCoordinator` wires it to `SkillsScreen`.
  MISSING: no way to export an installed skill as a portable package.
- **MCP tool registry — registry LIVE, agent use MISSING.**
  `McpToolRegistry` (discover/callTool with permission gate + audit),
  `McpPermissionManager`, `McpAuditLogger`, `McpCoordinator` (UI-driven
  execution) are live. But no runtime path calls `registry.callTool`:
  only `McpCoordinator.executeMcpTool` (manual, from the MCP screen).
  Codex's `mcp_tool_call` event is parsing of the Codex CLI's own MCP,
  not our registry. `ProviderSafetyAdapters.callTool` has zero callers.
  Agents cannot call MCP tools during a task.
- **Provider/model routing — PARTIAL, failover dead.** Per-kind provider
  profiles (`AppPreferences.loadProvider`), per-kind bridge factory,
  Antigravity Google-account failover (`MainViewModel`, auth/quota), team
  `providerFor` per kind, `ProjectBridgePool` per project: all live.
  DEAD: `CustomProviderStore` (zero production callers) and
  `ApiKeyFailoverCoordinator` (referenced only by the dead store). The
  vault already supports multi-key pools (`list`/`credentials`/`activate`);
  the coordinator's multi-key rotation is documented but never invoked.
- **Multi-agent orchestration — LIVE.** `AgentTeamOrchestrator` +
  `ConcurrentTeamDispatcher` + `RealTeamAgentExecutor` + `TeamStudioScreen`
  + `TeamCoordinator`, all wired, tests exist.
- **Tool permission policies — LIVE.** `McpPermissionManager` (per-server /
  per-project policies, one-time grants, always-allow) + `SafetyGateway` +
  `PermissionPolicyEngine`, wired into the registry and the bridges.
- **Secure approval UX — LIVE.** `SafetyGateway.authorize` returns PROMPT
  + `AuthorizationPrompt`; `PocketDevApp` renders `ApprovalCard`;
  `MainViewModel.answerApproval` / `answerApprovalWithLevel`;
  `FullAccessLaunchGate` surfaces launch/shell approvals in the live
  bridge path.

## What was built (this run)

Three bounded wirings; everything else in the Phase 4 scope was already
live (see audit above). No placeholders, no stubs, no version bump.

### A. MCP tools callable from the agent loop (registry LIVE, agent use was MISSING)

- NEW `runtime/McpAgentTools.kt` — the agent loop's view of the registry:
  `listTools(projectId)`, `callTool(serverId, toolName, arguments, projectId)`.
  All failures are plain error text, never thrown into a turn.
- NEW `runtime/McpToolRouter.kt` — pure routing (no Android deps, unit-testable):
  null delegate fails closed, delegate exceptions become error text, blank
  server/tool is an error, output capped at 32 KB.
- `runtime/GeminiAgentTools.kt` — two new function declarations
  (`list_mcp_tools`, `call_mcp_tool`; 5 tools → 7) and dispatch in
  `WorkspaceToolExecutor.execute()` via the router. Arguments accept a JSON
  object or a JSON string; anything else is an empty object, never a crash.
- `runtime/AntigravityRuntimeBridge.kt` — optional `mcpTools` ctor param,
  passed to the executor at the Gemini-API loop site.
- `ui/MainViewModel.kt` — `mcpAgentTools` adapter built from `mcpRegistry`
  + `mcpToolRegistry`: list discovers per enabled server, call goes through
  `McpToolRegistry.callTool` (permissions + audit stay inside the registry);
  DENY → "Error: MCP tool denied: …", PROMPT → approval-required error
  naming the MCP screen. Passed at all three Antigravity construction
  sites (field, `createRuntimeBridge`, team executor factory).
- `shortcut:` agent MCP calls that require a prompt fail with an
  approval-required message instead of an interactive card; interactive
  agent MCP approval is the upgrade path.
- BUG CAUGHT IN REVIEW: the adapter was first placed after
  `antigravityRuntime` (line ~474 reads it at field-init time); field
  initializers run in declaration order, so it would have been null and
  MCP tools silently unavailable in the main runtime. Moved above the
  runtime fields with a comment explaining why.

### B. API-key failover wired into `CustomAgentBridge` (coordinator was DEAD)

- `data/ApiKeyFailoverCoordinator.kt` — new `rotate(credentials, activate, reason)`:
  records the active key's failure, picks the next healthy key, activates
  it via the vault, returns it; null when there is nothing else to try
  (no credentials, non-key failure, single key, activation failure).
- `runtime/CustomAgentBridge.kt` — optional `rotateKey` ctor param;
  `postStreaming` rotates once on HTTP 401/403/429 (before the same-key
  retry, so a dead key is never retried) and re-issues the request with
  the new secret. `providerId` threaded through `callAnthropic` /
  `callOpenAi` / `postStreaming` from `provider.kind.name`.
- `ui/MainViewModel.kt` — `rotateApiKey(providerId, reason)` from the
  coordinator + vault, passed at all three `CustomAgentBridge` sites.
- `shortcut:` a 200 response carrying an error payload does not rotate
  (only the HTTP status is checked); that upgrade parses the error body
  for quota/auth signals before failing.

### C. Skills export (install existed, export was MISSING)

- `runtime/SkillManager.kt` — `exportSkill(name): File` zips the installed
  skill dir under relative paths (inverse of `installZip`, same 100 MB /
  50 MB-file / 10k-entry caps), returns the zip in the cache dir.
- `ui/coordinators/SkillsCoordinator.kt` — `exportSkill(name)` (IO,
  busy-guarded) hands a FileProvider share URI to state (`skillsExportUri`).
- `ui/MainViewModel.kt` — `skillsExportUri` added to `AppUiState`,
  `exportSkill(name)` + `clearSkillExport()` delegation.
- `ui/SkillsScreen.kt` — Export button per skill row; a `LaunchedEffect`
  fires the ACTION_SEND chooser once for `exportUri`, then clears it.
- `ui/PocketDevApp.kt` — wires `onExport` / `exportUri` / `onExportShared`.

## Tests

Local Gradle is dead in this sandbox (daemon socket black-holed, same as
Phase 3: `Could not receive a message from the daemon`; the wrapper also
cannot download Gradle). CI is the test gate for the full suite. What ran
locally, via kotlinc harnesses (`~/workspace/tools/kotlinc`, json 20240303,
junit, coroutines):

- **16/16 harness checks PASS** — `McpToolRouter` (list/call/null-delegate/
  delegate-failure/blank args) + `ApiKeyFailoverCoordinator.rotate`
  (next key + activation, single key, non-key failure, activation failure,
  no credentials), real production files compiled and executed.
- **20/20 real unit tests PASS** — `McpToolRouterTest` (5, new) +
  `ApiKeyFailoverCoordinatorTest` (15: 11 existing + 4 new rotate tests),
  run with JUnitCore against the real test files.
- **Bridge failover test COMPILES clean** (real `CustomAgentBridge.kt` +
  real `CustomAgentBridgeFailoverTest.kt` compiled by kotlinc, only a
  `URL(String)` deprecation warning) but **cannot execute locally**: the
  sandbox intercepts ALL TCP, including 127.0.0.1 loopback — a raw-socket
  probe got back `muse: Other TCP connections is turned off for this
  assistant...` instead of the test server. The pre-existing
  `CustomAgentBridgeStopTest` uses the identical raw-socket pattern, so
  this is a sandbox limit, not a test defect; CI executes it. The
  `HTTP -1` seen in the first local run was the sandbox interception,
  not a rotation bug.
- **Not runnable locally** (Android/Compose deps): `GeminiAgentLoopTest`
  (5→7 declarations), `SkillManagerTest` export round-trip — CI executes.
- **Ub gates: crapper exit 0, dryer exit 0, mutator exit 0.** As in Phase 3,
  they do not analyse `.kt` files (only the repo's Python runtime-bundle
  scripts); the dryer duplicate notes are pre-existing script findings,
  untouched by this change. Crapper's `.metrics/` output was deleted, not
  committed.

## CI

(TODO — filled in after push.)

## Not checked / left open

- No on-device check (needs the user's phone check like P1/P2/Phase 3).
- Interactive agent MCP approval (prompt-required MCP calls from the agent
  loop fail with an error message instead of a card — see shortcut above).
- 200-with-error-payload key rotation (see shortcut above).
- Phase-count question from the audit: is the user's "Phase 5" the doc's
  Phase 5 (Mobile-first capabilities) or Phase 6 (Advanced IDE)? Needs his
  word; does not change this run's scope.
- On-device: MCP tools in a real agent turn, key rotation against a real
  provider, skill export share sheet on the phone — all his phone check.
