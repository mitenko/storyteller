# Storyteller Iteration 5 — Render the panel, not the balloon

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a child the picture that goes with the line being read aloud. The reader currently crops the speech balloon, which shows them the lettering they cannot read; it should show the comic panel.

**Architecture:** The panel comes from the model, in the call that already returns the transcript — one more property per unit, no extra request and no extra image. A `panel` field is threaded through the five types the box must cross, the reader prefers it over the balloon, and the balloon is kept rather than replaced so it can still serve as the fallback. Pixel refinement of the panel's edges is specified but **gated on measurement**, not built speculatively.

**Tech Stack:** Kotlin, Android (BitmapRegionDecoder), Retrofit + kotlinx.serialization, Robolectric, JUnit 4, Python 3 for the measurement harness.

**Spec / evidence base:**
- [`docs/issues/2026-08-31-bubble-box-accuracy-measured.md`](../../issues/2026-08-31-bubble-box-accuracy-measured.md) §18 — the panel result this implements; §17 for what it replaces.
- [`docs/issues/2026-09-02-panel-edge-expansion-research.md`](../../issues/2026-09-02-panel-edge-expansion-research.md) — the pixel-expansion research, adjudicated below.

## Global Constraints

- `GraphTest` enforces `ui -> domain <- data`, `ui` never imports `data`, `domain` never imports `android.*`/`androidx.*`.
- Keep reject-don't-clamp at every level: a panel that fails validation becomes `null`, the reader falls back to the balloon, then to text. **Never manufacture a plausible-looking rectangle.**
- Keep `transformations: {"oversized_image": "error"}` and `PAGE_UPLOAD_VISUAL_TOKENS`. Panel coordinates arrive in the same pixel space as bubble coordinates and normalise identically — by the encoded dimensions, never the padded ones.
- Commits use the repo's existing author, `mitenko`. **No `Co-Authored-By` trailer of any kind.**
- Gradle: `BindException` → `./gradlew --stop`, retry. `Unable to delete directory .../test-results/...` → `./gradlew --stop`, delete `app/build/test-results` and `app/build/reports`, re-run. Neither is a code failure.

---

## What this revision fixes

The previous revision was reviewed, and a pre-flight check of the API surface it
assumed found more.

### The review comment: a self-contradicting test — **accepted**

Task 1's normalisation test supplied a non-null balloon *and* a whole-image panel,
then asserted the panel survived. Step 5's own `toPanel` rejects exactly that
combination (`coversPage && balloon != null`). No implementation could satisfy both,
and an implementer would have discovered it only after writing the validator.

Fixed by giving the normalisation test a **real panel** (a top-third band) rather
than the whole image. Its purpose is the division arithmetic, so a degenerate
rectangle was never the right fixture. The whole-page case keeps its own dedicated
rejection test, where it belongs.

### Three defects the review did not reach

**1. `cropFor(page, unit)` does not exist.** I invented that signature. The real one
is `cropBubble(image: PageImage, bounds: BoundingBox?): Bitmap?`
(`BubbleCrop.kt:44`), called from `ReaderScreen.kt:240` as
`cropBubble(pageImage, line.bounds)`. Every Task 2 test referenced a function
nobody could find.

**2. `toSpeechUnits()` is a silent-drop point, and the plan never mentioned it.**
This is the worst of the three. `SpeechUnit.kt:29` maps `ParsedUnit` to
`SpeechUnit` field by field. Add `panel` to both data classes, forget the mapper,
and **everything compiles, all existing tests pass, and every panel is silently
null** — indistinguishable from a model that returned none. Task 1 now names the
mapper and pins it with a test that fails if the field is dropped.

**3. The panel crosses five types, not two.** The previous revision said "modify
`ReaderUiState.kt`, `ReaderViewModel.kt`, `ReaderScreen.kt` as the crop source
requires", which is not an instruction. The actual chain is `UnitDto` →
`ParsedUnit` → `toSpeechUnits()` → `SpeechUnit` → `ReaderUiState.Line`
(`ReaderUiState.kt:38`, built at `ReaderViewModel.kt:224`) → `cropBubble`. Every
link is now named with its file and line.

### The pixel-expansion research, adjudicated

**Accepted, and it shapes this plan:**

