# Storyteller Iteration 5 — Render the panel, not the balloon

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a child the picture that goes with the line being read aloud. The reader currently crops the speech balloon, which shows them the lettering they cannot read; it should show the comic panel.

**Architecture:** The panel comes from the model, in the field it already fills for the transcript — one more property per unit, no extra request and no extra image. `BoundingBox` gains a sibling on the domain unit, the reader crops to it, and the balloon box is kept for optional highlighting rather than discarded. Pixel-based refinement of the panel's edges is specified but **gated on measurement**, not built speculatively.

**Tech Stack:** Kotlin, Android (BitmapFactory), Retrofit + kotlinx.serialization, Robolectric, JUnit 4, Python 3 for the measurement harness.

**Spec / evidence base:**
- [`docs/issues/2026-08-31-bubble-box-accuracy-measured.md`](../../issues/2026-08-31-bubble-box-accuracy-measured.md) §18 — the panel result this plan implements, and §17 for what it replaces.
- [`docs/issues/2026-09-02-panel-edge-expansion-research.md`](../../issues/2026-09-02-panel-edge-expansion-research.md) — the pixel-expansion research. Adjudicated below.

## Global Constraints

- `GraphTest` enforces `ui -> domain <- data`, `ui` never imports `data`, and `domain` never imports `android.*`/`androidx.*`.
- Keep reject-don't-clamp, at every level: a panel that fails validation becomes `null` and the reader falls back to the balloon crop, then to text. **Never manufacture a plausible-looking rectangle** — the research doc is right that this is the cardinal sin here, and it is the same principle that already governs `BoundsDto.toDomain`.
- Keep `transformations: {"oversized_image": "error"}` and the upload budget of `PAGE_UPLOAD_VISUAL_TOKENS`. Panel coordinates arrive in the same pixel space as bubble coordinates and are normalised the same way, by the encoded dimensions and never the padded ones.
- Commits use the repo's existing author, `mitenko`. **No `Co-Authored-By` trailer of any kind.**
- Gradle: `BindException` → `./gradlew --stop`, retry. `Unable to delete directory .../test-results/...` → `./gradlew --stop`, delete `app/build/test-results` and `app/build/reports`, re-run. Neither is a code failure.

---

## Adjudication of the panel-edge-expansion research

The research is sound on technique and its risk analysis is better than mine. Its
central premise is nonetheless wrong, and the disagreement is settled by
measurement rather than argument.

### Accepted, and it changes the plan

| finding | why it matters here |
|---|---|
| **§8's OpenCV history: "the useful contours tended to be comic panels rather than speech balloons"** | The most valuable line in the document. That experiment was recorded as a *failure* — it failed to find balloons. Re-read against §18 it is independent corroboration from a completely different method: pixel analysis keeps finding panels because panels are what is actually salient on the page. Two methods failing at balloons and succeeding at panels is a much stronger signal than either alone. |
| **"No visible boundary" — borderless panels, full-bleed art, balloons overlapping panel edges** | A real risk for the model-provided panel too, not only for pixel expansion. `page-1788295071078` is plausibly exactly this: X-Y cut returned the whole page for it. Task 1 therefore validates the panel and returns `null` rather than accepting a whole-page rectangle. |
| **The synthetic test set** | The best idea in the document. White balloon with black outline; white balloon connected to the page margin; coloured/borderless balloon; black gutter; dark character outlines; uneven lighting. It makes accept/reject logic unit-testable without a single copyrighted page, which nothing else in this investigation has managed. Adopted in Task 4. |
| **"A strict axis-aligned rectangle will fail when the page or panel is slanted"** | Mitigated but not eliminated: ML Kit Document Scanner rectifies the page before upload, which is why the measured `dx`/`dy` are near zero. Worth stating so nobody removes the scanner believing rectification is incidental. |
| **No OpenCV until a bitmap prototype justifies it** | Correct and adopted. An APK-size and native-integration cost taken before the cheap version has been shown insufficient is a cost taken on faith. |
| **"If it cannot beat the current crop on representative pages, do not ship it"** | Adopted verbatim as Task 4's gate, with the baseline changed to the model's panel. |

### Rejected: OCR as the seed

The document's algorithm begins "Use the union of the OCR word boxes matched to
one speech unit" and adds that the model's box "should not override a
high-confidence OCR placement." Measured on the same pages this app photographs:

| page | OCR words read |
|---|---|
| `page-1788294930134` (dense) | **0** |
| `page-1788295071078` (sparse) | 12, heavily corrupted (`'cquNq!.'`, `'youQE'`, `'EXETLY'`) |
| `page-1788289251857` (best case) | 50, about 60% coverage |

