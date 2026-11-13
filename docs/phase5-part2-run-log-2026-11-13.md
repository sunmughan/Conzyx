# Conzyx Phase 5 run log (part 2) — 2026-11-13

## Authority
- Architecture doc: `docs/AGENTIC_MOBILE_IDE_ARCHITECTURE.md` — **Phase 5 = Mobile-first
  capabilities** (background agent jobs, resumable sessions, intelligent
  Android notifications, voice commands, offline-first project/editor state,
  mobile command center).
- Branch: v3, HEAD before this run: e4ed6f63 (part 1: Command tab, CI
  43289547757 SUCCESS, 1388 tests).
- NO version bump (single bump only after all 5 phases complete).
- Part 1 log: `docs/phase5-run-log-2026-11-13.md`.

## Survey corrections (found while implementing, not in the part-1 survey)
- Voice DICTATION already existed end-to-end (mic button in the ChatTab
  composer → `RecognizerIntent` → appends to the prompt). What was missing
  was voice COMMANDS: a spoken "stop" was dictated as prompt text.
  Slice 3 adds command interpretation on top of the existing flow.
- Resumable sessions and queued prompts already exist (see part-1 log);
  nothing to build there.
- `NotificationCoordinator` was basic only: one result id (42, overwrites),
  no grouping, no quiet hours, no approval action. `McpPermissionManager`
  one-time approvals were IN-MEMORY ONLY.
- No WorkManager dependency; no voice-command parsing; the editor session
  persist is debounced 1,500 ms with no flush on backgrounding.
- Test deps are JUnit 4.13.2 + org.json ONLY — no mockk / mockito /
  Robolectric. Anything needing a Context (receiver, worker run, observer
  registration) is thin delegation over a pure, tested function.

## Slice 1: intelligent Android notifications (8afaafa2)
- `runtime/NotificationPolicy.kt` (new, pure): quiet hours 10pm–7am
  (`isQuietHour`, overnight wrap, start == end means off) and
  `deliveryFor`: failures always ALERT, anything else SILENT at night.
- `runtime/NotificationCoordinator.kt`: task results and remote events
  grouped (`conzyx-task-results` / `conzyx-remote-events`) with a silent
  group-summary notification; `postResult` / `postRemote` quiet-aware.
- `postApprovalRequired`: Approve / Deny actions via `McpApprovalReceiver`
  (new, manifest-registered, exported=false). Approve grants a durable
  one-time approval; Deny dismisses only. Approvals always alert, even
  during quiet hours, because the run is waiting on them.
- `mcp/McpPermissionManager.kt`: one-time approvals now persisted in
  `mcp_permissions.json` (grant/consume persist, loaded on restart,
  cleared by `clearAll`).
- `ui/MainViewModel.kt`: an MCP approval-needed call now posts the
  approval notification instead of only returning an error string.
- Settings: new Notifications accordion with a quiet-hours toggle
  (`AppPreferences.notificationQuietHoursEnabled`, default on).
- Tests: `NotificationPolicyTest` (5), one-time-approval restart test (1).

### Slice 1 fix (33db1027) — CI caught a real bug
- First CI run 43376322876: **FAILURE, 1393/1394** (compile passed, all
  other tests passed). Only `one-time approval survives manager restart`
  failed (`assertTrue(granted.allowed)`, test line 110).
- Root cause: `oneTimeApprovals` was declared AFTER the
  `init { loadPersisted() }` block, so the field was still null when load
  ran. The NPE was swallowed by `loadPersisted`'s `runCatching`, so
  approvals never survived a restart — while policies (declared before
  init) loaded fine, which is why only that one test failed.
- Fix: moved the declaration above `init`, with a comment explaining why
  the order matters. No test change needed; the failing test is the
  regression test.

## Slice 2: background agent jobs via WorkManager (a4386931)
- `runtime/SessionWatchdogWorker.kt` (new, `CoroutineWorker`, 15-min
  periodic, the minimum WorkManager interval): marks sessions killed
  with the process as INTERRUPTED via the existing
  `detectInterruptedSessions()`, counts queued prompts, and posts a
  resume reminder when anything waits. It NEVER starts a run itself;
  resuming stays a foreground, user-confirmed action. A failed cycle
  is logged and skipped; the next 15-minute tick tries again instead
  of retrying in a loop.
- `runtime/BackgroundJobScheduler.kt` (new): `enqueueAll()` with
  `ExistingPeriodicWorkPolicy.KEEP`, called from
  `MobileHarnessApplication.onCreate`.
- `NotificationCoordinator.postResumeReminder` (id 45, its own slot so
  it never overwrites the latest task result; quiet-hours silent).
- `countQueuedPrompts` reads the JSONArray lengths, not the files: a
  drained queue leaves an empty `[]` file behind, and `.tmp` files are
  mid-write staging.
- Tests: `SessionWatchdogWorkerTest` (4).

## Slice 3: voice commands (d99e92a3)
- `voice/VoiceCommandParser.kt` (new, pure): Stop / Resume /
  OpenCommandCenter / ShowStatus / Dictate. Normalized exact or
  leading-phrase match, so dictation can never fire a command
  mid-sentence.
- `ui/PocketDevApp.kt`: the ChatTab speech-result handler dispatches
  commands instead of always dictating. Stop calls the existing
  `onStop`; Resume reuses the command center's resume action;
  OpenCommandCenter selects the Command tab; ShowStatus toasts the run
  state. Dictation is unchanged for anything else.
