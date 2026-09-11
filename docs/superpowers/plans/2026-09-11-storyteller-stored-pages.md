# Stored Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A child can re-open a page they have already read, from a library, without re-photographing it and without the app paying for it again.

**Architecture:** A `StoredPageRepository` owns the photograph on disk, the row, and the eviction rule. `ReadingPipeline` gains a third entry point beside `start` and `retry` that skips the vision call and reuses everything after it. A new library screen lists stored pages newest-first and opens one into the existing reader.

**Tech Stack:** Kotlin, Room, Hilt, Jetpack Compose, kotlinx.serialization, JUnit 4, Robolectric, Turbine.

**Spec:** [`docs/superpowers/specs/2026-09-11-storyteller-stored-pages-design.md`](../specs/2026-09-11-storyteller-stored-pages-design.md)

## Global Constraints

- The architecture is `ui -> domain <- data`. `GraphTest` today enforces only two
  of the three rules — `ui never imports data` and `domain never imports android`.
  It does NOT check `domain never imports data`; Task 4 Step 5 adds that case,
  because Task 4 depends on it.
- Robolectric is pinned at `sdk = 34`. Do not bump it. `buildToolsVersion` stays unset.
- Commits use the repo's existing author, `mitenko`. **Never add a `Co-Authored-By` trailer of any kind.**
- Gradle on this machine: `FileLockContentionHandler` or `BindException` failures are environmental — run `./gradlew --stop` and retry. Neither is a code failure.
- Run tests with a hard timeout, never as an unbounded background task: an earlier runaway loop exhausted machine memory.
- Photographs go in `filesDir`, never `cacheDir`. The OS may purge `cacheDir`.
- The library holds **50** pages. The 51st read evicts the oldest.

## What already exists

Committed in `743d3b6`, do not rewrite:

- `StoredPageEntity(id, photoPath, unitsJson, parseVersion, createdAt)` in `data/local/Entities.kt`
- `StoredPageDao` with `observeAll(): Flow<List<StoredPageEntity>>`, `find(id)`, `count()`, `upsert(entity)`, `delete(id)`
- `MIGRATION_5_6`, registered in `DatabaseModule`, database at `version = 6`

---

### Task 1: The stored page, and how its units survive a round trip

Pure domain plus serialization. No Android, no Room.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/domain/model/StoredPage.kt`
- Create: `app/src/main/kotlin/com/storyteller/data/library/StoredUnitJson.kt`
- Modify: `app/src/main/kotlin/com/storyteller/domain/repository/Repositories.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/library/StoredUnitJsonTest.kt`

**Interfaces:**
- Consumes: `SpeechUnit`, `BoundingBox`, `PreparedUnit` from `domain.model`.
- Produces: `StoredPage(id, photo, units, audioNames, readAt)`; `StoredPageRepository`; `encodeUnits(List<PreparedUnit>): String`; `decodeUnits(String): List<SpeechUnit>`; `decodeAudioNames(String): List<String>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storyteller.data.library

