#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前后端的契约：给定材料的骨架，**一次算出整个可行域**。

## 为什么要有它

在此之前，前后端的对接是过程性的：前端问「这一档能装多少字」，后端答一个数；
画不出就退一档再问。后果是同一件事算两遍 —— 前端的判据算出「不该用日期型」，
渲染器又独立拒一次，而两边的判据是分别写的，谁也不保证一致。

这个文件把「能不能画」写成**一组不等式**，前端一次拿到全部形态的可行与不可行及理由，
不必撞回来。判据全部来自渲染器里的真实常数（从模块里 import，不抄字面量）。

## 能数学化的与不能的

**能**：可行性条件。它们本来就是不等式 —— 卡宽不小于日期宽、刻度格数不超上限、
每段在轴上不少于若干像素、页高够不够。这些解一次就得到 n 与 c 的可行区间。

**不能**：折行与座次。折行取决于真实字宽与折行点，公式给的是理论值，
实测上限与它不一样（试过）；座次是装箱问题、卡高还不等长。这两处必须
「量 + 验」，所以本文件只判可行性，具体字数上限仍由 capacity.measured_cap 二分实测。

## 与渲染器的一致性

`tests/run_checks.py` 里有一条常驻守卫：本文件的结论必须与真实渲染逐一致
（可行的画得出、不可行的被拒）。不一致就报错 —— 那说明契约与引擎漂了。
"""
import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
for _p in (HERE, os.path.join(HERE, "..", "..", "mqc-litigation-visual-redraw", "scripts")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import paper                                                       # noqa: E402
import render_multiband as MB                                      # noqa: E402
import render_dated_v2 as D                                        # noqa: E402
import render_spans_v2 as G                                        # noqa: E402
import render_vcolumns as V                                        # noqa: E402
from common import text_w                                          # noqa: E402


def _ymd(s):
    try:
        p = [int(x) for x in str(s).replace("-", "/").split("/")[:3]]
        while len(p) < 3:
            p.append(1)
        return date(*p)
    except Exception:
        return None


# --------------------------------------------------------- 横向（编号型）
def horizontal(n, chars, lanes=2):
    """横向能不能排下 n 个事项、每条 chars 字。

    不等式（常数全部取自 render_multiband）：
      · 卡宽 = (绘图宽 - (n-1)·列距) / n，必须放得下日期那一行
      · 事项数不超过绝对上限 —— 由「卡宽 ≥ 日期宽」解出来，不是拍的
      · 层数不超过 BAND_LADDER 的上限
    """
    W = paper.LAND_W - 2 * MB.MARGIN_X
    need_date = text_w("2024.12.31", MB.FS_DATE) + 2 * MB.PAD_X
    # 解 n 的上限。**判据是「列距 × 2 ≥ 日期宽」，不是「列距 ≥ 日期宽」** ——
    # 卡片上下交替，同侧相邻的两张隔着**两个**列距，所以每张卡实际可用的横向空间
    # 是列距的两倍。
    # 我第一版按「列距 ≥ 日期宽」解，算出上限 10，而实测是 21，差了一倍多 ——
    # 前端会因此白白转纵向。按两倍推出的分界正好是 21 到 22（21 个时同侧间距
    # 91px、恰好等于日期宽；22 个时 87px、不足），与实测完全吻合。
    n_by_width = int(2 * W // need_date)
    # 高度那一条：字越多卡越高，每侧能叠的层就越少，能装的事项跟着降。
    # 实测（逐点扫，双泳道）：4 字 24 个、10 字 24、20 字 23、30 字 20、40 字 17 ——
    # **上限不是一个常数，是一条随字数下降的曲线**，由宽度与高度两条约束取小。
    # 我先按单一常数写（先 10、后 21），两次都与实测不符；21 那次是因为我早先量的
    # 那个数受了别的因素影响，真实的宽度上限是 24。
    # 高度那一条我先按「卡高 → 每侧几层 → 每层放几个」推，35 组里有 4 组与实测不符
    # （30 字 17 个、30 字 20 个、40 字 17 个实际画得出，我判成不行）。
    # 系数猜不准，就**不猜** —— 改用实测表，表里的数字是逐点扫出来的。
    # 这是这个项目反复得到的同一条经验：折行与排布受真实字宽牵制，
    # 能算的算、算不准的量，不要用估出来的系数冒充公式。
    #
    # 实测（双泳道，A4 横版内）：
    _MEASURED = ((4, 24), (10, 24), (20, 23), (30, 20), (40, 17), (60, 13))
    n_by_height = _MEASURED[-1][1]
    for _c, _top in _MEASURED:
        if chars <= _c:
            n_by_height = _top
            break
    n_max = min(n_by_width + 3, n_by_height)
    ok = n <= n_max
    why = ""
    if not ok:
        why = (f"{n} 个事项在横向排不开：绘图宽 {W}px 分成 {n} 份只有 "
               f"{W / n:.0f}px，而日期那一行要 {need_date:.0f}px；"
               f"这一档（每条 {chars} 字）横向最多 {n_max} 个 —— "
               f"宽度允许 {n_by_width + 3} 个、高度允许 {n_by_height} 个，取小")
    return {"form": "编号型·横向", "ok": ok, "why": why,
            "n_max": n_max, "card_w": min(MB.CARD_W_MAX, W / max(n, 1))}


# ----------------------------------------------------------------- 纵向
def vertical(n, chars, lanes=2, cols=None):
    """纵向能不能排下。纵向**没有事项数上限**（超一页就分页），所以恒可行；
    返回的是要几页、每列多宽、每行几个字 —— 那才是前端要知道的。

    **这些数问引擎，不自己算。** 原来这里是一份手写的算术（自己取 `_card_w(1)`、
    自己算卡高与步距、自己除出每页几个），也就是这个项目明令不许有的第二份实现。
    它漂了，而且漂在最要紧的三档：30 / 60 / 90 个事项时契约说 2 / 3 / 4 页，
    引擎实际画 1 / 2 / 3 页 —— 差的正是列数（契约恒按一列算，引擎会按 [P24] 选两列
    去省一页）。前端照契约的数决定「要不要分页、要写多少字」，于是每一处都错。
    横向那边早就定了规矩（能不能画可以闭式，画成什么样必须问引擎），纵向这里漏掉了，
    因为**没有一条守卫盯过纵向的数**。现在补上了。

    探测一次约 11ms（实测 24 组），换来的是永远一致。
    """
    import copy
    events = []
    for i in range(n):
        e = {"id": str(i + 1), "head": "字" * max(1, chars), "unit_type": "fact",
             "source": {"file": "骨架", "locator": f"句{i}"},
             "time": {"certainty": "exact", "origin": "extracted", "kind": "occur",
                      "raw": "骨架", "date": f"20{20 + i // 12}/{i % 12 + 1}/{i % 27 + 1}",
                      "date_text": "x"}}
        if lanes >= 2:
            e["lane"] = "P" if i % 2 == 0 else "D"
        events.append(e)
    probe = {"schema_version": 2, "layout": "vertical_two_columns",
             "title_text": "图名草稿", "events": events}
    if lanes >= 2:
        probe["lanes"] = [{"id": "P", "label_text": "原告主张"},
                          {"id": "D", "label_text": "被告主张"}]
    try:
        plan = V.render(copy.deepcopy(probe), None, plan_only=True)
    except Exception as exc:
        # 纵向的约定是恒可行（超一页就分页），所以走到这里说明约定被破了。
        # 实测扫过 24 组（8 到 120 个事项 × 18 / 30 / 42 字）零拒绝，一次也没走到。
        # 但不许把它吞掉：装不下必须表现为说不行，不能返回一个画不出来的计划。
        return {"form": "编号型·纵向", "ok": False,
                "why": f"纵向本应恒可行，而引擎拒绝了：{str(exc).splitlines()[0]}"}
    pages = plan["pages"]
    why = ""
    if cols is not None and plan.get("cols") not in (None, cols):
        # 列数由引擎按页数选（[P24]：页数相同时取列少的，卡片更宽），不由调用方指定。
        # 不许静默忽略调用方给的值 —— 说出来，否则他会以为自己指定生效了。
        why = (f"列数由引擎按页数选，实为每侧 {plan.get('cols')} 列"
               f"（调用方给的 {cols} 列未采用）")
    return {"form": "编号型·纵向", "ok": True, "why": why,
            "pages": pages,
            # 每页几个是**从引擎的页数反推的平均值**，不是另算一套容量模型。
            "per_page": max(1, -(-n // pages)),
            "card_w": plan["card_w"], "per_line": plan["per_line"],
            "cols": plan.get("cols")}


# --------------------------------------------------------------- 日期型
def dated(dates):
    """日期型能不能画。三条不等式，常数全部取自 render_dated_v2。

    · 时点数在 3 到 CELLS_MAX 之间（少于三个没有距离可比；多于上限刻度成栅栏）
    · 相邻时点在轴上 ≥ NEIGHBOUR_MIN_PX
    · **同侧相邻卡（隔一个）在轴上 ≥ SAME_SIDE_MIN_PX** —— 真正卡住它的那一条
    """
    ds = sorted(d for d in (_ymd(x) for x in dates) if d)
    if len(ds) != len(dates):
        return {"form": "日期型", "ok": False,
                "why": "有时点没有精确日期，日期型要求全部精确"}
    if len(ds) < 3:
        return {"form": "日期型", "ok": False,
                "why": f"只有 {len(ds)} 个时点，少于三个没有距离可比"}
    if len(ds) > D.CELLS_MAX:
        return {"form": "日期型", "ok": False,
                "why": f"{len(ds)} 个时点，超过刻度格数上限 {D.CELLS_MAX}"}
    span_days = (ds[-1] - ds[0]).days or 1
    plot = paper.LAND_W - D.LEFT - D.RIGHT
    px_per_day = plot / span_days
    segs = [(ds[i + 1] - ds[i]).days for i in range(len(ds) - 1)]
    bad_near = [(i, s) for i, s in enumerate(segs)
                if s * px_per_day < D.NEIGHBOUR_MIN_PX]
    bad_same = [(i, segs[i] + segs[i + 1]) for i in range(len(segs) - 1)
                if (segs[i] + segs[i + 1]) * px_per_day < D.SAME_SIDE_MIN_PX]
    if bad_near:
        i, s = bad_near[0]
        return {"form": "日期型", "ok": False,
                "why": f"第 {i + 1}、{i + 2} 个时点相隔 {s} 天，按比例只有 "
                       f"{s * px_per_day:.0f}px，不足 {D.NEIGHBOUR_MIN_PX}px"}
    if bad_same:
        i, s = bad_same[0]
        return {"form": "日期型", "ok": False,
                "why": f"第 {i + 1}、{i + 2} 段合计 {s} 天，按比例只有 "
                       f"{s * px_per_day:.0f}px，而同侧相邻两卡要 "
                       f"{D.SAME_SIDE_MIN_PX}px"}
    # 刻度格数**不自己算，直接问渲染器**。
    # 它有一整套「按年/月/周铺格 + 断代折叠」的算法（折叠掉空白年格再数），
    # 我在契约里重写过一版，两处与渲染器不符 —— 这正是那条反复吃到的教训：
    # 同一个东西的第二种算法要复用第一种，不要重写。
    # 代价是这一档要试渲染一次（毫秒级，且只在日期型这一条路上），
    # 换来的是**永远一致**：契约与引擎用的是同一段代码。
    try:
        import copy as _cp
        _probe = {"schema_version": 2, "layout": "dated_point_timeline",
                  "title_text": "探测", "events": [
                      {"id": str(_i + 1), "head": "事项", "unit_type": "fact",
                       "source": {"file": "x", "locator": "x"},
                       "time": {"certainty": "exact", "origin": "extracted",
                                "kind": "occur", "raw": "x",
                                "date": _d.strftime("%Y/%m/%d"),
                                "date_text": _d.strftime("%Y.%m.%d")}}
                      for _i, _d in enumerate(ds)]}
        D.render(_cp.deepcopy(_probe))
    except Exception as _exc:
        return {"form": "日期型", "ok": False, "why": str(_exc).splitlines()[0]}
    return {"form": "日期型", "ok": True, "why": "",
            "px_per_day": px_per_day, "cells": len(ds)}


# --------------------------------------------------------------- 期间型
def spans(items):
    """期间型能不能画。items 为 [{"from":…, "to":…}]。

    · 至少两段（论点是重叠关系）且存在重叠或包含
    · 段数不超过 8
    · 每段在轴上 ≥ 6px（条太窄标签进不去，见 P16）
    """
    iv = []
    for s in items:
        a, b = _ymd(s.get("from")), _ymd(s.get("to"))
        if not (a and b):
            return {"form": "期间型", "ok": False,
                    "why": "有期间缺起止时间，那不是期间而是一个时点"}
        iv.append((a, b))
    if len(iv) < 2:
        return {"form": "期间型", "ok": False,
                "why": "只有一段期间，没有重叠可言"}
    if len(iv) > 8:
        return {"form": "期间型", "ok": False,
                "why": f"{len(iv)} 段期间，超过 8 段的上限"}
    overlap = any(a[0] < b[1] and b[0] < a[1]
                  for i, a in enumerate(iv) for b in iv[i + 1:])
    if not overlap:
        return {"form": "期间型", "ok": False,
                "why": "各段互不重叠也互不包含，不比编号型多说什么"}
    lo = min(a for a, _b in iv)
    hi = max(b for _a, b in iv)
    total = (hi - lo).days or 1
    plot = paper.LAND_W - G.LEFT - G.RIGHT
    px_per_day = plot / total
    thin = [(i, (b - a).days) for i, (a, b) in enumerate(iv)
            if (b - a).days * px_per_day < 6]
    if thin:
        i, dd = thin[0]
        return {"form": "期间型", "ok": False,
                "why": f"第 {i + 1} 段只有 {dd} 天，按比例 "
                       f"{dd * px_per_day:.1f}px，条身细到看不见（下限 6px）"}
    return {"form": "期间型", "ok": True, "why": "",
            "px_per_day": px_per_day, "n_spans": len(iv)}


# ------------------------------------------------------------------ 汇总
def feasible(dates=None, chars=20, lanes=2, span_items=None):
    """**一次算出整个可行域。** 这是前端唯一需要调的函数。

    返回 {"可行": [...], "不可行": [{form, why}], "推荐": form}
    推荐的次序：期间型 → 日期型 → 横向 → 纵向（前两种有语义前提，
    后两种是排布，横向优先因为卡片更宽）。
    """
    dates = dates or []
    n = len(span_items or dates)
    out, bad = [], []
    for r in ([spans(span_items)] if span_items else []) + \
             ([dated(dates)] if dates and all(dates) else []) + \
             [horizontal(n, chars, lanes), vertical(n, chars, lanes)]:
        (out if r["ok"] else bad).append(r)
    return {"可行": out, "不可行": bad,
            "推荐": (out[0]["form"] if out else None)}


if __name__ == "__main__":
    ds = sys.argv[1:] or ["2018/4/10", "2020/4/10", "2021/9/1", "2023/2/15",
                          "2024/6/20"]
    r = feasible(ds, chars=20)
    print(f"骨架：{len(ds)} 个时点，每条 20 字")
    for x in r["可行"]:
        extra = {k: v for k, v in x.items() if k not in ("form", "ok", "why")}
        print(f"  可行  {x['form']}　{extra}")
    for x in r["不可行"]:
        print(f"  不行  {x['form']}：{x['why']}")
    print(f"  推荐：{r['推荐']}")


# ============================================================================
# 映射函数（layout_horizontal）已**删除**。
#
# 它曾在这里，用闭式解卡宽与列距。删掉的理由是数据：同一批 12 组样本，
# 公式平均差实画 7.4px、只有 3 组准；而渲染器的 plan_only 出口平均差 0.0px、12 组全准。
#
# 差值不是偶然误差，是系统性的 —— 公式复现不了「优雅宽度」（[M11] 按左右余量各自加宽）
# 那一步，而那一步在渲染器里是最后落笔前才算的。我为了找这 8 到 13px 查了两轮，
# 先后怀疑列距下限、日期折行下限、min_card，三次都不是。
#
# 结论写成规矩：**要知道会画成什么样，问渲染器（plan_only=True），不要另写一份公式。**
# 四个渲染器都有这个出口，返回的是接下来真正落笔要用的量；
# tests 里有守卫盯着「计划必须等于实画」，三种漂移方式都验过会被抓住。
#
# 数学模型留在下面的判定函数里（horizontal / vertical / dated / spans）：
# 它们只回答**能不能画**（一组不等式），不回答**画成什么样**。
# 前者可以闭式，后者必须问引擎 —— 这是这一轮最要紧的一条分界。


# ==================================================== 第二层：不等式验算
"""**数学模型在这里当证明，不当第二份实现。**

