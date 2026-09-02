# Bubble Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the transcript-list reader with one that shows a single speech bubble at a time, cropped from the page photo, tapped to hear it.

**Architecture:** The page photo reaches the reader for the first time, carried through `PipelineState` the way badges were. Each speech unit's `bounds` crops a bubble out of it using the existing `CropGeometry`; a unit with no usable box renders its text large instead. Previous/next buttons move through units in reading order. The badge machinery is deleted.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, Retrofit + kotlinx.serialization, Media3, JUnit4 + Robolectric.

**Spec:** `docs/superpowers/specs/2026-08-26-storyteller-bubble-reader-design.md`

## Global Constraints

- **Model id is `claude-haiku-4-5`.** Never send `thinking`, `cache_control`, or an `effort` field inside `output_config`.
- Structured-outputs schema rules: every object needs `additionalProperties: false`; numeric ranges are unsupported, so the 0..1 bound on coordinates is stated in the instruction and clamped on the client.
- **`domain` contains NO Android imports.** `java.io.File` is fine.
- **`ui` must NEVER import `data`** — an architecture test enforces this.
- **`CancellationException` is caught FIRST and handled deliberately** at every suspend boundary, because repositories raise spurious ones. In `ReadingPipelineImpl` the established discriminator is `if (currentCoroutineContext().isActive)`.
- **No new Gradle dependencies.** No `material-icons-extended` — add local vector drawables (`app/src/main/res/drawable/ic_photo_camera.xml` is the pattern).
- **Robolectric is pinned to `sdk = 34`.** Do NOT bump it. Do NOT set `buildToolsVersion`. Do NOT modify any Gradle file.
- **Every failure path lands on the same fallback: render the unit's text.** The child must always be able to see and hear the line.
- **Commit as the repo-local identity** (`git config --local user.email` must be the `users.noreply.github.com` address). **Never add a `Co-Authored-By` trailer.**
- **Gradle on this machine:** run in the FOREGROUND with a generous timeout, never backgrounded. On `BindException: Address already in use` or `IOException: Unable to delete directory`, run `./gradlew --stop`, delete `app/build/test-results` and `app/build/reports` for the second, and retry — both are environmental. Verify with `testDebugUnitTest`; `./gradlew test` fails on a pre-existing release-variant manifest issue that is not yours to fix.

---

### Task 1: Measure the bubble boxes

This runs FIRST and its output is a number, not a feature. Everything after it assumes the model returns usable `units[].bounds`; the badge failure is evidence that assumption may not hold, and one page of measurement is cheaper than a rewritten reader built on sand.

**Files:**
- Modify: `app/src/test/kotlin/com/storyteller/evals/VisionEval.kt`
- Modify: `evals/README.md`
- Test: `app/src/test/kotlin/com/storyteller/evals/CharacterBoxEvalTest.kt`

**Interfaces:**
- Consumes: `iou(a: BoundingBox, b: BoundingBox): Float` and `SpeechUnit.bounds` (both already exist).
- Produces: bubble-box scoring in the eval report; no production API.

- [ ] **Step 1: Fix the two flaws that would flatter the number**

`VisionEval.scoreCharacterBoxes` matches names with `.trim().lowercase()` while production matches case-**sensitively** (the badge map is keyed on the trimmed, case-preserved `ParsedCharacter.name` and looked up by the trimmed, case-preserved `SpeechUnit.speaker`), and it has no narrator filter while production excludes the narrator. Both make the eval report better than the app behaves.

Change its matching to `.trim()` only, and skip expected characters for which `isNarrator(name)` is true.

- [ ] **Step 2: Write the failing test for bubble scoring**

In `CharacterBoxEvalTest.kt`:

```kotlin
    @Test fun `scores a bubble box against its hand-drawn box`() {
        val units = listOf(
            SpeechUnit(0, "Bear", "Hello", BoundingBox(0.10f, 0.10f, 0.50f, 0.30f)),
            SpeechUnit(1, "Mouse", "Hi", null),
        )
        val expected = listOf(
            ExpectedBubble(0, BoundingBox(0.10f, 0.10f, 0.50f, 0.30f)),
            ExpectedBubble(1, BoundingBox(0.60f, 0.60f, 0.90f, 0.80f)),
        )

        val score = scoreBubbleBoxes(units, expected)

        assertEquals(2, score.expected)
        assertEquals(1, score.boxed)
        assertEquals(1.0f, score.meanIou, 0.001f)
    }

    @Test fun `a bubble box that misses entirely scores zero`() {
        val units = listOf(SpeechUnit(0, "Bear", "Hello", BoundingBox(0.0f, 0.0f, 0.2f, 0.2f)))
        val expected = listOf(ExpectedBubble(0, BoundingBox(0.8f, 0.8f, 1.0f, 1.0f)))

        assertEquals(0.0f, scoreBubbleBoxes(units, expected).meanIou, 0.001f)
    }
```

