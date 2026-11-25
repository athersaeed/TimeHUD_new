# TimeHUD security and privacy review

Date: 2026-10-16. Source checkout: `/home/syedathersaeed/Downloads/Career/Hackathon/hack-to-the-future/TimeHUD_cloud`.

## Result and scope

No critical/high-severity exploitable issue or remote exfiltration path was identified in the reviewed source. Five actionable findings were identified: three medium-priority privacy/availability/safety issues and two low-priority integrity/privacy issues. This is a source review with focused JVM probes, not a penetration-test certification or a claim that the app cannot be compromised.

Reviewed local `main` at `ad069f24` plus all existing working-tree changes, including untracked accessibility/UI instrumented tests. Those source changes were present before this audit. They were not reverted, committed, pushed, or modified. The Play-uploaded 1.6/code-8 AAB is older than this working tree; this report does not establish that every finding is present in that exact binary.

Threat model: a normal sandboxed Android installation; another installed app may have overlay permission or control its own label/accessibility tree; a user may import an untrusted document; goals, calendar titles, usage history, and accessibility content are sensitive. Rooted-device extraction and a malicious replacement OS are outside scope. TimeHUD is a user-controlled focus aid, not a tamper-proof parental-control or enterprise security product.

## Findings, in priority order

### F1 — Medium: accessibility inspection copies arbitrary visible text, including editable text

Evidence:

- `blocking/TimeHudAccessibilityService.kt:188-209` invokes `AppWindowInspector.inspect` for any configured app rule when usage restriction allows the app. Even a daily-limit-only rule can cause a tree scan.
- `blocking/TimeHudAccessibilityService.kt:574-596` reads every visited node's `text`, `contentDescription`, and `hintText`, normalizes them, and adds them to label sets. Editable-node labels are copied into `editableLabels`. No password/editable-content exclusion or navigation-node allow-list appears in this path.
- `res/xml/timehud_accessibility_service.xml` requests retrievable window content and unimportant views across packages.
- `res/values/strings.xml:98` describes navigation labels and editable-field *state*. The repository instructions explicitly say not to inspect message bodies, post text, usernames, or captions.

Impact: when the other app exposes them through accessibility, private messages, post/caption text, usernames, and typed drafts can be processed transiently alongside navigation signals, even though they are unnecessary for a basic daily limit. Do not interpret this as proof that Android exposes raw passwords: password/sensitive-node filtering may occur in the platform or the source app. No persistence, logging, or transmission of these label sets was found. This is a confirmed data-minimization gap, not a demonstrated data leak to a third party.

Recommended fix: skip tree-content inspection entirely for daily-limit-only rules and unsupported packages. For supported section rules, recognize narrowly selected navigation identifiers and fixed labels; do not read editable field values, password-node values, or arbitrary post/message text. Where composer presence matters, retain a boolean or narrowly recognized resource ID. Avoid collecting window titles if a package does not need them. Align the disclosure with the resulting behavior rather than expanding collection merely to match the text.

Acceptance: add instrumented synthetic trees with private message text, password fields, and editable drafts; assert those values never enter signals. Confirm current section-recognition behavior still works with real devices. Static review confirms the current collection; no device-level private-text extraction experiment was run.

### F2 — Medium: unbounded goal-backup input permits resource exhaustion and oversized persistent state

Evidence:

- `GoalBackup.kt:143-152` calls `reader.readText()` without a byte/character limit, then parses the entire string.
- `GoalBackup.kt:69-78` validates types but not goal lengths, line counts, or total input size.
- `MainActivity.kt:237-240,263-265,369-377` keeps imported/editor strings in saveable UI state.
- `GoalSettings.kt:52-55` persists validated-but-unbounded imported strings.

Reproduction: the attached JVM probe passes a valid backup containing a 2 MiB goal through the actual compiled `GoalBackupFormat`; it returns `Success`. It uses the same JSON dependency as the repository's JVM tests. It does not prove identical Android-parser behavior for all malformed JSON inputs.

Impact: a user-selected oversized file or hostile document-provider stream can consume excessive heap before schema validation. An accepted large goal can later inflate preferences, UI work, and saved-instance state, potentially producing OOM, hangs, or state-transfer failures. `catch (Exception)` does not catch `OutOfMemoryError`. User selection is required; there is no remotely callable import endpoint, and no arbitrary code execution was identified. No destructive OOM or Android Binder-crash test was attempted.

