# Storyteller — feature backlog

For prioritising. Sizes are estimates, not measurements. Items 1 and 2 are the
ones asked for; the rest are what the code and the measurements say sits around
them. Nothing here is scheduled.

## Where this stands

| milestone | state |
|---|---|
| M0 decisions | D1, D2, D3, D5 **still open**; D4 answered (alignment + estimator) |
| M1 speaker accuracy | **done**, PR #5, measured 69% → 91% |
| M2 character roster | **done**, PR #6 |
| M4A/B/C bold-as-read | **done**, seen working on a device 2026-09-11 |
| M10A non-words | **done bar hearing it** |
| G the walkthrough | **still unrun** |
| everything else | not started |

Two bugs were found by using the app that no test had caught: the reader ringed the
wrong line, and one balloon could be read twice. Both are fixed. That remains the
best argument for running G.

## The list at a glance

| # | Feature | Size | Blocked by | What it buys |
|---|---|---|---|---|
| **0** | **Device walkthrough** | S | hardware | Confidence that any of this works at all |
| **1** | **Store pages / voices** | M | 3 (voices half only) | A page re-opens without re-photographing or re-paying |
| **2** | **Store books (up to 3)** | L | 1, 4, 5 | A child returns to their book, not a loose page |
| 3 | Stable character identity | M | — | The voice map stops fragmenting |
| 4 | Page & book identity | L | — | Knowing this photo is page 34 of *Amulet* |
| 5 | Storage caps + eviction | S | — | 3 books cannot fill the phone |
| 6 | Vocative prompt fix | **XS** | — | Measured: 69% → 81% speaker accuracy |
| 7 | Re-size the token budget for #6 | **XS** | — | Keeps the guard's thinking from crowding out the answer |
| 8 | Panel-scoped speaker ID + location | L | 3 | Where the speaker stands in the picture |
| **9** | **Choose the voice reading a character** | M | 3 | A wrong or disliked voice becomes pickable, not random |
| 10 | Book recognition (auto) | L | 4 | No manual "which book is this" |
| 11 | Multiple profiles | M | 2 | Two children, two libraries |
| 12 | Device-TTS fallback | M | — | Works offline / when ElevenLabs is down |
| 13 | WiFi pre-check | S | — | No surprise mobile-data spend |
| **15** | **Say non-words as sounds (M10A)** | S | — | **done, unheard** — "MM?" hums instead of spelling "em-em" |
| **16** | **Recognise onomatopoeia, play real sounds (M10B)** | M | 17 (M11) | A crash sounds like a crash, and costs no synthesis |
| **17** | **Know the page kind (M11)** | M | — | Prose, comic and illustrated text stop being treated as one thing |
| **18** | **Show the first panel sooner (M12)** | M | probe | Streaming the parse, if the API allows it at all |
| **19** | **Make the wait feel like something (M13)** | S | — | The child's own photograph during the read, not a grey spinner |
| **14** | **Bold the word as it is read** | M | — | A pre-reader can follow the words, not just hear them |

Recommended order is at the bottom, with reasoning.

---

## 0. Device walkthrough — the gate

Not a feature, but it outranks every feature. No interactive device verification
has ever been performed, on any iteration, and no real API call has ever been made
by this code — the suite runs against MockWebServer. PR #3 merged without it, and
its own body asked for a device look first.

Everything below is a guess about behaviour until this runs once.

**Blocked by:** an ordinary Android phone and real keys. A Pixel has been attached
before; the only device currently visible to `adb` is a locked Clover POS terminal.

---

## 1. Store pages / voices *(asked for)*

**What exists.** More than it looks, and less than it sounds.

- `cached_audio` — keyed `sha256(voiceId|text)`, files in `filesDir`. Genuinely
  durable, survives re-photographing, and the OS cannot purge it. **This half is
  largely done.**
- `parsed_page` — keyed on a hash of the uploaded JPEG **bytes**. This is a cache,
  not a library. Re-photographing the same page at a different angle or exposure
  is a complete miss, and there is no way to list what has been read.
- `character_voice` — `character` string → `voiceId`, random from the pool, first
  write wins. Global: **no book scope**.

**What is new.** A page becomes a *thing you can go back to* rather than a cache
hit you might get: a stored page record (photo, parse, panel boxes, audio refs), a
list to re-open it from, and a stable page key that survives a second photograph.

**The catch on the voices half.** Persisting the character→voice map durably is
only worth doing once character identity is stable. Measured on 2026-09-08: the
same page read five times returned `Character` ×5, then `Unknown Character` ×5,
then `Girl/Man/Old Man/Boy`. Persisting that produces a map with several rows for
one character and one row shared by several characters — and it is *sticky*,
because first write wins. Storing it makes the bug permanent instead of transient.

So: ship the **pages** half freely; gate the **voices** half on #3.

**Value.** ElevenLabs is $0.025/page and dominates cost. A stored page is a re-read
for free, and re-reading is what children do.

## 2. Store books (up to 3) *(asked for)*

