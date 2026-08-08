#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Flowchart renderer. graphviz (dot) is used ONLY as a positioning engine:
we feed it sized, label-less boxes, read node coordinates back via `-Tplain`,
then route the connectors and draw the styled SVG OURSELVES. graphviz 2.43's
ortho splines are unreliable for non-aligned edges, so we do NOT use its edge
routes — only node positions. Connectors are orthogonal with rounded corners,
and sibling branches share a common "bus" line so their turns are level.

Usage: python render_flow.py <semantic-map.json> <out.svg>
"""
import sys, math, subprocess
from collections import defaultdict
from common import (C, FONT, FS, TITLE_FONT, TOKENS, esc, wrap, text_w, load_map,
                    arrow_marker, head_trim)

FC = TOKENS["flow_colors"]
FS_TITLE_DOC = FS["doc_title"]
FS_T, FS_L, LH_T, LH_L = FS["node_title"], FS["subtitle"], 22, 18
FS_CAP = FS["edge_label"]
PADX, PADY = 16, 13
MAXW = 340
FORK = 26
HEX_INS = TOKENS["radius"]["decision_hex_inset"]   # angled end width of the decision hexagon
HEX_R = TOKENS["radius"]["corner"]                 # r≈2.5 rounding on hexagon vertices
TARGET_MIN = 0.66          # pad sides if skinnier than ~A4 portrait
EW = TOKENS["stroke"]["connector"]                 # one connector weight in a flow

def tw(s, fs): return text_w(s, fs)

def node_layout(n):
    lt = wrap(n["title"], FS_T, MAXW)
    ld = []
    for ln in n.get("lines", []):
        ld += wrap(ln, FS_L, MAXW)
    cw_ = max([tw(x, FS_T) for x in lt] + [tw(x, FS_L) for x in ld] + [1])
    ch_ = len(lt)*LH_T + len(ld)*LH_L
    return lt, ld, cw_, ch_

_THEME = None            # set by the dispatcher; "guizang" -> squarer, roomier boxes

def box_size(n):
    lt, ld, cw_, ch_ = node_layout(n)
    _T = TOKENS["tuning"]
    px, py = ((_T["guizang_pad_x"], _T["guizang_pad_y"])
              if _THEME == "guizang" else (PADX, PADY))   # 歸藏风: roomier, squarer
    w, h = cw_ + 2*px, ch_ + 2*py
    if n.get("kind","step") == "decision":
        # hexagon / diamond: taller box so the shape reads as a real decision and,
        # in 歸藏风, becomes a proper (non-flat) diamond whose vertices are exactly
        # where connectors land.
        w, h = cw_ + 2*px + 2*HEX_INS, ch_ + 2*py + _T["guizang_decision_lift"]
    if n.get("kind","step") == "terminal":
        w, h = w + 24, ch_ + 2*py + 6
    return w, h

def _aliases(m):
    # graphviz ids must be safe tokens; the model may use Chinese / spaces, so we
    # map every node to an internal alias and translate back after layout.
    return {n["id"]: f"N{i}" for i, n in enumerate(m["nodes"])}

def build_dot(m):
    al = _aliases(m)
    L = ['digraph G {', f'  rankdir={m.get("direction","TB")}; splines=ortho;',
         f'  nodesep={TOKENS["tuning"]["flow_nodesep"]}; ranksep={TOKENS["tuning"]["flow_ranksep"]};', '  node [shape=box, fixedsize=true];']
    # one uniform width for every STEP box: columns line up (fewer connector
    # bends) and the diagram stops mixing many box sizes.
    step_cw = [node_layout(n)[2] for n in m["nodes"] if n.get("kind", "step") == "step"]
    uni_step_w = (max(step_cw) + 2 * PADX) if step_cw else None
    for n in m["nodes"]:
        w, h = box_size(n)
        if uni_step_w and n.get("kind", "step") == "step":
            w = uni_step_w
        L.append(f'  {al[n["id"]]} [label="{al[n["id"]]}", width={w/72:.3f}, height={h/72:.3f}];')
    for e in m["edges"]:
        L.append(f'  {al[e["from"]]} -> {al[e["to"]]};')
    # optional explicit layering: when nodes carry a "col", lock same-col nodes
    # into one graphviz rank so multi-input dataflow diagrams render as parallel
    # columns (input -> process -> merge -> output) instead of a dot tangle.
    cols = {}
    for n in m["nodes"]:
        if "col" in n:
            cols.setdefault(n["col"], []).append(al[n["id"]])
    for c in sorted(cols):
        if len(cols[c]) > 1:
            L.append("  { rank=same; " + "; ".join(cols[c]) + " }")
    L.append('}')
    return "\n".join(L)

def run_dot(dot_src):
    try:
        p = subprocess.run(["dot", "-Tplain"], input=dot_src, capture_output=True, text=True, check=True)
    except FileNotFoundError:
        raise RuntimeError("graphviz 'dot' not found on PATH. Flowchart and "
                           "relationship layouts need it — install graphviz "
                           "(e.g. `apt-get install graphviz`).")
    nodes, gh = {}, 0.0
    for ln in p.stdout.splitlines():
        t = ln.split()
        if t and t[0] == "graph":
            gh = float(t[3])
        elif t and t[0] == "node":
            nodes[t[1]] = (float(t[2]), float(t[3]), float(t[4]), float(t[5]))
    return nodes, gh

def rounded(pts, r=2.5):
    pts = [p for i, p in enumerate(pts) if i == 0 or abs(p[0]-pts[i-1][0]) > 0.5 or abs(p[1]-pts[i-1][1]) > 0.5]
    if len(pts) < 2:
        return ""
    d = [f'M {pts[0][0]:.1f},{pts[0][1]:.1f}']
    for i in range(1, len(pts)-1):
        p0, p1, p2 = pts[i-1], pts[i], pts[i+1]
        v1 = (p1[0]-p0[0], p1[1]-p0[1]); l1 = math.hypot(*v1) or 1
        v2 = (p2[0]-p1[0], p2[1]-p1[1]); l2 = math.hypot(*v2) or 1
        rr = min(r, l1/2, l2/2)
        a = (p1[0]-v1[0]/l1*rr, p1[1]-v1[1]/l1*rr)
        b = (p1[0]+v2[0]/l2*rr, p1[1]+v2[1]/l2*rr)
        d.append(f'L {a[0]:.1f},{a[1]:.1f} Q {p1[0]:.1f},{p1[1]:.1f} {b[0]:.1f},{b[1]:.1f}')
    d.append(f'L {pts[-1][0]:.1f},{pts[-1][1]:.1f}')
    return " ".join(d)

def rounded_poly(pts, r=2.5):
    """Closed polygon with every vertex shaved to a small radius (r≈2.5, near
    right-angle) — matches the skill's corner rule for the decision hexagon."""
    n = len(pts)
    if n < 3:
        return ""
    d = []
    for i in range(n):
        p0, p1, p2 = pts[(i-1) % n], pts[i], pts[(i+1) % n]
        v1 = (p1[0]-p0[0], p1[1]-p0[1]); l1 = math.hypot(*v1) or 1
        v2 = (p2[0]-p1[0], p2[1]-p1[1]); l2 = math.hypot(*v2) or 1
        rr = min(r, l1/2, l2/2)
        a = (p1[0]-v1[0]/l1*rr, p1[1]-v1[1]/l1*rr)
        b = (p1[0]+v2[0]/l2*rr, p1[1]+v2[1]/l2*rr)
        d.append((f'M {a[0]:.1f},{a[1]:.1f}' if i == 0 else f'L {a[0]:.1f},{a[1]:.1f}')
                 + f' Q {p1[0]:.1f},{p1[1]:.1f} {b[0]:.1f},{b[1]:.1f}')
    d.append('Z')
    return " ".join(d)

