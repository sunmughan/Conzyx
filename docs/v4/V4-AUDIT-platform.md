# V4 Audit — Android Platform Capabilities

**Branch:** `v4` (branched from `v3` @ d06038b, v3.4.0)
**Scope:** AUDIT ONLY — manifest, permissions, services, SDK levels, and presence/absence of
OS-level automation APIs (Accessibility, MediaProjection, package discovery, gesture dispatch).
**Date:** 2026-10-17

---

## 1. Manifest inventory — `app/src/main/AndroidManifest.xml` (98 lines)

### 1.1 Permissions (9 declared)

| # | Permission | Manifest line | Notes |
|---|-----------|---------------|-------|
| 1 | `android.permission.INTERNET` | 3 | — |
| 2 | `android.permission.ACCESS_NETWORK_STATE` | 4 | — |
| 3 | `android.permission.POST_NOTIFICATIONS` | 5 | Runtime permission (API 33+) |
| 4 | `android.permission.FOREGROUND_SERVICE` | 6 | — |
| 5 | `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` | 7 | Matches the 3 services below |
| 6 | `android.permission.WAKE_LOCK` | 8 | — |
| 7 | `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 9 | Needs Settings intent; Play-sensitive |
| 8 | `android.permission.REQUEST_INSTALL_PACKAGES` | 10 | Install-APK flow; Play-sensitive |
| 9 | `android.permission.RECORD_AUDIO` | 11 | Runtime permission; declared but no capture API wired (see §3.2) |

**Notably ABSENT:** `BIND_ACCESSIBILITY_SERVICE`, `QUERY_ALL_PACKAGES`,
`FOREGROUND_SERVICE_MEDIA_PROJECTION`, and any `android.permission.CAPTURE_*`.

### 1.2 `<queries>` / package visibility

| Block | Manifest lines | Content |
|-------|---------------|---------|
| `<queries>` | 13–17 | Single `<intent>` for `android.speech.RecognitionService` only |

No `<package>` entries, no broad intent queries. On API 30+ with `targetSdk 36`
(Play builds), package visibility is therefore **restricted** — see §3.3.

### 1.3 Services (4)

| Service | Manifest lines | Exported | Foreground type |
|---------|---------------|----------|-----------------|
| `.runtime.MheMessagingService` (FCM) | 43–48 | false | n/a |
| `.runtime.RuntimeExecutionService` | 50–57 | false | `specialUse` ("User-started local development task") |
| `.runtime.RuntimeSetupService` | 59–66 | false | `specialUse` ("User-started installation of the local coding environment") |
| `.runtime.KeepAliveService` | 68–75 | false | `specialUse` ("Keeps the IDE session alive when the app is in the background") |

No `mediaProjection`, `camera`, `microphone`, or `connectedDevice` FGS types.
No `AccessibilityService` declaration anywhere in the manifest.

### 1.4 Receivers / Providers / Activities

| Component | Manifest lines | Notes |
|-----------|---------------|-------|
| `.MainActivity` (LAUNCHER, `singleTask`) | 29–41 | exported=true |
| `.runtime.AndroidAppInstallReceiver` | 86–88 | exported=false, no intent-filter in manifest |
| `androidx.core.content.FileProvider` | 77–85 | authorities `${applicationId}.files` |
| `package_compatibility` property | 89–91 | `package: name='com.codeair.mhe'` |

`app/src/online/AndroidManifest.xml` (flavor overlay): only swaps the FCM service
(`MheMessagingService` → `MobileHarnessFirebaseMessagingService`, dedup fix).
No platform-capability changes in any flavor.

### 1.5 `res/xml/` — no accessibility config

`app/src/main/res/xml/` contains only `data_extraction_rules.xml`,
`file_paths.xml`, `network_security_config.xml`. **No `accessibility_service_config.xml`.**

---

## 2. SDK versions — `app/build.gradle.kts`

| Field | Value | Source line |
|-------|-------|-------------|
| `namespace` / `applicationId` | `com.codeair.mhe` | 65 / 83 |
| `compileSdk` | 36 | 66 |
| `minSdk` | 28 | 84 |
| `targetSdk` | `if (playBuild) 36 else 28` | 87 |

Implications:
- **Default GitHub APK targets SDK 28.** API 29+ foreground-service-type enforcement,
  API 30+ package-visibility filtering, and API 34+ FGS-type declaration rules
  do **not** apply to the default build — but they **do** apply to `-PplayBuild` builds
  (targetSdk 36).
- `minSdk 28` ≥ API 21/26, so MediaProjection, AccessibilityService, and
  `dispatchGesture` APIs are all *available* — they are simply not declared or used.

---

## 3. Per-capability verdicts

All greps below ran over `app/src/main` (`*.kt`, `*.java`, `*.xml`). Zero-hit
searches are cited as evidence of absence.

### 3.1 Accessibility — **MISSING**

- No `AccessibilityService` subclass, no `android.accessibilityservice.AccessibilityService`
  import, no `BIND_ACCESSIBILITY_SERVICE` permission, no `<service>` with
  `android.accessibilityservice.AccessibilityService` intent-filter, no
  `meta-data android.accessibilityservice` config reference.
- No `AccessibilityManager`, `AccessibilityNodeInfo`, or
  `Settings.Secure` accessibility-enabled checks anywhere.

### 3.2 Screen capture (MediaProjection) — **MISSING**

- No `MediaProjection`, `MediaProjectionManager`, `createScreenCaptureIntent`,
  or `VIRTUAL_DISPLAY` references.
- No `ImageReader` / `PixelCopy` OS-level capture.
- `RECORD_AUDIO` is declared (manifest line 11) but nothing consumes it.
- The only pixel capture in the app is **WebView-local**:
  `app/src/main/java/com/codeair/mhe/visual/VisualCaptureEngine.kt:20-22`
  (`Bitmap.createBitmap` + `webView.draw(canvas)`) — captures the in-app browser
  page only, not the screen.

### 3.3 Package discovery — **MISSING**

- No `QUERY_ALL_PACKAGES` permission.
- No calls to `getInstalledPackages`, `getInstalledApplications`, or
  `queryIntentActivities` anywhere in `app/src/main`.
- `<queries>` covers only the speech recognizer (manifest lines 13–17), so on
  Play builds (targetSdk 36, API 30+ visibility rules) the app can see almost no
  other packages.

### 3.4 Gesture dispatch — **MISSING**

- No `dispatchGesture`, `performGlobalAction`, `GestureDescription`, or
  `UiAutomation` references.
- No shell-based input injection either: no `screencap`, no `input tap` /
  `input swipe` command strings in the codebase.

### 3.5 What the app DOES have today (for contrast)

The existing "agent drives the phone" path is **web-only**, not OS-level:
- In-app browser tab (`WebBrowserScreen.kt`) with `WebView.setWebContentsDebuggingEnabled(true)`
  (`WebBrowserScreen.kt:1381`; also toggled from `SettingsScreenModern.kt:969`).
- Chat commands drive the visible WebView via CDP + `evaluateJavascript` DOM
  injection (`WebBrowserScreen.kt:503,1520,1522,1710,1775`).
- Agent banner + Take-over button in the browser UI (per product history).

This covers web pages inside the app's own WebView. It cannot see or touch
anything outside the app (other apps, system UI, notifications, device settings).

---

## 4. What V4 M1/M2 would need to add

*(Checklist of new platform surface required for device-wide automation and
screen capture. No code — declarative additions only.)*

### For an AccessibilityService-based device-control milestone (M1-type)
1. **Manifest:** new `<service>` with
   `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`,
   intent-filter `android.accessibilityservice.AccessibilityService`,
   `android:exported="false"`, and `meta-data android.accessibilityservice`
   pointing at a new `res/xml/accessibility_service_config.xml`.
2. **Config XML (new file):** `accessibilityServiceInfo` flags —
   event types, feedback type, `canPerformGestures="true"` (needed for
   `dispatchGesture`), `canRetrieveWindowContent="true"`.
3. **Permission model:** Accessibility is **not grantable by the app** — the user
   must manually enable it in system Settings. V4 needs an onboarding /
   deep-link flow (`Settings.ACTION_ACCESSIBILITY_SETTINGS`). The service runs
   only after the user flips the switch; the app cannot force it.
4. **FGS interaction:** none required for the service itself, but any companion
   long-running automation worker on API 34+ (Play builds) must declare a valid
   `foregroundServiceType` and hold the matching permission.

### For a MediaProjection screen-capture milestone (M2-type)
1. **Manifest:** `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission plus a
   foreground service declared with `android:foregroundServiceType="mediaProjection"`
   (mandatory on API 29+; API 34+ also requires the permission at install).
