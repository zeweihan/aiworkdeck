#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""贯通上下游的那条路：材料 → 部分清单 → 勾选 → 出图。

**为什么要有这个文件。** 前面每一次「打通」都是我在临时脚本里手工把各步串起来的，
串法每次不同、不可复现，别人也无从照着走。这里把它定成一条路：
哪几步是代码算的、哪几步要模型答、答完由谁验、验不过怎么退回。

**为什么它不是一个「一键出图」的函数。** 中间有两步必须模型读懂材料才能做（判定每句是不是
事实、把材料划成几个部分），代码做不了；正则切过一版，切出三组同名和半截残句。所以这条路
是分段的：每一段代码做完，把「现在需要模型答什么」明确打出来，模型答完写成 JSON 交回来，
代码验过再走下一段。答错就是一次失败的检查，不是一张错的图。

分段（与 check_model_output 的六个检查器一一对应）：

    stage read      代码   读材料、切句、认叙述块              → state.json
    stage classify  模型   每句是不是已发生的事实，附理由      ← verdicts.json
    stage parts     模型   划成几个部分，每个附事实卡          ← parts.json
    stage offer     代码   生成给律师看的勾选清单
    stage pick      律师   报编号（也可全选、或交给 AI 定）
    stage budget    代码   按勾选算形态与每模块字数容量
    stage extract   模型   按容量写每个事项，声明 src_sids     ← items.json
    stage render    代码   校验 → 出图

用法：
    python pipeline.py read     <材料...>            # 建 state
    python pipeline.py offer                          # 需要 verdicts + parts
    python pipeline.py budget   <勾选的部分编号,...>
    python pipeline.py render   <输出.svg>            # 需要 items
