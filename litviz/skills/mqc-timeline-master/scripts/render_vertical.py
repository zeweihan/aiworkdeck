#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PROTOTYPE vertical single-column timeline renderer.

Not production code. Written to answer three questions with a real figure:
  1. does the seven-layer segmentation survive real judgment sentences?
  2. how many layers actually fit in the A4-portrait width budget?
  3. what does the outlier / low-certainty / quote-depth marking look like?

Reuses v1's common.py for CJK wrapping (禁则) and text measurement — the shared
kernel, not a second copy.
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
V1 = os.path.join(HERE, "..", "..", "mqc-litigation-visual-redraw", "scripts")
sys.path.insert(0, HERE)
sys.path.insert(0, V1)
import paper  # noqa: E402
from common import wrap, text_w, esc, C, FONT, TITLE_FONT, FS  # noqa
from geom import rule, dot_metrics, snap_centre, wrap_atomic  # noqa

# ---- A4 portrait budget (from the layout-budget spec) --------------------
W = paper.PORT_W        # A4 竖版画布宽，来自 paper.py
PAGE_H = paper.PORT_H   # 一页 A4 竖版的正文高度，来自 paper.py
PAD_X = 24              # figure side margin
RAIL_W = 78             # DEFAULT ONLY — measured per figure in render()
GAP = 16                # rail -> card
CARD_W = W - PAD_X * 2 - RAIL_W - GAP
CARD_PAD = 14
RX_OUT, RX_IN = 16, 12  # 通篇无直角

FS_T = FS["doc_title"]
FS_L1 = FS["node_title"]
FS_SUB = FS["subtitle"]
FS_NOTE = FS["note"]
LH1, LHS = FS_L1 + 8, FS_SUB + 6
HEAD_GAP = 6            # gap between the head line and the body/list block

TITLE_ZONE = 84         # 标题区只有标题，无副标、无说明字、无下划线
V_GAP = 18              # gap between cards
DOT_R = 6

CERT_MARK = {"exact": "", "range": "区间", "relative": "相对", "order": "仅次序"}

# Card content has EXACTLY two parts and TWO type sizes:
#   head  — one title line (17px, semibold). At most one per card.
#   body  — flowing prose (13px)          } exactly one of these,
#   items — numbered list  (13px)         } never both.
# No third size, no icons, no flags, no audit remarks. Audit remarks live in
# index_note and go to the provenance index, never onto the figure.



def build_lines(ev, _unused=None):
    """-> [(text, fs, kind)] where kind in {head, body, item, item_cont}"""
    inner = CARD_W - CARD_PAD * 2
    out = []
    for ln in wrap_atomic(ev["head"], FS_L1, inner, wrap, text_w):
        out.append((ln, FS_L1, "head"))
    if "items" in ev and "body" in ev:
        raise ValueError(f"event {ev['id']}: body and items must not coexist")
    if ev.get("body"):
        for ln in wrap_atomic(ev["body"], FS_SUB, inner, wrap, text_w):
            out.append((ln, FS_SUB, "body"))
    for i, it in enumerate(ev.get("items", []), start=1):
        marker = f"{i}、"
        ind = text_w(marker, FS_SUB)
        first = True
        for ln in wrap_atomic(it, FS_SUB, inner - ind, wrap, text_w):
            out.append(((marker + ln) if first else ln, FS_SUB,
                        "item" if first else "item_cont"))
            first = False
    return out, [], []


def card_h(lines):
    h = CARD_PAD * 2
    for _, fs, kind in lines:
        h += LH1 if kind == "head" else LHS
    # a head followed by body/items gets a small gap between the two parts
    kinds = [k for _, _, k in lines]
    if "head" in kinds and any(k != "head" for k in kinds):
        h += HEAD_GAP
    return h


