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

## The `characters` block (character-box accuracy)

Any fixture may also carry a `characters` array, scoring how well the model's
returned bounding box for each character matches a hand-drawn one:

    {
      "speakers": ["Narrator", "Bear"],
      "minUnits": 3,
      "characters": [
        { "name": "Bear", "emojiExpected": true,
          "bounds": { "left": 0.10, "top": 0.12, "right": 0.34, "bottom": 0.55 } }
      ]
    }

Each entry:

- `name` — must match the `name` the model returns for that character
  (matched trimmed and case-insensitively; matching is by name, not list
  position).
- `emojiExpected` — optional, defaults to `false`. Documents whether this
  character is expected to carry an emoji fallback; the harness does not yet
  assert on it, it is recorded for a human reading the fixture set.
- `bounds` — optional. Omit it for a prose picture book character, where
  there is no crop and so nothing to hand-draw a box against — the harness
  still counts whether the model returned that character and whether it
  carried a box, it just has nothing to compare the box to. Include it for a
  graphic-novel character: draw the box, in the same normalized 0..1
  coordinate space as the model's own `bounds` (`left`/`top`/`right`/`bottom`
  as fractions of image width/height), tightly around the character AS DRAWN
  on the page — not around the speech bubble.

For each fixture, the harness reports how many expected characters the model
returned at all, how many of those carried a returned box, and the mean
intersection-over-union (IoU) of the ones where both a hand-drawn and a
returned box exist to compare. See `evals/README.md` for how that number is
aggregated across a run and the 0.5 stop condition it is measured against.

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
  (0-based, matching `SpeechUnit.index`, not a character name — the model's
  units are not guaranteed to name a speaker uniquely, so matching by name
  the way `characters` does would be ambiguous here).
- `bounds` — required (unlike `characters.bounds`, which is optional for a
  prose picture book with no crop to draw). A bubble box IS the content the
  reader crops and shows, so every hand-drawn `bubbles` entry must carry one:
  draw it tightly around the speech bubble (or the equivalent hand-lettered
  text region in a book with no bubble outline) in the same normalized 0..1
  coordinate space as the model's own `bounds`.

For each fixture, the harness reports how many expected bubbles the model
also returned a box for and the mean intersection-over-union (IoU) of those.
See `evals/README.md` for how that number is aggregated across a run and the
0.5 stop condition it is measured against — that stop condition is stricter
in consequence here than for `characters`, since a bubble crop has no emoji
fallback the way a character badge does.