Recommended fix: enforce a small streamed byte limit before materializing/parsing input; enforce per-field and total character/line limits; reject oversized goals in both import and normal editing. Validate before replacement. Avoid repeatedly saving large duplicate goal strings in activity state. Keep document I/O off the main thread. Do not attempt to fix this by catching OOM.

Acceptance: valid boundary-sized backups succeed; one byte/character over the limit fails cleanly; an unknown-size provider cannot bypass the streamed cap; cancellation and malformed input preserve existing goals.

### F3 — Medium safety/availability: essential-app protection does not apply to individual app-limit rules

Evidence:

- `blocking/BrickMode.kt:443-447` permits essential packages for usage restrictions.
- `blocking/BrickMode.kt:452-462` then separately applies an `AppBlockRule` even when the restriction engine returned `Allow` because the app was essential.
- `ui/blocking/AppBlockingScreen.kt:155-179` excludes TimeHUD itself but does not exclude protected Settings/phone apps from rule selection.
- Rule saving does not reject those packages.

Reproduction: passing `com.android.settings`, a one-minute daily rule, and 60,000 ms of usage to the actual compiled `AppControlDecisionEngine` returns `Block(reason=DAILY_LIMIT)` even with the essential-app stage returning `Allow`.

Impact: a user can configure ordinary Settings, launcher, or dialer windows to be blocked, undermining the advertised/instructed escape and essential-app guarantees. This can obstruct permission revocation or ordinary phone access. It is not proof that Android's emergency-call screen is blocked, nor proof of an inescapable device lockout: TimeHUD itself remains excluded by the UI and OS recovery options exist. This is a product safety issue rather than unauthorized remote privilege escalation.

Recommended fix: define a minimal, trusted global never-block set for TimeHUD, system Settings, the active launcher, and critical call/permission flows; enforce it before both restriction and app-limit decisions, as well as in UI and rule persistence. Distinguish truly safety-critical packages from convenience exemptions such as Outlook or Wallet. Review/migrate existing rules for the critical packages.

Acceptance: expired app limits cannot block trusted Settings, active Home, or required call/permission flows, including when a stale rule already exists. Confirm on Android/Samsung devices without making real emergency calls.

### F4 — Low: an app-controlled display label grants an essential-app exemption

Evidence: `blocking/BrickMode.kt:487-507` allows a package when its display label matches names such as `settings`, `phone`, `messages`, or `outlook`. That label is controlled by the app publisher.

Reproduction: the compiled policy returns `true` for package `com.example.untrusted` with label `Settings`, and `false` for the same package with an ordinary label.

Impact: an installed app can evade Restricted/Brick Mode without being selected by claiming one of those names. This does not grant Android privileges or access to TimeHUD's private storage. Severity is low because focus restrictions are not an adversarial security boundary and the app must already be installed.

Recommended fix: use trusted package/component identity, resolved default roles, and where necessary system/signature checks. Treat labels as presentation only. Keep an explicit user override for optional exemptions.

Acceptance: a synthetic app with label `Settings` and an unrelated package receives no automatic exemption; real Settings/Home/phone remain available.

### F5 — Low, manufacturer-dependent: disabled backup is not an explicit device-transfer exclusion

Evidence:

- Manifest sets `allowBackup=false`.
- `res/xml/data_extraction_rules.xml:6-19` contains an empty cloud section and a commented-out device-transfer section; neither explicitly excludes app data.
- `res/xml/backup_rules.xml` also has no explicit exclusions.

Android documents that on some Android-12+ manufacturers, `allowBackup=false` disables cloud backup but does not prevent device-to-device transfer. Goals, rules, schedules, and focused usage are in private preferences and may therefore migrate through an authorized OS transfer, contrary to an absolute claim that all OS backup/transfer is disabled. This is not a way for an ordinary third-party app to read preferences and not evidence that Google Drive cloud backup is currently enabled.

Recommended fix: explicitly decide whether transfer is allowed. If no transfer is intended, exclude relevant credential-protected and device-protected data domains from both cloud and device-transfer rules, while retaining compatible legacy exclusions. If transfer is desired, document it and ensure restored active restrictions cannot unexpectedly enforce on the replacement device.

Acceptance: inspect effective extraction rules and test a supported manufacturer transfer using synthetic goals only.

## Additional hardening and validation gaps

These are not confirmed exploitable vulnerabilities:

