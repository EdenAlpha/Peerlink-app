# PeerLink hotspot + cleanup pass

> Historical baseline notes. See `F10_FINAL_REPORT.md` for the current combined
> source tree, including the corrected STUN/parser files, PeerCoin lifecycle,
> transport latency fixes, and validation limits.

This tree is based on the uploaded `Peerlink-main` project and focuses on the three requested changes: hotspot reliability first, removal of the invalid relay fallback, and conservative dead-code/UI cleanup.

## 1. Hotspot-owner / Wi-Fi client reliability

- Added `LanPathResolver` to select the **peer-reachable LAN path** instead of the first private IPv4 on the phone.
- The selected path records the local IPv4, prefix length, interface name/index, and (when Android exposes one) the exact `ConnectivityManager.Network`.
- Hotspot owners no longer require a `TRANSPORT_WIFI` `Network` object. This is important because Android/OEM SoftAP interfaces are frequently absent from `ConnectivityManager` even though the kernel can route through them.
- Discovery now uses the actual subnet prefix instead of assuming `/24` for directed broadcasts.
- Discovery binding falls back safely to wildcard on OEMs that reject Java binding to the SoftAP address; the data plane is still pinned later.
- The LAN path is re-resolved after the peer is known and again immediately before VPN startup, so cellular `10.x` addresses cannot accidentally become the session identity when the peer is on SoftAP/Wi-Fi.
- The native peer UDP socket source-binds to that exact local LAN address and is pinned to the exact kernel interface with `IP_UNICAST_IF` whenever an interface index is available.
- Rebinds re-apply the same interface pin.
- Added a native bidirectional probe/ACK (`0xFC`/`0xFD`) before the session is reported as running. Discovery/pairing and a working tunnel are now separate states.
- The probe runs off the Android service main thread and allows up to 60 seconds, avoiding ANRs and reducing first-run failures while the second phone is granting VPN consent.
- The foreground notification says `PeerLink Starting` until the data path is verified.

## 2. Relay fallback removed

The old relay shortcut has been removed from the native and Kotlin runtime paths.

The PCAPdroid reference captures show eFootball relay gameplay is carried in independent DTLS sessions to Konami `turn.konami.com` infrastructure. Copying one player's DTLS ciphertext over Wi-Fi and injecting it into the other player's different DTLS association is not a valid replacement for the relay.

Removed relay-specific classification/state, DNS relay-pool logic, relay tunnel flags/counters, relay source-rewrite injection, duplicate relay forwarding, and suppression of legitimate relay return packets. If eFootball falls back to its real relay, PeerLink now leaves that traffic on the ordinary passthrough path instead of attempting to replace it.

The fabricated direct-P2P/STUN path was preserved rather than redesigned in this cleanup.

## 3. Dead code / UI cleanup

Removed unreachable or superseded subsystems, including:

- old Wi-Fi Direct configuration/UI
- QR pairing/capture helpers
- Bluetooth credential exchange / Bluetooth peer transport
- native Bluetooth parallel data-plane branches and statistics
- obsolete match overlay/OCR/forfeit/signaling stack
- old `ApexSettingsScreen`, discovery animation UI, and the legacy ~1,900-line `CommonComponents` UI monolith
- obsolete accessibility resource left behind by the old Bluetooth-MAC UI
- unused Google downloadable-font resources/dependency
- unused ConstraintLayout dependency
- obsolete hand-written Prime/Shizuku ADB client/key/protocol/message stack that the live bootstrap had already replaced with `libadb-android`
- deprecated `AdbSpake2` stub that was no longer called

The active UI remains `PeerLinkScreen` plus the still-reachable unlock/Prime setup surfaces in `MainActivity`.

## 4. Intentionally retained

`TunnelEngine` is retained because `PassthroughBridgeEngine` still instantiates its bridge mode to provide eFootball's ordinary Internet TCP/UDP passthrough while the native backend owns the peer tunnel. Deleting that file wholesale would risk breaking login, DNS, authentication and matchmaking.

Prime Mode / GodMode pairing and call-blocking services are also retained because they are still reachable from the current UI/manifest.

## Validation performed

