# Reading Modes and Speaker Badges Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the reader a selectable auto/tap reading mode and a badge beside each line showing who is speaking.

**Architecture:** The vision call gains a page-level `characters` array carrying an optional emoji and an optional "character as drawn" box. A new badge repository crops that box out of the page photo once per character and pins it in `character_voice`, exactly as that table already pins a voice. Reading mode is a Room-backed setting read by the reader; neither `ReadingPipeline` nor `PagePlayer` changes behaviour.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, Retrofit + kotlinx.serialization, Media3, JUnit4 + Robolectric.

**Spec:** `docs/superpowers/specs/2026-08-25-storyteller-reading-modes-design.md`

## Global Constraints

These apply to every task. Values are copied verbatim from the spec and from iteration 1's constraints.

- **Model id is `claude-haiku-4-5`.** Do not change it.
- **Never send `thinking`, `cache_control`, or an `effort` field inside `output_config`.** Sending any of them is a defect, not a tuning knob.
- **Structured-outputs schema rules:** every object needs `additionalProperties: false`; numeric ranges are unsupported, so 0..1 bounds are stated in the instruction and clamped on the client.
- **Page images are downscaled to 1568 px on the long edge** before upload.
- **Persisted files that cost money or carry identity go in `filesDir`, never `cacheDir`.** This covers badge crops as well as audio.
- **No new Gradle dependencies.** Use Room for settings, not DataStore. Do not add `material-icons-extended`; add local vector drawables instead.
- **`domain` must contain no Android imports.** `java.io.File` is fine — `PreparedUnit` already uses it.
- **`ui` must never import `data`.** Hilt is the only place the two halves meet. There is a test that enforces this and it must stay green.
- **Robolectric is pinned to `sdk = 34`** in `app/src/test/resources/robolectric.properties`. Do not bump it; SDK 36 needs Java 21 and this toolchain is Java 17.
- **`buildToolsVersion` is deliberately unset.** Do not pin it.
- **Commit as the repo-local identity and never add a `Co-Authored-By` trailer.** Verify with `git config --local user.email` before the first commit.
- **Gradle on this machine:** run in the foreground with a generous timeout. On `BindException` or a stale file lock, run `./gradlew --stop` and retry, or use `--no-daemon`. A long build is not a hang.

## Spec deviation resolved here

The spec says rows "become tappable individually, as their audio lands" and also that "a row whose audio is not ready renders visibly disabled and is inert." Both cannot hold with today's `PipelineState.Preparing`, which carries only the cumulative `ready` list and a total — an unready row has no text to draw.

**Resolution: `Preparing` is extended to carry every parsed unit.** The child sees the whole page immediately, greyed out, filling in as synthesis lands. The pipeline's flow, concurrency and epoch guards are untouched; only the state's shape changes. Task 1 makes this change and Task 7 populates it.

---

### Task 1: Domain models for characters, badges and mode

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/domain/model/SpeechUnit.kt`
- Create: `app/src/main/kotlin/com/storyteller/domain/model/Badge.kt`
- Create: `app/src/main/kotlin/com/storyteller/domain/model/ReadingMode.kt`
- Modify: `app/src/main/kotlin/com/storyteller/domain/model/PipelineState.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/model/BadgeTest.kt`

**Interfaces:**
- Produces: `ParsedCharacter(name: String, emoji: String?, bounds: BoundingBox?)`; `ParsedPage(units: List<SpeechUnit>, characters: List<ParsedCharacter>)`; `Badge` sealed interface with `Badge.Image(file: File)`, `Badge.Emoji(value: String)`, `Badge.None`; `ReadingMode { Auto, Tap }`; `PipelineState.Preparing(units: List<SpeechUnit>, ready: List<PreparedUnit>)` with a `total: Int get() = units.size`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/storyteller/domain/model/BadgeTest.kt`:

```kotlin
package com.storyteller.domain.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeTest {

    @Test fun `preparing reports total from the units it carries`() {
        val units = listOf(
            SpeechUnit(0, "Bear", "Hello", null),
            SpeechUnit(1, NARRATOR, "The end", null),
        )
        assertEquals(2, PipelineState.Preparing(units, ready = emptyList()).total)
    }

    @Test fun `badge variants are distinguishable`() {
        val image: Badge = Badge.Image(File("/tmp/bear.jpg"))
        val emoji: Badge = Badge.Emoji("🐻")
        assertEquals(File("/tmp/bear.jpg"), (image as Badge.Image).file)
        assertEquals("🐻", (emoji as Badge.Emoji).value)
        assertEquals(Badge.None, Badge.None)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*BadgeTest*' --no-daemon`
Expected: FAIL — compilation error, `Badge` and the two-argument `Preparing` do not exist.

- [ ] **Step 3: Write minimal implementation**

Append to `SpeechUnit.kt`:

```kotlin
/** A character on the page, with whatever identity the model could supply. */
data class ParsedCharacter(
    val name: String,
    val emoji: String?,
    /** The character AS DRAWN — not the speech bubble. Normalized 0..1. */
    val bounds: BoundingBox?,
)

/** One page's parse: what is said, and who is on the page. */
data class ParsedPage(
    val units: List<SpeechUnit>,
    val characters: List<ParsedCharacter>,
)
```

Create `Badge.kt`:

```kotlin
package com.storyteller.domain.model

import java.io.File

/**
 * What renders beside a line. Resolution order is crop, then emoji, then blank;
 * see BadgeRepository. The narrator is always [None].
 *
 * [Image] carries java.io.File rather than anything Android: domain holds no
 * Android imports, and PreparedUnit already sets this precedent.
 */
sealed interface Badge {
    data class Image(val file: File) : Badge
    data class Emoji(val value: String) : Badge
    data object None : Badge
}
```

Create `ReadingMode.kt`:

```kotlin
package com.storyteller.domain.model

/** Auto reads the page through; Tap reads only the line the child touches. */
enum class ReadingMode { Auto, Tap }
```

In `PipelineState.kt`, replace the `Preparing` line:

```kotlin
    /**
     * [units] is every unit on the page, so the reader can show the whole page
     * greyed out while synthesis fills it in. [ready] is cumulative and ordered
     * by index; consumers must diff, not replay.
     */
    data class Preparing(val units: List<SpeechUnit>, val ready: List<PreparedUnit>) : PipelineState {
        val total: Int get() = units.size
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*BadgeTest*' --no-daemon`
Expected: PASS. Other modules will not compile yet — that is expected and Task 7 fixes the pipeline. To keep the tree buildable, also update the two `Preparing(...)` construction sites in `ReadingPipelineImpl.kt` now, passing `units` and `ready` (Task 7 revisits them), and the `is PipelineState.Preparing ->` branch in `ReaderViewModel.kt` to use `state.units.size` where it used `state.total`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/model app/src/test/kotlin/com/storyteller/domain/model app/src/main/kotlin/com/storyteller/domain/ReadingPipelineImpl.kt app/src/main/kotlin/com/storyteller/ui/reader/ReaderViewModel.kt
git commit -m "feat: add character, badge and reading-mode domain models"
```

---

### Task 2: Crop geometry

Pure arithmetic, deliberately separated from bitmap work so it is JVM-testable with no Robolectric and no image decoding.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/data/badge/CropGeometry.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/badge/CropGeometryTest.kt`

**Interfaces:**
- Consumes: `BoundingBox` (Task 1, pre-existing).
- Produces: `data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)`; `fun cropRect(bounds: BoundingBox, imageWidth: Int, imageHeight: Int, padFraction: Float = 0.10f, minEdgeFraction: Float = 0.02f): PixelRect?` — returns null when the box is implausible.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/storyteller/data/badge/CropGeometryTest.kt`:

```kotlin
package com.storyteller.data.badge

