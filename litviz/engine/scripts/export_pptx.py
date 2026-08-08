#!/usr/bin/env python3
"""Editable .pptx export — every element is a NATIVE PowerPoint object.

Why transcribe the master SVG instead of re-deriving the layout:
  the SVG is this skill's canonical artefact. Transcribing it means the deck a
  lawyer opens is the SAME figure that was delivered — geometry, mode theming
  (奇川风 / 歸藏风 / 白描) and the arrow junction all come across for free, and
  all seven layouts are covered by one code path. Re-deriving would create a
  second layout engine that silently drifts from the first.

What "editable" means here (the point of the whole feature):
  * boxes / bands / hexagons / circles are real shapes with real fills, line
    colours and corner radii — recolour or resize them in PowerPoint;
  * text lives INSIDE its shape, so double-click and type;
  * straight runs are native PowerPoint connectors; bent routes are traced
    point-for-point as editable freeform lines. Both carry a native arrowhead.
    NOTE: connectors are placed by geometry, NOT bound to a shape's connection
    sites — moving a box does not currently re-route its line. Reproducing the
    router's path exactly was chosen over auto-follow, because a line that
    follows but leaves the node from the wrong side is worse than one that
    stays put and is right.

Zero third-party dependencies: a .pptx is a ZIP of XML, written here with the
standard library only (same discipline as export_drawio.py).
"""
import math
import re
import zipfile
from xml.sax.saxutils import escape

EMU_PER_PX = 9525            # 96 px/inch, 914400 EMU/inch
PT_PER_PX = 0.75             # 96 px/inch vs 72 pt/inch — the SVG is in PX, OOXML
                             # font sizes are in POINTS. Conflating them renders
                             # every string 33% oversized, which is what burst the
                             # boxes in the first cut of this exporter.
# Font profiles. OOXML names ONE typeface per run — there is no CSS-style
# fallback chain — so whatever is named here either exists on the reader's
# machine or gets silently substituted by PowerPoint, changing the metrics and
# with them the layout.
#
# The default is therefore SAFE, not faithful. A court exhibit that renders
# correctly only on the machine that made it is a defect: the failure mode to
# design against is "it looked right on my laptop and wrong on the judge's".
# Every face below ships with Windows itself (not merely with Office), so the
# deck is reproducible on any Chinese legal desktop.
#
# Anyone who has installed the master faces can ask for them explicitly and get
# a deck that matches the SVG exactly.
FONT_PROFILES = {
    "safe": {"serif": "宋体", "sans": "微软雅黑", "mono": "Consolas"},
    "master": {"serif": "思源宋体", "sans": "Noto Sans SC", "mono": "IBM Plex Mono"},
}
_FONTS = dict(FONT_PROFILES["master"])
_SVG_SRC = [""]                       # the source SVG, for resolving pattern fills


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


def _emu(v):
    return int(round(v * EMU_PER_PX))


# ---------------------------------------------------------------- SVG parse
_ATTR = re.compile(r'(\w[\w-]*)\s*=\s*"([^"]*)"')


def _attrs(tag):
    return dict(_ATTR.findall(tag))


def _f(d, k, default=0.0):
    try:
        return float(d.get(k, default))
    except (TypeError, ValueError):
        return default


def _clean(colour, default=None):
    """SVG paint -> 6-digit hex, or None for 'none'/absent."""
    if not colour or colour.lower() == "none":
        return default
    c = colour.strip().lstrip("#")
    return c.upper() if re.fullmatch(r"[0-9A-Fa-f]{6}", c) else default


