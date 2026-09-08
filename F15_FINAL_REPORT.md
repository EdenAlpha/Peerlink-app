# F15 FINAL REPORT — F13 Everything + F14 Goal Detection, Fully Merged

**Deliverable:** `Peerlink-main_F15_f13_goaltruth.zip` (this tree)
**Mission (user's words):** *"take the f13 score version and update the goal detection FULLY matchprotocolreader.kt etc"* — F14-goal-truth solved goal detection but regressed the F13 good things. F15 is the F13 source with the F14 detector ported into it completely, and every regression fixed.

---

## 1. What F14-goal-truth regressed (all now restored)

| # | F13 good thing F14 dropped | F15 status |
|---|---|---|
| 1 | **40s full-time trailing window** (measured: 28.1s of post-burst result/stats traffic on the recorded 1-Sep match; F14 reverted to 10s, which refuses to settle that match) | `FULL_TIME_MAX_TRAILING_GAME_MS = 40_000L`, pinned by `fullTimeTrailingWindowAccepts28Seconds` |
| 2 | **sport/dport in telemetry** (the port-pair rematch boundary: measured 1-Sep — match 2 opened on port 18551 while match 1 ran on 22677) | Telemetry **v4**: 88-byte events = 20 hdr + **64B head** + sport/dport @84/86; boundary restored end-to-end |
| 3 | **Reconnect merge** (same-port second handshake within 45s of *game-traffic* age = same match, score preserved, tables re-learned) | Kept, plus the **decode-freeze model** so a re-key can never erase already-seen goals (see §3) |
| 4 | **Epoch quarantine watermark** (delayed fragments of a sealed epoch never resurrect it or bleed into the next) | Kept; synthetic 8 |
| 5 | **Un-gated silent epoch discard** (trace A: match 1's tail must not absorb match 2) | Kept; real replay 5 |
| 6 | **Handshake evidence** (probe/exchange as session metadata + "match start was not proven" settlement blocker) | Kept as METADATA only — it never decides who scored (that is the 0xC6 rule) |
| 7 | **Rich ledger (integrity v4 → v5)**: reconnects, port pair, quarantine count, handshake sides, per-goal audit | v5 adds phase confirmation, player IDs, relay flag, attribution basis |
| 8 | **Full test batteries**: real PCAP replays (25/25), synthetic (31/31), structural (27/27) | Rebuilt for the merged semantics — see §5 |
| 9 | **MatchPhase lifecycle** (WAITING/STARTING/LIVE/ENDING/SEALED), live score strip, unresolved-goal UI | Kept (F13 UI unchanged — it compiles against the merged model as-is) |

## 2. What F14-goal-truth contributed (all ported)

- **Unified keystream** `V[i] = KS[i]^KS[58]` per direction, majority-voted over the len-39/59/63 zero-footer classes (chained at 36/58, ≥95% share, ≥50 packets).
- **Deterministic goal detection**: 18-byte absolute signature at payload 19..36, per-packet key X voted over the signature (≥14/18), phase byte at 37.
- **Attribution**: the sender of the **0xC6 original** is the scorer; 0xC7 = conceder's echo, 0xC4 = post-event sync. Only originals count. The recorded paid-wrong-side failure (+0.30) is structurally impossible now.
- **Player identity**: custom-STUN (magic 21 12 a4 42, types 0x08xx/0x09xx) 10-digit modal IDs per direction — the ledger records *who* scored, not just which side.
- **Relay honesty**: ≥2000 game packets without convergence → relay-suspected, recorded, never settles.
- **Native filter**: len-39/63 classes + goal candidates 300..2000 + custom-STUN frames retained (F13's filter dropped them — the detector could never see them).

## 3. The one new mechanism: decode-freeze

F13 decoded goal messages at arrival (score survives a re-key, but a pre-gate goal is lost). F14 kept raw candidates until the end (pre-gate goals survive, but a mid-match re-key decodes old candidates against the NEW keystream and loses them). **F15 merges both:**

- candidates are collected raw from the first packet (pre-gate tolerance);
- the moment the gate converges — and again at every re-key — pending candidates are decoded and **frozen** into keystream-independent goal messages (score survives reconnect);
- frozen messages are bounded (512) and feed the same 12s clustering / echo / phase logic.

## 4. Settlement (fail-closed union — stricter than either parent)

PeerCoins move only when ALL of: keystream gate converged · handshake proven · full-time burst + terminal boundary with the 40s trailing window · every goal corroborated by the peer echo · every goal has a confirmed 0xC6 original · zero telemetry drops/parse failures/decode queue overflows · ≥100 packets per direction · ≥30s duration. Anything less records for audit and pays nothing. Ledger v5: v4-and-older records (the calibration era) pay nothing.

## 5. Verification (all run this session, real tools)

| Suite | What it proves | Result |
|---|---|---|
| `tests/run_native_tests.sh` (g++, ASan/UBSan) | native telemetry v4: 64B head, classes 39/59/63 + STUN + 300..2000 retained, ports carried, 88-byte layout | **50/50 PASS** |
| `MatchProtocolReaderTest.kt` (kotlinc 2.0.20 + JUnit 4) | gate convergence, relay honesty, **paid-wrong-side regression**, user-scored attribution, missing-0xC6 block, non-goal rejection, multi-goal, handshake evidence, STUN IDs, **pre-gate goal survival**, **40s window**, **reconnect score preservation**, handshake-proof blocker | **13/13 PASS** |
| Core + tunnel package compile (kotlinc) | MatchProtocolReader / MatchData / MatchTracker / NativePeerLinkBackend / PacketParser / StunFabricator compile clean against the merged model | **PASS** |
| `tests/f15_port_validation.py` | REAL PCAPs through the exact v4 event stream: 28_Jun → 0-1 LOSS peer **1825107968** c6-confirmed corroborated; 29_Jan → 0-1 LOSS peer **1774133334**; 06_Jan → relay, honest; synthetic flipped user goal → ME **1012115771** | **ALL PASS** |
| `tests/f15_replay_real.py` | REAL EVIDENCE: both PCAPs settle the correct LOSS end-to-end (gate+handshake+full-time+echo+phase); direction flip inverts attribution per-perspective; 1-Sep trace B seals at the app-log timestamp (557.0s vs 556.56s expected); trace A discards the un-gated tail, records match 2, never settles it | **26/26 PASS** |
| `tests/f15_synthetic.py` | equalizer 1-1 / 2-1 (A,B,A) / 0-3; unconfirmed-phase cluster blocks; **reconnect merge with score preserved across a re-key**; **port-pair change splits matches**; quarantine; relay; 28s full-time tail; pre-gate goal; halftime split (documented) | **ALL PASS** |
| `tests/f15_structural.py` | source invariants: detector intact, all 10 regressions fixed, no responder rule, no calibration model, ledger v5, v4 layout consistent Kotlin↔native, suites present | **45/45 PASS** |

Kotlin/Gradle full-build still requires the user's Android toolchain (this sandbox has no Android SDK); core+tunnel compile with the real Kotlin 2.0.20 compiler and behavioral verification runs against the line-for-line Python port — same protocol as F8–F14.

## 6. Honest boundaries (unchanged truths)

1. The 0xC6-original rule is 3-for-3 on opponent-scored real goals plus the synthetic local-direction test; one pcap of a match where *you* score pins the real direction (a flip is one line if it ever inverted).
2. Relayed matches stay opaque — reported, never guessed, never settled.
3. Halftime > 60s splits the match into two records (each fail-closed on its own chain).
4. eFootball protocol updates change the signature → the gate never converges → the reader fails loudly instead of lying.
5. A > 60s halftime or a same-port abandoned-epoch merge can under-settle; every such record carries its blockers in the ledger.

## 7. Build

Unchanged: same Gradle project, same CI (the new `MatchProtocolReaderTest` runs in it), same native build, no new permissions, no root, no app modification, no extra VPN.
