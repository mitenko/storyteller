#!/usr/bin/env python3
"""Measure how far the model's speech-bubble boxes are from the text they enclose.

    python scripts/measure_boxes.py BUNDLE [BUNDLE ...] [--out FILE]

Every number quoted in docs/issues/2026-08-31-bubble-box-accuracy-measured.md was
produced by a throwaway script in a temp directory. Comparing three protocol
revisions through three ad-hoc scripts is not a comparison, so this is the
committed version: the same measurement, runnable against any pulled bundle.

Ground truth is OCR, not a human. Windows' own `Windows.Media.Ocr` engine reads
`page-upload.jpg` -- the copy the model actually saw -- and returns a box per
word. Words are clustered into speech units by proximity, each cluster is matched
to a unit in `response.json` by text similarity, and the model's box is then
compared against the extent of the words it claims to enclose.

This runs entirely offline through a PowerShell shim. The page photographs are
copyrighted book pages and never leave the machine.

Two things the numbers do NOT mean:

  * OCR extent is the extent of the TEXT, not of the balloon drawn around it. A
    correct box is legitimately larger. That is why IoU is reported against three
    padding assumptions rather than one -- a box roughly 30% larger than its text
    is the honest target, not a box equal to it.
  * A unit whose text OCR could not read is dropped, not scored. Coverage is
    printed so a flattering mean over two matched units cannot pass as a result.
"""
import argparse
import difflib
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import traceback

# Single-link clustering distance, as a fraction of the image's long edge. Words
# in one balloon sit far closer than this; separate balloons sit far further.
CLUSTER_GAP = 0.035

# Below this difflib ratio a cluster and a unit are not the same speech, and
# pairing them anyway would measure OCR failure rather than box accuracy.
MATCH_FLOOR = 0.35

# A cluster holding fewer real letters than this is not a speech unit's extent.
# Without it a stray one-word cluster ("TO") gets paired with a whole balloon and
# reports it as ~16x too wide, which is a measurement artefact, not a finding.
MIN_CLUSTER_ALPHA = 3

# The padding assumptions IoU is reported against. A real balloon encloses its
# text with a margin, so 0% is a lower bound that no correct box could reach.
PADDINGS = (0.0, 0.30, 0.60)

OCR_PS1 = r'''
param([Parameter(Mandatory=$true)][string]$Path)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]

function Await($op, $type) {
    $m = $asTaskGeneric.MakeGenericMethod($type)
    $t = $m.Invoke($null, @($op))
    $t.Wait(-1) | Out-Null
    $t.Result
}

$null = [Windows.Storage.StorageFile, Windows.Foundation, ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType=WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime]

$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
$stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
$decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if ($null -eq $engine) { throw "no OCR engine is installed for this user's languages" }

$result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])

$words = New-Object System.Collections.ArrayList
foreach ($line in $result.Lines) {
    foreach ($w in $line.Words) {
        $r = $w.BoundingRect
        [void]$words.Add([pscustomobject]@{
            text = $w.Text; x = $r.X; y = $r.Y; w = $r.Width; h = $r.Height
        })
    }
}
[pscustomobject]@{
    width  = $decoder.PixelWidth
    height = $decoder.PixelHeight
    words  = $words
} | ConvertTo-Json -Compress -Depth 4
'''


def ocr(image_path):
    """Word boxes from the offline Windows OCR engine, in image pixels.

    Windows PowerShell 5.1 specifically, not pwsh: the WinRT type accelerators
    this shim relies on are not available in PowerShell 7.
    """
    fd, ps1 = tempfile.mkstemp(suffix=".ps1")
    os.close(fd)
    try:
        with open(ps1, "w", encoding="utf-8") as fh:
            fh.write(OCR_PS1)
        out = subprocess.run(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
             "-File", ps1, "-Path", os.path.abspath(image_path)],
            capture_output=True,
        )
        if out.returncode != 0:
            raise RuntimeError("OCR failed: %s" % out.stderr.decode(errors="replace").strip())
        payload = json.loads(out.stdout.decode("utf-8-sig", errors="replace"))
    finally:
        os.unlink(ps1)
    words = payload.get("words") or []
    # ConvertTo-Json collapses a one-element array to a bare object.
    if isinstance(words, dict):
        words = [words]
    return payload["width"], payload["height"], words