- Tests: `VoiceCommandParserTest` (5).

## Slice 4: offline-first editor state polish (629ed5f2)
- `ui/coordinators/EditorCoordinator.kt`: `flushSessionPersist()`
  cancels the pending 1.5 s debounced write and persists the current
  session immediately. A `ProcessLifecycleOwner` onStop observer calls
  it when the app backgrounds, so a background process kill can no
  longer lose up to 1.5 s of editor state (tabs, dirty buffers,
  cursor/scroll). The observer is removed in `onCleared`
  (`MainViewModel.onCleared`), following the existing coordinator
  cleanup pattern.
- Tests: `EditorSessionStoreTest` (3) covering the round-trip the flush
  relies on (dirty buffer, unknown/clear, corrupt file).

## Files changed
- `app/src/main/AndroidManifest.xml` (McpApprovalReceiver)
- `app/src/main/java/com/codeair/mhe/data/AppPreferences.kt`
  (notificationQuietHoursEnabled)
- `app/src/main/java/com/codeair/mhe/mcp/McpApprovalReceiver.kt` (new)
- `app/src/main/java/com/codeair/mhe/mcp/McpPermissionManager.kt`
  (durable one-time approvals + init-order fix)
- `app/src/main/java/com/codeair/mhe/runtime/NotificationCoordinator.kt`
  (grouping, quiet hours, approval + resume-reminder posts)
- `app/src/main/java/com/codeair/mhe/runtime/NotificationPolicy.kt` (new)
- `app/src/main/java/com/codeair/mhe/runtime/SessionWatchdogWorker.kt` (new)
- `app/src/main/java/com/codeair/mhe/runtime/BackgroundJobScheduler.kt` (new)
- `app/src/main/java/com/codeair/mhe/voice/VoiceCommandParser.kt` (new)
- `app/src/main/java/com/codeair/mhe/MobileHarnessApplication.kt`
  (enqueue watchdog)
- `app/src/main/java/com/codeair/mhe/ui/MainViewModel.kt`
  (approval notification + editor onCleared)
- `app/src/main/java/com/codeair/mhe/ui/SettingsScreenModern.kt`
  (Notifications accordion)
- `app/src/main/java/com/codeair/mhe/ui/PocketDevApp.kt`
  (voice-command dispatch)
- `app/src/main/java/com/codeair/mhe/ui/coordinators/EditorCoordinator.kt`
  (flush + background observer)
- `app/build.gradle.kts` (work-runtime-ktx:2.10.0)
- Tests: `NotificationPolicyTest`, `McpPermissionManagerTest` (+1),
  `SessionWatchdogWorkerTest`, `VoiceCommandParserTest`,
  `EditorSessionStoreTest`.

## Tests
- 18 new tests: 6 slice 1, 4 slice 2, 5 slice 3, 3 slice 4.
- Total: 1388 → **1406 @Test annotations**, all passing on CI
  (verify job green; the retrievable log text shows BUILD SUCCESSFUL,
  the per-test count line is not in the fetched log excerpt).
- Full suite runs on CI (local Gradle is dead in the sandbox; no
  kotlinc or Android SDK available locally to compile the changed
  files — see "Not checked").

## Ub gates
- crapper, dryer, mutator run from repo root on the final tree. As in
  Phases 3–4 and part 1, they do not analyse `.kt` files (only the
  repo's Python runtime-bundle scripts); dryer duplicate notes and
  mutator sites are pre-existing script findings, untouched by this
  change. Crapper `.metrics/` output not committed.

## CI
- Run 43376322876 (slice 1 only): FAILURE — 1393/1394, the restart
  test above. Fixed by 33db1027.
- Run **43378515650** (all four slices + fix): **SUCCESS** — verify
  8m26s, release 21m25s, 2026-11-13 06:24:44Z–06:46:11Z. No version
  bump; v3.5.18 in place.

## Not checked / known limits
- No local compile (see Tests). CI is the gate.
- No on-device phone check — the user's job, on his phone.
- `McpApprovalReceiver` has no unit test (needs a Context; thin
  delegation to the tested manager).
- Voice commands are prefix-matched: "stop the music" would stop the
  task. There is no media playback in the app, so the ambiguity is
  theoretical; noted, not fixed.
- Flush race (theoretical): if backgrounding lands in the exact
  moment a debounced write is mid-flight, the stale write could land
  after the flush write. The window is one file write; consequence is
  bounded to a 1.5 s-stale session, same as today's behavior.
- A foreground crash can still lose up to the 1.5 s debounce window;
  only backgrounding is now flush-safe.
- Quiet-hours window is fixed 10pm–7am; no UI to change the hours
  yet (on/off toggle only).
- The watchdog reminds; it never auto-resumes. WorkManager minimum
  interval is 15 minutes.

## What remains open
- Phase 5 implementation is now complete against the architecture doc:
  mobile command center (part 1), intelligent notifications, background
  agent jobs, voice commands, offline-first editor polish — plus the
  pre-existing resumable sessions and queued prompts.
- Still owed from the user: on-device verification on his phone, and
  the demo video. The single version bump after all 5 phases is his
  call (not done here, per the standing rule).
- Pre-existing, out of scope: Play draft blockers (install-packages +
  foreground-service declarations).
