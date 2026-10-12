# Mobile-Harness-Extended v3 — P0 Claim Verification (Milestone 0 baseline)

Branch `v3` == `main` at `a8a1740` (no v3 commits yet at audit time).
Method: static inspection of current sources. Line numbers below are from this snapshot.

## 1. Monolith line counts
- `app/src/main/java/com/jarves/mh/ui/MainViewModel.kt`: **6670 lines** — report says ~6671. **CONFIRMED** (off by one).
- `app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt`: **7236 lines** — report says ~7237. **CONFIRMED** (off by one).

## 2. GitHub autonomous workflow false-success paths — CONFIRMED
- `ui/MainViewModel.kt:6584` `startAutonomousIssueWorkflow`:
  - `agentExecutor = { prompt -> sendPrompt(prompt); true }` — fire-and-forget; `sendPrompt` only enqueues a chat message and returns `Unit`; the lambda returns `true` immediately without awaiting any agent completion.
  - `buildAndTestValidator = { true to "Tests compiled and passed" }` — unconditional success, runs nothing.
  - `token = ""` — empty token is passed (output-layer redaction initially obscured this; raw bytes confirm `token = "",`).
- `git/workflow/GitHubWorkflowEngine.kt:80`: default `buildAndTestValidator: suspend () -> Pair<Boolean, String> = { true to "All tests passing" }` — hardcoded success default.
- `git/workflow/GitHubWorkflowEngine.kt:73-80`: `runAutonomousWorkflow(...)` returns `Boolean`; with a blank token the push/PR stages are SKIPPED but the workflow still returns `true` / `COMPLETED` (lines ~196-215). No fail-fast on empty token.
- `git/workflow/GitHubWorkflowEngine.kt` commit stage: `stageAll` + `commitDetailed` run, but there is **no diff non-empty validation** — an empty diff is tolerated via the "nothing to commit" string check, so a no-op agent run can still "succeed".
- API errors: `githubClient.createPullRequest` exceptions are caught by the generic `catch (e: Exception)` and fail the workflow — that path is honest. But everything upstream of it can be fake.

## 3. Multi-agent simulation — CONFIRMED
- `agent/team/AgentTeamOrchestrator.kt:236-345` `runNextReadyTask()`:
  - Lines 261-263: `simulatedTokens = 2500L`, `simulatedDuration = 600L` recorded as real usage.
  - Lines 267-313: canned artifacts per role, including `TESTER` → "All 14 unit test assertions passed. 100% coverage on core feature paths." and `SECURITY_REVIEWER` → canned `ReviewVerdict.APPROVED` ("Security audit approved with zero critical findings.") submitted to the gatekeeper.
  - Line 335-340: task marked `COMPLETED` with simulated metrics.
  - No `isDemoMode` flag, no SIMULATED labeling anywhere in `agent/team/model/TeamModels.kt` (grep for `simulat`/`isSimulated`: no hits).
- Consequence: simulated security/test approvals flow into the same `gatekeeper`/`blackboard` structures a real run would use — a consumer cannot distinguish demo from real.

## 4. CloudJobRunner canned outputs — CONFIRMED
- `cloud/runner/CloudJobRunner.kt` (145 lines):
  - Lines 41-99: cloud branch prints canned logs ("BUILD SUCCESSFUL. Artifact produced: app-release-unsigned.apk"), constructs artifacts with **hardcoded** `sizeBytes` and **hardcoded** SHA-256 checksums, hardcoded `exitCode = 0`, state `COMPLETED`. No HTTP call is ever made to `worker.endpointUrl`.
  - Lines 102-133: local branch logs "Script executed with exit code 0." / "Completed." without running anything; `exitCode = 0` hardcoded.
  - No `NOT_CONFIGURED` state exists (`cloud/model/CloudHybridModels.kt:142-152` `HybridJobState` has QUEUED/ROUTING/.../COMPLETED/FAILED/CANCELLED only).
- Related: `cloud/registry/CloudWorkerRegistry.kt:75-120` ships hardcoded "default preset" workers (`https://build-cloud.pocketdev.io`, `https://gpu-cloud.pocketdev.io`, `https://eu-cloud.pocketdev.io`) marked `HEALTHY`/`isAvailable = true`. These endpoints are fictional; the runner never verifies reachability, so routing "succeeds" against nonexistent infrastructure.

## 5. LspManager.startServer fallback — CONFIRMED (with nuance)
- `lsp/LspManager.kt:46-54`: `startServer()` sets `LspServerStatus(isRunning = true, isBuiltInFallback = true)` and returns `true` without launching any process (`activeProcesses` is never populated).
- `lsp/LspManager.kt:26-35` (`init`): all 10 languages are pre-marked `isRunning = true, isBuiltInFallback = true`.
- Nuance: the data model **already** distinguishes fallback — `lsp/LspProtocolModels.kt:69-75` `LspServerStatus` has `isBuiltInFallback: Boolean = false` and `pid`. The dishonesty is in the *values* (`isRunning=true` while only the fallback is active), not in missing model fields. UI text must not present `isRunning=true + isBuiltInFallback=true` as a live language server.

## 6. Marketplace toast-only install — CONFIRMED
- `ui/PocketDevApp.kt:409-413` and `ui/PocketDevApp.kt:2535-2539`: both `MarketplaceScreen` wirings use `onInstallItem = { item -> viewModel.showToast("Marketplace: installed ${item.name}") }` and the uninstall equivalent. No installation logic runs.
- `marketplace/DeveloperMarketplaceCatalog.kt:233`: `isInstalled(itemId)` exists (backing store not yet inspected for durability — the app-level callbacks never call it).
- `ui/MarketplaceScreen.kt:82`: local `installedSet` is an in-memory `remember { mutableStateOf(setOf<String>()) }` — does not survive process death.

## 7. OperationResult-like contract — NOT FOUND
- Grep for `OperationResult` across all `*.kt`: **no hits**. No shared result contract exists. Each subsystem uses its own ad-hoc types (`GitCommandResult`, `HybridExecutionJob`, `Boolean` returns).

## 8. GitHub token handling — CONFIRMED (gap)
- `ui/MainViewModel.kt:6439-6571`: all `gitHubClient.*` calls pass `""` as the token (issues, PRs, workflow runs, releases, createIssue, mergePullRequest).
- `startAutonomousIssueWorkflow` passes `token = ""`.
- GitHub auth itself is real (`gh auth login --web --insecure-storage` via `startGitHubLogin`, `ui/MainViewModel.kt:3397`), and `runGitHubCli` (`ui/MainViewModel.kt:3600`) can execute `gh` commands — but the HTTP `GitHubClient` never consumes that credential. So the autonomous workflow's push/PR stages are silently skipped (empty token) while the workflow reports COMPLETED.

## Verdict
All P0 claims in the report's Milestone 0 verify against current `v3` code: **8/8 CONFIRMED** (LspManager with the nuance noted above). No P0 item was found already-fixed. Proceeding with Milestone 0 implementation.
