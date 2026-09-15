#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Numbered point-timeline (equidistant). For fact chronologies made of
discrete dated events. Cards alternate above/below a horizontal axis with
numbered circle nodes. A4-landscape-friendly aspect ratio.

Usage: python render_points.py <semantic-map.json> <out.svg>
"""
import sys
from common import C, FS, RADIUS, TITLE_FONT, esc, wrap, svg_open, load_map

CARD_W = 214
PAD_X, PAD_Y = 16, 13
_THEME = None
LH = 22
R = 24                    # numbered circle radius
CONNECT = 60              # axis -> card gap
COL_GAP_MIN = 190
MARGIN_X = 92
TARGET_RATIO = 1.45       # width : height, ~A4 landscape
RX = RADIUS["card"]

FS_DATE, FS_BODY, FS_TITLE, FS_NUM = FS["subtitle"], FS["node_title"], FS["doc_title"], FS["num"]


def card_lines(ev):
    return wrap(ev["text"], FS_BODY, CARD_W - PAD_X * 2)


def card_h(ev):
    head = (FS_DATE + 8) if ev.get("date_text") else 0
    return (26 if _THEME == "guizang" else PAD_Y) * 2 + head + len(card_lines(ev)) * LH


def render(m):
    evs = m["events"]
    n = len(evs)
    col_gap = max(CARD_W + 22, COL_GAP_MIN)
    width = MARGIN_X * 2 + (n - 1) * col_gap + CARD_W
    xof = [MARGIN_X + CARD_W / 2 + i * col_gap for i in range(n)]

    up = [e for e in evs if e.get("band","up") == "up"]
    dn = [e for e in evs if e.get("band","up") == "down"]
    max_up = max((card_h(e) for e in up), default=0)
    max_dn = max((card_h(e) for e in dn), default=0)

    title_zone = 124
    content_h = title_zone + max_up + CONNECT + 2 * R + CONNECT + max_dn + 56
    height = max(content_h, int(width / TARGET_RATIO))
    pad = (height - content_h) / 2
    axis_y = pad + title_zone + max_up + CONNECT + R

    S = [svg_open(width, height)]
    cx_all = width / 2
    S.append(f'<text data-role="title" x="{cx_all}" y="{pad+52}" font-size="{FS_TITLE}" '
             f'font-weight="700" font-family="{TITLE_FONT}" fill="{C["ink"]}" stroke="{C["ink"]}" stroke-width="0.3" text-anchor="middle">{esc(m["title_text"])}</text>')
    S.append(f'<line data-role="axis" x1="{MARGIN_X}" y1="{axis_y}" x2="{width-MARGIN_X}" '
             f'y2="{axis_y}" stroke="{C["line_soft"]}" stroke-width="2.5"/>')   # square ends: the axis is a bar, not a pill

    def draw_card(ev, cx, top):
        emph = ev.get("emphasis")
        lines = card_lines(ev)
        h = card_h(ev)
        fill = C["red"] if emph else C["card_fill"]
        date_col = C["white"] if emph else C["ink2"]
        body_col = C["white"] if emph else C["ink"]
        S.append(f'<g data-role="event" data-id="{ev["id"]}">')
        if emph:  # deep-red solid block + white text; NO border, NO accent bar
            S.append(f'<rect x="{cx-CARD_W/2}" y="{top}" width="{CARD_W}" height="{h}" rx="{RX}" fill="{fill}"/>')
        else:     # clean white card, thin gray border, small radius
            S.append(f'<rect x="{cx-CARD_W/2}" y="{top}" width="{CARD_W}" height="{h}" rx="{RX}" '
                     f'fill="{fill}" stroke="{C["card_stroke"]}" stroke-width="1"/>')
        ty = top + (26 if _THEME == "guizang" else PAD_Y) + FS_DATE
        if ev.get("date_text"):
            S.append(f'<text x="{cx}" y="{ty}" font-size="{FS_DATE}" font-weight="600" '
                     f'fill="{date_col}" text-anchor="middle">{esc(ev["date_text"])}</text>')
            ty += FS_DATE + 8
        ty += FS_BODY - FS_DATE
        for ln in lines:
            S.append(f'<text x="{cx}" y="{ty}" font-size="{FS_BODY}" fill="{body_col}" '
                     f'text-anchor="middle">{esc(ln)}</text>')
            ty += LH
        S.append('</g>')

    def connector(cx, y0, y1):
        S.append(f'<line x1="{cx}" y1="{y0}" x2="{cx}" y2="{y1}" stroke="{C["line_soft"]}" stroke-width="1.4"/>')

    for i, ev in enumerate(evs):
        cx = xof[i]
        if ev.get("band","up") == "up":
            top = axis_y - R - CONNECT - card_h(ev)
            connector(cx, top + card_h(ev), axis_y - R)
            draw_card(ev, cx, top)
        else:
            top = axis_y + R + CONNECT
            connector(cx, axis_y + R, top)
            draw_card(ev, cx, top)

    for i, ev in enumerate(evs):
        cx = xof[i]
        fill = C["red"] if ev.get("emphasis") else C["circle"]
        S.append(f'<circle data-role="node" data-id="{ev["id"]}" cx="{cx}" cy="{axis_y}" r="{R}" fill="{fill}"/>')
        S.append(f'<text x="{cx}" y="{axis_y+FS_NUM*0.35}" font-size="{FS_NUM}" font-weight="700" '
                 f'fill="{C["white"]}" text-anchor="middle">{esc(ev["id"])}</text>')

    S.append('</svg>')
    return "\n".join(S), int(width), int(height)


def main(mapfile, out):
    svg, w, h = render(load_map(mapfile))
    open(out, "w", encoding="utf-8").write(svg)
    print(f"[points] wrote {out}  {w}x{h}  ratio={w/h:.2f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "out.svg")
