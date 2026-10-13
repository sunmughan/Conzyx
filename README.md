<div align="center">

# Mobile Harness Extended

### A desktop-class AI software engineering workstation for Android

Run a real Linux development environment, command multi-agent AI engineering teams, automate GitHub workflows, build Android APKs, and collaborate in real time - all from your phone. No root required.

<br/>

[![Release v3.0.1](https://img.shields.io/badge/Release-v3.0.1-F28C52?style=flat-square&logo=github&logoColor=white)](https://github.com/sunmughan/Mobile-Harness-Extended/releases/tag/v3.0.1)
[![CI](https://img.shields.io/github/actions/workflow/status/sunmughan/Mobile-Harness-Extended/android.yml?branch=v3&style=flat-square&label=CI)](https://github.com/sunmughan/Mobile-Harness-Extended/actions)
[![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#-system-requirements)
[![ARM64](https://img.shields.io/badge/CPU-ARM64-5B8DEF?style=flat-square)](#-system-requirements)
[![Kotlin 2.1](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License MIT](https://img.shields.io/badge/License-MIT-8B7CF6?style=flat-square)](LICENSE)

<br/>

[**Download Online APK**](https://github.com/sunmughan/Mobile-Harness-Extended/releases/download/v3.0.1/app-online-release.apk) &nbsp;·&nbsp;
[**Download Offline APK**](https://github.com/sunmughan/Mobile-Harness-Extended/releases/download/v3.0.1/app-offline-release.apk) &nbsp;·&nbsp;
[**What's New**](#-whats-new-in-301) &nbsp;·&nbsp;
[**Installation**](#-installation) &nbsp;·&nbsp;
[**Build from Source**](#-building-from-source) &nbsp;·&nbsp;
[**Configuration**](#-configuration)

</div>

---

## Table of Contents

- [What's New in 3.0.1](#-whats-new-in-301)
- [What is Mobile Harness Extended?](#-what-is-mobile-harness-extended)
- [Features](#-features)
- [System Requirements](#-system-requirements)
- [Installation](#-installation)
- [Building from Source](#-building-from-source)
- [Configuration](#-configuration)
- [Project Structure](#-project-structure)
- [Architecture Overview](#-architecture-overview)
- [Security and Privacy](#-security-and-privacy)
- [Contributing](#-contributing)
- [License](#-license)

---

## 🚀 What's New in 3.0.1

### 🤖 Multi-Agent AI: now genuinely real

| Change | Detail |
|---|---|
| Real agent execution | The Multi-Agent Engineering Studio now dispatches genuine agent runs through your installed runtimes (Claude Code, DeepSeek, Antigravity, Codex). The old simulated runs are gone. |
| Honest failure states | If a runtime is missing or a provider key is not configured, tasks report `NOT_CONFIGURED`. Nothing is ever faked as success. |
| Live execution status | The "DEMO MODE" banner is removed. The studio header shows live status while agents work, and the DAG tab guides you when there are no tasks yet. |

### ✨ UI/UX polish pass

- **Onboarding rebuilt**: 7 architecture-jargon slides cut down to 3 outcome-focused slides, with a prominent "Try it now" button on slide one.
- **Clearer words everywhere**: microcopy rewritten across the app ("Quick Task Benchmark Trigger" is now "Test run: watch a task route between phone and cloud", and more).
- **Empty states that teach**: every empty screen now explains what the feature is for and offers one clear action, instead of a bare "No items".
- **Linear-style lists**: Settings developer tools moved from chunky cards to quiet list rows with dividers, one line per tool.
- **Bottom-anchored actions**: primary buttons (AI Analyze, Build APK, Plan) now sit in sticky bottom bars, inside the thumb zone.
- **48dp touch targets**: all interactive elements meet the minimum touch size.
- **Accent discipline**: orange is reserved for interactive elements only; decorative badges are neutral grays.
- **Studio cards relocated**: the four studio cards moved to the bottom of the home screen so your projects come first.
- **Branding cleanup**: leftover PocketDev references removed, version pills removed from studio headers and onboarding, phase pills removed from the tour.
- **Full rebrand to Mobile Harness Extended**: every user-facing "Mobile Harness" string is now "Mobile Harness Extended" (app name, install screen, launcher label, Play listing, privacy policy, in-app text, generated-app templates). The launcher icon and Play icon now carry an EXTENDED label under the mark, and the Play feature graphic reads "Mobile Harness Extended".
- **Agent tabs no longer truncate names**: the fixed tab row truncated agent names, which made two agents look like the same agent twice. Tabs are now a scrollable row with full names plus a distinct icon per agent.
- **Agent cards follow the viewed tab**: the config card used to render only for the active agent, so viewing the Antigravity tab while another agent was active showed a blank card and the multi-account UI looked removed. It was never removed. Each tab now shows its own agent's card, and viewing an installed but inactive agent offers a "Make active" button.
- **Honest update checks**: update checks only covered installed agents, while the card still claimed "up to date" without any check. The card now says plainly whether an agent is not installed, never checked, or the check failed.
- **Status bar respected**: the app no longer overrides status bar or navigation bar colors; your device defaults are left alone.
- **Browser redesigned**: bigger URL bar, top row reduced to URL bar + menu, ports strip and unused pills removed.
- **Files header fixed**: the "Files" title shared one row with 7 action buttons and wrapped vertically on narrow phones. The title now sits on its own top-left line with the full icon row below it.
- **Prompt-send crash fixed**: the native `pocket_spawn.c` still exported JNI symbols under the old upstream package (`Java_com_jarves_mh_runtime_NativeSpawn_*`) while the Kotlin class lives at `com.codeair.mhe.runtime.NativeSpawn`, so every agent session died at spawn time with "No implementation found". All three JNI entry points (spawn/waitFor/kill) renamed to the `com.codeair.mhe` package.
- **Full-app audit fixes (2026-10-13)**: duplicate FCM services removed in the online flavor (every push was delivered twice); `System.loadLibrary("pocketspawn")` now throws a clear reinstall message instead of a raw linker error; `scripts/build-play-release.sh` no longer defaults to the upstream author's `/Users/jarves/` keystore path; prebuilt `libpocketspawn.so` rebuilt from the fixed C source so ARM64-host builds don't reintroduce the JNI crash.
- **Agent screen fixes (2026-10-13)**: agent tab names are now centered in their own columns; the Runtime updates card shows only the currently viewed tab's agent updates (Antigravity/DeepSeek/Claude each get their own checker); new **Custom** agent tab — bring your own OpenAI-compatible or Claude-compatible API key (base URL + key + model), no binary download or install needed, chats directly via your API.
- **Status bar icons fix (2026-10-13)**: system status/navigation bar icons now follow the actual app theme — white icons in dark mode, dark icons in light mode. Previously the light XML theme forced dark icons even when the app rendered dark, making them invisible on the dark background.
- **Compile fixes (2026-10-13)**: fixed the build breakage from the latest UI/functional changes — stray closing brace left after removing the chat kill-switch Row, FilterChip `contentPadding` argument that does not exist in Material3, BrowserTab saveable-state saver wrapped for MutableState, and a missing `offset` import in the YouTube dialog.
- **No-fake-data cleanup (2026-10-13)**: every screen now shows only real data. Cloud Hybrid: telemetry reads real ActivityManager/BatteryManager/ConnectivityManager/PowerManager values, the fake pocketdev.io preset runners are gone (fleet starts empty, add your own), Ping All performs a real /health probe and marks dead workers OFFLINE.
- **Removed, not faked (2026-10-13)**: anything the app cannot back with a real API is gone instead of showing a placeholder. CPU temperature / thermal throttling are removed entirely (Android exposes no public API for them). Team Collaboration Studio and its whole Firebase collaboration package are removed (no collaboration server exists; a local-only "collaboration" session was useless). The Team orchestrator's demo/simulation stepping API is deleted — execution is real-only, and planning no longer records fabricated 1250-token / $0.003 budget entries.
- **Multi-Agent Studio dead-run fix (2026-10-13)**: Plan no longer secretly fires the goal into the single-agent chat as a hidden background task (it used to send "Multi-Agent Team Goal: Multi-Agent Team Goal: ..."). A failed first task used to block the whole DAG while the header kept claiming "Executing" and Step became a dead button — now the real failure reason (e.g. "The 'Claude Code' runtime is not installed") shows as a red banner with a **Retry failed** button that re-queues the failed tasks, failed DAG cards show their error inline, the goal input gets its own full-width row (it was squeezed to "Build robu..."), and the highlighted agent card's title is readable again (it rendered dark-on-dark).
- **Multi-Agent Studio engine fallback (2026-10-13)**: a team task no longer dies when its wired engine has no saved API key. The executor now resolves the engine per task: the role's wired engine when usable, else your currently selected agent, else any usable engine. A signed-in Antigravity session counts as auth (the Antigravity CLI never reads a vault API key), so a Manager task wired to Claude Code runs on your signed-in Antigravity/Gemini session instead of failing for a missing Claude key. The agent detail sheet shows which engine actually ran the task ("via Antigravity").
- **Multi-Agent Studio DeepSeek + Custom endpoints (2026-10-13)**: DeepSeek Harness is a full fallback engine (real file edits and tools), including custom OpenAI/Claude-compatible endpoints through its mh-custom route, and it explicitly rejects the Claude subscription login instead of failing mid-run. The Custom agent itself is deliberately NOT a fallback candidate: it is chat-only (no file edits, no tools), so letting it run would mark engineering tasks COMPLETED with zero code written. When no engine is usable and a Custom endpoint is configured, the error says exactly that and points to DeepSeek Harness as the way to use that endpoint for real engineering work.
- **Multi-Agent Studio UX fixes (2026-10-13)**: the DAG tab header no longer crams the "DAG Execution Pipeline" label and its buttons into one row (label gets its own full-width row, buttons sit on a scrollable row below). **Plan now runs the whole team**: pressing Plan creates the 10-task DAG + blueprint AND immediately auto-runs it (agents work, report, publish artifacts) — previously Plan only created the task list and left it sitting at 0/10, so nothing visibly happened until Step/Auto Run was pressed separately.
- **Cloud Hybrid telemetry is now realtime (2026-10-13)**: the coordinator used to read device metrics only once at startup, so Telemetry & Stats showed a stale snapshot (battery 5% when the phone was already at 13%). The screen now re-reads metrics on entry and every 30s while open.
- **Command palette cleanup (2026-10-13)**: the workspace command palette no longer shows the redundant lowercase enum name ("chat", "files", ...) on the right of each row — it was the same identity as the title and stole its width. Titles now use the full row; search still matches on keywords. **Dark text on dark backgrounds is fixed app-wide**: DAG task cards now use theme-aware status containers (tertiary/primary/secondary/error/surfaceVariant) with matching on-colors and every text carries an explicit color, so no title, description, error, or file chip can render black on a dark card again; the same explicit-color fix covers Comms messages, Blackboard artifacts, App Factory blueprint/bundle cards, Cloud Hybrid job cards, GitHub Studio file/branch/issue/workflow cards, Git diff lines (dark green/red text is now light green/light red in dark theme), and the Files header. The Comms input is rebuilt like the main chat composer: text field on top, circular send button inside the field at the bottom-right. The decorative **Resource Quota & Token Budget** card is removed (it showed a hardcoded 100,000-token quota and a usage counter that could never move because the CLI bridges report no token counts) and the tab is now just **Reviews** with the real reviewer sign-offs.
- **Crash-loop fix (2026-10-13, from the user's crash log)**: the app crashed on launch with `IllegalArgumentException: Key "interrupted-<sessionId>" was already used` because the persisted chat transcript held TWO rows with the same `interrupted-<sessionId>` id, and the chat LazyColumn keyed items by message id. Three layers: `persistMessages` never appends a second interrupted row for the same session; transcript loading dedupes by id so a poisoned transcript heals on load; and the chat list keys by `id#index` so a duplicate id can never crash the list again. Opening a file no longer ends in a white screen + restart.
- **"Analyzing" indicators removed (2026-10-13)**: the header "Analyzing..." pill and the "Analyzing Requirements" status banner are gone by user order. Other states (plan, build, fix, failure) still show.
- **Custom agent now streams (2026-10-13)**: the Custom agent used to make one blocking HTTP call and show only a "deciding the next useful step" line until the whole reply arrived. It now uses SSE streaming (OpenAI + Anthropic shapes) so the reply grows live in chat; endpoints that ignore `stream=true` fall back to the old single-response path.
- **Browser URL bar back to 2.2.2 (2026-10-13)**: the tall 50dp URL bar is restored to the 2.2.2 slim 40dp pill (20dp radius) with the Reload button embedded on the left and the Go button inside on the right. The 3-dots menu moved out of the top row down to the end of the icon row, after the other icons.
- **File open no longer fails silently (2026-10-13)**: tapping a file with no active project used to do nothing at all. It now shows "No project open. Open a project first, then tap the file again."
- **App updates pill fix (2026-10-13)**: Settings > App updates no longer shows an "ONLINE EDITION" badge plus a separate version label plus an "Automatic background updates are enabled." line. One small fully-rounded pill now reads "v3.0.1 ONLINE" (or "v3.0.1 OFFLINE"), sitting on the left with the Check now button on the right, in the same pill shape as the button.
- **Custom agent chat fix (2026-10-14)**: prompts sent with the Custom agent selected never showed any reply. The Custom bridge's response events were never collected, so the reply went nowhere and the chat stayed stuck "running", which silently ignored every later prompt too. The Custom event stream is now wired to the same handler as the other agents, so Custom replies (and errors, e.g. missing API key) appear in chat again.
- **Studio onboarding compile fix (2026-10-14)**: the per-tab onboarding commit shipped two compile errors (a duplicated `@Composable` annotation on the onboarding dialog, and a missing `@Composable` on the Studios row). The build was red; both are fixed.
- **Studios row + one-time studio onboarding (2026-10-13)**: the home screen no longer stacks three full-width studio cards. One "Studios" section now holds three small cards in a single row (App Factory, Multi Agent, Cloud Hybrid). Opening a studio the first time shows a one-time multi-page onboarding: one page per tab inside that studio (App Factory: Blueprint, Code & DB, Build & CI, Signing & Version, Play Store; Multi-Agent: Roster, Task DAG, Comms, Blackboard, Reviews; Cloud Hybrid: Telemetry & Stats, Workers Fleet, Job Pipeline, Routing Policies). Next/Back walk the pages, Got it / Skip opens the studio, Exit closes it, and any dismissal marks it seen so it never shows again for that studio.
- **Instant agent switching (2026-10-14)**: tapping another agent's tab now activates it immediately. It used to refuse with "Stop the current agent before switching", and a stuck session meant the switch never happened until the app was closed and reopened. Switching now stops the current session first, and late events from the stopped session are dropped so they can never leak into the new agent's chat.
- **Removed agent (2026-10-14)**: Google Cloud Code is gone entirely — its execution bridge never ran the binary (it only emitted a session-started event), so selecting it produced silence. Its enum entry, runtime bridge, auth controller, installer/update/check code, UI tabs, and tests are all deleted. The remaining agents were Claude Code, DeepSeek Harness, Antigravity, and Custom; Codex joined as a 5th agent later the same day.
- **Codex agent (2026-10-14)**: new 5th agent — OpenAI Codex CLI, driven headless via `codex exec --json` in the Linux guest. Installed with npm (`npm install -g @openai/codex`), API-key auth (your OpenAI key stored under its own `OPENAI` vault slot, optional model override), full file edits + tools like the other harness agents, and a fallback candidate for Multi-Agent Studio team tasks.
- **Codex/Custom vault collision fixed (2026-10-14)**: Codex briefly shared the Custom agent's `CUSTOM` vault slot, so saving one agent's API key overwrote the other's. Codex now has a dedicated `ProviderKind.OPENAI` (own vault slot, own default base URL/model); the dead Base URL field is removed from the Codex card because the bridge never used it.
- **Agent tabs are now icon-only (2026-10-14)**: square 56dp tabs with equal spacing and official brand icons (Antigravity, Claude Code, Codex, DeepSeek); Custom keeps its word, last in tab order. The selected tab gets a white background tile behind its icon; unselected tabs are transparent.
- **QA C1 fixed (2026-10-14)**: the code editor rendered a blank screen on every file open because the whole editor UI sat inside an unclosed `if (globalSearchOpen…)` block (the global-search dialog's closing brace was missing, so the rest of the screen only rendered while search was open). The `if` now closes right after the dialog.
- **QA C2 fixed (2026-10-14)**: the PENDING review verdict was invisible in light theme (light-grey `surfaceVariant` pill with unconditional white text). PENDING now uses theme `onSurfaceVariant` text; the dark verdict pills keep white text.
- **QA C3 fixed (2026-10-14)**: the App Factory keystore password shipped with the hardcoded default `pocketdev123`, shown in plaintext — in the Signing tab, and as a `pass = "pocketdev123"` default parameter in both `AppFactoryCoordinator` and `MainViewModel`, so any caller omitting the password silently reused it. The field now starts empty, is masked with a visibility toggle, is held in plain `remember` (not the saved-state bundle), and Generate stays disabled until the alias is non-blank and the password is at least 6 characters. `SigningAndVersionManager.createKeystore` also rejects passwords under 6 characters (keytool's own minimum) before spawning keytool. Covered by `SigningAndVersionManagerTest` (short/blank/blank-alias rejection + a real keytool generation).
- **QA C5 fixed (2026-10-14)**: the Play build still offered GitHub self-update ("Check now" downloaded an APK from GitHub and installed it outside Play), because `BuildConfig.IS_PLAY_BUILD` had zero Kotlin references. Self-update is now gated on it: `AppUpdater.check()` returns null and `download()` refuses on Play builds (single decision point `isSelfUpdateAllowed`), `RuntimeCoordinator` never checks or installs on Play builds, and Settings shows "Managed by Google Play" instead of Check now. Covered by `AppUpdaterTest` gate tests.
- **QA C6 + H19 fixed (2026-10-14)**: silent auto-update of tools/skills/agents defaulted to ON and replaced binaries with no backup, and per-item failures were swallowed so the message still claimed "up to date". Auto-update now defaults to OFF (the Settings toggle still turns it on), failures are collected and named in the final message, and agent updates back up the live binary before replacing it: Claude Code and Antigravity keep a `.previous` copy restored on verification failure (DeepSeek already rolled back), and Codex reinstalls its previous version on install/verify failure. The rollback paths need the Linux guest runtime, so no JVM unit test can exercise them — evidence is this diff + the CI build.
- **QA C7 fixed (2026-10-14)**: the SafetyGateway was complete but never constructed, and the Claude bridge built its own private policy engine — so the kill switch and permission toggles in Settings could never reach it. One shared engine now feeds one gateway constructed in MainViewModel; the Claude bridge takes that gateway and routes every per-tool approval through `gateway.authorize` (kill switch + path audit + MCP consult + engine), the kill switch blocks new main-chat and team-task sessions, and Settings states the honest scope (per-tool enforcement covers Claude Code; other agents run with their CLI defaults). Covered by `ClaudeRuntimeBridgeApprovalMappingTest` (approval-to-operation mapping) + the existing `SafetyGatewayTest` gateway suite.
- **QA C10 fixed (2026-10-14)**: MCP tool discovery/calls ran through `McpClientRunner`, which spoke the wrong wire protocol (LSP `Content-Length` framing, blank-line waits, per-call readers, no timeout, leaked processes) while the correct `McpToolRegistry` sat unused. The coordinator now routes discovery and calls through the registry (NDJSON, permission gating, audit logging, discovery cache, remote support, credential resolution); the runner is deleted, its two UI data classes moved to `McpTypes.kt`, and the registry gained the `stopServer` the remove flow needs. Covered by the existing `McpToolRegistryTest` suite (now testing the live path) + a new `stopServer` test.
- **QA C11 fixed (2026-10-14)**: paid listings installed free because a purchase gate existed with no payment flow behind it. Per the product decision the marketplace is fully free, so all paid concepts are deleted, not gated: `MarketplacePurchaseClient` + `MarketplaceSellerCenter` (+ tests) removed entirely, `MarketplacePrice`/`SellerProfile`/`PurchaseOrder`/`OrderStatus`/`EntitlementCheck`/`EntitlementVerdict`/`SalesRecord`/`SellerSalesReport` deleted, `priceCents`/`sellerId`/`maxPriceCents`/price sort removed, `RemoteListing.sellerName` renamed to `author` (old registry JSON still reads `sellerName` as `author` for backward compat). Covered by the updated `MarketplaceProtocolTest` / `MarketplaceListingValidatorTest` / `MarketplaceDiscoveryClientTest`.
- **QA C8 fixed (2026-10-14)**: publishing a marketplace item only added it to an in-memory list — it vanished on restart and never left the device. Published items are now validated (`validateMarketplaceItem`: name ≥3, description ≥20, install command/URL required), persisted durably in `MarketplacePublishedItemsStore`, and reloaded into the catalog on startup (dedupe by id). The dialog states the honest scope: no registry is configured in the app, so the item is saved on this device, not shared. Covered by `validateMarketplaceItem` tests; the SharedPreferences store itself needs Android Context and cannot run in a JVM unit test.
- **QA C9 fixed (2026-10-14)**: installing a marketplace MCP item materialized a package but never registered an MCP server, so the item was unusable. Installing an `MCP_SERVERS` item now registers it in `McpRegistry` (id `marketplace-<itemId>`, command/args split from the install value, refreshes the MCP list) and uninstalling removes that registration; items whose install value is blank or a download URL report honestly that no server was registered. Covered by `MarketplaceCoordinatorMcpMappingTest`.
- **Codex workspace-prompt test corrected (2026-10-14)**: the test asserted a containment sentence ("Do not create project output") that the real prompt never contained, so CI failed on a stale expectation. The test now asserts the sentence the prompt actually carries ("only inside this directory").

### 🛡️ Reliability

- **Keep-alive service**: a lightweight foreground service keeps the app alive when backgrounded, defeating aggressive OEM task killers (OnePlus, Xiaomi, Oppo, Vivo, Samsung). It starts automatically when you leave the app and stops when you return.
- **Firebase Crashlytics**: uncaught crashes now upload automatically to the Firebase console; no more manual log collection.
- **Startup crash fixes**: three init-order crashes fixed (`AgentRegistry` missing registration, two `MainViewModel` null-coordinator NPEs).
- **Back navigation fixed**: the system back button now closes overlay screens instead of killing the app.
- **Quick terminal sync**: the quick terminal now runs in the active project's workspace, matching the chat agent's working directory.

### 🔐 Auth and notifications

- **GitHub Sign-In**: added alongside Google sign-in, with compact round provider buttons on the auth screen.
- **Google Sign-In fixed**: the OAuth client ID now points at the correct Firebase project (`codeair-apps`).
- **Google Sign-In certificate fix**: the release signing key's SHA-1 and SHA-256 fingerprints are now registered in the Firebase console and `google-services.json` carries the matching Android OAuth clients, fixing the "package certificate hash" sign-in error on release builds.
- **FCM push notifications**: `MheMessagingService` receives pushes and the registration token is fetched and stored at startup; messaging is now shared across online and offline flavors.

---

## 📖 What is Mobile Harness Extended?

Mobile Harness Extended is an AI-powered software engineering environment that runs natively on Android. It combines a rootless Linux userspace (Ubuntu 20.04 on ARM64 via PRoot) with a full mobile IDE, multi-agent AI engineering teams, autonomous GitHub workflows, an Android app factory, and hybrid cloud execution.

The app ships in two editions:

| Edition | Size | Description |
|---|---|---|
| **Online** | ~150 MB | Lightweight. Downloads Linux runtime stacks on demand from verified releases. Best for devices with limited storage. |
| **Offline** | ~1.2 GB | Fully bundled. All toolchains (core, Python, Android SDK, Claude Code, DeepSeek, Antigravity) ship inside the APK. Works without network after install. |

Both editions share the same application ID (`com.codeair.mhe`) and the same feature set; only the runtime delivery differs.

---

## ✨ Features

### 📝 Mobile IDE

- **Multi-tab code workspace** with pinned tabs and state restoration across Android backgrounding
- **Touch-optimized coding toolbar**: auto-pairing brackets, indent controls, comment toggling, quick cursor navigation
- **Universal command palette** for instant navigation between files, chats, terminals, git diffs, and tools
- **Global search and replace** with regex, case sensitivity, and preview-before-replace
- **Crash-safe file operations** using atomic staging transactions

### 💻 Rootless Linux Terminal

- Real Ubuntu 20.04 ARM64 userspace via PRoot - zero root required
- Node.js, Python, Git, Clang/LLVM, Bash/Zsh toolchains
- Persistent terminal sessions, process lifecycle management, and workspace checkpoints/snapshots
- Quick terminal follows the active project's workspace automatically

### 🧠 AI Coding Assistance

- **Multi-provider gateway**: Anthropic Claude, DeepSeek, OpenRouter, and OpenAI-compatible endpoints with automatic 401/quota failover across accounts
- **Intent classification** (bug fix, feature, refactor, review, explanation, test writing)
- **Context engine** with token-budget-aware relevance ranking over symbols, diffs, and error traces
- **Persistent project memory** retaining architectural decisions and bug history across sessions
- **MCP (Model Context Protocol)** support: connect remote MCP servers, manage server processes, and use a secure credential store
- **Skill packages**: installable expert skill bundles (Compose architect, DevOps packager, and more)

### 🤖 Multi-Agent Engineering Team

- 10 specialized roles: Manager, System Architect, Frontend, Backend, QA, DevOps, Security Auditor, DBA, Docs Writer, UX Designer
- **Real execution**: every role runs genuine agent sessions through the installed runtimes; results are verified, never simulated
- Concurrent DAG task scheduler with per-file locking, budget quotas, and cancellation
- Shared blackboard memory, typed inter-agent messaging, and peer review gates
- Live execution status in the studio header; honest `NOT_CONFIGURED` reporting when a runtime or key is missing

### 🔁 Autonomous GitHub Workflows

- **Issue triage to pull request**: analyzes issues, reproduces bugs, creates branches, edits code, runs real Gradle builds and tests, and opens documented PRs
- **PR automation**: policy-gated auto-merge (CI green, required approvals), reviewer requests, merge-gate status checks
- **Issue management**: rule-based labeling, assignment, and status comments
- **Release automation**: tag creation, generated release notes, asset uploads
- **Workflow dispatch**: trigger and monitor GitHub Actions runs from the app

### 🔍 Semantic Code Intelligence

- AST parsing for Kotlin, Java, TypeScript, JavaScript, Python, Go, Rust, C/C++, and Shell
- Symbol outline, call graphs, and high-speed semantic indexing
- LSP protocol client with real-server detection (honest fallback labeling)

### 🛡️ Safety Engine

- Dangerous command interception (`rm -rf /`, disk formats, fork bombs)
- Sensitive file shielding for `.env`, SSH keys, and keystores
- Granular permission scopes (Once, Session, Project, Always, Deny) with an emergency kill switch and audit logging

### 🎨 Visual Development

- Embedded Chromium preview with live DOM inspector and computed CSS view
- Multi-device viewport presets and localhost port discovery
- Screenshot-based visual dev loop with pixel-diff verification

### 🏭 Android App Factory

- AI app blueprints: screens, navigation, data layer, and state models from a high-level concept
- Project scaffolding for Jetpack Compose, Flutter, React Native, and HTML5 web apps
- On-device Gradle APK builds, APK signing verification, release keystores, and Play Store listing preparation

### ☁️ Cloud Hybrid Execution

- Authenticated cloud worker protocol: submit jobs, stream logs, poll status, cancel
- Delta payload uploads (only changed files, SHA-256 verified), artifact download verification
- Cost controls: per-job estimates and rolling 30-day budget caps enforced before dispatch

### 🛒 AI Developer Marketplace

- Curated catalog of agents, MCP servers, skills, and starter templates with one-click install
- Listing validation, TTL-cached discovery, and purchase flow with local entitlement ledger
- Seller center: register, publish, update, unpublish, and view sales reports

### 🔔 Push Notifications

- Firebase Cloud Messaging receiver with token registration at startup
- Available in both online and offline flavors

---

## 📋 System Requirements

| Requirement | Minimum | Recommended |
|---|---|---|
| Android version | 9.0 (API 28) | 12+ |
| CPU architecture | ARM64 (aarch64) | ARM64 |
| RAM | 3 GB | 4 GB+ (large builds, multi-agent runs) |
| Free storage | 2 GB | 4 GB (projects, build caches) |
| Root | Not required | - |

The app runs 100% in rootless userspace. No root, no custom ROM, no special permissions beyond normal app sandboxing.

---

## 📲 Installation

1. Go to the [v3.0.1 release page](https://github.com/sunmughan/Mobile-Harness-Extended/releases/tag/v3.0.1).
2. Download the edition you want:
   - `app-online-release.apk` - lightweight, downloads runtimes on demand
   - `app-offline-release.apk` - everything bundled, works offline after install
3. On your phone, allow **Install unknown apps** for your browser or file manager when prompted.
4. Open the APK and tap **Install**.
5. (Online edition) On first launch, the app downloads the verified Linux runtime stacks. Keep the app open and on Wi-Fi during this step.

To update, simply install the newer APK over the existing one. Your projects and settings are preserved (the release APKs are signed with the same permanent release key, so in-place updates always work).

---

## 🔨 Building from Source

<details>
<summary><b>Prerequisites</b></summary>

| Tool | Required version | Notes |
|---|---|---|
| JDK | 17 (Temurin recommended) | `java -version` must report 17 |
| Android SDK command-line tools | latest | Set `ANDROID_HOME` / `ANDROID_SDK_ROOT` |
| Android platform | `platforms;android-36` | Compile SDK |
| Android build-tools | `35.0.0` | Used for `apksigner` / `aapt2` in CI |
| Android NDK | `26.1.10909125` (r26b) | Pinned in `app/build.gradle.kts` |
| CMake | `3.22.1` | Only needed when building native code on non-ARM64 hosts |
| Gradle | via wrapper | Do not install separately; use `./gradlew` |
| Git | any recent | With submodule support |
| Disk space | 15 GB free | SDK + NDK + build outputs |

Install the SDK packages (the same command CI uses):

```bash
sdkmanager "platform-tools" "platforms;android-36" "ndk;26.1.10909125" "cmake;3.22.1"
yes | sdkmanager --licenses
```

Key versions used by the project: Android Gradle Plugin `8.13.2`, Kotlin `2.1.0`, Compose BOM `2025.02.00`, Firebase BOM `34.19.0`.

</details>

<details>
<summary><b>Clone</b></summary>

```bash
git clone https://github.com/sunmughan/Mobile-Harness-Extended.git
cd Mobile-Harness-Extended
git checkout v3
git submodule update --init --recursive
```

</details>

<details>
<summary><b>Debug builds</b></summary>

```bash
# Online flavor (lightweight, runtimes downloaded on demand)
./gradlew assembleOnlineDebug

# Offline flavor (runtimes bundled into the APK)
./gradlew assembleOfflineDebug
```

APKs land in `app/build/outputs/apk/<flavor>/debug/`.

</details>

<details>
<summary><b>Release builds</b></summary>

Release builds require the signing key (see **Release signing** below). With the environment variables set:

```bash
./gradlew assembleOnlineRelease
./gradlew assembleOfflineRelease
```

</details>

<details>
<summary><b>Running tests</b></summary>

```bash
# Full online unit test suite (1000+ tests)
./gradlew testOnlineDebugUnitTest

# Offline flavor tests
./gradlew testOfflineDebugUnitTest
```

</details>

### Build variants

| Task | Flavor | Build type | Output |
|---|---|---|---|
| `assembleOnlineDebug` | online | debug | `app/build/outputs/apk/online/debug/` |
| `assembleOfflineDebug` | offline | debug | `app/build/outputs/apk/offline/debug/` |
| `assembleOnlineRelease` | online | release | `app/build/outputs/apk/online/release/` |
| `assembleOfflineRelease` | offline | release | `app/build/outputs/apk/offline/release/` |
| `testOnlineDebugUnitTest` | online | debug (tests) | test report |
| `testOfflineDebugUnitTest` | offline | debug (tests) | test report |

Flavor behavior is controlled by `BuildConfig.OFFLINE_RUNTIME_BUNDLES` and `BuildConfig.APP_VARIANT`. A Play-targeted build is available with `-PplayBuild=true` (targets SDK 36 and runs the Play readiness check via `./gradlew playReadinessCheck -PplayBuild=true`).

### Runtime bundles note

The offline flavor bundles Linux runtime archives (`pocketdev-*-arm64-*.tar.zst`). CI downloads the latest verified bundles from the `techjarves/Mobile-Harness` `runtime-*` releases into `dist/runtime-bundles/` before building. For local offline builds, place the bundle archives plus a `manifest.json` in `dist/runtime-bundles/`; the `prepareOfflineRuntimeAssets` task picks them up automatically.

---

## ⚙️ Configuration

<details>
<summary><b>Firebase setup</b></summary>

The project uses Firebase for authentication, push notifications, and crash reporting.

1. Create a project in the [Firebase console](https://console.firebase.google.com/).
2. Add an Android app with package name `com.codeair.mhe`.
3. Download `google-services.json` and place it at **`app/google-services.json`** (this exact path; the `com.google.gms.google-services` Gradle plugin reads it from there).
4. The plugin is already applied in `app/build.gradle.kts`; no further Gradle changes are needed.

The repository ships with a `google-services.json` for the `codeair-apps` Firebase project. Replace it with your own file to point the app at your project.

</details>

<details>
<summary><b>Firebase Auth</b></summary>

Enable the sign-in methods you want in **Firebase console > Authentication > Sign-in method**:

- **Email/Password** - used for app accounts
- **Google** - one-tap Google sign-in via the AndroidX Credentials API (`androidx.credentials`, `googleid` libraries are already wired)
- **GitHub** - OAuth sign-in via `OAuthProvider("github.com")`; add your GitHub OAuth client ID and secret in the Firebase console, with the authorized callback URL `https://<your-project>.firebaseapp.com/__/auth/handler`

No client-side configuration beyond `google-services.json` is required.

</details>

<details>
<summary><b>API keys and secrets</b></summary>

| Secret | Where it goes | Used for |
|---|---|---|
| `openrouter.apiKey` | `test-secrets.properties` at repo root (gitignored) | Debug-build AI provider testing via `BuildConfig.TEST_OPENROUTER_API_KEY` |
| AI provider keys (Anthropic, OpenAI, etc.) | Entered in-app under provider settings | Stored in the encrypted in-app credential store, never in the repo |
| `MH_RELEASE_*` | Environment variables / CI secrets | Release APK signing (below) |

Create `test-secrets.properties` for local debug builds that exercise AI providers:

```properties
openrouter.apiKey=sk-or-v1-your-key-here
```

The file is optional; builds succeed without it (the key defaults to empty).

</details>

<details>
<summary><b>Release signing</b></summary>

Release APKs are signed with a permanent upload key. Provide it through environment variables (CI reads them from GitHub Actions secrets):

| Environment variable | Purpose |
|---|---|
| `MH_UPLOAD_STORE_FILE` | Path to the `.jks` keystore file |
| `MH_UPLOAD_STORE_PASSWORD` | Keystore password |
| `MH_UPLOAD_KEY_ALIAS` | Key alias |
| `MH_UPLOAD_KEY_PASSWORD` | Key password |

When all four are set, the `upload` signing config is applied to release builds automatically. CI verifies the keystore's SHA-256 certificate fingerprint before building, so a wrong key fails fast instead of shipping a mismatched signature. Never commit keystores or passwords to the repository.

</details>

---

## 🗂️ Project Structure

```
Mobile-Harness-Extended/
├── app/
│   ├── build.gradle.kts          # App module: flavors, signing, Firebase, versions
│   ├── google-services.json      # Firebase config (replace with your own)
│   └── src/main/
│       ├── java/com/codeair/mhe/
│       │   ├── agent/            # Multi-agent team runtime, DAG dispatch, roles
│       │   ├── auth/             # Firebase Auth identity, Google/GitHub sign-in
│       │   ├── cloud/            # Cloud hybrid execution client and orchestration
│       │   ├── context/          # AI context engine: ranking, budgets, memory
│       │   ├── core/             # OperationResult integrity contract, utilities
│       │   ├── editor/           # Code editor, tabs, search, command palette
│       │   ├── factory/          # Android App Factory: blueprints, scaffolding, builds
│       │   ├── git/              # GitHub automation: workflows, PRs, issues, releases
│       │   ├── lsp/              # Language server protocol client
│       │   ├── marketplace/      # Marketplace: listing, discovery, purchasing
│       │   ├── mcp/              # Model Context Protocol client and servers
│       │   ├── network/          # GitHub REST client, transports
│       │   ├── runtime/          # PRoot Linux runtime installer and sessions
│       │   ├── security/         # Safety gateway, permission engine, auditing
│       │   ├── skills/           # Skill packages
│       │   ├── terminal/         # Terminal emulator and shell sessions
│       │   ├── ui/               # Jetpack Compose screens and ViewModels
│       │   ├── visual/           # Visual dev loop, DOM tools, screenshot diffing
│       │   └── workflow/         # Autonomous agent workflow engine
│       └── res/                  # Android resources
├── .github/workflows/android.yml # CI: build, test, sign, verify, release
├── V3_PROGRESS.md                # Engineering milestone log
├── PRIVACY.md                    # Privacy policy
└── LICENSE                       # MIT
```

---

## 🏗️ Architecture Overview

```
+-------------------------------------------------------------------+
|              Mobile Harness Extended (v3.0.1)                      |
|                                                                    |
|  +------------------+  +-----------------+  +------------------+   |
|  | Mobile IDE       |  | Visual DevTools |  | Android App      |   |
|  | Editor + Tabs    |  | Chromium + DOM  |  | Factory          |   |
|  | Search + Palette |  | Inspector       |  | Scaffold + Build |   |
|  +------------------+  +-----------------+  +------------------+   |
|                                                                    |
|  +--------------------------------------------------------------+  |
|  |         Multi-Agent Team Orchestration Engine (REAL)         |  |
|  |  Manager > Architect > Specialists > QA > Security Review    |  |
|  |  Real per-role execution - DAG scheduler - Blackboard        |  |
|  |  Honest NOT_CONFIGURED states - no simulated results         |  |
|  +--------------------------------------------------------------+  |
|                                                                    |
|  +---------------+  +----------------+  +----------------------+   |
|  | Cloud Hybrid  |  | AI Context     |  | Marketplace          |   |
|  | Execution     |  | Engine + MCP   |  | Agents, Skills,      |   |
|  | Cost controls |  | Multi-provider |  | Templates            |   |
|  +---------------+  +----------------+  +----------------------+   |
|                                                                    |
|  +--------------------------------------------------------------+  |
|  |              Safety Engine                                   |  |
|  |  Command interception - File shielding - Permissions - Audit |  |
|  +--------------------------------------------------------------+  |
|                                                                    |
|  +--------------------------------------------------------------+  |
|  |        PRoot Linux Userspace (Ubuntu 20.04 ARM64)             |  |
|  |  Node.js - Python - Git - Clang - No root required           |  |
|  +--------------------------------------------------------------+  |
|                                                                    |
|  +--------------------------------------------------------------+  |
|  |     Firebase (Auth + FCM + Crashlytics)          |  |
|  |  Identity - Notifications - Crash Reporting     |  |
|  +--------------------------------------------------------------+  |
+-------------------------------------------------------------------+
```

---

## 🔒 Security and Privacy

1. **Rootless isolation**: all compiler and shell execution runs inside an isolated PRoot Linux userspace. Host Android partitions remain protected.
2. **Dangerous command interception**: real-time inspection blocks destructive commands (`rm -rf /`, disk formats, fork bombs) before execution.
3. **Sensitive file protection**: `.env` files, SSH keys, certificates, and keystores are shielded from agent transcripts and external outputs.
4. **Local-first processing**: code parsing, AST indexing, git operations, and test runs happen on-device.
5. **Honest status reporting**: every subsystem reports verified outcomes only. Unconfigured backends report `NOT_CONFIGURED`; simulations are labeled `SIMULATED` and never count as success.

See [PRIVACY.md](PRIVACY.md) for the full privacy policy.

---

## 🤝 Contributing

1. Fork the repository and create a feature branch (`git checkout -b feature/my-change`).
2. Make your changes with tests. New features must include unit tests; pure-JVM tests are preferred (see existing `*Test.kt` files for the pattern).
3. Run the full verification locally: `./gradlew testOnlineDebugUnitTest assembleOnlineDebug`.
4. Open a pull request against `v3`. CI must be fully green before merge.
5. Follow the `OperationResult` honesty contract: never report success without a verified result, and never commit secrets or keystores.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
Copyright (c) 2024-2026 Mobile Harness Extended Contributors.
