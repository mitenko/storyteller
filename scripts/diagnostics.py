#!/usr/bin/env python3
"""Pull Storyteller diagnostic bundles off a device and draw the model's boxes.

The app records one bundle per vision call into its private files directory. A
debug build's private storage is readable over the cable with `run-as`, so no
permission, no network and no upload is involved -- the page photographs are
copyrighted book pages and never leave the machine.

    python scripts/diagnostics.py pull [--serial SERIAL] [--out DIR]
    python scripts/diagnostics.py overlay DIR [DIR ...]

`pull` copies every bundle to DIR (default ./diagnostics-pulled).
`overlay` writes overlay.png beside each bundle's page-display.jpg, with each
returned box drawn on it and labelled with its unit index and speaker, so
"comically wrong" becomes a measurable offset instead of an impression.

Boxes are drawn from response.json -- the model's RAW output -- not from
parse.json, because the client clamps coordinates into 0..1 and a box the model
placed off the page is exactly the kind of evidence that clamping hides. A box
that falls outside the page is drawn dashed at the edge it ran past and called
out in the printed summary.
"""
import argparse
import json
import os
import subprocess
import sys

PACKAGE = "com.storyteller"
REMOTE_DIR = "files/diagnostics"
FILES = ("page-display.jpg", "page-upload.jpg", "response.json", "parse.json", "meta.json")


def adb(serial, *args, binary=False):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    out = subprocess.run(cmd, capture_output=True)
    if out.returncode != 0:
        raise RuntimeError("%s failed: %s" % (" ".join(cmd), out.stderr.decode(errors="replace").strip()))
    return out.stdout if binary else out.stdout.decode(errors="replace")


def pull(serial, out_dir):
    listing = adb(serial, "shell", "run-as", PACKAGE, "ls", REMOTE_DIR).split()
    bundles = [b for b in listing if b.startswith("page-")]
    if not bundles:
        print("no bundles on the device yet -- read a page in the app first")
        return []
    os.makedirs(out_dir, exist_ok=True)
    pulled = []
    for b in sorted(bundles):
        local = os.path.join(out_dir, b)
        os.makedirs(local, exist_ok=True)
        for name in FILES:
            data = adb(serial, "exec-out", "run-as", PACKAGE, "cat",
                       "%s/%s/%s" % (REMOTE_DIR, b, name), binary=True)
            with open(os.path.join(local, name), "wb") as fh:
                fh.write(data)
        pulled.append(local)
        print("pulled %s (%d files)" % (b, len(FILES)))
    return pulled


def overlay(bundle):
    from PIL import Image, ImageDraw

    with open(os.path.join(bundle, "response.json"), encoding="utf-8") as fh:
        raw = json.load(fh)
    img = Image.open(os.path.join(bundle, "page-display.jpg")).convert("RGB")
    w, h = img.size
    draw = ImageDraw.Draw(img)

    print("\n%s  (display %dx%d)" % (os.path.basename(bundle), w, h))
    outside = 0
    for i, unit in enumerate(raw.get("units", [])):
        b = unit.get("bounds")
        speaker = unit.get("speaker", "?")
        text = (unit.get("text") or "").replace("\n", " ")[:32]
        if not b:
            print("  [%2d] %-14s no box                          %r" % (i, speaker[:14], text))
            continue
        off = [v for v in (b["left"], b["top"], b["right"], b["bottom"]) if v < 0.0 or v > 1.0]
        if off:
            outside += 1
        box = (b["left"] * w, b["top"] * h, b["right"] * w, b["bottom"] * h)
        # Clamp only for DRAWING, so an off-page box is still visible as the
        # edge it ran past rather than silently vanishing.
        drawn = (max(0, min(w - 1, box[0])), max(0, min(h - 1, box[1])),
                 max(0, min(w - 1, box[2])), max(0, min(h - 1, box[3])))
        if drawn[2] <= drawn[0] or drawn[3] <= drawn[1]:
            print("  [%2d] %-14s OFF-PAGE, nothing to draw        %r" % (i, speaker[:14], text))
            continue
        colour = (255, 64, 64) if off else (64, 220, 64)
        draw.rectangle(drawn, outline=colour, width=max(2, w // 300))
        draw.text((drawn[0] + 4, drawn[1] + 4), "%d %s" % (i, speaker[:12]), fill=colour)
        print("  [%2d] %-14s L%.3f T%.3f R%.3f B%.3f%s  %r"
              % (i, speaker[:14], b["left"], b["top"], b["right"], b["bottom"],
                 "  <-- OUTSIDE 0..1" if off else "", text))

    out = os.path.join(bundle, "overlay.png")
    img.save(out)
    print("  -> %s" % out)
    if outside:
        print("  !! %d box(es) fall outside 0..1; the app clamps those, which collapses them" % outside)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("pull", help="copy bundles off the device")
    p.add_argument("--serial")
    p.add_argument("--out", default="diagnostics-pulled")

    o = sub.add_parser("overlay", help="draw the returned boxes onto the page")
    o.add_argument("bundles", nargs="+")

    args = ap.parse_args()
    if args.cmd == "pull":
        for b in pull(args.serial, args.out):
            overlay(b)
    else:
        for b in args.bundles:
            overlay(b)
    return 0


if __name__ == "__main__":
    sys.exit(main())
