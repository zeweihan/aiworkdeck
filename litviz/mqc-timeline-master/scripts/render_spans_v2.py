#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1's proportional gantt, plus two additions. Byte copy as the starting
point: writing one from scratch dropped the honest ruler, the dotless markers and
the label-inside rule, all of which the frozen spec had already settled.

Added:
  BREAKS          long empty stretches compress under a paper-coloured
                  parallelogram, with the real interval named beside it.
  STIPULATED      a period an agreement fixes (an assessment year, a notice
                  window) is drawn lighter than a period of fact and may never
                  take the accent — 深红只标已发生的事实.

Not added, deliberately: a vertical version. A period needs LENGTH along the
axis, and the spec puts its label inside the bar. Turned on its side the bar is
26px wide, the label cannot go inside it, and the whole thing stops being 奇川风.
The horizontal form is the form.

Original header follows.
"""
"""Proportional gantt / period-timeline. For durations that overlap or leave
gaps (limitation periods, guarantee periods, performance windows). The axis is
DATE-PROPORTIONAL — bar length and overlap carry legal meaning, so it must not
be equidistant. Each period gets its own row.

Usage: python render_spans.py <semantic-map.json> <out.svg>
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "..", "mqc-litigation-visual-redraw",
                                "scripts"))
import paper
from geom import rule, TEXT_CLEAR, wrap_atomic, snap_axis  # noqa
from common import C, FS, DASH, TITLE_FONT, esc, text_w, parse_date, svg_open, load_map, wrap

FS_TITLE, FS_LABEL, FS_DATE, FS_YEAR, FS_PE = FS["doc_title"], 13, FS["note"], FS["axis_year"], 13
# 行距是**算出来的**：可用高度除以段数，夹在上下限之间。上限从 58 提到 96 ——
# 58 是按「八段占满纸」定的，段少时图只有 446px 而纸有 726px，下方空 280px、三条
# 条子挤在上半部分。提到 96 之后三段图高 560px、纸用得满；而八段那一档仍会被
# avail/n 压到 57px，不受影响。同一个公式在段少时给宽行距、段多时自动收紧，
# 不需要按段数分档写死 —— 这是「由几何算出来」而不是机械分档。
# 96 = 3.2 倍条高，再宽则条与条之间不像同一张图。
BAR_H, ROW_H_MAX = 30, 96   # [P23] 行距由 avail/n 自适应，夹在下限与这个上限之间
# 行距的下限：条本身 30px，上下各留 7px 才不至于两条相黏。行距是按纸算出来的，
# 不是写死的 —— 见 render() 里的推导。
ROW_H_MIN = BAR_H + 14
LEFT, PAD_L, RIGHT = 60, 24, 70
_LEFT_MIN_HINT = 60 + 24 + 70       # margins the ruler cannot use     # plot margins (labels sit in/near the bars, no gutter)
TOP, PE_ZONE_MIN, BOT = 100, 96, 64
PE_ROW = 40               # 一层时点标签占的高度（日期一行 + 说明一行）
#: [P21] 两条竖线靠到这个距离以内就算「太近」：后一条换深色、标注纵向错开一档。
#: 24px 约两个字宽 —— 实测同一张图上相邻竖线间距最小 41px、唯有两对是 11px，
#: 门槛落在两者之间，不会误判。
NEAR_PX = 24
# 相邻标签之间至少要留的空气，取自 geom.TEXT_CLEAR —— 这个数全项目只有一个。
# 不是「不重叠就行」：两个标签之间只剩三五个像素时，印出来就是连在一起的一长串，
# 而字宽的估算本身还有误差。
CLEAR_RATIO = TEXT_CLEAR
ARROW = 12
TARGET_RATIO = 1.9
# 宽度的下限。**这个数原来是 1200，从 v1 抄来的，而它比上限（paper.LAND_W = 1070）
# 还大**，于是下面那句 min(上限, max(下限, want)) 恒等于上限，want 被完全忽略 ——
# 结果是对的（轴总是铺满整幅），但表达方式是错的：一个永远不生效的下限，
# 谁读代码都会以为窄图会停在 1200。改成与上限同一个来源，行为一字不变
# （已逐字节比对产物），而式子说的话与它做的事一致了。日期型那份写的就是这样。
MIN_WIDTH = paper.LAND_W


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


