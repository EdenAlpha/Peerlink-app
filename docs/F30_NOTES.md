# PeerLink F30 — combined Prime and score reader update

This release retains all tested F29 Prime activation, cancellation, GPU restoration and memory-worker fixes. It merges the supplied score-reader improvement into that newer source. The supplied ZIP was based on F28; replacing the complete project with it would lose the F29 fixes.

## Score reading
- Retain the supplied tighter score-lane crop, paired/split digit support, glyph normalization and 1.8-second OCR wait.
- Preserve F29's single-flight OCR, bitmap ownership until completion, bounded screenshot pipeline and protection against late results from an earlier match.
- Parse score geometry in a separate JVM-tested reader. Reject clocks, decimals, arbitrary text separators, invalid ranges, mismatched rows, and conflicting equally plausible scores.
- Recognizing digits no longer requires recognizing tiny Full Time text. Finality is checked separately using the broader frame. Known half-time/pause screens are rejected.
- Two matching readings with final-screen evidence can submit automatically. If only the digits are clear, a message asks the player to tap FT on the visible final result. Manual FT still requires two agreeing fresh readings and the game in front. A PPS drop alone does not prove the game has ended.

## Prime
- The earlier pairing compile error is corrected; that F29 build passed before this merge.
- Keep the checked/timed ADB startup, cached-port retries, actionable activation errors, graphics restoration and memory shutdown fixes documented in F29_NOTES.md.
- Block activation while deactivation is restoring settings, and always release the restore guard after failure.

## Validation and limits
The workflow requires host regressions, Kotlin unit tests (including score parsing and Prime failure cases), and an Android APK build. No emulator boot is used. Exact results are recorded in the linked workflow and reports.

Parser tests use controlled OCR tokens; they do not prove recognition accuracy on actual eFootball screenshots. No game result screenshots were included in this new source ZIP. Both-phone activation and live result detection still need a physical-device check. Ambiguous OCR should request confirmation rather than settle a guessed result.

Install the same F30 APK on both phones. The APK is a debug build; the separate source ZIP contains the complete matching source.
