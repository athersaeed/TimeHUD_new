# X video feed inspection — 2026-10-13

No TimeHUD application code was changed.

## Emulator crash
Android 16/API 36 Google Play x86_64 emulator, X 12.34.0-prod.01 installed through Google Play. X crashed with `com.facebook.soloader.t: couldn't find DSO to load: libyoga.so`. The installed ARM64 split contains `lib/arm64-v8a/libyoga.so`, while the crash lists direct APK lookup paths under `lib/x86_64`. This supports an ABI/library-loader compatibility diagnosis; no emulator fix was verified.

## Samsung inspection
Physical Samsung SM-S721W over USB ADB, X 12.33.1-prod.01.

- Regular timeline: `MainLanding` and `scaffold_home_tabbed`; no `VideoTab`.
- Opened a timeline video in full screen: root UI resource ID `VideoTab`.
- Swiped up once to a different video: `VideoTab` remained present.
- Pressed Back to return to the timeline: `VideoTab` disappeared; timeline markers returned.
- Player also exposes Back, More options, Reply, Repost, Like, Bookmark, Share, play, Mute, and Dock video player content descriptions. These individual labels are not sufficient to identify the feed.

## Proposed behavior to discuss
An optional Block X video feed control could target the `VideoTab` screen inside `com.twitter.android`. Opening a video from the timeline already entered the swipeable feed in the observed flow, so blocking this screen would block full-screen entry in that flow while permitting the regular timeline.

This is a candidate detection signal based on UI Automator snapshots, not a verified TimeHUD AccessibilityService implementation. Other entry points, photos, comments, live video, landscape, hidden controls, language settings, and further X versions remain untested. UI Automator dumps timed out during playback; pausing enabled capture. That capture-tool behavior does not establish whether TimeHUD's accessibility service would have the same limitation.

## Captures
- timeline.xml / timeline.png: baseline timeline. Capture times differ slightly because the timeline was moving.
- feed.xml / feed.png: first full-screen video; screenshot was taken while playing and XML after pause.
- next-video.xml / next-video.png: second full-screen video after a swipe; screenshot during transition and XML after pause.
- after-back.xml: timeline after Back.
