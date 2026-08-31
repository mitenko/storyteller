# Bubble Crop Fixes (v4 parse schema)

## Issues Fixed

### Issue 1: Silent Clamping of Out-of-Range Coordinates ✅

**Problem:** Model returned coordinates outside 0..1 (e.g., `1.360`), which were silently clamped to 0-1 via `coerceIn()`. This collapsed boxes to zero height, which then failed `cropRect` validation and fell back to text rendering. The fallback was so graceful that the bug stayed invisible.

**Root Cause:** The comment in `PageSchema.kt` acknowledged the constraint ("0..1 bound on coordinates is stated in the instruction and clamped on the client") but there was no validation—invalid output was transformed silently instead of being flagged.

**Fix:** Modified `PageReaderImpl.BoundsDto.toDomain()` to:
- Reject bounds with any coordinate outside [0, 1]
- Return `null` for invalid boxes instead of clamping
- Allow the reader to detect invalid crops and fall back to text
- Makes model inaccuracy **visible** rather than hidden

**Code change:**
```kotlin
private fun BoundsDto.toDomain(): BoundingBox? {
    if (left < 0f || left > 1f || top < 0f || top > 1f ||
        right < 0f || right > 1f || bottom < 0f || bottom > 1f
    ) {
        return null
    }
    return BoundingBox(left, top, right, bottom)
}
```

**Tests added:**
- `rejects bounds that exceed the 0..1 range` — validates multi-coordinate rejection
- `rejects bounds with left outside range` — validates negative values
- `rejects bounds with right exceeding 1.0` — validates upper bound

---

### Issue 2: Speaker Attribution Collapsed to "Narrator" ✅

**Problem:** In parse v3, every unit returned as "Narrator" (100% of units). Earlier versions (v1-v2) correctly attributed 23-94% to actual characters. The regression happened when the `characters` array was removed from the schema.

**Root Cause:** The characters array forced the model to:
1. Identify distinct characters on the page
2. Reason about their identities  
3. Match speakers in dialogue to those identities

Removing it removed the reasoning context. Model now defaults to "Narrator" with no way to disambiguate.

**Fix:** Restored character enumeration in the prompt and schema:
- Added `characters` array back to `PAGE_SCHEMA`
- Updated `PAGE_INSTRUCTION` to request character enumeration
- Bumped `PARSE_VERSION` from 3 → 4 (new schema invalidates old cache)

**Code changes:**

1. **PageSchema.kt** — restored characters array:
```json
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
```

2. **PAGE_INSTRUCTION** — restored character enumeration request:
```
Also return characters: one entry per distinct character who speaks on this
page. Do not include the narrator.
- Set name to exactly the speaker string you used in units.
```

3. **Entities.kt** — bumped parse version:
```kotlin
const val PARSE_VERSION = 4
```

**Tests updated:**
- Changed assertion from "characters should no longer be requested" to verify characters ARE requested
- Ensures regression doesn't happen again

---

## Diagnostic Impact

The diagnostic bundle (`response.json` + `parse.json`) now captures:
- **Out-of-range coordinates**: Will now show as `null` in the parsed output (not clamped)
- **Speaker attribution**: Will now show correct character names (not collapsed to "Narrator")

This allows the `overlay.png` diagnostic to:
1. Show which boxes are valid vs. invalid at a glance
2. Verify character identification is working correctly

---

## Cache Invalidation

Old parses (v1-v3) will be cache misses under v4. The first time each page is photographed again, it will:
1. Request the vision API call (no longer cached)
2. Receive both units AND characters from the model
3. Store under v4, so it won't regress to old behavior

This is intentional and necessary—the schema changed structurally.

---

## What This Does NOT Fix

- ✅ Validates coordinate ranges (catches model hallucination)
- ✅ Restores speaker attribution reasoning
- ❌ Does not improve accuracy of bubble cropping (that's a prompt/model question)

The issue doc noted (§3): "No one has yet compared a returned box against where its bubble actually is." The overlay diagnostic will enable that measurement, which should come next.

---

## Commits

All changes in this session:
- Coordinate validation with tests
- Character enumeration restoration
- Parse version bump
- Test updates
