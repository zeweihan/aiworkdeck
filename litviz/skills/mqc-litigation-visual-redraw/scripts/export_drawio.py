#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Export a semantic-map.json to an EDITABLE draw.io (.drawio) diagram.

Same golden rule as the renderers: the model only emits semantic JSON; this
script derives ALL geometry deterministically. The output is an OPEN,
editable mxGraphModel XML (draw.io / diagrams.net) so a lawyer can rearrange,
retype and re-export (to Visio .vsdx / PDF / PNG / SVG) in a free,
cross-platform editor — with ZERO third-party dependencies (Python stdlib
only), preserving this skill's zero-dependency promise.

Two artifacts:
  <base>.drawio       — the mxGraphModel XML (structure-native, git-friendly)
  <base>.drawio.svg   — a valid SVG that ALSO embeds the model, so the one
                        file both renders anywhere (README/WPS/browser) and
                        opens editable in draw.io. Written by render.py, which
                        owns the pretty master SVG used as the visual face.

Covers all seven layouts. The node+edge families (graphviz_flow /
graphviz_relation / relation_tree) become vertices + connected edges; node
positions reuse graphviz `dot` when installed (matching the SVG master) and
fall back to a deterministic pure-stdlib layered layout when `dot` is absent
(so the export still works on the minimal / bare-repo / no-dot setup). The
positioned graphics (numbered / dated timelines, proportional gantt,
comparison table) are emitted as laid-out, editable shapes.

Text insets: to stop labels hugging the box border in draw.io, every text box
is sized to OUR own pre-wrapped line count, the line breaks are baked into the
value as <br>, and an explicit label `spacing` is set — so draw.io does not
silently re-wrap to an extra line and overflow the box.

Usage:
    python export_drawio.py <semantic-map.json> [out_basename]
        -> <out_basename>.drawio
