# PeerLink F11 — Prime UX, discovery, lifecycle, and transport hardening

Date: 2026-09-01

## Outcome

F11 is a source-level release candidate built on the corrected F10 networking
tree. It keeps the audited STUN fabricator and PacketParser unchanged, preserves
the repaired PeerCoin lifecycle and native gameplay queues, and fixes the Prime
Mode/Shizuku setup experience that could hide required controls or report a
connection before it was proven.

The final host verification result is **265/265 checks passed**. This does not
mean “1,000 out of 1,000 live matches”: an Android build and a two-phone field run
are still mandatory before release.

## 1. Prime/Shizuku setup is now coherent

- The setup screen uses durable evidence (`pairedTrusted` or `bootstrapped`) and
  no longer treats temporary `DISCOVERING`/`PAIRING` states as “paired.”
- **Open Developer Options**, pairing-code instructions, and code entry remain
  visible throughout discovery and failed attempts.
- Users may enter either `CODE` or `PORT:CODE`; every attempt has a bounded
  timeout, and a wrong code remains retryable instead of killing the service.
- Tapping the pairing notification opens the Prime setup screen directly.
- Prime setup distinguishes **Not paired**, **Ready**, **Starting**, **Active**,
  **Restore pending**, and **Error** instead of showing one ambiguous state.
- Activation is serialized. Rapid taps cannot start overlapping command runs.
- Turning Prime Master off deactivates an active or in-flight Prime session.
- Forget Pairing refuses unsafe states, shuts down and verifies the old local
  server, then rotates the authentication token.

## 2. Prime is non-root, authenticated, bounded, and truthful

- The loopback Prime command server requires a per-install 32-byte token and a
  versioned `prime_ok_v2` health proof. Old unauthenticated servers are rejected.
- Input and command output are bounded to prevent memory exhaustion.
- The app removed fake or unsafe “optimisations”: no `am kill-all`, cache purge,
  captive-portal disable, global Wi-Fi band override, hidden Wi-Fi suspend flag,
  or deprecated Wi-Fi sleep-policy mutation is used for a new activation.
- Legacy snapshots remain restorable so an interrupted older build does not
  strand the user’s previous settings.
- “Game Priority” now says what stock Android can actually do: reduce Doze and
  standby restrictions. A non-root app cannot guarantee another app is pinned
  permanently in RAM.
- FIFO real-time scheduling is attempted only when the executing identity is
  genuinely root. On normal phones, the native packet loops use safe Android
  thread priority instead.

## 3. No more automatic settings obstructions or radio surprises

- App startup never opens Wi-Fi Settings or the battery-optimisation screen.
- Battery protection is a clearly labelled, explicit user action in Settings.
- Auto Wi-Fi uses the already-running local Prime server on a background scope,
  checks the command result, and never blocks the Compose/UI thread.
- `Activity.onDestroy()` and foreground-service teardown no longer turn Wi-Fi
  off. Screen rotation, recreation, or swiping a non-session activity therefore
  cannot unexpectedly cut the local link.
- The Auto description explicitly says PeerLink may turn Wi-Fi on but will not
  turn it off automatically.

Android’s supported match-scoped low-latency Wi-Fi lock remains owned by the VPN
and is released when the session ends. Deprecated global Wi-Fi sleep controls
are not presented as latency optimisations.

## 4. Discovery and connection state are fail-visible

- Discovery identities use endpoint plus stable device ID, so two phones with
  the same display name are not collapsed or rejected.
- NSD/broadcast callbacks are generation-guarded and tied to the current network
  path. Stale callbacks cannot repopulate a newer discovery session.
- Peer observations from pairing and beacon sources are merged by IP and expire
  by freshness rather than one source deleting the other source’s live peer.
- The listener remains active continuously; individual pair attempts time out
  without silently ending discovery.
- UI states now say **permission needed**, **waiting for Wi-Fi**, **scanning**,
  **peers found**, **paused**, or **error**, with a real Retry action.
- A selected peer disappearing produces an error instead of an endless spinner.
- The session progresses through Pairing → Starting tunnel → Verifying path →
  Active. “Connected” appears only after bidirectional packet proof.
- VPN permission denial, VPN-interface creation failure, service-start failure,
  and path-verification timeout all leave the pending state and explain what
  happened. A pending connection is cancellable.

## 5. Jitter and packet delivery

