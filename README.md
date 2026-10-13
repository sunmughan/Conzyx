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
| Real agent execution | The Multi-Agent Engineering Studio now dispatches genuine agent runs through your installed runtimes (Claude Code, DeepSeek, Antigravity, Cloud Code). The old simulated runs are gone. |
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
- **Status bar respected**: the app no longer overrides status bar or navigation bar colors; your device defaults are left alone.
- **Browser redesigned**: bigger URL bar, top row reduced to URL bar + menu, ports strip and unused pills removed.
- **Files header fixed**: the "Files" title shared one row with 7 action buttons and wrapped vertically on narrow phones. The title now sits on its own top-left line with the full icon row below it.
- **Prompt-send crash fixed**: the native `pocket_spawn.c` still exported JNI symbols under the old upstream package (`Java_com_jarves_mh_runtime_NativeSpawn_*`) while the Kotlin class lives at `com.codeair.mhe.runtime.NativeSpawn`, so every agent session died at spawn time with "No implementation found". All three JNI entry points (spawn/waitFor/kill) renamed to the `com.codeair.mhe` package.
- **Full-app audit fixes (2026-10-13)**: duplicate FCM services removed in the online flavor (every push was delivered twice); `System.loadLibrary("pocketspawn")` now throws a clear reinstall message instead of a raw linker error; `scripts/build-play-release.sh` no longer defaults to the upstream author's `/Users/jarves/` keystore path; prebuilt `libpocketspawn.so` rebuilt from the fixed C source so ARM64-host builds don't reintroduce the JNI crash.
- **Agent screen fixes (2026-10-13)**: agent tab names are now centered in their own columns; the Runtime updates card shows only the currently viewed tab's agent updates (Antigravity/DeepSeek/Claude/Cloud each get their own checker); new **Custom** agent tab — bring your own OpenAI-compatible or Claude-compatible API key (base URL + key + model), no binary download or install needed, chats directly via your API.
- **Status bar icons fix (2026-10-13)**: system status/navigation bar icons now follow the actual app theme — white icons in dark mode, dark icons in light mode. Previously the light XML theme forced dark icons even when the app rendered dark, making them invisible on the dark background.

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

Mobile Harness Extended is an AI-powered software engineering environment that runs natively on Android. It combines a rootless Linux userspace (Ubuntu 20.04 on ARM64 via PRoot) with a full mobile IDE, multi-agent AI engineering teams, autonomous GitHub workflows, an Android app factory, hybrid cloud execution, and real-time team collaboration backed by Firebase.

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

### 👥 Team Collaboration (Firebase)

- Firebase Auth identity (email, Google, and GitHub sign-in)
- Multi-tenant workspaces with role-based access (Owner, Maintainer, Contributor, Viewer)
- Invites, presence heartbeats, and version-vector document sync with conflict resolution
- Auth-anchored Firestore security rules (shipped as `firestore.rules`)

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

The project uses Firebase for authentication, team collaboration (Firestore), push notifications, and crash reporting.

1. Create a project in the [Firebase console](https://console.firebase.google.com/).
2. Add an Android app with package name `com.codeair.mhe`.
3. Download `google-services.json` and place it at **`app/google-services.json`** (this exact path; the `com.google.gms.google-services` Gradle plugin reads it from there).
4. The plugin is already applied in `app/build.gradle.kts`; no further Gradle changes are needed.

The repository ships with a `google-services.json` for the `codeair-apps` Firebase project. Replace it with your own file to point the app at your project.

</details>

<details>
<summary><b>Firebase Auth</b></summary>

Enable the sign-in methods you want in **Firebase console > Authentication > Sign-in method**:

- **Email/Password** - used for workspace accounts
- **Google** - one-tap Google sign-in via the AndroidX Credentials API (`androidx.credentials`, `googleid` libraries are already wired)
- **GitHub** - OAuth sign-in via `OAuthProvider("github.com")`; add your GitHub OAuth client ID and secret in the Firebase console, with the authorized callback URL `https://<your-project>.firebaseapp.com/__/auth/handler`

No client-side configuration beyond `google-services.json` is required. Auth state gates all collaboration writes: unauthenticated users get `NOT_CONFIGURED`, never anonymous writes.

</details>

<details>
<summary><b>Firestore rules</b></summary>

The collaboration data model (`workspaces/{ws}/members|invites|presence|sync`) is protected by the rules in **`firestore.rules`** at the repository root:

- Reads require workspace membership; writes require appropriate roles
- Only `OWNER` can change roles or remove members
- Presence documents are writable only by the owning UID
- Invites gate write access for new members

Deploy them with the Firebase CLI:

```bash
firebase deploy --only firestore:rules
```

Or paste the file contents into **Firebase console > Firestore Database > Rules**.

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
│       │   ├── collab/           # Firebase collaboration: invites, presence, sync
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
├── firestore.rules               # Firestore security rules for collaboration
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
|  |     Firebase (Auth + Firestore + FCM + Crashlytics)          |  |
|  |  Identity - Workspaces - Presence - Sync - Notifications     |  |
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