import com.storyteller.domain.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropGeometryTest {

    @Test fun `pads by ten percent of the larger edge`() {
        // 0.2 wide x 0.1 tall on a 1000x1000 image = 200x100 px; larger edge 200,
        // so pad is 20px on every side: 160,180 origin and 240x140 size.
        val r = cropRect(BoundingBox(0.2f, 0.3f, 0.4f, 0.4f), 1000, 1000)!!
        assertEquals(160, r.left)
        assertEquals(180, r.top)
        assertEquals(240, r.width)
        assertEquals(140, r.height)
    }

    @Test fun `clamps padding at the image edge instead of going negative`() {
        val r = cropRect(BoundingBox(0f, 0f, 0.2f, 0.2f), 1000, 1000)!!
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        // Right edge extends to 200 + 20 padding = 220; left padding is clipped.
        assertEquals(220, r.width)
        assertEquals(220, r.height)
    }

    @Test fun `rejects a box with zero area`() {
        assertNull(cropRect(BoundingBox(0.5f, 0.5f, 0.5f, 0.5f), 1000, 1000))
    }

    @Test fun `rejects an inverted box`() {
        assertNull(cropRect(BoundingBox(0.6f, 0.6f, 0.2f, 0.2f), 1000, 1000))
    }

    @Test fun `rejects a sliver thinner than two percent of the image`() {
        // 0.2 tall is fine, but 0.01 wide is under the 2% floor.
        assertNull(cropRect(BoundingBox(0.50f, 0.3f, 0.51f, 0.5f), 1000, 1000))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*CropGeometryTest*' --no-daemon`
Expected: FAIL — `cropRect` unresolved.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/kotlin/com/storyteller/data/badge/CropGeometry.kt`:

```kotlin
package com.storyteller.data.badge

import com.storyteller.domain.model.BoundingBox
import kotlin.math.roundToInt

data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Converts a normalized character box into a padded, clamped pixel rect.
 *
 * Padding is a fraction of the box's LARGER edge, applied equally on all four
 * sides, because a box drawn tight to a character reads as a claustrophobic crop
 * at badge size. Returns null when the box is implausible — zero or negative
 * area, or an edge under [minEdgeFraction] of the image — since a sliver crop is
 * worse than no badge, and the caller falls back to the emoji.
 */
fun cropRect(
    bounds: BoundingBox,
    imageWidth: Int,
    imageHeight: Int,
    padFraction: Float = 0.10f,
    minEdgeFraction: Float = 0.02f,
): PixelRect? {
    if (imageWidth <= 0 || imageHeight <= 0) return null

    val w = bounds.right - bounds.left
    val h = bounds.bottom - bounds.top
    if (w <= 0f || h <= 0f) return null
    if (w < minEdgeFraction || h < minEdgeFraction) return null

    val pxWidth = w * imageWidth
    val pxHeight = h * imageHeight
    val pad = maxOf(pxWidth, pxHeight) * padFraction

    val left = ((bounds.left * imageWidth) - pad).coerceAtLeast(0f)
    val top = ((bounds.top * imageHeight) - pad).coerceAtLeast(0f)
    val right = ((bounds.right * imageWidth) + pad).coerceAtMost(imageWidth.toFloat())
    val bottom = ((bounds.bottom * imageHeight) + pad).coerceAtMost(imageHeight.toFloat())

    val width = (right - left).roundToInt()
    val height = (bottom - top).roundToInt()
    if (width <= 0 || height <= 0) return null

    return PixelRect(left.roundToInt(), top.roundToInt(), width, height)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*CropGeometryTest*' --no-daemon`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data/badge app/src/test/kotlin/com/storyteller/data/badge
git commit -m "feat: add crop geometry for character badges"
```

---

### Task 3: Schema and vision-call contract

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageSchema.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageReaderImpl.kt`
- Modify: `app/src/main/kotlin/com/storyteller/domain/repository/Repositories.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt` (exists — extend it)

**Interfaces:**
- Consumes: `ParsedCharacter`, `ParsedPage` (Task 1).
- Produces: `PageReader.read(image: PageImage): Result<ParsedPage>` — **signature change**, was `Result<List<SpeechUnit>>`.

- [ ] **Step 1: Write the failing test**

Add to `PageReaderImplTest.kt`. Match the file's existing MockWebServer setup; this test body assumes its `enqueueTextBlock(json: String)` helper — if the existing file names it differently, use that name.

```kotlin
    @Test fun `parses page-level characters alongside units`() = runTest {
        enqueueTextBlock(
            """
            {"units":[{"speaker":"Bear","text":"Hello","bounds":null}],
             "characters":[{"name":"Bear","emoji":"🐻",
                            "bounds":{"left":0.1,"top":0.1,"right":0.3,"bottom":0.4}}]}
            """.trimIndent(),
        )

        val page = reader.read(pageImage()).getOrThrow()

        assertEquals(1, page.units.size)
        assertEquals("Bear", page.characters.single().name)
        assertEquals("🐻", page.characters.single().emoji)
        assertEquals(0.3f, page.characters.single().bounds!!.right, 0.001f)
    }

    @Test fun `tolerates a page with no characters array entries`() = runTest {
        enqueueTextBlock("""{"units":[{"speaker":"Narrator","text":"Once","bounds":null}],"characters":[]}""")

        val page = reader.read(pageImage()).getOrThrow()

        assertEquals(1, page.units.size)
        assertTrue(page.characters.isEmpty())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*PageReaderImplTest*' --no-daemon`
Expected: FAIL — `read` returns `List<SpeechUnit>`, which has no `.units`.

- [ ] **Step 3: Write minimal implementation**

In `Repositories.kt`:

```kotlin
/** Reads the page, attributes speakers and identifies characters in one vision call. */
interface PageReader {
    suspend fun read(image: PageImage): Result<ParsedPage>
}
```

In `PageSchema.kt`, add a `characters` property alongside `units` inside `properties`, and add `"characters"` to the top-level `required` array:

```json
        "characters": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "name":   { "type": "string" },
              "emoji":  { "anyOf": [ { "type": "string" }, { "type": "null" } ] },
              "bounds": {
                "anyOf": [
                  {
                    "type": "object",
                    "properties": {
                      "left":   { "type": "number" },
                      "top":    { "type": "number" },
                      "right":  { "type": "number" },
                      "bottom": { "type": "number" }
                    },
                    "required": ["left", "top", "right", "bottom"],
                    "additionalProperties": false
                  },
                  { "type": "null" }
                ]
              }
            },
            "required": ["name", "emoji", "bounds"],
            "additionalProperties": false
          }
        }
```

Append to `PAGE_INSTRUCTION`:

```
    Also return characters: one entry per distinct character who speaks on this
    page. Do not include the narrator.
    - Set name to exactly the speaker string you used in units.
    - Set bounds to the box enclosing THE CHARACTER AS DRAWN — the figure itself,
      not their speech bubble — as fractions of the image between 0 and 1. Use
      null if the character is not depicted, or you cannot locate them.
    - Set emoji to a single emoji that best represents the character, or null if
      none fits.
```

In `PageReaderImpl.kt`, add DTOs and update mapping:

```kotlin
@Serializable
private data class CharacterDto(val name: String, val emoji: String?, val bounds: BoundsDto?)

@Serializable
private data class PageDto(val units: List<UnitDto>, val characters: List<CharacterDto> = emptyList())
```

Change `read` and `readOrThrow` return types to `ParsedPage`, and replace `toDomain()`:

```kotlin
    private fun BoundsDto.toDomain(): BoundingBox = BoundingBox(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f),
    )

    private fun PageDto.toDomain(): ParsedPage = ParsedPage(
        units = units.map { u ->
            ParsedUnit(speaker = u.speaker, text = u.text, bounds = u.bounds?.toDomain())
        }.toSpeechUnits(),
        characters = characters.map { c ->
            ParsedCharacter(name = c.name, emoji = c.emoji?.takeIf { it.isNotBlank() }, bounds = c.bounds?.toDomain())
        },
    )
```

The `characters` default of `emptyList()` matters: it lets an old cached JSON payload deserialize rather than throwing.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*PageReaderImplTest*' --no-daemon`
Expected: PASS. `ReadingPipelineImpl` will not compile until Task 7; to keep the tree building, change its `pageReader.read(image)` call to `.getOrElse { ... }` on `ParsedPage` and use `page.units` where it used `units`, holding `page.characters` in a local for now.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data/page app/src/main/kotlin/com/storyteller/domain app/src/test/kotlin/com/storyteller/data/page
git commit -m "feat: return page-level characters from the vision call"
```

---

### Task 4: Parse-cache versioning and the badge column

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/data/local/Entities.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/local/Daos.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/local/StorytellerDatabase.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/page/PageReaderImpl.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/local/MigrationTest.kt`

**Interfaces:**
- Produces: `ParsedPageEntity(imageHash, unitsJson, createdAt, parseVersion: Int)`; `CharacterVoiceEntity(character, voiceId, badgePath: String?)`; `const val PARSE_VERSION = 2`; `VoiceDao.setBadgePath(character: String, path: String): Int`; database `version = 2` with `MIGRATION_1_2`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/storyteller/data/local/MigrationTest.kt`:

```kotlin
package com.storyteller.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StorytellerDatabase::class.java,
    ).build()

    @After fun close() = db.close()

    @Test fun `a stale parse version is ignored so the page is re-read`() = runTest {
        val dao = db.parsedPageDao()
        dao.upsert(ParsedPageEntity("hash", "{}", 0L, parseVersion = 1))

        assertNull(dao.findCurrent("hash", PARSE_VERSION))
    }

    @Test fun `a current parse version hits`() = runTest {
        val dao = db.parsedPageDao()
        dao.upsert(ParsedPageEntity("hash", "{}", 0L, parseVersion = PARSE_VERSION))

        assertEquals("{}", dao.findCurrent("hash", PARSE_VERSION)!!.unitsJson)
    }

    @Test fun `a badge path is written only once per character`() = runTest {
        val dao = db.voiceDao()
        dao.upsert(CharacterVoiceEntity("Bear", "voice-1", badgePath = null))

        assertEquals(1, dao.setBadgePath("Bear", "/files/bear.jpg"))
        assertEquals(0, dao.setBadgePath("Bear", "/files/better-bear.jpg"))
        assertEquals("/files/bear.jpg", dao.find("Bear")!!.badgePath)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*MigrationTest*' --no-daemon`
Expected: FAIL — `parseVersion`, `badgePath`, `findCurrent` and `setBadgePath` do not exist.

- [ ] **Step 3: Write minimal implementation**

In `Entities.kt`:

```kotlin
/** Bumped whenever the cached parse payload's shape changes; older rows are misses. */
const val PARSE_VERSION = 2

@Entity(tableName = "character_voice")
data class CharacterVoiceEntity(
    @PrimaryKey val character: String,
    val voiceId: String,
    /** First sighting wins; see VoiceDao.setBadgePath. */
    val badgePath: String? = null,
)

@Entity(tableName = "parsed_page")
data class ParsedPageEntity(
    @PrimaryKey val imageHash: String,
    val unitsJson: String,
    val createdAt: Long,
    val parseVersion: Int = PARSE_VERSION,
)
```

In `Daos.kt`, add to `ParsedPageDao`:

```kotlin
    @Query("SELECT * FROM parsed_page WHERE imageHash = :hash AND parseVersion = :version")
    suspend fun findCurrent(hash: String, version: Int): ParsedPageEntity?
```

and to `VoiceDao`:

```kotlin
    /**
     * Writes the crop path only when there is not one already: first sighting
     * wins, mirroring how this table pins a voice. Returns rows updated, so the
     * caller can tell a write from a no-op.
     */
    @Query("UPDATE character_voice SET badgePath = :path WHERE character = :character AND badgePath IS NULL")
    suspend fun setBadgePath(character: String, path: String): Int
```

In `StorytellerDatabase.kt`, bump to `version = 2` and add the migration in the same file:

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE character_voice ADD COLUMN badgePath TEXT")
        // Default 1, not PARSE_VERSION: every row that already exists was written
        // by the old parser and must read as stale so it is re-fetched.
        db.execSQL("ALTER TABLE parsed_page ADD COLUMN parseVersion INTEGER NOT NULL DEFAULT 1")
    }
}
```

Register it wherever the database is built in `DatabaseModule.kt`: `.addMigrations(MIGRATION_1_2)`.

In `PageReaderImpl.kt`, change the cache read to `parsedPageDao.findCurrent(hash, PARSE_VERSION)` and the write to pass `parseVersion = PARSE_VERSION`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*MigrationTest*' --no-daemon`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data app/src/test/kotlin/com/storyteller/data/local
git commit -m "feat: version the parse cache and add a badge column"
```

---

### Task 5: Badge repository

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/domain/repository/BadgeRepository.kt` (interface — put it in `Repositories.kt` alongside the others instead if you prefer that file's convention)
- Create: `app/src/main/kotlin/com/storyteller/data/badge/BadgeRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/storyteller/di/RepositoryModule.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/badge/BadgeRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `cropRect` (Task 2), `VoiceDao.setBadgePath`/`find` (Task 4), `ParsedCharacter`, `Badge`, `PageImage`.
- Produces: `interface BadgeRepository { suspend fun badgesFor(image: PageImage, characters: List<ParsedCharacter>): Map<String, Badge> }`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/storyteller/data/badge/BadgeRepositoryImplTest.kt`:

```kotlin
package com.storyteller.data.badge

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.CharacterVoiceEntity
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.domain.model.Badge
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedCharacter
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BadgeRepositoryImplTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, StorytellerDatabase::class.java).build()
    private val repo = BadgeRepositoryImpl(db.voiceDao(), context.filesDir)

    @After fun close() = db.close()

    private fun page(): PageImage {
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return PageImage(out.toByteArray(), "image/jpeg")
    }

    @Test fun `crops a character with a usable box`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))

        val badges = repo.badgesFor(
            page(),
            listOf(ParsedCharacter("Bear", "🐻", BoundingBox(0.1f, 0.1f, 0.4f, 0.5f))),
        )

        val badge = badges.getValue("Bear")
        assertTrue("expected a crop, got $badge", badge is Badge.Image)
        assertTrue((badge as Badge.Image).file.length() > 0)
    }

    @Test fun `falls back to the emoji when there is no box`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))

        val badges = repo.badgesFor(page(), listOf(ParsedCharacter("Bear", "🐻", null)))

        assertEquals(Badge.Emoji("🐻"), badges.getValue("Bear"))
    }

    @Test fun `falls back to blank with neither box nor emoji`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))

        val badges = repo.badgesFor(page(), listOf(ParsedCharacter("Bear", null, null)))

        assertEquals(Badge.None, badges.getValue("Bear"))
    }

    @Test fun `keeps the first crop when the character is seen again`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))
        val first = repo.badgesFor(page(), listOf(ParsedCharacter("Bear", null, BoundingBox(0.1f, 0.1f, 0.4f, 0.5f))))

        val second = repo.badgesFor(page(), listOf(ParsedCharacter("Bear", null, BoundingBox(0.5f, 0.5f, 0.9f, 0.9f))))

        assertEquals(first.getValue("Bear"), second.getValue("Bear"))
    }

    @Test fun `a sliver box degrades to the emoji rather than failing`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))

        val badges = repo.badgesFor(
            page(),
            listOf(ParsedCharacter("Bear", "🐻", BoundingBox(0.5f, 0.3f, 0.505f, 0.5f))),
        )

        assertEquals(Badge.Emoji("🐻"), badges.getValue("Bear"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*BadgeRepositoryImplTest*' --no-daemon`
Expected: FAIL — `BadgeRepositoryImpl` unresolved.

- [ ] **Step 3: Write minimal implementation**

Interface, in `Repositories.kt`:

```kotlin
/**
 * Resolves what renders beside each line. Crops a character out of the page on
 * first sighting and pins it, so a character keeps one face for a whole book.
 */
interface BadgeRepository {
    suspend fun badgesFor(image: PageImage, characters: List<ParsedCharacter>): Map<String, Badge>
}
```

`app/src/main/kotlin/com/storyteller/data/badge/BadgeRepositoryImpl.kt`:

```kotlin
package com.storyteller.data.badge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.storyteller.data.local.VoiceDao
import com.storyteller.domain.model.Badge
import com.storyteller.domain.model.NARRATOR
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedCharacter
import com.storyteller.domain.repository.BadgeRepository
import java.io.File
import kotlinx.coroutines.CancellationException

private const val TAG = "BadgeRepository"
private const val QUALITY = 90

/**
 * [badgesDir] is under filesDir, never cacheDir: a purged badge would silently
 * change a character's face mid-book.
 */
class BadgeRepositoryImpl(
    private val voiceDao: VoiceDao,
    filesDir: File,
) : BadgeRepository {

    private val badgesDir = File(filesDir, "badges").apply { mkdirs() }

    override suspend fun badgesFor(
        image: PageImage,
        characters: List<ParsedCharacter>,
    ): Map<String, Badge> = characters
        .filter { it.name != NARRATOR }
        .associate { it.name to resolve(image, it) }

    /**
     * Strict fallback chain: stored crop, then a fresh crop, then the emoji, then
     * blank. Every failure degrades one step; none may propagate, because a page
     * is perfectly readable with no badges and a broken crop must not become an
     * error screen.
     */
    private suspend fun resolve(image: PageImage, character: ParsedCharacter): Badge {
        val emojiOrNone = character.emoji?.let(Badge::Emoji) ?: Badge.None
        return try {
            voiceDao.find(character.name)?.badgePath
                ?.let(::File)
                ?.takeIf { it.exists() && it.length() > 0 }
                ?.let { return Badge.Image(it) }

            val bounds = character.bounds ?: return emojiOrNone
            val file = crop(image, character.name, bounds) ?: return emojiOrNone

            // Loses the race deliberately: if another page wrote first, that crop
            // wins and this one is discarded, because first sighting wins.
            if (voiceDao.setBadgePath(character.name, file.absolutePath) == 0) {
                file.delete()
                val existing = voiceDao.find(character.name)?.badgePath?.let(::File)
                if (existing != null && existing.exists()) Badge.Image(existing) else emojiOrNone
            } else {
                Badge.Image(file)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "badge for ${character.name} failed; falling back", e)
            emojiOrNone
        }
    }

    private fun crop(
        image: PageImage,
        name: String,
        bounds: com.storyteller.domain.model.BoundingBox,
    ): File? {
        val full = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) ?: return null
        return try {
            val rect = cropRect(bounds, full.width, full.height) ?: return null
            val cropped = Bitmap.createBitmap(full, rect.left, rect.top, rect.width, rect.height)
            try {
                val out = File(badgesDir, "${name.hashCode()}.jpg")
                out.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                out.takeIf { it.length() > 0 }
            } finally {
                if (cropped !== full) cropped.recycle()
            }
        } finally {
            full.recycle()
        }
    }
}
```

Bind it in `RepositoryModule.kt` following the pattern already used for `VoiceRepositoryImpl`, providing `context.filesDir`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*BadgeRepositoryImplTest*' --no-daemon`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller app/src/test/kotlin/com/storyteller/data/badge
git commit -m "feat: crop and pin a badge per character"
```

---

### Task 6: Reading-mode setting

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/data/local/Entities.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/local/Daos.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/local/StorytellerDatabase.kt`
- Create: `app/src/main/kotlin/com/storyteller/data/settings/SettingsRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/storyteller/domain/repository/Repositories.kt`
- Modify: `app/src/main/kotlin/com/storyteller/di/RepositoryModule.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/settings/SettingsRepositoryImplTest.kt`

**Interfaces:**
- Produces: `SettingEntity(key: String, value: String)` in table `settings`; `SettingsDao` with `observe(key): Flow<SettingEntity?>` and `put(entity)`; `interface SettingsRepository { val mode: Flow<ReadingMode>; suspend fun setMode(mode: ReadingMode) }`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/storyteller/data/settings/SettingsRepositoryImplTest.kt`:

```kotlin
package com.storyteller.data.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.domain.model.ReadingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryImplTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StorytellerDatabase::class.java,
    ).build()
    private val repo = SettingsRepositoryImpl(db.settingsDao())

    @After fun close() = db.close()

    @Test fun `defaults to auto when nothing has been stored`() = runTest {
        assertEquals(ReadingMode.Auto, repo.mode.first())
    }

    @Test fun `round-trips a stored mode`() = runTest {
        repo.setMode(ReadingMode.Tap)
        assertEquals(ReadingMode.Tap, repo.mode.first())
    }

    @Test fun `an unrecognised stored value falls back to auto`() = runTest {
        db.settingsDao().put(com.storyteller.data.local.SettingEntity("reading_mode", "sideways"))
        assertEquals(ReadingMode.Auto, repo.mode.first())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*SettingsRepositoryImplTest*' --no-daemon`
Expected: FAIL — `SettingsRepositoryImpl` and `settingsDao` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `Entities.kt`:

```kotlin
/** Key-value so future settings need no migration. */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
```

In `Daos.kt`:

```kotlin
@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE key = :key")
    fun observe(key: String): Flow<SettingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SettingEntity)
}
```

In `StorytellerDatabase.kt`: add `SettingEntity::class` to `entities`, bump to `version = 3`, add `abstract fun settingsDao(): SettingsDao`, and add:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
    }
}
```

Register it too: `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`.

In `Repositories.kt`:

```kotlin
interface SettingsRepository {
    val mode: Flow<ReadingMode>
    suspend fun setMode(mode: ReadingMode)
}
```

`SettingsRepositoryImpl.kt`:

```kotlin
package com.storyteller.data.settings

