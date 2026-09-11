# Stored pages: a library a child can re-open for nothing

**Date:** 2026-09-11
**Milestone:** M5 in [`docs/BACKLOG.md`](../../BACKLOG.md)
**Status:** design agreed; the schema slice (M5.1, M5.2) is already committed —
see "What already landed".

## Why

Reading a page costs about **$0.026 of vision** and **$0.025 of synthesis**, and
today none of it survives. A child who wants yesterday's page must photograph it
again and the app pays for all of it again — even though the audio is already on
disk, because the parse cache is keyed on the exact bytes of a photograph nobody
can reproduce.

Storage turns a per-read cost into a per-page cost. Re-reading is what children
actually do, so this is the change that makes the economics work at all.

## Decisions taken

**The app still opens on the camera.** The library is one tap away, beside
Settings. Reading a new page stays the fastest path; the library is for going
back. This keeps the app's existing answer to "what is this for".

**Fifty pages, oldest evicted automatically.** At measured sizes — roughly
1–3MB per photograph and ~250KB of audio per page — that is a ceiling of about
50–150MB. Bounded from the first build, needs no UI, and M6 later replaces the
fixed number with a real size budget. The cost is honest and stated below.

## What already landed

`StoredPageEntity`, `StoredPageDao` and `MIGRATION_5_6` are committed (`743d3b6`).
Writing the schema before the design was the wrong order; this spec records what
exists rather than describing it as future work.

The schema carries three decisions worth restating, because the rest depends on
them:

- **Keyed on the hash of the uploaded bytes** — the same key `parsed_page` uses,
  so a stored page and its cached parse agree by construction rather than through
  a foreign key someone must maintain.
- **No audio is copied.** Clips live in `cached_audio` keyed on
  `sha256(voiceId|text)`, and the persisted voice map resolves the same voice for
  the same character. Re-opening therefore resolves the same keys and hits the
  same files. Which clips a page owns is *derived* from its units, never stored
  twice.
- **`parseVersion` is recorded but does not invalidate a row.** The transcript is
  what was read aloud. Re-parsing would spend a vision call to change wording the
  child has already heard.

## Architecture

Four pieces, each testable alone.

### 1. `StoredPageRepository` (domain interface, data implementation)

```kotlin
interface StoredPageRepository {
    fun observeLibrary(): Flow<List<StoredPage>>
    suspend fun save(image: PageImage, units: List<SpeechUnit>)
    suspend fun open(id: String): StoredPage?
    suspend fun delete(id: String)
}
```

`StoredPage` carries the id, the photo `File`, the units, and when it was read.
The repository owns the photograph on disk and the eviction rule; nothing above it
knows where bytes live.

### 2. A third way into the pipeline

`ReadingPipeline` gains one method beside `start` and `retry`:

```kotlin
fun openStored(units: List<SpeechUnit>, image: PageImage)
```

This is not new machinery. `retry()` already has a cached-parse branch that emits
`Preparing(cached, emptyList(), image)` and calls `prepareAll` — skipping the
vision call and running synthesis and prefetch exactly as a fresh read does.
`openStored` is that branch reached deliberately rather than by retrying.

Reusing the pipeline rather than constructing `PipelineState.Ready` directly is
the whole point: prefetch, playlist growth, failure mapping, cancellation and the
Auto/Tap modes all keep working, and a stored page behaves identically to a fresh
one from the reader's perspective.

### 3. The library screen

A new `Routes.LIBRARY`, reached from an icon on the capture screen beside
Settings. A grid of stored pages, newest first, each showing its photograph as a
thumbnail and the date it was read. Tapping one opens the reader. Long-pressing
one offers to delete it.

### 4. Eviction

On every successful save: insert, then if the row count exceeds fifty, delete the
oldest rows and everything they own — the photograph, and any audio clip no
*other* stored page still needs.

That last clause is the one with teeth. Two pages containing the same line in the
same voice share one clip, because the cache is content-addressed. Deleting a page
must not delete audio another page is still using.

## Data flow

**Saving.** `ReadingPipelineImpl` already knows when a read has fully succeeded —
it emits `Ready` with every prepared unit. Saving hangs off that, not off the
reader, so a page is stored whether or not the child stays on the screen.

**Opening.** Library → `openStored(units, image)` → `Preparing` → synthesis
resolves every clip from cache → `Ready`. No vision call, and no ElevenLabs call
unless a clip is genuinely missing.

**Evicting.** Save → count → delete oldest → remove unshared clips and the photo.

## The zero-network claim, stated precisely

Re-opening makes **no vision call, ever** — the transcript is stored.

It makes **no synthesis call** provided every clip is still on disk and the voice
for each character is unchanged. Two things can break that, both legitimate:

- **A cleared or migrated voice map.** `MIGRATION_4_5` already cleared it once.
  A different voice means a different cache key, so those lines are re-synthesised.
- **A missing clip.** `AudioRepositoryImpl` already treats a row whose file has
  vanished as a miss, and re-buys it.

Both are correct behaviour and neither is silent — the reader shows synthesis
progress as usual. The test pins the *normal* case: nothing changed, nothing
fetched.

## Error handling

| what went wrong | what happens |
|---|---|
| photograph missing from disk | the page opens text-only, exactly as a page whose crops fail does today |
| a clip missing | re-synthesised, as any cache miss is |
| stored units unparseable | the row is dropped and the page disappears from the library rather than failing to open |
| eviction cannot delete a file | the row still goes; an orphaned file is wasted space, not a broken library |

The bias throughout is that **a library entry never fails to open**. A page that
cannot show its picture is still a page a child can hear.

## Testing

- **Repository**, on a plain JVM with a temp dir: save writes a photo and a row;
  eviction at the cap removes the oldest and its photo; eviction never removes a
  clip another stored page still needs; delete removes row, photo and clips.
- **Pipeline**: `openStored` emits `Preparing` then `Ready` and makes **zero**
  calls to a recording `PageReader` — the property the milestone exists for.
- **Library screen**, Robolectric: an empty library says so; a populated one lists
  newest first; tapping reports the right id; long-press offers deletion.
- **Migration**: already covered — additive, and existing rows survive.

## Out of scope, deliberately

- **Books.** M8. A stored page belongs to no collection yet.
- **Recognising a re-photographed page.** M7. Opening happens by id from the
  library, never by matching a new photograph against stored ones.
- **A real size budget.** M6. Fifty pages is a stand-in for a measurement nobody
  has taken; M6.1 takes it.
- **Sharing or exporting.** Not on the backlog at all.

## Risks

**A page a child liked can vanish.** Fifty is arbitrary, eviction is silent, and
the fifty-first page read removes the first. This is the cost of being bounded
before M6 exists, and it is the thing most likely to want changing after real use.

**Photograph size is unmeasured.** The 1–3MB figure is inferred from the
`displayBytes` of pulled diagnostic bundles, not measured on device across a
range of books. If it is materially larger, fifty pages is the wrong number and
M6.1 should be pulled forward.

**Saving on `Ready` stores partially-read pages.** A child who photographs a page
and immediately leaves still has it stored, because `Ready` means synthesis
finished, not that anyone listened. That is the correct trade — the money is
already spent — but it means the library is a record of what was *paid for*, not
of what was read.
