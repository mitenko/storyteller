# Vision eval harness

Answers the one question no unit test can: does Haiku 4.5 actually read real book
pages and attribute the right speakers?

This is scored as a pass rate, not asserted as pass/fail — the model is
non-deterministic, so equality is the wrong instrument.

## Running it

    STORYTELLER_EVAL=1 ANTHROPIC_API_KEY=sk-ant-... ./gradlew testDebugUnitTest \
      --tests "com.storyteller.evals.VisionEval" -i

Without both environment variables the test is skipped, so it never runs in the
normal suite and never spends money by accident. Each page costs about $0.003.

Each fixture is run through the same `downscaleToPageImage` call the app
itself uses before it is uploaded. This is deliberate: the point of the eval
is to predict what production sees, so it must feed the model the same bytes
production feeds it, not a full-resolution photo that would flatter the
score. Note that this only re-encodes images whose long edge exceeds 1568px —
a smaller image is passed through byte-for-byte unmodified but is still
labelled `image/jpeg` internally, so a small PNG fixture would be uploaded as
raw PNG bytes tagged with a JPEG mime type. That fails loudly (an `ERROR` row
for that fixture, not a silent misscore), but there's no reason to hit it:
supply JPEGs, or make sure any PNG fixture exceeds 1568px on the long edge.

## Adding a fixture

1. Photograph a real page the way a child would hold the phone. Put it in
   `evals/fixtures/` — that directory is gitignored, since the pages are
   copyrighted and large. Prefer `.jpg`/`.jpeg`; see the downscaling note
   above if you want to use `.png`.
2. Write `evals/expected/<same-basename>.json`:

       { "speakers": ["Narrator", "Wolf"], "minUnits": 3 }

   `speakers` is the exact set you expect, order-insensitive. `minUnits` is a
   floor, not an equality — the model may legitimately split a long paragraph.
   A fixture with no matching `expected/*.json` file is skipped, not scored.

## What to cover

Aim for around 15 pages: picture books and graphic novels, good light and bad,
flat and angled, glare and none, plus at least one page with no text at all
(expected `speakers: []`, `minUnits: 0`) and one hand-lettered comic panel, which
is the hardest case and the reason ML Kit was dropped.

## Reading the report

The `boxed=n/m` column on each row is the second thing this harness measures:
whether the returned bounding boxes are usable. Iteration 1 ignores them, but
iteration 2 needs them for tappable speech bubbles. If boxes come back
consistently null or land on the wrong bubbles, that is the signal to
reintroduce ML Kit for geometry before committing to that feature.

The final summary line reads `N/M evaluated passed (S skipped, E errors); B/M
evaluated returned bounding boxes`. `M` (evaluated) counts only fixtures that
were actually scored — it excludes SKIP rows (no matching `expected/*.json`
yet) and ERROR rows (the read itself failed). This matters because the
recommended workflow is to build `evals/fixtures/` up gradually; if the
denominator were the raw fixture count, a directory with 15 photos and
`expected/` data for only 3 would report something like `3/15 passed` even if
all 3 scored fixtures passed. Record the `evaluated` baseline (not the raw
fixture count) as the reference point for future prompt changes — it moves
only when scoring behavior changes, not when someone adds an unlabelled
photo.

## Character box accuracy (IoU)

Speaker/unit scoring above answers "did the model read the page right." It
says nothing about whether the *box* the model draws around a character is
any good — and the badge-crop feature (iteration 2) rests entirely on that
box being good, since it crops the character out of the photo at that
rectangle.

Any `expected/<name>.json` may add a `characters` block (format documented in
`evals/expected/README.md`) carrying a hand-drawn `bounds` box per character.
The harness matches each expected character to the model's returned character
by name and computes intersection-over-union (IoU) between the hand-drawn box
and the returned one. Per fixture it reports how many expected characters the
model returned at all, how many of those carried a returned box, and the mean
IoU of the ones where both a hand-drawn and a returned box exist to compare.
The final report line aggregates the mean IoU across every character scored
in the whole run — that aggregate is the number this section is about.

**Stop condition — read this before building anything further on the crop
path:** if that aggregate mean IoU comes out **below 0.5**, stop and report
rather than proceeding. 0.5 is the usual detection threshold; a badge crop is
padded 10% and displayed small, so it tolerates more slop than a hit-target
would, but below 0.5 the crop starts framing the wrong thing. A score under
0.5 means the crop path should be abandoned in favour of the emoji path
rather than having more UI built on it. Only if the aggregate is at or above
0.5 should the run proceed and the number be recorded here as the baseline
for future prompt changes, the same way the pass rate above is recorded.

**This number has never been measured.** No run of this eval — on this
machine or any other, as far as this checkout's history shows — has ever
produced a character-box IoU. See the next section for why.

## No fixtures on this machine yet

There is nothing in `evals/fixtures/` — that directory is empty on every
checkout, by design (see "Adding a fixture" above) — and no
`expected/*.json` file in this repo currently carries a `characters` block
either, since there is no fixture yet to hand-draw one against. This repo
checkout has never produced a real score for either the pass rate or the
character-box IoU. (Separately, the eval also requires the `ANTHROPIC_API_KEY`
*environment variable* to be set in the shell that runs Gradle — a key
present only in `local.properties` does not satisfy it, since the test reads
`System.getenv`, not the Gradle property file.)

Running the test with no environment variables set (`./gradlew
testDebugUnitTest --tests "com.storyteller.evals.VisionEval"`) exercises none
of the code above; it exits immediately on the `STORYTELLER_EVAL` assumption,
before any file I/O or network call. That is expected and is how the test
behaves in ordinary CI/local runs.

To get a real number for either measurement: follow "Adding a fixture" above
for at least a handful of pages — for the character-box IoU specifically,
include at least one graphic-novel page with a `characters` block carrying a
hand-drawn `bounds` per character — export `ANTHROPIC_API_KEY` and
`STORYTELLER_EVAL=1` in the environment, then run the command under "Running
it". Whoever runs it first should record both numbers in this file, and must
honor the stop condition above if the character-box IoU comes in under 0.5.
