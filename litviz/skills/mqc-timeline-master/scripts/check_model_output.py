#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Check what the model returned for steps 4, 5 and 6.

Those three steps read meaning, so they cannot be made deterministic. What CAN
be made deterministic is the check: if every claim the model makes is verifiable
against the sentences, a wrong answer becomes a failed check rather than a wrong
figure. That is the whole design — the model is allowed to be wrong, it is not
allowed to be wrong silently.

The order matters. Each function reports what is wrong AND what to send back, so
the caller can re-ask for exactly the sentences that failed instead of re-running
the whole document.

  4 classify  every sentence is an event or is not, with no sentence unaccounted
  5 date      every time expression is quoted verbatim from its own sentence
  6 segment   every fragment is a substring of its own sentence
"""
import re


def _norm(s):
    return re.sub(r"\s+", "", s or "")


# ------------------------------------------------------------------ 4 classify

def check_classify(sentences, verdicts):
    """verdicts: [{"i": int, "is_event": bool, "why": str}]

    A sentence left out is the failure this guards against. A model asked to
    return only the events will return only the events and look complete, so the
    protocol demands a verdict per sentence and this counts them.
    """
    errs, redo = [], []
    seen = {}
    for v in verdicts:
        i = v.get("i")
        if not isinstance(i, int) or not 0 <= i < len(sentences):
            errs.append(f"判定引用了不存在的句号 {i!r}")
            continue
        if i in seen:
            errs.append(f"句 {i} 被判定了两次")
        seen[i] = v
        if not isinstance(v.get("is_event"), bool):
            errs.append(f"句 {i}: is_event 必须是真或假")
            redo.append(i)
        if not (v.get("why") or "").strip():
            errs.append(f"句 {i}: 未给出判定理由")
            redo.append(i)
    missing = [i for i in range(len(sentences)) if i not in seen]
    if missing:
        errs.append(f"{len(missing)} 句未判定：{missing[:10]}"
                    + ("…" if len(missing) > 10 else ""))
        redo += missing
    return errs, sorted(set(redo))


# ---------------------------------------------------------------------- 5 date

_CERT = {"exact", "range", "relative", "order"}
_KIND = {"occur", "sign", "effect", "send", "arrive", "receipt", "record", "due"}


def _has_day(sent, y, m, d):
    """这句话里有没有**到日**的表述。认同一个日子的多种写法。

    判据的本意是挡住「凭空加精度」：材料只说「2020 年」而判 exact 加 2020/1/1，
    那是把年份当成了一月一日。所以要求句子里真的有那个「日」。

    但第一版只认「N 日」这一种写法，真材料上当场撞坏：供货清单写的是
    `供货时间 2020.3.15`，银行流水与聊天记录时间戳写 `2020-03-15`、`2020/3/15` ——
    这些**已经精确到日**，却被判成「没有到日的表述」，于是合法的骨架被拦在门外。
    判据挡错了对象：它要挡的是精度不足，不是写法不同。

    所以认四种写法：`3 月 15 日`、`2020.3.15`、`2020-03-15`、`2020/3/15`，
    月与日都允许有没有前导零。**不放宽到「只要句中出现这个数字就算」** ——
    那样「数量 15」也会被当成 15 日，等于把这条判据废掉。
    """
    _m, _d = int(m), int(d)
    pats = [
        rf"{_d}\s*日",                                        # 3 月 15 日
        rf"(?<!\d){int(y)}\s*[./-]\s*0?{_m}\s*[./-]\s*0?{_d}(?!\d)",  # 2020.3.15
    ]
    return any(re.search(p, sent) for p in pats)


def check_dates(sentences, items):
    """items: [{"i": int, "certainty": str, "raw": str, "date"/"from"/"to"/...}]

    `raw` must be quoted verbatim from sentence i. Without that the model can
    paraphrase a date into existence — 「次年初」 becomes 2017/1/1 and nothing in
    the output shows that the material never said so.
    """
    errs, redo = [], []
    for it in items:
        i = it.get("i")
        if not isinstance(i, int) or not 0 <= i < len(sentences):
            errs.append(f"时间项引用了不存在的句号 {i!r}")
            continue
        c = it.get("certainty")
        if c not in _CERT:
            errs.append(f"句 {i}: certainty {c!r} 不在四档之内")
            redo.append(i)
        raw = it.get("raw", "")
        if not raw:
            errs.append(f"句 {i}: 缺 raw，必须是该句中的时间表述原文")
            redo.append(i)
        elif _norm(raw) not in _norm(sentences[i]):
            errs.append(f"句 {i}: raw {raw!r} 在该句中查不到，不得改写时间表述")
            redo.append(i)
        if it.get("kind") and it["kind"] not in _KIND:
            errs.append(f"句 {i}: kind {it['kind']!r} 不在允许之列")
            redo.append(i)
        if c == "exact" and not it.get("date"):
            errs.append(f"句 {i}: exact 必须给出 date")
            redo.append(i)
        if c == "range" and not (it.get("from") and it.get("to")):
            errs.append(f"句 {i}: range 必须给出 from 与 to")
            redo.append(i)
        if c == "relative" and not it.get("anchor"):
            errs.append(f"句 {i}: relative 必须给出 anchor")
            redo.append(i)
        # a date the sentence does not contain in digits must not be claimed exact
        if c == "exact" and it.get("date"):
            y, m, d = (it["date"].split("/") + ["", ""])[:3]
            if d and not _has_day(sentences[i], y, m, d):
                errs.append(f"句 {i}: 判为 exact 并给出 {it['date']}，"
                            f"但该句没有到日的表述")
                redo.append(i)
    return errs, sorted(set(redo))


# ------------------------------------------------------------------- 6 segment

def check_segments(sentences, segs):
    """segs: [{"i": int, "head": str, "body": str|None, "items": [str]|None}]

    Every piece must be a substring of its own sentence, in order. The card may
    drop words; it may not add or reorder them. This is the verbatim rule made
    checkable: 「只许删减不许改写」 is exactly 「is a subsequence of」.
    """
    errs, redo = [], []
    for sg in segs:
        i = sg.get("i")
        if not isinstance(i, int) or not 0 <= i < len(sentences):
            errs.append(f"片段引用了不存在的句号 {i!r}")
            continue
        src = _norm(sentences[i])
        if sg.get("body") and sg.get("items"):
            errs.append(f"句 {i}: body 与 items 不得并存")
            redo.append(i)
        parts = [("head", sg.get("head"))]
        if sg.get("body"):
            parts.append(("body", sg["body"]))
        for k, x in enumerate(sg.get("items") or []):
            parts.append((f"items[{k}]", x))
        if not (sg.get("head") or "").strip():
            errs.append(f"句 {i}: 缺 head")
            redo.append(i)
        for name, txt in parts:
            if not txt:
                continue
            # subsequence, not substring: the card is allowed to drop words
            pos, ok = 0, True
            for ch in _norm(txt):
                pos = src.find(ch, pos)
                if pos < 0:
                    ok = False
                    break
                pos += 1
            if not ok:
                errs.append(f"句 {i}.{name}: {txt[:20]!r} 不是该句的子序列，"
                            f"卡片文字只许删减不许改写")
                redo.append(i)
    return errs, sorted(set(redo))


def report(name, errs, redo):
    if not errs:
        return f"{name}: 通过"
    lines = [f"{name}: {len(errs)} 项不合规"]
    lines += [f"  {e}" for e in errs[:12]]
    if len(errs) > 12:
        lines.append(f"  …另有 {len(errs) - 12} 项")
    if redo:
        lines.append(f"  需重问的句号：{redo[:20]}"
                     + ("…" if len(redo) > 20 else ""))
    return "\n".join(lines)


# ---------------------------------------------------------------- 前端：拆解
def check_parts(sentences, parts, max_parts=8, blocks=None, events=None):
    """检查模型给出的「部分清单」（交互第三步）。

    parts: [{"id":int, "name":str, "sids":[int], "span":str, "first":str, "last":str}]

    这一步是模型读懂内容之后的智能划分，代码不做划分（正则切过一版，切出三组同名和
    「在“投标书”（投标文件第 8 页」这样的残句）。但**划分的完整性可以查**：
      · 每一句都要落在某个部分里，一句不漏 —— 漏掉的那句就是被悄悄丢掉的事实
      · 一句不许同时属于两个部分 —— 否则勾选两个部分会把同一件事画两遍
      · 部分数不许超上限（作者定的 7 到 8），超了就等于没划分
      · 名字不许重复 —— 三组同名律师无法勾选
      · 首末句必须是该部分里真实存在的那两句原文，防止模型自己编一句概括
    """
    errs, redo = [], []
    n = len(sentences)
    seen = {}
    names = {}
    # blocks: 摄入那一步认出的叙述块 {"claim": (9,15), "defence": (15,17), ...}
    # events: 分类那一步判定为事项的句号集合
    # 两者都可以不传（口述、提示词这类材料没有叙述块）。传了就多两条检查：
    #   · 一个部分不许跨叙述块 —— 判决书本来就有诉称/辩称/查明/理由四块的天然界，
    #     跨块切出来的部分必然名不副实（实测：名叫「本院查明的两期付款」的部分，
    #     首句却是签约、末句跑到了两年后的还款计划书）
    #   · 部分里不许只有非事项句 —— 法院名称、审判员署名、判项这类程序性文字
    #     不是事实，摆进候选清单让律师勾选毫无意义（实测有两个部分混进了这些）
    def _blk_of(i):
        if not blocks:
            return None
        for name, (a, b) in blocks.items():
            if a <= i < b:
                return name
        return "其他"
    if len(parts) > max_parts:
        errs.append(f"划分出 {len(parts)} 个部分，超过 {max_parts} 个的上限；"
                    f"让人从十几个里挑，比不划分还累")
    for p in parts:
        pid = p.get("id")
        sids = p.get("sids") or []
        if not sids:
            errs.append(f"部分 {pid}: 没有任何句子")
            redo.append(pid)
        nm = _norm(p.get("name", ""))
        if not nm:
            errs.append(f"部分 {pid}: 没有名字")
            redo.append(pid)
        elif nm in names:
            errs.append(f"部分 {pid} 与部分 {names[nm]} 同名 {p.get('name')!r}；"
                        f"同名的部分无法勾选")
            redo.append(pid)
        else:
            names[nm] = pid
        for s in sids:
            if not isinstance(s, int) or not (0 <= s < n):
                errs.append(f"部分 {pid}: 引用了不存在的句号 {s!r}")
                redo.append(pid)
            elif s in seen:
                errs.append(f"句 {s} 同时属于部分 {seen[s]} 与 {pid}；"
                            f"勾选两个部分会把同一件事画两遍")
                redo.append(pid)
            else:
                seen[s] = pid
    missing = [i for i in range(n) if i not in seen]
    if missing:
        errs.append(f"{len(missing)} 句没有落在任何部分里：{missing[:10]}"
                    f"{' …' if len(missing) > 10 else ''}；"
                    f"漏掉的句子就是被悄悄丢掉的事实")
        redo += [None]        # 需要重划整份
    # 跨块与全非事项：只有句子归属都对了才有意义查
    if not missing:
        for p in parts:
            pid = p.get("id")
            sids = p.get("sids") or []
            if not sids:
                continue
            if blocks:
                bs = {_blk_of(s) for s in sids}
                if len(bs) > 1:
                    errs.append(f"部分 {pid} 跨了 {sorted(bs)} 多个叙述块 —— "
                                f"诉称、辩称、查明、理由各有天然的界，跨块切出来的"
                                f"部分名不副实")
                    redo.append(pid)
            if events is not None:
                ev_in = [s for s in sids if s in set(events)]
                if not ev_in:
                    # 顺带说清怎么改：这类部分（诉请、证据清单、首部、署名）在真材料里
                    # 每份都有，两次跑管道两次撞上，我两次都是手工并进相邻部分 ——
                    # 那说明这不是偶发错误而是常规动作，报错就该把改法一起说出来。
                    errs.append(f"部分 {pid} 里没有任何事项句（全是程序性文字或"
                                f"法律评价）—— 这样的部分摆进候选清单，律师勾了也"
                                f"画不出东西。改法：并进相邻的有事实的部分，"
                                f"或者整段不进清单")
                    redo.append(pid)

    # 事实卡的检查放在完整性之后：只有句子归属都对了，首末句才有意义可比。
    # 顺序反了的后果：漏掉四句时报的是「事实卡不是原文」（首末句按 min/max 算，
    # 漏句之后 max 变了），前端拿到的返工提示指向错误的地方 —— 明明漏了句，
    # 却以为是抄错了原文。报错的顺序要跟因果一致：先报根本问题，再报派生问题。
    if not missing:
        for p in parts:
            pid = p.get("id")
            sids = p.get("sids") or []
            # 首末句必须真的是这个部分里的那两句
            if sids and all(isinstance(s, int) and 0 <= s < n for s in sids):
                lo, hi = min(sids), max(sids)
                for key, want in (("first", lo), ("last", hi)):
                    got = _norm(p.get(key, ""))
                    if got and got not in _norm(sentences[want]):
                        errs.append(f"部分 {pid}: {key} 不是句 {want} 的原文，"
                                    f"事实卡必须照抄原文，不许概括")
                        redo.append(pid)
    return errs, [r for r in sorted(set(x for x in redo if x is not None))]


# ---------------------------------------------------------------- 前端：容量
def check_capacity(items, cap_chars, cap_title=None, title=None, sentences=None):
    """检查抽出来的文字是否装得进模块（交互第四步之前）。

    items: [{"id":str, "head":str}]

    容量是后端算出来交给前端的数（scripts/capacity.py）。这条检查的意义在于：
    **超容量必须在出图之前被拦住**，而不是等渲染器截断 —— 截断等于从材料里删字，
    还留个记号说这里有东西你看不到，而图上不许出现省略号。
    同时容量也是目标：写得太短是把图上的地方浪费掉、把材料里的信息丢掉，所以低于
    容量四成时给一条告警（不拦，因为短也可能是材料本身就短）。
    """
    errs, warns, redo = [], [], []
    for it in items:
        h = it.get("head") or ""
        # attrib（「原告称」这类标注）会拼在正文开头，所以**占容量**。
        # 它不进 head 是因为子序列检查只认原文；但它占的是同一张卡的地方，
        # 算容量时必须连括号一起算，否则卡片会超出。
        _at = (it.get("attrib") or "").strip()
        if _at:
            if len(_at) > 5:
                errs.append(f"事项 {it.get('id')}: attrib「{_at}」{len(_at)} 字，"
                            f"不许超过 5 字 —— 它只说明是谁陈述，不是内容")
                redo.append(it.get("id"))
            h = f"（{_at}）" + h
        if len(h) > cap_chars:
            errs.append(f"事项 {it.get('id')}: {len(h)} 字超过容量 {cap_chars} 字，"
                        f"请在容量内删减（只许删不许改写），不要指望截断")
            redo.append(it.get("id"))
        elif cap_chars >= 12 and len(h) < cap_chars * 0.4:
            # 「写太短」要跟**原句能给多少**比，不能跟容量比。
            # 实测那份 docx 起诉状：容量 140 字而原句最长只有 93 字，七条全被报「写太短」
            # —— 再怎么写也到不了容量，报警只是噪音，而噪音会让人连真的告警一起忽略。
            # 所以只有当原句还有明显剩余可用时才提醒。
            room = None
            if sentences is not None:
                sids = it.get("src_sids") or ([it["src_sid"]]
                                              if it.get("src_sid") is not None else [])
                if sids and all(isinstance(s, int) and 0 <= s < len(sentences)
                                for s in sids):
                    room = len(_norm("".join(sentences[s] for s in sids)))
            if room is None or len(h) < room * 0.6:
                warns.append(f"事项 {it.get('id')}: 只写了 {len(h)} 字，容量有 "
                             f"{cap_chars} 字"
                             + (f"、原句有 {room} 字" if room else "")
                             + " —— 容量既是上限也是目标")
    if title is not None and cap_title:
        if len(title) > cap_title:
            errs.append(f"图名 {len(title)} 字超过容量 {cap_title} 字，请改短")
    return errs, warns, sorted(set(redo))


# ------------------------------------------------------------ 前端：只许删减
def check_subsequence(items, sentences):
    """卡片文字必须是来源句的**子序列**：只许删字，不许改写、换词、调顺序。

    items: [{"id":str, "head":str, "src_sids":[int, ...]}]
           兼容旧的单句写法 {"src_sid": int}

    **一个事项可以来自多句**，这是常态而不是例外：材料里一件事往往横跨两三句。
    真材料里现成的例子（材料一，句 29 与 30）：
        「2015 年 2 月 3 日，被告向原告支付 320 万元。」
        「此后被告未再付款。」
    合起来才是一个事项。第一版只允许一个 src_sid，于是这种合并**必然误报** ——
    文字不是任何单句的子序列，前端只能拆成两个事项，或者干脆绕过检查；绕过就等于
    这条忠实性保证没了。所以要按声明的**多句顺序拼接**之后核验。

    顺序也要一起管：src_sids 必须按句号升序，否则「先写后一句再写前一句」这种调序
    会被拼接掩盖过去 —— 那已经不是删减，是重新组织材料。
    """
    errs, redo = [], []
    for it in items:
        sids = it.get("src_sids")
        if sids is None:
            one = it.get("src_sid")
            sids = [one] if one is not None else []
        if not sids:
            errs.append(f"事项 {it.get('id')}: 没有声明 src_sids，"
                        f"无法核验文字是否出自原文")
            redo.append(it.get("id"))
            continue
        bad = [s for s in sids
               if not isinstance(s, int) or not (0 <= s < len(sentences))]
        if bad:
            errs.append(f"事项 {it.get('id')}: src_sids 里 {bad!r} 不指向任何句子")
            redo.append(it.get("id"))
            continue
        if list(sids) != sorted(sids):
            errs.append(f"事项 {it.get('id')}: src_sids {list(sids)} 不是升序 —— "
                        f"调换句子顺序不是删减，是重新组织材料")
            redo.append(it.get("id"))
            continue
        src = _norm("".join(sentences[s] for s in sids))
        txt = _norm(it.get("head") or "")
        i = 0
        for ch in txt:
            i = src.find(ch, i)
            if i < 0:
                errs.append(f"事项 {it.get('id')}: 文字不是句 {list(sids)} 的子序列"
                            f"（出现了原句里没有的字或改了顺序），"
                            f"只许删减不许改写")
                redo.append(it.get("id"))
                break
            i += 1
    return errs, sorted(set(redo))


# ------------------------------------------------------- 前端：泳道与来源一致
#: 侧标签里**不许出现的法律定性**。这些词是模型下的判断，不是材料上写着的字。
#: 真实犯过：七份材料全部出自原告一方，我却把两侧标成「原告主张 / 被告自认」——
#: 「自认」二字整份材料里一个字都没有，付款计划原文写的是「我司2021年的逾期货款」。
#: 同一批材料里 5 条卡片正文全部通过子序列核验（正文有铁律管着），
#: 而侧标签没有任何来源约束，于是我的法律定性直接印到了图上。
#:
#: 为什么这里用词表是够的（与「同日相反主张」那次不同）：那次要认的是「谁是对立方」，
#: 而对立方的叫法无穷（公司名、人名、简称），词表必然漏；**法律定性的词是有限的一小类**。
#: 而且这条是**只拦不放**：拦住等于退回「不出侧标签」那一档（图照样画得出），
#: 不存在漏报成错图的风险。
_VERDICT_WORDS = (
    "自认", "承认", "认可", "否认", "抗辩", "违约", "侵权", "恶意", "过错", "责任",
    "无效", "有效", "属实", "存疑", "虚假", "伪造", "不实", "败诉", "胜诉",
)


#: 句子里出现这些词，说明它说的是**将来要做的事**，不是做过的事。
#: 用于把「承诺/约定的时点」从事实里挑出来**提请核对**（告警，不是错误）。
_FUTURE_WORDS = (
    "承诺", "计划", "将于", "拟于", "应于", "应当于", "须于", "期限为", "之前支付",
    "前支付", "内支付", "内整改", "内履行", "按时支付", "付款计划", "交货期限",
)


def check_future_as_fact(items, sentences=None):
    """挑出可能把**承诺或约定的时点**当成已发生事实的事项。返回告警，不是错误。

    为什么必须有这一条：这一类**过得了全部句法判据** —— 日期确实在材料里、
    确实精确到日、正文确实是原句的子序列、`raw` 逐字可查。四条铁律一条都拦不住它，
    因为它错在**语义**：那是将来要做的事。而 model-steps 第 48 行写着
    「判据是句法的，不是价值的：问『有没有人在某时做了某事』」——
    「2022 年 5 月 18 日：100,000 元」经不起这一问：**没有人在那时做了什么**。

    真实犯过：一份被告盖章的付款计划给了四个付款日（5-18 十万、5-30 十万、
    6-10 十五万、6-20 九万四千余），四个日期四笔金额，看起来是整套材料里最好的
    时间轴素材，而它们全是承诺。**把承诺画成事实，是这张图能犯的最严重的错之一。**

    **为什么是告警而不是错误**：承诺与事实的分界要读懂意思才判得准，词表只能提示。
    「承诺三十日内整改」是承诺；而「原告承诺书于当日送达」里那个「承诺」属于文书名，
    送达本身是事实。判成错误会把后一种拦死，所以措辞是「请核对」——
    与「同日相反主张」那条一样：机械可查的疑点由代码提出，是不是真的由人看。
    """
    warns = []
    for it in items or []:
        txt = (it.get("head") or "") + (it.get("raw") or "")
        if sentences:
            for sid in (it.get("src_sids") or []):
                if 0 <= sid < len(sentences):
                    txt += sentences[sid]
        hit = [w for w in _FUTURE_WORDS if w in txt]
        if hit and it.get("kind") == "due":
            warns.append(f"事项 {it.get('id')} 的 kind 是 due（期限届满）且引用的句子里有 "
                         f"{hit[:3]} —— 请核对这是**已经届满并发生了什么**，还是"
                         f"**承诺将来要做的事**。承诺不进主轴；要画「到期未付」，"
                         f"材料里得另有记载（付款回单、催告函、对账单），"
                         f"不许用「后来起诉了所以没付」这类推断补上")
    return warns


def check_lane_labels(labels, items, lane_of_file=None):        # [C18]
    """侧标签的两条纪律：不许下法律定性，两侧必须出自两方各自的材料。

    labels:       {"P": "原告主张", "D": "被告主张"}
    items:        [{"id":str, "lane":"P"/"D", "src_file":str}]
    lane_of_file: {"起诉状":"P", "答辩状":"D"}；判决书这类一份含两方的不放进来

    **同一个词的合法性取决于它出自谁的材料。** 「被告主张」写在判决书里可以
    （法院居中记载两方陈述），写在原告的起诉状里就不行 —— 那是原告转述对方的话，
    而转述人自己是当事人、有身份性。项目早有两条规矩管着这件事的一半：
      · `model-steps.md`「**单方主张的转述**，除非主张本身就是那件事」（只管卡片正文）
      · `layout-constraints.md`「材料里有两方各自的主张（起诉状对答辩状、诉称对辩称）
        就是双泳道，**只有一方叙述就是单侧**」
    第二条直接判了那次的错：七份材料全出自原告，本该单侧。

    所以这里查两件：标签里有没有法律定性、两侧的材料是不是真的分属两方。
    拿不到 lane_of_file 时只查前一件 —— 判决书这类一份含两方的材料不在那张表里，
    对它们照放（叙述块定泳道那条路另有检查）。
    """
    errs = []
    for lane, name in (labels or {}).items():
        hit = [w for w in _VERDICT_WORDS if w in str(name)]
        if hit:
            errs.append(f"侧标签「{name}」含法律定性 {hit} —— 那是判断不是材料上的字，"
                        f"图上只许写百分百确认的客观事实或有明确出处的一方主张。"
                        f"说不准就不要给这一侧起名（不出侧标签，图照样画得出）")
    if lane_of_file and labels and len(labels) >= 2:
        sides = {}
        for it in items or []:
            f, ln = it.get("src_file"), it.get("lane")
            if ln:
                sides.setdefault(ln, set()).add(lane_of_file.get(f, f))
        # 每一侧的材料归属方
        owners = {ln: srcs for ln, srcs in sides.items()}
        flat = [o for srcs in owners.values() for o in srcs if o]
        if len(owners) >= 2 and len(set(flat)) == 1:
            errs.append(f"两侧的材料全部出自同一方（{sorted(set(flat))[0]}）却分了 "
                        f"{len(owners)} 侧并起了名 —— 只有一方叙述就是单侧。"
                        f"一方材料里写的「对方主张」是转述，转述人自己是当事人")
    return errs


def check_lane_source(items, lane_of_file):
    """泳道必须与这条事项**出自哪份材料**一致。

    items:        [{"id":str, "lane":"P"/"D", "src_file":str}]
    lane_of_file: {"起诉状":"P", "答辩状":"D", ...}

    这是一个被漏掉很久的洞：校验器从不比对 lane 与 source.file，所以泳道标错是**零检测**
    的 —— 把被告的主张画到原告那一侧，图看起来完全正常，几何全过、文字不挤，而它把
    对方的话安到了自己当事人头上。这是这套图能犯的最严重的错，比任何排版问题都严重。

    判决书这类材料一份里同时有两方陈述，所以它不在这张表里；表里只放「整份材料属于一方」
    的那些（起诉状、答辩状、代理意见）。判决书里的事项靠叙述块（诉称段 / 辩称段）定泳道，
    由抽取那一步声明，不在这条检查的范围内。
    """
    errs, redo = [], []
    for it in items:
        f = it.get("src_file")
        if f is None or f not in lane_of_file:
            continue                      # 不在表里的材料（如判决书）跳过
        want = lane_of_file[f]
        got = it.get("lane")
        if got != want:
            errs.append(f"事项 {it.get('id')}: 出自《{f}》却标成 "
                        f"{got!r} 一侧，应为 {want!r} —— 泳道标错会把对方的主张"
                        f"安到自己当事人头上")
            redo.append(it.get("id"))
    return errs, sorted(set(redo))


# ---------------------------------------------------------------- 前端：冲突
def check_conflicts(items, conflicts, sentences=None):
    """检查模型产出的冲突声明（双方对同一事实的相反陈述）。

    conflicts: [{"id":str, "kind":"date"/"characterization",
                 "members":[事项 id, ...], "note":str}]

    冲突是双方主张对读那类图的核心，规矩有四条，每条都对应一种真实会犯的错：

    · **成员至少两个，且必须来自不同的一方。** 只有一个成员不是冲突；两个成员同在
      原告一侧也不是冲突（那是同一方的两次陈述，属于材料自身矛盾，另有规矩：照材料画、
      另附说明）。
    · **日期冲突的成员必须真的日期不同。** 声明成 date 却两边日期一样，说明模型把
      「说法不同」当成了「日期不同」。
    · **性质冲突的成员日期必须相同或相近。** 性质冲突是「同一件事，双方定性相反」
      （材料二第 19 句：同一个竣工期限，原告算到 2022/9/22、被告算到 2023/8/14），
      日期差太远就不是同一件事，而是两件事。
    · **一个事项不许同时属于两处冲突。** 否则图上无法标示它到底跟谁冲突。

    最要紧的一条底线（validate_map 已管）：冲突的两方都必须画出来，不许消化掉一方。
    这里补的是「冲突声明本身站不站得住」。
    """
    errs, redo = [], []
    by_id = {it.get("id"): it for it in items}
    used = {}
    for c in conflicts:
        cid = c.get("id", "?")
        mem = c.get("members") or []
        kind = c.get("kind")
        if len(mem) < 2:
            errs.append(f"冲突 {cid}: 只有 {len(mem)} 个成员，一个成员不是冲突")
            redo.append(cid)
            continue
        gone = [x for x in mem if x not in by_id]
        if gone:
            errs.append(f"冲突 {cid}: 成员 {gone} 不在事项里")
            redo.append(cid)
            continue
        lanes = {by_id[x].get("lane") for x in mem}
        if len(lanes) < 2:
            errs.append(f"冲突 {cid}: 成员都在 {lanes} 同一侧 —— 同一方的两次陈述"
                        f"是材料自身矛盾，应照材料画并另附说明，不是冲突")
            redo.append(cid)
        ds = [by_id[x].get("date") for x in mem]
        if kind == "date":
            if len({d for d in ds if d}) < 2:
                errs.append(f"冲突 {cid}: 声明为日期冲突，但成员的日期是 {ds} —— "
                            f"日期相同就不是日期冲突，可能应为 characterization")
                redo.append(cid)
        elif kind == "characterization":
            try:
                from datetime import date
                ps = []
                for d in ds:
                    if d:
                        y, mo, da = (int(x) for x in str(d).replace("-", "/").split("/"))
                        ps.append(date(y, mo, da))
                if len(ps) >= 2 and (max(ps) - min(ps)).days > 370:
                    errs.append(f"冲突 {cid}: 声明为性质冲突，但成员日期相差 "
                                f"{(max(ps) - min(ps)).days} 天 —— 差这么远不是同一件事，"
                                f"而是两件事")
                    redo.append(cid)
            except Exception:
                pass
        for x in mem:
            if x in used:
                errs.append(f"事项 {x} 同时属于冲突 {used[x]} 与 {cid}，"
                            f"图上无法标示它跟谁冲突")
                redo.append(cid)
            else:
                used[x] = cid
    return errs, sorted(set(str(r) for r in redo))


# ------------------------------------------------- 前端：材料自身的矛盾
#: 「设立性」事项的关键词：这类事项在时间上必须先于依它而生的履行行为。
#: 只列最没有争议的那几个，不做语义推断 —— 判据要机械可查。
_FOUNDING = ("签订", "订立", "签署", "成立", "立案", "受理")
#: 「履行性」事项的关键词。
_PERFORM = ("交付", "支付", "付款", "验收", "履行", "交货", "安装", "结算")


#: `lane.relation` 里**不是对立陈述**的两个值。取自 RELAY-2 对泳道的定义：
#: 「泳道是语义分方（谁的主张 / 哪一层主体 / 哪个程序阶段）」——
#: 只有第一种是对立的，后两种同日各有动作是正常的，不是相反主张。
_NON_ADVERSARIAL_REL = {"actor", "stage"}        # [C16]


def _is_adversarial(lane_labels=None, lane_defs=None):
    """两侧是不是互相对立的陈述。**判据只看 `lane.relation`，不猜泳道名。**

    这个字段是补出来的，起因是一路撞出来的三次失败：
      · 支付宝流水两侧是「支出 / 收入」，同日既有支出又有收入是正常账目，
        75 笔报出 6 处「同日相反主张」，逐条回查原材料**一处都不是**对立主张；
      · 第一版按泳道名**白名单**判（含「原告/被告/甲方」才算对立），当场漏报：
        仓库示例 stress-two-sides 的泳道名是「甲公司、乙公司 / 丙公司」——
        真正对立的两方，却不含任何白名单字样。**漏报比误报严重**：
        误报是多问一句，漏报是把材料里真实存在的矛盾咽下去；
      · 改成**黑名单**之后仍只覆盖账目方向那一类，「母公司 / 子公司」「总包 / 分包」
        「一审 / 二审」「审理 / 执行」六个用例照样误报 —— 泳道名的写法是无穷的，
        而语义只有 RELAY-2 明列的三类，所以这是语义问题，不是字符串问题。
    根因是 schema 里 `lanes` 只表示「互相对读的对立两方」，而 RELAY-2 明列三种语义，
    后两种没有字段表达。所以补 `relation`。

    **词表兜底已经删掉，不留第二套判据。** 留过一版，后果立刻出现：不填 relation 时
    契约说「等于 assertion（对立）」，而词表看到「支出 / 收入」就把它判成不对立，
    于是**字段与词表两套判据并存，暗中生效的是词表**——同一件事有两个判据，
    必然有一个说话不算数。现在只有一个判据：`relation`，缺省即 `assertion`。
    """
    rels = [str((l or {}).get("relation") or "assertion") for l in (lane_defs or [])]
    if not rels:
        # 拿不到 lane 定义时照报：这条检查在诉讼材料上是准的，而诉讼材料是主流。
        # 宁可多问一句「请核对」，也不要在真有冲突时不出声。
        return True
    # 两侧都声明了非对立关系才算不对立；一侧 assertion 一侧 actor 说不清，保守照报。
    return not all(r in _NON_ADVERSARIAL_REL for r in rels)


def check_material_conflicts(items, sentences=None, conflicts=None,
                             lane_labels=None, lane_defs=None):
    """认出**材料自身的矛盾**，报告但不修正。

    规矩是作者定的：照材料画、另附一行说明，不许悄悄改成合理的日期 —— 改了就不是还原
    材料，而是替当事人整理事实。所以这里返回的全是告警，不是错误：它们不该拦住出图，
    只该出现在交付说明里。

    两类机械可查的矛盾（真材料里都撞过）：

    · **履行先于设立。** 「2019 年 4 月 8 日交付全部设计成果」而合同签订于同年 5 月 20 日
      —— 交付早于合同。判据只看关键词与日期先后，不做语义推断：出现设立性词的最早日期
      之前，若有履行性词的事项，就报出来。
    · **同日相反主张。** 同一天两方各有一项，一方主张履行、另一方主张未履行或反向请求。
      判据是「同一天 + 分属两侧」，具体是否真的对立要人看，所以措辞是「请核对」。

    有意不做的：不推断「谁在说谎」，不判断哪个日期是对的，不给修正建议。这三件都超出
    「还原材料」的边界。
    """
    warns = []
    dated = [(it, _ymd_of(it)) for it in items if _ymd_of(it)]
    if not dated:
        return warns

    def has(it, words):
        h = (it.get("head") or "") + (it.get("raw") or "")
        return any(w in h for w in words)

    founding = [(it, d) for it, d in dated if has(it, _FOUNDING)]
    if founding:
        first = min(d for _it, d in founding)
        who = [it for it, d in founding if d == first][0]
        early = [(it, d) for it, d in dated if has(it, _PERFORM) and d < first]
        for it, d in early:
            warns.append(
                f"材料自身矛盾：事项 {it.get('id')}（{_brief(it)}）发生于 "
                f"{_fmt(d)}，早于事项 {who.get('id')}（{_brief(who)}）的 {_fmt(first)} "
                f"—— 履行行为早于设立行为。已照材料画出，请在交付说明中指出")

    by_day = {}
    for it, d in dated:
        by_day.setdefault(d, []).append(it)
    # 已经声明为冲突的，不再报「请核对」—— 那正是它该有的样子。
    # 拿三份跑过的真实地图试过，这条检查报出四处「同日相反主张」，而四处都对：
    # 买卖案的对账单与延期要求、股权案里原告陈述与本院查明说的同一件事。也就是说它
    # 抓出了我自己漏声明冲突的地方。所以判据要把「已声明」排除，否则声明之后还在报，
    # 用户会学会忽略这类告警。
    declared = set()
    for c in (conflicts or []):
        for a in (c.get("members") or []):
            for b in (c.get("members") or []):
                if a != b:
                    declared.add(frozenset((str(a), str(b))))
    for d, group in sorted(by_day.items()):
        lanes = {g.get("lane") for g in group if g.get("lane")}
        ids = [str(g.get("id")) for g in group]
        if len(group) == 2 and frozenset(ids) in declared:
            continue
        if len(group) >= 2 and len(lanes) >= 2 and _is_adversarial(lane_labels, lane_defs):
            warns.append(
                f"同日相反主张：{_fmt(d)} 有 {len(group)} 项分属两方"
                f"（{'、'.join(str(g.get('id')) for g in group)}）—— 请核对是否为对立主张，"
                f"若是则应声明为冲突而不是两件事")
    return warns


def _ymd_of(it):
    d = it.get("date") or it.get("from")
    if not d:
        return None
    try:
        p = [int(x) for x in str(d).replace("-", "/").split("/")[:3]]
        while len(p) < 3:
            p.append(1)
        return tuple(p)
    except Exception:
        return None


def _fmt(t):
    return f"{t[0]}年{t[1]}月{t[2]}日"


def _brief(it):
    return (it.get("head") or "")[:12]


# --------------------------------------------------- 前端：该不该用期间型
#: 具有法律意义的期间。作者定的：期间型的价值在法律含义，不在「有起止日期」。
#: 这张表是判据的核心 —— 不在表里的时段（如「施工的第一阶段」）不构成用期间型的理由。
LEGAL_PERIODS = (
    "诉讼时效", "时效", "保证期间", "担保期间", "除斥期间", "质保期", "缺陷责任期",
    "借款期间", "用款期间", "计息期间", "利息", "罚息", "违约金计算",
    "履行期限", "付款期限", "交付期限", "工期", "顺延", "停工", "迟延", "逾期",
    "租赁期间", "租期", "代持期间", "锁定期", "业绩承诺期", "考核期",
    "审理期间", "举证期限", "上诉期",
    # 与时效有关的那几种说法：真材料里往往不写「诉讼时效」四个字，而是描述那段状态
    # （「未再主张权利」「沉默」「中断」）。实测漏判过「第二次催告后的沉默期间」——
    # 那是诉讼时效里最关键的一段，法律意义很明确，只是不带那四个字。
    "沉默", "未再主张", "未主张", "中断", "中止", "催告", "怠于",
    # 期间的通用后缀：带这些字的多半是在说一段有后果的时间
    "期间", "期限", "起止", "届满",
)


def check_span_worthiness(spans, points=None):
    """判断这份材料**该不该**用期间型，而不只是能不能画。

    作者定的两条：
      一、**法律含义**。期间型有意义是因为那段时间本身有法律后果 —— 诉讼时效、保证期间、
         借款期间、计息起止，以及其他有法律意义的期间。仅仅「有起止日期的一段时间」
         不够：「施工的第一阶段」有起止，但把它画成条，长度不说明任何法律问题。
      二、**起止时间必须明确**。期间型最要紧的就是开始与截止两个时点；缺一个就不是期间，
         是一个时点加一句「此后」。

    以及一条几何上的下限（不是作者定的，是画出来才知道的）：只有一段、或者几段互不重叠时，
    期间型不比编号型多说任何东西 —— 因为它的论点是重叠关系。

    返回 (errs, warns)。errs 表示不该用期间型，应改编号型；warns 表示可以画但要提醒。
    """
    errs, warns = [], []
    if not spans:
        return ["没有任何期间，不该用期间型"], []

    for s in spans:
        sid = s.get("id", "?")
        if not (s.get("from") and s.get("to")):
            errs.append(f"期间 {sid}: 缺起止时间（from / to）—— 期间型最要紧的就是"
                        f"开始与截止两个时点，缺一个就不是期间，"
                        f"而是一个时点加一句「此后」")
        lab = (s.get("label_text") or "") + (s.get("legal_basis") or "")
        if not any(k in lab for k in LEGAL_PERIODS):
            warns.append(f"期间 {sid}「{lab[:16]}」看不出法律意义上的期间 —— "
                         f"期间型的价值在于那段时间本身有法律后果（时效、保证期间、"
                         f"计息起止、工期顺延等）。若只是「一段时间」，编号型更合适")

    if len(spans) < 2:
        errs.append("只有一段期间：期间型的论点是**重叠关系**，一段之间没有重叠可言，"
                    "应改用编号型并把起止写成两个时点")
    else:
        def ymd(x):
            try:
                p = [int(v) for v in str(x).replace("-", "/").split("/")[:3]]
                while len(p) < 3:
                    p.append(1)
                return tuple(p)
            except Exception:
                return None
        iv = [(ymd(s.get("from")), ymd(s.get("to")), s.get("id")) for s in spans]
        iv = [x for x in iv if x[0] and x[1]]
        overlap = any(a[0] < b[1] and b[0] < a[1]
                      for i, a in enumerate(iv) for b in iv[i + 1:])
        if not overlap:
            errs.append("各段期间互不重叠也互不包含：期间型的论点是重叠关系，"
                        "互不重叠时它不比编号型多说任何东西，应改用编号型")
    return errs, warns


# --------------------------------------------------- 前端：该不该用日期型
#: 日期型独有的能力：让读者不看数字就看出「这段等了多久」。
#: 编号型的轴是等距的，五天与五年一样宽；期间型画的是一段时间本身，不是两件事之间的距离。
#: 只有日期型能让**空白本身成为证据**。所以它的用处集中在四类论点上。
DATED_ARGUMENTS = (
    "诉讼时效", "时效", "届满", "超过", "逾期", "迟延", "延误", "拖延",
    "怠于", "沉默", "未再主张", "长期未", "至今未", "多年",
    "同日", "当日", "次日", "同一天", "密集", "连续",
)


def check_dated_worthiness(dates, arguments=""):
    """判断这份材料**该不该**用日期型。

    dates: 按先后排好的日期字符串列表
    arguments: 前端写的一句话，说明这张图要证明什么（用来判断论点是否落在「距离」上）

    判据分两类：**几何的问引擎，语义的在这里判。**

    1. **能不能画（几何）**：交给 `feasible.dated`，它用渲染器的真实常数判定，
       并且刻度格数直接问渲染器。这里不再自己换算。
    2. **该不该画（语义）**：论点要落在距离上。日期齐全只说明**能**按比例画，
       不说明**该**按比例画 —— 一份普通的履约经过就算日期齐全，按比例画也只是把事情
       按天数摊开，那时候编号型更清楚。所以要看论点里有没有「时效、迟延、怠于、
       同日密集」这类字眼。

    **为什么几何那一半不许在这里自己换算。** 原来这里写的是一条换算出来的代理判据：
    「任意连续两段之和不少于两年」，推导是七格年份时每格 115px、同侧两卡要 230px。
    推导本身没错，错在它**只在年刻度那一档成立**，而单位是按跨度选的（跨年 ≥ 3 用年、
    ≤ 120 天用周、其余用月）。实测三处与引擎相反，两个方向都有：

      · 五点跨 23 天（周刻度）：判据说不该用，引擎画得出 —— 周刻度下每天 31.7px，
        最窄一段 95px、同侧 317px，完全排得开。**判据过严，把画得出的图判死。**
      · 三点跨三周：同上。
      · 八点各隔一年：判据说该用，引擎拒绝 —— 八格年份每格只有 100.75px，
        同侧两卡 201.5px，不足 230px。判据只数天数（730 天够了），
        没有考虑**格数一多每格就变窄**。**判据过松，前端写完字才撞回来。**

    这三种情形原来都测不到，因为守卫的四个样本恰好全都一致 —— 又一次
    「样本没让被测的项变化」。现在判据与引擎共用一段代码，方向上不可能再相反。

    另外算过一条但**没有设为门禁**：最长段与最短段之比的上限在 33 到 39 倍之间（几乎与
    时点数无关）。这个数很宽松，意味着「间距悬殊」本身很少是拒绝的理由 —— 而作者说的
    「一个太长一个太短」在几十倍以内其实画得出来，超过几十倍时断代会把空白折掉。
    所以悬殊只报提醒，不拒绝。
    """
    from datetime import date
    errs, warns = [], []

    def ymd(s):
        try:
            p = [int(x) for x in str(s).replace("-", "/").split("/")[:3]]
            while len(p) < 3:
                p.append(1)
            return date(*p)
        except Exception:
            return None

    ds = [d for d in (ymd(x) for x in dates) if d]
    if len(ds) != len(dates):
        errs.append("有时点没有精确日期 —— 日期型要求全部精确，"
                    "否则轴上的距离就是编出来的")
        return errs, warns
    ds.sort()

    # 几何那一半**问契约**（它用渲染器的常数判定，刻度格数直接问渲染器），
    # 不在这里换算第二份。它的理由本身就是机械的，直接转述给前端。
    # 这里的 import 放在函数内：本模块在别处只做文本核验，不该为此拖进四个渲染器。
    import feasible as _F
    _r = _F.dated([d.strftime("%Y/%m/%d") for d in ds])
    if not _r["ok"]:
        errs.append(f"{_r['why']} —— 改用编号型")

    segs = [(ds[i + 1] - ds[i]).days for i in range(len(ds) - 1)]
    if segs:
        lo, hi = min(s for s in segs if s > 0) if any(segs) else 1, max(segs)
        if lo and hi / lo > 33:
            warns.append(f"最长段是最短段的 {hi / lo:.0f} 倍（上限约 33 到 39 倍）—— "
                         f"断代会把中间的空白折掉，但短的那几段仍会挤在一起，"
                         f"请确认这正是你要表达的对比")

    if arguments and not any(k in arguments for k in DATED_ARGUMENTS):
        warns.append("论点里看不出距离本身是不是要点。日期型独有的能力是让空白成为证据"
                     "（时效届满与起诉之间隔了多久、催告后沉默了多久、约定与实际相差多久、"
                     "几件事挤在同一周）。若论点不在距离上，编号型更清楚")
    return errs, warns
