#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Dated point-timeline — DATE-PROPORTIONAL. Point events (not periods) placed
at their TRUE position on a real-time axis, so the distance between two events is
faithful to the elapsed time. The axis is a slightly-thick light-gray BAR that
carries an honest ruler (year ticks, or year+month ticks for a short span); the
precise date sits in each card. No node dots — the connector meets the bar edge.

Best for LONG, well-separated chronologies (诉讼时效、长期履行). For events packed
close together (a few days apart), use numbered_point_timeline instead — there the
gaps intentionally carry no meaning.

Usage: python render_dated.py <semantic-map.json> <out.svg>
"""
import sys
from datetime import date as _date
from common import C, FS, RADIUS, TITLE_FONT, esc, wrap, svg_open, load_map, parse_date

CARD_W = 214
PAD_X, PAD_Y = 16, 13
_THEME = None
LH = 22
BAR_H = 14                 # light-gray axis bar (slightly thick, not heavy)
BAR_FILL = "#E5E7EB"
TICK = "#FFFFFF"           # segment ticks: PAPER-WHITE, not darker than the bar.
                           # 作者定的（2026-08-19）：深灰的分格线把时间带切成一段段，
                           # 读者会以为那些段本身有含义。白线只是把整条带轻轻分开，
                           # 刻度的信息由下方的年份标签承担，不由线的深浅承担。
                           # 与 v2 的 D13 是同一条（v2 那边也是「与纸同色的白」）。
CONNECT = 56               # bar -> card gap
LEFT, RIGHT = 132, 132
YEAR_MIN_PX = 118          # min width per year so the ruler stays readable
MONTH_MIN_PX = 66          # min width per month (short spans)
RX = RADIUS["card"]
TARGET_RATIO = 1.9
MIN_WIDTH = 1180

FS_DATE, FS_BODY, FS_TITLE, FS_UNIT = FS["subtitle"], FS["node_title"], FS["doc_title"], FS["note"]


def _check_dates(evs):
    bad = [f'{e.get("id","?")}="{e.get("date","")}"' for e in evs
           if not _try(e.get("date"))]
    if bad:
        raise RuntimeError("dated_point_timeline needs every event's date as "
                           "YYYY/M/D. Un-parseable: " + ", ".join(bad) +
                           ". (For undated / clustered events use numbered_point_timeline.)")


def _try(s):
    try:
        parse_date(s); return True
    except Exception:
        return False


def _months_between(a0, a1):
    return (a1.year - a0.year) * 12 + (a1.month - a0.month)


def card_lines(ev):
    return wrap(ev["text"], FS_BODY, CARD_W - PAD_X * 2)


def card_h(ev):
    head = (FS_DATE + 8) if ev.get("date_text") else 0
    return (26 if _THEME == "guizang" else PAD_Y) * 2 + head + len(card_lines(ev)) * LH


def render(m):
    evs = m["events"]
    _check_dates(evs)
    ds = [parse_date(e["date"]) for e in evs]
    lo, hi = min(ds), max(ds)

    # unit granularity: long multi-year span -> year; short span -> month
    unit = m.get("axis_unit")
    if unit not in ("year", "month"):
        unit = "year" if (hi.year - lo.year) >= 3 else "month"

    # pad axis to whole-unit boundaries so first/last unit shows fully
    if unit == "year":
        a0, a1 = _date(lo.year, 1, 1), _date(hi.year + 1, 1, 1)
        n_units = a1.year - a0.year
    else:
        a0 = _date(lo.year, lo.month, 1)
        ey, em = (hi.year + (hi.month // 12)), (hi.month % 12 + 1)
        a1 = _date(ey, em, 1)
        n_units = _months_between(a0, a1)
    span = max(1, (a1 - a0).days)

    unit_min = YEAR_MIN_PX if unit == "year" else MONTH_MIN_PX
    plot_w = max(n_units * unit_min, MIN_WIDTH - LEFT - RIGHT)
    width = LEFT + RIGHT + plot_w

    def X(d):
        return LEFT + (d - a0).days / span * plot_w

    up = [e for e in evs if e.get("band", "up") == "up"]
    dn = [e for e in evs if e.get("band", "up") == "down"]
    max_up = max((card_h(e) for e in up), default=0)
    max_dn = max((card_h(e) for e in dn), default=0)
    title_zone = 124
    content_h = title_zone + max_up + CONNECT + BAR_H + CONNECT + max_dn + 60
    height = max(content_h, int(width / TARGET_RATIO))
    pad = (height - content_h) / 2
    axis_y = pad + title_zone + max_up + CONNECT + BAR_H / 2

    S = [svg_open(width, height)]
    S.append(f'<text data-role="title" x="{width/2}" y="{pad+52}" font-size="{FS_TITLE}" '
             f'font-weight="700" font-family="{TITLE_FONT}" fill="{C["ink"]}" stroke="{C["ink"]}" '
             f'stroke-width="0.3" text-anchor="middle">{esc(m["title_text"])}</text>')

    # the light-gray axis bar
    S.append(f'<rect data-role="axis" x="{LEFT:.1f}" y="{axis_y-BAR_H/2:.1f}" '
             f'width="{plot_w:.1f}" height="{BAR_H}" rx="0" fill="{BAR_FILL}"/>')   # a time RULER is a bar, not a pill

    # honest ruler: ticks at true unit boundaries; label centred in each unit span
    bounds = []
    if unit == "year":
        for yr in range(a0.year, a1.year + 1):
            bounds.append((_date(yr, 1, 1), str(yr)))
    else:
        y, mth = a0.year, a0.month
        for _ in range(n_units + 1):
            bounds.append((_date(y, mth, 1), f"{y}.{mth:02d}"))
            mth += 1
            if mth > 12:
                mth = 1; y += 1
    for i, (bd, _lab) in enumerate(bounds):
        bx = X(bd)
        if i not in (0, len(bounds) - 1):          # interior ticks divide the bar
            S.append(f'<line x1="{bx:.1f}" y1="{axis_y-BAR_H/2:.1f}" x2="{bx:.1f}" '
                     f'y2="{axis_y+BAR_H/2:.1f}" stroke="{TICK}" stroke-width="1"/>')
    for i in range(len(bounds) - 1):               # label centred in each unit span
        cx = (X(bounds[i][0]) + X(bounds[i+1][0])) / 2
        S.append(f'<text x="{cx:.1f}" y="{axis_y+FS_UNIT*0.35:.1f}" font-size="{FS_UNIT}" '
                 f'font-weight="700" fill="{C["ink2"]}" text-anchor="middle">{bounds[i][1]}</text>')

    def draw_card(ev, cx, top):
        emph = ev.get("emphasis")
        lines = card_lines(ev)
        h = card_h(ev)
        fill = C["red"] if emph else C["card_fill"]
        date_col = C["white"] if emph else C["ink2"]
        body_col = C["white"] if emph else C["ink"]
        S.append(f'<g data-role="event" data-id="{ev["id"]}">')
        if emph:
            S.append(f'<rect x="{cx-CARD_W/2:.1f}" y="{top:.1f}" width="{CARD_W}" height="{h}" rx="{RX}" fill="{fill}"/>')
        else:
            S.append(f'<rect x="{cx-CARD_W/2:.1f}" y="{top:.1f}" width="{CARD_W}" height="{h}" rx="{RX}" '
                     f'fill="{fill}" stroke="{C["card_stroke"]}" stroke-width="1"/>')
        ty = top + (26 if _THEME == "guizang" else PAD_Y) + FS_DATE
        if ev.get("date_text"):
            S.append(f'<text x="{cx:.1f}" y="{ty:.1f}" font-size="{FS_DATE}" font-weight="600" '
                     f'fill="{date_col}" text-anchor="middle">{esc(ev["date_text"])}</text>')
            ty += FS_DATE + 8
        ty += FS_BODY - FS_DATE
        for ln in lines:
            S.append(f'<text x="{cx:.1f}" y="{ty:.1f}" font-size="{FS_BODY}" fill="{body_col}" '
                     f'text-anchor="middle">{esc(ln)}</text>')
            ty += LH
        S.append('</g>')

    def connector(cx, y0, y1):
        S.append(f'<line x1="{cx:.1f}" y1="{y0:.1f}" x2="{cx:.1f}" y2="{y1:.1f}" stroke="{C["line_soft"]}" stroke-width="1.4"/>')

    for i, ev in enumerate(evs):
        cx = X(ds[i])
        if ev.get("band", "up") == "up":
            top = axis_y - BAR_H / 2 - CONNECT - card_h(ev)
            connector(cx, top + card_h(ev), axis_y - BAR_H / 2)
            draw_card(ev, cx, top)
        else:
            top = axis_y + BAR_H / 2 + CONNECT
            connector(cx, axis_y + BAR_H / 2, top)
            draw_card(ev, cx, top)

    S.append('</svg>')
    return "\n".join(S), int(width), int(height)




def main(mapfile, out):
    svg, w, h = render(load_map(mapfile))
    open(out, "w", encoding="utf-8").write(svg)
    print(f"[dated] wrote {out}  {w}x{h}  ratio={w/h:.2f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "out.svg")
