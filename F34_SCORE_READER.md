# F34 — Structure-First Score & Statistics Reader (device-independent rebuild)

## Why F34 exists (the honest audit)

The F29..F33 readers worked on the seven calibration screenshots and on
nothing else. Every constant in them — score-box windows (`x 0.415..0.4975`),
banner zones (`y 0.148..0.235`), the statistics geometry (`c0 = 0.2944`,
`step = 0.04472`), colour windows (`sat >= 0.58`, absolute RGB), the finality
band (`y 0.225..0.285`) — was a fraction of one phone's 1600x720 display.
That is not a rule, it is a measurement. The first aspect ratio that was not
20:9 broke it, and the fix was a threshold nudged by 0.0001 — curve-fitting
to one extra data point while a dozen other hardcoded constants waited to
fail on the next device. Nigeria / India / Indonesia / Malaysia is exactly
the install base where 18:9, 19:9, 19.5:9, 20:9, 21:9, tablets, notches,
vivid-mode oversaturation and eye-comfort dimming are all in play, and the
whole calibration set was ~7 screenshots off one physical phone.

F34 is the rework that critique demanded: **the reader finds the board by its
own shape, wherever it sits, and derives everything else from what it
found.** There are no fixed screen windows left in the pipeline.

## The five design rules (enforced by `tests/f34_score_detector_regression.py`)

1. **No fixed screen windows.** The score boxes, the label strip, the 13
   table rows, the value tokens and the menu digits are FOUND by structural
   search over the full frame; every sub-window derives from found anchors.
   The regression greps both the Python reference and the Kotlin port for
   the banned F29..F33 constants and fails if any return.
2. **Adaptive colour.** The UI-yellow hue is estimated per frame from the
   frame's own strong-chroma warm hue histogram (chroma-weighted mode over
   5-degree bins — UI yellow is one spectrally tight cluster, so it wins its
   bin even when vivid floodlit grass carries more total chroma across many
   bins). Masks centre on that estimate with generous absolute rails.
3. **Ink = local contrast + blue lean + ink-hue family.** Digits inside a
   yellow box are "significantly darker than the box" (threshold from the
   box's own value distribution), blue-leaning (`b + 16 >= r,g`), and within
   40 degrees of the box's own ink-hue reference (median hue of the pixels
   already dark enough to be ink). Yellow-navy JPEG halo fails all three;
   vivid-stretched navy passes all three. No absolute RGB anywhere.
4. **Authored-scale bounds only.** Size gates bound what the game UI can
   render relative to frame height (UI scales with display height on every
   device) and sit 2-6x wider than any measured device. Fragments use
   absolute anti-speck floors (6px) so a 13px base bar can join its digit
   on any canvas.
5. **Fail-safe.** Ambiguity refuses the read. A refused read is never a
   wrong read; the engine falls back to the retained ML Kit chain.

## Pipeline (Python reference `tests/f34_reference/prototype.py`,
   ported 1:1 to `ScoreBoardDetector.kt`)

* Pre-gate: no strong-chroma warm hue in the frame -> not a score screen.
* **Anchor search** (whole frame): yellow components filtered by shape,
  solidity, dark-glyph evidence and an **8-point solidity test** (a real box
  is yellow at its four bbox corners and four edge midpoints; every digit
  glyph fails at least one). Pairs must be size-coherent; the largest pair
  is validated by actually reading it.
* **Label strip**: solid-yellow run below the pair, scanned over the pair
  span plus one span of context (the text sits in the centre).
* **Finality**: F / H / clock on the strip's leftmost glyph — glyph-internal
  fractions only (scale-free), pinhole-tolerant hole counting.
* **Statistics**: below the strip, glyph size bounds from the score-box
  height, rows from y-clustering, tokens from x-proximity, the label column
  from cross-row **occupancy** (labels exist in every row and are wide),
  values chosen by position and validated by classification, rows indexed
  by the y-lattice so one unreadable value never shifts the rest. The '%'
  is excluded by aspect family (digits <= 0.82 tall-crop aspect, '%' 0.88).
