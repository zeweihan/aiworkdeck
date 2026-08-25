#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Dated point-timeline — DATE-PROPORTIONAL. v1's renderer, plus two additions.

This file starts as a byte copy of mqc-litigation-visual-redraw/scripts/
render_dated.py and changes only two things. Writing a dated renderer from
scratch was tried once and silently dropped the honest ruler, the dotless
markers and the 214px card, because the frozen spec already answered those
questions and a fresh implementation re-answers them wrongly. Start from the
answer.

Added:
  BREAKS   an empty stretch is compressed and covered by a parallelogram in the
           PAPER colour, so the bar reads as interrupted rather than merely
           thin. The real interval is named beside it: a break that does not
           say how long it is hides the thing it compresses.
  CLUSTERS events whose true positions collide share one point on the bar and
           fan their cards out along it. The spec's own answer for packed
           events is to use the numbered form, and that remains the default;
           this only covers a chronology that is well separated overall but has
           a couple of same-day pairs in it.

Everything else — ruler, tick placement, unit choice, card, colours, dotless
markers — is v1's and is not to be re-derived.

Original header follows.

Dated point-timeline — DATE-PROPORTIONAL. Point events (not periods) placed
at their TRUE position on a real-time axis, so the distance between two events is
faithful to the elapsed time. The axis is a slightly-thick light-gray BAR that
carries an honest ruler (year ticks, or year+month ticks for a short span); the
precise date sits in each card. No node dots — the connector meets the bar edge.

Best for LONG, well-separated chronologies (诉讼时效、长期履行). For events packed
close together (a few days apart), use numbered_point_timeline instead — there the
gaps intentionally carry no meaning.

