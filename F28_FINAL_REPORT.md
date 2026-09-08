# PeerLink F28 Match Result Automation

## Scope
F28 adds decode-free eFootball result detection without changing PeerLink's native packet-forwarding data path.

## Match flow
- STUN traffic prepares match automation.
- After more than 200 tunneled packets, both peers perform a synchronized Home/Away selection. Equal selections are rejected on both phones with stronger haptic/shake feedback.
- `GAMEPLAY_T0` is the first stable 24-27 PPS gameplay sample after the preparation gate.
- No automatic score capture can run before `GAMEPLAY_T0 + 5:00`.
- From 5:00 onward, gameplay PPS below 20 starts score capture immediately.
- Capture attempts run every 250 ms (4/s) for at most 20 seconds.
- A return above 24 PPS cancels the burst immediately on the next traffic-stat observation.
- Automatic results require two matching OCR reads before committing.
- A confirmed result is sealed and cannot later be replaced by a disconnect forfeit.

## Score capture efficiency
- Prime captures the display and crops it before transferring pixels to the app.
- Only the central top 32% and central bottom 30% score-bearing bands are retained; the middle pitch area and side regions are discarded.
- The composite is capped at 960 px width and JPEG quality 88. This keeps small final-result anchor text readable on high-resolution phones while remaining far below a full-frame OCR workload.
- OCR is on-device ML Kit and is invoked only during a manual FT read or the short post-5-minute low-PPS burst.
- Capture and OCR are decoupled with a capacity-1 channel: at most one frame is being analyzed and one newer frame can wait. Stale frames are recycled instead of queued.
- Score acceptance requires final-result screen evidence plus score geometry; statistics values outside a plausible football-score range are rejected.

## Result/disconnect safeguards
- Home is the left score and Away is the right score, mapped using the synchronized peer role lock.
- Manual FT performs one immediate capture. If eFootball is verifiably not foreground during a live PeerLink match, the local player forfeits 0-3 under the existing settlement rules.
- Sustained non-gameplay flow after 5:00 uses a 135-second disconnect confirmation window. Recovering normal flow cancels it.
- Attributable client/hotspot-owner disconnects can produce a 0-3 forfeit; ambiguous shared-network failures become No Contest.

## Validation completed
- F11 Prime regression: PASS (55 checks).
- F24 trace/overlay regression: PASS.
- F25 integration regression: PASS (10 checks).
- F26 integration regression: PASS (13 checks).
- F28 match automation regression: PASS (44 checks).
- Kotlin structural checks: PASS (122 checks).
- PrimeServer/PrimeClient isolated Kotlin compilation: PASS.
- PrimeScreenScoreDetector isolated Kotlin compilation: PASS.

A full Android Gradle compilation could not be executed in this environment because the Gradle 8.11.1 wrapper distribution is not locally cached and outbound network resolution is unavailable. No claim of a completed APK build is made in this source package.
