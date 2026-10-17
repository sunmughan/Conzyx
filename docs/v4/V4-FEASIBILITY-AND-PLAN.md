# Conzyx V4 — Feasibility Study & Implementation Plan

**Branch:** `v4` (from `v3` @ d06038b, v3.4.0)
**Date:** 2026-10-17
**Source roadmap:** `Conzyx_V4_Final_Implementation_and_Upgrade_Roadmap_23_2qs4.md`
**Study type:** Code audit + feasibility analysis. **No implementation code written.**

---

## 1. Verdict

**V4 is POSSIBLE in this app. Nothing in the roadmap is technically impossible on the current codebase.**

Breakdown:

| Roadmap area | Verdict | Confidence |
|---|---|---|
| Android App Operator (observe/act/verify loop) | POSSIBLE — new AccessibilityService + typed actions on existing SafetyGateway | High |
| Installed-app discovery & target resolution | POSSIBLE — QUERY_ALL_PACKAGES or scoped queries | High |
| Screenshot/OCR visual fallback | POSSIBLE — MediaProjection + OCR provider | Medium (consent UX + provider choice) |
| Hierarchical persistent memory (5 levels) | POSSIBLE — requires introducing Room; JSON-file patterns cannot meet the contract | High |
| Direct-control-first batching, no-progress guard, effect journal | POSSIBLE — new code on existing PlanExecutor/VisualDiffer | High |
| Approval binding, takeover, risk tiers | POSSIBLE — extend SafetyGateway/AuthorizationPrompt | High |
| Operator UI (entry, preflight, timeline, approvals, history) | POSSIBLE — new `operator/ui/` package, existing patterns reusable | High |
| Rakazo pattern adaptations | POSSIBLE — behavior ports, no code copying | High |

**The single hardest dependency:** the user must manually enable the AccessibilityService in Android Settings. The app cannot do this programmatically. If the user never enables it, the entire operator is non-functional. This is an Android platform constraint, not a code problem, and the roadmap already accounts for it (§7, M1).

---

## 2. What already exists (the 60%)

Audited 16 foundation files + full manifest + all persistence + UI layer. These are real, working, honestly-built components:

**Authorization & safety (reuse as-is):**
- `security/SafetyGateway.kt` — single choke point, ALLOW/PROMPT/DENY, kill switch. This IS V4's action-authorization foundation.
- `security/PermissionPolicyEngine.kt` — 15 categories, grant levels, emergency kill switch. Extend with `UI_INTERACT`, `SCREEN_CAPTURE`, `INPUT_INJECTION`.
- `security/SafetyOperation.kt` — sealed operation vocabulary. **This is the natural home for typed operator actions** (add Tap/Type/Scroll/etc. subtypes).
- `security/AuthorizationPrompt.kt` — approval prompt model with zero UI references. V4's approval sheet is essentially its missing renderer.

**Execution kernel (reuse as-is, extend):**
- `workflow/PlanExecutor.kt` — dependency-ordered execution, bounded repair, resume, safety-DENY never repaired.
- `workflow/StepExecutors.kt` — every side effect through the safety gateway; SIMULATED results refused. Add operator executors here.
- `workflow/TaskDecomposer.kt` — honest template-driven decomposition, refuses unknown goals. Add `OPERATE_APP` goal kind.
- `workflow/CheckpointStore.kt` — per-run persisted state, rollback. Needs effect-journal sibling.
- `agent/team/runtime/ConcurrentTeamDispatcher.kt` — concurrent DAG dispatch with budgets. Needs no-progress guard hook.
- `visual/VisualDiffer.kt` — pure-JVM pixel differ. Ideal no-progress-guard sensor, ready to use.
- `runtime/RuntimeExecutionService.kt` — foreground keep-alive + stop for long runs.

**UI patterns (reuse):**
- Inline `ApprovalCard` (Reject/Session/Once) — closest to V4 approval gate.
- `SettingsScreenModern` Fix→real-Settings-intent pattern — exactly what V4 preflight rows need.
- `RoadmapComponents.RoadmapStepRow` — step timeline visual pattern.
- `ModalBottomSheet` idiom (TeamStudio, GitHubStudio) — approval sheet shape.
- `BackgroundTaskSetupScreen` step-wizard — preflight screen shape.
- Material3 theme, edge-to-edge, IME insets — all established.

**Identity & conventions (reuse):**
- Stable UUID scheme for project/chat/message/session IDs — keep as V4 `*_id` format.
- `SafeFileOps.atomicWriteText` (tmp+rename) — crash-safe single-file writes.
- Keystore/EncryptedSharedPreferences secrets boundary — already correct, do not move secrets into memory tables.

