# Panel-scoped speaker identification: measured, and filed as a nice-to-have

**Status:** measured, **deferred**. Not scheduled, not blocking anything.
**Date:** 2026-09-08
**Question:** now that the panel box is reliable
([`2026-08-31-bubble-box-accuracy-measured.md`](2026-08-31-bubble-box-accuracy-measured.md)
§19, mean IoU 0.949), can a second vision pass over a single *panel* identify the
speaker better than the one whole-page call does? A panel is a much smaller
region than the page, which is the whole argument for trying.
**Verdict:** **yes, it works — and it is still a nice-to-have.** A one-paragraph
prompt fix, with no extra call and no architecture, captures more of the available
gain than panel-scoping does. Panel-scoping's distinctive value is the speaker's
**location**, not their name.

---

## 1. Why this was worth asking

Panels are solved and balloons are not, so the app already crops a panel it trusts.
That makes a panel crop free to produce. Speaker attribution, meanwhile, had never
been measured at all — §19.2 noted only in passing that it "drifted between calls".

Pulling the speakers out of the committed diagnostic bundles showed the drift is
not a footnote. The **same page**, read five times:

| bundle | speakers returned for the same eight lines |
|---|---|
| `…074358`, `…961215` | `Character` ×5, Duncan, Aly |
| `…845946` | `Unknown Character` ×5, Duncan, Aly |
| `…934899` | Girl, Man, Duncan, Old Man, Aly, Boy, Aly |
| `…251857` | Man, Man, Duncan, Man, Man, Narrator, Narrator, Boy, Boy, Fox |

Three failure modes, all live, and each one reaches the child differently:

1. **Vocative capture.** "BUY ME MORE TIME, **DUNCAN**." is attributed to *Duncan*,
   and "**ALY**, BEFORE WE GO ANY FURTHER…" to *Aly*. The model is reading the name
   being **addressed** and returning it as the speaker.
2. **Generic placeholders** — `Character`, `Man`, `Boy`. Two characters handed the
   same string get the same voice.
3. **Drift between calls**, so the voice can change on a re-read of one page.

The prompt itself invites failure 1. It says *"Use the character's name exactly as
it appears on the page"* — and on these pages the only printed names **are**
vocatives.

## 2. What was measured

Three arms, a ladder so each rung isolates one variable:

- **control** — the app's current whole-page prompt and schema, read out of
  `PageSchema.kt` so the probe cannot drift from what the app sends.
- **guarded** — identical, plus a paragraph saying the name a line addresses is not
  the speaker, and that a described speaker must be distinct from everyone else on
  the page. Isolates prompt wording.
- **panel** — the same guarded wording, but sent one panel at a time as a cropped
  image with only that panel's lines, asking for the speaker **and a box around
  them**. Isolates region size. Deliberately given no page-level roster, so the
  cross-panel hazard would show up if it were real.

Three pages × three arms × three repeats: 27 page-reads, 62 vision calls, $0.91.
Repeats matter because §19.2's drift means one run of each arm measures noise.

Pages: `page-1788289251857` (hard — six characters, none named in narration, both
printed names vocatives), `page-1788294930134` (two characters, vocatives, the
dense page where OCR read zero words), `page-1788295071078` (two characters, no
vocatives — a regression check, because the control arm already reads it 7/7).

Ground truth is hand-labelled by reading the pages, with speaker boxes read off a
100px grid — the same method and the same standard as
`scripts/fixtures/panels-page-1788294930134.json`. Lines the art does not settle
are marked ambiguous and excluded from accuracy, but a vocative trap is still
scored on them, because "the addressee is not the speaker" holds regardless of who
the speaker turns out to be.

## 3. The numbers

Like-for-like: character lines only, ambiguous lines excluded, and any line an arm
failed to return excluded from all arms. 48 judgements each.

| arm | correct | wrong | generic | vocative errors | name collisions | unstable lines |
|---|---|---|---|---|---|---|
| control | 33 (**69%**) | 5 | **8** | 2/15 | **10** | 10 |
| guarded | 39 (**81%**) | 7 | 0 | **0/15** | 6 | 19 |
| panel | 37 (**77%**) | 7 | 0 | **0/15** | **3** | 19 |

- **The prompt fix alone closes the vocative bug** — 2/15 to 0/15 — and takes
  accuracy from 69% to 81% for one paragraph and no extra call.
- **Panel-scoping halves collisions again**, 6 to 3, and this is where the region
  argument visibly pays: on the crowded top panel it returns "the girl with dark
  hair" and "the mustached man being grabbed" where the whole-page call could only
  manage `Man` for both — two characters, one voice.
