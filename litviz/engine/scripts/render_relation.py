#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Relationship-diagram renderer. Nodes are parties/entities; edges are labeled,
directed relationships. graphviz (engine chosen per topology) positions the
nodes; we draw the cards, the labeled relationship lines, per-node notes, and
top/bottom skip-routes ourselves (graphviz edge routes are unreliable).

Usage: python render_relation.py <semantic-map.json> <out.svg>
"""
import sys, math, subprocess
from common import (C, FONT, FS, TITLE_FONT, TOKENS, esc, wrap, text_w, load_map,
                    arrow_marker, head_trim, trim_end)

FC = TOKENS["flow_colors"]
FS_TITLE_DOC = FS["doc_title"]
FS_T, FS_NOTE, FS_EDGE = 19, FS["subtitle"], FS["subtitle"]
LH_NOTE = 20
PADX, PADY = 22, 16
_THEME = None  # "guizang" -> roomier, squarer modules
NODE_MINW = 150
SW_N = TOKENS["stroke"]["connector"]      # ordinary relationship line
SW_E = TOKENS["stroke"]["emphasis"]       # the one emphasised relationship
ARC_GAP = 56          # how far above the row the top-route runs
NOTE_GAP = 30         # gap from node bottom to its note
MARGIN = 72
LABEL_MAXW = 176      # above-labels wrap width (never lengthen the line to fit)
LABEL_MAXW_SIDE = 150 # side-labels wrap NARROWER so they sit cleanly on one side
LH_LABEL = FS_EDGE + 5
GAP_LBL = 13          # base gap between a label block and its line (never crossed)
GAP_LBL_EMPH = 22     # red/emphasis labels sit further from the line (breathing room)
NUDGE_CAP = 12        # bounded away-from-line nudge (labels stay near, never fly)


def label_anchor(pts, center_y=None):
    """Anchor a label to the edge's LONGEST segment and return (mode, line, along):
      'above' -> label sits ABOVE a horizontal line at y=line, centred at x=along
      'side'  -> label sits BESIDE a vertical line at x=line, at y=along
    A label is ALWAYS placed wholly on one side and NEVER crosses its own line.
    For a SIDE label, `along` is biased toward the segment end FARTHER from the
    diagram's vertical centre, so two side-labels stacked on one column spread
    apart (the upper one rides higher, the lower one lower) instead of crowding
    the middle."""
    # merge collinear runs first, so a vertical split by rounded corners counts as
    # ONE segment (otherwise the 'longest' is only half of it and the side-label
    # bias mis-anchors)
    merged = [pts[0]]
    for p in pts[1:]:
        if len(merged) >= 2:
            a, b = merged[-2], merged[-1]
            if (abs(a[0] - b[0]) < 1 and abs(b[0] - p[0]) < 1) or \
               (abs(a[1] - b[1]) < 1 and abs(b[1] - p[1]) < 1):
                merged[-1] = p; continue
        merged.append(p)
    best = None
    for i in range(len(merged) - 1):
        (x0, y0), (x1, y1) = merged[i], merged[i + 1]
        seglen = abs(x1 - x0) + abs(y1 - y0)
        if best is None or seglen > best[0]:
            best = (seglen, x0, y0, x1, y1)
    _, x0, y0, x1, y1 = best
    if abs(x1 - x0) >= abs(y1 - y0):
        return ("above", min(y0, y1), (x0 + x1) / 2)
    mid = (y0 + y1) / 2
    if center_y is not None:
        outer = y0 if abs(y0 - center_y) >= abs(y1 - center_y) else y1
        along = outer + (mid - outer) * TOKENS["tuning"]["relation_side_label_bias"]
    else:
        along = mid
    return ("side", x0, along)


def _clear(bx, boxes):
    for (L, T, R, B) in boxes:
        if not (bx[2] < L or bx[0] > R or bx[3] < T or bx[1] > B):
            return False
    return True


def _clear_segs(bx, segs):
    """Label box must not be crossed by any connector segment (R: labels never
    sit on a line — their own or another edge's)."""
    L, T, R, B = bx
    for x0, y0, x1, y1 in segs:
        if abs(x0 - x1) < 1:                       # vertical segment
            if L - 1 < x0 < R + 1 and min(y0, y1) < B and max(y0, y1) > T:
                return False
        elif abs(y0 - y1) < 1:                     # horizontal segment
            if T - 1 < y0 < B + 1 and min(x0, x1) < R and max(x0, x1) > L:
                return False
    return True


def place_edge_labels(reqs, node_boxes, segs, W, H):
    """Place each wrapped label wholly on ONE side of its line, at a real gap
    (larger for red emphasis), nudging ONLY away from the line (never onto or
    across it) and never onto a node, another label, or ANY connector segment.
    Side labels go to the side AWAY from the node cluster. Emitted last (on top)."""
    cxs = [(L + R) / 2 for (L, T, R, B) in node_boxes]
    cluster = sum(cxs) / len(cxs) if cxs else W / 2
    placed, out = [], []
    for r in reqs:
        lines, emph, mode, line, along = r["lines"], r["emph"], r["mode"], r["line"], r["along"]
        bw = max(text_w(ln, FS_EDGE) for ln in lines)
        bh = len(lines) * LH_LABEL
        gap = GAP_LBL_EMPH if emph else GAP_LBL

        gens = []
        if mode == "above":                       # red/emphasis labels sit BELOW their line
            halign = "middle"; tx = along
            up = lambda off: ((tx - bw / 2, line - gap - off - bh, tx + bw / 2, line - gap - off), tx, line - gap - off - bh)
            dn = lambda off: ((tx - bw / 2, line + gap + off, tx + bw / 2, line + gap + off + bh), tx, line + gap + off)
            gens = [dn, up] if emph else [up, dn]
        else:                                     # side: outward, away from the cluster
            halign = "start"
            outward = 1 if line >= cluster else -1
            def _side(off):
                left = line + gap + off if outward > 0 else line - gap - off - bw
                top = along - bh / 2
                return (left, top, left + bw, top + bh), left, top
            gens.append(_side)

        chosen, best_pen = None, 1e9
        for gen in gens:
            for step in range(0, NUDGE_CAP + 1):
                bx, tx2, tystart = gen(step * LH_LABEL)
                if bx[0] < 2 or bx[2] > W - 2 or bx[1] < 2 or bx[3] > H - 2:
                    continue
                pen = (0 if _clear(bx, node_boxes) else 100) + \
                      (0 if _clear(bx, placed) else 40) + \
                      (0 if _clear_segs(bx, segs) else 12)
                if pen == 0:
                    best_pen = 0; chosen = (bx, tx2, tystart); break
                if pen < best_pen:
                    best_pen, chosen = pen, (bx, tx2, tystart)
            if chosen is not None and best_pen == 0:
                break
        if chosen is None:
            chosen = gens[0](0)
        bx, tx2, tystart = chosen
        placed.append(bx)
        ty = tystart + FS_EDGE
        for ln in lines:
            out.append(f'<text x="{tx2:.1f}" y="{ty:.1f}" font-size="{FS_EDGE}" '
                       f'font-weight="{r["fw"]}" fill="{r["col"]}" '
                       f'text-anchor="{halign}">{esc(ln)}</text>')
            ty += LH_LABEL
    return out

def tw(s, fs): return text_w(s, fs)

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

def node_size(n):
    w = max(NODE_MINW, tw(n["title"], FS_T) + 2*PADX)
    h = FS_T + 2*(30 if _THEME == "guizang" else PADY) + 6
    return w, h

def _aliases(m):
    return {n["id"]: f"N{i}" for i, n in enumerate(m["nodes"])}

def build_dot(m):
    al = _aliases(m)
    eng_dir = m.get("direction", "LR")
    L = ['digraph G {', f'  rankdir={eng_dir}; splines=line;',
         '  nodesep=1.5; ranksep=2.6;', '  node [shape=box, fixedsize=true];']
    for n in m["nodes"]:
        w, h = node_size(n)
        L.append(f'  {al[n["id"]]} [label="{al[n["id"]]}", width={w/72:.3f}, height={h/72:.3f}];')
    for e in m["edges"]:
        constraint = "false" if e.get("route") in ("top", "bottom") else "true"
        L.append(f'  {al[e["from"]]} -> {al[e["to"]]} [constraint={constraint}];')
    L.append('}')
    return "\n".join(L)

def run_dot(dot_src, engine="dot"):
    try:
        p = subprocess.run([engine, "-Tplain"], input=dot_src, capture_output=True, text=True, check=True)
    except FileNotFoundError:
        raise RuntimeError(f"graphviz '{engine}' not found on PATH. Relationship "
                           "layouts need graphviz — install it (e.g. `apt-get "
                           "install graphviz`).")
    nodes, gw, gh = {}, 0.0, 0.0
    for ln in p.stdout.splitlines():
        t = ln.split()
        if t and t[0] == "graph":
            gw, gh = float(t[2]), float(t[3])
        elif t and t[0] == "node":
            nodes[t[1]] = (float(t[2]), float(t[3]), float(t[4]), float(t[5]))
    return nodes, gw, gh

LANE = 16             # lane pitch to separate parallel edge trunks (R5)
STUB = 22             # straight run out of a node / into an arrowhead (R2/R3)


def _route_edges(m, geo):
    """Orthogonal router (R1–R5): every edge gets a node-avoiding, lane-separated
    route with a straight departure/landing stub. Candidate routes are tried in
    order (straight → in-gap Z → side detour → bottom trunk); the first that
    crosses no non-endpoint node and doesn't reuse an occupied lane is chosen.
    Returns a point-list per edge, in m['edges'] order. Verified by geometry
    (node-crossings=0, parallel-overlap=0) — that is what makes 'no overlap' hold."""
    box = {nid: (g["left"], g["top"], g["right"], g["bottom"]) for nid, g in geo.items()}
    minL = min(b[0] for b in box.values()); maxR = max(b[2] for b in box.values())
    maxB = max(b[3] for b in box.values())

    def hit(x0, y0, x1, y1, sa, sb):
        for nid, (L, T, R, B) in box.items():
            if nid in (sa, sb):
                continue
            if abs(x0 - x1) < 1:                       # vertical
                if L - 4 < x0 < R + 4 and min(y0, y1) < B - 2 and max(y0, y1) > T + 2:
                    return True
            elif abs(y0 - y1) < 1:                     # horizontal
                if T - 4 < y0 < B + 4 and min(x0, x1) < R - 2 and max(x0, x1) > L + 2:
                    return True
        return False

    def blocked(pts, sa, sb):
        return any(hit(*pts[i], *pts[i + 1], sa, sb) for i in range(len(pts) - 1))

    v_used, h_used = [], []          # occupied mid-trunks: (pos, lo, hi)
    port_ctr = {}
    def port(nid, side, center, lo, hi):
        """Attachment point on a node side: the FIRST edge on that side sits at the
        dead centre (what the user wants); extra parallel edges take a SMALL offset
        that stays strictly ON the edge (clamped inside the corners), so they don't
        stack yet never leave the module's border line."""
        key = (nid, side); k = port_ctr.get(key, 0); port_ctr[key] = k + 1
        if k == 0:
            return center
        seq = []
        for i in range(1, 6):
            seq += [i * 18, -i * 18]
        off = seq[k - 1] if k - 1 < len(seq) else 0
        return max(lo + 12, min(hi - 12, center + off))
    def free(used, pos, lo, hi):
        return all(not (abs(pos - p) < LANE - 3 and min(hi, h) - max(lo, l) > 8)
                   for p, l, h in used)
    def claim(used, pos, lo, hi, step):
        k = 0
        while not free(used, pos + k, lo, hi):
            k += step
        used.append((pos + k, lo, hi)); return pos + k

    def arc(a, b, top):
        """Clean edge-centred skip arc for an explicit top/bottom route hint."""
        if top:
            ty = min(a["top"], b["top"]) - ARC_GAP
            return [(a["cx"], a["top"]), (a["cx"], ty), (b["cx"], ty), (b["cx"], b["top"])]
        ty = max(a["bottom"], b["bottom"]) + ARC_GAP
        return [(a["cx"], a["bottom"]), (a["cx"], ty), (b["cx"], ty), (b["cx"], b["bottom"])]

    routes = []
    for e in m["edges"]:
        sa, sb = e["from"], e["to"]
        a, b = geo[sa], geo[sb]
        route = e.get("route")
        pts = None

        # 1) explicit top/bottom hint → clean edge-centred arc (honoured as-is)
        if route in ("top", "bottom"):
            pts = arc(a, b, route == "top")

        # 2) same row, facing → straight; lane-separated so a bidirectional pair
        #    (A→B and B→A) never lands on the same line (clamped to stay on-edge)
        if pts is None and abs(a["cy"] - b["cy"]) < 8:
            if b["left"] >= a["right"]:
                sx, ex = a["right"], b["left"]
            else:
                sx, ex = a["left"], b["right"]
            y = claim(h_used, a["cy"], min(sx, ex), max(sx, ex), LANE)
            ylo = max(a["top"], b["top"]) + 10
            yhi = min(a["bottom"], b["bottom"]) - 10
            y = max(ylo, min(yhi, y))
            cand = [(sx, y), (ex, y)]
            if not blocked(cand, sa, sb):
                pts = cand

        # 3) cross row → DIRECT route: leave the edge centre, enter b from the side
        #    FACING a, land exactly on that edge. Try horizontal-first then
        #    vertical-first; use the first that crosses no node. (No far detour.)
        if pts is None:
            cands = []
            if b["left"] >= a["right"] or b["right"] <= a["left"]:   # x-separated → H-first Z
                if b["cx"] > a["cx"]:
                    sx, ex = a["right"], b["left"]; sa_side, sb_side = "R", "L"
                else:
                    sx, ex = a["left"], b["right"]; sa_side, sb_side = "L", "R"
                sy = port(sa, sa_side, a["cy"], a["top"], a["bottom"])
                ey = port(sb, sb_side, b["cy"], b["top"], b["bottom"])
                mx = claim(v_used, (sx + ex) / 2, min(sy, ey), max(sy, ey),
                           LANE if ex >= sx else -LANE)
                cands.append([(sx, sy), (mx, sy), (mx, ey), (ex, ey)])
            down = b["cy"] > a["cy"]                                  # V-first Z (enter top/bottom)
            ay, by = (a["bottom"], b["top"]) if down else (a["top"], b["bottom"])
            sxv = port(sa, "B" if down else "T", a["cx"], a["left"], a["right"])
            exv = port(sb, "T" if down else "B", b["cx"], b["left"], b["right"])
            my = claim(h_used, (ay + by) / 2, min(sxv, exv), max(sxv, exv), LANE)
            cands.append([(sxv, ay), (sxv, my), (exv, my), (exv, by)])
            for cand in cands:
                if not blocked(cand, sa, sb):
                    pts = cand; break

        # 4) direct route blocked by a node → tight detour around the NEARER side,
        #    still edge-centred, still landing exactly on b's edge
        if pts is None:
            mid = (a["cx"] + b["cx"]) / 2
            order = ([("L", minL), ("R", maxR)] if mid - minL <= maxR - mid
                     else [("R", maxR), ("L", minL)])
            for s, base in order:
                x = claim(v_used, base + STUB if s == "R" else base - STUB,
                          min(a["cy"], b["cy"]), max(a["cy"], b["cy"]), LANE if s == "R" else -LANE)
                ax = a["right"] if s == "R" else a["left"]
                bx = b["right"] if s == "R" else b["left"]
                cand = [(ax, a["cy"]), (x, a["cy"]), (x, b["cy"]), (bx, b["cy"])]
                if not blocked(cand, sa, sb):
                    pts = cand; break

        if pts is None:                              # last resort: bottom trunk (kept low
            yb = claim(h_used, maxB + STUB + 60, min(a["cx"], b["cx"]), max(a["cx"], b["cx"]), LANE)
            pts = [(a["cx"], a["bottom"]), (a["cx"], yb), (b["cx"], yb), (b["cx"], b["bottom"])]

        routes.append(pts)
    return routes


def _layout_nodes(m):
    """Deterministic, graphviz-free relation layout that SCALES.

    * A simple/linear small graph (no dominant hub) keeps its SOURCE ORDER in
      one centred row (甲→乙→丙 stays left-to-right).
    * Otherwise nodes go on an aligned GRID, filled in a **spiral from the centre
      in BFS order from the hub** — so the hub sits in the middle and each node
      lands near the neighbours it connects to. Clustering connected parties this
      way keeps edges short and greatly reduces crossings, and every row shares
      one vertical band. Spacing is generous, leaving node-free channels between
      cells for the router.
    Returns (pos {id:(cx,cy)} centred on origin, sizes {id:(w,h)})."""
    ids = [n["id"] for n in m["nodes"]]
    by_id = {n["id"]: n for n in m["nodes"]}
    sizes = {i: node_size(by_id[i]) for i in ids}
    n = len(ids)
    deg = {i: 0 for i in ids}
    adj = {i: set() for i in ids}
    for e in m["edges"]:
        if e["from"] in deg and e["to"] in deg:
            deg[e["from"]] += 1; deg[e["to"]] += 1
            adj[e["from"]].add(e["to"]); adj[e["to"]].add(e["from"])

    col_w = max(sizes[i][0] for i in ids)
    row_h = max(sizes[i][1] for i in ids)
    HGAP, VGAP = 150, 150                              # generous: node-free channels + label room
    px, py = col_w + HGAP, row_h + VGAP

    degvals = sorted(deg.values())
    second = degvals[-2] if n > 1 else 0
    dominant = n >= 5 and max(deg.values()) >= second + 2
    small_linear = n <= 4 and not dominant

    pos = {}
    if small_linear or (not dominant and n <= 6):
        # source order, centred row(s) of ≈3
        cols = n if n <= 3 else (2 if n == 4 else 3)
        rows, slots, rem = 0, [], n
        while rem > 0:
            ncol = min(cols, rem)
            for c in range(ncol):
                slots.append((rows, c, ncol))
            rem -= ncol; rows += 1
        col_off = lambda r, c, ncol: c - (ncol - 1) / 2
        rmid = (rows - 1) / 2
        for sk, (r, c, ncol) in enumerate(slots):
            pos[ids[sk]] = (col_off(r, c, ncol) * px, (r - rmid) * py)
        return pos, sizes

    # spiral cells from the centre (square ring spiral)
    hub = max(ids, key=lambda i: deg[i])
    neigh = [i for i in ids if i in adj[hub]]
    if len(neigh) <= 8:
        # APPROVED radial layout: hub centre, neighbours on its 4 sides then corners,
        # so the hub's edges spread across E/N/W/S (never 3 on one border).
        pos[hub] = (0.0, 0.0)
        ring = [(1, 0), (0, -1), (-1, 0), (0, 1), (1, -1), (-1, -1), (-1, 1), (1, 1)]
        rest = [i for i in ids if i != hub and i not in adj[hub]]
        for k, node in enumerate(neigh + rest):
            if k < len(ring):
                dx, dy = ring[k]; pos[node] = (dx * px, dy * py)
            else:
                pos[node] = ((k - len(ring) + 2) * px, 0.0)
        return pos, sizes

    def spiral(count):
        cells = [(0, 0)]
        x = y = 0; step = 1
        dirs = [(1, 0), (0, 1), (-1, 0), (0, -1)]
        di = 0
        while len(cells) < count:
            for _ in range(2):
                dx, dy = dirs[di % 4]; di += 1
                for _ in range(step):
                    x += dx; y += dy
                    cells.append((x, y))
                    if len(cells) >= count:
                        return cells
            step += 1
        return cells[:count]
    cells = spiral(n)

    # BFS order from the hub (busiest neighbours first) so the centre is dense
    from collections import deque
    hub = max(ids, key=lambda i: deg[i])
    seen = {hub}; q = deque([hub]); order = [hub]
    while q:
        u = q.popleft()
        for v in sorted(adj[u], key=lambda w: -deg[w]):
            if v not in seen:
                seen.add(v); q.append(v); order.append(v)
    for i in ids:                                      # any disconnected nodes
        if i not in seen:
            order.append(i)

    for node, (cx, cy) in zip(order, cells):
        pos[node] = (cx * px, cy * py)
    return pos, sizes


def render(m):
    pos, sizes = _layout_nodes(m)
    _deg = {n["id"]: 0 for n in m["nodes"]}
    for e in m["edges"]:
        if e["from"] in _deg: _deg[e["from"]] += 1
        if e["to"] in _deg: _deg[e["to"]] += 1
    _hub = max(_deg, key=lambda i: _deg[i]) if _deg else None
    S = lambda v: v                                    # positions are already px
    by_id = {n["id"]: n for n in m["nodes"]}
    # local extent (node boxes only; notes/labels handled via room below)
    xs0 = [pos[i][0] - sizes[i][0] / 2 for i in pos]
    xs1 = [pos[i][0] + sizes[i][0] / 2 for i in pos]
    ys0 = [pos[i][1] - sizes[i][1] / 2 for i in pos]
    ys1 = [pos[i][1] + sizes[i][1] / 2 for i in pos]
    gminx, gmaxx, gminy, gmaxy = min(xs0), max(xs1), min(ys0), max(ys1)

    # geometry in a top-origin space, leaving room for title, top arcs, notes,
    # and horizontal room on both sides for outward side-labels.
    title_zone = 74
    top_room = 34
    # Notes wrap; reserve room for the DEEPEST note actually present (a long note
    # used to wrap past a fixed 2-line allowance and overflow the canvas).
    _nlines = 0
    for _i in pos:
        _n = by_id.get(_i, {})
        if _n.get("note"):
            _nlines = max(_nlines, len(wrap(_n["note"], FS_NOTE, sizes[_i][0] + 80)))
    note_room = NOTE_GAP + max(2, _nlines) * LH_NOTE + 110
    HROOM = LABEL_MAXW_SIDE + STUB + GAP_LBL_EMPH + 30   # side-label breathing room
    W = (gmaxx - gminx) + 2*MARGIN + 2*HROOM
    yoff = title_zone + top_room + MARGIN*0.2
    H = (gmaxy - gminy) + title_zone + top_room + note_room + MARGIN

    geo = {}
    for nid, (x, y) in pos.items():
        w, h = sizes[nid]
        cx = (x - gminx) + MARGIN + HROOM
        cy = (y - gminy) + yoff
        geo[nid] = {"cx": cx, "cy": cy, "w": w, "h": h,
                    "top": cy - h/2, "bottom": cy + h/2,
                    "left": cx - w/2, "right": cx + w/2}

    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W:.0f}" height="{H:.0f}" viewBox="0 0 {W:.0f} {H:.0f}" font-family="{FONT}">',
           '<defs>' + arrow_marker('ag', C["line"], width=SW_N) + arrow_marker('ar', C["red"], size=14, width=SW_E) + '</defs>',
           f'<rect width="{W:.0f}" height="{H:.0f}" fill="{C["bg"]}"/>',
           f'<text x="{W/2:.0f}" y="46" font-size="{FS_TITLE_DOC}" font-weight="700" font-family="{TITLE_FONT}" '
           f'fill="{C["ink"]}" stroke="{C["ink"]}" stroke-width="0.3" text-anchor="middle">{esc(m["title_text"])}</text>']

    # edges — routed by the lane-based, node-avoiding router (R1–R5)
    out.append('<g data-role="edges">')
    label_reqs = []
    routes = _route_edges(m, geo)
    cy_center = (min(g["cy"] for g in geo.values()) + max(g["cy"] for g in geo.values())) / 2
    for e, pts in zip(m["edges"], routes):
        emph = e.get("emphasis")
        col = C["red"] if emph else C["line"]
        sw = SW_E if emph else SW_N
        mk = "url(#ar)" if emph else "url(#ag)"
        # The router aims at the node border; head-room is applied here so the
        # arrow TIP (not the line end) keeps its gap. Labels still anchor on the
        # UNtrimmed route, so placement is unchanged.
        d = rounded(trim_end(pts, head_trim(14 if emph else None, sw)))
        out.append(f'<path d="{d}" fill="none" stroke="{col}" stroke-width="{sw}" marker-end="{mk}"/>')
        if e.get("label"):   # wrapped + segment-aware + collision-placed after nodes
            lcol = C["red"] if emph else C["ink2"]
            mode, line, along = label_anchor(pts, cy_center)
            maxw = LABEL_MAXW_SIDE if mode == "side" else LABEL_MAXW
            label_reqs.append({"lines": wrap(e["label"], FS_EDGE, maxw),
                               "mode": mode, "line": line, "along": along,
                               "emph": bool(emph), "col": lcol, "fw": "700" if emph else "600"})
    out.append('</g>')

    # nodes + notes
    out.append('<g data-role="nodes">')
    for nid, g in geo.items():
        n = by_id[nid]
        # [AWD-PATCH 2 · 见 litviz/PATCHES.md] 原式把 \" 写在 f-string 表达式里，
        # 那是 PEP 701 语法，只有 Python ≥ 3.12 能编译；我们打包的运行时是 3.11，
        # 整个 render 模块会在 import 期就 SyntaxError。提出来赋值，输出逐字节不变。
        emph_attr = ' data-emph="1"' if nid == _hub else ''
        out.append(f'<g data-role="node" data-id="{nid}"{emph_attr}>')
        out.append(f'<rect x="{g["left"]:.1f}" y="{g["top"]:.1f}" width="{g["w"]:.1f}" height="{g["h"]:.1f}" '
                   f'rx="12" fill="{FC["step_fill"]}" stroke="{FC["step_stroke"]}" stroke-width="1"/>')
        out.append(f'<text x="{g["cx"]:.1f}" y="{g["cy"]+FS_T*0.35:.1f}" font-size="{FS_T}" font-weight="700" '
                   f'fill="{C["ink"]}" text-anchor="middle">{esc(n["title"])}</text>')
        if n.get("note"):
            nlines = wrap(n["note"], FS_NOTE, g["w"] + 80)
            ny = g["bottom"] + NOTE_GAP
            for ln in nlines:
                out.append(f'<text x="{g["cx"]:.1f}" y="{ny:.1f}" font-size="{FS_NOTE}" fill="{C["ink2"]}" '
                           f'text-anchor="middle">{esc(ln)}</text>')
                ny += LH_NOTE
        out.append('</g>')
    out.append('</g>')   # close nodes group
    node_boxes = [(g["left"], g["top"], g["right"], g["bottom"]) for g in geo.values()]
    segs = [(p[i][0], p[i][1], p[i + 1][0], p[i + 1][1])
            for p in routes for i in range(len(p) - 1)]
    out.append('<g data-role="edge-labels">')
    out += place_edge_labels(label_reqs, node_boxes, segs, W, H)
    out.append('</g>')
    out.append('</svg>')
    return "\n".join(out), int(W), int(H)

def main(mapfile, out):
    svg, w, h = render(load_map(mapfile))
    open(out, "w", encoding="utf-8").write(svg)
    print(f"[relation] wrote {out}  {w}x{h}  ratio={w/h:.2f}")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "out.svg")