def cluster_words(words, width, height):
    """Group word boxes into speech units by proximity, single-link.

    Text-similarity matching alone is not enough to build the ground-truth
    extent: short tokens ("THE", "I") match many units equally well, and a greedy
    text-first pass lets them be absorbed by the wrong one, which quietly inflates
    that unit's measured extent. Grouping by position first and matching the
    finished cluster as a whole removes that failure mode.
    """
    gap = CLUSTER_GAP * max(width, height)
    boxes = [(w["x"], w["y"], w["x"] + w["w"], w["y"] + w["h"], w["text"]) for w in words]
    parent = list(range(len(boxes)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(i, j):
        ri, rj = find(i), find(j)
        if ri != rj:
            parent[rj] = ri

    def edge_distance(a, b):
        dx = max(0.0, max(a[0] - b[2], b[0] - a[2]))
        dy = max(0.0, max(a[1] - b[3], b[1] - a[3]))
        return math.hypot(dx, dy)

    for i in range(len(boxes)):
        for j in range(i + 1, len(boxes)):
            if edge_distance(boxes[i], boxes[j]) <= gap:
                union(i, j)

    groups = {}
    for i, box in enumerate(boxes):
        groups.setdefault(find(i), []).append(box)

    clusters = []
    for members in groups.values():
        members.sort(key=lambda b: (b[1], b[0]))
        clusters.append({
            "x1": min(m[0] for m in members),
            "y1": min(m[1] for m in members),
            "x2": max(m[2] for m in members),
            "y2": max(m[3] for m in members),
            "text": " ".join(m[4] for m in members),
            "words": len(members),
        })
    clusters = [c for c in clusters if len(alpha(c["text"])) >= MIN_CLUSTER_ALPHA]
    clusters.sort(key=lambda c: (c["y1"], c["x1"]))
    return clusters


def alpha(s):
    return re.sub(r"[^a-z]", "", (s or "").lower())


def load_units(bundle, width, height):
    """Units from response.json, with bounds in image pixels.

    Handles both coordinate conventions this app has shipped: parse versions 1-4
    returned fractions of the image under left/top/right/bottom, version 5 returns
    absolute pixels under x1/y1/x2/y2. Reporting which one a bundle uses matters
    as much as the numbers -- the whole point of the change was that one of the
    two is the format the vendor documents as not working.
    """
    with open(os.path.join(bundle, "response.json"), encoding="utf-8") as fh:
        raw = json.load(fh)

    convention = "none"
    units = []
    for i, u in enumerate(raw.get("units", [])):
        b = u.get("bounds")
        box = None
        if b:
            if "x1" in b:
                convention = "pixels (parse v5)"
                box = (float(b["x1"]), float(b["y1"]), float(b["x2"]), float(b["y2"]))
            elif "left" in b:
                convention = "fractions (parse v1-4)"
                box = (float(b["left"]) * width, float(b["top"]) * height,
                       float(b["right"]) * width, float(b["bottom"]) * height)
        units.append({"i": i, "speaker": u.get("speaker", "?"),
                      "text": u.get("text", ""), "box": box})
    return units, convention


def match(clusters, units):
    """Best cluster per unit, above the similarity floor, each used once."""
    scored = []
    for u in units:
        if u["box"] is None:
            continue
        for c in clusters:
            r = difflib.SequenceMatcher(None, alpha(u["text"]), alpha(c["text"])).ratio()
            scored.append((r, u["i"], id(c), u, c))
    scored.sort(key=lambda s: -s[0])

    pairs, used_u, used_c = [], set(), set()
    for r, ui, ci, u, c in scored:
        if r < MATCH_FLOOR or ui in used_u or ci in used_c:
            continue
        used_u.add(ui)
        used_c.add(ci)
        pairs.append((u, c, r))
    pairs.sort(key=lambda p: p[0]["i"])
    return pairs


def pad(box, fraction):
    x1, y1, x2, y2 = box
    dx, dy = (x2 - x1) * fraction / 2.0, (y2 - y1) * fraction / 2.0
    return (x1 - dx, y1 - dy, x2 + dx, y2 + dy)


def iou(a, b):
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    if ix2 <= ix1 or iy2 <= iy1:
        return 0.0
    inter = (ix2 - ix1) * (iy2 - iy1)
    area_a = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1])
    area_b = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    return inter / (area_a + area_b - inter)


