# SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""dev-board#888 回归：三方对读的编号型时间轴导出 PPTX 时事件卡片重叠。

真机复现（QA-048-20260923 bug-008）：六事件合成案例
「样品采购交接争议事实经过时间轴」，PPTX 里 E-02 卡片盖住 E-01 右侧、E-01 总价
只剩"18,64..."、E-04/E-05 的日期与正文互盖。draw.io/PNG/SVG 同一案例据当时记录
也有覆盖，只是那份证据后来被 draw.io 编辑/保存重写，正式记录只留下了 PPTX 这一份。

根因定位（本文件之前做的实测，见 dev-board#888 落实记录）：不在
`export_pptx.py`——它只是把母版 SVG 的几何 1:1 照抄成 PPTX 形状，SVG 本身在
`render_multiband.py` 就已经画出了重叠的矩形。触发条件是「泳道」（lanes）声明到
三条以上时（这里是 申请方 / 北岸质检室 / 回应方 三方对读），`render()` 用
`lane_band` 把每个事件的横带号**直接按所属泳道覆盖**掉几何算法本该给出的、
带防撞检查的 `band`（`render_multiband.py` 里紧跟 `if lane_band:` 的那几行）——
覆盖之后同一条泳道的事件全部被摁进同一条横带，从不检查这条带内部挨不挨得下。
本案例里"申请方"这条泳道六项里占了五项，其中两项（E-01/E-02）在轴上只隔一个
列距（约 140px）却要摆 214px 宽的卡，真实横向重叠 74px。

修法（[AWD-PATCH 5]，见 PATCHES.md）：覆盖之后补一次同侧同带的横向碰撞检查，
撞了就 raise ValueError——这是这份引擎一贯的「排不下就拒绝，不能悄悄画出重叠」
做法（render_dated_v2.py 的 "collision: refuse, do not invent" 就是同一原则）。
`render_figure.deliver()` 的阶梯接到这个 ValueError 后会自动改试纵向
（render_vcolumns），所以修完仍然交得出一张图，只是形态从横向变纵向。

[AWD-PATCH 5] 把这份密集材料的交付形态从横向改成纵向之后，纵向这条路自己又带
出第二个缺陷（[AWD-PATCH 6]，见 PATCHES.md）：`render_vcolumns.py` 画的纵向
画布配 `paper.frame()` 裱白边后，`export_pptx.py` 的 `attach_text()` 判断
"是不是画布背景"用的是"面积占画布 90% 以上"这条面积比阈值——裱框之后外层
`<svg>` 变大而内层背景矩形没变，占比能跌到 90% 以下，于是标题被误收进这个几乎
铺满整页的背景矩形，PPTX 里标题变成横跨大半页的巨框、视觉上压在别的卡片上。
两条补丁一起看才是 #888 的完整交付面：先出现的（横向卡片重叠）与它路由到纵向
之后暴露的（标题吞并背景），都在这个夹具上验证。

跑法：
    python3 litviz/tests/test_timeline_pptx_overlap.py
