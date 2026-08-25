#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""五种可编辑格式的交付：SVG / PNG / PPTX / VSDX / drawio。

## 做法：复用 v1 的导出器，一个字都不重写

v1（诉讼可视化重画）已经把这件事解决了，而且解决得比新写一遍更好：
`export_pptx` 与 `export_vsdx` **读最终 SVG 再逐元素转**，所以「交付的就是那张图」——
同一套几何、同一种风格，不存在两份实现漂开的问题；`svg_to_png` 也在 v1 的 `render.py` 里。
所以这里只做三件事：调它们、把 v2 的地图适配成它们认识的形状、把结果汇总回来。

**不要在这里重写任何一种格式。** 这个项目反复吃过同一个教训：从零重写会无声丢掉
想清楚过的东西（日期型从零写过一次，丢了刻度尺、擅自加了圆点、卡宽被改）。
从已有的答案出发，丢不掉东西。

## 唯一需要新写的：地图适配

v1 的 `export_drawio` 从**语义地图**建模（不是从 SVG），而它读的是 v1 的字段名：
事项的正文在 `ev["text"]`、日期在顶层 `ev["date_text"]`。v2 的地图把正文放在 `head`、
把时间放在 `time` 里，另外还多了 v1 没有的 layout 名（`numbered_multiband`、
`vertical_single_column`、`vertical_two_columns`）。这些在几何上仍是编号型时间轴，
所以映到 v1 认识的那个名字即可 —— **改的是喂给它的地图，不是它本身**（v1 冻结）。

## 一条边界

drawio 的 `.drawio.svg` 是「一张正常的 SVG，同时内嵌可编辑模型」。它内嵌的是
**从地图建的模型**，与我们那张 SVG 的排布不是逐像素相同的（v1 的 drawio 有自己的
一套摆放）。所以它算第五种格式的可编辑载体，不算「那张图的另一种存法」，
说明里要照这个说，不许含糊。

**这条边界具体差在哪：drawio 里没有侧标签。** 自检量出来的 ——
SVG / PPTX / VSDX 三种都有「原告主张 / 被告主张」，drawio **一律没有**。
原因不是坏了，是 **v1 的 `export_drawio` 从来不知道泳道的存在**：那份 564 行的建模器
里一处 `lane` 都没有，v1 自己的八份示例也没有任何图用泳道 —— 泳道是 v2 新增的能力。
pptx / vsdx 有侧标签，是因为它们**读最终 SVG 逐元素转**（`export_pptx` 的开头写着
transcribe the master SVG instead of re-deriving the layout）；drawio 走的是从地图
重新建模那条路。

**明确不补**，理由是代价与收益不成比例：
  · 要补就得动 v1 那 564 行建模器，给它加一个新的几何维度（它的摆放自成一套，
    见 `_positions_layered`）—— 而 v1 是冻结的、已发布的、同行认可的。
  · 或者把 drawio 也改成读 SVG，那是重写，而上面「不要重写任何一种格式」那条
    正是为此写的。
  · 主交付物没问题：律师开庭用的 SVG、贴 Word 的 PNG、讲课用的 PPTX 全都有侧标签。
    drawio 是「拿到 draw.io 里继续改」的载体，少两个侧标签不影响那个用途，
    而且分侧本身在 drawio 里是看得见的（卡片仍在轴的两侧）。

