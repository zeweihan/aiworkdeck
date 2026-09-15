#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Vertical timeline with an axis down the middle and adaptive columns.

Same machinery as the horizontal multi-band renderer, rotated. The axis runs
down the page, cards sit to its left and right, and each side may use one or two
columns. The column count is DERIVED, never chosen by the user: banding is an
internal arrangement, not a figure anyone picks.

Why vertical wins on a portrait page: the axis gets the long dimension (962px of
usable height against 1065px of usable width in landscape, near enough the same),
but a card gets HALF THE PAGE WIDTH instead of one column pitch. Measured on A4
portrait:

    one column per side    ~21 events   card 289px   ~15 chars per line
    two columns per side   ~30 events   card 138px   ~6 chars per line
    three columns          ~32 events   card  88px   too narrow to bother

Three columns is not worth having: the step is already floored by the dot
diameter, so the extra column buys no capacity and costs half the card width.
Two per side is the real ceiling.

Crossing is the same rule as the horizontal form, transposed: a connector runs
horizontally from the dot out to its card, so an INNER card must not vertically
contain the dot of an event whose card is further out. Checked, not assumed.
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "mqc-litigation-visual-redraw", "scripts"))
sys.path.insert(0, HERE)
import paper  # noqa: E402
from common import wrap, text_w, esc, C, FONT, TITLE_FONT, FS  # noqa
from geom import rule, dot_metrics, snap_centre, R_MIN, wrap_atomic, body_lines, order_by_time  # noqa

W = paper.PORT_W          # A4 竖版画布宽，来自 paper.py
MARGIN_X = 24
AXIS_GAP = 26             # axis -> nearest column
COL_GAP = 12
# 卡片的两套内部尺寸。宽松那套是默认，收紧那套只在极限压缩时才用。
#
# 为什么要两套：二十个事件单侧交替时，同侧两张卡的间距被「卡高 + 空隙」顶死，量出来
# 只有 14px，二十张卡连成一片看不出分隔。这时候把卡内收紧、把省下的高度给空隙，分隔
# 就回来了（步距几乎不变，肉眼间距 14 → 20）。但这是一种取舍：卡内变紧了。
# 其余情形根本不缺空隙 —— 十三个事件两方对读的同侧间距有 108px，三十三个事件也是，
# 那里再收紧卡内就是白白牺牲，所以不动。
#
# 只缩卡不加空隙是没用的，反过来也一样：同侧步距按 (卡高 + 空隙) / 2 算，卡矮了步距
# 跟着矮、空隙照旧，看起来一模一样。两个数必须一起动。
# 与横向同一套：字号取自字阶，间距按字号成比例，比例基准取自 v1 的原值 16/13/22/8÷17。
# 这一段原来是纵向自己一套（正文 17px、行距 22、内边距 14/10、两组写死的 ROOMY/TIGHT），
# 与横向对不上。两半参数不一致，同一份材料横竖两张图的字号行距都不同，而 18 个事项
# 以上走的正是纵向 —— 交付出去的多数图反而用的是没对齐的那一套。
PAD_X_RATIO, PAD_Y_RATIO = 16 / 17, 13 / 17
LH_RATIO, DATE_GAP_RATIO = 22 / 17, 8 / 17
CARD_PAD_X = round(FS["subtitle"] * PAD_X_RATIO)
# 宽松与收紧两组仍然保留（纵向单轴密排时要靠它换分隔），但两组的数由字号算出，不写死。
# clear_v 是卡片之间的空隙，与字号无关，它是视觉分隔，照旧按像素给。
ROOMY = dict(pad_y=round(FS["subtitle"] * PAD_Y_RATIO),
             date_gap=round(FS["subtitle"] * DATE_GAP_RATIO),
             lh=round(FS["subtitle"] * LH_RATIO), clear_v=14)
TIGHT = dict(pad_y=max(1, ROOMY["pad_y"] - 2), date_gap=max(1, ROOMY["date_gap"] - 1),
             lh=max(FS["subtitle"] + 2, ROOMY["lh"] - 1), clear_v=20)
