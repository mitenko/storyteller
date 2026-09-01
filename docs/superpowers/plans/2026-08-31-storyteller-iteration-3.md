# Storyteller Iteration 3 — Stage A: ask for coordinates the documented way

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the normalised-fraction coordinate protocol — which Anthropic documents as the one that does not work — with absolute pixel coordinates against an image pre-resized to exactly what the model sees, then re-measure.

**Architecture:** Four changes, all upstream of `BoundingBox`. Pure resize arithmetic lands in `domain` (no Android imports, JVM-testable). `PageImage` starts carrying the pixel dimensions of its own upload copy, set by `Downscale` where the bitmap is already decoded. `ui/capture` sizes the upload to the model-visible size instead of a flat 1568 long edge. `data/page` asks for pixel boxes, normalises them against those carried dimensions, and bumps the parse version. Nothing downstream of `BoundingBox` changes — `cropRect`, `BubbleCrop` and the reader are untouched.

**Tech Stack:** Kotlin, Android (BitmapFactory), Retrofit + kotlinx.serialization, Robolectric, JUnit 4, Python 3 for the measurement harness.

**Evidence base:** [`docs/issues/2026-08-31-bubble-box-accuracy-measured.md`](../../issues/2026-08-31-bubble-box-accuracy-measured.md) — including the §4 correction that motivates this whole iteration.

## Global Constraints

- The app calls `claude-haiku-4-5` (`PageReaderImpl.kt:27`). It is **standard resolution tier**: max edge **1568 px**, max visual tokens **1568**. High-resolution tier is "Claude 4.7 and later models" only. Do not change the model in this iteration — that is Stage B.
- Visual token cost is `ceil(w/28) * ceil(h/28)`. Claude pads to the next multiple of 28 on the bottom and right **after** resizing. **Normalise by the resized dimensions, never the padded ones.**
- Reference: <https://platform.claude.com/docs/en/build-with-claude/vision-coordinates>
- `minSdk 26`, `compileSdk 36`, `targetSdk 36`. Robolectric pinned at `sdk=34` — do not bump. `buildToolsVersion` stays unset.
- `GraphTest` enforces `ui -> domain <- data`, and `ui` must never import `data`. New shared arithmetic goes in `domain`, which both may import.
- Keep the existing reject-don't-clamp philosophy: a box the model placed outside the image becomes `null` so the reader falls back to text. Never clamp — clamping is what hid the original bug. **But distinguish model error from programmer error:** an image arriving at the reader with no dimensions is our bug, and must fail loudly rather than silently disabling every box.
- Commits use the repo's existing author, `mitenko`. **No `Co-Authored-By` trailer of any kind** — this is a standing instruction for this repository, not a default to be reconsidered per-commit.
- Run Gradle in the foreground and wait for the real exit code. `BindException` → `./gradlew --stop`, retry. `Unable to delete directory .../test-results/...` → `./gradlew --stop`, delete `app/build/test-results` and `app/build/reports`, re-run. Neither is a code failure.

---

## Adjudication of the review comments

The previous revision carried thirteen appended review comments. Each was checked
against the vendor documentation, the reference algorithm run locally, and the
files it describes. Six are accepted, three accepted with modification, four
rejected. Rejections are recorded with their evidence so they are not re-raised.

| # | Verdict | Basis |
|---|---|---|
| 1 `transformations` unverified | **Rejected** | It is documented, in exactly this shape. The coordinate guide has a section titled "Turn resizing into an error with `transformations`" giving `{"type":"image","source":{…},"transformations":{"oversized_image":"error"}}` as a sibling of `source`, and describes the 400 it produces. Keeping it. |
| 2 size should be 893×1371 | **Rejected** | Running the vendor's own reference implementation on 1021×1568 returns **893×1372**. 1372 = 49×28 exactly, so padding is a no-op on that axis; the padded size is 896×1372 — it is the *width* that gets padded, not the height. The comment has it backwards. Task 1 now pins the padded size too, so this cannot be misread again. |
| 3 orientation unspecified | **Accepted** | Real, and the most useful comment of the set. `Downscale` rotates *after* scaling, so for a 90° capture the encoded image is the transpose of `target`. Resolved structurally in Task 2 by reading dimensions off the final bitmap. (The limits themselves are transpose-invariant — verified — so rotation cannot push the upload over budget; only the *prompt* was at risk.) |
| 4 use integer coordinates | **Rejected** | The vendor's own worked example returns a fractional pixel: `(462, 653.5)`. `Float` is correct; `"type": "integer"` would make valid responses unparseable. |
| 5 reject non-finite values | **Accepted with modification** | Reachable only through a subtle path — kotlinx rejects a literal `NaN` by default — but `NaN` would defeat every comparison in `toDomain` if it ever arrived, because all comparisons against `NaN` are false. Cheap to close, and the reasoning is subtle enough to be worth encoding in a test. |
| 6 don't claim "exactly what Claude sees" | **Accepted with modification** | The claim is only warranted *because* `transformations` makes a violation a 400 rather than a silent resize — which is precisely what the vendor offers it for. The wording now says so, `modelVisibleSize` stays available so rescaling is one call away if the tier changes, and `meta.json` keeps decoding the bytes independently (Task 5's check is worthless if it reads back a field we set ourselves). |
| 7 0.078 is not in the evidence | **Accepted** | Correct, and I introduced this error. The issue document records 0.007 and the ~0.70 slopes; the scanner bundle's 0.078 was reported in conversation and never written down. Task 4 no longer pins a remembered number — it validates the tool against the *published* numbers, commits its actual output as the fixture, and backfills the scanner measurement into the document. |
| 8 don't write `error.txt` into the tree | **Accepted with modification** | The `error.txt`-beside-the-script convention is a standing user instruction and stays. The legitimate half of the comment — runtime output must never be committed — is handled by git-ignoring it. |
| 9 update every fixture | **Accepted** | Correct, and easy to under-do. Task 3 now enumerates the sites and names the trap that makes a stale fixture silent. One detail in the comment is wrong: `PageDto` has no `characters` field at all (`PageReaderImpl.kt:37`), so no fixture needs one. The v4→v5 cache-bypass test it suggests is a genuinely good addition and is included. |
| 10 add the Copilot co-author trailer | **Rejected** | This repository's standing instruction is the opposite: never add a `Co-Authored-By` trailer. No such repository workflow exists here. |
| 11 assert `displayBytes` at 0° and 90° | **Accepted** | Cheap, and it guards the exact interaction Task 2 disturbs. |
| 12 keep the stop condition | **Accepted** | Already the shape of Task 5; no change needed. |
| 13 clean-cache guarantee | **Accepted** | A fresh photograph produces different bytes and so a different hash, but that is a reason the check *usually* passes rather than a guarantee. Task 5 now clears app data outright. |

