# Bubble-box accuracy: the measurement

**Status:** measured. The open question in
[`2026-08-31-bubble-crops-are-wrong.md`](2026-08-31-bubble-crops-are-wrong.md) §3
is now closed, and the answer is the bad one.
**Date:** 2026-08-31
**Verdict:** mean IoU **0.007** against a stop condition of 0.5. The boxes are not
usable and cannot be made usable by prompt work.

---

## 1. What was done

The companion document said the cheapest available instrument had never been
used: photograph a page, pull the bundle, compare a returned box against where
its bubble actually is. That measurement has now been taken.

One graphic-novel page was photographed on a Pixel 9a with a debug build, and the
diagnostic bundle pulled with `python scripts/diagnostics.py pull`
(bundle `page-1788205074358`, display 3000x4000, upload 1176x1568).

**Ground truth came from OCR**, not from eyeballing. Windows' built-in
`Windows.Media.Ocr` engine — present on the machine, no install, fully offline,
so the copyrighted page still never leaves the box — was run over
`page-upload.jpg`, **the exact pixels the model saw**. It returned 38 words with
per-word bounding boxes. Words were grouped into speech units and each group's
extent taken as the true location of that unit's text.

Six of the eight units could be anchored this way. The hand-lettered comic face
defeats OCR on "ERGH!" and garbles others ("DUNCAN." reads as "DQNCAN.",
"PRISON." as "P219N."), but a garbled word is still *located*, which is all the
measurement needs.

Scripts used are throwaway and not committed; the numbers below are reproducible
from the bundle plus the OCR JSON.

## 2. The numbers

Model box (from `response.json`, before any clamping) against OCR text extent,
both normalised to the uploaded image:

| unit | text | true text extent | model box | centre error |
|---|---|---|---|---|
| 0 | LET US GO!! | (0.584,0.170)-(0.634,0.183) | (0.650,0.120)-(0.950,0.220) | dx **+0.191** dy -0.006 |
| 3 | BUY ME MORE TIME, DUNCAN... | (0.278,0.357)-(0.389,0.445) | (0.420,0.350)-(0.750,0.550) | dx **+0.251** dy +0.049 |
| 4 | ALY, BEFORE WE GO ANY... | (0.241,0.534)-(0.379,0.650) | (0.080,0.620)-(0.500,0.820) | dx -0.020 dy +0.128 |
| 5 | THE PRISON'S WHAT YOU'RE... | (0.548,0.508)-(0.630,0.563) | (0.500,0.620)-(0.920,0.750) | dx +0.121 dy +0.149 |
| 6 | WHY WOULD YOU WANT TO... | (0.581,0.592)-(0.675,0.605) | (0.500,0.750)-(0.920,0.880) | dx +0.082 dy **+0.216** |
| 7 | BECAUSE WE'RE GOING TO... | (0.564,0.718)-(0.656,0.782) | (0.500,0.880)-(0.920,1.000) | dx +0.100 dy +0.190 |

**Centre error:** dx mean **+0.121** (sd 0.085), dy mean **+0.121** (sd 0.078).
On the 3000x4000 display copy that is roughly **360 px right and 480 px down**.

**Size:** model boxes run **3.0x to 6.0x** the text extent in width and 1.7x to
10.7x in height. Allowing that a balloon is perhaps 1.3-1.6x its text, the boxes
are still on the order of **2-4x too large**.

**IoU**, the number the bubble-reader spec named as its stop condition:

| compared against | per-unit IoU | mean |
|---|---|---|
| OCR text extent | 0.00 0.00 0.04 0.00 0.00 0.00 | **0.007** |
| text +30% (balloon estimate) | 0.00 0.00 0.08 0.00 0.00 0.00 | **0.014** |
| text +60% (generous) | 0.00 0.00 0.13 0.00 0.00 0.00 | **0.022** |

Five of six boxes have **zero overlap with their own text** under every
assumption about balloon padding. The spec's stop condition is not marginally
missed; it is missed by two orders of magnitude.

## 3. The structure of the error

The errors are not random. Fitting a single affine map from model coordinate to
true coordinate, independently per axis:

```
x:  true = 0.703 * model + 0.068     R2 = 0.737
y:  true = 0.707 * model + 0.063     R2 = 0.984
```

Both axes produce **the same slope, ~0.70, and the same intercept, ~0.065**. The
model's output is a uniform **~1.43x expansion of reality** about a point near
0.22.

The y fit at **R2 = 0.984** is the important one. Vertical *ordering and relative
spacing are near-perfect* — the model knows exactly which unit follows which and
roughly how far apart they sit — but the entire sequence is stretched to fill the
full 0..1 range. x fits far worse (R2 = 0.737) because horizontal position
carries much less ordering information to infer from.

That is the signature of a model **composing a plausible layout in reading
order**, not measuring one. Three further details agree:

- Every coordinate is a round multiple of 0.01-0.05.
- Units 5, 6 and 7 tile a single column exactly edge to edge — 0.62 -> 0.75 ->
  0.88 -> 1.00 — with identical left and right values on all three.
- The final box terminates at precisely 1.000, the frame edge.

The earlier document was right to say that 0.01 quantisation alone does not prove
fabrication (§3, "quantisation is not inaccuracy"). That caution was correct and
is now superseded by direct measurement: the granularity was weak evidence, but
the offsets are conclusive.

## 4. What this rules in and out

**Not a bug in this codebase.** The boxes above are read straight from
`response.json`, before `PageReaderImpl` clamps anything. `Downscale.kt:72` only
scales and rotates; it never crops, so display and upload share identical
framing. The companion document's §2 exclusions all still hold — and now the
remaining candidate is the model itself.

