# Storyteller — Android / Compose / MVVM Design

**Date:** 2026-08-24
**Status:** Approved design. Implementation plan not yet written.
**Supersedes:** `2026-03-23-storyteller-design.md` (Flutter, iOS + Android).

---

## 1. Overview

Storyteller is a native Android app for reading picture books and graphic novels
aloud to children. The user holds the phone over a page; the app photographs it,
determines who speaks each line, assigns a distinct voice to each character, and
reads the page aloud from start to finish.

### Platform decision

**Native Android only: Kotlin, Jetpack Compose, MVVM.**

The March design targeted Flutter for iOS + Android. Jetpack Compose is
Kotlin-only, so that cross-platform reach was deliberately traded away in favour
of idiomatic Compose and first-class access to CameraX and Media3. iOS becomes a
separate future project with its own spec; the only artifact that ports is the
vision prompt and its JSON schema, which are server-side and platform-agnostic.

Do not scaffold Flutter or Dart in this repository.

### Iteration 1 scope

1. Photograph a book page, graphic novel or plain text.
2. Parse the page text.
3. Identify the characters and who speaks each line.
4. Assign a voice per character.
5. Read the whole page aloud, start to finish.

**Explicitly deferred:** tapping a speech bubble to replay it; selectable
auto-read vs tap-to-play modes; LRU eviction and cache size caps; WiFi
pre-checks; device-TTS fallback; per-line highlighting during playback; multiple
profiles; book recognition; user-editable voice assignments; iOS.

Iteration 1 has no per-line interaction. The reader screen offers a single Play
affordance and reads the page through as one continuous performance.

---

## 2. Architecture

### Layering

One Gradle module (`:app`) with three package layers. Multi-module would be
premature for two screens; the package boundaries carry the discipline and can be
promoted to modules later.

```
com.storyteller
├── ui/            Compose + ViewModels (depends on domain only)
│   ├── capture/   CaptureScreen, CaptureViewModel, CaptureUiState
│   ├── reader/    ReaderScreen, ReaderViewModel, ReaderUiState
│   └── theme/
├── domain/        Pure Kotlin. No Android imports, no Compose.
│   ├── ReadingPipeline (interface + impl)
│   ├── model/     PageImage, SpeechUnit, BoundingBox, PreparedUnit, PipelineState
│   └── repository/  PageReader, VoiceRepository, AudioRepository, PagePlayer
└── data/          Implementations (depends on domain only)
    ├── page/      PageReaderImpl, Claude Retrofit service, DTOs, schema
    ├── voice/     VoiceRepositoryImpl, ElevenLabs voice service
    ├── audio/     AudioRepositoryImpl, file cache, Media3 PagePlayerImpl
    └── local/     Room database, DAOs, entities
```

**The dependency rule is one-directional: `ui -> domain <- data`.** `domain`
declares repository interfaces and owns the models; `data` implements them; `ui`
never imports anything from `data`. Hilt is the only place the halves meet.

Two consequences are the point of the structure rather than side effects:

- `domain` has no Android dependencies, so `ReadingPipeline` — the sequencing,
  the retry rules, the concurrent prefetch — is covered by plain JVM unit tests.
  No Robolectric, no emulator, no Compose test rule.
- ViewModels shrink to two jobs: expose a `StateFlow<UiState>`, and translate user
  intent into pipeline calls. Neither touches Retrofit, Room, or Media3. This is
  also why a future key-holding proxy is a `data`-only change.

### Pipeline ownership

The read-parse-voice-synthesize sequence spans two screens: capture starts it,
the reader consumes it, and voice prefetch runs while the user navigates between
them. `ReadingPipeline` therefore lives in `domain` and is bound
`@ActivityRetainedScoped` — it must outlive the capture-to-reader navigation and
survive rotation, but must not outlive the activity while holding a page of audio
files.

Rejected alternatives: a single nav-graph-scoped ViewModel (becomes a god object
owning camera, parsing, voices, caching, and playback), and per-screen ViewModels
calling repositories directly with no pipeline (orchestration and its error
handling get duplicated across two ViewModels).

### Stack

| Concern | Choice |
|---|---|
| Capture | CameraX |
| Page reading + speaker attribution | Claude Haiku 4.5, vision, structured outputs |
| Character voices | ElevenLabs |
| Persistence | Room |
| Playback | Media3 / ExoPlayer |
| DI | Hilt |
| Networking | Retrofit + kotlinx.serialization |

**ML Kit is deliberately absent.** The March design used ML Kit for on-device OCR
and then a second Claude call for speaker attribution, passing bounding boxes
across the boundary as spatial hints. A single vision call collapses both steps
and sees the actual page layout — which bubble a tail points to, who is drawn
where — instead of inferring it from y-coordinates. That matters most for
hand-lettered graphic novels, which are ML Kit's weakest case and are in scope
from iteration 1. The cost is losing the free on-device tier: every fresh page is
now a network call.