守卫盯的是**另一头**：SVG / PPTX / VSDX 这三种**必须**有侧标签 —— 哪天它们也丢了，
要当场报出来。（记在 ADR 0008。）
"""
import copy
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
V1 = os.path.join(HERE, "..", "..", "mqc-litigation-visual-redraw", "scripts")
for _p in (HERE, V1):
    if _p not in sys.path:
        sys.path.insert(0, _p)

#: v2 的 layout 名 → v1 的导出器认识的名字。几何上都是编号型时间轴。
_LAYOUT_ALIAS = {
    "numbered_multiband": "numbered_point_timeline",
    "vertical_single_column": "numbered_point_timeline",
    "vertical_two_columns": "numbered_point_timeline",
}


def adapt_map(m):
    """把 v2 的语义地图摊平成 v1 导出器认识的形状。

    与渲染器里的 `_v2_dates` 做的是同一件事（text ← head、date/date_text 从 time 摊上来），
    只是那边是给自己用、这边是给 v1 用。不改原地图。
    """
    m = copy.deepcopy(m)
    m["layout"] = _LAYOUT_ALIAS.get(m.get("layout"), m.get("layout"))
    for e in m.get("events", []) or []:
        t = e.get("time") or {}
        if not e.get("text"):
            e["text"] = e.get("head") or e.get("label_text") or ""
        if t.get("date_text") and not e.get("date_text"):
            e["date_text"] = t["date_text"]
        if t.get("date") and not e.get("date"):
            e["date"] = t["date"]
    for s in m.get("spans", []) or []:
        if not s.get("text"):
            s["text"] = s.get("label_text") or ""
    return m


def _svg_for_export(svg_path):
    """把**背景矩形**摘掉之后的一份临时 SVG，专供 pptx / vsdx 读。

    起因是一个真问题：PPT 里标题跑到了页面正中。查出来是 v1 的 `attach_text` 会把
    「包住某段文字、且那段文字水平居中于它」的矩形当成宿主，把文字折进那个形状 ——
    它只排除了「面积 ≥ 画布 90%」的背景。而我们的图有**两层白底**：`paper.frame`
    加的整幅 1154×443（100%，被排除）和渲染器自己画的内容底 1068×385 ——
    后者只占 **80.4%**，正好从 90% 那条线底下漏过去，于是把居中的图名收养了，
    标题框变成 1068×385、文字在框里垂直居中，看起来就是「标题跑到页面中间」。

    修法：渲染器给内容底打了 `data-role="canvas-bg"`，这里在交给 v1 的导出器之前
    把这一行摘掉。**不改 v1**（它冻结），也**不改交付的那张 SVG**（那是主交付物，
    背景要留着，否则贴到深色背景上会透）。摘掉背景不影响 pptx / vsdx：
    幻灯片与画布本身就是白的。
    """
    import re as _re
    src = open(svg_path, encoding="utf-8").read()
    cleaned = _re.sub(r'<rect data-role="canvas-bg"[^>]*/>\s*', "", src)
    if cleaned == src:
        return svg_path, None
    tmp = os.path.splitext(svg_path)[0] + ".__export__.svg"
    open(tmp, "w", encoding="utf-8").write(cleaned)
    return tmp, tmp


def deliver(svg_path, m, base=None):
    """出齐五种格式，返回 [(格式, 路径, 说明)]。

    **每一种都独立守护**：任何一种失败都只是少一种格式，绝不影响已经写好的 SVG。
    这一条照 v1 的做法来（它的注释写着 additive; NEVER breaks the SVG/PNG deliverable）。
    """
    base = base or os.path.splitext(svg_path)[0]
    out = [("SVG", svg_path, "主交付物，全元素可编辑")]

    try:
        import render as _v1render
        png = base + ".png"
        engine = _v1render.svg_to_png(svg_path, png)
        out.append(("PNG", png, f"位图预览与归档（{engine}）"))
    except Exception as exc:
        out.append(("PNG", "", f"未生成：{str(exc).splitlines()[0][:60]}"))

    # pptx / vsdx 读的是**摘掉背景矩形**的那一份（见 _svg_for_export）。
    _src, _tmp = _svg_for_export(svg_path)
    try:
        import export_pptx
        p, n = export_pptx.export(_src, base + ".pptx")
        out.append(("PPTX", p, f"{n} 个原生 PowerPoint 对象，逐个可改"))
    except Exception as exc:
        out.append(("PPTX", "", f"未生成：{str(exc).splitlines()[0][:60]}"))

    try:
        import export_vsdx
        p, n = export_vsdx.export(_src, base + ".vsdx")
        out.append(("VSDX", p, f"{n} 个形状，ProcessOn / Visio / WPS 可打开编辑"))
    except Exception as exc:
        out.append(("VSDX", "", f"未生成：{str(exc).splitlines()[0][:60]}"))

    try:
        import export_drawio
        mx, _a, _b = export_drawio.build_model(adapt_map(m))
        p = base + ".drawio"
        open(p, "w", encoding="utf-8").write(mx)
        out.append(("drawio", p, f"{mx.count('<mxCell')} 个单元，draw.io 内可改"))
        try:
            svg = open(svg_path, encoding="utf-8").read()
            p2 = base + ".drawio.svg"
            open(p2, "w", encoding="utf-8").write(export_drawio.embed_in_svg(svg, mx))
            out.append(("drawio.svg", p2, "正常 SVG，同时内嵌可编辑模型"))
        except Exception as exc:
            out.append(("drawio.svg", "", f"未生成：{str(exc).splitlines()[0][:60]}"))
    except Exception as exc:
        out.append(("drawio", "", f"未生成：{str(exc).splitlines()[0][:60]}"))

    if _tmp and os.path.exists(_tmp):
        os.unlink(_tmp)          # 临时文件不留在交付目录里
    return out


if __name__ == "__main__":
    import json
    if len(sys.argv) < 3:
        print("用法：export_formats.py <出图.svg> <语义地图.json>")
        sys.exit(1)
    for fmt, path, note in deliver(sys.argv[1],
                                   json.load(open(sys.argv[2], encoding="utf-8"))):
        print(f"  {fmt:<11}{path or '（无）':<40}{note}")
