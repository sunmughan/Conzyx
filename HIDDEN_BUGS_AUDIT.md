# Hidden Bugs Audit — Mobile Harness Extended (v3)

**Date:** 2026-10-13 ~11:15 IST
**Scope:** read-only audit of `~/workspace/mobile-harness-extended` for bugs of the same family as the just-fixed JNI crash (native `pocket_spawn.c` exported `Java_com_jarves_mh_runtime_NativeSpawn_*` while the Kotlin class is `com.codeair.mhe.runtime.NativeSpawn` → "No implementation found" on every prompt submit; fixed in commit `ecf8eb5`).
**Method:** repo-wide grep + symbol inspection (`nm -D`) + manifest/gradle/workflow review. No files were modified.

Severity: HIGH = crash or broken release · MEDIUM = wrong behavior / build failure risk · LOW = dead code / cosmetic · INFO = intentional, no action needed.

---

## 1. Remaining references to the old package/namespace (`jarves`)

### 1a. HIGH — stale prebuilt native library still exports the old JNI symbols
- **File:** `app/src/main/prebuiltJniLibs/arm64-v8a/libpocketspawn.so` (binary; verified with `nm -D`)
- **What:** exports `Java_com_jarves_mh_runtime_NativeSpawn_spawn`, `..._waitFor`, `..._kill` — the exact old symbols that caused the prompt-send crash.
- **Why it matters:** `app/build.gradle.kts:158` sets `enableCmake=false` when `os.arch` is `aarch64`/`arm64`, and then uses `src/main/prebuiltJniLibs` instead of building from CMake source. Anyone building on an ARM64 host (e.g. an Apple-Silicon Mac, an ARM CI runner, or a local dev machine) gets this stale `.so` and hits the identical "No implementation found" crash. The C-source fix (`ecf8eb5`) does NOT cover this path.
- **Suggested fix:** rebuild `libpocketspawn.so` from the fixed `pocket_spawn.c` with the NDK for `arm64-v8a` and replace the file in `prebuiltJniLibs/arm64-v8a/` (verify with `nm -D` that the symbols read `Java_com_codeair_mhe_runtime_NativeSpawn_*`). The other prebuilt libs (`libproot.so`, `libprootloader.so`, `libtalloc.so`, `libandroid-shmem.so`) export no `Java_` symbols and are fine.

### 1b. MEDIUM — hardcoded old developer home path in the Play release script
- **File:** `scripts/build-play-release.sh:5`
- **What:** `keystore_path="${MH_UPLOAD_STORE_FILE:-/Users/jarves/.mobile-harness/mobile-harness-upload.jks}"`
- **Why it matters:** if the script is ever run without `MH_UPLOAD_STORE_FILE` set, it fails with "Upload keystore not found" pointing at a path that exists only on the upstream author's old Mac. CI sets the env var so current releases are unaffected, but the script is broken by default.
- **Suggested fix:** change the default to a repo-relative path or fail fast with a clear message telling the operator to set `MH_UPLOAD_STORE_FILE`.

### 1c. LOW — commented-out workflow line still names the old package
- **File:** `.github/workflows/android.yml:308`
- **What:** `# "$ANDROID_HOME/platform-tools/adb" shell dumpsys package com.codeair.mhe | ... || "$ANDROID_HOME/platform-tools/adb" shell dumpsys package com.jarves.mh | ...`
- **Why it matters:** dead commented code; harmless, but if uncommented during debugging it queries a package that can never be installed.
- **Suggested fix:** delete the commented line or drop the `com.jarves.mh` fallback.

### 1d. LOW — emulator smoke script keeps the old package as a fallback
- **File:** `ci/emulator-smoke.sh:26`
- **What:** grep pattern `(${PACKAGE}|com\.jarves\.mh)` when checking the resumed activity.
- **Why it matters:** purely defensive fallback; harmless. No crash/build risk.
- **Suggested fix:** optional cleanup; leave as-is if the script is meant to work against upstream builds too.

### 1e. LOW — release-compatibility check accepts the old package name
- **File:** `.github/workflows/android.yml:251`
- **What:** `grep -qE "package: name='(com.codeair.mhe|com.jarves.mh)'"` on the *previous* release APK.
- **Why it matters:** intentional (allows upgrade-compatibility checks against an upstream-signed install). No risk.
- **Suggested fix:** none required; note it so nobody "cleans it up" thinking it's stale.

