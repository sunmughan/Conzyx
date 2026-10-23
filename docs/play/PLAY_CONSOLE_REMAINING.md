# Play Console: what remains to be done by hand

Submission is on hold by the developer's order. Everything repo-side for the declarations is prepared in this folder (`DATA_SAFETY.md`, `FOREGROUND_SERVICE_DECLARATION.md`, `APP_CONTENT_DECLARATIONS.md`, `REVIEWER_INSTRUCTIONS.md`) and in `PRIVACY.md`. The steps below can only be done in Play Console or on a physical device; nothing here has been submitted.

## Console steps, in order

1. **Enrol in Play App Signing** for package `com.codeair.mhe` and register the app if that has not been done yet.
2. **Verify the privacy policy URL resolves publicly**: the build defaults to `https://github.com/sunmughan/Conzyx/blob/main/PRIVACY.md`. If that URL does not serve the current policy, publish `PRIVACY.md` at a public HTTPS URL and build with `-PprivacyPolicyUrl=<that URL>`.
3. **Upload the AAB**: build with `./gradlew -PplayBuild=true -PappVersionCode=<next> -PappVersionName=<next> bundleOnlineRelease` (upload-key signing via the `MH_UPLOAD_*` environment variables, see `docs/PLAY_STORE_CHECKLIST.md`). Increment `appVersionCode` for every upload.
4. **App content > Data safety**: complete the form from `docs/play/DATA_SAFETY.md`. It must include the Firebase Crashlytics crash/diagnostic data, the FCM device token, the optional Firebase Authentication personal info, and the user-selected AI provider transfers. Do not reuse older answers that claim there is no crash-reporting SDK; that is no longer true.
5. **App content > Foreground service declarations**: declare all three `specialUse` services (runtime setup, runtime execution, session keep-alive) using the wording in `docs/play/FOREGROUND_SERVICE_DECLARATION.md`, and attach the demonstration video (step 9).
6. **App content > Account deletion**: state that the app allows account creation, point to the in-app deletion path, and enter a public web URL where users can request account deletion. That web page does not exist yet; publish one (for example a page in the public repository or on the support site) before filling this in.
7. **App content > App access**: paste the reviewer instructions and a time-limited, low-quota test provider credential (never commit it to the repo).
8. **Content rating + target audience**: complete the IARC questionnaire; target audience is adults (18+), per `docs/play/APP_CONTENT_DECLARATIONS.md`.
9. **Record the declaration video on a physical device** following the shot list in `docs/play/FOREGROUND_SERVICE_DECLARATION.md` (setup, coding task, keep-alive, and the Pen Testing backend), upload it where reviewers can open it, and link it in the foreground-service declaration.
10. **Store listing**: add the public support email, review the phone screenshots against the final release build, and confirm the listing website/support URLs point at `https://github.com/sunmughan/Conzyx`.
11. **Run the pre-launch report** on the uploaded AAB across supported Android versions and triage findings before any production rollout.

## Open policy risks to resolve before submitting

- **Downloadable local runtime**: the app downloads and runs a local Linux/toolchain runtime (and the Pen Testing bundle) outside Play's update mechanism. This architecture may need explicit policy approval; it is tracked in `docs/PLAY_STORE_CHECKLIST.md` and is the largest known submission risk.
- **Keep-alive foreground service**: it starts automatically when the app backgrounds. Play may reject auto-started keep-alive justifications; the fallback is removing `KeepAliveService` from the Play build (see `docs/play/FOREGROUND_SERVICE_DECLARATION.md`).
- **Anthropic branding/licensing review**: branding, authentication, licensing, and redistribution review for bundled agent tooling is still open in the checklist.
- **REQUEST_INSTALL_PACKAGES**: the app submits user-built APKs to the Android system installer (user confirmation required). Be ready to justify this permission in review if asked; it is documented in `PRIVACY.md`.
