# Voice Badge and Picker (M3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every line in the reader carries a tappable badge naming its speaker, and tapping it opens a screen offering three voices for that character, auditioned on the character's own shortest line and written back to the voice map.

**Architecture:** The voice list cache grows from a CSV of ids into a JSON list of
profiles carrying name, gender and age, because a picker cannot show
`pqHfZKP75CvOlQylNhV4` and a *contrasting* trio cannot be computed from ids alone.
Trio selection is a pure function over (all profiles, current voice, taken voices),
so the rule that decides what a child sees is testable with no network, no database
and no screen. `VoiceRepository` gains `choicesFor` and `assign`; `assign` is the
first thing in the app that can ever overwrite a voice. Re-synthesis after a change
needs no new pipeline API: `ReadingPipeline.retry()` already re-prepares from the
in-memory parse without a vision call, and the content-addressed audio cache means
only the changed character's lines actually miss.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Room (v6 -> v7),
kotlinx.serialization, Retrofit, Media3/ExoPlayer, JUnit4 + Robolectric (pinned
`sdk = 34`), `kotlinx-coroutines-test`.

**Spec:** [`docs/superpowers/specs/2026-09-13-storyteller-voice-badge-design.md`](../specs/2026-09-13-storyteller-voice-badge-design.md)

## Global Constraints

- **Architecture rule, enforced by `GraphTest`:** `ui` never imports `data`;
  `domain` never imports `data`; `domain` never imports `android`. New domain types
  go in `com.storyteller.domain.model`, new interfaces in
  `com.storyteller.domain.repository`, implementations in `com.storyteller.data.*`.
- **Commit identity:** the repo has a local `user.name`/`user.email` already set.
  Do not pass `--author`. **Never** add a `Co-Authored-By` trailer or a "Generated
  with Claude Code" footer to a commit in this repo.
- **Room migrations are hand-written** in `StorytellerDatabase.kt` and registered in
  `di/DatabaseModule.kt`. `exportSchema = false`, so Room validates the live table
  against the entity on open: a migrated table's columns must match **exactly** what
  Room would generate, or the app fails to open the database.
- **`Result`-returning suspend functions catch `CancellationException` first and
  rethrow it**, then catch `Throwable`. See `VoiceRepositoryImpl.voiceFor`. Never
  swallow cancellation.
- **New optional fields on existing data classes go LAST and get a default**, so the
  positional construction across the existing test suite keeps compiling. This is a
  documented repo convention — see the kdoc on `SpeechUnit.panel` and
  `ReaderUiState.Line.panel`.
- **Tests run with** `./gradlew :app:testDebugUnitTest`. Robolectric is pinned at
  `sdk = 34`; do not change it.
- **If Gradle fails with `BindException`, "Unable to delete directory
  .../test-results", or `NoClassDefFoundError: StorytellerApp_GeneratedInjector`**,
  the fault is environmental, not in the code: run `./gradlew --stop`, delete
  `app/build/test-results` and `app/build/reports` (for the `NoClassDefFoundError`,
  delete all of `app/build`), and retry. Do not "fix" code in response to these.

---

## File Structure