- Native `peerlink_backend.cpp`: host C++17 syntax/type check passes. The only emitted warnings are the known desktop-JDK JNI `AttachCurrentThread` signature difference; there are no C++ errors.
- Kotlin: parser pass reports no Kotlin syntax errors and no unresolved references to any removed/renamed PeerLink symbols. A complete Kotlin/Android compile cannot be performed without the Android/Compose classpath.
- Android manifest and all remaining XML resources parse successfully.
- Every manifest component still has a corresponding Kotlin class declaration.
- Repository scans show no remaining runtime relay-fallback symbols or references to the deleted UI/Wi-Fi Direct/Bluetooth/match classes.
- Native/Kotlin stats layouts remain aligned at 11 fields.

## Build-environment limitation

A full Gradle Android build was attempted, but this environment does not have the required Gradle 8.11.1 distribution cached and has no network access to download `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip`. Therefore this package is source-validated but **not claimed to be APK-build-verified in this environment**.

## Size of cleanup

Uploaded source tree: approximately 30,355 Kotlin/Java/C/C++/header lines across 70 source files.

Cleaned tree: approximately 18,499 lines across 45 source files.

Reduction: approximately 11,856 source lines (39.1%).

# Strategic relay / direct-bypass blocking (gameplay regression fix)

## Problem

Match testing showed the current build playing badly: the tunneled counter
stayed in single digits while the passthrough capture filled with gameplay
packets, with occasional relay matches. Capture analysis found two failure
modes, both of which bypass the fabricated-IP tunnel:

1. **Konami relay fallback.** The only confirmed relay capture in the user's
   GitHub `Pcsp` pcaps (`PCAPdroid_06_Jan_14_29_23.pcap`) carries 44,140
   gameplay packets (42.9 pps) to `34.155.120.34:5735` â€” the only
   gameplay-rate public flow in that capture. `tests/evidence_f15_port_validation.json`
   records the same signature (`port_pair [5735, 46220]`, `relay_suspected: true`).
   No direct-P2P capture ever used remote port 5735, and 5735 sits outside
   Android's ephemeral range (32768â€“60999), so a peer socket cannot source it.
2. **Direct-to-real-address bypass.** In the m3 capture of a bad match, the
   game sent 17,114 gameplay packets direct to the peer's real socket
   (`10.7.6.86:62195`) while the tunnel was alive and delivering; only 134
   replies returned (0.8% â€” a black hole). The game learns the peer's real
   address from Konami's encrypted signaling (host candidates), which PeerLink
   cannot rewrite, so the only counter is to refuse that direct path.

## Fix (TunnelEngine)

Three block rules, active only while paired:

- **relay**: passthrough UDP with remote port 5735 to a public address is
  dropped in both directions.
- **bypass**: passthrough UDP aimed at `stableGameplayRemotePort` (learned
  from tunneled flows, now also learned from inbound tunnel traffic so the
  device that rarely sends tunneled packets arms too) is dropped in both
  directions, but only while a tunneled packet was seen within the last 30 s.
  If the tunnel dies for real, the rule disarms itself after 30 s and ordinary
  passthrough resumes.
- **turn-relay**: passthrough UDP to/from an IP learned for Konami's *constant*
  TURN/relay hostname (`turn.konami.com`) at gameplay speed â€” â‰¥10 packets
  within 3 s per address:port, either direction sharing one window. The name is
  fixed even when its IPs rotate, and the IPs are learned two independent ways:
  by resolving the name on a background thread while the engine runs (does not
  depend on the game re-querying DNS), and by sniffing the game's own DNS
  answers (same learner that already watches `pesam.stun.service.konami.net`,
  now extended to watch turn domains in both the IPv4 and IPv6 query paths).
  This is what covers TURN-relayed gameplay on arbitrary ports: relay on those
  addresses runs at 10â€“40 pps and is caught whatever port or framing it wears
  (including DTLS-wrapped relay), while ports 53/443 are always exempt as a
  belt-and-braces.

Safety properties:

- DTLS records (content type 0x14â€“0x17, version 0xfefd) are never blocked in
  either direction *outside* the turn-relay rule, so the ~2.1 s heartbeat to
  `turn.konami.com` cannot be caught even by a port collision. Inside the
  turn-relay rule the heartbeat survives on **rate** instead of framing: one
  packet per ~2.1 s can never reach 10 packets per 3 s, and the rate window is
  shared between directions only for the same address:port â€” a relayed
  gameplay stream is a different flow key than the heartbeat.
- TURN addresses learned via DNS go into their own set, never into
  `learnedStunServerIps`, so STUN interception can not swallow the heartbeat.
