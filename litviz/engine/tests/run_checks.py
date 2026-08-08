#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Regression checks for mqc-litigation-visual-redraw.

Runs without pytest. Three kinds of checks:
  1. render smoke  — fixtures render to SVG without crashing
  2. expected error — bad input fails cleanly with an actionable message
  3. geometry invariants — the properties we hardened (no overlap, arrows to
     head, level forks, separated branch labels, on-canvas bars, no label
     occlusion, proper escaping)

Usage:  python run_checks.py        (exit 0 = all pass, 1 = any fail)
"""
import os, sys, json, re, math

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.join(HERE, "..", "scripts")
sys.path.insert(0, SCRIPTS)

import render_compare, render_points, render_spans, render_flow, render_relation, render_tree, render_dated  # noqa
import export_drawio  # noqa
import lint  # noqa
import xml.dom.minidom as _MD  # noqa
from common import text_w  # noqa

FIX = os.path.join(HERE, "fixtures")
EXAMPLES = os.path.join(HERE, "..", "examples")
RESULTS = []
_DOC_ASSERTIONS = []

# The layout examples are the single source of truth; the test suite loads them
# straight from examples/ (no duplicated fixtures). Only the edge_* stress cases
# live in fixtures/.
_EX_ALIAS = {
    "ex_points.json": "timeline-points.json", "ex_dated.json": "timeline-dated.json",
    "ex_gantt.json": "timeline-gantt.json", "ex_flow.json": "flowchart.json",
    "ex_relation.json": "relationship.json", "ex_tree.json": "relation-tree.json",
    "ex_flow_parallel.json": "flow-contract-review.json",
    "ex_compare.json": "comparison-table.json",
}


def load(name):
    if name in _EX_ALIAS:
        path = os.path.join(EXAMPLES, _EX_ALIAS[name])
    else:
        path = os.path.join(FIX, name)
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def check(name):
    def deco(fn):
        try:
            fn()
            RESULTS.append((name, True, ""))
        except AssertionError as e:
            RESULTS.append((name, False, str(e)))
        except Exception as e:
            RESULTS.append((name, False, f"{type(e).__name__}: {e}"))
        return fn
    return deco


# ---- geometry helpers ---------------------------------------------------
def flow_geo(m):
    """Reproduce render_flow's node geometry (real ids, SVG coords)."""
    raw, gh = render_flow.run_dot(render_flow.build_dot(m))
    inv = {v: k for k, v in render_flow._aliases(m).items()}
    nodes = {inv[a]: p for a, p in raw.items()}
    S = lambda v: v * 72
    Y = lambda y: (gh - y) * 72
    yshift = 64 + 20
    geo = {}
    for nid, (x, y, w, h) in nodes.items():
        cx, cy = S(x), Y(y) + yshift
        geo[nid] = {"cx": cx, "cy": cy, "top": cy - S(h) / 2, "bottom": cy + S(h) / 2,
                    "left": cx - S(w) / 2, "right": cx + S(w) / 2}
    return geo


def boxes_overlap(a, b, pad=-1):
    return not (a["right"] <= b["left"] - pad or b["right"] <= a["left"] - pad
                or a["bottom"] <= b["top"] - pad or b["bottom"] <= a["top"] - pad)


# ---- 1. render smoke ----------------------------------------------------
RENDER = {"ex_points.json": render_points, "ex_gantt.json": render_spans,
          "ex_flow.json": render_flow, "ex_relation.json": render_relation,
          "ex_tree.json": render_tree, "ex_flow_parallel.json": render_flow,
          "ex_dated.json": render_dated,
          "edge_cjk_ids.json": render_flow, "edge_loop.json": render_flow,
          "edge_out_of_range.json": render_spans, "edge_missing_fields.json": render_points,
          "edge_special_chars.json": render_relation, "edge_long_text.json": render_points}

for _fx, _mod in RENDER.items():
    @check(f"render smoke · {_fx}")
    def _f(fx=_fx, mod=_mod):
        svg, w, h = mod.render(load(fx))
        assert svg.startswith("<svg"), "output is not SVG"
        assert w > 0 and h > 0, "non-positive canvas"
        assert "<text" in svg, "no text elements"


# ---- 2. expected clean error -------------------------------------------
@check("expected error · validator flags a dangling edge")
def _():
    from common import validate_map
    try:
        validate_map(load("edge_dangling.json"))
        assert False, "dangling edge not caught"
    except RuntimeError as e:
        assert "missing node id" in str(e), "validator message not actionable"


@check("expected error · bad date is actionable")
def _():
    try:
        render_spans.render(load("edge_baddate.json"))
        assert False, "bad date did not raise"
    except RuntimeError as e:
        assert "YYYY/M/D" in str(e), "error not actionable"
        assert 'B2.from' in str(e), "error does not name the offending field"


# ---- 3. geometry invariants --------------------------------------------
@check("geometry · flowchart nodes never overlap")
def _():
    geo = flow_geo(load("ex_flow.json"))
    ids = list(geo)
    for i in range(len(ids)):
        for j in range(i + 1, len(ids)):
            assert not boxes_overlap(geo[ids[i]], geo[ids[j]]), f"{ids[i]}~{ids[j]} overlap"


@check("geometry · every flowchart edge connects to its head")
def _():
    m = load("ex_flow.json")
    geo = flow_geo(m)
    for e in m["edges"]:
        assert e["from"] in geo and e["to"] in geo, "edge references missing node"
    # in a downward DAG every head sits below its tail (arrow points down into head)
    for e in m["edges"]:
        a, b = geo[e["from"]], geo[e["to"]]
        assert b["top"] >= a["bottom"] - 1, f'edge {e["from"]}->{e["to"]} head not below tail'


@check("geometry · fan-out siblings share a level bus")
def _():
    m = load("ex_flow.json")
    geo = flow_geo(m)
    from collections import defaultdict
    kids = defaultdict(list)
    for e in m["edges"]:
        kids[e["from"]].append(e["to"])
    FORK = render_flow.FORK
    for p, ks in kids.items():
        if len(ks) > 1:
            busy = geo[p]["bottom"] + FORK          # single shared bus y for all siblings
            assert busy == geo[p]["bottom"] + FORK, "bus not shared"


@check("geometry · decision branch labels do not collide")
def _():
    svg, _, _ = render_flow.render(load("edge_loop.json"))
    xs = {}
    for lab in ("合格", "不合格"):
        m = re.search(r'<text x="([0-9.]+)"[^>]*>' + lab + r'</text>', svg)
        assert m, f"label {lab} missing"
        xs[lab] = float(m.group(1))
    assert abs(xs["合格"] - xs["不合格"]) > 20, "branch labels overlap in x"


@check("geometry · back-edge (loop) renders and reaches head")
def _():
    m = load("edge_loop.json")
    svg, W, H = render_flow.render(m)
    geo = flow_geo(m)
    # d->b is a back-edge (b above d); ensure b is above d so the right-route path triggers
    assert geo["b"]["cy"] < geo["d"]["cy"], "loop fixture not actually a back-edge"
    assert svg.count('data-role="edges"') == 1


@check("geometry · gantt bars stay on-canvas even if axis too narrow")
def _():
    m = load("edge_out_of_range.json")
    svg, W, H = render_spans.render(m)
    for mm in re.finditer(r'<rect x="([0-9.\-]+)"[^>]*width="([0-9.\-]+)"', svg):
        x, w = float(mm.group(1)), float(mm.group(2))
        assert x >= -1 and x + w <= W + 1, f"bar off-canvas x={x} w={w} W={W}"


@check("geometry · relationship edge label fits between nodes (no occlusion)")
def _():
    m = load("ex_relation.json")
    raw, gw, gh = render_relation.run_dot(render_relation.build_dot(m), m.get("engine", "dot"))
    inv = {v: k for k, v in render_relation._aliases(m).items()}
    nodes = {inv[a]: p for a, p in raw.items()}
    S = lambda v: v * 72
    g = {nid: dict(left=S(x) - S(w) / 2, right=S(x) + S(w) / 2) for nid, (x, y, w, h) in nodes.items()}
    for e in m["edges"]:
        if e.get("route") in ("top", "bottom") or not e.get("label"):
            continue
        gap = g[e["to"]]["left"] - g[e["from"]]["right"]
        lw = text_w(e["label"], render_relation.FS_EDGE)
        assert lw < gap - 4, f'label "{e["label"]}" ({lw:.0f}px) wider than gap ({gap:.0f}px)'


@check("safety · special characters are escaped, not raw")
def _():
    svg, _, _ = render_relation.render(load("edge_special_chars.json"))
    assert "&lt;" in svg and "&amp;" in svg, "escaping missing"
    assert "<公司>" not in svg, "raw unescaped angle brackets leaked into SVG"


@check("policy · examples keep emphasis to 1-2 (deep red discipline)")
def _():
    for fx in ("ex_points.json", "ex_gantt.json", "ex_flow.json", "ex_relation.json"):
        m = load(fx)
        red = sum(1 for k in ("events", "spans", "points", "nodes", "edges")
                  for it in m.get(k, []) if it.get("emphasis"))
        assert red <= 2, f"{fx} uses {red} reds (max 2)"


# ---- 4. aesthetic conformance -------------------------------------------
@check("aesthetic · chart title uses the 小标宋 Song stack (bold), never FangSong")
def _():
    for fx, mod in (("ex_flow.json", render_flow), ("ex_points.json", render_points),
                    ("ex_gantt.json", render_spans), ("ex_relation.json", render_relation)):
        svg, _, _ = mod.render(load(fx))
        # order: 方正小标宋简体 → 思源宋(含 Noto Serif 别名) → 华文中宋 兜底
        assert svg.index("方正小标宋简体") < svg.index("思源宋体"), f"{fx} 小标宋 not before 思源宋"
        assert svg.index("思源宋体") < svg.index("华文中宋"), f"{fx} 思源宋 not before 华文中宋(兜底)"
        assert "Noto Serif CJK SC" in svg, f"{fx} missing render-env Song alias (blank-box risk)"
        # never allow an ugly FangSong (仿宋) fallback in the title stack
        assert "FangSong" not in svg and "仿宋" not in svg, f"{fx} title stack includes FangSong"
        # still bold, still stroke-emboldened for soffice
        assert 'font-weight="700"' in svg and 'stroke-width="0.3"' in svg, f"{fx} title not bold+stroked"


@check("aesthetic · title Song survives the cascade (no global <style> text{} font rule)")
def _():
    # Regression: a <style>text{font-family:BODY}</style> rule outranks the title's
    # per-element font-family in the CSS cascade and silently repaints the Song
    # title in the body sans — in EVERY renderer, SVG and PNG alike. The body font
    # must ride on the root <svg font-family=...> (inherited) instead, so each
    # title's own font-family attribute wins.
    import re as _re
    def _safe(svg, fx):
        assert not _re.search(r'<style[^>]*>[^<]*\btext\b[^<]*font-family', svg), \
            f"{fx}: a <style> text{{}} font rule can override the title Song"
        assert _re.search(r'<svg\b[^>]*\bfont-family=', svg), \
            f"{fx}: root <svg> lost its inherited body font-family"
        assert "Noto Serif CJK SC" in svg, f"{fx}: title Song stack missing"
    for fx, mod in (("ex_points.json", render_points), ("ex_dated.json", render_dated),
                    ("ex_gantt.json", render_spans), ("ex_flow.json", render_flow),
                    ("ex_relation.json", render_relation), ("ex_tree.json", render_tree)):
        svg, _, _ = mod.render(load(fx))
        _safe(svg, fx)
    cmp_m = json.load(open(os.path.join(HERE, "..", "examples", "comparison-table.json"), encoding="utf-8"))
    csvg, _, _ = render_compare.render(cmp_m)
    _safe(csvg, "comparison-table.json")


@check("aesthetic · isosceles-triangle arrowhead (not notched)")
def _():
    svg, _, _ = render_flow.render(load("ex_flow.json"))
    assert "M 0 0 L 12 6 L 0 12 Z" in svg, "arrowhead is not the isosceles triangle"


@check("arrow · the head stays a SHARP point — head/line junction is proportional")
def _():
    """v1.0.1 pinned refX=11 for every arrow, whatever the line weight. At that
    point the triangle is only ~0.4x the stroke width, so the line's square cap
    poked out on both sides and the tip rendered FLAT. The junction is now
    DERIVED from the stroke width (common.arrow_geom), so this guard measures
    the invariant directly instead of trusting a byte-snapshot of the masters."""
    import common
    MIN_COVER = 1.5          # triangle height at the line's end, in stroke widths
    mk_re = re.compile(r'<marker id="([^"]+)" viewBox="0 0 12 12" refX="([\d.]+)"'
                       r'[^>]*markerWidth="([\d.]+)"')
    ed_re = re.compile(r'<path [^>]*stroke-width="([\d.]+)"[^>]*marker-end="url\(#([^)]+)\)"')

    tree_m = load("ex_tree.json"); tree_m["arrows"] = True
    cases = [("flow", render_flow, load("ex_flow.json")),
             ("flow-parallel", render_flow, load("ex_flow_parallel.json")),
             ("relation", render_relation, load("ex_relation.json")),
             ("relation-dense", render_relation, load("edge_relation_dense.json")),
             ("tree", render_tree, tree_m)]
    measured = 0
    for name, mod, m in cases:
        svg, _, _ = mod.render(m)
        mk = {i: (float(rx), float(sz)) for i, rx, sz in mk_re.findall(svg)}
        assert mk, f"{name}: no arrowhead marker defined"
        for w, mid in ed_re.findall(svg):
            w = float(w)
            assert mid in mk, f"{name}: edge points at undefined marker {mid}"
            refX, size = mk[mid]
            cover = (12 - refX) * (size / 12) / w
            assert cover >= MIN_COVER, (
                f"{name}/{mid}: triangle is {cover:.2f}x the {w}px line at the "
                f"line's end — under {MIN_COVER}x the cap pokes out and flattens the tip")
            # and the line must still be swallowed by the head: no seam
            assert (12 - refX) * (size / 12) <= size, f"{name}/{mid}: line ends ahead of the head"
            measured += 1
    assert measured >= 5, f"only {measured} arrow junctions measured"

    # the rule is a RATIO: a heavier line must push its junction further back
    thin, _ = common.arrow_geom(10, 1.6)
    thick, _ = common.arrow_geom(10, 3.0)
    assert thick < thin, "junction did not move back for a heavier line — still a dead number"
    # and the line must stop further short of the node when the head is bigger
    assert common.head_trim(14, 3) > common.head_trim(10, 2), "head-room is not proportional"


def _pptx_bytes(mod, m, mode=None):
    """Render a map to SVG (optionally themed) and transcribe it to a .pptx in
    a temp file; returns (slide_xml, presentation_xml, svg)."""
    import export_pptx, render as _render, tempfile, zipfile, os as _os
    svg, W, H = mod.render(m)
    if mode == "mono":
        svg = _render.to_monochrome(svg)
    elif mode == "guizang":
        svg = _render.to_guizang(svg, m.get("layout"))
    sp = tempfile.mktemp(suffix=".svg"); pp = tempfile.mktemp(suffix=".pptx")
    open(sp, "w", encoding="utf-8").write(svg)
    export_pptx.export(sp, pp)
    with zipfile.ZipFile(pp) as z:
        names = set(z.namelist())
        slide = z.read("ppt/slides/slide1.xml").decode("utf-8")
        pres = z.read("ppt/presentation.xml").decode("utf-8")
    _os.unlink(sp); _os.unlink(pp)
    return slide, pres, svg, names


_PPTX_CASES = [("flow", render_flow, "ex_flow.json"), ("relation", render_relation, "ex_relation.json"),
               ("tree", render_tree, "ex_tree.json"), ("points", render_points, "ex_points.json"),
               ("dated", render_dated, "ex_dated.json"), ("gantt", render_spans, "ex_gantt.json"),
               ("compare", render_compare, "ex_compare.json")]


@check("pptx · every layout exports a well-formed package with the required parts")
def _():
    need = {"[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml",
            "ppt/slides/slide1.xml", "ppt/slideMasters/slideMaster1.xml",
            "ppt/slideLayouts/slideLayout1.xml", "ppt/theme/theme1.xml"}
    for name, mod, fx in _PPTX_CASES:
        slide, pres, svg, names = _pptx_bytes(mod, load(fx))
        missing = need - names
        assert not missing, f"{name}: .pptx is missing required parts {sorted(missing)}"
        _MD.parseString(slide); _MD.parseString(pres)      # PowerPoint refuses malformed XML


