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

## No fixtures on this machine yet

There is nothing in `evals/fixtures/` and no `ANTHROPIC_API_KEY` configured
here — this repo checkout has never produced a real score. Running the test
with no environment variables set (`./gradlew testDebugUnitTest --tests
"com.storyteller.evals.VisionEval"`) exercises none of the code above; it
exits immediately on the `STORYTELLER_EVAL` assumption, before any file I/O or
network call. That is expected and is how the test behaves in ordinary CI/local
runs. To get a real number, follow "Adding a fixture" above for at least a
handful of pages, then run the command under "Running it".
