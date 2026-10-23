# Google Play Data safety worksheet

Use this worksheet when completing the Play Console Data safety form. Recheck it against the exact release before submitting.

## High-level answers

- The app does not contain ads or advertising SDKs.
- The online release (the Play build) includes Firebase Crashlytics, Firebase Cloud Messaging, and Firebase Authentication. The answers below cover them; earlier drafts of this worksheet that denied any crash-reporting SDK were wrong.
- Data is encrypted in transit when communicating with external services.
- Users can delete local projects and chats in the app; uninstalling removes app-private data.
- Accounts are optional: the app offers Firebase Authentication sign-in (email/password, Google, GitHub) and a guest mode. Account deletion is available inside the app (AuthRepository.deleteAccount, exposed in the auth UI).

## Data sent off device

When the user configures and uses an AI provider, the following may be sent to that provider for **App functionality**:

| Play category | Examples | Collected/shared | Required | Handling |
| --- | --- | --- | --- | --- |
| User-generated content | Prompts, chat messages, selected project content, tool results | Collected by the selected provider | Required to use AI chat | Encrypted in transit; provider retention rules apply |
| Files and documents | Files deliberately attached or included in an agent request | Collected by the selected provider | Optional | Encrypted in transit; provider retention rules apply |
| Audio files | Audio deliberately attached by the user | Collected by the selected provider only when included in a request | Optional | V1 does not automatically transcribe audio |
| Authentication information | Provider API key or short-lived provider credential | Shared with the selected provider/gateway for authentication | Required for provider access | Encrypted locally and in transit |

The app itself does not send projects or conversations to the Mobile Harness Extended developer unless a user deliberately shares them through a separate support channel.

## Data handled by Firebase services (online release)

Google/Firebase processes the following as a service provider. In Play Data safety terms this is data **collected** by the app through these SDKs, not sold and not used for advertising.

| Play category | Examples | When | Required | Handling |
| --- | --- | --- | --- | --- |
| Crash logs (App info and performance) | Exception type, message, stack trace, with pattern-based redaction of API keys, tokens, passwords, and private keys before upload (AppCrashLogger) | On crash or recorded non-fatal error | Collected by default; can be disabled via the crash-reporting flag | Sent to Firebase Crashlytics; Google retention policy applies |
| Diagnostics (App info and performance) | App version and variant, Android version/API level, device manufacturer and model, CPU architectures, memory statistics | Sent with crash reports | With crash reports | Sent to Firebase Crashlytics |
| Device or other IDs | Firebase Cloud Messaging registration token; Crashlytics installation ID | FCM token on registration/refresh; install ID with crash reports | Required for remote event delivery and crash reporting | Held by Firebase; token rotates |
| Name, Email address, User IDs (Personal info) | Email, display name, Firebase user ID, sign-in provider | Only when the user creates or signs in to an account (email/password, Google, or GitHub) | Optional — guest mode works without an account | Held by Firebase Authentication; user can delete the account in-app |

Firebase Cloud Messaging is used only as a remote event transport (agent/session completion events); see `docs/FCM_REMOTE_EVENTS.md`. Message content is limited to event title/body and an in-app route string.

## Other network recipients

During setup and maintenance, Ubuntu, Anthropic, Node.js, Composer, and configured package repositories may receive ordinary request metadata such as IP address, requested resource, request time, and user agent. These transfers are needed for **App functionality** and software downloads.

## Local-only data

Projects, conversations, attachments, terminal history, checkpoints, preferences, runtime files, and diagnostics are stored in app-private device storage. API keys are encrypted using an Android Keystore-backed key. Data that never leaves the device is not declared as collected under the Play definition.

## Verification before submission

- Confirm the Firebase set in the release is exactly Crashlytics + Messaging + Authentication; if an analytics SDK is added, this worksheet and the Console form must gain an analytics declaration first.
- Confirm every configured AI gateway is covered by the public privacy policy.
- Confirm the release contains no test keys or default private endpoints.
- Update this worksheet if cloud backup, subscriptions, or additional diagnostics upload are introduced.