1. **Overlay-based consent deception:** no app-specific obscured-touch filtering or `setHideOverlayWindows` use was found on consent/settings/restore flows. A malicious installed overlay app is a prerequisite. Android 12+ blocks some full-occlusion cases, but partial occlusion is not protected by default. Evaluate protection on sensitive confirmation windows, including Compose dialogs, without breaking TimeHUD's own HUD or legitimate accessibility tools. Do not claim a working attack without device testing.
2. **Screen capture and lock-state privacy:** goals and calendar titles are deliberately displayed by overlay views. No `FLAG_SECURE` or explicit HUD lock-state policy was found in those paths. Consider an optional privacy mode, secure flags for sensitive windows, and clearing sensitive overlays at lock. Whether any content is actually visible over the keyguard depends on Android/OEM window policy and was not tested. Screenshot availability alone is not a security vulnerability.
3. **Plaintext, user-directed goal export:** export intentionally writes JSON without encryption to the chosen Storage Access Framework destination. A provider may be cloud-backed, even though TimeHUD has no network permission. Explain the destination/privacy consequences in the UI if sensitive goals are expected; encryption should be a product decision. This is user-authorized sharing, not a discovered covert upload.
4. **Dependency assurance:** versions are pinned and the Gradle distribution has a checksum, but no full dependency advisory/SBOM assessment or historical credential scan was performed. The absence of a finding is not a guarantee that all transitive components are vulnerability-free.

## Controls that look sound in the reviewed source

- No INTERNET permission in the application manifest or the existing merged release manifest; no network client, remote account/token handling, WebView, or dynamic-code execution path found in app source.
- Preferences use `MODE_PRIVATE`; ordinary apps do not gain access just because preferences are not separately application-encrypted.
- Overlay service is not exported. Accessibility service is not exported and requires `BIND_ACCESSIBILITY_SERVICE`.
- Exported launcher activity only consumes a validated destination enum; no privileged action is performed from that extra.
- Exported boot receiver checks the protected boot/package-replaced actions and saved startup state plus current overlay/usage access. Its exported flag alone is not a demonstrated vulnerability.
- Main-activity notification PendingIntent is explicit and immutable.
- The merged release manifest's AndroidX initialization provider is not exported; its profile-installer receiver is protected by `android.permission.DUMP`.
- Goal import uses user-selected URIs, closes streams, validates format/schema/type, and asks before replacing saved goals. No path-based filesystem extraction was found.
- Calendar is read-only and queried with fixed projection/selection; no string interpolation into a user-controlled SQL selection.
- Accessibility label sets are transient; no logging or persistent writes of message/post text were found. Logs reviewed contain operational messages rather than goals/calendar bodies.
- Targeted credential-pattern scan of app source and build/scripts returned no hits. It was intentionally configured not to print potential secret values.

## Verification and limitations

Commands run:

- `./gradlew --gradle-user-home .gradle-work testDebugUnitTest lintDebug assembleDebugAndroidTest` — success; most tasks were up to date.
- `./gradlew --gradle-user-home .gradle-work --init-script /tmp/timehud-security-audit/rerun-tests.gradle testDebugUnitTest` — success; test task forced to execute, with 136 tests, zero failures/errors.
- Lint: zero errors, 43 warnings, one hint. This is not a security certification.
- Java probe against the actual current compiled Kotlin classes — all four observations reproduced. See `AuditProbe.java` and `probe-results.txt` beside this report. No app was installed and no malicious package was created.
- `adb devices -l` — no connected device/emulator. Instrumented tests were compiled but not executed; no phone-specific tapjacking, keyguard, permission-revocation, or device-transfer experiment was possible.
- Inspected source/manifest/build configuration, current changes, private-data flows, and an existing generated release manifest. Did not rebuild/scan the exact Play release or alter Play Console.

No app-source changes, commits, pushes, or uploads were made. Audit artifacts are in the separate Documents output workspace. Existing working-tree edits were preserved.

## Suggested remediation sequence

1. Minimize accessibility content inspection (F1).
2. Cap backup/goal sizes (F2).
3. Apply a minimal global essential-app safety exemption (F3).
4. Remove display-label trust (F4).
5. Make OS backup/transfer policy explicit (F5).
6. Validate sensitive consent flows, locking/unlocking, permission revocation, and overlay removal on a physical Samsung and a second Android version before a production update.

## Primary Android references

- AccessibilityNodeInfo API: https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
- Accessibility API disclosure requirements: https://support.google.com/googleplay/android-developer/answer/10964491
- Android backup behavior and allowBackup caveat: https://developer.android.com/identity/data/autobackup
- Android 12 transfer rules: https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore
- Tapjacking and partial-occlusion protections: https://developer.android.com/privacy-and-security/risks/tapjacking
