#!/usr/bin/env python3
"""Editable .vsdx export — the figure, re-openable in ProcessOn / Visio / WPS.

Why this format, out of the ten ProcessOn accepts
-------------------------------------------------
Its import list is xmind · mmap · txt · km · mm · md · opml · pos · vsdx · csv.
Eight of those are outline or mind-map formats: they carry a hierarchy of words,
not placed shapes. Pushing a litigation figure through one would flatten precise
dates, orthogonal routes and arrow DIRECTION into a tree — losing exactly the
things that make the diagram a legal argument. That is worse than exporting
nothing.

That leaves `pos` and `vsdx`. `pos` is ProcessOn's own format and would round-trip
best inside ProcessOn, but it is undocumented and private: it would have to be
reverse-engineered, could change without notice, and cannot be checked here.
`vsdx` is a published standard, opens in ProcessOn *and* Visio, WPS and Edraw,
and — decisively — LibreOffice can read it back, so the output can be rendered
and measured rather than merely hoped over.

Like the .pptx exporter this transcribes the MASTER SVG, so the file a lawyer
edits is the figure that was delivered, and there is no second layout engine to
drift. Standard library only: a .vsdx is a ZIP of XML.

Coordinate system, which is where Visio differs from everything else here:
  * units are INCHES, not pixels (96 px = 1 in);
  * the origin is the BOTTOM-left corner and Y points UP, so every y is flipped;
  * a shape is placed by its CENTRE (PinX/PinY) plus Width/Height, not by a
    top-left corner.
"""
import os
import re
import sys
import zipfile
from xml.sax.saxutils import escape

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_pptx import parse_svg, attach_text, _text_w   # noqa: E402  same geometry source

NS = "http://schemas.microsoft.com/office/visio/2012/main"
RNS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
MSV = "http://schemas.microsoft.com/visio/2010/relationships"
PX_PER_IN = 96.0

SERIF = "思源宋体"
SANS = "Noto Sans SC"
MONO = "IBM Plex Mono"


# A fixed timestamp on every ZIP entry. Otherwise the same map produces a
# different FILE on every run — the parts are byte-identical, only the archive's
# clock differs — which breaks byte-comparison, the safety net this project leans
# on hardest. 1980-01-01 is the earliest a ZIP can represent.
ZIP_EPOCH = (1980, 1, 1, 0, 0, 0)


def _zip_write(z, name, data):
    info = zipfile.ZipInfo(name, date_time=ZIP_EPOCH)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o600 << 16
    z.writestr(info, data)


def _in(px):
    return round(px / PX_PER_IN, 5)


class Page:
    """Flips SVG's top-left/Y-down space into Visio's bottom-left/Y-up space."""

    def __init__(self, w_px, h_px):
        self.w, self.h = w_px, h_px

    def x(self, px):
        return _in(px)

    def y(self, px):
        return _in(self.h - px)


def _face(fam):
    low = (fam or "").lower()
    if "mono" in low:
        return MONO
    if "sans" in low:
        return SANS
    if "serif" in low or "宋" in (fam or ""):
        return SERIF
    return SANS


def _char(lines):
    """Character section — ONE ROW PER LINE.

    A card's title and its detail lines are different sizes. Emitting a single
    row applied the title's size to the whole block, so 13px sub-lines rendered
    at 17px and the card's text no longer matched the figure. Visio addresses
    character formatting by row index, and <Text> selects a row with <cp>."""
    if not lines:
        return ""
    rows = "".join(
        f'<Row IX="{i}"><Cell N="Font" V="{_face(l.get("family"))}"/>'
        f'<Cell N="Size" V="{_in(l["fs"])}"/>'
        f'<Cell N="Color" V="#{l["fill"]}"/>'
        f'<Cell N="Style" V="{1 if l.get("bold") else 0}"/></Row>'
        for i, l in enumerate(lines))
    return f'<Section N="Character">{rows}</Section>'


