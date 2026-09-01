# Storyteller Iteration 4 — Stage B: give the model more pixels to localise from

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the vision call to a high-resolution-tier model, so the page reaches it at 1593x2324 instead of 902x1316, and find out whether the per-balloon scatter left over from Stage A is a resolution problem. Fix the ground truth first, because at present it cannot measure the answer.

**Architecture:** Two changes and one prerequisite. The prerequisite is in the measurement harness, not the app: replace the gap-clustered OCR ground truth with one aligned to the model's own transcript, removing the parameter that currently swings mean IoU by 9x. In the app, the model's identity and its resolution tier become a single `domain` value that both `ui/capture` (which sizes the upload) and `data/page` (which names the model) read, so the two cannot drift apart.

**Tech Stack:** Kotlin, Android, Retrofit + kotlinx.serialization, Robolectric, JUnit 4, Python 3 for the harness.

**Spec / evidence base:** [`docs/issues/2026-08-31-bubble-box-accuracy-measured.md`](../../issues/2026-08-31-bubble-box-accuracy-measured.md), sections 12 and 13. Stage B exists because of §13.5; Task 1 exists because of §13.3.

## Global Constraints

- High-resolution tier is **2576 px max edge, 4784 max visual tokens**, and covers "Claude 4.7 and later models". It is automatic on those models: **no beta header and no client-side opt-in**. Standard tier (1568/1568) is every other model, Haiku 4.5 included.
- `GraphTest` enforces two rules that together decide this plan's shape: **`ui` must never import `data`**, and **`domain` must never import `android.*` or `androidx.*`**. The tier is needed in `ui`, the model id in `data`; the only legal shared home is `domain`.
- Keep `transformations: {"oversized_image": "error"}`. It is the only thing that turns a tier/limit mismatch into a visible 400 instead of silently displaced coordinates, and this iteration changes exactly those limits.
- Keep reject-don't-clamp, and keep the loud `require` on missing image dimensions.
- `minSdk 26`, `compileSdk 36`, `targetSdk 36`. Robolectric pinned at `sdk=34` — do not bump. `buildToolsVersion` stays unset.
- Commits use the repo's existing author, `mitenko`. **No `Co-Authored-By` trailer of any kind.**
- Gradle: `BindException` → `./gradlew --stop`, retry. `Unable to delete directory .../test-results/...` → `./gradlew --stop`, delete `app/build/test-results` and `app/build/reports`, re-run. Neither is a code failure.

## What this costs, stated before it is spent

Per page, image tokens only, on the capture measured in §13:

| | tokens | documented price | image cost / page |
|---|---|---|---|
| Haiku 4.5, standard tier (today) | 1551 | $1 / M input | ~$0.0016 |
| a 4.7+ model, high-resolution tier | 4731 | Opus 5 is $5 / M input | ~$0.024 at Opus 5 prices |

That is roughly **3x the tokens**, and up to **15x the cost** if the model chosen is
Opus 5. Check the intended model's actual price at <https://claude.com/pricing>
before adopting: the vision documentation quotes only Haiku 4.5 and Opus 5, so any
figure for Sonnet 5 here would be invented.

This is a child reading a picture book, one call per page. A 20-page sitting moves
from fractions of a cent to tens of cents. That is affordable for measurement and
worth a deliberate decision before it becomes the default.

**Recommended model to measure with: Sonnet 5 (`claude-sonnet-5`)** — on the
high-resolution tier, materially cheaper than Opus 5, and strong at vision. Task 2
makes the choice a one-constant change, so measuring Opus 5 afterwards costs one
edit.

---

### Task 1: Ground truth from the transcript, not from a clustering gap

§13.3 is the blocker: on the Stage A bundle, mean IoU reads 0.013 at a clustering
gap of 0.035 and 0.118 at 0.050, because the small gap splits one balloon's lines
while the larger one merges two neighbouring balloons. A 9x spread cannot resolve
whether Stage B helped.

The fix removes the gap from the ground-truth path entirely. The model is a good
transcriber and a weak localiser — that is the finding this whole issue rests on —
so its *text* can be trusted to say which words belong to which unit, even when its
*boxes* cannot. Align the OCR word sequence to the model's transcript, and each
unit's extent becomes the bounding box of the words aligned to it. No proximity, no
threshold.