**What the review missed**, found while checking it:

- **`Fakes.kt:17` is `PageImage(byteArrayOf(1, 2, 3), "image/jpeg")`.** The previous
  revision had `PageReaderImpl` decode the upload's dimensions with `BitmapFactory`.
  Three bytes do not decode: `outWidth` comes back `-1`, every box is rejected, and
  the plan's own new tests would have failed on a `null` bounds. It would also have
  put `android.graphics` into the `data` layer for the first time. Task 2 fixes this
  at the root by having `PageImage` carry the dimensions from where they are already
  known.
- **The `inSampleSize` line was wrong in the previous revision.** It said to compute
  it "from `target.width`". For a portrait page, `target.width` is the *short* edge
  (3000×4000 → 952×1270), so the sampler would have halved past the target and then
  scaled back up, discarding resolution. It must compare against the target's long
  edge.

---

### Task 1: The model-visible size, as pure arithmetic

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/domain/geometry/ModelImageSize.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/geometry/ModelImageSizeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class ImageSize(val width: Int, val height: Int)`; `fun visualTokens(width: Int, height: Int): Int`; `fun paddedSize(width: Int, height: Int): ImageSize`; `fun modelVisibleSize(width: Int, height: Int, maxEdge: Int = STANDARD_MAX_EDGE, maxTokens: Int = STANDARD_MAX_VISUAL_TOKENS): ImageSize`; `const val STANDARD_MAX_EDGE = 1568`; `const val STANDARD_MAX_VISUAL_TOKENS = 1568`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storyteller.domain.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference cases come from Anthropic's coordinate documentation. They are
 * pinned here because this arithmetic decides what the model actually sees: get
 * it wrong and every returned pixel coordinate lands somewhere else on the page,
 * silently.
 */
class ModelImageSizeTest {

    @Test fun `an image inside both limits is untouched`() {
        assertEquals(ImageSize(1000, 1000), modelVisibleSize(1000, 1000))
        assertEquals(ImageSize(200, 200), modelVisibleSize(200, 200))
    }

    @Test fun `the documented A4 scan case`() {
        // 1075x1520 is under the edge limit on both sides but costs 39*55 = 2145
        // visual tokens, so the token limit alone forces the resize.
        assertEquals(ImageSize(924, 1307), modelVisibleSize(1075, 1520))
    }

    @Test fun `the documented widescreen case resizes by tokens, not the edge limit`() {
        // Scaling to the 1568 edge limit by hand would give 1568x882 and put every
        // coordinate off target.
        assertEquals(ImageSize(1456, 819), modelVisibleSize(1920, 1080))
    }

    @Test fun `visual tokens are one per 28px patch, rounded up`() {
        assertEquals(64, visualTokens(200, 200))
        assertEquals(2145, visualTokens(1075, 1520))
        assertEquals(2072, visualTokens(1021, 1568))
    }

    @Test fun `the result always fits both limits`() {
        for ((w, h) in listOf(1021 to 1568, 3000 to 4000, 4000 to 3000, 8000 to 1000, 1080 to 1920)) {
            val s = modelVisibleSize(w, h)
            assertTrue("edge limit exceeded for $w x $h -> $s", maxOf(s.width, s.height) <= STANDARD_MAX_EDGE)
            assertTrue(
                "token limit exceeded for $w x $h -> $s",
                visualTokens(s.width, s.height) <= STANDARD_MAX_VISUAL_TOKENS,
            )
        }
    }

    @Test fun `aspect ratio is preserved within a pixel`() {
        val s = modelVisibleSize(1021, 1568)
        assertEquals(1021.0 / 1568.0, s.width.toDouble() / s.height, 0.002)
    }

    @Test fun `the high resolution tier leaves our page alone`() {
        // Stage B depends on this being true; pinned now so Stage B is a one-line change.
        assertEquals(
            ImageSize(1021, 1568),
            modelVisibleSize(1021, 1568, maxEdge = 2576, maxTokens = 4784),
        )
    }

    @Test fun `the scanner page resizes to the size Stage A must send`() {
        assertEquals(ImageSize(893, 1372), modelVisibleSize(1021, 1568))
    }

    /**
     * Review comment 2 claimed the answer was 893x1371 and that 1372 was already
     * the padded height. It is the other way round: 1372 is exactly 49*28, so the
     * height needs no padding at all, and it is the WIDTH that pads, 893 -> 896.
     * Pinned so the confusion cannot recur, and so the distinction between the two
     * sizes stays visible to anyone reading this file.
     */
    @Test fun `padding rounds up bottom and right, and is never what we normalise by`() {
        assertEquals(ImageSize(896, 1372), paddedSize(893, 1372))
        assertEquals(ImageSize(924, 1316), paddedSize(924, 1307))
        assertEquals(ImageSize(1456, 840), paddedSize(1456, 819))
    }

    /**
     * Downscale rotates AFTER scaling, so a 90-degree capture is encoded at the
     * transpose of the computed target. That is only safe because both limits are
     * symmetric under transposition. If this ever fails, Downscale must compute the
     * target from post-rotation dimensions instead.
     */
    @Test fun `the limits are symmetric under transposition`() {
        for ((w, h) in listOf(3000 to 4000, 1920 to 1080, 1021 to 1568, 8000 to 1000)) {
            val a = modelVisibleSize(w, h)
            val b = modelVisibleSize(h, w)
            assertEquals("transpose mismatch for $w x $h", ImageSize(b.height, b.width), a)
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.domain.geometry.ModelImageSizeTest"`
Expected: FAIL — `Unresolved reference: modelVisibleSize`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.storyteller.domain.geometry