def _para():
    """Centre every line, the way the master centres its captions."""
    return ('<Section N="Paragraph"><Row IX="0">'
            '<Cell N="HorzAlign" V="1"/><Cell N="SpLine" V="-1.2"/></Row></Section>')


def _text(lines):
    if not lines:
        return ""
    body = "".join(f'<cp IX="{i}"/>{escape(l["t"])}' + ("\n" if i < len(lines) - 1 else "")
                   for i, l in enumerate(lines))
    return f"<Text>{body}</Text>"


def _fill_line(fill, stroke, sw):
    cells = ""
    if fill:
        cells += f'<Cell N="FillForegnd" V="#{fill}"/><Cell N="FillPattern" V="1"/>'
    else:
        cells += '<Cell N="FillPattern" V="0"/>'
    if stroke:
        cells += (f'<Cell N="LineColor" V="#{stroke}"/>'
                  f'<Cell N="LineWeight" V="{_in(sw)}"/><Cell N="LinePattern" V="1"/>')
    else:
        cells += '<Cell N="LinePattern" V="0"/>'
    return cells


def _shape(sid, name, cx, cy, w, h, cells, geom, text=""):
    w = max(w, 0.001)
    h = max(h, 0.001)
    return (f'<Shape ID="{sid}" NameU="{name}.{sid}" Type="Shape" '
            f'LineStyle="0" FillStyle="0" TextStyle="0">'
            f'<Cell N="PinX" V="{cx}"/><Cell N="PinY" V="{cy}"/>'
            f'<Cell N="Width" V="{w}"/><Cell N="Height" V="{h}"/>'
            f'<Cell N="LocPinX" F="Width*0.5" V="{w/2}"/>'
            f'<Cell N="LocPinY" F="Height*0.5" V="{h/2}"/>'
            f'{cells}{geom}{text}</Shape>')


def _rect_geom(rounded=False):
    rows = [("RelMoveTo", 0, 0), ("RelLineTo", 1, 0), ("RelLineTo", 1, 1),
            ("RelLineTo", 0, 1), ("RelLineTo", 0, 0)]
    body = "".join(f'<Row T="{t}" IX="{i+1}"><Cell N="X" V="{x}"/>'
                   f'<Cell N="Y" V="{y}"/></Row>' for i, (t, x, y) in enumerate(rows))
    return f'<Section N="Geometry" IX="0">{body}</Section>'


def _poly_geom(pts, x0, y0, w, h, page, close=True):
    """Absolute polyline expressed in the shape's own local inches."""
    rows = []
    for i, (px, py) in enumerate(pts):
        lx = _in(px - x0)
        ly = _in((y0 + h) - py)          # local Y also points up
        rows.append(f'<Row T="{"MoveTo" if i == 0 else "LineTo"}" IX="{i+1}">'
                    f'<Cell N="X" V="{lx}"/><Cell N="Y" V="{ly}"/></Row>')
    if close and pts:
        lx = _in(pts[0][0] - x0); ly = _in((y0 + h) - pts[0][1])
        rows.append(f'<Row T="LineTo" IX="{len(pts)+1}">'
                    f'<Cell N="X" V="{lx}"/><Cell N="Y" V="{ly}"/></Row>')
    return f'<Section N="Geometry" IX="0">{"".join(rows)}</Section>'


def _ellipse_geom(w, h):
    return (f'<Section N="Geometry" IX="0">'
            f'<Row T="Ellipse" IX="1">'
            f'<Cell N="X" V="{w/2}"/><Cell N="Y" V="{h/2}"/>'
            f'<Cell N="A" V="{w}"/><Cell N="B" V="{h/2}"/>'
            f'<Cell N="C" V="{w/2}"/><Cell N="D" V="{h}"/></Row></Section>')