import com.storyteller.data.local.SettingEntity
import com.storyteller.data.local.SettingsDao
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val KEY_MODE = "reading_mode"

class SettingsRepositoryImpl(private val dao: SettingsDao) : SettingsRepository {

    /**
     * Anything unreadable reads as Auto. A settings fault must never stop a page
     * being read, and Auto is iteration 1's behaviour, so it is the safe default.
     */
    override val mode: Flow<ReadingMode> = dao.observe(KEY_MODE).map { row ->
        ReadingMode.entries.firstOrNull { it.name == row?.value } ?: ReadingMode.Auto
    }

    override suspend fun setMode(mode: ReadingMode) = dao.put(SettingEntity(KEY_MODE, mode.name))
}
```

Bind it in `RepositoryModule.kt`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*SettingsRepositoryImplTest*' --no-daemon`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller app/src/test/kotlin/com/storyteller/data/settings
git commit -m "feat: persist the reading mode in Room"
```

---

### Task 7: Carry units and badges through the pipeline

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/domain/model/PipelineState.kt`
- Modify: `app/src/main/kotlin/com/storyteller/domain/ReadingPipelineImpl.kt`
- Modify: `app/src/main/kotlin/com/storyteller/di/PipelineModule.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/ReadingPipelineImplTest.kt` (exists — extend it)