The user’s symptom—an approximately 90–150 ms receive gap followed by packets a
few milliseconds apart—was not caused by the old Kotlin `JitterBuffer` in the
current native path: bridge mode returned before that object was created. F11
therefore removes the misleading UI switch and hard-disables the legacy buffer.
It does not add artificial gameplay delay.

The real F10 transport repairs remain intact:

- gameplay and control TUN queues are separate, and gameplay drains first;
- peer receive drains a bounded batch per readiness wake;
- score telemetry uses bounded SPSC rings instead of a packet-path mutex;
- diagnostic logging is deferred off packet threads;
- socket rebind and send are serialized without one broad gameplay FIFO;
- empty UDP datagrams cannot stop the backend;
- continuous Wi-Fi scanning is absent;
- UDP truth traces distinguish remote send spacing, kernel receive time,
  userspace wake/drain, queueing, actual TUN write, and local TX scheduling.

This removes known application-side burst mechanisms and provides evidence to
separate “the packet reached the kernel late” from “PeerLink woke or injected it
late.” It cannot honestly guarantee that every OEM scheduler, radio firmware, or
Bluetooth/Wi-Fi Direct implementation will never stall.

## 6. Corrected STUN, parser, and PeerCoin code retained

| File | SHA-256 |
|---|---|
| `StunFabricator.kt` | `2aa47a9ec0a0556d572fcfb424b60fed902a779ab7565dbf42f0364da3e6a260` |
| `PacketParser.kt` | `0064ce3beb1c68d3b03f78a6d1e0ab773061dc31e3f2e8738a031a5da5cc5f8e` |

The seven recognized STUN profiles remain profiles, not a seven-packet limit.
`CHANGE-PORT`, `CHANGE-IP`, combined change, `RESPONSE-PORT`,
`RESPONSE-ORIGIN`, and `OTHER-ADDRESS` retain their corrected semantics.
Malformed, unsupported, unknown, and unmeasured profiles fail open to the real
network rather than receiving a fabricated success.

PeerCoin settlement still fails closed: pre-gate goal events are replayed from a
bounded queue; events require peer echo; full-time proof plus a terminal boundary
is required; disconnect/silence alone cannot settle; final telemetry has one
consumer; record IDs are unique; legacy unconfirmed records pay zero; and ledger
writes use flush, `fsync`, backup recovery, and atomic replacement.

## 7. Verification

| Suite | Result |
|---|---:|
| F11 Prime/discovery/radio/UI regression gates | 55/55 |
| Kotlin/JNI/manifest structure across 38 Kotlin files | 101/101 |
| Eight-PCAP protocol, score, PeerCoin, STUN/parser lifecycle suite | 59/59 |
| Native backend under ASan/UBSan, including 50,000 malformed packets | 46/46 |
| UDP gap/burst trace analyzer | 4/4 |
| **Total** | **265/265** |

The known direct-P2P captures still decode as 0–1 with echoed goal events and
full-time control proof. Relay, non-matching protocol, lobby-only, and idle
captures remain non-settleable. The native suite retains the 1,000 mixed-port
transition test with zero wrong redirects in the corrected code.

## 8. Honest release blockers

- No Android APK was built here. Gradle 8.11.1 was not cached, and this workspace
  could not reach the Gradle distribution endpoint.
- No two-phone non-root live run has verified Prime setup, UI navigation, direct
  P2P, or remaining jitter on the target OEM devices.
- Both real goal captures are opponent-scored. Reverse attribution is symmetric
  and synthetically tested, but a real user-scored capture remains desirable.
- A future eFootball protocol or STUN-profile change can require a new capture
  and audit; unknown profiles intentionally pass through rather than being
  fabricated incorrectly.

## Release gate

Build with Android SDK/NDK and cached dependencies, then use two ordinary
**non-root** phones:

1. Complete Prime pairing once, including one wrong-code retry and app resume.
2. Confirm Developer Options/code entry stay visible and the notification opens
   Prime setup.
3. Verify discovery after Wi-Fi off/on, app background/resume, duplicate names,
   and a peer disappearing during pairing.
4. Play a match in which both sides score if possible; verify the direct P2P
   ports, score record, full-time settlement, disconnect, and rematch.
5. If any stutter occurs, export the text log and UDP CSV from both phones and
   compare remote-send, kernel-RX, userspace-RX, enqueue, and actual TUN-write
   timestamps before blaming the radio or the app.

Release only after that APK build and field matrix pass.
