#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Hierarchical relationship / subject tree (主体关系图) — an org-chart-style
top-down hierarchy: 实际控制人 → 控股公司 → 子公司, 集团结构, 股权/控制层级.

graphviz (dot, TB) positions the nodes; we draw the cards, the bracket
connectors (each 90° turn shaved to a small r≈2.5 radius, like the rest of the
skill), per-node notes and optional edge labels (持股比例 …) ourselves. Node
shading follows the hierarchy DEPTH (top = dark solid, middle = mid gray, leaves
= light card) — this is aesthetic level-coding, NOT legal semantics; only deep
red carries meaning. Structural edges have NO arrowheads by default (a hierarchy
line, not a directed relationship); set "arrows": true to add them.

Usage: python render_tree.py <semantic-map.json> <out.svg>
"""
import sys, math
from collections import defaultdict, deque
from common import (C, FONT, FS, TITLE_FONT, TOKENS, esc, wrap, text_w, load_map,
                    arrow_marker, head_trim)

FS_T = FS["node_title"]           # 17
FS_NOTE = FS["subtitle"]          # 13
FS_EDGE = FS["edge_label"]        # 13
LH_T, LH_NOTE = 22, 20
PADX, PADY = 20, 14
_THEME = None
MINW = 132
MAXW = 230
SW_N = 1.6                         # bracket connectors are lighter than flow/relation lines
SW_E = TOKENS["stroke"]["emphasis"]
FORK = 26                          # bus distance below a parent
NOTE_GAP = 26
MARGIN = 70
COLGAP = 46                        # horizontal gap added to the equal leaf slot
ROWGAP = 74                        # vertical gap between levels (room for the bus + labels)

# depth-based shading ramp (neutral gray only; red is reserved for emphasis)
def level_fill(level, maxlevel):
    if level == 0:
        return dict(fill="#374151", text=C["white"], border=None)          # root: dark solid
    if level >= maxlevel:
        return dict(fill="#EDEFF2", text=C["ink"], border=C["card_stroke"]) # leaf: light card
    # middle level(s): mid gray, lighten slightly with depth
    mids = ["#C9CED4", "#DADEE3", "#E4E7EB"]
    return dict(fill=mids[min(level - 1, len(mids) - 1)], text=C["ink"], border=None)


def _assert_tree(m):
    """relation_tree can only faithfully draw a STRICT hierarchy. If the input is
    really a network (a node with two parents, a cycle, or an unreachable node),
    refuse loudly and point to graphviz_relation — a clean-looking tree of the
    wrong structure is the worst failure mode."""
    ids = [n["id"] for n in m["nodes"]]
    indeg = {i: 0 for i in ids}
    kids = defaultdict(list)
    for e in m["edges"]:
        if e["to"] in indeg:
            indeg[e["to"]] += 1
        kids[e["from"]].append(e["to"])
    multi = [i for i in ids if indeg[i] > 1]
    if multi:
        raise RuntimeError(
            "relation_tree needs a strict hierarchy (each node has exactly one "
            f"parent). Multi-parent node(s): {', '.join(multi)}. This is a network, "
            "not a tree — use layout 'graphviz_relation'.")
    roots = [i for i in ids if indeg[i] == 0]
    if not roots:
        raise RuntimeError("relation_tree has no root (every node has a parent) — "
                           "likely a cycle. Use layout 'graphviz_relation'.")
    seen = set(roots)
    q = deque(roots)
    while q:
        u = q.popleft()
        for v in kids[u]:
            if v not in seen:
                seen.add(v); q.append(v)
    unreached = [i for i in ids if i not in seen]
    if unreached:
        raise RuntimeError(
            f"relation_tree: node(s) {', '.join(unreached)} not reachable from a "
            "root (cycle or disconnected graph) — use layout 'graphviz_relation'.")


def _levels(m):
    """Compute each node's depth from the forest roots (indegree 0). Honors an
    explicit node 'level' if provided."""
    ids = [n["id"] for n in m["nodes"]]
    indeg = {i: 0 for i in ids}
    kids = defaultdict(list)
    for e in m["edges"]:
        indeg[e["to"]] += 1
        kids[e["from"]].append(e["to"])
    lvl = {}
    q = deque()
    for n in m["nodes"]:
        if "level" in n:
            lvl[n["id"]] = n["level"]
    for i in ids:
        if indeg[i] == 0:
            lvl.setdefault(i, 0)
            q.append(i)
    seen = set(q)
    while q:
        u = q.popleft()
        for v in kids[u]:
            if v not in lvl:
                lvl[v] = lvl[u] + 1
            if v not in seen:
                seen.add(v)
                q.append(v)
    for i in ids:
        lvl.setdefault(i, 0)
    return lvl


def node_layout(n):
    lines = wrap(n["title"], FS_T, MAXW)
    cw = max([text_w(l, FS_T) for l in lines] + [1])
    return lines, cw


def rounded(pts, r=2.5):
    pts = [p for i, p in enumerate(pts)
           if i == 0 or abs(p[0]-pts[i-1][0]) > 0.5 or abs(p[1]-pts[i-1][1]) > 0.5]
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


def render(m):
    _assert_tree(m)
    lvl = _levels(m)
    maxlevel = max(lvl.values()) if lvl else 0
    by = {n["id"]: n for n in m["nodes"]}

    # measure content once
    lines_by, cw_by = {}, {}
    for n in m["nodes"]:
        lines, cw = node_layout(n)
        lines_by[n["id"]] = lines
        cw_by[n["id"]] = cw

    # UNIFORM height for every box, so no level looks thicker than another
    uni_h = max(len(lines_by[n["id"]]) for n in m["nodes"]) * LH_T + 2 * (26 if _THEME == "guizang" else PADY)
    # UNIFORM width PER LEVEL, so boxes line up in tidy columns instead of jumping
    level_w = {}
    for n in m["nodes"]:
        L = lvl[n["id"]]
        level_w[L] = max(level_w.get(L, MINW), cw_by[n["id"]] + 2 * PADX)
    sizes = {n["id"]: (level_w[lvl[n["id"]]], uni_h) for n in m["nodes"]}

    # --- deterministic tidy-tree layout (no graphviz): leaves get equal slots;
    #     each parent sits at the MIDPOINT of its children, so every fork is
    #     symmetric and sibling gaps are uniform. ---
    kids = defaultdict(list)
    indeg = defaultdict(int)
    ids = [n["id"] for n in m["nodes"]]
    for e in m["edges"]:
        kids[e["from"]].append(e["to"]); indeg[e["to"]] += 1
    roots = [i for i in ids if indeg[i] == 0] or [ids[0]]

    pitch = max(w for w, _ in sizes.values()) + COLGAP
    row_pitch = uni_h + ROWGAP
    xc, seat, seen = {}, [0], set()

    def place(u):
        if u in seen:
            return
        seen.add(u)
        ch = [c for c in kids[u] if c not in seen]
        if not ch:
            xc[u] = seat[0] * pitch; seat[0] += 1
        else:
            for c in ch:
                place(c)
            cs = [xc[c] for c in kids[u] if c in xc]
            xc[u] = sum(cs) / len(cs)
    for r in roots:
        place(r)
    for i in ids:
        if i not in xc:
            xc[i] = seat[0] * pitch; seat[0] += 1

    title_h = 98
    has_notes = any(n.get("note") for n in m["nodes"])
    note_room = (NOTE_GAP + 2 * LH_NOTE) if has_notes else 20

    geo = {}
    for nid in ids:
        w, h = sizes[nid]
        cx = xc[nid]
        cy = title_h + uni_h / 2 + lvl[nid] * row_pitch
        geo[nid] = dict(cx=cx, cy=cy, w=w, h=h, top=cy-h/2, bottom=cy+h/2,
                        left=cx-w/2, right=cx+w/2)

    xs = [g["left"] for g in geo.values()] + [g["right"] for g in geo.values()]
    minx, maxx = min(xs), max(xs)
    dx = MARGIN - minx
    for g in geo.values():
        g["cx"] += dx; g["left"] += dx; g["right"] += dx
    W = (maxx - minx) + 2 * MARGIN
    H = max(g["bottom"] for g in geo.values()) + note_room + MARGIN

    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W:.0f}" height="{H:.0f}" viewBox="0 0 {W:.0f} {H:.0f}" font-family="{FONT}">',
           '<defs>' + arrow_marker('ag', C["line"], width=SW_N) + arrow_marker('ar', C["red"], size=14, width=SW_E) + '</defs>',
           f'<rect width="{W:.0f}" height="{H:.0f}" fill="{C["bg"]}"/>',
           f'<text x="{W/2:.0f}" y="46" font-size="{FS["doc_title"]}" font-weight="700" font-family="{TITLE_FONT}" '
           f'fill="{C["ink"]}" stroke="{C["ink"]}" stroke-width="0.3" text-anchor="middle">{esc(m["title_text"])}</text>']

    arrows = bool(m.get("arrows"))
    kids = defaultdict(list)
    emph_edge = {}
    lbl_edge = {}
    for e in m["edges"]:
        kids[e["from"]].append(e["to"])
        emph_edge[(e["from"], e["to"])] = e.get("emphasis")
        if e.get("label"):
            lbl_edge[(e["from"], e["to"])] = e["label"]

    # bracket connectors (rounded corners); labels on the child drop, no box
    out.append('<g data-role="edges">')
    for p, ks in kids.items():
        g = geo[p]
        busy = g["bottom"] + FORK
        for k in ks:
            ck = geo[k]
            emph = emph_edge.get((p, k))
            col = C["red"] if emph else C["line"]
            sw = SW_E if emph else SW_N
            # derived head-room per edge: the red 3px edge carries a bigger head
            # than the 1.6px hairline, so it needs to stop further back
            gap = head_trim(14 if emph else None, sw) if arrows else 0
            pts = [(g["cx"], g["bottom"]), (g["cx"], busy), (ck["cx"], busy), (ck["cx"], ck["top"] - gap)]
            mk = (' marker-end="url(#ar)"' if emph else ' marker-end="url(#ag)"') if arrows else ""
            out.append(f'<path d="{rounded(pts)}" fill="none" stroke="{col}" stroke-width="{sw}"{mk}/>')
            lab = lbl_edge.get((p, k))
            if lab:
                lx = ck["cx"] + 8
                ly = (busy + ck["top"]) / 2 + 4
                lcol = C["red"] if emph else C["ink2"]
                out.append(f'<text x="{lx:.1f}" y="{ly:.1f}" font-size="{FS_EDGE}" '
                           f'font-weight="{"700" if emph else "600"}" fill="{lcol}" text-anchor="start">{esc(lab)}</text>')
    out.append('</g>')

    # nodes + notes
    out.append('<g data-role="nodes">')
    for nid, g in geo.items():
        n = by[nid]
        emph = n.get("emphasis")
        if emph:
            st = dict(fill=C["red"], text=C["white"], border=None)
        else:
            st = level_fill(lvl[nid], maxlevel)
        out.append(f'<g data-role="node" data-id="{nid}">')
        border = f' stroke="{st["border"]}" stroke-width="1"' if st["border"] else ""
        out.append(f'<rect x="{g["left"]:.1f}" y="{g["top"]:.1f}" width="{g["w"]:.1f}" height="{g["h"]:.1f}" '
                   f'rx="12" fill="{st["fill"]}"{border}/>')
        lines = lines_by[nid]
        ty = g["cy"] - (len(lines)-1)*LH_T/2 + FS_T*0.35
        for l in lines:
            out.append(f'<text x="{g["cx"]:.1f}" y="{ty:.1f}" font-size="{FS_T}" font-weight="700" '
                       f'fill="{st["text"]}" text-anchor="middle">{esc(l)}</text>')
            ty += LH_T
        if n.get("note"):
            ny = g["bottom"] + NOTE_GAP
            for l in wrap(n["note"], FS_NOTE, g["w"] + 90):
                out.append(f'<text x="{g["cx"]:.1f}" y="{ny:.1f}" font-size="{FS_NOTE}" '
                           f'fill="{C["ink2"]}" text-anchor="middle">{esc(l)}</text>')
                ny += LH_NOTE
        out.append('</g>')
    out.append('</g></svg>')
    return "\n".join(out), int(W), int(H)


def main(mapfile, out):
    svg, w, h = render(load_map(mapfile))
    open(out, "w", encoding="utf-8").write(svg)
    print(f"[tree] wrote {out}  {w}x{h}  ratio={w/h:.2f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "out.svg")