def _size_groups(lines):
    """Split a caption into runs of UNIFORM character formatting.

    Visio's per-run formatting (`<cp IX=n/>`) is spec-correct and renders
    correctly for Latin — but measured here, LibreOffice's Visio importer
    collapses a CJK shape's text to ONE size, so a card's 13px sub-lines came out
    at the title's size. Whether ProcessOn does the same cannot be checked from
    here, and shipping something that can only be verified by asking the reader
    is not good enough. So a caption that mixes sizes is emitted as one text
    shape per size instead, which every tool renders the same way. A caption of
    uniform size — the common case — stays inside its module as ONE object.
    """
    groups, cur = [], []
    key = lambda l: (l["fs"], l.get("bold"), l["fill"], _face(l.get("family")))
    for l in lines:
        if cur and key(cur[0]) != key(l):
            groups.append(cur); cur = []
        cur.append(l)
    if cur:
        groups.append(cur)
    return groups


def _text_shape(sid, group, page):
    """A borderless shape holding one uniformly-formatted run, placed on the
    master's own baselines."""
    fs = group[0]["fs"]
    widest = max(_text_w(l["t"], fs, l.get("track", 0)) for l in group)
    w = widest + fs
    top = min(l["y"] for l in group) - fs
    bot = max(l["y"] for l in group) + fs * 0.4
    h = bot - top
    cx = group[0]["x"] if group[0]["anchor"] == "middle" else group[0]["x"] + w / 2
    return _shape(sid, "Caption", page.x(cx), page.y((top + bot) / 2), _in(w), _in(h),
                  '<Cell N="FillPattern" V="0"/><Cell N="LinePattern" V="0"/>',
                  _rect_geom(), _char(group[:1]) + _para() + _text(group))


def build_shapes(prims, page):
    out, sid = [], 1
    for p in prims:
        if p["k"] == "text":
            if p.get("used"):
                continue
            tw = _text_w(p["t"], p["fs"], p.get("track", 0)) + p["fs"]
            anchor = p["anchor"]
            cx_px = p["x"] + (0 if anchor == "middle" else
                              (tw / 2 if anchor == "start" else -tw / 2))
            cy_px = p["y"] - p["fs"] * 0.36
            out.append(_shape(sid, "Label", page.x(cx_px), page.y(cy_px),
                              _in(tw), _in(p["fs"] * 1.6),
                              '<Cell N="FillPattern" V="0"/><Cell N="LinePattern" V="0"/>',
                              _rect_geom(), _char([p]) + _para() + _text([p])))
            sid += 1
            continue

        if p["k"] == "conn":
            pts = p["pts"]
            xs = [q[0] for q in pts]; ys = [q[1] for q in pts]
            x0, y0 = min(xs), min(ys)
            w, h = max(max(xs) - x0, 1e-3), max(max(ys) - y0, 1e-3)
            cells = (f'<Cell N="LineColor" V="#{p["stroke"]}"/>'
                     f'<Cell N="LineWeight" V="{_in(p["sw"])}"/>'
                     # Visio LinePattern: 1 solid, 2 DASHED, 3 dotted, 4 dash-DOT.
                     # 4 draws dash·dot·dash. The master's dasharray is a plain
                     # "6 4", so 2 is the faithful one. This is the sort of error
                     # that survives every structural check: the file is valid and
                     # the line is there — only a reader sees it is the wrong KIND.
                     f'<Cell N="LinePattern" V="{2 if p.get("dash") else 1}"/>'
                     f'<Cell N="FillPattern" V="0"/>'
                     + ('<Cell N="EndArrow" V="4"/><Cell N="EndArrowSize" V="2"/>'
                        if p.get("arrow") else ""))
            out.append(_shape(sid, "Route", page.x(x0 + w / 2), page.y(y0 + h / 2),
                              _in(w), _in(h), cells,
                              _poly_geom(pts, x0, y0, w, h, page, close=False)))
            sid += 1
            continue

        lines = p.get("lines", [])
        groups = _size_groups(lines)
        inline = lines if len(groups) <= 1 else []      # uniform caption stays inside
        cells = _fill_line(p.get("fill"), p.get("stroke"), p.get("sw", 1))
        if p["k"] == "rect":
            if p.get("rx", 0) > 0.5:
                cells += f'<Cell N="Rounding" V="{_in(p["rx"])}"/>'
            geom = _rect_geom()
        elif p["k"] == "ellipse":
            geom = _ellipse_geom(_in(p["w"]), _in(p["h"]))
        else:
            geom = _poly_geom(p["pts"], p["x"], p["y"], p["w"], p["h"], page)
        body = (_char(inline) + _para() + _text(inline)) if inline else ""
        out.append(_shape(sid, "Module", page.x(p["x"] + p["w"] / 2),
                          page.y(p["y"] + p["h"] / 2), _in(p["w"]), _in(p["h"]),
                          cells, geom, body))
        sid += 1
        if not inline:
            for g in groups:
                out.append(_text_shape(sid, g, page)); sid += 1
    return out


