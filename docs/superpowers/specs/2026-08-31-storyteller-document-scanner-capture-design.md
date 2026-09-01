# Storyteller — Document-Scanner Capture

**Date:** 2026-08-31
**Status:** Approved design. Implementation plan not yet written.
**Replaces:** the CameraX capture path in `2026-08-24-storyteller-compose-mvvm-design.md`.
**Evidence base:** [`docs/issues/2026-08-31-bubble-box-accuracy-measured.md`](../../issues/2026-08-31-bubble-box-accuracy-measured.md)

---

## 1. Overview

Capture stops being a CameraX preview with a shutter button and becomes the ML
Kit Document Scanner. The scanner finds the page in the frame, corrects its
perspective, cleans fingers and stains off it, and hands back a rectified JPEG
of the page alone.

Everything downstream of `PageImage` is untouched. That claim holds only if URI
reading, scanner-result validation and `downscaleToPageImage` all stay upstream
of `CaptureUiState.Captured`; §6 places them there deliberately.

## 2. Why

The photograph the vision call currently receives is mostly not the page. In the
measured bundle the book page spanned x 0.18–0.93, y 0.09–0.96 of the frame —
roughly a third of the image was desk, background and the facing page, complete
with its own artwork and lettering.

Two cheaper fixes were tried and rejected **on measurement, not on taste**:

- A brightness-based page detector returns the whole frame. The page margin, the
  gutters and the lit desk form one connected bright region; `RETR_EXTERNAL`
  discards the balloons as interior holes, and tuning the thresholds to the
  balloons returns the panels instead.
- A conservative gradient-energy crop keeps **86–91%** of the frame — a 1.03×
  linear gain, which is nothing. The facing page at the left edge carries as much
  edge energy as the target page.

Automatic page detection that actually works needs real quadrilateral detection.
Rather than own that, use Google's.

## 3. What this does NOT fix

Stated plainly so no one later reads this spec as a bug fix.

The bounding boxes will still be wrong. Mean IoU against OCR ground truth is
**0.007**, with five of six boxes having zero overlap with their own text, and
the error is structural: the model's output is a uniform ~1.43× expansion of
reality (fit slope 0.70 on both axes, y at R² 0.984). That is a model with no
grounding head composing a plausible layout in reading order. A cleaner input
does not give it one.

What this change buys:

- The page goes from ~65% of frame area to ~100%, about **1.24× linear
  resolution** on the lettering after the 1568 px downscale.
- The facing page and desk stop being available as distractors.
- Perspective is corrected, so the page is square rather than keystoned.
- It is the right substrate for the OCR-localisation split
  (`2026-08-31-bubble-box-accuracy-measured.md` §6), which is the actual fix.

Better transcription odds, and a foundation. Not a fix for the crops.

## 4. Data flow

```
CaptureScreen ("Read a page" button)
  -> PageScanner.startScanIntent()            (Task<IntentSender>, can fail)
  -> StartIntentSenderForResult
  -> GmsDocumentScanningResult.pages[0].imageUri
  -> PageBytesReader reads it IN the result callback   (see below)
  -> CaptureViewModel.onScanned(jpeg)
  -> downscaleToPageImage(jpeg, rotationDegrees = 0)   (see §7)
  -> CaptureUiState.Captured(PageImage)
  -> existing confirm screen
  -> pipeline.start(image)
```

`ReadingPipeline`, `PageReaderImpl`, `BubbleCrop`, `DiagnosticWriter` and the
whole `domain` layer are unchanged. `PageImage` keeps its current shape: `bytes`
is the 1568 px upload copy, `displayBytes` the full-resolution copy crops come
from, and both now describe the same rectified page, so the normalised mapping
`BubbleCrop` relies on stays sound.

**URI ownership.** The scanner's result URI is a transient grant to this app for
this result. It must be read to bytes **inside the activity-result callback**,
while the grant is live — never stored in state, held across configuration
change, or read later. Everything after `onScanned` deals in bytes only.

