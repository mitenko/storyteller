# Storyteller Iteration 4 — Stage B: give the model more pixels to localise from

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the vision call to a high-resolution-tier model, so the page reaches it at 1593x2324 instead of 902x1316, and find out whether the per-balloon scatter left over from Stage A is a resolution problem. Fix the ground truth first, because at present it cannot measure the answer.

**Architecture:** Two changes and one prerequisite. The prerequisite is in the measurement harness, not the app: replace the gap-clustered OCR ground truth with one aligned to the model's own transcript, removing the parameter that currently swings mean IoU by 9x. In the app, the model's identity and its resolution tier become a single `domain` value that both `ui/capture` (which sizes the upload) and `data/page` (which names the model) read, so the two cannot drift apart.

**Tech Stack:** Kotlin, Android, Retrofit + kotlinx.serialization, Robolectric, JUnit 4, Python 3 for the harness.

**Spec / evidence base:** [`docs/issues/2026-08-31-bubble-box-accuracy-measured.md`](../../issues/2026-08-31-bubble-box-accuracy-measured.md), sections 12 and 13. Stage B exists because of §13.5; Task 1 exists because of §13.3.

## Global Constraints

- High-resolution tier is **2576 px max edge, 4784 max visual tokens**, and covers "Claude 4.7 and later models". It is automatic on those models: **no beta header and no client-side opt-in**. Standard tier (1568/1568) is every other model, Haiku 4.5 included.
- `GraphTest` enforces two rules that together decide this plan's shape: **`ui` must never import `data`**, and **`domain` must never import `android.*` or `androidx.*`**. The tier is needed in `ui`, the model id in `data`; the only legal shared home is `domain`.
- Keep `transformations: {"oversized_image": "error"}`. It is the only thing that turns a tier/limit mismatch into a visible 400 instead of silently displaced coordinates, and this iteration changes exactly those limits. Its behaviour is not assumed: the preflight below shows the same image accepted without the field and rejected with it, on the same model.
- Keep reject-don't-clamp, and keep the loud `require` on missing image dimensions.
- `minSdk 26`, `compileSdk 36`, `targetSdk 36`. Robolectric pinned at `sdk=34` — do not bump. `buildToolsVersion` stays unset.
- Commits use the repo's existing author, `mitenko`. **No `Co-Authored-By` trailer of any kind.**
- Gradle: `BindException` → `./gradlew --stop`, retry. `Unable to delete directory .../test-results/...` → `./gradlew --stop`, delete `app/build/test-results` and `app/build/reports`, re-run. Neither is a code failure.

## Adjudication of the review comments

Eleven of the twelve are accepted. One is rejected, and the rejection is settled
by experiment rather than by argument, because it is the second time it has been
raised.

### The preflight, run 2026-09-01

The review's own recommendation was to preflight the model, tier and request field
before implementing. That is done. A synthetic gradient image at 1593x2324 — the
high-resolution answer for our page, and well over the standard tier's limits —
was sent to the live API with the app's own credentials. No book page was involved.

| request | result |
|---|---|
| `claude-sonnet-5`, `oversized_image: "error"` | **ACCEPTED**, 4750 input tokens |
| `claude-haiku-4-5`, same image, same field | **HTTP 400** |
| `claude-haiku-4-5`, same image, **field omitted** | **ACCEPTED**, 1569 input tokens |

Haiku's 400, in full:

> `messages.0.content.0: image dimensions 1593x2324 exceed the maximum image size
> of a model named on this request and would be downsized to 902x1316; scale the
> image to at most 902x1316 or set the image's oversized_image setting to
> "downsize"`

Four things are now established rather than assumed:

1. **`claude-sonnet-5` is reachable with this key and is on the high-resolution
   tier.** It accepted an image no standard-tier model will accept.
2. **`transformations` is enforced, not ignored.** Same model, same image, accepted
   without the field and rejected with it. An ignored field would have let both
   through; an unknown field would have failed on both.