import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class StoredUnitJsonTest {

    private fun prepared(
        index: Int,
        text: String = "line $index",
        bounds: BoundingBox? = BoundingBox(0.1f, 0.2f, 0.3f, 0.4f),
        audio: String = "key$index.mp3",
    ) = PreparedUnit(
        unit = SpeechUnit(
            index = index,
            speaker = "Cogsley",
            text = text,
            bounds = bounds,
            panel = BoundingBox(0f, 0f, 1f, 1f),
            characterId = "c1",
            voiceKey = "cogsley",
        ),
        voiceId = "v-abc",
        audio = File("/audio/$audio"),
    )

    /** Everything the reader needs must survive; anything lost shows as a page that reads differently. */
    @Test fun `units round-trip with bounds, panel, identity and voice key`() {
        val json = encodeUnits(listOf(prepared(0), prepared(1)))
        val units = decodeUnits(json)

        assertEquals(2, units.size)
        assertEquals(0, units[0].index)
        assertEquals("Cogsley", units[0].speaker)
        assertEquals("line 0", units[0].text)
        assertEquals(BoundingBox(0.1f, 0.2f, 0.3f, 0.4f), units[0].bounds)
        assertEquals(BoundingBox(0f, 0f, 1f, 1f), units[0].panel)
        assertEquals("c1", units[0].characterId)
        assertEquals("cogsley", units[0].voiceKey)
    }

    /** A null box must come back null, not as a zero-area box that crops to nothing. */
    @Test fun `a null bounding box survives as null`() {
        val units = decodeUnits(encodeUnits(listOf(prepared(0, bounds = null))))

        assertEquals(null, units[0].bounds)
    }

    /**
     * The clip file names are stored so eviction can delete exactly what a page
     * owns without recomputing a cache key from a voice map that may have changed
     * since.
     */
    @Test fun `the audio file names are recoverable for deletion`() {
        val json = encodeUnits(listOf(prepared(0, audio = "aaa.mp3"), prepared(1, audio = "bbb.mp3")))

        assertEquals(listOf("aaa.mp3", "bbb.mp3"), decodeAudioNames(json))
    }

    /** Malformed stored JSON must not throw: the library drops the row instead of failing to open. */
    @Test fun `unreadable json decodes to nothing rather than throwing`() {
        assertEquals(emptyList<SpeechUnit>(), decodeUnits("{not json"))
        assertEquals(emptyList<String>(), decodeAudioNames("{not json"))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.storyteller.data.library.StoredUnitJsonTest"`
Expected: FAIL — `Unresolved reference 'encodeUnits'`.

- [ ] **Step 3: Write the domain types**

Create `domain/model/StoredPage.kt`:

```kotlin
package com.storyteller.domain.model

import java.io.File

/**
 * A page the child has already read, and everything needed to read it again.
 *
 * [audioNames] is the file name of each unit's clip, recorded at save time.
 * Eviction deletes by name rather than recomputing a cache key, because the key
 * depends on the voice map and that can change between saving and deleting -
 * MIGRATION_4_5 cleared it once already.
 */
data class StoredPage(
    val id: String,
    val photo: File,
    val units: List<SpeechUnit>,
    val audioNames: List<String>,
    val readAt: Long,
)
```

Add to `domain/repository/Repositories.kt` (import `com.storyteller.domain.model.PreparedUnit`, `StoredPage`, `PageImage`, `kotlinx.coroutines.flow.Flow`):

```kotlin
/** The pages a child has read, kept so they can be opened again for nothing. */
interface StoredPageRepository {
    /** Newest first. */
    fun observeLibrary(): Flow<List<StoredPage>>

    /** Stores [image]'s photograph and [units]; evicts the oldest page beyond the cap. */
    suspend fun save(id: String, image: PageImage, units: List<PreparedUnit>)

    suspend fun open(id: String): StoredPage?

    /** Removes the row, its photograph, and any clip no other stored page needs. */
    suspend fun delete(id: String)
}
```

- [ ] **Step 4: Write the serialization**

Create `data/library/StoredUnitJson.kt`:

```kotlin
package com.storyteller.data.library

import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The stored shape of a unit.
 *
 * Its own DTO rather than serializing SpeechUnit directly: the domain type is
 * free to change without silently invalidating every page a child has stored,
 * and every field here is one someone decided to persist.
 *
 * [audioName] is the clip's file name, kept for exact deletion. [voiceId] is kept
 * beside it because a name alone cannot be re-derived if the file is gone.
 */
@Serializable
private data class StoredUnitDto(
    val index: Int,
    val speaker: String,
    val text: String,
    val bounds: BoxDto? = null,
    val panel: BoxDto? = null,
    val characterId: String? = null,
    val voiceKey: String? = null,
    val voiceId: String = "",
    val audioName: String = "",
)

@Serializable
private data class BoxDto(val l: Float, val t: Float, val r: Float, val b: Float)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun BoundingBox.toDto() = BoxDto(left, top, right, bottom)
private fun BoxDto.toDomain() = BoundingBox(l, t, r, b)

fun encodeUnits(prepared: List<PreparedUnit>): String =
    json.encodeToString(
        prepared.map { p ->
            StoredUnitDto(
                index = p.unit.index,
                speaker = p.unit.speaker,
                text = p.unit.text,
                bounds = p.unit.bounds?.toDto(),
                panel = p.unit.panel?.toDto(),
                characterId = p.unit.characterId,
                voiceKey = p.unit.voiceKey,
                voiceId = p.voiceId,
                audioName = p.audio.name,
            )
        },
    )

/**
 * Returns nothing rather than throwing on malformed input. A stored row that
 * cannot be read is dropped from the library; failing to open would strand a
 * child on an error screen for a page they can no longer reach anyway.
 */
fun decodeUnits(stored: String): List<SpeechUnit> =
    runCatching {
        json.decodeFromString<List<StoredUnitDto>>(stored).map {
            SpeechUnit(
                index = it.index,
                speaker = it.speaker,
                text = it.text,
                bounds = it.bounds?.toDomain(),
                panel = it.panel?.toDomain(),
                characterId = it.characterId,
                voiceKey = it.voiceKey,
            )
        }
    }.getOrDefault(emptyList())

fun decodeAudioNames(stored: String): List<String> =
    runCatching {
        json.decodeFromString<List<StoredUnitDto>>(stored)
            .map { it.audioName }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
```

- [ ] **Step 5: Run the test and watch it pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.storyteller.data.library.StoredUnitJsonTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/model/StoredPage.kt \
        app/src/main/kotlin/com/storyteller/data/library/StoredUnitJson.kt \
        app/src/main/kotlin/com/storyteller/domain/repository/Repositories.kt \
        app/src/test/kotlin/com/storyteller/data/library/StoredUnitJsonTest.kt
git commit -m "feat: the stored shape of a page, and its round trip"
```

---

### Task 2: The repository, the photograph on disk, and eviction

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/data/library/StoredPageRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/storyteller/di/RepositoryModule.kt`
- Modify: `app/src/main/kotlin/com/storyteller/di/DatabaseModule.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/library/StoredPageRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `StoredPageDao`, `encodeUnits`, `decodeUnits`, `decodeAudioNames`, `StoredPageRepository`.
- Produces: `StoredPageRepositoryImpl(dao, pagesDir, audioDir, maxPages)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storyteller.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoredPageRepositoryImplTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: StorytellerDatabase
    private lateinit var pages: File
    private lateinit var audio: File

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            StorytellerDatabase::class.java,
        ).allowMainThreadQueries().build()
        pages = tmp.newFolder("pages")
        audio = tmp.newFolder("audio")
    }

    @After fun tearDown() = db.close()

    private fun repo(maxPages: Int = 50) =
        StoredPageRepositoryImpl(db.storedPageDao(), pages, audio, maxPages)

    private fun clip(name: String): File =
        File(audio, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun prepared(index: Int, audioName: String) = PreparedUnit(
        unit = SpeechUnit(index, "Cogsley", "line $index", BoundingBox(0f, 0f, 1f, 1f)),
        voiceId = "v",
        audio = clip(audioName),
    )

    private fun image() = PageImage(
        bytes = byteArrayOf(9),
        mimeType = "image/jpeg",
        displayBytes = byteArrayOf(1, 2, 3, 4),
        width = 800,
        height = 1200,
    )

    @Test fun `saving writes the photograph and a row`() = runTest {
        val r = repo()
        r.save("page-a", image(), listOf(prepared(0, "a.mp3")))

        val library = r.observeLibrary().first()
        assertEquals(1, library.size)
        assertEquals("page-a", library[0].id)
        assertTrue("the photograph must be on disk", library[0].photo.exists())
        assertEquals(1, library[0].units.size)
    }

    /**
     * The photograph is the DISPLAY copy, not the upload copy: the reader crops
     * panels out of it, and the upload copy is downscaled to what the model sees.
     */
    @Test fun `the photograph stored is the display copy`() = runTest {
        val r = repo()
        r.save("page-a", image(), listOf(prepared(0, "a.mp3")))

        assertEquals(4, r.open("page-a")!!.photo.length())
    }

    @Test fun `opening a page that was never stored returns nothing`() = runTest {
        assertNull(repo().open("never"))
    }

    @Test fun `the library is newest first`() = runTest {
        val r = repo()
        r.save("old", image(), listOf(prepared(0, "a.mp3")))
        Thread.sleep(5)
        r.save("new", image(), listOf(prepared(0, "b.mp3")))

        assertEquals(listOf("new", "old"), r.observeLibrary().first().map { it.id })
    }

    @Test fun `past the cap the oldest page and its photograph go`() = runTest {
        val r = repo(maxPages = 2)
        r.save("one", image(), listOf(prepared(0, "1.mp3")))
        Thread.sleep(5)
        r.save("two", image(), listOf(prepared(0, "2.mp3")))
        Thread.sleep(5)
        val oldest = r.open("one")!!.photo
        r.save("three", image(), listOf(prepared(0, "3.mp3")))

        assertEquals(listOf("three", "two"), r.observeLibrary().first().map { it.id })
        assertFalse("the evicted photograph must go too", oldest.exists())
        assertFalse("and its clip", File(audio, "1.mp3").exists())
    }

    /**
     * The clause with teeth. Clips are content-addressed, so two pages containing
     * the same line in the same voice share one file. Evicting one page must not
     * silence the other.
     */
    @Test fun `a clip another page still needs is never deleted`() = runTest {
        val r = repo(maxPages = 2)
        val shared = "shared.mp3"
        r.save("one", image(), listOf(prepared(0, shared)))
        Thread.sleep(5)
        r.save("two", image(), listOf(prepared(0, shared)))
        Thread.sleep(5)
        r.save("three", image(), listOf(prepared(0, "3.mp3")))

        assertTrue(
            "page two still needs this clip",
            File(audio, shared).exists(),
        )
    }

    @Test fun `deleting a page removes its row, photograph and clip`() = runTest {
        val r = repo()
        r.save("page-a", image(), listOf(prepared(0, "a.mp3")))
        val photo = r.open("page-a")!!.photo

        r.delete("page-a")

        assertNull(r.open("page-a"))
        assertFalse(photo.exists())
        assertFalse(File(audio, "a.mp3").exists())
    }

    /**
     * Re-reading a page the library already holds must refresh it, not fail and not
     * duplicate: the id is the image hash, so it is the same page by definition.
     */
    @Test fun `saving the same page twice keeps one row`() = runTest {
        val r = repo()
        r.save("page-a", image(), listOf(prepared(0, "a.mp3")))
        r.save("page-a", image(), listOf(prepared(0, "a.mp3"), prepared(1, "b.mp3")))

        val library = r.observeLibrary().first()
        assertEquals(1, library.size)
        assertEquals(2, library[0].units.size)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.storyteller.data.library.StoredPageRepositoryImplTest"`
Expected: FAIL — `Unresolved reference 'StoredPageRepositoryImpl'`.

- [ ] **Step 3: Write the repository**

Create `data/library/StoredPageRepositoryImpl.kt`:

```kotlin
package com.storyteller.data.library

import com.storyteller.data.local.StoredPageDao
import com.storyteller.data.local.StoredPageEntity
import com.storyteller.data.local.PARSE_VERSION
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.StoredPage
import com.storyteller.domain.repository.StoredPageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Pages live in [pagesDir] as one JPEG each, named by the page id.
 *
 * [pagesDir] is under filesDir, never cacheDir: the photograph is the only copy of
 * what the child actually read, and the OS may purge cacheDir whenever it likes.
 *
 * Eviction is by COUNT, not by bytes. A real size budget is the storage
 * milestone's job; fifty is a bound that can exist today without measuring
 * anything, and being unbounded until then is the alternative.
 */
class StoredPageRepositoryImpl(
    private val dao: StoredPageDao,
    private val pagesDir: File,
    private val audioDir: File,
    private val maxPages: Int = 50,
) : StoredPageRepository {

    override fun observeLibrary(): Flow<List<StoredPage>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun save(id: String, image: PageImage, units: List<PreparedUnit>) {
        pagesDir.mkdirs()
        val photo = File(pagesDir, "$id.jpg")
        // The DISPLAY copy: the reader crops panels out of it. The upload copy is
        // downscaled to what the model sees and is far too soft to show.
        photo.writeBytes(image.displayBytes)

        dao.upsert(
            StoredPageEntity(
                id = id,
                photoPath = photo.absolutePath,
                unitsJson = encodeUnits(units),
                parseVersion = PARSE_VERSION,
                createdAt = System.currentTimeMillis(),
            ),
        )
        evictBeyondCap()
    }

    override suspend fun open(id: String): StoredPage? = dao.find(id)?.toDomain()

    override suspend fun delete(id: String) {
        val row = dao.find(id) ?: return
        dao.delete(id)
        File(row.photoPath).delete()
        removeUnsharedClips(decodeAudioNames(row.unitsJson))
    }

    private suspend fun evictBeyondCap() {
        val all = dao.observeAllOnce()
        if (all.size <= maxPages) return
        // observeAllOnce is newest-first, so the tail is the oldest.
        all.drop(maxPages).forEach { delete(it.id) }
    }

    /**
     * Clips are content-addressed - sha256(voiceId|text) - so two pages holding the
     * same line in the same voice share ONE file. Deleting a page must not silence
     * a page that is still in the library.
     */
    private suspend fun removeUnsharedClips(names: List<String>) {
        if (names.isEmpty()) return
        val stillNeeded = dao.observeAllOnce()
            .flatMap { decodeAudioNames(it.unitsJson) }
            .toSet()
        names.filterNot { it in stillNeeded }.forEach { File(audioDir, it).delete() }
    }

    private fun StoredPageEntity.toDomain() = StoredPage(
        id = id,
        photo = File(photoPath),
        units = decodeUnits(unitsJson),
        audioNames = decodeAudioNames(unitsJson),
        readAt = createdAt,
    )
}
```

- [ ] **Step 4: Add the one-shot query the repository needs**

In `data/local/Daos.kt`, inside `StoredPageDao`:

```kotlin
    /**
     * The same ordering as [observeAll], read once. Eviction and clip accounting
     * need a snapshot, and collecting a Flow to get one invites a deadlock inside
     * a suspend function that is already holding the caller's coroutine.
     */
    @Query("SELECT * FROM stored_page ORDER BY createdAt DESC")
    suspend fun observeAllOnce(): List<StoredPageEntity>
```

- [ ] **Step 5: Run the test and watch it pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.storyteller.data.library.StoredPageRepositoryImplTest"`
Expected: PASS, 8 tests.

- [ ] **Step 6: Wire it into Hilt**

In `di/DatabaseModule.kt`, beside the other DAO providers:

```kotlin
    @Provides fun storedPageDao(db: StorytellerDatabase): StoredPageDao = db.storedPageDao()
```

In `di/RepositoryModule.kt` (imports: `com.storyteller.data.library.StoredPageRepositoryImpl`, `com.storyteller.data.local.StoredPageDao`, `com.storyteller.domain.repository.StoredPageRepository`):

```kotlin
    /** Under filesDir for the same reason audio is: the OS may purge cacheDir. */
    @Provides @Singleton @Named("pagesDir")
    fun pagesDir(@ApplicationContext ctx: Context): File = File(ctx.filesDir, "pages")

    @Provides @Singleton
    fun storedPageRepository(
        dao: StoredPageDao,
        @Named("pagesDir") pagesDir: File,
        @Named("audioDir") audioDir: File,
    ): StoredPageRepository = StoredPageRepositoryImpl(dao, pagesDir, audioDir)
```

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: PASS, no regressions. `GraphTest` must still pass — `data` may import `domain`, never the reverse.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data/library/StoredPageRepositoryImpl.kt \
        app/src/main/kotlin/com/storyteller/data/local/Daos.kt \
        app/src/main/kotlin/com/storyteller/di/RepositoryModule.kt \
        app/src/main/kotlin/com/storyteller/di/DatabaseModule.kt \
        app/src/test/kotlin/com/storyteller/data/library/StoredPageRepositoryImplTest.kt
git commit -m "feat: keep fifty pages, and never delete a clip another page needs"
```

---

### Task 3: A third way into the pipeline

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/domain/ReadingPipeline.kt`
- Modify: `app/src/main/kotlin/com/storyteller/domain/ReadingPipelineImpl.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/ReadingPipelineStoredTest.kt`

**Interfaces:**
- Consumes: `PipelineState`, `SpeechUnit`, `PageImage`, the existing private `guarded`, `setState`, `prepareAll`.
- Produces: `ReadingPipeline.openStored(units: List<SpeechUnit>, image: PageImage)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storyteller.domain

import app.cash.turbine.test
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.SpeechUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPipelineStoredTest {

    private fun units() = (0..2).map { speechUnit(it) }

    /** The property the whole milestone exists for: opening a stored page is free. */
    @Test fun `opening a stored page never calls the page reader`() = runTest {
        val reader = FakePageReader(Result.success(units()))
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), FakeAudioRepository(), this)

        p.openStored(units(), pageImage())

        p.state.test {
            skipItems(1)
            assertEquals(0, reader.calls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `opening a stored page reaches Ready with every unit`() = runTest {
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success(units())),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
        )

        p.state.test {
            skipItems(1)
            p.openStored(units(), pageImage())
            val ready = awaitItem() as? PipelineState.Preparing
            assertEquals(3, ready?.total)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The image travels with it, or the reader falls back to text and shows no panels. */
    @Test fun `the stored photograph reaches the reader`() = runTest {
        val image = pageImage()
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success(units())),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
        )

        p.state.test {
            skipItems(1)
            p.openStored(units(), image)
            assertEquals(image, (awaitItem() as PipelineState.Preparing).image)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `an empty stored page does not strand the reader`() = runTest {
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success(emptyList())),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
        )

        p.state.test {
            skipItems(1)
            p.openStored(emptyList<SpeechUnit>(), pageImage())
            assertEquals(0, (awaitItem() as PipelineState.Preparing).total)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

`FakePageReader` needs a call counter. In `app/src/test/kotlin/com/storyteller/domain/Fakes.kt`, add `var calls = 0` to `FakePageReader` and increment it at the top of its `read` override.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.storyteller.domain.ReadingPipelineStoredTest"`
Expected: FAIL — `Unresolved reference 'openStored'`.

- [ ] **Step 3: Add it to the interface**

In `domain/ReadingPipeline.kt`:

```kotlin
    /**
     * Reads a page that is already parsed - from the library - skipping the vision
     * call and nothing else.
     *
     * Deliberately routed through the same preparation as a fresh read rather than
     * emitting Ready directly: prefetch, playlist growth, failure mapping,
     * cancellation and both reading modes all keep working, and a stored page
     * behaves identically to a fresh one from the reader's point of view.
     */
    fun openStored(units: List<SpeechUnit>, image: PageImage)
```

- [ ] **Step 4: Implement it**

In `ReadingPipelineImpl`, beside `retry()`:

```kotlin
    override fun openStored(units: List<SpeechUnit>, image: PageImage) {
        synchronized(lock) {
            lastImage = image
            parsed = units
            job?.cancel()
            val myEpoch = ++epoch
            job = scope.launch {
                guarded(myEpoch) {
                    setState(myEpoch, PipelineState.Preparing(units, emptyList(), image))
                    prepareAll(units, myEpoch, image)
                }
            }
        }
    }
```

`lastImage` and `parsed` are set so a `retry()` after a stored open takes the cached-parse branch rather than re-photographing.

- [ ] **Step 5: Run the test and watch it pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.storyteller.domain.ReadingPipelineStoredTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/ReadingPipeline.kt \
        app/src/main/kotlin/com/storyteller/domain/ReadingPipelineImpl.kt \
        app/src/test/kotlin/com/storyteller/domain/ReadingPipelineStoredTest.kt \
        app/src/test/kotlin/com/storyteller/domain/Fakes.kt
git commit -m "feat: open a stored page without a vision call"
```

---

### Task 4: Save a page once it is fully read

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/domain/ReadingPipelineImpl.kt`
- Modify: `app/src/main/kotlin/com/storyteller/di/PipelineModule.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/ReadingPipelineSaveTest.kt`

**Interfaces:**
- Consumes: `StoredPageRepository`, `PipelineState.Ready`.
- Produces: `ReadingPipelineImpl(pageReader, voices, audio, scope, library)` — a fifth constructor parameter, defaulted to null so existing tests keep compiling.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storyteller.domain

import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.StoredPage
import com.storyteller.domain.repository.StoredPageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class RecordingLibrary : StoredPageRepository {
    val saved = mutableListOf<Pair<String, Int>>()
    override fun observeLibrary(): Flow<List<StoredPage>> = flowOf(emptyList())
    override suspend fun save(id: String, image: PageImage, units: List<PreparedUnit>) {
        saved += id to units.size
    }
    override suspend fun open(id: String): StoredPage? = null
    override suspend fun delete(id: String) = Unit
}

class ReadingPipelineSaveTest {

    /** A page is stored once it is fully prepared, not when anyone looks at it. */
    @Test fun `a fully read page is saved`() = runTest {
        val library = RecordingLibrary()
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success((0..1).map { speechUnit(it) })),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
            library,
        )

        p.start(pageImage())
        advanceUntilIdle()

        assertEquals(1, library.saved.size)
        assertEquals(2, library.saved.single().second)
    }

    /**
     * A page that failed halfway is not a page. Storing it would put a half-read
     * entry in the library that opens into a broken read.
     */
    @Test fun `a failed read is not saved`() = runTest {
        val library = RecordingLibrary()
        val p = ReadingPipelineImpl(
            FakePageReader(Result.failure(IllegalStateException("no"))),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
            library,
        )

        p.start(pageImage())
        advanceUntilIdle()

        assertEquals(0, library.saved.size)
    }

    /** Re-opening a stored page must not store it again under a new timestamp. */
    @Test fun `opening a stored page does not re-save it`() = runTest {
        val library = RecordingLibrary()
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success(emptyList())),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
            library,
        )

        p.openStored((0..1).map { speechUnit(it) }, pageImage())
        advanceUntilIdle()

        assertEquals(0, library.saved.size)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.storyteller.domain.ReadingPipelineSaveTest"`
