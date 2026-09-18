# PeerLink F35 — validated score reader (the honest record)

## Why F35 exists

F34 shipped with a validation doc claiming **"In-envelope wrong reads: 0"**
across the 182-check robustness matrix. That claim was false. An independent
re-run of `tests/f34_reference/robustness.py` — exactly as shipped in the F34
zip — produced **119/182 clean and 5 WRONG reads**, one of which was fully
in-envelope and completely realistic:

> a 4:3 tablet, night-shift (blue-light filter) on, JPEG quality 60 — three
> conditions each individually claimed safe in the F34 doc. On that
> combination a real Full-Time stats screen reading **0-1** was silently
> reported as **0-0**, misclassified as a MENU screen.

The F34 doc also carried no saved evidence: nothing in `tests/evidence/`
showed the 182-check matrix ever having been run against the shipped code.
Both failures — the wrong claim and the missing evidence — are process
failures F35 fixes mechanically: the harness now **always** writes its full
machine-readable log (JSON) and matrix (TXT) to `tests/evidence/`, the exit
code gates on in-envelope wrong reads, and the envelope-edge rows are
counted separately.

## Root causes found (all reproduced pixel-level, all fixed structurally)

1. **Ink extraction collapsed under chroma subsampling.**
   F34 identified digit ink as "dark AND in the box's own ink-hue family".
   Under night-shift + JPEG q60, 4:2:0 chroma subsampling bleeds the
   surrounding yellow INTO a thin navy stroke — the stroke's chroma becomes
   a yellow/navy mix spread across the whole hue arc, the ink-hue reference
   itself (a median over the contaminated set) flipped to yellow
   (measured: hue_ink = 54 deg on a navy digit), and the gate then shredded
   the digit's own pixels (50-84% of the darkest pixels rejected). The "1"
   box's ink fraction fell below the floor, the box path died, and the
   frame fell through to the menu path.
   **Fix:** ink is now luma-dominant. The threshold is Otsu's split of the
   box's own bimodal v histogram (parameter-free), with a certainly-ink deep
   zone (luma alone decides) and an ambiguous zone where only a strong-yellow
   chroma signature rejects halo. Luma is the measured invariant: ink v
   0.14-0.54 vs yellow background 0.80-1.00 on every panel/compression
   transform tested.

2. **The menu path classified inverse glyphs.** With the box path dead, the
   menu fallback accepted the solid score boxes as "yellow digit clusters on
   dark" and classified their INVERSE (the digit-shaped holes) — a filled
   box with a "1"-shaped hole reads as a confident "0". That is where the
   0-0 came from.
   **Fix:** a solid-blob fill guard. Measured: real bold menu digits fill
   0.54-0.60 of their bbox; box/strip blobs fill 0.86-0.92. Guard at 0.72
   (mid-gap). A solid rectangle is never read as a glyph.

3. **The stats table lost thin glyph parts.** The table read from a global
   colour mask; under panel shift + q60 the top bar of a '5' fell out of the
   mask and the residual shape read '6' (55 -> 56); an '8' dropped out
   entirely and the token emitted '3'.
   **Fix:** every value token is now read TWICE by independent measurements
   — the global mask and a local Otsu window over the token's own
   neighbourhood (padded toward the column's alignment side, so a lost glyph
   is recovered from local contrast). Agreement emits; disagreement refuses
   the row; a single reading emits it. Token-level analogue of the engine's
   two-agreeing-reads rule. Plus an integrity rule: a token with any refused
   glyph is refused entirely (never '38' -> '3').

4. **Vivid grass out-voted the scoreboard in the hue histogram.** On a vivid
   panel, a floodlit pitch close-up forms a tight chroma spike at 90-95 deg
   that out-weighs the UI yellow in total chroma mass (measured 25.1M vs
   14.3M on one capture); the estimated hue left the yellow guard and the
   whole frame was refused.
   **Fix:** the hue mode pools only BLUE-FREE pixels. Yellow is spectrally
   the absence of blue: b/g <= 0.06 for UI yellow even under JPEG bleed,
   while that grass spike keeps b/g >= 0.24. The estimate is now
   area-independent — a pitch cannot out-vote the scoreboard.

