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
