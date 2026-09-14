# A badge on every line, and three voices behind it

**Date:** 2026-09-13
**Milestone:** M3 in [`docs/BACKLOG.md`](../../BACKLOG.md)
**Depends on:** M2 (character roster), merged in PR #6
**Status:** design, not yet built

## Why

Voices are assigned at **random** and never revisited. `VoiceRepositoryImpl.voiceFor`
picks `pool[random.nextInt(pool.size)]` the first time it sees a character and
persists it; `VoiceDao` has `find`, `count` and `upsert`, and nothing in the app ever
calls `upsert` again. A gruff voice on a small rabbit stays that way for the life of
the book.

The child is the one who notices, and the child has no way to say so.

## The shape

Every text row already prints its speaker — `ReaderScreen.kt:448`,
`Text(line.speaker, style = labelLarge)`. It looks like a caption and does nothing.

It becomes a **badge**: visibly a control, big enough for a child's finger, and
tapping it opens a screen offering **three voices for that character**. Pick one,
hear it, come back. The page keeps reading.

**Text, not a portrait.** `PROJECT.md` records a character "badge" that already
existed once — a cropped portrait with a `character_voice.badgePath` column that a
migration later dropped. This is not that returning. No image, no new column.

## Three voices, per character

Settled as D6 against the API's own labels, which carry gender, age, accent and a
descriptive word for each of the account's 21 voices.

**The three are per character, not three for the book.** If every character is
offered the same trio, a book can hold only three distinct voices and a page with six
characters has them colliding two-to-one — the Bone page in the diagnostics has five.

Each character is offered:

1. **the voice it already has** — so nothing a child has chosen can vanish
2. **two alternatives that contrast with it**, on gender and age before timbre,
   because those are the differences a four-year-old can hear from one sample
3. **never a voice already assigned to another character on the same page**

Two voices are labelled `characters_animation` (Callum, Harry) and are preferred for
characters over the conversational and social-media ones.

## What a child hears before choosing

Each of the three is auditioned on **that character's own shortest line on the
page**, not a canned sample and not the line in hand.

A badge exists because the character speaks, so such a line always exists.

*Ruled here rather than asked, and easily changed:* a canned sample means judging a
voice on words from another book; the line in hand may be a long paragraph, and
auditioning three voices on it costs three full syntheses every time a child opens
the screen. The character's shortest line is real dialogue from the book in front of
them, cheap to buy twice, and — because it is the character's own line — **already
synthesised in the current voice**, so the first of the three cards costs nothing.
The other two cache like any other clip, so a second visit to the same screen is free.

## Architecture

### 1. The voice list needs names

`voice_list` stores `voiceIdsCsv` — ids and nothing else. A picker cannot show
`pqHfZKP75CvOlQylNhV4`. The row gains the name and the labels the choice is computed
from, which means a schema change and a migration (v6 → v7).

### 2. `VoiceRepository` gains two methods

```kotlin
interface VoiceRepository {
    suspend fun voiceFor(character: String): Result<String>

    /** The three voices offered for [character] on a page where [taken] are spoken for. */
    suspend fun choicesFor(character: String, taken: Set<String>): Result<List<VoiceChoice>>

    /** Replaces the stored voice. The one thing nothing in the app can do today. */
    suspend fun assign(character: String, voiceId: String): Result<Unit>
}
```

`VoiceChoice(id, name, isCurrent)`. Selecting the trio is a pure function over the
voice list plus the current voice plus the taken set — so the rule that actually
decides what a child sees is testable without a network, a database or a screen.

### 3. The badge, and the screen behind it

`LineRow` renders the speaker as a control and reports taps upward, carrying the
line's **`voiceKey`**, not its displayed `speaker`.

That distinction is load-bearing and is the whole reason this waited for M2. The
badge shows `line.speaker`, which drifts between reads — "the boy with brown hair"
one time, "the boy in the green shirt" the next. The voice is keyed on the reconciled
`voiceKey`, which does not. A picker that wrote against the displayed string would
undo exactly what M2 was built to fix.

