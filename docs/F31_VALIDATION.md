# PeerLink F31 validation record

## Result-reader regression

- ScoreLaneReader smoke test: PASS.
- Compact OCR token `01`: PASS -> Home 0, Away 1.
- Compact OCR token `22`: PASS -> Home 2, Away 2.
- Narrow `S` OCR glyph in a narrow box: PASS -> 1.
- Clock-like and invalid score strings remain rejected.

## Match automation regression

`tests/f31_match_automation_regression.py`: **53/53 PASS**.

Covered: 200-packet H/A prompt, complementary Home/Away locking, 24–27 PPS gameplay start, five-minute gate, `<20 PPS` trigger, strict `>24 PPS` cancellation, 4 FPS/20 second capture bounds, 135-second disconnect handling, bounded capture/OCR pipeline, automatic two-read confirmation, manual one-shot FT, invalid manual FT forfeiture, Prime SurfaceControl capture path, and score visual preprocessing.

## Existing host regressions

- Kotlin structural checks: **136 checks, 0 failures**.
- F25 runtime regression: **16/16 PASS**.
- F26 Wi-Fi/socket regression: **38/38 PASS**.
- F27 latency regression: **7/7 PASS**.
- GameplayPathPolicy regression: PASS.
- UdpProxySockets regression: PASS.
- Native regression: PASS.
- Trace analyzer and retained F11/F16/F20/F21/F22/F23/F24/F25/F26 checks: PASS.

The aggregate `tests/run_checks.py` orchestration was also started successfully, but the long-running aggregate process did not complete in the available execution window. The three remaining native/runtime suites were run individually and passed, including F27.

## Real supplied screenshots

The three supplied eFootball reference captures were used to tune the fixed score cells and finality strips:

- walking/post-whistle banner: **0–1**
- statistics Full-Time screen: **0–1**
- Full-Time result menu: **2–2**

A host-side pixel-isolation reproduction successfully isolated these exact score glyphs before OCR. This validates the crop/segmentation geometry against the supplied images; it is not a substitute for ML Kit running on the physical Android device.

## Android build limitation

A complete Gradle Android build could not be executed in this environment. Gradle 8.11.1 is not cached and the environment cannot download the Gradle distribution. The changed PrimeServer and score-preprocessor sources were nevertheless compiled against Android/ML Kit structural stubs, and the project-wide structural checks passed.