"""
import sys, os, html as _html, subprocess, shutil
from collections import defaultdict
from datetime import datetime, timezone
import re

from common import TOKENS, wrap, text_w, load_map, parse_date

C = TOKENS["colors"]
FC = TOKENS["flow_colors"]
FS = TOKENS["type_scale"]

GRAPH_LAYOUTS = {"graphviz_flow", "graphviz_relation", "relation_tree"}
TIMELINE_LAYOUTS = {"numbered_point_timeline", "dated_point_timeline"}
SUPPORTED_LAYOUTS = GRAPH_LAYOUTS | TIMELINE_LAYOUTS | {"proportional_gantt", "comparison_table"}

# --- fit sizing (deterministic) ------------------------------------------
NODE_FS = 15
LINE_H = 22               # generous line box for 15px text
SPACE = 8                 # explicit label inset inside every box (anti-hug)
WFUDGE = 1.10             # width safety: draw.io's real font is a touch wider
PAD_Y = 12
MIN_W, MIN_H = 140, 50
MAX_CONTENT_W = 240       # px wrap width for node text
NOTE_FS = 12
NOTE_LH = 18
MARGIN = 40
TITLE_H = 62
CARD_W = 170              # timeline card width


def esc(s):
    return _html.escape(str(s), quote=True)


def attr_html(lines):
    """XML-attribute-safe mxCell value holding an HTML label with <br> breaks:
    each line is HTML-escaped, joined with <br>, then the whole HTML string is
    escaped again for the attribute (so <br> -> &lt;br&gt;)."""
    return esc("<br>".join(esc(ln) for ln in lines))


def _wrap(text, box_w, fs=NODE_FS):
    """Wrap to the text area = box_w minus the two label insets."""
    return wrap(text, fs, max(20, box_w - 2 * SPACE))


def _box_size(lines, min_w=MIN_W, min_h=MIN_H):
    w = max(min_w, max(text_w(ln, NODE_FS) for ln in lines) * WFUDGE + 2 * SPACE + 8)
    h = max(min_h, len(lines) * LINE_H + 2 * PAD_Y)
    return w, h


# small mxCell writers ----------------------------------------------------
def _vx(cid, x, y, w, h, style, value=""):
    return (f'<mxCell id="{cid}" value="{value}" style="{style}" vertex="1" '
            f'parent="1"><mxGeometry x="{x:.0f}" y="{y:.0f}" width="{w:.0f}" '
            f'height="{h:.0f}" as="geometry"/></mxCell>')


def _ln(cid, x1, y1, x2, y2, style):
    return (f'<mxCell id="{cid}" style="{style}" edge="1" parent="1">'
            f'<mxGeometry relative="1" as="geometry">'
            f'<mxPoint x="{x1:.0f}" y="{y1:.0f}" as="sourcePoint"/>'
            f'<mxPoint x="{x2:.0f}" y="{y2:.0f}" as="targetPoint"/>'
            f'</mxGeometry></mxCell>')


def _title_cell(title, x, w):
    # title uses a serif (宋体) stack to echo the SVG master's Song title face;
    # html=1 makes fontFamily behave as a CSS font-family list with fallbacks.
    return _vx("title", x, MARGIN, w, TITLE_H - 16,
               f"text;html=1;align=center;verticalAlign=middle;fontStyle=1;"
               f"fontFamily=思源宋体,SimSun,Songti SC,serif;"
               f"fontSize=20;fontColor={C['ink']};", attr_html([title]))


def _wrap_style(fill, stroke, font, extra="rounded=1;"):
    return (f"{extra}whiteSpace=wrap;html=1;spacing={SPACE};align=center;"
            f"verticalAlign=middle;fillColor={fill};strokeColor={stroke};"
            f"fontColor={font};fontSize={NODE_FS};")


# ============================================================ GRAPH =======
def _node_lines(n):
    lines = _wrap(n["title"], MAX_CONTENT_W)
    for extra in n.get("lines", []) or []:
        lines += _wrap(extra, MAX_CONTENT_W)
    return lines


def _positions_graphviz(m, sizes):
    if m["layout"] == "graphviz_relation":
        rankdir, engine = m.get("direction", "LR"), m.get("engine", "dot")
    else:
        rankdir, engine = "TB", "dot"
    if shutil.which(engine) is None:
        return None
    alias = {n["id"]: f"N{i}" for i, n in enumerate(m["nodes"])}
    L = ["digraph G {", f"  rankdir={rankdir}; splines=line;",
         "  nodesep=0.6; ranksep=0.9;", "  node [shape=box, fixedsize=true];"]
    for n in m["nodes"]:
        w, h = sizes[n["id"]]
        L.append(f'  {alias[n["id"]]} [width={w/72:.3f}, height={h/72:.3f}];')
    for e in m["edges"]:
        L.append(f'  {alias[e["from"]]} -> {alias[e["to"]]};')
    L.append("}")
    try:
        p = subprocess.run([engine, "-Tplain"], input="\n".join(L),
                           capture_output=True, text=True, check=True)
    except Exception:
        return None
    inv = {v: k for k, v in alias.items()}
    raw, gh = {}, 0.0
    for ln in p.stdout.splitlines():
        t = ln.split()
        if t and t[0] == "graph":
            gh = float(t[3])
        elif t and t[0] == "node" and t[1] in inv:
            raw[inv[t[1]]] = (float(t[2]) * 72, float(t[3]) * 72)
    if not raw:
        return None
    return {k: (x, gh * 72 - y) for k, (x, y) in raw.items()}


def _positions_layered(m, sizes):
    ids = [n["id"] for n in m["nodes"]]
    edges = [(e["from"], e["to"]) for e in m["edges"]
             if e["from"] in sizes and e["to"] in sizes]
    layer = {i: 0 for i in ids}
    for _ in range(len(ids) + 1):
        changed = False
        for a, b in edges:
            if layer[b] < layer[a] + 1:
                layer[b] = layer[a] + 1
                changed = True
        if not changed:
            break
    rows = defaultdict(list)
    for i in ids:
        rows[layer[i]].append(i)
    HGAP, VGAP = 60, 64
    row_w = {lv: sum(sizes[i][0] for i in ms) + HGAP * (len(ms) - 1)
             for lv, ms in rows.items()}
    full_w = max(row_w.values()) if row_w else 0
    pos, y = {}, 0.0
    for lv in sorted(rows):
        ms = rows[lv]
        rh = max(sizes[i][1] for i in ms)
        x = (full_w - row_w[lv]) / 2
        for i in ms:
            w, h = sizes[i]
            pos[i] = (x + w / 2, y + rh / 2)
            x += w + HGAP
        y += rh + VGAP
    return pos


def _node_style(n):
    emph = bool(n.get("emphasis"))
    kind = n.get("kind", "step")
    if kind == "terminal":
        fill = C["red"] if emph else FC["terminal_fill"]
        return (f"rounded=1;arcSize=50;whiteSpace=wrap;html=1;spacing={SPACE};"
                f"align=center;verticalAlign=middle;fillColor={fill};"
                f"strokeColor={fill};fontColor=#FFFFFF;fontStyle=1;fontSize={NODE_FS};")
    if kind == "decision":
        fill = C["red"] if emph else FC["decision_fill"]
        stroke = C["red"] if emph else FC["decision_stroke"]
        fontc = "#FFFFFF" if emph else C["ink"]
        return (f"shape=hexagon;perimeter=hexagonPerimeter2;whiteSpace=wrap;"
                f"html=1;spacing={SPACE};align=center;verticalAlign=middle;"
                f"fillColor={fill};strokeColor={stroke};fontColor={fontc};fontSize={NODE_FS};")
    if emph:
        return _wrap_style(C["red"], C["red"], "#FFFFFF") + "fontStyle=1;"
    return _wrap_style(FC["step_fill"], FC["step_stroke"], C["ink"])


def _edge_style(e, arrows):
    emph = bool(e.get("emphasis"))
    col = C["red"] if emph else C["line"]
    end = "block" if arrows else "none"
    return (f"edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;strokeColor={col};"
            f"strokeWidth={3 if emph else 2};endArrow={end};endFill={1 if arrows else 0};"
            f"fontColor={col if emph else C['ink2']};fontSize={FS['edge_label']};")


def _build_graph(m):
    sizes = {n["id"]: _box_size(_node_lines(n)) for n in m["nodes"]}
    pos = _positions_graphviz(m, sizes) or _positions_layered(m, sizes)
    arrows = bool(m.get("arrows", False)) if m["layout"] == "relation_tree" else True

    has_note = {n["id"]: bool(n.get("note")) for n in m["nodes"]}
    lefts, rights, tops, bots = [], [], [], []
    for n in m["nodes"]:
        cx, cy = pos[n["id"]]; w, h = sizes[n["id"]]
        over = 40 if has_note[n["id"]] else 0    # notes are w+80 wide (40px each side)
        lefts.append(cx - w / 2 - over); rights.append(cx + w / 2 + over); tops.append(cy - h / 2)
        extra = (NOTE_LH * len(_wrap(n["note"], w + 80, NOTE_FS)) + 12) if has_note[n["id"]] else 0
        bots.append(cy + h / 2 + extra)
    minx, maxx, miny, maxy = min(lefts), max(rights), min(tops), max(bots)
    offx, offy = MARGIN - minx, MARGIN + TITLE_H - miny
    W = (maxx - minx) + 2 * MARGIN
    H = (maxy - miny) + 2 * MARGIN + TITLE_H

    idmap = {n["id"]: f"c{i}" for i, n in enumerate(m["nodes"])}
    cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>',
             _title_cell(m.get("title_text", ""), MARGIN, maxx - minx)]
    for n in m["nodes"]:
        cx, cy = pos[n["id"]]; w, h = sizes[n["id"]]
        x, y = cx - w / 2 + offx, cy - h / 2 + offy
        cells.append(_vx(idmap[n["id"]], x, y, w, h, _node_style(n), attr_html(_node_lines(n))))
        if has_note[n["id"]]:
            nl = _wrap(n["note"], w + 80, NOTE_FS)
            cells.append(_vx(idmap[n["id"]] + "_note", x - 40, y + h + 6, w + 80, NOTE_LH * len(nl) + 6,
                             f"text;html=1;align=center;verticalAlign=top;spacing={SPACE};"
                             f"fontSize={NOTE_FS};fontColor={C['ink2']};", attr_html(nl)))
    for i, e in enumerate(m["edges"]):
        val = attr_html([e["label"]]) if e.get("label") else ""
        cells.append(f'<mxCell id="e{i}" value="{val}" style="{_edge_style(e, arrows)}" '
                     f'edge="1" parent="1" source="{idmap[e["from"]]}" '
                     f'target="{idmap[e["to"]]}"><mxGeometry relative="1" as="geometry"/></mxCell>')
    return cells, W, H


# ========================================================= TIMELINES ======
def _card_cell(cid, x, y, ev):
    lines = []
    if ev.get("date_text"):
        lines.append(ev["date_text"])
    lines += _wrap(ev["text"], CARD_W)
    _, ch = _box_size(lines, min_w=CARD_W, min_h=44)
    emph = bool(ev.get("emphasis"))
    style = (_wrap_style(C["red"], C["red"], "#FFFFFF") if emph
             else _wrap_style(C["card_fill"], C["card_stroke"], C["ink"]))
    return _vx(cid, x, y, CARD_W, ch, style, attr_html(lines)), ch


def _marker_cell(cid, cx, cy, label, emph, r=17):
    fill = C["red"] if emph else C["circle"]
    return _vx(cid, cx - r, cy - r, 2 * r, 2 * r,
               f"ellipse;whiteSpace=wrap;html=1;fillColor={fill};strokeColor={fill};"
               f"fontColor=#FFFFFF;fontStyle=1;fontSize=15;align=center;verticalAlign=middle;",
               attr_html([label]) if label else "")


def _card_heights(events):
    hs = []
    for ev in events:
        lines = ([ev["date_text"]] if ev.get("date_text") else []) + _wrap(ev["text"], CARD_W)
        _, ch = _box_size(lines, min_w=CARD_W, min_h=44)
        hs.append(ch)
    return hs


def _build_timeline(m, dated):
    events = m["events"]
    hs = _card_heights(events)
    up_h = max([h for h, ev in zip(hs, events) if ev.get("band", "up") == "up"] + [0])
    dn_h = max([h for h, ev in zip(hs, events) if ev.get("band", "up") != "up"] + [0])
    axis_y = MARGIN + TITLE_H + up_h + 46

    if dated:
        ds = [parse_date(ev["date"]) for ev in events]
        dmin, dmax = min(ds), max(ds)
        span = (dmax - dmin).days or 1
        axis_w = max(760, 120 * (len(events) - 1))
        xs = [MARGIN + CARD_W / 2 + (d - dmin).days / span * axis_w for d in ds]
    else:
        step = CARD_W + 34
        xs = [MARGIN + CARD_W / 2 + i * step for i in range(len(events))]
    W = max(xs) + CARD_W / 2 + MARGIN
    H = axis_y + dn_h + 60

    cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>',
             _title_cell(m.get("title_text", ""), MARGIN, W - 2 * MARGIN)]
    cells.append(_ln("axis", MARGIN, axis_y, W - MARGIN, axis_y,
                     f"endArrow=none;html=1;strokeColor={C['line_soft']};strokeWidth=2;"))
    for i, (ev, x) in enumerate(zip(events, xs)):
        up = ev.get("band", "up") == "up"
        # size the card, then place it above (up) or below (down) the axis
        lines = ([ev["date_text"]] if ev.get("date_text") else []) + _wrap(ev["text"], CARD_W)
        _, ch = _box_size(lines, min_w=CARD_W, min_h=44)
        cy_top = (axis_y - 40 - ch) if up else (axis_y + 40)
        c, ch = _card_cell(f"card{i}", x - CARD_W / 2, cy_top, ev)
        cells.append(c)
        label = ev.get("id", str(i + 1)) if not dated else ""
        r = 17 if not dated else 8
        # connector runs from the card's near edge to the marker's PERIMETER
        # (not its centre) so the line never overlaps/covers the circle; the
        # marker is appended AFTER the line so it also sits on top as a backstop.
        cy_card = (cy_top + ch) if up else cy_top
        mk_edge = (axis_y - r) if up else (axis_y + r)
        cells.append(_ln(f"cn{i}", x, cy_card, x, mk_edge,
                         f"endArrow=none;html=1;strokeColor={C['line_soft']};strokeWidth=1;"))
        cells.append(_marker_cell(f"mk{i}", x, axis_y, label, bool(ev.get("emphasis")), r))
    return cells, W, H


# ============================================================= GANTT =======
def _build_gantt(m):
    ax = m["axis"]
    start, end = parse_date(ax["start"]), parse_date(ax["end"])
    span = (end - start).days or 1
    axis_x0 = MARGIN + 40
    axis_w = 900
    sx = lambda d: axis_x0 + (parse_date(d) - start).days / span * axis_w

    top = MARGIN + TITLE_H + 30
    row_h, gap = 34, 16
    spans = m["spans"]
    # year ticks
    cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>']
    W = axis_x0 + axis_w + MARGIN
    H = top + len(spans) * (row_h + gap) + 70
    cells.append(_title_cell(m.get("title_text", ""), MARGIN, W - 2 * MARGIN))
    y_axis_bottom = top + len(spans) * (row_h + gap) + 6
    for yr in range(start.year, end.year + 1):
        try:
            tx = sx(f"{yr}/1/1")
        except Exception:
            continue
        if tx < axis_x0 - 1 or tx > axis_x0 + axis_w + 1:
            continue
        cells.append(_ln(f"tick{yr}", tx, top - 6, tx, y_axis_bottom,
                         f"endArrow=none;html=1;strokeColor={C['grid']};"))
        cells.append(_vx(f"yr{yr}", tx - 22, y_axis_bottom + 2, 44, 18,
                         f"text;html=1;align=center;fontSize=12;fontColor={C['ink2']};",
                         attr_html([str(yr)])))
    # bars
    for i, s in enumerate(spans):
        x1, x2 = sx(s["from"]), sx(s["to"])
        y = top + i * (row_h + gap)
        emph = bool(s.get("emphasis"))
        fill = C["red"] if emph else C["bar"]
        fontc = "#FFFFFF" if emph else C["ink"]
        bw = max(6, x2 - x1)
        label = s["label_text"]
        inside = text_w(label, 13) + 16 < bw
        cells.append(_vx(f"bar{i}", x1, y, bw, row_h,
                         f"whiteSpace=wrap;html=1;spacing={SPACE};rounded=0;align=center;"
                         f"verticalAlign=middle;fillColor={fill};strokeColor={fill};"
                         f"fontColor={fontc};fontSize=13;",
                         attr_html([label]) if inside else ""))
        if not inside:  # label to the right of the bar
            cells.append(_vx(f"barlbl{i}", x2 + 8, y, max(120, text_w(label, 13) + 16), row_h,
                             f"text;html=1;align=left;verticalAlign=middle;spacing={SPACE};"
                             f"fontSize=13;fontColor={C['red'] if emph else C['ink']};",
                             attr_html([label])))
    # point markers (dashed vertical lines)
    for i, pt in enumerate(m.get("points", []) or []):
        px = sx(pt["date"])
        emph = bool(pt.get("emphasis"))
        col = C["red"] if emph else C["line"]
        cells.append(_ln(f"pt{i}", px, top - 10, px, y_axis_bottom,
                         f"endArrow=none;html=1;dashed=1;strokeColor={col};"))
        side = pt.get("label_side", "right")
        lw = max(90, text_w(pt["label_text"], 12) + 12)
        lx = px + 6 if side == "right" else px - 6 - lw
        cells.append(_vx(f"ptl{i}", lx, top - 28, lw, 18,
                         f"text;html=1;align={'left' if side=='right' else 'right'};"
                         f"fontSize=12;fontColor={col};", attr_html([pt["label_text"]])))
    return cells, W, H


# ========================================================= COMPARE ========
def _build_compare(m):
    cols = m["columns"]
    rows = m["rows"]
    DIM_W, COL_W = 190, 250
    x_dim = MARGIN
    xs = [x_dim + DIM_W + j * COL_W for j in range(len(cols))]
    top = MARGIN + TITLE_H + 20

    def cell_lines(text, w):
        return _wrap(text, w)

    # header height
    hdr_lines = [cell_lines(c["title"], COL_W) for c in cols]
    hdr_h = max([len(l) for l in hdr_lines] + [1]) * LINE_H + 2 * PAD_Y
    W = xs[-1] + COL_W + MARGIN
    cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>',
             _title_cell(m.get("title_text", ""), MARGIN, W - 2 * MARGIN)]
    # header row
    cells.append(_vx("hdr_dim", x_dim, top, DIM_W, hdr_h,
                     _wrap_style(C["card_fill"], C["card_stroke"], C["ink"], "rounded=0;") + "fontStyle=1;",
                     attr_html(["对比维度"])))
    for j, c in enumerate(cols):
        emph = bool(c.get("emphasis"))
        style = (_wrap_style(C["red"], C["red"], "#FFFFFF", "rounded=0;") if emph
                 else _wrap_style(C["circle"], C["circle"], "#FFFFFF", "rounded=0;")) + "fontStyle=1;"
        cells.append(_vx(f"hdr{j}", xs[j], top, COL_W, hdr_h, style, attr_html(cell_lines(c["title"], COL_W))))
    # body rows
    y = top + hdr_h
    for i, r in enumerate(rows):
        dl = cell_lines(r.get("dimension", ""), DIM_W)
        cl = [cell_lines(r["cells"].get(c["id"], ""), COL_W) for c in cols]
        rh = max([len(dl)] + [len(x) for x in cl]) * LINE_H + 2 * PAD_Y
        cells.append(_vx(f"dim{i}", x_dim, y, DIM_W, rh,
                         _wrap_style(C["card_fill"], C["card_stroke"], C["ink"], "rounded=0;") + "fontStyle=1;",
                         attr_html(dl)))
        for j, c in enumerate(cols):
            emph = bool(c.get("emphasis"))
            fill = "#FBEBEB" if emph else C["bg"]
            cells.append(_vx(f"cell{i}_{j}", xs[j], y, COL_W, rh,
                             _wrap_style(fill, C["card_stroke"], C["ink"], "rounded=0;align=left;"),
                             attr_html(cl[j])))
        y += rh
    H = y + MARGIN
    return cells, W, H


# ============================================================ ASSEMBLE =====
def build_model(m):
    """Return (mxfile_xml, width, height) for any supported layout."""
    layout = m.get("layout")
    if layout in GRAPH_LAYOUTS:
        cells, W, H = _build_graph(m)
    elif layout == "numbered_point_timeline":
        cells, W, H = _build_timeline(m, dated=False)
    elif layout == "dated_point_timeline":
        cells, W, H = _build_timeline(m, dated=True)
    elif layout == "proportional_gantt":
        cells, W, H = _build_gantt(m)
    elif layout == "comparison_table":
        cells, W, H = _build_compare(m)
    else:
        raise RuntimeError(f'draw.io export does not support layout "{layout}".')

    model = (f'<mxGraphModel dx="{W:.0f}" dy="{H:.0f}" grid="1" gridSize="10" '
             f'guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" '
             f'pageScale="1" pageWidth="{max(850,int(W)):d}" '
             f'pageHeight="{max(1100,int(H)):d}" math="0" shadow="0">'
             f'<root>{"".join(cells)}</root></mxGraphModel>')
    # No `modified` attribute. It is optional in mxfile, and a wall-clock stamp
    # made the same map produce a different FILE on every run — which quietly
    # broke the safety net this project leans on hardest, byte-comparing a
    # rebuild against a known-good one. The filesystem already records when a
    # file was written; determinism is worth more here than restating it.
    mxfile = (f'<mxfile host="mqc-litigation-visual-redraw" '
              f'agent="mqc-litigation-visual-redraw" version="21.6.5" type="device">'
              f'<diagram id="litigation" name="{esc(m.get("title_text","图"))}">'
              f'{model}</diagram></mxfile>')
    return mxfile, int(W), int(H)


def embed_in_svg(svg_text, mxfile_xml):
    """Turn a master SVG into a .drawio.svg by adding draw.io's `content`
    attribute (the embedded, editable model) to the root <svg> element. The
    file stays a valid SVG that renders everywhere AND opens editable in
    draw.io. Uncompressed on purpose (stdlib-only, human-inspectable)."""
    idx = svg_text.find("<svg")
    if idx < 0:
        return svg_text
    end = svg_text.find(">", idx)
    if end < 0:
        return svg_text
    return svg_text[:end] + f' content="{esc(mxfile_xml)}"' + svg_text[end:]


def export(mapfile, base):
    m = load_map(mapfile)
    mxfile, w, h = build_model(m)
    out = base + ".drawio"
    open(out, "w", encoding="utf-8").write(mxfile)
    print(f"drawio: {out}  {w}x{h}")
    return mxfile


def theme_drawio(xml, mode, hub_id=None):
    """Re-colour the editable drawio model to match a visual mode.

    The SVG themes are post-processors, and so is this: the exporter keeps emitting
    the 奇川风 palette, and here we rewrite the `fillColor=/strokeColor=/fontColor=`
    values so the editable file a user opens in draw.io matches the figure they were
    given. Geometry, ids, and structure are untouched — only colours change.
    """
    if mode not in ("baimiao", "guizang"):
        return xml
    INK = "#111111"
    if mode == "baimiao":                       # pure black line-art: white fills, black lines
        xml = re.sub(r'fillColor=#(?!FFFFFF)[0-9A-Fa-f]{6}', 'fillColor=#FFFFFF', xml)
        xml = re.sub(r'strokeColor=#[0-9A-Fa-f]{6}', f'strokeColor={INK}', xml)
        xml = re.sub(r'fontColor=#[0-9A-Fa-f]{6}', f'fontColor={INK}', xml)
        # …and square the modules off, exactly as the SVG does. A white box with a
        # thin black rule reads as a filing form; a generous radius on it reads
        # soft and app-like. `arcSize=50` marks a terminal STADIUM — a semantic
        # cue for start/end, not decoration — so that one keeps its shape.
        xml = re.sub(r'rounded=1;(?!arcSize=50)', 'rounded=0;', xml)
        return xml
    # 歸藏风 — Klein blue + grey + white
    IKB, DINK, SUB, LINE, BORDER = "#002FA7", "#333333", "#737373", "#BDBDBD", "#D4D4D2"

    def _lum(c):
        r, g, b = int(c[1:3], 16), int(c[3:5], 16), int(c[5:7], 16)
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255
    # solid/emphasis fills -> blue; light fills -> white
    xml = re.sub(r'fillColor=(#[0-9A-Fa-f]{6})',
                 lambda m: 'fillColor=' + (IKB if _lum(m.group(1)) < 0.45 else '#FFFFFF'), xml)
    # borders soft grey; connectors soft grey (blue is for blocks, never lines)
    xml = re.sub(r'strokeColor=(#[0-9A-Fa-f]{6})',
                 lambda m: 'strokeColor=' + (IKB if m.group(1).upper() == IKB else
                                             (BORDER if _lum(m.group(1)) > 0.75 else LINE)), xml)
    # text: white on solid blue is already emitted as #FFFFFF; the rest -> ink / sub-grey
    xml = re.sub(r'fontColor=(#[0-9A-Fa-f]{6})',
                 lambda m: 'fontColor=' + (m.group(1) if m.group(1).upper() == "#FFFFFF"
                                           else (SUB if _lum(m.group(1)) > 0.4 else DINK)), xml)
    if IKB not in xml and hub_id:
        # A relation map has no dark fill to promote, so the KEY node (the hub) becomes
        # the solid blue block — the same emphasis the SVG theme gives it.
        def _hub(mm):
            cell = mm.group(0)
            cell = re.sub(r'fillColor=#[0-9A-Fa-f]{6}', f'fillColor={IKB}', cell)
            cell = re.sub(r'strokeColor=#[0-9A-Fa-f]{6}', f'strokeColor={IKB}', cell)
            return re.sub(r'fontColor=#[0-9A-Fa-f]{6}', 'fontColor=#FFFFFF', cell)
        xml = re.sub(r'<mxCell id="' + re.escape(hub_id) + r'"[^>]*?/?>', _hub, xml, count=1)
    return xml


def main(mapfile, base="final"):
    try:
        export(mapfile, base)
        return 0
    except RuntimeError as e:
        print(f"drawio skipped: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "final"))
