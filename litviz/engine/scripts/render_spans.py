#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Proportional gantt / period-timeline. For durations that overlap or leave
gaps (limitation periods, guarantee periods, performance windows). The axis is
DATE-PROPORTIONAL — bar length and overlap carry legal meaning, so it must not
be equidistant. Each period gets its own row.

Usage: python render_spans.py <semantic-map.json> <out.svg>
"""
import sys
from common import C, FS, DASH, TITLE_FONT, esc, text_w, parse_date, svg_open, load_map

FS_TITLE, FS_LABEL, FS_DATE, FS_YEAR, FS_PE = FS["doc_title"], 13, FS["note"], FS["axis_year"], 13
BAR_H, ROW_H = 30, 58
LEFT, PAD_L, RIGHT = 60, 24, 70     # plot margins (labels sit in/near the bars, no gutter)
TOP, PE_ZONE, BOT = 100, 96, 64
ARROW = 12
TARGET_RATIO = 1.9
MIN_WIDTH = 1200


def _check_dates(m):
    bad = []
    items = [("axis.start", m["axis"]["start"]), ("axis.end", m["axis"]["end"])]
    for s in m.get("spans", []):
        items += [(f'{s["id"]}.from', s["from"]), (f'{s["id"]}.to', s["to"])]
    for p in m.get("points", []):
        items.append((f'{p["id"]}.date', p["date"]))
    for field, val in items:
        try:
            parse_date(val)
        except Exception:
            bad.append(f'{field}="{val}"')
    if bad:
        raise RuntimeError("proportional_gantt needs dates as YYYY/M/D. "
                           "Un-parseable: " + ", ".join(bad) +
                           ". Fix the semantic map (keep original text in a label if a date is fuzzy).")
    # warn (don't fail) on reversed spans
    for s in m.get("spans", []):
        if parse_date(s["from"]) > parse_date(s["to"]):
            print(f'  [warn] span {s["id"]} has from > to (reversed); bar may render empty')


def render(m):
    _check_dates(m)
    spans = m["spans"]
    points = m.get("points", [])
    # axis auto-covers every date, so a bar/point can never fall off-canvas even
    # if the given axis range is too narrow.
    lows = [parse_date(m["axis"]["start"])] + [parse_date(s["from"]) for s in spans] + [parse_date(p["date"]) for p in points]
    highs = [parse_date(m["axis"]["end"])] + [parse_date(s["to"]) for s in spans] + [parse_date(p["date"]) for p in points]
    a0, a1 = min(lows), max(highs)
    span_days = max(1, (a1 - a0).days)
    n = len(spans)

    plot_top = TOP + PE_ZONE
    axis_y = plot_top + n * ROW_H + 12
    height = axis_y + BOT
    width = max(MIN_WIDTH, int(height * TARGET_RATIO))
    plot_w = width - LEFT - PAD_L - RIGHT

    def X(d):
        return LEFT + PAD_L + (d - a0).days / span_days * plot_w

    S = [svg_open(width, height)]
    S.append(f'<text data-role="title" x="{width/2}" y="46" font-size="{FS_TITLE}" font-weight="700" font-family="{TITLE_FONT}" '
             f'fill="{C["ink"]}" stroke="{C["ink"]}" stroke-width="0.3" text-anchor="middle">{esc(m["title_text"])}</text>')

    # gridlines + year axis
    S.append('<g data-role="axis">')
    for yr in range(a0.year, a1.year + 1):
        from datetime import date as _d
        gx = X(_d(yr, 1, 1))
        S.append(f'<line x1="{gx:.1f}" y1="{plot_top-6}" x2="{gx:.1f}" y2="{axis_y}" stroke="{C["grid"]}" stroke-width="1"/>')
        S.append(f'<text x="{gx:.1f}" y="{axis_y+22}" font-size="{FS_YEAR}" fill="{C["ink2"]}" text-anchor="middle">{yr}</text>')
    S.append(f'<line x1="{LEFT:.1f}" y1="{axis_y}" x2="{width-RIGHT:.1f}" y2="{axis_y}" stroke="{C["line"]}" stroke-width="1.5"/>')
    S.append('</g>')

    # point events: dashed verticals + stacked labels above the plot
    S.append('<g data-role="points">')
    for e in points:
        ex = X(parse_date(e["date"]))
        emph = e.get("emphasis")
        col = C["red"] if emph else C["ink2"]
        S.append(f'<line x1="{ex:.1f}" y1="{TOP+14}" x2="{ex:.1f}" y2="{axis_y}" stroke="{col}" '
                 f'stroke-width="{1.4 if emph else 1}" stroke-dasharray="{DASH}"/>')
        lvl = e.get("label_level", 0)
        side = e.get("label_side", "center")
        ly = TOP + 22 + lvl * 40
        anchor = {"left": "end", "right": "start", "center": "middle"}[side]
        lx = ex + (8 if side == "right" else -8 if side == "left" else 0)
        S.append(f'<text x="{lx:.1f}" y="{ly}" font-size="{FS_PE}" font-weight="600" fill="{col}" text-anchor="{anchor}">{esc(e["date"])}</text>')
        S.append(f'<text x="{lx:.1f}" y="{ly+18}" font-size="{FS_PE}" fill="{col}" text-anchor="{anchor}">{esc(e["label_text"])}</text>')
    S.append('</g>')

    # period bars (right-angle, never rounded); label centered inside, else hugging left edge
    S.append('<g data-role="spans">')
    for i, sp in enumerate(spans):
        x0, x1 = X(parse_date(sp["from"])), X(parse_date(sp["to"]))
        cy = plot_top + i * ROW_H + (ROW_H - BAR_H) / 2
        emph = sp.get("emphasis")
        fill = C["red"] if emph else C["bar"]
        lbl = sp["label_text"]
        lw = text_w(lbl, FS_LABEL)
        ty = cy + BAR_H / 2 + FS_LABEL * 0.36
        directional = sp.get("directional")
        body_end = (x1 - ARROW) if directional else x1
        bw_eff = body_end - x0
        S.append(f'<g data-role="span" data-id="{sp["id"]}">')
        if directional:
            S.append(f'<rect x="{x0:.1f}" y="{cy:.1f}" width="{max(0,body_end-x0):.1f}" height="{BAR_H}" fill="{fill}"/>')
            S.append(f'<path d="M{body_end:.1f},{cy:.1f} L{x1:.1f},{cy+BAR_H/2:.1f} L{body_end:.1f},{cy+BAR_H:.1f} Z" fill="{fill}"/>')
        else:
            S.append(f'<rect x="{x0:.1f}" y="{cy:.1f}" width="{max(0,bw_eff):.1f}" height="{BAR_H}" fill="{fill}"/>')
        if lw + 16 <= bw_eff:               # fits inside -> centered (white on red, ink on gray)
            tcol = C["white"] if emph else C["ink"]
            S.append(f'<text x="{(x0+body_end)/2:.1f}" y="{ty:.1f}" font-size="{FS_LABEL}" font-weight="600" '
                     f'fill="{tcol}" text-anchor="middle">{esc(lbl)}</text>')
        else:                                # too long -> hug the left edge, right-aligned
            S.append(f'<text x="{x0-8:.1f}" y="{ty:.1f}" font-size="{FS_LABEL}" font-weight="600" '
                     f'fill="{C["ink"]}" text-anchor="end">{esc(lbl)}</text>')
        S.append(f'<text x="{(x0+x1)/2:.1f}" y="{cy+BAR_H+15:.1f}" font-size="{FS_DATE}" '
                 f'fill="{C["ink2"]}" text-anchor="middle">{esc(sp["from"]+" - "+sp["to"])}</text>')
        S.append('</g>')
    S.append('</g></svg>')
    return "\n".join(S), width, height


def main(mapfile, out):
    svg, w, h = render(load_map(mapfile))
    open(out, "w", encoding="utf-8").write(svg)
    print(f"[spans] wrote {out}  {w}x{h}  ratio={w/h:.2f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "out.svg")