| finding | why it matters |
|---|---|
| **§8's OpenCV note: "the useful contours tended to be comic panels rather than speech balloons"** | The most valuable line in the document, and it is filed under a *failure*. Read against §18 it is corroboration from an unrelated method: pixel analysis keeps finding panels because panels are what is salient. Two methods failing at balloons and succeeding at panels beats either alone. |
| **"No visible boundary" — borderless panels, full-bleed art** | A real risk for the model's panel too. Task 1 rejects a whole-image panel rather than rendering the entire page for every line. |
| **The synthetic test set** | The best idea in the document: accept/reject logic made unit-testable with generated bitmaps, no copyrighted page required. Adopted wholesale in Task 4. |
| **"A strict axis-aligned rectangle will fail when the page is slanted"** | Mitigated by ML Kit Document Scanner's rectification, which is why measured dx/dy are near zero. Stated so nobody removes the scanner thinking rectification is incidental. |
| **No OpenCV until a bitmap prototype earns it** | Adopted. An APK-size and native-integration cost taken before the cheap version is shown insufficient is a cost taken on faith. |

**Rejected — OCR as the seed.** The algorithm opens "use the union of the OCR word
boxes matched to one speech unit". Measured on these pages: **0 words** on the dense
page, 12 corrupted on the sparse one, ~60% coverage at best.

And it is worse than unreliable. **`OcrWord` has no producer anywhere in
`app/src/main`** — `TranscriptOcrLocalizer` (a58ace6) is fed only by its own unit
test — and ML Kit Text Recognition is absent from `gradle/libs.versions.toml`,
which carries `mlkit-document-scanner` alone. The OCR path is *unbuilt*, and
building it is an uncosted prerequisite. The seed becomes the model's own box,
which exists on every unit of every page. The expansion algorithm survives the
substitution intact; only its starting point changes.

**Deferred, not rejected — the expansion algorithm itself.** §18 shows panels can be
found. Whether their *edges* need tightening is unknown, and that is Task 3's job.
Building a region-grower first would repeat Stage B: an expensive fix aimed at an
unmeasured problem.

---