**Created**

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/storyteller/domain/model/VoiceProfile.kt` | `VoiceProfile` (id, name, gender, age) + `VoiceChoice`; the pure `chooseTrio` selection rule |
| `app/src/main/kotlin/com/storyteller/data/voice/VoiceListJson.kt` | Encodes/decodes the cached profile list for the `voice_list` row |
| `app/src/main/kotlin/com/storyteller/ui/voice/VoicePickerUiState.kt` | What the picker screen renders |
| `app/src/main/kotlin/com/storyteller/ui/voice/VoicePickerViewModel.kt` | Loads choices, auditions, assigns, triggers re-preparation |
| `app/src/main/kotlin/com/storyteller/ui/voice/VoicePickerScreen.kt` | Three cards, a play control each, a confirm |
| `app/src/test/kotlin/com/storyteller/domain/model/VoiceProfileTest.kt` | The trio rule |
| `app/src/test/kotlin/com/storyteller/data/voice/VoiceListJsonTest.kt` | Round-trip and forward-compatibility of the cached list |
| `app/src/test/kotlin/com/storyteller/ui/voice/VoicePickerViewModelTest.kt` | Audition line, taken set, assign, re-prepare |
| `app/src/test/kotlin/com/storyteller/ui/voice/VoicePickerScreenTest.kt` | Card rendering and taps |

**Modified**

| File | Change |
|---|---|
| `data/voice/ElevenLabsVoiceApi.kt` | `VoiceDto` gains `labels` |
| `data/local/Entities.kt` | `VoiceListEntity.voiceIdsCsv` -> `voicesJson` |
| `data/local/StorytellerDatabase.kt` | `version = 7`, `MIGRATION_6_7` |
| `di/DatabaseModule.kt` | register `MIGRATION_6_7` |
| `domain/repository/Repositories.kt` | `VoiceRepository` gains `choicesFor`, `assign` |
| `data/voice/VoiceRepositoryImpl.kt` | profile-based pool; implement the two new methods |
| `ui/reader/ReaderUiState.kt` | `Line` gains `voiceKey` |
| `ui/reader/ReaderViewModel.kt` | populate `voiceKey` |
| `ui/reader/ReaderScreen.kt` | badge replaces the inert `Text(line.speaker)`; new `onOpenVoices` parameter |
| `ui/StorytellerNavHost.kt` | `Routes.VOICE` with a `voiceKey` argument |

---

### Task 1: Voices arrive with names and labels

The account's 21 voices all carry `labels` — measured 2026-09-14, `gender` is one of
`male`/`female`/`neutral`, `age` is one of `young`/`middle_aged`/`old`, and `use_case`
marks two voices (Callum, Harry) as `characters_animation` against the conversational
and social-media rest. Nothing in the app reads any of them today; `VoiceDto` declares
only `voice_id` and `name`.

Names arrive long — `"Roger - Laid-Back, Casual, Resonant"`. A child's card shows the
part before the first `" - "`.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/domain/model/VoiceProfile.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/voice/ElevenLabsVoiceApi.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/model/VoiceProfileTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `VoiceProfile(id, name, gender, age, useCase)` with
  `val shortName: String`; `VoiceChoice(id: String, name: String, isCurrent: Boolean)`;
  `VoiceDto(voiceId, name, labels: Map<String, String>)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/storyteller/domain/model/VoiceProfileTest.kt`:

```kotlin
package com.storyteller.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceProfileTest {

    @Test fun `a card shows the name before the dash, not the marketing tail`() {
        val roger = VoiceProfile("v1", "Roger - Laid-Back, Casual, Resonant", "male", "middle_aged", "")
        assertEquals("Roger", roger.shortName)
    }

    @Test fun `a name with no dash is shown whole`() {
        assertEquals("Bella", VoiceProfile("v2", "Bella", "female", "young", "").shortName)
    }

    @Test fun `surrounding whitespace never reaches a card`() {
        assertEquals("Adam", VoiceProfile("v3", "Adam - Dominant, Firm ", "male", "middle_aged", "").shortName)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*VoiceProfileTest'`
Expected: FAIL — `Unresolved reference: VoiceProfile`.

- [ ] **Step 3: Write the type**

Create `app/src/main/kotlin/com/storyteller/domain/model/VoiceProfile.kt`:

```kotlin
package com.storyteller.domain.model

/**
 * One voice the account can use, with the two labels a choice is computed from.
 *
 * [gender], [age] and [useCase] are ElevenLabs' own label strings, carried as they
 * arrive rather than mapped onto an enum: they are provider vocabulary, a new value
 * is a voice we simply cannot contrast well rather than a crash, and an enum here
 * would turn an unfamiliar label into a parse failure that costs a child every voice.
 *
 * Measured on 2026-09-14 across the account's 21 voices: [gender] is one of
 * "male", "female", "neutral"; [age] is one of "young", "middle_aged", "old";
 * [useCase] is "characters_animation" for exactly two (Callum, Harry) and
 * conversational or social-media values for the rest. Any of them can be blank -
 * two voices already ship with no descriptive label at all.
 */
data class VoiceProfile(
    val id: String,
    val name: String,
    val gender: String,
    val age: String,
    /** Why it matters: see [contrast]. Blank for a voice the provider never labelled. */
    val useCase: String = "",
) {
    /**
     * What a child sees. ElevenLabs names carry a marketing tail - "Roger -
     * Laid-Back, Casual, Resonant" - and a card offering three of those is a wall
     * of text for someone who may not read yet.
     */
    val shortName: String get() = name.substringBefore(" - ").trim()
}

/** One of the three voices offered for a character. */
data class VoiceChoice(
    val id: String,
    val name: String,
    val isCurrent: Boolean,
)
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*VoiceProfileTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Carry labels through the API type**

Modify `app/src/main/kotlin/com/storyteller/data/voice/ElevenLabsVoiceApi.kt` — the
whole file becomes:

```kotlin
package com.storyteller.data.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

/**
 * [labels] is defaulted rather than required: a voice the provider ships with no
 * labels at all must still be usable. Two of the account's own voices already
 * carry an empty "descriptive", and a missing map must cost a voice its contrast
 * hints, never the whole list its parse.
 */
@Serializable
data class VoiceDto(
    @SerialName("voice_id") val voiceId: String,
    val name: String,
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
data class VoiceListResponse(val voices: List<VoiceDto>)

interface ElevenLabsVoiceApi {
    @GET("v1/voices")
    suspend fun voices(): VoiceListResponse
}
```

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. `VoiceRepositoryImplTest`'s fixture JSON has no `labels` key, which
is exactly why the field is defaulted — if that test fails, the default was dropped.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/model/VoiceProfile.kt \
        app/src/main/kotlin/com/storyteller/data/voice/ElevenLabsVoiceApi.kt \
        app/src/test/kotlin/com/storyteller/domain/model/VoiceProfileTest.kt
git commit -m "feat: voices arrive with the labels a choice can be computed from"
```

---

### Task 2: The cached voice list holds profiles, not ids

`voice_list` stores `voiceIdsCsv` and nothing else. The picker needs names and
labels, so the row's payload becomes JSON.

**The migration drops the table and recreates it.** `voice_list` is a pure cache of
a `GET /v1/voices` response — one row, refetched the moment it is missing. Migrating
CSV into JSON would mean inventing labels for ids we have no labels for, and the
invented values would then decide what a child is offered. Dropping it costs exactly
one API call on next launch. `character_voice` is **not** touched: every voice a
child already has stays assigned.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/data/voice/VoiceListJson.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/local/Entities.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/local/StorytellerDatabase.kt`
- Modify: `app/src/main/kotlin/com/storyteller/di/DatabaseModule.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/voice/VoiceRepositoryImpl.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/voice/VoiceListJsonTest.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/local/MigrationTest.kt`

**Interfaces:**
- Consumes: `VoiceProfile` (Task 1).
- Produces: `encodeProfiles(List<VoiceProfile>): String`,
  `decodeProfiles(String): List<VoiceProfile>`;
  `VoiceListEntity(id: Int = 1, voicesJson: String, fetchedAt: Long)`;
  `MIGRATION_6_7`; `VoiceRepositoryImpl.voicePool(): List<VoiceProfile>`.

- [ ] **Step 1: Write the failing JSON test**

Create `app/src/test/kotlin/com/storyteller/data/voice/VoiceListJsonTest.kt`:

```kotlin
package com.storyteller.data.voice

import com.storyteller.domain.model.VoiceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceListJsonTest {

    @Test fun `a profile survives a round trip`() {
        val profiles = listOf(
            VoiceProfile("v1", "Roger - Laid-Back", "male", "middle_aged", "conversational"),
            VoiceProfile("v2", "Bella", "female", "", ""),
        )
        assertEquals(profiles, decodeProfiles(encodeProfiles(profiles)))
    }

    @Test fun `a row written by a later version is read, not thrown away`() {
        val json = """[{"id":"v1","name":"Roger","gender":"male","age":"old","accent":"british"}]"""
        assertEquals(listOf(VoiceProfile("v1", "Roger", "male", "old", "")), decodeProfiles(json))
    }

    /**
     * A corrupt cache must cost one API call, never the app. This row is written
     * by us and read by us, so the only way it is malformed is a bug or a
     * half-written row - and the recovery for both is to refetch.
     */
    @Test fun `an unreadable row reads as empty rather than throwing`() {
        assertTrue(decodeProfiles("not json at all").isEmpty())
        assertTrue(decodeProfiles("").isEmpty())
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*VoiceListJsonTest'`
Expected: FAIL — `Unresolved reference: encodeProfiles`.

- [ ] **Step 3: Write the codec**

Create `app/src/main/kotlin/com/storyteller/data/voice/VoiceListJson.kt`:

```kotlin
package com.storyteller.data.voice

import com.storyteller.domain.model.VoiceProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The stored shape of a voice.
 *
 * Its own DTO rather than serializing VoiceProfile directly, for the same reason
 * StoredUnitJson keeps one: the domain type is free to change without silently
 * invalidating a cached row, and every field here is one someone decided to
 * persist.
 */
@Serializable
private data class VoiceProfileDto(
    val id: String,
    val name: String,
    val gender: String = "",
    val age: String = "",
    val useCase: String = "",
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun encodeProfiles(profiles: List<VoiceProfile>): String =
    json.encodeToString(profiles.map { VoiceProfileDto(it.id, it.name, it.gender, it.age, it.useCase) })

/**
 * Empty on anything unreadable. The caller treats empty exactly as it treats a
 * missing row - refetch - so a corrupt cache costs one API call rather than every
 * voice in the app.
 */
fun decodeProfiles(raw: String): List<VoiceProfile> = try {
    json.decodeFromString<List<VoiceProfileDto>>(raw)
        .map { VoiceProfile(it.id, it.name, it.gender, it.age, it.useCase) }
} catch (e: Exception) {
    emptyList()
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*VoiceListJsonTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit the codec**

```bash
git add app/src/main/kotlin/com/storyteller/data/voice/VoiceListJson.kt \
        app/src/test/kotlin/com/storyteller/data/voice/VoiceListJsonTest.kt
git commit -m "feat: a codec for the cached voice list"
```

- [ ] **Step 6: Write the failing migration test**

Append this test inside the existing class in
`app/src/test/kotlin/com/storyteller/data/local/MigrationTest.kt`. It already
imports `SupportSQLiteOpenHelper`, `FrameworkSQLiteOpenHelperFactory` and
`SupportSQLiteDatabase` and has `context` and `name` properties — follow the
`adding the library keeps everything already stored` test directly above it:

```kotlin
    /**
     * The voice LIST is a cache and is dropped; the voice MAP is a child's
     * choices and must survive untouched. Getting these the wrong way round
     * would silently re-randomise every character in the app.
     */
    @Test fun `widening the voice list keeps every voice already assigned`() {
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `character_voice` " +
                        "(`character` TEXT NOT NULL, `voiceId` TEXT NOT NULL, PRIMARY KEY(`character`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voice_list` " +
                        "(`id` INTEGER NOT NULL, `voiceIdsCsv` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO character_voice VALUES ('cogsley', 'v9')")
            db.execSQL("INSERT INTO voice_list VALUES (1, 'v1,v2,v3', 42)")

            MIGRATION_6_7.migrate(db)

            db.query("SELECT voiceId FROM character_voice WHERE character = 'cogsley'").use { c ->
                c.moveToFirst()
                assertEquals("a child's chosen voices must survive a cache widening", "v9", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM voice_list").use { c ->
                c.moveToFirst()
                assertEquals("the stale id-only cache goes, and is refetched", 0, c.getInt(0))
            }
            // And the new shape is usable, not merely present.
            db.execSQL("""INSERT INTO voice_list VALUES (1, '[{"id":"v1","name":"Roger"}]', 43)""")
            db.query("SELECT voicesJson FROM voice_list").use { c ->
                c.moveToFirst()
                assertEquals("""[{"id":"v1","name":"Roger"}]""", c.getString(0))
            }
        }
    }
```

- [ ] **Step 7: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*MigrationTest'`
Expected: FAIL — `Unresolved reference: MIGRATION_6_7`.

- [ ] **Step 8: Change the entity, add the migration, register it**

In `app/src/main/kotlin/com/storyteller/data/local/Entities.kt`, replace the
`VoiceListEntity` declaration:

```kotlin
/**
 * The account's voices, cached. A pure cache of GET /v1/voices: one row, refetched
 * whenever it is missing or unreadable, and safe to drop at any time.
 *
 * [voicesJson] replaced an id-only CSV in v7. Ids alone cannot fill a picker - a
 * child cannot choose between pqHfZKP75CvOlQylNhV4 and CwhRBWXzGAHq8TQ4Fs17 - and
 * cannot be contrasted on gender or age.
 */
@Entity(tableName = "voice_list")
data class VoiceListEntity(
    @PrimaryKey val id: Int = 1,
    val voicesJson: String,
    val fetchedAt: Long,
)
```

In `app/src/main/kotlin/com/storyteller/data/local/StorytellerDatabase.kt`, set
`version = 7` and append after `MIGRATION_5_6`:

```kotlin
/**
 * Widens `voice_list` from an id-only CSV to a JSON list of profiles.
 *
 * The table is DROPPED rather than converted. It is a cache of GET /v1/voices, and
 * converting would mean inventing the gender and age labels the old rows never
 * carried - values that then decide which voices a child is offered. Dropping it
 * costs one API call on next launch.
 *
 * `character_voice` is deliberately untouched. That table is the child's own
 * choices; clearing it here would silently re-randomise every character, which is
 * the exact harm MIGRATION_4_5 accepted once and must not repeat casually.
 *
 * Column order and types must match what Room generates for VoiceListEntity, or
 * validation fails on open.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `voice_list`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `voice_list` " +
                "(`id` INTEGER NOT NULL, `voicesJson` TEXT NOT NULL, " +
                "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}
```

In `app/src/main/kotlin/com/storyteller/di/DatabaseModule.kt`, add the import
`com.storyteller.data.local.MIGRATION_6_7` and extend the call:

```kotlin
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
```

- [ ] **Step 9: Make `VoiceRepositoryImpl` speak profiles**

In `app/src/main/kotlin/com/storyteller/data/voice/VoiceRepositoryImpl.kt`, the one
line inside `voiceFor` that picks from the pool becomes:

```kotlin
                val pool = voicePool()
                require(pool.isNotEmpty()) { "ElevenLabs returned no voices" }
                val chosen = pool[random.nextInt(pool.size)].id
```

and `voicePool` is replaced entirely:

```kotlin
    /**
     * The account's voices, from cache when it holds any and from the network
     * otherwise. An unreadable cached row decodes as empty, which lands here as a
     * miss and refetches - a corrupt cache costs one call, not every voice.
     */
    internal suspend fun voicePool(): List<VoiceProfile> {
        voiceListDao.get()?.let { cached ->
            val profiles = decodeProfiles(cached.voicesJson)
            if (profiles.isNotEmpty()) return profiles
        }
        val profiles = api.voices().voices.map { dto ->
            VoiceProfile(
                id = dto.voiceId,
                name = dto.name,
                gender = dto.labels["gender"].orEmpty(),
                age = dto.labels["age"].orEmpty(),
                useCase = dto.labels["use_case"].orEmpty(),
            )
        }
        voiceListDao.put(
            VoiceListEntity(voicesJson = encodeProfiles(profiles), fetchedAt = System.currentTimeMillis()),
        )
        return profiles
    }
```

Add the import `com.storyteller.domain.model.VoiceProfile`.

- [ ] **Step 10: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. `VoiceRepositoryImplTest` and `DaoTest` both construct
`VoiceListEntity`; fix their call sites to pass `voicesJson` with a JSON array
string (e.g. `"""[{"id":"v-rachel","name":"Rachel"}]"""`) rather than a CSV. If a
test asserted on `voiceIdsCsv`, assert on `voicesJson`.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data/local/ \
        app/src/main/kotlin/com/storyteller/di/DatabaseModule.kt \
        app/src/main/kotlin/com/storyteller/data/voice/VoiceRepositoryImpl.kt \
        app/src/test/kotlin/com/storyteller/data/local/MigrationTest.kt \
        app/src/test/kotlin/com/storyteller/data/voice/VoiceRepositoryImplTest.kt \
        app/src/test/kotlin/com/storyteller/data/local/DaoTest.kt
git commit -m "feat: cache voices as profiles, and keep every assigned voice across the migration"
```

---

### Task 3: The rule that decides what a child is offered

A pure function: no network, no database, no screen. This is the part worth getting
right, because it is the part a child actually experiences.

**Contrast on gender and age before timbre**, because those are the differences a
four-year-old can hear from one short sample. Scoring: a different `gender` is worth
2, a different `age` is worth 1, and a voice ElevenLabs labels
`use_case: characters_animation` gets 1 — the spec's preference for the two
character voices (Callum, Harry) over the conversational and social-media rest. The
two highest-scoring voices win; ties break on `id` so the same character sees the
same pair twice running.

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/domain/model/VoiceProfile.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/model/VoiceProfileTest.kt`

**Interfaces:**
- Consumes: `VoiceProfile`, `VoiceChoice` (Task 1).
- Produces:
  `fun chooseTrio(all: List<VoiceProfile>, currentId: String, taken: Set<String>): List<VoiceChoice>`
  — the current voice first, then up to two alternatives; never longer than 3.

- [ ] **Step 1: Write the failing tests**

Append to the class in
`app/src/test/kotlin/com/storyteller/domain/model/VoiceProfileTest.kt`, adding
`import org.junit.Assert.assertTrue`:

```kotlin
    private val roster = listOf(
        VoiceProfile("m-mid", "Roger", "male", "middle_aged", ""),
        VoiceProfile("m-mid2", "Eric", "male", "middle_aged", ""),
        VoiceProfile("f-young", "Jessica", "female", "young", ""),
        VoiceProfile("m-old", "Bill", "male", "old", ""),
        VoiceProfile("f-mid", "Bella", "female", "middle_aged", ""),
    )

    @Test fun `the voice a child already has is always the first offer`() {
        val trio = chooseTrio(roster, currentId = "m-mid", taken = emptySet())
        assertEquals("m-mid", trio.first().id)
        assertTrue("the current voice must be marked", trio.first().isCurrent)
        assertEquals(3, trio.size)
    }

    @Test fun `the alternatives differ on gender before they differ on age`() {
        val trio = chooseTrio(roster, currentId = "m-mid", taken = emptySet())
        // f-young differs on both (score 3) and f-mid on gender alone (score 2);
        // m-old differs on age alone (score 1) and must lose to both.
        assertEquals(listOf("f-young", "f-mid"), trio.drop(1).map { it.id })
    }

    @Test fun `a voice another character already speaks in is never offered`() {
        val trio = chooseTrio(roster, currentId = "m-mid", taken = setOf("f-young", "f-mid"))
        assertEquals(listOf("m-mid", "m-old", "m-mid2"), trio.map { it.id })
    }

    /** Keeping your own voice is not "taken" - it is the point of offering it. */
    @Test fun `the current voice survives being in the taken set`() {
        val trio = chooseTrio(roster, currentId = "m-mid", taken = setOf("m-mid", "f-young"))
        assertEquals("m-mid", trio.first().id)
        assertTrue(trio.none { it.id == "f-young" })
    }

    @Test fun `a pool of one degrades to one offer rather than throwing`() {
        val one = listOf(VoiceProfile("only", "Solo", "male", "old", ""))
        assertEquals(listOf("only"), chooseTrio(one, currentId = "only", taken = emptySet()).map { it.id })
    }

    /**
     * The spec's preference: a voice ElevenLabs built for characters beats an
     * equally-contrasting one built for podcasts. Callum and Harry are the only
     * two the account has.
     */
    @Test fun `a character voice beats an equally contrasting conversational one`() {
        val withCallum = roster + VoiceProfile("callum", "Callum", "male", "middle_aged", "characters_animation")
        // callum matches m-mid on gender AND age, so it scores 0 on contrast alone
        // and would never place; the character-voice point is what carries it past
        // m-mid2, which is identical to it but for the label. It ties m-old on 1
        // and wins the tie on id.
        val trio = chooseTrio(withCallum, currentId = "m-mid", taken = setOf("f-young", "f-mid"))
        assertEquals(listOf("m-mid", "callum", "m-old"), trio.map { it.id })
    }

    /**
     * A voice the cache has never heard of - assigned before this milestone, or
     * removed from the account since. It must still head the list, or a child's
     * existing voice vanishes the moment they look at it.
     */
    @Test fun `a current voice missing from the pool is still offered, by id`() {
        val trio = chooseTrio(roster, currentId = "ghost", taken = emptySet())
        assertEquals("ghost", trio.first().id)
        assertTrue(trio.first().isCurrent)
        assertEquals(3, trio.size)
    }

    @Test fun `an empty pool offers nothing rather than throwing`() {
        assertTrue(chooseTrio(emptyList(), currentId = "", taken = emptySet()).isEmpty())
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*VoiceProfileTest'`
Expected: FAIL — `Unresolved reference: chooseTrio`.

- [ ] **Step 3: Write the rule**

Append to `app/src/main/kotlin/com/storyteller/domain/model/VoiceProfile.kt`:

```kotlin
/**
 * How unlike [current] [other] sounds, as a child hears it.
 *
 * Gender outweighs age because it is the difference a four-year-old can pick out
 * of one short sample; accent and the provider's "descriptive" word are ignored
 * entirely, being differences an adult notices and a child does not. A blank label
 * scores as no difference rather than as a difference, so a voice the provider
 * failed to label never wins on an absence.
 *
 * The last point is not contrast at all but preference: ElevenLabs labels two of
 * the account's voices "characters_animation" - Callum and Harry - and a voice
 * built for characters is a better offer for a character in a book than one built
 * for podcasts, all else equal. It is worth the same as an age difference and
 * strictly less than a gender one, so it breaks ties without deciding anything.
 */
private const val CHARACTER_VOICE = "characters_animation"

private fun contrast(current: VoiceProfile, other: VoiceProfile): Int {
    var score = 0
    if (current.gender.isNotBlank() && other.gender.isNotBlank() && current.gender != other.gender) score += 2
    if (current.age.isNotBlank() && other.age.isNotBlank() && current.age != other.age) score += 1
    if (other.useCase == CHARACTER_VOICE) score += 1
    return score
}

/**
 * The voices offered for one character: the one it already has, then the two most
 * contrasting voices still free.
 *
 * The current voice comes first and is never excluded, whatever [taken] says - it
 * is what the character speaks in now, so "already in use" describes it trivially,
 * and excluding it would delete a choice a child has already made. A [currentId]
 * the pool does not contain is still offered, under its id: a voice assigned
 * before this feature existed, or since removed from the account, must not vanish
 * the moment a child looks at it.
 *
 * Fewer than three voices in the pool yields fewer than three offers. One voice is
 * not a choice, and the screen says so rather than this pretending otherwise.
 *
 * Ties break on id so a character sees the same pair twice running. The pair still
 * varies with [taken], and therefore between pages - see the spec's risks.
 */
fun chooseTrio(
    all: List<VoiceProfile>,
    currentId: String,
    taken: Set<String>,
): List<VoiceChoice> {
    if (currentId.isBlank()) return emptyList()

    val current = all.firstOrNull { it.id == currentId }
        ?: VoiceProfile(currentId, currentId, "", "", "")

    val alternatives = all
        .filter { it.id != currentId && it.id !in taken }
        .sortedWith(compareByDescending<VoiceProfile> { contrast(current, it) }.thenBy { it.id })
        .take(2)

    return listOf(VoiceChoice(current.id, current.shortName, isCurrent = true)) +
        alternatives.map { VoiceChoice(it.id, it.shortName, isCurrent = false) }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*VoiceProfileTest'`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/model/VoiceProfile.kt \
        app/src/test/kotlin/com/storyteller/domain/model/VoiceProfileTest.kt
git commit -m "feat: choose a contrasting trio, and never drop the voice a child already has"
```

---

### Task 4: `VoiceRepository` can offer, and can overwrite

`assign` is the first thing in this app that can change a voice. `VoiceDao.upsert`
exists and is `OnConflictStrategy.REPLACE`, but is called from exactly one place —
`voiceFor`, on first sight of a character — so no code path has ever written twice
to one key. That this works is worth a test rather than an assumption.

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/domain/repository/Repositories.kt`
- Modify: `app/src/main/kotlin/com/storyteller/data/voice/VoiceRepositoryImpl.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/voice/VoiceRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `chooseTrio`, `VoiceChoice`, `VoiceProfile` (Tasks 1 and 3);
  `voicePool()` (Task 2).
- Produces:
  `suspend fun choicesFor(character: String, taken: Set<String>): Result<List<VoiceChoice>>`
  and `suspend fun assign(character: String, voiceId: String): Result<Unit>` on
  `VoiceRepository`.

- [ ] **Step 1: Write the failing tests**

Append to the class in
`app/src/test/kotlin/com/storyteller/data/voice/VoiceRepositoryImplTest.kt`. The
existing `body` fixture has no labels; these enqueue their own labelled response,
following the file's existing MockWebServer setup:

```kotlin
    private val labelled = """
        {"voices":[
          {"voice_id":"m-mid","name":"Roger - Laid-Back","labels":{"gender":"male","age":"middle_aged"}},
          {"voice_id":"f-young","name":"Jessica","labels":{"gender":"female","age":"young"}},
          {"voice_id":"m-old","name":"Bill","labels":{"gender":"male","age":"old"}}
        ]}
    """.trimIndent()

    @Test fun `assign overwrites a voice already chosen`() = runTest {
        server.enqueue(MockResponse(code = 200, body = labelled))
        val repo = VoiceRepositoryImpl(api, db.voiceDao(), db.voiceListDao(), Random(1))

        val first = repo.voiceFor("cogsley").getOrThrow()
        repo.assign("cogsley", "m-old").getOrThrow()

        assertEquals("m-old", repo.voiceFor("cogsley").getOrThrow())
        assertTrue("the test is vacuous unless assign actually changed something", first != "m-old")
    }

    @Test fun `choices lead with the voice the character already speaks in`() = runTest {
        server.enqueue(MockResponse(code = 200, body = labelled))
        val repo = VoiceRepositoryImpl(api, db.voiceDao(), db.voiceListDao(), Random(1))
        repo.assign("cogsley", "m-mid").getOrThrow()

        val choices = repo.choicesFor("cogsley", taken = emptySet()).getOrThrow()

        assertEquals("m-mid", choices.first().id)
        assertTrue(choices.first().isCurrent)
        assertEquals("Roger", choices.first().name)
    }

    @Test fun `choices exclude a voice another character speaks in`() = runTest {
        server.enqueue(MockResponse(code = 200, body = labelled))
        val repo = VoiceRepositoryImpl(api, db.voiceDao(), db.voiceListDao(), Random(1))
        repo.assign("cogsley", "m-mid").getOrThrow()

        val choices = repo.choicesFor("cogsley", taken = setOf("f-young")).getOrThrow()

        assertTrue(choices.none { it.id == "f-young" })
    }

    /**
     * A character with no voice yet - a badge tapped before synthesis reached that
     * line. Asking for choices must assign one rather than failing, so what the
     * screen shows as "current" is the voice the page will actually use.
     */
    @Test fun `choices for an unknown character assign a voice first`() = runTest {
        server.enqueue(MockResponse(code = 200, body = labelled))
        val repo = VoiceRepositoryImpl(api, db.voiceDao(), db.voiceListDao(), Random(1))

        val choices = repo.choicesFor("stranger", taken = emptySet()).getOrThrow()

        assertEquals(choices.first().id, repo.voiceFor("stranger").getOrThrow())
    }

    /**
     * Offline. The current voice alone is a true and useful answer: nothing a
     * child chose is lost, and the screen says the others cannot be reached.
     */
    @Test fun `an unreachable voice list still offers the current voice alone`() = runTest {
        val repo = VoiceRepositoryImpl(api, db.voiceDao(), db.voiceListDao(), Random(1))
        repo.assign("cogsley", "m-mid").getOrThrow()
        server.enqueue(MockResponse(code = 500, body = ""))

        val choices = repo.choicesFor("cogsley", taken = emptySet()).getOrThrow()

        assertEquals(listOf("m-mid"), choices.map { it.id })
        assertTrue(choices.first().isCurrent)
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*VoiceRepositoryImplTest'`
Expected: FAIL — `Unresolved reference: assign`.

- [ ] **Step 3: Widen the interface**

In `app/src/main/kotlin/com/storyteller/domain/repository/Repositories.kt`, replace
the `VoiceRepository` declaration and add the import
`com.storyteller.domain.model.VoiceChoice`:

```kotlin
/** Returns the voice for a character, assigning and persisting one on first sight. */
interface VoiceRepository {
    suspend fun voiceFor(character: String): Result<String>

    /**
     * The voices offered for [character]: the one it already speaks in, then up to
     * two contrasting alternatives, excluding every id in [taken] - the voices the
     * other characters on the page in hand already use.
     */
    suspend fun choicesFor(character: String, taken: Set<String>): Result<List<VoiceChoice>>

    /**
     * Replaces the stored voice for [character].
     *
     * The only write path in the app that can overwrite a voice: voiceFor assigns
     * once, on first sight, and never revisits.
     */
    suspend fun assign(character: String, voiceId: String): Result<Unit>
}
```

- [ ] **Step 4: Implement both**

Append to the class in
`app/src/main/kotlin/com/storyteller/data/voice/VoiceRepositoryImpl.kt`, adding the
imports `com.storyteller.domain.model.VoiceChoice` and
`com.storyteller.domain.model.chooseTrio`:

```kotlin
    /**
     * Assigns a voice first when the character has none. A badge can be tapped on a
     * line whose synthesis has not reached it, so the character may be genuinely
     * unseen - and showing "current" for a voice the page will not use would be a
     * lie the child then hears.
     *
     * An unreachable voice list is not a failure: the current voice alone is a
     * true, useful answer. Cancellation is still rethrown.
     */
    override suspend fun choicesFor(character: String, taken: Set<String>): Result<List<VoiceChoice>> = try {
        val current = voiceFor(character).getOrThrow()
        val pool = try {
            voicePool()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
        Result.success(chooseTrio(pool, current, taken))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /**
     * Taken under the same lock voiceFor assigns under: a write racing a
     * first-sight assignment for the same character must not interleave, or the
     * child's choice loses to a random one.
     */
    override suspend fun assign(character: String, voiceId: String): Result<Unit> = try {
        lock.withLock { voiceDao.upsert(CharacterVoiceEntity(character, voiceId)) }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
```

**Do not move either call inside the other's critical section.** `Mutex` is not
reentrant, and `voiceFor` already takes `lock` on its miss path. `choicesFor` calls
`voiceFor` *before* taking any lock, and `assign` takes the lock without calling
`voiceFor`, so nothing re-enters. Nesting them would deadlock the picker permanently.

- [ ] **Step 5: Run them and watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*VoiceRepositoryImplTest'`
Expected: PASS.

- [ ] **Step 6: Widen the shared fake**

Widening the interface breaks every fake that implements it. The important one is in
`app/src/test/kotlin/com/storyteller/domain/Fakes.kt`, which today answers
`"voice-$character"` from a formula and implements only `voiceFor`. It needs a real
map, the two new methods, and a way to fail a write — **not `TODO()`**, because
Task 6 asserts against every one of them:

```kotlin
/**
 * [assigned] is a real map, not a formula, because M3's whole point is that a voice
 * can be OVERWRITTEN - a fake that derives its answer from the character name
 * cannot represent that, and would pass every test in Task 6 regardless.
 */
class FakeVoiceRepository(
    private val fail: Set<String> = emptySet(),
    private val pool: List<VoiceProfile> = emptyList(),
    var failAssign: Boolean = false,
) : VoiceRepository {
    val assigned = mutableMapOf<String, String>()
    val choicesAskedWith = mutableListOf<Pair<String, Set<String>>>()

    override suspend fun voiceFor(character: String): Result<String> =
        if (character in fail) Result.failure(IllegalStateException("no voice"))
        else Result.success(assigned.getOrPut(character) { "voice-$character" })

    override suspend fun choicesFor(character: String, taken: Set<String>): Result<List<VoiceChoice>> {
        choicesAskedWith += character to taken
        val current = voiceFor(character).getOrElse { return Result.failure(it) }
        return Result.success(chooseTrio(pool, current, taken))
    }

    override suspend fun assign(character: String, voiceId: String): Result<Unit> =
        if (failAssign) Result.failure(IllegalStateException("disk full"))
        else { assigned[character] = voiceId; Result.success(Unit) }
}
```

Add the imports `com.storyteller.domain.model.VoiceChoice`,
`com.storyteller.domain.model.VoiceProfile` and
`com.storyteller.domain.model.chooseTrio`.

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. Any other `object : VoiceRepository` in the test tree needs the two
new methods too; grep for it.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/repository/Repositories.kt \
        app/src/main/kotlin/com/storyteller/data/voice/VoiceRepositoryImpl.kt \
        app/src/test/kotlin/com/storyteller/
git commit -m "feat: offer a character three voices, and let one be chosen"
```

---

### Task 5: The badge

`ReaderScreen.kt:448` prints `Text(line.speaker, style = labelLarge)`. It looks like
a caption and does nothing. It becomes a control.

**It carries `voiceKey`, not `speaker`.** The displayed label drifts between reads —
"the boy with brown hair" one time, "the boy in the green shirt" the next — and
`voiceKey` does not. This is the whole reason M3 waited for M2, and a picker written
against the displayed string would undo exactly what M2 fixed.

`ReaderUiState.Line.voiceKey` is **non-null**, defaulting to `NARRATOR`, because
`ReadingPipelineImpl.prepare` already resolves `unit.voiceKey ?: NARRATOR`. Narration
is a keyed character with a real row in the voice map, so its badge works like any
other rather than being a dead label.

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderUiState.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderViewModel.kt:318-329`
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderScreen.kt:396-407,411-460`
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/ReaderScreenTest.kt`

**Interfaces:**
- Consumes: `NARRATOR` (`com.storyteller.domain.model.NARRATOR`).
- Produces: `ReaderUiState.Line.voiceKey: String` (last, defaulted to `NARRATOR`);
  `LineRow(..., onBadgeTap: () -> Unit)`;
  `ReaderScreen(onBack: () -> Unit, onOpenVoices: (String) -> Unit)`.

- [ ] **Step 1: Write the failing screen tests**

Append to the class in
`app/src/test/kotlin/com/storyteller/ui/reader/ReaderScreenTest.kt`, following the
file's existing compose-rule setup:

```kotlin
    /**
     * The badge reports the KEY, not the label. The label drifts between reads of
     * one page and the key does not; a picker keyed on the label would write a
     * voice against a string the next read will not produce.
     */
    @Test fun `tapping a badge reports the line's voice key, not its speaker`() {
        var reported: String? = null
        rule.setContent {
            LineRow(
                line = ReaderUiState.Line(
                    index = 0,
                    speaker = "the pink rabbit with a bandaged ear",
                    text = "Watch your step.",
                    bounds = null,
                    audioReady = true,
                    voiceKey = "cogsley",
                ),
                sounding = false,
                onTap = {},
                onBadgeTap = { reported = "cogsley" },
            )
        }

        rule.onNodeWithText("the pink rabbit with a bandaged ear").performClick()

        assertEquals("cogsley", reported)
    }

    @Test fun `a badge names its own line, not the page's first`() {
        rule.setContent {
            LineRow(
                line = ReaderUiState.Line(
                    index = 3,
                    speaker = "Bill",
                    text = "Over here!",
                    bounds = null,
                    audioReady = true,
                    voiceKey = "bill",
                ),
                sounding = false,
                onTap = {},
                onBadgeTap = {},
            )
        }
        rule.onNodeWithText("Bill").assertExists()
    }

    /**
     * A line whose audio is still being synthesised is not tappable for PLAYBACK -
     * there is nothing to play - but its voice can still be changed. Gating the
     * badge on audioReady would make the picker unreachable during exactly the
     * wait a child is most likely to fill by fiddling.
     */
    @Test fun `a badge works before the line's audio does`() {
        var tapped = false
        rule.setContent {
            LineRow(
                line = ReaderUiState.Line(
                    index = 0,
                    speaker = "Bill",
                    text = "Over here!",
                    bounds = null,
                    audioReady = false,
                    voiceKey = "bill",
                ),
                sounding = false,
                onTap = {},
                onBadgeTap = { tapped = true },
            )
        }

        rule.onNodeWithText("Bill").performClick()

        assertTrue(tapped)
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*ReaderScreenTest'`
Expected: FAIL — no `voiceKey` parameter, no `onBadgeTap` parameter.

- [ ] **Step 3: Give `Line` a key**

In `app/src/main/kotlin/com/storyteller/ui/reader/ReaderUiState.kt`, append to the
`Line` data class after `timings`, and add the import
`com.storyteller.domain.model.NARRATOR`:

```kotlin
        /**
         * The key this line's voice is remembered under - what the badge opens the
         * picker on.
         *
         * NOT [speaker]: that string drifts between reads of one page, which is
         * the whole reason the voice map stopped being keyed on it. Non-null and
         * defaulted to [NARRATOR] because ReadingPipelineImpl.prepare already
         * resolves `voiceKey ?: NARRATOR`, so narration is a keyed character with
         * a real row rather than an absence.
         *
         * Last and defaulted so the positional construction across the screen
         * tests keeps compiling.
         */
        val voiceKey: String = NARRATOR,
```

- [ ] **Step 4: Populate it**

In `app/src/main/kotlin/com/storyteller/ui/reader/ReaderViewModel.kt`, inside the
`ReaderUiState.Line(` construction around line 318, add after `timings`:

```kotlin
                    voiceKey = u.voiceKey ?: NARRATOR,
```

- [ ] **Step 5: Make the badge a control**

In `app/src/main/kotlin/com/storyteller/ui/reader/ReaderScreen.kt`, add
`onBadgeTap: () -> Unit` to `LineRow`'s parameter list immediately after `onTap`,
and replace the `Text(line.speaker, style = MaterialTheme.typography.labelLarge)`
line inside the `Row` with:

```kotlin
            // A control, not a caption: minimum touch target, visible affordance,
            // and reachable while the line's own audio is still being synthesised.
            Surface(
                onClick = onBadgeTap,
                shape = RoundedCornerShape(BADGE_CORNER),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .heightIn(min = BADGE_MIN_TOUCH)
                    .semantics { contentDescription = "Change ${line.speaker}'s voice" },
            ) {
                Text(
                    line.speaker,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
```

Add near the file's other dimension constants:

```kotlin
private val BADGE_CORNER = 12.dp

/**
 * Material's own minimum. A four-year-old's aim is worse than an adult's, and this
 * is the only control on the reading screen that is not the whole line.
 */
private val BADGE_MIN_TOUCH = 48.dp
```

Add imports `androidx.compose.material3.Surface` and
`androidx.compose.foundation.layout.heightIn`.

Then pass it through at the `LineRow(` call site around line 396:

```kotlin
                onBadgeTap = { onOpenVoices(line.voiceKey) },
```

and thread `onOpenVoices: (String) -> Unit` up through every composable between that
call site and `ReaderScreen`'s signature, so `ReaderScreen(onBack, onOpenVoices)`.

- [ ] **Step 6: Run them and watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ReaderScreenTest'`
Expected: PASS.

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. `StorytellerNavHostTest` and any other `ReaderScreen(` call site now
need the new parameter; pass `onOpenVoices = {}`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/ app/src/test/kotlin/com/storyteller/ui/
git commit -m "feat: the speaker label becomes a badge that opens on the voice key"
```

---

### Task 6: The picker

Three cards, each with a play control; confirming writes the voice and sends the
reader back to re-read the page in it.

**Where its inputs come from.** `ReadingPipeline` is `@ActivityRetainedScoped`, so
the picker injects the same instance the reader holds and reads the page in hand out
of `pipeline.state`. It needs no dispatcher of its own: `AudioRepository` already
dispatches its own work, and the player is a main-thread call.

Its inputs:

- **`taken`** — every *other* line's `voiceKey`, resolved through `voiceFor`. The
  page is already prepared, so these are lookups, not assignments.
- **the audition line** — the shortest `text` among lines whose `voiceKey` matches.
  A badge exists because the character speaks, so one always exists. Falls back to
  the character's key if the pipeline is not in a page state (process death while
  the picker was open).

**Opening stops playback.** The app has one `PagePlayer`; an audition has to come
out of it, and a page reading underneath an audition is two voices at once. The
reader's ViewModel survives on the back stack, so its position is kept.

**Confirming calls `pipeline.retry()`.** No new pipeline API: `retry()` re-prepares
from the in-memory `parsed` list without a vision call, and because the audio cache
is keyed `sha256(voiceId|spokenForm(text))`, every line the change did not touch
hits cache. Only the changed character's lines are bought. The page restarts from
the top, which is the point — the child changed the voice in order to hear it.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/ui/voice/VoicePickerUiState.kt`
- Create: `app/src/main/kotlin/com/storyteller/ui/voice/VoicePickerViewModel.kt`
- Create: `app/src/main/kotlin/com/storyteller/ui/voice/VoicePickerScreen.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/StorytellerNavHost.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/voice/VoicePickerViewModelTest.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/voice/VoicePickerScreenTest.kt`

**Interfaces:**
- Consumes: `VoiceRepository.choicesFor`/`assign` (Task 4); `ReadingPipeline.state`
  and `.retry()`; `PagePlayer.play`/`.stop()`; `AudioRepository.audioFor`;
  `spokenForm` (`com.storyteller.domain.model.spokenForm`);
  `VoicePickerViewModel(voices, pipeline, player, audio, savedState)`.
- Produces: `Routes.VOICE = "voice/{voiceKey}"` and `Routes.voice(key)`;
  `VoicePickerUiState(title, cards, selectedId, message, saving)`;
  `VoiceCard(id, name, isCurrent, isReady)`;
  `VoicePickerScreen(onBack: () -> Unit)`;
  `VoicePickerFrame(state, onBack, onPlay, onSelect, onConfirm)`.

- [ ] **Step 1: Give the audio fake a real cache key**

`FakeAudioRepository` records only `text`, so it cannot tell a re-buy in a new voice
from a cache hit — and it can only fail by text, which cannot kill one audition when
all three share a line. Give it a `(text, voiceId)` key (Task 7 depends on this) and
a per-voice failure:

```kotlin
    val requestedPairs = mutableListOf<Pair<String, String>>()
    private val cache = mutableMapOf<Pair<String, String>, File>()

    /** Distinct (text, voice) pairs bought - what a real cache would have charged for. */
    val synthesisCount: Int get() = cache.size

    /** Fails only this voice, so a test can kill one card and leave the others alive. */
    var failOnlyForVoice: String? = null
```

In `audioFor`, record the pair, return a failure when `voiceId == failOnlyForVoice`,
return `cache[key]` when it is present, and otherwise store before returning. Keep
`requested` and `failFor` exactly as they are: existing pipeline tests assert on
both.

- [ ] **Step 2: Write the failing ViewModel tests**

Create `app/src/test/kotlin/com/storyteller/ui/voice/VoicePickerViewModelTest.kt`.
Follow `LibraryViewModelTest` for the `Dispatchers.setMain(StandardTestDispatcher())`
setup and its `@After` teardown.

**Every assertion comes after `advanceUntilIdle()`** — except the one that is
deliberately about what happens before it. On a `StandardTestDispatcher` nothing in
a `launch` has run until then, so an assertion placed before it passes whether or
not the code works; that exact mistake shipped a vacuous headline test in M5.

```kotlin
package com.storyteller.ui.voice

import androidx.lifecycle.SavedStateHandle
import com.storyteller.domain.FakeAudioRepository
import com.storyteller.domain.FakeVoiceRepository
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.model.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class VoicePickerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    // The page in hand. "Go." is the shortest line ON THE PAGE but belongs to bill;
    // "Hi!" is the shortest line COGSLEY speaks, and is the one to audition on.
    private val units = listOf(
        unit(0, "Cogsley", "cogsley", "Watch your step, it is very slippery here."),
        unit(1, "Cogsley", "cogsley", "Hi!"),
        unit(2, "Bill", "bill", "Go."),
        unit(3, "Narrator", null, "And so they set off together."),
    )

    private val pool = listOf(
        VoiceProfile("voice-cogsley", "Roger", "male", "middle_aged", ""),
        VoiceProfile("voice-bill", "Eric", "male", "middle_aged", ""),
        VoiceProfile("voice-Narrator", "Brian", "male", "old", ""),
        VoiceProfile("f-young", "Jessica", "female", "young", ""),
        VoiceProfile("f-mid", "Bella", "female", "middle_aged", ""),
    )

    private lateinit var voices: FakeVoiceRepository
    private lateinit var audio: FakeAudioRepository
    private lateinit var player: RecordingPlayer
    private lateinit var pipeline: RecordingPipeline

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        voices = FakeVoiceRepository(pool = pool)
        audio = FakeAudioRepository()
        player = RecordingPlayer()
        pipeline = RecordingPipeline(PipelineState.Ready(units.map { prepared(it) }, null))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(key: String = "cogsley") = VoicePickerViewModel(
        voices = voices,
        pipeline = pipeline,
        player = player,
        audio = audio,
        savedState = SavedStateHandle(mapOf("voiceKey" to key)),
    )

    @Test fun `the audition uses the character's own shortest line`() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()

        assertEquals(
            "only the character's own shortest line is auditioned",
            listOf("Hi!"),
            audio.requestedPairs.map { it.first }.distinct(),
        )
        assertEquals("and every offered voice speaks it", 3, audio.requestedPairs.size)
    }

    @Test fun `the voices other characters speak in are excluded`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val (character, taken) = voices.choicesAskedWith.single()
        assertEquals("cogsley", character)
        assertEquals(setOf("voice-bill", "voice-Narrator"), taken)
        assertTrue(vm.uiState.value.cards.none { it.id == "voice-bill" })
    }

    @Test fun `opening the picker stops the page reading`() = runTest(dispatcher) {
        viewModel()
        // Deliberately NO advanceUntilIdle: the stop must happen during
        // construction, before any suspension point, or the page is still sounding
        // underneath the first audition.
        assertEquals(1, player.stops)
    }

    @Test fun `confirming writes the voice and re-prepares the page`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        var done = false

        vm.select("f-young")
        vm.confirm { done = true }
        advanceUntilIdle()

        assertEquals("f-young", voices.assigned["cogsley"])
        assertEquals("the page must re-prepare in the new voice", 1, pipeline.retries)
        assertTrue(done)
    }

    @Test fun `a failed write leaves the old voice and says so`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        voices.failAssign = true
        var done = false

        vm.select("f-young")
        vm.confirm { done = true }
        advanceUntilIdle()

        assertEquals("voice-cogsley", voices.assigned["cogsley"])
        assertEquals("nothing may re-prepare after a write that did not happen", 0, pipeline.retries)
        assertFalse("and the screen must not close as if it had", done)
        assertNotNull(vm.uiState.value.message)
    }

    @Test fun `an audition that cannot be synthesised disables only its own card`() =
        runTest(dispatcher) {
            audio.failOnlyForVoice = "f-young"
            val vm = viewModel()
            advanceUntilIdle()

            val cards = vm.uiState.value.cards
            assertEquals(3, cards.size)
            assertFalse(cards.single { it.id == "f-young" }.isReady)
            assertTrue(cards.filter { it.id != "f-young" }.all { it.isReady })
            assertNull("one dead card is not a page-level error", vm.uiState.value.message)
        }

    @Test fun `a picker opened with no page behind it still offers the voices`() =
        runTest(dispatcher) {
            pipeline = RecordingPipeline(PipelineState.Idle)
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals("the trio does not depend on the page", 3, vm.uiState.value.cards.size)
        }

    private fun unit(index: Int, speaker: String, key: String?, text: String) =
        SpeechUnit(index = index, speaker = speaker, text = text, bounds = null, voiceKey = key)

    private fun prepared(u: SpeechUnit) =
        PreparedUnit(u, "voice-" + (u.voiceKey ?: "Narrator"), File("/tmp/" + u.index + ".mp3"))
}
```

Add the two recorders at the bottom of the test file, importing `PagePlayer`,
`PlaybackState`, `ReadingPipeline`, `PageImage`, `MutableStateFlow` and `StateFlow`:

```kotlin
/** A PagePlayer that records rather than sounds. */
class RecordingPlayer : PagePlayer {
    var stops = 0
    val played = mutableListOf<PreparedUnit>()
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state
    override fun play(units: List<PreparedUnit>) { played += units }
    override fun append(unit: PreparedUnit) { played += unit }
    override fun endOfPage() = Unit
    override fun stop() { stops++ }
}