A new `Routes.VOICE` screen takes that key, shows three cards, plays one on tap, and
writes the choice on confirm.

### 4. Changed voices need new audio

Audio is keyed `sha256(voiceId|spokenForm(text))`, so changing a character's voice
does not invalidate anything — it simply misses, and every line that character speaks
is re-synthesised at ElevenLabs rates. **This is the expensive part of the feature**
and the reason the backlog puts it after M5: a re-synthesised page wants a stored page
to belong to, or the new audio is tied to an in-memory reader and bought again next
time.

## Data flow

**Opening:** badge tap → `voiceKey` → `choicesFor(key, taken)` → three cards, current
one marked → audition clips synthesised once and cached.

`taken` is the set of voices resolved for the *other* `voiceKey`s on the page in
hand — the reader already holds them, so the screen is told, not left to ask.

**While the page is reading:** opening the picker **stops** playback. The app has
one `PagePlayer`; an audition has to come out of it, and a page reading underneath
an audition is two voices at once. The reader keeps its position — its ViewModel
survives on the back stack — so a child comes back to the line they left.

*Corrected during planning.* An earlier draft said the picker left playback alone.
That is not implementable against a single player, and would have sounded worse
than it read.

**The narrator is a character here.** `prepare()` already resolves
`unit.voiceKey ?: NARRATOR`, so narration is keyed and assignable like anyone else.
Its badge works; it is not a dead label.

**Choosing:** `assign(key, voiceId)` → `VoiceDao.upsert` → back to the reader → the
lines that character speaks re-prepare.

**Everyone else is untouched.** Only lines whose `voiceKey` matches are re-requested;
every other clip is already cached and stays.

## Error handling

| what went wrong | what happens |
|---|---|
| voice list unavailable (offline) | the screen shows the current voice alone and says the others cannot be reached; nothing is lost |
| an audition fails to synthesise | that card plays nothing and says so; the other two still work |
| `assign` fails to write | the choice is not applied and the child is told, rather than the reader silently re-reading in the old voice |
| fewer than three voices in the pool | offer what exists; one voice is not a choice, and the screen should say so plainly |

## Testing

- **Pure**: trio selection — current always included; alternatives contrast on gender
  or age; a voice taken by another character is never offered; a pool of one degrades.
- **Pure**: the audition line is the shortest line the *badged* character speaks, not
  the page's shortest and not the line tapped.
- **Repository**: `assign` overwrites an existing row; `choicesFor` reads the stored
  current voice.
- **Screen**, Robolectric: the badge names its own line's speaker, not the page's
  first; tapping reports the `voiceKey`; three cards render with the current marked.
- **Integration**: after `assign`, only the changed character's lines are re-requested.
- **Integration**: opening the picker mid-page stops playback and keeps position.

## Out of scope

- **Per-book voices.** M8. The map is global until books exist.
- **Recording your own voice.** Not on the backlog.
- **Re-rolling every character at once.** Cheap once `assign` exists; deferred.

## Risks

**Re-synthesis cost is real and unbounded.** A child who changes a voice on a
ten-line page buys ten clips. A child who does it repeatedly buys them repeatedly.
There is no spend cap anywhere in this app — the single largest gap in it — and this
feature is the first that lets a child spend money by tapping rather than by
photographing.

**The offered pair is order-dependent.** Excluding taken voices means the same
character can see different alternatives on different pages. The current voice is
always present, so nothing chosen disappears, but the copy should not claim the three
are fixed.

**A page of pure narration is one badge repeated down the screen**, and a crowded
comic page has six different ones. Both want looking at on a device before the
styling is settled.

## Review findings

### High: the current voice can conflict with the `taken` rule