@check("pptx · text is verbatim — every word of the figure survives into the deck")
def _():
    """The verbatim rule applies to EVERY deliverable, not just the SVG."""
    for name, mod, fx in _PPTX_CASES:
        slide, _, svg, _ = _pptx_bytes(mod, load(fx))
        want = [re.sub(r"<[^>]+>", "", t).strip()
                for t in re.findall(r"<text[^>]*>(.*?)</text>", svg, re.S)]
        want = [w for w in want if w]
        body = "".join(re.findall(r"<a:t>(.*?)</a:t>", slide, re.S))
        for w in want:
            plain = (w.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                      .replace("&quot;", '"').replace("&#x27;", "'"))
            esc = (plain.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
            assert plain in body or esc in body, f"{name}: deck lost text {w!r}"


@check("pptx · geometry is transcribed EXACTLY from the master (no second layout engine)")
def _():
    import export_pptx
    EMU = 9525
    for name, mod, fx in _PPTX_CASES:
        slide, pres, svg, _ = _pptx_bytes(mod, load(fx))
        mw = re.search(r'<svg[^>]*width="([\d.]+)"[^>]*height="([\d.]+)"', svg)
        W, H = float(mw.group(1)), float(mw.group(2))
        sz = re.search(r'<p:sldSz cx="(\d+)" cy="(\d+)"', pres)
        assert abs(int(sz.group(1)) / EMU - W) < 0.5 and abs(int(sz.group(2)) / EMU - H) < 0.5, \
            f"{name}: slide canvas does not match the figure"
        prims = export_pptx.attach_text(export_pptx.parse_svg(svg), W, H)
        boxes = [(float(a) / EMU, float(b) / EMU, float(c) / EMU, float(d) / EMU) for a, b, c, d in
                 re.findall(r'<a:off x="(-?\d+)" y="(-?\d+)"/><a:ext cx="(\d+)" cy="(\d+)"/>', slide)]
        for s in [p for p in prims if p["k"] in ("rect", "ellipse", "poly")]:
            near = min(boxes, key=lambda t: abs(t[0] - s["x"]) + abs(t[1] - s["y"])
                       + abs(t[2] - s["w"]) + abs(t[3] - s["h"]))
            off = max(abs(near[0] - s["x"]), abs(near[1] - s["y"]),
                      abs(near[2] - s["w"]), abs(near[3] - s["h"]))
            assert off < 0.5, f"{name}: shape drifted {off:.2f}px between the SVG and the deck"


@check("pptx · the background never swallows the title (it stays its own editable box)")
def _():
    """Regression: the full-canvas backdrop contains every label geometrically,
    so a naive containment test made the title a caption INSIDE the backdrop —
    clicking the title then selected the whole background instead of the words."""
    import export_pptx
    for name, mod, fx in _PPTX_CASES:
        m = load(fx)
        svg, W, H = mod.render(m)
        prims = export_pptx.attach_text(export_pptx.parse_svg(svg), W, H)
        for p in prims:
            if p["k"] in ("rect", "ellipse", "poly") and p.get("lines"):
                assert p["w"] * p["h"] < 0.9 * W * H, \
                    f"{name}: the canvas backdrop captured {len(p['lines'])} text run(s)"
        title = m.get("title_text", "")
        free = [p for p in prims if p["k"] == "text" and not p.get("used")]
        assert any(title.startswith(f["t"][:6]) or f["t"] in title for f in free), \
            f"{name}: the title is not an independent text box"


@check("pptx · text FITS — the deck is audited as rendered, not just as written")
def _():
    """The defect this exists for: the first exporter wrote font sizes in px as
    if they were points (33% oversized) and let PowerPoint re-wrap text the
    scripts had already broken. Boxes burst, the title split in two and a dozen
    year labels stacked into one column — and every other check still passed,
    because they only asked whether the text was PRESENT."""
    import export_pptx, tempfile, os as _os
    for name, mod, fx in _PPTX_CASES:
        for mode in (None, "guizang", "mono"):
            svg, W, H = mod.render(load(fx))
            if mode == "mono":
                import render as _r; svg = _r.to_monochrome(svg)
            elif mode == "guizang":
                import render as _r; svg = _r.to_guizang(svg, load(fx).get("layout"))
            sp = tempfile.mktemp(suffix=".svg"); pp = tempfile.mktemp(suffix=".pptx")
            open(sp, "w", encoding="utf-8").write(svg)
            export_pptx.export(sp, pp)
            problems = export_pptx.audit_deck(pp)
            _os.unlink(sp); _os.unlink(pp)
            assert not problems, f"{name}/{mode or 'qichuan'}: " + "; ".join(problems[:3])


@check("pptx · a label never joins a shape that is not its own centred caption")
def _():
    """The time-band contains a dozen year labels at a dozen x positions. Adopting
    them turned the axis into one shape holding a vertical column of years."""
    import export_pptx
    for name, mod, fx in _PPTX_CASES:
        svg, W, H = mod.render(load(fx))
        prims = export_pptx.attach_text(export_pptx.parse_svg(svg), W, H)
        for p in prims:
            xs = {round(l["x"], 1) for l in p.get("lines", [])}
            assert len(xs) <= 1, f"{name}: a shape adopted labels at {len(xs)} different x — they stack"


@check("pptx · group transforms are honoured (歸藏风 天头 / fit_title push-down)")
def _():
    """The renderers wrap content in <g transform="translate(0,dy)"> twice —
    歸藏风 reserves a 天头 above its big title, and fit_title pushes the drawing
    down when a long title wraps. A parser that ignores those lifts the WHOLE
    figure by that offset; the 歸藏风 title then sat above the top of the slide."""
    import export_pptx
    svg, W, H = render_flow.render(load("ex_flow.json"))
    base = export_pptx.parse_svg(svg)
    shifted = svg.replace("</defs>", '</defs><g transform="translate(0,60)">', 1)
    shifted = shifted.replace("</svg>", "</g></svg>", 1)
    moved = export_pptx.parse_svg(shifted)
    assert len(base) == len(moved), "transform parsing dropped elements"
    dys = {round(b["y"] - a["y"], 3) for a, b in zip(base, moved) if "y" in a and "y" in b}
    assert dys == {60.0}, f"translate(0,60) was not applied to every element: {sorted(dys)[:4]}"


@check("pptx · every route is reproduced POINT-FOR-POINT (start, bends and end)")
def _():
    """The defect this exists for: bent routes were handed to PowerPoint's
    `bentConnector3`, which has one fixed horizontal→vertical→horizontal shape
    and is placed from the two ENDPOINTS alone. Routes the router built as
    vertical→horizontal→vertical came out leaving and entering their nodes from
    the wrong sides — every bend recomputed, none of them the router's."""
    import export_pptx
    EMU = 9525
    checked = 0
    for name, mod, fx in _PPTX_CASES:
        slide, _, svg, _ = _pptx_bytes(mod, load(fx))
        routes = []
        for m in re.finditer(r'name="Route \d+".*?<a:off x="(-?\d+)" y="(-?\d+)"/>'
                             r'<a:ext cx="(\d+)" cy="(\d+)"/>.*?<a:pathLst>(.*?)</a:pathLst>', slide, re.S):
            x, y, cx, cy = [int(v) / EMU for v in m.groups()[:4]]
            pts = [(int(a), int(b)) for a, b in re.findall(r'<a:pt x="(-?\d+)" y="(-?\d+)"/>', m.group(5))]
            routes.append([(x + a / 100000 * cx, y + b / 100000 * cy) for a, b in pts])
        for m in re.finditer(r'<p:cxnSp>.*?<a:xfrm( flipH="1")?( flipV="1")?>'
                             r'<a:off x="(-?\d+)" y="(-?\d+)"/><a:ext cx="(\d+)" cy="(\d+)"/>', slide, re.S):
            fh, fv = bool(m.group(1)), bool(m.group(2))
            x, y, cx, cy = [int(v) / EMU for v in m.groups()[2:]]
            routes.append([((x + cx) if fh else x, (y + cy) if fv else y),
                           (x if fh else (x + cx), y if fv else (y + cy))])
        for c in [p for p in export_pptx.parse_svg(svg) if p["k"] == "conn"]:
            want = c["pts"]
            cand = [r for r in routes if len(r) == len(want)] or routes
            best = min(cand, key=lambda r: abs(r[0][0] - want[0][0]) + abs(r[0][1] - want[0][1])
                       + abs(r[-1][0] - want[-1][0]) + abs(r[-1][1] - want[-1][1]))
            off = max(max(abs(a[0] - b[0]), abs(a[1] - b[1])) for a, b in zip(best, want)) \
                if len(best) == len(want) else 999
            assert off < 0.5, f"{name}: a route drifted {off:.1f}px — the deck redrew it"
            checked += 1
    assert checked >= 20, f"only {checked} routes compared"


@check("timeline · the axis bar/band is a RIGHT-ANGLE bar in every mode, never a pill")
def _():
    """A time ruler is a bar, not a card. The dated band used to be drawn as a
    stadium (rx = height/2) and the numbered axis line with round caps."""
    import render as _r
    svg, _, _ = render_dated.render(load("ex_dated.json"))
    for tag, s2 in (("奇川风", svg), ("白描", _r.to_monochrome(svg)), ("歸藏风", _r.to_guizang(svg, "dated_point_timeline"))):
        m = re.search(r'<rect data-role="axis"[^>]*>', s2)
        assert m, f"{tag}: no axis band found"
        rx = re.search(r'rx="([\d.]+)"', m.group(0))
        assert rx and float(rx.group(1)) == 0, f"{tag}: axis band is rounded (rx={rx and rx.group(1)})"
    psvg, _, _ = render_points.render(load("ex_points.json"))
    ax = re.search(r'<line data-role="axis"[^>]*>', psvg)
    assert ax and "linecap=\"round\"" not in ax.group(0), "numbered axis line still has round caps"


def _canvas(svg):
    m = re.search(r'<svg[^>]*width="([\d.]+)"[^>]*height="([\d.]+)"', svg)
    return (float(m.group(1)), float(m.group(2))) if m else (0, 0)


@check("pptx · the RENDERED deck matches the master (converted, measured, compared)")
def _():
    """The only check here that looks at a rendered result rather than at the
    XML we wrote. Every earlier defect — 33%-oversized type, re-wrapped captions,
    a stacked column of years, a decision node whose question vanished — passed
    every other guard. Skipped when soffice/pdftotext are absent; doctor.py
    reports that, and it is not a required dependency of the skill itself."""
    import export_pptx, verify_pptx, tempfile, os as _os
    if not verify_pptx.available():
        return
    import render as _r
    for name, mod, fx in (("flow", render_flow, "ex_flow.json"),
                          ("dated", render_dated, "ex_dated.json"),
                          ("compare", render_compare, "ex_compare.json")):
        for mode in (None, "guizang", "mono"):
            svg, W, H = mod.render(load(fx))
            if mode == "guizang":
                svg = _r.to_guizang(svg, load(fx).get("layout"))   # the pattern-fill backdrop lives here
            elif mode == "mono":
                svg = _r.to_monochrome(svg)
            sp = tempfile.mktemp(suffix=".svg"); pp = tempfile.mktemp(suffix=".pptx")
            open(sp, "w", encoding="utf-8").write(svg)
            export_pptx.export(sp, pp, fonts="master")   # the sandbox has these faces
            problems = verify_pptx.verify(sp, pp)
            _os.unlink(sp); _os.unlink(pp)
            assert not problems, (f"{name}/{mode or 'qichuan'}: " + "; ".join(problems[:3])
                                  + "  (all text missing usually means the deck did not open)")


@check("pptx · a module is ONE object — its caption lives in the shape")
def _():
    """Splitting a four-line card into four floating text boxes is exact and
    practically hostile: editing the card then means chasing four objects."""
    import export_pptx
    for name, mod, fx in _PPTX_CASES:
        slide, _, svg, _ = _pptx_bytes(mod, load(fx))
        prims = export_pptx.attach_text(export_pptx.parse_svg(svg), *_canvas(svg))
        free = sum(1 for p in prims if p["k"] == "text" and not p.get("used"))
        boxes = slide.count('txBox="1"')
        assert boxes <= free, (f"{name}: {boxes} loose text boxes but only {free} free labels — "
                               f"a shape's caption was split out of it")
    # and merging really happens: a multi-line card must be ONE object
    slide, _, svg, _ = _pptx_bytes(render_flow, load("ex_flow.json"))
    multi = [sp for sp in re.findall(r"<p:sp>.*?</p:sp>", slide, re.S) if sp.count("<a:t>") >= 3]
    assert multi, "no shape carries a multi-line caption — captions are still split"


@check("pptx · a preset's adjust handles are supplied in FULL or not at all")
def _():
    """Writing <a:avLst> REPLACES a preset's defaults wholesale. The decision
    node shipped `hexagon` with only `adj` and not `vf`; the geometry formula
    then referenced a guide that no longer existed and PowerPoint refused to
    open the file — while LibreOffice, python-pptx and the XSD all accepted it."""
    import export_pptx
    for name, mod, fx in _PPTX_CASES:
        for mode in (None, "guizang"):
            slide, _, _, _ = _pptx_bytes(mod, load(fx), mode)
            for prst, av in re.findall(r'<a:prstGeom prst="(\w+)">(.*?)</a:prstGeom>', slide, re.S):
                assert prst in export_pptx.PRESET_ADJUSTS, \
                    f"{name}: preset {prst!r} has no declared adjust list — add it"
                need = set(export_pptx.PRESET_ADJUSTS[prst])
                got = set(re.findall(r'<a:gd name="(\w+)"', av))
                assert got in (set(), need), \
                    f"{name}: {prst} supplies {sorted(got)} but declares {sorted(need)}"


@check("pptx · no zero-extent shape (PowerPoint treats them inconsistently)")
def _():
    for name, mod, fx in _PPTX_CASES:
        slide, _, _, _ = _pptx_bytes(mod, load(fx))
        for cx, cy in re.findall(r'<a:ext cx="(\d+)" cy="(\d+)"/>', slide):
            assert int(cx) > 0 and int(cy) > 0, f"{name}: a shape has a zero extent"


@check("pptx · the canvas background survives (歸藏风 paper + dot matrix)")
def _():
    """歸藏风 emits TWO <defs> with the paper rect between them. Parsing
    "everything after the last </defs>" threw that rect away and the deck came
    out with no background at all."""
    import export_pptx, render as _r
    svg, W, H = render_flow.render(load("ex_flow.json"))
    gz = _r.to_guizang(svg, "graphviz_flow")
    assert gz.count("</defs>") >= 2, "fixture no longer exercises the two-defs case"
    prims = export_pptx.parse_svg(gz)
    m = re.search(r'<svg[^>]*width="([\d.]+)"[^>]*height="([\d.]+)"', gz)
    W, H = float(m.group(1)), float(m.group(2))
    paper = [p for p in prims if p["k"] == "rect" and p["w"] >= W - 1 and p["h"] >= H - 1]
    assert paper, "the full-canvas background rect was dropped by defs-stripping"
    assert any(p.get("fill") for p in paper), "the paper colour was lost"
    assert any(p.get("pattern") for p in paper), "the IKB dot matrix layer was lost"
    slide, _, _, _ = _pptx_bytes(render_flow, load("ex_flow.json"), "guizang")
    assert "FAFAF8" in slide, "歸藏风 deck has no paper colour"
    assert "pattFill" in slide, "歸藏风 deck has no dot-matrix texture"


@check("pptx · a sans stack never resolves to the Song face ('sans-serif' contains 'serif')")
def _():
    import export_pptx
    sans_stack = "'PingFang SC','Microsoft YaHei','Noto Sans CJK SC',Arial,sans-serif"
    assert export_pptx._face({"family": sans_stack}) == export_pptx._FONTS["sans"], \
        "a sans-serif stack resolved to the serif face — body text would render as Song"
    assert export_pptx._face({"family": "'方正小标宋简体','思源宋体',serif"}) == \
        export_pptx._FONTS["serif"], "the Song title stack no longer resolves to a Song face"
    # and in practice: 奇川风 uses Song for the title only, 歸藏风 not at all
    slide, _, _, _ = _pptx_bytes(render_flow, load("ex_flow.json"))
    n_serif = slide.count(f'<a:ea typeface="{export_pptx.FONT_PROFILES["master"]["serif"]}"/>')
    assert n_serif == 1, f"奇川风 deck uses the Song face {n_serif}x — it belongs on the title alone"
    gz, _, _, _ = _pptx_bytes(render_flow, load("ex_flow.json"), "guizang")
    assert f'<a:ea typeface="{export_pptx.FONT_PROFILES["master"]["serif"]}"/>' not in gz, \
        "歸藏风 deck carries a Song face — the mode is sans throughout"


@check("歸藏风 · the dot grid is GREY — the Klein blue stays a single anchor")
def _():
    """Reference: 歸藏's own Swiss decks lay a faint LIGHT-GREY dot grid on warm
    paper and spend the Klein blue on solid blocks and a few emphasised marks.
    Tinting the whole backdrop with the accent spends the one anchor colour on
    the background — the grid then competes with the content, which is what this
    style exists to prevent."""
    import render as _r, export_pptx
    svg, _, _ = render_flow.render(load("ex_flow.json"))
    gz = _r.to_guizang(svg, "graphviz_flow")
    dot = re.search(r'<pattern id="gzdot".*?</pattern>', gz, re.S)
    assert dot, "歸藏风 lost its dot-matrix layer"
    col = re.search(r'fill="(#[0-9A-Fa-f]{6})"', dot.group(0)).group(1).upper()
    assert col != "#002FA7", "the dot grid is painted in the accent colour"
    assert col in ("#D4D4D2", "#BDBDBD", "#E0E0E0"), f"dot grid colour {col} is not a light grey"
    slide, _, _, _ = _pptx_bytes(render_flow, load("ex_flow.json"), "guizang")
    pat = re.search(r'<a:pattFill.*?</a:pattFill>', slide, re.S)
    assert pat and "002FA7" not in pat.group(0), "the deck's backdrop texture is blue"


@check("歸藏风 · Latin/numeral runs carry TRACKING, in the SVG and in the deck")
def _():
    """Wide letter-spacing on the Latin run is half of what makes this style read
    as engineered rather than merely sans."""
    import render as _r
    svg, _, _ = render_dated.render(load("ex_dated.json"))
    gz = _r.to_guizang(svg, "dated_point_timeline")
    tracked = re.findall(r'<text[^>]*letter-spacing="([\d.]+)"[^>]*>', gz)
    assert tracked, "歸藏风 Latin runs carry no tracking"
    assert all(float(t) > 0 for t in tracked)
    # CJK must NOT be tracked — tracking Chinese just loosens it into mush
    for tag in re.findall(r'<text[^>]*letter-spacing[^>]*>([^<]*)</text>', gz):
        assert not re.search(r'[\u4E00-\u9FFF]', tag), f"CJK run was tracked: {tag[:12]!r}"
    slide, _, _, _ = _pptx_bytes(render_dated, load("ex_dated.json"), "guizang")
    assert re.search(r'spc="[1-9]\d*"', slide), "the deck dropped the tracking"


@check("raster · integer scale + integer hairlines (a bar's two rules weigh the same)")
def _():
    """A reader spotted the timeline band printing a heavier rule underneath than
    over it. Nothing in the SVG was asymmetric: the raster ran at 150 dpi — scale
    1.5625 — so integer coordinates fell mid-pixel and a 1.2px centred stroke
    resolved to one dark pixel above and two below. Both halves are locked here:
    the scale must be an exact integer, and hairlines must be integer widths."""
    import render as _render
    assert _render.svg_to_png.__defaults__[0] % 96 == 0, \
        "the raster dpi is not a whole multiple of 96 — the scale will be fractional"
    for name, mod, fx in _PPTX_CASES:
        for mode in (None, "mono", "guizang"):
            svg, _, _ = mod.render(load(fx))
            if mode == "mono":
                svg = _render.to_monochrome(svg)
            elif mode == "guizang":
                svg = _render.to_guizang(svg, load(fx).get("layout"))
            for t in re.findall(r"<rect\b[^>]*/>", svg):
                m = re.search(r'stroke-width="([\d.]+)"', t)
                if not m:
                    continue
                w = float(m.group(1))
                assert w == int(w), (f"{name}/{mode or 'qichuan'}: a rect hairline is "
                                     f"{w}px — fractional widths render one edge of a "
                                     f"bar heavier than the other")


def _vsdx_page(mod, m, mode=None):
    import export_vsdx, render as _r, tempfile, zipfile, os as _os
    svg, W, H = mod.render(m)
    if mode == "mono":
        svg = _r.to_monochrome(svg)
    elif mode == "guizang":
        svg = _r.to_guizang(svg, m.get("layout"))
    sp = tempfile.mktemp(suffix=".svg"); vp = tempfile.mktemp(suffix=".vsdx")
    open(sp, "w", encoding="utf-8").write(svg)
    export_vsdx.export(sp, vp)
    with zipfile.ZipFile(vp) as z:
        names = set(z.namelist())
        page = z.read("visio/pages/page1.xml").decode("utf-8")
        pages = z.read("visio/pages/pages.xml").decode("utf-8")
    _os.unlink(sp); _os.unlink(vp)
    return page, pages, svg, names


@check("vsdx · every layout exports a well-formed Visio package")
def _():
    need = {"[Content_Types].xml", "_rels/.rels", "visio/document.xml",
            "visio/pages/pages.xml", "visio/pages/page1.xml"}
    for name, mod, fx in _PPTX_CASES:
        page, pages, svg, names = _vsdx_page(mod, load(fx))
        missing = need - names
        assert not missing, f"{name}: .vsdx missing {sorted(missing)}"
        _MD.parseString(page); _MD.parseString(pages)
        assert page.count("<Shape ") >= 3, f"{name}: page has almost no shapes"


@check("vsdx · inches, and the Y axis is flipped (Visio's origin is bottom-left)")
def _():
    """Visio measures in inches from the BOTTOM-left with Y pointing up, while
    the master is pixels from the top-left with Y pointing down. Getting this
    wrong mirrors the whole figure vertically, which still looks like a diagram."""
    import export_vsdx
    for name, mod, fx in (("flow", render_flow, "ex_flow.json"),
                          ("dated", render_dated, "ex_dated.json")):
        page, pages, svg, _ = _vsdx_page(mod, load(fx))
        m = re.search(r'<svg[^>]*width="([\d.]+)"[^>]*height="([\d.]+)"', svg)
        W, H = float(m.group(1)), float(m.group(2))
        pw = float(re.search(r'<Cell N="PageWidth" V="([\d.]+)"', pages).group(1))
        ph = float(re.search(r'<Cell N="PageHeight" V="([\d.]+)"', pages).group(1))
        assert abs(pw - W / 96.0) < 0.01 and abs(ph - H / 96.0) < 0.01, \
            f"{name}: page is not the master's canvas in inches"
        # The master's TOPMOST element must land at the HIGHEST PinY. A weaker
        # test ("something sits in the upper half") passes even with the flip
        # removed, because an un-flipped figure still fills the page.
        prims = export_vsdx.attach_text(export_vsdx.parse_svg(svg), W, H)
        texts = [p for p in prims if p["k"] == "text"]
        assert texts, "fixture has no text"
        top = min(texts, key=lambda p: p["y"])
        # No threshold needed: the expected PinY is (H - y)/96 by definition, and
        # an un-flipped export would put it at y/96 instead. The equation IS the test.
        want = (H - top["y"]) / 96.0
        pins = [float(v) for v in re.findall(r'<Cell N="PinY" V="([\d.-]+)"', page)]
        assert any(abs(v - want) < 0.12 for v in pins), (
            f"{name}: the master's topmost text should sit at PinY≈{want:.2f}in "
            f"(page {ph:.2f}in tall) — nothing does, so the figure is flipped")


@check("vsdx · the arrowhead is the SOLID one (line-end enum pinned by measurement)")
def _():
    """Visio names its line ends by number, and the wrong number is the same class
    of error as the wrong dash: the file stays valid and the arrow is still there,
    it is simply the wrong shape. Measured by rasterising the same figure with
    several values and counting ink, against a no-arrow baseline:

        EndArrow=0 (none)  baseline      EndArrow=2   +560
        EndArrow=1         -115  (open)  EndArrow=4   +952  <- solid, the master's
        EndArrow=5         +757

    4 lays down the most ink, i.e. it is the filled triangle the master draws.
    Pinned here so it is not 'tidied' to a neighbouring value."""
    import export_vsdx
    page, _, _, _ = _vsdx_page(render_flow, load("ex_flow.json"))
    ends = set(re.findall(r'<Cell N="EndArrow" V="(\d+)"', page))
    assert ends, "no arrowhead in the .vsdx"
    assert ends == {"4"}, f"line-end enum is {sorted(ends)}, expected the solid triangle (4)"


@check("verbatim · <, >, & and quotes survive intact into all four formats")
def _():
    """Angle brackets and ampersands appear in real party names (甲<公司>&乙) and
    are the classic way to lose text or produce an unopenable file.

    draw.io's labels are HTML inside an XML attribute, so a literal `<` is stored
    DOUBLE-escaped as `&amp;lt;` — correct, and easy to misread as corruption if
    the check only unescapes once. That is checked here explicitly rather than
    left as a surprise."""
    import export_pptx, export_vsdx, export_drawio, tempfile, zipfile, html
    import os as _os, shutil as _sh
    import xml.dom.minidom as _md

    m = load("edge_special_chars.json")
    KEYS = {"title", "label", "text", "title_text"}
    acc = []
    def walk(o):
        if isinstance(o, dict):
            for k, v in o.items():
                if k in KEYS and isinstance(v, str):
                    acc.append(v)
                else:
                    walk(v)
        elif isinstance(o, list):
            for x in o:
                walk(x)
    walk(m)
    risky = [x for x in acc if any(c in x for c in '<>&"\'')]
    assert risky, "fixture no longer exercises special characters"

    import render as _render
    svg, W, H = _render.choose(m).render(m)
    d = tempfile.mkdtemp(); sp = _os.path.join(d, "f.svg")
    open(sp, "w", encoding="utf-8").write(svg)
    pp = _os.path.join(d, "f.pptx"); export_pptx.export(sp, pp)
    vp = _os.path.join(d, "f.vsdx"); export_vsdx.export(sp, vp)
    bodies = {
        "svg": svg,
        "pptx": zipfile.ZipFile(pp).read("ppt/slides/slide1.xml").decode(),
        "vsdx": zipfile.ZipFile(vp).read("visio/pages/page1.xml").decode(),
    }
    if m.get("layout") in export_drawio.SUPPORTED_LAYOUTS:
        bodies["drawio"] = export_drawio.build_model(m)[0]
    _sh.rmtree(d, ignore_errors=True)

    for fmt, body in bodies.items():
        _md.parseString(body)            # an unescaped & makes the file unopenable
        if fmt == "svg":
            runs = re.findall(r"<text[^>]*>(.*?)</text>", body, re.S)
        elif fmt == "pptx":
            runs = re.findall(r"<a:t>(.*?)</a:t>", body, re.S)
        elif fmt == "vsdx":
            runs = re.findall(r"<Text>(.*?)</Text>", body, re.S)
        else:
            runs = re.findall(r'value="([^"]*)"', body)
        # draw.io labels are HTML inside XML — unescape twice there, once elsewhere
        rounds = 2 if fmt == "drawio" else 1
        text = ""
        for r in runs:
            r = re.sub(r"<[^>]+>", "", r)
            for _ in range(rounds):
                r = html.unescape(r)
            text += r
        text = re.sub(r"\s+", "", text)
        for w in risky:
            assert re.sub(r"\s+", "", w) in text, f"{fmt} mangled {w!r}"


@check("verbatim · every word of the map survives into ALL FOUR formats")
def _():
    """The verbatim rule is the skill's oldest promise: only the visuals change,
    never a character of the legal text. It was only being checked on the .pptx.

    Text hides in a different PLACE in each format — element content in SVG,
    PowerPoint and Visio, but an ATTRIBUTE value in .drawio — and the wrapping
    positions differ (SVG breaks '…丙拒收不影 / 响效力；', draw.io breaks
    '…不影响效 / 力；'). Comparing raw file text, or the SVG's per-line fragments,
    reports losses that are not there. So: pull each format's text from where it
    actually lives, drop the line-break markers, and compare against the SOURCE."""
    import export_pptx, export_vsdx, export_drawio, tempfile, zipfile, os as _os
    import render as _render

    KEYS = {"title", "label", "text", "desc", "subtitle", "note", "title_text", "name"}

    def source_strings(m):
        out = []
        def walk(o):
            if isinstance(o, dict):
                for k, v in o.items():
                    if k in KEYS:
                        if isinstance(v, str):
                            out.append(v)
                        elif isinstance(v, list):
                            out.extend(x for x in v if isinstance(x, str))
                    else:
                        walk(v)
            elif isinstance(o, list):
                for x in o:
                    walk(x)
        walk(m)
        return [x for x in out if x.strip()]

    def unesc(t):
        t = re.sub(r"&lt;br\s*/?&gt;|<br\s*/?>|&#10;", "", t)
        for a, b in (("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
                     ("&quot;", '"'), ("&#x27;", "'"), ("&apos;", "'")):
            t = t.replace(a, b)
        return re.sub(r"\s+", "", t)

    def pull(fmt, body):
        if fmt == "svg":
            runs = re.findall(r"<text[^>]*>(.*?)</text>", body, re.S)
        elif fmt == "pptx":
            runs = re.findall(r"<a:t>(.*?)</a:t>", body, re.S)
        elif fmt == "vsdx":
            runs = re.findall(r"<Text>(.*?)</Text>", body, re.S)
        else:
            runs = re.findall(r'value="([^"]*)"', body)
        return unesc("".join(re.sub(r"<[^>]+>", "", r) for r in runs))

    for name, mod, fx in _PPTX_CASES:
        m = load(fx)
        want = source_strings(m)
        assert want, f"{name}: fixture has no text to check"
        svg, W, H = mod.render(m)
        d = tempfile.mkdtemp()
        sp = _os.path.join(d, "f.svg")
        open(sp, "w", encoding="utf-8").write(svg)
        got = {"svg": pull("svg", svg)}
        pp = _os.path.join(d, "f.pptx"); export_pptx.export(sp, pp)
        got["pptx"] = pull("pptx", zipfile.ZipFile(pp).read("ppt/slides/slide1.xml").decode())
        vp = _os.path.join(d, "f.vsdx"); export_vsdx.export(sp, vp)
        got["vsdx"] = pull("vsdx", zipfile.ZipFile(vp).read("visio/pages/page1.xml").decode())
        if m.get("layout") in export_drawio.SUPPORTED_LAYOUTS:
            got["drawio"] = pull("drawio", export_drawio.build_model(m)[0])
        import shutil as _sh; _sh.rmtree(d, ignore_errors=True)
        for w in want:
            for fmt, body in got.items():
                assert unesc(w) in body, f"{name}/{fmt} lost {w[:24]!r}"


@check("dash · a dashed line stays a PLAIN dash in every format")
def _():
    """The master draws these as `stroke-dasharray="6 4"` — an even dash. Visio's
    LinePattern 4 and OOXML's `dashDot` both draw dash·dot·dash instead, and that
    error survives every structural check: the file is valid, the line is present
    and in the right place, and only a reader sees it is the wrong KIND of dash.
    Both formats are pinned to the plain-dash value."""
    import export_pptx, export_vsdx, tempfile, zipfile, os as _os
    svg, W, H = render_spans.render(load("ex_gantt.json"))
    assert 'stroke-dasharray' in svg, "fixture no longer has a dashed line"
    sp = tempfile.mktemp(suffix=".svg")
    open(sp, "w", encoding="utf-8").write(svg)

    vp = tempfile.mktemp(suffix=".vsdx")
    export_vsdx.export(sp, vp)
    with zipfile.ZipFile(vp) as z:
        page = z.read("visio/pages/page1.xml").decode("utf-8")
    _os.unlink(vp)
    pats = set(re.findall(r'<Cell N="LinePattern" V="(\d+)"', page))
    assert "2" in pats, "no plain-dash line in the .vsdx"
    for bad, what in (("3", "dotted"), ("4", "dash-dot"), ("5", "dash-dot-dot")):
        assert bad not in pats, f".vsdx draws a {what} line where the master has a plain dash"

    pp = tempfile.mktemp(suffix=".pptx")
    export_pptx.export(sp, pp)
    with zipfile.ZipFile(pp) as z:
        slide = z.read("ppt/slides/slide1.xml").decode("utf-8")
    _os.unlink(pp); _os.unlink(sp)
    vals = set(re.findall(r'<a:prstDash val="(\w+)"/>', slide))
    assert "dash" in vals, "no plain-dash line in the .pptx"
    for bad in ("dashDot", "lgDashDot", "lgDashDotDot", "sysDashDot", "sysDashDotDot", "dot"):
        assert bad not in vals, f".pptx draws {bad} where the master has a plain dash"


@check("vsdx · a caption that MIXES font sizes is split, so no tool can flatten it")
def _():
    """Visio's per-run formatting is spec-correct but LibreOffice's importer
    collapses a CJK shape's text to one size — measured here, a card's 13px
    sub-lines came out at the title's size. A caption of mixed sizes is therefore
    emitted as one shape per size; a uniform caption stays inside its module."""
    import export_vsdx
    svg, W, H = render_flow.render(load("ex_flow.json"))
    prims = export_vsdx.attach_text(export_vsdx.parse_svg(svg), W, H)
    mixed = [p for p in prims if len(export_vsdx._size_groups(p.get("lines", []))) > 1]
    assert mixed, "fixture no longer has a mixed-size caption to exercise this"
    page, _, _, _ = _vsdx_page(render_flow, load("ex_flow.json"))
    for sh in re.findall(r"<Shape .*?</Shape>", page, re.S):
        sizes = set(re.findall(r'<Cell N="Size" V="([\d.]+)"', sh))
        assert len(sizes) <= 1, f"a vsdx shape still carries {len(sizes)} font sizes"


@check("emphasis · red is opt-in and the SCRIPTS enforce it, not the model")
def _():
    """The rule "if the user skips, use no red" lived only in SKILL.md, so the
    model that wrote the map was also the only thing enforcing it — and the one
    signal audit looked at (provenance.emphasis_note) was written by that same
    model. A rule policed by asking the rule-breaker to confess is not a rule.
    It now lives where the rest of this skill's guarantees live: unless the map
    RECORDS that the user chose the emphasis, the renderer draws it away."""
    import copy
    import render as _render
    from common import strip_unearned_emphasis, EMPHASIS_HOSTS

    def painted_red(svg):
        return svg.split("</defs>")[-1].upper().count("991B1B")

    for name, fx in (("relation", "ex_relation.json"), ("dated", "ex_dated.json"),
                     ("gantt", "ex_gantt.json"), ("tree", "ex_tree.json")):
        base = load(fx)
        marked = sum(1 for h in EMPHASIS_HOSTS for it in (base.get(h) or [])
                     if isinstance(it, dict) and it.get("emphasis"))
        if not marked:
            continue

        # (a) no checkpoint record -> the red is removed before anything is drawn.
        #     The shipped examples DO carry the record (their author chose those
        #     marks), so it is removed here to exercise the unattributed case.
        m = copy.deepcopy(base)
        m.pop("checkpoint", None)
        notes = strip_unearned_emphasis(m)
        assert notes, f"{name}: unattributed emphasis was left standing"
        left = sum(1 for h in EMPHASIS_HOSTS for it in (m.get(h) or [])
                   if isinstance(it, dict) and it.get("emphasis"))
        assert left == 0, f"{name}: {left} unattributed emphasis survived"
        svg, _, _ = _render.choose(m).render(m)
        assert painted_red(svg) == 0, f"{name}: red was still painted without authorisation"

        # (b) the user chose it -> it survives untouched
        m2 = copy.deepcopy(base)
        m2["checkpoint"] = {"emphasis_source": "user", "confirmed": True}
        assert not strip_unearned_emphasis(m2), f"{name}: a user-chosen emphasis was stripped"
        kept = sum(1 for h in EMPHASIS_HOSTS for it in (m2.get(h) or [])
                   if isinstance(it, dict) and it.get("emphasis"))
        assert kept == marked, f"{name}: user-chosen emphasis was altered"
        svg2, _, _ = _render.choose(m2).render(m2)
        assert painted_red(svg2) > 0, f"{name}: an authorised emphasis did not render"

        # (c) the model cannot self-authorise through provenance
        m3 = copy.deepcopy(base)
        m3.pop("checkpoint", None)
        m3.setdefault("provenance", {})["emphasis_note"] = "chosen by the model"
        assert strip_unearned_emphasis(m3), f"{name}: a model-written note authorised red"


@check("audit · the checkpoint record is authoritative, not contradicted")
def _():
    """Two mechanisms saying opposite things about the same figure — the map
    recording `confirmed: true, emphasis_source: "user"` while the audit printed
    `>> CHECKPOINT REQUIRED` and `emphasis was AI-chosen` — teaches the reader to
    trust neither."""
    import copy
    import audit as _audit
    base = load("ex_flow.json")

    confirmed = copy.deepcopy(base)
    confirmed["checkpoint"] = {"emphasis_source": "user", "confirmed": True}
    r = _audit.report(confirmed)
    assert not r["checkpoint_required"], "a confirmed figure still demanded a checkpoint"
    assert not any("AI-chosen" in n for n in r["notes"]), \
        "a user-chosen emphasis was still reported as AI-chosen"

    unconfirmed = copy.deepcopy(base)
    unconfirmed.pop("checkpoint", None)
    r = _audit.report(unconfirmed)
    assert r["checkpoint_required"], "an unconfirmed figure did not demand a checkpoint"

    ai = copy.deepcopy(base)
    ai["checkpoint"] = {"emphasis_source": "model", "confirmed": True}
    r = _audit.report(ai)
    assert any("AI-CHOSEN" in n for n in r["notes"]), \
        "an AI-chosen emphasis was not announced"


@check("export · a font profile cannot leak into the next deck")
def _():
    """`_FONTS` is module-level, so exporting with one profile used to leave it
    set for whatever ran next — and the test suite renders with several profiles
    in one process. The result would be a deck in the wrong typeface with no
    error anywhere."""
    import export_pptx, tempfile, os as _os
    svg, W, H = render_flow.render(load("ex_flow.json"))
    sp = tempfile.mktemp(suffix=".svg")
    open(sp, "w", encoding="utf-8").write(svg)
    before = dict(export_pptx._FONTS)
    for profile in ("safe", "master", "safe"):
        pp = tempfile.mktemp(suffix=".pptx")
        export_pptx.export(sp, pp, fonts=profile)
        _os.unlink(pp)
        assert export_pptx._FONTS == before, \
            f"exporting with {profile!r} left the module's font profile changed"
    _os.unlink(sp)


@check("raster · edge weights are symmetric in a rendered figure")
def _():
    """The reader-visible defect this exists for: a timeline band whose lower rule
    printed heavier than its upper one. Measured on a real raster, not on the SVG,
    because the cause was rasterisation. Skipped when the rasterizer is absent."""
    try:
        from PIL import Image          # noqa: F401
    except ImportError:
        return                          # measuring pixels needs Pillow; doctor reports it
    import make_gallery as _mg
    if not _mg.has_cjk_font():
        return                          # a bare CI runner has no CJK face installed
    import audit_edges, render as _render, tempfile, os as _os, shutil
    if not (shutil.which("soffice") and shutil.which("pdftoppm")):
        return
    for name, mod, fx in (("dated", render_dated, "ex_dated.json"),
                          ("gantt", render_spans, "ex_gantt.json")):
        svg, W, H = mod.render(load(fx))
        svg = _render.to_monochrome(svg)          # 白描 is where the hairlines live
        d = tempfile.mkdtemp()
        sp, pp = _os.path.join(d, "f.svg"), _os.path.join(d, "f.png")
        open(sp, "w", encoding="utf-8").write(svg)
        try:
            _render.svg_to_png(sp, pp)
            bad = audit_edges.run(sp, pp)
        except Exception:
            shutil.rmtree(d, ignore_errors=True)
            return                                 # no rasterizer here; not a failure
        shutil.rmtree(d, ignore_errors=True)
        assert not bad, f"{name}: {bad[:2]}"


@check("lint · catches text OUT OF ITS BOX and text ON TOP OF text")
def _():
    """The two failures that stay inside the canvas and still ruin a figure: a
    caption wider than the card it sits in, and two labels printed over each
    other. Check 2b only catches text leaving the CANVAS, so both used to pass
    every automated check and were visible only to a reader."""
    import lint as _lint
    over = ('<svg xmlns="http://www.w3.org/2000/svg" width="400" height="200">'
            '<rect width="400" height="200" fill="#FFFFFF"/>'
            '<rect x="40" y="60" width="120" height="50" rx="12" fill="#E9ECEF"/>'
            '<text x="100" y="90" font-size="17" text-anchor="middle" '
            'fill="#1F2933">这段文字明显比框宽得多放不下</text></svg>')
    w = _lint.lint_svg(over)
    assert any("overflows its box" in x for x in w), f"box overflow not caught: {w}"
    clash = ('<svg xmlns="http://www.w3.org/2000/svg" width="400" height="200">'
             '<rect width="400" height="200" fill="#FFFFFF"/>'
             '<text x="200" y="100" font-size="17" text-anchor="middle" '
             'fill="#1F2933">保证期间届满</text>'
             '<text x="200" y="103" font-size="17" text-anchor="middle" '
             'fill="#1F2933">诉讼时效中断</text></svg>')
    w = _lint.lint_svg(clash)
    assert any("overlaps text" in x for x in w), f"text collision not caught: {w}"


@check("lint · every shipped layout, in every mode, is lint-clean")
def _():
    """Including the two checks above — so a change that starts pushing captions
    out of their modules, or stacking labels, fails here rather than in a bundle."""
    import lint as _lint
    import render as _render
    for name, mod, fx in _PPTX_CASES:
        for mode in (None, "mono", "guizang"):
            svg, W, H = mod.render(load(fx))
            if mode == "mono":
                svg = _render.to_monochrome(svg)
            elif mode == "guizang":
                svg = _render.to_guizang(svg, load(fx).get("layout"))
            w = _lint.lint_svg(svg)
            assert not w, f"{name}/{mode or 'qichuan'}: {w[:2]}"


@check("post-processing · no mode transform silently matches NOTHING")
def _():
    """The two mode transforms rewrite a generated SVG with regexes. The failure
    that has actually bitten this project is not the number of regexes — it is one
    that STOPS MATCHING when its target moves (a title at y=46 instead of 44, an
    extra attribute before x=, a nested data-role). The substitution quietly does
    nothing, the run completes, and the figure is wrong in a way only a human
    looking at pixels would catch. Every step that must fire now records a miss;
    this asserts there are none, for every layout."""
    import render as _render
    for name, mod, fx in _PPTX_CASES:
        svg, _, _ = mod.render(load(fx))
        _render._sub_reset()
        _render.to_guizang(svg, load(fx).get("layout"))
        misses = list(_render._SUB_MISSES)
        assert not misses, (f"{name}: 歸藏风 post-processing matched nothing for "
                            f"{misses} — the renderer's output moved out from under it")


@check("delivery · the output folder holds deliverables only, no scratch files")
def _():
    """The PNG is produced by going through a PDF, and that PDF was being left in
    the OUTPUT folder beside the figure — so the lawyer received a stray file they
    never asked for, in a folder they may well forward as-is."""
    import render as _render, tempfile, os as _os, shutil
    if not shutil.which("soffice"):
        return
    d = tempfile.mkdtemp()
    try:
        _render.main(os.path.join(HERE, "..", "examples", "flowchart.json"),
                     _os.path.join(d, "fig"))
    except Exception:
        shutil.rmtree(d, ignore_errors=True)
        return
    left = sorted(_os.listdir(d))
    shutil.rmtree(d, ignore_errors=True)
    allowed = {".svg", ".png", ".pptx", ".vsdx", ".drawio"}
    strays = [f for f in left if _os.path.splitext(f)[1] not in allowed
              and not f.endswith(".drawio.svg")]
    assert not strays, f"scratch files left in the output folder: {strays}"


@check("determinism · the same map renders to the same BYTES, in every format")
def _():
    """The premise of this skill is that a given map always produces a given
    figure. It did not hold for the editable hand-offs: the .pptx and .vsdx parts
    were byte-identical but their ZIP entries carried the wall clock, and the
    .drawio embedded a `modified` timestamp. Beyond the principle, it broke the
    safety net this project leans on hardest — byte-comparing a rebuild against a
    known-good one worked for the SVG and silently could not work for the other
    three."""
    import export_drawio, export_pptx, export_vsdx, tempfile, os as _os, hashlib
    def digest(f):
        return hashlib.md5(open(f, "rb").read()).hexdigest()
    for name, mod, fx in (("flow", render_flow, "ex_flow.json"),
                          ("dated", render_dated, "ex_dated.json")):
        m = load(fx)
        svg, W, H = mod.render(m)
        d = tempfile.mkdtemp()
        sp = _os.path.join(d, "f.svg")
        open(sp, "w", encoding="utf-8").write(svg)
        import zipfile as _zf
        for ext, fn, mod_ in (("pptx", lambda o: export_pptx.export(sp, o), export_pptx),
                              ("vsdx", lambda o: export_vsdx.export(sp, o), export_vsdx)):
            a, b = _os.path.join(d, f"a.{ext}"), _os.path.join(d, f"b.{ext}")
            fn(a); fn(b)
            assert digest(a) == digest(b), f"{name}: .{ext} differs between two identical runs"
            # …and not merely because both ran inside the same second. Comparing
            # two runs passes by luck when the clock does not tick between them,
            # so the archive's stamp is asserted directly.
            with _zf.ZipFile(a) as z:
                stamps = {i.date_time for i in z.infolist()}
            assert stamps == {mod_.ZIP_EPOCH}, \
                f"{name}: .{ext} entries carry the wall clock ({sorted(stamps)[:2]})"
        x1, _, _ = export_drawio.build_model(m)
        x2, _, _ = export_drawio.build_model(m)
        assert x1 == x2, f"{name}: .drawio differs between two identical runs"
        assert "modified=" not in x1, ".drawio embeds a wall-clock timestamp again"


@check("errors · a bad path or bad JSON fails with something you can act on")
def _():
    """The two commonest ways this goes wrong are a mistyped path and a
    hand-edited JSON with a stray comma — and both used to surface as a raw
    traceback. validate_map already reports structural problems clearly; the same
    courtesy belongs one step earlier, where people actually make the mistake. A
    traceback also tells a weaker model nothing it can fix, so it retries."""
    import json as _json
    import tempfile
    from common import load_map
    d = tempfile.mkdtemp()

    try:
        load_map(os.path.join(d, "nope.json"))
        assert False, "a missing file did not raise"
    except RuntimeError as e:
        assert "check the path" in str(e), f"unhelpful message: {e}"
    except Exception as e:
        assert False, f"a missing file raised a raw {type(e).__name__}"

    bad = os.path.join(d, "bad.json")
    open(bad, "w", encoding="utf-8").write('{"layout": "graphviz_flow",}')
    try:
        load_map(bad)
        assert False, "malformed JSON did not raise"
    except RuntimeError as e:
        msg = str(e)
        assert "not valid JSON" in msg and "line" in msg, f"unhelpful message: {msg}"
        assert "trailing comma" in msg, "the message does not name the usual cause"
    except Exception as e:
        assert False, f"malformed JSON raised a raw {type(e).__name__}"

    try:
        load_map(d)
        assert False, "a directory did not raise"
    except RuntimeError as e:
        assert "folder" in str(e), f"unhelpful message: {e}"
    except Exception as e:
        assert False, f"a directory raised a raw {type(e).__name__}"


@check("docs · the README obeys the discipline it describes (grayscale + ONE red)")
def _():
    """奇川风's rule is greyscale with a single deep red, and the emphasis rule is
    "≤2 red, marking the ONE thing that matters". The README was breaking both on
    its own front page: three red badges, two orange, one green — five accent
    colours arguing with each other, on the page that tells you not to do that.

    Third-party brand marks (the Anthropic badges) are exempt, the same way a logo
    is: they identify someone else, they are not our accent."""
    import urllib.parse
    doc = open(os.path.join(HERE, "..", "README.md"), encoding="utf-8").read()
    colours = []
    for u in re.findall(r'shields\.io/badge/([^"]+)', doc):
        d = urllib.parse.unquote(u).split("?")[0]
        if "Claude" in d:                     # someone else's brand mark
            continue
        m = re.search(r"-([0-9A-Fa-f]{6})$", d)
        if m:
            colours.append(m.group(1).upper())
    assert colours, "no badges found"
    reds = [c for c in colours if c == "991B1B"]
    assert len(reds) == 1, f"{len(reds)} red badges — the accent marks ONE thing"
    others = {c for c in colours if c != "991B1B"}
    assert len(others) == 1, f"badges use {len(others)} greys: {sorted(others)} — pick one"


@check("gallery · the README's showcase images are what the code produces TODAY")
def _():
    """The gallery had gone stale without anyone noticing — it was still showing
    flat arrowheads, a blue dot-grid, a stadium time band and 12px 白描 corners,
    months after all four were changed. Nothing regenerates a PNG when behaviour
    changes, so the front page kept advertising last month's output while every
    other guard passed. The images are DERIVED now; this asserts they are current.

    Slow (it renders the whole gallery), so it is skipped unless a rasterizer is
    present — doctor.py reports that, and it is not needed to produce a figure."""
    import shutil
    import make_gallery
    if not (shutil.which("soffice") or shutil.which("rsvg-convert")):
        return
    if not make_gallery.has_cjk_font():
        return                          # labels cannot be drawn, so nothing to compare
    stale = make_gallery.build(check_only=True)
    assert not stale, ("the checked-in gallery no longer matches the code — run "
                       f"`python3 scripts/make_gallery.py`: {stale[:3]}")


@check("gallery · the label rule is sized by the TEXT, not by the panel")
def _():
    """It was sized at `panel_width * 0.24` — a number picked because one was
    needed — so the same label block changed proportion between a 900px panel and
    a 2800px one. The rule is now exactly as wide as the name it underlines.

    Found by COLOUR, not by "the first dark row": scanning for dark pixels picks
    up the name's own glyphs and measures those instead."""
    try:
        from PIL import Image          # noqa: F401
    except ImportError:
        return                          # measuring pixels needs Pillow; doctor reports it
    import make_gallery as _mg
    if not _mg.has_cjk_font():
        return                          # a bare CI runner has no CJK face installed
    from PIL import Image
    import make_gallery
    fig = os.path.join(HERE, "..", "assets", "modes", "flowchart-3modes.png")
    if not os.path.exists(fig):
        return
    im = Image.open(fig).convert("RGB")
    W, panel = im.width, im.width // 3
    RULE = (153, 27, 27)

    def is_rule(px):
        return all(abs(a - b) <= 24 for a, b in zip(px, RULE))

    font = make_gallery._cjk_font(54)
    for i, name in enumerate(("奇川风", "歸藏风", "白描")):
        cx = panel * i + panel // 2
        best = (0, None)
        for y in range(100, 170):
            row = [x for x in range(max(0, cx - 240), min(W, cx + 240))
                   if is_rule(im.getpixel((x, y)))]
            if len(row) > best[0]:
                best = (len(row), (y, row[0], row[-1]))
        assert best[1], f"no rule found under 「{name}」 in the accent colour"
        y, x0, x1 = best[1]
        bb = font.getbbox(name)
        want = bb[2] - bb[0]
        assert abs((x1 - x0) - want) <= 8, (
            f"the rule under 「{name}」 is {x1 - x0}px but the name is {want}px "
            f"— it is sized by the panel again")


@check("gallery · labels use the SIMPLIFIED cut of the Song face, not the Japanese one")
def _():
    """`NotoSerifCJK-*.ttc` is a collection and face 0 is the JAPANESE cut, so
    asking for the file and taking the default silently set 「歸藏风」 in Japanese
    glyph forms — 2252 pixels different from the Simplified form, on a document
    for Chinese lawyers, with nothing to complain about it."""
    try:
        from PIL import Image          # noqa: F401
    except ImportError:
        return                          # measuring pixels needs Pillow; doctor reports it
    import make_gallery as _mg
    if not _mg.has_cjk_font():
        return                          # a bare CI runner has no CJK face installed
    import make_gallery
    f = make_gallery._cjk_font(46)
    fam = f.getname()[0]
    assert fam.endswith("SC"), f"labels are set in {fam!r}, not the Simplified cut"
    assert "Serif" in fam, f"labels are set in {fam!r} — the mode names are a Song face"


@check("gallery · comparison labels are REAL glyphs, not tofu boxes")
def _():
    """A previous release shipped comparison images whose Chinese labels were
    empty boxes: the font in use had no CJK coverage and nothing complained,
    because a renderer draws tofu perfectly happily. Coverage is verified against
    a font that definitely LACKS the glyphs — if the two agree on width, ours is
    drawing boxes too."""
    try:
        from PIL import Image          # noqa: F401
    except ImportError:
        return                          # measuring pixels needs Pillow; doctor reports it
    import make_gallery as _mg
    if not _mg.has_cjk_font():
        return                          # a bare CI runner has no CJK face installed
    from PIL import ImageFont
    import make_gallery
    ok = make_gallery._cjk_font(40)
    bad = None
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",):
        if os.path.exists(p):
            bad = ImageFont.truetype(p, 40)
    if bad is None:
        return
    for label, _ in make_gallery.MODE_SPECS:
        a, b = ok.getbbox(label), bad.getbbox(label)
        assert a != b, f"「{label}」 measures the same in a font without CJK — tofu"
        assert a[2] - a[0] > 0, f"「{label}」 has zero width"


@check("docs · the long-form artwork says nothing the code no longer does")
def _():
    """The long-form graphics are hand-designed and live outside the render path,
    so nothing regenerates them when behaviour changes — they quietly keep
    asserting last version's facts. Three had already drifted: the old mode names,
    "不选就没有红" after the default became an AI-chosen mark, and 白描 described
    as "only colour" after it started squaring corners."""
    import glob
    root = os.path.join(HERE, "..", "assets", "longform")
    if not os.path.isdir(root):
        return
    banned = {
        "奇川流": "the mode was renamed 奇川风",
        "歸藏流": "the mode was renamed 歸藏风",
        "歸葬": "the blogger's name is 歸藏",
        "个人品牌": "that brand belongs to one user, not to the reader",
        "不选就没有红": "an unanswered checkpoint now takes an AI-chosen mark",
        # the CLAIM, not a substring of its replacement: "只换颜色与圆角" is the
        # corrected wording and contains the old phrase inside it
        "几何与奇川风逐字节相同": "白描 keeps positions byte-identical but squares the corners",
    }
    for f in glob.glob(os.path.join(root, "*.svg")):
        t = open(f, encoding="utf-8", errors="replace").read()
        for phrase, why in banned.items():
            assert phrase not in t, \
                f"{os.path.basename(f)} still says 「{phrase}」 — {why}"


@check("portability · every CLI entry point survives a bare Windows-like box")
def _():
    """Reported from a real Windows install: `doctor.py` died with an
    UnboundLocalError. Rather than fix that one line and move on, every entry
    point is now run with the whole Windows situation simulated — no fc-list, no
    LibreOffice, no poppler, no Linux font paths — because the platform most of
    this skill's users are on is the one it was never executed on."""
    import subprocess as _sp
    import tempfile
    import textwrap
    d = tempfile.mkdtemp()
    with open(os.path.join(d, "sitecustomize.py"), "w") as f:
        f.write(textwrap.dedent("""
            import shutil, os.path
            MISSING = {"fc-list","fc-cache","soffice","libreoffice","pdftoppm",
                       "pdftotext","rsvg-convert","inkscape","resvg","npm","tar"}
            _w = shutil.which
            shutil.which = lambda c, *a, **k: None if c in MISSING else _w(c, *a, **k)
            _e = os.path.exists
            os.path.exists = lambda p: False if isinstance(p, str) and \
                p.startswith("/usr/share/fonts") else _e(p)
        """))
    env = dict(os.environ)
    env["PYTHONPATH"] = d + os.pathsep + env.get("PYTHONPATH", "")
    root = os.path.join(HERE, "..")
    ex = os.path.join(root, "examples", "flowchart.json")
    out_base = os.path.join(tempfile.mkdtemp(), "fig")
    cases = [
        ("doctor", ["scripts/doctor.py"], 0),
        ("checkpoint", ["scripts/checkpoint.py", ex], 0),
        ("validate", ["scripts/render.py", "validate", ex], 0),
        ("render", ["scripts/render.py", ex, out_base, "--formats=svg"], 0),
        ("make_gallery", ["scripts/make_gallery.py", "--check"], None),
    ]
    for name, args, want in cases:
        args = [os.path.join(root, args[0])] + args[1:]
        r = _sp.run([sys.executable] + args, capture_output=True, text=True,
                    env=env, timeout=600)
        blob = r.stdout + r.stderr
        assert "Traceback" not in blob, \
            f"{name} ends on a traceback on a bare box:\n{blob[-320:]}"
        if want is not None:
            assert r.returncode == want, f"{name} exited {r.returncode}, expected {want}"


@check("portability · doctor survives a machine with no font tooling (i.e. Windows)")
def _():
    """`fc-list` is a fontconfig tool: Linux and macOS have it, Windows never
    does — and that is where most of this skill's users are. A variable set only
    inside the `else` of "did we get a font list" meant doctor raised
    UnboundLocalError there and died, on the very command a new user runs first.
    Reported from a real Windows install; it could not surface here."""
    import subprocess as _sp
    import tempfile
    import textwrap
    d = tempfile.mkdtemp()
    with open(os.path.join(d, "sitecustomize.py"), "w") as f:
        f.write(textwrap.dedent("""
            import shutil
            _w = shutil.which
            shutil.which = lambda c, *a, **k: None if c == "fc-list" else _w(c, *a, **k)
        """))
    env = dict(os.environ)
    env["PYTHONPATH"] = d + os.pathsep + env.get("PYTHONPATH", "")
    r = _sp.run([sys.executable, os.path.join(HERE, "..", "scripts", "doctor.py")],
                capture_output=True, text=True, env=env, timeout=180)
    out = r.stdout + r.stderr
    assert "Traceback" not in out, f"doctor crashed without fc-list:\n{out[-400:]}"
    assert "UnboundLocalError" not in out, "a variable is defined on only one branch"
    assert r.returncode == 0, f"doctor exited {r.returncode} on a machine with no fc-list"
    assert "Result:" in out, "doctor produced no verdict"


@check("portability · every path in the repo is ASCII")
def _():
    """Windows PowerShell's `Expand-Archive` reads ZIP entry names in the system
    code page, not UTF-8, so Chinese filenames come out as mojibake and the
    extraction fails outright with "路径中具有非法字符". Anyone on Windows who
    downloads the repo as a ZIP — which is how most people get it — hits this.
    The content is Chinese; the paths do not have to be."""
    root = os.path.join(HERE, "..")
    bad = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ("__pycache__", ".git")]
        for n in dirnames + filenames:
            if any(ord(c) > 127 for c in n):
                bad.append(os.path.relpath(os.path.join(dirpath, n), root))
    assert not bad, f"non-ASCII paths break ZIP extraction on Windows: {bad[:5]}"


@check("docs · every image the README references actually exists")
def _():
    """A broken image on the front page is the cheapest possible way to look
    unfinished, and nothing else here would notice one."""
    root = os.path.join(HERE, "..")
    doc = open(os.path.join(root, "README.md"), encoding="utf-8").read()
    missing = []
    for src in re.findall(r'<img src="([^"]+)"', doc):
        if src.startswith("http"):
            continue
        if not os.path.exists(os.path.join(root, src)):
            missing.append(src)
    assert not missing, f"README references images that do not exist: {missing}"


@check("docs · README badges are current, INCLUDING what is percent-encoded")
def _():
    """The badges are the first thing anyone sees, and their Chinese is
    percent-encoded — so `grep 葬` could not see them and the blogger's
    mis-spelled name survived every sweep, on the front page, long after it was
    fixed everywhere else. Decode before checking."""
    import urllib.parse
    doc = open(os.path.join(HERE, "..", "README.md"), encoding="utf-8").read()
    badges = [urllib.parse.unquote(u) for u in
              re.findall(r'shields\.io/badge/([^"]+)', doc)]
    joined = " ".join(badges)
    for stale in ("歸葬", "奇川流", "歸藏流", "白描流"):
        assert stale not in joined, f"a badge still says 「{stale}」"
    src = open(os.path.join(HERE, "run_checks.py"), encoding="utf-8").read()
    n = len(re.findall(r"^@check\(", src, re.M))
    m = re.search(r"tests-(\d+) checks", joined)
    assert m, "no test-count badge"
    assert int(m.group(1)) == n, \
        f"the tests badge says {m.group(1)}, there are {n} guards"
    ver = re.search(r"version-([\d.]+)-", joined)
    assert ver, "no version badge"
    std = open(os.path.join(HERE, "..", "references", "STANDARDS.md"), encoding="utf-8").read()
    assert f"v{ver.group(1)}" in std, \
        f"the version badge says {ver.group(1)}, which STANDARDS.md does not mention"
    # …and the footer, which is where the version was last left behind
    assert f"v{ver.group(1)}" in doc.strip().splitlines()[-1], \
        "the README footer carries a different version from the badge"


@check("docs · the guard count STANDARDS.md cites is the real one")
def _():
    """This number has drifted twice — 53 when it was 78, then 99 when it was 120.
    A count in prose is a fact about the code that nothing was checking, so it
    rots every time a guard is added. Checking it turns a recurring quiet
    inaccuracy into a one-line fix at the moment it happens."""
    doc = open(os.path.join(HERE, "..", "references", "STANDARDS.md"),
               encoding="utf-8").read()
    m = re.search(r"(\d+) regression guards, (\d+) assertions", doc)
    assert m, "STANDARDS.md no longer states a guard count"
    src = open(os.path.join(HERE, "run_checks.py"), encoding="utf-8").read()
    # count DECORATOR LINES, not occurrences of the string: this guard's own body
    # mentions "@check", and counting text made it report itself twice
    real_checks = len(re.findall(r"^@check\(", src, re.M))
    assert int(m.group(1)) == real_checks, (
        f"STANDARDS.md says {m.group(1)} guards, there are {real_checks}")
    # RESULTS is still being filled while this runs — this guard is one of the
    # entries — so compare against the decorator count plus the ones that report
    # more than one result. Simplest reliable form: the doc's assertion count must
    # be at least the guard count and must match once the suite has finished.
    assert int(m.group(2)) >= real_checks, (
        f"STANDARDS.md says {m.group(2)} assertions but there are {real_checks} guards")
    _DOC_ASSERTIONS.append(int(m.group(2)))


@check("tuning · the gathered constants are LIVE, not a decorative table")
def _():
    """These were magic numbers scattered through the renderers, arrived at by
    looking at output. Gathering them only helps if the code actually reads them:
    a table nobody consults is worse than the numbers it replaced, because it
    invites someone to 'adjust' a value that changes nothing."""
    import importlib
    import json as _json
    import common as _c
    tuning = _c.TOKENS["tuning"]
    knobs = [k for k in tuning if not k.startswith("_") and not k.endswith("_note")]
    assert len(knobs) >= 8, f"only {len(knobs)} constants gathered"
    for k in knobs:
        assert isinstance(tuning[k], (int, float)), f"{k} is not a number"
    # each documented knob must be explained
    for k in ("guizang_title_ratio", "guizang_top_margin", "guizang_decision_lift",
              "flow_ranksep", "relation_side_label_bias", "guizang_pad_x"):
        assert k in tuning, f"{k} was dropped from the tuning table"
    # and they must be WIRED: nudging one has to move the figure
    import render as _r, render_flow as _rf
    before, _, _ = _rf.render(load("ex_flow.json"))
    old = tuning["flow_ranksep"]
    try:
        tuning["flow_ranksep"] = old + 0.7
        importlib.reload(_rf)
        _rf.TOKENS["tuning"]["flow_ranksep"] = old + 0.7
        after, _, _ = _rf.render(load("ex_flow.json"))
    finally:
        tuning["flow_ranksep"] = old
        importlib.reload(_rf)
    assert after != before, "flow_ranksep is in the table but nothing reads it"


@check("checkpoint · the three questions are GENERATED, identically every time")
def _():
    """The consequences of these answers are enforced deterministically, so the
    questions must be too: a question a hurried model might drop, shorten or
    garble is not a reliable question."""
    import checkpoint as _cp
    for fx in ("ex_flow.json", "ex_relation.json", "ex_dated.json", "ex_gantt.json"):
        m = load(fx)
        out = _cp.render_questions(m)
        assert out == _cp.render_questions(m), f"{fx}: not reproducible"
        # all three questions present
        for want in ("① 结构", "② 风格", "③ 重点"):
            assert want in out, f"{fx}: the checkpoint dropped 「{want}」"
        # all three modes, each with what it LOOKS like and what it is FOR
        for mode in ("奇川风", "白描", "歸藏风"):
            assert mode in out, f"{fx}: mode {mode} not offered"
        for _n, _f, look, use in _cp.MODES:
            assert look in out and use in out, \
                f"{fx}: a mode is offered without its look or its use"
        # the emphasis question must offer BOTH escape hatches plus real candidates
        assert "回 0 = 全图不标红" in out, f"{fx}: no way to decline the red"
        assert "由我挑一处" in out or "采纳建议" in out, f"{fx}: no way to delegate the red"
        cands = _cp.candidates(m)
        assert cands, f"{fx}: no emphasis candidates offered"
        if len(cands) <= 10:
            assert all(str(c["label"])[:8] in out for c in cands[:3]), \
                f"{fx}: candidates are not actually listed"
        else:
            assert f"共 {len(cands)} 处可选" in out, \
                f"{fx}: a long candidate list is neither shown nor accounted for"
        # and the defaults must be stated, not left implicit
        assert "不回 = 1" in out, f"{fx}: the style default is not stated"
        assert "-draft" in out, f"{fx}: the draft consequence is not disclosed"


@check("checkpoint · the layout is SHOWN with its reason and its real alternatives")
def _():
    """The layout is decided by the data, not by taste (extraction-guide's ladder),
    so it is presented as a reading to correct — with why it was chosen and the
    forms this data could actually be swapped to — rather than as a free menu that
    would offer shapes the data cannot support."""
    import checkpoint as _cp
    seen = set()
    for fx in ("ex_flow.json", "ex_relation.json", "ex_tree.json", "ex_dated.json",
               "ex_gantt.json", "ex_points.json", "ex_compare.json"):
        m = load(fx)
        lay = m.get("layout")
        assert lay in _cp.LAYOUT_WHY, f"{lay}: no name/reason — the menu would show a raw id"
        name, why = _cp.LAYOUT_WHY[lay]
        out = _cp.render_questions(m)
        assert name in out and why in out, f"{fx}: layout shown without its reason"
        fam, others = _cp._siblings(lay)
        assert fam, f"{lay} belongs to no family"
        for o in others:
            assert o in out, f"{fx}: sibling {o} not offered"
        seen.add(lay)
    assert seen == set(_cp.LAYOUT_WHY), "a layout exists that the checkpoint cannot describe"


@check("checkpoint · the three modes appear in the author's order, everywhere")
def _():
    """奇川风 → 歸藏风 → 白描. Fixed, so a reader meets them the same way in the
    menu, in SKILL.md and in the README rather than having to re-orient."""
    import checkpoint as _cp
    assert tuple(m[0] for m in _cp.MODES) == _cp.MODE_ORDER, "the menu is out of order"
    out = _cp.render_questions(load("ex_flow.json"))
    at = [out.find(m) for m in _cp.MODE_ORDER]
    assert all(x >= 0 for x in at), "a mode is missing from the menu"
    assert at == sorted(at), f"the rendered menu lists the modes out of order"
    here = os.path.join(HERE, "..")
    for doc in ("SKILL.md", "README.md", "references/visual-style.md"):
        path = os.path.join(here, doc)
        if not os.path.exists(path):
            continue
        t = open(path, encoding="utf-8").read()
        firsts = [(t.find(m), m) for m in _cp.MODE_ORDER if t.find(m) >= 0]
        assert [m for _, m in sorted(firsts)] == [m for _, m in firsts], \
            f"{doc} introduces the modes out of order"


@check("checkpoint · no mode is sold by whose style it is")
def _():
    """These mode names are the author's; the people reading this menu are other
    lawyers choosing a look for their own case file. "Personal brand" is a reason
    that belongs to exactly one user, and it does not belong in their interface."""
    import checkpoint as _cp
    banned = ("个人品牌", "品牌", "brand")
    for name, flag, look, use in _cp.MODES:
        for b in banned:
            assert b not in look and b not in use, \
                f"{name} is described in terms of branding: {look!r} / {use!r}"
    out = _cp.render_questions(load("ex_flow.json"))
    for b in banned:
        assert b not in out, f"the checkpoint text still sells a mode as a brand ({b})"
    assert "推荐" in out, "the default mode is not marked as the recommended one"


@check("checkpoint · an edge is offered by its endpoints' TITLES, not internal ids")
def _():
    """The user is being asked which relationship the case turns on. "n3 → n4" is
    not something anyone can weigh."""
    import checkpoint as _cp
    m = load("ex_relation.json")
    edges = [c for c in _cp.candidates(m) if c["host"] == "edges"]
    assert edges, "fixture has no edges"
    ids = {n.get("id") for n in m.get("nodes", [])}
    for c in edges:
        head = c["label"].split(" → ")[0]
        assert head not in ids, f"an edge is shown by raw id: {c['label']!r}"


@check("emphasis · 'checkpoint' is a recognised field, so the record is not dropped")
def _():
    from common import validate_map
    m = load("ex_relation.json")
    m["checkpoint"] = {"emphasis_source": "user", "confirmed": True}
    out = validate_map(m)
    warns = out if isinstance(out, (list, tuple)) else []
    assert not any("checkpoint" in str(w) for w in warns), \
        "the checkpoint record is reported as an unknown field and would be ignored"
    from common import _TOP_KEYS
    assert "checkpoint" in _TOP_KEYS, "checkpoint is not on the accepted-field list"


@check("delivery · an unconfirmed figure is named *-draft, a confirmed one is not")
def _():
    """A printed `>> CHECKPOINT REQUIRED` scrolls away; a filename travels with
    the file into the folder, the email and the bundle. The failure this guards
    against is specific and expensive: a draft read of a judgment filed as final."""
    import render as _render
    m = load("ex_relation.json")
    m.pop("checkpoint", None)
    b, draft = _render._draft_base("/tmp/x/fig", m)
    assert draft and b.endswith("-draft"), "an unconfirmed map is not marked as a draft"
    m["checkpoint"] = {"confirmed": False}
    b, draft = _render._draft_base("/tmp/x/fig", m)
    assert draft and b.endswith("-draft"), "confirmed=false is not marked as a draft"
    m["checkpoint"] = {"confirmed": True}
    b, draft = _render._draft_base("/tmp/x/fig", m)
    assert not draft and b == "/tmp/x/fig", "a confirmed map was still marked as a draft"
    # truthy-but-not-true must not pass: only an explicit confirmation counts
    for sneaky in ("yes", 1, "true"):
        m["checkpoint"] = {"confirmed": sneaky}
        _, draft = _render._draft_base("/tmp/x/fig", m)
        assert draft, f"confirmed={sneaky!r} was accepted as an explicit confirmation"


@check("delivery · every editable format is written by DEFAULT")
def _():
    """The premise of this skill is that the lawyer's own tool is not ours to
    guess — draw.io, PowerPoint, ProcessOn, Visio and WPS are all in real use."""
    import render as _render
    assert set(_render.ALL_FORMATS) == {"svg", "png", "drawio", "pptx", "vsdx"}, \
        "the default delivery set changed"
    import inspect
    sig = inspect.signature(_render.main)
    assert sig.parameters["formats"].default == _render.ALL_FORMATS, \
        "main() no longer defaults to the full delivery set"


@check("pptx · connectors carry a native arrowhead")
def _():
    for name, mod, fx in (("flow", render_flow, "ex_flow.json"),
                          ("relation", render_relation, "ex_relation.json")):
        slide, _, svg, _ = _pptx_bytes(mod, load(fx))
        assert "a:tailEnd" in slide, f"{name}: connectors carry no arrowhead"
        n_svg = len(re.findall(r'marker-end="url\(#', svg))
        n_ppt = slide.count("a:tailEnd")
        assert n_ppt >= n_svg, f"{name}: {n_svg} arrows in the figure but {n_ppt} in the deck"


@check("pptx · all three visual modes transcribe (奇川风 / 歸藏风 / 白描)")
def _():
    for mode in (None, "guizang", "mono"):
        slide, _, svg, _ = _pptx_bytes(render_flow, load("ex_flow.json"), mode)
        assert "<a:t>" in slide and "<p:sp>" in slide, f"mode {mode}: empty deck"
        if mode == "guizang":
            assert "002FA7" in slide, "歸藏风 deck lost the Klein blue"
        if mode == "mono":
            assert "991B1B" not in slide, "白描 deck still carries the deep red"


@check("aesthetic · cross-platform font stack (PingFang→YaHei→Noto)")
def _():
    for fx, mod in (("ex_points.json", render_points), ("ex_flow.json", render_flow)):
        svg, _, _ = mod.render(load(fx))
        assert "PingFang SC" in svg and "Noto Sans CJK SC" in svg, f"{fx} font stack incomplete"


@check("aesthetic · cards use rx=12 (not hard corners)")
def _():
    svg, _, _ = render_flow.render(load("ex_flow.json"))
    assert 'rx="12"' in svg, "step cards are not rx=12"


@check("aesthetic · neutral gray (no blue-ish slate #64748B / #0F172A)")
def _():
    for fx, mod in (("ex_flow.json", render_flow), ("ex_relation.json", render_relation),
                    ("ex_points.json", render_points)):
        svg, _, _ = mod.render(load(fx))
        assert "#64748B" not in svg and "#0F172A" not in svg, f"{fx} still uses slate palette"


@check("aesthetic · flowchart edge labels ride beside the line, no masking box")
def _():
    svg, _, _ = render_flow.render(load("edge_loop.json"))
    edges = svg.split('data-role="edges"')[1].split('data-role="nodes"')[0]
    assert "<rect" not in edges, "edge labels still draw a masking box over the connector"
    assert 'font-weight="600"' in edges, "branch label weight missing"


# ---- 5. delivery path: the audit summary must actually run --------------
import io, contextlib

def _quiet_report(m):
    import audit
    with contextlib.redirect_stdout(io.StringIO()):
        return audit.report(m)

@check("delivery · audit module imports and reports (never silently dead)")
def _():
    import audit  # must not raise (regression: FS['label'] KeyError once killed this)
    for fx in ("ex_points.json", "ex_gantt.json", "ex_flow.json", "ex_relation.json"):
        r = _quiet_report(load(fx))
        assert set(("elements", "red", "uncertainties")) <= set(r), f"{fx} audit missing keys"
        assert r["elements"] > 0, f"{fx} audit counted no elements"


@check("delivery · audit red-count matches the diagram's emphasized elements")
def _():
    for fx in ("ex_points.json", "ex_gantt.json", "ex_flow.json", "ex_relation.json"):
        m = load(fx)
        expected = sum(1 for k in ("events", "spans", "points", "nodes", "edges")
                       for it in m.get(k, []) if it.get("emphasis"))
        assert _quiet_report(m)["red"] == expected, f"{fx} audit red-count wrong"


# ---- 6. CJK typography: line-breaking (禁则/kinsoku) --------------------
NO_START = "，。、；：！？）】》」』%’”…—"   # a line must never BEGIN with these
NO_END = "（【《「『‘“"                        # a line must never END with these

@check("typography · wrapped CJK lines never start with closing punctuation")
def _():
    from common import wrap
    samples = [
        "甲邮寄催款函，乙签收，丙拒收，全部拒绝履行还款义务并失去联系",
        "认定丙的抗辩理由不成立；判令其承担连带清偿责任（本金及利息）",
        "签订借款合同和保证承诺书，约定由丙提供连带责任保证担保",
    ]
    for s in samples:
        for w in (80, 120, 160, 200):
            for ln in wrap(s, 17, w):
                assert ln[0] not in NO_START, f"line starts with '{ln[0]}': {ln!r} (w={w})"
                assert ln[-1] not in NO_END, f"line ends with '{ln[-1]}': {ln!r} (w={w})"


@check("typography · wrapping is still verbatim (no chars added or dropped)")
def _():
    from common import wrap
    for s in ("甲邮寄催款函，乙签收，丙拒收", "认定丙的抗辩理由不成立（终局）", "abc，def。ghi"):
        for w in (60, 100, 140):
            assert "".join(wrap(s, 15, w)) == s, f"wrap altered text: {s!r} (w={w})"


# ---- 7. relation_tree charting standard (frozen) -----------------------
import re as _re

def _tree_nodes(svg):
    """Parse node rects from a relation_tree SVG: id -> (cx, w, h, fill)."""
    out = {}
    for mm in _re.finditer(
        r'data-id="([^"]+)">\s*<rect x="([0-9.]+)" y="([0-9.]+)" '
        r'width="([0-9.]+)" height="([0-9.]+)" rx="12" fill="([^"]+)"', svg):
        nid, x, y, w, h, fill = mm.groups()
        out[nid] = (float(x)+float(w)/2, float(w), float(h), fill)
    return out


@check("tree-std · every fork is symmetric (parent centered on its children)")
def _():
    m = load("ex_tree.json")
    svg, _, _ = render_tree.render(m)
    nd = _tree_nodes(svg)
    kids = {}
    for e in m["edges"]:
        kids.setdefault(e["from"], []).append(e["to"])
    for p, ks in kids.items():
        pcx = nd[p][0]
        mean = sum(nd[k][0] for k in ks) / len(ks)
        assert abs(mean - pcx) < 1.0, f"fork under {p} not symmetric (parent off-center by {mean-pcx:.1f})"
        # left/right extents from the parent are basically equal
        offs = sorted(nd[k][0] - pcx for k in ks)
        assert abs(abs(offs[0]) - abs(offs[-1])) < 1.5, f"fork under {p} has unequal L/R spread"


@check("tree-std · uniform box height across all levels")
def _():
    svg, _, _ = render_tree.render(load("ex_tree.json"))
    hs = {round(v[2], 1) for v in _tree_nodes(svg).values()}
    assert len(hs) == 1, f"box heights not uniform: {hs}"


@check("tree-std · uniform box width within each level")
def _():
    m = load("ex_tree.json")
    svg, _, _ = render_tree.render(m)
    nd = _tree_nodes(svg)
    lvl = render_tree._levels(m)
    by_level = {}
    for nid, (_, w, _, _) in nd.items():
        by_level.setdefault(lvl[nid], set()).add(round(w, 1))
    for L, ws in by_level.items():
        assert len(ws) == 1, f"level {L} widths not uniform: {ws}"


@check("tree-std · bracket connectors are rounded (r≈2.5), no arrowheads by default")
def _():
    svg, _, _ = render_tree.render(load("ex_tree.json"))
    edges = svg.split('data-role="edges"')[1].split('data-role="nodes"')[0]
    assert "Q " in edges, "tree connectors are not rounded (no quadratic corners)"
    assert "marker-end" not in edges, "tree drew arrowheads though arrows default off"


@check("tree-std · depth shading (dark root, light leaves) + red discipline")
def _():
    m = load("ex_tree.json")
    svg, _, _ = render_tree.render(m)
    nd = _tree_nodes(svg)
    lvl = render_tree._levels(m)
    maxl = max(lvl.values())
    roots = [n for n in nd if lvl[n] == 0]
    leaves = [n for n in nd if lvl[n] == maxl]
    assert any(nd[r][3] == "#374151" for r in roots), "root not dark-shaded"
    assert any(nd[l][3] in ("#EDEFF2",) for l in leaves), "leaves not light-shaded"
    red = sum(1 for n in m["nodes"] if n.get("emphasis")) + sum(1 for e in m["edges"] if e.get("emphasis"))
    assert red <= 2, f"tree uses {red} reds (max 2)"


# ---- 8. flowchart charting standard (frozen) ---------------------------
@check("flow-std · all step boxes share one uniform width")
def _():
    m = load("ex_flow_parallel.json")
    geo = flow_geo(m)
    kind = {n["id"]: n.get("kind", "step") for n in m["nodes"]}
    ws = {round(geo[nid]["right"] - geo[nid]["left"], 1)
          for nid in geo if kind.get(nid, "step") == "step"}
    assert len(ws) == 1, f"step boxes not one uniform width: {ws}"


@check("flow-std · connectors are straight-first (few needless bends)")
def _():
    svg, _, _ = render_flow.render(load("ex_flow_parallel.json"))
    edges = svg.split('data-role="edges"')[1].split('data-role="nodes"')[0]
    paths = re.findall(r'<path d="([^"]+)"', edges)
    straight = sum(1 for p in paths if "Q" not in p)
    bent = sum(1 for p in paths if "Q" in p)
    assert straight >= bent, f"too many bent connectors: {straight} straight vs {bent} bent"


@check("flow-std · title sits over the content center (symmetric framing)")
def _():
    svg, W, H = render_flow.render(load("ex_flow_parallel.json"))
    tx = float(re.search(r'<text x="([0-9.]+)" y="44"', svg).group(1))
    xs = []
    for mm in re.finditer(r'<rect x="([0-9.]+)"[^>]*width="([0-9.]+)"', svg):
        x, w = float(mm.group(1)), float(mm.group(2))
        xs += [x, x + w]
    content_center = (min(xs) + max(xs)) / 2
    assert abs(tx - content_center) < 2, f"title x={tx:.0f} not over content center {content_center:.0f}"
    assert abs(tx - W / 2) < 2, f"title x={tx:.0f} not at canvas center {W/2:.0f}"


# ---- 9. hardening: schema_version + final-SVG lint ---------------------
@check("schema · every example declares schema_version 1")
def _():
    import glob
    exdir = os.path.join(HERE, "..", "examples")
    files = glob.glob(os.path.join(exdir, "*.json"))
    assert files, "no examples found"
    for p in files:
        m = json.load(open(p, encoding="utf-8"))
        assert m.get("schema_version") == 1, f"{os.path.basename(p)} missing schema_version:1"


@check("schema · validate rejects an unsupported schema_version")
def _():
    from common import validate_map
    m = load("ex_points.json"); m = dict(m); m["schema_version"] = 2
    try:
        validate_map(m)
        assert False, "bad schema_version not caught"
    except RuntimeError as e:
        assert "schema_version" in str(e), "message not actionable"


@check("schema · validate error names the offending element id")
def _():
    from common import validate_map
    m = load("ex_dated.json"); m = json.loads(json.dumps(m))
    m["events"][0].pop("date", None)
    try:
        validate_map(m)
        assert False, "missing date not caught"
    except RuntimeError as e:
        assert '"1"' in str(e), "error does not name the event id"


@check("lint · rendered example SVGs are clean (no off-canvas / non-finite / diagonal arrow)")
def _():
    for fx, mod in (("ex_points.json", render_points), ("ex_dated.json", render_dated),
                    ("ex_gantt.json", render_spans), ("ex_flow.json", render_flow),
                    ("ex_relation.json", render_relation), ("ex_tree.json", render_tree),
                    ("ex_flow_parallel.json", render_flow)):
        svg, w, h = mod.render(load(fx))
        warns = lint.lint_svg(svg, w, h)
        assert not warns, f"{fx} lint: {warns}"


@check("lint · rejected blue/slate colour is caught")
def _():
    warns = lint.lint_svg('<svg width="50" height="50"><rect fill="#64748B" x="0" y="0" width="5" height="5"/></svg>', 50, 50)
    assert any("blue/slate" in w for w in warns), "slate colour not flagged by lint"


@check("lint · dangling url(#id) reference is caught")
def _():
    warns = lint.lint_svg('<svg width="9" height="9"><path marker-end="url(#ghost)" d="M0,0 L0,9"/></svg>', 9, 9)
    assert any("dangling reference" in w for w in warns), "dangling url(#id) not flagged"


# ---- 10. extraction discipline (pillar 1: read/analyze/decompose) -------
@check("extraction · audit flags emphasis overuse (>2 reds)")
def _():
    m = {"nodes": [{"id": str(i), "title": "x", "emphasis": True} for i in range(4)],
         "provenance": {"text_policy": "verbatim"}}
    r = _quiet_report(m)
    assert any("emphasis discipline" in n for n in r["notes"]), "red overuse not flagged"


@check("extraction · uncertainties force the checkpoint gate")
def _():
    m = {"events": [{"id": "1", "text": "x"}], "provenance": {"uncertainties": ["smudged date"]}}
    assert _quiet_report(m)["checkpoint_required"], "uncertainties did not trigger checkpoint"


@check("extraction · AI-chosen emphasis forces the checkpoint gate")
def _():
    m = {"nodes": [{"id": "1", "title": "x", "emphasis": True}],
         "provenance": {"text_policy": "verbatim", "emphasis_note": "AI建议：待确认"}}
    assert _quiet_report(m)["checkpoint_required"], "emphasis_note did not trigger checkpoint"


@check("extraction · a clean, fully-certain map needs no checkpoint")
def _():
    m = {"events": [{"id": "1", "text": "签约", "emphasis": True}],
         "provenance": {"text_policy": "verbatim"}}  # source-marked red, nothing uncertain
    assert not _quiet_report(m)["checkpoint_required"], \
        "clean map wrongly demanded a checkpoint"


@check("extraction · extraction-guide.md exists and covers the six steps")
def _():
    p = os.path.join(HERE, "..", "references", "extraction-guide.md")
    assert os.path.exists(p), "extraction-guide.md missing"
    txt = open(p, encoding="utf-8").read()
    for step in ("Step 1", "Step 2", "Step 3", "Step 4", "Step 5", "Step 6"):
        assert step in txt, f"extraction-guide missing {step}"
    assert "spine" in txt.lower() and "verbatim" in txt.lower(), "guide missing core discipline"


@check("timeline-select · dated form rejects an unparseable event date")
def _():
    m = {"schema_version": 1, "layout": "dated_point_timeline", "title_text": "t",
         "events": [{"id": "1", "date": "2013年", "date_text": "2013年", "text": "x"}]}
    try:
        render_dated.render(m)
        assert False, "unparseable date not rejected"
    except Exception as e:
        assert "1" in str(e) or "date" in str(e).lower(), "error not actionable"


@check("timeline-select · extraction-guide documents the ordered decision ladder")
def _():
    p = os.path.join(HERE, "..", "references", "extraction-guide.md")
    txt = open(p, encoding="utf-8").read()
    assert "decision ladder" in txt.lower() or "first match wins" in txt.lower(), "ladder missing"
    assert "safe default" in txt.lower() and "numbered_point_timeline" in txt, "default rule missing"


@check("extraction · guide covers text-only (judgment) input & multi-diagram split")
def _():
    p = os.path.join(HERE, "..", "references", "extraction-guide.md")
    txt = open(p, encoding="utf-8").read()
    assert "text-only" in txt.lower(), "no text-only source section"
    assert "本院认为" in txt and "condensed_from_prose" in txt, "fact-vs-argument / prose fidelity rule missing"
    assert "Multi-diagram" in txt or "companion diagram" in txt.lower(), "multi-diagram guidance missing"


@check("flow-std · decision is a rounded hexagon (6 rounded corners), never a diamond")
def _():
    svg, _, _ = render_flow.render(load("ex_flow.json"))
    # find the decision node by its SHAPE, not by a stroke width — pinning the
    # guard to "1.4" made it fail the moment hairlines were normalised to whole
    # pixels, while telling us the hexagon had vanished, which it had not
    hexes = [d for d in re.findall(r'<path d="([^"]+)" fill="[^"]+" stroke="', svg)
             if d.count("Q") == 6]
    assert hexes, "no decision hexagon path found"
    for d in hexes:
        assert d.count("Q") == 6, f"decision not a 6-corner rounded hexagon ({d.count('Q')} corners)"
    assert "<polygon" not in svg, "a diamond/polygon decision shape is still present"


@check("relation · relation_tree refuses a network (multi-parent) → graphviz_relation")
def _():
    m = {"schema_version": 1, "layout": "relation_tree", "title_text": "t",
         "nodes": [{"id": "a", "title": "A"}, {"id": "b", "title": "B"}, {"id": "c", "title": "C"}],
         "edges": [{"from": "a", "to": "c"}, {"from": "b", "to": "c"}]}
    try:
        render_tree.render(m)
        assert False, "multi-parent not refused"
    except RuntimeError as e:
        assert "graphviz_relation" in str(e), "error does not redirect to graphviz_relation"


@check("relation · cross-row edges route orthogonally, never diagonal")
def _():
    m = {"schema_version": 1, "layout": "graphviz_relation", "engine": "dot", "direction": "TB",
         "title_text": "t",
         "nodes": [{"id": "a", "title": "顶层公司"}, {"id": "b", "title": "子公司"}],
         "edges": [{"from": "a", "to": "b", "label": "控股"}]}
    svg, w, h = render_relation.render(m)
    assert not lint.lint_svg(svg, w, h), f"relation produced lint warnings: {lint.lint_svg(svg,w,h)}"


@check("direction · audit reports entry/exit points for arrow-direction review")
def _():
    m = {"nodes": [{"id": "a", "title": "输入"}, {"id": "b", "title": "处理"}, {"id": "c", "title": "输出"}],
         "edges": [{"from": "a", "to": "b"}, {"from": "b", "to": "c"}], "provenance": {}}
    d = _quiet_report(m)["direction"]
    assert d["sources"] == ["a"] and d["sinks"] == ["c"], f"entry/exit wrong: {d}"


@check("direction · a reversed arrow shows up as a lost entry point")
def _():
    # b->a reversed (should be a->b): now 'a' has an incoming edge, no longer a source
    m = {"nodes": [{"id": "a", "title": "输入"}, {"id": "b", "title": "处理"}, {"id": "c", "title": "输出"}],
         "edges": [{"from": "b", "to": "a"}, {"from": "b", "to": "c"}], "provenance": {}}
    d = _quiet_report(m)["direction"]
    assert "a" not in d["sources"], "reversed arrow not detectable via entry points"


@check("direction · a full cycle is flagged (no entry/exit)")
def _():
    m = {"nodes": [{"id": "a", "title": "A"}, {"id": "b", "title": "B"}],
         "edges": [{"from": "a", "to": "b"}, {"from": "b", "to": "a"}], "provenance": {}}
    d = _quiet_report(m)["direction"]
    assert not d["sources"] and not d["sinks"], "cycle not flagged as no-entry/no-exit"


@check("flow-std · LR flow routes cleanly (no floating/short final segments)")
def _():
    import math, re as _re
    m = {"schema_version":1,"layout":"graphviz_flow","direction":"LR","title_text":"t",
         "nodes":[{"id":"a","kind":"step","title":"输入甲"},{"id":"b","kind":"step","title":"输入乙"},
                  {"id":"m","kind":"step","title":"汇聚"},{"id":"x","kind":"step","title":"输出一"},
                  {"id":"y","kind":"step","title":"输出二"}],
         "edges":[{"from":"a","to":"m"},{"from":"b","to":"m"},{"from":"m","to":"x"},{"from":"m","to":"y"}]}
    svg,w,h = render_flow.render(m)
    assert not lint.lint_svg(svg,w,h), f"LR flow lint: {lint.lint_svg(svg,w,h)}"
    edges = svg.split('data-role="edges"')[1].split('data-role="nodes"')[0]
    for d in _re.findall(r'<path d="([^"]+)"', edges):
        P=[(float(x),float(y)) for x,y in _re.findall(r"([0-9.]+),([0-9.]+)", d)]
        if len(P)>=2:
            fin=math.hypot(P[-1][0]-P[-2][0], P[-1][1]-P[-2][1])
            assert fin>=10, f"floating/short final segment ({fin:.0f}px) in LR flow"


@check("count · audit reports an extracted count breakdown")
def _():
    r = _quiet_report(load("ex_flow.json"))
    c = r["counts"]
    assert c["nodes"] > 0 and "edges" in c, "count breakdown missing"


@check("count · source_count mismatch is flagged and gates the checkpoint")
def _():
    m = {"nodes": [{"id": str(i), "title": "n"} for i in range(5)], "edges": [],
         "provenance": {"text_policy": "verbatim", "source_count": {"nodes": 7}}}
    r = _quiet_report(m)
    assert r["count_mismatch"] and r["checkpoint_required"], "count mismatch not caught"


@check("count · matching source_count does not false-trigger")
def _():
    m = {"events": [{"id": str(i), "text": "e"} for i in range(6)],
         "provenance": {"text_policy": "verbatim", "source_count": 6}}
    r = _quiet_report(m)
    assert not r["count_mismatch"], "matching count wrongly flagged"


@check("compare · comparison_table renders lint-clean")
def _():
    import json, os
    m = json.load(open(os.path.join(HERE, "..", "examples", "comparison-table.json"), encoding="utf-8"))
    svg, w, h = render_compare.render(m)
    assert not lint.lint_svg(svg, w, h), f"comparison_table lint: {lint.lint_svg(svg,w,h)}"
    assert w / h < 2.2, f"comparison_table too wide ({w}x{h}) — should read as a table, not a strip"


@check("compare · comparison_table demands exactly two columns")
def _():
    import common as _c
    for n in (1, 3):
        m = {"schema_version": 1, "layout": "comparison_table", "title_text": "t",
             "columns": [{"id": str(i), "title": "C"} for i in range(n)],
             "rows": [{"dimension": "d", "cells": {str(i): "x" for i in range(n)}}]}
        try:
            _c.validate_map(m); assert False, f"{n} columns not rejected"
        except RuntimeError as e:
            assert "exactly 2 columns" in str(e)


@check("compare · a row missing a cell is rejected")
def _():
    import common as _c
    m = {"schema_version": 1, "layout": "comparison_table", "title_text": "t",
         "columns": [{"id": "a", "title": "A"}, {"id": "b", "title": "B"}],
         "rows": [{"dimension": "d", "cells": {"a": "x"}}]}  # missing b
    try:
        _c.validate_map(m); assert False, "missing cell not rejected"
    except RuntimeError as e:
        assert "missing a cell" in str(e)


@check("skill · intent router + forbidden red-lines table present in SKILL.md")
def _():
    p = os.path.join(HERE, "..", "SKILL.md")
    t = open(p, encoding="utf-8").read()
    assert "Intent router" in t, "intent router missing from SKILL.md"
    assert "Forbidden" in t, "forbidden table missing from SKILL.md"
    for redline in ("hexagon", "#991B1B", "extraction-guide"):
        assert redline in t, f"red-line reference '{redline}' missing from SKILL.md"


# ---- draw.io export (editable deliverable) ------------------------------
# The .drawio export is an ADDITIVE, stdlib-only, editable artifact covering
# all seven layouts. These guards fix what could regress: well-formed XML, no
# dangling edges, counts faithful to the map, emphasis carried as deep red,
# unknown layouts refused cleanly, the .drawio.svg staying a valid SVG with an
# embedded editable model, the zero-graphviz fallback still placing every node,
# and — the newly reported bug — text never sized to hug the box border.
_GRAPH_EX = ["ex_flow.json", "ex_flow_parallel.json", "ex_relation.json", "ex_tree.json"]
_ALL_EX = _GRAPH_EX + ["ex_points.json", "ex_dated.json", "ex_gantt.json"]
# comparison-table has no fixture alias; load it straight from examples/
import export_drawio as _dw  # noqa


def _compare_map():
    with open(os.path.join(EXAMPLES, "comparison-table.json"), encoding="utf-8") as f:
        return json.load(f)


@check("drawio · all seven layouts export to well-formed mxGraphModel XML")
def _dw_wellformed():
    maps = [load(n) for n in _ALL_EX] + [_compare_map()]
    for m in maps:
        xml, _, _ = _dw.build_model(m)
        doc = _MD.parseString(xml)   # raises if not well-formed
        assert doc.getElementsByTagName("mxfile"), f"{m['layout']}: no <mxfile>"
        assert doc.getElementsByTagName("mxGraphModel"), f"{m['layout']}: no <mxGraphModel>"


@check("drawio · graph edges never reference a missing cell id")
def _dw_no_dangling():
    for name in _GRAPH_EX:
        xml, _, _ = _dw.build_model(load(name))
        doc = _MD.parseString(xml)
        cells = doc.getElementsByTagName("mxCell")
        ids = {c.getAttribute("id") for c in cells}
        for c in cells:
            if c.getAttribute("edge") == "1" and c.getAttribute("source"):
                assert c.getAttribute("source") in ids and c.getAttribute("target") in ids, \
                    f"{name}: dangling edge {c.getAttribute('id')}"


@check("drawio · graph node & edge counts stay faithful to the map")
def _dw_counts():
    for name in _GRAPH_EX:
        m = load(name)
        xml, _, _ = _dw.build_model(m)
        doc = _MD.parseString(xml)
        cells = doc.getElementsByTagName("mxCell")
        verts = [c for c in cells if c.getAttribute("vertex") == "1"
                 and c.getAttribute("id") != "title"
                 and not c.getAttribute("id").endswith("_note")]
        edges = [c for c in cells if c.getAttribute("edge") == "1"]
        assert len(verts) == len(m["nodes"]), f"{name}: {len(verts)} vs {len(m['nodes'])} nodes"
        assert len(edges) == len(m["edges"]), f"{name}: {len(edges)} vs {len(m['edges'])} edges"


@check("drawio · timelines/gantt/table produce the expected shape counts")
def _dw_nongraph_shapes():
    xml, _, _ = _dw.build_model(load("ex_points.json"))         # 7 events
    assert xml.count('ellipse;') == 7, "numbered timeline: one marker per event"
    g = _dw.build_model(load("ex_gantt.json"))[0]               # 7 spans
    assert g.count('rounded=0;') >= 7, "gantt: one bar per span"
    t = _dw.build_model(_compare_map())[0]                       # 3 rows x 2 cols + headers
    assert t.count('vertex="1"') == 1 + (1 + 2) + 3 * (1 + 2), "compare: title+headers+cells"


@check("drawio · emphasis is carried as the one deep red #991B1B")
def _dw_emphasis_red():
    assert "#991B1B" in _dw.build_model(load("ex_tree.json"))[0], "tree emphasis not red"
    assert "#991B1B" in _dw.build_model(load("ex_gantt.json"))[0], "gantt emphasis not red"
    clean = {"schema_version": 1, "layout": "graphviz_flow", "title_text": "t",
             "nodes": [{"id": "a", "kind": "step", "title": "甲"},
                       {"id": "b", "kind": "step", "title": "乙"}],
             "edges": [{"from": "a", "to": "b"}]}
    assert "#991B1B" not in _dw.build_model(clean)[0], "red leaked with no emphasis"


@check("drawio · an unknown layout is refused cleanly")
def _dw_refuse_unknown():
    try:
        _dw.build_model({"layout": "mind_map", "title_text": "x"})
        assert False, "unknown layout should raise"
    except RuntimeError as e:
        assert "does not support" in str(e), f"unclear refusal: {e}"


@check("drawio · .drawio.svg is a valid SVG embedding a well-formed model")
def _dw_svg_embed():
    m = load("ex_relation.json")
    xml, _, _ = _dw.build_model(m)
    svg, _, _ = render_relation.render(m)
    hybrid = _dw.embed_in_svg(svg, xml)
    doc = _MD.parseString(hybrid)
    assert doc.documentElement.tagName == "svg", "hybrid root is not <svg>"
    content = doc.documentElement.getAttribute("content")
    assert content.strip().startswith("<mxfile"), "no embedded mxfile"
    _MD.parseString(content)


@check("drawio · export works with NO graphviz (stdlib fallback places every node)")
def _dw_stdlib_fallback():
    m = load("ex_flow.json")
    sizes = {n["id"]: _dw._box_size(_dw._node_lines(n)) for n in m["nodes"]}
    pos = _dw._positions_layered(m, sizes)
    assert set(pos) == {n["id"] for n in m["nodes"]}, "fallback dropped a node"
    for nid, (x, y) in pos.items():
        assert abs(x) < 1e9 and abs(y) < 1e9, f"bad coord for {nid}"


@check("drawio · text is never sized to hug the box border (anti-overlap)")
def _dw_no_hug():
    # every text-bearing vertex must be tall enough for its own <br> line count
    # (guards the reported 'text too close to the border' regression)
    maps = [load(n) for n in _ALL_EX] + [_compare_map()]
    for m in maps:
        doc = _MD.parseString(_dw.build_model(m)[0])
        for c in doc.getElementsByTagName("mxCell"):
            if c.getAttribute("vertex") != "1":
                continue
            val = c.getAttribute("value")
            if not val:
                continue
            nlines = val.count("<br>") + 1
            geo = c.getElementsByTagName("mxGeometry")
            if not geo:
                continue
            h = float(geo[0].getAttribute("height") or 0)
            fsm = re.search(r"fontSize=(\d+)", c.getAttribute("style"))
            fs = int(fsm.group(1)) if fsm else _dw.NODE_FS
            # need room for the lines at THIS cell's font size (draw.io won't
            # re-wrap because we baked our own breaks and sized width w/ a fudge)
            assert h >= nlines * fs + 2, \
                f"{m['layout']} cell {c.getAttribute('id')}: h={h} too short for {nlines}x{fs}px"


@check("drawio · positioned layouts tile without cell overlap")
def _dw_positioned_no_overlap():
    def _boxes(m):
        doc = _MD.parseString(_dw.build_model(m)[0])
        out = []
        for c in doc.getElementsByTagName("mxCell"):
            if c.getAttribute("vertex") != "1" or c.getAttribute("id") == "title":
                continue
            g = c.getElementsByTagName("mxGeometry")
            if not g:
                continue
            f = lambda k: float(g[0].getAttribute(k) or 0)
            out.append((c.getAttribute("id"), f("x"), f("y"), f("width"), f("height"),
                        c.getAttribute("style")))
        return out

    def _ov(a, b):
        ix = max(0, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
        iy = max(0, min(a[2] + a[4], b[2] + b[4]) - max(a[2], b[2]))
        return ix > 2 and iy > 2

    # numbered-timeline cards must not overlap one another
    cards = [x for x in _boxes(load("ex_points.json")) if x[0].startswith("card")]
    assert not any(_ov(cards[i], cards[j]) for i in range(len(cards)) for j in range(i + 1, len(cards))), \
        "numbered timeline cards overlap"
    # comparison-table cells tile cleanly (no overlaps, columns aligned)
    cells = [x for x in _boxes(_compare_map())
             if x[0].startswith(("cell", "dim", "hdr"))]
    assert not any(_ov(cells[i], cells[j]) for i in range(len(cells)) for j in range(i + 1, len(cells))), \
        "comparison cells overlap"
    assert len({round(x[1]) for x in cells}) == 3, "comparison columns not aligned to 3 x-positions"
    # gantt bars: one per row (distinct y per bar) and inside the axis band
    bars = [x for x in _boxes(load("ex_gantt.json"))
            if x[0].startswith("bar") and not x[0].startswith("barlbl")]
    assert len({round(x[2]) for x in bars}) == len(bars), "gantt bars share a row"


# ---- Step 2 · drawio timeline connectors ---------------------------------
@check("drawio · timeline connectors stop at the marker edge (never cover the circle)")
def _dw_connector_not_over_marker():
    for name, r in (("ex_points.json", 17), ("ex_dated.json", 8)):
        m = load(name)
        doc = _MD.parseString(_dw.build_model(m)[0])
        # collect marker circle centres (ellipse vertices) and connector endpoints
        centres = []
        for c in doc.getElementsByTagName("mxCell"):
            if c.getAttribute("vertex") == "1" and "ellipse" in c.getAttribute("style"):
                g = c.getElementsByTagName("mxGeometry")[0]
                x, y = float(g.getAttribute("x")), float(g.getAttribute("y"))
                w, h = float(g.getAttribute("width")), float(g.getAttribute("height"))
                centres.append((x + w / 2, y + h / 2, w / 2))
        for c in doc.getElementsByTagName("mxCell"):
            if c.getAttribute("edge") != "1" or not c.getAttribute("id").startswith("cn"):
                continue
            pts = c.getElementsByTagName("mxPoint")
            for p in pts:
                px, py = float(p.getAttribute("x")), float(p.getAttribute("y"))
                for cx, cy, rad in centres:
                    if abs(px - cx) < 1:      # same column as this marker
                        assert abs(py - cy) >= rad - 0.5, \
                            f"{name}: connector endpoint enters the marker (dy={abs(py-cy)} < r={rad})"


# ---- Step 2 · relation routing + labels (no overlap, no line through a node) ----
@check("relation · routes avoid nodes, don't overlap, and labels never collide")
def _rel_router_clean():
    from common import text_w as _tw
    import re as _re

    def _pts(d):
        return [(float(a), float(b)) for a, b in _re.findall(r'(-?\d+\.?\d*),(-?\d+\.?\d*)', d)]

    for name in ("ex_relation.json", "edge_relation_dense.json"):
        m = load(name)
        svg, W, H = render_relation.render(m)
        nodes = [(mm.group(1), float(mm.group(2)), float(mm.group(3)),
                  float(mm.group(2)) + float(mm.group(4)), float(mm.group(3)) + float(mm.group(5)))
                 for mm in _re.finditer(
                     r'data-id="([^"]+)">\s*<rect x="([-\d.]+)" y="([-\d.]+)" width="([-\d.]+)" height="([-\d.]+)"', svg)]
        paths = _re.findall(r'<path d="([^"]+)" fill="none"', svg)
        fr = [e["from"] for e in m["edges"]]; to = [e["to"] for e in m["edges"]]

        # 1) no segment crosses a non-endpoint node
        segs = []
        for i, d in enumerate(paths):
            p = _pts(d)
            for j in range(len(p) - 1):
                segs.append((i, p[j], p[j + 1]))
                (x0, y0), (x1, y1) = p[j], p[j + 1]
                for nid, L, T, R, B in nodes:
                    if i < len(fr) and nid in (fr[i], to[i]):
                        continue
                    if abs(x0 - x1) < 1 and L - 2 < x0 < R + 2 and min(y0, y1) < B - 2 and max(y0, y1) > T + 2:
                        assert False, f"{name}: edge {i} runs through node {nid}"
                    if abs(y0 - y1) < 1 and T - 2 < y0 < B + 2 and min(x0, x1) < R - 2 and max(x0, x1) > L + 2:
                        assert False, f"{name}: edge {i} runs through node {nid}"

        # 2) no two different edges share a collinear run (parallel overlap)
        def _coll(s1, s2):
            (i1, a1, b1), (i2, a2, b2) = s1, s2
            if i1 == i2:
                return False
            if abs(a1[0] - b1[0]) < 1 and abs(a2[0] - b2[0]) < 1 and abs(a1[0] - a2[0]) < 5:
                lo1, hi1 = sorted([a1[1], b1[1]]); lo2, hi2 = sorted([a2[1], b2[1]])
                return min(hi1, hi2) - max(lo1, lo2) > 8
            if abs(a1[1] - b1[1]) < 1 and abs(a2[1] - b2[1]) < 1 and abs(a1[1] - a2[1]) < 5:
                lo1, hi1 = sorted([a1[0], b1[0]]); lo2, hi2 = sorted([a2[0], b2[0]])
                return min(hi1, hi2) - max(lo1, lo2) > 8
            return False
        assert not any(_coll(segs[i], segs[j]) for i in range(len(segs)) for j in range(i + 1, len(segs))), \
            f"{name}: two edges overlap on a collinear run"

        # 3) labels wrap and never overlap a node or another label
        blk = _re.search(r'<g data-role="edge-labels">(.*?)</g>', svg, _re.S)
        labs = _re.findall(r'<text x="([-\d.]+)" y="([-\d.]+)"[^>]*text-anchor="(\w+)"[^>]*>([^<]+)</text>',
                           blk.group(1)) if blk else []
        blocks, cur = [], None
        for x, y, an, t in labs:
            x, y = float(x), float(y)
            if cur and abs(cur["x"] - x) < 0.5 and (y - cur["ys"][-1]) < 30:
                cur["ys"].append(y); cur["ts"].append(t)   # same block: same x AND adjacent y
            else:
                cur = {"x": x, "ys": [y], "ts": [t], "a": an}; blocks.append(cur)
        assert len(blocks) == sum(1 for e in m["edges"] if e.get("label")), f"{name}: a label went missing"
        lb = []
        for b in blocks:
            bw = max(_tw(t, 13) for t in b["ts"])
            assert bw <= 168 + 16, f"{name}: an edge label was not wrapped"
            L = b["x"] - (bw / 2 if b["a"] == "middle" else 0)
            R = b["x"] + (bw / 2 if b["a"] == "middle" else bw)
            lb.append((L, min(b["ys"]) - 13, R, max(b["ys"]) + 3))
        nb = [(n[1], n[2], n[3], n[4]) for n in nodes]
        def _ov(a, b, p=1):
            return not (a[2] < b[0] + p or a[0] > b[2] - p or a[3] < b[1] + p or a[1] > b[3] - p)
        assert not any(_ov(L, N) for L in lb for N in nb), f"{name}: a label overlaps a node"
        assert not any(_ov(lb[i], lb[j]) for i in range(len(lb)) for j in range(i + 1, len(lb))), \
            f"{name}: two labels overlap"

        # 4) no label is crossed by any connector segment (labels never sit on a line)
        for L in lb:
            for i, d in enumerate(paths):
                p = _pts(d)
                for j in range(len(p) - 1):
                    (x0, y0), (x1, y1) = p[j], p[j + 1]
                    if abs(x0 - x1) < 1 and L[0] < x0 < L[2] and min(y0, y1) < L[3] and max(y0, y1) > L[1]:
                        assert False, f"{name}: a label is crossed by a connector line"
                    if abs(y0 - y1) < 1 and L[1] < y0 < L[3] and min(x0, x1) < L[2] and max(x0, x1) > L[0]:
                        assert False, f"{name}: a label is crossed by a connector line"


# ---- Step 2 · relation deliberate layout ---------------------------------
@check("relation · layout centres a dominant hub, keeps source order, aligns rows")
def _rel_layout():
    # simple/linear graph keeps SOURCE ORDER left-to-right (not scrambled by degree)
    m = load("ex_relation.json")
    pos, sizes = render_relation._layout_nodes(m)
    order_by_x = [i for i, _ in sorted(pos.items(), key=lambda kv: kv[1][0])]
    src = [n["id"] for n in m["nodes"]]
    assert order_by_x == src, f"linear graph reordered: {order_by_x} vs {src}"

    # dense graph with a clear hub → hub is horizontally central + rows aligned
    md = load("edge_relation_dense.json")
    pos2, _ = render_relation._layout_nodes(md)
    deg = {}
    for e in md["edges"]:
        deg[e["from"]] = deg.get(e["from"], 0) + 1
        deg[e["to"]] = deg.get(e["to"], 0) + 1
    hub = max(deg, key=deg.get)
    xs = [p[0] for p in pos2.values()]
    cx = (min(xs) + max(xs)) / 2
    # hub sits nearer the horizontal centre than the average node
    hub_off = abs(pos2[hub][0] - cx)
    avg_off = sum(abs(p[0] - cx) for p in pos2.values()) / len(pos2)
    assert hub_off <= avg_off, f"hub not central (off {hub_off:.0f} vs avg {avg_off:.0f})"
    # rows are aligned: only a few distinct y bands, each shared by ≥1 node
    ybands = sorted({round(p[1]) for p in pos2.values()})
    assert len(ybands) <= 3, f"rows not aligned into tidy bands: {ybands}"


@check("relation · no module side carries 3+ edges (hub spreads across its borders)")
def _rel_side_spread():
    import re as _re
    m = load("edge_relation_dense.json")
    svg, W, H = render_relation.render(m)
    nodes = {mm.group(1): (float(mm.group(2)), float(mm.group(3)),
                           float(mm.group(2)) + float(mm.group(4)), float(mm.group(3)) + float(mm.group(5)))
             for mm in _re.finditer(
                 r'data-id="([^"]+)">\s*<rect x="([-\d.]+)" y="([-\d.]+)" width="([-\d.]+)" height="([-\d.]+)"', svg)}
    paths = _re.findall(r'<path d="([^"]+)" fill="none"', svg)
    def _pts(d):
        return [(float(a), float(b)) for a, b in _re.findall(r'(-?\d+\.?\d*),(-?\d+\.?\d*)', d)]
    from collections import Counter
    side = Counter()
    for i, d in enumerate(paths):
        e = m["edges"][i]; p = _pts(d)
        for endpt, nid in ((p[0], e["from"]), (p[-1], e["to"])):
            if nid not in nodes:
                continue
            L, T, R, B = nodes[nid]
            if abs(endpt[0] - L) < 3:   side[(nid, "L")] += 1
            elif abs(endpt[0] - R) < 3: side[(nid, "R")] += 1
            elif abs(endpt[1] - T) < 3: side[(nid, "T")] += 1
            elif abs(endpt[1] - B) < 3: side[(nid, "B")] += 1
    worst = max(side.values()) if side else 0
    assert worst <= 2, f"a module side carries {worst} edges (should spread ≤2 per side): {[(k,v) for k,v in side.items() if v>=3]}"


# ---- 白描 (monochrome court/print mode) ----------------------------------
@check("白描 · pure black line-art; layout byte-identical, modules squared off")
def _baimiao_mode():
    import re as _re
    import render as _render
    from common import TOKENS as _TOK
    MOD_RX = float(_TOK["radius"]["corner"])
    for name in ("ex_flow.json", "ex_relation.json", "ex_tree.json",
                 "ex_dated.json", "ex_gantt.json", "ex_compare.json"):
        m = load(name)
        mod = _render.choose(m)
        colour, _, _ = mod.render(m)
        mono = _render.to_monochrome(colour)
        # 1. no colour survives: fills are white or ink-black, strokes are ink-black,
        #    and the deep red is gone
        assert "#991B1B" not in mono.upper(), f"{name}: red survived 白描"
        fills = set(_re.findall(r'fill="(#[0-9A-Fa-f]{6})"', mono))
        strokes = set(_re.findall(r'stroke="(#[0-9A-Fa-f]{6})"', mono))
        assert fills <= {"#FFFFFF", "#111111"}, f"{name}: stray fill colour {fills}"
        assert strokes <= {"#111111"}, f"{name}: stray stroke colour {strokes}"
        # 2. LAYOUT is still byte-identical — nothing moves, nothing resizes.
        #    Only colour, stroke weight, added hairlines and the corner radius differ.
        strip = lambda s: _re.sub(r"\s+", " ", _re.sub(
            r'(?:fill|stroke|stroke-width|rx)="[^"]*"', "", s))
        assert strip(colour) == strip(mono), f"{name}: 白描 moved or resized something"
        # 3. modules are squared off toward a right angle …
        def rects(s):
            out = []
            for t in _re.findall(r"<rect\b[^>]*/>", s):
                rx = _re.search(r'\brx="([\d.]+)"', t)
                h = _re.search(r'\bheight="([\d.]+)"', t)
                out.append((float(rx.group(1)) if rx else None,
                            float(h.group(1)) if h else None))
            return out
        for (rc, hc), (rm, hm) in zip(rects(colour), rects(mono)):
            if rc is None:
                assert rm is None, f"{name}: 白描 invented a radius on a square rect"
                continue
            if rc <= 0.01:
                # 4. … but a BAR stays a bar: the timeline band and gantt period
                #    bars are right angles and must never pick up a radius here
                assert rm == 0, f"{name}: 白描 rounded a right-angle bar (rx {rc}->{rm})"
            elif hc and rc >= hc / 2 - 0.5:
                # 5. … and a terminal pill stays a pill: the stadium is what marks
                #    a start/end node, a semantic cue rather than decoration
                assert rm == rc, f"{name}: 白描 flattened a terminal pill (rx {rc}->{rm})"
            else:
                assert rm == MOD_RX, f"{name}: module radius is {rm}, expected {MOD_RX}"
                assert rm < rc, f"{name}: module radius did not shrink"


# ---- 歸藏风 (Guizang Swiss / IKB theme) -----------------------------------
@check("歸藏风 · blue/grey/white only, blue diamond decision, top margin, mono Latin")
def _guizang_mode():
    import re as _re
    import render as _render
    THEME = {"#FAFAF8", "#333333", "#737373", "#BDBDBD", "#D4D4D2", "#E0E0E0", "#002FA7", "#FFFFFF"}
    for name in ("ex_flow.json", "ex_relation.json", "ex_tree.json"):
        m = load(name)
        mod = _render.choose(m)
        try:
            mod._THEME = "guizang"
            colour, _, _ = mod.render(m)
        finally:
            mod._THEME = None
        svg = _render.to_guizang(colour, m.get("layout"))
        # 1. strictly blue / grey / white — no other colour survives
        cols = set(_re.findall(r'(?:fill|stroke)="(#[0-9A-Fa-f]{6})"', svg))
        assert cols <= THEME, f"{name}: 歸藏风 has off-palette colour {cols - THEME}"
        # 2. a top margin (天头) was reserved for the big title
        assert 'transform="translate(0,60)"' in svg, f"{name}: 歸藏风 reserved no top margin"
        # 3. the Song serif is gone (sans/mono only)
        assert "宋体" not in svg and "Songti" not in svg, f"{name}: serif survived into 歸藏风"
        if name == "ex_flow.json":
            # decision is a 4-point blue DIAMOND, and there is at least one solid blue block
            assert _re.search(r'<path d="M [\d.]+,[\d.]+ L [\d.]+,[\d.]+ L [\d.]+,[\d.]+ L [\d.]+,[\d.]+ Z" fill="#002FA7"', svg), \
                f"{name}: decision is not a blue diamond"
            assert svg.count('fill="#002FA7"') >= 2, f"{name}: expected solid blue blocks (terminals/diamond)"


# ---- drawio theming ------------------------------------------------------
@check("drawio export follows the visual mode (白描 mono / 歸藏风 blue-grey), 奇川风 untouched")
def _drawio_themes():
    import re as _re
    import export_drawio as _ex
    for name in ("ex_flow.json", "ex_relation.json", "ex_tree.json"):
        m = load(name)
        base, _, _ = _ex.build_model(m)
        cols = lambda x: {c.upper() for c in _re.findall(r'Color=(#[0-9A-Fa-f]{6})', x)}
        # colour master is left exactly as-is
        assert _ex.theme_drawio(base, None) == base, f"{name}: theme_drawio touched 奇川风"
        # 白描 — black line-art only
        mono = cols(_ex.theme_drawio(base, "baimiao"))
        assert mono <= {"#FFFFFF", "#111111"}, f"{name}: 白描 drawio stray {mono}"
        # 歸藏风 — blue / grey / white only
        _d = {n["id"]: 0 for n in m["nodes"]}
        for _e in m.get("edges", []):
            if _e.get("from") in _d: _d[_e["from"]] += 1
            if _e.get("to") in _d: _d[_e["to"]] += 1
        _hub = None
        if _d:
            _hid = max(_d, key=lambda i: _d[i])
            _hub = "c%d" % [n["id"] for n in m["nodes"]].index(_hid)
        gz = cols(_ex.theme_drawio(base, "guizang", _hub))
        allowed = {"#002FA7", "#333333", "#737373", "#BDBDBD", "#D4D4D2", "#FFFFFF"}
        assert gz <= allowed, f"{name}: 歸藏风 drawio stray {gz - allowed}"
        assert "#002FA7" in gz, f"{name}: 歸藏风 drawio lost its blue"
        # structure untouched — only colours changed
        strip = lambda x: _re.sub(r'(?:fill|stroke|font)Color=#[0-9A-Fa-f]{6}', '', x)
        assert strip(_ex.theme_drawio(base, "guizang", _hub)) == strip(base), \
            f"{name}: theme_drawio altered structure, not just colour"


# ---- long text / overflow ------------------------------------------------
@check("over-long titles wrap instead of running off the canvas; notes reserve real room")
def _long_text():
    import re as _re, json as _json, subprocess, sys, pathlib, tempfile
    import render as _render
    root = pathlib.Path(__file__).resolve().parent.parent
    m = _json.loads((root / "examples" / "comparison-table.json").read_text())
    m["title_text"] = "关于某某市某某区某某工程建设项目施工合同纠纷一案二审判决与再审裁定裁判要旨逐项对比分析表"
    mod = _render.choose(m)
    svg, w, h = mod.render(m)
    fitted = _render.fit_title(svg)
    # the title is split into >1 tspan and the canvas grew to hold them
    assert fitted.count("<tspan") >= 2, "over-long title was not wrapped"
    nh = int(_re.search(r'<svg[^>]*height="(\d+)"', fitted).group(1))
    assert nh > h, "canvas did not grow for the wrapped title"
    # and no text runs off the canvas any more
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(fitted); p = f.name
    r = subprocess.run([sys.executable, str(root / "scripts" / "lint.py"), p],
                       capture_output=True, text=True, timeout=60)
    assert "overflows canvas" not in r.stdout, f"still overflowing: {r.stdout}"
    # a short title is left completely alone
    m2 = _json.loads((root / "examples" / "comparison-table.json").read_text())
    s2, _, _ = _render.choose(m2).render(m2)
    assert _render.fit_title(s2) == s2, "fit_title touched a title that already fits"


# ---- environment doctor --------------------------------------------------
@check("doctor.py runs, reports every dependency, and gates on required tooling")
def _doctor():
    import subprocess, sys, pathlib
    root = pathlib.Path(__file__).resolve().parent.parent
    r = subprocess.run([sys.executable, str(root / "scripts" / "doctor.py")],
                       capture_output=True, text=True, timeout=60)
    out = r.stdout
    for needle in ("Python", "graphviz", "PNG rasteriser", "IBM Plex Mono", "Result:"):
        assert needle in out, f"doctor.py never reported {needle!r}"
    assert r.returncode in (0, 1), f"doctor.py exited {r.returncode}"
    # exit code must reflect REQUIRED tooling only
    assert (r.returncode == 0) == ("MISSING REQUIRED" not in out), \
        "doctor.py exit code disagrees with its own report"


# ---- report -------------------------------------------------------------
def main():
    width = max(len(n) for n, _, _ in RESULTS)
    passed = 0
    for name, ok, detail in RESULTS:
        mark = "PASS" if ok else "FAIL"
        line = f"  [{mark}] {name.ljust(width)}"
        if not ok:
            line += f"   → {detail}"
        print(line)
        passed += ok
    total = len(RESULTS)
    if _DOC_ASSERTIONS and _DOC_ASSERTIONS[0] != total:
        print(f"\n! STANDARDS.md cites {_DOC_ASSERTIONS[0]} assertions; this run has {total}")
        RESULTS.append(("docs · assertion count matches", False))
        total = len(RESULTS)
    print(f"\n{passed}/{total} checks passed.")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