/**
 * A pipeline parked in one state. [retries] is the assertion that matters: it is how
 * a test sees that the page was re-prepared in the new voice, and that it was NOT
 * re-prepared after a write that failed.
 */
class RecordingPipeline(initial: PipelineState) : ReadingPipeline {
    var retries = 0
    override val state = MutableStateFlow(initial)
    override fun start(image: PageImage) = Unit
    override fun retry() { retries++ }
    override fun reset() = Unit
    override fun openStored(units: List<SpeechUnit>, image: PageImage) = Unit
}
```

- [ ] **Step 3: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*VoicePickerViewModelTest'`
Expected: FAIL — `Unresolved reference: VoicePickerViewModel`.

- [ ] **Step 4: Write the UI state**

Create `app/src/main/kotlin/com/storyteller/ui/voice/VoicePickerUiState.kt`:

```kotlin
package com.storyteller.ui.voice

/**
 * One offered voice. [isReady] is false while its audition clip is still being
 * synthesised, or for good after that synthesis failed - a card that cannot be
 * heard must not pretend it can.
 */
data class VoiceCard(
    val id: String,
    val name: String,
    val isCurrent: Boolean,
    val isReady: Boolean = false,
)

/**
 * [message] carries anything the child needs told - that the voices could not be
 * reached, or that a choice did not save. Null in the ordinary case, and NOT set by
 * a single failed audition: one dead card among three is visible on the card itself,
 * and a page-level error over it would say the screen is broken when it is not.
 */
data class VoicePickerUiState(
    val title: String = "",
    val cards: List<VoiceCard> = emptyList(),
    val selectedId: String? = null,
    val message: String? = null,
    val saving: Boolean = false,
)
```