### Task 1: Ask for the panel, thread it through, validate it

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/data/local/Entities.kt:7` — `PARSE_VERSION`
- Modify: `app/src/main/kotlin/com/storyteller/domain/model/SpeechUnit.kt` — `ParsedUnit:9`, `SpeechUnit:11`, `toSpeechUnits:29`, plus `BoundingBox.contains`
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageSchema.kt` — schema and instruction
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageReaderImpl.kt` — `UnitDto`, validation, both `toDomain` call sites
- Test: `app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/model/SpeechUnitTest.kt` (create if absent)

**Interfaces:**
- Consumes: `BoundingBox(left, top, right, bottom)`, `BoundsDto`, the existing pixel-normalisation path.
- Produces: `ParsedUnit.panel: BoundingBox?`, `SpeechUnit.panel: BoundingBox?`, `BoundingBox.contains(other): Boolean`, `PARSE_VERSION = 7`.

- [ ] **Step 1: Bump the parse version FIRST**

`Entities.kt:7`: `const val PARSE_VERSION = 7`. Cached v6 rows have no panel, so
without the bump every re-read returns a panel-less parse and the feature measures
nothing.

- [ ] **Step 2: Write the failing tests**

In `PageReaderImplTest.kt`. `pageImage()` is 893x1372, so the arithmetic is exact:

```kotlin
    @Test fun `a panel is normalised against the uploaded image like bounds are`() = runTest {
        // A real panel -- the page's top third -- not a degenerate whole-image
        // rectangle. This test is about the division; the whole-image case has its
        // own test below, where it is a rejection rather than a pass.
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI",
             "bounds":{"x1":223.25,"y1":343,"x2":669.75,"y2":1029},
             "panel":{"x1":0,"y1":0,"x2":893,"y2":686}}
        ],"characters":[]}""")

        val u = reader().read(pageImage()).getOrThrow().units[0]
        assertEquals(0f, u.panel!!.left, 0.0001f)
        assertEquals(0f, u.panel!!.top, 0.0001f)
        assertEquals(1f, u.panel!!.right, 0.0001f)
        assertEquals(0.5f, u.panel!!.bottom, 0.0001f)
        assertEquals("the balloon must survive alongside it", 0.25f, u.bounds!!.left, 0.0001f)
    }

    @Test fun `a null panel is carried through, not invented`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":null,"panel":null}
        ],"characters":[]}""")
        assertNull(reader().read(pageImage()).getOrThrow().units[0].panel)
    }

    @Test fun `a panel outside the image is rejected, not clamped`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":null,
             "panel":{"x1":0,"y1":0,"x2":999999,"y2":10}}
        ],"characters":[]}""")
        assertNull(reader().read(pageImage()).getOrThrow().units[0].panel)
    }

    /**
     * The "no visible boundary" case from the expansion research. A rectangle
     * covering the whole image, for a unit whose balloon occupies a fraction of it,
     * is the model declining to answer. Accepting it would render the entire page
     * for every line while looking like a working feature; rejecting it falls back
     * to the balloon crop, which is honest.
     */
    @Test fun `a whole-image panel is rejected when the unit has a balloon`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":10,"y1":10,"x2":100,"y2":100},
             "panel":{"x1":0,"y1":0,"x2":893,"y2":1372}}
        ],"characters":[]}""")
        assertNull(
            "a whole-image panel tells the reader nothing it did not already know",
            reader().read(pageImage()).getOrThrow().units[0].panel,
        )
    }

    /**
     * The counterpart. A genuine full-bleed splash page IS one whole-image panel,
     * and there is no balloon to contradict it, so it is kept. The distinction is
     * the balloon, not the rectangle.
     */
    @Test fun `a whole-image panel is kept when there is no balloon to contradict it`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Narrator","text":"THE END","bounds":null,
             "panel":{"x1":0,"y1":0,"x2":893,"y2":1372}}
        ],"characters":[]}""")
        assertNotNull(reader().read(pageImage()).getOrThrow().units[0].panel)
    }

    @Test fun `a panel that does not contain its own balloon is rejected`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":500,"y1":500,"x2":600,"y2":600},
             "panel":{"x1":0,"y1":0,"x2":200,"y2":200}}
        ],"characters":[]}""")
        assertNull(
            "a panel that excludes its balloon is not that balloon's panel",
            reader().read(pageImage()).getOrThrow().units[0].panel,
        )
    }

    @Test fun `the prompt asks for the panel in pixels`() {
        val i = pageInstruction(893, 1372)
        assertTrue(i.contains("panel", ignoreCase = true))
        assertTrue("must still ask for pixels", i.contains("pixel", ignoreCase = true))
    }
```

And in `SpeechUnitTest.kt`, the mapper guard. **This is the test that catches the
silent-drop defect** — without it, forgetting `panel` in `toSpeechUnits` leaves
every panel null with a green suite:

```kotlin
    @Test fun `toSpeechUnits carries the panel, not only the bounds`() {
        val panel = BoundingBox(0f, 0f, 1f, 0.5f)
        val units = listOf(
            ParsedUnit("Wolf", "HI", bounds = BoundingBox(0.1f, 0.1f, 0.2f, 0.2f), panel = panel),
        ).toSpeechUnits()
        assertEquals(panel, units[0].panel)
    }
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.data.page.PageReaderImplTest" --tests "com.storyteller.domain.model.SpeechUnitTest"`
Expected: FAIL — `panel` is not a parameter of `ParsedUnit` or a property of `SpeechUnit`.

- [ ] **Step 4: Thread the field through the domain**

`SpeechUnit.kt`. Add `val panel: BoundingBox? = null` to **both** `ParsedUnit:9`
and `SpeechUnit:11` — defaulted so no existing construction site breaks — and add
the line to `toSpeechUnits:29`:

```kotlin
                bounds = p.bounds,
                panel = p.panel,
```

Then `contains`, which Step 6 needs:

```kotlin
/**
 * Whether [other] lies inside this box, within a small tolerance.
 *
 * The tolerance exists because the model routinely reports a panel edge a pixel or
 * two inside the balloon that touches it, and rejecting a correct panel over one
 * pixel would discard the feature on exactly the units it works best on.
 */
fun BoundingBox.contains(other: BoundingBox, tolerance: Float = 0.01f): Boolean =
    other.left >= left - tolerance && other.top >= top - tolerance &&
        other.right <= right + tolerance && other.bottom <= bottom + tolerance
```

- [ ] **Step 5: Add the field to the schema and the prompt**