每一步都会打印下一步该做什么，不必记这份说明。
"""
import json
import re
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
for _p in (HERE, os.path.join(HERE, "..", "..", "mqc-litigation-visual-redraw", "scripts")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import read_source as R                                           # noqa: E402
import check_model_output as C                                     # noqa: E402
import capacity as CAP                                             # noqa: E402

STATE = "state.json"


#: 四份要模型填的 JSON，各自的形状与「什么算对」。
#: 固化这件事的起因：每走一遍我都是手工写这四个文件、形状全靠记着 —— 换个人（或下一个
#: 会话的我）照着文档也走不通。所以每一步都要能自己打印出模板与判据。
SHAPES = {
    "verdicts.json": {
        "when": "read 之后",
        "shape": '[{"i": 0, "is_event": false, "why": "材料标题"}, ...]',
        "rules": [
            "每一句都要有一条，一句不漏（i 从 0 到句数减一）",
            "is_event 只在「已发生的具体事实」时为真；诉请、法律评价、"
            "合同约定、材料标题、署名都不是",
            "why 一句话说清为什么，用于返工时定位",
        ],
        "checker": "check_classify",
    },
    "parts.json": {
        "when": "verdicts 之后",
        "shape": '[{"id": 1, "name": "…", "sids": [0,1,2], '
                 '"first": "<首句原文>", "last": "<末句原文>"}]',
        "rules": [
            "每句都落在某个部分里，一句不漏；一句不属于两个部分",
            "部分数不超 8；名字不重复（同名无法勾选）",
            "first / last 照抄原文，不许概括",
            "每个部分里至少有一个事实句 —— 全是约定或主张的部分画不出东西，"
            "应并进相邻部分",
            "有叙述块时不许跨块（判决书的诉称/辩称/查明/理由）",
        ],
        "checker": "check_parts",
    },
    "skeleton.json": {
        "when": "budget 之后，**写正文之前**",
        "shape": '[{"id": "1", "src_sids": [1,2], "certainty": "exact", '
                 '"kind": "sign", "raw": "2023 年 4 月 12 日", "date": "2023/4/12", '
                 '"lane": "D"}]　期间型与 range 用 from / to 代替 date',
        "rules": [
            "只填骨架，不填 head —— 容量要等骨架定了才算得出来",
            # 这三项是 model-steps.md 第五步「时间归档」早就定好的，只是先前没写进这份
            # 契约，于是模型只给一个 date，四档确定度被压成了一个字段 —— 后果实测过：
            # 材料只说「2023 年 11 月」，我给 date=2023/11，编号型静默补成 11 月 1 日，
            # **图上印出了材料里不存在的「2023.11.01」**。而校验器 check_dates 本来就
            # 拦得住（判为 exact 却没有到日的表述），只是没人把骨架交给它。
            "certainty 四档按**材料的精度**填，不按推测：精确到日 exact（给 date）、"
            "只到年月或一段区间 range（给 from 与 to）、只说「次日」「之后」relative"
            "（给 anchor）、只知先后 order。判 exact 就必须有到日的表述",
            "raw 必须是那一句里的时间表述**原文，逐字**——检查会拿它回句子里找，"
            "找不到就报错。这一条挡的是把日期推算出来（「次年初」写成 2017/1/1）",
            "kind 说这个日期钉住哪个动作：occur / sign / effect / send / arrive / "
            "receipt / record / due。发出与到达要分开（催告函的发出日与到达日"
            "在法律上不是一件事）",
            # 图像来源要留名分。规矩见 HANDOVER 5.4：让模型直接读图（不引 OCR 依赖），
            # 但**图像来源的事件单独标记，交付时必须声明**哪几份材料没有文字层、
            # 有几个事件出自读图。schema 与 validate_map 早就完整实现了这条
            # （图像来源事件必须有 scope、数目必须相符、documents_unreadable 必须列出，
            # 不符是错误不是告警），缺的只是**管线组装地图时没把它带上** ——
            # 于是那条守卫在管线产物上永远看不到图像来源事件。
            "medium 只在这条事项**出自读图**时写 \"image\"（截图、扫描件、照片），"
            "默认 text_layer。写了它就必须在 image_docs.json 里列出读了图的材料，"
            "管线据此填 scope，validate_map 会核对数目",
            "src_sids 升序；一个事项跨几句就都写上（同一天的几条聊天记录常要合并）",
            "lane 必须与来源一致（起诉状的句子标 P、答辩状标 D）",
            "三条以上泳道（主体层级 / 程序层级 / 主从关系）：lane 用自定义的 id，"
            "并在 lane_labels.json 里给出每条的名字；默认上二下一，"
            "要改成上一下二就在 lane_sides.json 里写 {\"某条\": \"dn\"}；"
            "每侧最多 3 条、两侧共 6 条是极限",
            "期间型要 from 与 to 都有，且至少两段、存在重叠",
        ],
        "checker": "check_dates / check_lane_source / check_span_worthiness",
    },
    "items.json": {
        "when": "capacity 之后",
        "shape": '在骨架上补 head（与 attrib）：'
                 '[{"id": "1", "src_sids": [1,2], "date": "…", "lane": "D", '
                 '"head": "…", "attrib": "原告称", "emphasis": true}]',
        "rules": [
            "head 必须是 src_sids 那几句拼接后的**子序列** —— 只许删字，"
            "不许改写、换词、加标点",
            "head 不超容量（capacity 那一步给的数）；attrib 占容量，连括号一起算",
            "attrib 不超 5 字，只说明是谁陈述；冲突的两方都要有",
            "emphasis 最多一项（多标就不是重点了），只在奇川风有效",
        ],
        "checker": "check_subsequence / check_capacity",
    },
}


def print_shape(name):
    """打印某一份 JSON 的形状与判据。每一步结束时调用，不让人靠记性。"""
    s = SHAPES[name]
    print(f"下一步要写 {name}（{s['when']}）：")
    print(f"  {s['shape']}")
    for r in s["rules"]:
        print(f"  · {r}")
    print(f"  由 {s['checker']} 校验，不过就报错并指出该改哪一项")


def _load(path, what):
    if not os.path.exists(path):
        print(f"缺 {path}（{what}）。这一步要模型先答，答完写成这个文件再来。")
        sys.exit(2)
    return json.load(open(path, encoding="utf-8"))


def _save(obj, path=STATE):
    json.dump(obj, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


# ------------------------------------------------------------------ stage read
def stage_read(paths):
    """代码做：读材料、切句、认叙述块。多份材料按顺序拼成一条句号序列。"""
    sents, origin, blocks = [], [], {}
    for p in paths:
        r = R.read_with_blocks(p)
        base = len(sents)
        sents += r["sentences"]
        origin += [os.path.basename(p)] * len(r["sentences"])
        for k, (a, b) in (r.get("blocks_sids") or {}).items():
            blocks[f"{os.path.basename(p)}:{k}"] = (a + base, b + base)
    st = {"files": [os.path.basename(p) for p in paths],
          "files_full": list(paths),
          "sentences": sents, "origin": origin, "blocks": blocks}
    _save(st)
    print(f"读入 {len(paths)} 份材料，共 {len(sents)} 句"
          f"{'，认出 %d 个叙述块' % len(blocks) if blocks else '（无叙述块，可能是口述或提示词）'}。")
    print()
    print_shape("verdicts.json")
    print()
    print_shape("parts.json")
    print("然后跑：python pipeline.py pick        # 第一轮：勾材料来源")
    return st


# ----------------------------------------------------------------- stage offer
def _form_hint(part, S, ev):
    """这一段会画成什么图 —— 标在勾选清单里，图种就不必单独问一轮。

    图种是勾选结果决定的：勾「时效抗辩」自然是几个时点跨很多年（日期型），勾「工期与
    停工」自然是几段期间（期间型）。所以不必在勾完之后再问一遍。
    只有两种都可行且都有意义时才标出默认那一种，让他要改时说一声。
    """
    import re as _re
    sids = [s for s in part["sids"] if s in set(ev)]
    txt = "".join(S[s] for s in sids)
    # 期间型要求**至少两段**有起止的法律期间，不是「出现了期间的名字」。
    # 第一版判据是「撞到法律期间词 + 有一个『至』字」，于是「被告的时效抗辩」那一段
    # （只有一句事实、是主张不是期间）被标成期间型 —— 判据太粗，撞词就算。
    # 现在按 check_span_worthiness 的三条来数：要数得出两段以上带起止的期间。
    _pairs = _re.findall(r"(20\d{2})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日"
                         r"[^。；]{0,12}?(?:至|到|起至)\s*(20\d{2})\s*年", txt)
    if len(_pairs) >= 2 and any(k in txt for k in C.LEGAL_PERIODS[:20]):
        return "期间型"
    ds = _re.findall(r"(20\d{2})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日", txt)
    if 3 <= len(ds) <= 8:
        yrs = sorted(int(y) for y, _m, _d in ds)
        if yrs[-1] - yrs[0] >= 3 and any(k in txt for k in C.DATED_ARGUMENTS):
            return "日期型（讲距离）或编号型（讲先后），默认日期型"
    return "编号型"


def stage_pick(paths_or_sel=None):
    """强制交互的**前两轮**：勾材料来源、勾时间段。

    只有这两个维度，因为它们**天然互斥**：一句话只可能出自一份材料、也只可能落在一个
    时间点上。第三个维度「按内容」试过三种判据（法律期间词表、时间成簇、行为词）全部
    做不到互斥 —— 混乱材料里一句「两个月了，验收和付款都没动」同时是验收、付款、催告，
    而勾选要求互斥，否则勾两项会把同一句画两遍。所以舍弃。
    """
    import pick as PK
    st = _load(STATE, "先跑 read")
    if paths_or_sel in (None, "", "show"):
        r1 = PK.round_one(st["files_full"])
        # **只有一个选项时不问，直接进下一环节。**
        # 只投喂一份材料（或只讲了一段话）时，「选哪一份」只有一个答案 ——
        # 问一个只有一个答案的问题是浪费用户的时间。
        # 同理见 round_two 那一轮：材料只覆盖一小段时间线时不问时间段。
        if len(r1) == 1:
            st["pick_r1"] = [{k: v for k, v in r.items() if k != "sentences"}
                             for r in r1]
            st["picked_files"] = [r1[0]["id"]]
            _save(st)
            print(f"只有一份材料（{r1[0]['name']}，{r1[0]['n_sent']} 句，"
                  f"{r1[0]['span']}），跳过第一轮。")
            print()
            rows2, note = PK.round_two(r1, [r1[0]["id"]])
            st["pick_r2"] = rows2
            _save(st)
            _n_dates = sum(r["n"] for r in rows2)
            _ask, _why = PK.span_worth_asking(rows2, _n_dates)
            if not _ask:
                st["picked_spans"] = [r["id"] for r in rows2]
                _save(st)
                print(f"{_why}，不必再选时间段。")
                print()
                print("然后跑：python pipeline.py style 2")
                return
            print(f"第二轮 · 时间段　{note}")
            print()
            for r in rows2:
                print(f"  [{r['id']}] {r['label']}　{r['n']} 个日期")
            print()
            print("  [全部] 全部时间")
            print()
            print("然后跑：python pipeline.py span 全部      （或 span 1,2）")
            return
        st["pick_r1"] = [{k: v for k, v in r.items() if k != "sentences"} for r in r1]
        _save(st)
        print(f"第一轮 · 制作图表的材料来源（{len(r1)} 份）")
        print()
        for r in r1:
            print(f"  [{r['id']}] {r['name']}")
            print(f"      {r['n_sent']} 句，其中 {r['n_dated']} 句带日期　{r['span']}")
            print(f"      首句：{r['first']}")
            print()
        print("  [全部] 全部材料")
        print()
        print("报编号即可（可多选），或报「全部」。然后跑：")
        print("  python pipeline.py pick 1,2      # 勾第 1、2 份")
        print("  python pipeline.py pick 全部")
        return
    # 用户可以直接报「全部」而不先看清单 —— 真跑就是这样崩的（KeyError: pick_r1）。
    # 不能假设用户按我设想的顺序来：每一步都要能独立成立，缺的前置自己补上。
    if "pick_r1" not in st:
        r1 = PK.round_one(st["files_full"])
        st["pick_r1"] = [{k: v for k, v in r.items() if k != "sentences"} for r in r1]
        _save(st)
    sel = ([r["id"] for r in st["pick_r1"]] if paths_or_sel in ("全部", "all")
           else [int(x) for x in paths_or_sel.replace("，", ",").split(",") if x.strip()])
    st["picked_files"] = sel
    r1 = PK.round_one(st["files_full"])
    rows2, note = PK.round_two(r1, sel)
    st["pick_r2"] = rows2
    _save(st)
    print(f"已勾材料 {sel}")
    print()
    # 时间段只有一档时同样不问
    _n_dates = sum(r["n"] for r in rows2)
    _ask, _why = PK.span_worth_asking(rows2, _n_dates)
    if not _ask:
        st["picked_spans"] = [r["id"] for r in rows2]
        _save(st)
        print(f"{_why}，不必再选时间段。")
        print()
        print("然后跑：python pipeline.py style 2")
        return
    print(f"第二轮 · 时间段　{note}")
    print()
    for r in rows2:
        print(f"  [{r['id']}] {r['label']}　{r['n']} 个日期")
    print()
    print("  [全部] 全部时间")
    print()
    print("然后跑：python pipeline.py span 全部      （或 span 1,2）")


def stage_span(sel):
    """第二轮的答案：勾了哪几个时间段。勾完进第三轮。"""
    import pick as PK
    st = _load(STATE, "先跑到 pick")
    rows2 = st.get("pick_r2") or []
    if not rows2:
        # 同理：直接跑 span 时补上第二轮
        r1b = PK.round_one(st["files_full"])
        rows2, _n = PK.round_two(r1b, st.get("picked_files"))
        st["pick_r2"] = rows2
        _save(st)
    ids = ([r["id"] for r in rows2] if sel in ("全部", "all")
           else [int(x) for x in sel.replace("，", ",").split(",") if x.strip()])
    st["picked_spans"] = ids
    _save(st)
    print(f"已勾时间段 {ids}")
    print()
    print("第三轮 · 风格")
    print()
    for s in PK.round_three():
        print(f"  [{s['id']}] {s['name']}" + (f"　{s['tag']}" if s["tag"] else ""))
        print(f"      {s['look']}")
        print(f"      {s['use']}")
        print()
    print("  不回 = 1")
    print()
    print("然后跑：python pipeline.py style 1")


def stage_style(sel):
    """第三轮的答案：呈报给谁 → 风格。奇川风才进第五轮（标红）。"""
    import pick as PK
    st = _load(STATE, "先跑到 span")
    tbl = {s["id"]: s for s in PK.round_three()}
    # 不回默认第一档（奇川风）—— 照 v1 的「不回 = 1」。
    if str(sel).strip() in ("", "默认", "d"):
        chosen = tbl[1]
        print(f"未指定，按默认：{chosen['name']}")
    elif str(sel).strip() == "4":
        # 第四档「让我定」：steps 表与 next 的提示一直写着 `style <1-4>`，
        # 而 round_three() 只有三档，敲 4 会被判成「没有第 4 项」并退出 ——
        # 文档承诺了一个不存在的选项。这里按 v1 的「不回 = 1」落到默认档，
        # 不新增第四种风格，只把已经承诺的那条路接通。
        chosen = tbl[1]
        print(f"由我来定：{chosen['name']}　{chosen['look']}")
        print(f"      {chosen['use']}")
    else:
        chosen = tbl.get(int(sel))
        if not chosen:
            print(f"没有第 {sel} 项，请报 1 到 4（4 = 让我替你定）")
            sys.exit(1)
        print(f"已选：{chosen['name']}　{chosen['look']}")
    st["style"] = chosen["name"]
    _save(st)
    print()
    if chosen["name"] != "奇川风":
        print(f"第五轮跳过（{chosen['name']} 不需要标红：单色或自有视觉规则）")
        print("然后跑：python pipeline.py capacity")
        return
    print("第五轮（标红）在抽取完事项之后问。")
    print("然后跑：python pipeline.py capacity")


def in_scope(st, sid):
    """这一句在不在勾选的范围内。

    前两轮的勾选**必须真的限制后面的抽取**，否则它们只写不读、就是摆设 ——
    与 anchor 那个死字段一模一样的毛病（声明了、校验了、没人用）。
    所以抽取与出图都要过这道筛：句子出自勾中的材料，且它的日期落在勾中的时间段里。
    没有日期的句子跟着它所在的材料走（否则「此后」这类事项会被时间筛掉）。
    """
    import pick as PK
    files = st.get("picked_files")
    spans = st.get("picked_spans")
    origin = (st.get("origin") or [])
    if files:
        rows = st.get("pick_r1") or []
        # **按文件名精确比对，不用短名。** 短名是给人看的（去掉「材料N」前缀、截到十字），
        # 拿它当过筛的键有两处会坏：
        #   ① 同名多份 —— 二十份格式一样的催款函，`pick.name_of` 会编成「催款函 2」
        #      「催款函 3」，而这里算出的键不带编号，于是勾一份等于勾全部；
        #   ② 长名被截断 —— 两份材料前十字相同就分不开。
        # `pick_r1` 每行本来就带 `path`，`origin` 记的是每句出自哪个文件名，
        # 两边取同一个键（basename）就是精确的，不需要任何字符串加工。
        _picked = [r for r in rows if r["id"] in files]
        _nopath = [r.get("name") for r in _picked if not r.get("path")]
        if _nopath:
            # **缺字段不许静默放过。** 第一版写成「allow 为空就不过筛」，于是 pick_r1 里
            # 没有 path 时所有材料都进范围 —— 那是最坏的失效方式：过筛看起来在跑，
            # 实际一个都没筛掉，而且不报错。宁可当场报错，也不要静默失效
            # （与「守卫拿不到材料时不能静默跳过」是同一条）。
            raise ValueError(
                f"勾中的材料 {_nopath} 在 state 的 pick_r1 里没有 path，无法精确过筛。"
                f"pick_r1 由 pipeline.stage_pick 写入、每行都带 path；"
                f"手工拼 state 时要一并给上。")
        allow = {os.path.basename(r["path"]) for r in _picked}
        if allow and sid < len(origin) and origin[sid] not in allow:
            return False
    if spans:
        rows2 = st.get("pick_r2") or []
        labels = {r["label"] for r in rows2 if r["id"] in spans}
        if labels and sid < len(st.get("sentences", [])):
            ds = PK.dates_in(st["sentences"][sid])
            if ds:
                d = ds[0]
                # 判据是**这个日期落在哪一档**（`pick.in_bucket`，与列清单同一个函数），
                # 不是「标签里含不含这个年份」。后者在季度档上等于不过筛：
                # 「2024 年第 1 季度」这个标签里就写着 2024，于是 2024 年任何一天都算命中。
                if any(PK.in_bucket(d, lab) for lab in labels):
                    return True
                return False
    return True


def stage_offer():
    """代码做：验模型的两份输出，然后生成勾选清单。"""
    st = _load(STATE, "先跑 read")
    S = st["sentences"]
    verdicts = _load("verdicts.json", "模型的逐句判定")
    parts = _load("parts.json", "模型的部分划分")

    errs, redo = C.check_classify(S, verdicts)
    if errs:
        print("逐句判定没通过，请就下列句号重答：", redo[:20])
        for e in errs[:5]:
            print("  ", e)
        sys.exit(1)
    ev = [v["i"] for v in verdicts if v.get("is_event")]

    blocks = {k: tuple(v) for k, v in (st.get("blocks") or {}).items()}
    errs, redo = C.check_parts(S, parts, blocks=blocks or None, events=ev)
    if errs:
        print("部分划分没通过，请就下列部分重划：", redo[:20])
        for e in errs[:5]:
            print("  ", e)
        sys.exit(1)

    st["verdicts"], st["parts"], st["events"] = verdicts, parts, ev
    _save(st)
    print(f"读完{'、'.join('《%s》' % f for f in st['files'])}，共 {len(S)} 句，"
          f"其中 {len(ev)} 句是已发生的事实。")
    print("可以整份还原，也可以只取其中几个部分：")
    print()
    for p in parts:
        n_ev = len([s for s in p["sids"] if s in set(ev)])
        print(f"  {p['id']}. {p['name']}　（{_form_hint(p, S, ev)}）")
        print(f"     {len(p['sids'])} 句，其中 {n_ev} 句是事实")
        print(f"     首：{p.get('first', '')}")
        print(f"     末：{p.get('last', '')}")
        print()
    # 图种按**勾选的组合**算，不按单个部分算。
    # 实测：那份答辩状划成六个部分，每部分里各只有一段期间，逐部分判就全是编号型；
    # 而六段一起勾就是期间型（六段工期与停工互相重叠，那正是它的论点）。
    # 所以清单上的标注只是「单独勾这一段时」的图种，多选时要重算 —— 必须说清楚，
    # 否则用户以为勾了六个编号型，出来却是一张期间型。
    _all_form = _form_hint({"sids": [s for p in parts for s in p["sids"]]}, S, ev)
    if _all_form != "编号型":
        print(f"（若全选或多选相关的几段，会合成一张{_all_form}——"
              f"上面标的是「单独勾这一段」时的图种）")
    print("报编号即可，可多选，也可全选，或者让我替你定。")
    # 例子里的编号必须来自**这份清单**：写死一个数时，清单只有一两项就会提示
    # 「budget 4」而第 4 项根本不存在 —— 用户照着敲就会撞错。
    _eg = str(parts[-1].get("id", 1)) if parts else "1"
    print(f"然后跑：python pipeline.py budget {_eg}"
          + ("      （或 budget 1,2 多选 / budget all 全选）" if len(parts) > 1
             else "      （或 budget all）"))


# ---------------------------------------------------------------- stage budget
def _norm_raw(x):
    """归一化时间表述，**直接借 check_model_output 的那把尺子**，不另写一份。
    两边用不同的归一化，就会出现「校验器说查不到、而我这边说查得到」这类矛盾。"""
    import check_model_output as _C
    return _C._norm(x or "")


def stage_capacity():
    # 骨架支持两种：时点（date）与**期间**（from / to）。
    # 这一条是拿真材料撞出来的：那份答辩状讲的全是有长度的期间（总工期、两次停工、
    # 设计变更期间），而骨架原来只有一个 date 字段，只能把期间硬塞成一个时点 ——
    # 于是管道报「编号型」，把「重叠关系是本图的论点」那件事整个丢掉了。
    # 材料的性质决定图种（见约束表第九节），而骨架是唯一表达材料性质的地方，
    # 所以骨架必须能表达期间，否则图种在这一步就已经错了。
    """按**真实的事项骨架**算容量。

    这一步是补出来的，起因是一次真实的错：budget 当时按「勾选到的事实句数」（17 句）
    加「无日期、无泳道」去估，算出每模块 40 字；而真实骨架是 11 个事项、上下分主张方、
    同侧最长连续 3 项，横向只装得下 24 字。我按 40 字写完，横向被拒、自动转了纵向 ——
    而横向本来完全排得下，只是字要短一些。**容量估错的代价不是超容量报错，是形态被换掉。**

    句数不等于事项数（多句可以合成一个事项），有没有泳道、同侧连续几项都影响容量，
    所以容量必须等骨架定了才算，不能按句数估。
    """
    st = _load(STATE, "先跑到 budget")
    sk = _load("skeleton.json", "模型定的事项骨架（只有 id / src_sids / date / lane）")
    # 勾选的范围要真的生效：范围外的事项直接剔掉并报出来，不能默默画进去。
    _drop = [it for it in sk
             if not all(in_scope(st, s) for s in (it.get("src_sids") or []))]
    if _drop:
        sk = [it for it in sk if it not in _drop]
        print(f"按勾选的范围剔掉 {len(_drop)} 个事项："
              f"{[it.get('id') for it in _drop]}")
        if not sk:
            print("剔完没有事项了 —— 勾选的范围里没有可画的内容，请放宽范围。")
            sys.exit(1)
    # ---- 时间归档要过 check_dates（这是接线，不是新判据）---------------------
    # 起因是一次真实的错：材料只说「2023 年 11 月」，我在骨架里给 date=2023/11，
    # **编号型静默把它补成 2023-11-01，图上印出了材料里不存在的那个日**（终端还打印
    # 「同日相反主张：2023年11月1日」）。日期型倒是明确拒绝了（Un-parseable），
    # 而编号型没有人管 —— 图看起来完全正常，这正是这个项目定义的最坏产物。
    #
    # 而机制**本来就有**：model-steps.md 第五步「时间归档」定了四档确定度、raw 必须
    # 逐字可查；check_model_output.check_dates 全部实现了，回归里还有三条改坏测试
    # （时间原文改写 / 无到日却判 exact / range 缺端点）盯着它。缺的只是**没人把骨架
    # 交给它** —— 与 time.anchor 那个死字段、前两轮勾选只写不读是同一类毛病：
    # 声明了、校验器管着、交付路径不读它。
    #
    # 所以这里只做一件事：把骨架按 check_dates 认识的形状交给它。**判据一个字不新写**，
    # certainty / kind / raw 由模型填（那本来就是模型第五步的活），代码不去猜 ——
    # 拿正则从句子里猜时间表述就是第二份判据，必然漂。
    # 一个事项可以跨几句（同一天的几条聊天记录常要合并），而时间表述可能落在其中
    # **任何一句**里 —— 拿 src_sids[0] 去验，合并出来的事项就会被误报「raw 查不到」
    # （实测：付款那一项引了句 10 到 16，日期在末句，而 i 指向首句）。
    # 所以 i 取**raw 真正出现的那一句**；一句都找不到才是真错，交给 check_dates 去报。
    def _host(it):
        _sids = it.get("src_sids") or [0]
        _raw = _norm_raw(it.get("raw", ""))
        for _s in _sids:
            if _raw and _raw in _norm_raw(st["sentences"][_s]):
                return _s
        return _sids[0]
    _tm = [{"i": _host(it), "certainty": it.get("certainty"),
            "kind": it.get("kind"), "raw": it.get("raw", ""),
            **({"date": it["date"]} if it.get("date") else {}),
            **({"from": it["from"], "to": it["to"]}
               if it.get("from") and it.get("to") else {}),
            **({"anchor": it["anchor"]} if it.get("anchor") else {})}
           for it in sk if it.get("certainty")]
    if len(_tm) < len(sk):
        _miss = [it.get("id") for it in sk if not it.get("certainty")]
        print(f"骨架缺 certainty 的事项：{_miss} —— 时间归档是模型第五步的活，"
              f"四档按材料的精度填（见 python pipeline.py shape skeleton.json）。"
              f"只到年月要判 range 并给 from 与 to，不许补成某月 1 日。")
        sys.exit(1)
    _te, _tr = C.check_dates(st["sentences"], _tm)
    if _te:
        for _x in _te[:8]:
            print(f"  时间归档不合：{_x}")
        print("请按材料的精度改正后重跑（判 exact 必须有到日的表述；"
              "只到年月判 range 并给 from 与 to）。")
        sys.exit(1)

    # **期间型的信号不能只看「有没有 from/to」。** 这是接上时间校验之后当场撞出来的：
    # 材料只说「2023 年 11 月」，按 model-steps 第五步要判 certainty=range 并给 from 与
    # to（那是**精度只到月的一个时点**），而这里把它当成了「一段期间」，于是要求
    # 所有事项都给起止、还报「各段互不重叠，应改用编号型」—— 两个语义撞在同一个字段上。
    # 约束表第九节写得很清楚：期间型有意义是因为**那段时间本身有法律后果**
    # （诉讼时效期间、催告后的沉默期间），不是因为有两个端点。
    # 所以判据加一条：certainty 明确写了 range 的，是月精度时点，不算期间。
    has_spans = any(it.get("from") and it.get("to") and it.get("certainty") != "range"
                    for it in sk)
    if has_spans:
        # 期间型要先过「该不该用」这一关（法律含义 + 起止明确 + 存在重叠），
        # 不是「有 from/to 就用」。判据见 check_span_worthiness。
        _se, _sw = C.check_span_worthiness(
            [{"id": it.get("id"), "from": it.get("from"), "to": it.get("to"),
              "label_text": it.get("head") or it.get("label_text", "")} for it in sk])
        for _x in _sw:
            print("  提醒：", _x)
        if _se:
            print("这份材料不该用期间型：")
            for _x in _se:
                print("  ", _x)
            print("请把每段期间的起止写成两个时点，改走编号型。")
            sys.exit(1)
    if has_spans:
        n_span = len([it for it in sk if it.get("from") and it.get("to")])
        if n_span != len(sk):
            print(f"骨架里 {n_span} 个是期间、{len(sk) - n_span} 个是时点 —— "
                  f"期间型的每一行是一段期间，时点走另一条轨（points）。"
                  f"请把时点写成 points 而不是混在 spans 里。")
    # 日期型也要先过「该不该用」这一关，而且**前置判据与渲染器的结论一致**（验过五个
    # 场景全对），所以前端在写骨架时就知道，不必写完字撞回来才发现。
    if not has_spans:
        _ds = [it.get("date") for it in sk if it.get("date")]
        if len(_ds) == len(sk):
            _de, _dw = C.check_dated_worthiness(_ds, st.get("purpose", ""))
            if _de:
                print("这份材料不适合日期型（将走编号型）：")
                for _x in _de[:3]:
                    print("  ", _x)
            for _x in _dw[:2]:
                print("  提醒：", _x)

    dates = [it.get("date") or it.get("from") for it in sk]
    sides = [it.get("lane") for it in sk] if any(it.get("lane") for it in sk) else None
    _spans = ([{"from": it["from"], "to": it["to"]} for it in sk
               if it.get("from") and it.get("to")] if has_spans else None)
    b = CAP.budget(dates, sides, has_spans=has_spans, spans=_spans,
                   title=st.get("title", "图名草稿"))
    st["has_spans"] = has_spans
    if has_spans:
        st["per_span"] = b.get("per_span", [])
    # 三条以上泳道时把名字与侧别读进 state（模型另写两个小文件，缺就不管）
    for _fn, _key in (("lane_labels.json", "lane_labels"),
                      ("lane_sides.json", "lane_sides"),
                      ("lane_relations.json", "lane_relations"),
                      # 读了图的材料清单：[{"file": "…", "reason": "…"}]。
                      # 有 medium=image 的事项时必须给，出图那一步据此填 scope。
                      ("image_docs.json", "image_docs")):
        if os.path.exists(_fn):
            st[_key] = json.load(open(_fn, encoding="utf-8"))
    st["skeleton"] = sk
    st["cap"] = b["cap_per_module"]
    st["cap_title"] = b["cap_title"]
    st["cap_lane_label"] = b.get("cap_lane_label", 0)
    _save(st)
    print(f"骨架：{len(sk)} 个事项"
          f"{'，上下分主张方' if sides else '，单侧'}")
    print("形态与容量：", b["note"])
    if b.get("hint"):
        print("  ", b["hint"])
    print()
    # ---- 第五轮 · 标红哪一项（照抄 v1 的 checkpoint ③）------------------------
    # v1 的 SKILL.md 第 97 行写着：**不要自己选强调 —— 深红重点是用户的决定，
    # 在 CHECKPOINT 那一轮问，默认不标**。而 schema 第 592 行同一条纪律：
    # 「选择必须由用户作出，模型永远不许自己拟定」。
    # v2 先前只有分流（奇川风才问）与跳过的提示，**这一问本身没实现** ——
    # 于是 emphasis 字段实际是模型自己填的，八份示例七份都标了红。这里补上。
    # 白描与歸藏风不问：单色没有红可标，歸藏风有自己的视觉规则。
    if st.get("style") == "奇川风":
        print()
        print("第五轮 · 重点")
        print()
        print("  深红只标一处：本案的胜负手。")
        print()
        print("  [0] 全图不标红")
        # 这一轮在写正文之前问，所以骨架里还没有 head。
        # 用**源句**给用户看内容 —— 只印日期的话，八行日期看不出哪个是胜负手。
        _S5 = st.get("sentences") or []
        for _e in sk:
            _raw = (_e.get("raw") or _e.get("date") or "")
            _txt = _e.get("head") or ""
            if not _txt:
                _sids = _e.get("src_sids") or ([_e["src_sid"]]
                                               if _e.get("src_sid") is not None
                                               else [])
                _txt = "".join(_S5[i] for i in _sids if 0 <= i < len(_S5))
                # 去掉句首那个日期，剩下的才是这件事本身
                _txt = re.sub(r"^[\d〇一二三四五六七八九十]{2,4}\s*年\s*"
                              r"[\d一二三四五六七八九十]{1,2}\s*月"
                              r"(\s*[\d一二三四五六七八九十]{1,3}\s*日)?"
                              r"[，,、\s]*", "", _txt)
            print(f"  [{_e['id']}] {_raw}　{_txt[:26]}")
        print()
        print("  不回 = 由我挑一处并说明理由")
        print()
        print("  然后跑：python pipeline.py mark <编号>      # 0 = 不标红")
        print()

    print("下一步（模型）：按这个容量写正文，写成 items.json（在骨架上补 head）：")
    if has_spans:
        # 期间型不能报一个数：那个数是最短那一段的容量，用它套全图会把长段白白写短。
        # 逐段报，前端才知道哪一段能写长、哪一段只能给两个字或者干脆让它落到条外。
        print("  · **期间型逐段不同**，各段条内可放的字数：")
        for x in (b.get("per_span") or []):
            place = "条内" if x["in_bar"] >= 3 else "太短，标签会落到条右侧或条下方"
            print(f"      {x['id']}　{x['days']:>5} 天　条内 {x['in_bar']:>2} 字　{place}")
        print("    条右侧只放得下一行；条下方有整幅宽度，可以写长。")
    else:
        print(f'  · head 至多 {b["cap_per_module"]} 字，且必须是 src_sids 那几句拼接后的子序列')
    print(f"  · 图名至多 {b['cap_title']} 字：python pipeline.py title '…'")
    print("然后跑：python pipeline.py render 出图.svg")


def _curly(s):
    """把直角引号「」『』换成中文弯引号“”‘’。

    直角引号是日文与港台的习惯，大陆法律文书用弯引号。这件事在图名上尤其显眼 ——
    图名是整张图最大的字，用错了引号一眼就看出来（作者指出的正是这一处）。
    转换放在**写图名这一个出口**，不靠每次手打时记得：靠记性的地方一定会漏。
    """
    return (str(s).replace("「", "“").replace("」", "”")
            .replace("『", "‘").replace("』", "’"))


def stage_mark(sel):
    """第五轮的答案：标红哪一项。0 = 全图不标红。

    **`emphasis_source` 必须如实记**：用户选的记 user，模型代挑的记 model，
    不标的记 none。schema 那条纪律（模型永远不许自己拟定选择）靠这个字段落地 ——
    产物里能看出这一处红是谁决定的。
    """
    st = _load(STATE, "先跑到 capacity")
    if st.get("style") != "奇川风":
        print(f"{st.get('style')} 不标红，这一轮不适用。")
        return
    sk = st.get("skeleton") or []
    if not sk:
        print("还没有骨架，先跑 capacity。")
        sys.exit(1)
    _sel = str(sel).strip()
    if _sel in ("0", "无", "不标"):
        st["emphasis"] = None
        st["emphasis_source"] = "none"
        _save(st)
        print("全图不标红。")
        return
    _ids = {str(e["id"]) for e in sk}
    if _sel not in _ids:
        print(f"没有第 {_sel} 项。可选：0（不标红）或 {sorted(_ids, key=int)}")
        sys.exit(1)
    st["emphasis"] = _sel
    st["emphasis_source"] = "user"
    _save(st)
    _hit = [e for e in sk if str(e["id"]) == _sel][0]
    print(f"标红第 {_sel} 项：{_hit.get('raw') or _hit.get('date')}　"
          f"{(_hit.get('head') or '')[:24]}")
    print()
    print("然后跑：python pipeline.py title '…'")


def stage_title(title):
    """图名由模型写，容量由代码给。单独一步，因为它与事项的容量是两回事。

    第一次跑完整条路时忘了接这一步，出来的图题头是「图名待定」—— 一张图最显眼的地方
    空着，比任何排版问题都刺眼。凡是产物上会出现的东西，管道里都要有一步管它。
    """
    st = _load(STATE, "先跑到 budget")
    title = _curly(title)
    cap = st.get("cap_title") or 0
    if cap and len(title) > cap:
        print(f"图名 {len(title)} 字超过容量 {cap} 字，请改短。")
        sys.exit(1)
    st["title"] = title
    _save(st)
    print(f"图名已定（{len(title)} 字，容量 {cap} 字）：{title}")
    print("然后跑：python pipeline.py render 出图.svg")


def stage_budget(pick):
    """代码做：按勾选的部分算形态与容量。这是前后端对接的那个数。"""
    st = _load(STATE, "先跑 read 与 offer")
    S, ev = st["sentences"], set(st["events"])
    parts = st["parts"]
    ids = ([p["id"] for p in parts] if pick == "all"
           else [int(x) for x in pick.replace("，", ",").split(",") if x.strip()])
    sids = sorted(s for p in parts if p["id"] in ids for s in p["sids"] if s in ev)
    if not sids:
        print("勾选的部分里没有事实句，换一个部分。")
        sys.exit(1)
    st["picked_ids"], st["picked_sids"] = ids, sids
    _save(st)
    print(f"勾选了部分 {ids}，其中 {len(sids)} 句是事实：")
    for s in sids:
        print(f"  [{s}] {S[s][:60]}")
    print()
    print()
    print(f"这 {len(sids)} 句事实要合成几个事项、每个属于哪一方，是模型的判断 —— "
          f"**容量只有等这个骨架定了才算得出来**。")
    print_shape("skeleton.json")
    print("然后跑：python pipeline.py capacity      # 按真实骨架算容量")
    return
    print("下一步（模型）：按容量写每个事项，写成 items.json：")
    print('  [{"id": "1", "head": "<原句的子序列，只许删不许改写>", '
          '"src_sids": [25], "date": "2014/3/12", "lane": "P"}]')
    print("  · head 至多", b["cap_per_module"], "字，且必须是 src_sids 那几句拼接后的子序列")
    print("  · src_sids 必须升序；一个事项跨几句就都写上")
    print("然后跑：python pipeline.py render 出图.svg")


# ---------------------------------------------------------------- stage render
def stage_render(out):
    """代码做：校验模型抽出来的文字，组地图，出图。"""
    import copy
    import render_figure as RF
    st = _load(STATE, "先跑到 budget")
    S = st["sentences"]
    items = _load("items.json", "模型按容量抽出来的事项")

    e1, redo1 = C.check_subsequence(items, S)
    if st.get("has_spans"):
        # 期间型**不做逐段的字数拦截**。理由是它的三档落位（条内 / 条右侧一行 /
        # 条下方整幅宽）本来就是为「标签比条长」准备的退路，标签超出条身不是错误，
        # 是常态：那份答辩状里 49 天的图纸逾期段条内只放得下 2 字，而这一段本来就该
        # 让标签落到条外去写全。
        # 上一步我发现「拿最短那段的容量套全图」是错的，却只改了提示文字、没改这里的
        # 检查 —— 同一处错改了一半，于是六段全被判超容量。**指出问题与修掉问题是两件事。**
        e2, w2, redo2 = [], [], []
        _cap_of = {x["id"]: x["in_bar"] for x in (st.get("per_span") or [])}
        for _i, _it in enumerate(items):
            _c = _cap_of.get(f"s{_i + 1}", 0)
            if _c and len(_it["head"]) > _c:
                w2.append(f"{_it['id']}: {len(_it['head'])} 字超出条身容量 {_c} 字，"
                          f"标签会落到条右侧或条下方（这是设计中的退路，不是错误）")
    else:
        e2, w2, redo2 = C.check_capacity(items, st["cap"],
                                         cap_title=st.get("cap_title"),
                                         title=st.get("title"), sentences=S)
    lane_tbl = st.get("lane_of_file") or {}
    e3, redo3 = C.check_lane_source(items, lane_tbl)
    # 侧标签的两条纪律（[C18]）：不许下法律定性，两侧必须出自两方各自的材料。
    # 真实犯过：七份材料全部出自原告一方，图上却标着「原告主张 / 被告自认」——
    # 「自认」二字整份材料里一个字都没有，是我的法律定性。同一批材料里 5 条卡片正文
    # 全部通过子序列核验（正文有铁律），而侧标签当时没有任何来源约束。
    for _x_lbl in C.check_lane_labels(st.get("lane_labels"), items, lane_tbl):
        print(f"  侧标签不合：{_x_lbl}")
        e3 = list(e3) + [_x_lbl]
    if e1 or e2 or e3:
        print("抽取没通过，请就下列事项重写：", sorted(set(redo1 + redo2 + redo3)))
        for e in (e1 + e2 + e3)[:6]:
            print("  ", e)
        sys.exit(1)
    for w in w2[:5]:
        print("  告警：", w)
    # 材料自身的矛盾：报告，不修正，也不拦住出图。
    # 这些要进交付说明 —— 照材料画，另附一行指出矛盾，不许悄悄改成合理的日期。
    # 泳道名要传进去：「同日相反主张」只在两侧确实是对立陈述时才成立
    # （支付宝流水的「支出 / 收入」同日并存是正常账目，不是相反主张）。
    # lane 定义**在这里就地组装**，不能用 m["lanes"] —— 那个要到出图那一段（本函数
    # 更下面）才赋值，传进来是空的，于是判据退回词表兜底。第一版就这么错了：
    # 把 relation 从 actor 改成 assertion，误报本该回来，实测仍是 0 处 ——
    # **字段成了死字段，而表面上「误报消失了」，是靠词表蒙对的**。
    # 这正是这个项目最隐蔽的那类错，所以每加一个字段都要试着把它改坏一次。
    _lane_defs = [{"id": _l, "label_text": (st.get("lane_labels") or {}).get(_l, _l),
                   **({"relation": (st.get("lane_relations") or {})[_l]}
                      if (st.get("lane_relations") or {}).get(_l) else {})}
                  for _l in dict.fromkeys(
                      it.get("lane") for it in items if it.get("lane"))]
    # 承诺或约定的时点不是事实（告警）。这一类**过得了全部句法判据** ——
    # 日期在材料里、精确到日、正文是原句子序列、raw 逐字可查，四条铁律都拦不住，
    # 因为它错在语义。真实犯过：付款计划四个付款日看起来是最好的时间轴素材，全是承诺。
    # 第五轮的答案落进事项：只有用户选过（或明确不标）才动 emphasis。
    # **模型不许自己填** —— 这一处红是谁决定的，由 emphasis_source 如实记着。
    _emph = st.get("emphasis")
    _esrc = st.get("emphasis_source")
    if _esrc in ("user", "model"):
        for _it in items:
            _it["emphasis"] = (str(_it.get("id")) == str(_emph))
    elif _esrc == "none":
        for _it in items:
            _it.pop("emphasis", None)

    for _x_fut in C.check_future_as_fact(items, S):
        print(f"  请核对：{_x_fut}")
    _mc = C.check_material_conflicts(items, sentences=S,
                                     lane_labels=st.get("lane_labels"),
                                     lane_defs=_lane_defs,
                                     conflicts=st.get("conflicts"))
    for w in _mc:
        print("  材料矛盾：", w)
    if _mc:
        st["material_conflicts"] = _mc
        _save(st)

    if st.get("has_spans"):
        # 期间型：一段一行，容量逐段不同，所以容量检查要**逐段**比对，不能拿一个数套全图。
        m = {"schema_version": 2, "layout": "period_gantt",
             "title_text": st.get("title") or "图名待定", "spans": [], "points": []}
        cap_of = {x["id"]: x["in_bar"] for x in (st.get("per_span") or [])}
        for i, it in enumerate(items):
            sid = f"s{i + 1}"
            m["spans"].append({
                "id": sid, "from": it["from"], "to": it["to"],
                "label_text": it["head"], "unit_type": it.get("unit_type", "fact"),
                "source": {"file": it.get("src_file") or (st["files"] or ["材料"])[0],
                           "locator": f"句{it.get('src_sids')}"},
                **({"emphasis": True} if it.get("emphasis") else {})})
        if m["spans"]:
            m["axis"] = {"start": m["spans"][0]["from"], "end": m["spans"][-1]["to"]}
        kind, form, why, wh = RF.deliver(m, out)
        print(f"出图：{kind}·{form}　整幅 {wh[0]:.0f}x{wh[1]:.0f}　{out}")
        _say_why(kind, why)
        return

    proto = {"unit_type": "fact", "source": {}, "time": {}}
    m = {"schema_version": 2, "layout": "numbered_point_timeline",
         "title_text": st.get("title") or "图名待定", "events": []}
    # 泳道按骨架里**实际出现的**来，不写死两条。
    # 原来写死「原告主张 / 被告主张」，于是三泳道在管道这一侧根本走不到 ——
    # 渲染器支持六条，而管道只会给它两条。
    # 名字从 state 里的 lane_labels 取（模型在写骨架时一并给出）；没给就退回默认。
    _used = []
    for it in items:
        _l = it.get("lane")
        if _l and _l not in _used:
            _used.append(_l)
    # ---- 侧标签只在**模型说得出名字**时才出 -----------------------------------
    # 规矩见 HANDOVER：「有 lanes 就是有意义的分布，必出侧标签；没有就是纯为省空间的
    # 交替，不许出侧标签 —— **否则等于宣称一个数据里没有的区分**。」
    #
    # 原来这里有一句兜底 `{"P": "原告主张", "D": "被告主张"}`：模型不写
    # lane_labels.json，管线就**替它编造**一个诉讼语义。后果在真材料上出现过 ——
    # 支付宝流水两侧其实是支出与收入，图上却标着「原告主张 / 被告主张」，
    # 而图看起来完全正常。这两个词在语料里太顺手，兜底一接管就成了默认叙事。
    #
    # 三态，都不靠猜材料类型：
    #   · 事项不带 lane          → 单侧或纯交替，无侧标签
    #   · 带 lane、不给名字      → **上下分侧，不出侧标签**（分侧真实、但一句话说不清，
    #                             或者不必说。渲染器本来就支持这一档，实测几何与容量
    #                             与带标签时**完全一致**，侧标签不占横向空间）
    #   · 带 lane、给了名字      → 分侧 + 侧标签
    # 也就是说：**代码不代笔**。说不出名字不是错，编一个才是错。
    if _used:
        _labels = st.get("lane_labels") or {}
        _named = [_l for _l in _used if str(_labels.get(_l, "")).strip()]
        if not _named:
            # 一个都没命名 → 不声明 lanes，图上只做上下分侧、不出侧标签
            print(f"  分侧未命名（{len(_used)} 侧）：图上上下分侧但不出侧标签。"
                  f"分侧的语义说得出来时写 lane_labels.json，"
                  f'如 {{"P": "供货方主张", "D": "采购方主张"}}')
            _used = []
    if _used:
        m["lanes"] = []
        for _l in _used:
            # label_text 只用模型给的名字。给了一部分没给另一部分时，
            # 未命名那一条用 id 会把 P/D 这种内部标记印到图上，所以一并要求补齐。
            # 侧标签的字数上限由**当前档位的几何**决定：它贴左边缘起排，右边第一个
            # 障碍是首个圆点。事项越多卡越窄、余量越小（6 项 8 字、10 项 5 字、
            # 18 项 2 字），单行放不下折两行，两行还放不下就该在这里拦住 ——
            # 渲染器不截断也不缩字号（与图名同一条规矩）。
            # 作者定的用法：**优先四五个字**，够区分就行，两行只是兜底。
            _cap_ll = st.get("cap_lane_label") or 0
            _nm = str(_labels.get(_l, "")).strip()
            if _cap_ll and _nm and len(_nm) > _cap_ll:
                print(f"  侧标签「{_nm}」{len(_nm)} 字，这一档至多 {_cap_ll} 字"
                      f"（单行 {_cap_ll // 2} 字，放不下会折两行）。"
                      f"事项越多留给侧标签的横向余量越小 —— 请换个短名，"
                      f"四五个字够区分就行。")
                sys.exit(1)
            if not _nm:
                print(f"  泳道 {_l} 没有名字，而其余泳道有 —— 侧标签要么都出、"
                      f"要么都不出（半数命名会让图上出现 {_l} 这种内部标记）。"
                      f"请在 lane_labels.json 里补齐。")
                sys.exit(1)
            _entry = {"id": _l, "label_text": _labels[_l]}
            # relation 说明两侧是什么关系：assertion 谁的主张（默认）、
            # actor 哪一层主体、stage 哪个程序阶段（取自 RELAY-2 对泳道的定义）。
            # 只有 assertion 时「同日相反主张」才成立 —— 流水的支出与收入同日并存、
            # 一审与二审同日各有动作，都不是相反主张。不填等于 assertion。
            _rel = (st.get("lane_relations") or {}).get(_l)
            if _rel:
                _entry["relation"] = _rel
            # side 只在 state 里明确写了才带上（默认上二下一由渲染器定，见 M14）
            if (st.get("lane_sides") or {}).get(_l):
                _entry["side"] = st["lane_sides"][_l]
            m["lanes"].append(_entry)
        if len(m["lanes"]) >= 3:
            print(f"三泳道以上：{len(m['lanes'])} 条"
                  f"（{'、'.join(x['label_text'] for x in m['lanes'])}）")
    for it in items:
        ev = copy.deepcopy(proto)
        ev["id"] = it["id"]
        ev["head"] = it["head"]
        d = it.get("date")
        y, mo, da = (d.split("/") + ["1", "1"])[:3] if d else ("", "", "")
        ev["time"] = {"certainty": "exact" if d else "relative",
                      "origin": "extracted", "kind": "occur",
                      "raw": it.get("raw") or (d or "先后可定"), "date": d,
                      "date_text": (f"{y}.{int(mo):02d}.{int(da):02d}" if d else "")}
        ev["source"] = {"file": it.get("src_file") or (st["files"] or ["材料"])[0],
                        "locator": f"句{it.get('src_sids') or it.get('src_sid')}"}
        # medium 落在 **source 里**，不是事项的顶层 —— validate_map 读的是
        # event.source.medium（第一版我写在顶层，那条守卫根本看不到）。
        if it.get("medium") == "image":
            ev["source"]["medium"] = "image"
        if it.get("lane"):
            ev["lane"] = it["lane"]
        if it.get("emphasis"):
            ev["emphasis"] = True
        m["events"].append(ev)
    # ---- 图像来源要有名分（HANDOVER 5.4）--------------------------------------
    # 规矩：让模型直接读图（不引 OCR 依赖），但图像来源的事件单独标记，
    # **交付时必须声明哪几份材料没有文字层、有几个事件出自读图**。
    # 判据一个字不新写 —— schema 与 validate_map 早就完整实现了（图像来源事件必须有
    # scope、events_from_image 数目必须与实际相符、documents_unreadable 必须列出，
    # 不符是错误不是告警；造五个用例逐个验过，四种错法全被抓）。
    # 缺的只是管线组装地图时不带 medium、不带 scope，于是那条守卫在管线产物上
    # 永远看不到图像来源事件 —— 与 time.anchor 那个死字段同一类：
    # 规矩定了、schema 认了、校验器管着，交付路径不读它。
    _img_evs = [e for e in m["events"]
                if (e.get("source") or {}).get("medium") == "image"]
    if _img_evs:
        _docs = st.get("image_docs") or []
        if not _docs:
            print(f"  有 {len(_img_evs)} 个事项出自读图，但没有 image_docs.json —— "
                  f"按规矩必须声明哪几份材料没有文字层。请写 image_docs.json："
                  f'[{{"file": "材料一.docx", "reason": "聊天记录截图，无文字层"}}]')
            sys.exit(1)
        m["scope"] = {"mode": "full", "selection_source": "default",
                      "events_from_image": len(_img_evs),
                      "documents_unreadable": [
                          {"file": d.get("file", "?"),
                           "reason": d.get("reason", "无文字层，已读图"),
                           "read_as_image": True} for d in _docs]}
        print(f"  读图声明：{len(_img_evs)} 个事项出自读图"
              f"（事项 {'、'.join(str(e.get('id')) for e in _img_evs)}），"
              f"材料《{'》《'.join(d.get('file', '?') for d in _docs)}》无文字层；"
              f"这些事项未经逐字核验。")
    kind, form, why, wh = RF.deliver(m, out)
    print(f"出图：{kind}·{form}　整幅 {wh[0]:.0f}x{wh[1]:.0f}　{out}")
    _say_why(kind, why)
    # 传交付时**实际用的** layout：kind 是中文图种名，这里映回语义地图的 layout 名，
    # 因为 v1 的 _MODULE_LAYOUTS 是按 layout 名写的。
    _LAY_OF = {"编号型": "numbered_point_timeline", "日期型": "dated_point_timeline",
               "期间型": "proportional_gantt"}
    _apply_style(st, out, layout=_LAY_OF.get(kind, "numbered_point_timeline"))
    # ---- 五种可编辑格式（v1 已经解决过，这里只调它）------------------------
    # 顺序上必须在 _apply_style **之后**：风格变换改的是那张 SVG，而 pptx / vsdx
    # 是**读最终 SVG** 转出来的，先转就会把变换前的样子固定下来。
    _fmts = []
    try:
        import export_formats as EX
        _fmts = EX.deliver(out, m)
        print("可编辑格式：")
        for _f, _pth, _note in _fmts:
            print(f"  {_f:<11}{os.path.basename(_pth) if _pth else '（未生成）':<34}{_note}")
    except Exception as _exc:
        print(f"  可编辑格式未生成：{str(_exc).splitlines()[0][:70]}")
    # ---- 制作说明的事实（全部是已经算出来的，不推断）-----------------------
    # 制作说明是**给律师看的**，所以三条纪律：
    #   ① 不出现内部标识与像素数（英文 layout 名、230px 这类内部门禁值）——
    #      第一版把路由的整段机械理由抄了进去，图上就出现了 numbered_point_timeline；
    #   ② 每条一句话说完，不带两个句号；
    #   ③ 写实际发生的，不写能力上限 —— 容量那条第一版写「至多 110 字」，
    #      而图上每条只有 8 到 15 字，读起来像自相矛盾。改成同时给实际值。
    _trace(items, S, st, out)


def _apply_style(st, out, layout=None):
    """按第三轮选的风格做变换。**直接用 v1 的两个函数，不另写一份。**

    白描 = to_monochrome（单色线稿，位置与尺寸和彩色版一字不差）
    歸藏风 = to_guizang（克莱因蓝、浅灰点阵底、无衬线）
    奇川风 = 不变换（它就是渲染器的原生产物）

    两个函数都接受一张 SVG、返回变换后的 SVG，所以能直接用在这一档的产物上 ——
    验过：白描与歸藏在时间轴大师的图上都变换成功。
    **调 to_guizang 时必须把 layout 传进去。** 它的签名是 `to_guizang(svg, layout=None)`，
    而里面每一步变换都带一个 `need`：「这一步在这种 layout 上该不该触发」。
    modules -> white cards / blue diamonds 那一步只对流程图与关系图成立
    （`_MODULE_LAYOUTS` 是 relation_tree / graphviz_flow / graphviz_relation），
    时间轴没有那种模块组。不传 layout 时 need 退回 True，于是每张时间轴都会报一句
    「1 post-processing step matched NOTHING — the figure may be wrong」——
    **那是一条误报，而 v1 在 `_sub` 的注释里专门写了为什么不能有误报**：
    a report that cries wolf gets ignored（会哭狼的报告没人看，而它存在的目的
    正是防止这件事）。所以这不是「不影响」，它会把真报警一起淹掉。
    """
    style = st.get("style") or "奇川风"
    if style == "奇川风":
        return
    import sys as _s
    _v1 = os.path.join(HERE, "..", "..", "mqc-litigation-visual-redraw", "scripts")
    if _v1 not in _s.path:
        _s.path.insert(0, _v1)
    import render as _V1R
    svg = open(out, encoding="utf-8").read()
    try:
        # layout 由调用方传进来 —— 它的真值是 render_figure.deliver **实际交付的图种**。
        # 不从 state 猜、也不给硬编码默认值：拿默认值当真值正是这个项目一直在避免的事
        # （猜错时 need 又会算错，误报照样出现，而且更难查）。
        new_svg = (_V1R.to_monochrome(svg) if style == "白描"
                   else _V1R.to_guizang(svg, layout=layout))
    except Exception as exc:
        print(f"  {style} 变换失败：{str(exc).splitlines()[0][:60]}（保留奇川风）")
        return
    alt = os.path.splitext(out)[0] + f"-{style}.svg"
    open(alt, "w", encoding="utf-8").write(new_svg)
    print(f"  {style}：{alt}")


def _kill_autospace(docx):
    """关掉 Word 的中西文自动间距（autoSpaceDE / autoSpaceDN）。

    源文本里的空格已经在 _squeeze_cjk 挤掉了，JSON 里是「2022年11月8日」，而渲出来
    仍有空隙 —— 那是 Word 自己加的：OOXML 里这两个属性默认开启，会在中文与西文、
    中文与数字之间插入间距。作者要求前后不许有空格。

    docx-js 只能生成 autoSpaceDN，生成不了 autoSpaceDE，所以按文档技能给的办法
    解包改 XML：在样式表的默认段落属性里把两个都关掉，一处生效、全篇统一。
    「看起来是数据问题、实际是渲染器默认行为」这类坑，只有把产物渲出来看才发现。
    """
    import shutil
    import zipfile
    tmpd = docx + ".unz"
    if os.path.isdir(tmpd):
        shutil.rmtree(tmpd)
    with zipfile.ZipFile(docx) as z:
        z.extractall(tmpd)
    # 写进**每一个段落**的 pPr，不写在 styles.xml 的默认值里。
    # 默认值试过：属性确实写进了 docDefaults，渲出来空隙照旧 —— 表格单元格里的段落
    # 有自己的样式链，把默认值覆盖掉了。与其猜样式的层级，不如逐段写死，一处不漏。
    dp = os.path.join(tmpd, "word", "document.xml")
    add = '<w:autoSpaceDE w:val="0"/><w:autoSpaceDN w:val="0"/>'
    xml = open(dp, encoding="utf-8").read()
    # 有 pPr 的段落：插在 pPr 开头；没有 pPr 的：补一个
    xml = xml.replace("<w:pPr>", "<w:pPr>" + add)
    xml = re.sub(r"<w:p>(?!<w:pPr>)", "<w:p><w:pPr>" + add + "</w:pPr>", xml)
    open(dp, "w", encoding="utf-8").write(xml)
    base = docx + ".tmp"
    if os.path.exists(base):
        os.unlink(base)
    shutil.make_archive(base, "zip", tmpd)
    shutil.move(base + ".zip", docx)
    shutil.rmtree(tmpd)


def _squeeze_cjk(s):
    """去掉中文与西文（含数字）之间的空格。

    Word 会自动处理中西文间距，源文本里再留空格就是双份，看着满篇空隙。
    只去「一侧是中文、另一侧是西文或数字」的那些空格；西文之间的空格保留
    （「Times New Roman」不能被挤成一个词）。
    """
    import re as _re
    _CJK = r"\u4e00-\u9fff\u3000-\u303f\uff00-\uffef"
    s = _re.sub(rf"([{_CJK}])[ \t]+([0-9A-Za-z])", r"\1\2", s)
    s = _re.sub(rf"([0-9A-Za-z])[ \t]+([{_CJK}])", r"\1\2", s)
    # 反复挤，处理「中 1 中」这种夹在中间的
    for _ in range(3):
        s2 = _re.sub(rf"([{_CJK}])[ \t]+([0-9A-Za-z])", r"\1\2", s)
        s2 = _re.sub(rf"([0-9A-Za-z])[ \t]+([{_CJK}])", r"\1\2", s2)
        if s2 == s:
            break
        s = s2
    return s


def _trace(items, S, st, out):
    """顺带写一份**溯源索引**：图上每个元素出自材料何处。

    作者定过：不做「事实与证据对照表」（那会破坏这个 skill 的纯粹，它只画时间轴），
    改成溯源索引 —— 只说明图上**已经画出来**的元素各自出自哪一处，让用户知道图不是瞎编的。
    所以这份表里没有评价、没有证据是否成立的判断，只有对应关系。

    它现在能自动生成，是因为每个事项都带 src_sids：那个字段本来是给子序列检查用的，
    顺带就把溯源这件事解决了。一个字段管两件事，不必让模型再答一遍。
    """
    import subprocess
    rows = []
    for it in items:
        sids = it.get("src_sids") or ([it["src_sid"]] if it.get("src_sid") is not None else [])
        quote = "".join(S[s] for s in sids if isinstance(s, int) and 0 <= s < len(S))
        # 中文与西文之间不许有空格。
        # 材料原文里那些空格是排版软件留下的（「2023 年 1 月 12 日」），照抄进 Word
        # 就成了满篇中西文之间的空隙 —— Word 自己会做中西文间距，源文本里再塞空格
        # 就是双份。规范化时只清了页码与软换行，没清这一类，这里补上。
        quote = _squeeze_cjk(quote)
        rows.append({"no": it.get("id", ""),
                     "head": it.get("head") or it.get("label_text", ""),
                     "file": it.get("src_file") or (st.get("files") or ["材料"])[0],
                     "locator": "句" + "、".join(str(s) for s in sids),
                     "quote": quote[:120]})
    # 标题用换行分两级，不要拿全角空格拼在一行（渲出来是两个突兀的空格）
    # **一处挤干净**，不逐字段挤。
    # 逐字段挤过一版，漏了「定位」那一列（「句 9」是我自己拼的字符串，忘了挤），
    # docx 里还剩 12 段带空格。凡是「每处都要记得做一次」的事，就该收成一个出口
    # 统一做 —— 靠记性的地方一定会漏。
    payload = {"title": (st.get("title") or "本图") + "・溯源索引",
               "rows": [{k: _squeeze_cjk(v) if isinstance(v, str) else v
                         for k, v in r.items()} for r in rows]}
    js = os.path.join(HERE, "trace_index.js")
    docx = os.path.splitext(out)[0] + "-溯源索引.docx"
    tmp = os.path.splitext(out)[0] + "-trace.json"
    json.dump(payload, open(tmp, "w", encoding="utf-8"), ensure_ascii=False)
    try:
        r = subprocess.run(["node", js, tmp, docx], capture_output=True, text=True,
                           timeout=120)
        if r.returncode == 0:
            _kill_autospace(docx)
            print(f"溯源索引：{docx}（{len(rows)} 行）")
        else:
            print(f"溯源索引没写成：{(r.stderr or r.stdout)[:100]}")
    except Exception as exc:
        print(f"溯源索引没写成：{exc}")



def _say_why(kind, why):
    """出图理由分两层：先一句人话说结论，再把机械判据原样给出。

    这一处改的只是**呈现**，不是判据。起因是真实读者（含自动评测）把
    「日期型：…超过 8 格的上限…请改用编号型」读成了「出不了图，要改材料」，
    而事实相反：阶梯自动落到下一档，图已经出好了。结论不说在前面，
    读者就只能从一串机械理由里自己推。

    另外原来写的是 `why[:110]`，会在词中间断掉（实测断出
    「请改用编号型（numbered_point_timeli」这种残句）。机械理由一个字不删 ——
    C4 要求指名不成立的是哪一条 —— 改成按宽度折行，不截断。
    """
    if not why:
        return
    print(f"  图已出。图种是算出来的：这份材料落在「{kind}」这一档，"
          f"上一档不成立时会自动落档，不需要你改材料。判据：")
    # 折行的原子是「一个汉字」或「一串连续的 ASCII」：按单字符折会把
    # numbered_point_timeline 劈成两行，读起来像两个不同的标识符。
    atoms, buf = [], ""
    for ch in str(why):
        if ch == "\n":
            if buf:
                atoms.append(buf)
                buf = ""
            atoms.append("\n")
        elif ord(ch) < 128 and not ch.isspace():
            buf += ch
        else:
            if buf:
                atoms.append(buf)
                buf = ""
            atoms.append(ch)
    if buf:
        atoms.append(buf)

    line, width = "", 0
    for a in atoms:
        if a == "\n":
            print(f"    {line}")
            line, width = "", 0
            continue
        w = sum(1 if ord(c) < 128 else 2 for c in a)
        if width and width + w > 88:
            print(f"    {line}")
            line, width = "", 0
        if not line and a == " ":
            continue
        line += a
        width += w
    if line:
        print(f"    {line}")


#: 全流程。命令、谁做、要什么。`python pipeline.py steps` 打印它。
STEPS = [
    ("read <材料...>", "代码", "读材料、切句、认叙述块", ""),
    ("pick",           "用户", "第一轮：勾材料来源（含「全部」）", ""),
    ("span <编号|全部>", "用户", "第二轮：勾时间段（粒度按跨度自适应）", ""),
    ("style <1-4>",    "用户", "第三轮：呈报给谁 → 白描 / 奇川风 / 歸藏风 / 让 AI 定", ""),
    ("offer",          "模型", "划分并给勾选清单", "verdicts.json + parts.json"),
    ("budget <编号|all>", "用户", "勾哪几个部分", ""),
    ("capacity",       "代码", "按真实骨架算形态与容量", "skeleton.json"),
    ("title '…'",      "模型", "图名（容量内）", ""),
    ("render <出图.svg>", "代码", "校验 → 出图 → 顺带写溯源索引", "items.json"),
]


def stage_next():
    """现在走到第几步、下一步跑什么、缺哪个文件。

    **不做「一键出图」**：九步里有四步必须等用户回答（勾材料、勾时间段、选风格、勾部分），
    一口气跑完等于把那几轮交互替用户答了 —— 而那几轮勾选是这个 skill 的设计核心
    （见 references/front-end.md「一轮交互」那一节：先前的设计是四问，
    「先问全部还是局部」被作者指出是假省 token，改成读完材料直接给勾选清单）。

    这一条解决的是**另一件事**：中途接手时没人说得清当前状态。各步的报错本来就清楚
    （「缺 state.json，先跑 read」），但换会话、跑错顺序、文件写坏之后，
    模型只能靠 state.json 里有哪些键去推断走到第几步 —— 那是猜。
    """
    st = None
    if os.path.exists(STATE):
        try:
            st = json.load(open(STATE, encoding="utf-8"))
        except Exception as exc:
            print(f"state.json 读不出来（{type(exc).__name__}）—— 从头跑 read")
            print("  python pipeline.py read <材料...>")
            return
    if st is None:
        print("还没开始。第一步是读材料：")
        print("  python pipeline.py read <材料...>")
        print()
        print("材料是扫描件或照片（没有文字层）时，先走读图三步：")
        print("  python scripts/read_image.py probe <材料.pdf>")
        return

    # 走到哪一步，看的是**产物**而不是自称：每一步都有它必须留下的东西
    done, nxt = [], None
    done.append(f"read（{len(st.get('sentences') or [])} 句，"
                f"{len(st.get('files') or [])} 份材料）")
    for key, name, cmd, note in (
            ("pick_r1", "pick 第一轮·勾材料来源", "pick", "只有一份材料时自动跳过"),
            ("pick_r2", "span 第二轮·勾时间段", "span 全部", ""),
            ("style", "style 第三轮·选风格", "style 1", "1 法官白描 / 2 当事人奇川风 / 3 同行歸藏风 / 4 让我定"),
            ("parts", "offer 划分并给清单", "offer", "先写 verdicts.json + parts.json"),
            # 键名一律照代码里实际写的那个 —— 第一版这里猜成 picked_parts，
            # 而 stage_budget 写的是 picked_ids，于是勾完部分之后 next 还在说
            # 「下一步 budget」。**凭记忆写键名就是这个下场**，守卫盯住它。
            ("picked_ids", "budget 勾哪几个部分", "budget all", ""),
            ("cap", "capacity 算形态与容量", "capacity", "先写 skeleton.json"),
            ("title", "title 图名", "title '…'", "容量内"),
    ):
        if st.get(key) is not None:
            done.append(name.split("·")[0].split(" ")[0] + f"（{name}）")
        elif nxt is None:
            nxt = (name, cmd, note)
    print(f"已走完：{len(done)} 步")
    for d in done:
        print(f"  · {d}")
    if nxt is None:
        print()
        print("九步已齐，最后一步出图（先写 items.json：在骨架上补 head）：")
        print("  python pipeline.py render 出图.svg")
        print()
        print("出图会一并给出五种可编辑格式与溯源索引 docx。")
        return
    name, cmd, note = nxt
    print()
    print(f"下一步：{name}")
    print(f"  python pipeline.py {cmd}" + (f"      # {note}" if note else ""))
    _need = {"offer": ("verdicts.json", "parts.json"),
             "capacity": ("skeleton.json",)}.get(cmd.split()[0], ())
    for f in _need:
        if not os.path.exists(f):
            print(f"  这一步要模型先写 {f} —— 形状与判据："
                  f"python pipeline.py shape {f}")


def stage_steps():
    # 标题原来写「四轮交互都在前段」。表里标「用户」的确实是四行，但**实际有五轮** ——
    # 第五轮（标红，`mark`）在抽取完事项之后问，不在这张表里，于是同一个包里
    # README 说五个问题、这里说四轮。数字一旦写死就会与别处打架，改成不数数：
    # 判据是「谁做」那一列，它跟着 STEPS 走，永远不会漂。
    print("全流程（标着「用户」的那几步等他勾选，其余自动）：")
    print()
    print(f"  {'命令':<22}{'谁做':<6}{'做什么':<34}{'要先写的文件'}")
    print("  " + "-" * 86)
    for cmd, who, what, need in STEPS:
        print(f"  {cmd:<22}{who:<6}{what:<34}{need}")
    print()
    print()
    print("  另有 mark <编号|0>    用户  第五轮：深红标在哪一处（0 = 不标）")
    print("  ——它在抽取完事项之后、出图之前问，只有奇川风才问，所以不在上表的主序里。")
    print()
    print("中途接手：python pipeline.py next   —— 现在走到第几步、下一步跑什么")
    print("四份 JSON 的形状与判据：python pipeline.py shape verdicts.json")
    print("（也可写 parts.json / skeleton.json / items.json）")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    cmd = sys.argv[1]
    if cmd == "mark":
        stage_mark(sys.argv[2] if len(sys.argv) > 2 else "")
    elif cmd == "next":
        stage_next()
    elif cmd == "steps":
        stage_steps()
    elif cmd == "shape":
        print_shape(sys.argv[2] if len(sys.argv) > 2 else "verdicts.json")
    elif cmd == "read":
        stage_read(sys.argv[2:])
    elif cmd == "pick":
        stage_pick(sys.argv[2] if len(sys.argv) > 2 else None)
    elif cmd == "span":
        stage_span(sys.argv[2] if len(sys.argv) > 2 else "全部")
    elif cmd == "style":
        stage_style(sys.argv[2] if len(sys.argv) > 2 else "2")
    elif cmd == "offer":
        stage_offer()
    elif cmd == "capacity":
        stage_capacity()
    elif cmd == "title":
        stage_title(sys.argv[2] if len(sys.argv) > 2 else "")
    elif cmd == "budget":
        stage_budget(sys.argv[2] if len(sys.argv) > 2 else "all")
    elif cmd == "render":
        stage_render(sys.argv[2] if len(sys.argv) > 2 else "out.svg")
    else:
        print(__doc__)
        sys.exit(2)