**What exists.** Nothing. There is no book concept anywhere in the schema.

**What is new.** A book entity, an ordering of pages within it, a cap of three with
a rule for what happens at four, and — the part easy to miss — **book-scoped
voices**. Today `character_voice` is global, so a generic `Man` in one book and a
`Man` in another are the same row and therefore the same voice. Two books of
placeholders collapse into one cast. Scoping the map to a book is a small schema
change and it is a prerequisite, not a refinement.

**Storage.** Rough order of magnitude, not measured: ~10 units/page at ~24KB of
mp3 each ≈ 250KB/page, so a 200-page graphic novel ≈ 50MB, three ≈ 150MB, plus
page photographs if those are kept too. That is survivable but not free, and there
is no eviction of any kind today (#5).

**Cost.** ~$5 of ElevenLabs to voice a 200-page book once. Storage is what stops
that being $5 every time it is read — which is the real argument for #1 and #2 as
a pair.

**Blocked by:** #1 (a book is a pile of stored pages), #4 (knowing which book a
page belongs to), #5 (a cap needs eviction).

## 3. Stable character identity

Resolve one character roster per page — or per book — and attribute every line
against that fixed list, instead of letting each call invent a description.

Measured 2026-09-08: free-form descriptions drift between runs ("the bearded man" /
"the bearded old man"), and unstable lines *doubled* when descriptions replaced
placeholders. A different string is a different voice.

This is the unlock for #1's voices half, #8 and #9. Full measurement in
[`docs/issues/2026-09-08-speaker-attribution-measured.md`](issues/2026-09-08-speaker-attribution-measured.md).

## 4. Page & book identity

"Is this photo a page I have already read, and if so which one?" Today the answer
is byte-equality, which is almost always no.

