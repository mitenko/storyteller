# Storyteller — The Bubble Reader

**Date:** 2026-08-26
**Status:** Approved design. Implementation plan not yet written.
**Replaces:** the transcript-list reader from `2026-08-25-storyteller-reading-modes-design.md`.

---

## 1. Overview

The reader stops being a list of lines and becomes one speech bubble at a time.

Each speech unit is shown as a cropped, zoomed region of the page photograph —
the actual bubble as drawn, filling most of the screen. The speaker's name is
shown with it. Previous and next buttons move between units in reading order.
Tapping the bubble reads that line aloud.

This replaces the tappable transcript list built in the previous iteration. That
reader worked, but its speaker badges — a character cropped from the page —
failed in practice: the crops did not identify anyone recognisably.

## 2. Why the list is being replaced, and what that failure tells us

The badge failure is worth recording precisely, because it is the only empirical
evidence this project has about the vision model's bounding boxes, and this
design depends on boxes far more heavily than the list did.

Badges cropped `characters[].bounds` — the character as drawn. In use, those
crops did not identify the speaker. **Attribution itself is fine**: the model
assigns lines to the right characters, and the speaker names are useful and are
being kept. It is specifically the *boxes* that disappointed.

That matters here because this design rests on `units[].bounds` — the speech
bubble box. Different field, same model, same call, same page. The one thing we
know about this model's box output is that one kind of box was not good enough.

**The list tolerated bad boxes; this design does not.** A character box 20% off
still produced a recognisable bear. A bubble box 20% off shows clipped or wrong
text, and the crop *is* the content — there is nothing else on screen to fall
back on.

**Therefore the first implementation task measures bubble-box accuracy against
real pages, before any reader is built on it.** If the boxes are as poor as the
character boxes were, that is a finding worth having after one page and a few
cents rather than after a rewritten reader. See §9.

## 3. The screen

One unit at a time:

- **The bubble.** A cropped, zoomed region of the page photo containing that
  unit's `bounds`, sized to fill the available space.
- **The speaker's name**, retained from the list reader because it is useful.
- **Previous and next buttons**, moving through units in reading order.
- **Tapping the bubble plays that unit.**

Buttons rather than a swipe pager: explicitly chosen. A pager matches "one at a
time" more elegantly and leaves the whole screen to the bubble, but a small
child may never discover a swipe, where an arrow is visible. Discoverability wins
over elegance for this audience.

## 4. Where the image comes from, and at what resolution

The reader has never seen the photograph — `PageImage` goes into the pipeline and
only text comes out. It is threaded through `PipelineState` exactly as badges
were in the previous iteration.

**`PageImage` gains the original bytes, for display only.** Capture currently
downscales to 1568 px on the long edge — the point where Haiku stops gaining
detail — and discards the original. That is right for the model and wrong for
this reader: a bubble occupying a fifth of the page is about 300 px, blown up
across a ~1080 px screen.

So `PageImage` carries both:

- `bytes` — the 1568 px version. Unchanged. Still what is uploaded, and still
  what the parse cache keys on, so cache behaviour is untouched.
- `displayBytes` — the original capture, used only for cropping bubbles.

Nothing about the model path changes. Only the pixels the child sees improve.

The memory cost is real and bounded: one full-resolution JPEG for the page being
read, released when the page is replaced.

## 5. When there is no bubble

`units[].bounds` is nullable, and the vision prompt explicitly permits null when
the model cannot locate a unit. A prose picture book — text set as paragraphs
with an illustration — has no bubbles at all, so nulls are the normal case there,
not an error.

**When `bounds` is null, the reader renders the unit's text large instead of an
image crop.** Same navigation, same tap-to-play, same speaker name. This turns
the null case from a degraded state into a second properly supported kind of
book, and it means a page whose boxes come back unusable is still readable.

## 6. Cropping

Bubble crops reuse `cropRect` from the badge work — normalized bounds to pixel
rect, padded, clamped to the image, rejecting implausible boxes — rather than a
second implementation of the same arithmetic.

Two differences from badge cropping:

- **Padding is smaller.** A badge was padded 10% of the box's larger edge to
  avoid a claustrophobic portrait. A bubble wants only enough margin not to clip
  its own outline.
- **Nothing is stored.** Badge crops were written to `filesDir` and pinned per
  character. A bubble crop is derived from the page in front of the child and
  lives only as long as it is on screen.