def _points(d):
    """Absolute point list from the simple orthogonal paths the renderers emit
    (M / L / Q only — Q appears from the r≈2.5 corner rounding and is reduced to
    its endpoint, which is exactly the corner the route intended)."""
    pts, cur = [], None
    for m in re.finditer(r"([MLQmlq])\s*([-\d.,\s]+)", d):
        cmd, nums = m.group(1), [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", m.group(2))]
        if cmd in "Mm" and len(nums) >= 2:
            cur = (nums[0], nums[1]); pts.append(cur)
        elif cmd in "Ll":
            for i in range(0, len(nums) - 1, 2):
                cur = (nums[i], nums[i + 1]); pts.append(cur)
        elif cmd in "Qq" and len(nums) >= 4:
            cur = (nums[2], nums[3]); pts.append(cur)
    return pts


def _pattern_fill(svg, url):
    """Approximate an SVG <pattern> with a native PowerPoint pattern fill.

    歸藏风 lays a faint IKB dot-matrix over the paper. PowerPoint has no
    user-defined pattern tiles, but it does have preset ones, and a preset kept
    at the same colour and weight reads as the same texture — and stays a real,
    editable fill the user can restyle or switch off."""
    m = re.search(r'<pattern id="' + re.escape(url) + r'".*?</pattern>', svg, re.S)
    if not m:
        return None
    body = m.group(0)
    col = _clean(re.search(r'fill="([^"]*)"', body).group(1)) if 'fill="' in body else None
    if not col:
        return None
    op = re.search(r'opacity="([\d.]+)"', body)
    alpha = int(float(op.group(1)) * 100000) if op else 100000
    paper = _clean(re.search(r'<rect[^>]*fill="(#[0-9A-Fa-f]{6})"', svg).group(1)) or "FFFFFF"
    return (f'<a:pattFill prst="pct5"><a:fgClr><a:srgbClr val="{col}">'
            f'<a:alpha val="{max(4000, min(100000, alpha))}"/></a:srgbClr></a:fgClr>'
            f'<a:bgClr><a:srgbClr val="{paper}"/></a:bgClr></a:pattFill>')


def parse_svg(svg):
    """Master SVG -> flat primitive list. Order is preserved, so the deck's
    z-order matches the figure's.

    Group transforms ARE honoured. The renderers wrap content in
    `<g transform="translate(0,dy)">` twice — 歸藏风 reserves a 天头 above the big
    title, and `fit_title` pushes the drawing down when a long title wraps.
    Ignoring those silently lifted every element by that offset, which put the
    歸藏风 title above the top edge of the slide."""
    # Strip EVERY <defs> block instead of taking whatever follows the last one.
    # 歸藏风 emits two defs (the arrowhead, then the dot-matrix pattern) with the
    # PAPER background rect sitting between them — "everything after the last
    # </defs>" silently threw that rect away, so the deck came out with no
    # background at all.
    body = re.sub(r"<defs>.*?</defs>", "", svg, flags=re.S)
    prims = []
    stack = [(0.0, 0.0)]                       # accumulated translate
    token = re.compile(r"<(g|/g|rect|circle|line|path|text)\b([^>]*?)(/?)>")
    for m in token.finditer(body):
        kind, raw = m.group(1), m.group(2)
        if kind == "g":
            dx, dy = 0.0, 0.0
            tm = re.search(r"translate\(\s*(-?[\d.]+)[ ,]+(-?[\d.]+)\s*\)", raw)
            if tm:
                dx, dy = float(tm.group(1)), float(tm.group(2))
            stack.append((stack[-1][0] + dx, stack[-1][1] + dy))
            continue
        if kind == "/g":
            if len(stack) > 1:
                stack.pop()
            continue
        ox, oy = stack[-1]
        a = _attrs(raw)
        if kind == "text":
            end = body.find("</text>", m.end())
            txt = body[m.end():end] if end > 0 else ""
            txt = re.sub(r"<[^>]+>", "", txt)
            txt = (txt.replace("&lt;", "<").replace("&gt;", ">")
                      .replace("&quot;", '"').replace("&#x27;", "'").replace("&amp;", "&"))
            if not txt.strip():
                continue
            prims.append({"k": "text", "x": _f(a, "x") + ox, "y": _f(a, "y") + oy, "t": txt,
                          "fs": _f(a, "font-size", 13),
                          "bold": a.get("font-weight", "400") in ("600", "700", "bold"),
                          "fill": _clean(a.get("fill"), "000000"),
                          "anchor": a.get("text-anchor", "start"),
                          "track": _f(a, "letter-spacing", 0.0),
                          "family": a.get("font-family", "")})
        elif kind == "rect":
            w, h = _f(a, "width"), _f(a, "height")
            if w <= 0 or h <= 0:
                continue
            raw_fill = a.get("fill", "")
            pat = re.match(r"url\(#([\w-]+)\)", raw_fill or "")
            prims.append({"k": "rect", "x": _f(a, "x") + ox, "y": _f(a, "y") + oy, "w": w, "h": h,
                          "rx": _f(a, "rx"), "fill": _clean(raw_fill),
                          "pattern": pat.group(1) if pat else None,
                          "stroke": _clean(a.get("stroke")), "sw": _f(a, "stroke-width", 1)})
        elif kind == "circle":
            r = _f(a, "r")
            prims.append({"k": "ellipse", "x": _f(a, "cx") - r + ox, "y": _f(a, "cy") - r + oy,
                          "w": 2 * r, "h": 2 * r, "fill": _clean(a.get("fill")),
                          "stroke": _clean(a.get("stroke")), "sw": _f(a, "stroke-width", 1)})
        elif kind == "line":
            prims.append({"k": "conn",
                          "pts": [(_f(a, "x1") + ox, _f(a, "y1") + oy),
                                  (_f(a, "x2") + ox, _f(a, "y2") + oy)],
                          "stroke": _clean(a.get("stroke"), "000000"), "sw": _f(a, "stroke-width", 1),
                          "arrow": False, "dash": bool(a.get("stroke-dasharray"))})
        else:                                   # path
            pts = [(x + ox, y + oy) for x, y in _points(a.get("d", ""))]
            if len(pts) < 2:
                continue
            closed = "Z" in a.get("d", "").upper()
            if closed or _clean(a.get("fill")):
                xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
                prims.append({"k": "poly", "pts": pts, "x": min(xs), "y": min(ys),
                              "w": max(xs) - min(xs), "h": max(ys) - min(ys),
                              "fill": _clean(a.get("fill")), "stroke": _clean(a.get("stroke")),
                              "sw": _f(a, "stroke-width", 1)})
            else:
                prims.append({"k": "conn", "pts": pts, "stroke": _clean(a.get("stroke"), "000000"),
                              "sw": _f(a, "stroke-width", 1), "arrow": "marker-end" in raw,
                              "dash": bool(a.get("stroke-dasharray"))})
    return prims


def attach_text(prims, W=None, H=None):
    """Fold each <text> into the shape that contains it, so the deck has ONE
    object per box (double-click to edit) instead of a label floating on top of
    a rectangle that moves independently.

    The canvas BACKGROUND rect is never a host. It contains everything
    geometrically, so without this it would swallow the title and every free
    label — and the lawyer clicking the title would select the whole backdrop
    instead of the words. Those stay independent text boxes."""
    shapes = [p for p in prims if p["k"] in ("rect", "ellipse", "poly")]
    if W and H:
        canvas = W * H
        shapes = [s for s in shapes if s["w"] * s["h"] < 0.9 * canvas]
    for p in prims:
        if p["k"] != "text":
            continue
        host, best = None, None
        for s in shapes:
            if not (s["x"] - 1 <= p["x"] <= s["x"] + s["w"] + 1):
                continue
            if not (s["y"] <= p["y"] <= s["y"] + s["h"]):
                continue
            # A shape may only ADOPT text that is its own centred caption. The
            # time-band contains a dozen year labels at a dozen different x
            # positions; adopting those stacked them into one vertical list in
            # the middle of the band. Off-centre text keeps its own exact box.
            if p["anchor"] != "middle" or abs(p["x"] - (s["x"] + s["w"] / 2)) > 1.5:
                continue
            area = s["w"] * s["h"]
            if best is None or area < best:      # innermost wins
                host, best = s, area
        if host is not None:
            host.setdefault("lines", []).append(p)
            p["used"] = True
    return prims


# ------------------------------------------------------------------- OOXML
def _solid(hexcol):
    return f'<a:solidFill><a:srgbClr val="{hexcol}"/></a:solidFill>'


def _run(t):
    """One run. `sz` is in HUNDREDTHS OF A POINT while the SVG measures in px —
    conflating the two is what rendered every string 33% oversized and burst the
    boxes in the first cut of this exporter."""
    fam = (f'<a:latin typeface="{t["face"]}"/><a:ea typeface="{t["face"]}"/>'
           f'<a:cs typeface="{t["face"]}"/>')
    sz = max(100, int(round(t["fs"] * PT_PER_PX * 100)))
    # tracking: SVG letter-spacing is in px, OOXML `spc` in 1/100 pt. 歸藏风 puts
    # real tracking on its Latin/numeral runs — dropping it here would flatten
    # the engineered texture that is half of what defines the mode.
    spc = int(round(float(t.get("track", 0) or 0) * PT_PER_PX * 100))
    spc_attr = f' spc="{spc}"' if spc else ""
    return (f'<a:r><a:rPr lang="zh-CN" altLang="en-US" sz="{sz}"{spc_attr} '
            f'b="{1 if t["bold"] else 0}" dirty="0">{_solid(t["fill"])}{fam}</a:rPr>'
            f'<a:t>{escape(t["t"])}</a:t></a:r>')


def _face(p):
    """Pick a face from the SVG's font stack.

    "sans-serif" CONTAINS "serif" — testing for the latter first mapped every
    body stack ('PingFang SC', …, sans-serif) and 歸藏风's sans title onto the
    Song face. Sans is therefore tested FIRST."""
    low = p.get("family", "").lower()
    if "mono" in low:
        return _FONTS["mono"]
    if "sans" in low:
        return _FONTS["sans"]
    if "serif" in low or "宋" in p.get("family", ""):
        return _FONTS["serif"]
    return _FONTS["sans"]


def _lnspc(lines):
    """Line spacing taken from the MASTER's own baselines, in points.

    The scripts already decided where every line sits. Letting PowerPoint apply
    its default leading instead re-flows the block and pushes it out of the box —
    so the measured baseline delta is written back explicitly."""
    ys = [l["y"] for l in lines]
    if len(ys) < 2:
        gap = max(l["fs"] for l in lines) * 1.3
    else:
        gaps = [b - a for a, b in zip(ys, ys[1:]) if b - a > 0.5]
        gap = min(gaps) if gaps else max(l["fs"] for l in lines) * 1.3
    return f'<a:lnSpc><a:spcPts val="{max(100, int(round(gap * PT_PER_PX * 100)))}"/></a:lnSpc>'


def _txbody(lines, align="ctr", anchor="ctr"):
    """A text body that RENDERS what the master decided and nothing else.

    `wrap="none"` is the whole point: `common.wrap()` already broke this text to
    the CJK 禁则 rules and the box was sized around that result. If PowerPoint is
    allowed to re-wrap, it re-breaks the lines against its own font metrics and
    its own inset — which is what produced the burst boxes, the two-line title
    and the label sitting on top of the connector. Insets are zeroed for the same
    reason: the geometry is already final."""
    if not lines:
        return '<p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody>'
    spc = _lnspc(lines)
    paras = "".join(
        f'<a:p><a:pPr algn="{align}">{spc}</a:pPr>{_run({**l, "face": _face(l)})}</a:p>'
        for l in lines)
    return ('<p:txBody><a:bodyPr wrap="none" lIns="0" tIns="0" rIns="0" bIns="0" '
            f'anchor="{anchor}"><a:noAutofit/></a:bodyPr>'
            f'<a:lstStyle/>{paras}</p:txBody>')


def _paint(fill):
    """`fill` is either a 6-digit hex, a ready-made fill element (pattern), or
    None. Returns the fill element to drop into <p:spPr>."""
    if not fill:
        return "<a:noFill/>"
    if fill.startswith("<"):        # already an element, e.g. <a:pattFill .../>
        return fill
    return _solid(fill)


def _sp(sid, name, x, y, w, h, geom, fill, line, lines=()):
    ln = ('<a:ln w="%d">%s</a:ln>' % (_emu(line[1]), _solid(line[0]))) if line else '<a:ln><a:noFill/></a:ln>'
    return (f'<p:sp><p:nvSpPr><p:cNvPr id="{sid}" name="{name}"/><p:cNvSpPr/>'
            f'<p:nvPr/></p:nvSpPr><p:spPr>'
            f'<a:xfrm><a:off x="{_emu(x)}" y="{_emu(y)}"/><a:ext cx="{max(1, _emu(w))}" cy="{max(1, _emu(h))}"/></a:xfrm>'
            f'{geom}{_paint(fill)}{ln}</p:spPr>'
            f'{_txbody(list(lines))}</p:sp>')


# Every adjust handle a preset declares. Writing <a:avLst> REPLACES the preset's
# defaults wholesale, so supplying only some of them leaves the rest undefined —
# the geometry formula then references a missing guide and PowerPoint declares
# the file corrupt and refuses to open it. (LibreOffice, python-pptx and the XSD
# all accept it happily, which is how it shipped.) Supply all, or supply none.
PRESET_ADJUSTS = {
    "rect": (),
    "ellipse": (),
    "diamond": (),
    "roundRect": ("adj",),
    "straightConnector1": (),
    "bentConnector3": (),
    "hexagon": ("adj", "vf"),
}
PRESET_ADJ_DEFAULTS = {"vf": 115470}


def _avlst(prst, **vals):
    names = PRESET_ADJUSTS[prst]
    if not names:
        return "<a:avLst/>"
    gds = "".join(
        f'<a:gd name="{n}" fmla="val {int(vals.get(n, PRESET_ADJ_DEFAULTS.get(n, 0)))}"/>'
        for n in names)
    return f"<a:avLst>{gds}</a:avLst>"


def _geom_rect(rx, w, h):
    if rx <= 0.5:
        return f'<a:prstGeom prst="rect">{_avlst("rect")}</a:prstGeom>'
    adj = int(min(50000, max(0, rx / (min(w, h) / 2.0) * 50000)))
    return f'<a:prstGeom prst="roundRect">{_avlst("roundRect", adj=adj)}</a:prstGeom>' 


def _geom_poly(p):
    """Prefer a NAMED PowerPoint shape over a traced outline.

    Text inside a `custGeom` is not reliably rendered — the decision node came
    out as an empty hexagon with its question missing entirely. A preset also
    gives the lawyer a shape they recognise and can swap from the shape gallery.
    The traced outline is kept only for anything that is not recognisable, and
    `poly_holds_text()` tells the caller when the text has to sit in its own box
    instead."""
    w, h = max(p["w"], 1e-6), max(p["h"], 1e-6)
    nrm = [((a - p["x"]) / w, (b - p["y"]) / h) for a, b in p["pts"]]

    def near(pt, u, v, tol=0.06):
        return abs(pt[0] - u) < tol and abs(pt[1] - v) < tol

    # diamond — vertices at the four edge midpoints (歸藏风 decision node)
    if len(nrm) == 4 and all(any(near(q, *c) for q in nrm)
                             for c in ((0.5, 0), (1, 0.5), (0.5, 1), (0, 0.5))):
        return f'<a:prstGeom prst="diamond">{_avlst("diamond")}</a:prstGeom>', True

    # hexagon — a point at each side's mid-height plus flat top and bottom
    # (奇川风 decision node, whose corners carry the r≈2.5 rounding)
    left = [q for q in nrm if q[0] < 0.02]
    right = [q for q in nrm if q[0] > 0.98]
    if left and right and all(abs(q[1] - 0.5) < 0.08 for q in left + right):
        inset = min(q[0] for q in nrm if q[1] < 0.05) if any(q[1] < 0.05 for q in nrm) else 0.25
        adj = int(max(0, min(50000, inset * w / min(w, h) * 100000)))
        return f'<a:prstGeom prst="hexagon">{_avlst("hexagon", adj=adj)}</a:prstGeom>', True

    def X(v): return int(round((v - p["x"]) / w * 100000))
    def Y(v): return int(round((v - p["y"]) / h * 100000))
    pts = p["pts"]
    d = f'<a:moveTo><a:pt x="{X(pts[0][0])}" y="{Y(pts[0][1])}"/></a:moveTo>'
    d += "".join(f'<a:lnTo><a:pt x="{X(a)}" y="{Y(b)}"/></a:lnTo>' for a, b in pts[1:])
    return ('<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/>'
            '<a:rect l="0" t="0" r="100000" b="100000"/>'
            f'<a:pathLst><a:path w="100000" h="100000">{d}<a:close/></a:path></a:pathLst>'
            '</a:custGeom>'), False


def _connector(sid, p):
    """Reproduce the route EXACTLY.

    A straight run stays a native `straightConnector1`. A bent route does NOT:
    PowerPoint's `bentConnector3` has one fixed shape (horizontal → vertical →
    horizontal) and is positioned by the two ENDPOINTS alone, so feeding it a
    route the router built as vertical → horizontal → vertical made the line
    leave and enter its nodes from the wrong sides. Every bend the router
    computed was thrown away and re-guessed.

    Bent routes are therefore traced point-for-point as an open freeform, which
    is still a real, editable PowerPoint line (drag its points, restyle it) and
    carries a native arrowhead — but it is the router's path, not PowerPoint's
    idea of one. Same principle as everywhere else here: the scripts own the
    geometry, PowerPoint only draws it."""
    pts = p["pts"]
    head = '<a:tailEnd type="triangle" w="med" len="med"/>' if p.get("arrow") else ""
    dash = '<a:prstDash val="dash"/>' if p.get("dash") else ""
    ln = f'<a:ln w="{_emu(p["sw"])}" cap="flat">{_solid(p["stroke"])}{dash}{head}</a:ln>'

    if len(pts) == 2:
        (x0, y0), (x1, y1) = pts
        x, y = min(x0, x1), min(y0, y1)
        w, h = abs(x1 - x0), abs(y1 - y0)
        flips = (' flipH="1"' if x1 < x0 else "") + (' flipV="1"' if y1 < y0 else "")
        return (f'<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="{sid}" name="Connector {sid}"/>'
                f'<p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr><p:spPr>'
                f'<a:xfrm{flips}><a:off x="{_emu(x)}" y="{_emu(y)}"/>'
                f'<a:ext cx="{max(1, _emu(w))}" cy="{max(1, _emu(h))}"/></a:xfrm>'
                f'<a:prstGeom prst="straightConnector1"><a:avLst/></a:prstGeom>'
                f'{ln}</p:spPr></p:cxnSp>')

    xs = [q[0] for q in pts]; ys = [q[1] for q in pts]
    x, y = min(xs), min(ys)
    w, h = max(max(xs) - x, 1e-6), max(max(ys) - y, 1e-6)
    def X(v): return int(round((v - x) / w * 100000))
    def Y(v): return int(round((v - y) / h * 100000))
    d = f'<a:moveTo><a:pt x="{X(pts[0][0])}" y="{Y(pts[0][1])}"/></a:moveTo>'
    d += "".join(f'<a:lnTo><a:pt x="{X(a)}" y="{Y(b)}"/></a:lnTo>' for a, b in pts[1:])
    geom = ('<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/>'
            '<a:rect l="0" t="0" r="100000" b="100000"/>'
            f'<a:pathLst><a:path w="100000" h="100000" fill="none">{d}</a:path>'
            '</a:pathLst></a:custGeom>')
    return (f'<p:sp><p:nvSpPr><p:cNvPr id="{sid}" name="Route {sid}"/>'
            f'<p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>'
            f'<a:xfrm><a:off x="{_emu(x)}" y="{_emu(y)}"/>'
            f'<a:ext cx="{max(1, _emu(w))}" cy="{max(1, _emu(h))}"/></a:xfrm>'
            f'{geom}<a:noFill/>{ln}</p:spPr>{_txbody([])}</p:sp>')


def _text_w(s, fs, track=0.0):
    """Same metric the renderers wrap with (CJK ≈ 1 em, latin ≈ 0.56 em), plus
    any tracking, which 歸藏风 applies to Latin runs."""
    return sum(fs if ord(c) > 0x2E7F else fs * 0.56 for c in s) + track * max(0, len(s) - 1)


def _textbox(sid, p, W):
    """A free label — title, year, edge caption — placed from the MASTER's own
    anchor and baseline, not from anything PowerPoint works out for itself."""
    fs = p["fs"]
    w = _text_w(p["t"], fs, p.get("track", 0)) + fs * 0.8            # slack; wrap="none" anyway
    x = p["x"] - (w / 2 if p["anchor"] == "middle" else (w if p["anchor"] == "end" else 0))
    align = {"middle": "ctr", "end": "r"}.get(p["anchor"], "l")
    # SVG y is the BASELINE. The box is fitted to the GLYPHS (CJK ascent ≈ 0.88 em,
    # descent ≈ 0.17 em) rather than padded with invisible leading — a padded box
    # hangs off the canvas above a large title even though the letters are fine,
    # and makes "is it on the slide?" impossible to answer honestly.
    h = fs * 1.05
    y = p["y"] - fs * 0.88
    return (f'<p:sp><p:nvSpPr><p:cNvPr id="{sid}" name="Text {sid}"/>'
            f'<p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr>'
            f'<a:xfrm><a:off x="{_emu(x)}" y="{_emu(y)}"/>'
            f'<a:ext cx="{max(1, _emu(w))}" cy="{max(1, _emu(h))}"/></a:xfrm>'
            f'<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>'
            f'{_txbody([p], align, "ctr")}</p:sp>')


def build_shapes(prims, W, H):
    """Emit the deck.

    A module is ONE object: the box carries its own caption, all of it, however
    many lines. Splitting a four-line card into four floating text boxes is
    technically exact and practically hostile — a lawyer editing the card would
    have to chase four objects. PowerPoint is still not allowed to LAY OUT that
    caption: wrap is off, insets are zero, and the line spacing is the master's
    own measured baseline gap, so the lines land where the scripts put them.
    """
    out, sid = [], 2
    for p in prims:
        if p["k"] == "text":
            if not p.get("used"):
                out.append(_textbox(sid, p, W)); sid += 1
            continue
        if p["k"] == "conn":
            out.append(_connector(sid, p)); sid += 1
            continue
        lines = p.get("lines", [])
        line = (p["stroke"], p["sw"]) if p.get("stroke") else None
        paint = p.get("fill")
        if p.get("pattern"):
            paint = _pattern_fill(_SVG_SRC[0], p["pattern"]) or paint
        holds = True
        if p["k"] == "rect":
            geom = _geom_rect(p["rx"], p["w"], p["h"])
        elif p["k"] == "ellipse":
            geom = f'<a:prstGeom prst="ellipse">{_avlst("ellipse")}</a:prstGeom>'
        else:
            geom, holds = _geom_poly(p)
        out.append(_sp(sid, f'Shape {sid}', p["x"], p["y"], p["w"], p["h"],
                       geom, paint, line, lines if holds else ()))
        sid += 1
        if not holds:            # traced outline: its text would not render inside
            for t in lines:
                out.append(_textbox(sid, t, W)); sid += 1
    return out


# ------------------------------------------------------------- the package
_RELS = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
         '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
         '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/'
         'relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>')

