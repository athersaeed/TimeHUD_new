# AGENTS.md — TimeHUD Repository Instructions

These instructions apply to the entire repository. They are the default operating context for Codex and other coding agents working on TimeHUD.

`PROJECT_CONTEXT.md` contains the detailed source audit. Read it when a task affects runtime behavior, architecture, permissions, persistence, release configuration, or an unfamiliar subsystem. The checked-in source is always authoritative if documentation and code disagree.

## 1. Product and Repository Mental Model

TimeHUD is a native, local-only Android focus/accountability app.

Core flow:

1. The user opens the single Compose control activity in `MainActivity` and switches among Goals, App usage, App limits, and Permissions.
2. The user grants overlay access and usage access.
3. The user can edit short-term and long-term goals and optionally grant calendar read access.
4. Starting the HUD launches `OverlayService` as a foreground service.
5. A draggable bubble shows total interactive screen time and opens the goal check-in when tapped.
6. At newly observed five-minute usage buckets, it is replaced by the same full-screen goal check-in.
7. The user can switch goal groups, mark a goal done for the day, undo it, remove it, or close it. Bubble-opened check-ins close immediately; automatic five-minute check-ins enforce a five-second delay.
8. The service can restart after boot or package replacement when saved active state and required permissions allow it.
9. Independently of the HUD service, an optional accessibility service enforces configured per-app daily limits and supported in-app section rules for YouTube, Instagram, Facebook, Snapchat, and X. It preserves higher-layer multi-window/pop-up regions, keeps Instagram Messages exempt, and presents the shared goal check-in over blocked content. After the five-second pause, Close returns to Android Home.

Important timing behavior:

- Screen time is measured from a daily 3:00 AM boundary.
- The service refreshes the timer every 10 seconds.
- The active check-in triggers once per newly observed five-minute total-screen-time bucket.
- Service restart currently resets in-memory bucket state, so an active overlay may appear immediately when accumulated usage already exceeds five minutes.

Current repository facts:

- One Android application module: `:app`
- Kotlin
- Jetpack Compose for the setup/control activity
- XML plus platform `View` and `WindowManager` APIs for overlays
- `SharedPreferences` persistence
- Android Calendar Provider integration
- Android Usage Stats/AppOps integration
- Optional Android Accessibility Service for local app-limit detection and window-scoped shields
- Foreground service and boot receiver
- No backend, networking, authentication, cloud sync, analytics, ads, Room, DataStore, navigation graph, dependency injection, repository layer, or application `ViewModel`

Do not invent or assume missing backend, account, route, API, cloud, or database behavior.

## 2. Technology Baseline

Use repository-declared versions. Do not upgrade them incidentally.

- Gradle project: `TimeHUD`
- Namespace: `com.boringutils.timehud`
- Application ID: `com.boringutils.timehud`
- Minimum SDK: 24
- Target SDK: 36
- Compile SDK: 36.1
- Gradle wrapper: 9.3.1
- Android Gradle Plugin: 9.1.0
- Kotlin/Compose compiler line: 2.2.10
- Java source/target: 11
- Gradle/IDE JDK: 21
- UI: Compose Material 3 plus platform XML views
- Unit tests: JUnit 4
- Device tests: AndroidX Test/Espresso

Do not change SDK levels, Gradle, AGP, Kotlin, Compose, package names, application ID, signing, or dependency versions unless the task explicitly requires it.

## 3. Repository Map and Ownership

```text
settings.gradle.kts
build.gradle.kts
gradle/
  libs.versions.toml
  gradle-daemon-jvm.properties
  wrapper/

app/
  build.gradle.kts
  proguard-rules.pro
  src/main/
    AndroidManifest.xml
    java/com/boringutils/timehud/
      MainActivity.kt
      OverlayService.kt
      BootReceiver.kt
      CalendarAgenda.kt
      GoalSettings.kt
      GoalCompletionStore.kt
      OverlayServiceState.kt
      StartupPreferences.kt
      blocking/
        AppBlockModels.kt
        AppBlockSettings.kt
        TimeHudAccessibilityService.kt
      ui/blocking/
        AppBlockingScreen.kt
      ui/theme/
    res/
      layout/overlay_passive.xml
      layout/overlay_active.xml
      values/
      drawable/
      mipmap-*/
      xml/
  src/test/
  src/androidTest/
```

