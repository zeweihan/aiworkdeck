# SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""AWD-PATCH 6: a framed timeline sheet must not let its canvas-bg rect adopt the title.

paper.frame() wraps the content in a larger sheet, so the timeline's own canvas-bg
rect falls under export_pptx's 90%-of-canvas backdrop line and used to swallow the
centred title (the title's pptx shape became the whole content box).
"""
import json
import os
import re
import sys
import tempfile
import unittest
import zipfile

_HERE = os.path.dirname(os.path.abspath(__file__))
_SKILLS = os.path.join(os.path.dirname(_HERE), "skills")
_TL = os.path.join(_SKILLS, "mqc-timeline-master")
sys.path.insert(0, os.path.join(_TL, "scripts"))
sys.path.insert(0, os.path.join(_SKILLS, "mqc-litigation-visual-redraw", "scripts"))

import export_pptx  # noqa: E402
import paper  # noqa: E402
import render_vcolumns  # noqa: E402

_SHAPE = re.compile(r"<p:sp>.*?</p:sp>", re.S)
_XFRM = re.compile(r'<a:off x="(-?\d+)" y="(-?\d+)"/><a:ext cx="(\d+)" cy="(\d+)"/>')


class TimelinePptxTitleBackdropTest(unittest.TestCase):
    def test_framed_vertical_title_is_a_small_box_near_the_top(self):
        with open(os.path.join(_TL, "examples", "vertical-single-column.json"),
                  encoding="utf-8") as fh:
            m = json.load(fh)
        with tempfile.TemporaryDirectory() as tmp:
            svg_path = os.path.join(tmp, "tl.svg")
            render_vcolumns.render(m, svg_path)
            with open(svg_path, encoding="utf-8") as fh:
                svg = fh.read()
            svg = paper.frame(svg, landscape=False)
            with open(svg_path, "w", encoding="utf-8") as fh:
                fh.write(svg)
            W, H = map(float, re.search(r'<svg[^>]*width="([\d.]+)"[^>]*height="([\d.]+)"',
                                        svg).groups())
            pptx_path = os.path.join(tmp, "tl.pptx")
            export_pptx.export(svg_path, pptx_path)
            with zipfile.ZipFile(pptx_path) as z:
                xml = z.read("ppt/slides/slide1.xml").decode("utf-8")

        title = m["title_text"]
        boxes = []
        for sp in _SHAPE.findall(xml):
            xf = _XFRM.search(sp)
            if not xf:
                continue
            x, y, w, h = (int(v) / export_pptx.EMU_PER_PX for v in xf.groups())
            text = "".join(re.findall(r"<a:t>(.*?)</a:t>", sp))
            boxes.append((x, y, w, h, text))
            if w >= 0.85 * W and h >= 0.85 * H:
                self.assertEqual(text, "", f"background-sized shape {w:.0f}x{h:.0f} holds text")

        titled = [b for b in boxes if title[:6] in b[4]]
        self.assertEqual(len(titled), 1, f"title should be in exactly one shape: {titled}")
        x, y, w, h, _ = titled[0]
        self.assertLess(h, 0.25 * H, f"title box too tall: {x:.0f},{y:.0f} {w:.0f}x{h:.0f}")
        self.assertLess(y, 0.2 * H, f"title box not near the top: {x:.0f},{y:.0f} {w:.0f}x{h:.0f}")

    def test_shape_containing_everything_does_not_adopt_centred_text(self):
        # Canvas is much larger than the backdrop, so the 90%-area rule alone lets it through.
        prims = [
            {"k": "rect", "x": 0, "y": 0, "w": 400, "h": 300},
            {"k": "rect", "x": 150, "y": 100, "w": 100, "h": 40},
            {"k": "text", "x": 200, "y": 20, "anchor": "middle", "t": "title"},
            {"k": "conn", "pts": [(10, 200), (390, 200)]},
        ]
        export_pptx.attach_text(prims, 800, 600)
        self.assertNotIn("used", prims[2])
        self.assertNotIn("lines", prims[0])

    def test_inner_box_still_adopts_its_centred_caption(self):
        prims = [
            {"k": "rect", "x": 0, "y": 0, "w": 400, "h": 300},
            {"k": "rect", "x": 150, "y": 100, "w": 100, "h": 40},
            {"k": "rect", "x": 150, "y": 200, "w": 100, "h": 40},
            {"k": "text", "x": 200, "y": 125, "anchor": "middle", "t": "box"},
        ]
        export_pptx.attach_text(prims, 800, 600)
        self.assertTrue(prims[3].get("used"))
        self.assertEqual(prims[1].get("lines"), [prims[3]])


if __name__ == "__main__":
    unittest.main()