def render(m):
    raw, gh = run_dot(build_dot(m))
    inv = {v: k for k, v in _aliases(m).items()}
    nodes = {inv[a]: pos for a, pos in raw.items()}
    S = lambda v: v*72
    Y = lambda y: (gh - y)*72
    H = gh*72
    doc_title_h = 64
    H_total = H + doc_title_h + 68
    yshift = doc_title_h + 48
    # symmetric horizontal framing: measure the real content bounds and pad both
    # sides equally, so the content (and the centered title) sit in the true middle.
    lefts = [x*72 - w*72/2 for (x, y, w, h) in nodes.values()]
    rights = [x*72 + w*72/2 for (x, y, w, h) in nodes.values()]
    cl, cr = min(lefts), max(rights)
    content_w = cr - cl
    SIDE = 60
    Wc = content_w + 2*SIDE
    pad = max(0.0, (H_total*TARGET_MIN - Wc) / 2)   # extra symmetric pad to reach ~A4 portrait
    xoff = SIDE - cl + pad                           # shift content so both margins are equal
    W = content_w + 2*SIDE + 2*pad
    by_id = {n["id"]: n for n in m["nodes"]}

    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W:.0f}" height="{H_total:.0f}" viewBox="0 0 {W:.0f} {H_total:.0f}" font-family="{FONT}">',
           '<defs>' + arrow_marker('ar', FC["line"], width=EW) + '</defs>',
           f'<rect width="{W:.0f}" height="{H_total:.0f}" fill="{C["bg"]}"/>',
           f'<text x="{W/2:.0f}" y="44" font-size="{FS_TITLE_DOC}" font-weight="700" font-family="{TITLE_FONT}" '
           f'fill="{C["ink"]}" stroke="{C["ink"]}" stroke-width="0.3" text-anchor="middle">{esc(m["title_text"])}</text>']

    geo = {}
    for nid, (x, y, w, h) in nodes.items():
        cx, cy = S(x)+xoff, Y(y)+yshift
        geo[nid] = {"cx": cx, "cy": cy, "top": cy - S(h)/2, "bottom": cy + S(h)/2,
                    "left": cx - S(w)/2, "right": cx + S(w)/2}
    nchild, npar = defaultdict(int), defaultdict(int)
    for e in m["edges"]:
        nchild[e["from"]] += 1; npar[e["to"]] += 1

    horizontal = m.get("direction", "TB") == "LR"
    out.append('<g data-role="edges">')
    # Head-room is DERIVED, not guessed: the line stops far enough back that the
    # arrow TIP keeps its breathing room while the head stays sharp (see
    # common.arrow_geom). Grows automatically if the line weight ever changes.
    GAP = head_trim(width=EW)
    for e in m["edges"]:
        a, b = geo[e["from"]], geo[e["to"]]
        lanchor = "middle"
        if horizontal:
            # LR flow: connect source RIGHT -> target LEFT; buses run vertically.
            sx, sy, ex, ey = a["right"], a["cy"], b["left"] - GAP, b["cy"]
            if b["cx"] <= a["cx"] + 1:            # back-edge -> route along the bottom channel
                by = H_total - 20
                pts = [(a["cx"], a["bottom"]), (a["cx"], by), (b["cx"], by), (b["cx"], b["bottom"] + GAP)]
                lx, ly = (a["cx"] + b["cx"]) / 2, by + 16
            elif abs(sy - ey) < 8:                # same row -> straight horizontal
                pts = [(sx, sy), (ex, sy)]
                lx, ly = (sx + ex) / 2, sy - 10
            elif nchild[e["from"]] > 1:           # fan-out -> vertical bus right of the source
                busx = a["right"] + FORK
                pts = [(sx, sy), (busx, sy), (busx, ey), (ex, ey)]
                lx, ly = busx + 8, (sy + ey) / 2, 
                lanchor = "start"
            elif npar[e["to"]] > 1:               # fan-in -> vertical bus left of the target
                busx = b["left"] - FORK
                pts = [(sx, sy), (busx, sy), (busx, ey), (ex, ey)]
                lx, ly = busx - 8, (sy + ey) / 2
                lanchor = "end"
            else:                                 # single edge, offset rows -> one small jog near the head
                jog = min(16, max(6, abs(ex - sx) * 0.28))
                pts = [(sx, sy), (ex - jog, sy), (ex - jog, ey), (ex, ey)]
                lx, ly = (sx + ex) / 2, min(sy, ey) - 8
        else:
            sx, sy, ex, ey = a["cx"], a["bottom"], b["cx"], b["top"] - GAP
            if b["cy"] <= a["cy"] + 1:            # back-edge / loop -> route on the right
                rx = W - 18
                pts = [(a["right"], a["cy"]), (rx, a["cy"]), (rx, b["cy"]), (b["right"] + GAP, b["cy"])]
                lx, ly, lanchor = rx - 8, (a["cy"] + b["cy"]) / 2, "end"
            elif abs(sx - ex) < 8:                # (near-)aligned -> straight vertical, no bend
                pts = [(sx, sy), (sx, ey)]
                lx, ly, lanchor = sx + 8, (sy + ey) / 2 + 4, "start"
            elif nchild[e["from"]] > 1:           # fan-out -> centred above this branch's bus
                busy = a["bottom"] + FORK
                pts = [(sx, sy), (sx, busy), (ex, busy), (ex, ey)]
                lx, ly = (sx + ex) / 2, busy - 8
            elif npar[e["to"]] > 1:               # fan-in -> centred above the bus
                busy = b["top"] - FORK
                pts = [(sx, sy), (sx, busy), (ex, busy), (ex, ey)]
                lx, ly = (sx + ex) / 2, busy - 8
            else:                                 # single edge, offset columns -> one small jog near the head
                jog = min(16, max(6, (ey - sy) * 0.28))
                pts = [(sx, sy), (sx, ey - jog), (ex, ey - jog), (ex, ey)]
                lx, ly = (sx + ex) / 2, ey - jog - 8
        out.append(f'<path d="{rounded(pts)}" fill="none" stroke="{FC["line"]}" stroke-width="{EW}" marker-end="url(#ar)"/>')
        if e.get("label"):   # text beside/above the line — NO box, so the connector stays intact
            out.append(f'<text x="{lx:.1f}" y="{ly:.1f}" font-size="{FS_CAP}" font-weight="600" '
                       f'fill="{C["ink2"]}" text-anchor="{lanchor}">{esc(e["label"])}</text>')
    out.append('</g>')

    out.append('<g data-role="nodes">')
    for nid, (x, y, w, h) in nodes.items():
        n = by_id[nid]
        cx, cy = S(x)+xoff, Y(y)+yshift
        bw, bh = S(w), S(h)
        emph = n.get("emphasis")
        lt, ld, _, ch_ = node_layout(n)
        kind = n.get("kind","step")
        if emph:
            fill, stroke, tcol, dcol = C["red"], C["red"], C["white"], C["white"]
        elif kind == "terminal":
            fill, stroke, tcol, dcol = FC["terminal_fill"], FC["terminal_fill"], FC["terminal_txt"], FC["terminal_txt"]
        elif kind == "decision":
            fill, stroke, tcol, dcol = FC["decision_fill"], FC["decision_stroke"], C["ink"], C["ink2"]
        else:
            fill, stroke, tcol, dcol = FC["step_fill"], FC["step_stroke"], C["ink"], C["ink2"]

        out.append(f'<g data-role="node" data-id="{nid}">')
        if kind == "decision":
            ins = min(HEX_INS, bw/2 - 4)
            hexpts = [(cx-bw/2, cy), (cx-bw/2+ins, cy-bh/2), (cx+bw/2-ins, cy-bh/2),
                      (cx+bw/2, cy), (cx+bw/2-ins, cy+bh/2), (cx-bw/2+ins, cy+bh/2)]
            # The decision node is the one shape that exists ONLY as an outline —
            # white fill, grey rule — so its border is functional, not decorative,
            # and carries a heavier weight than the other modules. Kept an INTEGER
            # so its two horizontal edges rasterise at equal weight (see §5).
            out.append(f'<path d="{rounded_poly(hexpts, HEX_R)}" fill="{fill}" '
                       f'stroke="{stroke}" stroke-width="2"/>')
        elif kind == "terminal":
            out.append(f'<rect x="{cx-bw/2:.1f}" y="{cy-bh/2:.1f}" width="{bw:.1f}" height="{bh:.1f}" '
                       f'rx="{bh/2:.1f}" fill="{fill}" stroke="{stroke}" stroke-width="1"/>')
        else:
            out.append(f'<rect x="{cx-bw/2:.1f}" y="{cy-bh/2:.1f}" width="{bw:.1f}" height="{bh:.1f}" '
                       f'rx="12" fill="{fill}"/>')
        ty = cy - ch_/2 + FS_T
        for ln in lt:
            out.append(f'<text x="{cx:.1f}" y="{ty:.1f}" font-size="{FS_T}" font-weight="700" fill="{tcol}" text-anchor="middle">{esc(ln)}</text>')
            ty += LH_T
        for ln in ld:
            out.append(f'<text x="{cx:.1f}" y="{ty:.1f}" font-size="{FS_L}" fill="{dcol}" text-anchor="middle">{esc(ln)}</text>')
            ty += LH_L
        out.append('</g>')
    out.append('</g></svg>')
    return "\n".join(out), int(W), int(H_total)

def main(mapfile, out):
    svg, w, h = render(load_map(mapfile))
    open(out, "w", encoding="utf-8").write(svg)
    print(f"[flow] wrote {out}  {w}x{h}  ratio={w/h:.2f}")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "out.svg")
