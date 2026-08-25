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

Each fixture is downscaled to the same 1568px-long-edge JPEG the app itself
sends (`downscaleToPageImage`) before it is uploaded. This is deliberate: the
point of the eval is to predict what production sees, so it must feed the
model the same bytes production feeds it, not a full-resolution photo that
would flatter the score.

## Adding a fixture

1. Photograph a real page the way a child would hold the phone. Put it in
   `evals/fixtures/` — that directory is gitignored, since the pages are
   copyrighted and large. Any of `.jpg`, `.jpeg`, `.png` is fine; the harness
   downscales and re-encodes it before sending.
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

The `boxed=n/m` column is the second thing this harness measures: whether the
returned bounding boxes are usable. Iteration 1 ignores them, but iteration 2
needs them for tappable speech bubbles. If boxes come back consistently null or
land on the wrong bubbles, that is the signal to reintroduce ML Kit for geometry
before committing to that feature.

## No fixtures on this machine yet

There is nothing in `evals/fixtures/` and no `ANTHROPIC_API_KEY` configured
here — this repo checkout has never produced a real score. Running the test
with no environment variables set (`./gradlew testDebugUnitTest --tests
"com.storyteller.evals.VisionEval"`) exercises none of the code above; it
exits immediately on the `STORYTELLER_EVAL` assumption, before any file I/O or
network call. That is expected and is how the test behaves in ordinary CI/local
runs. To get a real number, follow "Adding a fixture" above for at least a
handful of pages, then run the command under "Running it".