- [ ] **Step 3: Run it to see it fail**

Run: `./gradlew testDebugUnitTest --tests '*CharacterBoxEvalTest*' --no-daemon`
Expected: FAIL — `scoreBubbleBoxes` and `ExpectedBubble` unresolved.

- [ ] **Step 4: Implement the scoring**

In `VisionEval.kt`:

```kotlin
/** One hand-drawn bubble box in a fixture's expected JSON, keyed by unit index. */
@Serializable
data class ExpectedBubble(val index: Int, val bounds: BoundingBox)

data class BubbleScore(val expected: Int, val boxed: Int, val meanIou: Float)

/**
 * Scores the model's per-unit bubble boxes against hand-drawn ones.
 *
 * [expected] counts every unit a human drew a box for; [boxed] counts those the
 * model also returned a box for; [meanIou] averages IoU over that overlap only,
 * so a model that returns few boxes cannot raise its own mean by abstaining -
 * the gap between expected and boxed is what exposes that.
 */
fun scoreBubbleBoxes(units: List<SpeechUnit>, expected: List<ExpectedBubble>): BubbleScore {
    val actual = units.associateBy { it.index }
    var boxed = 0
    var total = 0f
    expected.forEach { e ->
        val bounds = actual[e.index]?.bounds
        if (bounds != null) {
            boxed++
            total += iou(bounds, e.bounds)
        }
    }
    return BubbleScore(expected.size, boxed, if (boxed == 0) 0f else total / boxed)
}
```

Add `bubbles: List<ExpectedBubble> = emptyList()` to the eval's `Expected` fixture class, and print the bubble score per fixture alongside the character score.

- [ ] **Step 5: Run it to see it pass**

Run: `./gradlew testDebugUnitTest --tests '*CharacterBoxEvalTest*' --no-daemon`
Expected: PASS.

- [ ] **Step 6: Document the format and the stop condition**

In `evals/expected/README.md`, document the `bubbles` block:

```json
"bubbles": [
  { "index": 0, "bounds": { "left": 0.08, "top": 0.11, "right": 0.52, "bottom": 0.29 } }
]
```

In `evals/README.md`, state — without softening — that if mean bubble IoU comes out **below 0.5**, stop and report rather than proceeding: below that a crop starts framing the wrong thing, and the bubble reader's only fallback is rendering text, which if it fired on most units would mean the bubble reader is a text reader wearing a photograph.

- [ ] **Step 7: Run the eval if fixtures exist; report honestly if they do not**

Run: `STORYTELLER_EVAL=1 ANTHROPIC_API_KEY=<key> ./gradlew testDebugUnitTest --tests '*VisionEval*' --no-daemon`

`evals/fixtures/` is empty in this checkout, and the key must be a real shell environment variable — the eval does not read `local.properties`. If there are no fixtures, **report the measurement as BLOCKED. Do not fabricate fixtures, invent an IoU figure, or soften the stop condition.** An honest "built but unmeasured" is the correct outcome; a fabricated pass would retire the gate this task exists to provide.

- [ ] **Step 8: Commit**

```bash
git add app/src/test/kotlin/com/storyteller/evals evals/README.md evals/expected/README.md
git commit -m "test: score the model's speech-bubble boxes"
```

---