def fit(pairs_xy):
    """Least squares `true = a*model + b`, with R^2. Returns (a, b, r2, n)."""
    n = len(pairs_xy)
    if n < 2:
        return (float("nan"),) * 3 + (n,)
    mx = sum(p[0] for p in pairs_xy) / n
    my = sum(p[1] for p in pairs_xy) / n
    sxx = sum((p[0] - mx) ** 2 for p in pairs_xy)
    sxy = sum((p[0] - mx) * (p[1] - my) for p in pairs_xy)
    syy = sum((p[1] - my) ** 2 for p in pairs_xy)
    if sxx == 0 or syy == 0:
        return (float("nan"),) * 3 + (n,)
    a = sxy / sxx
    b = my - a * mx
    r2 = (sxy * sxy) / (sxx * syy)
    return a, b, r2, n


def mean_sd(values):
    n = len(values)
    if not n:
        return float("nan"), float("nan")
    m = sum(values) / n
    if n < 2:
        return m, 0.0
    return m, math.sqrt(sum((v - m) ** 2 for v in values) / (n - 1))


def measure(bundle, emit):
    name = os.path.basename(os.path.abspath(bundle.rstrip("/\\")))
    upload = os.path.join(bundle, "page-upload.jpg")
    if not os.path.exists(upload):
        raise FileNotFoundError("no page-upload.jpg in %s" % bundle)

    ocr_w, ocr_h, words = ocr(upload)

    meta_path = os.path.join(bundle, "meta.json")
    meta = {}
    if os.path.exists(meta_path):
        with open(meta_path, encoding="utf-8") as fh:
            meta = json.load(fh)
    # The coordinate space is the upload copy's own dimensions. meta.json records
    # them independently; the decode above is the check on that record, so a
    # disagreement is reported rather than silently preferred one way.
    width = meta.get("uploadWidth") or ocr_w
    height = meta.get("uploadHeight") or ocr_h

    emit("")
    emit("=" * 78)
    emit("%s" % name)
    emit("=" * 78)
    emit("upload %dx%d  (decoded %dx%d)%s" % (
        width, height, ocr_w, ocr_h,
        "   MISMATCH -- meta.json disagrees with the image" if (width, height) != (ocr_w, ocr_h) else ""))
    if meta.get("device"):
        emit("device %s, sdk %s" % (meta["device"], meta.get("androidSdk", "?")))

    units, convention = load_units(bundle, width, height)
    clusters = cluster_words(words, ocr_w, ocr_h)
    pairs = match(clusters, units)

    boxed = [u for u in units if u["box"] is not None]
    emit("coordinates %s" % convention)
    emit("%d units (%d with a box), %d OCR words in %d clusters, %d matched"
         % (len(units), len(boxed), len(words), len(clusters), len(pairs)))
    if boxed:
        emit("coverage %.0f%% of boxed units matched to OCR text"
             % (100.0 * len(pairs) / len(boxed)))
    if not pairs:
        emit("")
        emit("NOTHING MATCHED -- no measurement is possible from this bundle.")
        return

    emit("")
    emit("per unit (all figures normalised to the image, 0..1):")
    emit("  %-3s %-12s %-27s %-27s %7s %7s %6s %6s"
         % ("#", "speaker", "model box", "OCR text extent", "dx", "dy", "w/w", "IoU"))

    dxs, dys, ratios, ious = [], [], [], []
    fx, fy, cx, cy = [], [], [], []
    for u, c, r in pairs:
        m = u["box"]
        t = (c["x1"], c["y1"], c["x2"], c["y2"])
        mcx, mcy = (m[0] + m[2]) / 2 / width, (m[1] + m[3]) / 2 / height
        tcx, tcy = (t[0] + t[2]) / 2 / width, (t[1] + t[3]) / 2 / height
        dx, dy = mcx - tcx, mcy - tcy
        mw = (m[2] - m[0]) or 1e-9
        tw = (t[2] - t[0]) or 1e-9
        ratio = mw / tw
        v = iou(m, t)
        dxs.append(dx); dys.append(dy); ratios.append(ratio); ious.append(v)
        fx.append((m[0] / width, t[0] / width)); fx.append((m[2] / width, t[2] / width))
        fy.append((m[1] / height, t[1] / height)); fy.append((m[3] / height, t[3] / height))
        cx.append((mcx, tcx)); cy.append((mcy, tcy))

        emit("  %-3d %-12s %-27s %-27s %+7.3f %+7.3f %6.2f %6.3f" % (
            u["i"], u["speaker"][:12],
            "%.3f %.3f %.3f %.3f" % (m[0] / width, m[1] / height, m[2] / width, m[3] / height),
            "%.3f %.3f %.3f %.3f" % (t[0] / width, t[1] / height, t[2] / width, t[3] / height),
            dx, dy, ratio, v))

    emit("")
    mdx, sdx = mean_sd(dxs)
    mdy, sdy = mean_sd(dys)
    mr, sr = mean_sd(ratios)
    emit("centre error   dx mean %+.3f (sd %.3f)   dy mean %+.3f (sd %.3f)" % (mdx, sdx, mdy, sdy))
    emit("size ratio     model width / text width: mean %.2f (sd %.2f), range %.2f-%.2f"
         % (mr, sr, min(ratios), max(ratios)))

    emit("")
    emit("IoU against the OCR text extent, at three padding assumptions:")
    for p in PADDINGS:
        vals = [iou(u["box"], pad((c["x1"], c["y1"], c["x2"], c["y2"]), p)) for u, c, _ in pairs]
        m, _ = mean_sd(vals)
        emit("  text %+3.0f%%   mean IoU %.3f   (%d of %d units above 0.5)"
             % (p * 100, m, sum(1 for v in vals if v >= 0.5), len(vals)))

    emit("")
    emit("affine fit, true = a*model + b.  Both are reported because they answer")
    emit("different questions and the distinction has been conflated before:")
    emit("  over EDGES   - captures position AND size. a below 1 means the boxes are")
    emit("                 larger than the text they enclose.")
    emit("  over CENTRES - captures position only. a of 1 with b nonzero is a pure")
    emit("                 offset, whatever the boxes' size error.")
    for label, dx_pairs, dy_pairs in (("edges", fx, fy), ("centres", cx, cy)):
        ax, bx, r2x, nx = fit(dx_pairs)
        ay, by, r2y, ny = fit(dy_pairs)
        emit("  %-8s x  a %.3f  b %+.3f  R2 %.3f  (n=%d)" % (label, ax, bx, r2x, nx))
        emit("  %-8s y  a %.3f  b %+.3f  R2 %.3f  (n=%d)" % (label, ay, by, r2y, ny))

    emit("")
    best = max(
        (sum(iou(u["box"], pad((c["x1"], c["y1"], c["x2"], c["y2"]), p)) for u, c, _ in pairs) / len(pairs))
        for p in PADDINGS)
    emit("VERDICT: best mean IoU %.3f against the 0.5 stop condition -- %s"
         % (best, "PASS" if best >= 0.5 else "FAIL"))


def run(argv=None):
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bundles", nargs="+", help="pulled bundle directories")
    ap.add_argument("--out", help="also write the report to this file")
    args = ap.parse_args(argv)

    lines = []

    def emit(line):
        print(line)
        lines.append(line)

    for bundle in args.bundles:
        measure(bundle, emit)

    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")
        print("\nwrote %s" % args.out)
    return 0


def main():
    try:
        return run()
    except Exception:
        here = os.path.dirname(os.path.abspath(__file__))
        with open(os.path.join(here, "error.txt"), "w", encoding="utf-8") as fh:
            fh.write(traceback.format_exc())
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