- Healthy sessions never classify anything as relay or bypass traffic, so the
  rules are no-ops there.
- Blocked packets are still recorded in the passthrough capture as evidence,
  annotated once via `PassthroughRecorder.note`, counted per rule, logged on
  the first block and every 1000th (`STRATEGIC-BLOCK`), and summarised every
  30 s (`STRATEGIC` stats line: RelayBlocked / BypassBlocked / TurnRelayBlocked /
  TurnIps / RemoteGamePort / TunnelAgeMs).

Not covered: relay traffic to addresses that neither use port 5735, nor belong
to `turn.konami.com`, nor match the peer's own game port (no capture of such a
signature exists in this repository; if one appears, the passthrough capture
will contain it for analysis). IPv6 passthrough has no block rules yet (all
observed failures were IPv4); IPv6 answers for the turn hostname are likewise
not tracked.

## Rework: bridge-mode activation + Option-A private-door block

The three rules above never fired in production. `handleTunAction` returns
early in bridge mode â€” the mode production runs (the native backend owns the
peer tunnel; `PassthroughBridgeEngine` provides the Internet passthrough) â€” so
`strategicBlockReason` was unreachable on the send path, the periodic
`STRATEGIC` stats line was suppressed by `if (bridgeMode) return`, and the
bypass rule's inputs (`stableGameplayRemotePort`, `lastTunnelActivityMs`) are
only written by the Kotlin tunnel paths, which are dead in bridge mode. Match
m3 proved it: 17,114 leaked packets, zero `STRATEGIC-BLOCK` lines.

Changes:

1. **Strategic check runs in the bridge TX path** (`handleTunAction`). On a
   hit the packet is recorded in the passthrough capture as evidence, counted,
   announced (`STRATEGIC-BLOCK`), and **never enters the send queue** â€” this is
   the exact pipe where the leak physically left the device (proven: the
   leaked replies landed on this engine's own UDP flow sockets).
2. **Option-A rule: gameplay-speed traffic to a PRIVATE destination** (RFC 1918
   + CGNAT + link-local via `PacketParser.isPrivateIpBytes`) while the tunnel
   is alive, in both directions, sharing one rate window per remote IP.
   Evidence: the m3 leak was `10.7.6.86` (17,114 pkt @ ~27 pps to `:62195` +
   1,571 to `:31118`, 134 replies) â€” a private address can never be a public
   game service, so at â‰¥24 pps (`GAMEPLAY_PPS_MIN`) the only thing that fits
   is direct peer gaming. Slow private traffic passes the rate gate; DNS
   (port 53) is never evaluated; DTLS is exempt before the rule (heartbeat
   untouchable by construction); the block fails **open** when the tunnel
   stops reporting activity for 30 s.
3. **Bridge tunnel-liveness**: native publishes cumulative tunneled counts via
   `AppState.tunneled` every second; `noteBridgeTunnelActivity()` snapshots
   that counter and refreshes `lastTunnelActivityMs` only while it moves, so
   the 30 s window works (and prints as `TunnelAgeMs`) in bridge mode.
4. **STRATEGIC stats line emitted in bridge mode** every 30 s
   (`RelayBlocked / BypassBlocked / TurnRelayBlocked / TurnIps / RemoteGamePort
   / TunnelAgeMs`) plus a `PASS` line with the tunneled total â€” the on-screen
   proof the rules are armed.
5. **Topology mislabel fix** (`MatchAutomationEngine.detectAndAdvertiseTopology`):
   the old rule `route.contains(localIp)` matched every Wi-Fi client (its own
   route prints `src <localIp>`), so both phones logged `HOTSPOT_OWNER`
   (m3/old21 on wlan0) and disconnect faults were misattributed. Hotspot
   ownership is now proven positively by an **up** `ap*`/`swlan*`/`softap`
   interface; a client can never match it.
6. **NET-DOORS logging**: at VPN start and on underlying-network changes the
   app prints every interface with its IPv4 address
   (`rmnet_data0=10.7.6.86 ap0=10.57.220.34 tun0=10.0.0.2`), so "which door
   owns this address" is a log line, not a guess.

Verification for the next test match: tunneled counter climbs; `STRATEGIC
... BypassBlocked=N` grows on screen; `ðŸ›¡ STRATEGIC-BLOCK bypass` lines name
the private flow; the passthrough capture still contains every blocked packet
as evidence; `topology=` matches the real roles.

## Whistle tap: popup-free final-whistle capture (record & verify stage)

Goal: let the app hear eFootball's own final whistle — never the microphone,
never the MediaProjection "may capture passwords" dialog — so full-time
detection can stop depending on a manual FT tap. This change ships the
capture substrate and the verification tooling; auto-trigger logic lands next.

Mechanism (`PrimeWhistleTap.kt`): Prime (app_process, shell uid) registers an
`AudioPolicy` mix with `ROUTE_FLAG_LOOP_BACK_RENDER` through the Audio Policy
API — the same underlying path as AudioPlaybackCapture, but authorised by
`MODIFY_AUDIO_ROUTING`, which the shell uid holds since Android 13, instead of
a consent dialog. Loop-back keeps the device's own playback alive, so the
player keeps hearing the match (RemoteSubmix would mute the speaker).
Precedents: scrcpy's default audio source and yume-chan's shell PoC. All
audiopolicy entry points are resolved by runtime reflection, matching the
existing SurfaceControl capture pattern (no hidden-API compile dependency).

New pieces:

1. **`PrimeWhistleTap`** — `probe(seconds)` (fixed-length, for Settings) and
   `openSession()` (open-ended, for the FT hold), each an owned `Session`
   with a reader thread, idempotent close, and a 60 s safety cap. Three
   capture variants for the on-device experiment: `usage` (USAGE_GAME +
   USAGE_MEDIA mix), `usage_priv` (allowPrivilegedPlaybackCapture), `uid`
   (game-uid-only mix — nothing another app plays can enter the tap).
2. **PrimeServer commands** `__audio_probe__` / `__audio_start__` /
   `__audio_stop__` with their own busy flag — a probe or hold can never
   block score captures; the probe refuses while a session is live and the
   session auto-closes if the release never arrives.
3. **PrimeClient** `audioProbe/audioStart/audioStop` (JSON + WAV binary over
   the authenticated loopback socket, same pattern as scorecap).
4. **`WhistleWav`** — pure-Kotlin canonical 44-byte WAV header/peak/silence
   helpers with byte-exact unit tests (`WhistleWavTest`); a malformed header
   would make a genuine whistle unplayable, which is the one failure mode the
   verification feature cannot have.
5. **Settings ? Whistle sounds** — "Record 10-second test" auto-tries the
   three variants, keeps the first result that carries real audio, saves it
   under `filesDir/whistle/tap_*.wav`, and lists every recording with
   play/stop and delete so the user can hear with their own ears what was
   captured (and what will ship in the export).
6. **FT button gestures** — tap (<400 ms) behaves exactly like before
   (score capture, same 220 ms guard, eFootball-foreground gate unchanged);
   hold (=400 ms) records the game's audio press-to-release and, if the
   result carries sound, saves `filesDir/whistle/ref_*.wav` with a distinct
   vibration. Tap/cancel/overlay-teardown/mode-change all stop and discard
   an accidentally started capture — no path can leave Prime recording with
   no UI to stop it.
7. **Export zip** — a `whistle/` folder (reference + probes) plus a manifest
   line, so an exported match carries the audio evidence next to
   `match_log.txt` and the packet captures.

On-device verification for this stage: Prime active ? Settings ? Whistle
sounds ? open eFootball with sound ? Record 10 s ? press play and confirm the
menu sound/whistle is audible. Then in a match, hold FT at the final whistle
and confirm `ref_*.wav` saves (vibration) and appears in Settings and in the
next export. Failure modes report typed errors (`sdk_below_13`,
`no_audio_routing_permission`, `register_failed`, `already_active`) in the
log and on screen; silence is reported as silence, never as success.

## Semi-automatic Home/Away suggestion (tap = lock, hold = swap)

The standing rule from every recorded match: whoever CREATES the room is
always HOME, never AWAY (all side locks across the evening session - host
made every room, locked HOME - and the morning session - brother made every
room, locked HOME). The creator is already waiting in the lobby, so its
first STUN binding request to Konami's matchmaking servers goes out BEFORE
the joiner's. That ordering was confirmed on the analysed exports (gaps of
2 min and 5 min between the two phones' first calls).

What ships now:

1. **SideSuggestion.kt** (pure, unit-tested) - decides from two timestamps:
   earlier caller = room creator = HOME for that phone. No timestamps, gaps
   under 45 s (clock skew / simultaneous start) or over 30 min (times from
   different rooms) = NO suggestion, plain manual H/A stays. The rule never
   auto-applies: it only proposes.
2. **Timestamp capture** - the engine's ~1 Hz stats poll already watches the
   STUN intercept counters (same signal that arms the H/A prompt). The first
   counter jump records this phone's wall-clock call time; a fresh jump
   after a 10 s quiet gap (new room) records again. Times reset with the
   match session.
3. **Exchange** - two new match-control-channel messages: `STUN|<epochMs>`
   (sent when recorded and again at the H/A prompt) and `SWAP` (idempotent;
   the reliable sender delivers three copies, so a flip is always exactly
   one flip). Control channel only - never the gameplay port.
4. **Suggestion card** - when both times qualify, the SIDE_CHOICES screen
   shows one card, e.g. "Play HOME? You made the room - tap = lock, hold =
   swap", coloured for the suggested side. Plain H/A buttons remain when
   there is no suggestion.
     - **Tap** (<400 ms) accepts the suggested side in one step. The lock
       still waits for the peer's own tap (`pendingSuggestionConfirm`
       completes only when the peer's complementary choice arrives), so a
       card is never locked without a human tap on BOTH phones.
     - **Hold** (>=400 ms) = the player disagrees: local roles reset, both
       phones receive RESET + SWAP, both cards flip, each side taps once
       again. Both phones holding independently still yields exactly one
       flip - sides stay complementary (pinned by test).
5. **Logs** - every computation prints the evidence
   (`Side suggestion HOME (gap=127s swapped=false)`) so an export can check
   the suggestion against the actual locked sides.

The suggestion is advisory: nothing locks, nothing chooses without a tap,
and the old manual H/A buttons are one step away whenever the evidence is
missing or ambiguous. Still owed: one labelled match (who made the room,
who ended up HOME) to confirm the on-device timing matches the exports.

## Field round 2026-09-26: whistle no_system_context root cause + control-rx dedup

First field test of the suggestion card and the whistle tap (two exports,
02:24-02:58, both phones on the new APK). Findings and fixes:

1. **Whistle: `no_system_context` on both phones (12-13 attempts, probe and
   FT hold alike).** Root cause: `ActivityThread.systemMain()` builds its
   `H` handler, which needs a Looper on the CALLING thread â€” but audio
   commands run on `prime_worker` threads (accept loop spawns one per
   connection) which are bare; only `PrimeServerMain`'s main thread has a
   looper. systemMain threw, the old `runCatching { ... }.getOrNull()`
   swallowed the exception, and the app could only report the useless
   `no_system_context`. Fixes in `PrimeWhistleTap.systemContext()`:
   prepare a thread Looper first; attempt the standard
   `VMRuntime.setHiddenApiExemptions` unlock (harmless if blocked); retry
   via `currentActivityThread` if systemMain partially installed the
   thread; and NEVER swallow â€” the real exception chain now travels as
   `detail` into the log and the Settings message. Settings also stopped
   reporting `perm=false` before the permission was ever checked (shows
   `n/a` now) and gives a real sentence for this failure instead of the
   misleading "only silence came through".
2. **Control channel: one RESET processed three times.** `sendReliable`
   sends each message at 0/60/140 ms; the peer handled all three copies
   (field logs: three "Peer requested H/A reset" + three
   "H/A selection requested" within 6 ms). `receiveLoop` now drops exact
   duplicates inside a 2 s window (retransmission copies), while genuine
   re-sends ~90 s apart still pass. Also silences the triple
   "Peer matchmaking call time received" noise.
3. **Suggestion card: no card shown â€” correctly.** The exchange worked
   end-to-end on both phones (times delivered in under a second, logged on
   both sides), but the two first-call times were only 12.3 s apart â€”
   below the 45 s threshold â€” so no guess was made and manual H/A was
   used (locked complementary, AWAY/HOME). Tonight's STUN keepalives run
   every ~5 s from online-stack start (~90 s before the H/A prompt), so
   "first call" measures who entered online first, not directly who
   created the room; with a 12 s gap the order cannot be trusted (clock
   skew is well under a second â€” the order was real, but "who was first"
   still needs labelling before the threshold moves). Pending question:
   who created tonight's room?