2. **Consent model:** `createScreenCaptureIntent()` launches a **system consent
   dialog every capture session**; the grant token is one-shot and expires when
   the projecting process dies. V4 needs UX that re-prompts cleanly and handles
   denial. No manifest permission can bypass this.
3. **Audio note:** `RECORD_AUDIO` is already declared, which covers the audio
   side of a capture session if V4 wants it; no new audio permission needed.
4. **targetSdk split:** on default GitHub builds (targetSdk 28) the old
   startForeground-without-type path still works, but Play builds (targetSdk 36)
   enforce the full API 29/34 FGS-type contract — the two flavors will behave
   differently and must be tested separately.

### For package/app discovery (needed by either milestone)
1. **Option A — `QUERY_ALL_PACKAGES`:** one manifest line; restores broad
   `getInstalledPackages` / `queryIntentActivities` on API 30+. Play policy
   treats it as sensitive (declaration + review); **GitHub-only distribution
   sidesteps the review**, but the permission still prompts no runtime dialog.
2. **Option B — scoped `<queries>`:** enumerate target packages/intents
   declaratively. No sensitive permission, but V4 must know the package names
   up front — unsuitable for open-ended "list my installed apps".
3. Note: on default builds (targetSdk 28) package visibility filtering does not
   apply at all, so this only bites Play builds.

