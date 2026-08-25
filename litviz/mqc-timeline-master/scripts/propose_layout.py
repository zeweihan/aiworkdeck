#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Propose a figure type from the SHAPE of the extracted data.

Every threshold in here was measured during development against real material,
not chosen. Where a number appears, the comment says what it came from.

What this does and does not decide
----------------------------------
It reads counts, dates and overlaps. It never reads meaning. Whether this case
turns on a limitation period or on the order of events is a legal reading and
belongs to the lawyer; the router only reports which instruments the data can
support and which it cannot, with the arithmetic for each.

That split is why the output is phrased as a reading to correct rather than a
menu to pick from, and why exclusions carry their reason: offering a
date-proportional axis for events that all fall in one week is offering
something that cannot be delivered.

Ranking
-------
1. 期间型   spans that overlap, at least one of them a period of fact
2. 日期型   point events, few, well separated, at least one long gap
3. 编号型   everything else — the default, because the spacing promises nothing
            and therefore can never mislead

Within 编号型 the arrangement (horizontal bands / vertical columns / paginated)
follows from the count and the paper, and is not offered as a choice.
"""
import json, os, sys
from datetime import date


def _d(s):
    return date(*map(int, s.replace("-", "/").split("/")))


def _events(m):
    return m.get("events", [])


def _exact(m):
    return [e for e in _events(m)
            if e.get("time", {}).get("certainty") == "exact" and e["time"].get("date")]


def analyse(m):
    ev, ex = _events(m), _exact(m)
    sp = m.get("spans", [])
    lanes = m.get("lanes", [])
    out = {"events": len(ev), "exact": len(ex), "spans": len(sp),
           "lanes": len(lanes), "notes": []}

    if ex:
        ds = sorted(_d(e["time"]["date"]) for e in ex)
        out["span_days"] = (ds[-1] - ds[0]).days
        gaps = [(ds[i + 1] - ds[i]).days for i in range(len(ds) - 1)]
        out["gaps"] = gaps
        out["tight"] = sum(1 for g in gaps if g <= 3)
        # Predict the renderer's collision test the way the renderer does it:
        # the plot is as wide as the RULER needs (v1 gives each year 118px, each
        # month 66px), not a fixed 900. Approximating it as a share of the span
        # missed four events two days apart — each gap was a third of a six-day
        # span, which looks generous until you notice the whole axis is one
        # month wide and the three points sit inside 66 pixels.
        _MIN = 20.0
        _months = max(1, (ds[-1].year - ds[0].year) * 12 + ds[-1].month - ds[0].month)
        _plot = (max(1, ds[-1].year - ds[0].year + 1) * 118.0
                 if _months > 24 else _months * 66.0)
        out["plot_px"] = _plot
        out["crowded"] = [(i, i + 1, g) for i, g in enumerate(gaps)
                          if out["span_days"] and
                          g / out["span_days"] * _plot < _MIN]
        # The binding constraint is not the dots, it is the CARDS: they alternate
        # above and below, so two events two apart share a side and must clear
        # 214+16px. A pair 29 days apart on a four-month axis fails that while
        # the dots look comfortably separated.
        _CARD = 230.0
        out["card_clash"] = [(i, i + 2, (sum(gaps[i:i + 2])))
                             for i in range(len(gaps) - 1)
                             if out["span_days"] and
                             sum(gaps[i:i + 2]) / out["span_days"] * _plot < _CARD]
        # "A gap that carries the argument" is a RELATIVE thing. Requiring a
        # year excluded the clearest case there is — a delay case whose whole
        # span is 323 days and whose seven-month overrun is the point. What
        # matters is that one interval dominates the chronology, not that it
        # crosses some absolute length.
        # "One interval dominates" means it stands out from the OTHERS, not that
        # it passes a fixed share. Four events spaced evenly give every gap a
        # third of the span and would have sailed through a share test, while
        # having no dominant interval at all — the spacing there says nothing a
        # numbered axis does not say more cheaply.
        _srt = sorted(gaps, reverse=True)
        _rest = _srt[1:] or [0]
        out["long_gaps"] = (1 if _srt and _srt[0] >= 2.5 * (sum(_rest) / len(_rest))
                            else 0)
        out["empty_share"] = (sum(sorted(gaps, reverse=True)[:3]) / out["span_days"]
                              if out["span_days"] else 0.0)
    else:
        out.update(span_days=0, gaps=[], tight=0, long_gaps=0,
                   empty_share=0.0, crowded=[], card_clash=[])

    if sp:
        def ov(a, b):
            return a["from"] < b["to"] and b["from"] < a["to"]
        out["overlaps"] = sum(1 for i in range(len(sp)) for j in range(i + 1, len(sp))
                              if ov(sp[i], sp[j]))
        out["factual_spans"] = sum(1 for s in sp if s.get("unit_type") != "stipulated")
    else:
        out["overlaps"] = out["factual_spans"] = 0

    if lanes:
        from collections import Counter
        c = Counter(e.get("lane") for e in ev)
        out["per_lane"] = dict(c)
        out["max_side"] = max(c.values(), default=0)
    else:
        out["per_lane"], out["max_side"] = {}, 0
    return out


def propose(m):
    a = analyse(m)
    cand, ruled = [], []

    # ---- 期间型 ----------------------------------------------------------
    # Earns its place only when the bars' overlap IS the argument. Eight spans
    # is v1's own example ceiling; past that the overlaps stop being readable.
    if a["spans"]:
        if a["factual_spans"] == 0:        # [P9] 至少一段是已发生的事实
            ruled.append(("期间型", "全部期间都是约定条款，没有一段是已发生的事实"))
        elif a["overlaps"] == 0:           # [P9] 且至少两段重叠或包含
            ruled.append(("期间型", "没有任何两段期间重叠或包含，条形只是在罗列时长"))
        elif a["spans"] > 8:               # [P8] 段数上限
            ruled.append(("期间型", f"{a['spans']} 段期间超过 8 段，重叠关系已看不清"))
        else:
            cand.append(("期间型", "proportional_gantt",
                         f"{a['spans']} 段期间，其中 {a['overlaps']} 对重叠或包含，"
                         f"{a['factual_spans']} 段为已发生的事实"))
    else:
        ruled.append(("期间型", "地图中没有 spans，只有时点"))

    # ---- 日期型 ----------------------------------------------------------
    # Measured across eight real datasets: none of them fitted. Litigation
    # events cluster into a few days, and a proportional axis cannot separate
    # days. The three conditions below are what the one near-fit satisfied.
    if not _events(m):
        pass
    elif a["exact"] != a["events"]:
        ruled.append(("日期型", f"{a['events'] - a['exact']} 个事件没有精确日期，"
                                f"比例轴会宣称一个材料没有的精度"))
    elif a["crowded"]:
        # Match the renderer's own test rather than a separate rule of thumb: it
        # refuses when two points land within 20px of each other on the plotted
        # axis, so the router must predict exactly that, or it recommends a form
        # that then refuses to draw.
        n_bad = len(a["crowded"])
        g = a["crowded"][0][2]
        ruled.append(("日期型", f"有 {n_bad} 对相邻事件在按比例的轴上几乎重合"
                                f"（最近的一对相隔 {g} 天），比例轴分不开它们"))
    elif a["card_clash"]:
        n_bad = len(a["card_clash"])
        ruled.append(("日期型", f"有 {n_bad} 处同侧相邻卡片按比例放不下"
                                f"（最近的一处两段合计 {a['card_clash'][0][2]} 天）"))
    elif a["events"] > 8:
        # Eight is the ceiling on one axis; past that the cards crowd whatever
        # the dates say.
        ruled.append(("日期型", f"{a['events']} 个时点超过 8 个，一根轴放不下"))
    elif a["long_gaps"] == 0:
        ruled.append(("日期型", "各段间隔长短相近，没有一段明显突出，"
                                "距离说不出比顺序更多的东西"))
    else:
        cand.append(("日期型", "dated_point_timeline",
                     f"{a['events']} 个时点全部有精确日期，最短间隔 "
                     f"{min(a['gaps'])} 天，其中一段间隔明显长于其余，距离本身在说话"))

    # ---- 编号型 ----------------------------------------------------------
    # Always available. The spacing carries no claim, so it cannot be wrong.
    if _events(m):
        n = a["events"]
        # 排布不再查表。原来这里按事件数查一张阈值表（十三个以内两方对读就说横向
        # 多层），而渲染器是真的去试排；画布从 1177 收到 A4 横版的 993px 之后两者
        # 当场分家，路由器推荐横向、渲染器紧接着拒绝画它。这是最糟的那种不一致：
        # 用户拿到的是一句自相矛盾的建议。
        # 现在调同一个入口试排一次，报出来的就是接下来真会画出来的那一张。
        import render_figure
        form, why = render_figure.predict(m)
        arr = f"{form}（{why.rstrip('。')}）"
        cand.append(("编号型", "numbered_point_timeline",
                     f"{n} 个事件；排布：{arr}"))
    return a, cand, ruled


def checkpoint_text(m):
    a, cand, ruled = propose(m)
    L = ["① 结构 ─────────────────────────────"]
    L.append(f"   读到　{a['events']} 个事件"
             + (f" · {a['spans']} 段期间" if a["spans"] else "")
             + (f" · {a['lanes']} 方主张" if a["lanes"] else ""))
    if a["exact"] and a["span_days"]:
        L.append(f"　　　　跨度 {a['span_days']} 天 · 精确日期 {a['exact']}/{a['events']}"
                 f" · 最短相邻间隔 {min(a['gaps']) if a['gaps'] else 0} 天")
    if cand:
        n, _, why = cand[0]
        L.append(f"   建议　{n}")
        L.append(f"　　　　{why}")
        for n2, _, why2 in cand[1:]:
            L.append(f"   备选　{n2}　{why2}")
    for n3, why3 in ruled:
        L.append(f"   排除　{n3}　{why3}")
    L.append("   ▸ 图种由数据决定，不是口味。上面每一条都是算出来的，请核对而非挑选。")
    return "\n".join(L)


if __name__ == "__main__":
    for f in sys.argv[1:]:
        m = json.load(open(f, encoding="utf-8"))
        print(f"\n===== {os.path.basename(f)} =====")
        print(checkpoint_text(m))
