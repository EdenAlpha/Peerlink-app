# PeerLink F31 — high-rate result capture and score isolation

F31 is a targeted correction to the F30 result-capture path. Unrelated gameplay forwarding, tunnel policy, STUN handling, and existing Prime activation/restore logic are retained.

## What was corrected

- Prime score capture now prefers direct privileged `SurfaceControl.captureDisplay` through the long-lived Prime process. The old `/system/bin/screencap` path remains only as an OEM compatibility fallback.
- Prime still transfers only the two score-bearing display bands as a compact JPEG; it does not send full screenshots during automatic capture.
- The app no longer OCRs the broad composite. A fixed-layout visual preprocessor isolates only the two possible score rows and tiny `Full Time` text strips.
- One ML Kit pass now reads the isolated score and finality evidence. Team names, logos, pitch, statistics, and the eFootball separator are removed before OCR.
- OCR tokens such as `01`/`22` spanning the two score cells are supported. A narrow `S` glyph can be treated as OCR's `1` when its geometry matches the narrow digit.
- A single OCR miss no longer erases an existing automatic score candidate. Two matching score reads are still required before automatic submission, and final-time evidence remains mandatory for automatic settlement.
- PPS cancellation is strictly **above 24 PPS**; exactly 24 PPS remains inside the capture window.
- Manual FT now performs exactly **one** screen capture, as specified. If eFootball is definitely not the foreground app at the explicit FT tap, PeerLink records the local player as a 0–3 forfeit and warns them. An uncertain foreground query does not punish the player.

## Validation

- JVM score-reader tests include compact two-digit OCR tokens and narrow-glyph correction.
- Host F31 structural regression covers the five-minute gate, `<20 PPS` trigger, strict `>24 PPS` cancellation, 4 FPS/20 s limits, H/A handshake, manual FT, fake-FT forfeit, Prime direct capture, and isolated score preprocessing.
- The supplied real eFootball captures were used to tune the visual coordinates: walking-pitch banner, statistics Full-Time screen, and Full-Time result menu.
- A complete Android Gradle build is still not executable in this environment because the Gradle 8.11.1 distribution is not cached and external download is unavailable.
