# Storyteller — feature backlog

For prioritising. Sizes are estimates, not measurements. Items 1 and 2 are the
ones asked for; the rest are what the code and the measurements say sits around
them. Nothing here is scheduled.

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

# Subtasks

Broken down for the critical path only — items 0-7. Items 8-13 stay at one line
each until something promotes them; decomposing work that may never be built is
waste.

Sizes: **XS** under an hour, **S** half a day, **M** a day or two, **L** longer.
Estimates, not measurements.

## 6 + 7 — Speaker prompt fix *(one change, not two)*

| id | task | size | notes |
|---|---|---|---|
| 6.1 | Add the vocative guard to `pageInstruction` | XS | The addressee is not the speaker; work from the balloon tail |
| 6.2 | Make the guard preserve sound effects explicitly | XS | Must say `Narrator`, never drop. The drafted guard lost 14 `PAF!`/`FOOMP!` units |
| 6.3 | Require distinct speaker strings | XS | Kills `Character`/`Man` placeholders — measured 8 → 0 |
| 6.4 | Bump `PARSE_VERSION` 7 → 8 | XS | **Easy to forget.** The prompt change alters output semantics, so cached parses must read as stale |
| 6.5 | Re-measure thinking + answer tokens, raise `MAX_TOKENS` if thin | XS | 7,230 thinking + 780 answer against 8192 today |
| 6.6 | Raise the hardcoded 3000 in `scripts/measure_panels.py` | XS | It measures a limit the app does not have |
| 6.7 | Test: a sound-effect line survives as `Narrator` | S | `PageReaderImplTest` + MockWebServer already exist |
| 6.8 | Test: a vocative line does not return the addressee | S | Fixture from the real `…251857` response |
| 6.9 | Re-run the offline measurement on the three bundles | S | Confirms 81% holds once 6.2 stops the line loss. ~$0.30 |

Order: 6.1-6.4 together, then 6.5-6.6, then the tests, then 6.9 as the gate.

## 3 — Stable character identity

**Cheaper than it looked.** The roster channel already exists on the wire:
`PAGE_SCHEMA` declares `characters`, lists it as `required`, and `pageInstruction`
asks for it — but `PageDto` declares only `units`, so the answer is discarded. The
schema work is already paid for.

| id | task | size | notes |
|---|---|---|---|
| 3.1 | Decide roster scope: per page, or per book | XS | **Decision, blocks the rest.** Per-page is buildable today; per-book waits on #2 |
| 3.2 | Parse `characters` into `PageDto` instead of discarding it | XS | Field already returned and already billed |
| 3.3 | Enrich each entry with a stable id + a distinguishing description | S | Schema edit; `name` alone is what drifts |
| 3.4 | Make each unit's `speaker` reference a roster id | M | Domain change reaching `SpeechUnit`, the reader and the voice map |
| 3.5 | Cross-page canonicalisation | M | Match this page's roster to characters already seen. String similarity first; a model call only if that fails |
| 3.6 | Re-key `character_voice` on roster id + a Room migration | S | v4 → v5. Existing rows keyed on free strings cannot be salvaged; drop them |
| 3.7 | Test: roster is stable across repeated reads of one page | S | The metric that doubled, 10 → 19 unstable lines |
| 3.8 | Test: two characters never resolve to one id | S | The collision that gives two characters one voice |

## 1 — Store pages / voices

Split deliberately: the pages half ships now, the voices half waits on #3.

| id | task | size | notes |
|---|---|---|---|
| 1.1 | `stored_page` entity: photo path, parse, panels, audio refs, timestamp | S | Distinct from `parsed_page`, which stays a byte-keyed cache |
| 1.2 | Write a stored page at the end of a successful read | S | In `ReadingPipelineImpl`, after synthesis |
| 1.3 | Keep the page photograph in `filesDir` | S | Same reasoning as audio: paid-for, must not be purged |
| 1.4 | A library screen listing stored pages | M | New Compose screen + ViewModel + nav entry |
| 1.5 | Re-open a stored page into the reader without a vision call | M | Reader currently only enters from capture |
| 1.6 | Stable page key surviving a second photograph | — | **This is #4.** Until then, re-opening works only from the library |
| 1.7 | Persist the voice map durably | — | **Gated on #3.** Persisting today makes the fragmentation permanent, because first write wins |
| 1.8 | Test: a stored page re-opens with zero network calls | S | The property the whole feature exists for |

## 4 — Page & book identity

