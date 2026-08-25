#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Shared geometry helpers for the timeline renderers.

Belongs in the plugin-wide kernel once v1 is unfrozen; kept here for now so the
two renderers share ONE copy rather than two that drift.
"""


# ---------------------------------------------------------------- 文字间隙
# 相邻两处文字之间至少要留的空气，按字号的倍数算。
# 这个数必须只有一个。渲染器判「刻度标签放不放得下」原来用的是「标签宽 + 8px」，而
# 守卫要求 0.8 倍字号即 9.6px；8 < 9.6，于是有一档从渲染器溜过去、被守卫抓住 ——
# 同一件事有两个数，就一定会在某个宽度上打起来。渲染器与守卫现在都读这一个。
TEXT_CLEAR = 0.8


# ---------------------------------------------------------------- 线的角色
# 粗细与颜色曾经是散在十四个调用点上的字面值，于是同一种线在不同渲染器里长得不一样：
# 同一个软灰有 #C3C9D2 和 #C6CBD2 两个常数（差三个数值，眼睛分不出，但就是两个东西），
# 同一根引线在三个渲染器里分别是 1、1.4、2 三种粗细，而没有任何地方写明为什么。
# 「虚线比实线粗」那一类错也是这么来的：每个调用点各写一次，就一定会写出不一样的。
#
# 所以线不再由调用方描述外观，只声明**角色**；粗细与颜色在这张表里定一次。想加一种
# 新外观，就得先给它起个名字、写进这张表，而不是在某一行里随手打个数字。
# 守卫两条：渲染器源码里不许出现 rule() 以外的画线方式，产物里每条线的
# （颜色, 粗细）必须是这张表里的一对。
# **粗细只有一个数（发丝 1.2px），层次全部靠颜色。**
# 原来 tick 是 1.0、connector 是 1.4，两者又同色（line_soft），并排画在一张图上时
# 读者看到的是「有的粗有的细」而不是「刻度与引线」——作者反复指出的「竖线粗细不一致」
# 就是这 0.4px。0.4px 的差别在纸上既分不出层次、又看得出不齐，两头都不落好。
# 现在：刻度用更浅的 grid 色、引线与轴用 line_soft、强调用深红，粗细一律 1.2。
# 唯一的例外是期间型的刻度基线（axis_rule）：它是全图唯一的深色线，1.5px 是它的身份，
# 与其它线不会被误读为同一类。
HAIRLINE = 1.2    # [C11]
ROLES = {
    "axis":      ("line_soft", HAIRLINE),   # 发丝轴（编号型横向 / 纵向）
    "axis_rule": ("line",      1.5),        # 刻度基线（期间型），唯一的深色线
    # [D13] 刻度线用**与纸同色的白**，粗细不变（HAIRLINE）。
    # 经过：原来给的是 grid（#ECEEF1），与时间带本身（#D1D5DB）几乎同色，
    # 画了 13 条线而图上一条看不见 —— 等于没有刻度尺。
    # 照 v1 的规范先改成深灰（#6B7280，v1 写的是 #737373），刻度显出来了；
    # 作者看过之后定成**白色**：视觉上时间带像一段段拼接起来，比深灰的分隔线更干净。
    # 关键是「不能粗」—— 白线只做断口，不做装饰，所以宽度仍用 HAIRLINE。
    "tick":      ("bg",        HAIRLINE),   # 色带上的刻度线：与纸同色的断口
    "grid":      ("grid",      HAIRLINE),   # 年格线
    "connector": ("line_soft", HAIRLINE),   # 轴到卡片的引线
    "locator":   ("line_soft", HAIRLINE),   # 时点定位竖线（虚）
    "accent":    ("red",       HAIRLINE),   # 强调，唯一带含义的颜色
    # [P21] 两条竖线靠到 24px 以内时，后一条换成这一档深色，好让读者分得清哪条是哪个
    # 日期；配套的标注也换同色。深红留给「强调」，所以这里用深一档的中性灰。
    "edge_near": ("ink2",      HAIRLINE),
}


def rule(x1, y1, x2, y2, role, dash=False, mark=""):
    """按角色画一条直线。颜色与粗细来自 ROLES，调用方不许自带。

    mark 会写成 data-role，给守卫认元素用；调用方因此不必再去拼接字符串（拼接会
    被「源码里不许出现 <line」那条守卫抓住，而它抓得对：拼接就是绕过这张表的入口）。
    """
    if role not in ROLES:
        raise KeyError(f"未定义的线角色 {role!r}；先写进 geom.ROLES 再用")
    from common import C, DASH        # 色板只有一份，就是 v1 的那份
    tok, w = ROLES[role]
    out = hairline(x1, y1, x2, y2, C[tok], w, DASH if dash else "")
    if mark:
        out = out.replace("<line ", f'<line data-role="{mark}" ', 1)
    return out


def role_pairs():
    """(颜色, 粗细) 的合法集合，给守卫用。"""
    from common import C
    return {(C[t].upper(), w) for t, w in ROLES.values()}


def snap_axis(v, role="locator"):   # [C12] 判定与落笔共用一份吸附
    """按某个角色的线宽算出这条线最终落笔的坐标。

    绘制时 hairline 会把坐标吸到像素网格上（奇数宽吸半像素、偶数宽吸整数）。凡是
    「按位置做的判断」都必须用这个函数先算一遍，否则判定与落笔用的是两个数：
    期间型曾自己写了一份「四舍五入到半像素」，与这里的规则不一致，于是同一批端点被
    吸成 84.0 / 84.5 / 85.0 / 85.5 四个位置，靠近判定把本该一浅一深的两条都标成深色，
    穷举 1260 组里报出 45 组「只隔 1px 同色」。
    """
    _tok, w = ROLES.get(role, ROLES["locator"])
    return (int(v) + 0.5) if round(w) % 2 else float(round(v))


def hairline(x1, y1, x2, y2, colour, w=1.4, dash=""):
    """The ONLY way a straight rule gets drawn.

    # 虚线的规矩：**同粗、同色，只是虚。**
    # 一条虚线的分量不许超过它所变化的那条实线。原来不确定事实的卡框是 1.4px 虚线，
    # 而确定事实的卡框是 1px 实线 —— 于是越不确定的卡画得越重，正好反了；期间型的时点
    # 竖线更用了 ink2 这种正文灰，比连线的 line_soft 深两档，一排竖线比期间条本身还抢眼。
    # 虚这件事本身已经把区别说清楚了，再加粗加深就是把「存疑」画成了「重点」。


    A stroke renders at even thickness only when its centre sits on a half pixel
    for odd widths, or on an integer for even ones. Remembering that at every
    call site does not work: the vertical renderer shipped eight unaligned rules
    and the horizontal one shipped two before that, each time visible as
    connectors of visibly different weight. So the snapping lives here and
    nothing else emits a <line>. A regression guard asserts that.
    """
    def snap(v, width):
        return (int(v) + 0.5) if round(width) % 2 else float(round(v))

    if abs(x1 - x2) < 1e-6:
        x1 = x2 = snap(x1, w)
    if abs(y1 - y2) < 1e-6:
        y1 = y2 = snap(y1, w)
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return (f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
            f'stroke="{colour}" stroke-width="{w}"{d}/>')


# ---------------------------------------------------------------- dot model
# One formula, both orientations. The radius follows the SPACING between
# adjacent dots, so it adapts instead of being looked up in a hand-tuned table
# per layout. Everything else is a fixed ratio of the radius, so the three
# numbers can never drift apart:
#
#     2R <= OCCUPANCY x spacing        the beads may fill this much of the gap
#     font  = R x NUM_RATIO            numeral scales with the bead
#     base  = font x NUM_BASE          baseline offset keeps it optically centred
#
# OCCUPANCY is the only judgement call in here. At 0.45 a dense axis gets small
# beads and an open one gets the full size, which is what the eye wants; raising
# it makes a dense axis read as a chain, lowering it makes an open axis look
# under-marked.
R_MAX, R_MIN = 13.0, 8.0
OCCUPANCY = 0.45
NUM_RATIO = 1.05          # slightly under a 2-digit numeral's comfortable fit
# 0.36 is the standard optical centre for digits, which have no descender.
#
# Measured residual after snapping both centres to the half-pixel grid: within
# 0.5px on the horizontal form, up to 1.1px on the vertical one at radius 9.6.
# The residual does NOT respond to this constant — sweeping it from 0.34 to 0.40
# changed nothing — so it comes from rounding the font size to an integer and
# from a two-digit numeral nearly filling a small bead, not from the baseline.
# Chasing it further is not worth the time it has already cost; a guard below
# holds the line at 1.5px so it cannot silently get worse.
NUM_BASE = 0.36


def dot_metrics(spacing):
    """spacing -> (radius, numeral font size, baseline offset from centre)."""
    r = max(R_MIN, min(R_MAX, spacing * OCCUPANCY / 2))
    fs = round(r * NUM_RATIO)
    return r, fs, fs * NUM_BASE


def snap_centre(v):
    """Put a dot centre on the half-pixel grid.

    The horizontal renderer snapped its axis and the vertical one did not, so the
    two orientations rendered their numerals against centres in different pixel
    phases and no single baseline ratio could centre both. Snap the centre and
    the ratio becomes a property of the font alone.
    """
    return int(v) + 0.5


# ------------------------------------------------------------ CJK wrapping
import re as _re  # noqa: E402

# 禁则的两张表，与 common.wrap 用的是同一套（STANDARDS §4）。
NO_START = "，。、；：！？）】》」』…%"
NO_END = "（【《「『“"

_ATOM = _re.compile(
    r"[0-9]{4}\s*年\s*[0-9]{1,2}\s*月\s*[0-9]{1,2}\s*日"   # 2024年12月1日
    r"|[0-9]{4}\s*年\s*[0-9]{1,2}\s*月"                      # 2024年12月
    r"|[0-9]{4}\s*年"                                        # 2024年
    r"|[0-9]+(?:[.．][0-9]+)*\s*(?:%|万元|万|元|个|日|月|天)?")   # 485000元 / 65.5万元


def wrap_atomic(text, fs, max_w, wrap_fn, text_w_fn):
    """Wrap CJK text without ever splitting a number from itself or its unit.

    common.wrap breaks per character and honours 禁则, but it has no notion of a
    number: 「转账4万元及9300元」came out as 「…及9」/「300元」and 「485000元」left
    元 stranded on the next line. Both are unacceptable in a legal figure — a
    split figure reads as a different figure.

    This lived as a private copy inside one renderer, which is exactly the
    drift the shared kernel exists to prevent: the second renderer never got it
    and shipped the same bug again. One copy, here.

    禁則 IS OURS TO KEEP TOO. The first version of this function re-implemented
    the greedy loop and simply dropped 禁则, falling back to wrap_fn only when the
    text held no number at all — so every card containing a figure lost it, and a
    「、」 duly turned up at the head of a line in the vertical figure. That breaks
    the frozen typography standard (STANDARDS §4: a line never begins with a
    closing mark, never ends with an opening one). Fixing a shared mechanism by
    writing a second copy of it is the mistake this file exists to stop, so the
    rules live here once: an atom is never split, and a line never starts with a
    closing mark nor ends with an opening one.
    """
    toks, pos = [], 0
    for m in _ATOM.finditer(text):
        if m.start() > pos:
            toks += list(text[pos:m.start()])
        toks.append(m.group(0))
        pos = m.end()
    toks += list(text[pos:])
    if all(len(t) == 1 for t in toks):
        return wrap_fn(text, fs, max_w)
    out, cur, acc = [], "", 0.0
    for t in toks:
        w = text_w_fn(t, fs)
        if acc + w > max_w and cur:
            # 避头: a closing mark may not open the next line, so let it hang.
            if len(t) == 1 and t in NO_START:
                cur += t
                acc += w
                continue
            # 避尾: an opening mark may not be left stranded at a line end; it
            # travels down with the token that followed it.
            if cur[-1] in NO_END:
                opener = cur[-1]
                cur = cur[:-1]
                if cur:
                    out.append(cur)
                cur, acc = opener + t, text_w_fn(opener, fs) + w
                continue
            out.append(cur)
            cur, acc = t, w
        else:
            cur += t
            acc += w
    if cur:
        out.append(cur)
    return out


# ---------------------------------------------------------------- 正文对齐
#: 正文超过这么多行就改用两端对齐。
#: 一两行时居中好看（卡片像个标签）；三行以上居中会让每行的左右缺口各不相同，
#: 读起来是锯齿状的边。三行以上两端对齐，卡片才像一段正文。
JUSTIFY_FROM_LINES = 3    # [M12] 一两行居中，三行及以上左对齐



#: 两端对齐时**每个字缝最多允许加多少像素**。超过这个数就退回左对齐。
#: 这个数的来历（两个实测的锚点，不是拍的）：
#:   · 作者否掉过一版拉字距，那一版每缝要加 1.8px，他的评价是「肉眼就看出字距被撑开」
#:   · 日期型（卡宽 214、每行 14 到 17 字）实测每缝只要 0.33 到 0.87px；
#:     纵向单列（卡宽 300、每行 24 字）只要 0.18px
#: 所以门槛落在两者之间。1.0px 以下拉开肉眼不可察，1.8px 已被判难看。
#: **它是一条视觉门槛，作者可以改这个数**；改了之后守卫会按新的数重新核。
MAX_GAP_ADD = 1.0


def body_lines(lines, cx, cw, pad_x, fs, lh, y0, fill):
    """画卡片里的正文，返回 SVG 片段列表。三个渲染器共用这一份。

    一两行**居中**（卡片像个标签）；三行及以上**两端对齐**（Ctrl+J 那种：
    除末行外每一行都顶到左右两边）。

    做法：`textLength` 给该行一个精确的目标宽度、`lengthAdjust="spacing"` 只摊字距
    不拉字形。末行不参与，与 Word 的 Ctrl+J 一致。

    **为什么带一个门槛 MAX_GAP_ADD。** 拉字距曾被否过一次，那一次是在横向的窄卡上：
    13 个事项时卡内只有 100px、每行 11 字，残缺摊到字缝里每缝 4.4px；21 个事项时
    每缝 31px，字会散架。而宽卡完全不同：日期型每缝 0.33 到 0.87px、纵向 0.18px，
    肉眼不可察。所以两端对齐不是「行不行」的问题，是**分档**的问题 ——
    每缝要加的量在门槛之内就两端对齐，超出就退回左对齐（左右仍然基本齐平，
    因为折行本来贪心到满行）。
    """
    from common import text_w            # 字宽只有一份口径，就是 v1 的那份
    out = []
    inner = cw - pad_x * 2
    left = cx - inner / 2
    just = len(lines) >= JUSTIFY_FROM_LINES
    ty = y0
    # 拉不拉是**整张卡一起决定**的，不是逐行决定。逐行判会让同一张卡里有的行顶到两边、
    # 有的行短一截，那是同一张图上混着两种样式 —— 作者否过这种做法（期间型的标签
    # 曾经左右混放，改成一律优先右侧）。所以取这张卡里**最挤的那一行**来判：
    # 只要有一行要加的量超过门槛，这张卡整体退回左对齐。
    _adds = [(inner - text_w(l, fs)) / max(1, len(l) - 1) for l in lines[:-1]] or [0]
    _justify_all = just and max(_adds) <= MAX_GAP_ADD and min(_adds) >= 0
    for i, ln in enumerate(lines):
        if not just:
            out.append(f'<text x="{cx:.1f}" y="{ty:.1f}" font-size="{fs}" '
                       f'fill="{fill}" text-anchor="middle">{_esc(ln)}</text>')
            ty += lh
            continue
        # 三行以上：末行左对齐（与 Word 的 Ctrl+J 一致），其余两端对齐
        if _justify_all and i < len(lines) - 1:
            out.append(f'<text x="{left:.1f}" y="{ty:.1f}" font-size="{fs}" '
                       f'fill="{fill}" text-anchor="start" '
                       f'textLength="{inner:.1f}" lengthAdjust="spacing">'
                       f'{_esc(ln)}</text>')
        else:
            out.append(f'<text x="{left:.1f}" y="{ty:.1f}" font-size="{fs}" '
                       f'fill="{fill}" text-anchor="start">{_esc(ln)}</text>')
        ty += lh
    return out


def _esc(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


# ------------------------------------------------------- 非精确日期的排序
def order_by_time(events):
    """把事项排成图上的先后，非精确日期的按 **anchor** 落位。

    四档确定度里只有 exact 自带位置，其余三档要靠别的事项定位：
      exact     有日期，按日期排
      range     有起止，按 from 排（它的不确定是长度，不是位置）
      relative  「此后」「其间」「同年」这类，**紧跟 anchor 之后**
      order     只知道在某几件事之间，按 anchor 之后排；没有 anchor 就按给定顺序

    此前 anchor 是个**死字段**：校验器逼着模型填（certainty=relative 必须给 anchor），
    而没有一个渲染器读过它 —— 实测把 anchor 从「2」改成「4」，图上五个圆点的位置一模一样。
    位置实际由列表顺序决定，anchor 填对填错都没影响。这是最坏的一种情况：它看起来像有
    规则，所以没人会去查；而真材料里「此后」「其间」这类句子很多，一旦模型按语义填了
    anchor、图却按列表顺序画，两者不一致时没有任何人会发现。

    返回重排后的事项列表。稳定：相同位置的保持原有先后。
    """
    def key_of(e):
        t = e.get("time") or {}
        c = t.get("certainty", "exact")
        if c == "exact":
            return t.get("date") or ""
        if c == "range":
            return t.get("from") or ""
        return None                      # relative / order：靠 anchor

    by_id = {e.get("id"): e for e in events}
    fixed = [(i, e) for i, e in enumerate(events) if key_of(e) is not None]
    fixed.sort(key=lambda p: (_ymd(key_of(p[1])), p[0]))
    out = [e for _i, e in fixed]

    # 把靠 anchor 的插到它锚定的那个事项之后；锚不到就按原顺序放到末尾
    floating = [e for e in events if key_of(e) is None]
    for e in floating:
        a = ((e.get("time") or {}).get("anchor"))
        pos = None
        if a and a in by_id:
            for j, x in enumerate(out):
                if x.get("id") == a:
                    pos = j + 1
                    break
        if pos is None:
            out.append(e)
        else:
            # 同一个锚点上挂了好几个时，按它们原来的先后依次排在锚点之后
            while pos < len(out) and ((out[pos].get("time") or {}).get("anchor") == a):
                pos += 1
            out.insert(pos, e)
    return out


def _ymd(s):
    """把 2024/3/5 这类日期变成可比较的元组；空值排最前。"""
    try:
        parts = [int(x) for x in str(s).replace("-", "/").split("/")[:3]]
        while len(parts) < 3:
            parts.append(1)
        return tuple(parts)
    except Exception:
        return (0, 0, 0)