Expected: FAIL — too many arguments for `ReadingPipelineImpl`.

- [ ] **Step 3: Implement**

Add the parameter to `ReadingPipelineImpl`:

```kotlin
class ReadingPipelineImpl(
    private val pageReader: PageReader,
    private val voices: VoiceRepository,
    private val audio: AudioRepository,
    private val scope: CoroutineScope,
    /**
     * Null in tests that do not care about the library. A pipeline with no library
     * reads pages perfectly well and simply forgets them.
     */
    private val library: StoredPageRepository? = null,
) : ReadingPipeline {
```

Add a flag beside `lastImage`, and set it where `openStored` and `run` differ:

```kotlin
    /**
     * Whether the page in flight came from the library. A stored page must not be
     * saved again: it would take a new timestamp and jump to the front of a library
     * that is ordered by when a page was READ, not by when it was last opened.
     */
    private var fromLibrary = false
```

Set `fromLibrary = true` in `openStored` and `fromLibrary = false` in `start`, both inside the existing `synchronized(lock)` block.

In `prepareAll`, at the point where it currently calls `setState(myEpoch, PipelineState.Ready(ready.toList(), image))`, save first:

```kotlin
        val finished = ready.toList()
        if (!fromLibrary) {
            // The id is the hash of the uploaded bytes, which is what parsed_page
            // keys on too, so a stored page and its cached parse agree by
            // construction.
            library?.save(sha256Of(image), image, finished)
        }
        setState(myEpoch, PipelineState.Ready(finished, image))
```

