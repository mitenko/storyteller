# Speech-bubble crops are wrong

**Status:** open, under investigation. Diagnostic capture built; measurement not yet taken.
**Date raised:** 2026-08-30
**Severity:** blocks the bubble reader. Cropping the bubble is the feature.

---

## Symptom

The reader shows one speech unit at a time as a crop taken from the page
photograph. On device, the crops are **"comically wrong"** — reported after using
the app on a real graphic novel page.

A second, separate problem surfaced during the same investigation: **speaker
attribution has collapsed to "Narrator" for every unit.** See §5.

## 1. What the evidence is

Ten cached parses were read directly out of the app's Room database
(`adb exec-out run-as com.storyteller cat databases/storyteller.db`), covering
**292 bounding-box coordinates**.

| Measurement | Value |
|---|---|
| Coordinates landing exactly on a 0.01 grid | **292 / 292 (100%)** |
| Coordinates landing exactly on a 0.05 grid | 128 / 292 (44%) |
| Coordinates falling outside 0..1 | **5** |
| Largest coordinate seen | **1.360** |

The out-of-range values are concentrated in one page (`83dd2cdf6c10`), where
three consecutive units run down past the bottom of the page:

```
[10] L0.520 T0.960 R0.880 B1.080
[11] L0.520 T1.080 R0.880 B1.180
[12] L0.720 T1.280 R0.980 B1.360
```

The model was told, verbatim: *"as fractions of the image between 0 and 1,
measured from the top left."*

## 2. What this rules out

Each of these was checked by reading the code, not assumed:

- **Coordinate-space mismatch between the two image copies.** Bounds are
  normalised against the 1568px copy the model saw; crops are taken from the
  full-resolution `displayBytes`. Those always share orientation —
  `displayBytes` is the raw capture only when no rotation was baked in, and a
  rotated full-resolution copy otherwise — and downscaling preserves aspect
  ratio. The normalised mapping is therefore sound.
- **`cropRect` arithmetic.** Padding, clamping and the paired rounding are
  correct, and it rejects degenerate boxes rather than emitting junk.
- **The prompt's stated convention.** It matches what `cropRect` assumes.

## 3. What is NOT established

**That the in-range boxes are inaccurate.** An earlier claim that the model is
"fabricating" boxes was inferred from the 0.01 granularity, and that inference
outran the data. Quantisation is not inaccuracy: on a 1568px page, 0.01 is about
16 pixels, and against a bubble a few hundred pixels wide — with 4% padding
around the crop — a 16-pixel rounding error is comfortably absorbed. A model
rounding its output to two decimals can still be looking at the right place.

**No one has yet compared a returned box against where its bubble actually is.**
That measurement has never been taken, on this feature or on the badge feature
that preceded and failed for the same reason.

**The prompt has never been iterated for this task.** The `bounds` sentence is
word-for-word the one that produced the character boxes that already failed in
use. Nobody has tried asking for the whole balloon outline, or for the model to
work down the page bubble by bubble.

## 4. What IS proven

**Some boxes are unusable, and the app hides it.** Five coordinates fall outside
0..1. The client clamps into range, which collapses those boxes to zero height;
`cropRect` then rejects them and the reader silently renders the unit's text
instead. The fallback is graceful, which is exactly why this was invisible: the
child sees words instead of a bubble and nothing looks broken.

## 5. Second issue: attribution collapsed to Narrator

| Parse version | Units | Attributed to "Narrator" |
|---|---|---|
| v1 | 30 | 23% |
| v2 | 36 | **6%** |
| v3 (current) | 8 | **100%** |

`parseVersion` 3 is the first parse made after the `characters` array was removed
from the schema and prompt. Every unit now returns as "Narrator". Older parses of
comparable pages name "Gray Wolf", "Orange Fox", "Human", "Redbeard".

**Likely mechanism:** asking the model to enumerate the characters made it work
out who was who, and the `speaker` field inherited that reasoning. Removing the
array to save prompt weight took the reasoning with it.

**Why it matters more than the crops:** attribution drives the per-character
voices, which is the app's founding premise. It also contradicts the user's
explicit report that attribution was fine and the names were valuable.

**Caveat:** v3 is a single page. This is a strong signal, not proof. One more
photograph settles it.

## 6. Diagnostic capture (built, commit `ae83a3f`)

Every fresh vision call now writes a bundle to `filesDir/diagnostics/`:

| File | Why |
|---|---|
| `page-display.jpg` | the full-resolution upright copy crops are taken FROM |
| `page-upload.jpg` | the downscaled copy the model actually SAW |
| `response.json` | the payload **exactly as it arrived**, before clamping |
| `parse.json` | what the app made of it, so raw and interpreted can be diffed |
| `meta.json` | both images' real pixel dimensions, device, timestamp |

`response.json` is the file that matters: the clamp into 0..1 is what hid the
`1.36` values until the cache was read by hand, so the payload is stored
untouched.

Nothing is recorded on a cache hit — there is no response to record. Recording
runs off the main thread, catches cancellation first and swallows everything
else: a diagnostic must never cost a child a page. Retention keeps the 20 most
recent bundles.

### Using it

With a debug build on a connected device:

```
python scripts/diagnostics.py pull
```

Copies every bundle to `diagnostics-pulled/` and writes an `overlay.png` per
page with each returned box drawn on the actual photograph, labelled by unit
index and speaker. Boxes outside 0..1 draw in red at the edge they ran past
rather than vanishing, and are counted in the summary.

Transport is `adb run-as` over the cable: no permission, no network, no upload.
The page photographs are copyrighted book pages and never leave the machine.

**Note:** parses already cached produce no bundle. Photograph a page fresh.

## 7. Next steps, in order

1. **Take one overlay.** Photograph a graphic-novel page, pull the bundle, look
   at `overlay.png`. This replaces both the "comically wrong" impression and the
   inference from round numbers with a picture of exactly how far off the boxes
   are. It is the cheapest instrument available and it has not been used.
2. **Restore character enumeration in the prompt** (§5) — a small, targeted
   revert of the change that removed it. Not the badge crops; only the part that
   makes the model identify who is speaking.
3. **Iterate the prompt for bubble localisation** (§3) before concluding the
   model cannot do this. Measurement and improvement are cheaper together than
   sequentially.
4. **Then decide** whether the crop path is viable. If the boxes cannot be made
   accurate, the text fallback already works and is what has been rendering all
   along — but that decision should follow the measurement, not precede it.

## 8. Related history

The badge feature that preceded this reader cropped *character* boxes from the
same model on the same call, and failed in use for what may be the same reason.
Its premise was never measured either. The bubble reader's own spec
(`docs/superpowers/specs/2026-08-26-storyteller-bubble-reader-design.md` §2, §9)
required measuring bubble-box accuracy **before** building on it, with a stop
condition of mean IoU below 0.5. That sequencing was not followed: the eval was
built but has no fixtures, so the reader was rewritten on an unmeasured premise —
exactly the risk the spec named.
