# PeerLink F10 — integrated transport, PeerCoin, and jitter hardening

Date: 2026-09-01

## Outcome

F10 is built on the corrected PeerLink source tree. It preserves the audited
`StunFabricator.kt` and `PacketParser.kt` byte-for-byte, repairs the PeerCoin
lifecycle, and removes several shared transport mechanisms capable of producing
the reported **gap followed by a 2–10 ms packet burst**.

This is a source deliverable, not a claim of perfect live behavior. Host replay,
sanitizers, malformed-input tests, and source/JNI checks pass. The Android Gradle
distribution is not cached in this environment and its download is blocked, so
an APK build and a two-phone field run remain required before release.

## 1. STUN and game-port routing

The integrated files have these exact SHA-256 values:

| File | SHA-256 |
|---|---|
| `StunFabricator.kt` | `2aa47a9ec0a0556d572fcfb424b60fed902a779ab7565dbf42f0364da3e6a260` |
| `PacketParser.kt` | `0064ce3beb1c68d3b03f78a6d1e0ab773061dc31e3f2e8738a031a5da5cc5f8e` |

Important behavior retained:

- The seven labels are seven **recognized Binding-request profiles**, not a
  limit on the number of STUN packets a session may send.
- `CHANGE-PORT` selects the alternate source port in the server profile.
- `CHANGE-IP` selects the other server address; combined change uses both.
- `RESPONSE-PORT` changes the response destination port, not the server source.
- `RESPONSE-ORIGIN` describes the actual fabricated reply source.
- `OTHER-ADDRESS` remains the alternative to the original request destination.
- Retries are answered independently. Unknown servers, unsupported methods,
  malformed messages, required attributes the fabricator cannot honor, and
  native IPv6 profiles that were not measured are passed to the real Internet
  path rather than receiving a false success.

The native port router no longer uses a single "last game port." Its endpoint
key includes address family, local destination port, and remote source port.
STUN sockets therefore cannot overwrite an active gameplay socket. The 1,000-
case A/B regression still reproduces 1,000 wrong destinations in the old source
and zero in the corrected source; this is a synthetic regression result, not
1,000 live matches.

## 2. PeerCoin lifecycle

F10 does not infer a goal from packet size. It learns the measured per-direction
len-59 protocol table, decodes the family-2 header, and recognizes the measured
goal-event tail:

`90 90 0e 0e 8e 2e 8e 2e`

The P2P stream carries goal events rather than a continuously readable scoreboard.
Messages are clustered into one goal and must be echoed by the other peer before
the event is settlement-eligible.

Lifecycle repairs:

- Large event heads arriving before the protocol table converges are retained in
  a bounded queue and replayed after convergence. Overflow blocks settlement.
- One periodic telemetry consumer exists. Shutdown stops and joins it before the
  final drain, eliminating the old two-consumer race.
- PMT2 telemetry is versioned and strictly length-, counter-, ordinal-, direction-,
  and head-bounds checked. Rollback or corruption fails closed.
- A match can settle only after the measured bidirectional full-time len-26 burst
  and a terminal boundary (subsequent silence or an explicit rematch exchange).
  Silence, disconnect, service teardown, or a short/unidirectional flow cannot pay.
- A finalized reader is discarded. Session UUID plus segment ordinal makes each
  record ID unique; duplicate IDs never overwrite an existing record.
- Legacy F8/F9 records default to unconfirmed. Only confirmed integrity-version-3
  records affect balance and statistics.
- Ledger writes use a temporary file, flush, `fsync`, and atomic replacement.
  A missing, empty, oversized, or malformed primary causes recovery from the
  last-known-good backup instead of silently producing an empty wallet.
- Unverified matches remain visible for audit but always have zero settled reward.

## 3. Jitter and burst delivery

The symptom is compatible with packets reaching one queue normally, a consumer
not running for 80–150 ms, and then several queued datagrams being drained a few
milliseconds apart. The old code had multiple mechanisms capable of creating or
worsening that shape:

| Risk found | F10 change |
|---|---|
| Gameplay injection shared one FIFO/lock with DNS, STUN, and bridge traffic | Separate gameplay and control queues/locks; gameplay always drains first |
| Peer RX handled one datagram per wake | Drain up to 64 datagrams per readiness wake |
| Per-packet diagnostic mutexes/JNI/logcat activity | Atomic first-seen filter, compact summaries, and deferred logger thread |
| Score capture added a packet-path mutex | Two bounded SPSC telemetry rings; loss is counted and settlement fails closed |
| Three-second active Wi-Fi scanning | Continuous scans removed |
| Startup verified a socket and immediately disconnected/rebound it | Verified startup path is retained |
| Socket rebind could overlap `send()` | Rebind and all sends share one short serialization lock |
| Failed rebind could leave the original socket disconnected | Previous peer connection is restored on the failure path |
| Wi-Fi `onLost` stamped its cooldown before recovery, so recovery rejected itself and waited for the 10-second poll | Loss marks the path unavailable; one immediate cooldown-protected recovery runs when the exact network returns |
| All native loops used overly aggressive audio priority and code assumed shell could grant FIFO scheduling | Only four packet loops use safe Android `URGENT_DISPLAY`; root-only FIFO self-disables on stock phones |
| Keepalives ran 10–20 times per second even during gameplay | 0.5/0.75/1.0-second idle keepalives; gameplay sends suppress them |
| Empty UDP datagram was treated like stream EOF | It is consumed without stopping the backend |
| Receive and TUN queue delays could not be separated | Kernel RX, userspace receive, enqueue, and actual TUN-write timestamps are exported separately |

The final audit also fixed three defects in the diagnostic path itself:

1. Actual TUN-write events were recorded but discarded by CSV assembly.
2. Linux datagram transmit timestamp IDs start at zero, while the mirror began at
   one. The counter is now aligned with the
   [Linux kernel timestamping specification](https://docs.kernel.org/networking/timestamping.html#so-timestamping-also-so-timestamping-old-and-so-timestamping-new).
3. A very fast error-queue timestamp could arrive after `send()` but before its ID
   was registered. Registration now happens before send and is removed on failure.
   Reverse delivery of scheduler/software stages is also handled.

The Export Match Logs action now runs off the UI thread and writes both:

- `peerlink_match_<time>.txt`
- `peerlink_udp_trace_<time>.csv`

Analyze a trace with:

```sh
python3 tests/analyze_udp_trace.py peerlink_udp_trace_<time>.csv
```

The report separates remote sender spacing, receiver-kernel arrival spacing,
kernel-to-PeerLink wake/drain delay, PeerLink enqueue delay, actual TUN-write
delay, and local transmit scheduling. It also counts the exact gap-then-burst
shape. A live trace export temporarily omits trace events instead of blocking
gameplay; packet forwarding never waits for the export.

## 4. Verification completed

| Suite | Result |
|---|---:|
| Production native backend under AddressSanitizer + UndefinedBehaviorSanitizer | 46/46 |
| Real-PCAP protocol, lifecycle, malformed PMT2, reward matrix, and source invariants | 59/59 |
| Kotlin/JNI/manifest structural checks across 36 Kotlin files | 97/97 |
| UDP trace analyzer synthetic attribution checks | 4/4 |
| C++17 `-Wall -Wextra -Wpedantic` syntax pass | 0 warnings |
| Corrected STUN/parser hash checks | exact match |

The real-PCAP suite now covers all eight supplied captures at the fingerprint
gate. The two known direct-P2P 0–1 matches are detected with peer echoes and the
measured full-time marker. The March flow, both July relay flows, the January
relay flow, lobby-only, and idle/DNS captures remain non-settleable.

## 5. What remains honestly unproven

- No Android/Compose/NDK build completed here because Gradle 8.11.1 could not be
  downloaded. No APK is included.
- No live non-root two-phone run has yet measured whether the reported jitter is
  gone. F10 removes concrete software burst mechanisms and now provides timestamps
  to locate any remaining kernel/radio/device scheduling delay; it cannot honestly
  guarantee a radio or OEM scheduler.
- Both real goal captures are opponent-scored 0–1 matches. The reverse scorer
  direction is structurally symmetric and synthetic tests support it, but a real
  user-scored capture is still the required confirmation.
- Goal-signature messages for one goal span roughly 6–9 seconds in the supplied
  captures. Two genuine goals inside the same 10-second cluster may merge until a
  protocol event identifier is recovered from a suitable capture.
- The signature and server profiles are version-specific observations. Unknown
  future STUN profiles safely pass through, which protects connectivity but can
  allow eFootball to choose its real relay instead of PeerLink P2P.
- The PeerCoin ledger is crash-resistant and fail-closed, not a server-authoritative
  cryptographic currency ledger. A device owner who can modify private app data or
  the APK can still tamper with local values.

## Release gate

Build on a machine with the Android SDK/NDK and cached dependencies, then run one
ordinary **non-root** session on two phones:

1. Verify direct P2P starts and the correct game ports remain stable.
2. Play one match in which each side scores at least once if possible.
3. If a stutter occurs, immediately use **Export match logs** on both phones.
4. Run `tests/analyze_udp_trace.py` on both CSV files or upload them with the two
   text logs.
5. Release only after the APK build, score direction, settlement, disconnect,
   rematch, and trace attribution all pass on-device.
