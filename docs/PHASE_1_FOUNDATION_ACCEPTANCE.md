# Phase 1 Foundation Hardening — Acceptance Ledger

Branch: `phase-1-foundation-hardening`

## Rules
- Keep all Phase 1 changes on this branch; do not merge or push changes to `main`.
- A feature is not accepted solely because code exists. Acceptance requires tests/build evidence.
- Do not run the release APK workflow during this phase; the Phase 1 workflow is intentionally limited to unit tests and debug compilation.
- Firebase Google provider is already enabled per the project owner. The remaining Android signing SHA-1 registration will be supplied by the owner; runtime Google sign-in is not considered verified until a signed build is exercised on-device.

## Phase 1 workstreams
- [ ] Reproducible CI for this branch without release signing secrets.
- [ ] Crash-safe file writes and recoverable file reads.
- [ ] Global search and replace correctness beyond the capped preview list.
- [ ] Authentication configuration diagnostics without hard-coded OAuth client fallback.
- [ ] Automated online/offline unit tests and debug compilation.
- [ ] Review test reports and fix every failure before declaring completion.
- [ ] Final branch diff review; confirm `main` is unchanged.

## Evidence log
- Initial audit: the existing Android QA workflow's push branches do not include this phase branch, and its job compiles release APKs using release signing secrets. A dedicated debug-only verification workflow is being added for Phase 1.
- Baseline bug found: `GlobalSearchEngine.replaceAll` derived its target files from the UI search result, which is capped at 500 matches. Files occurring after the cap could be skipped silently.
- Baseline hardening issue found: `SafeFileOps` deleted an existing target before fallback rename/copy. A failed fallback could destroy the last good copy.
- Google sign-in: code now fails clearly if the generated `default_web_client_id` is absent and logs the exception type/message. Firebase provider enablement is owner-confirmed; signing SHA-1 and actual device flow remain external verification items.
- No tests/builds are marked passed until a GitHub Actions run reports success.
