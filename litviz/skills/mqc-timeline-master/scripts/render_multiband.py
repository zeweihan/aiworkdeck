#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Multi-band numbered timeline. 原告 above the axis, 被告 below, each spread over
up to three INVISIBLE bands.

This is v1's `render_points` form, unchanged in every visual particular — thin
hairline axis, solid dark numbered dot on the axis, date as the first line INSIDE
the card, solid grey card blocks, one deep red — with exactly one thing extended:
v1's `band` had two values (up / down) and therefore two rows. Two rows is what
forces one card per column, which is what crushed the cards to 71px when eight
consecutive events fell on the same side.

With three bands per side a card only has to clear the nearest card in ITS OWN
band, so its usable width becomes roughly (band count) x column pitch. Eight
consecutive 原告 events go from 71px to ~214px, which is v1's normal card width.

The bands are NEVER drawn. No lane lines, no tint stripes, no group bars — the
moment you draw them the figure reads as a table. Depth is carried by the length
of the hairline connector alone. (Same reason v1's longform forbids 竖标/底纹带
for grouping: grouping is carried by the card itself.)

Band assignment is greedy and deterministic: walk events in time order and put
each card in the band CLOSEST TO THE AXIS whose horizontal span is still free.
Because the bands below it are empty at that x, the connector can never cross
another card.
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "mqc-litigation-visual-redraw", "scripts"))
import paper  # noqa: E402
from common import wrap, text_w, esc, C, FONT, TITLE_FONT, FS, TOKENS  # noqa
from geom import rule, dot_metrics, R_MIN, wrap_atomic, body_lines, snap_axis, order_by_time  # noqa

RADIUS = TOKENS["radius"]

# ---- v1's own constants, copied not re-invented -------------------------
# Card width is DERIVED from the column pitch, never fixed.
#
# Multi-band buys horizontal room by letting cards OVERLAP horizontally. But the
# moment a card covers another same-side event's dot, that event's connector has
# to rise through this card: it disappears under the card and re-emerges above
# it, which reads as a line piercing the box. Drawing order hides overlap, not
# crossing. The only real fix is to forbid the overlap that causes it:
#
#     card_w / 2 + clearance <= pitch      i.e.  card_w <= 2 * pitch - 2 * clear
#
# so a card never reaches its same-side neighbour's dot. Measured on A4
# landscape this caps the horizontal form at about 15 events single-party and 14
# two-party, not the 20 the earlier arithmetic suggested — that number assumed
# the crossings were acceptable.
CW_BY_BAND = {}
CW_ELEGANT = {}
ELEGANT_W = 0
CARD_W_MAX, CARD_W_MIN = 214, 0     # 下限由日期折行后的真实需要算出，见 [M1]
# [M6] 行距与内边距按字号成比例，不写死。
# v1 的 24px 行距是配 17px 正文定的，比值 1.41。正文降到 12px 之后行距还是 24px，
# 比值变成 2.00，行间距成了字高的两倍，卡片里的两行字散开、与上下沿的距离也不对
# —— 作者一眼就看出来了。凡是按某一个字号手调出来的常数，字号一变就必须跟着变，
# 否则它就从「调好的」变成「错的」。
# 比例基准取自 **v1 的原值**：正文 17px 配左右内边距 16、上下内边距 13、行距 22。
# 上一版我取的是时间轴大师里那份复制品的 14 / 15 / 24，横竖还颠倒了（原值左右比上下
# 宽，复制品左右比上下窄）。基准取错，按比例算出来的每一档都跟着偏。凡是要沿用 v1 的
# 手调常数，就得回 v1 的文件里取，不能拿手边这份复制品当原件。
PAD_X_RATIO = 16 / 17     # 卡内左右内边距 ÷ 正文字号
PAD_Y_RATIO = 13 / 17     # 卡内上下内边距 ÷ 正文字号
LH_RATIO = 22 / 17        # 行距 ÷ 正文字号
DATE_GAP_RATIO = 8 / 17   # 日期与正文之间的间隙 ÷ 正文字号

