# Scrollable panel reader — design

**Date:** 2026-09-02
**Status:** approved in chat, pending written review

## 1. What changes and why

The reader shows one speech bubble at a time, cropped around a line's bounding
box, with previous/next arrows. Two things are now wrong with that.

It shows the child **the lettering they cannot read**. The whole point of reading
aloud to a pre-reader is that they cannot read the words; cropping to the words is
cropping to the one part of the page they get nothing from. The panel — the framed
picture the line is spoken in — is what they want, and the app has resolved panels
reliably since `PARSE_VERSION 7` (mean IoU 0.949 against hand labels, see
`docs/issues/2026-08-31-bubble-box-accuracy-measured.md` §19-20).

And it is **paged when it should be a page**. A comic page is a spatial thing read
in sequence; stepping through it one box at a time hides that structure.

So: a vertical scroll of the page's panels, each with its own lines tappable
beneath it.

### 1.1 A deleted feature this resembles, and does not repeat

`docs/PROJECT.md` records that a **transcript list** — "a scrollable list of every
line on the page" — was built and then deleted in favour of the one-bubble reader.

This is not that. That list was text; this is pictures with their text beneath.
The reason the old list failed is not recorded, and if it failed for a reason that
still applies, this design should be revisited rather than shipped.

## 2. State

`ReaderUiState.Playing` stops carrying a flat line list:

```kotlin
data class PanelGroup(
    /** The panel these lines share, or null when none was resolved. */
    val panel: BoundingBox?,
    val lines: List<Line>,
)

data class Playing(
    val panels: List<PanelGroup>,
    val current: Int,
    val image: PageImage?,
    val playback: PlaybackState,
    val mode: ReadingMode,
    val playingIndex: Int?,
) : ReaderUiState
```

**`current` and `playingIndex` remain LINE indices, not group indices.** Playback
is per line; only the rendering groups. Every existing invariant about those two
fields — including the deliberate divergence documented on `playingIndex` — is
unchanged. Group indices are derived where the UI needs them and stored nowhere.

`Line` is unchanged. It already carries `panel`.

## 3. Grouping

A new `ui/reader/PanelGrouping.kt`, pure Kotlin over `List<Line>`, no Android
imports, tested on the plain JVM:

```kotlin
fun List<ReaderUiState.Line>.groupByPanel(): List<ReaderUiState.PanelGroup>
```

Rules, all load-bearing:

1. **Consecutive runs only.** Lines group when they are adjacent in reading order
   *and* share an equal panel box. Two lines with the same panel separated by a
   line with a different one produce three groups, not two. Reading order is the
   product; collapsing across it would reorder the story.
2. **A null panel never groups.** Each line with `panel == null` is its own
   single-line group. Nulls are absences, not a shared value, and merging them
   would put unrelated lines under one picture.
3. **Equality is `BoundingBox` structural equality.** It is a data class, and §19
   measured that units sharing a panel receive byte-identical boxes, so exact
   equality is the correct test and no tolerance is needed. If that ever stops
   holding the symptom is visible — one picture per line — rather than silent.
4. **Order is preserved.** `panels.flatMap { it.lines }` equals the input list.
   This is worth asserting in a test: it is the property that guarantees no line
   is lost or duplicated by grouping.

### 3.1 A performance consequence

Today `Bubble` decodes a crop per line. Two lines sharing a panel decode the same
region twice. Grouping decodes **once per group**: on the measured page
`page-1788370633603`, 5 lines across 4 panels, that is 4 decodes rather than 5;
on `page-1788294930134`, 10 lines across 5 panels, 5 rather than 10.

This is a consequence of the structure, not a goal, and no caching is added for it.

## 4. Screen

A `LazyColumn` of panel cards. One card per `PanelGroup`, keyed by the index of
its first line so the key is stable across recomposition.

Each card:

- **The picture**, from `cropBubble(image, firstLine.bounds, group.panel)` — the
  existing function, unchanged, which already prefers the panel and falls back to
  the balloon.
- **Height capped** at `PANEL_MAX_HEIGHT_FRACTION = 0.5f` of the container
  height, named as a constant rather than inlined so it is one edit to tune, and
  **fitted, never cropped** (`ContentScale.Fit`). A crop to fill would discard
  art, which is the exact thing this feature exists to show. A tall panel
  letterboxes rather than losing its edges.
- **Its lines beneath**, one tappable row each, showing speaker and text.
- **The sounding line marked**, reusing the existing `sounding` treatment.
- **Not-yet-synthesised lines greyed**, reusing `contentAlphaFor(audioReady)`.