**~~Not fixable by prompt wording.~~ CORRECTED 2026-08-31 — this claim was wrong.**

> The original text read: *"Haiku 4.5 has no grounding or detection head. Asked
> for normalised boxes it returns confident round numbers. There is no phrasing
> of `PAGE_INSTRUCTION` that turns a language model into a detector."*
>
> Anthropic's coordinate documentation contradicts it directly. Claude supports a
> bounding-box workflow, and the documentation names **the format this app uses**
> as the one that does not work:
>
> > "Claude works best with absolute pixel coordinates. Ask for them explicitly
> > in your prompt. ... Claude does not work well when you ask for normalized
> > coordinates."
> > — <https://platform.claude.com/docs/en/build-with-claude/vision-coordinates>
>
> `PAGE_INSTRUCTION` (`PageSchema.kt:76`) asks for "fractions of the image
> between 0 and 1". Every measurement in this document was taken through the
> documented-bad protocol. The conclusion "the model cannot localise" was drawn
> without ever asking it the documented way.
>
> This does not invalidate the measurements — the boxes really were that wrong —
> but it invalidates the inference drawn from them, and it reorders §6: the
> protocol fix is now the first thing to try, not the OCR split.

**A real but secondary contributor: the frame is mostly not the page.** The
prompt says bounds are fractions "of the image" (`PageSchema.kt:76`), and in this
photograph the page spans only x 0.18-0.93, y 0.09-0.96 — roughly a third of the
frame is desk, facing page and background. This makes the task harder, but it is
not the explanation: a page-relative normalisation would fit slope 0.77/0.87 and
intercept 0.18/0.09, and the observed fit is 0.70/0.065.

## 5. What the model did get right

Worth recording precisely, because it constrains the fix. On this page the vision
call produced:

- **All eight speech units, no omissions**, covering every line of dialogue.
- **Verbatim text**, correct down to punctuation.
- **Correct reading order.**
- **Correct speaker attribution**, naming Duncan and Aly from context — confirming
  that restoring character enumeration (commit `bfc40d9`) fixed the regression
  recorded in the companion document §5.

The one thing it cannot do is say *where* anything is.

Its grouping of joined balloon lobes is inconsistent but defensible: it merged the
"ALY," lobe with the balloon below it into one unit, and split the
"THE PRISON'S.../WHY WOULD YOU..." pair into two, though both are the same
drawing convention. For a read-aloud app that is a pacing difference, not an
error.

## 6. Consequence for the design

`docs/PROJECT.md` records the decision as: *"No ML Kit: the vision call sees the
page layout, which is what makes speech bubbles in comics work."* The measurement
splits that claim in half. The vision call **does** read the page layout well
enough to order the units and attribute the speakers. It **does not** produce
coordinates, and no amount of prompt work will change that.

So the two jobs should be taken by two mechanisms:

- **Localisation** from something that reads pixels — on-device text recognition,
  or balloon detection — which is exactly what OCR just demonstrated at zero cost.
- **Text, order and attribution** from the vision call, which is already correct.
- **Joined on text**, since both sides produce it.

This reverses a documented decision and is a design change, not a bug fix. It is
recorded here for that decision to be taken deliberately.

## 6.1 Current implementation choice: OCR-localisation split

The current version of the app implements the cheaper architecture justified by the
latest diagnostics. The model remains responsible for speech transcription,
reading order and speaker attribution; OCR is responsible for the bubble extent.
The two outputs are aligned by text matching, and a unit only gets a crop when the
OCR match is sufficiently confident.

This is the trade-off the evidence supports:

- The vision call is strong at text and ordering, but not at fine-grained box
  placement.
- OCR is weak at semantic attribution but strong at where words sit on the page.
- Combining them is cheaper than paying a 3x premium for a higher-resolution model
  whose boxes still fail the 0.5 IoU threshold.

The implementation keeps the model's raw response available in diagnostics and
uses the OCR-localisation layer as a best-effort repair step rather than as a
replacement for the model transcript. Units with poor OCR coverage keep their
model bounds, and if those are absent or invalid the reader falls back to
text-only rendering instead of cropping a wrong region.

## 7. Immediate, independent of that decision

1. **Crop to the page before upload.** Removes the desk and facing page, and cuts
   a third of the frame that the model currently has to reason around. Worth
   doing under either design.
2. **Fix `scripts/diagnostics.py`.** A missing optional file is treated as
   success: `adb exec-out run-as ... cat <missing>` exits **0** with the shell's
   error on stdout, so the puller writes an `error.txt` containing
   `cat: ...: No such file or directory`, making a clean bundle look like a failed
   read. Observed on this very bundle.

## 8. Method note

The natural instinct was to detect the balloons with OpenCV and compare against
those. That was tried and abandoned: the white page margin, the gutters and the
lit desk form one connected bright region, so `RETR_EXTERNAL` discards the
balloons as interior holes, and `RETR_LIST` with the thresholds tuned to the
balloons returns the *panels* instead. OCR word boxes turned out to be both more
precise and far cheaper. The text extent understates the balloon, which is why
IoU is reported at three padding assumptions above rather than one.

## 9. Follow-up diagnostic: latest pulled bundle

The next fresh device capture was pulled to
`diagnostics-pulled/page-1788215961215`. It reproduces the same failure and
rules out a one-off bad page or an orientation mismatch.

### Capture geometry

| Image | Dimensions |
|---|---:|
| `page-upload.jpg` sent to the model | 1021 x 1568 |
| `page-display.jpg` used for cropping | 2439 x 3745 |

The aspect ratios differ by less than 0.01%, and both images are upright. The
display/upload relationship therefore preserves normalized coordinates; the
crop path is not introducing the observed displacement.

The model transcription is again essentially complete and correct:

- all eight speech units are present;
- reading order is correct;
- wording and punctuation are correct;
- `Duncan` and `Aly` are identified, confirming that character enumeration
  restored attribution.

The spatial output remains a coarse layout guess. For example, the model places
`LET US GO!!` at `(0.65, 0.18)-(0.95, 0.28)`, while the visible bubble is near
the upper-center of the page at approximately `(0.59, 0.02)-(0.74, 0.13)`.
The `THEY KNOW, SIR.` and `BUY ME MORE TIME...` rectangles are also shifted
down/right into neighboring artwork. The lower-right units are emitted as
large, adjacent column tiles rather than tight boxes around their balloons.

The error pattern is not a constant affine transform: boxes are oversized,
several have zero overlap with their own text, and boundaries are reused or
aligned to panel-like regions. This is consistent with the earlier measured
signature: the model knows the unit sequence but is not measuring pixel
boundaries.

## 10. Relevant vendor guidance

Anthropic's current coordinate documentation confirms that this use is a known
weak point:

<https://platform.claude.com/docs/en/build-with-claude/vision-coordinates>

The documentation says:

- Claude works best with **absolute pixel coordinates**;
- normalized coordinates such as values between 0 and 1000 do not work well;
- returned coordinates refer to the resized image Claude actually sees;
- images may be resized due to both edge limits and visual-token limits;
- images are padded to multiples of 28 pixels after resizing;
- the reliable approaches are to pre-resize to the expected dimensions or
  rescale returned pixel coordinates using the actual resized dimensions;
- for small/fine targets, crop a region of interest or use a higher-resolution
  model.

This guidance identifies two protocol weaknesses in the current implementation:

1. `PAGE_INSTRUCTION` requests normalized fractions rather than absolute pixel
   coordinates.
2. The app uploads a 1021 x 1568 image without accounting for the standard-tier
   visual-token resize. Using the documented resize calculation, that image is
   reduced to approximately 893 x 1371 before model processing.

Those weaknesses can add conversion uncertainty, but they do **not** explain
the latest result by themselves. A uniform aspect-preserving resize cancels
when coordinates are normalized, while the overlay shows large, non-uniform
semantic errors. Switching to absolute pixel coordinates is still the cheapest
controlled prompt/protocol experiment, but it should not be mistaken for a
likely complete fix.

## 11. Conclusion and recommended direction

The latest capture confirms the original conclusion: the vision call is a good
transcriber and weak localizer. The rectangle problem is not caused by
`cropRect`, the two image copies, orientation, or the scanner/capture geometry.
It is a limitation of asking a general vision-language model to produce precise
boxes for small stylized comic balloons.

The next implementation should separate the jobs:

- use local OCR or another pixel-grounded detector for text/bubble
  localization;
- continue using the vision call for transcription, reading order and speaker
  attribution;
- join localization results to vision units using normalized text, with a
  documented strategy for punctuation, OCR errors and multi-lobe balloons.

Before committing to that split, one isolated experiment is worthwhile: request
absolute pixel boxes in a fresh call and record the model-seen dimensions. If
that result remains poor, stop prompt tuning and proceed with the OCR/localizer
design.


---

## 12. The measurement is now a committed tool

Every number in sections 1-9 came from a throwaway script in a temp directory.
Those scripts no longer exist, so none of those numbers could be re-derived. The
measurement now lives at `scripts/measure_boxes.py`, and its output on both
existing bundles is committed at `scripts/fixtures/box-measurements.txt`.

    python scripts/measure_boxes.py diagnostics-pulled/<bundle> [--out FILE]

### 12.1 What reproduced, and what did not

Run against the two bundles this document already discusses:

| | published above | committed tool | |
|---|---|---|---|
| CameraX `page-1788205074358`, mean IoU | 0.007 | **0.009** | agrees |
| CameraX, centre error dy | +0.121 | **+0.119** | agrees |
| CameraX, affine slope both axes | ~0.70 | **0.625 (x), 0.803 (y)** | does NOT agree |
| scanner `page-1788215961215`, mean IoU | not recorded | **0.078** | — |
| scanner, centre slope | not recorded | **0.951 (x), 0.978 (y)** | — |

The IoU and the centre error reproduce. **The affine slopes do not**, and the
reason is visible in the tool's output: the original run paired one unit with a
single-word OCR cluster (`"TO"`), which reported that balloon as 16x too wide
and dragged the fit. The committed tool refuses a cluster with fewer than three
letters, which removes that pairing. Its slopes are the trustworthy ones.

This does not change any conclusion in this document. Both IoU figures are two
orders of magnitude below the 0.5 stop condition, and the verdict in section 2
stands exactly as written.

### 12.2 The scanner measurement, recorded at last

Section 9 discusses the ML Kit scanner bundle without ever stating its numbers;
they were reported in conversation and never written down. They are:

| | CameraX | ML Kit scanner |
|---|---|---|
| mean IoU (text extent) | 0.009 | **0.078** |
| mean IoU (text +60%) | 0.031 | **0.138** |
| centre error dx | +0.139 | +0.158 |
| centre error dy | +0.119 | +0.086 |
| box width / text width | 3.90x | **3.15x** |
| centre-fit slope x | 0.625 | **0.951** |
| centre-fit slope y | 0.803 | **0.978** |

The scanner change made the boxes roughly eight times better by IoU and, more
informatively, removed the scale distortion: the centre-fit slopes moved from
0.63/0.80 to 0.95/0.98. What remains is close to a pure translation, about +0.16
right and +0.09 down.

That matters for section 9's claim that "the error pattern is not a constant
affine transform". On the scanner bundle it very nearly is one. The distinction
is worth keeping straight because it separates "the model is guessing" from
"there is a systematic bias", and only the second is worth correcting for.

