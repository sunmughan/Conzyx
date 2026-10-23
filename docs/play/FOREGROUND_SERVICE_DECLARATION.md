# Foreground-service declaration

Mobile Harness Extended declares three `specialUse` foreground services in the manifest (`RuntimeSetupService`, `RuntimeExecutionService`, `KeepAliveService`), each with its own `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. The manifest subtype strings and the Play Console declarations must match the behavior below.

## Runtime setup service

- **Trigger:** The user taps Install after reviewing setup and optional toolchains.
- **Purpose:** Download, verify, extract, repair, and configure the private Linux development runtime.
- **Why foreground execution is required:** Initial setup is lengthy and interruption can leave package configuration incomplete. The ongoing notification exposes progress and a Stop action.
- **User impact if deferred:** The coding workspace cannot run until setup finishes.
- **Stop behavior:** The notification and setup UI offer cancellation. Wake locks are released on completion, failure, or cancellation.

Suggested Play Console wording:

> User-started installation and repair of the app's private local development runtime. The operation can take several minutes, displays continuous progress in an ongoing notification, and can be stopped by the user.

## Coding task service (RuntimeExecutionService)

- **Manifest subtype:** "User-started local development task".
- **Trigger:** The user sends a coding task from an open project, starts a terminal/agent session, or starts the Pen Testing backend from the Pen Testing screen.
- **Purpose:** Keep the explicitly started local work active while the user switches apps or locks the screen. The same service hosts three user-started workloads: agent coding sessions, terminal sessions, and the Pen Testing (PentAGI) backend session started from the Pen Testing screen.
- **Why foreground execution is required:** File edits, test commands, terminal sessions, and the local Pen Testing backend can run longer than a normal background-execution window. The notification shows current progress and a Stop action.
- **User impact if deferred:** The task is interrupted and partial changes require inspection or rollback; the Pen Testing backend stops mid-assessment.
- **Stop behavior:** The app and ongoing notification stop the active process and preserve recoverable task state. The Pen Testing screen's Stop control stops its backend stack and its service session.

Suggested Play Console wording:

> User-started local development task: a coding agent session, a terminal session, or the on-device Pen Testing backend, each started explicitly by the user in the app. The ongoing notification shows task progress and provides an immediate Stop action.

## Session keep-alive service (KeepAliveService)

- **Manifest subtype:** "Keeps the IDE session alive when the app is in the background".
- **Trigger:** Automatic. The Application class starts it when the app moves to the background and stops it when the app returns to the foreground.
- **Purpose:** Protect the user's in-progress workspace session (open project, running local runtime state) from aggressive OEM background killing while the app is briefly backgrounded.
- **Why foreground execution is required:** On several OEM Android builds, a backgrounded app without a foreground service is killed within minutes, losing the live session the user expects to return to.
- **Bounds:** The wake lock is capped at 10 minutes, the service is START_NOT_STICKY, and it never restarts itself after a user swipe-away or a system kill; the notification is shown only while the app is in the background.
- **Stop behavior:** Stops itself when the app returns to the foreground; a user swipe-away or system kill stays dead until the app backgrounds again.
- **Review risk to expect:** Play scrutinizes automatically started keep-alive services more closely than user-started ones. If a reviewer rejects this subtype, the fallback is to remove KeepAliveService from the Play build and accept OEM session loss; that decision belongs to the developer at submission time, not to this worksheet.

Suggested Play Console wording:

> Keeps the user's in-progress IDE session alive while the app is in the background on devices with aggressive background killing. The notification appears only while the app is backgrounded, the service stops when the app returns to the foreground, and it never restarts itself after the user dismisses the app.

## Demonstration video shot list

Record one continuous, reviewer-accessible video:

1. Open Mobile Harness Extended and start runtime setup from the explicit Install button.
2. Show the ongoing notification, real progress, Open action, and Stop setup action.
3. Return to the app and show the same live setup session.
4. In a prepared project, send a small coding task.
5. Background the app and show the ongoing task notification and Stop action.
6. Return to the app, stop or complete the task, and show that the notification disappears.
7. Background the app with no task running and show the keep-alive notification; return to the app and show that it disappears.
8. Open Pen Testing, start the backend, show the ongoing "Pen Testing" notification, background the app briefly, return, and stop the backend from the Pen Testing screen.

Do not show real API keys, private source code, email notifications, or personal device information in the recording.