def render(m, budget_layers, out_path):
    global RAIL_W, CARD_W
    evs = m["events"]
    lanes = m.get("lanes") or []
    nlane = max(1, len(lanes))
    # The rail must fit the WIDEST date text, not a guessed constant. Real
    # judgments carry ranges ("2021.05.19–05.21") and relative descriptions
    # ("上诉后（无日期）") far wider than a plain date. v1's lint caught the
    # hard-coded 78px running off the canvas.
    RAIL_W = max(78.0, max(text_w(e["time"]["date_text"], FS_SUB) for e in evs) + 8)
    track = W - PAD_X * 2 - RAIL_W - GAP
    LANE_GAP = 12
    CARD_W = (track - LANE_GAP * (nlane - 1)) / nlane
    packs = [build_lines(e, budget_layers) for e in evs]
    heights = [card_h(p[0]) for p in packs]

    # page-break avoidance: a break may never fall inside a card. Walk the
    # stack; if a card would straddle a break, push it wholly past the break.
    # ONE long figure: uniform spacing, nothing else. No guide lines, no
    # nudging cards away from an imaginary page edge — a lawyer is not going to
    # cut along a printed line. Pagination is a SEPARATE output that re-lays the
    # same map into A4 pages; it never leaks into this figure.
    lane_band = (30 + 14) if lanes else 0
    offsets, y0 = [], TITLE_ZONE + lane_band
    for h in heights:
        offsets.append(y0)
        y0 += h + V_GAP
    H = y0 + 40

    S = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
         f'viewBox="0 0 {W} {H}" font-family="{FONT}">',
         f'<rect width="{W}" height="{H}" fill="{C["bg"]}"/>']

    # title —— 折行，不缩字号也不截断。竖版收窄到 623px 之后 30px 的图名会宽出画布。
    _tls = wrap_atomic(m["title_text"], FS_T, W - 48, wrap, text_w) or [m["title_text"]]
    for _i, _ln in enumerate(_tls):
        S.append(f'<text x="{W/2}" y="{52 + _i * 38}" font-size="{FS_T}" '
                 f'font-weight="700" font-family="{TITLE_FONT}" fill="{C["ink"]}" '
                 f'stroke="{C["ink"]}" stroke-width="0.3" '
                 f'text-anchor="middle">{esc(_ln)}</text>')

    rail_x = PAD_X + RAIL_W
    lane_x = {}
    for k, l in enumerate(lanes):
        lane_x[l["id"]] = rail_x + GAP + k * (CARD_W + LANE_GAP)
    base_card_x = rail_x + GAP
    y = TITLE_ZONE
    if lanes:
        # Lane labels are HORIZONTAL and sit in the header band. Never rotated:
        # the exporters' parse_svg honours translate but not rotate, so rotated
        # text lands in the wrong place in pptx / vsdx / drawio.
        for l in lanes:
            lx = lane_x[l["id"]]
            # A lane heading is a label, not a card, so it does not take the
            # card's fill and border. Using the card colour made it
            # indistinguishable from a card to anything reading the output.
            S.append(f'<rect x="{lx}" y="{y}" width="{CARD_W}" height="30" rx="{RX_IN}" '
                     f'fill="{C["grid"]}"/>')
            S.append(f'<text x="{lx + CARD_W/2:.1f}" y="{y + 20}" font-size="{FS_SUB}" '
                     f'font-weight="600" fill="{C["ink"]}" text-anchor="middle">'
                     f'{esc(l["label_text"])}</text>')
        y = TITLE_ZONE + lane_band

    # the vertical axis line
    first_c = offsets[0] + heights[0] / 2
    last_c = offsets[-1] + heights[-1] / 2
    S.append(rule(rail_x, first_c, rail_x, last_c, "axis"))

    for i, (ev, (lines, used, dropped)) in enumerate(zip(evs, packs)):
        h = heights[i]
        y = offsets[i]
        cy = snap_centre(y + h / 2)
        emph = ev.get("emphasis")
        low = ev["time"]["certainty"] != "exact"
        deep = ev.get("quote_depth", 0) > 0

        # date rail: right-aligned date + certainty chip
        t = ev["time"]
        # Vertically CENTRE the date on the dot.
        #
        # One constant cannot do this. Measured against the rendered PNG:
        #   digits  (2019.11.29)   glyph body centre sits at baseline - 0.50em
        #   CJK     (上诉后（无日期）) glyph body centre sits at baseline - 0.38em
        # because digits have no descender while CJK glyphs hang below the
        # baseline. Using the CJK constant (0.35em, what v1 uses for the
        # horizontal year ticks) left the date 1.9px HIGH on a digit-only date.
        # So blend by the CJK share of the string. Residual measured <0.5px.
        txt = t["date_text"]
        cjk = sum(1 for ch in txt if '\u4e00' <= ch <= '\u9fff' or '\u3000' <= ch <= '\u303f'
                  or '\uff00' <= ch <= '\uffef')
        ratio = cjk / max(1, len(txt))
        date_base = cy + FS_SUB * (0.50 - 0.12 * ratio)
        S.append(f'<text x="{rail_x - 14}" y="{date_base:.1f}" font-size="{FS_SUB}" '
                 f'font-weight="600" fill="{C["red"] if emph else C["ink"]}" '
                 f'text-anchor="end">{esc(t["date_text"])}</text>')
        mark = CERT_MARK.get(t["certainty"], "")
        if mark:
            S.append(f'<text x="{rail_x - 14}" y="{date_base + FS_NOTE + 4:.1f}" '
                     f'font-size="{FS_NOTE}" fill="{C["note"]}" '
                     f'text-anchor="end">{esc(mark)}</text>')

        # marker carries ONE meaning: certainty. Solid = a precise date the point
        # can honestly stand on; hollow = anything softer (range / relative /
        # order-only). Whether the date was extracted or derived is said in the
        # date text and in the index, not in the dot — one channel, one meaning.
        if low:
            S.append(f'<circle cx="{rail_x}" cy="{cy}" r="{DOT_R}" fill="{C["bg"]}" '
                     f'stroke="{C["circle"]}" stroke-width="2"/>')
        else:
            S.append(f'<circle cx="{rail_x}" cy="{cy}" r="{DOT_R}" '
                     f'fill="{C["red"] if emph else C["circle"]}"/>')
        cx0 = lane_x.get(ev.get("lane"), base_card_x)
        S.append(rule(rail_x + DOT_R + 2, cy, cx0 - 2, cy, "connector"))

        # card — 奇川风: SOLID colour blocks, never outlined cards
        # (references/visual-style.md: "Prefer solid color blocks over outlined
        #  cards for a more premium, deliberate look")
        # quote_depth>0 is carried by a LIGHTER solid block, not a dashed border.
        if emph:
            fill = C["red"]
        elif deep:
            fill = C["grid"]          # #ECEEF1 — one step lighter, still solid
        else:
            fill = C["card_fill"]     # #F3F4F6
        # v1: fill plus a 1px #D6DAE0 border on every card except the accent.
        _st = "" if emph else f' stroke="{C["card_stroke"]}" stroke-width="1"'
        S.append(f'<rect x="{cx0}" y="{y}" width="{CARD_W}" height="{h}" rx="{RX_IN}" '
                 f'fill="{fill}"{_st}/>')

        ty = y + CARD_PAD
        prev_head = False
        ind_w = text_w("1、", FS_SUB)
        for ln, fs, kind in lines:
            if prev_head and kind != "head":
                ty += HEAD_GAP
            prev_head = (kind == "head")
            ty += LH1 if kind == "head" else LHS
            # v1 convention (render_dated.py: body_col = C["ink"]): the card body
            # IS the content, not a caption. Differentiation is size + weight,
            # never a paler ink.
            col = C["white"] if emph else C["ink"]
            fw = ' font-weight="600"' if kind == "head" else ""
            x = cx0 + CARD_PAD + (ind_w if kind == "item_cont" else 0)
            S.append(f'<text x="{x:.1f}" y="{ty - 5:.1f}" font-size="{fs}" '
                     f'fill="{col}"{fw}>{esc(ln)}</text>')

    S.append("</svg>")
    open(out_path, "w", encoding="utf-8").write("\n".join(S))

    # report which cards a break would cut
    return H, packs, []


if __name__ == "__main__":
    m = json.load(open(os.path.join(HERE, "map_verdict.json"), encoding="utf-8"))
    H, packs, _ = render(m, 0, os.path.join(HERE, "vertical.svg"))
    npara = sum(1 for e in m["events"] if e.get("body"))
    nlist = sum(1 for e in m["events"] if e.get("items"))
    notes = sum(1 for e in m["events"] if e.get("index_note"))
    print(f"长图 {W}x{H}  自然段 {npara} 张 / 列表 {nlist} 张  卡间距一律 {V_GAP}px")
    print(f"进溯源索引的审计说明 {notes} 条（不上图）")