## 5. Scanner configuration

`SCANNER_MODE_FULL`, page limit 1, `RESULT_FORMAT_JPEG`, gallery import off.

`SCANNER_MODE_FULL` is chosen over `BASE` for two reasons specific to this app:

- **Automatic capture.** It fires when the page is square in frame, so a child
  holds the phone over the book and it shoots itself. That is closer to the
  project's founding premise than a manual shutter ever was.
- **ML image cleaning** erases fingers and stains. A child holding a book open
  has thumbs on the page.

Gallery import is off: the app reads a book in front of the child, and the extra
button is a place to get lost.

**One confirm tap remains and cannot be removed.** The viewfinder and review
screens are a fixed part of the SDK flow; there is no documented option to skip
the review, run headless, or return a result without user confirmation. The whole
builder surface is `setScannerMode`, `setPageLimit`, `setResultFormats` and
`setGalleryImportAllowed`. This was verified against the API reference rather
than assumed.

That tap is not purely a cost: it is the natural place for an adult to check the
right page was caught before a vision call is spent on it.

## 6. Components

| File | Change |
|---|---|
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | add `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`; drop the three `androidx.camera` entries |
| `ui/capture/PageScanner.kt` | **new.** Builds the options, returns the start intent, and converts a raw `ActivityResult` into a sealed `ScanOutcome`. The only file importing `com.google.mlkit.vision.documentscanner` |
| `ui/capture/PageBytesReader.kt` | **new.** A one-method seam, `read(uri): ByteArray`, backed by `ContentResolver`. Injectable so failure modes are testable without Play Services |
| `ui/capture/CaptureScreen.kt` | collapses from ~307 lines to a launch button plus the existing confirm UI. Permission-request UI removed. Launches the scanner and renders state — it contains no URI or byte-reading logic |
| `ui/capture/CaptureUiState.kt` | `PermissionRequired` → `Idle`; add `Failed(reason)`; `Captured` unchanged |
| `ui/capture/CaptureViewModel.kt` | `onPermissionResult` deleted; `onCaptured(jpeg, rotation)` → `onScanned(jpeg)`; add `onScanFailed(reason)`, `onScanCancelled` |
| `ui/capture/Downscale.kt` | **unchanged.** Called with `rotationDegrees = 0`; the rotation path stays for `displayBytes` and for the tests |
| `AndroidManifest.xml` | remove `android.permission.CAMERA` — see §7 |

Two seams, `PageScanner` and `PageBytesReader`, exist so that the composable and
the view model stay testable without Play Services and without a real
`ContentResolver`.

## 7. Three facts unverified on device

Everything else in this design was checked against the API reference or the
codebase. These three were not, and each is settled by a single scan on the
target device. **None of them may be left standing on reasoning alone.**

**7.1 — Does the host app still need `CAMERA`?** The scanner runs in Play
Services' own process, so it should not. Removing the permission is a privacy
improvement for an app used by children. Test on a **clean install** — the
current device already holds a granted permission from the existing build, so
testing over that install proves nothing. If the scan fails without it, restore
the permission and its request flow and correct this section.

**7.2 — Is the result JPEG upright?** `downscaleToPageImage(jpeg, 0)` in §4
assumes it is. CameraX's `ImageProxy.rotationDegrees` — the app's only record of
orientation, since the re-encode drops EXIF — disappears with this migration.
Confirm both the upload and display copies come out correctly oriented and agree
with each other. A sideways display copy means every bubble crop lands on a
rotated page.

**7.3 — What resolution is the result JPEG?** This is the one with teeth.
`displayBytes` exists solely so bubble crops are sharp: `PageImage`'s own
documentation notes that a bubble filling a fifth of the page is about 300 px in
the upload copy, "soft blown up across a phone screen". Today the display copy is
3000×4000. If ML Kit returns a rectified page at, say, 1600 px on the long edge,
`displayBytes` quietly loses most of its resolution and every crop gets softer —
degrading the exact feature this work exists to serve, invisibly, with no test
that would catch it. `meta.json` already records `displayWidth`/`displayHeight`,
so one pull answers it. If the resolution is inadequate, that is a finding
against this design and must be raised, not absorbed.