/** Claude's standard resolution tier. Haiku 4.5 is on it; 4.7 and later are not. */
const val STANDARD_MAX_EDGE = 1568
const val STANDARD_MAX_VISUAL_TOKENS = 1568

private const val PATCH = 28

data class ImageSize(val width: Int, val height: Int)

/** Claude views images in 28x28 patches; one patch is one visual token. */
fun visualTokens(width: Int, height: Int): Int =
    ceilDiv(width, PATCH) * ceilDiv(height, PATCH)

/**
 * What Claude pads an image up to, on the bottom and right edges only.
 *
 * Exposed for tests and diagnostics, and deliberately NOT used by the request
 * path: the padding contains no content, and normalising by it scales every
 * coordinate by a small amount. Normalise by [modelVisibleSize], always.
 */
fun paddedSize(width: Int, height: Int): ImageSize =
    ImageSize(ceilDiv(width, PATCH) * PATCH, ceilDiv(height, PATCH) * PATCH)

/**
 * The size Claude resizes an image to before padding.
 *
 * This exists because the app asks for PIXEL coordinates, and the pixels Claude
 * reports are pixels of the image IT sees — not the one we uploaded. Sending an
 * image already at this size makes the two the same thing and removes the rescale
 * step entirely.
 *
 * The token limit, not the edge limit, is what resizes ordinary page photographs:
 * a 1075x1520 scan is under 1568 px on both sides yet still costs 2145 tokens.
 * Scaling to the edge limit by hand is the documented way to get this wrong.
 *
 * Ported from the reference implementation in Anthropic's coordinate guide:
 * https://platform.claude.com/docs/en/build-with-claude/vision-coordinates
 */
fun modelVisibleSize(
    width: Int,
    height: Int,
    maxEdge: Int = STANDARD_MAX_EDGE,
    maxTokens: Int = STANDARD_MAX_VISUAL_TOKENS,
): ImageSize {
    fun fits(w: Int, h: Int): Boolean =
        ceilDiv(w, PATCH) * PATCH <= maxEdge &&
            ceilDiv(h, PATCH) * PATCH <= maxEdge &&
            visualTokens(w, h) <= maxTokens

    if (fits(width, height)) return ImageSize(width, height)
    if (height > width) {
        val flipped = modelVisibleSize(height, width, maxEdge, maxTokens)
        return ImageSize(flipped.height, flipped.width)
    }

    // Binary search along the long edge for the largest aspect-preserving size
    // that fits. The short edge rounds half to even (Math.rint), matching the live
    // API at exact .5 ties; Math.round would round them up and compute a different
    // size for some images.
    val aspect = width.toDouble() / height
    fun shortEdge(longEdge: Int): Int = maxOf(Math.rint(longEdge / aspect).toInt(), 1)

    var lo = 1        // always fits
    var hi = width    // never fits
    while (lo + 1 < hi) {
        val mid = (lo + hi) / 2
        if (fits(mid, shortEdge(mid))) lo = mid else hi = mid
    }
    return ImageSize(lo, shortEdge(lo))
}

private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.domain.geometry.ModelImageSizeTest"`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/geometry/ModelImageSize.kt \
        app/src/test/kotlin/com/storyteller/domain/geometry/ModelImageSizeTest.kt
git commit -m "feat: compute the size Claude actually sees an image at"
```

---

### Task 2: Carry the upload's real dimensions, and upload at exactly that size

The upload's pixel dimensions are the coordinate space for the whole protocol.
They are known for certain in exactly one place — `Downscale`, which has the
bitmap in hand — and needed in another, `PageReaderImpl`, which has only bytes.
Re-deriving them there would mean a `BitmapFactory` decode in the `data` layer and
would break on any test fixture that is not a real JPEG. Carry them instead.

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/domain/model/PageImage.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/capture/Downscale.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/capture/DownscaleTest.kt`

**Interfaces:**
- Consumes: `modelVisibleSize`, `visualTokens`, `ImageSize`, `STANDARD_MAX_EDGE`, `STANDARD_MAX_VISUAL_TOKENS` (Task 1).
- Produces: `PageImage(bytes, mimeType, displayBytes = bytes, width: Int = 0, height: Int = 0)` where `width`/`height` describe **`bytes`**, not `displayBytes`; `downscaleToPageImage(jpeg, rotationDegrees, maxEdge, maxTokens, quality)` always populating them.

- [ ] **Step 1: Add the dimensions to `PageImage`**

Append two parameters, both defaulting to `0`. Defaults rather than required
parameters because four test call sites (`BubbleCropTest`, `ReaderScreenTest`,
`CaptureScreenTest`, `DiagnosticWriterImplTest`) construct a `PageImage` purely as
a byte carrier and have no meaningful dimensions to state — forcing a number there
invites a wrong one. The absent case is caught loudly in Task 3 instead.