One measurement is n=1. Do not hardcode a correction for that offset.

### 12.3 Two fits, not one

The tool reports the affine fit twice, over box edges and over box centres,
because they answer different questions and this document has conflated them.
A slope over **edges** below 1 means the boxes are larger than the text they
enclose. A slope over **centres** of 1 with a non-zero intercept means a pure
positional offset, whatever the size error. Section 3's reading of "slope 0.70
means 1.43x too large" is an edges interpretation; the R2 of 0.984 quoted beside
it is a centres figure. They cannot both come from the same fit.


---

## 13. Stage A measured: absolute pixel coordinates

Bundle `page-1788278845946`, 2026-09-01. Same page, same scanner, app data cleared
before the scan so no cached parse could be served.

### 13.1 The protocol worked exactly as designed

| check | result |
|---|---|
| upload dimensions | **902x1316** |
| `modelVisibleSize(2532x3695)` | **902x1316** — identical |
| visual tokens | 1551 of 1568 |
| server-side resize | none; the `oversized_image: "error"` guard did not fire |
| coordinates returned | pixels, `x1/y1/x2/y2` (parse v5 live) |

The arithmetic and the server agree. Every mechanical precondition this iteration
set out to establish is established.

### 13.2 The localisation still fails

**Mean IoU 0.013 to 0.118 against a stop condition of 0.5.** Stage A is not a pass.

What improved, and these hold regardless of the measurement parameter discussed
in 13.3:

| | CameraX v4 | scanner v4 | **scanner v5 (Stage A)** |
|---|---|---|---|
| box width / text width | 3.90x | 3.15x | **1.88x** |
| centre error dx | +0.139 | +0.158 | **+0.095** |
| centre error dy | +0.119 | +0.086 | **+0.005** |

Box *size* is now close to correct: 1.88x the bare text extent, where a drawn
balloon legitimately encloses its text at roughly 1.3-1.5x. The vertical offset
has collapsed to zero. A rightward bias of about +0.10 remains.

So asking in the documented format did change the model's behaviour, in the
direction the vendor guidance predicts. It did not change it enough. The residual
is per-balloon scatter, not a single transform that could be corrected for.

### 13.3 The measurement is less trustworthy than sections 2 and 12 implied

While checking whether the IoU drop against the scanner v4 bundle was real, the
ground truth turned out to be parameter-dependent on this page:

| clustering gap | mean IoU (Stage A bundle) | what the OCR does |
|---|---|---|
| 0.035 | 0.013 | splits Duncan's balloon across two clusters |
| 0.050 | 0.118 | merges units 5 and 6, which are different balloons |
| 0.070 | 0.118 | same over-merge |

A **9x spread** on one bundle from one parameter. No single gap is correct for
this page: small enough to keep two adjacent balloons apart is also small enough
to split the lines of a third.

`measure_boxes.py` now reports this spread on every run rather than quoting one
number from it. Treat IoU in this document as an order of magnitude. The size
ratio and centre error are stable across gaps and are the comparisons to trust.

### 13.4 This is a problem for the recommendation in section 11

Section 11 proposes handing localisation to local OCR. **The wall hit above is
that wall.** OCR could not reliably separate this page's balloons well enough to
*measure* against, and a localiser built on it would inherit exactly that
failure, not solve it.

That does not kill the idea, but it means section 11's recommendation is
unproven, and any Stage C must first demonstrate that OCR can segment a comic
page into balloons — not merely find words. Word boxes it produces well; balloon
grouping it does not.

### 13.5 Verdict

Against the three outcomes set out in the Stage A plan, this is the middle one:
**materially changed but far below the bar.** Stage B (a high-resolution-tier
model) is the indicated next step, because the residual is scatter and scatter is
what more input resolution plausibly reduces: on this capture the page reaches
the model at 902x1316 today and would reach it at 1593x2324 on the high-resolution
tier, 3.1x the pixel area.

Before Stage B is measurable, the ground-truth problem in 13.3 has to be fixed.
A 9x measurement spread cannot resolve the size of improvement Stage B would
produce.


---

## 14. Re-baselined on a ground truth that does not depend on a parameter

§13.3 left the measurement unusable: mean IoU swung 9x on one bundle depending on
a clustering gap. The ground truth is now built differently, and the change is not
cosmetic — **it reverses part of §13.2's conclusion.**

### 14.1 How the ground truth is built now

Not by grouping OCR words by distance. By aligning them to the model's own
transcript.

The model is an accurate transcriber and a poor localiser — the finding this whole
issue rests on — so its *text* is trustworthy evidence for which words belong to
which unit even where its *boxes* are not. Aligning the OCR word sequence against
the transcript assigns each word to at most one unit, and a unit's text extent is
the union of its words. **No distance threshold participates**, which the tool now
asserts on every run by recomputing the extents at three different gaps and
failing loudly if they differ. They do not.

Two guards, both added because this failure class has now appeared four times:

- **Spatial outlier rejection.** A word assigned on text alone can come from the
  wrong balloon when the token repeats. On the Stage A bundle a single stray
  `"THE"` widened one unit's extent from x0.25 to x0.57 and destroyed its score.
  Outliers are measured in median absolute deviations from the unit's own words,
  so the scale comes from the balloon rather than from a page-wide constant.
- **Coverage reported, never hidden.** OCR reads roughly half the words on this
  stylised lettering. Every unit's aligned-versus-wanted count and the words that
  matched nothing are printed, so a flattering mean over two units cannot pass.

### 14.2 A new metric, because IoU asks the wrong question here

**Containment**: how much of the located text falls inside the box the model drew.