def render(m, plan_only=False):
    _check_dates(m)
    spans = m["spans"]
    points = m.get("points", [])
    # axis auto-covers every date, so a bar/point can never fall off-canvas even
    # if the given axis range is too narrow.
    lows = [parse_date(m["axis"]["start"])] + [parse_date(s["from"]) for s in spans] + [parse_date(p["date"]) for p in points]
    highs = [parse_date(m["axis"]["end"])] + [parse_date(s["to"]) for s in spans] + [parse_date(p["date"]) for p in points]
    a0, a1 = min(lows), max(highs)
    # An axis that begins exactly where the first bar begins reads as a claim
    # that nothing preceded it, which the material does not make. Give the ruler
    # a whole unit of runway at each end, and snap to unit boundaries so the
    # first and last labels are real years rather than part-years.
    from datetime import date as _dt, timedelta as _td
    # [P4] 首尾留余量，但余量按**跨度的比例**给，不是固定一整年。
    # 固定一年的后果：内容只跨两年时，轴变成五格（2021 到 2025），有内容的两年只占五分
    # 之二，两头三格全是空白 —— 作者指出「2021 开始甚至到 2026，没有意义，应该把中间那
    # 部分拉宽」。
    # 余量的作用是让第一段期间不与轴起点齐平（齐平等于在说这之前什么都没有），所以它只
    # 需要看得出是一段空白，不需要一整年。取跨度的 12%，并夹在一个月到一年之间：跨度两年
    # 时余量约三个月，跨度十年时给满一年。
    # 余量按跨度比例给，但**不许跨出内容所在的年份**：左端最多退到内容起始那一年的
    # 1 月 1 日，右端最多推到内容结束那一年的 12 月 31 日。
    # 这样余量既留出了「这之前什么都没有」的那段空白，又不会整出一个几乎全空的年格
    # （固定一整年时 SC 那张轴变成 2021 到 2025 五格，有内容的两年只占五分之二）。
    _raw = max(1, (a1 - a0).days)    # [P17]
    _pad = min(365, max(30, int(_raw * 0.12)))
    a0 = max(_dt(a0.year, 1, 1), a0 - _td(days=_pad))
    a1 = min(_dt(a1.year, 12, 31), a1 + _td(days=_pad))
    # 不再吸附到年边界。吸附会把按比例算出的余量又拉回整年：跨度两年时算出三个月余量，
    # 吸附之后左端退回 1 月 1 日，2021 那一整格重新空出来，等于这条改动白做。
    # 年份标签本来就是画在轴上的刻度，轴从三月开始、第一个刻度落在下一个整年，读起来
    # 没有问题；而首尾各空一整格，读起来就是「这张图有一半在讲没有发生的事」。
    span_days = max(1, (a1 - a0).days)
    n = len(spans)

    # The canvas must be wide enough that every year on the ruler keeps v1's
    # 118px. Adding a year of runway at each end without widening the paper just
    # squeezed 2014 into 2015 — the runway has to be paid for, not borrowed from
    # the years already there.
    _years = a1.year - a0.year + 1
    # The left margin grows to hold the widest outboard label, so it has to be
    # counted here too — otherwise the ruler is sized for a 60px margin and then
    # squeezed into what is left after a 245px one.
    # A4 landscape is a HARD ceiling, not a preference: a lawyer prints this on
    # one sheet, and a figure 221px too wide is a figure that gets scaled down
    # until the 8pt floor is broken. So the paper wins and the ruler gives way —
    # the year pitch shrinks rather than the page growing.
    # 预算来自 paper.py，不在这里写死：这个数字曾经被抄成三份，其中横向那份隐含
    # 每边 10mm 页边距、竖版那份隐含 20mm，两套纸并存了很久没人发现。
    A4_LAND = paper.LAND_W
    want = int(LEFT + PAD_L + RIGHT + _years * 118)
    width = min(A4_LAND, max(MIN_WIDTH, want))
    # The margin stays put. Widening it to hold a long outboard label pushed the
    # plot 245px to the right and left the ruler starting well inside the page,
    # with labels floating over a stretch of paper that had no axis under it.
    # The label moves instead of the margin — see the side choice below.
    _LEFT = LEFT
    plot_w = width - _LEFT - PAD_L - RIGHT

    # ---- 轴 = 等长的年格 + 插进去的固定长度断代 ---------------------------
    # 与日期型同一个模型。原来这里按天数成比例铺开、再把过长的空白就地压短，三个
    # 后果：每一格年份宽度不同，读者看不出「一年」是多宽；断代只是某段被压扁，
    # 看着还像普通间隔；压缩区两侧的年份标签挤到一起，只好省掉中间那一格。
    #
    # 更糟的是压缩的对象。空白是按「相邻两个端点之间的天数」算的，而
    # 2017/11/02 到 2020/10/21 之间确实没有别的端点 —— 但那三年正是「补充协议签订
    # 至起诉的沉默期间」这一条期间本身。把它压短，等于把这张图要讲的那件事画短。
    #
    # 现在按格算：一格年份只要被任何一段期间或任何一个时点覆盖，就是有内容的格；
    # 只有连成一片、且总长超过判据的空格才折叠成一段固定宽度的断代。
    _CELL_MIN = text_w("2015", FS_YEAR) + CLEAR_RATIO * FS_YEAR
    # 格子按整年枚举，每格等宽（这是轴的模型，不能破）。
    # 试过把首尾格裁到轴的真实起止，结果等长格模型被破坏：标签只剩一个、条反而更窄。
    # 正确的做法在上面 —— 让余量小到不足以整出一个空年格，而不是事后裁格子。
    _cells = [(_dt(y, 1, 1), _dt(y + 1, 1, 1), str(y))
              for y in range(a0.year, a1.year + 1)]

    def _busy(c):                      # [P1] 一格有内容 = 与任何期间或时点相交
        for sp in spans:
            if parse_date(sp["from"]) < c[1] and c[0] < parse_date(sp["to"]):
                return True
        for pt in m.get("points", []):
            if c[0] <= parse_date(pt["date"]) < c[1]:
                return True
        return False

    _occ = [_busy(c) for c in _cells]
    # 首尾各留一整年的余量：第一段期间与轴起点齐平，看着像时间从那一年才开始存在，
    # 而材料没有这么讲。余量格不参与折叠判定。
    if _occ:
        _occ[0] = True
        _occ[-1] = True
    _cap = max(span_days * 0.18, 400)  # [P3] 折叠判据，与 [D4] 同一条
    _runs, _i = [], 0
    while _i < len(_cells):
        if _occ[_i]:
            _i += 1
            continue
        _j = _i
        while _j < len(_cells) and not _occ[_j]:
            _j += 1
        if (_cells[_j - 1][1] - _cells[_i][0]).days > _cap:
            _runs.append((_i, _j))
        _i = _j
    _folded = {k for a, b in _runs for k in range(a, b)}
    _keptc = [k for k in range(len(_cells)) if k not in _folded]

    BREAK_W = 34.0
    _CELL = (plot_w - BREAK_W * len(_runs)) / max(1, len(_keptc))
    if _CELL < _CELL_MIN:              # [P2] 年份标签不许互相压
        raise RuntimeError(
            f"折叠后仍有 {len(_keptc)} 格年份，A4 横版的绘图宽度 {plot_w:.0f}px "
            f"分下来每格只有 {_CELL:.0f}px，而年份标签要 {_CELL_MIN:.0f}px。"
            f"请减少跨度或改用编号型。")
    _xof, _brkx, _cur = {}, [], 0.0
    _rs = {a: (a, b) for a, b in _runs}
    _k = 0
    while _k < len(_cells):
        if _k in _rs:
            a, b = _rs[_k]
            _brkx.append((_cur + BREAK_W / 2, a, b))
            _cur += BREAK_W
            _k = b
            continue
        _xof[_k] = _cur
        _cur += _CELL
        _k += 1
    plot_w = _cur
    width = int(_LEFT + PAD_L + plot_w + RIGHT)

    def X(d):
        for k2, c in enumerate(_cells):
            if c[0] <= d < c[1]:
                if k2 in _xof:
                    f = (d - c[0]).days / max(1, (c[1] - c[0]).days)
                    return _LEFT + PAD_L + _xof[k2] + f * _CELL
                for bx, a, b in _brkx:
                    if a <= k2 < b:
                        return _LEFT + PAD_L + bx
        return _LEFT + PAD_L + (_xof[_keptc[-1]] + _CELL if _keptc else 0.0)

    # 时点标签分几层，按几何算，不读地图里的 label_level。分层是排布，属于内部
    # 数学：地图里写着 label_level 全是 0，在 1177px 的画布上刚好不撞，宽度收到
    # A4 横版的 993px 之后就叠成了一团。让脚本自己算，宽度再变一次也不用改地图。
    _pe_w = []
    for e in points:
        w = max(text_w(e["date"], FS_PE), text_w(e["label_text"], FS_PE))
        _pe_w.append(w)
    _order = sorted(range(len(points)), key=lambda k: X(parse_date(points[k]["date"])))
    _lvl = {}
    _occupied = []            # 每层已占到的右边界
    for k in _order:
        cx = X(parse_date(points[k]["date"]))
        x0, x1 = cx - _pe_w[k] / 2, cx + _pe_w[k] / 2
        need = FS_PE * CLEAR_RATIO
        for lv in range(len(_occupied) + 1):
            if lv == len(_occupied):
                _occupied.append(x1)
                _lvl[k] = lv
                break
            if x0 - _occupied[lv] >= need:   # [P5] 同层相邻标签要留空气
                _occupied[lv] = x1
                _lvl[k] = lv
                break
    _pe_levels = max(1, len(_occupied))
    # 层数决定这一区要多高。层数多了就让期间的行距让位，而不是让图长出纸外。
    # [P6] 层数决定这一区的高度
    PE_ZONE = max(PE_ZONE_MIN, 22 + PE_ROW * (_pe_levels - 1) + 18 + 16)
    plot_top = TOP + PE_ZONE
    # 行距按纸张预算算出来，不写死。写死 58px 时八段期间的图高 736px，超出 A4 横版
    # 的 676px 六十个像素，而当时只有宽度被封住、高度根本没有人定，所以这张图一直
    # 宽度合规、高度出界而没有任何东西报错。高这一维和宽一样硬：超高的图打印时会被
    # 整体缩小，最小的字跟着跌破 8pt。
    # 段数少的时候行距不必压，取上限；段数多才让行距让位，这与「刻度间距让位、纸张
    # 不让位」是同一条原则。
    # 断代说明要占一条自己的带，而不是用「轴线上方 26px」这种偏移量。偏移量是错的：
    # 只有两段期间时，最后一行离轴线不到 26px，那两行字就落进了那一行里，与它左侧的
    # 起止日期叠了 31px。凡是位置靠偏移量拼出来的东西，总有一组数据能让它撞上；写成
    # 高度公式里的一项，就撞不上了 —— 带占掉的高度会自动从行距里让出来。
    BRK_ZONE = 34 if _brkx else 0
    avail = paper.LAND_H - TOP - PE_ZONE - 12 - BRK_ZONE - BOT
    ROW_H = min(ROW_H_MAX, avail / max(1, n))
    if ROW_H < ROW_H_MIN:              # [P7] 行距按纸算，且有下限
        raise RuntimeError(
            f"{n} 段期间在 A4 横版上排不开：可用高度 {avail:.0f}px 分下来每段只有 "
            f"{ROW_H:.0f}px，而期间条本身就有 {BAR_H}px，两条会相黏。"
            f"期间型的启用条件本来就限八段以内，请先减少段数或改用编号型。")
    axis_y = plot_top + n * ROW_H + 12 + BRK_ZONE
    height = int(round(axis_y + BOT))



    S = [svg_open(width, height)]
    S.append(f'<text data-role="title" x="{width/2}" y="46" font-size="{FS_TITLE}" font-weight="700" font-family="{TITLE_FONT}" '
             f'fill="{C["ink"]}" stroke="{C["ink"]}" stroke-width="0.3" text-anchor="middle">{esc(m["title_text"])}</text>')

    # gridlines + year axis
    S.append('<g data-role="axis">')
    # 断口把一段静默压成固定的小宽度，压缩段两侧的年份因此会挤到一起：993px 的画布
    # 上 2018 与 2019 之间只剩 6px，印出来就是连成一串的数字。
    # 处理办法沿用日期型那一条：格线照画，只有标签让位，而且只省掉真正挤住的那一个，
    # 从左往右贪心保留。整段压缩区的年份全省掉试过一次，读者会连方位都失去，比原来
    # 那个半挤在一起的更糟。
    from datetime import date as _d
    _last_right = None
    for yr in range(a0.year, a1.year + 1):
        gx = X(_d(yr, 1, 1))
        S.append(rule(gx, plot_top - 6, gx, axis_y, "grid"))
        _lw = text_w(str(yr), FS_YEAR)
        _need = FS_YEAR * CLEAR_RATIO
        if _last_right is not None and (gx - _lw / 2) - _last_right < _need:
            continue                      # 这一格的年份不画：格线还在，位置读得出来
        _last_right = gx + _lw / 2
        S.append(f'<text x="{gx:.1f}" y="{axis_y+22}" font-size="{FS_YEAR}" fill="{C["ink2"]}" text-anchor="middle">{yr}</text>')
    S.append(rule(_LEFT, axis_y, width - RIGHT, axis_y, "axis_rule"))
    S.append('</g>')

    _edge_x = sorted({X(parse_date(sp[k])) for sp in spans for k in ("from", "to")})
    _pt_x = {X(parse_date(e["date"])) for e in points}
    # [P21] 靠得太近的两条竖线要**换色区分**。
    # 实测这张图上相邻竖线的间距最小 41px，唯有两对是 11px（相隔十三天、十二天）——
    # 并排两条同色虚线在 11px 上读起来是一条画重了的线，分不出哪条对应哪个日期。
    # 门槛取 24px（约两个字宽）：11px 落在门槛内，41px 在门槛外，中间没有别的样本。
    # 换色的那条用深色（ink2 一档），配套的标注也换同色，这样线与字能对上。
    # 靠近的判定要把**端点与时点放在一起**排队，不能只在端点之间判。
    # 只判端点的后果：示例里一个端点与一个时点只隔 7px，两条都保持默认色，
    # 守卫当场报出「只隔 7px 却同色」。竖线不分是端点还是时点，读者看到的是同一种线。
    # 判定要用**吸附后**的坐标。geom.rule 会把竖线吸到半像素网格上，而判定原来用吸附前
    # 的浮点值：两个端点相隔三天时吸附前是 84.6 与 85.7、吸附后都成了 84.5 与 85.5，
    # 于是同一个 x 上一条深一条浅，穷举报出「只隔 1px 同色」（1260 组里 45 组）。
    # 凡是「按位置做的判断」，都要用最终落笔的那个位置，不能用算出来的中间值。
    def _snapx(v):
        return snap_axis(v, "locator")      # 与落笔用同一份吸附，见 geom.snap_axis
    _all_x = sorted({_snapx(v) for v in
                     (list(_edge_x) + [X(parse_date(e["date"])) for e in points])})
    # 一浅一深地交替，而不是「凡靠近就换深色」。
    # 后者的后果：三条连着靠近时会出现深、深相邻，两条都深又分不开了（守卫报「只隔
    # 23px 却同色」正是这一种）。所以沿 x 走一遍，只在**前一条是浅色**时才把当前这条
    # 换成深色，保证任意相邻的一对必是一浅一深。
    _near = set()
    _prev_dark = False
    for _a, _b in zip(_all_x, _all_x[1:]):
        if _b - _a < NEAR_PX and not _prev_dark:
            _near.add(_b)
            _prev_dark = True
        else:
            _prev_dark = False
    globals()["_NEAR_X"] = _near


    # point events: dashed verticals + stacked labels above the plot
    S.append('<g data-role="points">')
    for e in points:
        ex = X(parse_date(e["date"]))
        emph = e.get("emphasis")
        # 线与字分开着色。竖线是定位线不是内容，原来用 ink2（正文灰）画，比连线用的
        # line_soft 深两档，一排竖线比期间条还抢眼；但日期与说明是内容，必须照旧读得
        # 清楚。第一版把两者共用一个颜色，结果竖线淡下来的同时把标签也一起淡掉了。
        # [P21] 与前一条时点靠得太近（24px 以内）时换成深色，标注同色，好让线与字对上。
        _near_pt = _snapx(ex) in _NEAR_X
        line_col = C["red"] if emph else (C["ink2"] if _near_pt else C["line_soft"])
        col = C["red"] if emph else C["ink2"]
        _role_pt = "accent" if emph else ("edge_near" if _near_pt else "locator")
        S.append(rule(ex, TOP + 14, ex, axis_y, _role_pt, dash=True))
        lvl = _lvl.get(points.index(e), 0)
        side = e.get("label_side", "center")
        ly = TOP + 22 + lvl * PE_ROW
        anchor = {"left": "end", "right": "start", "center": "middle"}[side]
        lx = ex + (8 if side == "right" else -8 if side == "left" else 0)
        S.append(f'<text x="{lx:.1f}" y="{ly}" font-size="{FS_PE}" font-weight="600" fill="{col}" text-anchor="{anchor}">{esc(e["date"])}</text>')
        S.append(f'<text x="{lx:.1f}" y="{ly+18}" font-size="{FS_PE}" fill="{col}" text-anchor="{anchor}">{esc(e["label_text"])}</text>')
    S.append('</g>')

    # break marks: a slug of paper cut across the ruler at a slant, with the real
    # interval named. Two hairlines read as a thin ruler rather than a broken one.
    if _brkx:
        for _bx, _ba, _bb in _brkx:
            mx = _LEFT + PAD_L + _bx
            # The break cuts the RULER, not the chart body. Running it the full
            # height put two slanted lines straight through every period bar,
            # which reads as damage rather than as a compressed axis.
            # Just taller than the rule itself. A break mark is a notch in the
            # ruler, not a feature of its own.
            y0, y1 = axis_y - 7, axis_y + 7
            # No paper-coloured slug here. The gantt's axis is a rule with year
            # ticks, not the solid band the dated form has, so there is no fill
            # to cut through — a parallelogram over it covers nothing and just
            # adds a shape. Two slashes plus the stated interval is the whole
            # mark on this form.
            # Half the length it was, and both slashes the same. The pair used to
            # run the height of the ruler zone and came out different lengths
            # because their endpoints were computed from different references.
            SL = 4
            for dx in (-3, 3):
                # 斜线，像素吸附不适用；但粗细与颜色仍然只能来自角色表。
                S.append(rule(mx + dx + SL, y0, mx + dx - SL, y1, "connector"))
            g = (_cells[_bb - 1][1] - _cells[_ba][0]).days
            yy, mm = g // 365, (g % 365) // 30
            dur = (f"{yy} 年 {mm} 个月" if yy and mm else
                   (f"{yy} 年" if yy else f"{mm} 个月"))
            # 放在刻度线上方、紧贴断口，分两行。原来放在刻度线下方，与年份标签同一
            # 带，读者分不清那行字是刻度还是注释；而它说的正是这一处断开的事，就该
            # 挨着断口。分两行是为了窄：一整行一百多像素会横跨好几年的格子。
            # 写在自己那条带里，带的高度已经从行距里让出来了。
            _by = axis_y - BRK_ZONE + 12
            for _k, _ln in enumerate(("此处间隔", dur)):
                S.append(f'<text x="{mx:.1f}" y="{_by + _k * 14:.1f}" '
                         f'font-size="{FS_DATE}" fill="{C["note"]}" '
                         f'text-anchor="middle">{esc(_ln)}</text>')

    # [P16] 每一段期间在轴上的宽度必须够看得见，否则拒绝。
    # 真材料压出来的：一份跨六年半的图里有一段「约定的外立面完工后 15 日付款期」，
    # 按比例只有 3px 宽，条几乎看不见，标签飘在外面，读者会以为图画错了。
    # 不能靠给它一个最小宽度解决 —— 那等于把 15 天画成更长的时间，和截断文字是同一类
    # 错：改了材料还让人看不出改了。真正的原因是轴的跨度与最短一段之比过大（这里
    # 1:158），一张纸上的比例轴装不下这两个尺度，所以说实话并让阶梯接住它。
    MIN_BAR_PX = 6
    _thin = []
    for sp in spans:
        _w7 = X(parse_date(sp["to"])) - X(parse_date(sp["from"]))
        if _w7 < MIN_BAR_PX:
            _d7 = (parse_date(sp["to"]) - parse_date(sp["from"])).days
            _thin.append((sp.get("label_text", sp["id"]), _d7, _w7))
    if _thin:
        _total = (a1 - a0).days
        _p7 = "、".join(f"{lab}（{d} 天，轴上仅 {w:.1f}px）" for lab, d, w in _thin[:3])
        raise RuntimeError(
            f"{len(_thin)} 段期间在轴上看不见：{_p7}。"
            f"轴的总跨度 {_total} 天，与最短一段之比约 1:{_total // max(1, min(d for _l, d, _w in _thin))}，"
            f"一张 A4 横版上的比例轴装不下这两个尺度。"
            f"请缩小取材的时间范围、去掉该段，或改用编号型（那里的间距不承载含义）。")

    # **只算不画的出口。放在全部门禁之后**（与日期型同一个教训）：
    # 原来它在第 281 行，而这道「段在轴上看不见」的门禁在 424 行 ——
    # 于是细段那一档 plan_only 报能画、真画被拒，前端会照着画不出来的计划写字。
    if plan_only:
        return {
            "kind": "期间型", "form": "横向",
            "n": len(spans), "width": width, "height": height,
            "row_h": ROW_H, "bar_h": BAR_H,
            "px_per_day": (width - LEFT - RIGHT) / max(1, span_days),
            "fits_page": height <= paper.LAND_H,
        }

    # [P20] 期间的**起止端点也要画定位竖线**，与时点同一套写法（虚线、软灰）。
    # 原来只有 points 那一段画竖线，六段期间的十二个端点一条都没有 —— 作者一眼看出
    # 「有很多期间起止日期的竖线你没有画」。
    # 竖线的作用是把「这一段从哪天到哪天」落到刻度上：没有它，读者只能靠条的左右端去
    # 猜位置，而条的端点本来就是要与别的期间对齐比较的，这正是期间型存在的理由。
    # 去重之后画：多段共用同一个端点（如三段都从 2021/4/1 起）时只画一条。
    # [P22] 端点竖线不加日期标注：每段起止已写在条左侧，再标一遍会重复十次
    S.append('<g data-role="edges">')
    # 去重也要用吸附后的坐标，且**同一个吸附位置只画一条**。
    # 原来判「相差 2px 以内算重合」，而 _pt_x 是吸附前的值：端点 84.6 与时点 85.7
    # 相差 1.1px 本该判重合跳过，却因为比的是吸附前的数而没跳，于是同一个 84.5 上
    # 画了两条 —— 一条走端点分支、一条走时点分支，颜色还不同。
    _pt_snap = {_snapx(v) for v in _pt_x}
    _drawn = set()
    for _ex in _edge_x:
        _sx = _snapx(_ex)
        if _sx in _pt_snap or _sx in _drawn:
            continue                     # 与时点重合、或已画过的位置不重复画
        _drawn.add(_sx)
        _role = "edge_near" if _sx in _near else "locator"
        S.append(rule(_sx, plot_top - 6, _sx, axis_y, _role, dash=True))
    S.append('</g>')

    # period bars (right-angle, never rounded); label centered inside, else hugging left edge
    S.append('<g data-role="spans">')
    for i, sp in enumerate(spans):
        # 起止日期与它的宽度要**在标签落位之前**算出来：标签的右侧余量必须扣掉日期块
        # 可能占用的宽度。上一版把它算在后面，于是标签落位时 _dw 还不存在。
        _d0v, _d1v = sp["from"], sp["to"]
        _dw2 = max(text_w(_d0v, FS_DATE), text_w(_d1v, FS_DATE))
        x0, x1 = X(parse_date(sp["from"])), X(parse_date(sp["to"]))
        cy = plot_top + i * ROW_H + (ROW_H - BAR_H) / 2
        emph = sp.get("emphasis")
        # A stipulated window is agreed, not something that ran. Lighter, and
        # never the accent: the validator rejects an emphasised stipulated span.
        fill = (C["red"] if emph
                else (C["grid"] if sp.get("unit_type") == "stipulated" else C["bar"]))
        lbl = sp["label_text"]
        lw = text_w(lbl, FS_LABEL)
        ty = cy + BAR_H / 2 + FS_LABEL * 0.36
        directional = sp.get("directional")
        body_end = (x1 - ARROW) if directional else x1
        bw_eff = body_end - x0
        S.append(f'<g data-role="span" data-id="{sp["id"]}">')
        # [P11] 期间条直角：一段运行的期间是条，不是卡片（rect 不带 rx）
        if directional:
            S.append(f'<rect x="{x0:.1f}" y="{cy:.1f}" width="{max(0,body_end-x0):.1f}" height="{BAR_H}" fill="{fill}"/>')
            S.append(f'<path d="M{body_end:.1f},{cy:.1f} L{x1:.1f},{cy+BAR_H/2:.1f} L{body_end:.1f},{cy+BAR_H:.1f} Z" fill="{fill}"/>')
        else:
            S.append(f'<rect x="{x0:.1f}" y="{cy:.1f}" width="{max(0,bw_eff):.1f}" height="{BAR_H}" fill="{fill}"/>')
        if lw + 16 <= bw_eff:          # [P12] 落位顺序第一档：条内居中               # fits inside -> centered (white on red, ink on gray)
            tcol = C["white"] if emph else C["ink"]
            S.append(f'<text x="{(x0+body_end)/2:.1f}" y="{ty:.1f}" font-size="{FS_LABEL}" font-weight="600" '
                     f'fill="{tcol}" text-anchor="middle">{esc(lbl)}</text>')
        else:
            # Outboard. v1 always hugged the LEFT edge, which is right for its own
            # short labels but wrong here: a 245px label on a bar that starts near
            # the axis origin runs off the page. Take whichever side has more
            # room, and wrap if even that side is short.
            # Prefer the RIGHT for every bar, so all outboard labels read in one
            # direction. Mixing sides bar by bar — right for one, left for the
            # next because its own right margin happened to be smaller — makes
            # the eye jump. Fall left only when the right genuinely cannot hold
            # the label even wrapped over two lines.
            # 左侧不再是候选。起止日期已经搬到条的左边，那块地被占了；原来「右边放
            # 不下就退到左边」这条规则会让标签正好压在日期上 —— 穷举 360 组里有 57
            # 组因此违规，全都是这一处。改一处而不告诉另一处那块地已经有人，是这个
            # 项目反复出现的错法。
            # 顺序改成：右侧（至多两行）→ 条的下方居中。条下方正是日期腾出来的那一带，
            # 本来就空着，而且它有整幅的宽度，长标签在那里一定放得下。
            # 右侧余量要扣掉**日期块可能占用的宽度**：日期在左侧放不下时也退到右侧，
            # 两者抢同一个位置。上一版没扣，于是第二条的日期与标签直接叠在一起。
            # 顺序是日期优先（它是事实，不能挪走），标签让位。
            _date_right = (_dw2 + 14)    # [P19] if x0 - (_LEFT + PAD_L) < _dw2 + 10 else 0
            room_r = width - RIGHT - body_end - 8 - _date_right
            rows_r = wrap_atomic(lbl, FS_LABEL, room_r, wrap, text_w) if lw > room_r else [lbl]
            # 判据必须是「真的放得下」，不是「塞得进四个字」。
            # 原来写 room_r >= 4×字号，于是余量 124px、标签却要 169px 时也走右侧，
            # 结果标签挤成两行贴着画布右边缘（作者一眼看出来那一处）。
            # 正确的判据：折行之后**每一行都在余量之内**，且不超过两行。
            # 还要加一条：右侧只允许**一行**。
            # 上一版允许两行，判据是「每行都在余量内」，于是余量刚够时标签折成两行贴着
            # 画布右边缘，读起来像被挤出去的（作者一眼看出来）。而条下方有整幅的宽度，
            # 一行就能写完 —— 既然下方更宽松，右侧就没有理由折行。
            _fits_r = (len(rows_r) == 1
                       and text_w(rows_r[0], FS_LABEL) <= room_r)
            if _fits_r:
                ry = ty - (len(rows_r) - 1) * 8
                for ln in rows_r:
                    S.append(f'<text x="{body_end + 8 + _date_right:.1f}" y="{ry:.1f}" '
                             f'font-size="{FS_LABEL}" font-weight="600" '
                             f'fill="{C["ink"]}" text-anchor="start">{esc(ln)}</text>')
                    ry += 16
            else:
                below = plot_w - 2 * PAD_L
                rows_b = wrap_atomic(lbl, FS_LABEL, below, wrap, text_w)
                # 居中点要夹回纸内。以条的中点居中，条靠右时最宽那行会伸出画布 ——
                # 穷举里剩下的 15 组全是这一种。夹的是标注的位置，不是文字本身：
                # 文字一个字都不能少。
                # [P13] 下方居中时把中心点夹回纸内
                _wmax = max(text_w(r, FS_LABEL) for r in rows_b)
                _cx = min(max((x0 + x1) / 2, _LEFT + PAD_L + _wmax / 2),
                          width - RIGHT - _wmax / 2)
                ry = cy + BAR_H + 14
                for ln in rows_b:
                    S.append(f'<text x="{_cx:.1f}" y="{ry:.1f}" '
                             f'font-size="{FS_LABEL}" font-weight="600" '
                             f'fill="{C["ink"]}" text-anchor="middle">{esc(ln)}</text>')
                    ry += 16
        # 起止日期放在条的左侧，分两行（起一行、止一行），右对齐贴着条的左端。
        # 原来横排在条的下方居中：一行 155px 的日期比短条本身还长，会伸到左右两边
        # 去，与相邻行的条和标签互相压；而条下方那一带同时还要放溢出的标签。挪到
        # 左侧之后，条下方腾空，日期也不再跨越它并不覆盖的那段时间。
        # 左边放不下就退回条的下方，宁可退回也不撑开左边距 —— 撑开边距会让轴线从
        # 更靠右的地方才开始画，标签底下那一大片纸没有轴。
        _d0, _d1 = sp["from"], sp["to"]
        # _dw 与落位决策已在标签落位之前算过（见上），这里直接复用。
        if x0 - (_LEFT + PAD_L) >= _dw2 + 10:  # [P14] 起止日期先放左侧
            for _k, _ln in enumerate((_d0, _d1)):
                S.append(f'<text x="{x0 - 10:.1f}" '
                         f'y="{cy + BAR_H / 2 - 3 + _k * 14:.1f}" '
                         f'font-size="{FS_DATE}" fill="{C["ink2"]}" '
                         f'text-anchor="end">{esc(_ln)}</text>')
        else:
            # 左侧放不下时退到条的**右侧**，仍然两行，左对齐。
            # 不能退到下方：下方那一行会与下一条的日期块挤在一起（作者看到的正是这个）。
            # 也不能加宽左边距：加宽会让轴从纸中间才开始画，标签浮在没有轴的那片纸上，
            # 这一条在上面 _LEFT 那段注释里已经踩过一次。
            # 右侧是唯一还空着的地方 —— 而它空着的前提是这一条的标签落在条内或更右，
            # 由 [P12] 保证；两者都要右侧时标签会被推得更远，仍然不重叠。
            # 右侧也要放得下才行：条一直延伸到画布右端时，日期会伸出去（穷举报出
            # 132 组「文字出界」）。放不下就退到**条内左端**，那里一定有位置 ——
            # 条身至少 6px（[P16] 保证），日期压在条上仍可读，而伸出画布是不可接受的。
            # [P18] 落位顺序：左侧 → 右侧 → 条内左端，样式始终两行
            if x1 + 10 + _dw2 <= width - RIGHT:
                _dx, _anchor = x1 + 10, "start"
            else:
                _dx, _anchor = x0 + 4, "start"
            for _k, _ln in enumerate((_d0, _d1)):
                S.append(f'<text x="{_dx:.1f}" '
                         f'y="{cy + BAR_H / 2 - 3 + _k * 14:.1f}" '
                         f'font-size="{FS_DATE}" fill="{C["ink2"]}" '
                         f'text-anchor="{_anchor}">{esc(_ln)}</text>')
        S.append('</g>')
    S.append('</g></svg>')
    return "\n".join(S), width, height


def main(mapfile, out):
    svg, w, h = render(load_map(mapfile))
    open(out, "w", encoding="utf-8").write(svg)
    print(f"[spans] wrote {out}  {w}x{h}  ratio={w/h:.2f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "out.svg")