def _rels(items):
    body = "".join(f'<Relationship Id="{i}" Type="{t}" Target="{tg}"/>' for i, t, tg in items)
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            f'{body}</Relationships>')


def export(svg_path, out_path):
    svg = open(svg_path, encoding="utf-8").read()
    m = re.search(r'<svg[^>]*width="([\d.]+)"[^>]*height="([\d.]+)"', svg)
    W, H = (float(m.group(1)), float(m.group(2))) if m else (960.0, 720.0)
    page = Page(W, H)
    prims = attach_text(parse_svg(svg), W, H)
    shapes = build_shapes(prims, page)

    page_xml = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                f'<PageContents xmlns="{NS}" xmlns:r="{RNS}" xml:space="preserve">'
                f'<Shapes>{"".join(shapes)}</Shapes></PageContents>')
    pages_xml = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                 f'<Pages xmlns="{NS}" xmlns:r="{RNS}" xml:space="preserve">'
                 f'<Page ID="0" NameU="Page-1" Name="Page-1" ViewScale="1" '
                 f'ViewCenterX="{_in(W)/2}" ViewCenterY="{_in(H)/2}">'
                 f'<PageSheet LineStyle="0" FillStyle="0" TextStyle="0">'
                 f'<Cell N="PageWidth" V="{_in(W)}"/><Cell N="PageHeight" V="{_in(H)}"/>'
                 f'<Cell N="DrawingScale" V="1"/><Cell N="PageScale" V="1"/>'
                 f'</PageSheet><Rel r:id="rId1"/></Page></Pages>')
    doc_xml = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               f'<VisioDocument xmlns="{NS}" xmlns:r="{RNS}" xml:space="preserve">'
               '<DocumentSettings TopPage="0" DefaultTextStyle="0" DefaultLineStyle="0" '
               'DefaultFillStyle="0" DefaultGuideStyle="0"/>'
               '<Colors/><FaceNames/><StyleSheets/></VisioDocument>')
    ct = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
          '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.'
          'relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>'
          '<Override PartName="/visio/document.xml" ContentType="application/vnd.ms-visio.'
          'drawing.main+xml"/>'
          '<Override PartName="/visio/pages/pages.xml" ContentType="application/vnd.ms-visio.'
          'pages+xml"/>'
          '<Override PartName="/visio/pages/page1.xml" ContentType="application/vnd.ms-visio.'
          'page+xml"/></Types>')

    parts = {
        "[Content_Types].xml": ct,
        "_rels/.rels": _rels([("rId1", MSV + "/document", "visio/document.xml")]),
        "visio/document.xml": doc_xml,
        "visio/_rels/document.xml.rels": _rels([("rId1", MSV + "/pages", "pages/pages.xml")]),
        "visio/pages/pages.xml": pages_xml,
        "visio/pages/_rels/pages.xml.rels": _rels([("rId1", MSV + "/page", "page1.xml")]),
        "visio/pages/page1.xml": page_xml,
    }
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        for n, d in parts.items():
            _zip_write(z, n, d)
    return out_path, len(shapes)


if __name__ == "__main__":
    p, n = export(sys.argv[1], sys.argv[2])
    print(f"vsdx: {p}  ({n} editable shapes)")