### 1f. INFO — functional upstream dependencies (not bugs)
These intentionally point at the upstream project and work as designed:
- `app/build.gradle.kts:31` and `app/src/main/java/com/codeair/mhe/runtime/RuntimeInstaller.kt:794` — offline runtime bundles downloaded from `techjarves/Mobile-Harness` `runtime-*` releases.
- `app/src/main/java/com/codeair/mhe/runtime/SkillManager.kt:526-789` — bundled skill sources at `github.com/jarves/<skill>`.
- `app/src/main/java/com/codeair/mhe/update/AppUpdater.kt:44` and `SettingsScreenModern.kt:1297` — the *last-resort* update-manifest fallback is the upstream `mobile-harness-update.json`. It is only reached if the primary manifest (`APP_UPDATE_MANIFEST_URL`, correctly set to `sunmughan/Mobile-Harness-Extended` at `app/build.gradle.kts:32-33`) AND the GitHub releases API both fail. Residual risk: in that corner case the app could offer an upstream-signed build that won't install over the Codeair-signed one. Consider pointing the fallback at the user's own repo or removing it.
- `SettingsScreenModern.kt:2276-2284` — contributor credit for Tech Jarves (intentional attribution).
- `app/src/test/.../RoadmapParserTest.kt` and `SkillManagerTest.kt` — old paths/URLs inside *test fixture data*; test-only, zero runtime risk.
- `README.md` / `docs/` — documentation mentions; zero runtime risk.

---

## 2. JNI / native mismatches

### 2a. CLEAN (after fix) — CMake-built native library matches Kotlin
- Kotlin `external fun` declarations: exactly 3, all in `app/src/main/java/com/codeair/mhe/runtime/NativeSpawnProcess.kt:101,110,111` (`spawn`, `waitFor`, `kill` on `private object NativeSpawn` in package `com.codeair.mhe.runtime`).
- C exports in `app/src/main/cpp/pocket_spawn.c:18,128,140`: now `Java_com_codeair_mhe_runtime_NativeSpawn_spawn/waitFor/kill` — match the Kotlin package exactly after commit `ecf8eb5`.
- No other `Java_` JNI exports exist in `app/src/main/cpp` (`pocket_launcher.c`, `executable_carrier.c` export none).
- No `native` methods in any `.java` file; no other `external fun` in Kotlin.

### 2b. HIGH — prebuilt .so reintroduces the same mismatch on ARM64 hosts
- Same as 1a: `app/src/main/prebuiltJniLibs/arm64-v8a/libpocketspawn.so` still exports the old `Java_com_jarves_*` symbols. Any build that takes the `enableCmake=false` branch (ARM64 host) ships the crashing library. This is the same bug class as the one just fixed, hiding in a binary artifact.
- **Suggested fix:** rebuild and replace as described in 1a, or delete the prebuilt dir and always build via CMake (requires NDK on all build hosts).

---

## 3. Hardcoded `/data/data/<package>` paths

**CLEAN.** No hardcoded `/data/data/` or `/data/user/0/` paths anywhere in Kotlin/Java/C/XML/Gradle sources. The one native-side path, `app/src/main/cpp/CMakeLists.txt` (`_PATH_TMP="/data/data/com.codeair.mhe/cache/"`), already uses the correct new package.

---

## 4. AndroidManifest.xml

**CLEAN — no stale package strings.** Verified:
- `app/src/main/AndroidManifest.xml`: no `package=` attribute (correct; uses Gradle `namespace`/`applicationId = "com.codeair.mhe"`), FileProvider authority `${applicationId}.files`, `package_compatibility` property `package: name='com.codeair.mhe'`. All services/receivers/providers resolve to existing classes.
- `app/src/online/AndroidManifest.xml`: only adds the online flavor's FCM service and metadata; no stale strings.

### 4a. MEDIUM — duplicate FCM handling in the online flavor (behavior bug, not a crash)
- **Files:** `app/src/main/AndroidManifest.xml` (registers `.runtime.MheMessagingService`) + `app/src/online/AndroidManifest.xml` (registers `.runtime.MobileHarnessFirebaseMessagingService`); classes at `app/src/main/java/com/codeair/mhe/runtime/MheMessagingService.kt` and `app/src/online/java/com/codeair/mhe/runtime/MobileHarnessFirebaseMessagingService.kt`.
- **What:** both services declare the `com.google.firebase.MESSAGING_EVENT` intent filter, and both post a user-visible notification in `onMessageReceived`. In the online flavor every push is therefore handled twice → duplicate notifications (and `PushTokenStore.save` runs twice on token refresh).
- **Why it matters:** user-visible double notifications; also double `messageId`-keyed posts via `NotificationCoordinator` in the online service.
- **Suggested fix:** in the online flavor, keep only one FCM service (merge the data-message handling of `MobileHarnessFirebaseMessagingService` into `MheMessagingService`, or disable the main one for the online source set via `tools:node="remove"`).

---

## 5. `System.loadLibrary` coverage

**CLEAN.**
- Single call: `app/src/main/java/com/codeair/mhe/runtime/NativeSpawnProcess.kt:98` → `System.loadLibrary("pocketspawn")`.
- Produced by: `app/src/main/cpp/CMakeLists.txt:13` → `add_library(pocketspawn SHARED pocket_spawn.c)`. Name matches (`libpocketspawn.so`).
- No other `loadLibrary` calls in any source set; no library is loaded that isn't built/packaged.

---

## 6. Other crash-prone patterns