- **Panel-scoping does not improve accuracy.** It is 4 points below the prompt fix.

## 4. Where panel-scoping loses: cross-panel context

The hazard predicted before running is real, and it is the reason this is filed as
a nice-to-have rather than scheduled.

- It cannot name the fox in panel 3. Four tiny figures stand in a wide street shot
  with an ambiguous tail; the identifying close-up is in **panel 5**, which a
  panel-local call never sees. It answers "the donkey-eared character" or
  "off-panel speaker".
- Worse, it calls visible speakers off-panel. For panel 4 it answered "an unseen
  companion off-panel". Checked against the art at magnification: **both balloon
  tails point at the boy, whose mouth is open.** The control arm gets this right.
- It regressed the page that already worked. On `page-1788295071078`, which control
  reads 7/7, one panel run answered "An unseen speaker off-panel" for a line the
  bearded man plainly speaks.

**Neither arm fixes instability, and the panel arm makes it worse** — unstable
lines doubled, 10 to 19, because free-form descriptions vary their wording between
runs ("the bearded man" / "the bearded old man"). For the voice map that is as
damaging as a collision: a different string is a different voice.

Both the drift and the cross-panel loss have the same cure, which is why neither is
worth chasing separately: **resolve one page-level character roster first, then
attribute against that fixed list.** This measurement is evidence for that design
rather than speculation about it.

## 5. The location half works better than the naming half

On the hard page, across three repeats:

| measure | result |
|---|---|
| speaker boxes returned | 20 |
| centre lands on the correct character | **18** |
| mean IoU vs hand-drawn character box | 0.616 |
| genuinely off-panel speaker, correctly returned `null` | 1 of 3 |
| …invented a box instead | **2 of 3** |

Loose boxes, right people. That is enough to highlight who is talking and not
enough for a tight portrait crop. The reject-don't-invent property this repo relies
on elsewhere does **not** hold here: on the one speaker who is genuinely not drawn
in the panel, it made up a box two times out of three.

This is the half a whole-page call cannot supply at all, and it is the honest
reason to keep the idea alive.

## 6. Cost, which inverts the intuition

Measured, at Sonnet 5 prices:

| arm | calls per page | cost per page |
|---|---|---|
| control (today) | 1 | **$0.021** |
| guarded | 1 | **$0.059** |
| panel second pass | +5 | **+$0.028** |

The cheap-looking prompt fix is the **expensive** one at runtime. The control
prompt does *zero* thinking; the guard intermittently triggers 3,600–7,230 thinking
tokens, roughly tripling cost. Panel calls are small and do not think at all —
125 output tokens on a measured call.

Note also that `PROJECT.md`'s "$0.003 per page for the vision call" is stale by 7×.
It predates Sonnet 5 at the current resolution; the measured figure is $0.021.

## 7. Two findings here that are NOT nice-to-haves

Both are independent of whether this feature is ever built.

**A speaker guard needs the token budget re-checked.** The probe ran at
`max_tokens: 3000`, overran it, ran again at 8000, overran that too — thinking
alone reached **7,230 tokens** on one guarded call — and needed 32000 to clear.

Two corrections, both in the app's favour, because the probe's 3000 was not the
app's number:

- **The app is at 8192.** The 3000 came from `scripts/measure_panels.py`, which
  hardcodes it, and the probe inherited it. `PageReaderImpl.MAX_TOKENS` has been
  8192 since the Sonnet 5 migration, and its KDoc already documents this exact
  failure, found on a device page where all 2048 tokens of the earlier budget went
  on thinking.
- **It does not fail silently.** `textBlock()` throws a `SerializationException`
  naming `stop_reason`, which the pipeline maps to "came back garbled". A truncated
  answer fails to parse rather than reaching a child as a plausible short page.

What survives is narrower and still worth acting on. 7,230 thinking tokens fits
inside 8192, but the answer must fit in what is left, and a measured ten-unit
answer is 710–780 tokens. That is thin headroom on a page busier than the ones
tested. **Sizing the budget belongs in the same change as the guard** — and the
harness's own 3000 should be raised, so it stops measuring a limit the app does
not have.

**A speaker guard must explicitly preserve sound effects.** The guard drafted here
dropped every `PAF!` and `FOOMP!` — 14 units across the run — because it pushed the
model to find a real speaker and it dropped the lines that had none, despite the
existing instruction to use `Narrator` for unspoken text. A dropped line is worse
than a wrongly-voiced one: the child never hears it.

## 8. Why this is a nice-to-have and not a plan