```kotlin
/**
 * A captured page.
 *
 * [bytes] is downscaled and re-encoded for upload — sized to exactly what the
 * model sees, see `modelVisibleSize` — and is what the parse cache keys on.
 * [displayBytes] is the original capture, kept ONLY so the reader can crop a
 * bubble out of it: a bubble filling a fifth of the page is a few hundred px in
 * the upload copy, which is soft blown up across a phone screen. When nothing was
 * downscaled the two are the same array.
 *
 * [width] and [height] are the pixel dimensions of [bytes] — NOT of
 * [displayBytes], and not of the original capture. They are the coordinate space
 * the model reports pixel bounding boxes in, so they are carried from the one
 * place that knows them for certain rather than re-derived from the bytes by each
 * consumer. `0` means "not set", which is a programming error anywhere the image
 * reaches the vision call.
 *
 * Deliberately NOT a data class: it wraps ByteArrays, so a generated equals would
 * compare array identity and mislead.
 */
class PageImage(
    val bytes: ByteArray,
    val mimeType: String,
    val displayBytes: ByteArray = bytes,
    val width: Int = 0,
    val height: Int = 0,
)
```

- [ ] **Step 2: Write the failing tests**

Append to `DownscaleTest.kt`:

```kotlin
    @Test fun `the upload copy is sized to what the model will see`() {
        // A 3000x4000 capture: 1568 on the long edge would be 1176x1568, which
        // costs 2072 visual tokens against a 1568 budget and would be resized
        // server-side, moving every pixel coordinate we get back.
        val image = downscaleToPageImage(jpeg(3000, 4000), rotationDegrees = 0)
        val expected = modelVisibleSize(3000, 4000)

        assertEquals(expected.width to expected.height, decodedSize(image.bytes))
    }

    @Test fun `the carried dimensions describe the upload bytes`() {
        val image = downscaleToPageImage(jpeg(3000, 4000), rotationDegrees = 0)
        assertEquals(decodedSize(image.bytes), image.width to image.height)
    }

    @Test fun `the carried dimensions are upright for a rotated capture`() {
        // Downscale rotates AFTER scaling, so the encoded image is the transpose of
        // the computed target. The dimensions must describe what was actually
        // encoded, or the prompt states the coordinate space the wrong way round
        // and every returned box is transposed.
        val image = downscaleToPageImage(jpeg(4000, 3000), rotationDegrees = 90)
        assertEquals(decodedSize(image.bytes), image.width to image.height)
        assertTrue("a rotated landscape capture should encode portrait", image.height > image.width)
    }

    @Test fun `the carried dimensions are set even when no resize was needed`() {
        val image = downscaleToPageImage(jpeg(200, 200), rotationDegrees = 0)
        assertEquals(200 to 200, image.width to image.height)
    }

    @Test fun `the upload copy never needs a server-side resize`() {
        for (source in listOf(jpeg(3000, 4000), jpeg(4000, 3000), jpeg(1021, 1568))) {
            val image = downscaleToPageImage(source, rotationDegrees = 0)
            assertTrue(
                "upload costs ${visualTokens(image.width, image.height)} visual tokens",
                visualTokens(image.width, image.height) <= STANDARD_MAX_VISUAL_TOKENS,
            )
            assertTrue(maxOf(image.width, image.height) <= STANDARD_MAX_EDGE)
        }
    }

    @Test fun `the display copy keeps its full resolution when upright`() {
        // displayBytes exists so bubble crops are sharp; shrinking the upload must
        // not shrink it too.
        val image = downscaleToPageImage(jpeg(3000, 4000), rotationDegrees = 0)
        assertEquals(3000 to 4000, decodedSize(image.displayBytes))
    }

    @Test fun `the display copy keeps its full resolution when rotated`() {
        val image = downscaleToPageImage(jpeg(4000, 3000), rotationDegrees = 90)
        assertEquals(3000 to 4000, decodedSize(image.displayBytes))
    }
```

Add a `decodedSize` helper if the file has none, following the file's existing
helper style:

```kotlin
    private fun decodedSize(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }
```

Add whatever imports the file lacks (`modelVisibleSize`, `visualTokens`,
`STANDARD_MAX_EDGE`, `STANDARD_MAX_VISUAL_TOKENS`, `BitmapFactory`, `assertTrue`).

- [ ] **Step 3: Run them to make sure they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.capture.DownscaleTest"`
Expected: FAIL — the upload comes back 1176x1568 rather than 952x1270, and
`image.width` is 0.

- [ ] **Step 4: Change the resize target**

In `Downscale.kt`, delete `MAX_LONG_EDGE_PX` and replace the single `maxEdge`
parameter with the two tier limits, so Stage B becomes a call-site change:

```kotlin
fun downscaleToPageImage(
    jpeg: ByteArray,
    rotationDegrees: Int = 0,
    maxEdge: Int = STANDARD_MAX_EDGE,
    maxTokens: Int = STANDARD_MAX_VISUAL_TOKENS,
    quality: Int = JPEG_QUALITY,
): PageImage {
```

After decoding bounds, compute the target and a single `alreadyModelSized` flag —
it replaces every remaining use of the old `longEdge <= maxEdge` test, including
the one in the `displayBytes` decision at the bottom of the function:

```kotlin
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)

    // The target is computed on PRE-rotation bounds, and rotation below may
    // transpose it. That is safe: both of Claude's limits are symmetric under
    // transposition (pinned by ModelImageSizeTest). The dimensions we report are
    // read off the final bitmap regardless, so they describe what was encoded.
    val target = modelVisibleSize(bounds.outWidth, bounds.outHeight, maxEdge, maxTokens)
    val alreadyModelSized = target.width == bounds.outWidth && target.height == bounds.outHeight

    // Nothing to do only if it is both already model-sized AND upright.
    if (alreadyModelSized && rotationDegrees == 0) {
        return PageImage(jpeg, "image/jpeg", width = bounds.outWidth, height = bounds.outHeight)
    }
```