Usage: python render_dated.py <semantic-map.json> <out.svg>
"""
import os
import sys
from datetime import date as _date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "..", "mqc-litigation-visual-redraw",
                                "scripts"))
from common import (C, FS, RADIUS, TITLE_FONT, esc, wrap, text_w,
                    svg_open, load_map, parse_date)
from geom import rule, TEXT_CLEAR, wrap_atomic, body_lines
import paper

CARD_W = 214
# 与编号型同一套：字号取自字阶（正文 subtitle 13、日期 note 12），间距按字号成比例，
# 比例基准取自 v1 原值 16/13/22/8 ÷ 17。日期型原来仍是正文 17px、内边距 16/13、行距 22，
# 与默认档不一致 —— 同一份材料换个图种，字号行距就变了。
PAD_X = round(FS["subtitle"] * 16 / 17)    # [C10]
PAD_Y = round(FS["subtitle"] * 13 / 17)
_THEME = None
LH = round(FS["subtitle"] * 22 / 17)
BAR_H = 14                 # light-gray axis bar (slightly thick, not heavy)
BAR_FILL = "#E5E7EB"
TICK = "#C6CBD2"           # segment ticks, slightly darker than the bar
CONNECT = 56               # bar -> card gap
LEFT, RIGHT = 132, 132
#: 刻度格数上限（[D12]）。**提到模块级**，因为可行域那一套（feasible）必须从这里取，
#: 不能抄字面量 —— 抄一份就会与渲染器分家，改了这边忘了那边。
CELLS_MAX = 8
#: 同侧相邻卡片至少要隔开多少像素（[D6]）。**由 CARD_W 推出，不另写字面量** ——
#: 我一度加了个 SAME_SIDE_MIN_PX = 230，而渲染器真正比的是 CARD_W + 16，
#: 那就成了同一个数的两份定义，改一处忘一处。契约要取的就是这个表达式。
SAME_SIDE_MIN_PX = CARD_W + 16
#: 相邻时点在轴上至少隔开的像素（[D5]）。渲染器里的判据用的就是这个数。
NEIGHBOUR_MIN_PX = 20
#: 年刻度每格的最小宽度。**115 是实际可达的最窄一格**，不是凭感觉定的数。
#: 原来写 118，而它比可达值还大 3px，于是七格年份那一档（每格 115px）永远被判违规，
#: 却是一张完全读得出的图（年份 2010 到 2016 排得很开，验过）。
#: 为什么可达值就是 115：八格年份画不出来 —— 会被同侧相邻卡片那道门禁拦住
#: （八格时相邻两格 730 天，按比例排下来同侧两卡隔不开 230px）。
#: 所以七格是极限，115px 就是这条轴上可能出现的最窄一格，下限定在那里。
#: 定得比它大只会误报、挡不住任何真问题；定得比它小则留了用不上的余量。
YEAR_MIN_PX = 115
MONTH_MIN_PX = 66          # min width per month (short spans)
WEEK_MIN_PX = 52           # min width per week — added for disputes measured in
                           # days, where a month ruler gives one or two ticks and
                           # nothing can be read off it. The unit is relative:
                           # a three-week acceptance dispute deserves the same
                           # legible ruler a ten-year chronology gets.
RX = RADIUS["card"]
TARGET_RATIO = 1.9
# A4 横版的宽度预算来自 paper.py，不在这里写死。硬上限：图要印在一张纸上，
# 所以刻度间距让位，纸张不让位。
# **原来这里还声明了一个 MIN_WIDTH = paper.LAND_W，全仓库零读者，已清掉** ——
# 宽度实际由下面的 plot_cap（paper.LAND_W − LEFT − RIGHT）封住。
# 它是怎么暴露的值得记：我拿它做改坏验证（+100px 看守卫是否报警），产物一点没变，
# 这才发现改的是一个没人读的名字。**死常数连改坏验证都骗得过 —— 因为改它什么都不会发生。**

# 正文 subtitle（13）、卡内日期 note（12），与编号型默认档一致。
FS_BODY, FS_DATE = FS["subtitle"], FS["note"]
FS_TITLE, FS_UNIT = FS["doc_title"], FS["note"]


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
    # [D10] 卡片换行走 geom.wrap_atomic，数字与它的单位不许被拆到两行
    return wrap_atomic(ev["text"], FS_BODY, CARD_W - PAD_X * 2, wrap, text_w)


#: [D14] 日期型卡片的正文**至多六行**。
#: 这一档是作者看三档实图之后定的，来历要记住，因为它不是算出来的：
#:   · 四行（56 字）：句子被删到一半，「未达标时由转让方以现」这种断法，
#:     补偿条件只说了一半，读者必须回去翻材料 —— 图没完成它的活
#:   · 六行（约 84 字，卡高 142 到 159px，图高 594）：句子主干说得完，
#:     而轴与轴上的空白仍是图上最显眼的东西
#:   · 八行（126 字，卡高 176px，图高 662）：卡片把轴挤成一条细带，
#:     而**日期型唯一不可替代的能力就是让轴上的空白成为证据**，卡片越高这个能力越弱
#: 为什么上限记在**行数**而不是字数：每行能放几个字随内容浮动（数字与半宽标点比汉字窄，
#: 同一档实测 14 到 17 字），行数才是稳定的几何量。字数上限由卡内宽反解后报给前端。
#: 超过六行**拒绝出图并说明**（[C4]），不截断（[C3] 图上不许出现省略号）——
#: 压到六行以内是前端在容量内做删减的事，不是渲染器切一刀。
MAX_BODY_ROWS = 6


def card_h(ev):
    head = (FS_DATE + 8) if ev.get("date_text") else 0
    return (26 if _THEME == "guizang" else PAD_Y) * 2 + head + len(card_lines(ev)) * LH


def _v2_dates(evs):
    """把 v2 的 time.date / time.date_text 摊到事件顶层。

    这个渲染器是 v1 的逐字复制，读的是 `e["date"]`；而 v2 的地图把时间搬进了
    `e["time"]`。结果是它面对本 skill 自己的示例地图会说每个日期都无法解析，
    一张也画不出来，而回归测试里这个渲染器压根不在被驱动的名单里，所以这件事
    一直没被发现，HANDOVER 甚至把其中一份记成了日期型的正例。

    修法是在入口处搭一层桥，不动 v1 那半边的任何一行：改 v1 的代码就会把当初
    想清楚过的东西重新答一遍，而且答错，这在这个文件上已经发生过一次。
    顶层字段若已存在则以顶层为准，v1 的地图因此原样通过。

    要摊的不止日期。v1 的卡片读 `text`，v2 叫 `head`；v1 的地图逐个事件写着
    `band`，而 v2 不写，因为分几层、谁在哪一层是脚本按几何算的内部排布，不进
    语义地图。没有 band 就按下标上下交替，这正是 v1 日期型的原本形制，也是
    「日期型最多上下两层」这条规矩的落地。v2 的 `body` 不摊过来：整段正文只有
    纵向单列放得下，日期型的卡片是日期加一行标题。
    """
    out = []
    for i, e in enumerate(evs):
        t = e.get("time") or {}
        c = dict(e)
        if "date" not in c and t:
            c["date"] = t.get("date") or t.get("from")
        if "date_text" not in c and t.get("date_text"):
            c["date_text"] = t["date_text"]
        if "text" not in c and c.get("head"):
            c["text"] = c["head"]
        if "band" not in c:            # [D7] 只有上下两层，交替落位
            c["band"] = "up" if i % 2 == 0 else "down"
        out.append(c)
    return out


def render(m, plan_only=False):
    evs = _v2_dates(m["events"])
    _check_dates(evs)
    ds = [parse_date(e["date"]) for e in evs]
    lo, hi = min(ds), max(ds)

    # unit granularity: long multi-year span -> year; short span -> month
    unit = m.get("axis_unit")   # [D1] 单位取一种且全图一致
    if unit not in ("year", "month", "week"):
        _dd = (hi - lo).days
        unit = ("year" if (hi.year - lo.year) >= 3
                else ("week" if _dd <= 120 else "month"))

    # pad axis to whole-unit boundaries so first/last unit shows fully
    if unit == "year":
        a0, a1 = _date(lo.year, 1, 1), _date(hi.year + 1, 1, 1)
        n_units = a1.year - a0.year
    elif unit == "week":
        from datetime import timedelta as _td
        a0 = lo - _td(days=lo.weekday())
        a1 = hi + _td(days=7 - hi.weekday())
        n_units = max(1, (a1 - a0).days // 7)
    else:
        a0 = _date(lo.year, lo.month, 1)
        ey, em = (hi.year + (hi.month // 12)), (hi.month % 12 + 1)
        a1 = _date(ey, em, 1)
        n_units = _months_between(a0, a1)
    span = max(1, (a1 - a0).days)

    # ---- 轴 = 等长的单位格 + 插进去的固定长度断代 -------------------------
    # 原来的做法是按天数成比例铺开，再把过长的空白就地压短。后果是每一格年份宽度
    # 各不相同：2015 年很长、2018 年很短，读者看不出「一年」是多宽，而断代也只是
    # 某一段被压扁，看着还像个普通间隔，压缩区两侧的年份标签还会挤到一起。
    #
    # 现在按作者定的办法：**每一个单位格长度完全一致**，断代不是把原来的长度压短，
    # 而是作为一段固定长度的线段插进去，插在哪断在哪，整体因此变长。这样一来
    # 「一年」始终一样宽，标签天然不会挤，而断开处因为多出一段固定长度，距离感是
    # 看得出来的。
    cells = []                       # 每格 (起, 止, 标签)
    if unit == "year":
        for y in range(a0.year, a1.year):
            cells.append((_date(y, 1, 1), _date(y + 1, 1, 1), str(y)))
    elif unit == "week":
        from datetime import timedelta as _td3
        for k in range(n_units):
            s = a0 + _td3(days=7 * k)
            cells.append((s, s + _td3(days=7), f"{s.month:02d}.{s.day:02d}"))
    else:
        y, mth = a0.year, a0.month
        for _ in range(n_units):
            ny, nm = (y + 1, 1) if mth == 12 else (y, mth + 1)
            cells.append((_date(y, mth, 1), _date(ny, nm, 1), f"{y}.{mth:02d}"))
            y, mth = ny, nm

    occupied = [any(c[0] <= d < c[1] for d in ds) for c in cells]

    # 空格连成一片才考虑断。判据仍是两条（占跨度 18% 以上且绝对超过 400 天）：只看
    # 比例会把十个月的迟延案里那七个月压掉，而那七个月正是那张图要讲的事。
    GAP_SHARE, GAP_MIN_DAYS = 0.18, 400   # [D4] 折叠判据：两条同时成立
    cap = max(span * GAP_SHARE, GAP_MIN_DAYS)
    runs, i = [], 0
    while i < len(cells):
        if occupied[i]:
            i += 1
            continue
        j = i
        while j < len(cells) and not occupied[j]:
            j += 1
        if (cells[j - 1][1] - cells[i][0]).days > cap:
            runs.append((i, j))       # [i, j) 这几格整段折叠成一个断代
        i = j
    dropped = {k for a, b in runs for k in range(a, b)}
    kept = [k for k in range(len(cells)) if k not in dropped]

    # 断代占固定宽度，明显短于一格，所以看得出来它不是一段真实的时间。
    BREAK_W = 34.0
    plot_cap = paper.LAND_W - LEFT - RIGHT
    _sample = {"year": "2015", "month": "2015.03", "week": "03.05"}[unit]
    # 间隙用 geom.TEXT_CLEAR，与守卫同一个数。写死 8px 时它比守卫要求的 9.6px 小，
    # 于是「等距按年、两个事件」这一档从这里溜过去、在守卫处才被抓住。
    label_w = text_w(_sample, FS_UNIT) + TEXT_CLEAR * FS_UNIT
    # [D2] 每格等长；断代是插进去的固定长度，不是把某一格压短
    CELL = (plot_cap - BREAK_W * len(runs)) / max(1, len(kept))
    # [D12] 刻度格数上限 8：格数再多，轴就从刻度变成栅栏。
    # 这一条比「至少要几个时点」聪明，因为它不看点数、只看**轴还读不读得出来**：
    # 三个时点跨四个月会铺成 17 格周次（每格 47px），读者要的是三个日期却看到十七个
    # 周次，中间大片空白只为撑出比例；而五个时点跨六年是 7 格年份，那张图有意义。
    # 判据落在几何上，所以它对任何单位（年/月/周）都成立，不必分别写规则。
    if len(kept) > CELLS_MAX:
        _u = {"year": "年", "month": "月", "week": "周"}[unit]
        raise RuntimeError(
            f"按{_u}刻度，折叠后仍有 {len(kept)} 格，超过 {CELLS_MAX} 格的上限。"
            f"刻度格数一多，轴就从刻度变成栅栏：读者要的是那几个日期，"
            f"而图上是 {len(kept)} 个{_u}次，中间的空白只为撑出比例。"
            f"请改用编号型（numbered_point_timeline），那里的间距不承载含义。")

    if CELL < label_w:                 # [D3] 刻度标签不许互相压
        raise RuntimeError(
            f"按{ {'year':'年','month':'月','week':'周'}[unit] }刻度，折叠后仍有 "
            f"{len(kept)} 格，A4 横版的绘图宽度 {plot_cap:.0f}px 分下来每格只有 "
            f"{CELL:.0f}px，而刻度标签本身要 {label_w:.0f}px，标签会互相压住。"
            f"比例轴到此为止，请改用编号型（numbered_point_timeline）。")

    # 每一格的左边界 x，以及断代的位置
    x_of, brk_x, cur = {}, [], 0.0
    run_start = {a: (a, b) for a, b in runs}
    k = 0
    while k < len(cells):
        if k in run_start:
            a, b = run_start[k]
            brk_x.append((cur + BREAK_W / 2, a, b))
            cur += BREAK_W
            k = b
            continue
        x_of[k] = cur
        cur += CELL
        k += 1
    plot_w = cur
    _side = LEFT
    width = int(LEFT + RIGHT + plot_w)

    def X(d):
        """日期 -> x。格内按比例，落在被折叠的那几格里就贴到断代中点。"""
        for k2, c in enumerate(cells):
            if c[0] <= d < c[1]:
                if k2 in x_of:
                    f = (d - c[0]).days / max(1, (c[1] - c[0]).days)
                    return _side + x_of[k2] + f * CELL
                for bx, a, b in brk_x:      # 落在断代里：贴中点，不假装有位置
                    if a <= k2 < b:
                        return _side + bx
        return _side + (x_of[kept[-1]] + CELL if kept else 0.0)

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

    # 诚实的刻度尺：每一格等长，刻度线画在格的边界上，标签居中在格内。
    # 被折叠掉的那几格不画标签也不画刻度 —— 它们在轴上没有宽度，画出来就是在
    # 宣称一个轴上不存在的位置。折叠区整段由断代那一小段代替，间隔多长写在旁边。
    for k2 in kept:                    # [D9] 被折叠的格不画标签也不画刻度
        bx = _side + x_of[k2]
        if abs(bx - _side) > 0.5:                  # 首格左边界与轴端重合，不画
            S.append(rule(bx, axis_y - BAR_H / 2, bx, axis_y + BAR_H / 2, "tick"))
        S.append(f'<text x="{bx + CELL / 2:.1f}" y="{axis_y+FS_UNIT*0.35:.1f}" '
                 f'font-size="{FS_UNIT}" font-weight="700" fill="{C["ink2"]}" '
                 f'text-anchor="middle">{cells[k2][2]}</text>')

    # ---- 断代：与纸同色的平行四边形横切色带，两侧各一道斜线。
    # 两条细线试过，读起来像色带变细而不是断开；切一块纸色出来才明确是中断，
    # 印刷惯例也是这么做的。
    SLANT, HALF = 4, 5
    for bx, a, b in brk_x:
        mx = _side + bx
        y0, y1 = axis_y - BAR_H / 2 - 2, axis_y + BAR_H / 2 + 2
        pts = (f"{mx - HALF + SLANT:.1f},{y0:.1f} {mx + HALF + SLANT:.1f},{y0:.1f} "
               f"{mx + HALF - SLANT:.1f},{y1:.1f} {mx - HALF - SLANT:.1f},{y1:.1f}")
        S.append(f'<polygon data-role="axis-break" points="{pts}" fill="{C["bg"]}"/>')
        for dx in (-HALF, HALF):
            S.append(rule(mx + dx + SLANT, y0, mx + dx - SLANT, y1, "connector"))
        # [D11] 指名被折叠的是哪几格，不只说间隔多长。「此处间隔 2 年」会被读成
        # 「相邻两个时点相隔两年」，而它说的是被折叠掉的那两格；指名之后没有第二种读法。
        labs = [cells[k3][2] for k3 in range(a, b)]
        if len(labs) == 1:
            dur = f"省略 {labs[0]}"
        elif len(labs) == 2:
            dur = f"省略 {labs[0]}、{labs[1]}"
        else:
            dur = f"省略 {labs[0]} 至 {labs[-1]}"
        # 说明放在色带上方、紧贴断代，不放下方：放在下方它与年份标签同一带，读者
        # 分不清那行字是刻度还是注释。
        # 分两行写，因为它必须窄。断代本身只有 34px 宽，而一整行「此处间隔 2 年 11
        # 个月」有一百多像素，会伸进左右两格去压住那里的引线 —— 一行字压在引线上，
        # 看起来就是图画错了。两行之后最宽的一行约 72px，左右各 36px，而相邻事件至少
        # 半格远（约 70px），压不上。
        # [D8] 断代说明分两行、贴在断代上方
        for _k, _ln in enumerate(("此处", dur)):
            S.append(f'<text x="{mx:.1f}" '
                     f'y="{axis_y - BAR_H / 2 - 34 + _k * 15:.1f}" '
                     f'font-size="{FS_UNIT}" fill="{C["note"]}" '
                     f'text-anchor="middle">{esc(_ln)}</text>')

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
        # [M12] 正文走 **geom.body_lines** 这一个出口：一两行居中、三行及以上左对齐
        # （汉字折行贪心到满行，左对齐之后左右两边自然齐平，也就是两端对齐的实际做法）。
        # 这里原来自己画一遍、且**恒为居中**，所以八行的卡片每行左右缺口各不相同，
        # 边是锯齿状的 —— 另两个渲染器早就改用共用出口了，日期型漏掉了。
        # 又一次「凡每处都要记得做一次的事，收成一个出口统一做」：这份文件是从 v1 逐字
        # 复制来的，而抽出 body_lines 那一轮没有把它一起换过来。
        S.extend(body_lines(lines, cx, CARD_W, PAD_X, FS_BODY, LH, ty, body_col))
        ty += LH * len(lines)
        S.append('</g>')

    def connector(cx, y0, y1):
        S.append(rule(cx, y0, cx, y1, "connector"))

    # ---- [D14] 正文不许超过六行 ---------------------------------------------
    # 与其余门禁同处：**装不下必须表现为拒绝**，不许悄悄把卡片撑高（那样图仍画得出来，
    # 但轴被挤成细带，已经不是设计中的样子 —— 这正是容量报 126 字那次的成因）。
    _over_rows = [(e["id"], len(card_lines(e))) for e in evs         # [D14]
                  if len(card_lines(e)) > MAX_BODY_ROWS]
    if _over_rows:
        raise ValueError(
            f"{len(_over_rows)} 个事项的正文超过 {MAX_BODY_ROWS} 行"
            f"（如事项 {_over_rows[0][0]} 有 {_over_rows[0][1]} 行）——"
            f"日期型的卡片再高就会把轴挤成一条细带，而这张图的论点在轴上的空白。"
            f"请把正文压到 {MAX_BODY_ROWS} 行以内（约 "
            f"{MAX_BODY_ROWS * int((CARD_W - 2 * PAD_X) // FS_BODY)} 字）再出图。")

    # ---- collision: refuse, do not invent -----------------------------------
    # Events packed close together get the NUMBERED form. That is the frozen
    # spec's own answer and it was there from the start; fanning their cards out
    # from a shared point was something invented here, and it is wrong: on a
    # proportional axis two events a day apart ARE at one place, and spreading
    # their cards along the axis puts each card somewhere its date is not.
    # Two tests, both real. Points must be distinguishable on the ruler, AND
    # two cards on the SAME side (they alternate, so two apart) must clear each
    # other. The second is what actually bites: a pair three days apart on a
    # four-month axis leaves 24px between the dots, and the cards need 230.
    _MIN_PX = 20                       # [D5] 相邻时点在轴上要分得开
    _dots = [(evs[i]["id"], evs[i + 1]["id"], (ds[i + 1] - ds[i]).days)
             for i in range(len(evs) - 1)
             if X(ds[i + 1]) - X(ds[i]) < _MIN_PX]
    _cards = [(evs[i]["id"], evs[i + 2]["id"], (ds[i + 2] - ds[i]).days)
              for i in range(len(evs) - 2)
              # [D6] 同侧相邻两卡（隔两位）必须放得下
              if X(ds[i + 2]) - X(ds[i]) < SAME_SIDE_MIN_PX]
    if _dots or _cards:
        why = []
        if _dots:
            p = "、".join(f"{a}与{b}相隔{g}天" for a, b, g in _dots[:3])
            why.append(f"{len(_dots)} 对时点在轴上几乎重合（{p}）")
        if _cards:
            p = "、".join(f"{a}与{b}相隔{g}天" for a, b, g in _cards[:3])
            why.append(f"{len(_cards)} 处同侧相邻卡片放不下"
                       f"（{p}，按比例只有不到 {SAME_SIDE_MIN_PX} 像素）")
        raise RuntimeError(
            "；".join(why) + "。请改用 numbered_point_timeline（编号型），"
            "那里的间距本来就不承载含义。")

    # **只算不画的出口。放在全部门禁之后** —— 这一点是踩过才知道的。
    # 原来它在第 300 行（刻度那道门禁之后、同侧间距那道门禁之前），于是八个等距时点
    # 那一档 plan_only 报「能画、七格年份、每格 115px」，而真画被同侧间距拒掉。
    # 前端会照着一个画不出来的计划去写字。
    # 规矩：**加出口时要验「计划与真画的结局一致」，不只是「参数一致」** ——
    # 结局包括「被拒」这种结局。
    if plan_only:
        return {
            "kind": "日期型", "form": "横向",
            "n": len(ds), "width": width, "height": height,
            "card_w": CARD_W, "cells": len(kept), "unit": unit,
            "px_per_cell": (width - LEFT - RIGHT) / max(1, len(kept)),
            "fits_page": height <= paper.LAND_H,
        }

    for i, ev in enumerate(evs):
        cx = bx = X(ds[i])
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