### 6a. HIGH (already known, fix pending user go-ahead) — `rememberSaveable` holding non-Parcelable `BrowserTab`
- **File:** `app/src/main/java/com/codeair/mhe/ui/WebBrowserScreen.kt:172` — `var tabs by rememberSaveable { ... }` where `BrowserTab` (`:62`, plain `data class`, not `Parcelable`) is stored. Crashes with `IllegalArgumentException: Parcel: unknown type for value BrowserTab(...)` during Activity-stop state save. This is the exact issue in Crashlytics and in the user's `crash__2__34_e926.log` (5 events, 2026-10-13 ~04:30–04:34 IST).
- **Suggested fix:** the custom `Saver` approach already proposed to the user (serialize tabs to a `List<String>` of urls/ids, or drop `rememberSaveable` for plain `remember`).

### 6b. CLEAN — all other `rememberSaveable` usages hold saveable types
Audited all 193 usages across 18 files. Everything else is: primitives, `String`/`String?`, `Int`/`Float`, `List<String>`/`List<Int>`, `Uri?` (`Parcelable`), or enums (`ProviderKind`, `AgentKind`, `WorkspaceTab`, `RootScreen`, `MarketplaceCategory`, `ProjectTemplate`, `PermissionCategory`, `AuditDecision`, `SettingsSection`, `PaletteActionCategory` — all `Serializable`). No other custom Parcel-crash vector found.

### 6c. CLEAN — `!!` usage
- Zero `!!` in `PocketDevApp.kt`, `AgentScreen.kt`, `MainViewModel.kt`.
- One in `app/src/main/java/com/codeair/mhe/runtime/DshRuntimeBridge.kt:115` (`openRouterGateway!!.url`) — null-guarded by the enclosing `if (openRouterGateway != null && ...)` condition. Safe.

### 6d. LOW — no `UnsatisfiedLinkError` defense around native calls
- **File:** `app/src/main/java/com/codeair/mhe/runtime/NativeSpawnProcess.kt` (`System.loadLibrary` in `init`) and its single caller `RuntimeInstaller.kt:2260`.
- **What:** no `try/catch (UnsatisfiedLinkError)` anywhere near the native boundary. With the symbol fix this is no longer crash-critical (the app's checkpoint logger already converts spawn failure into `AgentSessionFailed` rather than an app crash), but a missing/corrupt `.so` would still surface as an unhandled linker error.
- **Suggested fix:** optional hardening — wrap `System.loadLibrary("pocketspawn")` in try/catch and surface a clear "native runtime missing, reinstall the app" state instead of a linker crash.

---

## 7. build.gradle.kts / workflow — old-name references

- `app/build.gradle.kts:31` — `runtimeReleaseBaseUrl` points at upstream `techjarves/Mobile-Harness` releases (INFO, functional dependency for offline runtime bundles; see 1f).
- `app/build.gradle.kts:32-33` — `appUpdateManifestUrl` correctly points at `sunmughan/Mobile-Harness-Extended`. CLEAN.
- `.github/workflows/android.yml` — signing uses `MH_RELEASE_*` secrets with a permanent-key fingerprint check (CLEAN); the `publish_existing_v1_0_6` job is dormant (only runs on `main` + `[publish-existing]` commit marker; INFO).
- `scripts/build-play-release.sh:5` — the `/Users/jarves/` default keystore path (MEDIUM, see 1b).
- `gradle.properties`, `settings.gradle.kts` — clean, no old references.

---

## Summary counts

| # | Category | Findings (HIGH / MEDIUM / LOW / INFO) |
|---|----------|----------------------------------------|
| 1 | Old package/namespace references | 10 total — **1 HIGH**, 1 MEDIUM, 3 LOW, 5 INFO |
| 2 | JNI/native mismatches | **1 HIGH** (prebuilt `.so`; CMake path is clean after `ecf8eb5`) |
| 3 | Hardcoded `/data/data/<pkg>` paths | 0 — clean |
| 4 | AndroidManifest | 0 stale strings — clean; **1 MEDIUM** behavior issue (duplicate FCM services) |
| 5 | `System.loadLibrary` coverage | 0 — clean |
| 6 | Crash-prone patterns | **1 HIGH** (BrowserTab `rememberSaveable`, known/pending), 1 LOW (no linker-error guard) |
| 7 | build.gradle.kts / workflow | 1 MEDIUM (script path), rest clean/INFO |

**Action-required (new, not previously known):**
1. **HIGH:** rebuild `app/src/main/prebuiltJniLibs/arm64-v8a/libpocketspawn.so` from the fixed C source (or remove the prebuilt path) — otherwise ARM64-host builds reintroduce the exact prompt-send crash.
2. **MEDIUM:** online flavor posts every push notification twice (two FCM services); keep one.
3. **MEDIUM:** `scripts/build-play-release.sh` default keystore path points at `/Users/jarves/...`; fix the default.
4. **LOW:** wrap `System.loadLibrary("pocketspawn")` in an `UnsatisfiedLinkError` guard for a clean failure mode.