---

## 3. Components and contracts

```kotlin
// ---------- domain/model ----------
class PageImage(val bytes: ByteArray, val mimeType: String)

data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class SpeechUnit(
    val index: Int,             // reading order
    val speaker: String,        // "Narrator" or a character name
    val text: String,
    val bounds: BoundingBox?,   // normalized 0..1; populated, unused in iteration 1
)

data class PreparedUnit(val unit: SpeechUnit, val voiceId: String, val audio: File)

// ---------- domain/repository (interfaces only) ----------
interface PageReader {                                    // vision call + parse cache
    suspend fun read(image: PageImage): Result<List<SpeechUnit>>
}
interface VoiceRepository {                               // assigns on first sight, persists
    suspend fun voiceFor(character: String): Result<String>
}
interface AudioRepository {                               // cache hit, else synthesize
    suspend fun audioFor(text: String, voiceId: String): Result<File>
}
interface PagePlayer {                                    // Media3 lives behind this
    val state: StateFlow<PlaybackState>
    fun play(units: List<PreparedUnit>)
    fun append(unit: PreparedUnit)
    fun stop()
}

// ---------- domain ----------
sealed interface PipelineState {
    data object Idle : PipelineState
    data object Reading : PipelineState                                        // vision call in flight
    data class Preparing(val ready: List<PreparedUnit>, val total: Int) : PipelineState
    data class Ready(val units: List<PreparedUnit>) : PipelineState
    data class Failed(val reason: FailureReason, val retryable: Boolean) : PipelineState
}

enum class FailureReason { NoTextFound, Network, Parse, Synthesis }

interface ReadingPipeline {
    val state: StateFlow<PipelineState>
    fun start(image: PageImage)
    fun retry()
    fun reset()
}

// ---------- domain, playback ----------
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Playing : PlaybackState
    data object Finished : PlaybackState
}

// ---------- ui/capture ----------
sealed interface CaptureUiState {
    data object PermissionRequired : CaptureUiState
    data object Framing : CaptureUiState                        // live preview
    data class Captured(val image: PageImage) : CaptureUiState   // confirm or retake
}

// ---------- ui/reader ----------
sealed interface ReaderUiState {
    data object ReadingPage : ReaderUiState                                   // vision call in flight
    data class PreparingVoices(val ready: Int, val total: Int) : ReaderUiState
    data class Playing(val lines: List<Line>) : ReaderUiState
    data class Error(val message: String, val canRetry: Boolean) : ReaderUiState

    data class Line(val speaker: String, val text: String)
}
```

`ReaderViewModel` maps pipeline state to UI state directly: `Reading -> ReadingPage`,
`Preparing -> PreparingVoices(ready.size, total)`, `Ready -> Playing(lines)`,
`Failed -> Error`. The reduction from `List<PreparedUnit>` to a count is
deliberate — the UI has no use for file handles or voice IDs.

`PlaybackState` deliberately carries no unit index, and `ReaderUiState.Playing` no
"currently speaking" marker, because iteration 1 does not highlight lines. Media3
supplies the playlist index for free, so this is a one-line addition when
iteration 2 wants it. Contrast `SpeechUnit.bounds`, which *is* populated ahead of
need: a bounding box buys information we cannot get later without re-running the
evals, whereas a playlist index buys nothing.

Three of these are decisions rather than plumbing:

**`PageReader` replaces the March spec's `OcrService` plus `ParsingService`.** One
interface, because one call does both jobs. The name states the responsibility
rather than the mechanism, so if ML Kit returns purely for geometry it goes behind
this interface without disturbing anything above.

**`Preparing` carries the units ready so far, not just a count.** That is what
lets playback begin on unit 1 while units 2..n are still synthesizing. Waiting for
the full page would be simpler but costs roughly six seconds of silence on a
six-line page.

**`domain` owns `PageImage`, so it stays Android-free.** No `Bitmap`, no `Uri`, no
`File` from the camera. `CaptureViewModel` converts at the boundary.

`ReadingPipeline` is an interface with an implementation specifically so ViewModel
tests can drive arbitrary states without assembling three fakes to provoke each
one.

---

## 4. Data flow

### Capture to pipeline start

`CaptureViewModel` holds the CameraX `ImageCapture` use case. A shutter tap
produces JPEG bytes held in `CaptureUiState`. On "Read this page" the ViewModel
resizes the image so its long edge is at most **1568 px** and re-encodes as JPEG
at quality 85, wraps the bytes in `PageImage`, calls `pipeline.start(image)`, and
*then* navigates. The pipeline is already running before the reader composes,
which is the reason it is not ViewModel-scoped.