The child-facing symptom — the same character read in a different voice — is caused
mostly by vocative capture, placeholders and drift. Two of those three are fixed by
editing a prompt, and the third needs a roster, not a smaller region. Panel-scoping
costs five extra calls per page and a second failure surface, improves nothing
measurable about the *name*, and regresses a page that currently works.

What it uniquely buys is **where the speaker is standing**, and nothing in the app
consumes that today. The reader shows the panel; it does not point at anyone in it.

So this sits in the deferred list until something wants a speaker's position —
highlighting the talker as the line is read, or reviving the character portrait
that §"What got deleted along the way" records as removed.

**What would promote it:**

- A decision to show *who* is speaking inside the panel, not just the panel.
- A page-level roster landing first, so the panel pass has names to attribute
  against instead of inventing a description per panel.
- The reject-don't-invent gap closing: 2 invented boxes out of 3 off-panel cases is
  not safe to render.

## 9. What is not established

Three pages, one book, one art style, three repeats. The same narrow base as every
other measurement in this investigation.

The labels are mine. Section 4's panel-4 finding was verified against the art at
magnification, but the fox attribution in panel 3 rests on **dialogue logic** — the
speaker who asks Aly for directions is the one who answers the boy's objection two
panels later — not on a legible balloon tail. A second labeller could read that
differently. It is marked ambiguous in the truth file and excluded from the accuracy
column for exactly that reason.

The guarded arm's numbers carry a defect of my own making: its sound-effect drop
(§7) removed 14 lines, so its totals are computed on a like-for-like subset rather
than the full transcript. A guard written to preserve sound effects might score
differently, in either direction.

The probe scripts were throwaway and are not in the repo. Reproducing this means
rewriting them; the diagnostic bundles and the hand labels are what persist.

## 10. Review and solution directions

This measurement supports **not adding a per-panel attribution pass as the next
speaker fix**. The immediate child-facing problem is mostly vocative capture,
generic placeholders, and drift. Panel scoping improves neither attribution
accuracy nor stability, costs five extra calls per page, and introduces a real
cross-panel failure mode. Its distinctive value is spatial: it can say where a
visible speaker is standing.

### Immediate: strengthen the existing single call

The prompt guard is the cheapest first step, but it must be a complete transcript
invariant rather than only an attribution warning:

- Preserve every readable unit, including sound effects such as `PAF!` and
  `FOOMP!`.
- Use `Narrator` for sound effects, captions, and text with no identifiable
  speaker.
- Never omit a unit because its speaker is unknown.
- Treat a vocative as evidence about the addressee, not the speaker.
- Do not invent a character name from appearance alone unless the page establishes
  it.
- Keep the same speaker label stable throughout one page.

The guard should be measured again with sound-effect preservation enabled. A
dropped line is worse than a wrongly voiced line because it removes content from
the child's reading experience.

### Medium-term: return a page-level character roster

The likely fix for generic placeholders and drift is to resolve a roster before
assigning voices. The roster should use stable internal identifiers rather than
free-form speaker strings:

```json
{
  "characters": [
    {
      "id": "character_1",
      "name": "Duncan",
      "description": "..."
    }
  ],
  "units": [
    {
      "speakerId": "character_1",
      "text": "..."
    }
  ]
}
```

The identifier should be the voice-map key. The display name is user-facing and
diagnostic only. A roster entry can later carry aliases, a visual description,
an optional representative crop, and confidence without changing the identity
contract. This prevents `Man`, `the man`, and `mustached man` from silently
becoming three voices.

This should begin as a single-call experiment: ask the model to return the roster
and attribute every unit against it in the same response. If that cannot hold
identity consistently, split it into two calls — roster discovery first,
attribution against the fixed roster second. The two-call design is clearer but
adds latency, cost, and another failure boundary.

The roster also needs a defined scope. A page-level roster solves collisions
within one page, but it does not automatically preserve a character's voice
across pages. Book-level identity reconciliation should therefore be a later,
confidence-gated step, never an implicit merge based on a similar description.
Uncertain merges should remain separate or ask for confirmation rather than
silently changing an established voice.

### Future: panel-scoped location only

If the product later highlights the talker or revives character portraits, the
panel pass should consume the fixed page roster and return location, not invent
names:

```json
{
  "speakerId": "character_1",
  "speakerBox": null,
  "isVisible": false,
  "confidence": 0.0
}
```

The UI should render a box only when the speaker is visibly present and the
location passes a conservative confidence gate. A free-form box must not be
rendered when the speaker is off-panel; the measured two-out-of-three invented
box rate is not safe for automatic highlighting. Existing OCR localization can
continue to provide reliable text and bubble extents, but it is not by itself a
replacement for visual speaker identification.