**Files:**
- Modify: `scripts/measure_boxes.py`
- Modify: `scripts/fixtures/box-measurements.txt` (re-baselined)
- Modify: `docs/issues/2026-08-31-bubble-box-accuracy-measured.md` (a section recording the re-baseline)

- [ ] **Step 1: Add transcript alignment alongside the existing clustering**

Add, do not yet replace — both methods must run on the same bundles so the change
is auditable.

```python
def transcript_extents(words, units):
    """Each unit's text extent, from aligning OCR words to the model's transcript.

    The model transcribes accurately and localises poorly (see the issue document),
    so its text is usable as ground truth for WHICH words belong to a unit even
    though its boxes are not usable for where they are. Aligning the two token
    sequences assigns every OCR word to at most one unit, with no distance
    threshold anywhere -- which is the point, because the threshold was worth a 9x
    swing in the result.

    Returns {unit_index: (x1, y1, x2, y2)} for units that got any words.
    """
    ocr_tokens = [alpha(w["text"]) for w in words]
    unit_tokens, owner = [], []
    for u in units:
        for tok in u["text"].split():
            t = alpha(tok)
            if t:
                unit_tokens.append(t)
                owner.append(u["i"])

    assigned = {}
    matcher = difflib.SequenceMatcher(None, ocr_tokens, unit_tokens, autojunk=False)
    for oi, ui, size in matcher.get_matching_blocks():
        for k in range(size):
            w = words[oi + k]
            idx = owner[ui + k]
            box = (w["x"], w["y"], w["x"] + w["w"], w["y"] + w["h"])
            cur = assigned.get(idx)
            assigned[idx] = box if cur is None else (
                min(cur[0], box[0]), min(cur[1], box[1]),
                max(cur[2], box[2]), max(cur[3], box[3]))
    return assigned
```

- [ ] **Step 2: Report both, side by side, on all three bundles**

Print a block that gives, per unit, the clustered extent and the transcript
extent with the IoU each produces, then the two mean IoUs. Run:

```bash
python scripts/measure_boxes.py diagnostics-pulled/page-1788205074358 \
    diagnostics-pulled/page-1788215961215 diagnostics-pulled/page-1788278845946
```

- [ ] **Step 3: Confirm the gap sensitivity is gone**

The transcript extents must be **identical at every value in `SENSITIVITY_GAPS`**,
because no gap enters their computation. Assert that in the tool: compute them at
two gaps and fail loudly if they differ. If they do differ, the alignment is
reading clustering state from somewhere and the whole exercise is void.

Also print, per unit, how many OCR words were aligned to it. A unit with one
aligned word is the old `"TO"` failure wearing a new hat; report those and exclude
them with the existing `MIN_CLUSTER_ALPHA` reasoning applied to the aligned text.

- [ ] **Step 4: Switch the reported figures to the transcript extents**

Once Step 2's comparison has been read and Step 3 passes, make transcript extents
the primary ground truth. Keep the clustered numbers in the output as a secondary
line so the three bundles stay comparable with everything already published.

- [ ] **Step 5: Re-baseline and record**

```bash
python scripts/measure_boxes.py diagnostics-pulled/page-1788205074358 \
    diagnostics-pulled/page-1788215961215 diagnostics-pulled/page-1788278845946 \
    --out scripts/fixtures/box-measurements.txt
```

Add a section to the issue document giving the three bundles' IoU under both
methods. **If the transcript method changes the ranking of the three bundles,
say so plainly** — it would mean conclusions already drawn from the clustered
numbers, including §13.2's, need revisiting.

- [ ] **Step 6: Commit**

```bash
git add scripts/measure_boxes.py scripts/fixtures/box-measurements.txt \
        docs/issues/2026-08-31-bubble-box-accuracy-measured.md
git commit -m "fix: take the measurement's ground truth from the transcript, not a distance threshold"
```

---

### Task 2: One place that knows which model, and therefore which tier