前面已经定过一条分界：「能不能画」可以闭式，「画成什么样」必须问引擎。
这一节是那条分界的另一半 —— 引擎交出参数之后，用一组**恒成立的关系**验算它。

它能抓住的是逐字段比对抓不住的那类错：参数**自相矛盾**。
比如卡宽变宽了而列距没变（那么卡片必然重叠）、层数超过声明的上限、
图宽与「两边距 + 列距总和 + 一个卡宽」不符（那说明有一处尺寸算漏了）。

每条规则都在真实参数上筛过（24 组），有反例的不收。
被筛掉的一条值得记：`cw ≤ 2p - 2·CARD_CLEAR - 2` 在 13 个事项以上不成立 ——
因为优雅宽度（[M11]）会在那之后把卡片加宽。**看起来天经地义的关系也要先筛。**
"""


def verify_horizontal(plan):
    """验算横向的参数。返回违反的规则列表（空表示全部通过）。"""
    bad = []
    n, cw, pit = plan["n"], plan["card_w"], plan["pitch"]
    if cw > MB.CARD_W_MAX + 0.5:
        bad.append(f"卡宽 {cw:.1f} 超过上限 {MB.CARD_W_MAX}")
    # 「卡宽不小于日期宽」这一条**撤掉了**：我凭感觉写了 56，而 22 个事项 4 字时
    # 真实卡宽是 55.7（渲染器另有分支允许它低于 date_floor 的 57.6）。
    # 也就是说这条规则本身有反例 —— 按我定的规矩「有反例的不收」，撤。
    # 教训：验算规则也不许凭感觉定数，必须先在真实参数上筛过。
    # 我在筛那一步只跑了 24 组、恰好没覆盖 22 个事项这一档，所以漏掉了这个反例。
    if pit < MB.COL_PITCH_MIN - 0.5:
        bad.append(f"列距 {pit:.1f} 小于下限 {MB.COL_PITCH_MIN}")
    # 图宽那条等式**要用最小宽度**，不是落笔的优雅宽度：优雅宽度只把卡片朝空处加宽，
    # 画布并不跟着变。原来用 card_w 写，于是不得不带 30px 容差才不误报 ——
    # 而 30px 放得过一处真的算漏（实测最大偏差 14.5px，恰好等于两档宽度之差）。
    # 现在渲染器把 card_w_base 一起交出来，等式是精确的，容差 0.5px。
    # 缺这一项就直接报：宁可报「验不了」，不要退回一个验不出东西的宽容差。
    if "card_w_base" not in plan:
        bad.append("计划没有交出 card_w_base（最小宽度），图宽这一条无法验算")
    else:
        _w_expect = 2 * MB.MARGIN_X + (n - 1) * pit + plan["card_w_base"]
        if abs(plan["width"] - _w_expect) > 0.5:
            bad.append(f"图宽 {plan['width']} 与「2 边距 + (n-1) 列距 + 最小卡宽」"
                       f"= {_w_expect:.1f} 不符，某处尺寸算漏了")
        # 顺手想加的一条「最小宽度不许大于落笔宽度」**有反例，撤掉**：
        # 12 个事项时最小宽度 121.7、落笔 121.0 —— 因为不启用优雅宽度那一档
        # 渲染器会把宽度取整（免得图上出现 149.27 这种宽度）。
        # 按这个项目定的规矩，有反例的规则不收，哪怕它看起来天经地义。
    if not (0 < plan["axis_y"] < plan["height"]):
        bad.append(f"轴的位置 {plan['axis_y']:.0f} 落在图外（图高 {plan['height']:.0f}）")
    if max(plan["bands_up"], plan["bands_dn"]) > plan["max_bands"]:
        bad.append(f"层数 {plan['bands_up']}/{plan['bands_dn']} 超过上限 "
                   f"{plan['max_bands']}")
    if plan["height"] > paper.LAND_H + 0.5:
        bad.append(f"图高 {plan['height']:.0f} 超过纸高 {paper.LAND_H}")
    # 面积不等式：全部卡片的横向总宽不许超过「绘图宽 × 层数」，
    # 否则同层必然重叠。这一条抓的是「卡宽与层数不匹配」。
    _room = (paper.LAND_W - 2 * MB.MARGIN_X) * (plan["bands_up"] + plan["bands_dn"])
    if cw * n > _room + 1:
        bad.append(f"卡宽 {cw:.0f} × {n} 个 = {cw * n:.0f} 超过可用横向总量 "
                   f"{_room:.0f}（绘图宽 × 层数），同层必然重叠")
    return bad


def verify_vertical(plan):
    """验算纵向的参数。"""
    bad = []
    if plan["cols"] > V.MAX_COLS:
        bad.append(f"每侧 {plan['cols']} 列，超过上限 {V.MAX_COLS}")
    if plan["card_w"] > V._card_w(1) + 0.5:
        bad.append(f"卡宽 {plan['card_w']:.0f} 超过单列时的宽度 {V._card_w(1):.0f}")
    if plan["width"] != paper.PORT_W:
        bad.append(f"图宽 {plan['width']} 不等于竖版宽度 {paper.PORT_W}")
    _pages_expect = max(1, int((plan["height"] - 1) // V.PAGE_H) + 1)
    if plan["pages"] != _pages_expect:
        bad.append(f"页数 {plan['pages']} 与图高 {plan['height']:.0f} 不符"
                   f"（按一页 {V.PAGE_H} 算应为 {_pages_expect}）")
    return bad


def verify_dated(plan):
    """验算日期型的参数。"""
    bad = []
    if plan["cells"] > D.CELLS_MAX:
        bad.append(f"刻度 {plan['cells']} 格，超过上限 {D.CELLS_MAX}")
    _min_px = {"year": D.YEAR_MIN_PX, "month": D.MONTH_MIN_PX,
               "week": D.WEEK_MIN_PX}.get(plan["unit"], 0)
    if plan["px_per_cell"] < _min_px - 0.5:
        bad.append(f"每格 {plan['px_per_cell']:.0f}px，不足 {plan['unit']} "
                   f"刻度的下限 {_min_px}px")
    if plan["card_w"] != D.CARD_W:
        bad.append(f"卡宽 {plan['card_w']} 不等于日期型的固定卡宽 {D.CARD_W}")
    return bad


def verify_spans(plan):
    """验算期间型的参数。"""
    bad = []
    if plan["row_h"] > G.ROW_H_MAX + 0.5:
        bad.append(f"行距 {plan['row_h']:.0f} 超过上限 {G.ROW_H_MAX}")
    if plan["row_h"] < plan["bar_h"]:
        bad.append(f"行距 {plan['row_h']:.0f} 小于条高 {plan['bar_h']}，条会叠在一起")
    if plan["px_per_day"] <= 0:
        bad.append("每天的像素数不是正数，轴的比例算错了")
    return bad


def verify(plan):
    """按 plan 的种类挑对应的验算规则。四档共用这一个入口。"""
    key = (plan.get("kind"), plan.get("form"))
    if key == ("编号型", "横向"):
        return verify_horizontal(plan)
    if key == ("编号型", "纵向"):
        return verify_vertical(plan)
    if plan.get("kind") == "日期型":
        return verify_dated(plan)
    if plan.get("kind") == "期间型":
        return verify_spans(plan)
    return [f"不认识的种类 {key}"]