`<uses-feature android:name="android.hardware.camera.any" android:required="true" />`
stays: with gallery import off, a device with no camera cannot read a page. Note
this governs Play Store device filtering only — it is not a guarantee that Play
Services' scanner module can run on a given device, which is §8's problem.

## 8. Error handling

Deleting CameraX removes the only fallback capture path, so failures must be
honest rather than silent. The app's existing habit of degrading gracefully is
precisely what hid the bubble-crop bug for weeks; that is not repeated here.

| Case | Behaviour |
|---|---|
| User backs out of the scanner (`RESULT_CANCELED`) | return to `Idle`, no message |
| `getStartScanIntent` task fails | `Failed`, retryable |
| Scanner module unavailable or still downloading on first use (needs ≥1.7 GB RAM) | `Failed`, **retryable** — a download in progress is not permanent unavailability |
| Result data missing, or `GmsDocumentScanningResult` cannot be constructed | `Failed` |
| Result contains no pages | `Failed` |
| URI unreadable, null stream, or `ContentResolver` throws | `Failed` |

`Failed` carries a reason string for display and a retry that returns to the
scanner-launch state. No silent return to `Idle` on error: a child staring at an
unchanged screen has no way to know anything went wrong.

**Stale-state safety.** A failed scan must never leave an earlier `Captured`
image sitting confirmable behind the error — a retry that silently confirms the
previous page is worse than an error. A failure moves the state machine to
`Failed` and discards any prior `Captured`.

**Reset semantics.** `pipeline.reset()` is called when a captured or confirmed
page is *replaced*, preserving today's behaviour. It is **not** called when the
user merely backs out of a scanner launched from `Idle` — there is nothing to
reset, and resetting would discard an in-flight read for no reason.

## 9. Testing

| Test | Change |
|---|---|
| `DownscaleTest` | unchanged |
| `CaptureViewModelTest` | rewritten for `onScanned` / `onScanFailed` / `onScanCancelled` / `onRetake`, plus stale-state safety (§8) and reset semantics (§8). Pure JVM, no ML Kit |
| `PageScannerTest` | **new.** `ActivityResult` → `ScanOutcome` mapping: cancelled, missing data, unconstructable result, empty page list, success |
| `PageBytesReaderTest` | **new.** successful read, unreadable URI, null stream, resolver throwing |
| `CaptureScreenTest` | preview cases removed; add `Idle`, `Captured`, `Failed` rendering with a fake launcher |
| `GraphTest` | unchanged; `ui -> domain <- data` still holds, ML Kit confined to `ui/capture` |

**On-device verification is the real test, and it is specified here because §3
means this change cannot be judged by whether the crops look better.**

1. Clean install on the target device (§7.1 depends on it being clean).
2. Photograph a page.
3. `python scripts/diagnostics.py pull`.
4. From `meta.json` and the two JPEGs: does the rectified page fill the frame,
   do both copies share an orientation (§7.2), and is `displayWidth`/
   `displayHeight` still large enough for sharp crops (§7.3)?
5. Inspect `overlay.png` for parseable responses, or `response.json` and
   `error.txt` for failed reads.
6. Re-run the OCR measurement from
   `2026-08-31-bubble-box-accuracy-measured.md` §1 on the new bundle.

The success criteria are **page coverage, orientation and display resolution** —
not improved IoU. If IoU also improves, that is information about the model,
recorded but not expected.

## 10. Out of scope

- The OCR-localisation split. That is the fix for the boxes and needs its own
  design.
- Multi-page scanning. Page limit is 1; a book is read one page at a time.
- Any change to the reader, the pipeline, voices or caching.