IoU punishes a box for being larger than the bare text — but a drawn balloon
legitimately is larger — and punishes it again when OCR reads only part of the
words. Containment is unaffected by both, and asks the question that actually
decides whether a crop is usable: *is the text inside the box?*

### 14.3 The numbers, and the reversal

| | CameraX v4 | scanner v4 | scanner v5 (Stage A) |
|---|---|---|---|
| units scored | 4 of 8 | 5 of 8 | 4 of 8 |
| mean IoU (text) | 0.005 | **0.070** | 0.058 |
| mean IoU (text +60%) | 0.021 | **0.151** | 0.109 |
| **containment** | 0.035 | **0.504** | 0.101 |
| box width / text width | 3.98x | 2.84x | **1.74x** |
| centre error dx | +0.114 | +0.161 | **+0.106** |
| centre error dy | +0.141 | +0.079 | **-0.012** |

**Stage A is not ahead of the scanner v4 baseline.** It is behind it on IoU and
five times behind on containment. §13.2 reported Stage A as an improvement; on
this ground truth that claim does not survive, and the parts of it that do survive
are narrower than stated.

What Stage A genuinely did:

- **Box size is much better** — 1.74x the text extent against 2.84x, and a drawn
  balloon is legitimately about 1.3-1.5x. This is real and holds on every metric.
- **The vertical offset is gone** — dy from +0.079 to -0.012.
- **It did not put the boxes on the text.** Containment 0.101 means roughly nine
  tenths of the located text falls *outside* the box the model drew for it. Two of
  the four scored units have containment of exactly zero: box and text do not
  overlap at all.

The earlier, flattering reading came from a confound. Scanner v4's boxes were
large enough (2.84x) to cover text by accident; Stage A's are correctly sized and
land in the wrong place, so their misses are clean misses. A better-sized box that
misses is not obviously better than a sloppy box that overlaps, and calling it an
improvement was reading the size metric as though it were the placement metric.

### 14.4 What this means for Stage B

Unchanged as a plan, sharpened as a question. Stage B asks whether more input
pixels reduce the scatter. The honest baseline it must beat is now containment
0.504 and IoU 0.070 — the scanner v4 numbers — **not** Stage A's.

If a high-resolution model does not clear that, then across a corrected protocol
and tripled resolution the conclusion is that this model does not localise small
stylised balloons, and §11's OCR split is the only remaining direction — with
§13.4's warning attached, which §14.1 has now made concrete: OCR read only about
half the words on this page and none at all of three balloons.


---

## 15. Stage B measured, and the cost taken back out again

### 15.1 Stage B on device

Bundle `page-1788284934899`, 2026-09-01. Same page, app data cleared, upload
1583x2324 — exactly `modelVisibleSize` for the high-resolution tier — with
`modelId: claude-sonnet-5`, `resolutionTier: HIGH_RESOLUTION`, parse v6 recorded in
`meta.json`.

| | CameraX v4 | scanner v4 | Stage A v5 | **Stage B** |
|---|---|---|---|---|
| **containment** | 0.035 | 0.504 | 0.101 | **0.846** |
| mean IoU (text) | 0.005 | 0.070 | 0.058 | **0.302** |
| mean IoU (text +60%) | 0.021 | 0.151 | 0.109 | **0.471** |
| centre error dx | +0.114 | +0.161 | +0.106 | **+0.017** |
| centre error dy | +0.141 | +0.079 | -0.012 | **-0.005** |
| box width / text width | 3.98x | 2.84x | 1.74x | 1.85x (sd 0.16) |
| centres fit x | 0.625 | 0.951 | — | **0.871, R2 0.998** |
| centres fit y | 0.803 | 0.978 | — | **1.065, R2 0.976** |

The largest single step in this investigation. Containment goes from a tenth to
five sixths: most of the located text is now inside the box drawn for it. The
centre error is gone on both axes. Speaker attribution improved alongside it —
named characters where Stage A returned "Unknown Character" for six of eight units.

Both ground-truth methods agree on this bundle (transcript 0.302-0.471, the
superseded clustered method 0.366-0.434), which they did not on earlier ones.

**It still does not clear the stop condition.** Best mean IoU 0.471 against 0.5.
By the letter this is the middle outcome: materially up, below the bar. The
measured IoU is biased low — OCR reads only about 60% of the words on this
lettering, so the text extent it compares against is a lower bound — but that is
an inference from containment, not a measurement, and it does not turn 0.471 into
a pass.

### 15.2 Stage B changed two things at once

Model *and* resolution. Which one bought the improvement was never established, so
it was tested: the same captured page, re-sent at a range of upload sizes and
models, each result scored by the committed `measure_boxes.py`.

| model @ upload | visual tokens | containment | IoU +60% |
|---|---|---|---|
| **Haiku 4.5** @ 902x1316 | 1551 | **0.000** | 0.001 |
| **Sonnet 5** @ 902x1316 | **1551** | **0.932** | 0.467 (3 of 6 units clear 0.5) |
| Sonnet 5 @ 1148x1687 | 2501 | 0.905 | 0.457 |
| Sonnet 5 @ 1372x2016 | 3528 | 0.840 | 0.482 |
| Sonnet 5 @ 1583x2324 | 4731 | 0.857 | 0.465 |

**It was the model, not the pixels.** At an identical 1551 visual tokens, Haiku 4.5
scores 0.000 containment and Sonnet 5 scores 0.932. Above that budget more
resolution buys nothing measurable, and containment drifts slightly *down* as it
rises. The cheapest row is the best row.

Resolution was a reasonable hypothesis — §13.5 argued for it because the residual
was scatter and scatter is what more pixels should reduce. It was wrong, and it
was wrong in a way that only a controlled sweep could show, because Stage B moved
both variables together and the result would otherwise have been credited to the
expensive one.

