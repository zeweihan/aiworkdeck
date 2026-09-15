#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Hand-written validator for the v2 semantic map. Zero third-party deps, same
convention as v1's common.validate_map (jsonschema stays an optional extra).

Two contracts, both enforced here:
  1. A v1 map (schema_version 1) passes with ZERO v2 errors. v2 is additive.
  2. Every rule agreed during design is a check that CAN fail. Break the map and
     the matching check must fire — that is the only kind of rule worth having.

Usage:  python3 validate_v2.py <map.json> [<map.json> ...]
        exit 0 = every map clean
"""
import json, re, sys, os

# 破折号、连接号、各式引号一律禁止；书名号《》与括号不在此列
FORBIDDEN = {
    "\u2014": "破折号", "\u2013": "连接号", "\u2015": "破折号",
    '"': "直双引号", "'": "直单引号",
    "\u2018": "弯单引号", "\u2019": "弯单引号",
    "\u201c": "弯双引号", "\u201d": "弯双引号",
    "\u300c": "直角引号", "\u300d": "直角引号",
    "\u300e": "直角引号", "\u300f": "直角引号",
}

# 阶段与受众的有效组合。空的格子不是理论的瑕疵，是落地时必须拦住的错误：
# 案子还没立就不可能呈报法庭；已经结案分享就不该再交当事人。
VALID_FRAME = {
    "intake":    {"party", "internal"},
    "filed":     {"court", "party", "internal"},
    "exchanged": {"court", "party", "internal"},
    "complete":  {"court", "party", "internal", "peers"},
}

PROPORTIONAL = {"dated_point_timeline", "proportional_gantt"}
V2_LAYOUTS = {"vertical_single_column"}


def check(path):
    m = json.load(open(path, encoding="utf-8"))
    E, W = [], []
    sv = m.get("schema_version")
    layout = m.get("layout", "")

    # ---- 对 v1 与 v2 都适用的基本盘 ----
    if sv not in (1, 2):
        E.append(f'schema_version 应为 1 或 2，实为 {sv!r}')
    if not m.get("title_text"):
        E.append("缺少 title_text")
    if layout in V2_LAYOUTS and sv != 2:
        E.append(f'布局 {layout} 是 v2 的，但 schema_version 为 {sv}')

    if sv != 2:
        return E, W      # v1 地图到此为止：v2 的规矩一条都不套在它头上

    events = m.get("events", [])

    sc = m.get("scope")

    # ---- 卡片结构：一个标题行 + 一个正文块，二者不得并存 ----
    for e in events:
        i = e.get("id", "?")
        if not e.get("head"):
            E.append(f"事件 {i}: v2 要求 head（唯一的标题行）")
        if e.get("body") and e.get("items"):
            E.append(f"事件 {i}: body 与 items 不得并存")
        if not e.get("body") and not e.get("items"):
            W.append(f"事件 {i}: 无正文块，卡片只有标题行")

    # ---- 禁用标点：破折号与引号 ----
    for e in events:
        i = e.get("id", "?")
        fields = [("head", e.get("head", "")), ("body", e.get("body", "")),
                  ("date_text", (e.get("time") or {}).get("date_text", ""))]
        fields += [(f"items[{k}]", it) for k, it in enumerate(e.get("items", []))]
        for name, txt in fields:
            for ch, label in FORBIDDEN.items():
                if ch in (txt or ""):
                    E.append(f"事件 {i}.{name}: 含{label} {ch!r}")

    # ---- 时间：确定度、来源、锚点 ----
    ids = {e.get("id") for e in events}
    for e in events:
        i = e.get("id", "?")
        t = e.get("time")
        if not t:
            E.append(f"事件 {i}: v2 要求 time 块")
            continue
        c = t.get("certainty")
        if c not in ("exact", "range", "relative", "order"):
            E.append(f"事件 {i}: certainty 非法 {c!r}")
        if not t.get("raw"):
            E.append(f"事件 {i}: time.raw 必填，须为材料中的时间表述原文")
        if t.get("origin") == "computed":
            E.append(f"事件 {i}: v2 不存在 computed，法律期间计算属于另一个 skill")
        if c == "exact" and not t.get("date"):
            E.append(f"事件 {i}: certainty=exact 必须给出 date")
        if c == "range" and not (t.get("from") and t.get("to")):
            E.append(f"事件 {i}: certainty=range 必须给出 from 与 to")
        if c in ("relative", "order"):
            a = t.get("anchor")
            if a and a not in ids:
                E.append(f"事件 {i}: anchor 指向不存在的事件 {a!r}")
            if c == "relative" and not a:
                E.append(f"事件 {i}: certainty=relative 必须给出 anchor")

    # ---- 硬门禁：比例轴要求全部精确日期 ----
    mode = (m.get("axis") or {}).get("mode", "")
    proportional = layout in PROPORTIONAL or mode.startswith("proportional")
    if proportional:
        soft = [e.get("id") for e in events
                if (e.get("time") or {}).get("certainty") != "exact"]
        if soft:
            E.append(f"比例轴要求全部事件为精确日期，以下不是：{soft}。"
                     f"改用等距编号型，或补齐日期")

    # ---- 审计说明不得上图 ----
    for e in events:
        for k in ("note", "flag", "audit"):
            if k in e:
                E.append(f"事件 {e.get('id','?')}: 审计说明只能写在 index_note，不得用 {k}")

    # ---- stipulated 不得混进 events ----
    for e in events:
        if e.get("unit_type") == "stipulated":
            E.append(f"事件 {e.get('id','?')}: unit_type=stipulated 不进 events，另列 stipulated")

    # ---- quote_depth ----
    for e in events:
        q = e.get("quote_depth", 0)
        if not isinstance(q, int) or not 0 <= q <= 2:
            E.append(f"事件 {e.get('id','?')}: quote_depth 应为 0 到 2，实为 {q!r}")

    # ---- 期间型的启用条件：四条必须同时满足 ----
    # Measured against v1's own example and eight real datasets. A gantt earns
    # its place only when the periods themselves carry the argument; short of
    # that the numbered form says the same thing with less machinery, and the
    # numbered form is the default.
    if layout == "proportional_gantt":
        sp = m.get("spans", [])
        if not sp:
            E.append("期间型必须有 spans；只有时点请改用编号型")
        if len(sp) > 8:
            E.append(f"期间 {len(sp)} 条，超过 8 条后条与条的重叠关系已看不清，请拆图")
        # [P10] 深红只标已发生的事实；约定期间永不取强调色
        real = [x for x in sp if x.get("unit_type") != "stipulated"]
        if sp and not real:
            E.append("全部期间都是约定期间，没有一段是已发生的事实。"
                     "这张图讲的是合同条款而不是案情，请改用编号型或对比表")
        # do any two periods overlap or nest? if none do, the bars are merely a
        # list of durations and a numbered timeline reads better
        def _ov(a, b):
            return a["from"] < b["to"] and b["from"] < a["to"]
        if len(sp) >= 2 and not any(_ov(sp[i], sp[j])
                                    for i in range(len(sp)) for j in range(i + 1, len(sp))):
            W.append("没有任何两段期间重叠或包含。期间型的价值在于重叠本身，"
                     "若只是罗列时长，编号型更清楚")

    # ---- 期间：lane 必须解析得到，约定期间不得标红 ----
    for sp in m.get("spans", []):
        sid = sp.get("id", "?")
        if sp.get("lane") and sp["lane"] not in {l.get("id") for l in m.get("lanes", [])}:
            E.append(f"期间 {sid}: lane {sp['lane']!r} 不在 lanes 中")
        if sp.get("unit_type") == "stipulated" and sp.get("emphasis"):
            E.append(f"期间 {sid}: 约定期间不得使用强调色。"
                     f"深红只标已发生的事实，约定的窗口不是事实")
        if sp.get("from") and sp.get("to") and sp["from"] > sp["to"]:
            E.append(f"期间 {sid}: 起始晚于结束")

    # ---- 泳道 ----
    # lanes name the two opposing sides, if the material has them. Banding is the
    # renderer's business and is never declared here, so there is no layout value
    # to check — only that every lane reference resolves and that there are at
    # most two sides, because an axis has two sides.
    lanes = m.get("lanes", [])
    if lanes:
        if len(lanes) > 2:
            E.append(f"lanes 最多两条（轴只有上下两侧），实为 {len(lanes)} 条")
        lids = {l.get("id") for l in lanes}
        for e in events:
            if e.get("lane") and e["lane"] not in lids:
                E.append(f"事件 {e.get('id','?')}: lane {e['lane']!r} 不在 lanes 中")

    # ---- 分组：上限 7，且必须覆盖全部事件，一个不多一个不少 ----
    groups = m.get("groups", [])
    if groups:
        if len(groups) > 7:
            E.append(f"候选事项上限 7 组，实为 {len(groups)} 组")
        covered, dup = set(), []
        for g in groups:
            for eid in g.get("event_ids", []):
                if eid in covered:
                    dup.append(eid)
                covered.add(eid)
        if dup:
            E.append(f"分组重复覆盖事件 {sorted(set(dup))}")
        missing = ids - covered
        extra = covered - ids
        if missing:
            E.append(f"分组未覆盖事件 {sorted(missing)}，分组可以钝但不能漏")
        if extra:
            E.append(f"分组引用了不存在的事件 {sorted(extra)}")

    # ---- 选择的来源：模型不得自己挑 ----
    if sc:
        if sc.get("selected_groups") and sc.get("selection_source") != "user":
            E.append("scope.selected_groups 已填，但 selection_source 不是 user。"
                     "模型不得代替用户挑选取材范围")
        if sc.get("mode") == "targeted":
            if not (sc.get("sentences_total") and sc.get("sentences_read")):
                E.append("定向抽取必须记录 sentences_total 与 sentences_read，"
                         "否则无法在交付时声明未读范围")
            elif sc["sentences_read"] >= sc["sentences_total"]:
                W.append("mode=targeted 但已读句数不小于总句数，是否应为 full")




    # ---- 短标题必须与完整标题同源 ----
    # head_short 是给横向形态用的缩写。它必须是 head 的子序列：逐字按序取自
    # head，只许删不许改。这一条同时挡住两件事：改写，以及把短标题挂到别的
    # 事件上去。后者更危险，因为图看起来完全正常。
    for e in events:
        hs, hd = e.get("head_short"), e.get("head", "")
        if not hs:
            continue
        if len(hs) > 10:
            E.append(f"事件 {e.get('id','?')}: head_short 超过 10 字（{len(hs)} 字）")
        i = 0
        for ch in hs:
            i = hd.find(ch, i)
            if i < 0:
                E.append(f"事件 {e.get('id','?')}: head_short {hs!r} 不是 head 的子序列，"
                         f"要么被改写，要么挂错了事件")
                break
            i += 1

    # ---- 时序：地图必须已按日期非递减排列 ----
    # 渲染器不排序，校验器只报告。自动排序会把材料自身的时序矛盾藏起来，
    # 而那种矛盾往往正是要给律师看的东西。
    def _key(e):
        t = e.get("time") or {}
        return t.get("date") or t.get("from")

    seq = [(e.get("id"), _key(e)) for e in events if _key(e)]
    def _ymd(v):
        try:
            a = [int(x) for x in str(v).replace("-", "/").split("/")]
            return (a[0], a[1], a[2])
        except Exception:
            return None
    prev_id, prev = None, None
    for eid, v in seq:
        cur = _ymd(v)
        if cur is None:
            E.append(f"事件 {eid}: 日期 {v!r} 无法解析")
            continue
        if prev and cur < prev:
            E.append(f"事件 {eid} ({v}) 排在事件 {prev_id} 之后，但日期更早。"
                     f"地图须按日期排列；若材料本身时序矛盾，"
                     f"请照日期排列并在 index_note 中说明")
        prev_id, prev = eid, cur

    # ---- 图像来源：必须有名分，而且不许混进离群基准 ----
    img = [e.get("id") for e in events
           if ((e.get("source") or {}).get("medium") == "image")]
    if img:
        if not sc:
            E.append(f"有 {len(img)} 个事件出自图像识别，但没有 scope 块可供声明")
        else:
            n = sc.get("events_from_image")
            if n != len(img):
                E.append(f"scope.events_from_image 记为 {n!r}，实际图像来源事件 {len(img)} 个。"
                         f"交付时必须如实声明哪些事件未经逐字核验")
            if not sc.get("documents_unreadable"):
                E.append("有图像来源事件，但 scope.documents_unreadable 为空："
                         "哪几份材料没有文字层必须列出")
        for e in events:
            src = e.get("source") or {}
            if src.get("medium") == "image" and src.get("quote"):
                W.append(f"事件 {e.get('id','?')}: 图像来源却带 quote，"
                         f"该引文无法对磁盘文本逐字核验，只能作为转述")

    # [C6] 图名必须放得进标题块；超容量在出图前拦住，不截断
    # 标题块的规矩：字号可降但有下限（17px），最多两行。降到下限仍放不下，就是超容量，
    # 必须在出图之前拦住并要求改短 —— 渲染器不做截断，图上不许出现省略号。
    _tt = m.get("title_text", "")
    if _tt:
        # 不用 try 把异常吞掉：第一版写了 except Exception: pass，而 os 没导入，
        # 于是这条检查静默失效，三种长度的图名全部「通过」，看起来像检查过了。
        # 吞异常的检查比没有检查更坏。
        # common 在 v1 的 scripts 里，校验器默认不在那条路径上 —— 上一版因此抛
        # ModuleNotFoundError，而我的测试脚本又没看 stderr，于是三种长度全显示「通过」。
        # 两个疏漏叠在一起，结果是「检查存在、但从未运行过，且看起来是过的」。
        import importlib.util as _tu
        import os as _tos
        import sys as _tsys
        _here = _tos.path.dirname(_tos.path.abspath(__file__))
        for _pth in (_here, _tos.path.join(_here, "..", "..",
                                           "mqc-litigation-visual-redraw", "scripts")):
            if _pth not in _tsys.path:
                _tsys.path.insert(0, _pth)
        if True:
            _tsp = _tu.spec_from_file_location("paper", _tos.path.join(_here, "paper.py"))
            _tp = _tu.module_from_spec(_tsp)
            _tsp.loader.exec_module(_tp)
            from common import text_w as _tw, wrap as _twrap
            # 按最窄的那一档（纵版）算，因为形态是自动选的，事前不知道会走横还是竖。
            _avail = _tp.PORT_W - 48
            _fs, _lines, _zone = _tp.title_fit(_tt, _avail, _tw, _twrap)
            if len("".join(_lines)) < len(_tt):
                _cap = [c for c in _tp.title_capacity(_avail, _tw) if c[0] == _fs]
                _lim = _cap[0][2] if _cap else 0
                E.append(f"图名 {len(_tt)} 字，降到最小字号 {_fs}px 两行仍放不下"
                         f"（上限约 {_lim} 字）。请改短图名，不要指望截断")

    # ---- 冲突：两种，都必须画出来，不许消化掉 ----
    for c in m.get("conflicts", []):
        cid = c.get("id", "?")
        if c.get("kind") not in ("date", "characterization"):
            E.append(f"冲突 {cid}: kind 应为 date 或 characterization，实为 {c.get('kind')!r}")
        mem = c.get("members", [])
        if len(mem) < 2:
            E.append(f"冲突 {cid}: 至少两个成员，只有一个就不是冲突")
        gone = [x for x in mem if x not in ids]
        if gone:
            E.append(f"冲突 {cid}: 成员 {gone} 不在 events 中。"
                     f"冲突的任一方被删掉，等于替用户和解了争点")

    # ---- 三维坐标的有效组合 ----
    fr = m.get("frame")
    if fr:
        st, au = fr.get("stage"), fr.get("audience")
        if st == "auto" and not fr.get("stage_inferred_from"):
            E.append("frame.stage=auto 必须记录 stage_inferred_from（推定依据）")
        if st in VALID_FRAME and au and au not in VALID_FRAME[st]:
            E.append(f"阶段 {st} 与受众 {au} 是空组合：该阶段不可能面向该受众")

    # ---- 未确认即草稿 ----
    cp = m.get("checkpoint") or {}
    if not cp.get("confirmed") or not cp.get("extraction_confirmed"):
        W.append("checkpoint 未全部确认，产物应命名为 *-draft.*")

    return E, W


def main(paths):
    bad = 0
    for p in paths:
        E, W = check(p)
        tag = "FAIL" if E else "ok  "
        print(f"[{tag}] {os.path.basename(p)}   错误 {len(E)} / 告警 {len(W)}")
        for x in E:
            print(f"        错误  {x}")
        for x in W:
            print(f"        告警  {x}")
        bad += bool(E)
    print(f"\n{len(paths) - bad}/{len(paths)} 份地图通过")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