### Task 2: Keep the original capture for display

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/domain/model/PageImage.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/capture/Downscale.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/capture/DownscaleTest.kt`

**Interfaces:**
- Produces: `PageImage(bytes: ByteArray, mimeType: String, displayBytes: ByteArray)` — `bytes` unchanged in meaning (1568 px, uploaded, cache-keyed), `displayBytes` the full-resolution original.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test fun `keeps the original bytes for display while downscaling for upload`() {
        val original = jpeg(4000, 3000)

        val page = downscaleToPageImage(original)

        val (uploadW, _) = sizeOf(page.bytes)
        val (displayW, _) = sizeOf(page.displayBytes)
        assertEquals(1568, uploadW)
        assertEquals(4000, displayW)
    }

    @Test fun `a small capture shares one array rather than copying it`() {
        val original = jpeg(800, 600)

        val page = downscaleToPageImage(original)

        // Nothing was downscaled, so display and upload are the same pixels;
        // holding two copies of an identical array would waste memory for nothing.
        assertSame(page.bytes, page.displayBytes)
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew testDebugUnitTest --tests '*DownscaleTest*' --no-daemon`
Expected: FAIL — `displayBytes` unresolved.

- [ ] **Step 3: Implement**

`PageImage.kt`:

```kotlin
/**
 * A captured page.
 *
 * [bytes] is downscaled and re-encoded for upload — 1568 px on the long edge,
 * where Haiku stops gaining detail — and is what the parse cache keys on.
 * [displayBytes] is the original capture, kept ONLY so the reader can crop a
 * bubble out of it: a bubble filling a fifth of the page is about 300 px in the
 * upload copy, which is soft blown up across a phone screen. When nothing was
 * downscaled the two are the same array.
 *
 * Deliberately NOT a data class: it wraps ByteArrays, so a generated equals
 * would compare array identity and mislead.
 */
class PageImage(
    val bytes: ByteArray,
    val mimeType: String,
    val displayBytes: ByteArray = bytes,
)
```

In `Downscale.kt`, the early-return path already returns the original untouched — pass it as both. On the downscaling path, pass the original as `displayBytes`:

```kotlin
    if (longEdge <= maxEdge && rotationDegrees == 0) return PageImage(jpeg, "image/jpeg")
    ...
    return PageImage(out.toByteArray(), "image/jpeg", displayBytes = jpeg)
```

Note the rotation case: when the image is small enough but rotated, the pixels are re-encoded, so `displayBytes` must be the ROTATED output, not the raw sensor JPEG — otherwise the reader would crop from a sideways page. Pass `out.toByteArray()` for both in that branch.

- [ ] **Step 4: Run it to see it pass**

Run: `./gradlew testDebugUnitTest --tests '*DownscaleTest*' --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/model/PageImage.kt app/src/main/kotlin/com/storyteller/ui/capture app/src/test/kotlin/com/storyteller/ui/capture
git commit -m "feat: keep the full-resolution capture for display"
```

---

### Task 3: Drop `characters` from the vision call

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageSchema.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageReaderImpl.kt`
- Modify: `app/src/main/kotlin/com/storyteller/domain/model/SpeechUnit.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/local/Entities.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt`

**Interfaces:**
- Produces: `ParsedPage(units: List<SpeechUnit>)` — the `characters` field is gone, `ParsedCharacter` is deleted. `PARSE_VERSION = 3`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test fun `does not ask the model for characters`() {
        val schema = PAGE_SCHEMA.toString()

        assertFalse("characters should no longer be requested", schema.contains("characters"))
        assertFalse("emoji should no longer be requested", schema.contains("emoji"))
    }

    @Test fun `parses a page of units`() = runTest {
        enqueueTextBlock("""{"units":[{"speaker":"Bear","text":"Hello","bounds":null}]}""")

        val page = reader.read(pageImage()).getOrThrow()

        assertEquals(1, page.units.size)
        assertEquals("Bear", page.units.single().speaker)
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew testDebugUnitTest --tests '*PageReaderImplTest*' --no-daemon`
Expected: FAIL — the schema still contains `characters`.

- [ ] **Step 3: Implement**

In `PageSchema.kt`, delete the whole `characters` property from `properties`, remove `"characters"` from the top-level `required` array, and delete the paragraph of `PAGE_INSTRUCTION` that asks for characters, their boxes and their emoji.

In `SpeechUnit.kt`, delete `ParsedCharacter` and reduce `ParsedPage`:

```kotlin
/** One page's parse: what is said, in reading order. */
data class ParsedPage(val units: List<SpeechUnit>)
```

In `PageReaderImpl.kt`, delete `CharacterDto`, drop `characters` from `PageDto`, and reduce the mapping to `ParsedPage(units = ...)`.

