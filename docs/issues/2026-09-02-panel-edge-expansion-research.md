# Expanding from speech bubbles to comic-panel edges

**Date:** 2026-09-02

**Question:** Can the app start from a speech-bubble location and expand
outward until it finds the white or black edges of the comic panel?

## Short answer

Yes, this is technically possible as a pixel-analysis refinement, but it is
not reliable enough to be the sole localization method. Finding a panel
boundary is generally more tractable than finding the balloon boundary:
panels often have long, dark gutters or straight borders, while balloons may
be white, colored, borderless, broken, or partially covered by artwork.

The safest design is hybrid:

1. Use OCR word boxes as the initial, pixel-grounded seed.
2. Expand around the seed using local color and texture measurements.
3. Score nearby dark lines, gutters, and abrupt texture changes as candidate
   panel boundaries.
4. Accept the result only when the evidence is strong and consistent.
5. Otherwise retain the OCR crop and the existing text fallback.

## Evidence in this repository

The current reader crops using `BubbleCrop.kt`, from a normalized
`BoundingBox`. The geometry helper in `CropGeometry.kt` adds a fixed margin and
clamps it to the image. It does not inspect image pixels.

The current localization abstraction is
`app/src/main/kotlin/com/storyteller/domain/ocr/PageLocalizer.kt`.
`TranscriptOcrLocalizer` matches recognized words to the vision transcript and
uses the matched word extent as the replacement box. This is a good starting
point because it provides a location tied to actual pixels rather than relying
on the vision model's inaccurate balloon rectangles.

The measured-box investigation in
`docs/issues/2026-08-31-bubble-box-accuracy-measured.md` §8 already records an
important limitation. OpenCV thresholding and contour retrieval were tried:

- `RETR_EXTERNAL` merged the white page margin, gutters, and lit desk into one
  connected bright region, causing balloons to appear as interior holes.
- `RETR_LIST` retained more contours, but the useful contours tended to be
  comic panels rather than speech balloons.

That result does not rule out seeded expansion. It means that global threshold
and contour extraction should not be expected to solve the problem by
themselves.

## Proposed algorithm

### 1. Establish the seed

Use the union of the OCR word boxes matched to one speech unit. Add a small
initial margin so letters touching the balloon border do not become the
starting edge. The model-provided box can be used as a secondary hint, but
should not override a high-confidence OCR placement.

### 2. Estimate the local balloon appearance

Sample pixels just outside the text, preferably in several directions. Estimate
local luminance, color variance, and texture rather than assuming that a
balloon is pure white. A white balloon, a pale colored balloon, and a
photographed page under uneven lighting need different thresholds.

### 3. Grow through likely balloon pixels

Region-grow from the seed through pixels sufficiently similar to the local
balloon estimate. Use adaptive thresholds or a locally normalized grayscale
image so shadows and page lighting do not dominate. Suppress small text
components and use light morphology to bridge tiny breaks in an outline.

The grow operation must have a hard maximum expansion. Without one, the page
margin, gutter, or desk can become the connected component.

### 4. Search for panel boundaries

Independently calculate edge evidence around the growing region. Candidate
boundaries include:

- long dark horizontal or vertical lines;
- broad low-texture gutters;
- a sustained luminance or texture change;
- repeated evidence across a run of neighboring pixels rather than a single
  dark pixel.

For photographs, line evidence should be tolerant of perspective and small
gaps. A strict axis-aligned rectangle will fail when the page or panel is
slanted.

### 5. Score and reject

A candidate crop should score well on all of the following:

- contains all matched OCR words;
- leaves a reasonable margin around the text;
- has boundary evidence on multiple sides;
- does not include a large amount of unrelated high-texture artwork;
- stays within a bounded distance from the seed;
- is stable when the image is analyzed at a second small scale.

If no candidate clears the confidence threshold, do not manufacture a
plausible-looking crop. Keep the OCR-derived box or render the text fallback.

## White versus black boundaries

### White balloon or white panel

White-region growth can identify the interior when the surrounding art is
darker, but page margins and gutters are often connected to the same region.
The algorithm needs local texture and boundary evidence, not brightness alone.
A black outline is useful when it is continuous, but many comic styles have
faint, broken, or absent outlines.

### Black gutter or border

Dark-line detection is usually more useful for finding the enclosing panel than
for finding the speech balloon. Canny-style edges, line scoring, and connected
dark runs can identify a border even when the interior is not uniform. The
detector must distinguish a panel border from character outlines, lettering,
hair, and other dark artwork.

### No visible boundary

Some pages have borderless panels, full-bleed art, colored backgrounds, or
balloons that overlap panel edges. In these cases there may be no defensible
pixel boundary. The correct result is a bounded text-centered crop, not an
overconfident panel rectangle.

## Implementation options

### Android bitmap implementation

The first prototype can avoid a native dependency. Decode a small display
region, sample luminance and color, perform bounded flood fill, and calculate
horizontal/vertical run scores. This keeps the dependency surface small and
makes the acceptance/rejection logic easy to unit test with synthetic bitmaps.

### OpenCV

OpenCV supplies useful primitives: fixed or adaptive thresholding, Canny edge
detection, morphology, connected components, contours, and contour hierarchy.
It does not provide a comic-aware panel detector, and adding it would increase
APK size and native integration complexity. It should be introduced only if a
bitmap prototype demonstrates that the primitives materially improve crops.

### ML Kit

ML Kit Text Recognition is appropriate for producing the initial word boxes.
It identifies text and its geometry; it does not identify comic-panel
boundaries. The current Gradle configuration includes the document scanner but
does not yet include the text-recognition artifact, so wiring the existing OCR
abstraction to a real recognizer is a separate implementation step.

## Recommendation

Prototype seeded panel expansion as a conservative fallback/refinement, not as
a replacement for OCR localization. Begin with a small synthetic-image test
set covering:

- a white balloon with a black outline;
- a white balloon connected to a white page margin;
- a colored or borderless balloon;
- a black rectangular gutter;
- panel art containing dark character outlines;
- a photographed page with uneven lighting and perspective.

Measure whether the resulting crop improves intersection-over-union against
hand-drawn balloon boxes without increasing false expansions into neighboring
panels. Keep the feature behind a confidence gate. If it cannot beat the
current OCR crop on representative pages, do not ship it.

## References

- OpenCV thresholding:
  https://docs.opencv.org/4.x/d7/d4d/tutorial_py_thresholding.html
- OpenCV Canny edge detection:
  https://docs.opencv.org/4.x/da/d22/tutorial_py_canny.html
- OpenCV contour hierarchy:
  https://docs.opencv.org/4.x/d9/d8b/tutorial_py_contours_hierarchy.html
- ML Kit Text Recognition for Android:
  https://developers.google.com/ml-kit/vision/text-recognition/v2/android
