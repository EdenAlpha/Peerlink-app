# F10 verification evidence

Final run: 2026-09-01

| File | Meaning | Result |
|---|---|---:|
| `native_regression.txt` | Production native backend compiled and run with ASan + UBSan | 46/46 |
| `protocol_lifecycle.txt` | Eight-PCAP gate, two known scores, PMT2 corruption, PeerCoin and lifecycle invariants | 59/59 |
| `kotlin_structural.txt` | Kotlin token/package, JNI, manifest and integration consistency | 97/97 |
| `trace_analyzer.txt` | Synthetic gap attribution and burst recognition | 4/4 |
| `native_warnings.txt` | C++17 `-Wall -Wextra -Wpedantic` syntax output | empty (zero warnings) |
| `corrected_source_hashes.txt` | Installed STUN/parser SHA-256 values | exact expected hashes |
| `gradle_attempt.txt` | Android build prerequisite attempt | blocked: Gradle 8.11.1 download network unreachable |

These are host tests and PCAP replays. They do not replace an Android APK build
or a live two-phone, non-root match test.
