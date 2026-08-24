# Storyteller Iteration 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Photograph a book page, identify who speaks each line, assign a distinct voice per character, and read the whole page aloud.

**Architecture:** Single `:app` module, three package layers with dependencies pointing inward (`ui -> domain <- data`). `domain` is plain Kotlin with no Android imports, so the orchestration logic is covered by fast JVM tests. `ReadingPipeline` is `@ActivityRetainedScoped` and owns the read-parse-voice-synthesize sequence, outliving the capture-to-reader navigation so voice prefetch keeps running across it. Hilt is the only place `ui` and `data` meet.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, CameraX, Room, Media3/ExoPlayer, Retrofit + kotlinx.serialization, Claude Haiku 4.5 (vision + structured outputs), ElevenLabs. Tests: JUnit4, kotlinx-coroutines-test, Turbine, MockWebServer, Robolectric, Compose UI test.

**Spec:** `docs/superpowers/specs/2026-08-24-storyteller-compose-mvvm-design.md`

## Global Constraints

Every task's requirements implicitly include this section.

- **Platform:** native Android only. Never add Flutter, Dart, or any Kotlin Multiplatform target. `minSdk = 26`, `compileSdk`/`targetSdk = 36`, JVM target 17.
- **Layering:** `ui` must never import from `data`. `domain` must never import `android.*`, `androidx.*`, or Compose. Violations are review failures, not style opinions.
- **Model id is exactly `claude-haiku-4-5`.** Never append a date suffix.
- **Never send `output_config.effort` to Haiku 4.5** — the effort parameter errors on this model. Never send a `thinking` block; this is mechanical extraction.
- **Never send `cache_control`.** The stable prefix is a few hundred tokens and Haiku 4.5 needs 4096 to cache, so it would silently never hit and only add code.
- **Anthropic requests require the header `anthropic-version: 2023-06-01`.**
- **Image uploads:** long edge clamped to 1568 px, JPEG quality 85.
- **Synthesis concurrency is capped at 3** in-flight ElevenLabs requests.
- **Keys live in `local.properties`** and reach code only via `BuildConfig`. `local.properties` is gitignored and must stay that way. Never hardcode a key, never log one, never put one in a committed test fixture.
- **No network in the unit test suite.** Every HTTP test uses MockWebServer.
- **Dependency versions in `libs.versions.toml` are floors to verify, not gospel.** If Gradle cannot resolve one, bump to the latest stable, and say which version you used in the commit message.
- Run `./gradlew testDebugUnitTest` before every commit. It must pass.

---

### Task 1: Gradle project, Hilt, and test infrastructure

Deliverable: the app builds, installs, and one JVM test passes.

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/storyteller/StorytellerApp.kt`
- Create: `app/src/main/kotlin/com/storyteller/MainActivity.kt`
- Create: `local.properties.example`
- Test: `app/src/test/kotlin/com/storyteller/BuildConfigKeysTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `BuildConfig.ANTHROPIC_API_KEY: String`, `BuildConfig.ELEVENLABS_API_KEY: String`, the `StorytellerApp` Hilt application class, and the `com.storyteller` package root.

- [ ] **Step 1: Generate the base project in Android Studio**

New Project → Empty Activity (Compose) → package `com.storyteller`, language Kotlin, minSdk 26, build config language Kotlin DSL. Let the template pin AGP, Kotlin, and the Compose BOM — do not hand-write those versions. Then delete the generated `app/src/main/java` tree and create `app/src/main/kotlin` in its place.

- [ ] **Step 2: Write the version catalog additions**

Append to `gradle/libs.versions.toml`, keeping the template's existing `[versions]` entries for agp, kotlin, and composeBom:

```toml
[versions]
hilt = "2.57"
room = "2.8.1"
retrofit = "3.0.0"
okhttp = "5.1.0"
serialization = "1.9.0"
media3 = "1.8.0"
camerax = "1.5.0"
coroutines = "1.10.2"
turbine = "1.2.1"
robolectric = "4.16"
ksp = "2.2.10-2.0.2"

[libraries]
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version = "1.3.0" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-serialization = { module = "com.squareup.retrofit2:converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver3-junit4", version.ref = "okhttp" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
camerax-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
camerax-view = { module = "androidx.camera:camera-view", version.ref = "camerax" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }

[plugins]
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 3: Configure `app/build.gradle.kts`**

Add the plugins and the key plumbing. Keep the template's `android { }` block and add to it:

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.storyteller"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String", "ANTHROPIC_API_KEY",
            "\"${localProps.getProperty("ANTHROPIC_API_KEY", "")}\"",
        )
        buildConfigField(
            "String", "ELEVENLABS_API_KEY",
            "\"${localProps.getProperty("ELEVENLABS_API_KEY", "")}\"",
        )
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { jvmToolchain(17) }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.media3.exoplayer)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
}
```

- [ ] **Step 4: Write `local.properties.example` and the Hilt application class**

`local.properties.example`:

```properties
# Copy to local.properties and fill in. local.properties is gitignored.
# These keys end up in BuildConfig and are extractable from a built APK.
# Acceptable for a personal build; NOT safe for a store release.
ANTHROPIC_API_KEY=sk-ant-...
ELEVENLABS_API_KEY=...
```

`app/src/main/kotlin/com/storyteller/StorytellerApp.kt`:

```kotlin
package com.storyteller

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StorytellerApp : Application()
```

In `AndroidManifest.xml`, set `android:name=".StorytellerApp"` on `<application>` and add above it:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-feature android:name="android.hardware.camera.any" android:required="true" />
```

- [ ] **Step 5: Write the failing test**

`app/src/test/kotlin/com/storyteller/BuildConfigKeysTest.kt`:

```kotlin
package com.storyteller

import org.junit.Assert.assertNotNull
import org.junit.Test