- [ ] **Step 5: Write the ViewModel**

Create `app/src/main/kotlin/com/storyteller/ui/voice/VoicePickerViewModel.kt`:

```kotlin
package com.storyteller.ui.voice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.NARRATOR
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.model.spokenForm
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PagePlayer
import com.storyteller.domain.repository.VoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Three voices for one character, each auditioned on a line that character actually
 * speaks on the page in hand.
 *
 * Reads the page out of [ReadingPipeline], which is @ActivityRetainedScoped and so
 * is the very instance the reader behind this screen is using. Nothing travels
 * through the navigation argument but the voice KEY: the page would not survive the
 * trip, and the displayed label would be the wrong thing to key on anyway - it
 * drifts between reads, which is the whole reason the voice map stopped using it.
 */
@HiltViewModel
class VoicePickerViewModel @Inject constructor(
    private val voices: VoiceRepository,
    private val pipeline: ReadingPipeline,
    private val player: PagePlayer,
    private val audio: AudioRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val voiceKey: String = savedState.get<String>("voiceKey").orEmpty().ifBlank { NARRATOR }

    private val _uiState = MutableStateFlow(VoicePickerUiState(title = voiceKey))
    val uiState: StateFlow<VoicePickerUiState> = _uiState.asStateFlow()

    /** The clip each offered voice was auditioned with, filled in as they arrive. */
    private val clips = mutableMapOf<String, File>()

    private val page: List<SpeechUnit> = when (val s = pipeline.state.value) {
        is PipelineState.Ready -> s.units.map { it.unit }
        is PipelineState.Preparing -> s.units
        else -> emptyList()
    }

    init {
        // SYNCHRONOUS, before any launch, for the same reason LibraryViewModel.open
        // calls pipeline.reset() synchronously: this screen is already visible, and
        // a suspension point before this leaves a window in which the page is still
        // sounding underneath the first audition. There is one PagePlayer, so the
        // audition and the page cannot both have it.
        player.stop()
        load()
    }

    private fun load() = viewModelScope.launch {
        val taken = page
            .map { it.voiceKey ?: NARRATOR }
            .filter { it != voiceKey }
            .distinct()
            .mapNotNull { voices.voiceFor(it).getOrNull() }
            .toSet()

        val choices = voices.choicesFor(voiceKey, taken).getOrElse {
            _uiState.update { s -> s.copy(message = "Couldn't reach the voices just now.") }
            return@launch
        }
        _uiState.update { s ->
            s.copy(cards = choices.map { VoiceCard(it.id, it.name, it.isCurrent, isReady = false) })
        }

        // A badge exists because this character speaks, so a line always exists. The
        // key is a fallback for a picker that outlived its page - process death -
        // where speaking the character's own name beats auditioning nothing.
        val line = page
            .filter { (it.voiceKey ?: NARRATOR) == voiceKey }
            .minByOrNull { it.text.length }
            ?.text ?: voiceKey

        // One card's failure must not touch the other two, so each is its own launch
        // and its own miss. The current voice's clip is usually already on disk - it
        // is this character's own line in the voice it already speaks in - so that
        // card typically goes ready without buying anything.
        for (card in choices) {
            launch {
                val file = audio.audioFor(spokenForm(line), card.id).getOrNull() ?: return@launch
                clips[card.id] = file
                _uiState.update { s ->
                    s.copy(cards = s.cards.map { if (it.id == card.id) it.copy(isReady = true) else it })
                }
            }
        }
    }

    /** Selects a voice AND plays it: on this screen those are the same gesture. */
    fun select(id: String) {
        _uiState.update { it.copy(selectedId = id) }
        val file = clips[id] ?: return
        val sample = page.firstOrNull { (it.voiceKey ?: NARRATOR) == voiceKey } ?: return
        player.play(listOf(PreparedUnit(sample, id, file)))
    }

    /**
     * Writes the choice, then re-prepares the page in it.
     *
     * [ReadingPipeline.retry] rather than a new pipeline method: it re-prepares from
     * the in-memory parse with no vision call, and because audio is keyed
     * sha256(voiceId|spokenForm(text)), every line this change did not touch hits
     * cache. Only this character's lines are bought.
     *
     * [onDone] runs ONLY on a successful write. Closing the screen after a failed
     * one would show a child the reader re-reading in the old voice with no
     * explanation for why their choice did nothing.
     */
    fun confirm(onDone: () -> Unit) {
        val chosen = _uiState.value.selectedId ?: return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            voices.assign(voiceKey, chosen).fold(
                onSuccess = {
                    _uiState.update { it.copy(saving = false) }
                    player.stop()
                    pipeline.retry()
                    onDone()
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _uiState.update {
                        it.copy(saving = false, message = "Couldn't save that voice. Try again?")
                    }
                },
            )
        }
    }

    /** An audition must not outlive the screen that started it. */
    override fun onCleared() {
        player.stop()
    }
}
```

