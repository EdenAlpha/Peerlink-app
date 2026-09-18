# PeerLink F36 — proximity gluing + unknown-stays-unknown (the honest record)

## Why F36 exists

F35 fixed the six F34 root causes and shipped with saved matrix evidence, but
an external review of the F35 build found the job only half done:

> a real Full-Time stats screen that says **3-1** still gets silently reported
> as **1-1**. The "3"'s ink splits into 3 disconnected pieces (top curl,
> spine, bottom curl), and the piece-gluing step only reconnects pieces that
> overlap sideways by more than 50% of their width, which these don't. That
> specific function was never touched in the F35 update — diffed directly,
> byte-identical.

The review also flagged: 3 of the stat rows (Total Shots, Shots on Target,
Free Kicks) came back empty — refused, not wrong, but incomplete — and
prescribed two structural changes:

1. the piece-gluing logic must reconnect fragments that are simply *close
   together*, not just ones that overlap sideways by a lot;
2. when the reader finds something that looks exactly like a scoreboard box
   but cannot confidently read the digit inside, that must end in "unknown,"
   full stop — never a fall-through to a different screen type's reader.

Point 2 was already closed by F35's `MENU_SOLID_FILL = 0.72` guard on the
observed frame; F36 closes it **structurally** as well (see below) so the
outcome no longer depends on the menu reader refusing.

## What was measured before changing anything

Every claim below comes from a saved probe script, not a guess:

- **The old glue rule.** `merge_fragments` (one call site: the score-box
  reader) required `horizontal overlap > 50% of the narrower fragment's
  width`. A real '3' harvested from the captures (16×11 px) splits at its
  two waist joints into exactly the review's three pieces; at that font's
  stroke weights the spine piece is 7 px wide and *barely* passes the 50 %
  bar — which is why the 7-capture calibration set never caught it. On the
  review's 1376×768 device the spine renders narrower (their words: the
  curls "barely touch it sideways — nowhere near that 50% bar"), the glue
  refuses, the height floors (`h >= 0.25*box.h`, `h >= 0.62*hmax`) then
  keep a spine-shaped remainder, and the classifier reads a '1'. The
  mechanism chain was reproduced piece-for-piece from the review's
  description on real captured glyphs (scripts/f36_probe.py,
  f36_diff.py); the exact failing frame is on the reviewer's device and
  **not** in this repo, so the fix is validated on mechanism + full-matrix
  regression, not on that one frame.
- **The empty stat rows.** In `token_value_local`, pieces narrower than
  aspect 0.28 were *silently dropped* as "speck/streak". A serif-less '1'
  at 16 px is 3–5 px wide — aspect 0.19–0.27, inside the drop zone. A token
  "14" therefore read locally as "4"; `cross_value` saw 14 vs 4, refused
  the disagreement, and the row came back empty. Measured live: full-height
  thin pieces `(19, 4)` inside real token windows being dropped by the
  aspect clause (scripts/f36_stats_probe.py).

## Fixes (structural, prototype.py + ScoreBoardDetector.kt 1:1)

1. **Proximity gluing** (`merge_fragments`, both readers' paths): join two
   fragments when they are vertically close (`gap <= ygap`) AND horizontally
   overlapping (`ov > 0`) or horizontally adjacent within `xjoin`
   (= 0.15 × the fragments' own height — derived from what it finds, no
   frame constant). The 50 %-overlap bar is gone. Safety is unchanged by
   construction: side-by-side distinct digits are still re-split by
   `split_wide` after any bbox union; `max_w`/`max_h` still cap every
   union; every piece still passes the unchanged confidence gates, so a
   bad glue ends in refusal, never a guess. The merge now runs to a
   **fixpoint**, so a curl+spine+curl chain reassembles regardless of which
   side the break lands on or the order the pieces arrive in.
2. **Glue windows widened to measured joint loss**: box path
   `ygap 0.12 → 0.20` of box height, local stats window `0.12 → 0.25` of
   token height (a 2–3 px chroma bleed at a joint must sit inside the glue
   window; measured on 29 px boxes and 16 px table glyphs). Cross-row
   gluing stays impossible (next table row sits ~1 row pitch away).
3. **Thin-piece reroute** (`token_value_local`): a glyph-sized thin piece
   is now **classified, never silently dropped**. A confident digit joins
   the number; a refusal kills the token (integrity) and `cross_value`
   falls back to the global read. The "14"→"4" silent short-read is
   structurally impossible in either reader now.
4. **Unknown-stays-unknown** (`analyze`): the box finder's SHAPE-validated
   pairs are scoreboard evidence in themselves. If any pair is found but no
   pair validates by reading, `analyze` returns unknown immediately — the
   menu reader is never consulted on that frame. (Menu frames yield zero
   shape-validated pairs, verified natively and across the matrix.)

An honest rejection: a `pct_veto` extension to catch a shattered '%'
(sliver/slash fragments) was investigated and **dropped** — measured drift
and run-width features of the refusing '%' fragments overlap the real-digit
envelope ('1' drift 0.217 vs '%' fragment 0.214), so any bar that catches
the fragments risks eating real digits. Possession rows whose '%' shatters
under stress still refuse (fail-safe); nothing about them can misreport.

## Validation (all evidence saved in tests/evidence/)

- **Robustness matrix (the gate): 182 checks, 149 clean, 0 in-envelope
  WRONG reads**, +1 WRONG on the documented Gaussian-blur envelope-edge row
  (blur never occurs in the shipping capture path). Identical ledger to
  F35 — zero regressions at cell level, verified by an automated per-cell
  diff against the archived F35 evidence (work/f35_baseline_evidence.json).
- **Stat-row recovery:** the fixes recovered 14 previously-refused stat
  rows across 6 matrix cells (e.g. scale0.5/005550 7 → 13 rows = full
  table; scale0.75/140853 7 → 12; prod1280+q88 11 → 12). Completeness
  improved; wrongness did not move.
- **Parameter basin:** every new constant (BOX_YGAP_FRAC, LOCAL_YGAP_FRAC,
  XJOIN_FRAC, THIN_ASPECT) re-ran the FULL 182 matrix at
  0.70x/0.85x/1.15x/1.30x — **0 in-envelope wrong reads in all 16 runs**
  (tests/evidence/f36_parameter_basin.txt). Exactness note: at the single
  extreme BOX_YGAP_FRAC=0.26 (1.30x) three cells trade exact reads for
  fail-safe refusals (146/182 clean, still 0 wrong) — the shipped 0.20 sits
  well inside the clean plateau; wrongness never moves anywhere.
- **Regression suites:** f34_score_detector_regression ALL PASS (structure
  greps, bank hygiene, native 7/7, production 7/7, Kotlin sync);
  f31_match_automation_regression PASS; native prototype 7/7.
- **Known envelope edges (unchanged, honest):** the blur+jpg75 row and
  sub-identity-resolution refusals; itemised in
  tests/evidence/robustness_latest.txt.

## What F36 does NOT claim

- The review's exact 1376×768 frame (3-1 Full-Time board) is not in this
  repo's capture set — the fix is proven on the mechanism chain, on real
  glyphs, and by full-matrix non-regression. Re-running that frame against
  this build is the reviewer's acceptance test, and it is explicitly
  requested before this fix is called confirmed on that device.
- Physical two-phone verification of settlement is still required before
  competition use (unchanged since F31).

Version 5.0.11-f36.