### Key files

- `MainActivity.kt`
  - Only in-app activity
  - Compose setup/control screen
  - Permission status and special-access intents
  - Goal editing/saving and calendar import initiation
  - HUD start/stop controls

- `OverlayService.kt`
  - Foreground-service lifecycle and notification
  - Usage-event aggregation
  - Passive and active overlay creation/removal
  - Five-minute trigger behavior
  - Goal interaction UI and live calendar agenda
  - Highest-regression-risk file

- `BootReceiver.kt`
  - Conditional service restart after boot or package replacement

- `CalendarAgenda.kt`
  - Calendar Provider query, event formatting, and import transformation

- `GoalSettings.kt`
  - Goal defaults, parsing, normalization, persistence, and removal

- `GoalCompletionStore.kt`
  - Date-scoped completion state

- `OverlayServiceState.kt`
  - Process-local `StateFlow` used by the activity to present Start/Stop state

- `StartupPreferences.kt`
  - Persistent desired HUD-active state used for restart behavior

- `blocking/`
  - App-limit rule persistence, pure decisions/visible-region geometry, focused-use accounting, supported-app navigation-state classification, accessibility window observation, and blocking overlays

- `ui/blocking/AppBlockingScreen.kt`
  - App-limit rule editor, Accessibility Settings entry, daily-limit controls, and per-section options for YouTube, Instagram, Facebook, Snapchat, and X

- `overlay_passive.xml`
  - Small always-on-top timer

- `overlay_active.xml`
  - Full-screen check-in, agenda, completion modal, and celebration layers

Read related files together:

- Screen-time/service work: `OverlayService.kt`, `MainActivity.kt`, `AndroidManifest.xml`, both overlay layouts, and tests.
- Goal work: `MainActivity.kt`, `OverlayService.kt`, `GoalSettings.kt`, `GoalCompletionStore.kt`, and tests.
- Calendar work: `MainActivity.kt`, `OverlayService.kt`, `CalendarAgenda.kt`, `AndroidManifest.xml`, and tests.
- Boot/restart work: `BootReceiver.kt`, `StartupPreferences.kt`, `OverlayService.kt`, `MainActivity.kt`, and manifest.
- Build/release work: root Gradle files, app Gradle file, version catalog, manifest, ProGuard rules, and release documentation.

## 4. Current Architecture

This is a small component-oriented Android application. It is not currently MVVM, MVI, Clean Architecture, or repository-based.

```text
Android permission/special-access state
  -> MainActivity local Compose state
  -> Start button enabled/disabled

SharedPreferences
  <-> GoalSettings / GoalCompletionStore / StartupPreferences
  -> MainActivity goal editor
  -> OverlayService goal and restart behavior

UsageStatsManager + PowerManager
  -> OverlayService usage calculation
  -> passive timer
  -> five-minute active-overlay trigger

Calendar Provider
  -> CalendarAgenda
  -> optional import in MainActivity
  -> live agenda in OverlayService

OverlayService lifecycle
  -> OverlayServiceStateStore StateFlow
  -> MainActivity Start/Stop presentation

Accessibility windows + configured app-limit rules
  -> TimeHudAccessibilityService classification and focused-use accounting
  -> visible-region subtraction for higher pop-up windows
  -> TYPE_ACCESSIBILITY_OVERLAY blocking pieces
```

Preserve this simple architecture for small changes. Do not introduce a framework merely to make one local change.

For a genuinely larger feature:

- Put new Compose feature UI under `ui/<feature>/` rather than making `MainActivity.kt` indefinitely larger.
- Use an AndroidX `ViewModel` with immutable state and `StateFlow` when screen state must survive recreation or coordinate asynchronous work.
- Prefer constructor/function dependencies and small interfaces before adding a DI framework.
- Add a repository boundary only when adding a real secondary data source such as an API or database.
- Add Navigation Compose only when a second true in-app destination exists.

