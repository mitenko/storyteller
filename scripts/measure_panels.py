#!/usr/bin/env python3
"""Score the model's comic-panel boxes against hand-labelled ground truth.

    python scripts/measure_panels.py <bundle-dir> <labels.json> [--live]

The panel result in docs/issues/2026-08-31-bubble-box-accuracy-measured.md section
18 rests on structural consistency and visual inspection, which is the standard
this investigation has criticised elsewhere. OCR cannot supply panel extents -- on
the labelled page it read zero words -- so the ground truth is hand-drawn, once,
and committed beside this script.

By default the panels are read from the bundle's own response.json, which is what
the app produced. With --live the same page is re-sent to the API using the app's
current prompt and schema, for measuring a change before it has reached a device.

Stop condition, per the plan: mean IoU >= 0.7 AND every unit assigned to the
correct labelled panel.
"""
import argparse
import base64
import json
import os
import sys
import traceback
import urllib.request

STOP_IOU = 0.70


def iou(a, b):
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    if ix2 <= ix1 or iy2 <= iy1:
        return 0.0
    inter = (ix2 - ix1) * (iy2 - iy1)
    aa = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1])
    ab = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    return inter / (aa + ab - inter)


def centre_in(box, outer):
    cx, cy = (box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0
    return outer[0] <= cx <= outer[2] and outer[1] <= cy <= outer[3]


def box_of(d):
    return (float(d["x1"]), float(d["y1"]), float(d["x2"]), float(d["y2"])) if d else None


def units_from_response(bundle):
    with open(os.path.join(bundle, "response.json"), encoding="utf-8") as fh:
        raw = json.load(fh)
    return [{"i": i, "speaker": u.get("speaker", "?"), "text": u.get("text", ""),
             "bounds": box_of(u.get("bounds")), "panel": box_of(u.get("panel"))}
            for i, u in enumerate(raw.get("units", []))]


def units_live(bundle, width, height):
    """Re-ask using the app's CURRENT prompt and schema, parsed out of the source.

    Reading them from the Kotlin rather than restating them here means this cannot
    drift from what the app actually sends, which is the failure that would make
    the measurement meaningless.
    """
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    schema_kt = os.path.join(repo, "app/src/main/kotlin/com/storyteller/data/page/PageSchema.kt")
    src = open(schema_kt, encoding="utf-8").read()

    schema = json.loads(src[src.index('"""\n    {', src.index("PAGE_SCHEMA")) + 4:
                            src.index('"""', src.index('"""\n    {', src.index("PAGE_SCHEMA")) + 4)])
    body = src[src.index('fun pageInstruction'):]
    body = body[body.index('"""') + 3:]
    instruction = body[:body.index('"""')]
    instruction = (instruction.replace("$width", str(width))
                              .replace("$height", str(height)).strip())

    key = open(r"D:/Claude/creds/anthropic.txt").read().strip()
    model_kt = open(os.path.join(
        repo, "app/src/main/kotlin/com/storyteller/domain/model/VisionModel.kt"),
        encoding="utf-8").read()
    model = model_kt.split('id = "')[1].split('"')[0]

    jpeg = open(os.path.join(bundle, "page-upload.jpg"), "rb").read()
    payload = {
        "model": model, "max_tokens": 3000,
        "output_config": {"format": {"type": "json_schema", "schema": schema}},
        "messages": [{"role": "user", "content": [
            {"type": "image",
             "source": {"type": "base64", "media_type": "image/jpeg",
                        "data": base64.b64encode(jpeg).decode()},
             "transformations": {"oversized_image": "error"}},
            {"type": "text", "text": instruction}]}]}
    req = urllib.request.Request(
        "https://api.anthropic.com/v1/messages", data=json.dumps(payload).encode(),
        headers={"x-api-key": key, "anthropic-version": "2023-06-01",
                 "content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        j = json.load(r)
    text = next(b["text"] for b in j["content"] if b["type"] == "text")
    print("(live call: model %s, %d input tokens)" % (model, j["usage"]["input_tokens"]))
    raw = json.loads(text)
    return [{"i": i, "speaker": u.get("speaker", "?"), "text": u.get("text", ""),
             "bounds": box_of(u.get("bounds")), "panel": box_of(u.get("panel"))}
            for i, u in enumerate(raw.get("units", []))]


def run(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bundle")
    ap.add_argument("labels")
    ap.add_argument("--live", action="store_true",
                    help="re-ask the API with the app's current prompt and schema")
    args = ap.parse_args(argv)

    labels = json.load(open(args.labels, encoding="utf-8"))
    W, H = labels["uploadWidth"], labels["uploadHeight"]
    truth = [(p, (float(p["x1"]), float(p["y1"]), float(p["x2"]), float(p["y2"])))
             for p in labels["panels"]]

    units = units_live(args.bundle, W, H) if args.live else units_from_response(args.bundle)

    print("=" * 78)
    print("%s against %s" % (os.path.basename(args.bundle.rstrip("/\\")),
                             os.path.basename(args.labels)))
    print("%d hand-labelled panels, %d units" % (len(truth), len(units)))
    print()

    # Which labelled panel SHOULD each unit be in, per the labels' own expectation.
    expected_of = {}
    for idx, (meta, _) in enumerate(truth):
        for u in meta.get("expectedUnits", []):
            expected_of[u] = idx

    print("  %-3s %-10s %-24s %-24s %6s  %s"
          % ("#", "speaker", "model panel", "labelled panel", "IoU", "assignment"))
    ious, wrong, missing = [], 0, 0
    got_for_label = {}
    for u in units:
        want = expected_of.get(u["i"])
        if u["panel"] is None:
            missing += 1
            print("  %-3d %-10s %-24s %-24s %6s  no panel returned"
                  % (u["i"], u["speaker"][:10], "-",
                     "p%s" % want if want is not None else "-", "-"))
            continue
        # Assign by which labelled panel the model's panel centre falls in.
        assigned = next((i for i, (_, t) in enumerate(truth) if centre_in(u["panel"], t)), None)
        ok = assigned is not None and assigned == want
        if not ok:
            wrong += 1
        t = truth[want][1] if want is not None else None
        v = iou(u["panel"], t) if t else 0.0
        ious.append(v)
        got_for_label.setdefault(want, []).append(tuple(round(c) for c in u["panel"]))
        print("  %-3d %-10s %-24s %-24s %6.3f  %s"
              % (u["i"], u["speaker"][:10],
                 "%.0f,%.0f %.0f,%.0f" % u["panel"],
                 ("%.0f,%.0f %.0f,%.0f" % t) if t else "-",
                 v, "p%s ok" % want if ok else "WRONG PANEL (got p%s, want p%s)" % (assigned, want)))

    print()
    mean = sum(ious) / len(ious) if ious else 0.0
    print("mean IoU over %d units with a panel: %.3f" % (len(ious), mean))
    print("units in the wrong panel: %d" % wrong)
    print("units with no panel returned: %d" % missing)

    # Section 18's stability claim, checked rather than asserted.
    unstable = {k: v for k, v in got_for_label.items() if len(set(v)) > 1}
    if unstable:
        print("UNSTABLE: units sharing a labelled panel got different model panels:")
        for k, v in unstable.items():
            print("  p%s -> %s" % (k, sorted(set(v))))
    else:
        print("stable: every unit sharing a labelled panel got an identical model panel")

    print()
    passed = mean >= STOP_IOU and wrong == 0 and missing == 0
    print("VERDICT: mean IoU %.3f vs %.2f, %d wrong, %d missing -- %s"
          % (mean, STOP_IOU, wrong, missing, "PASS" if passed else "FAIL"))
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
