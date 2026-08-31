package com.storyteller.data.page

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Structured-outputs schema. Every object needs additionalProperties=false, and
 * numeric ranges are unsupported, so the 0..1 bound on coordinates is stated in
 * the instruction and clamped on the client.
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
                      "left":   { "type": "number" },
                      "top":    { "type": "number" },
                      "right":  { "type": "number" },
                      "bottom": { "type": "number" }
                    },
                    "required": ["left", "top", "right", "bottom"],
                    "additionalProperties": false
                  },
                  { "type": "null" }
                ]
              }
            },
            "required": ["speaker", "text", "bounds"],
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

val PAGE_INSTRUCTION: String = """
    This is a photograph of one page from a children's storybook or graphic novel.

    Return every speech unit on the page, in reading order. A speech unit is one
    continuous piece of dialogue or narration.

    For each unit:
    - Set speaker to the character who says it. Use "Narrator" for description or
      narration not attributed to a character. If you cannot tell who is speaking,
      use "Narrator".
    - Use the character's name exactly as it appears on the page.
    - Reproduce the text verbatim. Do not merge units, split units, translate, or
      correct spelling.
    - Set bounds to the box enclosing that unit's speech bubble or text block, as
      fractions of the image between 0 and 1, measured from the top left. Use null
      if you cannot locate it.

    Also return characters: one entry per distinct character who speaks on this
    page. Do not include the narrator.
    - Set name to exactly the speaker string you used in units.

    Ignore page numbers, running heads, publisher marks, and any text that is part
    of the artwork rather than something to be read aloud.
""".trimIndent()
