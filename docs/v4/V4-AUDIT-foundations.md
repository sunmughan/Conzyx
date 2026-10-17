# V4 Audit — Agent / Workflow / Security / Runtime / Visual Foundations

Branch: `v4` (from `v3`). Audit date: 2026-10-17. **AUDIT ONLY — no code changed.**
Scope: does the existing foundation support V4's Android App Operator needs?
V4 operator needs checked against: typed operator actions, action authorization,
step/effect journal, no-progress guard, approval binding, takeover.

Verdict scale: **REUSE-AS-IS** (fits V4 unchanged) · **EXTEND** (right shape,
needs new surface) · **MISSING** (does not exist).

---

## 1. agent/

| File | Purpose | Key APIs | V4 verdict |
|---|---|---|---|
| `agent/AgentChatController.kt` (330 lines) | Chat message history, prompt construction (`@file` mention resolution, attachment sanitization), PLAN/BUILD/UNIFIED mode instructions, streaming-delta coalescing, scratchpad derivation from roadmap + live process | `buildModeInstruction(ExecutionMode, …)`, `buildEnrichedPrompt(…)`, `resolveMentionedWorkspacePaths(…)`, `sanitizeAttachmentName(…)`, `deriveScratchpadItems(…)`, `finishWorkSegment(…)`, `ensureCompletionMessage(…)` | **EXTEND** — the `<execution_mode>` instruction-block pattern is the natural home for an `OPERATOR` mode with a typed action vocabulary; prompt/mention machinery is reusable. Chat-centric (code workspace), not device-control centric. |
| `agent/AutonomousLoopEngine.kt` (90 lines) | Build-toolchain detection (Gradle/npm/Cargo/Go/Python), compiler-error parsing, auto-fix prompt generation | `detectBuildTool(File)`, `parseCompilerErrors(String)`, `buildAutoFixPrompt(…)` | **REUSE-AS-IS** for what it is; mostly irrelevant to App Operator. The bounded retry-budget pattern (`iteration`/`maxIterations`) is a usable template for operator retry budgets. |
| `agent/team/model/TeamModels.kt` (~293 lines) | Team domain model: 10 `TeamRole`s, per-role `TeamRolePermissions` (5 toggles), `TeamRoleConfig`, `TeamTask` DAG node (with `assignedCustomAgentId`), `TeamTaskStatus` lifecycle, typed `TeamMessage`/`TeamMessageType`, `TeamArtifact`, `ReviewRecord`/`ReviewVerdict`, `ConflictDetectionResult`, `TeamBudget`, `TeamSessionState` (incl. `executionLog`) | `TeamRole`, `TeamRolePermissions.forRole()`, `TeamTask`, `TeamSessionState`, `TeamMessageType` | **EXTEND** — `TeamTask`/`TeamTaskStatus` DAG + `TeamSessionState.executionLog` is a ready-made task model, and `TeamRolePermissions` is a permission vocabulary. But roles are code-team roles (no operator roles), and there are no action/effect message types. The session log is a string list, not a structured action→effect journal. |
| `agent/team/model/CustomAgents.kt` (315 lines) | User-defined agents: `CustomTeamAgent` (id, name, title, description, baseRole, systemPrompt, permissions, defaultAgentKind, boundProjectIds, isEnabled), `BuiltinAgentOverride`, keyword-template draft generator + goal matcher | `draftCustomAgent(description)`, `customAgentMatchesGoal(…)`, `CustomTeamAgent`, `BuiltinAgentOverride` | **EXTEND** — "agent = identity + instructions + permissions + binding, user-approved, AI never creates autonomously" maps well to V4 operator agents (bound to target apps instead of projects). `draftCustomAgent` is keyword-template based, not LLM. |
| `agent/team/AgentTeamOrchestrator.kt` (593 lines) | Builds the project DAG (`planProject(goal)` / `planProject(goal, customAgents)`), single-step and full-run execution entry points, wires dispatcher + blackboard + comm bus | `planProject(String)`, `planProject(String, List<CustomTeamAgent>)`, `executeNextReadyTask()`, `autoRunAllReal()` | **EXTEND** — the plan-then-dispatch shape is right, but the hardcoded 10-role code DAG (manager→architect→…→devops) is wrong for V4; needs an operator plan template (observe→act→verify loops per target app). |
| `agent/team/runtime/ConcurrentTeamDispatcher.kt` (556 lines) | Concurrent DAG dispatch: per-stage semaphore (bounded by `maxConcurrentAgents` + per-role limits), per-file mutexes against `affectedFiles` overlap, budget-quota halt, cancellation, `customAgentResolver` prompt/permission overlay | `runAll()`, `runReadyTask(task)`, `cancel()`, `TeamTaskOutcome`, `TeamRunReport` | **REUSE-AS-IS** for concurrent dispatch; **EXTEND** to add a no-progress guard (today: stages run to completion or budget exhaustion; no "no observable effect in N steps" circuit breaker). |
| `agent/team/runtime/RolePromptRenderer.kt` (96 lines) | Pure-JVM prompt builder: custom-agent or role identity + task + dependency outputs + blackboard digest; machine-readable `VERDICT:` marker for reviewers, parsed honestly (no marker = no review recorded) | `render(TeamExecutionContext, …)`, `parseVerdict(String)`, `VERDICT_MARKER` | **EXTEND** — the renderer + machine-readable marker pattern is exactly what V4 needs for typed operator actions (e.g. require agents to emit `ACTION:` lines). Currently no action vocabulary; markers exist only for review verdicts. |