#: 默认正文字号。13px 是作者看四档实图之后选定的：印在 A4 横版上是 8.7pt，比 8pt 的
#: 下限留一点余量，13 个事项那一档每行放得下 6 个字。
#: 不能留在 v1 的 17px —— 17px 是配 v1「卡宽固定 214px」定的，而 A4 封住宽度之后卡宽
#: 由事项数压出来，13 个事项时只有 111px，17px 连五个字的标题都放不下，图直接画不出来。
# [M9] 默认档取自字阶，不写死数字：正文用 subtitle（13），日期用 note（12）。
# 守卫不许渲染器里出现写死的字号，这条是对的 —— 而 13 与 12 恰好就是字阶里已有的两级，
# 所以引用它们即可，v1 的字阶一个字没改。
FS_BODY_DEFAULT = FS["subtitle"]
FS_DATE_DEFAULT = FS["note"]
PAD_X = round(FS_BODY_DEFAULT * PAD_X_RATIO)
PAD_Y = round(FS_BODY_DEFAULT * PAD_Y_RATIO)
LH = round(FS_BODY_DEFAULT * LH_RATIO)
DATE_GAP = round(FS_BODY_DEFAULT * DATE_GAP_RATIO)
CONNECT = 48              # axis -> nearest band (+10%)
BAND_GAP = 16             # clearance between two bands (+10%)
MARGIN_X = 56
# 侧标签不再占横向空间。作者的判断：时间轴应该通到底，占满整个横向画幅，而主张方
# 标注凭什么先切掉 72px。这一刀在 13 个事件那一档直接值一个事件的位置。
# 改成把标注压在轴的左端上下方（轴线之上、之下各一行小字），标注与轴共用同一段横向
# 空间，轴从左边距一直画到右边距。
SIDE_GUTTER = 0
# The pitch floor is set by the NUMBERED DOTS, not by the cards: cards may
# overlap across bands, dots may not. 2R plus clearance is the real floor.
COL_PITCH_MIN = 2 * R_MIN + 10
# 标题块的高度由 paper.title_fit 按实际行数算出，这里只留一个默认值给早期计算用。
# 原来写死 116，且横向标题**根本不折行** —— 标题一长就直接溢出画布。
TITLE_ZONE_EST = 116      # 座次阶段的保守估计；真实块高由 paper.title_fit 算出
RX = RADIUS["card"]
# Band count is DERIVED from the event count, not fixed. Both floors were
# measured on A4 landscape (1177px, card 168px): same-band clearance needs
# stride x pitch >= card + 2 x clearance, and dots must not touch.
#   <=10 events  1 band  per side (plain alternation, v1's original form)
#   <=16 events  2 bands per side
#   <=20 events  3 bands per side   <- 20 is a DESIGN cap, not a geometric one:
#                                      three bands still clear at ~28, but the
#                                      dots read as one continuous string well
#                                      before that.
#   > 20 events  refuse; go vertical.
# 泳道阶梯与各档的正文行数上限。三个数都是量出来的，不是定的：
#
#   双泳道两泳道，14 个事件是极限。15 个起卡宽掉到 93px，日期被迫折成两行、卡片跟着
#   撑高，图高 780px 超出纸高 54px，每行只剩 5 个字。作者的判断是「每个模块已经是
#   极限的恶心」，量出来的拐点正好在 14 与 15 之间。
#
#   三泳道要成立，正文必须限制成一行。一行时卡高 74px，一侧 48+74×3+16×2 = 302px，
#   两侧加标题区共 720px，卡进纸高 726px；正文两行时卡高 98px，全图 864px，超 138px，
#   无论怎么调都装不下。所以「三泳道」和「正文两行」不能同时要，这是算术不是取舍。
# 阶梯只是**起点**，不是判决。层数最终由座次算出来：先按这张表给一个起始层数，
# 排不下就加一层，加到三层还排不下才拒绝。
# 这里原来写着 (10,1),(14,2),(20,3) 并被我当成「事件数上限」报给作者，那是错的：
# 量出来同一个事件数换个排布结论完全不同 —— 14 个事件完美交错时上下各一层就够、
# 图高 444px，纸内还空一半；同侧连三才要两层、图高 624px。把最坏值填进一维表，
# 等于把很多本来画得出的图判死。所以事件数只用来给起点，真正说话的是同侧最长连续段。
BAND_LADDER = ((10, 1), (14, 2), (20, 3))
#: 每一档泳道下，卡片正文最多几行。三泳道那一档是 1，理由见上。
# 按修正后的间距（行距 = 字号 × 1.41、内边距 = 字号 × 0.88）重算：
#   一层任意行数都装得下；两层三行 666px 装得下；三层一行 676px 刚好，两行 778px 超 52px。
# 上一版这张表是按旧的写死间距（行距 24、内边距 15）算的，三层那一档当时也是 1 行，
# 但两层那一档当时是 2 行，现在放宽到 3 行。手调的常数一变，依赖它的表就要重算。
ROWS_BY_BAND = {1: 3, 2: 3, 3: 1}
HARD_MAX = 24             # beyond this the label is 2 characters; go vertical
CARD_CLEAR = 14           # horizontal clearance each side of a card

FS_TITLE, FS_NUM = FS["doc_title"], FS["num"]
FS_BODY, FS_DATE = FS_BODY_DEFAULT, FS_DATE_DEFAULT


def card_lines(ev, cw=None):
    txt = ev.get("head_short") or ev.get("head") or ev.get("text", "")
    # 「谁陈述的」写在**单独的字段** attrib 里，渲染时拼在正文开头。
    # 不能直接写进 head：head 必须是原句的子序列（只许删减不许改写），而「原告称」
    # 这三个字不是原句里的，写进去子序列检查必然拦住 —— 它是标注不是引文。
    _at = (ev.get("attrib") or "").strip()
    if _at:
        txt = f"（{_at}）" + txt
    w = cw if cw is not None else CARD_W
    return wrap_atomic(txt, FS_BODY, w - PAD_X * 2, wrap, text_w)


def card_h(ev, cw=None):
    nd = len(date_lines(ev, cw if cw is not None else CARD_W))
    head = (FS_DATE * nd + (nd - 1) * 3 + DATE_GAP) if nd else 0
    return PAD_Y * 2 + head + len(card_lines(ev, cw)) * LH


def date_text(ev):
    return ev.get("time", {}).get("date_text") or ev.get("date_text") or ""


def date_lines(ev, cw=None):
    """日期在卡片里占几行。

    卡片窄到装不下整个日期时，允许在**年与月日之间**折成两行（2023 / 01.10）。这只是
    插一个换行，一个字不改，忠实规则允许；而它换来的容量很实在：卡宽下限从「整个日期
    的宽度」降到「年那四个字符的宽度」，16 个事件那一档就是被这 9px 卡住的。
    不折行时仍是一行，稀疏图的观感不变。
    """
    dt = date_text(ev)
    if not dt:
        return []
    if cw is None or text_w(dt, FS_DATE) <= cw - PAD_X * 2:
        return [dt]
    for sep in (".", "-", "/", "年"):
        if sep in dt:
            a, b = dt.split(sep, 1)
            return [a + (sep if sep == "年" else ""), b]
    return [dt]


def date_floor(evs):
    """折行之后，日期这一行对卡宽的真实要求。"""
    widest = 0
    for e in evs:
        dt = date_text(e)
        if not dt:
            continue
        parts = date_lines(e, cw=0)          # cw=0 逼它给出折行后的两半
        widest = max(widest, max(text_w(x, FS_DATE) for x in parts))
    return widest + PAD_X * 2


def assign_bands(evs, side_of, xof, max_bands, cw_by_band=None):
    """Greedy: nearest free band to the axis. Returns {id: band_index}.

    The occupied span is the card plus HALF the clearance on each side, so two
    neighbours end up exactly one clearance apart. Adding a full clearance to
    both sides demanded two clearances between neighbours, which made cards that
    fit by exactly 28px fail on a half-pixel of rounding and get pushed up a
    band. That is why twelve alternating events, whose same-side neighbours are
    already two columns apart, were spread over three bands for no reason.
    """
    occupied = {}          # (side, band) -> list of (x_left, x_right)
    out = {}
    prev = {}              # side -> band of the previous card on that side
    def half_of(b):
        w = (cw_by_band or {}).get(b, CARD_W)
        return w / 2 + CARD_CLEAR / 2
    for i, ev in enumerate(evs):
        side = side_of(ev)
        cx = xof[i]
        # Try bands in order of DISTANCE FROM THE PREVIOUS CARD on this side, so a
        # run of events walks 0,1,0,1 or 0,1,2,1,0 and never jumps two bands at
        # once. Ties break toward the axis, so a sparse figure still collapses to
        # band 0 instead of drifting upward. Always trying band 0 first produced
        # sequences like 0,0,2,1,2,1,2,0 — legal, but it reads as noise.
        # 默认落在**最靠近时间轴的那一条泳道**，也就是低位优先。
        # 原来是「离上一张卡最近的泳道优先」，那条规则在一侧连着好几个事件时会把它们
        # 一路往高位推，读者要抬着眼睛看，而低位本来空着。作者的判断：默认应当贴近
        # 时间轴，不该默认远离它。
        # 「离上一张最近」当初是为了避免 0,0,2,1,2 这种跳跃；低位优先同样不会跳，
        # 因为它总是从 0 开始往上找第一个放得下的。
        order = list(range(max_bands))   # [M5] 低位优先
        for b in order:
            half = half_of(b)
            L, Rr = cx - half, cx + half
            taken = occupied.setdefault((side, b), [])
            if all(Rr <= a + 0.51 or L >= z - 0.51 for a, z in taken):
                taken.append((L, Rr))
                out[ev["id"]] = b
                prev[side] = b
                break
        else:
            # No band could seat this card. Cramming it into the last band was how
            # a three-band figure ended up reporting a fourth: the overflow has to
            # fail the whole attempt so the caller escalates or refuses, never
            # silently produce a layout that violates its own constraints.
            return None
    return out