_THEME = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
          '<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="mqc">'
          '<a:themeElements><a:clrScheme name="mqc">'
          '<a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>'
          '<a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>'
          '<a:dk2><a:srgbClr val="1F2933"/></a:dk2><a:lt2><a:srgbClr val="F3F4F6"/></a:lt2>'
          '<a:accent1><a:srgbClr val="991B1B"/></a:accent1><a:accent2><a:srgbClr val="6B7280"/></a:accent2>'
          '<a:accent3><a:srgbClr val="9CA3AF"/></a:accent3><a:accent4><a:srgbClr val="D1D5DB"/></a:accent4>'
          '<a:accent5><a:srgbClr val="374151"/></a:accent5><a:accent6><a:srgbClr val="002FA7"/></a:accent6>'
          '<a:hlink><a:srgbClr val="6B7280"/></a:hlink><a:folHlink><a:srgbClr val="9CA3AF"/></a:folHlink>'
          '</a:clrScheme>'
          '<a:fontScheme name="mqc"><a:majorFont><a:latin typeface="Arial"/>'
          '<a:ea typeface=""/><a:cs typeface=""/></a:majorFont>'
          '<a:minorFont><a:latin typeface="Arial"/><a:ea typeface=""/>'
          '<a:cs typeface=""/></a:minorFont></a:fontScheme>'
          '<a:fmtScheme name="mqc">'
          '<a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
          '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
          '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>'
          '<a:lnStyleLst>'
          '<a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>'
          '<a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>'
          '<a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>'
          '<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle>'
          '<a:effectStyle><a:effectLst/></a:effectStyle>'
          '<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>'
          '<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
          '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
          '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>'
          '</a:fmtScheme></a:themeElements></a:theme>')