## 2. workflow/

| File | Purpose | Key APIs | V4 verdict |
|---|---|---|---|
| `workflow/TaskDecomposer.kt` (111 lines) | Honest template-driven decomposition: ISSUE_FIX/FEATURE/REFACTOR → fixed validated step sequences; CUSTOM carries caller-supplied steps validated for unique ids, known deps, no cycles (via `topologicalOrder`); anything else is **refused**, never hallucinated | `TaskDecomposer.decompose(goal)` → `Planned`/`Refused`, `TemplateTaskDecomposer`, `DecompositionOutcome` | **EXTEND** — honest-refusal + template discipline is the right foundation for V4; needs an operator goal kind/template (e.g. `OPERATE_APP` → observe/act/verify steps) and `PlanStepKind` extension for UI actions. |
| `workflow/PlanExecutor.kt` (204 lines) | Dependency-ordered execution with bounded auto-repair (`RepairAdvisor`), resume (SUCCEEDED steps kept), cancellation; safety DENY is never repaired; PROMPT fails the step so a user grant + resume can continue later | `executeSubset(…)`, `runWithRepair(…)`, `AutoFixRepairAdvisor`, `RepairAdvice` | **REUSE-AS-IS** as the execution kernel; **EXTEND** with a no-progress-guard hook and an effect journal (today `OperationResult` carries `logs` + `verificationEvidence` strings but no before/after state records). |
| `workflow/StepExecutors.kt` (302 lines) | One executor per `PlanStepKind` (ANALYZE, IMPLEMENT, WRITE_FILE, RUN_COMMAND, RUN_TESTS, GIT_COMMIT, GIT_PUSH); every side effect goes through the M5 safety-gateway adapters; SIMULATED agent results are refused; WRITE_FILE checkpoints before writing | `StepExecutor` interface, `WorkflowContext`, `AnalyzeStepExecutor`, `ImplementStepExecutor`, `WriteFileStepExecutor`, `CommandStepExecutor`, `GitStepExecutor`, `ProcessCommandRunner` | **EXTEND** — the "every effect through the safety gateway + checkpoint-before-write" pattern is exactly the V4 model; needs new `StepExecutor`(s) for operator actions (tap/type/scroll/screenshot/launch) wired through `SafetyGateway` with new `SafetyOperation` subtypes. |
| `workflow/CheckpointStore.kt` (199 lines) | Pre-write file snapshots + `rollback()` to unwind failed runs; persisted `WorkflowRunState` JSON per run for resume; corrupt state ignored, never crashes resume | `snapshot(…)`, `rollback(…)`, `saveRunState(…)`, `loadRunState(…)` | **EXTEND** — file rollback is useful; V4 additionally needs an **effect journal** (action log with before/after screenshot refs), which this is not. The run-state persistence pattern is reusable for operator session persistence. |

`workflow/WorkflowModels.kt` defines the vocabulary these share: `PlanStepKind` (7 code-oriented kinds),
`PlanStep` (id/kind/title/target/payload/dependsOn/maxAttempts), `WorkflowPlan.topologicalOrder()`,
`StepRunStatus`, `WorkflowRunState`, `WorkflowPhase`. **EXTEND** — needs UI-action step kinds.