3. **`modelVisibleSize` is correct.** The server independently names **902x1316**
   as the standard-tier target for our page geometry — the exact value the Kotlin
   computes, and the exact value Stage A uploaded.
4. **The token estimate is correct.** Predicted 4731 image tokens for the
   high-resolution upload; measured 4750 including the prompt. Haiku's downsized
   control came back 1569 against a predicted 1551 + prompt.

### Verdicts

| # | Verdict | Basis |
|---|---|---|
| 1 model/tier unverified | **Accepted, and now discharged** | The preflight above. Sonnet 5 confirmed available and high-resolution tier. Task 2's constant is no longer a guess. |
| 2 `transformations` undocumented | **Rejected** | It is documented — the coordinate guide has a section titled "Turn resizing into an error with `transformations`" giving this exact JSON — and it is empirically live, per the preflight's three-way control. It is also already shipping: Stage A's device bundle `page-1788278845946` was read successfully with this field set. Had it been an unknown property, every Stage A request would have failed; none did. This is the same comment Iteration 3 raised and rejected on documentation alone; it is now rejected on documentation, a controlled experiment, and production evidence. |
| 3 alignment ambiguity on repeated tokens | **Accepted — the most valuable comment here** | Correct, and it is the third appearance of this failure class in this issue: greedy text matching already contaminated the original measurement, and a one-word `"TO"` cluster already corrupted the committed fixture. Task 1 now reports per-unit alignment confidence and refuses low-confidence units rather than publishing them. |
| 4 same-page identity check | **Accepted** | Clearing app data proves the parse is fresh; it proves nothing about which page was photographed. Cheap to check, and a wrong page would invalidate the whole comparison silently. |
| 5 rollback gate | **Accepted** | Added to Task 4. |
| 6 punctuation and contraction normalisation | **Accepted** | Folded into Task 1 with #3; they are the same defect seen from two sides. |
| 7 n=1 cannot establish generalisation | **Accepted, with the ordering kept** | Correct. The same page is required for *comparability* against three existing bundles; more pages are required before a *production* decision. Both hold, so Task 4 gates the production default on a multi-page set while keeping the single-page comparison as the experiment. |
| 8 cost must include all tokens | **Accepted** | The preflight measured the real totals, so the table below now uses them instead of image tokens alone. |
| 9 rotation dimension propagation | **Accepted in part** | Iteration 3 already tests carried dimensions for a 90-degree capture. What is genuinely untested is 270 degrees and the end-to-end chain through `pageInstruction` and normalisation. That gap is real and is added. |
| 10 normalise by encoded, never padded | **Accepted** | Already true in the code; not asserted anywhere. An invariant nothing tests is an invariant waiting to break. |
| 11 record model and tier in the bundle | **Accepted — a real gap I missed** | `meta.json` records dimensions but not which model, tier or parse version produced them. A Stage B bundle is currently indistinguishable from a Stage A one except by inference from its size. |
| 12 model id absent from the cache key | **Accepted, and fixed rather than deferred** | I had recorded this as latent. On reflection the reviewer is right that "safe only while every model change also changes upload bytes" is too fragile a thing to leave resting on a coincidence. It is a small change and it is in this iteration. |

## What this costs, measured rather than estimated

Per page, **total input tokens**, from the preflight:

| | input tokens | documented price | input cost / page |
|---|---|---|---|
| Haiku 4.5, standard tier (today) | 1569 | $1 / M | ~$0.0016 |
| Sonnet 5, high-resolution tier | 4750 | see <https://claude.com/pricing> | 3.03x the tokens |

The real page prompt is longer than the preflight's one-line probe — roughly 300
further tokens for `pageInstruction` plus the JSON schema — and output tokens are
extra and unchanged by tier. Token count rises **3.03x**; the bill rises by that
multiplied by whatever Sonnet 5's input price is against Haiku 4.5's $1/M. The
vision documentation quotes only Haiku 4.5 and Opus 5 ($5/M), so Sonnet 5's price
must be read from the pricing page before this becomes the default — Task 4 gates
on exactly that.

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

