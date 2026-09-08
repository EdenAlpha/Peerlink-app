# PeerLink F11 verification

Final host run: 2026-09-01

| Suite | Command | Result |
|---|---|---:|
| Prime, discovery, radio, and UX gates | `python3 tests/f11_prime_discovery.py` | 55/55 |
| Kotlin/JNI/manifest structure | `python3 tests/kotlin_structural.py` | 101/101 |
| Eight-PCAP protocol, PeerCoin, lifecycle, STUN/parser invariants | `python3 tests/f10_protocol_lifecycle.py` | 59/59 |
| Native backend with ASan/UBSan plus malformed-input fuzzing | `bash tests/run_native_tests.sh` | 46/46 |
| UDP gap/burst attribution | `python3 tests/trace_analyzer_test.py` | 4/4 |
| **Total** | | **265/265** |

The native compilation also retains the existing C++17 `-Wall -Wextra
-Wpedantic` zero-warning evidence. The production STUN/parser hashes remain:

- `StunFabricator.kt`: `2aa47a9ec0a0556d572fcfb424b60fed902a779ab7565dbf42f0364da3e6a260`
- `PacketParser.kt`: `0064ce3beb1c68d3b03f78a6d1e0ab773061dc31e3f2e8738a031a5da5cc5f8e`

These are host builds, sanitizer tests, source gates, and packet-capture replays.
They do not replace an Android Gradle/NDK build or a real two-phone non-root run.
The Gradle 8.11.1 wrapper distribution was not cached and its download endpoint
was unreachable from this workspace, so no APK is claimed or included.
