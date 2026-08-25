#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Paginate a vertical timeline into A4 portrait pages.

The long figure and the paginated one are two OUTPUTS of one layout, not two
layouts. The long figure stays uniform and carries no guide lines: a lawyer is
not going to cut along a printed dash. Pagination re-emits the same seating,
page by page, and only here is the spacing allowed to shift, because a reader
never sees two pages edge to edge.

Three rules, all of them enforced rather than assumed:
  1. A break never falls inside a card. A card that would straddle one starts
     the next page instead.
  2. Every page repeats the party labels and states the span it covers, plus a
     continuation marker after the first. Turning to page three and not knowing
     which column is whose makes the figure useless.
  3. Numbering never restarts. Card 14 is card 14 on page two, so the figure and
     the provenance index still line up.
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "mqc-litigation-visual-redraw", "scripts"))
sys.path.insert(0, HERE)
import paper  # noqa: E402
from common import text_w, wrap, esc, C, FONT, TITLE_FONT, FS  # noqa
from geom import rule, snap_centre, body_lines  # noqa
import render_vcolumns as V  # noqa

PAGE_H = paper.PORT_H
# 标题块的高度由 paper.title_fit 按实际行数算出，主张方标注紧随其后。
# 原来这两个数写死 118 / 100，是按「标题一行 30px」定的；标题折成两行之后第二行
# 压在标注上（守卫报「同一行两处文字只隔 -110px」）。凡是跟着标题走的位置，都得
# 由标题块的实际高度驱动。
HEAD_H_MIN = 118
LABEL_GAP = 18            # 标题块底到主张方标注基线
FOOT_H = 30