Any architectural expansion must be intentional, justified, scoped, tested, and documented.

## 5. Behavioral Invariants

Preserve these unless the request explicitly changes them.

### Service and overlay lifecycle

- The service must enter foreground operation correctly and maintain its ongoing notification.
- Starting the HUD must save current goal fields before service startup.
- Explicit Stop must stop the service, remove overlays, and clear the persisted active/restart preference.
- System destruction is not an explicit user stop.
- Only one passive or active overlay should be attached at a time.
- Overlay cleanup must be safe during stop, recreation, and failure.
- Notification tap should return to the single activity without unnecessary duplicate instances.
- Boot/package replacement restart stays gated by saved active state and required permissions.

### Screen-time behavior

- The daily boundary is 3:00 AM unless explicitly changed.
- Screen time means interactive-screen time derived from Android usage events and current interactive state.
- Preserve existing five-minute bucket semantics unless explicitly redesigned.
- Do not replace interactive time with app-usage totals unless requested.
- Time aggregation and boundary changes require focused regression tests.

### Goal behavior

- Short-term and long-term goals are separate multiline text sets.
- Display parsing trims blank lines and bullet prefixes.
- The overlay currently displays at most eight parsed goal lines.
- Completion is scoped to the current local date.
- Tapping a completed goal can undo completion.
- Removing a goal changes persisted goal text.
- Goal identity currently derives from normalized text; preserve it unless the task includes a migration to stable IDs.
- Prevent stale activity state from overwriting service-side changes.

### Calendar behavior

- Calendar access is optional and read-only.
- The HUD must remain usable without calendar permission.
- Live overlay agenda and manually imported calendar text are distinct concepts, even though current behavior can mix imported text into goals.
- Do not request write-calendar access unless explicitly required.
- Do not log private calendar titles or goal text.

### Permission behavior

- Overlay and usage access are required to start the HUD.
- Calendar access is optional.
- Accessibility access is optional for the HUD but required for app-limit enforcement. Rules stay local and must not inspect, persist, or log message bodies, post text, usernames, or captions.
- Every new permission needs manifest configuration where required, request flow, granted behavior, denied behavior, revoked-while-running behavior, and an appropriate user explanation.
- A manifest declaration alone is never sufficient for a runtime or special-access permission.

### App-limit behavior

- App limits use the same 3:00 AM daily boundary as app usage.
- Usage Access seeds the current daily total; subsequent enforcement time is accumulated from the focused accessibility window so a visible background app does not gain focused time behind a Samsung pop-up.
- In-app recognition is intentionally limited to supported English navigation labels, window titles, and view identifiers: YouTube Shorts/search/PiP/comments; Instagram Stories/Reels/Explore; Facebook Stories/Reels/Marketplace; Snapchat Spotlight/Stories; and X Videos/Explore. Unknown sections must not be described as confidently recognized.
- When an individual Instagram chat is confidently recognized through thread/composer signals, the open-chat exemption takes priority over section and daily-limit blocking. The Messages inbox itself is not exempt when Instagram section blocking is configured.
- An actively selected/current Instagram Stories, Reels, or Explore surface must beat a stale Messages container left in the accessibility tree; an actively selected Messages tab is classified as either inbox or open thread rather than receiving a blanket exemption.
- Blocking overlays cover only the target window's exposed regions; higher-layer pop-up/system windows must remain visible and interactive.
- Disabling or revoking the accessibility service must remove service-owned blocking overlays.

## Known Baseline Risks to Verify

These were confirmed in the 2026-07-11 audit. Treat them as context, not automatic scope. Verify the current source before relying on them because later changes may have fixed them.

