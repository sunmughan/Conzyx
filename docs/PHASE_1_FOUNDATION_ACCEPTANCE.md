# Phase 1 — Foundation Hardening Acceptance Ledger

Repository: `sunmughan/Mobile-Harness-Extended`
Working branch: `phase-1-foundation-hardening`
Baseline: `main` at `a8a1740e397f5a5f366d7779cc979dd27c4005f5`

## Non-negotiable rules
- Keep all Phase 1 implementation commits on this branch. Do not merge or commit to `main`.
- Do not mark any test as passed unless its actual test/build result is available.
- Do not start Phase 2 until every Phase 1 acceptance gate has evidence.
- Do not trigger the release APK workflow during this phase. The Phase 1 branch CI path runs unit tests and debug compilation only.
- Google provider enablement in Firebase is owner-confirmed. The owner will register the SHA-1 fingerprint; Google sign-in still needs validation against a build signed with the corresponding certificate.

## Acceptance checklist

### A. State and architecture boundaries
- [ ] Extract domain operations from `MainViewModel` into focused coordinators/repositories/ViewModels without changing behaviour.
- [ ] Separate UI navigation from domain operations in `PocketDevApp`.
- [ ] Establish one explicit owner for each state machine and persistence boundary.
- [ ] Keep agent/session, runtime, project, editor, Git, App Factory, MCP/security and collaboration operations testable without routing all behaviour through the root ViewModel.
- [ ] Add regression tests for extracted seams and verify public call sites.

### B. Durable data and transactional writes
- [x] Remove the unsafe delete-before-rename/copy fallback in `SafeFileOps`; replace with filesystem move semantics that preserve the existing target on failure.
- [x] Make checkpoint change-manifest writes atomic in `WorkspaceCheckpoints` and the duplicate Claude runtime implementation.
- [x] Add tests for UTF-8 writes, overwrite, binary writes, missing/empty reads and stale temp cleanup.
- [ ] Test failure paths where storage is unavailable, write/rename fails, and a process is interrupted during a checkpoint write.
- [ ] Make baseline checkpoint creation transactional; never replace the last known-good baseline with a partially copied snapshot.
- [ ] Audit remaining direct writes to session metadata, checkpoint metadata, editor files and runtime manifests; use atomic writes where the data is recovery-critical.
- [ ] Verify rollback and restore do not follow symlinks or escape the selected workspace.

### C. Session/process/runtime recovery
- [ ] Verify interrupted session detection and restoration across fresh manager instances.
- [ ] Verify child process/PTY cleanup on completion, failure, cancellation and timeout.
- [ ] Verify foreground-service restart policy, notification completion and wake-lock release on every terminal path.
- [ ] Add failure-injection coverage for partial runtime install/update, corrupted rootfs, failed checkpoint write and out-of-storage.
- [ ] Validate low-memory/process-death recovery on an Android device or emulator; unit simulations alone do not satisfy this gate.
- [ ] Add a low-storage and large-project profiling budget and explicit graceful failure states.

### D. Global search/replace correctness
- [x] Fix `replaceAll` so the UI's 500-match preview cap does not silently limit the files changed by a replace operation.
- [x] Add regression tests for replacement across files beyond the preview cap and literal replacement strings containing regex metacharacters.
- [ ] Review exclusions, glob handling, large-file limits, symlink behaviour and replacement failures.
- [ ] Add undo/preview integration tests and verify file contents remain intact when writes fail.

### E. Authentication diagnostics — owner-deferred
- [x] Owner instruction: do not continue Google Sign-In work during this Phase 1 execution; do not spend Phase 1 time on SHA-1 registration or Google Sign-In device validation.
- [x] Existing branch changes to authentication are not being extended or reworked in response to this instruction.
- [ ] Deferred for a separately authorized authentication task: validate generated `default_web_client_id`, signed-APK Google Sign-In scenarios, and email/password regression.
- Scope note: these deferred authentication checks are not Phase 1 exit blockers unless the owner explicitly brings them back into scope.

### F. CI and release gates
- [x] Add the custom Phase 1 branch to the existing Android QA workflow's push trigger.
- [x] Make the Phase 1 branch skip the release/signing job and run unit tests plus online/offline debug compilation only.
- [x] Obtain successful online unit-test + debug-build results from GitHub Actions (run `38168309273`, commit `4aa75c2b32123bd3d21a66956b625a4d293ea426`).
- [x] Obtain successful offline unit-test + debug-build results from GitHub Actions (same run; both steps completed successfully).
- [ ] Review test reports and fix all failures; verify no tests are skipped unintentionally.
- [ ] Independently review the final branch diff and confirm `main` remains unchanged.
- [ ] Only after every phase is complete may the release APK workflow be triggered.

## Evidence log

1. Static audit found `SafeFileOps` deleted an existing target before fallback rename/copy. A failed fallback could lose the last good copy. This was replaced with atomic move and a non-destructive replacement fallback.
2. Static audit found `GlobalSearchEngine.replaceAll` chose target files from the capped search result list. A file occurring after the 500-match preview cap could be skipped silently. Replace now traverses eligible files independently of the UI preview cap.
3. Checkpoint manifests were written using direct `File.writeText`. These writes now use `SafeFileOps.atomicWriteText` and fail explicitly if persistence fails.
4. Regression tests were added/expanded for storage operations, global replacement beyond 500 preview matches, literal replacement strings and checkpoint persistence/path safety.
5. The Phase 1 branch path in `.github/workflows/android.yml` runs online/offline unit tests and debug builds without release signing secrets. GitHub Actions run [`38168309273`](https://github.com/sunmughan/Mobile-Harness-Extended/actions/runs/38168309273), for commit `4aa75c2b32123bd3d21a66956b625a4d293ea426`, completed with conclusion **success**; both online and offline test/build steps completed successfully. Release/publish jobs were skipped as intended.
6. Per owner instruction, Google Sign-In and authentication validation are deferred from this Phase 1 execution. Do not touch authentication implementation or perform SHA-1/device-validation work unless separately authorized.

## Exit gate
Phase 1 is complete only when all checklist items are either verified complete with evidence or explicitly agreed as out of scope by the project owner, all required CI/device tests pass, and a final diff review confirms no regressions. Code presence, a successful commit, or a workflow file alone is not proof of completion.