The resolution tier is a property of the model. Today the model name lives in
`data/page/PageReaderImpl.kt:27` and the resize limits are defaulted in
`ui/capture/Downscale.kt`, and `ui` may not import `data`. Left as is, Stage B
would require editing two files in two layers that cannot reference each other,
with nothing but a 400 at runtime to catch a mismatch.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/domain/model/VisionModel.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/model/VisionModelTest.kt`

**Interfaces:**
- Consumes: `STANDARD_MAX_EDGE`, `STANDARD_MAX_VISUAL_TOKENS`, `ImageSize`, `modelVisibleSize` from `domain/geometry/ModelImageSize.kt`.
- Produces: `enum class ResolutionTier(val maxEdge: Int, val maxTokens: Int)`; `data class VisionModel(val id: String, val tier: ResolutionTier)`; `val PAGE_VISION_MODEL: VisionModel`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storyteller.domain.model

import com.storyteller.domain.geometry.ImageSize
import com.storyteller.domain.geometry.modelVisibleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModelTest {

    @Test fun `the documented tier limits`() {
        assertEquals(1568, ResolutionTier.STANDARD.maxEdge)
        assertEquals(1568, ResolutionTier.STANDARD.maxTokens)
        assertEquals(2576, ResolutionTier.HIGH_RESOLUTION.maxEdge)
        assertEquals(4784, ResolutionTier.HIGH_RESOLUTION.maxTokens)
    }

    /**
     * The whole point of this type. If the tier could be set independently of the
     * model, Stage B would be two edits in two layers that cannot see each other,
     * and getting one of them wrong produces displaced coordinates rather than a
     * compile error.
     */
    @Test fun `the page model carries its own tier`() {
        assertTrue(PAGE_VISION_MODEL.id.isNotBlank())
        assertTrue(
            "a 4.7-or-later model must be on the high-resolution tier",
            PAGE_VISION_MODEL.tier == ResolutionTier.HIGH_RESOLUTION,
        )
    }

    @Test fun `the scanner page reaches a high resolution model far larger`() {
        val page = ImageSize(2532, 3695)
        val standard = modelVisibleSize(page.width, page.height,
            ResolutionTier.STANDARD.maxEdge, ResolutionTier.STANDARD.maxTokens)
        val high = modelVisibleSize(page.width, page.height,
            ResolutionTier.HIGH_RESOLUTION.maxEdge, ResolutionTier.HIGH_RESOLUTION.maxTokens)

        assertEquals(ImageSize(902, 1316), standard)
        assertEquals(ImageSize(1593, 2324), high)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.domain.model.VisionModelTest"`
Expected: FAIL — `Unresolved reference: ResolutionTier`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.storyteller.domain.model

import com.storyteller.domain.geometry.STANDARD_MAX_EDGE
import com.storyteller.domain.geometry.STANDARD_MAX_VISUAL_TOKENS

/**
 * How large an image a model will look at before resizing it.
 *
 * High resolution is automatic on the models that have it -- no beta header, no
 * opt-in -- so this is not a request, it is a fact about the model that the
 * upload has to be sized against.
 * https://platform.claude.com/docs/en/build-with-claude/vision#evaluate-image-size
 */
enum class ResolutionTier(val maxEdge: Int, val maxTokens: Int) {
    STANDARD(STANDARD_MAX_EDGE, STANDARD_MAX_VISUAL_TOKENS),
    HIGH_RESOLUTION(2576, 4784),
}

/**
 * The model the page is read with, and the tier that follows from it.
 *
 * These travel together because they are needed in layers that cannot import each
 * other: `data/page` sends [id], `ui/capture` sizes the upload from [tier], and
 * GraphTest forbids `ui` from importing `data`. Split across the two layers they
 * would drift, and the symptom of drift is not a crash but every bounding box
 * landing somewhere else on the page.
 */
data class VisionModel(val id: String, val tier: ResolutionTier)

/**
 * Sonnet 5: on the high-resolution tier, so a scanned page reaches it at
 * 1593x2324 rather than the 902x1316 Haiku 4.5 sees. Stage A established that the
 * coordinate protocol is now correct and that what remains is per-balloon scatter;
 * this is the test of whether that scatter is a resolution limit.
 *
 * Changing this line changes the tier with it, and the upload size follows. Verify
 * the price of any replacement before adopting it: image tokens roughly triple on
 * the high-resolution tier, and this is one call per page turn.
 */
val PAGE_VISION_MODEL = VisionModel("claude-sonnet-5", ResolutionTier.HIGH_RESOLUTION)
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.domain.model.VisionModelTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/model/VisionModel.kt \
        app/src/test/kotlin/com/storyteller/domain/model/VisionModelTest.kt