def render(m, out_path, target_w=None, min_card=None, fs_body=None, fs_date=None,
           plan_only=False):
    # 横向形态的宽度默认就是 A4 横版的预算，不是「不限」。以前默认 None 等于不限，
    # 于是二十个事件直出 2623px，是横版纸的两倍半，而调用方只要忘了传第三个参数就
    # 会拿到这样一张图。默认值必须是那个硬上限，传参只用来做实验。
    if target_w is None:
        target_w = paper.LAND_W
    global FS_BODY, FS_DATE
    # The card-width floor and the body size are the two knobs that decide how
    # many events fit. They are arguments, not constants, because "how many fit"
    # has no single answer: it is whatever width still holds a readable label.
    if fs_body:
        FS_BODY = fs_body
        globals()["LH"] = round(fs_body * LH_RATIO)
        globals()["PAD_Y"] = round(fs_body * PAD_Y_RATIO)
        globals()["DATE_GAP"] = round(fs_body * DATE_GAP_RATIO)
        globals()["PAD_X"] = round(fs_body * PAD_X_RATIO)
    if fs_date:
        # The date line is never wrapped, so the LONGEST date sets a hard floor on
        # the card width. It turned out to be the real cap on how many events fit:
        # a range like 2021.05.19至21 needs 128px at 13px type, which is wider
        # than a 14-event card. Shrinking the dots and the labels changed nothing
        # until this shrank too.
        FS_DATE = fs_date
    floor = min_card or CARD_W_MIN
    # [C14] 冲突的两方：卡框与引线都用**虚线**，并在正文开头加一个小括号说明是谁陈述。
    # 复用已有的规矩（不确定的用虚线），不引入新的视觉元素 —— 而「对同一件事双方说法
    # 相反」正是一种不确定：哪一方为真要由法院认定，图上不该替它下判断。
    # 两方各在自己的象限（原告在上、被告在下），本来就分开，所以不必连线也不必记号。
    _conf_ids = set()
    for _c in (m.get("conflicts") or []):
        for _x in (_c.get("members") or []):
            _conf_ids.add(str(_x))

    # [C13] 非精确日期的事项按 anchor 落位，不按列表顺序。
    # anchor 此前是个死字段：校验器逼着模型填，而渲染器从不读它 —— 实测把 anchor 从
    # 「2」改成「4」，图上圆点位置一模一样。位置实际由列表顺序决定，于是模型按语义填的
    # anchor 与图上的先后不一致时，没有任何人会发现。
    evs = order_by_time(m["events"])
    lanes = m.get("lanes") or []
    # [M14] 三泳道：**极端情况下的构图**，用来体现层级关系，不是容量的下一档。
    # 作者反复要求过，我一直以「触发不到、加层反而更挤」为理由拒绝 —— 那是把泳道
    # 当成了容量阶梯。泳道是语义上的分方（谁的主张 / 哪一层主体 / 哪个程序阶段），
    # 层（band）才是几何上的错开，两件事不是一件。
    # 排法：三条泳道依次往下，轴画在**第一条与第二条之间**（不穿透任何卡片）。
    # 声明三条 lanes 就走三泳道，与事项多少无关 —— 七八个也行、九十个也行，
    # 多到横向排不下时照旧转纵向。
    up_id = lanes[0]["id"] if lanes else None
    # [M14] **泳道由 lane 直接决定在哪一条横带上**，不由拥挤程度分配。
    # 极限是两侧各三条、共六条横带（与早先定的横轴上限一致：轴上三层、轴下三层）。
    #
    # 我理解错过两轮：先做成三条平铺往下，又做成上二下一 —— 都不对。
    # 作者说的是**同一侧堆三条**：轴的一侧就有三层横带，每一类主体占一条。
    #
    # 与现有的「层」（band）的关系：层本来是几何机制（同侧排不开就往外加一层），
    # 而泳道要的是**语义机制** —— 同一类主体永远在同一条带上，不管排不排得开。
    # 两者用同一套坐标，区别只在层号由谁决定：几何触发时由 assign_bands 按拥挤程度算，
    # 语义触发时由 lane 在声明里的次序直接指定。
    # 每条泳道分到「哪一侧、第几层」。规则：
    #   · **默认上二下一**（三条时）—— 轴上方两条、下方一条。
    #     只有当下方两条有特殊含义时才启用上一下二，靠 lanes 里的 side 字段指定。
    #   · 四条起按两侧交替铺：上、下、上、下…… 极限两侧各三条、共六条。
    #   · 每条泳道可以自己写 "side": "up" / "dn" 覆盖默认，供上一下二那种情形用。
    lane_band = {}
    lane_side = {}
    if lanes and len(lanes) >= 3:
        _n_ln = len(lanes)
        if _n_ln == 3:
            _default = ["up", "up", "dn"]      # 默认上二下一
        else:
            _default = ["up" if _i % 2 == 0 else "dn" for _i in range(_n_ln)]
        _cnt = {"up": 0, "dn": 0}
        for _i, _ln in enumerate(lanes):
            _sd = _ln.get("side") or _default[_i]
            if _sd not in ("up", "dn"):
                _sd = _default[_i]
            lane_side[_ln["id"]] = _sd
            lane_band[_ln["id"]] = _cnt[_sd]
            _cnt[_sd] += 1
        # 每侧最多三条（与横轴上限一致）：超了就把多的挪到另一侧，再多就拒绝
        for _sd in ("up", "dn"):
            if _cnt[_sd] > 3:
                raise ValueError(
                    f"{_sd} 侧声明了 {_cnt[_sd]} 条泳道，超过每侧 3 条的上限"
                    f"（两侧共 6 条是极限）。请合并泳道或改用纵向。")
    tri = bool(lane_band)

    idx = {e["id"]: k for k, e in enumerate(evs)}

    def side_of(ev):
        if tri:
            return lane_side.get(ev.get("lane"), "up")
        if lanes:
            return "up" if ev.get("lane") == up_id else "dn"
        if ev.get("band"):
            return "up" if ev["band"] == "up" else "dn"
        # single party: alternate, exactly as v1's numbered form does. Alternating
        # is what buys the horizontal overlap in the first place.
        return "up" if idx[ev["id"]] % 2 == 0 else "dn"

    n = len(evs)
    # 三条以上泳道时，侧标必须有**专属的宽度**，否则它跟卡片抢同一块地方。
    # SIDE_GUTTER 是 0（两泳道时侧标落在轴的上下方、卡片之间的空白里，够用），
    # 但三泳道时贴轴那一层的卡片就在标注的高度上，标注被盖住 ——
    # 实测「集团」落在 y=181、而那一层卡片占 150 到 204，图上就看不见它。
    # 所以按最长的那个标注算出需要的宽度，让轴从那之后才开始。
    # **时间轴必须贯穿到底，不许为了放标注在左侧留出空白。**
    # 我曾按最长标注加宽 gut，把轴的起点往右推了 58px —— 那是违反这条规矩的：
    # 轴是这张图的主干，标注是附注，不能让附注挤走主干。
    # 标注的位置改到「引线水平位置的终点」那里（见下方 _lbls 的算法），不占轴的地盘。
    gut = SIDE_GUTTER if lanes else 0
    up = [e for e in evs if side_of(e) == "up"]
    dn = [e for e in evs if side_of(e) == "dn"]

    def snap(v):
        # A 1.4px stroke renders evenly only when its centre sits on a half
        # pixel; landing on integers made half the connectors read thicker.
        return int(v) + 0.5
    # Column pitch is FITTED to the target medium, then checked. Two floors must
    # both hold: dots may not touch (2R + clearance), and two cards in the same
    # band must clear each other (stride x pitch >= card + 2 x clearance).
    # The ladder gives a STARTING depth from the load on one side. It is only a
    # start: eight 原告 events spread evenly need two bands, but eight consecutive
    # ones need three, and the count alone cannot tell them apart. So try the
    # ladder's depth, and if the greedy assignment cannot seat every card without
    # a same-band collision, add a band and try again.
    # [M10] 一律**从一层开始试**，排不下才加层。
    # 原来起点由事件数查表定：13 个事项算出起点 2 层，于是「一层」这个档从来没被试过。
    # 而一层的卡片可以很高（高度预算一层能给到 10 行），两层就只剩 3 行 —— 换行能换几行
    # 直接决定容量，所以跳过一层等于把容量砍掉三分之二：13 个事项本可写 60 字，实测只
    # 报得出 18 字。作者点出的正是这一处：当初只考虑了宽度，没考虑高度可以换行。
    # 层数多只解决「同侧卡片横向撞车」，代价是每张卡变矮、字变少。所以顺序必须是
    # 少层优先，而不是按事件数预判。
    load = max(len(up), len(dn)) if lanes else (n + 1) // 2
    start = 1
    if n > HARD_MAX:
        raise ValueError(
            f"{n} 个事件超过横向形态的上限 {HARD_MAX} 个")

    def seats(bands):
        global CARD_W
        Rr0 = R_MIN + 2                     # 圆点半径的保守估计，仅用于高度反解
        st = bands * 2 if not lanes else bands
        if target_w and n > 1:
            # width = 2*MARGIN + gut + (n-1)*p + card_top, card_top ~ 2*p*span
            # width = 2*MARGIN + gut + (n-1)*p + card, with card = 2p - 2*clear.
            # Solving: p = (T - 2*MARGIN - gut + 2*clear) / (n + 1).
            p = (target_w - MARGIN_X * 2 - gut + 2 * CARD_CLEAR) / (n + 1)
        else:
            p = (CARD_W_MAX + 2 * CARD_CLEAR) / 2
        p = max(p, COL_PITCH_MIN)
        # Card width by band. The topmost band has nothing above it, so it is
        # limited only by its same-band neighbours; every lower band must also
        # clear the dots of the bands above. Escalating to another band is only
        # worth anything if the lower cards give ground in exchange — otherwise
        # the figure just gets taller and nothing fits that did not fit before.
        # ONE width for every band. The topmost band could legally be one
        # clearance wider (14px), because nothing above it needs clearing — that
        # is the entire prize for jumping a band, and it is not worth the extra
        # row of height and the longer connector it costs. So:
        #   topmost band     card <= 2*pitch - clear        (same-band, 2 columns)
        #   any lower band   card <= 2*pitch - 2*clear      (also clears the
        #                                                    adjacent higher dot)
        # and we simply use the stricter of the two everywhere.
        # Shave a pixel off the theoretical maximum. Two same-side cards two
        # columns apart clear each other by EXACTLY one clearance at the limit, and
        # exact equality plus the half-pixel snap made the seating test fail and
        # escalate a band for nothing. Geometry that only just fits does not fit.
        # [M7] 每一层各自算宽度，不再全图统一压到最挤的那一档。
        # 一张卡的宽度受限于「不许横向盖住同侧更高层事件的圆点」，而**最高那一层上面
        # 没有东西**，所以它只需要避开同层邻居（相隔 bands 个列距），可以跨到
        # bands×列距。下面各层仍受一个列距的约束。
        # 这一条同时解释了三泳道为什么一直用不上：卡宽写成 2×列距 时，同侧连续事件
        # 按 0,1,0,1 交替就永远够用，第三层根本触发不到。三层真正买到的是宽度，
        # 不是纵向空间 —— 我此前把这件事说反了。
        cw_low = min(CARD_W_MAX, 2 * p - 2 * CARD_CLEAR - 2)
        # 顶层同层邻居相隔 bands 个列距，所以要减**两倍**净空（左右各一次），
        # 与下层同一个写法。第一版只减了一倍，于是同层重叠检查当场拒绝，三层又没画出来。
        cw_top = min(CARD_W_MAX, bands * p - 2 * CARD_CLEAR - 2) if bands >= 2 else cw_low
        CW = {b: (cw_top if b == bands - 1 else cw_low) for b in range(bands)}
        cw = cw_low
        # [M1] 卡宽下限 = 日期折行后真实需要的宽度，不再用写死的舒适度数字。
        # 原来是 max(CARD_W_MIN=104, 整行日期宽)。104 是个凭感觉定的数，16 个事件那一
        # 档的卡宽 86px 就是被它拒的，而日期折两行之后只要 61px。
        need_date = date_floor(evs)
        if cw < max(floor, need_date):
            return None
        # [M2] 本档泳道允许的正文行数。装不下就换下一档，不截断文字。
        # 三泳道那一档只允许一行，因为三层两行的图高 864px，超出纸高 138px，怎么调都
        # 装不下。所以三泳道能不能用，取决于前端有没有把文字抽到一行的容量之内 ——
        # 这正是「容量决定文字」那件事在排布上的落点。
        # 行数上限改成**按高度算**，不再查表。
        # 查表那一版把「一层」也限成 3 行，于是 15 个事件的十字标题要 4 行就被拒，
        # 而那张图只有 410px 高、纸高 726px 还空着一半 —— 规则比纸更严，那就是规则错了。
        # 真正的约束只有一条：图高不许超过纸高。所以直接反解这一档甬道下每张卡最多几行。
        _hc_max = (paper.LAND_H - TITLE_ZONE_EST - 58 - 2 * Rr0
                   - 2 * (CONNECT + BAND_GAP * (bands - 1))) / (2 * bands)
        _rows_cap = int((_hc_max - PAD_Y * 2 - FS_DATE - DATE_GAP) // LH)
        _rows_cap = max(1, _rows_cap)
        CARD_W = cw
        if any(len(card_lines(e)) > _rows_cap for e in evs):
            return None
        CARD_W = cw
        xs = [snap(MARGIN_X + gut + CARD_W / 2 + i * p) for i in range(n)]
        bd = assign_bands(evs, side_of, xs, bands, CW)
        if bd is None:
            return None
        # a card pushed past the last band is an overflow, not a seat
        for arr in (up, dn):
            seen = {}
            for e in arr:
                k = bd[e["id"]]
                cx = xs[idx[e["id"]]]
                _w = CW[k]
                for a, z in seen.setdefault(k, []):
                    if not (cx + _w / 2 + CARD_CLEAR <= a or
                            cx - _w / 2 - CARD_CLEAR >= z):
                        return None
                seen[k].append((cx - _w / 2 - CARD_CLEAR,
                                cx + _w / 2 + CARD_CLEAR))
        # No connector may cross a card. A connector rises at its own dot's x, so
        # this is equivalent to: no card may horizontally contain another
        # same-side dot. Checked, not assumed — the earlier version relied on
        # draw order, which hides an overlap but cannot hide a crossing.
        # Crossing is DIRECTIONAL. A card at band b is crossed only by the
        # connector of a HIGHER band, because a connector stops at its own band
        # and never continues past it. So the rule is one-way: a card must clear
        # the dots of events ABOVE it, and may freely overhang the dots of events
        # below it. Testing it symmetrically doubled the restriction and cost
        # roughly a third of the usable card width.
        for arr in (up, dn):
            for e in arr:
                ex = xs[idx[e["id"]]]
                for f in arr:
                    if bd[f["id"]] <= bd[e["id"]]:
                        continue
                    if abs(xs[idx[f["id"]]] - ex) < CW[bd[e["id"]]] / 2 + CARD_CLEAR:
                        return None
        globals()["CW_BY_BAND"] = CW
        return bands, st, p, xs, bd

    got = None
    # [M3] 层数由座次算出：起点查表，排不下加一层，三层仍排不下才拒绝
    for bands in range(start, BAND_LADDER[-1][1] + 1):
        got = seats(bands)
        if got:
            break
    if not got:
        raise ValueError(
            f"{n} 个事件在轴上下各 {BAND_LADDER[-1][1]} 条泳道内仍排不开（过于密集）")
    max_bands, stride, pitch, xof, band = got
    # [M14] 声明了三条以上泳道时，**层号由 lane 决定**，覆盖按拥挤程度算出的那一套。
    # 这是「语义分方」与「几何错开」的分界：泳道要的是同一类主体永远在同一条带上，
    # 哪怕它排得下也不许并到别的带里去 —— 读者靠带的位置认类别。
    if lane_band:
        band = {e["id"]: lane_band.get(e.get("lane"), 0) for e in evs}
        max_bands = max(band.values()) + 1

    need = CARD_W + 2 * CARD_CLEAR
    fits = (stride * pitch >= need)
    # The dot scales with the pitch: at 20 events a 17px radius makes the axis
    # read as one continuous string of beads. The numeral scales with it, and its
    # baseline offset is a RATIO of the font size so it stays centred at any size.
    # Sized on the bands actually USED, not on the depth the seating routine was
    # allowed to try. A figure that ended up flat on one band should get the large
    # bead even if two bands were attempted first.
    # One model, shared with the vertical renderer: the bead follows the pitch.
    Rr, fs_num, num_base = dot_metrics(pitch)

    width = MARGIN_X * 2 + gut + (n - 1) * pitch + CARD_W

    # ---- [M8] 逐卡放宽：最小宽度与优雅宽度 --------------------------------
    # 全图共用一个卡宽，是按最挤的那一处算出来的，于是只要有一处挤，所有卡片都被压到
    # 那个宽度。而一张卡真正受的约束只来自它自己两侧的邻居：位置空的那几张本来可以宽
    # 很多（13 个事项时最小宽度 111px 每行 6 字，无邻居约束时能到 214px 每行 15 字，
    # 差九个字）。
    #
    # 两个标准，一张图里只许出现这两种值，不许有连续变化的第三种：
    #   最小宽度  = 座次算出的那个刚性宽度，保证不盖住同侧邻居的圆点（引线不穿卡）
    #   优雅宽度  = 这张卡在自己位置上能长到的宽度，上限取 v1 的正常卡宽 CARD_W_MAX
    # 两档看着是有意的分档；连续变化看着是没调好。
    #
    # 判据只有一条，与守卫同源：放宽后这张卡的左右边界，到**同侧任何更高层事件的圆点**
    # 都要留净空（穿卡是单向的，低层盖住高层的圆点才算穿越），且与同层邻居不重叠。
    CARD_W_MIN_ACTUAL = CARD_W
    _room = {}
    for _i, _e in enumerate(evs):
        _side, _b, _x = side_of(_e), band[_e["id"]], xof[_i]
        _limit = CARD_W_MAX
        for _j, _f in enumerate(evs):
            if _j == _i or side_of(_f) != _side:
                continue
            _d = abs(xof[_j] - _x)
            if band[_f["id"]] > _b:                  # 更高层：不许盖住它的圆点
                _limit = min(_limit, 2 * (_d - CARD_CLEAR))
            elif band[_f["id"]] == _b:               # 同层：不许与它重叠
                _limit = min(_limit, 2 * _d - CARD_W - 2 * CARD_CLEAR)
        # [M11] 不许伸出纸外，但**卡片不必以圆点为中心左右对称** —— 贴边的那张可以整体往
        # 内侧偏。原来写成 min(2×左余量, 2×右余量)，等于要求对称，于是首尾两张被边距
        # 卡回最小宽度：一张图里十一张 124px、首尾两张 110.9px，一眼看得出不齐。
        # 现在按「左余量 + 右余量」算总可用宽度，卡片在其中居中，够不到边就自动内偏。
        _room[_e["id"]] = max(CARD_W, min(CARD_W_MAX, _limit,
                                         (_x - MARGIN_X) + (width - MARGIN_X - _x)))

    # 量化成两档。上一版直接用每张卡各自的余量，结果一张图里出现 111 / 112 / 114 /
    # 143 / 214 五种宽度 —— 差两个像素的两张卡看着就是没对齐，正是「没调好」的样子。
    # 优雅宽度取全图余量的**中位数**再向下取整到 4 的倍数：中位数保证至少一半卡片够宽，
    # 向下取整让它是个整洁的数。够得到的用优雅宽度，够不到的退回最小宽度，不许有第三种。
    # 优雅宽度绑在**这一档自己的最小宽度**上，不由余量决定。
    # 上一版取全图余量的中位数，13 个事项时算出 140px，比最小宽度 111px 宽了 26%，
    # 两种卡片摆在一起差得太多；而未量化那一版更夸张，直接长到 214px。
    # 系数 1.15 是量出来选的：13 到 18 各档都正好多放一个字，而且各档之间不串档
    # （14 的优雅宽度 116px 不会跑成 13 那一档的样子）。
    # 启用门槛 13：12 个及以下最小宽度已有 122px、每行八个字，本来就不小，再放宽没有
    # 意义，只会让同一张图里的卡片参差不齐。所以那些图一律用最小宽度。
    # [M8] 两档宽度：优雅宽度 = 该档最小宽度 × 1.15
    ELEGANT_RATIO = 1.15
    ELEGANT_FROM_N = 13
    if n >= ELEGANT_FROM_N:
        ELEGANT_W = max(CARD_W, min(CARD_W_MAX, int(CARD_W * ELEGANT_RATIO // 4) * 4))
    else:
        ELEGANT_W = int(CARD_W)     # 不放宽，全图统一用最小宽度（取整，免得出现 149.27 这种宽度）
    _elegant = {k: (ELEGANT_W if v >= ELEGANT_W else CARD_W) for k, v in _room.items()}
    CW_ELEGANT = _elegant
    globals()["CW_ELEGANT"] = _elegant       # 供守卫与调用方查验
    globals()["ELEGANT_W"] = ELEGANT_W

    # Snap every x to the half-pixel grid. A 1.4px stroke centred on a fractional
    # x lands across two device pixels on one card and inside one on the next, so
    # the connectors came out visibly uneven in thickness.

    used_up = max([band[e["id"]] for e in evs if side_of(e) == "up"], default=0) + 1
    used_dn = max([band[e["id"]] for e in evs if side_of(e) == "dn"], default=0) + 1

    # A band's distance from the axis is DERIVED from the tallest card in the
    # bands beneath it. Treating it as a small decorative rise was the bug: with
    # a 26px step a two-line card in band 0 reached straight into band 1 and the
    # two collided. Bands are rows, so the step is a row height.
    def band_h(side, b):
        _bw = CW_BY_BAND.get(b, CARD_W)
        hs = [card_h(e, _bw) for e in evs if side_of(e) == side and band[e["id"]] == b]
        return max(hs, default=0)

    def offset(side, b):
        o = CONNECT
        for k in range(b):
            h = band_h(side, k)
            if h:
                o += h + BAND_GAP
        return o

    def reach(ev):
        side = side_of(ev)
        return offset(side, band[ev["id"]]) + card_h(ev)

    up_h = max([reach(e) for e in evs if side_of(e) == "up"], default=CONNECT)
    dn_h = max([reach(e) for e in evs if side_of(e) == "dn"], default=CONNECT)

    # 标题块先算：字号从阶梯往下试，取第一个能在两行内放下的；块高按实际行数算。
    _tfs, _tlines, _tzone = paper.title_fit(m.get("title_text", ""),
                                            width - MARGIN_X * 2, text_w, wrap)
    # 三泳道时图高要算上第三层，否则它画在画布外面（实测图高不变、卡片超出）。
    height = _tzone + up_h + 2 * Rr + dn_h + 58


    axis_y = snap(_tzone + up_h + Rr)

    # **只算不画的出口。**
    # 前端需要确定地知道「会画成什么样」，而此前唯一的办法是我另写一份公式再算一遍 ——
    # 试过，卡宽一个参数就有四处夹取，抄出来的第二份实现差 8 到 14px，而且必然跟着漂。
    # 让渲染器自己把算好的参数交出来，前端拿到的就是**真值**，不存在两份实现。
    # 这里所有的量都是渲染器接下来真正落笔要用的，不是重算的。
    if plan_only:
        return {
            "kind": "编号型", "form": "横向",
            "n": len(evs), "width": width, "height": height,
            "pitch": pitch, "stride": stride,
            # 卡宽要给**实际落笔用的那个**。
            # 落笔时取的是 CW_ELEGANT（[M11] 优雅宽度：按左右余量各自加宽），
            # 只有它没有值时才退回按层的宽度。第一版只返回了按层的宽度，
            # 于是「计划」比「实画」窄 8 到 13px —— 与我另写一份公式时一模一样的差值。
            # 这说明差值从来不在公式，在**出口取错了变量**。
            "card_w": (min(CW_ELEGANT.values()) if CW_ELEGANT else CARD_W),
            # **最小宽度也要交出来。** 落笔用的是优雅宽度，但**图宽是按最小宽度排的**
            # （优雅宽度只把卡片朝空处加宽，不改画布）。少了这一项，验算层那条
            # 「图宽 = 2 边距 + (n-1) 列距 + 卡宽」就只能带一个 30px 的容差 ——
            # 而 30px 的容差放得过一处真的算漏（实测偏差最大 14.5px，正是优雅宽度
            # 减最小宽度之差：13 个事项 124 − 110.9、14 个 116 − 101.5）。
            # 交出这一项之后那条等式变成精确的，容差 0.5px。
            "card_w_base": float(CARD_W),
            "card_w_by_id": dict(CW_ELEGANT),
            "card_w_by_band": dict(CW_BY_BAND),
            "bands_up": used_up, "bands_dn": used_dn, "max_bands": max_bands,
            "axis_y": axis_y, "lanes": len(lanes) if lanes else 0,
            # 横向的 fits_page **恒为真**，因为超纸的组合在这之前就被拒了
            # （试过 16/18/20 个事项配 60 到 80 字，全部在参数阶段抛错）。
            # 留着这个字段是为了四档接口一致，但它在横向不承载信息 ——
            # 说清楚比留一个看起来有用、实际恒真的字段好。
            # 真正判断「装不装得下」的是：调用方拿不到计划（抛错）就是装不下。
            "fits_page": height <= paper.LAND_H,
        }
    # 第三条泳道的起点：下侧那一层的最深处再加一个空隙。按实际占高算，不写死 ——
    # 写死的话下侧卡片一变高就会叠上来。
    # 第三层的起点 = 第二层**实际占到的最深处** + 一个 BAND_GAP。
    # 上一版写成 reach(e) + card_h(e)，而 reach 本身已经含了卡高，等于把卡高算了两次，
    # 图上第二层与第三层之间空出一大片（约 150px）。
    # 判据：reach(e) 就是那张卡的下沿，取最大值即第二层的最深处。

    S = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width:.0f}" height="{height:.0f}" '
         f'viewBox="0 0 {width:.0f} {height:.0f}" font-family="{FONT}">',
         f'<rect data-role="canvas-bg" width="{width:.0f}" height="{height:.0f}" fill="{C["bg"]}"/>']
    _tby = paper.TITLE_PAD_TOP + _tfs
    for _li, _ln in enumerate(_tlines):
        S.append(f'<text data-role="title" x="{width/2:.1f}" '
                 f'y="{_tby + _li * _tfs * paper.TITLE_LH_RATIO:.1f}" '
                 f'font-size="{_tfs}" font-weight="700" font-family="{TITLE_FONT}" '
                 f'fill="{C["ink"]}" stroke="{C["ink"]}" stroke-width="0.3" '
                 f'text-anchor="middle">{esc(_ln)}</text>')

    # the axis: a HAIRLINE, as in v1. Not a thick grey band.
    S.append(rule(MARGIN_X + gut, axis_y, width - MARGIN_X, axis_y, "axis",
                  mark="axis"))

    # Side labels appear IF AND ONLY IF `lanes` is present. With lanes the up/down
    # split carries meaning (原告 / 被告) and must be named. Without lanes the split
    # is plain alternation bought for space, carries no meaning, and must NOT be
    # labelled — a label would assert a distinction the data does not make.
    if lanes:
        # 位置：贴着左边缘，一个在轴上方、一个在轴下方，纵向落在**最短那根竖线的终点**
        # 上，也就是第 0 层卡片贴近轴的那一侧。这样两个标注各自站在自己那一侧的卡片带
        # 里，不占横向空间、不压轴线，也不需要「轴上」「轴下」这种解释词。
        # 前两版都错了：第一版右对齐到一条专门留出的 72px 槽里（轴因此到不了左边缘），
        # 第二版压在轴线上下各 10px（两行字叠在轴上、彼此还挨着）。
        # 最短竖线的终点：轴到第 0 层卡片贴轴那一侧的距离。上方的标注基线落在那里
        # 稍上一点，下方的落在那里稍下一点，两者都贴左边缘。
        # 第三版还错过一次：上方标注写在 _top_end - 6，而 _top_end 已经是卡片下沿，
        # 减 6 之后落在卡片里，被卡片盖住看不见（那张图上「原告主张」整个不见了）。
        # 正确的位置是卡片下沿再往下一点，也就是竖线那一段的中间。
        # 位置定在**短竖线的中线**上：轴与第 0 层卡片之间那一段的正中，两侧对称。
        # 前几版都偏了：贴卡片下沿时看起来像卡片的注脚，贴轴时又压着轴线。
        # [M4] 标注落在短竖线中线，贴左边缘，不占横向
        _top_mid = axis_y - Rr - CONNECT / 2
        _bot_mid = axis_y + Rr + CONNECT / 2
        # 三泳道时要画三个侧标，第三个落在第三层的位置上。
        # 上一版只画两个，图上缺「项目公司」那一条 —— 读者看到三层却只有两个名字。
        # **标注只有两个：上方一个、下方一个。**
        # 泳道只分上下两组 —— 同一方的内容放在一起（甲方归甲方、乙方归乙方）；
        # 某一侧因为排布需要拆成两条横带是几何错开，不改变它仍属同一方，
        # 所以不该为每条带各起一个名字（我曾给三条带三个标注，是错的）。
        # 位置：贴着引线的终点那一带（axis_y ± Rr ± CONNECT/2），不占轴的地盘。
        _up_name = lanes[0].get("label_text", "")
        _dn_name = (lanes[1].get("label_text", "") if len(lanes) > 1 else "")
        # [M4] 侧标签的**横向余量由档位决定**：它贴左边缘起排，右边第一个障碍是首个圆点，
        # 而首圆点 x = MARGIN_X + 最小卡宽/2（六个档位实测全一致）。事项越多卡越窄，
        # 余量越小：4 到 6 项能放 8 字，10 项只剩 5 字，18 项只剩 2 字。
        # 原来是**单行硬画、不量宽度**，于是事项一多标签就横向撞上第一根竖线。
        # 放不下就折**两行**（竖线那一段高 CONNECT=48px，两行需约 26px，够）；
        # 两行仍放不下由 capacity 那一步提前拦住并报出这一档的字数上限 ——
        # 渲染器不截断、不缩字号，与图名那条规矩一致。
        _lbl_avail = max(1.0, (MARGIN_X + CARD_W / 2) - MARGIN_X - 6)

        def _lbl_lines(txt):                          # [C17]
            """侧标签折行：优先一行；放不下折两行（尽量均分，不拆开数字与英文）。"""
            if not txt or text_w(txt, FS_DATE) <= _lbl_avail:
                return [txt] if txt else []
            best = None
            for cut in range(1, len(txt)):
                a, b = txt[:cut], txt[cut:]
                wa, wb = text_w(a, FS_DATE), text_w(b, FS_DATE)
                if wa <= _lbl_avail and wb <= _lbl_avail:
                    score = abs(wa - wb)
                    if best is None or score < best[0]:
                        best = (score, [a, b])
            return best[1] if best else [txt]      # 两行也放不下：照原样画，由上游拦
        for _name, _mid, _up in ((_up_name, _top_mid, True), (_dn_name, _bot_mid, False)):
            _ls = _lbl_lines(_name)
            if not _ls:
                continue
            # 两行时：上方那个往上长（远离轴），下方那个往下长，都不压轴线
            for _k, _ln in enumerate(_ls):
                _dy = (-(len(_ls) - 1 - _k) if _up else _k) * FS_DATE * 1.15
                S.append(f'<text x="{MARGIN_X:.1f}" y="{_mid + FS_DATE * 0.36 + _dy:.1f}" '
                         f'font-size="{FS_DATE}" font-weight="600" fill="{C["note"]}" '
                         f'text-anchor="start">{esc(_ln)}</text>')

    def leader(ev, cx):
        b = band[ev["id"]]
        h = card_h(ev)
        off = offset(side_of(ev), b)
        _sd = side_of(ev)
        if _sd == "up":
            y0, y1 = axis_y - Rr - off, axis_y - Rr
        else:
            y0, y1 = axis_y + Rr, axis_y + Rr + off
        soft = (ev.get("time", {}).get("certainty", "exact") != "exact"
                or str(ev.get("id")) in _conf_ids)
        dash = ' stroke-dasharray="6 4"' if soft else ''
        S.append(rule(cx, y0, cx, y1, "connector", dash=soft))

    def draw(ev, cx):
        b = band[ev["id"]]
        _cw = CW_ELEGANT.get(ev["id"], CW_BY_BAND.get(b, CARD_W))
        # 卡片可以整体往内侧偏，但不许压到页边距上。夹一次即可：贴左边的往右挪，
        # 贴右边的往左挪。之前只放开了对称假设、没夹边距，于是首张左端 49.5px
        # 压进了 56px 的边距里 6.5px。
        cx = min(max(cx, MARGIN_X + _cw / 2), width - MARGIN_X - _cw / 2)
        h = card_h(ev, _cw)
        emph = ev.get("emphasis")
        off = offset(side_of(ev), b)
        _sd2 = side_of(ev)
        if _sd2 == "up":
            top = axis_y - Rr - off - h
        else:
            top = axis_y + Rr + off
        soft = (ev.get("time", {}).get("certainty", "exact") != "exact"
                or str(ev.get("id")) in _conf_ids)
        if emph:
            S.append(f'<rect x="{cx - _cw/2:.1f}" y="{top:.1f}" width="{_cw:.1f}" '
                     f'height="{h}" rx="{RX}" fill="{C["red"]}"/>')
        elif soft:
            # An unfixed date gets a WHITE card with a dashed outline, and its
            # connector is dashed too. A pale tint would have said the same thing
            # on screen and then vanished in 白描, where every fill is flattened
            # to white; a dash survives the monochrome pass, so the distinction
            # still exists in the copy that goes to court.
            S.append(f'<rect x="{cx - _cw/2:.1f}" y="{top:.1f}" width="{_cw:.1f}" '
                     f'height="{h}" rx="{RX}" fill="{C["bg"]}" stroke="{C["card_stroke"]}" '
                     f'stroke-width="1" stroke-dasharray="6 4"/>')
        else:
            # v1 strokes every card that is not the accent one: fill #F3F4F6,
            # border #D6DAE0 at 1px. Only the deep-red card is borderless.
            S.append(f'<rect x="{cx - _cw/2:.1f}" y="{top:.1f}" width="{_cw:.1f}" '
                     f'height="{h}" rx="{RX}" fill="{C["card_fill"]}" '
                     f'stroke="{C["card_stroke"]}" stroke-width="1"/>')
        ty = top + PAD_Y + FS_DATE
        _dls = date_lines(ev, _cw)
        for _i, dt in enumerate(_dls):
            S.append(f'<text x="{cx:.1f}" y="{ty:.1f}" font-size="{FS_DATE}" '
                     f'font-weight="600" fill="{C["white"] if emph else C["ink2"]}" '
                     f'text-anchor="middle">{esc(dt)}</text>')
            ty += FS_DATE + (3 if _i < len(_dls) - 1 else DATE_GAP)
        ty += FS_BODY - FS_DATE
        S.extend(body_lines(card_lines(ev, _cw), cx, _cw, PAD_X, FS_BODY, LH, ty,
                        C["white"] if emph else C["ink"]))

    # Connectors FIRST, cards after. A card sitting in band 0 is crossed by the
    # band 1 and band 2 connectors of its horizontal neighbours — that is not an
    # accident, it is what "nearest free band" means: a card lands in band 1
    # precisely because band 0 was taken at that x. So the connector must pass
    # UNDER the card, which in SVG means drawing it earlier.
    for i, ev in enumerate(evs):
        leader(ev, xof[i])
    for i, ev in enumerate(evs):
        draw(ev, xof[i])

    # 冲突的画法未定：作者没有要求过任何图上记号，我不擅自加。
    # conflicts 字段仍由 validate_map 校验（成员至少两个、都必须画出来、
    # 不许消化掉一方），但**怎么在图上表达分歧，等作者定**。

    # numbered dots last, so nothing overlaps them
    for i, ev in enumerate(evs):
        cx = xof[i]
        # Every dot is SOLID. Certainty is carried by the date text (2025年2月 vs
        # 2025.02.12) and by the index, never by the dot: a row of dots in mixed
        # fills reads as a rack of billiard balls, and 奇川风 allows exactly one
        # meaningful colour.
        fill = C["red"] if ev.get("emphasis") else C["circle"]
        S.append(f'<circle data-role="node" data-id="{ev["id"]}" cx="{cx:.1f}" '
                 f'cy="{axis_y:.1f}" r="{Rr:.1f}" fill="{fill}"/>')
        num_col = C["white"]
        S.append(f'<text x="{cx:.1f}" y="{axis_y + num_base:.1f}" font-size="{fs_num}" '
                 f'font-weight="700" fill="{num_col}" text-anchor="middle">'
                 f'{esc(str(i + 1))}</text>')

    S.append("</svg>")
    open(out_path, "w", encoding="utf-8").write("\n".join(S))
    return int(width), int(height), used_up, used_dn, fits, pitch


if __name__ == "__main__":
    src = sys.argv[1]
    out = sys.argv[2]
    m = json.load(open(src, encoding="utf-8"))
    tw = int(sys.argv[3]) if len(sys.argv) > 3 else None
    w, h, bu, bd, fits, pitch = render(m, out, tw)
    print(f"画布 {w}x{h}   上方 {bu} 层 / 下方 {bd} 层   卡宽 {CARD_W}px   "
          f"列距 {pitch:.0f}px   同层净空 {'够' if fits else '不够'}")