* **Menu**: the largest readable yellow digit clusters on a dark background;
  the dash survives fragment merging as its own wide-short cluster and
  splits home/away. Authored-scale floor keeps clock text out.
* **Templates**: 88-grid bank harvested from the calibration captures
  THROUGH the F34 structural reader, under lossless AND the production
  encodings (q88, q75, 1280px+q88); every degraded-encoding template must
  still be recognised as its digit by the lossless bank before admission
  (bank hygiene), near-duplicates are pruned, and degraded templates carry
  a 0.85 scoring weight.

## Measured results (all seven calibration captures)

| Suite | Result |
|---|---|
| Native PNG | **7/7 exact** — type, score, finality, all 13 stat rows |
| Production encoding (1280px + JPEG q88) | exact on all captures (bank covers it) |
| Aspect reframes 16:9 / 4:3 / 18:9 / 19.5:9 / 21:9 | exact (board found wherever it sits) |
| Vivid / eye-comfort / night / cool / dull panels | exact or fail-safe; cool+dull exact |
| JPEG q88 / q75 / q60 | score exact; deep-q tables fail safe (never wrong) |
| Downscale 0.75 / 0.5, upscale 1.5 | score exact to 0.5x; tables fail safe below ~0.6x |
| Gaussian blur + JPEG (beyond production) | envelope edge — see below |

In-envelope wrong reads: **0** across the 182-check matrix
(`tests/f34_reference/robustness.py`). Fail-safe refusals concentrate at
sub-production JPEG quality and 0.5x resolution, where 16px digits carry
no identity — the reader refuses instead of guessing.

### Documented envelope edge

Gaussian blur + JPEG q75 does not occur anywhere in the shipping path
(screen capture -> JPEG q88, optional LANCZOS downscale to 1280). At that
destruction level a 16px digit loses the information that separates
'6' from '3' (confidence 0.83 for a wrong read); no gate can refuse what no
signal can detect. Those matrix cells are kept VISIBLE as WRONG in the
robustness output rather than hidden: they mark the operating envelope, the
engine still requires two agreeing reads before settling, and the ML Kit
fallback chain remains for frames the pixel engine refuses.

## Files

| File | Change |
|---|---|
| `app/.../service/ScoreBoardDetector.kt` | **rewritten** — structure-first gate+read, no position windows |
| `app/.../service/ScoreBoardTemplates.kt` | **regenerated** — 88-grid weighted bank, production encodings |
| `tests/f34_score_detector_regression.py` | **new** — structure + bank + calibration + Kotlin-sync |
| `tests/f34_reference/` | **new** — executable Python reference, template exporter, robustness harness |
| `tests/run_checks.py` | registers the F34 suite |
| `app/build.gradle.kts` | version 5.0.9-f34 |

## Verify on the host (no Android SDK needed)

```bash
python3 tests/f34_score_detector_regression.py     # structure + bank + calibration + sync
F34_NOBLUR=1 python3 tests/f34_reference/robustness.py   # in-envelope matrix
python3 tests/f34_reference/prototype.py           # the 7 captures, native
F34_CAPTURES=/path/to/captures python3 tests/f34_reference/robustness.py
```

`F34_CAPTURES` points the reference at any captures directory; adding a new
device's screenshots to the harvest set is one command:
`F34_CAPTURES=... python3 tests/f34_reference/export_templates.py`.

## Known limits

* The template bank encodes the eFootball UI font. A future UI reskin needs
  a re-harvest (one command, above) — not a code change.
* Beyond-envelope destruction (Gaussian blur) can produce confident wrong
  reads; the envelope is documented above and protected at the engine level
  by the two-agreeing-reads settle rule.
* Android Gradle build remains impossible offline (as for F31/F32/F33);
  `ScoreBoardDetector.kt` keeps no Android imports in the reader core and
  preserves the F33 call shapes (`analyze(Bitmap, Geometry): Detection?`),
  so PrimeServer/PrimeClient/MatchAutomationEngine are untouched.