## 3. security/

| File | Purpose | Key APIs | V4 verdict |
|---|---|---|---|
| `security/PermissionPolicyEngine.kt` (547 lines) | Granular permission evaluation: 15 `PermissionCategory`s, `PermissionLevel` ONCE/SESSION/PROJECT/ALWAYS/DENY, session/once/project/global grants, emergency kill switch, dangerous-command detection, sensitive-path checks | `evaluatePermission(PermissionRequest)`, `grantPermission(…)`, `engageEmergencyKillSwitch()`, `PermissionCategory`, `PermissionLevel` | **EXTEND** — engine is solid; needs new `PermissionCategory` entries for operator actions (e.g. `UI_INTERACT`, `SCREEN_CAPTURE`, `INPUT_INJECTION`, `APP_LAUNCH`). The category enum is the natural extension point. |
| `security/SafetyGateway.kt` (277 lines) | Single authorization choke point for every side effect: kill switch → path audit → MCP perms → policy engine; verdicts ALLOW/DENY/PROMPT; `requireAllowed` throws `SafetyDeniedException` (no bypass); `recordGrant` persists user grants per scope | `authorize(…)`, `requireAllowed(…)`, `recordGrant(…)`, `SafetyVerdict`, `SafetyDecision` | **REUSE-AS-IS** — this **is** V4's action-authorization foundation. Approval binding exists at the operation level (PROMPT → grant → scoped grant record). |
| `security/SafetyOperation.kt` | Sealed operation vocabulary: `FileOp`, `ShellExec`, `GitOp`, `McpToolCall`, `NetworkRequest`, `ApkInstall`, `RemoteExec` (+ `FileOpKind`, `GitOpKind`) | `SafetyOperation` sealed class, `describe()` | **EXTEND** — **this is the home for typed operator actions**: add subtypes (Tap/Type/Scroll/Swipe/LongPress/LaunchApp/PressKey/Screenshot…), each with a `describe()` for prompts and audit. |
| `security/AuthorizationPrompt.kt` | Builds the user-facing approval prompt for an operation (`forOperation`) | `AuthorizationPrompt.forOperation(…)` | **REUSE-AS-IS** — prompt generation already exists; extend per new operation subtypes. |
| `security/SecurityAuditLogger.kt` (172 lines) | Crash-safe audit trail (JSON, 1000-entry cap, atomic writes), queryable by category/decision/project | `log(SecurityAuditEntry)`, `getEntries(…)`, `filter(…)`, `AuditDecision` | **EXTEND** — closest thing to a step/effect journal, but it logs **authorization decisions**, not action outcomes/effects. V4 needs effect records (action, before/after screenshot refs, observed UI delta) — either extend the entry schema or add a parallel journal. |

## 4. runtime/

| File | Purpose | Key APIs | V4 verdict |
|---|---|---|---|
| `runtime/RuntimeExecutionService.kt` (221 lines) | Foreground `Service` keeping long agent runs alive: wake + wifi locks (90-min cap), per-session notifications, START/PROGRESS/COMPLETE/FAILED/CANCELLED intents, `RuntimeTaskController` stop registry | `ACTION_START/STOP/PROGRESS/COMPLETE/FAILED/CANCELLED`, `RuntimeTaskController.requestStop(…)` | **REUSE-AS-IS** for keeping operator runs alive with user-visible progress + a stop path. **Not** a takeover mechanism: the user can only stop, not pause-take-control-and-resume. |

## 5. visual/