def paginate(m, outdir, prefix="page"):
    box = V.layout(m)
    ys, hmax = box["ys"], box["hmax"]
    evs, R = box["evs"], box["R"]
    lanes, axis_x = box["lanes"], box["axis_x"]

    # ---- cut points: walk the cards, close a page before one would straddle
    body = PAGE_H - HEAD_H_MIN - FOOT_H
    pages, cur, top = [], [], ys[0] - hmax / 2
    for i, y in enumerate(ys):
        h = V._h(evs[i], box["cw"])
        if cur and (y + h / 2) - top > body:
            pages.append((cur, top))
            top = y - h / 2
            cur = []
        cur.append(i)
    if cur:
        pages.append((cur, top))

    files = []
    for pno, (items, ptop) in enumerate(pages, start=1):
        S = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{V.W}" height="{PAGE_H}" '
             f'viewBox="0 0 {V.W} {PAGE_H}" font-family="{FONT}">',
             f'<rect width="{V.W}" height="{PAGE_H}" fill="{C["bg"]}"/>']

        # 标题块与横向、纵向共用 paper 里那一套：字号从阶梯往下试、最多两行、
        # 块高按行数算。分页原来写死 30px 单行不折行，长标题在接上页那几张会直接溢出
        # 画布 —— 而接上页恰恰是标题最长的那一档（要多带「（接上页）」四个字）。
        _ttxt = m["title_text"] + ("" if pno == 1 else "（接上页）")
        _tfs, _tls, _tzone = paper.title_fit(_ttxt, V.W - 48, text_w, wrap)
        _label_y = _tzone + LABEL_GAP
        for _ti, _tln in enumerate(_tls):
            S.append(f'<text x="{V.W/2}" '
                     f'y="{paper.TITLE_PAD_TOP + _tfs + _ti * _tfs * paper.TITLE_LH_RATIO:.1f}" '
                     f'font-size="{_tfs}" font-weight="700" font-family="{TITLE_FONT}" '
                     f'fill="{C["ink"]}" stroke="{C["ink"]}" stroke-width="0.3" '
                     f'text-anchor="middle">{esc(_tln)}</text>')
        # No page numbers, no date span, nothing else under the title. The
        # frozen rule is that the title band carries the chart name and nothing
        # more, and it has now been broken twice: first with an explanatory
        # subtitle, then with this. Page identity belongs in the file name.
        if lanes:
            # Centre each label over the FULL span its side occupies, which with
            # two columns is both of them, not just the inner one.
            k = box["k"]
            side_w = k * box["cw"] + (k - 1) * V.COL_GAP
            for lab, x in ((lanes[0]["label_text"], axis_x - V.AXIS_GAP - side_w / 2),
                           (lanes[1]["label_text"], axis_x + V.AXIS_GAP + side_w / 2)):
                S.append(f'<text x="{x:.1f}" y="{_label_y}" font-size="{FS["subtitle"]}" '
                         f'font-weight="600" fill="{C["ink2"]}" text-anchor="middle">'
                         f'{esc(lab)}</text>')

        # 正文起点也跟着标题块走：标题两行时头部变高，卡片必须整体下移，
        # 否则第一张卡会压在主张方标注上。取实际头高与最小头高的较大者。
        dy = max(HEAD_H_MIN, _label_y + 30) + 30 - ptop
        py = [snap_centre(ys[i] + dy) for i in items]
        S.append(rule(axis_x, py[0], axis_x, py[-1], "axis"))

        for n, i in enumerate(items):                     # connectors
            ev = evs[i]
            x = box["card_x"](ev)
            soft = ev.get("time", {}).get("certainty", "exact") != "exact"
            a, b = ((x + box["cw"], axis_x - R) if box["side_of"](ev) == "L"
                    else (axis_x + R, x))
            S.append(rule(a, py[n], b, py[n], "connector", dash=soft))

        for n, i in enumerate(items):                     # cards
            ev = evs[i]
            x, h = box["card_x"](ev), V._h(ev, box["cw"])
            emph = ev.get("emphasis")
            soft = ev.get("time", {}).get("certainty", "exact") != "exact"
            t = py[n] - h / 2
            if emph:
                S.append(f'<rect x="{x:.1f}" y="{t:.1f}" width="{box["cw"]:.1f}" '
                         f'height="{h}" rx="{V.RX}" fill="{C["red"]}"/>')
            elif soft:
                S.append(f'<rect x="{x:.1f}" y="{t:.1f}" width="{box["cw"]:.1f}" '
                         f'height="{h}" rx="{V.RX}" fill="{C["bg"]}" '
                         f'stroke="{C["card_stroke"]}" stroke-width="1" '
                         f'stroke-dasharray="6 4"/>')
            else:
                S.append(f'<rect x="{x:.1f}" y="{t:.1f}" width="{box["cw"]:.1f}" '
                         f'height="{h}" rx="{V.RX}" fill="{C["card_fill"]}" '
                         f'stroke="{C["card_stroke"]}" stroke-width="1"/>')
            cxm = x + box["cw"] / 2
            ty = t + V.CARD_PAD_Y + V.FS_DATE
            d = V._date(ev)
            if d:
                S.append(f'<text x="{cxm:.1f}" y="{ty:.1f}" font-size="{V.FS_DATE}" '
                         f'font-weight="600" fill="{C["white"] if emph else C["ink2"]}" '
                         f'text-anchor="middle">{esc(d)}</text>')
                ty += V.FS_DATE + V.DATE_GAP
            ty += V.FS_BODY - V.FS_DATE
            S.extend(body_lines(V._lines(ev, box["cw"]), cxm, box["cw"], V.CARD_PAD_X,
                            V.FS_BODY, V.LH, ty, C["white"] if emph else C["ink"]))

        for n, i in enumerate(items):                     # dots, original numbers
            ev = evs[i]
            fill = C["red"] if ev.get("emphasis") else C["circle"]
            S.append(f'<circle data-role="node" data-id="{ev["id"]}" cx="{axis_x}" '
                     f'cy="{py[n]:.1f}" r="{R:.1f}" fill="{fill}"/>')
            S.append(f'<text x="{axis_x}" y="{py[n] + box["num_base"]:.1f}" '
                     f'font-size="{box["fs_num"]}" font-weight="700" '
                     f'fill="{C["white"]}" text-anchor="middle">{esc(str(i + 1))}</text>')

        S.append("</svg>")
        f = os.path.join(outdir, f"{prefix}-{pno}.svg")
        # 每一页都要裱白边，与入口出的单张图一视同仁。
        # 分页是独立于入口的另一条路径，而白边原来只接在入口上，于是三页产物全是
        # 700px 宽而不是整幅的 784px —— 律师把分页图粘进 Word，得到的是没有白边、
        # 长宽比也不贴纸的一叠图。凡是交付物都要过同一道裱框，这一条不能有例外。
        open(f, "w", encoding="utf-8").write(paper.frame("\n".join(S), landscape=False))
        files.append(f)
    return files, len(pages)


if __name__ == "__main__":
    m = json.load(open(sys.argv[1], encoding="utf-8"))
    outdir = sys.argv[2]
    os.makedirs(outdir, exist_ok=True)
    fs, n = paginate(m, outdir)
    print(f"分成 {n} 页：" + "  ".join(os.path.basename(x) for x in fs))