A seed that does not exist on two of three real pages cannot be the primary
input. The same document's own §8 citation explains why this was not obvious:
OCR-based localisation had never been run on a real page, because —

**`OcrWord` has no producer.** `TranscriptOcrLocalizer` was committed in a58ace6
with a unit test that constructs `OcrWord` by hand, and nothing in `app/src/main`
constructs one. ML Kit Text Recognition is absent from `gradle/libs.versions.toml`
(only `mlkit-document-scanner` is present). So the OCR path is not merely
unreliable — it is unbuilt, and building it is a prerequisite the research does
not cost.

**The seed is the model's own box instead.** It is measured, present on every unit
of every page, well-centred (dx +0.014, dy -0.008), and — per §18 — the model
resolves panels far more reliably than balloons. The research's expansion
algorithm survives this substitution intact; only its starting point changes.

### Deferred, not rejected

The pixel-expansion algorithm itself. §18 shows the model's panels tile the page
and contain their balloons, so the question is not whether panels can be found but
whether their **edges** need tightening. That is Task 3's job to answer. Building a
region-grower before knowing the answer would be the same mistake as Stage B's
resolution increase: an expensive fix aimed at an unmeasured problem.

---

### Task 1: Ask for the panel, and validate it

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageSchema.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageReaderImpl.kt`
- Modify: `app/src/main/kotlin/com/storyteller/domain/model/` — whichever file declares `ParsedUnit` and `SpeechUnit`
- Modify: `app/src/main/kotlin/com/storyteller/data/local/Entities.kt:7`
- Test: `app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt`

**Interfaces:**
- Consumes: `BoundingBox`, and the existing pixel-normalisation path.
- Produces: `ParsedUnit.panel: BoundingBox?` and `SpeechUnit.panel: BoundingBox?`; `PARSE_VERSION = 7`.

- [ ] **Step 1: Bump the parse version FIRST**

`Entities.kt:7`: `const val PARSE_VERSION = 7`. Cached v6 rows have no panel field,
so without the bump every re-read returns a panel-less parse and the feature
measures nothing.

- [ ] **Step 2: Write the failing tests**

```kotlin
    @Test fun `a panel is normalised against the uploaded image like bounds are`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI",
             "bounds":{"x1":223.25,"y1":343,"x2":669.75,"y2":1029},
             "panel":{"x1":0,"y1":0,"x2":893,"y2":1372}}
        ],"characters":[]}""")

        val u = reader().read(pageImage()).getOrThrow().units[0]
        assertEquals(0f, u.panel!!.left, 0.0001f)
        assertEquals(1f, u.panel!!.right, 0.0001f)
        assertEquals(0.25f, u.bounds!!.left, 0.0001f)
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
     * The "no visible boundary" case from the expansion research: a full-bleed or
     * borderless page has no defensible panel, and a rectangle covering the whole
     * image is the model saying so without saying so. Rejecting it makes the reader
     * fall back to the balloon crop, which is an honest answer; accepting it would
     * render the entire page for every line and look like a feature working.
     */
    @Test fun `a whole-page panel is rejected as no panel at all`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":10,"y1":10,"x2":100,"y2":100},
             "panel":{"x1":0,"y1":0,"x2":893,"y2":1372}}
        ],"characters":[]}""")
        // Same coordinates as the first test, which asserts they PARSE; here the
        // unit has a balloon, so a whole-page panel is a degenerate answer.
        assertNull(
            "a panel covering the whole image tells the reader nothing",
            reader().read(pageImage()).getOrThrow().units[0].panel,
        )
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

    @Test fun `the prompt asks for the panel in pixels and says what a panel is`() {
        val i = pageInstruction(893, 1372)
        assertTrue(i.contains("panel", ignoreCase = true))
        assertTrue("must still ask for pixels", i.contains("pixel", ignoreCase = true))
    }
```

Note the deliberate tension between test 1 and test 4: the same whole-image panel
parses when the unit has no balloon to contradict it, and is rejected when it does.
That is intended — "the whole page" is a legitimate answer for a full-bleed splash
and a degenerate one for a unit whose balloon occupies 1% of it. Encode exactly
that rule and no broader one.

- [ ] **Step 3: Run them to make sure they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.data.page.PageReaderImplTest"`
Expected: FAIL — `panel` is not a property of `SpeechUnit`.

- [ ] **Step 4: Add the field to the schema and the prompt**

