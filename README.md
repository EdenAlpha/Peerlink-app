# PeerLink F27

Android source for PeerLink 5.0.3-f27 (version code 8), based on the published F26 build. This update removes blocking waits on packet diagnostics and refreshes the app screens and launcher icon.

The current source is in `project/` in the GitHub repository. Open that directory in Android Studio. The application ID remains `com.peerlink.app`. Older source archives and patches are historical and must not be reapplied.

Build with JDK 21, Android platform 36, CMake 3.22.1 and NDK 27.0.12077973:

```sh
bash gradlew :app:testDebugUnitTest :app:assembleDebug
```

Run the host regression suite:

```sh
python3 tests/run_checks.py
```

The repository's F27 workflow compiles the app, runs host/Kotlin tests and checks the Compose UI on an Android emulator before publishing a main-branch release. Screenshots and reports are included in the workflow artifact. The standalone workflow in this directory builds without publishing.

Read [F27 notes](docs/F27_NOTES.md) for the old-source comparison, reproduced diagnostic blocking, Wi-Fi API limits, design changes and physical test guidance. Local socket and emulator checks do not prove that input lag is eliminated on two real phones.

## Latest: F36 — Proximity Gluing + Unknown-Stays-Unknown

See F36_SCORE_READER.md. The external review of F35 proved the headline bug
half-fixed: a split "3" (thin joints eaten by compression) still misread
1-1 instead of 3-1 because the fragment-gluing step demanded >50% sideways
overlap, and 3 stat rows came back empty because thin "1" pieces were
silently dropped. F36 replaces the glue rule with proximity-based
reassembling (fixpoint, break-side independent), reroutes glyph-sized thin
pieces through the classifier instead of dropping them, and returns
"unknown" — never a menu guess — when shape-validated score boxes refuse to
read. **0 in-envelope wrong reads across 182 checks**, 149/182 clean, 14
stat rows recovered vs F35, parameter basin flat at 0 wrong reads for every
new constant, evidence in tests/evidence/. Version 5.0.11-f36.

## F35 — Validated Score Reader

See F35_SCORE_READER.md. F34's claimed "0 in-envelope wrong reads" was false
(an independent re-run found 5, one fully realistic: 4:3 tablet + night-shift
+ JPEG q60 turned a real 0-1 into a silent 0-0). F35 fixes the six root
causes structurally (Otsu-anchored luma ink, inverse-glyph guard,
cross-validated table reads, blue-free hue pooling, fused-banner sub-box
recovery, size-robust F/H gates) and the harness now writes its full evidence
log on every run. Version 5.0.10-f35.