A group whose picture cannot be produced (`cropBubble` returns null — no panel, no
balloon, or a decode failure) renders its lines as a text-only card. This is the
existing text fallback, unchanged in behaviour and now scoped to a card.

### 4.1 The word-highlighting seam

A future feature will bold the word currently being spoken. This design does not
build it and adds no unused parameters for it. It does one thing: each line's text
is rendered by its own small composable, so that change is local to one function
rather than surgery on the card.

## 5. Auto-scroll

`LaunchedEffect(playingIndex)` animates the list to the group containing the
sounding line.

- **It scrolls to the GROUP, not the line.** With the picture capped at half the
  screen, bringing the group into view brings the picture and its lines together,
  which is the requirement this cap exists to satisfy.
- **A manual drag suspends it.** Detected from the list's own scroll state. While
  suspended, playback continues and the sounding marker still moves; only the
  scrolling stops.
- **A tap resumes it.** Tapping any line is a fresh statement of where the child
  wants to be, so it clears the suspension.
- **A new page resets it.** Suspension does not survive into the next page.

In **Tap** mode nothing plays on entry, so nothing scrolls until the first tap.
The mechanism is identical; it is driven by `playingIndex`, which is simply null
until then.

## 6. Tap

`onBubbleTapped()` takes no argument today — it plays whatever `current` is,
because only one bubble is ever on screen. A list needs the index:

```kotlin
fun onLineTapped(index: Int)
```

Semantics differ by mode, and this is a real behavioural addition rather than a
rename:

- **Tap mode** — unchanged from today, applied to the tapped line: play that one
  line, as a one-unit playlist followed by `endOfPage()`.
- **Auto mode** — jump: play from the tapped line to the end of what is ready, so
  the story continues from where the child pointed. Today `onBubbleTapped()`
  early-returns in Auto mode entirely.

In both modes, tapping a line whose audio is not yet synthesised is a **no-op**.
Those lines are already greyed by `audioReady`, so the affordance and the
behaviour agree.

`current` follows the tap in both modes, so the two stay coherent. Section 7
deletes `moveTo`, so `onLineTapped` sets `current` itself — and must keep
`moveTo`'s bound (`coerceIn(0, lines.size - 1)`), which was the only thing in that
function not specific to the arrows.

## 7. Deletions

- The previous/next control bar.
- `ReaderViewModel.onNext()`, `onPrevious()`, and the private `moveTo(index)` that
  serves only them.
- The single-`Bubble` paging path in `ReaderScreen`.

Accessibility is not weakened by dropping the arrows: a `LazyColumn`'s items are
natively traversable by TalkBack, and each line row is an ordinary clickable with
a content description. The arrows were partly standing in for that.

`BubbleCrop.kt` is untouched.

## 8. Error and edge cases

| case | behaviour |
|---|---|
| no panel on any line | every line is its own group; balloon crops, exactly as today |
| no panel and no balloon | text-only cards |
| `cropBubble` returns null | that card renders text-only; the rest of the page is unaffected |
| one line on the page | one group, one card, no scrolling |
| page still synthesising | all lines visible, un-ready ones greyed and un-tappable |
| retry after failure | `onRetry()` unchanged; `current` resets to 0 |

## 9. Testing

**Pure JVM, `PanelGroupingTest`:** a single run; several runs; alternating panels;
all-null panels; mixed null and non-null; the flatten-equals-input property; an
empty list.

**Robolectric/Compose, `ReaderScreenTest`:** a card per group; tapping a line calls
`onLineTapped` with that line's index; an un-ready line does not; the sounding
marker is on the right row; a text-only card when there is no picture.

**`ReaderViewModelTest`:** `onLineTapped` in Tap mode plays one unit;
`onLineTapped` in Auto mode plays from that index; tapping an un-ready index is a
no-op; `current` follows the tap.

Existing `ReaderScreenTest` and `ReaderViewModelTest` cases that assert the paged
model or the arrows are updated or removed with their feature.

## 10. Out of scope

- **Word-level highlighting.** Section 4.1 leaves the seam; the feature is later.
- **Panels with no dialogue.** The hut panel on `page-1788294930134` is real and
  will not appear. The schema returns a panel *per unit*, so a panel nobody speaks
  in is not in the data at all. Showing them would need a page-level panel list
  from the model — a separate change with its own measurement.
- **Cross-page scrolling.** One page at a time, as today.
- **Pinch-zoom on a panel.**