- [ ] **Step 6: Write the screen**

Create `app/src/main/kotlin/com/storyteller/ui/voice/VoicePickerScreen.kt` with a
stateless `VoicePickerFrame` separate from the Hilt-wired `VoicePickerScreen` — the
pattern `SettingsScreen`/`SettingsFrame` already uses, so the frame is testable
without Hilt. `Scaffold` + `TopAppBar` with a back action, titled with the
character's key. One `Card` per choice showing `name`, a play control (disabled
while `!isReady`), a selected indicator, and a confirm button enabled only when
`selectedId != null`. `message`, when non-null, renders above the cards.

- [ ] **Step 7: Wire the route**

In `app/src/main/kotlin/com/storyteller/ui/StorytellerNavHost.kt`, extend `Routes`:

```kotlin
    /**
     * The voice KEY, not the speaker label: the label drifts between reads and is
     * not what the voice map is keyed on. Encoded, because a key can be a phrase
     * with spaces and punctuation.
     */
    const val VOICE = "voice/{voiceKey}"
    fun voice(voiceKey: String) = "voice/" + Uri.encode(voiceKey)
```

and the destinations:

```kotlin
        composable(Routes.READER) {
            ReaderScreen(
                onBack = { nav.popBackStack() },
                onOpenVoices = { key -> nav.navigate(Routes.voice(key)) },
            )
        }
        composable(
            Routes.VOICE,
            arguments = listOf(navArgument("voiceKey") { type = NavType.StringType }),
        ) {
            // popBackStack, not navigate: returning must not stack a second reader
            // behind this screen. Same rule the reader follows returning to capture.
            VoicePickerScreen(onBack = { nav.popBackStack() })
        }
```