In `Entities.kt`, bump `PARSE_VERSION` to `3` — the cached payload shape changed again, so rows written by the previous parser must read as stale and be re-fetched. Its comment already explains the mechanism; no migration is needed, because `parseVersion` is a value in an existing column.

- [ ] **Step 4: Run it to see it pass**

Run: `./gradlew testDebugUnitTest --tests '*PageReaderImplTest*' --no-daemon`
Expected: PASS. Other modules will not compile until Task 4 removes their `characters` consumers; that is expected and Task 4 fixes it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data/page app/src/main/kotlin/com/storyteller/domain/model app/src/main/kotlin/com/storyteller/data/local app/src/test/kotlin/com/storyteller/data/page
git commit -m "feat: stop asking the vision call for characters"
```

---

### Task 4: Delete the badge machinery

The largest deletion in the plan. It removes reviewed, working code, and a database column, on purpose: badges failed in use, and keeping them costs a column, a repository, a Hilt binding and their tests while paying nothing.

**Files:**
- Delete: `app/src/main/kotlin/com/storyteller/data/badge/BadgeRepositoryImpl.kt`
- Delete: `app/src/main/kotlin/com/storyteller/domain/model/Badge.kt`
- Delete: `app/src/main/kotlin/com/storyteller/ui/reader/BadgeIcon.kt`
- Delete: `app/src/test/kotlin/com/storyteller/data/badge/BadgeRepositoryImplTest.kt`
- Modify: `app/src/main/kotlin/com/storyteller/domain/repository/Repositories.kt`, `app/src/main/kotlin/com/storyteller/data/local/Entities.kt`, `Daos.kt`, `StorytellerDatabase.kt`, `app/src/main/kotlin/com/storyteller/di/RepositoryModule.kt`, `DatabaseModule.kt`, `app/src/main/kotlin/com/storyteller/ui/reader/ReaderScreen.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/local/ParseVersionAndBadgePathDaoTest.kt`

**Interfaces:**
- Produces: `CharacterVoiceEntity(character: String, voiceId: String)` — no `badgePath`. `MIGRATION_3_4`. `BadgeRepository`, `Badge`, `BadgeIcon` and `CurrentSpeakerHeader` no longer exist.

**Keep:** `CropGeometry.kt` and its tests — Task 6 uses them.

- [ ] **Step 1: Write the failing migration test**

Rename `ParseVersionAndBadgePathDaoTest.kt` to `ParseVersionDaoTest.kt`, drop its `setBadgePath` test, and add:

```kotlin
    @Test fun `character voices survive the badge column being dropped`() = runTest {
        val dao = db.voiceDao()
        dao.upsert(CharacterVoiceEntity("Bear", "voice-1"))

        assertEquals("voice-1", dao.find("Bear")?.voiceId)
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew testDebugUnitTest --tests '*ParseVersionDaoTest*' --no-daemon`
Expected: FAIL — `CharacterVoiceEntity` still requires `badgePath`.

- [ ] **Step 3: Delete the code**

Delete the four files listed above. Then:

- `Repositories.kt`: remove the `BadgeRepository` interface and the `Badge`/`ParsedCharacter` imports.
- `Entities.kt`: `CharacterVoiceEntity` loses `badgePath`.
- `Daos.kt`: remove `VoiceDao.setBadgePath` and its kdoc.
- `RepositoryModule.kt` / `DatabaseModule.kt`: remove the `badgeRepository` provider and any badge-directory provider.
- `ReaderScreen.kt`: remove `CurrentSpeakerHeader`, the `BadgeIcon` call in `LineRow`, and the badge import. Task 8 rewrites this screen; here, just make it compile without badges.

- [ ] **Step 4: Write the migration**

SQLite cannot drop a column in older engines, and Room validates the live schema against the entity — an extra column is a mismatch that throws on open. So the table is recreated. In `StorytellerDatabase.kt`, bump to `version = 4` and add:

```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Recreate without badgePath: Room compares the live table against the
        // entity, so leaving a column the entity no longer declares fails
        // validation on open. Column order and types must match exactly what
        // Room generates for CharacterVoiceEntity.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `character_voice_new` " +
                "(`character` TEXT NOT NULL, `voiceId` TEXT NOT NULL, PRIMARY KEY(`character`))",
        )
        db.execSQL(
            "INSERT INTO `character_voice_new` (`character`, `voiceId`) " +
                "SELECT `character`, `voiceId` FROM `character_voice`",
        )
        db.execSQL("DROP TABLE `character_voice`")
        db.execSQL("ALTER TABLE `character_voice_new` RENAME TO `character_voice`")
    }
}
```

Register it: `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`. **All three must remain** — dropping an earlier one breaks upgrades from that version and no test would catch it.

Verify the `CREATE TABLE` against Room's own generated SQL in `app/build/generated/ksp/debug/kotlin/com/storyteller/data/local/StorytellerDatabase_Impl.kt` after building. No test executes migrations in this project (`exportSchema = false`, no `room.schemaLocation`), so that comparison IS the verification.

- [ ] **Step 5: Run the suite**

Run: `./gradlew testDebugUnitTest --no-daemon`
Expected: PASS. Badge tests are gone; nothing else should fail.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: delete the badge machinery"
```