| id | task | size | notes |
|---|---|---|---|
| 4.1 | Choose the mechanism: perceptual hash vs manual entry | S | **Decision.** Manual is far cheaper and may be enough for a personal build |
| 4.2 | Perceptual page key tolerant of angle and exposure | L | Only if 4.1 says automatic |
| 4.3 | Manual "which book, which page" on capture | S | The cheap path |
| 4.4 | Page ordering within a book | S | |
| 4.5 | Test: the same page re-photographed resolves to one record | M | The claim that justifies the feature |

## 5 — Storage caps + eviction

| id | task | size | notes |
|---|---|---|---|
| 5.1 | Measure real audio bytes per page on device | XS | Every size figure here is an estimate; no real synthesis has ever run |
| 5.2 | A size cap with a configured ceiling | S | |
| 5.3 | LRU eviction over audio + photos, never over a stored book | S | Evicting inside a kept book is the trap |
| 5.4 | What happens at a fourth book | S | **Decision, product-shaped:** refuse, or evict least-recently-read |
| 5.5 | Test: eviction never breaks a stored book | S | |

## 2 — Store books (up to 3)

| id | task | size | notes |
|---|---|---|---|
| 2.1 | `book` entity + page membership and order | S | |
| 2.2 | Scope `character_voice` to a book | S | Today it is global: `Man` in one book is `Man` in another, same voice |
| 2.3 | Room migration for both | S | |
| 2.4 | Bookshelf UI, max three | M | |
| 2.5 | Assign a captured page to a book | M | Needs #4's answer |
| 2.6 | Enforce the cap, per 5.4's decision | S | |
| 2.7 | Test: two books with identical placeholder names keep separate voices | S | The bug 2.2 exists to prevent |

## 0 — Device walkthrough

| id | task | size | notes |
|---|---|---|---|
| 0.1 | Get an ordinary Android phone attached | — | Blocked on hardware, not on code |
| 0.2 | Real keys in `local.properties` | XS | Absent today; the suite runs on MockWebServer |
| 0.3 | Run the 11-item walkthrough | S | `docs/superpowers/plans/2026-08-24-storyteller-iteration-1.md` |
| 0.4 | Watch the merged panel reader specifically | S | PR #3 merged unseen on a device |
| 0.5 | Record findings as a dated issue doc | S | |

---

# The ordered queue

One flat list. Everything above the line is doable now.

| rank | task | why here |
|---|---|---|
| 1 | 0.2-0.5 | Validate real capture, API calls, synthesis, and the merged reader as soon as hardware is available |
| 2 | 6.1-6.4 | Smallest change on the list with a measured gain. 69% → 81% |
| 3 | 6.5-6.6 | Measure guard-plus-answer headroom; raise 8192 only if the evidence requires it |
| 4 | 6.7-6.9 | Prove the guard preserves sound effects and the measured gain survives the corrected transcript |
| 5 | 3.1 | Choose page-level roster scope first; book scope waits for stored books |
| 6 | 3.2-3.3 | Nearly free: the roster is already requested and already billed |
| 7 | 3.4, 3.6-3.8 | Stable IDs, voice-map migration, and collision/consistency tests; the hinge for persistence |
| 8 | 1.1-1.3 | Store pages and paid assets, without durable voice assignments |
| 9 | 1.4-1.5, 1.8 | Library and zero-network reopen flow |
| 10 | 4.1, then 4.3 if manual | Choose the cheapest viable identity workflow before implementing recognition |
| 11 | 4.4-4.5 | Page ordering and re-photograph matching after the identity decision |
| 12 | 5.1, 5.4 | Measure real bytes and decide what happens at a fourth book |
| 13 | 5.2-5.5 | Add caps and book-boundary eviction before presenting three-book storage |
| 14 | 2.1-2.3, 2.7 | Books schema, book-scoped voices, and separation tests |
| 15 | 2.4-2.6 | Bookshelf UI, assignment, and cap enforcement |
| 16 | 3.5 | Cross-page canonicalisation once there are stored pages to reconcile |
| 17 | 9.1-9.6 | Voice picker, once #3 makes a chosen voice stick |
| 18 | 14.1, then 14.2-14.7 | Bold-as-read. Ranked last only because nothing depends on it — see the caveat below |
| — | 8, 10-13 | On appetite. None blocks anything above |

**Four decisions gate real work** and are cheap to make now: 3.1 roster scope,
4.1 identity mechanism, 5.4 behaviour at a fourth book, and 14.1 timing source.

**One caveat on the order.** #14 sits low because nothing depends on it, not
because it matters least — it is arguably the most visible thing on this list to a
child learning to read, and it is the only asked-for feature with no blocker at
all. If the point is to show someone what the app does, build it next.