1568 px is the point above which Haiku 4.5 gains nothing: the high-resolution
vision tier (2576 px long edge) is Opus 4.7 and later plus Sonnet 5, not Haiku, so
a larger upload costs tokens without adding detail. If the evals show text is
being misread on dense comic pages, raising this ceiling is not the fix — moving
that call to a high-resolution model is.

`PageImage` wraps a `ByteArray`, so it is an ordinary class rather than a `data
class`: array identity would make generated `equals` misleading. Nothing compares
`PageImage` instances; the parse cache hashes the bytes explicitly.

### Read

`PageReaderImpl` hashes the compressed bytes and checks the parse cache. On a miss
it posts the image to Claude Haiku 4.5 with the response schema attached, then
writes the result to the cache. State moves `Reading -> Preparing(ready = [], total = n)`.

Request shape:

- `model`: `claude-haiku-4-5`
- one `image` content block (base64 JPEG) plus one `text` block carrying the
  instruction
- `output_config.format` with a `json_schema` (below)
- `max_tokens`: 2048, non-streaming — output is small
- **Do not send `output_config.effort`.** The effort parameter errors on Haiku
  4.5. Do not send a `thinking` block either; this is mechanical extraction.
- **Prompt caching will not help.** The stable prefix is a few hundred tokens and
  Haiku 4.5 requires a 4096-token minimum to cache, so it would silently never
  hit. Do not add `cache_control`.

Response schema. Structured outputs requires `additionalProperties: false` on
every object and does not support numeric range constraints, so the 0..1 bound on
coordinates is documented in the field description and validated on the client.

```json
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
```

Instruction text, in outline: return every speech unit on the page in reading
order; attribute each to the character who speaks it, using `Narrator` for
description not attributed to a character; use the character name exactly as it
appears; if a speaker cannot be determined use `Narrator`; reproduce the text
verbatim without merging or splitting; give each unit a normalized bounding box
covering its bubble or text block.

`index` is assigned client-side from array order, not requested from the model.

### Prepare, concurrently but in order

Units must *play* in reading order but need not be *synthesized* in that order:

```kotlin
val jobs = units.map { unit -> async { semaphore.withPermit { prepare(unit) } } }
jobs.forEach { job -> emitReady(job.await()) }
```

Synthesis runs in parallel behind a `Semaphore(3)` to stay inside ElevenLabs rate
limits, while awaiting the deferreds in index order guarantees units surface in
reading order. `prepare` is `voiceFor(speaker)` followed by
`audioFor(text, voiceId)`.

### Play

`ReaderViewModel` collects `PipelineState`. The first `PreparedUnit` triggers
`player.play(listOf(it))`; each subsequent one is `player.append(it)`. Media3
plays the growing playlist gaplessly, so the page begins speaking while later
lines are still being generated.

**`Preparing` carries a cumulative list, so `ReaderViewModel` must track a
high-water index and append only past it.** Re-collecting the state otherwise
re-appends units already handed to the player, producing duplicated audio. This
has an explicit test.

### Caches

| Cache | Key | Storage | Reach |
|---|---|---|---|
| Parse | `sha256` of compressed JPEG bytes | Room | Byte-identical input only: rotation, back-navigation, retry after partial failure. A fresh photo of the same page misses. |
| Audio | `sha256(text + voiceId)` | Files in `filesDir/audio/`, metadata in Room | Durable. Page text is identical however it was photographed, so a re-read tomorrow from a different photo hits every unit. |
| Voice list | single row | Room | Fetched once from ElevenLabs `/voices`; voices assigned locally thereafter. |
| Character voice map | character name | Room | A character keeps the same voice across pages and sessions. |

Neither cache evicts in iteration 1. Unbounded is acceptable at this size; LRU
and size caps are deferred.

The reach difference is the important part: a re-read costs roughly $0.003 for the
vision call and nothing for audio — about 90% of the page cost avoided, not 100%.

### Failure paths

Structured outputs enforces the schema server-side, which removes the March
spec's malformed-JSON retry ladder almost entirely.

| Failure | State | Reader shows |
|---|---|---|
| Camera permission denied | capture-side `CaptureUiState` | Explanation, deep link to settings |
| Vision call network or HTTP error | `Failed(Network, retryable = true)` | Message, Retry |
| Zero speech units returned | `Failed(NoTextFound, retryable = true)` | "Couldn't read this page — try better light or hold steadier" |
| Response fails deserialization or client validation | `Failed(Parse, retryable = true)` | Message, Retry |
| Voice list fetch fails | `Failed(Network, retryable = true)` | Message, Retry |
| Any unit's synthesis fails | `Failed(Synthesis, retryable = true)` | Message, Retry |