**Interfaces:**
- Consumes: `BadgeRepository` (Task 5), `ParsedPage` (Task 3), `Preparing(units, ready)` (Task 1).
- Produces: `PipelineState.Ready(units: List<PreparedUnit>, badges: Map<String, Badge>)`; `PipelineState.Preparing(units, ready, badges)`; `ReadingPipelineImpl(pageReader, voices, audio, badges, scope)`.

- [ ] **Step 1: Write the failing test**

Add to `ReadingPipelineImplTest.kt`, using the file's existing fakes:

```kotlin
    @Test fun `preparing carries every unit so the reader can grey out the rest`() = runTest {
        val pipeline = pipelineWith(units = 3)
        pipeline.start(pageImage())
        advanceUntilIdle()

        val seen = states.filterIsInstance<PipelineState.Preparing>()
        assertTrue("expected at least one Preparing", seen.isNotEmpty())
        seen.forEach { assertEquals(3, it.units.size) }
    }

    @Test fun `ready carries the badges resolved for the page`() = runTest {
        val pipeline = pipelineWith(units = 1, badges = mapOf("Bear" to Badge.Emoji("🐻")))
        pipeline.start(pageImage())
        advanceUntilIdle()

        val ready = states.filterIsInstance<PipelineState.Ready>().last()
        assertEquals(Badge.Emoji("🐻"), ready.badges.getValue("Bear"))
    }

    @Test fun `a badge failure does not fail the page`() = runTest {
        val pipeline = pipelineWith(units = 1, badgesThrow = true)
        pipeline.start(pageImage())
        advanceUntilIdle()

        assertTrue(states.last() is PipelineState.Ready)
    }
```

