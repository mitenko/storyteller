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