In `PAGE_SCHEMA`, give each unit a `panel` with the identical `anyOf [object,
null]` shape as `bounds`, with `x1/y1/x2/y2` as `"number"` (the vendor's own
worked example returns a fractional pixel), and add `"panel"` to the unit's
`required` list.

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
load-bearing: §18 found the model already does this unprompted, and it is the
property that makes consecutive lines render as one stable picture instead of a
subtly jittering crop.

- [ ] **Step 5: Parse and validate**

`BoundsDto` is reused for the panel — same shape, same normalisation. Add
`val panel: BoundsDto? = null` to `UnitDto`, carry `panel` through
`ParsedUnit` and `SpeechUnit`, and validate:

```kotlin
    /**
     * A panel is accepted only if it could actually be a panel for this unit.
     *
     * The expansion research's "no visible boundary" case is the reason for the
     * whole-image rule: a full-bleed page has no defensible panel, and a rectangle
     * covering everything is the model declining to answer. Rendering it would show
     * the entire page for every line while looking like a working feature.
     */
    private fun BoundsDto.toPanel(width: Int, height: Int, balloon: BoundingBox?): BoundingBox? {
        val box = toDomain(width, height) ?: return null
        val coversPage = box.right - box.left > 0.98f && box.bottom - box.top > 0.98f
        if (coversPage && balloon != null) return null
        if (balloon != null && !box.contains(balloon)) return null
        return box
    }
```

`contains` belongs on `BoundingBox` in `domain`, with a small tolerance for the
model reporting a panel edge a pixel or two inside its own balloon.

- [ ] **Step 6: Run the whole suite, then commit**

```bash
./gradlew :app:testDebugUnitTest
git add app/src/main/kotlin/com/storyteller/data/ app/src/main/kotlin/com/storyteller/domain/model/ \
        app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt
git commit -m "feat: ask the model which panel each speech unit sits in"
```

---

### Task 2: Render the panel

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/BubbleCrop.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderUiState.kt`, `ReaderViewModel.kt`, `ReaderScreen.kt` as the crop source requires
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/BubbleCropTest.kt`

**Interfaces:**
- Consumes: `SpeechUnit.panel` (Task 1).
- Produces: no new public signature; the crop source becomes panel-then-balloon-then-text.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test fun `crops to the panel when one is present`() {
        val unit = speechUnit(0).copy(
            bounds = BoundingBox(0.40f, 0.40f, 0.50f, 0.50f),
            panel = BoundingBox(0.00f, 0.25f, 1.00f, 0.60f),
        )
        val crop = cropFor(page, unit)!!
        // The panel, not the balloon: a panel crop is materially wider than the
        // balloon it contains, which is the entire point of the change.
        assertTrue(crop.width > crop.height)
    }

    @Test fun `falls back to the balloon when no panel was returned`() {
        val unit = speechUnit(0).copy(
            bounds = BoundingBox(0.40f, 0.40f, 0.50f, 0.50f), panel = null,
        )
        assertNotNull(cropFor(page, unit))
    }

    @Test fun `falls back to text when neither is present`() {
        assertNull(cropFor(page, speechUnit(0).copy(bounds = null, panel = null)))
    }
