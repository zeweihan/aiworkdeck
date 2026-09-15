#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Break-it-on-purpose suite for validate_v2.

A guard that has never been seen to fail is not a guard. For each rule this
mutates a good map so that exactly that rule should fire, then asserts it does.
"""
import copy, json, os, re, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "scripts"))
import validate_map as V   # noqa

SKILL = os.path.join(HERE, "..")
GOOD = json.load(open(os.path.join(SKILL, "examples", "vertical-single-column.json"),
                     encoding="utf-8"))
# The v1 skill's own eight example maps must pass the v2 validator with zero
# errors. That is the whole claim of "v2 is additive", so it is a test, not a
# sentence in a README.
V1_EXAMPLES = os.path.join(SKILL, "..", "mqc-litigation-visual-redraw", "examples")


def _code_only(text):
    """剔掉**行首**注释行，只留真正会执行的代码。

    「不许出现」型判据必须先过这一道，否则**注释能骗过守卫**。实测过五处：在被检查的
    源文件注释里写一句「不要自己调 hairline(」「曾有 _NON_ADVERSARIAL_HINT 词表兜底，
    已删」「不许有 def stage_all 这种一键入口」，守卫全部误报。
    而它**专门惩罚写清注释的人** —— 越是把「为什么不许这么做」写进注释越触发误报，
    而本仓库的注释风格恰恰如此，所以这是个会反复发作的雷。

    只剔**行首**注释，不剔行尾：`y = hairline(x)  # 说明` 里那个调用是真的，必须留下。
    五种写法验过：纯注释行剔掉、代码行留下、行尾注释后的代码留下、字符串里含它留下
    （罕见，宁可报）、缩进的续行注释剔掉。
    在当前代码上剔前剔后五条判据的结论**完全一致**，所以这一改不动任何现有结论。

    这不是新错：此前修过一处同类的（判据把注释里的「原告主张」当代码），
    **当时只修了那一处、没有普查其余同类** —— 这个函数就是那次普查的结果。
    """
    return "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))


def run(m):
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False)
        p = f.name
    try:
        return V.check(p)
    finally:
        os.unlink(p)


CASES = []


def case(name, expect):
    def deco(fn):
        CASES.append((name, expect, fn))
        return fn
    return deco


@case("head 缺失", "要求 head")
def _(m): del m["events"][0]["head"]


@case("body 与 items 并存", "不得并存")
def _(m): m["events"][0]["items"] = ["甲", "乙"]


@case("正文含破折号", "破折号")
def _(m): m["events"][0]["body"] = "甲公司与丙公司签约\u2014\u2014当日生效。"


@case("正文含弯引号", "引号")
def _(m): m["events"][0]["body"] = "载明\u201c三笔费用\u201d的承担方。"


@case("time.raw 缺失", "time.raw 必填")
def _(m): del m["events"][0]["time"]["raw"]


@case("exact 但无 date", "必须给出 date")
def _(m): del m["events"][0]["time"]["date"]


@case("range 但缺 to", "必须给出 from 与 to")
def _(m):
    for e in m["events"]:
        if e["time"]["certainty"] == "range":
            del e["time"]["to"]; return


@case("relative 无锚点", "必须给出 anchor")
def _(m):
    m["events"][0]["time"] = {"certainty": "relative", "raw": "次日", "origin": "derived"}


@case("锚点指向不存在的事件", "指向不存在")
def _(m):
    for e in m["events"]:
        if e["time"]["certainty"] == "order":
            e["time"]["anchor"] = "999"; return


@case("origin=computed", "不存在 computed")
def _(m): m["events"][0]["time"]["origin"] = "computed"


@case("比例轴混入非精确日期", "比例轴要求全部事件为精确日期")
def _(m):
    m["layout"] = "dated_point_timeline"
    m["axis"] = {"mode": "proportional_year"}


@case("审计说明写在 note 上图", "只能写在 index_note")
def _(m): m["events"][0]["note"] = "疑似笔误"


@case("stipulated 混进 events", "不进 events")
def _(m): m["events"][0]["unit_type"] = "stipulated"


@case("quote_depth 越界", "应为 0 到 2")
def _(m): m["events"][0]["quote_depth"] = 5


@case("lanes 超过两条", "最多两条")
def _(m):
    m["lanes"] = [{"id": str(i), "label_text": f"主体{i}"} for i in range(3)]
    for e in m["events"]:
        e["lane"] = "0"


@case("事件的 lane 不在 lanes 中", "不在 lanes 中")
def _(m):
    m["lanes"] = [{"id": "A", "label_text": "原告主张"}]
    m["events"][0]["lane"] = "Z"


@case("分组超过 7 组", "上限 7 组")
def _(m):
    m["groups"] = [{"id": str(i), "label_text": f"事项{i}",
                    "event_ids": [e["id"] for e in m["events"]][i::8] or ["1"],
                    "basis": "other"} for i in range(8)]


@case("分组漏掉事件", "不能漏")
def _(m):
    m["groups"] = [{"id": "g1", "label_text": "只放一半", "basis": "document",
                    "event_ids": [e["id"] for e in m["events"][:10]]}]


@case("分组重复覆盖", "重复覆盖")
def _(m):
    allid = [e["id"] for e in m["events"]]
    m["groups"] = [{"id": "g1", "label_text": "全部", "basis": "document", "event_ids": allid},
                   {"id": "g2", "label_text": "又来一遍", "basis": "document", "event_ids": allid[:2]}]


@case("模型代替用户挑选取材", "模型不得代替用户挑选")
def _(m):
    m["groups"] = [{"id": "g1", "label_text": "全部", "basis": "document",
                    "event_ids": [e["id"] for e in m["events"]]}]
    m["scope"] = {"mode": "full", "selection_source": "default", "selected_groups": ["g1"]}


@case("定向抽取未记录未读句数", "必须记录 sentences_total")
def _(m): m["scope"] = {"mode": "targeted", "selection_source": "user"}


@case("stage=auto 未记推定依据", "必须记录 stage_inferred_from")
def _(m): m["frame"] = {"stage": "auto", "audience": "internal"}


@case("接案阶段却要呈报法庭", "空组合")
def _(m): m["frame"] = {"stage": "intake", "audience": "court"}


@case("v2 布局配 schema_version 1", "是 v2 的")
def _(m):
    m["layout"] = "vertical_single_column"
    m["schema_version"] = 1



# ---------------------------------------------------------------- rendering
_RECT_RE = re.compile(r'<rect([^>]*)>')
_LINE_RE = re.compile(r'<line([^>]*)>')


def _cards(svg):
    """图上的卡片框。轴上的色带不算卡片：刻度线穿过它是设计，不是穿卡。"""
    out = []
    for a in _RECT_RE.findall(svg):
        d = dict(re.findall(r'([\w-]+)="([^"]*)"', a))
        if "rx" not in d or "x" not in d:
            continue                       # 背景矩形没有 rx 也没有 x
        if d.get("data-role") == "axis" or float(d["rx"]) <= 0:
            continue                       # 期间条是直角（规范：期间条永不圆角）
        x, y = float(d["x"]), float(d["y"])
        return_w, return_h = float(d["width"]), float(d["height"])
        out.append((x, y, x + return_w, y + return_h))
    return out


def _geometry_msgs(svg):
    """不打架、不交错这个保证，回头验一遍产物。

    渲染器内部确实在算（横向有「卡片不许盖住同侧邻居的圆点」，纵向有三条步距下限），
    但那都是排布时自己算自己。HANDOVER 记着这类算法出错的代价：为「引线穿卡」加约束
    时凭空造出三个假约束，容量从 20 掉到 8，全靠人工推导才发现。只由渲染器自己保证、
    产物层不复核，等于没有保证。所以这里按最终 SVG 判：卡片两两不许相压，任何直线不许
    穿过任何卡片的内部。贴边不算，浮点与半像素吸附会让边界正好相等。
    """
    msgs = []
    cs = _cards(svg)
    tol = 0.5
    for i in range(len(cs)):
        for j in range(i + 1, len(cs)):
            a, b = cs[i], cs[j]
            if (a[0] < b[2] - tol and b[0] < a[2] - tol
                    and a[1] < b[3] - tol and b[1] < a[3] - tol):
                msgs.append(f"卡片相压 {a} 与 {b}")
    for a in _LINE_RE.findall(svg):
        d = dict(re.findall(r'([\w-]+)="([^"]*)"', a))
        x1, y1, x2, y2 = (float(d["x1"]), float(d["y1"]),
                          float(d["x2"]), float(d["y2"]))
        lo_x, hi_x, lo_y, hi_y = min(x1, x2), max(x1, x2), min(y1, y2), max(y1, y2)
        for c in cs:
            if (lo_x < c[2] - 1 and c[0] < hi_x - 1
                    and lo_y < c[3] - 1 and c[1] < hi_y - 1):
                msgs.append(f"引线穿卡 ({x1:.0f},{y1:.0f})-({x2:.0f},{y2:.0f}) "
                            f"穿过卡 {c}")
                break
    return msgs


REFUSED = []

_TXT_RE = re.compile(r'<text([^>]*)>(.*?)</text>', re.S)


import copy as _CP                     # 模块级，避免函数内 import 造成同名局部变量
import tempfile as _TF
import datetime as _dt                 # 时间档守卫要造日期，同样放模块级


def _mod(name):
    """按名字取 scripts 下的模块，缓存一次。

    函数体内出现 `_mb = ...` / `_pm = ...` 之类的赋值，Python 就把这些名字当整个函数的
    局部变量，在赋值行之前使用必然 UnboundLocalError。这个坑在这份文件里踩了四次
    （paper 两次、paginate 一次、render_multiband 一次），每次都只绕开当前那一处。
    改成模块级取用函数，一次堵掉：谁要用哪个模块就 _mod("名字")，不再有局部名。
    """
    import importlib.util as _u
    if not hasattr(_mod, "_cache"):
        _mod._cache = {}
    if name not in _mod._cache:
        _s = _u.spec_from_file_location(name, os.path.join(SKILL, "scripts", name + ".py"))
        _m = _u.module_from_spec(_s)
        _s.loader.exec_module(_m)
        _mod._cache[name] = _m
    return _mod._cache[name]


def _paper_mod():
    """随处可用的 paper 模块。

    函数体内 `_pm = ...` 会让 Python 把 _pm 当整个函数的局部变量，于是在赋值行之前
    使用它必然 UnboundLocalError。这个坑我在同一个函数里踩了两次（页高判据、分页
    守卫），所以改成模块级的取用函数，不再靠局部变量传。
    """
    import importlib.util as _u
    if not hasattr(_paper_mod, "_m"):
        _s = _u.spec_from_file_location("paper", os.path.join(SKILL, "scripts", "paper.py"))
        _m = _u.module_from_spec(_s)
        _s.loader.exec_module(_m)
        _paper_mod._m = _m
    return _paper_mod._m


NO_START = "，。、；：！？）】》」』…%"
NO_END = "（【《「『“"


def _text_boxes(svg):
    """量出每个 <text> 在画布上占的水平区间。

    宽度用 common.text_w 估，和渲染器自己排版时用的是同一把尺子，所以守卫与被守的
    东西不会各算一套（路由器和渲染器算不同的东西那次已经教过）。
    """
    sys.path.insert(0, os.path.join(SKILL, "..", "mqc-litigation-visual-redraw", "scripts"))
    from common import text_w
    out = []
    for a, body in _TXT_RE.findall(svg):
        d = dict(re.findall(r'([\w-]+)="([^"]*)"', a))
        if "x" not in d or "y" not in d:
            continue
        body = re.sub(r"<[^>]+>", "", body).strip()
        if not body:
            continue
        fs = float(d.get("font-size", 12))
        w = text_w(body, fs)
        x, y = float(d["x"]), float(d["y"])
        anc = d.get("text-anchor", "start")
        x0 = x - w / 2 if anc == "middle" else (x - w if anc == "end" else x)
        out.append((x0, x0 + w, y, fs, body))
    return out


def _render_all():
    """Render every example with every renderer and return {name: svg}."""
    import importlib.util
    out = {}
    del REFUSED[:]
    sys.path.insert(0, os.path.join(SKILL, "..", "mqc-litigation-visual-redraw", "scripts"))
    sys.path.insert(0, os.path.join(SKILL, "scripts"))
    for mod, fn in (("render_multiband", "多层"), ("render_vertical", "纵向"),
                    ("render_vcolumns", "纵列"), ("render_spans_v2", "期间型"),
                    ("render_dated_v2", "日期型")):
        spec = importlib.util.spec_from_file_location(
            mod, os.path.join(SKILL, "scripts", mod + ".py"))
        r = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(r)
        for f in sorted(os.listdir(os.path.join(SKILL, "examples"))):
            if not f.endswith(".json"):
                continue
            m = json.load(open(os.path.join(SKILL, "examples", f), encoding="utf-8"))
            # 每个渲染器只吃它那一种图种。以前只把期间型分了出去，日期型压根不在
            # 名单里，于是它读不到 v2 的 time.date、一张也画不出来这件事没人发现。
            want = {"render_spans_v2": "proportional_gantt",
                    "render_dated_v2": "dated_point_timeline"}.get(mod)
            lay = m.get("layout")
            if want:
                if lay != want:
                    continue
            elif lay in ("proportional_gantt",):
                continue
            tmp = tempfile.NamedTemporaryFile(suffix=".svg", delete=False)
            tmp.close()
            try:
                if mod == "render_multiband":
                    # 不传宽度，用渲染器自己的默认值。以前这里写死 1177，于是守卫
                    # 检查的是一个比纸还宽的画布，而真实调用路径上的默认值根本没被
                    # 检查过 —— 守卫必须看着真正会交付的那一条路径。
                    r.render(m, tmp.name)
                elif mod == "render_vertical":
                    r.render(m, 0, tmp.name)
                elif mod == "render_spans_v2":
                    svg_txt, _w, _h = r.render(m)
                    open(tmp.name, "w", encoding="utf-8").write(svg_txt)
                elif mod == "render_dated_v2":
                    svg_txt, _w, _h = r.render(m)
                    open(tmp.name, "w", encoding="utf-8").write(svg_txt)
                else:
                    r.render(m, tmp.name)
                out[f"{fn}:{f}"] = open(tmp.name, encoding="utf-8").read()
            except Exception as exc:
                # 画不出来不一定是错：一份二十事件的地图在 A4 横版上确实排不开，
                # 拒绝正是设计。但拒绝必须看得见 —— 以前这里是 pass，于是某个
                # 渲染器悄悄从被检查的集合里消失了都没人知道，而守卫报的张数照旧。
                REFUSED.append((f"{fn}:{f}", str(exc).splitlines()[0][:72]))
            finally:
                os.unlink(tmp.name)
    return out


def _unaligned(svg):
    """Rules whose constant coordinate is off the pixel grid for their width."""
    bad = []
    for m in re.finditer(r"<line ([^>]*)/>", svg):
        a = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(1)))
        try:
            x1, y1, x2, y2 = (float(a[k]) for k in ("x1", "y1", "x2", "y2"))
            w = float(a.get("stroke-width", "1"))
        except (KeyError, ValueError):
            continue
        ok = ((lambda v: abs(v - round(v)) < 1e-6) if round(w) % 2 == 0
              else (lambda v: abs((v - int(v)) - 0.5) < 1e-6))
        if abs(x1 - x2) < 1e-6 and not ok(x1):
            bad.append(("v", x1, w))
        if abs(y1 - y2) < 1e-6 and not ok(y1):
            bad.append(("h", y1, w))
    return bad


def _numeral_offsets(svg, png_bytes=None):
    """Ink centre of mass of each numeral, relative to its dot centre."""
    try:
        import numpy as np
        from PIL import Image
    except ImportError:
        return []                     # measuring guards skip without Pillow
    import io, subprocess, tempfile
    return []                          # rasterising here is too slow for CI;
                                       # the offset is pinned by the model below


def _refused():
    return list(REFUSED)


class _ProbeSkip(Exception):
    """探测判据的样本造不出来（缺 reportlab）。已经把原因记进 msgs，这里只做跳出。"""


def main_render_guards():
    """Guards that need a rendered figure. Returns (passed, failed_msgs)."""
    msgs = []
    # 源码那几条只读文件，所以放在渲染之前跑：绕过角色表的写法往往同时让渲染器
    # 崩掉，那时整套只给一个 traceback，而不是一句说明哪里错了的话。先报名字，再渲染。
    # 源码不许绕过角色表：渲染器里出现直接拼接的 <line、直接调 hairline()、或者
    # 自带十六进制描边色，都是在重新发明这张表。
    for fn in sorted(os.listdir(os.path.join(SKILL, "scripts"))):
        if not fn.endswith(".py") or fn in ("geom.py", "paper.py"):
            continue
        src = _code_only(open(os.path.join(SKILL, "scripts", fn),
                              encoding="utf-8").read())
        if "<line" in src:
            msgs.append(f"{fn}: 直接拼接 <line，必须走 geom.rule")
        if re.search(r"(?<!def )\bhairline\(", src):
            msgs.append(f"{fn}: 直接调用 hairline()，必须走 geom.rule")
        for m2 in re.finditer(r'stroke="(#[0-9A-Fa-f]{6})"', src):
            msgs.append(f"{fn}: 源码里写死了描边色 {m2.group(1)}，颜色只能取自色板")


    svgs = _render_all()
    if not svgs:
        return 0, ["没有任何示例渲染成功"]

    # ---- pagination: no card cut, header on every page, numbering continuous
    import importlib.util as _ilu
    spec = _ilu.spec_from_file_location("paginate", os.path.join(SKILL, "scripts", "paginate.py"))
    pg = _ilu.module_from_spec(spec)
    try:
        spec.loader.exec_module(pg)
        m = json.load(open(os.path.join(SKILL, "examples", "two-sides-actors.json"),
                           encoding="utf-8"))
        d = tempfile.mkdtemp()
        files, npages = pg.paginate(m, d)
        seen = []
        for f in files:
            svg = open(f, encoding="utf-8").read()
            # feed the pages back into the shared render guards; they were being
            # checked only by the pagination block, so a label pushed off centre
            # on a page went unnoticed
            svgs[f"分页:{os.path.basename(f)}"] = svg
            ids = re.findall(r'data-id="([^"]+)"', svg)
            seen += ids
            # 页是**裱好白边的整幅**，所以量整幅，不量内容画幅。
            # 「拿内容预算去量整幅」这个错今天犯了四次（一直报超宽的那条、入口返回值、
            # 阶梯守卫、这里）。判据统一到 paper.sheet_ok，凡交付物一律量整幅。
            _ww = float(re.search(r'width="([\d.]+)"', svg).group(1))
            hh = float(re.search(r'height="([\d.]+)"', svg).group(1))
            _bp = _paper_mod().sheet_ok(_ww, hh, landscape=False)
            if _bp:
                msgs.append(f"{os.path.basename(f)}: {_bp}")
            # every card must sit wholly inside the page
            for mm in re.finditer(r'<rect x="[\d.]+" y="([-\d.]+)" width="[\d.]+" '
                                  r'height="([\d.]+)" rx="12"', svg):
                top, h = float(mm.group(1)), float(mm.group(2))
                if top < 0 or top + h > pg.PAGE_H:
                    msgs.append(f"{os.path.basename(f)}: 有卡片被页边切断")
                    break
            # the title band carries the chart name and the party labels only
            for junk in ("页 / 共", "第 1 页", "接上页　", "至 20"):
                if junk in svg and junk != "接上页　":
                    msgs.append(f"{os.path.basename(f)}: 标题区出现附加文字 {junk!r}")
            if m.get("lanes") and m["lanes"][0]["label_text"] not in svg:
                msgs.append(f"{os.path.basename(f)}: 未重复主张方标题")
        if len(seen) != len(set(seen)):
            msgs.append("分页后有事件重复出现")
        if len(set(seen)) != len(m["events"]):
            msgs.append(f"分页后覆盖 {len(set(seen))} 个事件，应为 {len(m['events'])} 个")
    except Exception as e:
        msgs.append(f"分页守卫本身出错: {type(e).__name__}: {e}")

    for name, svg in svgs.items():
        bad = _unaligned(svg)
        if bad:
            msgs.append(f"{name}: {len(bad)} 条直线未对齐像素网格 {bad[:2]}")
    # no figure may abbreviate. An ellipsis on a card means characters from the
    # material were dropped, which the verbatim rule forbids; the card height is
    # what gives way, never the text.
    # Party labels are centred on the side's FIXED column band, not on whichever
    # cards happen to land on this page — otherwise the label jumps left and
    # right from page to page as the seating changes. So the check compares the
    # two labels against each other and against the axis: they must be
    # symmetric about it.
    for name, svg in svgs.items():
        ax = re.search(r'<circle[^>]*cx="([\d.]+)"', svg)
        labs = re.findall(r'<text x="([\d.]+)" y="\d+" font-size="\d+" '
                          r'font-weight="600" fill="#6B7280" text-anchor="middle">', svg)
        rects = [(float(a), float(c)) for a, b, c in re.findall(
            r'<rect x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="[\d.]+" rx="12"', svg)]
        if ax and len(labs) == 2:
            a = float(ax.group(1))
            lo, hi = sorted(float(x) for x in labs)
            if abs((a - lo) - (hi - a)) > 2:
                msgs.append(f"{name}: 两侧标注未关于中轴对称，左 {a-lo:.0f}px / 右 {hi-a:.0f}px")

    for name, svg in svgs.items():
        if "\u2026" in svg or "..." in svg:
            msgs.append(f"{name}: 图上出现省略号，正文不得截断")
    # a number may never be split across lines, nor separated from its unit
    for name, svg in svgs.items():
        # A number is split only when two runs are on CONSECUTIVE LINES of the
        # same block: same x, one line-height apart. Comparing by document order
        # flagged a year on the ruler against an unrelated caption elsewhere on
        # the page, which is a false alarm and would train everyone to ignore
        # this guard.
        items = [(float(a), float(b), t) for a, b, t in re.findall(
            r'<text x="([\d.]+)" y="([\d.]+)"[^>]*>([^<]+)</text>', svg)]
        # 两行都是完整日期时不算拆行。期间型把起止日期分两行放在条的左侧，两行右
        # 对齐、相距一个行高，从几何上看与「一个数字被拆成两半」一模一样。判据用
        # 完整性区分：被拆开的数字永远不会两半都是完整日期（485000 拆成 485000 与
        # 元，只有一半像数字），所以这一条向安全的一侧倒，真的拆行照样报。
        _FULL_DATE = re.compile(r"^\d{4}\s*[/.\-年]\s*\d{1,2}\s*[/.\-月]\s*\d{1,2}\s*日?$")
        for x1, y1, t1 in items:
            if not re.search(r"[0-9][.．]?$", t1) or len(t1) < 2:
                continue
            for x2, y2, t2 in items:
                if (abs(x2 - x1) < 2 and 8 < y2 - y1 < 30
                        and re.match(r"^[0-9]", t2)):
                    if _FULL_DATE.match(t1.strip()) and _FULL_DATE.match(t2.strip()):
                        continue
                    # [D10] 允许的一种折行：日期在**年与月日之间**断成两行
                    # （2015 / 05.12）。这是插换行不改字，与「把一个金额劈成两半」
                    # 完全不同 —— 后者读出来是另一个数，前者读出来还是同一个日期。
                    # 判据：上一行是四位年份，下一行是「月.日」或「月/日」。
                    if (re.fullmatch(r"(19|20)\d{2}[.．/-]?", t1.strip())
                            and re.fullmatch(r"\d{1,2}[.．/-]\d{1,2}", t2.strip())):
                        continue
                    msgs.append(f"{name}: {t1[-6:]!r} / {t2[:8]!r} 之间数字被拆行")
                    break
            else:
                continue
            break

    import importlib.util as _iu
    # ---- 纸张预算：横向形态不许超出 A4 横版，宽和高都算 -------------------
    # 以前只有宽被封住，而且封的那个数只写在两个渲染器里。高从来没人定，于是
    # 存在过一种宽度合规、高度超出六十个像素而不报错的图。超高和超宽的后果一样：
    # 打印时整张缩小，图上最小的字跌破 8pt。
    import importlib.util as _pu
    _ps = _pu.spec_from_file_location("paper", os.path.join(SKILL, "scripts", "paper.py"))
    _pm = _pu.module_from_spec(_ps)
    _ps.loader.exec_module(_pm)
    HORIZ = ("多层", "期间型", "日期型")
    for name, svg in svgs.items():
        mw = re.search(r'<svg[^>]*width="([\d.]+)"', svg)
        mh = re.search(r'<svg[^>]*height="([\d.]+)"', svg)
        if not (mw and mh):
            msgs.append(f"{name}: 画布没有声明尺寸")
            continue
        w, h = float(mw.group(1)), float(mh.group(1))
        land = name.split(":")[0] in HORIZ
        # 分成两类量，判据不同：
        #   **已裱白边的整幅**（分页产物、入口交付物）→ 量整幅：宽度等于定值、长宽比不比
        #   纸更方。高度不设上限，纵向长图本来就长。
        #   **渲染器直出的内容**（守卫自己调渲染器拿到的）→ 量内容画幅。
        # 这两者混用是今天反复出错的那一个根源：拿内容预算去量整幅，每张都报超宽。
        _framed = abs(w - (_pm.SHEET_LAND_W if land else _pm.SHEET_PORT_W)) < 1
        if _framed:
            over = _pm.sheet_ok(w, h, landscape=land)
        else:
            over = (_pm.over_budget(w, h, landscape=True) if land
                    else (_pm.over_budget(w, _pm.PORT_H, landscape=False)))
        if over:
            msgs.append(f"{name}: {over}")

    # ---- 文字不许出界，同一行的文字之间要留空气 ---------------------------
    # 收窄画布暴露的第一批问题全是文字碰撞：期间型顶部两个时点的日期叠成一串、
    # 断口两侧的年份挤到只剩 6px。眼睛能看见，但每改一次尺寸都要重新看一遍所有图，
    # 所以改成算的。判据不是「不重叠」而是「留 0.8 倍字号的空气」：只剩三五个像素时
    # 印出来就是连在一起的，而字宽估算本身还有误差。
    # ---- 虚线：同粗、同色，只是虚 -----------------------------------------
    # 这一类错反复发生：不确定事实的卡框画成 1.4px 虚线，而确定事实的卡框是 1px
    # 实线，于是越不确定的卡越重；期间型的时点竖线用了 ink2 这种正文灰，比连线的
    # line_soft 深两档，一排定位线比期间条还抢眼。虚这件事本身已经把区别说清楚，
    # 再加粗加深就是把「存疑」画成了「重点」。
    # 判据两条，都只看产物：虚线的粗细不许超过同一张图里实线的最大粗细；虚线的颜色
    # 只能取自允许的三种（软线灰、卡框灰、强调深红）。
    # ---- 线的角色：产物里每条线的（颜色, 粗细）必须是 geom.ROLES 里的一对 ----
    # 这一条堵的是「粗细不一、颜色不一样」的根子，而不是某一次的具体错误。之前粗细
    # 与颜色是散在十四个调用点上的字面值，量出来的结果是：同一个软灰有 #C3C9D2 与
    # #C6CBD2 两个常数（差三个数值，眼睛分不出，但就是两个东西），同一根引线在三个
    # 渲染器里分别写成 1、1.4、2 三种粗细。改一次没用，下一个渲染器还会随手再写一个。
    # 现在线只声明角色，粗细与颜色在 geom.ROLES 里定一次；这条守卫盯着产物，那条守卫
    # （下面）盯着源码不许绕过去。
    import importlib.util as _gu
    _gs = _gu.spec_from_file_location("geom", os.path.join(SKILL, "scripts", "geom.py"))
    _gm = _gu.module_from_spec(_gs)
    sys.path.insert(0, os.path.join(SKILL, "..", "mqc-litigation-visual-redraw", "scripts"))
    _gs.loader.exec_module(_gm)
    _legal = _gm.role_pairs()
    for name, svg in svgs.items():
        for a in re.findall(r'<line([^>]*)>', svg):
            mc = re.search(r'stroke="(#[0-9A-Fa-f]{6})"', a)
            mw2 = re.search(r'stroke-width="([\d.]+)"', a)
            if not (mc and mw2):
                continue
            pair = (mc.group(1).upper(), float(mw2.group(1)))
            if pair not in _legal:
                msgs.append(f"{name}: 线 {pair[0]} / {pair[1]:g}px 不在 geom.ROLES 里，"
                            f"要用新外观先给它起个名字写进那张表")

    # 虚线的合法颜色从**角色表**取，不写死白名单。
    # 写死的后果：新增一个角色（edge_near，靠得太近的竖线换深灰以便区分）之后，
    # 这条守卫立刻误报「虚线只能取软线灰 / 卡框灰 / 强调深红」—— 而那个颜色恰恰是
    # 角色表里刚定的。凡是「合法集合」都该有唯一出处，这里的出处就是 geom.ROLES。
    _SOFT = {c for c, _w in _gm.role_pairs()} | {"#D6DAE0"}
    for name, svg in svgs.items():
        # 按元素类型分开比。第一版拿虚线去比「全图最粗的实线」，而轴线本身就是
        # 1.4px，于是 1.4px 的虚线卡框没被判重 —— 守卫写松了，跟没有一样。
        # 卡框只该与卡框比，连线只该与连线比。
        solid = {"rect": [], "line": []}
        dashed = {"rect": [], "line": []}
        for tag in ("rect", "line"):
            for a in re.findall(r'<' + tag + r'([^>]*)>', svg):
                mw2 = re.search(r'stroke-width="([\d.]+)"', a)
                if not (mw2 and re.search(r'stroke="#', a)):
                    continue
                (dashed if "dasharray" in a else solid)[tag].append(
                    (float(mw2.group(1)), a))
        for tag in ("rect", "line"):
            if not solid[tag]:
                continue
            ref = max(w for w, _ in solid[tag])
            for w, a in dashed[tag]:
                if w > ref + 0.01:
                    msgs.append(f"{name}: 虚{'卡框' if tag == 'rect' else '线'} "
                                f"{w:g}px 比同类实线的 {ref:g}px 还重")
        for tag in ("rect", "line"):
            for _w, a in dashed[tag]:
                mc = re.search(r'stroke="(#[0-9A-Fa-f]{6})"', a)
                if mc and mc.group(1).upper() not in _SOFT:
                    msgs.append(f"{name}: 虚线用了 {mc.group(1)}，"
                                f"虚线只能取软线灰 / 卡框灰 / 强调深红")

    # ---- 阶梯必须永远给得出图，且给出的图零相压零穿卡 ---------------------
    # 这是整个后半段的承诺本身，所以它要被检查，而不是靠推理相信。真实示例只有七份，
    # 覆盖不到量的两端；这里用合成材料从 4 个走到 120 个，形状挑过：均匀跨年、每年
    # 一个、连续十二天、三年内三十个、两方对读五十个与一百二十个。
    # 任何一档给不出图，就是阶梯断了；给得出但相压或穿卡，就是几何算错了。
    import copy as _cp
    import tempfile as _tf2
    # 入口模块在这一段之前还没被载入（载入它的那一段在下面），所以自己载一次。
    _fs2 = _iu.spec_from_file_location(
        "render_figure", os.path.join(SKILL, "scripts", "render_figure.py"))
    _fm = _iu.module_from_spec(_fs2)
    _fs2.loader.exec_module(_fm)
    _base = json.load(open(os.path.join(SKILL, "examples", "dated-limitation.json"),
                           encoding="utf-8"))

    def _synth(dates, lanes=False):
        mm2 = _cp.deepcopy(_base)
        mm2.pop("spans", None)
        mm2.pop("points", None)
        evs2 = []
        for i2, d2 in enumerate(dates):
            e2 = _cp.deepcopy(_base["events"][0])
            y2, mo2, da2 = d2.split("/")
            e2["id"] = str(i2 + 1)
            e2["time"] = {"certainty": "exact", "origin": "extracted",
                          "kind": "occur", "raw": f"{y2}年{mo2}月{da2}日",
                          "date": d2, "date_text": d2.replace("/", ".")}
            e2["head"] = f"第{i2 + 1}项事实经过与相应凭证"
            e2.pop("emphasis", None)
            e2.pop("band", None)
            if lanes:
                e2["lane"] = "P" if i2 % 2 == 0 else "D"
            evs2.append(e2)
        mm2["events"] = evs2
        if lanes:
            mm2["lanes"] = [{"id": "P", "label_text": "原告主张"},
                            {"id": "D", "label_text": "被告主张"}]
        else:
            mm2.pop("lanes", None)
        return mm2

    LADDER = [
        ("4 个跨 6 年", [f"201{5 + 2 * i}/3/1" for i in range(4)], False),
        ("8 个每年一个", [f"20{15 + i}/6/1" for i in range(8)], False),
        ("12 个连续 12 天", [f"2020/3/{i + 1}" for i in range(12)], False),
        ("30 个跨 3 年", [f"20{20 + i // 10}/{i % 10 + 1}/5" for i in range(30)], False),
        ("50 个两方对读", [f"20{18 + i // 12}/{i % 12 + 1}/8" for i in range(50)], True),
        ("120 个两方对读", [f"20{10 + i // 12}/{i % 12 + 1}/8" for i in range(120)], True),
    ]
    for label, dates, lanes in LADDER:
        tmp2 = _tf2.NamedTemporaryFile(suffix=".svg", delete=False)
        tmp2.close()
        try:
            _kind, _form, _why, (_w3, _h3) = _fm.deliver(_synth(dates, lanes), tmp2.name)
            svg3 = open(tmp2.name, encoding="utf-8").read()
        except Exception as _e3:
            msgs.append(f"阶梯 {label}: 一张图也给不出 {type(_e3).__name__}: "
                        f"{str(_e3).splitlines()[0][:80]}")
            continue
        finally:
            os.unlink(tmp2.name)
        for g3 in _geometry_msgs(svg3):
            msgs.append(f"阶梯 {label}: {g3}")
        # 入口返回的是**裱好白边的整幅**尺寸，所以要用 sheet_ok 量整幅，不能拿内容
        # 画幅的预算去量。这是同一个错的第二次：守卫量错了对象，看起来却像图错了。
        _bad3 = _pm.sheet_ok(_w3, _h3, landscape=(_form == "横向"))
        if _bad3:
            msgs.append(f"阶梯 {label}: {_bad3}")

    # ---- 纵向长图超过一页，必须真的出页 ---------------------------------
    # 阶梯的最后一档是「纵向一直延续下去、分很多张图」。入口曾经只把页数写进说明，
    # 产物仍是一张长图，而律师要的是能直接打印的那几页 —— 少了这一步，那一档是空的。
    # 45 个事项是这一档的实测样本：长图 1787px，应出 2 页。
    _lm = json.load(open(os.path.join(SKILL, "examples", "two-sides-numbered.json"),
                         encoding="utf-8"))
    _lproto = _lm["events"][0]
    _levs = []
    for _i9 in range(45):
        _e9 = _CP.deepcopy(_lproto)
        _e9["id"] = str(_i9 + 1)
        _e9["head"] = f"第{_i9 + 1}项事实经过与相应凭证记录"
        _e9.pop("head_short", None)
        _e9.pop("body", None)
        _e9.pop("emphasis", None)
        _e9.pop("index_note", None)
        _e9["lane"] = "P" if _i9 % 2 == 0 else "D"
        _d9 = f"2025/{_i9 // 4 + 1}/{(_i9 % 4) * 7 + 2}"
        _y9, _mo9, _da9 = _d9.split("/")
        _e9["time"] = {"certainty": "exact", "origin": "extracted", "kind": "occur",
                       "raw": f"{_y9}年{_mo9}月{_da9}日", "date": _d9,
                       "date_text": f"{_y9}.{int(_mo9):02d}.{int(_da9):02d}"}
        _levs.append(_e9)
    _lm["events"] = _levs
    _ld = _TF.mkdtemp()
    _lout = os.path.join(_ld, "long.svg")
    try:
        _k9, _f9, _w9, _wh9 = _fm.deliver(_lm, _lout)
        _pages9 = sorted(_g for _g in os.listdir(_ld) if "-page-" in _g)
        _tall = _wh9[1]
        if _tall > _paper_mod().SHEET_PORT_H + 1 and not _pages9:
            msgs.append(f"纵向长图 {_tall:.0f}px 超过一页，却没有出任何分页产物")
        for _pg9 in _pages9:
            _s9 = open(os.path.join(_ld, _pg9), encoding="utf-8").read()
            _m9 = re.search(r'<svg[^>]*width="([\d.]+)"[^>]*height="([\d.]+)"', _s9)
            _b9 = _paper_mod().sheet_ok(float(_m9.group(1)), float(_m9.group(2)),
                                        landscape=False)
            if _b9:
                msgs.append(f"长图分页 {_pg9}: {_b9}")
        _ids9 = []
        for _pg9 in _pages9:
            _ids9 += re.findall(r'data-id="([^"]+)"',
                                open(os.path.join(_ld, _pg9), encoding="utf-8").read())
        if _pages9 and len(set(_ids9)) != 45:
            msgs.append(f"长图分页覆盖 {len(set(_ids9))} 个事项，应为 45 个")
    except Exception as _e10:
        msgs.append(f"长图分页守卫出错: {type(_e10).__name__}: {str(_e10)[:60]}")

    # 示例的图名都短，一行就放下了，所以只用示例测不出标题折行 —— 两个探针（横向改回
    # 不折行、分页改回写死 30px 单行）都漏过。补一份**长图名**的形状进来，那才是能卡住
    # 这条的形状：40 字在横向要两行、在纵向要降档。
    _lt = "东北大学秦皇岛分校与秦皇岛佳鑫诺教育科技有限公司买卖合同纠纷双方主张对读时间轴"
    for _src, _fn2, _land2 in (("横向长名", "render_multiband", True),
                               ("纵向长名", "render_vcolumns", False)):
        _mm8 = json.load(open(os.path.join(SKILL, "examples", "two-sides-numbered.json"),
                              encoding="utf-8"))
        for _e11 in _mm8["events"]:
            _e11.pop("head_short", None)
            _e11.pop("body", None)
            _e11["head"] = "事项经过"
        _mm8["title_text"] = _lt
        _t8 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
        _t8.close()
        try:
            _mod(_fn2).render(_mm8, _t8.name)
            svgs[f"{_src}:合成"] = open(_t8.name, encoding="utf-8").read()
        except Exception as _e12:
            msgs.append(f"{_src} 画不出：{str(_e12).splitlines()[0][:50]}")
        finally:
            os.unlink(_t8.name)

    # ---- 贴边的卡片不许因为边距而退回最小宽度 ----------------------------
    # 优雅宽度原来写成 min(2×左余量, 2×右余量)，等于要求卡片以圆点为中心左右对称，
    # 于是首尾两张被页边距卡回最小宽度：一张图十一张 124px、首尾两张 110.9px，
    # 作者一眼看出「第一个模块的宽度不对」。卡片可以整体往内侧偏，不必对称。
    # 判据：同一层里若出现两种宽度，且窄的那些恰在两端，就是这个毛病。
    for name, svg in svgs.items():
        _rs = sorted((float(x), float(w)) for x, w in re.findall(
            r'<rect x="([\d.]+)" y="[\d.]+" width="([\d.]+)"[^>]*rx="12"', svg))
        if len(_rs) < 4:
            continue
        _wid = [round(w) for _x, w in _rs]
        _uniq = sorted(set(_wid))
        if len(_uniq) == 2 and _wid[0] == _uniq[0] and _wid[-1] == _uniq[0] \
                and _wid.count(_uniq[0]) == 2:
            msgs.append(f"{name}: 只有首尾两张卡是 {_uniq[0]}px、其余 {_uniq[1]}px，"
                        f"贴边的卡片不该因为页边距退回最小宽度")

    # ---- 正文对齐：一两行居中，三行及以上左对齐 --------------------------
    # 三处渲染器原来各写一份逐字相同的绘制代码，改一处会漏两处，所以抽成
    # geom.body_lines 一份。这条守卫盯的是产物：同一张卡里的正文行，要么全部居中
    # （一两行），要么全部左对齐（三行及以上），不许混。
    # 三行以上是**两端对齐**（作者定的：左对齐不好看，要 Word 的 Ctrl+J 那种）。
    # 做法 textLength + lengthAdjust="spacing"（只摊字距、不拉字形），末行不参与。
    # 这里只做一件事：扫常驻产物，**用守卫自己的门槛**核每缝实际加了多少。
    # 门槛写在守卫里、不读 geom 的常数 —— 第一版就是读了被测模块的 MAX_GAP_ADD，
    # 于是把那个常数放到 40px（等于允许硬拉字距）守卫照样通过：自己给自己打分。
    # 1.8px 这个数是作者判过「肉眼看得出字距被撑开」的那一档，所以守卫的红线定在它以下。
    _J_LIMIT = 1.0
    sys.path.insert(0, os.path.join(SKILL, "..", "mqc-litigation-visual-redraw", "scripts"))
    from common import text_w as _tw_j
    if _mod("geom").MAX_GAP_ADD > _J_LIMIT + 1e-9:
        msgs.append(f"geom.MAX_GAP_ADD = {_mod('geom').MAX_GAP_ADD}px 超过守卫的红线 "
                    f"{_J_LIMIT}px（作者判难看的那一档是 1.8px）")
    for name, svg in svgs.items():
        for _m in re.finditer(r'<text[^>]*font-size="(\d+)"[^>]*textLength="([\d.]+)"'
                              r'[^>]*lengthAdjust="spacing"[^>]*>([^<]*)</text>', svg):
            _fs_j, _tl_j, _txt_j = int(_m.group(1)), float(_m.group(2)), _m.group(3)
            if not _txt_j:
                continue
            _add_j = (_tl_j - _tw_j(_txt_j, _fs_j)) / max(1, len(_txt_j) - 1)
            if _add_j > _J_LIMIT + 0.01 or _add_j < 0:
                msgs.append(f"{name}: 两端对齐每缝加 {_add_j:.2f}px，超过 {_J_LIMIT}px "
                            f"（{_txt_j[:10]}…）—— 这一档该退回左对齐")
                break
    _mm11 = json.load(open(os.path.join(SKILL, "examples", "two-sides-numbered.json"),
                           encoding="utf-8"))
    # 日期也要重排成均匀分布：示例那份日期挤在几个月内，长文本一来形态就变成纵向，
    # 于是这条守卫报「画不出」而不是报对齐问题 —— 测试数据不对，测什么都测不到。
    for _i14, _e14 in enumerate(_mm11["events"]):
        _e14.pop("head_short", None)
        _e14.pop("body", None)
        _e14["head"] = "甲学院因体质健康标准测试需要就四套田径比赛设备公开招标"
        # lane 也要改成严格交错。示例的 lane 是按案情分的（同侧连着好几个），
        # 同侧连续会升层，升层就把容量压掉三分之二，于是这条守卫报「画不出」
        # 而不是报对齐问题。这与 M10 那条规律是同一件事的两面。
        _e14["lane"] = "P" if _i14 % 2 == 0 else "D"
        _d14 = f"20{23 + _i14 // 12}/{_i14 % 12 + 1}/{_i14 % 27 + 1}"
        _e14["time"] = {"certainty": "exact", "origin": "extracted", "kind": "occur",
                        "raw": "x", "date": _d14,
                        "date_text": f"2023.{_i14 % 12 + 1:02d}.{_i14 % 27 + 1:02d}"}
    _t11 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
    _t11.close()
    try:
        _mod("render_multiband").render(_mm11, _t11.name)
        _s11 = open(_t11.name, encoding="utf-8").read()
        _fsb11 = _mod("render_multiband").FS_BODY
        _mid = len(re.findall(rf'font-size="{_fsb11}"[^>]*text-anchor="middle"', _s11))
        _st = len(re.findall(rf'font-size="{_fsb11}"[^>]*text-anchor="start"', _s11))
        if _mid and _st:
            msgs.append(f"多行正文: 同一张图里既有居中又有左对齐的正文（居中 {_mid} 行、"
                        f"左对齐 {_st} 行），三行及以上应一律左对齐")
        if not _st:
            msgs.append("多行正文: 三行以上的正文没有左对齐")
        svgs["多行正文:合成"] = _s11
    except Exception as _e15:
        msgs.append(f"多行正文画不出：{str(_e15).splitlines()[0][:50]}")
    finally:
        os.unlink(_t11.name)

    # ---- 前端三个检查器：必须真的抓得住错 ---------------------------------
    # 前端的拆解与抽取是模型做的，代码只验。所以这三个检查器就是前端的全部保证 ——
    # 它们要是抓不住错，模型答错就会变成一张错的图而不是一次失败的检查。
    # 六种最容易犯的错各撞一次：漏句、一句归两组、同名、事实卡编概括、超部分数上限、
    # 超容量、改写而非删减。
    _cm = _mod("check_model_output")
    _rsrc = _mod("read_source")
    _S17 = _rsrc.read(os.path.join(SKILL, "tests", "fixtures",
                                   "synthetic-judgment.txt"))["sentences"]
    _nS = len(_S17)
    _cut = [0, _nS // 3, 2 * _nS // 3, _nS]
    _good17 = []
    for _i17 in range(3):
        _r17 = list(range(_cut[_i17], _cut[_i17 + 1]))
        _good17.append({"id": _i17 + 1, "name": f"第{_i17 + 1}部分",
                        "sids": _r17,
                        "first": _S17[min(_r17)][:16],
                        "last": _S17[max(_r17)][:16]})
    _e17, _ = _cm.check_parts(_S17, _good17)
    if _e17:
        msgs.append(f"前端检查器：正确的划分被误判 —— {_e17[0][:70]}")

    def _must_catch(label17, parts17=None, items17=None, cap17=None):
        if parts17 is not None:
            _er, _ = _cm.check_parts(_S17, parts17)
        elif cap17 is not None:
            _er, _w, _ = _cm.check_capacity(items17, cap17)
        else:
            _er, _ = _cm.check_subsequence(items17, _S17)
        if not _er:
            msgs.append(f"前端检查器漏过「{label17}」")

    _p17 = _CP.deepcopy(_good17)
    _p17[1]["sids"] = _p17[1]["sids"][:2]
    _must_catch("漏句", parts17=_p17)
    _p17 = _CP.deepcopy(_good17)
    _p17[0]["sids"] = _p17[0]["sids"] + [_cut[1]]
    _must_catch("一句归两组", parts17=_p17)
    _p17 = _CP.deepcopy(_good17)
    _p17[1]["name"] = _p17[0]["name"]
    _must_catch("两组同名", parts17=_p17)
    _p17 = _CP.deepcopy(_good17)
    _p17[0]["first"] = "本案系合同纠纷双方就款项发生争议"
    _must_catch("事实卡编概括", parts17=_p17)
    _p17 = _CP.deepcopy(_good17) + [
        {"id": 90 + _j, "name": f"多{_j}", "sids": [], "first": "", "last": ""}
        for _j in range(7)]
    _must_catch("部分数超上限", parts17=_p17)
    _must_catch("超容量", items17=[{"id": "1", "head": "事" * 40}], cap17=20)
    # 一个事项来自多句是常态：合并两句、三句都要通过，倒序与掺字要被拒。
    # 第一版只允许一个 src_sid，这种合并必然误报（真材料里句 29 与 30 就是一件事）。
    _multi = [{"id": "m1", "head": _cm._norm(_S17[0])[:6] + _cm._norm(_S17[1])[:6],
               "src_sids": [0, 1]}]
    _em, _ = _cm.check_subsequence(_multi, _S17)
    if _em:
        msgs.append(f"前端检查器：合并两句被误拒 —— {_em[0][:60]}")
    _rev = [{"id": "m2", "head": _cm._norm(_S17[0])[:6], "src_sids": [1, 0]}]
    _er2, _ = _cm.check_subsequence(_rev, _S17)
    if not _er2:
        msgs.append("前端检查器漏过「src_sids 倒序」")

    _must_catch("改写而非删减",
                items17=[{"id": "1", "head": "完全没有出现过的另一句话啊", "src_sid": 0}])

    # ---- check_capacity 的另两条判据也要有守卫 -------------------------------------
    # 自检查出来的：这个函数有四条判据，而守卫只调 `check_capacity(items17, cap17)`
    # 两个参数，于是另外三条**样本根本到不了**——逐条改成 `if False:` 回归照样绿。
    # 管线是全参数调用的（cap_title / title / sentences 都传），所以功能一直在保护产物，
    # 缺的只是「它被改坏时有没有人发现」。
    #
    # 补两条，第三条不补：
    #  · 图名超容量（[C6]）—— **必补**。C6 另有一道防线在 validate_map，
    #    但**管线不调 validate_map**（pipeline 里出现 0 次），实际拦住图名超容量的
    #    就是这一条。它被改坏，图名会超出标题块画出去，而 C6 明写「渲染器不截断」，
    #    后果是字被切掉。
    #  · attrib 超 5 字（[C14]）—— 补。attrib 拼在正文开头，超 5 字会挤掉正文。
    #  · 「正文写太短」那条**不补**：它是 warns 不是 errs，被改坏只少一句提醒、
    #    不会产出错图；而补它要造带 sentences 的样本，守卫复杂度不值。（记在 ADR）
    _cap_it = [{"id": "1", "head": "双方签订采购合同", "src_sid": 0}]
    # 正向：该报的必须报
    if not _cm.check_capacity(_cap_it, 100, cap_title=22,
                              title="这是一个非常非常长的图名超出了容量限制应当被拦住")[0]:
        msgs.append("图名超容量没被 check_capacity 拦住 —— [C6] 要求出图前拦住并要求改短，"
                    "渲染器不截断，漏了就会把图名画出标题块（字被切掉）")
    if not _cm.check_capacity([dict(_cap_it[0], attrib="原告方如此陈述的")], 100)[0]:
        msgs.append("attrib 超 5 字没被拦住 —— [C14] 规定 attrib 不超 5 字，"
                    "它拼在正文开头，超了会挤掉正文")
    # 反向：合规的必须放过，否则误报会逼人绕过这条检查
    if _cm.check_capacity(_cap_it, 100, cap_title=22, title="短图名")[0]:
        msgs.append("容量内的图名被误判超容量")
    if _cm.check_capacity([dict(_cap_it[0], attrib="原告称")], 100)[0]:
        msgs.append("5 字以内的 attrib 被误判超长")
    # 真的只删减，必须通过
    _sub17 = "".join(ch for _i in (0,) for ch in _S17[0][::2])
    _eok, _ = _cm.check_subsequence([{"id": "1", "head": _sub17, "src_sid": 0}], _S17)
    if _eok:
        msgs.append(f"前端检查器：真的只删减却被拒 —— {_eok[0][:60]}")

    # 折叠短片段不许跨段落：小标题自己成段时（「证据清单。」五个字）不能被折回上一段，
    # 否则溯源索引的原文摘录里会混进下一段的标题（真材料里出现过）。
    _dpath = os.path.join(SKILL, "tests", "fixtures", "m3-complaint.docx")
    # **缺 python-docx 时跳过这一条，不让整个回归崩掉。** CI 那台机器是干净的，
    # 于是这里抛裸的 ModuleNotFoundError、退出码 1，前面五组守卫的结果全被埋掉。
    # 这个 skill 的口径是零第三方依赖：缺一样只该少一种能力
    # （与 doctor.py 那套「缺什么就退化成什么样」一致），不该让整条路崩。
    # 跳过时**必须印一行**，否则就成了静默失效 —— 那比报错更坏。
    _dsents = None
    if os.path.exists(_dpath):
        try:
            _dsents = _rsrc.read(_dpath)["sentences"]
        except getattr(_rsrc, "DocxUnavailable", RuntimeError) as _e_docx:
            print("  略过「小标题不许折进上一句」这一条："
                  "本机没装 python-docx，读不了 .docx 样本")
            print("    装它就能跑：pip install python-docx")
    if _dsents:
        for _s19 in _dsents:
            if "。" in _s19[:-1] and len(_s19.split("。")[-1].strip()) in range(1, 6):
                msgs.append(f"切句把一个小标题折进了上一句：{_s19[-24:]!r}")
                break

    # 日期型的**前置判据必须与渲染器的结论一致**：前端算出「不该用」的，渲染器也该拒；
    # 前端算出「该用」的，渲染器要画得出。不一致就意味着前端白算一遍、还得撞回来才知道。
    _dbase2 = json.load(open(os.path.join(SKILL, "examples", "dated-limitation.json"),
                             encoding="utf-8"))
    _dpr = _dbase2["events"][0]

    def _dtry(ds18):
        mm18 = _CP.deepcopy(_dbase2)
        ev18 = []
        for _i18, _d18 in enumerate(ds18):
            _e18 = _CP.deepcopy(_dpr)
            _e18["id"] = str(_i18 + 1)
            _e18["head"] = "事项经过说明"
            for _k18 in ("head_short", "body", "band", "emphasis"):
                _e18.pop(_k18, None)
            _y18, _mo18, _da18 = _d18.split("/")
            _e18["time"] = {"certainty": "exact", "origin": "extracted",
                            "kind": "occur", "raw": "x", "date": _d18,
                            "date_text": f"{_y18}.{int(_mo18):02d}.{int(_da18):02d}"}
            ev18.append(_e18)
        mm18["events"] = ev18
        try:
            _mod("render_dated_v2").render(mm18)
            return True
        except Exception:
            return False

    # 样本要**挑到会分歧的形状上**。原来这四个恰好判据与引擎全都一致，于是那条换算出来的
    # 代理判据（任意连续两段 ≥ 两年）错了很久也测不出来 —— 它只在年刻度成立，而单位是按
    # 跨度选的。后三个是补上的，两个方向各有：
    #   · 短跨度走周刻度：判据过严（说不该用，引擎画得出）
    #   · 八点各隔一年：判据过松（说该用，而八格年份每格只有 100.75px、
    #     同侧 201.5px 不足 230px，引擎拒绝）
    _dcases = [("五点跨六年", ["2018/4/10", "2020/4/10", "2021/9/1", "2023/2/15",
                          "2024/6/20"]),
               ("两段过密", ["2020/1/1", "2020/3/1", "2020/6/1", "2023/1/1"]),
               ("三点跨四月", ["2024/3/5", "2024/4/18", "2024/6/28"]),
               ("九点", [f"20{20 + _i}/1/1" for _i in range(9)]),
               ("五点跨 23 天（周刻度）", ["2024/3/5", "2024/3/8", "2024/3/15",
                                  "2024/3/22", "2024/3/28"]),
               ("三点跨三周", ["2024/3/1", "2024/3/10", "2024/3/21"]),
               ("八点各隔一年", [f"20{16 + _i}/1/1" for _i in range(8)]),
               ("七点各隔一年", [f"20{17 + _i}/1/1" for _i in range(7)]),
               ("五点跨一年半", ["2023/1/10", "2023/5/20", "2023/9/1",
                            "2024/2/14", "2024/7/1"])]
    for _lbl18, _ds18 in _dcases:
        _e18b, _ = _cm.check_dated_worthiness(_ds18, "时效届满")
        _pre_ok = not _e18b
        _real_ok = _dtry(_ds18)
        if _pre_ok != _real_ok:
            msgs.append(f"日期型前置判据与渲染器不一致（{_lbl18}）："
                        f"前置说{'该用' if _pre_ok else '不该用'}、"
                        f"渲染器{'画得出' if _real_ok else '拒绝'} —— "
                        f"前端会白算一遍还得撞回来")

    # 强制交互的前两轮：材料来源、时间段。两个维度都必须**天然互斥**，
    # 而且第二轮要跟着第一轮的勾选变（勾了哪几份材料，时间列就只统计那几份）。
    _pk = _mod("pick")
    # 测试材料必须放在**仓库内**。原来指向仓库外的 work/case6，路径少往上两层、
    # 目录不存在，整段守卫被 if len(_mp) >= 2 静默跳过 —— 三个改坏验证全漏过，
    # 而我以为是判据写得不好。**守卫拿不到材料时不能静默跳过，要报出来。**
    _mp = [os.path.join(SKILL, "tests", "fixtures", _f)
           for _f in ("m6-messy.txt", "m1-judgment.txt", "m2-defence.txt")]
    _mp = [_x for _x in _mp if os.path.exists(_x)]
    if len(_mp) < 2:
        msgs.append(f"交互两轮的测试材料找不到（找到 {len(_mp)} 份）—— "
                    f"守卫拿不到材料就等于没有守卫，不许静默跳过")
    else:
        _r1 = _pk.round_one(_mp)
        if len({_r["name"] for _r in _r1}) != len(_r1):
            msgs.append("第一轮的材料短名有重复 —— 同名的行无法勾选")
        # 同名材料要能自动编号。真实场景：二十份格式一样的催款函。
        # 拿三份名字本来就不同的材料测不到这条（试过，改坏漏过）——
        # 探针要造出同名的输入，被测的规则才成为唯一的原因。
        _dup = os.path.join(SKILL, "tests", "fixtures", "m1-judgment.txt")
        if os.path.exists(_dup):
            _r_dup = _pk.round_one([_dup, _dup, _dup])
            if len({_r["name"] for _r in _r_dup}) != 3:
                msgs.append(f"三份同名材料没有自动编号，短名是 "
                            f"{[_r['name'] for _r in _r_dup]} —— 同名的行无法勾选")
        for _r in _r1:
            if _r["n_dated"] == 0 and "无可识别日期" not in _r["span"]:
                msgs.append(f"材料 {_r['name']} 一个日期都没认出来，"
                            f"但 span 却给了范围")
        # 判据要**直接盯行为**，不能拿数量比对。
        # 上一版比的是「日期总数变没变少」与「标签里有没有『至』字」，两个改坏
        # （第二轮不跟第一轮走、粒度写死成按年）都漏过了 —— 因为改坏之后那些数字与
        # 字样恰好仍然满足。判据必须让被测的那条规则成为唯一的原因。
        #
        # 一、第二轮跟着第一轮走：**逐份材料**比对。只勾第 i 份时，时间段里出现的
        #    日期集合必须恰好等于第 i 份材料自己的日期集合 —— 这是「跟着走」的定义，
        #    不是数量少一点就算。
        for _i25, _r25 in enumerate(_r1):
            # 口径要与 round_two 一致：数**出现次数**，不是去重后的集合。
            # 聊天记录同一天有好几条（真材料里 2023-04-12 出现四次），拿去重后的 11
            # 去比 round_two 数出的 17，就会误报成 bug —— 而它其实是对的。
            # 比对两个数之前，先确认两边数的是同一种东西。
            _own = [_d for _s in _r25["sentences"] for _d in _pk.dates_in(_s)]
            _rows25, _ = _pk.round_two(_r1, [_r25["id"]])
            _got25 = sum(_x["n"] for _x in _rows25)
            if _got25 != len(_own):
                msgs.append(f"只勾材料「{_r25['name']}」时，时间段统计到 {_got25} 个日期，"
                            f"而这份材料自己有 {len(_own)} 个 —— "
                            f"第二轮没有跟着第一轮的勾选走")
                break
        # 二、粒度按跨度自适应：造三个跨度不同的输入，三档必须**各不相同**。
        #    写死成任何一档都会让三者相同。
        import datetime as _dt25

        def _fake(days25):
            return [{"id": 1, "name": "x", "sentences": [
                f"{_dt25.date(2020, 1, 1)} 起", f"{_dt25.date(2020, 1, 1) + _dt25.timedelta(days=days25)} 止"]}]
        _grains = []
        for _d25 in (200, 1200, 4000):     # 约 0.5 年、3.3 年、11 年
            _rows26, _note26 = _pk.round_two(_fake(_d25), None)
            _grains.append(_note26.split("列")[0])
        if len(set(_grains)) < 3:
            msgs.append(f"时间粒度没有按跨度自适应：0.5 年 / 3.3 年 / 11 年 三档"
                        f"算出的粒度是 {_grains} —— 应当分别是按季度、按年、按两年")

    # 四份 JSON 的模板必须与真正的检查器、真正的步骤对得上，否则文档会与代码分家：
    # 照着模板写却过不了检查，是最难查的一类问题（人会以为自己写错了）。
    _pl0 = _mod("pipeline")
    _cm0 = _mod("check_model_output")
    for _fn30, _sp30 in _pl0.SHAPES.items():
        for _ck30 in re.split(r"[/、]", _sp30["checker"]):
            _ck30 = _ck30.strip()
            if _ck30 and not hasattr(_cm0, _ck30):
                msgs.append(f"{_fn30} 的模板说由 {_ck30} 校验，"
                            f"但 check_model_output 里没有这个函数")
    # 步骤表里提到的命令必须真的能被派发（否则照着 steps 跑会说「未知命令」）
    _src30 = open(os.path.join(SKILL, "scripts", "pipeline.py"),
                  encoding="utf-8").read()
    for _cmd30, _who30, _what30, _need30 in _pl0.STEPS:
        _name30 = _cmd30.split()[0]
        if f'cmd == "{_name30}"' not in _src30:
            msgs.append(f"步骤表列了命令 {_name30}，但 pipeline 里没有派发它")
        if _need30:
            for _f30 in re.split(r"[+、]", _need30):
                _f30 = _f30.strip()
                if _f30 and _f30 not in _pl0.SHAPES:
                    msgs.append(f"步骤 {_name30} 说要先写 {_f30}，"
                                f"但 SHAPES 里没有它的形状说明")

    # 勾选必须真的限制抽取范围。只写不读就是摆设 —— 与 anchor 那个死字段同一种毛病。
    _pl = _mod("pipeline")
    _st27 = {"picked_files": [1],
             # pick_r1 每行都带 path —— 过筛按文件名精确比对，不靠短名（见 in_scope）。
             # 这个样本原来只给 name，于是新判据拿不到键；现在缺 path 会直接报错，
             # 不再静默放过所有材料。
             "pick_r1": [{"id": 1, "name": "混乱材料",
                          "path": "/x/m6-messy.txt"},
                         {"id": 2, "name": "判决书", "path": "/x/m1-judgment.txt"}],
             "origin": ["m6-messy.txt"] * 3 + ["m1-judgment.txt"] * 3,
             "sentences": ["2023-04-12 甲", "2023-05-08 乙", "2023-06-14 丙",
                           "2014-03-12 丁", "2014-09-25 戊", "2015-03-25 己"]}
    _in = [_pl.in_scope(_st27, _i) for _i in range(6)]
    if _in[:3] != [True, True, True]:
        msgs.append(f"勾中的材料被筛掉了：前三句 in_scope = {_in[:3]}")
    if any(_in[3:]):
        msgs.append(f"没勾的材料没有被筛掉：后三句 in_scope = {_in[3:]} —— "
                    f"勾选只写不读就是摆设")
    # 时间段的筛选同样要生效
    _st28 = dict(_st27, picked_files=[], picked_spans=[1],
                 pick_r2=[{"id": 1, "label": "2023 年", "n": 3},
                          {"id": 2, "label": "2014 年", "n": 2}])
    _in2 = [_pl.in_scope(_st28, _i) for _i in range(6)]
    if not all(_in2[:3]) or any(_in2[3:]):
        msgs.append(f"时间段的筛选没生效：in_scope = {_in2} —— "
                    f"勾了 2023 年，2014 年的句子仍在范围内")
    # **材料过筛要按文件名精确比对，不能靠短名。** 短名是给人看的（去掉「材料N」前缀、
    # 截到十字），拿它当过筛的键有两处会坏，两个样本各钉一处：
    #   ① 同名多份：二十份格式一样的催款函，pick.name_of 编成「催款函 2」「催款函 3」，
    #      而按短名算出的键不带编号，于是**勾一份等于勾全部**；
    #   ② 长名前十字相同：截断之后两份材料分不开。
    # 上面那个样本（混乱材料 / 判决书）两个名字差别很大，这两种情形都测不到 ——
    # 又是「样本没让被测的项变化」。
    _S30 = ["2023-04-12 甲", "2023-05-08 乙", "2024-03-12 丙", "2024-09-25 丁"]
    _st30 = {"sentences": _S30,
             "origin": ["催款函-第一次.txt"] * 2 + ["催款函-第二次.txt"] * 2,
             "pick_r1": [{"id": 1, "path": "/x/催款函-第一次.txt", "name": "催款函-第一次"},
                         {"id": 2, "path": "/x/催款函-第二次.txt", "name": "催款函-第一次 2"}],
             "picked_files": [1], "picked_spans": None}
    _in30 = [_pl.in_scope(_st30, _i) for _i in range(4)]
    if _in30 != [True, True, False, False]:
        msgs.append(f"同名多份材料的过筛不对：只勾了第一份，in_scope = {_in30}"
                    f"（应为 [True, True, False, False]）—— 勾一份等于勾全部")
    _st31 = {"sentences": _S30,
             "origin": ["某某公司与某某公司买卖合同纠纷起诉状.txt"] * 2
                       + ["某某公司与某某公司买卖合同纠纷答辩状.txt"] * 2,
             "pick_r1": [{"id": 1, "path": "/x/某某公司与某某公司买卖合同纠纷起诉状.txt",
                          "name": "某某公司与某某公司买"},
                         {"id": 2, "path": "/x/某某公司与某某公司买卖合同纠纷答辩状.txt",
                          "name": "某某公司与某某公司买 2"}],
             "picked_files": [2], "picked_spans": None}
    _in31 = [_pl.in_scope(_st31, _i) for _i in range(4)]
    if _in31 != [False, False, True, True]:
        msgs.append(f"长名材料的过筛不对：只勾了答辩状，in_scope = {_in31}"
                    f"（应为 [False, False, True, True]）—— 短名截断到十字就分不开了")
    # 缺 path 时**必须报错，不许静默放过**。新判据第一版写成「allow 为空就不过筛」，
    # 于是只给 name 的样本让所有材料都进了范围 —— 过筛看起来在跑、实际一个都没筛掉、
    # 还不报错，是最坏的失效方式。
    _st32 = {"sentences": ["2023-04-12 甲", "2024-03-12 乙"],
             "origin": ["甲.txt", "乙.txt"],
             "pick_r1": [{"id": 1, "name": "甲"}],      # 故意不给 path
             "picked_files": [1], "picked_spans": None}
    try:
        _pl.in_scope(_st32, 0)
        msgs.append("pick_r1 缺 path 时 in_scope 没有报错 —— 过筛会静默失效，"
                    "所有材料都进范围")
    except ValueError:
        pass
    except Exception as _e32:
        msgs.append(f"pick_r1 缺 path 时抛的不是 ValueError：{type(_e32).__name__}")

    # **季度档也要真的过筛。** 上面那个样本全是年档，于是「拿年份做子串比对」这种
    # 写法也能通过 —— 样本没让被测的项变化，判据就测不到（与层数恒为 1 那次同一类）。
    # 而跨度不到两年才按季度列，季度正是第二轮唯一真正需要过筛的那一档。
    _st29 = {"picked_files": [], "picked_spans": [1],
             "pick_r1": [{"id": 1, "name": "催告",
                          "path": "/x/m7-short.txt"}],
             "pick_r2": [{"id": 1, "label": "2024 年第 1 季度", "n": 1},
                         {"id": 2, "label": "2024 年第 3 季度", "n": 1},
                         {"id": 3, "label": "2025 年第 1 季度", "n": 1}],
             "origin": ["m7-short.txt"] * 3,
             "sentences": ["2024-01-15 第一次催告", "2024-08-20 第二次催告",
                           "2025-02-03 对方回函"]}
    _in3 = [_pl.in_scope(_st29, _i) for _i in range(3)]
    if _in3 != [True, False, False]:
        msgs.append(f"季度档没有真的过筛：勾了 2024 年第 1 季度，三句的 in_scope = "
                    f"{_in3}（应为 [True, False, False]）—— 同年别的季度也被放进来了")
    # 列清单与过筛必须是**同一个判据**：round_two 给某个日期分到哪一档，
    # in_bucket 就必须认它属于那一档，且不认别的档。两个方向都盯 ——
    # 只盯一个方向的话，把 in_bucket 写成「恒为真」也会通过。
    for _step29, _dates29 in (("quarter", [_dt.date(2024, 1, 15), _dt.date(2024, 8, 20)]),
                              ("year", [_dt.date(2023, 4, 12), _dt.date(2014, 3, 12)]),
                              ("two-year", [_dt.date(2018, 4, 10), _dt.date(2021, 9, 1)])):
        for _d29 in _dates29:
            _lab29 = _pk.bucket_label(_d29, _step29)
            if not _pk.in_bucket(_d29, _lab29):
                msgs.append(f"{_step29} 档：{_d29} 被分到「{_lab29}」，"
                            f"而 in_bucket 不认它属于这一档")
            _others29 = [_pk.bucket_label(_x, _step29) for _x in _dates29
                         if _pk.bucket_label(_x, _step29) != _lab29]
            for _o29 in _others29:
                if _pk.in_bucket(_d29, _o29):
                    msgs.append(f"{_step29} 档：{_d29} 属于「{_lab29}」，"
                                f"却也被判进「{_o29}」—— 档与档必须互斥")

    # [D13] 时间带上的刻度必须**看得出来**：与时间带的底色不许同色。
    # 这一条是补出来的 —— 改坏验证时把刻度退回浅灰（#ECEEF1），守卫漏过了，
    # 因为它只查颜色在不在角色表里，不查「与背景分不分得开」。
    # 而这正是那个 bug 的本体：画了 13 条线，图上一条看不见。
    _gm5 = _mod("geom")
    _tick_role = _gm5.ROLES.get("tick")
    if _tick_role:
        _tick_color = _gm5.C.get(_tick_role[0], "") if hasattr(_gm5, "C") else ""
        _dm5 = _mod("render_dated_v2")
        _tick_color = _dm5.C.get(_tick_role[0], "")
        _band_color = _dm5.C.get("bar", "")
        if _tick_color and _tick_color.upper() == _band_color.upper():
            msgs.append(f"刻度色 {_tick_color} 与时间带底色 {_band_color} 相同，"
                        f"刻度画了也看不见")
        # 白色是**故意的**（与纸同色的断口），深灰也可以；但不许是与带子相近的浅灰。
        def _lum(h):
            h = h.lstrip("#")
            return (int(h[0:2], 16) * 0.299 + int(h[2:4], 16) * 0.587
                    + int(h[4:6], 16) * 0.114)
        if _tick_color and _band_color:
            if abs(_lum(_tick_color) - _lum(_band_color)) < 25:
                msgs.append(f"刻度色 {_tick_color} 与时间带 {_band_color} 的明度差 "
                            f"{abs(_lum(_tick_color) - _lum(_band_color)):.0f} 太小，"
                            f"刻度看不出来（白色断口或深灰都可以，浅灰不行）")

    # ---- 只有一个选项时不许问 ---------------------------------------------
    # 只投喂一份材料时「选哪一份」只有一个答案；材料只覆盖一小段时间线时「选哪个
    # 时间段」也只有一个答案。**问一个只有一个答案的问题是浪费用户的时间。**
    _pk2 = _mod("pick")
    _one = os.path.join(SKILL, "tests", "fixtures", "m6-messy.txt")
    if os.path.exists(_one):
        _r1b = _pk2.round_one([_one])
        if len(_r1b) != 1:
            msgs.append(f"一份材料却列出 {len(_r1b)} 行")
        # 时间段：这份材料跨 0.8 年、按季度分四档，所以第二轮该问
        _rows2b, _note2b = _pk2.round_two(_r1b, [_r1b[0]["id"]])
        if len(_rows2b) <= 1:
            msgs.append(f"跨 0.8 年的材料只分出 {len(_rows2b)} 档时间段，"
                        f"第二轮会被误跳过")
    _short = os.path.join(SKILL, "tests", "fixtures", "m7-short.txt")
    if os.path.exists(_short):
        _r1c = _pk2.round_one([_short])
        _rows2c, _ = _pk2.round_two(_r1c, [_r1c[0]["id"]])
        if len(_rows2c) > 1:
            msgs.append(f"时间线只覆盖一个月，却分出 {len(_rows2c)} 档时间段 —— "
                        f"第二轮该跳过却会问")

    # ---- 三种风格都要能在这一档的产物上变换成功 ---------------------------
    # 白描与歸藏**直接用 v1 的 to_monochrome / to_guizang**，不另写一份 ——
    # 那两个函数接受一张 SVG 返回变换后的 SVG，所以能直接用在时间轴大师的图上。
    # 这条守卫盯的是「变换不许抛错、且真的改变了产物」：白描要把实心块变成白底框线，
    # 歸藏要换成克莱因蓝加点阵底。变换成功但什么都没改，等于没有这个风格。
    _v1dir = os.path.join(SKILL, "..", "mqc-litigation-visual-redraw", "scripts")
    if _v1dir not in sys.path:
        sys.path.insert(0, _v1dir)
    try:
        import render as _V1R
    except Exception as _e60:
        msgs.append(f"取不到 v1 的 render（风格变换靠它）：{str(_e60)[:50]}")
        _V1R = None
    if _V1R:
        _m60 = _CP.deepcopy(json.load(open(os.path.join(
            SKILL, "examples", "two-sides-numbered.json"), encoding="utf-8")))
        _t60 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
        _t60.close()
        try:
            _mod("render_multiband").render(_m60, _t60.name)
            _svg60 = open(_t60.name, encoding="utf-8").read()
        finally:
            os.unlink(_t60.name)
        for _name60, _fn60 in (("白描", _V1R.to_monochrome),
                               ("歸藏风", _V1R.to_guizang)):
            try:
                _out60 = _fn60(_svg60)
            except Exception as _e61:
                msgs.append(f"{_name60} 变换失败：{str(_e61).splitlines()[0][:60]}")
                continue
            if _out60 == _svg60:
                msgs.append(f"{_name60} 变换没有改变产物 —— 等于没有这个风格")
            if _name60 == "白描" and "#8C1C13" in _out60:
                msgs.append("白描里还留着深红 —— 单色变换没把重点色转掉")

    # ---- plan_only 交出的参数必须等于实画的参数 ---------------------------
    # 渲染器多了一个「只算不画」的出口，前端靠它确定地知道会画成什么样。
    # **这个出口最容易犯的错不是算错，是取错变量** —— 第一版返回按层的卡宽，
    # 而落笔用的是 CW_ELEGANT（[M11] 优雅宽度），于是计划比实画窄 8 到 13px，
    # 而这个差值恰好与我另写一份公式时一模一样，害我以为是公式漏了夹取，查了两轮。
    # 有了这条守卫，取错变量当场就会被抓住。
    _mb3 = _mod("render_multiband")
    _plan_bad = []
    for _n50 in (8, 10, 13, 15, 17, 20, 22, 24):
        # 分布要**两种都跑**。原来只跑连三，而图宽那条等式的偏差恰恰只在交错分布上
        # 出现（交错才是一层、才启用优雅宽度），于是那条规则带着 30px 的容差躺了很久
        # 也没人发现它验不出东西 —— 又一次「样本没让被测的项变化」。
        for _c50, _sh50 in [(_c, _s) for _c in (4, 10, 20, 40)
                            for _s in ("连三", "交错")]:
            _m50 = _CP.deepcopy(json.load(open(os.path.join(
                SKILL, "examples", "two-sides-numbered.json"), encoding="utf-8")))
            _pr50 = _m50["events"][0]
            _ev50 = []
            for _i50 in range(_n50):
                _e50 = _CP.deepcopy(_pr50)
                _e50["id"] = str(_i50 + 1)
                _e50["head"] = "事" * _c50
                for _k50 in ("head_short", "body", "emphasis", "index_note"):
                    _e50.pop(_k50, None)
                # 分布要能**升层**，否则层数那一项测不到（试过：交错分布层数恒为 1，
                # 把出口的层数写死成 1 也照样通过）。连三个同侧就会升层。
                # 交错那一档测的是另一件事：一层、启用优雅宽度，图宽等式在这里才咬人。
                _e50["lane"] = (("P" if _i50 % 2 == 0 else "D") if _sh50 == "交错"
                                else ("P" if (_i50 // 3) % 2 == 0 else "D"))
                _e50["time"] = {"certainty": "exact", "origin": "extracted",
                                "kind": "occur", "raw": "x",
                                "date": f"20{20 + _i50 // 12}/{_i50 % 12 + 1}/"
                                        f"{_i50 % 27 + 1}",
                                "date_text": "x"}
                _ev50.append(_e50)
            _m50["events"] = _ev50
            try:
                _pl = _mb3.render(_CP.deepcopy(_m50), None, plan_only=True)
            except Exception:
                continue
            _t50 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
            _t50.close()
            try:
                _r50 = _mb3.render(_CP.deepcopy(_m50), _t50.name)
                _s50 = open(_t50.name, encoding="utf-8").read()
                _real_cw = min(float(_x) for _x in re.findall(
                    r'width="([\d.]+)"[^>]*rx="12"', _s50))
            except Exception:
                continue
            finally:
                os.unlink(_t50.name)
            # **逐字段验算**：出口交出 16 个字段，原来只比对 3 个 ——
            # 其余 13 个（列距、轴位置、逐事项卡宽、图宽、是否在纸内）交出去了却没人验，
            # 而前端会照着用。这跟死字段是同一类隐患：看起来有，实际没保证。
            # 每一项都从产物里量真值：列距量相邻圆点的 x 差、轴位置量轴那条线的 y、
            # 逐事项卡宽量每个矩形的宽度。
            _cx50 = sorted(float(_x) for _x in re.findall(
                r'<circle[^>]*data-role="node"[^>]*cx="([\d.]+)"', _s50))
            if len(_cx50) > 1:
                _real_pitch = (_cx50[-1] - _cx50[0]) / (len(_cx50) - 1)
                if abs(_pl["pitch"] - _real_pitch) > 0.6:
                    _plan_bad.append(f"{_n50}个{_c50}字 列距 计划"
                                     f"{_pl['pitch']:.1f} 实测{_real_pitch:.1f}")
                # **最小宽度也要从产物验。** 它是新交出的字段，而新字段最容易变成死字段
                # （声明了、有人管着、没人验）。验法不是拿它去比某张卡的宽度（落笔用的是
                # 优雅宽度，两者本来就不等），而是用它去合图宽那条等式，
                # 且**列距与图宽都取产物量出来的真值** —— 出口若取错变量，这里当场不成立。
                # 容差 1.5px 是量出来的：33 组样本里最大偏差 0.90px（圆点吸附半像素所致），
                # 不是凭感觉给的余量。
                if "card_w_base" not in _pl:
                    _plan_bad.append(f"{_n50}个{_c50}字 出口没有交出 card_w_base，"
                                     f"图宽无法验算")
                else:
                    _w_art50 = (2 * _mb3.MARGIN_X + (len(_cx50) - 1) * _real_pitch
                                + _pl["card_w_base"])
                    if abs(_r50[0] - _w_art50) > 1.5:
                        _plan_bad.append(
                            f"{_n50}个{_c50}字{_sh50} 图宽 产物{_r50[0]:.0f}"
                            f" 与「2 边距 + 实测列距×{len(_cx50) - 1} + 最小宽度"
                            f"{_pl['card_w_base']:.1f}」={_w_art50:.1f} 不符")
            _ax50 = re.search(r'<line[^>]*data-role="axis"[^>]*y1="([\d.]+)"', _s50)
            if _ax50 and abs(_pl["axis_y"] - float(_ax50.group(1))) > 0.6:
                _plan_bad.append(f"{_n50}个{_c50}字 轴位置 计划"
                                 f"{_pl['axis_y']:.1f} 实测{_ax50.group(1)}")
            _rw50 = [float(_w) for _w in re.findall(
                r'<rect x="[\d.]+" y="[\d.]+" width="([\d.]+)"[^>]*rx="12"', _s50)]
            if len(_pl["card_w_by_id"]) and len(_rw50) != len(_pl["card_w_by_id"]):
                _plan_bad.append(f"{_n50}个{_c50}字 逐事项卡宽 计划 "
                                 f"{len(_pl['card_w_by_id'])} 项、图上 {len(_rw50)} 个矩形")
            if _pl["width"] != _r50[0]:
                _plan_bad.append(f"{_n50}个{_c50}字 图宽 计划{_pl['width']}"
                                 f" 实画{_r50[0]}")
            # 这里用 _mod("paper") 现取，不依赖后面才赋值的局部变量 _pp ——
            # 函数内同名局部变量这个坑在这份文件里踩过多次。
            # 横向的 fits_page 恒真（超纸的组合在参数阶段就被拒），所以这一项
            # 在横向测不出改坏 —— 试过把它写死成 True，守卫照样通过。
            # 判据改成：**装不下必须表现为抛错**，而不是返回一个 fits_page=False 的计划。
            # 这一条才是横向真正的语义。
            _land_h = _mod("paper").LAND_H
            if _r50[1] > _land_h:
                _plan_bad.append(f"{_n50}个{_c50}字 图高 {_r50[1]:.0f} 超过纸高 "
                                 f"{_land_h}，却没有在参数阶段被拒 —— "
                                 f"横向的约定是装不下就抛错")
            if _pl["fits_page"] != (_r50[1] <= _land_h):
                _plan_bad.append(f"{_n50}个{_c50}字 fits_page 报 "
                                 f"{_pl['fits_page']}，而图高 {_r50[1]:.0f}"
                                 f" 对纸高 {_land_h}")
            # 步距与层数上限：这两项是内部量，产物里没有直接对应的元素，
            # 但它们之间有**必然关系**，可以互验（这已经是第二层的做法）：
            #   · 层数不许超过声明的上限
            #   · 每层的卡宽必须都不超过 CARD_W_MAX，且贴轴那层不宽于外层
            if max(_pl["bands_up"], _pl["bands_dn"]) > _pl["max_bands"]:
                _plan_bad.append(f"{_n50}个{_c50}字 层数 "
                                 f"{_pl['bands_up']}/{_pl['bands_dn']} "
                                 f"超过上限 {_pl['max_bands']}")
            _cwb = _pl.get("card_w_by_band") or {}
            if _cwb and max(_cwb.values()) > _mb3.CARD_W_MAX + 0.5:
                _plan_bad.append(f"{_n50}个{_c50}字 某层卡宽 "
                                 f"{max(_cwb.values()):.0f} 超过上限 "
                                 f"{_mb3.CARD_W_MAX}")
            if _pl["stride"] < 1:
                _plan_bad.append(f"{_n50}个{_c50}字 步距 {_pl['stride']} 不合理")
            # 第二层：**不等式验算**。逐字段比对抓不住「参数自相矛盾」，
            # 而这些恒成立的关系抓得住（卡宽与列距不匹配、图宽与尺寸和不符、
            # 层数超上限、卡片总宽超过可用横向总量）。
            # 每条都在 24 组真实参数上筛过；有反例的不收 ——
            # 被筛掉的一条是 cw ≤ 2p-2c-2，它在 13 个事项以上不成立（优雅宽度加宽了）。
            for _vio in _mod("feasible").verify(_pl):
                _plan_bad.append(f"{_n50}个{_c50}字 验算不通过：{_vio}")
            if abs(_pl["card_w"] - _real_cw) > 1:
                _plan_bad.append(f"{_n50}个{_c50}字 卡宽 计划{_pl['card_w']:.0f}"
                                 f" 实画{_real_cw:.0f}")
            elif abs(_pl["height"] - _r50[1]) > 1:
                _plan_bad.append(f"{_n50}个{_c50}字 图高 计划{_pl['height']:.0f}"
                                 f" 实画{_r50[1]:.0f}")
            elif (_pl["bands_up"], _pl["bands_dn"]) != (_r50[2], _r50[3]):
                _plan_bad.append(f"{_n50}个{_c50}字 层数 计划"
                                 f"{_pl['bands_up']}/{_pl['bands_dn']}"
                                 f" 实画{_r50[2]}/{_r50[3]}")
    if _plan_bad:
        msgs.append(f"plan_only 与实画不一致 {len(_plan_bad)} 处（如 "
                    f"{_plan_bad[0]}）—— 前端会照着一个不准的参数写字")

    # 另外三档的 plan_only 也要与实画一致（纵向、日期型、期间型）
    _vm5 = _mod("render_vcolumns")
    for _n55 in (13, 20, 30, 45, 60):
        _m55 = _CP.deepcopy(json.load(open(os.path.join(
            SKILL, "examples", "two-sides-numbered.json"), encoding="utf-8")))
        _pr55 = _m55["events"][0]
        _ev55 = []
        for _i55 in range(_n55):
            _e55 = _CP.deepcopy(_pr55)
            _e55["id"] = str(_i55 + 1)
            _e55["head"] = "事" * 20
            for _k55 in ("head_short", "body", "emphasis", "index_note"):
                _e55.pop(_k55, None)
            _e55["lane"] = "P" if _i55 % 2 == 0 else "D"
            _e55["time"] = {"certainty": "exact", "origin": "extracted",
                            "kind": "occur", "raw": "x",
                            "date": f"20{20 + _i55 // 12}/{_i55 % 12 + 1}/"
                                    f"{_i55 % 27 + 1}", "date_text": "x"}
            _ev55.append(_e55)
        _m55["events"] = _ev55
        try:
            _p55 = _vm5.render(_CP.deepcopy(_m55), None, plan_only=True)
        except Exception:
            continue
        _t55 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
        _t55.close()
        try:
            _r55 = _vm5.render(_CP.deepcopy(_m55), _t55.name)
        except Exception:
            continue
        finally:
            os.unlink(_t55.name)
        if abs(_p55["height"] - _r55[1]) > 1 or _p55["cols"] != _r55[2]:
            msgs.append(f"纵向 plan_only 与实画不一致（{_n55} 个）："
                        f"图高 {_p55['height']:.0f}/{_r55[1]:.0f}、"
                        f"列数 {_p55['cols']}/{_r55[2]}")
    for _fn56, _mod56, _ex56 in (("日期型", "render_dated_v2", "dated-limitation.json"),
                                 ("期间型", "render_spans_v2", "gantt-periods.json")):
        _mm56 = json.load(open(os.path.join(SKILL, "examples", _ex56),
                               encoding="utf-8"))
        _r56 = _mod(_mod56)
        try:
            _p56 = _r56.render(_CP.deepcopy(_mm56), plan_only=True)
            _svg56, _w56, _h56 = _r56.render(_CP.deepcopy(_mm56))
        except Exception as _e56:
            msgs.append(f"{_fn56} plan_only 出错：{str(_e56).splitlines()[0][:50]}")
            continue
        if abs(_p56["height"] - _h56) > 1 or _p56["width"] != _w56:
            msgs.append(f"{_fn56} plan_only 与实画不一致："
                        f"{_p56['width']}x{_p56['height']:.0f} 对 {_w56}x{_h56}")

    # ---- [M12] 三行以上的正文必须左对齐（两端对齐的实际做法）----------------
    # 这条守卫是补出来的，起因：日期型自己画了一遍正文、**恒为居中**，八行的卡片
    # 每行左右缺口各不相同，边是锯齿状的。横向与纵向早就改用共用出口 geom.body_lines，
    # 日期型漏掉了（这份文件从 v1 逐字复制来，抽出共用出口那一轮没换它）。
    # **为什么两千多组穷举一次也没抓到**：穷举里日期型的 head 都很短、只有一行，
    # 而这条规则只在三行以上才生效 —— 样本没让被测的项变化。所以这里专门造多行样本。
    _al_bad = []
    _dal = _mod("render_dated_v2")
    _mal = _mod("render_multiband")
    _val = _mod("render_vcolumns")
    _LONGTXT = ("二零一八年四月十日，双方签订股权转让协议，约定标的公司当年度净利润"
                "不低于六百万元，未达标时由转让方以现金方式补偿差额部分，"
                "补偿款应于审计报告出具后三十日内付清")

    def _anchors(_svg):
        _fs = _dal.FS_BODY
        _got = {}
        for _m in re.finditer(r'<text[^>]*font-size="%d"[^>]*>' % _fs, _svg):
            _a = re.search(r'text-anchor="(\w+)"', _m.group(0))
            _k = _a.group(1) if _a else "start"
            _got[_k] = _got.get(_k, 0) + 1
        return _got

    # 日期型：多行正文
    for _cc in (60, 84, 120):
        _mm = {"schema_version": 2, "layout": "dated_point_timeline",
               "title_text": "对齐守卫", "events": [
                   {"id": str(_i + 1), "head": _LONGTXT[:_cc], "unit_type": "fact",
                    "source": {"file": "x", "locator": "x"},
                    "time": {"certainty": "exact", "origin": "extracted",
                             "kind": "occur", "raw": "x", "date": _d,
                             "date_text": _d.replace("/", ".")}}
                   for _i, _d in enumerate(["2018/4/10", "2020/4/10", "2021/9/1",
                                            "2023/2/15", "2024/6/20"])]}
        try:
            _rr = _dal.render(_CP.deepcopy(_mm))
            _sv = _rr[0] if isinstance(_rr, tuple) else _rr
        except Exception as _e:
            _al_bad.append(f"日期型 {_cc} 字渲染失败：{str(_e).splitlines()[0][:40]}")
            continue
        _got = _anchors(_sv)
        if _got.get("middle"):
            _al_bad.append(f"日期型 {_cc} 字：{_got['middle']} 处正文居中，"
                           f"三行以上必须左对齐（M12）")
    # 横向与纵向：同样造多行样本，两个方向都盯
    def _mk_al(_n, _layout):
        return {"schema_version": 2, "layout": _layout, "title_text": "对齐守卫",
                "lanes": [{"id": "P", "label_text": "原告主张"},
                          {"id": "D", "label_text": "被告主张"}],
                "events": [{"id": str(_i + 1), "head": _LONGTXT[:60], "unit_type": "fact",
                            "lane": "P" if _i % 2 == 0 else "D",
                            "source": {"file": "x", "locator": "x"},
                            "time": {"certainty": "exact", "origin": "extracted",
                                     "kind": "occur", "raw": "x",
                                     "date": f"20{20 + _i // 12}/{_i % 12 + 1}/{_i % 27 + 1}",
                                     "date_text": "x"}} for _i in range(_n)]}
    for _tag_al, _mod_al, _lay_al, _n_al in (("横向", _mal, "numbered_point_timeline", 6),
                                             ("纵向", _val, "vertical_two_columns", 10)):
        _t_al = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
        _t_al.close()
        try:
            _mod_al.render(_CP.deepcopy(_mk_al(_n_al, _lay_al)), _t_al.name)
            _got = _anchors(open(_t_al.name, encoding="utf-8").read())
            if _got.get("middle"):
                _al_bad.append(f"{_tag_al} 多行正文：{_got['middle']} 处居中，"
                               f"三行以上必须左对齐（M12）")
        except Exception as _e:
            _al_bad.append(f"{_tag_al} 多行样本渲染失败：{str(_e).splitlines()[0][:40]}")
        finally:
            os.unlink(_t_al.name)
    # 反向也要盯：一两行时必须居中，否则把规则写成「永远左对齐」也会通过
    _mm1 = {"schema_version": 2, "layout": "dated_point_timeline", "title_text": "对齐守卫",
            "events": [{"id": str(_i + 1), "head": "签订协议", "unit_type": "fact",
                        "source": {"file": "x", "locator": "x"},
                        "time": {"certainty": "exact", "origin": "extracted",
                                 "kind": "occur", "raw": "x", "date": _d,
                                 "date_text": _d.replace("/", ".")}}
                       for _i, _d in enumerate(["2018/4/10", "2020/4/10", "2021/9/1",
                                                "2023/2/15", "2024/6/20"])]}
    try:
        _rr1 = _dal.render(_CP.deepcopy(_mm1))
        _sv1 = _rr1[0] if isinstance(_rr1, tuple) else _rr1
        if _anchors(_sv1).get("start"):
            _al_bad.append("日期型单行正文被左对齐了 —— 一两行应当居中（M12）")
    except Exception as _e:
        _al_bad.append(f"日期型单行样本渲染失败：{str(_e).splitlines()[0][:40]}")
    # ---- 两端对齐：三条判据，正反两个方向都盯 -------------------------------
    # 判据一（正向）：**宽卡多行必须真的拉**。少了这一条，把两端对齐整个关掉也会通过。
    # 判据二（反向）：**窄卡不许拉**。横向 13 个事项时每缝要加 4.4px，那是作者判难看的
    #                那一档（他量的是 1.8px），必须退回左对齐。
    # 判据三：**一张卡不许混**两种边（逐行判就会混，作者否过同一张图里混样式）。
    # 三条都要有自己的样本：常驻产物的正文只有一两行，这三件事在那上面全测不到。
    _J_LIM2 = 1.0
    def _tally(_svg):
        """数正文行的账：返回（总行数, 拉过的行数）。

        **不分组。** 第一版试过按 `<g data-role="event">` 分组（只有日期型这么包，
        纵向根本没有这个组，于是在纵向上取到零张卡）；第二版试过按 y 是否连续切卡
        （纵向两侧两列的 y 是交错的，把十张卡并成了一张）。
        正确的口径不需要分组：**每张卡只有末行不拉**，所以
        拉过的行数必须恰好等于 总行数 − 卡片数。实测纵向 30 行、10 张卡、拉过 20，
        日期型 30 行、5 张卡、拉过 25，账都对得上。
        """
        _all = re.findall(r'<text[^>]*font-size="13"[^>]*>([^<]*)</text>', _svg)
        _pl = re.findall(r'<text[^>]*font-size="13"[^>]*lengthAdjust="spacing"[^>]*>'
                         r'([^<]*)</text>', _svg)
        return len(_all), len(_pl)

    # 宽卡：日期型六行（5 张卡）、纵向多行（10 张卡）—— 非末行必须全部拉过
    _wide = []
    _mmj = {"schema_version": 2, "layout": "dated_point_timeline", "title_text": "对齐守卫",
            "events": [{"id": str(_i + 1), "head": _LONGTXT[:84], "unit_type": "fact",
                        "source": {"file": "x", "locator": "x"},
                        "time": {"certainty": "exact", "origin": "extracted",
                                 "kind": "occur", "raw": "x", "date": _d,
                                 "date_text": _d.replace("/", ".")}}
                       for _i, _d in enumerate(["2018/4/10", "2020/4/10", "2021/9/1",
                                                "2023/2/15", "2024/6/20"])]}
    try:
        _rj = _dal.render(_CP.deepcopy(_mmj))
        _wide.append(("日期型六行", _rj[0] if isinstance(_rj, tuple) else _rj, 5))
    except Exception as _e:
        _al_bad.append(f"日期型六行样本渲染失败：{str(_e).splitlines()[0][:40]}")
    _tw2 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
    _tw2.close()
    try:
        _val.render(_CP.deepcopy(_mk_al(10, "vertical_two_columns")), _tw2.name)
        _wide.append(("纵向多行", open(_tw2.name, encoding="utf-8").read(), 10))
    except Exception as _e:
        _al_bad.append(f"纵向多行样本渲染失败：{str(_e).splitlines()[0][:40]}")
    finally:
        os.unlink(_tw2.name)
    for _tag_j, _svg_j, _ncard_j in _wide:
        _tot_j, _pull_j = _tally(_svg_j)
        if _tot_j < _ncard_j * 3:
            _al_bad.append(f"{_tag_j}：正文只有 {_tot_j} 行、{_ncard_j} 张卡，"
                           f"没到三行，样本没测到该测的项")
            continue
        # 判据一（正向）：该拉的必须拉。少了这一条，把两端对齐整个关掉也会通过。
        if _pull_j != _tot_j - _ncard_j:
            _al_bad.append(f"{_tag_j}：{_tot_j} 行正文、{_ncard_j} 张卡，"
                           f"拉过 {_pull_j} 行，应为 {_tot_j - _ncard_j} 行"
                           f"（每张卡只有末行不拉）")
    # 判据三：**一张卡不许混两种边**。要抓这条，样本必须挑在**卡内余量跨门槛**的那一档
    # ——实测横向 10 个事项 40 字时，同一张卡里各行每缝要加的量从 -0.56px 到 1.00px，
    # 跨过门槛。整卡判时这张卡整体不拉（有一行的余量是负的）；改成逐行判就会出现
    # 有的行拉、有的行不拉。第一版守卫用的样本各行余量都在门槛内，混排根本不会发生，
    # 所以那个改坏漏过了 —— 又一次「样本没让被测的项变化」。
    _tm2 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
    _tm2.close()
    try:
        _mmix = _mk_al(10, "numbered_point_timeline")
        for _e_mix in _mmix["events"]:
            _e_mix["head"] = _LONGTXT[:40]
        _mal.render(_CP.deepcopy(_mmix), _tm2.name)
        _smix = open(_tm2.name, encoding="utf-8").read()
        if "lengthAdjust" in _smix:
            _al_bad.append("横向 10 个事项 40 字：同一张卡里各行余量跨门槛"
                           "（-0.56 到 1.00px），整卡应当不拉，却出现了两端对齐 —— "
                           "一张卡不许混两种边")
    except Exception as _e:
        _al_bad.append(f"混排样本渲染失败：{str(_e).splitlines()[0][:40]}")
    finally:
        os.unlink(_tm2.name)
    # 判据二（反向）：窄卡不许拉。横向 13 个事项卡内 100px、每缝要加 4.4px，
    # 那已经超过作者判难看的 1.8px，必须退回左对齐。
    _tn2 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
    _tn2.close()
    try:
        _mal.render(_CP.deepcopy(_mk_al(13, "numbered_point_timeline")), _tn2.name)
        if "lengthAdjust" in open(_tn2.name, encoding="utf-8").read():
            _al_bad.append("横向 13 个事项（卡内 100px、每缝要加 4.4px）被两端对齐了 —— "
                           "这一档该退回左对齐")
    except Exception as _e:
        _al_bad.append(f"横向窄卡样本渲染失败：{str(_e).splitlines()[0][:40]}")
    finally:
        os.unlink(_tn2.name)
    if _al_bad:
        msgs.append(f"正文对齐不合 M12：{len(_al_bad)} 处（{_al_bad[0]}）")

    # ---- PPT 里图名必须在顶部，且框高就是那一行的高度 -------------------------
    # 真问题：PPT 打开后图名跑到了页面正中。查出来是 v1 的 attach_text 会把
    # 「包住某段文字、且那段文字水平居中于它」的矩形当成宿主，而它只排除了
    # 「面积 ≥ 画布 90%」的背景 —— 我们的图有**两层白底**：paper.frame 加的整幅
    # （100%，被排除）与渲染器自己画的内容底（**只占 80.4%**，从那条线底下漏过去），
    # 于是图名被内容底收养，框高变成整张图的高度、文字在框里垂直居中。
    # 修法：渲染器给内容底打 data-role="canvas-bg"，export_formats 在交给 v1 之前
    # 把这一行摘掉（不改 v1、也不改交付的那张 SVG）。
    # 守卫盯**产物**：图名的框高不许超过页高的两成，且必须落在页面上半。
    _t_pp = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
    _t_pp.close()
    try:
        _m_pp = json.load(open(os.path.join(SKILL, "examples",
                                           "two-sides-numbered.json"), encoding="utf-8"))
        _title_pp = str(_m_pp.get("title_text") or "").strip()
        _mod("render_multiband").render(_CP.deepcopy(_m_pp), _t_pp.name)
        # **必须走真实交付路径**：渲染器直出的 SVG 里内容底占画布 100%，v1 的
        # attach_text 会按「≥90% 就是背景」把它排除，所以直出的产物上这个 bug
        # 根本不出现。只有经过 paper.frame 裱上整幅白边之后，内容底才掉到约 80%，
        # 从那条线底下漏过去、把图名收养。第一版守卫拿直出产物测，两个改坏全漏（验过）。
        # 注意读写顺序：**先把内容读完再打开写**。写成
        # open(p,"w").write(frame(open(p).read())) 会先截断再读，读到的是空文件 ——
        # 我在探针里踩过一次，然后据此得出了「裱框会让导出变成 0 个对象」这个错结论，
        # 白查了好几轮。凡是「读同一个文件再写回去」，两步必须分开。
        _pap = _mod("paper")
        _raw_pp = open(_t_pp.name, encoding="utf-8").read()
        open(_t_pp.name, "w", encoding="utf-8").write(
            _pap.frame(_raw_pp, landscape=True))
        _got_pp = _mod("export_formats").deliver(_t_pp.name, _m_pp)
        _pptx = [_pth for _f, _pth, _n in _got_pp if _f == "PPTX" and _pth]
        if not _pptx:
            msgs.append("PPT 没出来，图名位置无从验证")
        elif _title_pp:
            import zipfile as _zp
            _z = _zp.ZipFile(_pptx[0])
            _sl = _z.read("ppt/slides/slide1.xml").decode()
            _pr = _z.read("ppt/presentation.xml").decode()
            _cy = int(re.search(r'sldSz[^>]*cy="(\d+)"', _pr).group(1))
            _hit = False
            for _sp in re.findall(r"<p:sp>.*?</p:sp>", _sl, re.S):
                _tx = "".join(re.findall(r"<a:t>([^<]*)</a:t>", _sp))
                if _title_pp[:6] and _title_pp[:6] in _tx:
                    _hit = True
                    _o = re.search(r'<a:off x="(-?\d+)" y="(-?\d+)"/>'
                                   r'<a:ext cx="(\d+)" cy="(\d+)"', _sp)
                    _y, _h = int(_o.group(2)), int(_o.group(4))
                    if _h > 0.2 * _cy:
                        msgs.append(f"PPT 里图名的框高占页面 {_h/_cy*100:.0f}%"
                                    f"（应当只有一行的高度）—— 它被背景矩形收养了，"
                                    f"于是文字垂直居中到页面中部")
                    if _y > 0.35 * _cy:
                        msgs.append(f"PPT 里图名落在页面 {_y/_cy*100:.0f}% 高处，"
                                    f"图名应当在顶部")
                    break
            if not _hit:
                msgs.append("PPT 里找不到图名那个对象")
    except Exception as _e_pp:
        msgs.append(f"验 PPT 图名时出错：{type(_e_pp).__name__}: {str(_e_pp)[:60]}")
    finally:
        for _ext in (".svg", ".png", ".pptx", ".vsdx", ".drawio", ".drawio.svg",
                     ".__export__.svg"):
            _q = os.path.splitext(_t_pp.name)[0] + _ext
            if os.path.exists(_q):
                os.unlink(_q)

    # ---- 溯源索引里不许出现直角引号 ------------------------------------------
    # 作者的要求：正式文稿用中文引号（“”），不用直角引号（「」『』）。
    # 转换收在 trace_index.js 的 run() 一个出口做 —— 逐处改过一版，尾注那句就漏了。
    try:
        import subprocess as _sp2
        _pay2 = {"title": "引号守卫", "rows": [
            {"no": "1", "head": "「甲」与『乙』签约", "file": "材料",
             "locator": "句1", "quote": "「原文」。"}]}
        _tj2 = _TF.NamedTemporaryFile(suffix=".json", delete=False, mode="w",
                                      encoding="utf-8")
        json.dump(_pay2, _tj2, ensure_ascii=False)
        _tj2.close()
        _td2 = _tj2.name.replace(".json", ".docx")
        _sp2.run(["node", os.path.join(SKILL, "scripts", "trace_index.js"),
                  _tj2.name, _td2], capture_output=True, text=True, timeout=120)
        if not os.path.exists(_td2):
            # **报清原因**，不要只说「没出来」。CI 上撞过一次：trace_index.js
            # require("docx")（npm 的包，本机是全局装的、仓库里没有 package.json），
            # CI 那台机器没有它，于是这条只印「没出来」，看不出该装什么。
            _why2 = ""
            _r2 = _sp2.run(["node", "-e", "require('docx')"],
                           capture_output=True, text=True, timeout=60)
            if _r2.returncode:
                _why2 = ("：node 找不到 docx 包（npm install -g docx@9）"
                         if "Cannot find module" in (_r2.stderr or "")
                         else f"：node 报 {(_r2.stderr or '')[:60]}")
            msgs.append(f"引号守卫：索引文档没出来{_why2}")
        else:
            import zipfile as _zp2
            _dx2 = _zp2.ZipFile(_td2).read("word/document.xml").decode()
            _bad2 = [c for c in "「」『』" if c in _dx2]
            if _bad2:
                msgs.append(f"溯源索引里出现直角引号 {''.join(_bad2)} —— "
                            f"正式文稿要用中文引号")
            if "\u201c" not in _dx2:
                msgs.append("溯源索引里没有中文引号 —— 转换可能把引号整个吃掉了")
        for _q in (_tj2.name, _td2):
            if os.path.exists(_q):
                os.unlink(_q)
    except Exception as _e_q:
        msgs.append(f"验引号时出错：{type(_e_q).__name__}: {str(_e_q)[:60]}")

    # ---- 五种可编辑格式：三种图种都要出得齐 ----------------------------------
    # 作者的要求：新项目也要有 v1 那五种（SVG / PNG / PPTX / VSDX / drawio）。
    # 做法是**复用 v1 的导出器**（pptx 与 vsdx 读最终 SVG 逐元素转，所以「交付的就是
    # 那张图」；drawio 从语义地图建模），只加一个把 v2 地图摊平的适配器 ——
    # v1 的导出器读 ev["text"] 与顶层 date_text，而 v2 放在 head 与 time 里，
    # 另外 v2 多了 numbered_multiband / vertical_* 这些 layout 名，几何上仍是编号型。
    # 守卫盯两件：三种图种五种格式都出得来（少一种就报），以及**适配器不许改原地图**。
    _ef = _mod("export_formats")
    _fmt_bad = []
    _fmt_cases = []
    # 第一个样本特意取 **numbered-multiband.json** —— 它的 layout 是 v2 新增的
    # `numbered_multiband`，v1 的 drawio 导出器不认识，必须靠 _LAYOUT_ALIAS 映射。
    # 先只用 v1 认识的那三种 layout 做样本，于是把别名整条删掉守卫也照样通过（验过）：
    # 又是「样本没让被测的项变化」。
    for _fn_e, _modname in (("numbered-multiband.json", "render_vcolumns"),
                            ("two-sides-numbered.json", "render_multiband"),
                            ("dated-limitation.json", "render_dated_v2"),
                            ("gantt-periods.json", "render_spans_v2")):
        _m_e = json.load(open(os.path.join(SKILL, "examples", _fn_e), encoding="utf-8"))
        _t_e = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
        _t_e.close()
        try:
            _file_api = _modname in ("render_multiband", "render_vcolumns")
            _r_e = (_mod(_modname).render(_CP.deepcopy(_m_e), _t_e.name) if _file_api
                    else _mod(_modname).render(_CP.deepcopy(_m_e)))
            if not _file_api:
                _sv = _r_e[0] if isinstance(_r_e, tuple) else _r_e
                open(_t_e.name, "w", encoding="utf-8").write(_sv)
            _before = json.dumps(_m_e, sort_keys=True, ensure_ascii=False)
            _got = _ef.deliver(_t_e.name, _m_e)
            if json.dumps(_m_e, sort_keys=True, ensure_ascii=False) != _before:
                _fmt_bad.append(f"{_fn_e}：适配器改了调用方的地图（必须不改原件）")
            _missing = [_f for _f, _pth, _n in _got if not _pth]
            if _missing:
                _fmt_bad.append(f"{_fn_e}：{'、'.join(_missing)} 没出来"
                                f"（{[n for f, p, n in _got if not p][0][:40]}）")
            _fmt_cases.append((_fn_e, len([1 for _f, _pth, _n in _got if _pth])))
        except Exception as _e_e:
            _fmt_bad.append(f"{_fn_e}：出格式时抛错 {str(_e_e).splitlines()[0][:50]}")
        finally:
            for _ext in (".svg", ".png", ".pptx", ".vsdx", ".drawio", ".drawio.svg"):
                _q = os.path.splitext(_t_e.name)[0] + _ext
                if os.path.exists(_q):
                    os.unlink(_q)
    if _fmt_bad:
        msgs.append(f"五种格式没出齐：{len(_fmt_bad)} 处（{_fmt_bad[0]}）")
    elif any(n < 6 for _f, n in _fmt_cases):
        msgs.append(f"五种格式（含 drawio.svg 共 6 个文件）有缺：{_fmt_cases}")

    # ---- 「判 exact 必须有到日的表述」要认多种写法 -------------------------------
    # 判据的本意是挡住**凭空加精度**：材料只说「2020 年」而判 exact 加 2020/1/1，
    # 那是把年份当成了一月一日。第一版只认「N 日」这一种写法，真材料上当场撞坏：
    # 两份扫描合同的供货清单写的是 `供货时间 2020.3.15`，判据说「该句没有到日的表述」，
    # 把**已经精确到日**的骨架拦在门外 —— 实测那一份材料 16 个事项里 8 个被拦、图出不来。
    # 而这种写法不是特例：四份真材料里三份都有（支付宝流水 2 处、扫描证据目录 3 处、
    # 两份合同 29 处），因为表格类材料（清单、流水、票据）天然用点分格式。
    # 判据挡错了对象 —— 它要挡的是精度不足，不是写法不同。
    #
    # **修的时候不许放水**：不能改成「句中出现这个数字就算」，那样「数量 15」
    # 「第 15 条」「金额 20200315」都会被当成 15 日，等于把这条判据废掉。
    # 所以两个方向都要盯：该过的四种写法要过，该拦的七种错要照旧拦住。
    _cd = _mod("check_model_output")

    def _day_err(_sent, _raw, _date):
        _e, _ = _cd.check_dates([_sent], [{"i": 0, "certainty": "exact",
                                           "kind": "occur", "raw": _raw,
                                           "date": _date}])
        return [_x for _x in _e if "到日" in _x]
    for _sent, _raw, _date, _tag in (
            ("2021年11月9日送达被申请人。", "2021年11月9日", "2021/11/9", "年月日"),
            ("供货时间 2020.3.15，材料 盒尺。", "2020.3.15", "2020/3/15", "点分"),
            ("交易时间 2024-12-05 11:41:47。", "2024-12-05", "2024/12/5", "横线带零"),
            ("2020/3/15 到货。", "2020/3/15", "2020/3/15", "斜杠")):
        if _day_err(_sent, _raw, _date):
            msgs.append(f"到日的写法「{_tag}」被判成没有到日的表述 —— "
                        f"合法的骨架会被拦在门外（真材料上 8 个事项因此出不了图）")
    for _sent, _raw, _date, _tag in (
            ("2020 年签订了合同。", "2020 年", "2020/1/1", "只有年却判成 1 月 1 日"),
            ("2020 年 3 月签订。", "2020 年 3 月", "2020/3/1", "只到月却判成 1 日"),
            ("次年初交付全部成果。", "次年初", "2017/1/1", "推算出来的日期"),
            ("2021 年 11 月被其拉黑。", "2021 年 11 月", "2021/11/30", "只到月却判成月末"),
            ("数量 15，单位袋，2020 年 3 月。", "2020 年 3 月", "2020/3/15",
             "拿数量 15 当 15 日"),
            ("合同总价 20200315 元。", "20200315", "2020/3/15", "把金额当成日期"),
            ("第 15 条约定，2020 年 3 月供货。", "2020 年 3 月", "2020/3/15",
             "拿条款号当日")):
        if not _day_err(_sent, _raw, _date):
            msgs.append(f"「{_tag}」没有被拦住 —— 这条判据是挡凭空加精度的，"
                        f"放宽到认任意数字就等于废掉它")

    # ---- 扫描件与照片：读图这条路的账目 -----------------------------------------
    # 规矩见 HANDOVER 5.4 与 front-end.md：律师大概率会传扫描件，**不能因为难就砍掉
    # 这个能力**；让模型直接读图、不引 OCR 依赖，但图像来源的事项单独标记、
    # 交付时必须声明。做法照 Anthropic 官方 pdf-reading skill：
    # 探测 → 150 DPI 栅格化 → 模型逐页看图，用的全是 poppler 自带命令，
    # 不装模型不调 API，所以各家 harness 里都跑得动。
    _ri = _mod("read_image")
    # ① 探测的判据必须落在**可读字符数**，不是「pdffonts 有没有字体」。
    #    真材料反例：20 页扫描证据的 pdffonts 不是空表（第 20 页挂着一个空字体），
    #    而逐页可读字符全部为 0。判据看错就会把扫描件当文字型，然后什么都读不到。
    _ri_src = open(os.path.join(SKILL, "scripts", "read_image.py"),
                   encoding="utf-8").read()
    if "pdftotext" not in _ri_src or "chars_per_page" not in _ri_src:
        msgs.append("read_image 的探测没有逐页量可读字符 —— "
                    "只看 pdffonts 会被空字体骗过（真材料上撞过）")
    # ② 转写稿的账目核对：页数账 + 材料自带编号的缺号。三个方向都要盯，
    #    尤其第三条 —— 编号总数必须**单独声明**，不许从声明的区间反推：
    #    试过取区间最大值当上限，模型只转到第 30 项时缺号检查照样通过，
    #    那等于自己给自己划及格线（验过）。
    _decl_ok = [{"pages": "1", "series": "证据", "from": 1, "to": 6},
                {"pages": "2", "series": "证据", "from": 7, "to": 10},
                {"pages": "3-12", "series": "序号", "from": 1, "to": 38},
                {"pages": "13-15"}, {"pages": "16-19"}, {"pages": "20"}]
    _tot = {"证据": 10, "序号": 38}
    if _ri.check_transcript(_decl_ok, _tot, 20):
        msgs.append(f"完整的转写声明被判错："
                    f"{_ri.check_transcript(_decl_ok, _tot, 20)[0][:60]}")
    # 判据要认到**具体哪一条**在报，不能只看「有没有报」：漏一页会同时触发页数账
    # 与缺号两条，只判「非空」的话，把页数账整个关掉也照样通过（验过时漏了这个）。
    for _bad, _tag, _need in (
            ([_d for _d in _decl_ok if _d["pages"] != "2"], "漏了第 2 页",
             "没有交代"),
            ([dict(_d, **({"to": 30} if _d.get("series") == "序号" else {}))
              for _d in _decl_ok], "编号只转到 30（总数 38）", "缺号即漏转"),
            ([_d for _d in _decl_ok if _d.get("series") != "序号"], "整张表没转",
             "缺号即漏转")):
        _got_msgs = _ri.check_transcript(_bad, _tot, 20)
        if not any(_need in _x for _x in _got_msgs):
            msgs.append(f"转写稿漏转（{_tag}）没被「{_need}」那一条抓住"
                        f"（实报：{_got_msgs or '什么都没报'}）—— "
                        f"转写稿的句子是模型自己写的，完备性对账在这条路上会变成自证，"
                        f"外部锚（页数账 + 材料印着的编号总数）是唯一的把手")
    # ③ 表格里的一列日期压成一行，账目核对必须抓住 —— 真材料上犯过：
    #    两份扫描合同第 6 页是供货清单（序号 1 至 30、含一列供货时间），
    #    转写时写成一句「供货时间出现：2020.2.20、2020.2.26、…」，
    #    结果管线只认出 3 个事实句，14 个供货日画不成 14 个事项（一句只能成一个事项）。
    #    这条经验写进了 front-end.md，但**光写文档不配判据，下次照样会犯**。
    if not _ri.check_transcript([{"pages": str(_i)} for _i in range(1, 7)],
                                {"供货清单序号": 29}, 6):
        msgs.append("表格里的编号一条都没逐条声明（相当于压成一行），账目核对却通过 —— "
                    "转写时合掉的，后面再也拆不出来")
    #    反过来：逐条声明齐了就该通过，否则每次都误报、这条检查会被学会忽略
    if _ri.check_transcript(
            [{"pages": str(_i)} for _i in range(1, 6)] +
            [{"pages": "6", "series": "供货清单序号", "from": 1, "to": 29}],
            {"供货清单序号": 29}, 6):
        msgs.append("逐条声明齐全（1 至 29，总数 29）却被判缺号 —— "
                    "总数要按**有内容的项数**算，不按最大序号（那张表第 30 行是空行）")

    # ④ 探测在真材料上要判得对：文字型判 text_layer、纯扫描件判 scanned
    # 探测要在真 PDF 上验，而且**不许因为样本不存在就跳过**（那条守卫我第一版写成
    # `if os.path.exists(fixture)`，而那份 fixture 根本不存在，于是整条静默失效）。
    # 就地生成一份带文字层的 PDF 来验。
    _t_pdf = _TF.NamedTemporaryFile(suffix=".pdf", delete=False)
    _t_pdf.close()
    try:
        # 用 reportlab 造（test-only：出图那条路一行都不 import 它；
        # ps2pdf 容器里没有）。**缺了如实报出来，不抛裸的 ModuleNotFoundError** ——
        # 这条守卫的价值就在于「不许因为样本不存在就跳过」（见上面那段注释），
        # 所以也不许因为缺库就静默跳过；报出来，让 CI 日志一眼看出该装什么。
        try:
            from reportlab.pdfgen import canvas as _cv
        except ModuleNotFoundError:
            msgs.append("探测判据跑不了：本机没装 reportlab，造不出带文字层的 PDF 样本。"
                        "装它：pip install reportlab（CI 的 workflow 里已经装了）")
            raise _ProbeSkip()
        _c = _cv.Canvas(_t_pdf.name)
        for _i in range(2):
            _c.setFont("Helvetica", 12)
            _c.drawString(72, 720, f"text layer probe sample page {_i + 1}")
            _c.drawString(72, 700, "this page has a real font and extractable text")
            _c.showPage()
        _c.save()
        if os.path.getsize(_t_pdf.name) == 0:
            msgs.append("造不出带文字层的 PDF 样本，探测那一条守卫等于没跑")
        else:
            _v = _ri.probe(_t_pdf.name)
            if _v["verdict"] != "text_layer":
                msgs.append(f"有文字层的 PDF 被判成 {_v['verdict']} —— "
                            f"会白跑一趟读图（逐页字符：{_v['chars_per_page']}）")
            # **反向也要验**：扫描件必须判成 scanned。少了这一条，把探测写成
            # 「永远有字」也照样通过（验过），而那会让扫描件被当成文字型 ——
            # 后果比误判文字型严重得多：什么都读不到，图上直接少掉整份材料。
            # 造一份纯图 PDF：把上面那份 PDF 栅格化成图，再用 img2pdf 包回去。
            _t_img = _TF.NamedTemporaryFile(suffix=".pdf", delete=False)
            _t_img.close()
            try:
                import subprocess as _sp2
                _dir_img = _TF.mkdtemp()
                _sp2.run(["pdftoppm", "-jpeg", "-r", "80", _t_pdf.name,
                          os.path.join(_dir_img, "q")], capture_output=True,
                         timeout=120)
                _jpgs = sorted(os.path.join(_dir_img, f)
                               for f in os.listdir(_dir_img) if f.endswith(".jpg"))
                if not _jpgs:
                    msgs.append("造不出纯图 PDF 样本，探测的反向那一条等于没跑")
                elif not __import__("shutil").which("img2pdf"):
                    # **如实报缺，不抛裸的 FileNotFoundError。** CI 上撞过：
                    # 它被外层 except Exception 接住、报成「验探测判据时出错」，
                    # 看不出该装什么。img2pdf 是 test-only（出图那条路不 import 它）。
                    msgs.append("探测的反向那一条跑不了：本机没装 img2pdf，"
                                "造不出纯图 PDF 样本。装它：pip install img2pdf"
                                "（CI 的 workflow 里已经装了）")
                else:
                    _sp2.run(["img2pdf", "-o", _t_img.name] + _jpgs,
                             capture_output=True, timeout=120)
                    _v2 = _ri.probe(_t_img.name)
                    if _v2["verdict"] != "scanned":
                        msgs.append(f"纯图 PDF 被判成 {_v2['verdict']} —— "
                                    f"扫描件会被当成有文字层，于是一个字都读不到、"
                                    f"图上整份材料消失（逐页字符："
                                    f"{_v2['chars_per_page']}）")
            finally:
                for _f in (_t_img.name,):
                    if os.path.exists(_f):
                        os.unlink(_f)
    except _ProbeSkip:
        # 缺库的原因已经记进 msgs（那一条会让回归红），这里只做跳出，不重复报。
        pass
    except Exception as _e_pdf:
        msgs.append(f"验探测判据时出错：{type(_e_pdf).__name__}: {str(_e_pdf)[:50]}")
    finally:
        if os.path.exists(_t_pdf.name):
            os.unlink(_t_pdf.name)

    # ---- 承诺或约定的时点不是事实（告警型判据）-----------------------------------
    # 时间轴画的是客观发生过的事，不是将要发生的事。这一类最险，因为它
    # **过得了全部句法判据**：日期在材料里、精确到日、正文是原句的子序列、raw 逐字可查。
    # 真实犯过：一份被告盖章的付款计划给了四个付款日（5-18 十万、5-30 十万、
    # 6-10 十五万、6-20 九万四千余），四个日期四笔金额，看起来是整套材料里最好的
    # 时间轴素材，而它们全是承诺 —— **把承诺画成事实，是这张图能犯的最严重的错之一**。
    # 措辞是「请核对」不是错误：承诺与事实的分界要读懂意思才判得准，词表只能提示
    # （「承诺书于当日送达」里那个「承诺」是文书名，送达本身是事实，判成错误会拦死它）。
    _cf = _mod("check_model_output")
    _S_fut = ["2022年5月18日：100,000元。",
              "至2022年5月30日，拖欠货款本金合计为803439.55元。",
              "上述催告函于2021年11月9日送达被申请人。",
              "针对于我司2021年的逾期货款444859.86的情况，做出以下的付款计划。"]
    for _it_fut, _want_fut, _tag_fut in (
            ([{"id": "1", "kind": "due", "raw": "2022年5月18日", "src_sids": [0, 3],
               "head": "2022年5月18日：100,000元"}], True, "付款计划的承诺日"),
            ([{"id": "2", "kind": "due", "raw": "2022年5月30日", "src_sids": [1],
               "head": "拖欠货款本金合计为803439.55元"}], False, "至某日拖欠（已发生）"),
            ([{"id": "3", "kind": "arrive", "raw": "2021年11月9日", "src_sids": [2],
               "head": "催告函送达被申请人"}], False, "送达（kind 不是 due）")):
        _got_fut = bool(_cf.check_future_as_fact(_it_fut, _S_fut))
        if _got_fut != _want_fut:
            msgs.append(f"承诺时点判据在「{_tag_fut}」上判错："
                        f"{'报了' if _got_fut else '没报'}，应当{'报' if _want_fut else '不报'}"
                        + ("　—— 承诺被当成事实画上去，四条句法铁律都拦不住"
                           if _want_fut else "　—— 误报会把已发生的事也标成可疑"))
    if "C.check_future_as_fact(" not in open(
            os.path.join(SKILL, "scripts", "pipeline.py"), encoding="utf-8").read():
        msgs.append("pipeline 没有调 check_future_as_fact —— 判据在、交付路径不读它")

    # ---- 已解决的事，不许在别处还被描述成待做 --------------------------------------
    # 自检扫出来的四处真矛盾：RELAY-1 还写着「第一版不做 OCR」（读图七份材料跑过了）、
    # HANDOVER 还写着「SKILL.md 不存在，这是它不能用的直接原因」（已补上）、
    # 两处还写着「串一条入口命令」（已加 next）。
    #
    # **危害是实的，而且我自己撞过**：这几十轮里我读到旧说法就断定「管线不认 PDF」，
    # 而 HANDOVER 620 行早写着 PDF 是主力格式 —— 白查了几轮。
    # 旧文字不删（它记着当时为什么没做，是历史），但必须就地标注「已解决」。
    _live = {
        "读图": (r"不做\s*OCR|不支持扫描件|无法读图",
                 os.path.join(SKILL, "scripts", "read_image.py"),
                 "扫描件读图已经做了（ADR 0001、0002）"),
        "SKILL.md": (r"`?SKILL\.md`?\s*不存在|无法被触发",
                     os.path.join(SKILL, "SKILL.md"),
                     "SKILL.md 已补上"),
    }
    for _tag, (_pat, _proof, _why) in _live.items():
        if not os.path.exists(_proof):
            continue                      # 那件事确实还没做，旧说法是对的
        for _fn in ("HANDOVER.md", "RELAY-1-overview.md", "RELAY-2-mapping.md",
                    "RELAY-3-retrospective.md", "SKILL.md",
                    "references/front-end.md", "references/model-steps.md"):
            _fp = os.path.join(SKILL, _fn)
            if not os.path.exists(_fp):
                continue
            _lines = open(_fp, encoding="utf-8").read().splitlines()
            for _i, _l in enumerate(_lines):
                if not re.search(_pat, _l):
                    continue
                # 就地标注算数：本行或紧随其后的三行里出现「已解决」即可
                _near = "".join(_lines[_i:_i + 4])
                if "已解决" not in _near:
                    msgs.append(f"{_fn} 第 {_i + 1} 行还把「{_tag}」说成没做，"
                                f"而 {_why} —— 旧文字可以留（那是历史），"
                                f"但要就地标注「已解决」，"
                                f"否则下一个接手的人会照着它白查几轮")

    # ---- next 说的「走到第几步」必须与代码实际写的 state 键一致 ---------------------
    # 九步各自的报错本来就清楚（「缺 state.json，先跑 read」），缺的是**中途接手时
    # 没人说得清当前状态** —— 换会话、跑错顺序、文件写坏之后，模型只能靠 state 里有
    # 哪些键去推断走到第几步，那是猜。`pipeline.py next` 就是回答这一问的。
    #
    # 它**不做「一键出图」**：九步里有四步必须等用户回答（勾材料、勾时间段、选风格、
    # 勾部分），一口气跑完等于替用户答了那四轮，而那四轮是这个 skill 的设计核心。
    #
    # 这条守卫盯的是 next 里那张表的**键名**：第一版把 budget 那一步的键猜成
    # picked_parts，而 stage_budget 实际写的是 picked_ids，于是勾完部分之后
    # next 还在说「下一步 budget」。**凭记忆写键名就是这个下场。**
    _pl_next = open(os.path.join(SKILL, "scripts", "pipeline.py"),
                    encoding="utf-8").read()
    if "def stage_next" not in _pl_next:
        msgs.append("pipeline 没有 next 命令 —— 中途接手时没人说得清走到第几步")
    else:
        _tbl = _pl_next[_pl_next.index("def stage_next"):]
        _tbl = _tbl[:_tbl.index("def stage_steps")]
        _keys = re.findall(r'\("(\w+)", "[^"]*", "[^"]*"', _tbl)
        if len(_keys) < 7:
            msgs.append(f"next 的步骤表只认出 {len(_keys)} 步 —— 九步少一步，"
                        f"中途接手就会被指到错的下一步")
        for _k in _keys:
            # 每个键都必须是某一步真的会写进 state 的
            if f'st["{_k}"]' not in _pl_next:
                msgs.append(f"next 拿 state 的 {_k!r} 判进度，而没有任何一步写这个键 —— "
                            f"那一步会被永远判成没做（第一版就把 picked_ids 猜成了 "
                            f"picked_parts）")
        # 一键出图不许出现：那会把四轮交互替用户答掉
        if re.search(r'def stage_(all|auto|oneshot)\b', _code_only(_pl_next)):
            msgs.append("pipeline 出现了一键出图的入口 —— 九步里有四步必须等用户回答，"
                        "替他答掉等于废掉这个 skill 的交互设计")

    # ---- SKILL.md 不许描述不存在的东西 --------------------------------------------
    # HANDOVER 619 行的告诫：「管线通了再写，否则会描述一个不存在的东西」。
    # SKILL.md 是这个 skill 唯一的入口（没有它就无法被触发），而它点到的每个文件、
    # 每条命令都是承诺 —— 承诺落空的代价是模型照着它去调一个不存在的命令。
    # 写这份文档时就犯过一次：把判据条数写成 226，实际 230。所以数字一律不写死。
    _sk_md = os.path.join(SKILL, "SKILL.md")
    if not os.path.exists(_sk_md):
        msgs.append("SKILL.md 不存在 —— 这个 skill 无法被触发，所有能力都调不到")
    else:
        _sk = open(_sk_md, encoding="utf-8").read()
        # 前置字段：name 与目录名一致、description 非空（触发全靠它）
        # SKILL 是 os.path.join(HERE, "..")，没规范化过，basename 会取到 ".."，
        # 所以这里自己取绝对路径（不改那个变量 —— 别处都在用它）。
        _sk_dir = os.path.basename(os.path.abspath(SKILL))
        if f"name: {_sk_dir}" not in _sk:
            msgs.append(f"SKILL.md 的 name 与目录名 {_sk_dir} 不一致 —— "
                        f"装进 harness 后会找不到")
        if "description:" not in _sk:
            msgs.append("SKILL.md 缺 description —— 那是唯一的触发依据")
        # 点到的文件必须存在
        for _rel in sorted(set(re.findall(
                r"`(references/[\w.-]+|scripts/[\w.]+|tests/[\w.]+)`", _sk))):
            if not os.path.exists(os.path.join(SKILL, _rel)):
                msgs.append(f"SKILL.md 点到 {_rel}，而它不存在 —— "
                            f"模型会照着它去读一个没有的文件")
        # 点到的命令必须真能跑
        _src_cmd = "".join(
            open(os.path.join(SKILL, "scripts", _n), encoding="utf-8").read()
            for _n in ("pipeline.py", "read_image.py"))
        for _c in sorted(set(re.findall(r"pipeline\.py (\w+)", _sk)
                             + re.findall(r"read_image\.py (\w+)", _sk))):
            if f'"{_c}"' not in _src_cmd and f"def stage_{_c}" not in _src_cmd:
                msgs.append(f"SKILL.md 写着命令 {_c}，而脚本里没有它 —— "
                            f"模型会照着它去调一个不存在的命令")
        # 九步的命令名必须与 pipeline 的 STEPS 对得上（表漂了就等于说明书错了）
        for _need in ("read", "pick", "span", "style", "offer", "budget",
                      "capacity", "title", "render"):
            if f"`{_need}" not in _sk:
                msgs.append(f"SKILL.md 的工作流表里没有第 {_need} 步 —— "
                            f"九步少一步，用的人就卡在那里")

    # ---- 侧标签必须活到 SVG / PPTX / VSDX 三种交付物里 -----------------------------
    # 自检量出来的：SVG / PPTX / VSDX 三种都有侧标签，**drawio 一律没有**。
    # drawio 那一处不是坏了，是 v1 的 export_drawio 从来不知道泳道的存在
    # （那份 564 行的建模器里一处 lane 都没有，v1 自己的示例也没有任何图用泳道）——
    # 泳道是 v2 新增的能力。明确不补，理由记在 ADR 0008。
    #
    # 这条守卫盯的是**另一头**：律师真正拿去用的那三种（开庭的 SVG、贴 Word 的 PNG
    # 出自 SVG、讲课的 PPTX、在 Visio/WPS 里改的 VSDX）**必须**有侧标签。
    # 哪天它们也丢了 —— 比如某次改动让侧标签不再进 SVG，或者 pptx 的转写漏掉它 ——
    # 要当场报出来。按 HANDOVER 的规矩：有 lanes 就必出侧标签，
    # 该出而没出，等于让读者看到上下分侧却不知道为什么分。
    _lab_map = os.path.join(SKILL, "examples", "two-sides-numbered.json")
    if not os.path.exists(_lab_map):
        msgs.append("侧标签守卫的样本 examples/two-sides-numbered.json 不在了 —— "
                    "这条检查等于没跑")
    else:
        _lm = json.load(open(_lab_map, encoding="utf-8"))
        _labs = [l.get("label_text", "") for l in (_lm.get("lanes") or [])]
        if not _labs:
            msgs.append("侧标签守卫的样本没有 lanes —— 换一份带泳道的样本")
        else:
            _rf_lab = _mod("render_figure")
            _ef_lab = _mod("export_formats")
            _d_lab = _TF.mkdtemp()
            _o_lab = os.path.join(_d_lab, "lab.svg")
            try:
                _rf_lab.deliver(_CP.deepcopy(_lm), _o_lab)
                _res_lab = _ef_lab.deliver(_o_lab, _CP.deepcopy(_lm))
            except Exception as _e_lab:
                msgs.append(f"侧标签守卫出图失败：{type(_e_lab).__name__}: "
                            f"{str(_e_lab)[:60]}")
                _res_lab = []
            _want = {"SVG", "PPTX", "VSDX"}
            for _tag_lab, _path_lab, _ in _res_lab:
                if _tag_lab not in _want or not _path_lab or not os.path.exists(_path_lab):
                    continue
                if _tag_lab == "SVG":
                    _body = open(_path_lab, encoding="utf-8").read()
                else:
                    import zipfile as _zf
                    _z = _zf.ZipFile(_path_lab)
                    _body = "".join(
                        _z.read(_n).decode("utf-8", "ignore")
                        for _n in _z.namelist() if _n.endswith(".xml"))
                _lost = [_l for _l in _labs if _l not in _body]
                if _lost:
                    msgs.append(f"{_tag_lab} 里丢了侧标签 {_lost} —— "
                                f"有 lanes 就必出侧标签，该出而没出等于让读者看到"
                                f"上下分侧却不知道为什么分（drawio 是明写的例外，"
                                f"见 ADR 0008）")

    # ---- 第五轮（标红）必须由用户决定 ---------------------------------------------
    # v1 的 SKILL.md 第 97 行：**不要自己选强调 —— 深红重点是用户的决定，
    # 在 CHECKPOINT 那一轮问**；schema 第 592 行同一条纪律：
    # 「选择必须由用户作出，模型永远不许自己拟定」。
    # v2 先前只有分流（奇川风才问）与跳过提示，**那一问本身没实现** ——
    # 于是 emphasis 实际是模型自己填的（八份示例七份都标了红）。这里盯住三件：
    _pl_mk = open(os.path.join(SKILL, "scripts", "pipeline.py"),
                  encoding="utf-8").read()
    # 判据要**函数式验证**，不是查字符串：改名成 stage_mark_x 之后
    # 「"def stage_mark" not in」照样通不过（子串还在），而 emphasis_source
    # 在注释里也出现，改坏一处剩下四处仍在 —— 两处都实测漏报过。
    _pl_mod = _mod("pipeline")
    if not callable(getattr(_pl_mod, "stage_mark", None)):
        msgs.append("pipeline 没有 stage_mark —— 第五轮（标红）无处作答，"
                    "红标就会回到模型自己填")
    if not re.search(r'cmd == "mark"', _pl_mk):
        msgs.append("mark 没有接进命令分派 —— 函数在、用户调不到")
    # emphasis_source 必须真的写进 state：数它在**赋值语句**里出现几次
    _n_src = len(re.findall(r'st\["emphasis_source"\]\s*=', _pl_mk))
    if _n_src < 2:
        msgs.append(f"emphasis_source 只在 {_n_src} 处被赋值 —— "
                    f"user / none 两条路都要如实记，否则查不出这处红是谁决定的")
    if not re.search(r'_esrc\s*=\s*st\.get\("emphasis_source"\)', _pl_mk):
        msgs.append("出图时没有读 emphasis_source —— 用户的选择落不到产物上")
    # 白描与歸藏风不许问这一轮（单色没有红可标）
    if 'st.get("style") == "奇川风"' not in _pl_mk:
        msgs.append("第五轮没有按风格分流 —— 白描是单色，问标红没有意义")

    # ---- 第二轮：日期少就不该问 ---------------------------------------------------
    # 作者指出的：三个日期还让人从三个季度里挑，是没必要的选择。
    # 门槛 8 是实测定的：日期数少于 8 时不论分出几档，每档只有一两个日期。
    _pk_sp = _mod("pick")
    if not hasattr(_pk_sp, "span_worth_asking"):
        msgs.append("pick 没有 span_worth_asking —— 第二轮会在日期很少时白问一轮")
    else:
        for _n, _rows_n, _want in ((3, 2, False), (5, 3, False), (7, 4, False),
                                   (8, 4, True), (20, 6, True)):
            _rows_fake = [{"id": i + 1, "label": f"b{i}", "n": 1}
                          for i in range(_rows_n)]
            _ask, _ = _pk_sp.span_worth_asking(_rows_fake, _n)
            if _ask != _want:
                msgs.append(f"第二轮在 {_n} 个日期时{'问了' if _ask else '没问'}，"
                            f"应当{'问' if _want else '不问'} —— "
                            f"日期少于 {_pk_sp.SPAN_ASK_MIN} 个时每档只有一两个，"
                            f"选没有意义")
        # 单档时一律不问（原有判据，不许丢）
        if _pk_sp.span_worth_asking([{"id": 1, "label": "x", "n": 30}], 30)[0]:
            msgs.append("只分出一档时还在问第二轮 —— 一个选项的问题不该问")

    # ---- 第三方库缺席时只许少一种能力，不许整条路崩 -------------------------------
    # CI 上撞过一次：那台机器没装 python-docx，读 .docx 的守卫抛裸的
    # ModuleNotFoundError、退出码 1，**前面五组守卫的结果全被埋掉**，
    # 而报错信息只有一行 import 失败，看不出该装什么。
    # 这个 skill 的口径是零第三方依赖，与 doctor.py 那套「缺什么就退化成什么样」
    # 一致：缺一样只该少一种能力。
    _srcs = {}
    for _fn3 in sorted(os.listdir(os.path.join(SKILL, "scripts"))):
        if _fn3.endswith(".py"):
            _srcs[_fn3] = _code_only(open(os.path.join(SKILL, "scripts", _fn3),
                                          encoding="utf-8").read())
    # 每一处第三方 import 都要在 try 里，并且给出说得清的替代
    _THIRD = ("docx", "PIL", "fontTools", "lxml", "numpy", "yaml", "requests")
    for _fn3, _s3 in _srcs.items():
        for _lib in _THIRD:
            for _m3 in re.finditer(rf"^(\s*)(?:from {_lib}[\w.]* import|"
                                   rf"import {_lib})\b", _s3, re.M):
                _indent = len(_m3.group(1))
                # 往上找最近的 try:，缩进要比 import 浅
                _before = _s3[:_m3.start()].splitlines()
                _guarded = False
                for _l3 in reversed(_before[-6:]):
                    if _l3.strip() == "try:" and (len(_l3) - len(_l3.lstrip())) < _indent:
                        _guarded = True
                        break
                if not _guarded:
                    msgs.append(f"{_fn3}: `{_m3.group(0).strip()}` 没有包在 try 里 —— "
                                f"这台机器没装 {_lib} 时会抛裸的 ModuleNotFoundError，"
                                f"把整条路带崩。缺一样只该少一种能力")

    # ---- 仓库里每个路径都必须是 ASCII ---------------------------------------------
    # 理由抄自 v1 的同名守卫（它写得很清楚）：Windows PowerShell 的 Expand-Archive
    # 按系统代码页而不是 UTF-8 读 ZIP 条目名，所以中文文件名会变成乱码、解压直接报
    # 「路径中具有非法字符」。而下载 ZIP 是大多数人拿到仓库的方式。
    # **内容是中文，路径不必是。**
    # 开源前清过一次：这个 skill 里曾有 17 个中文路径（三份 RELAY、六份 fixture、
    # 八条 ADR），全部改成 ASCII，中文标题保留在文件内容里。
    _nonascii = []
    for _dp, _dirs, _fs in os.walk(SKILL):
        _dirs[:] = [d for d in _dirs if d not in (".git", "__pycache__")]
        for _f in _fs:
            _p = os.path.join(_dp, _f)
            try:
                _p.encode("ascii")
            except UnicodeEncodeError:
                _nonascii.append(os.path.relpath(_p, SKILL))
    if _nonascii:
        msgs.append(f"这些路径不是 ASCII：{_nonascii[:5]}"
                    f"{' 等 ' + str(len(_nonascii)) + ' 个' if len(_nonascii) > 5 else ''}"
                    f" —— Windows 上按 ZIP 下载会解压失败。内容用中文没问题，"
                    f"文件名要用 ASCII")

    # ---- ADR 与代码不许脱钩 -------------------------------------------------------
    # 学自 diagram-design 的 ADR 制度。它那条 ADR 0002 的修订记里写得最直白：
    # **一个改了计数器却没修订本文件的 PR，是悄悄把自己变成了权威**。
    # 所以每条 ADR 的「执行处」点到的判据函数，必须真的存在；
    # 反过来，判据被删掉而 ADR 还挂着，就是脱钩 —— 那时该修订 ADR，不是让代码说话。
    _adr_dir = os.path.join(SKILL, "docs", "adr")
    if not os.path.isdir(_adr_dir):
        msgs.append("docs/adr 不在了 —— 已定决策与「否掉的替代方案」是防止同一件事被反复"
                    "重新讨论的唯一一层（实测：扫描件能不能读讨论过三轮）")
    else:
        _adr_files = sorted(f for f in os.listdir(_adr_dir)
                            if f.endswith(".md") and f != "README.md")
        if len(_adr_files) < 6:
            msgs.append(f"docs/adr 只剩 {len(_adr_files)} 条 —— 决策记录不许删，"
                        f"决策变了在原条尾部加「修订」")
        _idx = open(os.path.join(_adr_dir, "README.md"), encoding="utf-8").read()
        # 判据要落在**目录表格的行**上，不是全文里有没有这个数字：
        # README 的正文里会引用别的 ADR 编号（0006 那条就提到 0005），
        # 用「全文包含」判的话漏一行也照样通过 —— 实测漏过一次（0005 缺行没被抓住）。
        _idx_nums = set(re.findall(r"\|\s*\[(\d{4})\]", _idx))
        for _f in _adr_files:
            if _f[:4] not in _idx_nums:
                msgs.append(f"ADR {_f[:4]} 不在 README 的目录表格里 —— "
                            f"索引漏了就等于没有，下一个人翻不到它")
        # 每条 ADR 点到的判据函数必须存在（写「仅文档，无判据」的那条豁免）
        _all_src = "".join(
            open(os.path.join(SKILL, "scripts", _n), encoding="utf-8").read()
            for _n in os.listdir(os.path.join(SKILL, "scripts")) if _n.endswith(".py"))
        for _f in _adr_files:
            _t = open(os.path.join(_adr_dir, _f), encoding="utf-8").read()
            if "仅文档，无判据" in _t:
                continue
            # 只认**函数名**，不要把模块名 check_model_output 也抓进来
            # （第一版就是这么误报的：判据太粗，与那次「把注释里的字当代码」同一类错）。
            _fns = {_x for _x in re.findall(r"`?(check_[a-z_]+|_is_adversarial)`?", _t)
                    if _x not in ("check_model_output",)}
            _miss = [_fn for _fn in _fns if f"def {_fn}" not in _all_src]
            if _miss:
                msgs.append(f"ADR {_f[:4]} 的执行处点到 {_miss}，而代码里没有这个函数 —— "
                            f"判据被删就该修订 ADR，不许让代码悄悄成为权威")

    # ---- [C18] 侧标签只许写百分百确认的东西 -------------------------------------
    # 作者定的：两个象限上的文字必须是**百分百确认的客观事实**，或者**有明确材料出处的
    # 一方主张**；不能确认就干脆不写 —— 要有谦抑性。
    # 真实犯过：七份材料全部出自原告一方，图上却标着「原告主张 / 被告自认」，
    # 而「自认」二字整份材料里一个字都没有（付款计划原文写的是「我司2021年的逾期货款」）。
    # 同一批材料里 5 条卡片正文全部通过子序列核验 —— **正文有铁律，侧标签当时没有**。
    #
    # 更细的一层（作者补的）：**同一个词的合法性取决于它出自谁的材料**。
    # 「被告主张」写在判决书里可以（法院居中记载两方陈述），写在原告的起诉状里就不行 ——
    # 那是原告转述对方的话，而转述人自己是当事人、有身份性。
    # 项目早有两条规矩管着一半：model-steps「单方主张的转述」（只管卡片正文）、
    # layout-constraints「材料里有两方各自的主张就是双泳道，**只有一方叙述就是单侧**」。
    _cll = _mod("check_model_output")
    for _lb, _items, _tbl, _want, _tag in (
            ({"P": "原告主张", "D": "被告自认"}, None, None, True, "含「自认」"),
            ({"P": "甲方", "D": "乙方违约"}, None, None, True, "含「违约」"),
            ({"P": "原告", "D": "被告否认"}, None, None, True, "含「否认」"),
            ({"P": "供货方", "D": "采购方"}, None, None, False, "身份词"),
            ({"P": "支出", "D": "收入"}, None, None, False, "账目方向"),
            ({"P": "原告诉称", "D": "被告辩称"},
             [{"id": "1", "lane": "P", "src_file": "判决书"},
              {"id": "2", "lane": "D", "src_file": "判决书"}], {}, False,
             "判决书（法院居中记载）"),
            ({"P": "原告主张", "D": "被告主张"},
             [{"id": "1", "lane": "P", "src_file": "起诉状"},
              {"id": "2", "lane": "D", "src_file": "答辩状"}],
             {"起诉状": "P", "答辩状": "D"}, False, "两方各自提交"),
            ({"P": "原告主张", "D": "被告主张"},
             [{"id": "1", "lane": "P", "src_file": "起诉状"},
              {"id": "2", "lane": "D", "src_file": "起诉状"}],
             {"起诉状": "P"}, True, "两侧全出自原告一方")):
        _got = bool(_cll.check_lane_labels(_lb, _items, _tbl))
        if _got != _want:
            msgs.append(f"侧标签判据在「{_tag}」上判错：{'拦了' if _got else '放了'}，"
                        f"应当{'拦' if _want else '放'} —— "
                        + ("图上会出现材料里没有的判断（比排版错严重得多）" if _want
                           else "误拦会逼人不写标签，而作者定的是「说得出就写」"))
    # 管线必须真的调它，否则判据在、交付路径不读它（time.anchor 那类死字段）
    _pls_c18 = open(os.path.join(SKILL, "scripts", "pipeline.py"),
                    encoding="utf-8").read()
    if "C.check_lane_labels(" not in _pls_c18:
        msgs.append("pipeline 没有调 check_lane_labels —— 判据在、交付路径不读它，"
                    "图上照样会出现法律定性")

    # ---- 侧标签：代码不代笔，且不许撞竖线 ---------------------------------------
    # 规矩见 HANDOVER：「有 lanes 就是有意义的分布，必出侧标签；没有就是纯为省空间的
    # 交替，不许出侧标签 —— **否则等于宣称一个数据里没有的区分**。」
    # 原来管线有一句兜底 {"P": "原告主张", "D": "被告主张"}：模型不写 lane_labels.json
    # 就替它编一个诉讼语义。真材料上出现过 —— 支付宝流水两侧其实是支出与收入，
    # 图上却标着「原告主张 / 被告主张」，而图看起来完全正常。这两个词在语料里太顺手。
    # 判断权在模型：读了材料说得出语义就起名（**优先四五个字**），说不出就不声明 lane。
    _pls_lbl = open(os.path.join(SKILL, "scripts", "pipeline.py"),
                    encoding="utf-8").read()
    # 判据要**剔掉注释行**再看：这一段的注释里本来就写着那个兜底为什么被删
    # （「原来这里有一句兜底 {"P": "原告主张"…}」），拿注释当代码就是误报，
    # 而误报会把真报警一起淹掉。只看真正会执行的代码行。
    _code_lbl = "\n".join(
        l for l in _pls_lbl.split("def stage_render")[-1].splitlines()
        if not l.lstrip().startswith("#"))
    if re.search(r'_fallback|"原告主张"\s*[,:}]', _code_lbl):
        msgs.append("pipeline 的出图那一段又出现「原告主张」兜底 —— "
                    "侧标签不许由代码编造，模型不给名字就不出标签")
    # 侧标签的字数容量必须随档位收缩，且**图上不许撞第一根竖线**。
    # 它贴左边缘起排，右边第一个障碍是首个圆点（x = MARGIN_X + 最小卡宽/2，六档实测一致）。
    # 事项越多卡越窄：6 项 8 字、10 项 5 字、18 项 2 字。单行放不下折两行。
    _mb_lbl = _mod("render_multiband")
    _cap_lbl = _mod("capacity")
    sys.path.insert(0, os.path.join(SKILL, "..", "mqc-litigation-visual-redraw", "scripts"))
    from common import text_w as _tw_lbl

    def _mk_lbl(_n, _up, _dn):
        _ev = [{"id": str(_i + 1), "head": "字" * 20, "unit_type": "fact",
                "lane": "P" if _i % 2 == 0 else "D",
                "source": {"file": "x", "locator": "x"},
                "time": {"certainty": "exact", "origin": "extracted", "kind": "occur",
                         "raw": "x", "date": f"20{20 + _i // 12}/{_i % 12 + 1}/"
                                             f"{_i % 27 + 1}", "date_text": "x"}}
               for _i in range(_n)]
        return {"schema_version": 2, "layout": "numbered_point_timeline",
                "title_text": "侧标守卫",
                "lanes": [{"id": "P", "label_text": _up},
                          {"id": "D", "label_text": _dn}], "events": _ev}
    _lbl_bad = []
    _prev_cap = None
    for _n_lbl in (8, 10, 13, 18):
        _cap = _cap_lbl.probe(_mk_lbl(_n_lbl, "甲", "乙")).get("lane_label_cap", 0)
        if not _cap:
            _lbl_bad.append(f"{_n_lbl} 项档位报侧标签容量 0 —— 编号型横向有侧标签，"
                            f"容量必须算得出来")
        if _prev_cap is not None and _cap > _prev_cap:
            _lbl_bad.append(f"{_n_lbl} 项的侧标签容量 {_cap} 比事项更少那档的 "
                            f"{_prev_cap} 还大 —— 事项越多卡越窄，余量只会更小")
        _prev_cap = _cap
        # 用满容量的名字出图，量它有没有撞第一根竖线
        _nm = "字" * max(1, _cap)
        _t_lbl = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
        _t_lbl.close()
        try:
            _p_lbl = _mb_lbl.render(_CP.deepcopy(_mk_lbl(_n_lbl, _nm, _nm)), None,
                                    plan_only=True)
            _mb_lbl.render(_CP.deepcopy(_mk_lbl(_n_lbl, _nm, _nm)), _t_lbl.name)
            _s_lbl = open(_t_lbl.name, encoding="utf-8").read()
        except Exception as _e_lbl:
            _lbl_bad.append(f"{_n_lbl} 项侧标样本渲染失败："
                            f"{str(_e_lbl).splitlines()[0][:40]}")
            continue
        finally:
            os.unlink(_t_lbl.name)
        _first = _mb_lbl.MARGIN_X + _p_lbl["card_w_base"] / 2
        for _m_lbl in re.finditer(r'<text x="([\d.]+)" y="[\d.]+" font-size="%d" '
                                  r'font-weight="600"[^>]*>([^<]*)</text>'
                                  % _mb_lbl.FS_DATE, _s_lbl):
            _x, _t2 = float(_m_lbl.group(1)), _m_lbl.group(2)
            if abs(_x - _mb_lbl.MARGIN_X) > 0.6:
                continue                      # 不是侧标签
            if _x + _tw_lbl(_t2, _mb_lbl.FS_DATE) > _first + 0.6:
                _lbl_bad.append(f"{_n_lbl} 项：侧标签「{_t2}」右端超过首个圆点 "
                                f"{_first:.0f}px —— 会撞上第一根竖线")
                break
    if _lbl_bad:
        msgs.append(f"侧标签不合规 {len(_lbl_bad)} 处（{_lbl_bad[0]}）")

    # ---- 图像来源要有名分，且管线必须把它带进地图 -------------------------------
    # 规矩见 HANDOVER 5.4：让模型直接读图（不引 OCR 依赖），但**图像来源的事件单独标记，
    # 交付时必须声明**哪几份材料没有文字层、有几个事件出自读图。
    # 判据一个字不新写 —— schema 与 validate_map 早就完整实现了。真实缺口是：
    # **管线组装地图时不带 medium、不带 scope**，于是那条守卫在管线产物上永远看不到
    # 图像来源事件（pipeline 里 medium 出现 0 次、scope 出现 0 次），
    # 与 time.anchor 那个死字段同一类：规矩定了、schema 认了、校验器管着，
    # 交付路径不读它。这条守卫盯的就是**这次接线不许再被拆掉**。
    _pls_img = open(os.path.join(SKILL, "scripts", "pipeline.py"),
                    encoding="utf-8").read()
    for _tok, _why in (
            ('ev["source"]["medium"] = "image"',
             "管线要把 medium 写进 source（validate_map 读的是 event.source.medium）"),
            # 只查字符串在不在是不够的：把它写死成 0 也照样有这个字符串。
            # 实测这个改坏漏过一次，所以判据要落在**填的是不是实际数目**上（见下）。
            ('"events_from_image": len(_img_evs)',
             "scope.events_from_image 必须填实际数目，写死成常数等于谎报"),
            ('"documents_unreadable"', "管线要列出读了图的材料"),
            ('("image_docs.json", "image_docs")',
             "要有读取入口，否则 image_docs 永远是 None（这一处漏过一次）")):
        if _tok not in _pls_img:
            msgs.append(f"pipeline 缺 {_tok} —— {_why}")
    # 判据那一头也要验：管线组装出来的 scope 必须真能被 validate_map 认可，
    # 且三种错法（谎报数目、不列材料、不给 scope）必须被抓 —— 否则声明就是摆设。
    _vm_img = _mod("validate_map")
    _ev_img = [{"id": str(_i), "head": "甲", "unit_type": "fact",
                "lane": "P" if _i % 2 else "D",
                "source": {"file": "材料一", "locator": f"句{_i}",
                           **({"medium": "image"} if _i >= 3 else {})},
                "time": {"certainty": "exact", "origin": "extracted", "kind": "occur",
                         "raw": f"2021年5月{_i}日", "date": f"2021/5/{_i}",
                         "date_text": f"2021.05.0{_i}"}}
               for _i in range(1, 6)]
    _base_img = {"schema_version": 2, "layout": "numbered_point_timeline",
                 "title_text": "读图守卫",
                 "lanes": [{"id": "P", "label_text": "甲方"},
                           {"id": "D", "label_text": "乙方"}],
                 "events": _ev_img}
    _docs_img = [{"file": "材料一.docx", "reason": "截图，无文字层",
                  "read_as_image": True}]
    _n_img = sum(1 for _e in _ev_img if (_e["source"].get("medium") == "image"))

    def _img_errs(_scope):
        _t = dict(_base_img)
        if _scope is None:
            _t.pop("scope", None)
        else:
            _t["scope"] = _scope
        _f = _TF.NamedTemporaryFile(suffix=".json", delete=False, mode="w",
                                    encoding="utf-8")
        json.dump(_t, _f, ensure_ascii=False)
        _f.close()
        try:
            _E, _W = _vm_img.check(_f.name)
        finally:
            os.unlink(_f.name)
        return [_x for _x in _E if "图像" in _x or "events_from_image" in _x
                or "documents_unreadable" in _x]
    _ok_scope = {"mode": "full", "selection_source": "default",
                 "events_from_image": _n_img, "documents_unreadable": _docs_img}
    if _img_errs(_ok_scope):
        msgs.append(f"如实声明的 scope 仍被判错：{_img_errs(_ok_scope)[0][:70]}")
    for _bad, _tag in (
            ({**_ok_scope, "events_from_image": _n_img - 1}, "谎报数目"),
            ({"mode": "full", "selection_source": "default",
              "events_from_image": _n_img}, "不列 documents_unreadable"),
            (None, "完全不给 scope")):
        if not _img_errs(_bad):
            msgs.append(f"图像来源声明作假（{_tag}）却没被抓 —— "
                        f"那句「未经逐字核验」的声明就成了摆设")

    # ---- 「同日相反主张」只在 lane.relation = assertion 时才报 -------------------
    # 真材料撞出来的：支付宝流水两侧是「支出 / 收入」，同日既有支出又有收入是正常账目，
    # 75 笔报出 6 处，逐条回查原材料**一处都不是**对立主张。
    # 判据的落点换过三版，前两版都错，教训写在 _is_adversarial 的文档串里：
    #   白名单（漏报，仓库示例「甲公司、乙公司 / 丙公司」不含白名单字样）
    #   → 黑名单（仍误报，主体层级与程序阶段六个用例全中）
    #   → **lane.relation 字段**（语义问题不能用字符串猜）
    # 三个值取自 RELAY-2 对泳道的定义：assertion 谁的主张 / actor 哪一层主体 /
    # stage 哪个程序阶段。**缺省即 assertion**，所以已有地图一字不用改。
    _cm = _mod("check_model_output")
    _lit2 = [{"id": "1", "date": "2021/5/8", "lane": "P", "head": "已交付全部货物"},
             {"id": "2", "date": "2021/5/8", "lane": "D", "head": "从未收到货物"}]

    def _lanes_rel(rel):
        if rel is None:
            return [{"id": "P", "label_text": "甲"}, {"id": "D", "label_text": "乙"}]
        return [{"id": "P", "label_text": "甲", "relation": rel},
                {"id": "D", "label_text": "乙", "relation": rel}]
    for _rel, _want, _tag in ((None, True, "不填（契约：等于 assertion）"),
                              ("assertion", True, "assertion 谁的主张"),
                              ("actor", False, "actor 哪一层主体"),
                              ("stage", False, "stage 哪个程序阶段")):
        _got = bool(_cm.check_material_conflicts(_lit2, lane_defs=_lanes_rel(_rel)))
        if _got != _want:
            msgs.append(f"relation={_rel}（{_tag}）时同日两侧{'不报' if _got else '不报'}"
                        f"，应当{'报' if _want else '不报'} —— "
                        f"{'漏报，材料里真实存在的矛盾被咽下去' if _want else '误报，会把真报警淹掉'}")
    # 拿不到 lane 定义时必须照报，否则「不给 lanes」就成了绕过检查的后门
    if not _cm.check_material_conflicts(_lit2, lane_defs=None):
        msgs.append("拿不到 lane 定义时不报了 —— 必须照旧报，否则省略 lanes 就能绕过检查")
    # 混搭说不清（一侧 assertion 一侧 actor）要保守照报
    if not _cm.check_material_conflicts(_lit2, lane_defs=[
            {"id": "P", "label_text": "原告", "relation": "assertion"},
            {"id": "D", "label_text": "子公司", "relation": "actor"}]):
        msgs.append("一侧 assertion 一侧 actor 时不报了 —— 说不清就该保守照报")
    # **不许再出现第二套判据。** 留过一版泳道名词表兜底，后果立刻出现：不填 relation 时
    # 契约说等于 assertion（对立），而词表看到「支出 / 收入」判成不对立，
    # 暗中生效的是词表。同一件事有两个判据，必然有一个说话不算数。
    _cm_src = _code_only(open(os.path.join(SKILL, "scripts",
                                           "check_model_output.py"),
                              encoding="utf-8").read())
    if "_NON_ADVERSARIAL_HINT" in _cm_src:
        msgs.append("check_model_output 里又出现了泳道名词表兜底 —— "
                    "判据只许有一个（lane.relation），第二套必然与它打架")
    # schema 必须认这个字段，且必须是**可选**的（已有地图一字不用改）
    _sch = json.load(open(os.path.join(SKILL, "schemas", "semantic-map.schema.json"),
                          encoding="utf-8"))
    _lane_def = _sch["$defs"]["lane"]
    if "relation" not in _lane_def.get("properties", {}):
        msgs.append("schema 的 lane 里没有 relation —— 判据读它，schema 不认它，"
                    "地图一带这个字段就会被 additionalProperties=false 拒掉")
    elif "relation" in _lane_def.get("required", []):
        msgs.append("schema 把 relation 列为必填 —— 它必须可选，"
                    "否则八份已有地图与用户手上的地图全部失效")

    # ---- 歸藏风的图名容量要按它实际用的字号算 ----------------------------------
    # 真材料撞出来的：歸藏风把图名从 30 号放大到 42 号（v1 有意为之，字号随画布宽度走，
    # 免得宽图的标题显得太小），但它**只原地放大、不折行也不量宽度**。33 字的图名在
    # 42 号下宽 1302px、整幅只有 1154px —— 图上左右两端的字被切掉。
    # 修在源头：选了歸藏风就按它的字号报容量（一行），前端一开始就写得下。
    # 两条判据：① 歸藏风的容量必须**明显小于**奇川风（否则等于没生效）；
    #          ② 容量上限那一档，实际渲染必须落在画布内（估宽骗过我一次，所以量产物）。
    _cap_mod = _mod("capacity")
    _m_gz = json.load(open(os.path.join(SKILL, "examples", "two-sides-numbered.json"),
                           encoding="utf-8"))
    _caps = {}
    for _sty in ("奇川风", "歸藏风"):
        _t_gz = _CP.deepcopy(_m_gz)
        _t_gz["style"] = _sty
        _caps[_sty] = _cap_mod.probe(_t_gz)
    if _caps["歸藏风"]["title_fs"] <= _caps["奇川风"]["title_fs"]:
        msgs.append(f"歸藏风的图名字号 {_caps['歸藏风']['title_fs']} 没有大于奇川风的 "
                    f"{_caps['奇川风']['title_fs']} —— 容量算的不是它实际会用的字号")
    if _caps["歸藏风"]["title_cap"] >= _caps["奇川风"]["title_cap"]:
        msgs.append(f"歸藏风的图名容量 {_caps['歸藏风']['title_cap']} 不小于奇川风的 "
                    f"{_caps['奇川风']['title_cap']} —— 字号更大、又不折行，容量必须更小")
    # 容量上限那一档要真的放得下：拿满容量的图名过一遍歸藏风变换，量实际宽度
    _n_gz = _caps["歸藏风"]["title_cap"]
    if _n_gz:
        _t_txt = "承" * _n_gz
        _gfs = _caps["歸藏风"]["title_fs"]
        sys.path.insert(0, os.path.join(SKILL, "..", "mqc-litigation-visual-redraw",
                                        "scripts"))
        from common import text_w as _tw_gz
        _w_gz = _tw_gz(_t_txt, _gfs)
        if _w_gz > _mod("paper").SHEET_LAND_W:
            msgs.append(f"歸藏风报的图名容量 {_n_gz} 字在 {_gfs} 号字下宽 {_w_gz:.0f}px，"
                        f"超出整幅 {_mod('paper').SHEET_LAND_W}px —— 容量报大了，"
                        f"图上的字会被切掉")

    # ---- 只到年月的时间表述要认得出，且不许误认 --------------------------------
    # 真材料撞出来的：证据目录里「2023 年 11 月再次出现多媒体触摸一体机故障问题」
    # 被 dates_in 完全认不出（只有到日的正则），于是**第二轮的时间段清单里 2023 年
    # 整年消失** —— 而那正是本案「再次故障」的关键时点，律师勾时间段时看不到它。
    # 判据必须两个方向都盯：该认的要认出，**不该认的一个都不许误认**。
    # 后者是这条的风险所在：放宽一点就会把「质保期为 12 个月」「合计金额 262000 元」
    # 「20% 的违约金」「（2024）某仲案字第 00000 号」认成日期。
    _ymok = [("2023年11月再次出现故障。", 1),
             ("申请人于2023年11月至2024年1月期间花费维修费用7826元。", 2),
             ("2021年11月9日送达被申请人。", 1),          # 到日的不许被重复计一次
             ("2020年5月8日与2020年5月9日两次。", 2)]
    _ymno = ["合同第四条约定合同总价为31万元，质保期为12个月。",
             "被申请人共向申请人开具五张发票，合计金额为262000元。",
             "合同总价20%的违约金，每延误一天0.1%。",
             "案号：（2024）某仲案字第00000号。",
             "页码范围：11-13",
             "2019年年底完成。"]
    for _s_ym, _n_ym in _ymok:
        _got = _pk.dates_in(_s_ym)
        if len(_got) != _n_ym:
            msgs.append(f"日期识别：{_s_ym[:18]}… 应认出 {_n_ym} 个时点，"
                        f"实得 {len(_got)} 个（{_got}）")
    for _s_ym in _ymno:
        _got = _pk.dates_in(_s_ym)
        if _got:
            msgs.append(f"日期误认：{_s_ym[:20]}… 里没有日期，却认出 {_got} —— "
                        f"把数量、金额、百分数或案号当成了日期")

    # ---- 骨架的时间归档必须过 check_dates ------------------------------------
    # 真材料撞出来的：证据目录里写「2023 年 11 月」，骨架给 date=2023/11，
    # **编号型静默补成 2023-11-01，图上印出材料里不存在的那个日**（日期型倒是拒绝了）。
    # 机制本来就有（model-steps 第五步四档确定度 + check_dates 全套判据 + 三条改坏测试），
    # 缺的只是没人把骨架交给它 —— 与 time.anchor 那个死字段同一类：声明了、
    # 校验器管着、交付路径不读它。这条守卫盯的就是**这次接线不许再被拆掉**。
    _sh = open(os.path.join(SKILL, "scripts", "pipeline.py"), encoding="utf-8").read()
    if "C.check_dates(" not in _sh:
        msgs.append("pipeline 没有把骨架交给 check_dates —— 月精度的日期会被静默补成"
                    "某月 1 日，图上出现材料里不存在的日期")
    for _tok, _why in (('"certainty"', "skeleton 契约要求模型填 certainty"),
                       ('"raw"', "skeleton 契约要求 raw 逐字可查")):
        if _tok not in _sh:
            msgs.append(f"pipeline 的 skeleton 契约缺 {_tok} —— {_why}")
    # 期间型的信号不许只看 from/to：月精度时点（certainty=range）也带两个端点，
    # 混在一起会把「精度只到月的一个时点」当成「一段期间」，报出互不重叠、要求改编号型。
    if 'it.get("certainty") != "range"' not in _sh:
        msgs.append("期间型的信号没有把 certainty=range 排除 —— "
                    "月精度时点会被当成一段期间")

    # ---- [D14] 日期型的正文封在六行 ------------------------------------------
    # 作者看过四行 / 六行 / 八行三档实图之后定的（四行句子只说一半、八行把轴挤成细带，
    # 而日期型唯一不可替代的能力是让轴上的空白成为证据）。守卫三条，正反都盯：
    #   · 六行必须画得出（少了这一条，把上限改成 4 也会通过）
    #   · 七行必须被拒，且理由要指名行数（不许悄悄把卡片撑高，也不许截断）
    #   · capacity 报的字数必须落在六行那一档（改前报 126 字，见下）
    # 126 字那个数的来历要记住：**measured_cap 二分时只比排布不比图种**，字一多
    # deliver 已按阶梯退回编号型，而两者排布都叫「横向」，于是把「换成编号型之后还
    # 画得出」当成了「日期型能装这么多字」。判据现在是「图种·排布」。
    _d14 = _mod("render_dated_v2")
    _cap14 = _mod("capacity")
    _LONG14 = ("2018年4月10日，双方签订股权转让协议，约定标的公司2018年度净利润不低于"
               "600万元，未达标时由转让方以现金方式补偿差额部分，补偿款应于审计报告出具后"
               "三十日内付清，逾期按日万分之五计付违约金，并以剩余股权提供质押担保")

    def _mk14(_chars):
        return {"schema_version": 2, "layout": "dated_point_timeline",
                "title_text": "六行守卫", "events": [
                    {"id": str(_i + 1), "head": _LONG14[:_chars], "unit_type": "fact",
                     "source": {"file": "x", "locator": "x"},
                     "time": {"certainty": "exact", "origin": "extracted",
                              "kind": "occur", "raw": "x", "date": _d,
                              "date_text": _d.replace("/", ".")}}
                    for _i, _d in enumerate(["2018/4/10", "2020/4/10", "2021/9/1",
                                             "2023/2/15", "2024/6/20"])]}

    def _rows14(_chars):
        _gm14 = _mod("geom")
        sys.path.insert(0, os.path.join(SKILL, "..", "mqc-litigation-visual-redraw",
                                        "scripts"))
        from common import text_w as _tw14, wrap as _wr14
        return len(_gm14.wrap_atomic(_LONG14[:_chars], _d14.FS_BODY,
                                     _d14.CARD_W - 2 * _d14.PAD_X, _wr14, _tw14))

    _c6 = next((_c for _c in range(40, 130) if _rows14(_c) == _d14.MAX_BODY_ROWS), None)
    _c7 = next((_c for _c in range(40, 200) if _rows14(_c) == _d14.MAX_BODY_ROWS + 1), None)
    if _c6 is None or _c7 is None:
        msgs.append(f"[D14] 造不出正好 {_d14.MAX_BODY_ROWS} 行 / "
                    f"{_d14.MAX_BODY_ROWS + 1} 行的样本，这条守卫等于没跑")
    else:
        try:
            _d14.render(_CP.deepcopy(_mk14(_c6)))
        except Exception as _e14:
            msgs.append(f"[D14] {_d14.MAX_BODY_ROWS} 行（{_c6} 字）本该画得出，"
                        f"却被拒：{str(_e14).splitlines()[0][:50]}")
        try:
            _d14.render(_CP.deepcopy(_mk14(_c7)))
            msgs.append(f"[D14] {_d14.MAX_BODY_ROWS + 1} 行（{_c7} 字）本该被拒，"
                        f"却画出来了 —— 卡片被撑高，轴会挤成一条细带")
        except Exception as _e14:
            if "行" not in str(_e14):
                msgs.append(f"[D14] 超行的拒绝理由里没有指名行数："
                            f"{str(_e14).splitlines()[0][:50]}")
        # capacity 报的容量必须落在这一档：不小于六行样本的字数、不到七行样本的字数
        _capv, _capform = _cap14.measured_cap(_CP.deepcopy(_mk14(10)))
        if not (_c6 - 6 <= _capv < _c7):
            msgs.append(f"[D14] capacity 报 {_capv} 字（形态 {_capform}），"
                        f"而六行约 {_c6} 字、七行 {_c7} 字 —— 容量与行数上限对不上")

    # ---- 契约与引擎必须逐一致 --------------------------------------------
    # feasible.py 是前后端的契约：它把「能不能画」写成不等式，前端一次拿到整个可行域，
    # 不必撞回来。**但契约与渲染器一旦漂开，比没有契约更糟** —— 前端会照着一个不准的
    # 可行域做决定。所以这条守卫逐点比对：契约说可行的必须画得出、说不行的必须被拒。
    #
    # 校准过程本身值得记：横向上限我先按「列距 ≥ 日期宽」解出 10（实测 21，差一倍多，
    # 因为卡片上下交替、同侧相邻隔着两个列距）；改成两倍后又发现真实上限是 24 而非 21；
    # 再按「卡高 → 每侧层数」推，35 组里 4 组不符。最后**改用逐点扫出来的实测表**。
    # 结论：能算的算，算不准的量，不要用估出来的系数冒充公式。
    _fs = _mod("feasible")
    _mb2 = _mod("render_multiband")
    _pp = _mod("paper")

    def _real_h(n40, c40):
        _m40 = _CP.deepcopy(json.load(open(os.path.join(
            SKILL, "examples", "two-sides-numbered.json"), encoding="utf-8")))
        _pr40 = _m40["events"][0]
        _ev40 = []
        for _i40 in range(n40):
            _e40 = _CP.deepcopy(_pr40)
            _e40["id"] = str(_i40 + 1)
            _e40["head"] = "事" * c40
            for _k40 in ("head_short", "body", "emphasis", "index_note"):
                _e40.pop(_k40, None)
            _e40["lane"] = "P" if _i40 % 2 == 0 else "D"
            _e40["time"] = {"certainty": "exact", "origin": "extracted",
                            "kind": "occur", "raw": "x",
                            "date": f"20{20 + _i40 // 12}/{_i40 % 12 + 1}/"
                                    f"{_i40 % 27 + 1}",
                            "date_text": "x"}
            _ev40.append(_e40)
        _m40["events"] = _ev40
        _t40 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
        _t40.close()
        try:
            _r40 = _mb2.render(_m40, _t40.name)
            return _r40[1] <= _pp.LAND_H
        except Exception:
            return False
        finally:
            os.unlink(_t40.name)

    _mism = []
    for _c40 in (4, 10, 20, 30, 40, 60):
        for _n40 in (8, 12, 17, 20, 23, 24, 25, 28):
            if _fs.horizontal(_n40, _c40)["ok"] != _real_h(_n40, _c40):
                _mism.append((_n40, _c40))
    if _mism:
        msgs.append(f"契约与横向渲染器不一致 {len(_mism)} 处（如 {_mism[:3]}）—— "
                    f"前端会照着一个不准的可行域做决定")

    # **纵向的数也要有人盯。** 这一处此前是零守卫，于是契约里那份手写算术漂了很久：
    # 30 / 60 / 90 个事项时契约说 2 / 3 / 4 页，引擎实际画 1 / 2 / 3 页（差的是列数，
    # 引擎会按 [P24] 选两列去省一页）。前端照契约的数决定要不要分页、要写多少字。
    # 判据是「契约必须**转述**引擎，不许自己算」—— 把 vertical 换回任何一份算术，
    # 这条就报（验过）。而页数本身是否可信，由下面那一段拿实画的图高再核一次。
    _vm6 = _mod("render_vcolumns")

    def _v_plan(n60, c60):
        _ev60 = []
        for _i60 in range(n60):
            _ev60.append({"id": str(_i60 + 1), "head": "字" * c60, "unit_type": "fact",
                          "lane": "P" if _i60 % 2 == 0 else "D",
                          "source": {"file": "骨架", "locator": f"句{_i60}"},
                          "time": {"certainty": "exact", "origin": "extracted",
                                   "kind": "occur", "raw": "骨架",
                                   "date": f"20{20 + _i60 // 12}/{_i60 % 12 + 1}/"
                                           f"{_i60 % 27 + 1}", "date_text": "x"}})
        return {"schema_version": 2, "layout": "vertical_two_columns",
                "title_text": "图名草稿", "events": _ev60,
                "lanes": [{"id": "P", "label_text": "原告主张"},
                          {"id": "D", "label_text": "被告主张"}]}

    _vmis = []
    for _n60 in (8, 13, 20, 30, 45, 60, 90, 120):
        for _c60 in (18, 42):
            _con60 = _fs.vertical(_n60, _c60)
            try:
                _eng60 = _vm6.render(_CP.deepcopy(_v_plan(_n60, _c60)), None,
                                     plan_only=True)
            except Exception as _e60:
                if _con60["ok"]:
                    _vmis.append(f"{_n60}个{_c60}字 契约说可行而引擎抛错："
                                 f"{str(_e60).splitlines()[0][:40]}")
                continue
            for _k60 in ("pages", "per_line", "card_w", "cols"):
                if _con60.get(_k60) != _eng60.get(_k60):
                    _vmis.append(f"{_n60}个{_c60}字 {_k60}：契约 {_con60.get(_k60)}"
                                 f" 对引擎 {_eng60.get(_k60)}")
    # 页数要与**实画的图高**相符，否则契约与引擎一起错也验不出来。
    for _n61 in (30, 60, 90):
        _t61 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
        _t61.close()
        try:
            _r61 = _vm6.render(_CP.deepcopy(_v_plan(_n61, 18)), _t61.name)
            _h61 = _r61[1] if isinstance(_r61, tuple) else 0
            _pg61 = max(1, int((_h61 - 1) // _vm6.PAGE_H) + 1)
            if _fs.vertical(_n61, 18)["pages"] != _pg61:
                _vmis.append(f"{_n61}个事项 页数：契约 "
                             f"{_fs.vertical(_n61, 18)['pages']} 对实画图高 "
                             f"{_h61:.0f}px 折出的 {_pg61} 页")
        except Exception as _e61:
            _vmis.append(f"{_n61}个事项 纵向实画出错：{str(_e61).splitlines()[0][:40]}")
        finally:
            os.unlink(_t61.name)
    if _vmis:
        msgs.append(f"契约与纵向渲染器不一致 {len(_vmis)} 处（如 {_vmis[0]}）—— "
                    f"契约必须转述引擎，不许自己算一遍")

    # 日期型的契约同样要与渲染器一致
    _dcases2 = [["2018/4/10", "2020/4/10", "2021/9/1", "2023/2/15", "2024/6/20"],
                ["2020/1/1", "2020/3/1", "2020/6/1", "2023/1/1"],
                ["2024/3/5", "2024/4/18", "2024/6/28"],
                [f"20{20 + _i}/1/1" for _i in range(9)]]
    for _ds40 in _dcases2:
        _pred = _fs.dated(_ds40)["ok"]
        _real = _dtry(_ds40) if "_dtry" in dir() else None
        if _real is not None and _pred != _real:
            msgs.append(f"契约与日期型渲染器不一致：{_ds40[:2]}… "
                        f"契约说{'可行' if _pred else '不行'}")

    # 摄入不许损坏日期：这是时间轴的全部信息，日期错了后面全是空的。
    # 三类真实事故（都在真材料上出过）：
    #   · 清页码的规则把 2023-04-12 削成 202312（-04- 被当成页码 -12-）
    #   · 清「3/12」页码的规则把 2023/4/12 削成 /12
    #   · 页码夹在日期中间（「2021 年 6 月 \n 5 \n 22 日」），清掉后留两个换行，
    #     接回断行只认单个换行，日期永久断成两句
    _ing = [
        ("横线日期", "2023-04-12 09:14 乙方经理：王总早", "2023-04-12"),
        ("斜杠日期", "2023/4/12 交付设备", "2023/4/12"),
        ("点分日期", "23.4.12 到货", "23.4.12"),
        ("年月日", "2024年1月18日通话", "2024年1月18日"),
    ]
    for _lbl20, _src20, _want20 in _ing:
        _got20, _ = _rsrc.normalise(_src20)
        if _want20 not in _got20:
            msgs.append(f"摄入损坏了{_lbl20}：{_src20!r} → {_got20!r}，"
                        f"日期是时间轴的全部信息")
    # 真页码仍要清掉，否则这条修法就是把规则废了
    for _lbl21, _src21 in (("独占一行的 -12-", "正文\n- 12 -\n下一页"),
                           ("独占一行的 3/12", "正文\n3/12\n下一页"),
                           ("裸数字页码", "正文\n7\n下一页")):
        _got21, _ = _rsrc.normalise(_src21)
        if re.search(r"\d", _got21):
            msgs.append(f"{_lbl21} 没有被清掉：{_got21!r}")
    # 页码夹在日期中间时要接回来
    _got22, _ = _rsrc.normalise("原告于 2021 年 6 月\n5\n22 日签收。")
    if "6 月 22 日" not in _got22.replace("\n", " "):
        msgs.append(f"页码夹在日期中间时没接回来：{_got22!r}")
    # 五份材料逐份核对：不许有任何日期被切成两句
    for _fn23 in ("m1-judgment.txt", "m2-defence.txt", "m5-contradictory.txt",
                  "m6-messy.txt"):
        _pp23 = os.path.join(SKILL, "tests", "fixtures", _fn23)
        if not os.path.exists(_pp23):
            continue
        _S23 = _rsrc.read_with_blocks(_pp23)["sentences"]
        for _i23 in range(len(_S23) - 1):
            if (re.search(r"\d\s*月\s*$", _S23[_i23])
                    and re.match(r"^\s*\d{1,2}\s*日", _S23[_i23 + 1])):
                msgs.append(f"{_fn23}: 句 {_i23} 与 {_i23 + 1} 之间把一个日期切开了"
                            f"（…{_S23[_i23][-10:]} | {_S23[_i23 + 1][:8]}…）")
                break

    # 期间型该不该用：法律含义、起止明确、存在重叠，三条都要管。
    _sw_cases = [
        ("时效与沉默期间重叠", [{"id": "s1", "from": "2018/3/1", "to": "2021/3/1",
                          "label_text": "诉讼时效三年期间"},
                         {"id": "s2", "from": "2019/6/15", "to": "2022/9/30",
                          "label_text": "第二次催告后的沉默期间"}], False),
        ("只有一段", [{"id": "s1", "from": "2018/3/1", "to": "2021/3/1",
                   "label_text": "诉讼时效期间"}], True),
        ("互不重叠", [{"id": "s1", "from": "2018/1/1", "to": "2018/6/1",
                   "label_text": "借款期间"},
                  {"id": "s2", "from": "2019/1/1", "to": "2019/6/1",
                   "label_text": "计息期间"}], True),
        # 每个样本只让**被测的那一条**成为唯一原因，否则拿掉一条仍被另一条挡住、
        # 探针漏过（试过：缺截止时间的样本同时也互不重叠，只有一段的样本同样没重叠）。
        # 所以这两个样本都补成「其余条件全部满足」。
        ("缺截止时间（其余都满足）",
         [{"id": "s1", "from": "2018/3/1", "to": None, "label_text": "保证期间"},
          {"id": "s2", "from": "2018/1/1", "to": "2019/1/1", "label_text": "借款期间"},
          {"id": "s3", "from": "2018/2/1", "to": "2018/8/1", "label_text": "计息期间"}],
         True),
    ]
    # 「只有一段」单独测：一段时不可能有重叠，所以要直接看错误里有没有那句话
    _e16, _ = _cm.check_span_worthiness(
        [{"id": "s1", "from": "2018/3/1", "to": "2021/3/1", "label_text": "诉讼时效期间"}])
    if not any("只有一段" in x for x in _e16):
        msgs.append("期间型该不该用：漏过「只有一段」")
    # 「缺起止」单独测：其余条件都满足，缺起止必须单独报出来
    _e17, _ = _cm.check_span_worthiness(
        [{"id": "s1", "from": "2018/3/1", "to": None, "label_text": "保证期间"},
         {"id": "s2", "from": "2018/1/1", "to": "2019/1/1", "label_text": "借款期间"},
         {"id": "s3", "from": "2018/2/1", "to": "2018/8/1", "label_text": "计息期间"}])
    if not any("缺起止时间" in x for x in _e17):
        msgs.append("期间型该不该用：漏过「缺起止时间」")
    for _lbl15, _sp15, _want_err in _sw_cases:
        _e15, _w15 = _cm.check_span_worthiness(_sp15)
        if _want_err and not _e15:
            msgs.append(f"期间型该不该用：漏过「{_lbl15}」")
        if not _want_err and _e15:
            msgs.append(f"期间型该不该用：误拒「{_lbl15}」—— {_e15[0][:50]}")

    # [M14] 三泳道：三层要照声明的顺序、层间不许留过大空隙、**标注只有两个**。
    # 这一行原来写着「三个侧标都要画」，与下面十几行处的判据（_lbl_cnt > 2 就报错）
    # 正好相反 —— 判据改对了，抬头的注释没跟着改。注释骗人比判据错更难发现。
    _t3 = _CP.deepcopy(json.load(open(os.path.join(SKILL, "examples",
                                                   "two-sides-numbered.json"),
                                      encoding="utf-8")))
    _t3["lanes"] = [{"id": "A", "label_text": "集团"},
                    {"id": "B", "label_text": "子公司"},
                    {"id": "C", "label_text": "项目公司"}]
    for _i31, _e31 in enumerate(_t3["events"]):
        _e31["lane"] = "ABC"[_i31 % 3]
        _e31["head"] = "三层主体的事项"
        for _k31 in ("head_short", "body", "emphasis", "index_note"):
            _e31.pop(_k31, None)
        _e31["time"] = {"certainty": "exact", "origin": "extracted", "kind": "occur",
                        "raw": "x", "date": f"20{21 + _i31 // 6}/{_i31 % 12 + 1}/5",
                        "date_text": "x"}
    _tf3 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
    _tf3.close()
    try:
        _mod("render_multiband").render(_t3, _tf3.name)
        _s3 = open(_tf3.name, encoding="utf-8").read()
        # **标注只有两个**（上方一个、下方一个），不是每条带一个。
        # 泳道只分上下两组：同一方的内容放在一起，某一侧拆成两条横带是几何错开，
        # 不改变它仍属同一方。原来这条守卫要求「三条带三个名字」，是照错的理解写的。
        _lbl_cnt = len([_x for _x in ("集团", "子公司", "项目公司") if _x in _s3])
        if _lbl_cnt > 2:
            msgs.append(f"图上出现了 {_lbl_cnt} 个泳道标注 —— "
                        f"标注只该有两个（上方一个、下方一个）")
        # 三层的 y 要照声明的顺序：A 最上、B 中、C 最下
        _ys3 = {}
        for _m3 in re.finditer(r'<rect x="([\d.]+)" y="([\d.]+)"[^>]*rx="12"', _s3):
            _ys3.setdefault(round(float(_m3.group(2))), 0)
            _ys3[round(float(_m3.group(2)))] += 1
        _lvls = sorted(_ys3)
        if len(_lvls) < 3:
            msgs.append(f"三泳道只排出 {len(_lvls)} 层（y 坐标 {_lvls}）")
        # 判据改成「**默认上二下一**」：三条泳道时，轴上方要有两层、下方一层。
        # 原来量的是「各层卡片上沿的差值是否均匀」，那条判据本身不对 ——
        # 三条泳道分居轴两侧、卡高也不同，上沿差值本来就不均匀（实测 70 / 18 / 176，
        # 其中 176 正是跨过轴的那一段），于是正确的排布被判成「把卡高算了两次」。
        # 判据要盯规则本身：上方几层、下方几层。
        # 侧标不许被卡片遮住：三泳道时最左边的卡片必须让开侧标的宽度。
        # 实测过这个错：SIDE_GUTTER 是 0，贴轴那一层的卡片就在标注的高度上，
        # 「集团」落在 y=181、卡片占 150 到 204，图上完全看不见它。
        # **时间轴必须贯穿到底**：不许为了放标注在左侧留出空白、把轴的起点往右推。
        # 我曾按最长标注加宽 gut，轴的起点被推了 58px —— 轴是主干，标注是附注，
        # 不能让附注挤走主干。标注该贴在引线终点那一带。
        _axis = re.search(r'<line x1="([\d.]+)"[^>]*data-role="axis"', _s3)
        if _axis:
            _ax_x = float(_axis.group(1))
            _mx = _mod("render_multiband").MARGIN_X
            if _ax_x > _mx + 2:
                msgs.append(f"时间轴没有贯穿：起点在 x={_ax_x:.0f}，"
                            f"而页边距是 {_mx} —— 很可能为了放标注推走了轴")

        # 真正的判据：三条泳道**默认上二下一**；指定 side 时按指定的来。
        _tf3b = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
        _tf3b.close()
        try:
            _r3b = _mod("render_multiband").render(_t3, _tf3b.name)
            _bu3, _bd3 = _r3b[2], _r3b[3]
            if (_bu3, _bd3) != (2, 1):
                msgs.append(f"三条泳道默认应为上二下一，实际上 {_bu3} 下 {_bd3}")
            # 指定 side 之后要变成上一下二
            _t3c = _CP.deepcopy(_t3)
            _t3c["lanes"][1]["side"] = "dn"
            _t3c["lanes"][2]["side"] = "dn"
            _r3c = _mod("render_multiband").render(_t3c, _tf3b.name)
            if (_r3c[2], _r3c[3]) != (1, 2):
                msgs.append(f"指定 side=dn 之后应为上一下二，"
                            f"实际上 {_r3c[2]} 下 {_r3c[3]}")
            # 一侧超过三条要拒绝（两侧共六条是极限）
            _t3d = _CP.deepcopy(_t3)
            _t3d["lanes"] = [{"id": f"L{_i}", "label_text": f"第{_i}方", "side": "up"}
                             for _i in range(4)]
            for _i3d, _e3d in enumerate(_t3d["events"]):
                _e3d["lane"] = f"L{_i3d % 4}"
            try:
                _mod("render_multiband").render(_t3d, _tf3b.name)
                msgs.append("一侧声明了 4 条泳道却没有被拒绝 —— "
                            "每侧上限 3 条、两侧共 6 条是极限")
            except ValueError:
                pass
        except Exception as _e3e:
            msgs.append(f"三泳道排法守卫出错：{str(_e3e).splitlines()[0][:60]}")
        finally:
            os.unlink(_tf3b.name)
    except Exception as _e32:
        msgs.append(f"三泳道画不出：{str(_e32).splitlines()[0][:60]}")
    finally:
        os.unlink(_tf3.name)

    # [C14] 冲突成员必须画成虚线，且 attrib 要拼到正文开头。
    _cm14 = json.load(open(os.path.join(SKILL, "examples", "two-sides-numbered.json"),
                           encoding="utf-8"))
    # 挑**精确日期**的两项做冲突成员。示例前两项本来是 range（非精确），本来就画虚线
    # —— 拿它们做样本，探针测不出「冲突不画虚线」这个改坏（试过，漏过了）。
    # 测试样本必须让被测的那条规则成为唯一的原因。
    _ex14 = [e["id"] for e in _cm14["events"]
             if (e.get("time") or {}).get("certainty") == "exact"][:2]
    _ids14 = _ex14 if len(_ex14) == 2 else [e["id"] for e in _cm14["events"]][:2]
    for _e14b in _cm14["events"]:
        if _e14b["id"] == _ids14[0]:
            _e14b["attrib"] = "原告称"
        if _e14b["id"] == _ids14[1]:
            _e14b["attrib"] = "被告称"
    _cm14["conflicts"] = [{"id": "c", "kind": "characterization",
                           "members": _ids14, "note": "测试"}]
    _t14 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
    _t14.close()
    try:
        _mod("render_multiband").render(_cm14, _t14.name)
        _s14 = open(_t14.name, encoding="utf-8").read()
        if "（原告称）" not in _s14 or "（被告称）" not in _s14:
            msgs.append("冲突成员的 attrib 没有拼到正文开头 —— "
                        "读者看不出这一条是谁陈述的")
        # 判据要盯**那两项自己**是不是虚线，不能数全图的虚线总数。
        # 数总数的版本漏过了改坏：示例里本来就有 5 张虚线卡（4 个 range 加 1 个 order），
        # 拿掉冲突判据之后总数仍然 ≥ 2，判据就通过了。
        # 做法：那两项带 attrib，所以按「（原告称）」定位到它所在的那张卡，看它虚不虚。
        for _tag14 in ("（原告称）", "（被告称）"):
            _pos = _s14.find(_tag14)
            if _pos < 0:
                continue
            # 卡框画在文字之前，往前找最近的那个 rx="12" 矩形
            _before = _s14[:_pos]
            _last = _before.rfind('rx="12"')
            _rect = _before[_last:_last + 240] if _last >= 0 else ""
            if "stroke-dasharray" not in _rect:
                msgs.append(f"{_tag14} 那一项是冲突成员，卡框却不是虚线 —— "
                            f"冲突的两方都要用虚线（哪一方为真由法院认定，"
                            f"图上不该替它下判断）")
    except Exception as _e14c:
        msgs.append(f"冲突样本画不出：{str(_e14c).splitlines()[0][:50]}")
    finally:
        os.unlink(_t14.name)

    # 材料自身矛盾：两类机械可查的形态都要报，且已声明的冲突不再报。
    _mcase = [{"id": "1", "head": "原告与被告签订《设计合同》", "date": "2019/5/20",
               "lane": "P"},
              {"id": "2", "head": "被告向原告交付全部设计成果", "date": "2019/4/8",
               "lane": "P"},
              {"id": "3", "head": "原告发出《整改通知》", "date": "2020/3/10", "lane": "P"},
              {"id": "4", "head": "被告发出《结算函》", "date": "2020/3/10", "lane": "D"}]
    _mw = _cm.check_material_conflicts(_mcase)
    if not any("履行行为早于设立行为" in x for x in _mw):
        msgs.append("材料矛盾检查漏过「履行早于设立」（交付 2019/4/8 早于签约 2019/5/20）")
    if not any("同日相反主张" in x for x in _mw):
        msgs.append("材料矛盾检查漏过「同日相反主张」")
    _mw2 = _cm.check_material_conflicts(
        _mcase, conflicts=[{"id": "c", "kind": "characterization",
                            "members": ["3", "4"]}])
    if any("同日相反主张" in x for x in _mw2):
        msgs.append("已声明为冲突的两项仍被报「请核对」—— 声明之后就不该再报，"
                    "否则用户会学会忽略这类告警")

    # 产物上不许出现直角引号「」『』。
    # 大陆法律文书用弯引号“”，直角引号是日文与港台习惯。图名是整张图最大的字，
    # 用错了一眼就看出来 —— 而这类错不会来自代码，来自每次手打图名时的习惯，
    # 所以判据要落在**产物**上，不是落在源码上。
    for name, svg in svgs.items():
        _corner = [c for c in "「」『』" if c in svg]
        if _corner:
            msgs.append(f"{name}: 图上出现直角引号 {''.join(_corner)}，"
                        f"大陆法律文书应用弯引号“”‘’")

    # [C13] anchor 必须真的决定位置：同一份地图，anchor 指向不同事件时图上的先后要变。
    # 这一条是补出来的 —— anchor 曾是死字段，校验器逼着模型填而渲染器从不读，
    # 把它从「2」改成「4」，图上圆点位置一模一样。
    _abase = json.load(open(os.path.join(SKILL, "examples", "two-sides-numbered.json"),
                            encoding="utf-8"))
    _aproto = _abase["events"][0]

    def _amk(anchor20):
        mm20 = _CP.deepcopy(_abase)
        mm20.pop("lanes", None)
        _ev20 = []
        for _i20, (_d20, _h20) in enumerate([("2020/1/1", "甲"), ("2020/6/1", "乙"),
                                             (None, "丙"), ("2021/3/1", "丁"),
                                             ("2021/9/1", "戊")]):
            _e20 = _CP.deepcopy(_aproto)
            _e20["id"] = str(_i20 + 1)
            _e20["head"] = _h20
            for _k20 in ("head_short", "body", "emphasis", "index_note", "lane"):
                _e20.pop(_k20, None)
            if _d20:
                _y20, _mo20, _da20 = _d20.split("/")
                _e20["time"] = {"certainty": "exact", "origin": "extracted",
                                "kind": "occur", "raw": "x", "date": _d20,
                                "date_text": f"{_y20}.{int(_mo20):02d}.{int(_da20):02d}"}
            else:
                _e20["time"] = {"certainty": "relative", "origin": "extracted",
                                "kind": "occur", "raw": "此后", "anchor": anchor20,
                                "date": None, "date_text": ""}
            _ev20.append(_e20)
        mm20["events"] = _ev20
        return mm20

    _seq = {}
    for _a20 in ("2", "4"):
        _t20 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
        _t20.close()
        try:
            _mod("render_multiband").render(_amk(_a20), _t20.name)
            _s20 = open(_t20.name, encoding="utf-8").read()
            _seq[_a20] = [x for x in re.findall(r">([甲乙丙丁戊])</text>", _s20)]
        except Exception as _e21:
            msgs.append(f"anchor 排序样本画不出：{str(_e21).splitlines()[0][:50]}")
        finally:
            os.unlink(_t20.name)
    if len(_seq) == 2 and _seq.get("2") == _seq.get("4"):
        msgs.append(f"anchor 改变了指向，图上的先后却没变（{_seq.get('2')}）—— "
                    f"anchor 是死字段，非精确日期的事项按列表顺序在排")

    # 冲突：声明要站得住，且必须画在图上。
    _cf_items = [{"id": "a", "lane": "P", "date": "2022/9/22"},
                 {"id": "b", "lane": "D", "date": "2023/8/14"},
                 {"id": "c", "lane": "P", "date": "2021/4/1"}]
    _ce, _ = _cm.check_conflicts(
        _cf_items, [{"id": "x", "kind": "characterization", "members": ["a", "b"]}])
    if _ce:
        msgs.append(f"前端检查器：正确的冲突被误判 —— {_ce[0][:60]}")
    for _lbl, _cfs in (("只有一个成员", [{"id": "y", "kind": "date", "members": ["a"]}]),
                       ("成员同在一侧",
                        [{"id": "z", "kind": "date", "members": ["a", "c"]}])):
        _ce2, _ = _cm.check_conflicts(_cf_items, _cfs)
        if not _ce2:
            msgs.append(f"前端检查器漏过「{_lbl}」")
    # 泳道与来源一致：这是最严重的一类错（把对方主张安到自己当事人头上），
    # 而它此前是零检测的 —— 图看起来完全正常，几何全过、文字不挤。
    _lane_tbl = {"起诉状": "P", "答辩状": "D"}
    _le, _ = _cm.check_lane_source(
        [{"id": "1", "lane": "P", "src_file": "起诉状"},
         {"id": "2", "lane": "P", "src_file": "答辩状"}], _lane_tbl)
    if not _le:
        msgs.append("前端检查器漏过「泳道标错」")
    _le2, _ = _cm.check_lane_source(
        [{"id": "1", "lane": "P", "src_file": "起诉状"},
         {"id": "2", "lane": "D", "src_file": "答辩状"},
         {"id": "3", "lane": "P", "src_file": "判决书"}], _lane_tbl)
    if _le2:
        msgs.append(f"前端检查器：正确的泳道被误判 —— {_le2[0][:60]}")

    # 前后端的映射：只给骨架就要能问出形态与容量（写字之前）
    _capm = _mod("capacity")
    _bd = _capm.budget(["2014/3/12", "2014/4/8", "2014/9/25", "2015/2/3",
                        "2015/3/25", "2021/6/18", "2024/11/5", "2025/1/8"],
                       ["P", "D", "P", "D", "P", "D", "P", "D"],
                       title="甲公司与乙公司纠纷主要时点图")
    if _bd["cap_per_module"] < 10 or _bd["cap_title"] < 10:
        msgs.append(f"映射：骨架预算算出的容量不合理 "
                    f"（每模块 {_bd['cap_per_module']} 字、图名 {_bd['cap_title']} 字）")

    # ---- 路由的分界线必须与规范一致 ---------------------------------------
    # 规范第九节写着横纵的分界：写完整句子（40 字）时 17 个事项以内横向、18 个以上纵向；
    # 写短语（20 字）时 21 个以内横向、22 个以上纵向。这几档要与入口的实际选择一一对上。
    # 写那一节时我先按估算写成「短语时横向能撑到 24 个」，而入口在 24 个时转了纵向 ——
    # 规范与代码打架。凡写进规范的数都要实测，这条守卫就是防它再分家。
    _route_cases = [(13, 60, "横向"), (17, 40, "横向"), (18, 40, "纵向"),
                    (21, 20, "横向"), (22, 20, "纵向"), (30, 8, "纵向")]
    for _n16, _c16, _want16 in _route_cases:
        # 直接读文件，不依赖后面才赋值的 _vbase —— 函数内同名局部变量这个坑
        # 在这份文件里已经踩到第五次了。
        _mm16 = json.load(open(os.path.join(SKILL, "examples",
                                            "two-sides-numbered.json"),
                               encoding="utf-8"))
        _pr16 = _mm16["events"][0]
        _ev16 = []
        for _i16 in range(_n16):
            _e16 = _CP.deepcopy(_pr16)
            _e16["id"] = str(_i16 + 1)
            _e16["head"] = "事" * _c16
            for _k16 in ("head_short", "body", "emphasis", "index_note"):
                _e16.pop(_k16, None)
            _d16 = f"20{23 + _i16 // 12}/{_i16 % 12 + 1}/{_i16 % 27 + 1}"
            _y16, _mo16, _da16 = _d16.split("/")
            _e16["time"] = {"certainty": "exact", "origin": "extracted",
                            "kind": "occur", "raw": "x", "date": _d16,
                            "date_text": f"{_y16}.{int(_mo16):02d}.{int(_da16):02d}"}
            _e16["lane"] = "P" if _i16 % 2 == 0 else "D"
            _ev16.append(_e16)
        _mm16["events"] = _ev16
        _mm16["title_text"] = "甲公司与乙公司纠纷时间轴"
        _t16 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
        _t16.close()
        try:
            _k16b, _form16, _why16, _wh16 = _fm.deliver(_mm16, _t16.name)
        except Exception as _e17b:
            msgs.append(f"路由 {_n16}个{_c16}字: 画不出 "
                        f"{str(_e17b).splitlines()[0][:50]}")
            continue
        finally:
            os.unlink(_t16.name)
        if _form16 != _want16:
            msgs.append(f"路由 {_n16}个{_c16}字: 规范说 {_want16}，实际选了 {_form16}")

    # ---- 纵向与分页的穷举 -------------------------------------------------
    # 横向、期间型、日期型都各有一套穷举，纵向与分页一直没有 —— 而 18 个事项以上走的
    # 正是纵向，四五十个以上交付的正是分页那几页。维度：事项数 × 上下分布 × 标题长短
    # × 有无主张方。判据是纵向专属的（卡片不越竖版边界、不相压、文字不出界、页宽等于
    # 整幅宽、分页不切卡、事项不重不漏），照横向那套判据测纵向是测不到的。
    _vm2 = _mod("render_vcolumns")
    _pg2 = _mod("paginate")
    _vbase = json.load(open(os.path.join(SKILL, "examples", "two-sides-numbered.json"),
                            encoding="utf-8"))

    def _vmk(n13, sides13, chars13, lanes13=True):
        mm13 = _CP.deepcopy(_vbase)
        _pr13 = mm13["events"][0]
        evs13 = []
        for i13 in range(n13):
            e13 = _CP.deepcopy(_pr13)
            e13["id"] = str(i13 + 1)
            e13["head"] = "事" * chars13
            for k13 in ("head_short", "body", "emphasis", "index_note"):
                e13.pop(k13, None)
            d13 = f"20{20 + i13 // 12}/{i13 % 12 + 1}/{i13 % 27 + 1}"
            y13, mo13, da13 = d13.split("/")
            e13["time"] = {"certainty": "exact", "origin": "extracted", "kind": "occur",
                           "raw": "x", "date": d13,
                           "date_text": f"{y13}.{int(mo13):02d}.{int(da13):02d}"}
            if lanes13:
                e13["lane"] = "P" if sides13[i13] == "U" else "D"
            evs13.append(e13)
        mm13["events"] = evs13
        if not lanes13:
            mm13.pop("lanes", None)
        mm13["title_text"] = "甲公司与乙公司纠纷双方主张对读时间轴"
        return mm13

    def _audit_vertical(svg13, sheet=False):
        out13 = []
        W13 = float(re.search(r'<svg[^>]*width="([\d.]+)"', svg13).group(1))
        H13 = float(re.search(r'<svg[^>]*height="([\d.]+)"', svg13).group(1))
        _want_w = _paper_mod().SHEET_PORT_W if sheet else _paper_mod().PORT_W
        if abs(W13 - _want_w) > 1:
            out13.append(f"宽 {W13:.0f} 应为 {_want_w}")
        cards13 = []
        for a13 in re.findall(r'<rect([^>]*)>', svg13):
            d13 = dict(re.findall(r'([\w-]+)="([^"]*)"', a13))
            if "rx" not in d13 or "x" not in d13:
                continue
            x13, y13 = float(d13["x"]), float(d13["y"])
            w13, h13 = float(d13["width"]), float(d13["height"])
            cards13.append((x13, y13, x13 + w13, y13 + h13))
            if x13 < -1 or x13 + w13 > W13 + 1:
                out13.append(f"卡片横向越界 {x13:.0f}..{x13 + w13:.0f}")
            if y13 < -1 or y13 + h13 > H13 + 1:
                out13.append(f"卡片被切 y={y13:.0f}..{y13 + h13:.0f}")
        for i14 in range(len(cards13)):
            for j14 in range(i14 + 1, len(cards13)):
                a14, b14 = cards13[i14], cards13[j14]
                if (a14[0] < b14[2] - .5 and b14[0] < a14[2] - .5
                        and a14[1] < b14[3] - .5 and b14[1] < a14[3] - .5):
                    out13.append("卡片相压")
        for x0b, x1b, _y, _fs, body13 in _text_boxes(svg13):
            if x0b < -1 or x1b > W13 + 1:
                out13.append(f"文字出界 {body13[:8]!r}")
        return sorted(set(out13))

    _vpats = {"交错": lambda k: "".join("UD"[i % 2] for i in range(k)),
              "连三": lambda k: "".join("UD"[(i // 3) % 2] for i in range(k)),
              "一边倒": lambda k: "U" * k}
    _vbad = []
    for _n13 in (8, 13, 20, 30, 45, 60):
        for _pn13, _g13 in _vpats.items():
            for _c13 in (4, 12, 25, 45, 70):
                for _ln13 in (True, False):
                    _t13 = _TF.NamedTemporaryFile(suffix=".svg", delete=False)
                    _t13.close()
                    try:
                        _vm2.render(_vmk(_n13, _g13(_n13), _c13, _ln13), _t13.name)
                        _s13 = open(_t13.name, encoding="utf-8").read()
                    except Exception:
                        continue
                    finally:
                        os.unlink(_t13.name)
                    _v13 = _audit_vertical(_s13)
                    if _v13:
                        _vbad.append(f"{_n13}个 {_pn13} {_c13}字 泳道{_ln13}: {_v13[:2]}")
                    # 第三层：把**参数验算**挂进穷举。
                    # 前两层各自只验了十几组样本，而这里已经有 180 组在逐组渲染 ——
                    # 顺手多验一次参数，覆盖面从十几组变成上百组，成本只是多算一遍不等式。
                    try:
                        _pl13 = _vm2.render(_vmk(_n13, _g13(_n13), _c13, _ln13),
                                            None, plan_only=True)
                        for _vio13 in _mod("feasible").verify(_pl13):
                            _vbad.append(f"{_n13}个 {_pn13} {_c13}字 参数验算：{_vio13}")
                    except Exception:
                        pass
    if _vbad:
        msgs.append(f"纵向穷举有 {len(_vbad)} 组违规，例如 {_vbad[0]}")

    _pbad = []
    for _n14 in (25, 45, 80, 120):
        for _pn14, _g14 in _vpats.items():
            for _c14 in (4, 20, 45):
                _d14 = _TF.mkdtemp()
                try:
                    _files14, _np14 = _pg2.paginate(_vmk(_n14, _g14(_n14), _c14), _d14)
                except Exception:
                    continue
                _seen14 = []
                for _f14 in _files14:
                    _s14 = open(_f14, encoding="utf-8").read()
                    for _x14 in _audit_vertical(_s14, sheet=True):
                        _pbad.append(f"{_n14}个 {_pn14} {_c14}字: {_x14}")
                    _seen14 += re.findall(r'data-id="([^"]+)"', _s14)
                if len(_seen14) != len(set(_seen14)):
                    _pbad.append(f"{_n14}个 {_pn14} {_c14}字: 事项重复出现")
                if len(set(_seen14)) != _n14:
                    _pbad.append(f"{_n14}个 {_pn14} {_c14}字: 覆盖 {len(set(_seen14))} 个")
    if _pbad:
        msgs.append(f"分页穷举有 {len(_pbad)} 组违规，例如 {_pbad[0]}")

    # ---- [D12] 刻度格数上限：轴不许变成栅栏 -------------------------------
    # 三个时点跨四个月会铺成 17 格周次，读者要的是三个日期却看到十七个周次。
    # 这一档以前画得出来（几何全过、文字不挤），所以只靠通用守卫测不出 —— 必须专门
    # 拿这个形状去撞它：合规的那一档（五点跨六年、7 格年份）要画得出，
    # 不合规的那一档（三点跨四月、17 格）必须被拒。
    _dm = _mod("render_dated_v2")
    _dbase = json.load(open(os.path.join(SKILL, "examples", "dated-limitation.json"),
                            encoding="utf-8"))

    def _dated_case(items):
        mm12 = _CP.deepcopy(_dbase)
        _pr = mm12["events"][0]
        evs12 = []
        for i12, (d12, h12) in enumerate(items):
            e12 = _CP.deepcopy(_pr)
            e12["id"] = str(i12 + 1)
            e12["head"] = h12
            for k12 in ("head_short", "body", "band", "emphasis"):
                e12.pop(k12, None)
            y12, mo12, da12 = d12.split("/")
            e12["time"] = {"certainty": "exact", "origin": "extracted", "kind": "occur",
                           "raw": "x", "date": d12,
                           "date_text": f"{y12}.{int(mo12):02d}.{int(da12):02d}"}
            evs12.append(e12)
        mm12["events"] = evs12
        return mm12

    _ok_case = [("2018/4/10", "签订借款合同"), ("2020/4/10", "还款期限届满"),
                ("2021/9/1", "发出催款函"), ("2023/2/15", "签署还款协议"),
                ("2024/6/20", "提起本案诉讼")]
    _bad_case = [("2024/3/5", "交付第一批货物"), ("2024/4/18", "通知质量问题"),
                 ("2024/6/28", "提起本案诉讼")]
    try:
        _dm.render(_dated_case(_ok_case))
    except Exception as _e17:
        msgs.append(f"日期型合规样本被拒了：{str(_e17).splitlines()[0][:60]}")
    try:
        _dm.render(_dated_case(_bad_case))
        msgs.append("日期型：三个时点跨四个月铺成 17 格周次，却没有被拒 —— "
                    "刻度格数上限失效，轴已经变成栅栏")
    except Exception:
        pass

    # ---- 按位置做的判断必须用落笔后的坐标 --------------------------------
    # 这一轮最贵的一个错：期间型自己写了一份「四舍五入到半像素」的吸附，而绘制用的是
    # geom 里那份（奇数线宽吸半像素、偶数吸整数）。两份规则不一致，同一批端点被吸成
    # 84.0 / 84.5 / 85.0 / 85.5 四个位置，靠近判定于是把本该一浅一深的两条都标成深色，
    # 穷举 1260 组里报出 45 组「只隔 1px 同色」。
    # 判据：渲染器里不许再出现自制的坐标吸附，一律走 geom.snap_axis。
    for fn9 in sorted(os.listdir(os.path.join(SKILL, "scripts"))):
        if not fn9.startswith("render_") or not fn9.endswith(".py"):
            continue
        src9 = _code_only(open(os.path.join(SKILL, "scripts", fn9),
                               encoding="utf-8").read())
        if re.search(r"round\([^)]*\*\s*2\s*\)\s*/\s*2", src9):
            msgs.append(f"{fn9}: 自己写了坐标吸附（round(v*2)/2），"
                        f"必须走 geom.snap_axis，否则判定与落笔用的是两个数")

    # ---- [P20][P21] 期间端点必须有竖线；靠得太近的必须换色 ----------------
    # 两条都是真材料压出来的：原来只有时点画竖线，六段期间的十二个端点一条都没有；
    # 补上之后又出现两对间距 11px 的同色虚线，读起来是一条画重了的线。
    try:
        _gs2 = json.load(open(os.path.join(SKILL, "examples", "gantt-periods.json"),
                              encoding="utf-8"))
        _gsvg2 = _mod("render_spans_v2").render(_gs2)[0]
        _sp2 = _gs2.get("spans", [])
        _dl = [dict(re.findall(r'([\w-]+)="([^"]*)"', a))
               for a in re.findall(r'<line([^>]*)>', _gsvg2)]
        _dl = [d for d in _dl if "stroke-dasharray" in d]
        if len(_dl) < len(_sp2):
            msgs.append(f"期间端点竖线只有 {len(_dl)} 条，而期间有 {len(_sp2)} 段，"
                        f"端点竖线可能没画")
        # 同一个 x 上可能画着两条重合的线（一个期间端点恰好也是一个时点），其中一条
        # 换了色、一条没换。所以要**按 x 归组**，一组里只要有一条是深色就算已区分；
        # 上一版拿组里任意一条去比，报出「同色」其实是比错了对象。
        _bycol = {}
        for d in _dl:
            _bycol.setdefault(round(float(d["x1"]), 1), set()).add(d.get("stroke"))
        _xs2 = sorted(_bycol)
        _dark = {c for c, _w in _gm.role_pairs()} - {"#C3C9D2", "#ECEEF1"}
        _colmap = {x: ("dark" if (_bycol[x] & _dark) else "soft") for x in _xs2}
        for _a2, _b2 in zip(_xs2, _xs2[1:]):
            if _b2 - _a2 < _mod("render_spans_v2").NEAR_PX:
                if _colmap.get(_a2) == _colmap.get(_b2):
                    msgs.append(f"期间型: x={_a2} 与 x={_b2} 两条竖线只隔 "
                                f"{_b2 - _a2:.0f}px 却同色，读者分不清哪条是哪个日期")
    except Exception as _e16:
        msgs.append(f"端点竖线守卫出错：{type(_e16).__name__}: {str(_e16)[:60]}")

    # ---- 期间型：标签不许贴边；三种图种的正文字号必须一致 ----------------
    # 两条都是探针漏过之后补的：示例的标签都短、日期型示例也短，所以「右侧放宽到两行」
    # 和「日期型退回 17px」两个改坏都测不出来。补一份长标签的期间型进来，并直接比对
    # 三个渲染器的正文字号。
    _gl = {"s1": "二〇一五年度业绩考核窗口", "s2": "二〇一六年度业绩考核窗口",
           "s3": "补充协议调整后的累计考核窗口（两年合计）",
           "s4": "股权代持存续期间，至权益转出之日止，期间目标公司由原股东团队经营",
           "s5": "约定履行期", "s6": "逾期未交付期间",
           "s7": "补充协议签订至提起诉讼的沉默期间，权利人未主张任何权利",
           "s8": "本案审理期间（受理至鉴定）"}
    _gm2 = json.load(open(os.path.join(SKILL, "examples", "gantt-periods.json"),
                          encoding="utf-8"))
    for _s10 in _gm2.get("spans", []):
        if _s10.get("id") in _gl:
            _s10["label_text"] = _gl[_s10["id"]]
    try:
        _gsvg = _mod("render_spans_v2").render(_gm2)[0]
        svgs["期间型长标签:合成"] = _gsvg
        _gw = float(re.search(r'<svg[^>]*width="([\d.]+)"', _gsvg).group(1))
        for _tb in _text_boxes(_gsvg):
            if _tb[1] > _gw - 40:
                msgs.append(f"期间型长标签: 标签 {_tb[4][:10]!r} 右端 {_tb[1]:.0f} "
                            f"贴到画布边缘（画布 {_gw:.0f}，至少留 40px）")
        # [P12] 右侧标签只允许一行 —— 直接数行数，不看位置。
        # 第一版只查「右端有没有贴边」，而放宽到两行之后标签折了行但右端仍在阈值内，
        # 探针照样漏过。规则说的是「只允许一行」，判据就该直接数行数。
        # 做法：把同一个 x 起点、上下相邻一个行距的两处标签视为同一个标签折行。
        _starts = {}
        for _tb in _text_boxes(_gsvg):
            if _tb[3] != _mod("render_spans_v2").FS_LABEL:
                continue
            _starts.setdefault(round(_tb[0]), []).append(_tb[2])
        for _x10, _ys10 in _starts.items():
            _ys10.sort()
            for _a10, _b10 in zip(_ys10, _ys10[1:]):
                if 8 <= _b10 - _a10 <= 24 and _x10 > _gw * 0.5:
                    msgs.append(f"期间型长标签: x={_x10} 处的右侧标签折成多行，"
                                f"右侧只允许一行（放不下就落到条下方）")
                    break
    except Exception as _e13:
        msgs.append(f"期间型长标签画不出：{str(_e13).splitlines()[0][:50]}")

    # 三种图种的正文字号必须相同：同一份材料换个图种，字号不该变。
    _fsb = {}
    for _mn in ("render_multiband", "render_vcolumns", "render_dated_v2"):
        try:
            _fsb[_mn] = _mod(_mn).FS_BODY
        except Exception:
            pass
    if len(set(_fsb.values())) > 1:
        msgs.append(f"三种图种的正文字号不一致：{_fsb}")

    # ---- 标题块：最多两行、字号在阶梯上、块高按行数算 ---------------------
    # 标题不是「上方一行字」，是一个预留的块。三种产物（横向、纵向、分页）必须用同一套，
    # 否则同一个图名在三种形态下的字号和留白各不相同 —— 横向原来根本不折行（长标题直接
    # 溢出画布），分页原来写死 30px 单行，而接上页那几张的标题最长（要多带四个字）。
    _pmod = _paper_mod()
    _lad = set(_pmod.title_ladder())
    for name, svg in svgs.items():
        _tt9 = re.findall(r'<text[^>]*font-weight="700"[^>]*font-size="(\d+)"[^>]*>|'
                          r'<text[^>]*font-size="(\d+)"[^>]*font-weight="700"[^>]*>', svg)
        _sizes = {int(a or b) for a, b in _tt9 if (a or b)}
        _title_sizes = _sizes & set(range(_pmod.title_ladder()[-1], 99))
        for _s9 in _title_sizes:
            if _s9 not in _lad and _s9 < 24:
                continue                        # 圆点数字等也是 700 字重，跳过小号
            if _s9 >= _pmod.title_ladder()[-1] and _s9 not in _lad:
                msgs.append(f"{name}: 标题字号 {_s9} 不在阶梯 {sorted(_lad)} 上")
        # 行数：data-role="title" 的行数不许超过两行（横向标着这个 role）
        _nrole = len(re.findall(r'data-role="title"', svg))
        if _nrole > _pmod.TITLE_MAX_LINES:
            msgs.append(f"{name}: 标题占 {_nrole} 行，最多 {_pmod.TITLE_MAX_LINES} 行")

    # 清晰度：导出倍数必须让纸上 dpi 达到印刷下限。尺寸管字够不够大，倍数管印出来
    # 清不清楚，是两件事；993px 直出只有 108dpi，两倍也只有 216dpi。
    if _pm.raster_dpi() < _pm.PRINT_DPI:
        msgs.append(f"清晰度：按 {_pm.raster_scale()} 倍导出只有 "
                    f"{_pm.raster_dpi():.0f} dpi，低于印刷下限 {_pm.PRINT_DPI}")

    # 期间型也要同样压一遍。它的示例只有一份，而缺陷恰恰出在示例覆盖不到的形状上：
    # 跨三十年、只有两段时，断代说明原来用「轴线上方 26px」这个偏移量落位，正好落进
    # 最后一行，与那一行左侧的起止日期叠了 31px。凡是靠偏移量拼出来的位置，总有一组
    # 数据能让它撞上。
    _gs4 = _iu.spec_from_file_location(
        "render_spans_v2", os.path.join(SKILL, "scripts", "render_spans_v2.py"))
    _gsp = _iu.module_from_spec(_gs4)
    _gs4.loader.exec_module(_gsp)
    _gbase = json.load(open(os.path.join(SKILL, "examples", "gantt-periods.json"),
                            encoding="utf-8"))

    def _gsynth(spans, points=()):
        mm3 = _cp.deepcopy(_gbase)
        mm3["spans"] = [dict(id=str(i3 + 1), label_text=s3[2],
                             **{"from": s3[0], "to": s3[1]})
                        for i3, s3 in enumerate(spans)]
        mm3["points"] = [dict(id=f"p{i3 + 1}", date=p3[0], label_text=p3[1])
                         for i3, p3 in enumerate(points)]
        mm3["axis"] = {"start": spans[0][0], "end": max(s3[1] for s3 in spans)}
        return mm3

    GANTT_CASES = [
        ("8 段连续", [(f"{2015 + i}/1/1", f"{2016 + i}/6/1", f"第{i + 1}段期间")
                   for i in range(8)], ()),
        ("8 段全部一天", [(f"2015/1/{i + 1}", f"2015/1/{i + 2}", f"第{i + 1}段")
                     for i in range(8)], ()),
        ("标签极长", [("2015/1/1", "2016/1/1", "补充协议调整后的累计业绩考核窗口两年合计不低于一千二百六十万元"),
                  ("2015/6/1", "2017/1/1", "案涉业务约定履行期间自签约后三个月内交付并验收合格")], ()),
        ("跨三十年，两段带断代", [("1995/1/1", "1996/1/1", "远期期间"),
                       ("2024/1/1", "2025/1/1", "近期期间")], ()),
        ("8 个时点挤在一月", [("2015/1/1", "2016/1/1", "唯一期间")],
         tuple((f"2015/1/{i + 1}", f"时点{i + 1}") for i in range(8))),
    ]
    for label, spans, points in GANTT_CASES:
        try:
            svg4, _w4, _h4 = _gsp.render(_gsynth(spans, points))
        except Exception as _e4:
            continue      # 拒绝是允许的：期间型宁可不画，也不画错
        for g4 in _geometry_msgs(svg4):
            msgs.append(f"期间型 {label}: {g4}")
        if _pm.over_budget(_w4, _h4, landscape=True):
            msgs.append(f"期间型 {label}: 超出横版预算 {_w4:.0f}x{_h4:.0f}")
        boxes4 = _text_boxes(svg4)
        for i4 in range(len(boxes4)):
            for j4 in range(i4 + 1, len(boxes4)):
                a4, b4 = boxes4[i4], boxes4[j4]
                if abs(a4[2] - b4[2]) > max(a4[3], b4[3]) * 0.6:
                    continue
                lo4, hi4 = (a4, b4) if a4[0] <= b4[0] else (b4, a4)
                if hi4[0] - lo4[1] < 0.8 * max(a4[3], b4[3]):
                    msgs.append(f"期间型 {label}: 同一行两处文字只隔 "
                                f"{hi4[0] - lo4[1]:.0f}px，{lo4[4][:12]!r} 与 "
                                f"{hi4[4][:12]!r}")

    # 间隙比例取自 geom.TEXT_CLEAR，渲染器读的是同一个。守卫自己写一份 0.8 时，
    # 它与渲染器里那个「+8px」曾经差 1.6px，日期型有一档正好落在这个缝里。
    # ---- 日期型与期间型：穷举，只许两种结局 -------------------------------
    # 这两种图种此前各自只有一份示例在被检查，等于几乎没采样。它们的保证方式是
    # 「拒绝而不是硬画」，而这句话只有穷举才证得出：把事件数、间隔形态、标签长度、
    # 时点个数交叉铺开，每一组的结局必须是**拒绝并给出机械理由**，或者**画出来且
    # 完全合规**，中间那种（画出来但相压、出界、挤压、破禁则、超预算）一组都不许有。
    # 第一次跑出来日期型 3 组、期间型 57 组落在中间，都不是想象出来的：
    #   日期型那 3 组的根子是同一件事有两个数 —— 渲染器判标签放不下用「标签宽 + 8px」，
    #   守卫要求 0.8 倍字号即 9.6px，8 < 9.6，于是有一档正好从缝里溜过去。
    #   期间型那 57 组是我自己引入的回归 —— 起止日期搬到条的左侧之后，「右边放不下就
    #   退到左边」这条旧规则让标签正好压在日期上。改一处而不告诉另一处那块地已经
    #   有人，是这个项目反复出现的错法。
    def _audit_figure(svg, landscape=True):
        out = list(_geometry_msgs(svg))
        mw4 = re.search(r'<svg[^>]*width="([\d.]+)"', svg)
        mh4 = re.search(r'<svg[^>]*height="([\d.]+)"', svg)
        w4, h4 = float(mw4.group(1)), float(mh4.group(1))
        if _pm.over_budget(w4, h4, landscape=landscape):
            out.append("超出纸张预算")
        bs4 = _text_boxes(svg)
        for x0b, x1b, _y, _fs, body in bs4:
            if x0b < -1 or x1b > w4 + 1:
                out.append(f"文字出界 {body[:10]!r}")
            if body[0] in NO_START or body[-1] in NO_END:
                out.append(f"禁则 {body[:8]!r}")
        for i4 in range(len(bs4)):
            for j4 in range(i4 + 1, len(bs4)):
                a4, b4 = bs4[i4], bs4[j4]
                if abs(a4[2] - b4[2]) > max(a4[3], b4[3]) * 0.6:
                    continue
                lo4, hi4 = (a4, b4) if a4[0] <= b4[0] else (b4, a4)
                if hi4[0] - lo4[1] < _gm.TEXT_CLEAR * max(a4[3], b4[3]):
                    out.append(f"文字相挤 {lo4[4][:8]!r}|{hi4[4][:8]!r}")
        if "\u2026" in svg or "..." in svg:
            out.append("出现省略号")
        # [P16] 的产物形式：期间条（直角 rect）必须够宽到看得见。
        # 只在源码里挡不住 —— 拿掉那道门禁后穷举照样全过，因为原来的形状最短也有半年，
        # 从来没采到 3px 那一档。规则要配一个恰好卡在它身上的探针，这一条是 HANDOVER
        # 里「判据之间有冗余，单改一条测不出来」的老账。
        for a6 in re.findall(r'<rect([^>]*)>', svg):
            d6 = dict(re.findall(r'([\w-]+)="([^"]*)"', a6))
            # 期间条是直角，而直角在这里的写法是**不写 rx**，不是 rx="0"。第一版判据
            # 写成 rx == "0"，于是一条也没扫到，探针照样漏过 —— 守卫写错了判据，看起来
            # 却和守住了一模一样。
            if "rx" in d6 or "x" not in d6 or "width" not in d6:
                continue
            if d6.get("data-role") == "axis":
                continue
            if 0 < float(d6["width"]) < 6:
                out.append(f"期间条只有 {float(d6['width']):.1f}px，看不见")
        return sorted(set(out))

    _ds = _iu.spec_from_file_location(
        "render_dated_v2", os.path.join(SKILL, "scripts", "render_dated_v2.py"))
    _dmod = _iu.module_from_spec(_ds)
    _ds.loader.exec_module(_dmod)
    _gsp = _iu.spec_from_file_location(
        "render_spans_v2", os.path.join(SKILL, "scripts", "render_spans_v2.py"))
    _gmod = _iu.module_from_spec(_gsp)
    _gsp.loader.exec_module(_gmod)

    _DPAT = {
        "等距按年": lambda n: [f"{2010 + i}/6/1" for i in range(n)],
        "等距两年": lambda n: [f"{2010 + i * 2}/6/1" for i in range(n)],
        "等距三年": lambda n: [f"{2006 + i * 3}/6/1" for i in range(n)],
        "尾部长空白": lambda n: [f"2010/6/{i + 1}" for i in range(n - 1)] + ["2025/6/1"],
        "中间长空白": lambda n: ([f"{2010 + i}/3/1" for i in range((n + 1) // 2)]
                             + [f"{2024 + i}/3/1" for i in range(n // 2)]),
        "同月密集": lambda n: [f"2020/5/{i + 1}" for i in range(n)],
        "跨十年零散": lambda n: [f"{2005 + i * 4}/{(i % 12) + 1}/{(i % 28) + 1}"
                            for i in range(n)],
    }
    _HEADS = ("第N项", "第N项事实经过与凭证", "第N项事实经过及其对应的书面凭证与说明")
    dated_mid = []
    for pn, gen4 in _DPAT.items():
        for n4 in range(2, 13):
            for h4 in _HEADS:
                mm4 = _synth(gen4(n4))
                for e4 in mm4["events"]:
                    e4["head"] = h4.replace("N", e4["id"])
                try:
                    svg4, _w5, _h5 = _dmod.render(mm4)
                except Exception:
                    continue                  # 拒绝，合法结局
                v4 = _audit_figure(svg4)
                if v4:
                    dated_mid.append(f"{pn} n={n4}: {v4[:2]}")
                # 第三层：参数验算挂进日期型穷举（这一段本来就在逐组渲染）
                try:
                    _pl4 = _dm.render(_cp.deepcopy(mm4), plan_only=True)
                    for _vio4 in _mod("feasible").verify(_pl4):
                        dated_mid.append(f"{pn} n={n4} 参数验算：{_vio4}")
                except Exception:
                    pass
    if dated_mid:
        msgs.append(f"日期型穷举有 {len(dated_mid)} 组画出来却违规，"
                    f"例如 {dated_mid[0]}")

    _gbase = json.load(open(os.path.join(SKILL, "examples", "gantt-periods.json"),
                            encoding="utf-8"))

    def _gsynth(spans, npt, label):
        mm5 = _cp.deepcopy(_gbase)
        proto = _gbase["spans"][0]
        arr = []
        for i5, (a5, b5) in enumerate(spans):
            s5 = _cp.deepcopy(proto)
            s5["id"], s5["from"], s5["to"] = f"s{i5 + 1}", a5, b5
            s5["label_text"] = label.replace("N", str(i5 + 1))
            s5.pop("emphasis", None)
            s5.pop("directional", None)
            s5["unit_type"] = "fact" if i5 % 2 else "stipulated"
            arr.append(s5)
        arr[0]["unit_type"] = "fact"
        mm5["spans"] = arr
        pts = []
        protop = (_gbase.get("points") or [None])[0]
        if protop:
            for j5 in range(npt):
                q5 = _cp.deepcopy(protop)
                q5["id"] = f"p{j5 + 1}"
                q5["date"] = f"{2015 + j5}/{(j5 % 12) + 1}/{(j5 % 27) + 1}"
                q5["label_text"] = f"第{j5 + 1}个时点"
                q5.pop("emphasis", None)
                pts.append(q5)
        mm5["points"] = pts
        mm5["axis"] = {"start": min(a for a, _ in spans),
                       "end": max(b for _, b in spans)}
        return mm5

    def _gspans_dense(n5, yrs, gap):
        """按段数、跨度、端点密度生成期间。端点密度是撞出竖线靠近那一类问题的关键维度。"""
        from datetime import date as _d9, timedelta as _t9
        out = []
        d0 = _d9(2020, 1, 1)
        total = int(yrs * 365)
        for i9 in range(n5):
            s9 = d0 + _t9(days=int(i9 * gap))
            e9 = s9 + _t9(days=max(20, int(total * (0.3 + 0.1 * (i9 % 4)))))
            out.append((f"{s9.year}/{s9.month}/{s9.day}",
                        f"{e9.year}/{e9.month}/{e9.day}"))
        return out


    def _gspans(n5, mode):
        o5 = []
        for i5 in range(n5):
            if mode == "全重叠":
                o5.append(("2015/1/1", f"{2016 + i5}/12/31"))
            elif mode == "链式重叠":
                o5.append((f"{2015 + i5}/1/1", f"{2017 + i5}/6/30"))
            elif mode == "嵌套":
                o5.append((f"{2015 + i5}/1/1", f"{2015 + 2 * n5 - i5}/12/31"))
            elif mode == "并列不重叠":
                o5.append((f"{2015 + 2 * i5}/1/1", f"{2015 + 2 * i5}/12/31"))
            elif mode == "长轴夹短段":
                # 一段 15 天的约定付款期落在多年跨度里 —— 真材料就是这个形状：
                # 轴 3286 天，最短段 15 天，比例 1:219，比例轴装不下这两个尺度。
                o5.append((f"{2019 + i5}/1/1", f"{2019 + i5}/1/16") if i5 == 0
                          else (f"{2019 + i5}/1/1", f"{2021 + i5}/6/30"))
            else:
                o5.append((f"{2000 + 2 * i5}/1/1", f"{2003 + 2 * i5}/6/30"))
        return o5

    # 维度里加进这几轮新发现的那些：**端点密度**（相邻端点隔几天）与跨度长短。
    # 之前的穷举只铺了重叠形态与标签长短，撞不出「两条竖线靠到 1px」这类问题 ——
    # 那一类是靠端点密度这一维才暴露的（1260 组里 45 组）。
    # 合成材料的日期必须**单调递增**。用 2023/{i%12+1} 这类循环日期时，第 13 个绕回
    # 一月，而排序按日期走（见 [C13]），交错就被打乱、容量随之下降 —— 守卫报出「规范说
    # 横向，实际选了纵向」，看着像规范错了，其实是测试数据假设「列表顺序就是时间顺序」。
    # 加上 anchor 排序之后这个假设不再成立，凡造合成材料都要让日期真的递增。
    _GAPS = (3, 15, 90, 400)
    _YRS = (1, 2, 5, 11, 20)
    _GLAB = ("第N段", "第N段约定考核窗口",
             "第N段补充协议调整后的累计考核窗口（两年合计不低于1260万元）")
    gantt_mid = []
    for mode in ("全重叠", "链式重叠", "嵌套", "并列不重叠", "跨二十年", "长轴夹短段"):
        for n5 in range(2, 10):
            for npt in (0, 3, 6):
                for lab in _GLAB:
                    try:
                        svg5, _w6, _h6 = _gmod.render(_gsynth(_gspans(n5, mode),
                                                              npt, lab))
                    except Exception:
                        continue              # 拒绝，合法结局
                    v5 = _audit_figure(svg5)
                    if v5:
                        gantt_mid.append(f"{mode} n={n5} 时点{npt}: {v5[:2]}")
    # 再跑一轮「端点密度 × 跨度」的穷举。
    for _n9 in range(2, 9):
        for _y9 in _YRS:
            for _g9 in _GAPS:
                for _l9 in _GLAB:
                    _sp9 = _gspans_dense(_n9, _y9, _g9)
                    try:
                        _svg9, _w9b, _h9b = _gmod.render(_gsynth(_sp9, 0, _l9))
                    except Exception:
                        continue
                    _v9 = _audit_figure(_svg9)
                    if _v9:
                        gantt_mid.append(f"{_n9}段 跨{_y9}年 端点隔{_g9}天: {_v9[:2]}")
    if gantt_mid:
        msgs.append(f"期间型穷举有 {len(gantt_mid)} 组画出来却违规，"
                    f"例如 {gantt_mid[0]}")

    # ---- 约束表与代码的编号必须一一对应 ----------------------------------
    # references/layout-constraints.md 把日期型与期间型的每条约束写成不等式并编号，
    # 代码里执行它的那一行带同样的编号。两边分开演化时，改紧一处忘了另一处，就会出现
    # 文档说得漂亮而代码管不住的情况 —— 模型三步那一段已经教过这件事，做法照搬。
    _doc = open(os.path.join(SKILL, "references", "layout-constraints.md"),
                encoding="utf-8").read()
    _doc_ids = set(re.findall(r"\|\s*([DPCM]\d+)\s*\|", _doc))
    _code_ids = set()
    for fn in sorted(os.listdir(os.path.join(SKILL, "scripts"))):
        if fn.endswith(".py"):
            src = open(os.path.join(SKILL, "scripts", fn), encoding="utf-8").read()
            # 编号必须写成 # [C6] 这种带方括号的形式。不能只认 C6：注释里出现的
            # 十六进制颜色 #C6CBD2 会被当成编号 C6，于是「代码里找不到 C6」这条永远
            # 报错，而实际上标记就在那里。守卫的正则太宽，与被检查的内容撞车。
            _code_ids |= set(re.findall(r"#\s*\[([DPCM]\d+)\]", src))
    # C1 到 C4 与 P15 是共同前提与「不做」，落在 paper.py / 守卫 / 无实现，不逐行标。
    _exempt = {"C1", "C2", "C3", "C4", "P15"}
    _only_doc = _doc_ids - _code_ids - _exempt
    _only_code = _code_ids - _doc_ids
    if _only_doc:
        msgs.append(f"约束表里有 {sorted(_only_doc)} 条，代码里找不到执行处")
    if _only_code:
        msgs.append(f"代码里标了 {sorted(_only_code)}，约束表里没有这一条")

    # ---- 附表逐格核对：表里写的字数与层数，必须与实际渲染一致 ---------------
    # 这张表是前端做抽丝剥茧时唯一要查的东西（取材几个事项 → 标题抽到几个字），所以它
    # 一旦与代码漂移就会指挥前端写出装不下的文字。守卫的做法是把每一格重新渲染一次，
    # 拿实测的字数上限与层数去比表里的数。四十四格，跑一遍约一分钟。
    _rows_tbl = re.findall(
        r"^\|\s*(\d+)\s*\|\s*(\d+)\s*\|(.+?)\|\s*$", _doc, re.M)
    _pats = {
        0: lambda k: "".join("UD"[i % 2] for i in range(k)),           # 交错
        1: lambda k: "".join("UD"[(i // 2) % 2] for i in range(k)),    # 连二
        2: lambda k: "".join("UD"[(i // 3) % 2] for i in range(k)),    # 连三
        3: lambda k: "U" * k,                                          # 全在一侧
    }
    _mbs = _iu.spec_from_file_location(
        "render_multiband", os.path.join(SKILL, "scripts", "render_multiband.py"))
    _mb = _iu.module_from_spec(_mbs)
    _mbs.loader.exec_module(_mb)
    _tbl_bad = []
    for _n_s, _cw_s, _cells in _rows_tbl:
        _n = int(_n_s)
        if not 8 <= _n <= 18:
            continue
        _parts = [c.strip() for c in _cells.split("|") if c.strip()]
        for _ci, _cell in enumerate(_parts[:4]):
            _m2 = re.match(r"(\d+)字\s*(\d+)\+(\d+)\s*(\d+)", _cell)
            if not _m2:
                continue
            _want = (int(_m2.group(1)), int(_m2.group(2)), int(_m2.group(3)))
            _sides = _pats[_ci](_n)
            _got = None
            # 上限要留够：高度反解之后一层能写到一百多字，扫到 60 就停会把表判错
            # （守卫报 59 而实际 119）。这是「探针的量程不够」，不是产物不对。
            for _c in range(1, 140):
                mm6 = _cp.deepcopy(_gbase) if False else None
                mm6 = json.load(open(os.path.join(SKILL, "examples",
                                                  "two-sides-numbered.json"),
                                     encoding="utf-8"))
                _proto = mm6["events"][0]
                _evs = []
                for _i in range(_n):
                    _e = _cp.deepcopy(_proto)
                    _e["id"] = str(_i + 1)
                    _e["head"] = "事" * _c
                    # head_short 优先于 head，探针必须先去掉它，否则改的是没人读的字段。
                    # 第一版就栽在这里：四十四格全都测出同一个四字标题，而我以为在扫字数。
                    _e.pop("head_short", None)
                    _e.pop("body", None)
                    _e["time"] = {"certainty": "exact", "origin": "extracted",
                                  "kind": "occur", "raw": "x",
                                  "date": f"20{23 + _i // 12}/{_i % 12 + 1}/{_i % 27 + 1}",
                                  "date_text": f"2023.{_i % 12 + 1:02d}.{_i % 27 + 1:02d}"}
                    _e.pop("emphasis", None)
                    _e.pop("index_note", None)
                    _e["lane"] = "P" if _sides[_i] == "U" else "D"
                    _evs.append(_e)
                mm6["events"] = _evs
                _t6 = _tf2.NamedTemporaryFile(suffix=".svg", delete=False)
                _t6.close()
                try:
                    # 不传字号，走默认档。守卫必须守交付时真正用的那一档 —— 这里
                    # 曾经写死 fs_body=12，于是默认改成 13 之后守卫仍在检 12，
                    # 报出「表写 36 实测 39」这种两边各说各话的错。
                    _w6, _h6, _bu, _bd, _f6, _p6 = _mb.render(mm6, _t6.name)
                    if _h6 <= _pm.LAND_H:
                        _got = (_c, _bu, _bd)
                except Exception:
                    pass
                finally:
                    os.unlink(_t6.name)
            if _got != _want:
                _tbl_bad.append(f"n={_n} 第{_ci + 1}列: 表写 {_want}，实测 {_got}")
    if _tbl_bad:
        msgs.append(f"附表有 {len(_tbl_bad)} 格与实际不符，例如 {_tbl_bad[0]}")

    # ---- [M8] 放宽后仍只许两档宽度 ---------------------------------------
    # 逐卡放宽是为了「能放下的就放下」，但宽度必须量化成最小与优雅两档。第一版直接用
    # 每张卡各自的余量，一张图里出现了 111 / 112 / 114 / 143 / 214 五种宽度，差两个
    # 像素的两张卡看着就是没对齐 —— 那正是作者说的「没调好」。
    # 不相压不穿卡由前面的几何守卫管；这一条只管「只有两档」。
    # 示例地图的事件挨得均匀，逐卡余量算出来几乎一样，所以只用示例是测不出「多档宽度」
    # 的 —— 第一版探针就这么漏过了。补一个成簇加大段空白的形状进来，那才是优雅宽度真正
    # 发挥作用、也真正会分出多档的形状。
    _clustered = json.load(open(os.path.join(SKILL, "examples",
                                             "two-sides-numbered.json"),
                                encoding="utf-8"))
    _cdates = ["2023/1/10", "2023/1/12", "2023/1/15", "2023/1/20", "2023/2/2",
               "2023/2/3", "2023/2/10", "2024/6/5", "2024/6/8", "2024/6/20",
               "2025/3/18", "2025/4/15", "2025/5/6"]
    _cev = []
    for _i, _d in enumerate(_cdates):
        _e = _cp.deepcopy(_clustered["events"][0])
        _e["id"] = str(_i + 1)
        _e["head"] = "事项经过"
        _e.pop("head_short", None)
        _e.pop("body", None)
        _e.pop("emphasis", None)
        _e.pop("index_note", None)
        _y, _mo, _da = _d.split("/")
        _e["time"] = {"certainty": "exact", "origin": "extracted", "kind": "occur",
                      "raw": f"{_y}年{_mo}月{_da}日", "date": _d,
                      "date_text": f"{_y}.{int(_mo):02d}.{int(_da):02d}"}
        _e["lane"] = "P" if _i % 2 == 0 else "D"
        _cev.append(_e)
    _clustered["events"] = _cev
    _tc = _tf2.NamedTemporaryFile(suffix=".svg", delete=False)
    _tc.close()
    try:
        _mb.render(_clustered, _tc.name)
        svgs["成簇:合成"] = open(_tc.name, encoding="utf-8").read()
    except Exception as _ec:
        msgs.append(f"成簇形状画不出：{str(_ec).splitlines()[0][:60]}")
    finally:
        os.unlink(_tc.name)

    # 分页产物也要进被检查的集合。它是独立于入口的另一条路径，此前只被几条老守卫
    # 看着，新加的那些（白边、整幅尺寸、只许两档宽度、文字不相挤）一条都没覆盖到 ——
    # 而四五十个事项那一档交付出去的正是这几页。
    import tempfile as _tf3
    # paper 在这一段之前还没被载入，自己载一次，且**换个变量名**：函数里后面有
    # `_pm = ...`，Python 因此把 _pm 当整个函数的局部变量，在赋值之前读它必然报
    # UnboundLocalError。同名不同处赋值，是这类错的常见来源。
    _pms2 = _iu.spec_from_file_location(
        "paper", os.path.join(SKILL, "scripts", "paper.py"))
    _pmA = _iu.module_from_spec(_pms2)
    _pms2.loader.exec_module(_pmA)
    _pgs = _iu.spec_from_file_location(
        "paginate", os.path.join(SKILL, "scripts", "paginate.py"))
    _pgm = _iu.module_from_spec(_pgs)
    try:
        _pgs.loader.exec_module(_pgm)
        _pm2 = json.load(open(os.path.join(SKILL, "examples", "stress-two-sides.json"),
                              encoding="utf-8"))
        for _e7 in _pm2["events"]:
            _e7.pop("head_short", None)
            _e7.pop("body", None)
        # 换成长图名：分页的标题最长（接上页那几张还要多带四个字），而守卫样本原来
        # 用的是短图名，于是「分页标题写死 30px 单行」这个改坏一直漏过。
        _pm2["title_text"] = ("东北大学秦皇岛分校与秦皇岛佳鑫诺教育科技有限公司"
                              "买卖合同纠纷双方主张对读时间轴")
        _pd = _tf3.mkdtemp()
        _pfiles, _pn = _pgm.paginate(_pm2, _pd)
        for _pf in _pfiles:
            svgs[f"分页:{os.path.basename(_pf)}"] = open(_pf, encoding="utf-8").read()
        for _pf in _pfiles:
            _s7 = open(_pf, encoding="utf-8").read()
            _m7 = re.search(r'<svg[^>]*width="([\d.]+)"[^>]*height="([\d.]+)"', _s7)
            _b7 = _pmA.sheet_ok(float(_m7.group(1)), float(_m7.group(2)), landscape=False)
            if _b7:
                msgs.append(f"分页 {os.path.basename(_pf)}: {_b7}")
    except Exception as _e8:
        msgs.append(f"分页守卫本身出错: {type(_e8).__name__}: {str(_e8)[:60]}")

    for name, svg in svgs.items():
        wset = {round(float(w)) for w in re.findall(
            r'<rect x="[\d.]+" y="[\d.]+" width="([\d.]+)"[^>]*rx="12"', svg)}
        if len(wset) > 2:
            msgs.append(f"{name}: 卡片出现 {len(wset)} 种宽度 {sorted(wset)}，"
                        f"只许最小与优雅两档")

    CLEAR = _gm.TEXT_CLEAR
    for name, svg in svgs.items():
        for g in _geometry_msgs(svg):
            msgs.append(f"{name}: {g}")

    # ---- 禁则：行首不许是收尾标点，行尾不许是起始标点 ---------------------
    # STANDARDS §4 是冻结标准，而它被破过一次，破法很典型：geom.wrap_atomic 为了不拆
    # 数字重写了一遍贪心折行循环，把 common.wrap 的禁则丢在原处，只在「整段文字里
    # 一个数字都没有」时才回退。于是凡卡片里带数字，禁则就失效，纵向图上一个顿号
    # 当真跑到了行首。标准写在文档里挡不住这个，守卫才挡得住。
    for name, svg in svgs.items():
        for _x0, _x1, _y, _fs, body in _text_boxes(svg):
            if body[0] in NO_START:
                msgs.append(f"{name}: 行首出现收尾标点 {body[:14]!r}")
            if body[-1] in NO_END:
                msgs.append(f"{name}: 行尾出现起始标点 {body[:14]!r}")
    for name, svg in svgs.items():
        boxes = _text_boxes(svg)
        mw = re.search(r'<svg[^>]*width="([\d.]+)"', svg)
        W = float(mw.group(1)) if mw else 0
        for x0, x1, y, fs, body in boxes:
            if x0 < -1 or x1 > W + 1:
                msgs.append(f"{name}: 文字出界 {body[:16]!r} 占 {x0:.0f}..{x1:.0f}，"
                            f"画布宽 {W:.0f}")
        for i in range(len(boxes)):
            for j in range(i + 1, len(boxes)):
                a, b = boxes[i], boxes[j]
                if abs(a[2] - b[2]) > max(a[3], b[3]) * 0.6:
                    continue               # 不在同一行，不比
                lo, hi = (a, b) if a[0] <= b[0] else (b, a)
                gap = hi[0] - lo[1]
                if gap < CLEAR * max(a[3], b[3]):
                    msgs.append(f"{name}: 同一行两处文字只隔 {gap:.0f}px，"
                                f"{lo[4][:12]!r} 与 {hi[4][:12]!r}")

    # ---- routing: the proposal must match the shape it was measured against
    # These are not "the answers we happen to get today" — each one is a case
    # whose shape was measured during development, and the router exists to
    # reproduce that reading. If a threshold moves, one of these breaks and says
    # which.
    _rs = _iu.spec_from_file_location(
        "propose_layout", os.path.join(SKILL, "scripts", "propose_layout.py"))
    _rm = _iu.module_from_spec(_rs)
    _rs.loader.exec_module(_rm)
    expect = {
        # overlapping factual periods -> the bars carry the argument
        "gantt-periods.json": "期间型",
        # three well-separated points, one interval dominating the span
        "dated-limitation.json": "日期型",
        # everything real: events cluster into days, so spacing must promise
        # nothing. Seven of ten, which is why the numbered form is the default.
        "numbered-multiband.json": "编号型",
        "two-sides-actors.json": "编号型",
        "dated-proportional.json": "编号型",
        "stress-two-sides.json": "编号型",
    }
    for fn, want in expect.items():
        fp = os.path.join(SKILL, "examples", fn)
        if not os.path.exists(fp):
            msgs.append(f"路由用例缺失: {fn}")
            continue
        mm = json.load(open(fp, encoding="utf-8"))
        try:
            _a, _c, _r = _rm.propose(mm)
        except Exception as _e2:
            # 路由器现在会真的试排一次，所以渲染器坏了会从这里冒出来。要报成一句话：
            # 裸抛会让整套在这里断掉，前面所有已经收集到的消息一起丢失。
            msgs.append(f"路由 {fn}: 路由器出错 {type(_e2).__name__}: "
                        f"{str(_e2).splitlines()[0][:90]}")
            continue
        got = _c[0][0] if _c else "无"
        if got != want:
            msgs.append(f"路由 {fn}: 建议 {got}，应为 {want}")
        # every rejection must state why; a bare "not suitable" teaches nothing
        for name, why in _r:
            if not why or len(why) < 6:
                msgs.append(f"路由 {fn}: 排除 {name} 未给出理由")

    # ---- 路由器说的排布，必须就是真会画出来的那一张 ----------------------
    # 这是「路由器与渲染器必须算同一件事」的可检形式。它们曾经分家：路由器按事件数
    # 查表说横向多层，渲染器试排之后拒绝，用户拿到一句自相矛盾的建议。现在两边都走
    # render_figure，这条守卫钉住这一点 —— 哪天有人又在路由器里塞一张阈值表，它会响。
    _fs = _iu.spec_from_file_location(
        "render_figure", os.path.join(SKILL, "scripts", "render_figure.py"))
    _fm = _iu.module_from_spec(_fs)
    _fs.loader.exec_module(_fm)
    for fn in sorted(os.listdir(os.path.join(SKILL, "examples"))):
        if not fn.endswith(".json"):
            continue
        mm = json.load(open(os.path.join(SKILL, "examples", fn), encoding="utf-8"))
        if not mm.get("events"):
            continue
        try:
            form, _why = _fm.predict(mm)
        except Exception as _e:
            # 入口跑不出图是一条失败，但要作为一句话报出来。裸抛会让整套在这里断掉，
            # 前面收集到的所有消息一起丢失，只剩一个 traceback。
            msgs.append(f"路由 {fn}: 入口没能出图 {type(_e).__name__}: "
                        f"{str(_e).splitlines()[0][:90]}")
            continue
        form, _why = form, _why
        _a, _c, _r = _rm.propose(mm)
        said = next((d for nm, _lay, d in _c if nm == "编号型"), None)
        if said is None:
            msgs.append(f"路由 {fn}: 没有给出编号型这一项（编号型总是可用）")
        elif form not in said:
            msgs.append(f"路由 {fn}: 建议里的排布与实际画出来的不一致，"
                        f"实际是{form}，建议写的是 {said[:40]}")

    # Each criterion needs a case that turns on IT ALONE, or loosening one
    # threshold changes nothing because another still catches the map. Probing
    # the real examples proved exactly that: relaxing the three-day rule left
    # every verdict unchanged, because the six-point ceiling caught them anyway.
    def _mk(offsets, exact=True):
        """Build a probe from explicit day offsets, so each case has the exact
        shape the criterion it targets is about."""
        from datetime import date, timedelta
        d0 = date(2020, 1, 1)
        return {"schema_version": 2, "layout": "numbered_point_timeline",
                "title_text": "t", "events": [
                    {"id": str(i + 1), "head": f"事件{i+1}",
                     "time": {"certainty": "exact" if exact else "range",
                              "raw": "x",
                              "date": (d0 + timedelta(days=o)).strftime("%Y/%m/%d"),
                              "date_text": "x"}}
                    for i, o in enumerate(offsets)]}

    def _synth(n, step_days, exact=True):
        return _mk([i * step_days for i in range(n)], exact)

    probes = [
        # three years wide, but two of the points fall in the same week — the
        # real shape that defeats a proportional axis
        ("轴上几乎重合", _mk([0, 400, 402, 1100]), "日期型", "几乎重合"),
        ("时点过多", _synth(9, 400), "日期型", "超过 8 个"),
        ("间隔无主次", _synth(4, 400), "日期型", "长短相近"),
        # dots separate comfortably; the CARDS are what collide
        ("同侧卡片放不下", _mk([0, 200, 260, 320, 800]), "日期型", "放不下"),
        ("日期不精确", _synth(4, 400, exact=False), "日期型", "没有精确日期"),
    ]
    for tag, mm, form, frag in probes:
        _a, _c, _r = _rm.propose(mm)
        hit = [w for nm, w in _r if nm == form and frag in w]
        if not hit:
            msgs.append(f"路由判据「{tag}」失效：{form} 未因 {frag!r} 被排除，"
                        f"实得 {[w for nm, w in _r if nm == form] or '未排除'}")

    # ---- A4 landscape is the ceiling for every horizontal figure -----------
    # A lawyer prints this on one sheet. A figure wider than the sheet gets
    # scaled down at print time until the 8pt floor breaks, so the width is a
    # hard limit and the ruler pitch is what gives way.
    # 上限**从 paper 现取**，不写死。1177 是白边改档之前的数，比现在的内容画幅
    # 1070 松 107px —— 一张 1150px 的横向图会照样通过，而它印出来已经跌破 8pt。
    # 守卫管的是渲染器直出的**未加白边**的产物（实测编号型 1068、日期型与期间型 1070），
    # 所以上限是内容画幅 LAND_W，不是交付整幅 SHEET_LAND_W。
    A4_LAND = _mod("paper").LAND_W
    for name, svg in svgs.items():
        if name.startswith(("纵向", "纵列", "分页")):
            continue
        mm = re.search(r'<svg[^>]*width="(\d+)"', svg)
        if mm and int(mm.group(1)) > A4_LAND:
            msgs.append(f"{name}: 画布宽 {mm.group(1)}px 超过 A4 横版 {A4_LAND}px")

    # ---- break marks are notches, and the run they compress is visibly short --
    for name, svg in svgs.items():
        # both slashes of a pair must be the same length
        lens = []
        for x1, y1, x2, y2 in re.findall(
                r'<line x1="([-\d.]+)" y1="([-\d.]+)" x2="([-\d.]+)" y2="([-\d.]+)"', svg):
            dx, dy = float(x2) - float(x1), float(y2) - float(y1)
            if abs(dx) > 1 and abs(dy) > 1:          # slanted: a break slash
                lens.append(round((dx * dx + dy * dy) ** 0.5, 1))
        if lens and max(lens) - min(lens) > 1.0:
            msgs.append(f"{name}: 断线两段长度不一 {sorted(set(lens))}")
        if lens and max(lens) > 30:
            msgs.append(f"{name}: 断线长 {max(lens):.0f}px，应只是刻度上的一个缺口")

    # ---- nothing may sit outside the canvas, and the axis must reach the ends
    # A long outboard label used to widen the left margin, which pushed the ruler
    # inward and left a stretch of paper with labels floating over no axis at
    # all. The label moves now, not the margin — this checks both halves of that.
    for name, svg in svgs.items():
        mm = re.search(r'<svg[^>]*width="(\d+)"[^>]*height="(\d+)"', svg)
        if not mm:
            continue
        W_, H_ = int(mm.group(1)), int(mm.group(2))
        for tx, ty in re.findall(r'<text x="([-\d.]+)" y="([-\d.]+)"', svg):
            if float(tx) < -2 or float(tx) > W_ + 2:
                msgs.append(f"{name}: 文字横向出界 x={tx}")
                break
        # the ruler must span essentially the whole plot, not start part-way in
        ax = re.search(r'data-role="axis"[^>]*x1="([\d.]+)"[^>]*x2="([\d.]+)"', svg)
        if ax and (float(ax.group(2)) - float(ax.group(1))) < W_ * 0.55:
            msgs.append(f"{name}: 轴线只覆盖画布的 "
                        f"{(float(ax.group(2)) - float(ax.group(1))) / W_ * 100:.0f}%，"
                        f"左右有大片无轴区域")

    # ---- cards: every non-accent card carries v1's 1px border -------------
    # 奇川风 gives cards fill #F3F4F6 with a #D6DAE0 stroke; only the deep-red
    # card is borderless. Three renderers here were drawing fill alone, which
    # reads flatter than the rest of the skill and is a silent divergence from
    # the frozen style.
    for name, svg in svgs.items():
        plain = re.findall(r'<rect[^>]*fill="#F3F4F6"[^>]*/>', svg)
        naked = [r for r in plain if "stroke=" not in r]
        if naked:
            msgs.append(f"{name}: {len(naked)} 张常规卡片没有描边，奇川风要求 1px #D6DAE0")
        red = re.findall(r'<rect[^>]*fill="#991B1B"[^>]*/>', svg)
        boxed = [r for r in red if "stroke=" in r]
        if boxed:
            msgs.append(f"{name}: 深红卡片带了描边，强调块应为无框实心")

    # ---- type scale: the renderers may never name a size of their own -------
    # Card body is FS["node_title"], the date line is FS["subtitle"], the figure
    # title is FS["doc_title"]. Nothing else. This is pinned because the sizes
    # can appear to change without being changed — swapping a four-character
    # label for a twenty-character one, or narrowing the card, both read as a
    # bigger typeface — so the one thing that must be beyond doubt is that the
    # numbers themselves are v1's and are not being nudged.
    import importlib.util as _iu
    _cs = _iu.spec_from_file_location(
        "common", os.path.join(SKILL, "..", "mqc-litigation-visual-redraw",
                               "scripts", "common.py"))
    _cm = _iu.module_from_spec(_cs)
    _cs.loader.exec_module(_cm)
    allowed = {_cm.FS["doc_title"], _cm.FS["node_title"],
               _cm.FS["subtitle"], _cm.FS["note"]}
    for mod in ("render_multiband.py", "render_vcolumns.py",
                "render_vertical.py", "paginate.py"):
        src = open(os.path.join(SKILL, "scripts", mod), encoding="utf-8").read()
        for lit in re.findall(r'font-size="(\d+)"', src):
            msgs.append(f"{mod}: 硬编码字号 {lit}，必须取自 common.FS")
        for m2 in re.finditer(r'^\s*FS_[A-Z_]+\s*=\s*(\d+)\s*$', src, re.M):
            msgs.append(f"{mod}: 字号常量写死为 {m2.group(1)}，必须取自 common.FS")
    for name, svg in svgs.items():
        used = {int(x) for x in re.findall(r'font-size="(\d+)"', svg)}
        # 标题阶梯上的每一档都是合法字号（它们全部取自字阶，只是这条守卫的白名单
        # 原来是按「一张图只用几个固定字号」写的，没算上标题会降档）。
        stray = used - allowed - set(_paper_mod().title_ladder()) - {round(v) for v in
                                  [_cm.FS["node_title"] * 0.6, 8, 9, 10, 11, 12, 13, 14]}
        if stray:
            msgs.append(f"{name}: 出现非字阶字号 {sorted(stray)}")

    # the dot model must be ONE shared formula, not a per-layout lookup table
    # 名单原来写死三个渲染器，于是日期型与期间型从来不在被检查的集合里 —— 「金额被
    # 拆到两行」这个修过两次的 bug 第三次复发，就是因为日期型的卡片一直直接调 wrap()
    # 而没人看着。守卫的覆盖面本身也会漂移，所以改成扫全部渲染器，新加一个自动纳入。
    for mod in sorted(f for f in os.listdir(os.path.join(SKILL, "scripts"))
                      if f.startswith("render_") and f.endswith(".py")):
        src = _code_only(open(os.path.join(SKILL, "scripts", mod),
                              encoding="utf-8").read())
        if "DOT_R_BY" in src or "NUM_RATIO =" in src or "NUM_BASE =" in src:
            msgs.append(f"{mod}: 圆点尺寸必须取自 geom.dot_metrics，不得在渲染器里另立常量")
        # every wrap must go through wrap_atomic, or one renderer silently keeps
        # splitting numbers while the others do not — which is exactly how this
        # bug came back after being fixed once
        if re.search(r"[^_a-zA-Z]wrap\(", src):
            msgs.append(f"{mod}: 直接调用 wrap()，必须走 geom.wrap_atomic")
    # and every dot must sit on the pixel grid, or no baseline ratio can centre
    # the numeral consistently across orientations
    for name, svg in svgs.items():
        off = [float(m) for m in re.findall(r'<circle[^>]*cy="([\d.]+)"', svg)]
        bad = [v for v in off if abs((v - int(v)) - 0.5) > 1e-6]
        if bad:
            msgs.append(f"{name}: {len(bad)} 个圆心未落在半像素网格")

    # every rule must come from geom.hairline, i.e. no renderer builds one inline
    for mod in ("render_multiband.py", "render_vertical.py"):
        src = open(os.path.join(SKILL, "scripts", mod), encoding="utf-8").read()
        if "'<line x1=" in src or '"<line x1=' in src:
            msgs.append(f"{mod}: 仍有直接拼接的 <line>，必须走 geom.hairline")
    return len(svgs), msgs


def main():
    print("=== v1 的示例地图必须原样通过（纯增量的证明）===")
    v1bad = 0
    for fn in sorted(os.listdir(V1_EXAMPLES)):
        if not fn.endswith(".json"):
            continue
        E, W = V.check(os.path.join(V1_EXAMPLES, fn))
        if E:
            v1bad += 1
            print(f"  FAIL {fn}: {E}")
    print(f"  {'全部通过' if not v1bad else str(v1bad) + ' 份未通过'}\n")

    print("=== 本 skill 的示例地图 ===")
    ownbad = 0
    own = os.path.join(SKILL, "examples")
    for fn in sorted(os.listdir(own)):
        if not fn.endswith(".json"):
            continue
        E, W = V.check(os.path.join(own, fn))
        if E:
            ownbad += 1
            print(f"  FAIL {fn}: {E}")
    print(f"  {'全部通过' if not ownbad else str(ownbad) + ' 份未通过'}\n")

    print("=== 读材料：确定性部分 ===")
    import importlib.util as _ru
    _rs2 = _ru.spec_from_file_location(
        "read_source", os.path.join(SKILL, "scripts", "read_source.py"))
    _rd = _ru.module_from_spec(_rs2)
    _rs2.loader.exec_module(_rd)
    read_msgs = []

    # Splitting must LOSE NOTHING. Rejoining every sentence has to reproduce the
    # normalised text character for character; anything else means a sentence
    # was dropped, and a dropped sentence defeats the whole recall guarantee
    # before the model is even asked a question.
    fx = os.path.join(SKILL, "tests", "fixtures")
    if os.path.isdir(fx):
        import re as _re2
        for fn in sorted(os.listdir(fx)):
            if not fn.endswith(".txt"):
                continue
            raw = open(os.path.join(fx, fn), encoding="utf-8").read()
            text, counts = _rd.normalise(raw)
            sents = _rd.split_sentences(text)
            a = _re2.sub(r"\s+", "", "".join(sents))
            b = _re2.sub(r"\s+", "", text)
            if a != b:
                read_msgs.append(f"{fn}: 切句丢失 {len(b) - len(a)} 字")
            # reconciliation must reject an unaccounted sentence
            miss, extra = _rd.reconcile(sents, [{"sentence_ids": [0]}], [])
            if len(miss) != len(sents) - 1:
                read_msgs.append(f"{fn}: 完备性对账没有报出未处理的句子")
            # paragraphs must reconstruct, and the narrative blocks must be found
            ps = _rd.paragraphs(raw)
            if len(ps) < 2:
                read_msgs.append(f"{fn}: 段落重建只得到 {len(ps)} 段")
            bl = _rd.blocks(ps)
            if fn.startswith("judgment-"):
                # both heading conventions must yield the same five blocks; the
                # judgment that never writes 「本院查明」 is the whole reason the
                # findings are located by their closing line
                for need in ("claim", "defence", "findings", "reasoning", "order"):
                    if need not in bl:
                        read_msgs.append(f"{fn}: 未识别叙述块 {need}")
                if "findings" in bl:
                    a, b = bl["findings"]
                    if not any(_re2.match(r"^\d{2,4}\s*年", ps[i])
                               for i in range(a, min(b, len(ps)))):
                        read_msgs.append(f"{fn}: findings 块内没有任何以日期开头的段落")

            # a quote that is not in the text must be rejected
            bad = _rd.verify_quotes(sents, [{"id": "x", "source":
                                             {"quote": "此句绝不存在于材料之中"}}])
            if not bad:
                read_msgs.append(f"{fn}: 溯源核验放过了查不到的引文")
    else:
        read_msgs.append("缺少 tests/fixtures，读材料这一段没有素材可验")
    # ---- the three model steps: the checker must reject every shape of bad
    # output, and the instructions must name every field the checker demands.
    # Instructions and checker drift apart otherwise: a rule tightened in one and
    # not the other makes the model produce output that cannot pass, with nothing
    # to say why.
    _cs = _ru.spec_from_file_location(
        "check_model_output", os.path.join(SKILL, "scripts", "check_model_output.py"))
    _ck = _ru.module_from_spec(_cs)
    _cs.loader.exec_module(_ck)
    S3 = ["甲公司与乙公司于2021年1月5日签订《设备采购合同》。", "本院认为构成违约。"]
    good_cls = [{"i": 0, "is_event": True, "why": "签约"},
                {"i": 1, "is_event": False, "why": "说理"}]
    probes = [
        ("漏判句子", lambda: _ck.check_classify(S3, good_cls[:1])),
        ("重复判定", lambda: _ck.check_classify(S3, good_cls + [good_cls[0]])),
        ("判定无理由", lambda: _ck.check_classify(
            S3, [good_cls[0], {"i": 1, "is_event": False, "why": ""}])),
        ("时间原文改写", lambda: _ck.check_dates(
            S3, [{"i": 0, "certainty": "exact", "raw": "2021年初", "date": "2021/1/5"}])),
        ("无到日却判exact", lambda: _ck.check_dates(
            S3, [{"i": 1, "certainty": "exact", "raw": "本院", "date": "2021/1/1"}])),
        ("range缺端点", lambda: _ck.check_dates(
            S3, [{"i": 0, "certainty": "range", "raw": "2021年1月5日", "from": "2021/1/1"}])),
        ("片段被改写", lambda: _ck.check_segments(
            S3, [{"i": 0, "head": "双方于年初签约"}])),
        ("片段乱序", lambda: _ck.check_segments(
            S3, [{"i": 0, "head": "《设备采购合同》签订甲公司"}])),
        ("body与items并存", lambda: _ck.check_segments(
            S3, [{"i": 0, "head": "签订合同", "body": "甲公司", "items": ["乙公司"]}])),
    ]
    for tag, fn in probes:
        errs, _redo = fn()
        if not errs:
            read_msgs.append(f"模型输出校验「{tag}」放过了不合规输出")
    ok_cls, _ = _ck.check_classify(S3, good_cls)
    if ok_cls:
        read_msgs.append(f"模型输出校验误伤了合规输出：{ok_cls[:1]}")

    doc = os.path.join(SKILL, "references", "model-steps.md")
    if not os.path.exists(doc):
        read_msgs.append("缺少 references/model-steps.md，模型三步没有指令可依")
    else:
        d = open(doc, encoding="utf-8").read()
        for field in ("is_event", "why", "certainty", "raw", "anchor",
                      "head", "body", "items", "index_note",
                      "exact", "range", "relative", "order"):
            if field not in d:
                read_msgs.append(f"指令未提到校验器要求的 {field}")

    for x in read_msgs:
        print(f"  FAIL {x}")
    print(f"  {'通过' if not read_msgs else str(len(read_msgs)) + ' 项未过'}\n")

    print("=== 脱敏守卫（仓库内不得留下真实标识）===")
    import importlib.util as _au
    _as = _au.spec_from_file_location(
        "check_anonymised", os.path.join(HERE, "check_anonymised.py"))
    _am = _au.module_from_spec(_as)
    _as.loader.exec_module(_am)
    # The gate covers the whole plugin, not just this skill: a real name in the
    # other skill's documentation is exactly as public. It found one there the
    # first time it was pointed at it.
    PLUGIN = os.path.normpath(os.path.join(SKILL, "..", ".."))
    # ANON_GUARD_SELFTEST — this file plants real-looking identifiers a few lines
    # below in order to prove the guard reports them, so the guard skips it. The
    # marker only works under tests/, and every skip is printed.
    _exempt = []
    leaks = _am.scan(PLUGIN, _exempt)
    for f in _exempt:
        print(f"  略过（守卫的自测样本）{f}")
    for f, what, frag in leaks:
        print(f"  FAIL {f}: {what} {frag!r}")
    print(f"  {'干净' if not leaks else str(len(leaks)) + ' 处未脱敏'}\n")

    # A clean scan proves nothing on its own: an empty pattern table scans clean
    # too. Both structural rules leaked something real once, so each one gets a
    # planted identifier and has to report it. The plant goes into a COPY of the
    # shipping tree — the guard reads files, so it can only be tested on files.
    print("=== 脱敏守卫本身：种一个真标识，必须报出来 ===")
    import shutil as _sh
    import tempfile as _tf

    def _plant(mutate):
        tmp = _tf.mkdtemp()
        for d in ("examples", "references"):
            _sh.copytree(os.path.join(SKILL, d), os.path.join(tmp, d))
        mutate(tmp)
        found = _am.scan(tmp)
        _sh.rmtree(tmp, ignore_errors=True)
        return found

    def _edit(path, old, new):
        def go(root):
            p = os.path.join(root, path)
            t = open(p, encoding="utf-8").read()
            assert old in t, f"改坏测试的锚点已失效：{path} 中找不到 {old!r}"
            open(p, "w", encoding="utf-8").write(t.replace(old, new, 1))
        return go

    PLANTS = [
        ("机构名不是占位符（文档）",
         _edit("references/model-steps.md",
               "甲公司与丙公司签署", "金码大酒店与年年吉祥公司签署")),
        ("机构名不是占位符（地图）",
         _edit("examples/gantt-periods.json",
               "丙公司为目标公司", "丙公司为华鼎实业集团")),
        ("引文里内嵌专名",
         _edit("examples/two-sides-numbered.json",
               "原告因案涉设备购置项目公开招标",
               "原告因“分校运动会信息化建设设备购置”公开招标")),
        ("旧规则仍在：公民身份号码",
         _edit("examples/gantt-periods.json",
               "脱敏示例", "脱敏示例。110101199001011234")),
    ]
    plant_fail = 0
    for label, mutate in PLANTS:
        got = _plant(mutate)
        if got:
            print(f"  {label:<28}抓住　{got[0][1]}")
        else:
            plant_fail += 1
            print(f"  {label:<28}漏过　这条规则形同不存在")

    # Planting proves the rule fires. It does not prove the rule stays quiet on
    # correct input, and a rule that flags 丁建设有限公司 gets switched off within a
    # week. So both sides are pinned, one probe per shape the rule has to get
    # right. The 过 column is the half that was wrong in the first draft.
    PROBES = [
        ("甲公司", False), ("丁建设有限公司", False), ("甲控股集团", False),
        ("目标公司", False), ("控股公司", False), ("有限公司", False),
        ("某某人民法院", False), ("参照旧公司法解释三", False),
        ("无法在原场所继续经营酒店", False), ("北京某某律师事务所", False),
        ("年年吉祥公司", True), ("金码大酒店", True),
        ("华鼎实业集团", True), ("天元律师事务所", True),
        ("丙公司为华鼎实业集团", True),   # 前一句的占位符不得掩护后面的真名
        ("原告甲学院与被告乙公司", False),
    ]
    probe_fail = 0
    for s, want in PROBES:
        got = bool(_am._entity_hits(s))
        if got != want:
            probe_fail += 1
            print(f"  探针 {s:<16}期望{'报出' if want else '放过'}，"
                  f"实得{'报出' if got else '放过'}")
    print(f"  {len(PLANTS) - plant_fail}/{len(PLANTS)} 条确认可失败；"
          f"{len(PROBES) - probe_fail}/{len(PROBES)} 个探针符合预期\n")
    plant_fail += probe_fail

    print("=== 渲染期守卫（直线像素对齐 / 唯一画线入口）===")
    nsvg, rmsgs = main_render_guards()
    for x in rmsgs:
        print(f"  FAIL {x}")
    print(f"  渲染 {nsvg} 张，{'全部对齐且都在纸内' if not rmsgs else str(len(rmsgs)) + ' 项未过'}")
    for name, why in _refused():
        print(f"  已拒绝（设计如此，不是失败）{name}: {why}")
    print()

    print("=== 每条守卫故意改坏，必须报错 ===")
    print(f"{'守卫':<34}{'结果':>6}")
    print("-" * 58)
    fail = 0
    for name, expect, mutate in CASES:
        m = copy.deepcopy(GOOD)
        mutate(m)
        E, W = run(m)
        hit = any(expect in x for x in E)
        if not hit:
            fail += 1
            print(f"{name:<34}{'没抓住':>6}   期望含 {expect!r}，实得 {E or '零错误'}")
        else:
            print(f"{name:<34}{'抓住':>6}")
    print("-" * 58)
    E, W = run(copy.deepcopy(GOOD))
    clean = not E
    print(f"{'未改动的好地图仍然通过':<34}{'是' if clean else '否':>6}")
    print(f"\n{len(CASES) - fail}/{len(CASES)} 条守卫确认可失败" + ("" if clean else "；但好地图被误伤"))
    return 1 if (fail or not clean or v1bad or ownbad or rmsgs or leaks
                 or read_msgs or plant_fail) else 0


if __name__ == "__main__":
    sys.exit(main())
