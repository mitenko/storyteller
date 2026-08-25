# Storyteller — project notes

A graphic novel / storybook reader for children. The user holds the phone over a
book page; the app photographs it, works out who says what, and reads the page
aloud with a different voice per character.

## Platform

**Native Android only. Kotlin + Jetpack Compose + MVVM.**

iOS is deferred to a separate future project. Jetpack Compose is Kotlin-only, so
there is no shared codebase; only the vision prompt and its JSON schema carry over.

This supersedes the earlier Flutter design at
`docs/superpowers/specs/2026-03-23-storyteller-design.md`, which targeted iOS +
Android via Flutter. Do not scaffold Flutter or Dart in this repo.

## Architecture

Single `:app` module, three package layers, dependencies pointing inward:
`ui -> domain <- data`. `domain` is plain Kotlin with no Android imports, so the
orchestration logic is covered by JVM unit tests. `ui` never imports `data`; Hilt
is the only place the two halves meet. `GraphTest` enforces both rules.

`ReadingPipeline` (`@ActivityRetainedScoped`) owns the read-parse-voice-synthesize
sequence and outlives the capture-to-reader navigation, so voice prefetch keeps
running across it.

Current design: `docs/superpowers/specs/2026-08-24-storyteller-compose-mvvm-design.md`

## Stack

- **CameraX** for capture, downscaled to 1568 px on the long edge
- **Claude Haiku 4.5 vision** reads the page and attributes speakers in a single
  call, constrained by a JSON schema. No ML Kit: the vision call sees the page
  layout, which is what makes speech bubbles in comics work.
- **ElevenLabs** for character voices
- **Room** for the character-to-voice map and cache metadata
- **Media3** for playback, one MediaItem per speech unit played as a playlist
- **Hilt** for DI, **Retrofit + kotlinx.serialization** for both APIs

API keys are injected at build time from `local.properties` into `BuildConfig`.
That is fine for a personal build and NOT safe for a store release, since the keys
are extractable from the APK. The seam for a key-holding proxy sits entirely in
`data`.

## Iteration 1 scope

1. Photograph a book page, graphic novel or plain text
2. Parse the page text
3. Identify the characters and who speaks each line
4. Assign a voice per character
5. Read the whole page aloud, start to finish

Deferred: tapping a speech bubble to replay it, selectable auto-read vs tap modes,
LRU eviction and cache size caps, WiFi pre-checks, device-TTS fallback, multiple
profiles, book recognition, user-editable voice assignments.

`SpeechUnit` carries an optional normalized bounding box that iteration 1 does not
use. It is populated from day one so the accuracy of the coordinates is known
before iteration 2 needs them for tappable bubbles.

## Cost notes

Roughly $0.003 per page for the vision call and $0.025 per page for ElevenLabs, so
synthesis dominates. The audio cache is keyed on `sha256(voiceId|text)` and is
therefore the durable one: a re-read from a different photo still hits it, and it
lives in `filesDir` rather than `cacheDir` so the OS cannot purge paid-for audio.
The parse cache is keyed on image bytes, so it only helps for byte-identical input
(rotation, back-navigation, retry after partial failure).

## Testing

Most logic is JVM-testable and should stay that way. Device tests only for CameraX
and Media3. Vision accuracy is an eval harness over real book photos scored as a
pass rate, not a pass/fail assertion — see `evals/README.md`.

Two gaps worth knowing: no interactive device verification has been performed, and
no real API call has ever been made by this code (`local.properties` is absent, so
the suite runs against MockWebServer). The manual walkthrough in
`docs/superpowers/plans/2026-08-24-storyteller-iteration-1.md` is the gate that
closes both.