**The id must be the same hash `PageReaderImpl` uses as its parse-cache key, and it is not reachable from here.** `sha256` lives in `com.storyteller.data.Hashing`, the pipeline lives in `com.storyteller.domain`, and the architecture is `ui -> domain <- data` — domain importing data would point a dependency the wrong way.

Move it. `Hashing.kt` is pure Kotlin with no Android and no Room, so it belongs in `domain` regardless:

```bash
git mv app/src/main/kotlin/com/storyteller/data/Hashing.kt        app/src/main/kotlin/com/storyteller/domain/Hashing.kt
```

Change its package to `com.storyteller.domain` and fix the imports in `AudioRepositoryImpl` and `PageReaderImpl` (`data` importing `domain` is the correct direction). Do **not** reimplement the hash: two hashes that agree today and drift tomorrow would silently store every page twice, and nothing would fail.

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.storyteller.domain.ReadingPipelineSaveTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Make the layering rule enforced, not merely observed**

Task 4 relies on `domain` not importing `data`, and `GraphTest` does not check it — it
tests `ui never imports data` and `domain never imports android` only. No domain file
violates it today, so this pins the status quo rather than changing anything:

```kotlin
    @Test fun `domain never imports data`() {
        val domainDir = File(srcMain, "domain")
        val offenders = domainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { it.startsWith("import com.storyteller.data") } }
            .map { it.name }
            .toList()

        assertTrue("domain imports data in: $offenders", offenders.isEmpty())
    }
```

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.storyteller.di.GraphTest"`
Expected: PASS — and it must pass BEFORE the move in Step 3 is undone by anyone.