git commit -m "feat: keep the vision model and its resolution tier in one place"
```

---

### Task 3: Send the bigger image to the bigger model

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/capture/Downscale.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/capture/CaptureViewModel.kt:30`
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageReaderImpl.kt:27`
- Modify: `app/src/main/kotlin/com/storyteller/data/local/Entities.kt:7`
- Test: `app/src/test/kotlin/com/storyteller/ui/capture/DownscaleTest.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt`

**Interfaces:**
- Consumes: `PAGE_VISION_MODEL`, `ResolutionTier` (Task 2).
- Produces: no new signatures. `downscaleToPageImage` keeps its `maxEdge`/`maxTokens` parameters; only the defaults' source changes.

- [ ] **Step 1: Bump the parse version FIRST**

`Entities.kt:7`: `const val PARSE_VERSION = 6`.

Strictly the cache would miss anyway, because the upload bytes change size and the
key is their hash. Bump regardless — and note the real gap while here: **the cache
key does not include the model id.** Two models given byte-identical uploads would
share a cached parse. Today they cannot, because the tier changes the upload size,
so this is latent rather than live. Record it in the issue document as a known
limitation; do not fix it in this task.

- [ ] **Step 2: Write the failing tests**

In `DownscaleTest.kt`:

```kotlin
    @Test fun `the upload is sized for the tier the page model is on`() {
        val image = downscaleToPageImage(jpeg(2532, 3695), rotationDegrees = 0)
        val expected = modelVisibleSize(
            2532, 3695,
            PAGE_VISION_MODEL.tier.maxEdge, PAGE_VISION_MODEL.tier.maxTokens,
        )
        assertEquals(expected.width to expected.height, sizeOf(image.bytes))
        assertEquals(expected.width to expected.height, image.width to image.height)
    }

    @Test fun `a high resolution page still fits its own tier's budget`() {
        val image = downscaleToPageImage(jpeg(2532, 3695), rotationDegrees = 0)
        val tier = PAGE_VISION_MODEL.tier
        assertTrue(visualTokens(image.width, image.height) <= tier.maxTokens)
        assertTrue(maxOf(image.width, image.height) <= tier.maxEdge)
    }
```

The existing `the upload copy never needs a server-side resize` test asserts
against `STANDARD_MAX_*`. **Change it to assert against
`PAGE_VISION_MODEL.tier`**, not to assert both — the standard-tier constants
remain correct as constants and are still covered by `ModelImageSizeTest`.

In `PageReaderImplTest.kt`:

```kotlin
    @Test fun `the request names the page vision model`() = runTest {
        enqueueTextBlock("""{"units":[],"characters":[]}""")
        reader().read(pageImage())
        val body = server.takeRequest()!!.body!!.utf8()
        assertTrue(body.contains(PAGE_VISION_MODEL.id))
    }
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.capture.DownscaleTest" --tests "com.storyteller.data.page.PageReaderImplTest"`
Expected: FAIL — the upload is still 902x1316 and the body still names `claude-haiku-4-5`.

- [ ] **Step 4: Point both layers at the shared value**

In `Downscale.kt`, default the two limits from the model rather than from the
standard-tier constants, and import from `domain.model`:

```kotlin
fun downscaleToPageImage(
    jpeg: ByteArray,
    rotationDegrees: Int = 0,
    maxEdge: Int = PAGE_VISION_MODEL.tier.maxEdge,
    maxTokens: Int = PAGE_VISION_MODEL.tier.maxTokens,
    quality: Int = JPEG_QUALITY,
): PageImage {
```

Update its KDoc: the tier is no longer assumed, it comes from the model, and the
`oversized_image: "error"` guard is what proves the two agree.

`CaptureViewModel.kt:30` needs no change — it relies on the defaults. Confirm that
by reading it rather than assuming.

In `PageReaderImpl.kt`, delete `private const val MODEL` and use
`PAGE_VISION_MODEL.id` in `requestBody`.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. Watch for `VisionEval` / `VisionEvalSelfTest`, which call
`downscaleToPageImage` and will now produce larger uploads.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/capture/Downscale.kt \
        app/src/main/kotlin/com/storyteller/data/page/PageReaderImpl.kt \
        app/src/main/kotlin/com/storyteller/data/local/Entities.kt \
        app/src/test/kotlin/com/storyteller/ui/capture/DownscaleTest.kt \
        app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt
git commit -m "feat: read the page with a high-resolution-tier model"
```

---

### Task 4: Measure Stage B on device

**Files:**
- Modify: `docs/issues/2026-08-31-bubble-box-accuracy-measured.md`

- [ ] **Step 1: Install onto a cleared app**

```bash
./gradlew installDebug
adb -s 59251JEBF12416 shell pm clear com.storyteller
adb -s 59251JEBF12416 shell monkey -p com.storyteller -c android.intent.category.LAUNCHER 1
```

Scan **the same page** as bundles `page-1788205074358`, `page-1788215961215` and
`page-1788278845946`. A different page is a different subject and measures nothing.

- [ ] **Step 2: Confirm the request actually changed**

```bash
python scripts/diagnostics.py pull --serial 59251JEBF12416
```

`meta.json` must show an upload near **1593x2324**, not 902x1316. If it still shows
the standard-tier size, `Downscale` is not reading the model's tier and the whole
measurement is void.

If the read failed, look for a 400 first. `oversized_image: "error"` firing here
means the high-resolution limits in `ResolutionTier` are wrong, or the model chosen
is not on that tier after all. **Fix the tier; do not remove the guard** — without
it this failure would have been silent displaced coordinates.

- [ ] **Step 3: Measure**

```bash
python scripts/measure_boxes.py diagnostics-pulled/<newest-bundle>
```

- [ ] **Step 4: Record the result, whichever way it goes**

Add a section with the same table shape as §13, comparing all four bundles on the
Task 1 ground truth. **Do not report a partial improvement as success.**

- **IoU >= 0.5** — Stage B worked. Say so, close the issue, and settle whether the cost is acceptable as the default.
- **IoU materially up, below 0.5** — resolution is part of it. Report the remaining structure, and weigh a further step against the cost already being paid.
- **IoU roughly unchanged** — resolution was not the limit either. Stages A and B together then say the model does not localise small stylised balloons at any resolution or protocol, and the honest conclusion is that this approach is finished. Say that, and treat §11's OCR split as the remaining option — but see §13.4: it must first be shown that OCR can group a comic page into balloons at all, which it could not do well enough to measure with.

Then revert the model if the result does not justify the cost. A 15x bill for an
unchanged number is not a thing to leave switched on by inertia.

```bash
git add docs/issues/2026-08-31-bubble-box-accuracy-measured.md
git commit -m "docs: record what a high-resolution model changed"
```

---

## Self-Review

**Spec coverage.** §13.5 asks for a high-resolution-tier measurement (Tasks 2-4)
and states it is not measurable until §13.3 is fixed (Task 1). §13.4's warning
about the OCR split is carried into Task 4's third outcome rather than left in the
issue document alone.

**Placeholder scan.** No TBD/TODO. The one deliberately open value is the model
choice, which is stated as a recommendation with its cost, and isolated to a single
constant so changing it is one edit.

**Type consistency.** `ResolutionTier` and `VisionModel` are produced in Task 2 and
consumed in Task 3 by both `ui/capture` and `data/page`. `modelVisibleSize`,
`visualTokens` and `ImageSize` come from Iteration 3 Task 1 unchanged.

**Ordering.** Task 1 comes first and is not optional. Running Tasks 2-4 against the
current ground truth would produce a number with a 9x error bar, which cannot
distinguish success from failure — the exact position Stage A ended in.

**Three risks worth naming.**

The cost is real and recurring, up to 15x per page on image tokens. It is stated
above rather than discovered on a bill, and Task 4 ends by requiring the model be
reverted if the result does not earn it.

Task 1 assumes the model's transcription is accurate enough to be ground truth for
word ownership. The issue document supports that — "a good transcriber and weak
localiser" is its central finding — but it is an assumption, and if OCR reading
order diverges badly from the model's reading order the alignment degrades quietly.
Step 3's per-unit aligned-word counts are the guard, and they must actually be read,
not just printed.

The cache key still does not include the model id. Task 3 Step 1 records this
rather than fixing it, because today the tier changes the upload size and so the
hash. If a future change puts two models on the same tier, that becomes live, and a
page read by one could be served a parse produced by the other.