- Usage-access checks in `MainActivity.kt` and `BootReceiver.kt` call API-29 `unsafeCheckOpNoThrow()` while min SDK is 24; the baseline `lintDebug` therefore fails and API 24-28 are unsafe.
- Usage-history aggregation and calendar queries run synchronously on the main thread.
- `POST_NOTIFICATIONS` is declared but has no runtime permission flow.
- Activity goal-editor state and service-side goal removal can diverge; a later activity save can restore a removed goal.
- Calendar import can turn the `Calendar Today` header and event lines into checkable goals and can remain stale on later days.
- Restarting the service can immediately show the active overlay because the last triggered bucket is not persisted.
- Release output is unsigned; production signing, CI release automation, store metadata, privacy documentation, analytics, and crash reporting are absent.
- The app uses privacy-sensitive capabilities: overlay access, usage history, calendar read access, boot restart, notifications, and a special-use foreground service.

Do not fix unrelated baseline risks inside a narrowly scoped task. Do not make them worse, and call out any one directly affected by the requested change.

## 6. Implementation Rules

### Workflow

Before editing:

1. Read this file.
2. Read `PROJECT_CONTEXT.md` for runtime behavior or unfamiliar subsystems.
3. Inspect the exact implementation and relevant tests.
4. Check `git status` and current diffs when available.
5. Identify the smallest safe change and likely regression surfaces.

Then implement directly. Do not stop after describing a plan unless the user requested analysis only.

### Scope control

- Make the smallest coherent change that satisfies the request.
- Do not refactor unrelated code.
- Do not rename unrelated files, classes, resources, preference keys, or packages.
- Do not reformat entire files for a localized change.
- Preserve user-authored work already present in the working tree.
- Do not discard or overwrite changes you did not create.
- Do not add dependencies when existing Android/Kotlin APIs reasonably solve the task.
- Explain any new dependency and keep it in the version catalog.
- Do not add speculative features, abstractions, telemetry, or cloud components.

### Kotlin and Android style

- Follow official Kotlin style with four-space indentation.
- Use `UpperCamelCase` for classes, data classes, enums, and composables.
- Use `lowerCamelCase` for functions and properties.
- Use `UPPER_SNAKE_CASE` for constants.
- Use `snake_case` for Android resources.
- Keep composables focused on presentation and event wiring.
- Keep platform-heavy usage, overlay, calendar, and service logic outside composables.
- Extract pure transformations and state machines so they can be unit-tested.
- Prefer explicit names over clever abstractions.
- Handle nullable/platform data deliberately.
- Avoid deprecated APIs unless compatibility requires a guarded fallback.

### API-level compatibility

The declared minimum SDK is 24. Every platform call must be safe from API 24 through the target SDK.

For newer APIs:

- add an explicit `Build.VERSION.SDK_INT` branch,
- provide a correct lower-API fallback,
- test both branches where practical,
- run lint,
- do not suppress or baseline a genuine compatibility error.

Do not raise the minimum SDK as a shortcut unless explicitly approved.

### Concurrency and lifecycle

- Do not run potentially slow usage-history scans or Calendar Provider queries on the main thread.
- Use lifecycle-aware coroutines for activity/UI work.
- Use a service-owned coroutine scope or equivalent lifecycle-safe mechanism for service background work.
- Cancel work when its owner is destroyed.
- Avoid duplicate polling loops, duplicate overlay attachment, duplicate navigation, and repeated submissions.
- Do not retain activity references in services or static objects.
- Treat permission revocation, provider failure, and `WindowManager` failure as recoverable where possible.
- Keep UI mutations on the main thread.

### State and persistence

- Keep one authoritative source for editable goal data.
- Reload or observe persisted changes before saving stale activity state.
- Do not change `SharedPreferences` file names or keys without a migration.
- Do not silently discard user data during representation changes.
- Model loading, success, empty, and error states for new asynchronous UI.
- Keep one-time events separate from durable state.
- Prefer stable IDs over display text for new structured data.

### UI, resources, and accessibility

- Reuse the existing design language unless a redesign is requested.
- Put new user-visible text in string resources.
- Prefer resource/theme tokens over new hardcoded colors, dimensions, and spacing.
- Preserve dark-mode usability.
- Test small screens and large font scales.
- Use start/end instead of left/right layout attributes.
- Add meaningful semantics, focusability, labels, and adequate touch targets.
- Do not make the full-screen overlay impossible to escape because of a UI regression.
- Keep the screen-time bubble's tap-versus-drag behavior distinct and accessible.
- Provide relevant loading, empty, disabled, success, and error behavior.

