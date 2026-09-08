# PeerLink F29 — Prime reliability fixes

Built from the uploaded F28 score automation source, retaining the earlier packet/UI fixes.

## Changes
- Check ADB handshake results; bound/cancel connection and shell reads, stop at a checked command-exit marker, retry local discovered endpoints, reuse the cached connect port, and never log the launch token.
- Preserve activation state across Activity recreation; retain actionable startup failures and release the activation guard on cancellation.
- Remove NSD port-binding probes and per-activation thread leaks. Ignore stopped-watcher callbacks and retry busy resolutions.
- Upgrade an authenticated older resident Prime daemon before activation. Score capture remains authenticated and local.
- Wait for memory/frame workers to stop before reversing settings, refuse to freeze from stale/unknown foreground state, and suspend reclamation during live matches.
- Replace invalid custom-mode reset commands with verified downscale disable and mode restoration.
- Stop graphics mutation probes during ordinary activation. Explicit verification requires the game closed. Serialize graphics operations; recognize numeric Android Game Mode availability and verify/restore Prime's CPU Game Mode setting.
- Carry command timeouts to PrimeServer, including longer ART operations; cover process exit as well as output reads. Require eFootball closed for ART optimization.
- Retain OCR input until ML Kit finishes, even after timeout. Limit OCR and screenshot concurrency; avoid cascading fallback screenshots after a slow modern capture.
- Require explicit final-result text in the same score region. Require two agreeing manual reads, prevent duplicate FT submissions, and discard results from an earlier session or cancelled burst.
- An uncertain foreground check or retained Wi-Fi IP cannot prove a forfeited match. Those cases no longer automatically assign a 0–3 loss to a player.

## Verification
The release workflow requires host regressions, Kotlin unit tests and an Android APK build. It does not boot an emulator. See the workflow run and test reports for the result.

Physical-device wireless-debugging activation, OEM graphics behavior and live eFootball OCR still require testing on both players' phones. This release does not establish 100% OCR accuracy or guarantee Android will retain wireless-debugging authorization after reboot/revocation. Keep explicit full-time text visible for score detection; ambiguous screens are rejected instead of guessed.

The APK is a debug build. Install the same version on both phones.

Android command semantics checked against [AOSP GameManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/app/GameManagerShellCommand.java).