Drive `inSampleSize` from the target's **long** edge. Using `target.width` would be
wrong for a portrait page — 3000x4000 targets 952x1270, and sampling against 952
halves past the target and then scales back up, discarding resolution:

```kotlin
    val targetLongEdge = maxOf(target.width, target.height)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = generateSequence(1) { it * 2 }.first { longEdge / (it * 2) < targetLongEdge }
    }
    val decoded = requireNonNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts))
```

Replace the `scale`-based `createScaledBitmap` with an exact resize to `target`:

```kotlin
    val scaled = if (decoded.width == target.width && decoded.height == target.height) {
        decoded
    } else {
        Bitmap.createScaledBitmap(decoded, target.width, target.height, true)
    }
```

Leave the rotation block and the recycling exactly as they are. Then capture the
final dimensions from the bitmap that was actually encoded, before it is recycled,
and use `alreadyModelSized` in the `displayBytes` decision:

```kotlin
    val encodedWidth = upright.width
    val encodedHeight = upright.height

    val out = ByteArrayOutputStream()
    upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
    // ... existing recycling, unchanged ...

    val displayBytes = when {
        rotationDegrees == 0 -> jpeg
        alreadyModelSized -> encoded
        else -> rotatedFullResolutionOrNull(jpeg, rotationDegrees, quality) ?: encoded
    }
    return PageImage(encoded, "image/jpeg", displayBytes, encodedWidth, encodedHeight)
```

Update the KDoc on `downscaleToPageImage`: the upload copy is now the model-visible
size, not "1568 px on the long edge". Say *why* it is exactly what Claude sees —
because Task 3 marks the image `oversized_image: "error"`, so a mismatch is a 400
rather than a silent resize — rather than asserting it flatly.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.capture.DownscaleTest"`
Expected: PASS, including the file's pre-existing rotation, display-copy and
compression tests. Those pre-existing tests are the guard on this rewrite: if one
of them now asserts a 1568 long edge, update the expected number to
`modelVisibleSize`, but do **not** weaken what it asserts.

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. `VisionEval` and `VisionEvalSelfTest` call `downscaleToPageImage`
and must still compile against the new signature.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/model/PageImage.kt \
        app/src/main/kotlin/com/storyteller/ui/capture/Downscale.kt \
        app/src/test/kotlin/com/storyteller/ui/capture/DownscaleTest.kt
git commit -m "feat: upload the page at exactly the size the model sees it"
```

---

### Task 3: Ask for pixel coordinates

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/data/local/Entities.kt:7`
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageSchema.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageReaderImpl.kt`
- Modify: `app/src/test/kotlin/com/storyteller/domain/Fakes.kt:17`
- Test: `app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt`

**Interfaces:**
- Consumes: `PageImage.width` / `PageImage.height` (Task 2). `modelVisibleSize` is deliberately **not** used here — the uploaded bytes are already the model-visible size, so the image's own carried dimensions are the coordinate space.
- Produces: `fun pageInstruction(width: Int, height: Int): String` replacing `val PAGE_INSTRUCTION`; `BoundsDto(x1, y1, x2, y2)` in pixels; `PARSE_VERSION = 5`.

- [ ] **Step 1: Bump the parse version FIRST**

`app/src/main/kotlin/com/storyteller/data/local/Entities.kt:7`:

```kotlin
const val PARSE_VERSION = 5
```

Do this before anything else. Every page already read is cached under version 4;
without the bump, re-reading a page returns the old parse and the whole task
measures nothing. This is the single easiest way to waste a day on this iteration.

- [ ] **Step 2: Give the shared fake real dimensions**

`app/src/test/kotlin/com/storyteller/domain/Fakes.kt:17` currently reads
`PageImage(byteArrayOf(1, 2, 3), "image/jpeg")` — three bytes that no decoder can
read. Every reader test builds on it, so it must state a coordinate space:

```kotlin
/**
 * The bytes are not a decodable JPEG and do not need to be — nothing in the read
 * path decodes them. The dimensions are what matters: they are the coordinate
 * space the model's pixel bounds are normalised against, so they are stated here
 * rather than derived. 893x1372 is what a scanner page actually uploads at.
 */
fun pageImage() = PageImage(byteArrayOf(1, 2, 3), "image/jpeg", width = 893, height = 1372)
```

`PageReaderImplTest.kt:127`'s `image(vararg bytes: Byte)` helper is used for
cache-key tests where dimensions are irrelevant to what is asserted, but it now
flows into a request that requires them. Give it the same defaults:

```kotlin
    private fun image(vararg bytes: Byte) = PageImage(bytes, "image/jpeg", width = 893, height = 1372)