### Cross-cutting
- **minSdk 28 is sufficient** — no SDK bump required for any of the above APIs.
- **Distribution:** Play submission is currently on hold (GitHub-only releases),
  which removes the Play-declaration burden for Accessibility API usage,
  `QUERY_ALL_PACKAGES`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and
  `REQUEST_INSTALL_PACKAGES`. If Play submission resumes, each needs a
  policy declaration and review.
- **No conflicts found:** the three existing `specialUse` foreground services,
  the FileProvider, and the install receiver do not overlap with the additions
  above; the new service(s) can coexist.

---

## 5. Bottom line

| Capability | Today (v4 branch) | V4 needs |
|-----------|-------------------|----------|
| AccessibilityService | **MISSING** — no service, permission, config, or code | New service + config XML + manual-enable onboarding |
| Screen capture (MediaProjection) | **MISSING** — only WebView-local `draw()` | New FGS (`mediaProjection` type) + per-session consent UX |
| Package discovery | **MISSING** — no QUERY_ALL_PACKAGES, no PackageManager enumeration | `QUERY_ALL_PACKAGES` (or scoped `<queries>`) |
| Gesture dispatch | **MISSING** — no `dispatchGesture`/`performGlobalAction`/shell input | Falls out of the AccessibilityService once declared |
| In-app web automation (CDP) | **PRESENT** — WebView debugging + JS injection | Reusable as-is; orthogonal to OS-level control |

Feasibility: **yes, all four missing capabilities are addable** — minSdk 28
supports every required API, compileSdk 36 exposes them, and nothing in the
existing manifest conflicts. The costs are (a) new manifest/service/config
surface, (b) two user-consent flows that cannot be automated (accessibility
Settings toggle; MediaProjection per-session dialog), and (c) Play-policy
declarations if store submission ever resumes. The default GitHub build
(targetSdk 28) faces fewer enforcement hurdles than the Play build
(targetSdk 36); both flavors need separate verification.