---

### Task 5: Carry the page image to the reader

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/domain/model/PipelineState.kt`
- Modify: `app/src/main/kotlin/com/storyteller/domain/ReadingPipelineImpl.kt`
- Modify: `app/src/main/kotlin/com/storyteller/di/PipelineModule.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/ReadingPipelineImplTest.kt`

**Interfaces:**
- Produces: `PipelineState.Preparing(units, ready, image: PageImage?)` and `PipelineState.Ready(units, image: PageImage?)` — the `badges` field is replaced, not supplemented. `ReadingPipelineImpl(pageReader, voices, audio, scope)` — the `badges` constructor parameter is gone.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test fun `ready carries the page image the units came from`() = runTest {
        val image = pageImage()
        val pipeline = pipelineWith(units = 2)

        pipeline.start(image)
        advanceUntilIdle()

        val ready = states.filterIsInstance<PipelineState.Ready>().last()
        assertSame(image, ready.image)
    }

    @Test fun `preparing carries the page image too`() = runTest {
        val image = pageImage()
        val pipeline = pipelineWith(units = 3)

        pipeline.start(image)
        advanceUntilIdle()

        states.filterIsInstance<PipelineState.Preparing>().forEach { assertSame(image, it.image) }
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew testDebugUnitTest --tests '*ReadingPipelineImplTest*' --no-daemon`
Expected: FAIL — `image` unresolved on both states.

- [ ] **Step 3: Implement**

In `PipelineState.kt`:

```kotlin
    /**
     * [units] is every unit on the page, so the reader can show the whole page
     * while synthesis fills it in. [ready] is cumulative and ordered by index;
     * consumers must diff, not replay. [image] is the page those units were read
     * from, and is what the reader crops bubbles out of.
     */
    data class Preparing(
        val units: List<SpeechUnit>,
        val ready: List<PreparedUnit>,
        val image: PageImage? = null,
    ) : PipelineState {
        val total: Int get() = units.size
    }

    data class Ready(
        val units: List<PreparedUnit>,
        val image: PageImage? = null,
    ) : PipelineState
```

In `ReadingPipelineImpl.kt`: drop the `badges: BadgeRepository` constructor parameter, the `pageBadges` field, and the `badgesFor` block with its try/catch. Keep the image already held in `lastImage` and pass it into every `Preparing` and `Ready`. `retry()` reuses `lastImage` as it already does. Clear nothing extra in `reset()` — `lastImage` is already cleared there.

Update `PipelineModule.kt` to stop injecting `BadgeRepository`.

- [ ] **Step 4: Run it to see it pass**

Run: `./gradlew testDebugUnitTest --tests '*ReadingPipelineImplTest*' --no-daemon`
Expected: PASS, **including the pre-existing exactly-3 in-flight concurrency test, which must still read exactly 3.** Also confirm the epoch-guard and cancellation tests still pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain app/src/main/kotlin/com/storyteller/di app/src/test/kotlin/com/storyteller/domain
git commit -m "feat: carry the page image through the pipeline"
```

---

### Task 6: Crop a bubble from the page

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/ui/reader/BubbleCrop.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/BubbleCropTest.kt`

**Interfaces:**
- Consumes: `cropRect(bounds, imageWidth, imageHeight, padFraction, minEdgeFraction): PixelRect?` from `com.storyteller.data.badge.CropGeometry`, and `PageImage.displayBytes`.
- Produces: `fun cropBubble(image: PageImage, bounds: BoundingBox?): Bitmap?` — null whenever a bubble cannot be produced, for ANY reason.