---

## 3. What is missing (the 40%)

### 3a. Device-interaction capabilities (all missing, all addable)

| Capability | Today | V4 needs | Effort |
|---|---|---|---|
| AccessibilityService | No service, permission, config, or code | New service + `res/xml/accessibility_service_config.xml` + manual-enable onboarding | Medium |
| Screen capture | Only in-app WebView `draw()` | MediaProjection FGS (`mediaProjection` type) + per-session consent UX | Medium |
| Package discovery | No QUERY_ALL_PACKAGES, no PackageManager enumeration | `QUERY_ALL_PACKAGES` (GitHub-only = no Play review) or scoped `<queries>` | Low |
| Gesture dispatch | No `dispatchGesture`/`performGlobalAction` | Falls out of AccessibilityService once declared | Low (part of service) |
| OCR | None | OCR provider behind capability interface | Medium (provider choice) |

minSdk 28 supports every required API. compileSdk 36 exposes them. No SDK bump needed. Default GitHub build (targetSdk 28) faces fewer enforcement hurdles than the Play build (targetSdk 36); both flavors need separate verification. Play submission is on hold, so Play-policy declarations are currently non-blocking.

### 3b. Operator loop logic (new code)

1. **Typed operator action model** — new `SafetyOperation` subtypes + `PlanStepKind` extensions + executors.
2. **Step/effect journal** — structured action→effect records (action, target, before/after refs, observed delta). `SecurityAuditLogger` logs auth decisions, not effects.
3. **No-progress guard** — deterministic guard using `VisualDiffer` as sensor; block repeated equivalent actions after bounded streak.
4. **Approval binding** — bind approval to run+step+action+target+payload digest; invalidate on change.
5. **Takeover** — pause → user acts → resume with fresh observation. Existing "takeover" is browser-tab-scoped only.
6. **Operator plan templates** — `OPERATE_APP` goal kind in TaskDecomposer; observe→act→verify loop template.

### 3c. Persistent memory (new Room database)

Current state: NO Room, NO SQLite, NO DataStore. Everything is SharedPreferences + org.json files. Per-level verdicts:

| V4 level | Today |
|---|---|
| Global | PARTIAL (config exists, not memory records) |
| Project | PARTIAL (stable ID + dirs, no goal/decisions/progress records) |
| Conversation | PARTIAL (exact history but **capped at newest 1000**, no summaries/events) |
| Task/Execution | PARTIAL (run state + checkpoints, no Task record/effect journal) |
| AppKnowledge | **MISSING** (zero) |

The §21 relational contract (unique `(conversation_id, sequence)`, indexes, foreign keys, transactional writes) **cannot be met by the JSON-file patterns**. V4 must introduce Room. Keep SharedPreferences for flags, Keystore for secrets, filesDir for workspace files. V4.0 must include one-time JSON→Room import.

### 3d. Operator UI (new package)

Greenfield: live execution timeline, run history, evidence-based result screen, 5-state capability view, pause/resume/takeover. Reuse: approval card idiom, preflight Fix pattern, step-row rendering, bottom sheets. **New `operator/ui/` package with dedicated `OperatorViewModel`** — do NOT extend the 5,567-line MainViewModel or 188-field AppUiState. Add `OPERATOR` to `OverlayScreen` enum.

### 3e. Test infrastructure

172 unit test files, **zero instrumentation tests** (`androidTest/` does not exist). V4 §9.7 fault-injection scenarios (stale targets, approval replay, process death at dispatch boundaries, takeover races) require new instrumentation infra. CI currently runs only JVM unit tests.

---

## 4. Risk assessment

| Risk | Severity | Mitigation |
|---|---|---|
| User never enables AccessibilityService → operator dead | **HIGH** | Onboarding that explains value before deep-linking to Settings; preflight screen shows honest NEEDS_PERMISSION; core app remains fully usable without it |
| MediaProjection per-session consent fatigue | Medium | Use accessibility semantics as default; screenshots only as fallback (roadmap already mandates this) |
| OEM battery/lifecycle killing the service mid-run | Medium | Durable run store + resume-from-fresh-observation (M7); foreground service with user-visible notification |
| Play policy if submission resumes (Accessibility, QUERY_ALL_PACKAGES) | Low (Play on hold) | Declarations + review; GitHub distribution unaffected |
| Scope creep: 11 milestones (M0-M10) is 3-6 months | Medium | Gate strictly per roadmap §11; ship V4.x incrementally; never advertise unbuilt capabilities |
| MainViewModel/PocketDevApp god objects absorbing operator code | Medium | Hard rule: operator code lives in `operator/` package only, dedicated ViewModel |
| No-progress guard false positives on slow apps | Low | Tune thresholds with test evidence (roadmap suggests 3-streak default) |