### Evaluation additions

The next attribution experiment should report more than exact string accuracy:

- exact speaker-string accuracy;
- normalized alias or roster-ID accuracy;
- vocative errors;
- omitted units, especially sound effects;
- identity consistency across repeated reads;
- voice-assignment consistency across adjacent pages;
- false visible-speaker boxes for genuinely off-panel speech.

The current cost figures should remain tied to the exact model, image size,
prompt/schema, output tokens, and thinking tokens. The older `$0.003 per page`
figures in historical project documents should be labeled as historical rather
than reused for current planning.

### Promotion order

1. Keep the single-call architecture and add the sound-effect-preserving
   vocative guard.
2. Prototype stable page-level roster IDs and measure repeated-read consistency.
3. Add book-level identity reconciliation only after page-level IDs are reliable.
4. Add panel-scoped `speakerId` and location only when a UI feature consumes the
   position and the reject-don't-invent gate is demonstrably safe.

---

## 21. The prompt fix shipped, and measured better than the spike predicted

2026-09-10. The guard is now in `pageInstruction` (M1). Re-measured on the same
three bundles, same hand labels, three repeats, so the numbers below are directly
comparable to §3.

| metric | old prompt | spike's draft guard | shipped |
|---|---|---|---|
| accuracy | 69% | 81% | **91%** |
| generic placeholders | 8 | 0 | **0** |
| dropped sound effects | 0 | **14** | **0** |
| genuine vocative errors | 2/15 | 0/15 | **1/15** |
| name collisions | 10 | 6 | **4** |

Better than either arm the spike tested. The Duncan and Aly traps are gone
outright: `BUY ME MORE TIME, DUNCAN.` returns "the bearded old man" on all three
repeats, and "Aly" never appears as a speaker.

### 21.1 Two things §7 got wrong

**The cost warning does not apply to what shipped.** §6 recorded the guard tripling
cost by driving 3,600-7,230 thinking tokens. The shipped wording spends **zero**
thinking tokens on all nine calls, with a maximum of 1095 output against a budget
of 8192 — 7,097 spare. Roughly $0.026 a page, against $0.021 before. Two prompts
aimed at the same behaviour had entirely different cost profiles, which is worth
remembering before quoting a token figure for "a guard" in the abstract.

**So `MAX_TOKENS` needed no raise.** §7 called for re-sizing it alongside the
guard. Measured, there was nothing to re-size.

### 21.2 A clause that made the primary bug five times worse

The run left one visible defect: `the pink rabbit (Cogsley)` — the right speaker
with the addressee annotated after it. Harmless as attribution and harmful as data,
because that string is a voice-map key, so one rabbit becomes two rows and two
voices.

A clause was added forbidding appended notes. It backfired, measured on the same
three pages:

| | without the clause | with it |
|---|---|---|
| accuracy | **91%** | 88% |
| genuine vocative errors | **1/15** | **5/15** |
| collisions | **4** | 7 |

Forbidding the parenthetical made the model drop the **description** and keep the
**annotation** — "the pink rabbit (Cogsley)" collapsed to a bare "Cogsley", which
is precisely the error the whole paragraph exists to prevent. Reverted.

The lesson is narrow and worth stating: a prompt clause aimed at a small defect
moved the primary metric five times in the wrong direction, and no amount of
reading the wording would have predicted it. Prompt changes here are measured or
they are guesses.

The parenthetical remains, twice in nine calls. It is a **stability** defect rather
than an attribution one, and the roster in `docs/BACKLOG.md` M2 is the right fix:
one resolved identity per character, so the spelling of the label stops being what
the voice map is keyed on.

### 21.3 The measurement had a bug too

The first reading of this run reported 3/15 vocative errors. Two of those were the
scorer counting `the pink rabbit (Cogsley)` as a claim that Cogsley speaks, when it
names the rabbit and merely mentions the addressee. `claims_speaker_is` now strips
parenthesised text before matching, and both runs above were re-scored under the
corrected metric before being compared.

Worth recording because §13.3 made the same kind of error in the other direction:
the instrument is as capable of being wrong as the thing it measures, and a
comparison across a metric change is not a comparison.

### 21.4 What is still open

Instability is untouched: 12 lines of 69 return a different speaker string between
repeats — "the boy with brown hair" one run, "the boy in the green shirt" the next.
Same character, two keys, two voices. Prompt wording cannot fix it, and M2 is where
it goes.