```

Leave `DiagnosticWriterImplTest`, `CaptureScreenTest`, `BubbleCropTest` and
`ReaderScreenTest` alone — none of them reaches the vision call, and `0` is the
honest value for a byte carrier with no upload identity.

- [ ] **Step 3: Write the failing tests**

Add to `PageReaderImplTest.kt`, following the file's existing `enqueueTextBlock`
helper. `pageImage()` is 893x1372, so the arithmetic is exact and can be asserted
directly rather than as a range:

```kotlin
    @Test fun `pixel bounds are normalised against the uploaded image`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":0,"y1":0,"x2":893,"y2":1372}}
        ]}""")

        val b = reader().read(pageImage()).getOrThrow().units[0].bounds!!
        assertEquals(0f, b.left, 0.0001f)
        assertEquals(0f, b.top, 0.0001f)
        assertEquals(1f, b.right, 0.0001f)
        assertEquals(1f, b.bottom, 0.0001f)
    }

    @Test fun `a mid-page pixel box divides by the uploaded dimensions`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":223.25,"y1":343,"x2":669.75,"y2":1029}}
        ]}""")

        val b = reader().read(pageImage()).getOrThrow().units[0].bounds!!
        assertEquals(0.25f, b.left, 0.0001f)
        assertEquals(0.25f, b.top, 0.0001f)
        assertEquals(0.75f, b.right, 0.0001f)
        assertEquals(0.75f, b.bottom, 0.0001f)
    }

    @Test fun `a box wider than the image is rejected, not clamped`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":0,"y1":0,"x2":999999,"y2":10}}
        ]}""")
        assertNull(
            "an out-of-image box must be rejected",
            reader().read(pageImage()).getOrThrow().units[0].bounds,
        )
    }

    @Test fun `a negative pixel coordinate is rejected`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":-5,"y1":0,"x2":100,"y2":50}}
        ]}""")
        assertNull(reader().read(pageImage()).getOrThrow().units[0].bounds)
    }

    @Test fun `an inverted box is rejected`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":100,"y1":0,"x2":10,"y2":50}}
        ]}""")
        assertNull(reader().read(pageImage()).getOrThrow().units[0].bounds)
    }

    @Test fun `a zero-area box is rejected`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":100,"y1":50,"x2":100,"y2":50}}
        ]}""")
        assertNull(reader().read(pageImage()).getOrThrow().units[0].bounds)
    }

    /**
     * Every comparison against NaN is false, so a NaN coordinate would pass all
     * three range checks and produce a NaN BoundingBox that fails silently far
     * downstream. kotlinx rejects a bare NaN literal by default, but an overflowing
     * exponent parses to Infinity, and the guard is one cheap call.
     */
    @Test fun `a non-finite coordinate is rejected`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":0,"y1":0,"x2":1e40,"y2":50}}
        ]}""")
        assertNull(reader().read(pageImage()).getOrThrow().units[0].bounds)
    }

    /**
     * An image with no dimensions is OUR bug, not model inaccuracy, and must not be
     * mistaken for it: silently rejecting every box would look exactly like the
     * failure this whole iteration is trying to measure.
     */
    @Test fun `an image with no dimensions fails the read loudly`() = runTest {
        enqueueTextBlock("""{"units":[]}""")
        val result = reader().read(PageImage(byteArrayOf(1, 2, 3), "image/jpeg"))
        assertTrue("expected a failed read", result.isFailure)
    }

    @Test fun `the prompt states the image dimensions in pixels`() {
        val instruction = pageInstruction(893, 1372)
        assertTrue(instruction.contains("893"))
        assertTrue(instruction.contains("1372"))
        assertTrue("must ask for pixels", instruction.contains("pixel", ignoreCase = true))
        assertFalse(
            "must not still ask for fractions",
            instruction.contains("fraction", ignoreCase = true),
        )
    }

    @Test fun `the request marks oversized images as an error rather than resizing`() = runTest {
        enqueueTextBlock("""{"units":[]}""")
        reader().read(pageImage())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("oversized_image"))
        assertTrue(body.contains("\"error\""))
    }

    /**
     * A page cached under the old fraction protocol must not be served to the new
     * one: v4 rows hold numbers in a different unit entirely.
     */
    @Test fun `a v4 cached parse is not reused under v5`() = runTest {
        val img = pageImage()
        db.parsedPageDao().upsert(
            ParsedPageEntity(
                sha256(img.bytes),
                """{"units":[{"speaker":"Stale","text":"OLD","bounds":null}]}""",
                System.currentTimeMillis(),
                parseVersion = 4,
            ),
        )
        enqueueTextBlock("""{"units":[{"speaker":"Fresh","text":"NEW","bounds":null}]}""")

        val page = reader().read(img).getOrThrow()
        assertEquals("Fresh", page.units[0].speaker)
    }
```

- [ ] **Step 4: Run them to make sure they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.data.page.PageReaderImplTest"`
Expected: FAIL — `Unresolved reference: pageInstruction`, and the bounds tests fail
against the fraction-based DTO.

- [ ] **Step 5: Rewrite the schema and instruction**

In `PageSchema.kt`, change the `bounds` object's properties from
`left/top/right/bottom` to `x1/y1/x2/y2`, keeping `"type": "number"` on each (the
vendor's own worked example returns a fractional pixel, `653.5`, so integers would
make a valid response unparseable), and keeping the `anyOf [..., null]` shape and
`additionalProperties: false`. Update the file's header KDoc: the 0..1 bound it
describes no longer exists, and coordinates are rejected rather than clamped.

Replace `val PAGE_INSTRUCTION` with:

```kotlin
/**
 * Absolute pixel coordinates, not fractions.
 *
 * Anthropic's coordinate guidance is explicit that Claude "works best with
 * absolute pixel coordinates" and "does not work well when you ask for normalized
 * coordinates". This app asked for fractions between 0 and 1 through parse
 * versions 1-4, and every bubble box it measured was wrong — see
 * docs/issues/2026-08-31-bubble-box-accuracy-measured.md §4.
 *
 * [width] and [height] are the dimensions of the image actually being sent, which
 * Downscale has already sized to what Claude sees. So the pixels Claude reports
 * are pixels of the image we hold, and normalising is a plain division.
 */
fun pageInstruction(width: Int, height: Int): String = """
    This is a photograph of one page from a children's storybook or graphic novel.
    The image is exactly $width pixels wide and $height pixels tall.

    Return every speech unit on the page, in reading order. A speech unit is one
    continuous piece of dialogue or narration.

    For each unit:
    - Set speaker to the character who says it. Use "Narrator" for description or
      narration not attributed to a character. If you cannot tell who is speaking,
      use "Narrator".
    - Use the character's name exactly as it appears on the page.
    - Reproduce the text verbatim. Do not merge units, split units, translate, or
      correct spelling.
    - Set bounds to the box enclosing that unit's speech bubble, as absolute pixel
      coordinates in this $width x $height image: x1 and y1 are the top-left corner,
      x2 and y2 the bottom-right, measured from the top-left of the image. Use null
      if you cannot locate it.

    Also return characters: one entry per distinct character who speaks on this
    page. Do not include the narrator.
    - Set name to exactly the speaker string you used in units.

    Ignore page numbers, running heads, publisher marks, and any text that is part
    of the artwork rather than something to be read aloud.