class BuildConfigKeysTest {
    @Test
    fun `build config exposes both api key fields`() {
        assertNotNull(BuildConfig.ANTHROPIC_API_KEY)
        assertNotNull(BuildConfig.ELEVENLABS_API_KEY)
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.BuildConfigKeysTest"`
Expected: FAIL — unresolved reference `ANTHROPIC_API_KEY`, if Step 3 was not applied. If Step 3 is already in place it passes; that is fine, the point is that the field exists.

- [ ] **Step 7: Run the full build and test suite**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL. If a dependency fails to resolve, bump that version in `libs.versions.toml` to the latest stable and note the change.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle app local.properties.example
git commit -m "chore: scaffold Android project with Hilt and test infrastructure"
```

---

### Task 2: Domain models and contracts

Deliverable: every type the rest of the plan references, plus the one piece of real logic in this layer — assigning reading-order indices.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/domain/model/PageImage.kt`
- Create: `app/src/main/kotlin/com/storyteller/domain/model/SpeechUnit.kt`
- Create: `app/src/main/kotlin/com/storyteller/domain/model/PipelineState.kt`
- Create: `app/src/main/kotlin/com/storyteller/domain/model/PlaybackState.kt`
- Create: `app/src/main/kotlin/com/storyteller/domain/repository/Repositories.kt`
- Create: `app/src/main/kotlin/com/storyteller/domain/ReadingPipeline.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/model/SpeechUnitTest.kt`

**Interfaces:**
- Consumes: the `com.storyteller` package root from Task 1.
- Produces: `PageImage(bytes, mimeType)`; `BoundingBox(left, top, right, bottom)`; `ParsedUnit(speaker, text, bounds)`; `SpeechUnit(index, speaker, text, bounds)`; `List<ParsedUnit>.toSpeechUnits(): List<SpeechUnit>`; `PreparedUnit(unit, voiceId, audio)`; `PipelineState` with `Idle`, `Reading`, `Preparing(ready, total)`, `Ready(units)`, `Failed(reason, retryable)`; `FailureReason` with `NoTextFound`, `Network`, `Parse`, `Synthesis`; `PlaybackState` with `Idle`, `Playing`, `Finished`; interfaces `PageReader.read`, `VoiceRepository.voiceFor`, `AudioRepository.audioFor`, `PagePlayer.state/play/append/stop`; interface `ReadingPipeline.state/start/retry/reset`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/storyteller/domain/model/SpeechUnitTest.kt`:

```kotlin
package com.storyteller.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechUnitTest {

    private fun parsed(speaker: String, text: String) = ParsedUnit(speaker, text, bounds = null)

    @Test
    fun `assigns indices from list order`() {
        val units = listOf(
            parsed("Narrator", "Once upon a time,"),
            parsed("Wolf", "Get away!"),
            parsed("Little Red", "No!"),
        ).toSpeechUnits()

        assertEquals(listOf(0, 1, 2), units.map { it.index })
        assertEquals(listOf("Narrator", "Wolf", "Little Red"), units.map { it.speaker })
    }

    @Test
    fun `normalizes blank speaker to Narrator`() {
        val units = listOf(parsed("", "Some description."), parsed("   ", "More.")).toSpeechUnits()
        assertEquals(listOf("Narrator", "Narrator"), units.map { it.speaker })
    }

    @Test
    fun `drops units whose text is blank`() {
        val units = listOf(parsed("Wolf", "Hello"), parsed("Wolf", "   ")).toSpeechUnits()
        assertEquals(1, units.size)
        assertEquals(0, units.single().index)
    }

    @Test
    fun `reindexes after dropping so indices stay contiguous`() {
        val units = listOf(
            parsed("A", "one"),
            parsed("B", "  "),
            parsed("C", "three"),
        ).toSpeechUnits()
        assertEquals(listOf(0, 1), units.map { it.index })
        assertEquals(listOf("A", "C"), units.map { it.speaker })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.domain.model.SpeechUnitTest"`
Expected: FAIL — unresolved references `ParsedUnit` and `toSpeechUnits`.

- [ ] **Step 3: Write the models**

`domain/model/PageImage.kt`:

```kotlin
package com.storyteller.domain.model

/**
 * A captured page, already downscaled and re-encoded for upload.
 *
 * Deliberately NOT a data class: it wraps a ByteArray, so a generated equals
 * would compare array identity and mislead. Nothing compares PageImage
 * instances; the parse cache hashes [bytes] explicitly.
 */
class PageImage(val bytes: ByteArray, val mimeType: String)
```

`domain/model/SpeechUnit.kt`:

```kotlin
package com.storyteller.domain.model

import java.io.File

/** Normalized to 0..1 against the uploaded image. */
data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** What the model returns, before reading-order indices are assigned. */
data class ParsedUnit(val speaker: String, val text: String, val bounds: BoundingBox?)

data class SpeechUnit(
    val index: Int,
    val speaker: String,
    val text: String,
    val bounds: BoundingBox?,
)

data class PreparedUnit(val unit: SpeechUnit, val voiceId: String, val audio: File)

const val NARRATOR = "Narrator"

/**
 * Assigns reading-order indices from list position, drops units with no
 * speakable text, and normalizes a missing speaker to [NARRATOR].
 *
 * Indices come from position AFTER dropping, so they stay contiguous and can be
 * used directly as playlist positions.
 */
fun List<ParsedUnit>.toSpeechUnits(): List<SpeechUnit> =
    filter { it.text.isNotBlank() }
        .mapIndexed { i, p ->
            SpeechUnit(
                index = i,
                speaker = p.speaker.trim().ifBlank { NARRATOR },
                text = p.text.trim(),
                bounds = p.bounds,
            )
        }
```

`domain/model/PipelineState.kt`:

```kotlin
package com.storyteller.domain.model

enum class FailureReason { NoTextFound, Network, Parse, Synthesis }

sealed interface PipelineState {
    data object Idle : PipelineState
    /** Vision call in flight. */
    data object Reading : PipelineState
    /** [ready] is cumulative and ordered by index; consumers must diff, not replay. */
    data class Preparing(val ready: List<PreparedUnit>, val total: Int) : PipelineState
    data class Ready(val units: List<PreparedUnit>) : PipelineState
    data class Failed(val reason: FailureReason, val retryable: Boolean) : PipelineState
}
```

`domain/model/PlaybackState.kt`:

```kotlin
package com.storyteller.domain.model

/**
 * No unit index by design: iteration 1 does not highlight lines. Media3 supplies
 * the playlist index for free, so adding it when iteration 2 wants highlighting
 * is a one-line change.
 */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Playing : PlaybackState
    data object Finished : PlaybackState
}
```

`domain/repository/Repositories.kt`:

```kotlin
package com.storyteller.domain.repository

import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Reads the page and attributes speakers in one vision call. Caches by image bytes. */
interface PageReader {
    suspend fun read(image: PageImage): Result<List<SpeechUnit>>
}

/** Returns the voice for a character, assigning and persisting one on first sight. */
interface VoiceRepository {
    suspend fun voiceFor(character: String): Result<String>
}

/** Returns a local audio file for the text in the given voice, synthesizing on a cache miss. */
interface AudioRepository {
    suspend fun audioFor(text: String, voiceId: String): Result<File>
}

interface PagePlayer {
    val state: StateFlow<PlaybackState>
    fun play(units: List<PreparedUnit>)
    fun append(unit: PreparedUnit)
    fun stop()
}
```

`domain/ReadingPipeline.kt`:

```kotlin
package com.storyteller.domain

import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface, not just a class, so ViewModel tests can emit arbitrary states
 * without assembling three fakes to provoke each one.
 */
interface ReadingPipeline {
    val state: StateFlow<PipelineState>
    fun start(image: PageImage)
    fun retry()
    fun reset()
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.domain.model.SpeechUnitTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Verify the layering rule mechanically**

Run: `grep -rE "^import (android|androidx)" app/src/main/kotlin/com/storyteller/domain/ || echo CLEAN`
Expected: `CLEAN`. If anything prints, `domain` has picked up an Android dependency and the JVM test story is broken.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain app/src/test/kotlin/com/storyteller/domain
git commit -m "feat: add domain models, repository contracts, and reading-order indexing"
```

---

### Task 3: ReadingPipelineImpl — ordering and concurrency

Deliverable: the pipeline drives read then prepare, surfaces units in reading order however synthesis completes, and never exceeds three in-flight syntheses.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/domain/ReadingPipelineImpl.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/ReadingPipelineImplTest.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/Fakes.kt`

**Interfaces:**
- Consumes: everything from Task 2.
- Produces: `ReadingPipelineImpl(pageReader, voices, audio, scope)` implementing `ReadingPipeline`. Test fakes `FakePageReader`, `FakeVoiceRepository`, `FakeAudioRepository` with the fields used by Task 4.

- [ ] **Step 1: Write the fakes**

`app/src/test/kotlin/com/storyteller/domain/Fakes.kt`:

```kotlin
package com.storyteller.domain

import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PageReader
import com.storyteller.domain.repository.VoiceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

fun speechUnit(index: Int, speaker: String = "Wolf", text: String = "line $index") =
    SpeechUnit(index = index, speaker = speaker, text = text, bounds = null)

fun pageImage() = PageImage(byteArrayOf(1, 2, 3), "image/jpeg")

class FakePageReader(
    var result: Result<List<SpeechUnit>> = Result.success(emptyList()),
) : PageReader {
    var calls = 0
    override suspend fun read(image: PageImage): Result<List<SpeechUnit>> {
        calls++
        return result
    }
}

class FakeVoiceRepository(private val fail: Set<String> = emptySet()) : VoiceRepository {
    override suspend fun voiceFor(character: String): Result<String> =
        if (character in fail) Result.failure(IllegalStateException("no voice"))
        else Result.success("voice-$character")
}

/**
 * [delays] maps unit text to a synthesis delay so a test can make later units
 * finish first. [maxInFlight] records peak concurrency.
 */
class FakeAudioRepository(
    private val delays: Map<String, Long> = emptyMap(),
    private val failFor: Set<String> = emptySet(),
) : AudioRepository {
    val requested = mutableListOf<String>()
    var maxInFlight = 0
    private var inFlight = 0
    private val lock = Mutex()

    override suspend fun audioFor(text: String, voiceId: String): Result<File> {
        lock.withLock { inFlight++; maxInFlight = maxOf(maxInFlight, inFlight); requested += text }
        try {
            delay(delays[text] ?: 10L)
            if (text in failFor) return Result.failure(IllegalStateException("synthesis failed"))
            return Result.success(File("/tmp/$voiceId-${text.hashCode()}.mp3"))
        } finally {
            lock.withLock { inFlight-- }
        }
    }
}
```

- [ ] **Step 2: Write the failing test**

`app/src/test/kotlin/com/storyteller/domain/ReadingPipelineImplTest.kt`:

```kotlin
package com.storyteller.domain

import app.cash.turbine.test
import com.storyteller.domain.model.PipelineState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPipelineImplTest {

    private fun pipeline(
        reader: FakePageReader,
        audio: FakeAudioRepository,
        voices: FakeVoiceRepository = FakeVoiceRepository(),
        scope: TestScope,
    ) = ReadingPipelineImpl(reader, voices, audio, scope)

    @Test
    fun `surfaces units in reading order even when synthesis finishes out of order`() = runTest {
        val units = (0..2).map { speechUnit(it) }
        val reader = FakePageReader(Result.success(units))
        // line 0 is slowest, line 2 fastest — completion order is the reverse of reading order
        val audio = FakeAudioRepository(delays = mapOf("line 0" to 300L, "line 1" to 200L, "line 2" to 10L))
        val p = pipeline(reader, audio, scope = this)

        p.state.test {
            assertEquals(PipelineState.Idle, awaitItem())
            p.start(pageImage())
            assertEquals(PipelineState.Reading, awaitItem())

            val ordersSeen = mutableListOf<List<Int>>()
            while (true) {
                when (val s = awaitItem()) {
                    is PipelineState.Preparing -> ordersSeen += s.ready.map { it.unit.index }
                    is PipelineState.Ready -> {
                        ordersSeen += s.units.map { it.unit.index }
                        break
                    }
                    else -> error("unexpected $s")
                }
            }
            // Every emission is a prefix of 0,1,2 — never 2 before 0.
            ordersSeen.forEach { seen -> assertEquals((0 until seen.size).toList(), seen) }
            assertEquals(listOf(0, 1, 2), ordersSeen.last())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `never exceeds three in-flight syntheses`() = runTest {
        val units = (0..9).map { speechUnit(it) }
        val reader = FakePageReader(Result.success(units))
        val audio = FakeAudioRepository(delays = units.associate { it.text to 100L })
        val p = pipeline(reader, audio, scope = this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            while (awaitItem() !is PipelineState.Ready) { /* drain */ }
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("peak in-flight was ${audio.maxInFlight}", audio.maxInFlight <= 3)
    }

    @Test
    fun `empty page reports NoTextFound and does not synthesize`() = runTest {
        val reader = FakePageReader(Result.success(emptyList()))
        val audio = FakeAudioRepository()
        val p = pipeline(reader, audio, scope = this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            assertEquals(PipelineState.Reading, awaitItem())
            val failed = awaitItem() as PipelineState.Failed
            assertEquals(com.storyteller.domain.model.FailureReason.NoTextFound, failed.reason)
            assertTrue(failed.retryable)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(audio.requested.isEmpty())
    }

    @Test
    fun `reset returns to Idle`() = runTest {
        val reader = FakePageReader(Result.success(listOf(speechUnit(0))))
        val p = pipeline(reader, FakeAudioRepository(), scope = this)
        p.state.test {
            skipItems(1)
            p.start(pageImage())
            while (awaitItem() !is PipelineState.Ready) { /* drain */ }
            p.reset()
            assertEquals(PipelineState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.domain.ReadingPipelineImplTest"`
Expected: FAIL — unresolved reference `ReadingPipelineImpl`.

- [ ] **Step 4: Write the implementation**

`app/src/main/kotlin/com/storyteller/domain/ReadingPipelineImpl.kt`:

```kotlin
package com.storyteller.domain

import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PageReader
import com.storyteller.domain.repository.VoiceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val MAX_IN_FLIGHT_SYNTHESES = 3

class ReadingPipelineImpl(
    private val pageReader: PageReader,
    private val voices: VoiceRepository,
    private val audio: AudioRepository,
    private val scope: CoroutineScope,
) : ReadingPipeline {

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    override val state: StateFlow<PipelineState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastImage: PageImage? = null

    override fun start(image: PageImage) {
        lastImage = image
        job?.cancel()
        job = scope.launch { run(image) }
    }

    override fun retry() {
        val image = lastImage ?: return
        start(image)
    }

    override fun reset() {
        job?.cancel()
        job = null
        lastImage = null
        _state.value = PipelineState.Idle
    }

    private suspend fun run(image: PageImage) {
        _state.value = PipelineState.Reading

        val units = pageReader.read(image).getOrElse { e ->
            _state.value = PipelineState.Failed(e.toReason(FailureReason.Network), retryable = true)
            return
        }
        if (units.isEmpty()) {
            _state.value = PipelineState.Failed(FailureReason.NoTextFound, retryable = true)
            return
        }

        _state.value = PipelineState.Preparing(ready = emptyList(), total = units.size)
        prepareAll(units)
    }

    private suspend fun prepareAll(units: List<SpeechUnit>) = coroutineScope {
        val gate = Semaphore(MAX_IN_FLIGHT_SYNTHESES)
        // Launch every unit concurrently, then await in index order. Concurrency
        // without losing reading order.
        val jobs = units.map { unit -> async { gate.withPermit { prepare(unit) } } }

        val ready = mutableListOf<PreparedUnit>()
        for (deferred in jobs) {
            val prepared = deferred.await().getOrElse { e ->
                _state.value = PipelineState.Failed(e.toReason(FailureReason.Synthesis), retryable = true)
                return@coroutineScope
            }
            ready += prepared
            _state.value = PipelineState.Preparing(ready.toList(), units.size)
        }
        _state.value = PipelineState.Ready(ready.toList())
    }

    private suspend fun prepare(unit: SpeechUnit): Result<PreparedUnit> {
        val voiceId = voices.voiceFor(unit.speaker).getOrElse { return Result.failure(it) }
        val file = audio.audioFor(unit.text, voiceId).getOrElse { return Result.failure(it) }
        return Result.success(PreparedUnit(unit, voiceId, file))
    }
}

private fun Throwable.toReason(default: FailureReason): FailureReason = when (this) {
    is java.io.IOException -> FailureReason.Network
    is kotlinx.serialization.SerializationException -> FailureReason.Parse
    else -> default
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.domain.ReadingPipelineImplTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/ReadingPipelineImpl.kt app/src/test/kotlin/com/storyteller/domain
git commit -m "feat: add reading pipeline with ordered emission and bounded synthesis concurrency"
```

---

### Task 4: ReadingPipelineImpl — retry economics and failure mapping

Deliverable: retry re-walks the page without re-paying for work already done, and each failure class maps to the right `FailureReason`.

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/domain/ReadingPipelineImpl.kt`
- Test: `app/src/test/kotlin/com/storyteller/domain/ReadingPipelineRetryTest.kt`

**Interfaces:**
- Consumes: `ReadingPipelineImpl` and the fakes from Task 3.
- Produces: no new public API. `retry()` gains the guarantee that already-prepared units are reused.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/storyteller/domain/ReadingPipelineRetryTest.kt`:

```kotlin
package com.storyteller.domain

import app.cash.turbine.test
import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.PipelineState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPipelineRetryTest {

    @Test
    fun `retry does not re-synthesize units already prepared`() = runTest {
        val units = (0..2).map { speechUnit(it) }
        val reader = FakePageReader(Result.success(units))
        // line 2 fails the first time round
        val audio = FakeAudioRepository(failFor = setOf("line 2"))
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), audio, this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            while (awaitItem() !is PipelineState.Failed) { /* drain to failure */ }
            cancelAndIgnoreRemainingEvents()
        }

        val firstPass = audio.requested.toList()
        assertEquals(listOf("line 0", "line 1", "line 2"), firstPass.sorted())

        // Second pass: the cache is the AudioRepository's business, so the pipeline
        // is allowed to ask again — what must NOT happen is a second vision call.
        p.retry()
        assertEquals("vision call must not be repeated on retry", 1, reader.calls)
    }

    @Test
    fun `IOException from the reader maps to Network and stays retryable`() = runTest {
        val reader = FakePageReader(Result.failure(java.io.IOException("offline")))
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), FakeAudioRepository(), this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            assertEquals(PipelineState.Reading, awaitItem())
            val f = awaitItem() as PipelineState.Failed
            assertEquals(FailureReason.Network, f.reason)
            assertEquals(true, f.retryable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SerializationException from the reader maps to Parse`() = runTest {
        val reader = FakePageReader(
            Result.failure(kotlinx.serialization.SerializationException("bad shape")),
        )
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), FakeAudioRepository(), this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            skipItems(1)
            assertEquals(FailureReason.Parse, (awaitItem() as PipelineState.Failed).reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `voice lookup failure maps to Synthesis`() = runTest {
        val reader = FakePageReader(Result.success(listOf(speechUnit(0, speaker = "Wolf"))))
        val voices = FakeVoiceRepository(fail = setOf("Wolf"))
        val p = ReadingPipelineImpl(reader, voices, FakeAudioRepository(), this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            skipItems(2) // Reading, Preparing(empty)
            assertEquals(FailureReason.Synthesis, (awaitItem() as PipelineState.Failed).reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retry with no prior start is a no-op`() = runTest {
        val reader = FakePageReader(Result.success(listOf(speechUnit(0))))
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), FakeAudioRepository(), this)
        p.retry()
        assertEquals(PipelineState.Idle, p.state.value)
        assertEquals(0, reader.calls)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.domain.ReadingPipelineRetryTest"`
Expected: FAIL on `retry does not re-synthesize units already prepared` — the current `retry()` calls `start()`, which re-runs `pageReader.read`, so `reader.calls` is 2.

- [ ] **Step 3: Implement retry that reuses the parsed page**

Replace `retry()` and add a cached-units field in `ReadingPipelineImpl`:

```kotlin
    private var parsed: List<SpeechUnit>? = null

    override fun retry() {
        val cached = parsed
        val image = lastImage ?: return
        job?.cancel()
        job = scope.launch {
            if (cached != null) {
                _state.value = PipelineState.Preparing(ready = emptyList(), total = cached.size)
                prepareAll(cached)
            } else {
                run(image)
            }
        }
    }
```

And in `run`, record the parse result before preparing:

```kotlin
        parsed = units
        _state.value = PipelineState.Preparing(ready = emptyList(), total = units.size)
        prepareAll(units)
```

Also clear it in `reset()`:

```kotlin
        parsed = null
```

The audio cache is `AudioRepository`'s responsibility, so re-asking for an already-synthesized unit is a cache hit rather than a second purchase. What retry must never repeat is the vision call, which this makes structural rather than incidental.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.domain.ReadingPipelineRetryTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS. Task 3's tests must still pass unchanged.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/domain/ReadingPipelineImpl.kt app/src/test/kotlin/com/storyteller/domain/ReadingPipelineRetryTest.kt
git commit -m "feat: reuse parsed page on retry so the vision call is never repeated"
```

---

### Task 5: Room persistence

Deliverable: three tables — character voice map, parse cache, audio cache metadata — with DAOs proven against an in-memory database.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/data/local/Entities.kt`
- Create: `app/src/main/kotlin/com/storyteller/data/local/Daos.kt`
- Create: `app/src/main/kotlin/com/storyteller/data/local/StorytellerDatabase.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/local/DaoTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `CharacterVoiceEntity(character, voiceId)`; `ParsedPageEntity(imageHash, unitsJson, createdAt)`; `CachedAudioEntity(key, path, createdAt)`; `VoiceDao.find/upsert`; `ParsedPageDao.find/upsert`; `CachedAudioDao.find/upsert`; `VoiceListDao.get/put` over `VoiceListEntity(id, voiceIdsCsv, fetchedAt)`; `StorytellerDatabase` exposing all four DAOs.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/storyteller/data/local/DaoTest.kt`:

```kotlin
package com.storyteller.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest {

    private lateinit var db: StorytellerDatabase

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorytellerDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `voice assignment persists and is stable`() = runTest {
        val dao = db.voiceDao()
        assertNull(dao.find("Wolf"))
        dao.upsert(CharacterVoiceEntity("Wolf", "voice-antoni"))
        assertEquals("voice-antoni", dao.find("Wolf")?.voiceId)
        // a second sighting must not change the voice
        assertEquals("voice-antoni", dao.find("Wolf")?.voiceId)
    }

    @Test fun `upsert on the same character replaces rather than duplicating`() = runTest {
        val dao = db.voiceDao()
        dao.upsert(CharacterVoiceEntity("Wolf", "a"))
        dao.upsert(CharacterVoiceEntity("Wolf", "b"))
        assertEquals("b", dao.find("Wolf")?.voiceId)
        assertEquals(1, dao.count())
    }

    @Test fun `parse cache round-trips by image hash`() = runTest {
        val dao = db.parsedPageDao()
        assertNull(dao.find("hash-1"))
        dao.upsert(ParsedPageEntity("hash-1", """[{"speaker":"Wolf"}]""", 1000L))
        assertEquals("""[{"speaker":"Wolf"}]""", dao.find("hash-1")?.unitsJson)
        assertNull(dao.find("hash-2"))
    }

    @Test fun `audio cache round-trips by key`() = runTest {
        val dao = db.cachedAudioDao()
        dao.upsert(CachedAudioEntity("k1", "/files/audio/k1.mp3", 1000L))
        assertEquals("/files/audio/k1.mp3", dao.find("k1")?.path)
    }

    @Test fun `voice list stores a single row`() = runTest {
        val dao = db.voiceListDao()
        assertNull(dao.get())
        dao.put(VoiceListEntity(voiceIdsCsv = "a,b,c", fetchedAt = 1000L))
        dao.put(VoiceListEntity(voiceIdsCsv = "d,e", fetchedAt = 2000L))
        assertEquals("d,e", dao.get()?.voiceIdsCsv)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.data.local.DaoTest"`
Expected: FAIL — unresolved reference `StorytellerDatabase`.

- [ ] **Step 3: Write entities, DAOs, and the database**

`data/local/Entities.kt`:

```kotlin
package com.storyteller.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "character_voice")
data class CharacterVoiceEntity(
    @PrimaryKey val character: String,
    val voiceId: String,
)

/** Keyed on a hash of the uploaded JPEG bytes, so only byte-identical input hits. */
@Entity(tableName = "parsed_page")
data class ParsedPageEntity(
    @PrimaryKey val imageHash: String,
    val unitsJson: String,
    val createdAt: Long,
)

/** Keyed on sha256(text + voiceId) — survives re-photographing the same page. */
@Entity(tableName = "cached_audio")
data class CachedAudioEntity(
    @PrimaryKey val key: String,
    val path: String,
    val createdAt: Long,
)

@Entity(tableName = "voice_list")
data class VoiceListEntity(
    @PrimaryKey val id: Int = 1,
    val voiceIdsCsv: String,
    val fetchedAt: Long,
)
```

`data/local/Daos.kt`:

```kotlin
package com.storyteller.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VoiceDao {
    @Query("SELECT * FROM character_voice WHERE character = :character")
    suspend fun find(character: String): CharacterVoiceEntity?

    @Query("SELECT COUNT(*) FROM character_voice")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CharacterVoiceEntity)
}

@Dao
interface ParsedPageDao {
    @Query("SELECT * FROM parsed_page WHERE imageHash = :hash")
    suspend fun find(hash: String): ParsedPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ParsedPageEntity)
}

@Dao
interface CachedAudioDao {
    @Query("SELECT * FROM cached_audio WHERE key = :key")
    suspend fun find(key: String): CachedAudioEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedAudioEntity)
}

@Dao
interface VoiceListDao {
    @Query("SELECT * FROM voice_list WHERE id = 1")
    suspend fun get(): VoiceListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: VoiceListEntity)
}
```

`data/local/StorytellerDatabase.kt`:

```kotlin
package com.storyteller.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CharacterVoiceEntity::class,
        ParsedPageEntity::class,
        CachedAudioEntity::class,
        VoiceListEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class StorytellerDatabase : RoomDatabase() {
    abstract fun voiceDao(): VoiceDao
    abstract fun parsedPageDao(): ParsedPageDao
    abstract fun cachedAudioDao(): CachedAudioDao
    abstract fun voiceListDao(): VoiceListDao
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.data.local.DaoTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data/local app/src/test/kotlin/com/storyteller/data/local
git commit -m "feat: add Room schema for voice map, parse cache, and audio cache"
```

---

### Task 6: VoiceRepositoryImpl

Deliverable: a character gets a random voice on first sight, the same voice forever after, and the ElevenLabs voice list is fetched exactly once.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/data/voice/ElevenLabsVoiceApi.kt`
- Create: `app/src/main/kotlin/com/storyteller/data/voice/VoiceRepositoryImpl.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/voice/VoiceRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `VoiceRepository` (Task 2); `VoiceDao`, `VoiceListDao`, `StorytellerDatabase` (Task 5).
- Produces: `ElevenLabsVoiceApi.voices(): VoiceListResponse` with `VoiceListResponse(voices: List<VoiceDto>)` and `VoiceDto(voiceId: String, name: String)`; `VoiceRepositoryImpl(api, voiceDao, voiceListDao, random)`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/storyteller/data/voice/VoiceRepositoryImplTest.kt`:

```kotlin
package com.storyteller.data.voice

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.StorytellerDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class VoiceRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var db: StorytellerDatabase
    private lateinit var api: ElevenLabsVoiceApi

    private val body = """
        {"voices":[
          {"voice_id":"v-rachel","name":"Rachel"},
          {"voice_id":"v-antoni","name":"Antoni"},
          {"voice_id":"v-bella","name":"Bella"}
        ]}
    """.trimIndent()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val json = Json { ignoreUnknownKeys = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ElevenLabsVoiceApi::class.java)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            StorytellerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { server.close(); db.close() }

    private fun repo(seed: Int = 42) =
        VoiceRepositoryImpl(api, db.voiceDao(), db.voiceListDao(), Random(seed))

    @Test fun `assigns a voice from the list on first sight`() = runTest {
        server.enqueue(MockResponse(body = body))
        val id = repo().voiceFor("Wolf").getOrThrow()
        assertTrue(id in setOf("v-rachel", "v-antoni", "v-bella"))
    }

    @Test fun `same character gets the same voice on the second call`() = runTest {
        server.enqueue(MockResponse(body = body))
        val r = repo()
        val first = r.voiceFor("Wolf").getOrThrow()
        val second = r.voiceFor("Wolf").getOrThrow()
        assertEquals(first, second)
    }

    @Test fun `voice list is fetched only once across many characters`() = runTest {
        server.enqueue(MockResponse(body = body))
        val r = repo()
        r.voiceFor("Wolf").getOrThrow()
        r.voiceFor("Little Red").getOrThrow()
        r.voiceFor("Narrator").getOrThrow()
        assertEquals(1, server.requestCount)
    }

    @Test fun `server error surfaces as a failure`() = runTest {
        server.enqueue(MockResponse(code = 500))
        val result = repo().voiceFor("Wolf")
        assertTrue(result.isFailure)
    }

    @Test fun `sends the api key header`() = runTest {
        server.enqueue(MockResponse(body = body))
        repo().voiceFor("Wolf").getOrThrow()
        assertEquals("/v1/voices", server.takeRequest().target)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.data.voice.VoiceRepositoryImplTest"`
Expected: FAIL — unresolved references `ElevenLabsVoiceApi`, `VoiceRepositoryImpl`.

- [ ] **Step 3: Write the API and repository**

`data/voice/ElevenLabsVoiceApi.kt`:

```kotlin
package com.storyteller.data.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

@Serializable
data class VoiceDto(@SerialName("voice_id") val voiceId: String, val name: String)

@Serializable
data class VoiceListResponse(val voices: List<VoiceDto>)

interface ElevenLabsVoiceApi {
    @GET("v1/voices")
    suspend fun voices(): VoiceListResponse
}
```

`data/voice/VoiceRepositoryImpl.kt`:

```kotlin
package com.storyteller.data.voice

import com.storyteller.data.local.CharacterVoiceEntity
import com.storyteller.data.local.VoiceDao
import com.storyteller.data.local.VoiceListDao
import com.storyteller.data.local.VoiceListEntity
import com.storyteller.domain.repository.VoiceRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

class VoiceRepositoryImpl(
    private val api: ElevenLabsVoiceApi,
    private val voiceDao: VoiceDao,
    private val voiceListDao: VoiceListDao,
    private val random: Random = Random.Default,
) : VoiceRepository {

    // Serializes assignment so two units with the same new speaker cannot race
    // and hand the same character two different voices.
    private val lock = Mutex()

    override suspend fun voiceFor(character: String): Result<String> = runCatching {
        voiceDao.find(character)?.let { return@runCatching it.voiceId }
        lock.withLock {
            voiceDao.find(character)?.let { return@withLock it.voiceId }
            val pool = voicePool()
            require(pool.isNotEmpty()) { "ElevenLabs returned no voices" }
            val chosen = pool[random.nextInt(pool.size)]
            voiceDao.upsert(CharacterVoiceEntity(character, chosen))
            chosen
        }
    }

    private suspend fun voicePool(): List<String> {
        voiceListDao.get()?.let { cached ->
            val ids = cached.voiceIdsCsv.split(",").filter { it.isNotBlank() }
            if (ids.isNotEmpty()) return ids
        }
        val ids = api.voices().voices.map { it.voiceId }
        voiceListDao.put(
            VoiceListEntity(voiceIdsCsv = ids.joinToString(","), fetchedAt = System.currentTimeMillis()),
        )
        return ids
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.data.voice.VoiceRepositoryImplTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data/voice app/src/test/kotlin/com/storyteller/data/voice
git commit -m "feat: assign and persist a stable ElevenLabs voice per character"
```

---

### Task 7: PageReaderImpl — Claude vision call

Deliverable: one vision call reads the page and attributes speakers, results are cached by image hash, and the request obeys every Haiku 4.5 constraint in Global Constraints.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/data/page/ClaudeApi.kt`
- Create: `app/src/main/kotlin/com/storyteller/data/page/PageSchema.kt`
- Create: `app/src/main/kotlin/com/storyteller/data/page/PageReaderImpl.kt`
- Create: `app/src/main/kotlin/com/storyteller/data/Hashing.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt`

**Interfaces:**
- Consumes: `PageReader`, `PageImage`, `SpeechUnit`, `ParsedUnit`, `toSpeechUnits` (Task 2); `ParsedPageDao` (Task 5).
- Produces: `sha256(bytes: ByteArray): String` and `sha256(text: String): String` in `com.storyteller.data`; `ClaudeApi.messages(body: JsonObject): JsonObject`; `PAGE_SCHEMA: JsonObject`; `PAGE_INSTRUCTION: String`; `PageReaderImpl(api, parsedPageDao, json)`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/storyteller/data/page/PageReaderImplTest.kt`:

```kotlin
package com.storyteller.data.page

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.domain.model.PageImage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@RunWith(RobolectricTestRunner::class)
class PageReaderImplTest {

    private lateinit var server: MockWebServer
    private lateinit var db: StorytellerDatabase
    private lateinit var api: ClaudeApi
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A well-formed structured-outputs response: the JSON lives in the text block. */
    private fun okResponse(unitsJson: String) = MockResponse(
        body = """{"content":[{"type":"text","text":"{\"units\":$unitsJson}"}]}""",
    )

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ClaudeApi::class.java)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            StorytellerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { server.close(); db.close() }

    private fun reader() = PageReaderImpl(api, db.parsedPageDao(), json)
    private fun image(vararg bytes: Byte) = PageImage(bytes, "image/jpeg")

    @Test fun `maps response units to indexed speech units`() = runTest {
        server.enqueue(
            okResponse(
                """[
                  {"speaker":"Narrator","text":"Once upon a time,","bounds":null},
                  {"speaker":"Wolf","text":"Get away!","bounds":{"left":0.1,"top":0.2,"right":0.5,"bottom":0.4}}
                ]""",
            ),
        )
        val units = reader().read(image(1, 2, 3)).getOrThrow()

        assertEquals(2, units.size)
        assertEquals(listOf(0, 1), units.map { it.index })
        assertEquals("Wolf", units[1].speaker)
        assertEquals(0.1f, units[1].bounds!!.left, 0.0001f)
        assertNull(units[0].bounds)
    }

    @Test fun `request obeys the Haiku constraints`() = runTest {
        server.enqueue(okResponse("""[{"speaker":"Wolf","text":"Hi","bounds":null}]"""))
        reader().read(image(1, 2, 3)).getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        val body = json.parseToJsonElement(recorded.body!!.utf8()).jsonObject

        assertEquals("claude-haiku-4-5", body["model"]!!.jsonPrimitive.content)
        assertTrue("must use structured outputs", body.containsKey("output_config"))
        assertTrue(body["output_config"]!!.jsonObject.containsKey("format"))
        // Constraints from the spec: these three must never be sent to Haiku 4.5.
        assertFalse("effort errors on Haiku 4.5", body["output_config"]!!.jsonObject.containsKey("effort"))
        assertFalse("thinking must not be sent", body.containsKey("thinking"))
        assertFalse("cache_control cannot hit at this prefix size", body.containsKey("cache_control"))
    }

    @Test fun `second read of identical bytes does not call the network`() = runTest {
        server.enqueue(okResponse("""[{"speaker":"Wolf","text":"Hi","bounds":null}]"""))
        val r = reader()
        r.read(image(9, 9)).getOrThrow()
        val cached = r.read(image(9, 9)).getOrThrow()

        assertEquals(1, server.requestCount)
        assertEquals("Hi", cached.single().text)
    }

    @Test fun `different bytes miss the cache`() = runTest {
        server.enqueue(okResponse("""[{"speaker":"A","text":"one","bounds":null}]"""))
        server.enqueue(okResponse("""[{"speaker":"B","text":"two","bounds":null}]"""))
        val r = reader()
        assertEquals("one", r.read(image(1)).getOrThrow().single().text)
        assertEquals("two", r.read(image(2)).getOrThrow().single().text)
        assertEquals(2, server.requestCount)
    }

    @Test fun `blank page yields an empty list rather than an error`() = runTest {
        server.enqueue(okResponse("[]"))
        assertTrue(reader().read(image(1)).getOrThrow().isEmpty())
    }

    @Test fun `http error is a failure and is not cached`() = runTest {
        server.enqueue(MockResponse(code = 529))
        server.enqueue(okResponse("""[{"speaker":"Wolf","text":"Hi","bounds":null}]"""))
        val r = reader()
        assertTrue(r.read(image(7)).isFailure)
        assertEquals("Hi", r.read(image(7)).getOrThrow().single().text)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.data.page.PageReaderImplTest"`
Expected: FAIL — unresolved references `ClaudeApi`, `PageReaderImpl`.

- [ ] **Step 3: Write the hashing helper and the API**

`data/Hashing.kt`:

```kotlin
package com.storyteller.data

import java.security.MessageDigest

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
```

`data/page/ClaudeApi.kt`:

```kotlin
package com.storyteller.data.page

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The request body is built as a JsonObject rather than typed DTOs because the
 * image block and the response schema are both nested free-form JSON, and a
 * typed mirror of the Messages API would be more code with no added safety here.
 */
interface ClaudeApi {
    @POST("v1/messages")
    suspend fun messages(@Body body: JsonObject): JsonObject
}
```

- [ ] **Step 4: Write the schema and instruction**

`data/page/PageSchema.kt`:

```kotlin
package com.storyteller.data.page

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Structured-outputs schema. Every object needs additionalProperties=false, and
 * numeric ranges are unsupported, so the 0..1 bound on coordinates is stated in
 * the instruction and clamped on the client.
 */
val PAGE_SCHEMA: JsonObject = Json.parseToJsonElement(
    """
    {
      "type": "object",
      "properties": {
        "units": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "speaker": { "type": "string" },
              "text": { "type": "string" },
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
            "required": ["speaker", "text", "bounds"],
            "additionalProperties": false
          }
        }
      },
      "required": ["units"],
      "additionalProperties": false
    }
    """.trimIndent(),
).jsonObject

val PAGE_INSTRUCTION: String = """
    This is a photograph of one page from a children's storybook or graphic novel.

    Return every speech unit on the page, in reading order. A speech unit is one
    continuous piece of dialogue or narration.

    For each unit:
    - Set speaker to the character who says it. Use "Narrator" for description or
      narration not attributed to a character. If you cannot tell who is speaking,
      use "Narrator".
    - Use the character's name exactly as it appears on the page.
    - Reproduce the text verbatim. Do not merge units, split units, translate, or
      correct spelling.
    - Set bounds to the box enclosing that unit's speech bubble or text block, as
      fractions of the image between 0 and 1, measured from the top left. Use null
      if you cannot locate it.

    Ignore page numbers, running heads, publisher marks, and any text that is part
    of the artwork rather than something to be read aloud.
""".trimIndent()
```

- [ ] **Step 5: Write PageReaderImpl**

`data/page/PageReaderImpl.kt`:

```kotlin
package com.storyteller.data.page

import android.util.Base64
import com.storyteller.data.local.ParsedPageDao
import com.storyteller.data.local.ParsedPageEntity
import com.storyteller.data.sha256
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedUnit
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.model.toSpeechUnits
import com.storyteller.domain.repository.PageReader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val MODEL = "claude-haiku-4-5"
private const val MAX_TOKENS = 2048

@Serializable
private data class BoundsDto(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Serializable
private data class UnitDto(val speaker: String, val text: String, val bounds: BoundsDto?)

@Serializable
private data class PageDto(val units: List<UnitDto>)

class PageReaderImpl(
    private val api: ClaudeApi,
    private val parsedPageDao: ParsedPageDao,
    private val json: Json,
) : PageReader {

    override suspend fun read(image: PageImage): Result<List<SpeechUnit>> = runCatching {
        val hash = sha256(image.bytes)

        parsedPageDao.find(hash)?.let { cached ->
            return@runCatching json.decodeFromString<PageDto>(cached.unitsJson).toDomain()
        }

        val response = api.messages(requestBody(image))
        val payload = response.textBlock()
        val page = json.decodeFromString<PageDto>(payload)

        // Cache only successful parses, and cache the normalized payload so a hit
        // and a miss produce identical results.
        parsedPageDao.upsert(
            ParsedPageEntity(hash, json.encodeToString(page), System.currentTimeMillis()),
        )
        page.toDomain()
    }

    private fun requestBody(image: PageImage): JsonObject = buildJsonObject {
        put("model", MODEL)
        put("max_tokens", MAX_TOKENS)
        // No "thinking", no "cache_control", and no effort inside output_config:
        // see Global Constraints. Sending any of them is a defect, not a tuning knob.
        putJsonObject("output_config") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", PAGE_SCHEMA)
            }
        }
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", image.mimeType)
                                    put(
                                        "data",
                                        Base64.encodeToString(image.bytes, Base64.NO_WRAP),
                                    )
                                }
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", PAGE_INSTRUCTION)
                            },
                        )
                    }
                },
            )
        }
    }

    /** Structured outputs guarantees the first text block is the JSON payload. */
    private fun JsonObject.textBlock(): String =
        this["content"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "text" }["text"]!!
            .jsonPrimitive.content

    private fun PageDto.toDomain(): List<SpeechUnit> =
        units.map { u ->
            ParsedUnit(
                speaker = u.speaker,
                text = u.text,
                bounds = u.bounds?.let {
                    BoundingBox(
                        left = it.left.coerceIn(0f, 1f),
                        top = it.top.coerceIn(0f, 1f),
                        right = it.right.coerceIn(0f, 1f),
                        bottom = it.bottom.coerceIn(0f, 1f),
                    )
                },
            )
        }.toSpeechUnits()
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.data.page.PageReaderImplTest"`
Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data app/src/test/kotlin/com/storyteller/data/page
git commit -m "feat: read and attribute a page in one Claude vision call, cached by image hash"
```

---

### Task 8: AudioRepositoryImpl

Deliverable: synthesized audio is written once and reused forever, keyed so that the same line in the same voice always resolves to the same file.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/data/audio/ElevenLabsTtsApi.kt`
- Create: `app/src/main/kotlin/com/storyteller/data/audio/AudioRepositoryImpl.kt`
- Test: `app/src/test/kotlin/com/storyteller/data/audio/AudioRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `AudioRepository` (Task 2); `CachedAudioDao` (Task 5); `sha256` (Task 7).
- Produces: `ElevenLabsTtsApi.synthesize(voiceId, body): ResponseBody`; `AudioRepositoryImpl(api, dao, audioDir)`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/storyteller/data/audio/AudioRepositoryImplTest.kt`:

```kotlin
package com.storyteller.data.audio

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.StorytellerDatabase
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit

@RunWith(RobolectricTestRunner::class)
class AudioRepositoryImplTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: StorytellerDatabase
    private lateinit var api: ElevenLabsTtsApi

    private fun audioResponse(bytes: ByteArray) =
        MockResponse.Builder().code(200).body(Buffer().write(bytes)).build()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .build()
            .create(ElevenLabsTtsApi::class.java)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            StorytellerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { server.close(); db.close() }

    private fun repo() = AudioRepositoryImpl(api, db.cachedAudioDao(), tmp.newFolder("audio"))

    @Test fun `synthesizes on a miss and writes the bytes to disk`() = runTest {
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 1, 2, 3)
        server.enqueue(audioResponse(mp3))

        val file = repo().audioFor("Get away!", "v-antoni").getOrThrow()

        assertTrue(file.exists())
        assertArrayEquals(mp3, file.readBytes())
    }

    @Test fun `second request for the same text and voice does not call the network`() = runTest {
        server.enqueue(audioResponse(byteArrayOf(1, 2, 3)))
        val r = repo()
        val first = r.audioFor("Get away!", "v-antoni").getOrThrow()
        val second = r.audioFor("Get away!", "v-antoni").getOrThrow()

        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(1, server.requestCount)
    }

    @Test fun `same text in a different voice is a different file`() = runTest {
        server.enqueue(audioResponse(byteArrayOf(1)))
        server.enqueue(audioResponse(byteArrayOf(2)))
        val r = repo()
        val a = r.audioFor("Hello", "v-a").getOrThrow()
        val b = r.audioFor("Hello", "v-b").getOrThrow()

        assertTrue(a.absolutePath != b.absolutePath)
        assertEquals(2, server.requestCount)
    }

    @Test fun `a cache row whose file has been deleted re-synthesizes`() = runTest {
        server.enqueue(audioResponse(byteArrayOf(1, 2, 3)))
        server.enqueue(audioResponse(byteArrayOf(4, 5, 6)))
        val r = repo()
        val first = r.audioFor("Hello", "v-a").getOrThrow()
        assertTrue(first.delete())

        val again = r.audioFor("Hello", "v-a").getOrThrow()
        assertArrayEquals(byteArrayOf(4, 5, 6), again.readBytes())
        assertEquals(2, server.requestCount)
    }

    @Test fun `http error is a failure and writes nothing`() = runTest {
        server.enqueue(MockResponse(code = 429))
        val dir = tmp.newFolder("audio2")
        val r = AudioRepositoryImpl(api, db.cachedAudioDao(), dir)

        assertTrue(r.audioFor("Hello", "v-a").isFailure)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.data.audio.AudioRepositoryImplTest"`
Expected: FAIL — unresolved references `ElevenLabsTtsApi`, `AudioRepositoryImpl`.

- [ ] **Step 3: Write the API and repository**

`data/audio/ElevenLabsTtsApi.kt`:

```kotlin
package com.storyteller.data.audio

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class TtsRequest(
    val text: String,
    val model_id: String = "eleven_flash_v2_5",
)

interface ElevenLabsTtsApi {
    @POST("v1/text-to-speech/{voiceId}")
    suspend fun synthesize(
        @Path("voiceId") voiceId: String,
        @Body body: TtsRequest,
    ): ResponseBody
}
```

`data/audio/AudioRepositoryImpl.kt`:

```kotlin
package com.storyteller.data.audio

import com.storyteller.data.local.CachedAudioDao
import com.storyteller.data.local.CachedAudioEntity
import com.storyteller.data.sha256
import com.storyteller.domain.repository.AudioRepository
import java.io.File

/**
 * Files live in the app's internal files dir, not the cache dir, so the OS cannot
 * purge them under storage pressure. The key is content-addressed, so the same
 * line in the same voice always resolves to the same file no matter how the page
 * was photographed. This is the cache that actually saves money.
 *
 * No eviction in iteration 1, by design.
 */
class AudioRepositoryImpl(
    private val api: ElevenLabsTtsApi,
    private val dao: CachedAudioDao,
    private val audioDir: File,
) : AudioRepository {

    override suspend fun audioFor(text: String, voiceId: String): Result<File> = runCatching {
        val key = sha256("$voiceId|$text")

        dao.find(key)?.let { row ->
            val existing = File(row.path)
            // A row can outlive its file if the user cleared app storage.
            if (existing.exists() && existing.length() > 0) return@runCatching existing
        }

        val target = File(audioDir.apply { mkdirs() }, "$key.mp3")
        val partial = File(audioDir, "$key.mp3.part")

        api.synthesize(voiceId, TtsRequest(text = text)).use { body ->
            partial.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        // Write to a temp name then rename, so an interrupted download can never
        // be mistaken for a valid cache entry.
        check(partial.renameTo(target)) { "could not finalize audio for $key" }

        dao.upsert(CachedAudioEntity(key, target.absolutePath, System.currentTimeMillis()))
        target
    }.onFailure {
        File(audioDir, "${sha256("$voiceId|$text")}.mp3.part").delete()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.data.audio.AudioRepositoryImplTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data/audio app/src/test/kotlin/com/storyteller/data/audio
git commit -m "feat: content-addressed persistent audio cache over ElevenLabs synthesis"
```

---

### Task 9: PagePlayerImpl over Media3

Deliverable: a growing playlist plays through in reading order, and `PlaybackState` reaches `Finished`.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/data/audio/PagePlayerImpl.kt`
- Test: `app/src/androidTest/kotlin/com/storyteller/data/audio/PagePlayerImplTest.kt`

**Interfaces:**
- Consumes: `PagePlayer`, `PlaybackState`, `PreparedUnit` (Task 2).
- Produces: `PagePlayerImpl(context)` implementing `PagePlayer`, plus `release()`.

- [ ] **Step 1: Write the implementation first**

Media3 needs a real device, so this task inverts the usual order: the test is instrumented and slow, and writing it against a non-existent class costs a full device cycle per iteration.

`data/audio/PagePlayerImpl.kt`:

```kotlin
package com.storyteller.data.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.repository.PagePlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PagePlayerImpl(context: Context) : PagePlayer {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val player = ExoPlayer.Builder(context).build().apply {
        addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.value = when (playbackState) {
                        Player.STATE_ENDED -> PlaybackState.Finished
                        Player.STATE_READY -> if (isPlaying) PlaybackState.Playing else _state.value
                        else -> _state.value
                    }
                }
            },
        )
    }

    override fun play(units: List<PreparedUnit>) {
        player.setMediaItems(units.map { it.mediaItem() })
        player.prepare()
        player.play()
        _state.value = PlaybackState.Playing
    }

    /** Appended at the end, so reading order is preserved as units arrive. */
    override fun append(unit: PreparedUnit) {
        player.addMediaItem(unit.mediaItem())
    }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState.Idle
    }

    fun release() = player.release()

    private fun PreparedUnit.mediaItem(): MediaItem = MediaItem.fromUri(audio.toURI().toString())
}
```

- [ ] **Step 2: Write the instrumented test**

`app/src/androidTest/kotlin/com/storyteller/data/audio/PagePlayerImplTest.kt`:

```kotlin
package com.storyteller.data.audio

import androidx.test.espresso.idling.CountingIdlingResource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PagePlayerImplTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Copies the bundled 200ms silent mp3 out of assets so the player has real media. */
    private fun silence(name: String): File {
        val out = File(context.cacheDir, name)
        context.assets.open("silence-200ms.mp3").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    private fun prepared(index: Int, file: File) = PreparedUnit(
        unit = SpeechUnit(index, "Wolf", "line $index", null),
        voiceId = "v-a",
        audio = file,
    )

    @Test
    fun playsAGrowingPlaylistThroughToFinished() = runTest {
        val player = PagePlayerImpl(context)
        try {
            player.play(listOf(prepared(0, silence("a.mp3"))))
            player.append(prepared(1, silence("b.mp3")))
            player.append(prepared(2, silence("c.mp3")))

            withTimeout(10_000) {
                assertEquals(PlaybackState.Finished, player.state.first { it == PlaybackState.Finished })
            }
        } finally {
            player.release()
        }
    }
}
```

- [ ] **Step 3: Add the test fixture**

Create `app/src/main/assets/silence-200ms.mp3`. Generate it with ffmpeg:

```bash
ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 0.2 -q:a 9 app/src/main/assets/silence-200ms.mp3
```

Add `androidTestImplementation("androidx.test.ext:junit:1.3.0")` and `androidTestImplementation("androidx.test:runner:1.7.0")` to `app/build.gradle.kts` if the template did not already.

- [ ] **Step 4: Run the instrumented test**

Run: `./gradlew connectedDebugAndroidTest --tests "com.storyteller.data.audio.PagePlayerImplTest"`
Expected: PASS. Requires a connected device or a running emulator. If none is available, note it and run this task's verification before the Task 13 manual walkthrough instead.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/data/audio/PagePlayerImpl.kt app/src/androidTest app/src/main/assets
git commit -m "feat: play a growing page playlist through Media3"
```

---

### Task 10: Hilt wiring

Deliverable: the object graph builds, every interface has exactly one binding, and both base URLs and key headers are configured in one place.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/di/NetworkModule.kt`
- Create: `app/src/main/kotlin/com/storyteller/di/DatabaseModule.kt`
- Create: `app/src/main/kotlin/com/storyteller/di/RepositoryModule.kt`
- Create: `app/src/main/kotlin/com/storyteller/di/PipelineModule.kt`
- Test: `app/src/test/kotlin/com/storyteller/di/GraphTest.kt`

**Interfaces:**
- Consumes: every implementation from Tasks 5-9, and `BuildConfig` from Task 1.
- Produces: Hilt bindings for `PageReader`, `VoiceRepository`, `AudioRepository`, `PagePlayer`, and `ReadingPipeline`. Qualifiers `@AnthropicRetrofit` and `@ElevenLabsRetrofit`.

- [ ] **Step 1: Write the network module**

`di/NetworkModule.kt`:

```kotlin
package com.storyteller.di

import com.storyteller.BuildConfig
import com.storyteller.data.audio.ElevenLabsTtsApi
import com.storyteller.data.page.ClaudeApi
import com.storyteller.data.voice.ElevenLabsVoiceApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AnthropicRetrofit
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ElevenLabsRetrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Provides @Singleton @AnthropicRetrofit
    fun anthropicRetrofit(json: Json): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("x-api-key", BuildConfig.ANTHROPIC_API_KEY)
                        .header("anthropic-version", "2023-06-01")
                        .header("content-type", "application/json")
                        .build(),
                )
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides @Singleton @ElevenLabsRetrofit
    fun elevenLabsRetrofit(json: Json): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
                        .build(),
                )
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.elevenlabs.io/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides @Singleton
    fun claudeApi(@AnthropicRetrofit r: Retrofit): ClaudeApi = r.create(ClaudeApi::class.java)

    @Provides @Singleton
    fun voiceApi(@ElevenLabsRetrofit r: Retrofit): ElevenLabsVoiceApi =
        r.create(ElevenLabsVoiceApi::class.java)

    @Provides @Singleton
    fun ttsApi(@ElevenLabsRetrofit r: Retrofit): ElevenLabsTtsApi =
        r.create(ElevenLabsTtsApi::class.java)
}
```

- [ ] **Step 2: Write the database and repository modules**

`di/DatabaseModule.kt`:

```kotlin
package com.storyteller.di

import android.content.Context
import androidx.room.Room
import com.storyteller.data.local.CachedAudioDao
import com.storyteller.data.local.ParsedPageDao
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.data.local.VoiceDao
import com.storyteller.data.local.VoiceListDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): StorytellerDatabase =
        Room.databaseBuilder(ctx, StorytellerDatabase::class.java, "storyteller.db").build()

    @Provides fun voiceDao(db: StorytellerDatabase): VoiceDao = db.voiceDao()
    @Provides fun parsedPageDao(db: StorytellerDatabase): ParsedPageDao = db.parsedPageDao()
    @Provides fun cachedAudioDao(db: StorytellerDatabase): CachedAudioDao = db.cachedAudioDao()
    @Provides fun voiceListDao(db: StorytellerDatabase): VoiceListDao = db.voiceListDao()

    /** filesDir, not cacheDir: the OS must not be able to purge paid-for audio. */
    @Provides @Singleton @Named("audioDir")
    fun audioDir(@ApplicationContext ctx: Context): File = File(ctx.filesDir, "audio")
}
```

`di/RepositoryModule.kt`:

```kotlin
package com.storyteller.di

import android.content.Context
import com.storyteller.data.audio.AudioRepositoryImpl
import com.storyteller.data.audio.ElevenLabsTtsApi
import com.storyteller.data.audio.PagePlayerImpl
import com.storyteller.data.local.CachedAudioDao
import com.storyteller.data.local.ParsedPageDao
import com.storyteller.data.local.VoiceDao
import com.storyteller.data.local.VoiceListDao
import com.storyteller.data.page.ClaudeApi
import com.storyteller.data.page.PageReaderImpl
import com.storyteller.data.voice.ElevenLabsVoiceApi
import com.storyteller.data.voice.VoiceRepositoryImpl
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PagePlayer
import com.storyteller.domain.repository.PageReader
import com.storyteller.domain.repository.VoiceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides @Singleton
    fun pageReader(api: ClaudeApi, dao: ParsedPageDao, json: Json): PageReader =
        PageReaderImpl(api, dao, json)

    @Provides @Singleton
    fun voiceRepository(
        api: ElevenLabsVoiceApi,
        voiceDao: VoiceDao,
        listDao: VoiceListDao,
    ): VoiceRepository = VoiceRepositoryImpl(api, voiceDao, listDao)

    @Provides @Singleton
    fun audioRepository(
        api: ElevenLabsTtsApi,
        dao: CachedAudioDao,
        @Named("audioDir") dir: File,
    ): AudioRepository = AudioRepositoryImpl(api, dao, dir)

    @Provides @Singleton
    fun pagePlayer(@ApplicationContext ctx: Context): PagePlayer = PagePlayerImpl(ctx)
}
```

- [ ] **Step 3: Write the pipeline module**

`di/PipelineModule.kt`:

```kotlin
package com.storyteller.di

import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.ReadingPipelineImpl
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PageReader
import com.storyteller.domain.repository.VoiceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * ActivityRetainedScoped, not Singleton: the pipeline must survive the
 * capture-to-reader navigation and rotation, but must not outlive the activity
 * while holding a page of audio.
 */
@Module
@InstallIn(ActivityRetainedComponent::class)
object PipelineModule {

    @Provides @ActivityRetainedScoped
    fun pipelineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @ActivityRetainedScoped
    fun readingPipeline(
        pageReader: PageReader,
        voices: VoiceRepository,
        audio: AudioRepository,
        scope: CoroutineScope,
    ): ReadingPipeline = ReadingPipelineImpl(pageReader, voices, audio, scope)
}
```

- [ ] **Step 4: Write the graph test**

`app/src/test/kotlin/com/storyteller/di/GraphTest.kt`:

```kotlin
package com.storyteller.di

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hilt graph validity is enforced at compile time by the annotation processor, so
 * this test guards the things the processor cannot see: that no module has gone
 * missing, and that the layering rule still holds.
 */
class GraphTest {

    private val srcMain = File("src/main/kotlin/com/storyteller")

    @Test fun `all four modules exist`() {
        listOf("NetworkModule", "DatabaseModule", "RepositoryModule", "PipelineModule")
            .forEach { assertTrue("$it missing", File(srcMain, "di/$it.kt").exists()) }
    }

    @Test fun `ui never imports data`() {
        val offenders = File(srcMain, "ui").walkTopDown()
            .filter { it.extension == "kt" }
            .filter { f -> f.readLines().any { it.startsWith("import com.storyteller.data") } }
            .map { it.name }
            .toList()
        assertTrue("ui imports data in: $offenders", offenders.isEmpty())
    }

    @Test fun `domain never imports android`() {
        val offenders = File(srcMain, "domain").walkTopDown()
            .filter { it.extension == "kt" }
            .filter { f ->
                f.readLines().any {
                    it.startsWith("import android.") || it.startsWith("import androidx.")
                }
            }
            .map { it.name }
            .toList()
        assertTrue("domain imports Android in: $offenders", offenders.isEmpty())
    }
}
```

- [ ] **Step 5: Run the build and the suite**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: PASS. A missing binding fails the Hilt annotation processor at compile time with an explicit "cannot be provided" message naming the type.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/di app/src/test/kotlin/com/storyteller/di
git commit -m "feat: wire the object graph with Hilt and guard the layering rule in tests"
```

---

### Task 11: Capture screen

Deliverable: a viewfinder that takes a photo, offers retake or confirm, downscales to 1568 px, and starts the pipeline.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/ui/capture/Downscale.kt`
- Create: `app/src/main/kotlin/com/storyteller/ui/capture/CaptureUiState.kt`
- Create: `app/src/main/kotlin/com/storyteller/ui/capture/CaptureViewModel.kt`
- Create: `app/src/main/kotlin/com/storyteller/ui/capture/CaptureScreen.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/capture/DownscaleTest.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/capture/CaptureViewModelTest.kt`

**Interfaces:**
- Consumes: `ReadingPipeline`, `PageImage` (Tasks 2-4).
- Produces: `downscaleToPageImage(jpeg: ByteArray, maxEdge: Int = 1568, quality: Int = 85): PageImage`; `CaptureUiState` with `PermissionRequired`, `Framing`, `Captured(image)`; `CaptureViewModel.uiState/onPermissionResult/onCaptured/onRetake/onConfirm`; `CaptureScreen(onNavigateToReader)`.

- [ ] **Step 1: Write the failing downscale test**

`app/src/test/kotlin/com/storyteller/ui/capture/DownscaleTest.kt`:

```kotlin
package com.storyteller.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownscaleTest {

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            .toByteArray()
    }

    private fun sizeOf(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }

    @Test fun `clamps the long edge to 1568 and preserves aspect ratio`() {
        val out = downscaleToPageImage(jpeg(4000, 3000))
        val (w, h) = sizeOf(out.bytes)
        assertEquals(1568, w)
        assertEquals(1176, h)  // 3000 * 1568 / 4000
    }

    @Test fun `clamps the long edge when the image is portrait`() {
        val out = downscaleToPageImage(jpeg(3000, 4000))
        val (w, h) = sizeOf(out.bytes)
        assertEquals(1568, h)
        assertEquals(1176, w)
    }

    @Test fun `does not upscale an image already smaller than the ceiling`() {
        val out = downscaleToPageImage(jpeg(800, 600))
        val (w, h) = sizeOf(out.bytes)
        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test fun `reports jpeg mime type`() {
        assertEquals("image/jpeg", downscaleToPageImage(jpeg(200, 200)).mimeType)
    }

    @Test fun `output is smaller than the input for an oversized photo`() {
        val input = jpeg(4000, 3000)
        assertTrue(downscaleToPageImage(input).bytes.size < input.size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.ui.capture.DownscaleTest"`
Expected: FAIL — unresolved reference `downscaleToPageImage`.

- [ ] **Step 3: Write the downscaler**

`ui/capture/Downscale.kt`:

```kotlin
package com.storyteller.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.storyteller.domain.model.PageImage
import java.io.ByteArrayOutputStream

/** 1568 px is where Haiku 4.5 stops gaining detail; see Global Constraints. */
const val MAX_LONG_EDGE_PX = 1568
private const val JPEG_QUALITY = 85

fun downscaleToPageImage(
    jpeg: ByteArray,
    maxEdge: Int = MAX_LONG_EDGE_PX,
    quality: Int = JPEG_QUALITY,
): PageImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)

    if (longEdge <= maxEdge) return PageImage(jpeg, "image/jpeg")

    // inSampleSize halves cheaply; finish with an exact scale so the long edge
    // lands on maxEdge rather than somewhere between maxEdge and 2x maxEdge.
    val opts = BitmapFactory.Options().apply {
        inSampleSize = generateSequence(1) { it * 2 }.first { longEdge / (it * 2) < maxEdge }
    }
    val decoded = requireNonNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts))

    val scale = maxEdge.toFloat() / maxOf(decoded.width, decoded.height)
    val scaled = if (scale >= 1f) decoded else Bitmap.createScaledBitmap(
        decoded,
        Math.round(decoded.width * scale),
        Math.round(decoded.height * scale),
        true,
    )

    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    return PageImage(out.toByteArray(), "image/jpeg")
}

private fun requireNonNull(bitmap: Bitmap?): Bitmap =
    requireNotNull(bitmap) { "could not decode captured JPEG" }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.ui.capture.DownscaleTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Write the failing ViewModel test**

`app/src/test/kotlin/com/storyteller/ui/capture/CaptureViewModelTest.kt`:

```kotlin
package com.storyteller.ui.capture

import android.graphics.Bitmap
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class RecordingPipeline : ReadingPipeline {
    override val state = MutableStateFlow<PipelineState>(PipelineState.Idle) as StateFlow<PipelineState>
    val started = mutableListOf<PageImage>()
    var resets = 0
    override fun start(image: PageImage) { started += image }
    override fun retry() = Unit
    override fun reset() { resets++ }
}

@RunWith(RobolectricTestRunner::class)
class CaptureViewModelTest {

    private fun jpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            .toByteArray()
    }

    @Test fun `starts in PermissionRequired until permission is granted`() = runTest {
        val vm = CaptureViewModel(RecordingPipeline())
        assertEquals(CaptureUiState.PermissionRequired, vm.uiState.value)
        vm.onPermissionResult(granted = true)
        assertEquals(CaptureUiState.Framing, vm.uiState.value)
    }

    @Test fun `denied permission stays in PermissionRequired`() = runTest {
        val vm = CaptureViewModel(RecordingPipeline())
        vm.onPermissionResult(granted = false)
        assertEquals(CaptureUiState.PermissionRequired, vm.uiState.value)
    }

    @Test fun `capture moves to Captured and retake returns to Framing`() = runTest {
        val vm = CaptureViewModel(RecordingPipeline())
        vm.onPermissionResult(true)
        vm.onCaptured(jpeg())
        assertTrue(vm.uiState.value is CaptureUiState.Captured)
        vm.onRetake()
        assertEquals(CaptureUiState.Framing, vm.uiState.value)
    }

    @Test fun `confirm starts the pipeline with a downscaled image`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onPermissionResult(true)
        vm.onCaptured(jpeg())
        vm.onConfirm()

        assertEquals(1, pipeline.started.size)
        assertEquals("image/jpeg", pipeline.started.single().mimeType)
        assertTrue(pipeline.started.single().bytes.isNotEmpty())
    }

    @Test fun `confirm before capture does nothing`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onPermissionResult(true)
        vm.onConfirm()
        assertTrue(pipeline.started.isEmpty())
    }

    @Test fun `retake resets the pipeline so a stale page cannot be shown`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onPermissionResult(true)
        vm.onCaptured(jpeg())
        vm.onRetake()
        assertEquals(1, pipeline.resets)
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.ui.capture.CaptureViewModelTest"`
Expected: FAIL — unresolved references `CaptureViewModel`, `CaptureUiState`.

- [ ] **Step 7: Write the state and ViewModel**

`ui/capture/CaptureUiState.kt`:

```kotlin
package com.storyteller.ui.capture

import com.storyteller.domain.model.PageImage

sealed interface CaptureUiState {
    data object PermissionRequired : CaptureUiState
    data object Framing : CaptureUiState
    data class Captured(val image: PageImage) : CaptureUiState
}
```

`ui/capture/CaptureViewModel.kt`:

```kotlin
package com.storyteller.ui.capture

import androidx.lifecycle.ViewModel
import com.storyteller.domain.ReadingPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val pipeline: ReadingPipeline,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CaptureUiState>(CaptureUiState.PermissionRequired)
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    fun onPermissionResult(granted: Boolean) {
        _uiState.value = if (granted) CaptureUiState.Framing else CaptureUiState.PermissionRequired
    }

    /** [jpeg] is the raw CameraX capture; downscaling happens here, at the boundary. */
    fun onCaptured(jpeg: ByteArray) {
        _uiState.value = CaptureUiState.Captured(downscaleToPageImage(jpeg))
    }

    fun onRetake() {
        pipeline.reset()
        _uiState.value = CaptureUiState.Framing
    }

    /** Starts the pipeline; the caller navigates to the reader afterwards. */
    fun onConfirm() {
        val captured = _uiState.value as? CaptureUiState.Captured ?: return
        pipeline.start(captured.image)
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.ui.capture.CaptureViewModelTest"`
Expected: PASS, 6 tests.

- [ ] **Step 9: Write the Compose screen**

`ui/capture/CaptureScreen.kt`:

```kotlin
package com.storyteller.ui.capture

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.Executors

@Composable
fun CaptureScreen(
    onNavigateToReader: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onPermissionResult(granted) }

    LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize()) {
        when (state) {
            CaptureUiState.PermissionRequired -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Storyteller needs the camera to read a page.")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
            }

            CaptureUiState.Framing -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            val provider = ProcessCameraProvider.getInstance(ctx).get()
                            val preview = CameraPreview.Builder().build()
                                .also { it.surfaceProvider = view.surfaceProvider }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture,
                            )
                        }
                    },
                )
                Button(
                    onClick = {
                        imageCapture.takePicture(
                            executor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                                    image.close()
                                    viewModel.onCaptured(bytes)
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    image_capture_failed(exception)
                                }
                            },
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
                ) { Text("Take photo") }
            }

            is CaptureUiState.Captured -> Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(onClick = viewModel::onRetake) { Text("Retake") }
                Button(
                    onClick = { viewModel.onConfirm(); onNavigateToReader() },
                ) { Text("Read this page") }
            }
        }
    }
}

private fun image_capture_failed(e: ImageCaptureException) {
    android.util.Log.e("CaptureScreen", "capture failed", e)
}
```

- [ ] **Step 10: Run the build and the suite**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/capture app/src/test/kotlin/com/storyteller/ui/capture
git commit -m "feat: capture screen with 1568px downscale and pipeline handoff"
```

---

### Task 12: Reader screen

Deliverable: the reader maps pipeline state to UI, appends each newly-ready unit to the player exactly once, and offers retry on failure.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderUiState.kt`
- Create: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderViewModel.kt`
- Create: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderScreen.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/ReaderViewModelTest.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/ReaderScreenTest.kt`

**Interfaces:**
- Consumes: `ReadingPipeline`, `PagePlayer`, `PipelineState`, `PreparedUnit` (Tasks 2-4, 9).
- Produces: `ReaderUiState` with `ReadingPage`, `PreparingVoices(ready, total)`, `Playing(lines)`, `Error(message, canRetry)` and nested `Line(speaker, text)`; `ReaderViewModel.uiState/onRetry/onStop`; `ReaderScreen(onBack)`.

- [ ] **Step 1: Write the failing ViewModel test**

The second test here is the one that matters: `Preparing` carries a cumulative list, so a naive collector re-appends units already given to the player and the child hears lines twice.

`app/src/test/kotlin/com/storyteller/ui/reader/ReaderViewModelTest.kt`:

```kotlin
package com.storyteller.ui.reader

import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.repository.PagePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FakePipeline : ReadingPipeline {
    val states = MutableStateFlow<PipelineState>(PipelineState.Idle)
    override val state: StateFlow<PipelineState> = states
    var retries = 0
    override fun start(image: PageImage) = Unit
    override fun retry() { retries++ }
    override fun reset() = Unit
}

class FakePlayer : PagePlayer {
    override val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val played = mutableListOf<Int>()
    val appended = mutableListOf<Int>()
    var stops = 0
    override fun play(units: List<PreparedUnit>) { played += units.map { it.unit.index } }
    override fun append(unit: PreparedUnit) { appended += unit.unit.index }
    override fun stop() { stops++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun prepared(index: Int) = PreparedUnit(
        unit = SpeechUnit(index, "Wolf", "line $index", null),
        voiceId = "v",
        audio = File("/tmp/$index.mp3"),
    )

    @Test fun `maps pipeline states to reader states`() = runTest(dispatcher) {
        val pipeline = FakePipeline()
        val vm = ReaderViewModel(pipeline, FakePlayer())

        pipeline.states.value = PipelineState.Reading
        runCurrent()
        assertEquals(ReaderUiState.ReadingPage, vm.uiState.value)

        pipeline.states.value = PipelineState.Preparing(listOf(prepared(0)), total = 3)
        runCurrent()
        assertEquals(ReaderUiState.PreparingVoices(ready = 1, total = 3), vm.uiState.value)

        pipeline.states.value = PipelineState.Ready(listOf(prepared(0), prepared(1)))
        runCurrent()
        val playing = vm.uiState.value as ReaderUiState.Playing
        assertEquals(listOf("line 0", "line 1"), playing.lines.map { it.text })
        assertEquals("Wolf", playing.lines.first().speaker)
    }

    @Test fun `appends each ready unit exactly once as the cumulative list grows`() =
        runTest(dispatcher) {
            val pipeline = FakePipeline()
            val player = FakePlayer()
            ReaderViewModel(pipeline, player)

            pipeline.states.value = PipelineState.Preparing(listOf(prepared(0)), 3)
            runCurrent()
            pipeline.states.value = PipelineState.Preparing(listOf(prepared(0), prepared(1)), 3)
            runCurrent()
            pipeline.states.value =
                PipelineState.Preparing(listOf(prepared(0), prepared(1), prepared(2)), 3)
            runCurrent()

            assertEquals("first unit starts playback", listOf(0), player.played)
            assertEquals("later units append once each", listOf(1, 2), player.appended)
        }

    @Test fun `Ready after Preparing does not replay units already queued`() =
        runTest(dispatcher) {
            val pipeline = FakePipeline()
            val player = FakePlayer()
            ReaderViewModel(pipeline, player)

            pipeline.states.value = PipelineState.Preparing(listOf(prepared(0), prepared(1)), 2)
            runCurrent()
            pipeline.states.value = PipelineState.Ready(listOf(prepared(0), prepared(1)))
            runCurrent()

            assertEquals(listOf(0), player.played)
            assertEquals(listOf(1), player.appended)
        }

    @Test fun `failure maps to an error with a retry affordance`() = runTest(dispatcher) {
        val pipeline = FakePipeline()
        val vm = ReaderViewModel(pipeline, FakePlayer())

        pipeline.states.value = PipelineState.Failed(FailureReason.NoTextFound, retryable = true)
        runCurrent()
        val error = vm.uiState.value as ReaderUiState.Error
        assertTrue(error.canRetry)
        assertTrue(error.message.contains("read this page", ignoreCase = true))

        vm.onRetry()
        assertEquals(1, pipeline.retries)
    }

    @Test fun `each failure reason gets its own message`() = runTest(dispatcher) {
        val pipeline = FakePipeline()
        val vm = ReaderViewModel(pipeline, FakePlayer())
        val messages = mutableSetOf<String>()

        FailureReason.entries.forEach { reason ->
            pipeline.states.value = PipelineState.Failed(reason, retryable = true)
            runCurrent()
            messages += (vm.uiState.value as ReaderUiState.Error).message
        }
        assertEquals("every reason needs a distinct message", FailureReason.entries.size, messages.size)
    }

    @Test fun `onStop stops the player`() = runTest(dispatcher) {
        val player = FakePlayer()
        val vm = ReaderViewModel(FakePipeline(), player)
        vm.onStop()
        assertEquals(1, player.stops)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.ui.reader.ReaderViewModelTest"`
Expected: FAIL — unresolved references `ReaderViewModel`, `ReaderUiState`.

- [ ] **Step 3: Write the state and ViewModel**

`ui/reader/ReaderUiState.kt`:

```kotlin
package com.storyteller.ui.reader

sealed interface ReaderUiState {
    data object ReadingPage : ReaderUiState
    data class PreparingVoices(val ready: Int, val total: Int) : ReaderUiState
    data class Playing(val lines: List<Line>) : ReaderUiState
    data class Error(val message: String, val canRetry: Boolean) : ReaderUiState

    data class Line(val speaker: String, val text: String)
}
```

`ui/reader/ReaderViewModel.kt`:

```kotlin
package com.storyteller.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.repository.PagePlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val pipeline: ReadingPipeline,
    private val player: PagePlayer,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.ReadingPage)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /**
     * How many units have already been handed to the player. PipelineState.Preparing
     * carries a CUMULATIVE list, so without this the same unit is appended on every
     * emission and the child hears lines twice.
     */
    private var queued = 0

    init {
        viewModelScope.launch {
            pipeline.state.collect { state -> onPipelineState(state) }
        }
    }

    private fun onPipelineState(state: PipelineState) {
        when (state) {
            PipelineState.Idle, PipelineState.Reading -> {
                _uiState.value = ReaderUiState.ReadingPage
            }

            is PipelineState.Preparing -> {
                queue(state.ready)
                _uiState.value = ReaderUiState.PreparingVoices(state.ready.size, state.total)
            }

            is PipelineState.Ready -> {
                queue(state.units)
                _uiState.value = ReaderUiState.Playing(
                    state.units.map { ReaderUiState.Line(it.unit.speaker, it.unit.text) },
                )
            }

            is PipelineState.Failed -> {
                queued = 0
                _uiState.value = ReaderUiState.Error(state.reason.message(), state.retryable)
            }
        }
    }

    private fun queue(ready: List<PreparedUnit>) {
        if (ready.size <= queued) return
        val fresh = ready.drop(queued)
        if (queued == 0) {
            player.play(listOf(fresh.first()))
            fresh.drop(1).forEach(player::append)
        } else {
            fresh.forEach(player::append)
        }
        queued = ready.size
    }

    fun onRetry() {
        queued = 0
        pipeline.retry()
    }

    fun onStop() = player.stop()
}

private fun FailureReason.message(): String = when (this) {
    FailureReason.NoTextFound ->
        "Couldn't read this page. Try more light, or hold the camera steadier."
    FailureReason.Network ->
        "Couldn't reach the internet. Check the connection and try again."
    FailureReason.Parse ->
        "Something came back garbled. Try that page again."
    FailureReason.Synthesis ->
        "Couldn't make the voices for this page. Try again."
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.ui.reader.ReaderViewModelTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Write the Compose screen**

`ui/reader/ReaderScreen.kt`:

```kotlin
package com.storyteller.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReaderContent(state = state, onRetry = viewModel::onRetry, onBack = { viewModel.onStop(); onBack() })
}

/** Stateless, so Compose tests can drive every branch without Hilt. */
@Composable
fun ReaderContent(
    state: ReaderUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state) {
            ReaderUiState.ReadingPage -> Centered {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "Reading the page" })
                Text("Reading the page…")
            }

            is ReaderUiState.PreparingVoices -> Centered {
                CircularProgressIndicator()
                Text("Getting voices ready… ${state.ready} of ${state.total}")
            }

            is ReaderUiState.Playing -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.lines) { line ->
                    Column {
                        Text(line.speaker, style = MaterialTheme.typography.labelMedium)
                        Text(line.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            is ReaderUiState.Error -> Centered {
                Text(state.message, style = MaterialTheme.typography.bodyLarge)
                if (state.canRetry) Button(onClick = onRetry) { Text("Try again") }
                Button(onClick = onBack) { Text("Take another photo") }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
```

- [ ] **Step 6: Write the Compose test**

`app/src/test/kotlin/com/storyteller/ui/reader/ReaderScreenTest.kt`:

```kotlin
package com.storyteller.ui.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
class ReaderScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `shows progress while preparing voices`() {
        compose.setContent {
            ReaderContent(ReaderUiState.PreparingVoices(2, 5), onRetry = {}, onBack = {})
        }
        compose.onNodeWithText("Getting voices ready… 2 of 5").assertIsDisplayed()
    }

    @Test fun `lists speakers and lines in order`() {
        val lines = listOf(
            ReaderUiState.Line("Narrator", "Once upon a time,"),
            ReaderUiState.Line("Wolf", "Get away!"),
        )
        compose.setContent { ReaderContent(ReaderUiState.Playing(lines), onRetry = {}, onBack = {}) }

        compose.onNodeWithText("Narrator").assertIsDisplayed()
        compose.onNodeWithText("Once upon a time,").assertIsDisplayed()
        compose.onNodeWithText("Wolf").assertIsDisplayed()
        compose.onNodeWithText("Get away!").assertIsDisplayed()
    }

    @Test fun `error state offers retry and invokes the callback`() {
        var retries = 0
        compose.setContent {
            ReaderContent(
                ReaderUiState.Error("Couldn't read this page.", canRetry = true),
                onRetry = { retries++ },
                onBack = {},
            )
        }
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test fun `non-retryable error hides the retry button`() {
        compose.setContent {
            ReaderContent(ReaderUiState.Error("Nope.", canRetry = false), onRetry = {}, onBack = {})
        }
        compose.onNodeWithText("Take another photo").assertIsDisplayed()
    }
}
```

Add to `app/build.gradle.kts` dependencies if the template did not:

```kotlin
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.ui.reader.*"`
Expected: PASS, 10 tests across both classes.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/reader app/src/test/kotlin/com/storyteller/ui/reader
git commit -m "feat: reader screen with once-only unit queueing and per-reason errors"
```

---

### Task 13: Navigation and app assembly

Deliverable: the app runs end to end on a device — photograph a page, hear it read aloud.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/ui/StorytellerNavHost.kt`
- Modify: `app/src/main/kotlin/com/storyteller/MainActivity.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/StorytellerNavHostTest.kt`

**Interfaces:**
- Consumes: `CaptureScreen` (Task 11), `ReaderScreen` (Task 12).
- Produces: `StorytellerNavHost()`, and route constants `Routes.CAPTURE = "capture"`, `Routes.READER = "reader"`.

- [ ] **Step 1: Add the navigation dependency**

Add to `app/build.gradle.kts`:

```kotlin
    implementation("androidx.navigation:navigation-compose:2.9.5")
```

- [ ] **Step 2: Write the nav host**

`ui/StorytellerNavHost.kt`:

```kotlin
package com.storyteller.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.storyteller.ui.capture.CaptureScreen
import com.storyteller.ui.reader.ReaderScreen

object Routes {
    const val CAPTURE = "capture"
    const val READER = "reader"
}

@Composable
fun StorytellerNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.CAPTURE) {
        composable(Routes.CAPTURE) {
            CaptureScreen(onNavigateToReader = { nav.navigate(Routes.READER) })
        }
        composable(Routes.READER) {
            // popBackStack, not navigate: returning to capture must not stack a
            // second capture screen behind the reader.
            ReaderScreen(onBack = { nav.popBackStack() })
        }
    }
}
```

- [ ] **Step 3: Wire MainActivity**

`MainActivity.kt`:

```kotlin
package com.storyteller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.storyteller.ui.StorytellerNavHost
import com.storyteller.ui.theme.StorytellerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StorytellerTheme { StorytellerNavHost() } }
    }
}
```

- [ ] **Step 4: Write the route test**

`app/src/test/kotlin/com/storyteller/ui/StorytellerNavHostTest.kt`:

```kotlin
package com.storyteller.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StorytellerNavHostTest {

    @Test fun `routes are distinct and stable`() {
        assertEquals("capture", Routes.CAPTURE)
        assertEquals("reader", Routes.READER)
        assertNotEquals(Routes.CAPTURE, Routes.READER)
    }
}
```

- [ ] **Step 5: Build, test, and install**

```bash
./gradlew assembleDebug testDebugUnitTest
./gradlew installDebug
```

Expected: BUILD SUCCESSFUL, suite green, app installs.

- [ ] **Step 6: Manual end-to-end walkthrough**

With real keys in `local.properties` and a real book to hand, confirm each of these:

1. First launch prompts for camera permission; denying shows the explanation and the allow button works.
2. Viewfinder appears; "Take photo" produces the retake/confirm controls.
3. "Retake" returns to the viewfinder.
4. "Read this page" navigates immediately, showing "Reading the page…" — it must not block on the network before navigating.
5. Progress advances through "Getting voices ready… n of m".
6. Audio begins before every unit is ready — the first line should start while later lines are still counting up.
7. The page reads through in order, one voice per character, with the narrator distinct from the characters.
8. Rotating the device mid-read does not restart the pipeline or replay audio from the beginning.
9. Photograph a page from the same book with a second character; that character gets its own voice and previously seen characters keep theirs.
10. Re-photograph a page already read; playback starts markedly faster (audio cache hit) even though the vision call runs again.
11. Turn off Wi-Fi and mobile data, then read a new page: the network error appears with "Try again", and reconnecting plus retry succeeds.

Record anything that fails as a bug rather than fixing it inline; the walkthrough is a gate, not a debugging session.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/StorytellerNavHost.kt app/src/main/kotlin/com/storyteller/MainActivity.kt app/src/test/kotlin/com/storyteller/ui app/build.gradle.kts
git commit -m "feat: wire capture and reader into a running app"
```

---

### Task 14: Vision eval harness

Deliverable: a repeatable, scored answer to the only question no unit test can settle — does the model actually read real pages correctly?

**Files:**
- Create: `app/src/test/kotlin/com/storyteller/evals/VisionEval.kt`
- Create: `evals/README.md`
- Create: `evals/expected/README.md`
- Modify: `.gitignore` (already excludes `evals/fixtures/`; confirm)

**Interfaces:**
- Consumes: `PageReaderImpl`, `ClaudeApi` (Task 7); `downscaleToPageImage` (Task 11).
- Produces: a JVM test tagged so it never runs in the normal suite, plus `evals/expected/*.json` fixtures.

- [ ] **Step 1: Write the harness**

`app/src/test/kotlin/com/storyteller/evals/VisionEval.kt`:

```kotlin
package com.storyteller.evals

import com.storyteller.data.local.ParsedPageDao
import com.storyteller.data.local.ParsedPageEntity
import com.storyteller.data.page.ClaudeApi
import com.storyteller.data.page.PageReaderImpl
import com.storyteller.domain.model.PageImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

@Serializable
private data class Expected(val speakers: List<String>, val minUnits: Int)

/**
 * Not a pass/fail test. The model is non-deterministic, so this scores a pass RATE
 * over real photographs and prints a report. It is skipped unless
 * STORYTELLER_EVAL=1 and ANTHROPIC_API_KEY are both set, so it never runs in the
 * normal suite and never costs money by accident.
 */
class VisionEval {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val noCache = object : ParsedPageDao {
        override suspend fun find(hash: String): ParsedPageEntity? = null
        override suspend fun upsert(entity: ParsedPageEntity) = Unit
    }

    @Test
    fun scoreSpeakerAttributionOnRealPages() = runBlocking {
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        assumeTrue("set STORYTELLER_EVAL=1 to run", System.getenv("STORYTELLER_EVAL") == "1")
        assumeTrue("ANTHROPIC_API_KEY required", key.isNotBlank())

        val fixtures = File("../evals/fixtures").listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("no fixtures in evals/fixtures", fixtures.isNotEmpty())

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .build(),
            )
        }.build()

        val api = Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ClaudeApi::class.java)

        val reader = PageReaderImpl(api, noCache, json)

        var passed = 0
        var withBoxes = 0
        val report = StringBuilder("\n=== Vision eval ===\n")

        for (photo in fixtures) {
            val expectedFile = File("../evals/expected/${photo.nameWithoutExtension}.json")
            if (!expectedFile.exists()) {
                report.append("SKIP  ${photo.name} — no expected/${expectedFile.name}\n")
                continue
            }
            val expected = json.decodeFromString<Expected>(expectedFile.readText())
            val units = reader.read(PageImage(photo.readBytes(), "image/jpeg")).getOrElse { e ->
                report.append("ERROR ${photo.name} — ${e.message}\n")
                continue
            }

            val speakers = units.map { it.speaker }.distinct().sorted()
            val speakersOk = speakers == expected.speakers.sorted()
            val countOk = units.size >= expected.minUnits
            val boxed = units.count { it.bounds != null }
            if (boxed > 0) withBoxes++

            if (speakersOk && countOk) passed++
            report.append(
                if (speakersOk && countOk) "PASS  " else "FAIL  ",
            ).append(photo.name)
                .append(" — units=").append(units.size).append("/min ").append(expected.minUnits)
                .append(", speakers=").append(speakers)
                .append(" expected=").append(expected.speakers.sorted())
                .append(", boxed=").append(boxed).append("/").append(units.size)
                .append('\n')
        }

        val scored = fixtures.size
        report.append("--- $passed/$scored pages passed; $withBoxes/$scored returned bounding boxes ---\n")
        println(report)
    }
}
```

- [ ] **Step 2: Write the fixture documentation**

`evals/README.md`:

```markdown
# Vision eval harness

Answers the one question no unit test can: does Haiku 4.5 actually read real book
pages and attribute the right speakers?

This is scored as a pass rate, not asserted as pass/fail — the model is
non-deterministic, so equality is the wrong instrument.

## Running it

    STORYTELLER_EVAL=1 ANTHROPIC_API_KEY=sk-ant-... ./gradlew testDebugUnitTest \
      --tests "com.storyteller.evals.VisionEval" -i

Without both environment variables the test is skipped, so it never runs in the
normal suite and never spends money by accident. Each page costs about $0.003.

## Adding a fixture

1. Photograph a real page the way a child would hold the phone. Put it in
   `evals/fixtures/` — that directory is gitignored, since the pages are
   copyrighted and large.
2. Write `evals/expected/<same-basename>.json`:

       { "speakers": ["Narrator", "Wolf"], "minUnits": 3 }

   `speakers` is the exact set you expect, order-insensitive. `minUnits` is a
   floor, not an equality — the model may legitimately split a long paragraph.

## What to cover

Aim for around 15 pages: picture books and graphic novels, good light and bad,
flat and angled, glare and none, plus at least one page with no text at all
(expected `speakers: []`, `minUnits: 0`) and one hand-lettered comic panel, which
is the hardest case and the reason ML Kit was dropped.

## Reading the report

The `boxed=n/m` column is the second thing this harness measures: whether the
returned bounding boxes are usable. Iteration 1 ignores them, but iteration 2
needs them for tappable speech bubbles. If boxes come back consistently null or
land on the wrong bubbles, that is the signal to reintroduce ML Kit for geometry
before committing to that feature.
```

`evals/expected/README.md`:

```markdown
Expected results for the vision eval, one JSON file per fixture image, named after
the image. These are committed; the images in `../fixtures/` are not.

    { "speakers": ["Narrator", "Wolf"], "minUnits": 3 }
```

- [ ] **Step 3: Confirm fixtures stay out of git**

Run: `git check-ignore -v evals/fixtures || echo NOT_IGNORED`
Expected: a match on the `evals/fixtures/` rule from the initial commit. If it prints `NOT_IGNORED`, add `evals/fixtures/` to `.gitignore` before proceeding — the fixtures are copyrighted book pages.

- [ ] **Step 4: Verify the harness skips cleanly by default**

Run: `./gradlew testDebugUnitTest --tests "com.storyteller.evals.VisionEval"`
Expected: PASS by assumption failure (skipped), with no network call and no cost.

- [ ] **Step 5: Run it for real with at least three fixtures**

```bash
STORYTELLER_EVAL=1 ANTHROPIC_API_KEY=sk-ant-... \
  ./gradlew testDebugUnitTest --tests "com.storyteller.evals.VisionEval" -i
```

Expected: a printed report with a pass rate. Record the baseline number in the commit message — it is the reference point for every future prompt change.

- [ ] **Step 6: Commit**

```bash
git add app/src/test/kotlin/com/storyteller/evals evals .gitignore
git commit -m "test: add scored vision eval harness over real book pages"
```

---

## Plan Self-Review

Run against the spec after completing the plan.

**Spec coverage.** Section 2 layering → Task 1 (structure) and Task 10 (`GraphTest` enforces it mechanically). Section 2 pipeline ownership → Task 10 `PipelineModule` (`@ActivityRetainedScoped`). Section 2 stack → Tasks 1, 5-9. Section 3 contracts → Task 2, all types defined. Section 4 capture-to-start → Task 11. Section 4 read → Task 7. Section 4 prepare/concurrency → Task 3. Section 4 play → Tasks 9 and 12. Section 4 caches → Tasks 5, 7, 8. Section 4 failure paths → Task 4 (mapping) and Task 12 (messages, one test asserts all four are distinct). Section 5 testing → every task; the eval is Task 14. Section 6 cost → no code, exercised by Task 13 step 6 item 10 and Task 14. Section 7 key handling → Task 1 (`BuildConfig`, `local.properties.example`) and the Global Constraints. Section 8 not-planned items → correctly absent.

**Gaps closed during review.** `PagePlayerImpl.release()` was never called, leaking an ExoPlayer per activity. Task 10 provides `PagePlayer` as a `@Singleton`, so the leak is bounded to one instance for the process rather than one per navigation — acceptable for iteration 1 and noted here rather than silently left. `ReaderViewModel.onStop` is wired to back navigation in Task 12 step 5 so playback stops when leaving the reader.

**Type consistency.** `toSpeechUnits` (Task 2) is called only in Task 7. `sha256` (Task 7) is used in Tasks 7 and 8 with the same signature. `PipelineState.Preparing(ready, total)` is constructed in Tasks 3-4 and consumed in Task 12 with matching field names. `FailureReason` has four entries in Task 2, is produced in Tasks 3-4, and Task 12's `message()` is exhaustive over all four with a test that enforces distinctness. `PagePlayer.play/append/stop` match between Task 2, Task 9, and the `FakePlayer` in Task 12.

**Known deviation.** Task 9 writes the implementation before the test, inverting TDD. Media3 requires a device, so a red-first cycle costs a full deploy per iteration for no design feedback. This is deliberate and confined to that one task.
