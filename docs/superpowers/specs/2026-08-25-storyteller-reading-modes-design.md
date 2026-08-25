# Storyteller — Reading Modes and Speaker Badges

**Date:** 2026-08-25
**Status:** Approved design. Implementation plan not yet written.
**Builds on:** `2026-08-24-storyteller-compose-mvvm-design.md` (iteration 1).

---

## 1. Overview

Iteration 2 gives the reader two ways to hear a page, and gives every line a
visible speaker.

**Reading mode** is a user setting with two values. **Auto** reads the page
through from start to finish — iteration 1's behaviour, unchanged. **Tap** reads
nothing on arrival; the child taps a line and hears that line.

**Speaker badges** put a small image beside each line in the reader showing who
is speaking. For a graphic novel the badge is the character as drawn, cropped
from the page photo. Where there is no character art to cut from, the badge falls
back to an emoji supplied by the vision call. The narrator never gets a badge.

The iteration 1 spec deferred "tapping a speech bubble to replay it" and
"selectable auto-read vs tap-to-play modes". This design delivers the second and
deliberately does **not** deliver the first — see §9.

## 2. What the child taps

**Tapping happens on the transcript list, not on the page photo.**

The reader already renders a `LazyColumn` of speaker/text rows. Tap mode makes
those rows tappable. It does not show the page image, does not map bounding boxes
to screen coordinates, and does not hit-test.

This was the pivotal decision, and it was taken deliberately over the more
obvious "tap the speech bubble on the photo":

- Bubble hit-testing lives or dies on `SpeechUnit.bounds` being tight. Those
  bounds have never been measured against a real page. Worse, until 2026-08-25
  every image sent to the vision call was rotated 90 degrees, so any bounds ever
  returned were derived from a sideways page.
- Real graphic novels have overlapping, irregular, tailed bubbles. A rectangle is
  a poor hit target for them.
- The list is legible to an adult reading along, and works identically for plain
  prose picture books, which have no bubbles at all.

`SpeechUnit.bounds` therefore remains unused in iteration 2, exactly as it was in
iteration 1. It is retained, not removed: it is still the natural input for
bubble interaction if a later iteration wants it, and it costs nothing.

## 3. Vision call contract

Characters are page-level, not unit-level — a character who speaks twice must not
yield two crops. So the structured output gains a sibling array rather than extra
fields on each unit:

```
{
  units:      [{ speaker, text, bounds }],
  characters: [{ name, emoji, bounds }]
}
```

`units` is unchanged. `characters[].bounds` is the character **as drawn** — a
different rectangle from the speech bubble in `units[].bounds`. Both `emoji` and
`bounds` are optional per character: a prose picture book will commonly return an
emoji and no box.

Domain model: a new `ParsedCharacter(name: String, emoji: String?, bounds:
BoundingBox?)`. The page repository returns units and characters together instead
of `List<ParsedUnit>`.

### Parse-cache migration

`parsed_page` rows written before this change carry no characters. Add a
`parseVersion` column; rows below the current version are treated as **misses**,
so the next read of that page repopulates them.

This re-bills the vision call at roughly $0.003 per stale page. That is the
correct trade: the audio cache keys on `sha256(voiceId|text)` and is untouched by
this change, so the synthesis cost — approximately 8/9ths of the total — still
hits. Silently serving badge-less pages forever would be worse.

## 4. Cropping and badge storage

Cropping happens in `data`, after the parse, from the same `PageImage` bytes the
pipeline already holds in memory. Normalized 0..1 bounds are converted to a pixel
rect, padded by **10% of the box's larger edge on all four sides**, clamped to the
image, and written to `filesDir`. The padding exists because a box drawn tight to
a character reads as a claustrophobic crop at badge size.

A box is **implausible**, and skips to the next step of the fallback chain, if
after clamping it has zero or negative area, or if either edge is under 2% of the
image — a sliver crop is worse than no badge.

`filesDir`, not `cacheDir` — the same rule the audio cache follows. A purged
badge would silently change a character's identity mid-book.

`character_voice` gains `badgePath: String?`.

**First sighting wins.** The crop is written only when `badgePath` is null. This
mirrors the behaviour already in that table, where a character's voice is pinned
on first encounter and reused on every later page. A character keeps both its
voice and its face for the whole book.

The known cost of first-sighting-wins is that an unlucky first crop — the
character in profile, tiny, or half off the page — persists for the book. This is
accepted for iteration 2. Letting a better crop displace a worse one needs a
quality metric that does not exist yet.

### Badge resolution

A strict fallback chain, evaluated per character:

1. **Stored crop** — the character as drawn.
2. **Emoji** — from the vision call.
3. **Blank** — nothing renders.

The narrator is always blank and never gets a badge, whatever the model returns.

Every step degrades silently. An implausible box, a decode failure, or a missing
file drops to the next step down. **A badge failure must never fail a page**: the
page is readable with no badges at all, and the child's experience of a broken
crop should be an absent picture, not an error screen.

## 5. Reading mode and settings

`ReadingMode { Auto, Tap }` lives in `domain`.

