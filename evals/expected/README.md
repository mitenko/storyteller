Expected results for the vision eval, one JSON file per fixture image, named after
the image. These are committed; the images in `../fixtures/` are not.

    { "speakers": ["Narrator", "Wolf"], "minUnits": 3 }

`speakers` is the exact set of distinct speaker names the harness should see
across the page (order does not matter, and it deduplicates). `minUnits` is a
floor on how many speech units the model should return — use a floor rather
than an exact count because the model may legitimately split one long line of
dialogue or narration into more than one unit.

For a page with no text at all, use:

    { "speakers": [], "minUnits": 0 }

A fixture image with no matching JSON file here is skipped by the harness
rather than counted as a failure, so an incomplete `evals/fixtures/` directory
degrades gracefully instead of tanking the pass rate.

## The `bubbles` block (speech-bubble box accuracy)

Any fixture may also carry a `bubbles` array, scoring how well the model's
returned bounding box for each speech unit matches a hand-drawn one:

    {
      "speakers": ["Narrator", "Bear"],
      "minUnits": 3,
      "bubbles": [
        { "index": 0, "bounds": { "left": 0.08, "top": 0.11, "right": 0.52, "bottom": 0.29 } }
      ]
    }

Each entry:

- `index` — the reading-order index of the `SpeechUnit` this box belongs to
  (0-based, matching `SpeechUnit.index`). Matching by index rather than by
  speaker name is deliberate: the model's units are not guaranteed to name a
  speaker uniquely, so name-based matching would be ambiguous here.
- `bounds` — required. A bubble box IS the content the reader crops and
  shows, so every hand-drawn `bubbles` entry must carry one: draw it tightly
  around the speech bubble (or the equivalent hand-lettered text region in a
  book with no bubble outline) in the same normalized 0..1 coordinate space
  as the model's own `bounds`.

For each fixture, the harness reports how many expected bubbles the model
also returned a box for and the mean intersection-over-union (IoU) of those.
See `evals/README.md` for how that number is aggregated across a run and the
0.5 stop condition it is measured against — that condition matters
particularly here because a bubble crop has no fallback content on screen if
it is wrong (the reader falls back to rendered text instead of the photo).
