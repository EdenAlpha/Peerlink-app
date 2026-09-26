# Honest-mode kill-test (branch test/no-stun-fabrication)

## Flag
`StunFabricator.fabricationEnabled` (default `true` = production behavior).
Set `false` for the §7.3 one-match kill-test only. Do NOT merge `false` to main.

## What false changes (3 files)
1. `StunFabricator.kt` — both `fabricateStunResponse` / `fabricateIpv6StunResponse`
   return null immediately (real STUN to `35.76.243.83:3478` passes through;
   game learns its TRUE reflexive + NAT type).
2. `TunnelEngine.kt` `handleInterceptAction` — null STUN response now
   `handlePassthrough` instead of drop. NOTE: this also changes default-true
   behavior for UNKNOWN-profile STUN (was silent drop, now passthrough, same
   as the IPv6 path). Intended per `StunFabricator` KDoc ("return null so the
   caller can preserve normal Internet passthrough") — flag in merge review.
3. `PacketParser.kt` RULE 1b — when fabrication off AND paired, tunnel UDP to
   real peer LAN (`AppState.peerIp`) so P2P keeps riding the tunnel with
   honest host candidates. Paired-gated (pre-pairing beacons unaffected).
   No destination-port filter: any UDP to the peer unicast IP tunnels while
   the flag is off. Fine for one match; allowlist game ports before any
   production use. IPv4-only; IPv6 peer LAN leaves RULE 1b inert (fail-safe).

## Kill-test protocol (both phones, same build, flag false)
1. Fresh pairing, start friend match, play as in 2026-09-26 captures.
2. Export match bundles from BOTH phones (match_log + passthrough + udp_trace
   + score_shots, same format).
3. Pass criteria: no 54B-burst→0pps cliffs; supervisor DTLS lives to full time
   with ~50 s result phase (clean-match shape). Fail criteria: same-second
   54B cliffs on both phones → suspect #1 dead, back to NTL-silence mechanism.
4. Watch for: zero RULE 1b hits (means peer `hostAddress` was scoped/IPv6 —
   check `peerIp` in logs); game failing to start (real NAT impassable on
   hotspot → different problem, report lobby STUNCHECK error).

## GateInfo baseline (for comparison)
Both 2026-09-26 phones sent identical plaintext:
`POST /ntl/api/GateInfo.php` → `ntl.service.konami.net` (35.174.175.11:80)
`{"titleCode":"PES2022","locale":"US","version":"6.0.1","extra":"","apiLevel":"4"}`
(z1 ts 1790386119275, z2 ts 1790386131554). Response not byte-captured
(v1 recorder scope: no inbound TCP).
