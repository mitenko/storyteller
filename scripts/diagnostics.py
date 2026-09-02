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
parse.json, because the client validates coordinates into the domain model and a
box the model placed off the page is exactly the kind of evidence that derived
data can hide. A box that falls outside the page is drawn at the edge it ran
past and called out in the printed summary. Failed parses can be inspected
directly in response.json and error.txt; they do not produce an overlay.
"""
import argparse
import json
import os
import subprocess
import sys
import traceback

PACKAGE = "com.storyteller"
REMOTE_DIR = "files/diagnostics"
FILES = ("page-display.jpg", "page-upload.jpg", "response.json", "parse.json", "meta.json")
OPTIONAL_FILES = ("error.txt",)


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
        # `adb exec-out run-as ... cat missing` exits 0 and puts the shell's own
        # "No such file" on stdout, so a missing file cannot be detected from the
        # exit code -- writing it blind produced an error.txt holding that message
        # and made a clean bundle look like a failed read. List once instead and
        # fetch only what is actually there.
        present = set(adb(serial, "shell", "run-as", PACKAGE, "ls",
                          "%s/%s" % (REMOTE_DIR, b)).split())
        for name in FILES + OPTIONAL_FILES:
            if name not in present:
                if name in FILES:
                    print("  %s: MISSING %s" % (b, name))
                continue
            data = adb(serial, "exec-out", "run-as", PACKAGE, "cat",
                       "%s/%s/%s" % (REMOTE_DIR, b, name), binary=True)
            with open(os.path.join(local, name), "wb") as fh:
                fh.write(data)
        pulled.append(local)
        print("pulled %s%s" % (b, "  (failed read)" if "error.txt" in present else ""))
    return pulled


def box_in_pixels(bounds, width, height):
    """A response box in image pixels, whichever coordinate convention it uses.

    Parse versions 1-4 returned fractions of the image under left/top/right/
    bottom; version 5 returns absolute pixels under x1/y1/x2/y2. Both shapes are
    handled so that bundles pulled before and after the protocol change can be
    overlaid and compared with the same command.

    Returns (box, outside), where `outside` marks a box the model placed beyond
    the image -- the app rejects those rather than cropping to them.
    """
    if "x1" in bounds:
        box = (float(bounds["x1"]), float(bounds["y1"]),
               float(bounds["x2"]), float(bounds["y2"]))
        outside = box[0] < 0 or box[1] < 0 or box[2] > width or box[3] > height
    else:
        f = (float(bounds["left"]), float(bounds["top"]),
             float(bounds["right"]), float(bounds["bottom"]))
        outside = any(v < 0.0 or v > 1.0 for v in f)
        box = (f[0] * width, f[1] * height, f[2] * width, f[3] * height)
    return box, outside


def overlay(bundle):
    from PIL import Image, ImageDraw

    with open(os.path.join(bundle, "response.json"), encoding="utf-8") as fh:
        raw_text = fh.read()
    try:
        raw = json.loads(raw_text)
    except json.JSONDecodeError:
        print("\n%s  has a non-JSON response; see response.json and error.txt" %
              os.path.basename(bundle))
        return None
    # Drawn on page-display, but v5 boxes are pixels of page-UPLOAD, so they are
    # rescaled through the upload's dimensions rather than used directly.
    img = Image.open(os.path.join(bundle, "page-display.jpg")).convert("RGB")
    w, h = img.size
    upload_w, upload_h = w, h
    meta_path = os.path.join(bundle, "meta.json")
    if os.path.exists(meta_path):
        with open(meta_path, encoding="utf-8") as fh:
            meta = json.load(fh)
        upload_w = meta.get("uploadWidth") or w
        upload_h = meta.get("uploadHeight") or h
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
        box, off = box_in_pixels(b, upload_w, upload_h)
        if off:
            outside += 1
        # Into display space, so the drawing lands on the page the reader sees.
        sx, sy = w / float(upload_w), h / float(upload_h)
        box = (box[0] * sx, box[1] * sy, box[2] * sx, box[3] * sy)
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
        print("  [%2d] %-14s L%.0f T%.0f R%.0f B%.0f px%s  %r"
              % (i, speaker[:14], box[0], box[1], box[2], box[3],
                 "  <-- OUTSIDE THE IMAGE" if off else "", text))

    out = os.path.join(bundle, "overlay.png")
    img.save(out)
    print("  -> %s" % out)
    if outside:
        print("  !! %d box(es) fall outside the image; the app rejects those for cropping" % outside)
    return out


def run():
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