When `cropRect` rejects a box as implausible, the reader falls back to §5's text
rendering — the same path as a null box.

## 7. Playback and reading mode

Tapping the bubble plays that unit, through the existing one-unit-playlist path.

Both reading modes stay meaningful:

- **Tap** — the default. Nothing plays until the child taps the bubble.
- **Auto** — advances through the bubbles as each finishes, following the
  player's `playlistIndex`.

Auto reuses the index plumbing added for the current-speaker header. Without it
Auto would have to become vestigial, which would be a worse outcome than keeping
one branch of `when`.

## 8. What gets deleted

The badge machinery goes, deliberately rather than by neglect:

- `BadgeRepository` and its implementation — the crop, the temp-file rename, the
  pinning.
- `character_voice.badgePath` and `VoiceDao.setBadgePath`. The column is already
  documented as write-only with no reader; this removes it rather than leaving a
  column nothing uses.
- `BadgeIcon`, `CurrentSpeakerHeader`, and the badge fields on the reader's state.
- **The whole `characters` array** goes from the vision call — the box, the emoji
  and the array itself. The box fed badges; the emoji was only ever the badge's
  fallback; neither has a consumer once badges are gone, and asking the model for
  fields nothing reads is cost and prompt weight for nothing. `ParsedCharacter`
  goes with it, and `ParsedPage` collapses back to the unit list.

  This changes the cached parse payload, so `PARSE_VERSION` bumps to 3 and rows
  written by the current parser become misses — the same mechanism, and the same
  one-off re-read cost of about $0.003 per stale page, as when `characters` was
  added.

The speaker *name* stays. `CropGeometry` stays — this design uses it.

Deleting reviewed, working code is uncomfortable, which is exactly why it is
written down here rather than left to drift into dead weight. The badge feature
failed in use; keeping it costs a schema column, a repository, a Hilt binding and
their tests, and pays nothing.

## 9. Measuring the boxes first

The first implementation task extends the existing eval harness to score
`units[].bounds` — bubble boxes — the way it already scores character boxes:
intersection-over-union against hand-drawn boxes on real page fixtures.

It carries the same stop condition, for the same reason: **below 0.5 mean IoU,
stop and report rather than proceeding.** Below that, a crop starts framing the
wrong thing, and this design has no fallback beyond §5's text rendering — which,
if it fired on most units, would mean the bubble reader is a text reader wearing
a photograph.

Two known problems in the existing eval must be fixed as part of this, or the
number it reports will be optimistic:

- Its name matching is case-insensitive while production is case-sensitive.
- It has no narrator filter, while production excludes the narrator.

Neither affects the app. Both would flatter the measurement.

**The eval still has no fixtures.** `evals/fixtures/` is empty, and filling it
needs photographs of real pages with hand-drawn boxes — work only the repository
owner can do. Until it runs, this design's central assumption is unmeasured, in
exactly the way the badge feature's was before it failed.

## 10. Error handling

| Failure | Behaviour |
|---|---|
| `bounds` null | Render the unit's text large (§5) |
| `bounds` implausible — `cropRect` returns null | Render the unit's text large |
| Page image missing or undecodable | Render the unit's text large |
| Crop throws (OOM on a large page) | Render the unit's text large |
| Tap on a unit whose audio is not ready | Inert, and the bubble shows as not-yet-ready |
| Playback error | Existing `onPlayerError` path |

Every failure lands on the same fallback: the words, rendered as text. The child
can always hear and see the line, whatever the model returned.

## 11. Testing

**JVM.** Crop selection per unit — a real box crops, a null box falls back to
text, an implausible box falls back to text. Navigation — previous/next bound at
the ends, index in reading order. Tap plays exactly the current unit. Auto
follows `playlistIndex` while Tap ignores it, as now.

**Robolectric.** The bubble renders for a unit with a box; text renders for a
unit without one; the speaker name shows in both; previous is disabled on the
first unit and next on the last.

**Eval.** Bubble-box IoU against real fixtures, per §9 — the one thing no unit
test can answer.

## 12. Not in this iteration

- Word-level highlighting synchronised to speech. It needs per-word geometry from
  the model on top of per-unit boxes, and the model's boxes are the very thing
  in question. Revisit only if §9 comes back strong.
- Showing the whole page with bubbles as tap targets. That was considered and
  rejected in favour of one-at-a-time.
- Retaining the transcript list as an alternative view. If the bubble reader
  works, the list is redundant; if it does not, the list is what we fall back to
  in git history.