### Security and privacy

Never commit or print:

- SDK paths
- keystores
- signing passwords
- API keys
- tokens
- credentials
- private local configuration
- calendar titles or goal content in logs

Treat overlay access, usage access, calendar data, notifications, boot restart, and foreground-service behavior as privacy-sensitive.

The app is local-only. Adding a backend, accounts, analytics, ads, crash reporting, or cloud sync is an architectural and privacy change, not a routine implementation detail.

## 7. Tests and Verification

Name tests by observable behavior, for example:

```text
calendar_import_replaces_previous_section
usage_access_check_uses_legacy_fallback_before_api_29
service_restart_does_not_attach_duplicate_overlay
```

Use:

- JVM tests for pure goal, calendar, normalization, persistence transformation, time aggregation, and trigger logic.
- Instrumented tests for manifests, permissions, Android-version branches, services, overlays, notifications, boot behavior, and Compose UI.
- Manual device testing for special-access screens, overlay behavior, long-running services, boot restart, battery restrictions, and permission revocation.

Commands:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease
./gradlew bundleRelease
```

Verification expectations:

- Run the narrowest relevant tests while implementing.
- Before finishing a normal code change, run at minimum:
  - `./gradlew testDebugUnitTest`
  - `./gradlew lintDebug`
  - `./gradlew assembleDebug`
- Also run `assembleDebugAndroidTest` for manifest, permission, service, overlay, or UI changes.
- Run `connectedDebugAndroidTest` only when a device/emulator is available; report when unavailable.
- Run release assembly for Gradle, manifest, resource shrinking, ProGuard/R8, dependency, or release-sensitive changes.
- Never claim a command passed unless it actually ran successfully.
- Report pre-existing failures separately from failures caused by the change.
- Do not hide genuine problems with lint baselines or broad suppressions.
- When behavior cannot be automated, provide exact manual testing steps.

## 8. Documentation Rules

Update `PROJECT_CONTEXT.md` when a change materially affects:

- product behavior,
- screens or navigation,
- service lifecycle,
- timing or screen-time semantics,
- architecture or data flow,
- persistence keys or formats,
- permissions or Android components,
- dependencies or SDK requirements,
- build/release instructions,
- integrations,
- known limitations or production risks.

Update this `AGENTS.md` only when repository-wide working rules or stable architectural facts change.

Do not document speculative future architecture as though it exists.

## 9. Commit and Pull Request Guidance

Use short imperative commits, for example:

```text
Fix usage access check on API 28
Move calendar query off main thread
Prevent stale goal editor overwrite
```

A pull request or final implementation summary should state:

- user-visible behavior changed,
- implementation approach,
- affected files,
- affected Android versions and permissions,
- tests and commands run,
- manual verification steps,
- remaining limitations or risks.

Include screenshots or recordings for meaningful Compose or overlay visual changes.

## 10. Required Final Response From Codex

After implementing a task, provide:

1. Concise implementation summary.
2. Resulting user experience or behavior.
3. Files changed.
4. Important technical decisions.
5. Tests added or updated.
6. Exact verification commands and results.
7. Manual testing steps when needed.
8. Assumptions, limitations, or unresolved risks.
9. Confirmation that unrelated behavior was not intentionally changed.

Keep the response proportional to the change. Do not paste large unchanged code sections.

## 11. Compact Prompt Contract

Because this file supplies repository context and standard engineering rules, future prompts can remain focused.

A normal prompt only needs:

```text
Task:
[What should change.]

Current behavior:
[What happens now, including the bug or limitation.]

Expected behavior:
[What should happen after the change.]

Acceptance criteria:
- [Observable requirement]
- [Observable requirement]
- [Error, empty, permission, or edge-case behavior]

Task-specific constraints:
- [Only constraints not already covered by AGENTS.md]
```

The agent should inspect the implementation, make the smallest safe change, update relevant tests and documentation, run appropriate verification, and return the standard final summary without requiring the prompt to repeat repository-wide instructions.