Extend the test file's `pipelineWith` helper to accept `badges: Map<String, Badge> = emptyMap()` and `badgesThrow: Boolean = false`, backing it with a fake `BadgeRepository` that returns the map or throws.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*ReadingPipelineImplTest*' --no-daemon`
Expected: FAIL — `Ready` has no `badges`, and the constructor takes four arguments.

- [ ] **Step 3: Write minimal implementation**

In `PipelineState.kt`:

```kotlin
    data class Preparing(
        val units: List<SpeechUnit>,
        val ready: List<PreparedUnit>,
        val badges: Map<String, Badge> = emptyMap(),
    ) : PipelineState {
        val total: Int get() = units.size
    }

    data class Ready(
        val units: List<PreparedUnit>,
        val badges: Map<String, Badge> = emptyMap(),
    ) : PipelineState
```

In `ReadingPipelineImpl.kt`: add `private val badges: BadgeRepository` as the fourth constructor parameter, keep a `private var pageBadges: Map<String, Badge> = emptyMap()` guarded by `lock`, and in `run()` after a successful parse:

```kotlin
        val page = pageReader.read(image).getOrElse { e ->
            setState(myEpoch, PipelineState.Failed(e.toReason(FailureReason.Network), retryable = true))
            return
        }
        val units = page.units
        if (units.isEmpty()) {
            setState(myEpoch, PipelineState.Failed(FailureReason.NoTextFound, retryable = true))
            return
        }

        // Badges are cosmetic: a failure here must never cost the child the page.
        // BadgeRepositoryImpl already degrades internally; this is the outer belt.
        val resolved = try {
            badges.badgesFor(image, page.characters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyMap()
        }

        synchronized(lock) {
            if (epoch == myEpoch) {
                parsed = units
                pageBadges = resolved
            }
        }
        setState(myEpoch, PipelineState.Preparing(units, emptyList(), resolved))
        prepareAll(units, myEpoch)
```

In `prepareAll`, pass `units` and the badges through both emissions:

```kotlin
            setState(myEpoch, PipelineState.Preparing(units, ready.toList(), pageBadges))
...
        setState(myEpoch, PipelineState.Ready(ready.toList(), pageBadges))
```

In `retry()`'s cached branch, use `PipelineState.Preparing(cached, emptyList(), pageBadges)`. In `reset()`, add `pageBadges = emptyMap()`.

Update `PipelineModule.kt` to inject `BadgeRepository`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*ReadingPipelineImplTest*' --no-daemon`
Expected: PASS, including the pre-existing concurrency and epoch tests — **especially** the one asserting exactly 3 in-flight syntheses, which must still read exactly 3.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller app/src/test/kotlin/com/storyteller/domain
git commit -m "feat: carry parsed units and badges through the pipeline"
```

---

### Task 8: Mode-aware reader ViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderUiState.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderViewModel.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/ReaderViewModelTest.kt` (exists — extend it)

**Interfaces:**
- Consumes: `SettingsRepository` (Task 6), `Preparing`/`Ready` with badges (Task 7).
- Produces: `ReaderUiState.Line(index: Int, speaker: String, text: String, badge: Badge, enabled: Boolean)`; `ReaderUiState.Playing(lines, playback, mode, playingIndex: Int?)`; `ReaderViewModel.onLineTapped(index: Int)`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test fun `tap mode does not start playback on its own`() = runTest {
        val player = RecordingPlayer()
        val vm = readerViewModel(player, mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Ready(preparedUnits(2), emptyMap()))
        advanceUntilIdle()

        assertTrue("tap mode must not autoplay", player.played.isEmpty())
    }

    @Test fun `auto mode still plays as it always did`() = runTest {
        val player = RecordingPlayer()
        val vm = readerViewModel(player, mode = ReadingMode.Auto)
        pipeline.emit(PipelineState.Ready(preparedUnits(2), emptyMap()))
        advanceUntilIdle()

        assertEquals(1, player.played.size)
    }

    @Test fun `tapping a line plays exactly that unit`() = runTest {
        val player = RecordingPlayer()
        val vm = readerViewModel(player, mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Ready(preparedUnits(3), emptyMap()))
        advanceUntilIdle()

        vm.onLineTapped(1)
        advanceUntilIdle()

        assertEquals(listOf(1), player.played.map { it.single().unit.index })
        assertEquals(1, player.endOfPageCalls)
        assertEquals(1, (vm.uiState.value as ReaderUiState.Playing).playingIndex)
    }

    @Test fun `tapping a line whose audio is not ready is inert`() = runTest {
        val player = RecordingPlayer()
        val vm = readerViewModel(player, mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Preparing(speechUnits(3), preparedUnits(1), emptyMap()))
        advanceUntilIdle()

        vm.onLineTapped(2)
        advanceUntilIdle()

        assertTrue(player.played.isEmpty())
    }

    @Test fun `preparing shows every line with only ready ones enabled`() = runTest {
        val vm = readerViewModel(RecordingPlayer(), mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Preparing(speechUnits(3), preparedUnits(1), emptyMap()))
        advanceUntilIdle()

        val lines = (vm.uiState.value as ReaderUiState.Playing).lines
        assertEquals(3, lines.size)
        assertEquals(listOf(true, false, false), lines.map { it.enabled })
    }
```

Extend the test file's helpers: `readerViewModel(player, mode)` builds the ViewModel with a fake `SettingsRepository` emitting that mode; `speechUnits(n)` and `preparedUnits(n)` build fixtures; `RecordingPlayer` records `played: List<List<PreparedUnit>>` and `endOfPageCalls`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*ReaderViewModelTest*' --no-daemon`
Expected: FAIL — `onLineTapped`, `playingIndex` and the new `Line` shape do not exist.

- [ ] **Step 3: Write minimal implementation**

`ReaderUiState.kt`:

```kotlin
    data class Playing(
        val lines: List<Line>,
        val playback: PlaybackState,
        val mode: ReadingMode,
        /** The row currently sounding, or null. Tap mode knows it because it handled the tap. */
        val playingIndex: Int?,
    ) : ReaderUiState

    data class Line(
        val index: Int,
        val speaker: String,
        val text: String,
        val badge: Badge,
        /** False until this line's audio has been synthesized. */
        val enabled: Boolean,
    )
```

In `ReaderViewModel.kt`: inject `settings: SettingsRepository`, hold `private var mode = ReadingMode.Auto` and `private var playingIndex: Int? = null`, and collect the mode in `init`:

```kotlin
        viewModelScope.launch { settings.mode.collect { mode = it } }
```

Guard the auto-play path — `queue()` is what hands units to the player, so gate its call sites rather than its body:

```kotlin
            is PipelineState.Preparing -> {
                if (mode == ReadingMode.Auto) queue(state.ready)
                _uiState.value = playingState(state.units, state.ready, state.badges)
            }

            is PipelineState.Ready -> {
                if (mode == ReadingMode.Auto) {
                    queue(state.units)
                    player.endOfPage()
                }
                _uiState.value = playingState(state.units.map { it.unit }, state.units, state.badges)
            }
```

Add the builder and the tap handler:

```kotlin
    /** Every unit renders; only the synthesized ones are tappable. */
    private fun playingState(
        all: List<SpeechUnit>,
        ready: List<PreparedUnit>,
        badges: Map<String, Badge>,
    ): ReaderUiState.Playing {
        val readyIndices = ready.mapTo(mutableSetOf()) { it.unit.index }
        return ReaderUiState.Playing(
            lines = all.map { u ->
                ReaderUiState.Line(
                    index = u.index,
                    speaker = u.speaker,
                    text = u.text,
                    badge = if (u.speaker == NARRATOR) Badge.None else badges[u.speaker] ?: Badge.None,
                    enabled = mode == ReadingMode.Auto || u.index in readyIndices,
                )
            },
            playback = playback,
            mode = mode,
            playingIndex = playingIndex,
        )
    }

    /**
     * A one-unit playlist plus an immediate endOfPage(): the player needs no new
     * API for tap mode, and a tap while something plays REPLACES it, which is
     * what a child tapping a new row means.
     */
    fun onLineTapped(index: Int) {
        if (mode != ReadingMode.Tap) return
        val prepared = lastReady.firstOrNull { it.unit.index == index } ?: return
        playingIndex = index
        player.play(listOf(prepared))
        player.endOfPage()
        (_uiState.value as? ReaderUiState.Playing)?.let { _uiState.value = it.copy(playingIndex = index) }
    }
```

Track `private var lastReady: List<PreparedUnit> = emptyList()`, assigned in both the `Preparing` and `Ready` branches, and cleared in the `Idle`/`Reading` branch alongside `queued = 0`. Clear `playingIndex` there too, and when playback reaches `Finished`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*ReaderViewModelTest*' --no-daemon`
Expected: PASS, including the pre-existing cumulative-queue and reset tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/reader app/src/test/kotlin/com/storyteller/ui/reader
git commit -m "feat: make the reader mode-aware and tappable"
```

---

### Task 9: Reader UI — badges, tappable rows, highlight

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderScreen.kt`
- Create: `app/src/main/kotlin/com/storyteller/ui/reader/BadgeIcon.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/ReaderScreenTest.kt` (create if absent)

**Interfaces:**
- Consumes: `ReaderUiState.Line`, `Badge` (Task 8).
- Produces: `@Composable internal fun LineRow(line: ReaderUiState.Line, isPlaying: Boolean, onTap: (Int) -> Unit)`; `@Composable internal fun BadgeIcon(badge: Badge)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storyteller.ui.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.storyteller.domain.model.Badge
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun line(index: Int = 0, enabled: Boolean = true, badge: Badge = Badge.None) =
        ReaderUiState.Line(index, "Bear", "Hello there", badge, enabled)

    @Test fun `an enabled row reports the index it was given`() {
        var tapped: Int? = null
        compose.setContent { LineRow(line(index = 2), isPlaying = false, onTap = { tapped = it }) }

        compose.onNodeWithText("Hello there").performClick()

        assertEquals(2, tapped)
    }

    @Test fun `a disabled row is inert`() {
        var tapped: Int? = null
        compose.setContent { LineRow(line(enabled = false), isPlaying = false, onTap = { tapped = it }) }

        compose.onNodeWithText("Hello there").performClick()

        assertEquals(null, tapped)
    }

    @Test fun `an emoji badge renders`() {
        compose.setContent { BadgeIcon(Badge.Emoji("🐻")) }
        compose.onNodeWithText("🐻").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*ReaderScreenTest*' --no-daemon`
Expected: FAIL — `LineRow` and `BadgeIcon` unresolved.

- [ ] **Step 3: Write minimal implementation**

`BadgeIcon.kt`:

```kotlin
package com.storyteller.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import androidx.compose.runtime.remember
import com.storyteller.domain.model.Badge

/** Fixed size so rows align whether or not a badge is present. */
val BADGE_SIZE = 40.dp

/**
 * Occupies its slot even when blank: collapsing it would indent narrator lines
 * differently from character lines and the list would read as ragged.
 */
@Composable
internal fun BadgeIcon(badge: Badge) {
    Box(Modifier.size(BADGE_SIZE).clip(CircleShape), contentAlignment = Alignment.Center) {
        when (badge) {
            is Badge.Emoji -> Text(badge.value)
            is Badge.Image -> {
                val bitmap = remember(badge.file.path) {
                    BitmapFactory.decodeFile(badge.file.path)?.asImageBitmap()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.size(BADGE_SIZE),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Badge.None -> Unit
        }
    }
}
```

In `ReaderScreen.kt`, replace the `LazyColumn` item body with `LineRow`, and add:

```kotlin
/**
 * The speaker name is the badge's accessible label: the badge itself is
 * decorative (contentDescription null) because the name is already right there
 * as text, and announcing it twice is noise.
 */
@Composable
internal fun LineRow(line: ReaderUiState.Line, isPlaying: Boolean, onTap: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = line.enabled) { onTap(line.index) }
            .background(if (isPlaying) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeIcon(line.badge)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.alpha(if (line.enabled) 1f else 0.4f)) {
            Text(line.speaker, style = MaterialTheme.typography.labelMedium)
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
```

Wire the call site: `LineRow(line, isPlaying = line.index == state.playingIndex, onTap = onLineTapped)`, threading `onLineTapped` from `ReaderScreen`'s `viewModel::onLineTapped`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*ReaderScreenTest*' --no-daemon`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/reader app/src/test/kotlin/com/storyteller/ui/reader
git commit -m "feat: render speaker badges and tappable lines"
```

---

### Task 10: Settings screen and navigation

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/kotlin/com/storyteller/ui/settings/SettingsViewModel.kt`
- Create: `app/src/main/res/drawable/ic_settings.xml`
- Modify: `app/src/main/kotlin/com/storyteller/ui/StorytellerNavHost.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/capture/CaptureScreen.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/settings/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository` (Task 6).
- Produces: `Routes.SETTINGS = "settings"`; `@Composable fun SettingsScreen(onBack: () -> Unit)`; `@Composable internal fun ReadingModeRow(mode: ReadingMode, onChange: (ReadingMode) -> Unit)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storyteller.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.storyteller.domain.model.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `toggling from auto reports tap`() {
        var chosen: ReadingMode? = null
        compose.setContent { ReadingModeRow(ReadingMode.Auto, onChange = { chosen = it }) }

        compose.onNodeWithText("Tap each line to hear it").performClick()

        assertEquals(ReadingMode.Tap, chosen)
    }

    @Test fun `toggling from tap reports auto`() {
        var chosen: ReadingMode? = null
        compose.setContent { ReadingModeRow(ReadingMode.Tap, onChange = { chosen = it }) }

        compose.onNodeWithText("Tap each line to hear it").performClick()

        assertEquals(ReadingMode.Auto, chosen)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*SettingsScreenTest*' --no-daemon`
Expected: FAIL — `ReadingModeRow` unresolved.

- [ ] **Step 3: Write minimal implementation**

`SettingsViewModel.kt`:

```kotlin
package com.storyteller.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val mode: StateFlow<ReadingMode> =
        settings.mode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingMode.Auto)

    fun setMode(mode: ReadingMode) {
        viewModelScope.launch { settings.setMode(mode) }
    }
}
```

`SettingsScreen.kt` — a settings list with one row, built so adding rows needs no restructuring:

```kotlin
package com.storyteller.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.domain.model.ReadingMode

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        ReadingModeRow(mode, viewModel::setMode)
        Button(onClick = onBack) { Text("Done") }
    }
}

/**
 * Stateless so it can be tested without Hilt, matching how the capture screen's
 * halves are tested.
 */
@Composable
internal fun ReadingModeRow(mode: ReadingMode, onChange: (ReadingMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Tap each line to hear it", style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = mode == ReadingMode.Tap,
            onCheckedChange = { onChange(if (it) ReadingMode.Tap else ReadingMode.Auto) },
        )
    }
}
```

The test clicks the label, so the whole row must be clickable: add `.clickable { onChange(if (mode == ReadingMode.Auto) ReadingMode.Tap else ReadingMode.Auto) }` to the `Row`'s modifier.

`ic_settings.xml` — a local vector, since `material-icons-extended` is barred:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z" />
</vector>
```

In `StorytellerNavHost.kt`: add `const val SETTINGS = "settings"` to `Routes`, pass `onOpenSettings = { nav.navigate(Routes.SETTINGS) }` to `CaptureScreen`, and add:

```kotlin
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
```

In `CaptureScreen.kt`: add an `onOpenSettings: () -> Unit` parameter and, inside the `Framing` branch's `Box`, an `IconButton` aligned `Alignment.TopEnd` using `painterResource(R.drawable.ic_settings)` with `contentDescription = "Settings"`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*SettingsScreenTest*' --no-daemon`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui app/src/main/res/drawable/ic_settings.xml app/src/test/kotlin/com/storyteller/ui/settings
git commit -m "feat: add a settings screen with the reading-mode toggle"
```

---

### Task 11: Character-box eval

Spec §12 says character-box accuracy is unmeasured and should be evaluated **before** the UI is trusted. This task closes that, and is the only one whose output is a number rather than a feature.

**Files:**
- Modify: `app/src/test/kotlin/com/storyteller/evals/VisionEval.kt`
- Modify: `evals/README.md`
- Create: `evals/expected/README.md` guidance for the `characters` block (append to the existing file if present)

**Interfaces:**
- Consumes: `PageReader.read` returning `ParsedPage` (Task 3).

- [ ] **Step 1: Extend the expected-output format**

Document in `evals/expected/README.md` that each fixture's expected JSON may now carry:

```json
"characters": [
  { "name": "Bear", "emojiExpected": true,
    "bounds": { "left": 0.10, "top": 0.12, "right": 0.34, "bottom": 0.55 } }
]
```

- [ ] **Step 2: Write the eval assertion**

Add to `VisionEval.kt`, following the file's existing per-fixture loop and scoring style:

```kotlin
    /**
     * Intersection-over-union against a hand-drawn box. 0.5 is the usual
     * detection threshold; a badge crop is padded by 10% and shown at 40dp, so
     * it tolerates more slop than a hit target would - but below 0.5 the crop
     * starts framing the wrong thing.
     */
    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val x1 = maxOf(a.left, b.left)
        val y1 = maxOf(a.top, b.top)
        val x2 = minOf(a.right, b.right)
        val y2 = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }
```

Report, per fixture: how many expected characters were returned at all, how many carried a box, and the mean IoU of those that did.

- [ ] **Step 3: Run the eval and record the result**

Run: `./gradlew testDebugUnitTest --tests '*VisionEval*' --no-daemon`

This needs real fixtures in `evals/fixtures/` (gitignored — photographs of copyrighted pages) and a real `ANTHROPIC_API_KEY` in `local.properties`.

Record the mean IoU in `evals/README.md`. **If it is below 0.5, stop and report rather than proceeding** — the badge feature rests on this number, and a low score means the crop path should be abandoned in favour of the emoji path before any more UI is built on it.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/kotlin/com/storyteller/evals evals/README.md evals/expected/README.md
git commit -m "test: measure character box accuracy in the vision eval"
```

---

## Manual verification

Run on a real device with real keys and a real book. **Iteration 1's walkthrough has still never been run**; do that first, because these steps assume the underlying read-and-speak path works.

1. Settings opens from the capture screen and closes with Done.
2. The reading-mode toggle survives killing and relaunching the app.
3. In **auto** mode, a page reads through exactly as before — no regression.
4. In **tap** mode, nothing plays on arrival.
5. In tap mode, the whole page appears greyed out and lines become tappable one at a time as synthesis lands.
6. Tapping a line plays that line and highlights its row.
7. Tapping a second line while the first is playing interrupts it.
8. Tapping a greyed-out line does nothing.
9. A graphic novel page shows cropped character badges that actually frame the character.
10. A prose picture book shows emoji badges.
11. Narrator lines show a blank badge, and stay aligned with the others.
12. Photograph a second page with the same character: the badge is identical to the first page's.
13. Re-photograph a page already read: badges appear immediately, playback is fast.

Record failures as bugs rather than fixing them inline.