Add imports `android.net.Uri`, `androidx.navigation.NavType`,
`androidx.navigation.compose.navArgument` and
`com.storyteller.ui.voice.VoicePickerScreen`.

- [ ] **Step 8: Write the screen tests**

Create `app/src/test/kotlin/com/storyteller/ui/voice/VoicePickerScreenTest.kt`
against `VoicePickerFrame`, following `SettingsScreenTest`:

- three cards render, and exactly one shows as current;
- a card with `isReady = false` does not report a play tap;
- confirm is disabled until a card is selected;
- a non-null `message` is displayed.

- [ ] **Step 9: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/ app/src/test/kotlin/com/storyteller/ui/
git commit -m "feat: a screen that auditions three voices and writes the one a child picks"
```

---

### Task 7: Prove the economic claim, on the bench and on the device

The milestone's claim is that changing one character's voice re-buys **that
character's lines and nothing else**. It follows from content-addressed audio rather
than from any code written above, which is exactly why it needs a test that would
fail if the reasoning were wrong — and a run on a real device, because the last two
milestones each shipped a bug no unit test could see.

**Files:**
- Test: `app/src/test/kotlin/com/storyteller/domain/ReadingPipelineVoiceChangeTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Write the failing integration test**

Create `app/src/test/kotlin/com/storyteller/domain/ReadingPipelineVoiceChangeTest.kt`,
following `ReadingPipelineRetryTest` for construction and dispatcher handling. Use a
counting `AudioRepository` fake that records every `(text, voiceId)` pair it is asked
for and caches by that pair, so a repeat ask is answerable without counting as a new
synthesis.