| File | Purpose | Key APIs | V4 verdict |
|---|---|---|---|
| `visual/VisualDiffer.kt` (187 lines) | Pure-JVM pixel differ: per-channel tolerance, ignore regions, 16px-cell flood-fill merge into `ChangedRegion` bounding boxes; throws on size mismatch instead of lying | `VisualDiffer.diff(before, after, tolerance, ignoreRegions, threshold)`, `PixelBuffer`, `VisualDiffResult`, `ChangedRegion` | **REUSE-AS-IS** — ideal for before/after screenshot comparison in an operator verify step (this is the no-progress-guard's sensor). |
| `visual/VisualCaptureEngine.kt` | Captures **in-app WebViews only** (`webView.draw(canvas)`); saves snapshots with DOM/console/network metadata | `captureWebView(…)`, `saveSnapshot(…)` | **MISSING-adjacent** — not usable for arbitrary-app screenshots; see gaps. |

---

## 6. Device-interaction capability check

Greps over `app/src/main/java` for `AccessibilityService`, `MediaProjection`,
`UiAutomation`, `dispatchGesture`, `AccessibilityNodeInfo`, `GestureDescription`,
`performAction` returned **zero hits**. `AndroidManifest.xml` contains **no**
accessibility-service declaration / `BIND_ACCESSIBILITY` permission.
Related findings:

- `VisualCaptureEngine` draws only the app's own WebViews — no system-wide screenshot path.
- No OCR / text-recognition code anywhere.
- The only "takeover" in the codebase is `WebBrowserScreen.takeOverBrowserTab`
  (user takes over the agent-driven **in-app browser tab** by clearing agent drive
  state) — not a pause/act/resume takeover for an operator run.

---

## 7. Gaps for V4 — completely missing

1. **Typed operator action model.** No Tap/Type/Scroll/Swipe/LongPress/LaunchApp/
   PressKey/Screenshot action types. Natural home: new `SafetyOperation` subtypes
   (+ new `PlanStepKind`s, + new `StepExecutor`).
2. **AccessibilityService / UI-tree access.** No node hierarchy, no accessibility
   actions, no `AccessibilityEvent` stream. This is the hard prerequisite for
   observe (what's on screen) and act (tap a node) without screenshots-only hacks.
3. **System screenshot capture.** No `MediaProjection`; `VisualCaptureEngine` is
   in-app WebView only. Operator verify steps need arbitrary-app screenshots
   (feeds directly into the reusable `VisualDiffer`).
4. **Gesture injection.** No `dispatchGesture` — no way to synthesize taps/swipes.
5. **OCR.** No text recognition on screenshots (needed when accessibility tree is
   unavailable, e.g. WebViews/canvases in target apps).
6. **Step/effect journal.** `SecurityAuditLogger` records auth decisions;
   `TeamSessionState.executionLog` and `OperationResult.logs` are string lists.
   Nothing records structured action→effect tuples (action, target, before/after
   screenshot refs, observed delta, timestamp).
7. **No-progress guard.** Nothing detects "N actions, zero observable UI change"
   or "screenshot identical 3 times in a row" to halt/replan. `VisualDiffer`
   provides the sensor; the guard policy + wiring into `PlanExecutor`/
   `ConcurrentTeamDispatcher` does not exist.
8. **Approval binding for action sequences.** `SafetyGateway` PROMPT +
   `recordGrant` gives per-operation approval, but there is no "approve this
   planned action sequence" binding (approval tied to a plan/step instance), and
   no operator-oriented approval UX.
9. **Takeover (pause → user acts → resume).** `RuntimeExecutionService` offers stop
   only; `WebBrowserScreen` takeover is browser-tab-scoped. No mechanism to pause
   an operator run, let the user perform steps manually, then resume with the
   manual actions recorded in the journal.
10. **Operator plan templates.** `TaskDecomposer` has no `OPERATE_APP` goal kind;
    `AgentTeamOrchestrator.planProject` builds a fixed 10-role **code** DAG.
    V4 needs an observe→act→verify loop template per target app, plus an operator
    role/agent model distinct from code-team roles.

## 8. Bottom line

Roughly 60% of an operator loop skeleton already exists and is honestly built:
`SafetyGateway` (+ `PermissionPolicyEngine`, `AuthorizationPrompt`) is a genuine
action-authorization foundation; `PlanExecutor` + `StepExecutors` + `CheckpointStore`
is a genuine supervised execution kernel with resume/rollback; `VisualDiffer` is a
ready-made change sensor; `ConcurrentTeamDispatcher` handles multi-agent dispatch;
`TaskDecomposer`'s honest-refusal discipline is the right planning ethic.

The missing 40% is all on the device-interaction side (AccessibilityService,
MediaProjection screenshots, `dispatchGesture`, OCR) plus the operator-specific
loop logic (typed actions as `SafetyOperation` subtypes, effect journal,
no-progress guard, approval binding to action sequences, true takeover).
The device-interaction half is the feasibility risk: it requires a new
AccessibilityService with user enablement, a MediaProjection consent flow, and
Play-policy-sensitive permissions — none of which have any precedent in the
current codebase.