5. **A fused banner lost the scoreline.** At the production encoding
   (0.8x + q88) the walking banner's translucent bar crossed the strict
   threshold and fused the two score boxes into one 820px-wide component
   that failed the aspect gate.
   **Fix:** wide low-fill components get sub-box extraction from their own
   vertical run profile (tall solid columns = box candidates; the scales —
   p90 run height, median wall width — come from the profile itself).

6. **F/H finality gates were calibrated at one size.** At 10-14px the F's
   crossbar sits at ~0.55h — exactly where F34's lower-right window began —
   so an F read as UNKNOWN; and a 9px-wide H has top-stem fill 0.67, above
   the 0.60 gate.
   **Fix:** bottom-quarter window (0.70h — above the crossbar on any
   rendering) and H gate at 0.80 (measured populations: H 0.5-0.67, F ~1.0).

## Results (this code, this zip, evidence in tests/evidence/)

Calibration: **7/7 exact native; 7/7 production-encoding exact**
(1280px + JPEG q88) — type, score, finality, all 13 stat rows.

Robustness matrix (`tests/f34_reference/robustness.py`, 26 transforms x 7
captures = 182 checks):

| Result | Value |
|---|---|
| Clean checks | **149/182** |
| **In-envelope WRONG reads** | **0** |
| Envelope-edge WRONG reads (documented Gaussian-blur rows, excluded from the claim) | 1 |

The previously-failing combination `4:3+night+jpeg60` is now **7/7 clean**.
Every aspect-ratio transform (16:9 / 4:3 / 18:9 / 19.5:9 / 21:9), every panel
mode (vivid / eye-comfort / night-shift / cool / dull), JPEG q88/q75/q60,
scale 0.5x-1.5x, production encoding, and their combinations: exact or
fail-safe, never wrong.

Remaining non-clean cells are honest fail-safes, listed in
`tests/evidence/robustness_latest.txt`: mostly 11-12/13 stat rows under
deep-q/0.75x/0.5x (one row refuses rather than guesses), finality UNKNOWN at
0.5x (8px glyphs, below the identity floor), and the documented blur rows
(blur never occurs in the shipping capture path; the engine's double-read +
ML Kit fallback protect the ledger there).

Parameter basin check (`tests/evidence/f35_parameter_basin.txt`): every new
constant holds 0 in-envelope wrong reads across +/-30% perturbations; the
single hard edge (the F-crossbar window) is a measured structural constraint
of the letter F, and the shipped value leaves margin.

## Latency

Python reference: ~170 ms/frame average across the matrix (was ~148 ms in
F34). The Kotlin port runs the same steps on primitive arrays; the F32
budget note stands — template reads stay in the low-millisecond range on
device, orders below the 1.5 s OCR path. The added Otsu passes are single
64-bin histograms over box-sized windows.

## Structure

No fixed screen windows exist anywhere (the regression suite greps both the
Python reference and the Kotlin port for the banned F29..F33 constants and
passes). All geometry derives from anchors found in the frame.

## Suite status (run from this exact tree)

- `tests/f34_score_detector_regression.py`: **ALL PASS**
  (structure greps, bank hygiene 208 templates / all digits covered,
  native 7/7, production 7/7, Kotlin sync)
- `tests/f31_match_automation_regression.py`: **PASS**
- `tests/f34_reference/robustness.py`: 149/182 clean,
  **0 in-envelope wrong reads**, evidence written to
  `tests/evidence/robustness_latest.{json,txt}` on every run
- Android Gradle build: still impossible in this offline environment
  (pre-existing, documented since F31). `ScoreBoardDetector.kt` keeps the
  F33/F34 public call shapes (`analyze(Bitmap, Geometry): Detection?`);
  PrimeServer, PrimeClient and MatchAutomationEngine are untouched.
  Physical two-phone verification remains required before competition use.

## The process rule this version adds

A claim of "0 wrong reads" must be accompanied by the artifact that produced
it. `robustness.py` now writes `tests/evidence/robustness_latest.json`
(every check, every got-value, every error) and a human-readable matrix on
EVERY run, and exits non-zero if any in-envelope check is WRONG. The number
in this file is the number in the log, from this tree, this day.