- [ ] **Step 2a: Report alignment confidence, and refuse units that lack it**

Review comment 3 is the most valuable of the set, because this failure class has
now appeared three times in this issue: greedy substring matching contaminated the
first measurement, a one-word `"TO"` cluster corrupted the committed fixture, and
flattened-sequence alignment can misassign a repeated `THE`/`TO`/`YOU` to the wrong
unit in exactly the same way. A published IoU must never rest on a guess about
which balloon a word came from.

`get_matching_blocks()` is monotonic, so ownership cannot cross backwards — but
monotonic is not the same as correct when OCR drops or reorders a word. Add a
per-unit confidence and drop what fails it:

```python
MIN_ALIGNMENT_CONFIDENCE = 0.5
MIN_ALIGNED_WORDS = 2

def alignment_confidence(aligned_count, unit):
    """How much of a unit's transcript was actually located, 0..1.

    Not a similarity score: a unit can match a few words strongly and still have
    most of its text unlocated, and its extent is then a fragment -- precisely the
    artefact that made the committed CameraX fixture report a balloon as 16x too
    wide. Coverage is the property that matters, so coverage is what is measured.
    """
    want = [t for t in (alpha(w) for w in unit["text"].split()) if len(t) >= 3]
    return (aligned_count / len(want)) if want else 0.0
```

Require both thresholds. Print, for every unit: aligned word count, transcript word
count, confidence, and whether it was used. Print the OCR tokens that aligned to
nothing and the transcript tokens never found — those two lists are how a reader
sees the ground truth is sound, and their absence is how the earlier measurements
concealed their own contamination.

Normalisation (review comment 6): `alpha()` already strips apostrophes, so
`PRISON'S` and `PRISONS` collapse together, which is wanted. What is not wanted is
a one- or two-letter token being decisive, so tokens under three letters are
aligned but never counted toward confidence and never alone establish an extent.

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
 * Verified against the live API on 2026-09-01, not inferred from the model name:
 * this model accepted a 1593x2324 image marked oversized_image=error, which no
 * standard-tier model will accept, and Haiku 4.5 rejected the identical request
 * with a 400 naming 902x1316 as its own limit.
 *
 * Changing this line changes the tier with it, and the upload size follows. Check
 * the price of any replacement before adopting it: total input tokens measured
 * 3.03x Haiku 4.5's on the same page, and this is one call per page turn.
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

- [ ] **Step 1: Bump the parse version FIRST, and put the model in the cache key**

`Entities.kt:7`: `const val PARSE_VERSION = 6`.

Then fix the cache key, which review comment 12 is right to refuse to let stand.
I had recorded it as latent because a tier change also changes the upload size and
therefore its hash — but that is a coincidence of this particular change rather
than a property of the design, and "a parse cache must never return output from a
different vision model" is the correct bar. In `PageReaderImpl.readOrThrow`:

```kotlin
        // The model belongs in the cache key, not just the bytes: two models given
        // byte-identical uploads are two different answers to the same question.
        // Today a tier change also changes the upload size and so the hash, which
        // is why this was survivable -- but that is a coincidence of this change,
        // and the next same-tier model switch would silently serve the old model's
        // boxes for a page the new one never saw.
        val hash = sha256(image.bytes + PAGE_VISION_MODEL.id.toByteArray())
```

Test it:

```kotlin
    @Test fun `a parse cached under one model is not served to another`() = runTest {
        val img = pageImage()
        db.parsedPageDao().upsert(
            ParsedPageEntity(
                sha256(img.bytes + "some-other-model".toByteArray()),
                QQQ{"units":[{"speaker":"Stale","text":"OLD","bounds":null}]}QQQ,
                System.currentTimeMillis(),
                parseVersion = PARSE_VERSION,
            ),
        )
        enqueueTextBlock(QQQ{"units":[{"speaker":"Fresh","text":"NEW","bounds":null}],"characters":[]}QQQ)

        assertEquals("Fresh", reader().read(img).getOrThrow().units[0].speaker)
    }
```