The rules say both that the current voice is always included and that a voice
already assigned to another character on the page is never offered. Those
conditions can be mutually exclusive, especially after a page is re-read or
after assignments are made on another page. The spec needs a precedence rule
and an explicit behavior for a collision. The safest behavior is to preserve
the current assignment as a fourth, locked choice (clearly marked as a
collision), exclude it from alternatives, and prevent assigning the same voice
to two characters through this screen. Otherwise `choicesFor` cannot satisfy
its own contract.

### High: auditioning is not guaranteed to be free for the current voice

The shortest line may not have been synthesized: Tap mode can leave it
unplayed, and a page may be only partially prepared when the badge is opened.
The statement that the first card costs nothing is therefore only true on a
cache hit. The opening flow needs an explicit cache lookup and loading state
for all three cards, including the current voice, plus cancellation behavior
when the screen is left. It should also say whether an audition failure is
allowed to trigger a paid synthesis or only reports a cache/network failure.

### High: reassignment has no defined page/audio consistency model

`assign` changes a global character-to-voice row, while already prepared
`PreparedUnit`s, the active player queue, and stored pages still contain the
old voice/audio. “The lines ... re-prepare” does not define whether playback is
paused, whether the current line restarts, how stale queued items are removed,
or how a stored page is updated. Define an assignment event/version and an
atomic reader refresh policy: stop or replace only the affected queued items,
preserve the current position where possible, and ensure the next stored-page
open resolves intentionally rather than silently mixing old and new audio.

### High: global assignments conflict with the stated per-page experience

The map is global until M8, so assigning a voice for one page changes every
existing and future book that uses the same `voiceKey`. The out-of-scope note
only says that per-book voices are deferred; it does not describe this
user-visible consequence. The picker must state that the choice applies
everywhere, and the design should specify whether existing stored pages are
re-rendered with the new voice or retain the voice captured when they were
read. Without this, reopening a stored page can produce audio different from
the page previously saved.

### Medium: the voice metadata and cache contract is incomplete

Adding names and selection labels requires more than `voiceIdsCsv`: the API
metadata shape, serialization format, freshness/expiry policy, migration
behavior, and handling of removed or renamed voices are unspecified. The
offline fallback cannot reliably show the current voice’s name when the
database currently stores only its id. Define a versioned cached voice record
(id, name, gender, age, accent, descriptive labels, and category), how old
rows are read, and what happens when the current id is absent from a refreshed
pool.

### Medium: “contrast” is not an executable selection algorithm

“Gender and age before timbre” does not define how to rank candidates, what to
do when metadata is missing, or how ties are broken. As written, the pure
selection test cannot be deterministic and two implementations can produce
different trios. Specify a stable ordering and tie-breaker (for example,
category preference, gender distance, age distance, timbre distance, then
voice id), and define whether missing metadata is a mismatch or merely
unknown.

### Medium: the source of `taken` can become stale

Passing `taken` from the reader is correct only for the snapshot represented by
the current page. Another picker, a delayed assignment, or a page refresh can
change the global map before confirmation. `assign` must re-check uniqueness
against the current assignments (and return a conflict result), rather than
trusting the screen’s old `taken` set. The repository contract should make this
atomic with the write.

### Medium: the audition line needs a stable tie and empty-text rule

The shortest-line rule needs deterministic handling for equal-length lines,
whitespace-only text, and lines whose `voiceKey` is null or cannot be resolved.
“Shortest” should be defined as spoken characters or tokens, with source order
as the tie-breaker, and the picker should have an explicit unavailable state
when no usable text exists. This also belongs in the pure-function contract.

### Low: accessibility and interaction states are under-specified

The badge is being changed from a passive `Text` to a control, but the design
does not require an accessible label, minimum touch target, selected state,
disabled/loading state, or a non-audio indication for an audition failure.
Those states matter particularly for a child using TalkBack or a device where
audio is unavailable; include them in the screen acceptance criteria.
