# TimeHUD Project Context

Audit date: 2026-07-11

Last implementation update: 2026-09-03

This document is a source-based technical handoff for the checked-in Android project. It describes the current repository, not a proposed architecture. Generated content under `app/build/`, `.gradle/`, `.gradle-work/`, and `.kotlin/` was ignored except for verification reports and build artifacts produced during this audit.

## 1. App overview

### Identity and purpose

- The Gradle project name, custom launcher icon, launcher label, and on-screen title are all branded **TimeHUD** (`settings.gradle.kts:25`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/values/strings.xml:2`, `app/src/main/java/com/boringutils/timehud/MainActivity.kt`).
- TimeHUD is a native Android focus/accountability utility. It measures how long the device screen has been interactive since a 3:00 AM daily boundary, displays the total in a movable always-on-top bubble, and periodically replaces it with a full-screen goal check-in (`OverlayService.kt`).
- The likely target user is someone who wants persistent awareness of screen time and repeated reminders of daily and longer-term goals. This is a reasonable inference from the default goals and UI copy, not an explicitly documented market definition (`GoalSettings.kt:28-34`, `overlay_active.xml:88-103`).
- The main problem it addresses is that ordinary screen-time information is easy to ignore. TimeHUD keeps the total available in a movable bubble over other apps and interrupts at five-minute usage buckets with goals and an optional calendar agenda.

### Primary user journey

1. Launch the single `MainActivity` from the app icon. The **Goals** destination opens first, with a hamburger drawer for **Goals**, **App usage**, **App limits**, and **Permissions**.
2. Open **Permissions** from the bottom of the drawer and grant special overlay access and usage access. The **Start HUD** button remains disabled until both are detected (`MainActivity.kt`). Calendar access is optional and is requested on this page.
3. On **Goals**, optionally edit short-term and long-term goals. The **Goal Backup** panel can export the current editor contents to JSON or restore a validated JSON backup after confirmation. The system document picker supplies one-time file access; no storage permission is requested (`MainActivity.kt`, `ui/backup/GoalBackupPanel.kt`).
4. Optionally import today's visible calendar events into the short-term text after calendar access is granted (`MainActivity.kt`, `CalendarAgenda.kt`).
5. Save goals or press **Start HUD**, which saves the current fields and starts `OverlayService` as a foreground service (`MainActivity.kt`).
6. A circular timer bubble appears over other apps and updates every 10 seconds. It can be dragged anywhere within the screen; the last position is restored when the bubble returns (`OverlayService.kt`, `BubblePositioning.kt`, `overlay_passive.xml`).
7. Once per newly observed five-minute total-screen-time bucket, a touchable full-screen overlay appears. It shows the time, up to five live calendar events, and up to eight short- or long-term goal lines (`OverlayService.kt:161-212`, `OverlayService.kt:236-260`, `OverlayService.kt:491-509`, `OverlayService.kt:528-532`). Because `lastTriggeredBucket` begins at `-1`, starting/restarting the service after at least five minutes of accumulated screen time can trigger this overlay immediately.
8. Tapping the bubble opens the same full-screen check-in on demand and enables its close button immediately. Automatically triggered five-minute check-ins keep the five-second close delay. In the check-in, the user can switch between short- and long-term goals, tap a goal, mark it done for today, remove it, or cancel. Completion shows a brief animation (`OverlayService.kt`, `overlay_active.xml`).
9. The persistent notification opens `MainActivity`. **Stop HUD** stops the service and clears the restart preference (`OverlayService.kt:100-125`, `MainActivity.kt:130-132`).
10. If the HUD was marked active, boot or package replacement attempts to restart it when overlay and usage permissions are still present (`BootReceiver.kt:17-35`).
11. Open **App usage** to refresh a top-five horizontal bar chart and a full longest-first list of per-app foreground time since the same 3:00 AM boundary. This history is read locally through `UsageStatsManager` and is not persisted or uploaded (`ui/usage/`).
12. Open **App limits** to search by app name or package and configure daily focused-use limits for launchable apps. Configured and recently used apps sort first. Supported per-section controls are YouTube Shorts/search/PiP/comments; Instagram Stories/Reels/Explore; Facebook Stories/Reels/Marketplace; Snapchat Spotlight/Stories; and the X Videos feed, full-screen video viewer, and Explore. Instagram can exempt an actually open chat, while the Messages inbox itself still shows the check-in when section blocking is configured. Enabling the optional TimeHUD accessibility service applies those rules independently of whether the HUD foreground service is running. Blocked content shows the same timer, agenda, and goals check-in used by the HUD; its Close button unlocks after five seconds and sends the user to Android Home (`ActiveOverlayContentController.kt`, `ui/blocking/`, `blocking/`).
13. The accessibility service observes all interactive windows, classifies supported navigation state locally, and draws touch-blocking accessibility-overlay pieces over only the exposed portion of a limited window. Higher-layer Samsung Pop-up View, split-screen, keyboard, and system-window rectangles are subtracted so they remain usable.

### Features confirmed in the current source

- Persistent draggable screen-time bubble with tap-to-open check-in behavior and saved position.
- Foreground service and ongoing notification.
- Screen-interactive-time calculation with a 3:00 AM boundary.
- Five-minute-bucket full-screen check-ins.
- Editable short-term and long-term goal lists.
- Hamburger navigation with Goals as the default page, App usage and App limits as secondary pages, and Permissions anchored at the bottom of the drawer.
- Per-app foreground-time chart and descending list using the 3:00 AM daily boundary.
- Searchable, configurable daily focused-use app limits, locally seeded from the current usage total.
- Optional section blocking for YouTube, Instagram, Facebook, Snapchat, and X, with a conversation-level open-chat exemption for Instagram but no blanket inbox exemption.
- Multi-window-aware accessibility shields that preserve higher-layer pop-up regions.
- Manual goal-only JSON export and replacement import through Android's Storage Access Framework, including confirmation and validation.
- Daily completion state, undo by tapping a completed row, permanent goal removal, and completion animation.
- Optional read-only import/display of today's visible device-calendar events.
- Start/stop state reflected in the setup UI while the process is alive.
- Conditional restart after boot or app replacement.

### Incomplete, absent, or only implied functionality

- There is no custom backend, cloud sync, account system, or network client. The application ID is `com.boringutils.timehud`, and the manifest does not request `INTERNET`.
- There are no explicit planned-feature documents, roadmap, or application TODOs. The only TODO found is template text in `data_extraction_rules.xml:8`; it is not evidence of a planned user feature.
- Notification permission is declared but never requested at runtime.
- A production signing setup, complete store metadata, release automation, privacy documentation, analytics, and crash reporting are absent. A Play-ready 512x512 icon source is checked in under `store-assets/`.

## 2. Current technology stack

| Area | Confirmed implementation |
|---|---|
| Language | Kotlin. Resolved Kotlin standard library `2.2.10`; the Compose compiler plugin alias is also `2.2.10` (`gradle/libs.versions.toml:9`, resolved Gradle dependency report). |
| UI | Mixed: Jetpack Compose for `MainActivity`; XML layouts plus platform `View` widgets and `WindowManager` for overlays. |
| Material | Compose Material 3. Declared Compose BOM `2024.09.00`; resolved `material3` is `1.3.0`. Compose UI artifacts resolve to `1.9.2` because newer direct/transitive constraints override the BOM's `1.7.0` constraints. |
| Min SDK | 24 (`app/build.gradle.kts:16`). Usage-access checks select the API-29+ method only on Android 10 or newer and use the compatible `checkOpNoThrow()` fallback on API 24-28. |
| Target SDK | 36 (`app/build.gradle.kts:17`). |
| Compile SDK | Android API `36.1` (`app/build.gradle.kts:8-12`). |
| Gradle | Wrapper `9.3.1` with SHA-256 verification (`gradle/wrapper/gradle-wrapper.properties:2-9`). |
| Android Gradle Plugin | `9.1.0` (`gradle/libs.versions.toml:2`, `:29`). |
| Java/JVM | Java source and target compatibility 11 (`app/build.gradle.kts:34-37`). Gradle daemon toolchain 21 (`gradle/gradle-daemon-jvm.properties:12`); IDE metadata selects `jbr-21` (`.idea/misc.xml:3`). |
| Core libraries | AndroidX Core KTX `1.18.0`, Lifecycle Runtime/ViewModel Compose `2.10.0`, and Activity Compose `1.13.0` (`gradle/libs.versions.toml`, `app/build.gradle.kts`). |
| Async/reactive | `StateFlow` for service-running and app-usage UI state; Android main-thread `Handler` for service ticks and delayed overlay UI work; lifecycle-aware coroutines with `Dispatchers.IO` for backup files and app-usage history. No RxJava. |
| Navigation | One launcher activity with a state-based Material 3 navigation drawer. Goals is the default destination; App usage, App limits, and Permissions are sibling destinations. No Navigation Compose graph or route arguments are needed. |
| Dependency injection | None. Android services are acquired through `Context`; helper objects are Kotlin singletons. |
| Persistence | Android `SharedPreferences` for goals, service state, bubble position, app-limit rules, and daily focused-use totals; no Room, DataStore, files, or cache database. |
| Networking/backend | None. No Retrofit, Ktor, Volley, Firebase, Supabase, API models, base URL, or backend service. |
| Serialization | Android platform `org.json.JSONObject` for the versioned goal-backup JSON. A test-only `org.json:json:20260522` dependency supplies the same API to JVM tests and is not packaged in the app. |
| Image loading | None. Only packaged launcher resources. |
| Authentication | None. |
| Analytics/crash reporting | None. |
| Unit testing | JUnit 4 `4.13.2`. |
| Instrumented testing | AndroidX Test JUnit `1.3.0`, Espresso Core `3.7.0`, and Compose UI test dependencies resolving to `1.9.2`. `MainNavigationTest` exercises drawer navigation when run on a device/emulator. |

The project is a single application module. It uses AGP 9's built-in Kotlin support; no separate Kotlin Android plugin is declared. No dependency lockfile is present.

## 3. Project architecture

### Architectural characterization

The app is a **single-module, component-oriented Android application**, not MVVM, MVI, or Clean Architecture:

- The app does not use app-wide MVVM, MVI, Clean Architecture, use cases, or dependency injection. The App usage destination adds one focused `AndroidViewModel` and a local usage repository without changing the rest of the architecture.
- `MainActivity` owns the Compose Goals/Permissions state, drawer selection, Android permission launchers, preference helpers, calendar helpers, and service start/stop functions.
- `OverlayService` is the runtime controller. It calculates usage, inflates and mutates overlay views, queries calendar data, and reads/writes goal persistence.
- Stateless or persistence-oriented behavior is extracted into singleton objects: `CalendarAgenda`, `GoalSettings`, `GoalCompletionStore`, `StartupPreferences`, and `OverlayServiceStateStore`.
- `AppUsageRepository` performs the platform usage-event query, while `AppUsageCalculator` contains the pure interval aggregation used by JVM tests.

### UI state and data flow

```text
Android special-access/settings state
  -> MainActivity permission checks
  -> local Compose booleans
  -> Start button enabled/disabled

SharedPreferences (goal text)
  <-> GoalSettings
  -> MainActivity rememberSaveable editor state
  -> OverlayService activeGoalConfig
  -> imperative XML goal rows

UsageStatsManager events + PowerManager.isInteractive
  -> OverlayService.queryScreenTimeMs()
  -> 10-second Handler tick
  -> passive TextView and five-minute bucket trigger

UsageStatsManager foreground/background events
  -> AppUsageRepository on Dispatchers.IO
  -> AppUsageCalculator
  -> AppUsageViewModel StateFlow
  -> chart and descending app rows

AccessibilityWindowInfo trees + app-limit SharedPreferences
  -> TimeHudAccessibilityService local supported-app classifier
  -> focused-window daily accounting
  -> app-limit decision engine
  -> exposed target region minus higher-layer windows
  -> TYPE_ACCESSIBILITY_OVERLAY shield pieces

Calendar provider
  -> CalendarAgenda
  -> optional imported goal text and live active-overlay agenda

Storage Access Framework document URI
  <-> GoalBackupStorage on Dispatchers.IO
  <-> GoalBackupFormat validation/serialization
  <-> current editor state and GoalSettings replacement

OverlayService lifecycle
  -> OverlayServiceStateStore StateFlow
  -> MainActivity.collectAsState()
  -> Start/Stop button
```

### State handling

- `MainActivity` keeps permission flags with `remember`, drawer selection plus editor text/status with `rememberSaveable`, and observes `OverlayServiceStateStore.uiState` with `collectAsState()`.
- An `ON_RESUME` observer rechecks overlay, usage, and calendar permissions after system screens return (`MainActivity.kt:181-191`).
- `rememberSaveable` restores the selected destination, unsaved goal edits, and status across ordinary activity recreation. App-usage loading/results live in an activity-scoped `AppUsageViewModel`; other screens still have no ViewModel or saved-state schema.
- `OverlayServiceStateStore` is process-memory only. `StartupPreferences` separately persists the user's active-HUD intent for boot/restart behavior (`OverlayServiceState.kt`, `StartupPreferences.kt`).
- Overlay state (`isActiveState`, selected goal mode, current view, five-minute bucket) exists only in service fields and is lost if the service/process is recreated (`OverlayService.kt:48-53`).

### Business logic, errors, and loading

- Screen-time aggregation and trigger logic live in `OverlayService` (`OverlayService.kt:519-602`).
- Per-app foreground interval aggregation lives in `AppUsageCalculator`; platform querying and label resolution live in `AppUsageRepository`; loading, permission-required, empty, success, and unavailable states live in `AppUsageViewModel`.
- Goal parsing, normalization, removal, and persistence live in `GoalSettings.kt`; completion-key and daily-date behavior live in `GoalCompletionStore.kt`.
- Calendar querying/formatting lives in `CalendarAgenda.kt`.
- Goal backup schema validation/serialization and one-time URI stream handling live in `GoalBackup.kt`; picker and confirmation state remain in `TimeHUDScreen`.
- There is no generalized app-wide loading/error model. Goal backup introduces typed parse/read/write results plus disabled/loading/success/error presentation while asynchronous work runs; older goal/calendar/service flows remain mostly synchronous and ad hoc.
- Calendar query `SecurityException` and `IllegalArgumentException` are converted to an empty list, so callers cannot distinguish failure from “no events” (`CalendarAgenda.kt:59-77`). Other service/overlay failures are not surfaced to the user.
- Backup document/JSON work and the new per-app usage-history query run on `Dispatchers.IO`. Existing calendar queries and the service's total screen-time aggregation still run on the main thread; the service `Handler` also drives UI ticks and animation delays.

## 4. Directory and module map

```text
TimeHUD_cloud/
  settings.gradle.kts                 Repositories, Foojay resolver, project name, :app module
  build.gradle.kts                    Root plugin aliases
  gradle.properties                   Gradle JVM memory and Kotlin code-style settings
  gradle/
    libs.versions.toml                 Plugin/library version catalog
    gradle-daemon-jvm.properties       JDK 21 daemon toolchain declaration
    wrapper/                           Pinned Gradle 9.3.1 wrapper
  app/
    build.gradle.kts                   Android namespace, SDKs, variants, dependencies
    proguard-rules.pro                 Default template; no app-specific keep rules
    src/main/
      AndroidManifest.xml              Permissions and Android component declarations
      java/com/boringutils/timehud/
        MainActivity.kt                Compose setup/control UI and permission/service actions
        OverlayService.kt              Foreground service, usage aggregation, overlay UI/runtime
        BootReceiver.kt                Conditional restart after boot/package replacement
        CalendarAgenda.kt              Calendar-provider model, query, and formatting
        GoalBackup.kt                  Goal-only JSON schema, validation, and SAF stream handling
        GoalSettings.kt                Goal model, parsing, defaults, preference persistence
        GoalCompletionStore.kt         Date-scoped completion persistence
        OverlayServiceState.kt         Process-local service StateFlow
        StartupPreferences.kt          Persistent “HUD active” preference
        blocking/
          AppBlockModels.kt            Pure rules, decisions, classifiers, and rectangle subtraction
          AppBlockSettings.kt          Rule/accessibility-status/focused-use persistence helpers
          TimeHudAccessibilityService.kt
                                        Window observer, supported-app inspection, usage enforcement, overlays
        ui/blocking/
          AppBlockingScreen.kt         App-limit destination, ViewModel, and rule editor
        ui/theme/
          Color.kt                     Template Compose color tokens
          Theme.kt                     Material 3 dynamic/light/dark theme selection
          Type.kt                      One customized bodyLarge typography style
        ui/backup/
          GoalBackupPanel.kt           Backup controls, status, and replace confirmation dialog
        ui/navigation/
          TimeHudDrawerScaffold.kt     Drawer destinations, top bar, and secondary-page Back handling
        ui/usage/
          AppUsageModels.kt            Pure foreground-interval aggregation and compact formatting
          AppUsageRepository.kt        UsageStats query and installed-app label resolution
          AppUsageScreen.kt            App-usage ViewModel, states, chart, and descending rows
      res/
        layout/overlay_passive.xml     Draggable screen-time bubble
        layout/overlay_active.xml      Full-screen check-in, agenda, modal, celebration layers
        values/                        App/notification strings, legacy colors, platform theme
        drawable/                      Launcher vectors and two currently unused backgrounds
        mipmap-*/                      Launcher icons
        xml/                            Backup/data-extraction template rules
    src/test/.../ExampleUnitTest.kt     Eight JVM unit tests
    src/test/.../GoalBackupTest.kt      Goal backup schema and calendar-exclusion JVM tests
    src/test/.../AppUsageCalculatorTest.kt
                                         App usage aggregation, boundary, and formatting tests
    src/androidTest/.../ExampleInstrumentedTest.kt
                                         Two device tests for package/component metadata
    src/androidTest/.../MainNavigationTest.kt
                                         Compose drawer-destination navigation test
  store-assets/
    timehud-play-store-icon-512.png     Play Console app icon
    timehud-adaptive-foreground-source.png
                                         Transparent source for the launcher mark
```

There is no `README`, `Application` subclass, additional module, CI directory, navigation resource, flavor source set with content, or application documentation other than this file.

## 5. Screen inventory

The overlay entries below are service-owned window layers rather than Android navigation destinations.

| Screen/layer | Route or destination | Main source | ViewModel | Purpose and entry | Important actions | Data dependencies |
|---|---|---|---|---|---|---|
| Goals page | Launcher `MAIN`/`LAUNCHER` -> `.MainActivity`; `GOALS` drawer destination | `MainActivity.kt`, Compose | None | Default in-app page; opened by launcher, notification, drawer, or Back from a secondary page | Edit/save/backup goals, import calendar, start/stop HUD, open Permissions when required access is missing | Goal/calendar/backup helpers, `OverlayServiceStateStore` |
| App usage page | `APP_USAGE` drawer destination | `ui/usage/`, Compose | `AppUsageViewModel` | Secondary page opened from the drawer | Refresh, inspect top-five chart, scroll every app longest-first, open Permissions when usage access is missing | `UsageStatsManager`, `PackageManager`, `AppUsageRepository` |
| App limits page | `APP_LIMITS` drawer destination | `ui/blocking/`, Compose | `AppBlockingViewModel` | Secondary page opened from the drawer | Search installed apps by name or package; enable the app-limit accessibility service; set/remove daily limits; configure supported section rules and the Instagram open-chat exemption | Usage history, `AppBlockSettings`, Android Accessibility Settings |
| Permissions page | `PERMISSIONS` drawer destination, anchored at the bottom | `MainActivity.kt`, Compose | None | Secondary page opened from the drawer or missing-access actions | Grant overlay, usage, and optional calendar access | Android Settings/AppOps and runtime permission controller |
| Goal Backup panel | Section within setup/control screen | `ui/backup/GoalBackupPanel.kt`, `MainActivity.kt` | None | Appears immediately below goal settings | Export current editor text; open and validate a JSON backup | Activity Result APIs, `GoalBackupFormat`, `GoalBackupStorage`, current editor state |
| Replace Goals dialog | Compose `AlertDialog`; no route | `ui/backup/GoalBackupPanel.kt` | None | Appears only after a complete valid backup is read | Replace both saved/editor goal groups or cancel | Pending validated goal text and `GoalSettings` |
| Screen-time bubble | No route; `WindowManager` overlay created by `OverlayService.showPassiveOverlay()` | `OverlayService.kt`, `BubblePositioning.kt`, `overlay_passive.xml` | Saved x/y position | Appears when the service starts and whenever active check-in closes | Drag to reposition; tap to open the active check-in | `UsageStatsManager`, `PowerManager`, `SharedPreferences` |
| Active goal check-in | No route; `showActiveOverlay()` from a bubble tap or once per observed five-minute bucket, or window-aware accessibility-overlay pieces for blocked apps | `ActiveOverlayContentController.kt`, `OverlayService.kt`, `blocking/TimeHudAccessibilityService.kt`, `overlay_active.xml` | None | Temporarily replaces the bubble or covers only the visible blocked-app regions while leaving higher Samsung Pop-up View windows exposed | Switch short/long goals and tap a goal; HUD closes return to the previous app, while a blocked-app close unlocks after five seconds and returns Home | Usage total, `GoalSettings`, `GoalCompletionStore`, live `CalendarAgenda`, accessibility global Home action |
| Goal completion modal | Child layer in active overlay (`layout_completion_modal`) | `OverlayService.kt:339-374`, `overlay_active.xml:162-227` | None | Opens when an incomplete goal row is tapped | Done for today, remove goal, cancel, tap scrim to dismiss | Active goal and completion/preferences stores |
| Completion celebration | Transient child layer in active overlay | `OverlayService.kt:376-489`, `overlay_active.xml:132-160` | None | Runs after marking a task complete | None | Current goal-row view |
| Overlay permission settings | External `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` | `MainActivity.kt:106-113` | N/A | Opened from Overlay Permission card | User grants/denies special access | Android Settings |
| Usage access settings | External `Settings.ACTION_USAGE_ACCESS_SETTINGS` | `MainActivity.kt:115-119` | N/A | Opened from Usage Access card | User grants/denies special access | Android Settings |
| Calendar permission dialog | Android runtime permission UI | `MainActivity.kt:197-215` | N/A | Opened by Connect/Import Calendar when needed | Grant/deny calendar read access | Android permission controller |

There are no splash, onboarding, authentication, profile, conventional settings, detail, or bottom-sheet screens.

## 6. Navigation flow

### Navigation model

- Start destination: exported `MainActivity`, declared with `singleTop` launch mode (`AndroidManifest.xml:24-35`).
- There is no authentication gate, nested navigation graph, bottom navigation, route parameter, or app link/deep link. A Material 3 drawer switches among four enum-backed Compose destinations without a navigation library.
- The notification `PendingIntent` uses `NEW_TASK | SINGLE_TOP | CLEAR_TOP`, bringing the existing activity to the front when possible (`OverlayService.kt:114-125`).
- Back closes an open drawer first; from App usage, App limits, or Permissions it returns to Goals; from Goals the default activity behavior leaves/finishes the activity. The foreground service and overlay continue until explicitly stopped or destroyed.
- Starting/stopping does not navigate. Overlay windows are attached by the service independently of the activity.
- Boot may start the service without opening an activity.
- Logout does not exist.
- The active window is created with `FLAG_NOT_FOCUSABLE`, so it does not own normal activity back handling. Closing is through its button: immediately for a bubble-opened check-in, or after five seconds for an automatic check-in.

```text
App icon or notification
  -> MainActivity -> Goals (default)
       -> hamburger -> App usage -> local 3:00 AM usage chart and descending list
       -> hamburger -> App limits
            -> accessibility disclosure -> Android accessibility settings
            -> app rule -> daily minutes and optional supported-app section controls
                 -> TimeHudAccessibilityService
                      -> focused-use accounting and supported-section classification
                      -> exposed blocked window regions minus higher pop-up windows
       -> hamburger -> Permissions
            -> Overlay permission card -> Android overlay settings -> resume MainActivity
            -> Usage card -> Android usage-access settings -> resume MainActivity
            -> Calendar card -> runtime permission dialog -> resume MainActivity
       -> Calendar button -> import into editor when calendar access is granted
       -> Export Goals -> system create-document picker -> write goal-only JSON
       -> Import Goals -> system open-document picker
            -> invalid/unreadable -> inline error; no data change
            -> valid -> Replace Goals dialog
                 -> Replace Goals -> save both groups and update editors
                 -> Cancel -> no data change
       -> Start HUD -> OverlayService
            -> draggable screen-time bubble
                 -> tap -> active full-screen check-in
            -> new five-minute usage bucket -> active full-screen check-in
                 -> goal tap -> completion modal -> done/remove/cancel
                 -> close after 5 seconds -> screen-time bubble
App limit/supported section detected -> shared goal check-in over visible blocked regions
                 -> close after 5 seconds -> Android Home
       -> Stop HUD -> service and overlays removed

Device boot or app replacement
  -> BootReceiver
       -> prior HUD active + overlay/usage access -> OverlayService
       -> otherwise no action
```

## 7. State management

### Important state owners

| Owner | State exposed/held | Inputs/events | Restoration and fragility |
|---|---|---|---|
| `TimeHUDScreen` composable | Permission flags; short/long editor strings; save/calendar/backup status; pending picker snapshots and validated import; collected service state | Permission taps, edits, calendar import, backup export/import/confirmation, start/stop, resume | Goal editor and pending backup state use `rememberSaveable`; file I/O uses a composition coroutine scope and returns to main state. A confirmed restore writes and updates both editor fields together, preventing pre-import editor text from being re-saved. The older service-side goal-removal divergence remains outside this flow. |
| `AppUsageViewModel` | Loading, ready/empty, permission-required, and unavailable states plus sorted app entries | Page composition/resume and manual Refresh | Activity-scoped ViewModel cancels a previous refresh before starting a new `Dispatchers.IO` query. Results are not persisted. |
| `AppBlockingViewModel` | Loading, usage-permission-required, launchable app list, current daily usage, and configured rules | Page composition/resume and rule save/removal | Activity-scoped ViewModel loads package/usage state on `Dispatchers.IO`; rules persist separately in `AppBlockSettings`. The App limits composable keeps its local search query across ordinary recreation and filters the already-loaded list without another provider query. |
| `TimeHudAccessibilityService` | Current focused configured package, focus interval, visible window classifications, blocking targets, and attached overlay pieces | Accessibility window/content events, scheduled daily-limit boundary checks, and the blocked-overlay Home action | System-owned optional service. Rules and accumulated daily usage persist; live nodes and overlays do not. Destruction/interruption flushes focus time and removes overlays. |
| `OverlayServiceStateStore` | `StateFlow<OverlayServiceUiState>` with `isRunning` and derived `primaryAction` | `markRunning()`, `markStopped()` from service lifecycle | Process-local only. It is sufficient for the current same-process activity/service but is not an authoritative cross-process/persistent service monitor. |
| `StartupPreferences` | Persistent `hud_active` boolean | Service `onStartCommand` marks true; explicit activity stop marks false | Used for boot restart intent. `OverlayService.onDestroy()` intentionally does not clear it, so system destruction still leaves restart intent set. |
| `OverlayService` / `ActiveOverlayContentController` | Current overlay views, active/passive flag, last bucket, goal snapshot, and goal mode | 10-second tick; switch, close, and goal-row clicks; the accessibility service reuses the content controller for blocked-app check-ins | Plain fields; not restored after service/process recreation. Restart resets the bucket and can immediately show active UI. |
| `GoalSettings` | Short/long raw strings | Load, save, remove | `SharedPreferences`; editor displays raw strings, overlay displays normalized nonblank lines capped at eight. |
| `GoalBackupFormat` / `GoalBackupStorage` | Versioned goal-only JSON and typed read/write results | Serialize current editor snapshot; validate selected document | Parsing produces no goal object until every required field is valid. Unknown fields are ignored. URI access is one-time and streams are always closed. |
| `GoalCompletionStore` | Set of normalized goal keys plus stored date | Complete/uncomplete goal | Daily by local `yyyy-MM-dd`; old stored keys remain on disk but are ignored after the date changes until a new completion is saved. |

There is no LiveData, Redux/MVI intent model, one-time event channel, snackbar system, or app-wide error state. App usage has focused loading/empty/permission/error states; calendar and backup status are rendered inline. Empty goals produce `- No goals saved yet`; empty calendar hides the agenda or reports “No visible calendar events today.”

## 8. Data layer

### Local persistence

All persistence is private `SharedPreferences`:

| Preference file | Keys | Owner | Content |
|---|---|---|---|
| `timehud_goals` | `short_term_goals`, `long_term_goals` | `GoalSettings.kt` | Multiline goal strings. Normal Save/Start trims outer whitespace; confirmed backup replacement preserves validated strings exactly. Default hardcoded seed goals are used until values are saved. |
| `timehud_goal_completion` | `completed_date`, `completed_keys` | `GoalCompletionStore.kt:16-56` | Local-date key and a string set such as `short:finish report`. |
| `timehud_startup` | `hud_active` | `StartupPreferences.kt:5-25` | Whether the HUD should restart after boot/update. |
| `timehud_app_blocking` | Configured package set plus per-package limit/blocked-surface/Messages keys | `AppBlockSettings.kt` | Local app-limit rules. Legacy Instagram Reels/Stories booleans migrate when loaded; saving writes the generalized surface set. Removing a rule removes its configuration keys. |
| `timehud_focused_app_usage` | 3:00 AM period start plus per-package seeded/usage keys | `FocusedAppUsageStore` | UsageStats-seeded daily totals plus focused accessibility-window time used for enforcement. Resets when the period start changes. |

- Writes use asynchronous `SharedPreferences.Editor.apply()`.
- Goal display trims bullet prefixes, drops blank lines, and silently shows only the first eight entries (`GoalSettings.kt`).
- Completion identity lowercases, removes diacritics, and reduces non-alphanumeric runs to spaces. Distinct display strings can therefore collide.
- `GoalSettings.removeGoal()` removes only the first normalized matching line and saves immediately.
- Android OS backup is disabled (`AndroidManifest.xml:15-18`). Users can manually export goal text, but there is no automatic backup, sync, migration, or recovery for completion/startup state; uninstall/clear-data still loses anything not manually exported.

### Calendar provider

- `CalendarAgenda.loadTodayVisibleInstances()` queries `CalendarContract.Instances` from local midnight through next local midnight, filters to visible and not-deleted instances, and sorts by begin/end (`CalendarAgenda.kt:56-89`).
- The response model is `TodayCalendarItem` with event/calendar IDs, title, start/end, all-day flag, calendar name, and optional color (`CalendarAgenda.kt:14-23`). Name/color/IDs are currently not shown or persisted as structured data.
- All-day events render as `All day <title>`; timed events use locale-default `HH:mm-HH:mm` (`CalendarAgenda.kt:107-115`).
- Manual import uses the shared `CalendarGoalSection` helper to remove everything from the first recognized `Calendar Today` header onward, then appends the new section (`CalendarAgenda.kt:25-35`, `:91-105`). It does not automatically refresh tomorrow, and imported lines become ordinary goal rows (including the header) if saved.

### Manual goal backup

- `GoalBackup.kt` defines JSON identifier `timehud-goals`, schema version `1`, export timestamp, and raw short-/long-term multiline strings. Unknown fields are ignored; required fields, types, identifier, and schema are validated before a `GoalBackup` is returned.
- Export snapshots the visible editor fields, including unsaved edits, then removes the reserved `Calendar Today` section from short-term text through `CalendarGoalSection.removeFrom()`. The generated header and every line after it are therefore excluded.
- Import also removes a recognized generated calendar section, then waits for explicit **Replace Goals** confirmation. Replacement writes both preference keys in one editor transaction and immediately updates both Compose editor values. Empty strings are valid and clear that group.
- Only goal text/order and `exportedAtEpochMs` metadata are in the file. Completion keys, calendar/provider data, usage totals/history, service/startup state, permissions, and unrelated configuration are never serialized.
- File access uses `CreateDocument(application/json)` / `OpenDocument`, suggested name `timehud-goals-backup.json`, one-time document URIs, `openOutputStream()` / `openInputStream()`, UTF-8, and `use` blocks. Reading, parsing, serialization, and writing run on `Dispatchers.IO`. No URI permission is retained.

Schema:

```json
{
  "format": "timehud-goals",
  "schemaVersion": 1,
  "exportedAtEpochMs": 0,
  "shortTermGoalText": "First goal\nSecond goal",
  "longTermGoalText": "Long-term goal"
}
```

### Usage data

- `queryScreenTimeMs()` reads `UsageStatsManager.queryEvents()` from the most recent 3:00 AM boundary, looking for numeric event types 15 and 16 (screen interactive/non-interactive), and uses `PowerManager.isInteractive` for an empty/current interval edge case (`OverlayService.kt:542-593`).
- There is no caching; the entire period is queried again every 10 seconds on the main thread.
- There is no structured error mapping, retry policy, telemetry, or test coverage for this algorithm.
- The App usage page separately reads per-package foreground/background events from the most recent 3:00 AM boundary on `Dispatchers.IO`. It totals intervals per package, resolves an application label when Android permits it, sorts by duration descending, and falls back to the package name when a label is unavailable.
- Per-app results are refreshed on page entry/resume or by the Refresh button and are not persisted. The screen models permission-required, loading, empty, unavailable, and success states; pure aggregation/boundary/formatting behavior has JVM tests.

### Network/API/database status

There are no API clients, base URLs, endpoints, network DTOs, repositories, Room entities/DAOs, DataStore, caches, offline queues, or synchronization jobs. The only serialization is the user-initiated local goal-backup JSON. The app remains entirely device-local and functional without network access.

## 9. Backend and external integrations

| Integration | Purpose | Configuration and interacting files | Secrets/config keys | Status |
|---|---|---|---|---|
| Android Usage Access/AppOps | Permission gate, screen-interactive events, and per-app foreground history | Manifest `PACKAGE_USAGE_STATS`; `MainActivity.kt`; `BootReceiver.kt`; `OverlayService.kt`; `ui/usage/` | None | Implemented with API 24-28 permission-check fallback; lower-API and real-device app-history behavior still need manual coverage. |
| Android `WindowManager` overlays | Passive and active HUD over other apps | Manifest `SYSTEM_ALERT_WINDOW`; permission intent in `MainActivity`; overlay creation in `OverlayService.kt:128-212` | None | Implemented; permission-revocation failures are not caught. |
| Android Calendar Provider | Optional import and live agenda | Manifest `READ_CALENDAR`; `MainActivity.kt:148-179`; `CalendarAgenda.kt`; `OverlayService.kt:175,491-509` | None | Implemented read-only. Query failures are treated as empty data. |
| Android foreground-service notification | Keeps service foreground and returns to app | Manifest foreground-service permissions/type; `OverlayService.kt:55-125`; strings resources | None | Implemented. Runtime notification permission handling is missing. |
| Android boot/package broadcasts | Restart previously active HUD | Manifest receiver; `BootReceiver.kt`; `StartupPreferences.kt` | None | Implemented with overlay/usage gates. |
| Android Storage Access Framework | One-time manual creation/opening of goal backup JSON documents | `MainActivity.kt`, `GoalBackup.kt`, `ui/backup/GoalBackupPanel.kt` | None; no retained URI grant | Implemented for goal-only export and confirmed replacement import. |

No Firebase/Supabase/custom backend, Google Play Services API, Maps, payments, push messaging, ads, cloud storage, social login, third-party API, analytics, or crash reporting is configured. No environment variable or secret is required by current application code.

## 10. Authentication and user session

Authentication does not exist. There is no login, registration, user model, token/session storage, refresh, protected route, role, password reset, logout, account deletion, or social provider.

The `hud_active` startup preference is only a local service-restart preference; it is not a user session. No credentials, API keys, signing passwords, or tokens were found in source/configuration. `local.properties` contains only an `sdk.dir` key and its value is intentionally not reproduced here.

## 11. Permissions and Android platform features

| Manifest permission | Why/where used | Request/handling | Refusal behavior |
|---|---|---|---|
| `SYSTEM_ALERT_WINDOW` | Add HUD windows through `WindowManager` | Special-access intent from `requestOverlayPermission()`; checked with `Settings.canDrawOverlays()` | Start button stays disabled. If revoked while running, overlay calls are not guarded and may fail. |
| `PACKAGE_USAGE_STATS` | Read usage events for screen time | Usage-access Settings intent; checked through `AppOpsManager` with an API-level-compatible fallback | Start button stays disabled; boot restart is skipped. |
| `READ_CALENDAR` | Query today's visible calendar instances | Optional runtime request on the Permissions page; Goals imports after access is granted | Inline denial/status message; HUD still works and agenda query returns empty. |
| `FOREGROUND_SERVICE` | Run `OverlayService` persistently | Normal install permission; no prompt | No custom refusal path. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Target-36 special-use foreground-service type | Declared with `foregroundServiceType="specialUse"` and explanatory property (`AndroidManifest.xml:47-54`) | No runtime UI; release policy/declaration requirements are outside this repo. |
| `POST_NOTIFICATIONS` | Permit foreground notification visibility on Android 13+ | Declared only; no runtime request exists | App does not explain or react to denial. Foreground-service UX/visibility can be reduced depending on platform behavior. |
| `RECEIVE_BOOT_COMPLETED` | Receive boot and package-replaced broadcasts | Normal install permission; receiver is exported and sender-protected (`AndroidManifest.xml:37-45`) | No restart if broadcast is unavailable or app is in a stopped state. |

Other platform usage:

- Foreground service: yes, `START_STICKY`, notification channel importance low.
- Broadcast receiver: yes, `BootReceiver` for boot and package replacement.
- Notifications: foreground-service notification only; no action buttons or push notifications.
- Content provider: system calendar read through `ContentResolver`; no app-owned provider.
- Documents: Storage Access Framework create/open contracts for manual goal backup; no broad storage/media permission and no permanent URI grant.
- Background scheduler: none; no WorkManager, AlarmManager, JobScheduler, or worker.
- Deep/app links: none.
- Camera, location, microphone, storage/media, Bluetooth, biometrics, NFC: not declared or used.

## 12. Design system and UI conventions

### Compose activity

- `TimeHUDTheme` uses Material 3 and chooses dynamic light/dark color on Android 12+, otherwise template purple schemes (`ui/theme/Theme.kt:14-57`).
- In practice, `MainActivity` hardcodes a dark `#0D0D1A` surface and most component colors, so system theme/dynamic color has limited effect (`MainActivity.kt:68-72`, `:216-321`).
- Typography only customizes `bodyLarge`; most screen text declares explicit `sp`, weights, and colors (`ui/theme/Type.kt`).
- Reusable Compose pieces are `PermissionCard`, `GoalSettingsPanel`, `GoalBackupPanel`, `GoalImportConfirmationDialog`, and `timeHudTextFieldColors()`.
- Setup layout is vertically scrollable and applies status/navigation-bar padding, which supports smaller screens better than the overlay.

### Overlay UI

- Overlays use platform XML `LinearLayout`, `FrameLayout`, `ScrollView`, `Button`, `Switch`, `CheckBox`, and programmatically created goal rows.
- The application XML theme is legacy `android:Theme.Material.Light.NoActionBar`, while the content is visually dark and the activity uses Material 3 Compose (`values/themes.xml:4`).
- Colors, spacing, strings, and text sizes are mostly hardcoded across Kotlin/XML. `bg_active.xml`, `bg_passive.xml`, and all legacy colors in `values/colors.xml` are currently unused according to lint.
- The 64dp bubble uses compact green monospace text; the active timer uses 74sp. The goals list scrolls, but the full active layout has fixed top content and no system-inset handling.

### Accessibility, localization, and states

- There is only a default English resource set; most visible Compose/XML/Kotlin copy is hardcoded and cannot be localized. Lint reports 15 hardcoded/set-text localization warnings.
- Lint reports the clickable completion-modal scrim as not focusable (`overlay_active.xml:167`).
- Dynamic goal rows are clickable and focusable and contain visible text/checkbox, but no semantic role/content description is set programmatically.
- `FLAG_NOT_FOCUSABLE` on the active overlay and fixed sizing create keyboard, switch-access, and large-font concerns. These flows have no accessibility tests.
- RTL support is declared, but `overlay_active.xml` uses `paddingLeft`/`paddingRight` and `layout_marginLeft`/`layout_marginRight` rather than start/end.
- There are no loading indicators. Empty goals and calendar are handled; broader service/permission/query errors are mostly absent or collapsed into empty states.
- Goal fields have no validation, length limit, or warning that only eight nonblank lines are displayed.

## 13. Build and environment setup

### Prerequisites

- Android Studio: exact version is not pinned or identifiable from the repo. Use a release that supports AGP `9.1.0`, Gradle `9.3.1`, and Android SDK `36.1`.
- JDK 21 is the repository-declared Gradle daemon toolchain and IDE JDK. Java source compatibility is 11.
- Install Android SDK Platform `android-36.1` and normal build/platform tools through Android Studio.
- `local.properties` must define `sdk.dir=<local Android SDK path>`. Do not commit machine-specific paths.
- Network access is needed on the first build to download the pinned Gradle distribution and declared Maven dependencies. No application runtime secret or backend config file is needed.

### Open and run

1. Open the repository root in Android Studio and allow Gradle sync.
2. Select the `app` configuration and an API 24+ emulator/device.
3. Run the debug app.
4. For a meaningful manual test, use a physical device or emulator image that supports Usage Access and overlay special-access screens. Calendar testing requires at least one local calendar/event.
5. Grant overlay and usage access in the app. Calendar permission is optional.

### Commands

```bash
# Debug APK
./gradlew assembleDebug

# JVM unit tests
./gradlew testDebugUnitTest

# Android lint
./gradlew lintDebug

# Compile the instrumented-test APK without running it
./gradlew assembleDebugAndroidTest

# Run device tests with an attached emulator/device
./gradlew connectedDebugAndroidTest

# Minified, resource-shrunk unsigned release APK
./gradlew assembleRelease

# Release Android App Bundle; signing still needs a production configuration
./gradlew bundleRelease
```

No ktlint, detekt, Spotless, or other formatting task is configured. In a restricted environment where the home Gradle cache is not writable, the audit successfully used `./gradlew --gradle-user-home .gradle-work <tasks>`; normal developer machines should use the default Gradle user home.

### Intentional debug-update APK hook

The repository includes a version-controlled `post-commit` hook for intentionally producing a fresh, locally installable debug update. Enable it once in this checkout with:

```bash
git config core.hooksPath .githooks
```

A commit whose message contains the exact marker `[apk]`, for example `git commit -m "Add weekly usage graph [apk]"`, runs `scripts/build-debug-update.sh`. The script runs the JVM unit tests, forces a fresh `assembleDebug`, reads `versionName` from `app/build.gradle.kts`, and copies the result to `app/release/TimeHUD-v<version>-debug-update.apk`. APK files remain ignored by Git. Commits without `[apk]` do not run the build.

The debug APK uses the local Android debug certificate and is intended only for direct testing updates on devices that already have a matching debug-signed build. It is not a Play Store release artifact. A failing post-commit build does not undo the commit that triggered it; fix the reported error and run `scripts/build-debug-update.sh` directly to retry.

## 14. Build variants and release process

- Modules: only `:app` (`settings.gradle.kts:26`).
- Namespace: `com.boringutils.timehud` (`app/build.gradle.kts:7`).
- Application ID for all variants: `com.boringutils.timehud` (`app/build.gradle.kts:15`).
- Identity migration: this replaces the former `com.example.timehud.cloud` application ID. Android treats the new ID as a different installed application, so preferences, goal data, granted special/runtime permissions, and HUD startup state from an older installation do not transfer automatically. The old and new packages can coexist until the old app is uninstalled.
- Version: code `4`, name `1.3` (`app/build.gradle.kts:18-19`).
- Variants: default `debug` and `release`; no product flavors or environment-specific source/config values.
- Release enables R8 code minification and resource shrinking and uses optimized default ProGuard rules plus an unchanged template `proguard-rules.pro` (`app/build.gradle.kts:24-32`).
- No signing configuration exists. `assembleRelease` produces `app/build/outputs/apk/release/app-release-unsigned.apk`.
- `bundleRelease` produces `app/build/outputs/bundle/release/app-release.aab`; the generated bundle is unsigned until a production keystore is supplied through Android Studio or a secure release-signing configuration.
- No complete Play Store metadata, package publishing plugin, release notes, privacy policy, CI/CD, or deployment configuration is present. The app icon asset is available under `store-assets/`.
- No environment-specific backend URL exists because there is no backend.

Production blockers/concerns:

1. Release signing and a secure secret-management process are absent.
2. Runtime notification-permission handling is absent.
3. Special-use foreground service, overlay, usage access, and calendar access need Play-policy/privacy review and clear user disclosure.
4. There is no automated CI gate or reproducible release checklist.
5. The declared old Compose BOM is not actually controlling all resolved Compose versions; UI resolves to `1.9.2` while Material 3 remains `1.3.0`.

## 15. Testing status

### Existing tests

`app/src/test/java/com/boringutils/timehud/ExampleUnitTest.kt` contains 12 passing JVM tests:

- Start/stop primary-action derivation.
- Calendar-section append, replacement, and empty behavior.
- Goal-completion key normalization.
- Stored completion-date comparison.
- First-matching goal-line removal.

`app/src/test/java/com/boringutils/timehud/GoalBackupTest.kt` contains 14 passing JVM tests covering multiline and empty round trips; quotes, backslashes, Unicode, punctuation, and newlines; calendar-section exclusion and manual ordering; empty/invalid JSON; wrong identifiers; unsupported versions; missing/type-invalid fields; ignored unknown fields; and all-or-nothing parse failure.

`app/src/test/java/com/boringutils/timehud/AppUsageCalculatorTest.kt` contains six passing JVM tests covering repeated foreground intervals, an app already active at the period boundary, an app still active at refresh time, duplicate background events, compact duration formatting, and the pre-3:00 AM previous-day boundary.

`app/src/test/java/com/boringutils/timehud/blocking/AppBlockingTest.kt` covers open-chat versus inbox behavior, stale Messages-container transitions into Reels, generalized surface rules, X full-screen-player recognition without matching ordinary inline videos, all screenshot-requested option sets and classifiers, daily-limit boundaries, central and edge pop-up subtraction, and full-screen occlusion.

`app/src/test/java/com/boringutils/timehud/ui/blocking/AppBlockingScreenTest.kt` contains four pure search-filter tests covering blank-query ordering, case-insensitive app-name and package-name matching, and no-result behavior.

`app/src/androidTest/java/com/boringutils/timehud/ExampleInstrumentedTest.kt` contains three device tests:

- Application package name.
- Manifest registration/export/permission/metadata status for `BootReceiver`, `OverlayService`, and `TimeHudAccessibilityService`.
- Passive overlay bubble interaction metadata.

`MainNavigationTest.kt` adds one Compose UI test that opens App usage, App limits, and Permissions through the drawer. It compiles into the Android-test APK but still requires a connected device/emulator to execute. There are no service-lifecycle, permission-grant/revoke, ViewModel, repository-platform, screenshot, or end-to-end tests.

### Verification performed on 2026-07-11

| Command | Result | Notes |
|---|---|---|
| `env GRADLE_USER_HOME=.gradle-work ./gradlew --version` | Passed after network approval | Downloaded and verified the pinned Gradle `9.3.1`; daemon toolchain is Java 21. The first attempts failed only because the default cache was read-only and sandbox networking was blocked. |
| `./gradlew --gradle-user-home .gradle-work testDebugUnitTest lintDebug assembleDebug assembleRelease` | Overall failed at `lintDebug` | `testDebugUnitTest` passed all 8 tests and `assembleDebug` passed. Lint found 2 errors and 43 warnings. The combined invocation stopped before final release packaging. Kotlin emitted deprecation warnings for `unsafeCheckOpNoThrow`; native library stripping also emitted a non-fatal packaging warning. |
| `./gradlew --gradle-user-home .gradle-work assembleRelease assembleDebugAndroidTest` | Passed | Produced a minified/resource-shrunk unsigned release APK and compiled/packaged the instrumented-test APK. `lintVitalRelease` passed. |
| `./gradlew --gradle-user-home .gradle-work :app:dependencies --configuration debugRuntimeClasspath` | Passed | Used to record resolved dependency versions. |
| `adb devices` | No attached devices | ADB started successfully outside the sandbox, but listed no emulator/device. `connectedDebugAndroidTest` therefore was not run. |
| `./gradlew --gradle-user-home .gradle-work testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` | Passed after goal-backup implementation | The run passed with 21 JVM tests at that point; debug APK, instrumented-test APK, and minified unsigned release APK assembled successfully. `lintVitalRelease` passed. A final focused run then passed all 22 tests after adding the empty-file case. |
| `./gradlew --gradle-user-home .gradle-work lintDebug` | Failed on unchanged baseline | Still reports exactly 2 errors and 43 warnings. The errors remain the API-29 `unsafeCheckOpNoThrow()` calls in `BootReceiver.kt:44` and `MainActivity.kt:98`; no goal-backup lint finding was reported. |
| `./gradlew --gradle-user-home .gradle-work testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` | Passed after package rename | All 22 JVM tests passed under `com.boringutils.timehud`; debug, instrumented-test, and minified unsigned release APKs assembled. Output metadata confirms app ID `com.boringutils.timehud` and test app ID `com.boringutils.timehud.test`. |
| `./gradlew --gradle-user-home .gradle-work lintDebug` | Failed on unchanged baseline after package rename | Still exactly 2 errors and 43 warnings, now referencing the moved `com/boringutils/timehud` sources. |

The two API compatibility errors recorded in the 2026-07-11 runs were later fixed by guarding `unsafeCheckOpNoThrow()` and using `checkOpNoThrow()` on API 24-28. Other warning groups included hardcoded/non-localizable text, one keyboard-inaccessible clickable view, unused resources, null-root layout inflation, redundant SDK checks, dependency-version notices, and KTX suggestions. Generated report: `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`.

### Verification performed on 2026-08-24

| Command/check | Result | Notes |
|---|---|---|
| `./gradlew --gradle-user-home .gradle-work lintDebug` | Passed | The guarded API-29 call and API 24-28 fallback clear the prior two lint errors. |
| `./gradlew --gradle-user-home .gradle-work testDebugUnitTest assembleDebug assembleDebugAndroidTest bundleRelease` | Passed | JVM tests passed; debug APK and instrumented-test APK compiled; minified release AAB built with release lint-vital checks. |
| Release manifest metadata | Passed | Confirms application ID `com.boringutils.timehud`, version code `4`, and version name `1.3`. |
| `jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab` | Unsigned as expected | A production keystore is not configured; the bundle must be signed before Play Console upload. |

### Verification performed on 2026-09-01

| Command/check | Result | Notes |
|---|---|---|
| `./gradlew --gradle-user-home .gradle-work testDebugUnitTest` | Passed | All 32 JVM tests passed, including the six new per-app usage aggregation/boundary/formatting tests. |
| `./gradlew --gradle-user-home .gradle-work lintDebug assembleDebug assembleDebugAndroidTest` | Passed | Lint completed with zero errors and 41 warnings; the debug APK and Android-test APK compiled. The Compose drawer test was compiled but not run because no connected device/emulator was used. |
| `./gradlew --gradle-user-home .gradle-work assembleRelease` | Passed | The minified and resource-shrunk unsigned release APK compiled with the new lifecycle dependency. Android's native-library stripping task emitted its existing non-fatal `libandroidx.graphics.path.so` packaging note. |
| `adb devices` | No attached devices | The drawer test and real Usage Access/provider behavior could not be executed in this workspace. |
| `git diff --check` | Passed | No whitespace errors were introduced. |

### Verification performed on 2026-09-01 for app limits

| Command/check | Result | Notes |
|---|---|---|
| `./gradlew --gradle-user-home .gradle-work testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease` | Passed | All 39 JVM tests passed. Lint completed with zero errors and 41 non-fatal warnings. Debug, Android-test, and minified/resource-shrunk unsigned release APKs assembled. |
| `adb devices` | No attached devices | Accessibility enablement, real supported-app view identifiers, Samsung Pop-up View layering, split-screen, rule enforcement, and the compiled device tests were not run. |
| `git diff --check` | Passed | No whitespace errors were introduced. |

### Verification performed on 2026-09-03 for App limits search and X Videos

| Command/check | Result | Notes |
|---|---|---|
| `./gradlew --gradle-user-home .gradle-work testDebugUnitTest` | Passed | All 57 JVM tests passed, including App limits search coverage plus X Videos option, decision, feed/full-screen/inline classifier, and stale-node priority coverage. |
| `./gradlew --gradle-user-home .gradle-work lintDebug assembleDebug assembleDebugAndroidTest` | Passed | Lint, the debug APK, and Android-test APK compilation completed successfully. |
| `adb devices` | No attached devices | A supplied device screenshot exposed the original X full-screen-viewer false negative; the corrected classifier and search UI were not rerun on an attached device or emulator. |
| `git diff --check` | Passed | No whitespace errors were introduced. |

### Coverage gaps

The most important untested behavior is also the most failure-prone: total screen-time aggregation and boundary cases; five-minute triggering; foreground-service lifecycle/restart; overlay add/remove behavior; permission grant/revoke; real-device/vendor `UsageStatsManager` event completeness and label visibility; real YouTube/Instagram/Facebook/Snapchat/X accessibility-tree signals; Samsung Pop-up View and split-screen layer/bounds behavior; accessibility-service revocation; notification behavior; boot receiver decision logic; real SharedPreferences date rollover; calendar-provider failures; active modal/accessibility behavior; state synchronization between the activity and service; and actual system-picker/`ContentResolver` backup UI flows. App-time aggregation, blocking decisions/geometry, and backup schema/text behavior are unit-tested, but platform providers and compiled device tests still need device coverage.

## 16. Current implementation status

### Fully implemented in source

- Single activity with a hamburger drawer, Goals default page, App usage and App limits secondary pages, Permissions bottom destination, and permission status refresh.
- Local per-app foreground-time graph and longest-first rows using the 3:00 AM boundary, explicit loading/empty/error states, and manual refresh.
- Local daily app-limit rules for launchable apps, focused-window accounting, supported YouTube/Instagram/Facebook/Snapchat/X section recognition, Instagram open-chat exemption, and higher-window region subtraction for blocking shields.
- Foreground overlay service, draggable bubble, active check-in, notification, explicit stop.
- Local goal editing, defaults, persistence, daily completion, undo/removal, and celebration UI.
- Manual goal-only JSON export and validated, confirmed replacement import through the system document picker.
- Optional calendar permission, import, and live agenda rendering.
- Prior-active conditional restart after boot/package replacement.
- Debug/release compilation and basic project-specific unit/manifest tests.

“Fully implemented” here means a complete code path exists; it does not mean production-hardened or comprehensively tested.

### Partial/fragile functionality

- API 24-28 usage-access behavior has a compatible code path but has not been exercised on physical devices or emulators in this workspace.
- Notification permission is manifest-only (`AndroidManifest.xml:8`); no `POST_NOTIFICATIONS` launcher exists.
- Calendar errors and empty calendars are indistinguishable (`CalendarAgenda.kt:59-77`). Imported calendar text is a manual snapshot and can remain stale on later days.
- Service state is split between an in-memory StateFlow and persistent desired-start preference, with no OS-level service query.
- Release assembly works but produces an unsigned APK.

### Placeholders, hardcoded data, and disabled code

- Hardcoded default goals are user-visible seed data (`GoalSettings.kt:28-34`), not remote/mock API data.
- Much of the UI copy/color/spacing is hardcoded in `MainActivity.kt` and the two overlay layouts.
- `backup_rules.xml`, `data_extraction_rules.xml`, `proguard-rules.pro`, theme colors, and typography retain Android Studio template content. Android OS automatic backup remains disabled; this is separate from manual goal export.
- `bg_active.xml`, `bg_passive.xml`, and `values/colors.xml` resources are unused according to lint.
- Empty `app/src/debug/res/values/` exists but defines no debug override.
- No meaningful application code is commented out or disabled. No app-feature TODO/FIXME/HACK markers were found.

### Known behavioral issues supported by code

- If the service removes a goal while `MainActivity` remains alive behind the overlay, the activity's independent saved editor state is not refreshed; a later Save/Start can restore the removed line.
- Saving imported calendar text still causes the literal `Calendar Today` header and event lines to be parsed as checkable goals in the live editor/HUD, with only the first eight total lines shown. Manual goal backups specifically exclude that generated section (`CalendarAgenda.kt:25-35`, `GoalBackup.kt:18-27`).
- Restarting the service after five minutes of accumulated usage can immediately open the active overlay because the last bucket is not persisted.
- Goal normalization can make visually different goals share one completion key.

## 17. Important files

| File | Responsibility | Why it matters | Interacts with |
|---|---|---|---|
| `settings.gradle.kts` | Project/module/repository configuration | Confirms one-module structure and project name | Root build, `:app`, version catalog |
| `gradle/libs.versions.toml` | Central dependency/plugin versions | Source of declared stack versions | Root/app Gradle files |
| `app/build.gradle.kts` | Android SDKs, IDs, variants, R8, dependencies | Governs compatibility and release output | Manifest, source sets, ProGuard |
| `app/src/main/AndroidManifest.xml` | Permissions/components/FGS type | Defines every platform capability and entry point | `MainActivity`, `OverlayService`, `BootReceiver` |
| `MainActivity.kt` | Compose setup UI and permission/service actions | Only activity and user configuration surface | Goal/calendar helpers, service/state store, Android Settings |
| `OverlayService.kt` | Foreground runtime and all HUD behavior | Core of the product; highest regression surface | XML overlays, usage/power services, goals, calendar, notification, activity |
| `CalendarAgenda.kt` | Calendar query/model/format/import transformation | Owns all calendar semantics and failure collapsing | Activity import, active agenda, calendar provider |
| `GoalBackup.kt` | Goal backup model, schema validation, JSON codec, and SAF stream handling | Enforces goal-only, all-or-nothing, versioned import/export | Main activity, calendar-section helper, `ContentResolver` |
| `GoalSettings.kt` | Goal defaults/model/parsing/storage/removal | Defines what counts as a displayed goal and identity normalization | Activity editor, overlay rows, completion store |
| `GoalCompletionStore.kt` | Date-scoped completion persistence | Controls daily done/undo behavior | Overlay goal interactions, `GoalMode`/normalization |
| `OverlayServiceState.kt` | In-process running state | Drives Start/Stop button | Activity and service lifecycle |
| `StartupPreferences.kt` | Persistent desired restart state | Controls boot behavior and explicit stop semantics | Activity, service, receiver |
| `BootReceiver.kt` | Boot/update restart gate | Background entry point with API compatibility issue | Startup preferences, permission checks, service |
| `res/layout/overlay_active.xml` | Full-screen overlay/modal/celebration structure | Most complex UI and accessibility surface | `OverlayService` view IDs/mutations |
| `res/layout/overlay_passive.xml` | Passive timer structure | Always-visible runtime UI | `OverlayService` |
| `ui/theme/Theme.kt` | Compose Material theme selection | Defines theme defaults, though activity overrides many values | `MainActivity`, color/type files |
| `ui/backup/GoalBackupPanel.kt` | Backup controls, status messages, and confirmation dialog | Owns the user-facing backup surface | `MainActivity`, string resources |
| `ui/navigation/TimeHudDrawerScaffold.kt` | Drawer and enum-backed page switching | Separates setup, usage, and permission concerns without a route dependency | `MainActivity`, Back handling |
| `ui/usage/` | Per-app usage aggregation, platform query, ViewModel, and Compose presentation | Owns the new chart/list feature and keeps provider work off the main thread | `UsageStatsManager`, `PackageManager`, Lifecycle ViewModel |
| `blocking/` | App-limit rules, persistence, focus tracking, supported-app classification, accessibility service, and overlay geometry | Owns local enforcement and multi-window-aware blocking | Accessibility APIs, UsageStats seed, SharedPreferences, WindowManager |
| `ui/blocking/` | App-limit list, accessibility disclosure/status, and rule dialog | Owns user configuration for daily and supported section rules | `AppBlockSettings`, `AppUsageRepository`, Android Accessibility Settings |
| `ExampleUnitTest.kt` | Twelve existing pure behavior tests | Covers calendar, goals, completion keys, bubble positioning, triggers, and service UI state | Calendar, goals, completion keys, overlay helpers, service UI state |
| `GoalBackupTest.kt` | Fourteen backup codec/text tests | Protects schema compatibility, special characters, exclusion, and error atomicity | `GoalBackup`, `CalendarGoalSection`, test-only `org.json` runtime |
| `AppUsageCalculatorTest.kt` | Six per-app usage tests | Protects interval aggregation, reset boundary, duplicate handling, and display formatting | `AppUsageCalculator`, duration helpers |
| `AppBlockingTest.kt` | Nineteen pure app-blocking tests | Protects decision priority, all supported option/classifier mappings, X full-screen-versus-inline-video recognition, limits, and pop-up region subtraction | `AppBlockDecisionEngine`, `AppSurfaceClassifier`, `VisibleRegionCalculator` |
| `AppBlockingScreenTest.kt` | Four pure App limits search tests | Protects blank-query ordering, app-name/package matching, and no-result filtering | `BlockableAppUi`, `filterBlockableApps` |
| `ExampleInstrumentedTest.kt` | Package/manifest/bubble device tests | Basic Android component and overlay metadata coverage | Built manifest, Android package manager, overlay XML |
| `MainNavigationTest.kt` | Drawer Compose UI test | Checks that App usage, App limits, and Permissions are reachable | `MainActivity`, Compose test runtime |

Future work on screen-time behavior should read `OverlayService.kt`, `AndroidManifest.xml`, `MainActivity.kt`, and the overlay layouts together. Goal/calendar changes should also read all three preference/helper files because normalized strings are shared identifiers.

## 18. Risks and fragile areas

| Severity | Risk | Evidence and impact |
|---|---|---|
| **Medium** | API 24-28 permission fallback lacks device coverage | Both permission checks use the compatible `checkOpNoThrow()` path below API 29, but this branch has only static/lint verification in this workspace. |
| **High** | Selective app recognition and Samsung window layering need device validation | YouTube, Instagram, Facebook, Snapchat, and X view identifiers/content descriptions plus Samsung AccessibilityWindowInfo layers can change by app, language, One UI, and Android version. Pure classification and geometry tests cannot prove live section recognition or pop-up z-order. |
| **High** | No production signing/release process | Release APK is unsigned; no keystore indirection, CI, store configuration, or release checklist exists. Production delivery is not reproducible. |
| **High** | Main-thread period-wide usage query | Every 10 seconds, `ScreenTimeDisplay.queryMs()` scans usage events from 3:00 AM on the main looper; a newly attached blocked-app check-in also reads it once. Large histories or slow providers can delay overlay UI/service responsiveness and risk ANRs. Calendar queries also run on the main thread. |
| **High** | Sensitive platform capabilities lack production/privacy hardening | Always-on-top UI, usage history, calendar data, boot restart, and special-use foreground service have no onboarding rationale, privacy policy/store declaration, or denial/revocation recovery in repo. This is a policy and trust risk. |
| **Medium** | Overlay failures are not contained | `WindowManager.addView()` and service startup have no `SecurityException`/bad-token handling. Permission revocation or vendor behavior can crash the service instead of showing recovery UI. |
| **Medium** | Screen-time correctness is unverified | The algorithm uses raw event IDs, inferred period-start state, a 3:00 AM boundary, and no tests. Changes can easily double-count/miss intervals. |
| **Medium** | Notification permission not requested | `POST_NOTIFICATIONS` is declared but no runtime flow or denial state exists, reducing foreground-service transparency on recent Android versions. |
| **Medium** | Activity/service goal state can diverge | Activity editor is a saved local copy; service removal writes preferences independently. A stale activity save can resurrect deleted data. |
| **Medium** | Non-goal local data can be lost | Manual JSON backup now protects goal text when the user exports it, but `allowBackup=false` and no automatic backup/sync mean completion/startup state and unexported changes are still lost on uninstall/clear-data by design. |
| **Medium** | Compose dependency alignment is surprising | BOM `2024.09.00` constrains UI `1.7.0`, but resolved UI is `1.9.2` while Material 3 is `1.3.0`. Builds pass, but future dependency changes can expose binary/UI incompatibility. |
| **Medium** | Active overlay accessibility/responsiveness | Non-focusable full-screen window, fixed 74sp timer/top content, untranslated hardcoded strings, left/right layout attributes, and lint keyboard warning can break large-font, keyboard/switch, RTL, or small-screen use. |
| **Medium** | Automatic full-screen interruption has weak escape behavior | It covers all apps and intercepts touches, while close is disabled for five seconds and normal Back is not owned by the overlay window. Bubble-opened check-ins can close immediately, but an automatic-flow UI regression can still trap input until the service is stopped externally. |
| **Low** | Calendar import semantics are lossy/stale | Import removes all lines after an exact header, persists a day-specific snapshot, and turns header/events into goal rows. Provider exceptions look like an empty calendar. |
| **Low** | Goal identity collisions | Normalization strips punctuation/diacritics and truncates display to eight rows, so distinct goals can share completion state or disappear from overlay without warning. |
| **Low** | Design/resources are inconsistent | Compose Material 3, a legacy platform theme, platform overlay widgets, template colors, and hardcoded tokens coexist; unused resources increase maintenance noise. |
| **Low** | No crash/analytics diagnostics | Runtime failures are mostly invisible beyond a `TimeHUD` debug log message; production diagnosis would be difficult. |

No exposed credential was found. The main security concern is privileged local capability and data handling, not leaked secrets.

## 19. Rules for future changes

These rules reflect the existing small architecture while addressing its actual boundaries:

1. Do not describe the current app as MVVM/Clean Architecture. Preserve the simple single-module structure for small changes; introduce layers only when a real second data source/screen warrants them.
2. Keep platform-heavy overlay/usage logic out of composables. `MainActivity` should coordinate UI; `OverlayService` and focused helpers should own platform calls.
3. Treat `OverlayService` as a high-risk runtime component. Preserve foreground startup timing, passive/active view cleanup, `START_STICKY` intent, boot preference semantics, and five-minute bucket behavior unless a change explicitly redesigns them.
4. Fix and test API-level branching whenever adding platform APIs. Every call must be valid from min SDK 24 or the min SDK must be deliberately changed in a separate approved change.
5. For new Compose screens, create a `ui/<feature>/` package instead of making `MainActivity.kt` larger. There is currently no router; add a navigation dependency/graph only when a second true in-app destination exists, centralize route names/arguments, and test launch/back behavior.
6. There are no ViewModels today. For new screen state that must survive recreation or perform async work, use an AndroidX `ViewModel` with an immutable state data class and `StateFlow`; do not add more process-global mutable UI singletons.
7. Keep one authoritative source for editable goal state. Preference changes made from the service must be observable or explicitly reloaded before the activity saves.
8. Keep pure transformations (`GoalText`, calendar formatting, time aggregation) separated and unit-testable. Move screen-time aggregation out of the service before materially changing it.
9. Do not perform provider/history queries on the main thread. Use lifecycle-aware coroutines or a dedicated worker context and return explicit loading/success/error state where UI waits.
10. Do not silently turn provider errors into “empty” unless that distinction is intentionally unimportant. Show actionable permission/service errors in the setup UI and keep logs free of private calendar/goal text.
11. There is no DI framework. Prefer constructor/function parameters and small interfaces for new helpers. Do not add a DI framework solely for one dependency; if the dependency graph grows, adopt one in an explicit architectural change.
12. There are no repositories today. If adding an API or database, put it behind a repository interface rather than calling it from Compose or `OverlayService`.
13. Add new API calls through a single configured client, map transport DTOs to app models, model failures, and add repository tests. Put non-secret URLs in variant-aware Gradle/BuildConfig configuration and secrets in uncommitted local/CI secret stores. Never put credentials in Kotlin, XML resources, `gradle.properties`, `local.properties`, or signing blocks.
14. Centralize user-visible strings, colors, spacing, and accessibility semantics. Test large font, TalkBack/keyboard, RTL, small screens, light/dark mode, and overlay insets.
15. Add every new permission to a documented grant/deny/revoke flow; manifest declaration alone is insufficient.
16. Add unit tests for new pure logic, device/UI tests for manifest/permission/navigation/overlay behavior, and regression tests for screen-time boundaries and persistence migration.
17. Before committing, run at minimum `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `bundleRelease`, and `assembleDebugAndroidTest`; run `connectedDebugAndroidTest` on attached API 24/28/29+ devices where available.
18. Keep release signing values outside source control and document the secure CI/release path before store distribution.
19. Preserve goal-backup identifier `timehud-goals` and schema version `1`. Add a new schema version and backward-compatible parser deliberately; never add completion, calendar, service, permission, usage, device, or unrelated configuration data to this goal-only format.

## 20. Recommended next steps

Do not treat this list as work already completed.

### Critical fixes

1. Exercise the guarded usage-access check on API 24/28/29+ devices and add regression coverage for both platform branches.
2. Move usage-event and calendar-provider queries off the main thread and isolate/test the screen-time state machine, including 3:00 AM, first-event, currently interactive, restart, and bucket-boundary cases.
3. Catch overlay/service permission failures and provide a recovery path when special access is revoked while running.
4. Define a production signing and secret-management process; do not distribute unsigned release artifacts.

### Important improvements

1. Add runtime `POST_NOTIFICATIONS` handling with clear explanation and denial behavior.
2. Establish one observable goal source so service removals and activity edits cannot overwrite each other.
3. Separate imported calendar agenda data from manually editable/checkable goal text, and surface query errors distinctly from no events.
4. Align the Compose BOM/direct dependencies intentionally and document why versions differ.
5. Add clear first-run disclosure/privacy documentation for usage, overlay, calendar, boot, and foreground-service behavior.

### Testing improvements

1. Unit-test extracted screen-time aggregation and trigger logic.
2. Add SharedPreferences tests for save/remove, date rollover, duplicate normalized goals, and startup intent.
3. Add instrumented tests for grant/deny/revoke, boot receiver gating, notification channel, and service lifecycle.
4. Add Compose UI tests for enablement, save/import status, recreation, and Start/Stop state.
5. Add overlay UI/accessibility tests across API levels, font scales, screen sizes, and RTL.
6. Add CI that runs unit tests, lint, debug/release assembly, and instrumented tests where an emulator is available.

### User-experience improvements

1. Explain the 3:00 AM reset and five-minute interruption behavior in-app.
2. Let users configure reminder cadence/reset boundary or temporarily pause the HUD, if product intent supports it.
3. Provide explicit save/validation feedback and warn when more than eight goals will be hidden.
4. Make the active overlay safely dismissible through accessible Back/keyboard/switch controls while preserving the intended check-in.
5. Extract/localize strings and consolidate theme tokens; test dynamic color rather than mixing it with mostly hardcoded colors.

### Production-readiness tasks

1. Add signed AAB generation, secure CI variables, versioning, release notes, and rollback/checklist documentation.
2. Review Play policy eligibility for overlay, usage access, calendar, boot restart, notification, and `specialUse` foreground-service declarations.
3. Add a privacy policy/data-retention explanation, even though data is local-only.
4. Add opt-in diagnostics or privacy-preserving crash reporting if appropriate.
5. Test target SDK 36 behavior on real devices from multiple vendors, including boot, battery restrictions, force-stop, permission revocation, and long-running service behavior.

### Optional future enhancements

1. Structured goals with stable IDs instead of normalized text keys.
2. Automatic daily calendar refresh without mixing calendar items into persisted goal text.
3. Optional encryption or broader settings backup only if separately designed; the current manual format intentionally contains goals only.
4. Configurable overlay position, size, colors, quiet periods, and reminder interval.
5. Cloud sync/accounts only if explicitly designed later; none exists now, and adding it would require a new security, privacy, auth, offline, and migration architecture.