Needs a perceptual key rather than a byte hash, plus some notion of page order.
The manual version — the child or parent says "this is book 2, page 34" — is much
cheaper than the automatic one (#10) and may be enough.

## 5. Storage caps + eviction

Already on the deferred list. LRU eviction and cache size caps do not exist. Three
stored books make this real rather than theoretical.

## 6. Vocative prompt fix — **do this first**

The prompt says *"Use the character's name exactly as it appears on the page"*, and
on a comics page the only printed names are often the ones being **addressed**. So
"BUY ME MORE TIME, DUNCAN." is attributed to *Duncan*.

Measured: one added paragraph took speaker accuracy 69% → 81%, vocative errors
2/15 → 0/15, and generic placeholders 8 → 0. No extra call, no architecture.

**Two conditions.** The guard must explicitly preserve sound effects as `Narrator`
— the drafted version dropped every `PAF!` and `FOOMP!`, and a dropped line is
never heard at all. And it needs #7, because the guard induces thinking.

## 7. Re-size the token budget — **do this with #6**

Not a bug today. `PageReaderImpl.MAX_TOKENS` is **8192**, and its KDoc already
documents this failure mode from a device page. A `max_tokens` stop also fails
loudly — `textBlock()` throws naming `stop_reason` — rather than producing a short
transcript.

The concern is headroom under #6. The guard drove thinking to **7,230 tokens** on
one call, and a ten-unit answer needs 710-780 on top of that. It fits, barely, on
the pages tested. Re-measure with the guard in place and raise if needed.

(`scripts/measure_panels.py` hardcodes 3000 and should be raised regardless — it
currently measures a limit the app does not have.)

## 8. Panel-scoped speaker ID + location

Filed as a nice-to-have on 2026-09-08. It works, and it is the only way to get the
speaker's *position* in the picture, but it does not beat #6 on the speaker's
*name*, costs five extra calls per page, and regressed a page that already read
correctly. Promote it if the reader is going to point at who is talking.

## 9. Choose the voice reading a character *(asked for)*

Today voices are assigned **at random**: `VoiceRepositoryImpl.voiceFor` picks
`pool[random.nextInt(pool.size)]` on first sight of a character and persists it,
first-write-wins. Nobody chooses anything, and a bad draw is permanent — a gruff
voice on a small rabbit stays that way for the whole book.

The feature is a picker: show the page's characters, let a voice be auditioned and
assigned, and write that choice through `character_voice`.

**What makes it awkward, and why it wants #3 first.** The map is keyed on the raw
speaker string. Pick a voice for `Man` and it applies to every `Man` on every page
of every book; pick one for "the bearded old man" and the next call's "the bearded
man" gets a fresh random draw instead. So a picker built today would let a child
fix a voice and then watch it come undone. With a stable roster behind it, the
picker becomes what it sounds like.

**Also worth deciding:** whether the choice is per character, per book, or global,
which is the same question #2's book-scoping asks (2.2).

**Comparing voices on one line is the expensive gesture, and the cache is why.**
Audio is keyed on `sha256(voiceId|text)`, so every candidate voice against the same
line is a separate synthesis and a separate cached file — auditioning six voices on
one line costs six syntheses, not one. That is fine once (the results cache, so
re-auditioning is free) and painful if a child taps through voices repeatedly.

Three ways to keep it cheap, and 9.2a should pick one:

- **A short fixed audition line** shared across every character, so the whole voice
  pool is synthesised once ever and every later comparison is a cache hit. Cheapest
  by far; the child judges a voice on words that are not their story.
- **The page's shortest line** as the audition text. Still real dialogue, still
  cheap, and it caches per line rather than per pool.
- **The line in hand**, whatever it is. Truest to the choice being made, and the
  only option whose cost scales with how much a child fiddles.

Recommended: the second. It keeps the judgement honest — a real line from the book
in front of them — without letting a bored child run up the bill on a long
paragraph.

| id | task | size | notes |
|---|---|---|---|
| 9.1 | Fetch and cache the voice pool with names, not just ids | S | `voice_list` stores a CSV of ids today; a picker needs labels |
| 9.2 | Audition: play a candidate voice on THIS line's real text | M | Not a canned sample — a child judges a voice by the words they just heard it get wrong |
| 9.2a | Compare several voices on the same line, back to back | M | The actual choosing gesture. See the cost note below |
| 9.3 | A picker listing the page's characters and their current voice | M | New screen or a sheet from the reader |
| 9.4 | Write the choice through, overriding the random assignment | S | `voiceFor` currently never revisits a stored row |
| 9.5 | Re-synthesise the page's lines for a changed voice | M | The audio cache is keyed on `voiceId|text`, so old audio stays valid and new audio is a fresh miss — the pipeline just needs to re-request |
| 9.6 | Test: a chosen voice survives a re-read of the page | S | The property the feature exists for |

## 9a. Deferred sibling: bulk re-roll

"I don't like any of these" — a single control that re-draws every unassigned
character. Cheap once 9.4 exists; not worth building before it.

## 10. Book recognition (automatic)

Already deferred. The automatic form of #4. Expensive; #4's manual form probably
answers the same need for a personal build.

## 11. Multiple profiles

Already deferred. Only meaningful once there is a library to divide (#2).

## 12. Device-TTS fallback

Already deferred. Offline reading, and a floor under ElevenLabs outages.

## 14. Bold the word as it is read *(asked for)*

Highlight each word as it is spoken, so a child following along sees which word
makes which sound. This is the feature that turns the app from listening into
early reading practice.

**The codebase already expects it.** `LineText` exists as its own composable for
precisely this reason — its KDoc says so: *"a planned feature bolds the word
currently being spoken, and that change belongs in one small function rather than
inside the card's layout. No parameter for it is added until it is built."* So the
render side is a small, well-sited change.

**The hard part is timing, not rendering.** Nothing in the app currently knows
when a given word is spoken. Media3 reports a position within a unit's audio
file, and a unit is a whole line. Three ways to bridge that, cheapest first:

- **Estimate by character count.** Split the line's duration across its words
  proportionally. No API cost, no new data, and wrong on any line with a pause in
  it — which for dramatic comic dialogue is most of them.
- **Ask ElevenLabs for timestamps.** Their API can return per-character alignment
  alongside the audio. Accurate, and it changes the audio request and the cache
  payload: `cached_audio` stores a path and nothing else, so alignment needs
  somewhere to live or it is re-fetched on every cache hit.
- **Force-align on device.** Accurate, offline, and far more machinery than this
  is worth.

Recommended: the second, with the first as a fallback for cached audio that
predates alignment.

| id | task | size | notes |
|---|---|---|---|
| 14.1 | Decide the timing source | S | **Decision.** Estimated vs. real alignment changes everything below |
| 14.2 | Request and keep per-word timings alongside the audio | M | `cached_audio` gains a column, or a sidecar file beside the mp3 |
| 14.3 | Expose playback position per sounding unit | S | `PagePlayerImpl` has the position; `PlaybackState` does not carry it |
| 14.4 | Add the word index parameter to `LineText` | S | The change its KDoc reserved |
| 14.5 | Render the accent | S | Bold, or a highlight behind the word — worth trying both on a device |
| 14.6 | Fall back cleanly when there are no timings | S | Cached audio from before this exists must still play, just without the accent |
| 14.7 | Test: the accented index tracks position, and is absent without timings | S | Pure logic, testable off-device |

**Interaction with #1:** stored pages make this cheaper to iterate on — re-reading
a page to watch the highlight costs nothing once the audio is kept.

## 13. WiFi pre-check

Already deferred. Small. Matters more once a whole book might be synthesised in one
sitting.

---

## Recommended order

**First, 0, the device walkthrough, when hardware is available.** The app has
never been observed using real capture, real API calls, or the merged reader.
Everything structural should be informed by one real end-to-end run. If hardware
remains blocked, proceed with 6 + 7 rather than waiting indefinitely.

**Next, 6 + 7 together.** They are the smallest items on the list and the only
ones with a measured improvement attached. They also make everything downstream
better, because a fragmented voice map is a fragmented voice map whether or not
it is persisted. #7 is a headroom measurement and gate inside the same change,
not an automatic request to increase the current 8192-token limit.

**Then 3, stable page-level character identity.** It is the hinge. Parse the
already-requested roster, introduce stable page-local IDs, and attribute units
against those IDs before attempting book-level reconciliation. #1's voices half,
#8, and #9 all wait on it, and #2 inherits the problem if it lands first.

**Then stored pages, identity, caps, and books:** 1 (pages half only) → 4 →
5 → 2. Pages before books; a cap before three books; identity before a library.
Persisting voices remains gated on #3. #2 is last because it needs the other
three.

Everything else is genuinely optional and can be pulled forward on appetite rather
than dependency.

## Not on this list

Say if any of these belong: reading progress / resume-where-you-left-off, sharing
or exporting a read book, parental controls or time limits, reading speed control,
or anything about the capture step itself.

(Word-highlight-as-spoken graduated off this list — it is #14. The voice picker is
#9.)

---

# Tasks

Every task below is **XS** (under an hour) or **S** (half a day). Anything that was
M or L has been split until it is pickable in one sitting — a task you cannot
finish in a sitting is a task that gets half-done.

Tasks are grouped into **milestones**, and each milestone ends in something you can
see working on the device. That is the unit worth shipping; a task is just how it
gets done.

## What the code changed about the estimates

Three sizes in the earlier draft were wrong, found by reading the code rather than
guessing:

- **Word timing is not "expose what the player already has".** `PlaybackState` is
  event-driven: it updates on `onPlaybackStateChanged` and `onMediaItemTransition`
  and nowhere else. Nothing samples position. Word highlighting needs a repeating
  sampler with lifecycle rules (stop on pause, on background, on dispose), which is
  a new mechanism — M4 splits it out properly.
- **`PlaybackState.Playing` carries a playlist index, not a unit index**, and its
  own KDoc warns Tap mode always reports 0. Anything mapping playback to a line
  must go through `ReaderViewModel.playlistUnits`, not through the state directly.
- **The character roster is nearly free.** `PAGE_SCHEMA` already declares
  `characters`, already marks it required, and `pageInstruction` already asks for
  it — `PageDto` just throws it away. That is a parse change, not an API change.

## M0 — Decisions (no code, about an hour for all five)

These gate real work and cost nothing but a choice. Taking them together avoids
five separate stalls later.

| id | decision | what it blocks |
|---|---|---|
| D1 | Roster scope: per page, or per book | Shapes M2.4, M3.8, M8.2 |
| D2 | Page identity: perceptual hash, or manual entry | Picks M7A or M7B |
| D3 | At a fourth book: refuse, or evict least-recently-read | Shapes M6B.2 and M8.6 |
| D4 | Word timing: ElevenLabs alignment, or estimate from characters | Changes all of M4 |
| D5 | Audition line: fixed sample, page's shortest line, or the line in hand | Sets the cost of M3.7 |

Suggested for a personal build: **per page**, **manual**, **refuse**, **alignment
with the estimator as fallback**, **page's shortest line**.

## M1 — Speaker accuracy — **done 2026-09-10, PR #5**

Measured 69% to **91%**, generic placeholders 8 to 0, no dropped sound effects,
vocative errors 2/15 to 1/15. Better than the 81% the spike predicted. `MAX_TOKENS`
needed no change: the shipped wording spends zero thinking tokens where the spike's
draft spent 3,600-7,230.

| id | task | size |
|---|---|---|
| M1.1 | Add the vocative guard to `pageInstruction` | XS |
| M1.2 | Guard must preserve sound effects as `Narrator`, never drop | XS |
| M1.3 | Require distinct speaker strings, killing `Character`/`Man` | XS |
| M1.4 | Bump `PARSE_VERSION` 7 to 8 so cached parses read stale | XS |
| M1.5 | Re-measure thinking + answer tokens; raise `MAX_TOKENS` if thin | XS |
| M1.6 | Raise the hardcoded 3000 in `scripts/measure_panels.py` | XS |
| M1.7 | Test: a sound-effect line survives as `Narrator` | S |
| M1.8 | Test: a vocative line does not return the addressee | S |
| M1.9 | Re-run the offline measurement on the three bundles, about $0.30 | S |

**Done when** the measurement reproduces 80% or better with no dropped sound
effects.

## M2 — Character roster — **done 2026-09-10, PR #6**

Zero dangling ids, zero duplicate ids, zero dropped lines over nine reads. Wrong
attributions fell 4 to 1 and the last vocative error went with them. The limit is a
passing test: a character named on one page and only described on another is still
two, and 23 of 28 characters came back unnamed.

The ID must be stable beyond one model response. A generated `character_1` is
only response-local unless it is persisted with the page or reconciled against
an existing roster. Raw model labels remain diagnostic; voice assignments use
the persisted roster ID. This contract is part of M2, not an implementation
detail.

| id | task | size |
|---|---|---|
| M2.1 | Parse `characters` into `PageDto` instead of discarding it | XS |
| M2.2 | Define roster-ID lifetime: persist per page and reconcile before creating a new id | S |
| M2.3 | Add a stable id and a distinguishing description per entry | S |
| M2.4 | Carry the roster on `ParsedPage` through the domain | S |
| M2.5 | Resolve each unit's `speaker` to a roster id at parse time | S |
| M2.6 | Keep the raw string beside the id, for display and debugging | XS |
| M2.7 | Test: one page read twice yields the same ids | S |
| M2.8 | Test: two characters never collapse to one id | S |
| M2.9 | Re-key `character_voice` on roster id | S |
| M2.10 | Room migration v4 to v5; drop unsalvageable free-string rows | S |
| M2.11 | Test: the migration drops old rows without failing on open | S |

**Done when** reading one page twice resolves the same roster identities and
assigns the same voices both times. Do not migrate the voice table before the
ID and repeated-read tests pass; otherwise persistence can make a bad identity
decision permanent.

## M3 — Voice picker (needs M2)

Split the picker into identity-safe selection first and audio refresh second.
The first part is useful once M2 is complete; whole-page re-synthesis should
wait until stored-page ownership exists so changed audio has a durable home.

| id | task | size |
|---|---|---|
| M3.1 | Store voice **names** alongside ids in `voice_list` | S |
| M3.2 | Choose the audition line per D5 | XS |
| M3.3 | Synthesise and cache one audition clip per candidate voice | S |
| M3.4 | Picker route and entry point from the reader | S |
| M3.5 | Picker UI: this page's characters and current voice | S |
| M3.6 | Play a candidate on tap | S |
| M3.7 | Compare several voices back to back on one line | S |
| M3.8 | Write the choice through, overriding the random assignment | S |
| M3.9 | Test: a chosen voice survives a re-read | S |
| M3.10 | Test: choosing does not re-synthesise unchanged lines | S |
| M3.11 | Re-request audio for lines whose voice changed, after M5 | S |

**Done when** a disliked voice can be changed and stays changed.

## M4A — Word timing data — **done 2026-09-11**

Timing persistence is independent of playback sampling. Finish this slice before
changing the player so cached audio has a defined timing contract.

| id | task | size |
|---|---|---|
| M4A.1 | Obtain per-word timings per D4 | S |
| M4A.2 | Persist timings beside the audio: column or sidecar | S |
| M4A.3 | Estimator fallback: split duration by character count | S |
| M4A.4 | Degrade cleanly when cached audio has no timings | S |
| M4A.5 | Test timing serialization and estimator fallback | S |

## M4B — Playback position — **done 2026-09-11**

The sampler is a cold flow shared `WhileSubscribed`: no collector, no loop. Two
earlier shapes hung the whole test suite, the first badly enough to be killed for
memory — an always-on delay loop means the test scheduler never reaches idle.

| id | task | size |
|---|---|---|
| M4B.1 | Position sampler in `PagePlayerImpl`, every 50-100ms while playing | S |
| M4B.2 | Stop the sampler on pause, background, and dispose | S |
| M4B.3 | Carry elapsed-within-unit on playback state | S |
| M4B.4 | Map position through `ReaderViewModel.playlistUnits`, not playlist index | S |
| M4B.5 | Test sampler lifecycle and unit mapping | S |

## M4C — Word highlight UI — **done 2026-09-11, seen on a device**

| id | task | size |
|---|---|---|
| M4C.1 | Pure function: elapsed plus timings gives a word index | XS |
| M4C.2 | Add word-index parameter to `LineText` | XS |
| M4C.3 | Render the accent | S |
| M4C.4 | Test the accented index and null fallback | S |

**Done when** a child can follow the words on a real page, cached audio without
timings still plays, and the phone does not get warm.

## M5 — Stored pages

| id | task | size |
|---|---|---|
| M5.1 | `stored_page` entity: photo path, parse, panels, audio refs, timestamp | S |
| M5.2 | Migration for it | S |
| M5.3 | Write a stored page after a successful read | S |
| M5.4 | Keep the page photograph in `filesDir`, never `cacheDir` | S |
| M5.5 | Library route | XS |
| M5.6 | Library list with thumbnails | S |
| M5.7 | Re-open a stored page into the reader with no vision call | S |
| M5.8 | Delete a stored page, taking its audio and photo with it | S |
| M5.9 | Test: re-opening makes zero network calls | S |

**Done when** a page read yesterday reopens instantly and for nothing.

## M6A — Storage limits for loose pages

| id | task | size |
|---|---|---|
| M6.1 | Measure real audio bytes per page on the device | XS |
| M6.2 | A configurable size ceiling | S |
| M6.3 | LRU eviction over loose audio and photos | S |
| M6.4 | Define atomic cleanup of a page record, photo, and owned audio | S |
| M6.5 | Test loose-page eviction and cleanup | S |

## M6B — Book-protected storage

Book protection cannot be implemented before page membership exists. It is a
follow-up to M8, not a hidden prerequisite of the first storage-cap slice.

| id | task | size |
|---|---|---|
| M6B.1 | Never evict assets belonging to a stored book | S |
| M6B.2 | Define behaviour at the ceiling per D3 | S |
| M6B.3 | Test eviction never breaks a stored book | S |

## M7A — Page metadata

| id | task | size |
|---|---|---|
| M7A.1 | Store optional manual page number at capture | S |
| M7A.2 | Test page-number editing and display | S |

## M7B — Automatic page identity (optional)

Automatic matching is separate from manual book assignment. It is not required
for a useful personal-build library.

| id | task | size |
|---|---|---|
| M7B.1 | Perceptual page key tolerant of angle and exposure, if D2 is auto | S |
| M7B.2 | Match a re-photographed page to its stored record | S |
| M7B.3 | Test the same page photographed twice resolves to one record | S |

## M8 — Books, up to three

| id | task | size |
|---|---|---|
| M8.1 | `book` entity, with page membership and order | S |
| M8.2 | Scope `character_voice` to a book | S |
| M8.3 | Migration for both | S |
| M8.4 | Bookshelf route and list | S |
| M8.5 | Assign a stored page to a book | S |
| M8.6 | Enforce the cap, per D3 | S |
| M8.7 | Test: identical placeholder names in two books keep separate voices | S |

## M9 — The optional tail

One line each. None of it blocks anything above; pull one forward on appetite.

| id | item | rough | note |
|---|---|---|---|
| M9.1 | Panel-scoped speaker location (#8) | 5 tasks | Only once something renders "who is talking". Needs M2 |
| M9.2 | Automatic book recognition (#10) | 6 tasks | The expensive form of M7 |
| M9.3 | Multiple profiles (#11) | 5 tasks | Needs M8 |
| M9.4 | Device-TTS fallback (#12) | 5 tasks | Offline reading, and a floor under ElevenLabs outages |
| M9.5 | WiFi pre-check (#13) | 2 tasks | Matters more once a whole book is synthesised at once |
| M9.6 | Bulk voice re-roll | 2 tasks | Cheap once M3.8 exists |

## The gate, whenever hardware allows

| id | task | size |
|---|---|---|
| G1 | Run the 11-item walkthrough with a real storybook | S |
| G2 | Watch the merged panel reader specifically | S |
| G3 | Record the findings as a dated issue doc | S |

---

# Order

The order has two tracks because hardware is currently a conditional dependency:

- **Hardware available:** `M0 → G → M1 → M2 → M4A → M4B → M4C → M5 → M3 → M6A → M8 → M6B → M7 → M9`
- **Hardware unavailable:** `M0 → M1 → M2 → M4A → M4B → M4C → M5 → M3 → M6A → M8 → M6B → M7 → M9`, with `G` run at the first opportunity.

Where the reasoning is not obvious:

**M0 first.** Five decisions cost an hour together, and each one left open stalls a
milestone later.

**G is conditional but urgent.** The walkthrough is the cheapest way to discover
capture, API, synthesis, and reader failures; it should not wait for documentation
or code work if a real device is available. If hardware is blocked, M1 is still
safe to do because it is covered by offline measurements and MockWebServer tests.

**M1 next on the code track.** It is nine small tasks and the only work with a
measured gain already attached: 69% to 81%. It also prevents bad speaker labels
from becoming durable data later.

**M2 before persistence.** The roster is nearly free because the schema already
requests it, but stable-ID lifetime must be proven before migrating
`character_voice`. Otherwise the first persistent implementation can turn model
drift into a permanent wrong voice.

**M4 is split into three visible slices.** Timing storage, playback sampling, and
highlight rendering touch different subsystems and can fail independently. Keeping
them separate makes each change reviewable and ensures cached audio without timing
data has an explicit fallback.

**M5 before M3's full refresh path.** A picker can audition and persist a choice
after M2, but re-synthesizing all affected lines needs stored-page ownership. This
prevents changed audio from being orphaned or tied only to an in-memory reader.

**M6A precedes M8; M6B follows it.** Measuring bytes and evicting loose pages needs
no book model. Protecting book assets does, so that rule belongs after M8 rather
than pretending M6 is fully complete beforehand.

**M7 is manual-first and optional beyond that.** Manual page metadata and book
assignment are enough for a personal build. Perceptual matching is a separate
experiment and should not block books unless re-photograph recognition proves
essential.

**M9 last, and possibly never.** Nothing above depends on any of it.

## M10 — Lines that are not ordinary speech

Comics are full of text that is not a sentence, and the reader currently sends all
of it to a text-to-speech engine that assumes it is one. Two separate problems,
both heard on a device on 2026-09-11.

### M10A — Say non-words as sounds, not as letters — **mostly done 2026-09-11**

`MM?` on page `1789149384715` was read "em-em" on a device, because a short
all-caps token looks like an initialism and comics are lettered in capitals.

**The probe settled the shape of this, and narrowed it.** Each word synthesised
both ways, one sample each:

| word | CAPS | sentence | ratio |
|---|---|---|---|
| **MM?** | 0.604s | **0.418s** | **x1.44** |
| HMM. | 0.557s | 0.511s | x1.09 |
| UGH! | 0.464s | 0.511s | x0.91 |
| SHH. | 0.464s | 0.604s | x0.77 |

Only `MM` moves. `SHH` and `UGH` got **longer** in sentence case, so the blanket
lower-casing this milestone originally assumed would have fixed one word and
quietly damaged others — and would have rewritten every line of ordinary dialogue
too, since those are capitals as well.

| id | task | size | status |
|---|---|---|---|
| M10A.1 | Probe: does `Mm?` hum where `MM?` spells? | XS | **done** — x1.44, table above |
| M10A.2 | `spokenForm`, for SYNTHESIS only, never for display | S | **done** — runs of 2+ Ms only |
| M10A.3 | Decide the cache key | XS | **done** — keyed on the spoken text |
| M10A.4 | Bump `PARSE_VERSION` | XS | **not needed** — see below |
| M10A.5 | Test: display unchanged, spoken text normalised | S | **done** — 10 cases |
| M10A.6 | Hear it on a device and confirm | S | **open** — the only real proof |

**The cache resolved more cheaply than feared.** Keying on the spoken text means
lines whose spoken form equals their shown form — every line without an `MM` —
keep the clips already bought. Only the handful that change get new keys. And the
parse itself is untouched, so no `PARSE_VERSION` bump: M10A.4 is closed unbuilt.

**Still not established, and it is the whole claim:** nobody has heard this.
A 44% duration gap is strong evidence for spelled-versus-hummed and is not proof.
If `Mm?` does not actually hum, the fallback is a small substitution table, which
is more code and likelier to be wrong on a word nobody anticipated. Clips to judge
by are in the session scratchpad under `m10a/`.

**The rule is deliberately extensible and deliberately small.** Adding a word means
adding a measurement beside it. `OK` is kept in the tests as the cautionary case:
lower-casing it is exactly the plausible-looking change that makes a common word
worse.

### M10B — Recognise onomatopoeia and play the real sound

A sound effect read aloud in a character voice is not a sound effect. `BOOM` wants
an explosion; `SKRIEEECH` wants tyres. Today the narrator says the word.

**A lookup table cannot do this, and the evidence is already in the repository.**
Sweeping every pulled diagnostic bundle for sound effects turns up:

```
PAF!   FOOMP!   FOOMP!!   K-CHUNG!   WHIRRRRR   KRRKKRKK   KRRKR.
```

`KRRKKRKK` is in no dictionary and no hardcoded list would ever have anticipated
it. Comics invent their spelling every time, which is the whole charm of them.

**And they cannot be detected by shape either.** The same sweep, filtering on
"short, all-capitals, no lowercase", also returned `DOWN!`, `HEY!`, `NO.`, `COME.`,
`EAT.`, `GET DOWN!` — ordinary dialogue, indistinguishable from `FOOMP!` to any
regex, because comics letter *everything* in capitals. A rule that catches the
sound effects also silences the dialogue.

So the model classifies them. It is already reading the page and already returning
these as `Narrator` units; one more field per unit says what KIND of noise it is —
explosion, impact, screech, machine, footstep — and the app maps that small fixed
set of CATEGORIES to bundled audio. Invented spellings then cost nothing, because
nothing matches on the word.

**One distinction the categories must carry: whose noise is it?** The same sweep
found `ERGH!`, `GAH!`, `OOF!`, `UNGH!`, `MM?`, `SHH.` — a character grunting,
wincing, hushing. Those are *vocal*, and replacing `GAH!` with a canned sound would
strip a character of their voice mid-scene. Only environmental noises get replaced;
vocal ones stay with the speaker, where M10A is already making them read properly.

**Comic pages only** — hence the dependency on M11. On a page of prose, a capitalised
word is a word.

| id | task | size |
|---|---|---|
| M10B.1 | Decide the category set, and which are vocal (never replaced) | XS |
| M10B.2 | Ask the model to categorise each sound-effect unit, one field | XS |
| M10B.3 | Source or synthesise one bundled clip per category | S |
| M10B.4 | Map category to clip; unknown or absent category reads the word as today | S |
| M10B.5 | Play the clip in place of synthesis, in the same playlist | S |
| M10B.6 | Gate on M11 saying the page is a comic | S |
| M10B.7 | Test: a categorised unit costs no ElevenLabs call | S |
| M10B.8 | Test: a vocal interjection is NOT replaced | S |
| M10B.9 | Measure on the rabbit page (4 effects) and the robot page (2) | S |

**It saves money as well as sounding better.** Sound-effect units are pure cost
today — one synthesis each, at ElevenLabs rates, to say "FOOMP" badly. The rabbit
page alone carries four.

**M10B.1 and M10B.3 are product decisions, not technical ones.** A bundled
explosion is one fixed noise for every book and every art style, where the narrator
at least varies with the page. And a real explosion behind a four-year-old's
bedtime story may be less charming in practice than it sounds in a backlog. Worth
hearing both before committing to a library of clips.

**Both depend on M4's word timings only loosely:** a substituted effect has no word
alignment, so the accent simply does not apply to it, which the existing null path
already handles.

## M11 — Know what kind of page this is

The prompt opens "This is a photograph of one page from a children's storybook or
graphic novel" and then asks for a speech-balloon box and a comic-panel box for
every unit. That is three different kinds of page treated as one:

- **A graphic novel page** — panels, balloons, several speakers. What the reader
  was designed around.
- **A full page of prose** — no panels, no balloons, one voice. Every unit comes
  back with `panel: null` and `bounds: null` or a box around a paragraph, and the
  reader falls back to text-only cards. It works, and it asks the model for two
  boxes per unit that cannot exist.
- **Text with an illustration** — one picture, text beside or below it. Today the
  picture is either missed entirely or returned as a single whole-page "panel",
  which §18.2 of the bubble-box issue records the model doing.

| id | task | size |
|---|---|---|
| M11.1 | Ask the model to classify the page, one field, in the call it already makes | XS |
| M11.2 | Carry the kind through `ParsedPage` to the reader | S |
| M11.3 | Prose: stop asking for panel boxes, and read paragraphs rather than balloons | S |
| M11.4 | Illustrated text: one picture for the page, not one per line | S |
| M11.5 | Reader renders each kind appropriately | S |
| M11.6 | Test: a classified prose page requests no panel boxes | S |
| M11.7 | Measure on one page of each kind, the three already in the bundles | S |

**Cheap because the call already exists.** Classification is one more field on a
response the app already pays for — the same trick that made the character roster
nearly free. No extra call, no extra image.

**The risk is a wrong classification, not a missing one.** A graphic novel read as
prose loses every picture; prose read as a graphic novel asks for boxes that do not
exist and renders text-only anyway. The second failure is survivable and the first
is not, so when the model is unsure the answer should be "graphic novel" and the
existing null-panel fallback should do the rest.

## M12 — Show the first panel sooner

**What is already true, so nobody optimises it twice.** Panels do NOT wait for
audio: `PipelineState.Preparing` carries every unit as soon as the parse returns,
and the reader renders them immediately, greyed until each line's audio lands.
Crop decoding is already per-card and off-thread — `PanelCard` decodes in
`produceState` on `Dispatchers.Default`, so the first panel's picture never waits
for the tenth.

**What actually costs the wait: the vision call is atomic.** `PageReaderImpl` makes
one request and parses one complete JSON body, so nothing at all can render until
the model has finished the whole page. On a ten-unit page that is the entire delay
a child sits through, and it is the only remaining place to win.

The fix is to stream it. Anthropic's API supports server-sent events, and the
response is a JSON array of units — so units can be surfaced as they arrive rather
than after the last one.

| id | task | size |
|---|---|---|
| M12.1 | Probe: does streaming with a JSON-schema response actually yield usable partial units? | XS |
| M12.2 | Stream the vision response instead of awaiting the whole body | S |
| M12.3 | Parse units incrementally, tolerating a half-written object | S |
| M12.4 | Emit `Preparing` as units arrive, not once at the end | S |
| M12.5 | Start synthesising unit 0 before unit N has been parsed | S |
| M12.6 | Keep the diagnostic bundle whole — it records the RAW response | S |
| M12.7 | Test: a page renders its first panel before the last unit is parsed | S |

**M12.1 first, and it may kill the rest.** Structured outputs and streaming do not
always compose: if the service only emits the JSON once complete, there is nothing
to stream and the milestone is dead. One probe answers it, the same way M10A.1 did.

**A real cost to weigh:** the diagnostic bundle is the app's only window into what
the model actually said, and it currently records one complete raw response.
Streaming must not fragment that — §20.1 of the bubble-box issue is a whole section
about a field going missing from a bundle and the hours it cost.

## M13 — Make the wait feel like something

While the vision call is in flight the reader shows a spinner and the words
"Reading the page…". A child who has just photographed a page is shown a grey
circle and no evidence their photograph was taken at all.

Show them their own photograph instead, with a colour wash moving over it, so the
wait reads as the app *looking at the page they just took*.

| id | task | size |
|---|---|---|
| M13.1 | Carry the captured image on `PipelineState.Reading` | S |
| M13.2 | Render the photograph behind the reading state | S |
| M13.3 | Animate a colour sweep over it, respecting reduced-motion settings | S |
| M13.4 | Fall back to today's spinner when there is no image | S |
| M13.5 | Test: the reading state shows the photograph when one exists, and does not crash without one | S |

**One structural note.** `PipelineState.Reading` is a `data object` and carries
nothing. `Preparing` already carries `image`, and its kdoc says why it is required
rather than defaulted: "a call site that forgets to pass it should fail to compile
rather than silently ship a page nobody can crop a bubble from." `Reading` should
follow that, which turns it from an object into a data class — a small change that
touches every branch matching on it.

**Worth doing after M12, not before.** If streaming lands, this wait gets much
shorter and an elaborate animation over a one-second gap is wasted work. If M12.1
says streaming is impossible, this becomes the only thing that improves that wait
and is worth more.