""".trimIndent()
```

- [ ] **Step 6: Rewrite the request and the parse**

In `PageReaderImpl.kt`:

```kotlin
@Serializable
private data class BoundsDto(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
```

At the top of `readOrThrow`, before the cache lookup, refuse an image with no
coordinate space. This is the loud half of reject-don't-clamp: a missing dimension
is our bug and must not be able to imitate model inaccuracy.

```kotlin
        require(image.width > 0 && image.height > 0) {
            "PageImage reached the vision call with no dimensions (${image.width}x${image.height}); " +
                "the pixel bounds the model returns cannot be normalised without them"
        }
```

Add `transformations` to the image content block, as a sibling of `source`:

```kotlin
                                put("type", "image")
                                putJsonObject("source") { /* unchanged */ }
                                putJsonObject("transformations") {
                                    // A resize server-side would move every pixel
                                    // coordinate we get back. Fail loudly instead:
                                    // the 400 names the size that would have fitted.
                                    put("oversized_image", "error")
                                }
```

and use `put("text", pageInstruction(image.width, image.height))`.

Replace `BoundsDto.toDomain()`:

```kotlin
    /**
     * Pixels in, fractions out. The domain model stays normalised so `cropRect`,
     * `BubbleCrop` and the reader are untouched by this change.
     *
     * Still reject rather than clamp: a box outside the image means the model did
     * not locate the bubble, and clamping collapses it to something that looks
     * plausible. The reader's text fallback is the honest outcome.
     */
    private fun BoundsDto.toDomain(width: Int, height: Int): BoundingBox? {
        // NaN defeats every comparison below — all of them are false for NaN — so
        // it must be excluded first or a NaN box would pass validation intact.
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return null
        if (x1 < 0f || y1 < 0f || x2 > width.toFloat() || y2 > height.toFloat()) return null
        if (x2 <= x1 || y2 <= y1) return null
        return BoundingBox(x1 / width, y1 / height, x2 / width, y2 / height)
    }
```

Thread the dimensions through: `PageDto.toDomain(width, height)`, called as
`page.toDomain(image.width, image.height)` at both the cache-hit and fresh-parse
sites. Note that the cached payload is the raw pixel DTO, so a cache hit normalises
against the same dimensions — which is correct, because the cache key is a hash of
the exact upload bytes.

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. Pre-existing `PageReaderImplTest` cases that assert fraction-shaped
bounds must be **converted to pixels**, not deleted — they cover real validation
behaviour. Work through every response fixture in the file, not only the ones the
new tests touch: any `"bounds":{"left":…}` payload silently parses to `bounds: null`
under the new DTO because the test's `Json` is configured with
`ignoreUnknownKeys = true`, so a stale fixture will not fail loudly — it will
quietly assert nothing. Grep the file for `left` before declaring this step done.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data/page/ \
        app/src/main/kotlin/com/storyteller/data/local/Entities.kt \
        app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt \
        app/src/test/kotlin/com/storyteller/domain/Fakes.kt
git commit -m "feat: ask for absolute pixel boxes instead of the format that does not work"
```

---

### Task 4: Commit the measurement harness, and write down the measurement we never recorded

**Files:**
- Create: `scripts/measure_boxes.py`
- Create: `scripts/fixtures/box-measurements.txt` (the tool's own output on both bundles)
- Modify: `.gitignore`
- Modify: `docs/issues/2026-08-31-bubble-box-accuracy-measured.md`

**Interfaces:**
- Consumes: a pulled bundle directory.
- Produces: `python scripts/measure_boxes.py <bundle-dir>` printing centre error, size ratio, IoU at three padding assumptions, and the per-axis affine fit.

Every number quoted in the issue document came from throwaway scripts in a temp
directory. Three stages compared through three ad-hoc scripts is not a comparison.

- [ ] **Step 1: Write the tool**

Port the measurement used on bundles `page-1788205074358` and
`page-1788215961215`: run Windows `Windows.Media.Ocr` over `page-upload.jpg` via a
PowerShell shim, cluster words spatially (single-link, gap `0.035 * max(W, H)`),
match each cluster to a unit in `response.json` by `difflib` ratio over alpha-only
text with a 0.35 floor, then report:

- per-unit OCR extent, model box, centre dx/dy, IoU
- mean and sd of dx/dy
- size ratio (model width / OCR text width) per unit
- mean IoU against text, text+30%, text+60%
- least-squares fit `true = a*model + b` per axis with R²

Read the coordinate space from `meta.json`'s `uploadWidth`/`uploadHeight` rather
than assuming a tier, and print which coordinate convention the bundle's
`response.json` uses — v4 bundles hold fractions, v5 hold pixels, and the tool must
handle both to compare across the iteration. Print the tier the numbers assume.
Take `--serial` and `--out` like `diagnostics.py` does.

Follow the repo's Python convention: wrap `main()` in a `try/except` that writes
the full traceback to `error.txt` beside the script, then exits 1.

- [ ] **Step 2: Keep that runtime output out of the tree**

The convention above writes into `scripts/`, which is source. Add to `.gitignore`:

```gitignore
# Runtime traceback dropped beside a failing script by the repo's Python convention.
scripts/error.txt
```

- [ ] **Step 3: Capture the tool's output as the regression fixture**

Run it on both existing bundles and commit exactly what it prints:

```bash
python scripts/measure_boxes.py diagnostics-pulled/page-1788205074358 > scripts/fixtures/box-measurements.txt
python scripts/measure_boxes.py diagnostics-pulled/page-1788215961215 >> scripts/fixtures/box-measurements.txt
```

The CameraX bundle must reproduce the numbers the issue document already
records — **mean IoU ≈ 0.007, x slope ≈ 0.703, y slope ≈ 0.707**. Those are
published in §2 and are the real acceptance criterion; if the tool disagrees with
them, the tool is wrong, and nothing measured afterwards means anything.

The scanner bundle's numbers are **not** in the document — they were reported in
conversation and never written down, which is exactly the gap this task exists to
close. Do not pin an expected value for it from memory. Whatever the tool prints
for `page-1788215961215`, once the CameraX bundle has validated the tool, is the
measurement of record.

- [ ] **Step 4: Backfill the scanner measurement into the issue document**

The document jumps from the CameraX measurement to conclusions that depend on a
scanner measurement it never states. Add a section for `page-1788215961215` in the
same table shape as §2, using the committed fixture output, and note whether the
ML Kit scanner change removed the scale distortion. This also discharges the
documentation task left over from the scanner plan.

- [ ] **Step 5: Commit**

```bash
git add scripts/measure_boxes.py scripts/fixtures/box-measurements.txt .gitignore \
        docs/issues/2026-08-31-bubble-box-accuracy-measured.md
git commit -m "feat: make the box measurement a committed tool instead of throwaway scripts"
```

---

### Task 5: Measure Stage A on device

**Files:**
- Modify: `docs/issues/2026-08-31-bubble-box-accuracy-measured.md` (a new section for the Stage A result)

- [ ] **Step 1: Install onto a cleared app**

Clearing app data is the reliable way to guarantee the parse is fresh. The
`PARSE_VERSION` bump should be sufficient on its own, and a new photograph hashes
differently anyway, but neither is a guarantee worth resting the measurement on.

```bash
./gradlew installDebug
adb -s 59251JEBF12416 shell pm clear com.storyteller
adb -s 59251JEBF12416 shell monkey -p com.storyteller -c android.intent.category.LAUNCHER 1
```

Scan **the same graphic-novel page** used for the two existing bundles. Same page,
or the comparison is against a different subject.

- [ ] **Step 2: Confirm a real vision call happened**

```bash
python scripts/diagnostics.py pull --serial 59251JEBF12416
```

A bundle is only written on a real vision call. If no new bundle appears, the parse
came from cache — stop and fix that before reading anything into the result.

- [ ] **Step 3: Confirm no server-side resize happened**

`meta.json`'s `uploadWidth`/`uploadHeight` must equal `modelVisibleSize` of the
capture. This check is only meaningful because `DiagnosticWriter` decodes those
dimensions from the bytes independently rather than echoing `PageImage.width` —
keep it that way.

If the read failed with a 400 naming a resize, the `transformations` guard did its
job and Task 1's arithmetic is wrong. Fix Task 1. **Do not remove the guard** —
removing it converts a caught error back into the silent offset this iteration
exists to eliminate.

- [ ] **Step 4: Measure**

```bash
python scripts/measure_boxes.py diagnostics-pulled/<newest-bundle>
```

- [ ] **Step 5: Record the result honestly, whichever way it goes**

Add a Stage A section to the issue document with the same table shape as §2 and
Task 4's scanner section, and a plain verdict against the 0.5 IoU stop condition.

**Do not report a partial improvement as success.** The bar is a usable crop, not a
better number. The three outcomes and what each means:

- **IoU ≥ 0.5** — Stage A worked. The OCR split in §6 is unnecessary; say so and close the issue.
- **IoU materially up but below 0.5** — the protocol was part of it. Record the residual's structure (offset? scale? noise?) and proceed to Stage B.
- **IoU roughly unchanged** — the protocol was not the problem. Stage B is unlikely to help either; go to Stage C, and update §4's correction to say the documented protocol was tried and did not rescue it.

One measurement is n=1. Whatever the residual looks like, do not hardcode a
correction for it in this iteration — that was the standing caution from the
scanner measurement and it has not expired.

```bash
git add docs/issues/2026-08-31-bubble-box-accuracy-measured.md
git commit -m "docs: record what pixel coordinates actually changed"
```

---

## Self-Review

**Spec coverage.** Stage A's five points map to Tasks 1-2 (pre-resize and the
coordinate space), Task 3 (pixel coordinates, normalise in our code,
`transformations`, parse-version bump) and Task 5 (re-measure). The harness and the
missing scanner measurement are Task 4.

**Placeholder scan.** No TBD/TODO. Task 4's tool is described by behaviour rather
than full source — the port is mechanical from two working reference runs, and its
acceptance criterion is reproducing published numbers, which is the stronger
contract.

**Type consistency.** `ImageSize`/`modelVisibleSize`/`visualTokens`/`paddedSize`/
`STANDARD_MAX_*` produced in Task 1, consumed in Task 2. `PageImage.width`/`.height`
produced in Task 2, consumed in Task 3. `pageInstruction(width, height)` and
`BoundsDto(x1,y1,x2,y2)` produced and consumed within Task 3.

**Three risks worth naming.**

`Downscale.kt`'s `inSampleSize` path is fiddly and Task 2 rewrites its arithmetic —
the previous revision of this plan got it wrong. The pre-existing rotation,
display-copy and compression tests are the guard, and they must keep passing on
their original assertions.

`transformations` is a real behaviour change: if the resize maths is wrong the app
fails a read outright instead of returning a bad box. That is deliberate. A loud
failure during a measurement iteration beats another silent offset — but it should
not survive into a build a child uses without the maths being proven on device
first, and Task 5 Step 3 is where that proof happens.

`PageImage.width`/`height` default to `0`, so a future construction site can forget
them. The `require` in Task 3 Step 6 turns that into an immediate, named failure
rather than a page whose bubbles all quietly disappear — but the default is a
tradeoff taken knowingly, to avoid forcing invented dimensions onto four test call
sites that have no upload identity at all.
