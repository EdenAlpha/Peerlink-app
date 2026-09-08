# PeerLink port-routing and STUN fixes — 2026-08-31

> Historical component report. The integrated F10 transport, score lifecycle,
> and jitter work is documented in `F10_FINAL_REPORT.md`; its final evidence
> counts supersede the older counts below.

This is a corrected **source project**, not an installable APK. No root is
required for these changes or the host tests. Decryption is not part of this work.

## Main result

The original native backend redirected incoming game packets to whichever local
port it most recently saw, including ports used by unrelated STUN checks. The
fixed backend preserves the UDP destination port addressed by the peer.

The same 1,000 synthetic interference cases were executed against both source
versions: the original delivered all 1,000 to the wrong port; the corrected source
delivered none to the wrong port. This is a reproducible code regression test,
**not a claim of 1,000 successful live matches**.

## Changes made

- Native routing now tracks the local address separately for each IP family,
  local port and remote port. Discovery sockets cannot replace an active match's
  port. Concurrent sockets and new local-address observations stay separate.
- A successful STUN callback records only that socket's local binding, allowing
  an incoming peer packet before the first outgoing game packet. Diagnostic
  last-seen ports are no longer used to route packets.
- Endpoint records are scoped to one backend instance, capped at 1,024 and expire
  after 120 seconds without an outbound observation. Incoming traffic does not
  refresh an obsolete entry. Unknown endpoints use the configured VPN address,
  never another socket's learned address. A new STUN address invalidates conflicting
  exact-flow records for the same local socket only.
- STUN change-IP/change-port bits and response destination-port handling are
  corrected, including combined requests. `OTHER-ADDRESS` remains the alternative
  of the original request destination. `RESPONSE-ORIGIN` matches the reply source.
  These rules follow [RFC 5780, section 6.1](https://www.rfc-editor.org/rfc/rfc5780.html#section-6.1).
- Immediate STUN retries get responses; a transaction ID on one socket no longer
  suppresses the same ID on another. Message/attribute bounds and optional request
  fingerprints are checked. Unsupported required attributes, including requests
  needing authentication, go to the normal server path instead of receiving an
  invented success.
- Two additional server profiles were verified from the supplied captures.
  Unknown servers no longer inherit an unrelated hard-coded cluster.
- Non-Binding STUN methods are not swallowed. A declined fabrication goes to the
  Internet bridge. Relay-addressed traffic is still not diverted into the LAN tunnel.
- Empty UDP datagrams no longer terminate the receive loop. Invalid IP/UDP
  lengths are rejected; fragment payloads cannot be mistaken for socket ports.
- The Internet bridge starts before the potentially 60-second peer probe, so
  DNS/login/passthrough traffic has a consumer during startup. The UI still waits
  for the probe before declaring the session active.

## Capture-backed additions

| Capture / packet | Main server | Alternate server | F000 third server |
|---|---|---|---|
| `PCAPdroid_03_Jul_11_46_30.pcap` / 12202 | 35.76.243.83 | 54.150.169.24 | 54.65.202.22 |
| `PCAPdroid_03_Jul_11_46_34.pcap` / 7567 | 54.64.79.140 | 13.230.144.13 | 176.34.63.31 |

Their F000 values were respectively
`057300030000000300010d973641ca16` and
`057300030000000300010d97b0223f1f`. The existing two profiles are retained.
These observations are not a guarantee of future server assignments.

## Verification

| Check | Status |
|---|---|
| Native regression suite | 27 checks passed; zero failures |
| Original vs fixed port-interference test | 1,000 wrong destinations before; zero after |
| Random packet-input exercise | 50,000 inputs, under AddressSanitizer and UndefinedBehaviorSanitizer |
| Empty-datagram receive-loop test | Passed using real host datagram sockets |
| Native host compile with the project's no-exceptions/no-RTTI flags | Passed; the port regression also passed (not an Android link/build) |
| Kotlin STUN matrix | Added, **not run** here: Kotlin compiler unavailable |
| Android Gradle build/unit tests | **Blocked**: Gradle distribution download reports network unreachable |
| Android/game end-to-end run | **Not performed** |

The native suite includes production `peerlink_backend.cpp` directly. Host
Android logging/JNI adapters are in `tests/shim`. One adapter returns explicitly
mocked bytes to exercise the native STUN callback-success branch; this does not
execute or validate Kotlin. LeakSanitizer is disabled because of host restrictions;
AddressSanitizer and UndefinedBehaviorSanitizer are enabled. No whole-app memory-
safety claim is made.

The pending Kotlin suite tests actual `StunFabricator.kt` and `PacketParser.kt`,
including 128 combinations of known server, server port, change flags and response
destination, plus checksums, fingerprints, retries and malformed requests.

From this project directory:

```sh
bash tests/run_native_tests.sh
# With a Kotlin compiler and Java on PATH; no Android SDK or phone needed:
bash tests/run_stun_tests.sh
# Or with a configured JDK/Android SDK/NDK and dependency access:
bash ./gradlew testDebugUnitTest assembleDebug
```

`tests/port_regression_ab.cpp` can be compiled against the original backend by
defining `PEERLINK_BACKEND_SOURCE` to its absolute path. Without that definition it
uses the corrected source. Exit 1 on the original is the expected reproduced defect.

## Limits — do not call this universally bulletproof

- Kotlin and the complete Android APK must be compiled and checked before release.
  This package has not been installed or used for a live match.
- New/unknown server profiles and native IPv6 STUN servers use normal Internet
  passthrough. This may let the game choose a relay; it does **not** guarantee P2P.
  IPv4-mapped IPv6 server wrappers are handled separately and need Android validation.
- IP fragment reassembly and IPv6 extension-header tunneling are not added.
  Fragmented peer packets are rejected rather than misrouted. The Kotlin bridge
  parser also rejects fragments. Avoid claiming fragmentation support.
- NAT/network reachability, Wi-Fi changes and Android/OEM behavior are not solved
  by packet-unit tests. The older audit's broader JNI stop/rebind lifetime risks,
  permissive socket-preparation fallback, and uncorrelated one-byte peer probe
  remain outside this patch. No peer authentication/session-epoch protocol was added.
- The native STUN fabricator itself is not gated on probe completion; only the
  existing UI readiness and earlier bridge startup are covered by the service change.
- Simultaneous sockets that advertise an identical fabricated address/port and
  peer tuple cannot be disambiguated without a new explicit port-translation scheme.

## Provenance

Based on the uploaded `Peerlink-main (1) (3).zip`, SHA-256:

`37e86fce71c935b81cc7176da4f5dbc1348217378955da6916e5802a061cd4f9`

Only four production files changed: `peerlink_backend.cpp`, `StunFabricator.kt`,
`PacketParser.kt`, and `PeerLinkVpnService.kt`. The remaining additions are tests
and this handoff. The original uploaded ZIP was not modified.