- [ ] **Step 6: Wire the library into the Hilt-provided pipeline**

In `di/PipelineModule.kt`, add `library: StoredPageRepository` to the provider's parameters and pass it as the fifth argument.

- [ ] **Step 7: Run the whole suite, then commit**

```bash
./gradlew.bat :app:testDebugUnitTest
git add -A
git commit -m "feat: keep a page once it has been read all the way through"
```

---

### Task 5: The library screen

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/ui/library/LibraryScreen.kt`
- Create: `app/src/main/kotlin/com/storyteller/ui/library/LibraryViewModel.kt`
- Create: `app/src/main/res/drawable/ic_library.xml`
- Modify: `app/src/main/kotlin/com/storyteller/ui/StorytellerNavHost.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/capture/CaptureScreen.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/library/LibraryScreenTest.kt`

**Interfaces:**
- Consumes: `StoredPageRepository.observeLibrary()`, `ReadingPipeline.openStored`.
- Produces: `LibraryScreen(onBack, onOpenPage)`, `LibraryUiState`, `Routes.LIBRARY`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storyteller.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryScreenTest {

    @get:Rule val compose = createComposeRule()

    /** An empty library must say so, not present an empty grid that looks broken. */
    @Test fun `an empty library explains itself`() {
        compose.setContent { LibraryContent(state = LibraryUiState(emptyList()), onOpen = {}, onDelete = {}) }

        compose.onNodeWithText("No pages yet.").assertIsDisplayed()
    }

    @Test fun `a stored page can be opened`() {
        var opened: String? = null
        compose.setContent {
            LibraryContent(
                state = LibraryUiState(listOf(LibraryItem("page-a", null, "Today"))),
                onOpen = { opened = it },
                onDelete = {},
            )
        }

        compose.onNodeWithContentDescription("Read this page again").performClick()

        assertEquals("page-a", opened)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.storyteller.ui.library.LibraryScreenTest"`