### 15.3 What ships

Sonnet 5, uploaded at **1568 visual tokens** rather than the tier's 4784 —
897x1316 on this capture, the same token count the app spent on Haiku 4.5.

`VisionModel` now carries an upload budget separate from the tier ceiling. The
ceiling is a fact about the model and is what keeps the upload safe from
server-side resizing, so `oversized_image: "error"` still guarantees the returned
pixels are pixels of the array we hold. The budget is our choice within it.

Net cost against the original Haiku 4.5 app: **no token increase at all.** The only
difference is Sonnet 5's price per token. Against Stage B as first implemented,
this is a **3.05x token reduction**.

### 15.4 Still open

- **n=1.** One page, and the model segmented it into a different number of units on
  each call (7 to 10), so the gaps between the resolution rows are within noise.
  What is not within noise is 0.000 against 0.932 at the same size.
- **The stop condition is unmet**, at 0.471 against 0.5, and no amount of
  re-reading the same page will settle whether that matters. The honest test is
  whether a child gets a usable crop, on several pages.
- **Sonnet 5's price per token** still has to be read from the pricing page before
  this is defensible as a permanent default.


---

## 16. The cheap configuration measured on device, and it passes

Bundle `page-1788289251857`, 2026-09-01. Same page, app data cleared. Upload
**870x1372 = 1568 visual tokens** exactly the budget, with `claude-sonnet-5`,
`HIGH_RESOLUTION` and parse v6 in `meta.json`.

| | scanner v4 | Stage A | Stage B @4731 tok | **Stage B @1568 tok** |
|---|---|---|---|---|
| **containment** | 0.504 | 0.101 | 0.846 | **0.929** |
| mean IoU (text) | 0.070 | 0.058 | 0.302 | **0.325** |
| mean IoU (+60%) | 0.151 | 0.109 | 0.471 | **0.559** |
| centre error dx | +0.161 | +0.106 | +0.017 | **+0.014** (sd 0.028) |
| centre error dy | +0.079 | -0.012 | -0.005 | **-0.008** (sd 0.015) |
| centres fit x | 0.951 | — | 0.871 | **0.917, R2 0.993** |
| centres fit y | 0.978 | — | 1.065 | **1.070, R2 0.998** |
| visual tokens | 1551 | 1551 | 4731 | **1568** |

**The stop condition is met: mean IoU 0.559 against 0.5.** The first configuration
to clear it, and it is the cheapest one measured. Four of six scored units have
containment of exactly 1.000 — the box wholly encloses its text.

The offline sweep in section 15.2 transferred to the device path: the small upload
beats the large one on every metric, at a third of the tokens.

### 16.1 Two qualifications on that PASS

**0.559 is the +60%-padding figure; against the bare text extent it is 0.325.** A
drawn balloon legitimately encloses its text with a margin, so the padded figure is
the fair comparison — but the stop condition was written without saying which, and
reading it the favourable way is a choice worth declaring rather than burying.
Containment 0.929 is the more trustworthy number and does not depend on that
choice.

**Still one page.** Every measurement in sections 13-16 is the same page. The model
also segmented it differently on each call — 7 to 10 units — so unit-level figures
carry real noise. Before this is a production default it needs a dense page, a
sparse page and a multi-panel layout, per the plan's Task 4 Step 5.

### 16.2 A bug fixed to get here

`measure_boxes.py` failed the whole bundle when an OCR word contained a raw control
character, which PowerShell emits unescaped and the JSON decoder rejects. Now
parsed with `strict=False`: the word's text barely matters, its box is what is
being measured, and failing an entire measurement over one stray byte was wrong.


---

## 17. Two new pages, and what they refute

Bundles `page-1788294930134` (dense, 10 units) and `page-1788295071078` (sparse,
6 units), 2026-09-02, on the shipped 1568-token Sonnet 5 configuration.

**Neither page is measurable.** OCR read **0 words** on the dense page and 12
heavily corrupted words on the sparse one (`'cquNq!.'`, `'youQE'`, `'OLDEQ'`,
`'EXETLY'`, `'SPPJNG'`). Zero of 16 units scored across both.

That is a limitation of the measuring instrument, not a finding about the app. The
transcription on both pages is visibly good: "SPOILED YOUR LUNCH, BARBARIANS!",
sound effects isolated as `PAF!` / `FOOMP!` / `WHIRRRRR` and attributed to
Narrator, characters named as Cogsley, Rabbit, Robot, Old Man.

### 17.1 This refutes the OCR-localisation direction

Section 6.1 and section 11 propose giving bubble localisation to OCR. Section 13.4
flagged that as unproven. It is now **refuted**: on these two pages an OCR
localiser would have produced nothing at all — no words, no boxes, no crops. A
mechanism that returns nothing on two of three real pages cannot be the one the
reader depends on.

Section 6.1's supporting claims need three corrections, recorded here rather than
edited away:

- *"OCR is ... strong at where words sit on the page"* — not on stylised comic
  lettering. 0 words on one page, 12 corrupted on another, ~60% coverage on the
  best case.
- *"paying a 3x premium"* — that premium was removed in commit 35674e4. The
  shipped configuration spends 1568 visual tokens, the same as the original Haiku
  4.5 app.
- *"a higher-resolution model whose boxes still fail the 0.5 IoU threshold"* —
  overtaken by section 16: the shipped configuration passes at 0.559, and the
  higher-resolution variant was dropped because it was *worse*, not because it
  failed.

The `PageLocalizer` seam committed in a58ace6 is harmless — it defaults to
`NoOpPageLocalizer` — but it should not be wired up on this evidence.

### 17.2 A better idea, tested: snap the model's box to the balloon

