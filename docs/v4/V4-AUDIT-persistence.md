# V4 Feasibility Audit — Persistence & Memory Architecture

**Branch:** v4 (from v3 @ d06038b) | **Date:** 2026-10-17 | **Scope:** audit only, no code
**Base package:** `com.codeair.mhe` (`app/src/main/java/com/codeair/mhe/`)
**Mapped against:** V4 roadmap sections 19 (hierarchical persistent memory) and 21 (data model & storage contract)

---

## 1. Inventory of every persistence mechanism

| # | Mechanism | Location (file) | Format | Scope / what it holds |
|---|---|---|---|---|
| 1 | SharedPreferences `pocket_preferences` | `data/AppPreferences.kt` | Key-value primitives + one big JSON string (`projects_json`) | Onboarding flags, `last_active_project_id`, `last_active_chat_id_<projectId>`, full project list (id, name, slug, kind, updatedAtMillis) |
| 2 | SharedPreferences `pocket_secrets` | `data/ApiKeyVault.kt` | Encrypted blobs (Android Keystore, AES-GCM) + JSON metadata | API key pools per provider (`CUSTOM_<configId>` vault ids), active key id |
| 3 | SharedPreferences `custom_providers` | `data/CustomProviderStore.kt` | org.json `JSONArray` of provider configs | Custom LLM provider configs (StateFlow-backed) |
| 4 | SharedPreferences `custom_team_agents` | `data/CustomTeamAgentStore.kt` | org.json `JSONArray` (+ overrides map) | Custom team agents + builtin agent overrides (StateFlow-backed) |
| 5 | EncryptedSharedPreferences `gemini_api_secure_prefs` | `data/GeminiApiStore.kt` | Encrypted key-value (AES256_SIV keys / AES256_GCM values) | Gemini API key + model selection |
| 6 | SharedPreferences `antigravity_auth_store` | `runtime/AntigravityAuthController.kt` | Key-value | Antigravity auth tokens |
| 7 | SharedPreferences `process_forensics` | `MobileHarnessApplication.kt` | Key-value | Process-lifecycle forensics flags |
| 8 | SharedPreferences (marketplace prefs) | `marketplace/MarketplaceInstallManager.kt`, `marketplace/MarketplacePublishedItemsStore.kt` | Key-value + JSON | Installed marketplace items, published items |
| 9 | App-private JSON files `filesDir/chats/<projectId>/<chatId>.json` | `data/AppPreferences.kt` (`saveMessages`/`loadMessages`) | org.json `JSONArray` of messages | **Exact chat message history** per conversation. Crash-safe via tmp-file + rename. **Capped: newest 1000 messages only** (`MAX_TRANSCRIPT_MESSAGES = 1000`, AppPreferences.kt:674) |
| 10 | App-private JSON file `filesDir/chats/<projectId>/index.json` | `data/AppPreferences.kt` | org.json `JSONArray` | Conversation list per project (id, title, timestamps) |
| 11 | App-private JSON files `filesDir/agent_sessions/active_sessions/session_<id>.json` | `session/AgentSessionManager.kt` | org.json | Agent session records: sessionId, projectId, projectTitle, chatId, initialPrompt, startedAt/updatedAt, status (RUNNING/COMPLETED/FAILED/INTERRUPTED), activeRoadmap, summary |
| 12 | App-private run dirs `<baseDir>/<runId>/state.json` + `checkpoints/` | `workflow/CheckpointStore.kt` | org.json | Workflow run state (plan steps, step results, phase) persisted after every step; pre-write file snapshots for rollback |
| 13 | App-private dir `filesDir/workspaces/<projectId>` | `workspace/ProjectWorkspaceController.kt` | Raw files | Project workspace files (the actual code/files the agent edits) |
| 14 | App-private JSON `filesDir/indexes/<projectId>.json` | `runtime/ProjectIndex.kt` | org.json | Semantic code index per project (IndexedFile list) |
| 15 | App-private checkpoints `filesDir/checkpoints/<projectId>/snapshots/` | `workspace/WorkspaceCheckpointManager.kt` | Files + `checkpoints_index.json` | Full workspace snapshot checkpoints with metadata |
| 16 | App-private JSON `filesDir/mcp/mcp_servers.json` | `mcp/McpRegistry.kt` | org.json | MCP server registry (uses `SafeFileOps.atomicWriteText`) |
| 17 | App-private `filesDir/security/security_audit.json` | `security/SecurityAuditLogger.kt` | JSON | Security audit events (append-style immutable log) |
| 18 | App-private crash log `filesDir/<crash file>` | `AppCrashLogger.kt` | Text | Crash logs + checkpoints |
| 19 | App-private `filesDir/marketplace/<id>/manifest.json` + `package.bin` | `marketplace/MarketplaceInstallManager.kt` | JSON + binary | Materialized marketplace packages |
| 20 | MCP credentials (Keystore + sidecar file) | `mcp/McpCredentialStore.kt` | Encrypted (Android Keystore) | MCP server credentials |