Expected: FAIL — `Unresolved reference 'LibraryContent'`.

- [ ] **Step 3: Write the state, ViewModel and screen**

`LibraryViewModel.kt` exposes `uiState: StateFlow<LibraryUiState>` mapped from `StoredPageRepository.observeLibrary()`, plus `open(id)` which loads the page and calls `pipeline.openStored(page.units, PageImage(bytes = ByteArray(0), mimeType = "image/jpeg", displayBytes = page.photo.readBytes()))`, and `delete(id)`.

`LibraryUiState(items: List<LibraryItem>)`; `LibraryItem(id: String, thumbnail: ImageBitmap?, readAt: String)`.

`LibraryContent` is stateless — a `LazyVerticalGrid` of cards, each with the thumbnail, the date, `contentDescription = "Read this page again"`, and a long-press that calls `onDelete(id)`. When `items` is empty it shows `Text("No pages yet.")` centred.

Decode the thumbnail off the composition thread with `produceState` and `Dispatchers.Default`, exactly as `PanelCard` already does — a grid of full-size JPEGs decoded inline will drop frames.

- [ ] **Step 4: Add the route and the way in**

`Routes.LIBRARY = "library"`; a `composable(Routes.LIBRARY)` entry rendering `LibraryScreen(onBack = { nav.popBackStack() }, onOpenPage = { nav.navigate(Routes.READER) })`.