With no device-TTS fallback in iteration 1, one failed unit fails the page rather
than leaving a silent gap mid-story. This is acceptable because **retry is nearly
free**: every unit already synthesized is on disk, so `retry()` re-walks the list
and pays only for what is missing.

---

## 5. Testing

| What | Where | How |
|---|---|---|
| `ReadingPipeline` — ordering, concurrency bound, retry, failure mapping | JVM | Fake repositories, `runTest` virtual time, Turbine on the `StateFlow` |
| `PageReaderImpl` — request shape, DTO mapping, cache short-circuit | JVM | MockWebServer, temp dir |
| `AudioRepositoryImpl` — key stability, hit/miss, file writes | JVM | MockWebServer, temp dir |
| `VoiceRepositoryImpl` — first-sight assignment, persistence | JVM (Robolectric) | In-memory Room |
| ViewModels — state mapping, high-water append | JVM | Fake `ReadingPipeline` emitting canned states |
| `ReaderScreen` — renders each `ReaderUiState` | JVM (Robolectric) or device | `createComposeRule` |
| CameraX capture | Device | Instrumented smoke test; largely manual |
| Media3 plays n files in order | Device | Instrumented |
| Vision accuracy | Manual / on demand | Eval harness, below |

The pipeline tests carry the most value. Written first:

- Fake `AudioRepository` returns unit 3 quickly and unit 1 slowly; assert units
  still surface in index order. This pins the await-in-order pattern, the easiest
  thing in the design to get subtly wrong.
- Fake asserts in-flight synthesis never exceeds 3.
- Retry after partial failure re-requests only the missing units.
- `Preparing` re-emission does not cause a duplicate `append`.

### Vision accuracy is an eval, not a test

Whether Haiku correctly attributes a line to the Wolf on a hand-lettered comic
panel is non-deterministic; equality assertions are the wrong instrument. Instead:
roughly 15 photos of real books — picture books and graphic novels, good light and
bad, angled and flat — run through `PageReader` with expected attributions
recorded alongside, scored as a pass rate. Re-run whenever the prompt or model
changes. It stays out of CI (real keys, real cost) and runs on demand.

The bounding boxes are inspected in the same harness: confirm the boxes land on
the right bubbles, which tells us before iteration 2 whether tappable regions are
viable from the vision call or whether ML Kit must return for geometry.

---

## 6. Cost

| Item | Per page |
|---|---|
| Claude Haiku 4.5 vision call (~1,600 image tokens + prompt, ~200 out) | ~$0.003 |
| ElevenLabs Flash/Turbo at $0.05 per 1k characters, ~500 characters | ~$0.025 |
| **Total, fresh page** | **~$0.028** |
| Re-read of a previously heard page | ~$0.003 |

Synthesis dominates by roughly 8x, which is why the audio cache is the design's
main cost lever and why eviction is the thing deferred most reluctantly.

---

## 7. Security and known risks

**API keys are extractable.** Keys are injected at build time from
`local.properties` into `BuildConfig`. This is acceptable for a personal build and
**not safe for a store release** — anyone can pull both keys from a released APK
and drain the ElevenLabs account. The mitigation is a proxy service holding the
keys with per-device rate limiting; the seam for it is confined to `data`, so
`domain` and `ui` are unaffected by the migration.

**Capture quality is the largest unknown.** Whether a phone photo of a real page
is readable — glare, angle, gutter curve, a child's unsteady hands — is not
addressed by any architecture. A short throwaway spike against ten real photos
should precede implementation; if it fails, the answer is a different capture UX,
not a different design. Those photos become the first fixtures of the eval
harness, so the work carries forward.

**Bounding box accuracy is unmeasured.** The field is populated from day one at no
extra cost precisely so this is known before iteration 2 depends on it.

**Every page is now a network call.** Dropping ML Kit removed the free on-device
tier. There is no offline mode, by design.

---

## 8. Not planned, as distinct from deferred

Section 1 lists what is **deferred** — intended for a later iteration and
accounted for in this design. Tapping a speech bubble to replay a line and
per-line highlighting are both iteration 2 and belong there, not here.

The following have **no current intent** and nothing in this design reserves room
for them. Adding any of them later is a new design conversation, not a planned
increment:

- Offline mode. Dropping ML Kit means every fresh page is a network call, by
  choice.
- Multiple user profiles.
- Book or story recognition — the app reads the page in front of it and holds no
  concept of which book it belongs to.
- User-editable voice assignments. Voices are assigned randomly on first sight and
  are not presented as adjustable.
- Blur or quality detection on the captured photo. The user judges the preview
  visually and retakes.
- iOS, which is a separate project rather than a later iteration of this one.