### What does NOT exist
- **No Room, no SQLite, no DataStore (neither Preferences nor Proto).** Verified by grep for `@Entity`, `@Dao`, `@Database`, `SQLiteOpenHelper`, `DataStore`, `Room.` across all sources — zero hits in app code. (The only `Room` mention is in `factory/generators/AndroidComposeGenerator.kt`, which *generates* Room code for user projects; the app itself never uses it.)
- **No kotlinx.serialization.** Zero hits. Serialization is 100% `org.json` (manual `JSONObject`/`JSONArray` put/get).
- **No migration infrastructure.** `app/build.gradle.kts` has no Room/DataStore/SQLite dependencies. The only "migrations" are two one-off manual code paths: legacy `chats/<projectId>.json` -> `chats/<projectId>/index.json` (AppPreferences.kt:409), and `McpCredentialStore` key re-encryption migration (mcp/McpCredentialStore.kt:168-172).

### Crash-safety status (existing)
- `saveMessages`: tmp-file write + `renameTo` (fallback copyTo+delete) — crash-safe single-file write (AppPreferences.kt:498-505).
- `SafeFileOps.atomicWriteText` — same tmp+rename pattern, used by MCP registry and others (workspace/SafeFileOps.kt:20).
- `CheckpointStore.saveRunState` — plain `writeText`, wrapped in `runCatching` (failure reported, not crash-safe).
- Corrupt reads are tolerated (`runCatching` -> null/empty) but there is **no journaling, no multi-record transactions, no schema versioning, no corruption repair**.

---

## 2. data/ package store patterns

All stores follow one convention: **SharedPreferences + `org.json` + in-memory `StateFlow`**.

| Store | Prefs file | Serializes | Notes |
|---|---|---|---|
| `CustomProviderStore.kt` | `custom_providers` | `JSONArray` via `model/customProviderFromJson` / `toJson` | save() = rewrite whole array |
| `CustomTeamAgentStore.kt` | `custom_team_agents` | `JSONArray` (agents) + overrides | `getEnabledForProject(projectId)` does project binding filter in memory |
| `ApiKeyVault.kt` | `pocket_secrets` | JSON metadata + Keystore-encrypted secrets | `@Synchronized` methods |
| `AppPreferences.kt` | `pocket_preferences` | Primitives (`.commit()` = synchronous) + `projects_json` array | 681 lines; also owns all chat file I/O |
| `GeminiApiStore.kt` | `gemini_api_secure_prefs` | EncryptedSharedPreferences | Lazy MasterKey |

Pattern traits: whole-collection rewrite on every save (no partial updates), no transactions, no schema version field, `optString`/`optLong` used defensively for forward-compat reads.

---

## 3. workflow/CheckpointStore.kt

- **Persists:** `WorkflowRunState` (runId, plan steps, per-step results, phase, review notes, finished result) as `<baseDir>/<runId>/state.json`, rewritten after every step; plus pre-write snapshots of workspace files under `<runId>/checkpoints/<relative path>` (with `.absent` markers for files that did not exist).
- **Format:** org.json, manual mapping (`stateToJson`/`jsonToState`).
- **Scope:** per workflow run; supports `rollback()` (restore snapshots) and `discard()`.
- **Gaps vs V4 §21:** no `task_id` linkage (runs are not tied to tasks/conversations), no attempt counter, no `ActionEffect` journal (step results only), no `ObservationRef`, no `approval` binding records, no atomicity between state write and checkpoint write.

---

## 4. Conversations / chat history

- `AgentChatController.kt` (330 lines) is a **controller, not a store** — it sanitizes attachment names and resolves `@path` mentions. It holds no history.
- History lives in `data/AppPreferences.kt`: `filesDir/chats/<projectId>/index.json` (conversation list) + `filesDir/chats/<projectId>/<chatId>.json` (messages).
- `ChatMessage` (model/Models.kt:417) has stable UUID `id`, `fromUser`, `text`, `createdAt` (Instant), attachments, workItems, optional `roadmap`. Ordering is array order.
- `ProjectChat` (model/Models.kt:436) has stable UUID `id`, title, created/updated millis.
- **Exact history is NOT fully retained:** `saveMessages` keeps only the newest 1000 (`MAX_TRANSCRIPT_MESSAGES`), rewriting the whole file on every message. V4 §19.3 requires the *original* full history as source of truth with summaries as derived data — the current 1000-cap and no-summary-separation violates this.
- No conversation summaries, no context-window index, no user-correction records, no append-only event log.

---

## 5. Projects

- `Project` model (model/Models.kt:184): stable UUID `id`, name, description, language, `slug` (deduped), `rootPath`, `updatedAtMillis`, `kind` (PROJECT / QUICK_PROJECT).
- Stored as a single JSON array in `pocket_preferences` under key `projects_json`; rewritten wholesale on save; slug-collision repair and `updatedAtMillis` backfill run on load.
- Project workspace files live in `filesDir/workspaces/<projectId>`; checkpoints and code index are project-scoped by directory name.
- **Gaps vs V4 §19.1/21:** no persisted project goal, scope, constraints, decisions, task index, progress, open questions, or project summary records. Project identity is stable; project *memory content* does not exist.

