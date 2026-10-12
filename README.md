# Mobile Harness Extended (v3.0.0)

<div align="center">

### *Desktop-Class AI Software Engineering IDE on Android*

**Run real Linux development toolchains, deploy multi-agent engineering teams, edit source code with desktop-grade IDE ergonomics, offload workloads hybridly to the cloud, compile Android APKs, inspect live DOM trees, and ship tested pull requests — directly from your phone.**

<br />

[![Release v3.0.0](https://img.shields.io/badge/Release-v3.0.0-F28C52?style=flat-square&logo=github&logoColor=white)](https://github.com/sunmughan/Mobile-Harness-Extended/releases/tag/v3.0.0)
[![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#system-requirements)
[![CPU ARM64](https://img.shields.io/badge/CPU-ARM64-5B8DEF?style=flat-square)](#system-requirements)
[![Kotlin 2.1](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![MIT License](https://img.shields.io/badge/License-MIT-8B7CF6?style=flat-square&logo=opensourceinitiative&logoColor=white)](LICENSE)

<br />

[**Download Online APK (v3.0.0)**](https://github.com/sunmughan/Mobile-Harness-Extended/releases/download/v3.0.0/app-online-release.apk) &nbsp;•&nbsp;
[**Download Offline APK (v3.0.0)**](https://github.com/sunmughan/Mobile-Harness-Extended/releases/download/v3.0.0/app-offline-release.apk) &nbsp;•&nbsp;
[**Architecture**](#architecture-overview) &nbsp;•&nbsp;
[**All 14 Phases**](#the-14-engineering-phases-v300) &nbsp;•&nbsp;
[**Build from Source**](#building-from-source)

</div>

---

> [!NOTE]
> **Major Milestone Release — v3.0.0 (versionCode `32`):**
> Mobile Harness Extended v3.0.0 transforms your Android device into a complete, autonomous software engineering workstation. Powered by a rootless PRoot Linux userspace, a 10-role Multi-Agent Engineering Team, an Autonomous Issue-to-PR pipeline, a Cloud + Phone Hybrid Coordinator, an Android App Factory, and a curated AI Developer Marketplace, v3.0.0 delivers desktop-level capability without requiring root access.

---

## Capability Status (v3 — Milestone 0 integrity gate)

Honest labels for what each area actually does today. A feature is only
`IMPLEMENTED` when it executes real work end-to-end; anything simulated is
labelled `SIMULATED` and never reports success.

| Area | Status | What it really means |
|---|---|---|
| PRoot Linux runtime, terminal, editor, checkpoints, sessions | IMPLEMENTED | Real on-device execution, process/session lifecycle, workspace snapshots |
| Semantic code index, completions, diagnostics | PARTIAL | Real regex/symbol engine; **no real language-server process** — the built-in fallback is labelled as fallback, never "server running" |
| Autonomous GitHub Issue-to-PR | PARTIAL | Real Git ops + real PR API; the agent step awaits genuine completion and the build step runs **real Gradle build+test**; blank token or empty diff fails fast |
| Multi-Agent Engineering Team | SIMULATED | **Demo mode**: roles, DAG, blackboard and review structures exist, but no real agent executes per role; simulated test/security outputs are labelled and never count as approval |
| Cloud + Phone Hybrid | SIMULATED | Routing/worker/job models exist; **no real cloud backend is configured** — jobs return `NOT_CONFIGURED`, never fake success; local fallback reports real exit codes |
| Team Collaboration sync | PARTIAL | Local session/share models and RBAC calculations; no shared backend or cross-device sync |
| AI Developer Marketplace | PARTIAL | Real catalog + durable install manager (packages materialized on disk); no hosted publisher service yet |

---

## The 14 Engineering Phases (v3.0.0)

Mobile Harness Extended v3.0.0 is the culmination of 14 core engineering phases designed to bring high-end AI software engineering into a mobile form factor:

### Phase 1: Real Mobile IDE & Code Editor Foundation
- **Multi-Tab Code Workspace:** Desktop-class tab management supporting simultaneous open files, sticky pin states, and tab state restoration across Android backgrounding.
- **Touch-Optimized Coding Toolbar:** Instant auto-pairing bracket insertion (`{}`, `[]`, `()`), indentation controls (Tab / Shift-Tab), single/multi-line comment toggling, and quick cursor navigation.
- **Universal Command Palette (`Ctrl+P` / Action Bar):** Instant fuzzy-search command palette to navigate between files, chat sessions, terminals, git diffs, app factories, and tools.
- **Global Search & Replace Engine:** Multi-file regex query engine with match highlighting, case sensitivity toggling, and preview-before-replace safeguards.
- **Crash-Safe File Operations:** Atomic staging transactions (`SafeFileOps`) preventing corruption during power cuts or OS process eviction.

### Phase 2: Multi-Language Semantic Code Intelligence
- **9-Language AST Parsing Engine:** Real-time Abstract Syntax Tree parsing for Kotlin, Java, TypeScript, JavaScript, Python, Go, Rust, C/C++, and Shell scripts.
- **Symbol Outline & Call Graph:** Extracts classes, interfaces, methods, functions, and properties with hierarchical nesting, forward call discovery, and inverse caller tracing.
- **Semantic Code Indexing:** High-speed token indexing enabling instant symbol definitions, usage finding, and context injection into LLM prompts.

### Phase 3: AI Context Engine 2.0 & Persistent Memory
- **Automatic Intent Classification:** Real-time intent detection categorizing requests (`BUG_FIX`, `FEATURE_IMPLEMENTATION`, `REFACTORING`, `CODE_REVIEW`, `EXPLANATION`, `TEST_WRITING`).
- **Token Budget Relevance Ranking:** Ranks symbol graph nodes, recent git diffs, error traces, and active file buffers to compose optimal prompts within strict context windows.
- **Multi-Tier Project Memory:** Persistent memory store retaining architectural patterns, design decisions, resolved issues, and bug histories across agent sessions.

### Phase 4: Autonomous Agent Orchestrator & Self-Healing Loop
- **14-State Engineering Lifecycle:** Manages engineering workflows through deterministic state machines (`UNDERSTAND` → `PLAN` → `IMPLEMENT` → `BUILD` → `TEST` → `ANALYZE_FAILURE` → `FIX` → `RETEST` → `REVIEW` → `APPROVE`).
- **Compiler Diagnostics Parser:** Analyzes stdout/stderr from Gradle, GCC, Clang, npm, pytest, and rustc to pinpoint exact file, line, and syntax error locations.
- **Self-Healing Iterative Repair:** Automatically catches test failures, formulates corrective diffs, applies fixes, and re-runs test suites until 100% passing.
- **Baseline Checkpoint Rollback:** Automatic snapshot creation before each task, enabling instant rollback if unexpected regressions occur.

### Phase 5: Granular Permission & Safety Engine
- **Destructive Command Defense (`DangerousCommandDetector`):** Proactively intercepts and blocks root-level wipes (`rm -rf /`), raw disk formats (`mkfs`, `dd`), and fork bombs before execution.
- **Sensitive File Shield:** Prevents exposure of `.env` secret keys, SSH identities, keystore certificates, and private credentials to external outputs.
- **11 Permission Categories & 5 Scopes:** Granular control across file writes, package installs, network calls, git pushes, and terminal executions with `ONCE`, `SESSION`, `PROJECT`, `ALWAYS`, and `DENY` scopes.
- **Emergency Kill Switch & Audit Logging:** Hardware-responsive kill switch instantly terminating active agent loops, backed by crash-safe audit logs.

### Phase 6: Multi-Account OAuth & Provider Failover
- **Google OAuth Antigravity Bridge:** Native Google OAuth sign-in supporting Claude, DeepSeek, and Antigravity models.
- **Zero-Downtime 401 Session Failover:** Detects token expiration and quota rate limits, automatically rotating to alternative authenticated accounts without interrupting background jobs.
- **Multi-Provider Unified Gateway:** Connects seamlessly with Anthropic Claude Code, DeepSeek Coder, Antigravity, OpenRouter, and custom OpenAI-compatible endpoints.

### Phase 7: Autonomous GitHub Issue-to-PR Engine
> **Integrity (v3):** the workflow now fails fast on a blank GitHub token, waits for the real agent result (no fire-and-forget), runs a **real Gradle build + test** instead of a canned pass, and refuses empty diffs. Real API errors surface to the UI.
- **Native GitHub Integration:** Bundled `gh` CLI and GitHub REST API integration with OAuth token authentication and repository context indexing.
- **Autonomous Issue Triage:** Analyzes issue descriptions, reproduces bug conditions, extracts stack traces, and creates dedicated feature/fix branches.
- **Automated Verification & Pull Request Submission:** Edits code, executes the test suite offline, commits changes with conventional commit messages, pushes to origin, and opens a fully documented pull request.

### Phase 8: Real Visual Dev Environment & DOM Inspector
- **Embedded Chromium Webview Runtime:** In-app browser engine designed to preview and interact with local frontend servers (Vite, Next.js, React, Vue, HTML/CSS).
- **Interactive DOM Inspector:** Select and inspect live DOM elements, view computed CSS properties, and inspect layout dimensions.
- **Multi-Device Responsive Sizing:** Test viewports instantly with Mobile, Tablet, and Desktop presets.
- **Localhost Port Discovery:** Proactively scans active localhost ports and exposes instant reverse proxies.
- **Visual Context Capture:** One-click visual snapshot attachment injecting DOM nodes and screen state into agent prompts.

### Phase 9: Android App Factory v2.0
- **AI App Blueprint Engine:** Converts high-level app concepts into complete architectural blueprints specifying screens, navigation graphs, data layers, and state models.
- **Multi-Framework Project Scaffolding:** Generates production-ready starter projects for Jetpack Compose (Kotlin), Flutter (Dart), React Native (TypeScript), and HTML5 WebApps.
- **Native Gradle APK Builder:** Executes Gradle build daemons in PRoot to compile and package standalone debug APKs on-device.
- **Release Packaging & Play Store Bundle:** Generates release keystores, signs APKs/AABs, handles version bumping, and generates Play Store listing metadata.

### Phase 10: Multi-Agent Engineering Team Architecture
- **10 Specialized Autonomous Roles:**
  1. **Manager:** Requirement decomposition, task assignment, budget monitoring, and milestone approval.
  2. **System Architect:** High-level system design, module boundaries, tech stack selection, and interface contracts.
  3. **Frontend Specialist:** UI layout, design systems, responsive typography, state management, and user interaction.
  4. **Backend Specialist:** Server APIs, data validation, business logic, persistence, and external service connectors.
  5. **QA Engineer:** Unit testing, integration assertions, edge-case coverage, and regression prevention.
  6. **DevOps Lead:** Build automation, container configurations, dependency version pinning, and CI pipelines.
  7. **Security Auditor:** OWASP compliance, injection protection, permission checking, and secret masking.
  8. **Database Admin:** Schema modeling, migration integrity, query indexing, and data consistency.
  9. **Technical Docs Writer:** Architecture documentation, API specifications, inline docstrings, and changelogs.
  10. **UX Designer:** Accessibility compliance, tactile ergonomics, navigation flow, and visual hierarchy.

### Phase 11: Multi-Agent Team Orchestration & Studio UI
> **Demo mode (v3):** the team structures, DAG scheduler, blackboard, and review gates are real, but no autonomous agent executes per role yet — task and review outputs are simulated, labelled `SIMULATED`, and never count as test/security approval.
- **Shared Blackboard Architecture:** Unified memory structure where all agent roles post proposals, review feedbacks, and merge artifacts.
- **Directed Acyclic Graph (DAG) Task Scheduler:** Resolves task dependencies, executes parallel subtasks, and synchronizes milestones.
- **Asynchronous Communication Bus:** Type-safe message broker enabling inter-agent messaging, escalation, and peer handoffs.
- **Peer Review Gates:** Requires mandatory QA and Security approvals before code proposals merge into the main branch.
- **Team Studio UI:** Dedicated visual dashboard displaying live agent states, role badges, active tasks, log streams, and token usage meters.

### Phase 12: Cloud + Phone Hybrid Coordinator
> **Not configured (v3):** no cloud worker backend ships with the app. Jobs report `NOT_CONFIGURED` instead of fake success; the local fallback runs the real command and reports its real exit code.
- **Workload Decision Engine:** Dynamically calculates whether to execute tasks locally on the phone or offload them to remote cloud workers.
- **Environmental Telemetry:** Monitors battery percentage, charging state, thermal throttling state, available RAM, and network throughput.
- **Remote Build Runners:** Offloads CPU-intensive Gradle compilation, Docker packaging, and heavyweight model training to cloud runners.
- **FastBuild Artifact Caching:** Synchronizes compilation artifacts and build caches back to the local project without manual transfers.

### Phase 13: Team Collaboration & Workspace Sync
- **Multi-Tenant Workspaces:** Support for personal and organization workspaces with role-based access control (RBAC).
- **Four-Tier RBAC Permissions:** Granular roles (`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`) managing code editing, terminal execution, and settings changes.
- **Inline Code Comments & Reviews:** Threaded code review discussions on specific file lines with resolution tracking.
- **Real-Time Activity Audit Trails:** Searchable event logs tracking file modifications, command invocations, and role promotions.

### Phase 14: AI Developer Marketplace
- **Curated Multi-Category Catalog:** Discover and 1-click install verified developer extensions:
  - **AI Agents:** Claude 3.7 Sonnet, DeepSeek R1, Gemini 2.5 Pro, Llama 3.3 70B Code Specialist.
  - **MCP Servers:** PostgreSQL MCP, Puppeteer Browser MCP, GitHub Context MCP, Filesystem MCP, Brave Search MCP.
  - **Specialized Skills:** Jetpack Compose Architect, DevOps & Docker Packager, Next.js 15 Wizard, Rust Systems Optimization.
  - **Starter Templates:** Android Kotlin Starter, FastAPI Microservice, React Native Expo, Rust Axum API.
- **Custom Tool Publishing:** Package, register, and distribute custom tools and skills directly into your workspace.
- **Install/Uninstall Lifecycle:** Full tracking of active tools, version tags, and 1-click uninstallation.

---

## Architecture Overview

```
+-----------------------------------------------------------------------------------+
|                           Mobile Harness Extended (v3.0.0)                         |
|                                                                                   |
|  +--------------------+  +-----------------------+  +--------------------------+  |
|  | Desktop Mobile IDE |  |  Visual DevTools DOM  |  |  Android App Factory     |  |
|  | - Monaco Editor    |  | - Embedded Chromium   |  | - Compose / Flutter / RN |  |
|  | - Tab Manager      |  | - Live DOM Inspector  |  | - Scaffolding Engine     |  |
|  | - Global Search    |  | - Port Scanner Proxy  |  | - Native Gradle APK      |  |
|  +--------------------+  +-----------------------+  +--------------------------+  |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  |                  Multi-Agent Team Orchestration Engine (Phases 10-11)        |  |
|  |  [Manager] -> [Architect] -> [Frontend/Backend] -> [QA] -> [Security]      |  |
|  |  * Shared Blackboard Memory  * Task DAG Scheduler  * Token Budget Tracker   |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|  +---------------------------+  +---------------------+  +---------------------+  |
|  | Cloud + Phone Hybrid      |  | AI Context Engine   |  | AI Dev Marketplace  |  |
|  | - Battery/Thermal Router  |  | - Intent Classifier |  | - Agents & MCP     |  |
|  | - Remote Build Offloader  |  | - AST Symbol Graph  |  | - Skills & Starters |  |
|  | - FastBuild Cache Sync    |  | - Persistent Memory |  | - Custom Publisher  |  |
|  +---------------------------+  +---------------------+  +---------------------+  |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  |                     Granular Permission & Safety Engine                     |  |
|  |  * Dangerous Command Interceptor  * Sensitive File Shield  * Audit Logger   |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  |                   PRoot Userspace Subsystem (Ubuntu 20.04 ARM64)            |  |
|  |  * Node.js  * Python  * Git  * Clang/LLVM  * Bash/Zsh  * Zero Root Required |  |
|  +-----------------------------------------------------------------------------+  |
+-----------------------------------------------------------------------------------+
```

---

## System Requirements

- **Operating System:** Android 9.0 (API 28) or higher
- **CPU Architecture:** ARM64 (aarch64)
- **RAM:** Minimum 3 GB (4 GB+ recommended for large builds and multi-agent coordination)
- **Storage:**
  - Online Edition: ~150 MB initial (downloads stacks on demand)
  - Offline Edition: ~1.2 GB (all toolchains pre-bundled)
  - Free space: 2 to 4 GB recommended for projects and build caches
- **Root Requirement:** **None.** Runs 100% in rootless userspace via PRoot.

---

## Building from Source

### Prerequisites
- JDK 17 or higher
- Android SDK (API 36 compile, API 28 min)
- Android NDK `26.1.10909125` (r26b)

### Build Commands
```bash
# Clone the repository
git clone https://github.com/sunmughan/Mobile-Harness-Extended.git
cd Mobile-Harness-Extended

# Run the complete offline unit test suite (440+ tests)
./gradlew testOfflineDebugUnitTest

# Build the Online APK (Lightweight)
./gradlew assembleOnlineDebug

# Build the Offline APK (Fully Bundled Runtimes)
./gradlew assembleOfflineDebug
```

---

## Security & Privacy Architecture

1. **Rootless Isolation:** All compiler and shell executions run inside an isolated PRoot Linux userspace container. Host Android system partitions (`/system`, `/vendor`, `/data/data`) remain protected.
2. **Dangerous Command Interceptor:** Real-time regex and token inspection prevents destructive commands like `rm -rf /`, `mkfs.*`, disk formatting, and system-level tampering.
3. **Sensitive File Protection:** Automatic shielding protects `.env`, private SSH keys, `.pem` certificates, and Android keystores from being emitted or printed into agent transcripts.
4. **Local-First Processing:** Code parsing, AST symbol extraction, git repository management, and offline test executions occur entirely on-device without third-party telemetries.

---

## License

This project is licensed under the [MIT License](LICENSE).
Copyright (c) 2024-2026 Mobile Harness Extended Contributors.