In `CaptureScreen`, beside the existing settings `IconButton`, add one using `ic_library.xml` with `contentDescription = "Pages you have read"` calling a new `onOpenLibrary` parameter.

`ic_library.xml` is a 24dp vector — three horizontal bars, matching the weight of `ic_settings.xml`.

- [ ] **Step 5: Run the test and watch it pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.storyteller.ui.library.LibraryScreenTest"`
Expected: PASS, 2 tests.

- [ ] **Step 6: Run the whole suite and commit**

```bash
./gradlew.bat :app:testDebugUnitTest
git add -A
git commit -m "feat: a shelf of the pages a child has read"
```

---

### Task 6: See it on a device

No new code. This milestone's claim is economic, and only a device can confirm it.

- [ ] **Step 1: Install**

```bash
./gradlew.bat :app:assembleDebug
adb -s <serial> install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.storyteller/.MainActivity
```

Launch with `am start`, never `adb shell monkey` — monkey turns the device's auto-rotate on.

- [ ] **Step 2: Read a page, then open it from the library**

Confirm: the page appears in the library with its photograph; opening it shows the panels and reads aloud; and it starts **immediately**, because nothing is being fetched.

- [ ] **Step 3: Confirm it cost nothing**

```bash
python scripts/diagnostics.py pull --serial <serial>
```