---

## 5. Recommended implementation sequence

Maps to roadmap §22 (V4.0–V4.7). Each phase = shippable increment with its acceptance gate.

**Phase 1 — V4.0 Baseline + V4.1 Memory foundation**
- M0 audit (this document is the audit output).
- Introduce Room; define entities: Project, Conversation, ConversationEvent, MemoryRecord, Task, ExecutionRun, ActionEffect, AppKnowledgeVersion, ScreenNode, FeatureCapability, ActionRecipe, InstalledApp, ObservationRef.
- One-time JSON→Room import; remove 1000-message cap (append-only events).
- Migrations + schema versioning from day one.

**Phase 2 — V4.2 App registry + V4.3 Direct-control executor**
- AccessibilityService + config + onboarding deep-link.
- Typed `SafetyOperation` operator subtypes; `OperatorTaskExecutor` through SafetyGateway.
- Observation coordinator (node tree → normalized UI state), freshness/generation tracking.
- Package discovery (QUERY_ALL_PACKAGES) + TargetAppResolver.
- Preflight screen reusing Settings Fix pattern.

**Phase 3 — M3/M4 Actions + verification**
- Bounded tap/long-press/scroll/type/back/launch/wait executors.
- Postcondition verifier using VisualDiffer + semantic checks.
- No-progress guard; stale-target rejection.

**Phase 4 — M5 Visual fallback**
- MediaProjection FGS + consent lifecycle; OCR provider interface.

**Phase 5 — M6/M7 Planning + durability**
- `OPERATE_APP` decomposer template; workflow integration.
- ActionEffect journal; unknown-outcome reconciliation; resume.

**Phase 6 — M8/M9 Approval + UI**
- `OperatorApprovalSheet` rendering AuthorizationPrompt + binding.
- Takeover coordinator + lease; operator entry/timeline/history/capability screens.

**Phase 7 — M10 Hardening**
- `androidTest/` infra; fault-injection suite; OEM/API-level matrix; capability truth table; release gates.

---

## 6. Effort estimate

| Phase | Scope | Rough size |
|---|---|---|
| 1 — Memory (Room + import) | 13 entities, DAOs, migrations, import | Large |
| 2 — Registry + executor core | A11y service, observation, typed actions, resolver | Large |
| 3 — Actions + verification | Executors, verifier, guards | Medium |
| 4 — Visual fallback | MediaProjection, OCR | Medium |
| 5 — Planning + durability | Decomposer, journal, resume | Medium |
| 6 — Approval + UI | Sheets, takeover, 6 screens | Large |
| 7 — Hardening | Instrumentation, matrix, gates | Medium |

Total: **~4-6 months** for the full M0–M10 roadmap at sustainable pace, assuming one developer. Phases 1–3 alone (memory + observe/act/verify on accessibility semantics) are a meaningful V4.0–V4.3 shippable slice.

---

## 7. Go / no-go per roadmap milestone

| Milestone | Go? | Note |
|---|---|---|
| M0 Audit | **GO** — this document | Done |
| M1 Preflight + discovery | **GO** | Straightforward Android work |
| M2 A11y observation | **GO** | Core risk is user enablement, not code |
| M3 Safe actions | **GO** | Builds on SafetyGateway |
| M4 Verification/recovery | **GO** | VisualDiffer ready |
| M5 Screenshot/OCR | **GO with caveat** | OCR provider selection needed |
| M6 NL planning | **GO** | TaskDecomposer pattern fits |
| M7 Durability | **GO** | Requires Phase 1 Room work first |
| M8 Approval gates | **GO** | AuthorizationPrompt renderer |
| M9 UI | **GO** | New package, don't touch god objects |
| M10 Hardening | **GO** | Needs new androidTest infra |

**No milestone is a no-go.** The roadmap is implementable end-to-end on this codebase.

---

## 8. Source audits (detail)

- `V4-AUDIT-platform.md` — manifest, permissions, services, SDK, per-capability verdicts
- `V4-AUDIT-foundations.md` — agent/workflow/security/runtime/visual file-by-file
- `V4-AUDIT-persistence.md` — every persistence mechanism, per-memory-level verdicts
- `V4-AUDIT-ui.md` — UI stack, screens, ViewModels, reusable patterns, CI/tests

---

*Study complete. No implementation code written. Awaiting build order.*