---

## 6. Database / migration infrastructure

**None.** No Room/SQLite/DataStore dependency, no `@Entity`/`@Dao`/`@Database`, no schema-version field on any stored document, no migration runner. Forward compatibility is handled ad hoc via `optX` JSON reads and two one-off manual migration code paths (see §1).

---

## 7. Verdicts against V4 memory levels (§19/§21)

| V4 level | Verdict | Evidence |
|---|---|---|
| **Global** | **PARTIAL** | Exists as *configuration*, not as *memory*: `pocket_preferences` (user prefs, onboarding), custom providers/agents stores, `antigravity_auth_store`. Missing: `MemoryRecord` with scope/provenance/sensitivity/revision, "cross-project facts explicitly marked global", global retrieval policy. |
| **Project** | **PARTIAL** | Stable `project_id` (UUID), slug, project-scoped chat dirs/workspaces/checkpoints/indexes. Missing: project goal, scope, files/artifacts registry, constraints, decisions, task index, progress, open questions, project summary — i.e. everything §19.1 lists under Project memory. |
| **Conversation** | **PARTIAL** | Stable `conversation_id` (`ProjectChat.id` UUID), exact per-message records with `message_id` (UUID), timestamps, chronological ordering, crash-safe file writes. Missing: full (uncapped) history retention (currently newest-1000), separate derived summaries + revision tracking, context-window index, user corrections as records, append-only event semantics, `ConversationEvent` sequence-uniqueness guarantee. |
| **Task/Execution** | **PARTIAL** | `WorkflowRunState` per `run_id` with step results + `CheckpointStore` rollback; `AgentSessionManager` session records with status enum. Missing: `Task` record linking conversation+project, `ExecutionRun` state machine with attempts, `ActionEffect` journal (dispatch/outcome/approval binding), `ObservationRef`, transactional message+linkage writes. |
| **AppKnowledge** | **MISSING** | No `InstalledApp` registry, no `AppKnowledgeVersion`, no `ScreenNode`, no `FeatureCapability`, no `ActionRecipe`, no evidence/confidence records. Grep for these concepts returns zero hits. No versioned publication/rollback. |

**Cross-cutting gaps (§19.2/19.3/19.6):** no `memory_record_id`/`effect_id` identity scheme; no creation/update timestamps or schema-version on most records; no transactional/journaled writes (tmp+rename is per-file only); no defined migration story; secrets are correctly kept out of ordinary stores (Keystore/EncryptedSharedPreferences) — that part is already right.

---

## 8. Recommended storage approach for V4

**Recommendation: introduce Room (SQLite) as the V4 memory database, and keep SharedPreferences + files for what they already do well. Do NOT try to build §19/§21 on the existing JSON-file patterns.**

Reasoning:

1. **The V4 contract is relational.** §21.1 explicitly requires unique `(conversation_id, sequence)`, indexes on `(project_id, updated_at)` / `(conversation_id, timestamp)` / `(task_id, created_at)` / `(run_id, step_id)`, and foreign-key integrity across project/conversation/task/run. The current stores cannot express any of this; emulating joins and ordered-event uniqueness over JSON files is strictly worse than SQLite.
2. **Crash-safe multi-record writes need transactions.** §19.3 demands "transactional or journaled" writes so a message is never saved without its linkage. Room `@Transaction` gives this; the current tmp+rename pattern is per-file only and `CheckpointStore` isn't even atomic.
3. **Migrations are a stated V4.0 acceptance criterion** ("Define database migrations... migration/rollback plan exists"). Room's `Migration` classes + `schema version` are the standard answer; the codebase currently has zero migration infrastructure, so there is no incumbent to displace.
4. **Append-only event history fits a table, not a rewritten JSON array.** The current `saveMessages` rewrites the entire transcript file on every message (O(n²) total I/O, mitigated only by the 1000-cap). An `ConversationEvent` table with `(conversation_id, sequence)` unique index gives O(1) appends and uncapped exact history — directly satisfying §19.3.
5. **Keep what works:** SharedPreferences remains right for flags/prefs/last-active pointers; EncryptedSharedPreferences/Keystore remain right for secrets (never move secrets into memory tables — §19.6/21.2 agree); `filesDir` remains right for workspace files, snapshots, and package binaries. Room should own *records and linkages* (Project, Conversation, ConversationEvent, MemoryRecord, Task, ExecutionRun, ActionEffect, AppKnowledge tables), not blobs.
6. **Cost note:** Room is a new dependency (currently absent from `app/build.gradle.kts`), so V4.0 must budget for schema design, migration scaffolding, and a one-time import of existing JSON state (projects, chats, sessions, workflow runs) into the new tables — the existing `optX`-style tolerant reads make this import straightforward.

**What to reuse:** stable UUID identity scheme (already used for project/chat/message/session ids — keep it as the V4 `*_id` format), `SafeFileOps.atomicWriteText` for any remaining file writes, the Keystore/EncryptedSharedPreferences secrets boundary, and the `SecurityAuditLogger` append-only pattern as the model for the audit/event tables.
