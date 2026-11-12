# Conzyx Phase 3 run log — 2026-11-12

Authority: `docs/AGENTIC_MOBILE_IDE_ARCHITECTURE.md`, "### Phase 3 — Agentic development".
Repo: `sunmughan/Conzyx`, branch `v3`, base `c3d0bb4c`. No version bump (user order: one bump after all 5 phases).

## What was already present (audit, verified by grep/read)

- **Build/test execution — LIVE.** `AppBuildOrchestrator.runBuild` (guest Gradle via
  `GuestBuildSession`, called from `AppFactoryCoordinator`), `MainViewModel.runRealGradleBuildAndTest`
  (real `gradle assembleDebug testDebugUnitTest` in proot, wired as the
  `buildAndTestValidator` into `GitHubWorkflowEngine`). `AppFactoryLoop` is dead code
  (zero callers) — left untouched, not wired.
- **Structured test results — MISSING.** No JUnit XML parsing anywhere; the live path kept
  only a raw log tail (10 head + 40 tail lines).
- **Failure diagnosis — code existed, unused in the live path.** `SelfHealingLoop.diagnose`
  (8 error types, file:line extraction, confidence) was called only from
  `AgentOrchestrator.executeVerificationLoop`, which has zero callers. The live repair
  path built its fix prompt from the raw log tail.
- **Bounded self-repair loop — LIVE.** `GitHubWorkflowEngine`: build → fail → ONE bounded
  repair attempt → rebuild → fail workflow. `PlanExecutor` (maxAttempts) and
  `AgentOrchestrator.executeVerificationLoop` (max 3) exist but have zero callers.
  No change needed to the bound.
- **Debugging / deep links — MISSING end-to-end.** `classifyChatLink` stripped the `#L`
  fragment; `PocketDevApp` discarded the line (`onNavigateToFileLine = { path, _ -> ... }`);
  `EditorCoordinator.openFileByPath` had no line param. Plumbing existed:
  `CodeEditorScreen.goToLine(line)` and `EditorViewState.scrollLine` (never populated).
- **Agent activity timeline — DATA live, UI banner-only.** `OrchestratorSnapshot.history`
  (`EngineeringStepEvent`: state, stepTitle, details, iteration, timestamp) is populated
  during real tasks; the UI showed only `EngineeringStateBanner`. Note:
  `bannerSnapshotFor` returns a SYNTHETIC snapshot with `history = emptyList()` for
  finished tasks, so the dialog must read the live `orchestratorSnapshot.history`.

## What was built (this run)

- **A. Structured test results** — new `factory/build/TestResultParser.kt`: parses Gradle
  JUnit XML (`build/test-results/testDebugUnitTest/TEST-*.xml`) into
  `StructuredTestResults` (total/passed/failed/skipped/errors/durationMs/failures with
  class + test name + message). Returns `null` when no reports exist or all are
  malformed — never invents data. `summaryLine()` e.g. `Tests: 14 passed, 2 failed,
  0 skipped (12.4s)`. Wired into `MainViewModel.runRealGradleBuildAndTest`: success
  appends the summary to `verificationEvidence`; failure appends the summary + failing
  test names to the logs. New `TestResultParserTest` (5 tests: counts + failing test,
  multi-file aggregate, missing dir → null, no reports → null, malformed skipped).
- **B. Failure diagnosis surfaced** — `GitHubWorkflowEngine` fix prompt now starts with a
  diagnosis block from `SelfHealingLoop().diagnose(logs)` (type + confidence, root cause,
  suggested fix, affected files with line numbers), followed by the real log tail. The
  loop stays bounded at one repair attempt. `runRealGradleBuildAndTest` failure error
  also carries a one-line diagnosis. No session→git import cycle (`session` imports no
  `git.workflow`). New `repairPromptCarriesFailureDiagnosis` test in
  `GitHubWorkflowEngineTest` (asserts `Diagnosis: COMPILATION_ERROR`, root cause,
  suggested fix, affected file in the repair prompt).
- **C. Deep links from stack traces** — `classifyChatLink` parses `#L12` / `#L12-L34`
  into `ChatLinkTarget.WorkspaceFile.line` (new defaulted param; existing
  `ChatLinkRoutingTest` assertions still pass unchanged). `onOpenFileLink` /
  `onOpenFileByPath` are now `(String, Int?) -> Unit` through `PocketDevApp`
  (chat tap + `onNavigateToFileLine` now pass the line through) →
  `MainViewModel.openFileByPath(path, line)` → `EditorCoordinator.openFileByPath`,
  which seeds the buffer's `EditorViewState.scrollLine` (line − 1) before either the
  dirty-buffer or fresh-load branch, so the async disk load preserves it via
  `syncFromDisk`. `CodeEditorScreen` jumps once via `goToLine(scrollLine + 1)` in a
  `LaunchedEffect` keyed on `(filePath, value.text, targetScrollLine)` — waits for
  async content, re-jumps on a second tap with a different line, never re-jumps on
  edits. New `lineFragmentParsesToDeepLinkTarget` test (range start, bare path,
  non-line fragment → null).