_NS = ('xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
       'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
       'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"')


def _master():
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            f'<p:sldMaster {_NS}><p:cSld><p:spTree>'
            '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
            '<p:grpSpPr/></p:spTree></p:cSld>'
            '<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" '
            'accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" '
            'folHlink="folHlink"/>'
            '<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>'
            '</p:sldMaster>')


def _layout():
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            f'<p:sldLayout {_NS} type="blank" preserve="1"><p:cSld name="Blank"><p:spTree>'
            '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
            '<p:grpSpPr/></p:spTree></p:cSld><p:clrMapOvr><a:overrideClrMapping bg1="lt1" tx1="dk1" '
            'bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" '
            'accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>'
            '</p:clrMapOvr></p:sldLayout>')


def export(svg_path, out_path, fonts="master"):
    """Write the deck.

    `_FONTS` and `_SVG_SRC` are module-level because the font face and the
    pattern source are needed deep inside the run/shape builders, and threading
    them through every call would be noise. They are saved and restored around
    the run so a caller's profile cannot leak into the next export — the test
    suite renders with several profiles in one process, which is exactly the
    situation that would otherwise produce a deck in the wrong typeface with no
    error anywhere.
    """
    global _FONTS
    if fonts not in FONT_PROFILES:
        raise ValueError(f"unknown font profile {fonts!r}; use one of {sorted(FONT_PROFILES)}")
    prev_fonts, prev_src = _FONTS, _SVG_SRC[0]
    _FONTS = dict(FONT_PROFILES[fonts])
    svg = open(svg_path, encoding="utf-8").read()
    _SVG_SRC[0] = svg
    mw = re.search(r'<svg[^>]*width="([\d.]+)"[^>]*height="([\d.]+)"', svg)
    W, H = (float(mw.group(1)), float(mw.group(2))) if mw else (960.0, 720.0)
    prims = attach_text(parse_svg(svg), W, H)
    shapes = build_shapes(prims, W, H)

    slide = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             f'<p:sld {_NS}><p:cSld><p:spTree>'
             '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
             '<p:grpSpPr/>' + "".join(shapes) +
             '</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>')

    pres = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            f'<p:presentation {_NS}>'
            '<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>'
            '<p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst>'
            f'<p:sldSz cx="{_emu(W)}" cy="{_emu(H)}"/><p:notesSz cx="{_emu(H)}" cy="{_emu(W)}"/>'
            '</p:presentation>')

    ct = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
          '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
          '<Default Extension="xml" ContentType="application/xml"/>'
          '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-'
          'officedocument.presentationml.presentation.main+xml"/>'
          '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.'
          'openxmlformats-officedocument.presentationml.slideMaster+xml"/>'
          '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.'
          'openxmlformats-officedocument.presentationml.slideLayout+xml"/>'
          '<Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-'
          'officedocument.presentationml.slide+xml"/>'
          '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-'
          'officedocument.theme+xml"/></Types>')

    def rels(items):
        body = "".join(f'<Relationship Id="{i}" Type="http://schemas.openxmlformats.org/'
                       f'officeDocument/2006/relationships/{t}" Target="{tg}"/>' for i, t, tg in items)
        return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                f'{body}</Relationships>')

    parts = {
        "[Content_Types].xml": ct,
        "_rels/.rels": _RELS,
        "ppt/presentation.xml": pres,
        "ppt/_rels/presentation.xml.rels": rels([
            ("rId1", "slideMaster", "slideMasters/slideMaster1.xml"),
            ("rId2", "slide", "slides/slide1.xml"),
            ("rId3", "theme", "theme/theme1.xml")]),
        "ppt/slideMasters/slideMaster1.xml": _master(),
        "ppt/slideMasters/_rels/slideMaster1.xml.rels": rels([
            ("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"),
            ("rId2", "theme", "../theme/theme1.xml")]),
        "ppt/slideLayouts/slideLayout1.xml": _layout(),
        "ppt/slideLayouts/_rels/slideLayout1.xml.rels": rels([
            ("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")]),
        "ppt/slides/slide1.xml": slide,
        "ppt/slides/_rels/slide1.xml.rels": rels([
            ("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml")]),
        "ppt/theme/theme1.xml": _THEME,
    }
    try:
        with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
            for name, data in parts.items():
                _zip_write(z, name, data)
    finally:
        _FONTS, _SVG_SRC[0] = prev_fonts, prev_src
    return out_path, len(shapes)


