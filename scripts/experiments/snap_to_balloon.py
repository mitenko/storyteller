#!/usr/bin/env python3
"""Round 2: are the snap failures fixable, or intrinsic?

Round 1 found the idea works when the fill lands in the balloon (containment
1.000 on every such unit) but three failure modes:

  collapse  the seed lands on a letter stroke or a tiny enclosed pocket, and the
            fill returns a near-zero region
  merge     two adjacent balloons fill as one region, so both get the same box
  escape    the fill leaks through the outline into the page background

This tries the obvious fixes -- multi-seed with largest-region selection for
collapse, and an area floor -- to find out which failures are seed-selection
problems (fixable) and which are topology problems (not).
"""
import json, os, sys
from collections import deque

REPO = r'D:/Claude/apps/storyteller'
sys.path.insert(0, os.path.join(REPO, 'scripts'))
import measure_boxes as M
from PIL import Image

MIN_LUMA, MAX_SAT = 165, 60
MAX_GROWTH = 3.0
MIN_FILL_FRACTION = 0.15   # a fill smaller than this fraction of the seed box collapsed


def fill_from(px, w, h, seed, limit, is_paper):
    seen = {seed}
    q = deque([seed])
    minx = maxx = seed[0]
    miny = maxy = seed[1]
    while q:
        x, y = q.popleft()
        if len(seen) > limit:
            return None, len(seen)
        minx, maxx = min(minx, x), max(maxx, x)
        miny, maxy = min(miny, y), max(maxy, y)
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < w and 0 <= ny < h and (nx, ny) not in seen and is_paper(nx, ny):
                seen.add((nx, ny))
                q.append((nx, ny))
    return (float(minx), float(miny), float(maxx), float(maxy)), len(seen)


def snap(img, box, w, h):
    px = img.load()
    x1, y1, x2, y2 = [int(round(v)) for v in box]
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(w - 1, x2), min(h - 1, y2)
    if x2 <= x1 or y2 <= y1:
        return None, "degenerate seed"

    def is_paper(x, y):
        r, g, b = px[x, y][:3]
        luma = (r * 299 + g * 587 + b * 114) // 1000
        return luma >= MIN_LUMA and (max(r, g, b) - min(r, g, b)) <= MAX_SAT

    seed_area = (x2 - x1) * (y2 - y1)
    limit = seed_area * MAX_GROWTH

    # Multi-seed: a grid over the box, keeping the LARGEST region found. A letter
    # stroke or a tiny pocket loses to the balloon's own interior, which fixes the
    # collapse failure without any threshold tuning.
    best, best_px, escaped = None, 0, False
    tried = set()
    step_x = max(1, (x2 - x1) // 6)
    step_y = max(1, (y2 - y1) // 6)
    for y in range(y1, y2 + 1, step_y):
        for x in range(x1, x2 + 1, step_x):
            if not is_paper(x, y) or (x, y) in tried:
                continue
            region, npx = fill_from(px, w, h, (x, y), limit, is_paper)
            tried.add((x, y))
            if region is None:
                escaped = True
                continue
            if npx > best_px:
                best, best_px = region, npx

    if best is None:
        return None, "escaped" if escaped else "no paper pixel in the box"
    if best_px < seed_area * MIN_FILL_FRACTION:
        return None, "collapsed (%.0f%% of seed area)" % (100.0 * best_px / seed_area)
    return best, None


BUNDLES = ['page-1788289251857', 'page-1788284934899',
           'page-1788295071078', 'page-1788294930134']

totals = {"ok": 0, "reject": 0, "merge": 0}
scored_all = []

for name in BUNDLES:
    d = os.path.join(REPO, 'diagnostics-pulled', name)
    meta = json.load(open(os.path.join(d, 'meta.json')))
    W, H = meta['uploadWidth'], meta['uploadHeight']
    img = Image.open(os.path.join(d, 'page-upload.jpg')).convert('RGB')
    units, _ = M.load_units(d, W, H)
    ow, oh, words = M.ocr(os.path.join(d, 'page-upload.jpg'))
    extents = M.transcript_extents(words, units)

    print("=" * 78)
    print("%s   %dx%d   %d units" % (name, W, H, len(units)))
    snaps = {}
    for u in units:
        if u['box'] is None:
            continue
        s, why = snap(img, u['box'], W, H)
        snaps[u['i']] = (s, why)

    # merge detection: two units snapping to (nearly) the same region
    keys = [k for k, (s, _) in snaps.items() if s]
    merged = set()
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            a, b = snaps[keys[i]][0], snaps[keys[j]][0]
            if M.iou(a, b) > 0.8:
                merged.add(keys[i]); merged.add(keys[j])

    gains = []
    for u in units:
        if u['box'] is None:
            continue
        s, why = snaps[u['i']]
        rec = extents.get(u['i'])
        t = rec['box'] if rec else None
        cm = M.containment(u['box'], t) if t else None
        cs = M.containment(s, t) if (t and s) else None
        tag = why or ("MERGED with a neighbour" if u['i'] in merged else "ok")
        if why:
            totals["reject"] += 1
        elif u['i'] in merged:
            totals["merge"] += 1
        else:
            totals["ok"] += 1
        print("  %-3d %-24s %-24s %7s %7s  %s" % (
            u['i'],
            "%.0f,%.0f %.0f,%.0f" % tuple(u['box']),
            ("%.0f,%.0f %.0f,%.0f" % s) if s else "-",
            "-" if cm is None else "%.3f" % cm,
            "-" if cs is None else "%.3f" % cs, tag))
        if cm is not None and cs is not None and u['i'] not in merged and not why:
            gains.append((cm, cs)); scored_all.append((cm, cs))
    if gains:
        print("  clean units: containment model %.3f -> snapped %.3f" % (
            sum(g[0] for g in gains) / len(gains), sum(g[1] for g in gains) / len(gains)))
    print()

print("=" * 78)
n = sum(totals.values())
print("across all four pages, %d boxed units:" % n)
for k, v in totals.items():
    print("  %-8s %2d  (%.0f%%)" % (k, v, 100.0 * v / n))
if scored_all:
    print("  on units that were clean AND scoreable (%d): containment %.3f -> %.3f" % (
        len(scored_all),
        sum(g[0] for g in scored_all) / len(scored_all),
        sum(g[1] for g in scored_all) / len(scored_all)))