"""
import copy
import os
import re
import sys
import tempfile
import unittest
import zipfile

_HERE = os.path.dirname(os.path.abspath(__file__))
_LITVIZ = os.path.dirname(_HERE)
_TL_SCRIPTS = os.path.join(_LITVIZ, "skills", "mqc-timeline-master", "scripts")
sys.path.insert(0, _TL_SCRIPTS)

import render_figure  # noqa: E402  (自己会把 redraw 引擎的 scripts/ 一并塞进 sys.path)
import export_pptx  # noqa: E402

EMU_PER_PX = export_pptx.EMU_PER_PX

# 与真机复现一致：合成案例「样品采购交接争议事实经过时间轴」六事件，
# 三方对读（申请方 / 北岸质检室 / 回应方），照 bug-008.md 的原文重建。
BASE_MAP = {
    "schema_version": 2,
    "diagram_type": "timeline",
    "title_text": "样品采购交接争议事实经过时间轴",
    "layout": "numbered_point_timeline",
    "medium": "a4_landscape",
    "lanes": [
        {"id": "P", "label_text": "申请方 林栀禾（青岚器物工作室）"},
        {"id": "Q", "label_text": "北岸质检室"},
        {"id": "D", "label_text": "回应方 周闻川（云栖材料社）"},
    ],
    "events": [
        {
            "id": "E-01", "lane": "P", "unit_type": "event",
            "time": {"certainty": "exact", "date": "2026/9/12", "date_text": "2026.09.12"},
            "head": "约定交付 12 组陶片样品，示例总价人民币 18,640.00 元",
            "head_short": "约定交付 12 组陶片样品，示例总价人民币 18,640.00 元",
        },
        {
            "id": "E-02", "lane": "P", "unit_type": "event",
            "time": {"certainty": "exact", "date": "2026/9/14", "date_text": "2026.09.14"},
            "head": "记录示例付款人民币 10,000.00 元，余款待验收",
            "head_short": "记录示例付款人民币 10,000.00 元，余款待验收",
        },
        {
            "id": "E-03", "lane": "D", "unit_type": "event",
            "time": {"certainty": "exact", "date": "2026/9/16", "date_text": "2026.09.16"},
            "head": "交付清单记为 12 组；双方对包装外观的描述不一致",
            "head_short": "交付清单记为 12 组；双方对包装外观的描述不一致",
        },
        {
            "id": "E-04", "lane": "P", "unit_type": "event",
            "time": {"certainty": "exact", "date": "2026/9/18", "date_text": "2026.09.18"},
            "head": "申请方提出其中 2 组样品存在外观差异；回应方要求按复核流程处理",
            "head_short": "申请方提出其中 2 组样品存在外观差异；回应方要求按复核流程处理",
        },
        {
            "id": "E-05", "lane": "Q", "unit_type": "event",
            "time": {"certainty": "exact", "date": "2026/9/20", "date_text": "2026.09.20"},
            "head": "北岸质检室记录复核费用人民币 2,300.00 元，并建议补充留样照片",
            "head_short": "北岸质检室记录复核费用人民币 2,300.00 元，并建议补充留样照片",
        },
        {
            "id": "E-06", "lane": "P", "unit_type": "event",
            "time": {"certainty": "exact", "date": "2026/9/23", "date_text": "2026.09.23"},
            "head": "双方同意交换编号照片，但对尾款支付条件仍有争议",
            "head_short": "双方同意交换编号照片，但对尾款支付条件仍有争议",
        },
    ],
    "provenance": {"text_policy": "verbatim-condensed"},
    "checkpoint": {"confirmed": True, "extraction_confirmed": True, "emphasis_source": "user"},
}


def _pptx_shape_boxes(slide):
    """从 slide XML 里只取**事件卡片**的 (x, y, w, h)（单位 px）。

    卡片是 `export_pptx._geom_rect` 在 `rx>0.5` 时选的 `roundRect` 预设几何；
    画布背景、坐标轴连线、编号圆点、标题文本框用的是别的预设（`rect`/直线连接符/
    `ellipse`/文本框），它们本来就该与卡片或彼此"相交"（圆点坐在轴线上、标题横跨
    整个画布宽度），拿全部形状做两两相交检查会把这些设计内的重叠也当成 bug。"""
    boxes = []
    for sp in re.findall(r"<p:sp>.*?</p:sp>", slide, re.S):
        if 'prst="roundRect"' not in sp:
            continue
        m = re.search(r'<a:off x="(-?\d+)" y="(-?\d+)"/><a:ext cx="(\d+)" cy="(\d+)"/>', sp)
        if not m:
            continue
        x, y, cx, cy = (int(v) / EMU_PER_PX for v in m.groups())
        boxes.append((x, y, cx, cy))
    return boxes


def _pptx_page_size(pptx_path):
    """<p:sldSz> 里的整页尺寸（像素），来自 ppt/presentation.xml。"""
    with zipfile.ZipFile(pptx_path) as z:
        pres = z.read("ppt/presentation.xml").decode("utf-8")
    m = re.search(r'<p:sldSz cx="(\d+)" cy="(\d+)"', pres)
    return (int(m.group(1)) / EMU_PER_PX, int(m.group(2)) / EMU_PER_PX)


def _pptx_title_box(slide, title_text):
    """标题所在 `<p:sp>` 的 (x, y, w, h)（像素）——按标题文本内容匹配那个形状。

    dev-board#888 [AWD-PATCH 6] 回归用：修复前标题会被 `attach_text()` 错收进
    画布背景矩形，这里按*文本内容*而不是按"第一个形状"去找标题，因为背景矩形
    吞并标题之后，出现在 slide 里的第一个带文字的 `<p:sp>` 就是那个被撑大的
    背景本身——按位置/顺序找是找不出这个 bug 的，必须按内容找。"""
    for sp in re.findall(r"<p:sp>.*?</p:sp>", slide, re.S):
        texts = "".join(re.findall(r'<a:t>(.*?)</a:t>', sp))
        if title_text[:6] in texts:
            m = re.search(r'<a:off x="(-?\d+)" y="(-?\d+)"/><a:ext cx="(\d+)" cy="(\d+)"/>', sp)
            if m:
                x, y, cx, cy = (int(v) / EMU_PER_PX for v in m.groups())
                return (x, y, cx, cy)
    return None


def _overlaps(a, b):
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    return ax < bx + bw and bx < ax + aw and ay < by + bh and by < ay + ah


class TimelinePptxOverlapTest(unittest.TestCase):
    def _render_pptx(self, semantic_map):
        with tempfile.TemporaryDirectory() as d:
            svg_path = os.path.join(d, "diagram.svg")
            kind, form, why, (w, h) = render_figure.deliver(semantic_map, svg_path)
            pptx_path = os.path.join(d, "diagram.pptx")
            export_pptx.export(svg_path, pptx_path)
            with zipfile.ZipFile(pptx_path) as z:
                slide = z.read("ppt/slides/slide1.xml").decode("utf-8")
            boxes = _pptx_shape_boxes(slide)
            page_w, page_h = _pptx_page_size(pptx_path)
            title_box = _pptx_title_box(slide, semantic_map["title_text"])
            return kind, form, why, boxes, slide, title_box, page_w, page_h

    def test_six_event_three_lane_case_has_no_overlapping_shapes(self):
        """dev-board#888 的真机夹具：修复前这里会红（E-01/E-02 两个 <p:sp> 相交）。"""
        m = copy.deepcopy(BASE_MAP)
        kind, form, why, boxes, slide, title_box, page_w, page_h = self._render_pptx(m)

        clashes = [(i, j) for i in range(len(boxes)) for j in range(i + 1, len(boxes))
                   if _overlaps(boxes[i], boxes[j])]
        self.assertEqual(clashes, [],
                          "PPTX 里存在互相重叠的形状：%r（形态=%s/%s，理由=%s）"
                          % (clashes, kind, form, why[:200]))

        # E-01 的总价必须完整出现在某个 <a:t> 文本节点里，不能被截断成 "18,64..."。
        amounts = re.findall(r'<a:t>([^<]*)</a:t>', slide)
        joined = "".join(amounts)
        self.assertIn("18,640.00", joined)
        self.assertIn("2,300.00", joined)

        # [AWD-PATCH 6] 回归：标题不许被 attach_text() 错收进画布背景矩形。
        # 修复前 title_box 是背景矩形本身，高度能到页高的 90%+ 并横压第三张卡；
        # 修好之后标题应该是一个贴顶部的小文本框。
        self.assertIsNotNone(title_box, "PPTX 里找不到标题文本所在的形状")
        _tx, _ty, _tw, _th = title_box
        self.assertLessEqual(_th, page_h * 0.15,
                              "标题形状高 %.0fpx，超过页高 %.0fpx 的 15%%——"
                              "很可能又被画布背景矩形吞并了" % (_th, page_h))
        title_clashes = [i for i, box in enumerate(boxes) if _overlaps(title_box, box)]
        self.assertEqual(title_clashes, [],
                          "标题形状与事件卡片矩形相交：%r（标题=%r）"
                          % (title_clashes, title_box))

    def test_two_lane_case_is_unaffected_by_the_guard(self):
        """回归防呆：只有两条泳道（不触发 lane_band 覆盖）时不该被新加的检查误伤。"""
        m = copy.deepcopy(BASE_MAP)
        m["lanes"] = [
            {"id": "P", "label_text": "申请方"},
            {"id": "D", "label_text": "回应方"},
        ]
        for ev in m["events"]:
            if ev["lane"] == "Q":
                ev["lane"] = "P"
        kind, form, why, boxes, slide, title_box, page_w, page_h = self._render_pptx(m)
        clashes = [(i, j) for i in range(len(boxes)) for j in range(i + 1, len(boxes))
                   if _overlaps(boxes[i], boxes[j])]
        self.assertEqual(clashes, [], "两泳道场景不该出现重叠：%r" % (clashes,))

    def test_long_body_variant_still_has_no_overlapping_shapes(self):
        """事项文字长短不均时（一项约 200 字）仍不许因为换行而互相压住。"""
        m = copy.deepcopy(BASE_MAP)
        long_body = (
            "申请方提出其中 2 组样品存在外观差异，具体表现为釉面色差、边缘细微磕碰"
            "以及包装内衬受潮痕迹，并附上编号照片与现场记录作为佐证；回应方要求按"
            "双方此前约定的复核流程处理，先由北岸质检室出具书面复核意见，再由双方"
            "共同确认是否构成质量问题，同时保留对复核结论提出异议的权利，避免争议"
            "扩大化处理，双方并约定在收到复核意见后五个工作日内以书面方式相互确认，"
            "逾期未确认的视为对复核结论无异议，以此作为后续结算尾款的依据之一。"
        )
        self.assertGreaterEqual(len(long_body), 200)
        m["events"][3]["head"] = long_body
        m["events"][3]["head_short"] = long_body

        kind, form, why, boxes, slide, title_box, page_w, page_h = self._render_pptx(m)
        clashes = [(i, j) for i in range(len(boxes)) for j in range(i + 1, len(boxes))
                   if _overlaps(boxes[i], boxes[j])]
        self.assertEqual(clashes, [],
                          "长文本样本里出现重叠：%r（形态=%s/%s，理由=%s）"
                          % (clashes, kind, form, why[:200]))
        if title_box is not None:
            title_clashes = [i for i, box in enumerate(boxes) if _overlaps(title_box, box)]
            self.assertEqual(title_clashes, [],
                              "长文本样本里标题与卡片相交：%r" % (title_clashes,))


if __name__ == "__main__":
    unittest.main()