```kotlin
    /**
     * The milestone's economic claim. Changing one character's voice must re-buy
     * that character's lines and NOTHING else: the audio cache is keyed
     * sha256(voiceId|spokenForm(text)), so every untouched line hits.
     */
    @Test fun `changing one voice re-buys only that character's lines`() = runTest {
        // Page: cogsley x2, bill x1, narrator x1.
        pipeline.start(image)
        advanceUntilIdle()
        val afterFirstRead = audio.synthesisCount

        voices.assign("cogsley", "v-new")
        pipeline.retry()
        advanceUntilIdle()

        assertEquals("no second vision call on a re-prepare", 1, reader.calls)
        assertEquals(
            "only cogsley's two lines are re-bought",
            afterFirstRead + 2,
            audio.synthesisCount,
        )
    }
```

- [ ] **Step 2: Run it and watch it fail, for the right reason**

Run: `./gradlew :app:testDebugUnitTest --tests '*ReadingPipelineVoiceChangeTest'`
Expected: FAIL. **Read the failure.** It must fail on the count, not on a missing
symbol — a test that fails to compile has proved nothing yet.

- [ ] **Step 3: Make it pass**

No production change should be needed; the behaviour falls out of the cache key. If
it does not pass, the reasoning above is wrong and **that is the finding** — stop and
report it rather than adjusting the assertion to match what the code does.

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 0 failures. Record the exact test count.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/kotlin/com/storyteller/domain/ReadingPipelineVoiceChangeTest.kt
git commit -m "test: changing a voice re-buys that character's lines and no others"
```

