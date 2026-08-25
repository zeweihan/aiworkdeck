#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""强制交互的前两轮：勾材料来源、勾时间段。

**为什么只有这两个维度。** 试过第三个维度「按内容」（按法律期间词表、按时间成簇、按行为词
三种判据都实做过），结论是它做不到互斥 —— 混乱材料里一句话往往同时说几件事（真材料：
「两个月了，验收和付款都没动」同时落进验收、付款、催告三项），而勾选要求互斥，否则勾两项
会把同一句画两遍。所以按内容分从根上不成立，舍弃。

而这两个维度**天然互斥**：一句话只可能出自一份材料，也只可能落在一个时间点上。判据唯一，
所以列出来的表一定对，不会出现「这句到底算哪一项」的争议。

两轮都带「全部」。
"""
import os
import re
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
for _p in (HERE, os.path.join(HERE, "..", "..", "mqc-litigation-visual-redraw", "scripts")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import read_source as R                                            # noqa: E402

#: 一行里的日期。四种写法都要认（横线、斜杠、点、年月日），因为聊天记录、合同、
#: 判决书各用一种。摄入那一层已经保证这些不会被清页码的规则损坏。
_DATE = re.compile(
    r"(?:(19|20)\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})"
    r"|(?<!\d)(\d{2})\s*[.]\s*(\d{1,2})\s*[.]\s*(\d{1,2})(?!\d)")


#: 只到**年月**的时间表述（材料里很常见：「2023 年 11 月再次出现故障」）。
#: 判据要卡得死，否则会把不是日期的东西认成日期 —— 实测必须排除的几种：
#:   · 「质保期为 12 个月」 —— 「个月」前面是数量不是月份，所以月后不许紧跟「个」
#:   · 「合计金额为 262000 元」「20% 的违约金」 —— 所以年份前后不许紧贴数字
#:   · 「（2024）某仲案字第 00000 号」 —— 案号里的年份后面没有「年」字，天然排除
#:   · 「页码范围：11-13」 —— 没有年份，天然排除
#: 与到日的那条正则**互不重叠**：这条要求月后**不是**「数字+日」，
#: 否则「2021年11月9日」会被同时认成年月与年月日两次。
_YM = re.compile(r"(?<!\d)((?:19|20)\d{2})\s*年\s*(\d{1,2})\s*月(?!\s*\d{1,2}\s*日)(?!\s*个)")


def months_in(s):
    """一句话里只到**年月**的时间表述，返回该月的第一天与精度标记。

    为什么要单列一个函数、而不是把它塞进 `dates_in`：`dates_in` 的四个调用方
    （第一轮的日期范围、第二轮的时间段分档、`in_scope` 的时间过筛）要的是
    「这句话大致落在什么时候」，把月精度的时点混进去是对的；而**出图那一头**
    绝不能把「2023 年 11 月」当成 11 月 1 日 —— 那是 check_dates 已经在挡的事
    （判 exact 必须有到日的表述）。两处要的精度不同，所以分开给，让调用方自己选。
    真材料撞出来的：证据目录里「2023 年 11 月再次出现多媒体触摸一体机故障问题」
    完全认不出来，于是第二轮的时间段清单里 **2023 年整年消失**，
    而那正是本案「再次故障」的关键时点，律师勾时间段时根本看不到它。
    """
    out = []
    for m in _YM.finditer(s):
        try:
            out.append(date(int(m.group(1)), int(m.group(2)), 1))
        except Exception:
            continue
    return out


def dates_in(s, month_precision=True):
    """一句话里的全部日期。同一句可能有好几个（合同条款常见）。

    `month_precision=True` 时把只到年月的时间表述也算进来（该月第一天），
    因为这个函数的调用方都是**列清单与过筛**，要的是「大致落在什么时候」。
    出图那一路不走这里，它由 `check_dates` 按四档确定度把关。
    """
    out = []
    for m in _DATE.finditer(s):
        try:
            if m.group(1):
                y, mo, da = int(m.group(1) + m.group(0)[2:4]), int(m.group(2)), int(m.group(3))
                y = int(re.match(r"(?:19|20)\d{2}", m.group(0)).group())
            else:
                y = 2000 + int(m.group(4))
                mo, da = int(m.group(5)), int(m.group(6))
            out.append(date(y, mo, da))
        except Exception:
            continue
    if month_precision:
        out += months_in(s)
    return sorted(out)


def name_of(path, sentences, used):
    """给一份材料起个短名（不超十字）。

    有原名就用原名（去掉扩展名与编号前缀）；同名的加编号，不硬凑不同的名字 ——
    二十份格式一样的催款函，编号加时间加首句比编出二十个名字有用。
    """
    base = os.path.splitext(os.path.basename(path))[0]
    base = re.sub(r"^材料[一二三四五六七八九十\d]+[-_]?", "", base)[:10]
    if not base:
        base = (sentences[0][:10] if sentences else "未命名")
    n = used.get(base, 0) + 1
    used[base] = n
    return base if n == 1 else f"{base} {n}"


def round_one(paths):
    """第一轮：按材料来源列。每份一行，给出短名、句数、日期范围、首句。"""
    used, rows = {}, []
    for p in paths:
        r = R.read_with_blocks(p)
        S = r["sentences"]
        ds = [d for s in S for d in dates_in(s)]
        rows.append({
            "id": len(rows) + 1,
            "path": p,
            "name": name_of(p, S, used),
            "n_sent": len(S),
            "n_dated": len([s for s in S if dates_in(s)]),
            "span": (f"{min(ds)} 至 {max(ds)}" if ds else "无可识别日期"),
            "first": (S[0][:26] if S else ""),
            "sentences": S,
        })
    return rows


def bucket_label(d, step):
    """一个日期落在哪一档上，返回那一档的标签。

    **这是时间档标签的唯一出处。** 抽出来的起因是一个真实的漏：过筛那一头
    （`pipeline.in_scope`）原来拿「标签里含不含这个年份」当判据，而季度标签里本来就写着
    年份 —— 于是勾了「2024 年第 1 季度」，2024 年第 3 季度的句子照样进来，季度这一档
    形同虚设。而跨度不到两年才按季度列，也就是说第二轮唯一真正需要它生效的那一档，
    它没生效。判据太宽，与「下一行以数字开头」那次是同一类错。
    """
    if step == "quarter":
        return f"{d.year} 年第 {(d.month - 1) // 3 + 1} 季度"
    if step == "year":
        return f"{d.year} 年"
    return f"{d.year - (d.year % 2)} 至 {d.year - (d.year % 2) + 1} 年"


def step_of(label):
    """从标签反认粒度。标签的形状唯一决定粒度，所以这不是猜：
    带「季度」的只可能是季度档，带「至」的只可能是两年档，其余是年档。"""
    if "季度" in label:
        return "quarter"
    if "至" in label:
        return "two-year"
    return "year"


def in_bucket(d, label):
    """这个日期在不在这一档里。

    判据是**照同一个函数算出来的标签是不是它** —— 不另写一套「把标签解析成起止区间
    再比大小」的算法。同一个东西的第二种算法要复用第一种，这个项目在叙述块段号换句号、
    日期型刻度格数上已经各栽过一次。
    """
    return bucket_label(d, step_of(label)) == label


def round_two(rows, picked_ids=None):
    """第二轮：按时间列。

    粒度**由跨度决定**，不写死：跨度不到两年按季度列（否则一年一行看不出细节），
    两到六年按年列，超过六年按两年一档列（否则行数太多，勾选变负担）。
    """
    picked = rows if not picked_ids else [r for r in rows if r["id"] in picked_ids]
    ds = sorted(d for r in picked for s in r["sentences"] for d in dates_in(s))
    if not ds:
        return [], "无可识别日期，时间这一轮跳过"
    lo, hi = ds[0], ds[-1]
    years = (hi - lo).days / 365.0
    if years < 2:
        step, label = "quarter", "按季度"
    elif years <= 6:
        step, label = "year", "按年"
    else:
        step, label = "two-year", "按两年"
    buckets = {}
    for d in ds:
        key = bucket_label(d, step)                  # 标签只有一个出处
        buckets[key] = buckets.get(key, 0) + 1
    rows2 = [{"id": i + 1, "label": k, "n": v}
             for i, (k, v) in enumerate(sorted(buckets.items()))]
    # 跨度那句话给用户看，所以直读，不印内部档位名（原来印的是「按季度列（…）」，
    # 把内部说法漏给了用户，读起来不通）。
    if years < 0.05:
        span_note = f"全部落在 {lo} 这一天"
    elif years < 1:
        span_note = f"全部跨度 {lo} 至 {hi}，约 {round(years * 12)} 个月"
    else:
        span_note = f"全部跨度 {lo} 至 {hi}，约 {years:.1f} 年"
    return rows2, span_note


#: 日期少到这个数以下时，第二轮不问 —— 实测出来的：日期数少于 8 时，
#: 不论分出几档，每档只有一两个日期，让人从里面挑没有意义（作者指出的问题）。
SPAN_ASK_MIN = 8


def span_worth_asking(rows2, n_dates):
    """第二轮值不值得问。返回 (要不要问, 不问时给用户的那句话)。"""
    if len(rows2) <= 1:
        return False, "材料只覆盖一小段时间"
    if n_dates < SPAN_ASK_MIN:
        return False, f"材料里只有 {n_dates} 个日期"
    return True, ""


def show(paths, picked_ids=None):
    r1 = round_one(paths)
    print(f"第一轮 · 制作图表的材料来源（{len(r1)} 份）")
    print()
    for r in r1:
        print(f"  [{r['id']}] {r['name']}")
        print(f"      {r['n_sent']} 句，其中 {r['n_dated']} 句带日期　{r['span']}")
        print(f"      首句：{r['first']}")
        print()
    print("  [全部] 全部材料")
    print()
    r2, note = round_two(r1, picked_ids)
    print(f"第二轮 · 时间段（{note}）")
    print()
    for r in r2:
        print(f"  [{r['id']}] {r['label']}　{r['n']} 个日期")
    print()
    print("  [全部] 全部时间")
    return r1, r2


if __name__ == "__main__":
    show(sys.argv[1:])


# --------------------------------------------------- 第三轮：风格
#: **照抄 v1 的那一问**（作者定的）。v1 的写法是「奇川风推荐、多数场合通用」，
#: 不回默认第一档；而不是按受众分三档、把白描配给法官 —— 后者是 v2 先前的写法，
#: 实际上白描只在「必须纯黑白」时才用，把它设成法官那一档是过度分流。
#: 三段描述与用途逐字取自 v1 的 checkpoint 输出，不是我编的。
STYLES = [
    {"id": 1, "name": "奇川风", "tag": "推荐",
     "look": "宋体标题 · 灰阶分层 · 单一深红点睛",
     "use": "多数场合通用 —— 呈报法庭、交当事人、内部办案皆可"},
    {"id": 2, "name": "歸藏风", "tag": "",
     "look": "克莱因蓝 · 浅灰点阵底 · 无衬线 · 直角发丝线",
     "use": "对外传播 —— 线上发布、课件、分享"},
    {"id": 3, "name": "白描", "tag": "",
     "look": "纯黑白线稿 · 实心块转白底框线 · 近直角",
     "use": "须为纯黑白时 —— 打印、影印、卷宗附件"},
]


def round_three():
    """第三轮：风格。不回默认第一档（奇川风）。"""
    return STYLES


def round_four(events, style="奇川风"):
    """第五轮：标红哪一项（**只有奇川风有这一轮**）。

    编号沿用管线对用户的叫法（prompt 里印的是「第五轮 · 重点」）——
    这里原来写「第四轮」，与 pipeline 打给用户看的字不一致。

    白描是单色，红的会被变换成黑白，标了等于没标；歸藏风有自己的一套视觉表面，
    重点由它自己的规则处理。所以这一轮对另外两种风格不出现 —— 问了也没有用的选项，
    比不问更糟。

    候选项从事项里挑，不由我判断哪个「最重要」（那是法律判断，超出边界）：
    列出全部事项让他勾，另给一个「让 AI 定」。
    """
    if style != "奇川风":
        return [], f"{style} 不需要这一轮（单色或自有视觉规则）"
    rows = [{"id": i + 1, "text": (e.get("head") or "")[:24],
             "date": (e.get("time") or {}).get("date_text", "")}
            for i, e in enumerate(events)]
    return rows, "标红哪一项（可不选、可让 AI 定；最多标一项，多标就不是重点了）"