In `PAGE_SCHEMA`, give each unit a `panel` with the identical
`anyOf [object, null]` shape as `bounds`, `x1/y1/x2/y2` typed `"number"` (the
vendor's own worked example returns a fractional pixel), and add `"panel"` to the
unit's `required` array.

In `pageInstruction`, after the `bounds` bullet:

```kotlin
    - Set panel to the comic panel that contains that balloon, in the same
      $width x $height pixel coordinates. A panel is the framed picture the balloon
      sits in, bounded by the gutters or page edges around it. Include the artwork,
      not just the balloon. Two units in the same panel must get the same panel box.
      Use null if the page is a single full-bleed picture with no panel divisions,
      or if you cannot tell.
```

The "two units in the same panel must get the same panel box" sentence is
load-bearing. §18 found the model already does this unprompted, and it is the
property that makes consecutive lines render as one stable picture rather than a
subtly jittering crop.

- [ ] **Step 6: Parse and validate**

In `PageReaderImpl.kt`, add `val panel: BoundsDto? = null` to `UnitDto` — reusing
`BoundsDto`, since a panel is the same shape in the same space — and validate:

```kotlin
    /**
     * A panel is accepted only if it could be a panel for THIS unit.
     *
     * Two rejections beyond the shared geometry checks. A whole-image rectangle
     * alongside a real balloon is the model declining to answer, and rendering it
     * would show the entire page for every line while looking like the feature
     * working. A panel that excludes its own balloon is not that balloon's panel,
     * whatever else it is.
     */
    private fun BoundsDto.toPanel(width: Int, height: Int, balloon: BoundingBox?): BoundingBox? {
        val box = toDomain(width, height) ?: return null
        if (balloon == null) return box
        val coversImage = box.right - box.left > 0.98f && box.bottom - box.top > 0.98f
        if (coversImage) return null
        if (!box.contains(balloon)) return null
        return box
    }
```

Then in `PageDto.toDomain`, compute the balloon first and pass it in:

```kotlin
        units = units.map { u ->
            val bounds = u.bounds?.toDomain(width, height)
            ParsedUnit(
                speaker = u.speaker,
                text = u.text,
                bounds = bounds,
                panel = u.panel?.toPanel(width, height, bounds),
            )
        }.toSpeechUnits(),
```

- [ ] **Step 7: Run the whole suite, then commit**

```bash
./gradlew :app:testDebugUnitTest
git add app/src/main/kotlin/com/storyteller/data/ app/src/main/kotlin/com/storyteller/domain/model/SpeechUnit.kt \
        app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt \
        app/src/test/kotlin/com/storyteller/domain/model/SpeechUnitTest.kt
git commit -m "feat: ask the model which panel each speech unit sits in"
```

---

### Task 2: Render the panel

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderUiState.kt:38` — `Line` gains `panel`
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderViewModel.kt:224` — populate it
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/BubbleCrop.kt:44` — crop source
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderScreen.kt:240` — call site
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/BubbleCropTest.kt`

**Interfaces:**
- Consumes: `SpeechUnit.panel` (Task 1).
- Produces: `cropBubble(image, bounds, panel)` — the existing function gains a third parameter, defaulted to `null` so no call site is forced to change before it is ready.

- [ ] **Step 1: Write the failing tests**

`BubbleCropTest.kt` already builds a real JPEG via `PageImage(out.toByteArray(),
"image/jpeg")`; follow that existing helper rather than inventing a fixture.

```kotlin
    @Test fun `crops to the panel when one is present`() {
        val balloon = BoundingBox(0.40f, 0.40f, 0.50f, 0.50f)
        val panel = BoundingBox(0.00f, 0.25f, 1.00f, 0.60f)

        val panelCrop = cropBubble(page, balloon, panel)!!
        val balloonCrop = cropBubble(page, balloon, null)!!

        // The panel is a wide band; the balloon is a small square inside it. Any
        // implementation that quietly kept cropping the balloon fails here.
        assertTrue(
            "panel crop ${panelCrop.width}x${panelCrop.height} should be wider than " +
                "balloon crop ${balloonCrop.width}x${balloonCrop.height}",
            panelCrop.width > balloonCrop.width,
        )
    }

    @Test fun `falls back to the balloon when no panel was returned`() {
        assertNotNull(cropBubble(page, BoundingBox(0.40f, 0.40f, 0.50f, 0.50f), null))
    }

    @Test fun `falls back to text when neither is present`() {
        assertNull(cropBubble(page, null, null))
    }

    /**
     * A panel is already the framed picture. Padding it pulls in the neighbouring
     * panel's art, which is the exact failure the expansion research warns about,
     * so the panel path passes padFraction = 0 where the balloon path pads.
     */
    @Test fun `a panel crop is not padded outward`() {
        val panel = BoundingBox(0.25f, 0.25f, 0.75f, 0.75f)
        val crop = cropBubble(page, null, panel)!!
        val decoder = BitmapRegionDecoder.newInstance(page.displayBytes, 0, page.displayBytes.size, false)!!
        val expected = cropRect(panel, decoder.width, decoder.height, padFraction = 0f)!!
        decoder.recycle()
        assertEquals(expected.width / crop.width, expected.height / crop.height)
        assertTrue("an unpadded panel crop cannot exceed the panel's own width",
            crop.width <= expected.width)
    }
```

- [ ] **Step 2: Run them to make sure they fail**

Expected: FAIL — `cropBubble` takes two parameters.

- [ ] **Step 3: Add the panel path to `cropBubble`**

Keep the function's name, its `BitmapRegionDecoder` strategy, its recycling, and
its catch-all null return — all of that is load-bearing and none of it changes.
Add the third parameter and choose the source:

```kotlin
fun cropBubble(image: PageImage, bounds: BoundingBox?, panel: BoundingBox? = null): Bitmap? {
    // The panel is the picture the line was spoken in; the balloon is a crop of
    // lettering a child cannot read. Prefer the panel, keep the balloon as the
    // fallback, and let both be absent -- the reader's text-only path is the
    // honest outcome when the model located nothing.
    val source = panel ?: bounds ?: return null
    val pad = if (panel != null) 0f else BUBBLE_PAD
    ...
    val rect = cropRect(source, decoder.width, decoder.height, padFraction = pad) ?: return null
```

Update the KDoc: it currently says "The bubble [bounds] encloses". It now returns
the panel when there is one.

- [ ] **Step 4: Thread it to the call site**

`ReaderUiState.kt:38` — add to `Line`:

```kotlin
        /** The comic panel this line was spoken in, or null when none was resolved. */
        val panel: BoundingBox? = null,
```

`ReaderViewModel.kt:224` — pass `panel = unit.panel` where the `Line` is built.
`ReaderScreen.kt:240` — `cropBubble(pageImage, line.bounds, line.panel)`.

Also widen `produceState`'s key list on line 239 from `(null, line.index, image)`:
the crop must be recomputed when the panel changes, and `line.index` alone will not
notice that.

- [ ] **Step 5: Run the whole suite, then commit**

```bash
./gradlew :app:testDebugUnitTest
git commit -m "feat: render the panel a line was spoken in, not a crop of its lettering"
```

---

### Task 3: Measure the panels against hand-labelled ground truth

§18.4 states plainly that the panel result is **unmeasured** — it rests on
structural consistency and visual inspection, which is the same standard this
investigation has criticised elsewhere. There is no OCR-derived ground truth for
panels and there cannot be, so label a page by hand, once.

**Files:**
- Create: `scripts/fixtures/panels-page-1788294930134.json`
- Modify: `scripts/measure_boxes.py` — a `--panels` mode
- Modify: `docs/issues/2026-08-31-bubble-box-accuracy-measured.md`

- [ ] **Step 1: Hand-label the dense page**

`page-1788294930134`, chosen because OCR read **zero** words on it: no other method
can produce ground truth there, and it is where the model's panels most need
independent checking. Record each panel's rectangle in upload pixel coordinates
with a `note` naming what is in it, so a later reader can check the labels rather
than trust them:

```json
{
  "bundle": "page-1788294930134",
  "uploadWidth": 840, "uploadHeight": 1411,
  "panels": [
    {"x1": 0, "y1": 0, "x2": 840, "y2": 322, "note": "Rabbit, HE DID IT!!!"}
  ]
}
```

- [ ] **Step 2: Score the model's panels against the labels**

Per unit: IoU between the model's panel and the labelled panel containing its
balloon; whether the unit was assigned to the correct labelled panel; and whether
units sharing a labelled panel received identical model panels.

**Stop condition: mean IoU >= 0.7 AND every unit in the correct panel.** Higher
than the 0.5 used for balloons, deliberately — a panel is a large high-contrast
rectangle, and a mechanism that cannot locate one accurately is not worth the field.

- [ ] **Step 3: Record the result and decide whether Task 4 runs**

- **Passes with tight edges** — done. Do not build pixel refinement; close it out and say so in the research document.
- **Passes but edges are consistently loose or tight** — Task 4 is warranted.
- **Fails** — §18.4's caveat was the operative finding and §18.2's visual read was wrong. Stop and say so.

```bash
git commit -m "test: measure the model's panels against hand-labelled ground truth"
```

---

### Task 4 (GATED): Snap the panel edges with pixel evidence

**Do not start unless Task 3 Step 3 selected the middle outcome.** This implements
the expansion research's algorithm, seeded from the model's panel rather than OCR.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/domain/geometry/PanelSnap.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/geometry/PanelSnapTest.kt`

- [ ] **Step 1: Build the synthetic test set first**

The research document's best idea, and it comes before the algorithm. Generate
bitmaps in the test itself — no fixtures, no copyrighted pages:

1. white balloon, black outline, inside a bordered panel
2. white balloon touching a white page margin — the connected-region trap
3. coloured balloon, no outline
4. black gutter between two panels
5. panel art with heavy dark character outlines — the false-border trap
6. uneven lighting across the panel — the photographed-page trap
7. full-bleed art, no border at all — **must return null**

Each case asserts the accept/reject decision, not a pixel-exact rectangle. Case 7
matters most: the correct output is nothing.

- [ ] **Step 2: Implement bounded edge search**

Per the research: search each of the four directions independently for a sustained
dark run, a low-texture gutter, or a persistent luminance step, requiring evidence
across a run of neighbouring pixels rather than one dark pixel. Cap the search
distance as a fraction of the seed panel. Accept an edge only on strong evidence;
keep the model's edge otherwise.

Pure Kotlin over an `IntArray` of pixels, in `domain`, JVM-testable, no new
dependency. **No OpenCV** — per the research, and because a bitmap prototype has
not yet been shown insufficient.

- [ ] **Step 3: Gate on the same measurement**

Re-run Task 3's scoring with snapping on. **If mean IoU does not improve, delete
it** and record that the model's panels were already as good as pixel evidence
could make them.

---

## Self-Review

**Spec coverage.** §18.3's recommendation is Tasks 1-2; §18.4's missing evidence is
Task 3; the research document's algorithm is Task 4, gated. §17's refutation of the
OCR split is honoured by building nothing on `TranscriptOcrLocalizer`.

**Placeholder scan.** No TBD/TODO. Task 4 is specified but unstarted, with an
explicit entry condition.

**Type consistency, checked against the source this time.** `BoundingBox(left, top,
right, bottom)` at `SpeechUnit.kt:6`; `ParsedUnit:9` and `SpeechUnit:11` both gain
`panel`; `toSpeechUnits:29` carries it; `ReaderUiState.Line:38` gains it;
`ReaderViewModel.kt:224` populates it; `cropBubble` at `BubbleCrop.kt:44` consumes
it via `cropRect(bounds, w, h, padFraction, minEdgeFraction)` at
`CropGeometry.kt:18`. Every one of these was read before being written down.

**Three risks worth naming.**

Tasks 1-2 ship a user-visible change before Task 3 measures it. Deliberate — the
crop is trivially reversible and the fallback chain means the worst case is today's
behaviour — but Task 3 must not be skipped once the feature looks right on screen.
"Looks right" is precisely what §18.4 warns is insufficient, and it is the standard
this investigation has already been burned by twice.

The whole-image rejection could suppress a legitimate full-bleed splash page that
happens to carry a balloon. That is the intended trade: for such a page the balloon
crop is the better fallback anyway, and a rule that renders the whole page for
every line would be indistinguishable from the feature working.

`TranscriptOcrLocalizer` and the `PageLocalizer` seam stay in the tree, wired to
`NoOpPageLocalizer` and fed by nothing. This plan does not remove them, but nothing
here should be built on them, and if Task 3 passes they should be deleted in a
follow-up rather than left as an attractive nuisance.