- [ ] **Step 1b: Record the model and tier in the diagnostic bundle**

Review comment 11 is a real gap. `meta.json` records dimensions but not what
produced them, so a Stage B bundle is distinguishable from a Stage A one only by
inferring from its size — exactly the kind of inference this issue has already been
burned by. `DiagnosticWriter.metaJson` gains three fields:

```kotlin
              "modelId": "${PAGE_VISION_MODEL.id}",
              "resolutionTier": "${PAGE_VISION_MODEL.tier.name}",
              "parseVersion": $PARSE_VERSION,
```

`DiagnosticWriter` is in `data`, which may import `domain`, so this is legal under
`GraphTest`. Update `DiagnosticWriterImplTest` to assert all three. Update
`measure_boxes.py` to print them, so every measurement states which model and
protocol produced the bundle it is measuring.

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

    /**
     * Review comment 10. Normalisation divides by the dimensions of the image we
     * encoded -- never by Claude's padded dimensions, never by a guess at what the
     * server did. 893x1372 pads to 896x1372, so a full-width box normalises to
     * exactly 1.0 under the encoded width and 0.9967 under the padded one. That is
     * the class of small silent error this whole iteration exists to remove, and it
     * is true in the code today but asserted nowhere.
     */
    @Test fun `bounds normalise by the encoded dimensions, not the padded ones`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":0,"y1":0,"x2":893,"y2":1372}}
        ],"characters":[]}""")
        val b = reader().read(pageImage()).getOrThrow().units[0].bounds!!
        assertEquals("must divide by 893, not the padded 896", 1f, b.right, 0.0001f)
    }
```

And in `DownscaleTest.kt`, the orientation chain review comment 9 asks for.
Iteration 3 covers carried dimensions at 90 degrees; 270 and the full sweep are
untested:

```kotlin
    @Test fun `carried dimensions stay upright at 270 degrees`() {
        val image = downscaleToPageImage(jpeg(4000, 3000), rotationDegrees = 270)
        assertEquals(sizeOf(image.bytes), image.width to image.height)
        assertTrue("a rotated landscape capture should encode portrait", image.height > image.width)
        assertEquals(3000 to 4000, sizeOf(image.displayBytes))
    }

    @Test fun `the prompt describes the same space the upload was encoded in`() {
        // The bug this guards is invisible: a transposed prompt yields boxes that
        // parse cleanly, validate cleanly, and land in the wrong place.
        for (rotation in listOf(0, 90, 180, 270)) {
            val image = downscaleToPageImage(jpeg(4000, 3000), rotationDegrees = rotation)
            assertEquals("rotation $rotation", sizeOf(image.bytes), image.width to image.height)
        }
    }
```

`pageInstruction` is built from `image.width`/`image.height`, so pinning those to
the encoded bytes at every rotation pins the prompt with them.

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

- [ ] **Step 1b: Prove it is the same page (review comment 4)**

Clearing app data proves the parse is fresh. It proves nothing about which page was
photographed, and a wrong page invalidates the comparison silently while looking
entirely normal. Add a `--identity` mode to `measure_boxes.py` that prints, for each
bundle, its display dimensions, its `modelId` when present, and a 256-bit average
hash of a 16x16 greyscale reduction of `page-display.jpg`. Compare the new bundle
against `page-1788278845946` before measuring.

The two hashes will not be identical — it is a fresh photograph, not a copy — but
they must agree in the large majority of their bits. A different page differs
grossly. If they disagree, rescan rather than measure, and if the disagreement
persists, record the caveat with the result instead of suppressing it.

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

- [ ] **Step 5: Decide the production default deliberately (review comments 5, 7, 8)**

Three gates, none of which the single-page experiment clears on its own:

**Generalisation.** One page cannot establish that the model is or is not
resolution-limited across comic layouts. The same-page comparison is the
*experiment*; before Stage B's model becomes the shipped default, measure at least
a dense page, a sparse page, and a multi-panel/multi-column layout. A model that
wins on one page and loses on the others has told you it is noise.

**Cost.** Read Sonnet 5's actual input price at <https://claude.com/pricing> and
multiply by the measured **3.03x** token increase. The preflight supplies the token
counts; only the price is missing, and no figure for it should be invented here.

**Rollback, if the result does not earn it.** Not merely changing the constant back:

```bash
# 1. revert PAGE_VISION_MODEL to VisionModel("claude-haiku-4-5", ResolutionTier.STANDARD)
# 2. bump PARSE_VERSION again. With the model now in the cache key the v6 rows
#    would miss anyway, but the bump makes that a stated guarantee rather than a
#    consequence of one.
./gradlew :app:testDebugUnitTest && ./gradlew installDebug
adb -s 59251JEBF12416 shell pm clear com.storyteller
# 3. scan, pull, and CONFIRM in meta.json: modelId is haiku-4-5, resolutionTier is
#    STANDARD, and uploadWidth/Height are back to 902x1316
```

A reverted build still uploading at high-resolution dimensions is a half-revert
that pays the token cost for nothing. Step 1b's `meta.json` fields exist precisely
so that cannot pass unnoticed.

A 3x bill for an unchanged number is not a thing to leave switched on by inertia.

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

**What the review changed.** Eleven comments accepted, one rejected on evidence.
The substantive additions are alignment confidence in Task 1, the model in the
cache key and in `meta.json` in Task 3, and a page-identity check plus a real
rollback procedure in Task 4. The one rejection, `transformations`, is now settled
by a controlled experiment rather than by re-reading the same documentation.

**Three risks worth naming.**

The cost is real and recurring: **3.03x the input tokens**, measured rather than
estimated. Whether that is 3x the bill or more depends on Sonnet 5's price, which
Task 4 requires be read rather than assumed. Task 4 also ends by requiring the
model be reverted, and the revert verified in `meta.json`, if the result does not
earn it.

Task 1 assumes the model's transcription is accurate enough to be ground truth for
word ownership. The issue document supports that — "a good transcriber and weak
localiser" is its central finding — but it is an assumption, and if OCR reading
order diverges badly from the model's reading order the alignment degrades quietly.
Step 2a's confidence report is the guard, and it must actually be read, not merely
printed. This failure class has already appeared three times in this issue; it will
not announce itself the fourth time either.

Stage B may simply not work, and that is a legitimate outcome rather than a
setback. Stage A established that the protocol is now correct; if tripling the
pixels changes nothing, the two stages together say this model does not localise
small stylised balloons at any resolution or protocol. Task 4's third outcome
writes that conclusion down in advance so it cannot be quietly avoided — and §13.4
already warns that the OCR fallback everyone assumes is waiting has a wall of its
own.

---

## Appendix: the review as received

### Blocking or high-risk corrections

1. **Verify the selected model and tier before changing the app.** The plan
   recommends `claude-sonnet-5` and assumes it is available to this API key and
   on the 2576/4784 high-resolution tier, but no live API/model-list check is
   included. A model that is unavailable, access-restricted, or assigned to a
   different tier would turn the device experiment into an API failure rather
   than a resolution measurement. Add a preflight using the same endpoint and
   credentials as the app, and record the confirmed model id, tier limits and
   price before implementation.

2. **Do not require the undocumented `transformations` field without endpoint
   evidence.** Iteration 3's review already flagged
   `"transformations": {"oversized_image": "error"}` as absent from the cited
   Anthropic documentation. Stage B repeats it as a global constraint and makes
   a 400 part of the expected control flow. Either cite the exact API schema that
   accepts this field and add a request test, or remove it and enforce the
   invariant locally by checking the encoded upload dimensions before sending.
   An unknown image-block property could make every Stage B request fail.

3. **Task 1's `SequenceMatcher` alignment is not deterministic ground truth when
   words repeat.** Matching one flattened OCR sequence to one flattened
   transcript sequence can assign repeated tokens such as “THE”, “TO” or “YOU”
   to the wrong unit, especially when OCR misses or inserts a word.
   `get_matching_blocks()` does not know unit boundaries and can choose an
   arbitrary occurrence. Add an alignment confidence/ambiguity report and reject
   ambiguous matches, or use a monotonic dynamic-programming alignment that
   preserves ownership and reports gaps. Do not silently turn low-confidence
   transcript matches into published IoU numbers.

4. **The “same page” requirement needs a visual identity check.** Clearing app
   data prevents a parse-cache hit, but it does not prove that the new scan is
   the same physical page or that the scanner produced the same crop. Record a
   stable local image hash and compare the pulled display/upload dimensions and
   page content with the existing fixtures before accepting the Stage B result.

5. **Add a rollback gate before installing a high-cost default.** The plan says
   to revert if the result is not justified, but does not specify rollback
   validation. Preserve the last known-good model/tier values, rebuild after
   reverting, verify the request model and upload dimensions, and confirm the
   cache version cannot serve a Stage B parse to the reverted build.

### Measurement and correctness gaps

6. **Define how OCR alignment handles punctuation and contractions.** The
   proposed `alpha()` comparison may turn `PRISON'S` into `PRISONS`, but it also
   collapses distinct tokens and can make unrelated short words match. Document
   normalization for apostrophes, numbers, repeated words and OCR substitutions;
   print unmatched transcript/OCR tokens and a per-unit confidence score.

7. **Do not use a single page to decide that the model is resolution-limited.**
   The same-page experiment is necessary for comparability, but n=1 cannot
   establish generalization to other comic layouts. Define a small follow-up
   fixture set (at least one dense page, one sparse page and one multi-column
   page-panel layout) before making a production model decision.

8. **The cost estimate must include all request tokens, not only image tokens.**
   The table labels itself “image tokens only,” which is useful, but the model
   recommendation should also state that the prompt, schema and response tokens
   add cost. Use the actual selected-model pricing before choosing Sonnet as the
   default.

9. **Test dimension propagation after rotation and exact resizing.** Add tests
   using sideways input that verify `PageImage.width`/`height`,
   `pageInstruction(width, height)`, pixel normalization and `displayBytes` all
   use the final upright dimensions for 90- and 270-degree inputs. A mismatch
   here would recreate the original coordinate-space bug while appearing to use
   the correct tier.

10. **Make the local size invariant independent of provider padding.** The plan
    correctly distinguishes pre-padding dimensions from Claude's 28-pixel
    padding, but the request and tests should explicitly assert that
    normalization divides by the encoded upload dimensions, never by padded
    dimensions or a guessed server-side size. Keep this observable in
    diagnostics or the request test.

11. **Update stale diagnostics and comments as part of the plan.** The current
    diagnostic code supports both v1-v4 normalized boxes and v5 pixel boxes, but
    comments and fixtures must identify Stage B's parse version and model id.
    Add `modelId`, resolution tier and upload dimensions to `meta.json`, or record
    them in the measurement output, so a future bundle cannot be mistaken for a
    Stage A result.

12. **Resolve the cache-key limitation now or make it an explicit acceptance
    constraint.** The plan knowingly leaves the model id out of the cache key.
    That is safe only while every model change also changes upload bytes. Include
    the model id/tier in the cache key if possible without an unnecessary
    migration, or state clearly that every same-size model switch requires a
    cache/database version bump. A parse cache must never return output from a
    different vision model.

### Recommendation

Proceed with Stage B only after the model/tier and request-field preflights are
resolved and Task 1 reports alignment confidence rather than only IoU. The
absolute-pixel experiment materially improved box size and vertical placement in
Stage A, so a high-resolution test is justified, but the success criterion must
remain usable crops (IoU at least 0.5), not a lower cost-adjusted error or a
single visually improved overlay.
