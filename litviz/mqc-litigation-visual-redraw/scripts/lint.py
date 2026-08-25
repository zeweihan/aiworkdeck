#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Final-SVG lint — the artifact-level checks that are easiest to miss on a
fixture but obvious in a browser (inspired by Archify's check-render-output).
Read-only: it inspects the produced SVG and reports warnings; it never changes
how anything is drawn.

Checks:
  1. non-finite numbers leaked into attributes (nan / inf / None)
  2. elements whose box falls outside the canvas (clipping)
  3. arrows drawn as a single diagonal line (should be orthogonal)

Usage:  python lint.py <file.svg>       (exit 1 if any warning)
        from render.py: lint_svg(svg, w, h) -> list[str]
"""
import sys, re

_NUM = r'-?\d+(?:\.\d+)?'

# Explicitly-rejected blue / slate families (Tailwind slate + common blues). Our
# own neutral grays are mildly cool and are intentionally NOT in this list.
_REJECTED_BLUE = {
    "#0F172A", "#1E293B", "#334155", "#475569", "#64748B", "#94A3B8",
    "#CBD5E1", "#E2E8F0", "#F1F5F9", "#0EA5E9", "#3B82F6", "#2563EB",
    "#1D4ED8", "#1E40AF", "#60A5FA", "#93C5FD",
}


def _canvas(svg):
    m = re.search(r'<svg[^>]*width="(\d+)"[^>]*height="(\d+)"', svg)
    if not m:
        return None, None
    return float(m.group(1)), float(m.group(2))


def lint_svg(svg, w=None, h=None):
    warns = []
    if w is None or h is None:
        w, h = _canvas(svg)

    # 1. non-finite numbers in numeric attributes (case-sensitive: Python emits
    #    'nan'/'inf'; capital 'None' won't collide with the valid fill="none")
    if re.search(r'"[^"]*\b(?:nan|inf|Infinity|NaN|None)\b[^"]*"', svg):
        warns.append("non-finite value (nan/inf/None) leaked into an SVG attribute")

    # 2. off-canvas boxes (rects) and text anchors
    if w and h:
        tol = 2.0
        for mm in re.finditer(
                rf'<rect x="({_NUM})" y="({_NUM})" width="({_NUM})" height="({_NUM})"', svg):
            x, y, ww, hh = map(float, mm.groups())
            if x < -tol or y < -tol or x + ww > w + tol or y + hh > h + tol:
                warns.append(f"rect off-canvas at x={x:.0f},y={y:.0f} ({ww:.0f}x{hh:.0f}) vs {w:.0f}x{h:.0f}")
        for mm in re.finditer(rf'<text x="({_NUM})" y="({_NUM})"', svg):
            x, y = float(mm.group(1)), float(mm.group(2))
            if x < -tol or y < -tol or x > w + tol or y > h + tol:
                warns.append(f"text anchor off-canvas at x={x:.0f},y={y:.0f} vs {w:.0f}x{h:.0f}")

        # 2b. text whose RENDERED WIDTH spills off the canvas. The anchor can sit
        # comfortably inside while the glyphs run past the edge — that is exactly how
        # an over-long title or note gets clipped, so check the extent, not the point.
        for mm in re.finditer(rf'<text ([^>]*?)x="({_NUM})"([^>]*?)>([^<]*)</text>', svg):
            attrs = mm.group(1) + mm.group(3)
            fsm = re.search(rf'font-size="({_NUM})"', attrs)
            if not fsm:
                continue
            x, fs, txt = float(mm.group(2)), float(fsm.group(1)), mm.group(4)
            am = re.search(r'text-anchor="(\w+)"', attrs)
            anchor = am.group(1) if am else "start"
            if not txt.strip():
                continue
            # CJK ≈ full em, Latin ≈ 0.55 em
            tw = sum(fs if ord(c) > 0x2E80 else fs * 0.55 for c in txt)
            left = x - tw / 2 if anchor == "middle" else (x - tw if anchor == "end" else x)
            if left < -tol or left + tw > w + tol:
                warns.append(f'text overflows canvas: "{txt[:18]}…" ({tw:.0f}px) at x={x:.0f} vs {w:.0f}')

    # 3. arrowed path that is a single diagonal segment (should be orthogonal)
    for mm in re.finditer(r'<path d="([^"]+)"[^>]*marker-end=', svg):
        d = mm.group(1)
        pts = re.findall(rf'({_NUM}),({_NUM})', d)
        if "Q" not in d and len(pts) == 2:
            (x1, y1), (x2, y2) = ([float(a) for a in p] for p in pts)
            if abs(x1 - x2) > 1.5 and abs(y1 - y2) > 1.5:
                warns.append(f"diagonal arrow ({x1:.0f},{y1:.0f})->({x2:.0f},{y2:.0f}) — should be orthogonal")

    # 4. rejected blue / slate palette (the "no blue" standard, as an artifact check).
    #    A blacklist, not a blue-channel test — our neutral grays are mildly cool and
    #    must not be false-flagged; only the explicitly-rejected families are caught.
    for mm in re.finditer(r'(?:fill|stroke)="(#[0-9A-Fa-f]{6})"', svg):
        if mm.group(1).upper() in _REJECTED_BLUE:
            warns.append(f"rejected blue/slate colour {mm.group(1)} (palette is neutral gray + one deep red)")

    # 5. marker sanity: orient must be "auto" (never the deprecated auto-start-reverse)
    for mm in re.finditer(r'<marker\b[^>]*>', svg):
        if 'auto-start-reverse' in mm.group(0) or 'orient="auto"' not in mm.group(0):
            warns.append("marker orient is not \"auto\" (deprecated/absent orient rotates arrows wrong)")

    # 5b. TEXT SPILLING OUT OF ITS OWN BOX, and TEXT COLLIDING WITH TEXT.
    #     Check 2b already catches a line that runs off the CANVAS; these are the
    #     two failures that stay inside the canvas and still make a figure
    #     unusable — a caption wider than the card it sits in, and two labels
    #     printed on top of each other. Both are invisible to every other check
    #     here, and both are exactly what a reader notices first.
    boxes = []
    for m in re.finditer(r"<rect\b[^>]*/>", svg):
        t = m.group(0)
        g = lambda a: re.search(r'\b' + a + r'="(-?[\d.]+)"', t)
        if all(g(a) for a in ("x", "y", "width", "height")):
            x, y, bw, bh = (float(g(a).group(1)) for a in ("x", "y", "width", "height"))
            boxes.append((x, y, bw, bh))
    texts = []
    for m in re.finditer(r'<text\b([^>]*)>(.*?)</text>', svg, re.S):
        a, body = m.group(1), re.sub(r"<[^>]+>", "", m.group(2))
        body = body.strip()
        if not body:
            continue
        g = lambda k: re.search(k + r'="(-?[\d.]+)"', a)
        if not (g("x") and g("y")):
            continue
        tx, ty = float(g("x").group(1)), float(g("y").group(1))
        fs = float(g("font-size").group(1)) if g("font-size") else 13.0
        tr = float(g("letter-spacing").group(1)) if g("letter-spacing") else 0.0
        wpx = sum(fs if ord(c) > 0x2E7F else fs * 0.55 for c in body) + tr * max(0, len(body) - 1)
        anch = re.search(r'text-anchor="(\w+)"', a)
        anch = anch.group(1) if anch else "start"
        x0 = tx - (wpx / 2 if anch == "middle" else (wpx if anch == "end" else 0))
        texts.append((x0, ty - fs * 0.86, wpx, fs * 1.02, body))

    for x0, y0, wpx, hpx, body in texts:
        # the smallest box that contains the text's anchor point is its container
        host = None
        for bx, by, bw, bh in boxes:
            if bx <= x0 + wpx / 2 <= bx + bw and by <= y0 + hpx / 2 <= by + bh:
                if host is None or bw * bh < host[2] * host[3]:
                    host = (bx, by, bw, bh)
        if host and wpx > host[2] + 1.0:
            warns.append(f"text overflows its box: {body[:20]!r} needs "
                            f"{wpx:.0f}px inside a {host[2]:.0f}px module")

    for i in range(len(texts)):
        ax, ay, aw, ah, at = texts[i]
        for j in range(i + 1, len(texts)):
            bx, by, bw2, bh2, bt = texts[j]
            ox = min(ax + aw, bx + bw2) - max(ax, bx)
            oy = min(ay + ah, by + bh2) - max(ay, by)
            # a real collision, not two lines of one caption brushing past each other
            if ox > 2.0 and oy > min(ah, bh2) * 0.5:
                warns.append(f"text overlaps text: {at[:16]!r} and {bt[:16]!r} "
                                f"share {ox:.0f}x{oy:.0f}px")
                break

    # 6. well-formed XML (a malformed SVG rasterizes to nothing)
    try:
        import xml.etree.ElementTree as ET
        ET.fromstring(svg)
    except Exception as e:
        warns.append(f"SVG is not well-formed XML: {e}")

    # 7. reference integrity: every url(#id) must resolve to a defined id
    defined = set(re.findall(r'\bid="([^"]+)"', svg))
    for ref in re.findall(r'url\(#([^)]+)\)', svg):
        if ref not in defined:
            warns.append(f'dangling reference url(#{ref}) — no element defines id="{ref}"')

    # de-dupe while keeping order
    seen, out = set(), []
    for wn in warns:
        if wn not in seen:
            seen.add(wn); out.append(wn)
    return out


def main(path):
    svg = open(path, encoding="utf-8").read()
    warns = lint_svg(svg)
    if warns:
        print(f"lint: {len(warns)} warning(s) in {path}")
        for w in warns:
            print("  - " + w)
        return 1
    print(f"lint: clean — {path}")
    return 0



def _quiet_broken_pipe():
    """`… | head` closes the pipe early; without this the script ends on a
    traceback, which looks like a crash to anyone reading the terminal."""
    import signal
    try:
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    except (AttributeError, ValueError):
        pass

if __name__ == "__main__":
    _quiet_broken_pipe()
    sys.exit(main(sys.argv[1]))