if __name__ == "__main__":
    import sys
    p, n = export(sys.argv[1], sys.argv[2],
                  sys.argv[3] if len(sys.argv) > 3 else "master")
    print(f"pptx: {p}  ({n} editable objects)")


def audit_deck(pptx_path):
    """Geometric backstop for the thing a reviewer would otherwise have to SEE.

    The first cut of this exporter shipped burst boxes, a two-line title and a
    column of stacked year labels — and every automated check still passed,
    because they verified that text was PRESENT and that shapes were POSITIONED,
    never that the text would still FIT once PowerPoint rendered it. The defects
    all came from the DECK, not the master: font sizes written in px as if they
    were points (33% oversized) and PowerPoint re-wrapping text the scripts had
    already broken.

    So this audits the ARTEFACT: it reads back each run's real point size, turns
    it into px, measures it with the same metric the renderers wrap by, and
    checks it against the box it was actually given.

    Returns a list of human-readable problems; empty means clean."""
    with zipfile.ZipFile(pptx_path) as z:
        slide = z.read("ppt/slides/slide1.xml").decode("utf-8")
        pres = z.read("ppt/presentation.xml").decode("utf-8")
    sz = re.search(r'<p:sldSz cx="(\d+)" cy="(\d+)"', pres)
    W, H = int(sz.group(1)) / EMU_PER_PX, int(sz.group(2)) / EMU_PER_PX
    bad = []
    if 'wrap="none"' not in slide:
        bad.append("text bodies allow PowerPoint to re-wrap — the master already wrapped")
    if 'lIns="0"' not in slide:
        bad.append("text bodies keep PowerPoint's default insets — geometry is already final")

    for sp in re.findall(r"<p:sp>.*?</p:sp>", slide, re.S):
        off = re.search(r'<a:off x="(-?\d+)" y="(-?\d+)"/><a:ext cx="(\d+)" cy="(\d+)"/>', sp)
        if not off:
            continue
        x, y, cx, cy = [int(v) / EMU_PER_PX for v in off.groups()]
        runs = re.findall(r'sz="(\d+)"[^>]*>.*?<a:t>(.*?)</a:t>', sp, re.S)
        for pts, txt in runs:
            fs_px = int(pts) / 100.0 / PT_PER_PX
            tw = _text_w(txt, fs_px)
            if tw > cx + fs_px * 0.6:
                bad.append(f"{txt[:16]!r} needs {tw:.0f}px but its box is {cx:.0f}px "
                           f"(font {fs_px:.1f}px)")
            if fs_px > cy + 1:
                bad.append(f"{txt[:16]!r} is {fs_px:.1f}px tall in a {cy:.0f}px box")
            if x < -1 or y < -1 or x + cx > W + 1 or y + cy > H + 1:
                bad.append(f"{txt[:16]!r} sits off the slide")
    return bad