- [ ] **Step 6: Check it on the device**

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.storyteller/.MainActivity
```

**Never launch this app with `adb shell monkey`** — it turns on the device's
auto-rotate. `am start` only.

Then, by hand, on a real page:

1. Photograph a page with at least two speakers and let it read.
2. Tap a badge. The picker opens, the page **stops**, three cards appear.
3. Play each. Confirm each is audibly a different voice, and that the clip is the
   character's own shortest line from this page.
4. Pick a new voice and confirm. The page re-reads in the new voice; every other
   character is unchanged; there is no perceptible wait for the unchanged lines.
5. Go back, tap the **same** badge again. The chosen voice now shows as current, and
   the cards play instantly — they are cached.
6. Tap a narrator line's badge. It opens and works.
7. `adb shell ls /sdcard/Android/data/com.storyteller/files/diagnostics` — no new
   bundle from the re-read. A bundle is written per vision call; a re-prepare makes
   none.

Record what actually happened, including anything that did not match. A step that
was not run is reported as not run.

---

## Decisions this plan takes that the spec left open

- **Re-preparation is `pipeline.retry()`**, not a new pipeline method. It already
  skips the vision call from the in-memory parse, and `openStored` sets that parse
  too, so it works for library pages as well as fresh ones.
- **The page restarts from the top after a change.** A child changed the voice in
  order to hear it.
- **Contrast scoring is gender 2, age 1**, ties broken by id. Deliberately crude and
  entirely inside one pure function, so it is cheap to change after hearing it.
- **The narrator gets a working badge**, because `prepare()` already keys it.
- **The badge is tappable before the line's audio is ready**, though the line itself
  is not playable then.

## Known consequences worth stating

- **Re-saving.** `retry()` on a freshly photographed page re-runs `prepareAll`, which
  saves the page again under the same id with a new timestamp. It jumps to the front
  of the library, and the old voice's clips linger until eviction. This is the
  already-deferred M5 minor "re-saving a page under the same id orphans the previous
  version's clips" — M3 makes it reachable by tapping rather than only by retrying a
  failure. Not fixed here; worth a backlog line.
- **No spend cap.** Changing a voice on a ten-line page buys ten clips, and nothing
  in this app limits how often that can happen. M3 is the first feature that lets a
  child spend money by tapping rather than by photographing. Stated in the spec's
  risks and unaddressed by this plan.