```

- [ ] **Step 2: Run it, then implement the three-level fallback**

Panel, else balloon, else null (the reader's existing text-only path). Keep
`CropGeometry`'s margin logic for the balloon case; a panel needs **no added
margin** — it is already the framed picture, and padding it would pull in the
neighbouring panel's art, which is the exact failure the research warns about.

- [ ] **Step 3: Run the whole suite, then commit**

```bash
git commit -m "feat: render the panel a line was spoken in, not a crop of its lettering"
```

---

### Task 3: Measure the panel against hand-labelled ground truth

§18.4 is explicit that the panel result is **unmeasured**: it rests on structural
consistency and visual inspection. There is no OCR-derived ground truth for panels
and there cannot be. So label one page by hand, once.

**Files:**
- Create: `scripts/fixtures/panels-page-1788294930134.json`
- Modify: `scripts/measure_boxes.py` — a `--panels` mode
- Modify: `docs/issues/2026-08-31-bubble-box-accuracy-measured.md`

- [ ] **Step 1: Hand-label the dense page**

`page-1788294930134`, chosen because OCR read zero words on it — no other method
can produce ground truth here, and it is where the model's panels most need
checking. Record each panel's rectangle in upload pixel coordinates, by reading
them off the image, with a comment naming what is in each panel so a later reader
can check the labels rather than trust them.

- [ ] **Step 2: Score the model's panels against the labels**

Per unit: IoU between the model's panel and the hand-labelled panel containing its
balloon, plus containment of the balloon within the panel. Report:

- mean IoU across units
- how many units were assigned to the correct labelled panel
- whether units sharing a labelled panel received identical model panels

**Stop condition: mean IoU ≥ 0.7 and every unit in the correct panel.** Higher than
the 0.5 used for balloons, deliberately: a panel is a large high-contrast rectangle
and a mechanism that cannot locate one accurately is not worth the field.

- [ ] **Step 3: Record the result, and decide whether Task 4 is needed**

- **Passes, edges tight** — the feature is done. Do not build pixel refinement; close it out and say so in the research document.
- **Passes, but edges consistently loose or tight by a margin** — Task 4 is warranted as an edge-snapping refinement.
- **Fails** — the panels are not what §18's visual inspection suggested. Stop, and re-read §18.4's caveat as the operative finding rather than the footnote.

```bash
git commit -m "test: measure the model's panels against hand-labelled ground truth"
```

---

### Task 4 (GATED on Task 3): Snap the panel edges with pixel evidence

**Do not start this task unless Task 3 Step 3 selected the middle outcome.** It
implements the research document's algorithm, seeded from the model's panel rather
than from OCR.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/domain/geometry/PanelSnap.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/geometry/PanelSnapTest.kt`

- [ ] **Step 1: Build the synthetic test set first**

The research document's best idea, and it comes before any algorithm. Generate
bitmaps in the test itself — no fixture files, no copyrighted pages:

1. white balloon, black outline, inside a bordered panel
2. white balloon touching a white page margin (the connected-region trap)
3. coloured balloon, no outline
4. black gutter between two panels
5. panel art containing heavy dark character outlines (the false-border trap)
6. uneven lighting across the panel (the photographed-page trap)
7. full-bleed art with no panel border at all — **must return null**

Each case asserts the accept/reject decision, not a pixel-exact rectangle. Case 7
is the one that matters most: the correct output is nothing.

- [ ] **Step 2: Implement bounded edge search**

Per the research: search each of the four directions independently for boundary
evidence — a sustained dark run, a low-texture gutter, or a persistent luminance
step — requiring evidence across a run of neighbouring pixels rather than a single
dark pixel. Cap the search distance as a fraction of the seed panel. Accept an edge
only when the evidence is strong on that side; keep the model's edge otherwise.

Pure Kotlin on an `IntArray` of pixels, in `domain`, so it is JVM-testable and adds
no dependency. **No OpenCV** — per the research, and per the plain fact that a
bitmap prototype has not yet been shown insufficient.

- [ ] **Step 3: Gate on the same measurement**

Re-run Task 3's scoring with snapping enabled. **If mean IoU does not improve, do
not ship it** — delete it, and record that the model's panels were already as good
as pixel evidence could make them.

---

## Self-Review

**Spec coverage.** §18.3's recommendation is Tasks 1-2; §18.4's missing evidence is
Task 3; the research document's algorithm is Task 4, gated. §17's refutation of the
OCR split is honoured by not building on `TranscriptOcrLocalizer`.

**Placeholder scan.** No TBD/TODO. Task 4 is deliberately specified but unstarted,
with an explicit entry condition rather than an implicit one.

**Type consistency.** `panel: BoundingBox?` is produced in Task 1 on both
`ParsedUnit` and `SpeechUnit`, consumed in Task 2, scored in Task 3, refined in
Task 4. `BoundingBox.contains` is added in Task 1 and reused in Task 4's rejection
logic.

**Three risks worth naming.**

The panel field is unmeasured until Task 3, and Tasks 1-2 ship a user-visible
change before it. That ordering is deliberate — the crop is trivially reversible
and the fallback chain means the worst case is today's behaviour — but it does mean
Task 3 must not be skipped once the feature looks right on screen. Looking right is
what §18.4 already warns is insufficient.

The whole-page rejection rule in Task 1 Step 5 could suppress a legitimate
full-bleed splash page's panel. That is the intended trade: for a splash page the
balloon crop is the better fallback anyway, and a rule that renders the entire page
for every line would be indistinguishable from the feature working.

`TranscriptOcrLocalizer` and its `PageLocalizer` seam remain in the tree, wired to
`NoOpPageLocalizer` and fed by nothing. This plan does not remove them, but nothing
here should be built on them, and if Task 3 passes they should be deleted in a
follow-up rather than left as an attractive nuisance.
