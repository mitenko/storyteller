# Storyteller — project notes

A graphic novel / storybook reader for children. The user holds the phone over a
book page; the app photographs it, works out who says what, and reads it aloud
with a different voice per character, one speech bubble at a time.

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
(overall Compose/MVVM shape) and
`docs/superpowers/specs/2026-08-26-storyteller-bubble-reader-design.md` (the
one-bubble-at-a-time reader).

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

## What the app does today

1. Photograph a book page, graphic novel or plain text
2. Parse the page text and attribute each line to a speaker in one vision call
3. Assign a voice per character
4. Show ONE speech bubble at a time — cropped out of the page photo around that
   line's bounding box, falling back to plain text when there is no usable box
   (a prose page, or a crop that fails) — with previous/next controls
5. Read it aloud either automatically, straight through the page (**Auto**), or
   one tapped bubble at a time (**Tap**) — a per-user setting in Settings,
   alongside a three-way theme choice (System / Light / Dark)

Deferred: LRU eviction and cache size caps, WiFi pre-checks, device-TTS
fallback, multiple profiles, book recognition, user-editable voice assignments.

### What got deleted along the way

- **The character "badge"** — a small cropped portrait shown per character —
  and its Room column (`character_voice.badgePath`, added then dropped by a
  migration; see `StorytellerDatabase.kt`).
- **The `characters` array** the vision call used to return separately from
  the per-line units — dropped from the JSON schema; speaker attribution now
  comes entirely from each unit's own `speaker` field.
- **The transcript list** — a scrollable list of every line on the page,
  replaced by the one-bubble-at-a-time reader above.

### Speech-bubble box accuracy is UNMEASURED

`SpeechUnit.bounds` is what the reader crops a bubble out of, and the scoring
machinery for it exists — `scoreBubbleBoxes`/`ExpectedBubble` in
`app/src/test/kotlin/com/storyteller/evals/VisionEval.kt`, IoU against
hand-drawn boxes, with a documented stop condition (mean IoU below 0.5 means
the crop is regularly framing the wrong thing). But `evals/fixtures/` is empty
in this checkout — no real book photos, no hand-drawn `bounds` in
`evals/expected/*.json` — so that scoring code has never actually been run
against real pages. Whether the model's boxes are usable is presently unknown;
the reader's text fallback is silently doing an unknown amount of work. Adding
fixtures and running the eval (see `evals/README.md`) is what would answer
this, and nothing in the codebase currently depends on the answer being good.

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

Two gaps worth knowing: no interactive device verification has ever been performed,
for either iteration, and no real API call has ever been made by this code
(`local.properties` is absent, so the suite runs against MockWebServer). The manual
walkthrough at the end of
`docs/superpowers/plans/2026-08-26-storyteller-bubble-reader.md` is the current
gate — it needs a real device, real keys, and a graphic novel, and none of those
have been available yet.
