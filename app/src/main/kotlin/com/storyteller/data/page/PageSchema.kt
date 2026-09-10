package com.storyteller.data.page

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Structured-outputs schema. Every object needs additionalProperties=false, and
 * numeric ranges are unsupported, so the bound on coordinates is stated in the
 * instruction and enforced on the client — which REJECTS an out-of-image box
 * rather than clamping it, so a model that failed to locate a bubble is visible
 * instead of being collapsed into something plausible-looking.
 *
 * Coordinates are absolute pixels of the uploaded image and stay "number", not
 * "integer": the vendor's own worked example returns a fractional pixel (653.5),
 * so integers would make a valid response unparseable.
 */
val PAGE_SCHEMA: JsonObject = Json.parseToJsonElement(
    """
    {
      "type": "object",
      "properties": {
        "units": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "speaker": { "type": "string" },
              "text": { "type": "string" },
              "bounds": {
                "anyOf": [
                  {
                    "type": "object",
                    "properties": {
                      "x1": { "type": "number" },
                      "y1": { "type": "number" },
                      "x2": { "type": "number" },
                      "y2": { "type": "number" }
                    },
                    "required": ["x1", "y1", "x2", "y2"],
                    "additionalProperties": false
                  },
                  { "type": "null" }
                ]
              },
              "panel": {
                "anyOf": [
                  {
                    "type": "object",
                    "properties": {
                      "x1": { "type": "number" },
                      "y1": { "type": "number" },
                      "x2": { "type": "number" },
                      "y2": { "type": "number" }
                    },
                    "required": ["x1", "y1", "x2", "y2"],
                    "additionalProperties": false
                  },
                  { "type": "null" }
                ]
              }
            },
            "required": ["speaker", "text", "bounds", "panel"],
            "additionalProperties": false
          }
        },
        "characters": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "name": { "type": "string" }
            },
            "required": ["name"],
            "additionalProperties": false
          }
        }
      },
      "required": ["units", "characters"],
      "additionalProperties": false
    }
    """.trimIndent(),
).jsonObject

/**
 * Absolute pixel coordinates, not fractions.
 *
 * Anthropic's coordinate guidance is explicit that Claude "works best with
 * absolute pixel coordinates" and "does not work well when you ask for normalized
 * coordinates". This app asked for fractions between 0 and 1 through parse
 * versions 1-4, and every bubble box it measured was wrong — see
 * docs/issues/2026-08-31-bubble-box-accuracy-measured.md section 4.
 *
 * [width] and [height] are the dimensions of the image actually being sent, which
 * Downscale has already sized to what Claude sees. So the pixels Claude reports
 * are pixels of the image we hold, and normalising is a plain division.
 */
fun pageInstruction(width: Int, height: Int): String = """
    This is a photograph of one page from a children's storybook or graphic novel.
    The image is exactly $width pixels wide and $height pixels tall.

    Return every speech unit on the page, in reading order. A speech unit is one
    continuous piece of dialogue or narration.

    For each unit:
    - Set speaker to the character who says it. Use "Narrator" for description or
      narration not attributed to a character, and for sound effects. If you cannot
      tell who is speaking, use "Narrator".
    - Work out WHO is speaking from the balloon's tail - the pointer joining the
      balloon to its speaker - and from who is drawn mid-speech. The name a line
      ADDRESSES is not the speaker: in "Buy me more time, Duncan.", the speaker is
      the person talking TO Duncan, never Duncan. On these pages the only printed
      names are often the ones being addressed, so a name inside the text is
      evidence about who is listening, not about who is talking.
    - Use a character's own name when the page gives it. When it does not, describe
      them so they cannot be confused with anyone else on the page - "the bearded
      old man", "the fox", "the boy in the green shirt". Never use an
      interchangeable label such as "Character", "Unknown Character", "Man" or
      "Person": two different characters must never receive the same speaker
      string, and the same character should read the same way on every line.
    - Return sound effects and lettering that is spoken aloud - PAF!, FOOMP! - as
      units with speaker "Narrator". Do not drop a line because no character speaks
      it. A line that is missing is never read to the child at all.
    - Reproduce the text verbatim. Do not merge units, split units, translate, or
      correct spelling.
    - Set bounds to the box enclosing that unit's speech bubble, as absolute pixel
      coordinates in this $width x $height image: x1 and y1 are the top-left corner,
      x2 and y2 the bottom-right, measured from the top-left of the image. Use null
      if you cannot locate it.
    - Set panel to the comic panel that contains that balloon, in the same
      $width x $height pixel coordinates. A panel is the framed picture the balloon
      sits in, bounded by the gutters or page edges around it. Include the artwork,
      not just the balloon. Two units in the same panel must get the same panel box.
      Use null if the page is a single full-bleed picture with no panel divisions,
      or if you cannot tell.

    Also return characters: one entry per distinct character who speaks on this
    page. Do not include the narrator.
    - Set name to exactly the speaker string you used in units.

    Ignore page numbers, running heads, publisher marks, and any text that is part
    of the artwork rather than something to be read aloud.
""".trimIndent()
