#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Two-column comparison table (对比表). Two positions/options compared row by row
across shared dimensions — the clearest, highest-fidelity way to show "A vs B"
(两裁判要旨 / 两诉讼方案 / 两罪名). Deterministic self-layout: the model supplies
only the two column headers and the per-dimension cells; this script computes every
coordinate. Restrained house style (neutral gray + one deep red, rounded cards,
Song title) — no new visual vocabulary.

semantic map:
  layout: "comparison_table"
  columns: [ {id, title, emphasis?}, {id, title, emphasis?} ]   # exactly 2
  rows: [ { dimension: "焦点二·责任性质",
            cells: { "<col id>": "…", "<col id>": "…" } }, ... ]

Usage: python render_compare.py <map.json> <out.svg>
"""
import sys
from common import C, FS, RADIUS, FONT, TITLE_FONT, esc, wrap, text_w, svg_open, load_map

FS_H = FS["node_title"]        # column header
FS_DIM = FS["subtitle"]        # dimension label (left gutter)
FS_CELL = FS["node_title"]     # cell body
LH = 23
PADX, PADY = 18, 14
_THEME = None
DIM_W = 150                    # left gutter width for the dimension label
COL_W = 300                    # each comparison column
COL_GAP = 26
DIM_GAP = 20
MARGIN = 64
TITLE_H = 116
ROW_GAP = 14
RX = RADIUS["card"]


def _wrap_cell(t, w):
    return wrap(t, FS_CELL, w - 2 * PADX)


def render(m):
    cols = m["columns"]
    if len(cols) != 2:
        raise RuntimeError("comparison_table needs exactly 2 columns (A vs B); "
                           f"got {len(cols)}. For >2 options use a different layout.")
    rows = m["rows"]
    cid = [c["id"] for c in cols]

    x_dim = MARGIN
    x_col = [MARGIN + DIM_W + DIM_GAP,
             MARGIN + DIM_W + DIM_GAP + COL_W + COL_GAP]
    width = x_col[1] + COL_W + MARGIN

    # per-row height = tallest cell (or the dimension label)
    row_h = []
    row_lines = []
    for r in rows:
        cell_lines = {c: _wrap_cell(r["cells"].get(c, ""), COL_W) for c in cid}
        dim_lines = wrap(r.get("dimension", ""), FS_DIM, DIM_W - 8)
        n = max([len(v) for v in cell_lines.values()] + [len(dim_lines)] + [1])
        row_h.append(n * LH + 2 * (24 if _THEME == "guizang" else PADY))
        row_lines.append((dim_lines, cell_lines))

    header_h = max(len(wrap(c["title"], FS_H, COL_W - 2 * PADX)) for c in cols) * LH + 2 * PADY
    total = TITLE_H + header_h + ROW_GAP + sum(h + ROW_GAP for h in row_h) + MARGIN
    height = total

    S = [svg_open(width, height)]
    S.append(f'<text data-role="title" x="{width/2:.0f}" y="{MARGIN+18}" font-size="{FS["doc_title"]}" '
             f'font-weight="700" font-family="{TITLE_FONT}" fill="{C["ink"]}" stroke="{C["ink"]}" '
             f'stroke-width="0.3" text-anchor="middle">{esc(m["title_text"])}</text>')

    # column headers
    hy = TITLE_H
    for k, c in enumerate(cols):
        emph = c.get("emphasis")
        fill = C["red"] if emph else "#374151"
        tcol = C["white"]
        S.append(f'<rect x="{x_col[k]:.1f}" y="{hy:.1f}" width="{COL_W}" height="{header_h}" '
                 f'rx="{RX}" fill="{fill}"/>')
        hl = wrap(c["title"], FS_H, COL_W - 2 * PADX)
        ty = hy + header_h/2 - (len(hl)-1)*LH/2 + FS_H*0.35
        for ln in hl:
            S.append(f'<text x="{x_col[k]+COL_W/2:.1f}" y="{ty:.1f}" font-size="{FS_H}" font-weight="700" '
                     f'fill="{tcol}" text-anchor="middle">{esc(ln)}</text>')
            ty += LH

    # rows
    y = hy + header_h + ROW_GAP
    for i, r in enumerate(rows):
        h = row_h[i]
        dim_lines, cell_lines = row_lines[i]
        # dimension label (left gutter, no box, ink2)
        dy = y + h/2 - (len(dim_lines)-1)*LH/2 + FS_DIM*0.35
        for ln in dim_lines:
            S.append(f'<text x="{x_dim:.1f}" y="{dy:.1f}" font-size="{FS_DIM}" font-weight="700" '
                     f'fill="{C["ink2"]}" text-anchor="start">{esc(ln)}</text>')
            dy += LH
        # two cells
        for k, c in enumerate(cid):
            emph = cols[k].get("emphasis")
            fill = "#FBEBEB" if emph else C["card_fill"]
            stroke = C["red"] if emph else C["card_stroke"]
            S.append(f'<rect x="{x_col[k]:.1f}" y="{y:.1f}" width="{COL_W}" height="{h}" rx="{RX}" '
                     f'fill="{fill}" stroke="{stroke}" stroke-width="1"/>')
            ls = cell_lines[c]
            cy = y + h/2 - (len(ls)-1)*LH/2 + FS_CELL*0.35
            for ln in ls:
                S.append(f'<text x="{x_col[k]+COL_W/2:.1f}" y="{cy:.1f}" font-size="{FS_CELL}" '
                         f'fill="{C["ink"]}" text-anchor="middle">{esc(ln)}</text>')
                cy += LH
        y += h + ROW_GAP

    S.append('</svg>')
    return "\n".join(S), int(width), int(height)


def main(mapfile, out):
    svg, w, h = render(load_map(mapfile))
    open(out, "w", encoding="utf-8").write(svg)
    print(f"[compare] wrote {out}  {w}x{h}  ratio={w/h:.2f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "out.svg")
