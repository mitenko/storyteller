#!/usr/bin/env python3
"""Recursive X-Y cut: does it find real panels, not just horizontal tiers?

A single global projection finds only tiers, because a full-page column profile
crosses every tier and artwork in one bleeds across the gutter position of
another. The standard remedy is to recurse: split on rows, then project columns
WITHIN each strip, then rows within each of those, until no cut is found.

Scored by the question that matters for the product: does each balloon end up in
a panel that is materially smaller than the page, and do balloons in visibly
different panels get different panels?
"""
import json, os, sys

REPO = r'D:/Claude/apps/storyteller'
sys.path.insert(0, os.path.join(REPO, 'scripts'))
import measure_boxes as M
from PIL import Image

PURITY = 0.70
MIN_GUTTER = 3
MIN_PANEL = 0.04     # a region below this fraction of the page is not a panel
MAX_DEPTH = 4


def is_gutter_line(px, fixed, span_lo, span_hi, axis, step=2):
    pale = dark = n = 0
    for v in range(span_lo, span_hi + 1, step):
        r, g, b = (px[v, fixed][:3] if axis == 'row' else px[fixed, v][:3])
        lum = (r * 299 + g * 587 + b * 114) // 1000
        sat = max(r, g, b) - min(r, g, b)
        n += 1
        if lum >= 205 and sat <= 40:
            pale += 1
        elif lum <= 55:
            dark += 1
    if n == 0:
        return False
    return max(pale / n, dark / n) >= PURITY


def find_cuts(px, box, axis):
    """Gutter runs inside `box` along `axis`, excluding ones touching the edges."""
    x1, y1, x2, y2 = [int(v) for v in box]
    lo, hi = (y1, y2) if axis == 'row' else (x1, x2)
    span_lo, span_hi = (x1, x2) if axis == 'row' else (y1, y2)
    flags = [is_gutter_line(px, i, span_lo, span_hi, axis) for i in range(lo, hi + 1)]
    out, start = [], None
    for i, f in enumerate(flags):
        if f and start is None:
            start = i
        elif not f and start is not None:
            if i - start >= MIN_GUTTER:
                out.append((lo + start, lo + i - 1))
            start = None
    if start is not None and len(flags) - start >= MIN_GUTTER:
        out.append((lo + start, hi))
    # A gutter flush against the region's own edge is its border, not a divider.
    return [r for r in out if r[0] > lo + 1 and r[1] < hi - 1]


def xy_cut(px, box, W, H, depth=0):
    if depth >= MAX_DEPTH:
        return [box]
    area_frac = ((box[2] - box[0]) * (box[3] - box[1])) / float(W * H)
    if area_frac < MIN_PANEL * 2:
        return [box]

    for axis in ('row', 'col'):
        cuts = find_cuts(px, box, axis)
        if not cuts:
            continue
        pieces, prev = [], (box[1] if axis == 'row' else box[0])
        edges = [(c[0], c[1]) for c in cuts]
        for lo, hi in edges:
            piece = ((box[0], prev, box[2], lo) if axis == 'row'
                     else (prev, box[1], lo, box[3]))
            pieces.append(piece)
            prev = hi
        last = ((box[0], prev, box[2], box[3]) if axis == 'row'
                else (prev, box[1], box[2], box[3]))
        pieces.append(last)
        pieces = [p for p in pieces
                  if ((p[2] - p[0]) * (p[3] - p[1])) / float(W * H) >= MIN_PANEL]
        if len(pieces) >= 2:
            out = []
            for p in pieces:
                out.extend(xy_cut(px, p, W, H, depth + 1))
            return out
    return [box]


for name in ('page-1788289251857', 'page-1788284934899',
             'page-1788295071078', 'page-1788294930134'):
    d = os.path.join(REPO, 'diagnostics-pulled', name)
    meta = json.load(open(os.path.join(d, 'meta.json')))
    W, H = meta['uploadWidth'], meta['uploadHeight']
    px = Image.open(os.path.join(d, 'page-upload.jpg')).convert('RGB').load()
    units, _ = M.load_units(d, W, H)

    panels = xy_cut(px, (0.0, 0.0, float(W - 1), float(H - 1)), W, H)
    print("=" * 78)
    print("%s  %dx%d   %d panels found" % (name, W, H, len(panels)))
    for i, p in enumerate(panels):
        print("   panel %d  %4.0f,%4.0f %4.0f,%4.0f  %3.0f%% of page" % (
            i, p[0], p[1], p[2], p[3],
            100 * ((p[2] - p[0]) * (p[3] - p[1])) / float(W * H)))

    assigned = {}
    for u in units:
        if u['box'] is None:
            continue
        b = u['box']
        cx, cy = (b[0] + b[2]) / 2, (b[1] + b[3]) / 2
        hit = None
        for i, p in enumerate(panels):
            if p[0] <= cx <= p[2] and p[1] <= cy <= p[3]:
                hit = i
                break
        assigned[u['i']] = hit
    groups = {}
    for uid, pid in assigned.items():
        groups.setdefault(pid, []).append(uid)
    print("   balloon -> panel: %s" % ", ".join(
        "p%s:{%s}" % (k, ",".join(str(v) for v in sorted(vs))) for k, vs in sorted(
            groups.items(), key=lambda kv: (kv[0] is None, kv[0]))))
    distinct = len([k for k in groups if k is not None])
    print("   %d balloons across %d distinct panels" % (len(assigned), distinct))
    print()