A re-opened page must produce **no new diagnostic bundle** — a bundle is written per vision call, so a new one means the vision call happened.

- [ ] **Step 4: Record what happened**

Add findings to `docs/BACKLOG.md` under M5, including the measured size of one stored page's photograph — the spec's 1–3MB estimate is inferred, and it is what the fifty-page cap rests on.

---

## Self-review

**Spec coverage.** Repository, photo on disk, eviction, shared-clip protection, `openStored`, save-on-Ready, library screen, capture entry point, deletion, device check — all have tasks. The spec's error-handling table is covered by: malformed JSON (Task 1), missing photo (the reader's existing text fallback), missing clip (the audio cache's existing miss path).

**Placeholders.** None. Task 5's UI is described rather than fully coded, which is deliberate and the one place a reader should expect to make judgement calls — the tests pin the behaviour that matters.

**Type consistency.** `save(id, image, units)` takes `List<PreparedUnit>` in Task 1's interface, Task 2's implementation and Task 4's caller. `openStored(units, image)` takes `List<SpeechUnit>` in Tasks 3 and 5. `decodeAudioNames` returns `List<String>` in Tasks 1 and 2.

**The risk that turned out to be real.** Task 4 needs the page id to equal `PageReaderImpl`'s cache key, and `sha256` sits in `data` where the pipeline cannot reach it without pointing a dependency backwards. Task 4 now moves it into `domain` explicitly, and adds the `GraphTest` case that would have caught the shortcut. Two hashes that agree today and drift tomorrow would silently store every page twice, and no test would fail.
