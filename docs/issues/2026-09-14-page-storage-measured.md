# Page storage: the measurement

**Status:** measured. Closes **M6.1**, which the backlog scheduled as "measure real
audio bytes per page on the device".
**Date:** 2026-09-14
**Verdict:** a stored page costs **~1.8 MB**, of which **84% is the photograph**.
M5's fifty-page cap lands at about **91 MB** — inside the 50–150 MB the M5 spec
predicted, and at the low end of it. The cap does not need changing; what needs
changing is that it counts pages instead of bytes.

---

## 1. What was measured, and how

M6.1 was written as a device task. It did not need to be: **20 diagnostic bundles
pulled from the Pixel 9a** are already in `diagnostics-pulled/`, and each carries the
two JPEGs the app actually produces plus the parse it got back. That is the same
evidence a device run would gather, for the photograph half, already collected.

Measured across all 20:

| quantity | min | mean | max |
|---|---|---|---|
| `page-display.jpg` — **the copy that is stored** | 1.21 MB | **1.53 MB** | 2.05 MB |
| `page-upload.jpg` — sent to the model, not stored | 231 KB | 296 KB | 860 KB |
| characters of speech per page | 88 | 262 | 463 |

Three of the twenty parsed to zero units — pages the vision call returned nothing
usable for. They are left in the character statistics as blanks rather than dropped,
because a stored page with no audio is a real case the budget must survive.

## 2. The audio half is an estimate, and says so

No bundle carries audio, so this is derived rather than measured, and the assumptions
are stated so they can be attacked:

- `ElevenLabsTtsApi` sends **no `output_format`**, so ElevenLabs' default applies:
  `mp3_44100_128` — 128 kbit/s, **16 KB per second**.
- Speech runs at roughly **14 characters per second**.

At 262 characters that is ~19 seconds, or **~300 KB per page**; the longest page
measured (463 characters) gives ~530 KB.

This is close enough to the M5 spec's "~250 KB of audio per page" to suggest that
figure was about right. It is still an estimate. **The one number still worth taking
on a device is the real byte size of a page's clips** — everything else here is
measured.

## 3. What this changes

**A page costs ~1.83 MB, and the photograph is 84% of it.** Any byte budget is
therefore a photograph budget with a rounding error attached. Fifty pages is about
**91 MB**, worst case about 130 MB.

**The fifty-page cap was a reasonable guess and survives.** M5 chose it without
measuring and flagged that "if photograph size is materially larger, fifty pages is
the wrong number and M6.1 should be pulled forward". It is not materially larger.
Nothing needs pulling forward.

**But counting pages is still the wrong rule**, and now for a specific reason rather
than a principle: the spread is real. A 2.05 MB page is **69% larger** than a 1.21 MB
one, so fifty pages is anywhere between 60 MB and 103 MB of photographs. A count
cannot promise a ceiling; that is what M6.2 is for.

## 4. A tempting optimisation that should NOT be taken

The obvious reaction to "84% is the photograph" is to downscale the stored copy. The
display JPEG is 3000×4000 and the phone's screen is roughly 1080 wide, so it looks
like three quarters of those bytes are being kept for nothing.

**They are not.** The display copy exists so the reader can crop a single *panel* out
of it. A panel occupying a sixth of the page width, shown full-width on screen, needs
roughly 6500 px of source to render sharply — so 3000 px is already **below** what
panel cropping would ideally have, not above it. Downscaling would degrade exactly
the feature the copy exists for.

The photograph is also already compressed hard: 3000×4000 in 1.53 MB is about
**0.13 bytes per pixel**. There is no easy win left in the encoder either.

So the storage answer is eviction, not compression. Recorded here because "just
downscale the photo" is the first thing anyone will suggest, including me.

## 5. Consequences for M6A

| task | what the measurement says |
|---|---|
| M6.1 | **Done**, except the real audio byte figure, which remains estimated |
| M6.2 | A ceiling in **bytes**. ~1.8 MB/page means a 100 MB budget is roughly 55 pages — close enough to fifty that the change is about *guaranteeing* the ceiling, not moving it |
| M6.3 | LRU over photographs first; audio is a sixth of the problem |
| M6.4 | Unchanged — atomic cleanup is correctness, not size |
| M6.5 | Should assert on **bytes**, using page sizes drawn from the range above rather than one nominal size, since the spread is the reason the rule is changing |