A `SettingsRepository` interface exposes `mode: Flow<ReadingMode>` and
`setMode(mode)`. It is implemented in `data` over a **key-value `settings` table
in the existing Room database**.

Room rather than `DataStore`: the project has exactly one persistence mechanism
today, and adding a second for a single boolean is not worth it. Key-value rather
than a typed column per setting: the settings page is expected to grow, and this
way new settings need no migration.

Mode is read where playback is decided, not threaded through the pipeline.

## 6. What does not change

Two components were expected to need work and do not. Both findings shaped the
design, so they are recorded here as constraints on implementation:

**`ReadingPipeline` is unchanged.** Audio is pre-synthesized for the whole page in
both modes — same vision call, same concurrent synthesis, same
`Preparing(ready, total)` progression. Mode changes only what the reader does
with prepared units.

The cost of this is explicit and accepted: **tap mode bills the whole page even
if the child taps two lines.** On-demand synthesis would save money on
lightly-tapped pages, at the price of a multi-second wait on the first tap of
every line. For a child poking at a page, latency is the worse failure.

**`PagePlayer` is unchanged.** Tap mode needs no new player API: it is
`play(listOf(unit))` followed immediately by `endOfPage()`. The existing
`pageComplete` latch then treats `STATE_ENDED` as `Finished` correctly for a
one-item playlist.

A tap while another line is playing **replaces** the playlist rather than
queueing. This is the intended behaviour: a child tapping a new row means "read
that one now."

`PlaybackState` still carries no unit index, and still does not need one. In tap
mode the ViewModel knows which row is playing because it just handled the tap.

## 7. Reader UI

`ReaderUiState.Line` gains `index: Int` and `badge: Badge`, where `Badge` is a
sealed interface of `Image(File)`, `Emoji(String)` and `None`.
`ReaderUiState.Playing` gains `mode: ReadingMode` and `playingIndex: Int?`.

In tap mode nothing plays on arrival. Rows become tappable **individually, as
their audio lands** — the `ready` list in `Preparing` is already cumulative and
ordered by index, so the child can tap line 1 while line 8 is still synthesizing.
A row whose audio is not ready renders visibly disabled and is inert. The
currently playing row is highlighted.

`domain` keeps no Android imports: `Badge.Image` carries a `java.io.File`, as
`PreparedUnit` already does.

Badges render as a **circle of a single fixed size**, cropped to fill, in a fixed
leading column so that rows stay aligned whether or not a badge is present. A
blank badge occupies the column rather than collapsing it — otherwise narrator
lines would sit at a different indent from character lines and the list would
read as ragged.

## 8. Settings screen

A new `Routes.SETTINGS` and `SettingsScreen`, reached from an icon on the capture
screen. The icon goes on the screen itself rather than an app bar, because the
theme is deliberately no-action-bar.

One toggle now, laid out as a list of settings rows so it grows without
restructuring.

## 9. Not in this iteration

- **Highlighting the current line during auto playback.** This needs the player
  to report its playlist index, which `PlaybackState` deliberately omits. Tap
  mode gets highlighting free because the ViewModel handled the tap; auto mode
  does not, and adding it is a player change out of scope here.
- **The page photo in the reader**, and bubble hit-testing — see §2.
- **Replacing a poor first crop** with a better one — see §4.
- **On-demand synthesis** in tap mode — see §6.
- Cache eviction and size caps, WiFi pre-checks, device-TTS fallback, multiple
  profiles, book recognition, user-editable voices, iOS. All still deferred from
  iteration 1.

## 10. Error handling

| Failure | Behaviour |
|---|---|
| Character box missing or implausible | Fall back to emoji, then blank |
| Crop decode or write fails | Fall back to emoji, then blank |
| Badge file missing at render time | Render blank |
| Tap on a row whose audio is not ready | Row is disabled; the tap is inert |
| Playback error in tap mode | Existing `onPlayerError` path; row un-highlights |
| Settings read fails | Default to `Auto` |

No badge or settings failure is allowed to fail a page.

## 11. Testing

**JVM / domain.** Badge resolution precedence including the narrator;
first-sighting-wins; crop geometry — normalized to pixel, padding, clamping at
edges, degenerate and inverted boxes; mode switching.

**Robolectric.** Badges render for each `Badge` variant; tapping a row plays
exactly that one unit and no others; a disabled row does not; the playing row is
highlighted.

**Room.** Settings round-trip; `badgePath` first-write-wins; the `parseVersion`
migration, including that a stale row is treated as a miss.

**Not covered by any of the above:** whether the model's character boxes actually
frame characters. That is an eval question, not a unit-test question, and it
belongs in the existing `evals/` harness against real page fixtures.

## 12. Known risks

- **Character box accuracy is unmeasured.** The whole badge feature rests on it.
  Unlike bubble hit-testing, a roughly-right box still yields a recognisable
  crop, which is why this design is viable where §2's alternative was not — but
  it should be evaluated early, before the UI is built on top of it.
- **Iteration 1 has still never been verified on a real book.** The manual
  walkthrough in the iteration 1 plan remains unrun. Building iteration 2 on top
  does not close that gate, and every defect it would have caught is still live.