The model's boxes are well **centred** (dx +0.014, dy -0.008) and roughly the
right **size** (1.86x the text). What they are not is *aligned to the balloon*. So
use the box as a seed rather than as an answer: flood-fill the balloon's white
interior outward from inside the box, and take the filled region's extent. A
speech balloon is paper-white inside a dark outline, which is exactly the kind of
boundary a fill stops at.

Tested on all four Sonnet 5 bundles, 33 boxed units, scored with the committed
harness:

| outcome | units | share |
|---|---|---|
| snapped cleanly | 25 | **76%** |
| rejected (fill collapsed or escaped) | 4 | 12% |
| merged with an adjacent balloon | 4 | 12% |

**On every unit that snapped cleanly and could be scored, containment went from
0.887 to 1.000.** Not "improved" — the balloon's text ends up entirely inside the
box, on all ten such units.

The decisive part: **it worked on the dense page where OCR read zero words**, 8 of
10 units. It operates on pixels next to a known-good seed, so it does not need to
read anything. That is precisely the failure mode that kills the OCR approach.

### 17.3 Why this is safe to build

Each failure mode is detectable before the box is used, and the fallback is the
box we already ship today:

- **collapse** — the filled region is a tiny fraction of the seed box. Detected by
  an area floor; multi-seed sampling (take the largest region found in the box)
  already fixed every collapse on one page, including a unit that had returned
  0.008 containment.
- **escape** — the fill grows past a multiple of the seed area, meaning it leaked
  through a gap in the outline into the page background. Detected by an area cap.
- **merge** — two units snap to nearly the same region, so two adjacent balloons
  filled as one. Detected by comparing snapped boxes pairwise: IoU above ~0.8
  between two units means reject both.

Every detected failure keeps the model's original box. So the method is
**monotonic**: 76% of units get a perfect crop, the remaining 24% get exactly what
they get today, and no unit gets worse. That is a different risk profile from
every other option considered in this document, all of which replaced a working
mechanism with an unproven one.

### 17.4 What it does not do

It does not find comic **panel** edges, which was the other half of the question.
Panels are bounded by gutters, and a fill seeded inside a balloon stops at the
balloon's own outline long before reaching them. Panel detection would be a
separate mechanism — and it is not obviously needed: the reader crops a *bubble*,
not a panel.

It is also 33 units on four pages of one book, with one art style. The fill
thresholds (luma >= 165, saturation <= 60) are tuned to white paper balloons and
would need revisiting for a book that uses coloured or textured balloons, or
black balloons with white lettering.


---

## 18. The panel, not the balloon, is what should be rendered

A product correction that reframes this whole issue. The reader crops the speech
**balloon** and shows it beside the spoken line — which shows a child the lettering
they cannot read. What they want is the **picture**: the character speaking, the
action. That is the **panel**.

This is worth stating plainly because it changes what "accurate" means. Every
measurement in sections 2-17 scores a balloon crop. If the panel is what gets
rendered, balloon precision stops being the product requirement and becomes merely
an input to finding the panel — and the tolerance is far looser, because a panel is
ten to forty times the balloon's area.

### 18.1 Pixel-based panel detection: unreliable

Comic pages separate panels with gutters, so the classic approach is to project
rows and columns, find near-uniform runs, and cut. A single global projection finds
only horizontal tiers, because a full-page column profile crosses every tier and
artwork in one bleeds across another's gutter. The standard remedy is a recursive
X-Y cut: split on rows, then project columns within each strip, and recurse.

Tested on all four bundles:

| page | panels found | balloons in distinct panels |
|---|---|---|
| `page-1788284934899` | 5 | **7 across 5** — essentially correct |
| `page-1788289251857` | 5 | 10 across 4, but 5 share one 48% region |
| `page-1788294930134` | 2 | 10 across 2; 8 share a 72% region |
| `page-1788295071078` | 1 | **all 6 in one whole-page region — total failure** |

One page works, one is partial, two fail. Threshold-tuning moved which pages failed
but not how many: a purity high enough to reject artwork also rejects the thin
gutters, and a purity loose enough to catch them starts cutting through pictures.

### 18.2 Asking the model for the panel: reliable

The reason everything in this document has been hard is that balloons are *small*.
A box 10% of the page off is catastrophic for a balloon occupying 3% of it. A panel
occupies 12-36%, is bounded by a high-contrast frame, and is rectangular — a far
easier target. So the same call that returns the transcript was asked for a `panel`
box per unit alongside `bounds`.

| page | units | distinct panels | every panel contains its balloon |
|---|---|---|---|
| `page-1788289251857` | 8 | **5** | yes |
| `page-1788295071078` | 7 | **5** | yes |
| `page-1788294930134` | 10 | **5** | yes (every exception was a unit with a null balloon, not a bad panel) |

Three properties make this convincing beyond the counts:

- **The panels tile the page.** On `page-1788289251857`: y 0-330, 332-657, then
  659-1372 split into x 0-417 and 419-870, the right column splitting again at
  y 1012/1014. That is a real comic layout, not five plausible-looking rectangles.
- **Units in the same panel get byte-identical panel boxes.** Units 2, 3 and 4 all
  return `0,332 870,657`. The model is not guessing per unit; it is reporting a
  structure it has actually resolved.
- **It works on `page-1788294930134`**, the dense page where OCR read zero words and
  X-Y cut found two regions. Five panels, correctly tiled.

### 18.3 Recommendation

Render the **panel**, and get it from the model in the same call that already
returns the transcript. It costs no extra request and no extra image: one more
field per unit.

That reorders everything still open in this document:

- The balloon-snap flood fill in section 17 stops being the main event. It is still
  worth having as a refinement — containment 0.887 to 1.000 on 76% of units — but
  for highlighting a bubble within a panel, not for the primary crop.