# 判据：按宽松那套排完，同侧最小间距低于这个数就叫极限压缩。24px 是量出来的分界 ——
# 20 事件那张是 14px，其余各图最小 108px，中间没有别的样本落在附近。
GAP_COMFORT = 24
CARD_PAD_Y = ROOMY["pad_y"]
DATE_GAP = ROOMY["date_gap"]
CARD_W_MIN = 104
RX = 12
PAGE_H = paper.PORT_H     # 一页 A4 竖版的正文高度，来自 paper.py
TITLE_ZONE = 76           # title band; +26 when party labels are shown
MAX_COLS = 2
CLEAR_V = ROOMY["clear_v"]


# 正文用 subtitle（13）、日期用 note（12），与横向的默认档一致。
FS_BODY, FS_DATE, FS_TITLE = FS["subtitle"], FS["note"], FS["doc_title"]
FS_TITLE_LOCAL = FS_TITLE
TITLE_LH = 38             # 图名折行时的行距
LH = ROOMY["lh"]


def _use(metrics):
    """切换这一张图用哪一套卡片尺寸。模块级常量，因为 _h() 也要读到。"""
    global CARD_PAD_Y, DATE_GAP, LH, CLEAR_V
    CARD_PAD_Y = metrics["pad_y"]
    DATE_GAP = metrics["date_gap"]
    LH = metrics["lh"]
    CLEAR_V = metrics["clear_v"]


def _card_w(cols):
    return (W - MARGIN_X * 2 - AXIS_GAP * 2 - COL_GAP * (cols - 1)) / (2 * cols)


def _title_fit(txt, avail):
    """标题块，与横向、分页共用 paper 里那一套。

    纵向原来会折行但**不限行数**，作者定的规矩是最多折一次（两行为止）；块高也原来
    写死 76，现在按实际行数算。三处产物（横向、纵向、分页）必须用同一套，否则同一个
    标题在三种形态下的字号和留白各不相同。
    """
    return paper.title_fit(txt, avail, text_w, wrap)


def _title_lines(txt, avail):
    """图名折行。竖版收窄到 623px 之后，30px 的图名会宽出画布几十个像素。

    不许缩字号：字号一律取自 common.FS，渲染器里不许写死，而降一档就把图名和
    编号数字变成同一级。也不许截断加省略号，那等于从材料里删字。所以让标题区变高、
    图名折行，这与卡片让步、文字不让步是同一条。
    """
    return wrap_atomic(txt, FS_TITLE_LOCAL, avail, wrap, text_w) or [txt]


def _lines(ev, cw, max_rows=None):
    """The full title, wrapped. Never clipped, never abbreviated.

    An earlier version clipped the title and appended an ellipsis when it ran
    past a fixed row count. That is the wrong trade in two ways: it drops
    characters from the material, which the verbatim rule forbids outright, and
    it leaves a mark on the figure announcing that something is missing. The
    card height is what gives way, not the text: cards get thinner and the step
    gets shorter until everything fits. max_rows is accepted and ignored so the
    call sites need not change.
    """
    # [C14] attrib 是标注不是引文：单独字段，渲染时拼在正文开头。
    # 不能写进 head —— head 必须是原句的子序列，「原告称」三个字不在原句里。
    _txt = ev.get("head") or ev.get("text", "")
    _at = (ev.get("attrib") or "").strip()
    if _at:
        _txt = f"（{_at}）" + _txt
    return wrap_atomic(_txt, FS_BODY,
                       cw - CARD_PAD_X * 2, wrap, text_w)


def _h(ev, cw, max_rows=2):
    head = FS_DATE + DATE_GAP if _date(ev) else 0
    return CARD_PAD_Y * 2 + head + len(_lines(ev, cw, max_rows)) * LH


def _date(ev):
    return ev.get("time", {}).get("date_text") or ev.get("date_text") or ""