- **D. Agent activity timeline** — `EngineeringStateBanner` gains optional `onShowTimeline`
  (banner becomes tappable + shows a History icon only when history is non-empty).
  New `AgentActivityTimelineDialog` in `RoadmapComponents.kt` lists the history
  (time HH:mm:ss, state, step title, details, iteration). `PocketDevApp` banner site
  passes `orchestratorSnapshot.history` (live, full), falling back to
  `bannerSnapshot.history`. No Compose UI test (unit suite only) — see Not checked.

## Tests

- **Local Gradle suite: COULD NOT RUN.** Every Gradle invocation on this VM fails with
  `Could not receive a message from the daemon`, including bare `gradle help` (Gradle
  8.14 and 9.5.0, daemon and `--no-daemon`, heap 1–1.5 GB). Root cause, established by
  `--debug` + daemon logs + `ss`: the daemon starts and listens
  (`[::ffff:127.0.0.1]:port`), the client connects and dispatches, but the daemon never
  sees the connection — localhost daemon sockets are black-holed in this sandbox
  (a plain Python loopback TCP test works, so it is Gradle-daemon-specific). Earlier
  confusion (GRADLE_USER_HOME root-vs-hatch mismatch) was fixed and is not the cause.
  Also: the Gradle dependency cache is thin (9 groups, no JUnit/Compose jars), and the
  wrapper distribution is only a cached zip, so an offline local build could not resolve
  dependencies anyway. **CI is the test gate for this run.**
- **Direct kotlinc verification (substitute, passed):** compiled `TestResultParser` +
  a 17-check harness with the cached `kotlin-compiler-embeddable` 2.0.21 + stdlib —
  **17/17 PASS** (counts, aggregate, null cases, malformed). Compiled `SelfHealingLoop`
  + a 9-check harness — **9/9 PASS**, including the exact log line from the new engine
  test (`COMPILATION_ERROR`, `Broken.kt:12`). Harness sources are ephemeral
  (`/tmp/phase3-harness-main.kt`, `/tmp/phase3-diag-main.kt`); the real unit tests
  live in the repo and run in CI.
- **Ub-mode gates: do not apply.** `crapper` and `dryer` report "No source files to
  analyze" for `.kt` files (they cover Clojure/Java/Go/TypeScript/Rust/Python, not
  Kotlin) — same situation as the PHP groups. `mutator` not run for the same reason.

## CI

- Commit: `dd356eae` (Phase 3) + `7235f147` (forward-reference fix, below) on `v3`.
- CI run 43166358625 (dd356eae): FAILED in verify — compile error, see below.
- CI run 43166561077 (7235f147): release job ✓ (20m9s), verify job ✗ (7m41s):
  `testOnlineDebugUnitTest`, 1371 tests, 4 failed — all 4 were the new tests
  (TestResultParserTest x3, ChatLinkRoutingTest x1). Everything else passed,
  including the new engine-diagnosis test.
- **Fix commit 1 — forward reference (caught locally before CI finished):**
  the deep-link jump effect called local `goToLine` before its declaration;
  Kotlin local functions are NOT visible before declaration (verified with the
  compiler: `unresolved reference`). The effect was moved to just after
  `goToLine`. `7235f147`.
- **Fix commit 2 — deep-link regex (real CI bug):** `#L12-L34` ranges carry the
  `L` on BOTH ends, but the regex expected `-34` (`(?:-\d+)?`), so the range
  never matched and the line came back null. Fixed to `(?:-L?\d+)?`, verified
  by a 7-check harness (L12, L12-L34, L12-34, L42, non-line → null, lowercase).
- **Fix commit 3 — JAXP-free TestResultParser (real CI bug):** `parseReport`
  returned null in CI for reports that parse fine locally on JDK 17 and 21,
  with and without `android.jar` on the classpath — the `DocumentBuilderFactory`
  provider lookup is environment-dependent and fails on the CI unit-test
  runtime. Rewrote the parser as a small deterministic reader (attribute
  regexes + XML entity unescape, no JAXP): same public API and tests, no
  provider variance, no XXE surface at all. A `</testsuite>` presence check
  keeps the malformed-file → null behavior. Verified by a 24-check harness
  on JDK 17 and 21 (counts, aggregate, null cases, malformed, self-closing
  testcase guard so a failure is never attributed to the wrong test).
- CI run for the fix commits: 43169566903 — first attempt failed in the SETUP step
  ("Fetch latest verified runtime bundles", `URLError: [Errno 104] Connection
  reset by peer`, a transient runner network flake; no build/test ran, nothing
  to do with this change). Re-ran the failed verify job (`gh run rerun`):
  **GREEN — verify ✓ 7m13s, release ✓ 14m36s.** `testOnlineDebugUnitTest`
  BUILD SUCCESSFUL; 1371/1371 tests pass (same 1371-test set as the red run,
  failures 4 → 0). Final commit: `b4171f0b`.

## Not checked / left open

- Full unit suite (49 pre-existing + 7 new = 56 tests) NOT run locally — blocked by the
  broken local Gradle daemon (above). CI is the gate; if CI is red, fix and re-push.
- No on-device check of the deep-link jump or the timeline dialog (Compose UI, no UI
  test written); needs the user's phone check like P1/P2.
- `shortcut:` new files under `factory/build/` are git-ignored by the blanket `build/`
  rule in `.gitignore:17`, so they were `git add -f`'d, matching the precedent of the
  other tracked files in that directory. A `.gitignore` fix (e.g. `/build/` anchoring)
  is a separate cleanup, not done here.
- Phase 4 NOT started.
