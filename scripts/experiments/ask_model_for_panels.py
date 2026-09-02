#!/usr/bin/env python3
"""Ask the model for the PANEL each speech unit sits in, and see if it is better.

Every difficulty in this investigation comes from balloons being small: a box that
is 10% of the page off matters enormously for a balloon occupying 3% of it. A
panel occupies 10-50%, so the same absolute error is proportionally minor -- and a
panel is a high-contrast bounded rectangle, which is an easier thing to find than
a soft white blob.

This asks for both boxes per unit on the same call and compares the panel against
the recursive X-Y cut result, which is the pixel-based alternative.
"""
import base64, io, json, os, sys, urllib.request

REPO = r'D:/Claude/apps/storyteller'
sys.path.insert(0, os.path.join(REPO, 'scripts'))
import measure_boxes as M

KEY = open(r"D:/Claude/creds/anthropic.txt").read().strip()
URL = "https://api.anthropic.com/v1/messages"

SCHEMA = json.loads('''
{"type":"object","properties":{
 "units":{"type":"array","items":{"type":"object","properties":{
   "speaker":{"type":"string"},"text":{"type":"string"},
   "bounds":{"anyOf":[{"type":"object","properties":{
     "x1":{"type":"number"},"y1":{"type":"number"},
     "x2":{"type":"number"},"y2":{"type":"number"}},
     "required":["x1","y1","x2","y2"],"additionalProperties":false},{"type":"null"}]},
   "panel":{"anyOf":[{"type":"object","properties":{
     "x1":{"type":"number"},"y1":{"type":"number"},
     "x2":{"type":"number"},"y2":{"type":"number"}},
     "required":["x1","y1","x2","y2"],"additionalProperties":false},{"type":"null"}]}},
   "required":["speaker","text","bounds","panel"],"additionalProperties":false}}},
 "required":["units"],"additionalProperties":false}''')


def instruction(w, h):
    return f"""This is a photograph of one page from a children's storybook or graphic novel.
The image is exactly {w} pixels wide and {h} pixels tall.

Return every speech unit on the page, in reading order.

For each unit:
- speaker: the character who says it, or "Narrator".
- text: reproduce it verbatim.
- bounds: the speech balloon enclosing that unit, as absolute pixel coordinates
  x1,y1 (top-left) to x2,y2 (bottom-right) in this {w} x {h} image.
- panel: the COMIC PANEL that contains that balloon, as absolute pixel
  coordinates in the same space. A panel is the framed picture the balloon sits
  in, bounded by the gutters or page edges around it -- not the balloon, and not
  the whole page unless the page really is a single full-bleed panel. Include the
  artwork, not just the balloon. Use null only if you cannot tell.
"""


def call(model, jpeg_bytes, w, h):
    body = {"model": model, "max_tokens": 3000,
            "output_config": {"format": {"type": "json_schema", "schema": SCHEMA}},
            "messages": [{"role": "user", "content": [
                {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg",
                 "data": base64.b64encode(jpeg_bytes).decode()},
                 "transformations": {"oversized_image": "error"}},
                {"type": "text", "text": instruction(w, h)}]}]}
    req = urllib.request.Request(URL, data=json.dumps(body).encode(),
        headers={"x-api-key": KEY, "anthropic-version": "2023-06-01",
                 "content-type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            j = json.load(r)
        return json.loads(next(b["text"] for b in j["content"] if b["type"] == "text")), None
    except urllib.error.HTTPError as e:
        d = e.read().decode(errors="replace")
        try:
            d = json.loads(d)["error"]["message"]
        except Exception:
            pass
        return None, "HTTP %d: %s" % (e.code, d[:200])


for name in ('page-1788289251857', 'page-1788295071078', 'page-1788294930134'):
    d = os.path.join(REPO, 'diagnostics-pulled', name)
    meta = json.load(open(os.path.join(d, 'meta.json')))
    W, H = meta['uploadWidth'], meta['uploadHeight']
    jb = open(os.path.join(d, 'page-upload.jpg'), 'rb').read()

    out, err = call("claude-sonnet-5", jb, W, H)
    print("=" * 78)
    print("%s  %dx%d" % (name, W, H))
    if err:
        print("  %s" % err)
        continue

    units = out.get("units", [])
    panels = []
    for i, u in enumerate(units):
        p = u.get("panel")
        b = u.get("bounds")
        if not p:
            print("   %-2d %-12s panel: null" % (i, u.get("speaker", "?")[:12]))
            continue
        pa = (p["x2"] - p["x1"]) * (p["y2"] - p["y1"])
        frac = pa / float(W * H)
        ba = (b["x2"] - b["x1"]) * (b["y2"] - b["y1"]) if b else 0
        contains = (b and p["x1"] <= b["x1"] and p["y1"] <= b["y1"]
                    and p["x2"] >= b["x2"] and p["y2"] >= b["y2"])
        panels.append((p["x1"], p["y1"], p["x2"], p["y2"]))
        print("   %-2d %-12s panel %4.0f,%4.0f %4.0f,%4.0f  %3.0f%% of page  %sx balloon  %s"
              % (i, u.get("speaker", "?")[:12], p["x1"], p["y1"], p["x2"], p["y2"],
                 100 * frac, ("%.1f" % (pa / ba)) if ba else "-",
                 "contains balloon" if contains else "DOES NOT contain its balloon"))

    # how many DISTINCT panels did it name?
    distinct = []
    for p in panels:
        if not any(M.iou(p, q) > 0.7 for q in distinct):
            distinct.append(p)
    print("   %d units -> %d distinct panels" % (len(units), len(distinct)))