- The OCR split in sections 6.1 and 11 is finished. Section 17 refuted it on
  reliability; this removes the requirement it was trying to satisfy.
- The 0.5 IoU stop condition was written for balloon crops. It should be restated
  for panels, where the honest test is whether the rendered picture shows the
  moment being read aloud.

### 18.4 What is not established

Panel boxes have not been scored against ground truth, because there is none: OCR
cannot supply panel extents, and no page has hand-labelled panels. The evidence
above is structural consistency plus visual inspection, which is weaker than the
containment numbers elsewhere in this document. Before this ships, at least one
page should be hand-labelled with its true panel rectangles so the claim can be
measured rather than argued.

Three pages of one book, one art style. A page with overlapping, circular, or
borderless panels has not been tried.


---

## 19. The panels measured against hand-labelled ground truth: PASS

§18.4 recorded the panel result as **unmeasured** — resting on structural
consistency and visual inspection, which is the standard this document has
criticised elsewhere. It is now measured.

**Ground truth:** `scripts/fixtures/panels-page-1788294930134.json`. Five panels
labelled by hand off a 100px coordinate grid rendered over the upload copy. The
page was chosen because **OCR read zero words on it** — no automated method can
produce ground truth there, which is exactly why it needed hand labels.

**Tool:** `scripts/measure_panels.py`. Its `--live` mode reads the schema and
prompt out of `PageSchema.kt` rather than restating them, so the measurement
cannot drift from what the app actually sends.

| unit | model panel | labelled panel | IoU |
|---|---|---|---|
| 0 | 0,0 840,322 | 0,0 840,305 | 0.947 |
| 1-3 | 0,330 840,636 | 0,325 840,620 | 0.932 |
| 4-5 | 0,645 420,1010 | 0,640 420,1000 | 0.959 |
| 6-7 | 420,645 840,1010 | 432,640 840,1000 | 0.932 |
| 8-9 | 0,1020 840,1411 | 0,1012 840,1411 | 0.980 |

**Mean IoU 0.949 against a stop condition of 0.70. Zero units in the wrong panel,
zero units without a panel.**

Two further results:

- **Stability confirmed by measurement, not assertion.** §18 observed that units
  sharing a panel receive identical boxes. The tool checks it: every one of the
  five groups returned a byte-identical rectangle. This is the property that makes
  consecutive lines render as one steady picture instead of a jittering crop.
- **The edges are tight.** Disagreements are 5-20 px on a 1411 px page, which is
  inside the slant tolerance: the page is a photograph of a curved book, so its
  gutters are not axis-aligned and neither the labels nor the model's output can be
  exact. Roughly 0.95 is the practical ceiling here, and the result is at it.

### 19.1 Task 4 is closed unbuilt

The iteration-5 plan gated pixel-based edge snapping on this measurement, with
three outcomes. The result is the first: **passes with tight edges — do not build
pixel refinement.**

There is nothing for a region-grower to fix. A 5-20 px disagreement against labels
that are themselves approximations of a slanted boundary is not an error signal, it
is the measurement floor. Building `PanelSnap.kt` would have added a pixel
algorithm, a synthetic test suite and a rejection heuristic to chase noise.

This also closes the question the expansion research asked. Expanding outward from
a balloon to the panel edges is *possible* — §17.2 measured containment going 0.887
to 1.000 on 76% of units by flood fill — but it is **unnecessary**, because the
model resolves the panel directly at IoU 0.949 for no extra request and no extra
image. The cheapest mechanism was again the best one, which is now the third time
in this investigation.

### 19.2 What is still not established

One page. The labels are mine, drawn from the same image the model saw, and a
second labeller might place the gutters a few pixels differently — though not
enough to move 0.949 below 0.70.

Speaker attribution drifted between calls on this page (`Rabbit` in one run,
`Narrator` in another for the same unit). That is unrelated to panels and does not
affect this result, but it is a reminder that the model is not deterministic across
calls, and any future single-run comparison should account for it.


---

## 20. Panels on device, on an unseen page

Bundle `page-1788370633603`, 2026-09-02. A page never used in any measurement here,
read through the app's own path rather than an offline harness. `parseVersion: 7`,
`claude-sonnet-5`, upload 911x1316.

**5 units, all 5 with a balloon and a panel, resolving to 4 distinct panels:**

| panel | units | content |
|---|---|---|
| 18,15 460,636 | 0, 1 | robot kneeling with the dragon — two balloons, identical panel box |
| 473,15 893,315 | 2 | dragon from behind |
| 473,325 893,636 | 3 | dragon close-up |
| 18,650 893,978 | 4 | wide beach band, three characters |

Visual inspection: every panel sits on the drawn border, every balloon is inside
its panel, and the page's fifth panel — the hut, which carries no dialogue —
correctly has no unit referencing it. The stability property holds again: units 0
and 1 share a panel and received a byte-identical box.

The timeout fix held. No `error.txt`, where the two preceding scans on this device
both failed with `SocketTimeoutException`.

### 20.1 A defect this bundle exposed

`parse.json` contained no `panel`, while `response.json` did. The cause was not
validation: `DiagnosticWriter.parseJson` hand-writes its output field by field and
had never been taught the new field.

This is the same silent-drop class as `toSpeechUnits`, which section 19's work had
guarded with a test — and it was missed on a *second* hand-written copier in a
different file. It matters more than a cosmetic gap, because the diagnostic is the
only way to tell, from a pulled bundle, whether a panel was rejected by validation
or never recorded. Those two look identical, and one of them is a bug.

Fixed, with the two boxes now pinned by `DiagnosticWriterImplTest`, and the
function's KDoc says plainly that it is a place fields go to be forgotten.