def _assign(evs, side_of, ys, cols, cw, max_rows=2):
    """Greedy column per side, preferring the column next to the previous card."""
    taken, out, prev = {}, {}, {}
    for i, ev in enumerate(evs):
        s = side_of(ev)
        hh = _h(ev, cw, max_rows)
        top, bot = ys[i] - hh / 2 - CLEAR_V / 2, ys[i] + hh / 2 + CLEAR_V / 2
        order = sorted(range(cols), key=lambda c: (abs(c - prev.get(s, 0)), c))
        for c in order:
            occ = taken.setdefault((s, c), [])
            if all(bot <= a + 0.51 or top >= z - 0.51 for a, z in occ):
                occ.append((top, bot))
                out[ev["id"]] = c
                prev[s] = c
                break
        else:
            return None
    return out


def _title_zone(m):
    """标题区的高度。图名折成几行，这一区就要高几行。

    折行而不加高，第二行就会压到第一张卡上：623px 的画布上这件事当场发生过。
    """
    return _title_fit(m.get("title_text", ""), W - 48)[2]


def render(m, out_path, cols=None, _capture=None, plan_only=False):
    # [C13] 非精确日期的事项按 anchor 落位，不按列表顺序。
    # anchor 此前是个死字段：校验器逼着模型填，而渲染器从不读它 —— 实测把 anchor 从
    # 「2」改成「4」，图上圆点位置一模一样。位置实际由列表顺序决定，于是模型按语义填的
    # anchor 与图上的先后不一致时，没有任何人会发现。
    # [C14] 冲突的两方：卡框与引线用虚线，正文开头加小括号说明是谁陈述
    _conf_ids = set()
    for _c in (m.get("conflicts") or []):
        for _x in (_c.get("members") or []):
            _conf_ids.add(str(_x))

    evs = order_by_time(m["events"])
    lanes = m.get("lanes") or []
    up_id = lanes[0]["id"] if lanes else None
    idx = {e["id"]: k for k, e in enumerate(evs)}

    def side_of(ev):
        if lanes:
            return "L" if ev.get("lane") == up_id else "R"
        return "L" if idx[ev["id"]] % 2 == 0 else "R"

    n = len(evs)

    def attempt(k, max_rows):
        cw = _card_w(k)
        if cw < CARD_W_MIN:
            return None
        need_date = max(text_w(_date(e), FS_DATE) for e in evs) + CARD_PAD_X * 2
        if cw < need_date:
            return None
        hmax = max(_h(e, cw, max_rows) for e in evs)
        R, _fsn, _nb = dot_metrics(2 * R_MIN + 10)   # provisional, refined below
        # How far apart two cards in the SAME column actually are depends on how
        # the sides interleave. Perfect alternation puts them 2k steps apart, but
        # a run of consecutive same-side events puts them only k apart, and this
        # case has eight 原告 events in a row. Measure the worst run instead of
        # assuming the ideal.
        runs, cur, last = 1, 1, None
        for e in evs:
            s = side_of(e)
            cur = cur + 1 if s == last else 1
            runs = max(runs, cur)
            last = s
        same_col_gap = k if runs > 1 else 2 * k
        neighbour_gap = 1 if runs > 1 else 2
        step = max(2 * R_MIN + 10,
                   (hmax + CLEAR_V) / same_col_gap,
                   (hmax / 2 + CLEAR_V) / neighbour_gap)
        # 76 + 72: title baseline 40, label baseline 100, and the first card
        # then clears the label by the same margin the pages use.
        band = _title_zone(m) + (72 if lanes else 0)
        ys = [snap_centre(band + hmax / 2 + i * step) for i in range(n)]
        col = _assign(evs, side_of, ys, k, cw, max_rows)
        if col is None:
            return None
        # [P25] 引线穿卡的判据：只有当**外侧卡的引线要横穿内侧卡**时才算穿越。
        # 引线从轴水平伸到卡片，所以外侧卡的引线会经过内侧列所在的那条横带 ——
        # 只有内侧那张卡的竖直范围覆盖了外侧卡的引线高度，才真的被穿。
        # 原来的判据是「同侧、外侧列的两张卡竖直距离必须大于半个卡高加空隙」，
        # 它把「不同列」当成了「必须竖直分开」，而两列的意义恰恰是**横向错开、
        # 竖直可以挨着**。后果：六个事项、21 字标题就报「每侧 2 列排不开」，
        # 而 30 / 60 / 90 那几档本该靠两列省一页，于是两列整片区间都被误拒。
        # 现在按真实几何判：外侧卡的引线在 ys[j] 这个高度横穿，内侧卡占
        # [ys[i] - h/2, ys[i] + h/2]，两者重叠才算穿越。
        for i, e in enumerate(evs):
            for j, f in enumerate(evs):
                if side_of(f) != side_of(e) or col[f["id"]] <= col[e["id"]]:
                    continue
                _hi = _h(e, cw, max_rows) / 2
                if abs(ys[j] - ys[i]) < _hi:
                    return None
        return k, cw, step, ys, col, R, hmax, max_rows

    # Prefer the arrangement that stays on ONE page with the most text it can
    # hold there; only spill to a second page when one row per card is not
    # enough. Order matters: rows first, columns second.
    # Column count is chosen by PAGE FIT, not by whether a seating exists. One
    # column always seats — that is why two columns never appeared before — so
    # the question is not "does it fit" but "does it fit on a page". Fewest
    # columns wins, because fewer columns means wider cards; a second column is
    # bought only when one column would spill onto another page.
    def _select():
        best, best_pages = None, 10 ** 6
        for k in ([cols] if cols else range(1, MAX_COLS + 1)):
            cand = attempt(k, None)
            if not cand:
                continue
            _k, _cw, _step, _ys, _col, _R, _hmax, _mr = cand
            tall = _ys[-1] + _hmax / 2 + 28
            if tall <= PAGE_H:
                return cand          # 装得进一页：列数少的赢，就到这里
            # 装不进一页：按**页数**比，页数相同时列数少的赢。
            # 原来这里比的是绝对高度，于是十三个事件选了两列、卡宽 128px、仍旧两页，
            # 而一列同样是两页、卡宽 262px。矮一点却换不来少一页，那点高度买不到任何
            # 东西，代价是卡片宽度砍掉一半。分页的意义是页数，不是像素。
            pages = int((tall - 1) // PAGE_H) + 1
            if best is None or pages < best_pages:
                best, best_pages = cand, pages
        return best

    # [P24] 两列若能省一页，就该为它降低字数再试一次。
    # 实测两列在 30 / 60 / 90 个事项这几档确实能省一页（30 个：一列两页、两列一页），
    # 但上面这段拿**同一批标题**去试两列，标题一长就被座次拒掉，于是两列从来没被选中过。
    # 缺的不是判据而是那条退路：先按原字数试，两列被拒就按两列的容量把标题截短再试，
    # 若因此少一页则采用两列。截短这件事由前端做（容量既是上限也是目标），渲染器只负责
    # 把「两列能省一页」这个事实报出来 —— 所以这里只记录，不擅自改文字。
    def _two_col_would_help():
        """两列是否能省一页。返回 (能否, 两列的字数上限)。"""
        one = _select_pages(1)
        if one is None:
            return False, 0
        for cap in range(1, 40):
            two = _select_pages(2, cap_chars=cap)
            if two is not None and two < one:
                return True, cap
        return False, 0

    def _min_gap(cand):
        """按真实座次量相邻同侧同列卡片之间最小的空隙。

        不用「(卡高 + 空隙) / 步距」那个公式反推：公式给的是最坏情形，量出来六张图
        全是 14px，分不出哪一张真的被压满。按座次一对一对地量，才看得出五个事件那张
        其实有 25px（卡只有一行，矮），而二十个事件那张通篇贴着底。
        """
        _k2, _cw2, _st2, _ys2, _col2, _R2, _hm2, _mr2 = cand
        buckets = {}
        for i2, e2 in enumerate(evs):
            buckets.setdefault((side_of(e2), _col2[e2["id"]]), []).append(
                (_ys2[i2], _h(e2, _cw2, _mr2)))
        gaps = []
        for arr in buckets.values():
            arr.sort()
            for a2, b2 in zip(arr, arr[1:]):
                gaps.append(b2[0] - b2[1] / 2 - (a2[0] + a2[1] / 2))
        return min(gaps) if gaps else 1e9

    # 先按宽松的卡片尺寸排。只有同时满足两条才换成收紧那一套：
    #   1. 没有两侧主张方。有主张方时两侧各自成列，步距天然是两倍，卡片本来就分得开；
    #      交替纯粹为了省地方时才会通篇贴着底。
    #   2. 按宽松尺寸排完，实际最小空隙已经低于 GAP_COMFORT。
    # 收紧卡内是有代价的，不缺空隙的图不该付这个代价。五个事件那张最小空隙 25px，
    # 因此照旧走宽松；二十个事件那张 14px，才换。
    _use(ROOMY)
    got = _select()
    _tight = False
    if got and not lanes and _min_gap(got) < GAP_COMFORT:
        _use(TIGHT)
        _t = _select()
        if _t:
            got, _tight = _t, True
        else:
            _use(ROOMY)
    if not got:
        _use(ROOMY)
        raise ValueError(f"{n} 个事件在每侧 {MAX_COLS} 列内排不开，请拆图或分页。")
    k, cw, step, ys, col, _R0, hmax, max_rows = got
    # Now that the step is settled, size the bead from it — same model as the
    # horizontal form, so the two orientations can never drift apart.
    R, fs_num, num_base = dot_metrics(step)

    H = ys[-1] + hmax / 2 + 28
    axis_x = W / 2

    # **只算不画的出口**（与横向同一个模式）。
    # 交出的必须是接下来真正落笔要用的量，不是另算的 —— 横向那一档吃过这个亏：
    # 出口返回按层的卡宽而落笔用优雅宽度，计划比实画窄 8 到 13px。
    if plan_only:
        return {
            "kind": "编号型", "form": "纵向",
            "n": len(evs), "width": W, "height": H,
            "cols": k, "card_w": cw, "step": step, "rows_max": max_rows,
            "pages": max(1, int((H - 1) // PAGE_H) + 1),
            "per_line": max(1, int((cw - 2 * CARD_PAD_X) // FS_BODY)),
            "lanes": len(m.get("lanes") or []),
            "fits_page": H <= PAGE_H,
        }
    _tfit = _title_fit(m.get("title_text", ""), W - 48)
    S = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H:.0f}" '
         f'viewBox="0 0 {W} {H:.0f}" font-family="{FONT}">',
         f'<rect data-role="canvas-bg" width="{W}" height="{H:.0f}" fill="{C["bg"]}"/>',
         *[f'<text x="{W/2}" '
           f'y="{paper.TITLE_PAD_TOP + _tfit[0] + _i * _tfit[0] * paper.TITLE_LH_RATIO:.1f}" '
           f'font-size="{_tfit[0]}" '
           f'font-weight="700" font-family="{TITLE_FONT}" fill="{C["ink"]}" '
           f'stroke="{C["ink"]}" stroke-width="0.3" text-anchor="middle">'
           f'{esc(_ln)}</text>'
           for _i, _ln in enumerate(_tfit[1])],
         rule(axis_x, ys[0], axis_x, ys[-1], "axis")]

    # Which side is whose. Centred over the FULL span that side occupies, which
    # with two columns is both of them. Nothing else goes under the title.
    if lanes:
        side_w = k * cw + (k - 1) * COL_GAP
        for lab, lx in ((lanes[0]["label_text"], axis_x - AXIS_GAP - side_w / 2),
                        (lanes[1]["label_text"], axis_x + AXIS_GAP + side_w / 2)):
            # 主张方标注紧贴图形，不贴标题。原来钉在标题区下方 24px，而第一张卡还在
            # 72px 之后，这行字于是浮在标题与图形中间、离图形太远，看起来像标题的
            # 副标题而不是两侧的标识。改成从第一张卡的上沿往上量固定的一点距离。
            _first_top = ys[0] - hmax / 2
            S.append(f'<text x="{lx:.1f}" y="{_first_top - 14:.0f}" font-size="{FS_DATE}" '
                     f'font-weight="600" fill="{C["ink2"]}" text-anchor="middle">'
                     f'{esc(lab)}</text>')

    def card_x(ev):
        c = col[ev["id"]]
        if side_of(ev) == "L":
            return axis_x - AXIS_GAP - (c + 1) * cw - c * COL_GAP
        return axis_x + AXIS_GAP + c * (cw + COL_GAP)

    for i, ev in enumerate(evs):                      # connectors first
        x = card_x(ev)
        soft = (ev.get("time", {}).get("certainty", "exact") != "exact"
                or str(ev.get("id")) in _conf_ids)
        a, b = ((x + cw, axis_x - R) if side_of(ev) == "L" else (axis_x + R, x))
        S.append(rule(a, ys[i], b, ys[i], "connector", dash=soft))

    for i, ev in enumerate(evs):                      # then cards
        x, h = card_x(ev), _h(ev, cw, max_rows)
        emph = ev.get("emphasis")
        soft = (ev.get("time", {}).get("certainty", "exact") != "exact"
                or str(ev.get("id")) in _conf_ids)
        top = ys[i] - h / 2
        if emph:
            S.append(f'<rect x="{x:.1f}" y="{top:.1f}" width="{cw:.1f}" height="{h}" '
                     f'rx="{RX}" fill="{C["red"]}"/>')
        elif soft:
            S.append(f'<rect x="{x:.1f}" y="{top:.1f}" width="{cw:.1f}" height="{h}" '
                     f'rx="{RX}" fill="{C["bg"]}" stroke="{C["card_stroke"]}" '
                     f'stroke-width="1" stroke-dasharray="6 4"/>')
        else:
            # v1: every non-accent card carries a 1px #D6DAE0 border.
            S.append(f'<rect x="{x:.1f}" y="{top:.1f}" width="{cw:.1f}" height="{h}" '
                     f'rx="{RX}" fill="{C["card_fill"]}" stroke="{C["card_stroke"]}" '
                     f'stroke-width="1"/>')
        cxm = x + cw / 2
        ty = top + CARD_PAD_Y + FS_DATE
        d = _date(ev)
        if d:
            S.append(f'<text x="{cxm:.1f}" y="{ty:.1f}" font-size="{FS_DATE}" '
                     f'font-weight="600" fill="{C["white"] if emph else C["ink2"]}" '
                     f'text-anchor="middle">{esc(d)}</text>')
            ty += FS_DATE + DATE_GAP
        ty += FS_BODY - FS_DATE
        S.extend(body_lines(_lines(ev, cw, max_rows), cxm, cw, CARD_PAD_X, FS_BODY, LH, ty,
                        C["white"] if emph else C["ink"]))

    for i, ev in enumerate(evs):                      # dots last
        fill = C["red"] if ev.get("emphasis") else C["circle"]
        S.append(f'<circle data-role="node" data-id="{ev["id"]}" cx="{axis_x}" '
                 f'cy="{ys[i]:.1f}" r="{R}" fill="{fill}"/>')
        S.append(f'<text x="{axis_x}" y="{ys[i] + num_base:.1f}" '
                 f'font-size="{fs_num}" font-weight="700" fill="{C["white"]}" '
                 f'text-anchor="middle">{esc(str(i + 1))}</text>')

    if _capture is not None:
        _capture.update(dict(k=k, cw=cw, step=step, ys=ys, col=col, R=R,
                             hmax=hmax, fs_num=fs_num, num_base=num_base,
                             side_of=side_of, card_x=card_x, evs=evs,
                             lanes=lanes, axis_x=axis_x, H=H))
    S.append("</svg>")
    open(out_path, "w", encoding="utf-8").write("\n".join(S))
    return int(W), int(H), k, cw, step, max_rows


def layout(m, cols=None):
    """Run the seating only, and hand back everything a paginator needs."""
    box = {}
    render(m, os.devnull, cols, _capture=box)
    return box


if __name__ == "__main__":
    m = json.load(open(sys.argv[1], encoding="utf-8"))
    w, h, k, cw, st, rows = render(m, sys.argv[2])
    print(f"画布 {w}x{h}   每侧 {k} 列   卡宽 {cw:.0f}px   步距 {st:.0f}px   "
          f"每卡至多 {rows} 行   页数 {h // paper.PORT_H + 1}")