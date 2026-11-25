# TimeHUD reliability review — September 5, 2026

Source checkout: `/home/syedathersaeed/Downloads/Career/Hackathon/hack-to-the-future/TimeHUD_cloud`, based on `f341b6be` on `main`.

Reviewed HUD lifecycle/window operations, interactive screen-time aggregation and its consumers, goal editing/storage/backup restore, boot permission checks, and the accessibility enforcement integration. This was a focused reliability pass, not proof that every app flow is bug-free.

## Fixes

1. **UI-thread usage scans:** HUD polling now performs provider queries on `Dispatchers.IO`, serially, in the existing service-owned scope. Bubble taps and window attachment reuse the latest reading. Blocked-app check-ins load their timer asynchronously and cancel delivery when disposed. Slow provider calls no longer block those UI paths; failed queries retain the prior HUD reading and retry on the next poll.
2. **Lost screen time:** Duplicate interactive events previously replaced the start of an open interval. A new streaming calculator preserves it, supports intervals starting at zero, ignores out-of-period events, and handles duplicate screen-off transitions. The existing 3 AM boundary and empty-history fallback remain.
3. **Overlay window failures:** Attach/update exceptions now dispose owned content, remove windows, clear running/restart state and stop the HUD. Five-minute checks cannot attach a new HUD check-in while the accessibility blocker is visible.
4. **Deleted goals returning:** Save/Start now reconciles persisted removals against the editor's saved baseline. Unrelated draft additions and rewritten lines survive. Each goal group is reconciled independently; duplicate removals are count-aware and non-English text is compared without lossy normalization. Baselines survive recreation and reset after save/import.

## Changed files

All paths below are relative to the source checkout:

- `app/src/main/java/com/boringutils/timehud/OverlayService.kt`
- `app/src/main/java/com/boringutils/timehud/ActiveOverlayContentController.kt`
- `app/src/main/java/com/boringutils/timehud/ScreenTimeDisplay.kt` (new)
- `app/src/main/java/com/boringutils/timehud/ScreenTimeCalculator.kt` (new)
- `app/src/main/java/com/boringutils/timehud/blocking/TimeHudAccessibilityService.kt`
- `app/src/main/java/com/boringutils/timehud/GoalSettings.kt`
- `app/src/main/java/com/boringutils/timehud/MainActivity.kt`
- `app/src/test/java/com/boringutils/timehud/ScreenTimeCalculatorTest.kt` (8 new regressions)
- `app/src/test/java/com/boringutils/timehud/GoalEditingTest.kt` (7 new regressions)
- `AGENTS.md` and `PROJECT_CONTEXT.md` (updated reliability guidance)

No dependencies, permissions, SDK levels, signing configuration, storage keys or goal-completion identities changed. Unrelated product behavior was not intentionally changed. No release build was required for these runtime-only fixes.

## Verification

Baseline and final builds passed:

```bash
./gradlew --gradle-user-home .gradle-work testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
git diff --check
```

- 103 JVM tests, zero failures/errors; 15 regressions added.
- Lint passed with 41 warnings, including existing hardcoded text, accessibility/style, unused-resource and dependency-update warnings.
- Debug app APK and instrumentation test APK built successfully.
- Final diff check passed.
- `adb devices` found no attached device/emulator; `connectedDebugAndroidTest` and manual UI tests were not run.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. It has not been installed or sent to the phone during this review.
- Changes remain uncommitted locally. GitHub main remains at the checkpoint pushed before this review.

## Device checks still needed

1. Start HUD; verify the initial ellipsis becomes a time, drag/tap the bubble, and close the manual check-in immediately.
2. Cross a five-minute boundary; verify one check-in and the five-second close delay. Stop/start HUD and verify the documented existing restart behavior.
3. Open a restricted app; verify its timer loads and no new HUD check-in stacks over it at the next bucket.
4. Revoke overlay permission while HUD is active; verify failure stops cleanly, then restore permission and start again.
5. Leave unsaved goal additions in the editor, remove a different goal through the overlay, then Save/Start. Verify the deletion stays deleted and additions remain. Repeat after activity recreation and with both goal groups.

## Remaining limitations

The goal editor can show an old line until Save/Start; reconciliation does not live-refresh it. Screen-time history still depends on Android/OEM completeness and infers initial state. HUD restart still permits an immediate check-in after five accumulated minutes. Notification permission still lacks a runtime request flow. Existing goal completion normalization can conflate distinct goals; changing that safely requires a storage migration. Samsung window layering and supported social-app section recognition require live device testing. These were left as explicit follow-up areas rather than described as solved.