**Note on layering:** `CropGeometry` currently lives in `data`, and `ui` must never import `data`. Move `CropGeometry.kt` and its test to `com.storyteller.domain.geometry` as part of this task — it is pure arithmetic with no Android or data dependency, and both `ui` and `data` may depend on `domain`. Update the imports in `BadgeRepositoryImpl`'s former callers (none remain after Task 4) and in `CropGeometryTest`.

- [ ] **Step 1: Write the failing test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class BubbleCropTest {

    private fun page(width: Int = 800, height: Int = 600): PageImage {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return PageImage(out.toByteArray(), "image/jpeg")
    }

    @Test fun `crops the region a bubble occupies`() {
        val bitmap = cropBubble(page(), BoundingBox(0.25f, 0.25f, 0.75f, 0.75f))

        assertNotNull(bitmap)
        // 0.5 x 0.5 of 800x600 = 400x300, plus 4% padding of the larger edge.
        assertTrue("expected a crop smaller than the page", bitmap!!.width < 800)
        assertTrue(bitmap.width > 300)
    }

    @Test fun `returns null when there is no box`() {
        assertNull(cropBubble(page(), null))
    }

    @Test fun `returns null for an implausible box`() {
        assertNull(cropBubble(page(), BoundingBox(0.5f, 0.5f, 0.5f, 0.5f)))
    }

    @Test fun `returns null rather than throwing on undecodable bytes`() {
        val broken = PageImage(ByteArray(8) { 0 }, "image/jpeg")

        assertNull(cropBubble(broken, BoundingBox(0.1f, 0.1f, 0.4f, 0.4f)))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew testDebugUnitTest --tests '*BubbleCropTest*' --no-daemon`
Expected: FAIL — `cropBubble` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.storyteller.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.storyteller.domain.geometry.cropRect
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage

private const val TAG = "BubbleCrop"

/**
 * 4%, against a badge's 10%: a badge wanted breathing room around a portrait,
 * a bubble wants only enough margin not to clip its own outline.
 */
private const val BUBBLE_PAD = 0.04f

/**
 * The bubble [bounds] encloses, cut from the page's full-resolution copy.
 *
 * Returns null for every failure — no box, an implausible box, undecodable
 * bytes, a crop that throws — because the reader's single fallback is rendering
 * the unit's text, and a bubble that cannot be produced must reach that path
 * rather than an error screen.
 */
fun cropBubble(image: PageImage, bounds: BoundingBox?): Bitmap? {
    if (bounds == null) return null
    val full = BitmapFactory.decodeByteArray(image.displayBytes, 0, image.displayBytes.size)
        ?: return null
    return try {
        val rect = cropRect(bounds, full.width, full.height, padFraction = BUBBLE_PAD)
            ?: return null
        Bitmap.createBitmap(full, rect.left, rect.top, rect.width, rect.height)
    } catch (e: Throwable) {
        Log.w(TAG, "could not crop a bubble; the reader will show its text", e)
        null
    } finally {
        full.recycle()
    }
}
```

Note the recycle: `full` is recycled in `finally`, and `Bitmap.createBitmap` copies when a sub-rect is requested, so the returned bitmap survives. If the rect covers the whole image `createBitmap` may return the source — guard with `if (cropped === full) cropped.copy(full.config, false) else cropped` before recycling.

- [ ] **Step 4: Run it to see it pass**

Run: `./gradlew testDebugUnitTest --tests '*BubbleCropTest*' --no-daemon`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller app/src/test/kotlin/com/storyteller
git commit -m "feat: crop a speech bubble out of the page"
```

---

### Task 7: One unit at a time in the reader's state

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderUiState.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderViewModel.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Produces: `ReaderUiState.Line(index: Int, speaker: String, text: String, bounds: BoundingBox?, audioReady: Boolean)` and `ReaderUiState.Playing(lines: List<Line>, current: Int, image: PageImage?, playback: PlaybackState, mode: ReadingMode, playingIndex: Int?)`; `ReaderViewModel.onNext()`, `onPrevious()`, `onBubbleTapped()`.

`Line` keeps its name and loses `badge` and `tappable`. It GAINS `bounds`, copied from the `SpeechUnit` it was built from — Task 8's `Bubble` needs it to crop, and carrying it on the line it belongs to is simpler than plumbing the unit list separately.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test fun `starts on the first unit`() = runTest {
        val vm = readerViewModel(RecordingPlayer(), mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Ready(preparedUnits(3), pageImage()))
        advanceUntilIdle()

        assertEquals(0, (vm.uiState.value as ReaderUiState.Playing).current)
    }

    @Test fun `next and previous move one unit and stop at the ends`() = runTest {
        val vm = readerViewModel(RecordingPlayer(), mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Ready(preparedUnits(2), pageImage()))
        advanceUntilIdle()

        vm.onPrevious()
        assertEquals("must not go before the first unit", 0, current(vm))

        vm.onNext(); assertEquals(1, current(vm))
        vm.onNext(); assertEquals("must not go past the last unit", 1, current(vm))
    }

    @Test fun `tapping the bubble plays the unit on screen`() = runTest {
        val player = RecordingPlayer()
        val vm = readerViewModel(player, mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Ready(preparedUnits(3), pageImage()))
        advanceUntilIdle()

        vm.onNext()
        vm.onBubbleTapped()
        advanceUntilIdle()

        assertEquals(listOf(1), player.played.map { it.single().unit.index })
    }

    @Test fun `auto advances to the unit the player moved to`() = runTest {
        val player = RecordingPlayer()
        val vm = readerViewModel(player, mode = ReadingMode.Auto)
        pipeline.emit(PipelineState.Ready(preparedUnits(3), pageImage()))
        advanceUntilIdle()

        player.state.value = PlaybackState.Playing(2)
        advanceUntilIdle()

        assertEquals(2, current(vm))
    }

    private fun current(vm: ReaderViewModel) = (vm.uiState.value as ReaderUiState.Playing).current
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew testDebugUnitTest --tests '*ReaderViewModelTest*' --no-daemon`
Expected: FAIL — `current`, `onNext`, `onPrevious`, `onBubbleTapped` unresolved.

- [ ] **Step 3: Implement**

In `ReaderUiState.kt`, `Line` becomes:

```kotlin
    data class Line(
        val index: Int,
        val speaker: String,
        val text: String,
        /** The unit's speech-bubble box, or null when the model could not locate one. */
        val bounds: BoundingBox?,
        val audioReady: Boolean,
    )
```

and `Playing` gains `current: Int` and `image: PageImage?`. Build each `Line`'s `bounds` from the `SpeechUnit` it comes from in `playingState`.

In `ReaderViewModel.kt`:

```kotlin
    private var current = 0

    fun onNext() = moveTo(current + 1)

    fun onPrevious() = moveTo(current - 1)

    /** Bounded at both ends: there is no wrap-around, and no unit off the page. */
    private fun moveTo(index: Int) {
        val state = _uiState.value as? ReaderUiState.Playing ?: return
        val bounded = index.coerceIn(0, (state.lines.size - 1).coerceAtLeast(0))
        if (bounded == current) return
        current = bounded
        _uiState.value = state.copy(current = bounded)
    }

    /** Plays whichever unit is on screen; the one-unit playlist path is unchanged. */
    fun onBubbleTapped() {
        if (mode != ReadingMode.Tap) return
        val prepared = lastReady.firstOrNull { it.unit.index == current } ?: return
        playingIndex = current
        player.play(listOf(prepared))
        player.endOfPage()
        (_uiState.value as? ReaderUiState.Playing)?.let { _uiState.value = it.copy(playingIndex = current) }
    }
```

In the player-state collector, Auto already sets `playingIndex` from `playlistIndex`; also move `current` there, so Auto turns the page as it reads:

```kotlin
                if (mode == ReadingMode.Auto && state is PlaybackState.Playing) {
                    playingIndex = state.playlistIndex
                    current = state.playlistIndex
                }
```

Reset `current = 0` wherever `queued` is reset (the `Idle`/`Reading` branch and the `Failed` branch).

- [ ] **Step 4: Run it to see it pass**

Run: `./gradlew testDebugUnitTest --tests '*ReaderViewModelTest*' --no-daemon`
Expected: PASS, including the pre-existing cumulative-queue, reset, mode-ordering and `onCleared` tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/reader app/src/test/kotlin/com/storyteller/ui/reader
git commit -m "feat: move the reader through one unit at a time"
```

---

### Task 8: The bubble on screen

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderScreen.kt`
- Create: `app/src/main/res/drawable/ic_arrow_forward.xml`
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/ReaderScreenTest.kt`

**Interfaces:**
- Consumes: `cropBubble` (Task 6), `ReaderUiState.Playing` (Task 7).
- Produces: `@Composable internal fun Bubble(line: ReaderUiState.Line, image: PageImage?, onTap: () -> Unit)`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test fun `a unit with no box shows its words instead`() {
        compose.setContent {
            Bubble(line("Bear", "Hello there", bounds = null), image = null, onTap = {})
        }

        compose.onNodeWithText("Hello there").assertIsDisplayed()
        compose.onNodeWithText("Bear").assertIsDisplayed()
    }

    @Test fun `tapping the bubble reports it`() {
        var taps = 0
        compose.setContent {
            Bubble(line("Bear", "Hello there", bounds = null), image = null, onTap = { taps++ })
        }

        compose.onNodeWithContentDescription("Read this line").performClick()

        assertEquals(1, taps)
    }

    @Test fun `previous is disabled on the first unit and next on the last`() {
        compose.setContent {
            ReaderContent(
                playing(listOf(line("Bear", "One"), line("Mouse", "Two")), current = 0),
                onRetry = {}, onBack = {}, onNext = {}, onPrevious = {}, onBubbleTapped = {},
            )
        }

        compose.onNodeWithContentDescription("Previous line").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next line").assertIsEnabled()
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew testDebugUnitTest --tests '*ReaderScreenTest*' --no-daemon`
Expected: FAIL — `Bubble` unresolved.

- [ ] **Step 3: Add the forward arrow**

`app/src/main/res/drawable/ic_arrow_forward.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,4l-1.41,1.41L16.17,11H4v2h12.17l-5.58,5.59L12,20l8,-8z" />
</vector>
```

- [ ] **Step 4: Implement the screen**

Replace the `LazyColumn` in the `Playing` branch with the bubble, the speaker name and the two buttons:

```kotlin
/**
 * One unit, filling the screen. The crop is remembered per (unit, image) so
 * scrolling back and forth does not re-decode the page each time.
 *
 * When there is no bubble to show — no box, an implausible box, an undecodable
 * page — the words are rendered instead. That is the single fallback every
 * failure path in this screen lands on, so the child can always read and hear
 * the line whatever the model returned.
 */
@Composable
internal fun Bubble(line: ReaderUiState.Line, image: PageImage?, onTap: () -> Unit) {
    val bitmap = remember(line.index, image) {
        image?.let { cropBubble(it, line.bounds) }?.asImageBitmap()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Read this line", enabled = line.audioReady, onClick = onTap)
            .semantics { contentDescription = "Read this line" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(line.speaker, style = MaterialTheme.typography.labelLarge)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = line.text,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(line.text, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
```

Under it, a row of two `IconButton`s using `ic_arrow_back` and `ic_arrow_forward`, with `contentDescription` of `"Previous line"` and `"Next line"`, `enabled = current > 0` and `enabled = current < lines.size - 1`.

`ReaderUiState.Line` already carries `bounds` from Task 7, so the screen crops from the line in front of it.

- [ ] **Step 5: Run it to see it pass**

Run: `./gradlew testDebugUnitTest --tests '*ReaderScreenTest*' --no-daemon`
Expected: PASS.

- [ ] **Step 6: Run the whole suite and commit**

```bash
./gradlew testDebugUnitTest --no-daemon
git add -A
git commit -m "feat: show one speech bubble at a time"
```

---

## Manual verification

Needs a real device, real keys in `local.properties`, and a graphic novel.

1. Photograph a page with speech bubbles. The reader shows the first bubble, cropped from the page, with the speaker's name.
2. The crop actually contains that unit's bubble — not a neighbour's, not clipped.
3. Tapping the bubble reads that line.
4. Next and previous move one unit at a time and stop at the ends.
5. In Auto mode, the page turns itself as each line finishes.
6. Photograph a prose page with no bubbles: every unit renders as text, and still reads aloud.
7. A page where the model returns some boxes and not others mixes bubbles and text without complaint.
8. The bubble is legibly sharp — this is what Task 2's full-resolution copy is for.

Record failures as bugs rather than fixing them inline.
