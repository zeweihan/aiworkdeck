#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Entry point: semantic-map.json -> final.svg (+ final.png).

    python render.py <semantic-map.json> [out_basename]

Picks the layout from the map:
    layout == "numbered_point_timeline"  -> render_points
    layout == "proportional_gantt"       -> render_spans
(falls back to heuristics if `layout` is absent).

SVG is the primary, editable deliverable. PNG is derived for preview/filing.
"""
import sys, os, re, shutil, subprocess
from common import load_map, validate_map, strip_unearned_emphasis, TITLE_FONT, TOKENS
import math
import render_points, render_spans, render_flow, render_relation, render_tree, render_dated, render_compare
import export_drawio
import export_pptx
import export_vsdx


def _best_installed_song():
    """LibreOffice does NOT walk the CSS font-family list for CJK: if the first
    family is missing it substitutes its own SANS default (WenQuanYi), ignoring
    the Song faces listed later. So for the PNG we find the best Song ACTUALLY
    installed on this machine and put it first. Priority prefers real 方正小标宋
    when present (so a lawyer's own machine renders the true face), then 思源宋/
    Noto Serif, then 华文中宋."""
    # [AWD-PATCH 1 · 见 litviz/PATCHES.md] 上游只探 fc-list。fontconfig 在 Linux 上
    # 才是标配，macOS / Windows 的干净机器上没有这个命令 —— except 吞掉后返回 None，
    # 标题就按 CSS 首选的「方正小标宋简体」交给光栅器，而那是套商业字体，装不了，
    # 于是 PNG 标题整排豆腐块（正文用的是另一套 sans stack，所以只有标题坏）。
    # 这里把探测扩成三路：显式环境变量 > fontconfig > 按平台字体目录认文件名。
    override = os.environ.get("LITVIZ_TITLE_FONT", "").strip()
    if override:
        return override

    out = ""
    try:
        out = subprocess.run(["fc-list"], capture_output=True, text=True).stdout
    except Exception:
        out = ""      # 没有 fontconfig，落到下面的按文件名探测

    installed = set()
    # 字体文件名 -> 家族名。没有 fontTools 就读不出家族名，而这些文件名在各自平台上
    # 是稳定的，够用；认不出的字体只是不参与优选，不会出错。
    known_files = {
        "songti.ttc": "Songti SC",
        "stsong.ttf": "STSong",
        "simsun.ttc": "SimSun", "simsun.ttf": "SimSun", "simsunb.ttf": "SimSun",
        "notoserifsc-regular.otf": "Noto Serif SC",
        "notoserifcjksc-regular.otf": "Noto Serif CJK SC",
        "notoserifcjk-regular.ttc": "Noto Serif CJK SC",
        "sourcehanserifsc-regular.otf": "Source Han Serif SC",
    }
    font_dirs = []
    if os.environ.get("LITVIZ_FONT_DIR"):      # 宿主应用自带的字体（AI Workdeck 走这条）
        font_dirs.append(os.environ["LITVIZ_FONT_DIR"])
    if sys.platform == "darwin":
        font_dirs += [os.path.expanduser("~/Library/Fonts"), "/Library/Fonts",
                      "/System/Library/Fonts", "/System/Library/Fonts/Supplemental"]
    elif os.name == "nt":
        font_dirs += [os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "Fonts")]
        if os.environ.get("LOCALAPPDATA"):
            font_dirs.append(os.path.join(os.environ["LOCALAPPDATA"],
                                          "Microsoft", "Windows", "Fonts"))
    for d in font_dirs:
        try:
            for fn in os.listdir(d):
                fam = known_files.get(fn.lower())
                if fam:
                    installed.add(fam)
        except OSError:
            continue

    for fam in ("方正小标宋", "FZXiaoBiaoSong", "思源宋体", "Source Han Serif SC",
                "Noto Serif CJK SC", "Noto Serif SC", "华文中宋", "STZhongsong",
                "Songti SC", "SimSun"):
        if fam in out or fam in installed:
            return fam
    return None


def _png_safe_svg(svg_path):
    """Write a soffice-only copy of the SVG whose title font-family leads with an
    installed Song (see _best_installed_song). The on-disk master SVG is left
    untouched — it keeps 方正小标宋 first for viewers that DO walk the list."""
    song = _best_installed_song()
    src = open(svg_path, encoding="utf-8").read()
    marker = f'font-family="{TITLE_FONT}"'
    if not song or marker not in src:
        return svg_path, None
    new_font = f"'{song}',serif"
    fixed = src.replace(marker, f'font-family="{new_font}"')
    # LibreOffice outlines *stroked* CJK <text> using its sans default (WenQuanYi)
    # instead of the requested Song, so the PNG title came out looking like 黑体.
    # The on-disk master SVG keeps the hairline stroke (renders fine on rsvg /
    # browsers); for the soffice-only copy we drop stroke on the TITLE text alone
    # and rely on the real Bold face (font-weight:700 -> Noto Serif CJK Bold).
    def _strip_title_stroke(m):
        tag = m.group(0)
        tag = re.sub(r'\s+stroke="[^"]*"', '', tag)
        tag = re.sub(r'\s+stroke-width="[^"]*"', '', tag)
        return tag
    fixed = re.sub(r'<text\b[^>]*font-family="' + re.escape(new_font) + r'"[^>]*>',
                   _strip_title_stroke, fixed)
    tmp = os.path.splitext(svg_path)[0] + "__png.svg"
    open(tmp, "w", encoding="utf-8").write(fixed)
    return tmp, tmp



def choose(m):
    layout = m.get("layout", "")
    if layout == "numbered_point_timeline":
        return render_points
    if layout == "dated_point_timeline":
        return render_dated
    if layout == "proportional_gantt":
        return render_spans
    if layout == "graphviz_flow":
        return render_flow
    if layout == "graphviz_relation":
        return render_relation
    if layout == "relation_tree":
        return render_tree
    if layout == "comparison_table":
        return render_compare
    # heuristic fallback
    if m.get("nodes") and m.get("edges"):
        return render_flow
    return render_spans if m.get("spans") else render_points


def _svg_px_size(svg_path):
    """The master's own pixel canvas, so the raster can be pinned to an exact
    integer multiple of it."""
    try:
        head = open(svg_path, encoding="utf-8").read(600)
    except OSError:
        return None
    m = re.search(r'<svg[^>]*\bwidth="([\d.]+)"[^>]*\bheight="([\d.]+)"', head)
    return (float(m.group(1)), float(m.group(2))) if m else None


def svg_to_png(svg_path, png_path, dpi=192):
    """Render SVG -> PNG with whatever is installed. Prefer a real SVG
    rasterizer; fall back to soffice -> PDF -> pdftoppm (works everywhere
    LibreOffice is present, which is the common minimal environment)."""
    def has(x):
        return shutil.which(x) is not None

    wh = _svg_px_size(svg_path)
    scale = max(1, int(round(dpi / 96.0)))
    if has("rsvg-convert"):
        cmd = ["rsvg-convert", "-d", str(dpi), "-p", str(dpi)]
        if wh:                      # exact integer scale — see the note below
            cmd += ["-w", str(int(wh[0] * scale)), "-h", str(int(wh[1] * scale))]
        subprocess.run(cmd + [svg_path, "-o", png_path], check=True)
        return "rsvg-convert"
    if has("resvg"):
        subprocess.run(["resvg", "--dpi", str(dpi), svg_path, png_path], check=True)
        return "resvg"
    if has("inkscape"):
        subprocess.run(["inkscape", svg_path, "--export-type=png",
                        f"--export-dpi={dpi}", f"--export-filename={png_path}"], check=True)
        return "inkscape"
    # LibreOffice SVG -> PDF -> pdftoppm. Preferred over cairosvg for THIS skill
    # because our text is all-CJK: soffice+Noto renders Chinese reliably, whereas
    # cairosvg's Cairo font API does not do fontconfig fallback and can emit □
    # tofu boxes for CJK. So soffice is tried first; cairosvg is the last resort.
    if has("soffice") and has("pdftoppm"):
        outdir = os.path.dirname(os.path.abspath(png_path)) or "."
        src_svg, tmp = _png_safe_svg(svg_path)   # title Song-first so soffice picks a Song, not its sans default
        subprocess.run(["soffice", "--headless", "--convert-to", "pdf",
                        "--outdir", outdir, src_svg], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        pdf = os.path.splitext(src_svg)[0] + ".pdf"
        pdf = os.path.join(outdir, os.path.basename(pdf))
        prefix = os.path.splitext(png_path)[0]
        # Pin the output to an EXACT integer multiple of the SVG's own pixel size.
        # Left to `-r dpi`, the scale comes out at 2.0012 rather than 2 — soffice
        # rounds the PDF page by a few thousandths of a point — and that drift is
        # enough that a long bar's two horizontal edges land on different
        # sub-pixel phases and print at different weights (a visibly heavier rule
        # under the timeline band than over it). An exact scale puts every integer
        # coordinate on a pixel boundary and both edges come out equal.
        cmd = ["pdftoppm", "-png", "-r", str(dpi)]
        wh = _svg_px_size(svg_path)
        if wh:
            scale = max(1, int(round(dpi / 96.0)))
            cmd += ["-scale-to-x", str(int(wh[0] * scale)),
                    "-scale-to-y", str(int(wh[1] * scale))]
        subprocess.run(cmd + [pdf, prefix], check=True)
        produced = prefix + "-1.png"
        if os.path.exists(produced):
            os.replace(produced, png_path)
        # Clean up after the detour. The PNG is made by going through a PDF, and
        # that PDF was being left in the OUTPUT folder beside the deliverables —
        # so the lawyer received a stray file they never asked for, in a folder
        # they may forward as-is. It is also the one artefact here that is not
        # reproducible (LibreOffice stamps it with the creation time), which
        # would leave a permanent false positive in any byte-level comparison.
        for leftover in (tmp, pdf):
            if leftover and os.path.exists(leftover):
                try:
                    os.remove(leftover)
                except OSError:
                    pass
        return "soffice+pdftoppm"
    try:
        import cairosvg  # noqa — last resort; may render CJK as tofu (see note above)
        cairosvg.svg2png(url=svg_path, write_to=png_path, dpi=dpi)
        return "cairosvg"
    except Exception:
        pass
    raise RuntimeError("No SVG->PNG renderer found. Install rsvg-convert/resvg/"
                       "inkscape, or LibreOffice(soffice)+pdftoppm (recommended for CJK).")


def to_monochrome(svg):
    """白描 (bái-miáo) — the court / print mode. Takes the 奇川风 output and
    recolours it to pure black line-art: every colour becomes black ink, every
    solid colour block becomes an OUTLINE module (white fill), markers/dots stay
    solid black. Emphasis still reads, since it already carries a thicker stroke
    and bolder weight (now in black instead of red).

    Layout is untouched — every position, size, route and label is byte-for-byte
    the 奇川风 figure. The ONE shape change is the module corner radius: outline
    modules are squared off to a near right angle, because a white box drawn with
    a thin black rule reads as a filing-cabinet form, and a generous 12px radius
    on it reads soft and app-like rather than sober. Two shapes are explicitly
    NOT touched:

      * anything already at rx=0 — the timeline time-band and gantt period bars
        are BARS, and a bar with corners is a card (see visual-style.md);
      * terminal pills (rx == height/2) — the stadium is what distinguishes a
        start/end node from an ordinary step, so flattening it would erase a
        semantic distinction rather than soften a decorative one.
    """
    INK = "#111111"
    HAIRLINE = TOKENS["stroke"]["hairline"]     # integer — see the note below
    MOD_RX = TOKENS["radius"]["corner"]        # the same r≈2.5 used on every bend

    def _square(rm):
        tag = rm.group(0)
        rx = re.search(r'\brx="([\d.]+)"', tag)
        h = re.search(r'\bheight="([\d.]+)"', tag)
        if not rx or not h:
            return tag
        r, hh = float(rx.group(1)), float(h.group(1))
        if r <= 0.01 or r >= hh / 2 - 0.5:     # already square, or a terminal pill
            return tag
        return tag.replace(f'rx="{rx.group(1)}"', f'rx="{MOD_RX}"')
    svg = re.sub(r"<rect\b[^>]*/>", _square, svg)

    # text -> black ink
    svg = re.sub(r'(<text\b[^>]*?) fill="[^"]*"', r'\1 fill="' + INK + '"', svg)
    # rectangles + filled shape-paths (modules, hexagons, bars) -> white = outline
    svg = re.sub(r'(<rect\b[^>]*?) fill="[^"]*"', r'\1 fill="#FFFFFF"', svg)
    svg = re.sub(r'(<path\b[^>]*?) fill="#[0-9A-Fa-f]{6}"', r'\1 fill="#FFFFFF"', svg)
    # circles (numbered timeline markers) -> RINGS: white fill + black border, so the
    # number inside stays readable (a solid black disc would hide it).
    def _ring(cm):
        c = re.sub(r' fill="[^"]*"', ' fill="#FFFFFF"', cm.group(0))
        return c if "stroke=" in c else c[:-2] + f' stroke="{INK}" stroke-width="1.8"/>'
    svg = re.sub(r'<circle\b[^>]*?/>', _ring, svg)
    # emphasis line drops to normal weight in 白描 (no heavy bottom rule)
    svg = re.sub(r'stroke-width="3"', 'stroke-width="2"', svg)
    # every coloured stroke -> black
    svg = re.sub(r'stroke="#[0-9A-Fa-f]{6}"', 'stroke="' + INK + '"', svg)
    # a filled bar/box with NO border (period bars, timeline band) would vanish
    # as a white fill — give it a hairline so it reads as an OUTLINED long box.
    #
    # The hairline width is an INTEGER on purpose. A centred stroke straddles the
    # edge it sits on: at the 2x raster scale a 1.2px stroke becomes 2.4 device
    # px, whose two partial ends can round differently at the top and bottom of
    # the same bar — which printed a rule under the timeline band visibly heavier
    # than the one over it. A 1px stroke is exactly 2 device px and lands on the
    # pixel boundary, so both edges come out identical. (`shape-rendering=
    # "crispEdges"` was tried first and measured to change nothing here — the
    # rasterizer ignores it — so it is not carried.)
    def _outline(rm):
        r = rm.group(0)
        return r if "stroke=" in r else (
            r[:-2] + f' stroke="{INK}" stroke-width="{HAIRLINE}"/>')
    svg = re.sub(r'<rect\b(?=[^>]*\sx=")[^>]*?/>', _outline, svg)
    # arrowheads live inside <marker> and were just whitened — make them black again
    svg = re.sub(r'<marker\b.*?</marker>',
                 lambda mm: mm.group(0).replace('fill="#FFFFFF"', 'fill="' + INK + '"'),
                 svg, flags=re.S)
    return svg


# --- post-processing safety net -------------------------------------------
# The two mode transforms rewrite a generated SVG with regexes, and the failure
# mode that has actually bitten this project is not "too many regexes" — it is a
# regex that silently STOPS MATCHING when the thing it targets moves (a title at
# y=46 instead of 44, an extra attribute before x=, a nested data-role). The
# substitution quietly does nothing, the code runs to completion, and the figure
# is wrong in a way only a human looking at pixels would catch.
#
# So every substitution that MUST fire is made through _sub(), which records a
# miss. Callers end with _sub_report(), turning a silent wrong figure into a
# loud, named complaint. This does not make the post-processing clean — that
# would mean teaching the renderers the modes natively — but it removes the part
# that is dangerous rather than merely ugly.
_SUB_MISSES = []

# Layouts whose figures contain module groups at all. Used to decide whether a
# module-specific post-processing step is REQUIRED to fire here.
_MODULE_LAYOUTS = {"graphviz_flow", "graphviz_relation", "relation_tree"}


def _sub(pattern, repl, text, what, flags=0, count=0, need=True):
    """re.sub that remembers when it changed nothing.

    `need` is the caller's statement that this step APPLIES here. Several steps
    are layout-specific — there are no module groups in a timeline and no axis
    band in a flowchart — and a miss there is correct, not a fault. Marking those
    unconditionally "required" would make the report cry wolf, and a report that
    cries wolf gets ignored, which is the thing it exists to prevent.
    """
    out, n = re.subn(pattern, repl, text, count=count, flags=flags)
    if n == 0 and need:
        _SUB_MISSES.append(what)
    return out


def _sub_reset():
    _SUB_MISSES.clear()


def _sub_report(mode):
    """Print anything that failed to match. Returns the list of misses."""
    if _SUB_MISSES:
        print(f"{mode}: {len(_SUB_MISSES)} post-processing step(s) matched NOTHING — "
              f"the figure may be wrong:")
        for w in _SUB_MISSES:
            print(f"  · {w}")
    return list(_SUB_MISSES)


def to_guizang(svg, layout=None):
    """歸藏风 — the Guizang "Swiss International" theme (for online / lecture /
    social sharing). Same 奇川风 geometry & layout; only the surface changes:
      · sans-serif type (Inter / Noto Sans SC), replacing the Song serif;
      · Klein-blue #002FA7 accent — decision nodes become blue DIAMONDS with white
        text; emphasis / feedback edges turn blue;
      · plain white modules with a light-grey hairline border and SHARP corners
        (small radii -> 0; the terminal pill keeps its stadium shape);
      · dark-grey text, light-grey connectors (soft, not heavy)."""
    PAPER, INK, SUB, LINE, BORDER, IKB = \
        "#FAFAF8", "#333333", "#737373", "#BDBDBD", "#D4D4D2", "#002FA7"
    _sub_reset()
    svg = _sub(r'font-family="[^"]*"',
               "font-family=\"Inter, 'Noto Sans SC', 'Helvetica Neue', Arial, sans-serif\"",
               svg, "sans-serif type replacing the Song serif")
    svg = re.sub(r'rx="([\d.]+)"', lambda m: 'rx="0"' if float(m.group(1)) <= 14 else m.group(0), svg)
    svg = _sub(r'(<rect width="\d+" height="\d+" )fill="[^"]*"', r'\1fill="' + PAPER + '"',
               svg, "warm paper background", count=1)
    # artistic dot-matrix layer (Guizang Swiss signature): a faint LIGHT-GREY
    # dot grid on warm paper. The dots are deliberately NOT the accent colour —
    # in this style the Klein blue is the single high-saturation anchor, spent on
    # solid blocks and a few emphasised marks. Tinting the whole backdrop with it
    # spends that anchor on the background and the grid starts competing with the
    # content, which is exactly what the style forbids.
    wm = re.search(r'<svg[^>]*width="(\d+)"[^>]*height="(\d+)"', svg)
    if wm:
        sw, sh = wm.group(1), wm.group(2)
        dots = (f'<defs><pattern id="gzdot" width="26" height="26" patternUnits="userSpaceOnUse">'
                f'<circle cx="2" cy="2" r="1.2" fill="{BORDER}"/></pattern></defs>'
                f'<rect width="{sw}" height="{sh}" fill="url(#gzdot)"/>')
        svg = _sub(r'(<rect width="\d+" height="\d+" fill="' + re.escape(PAPER) + r'"/>)', r'\1' + dots, svg,
                   "IKB dot-matrix layer", count=1)
    # doc title -> BIG, light, sans, CENTRED, size RELATIVE to the canvas width
    _wt = re.search(r'width="(\d+)"', svg)
    _W = int(_wt.group(1)) if _wt else 1000; _cx = _W // 2
    _T = TOKENS["tuning"]
    _tfs = max(_T["guizang_title_min"], min(_T["guizang_title_max"],
                                           round(_W * _T["guizang_title_ratio"])))
    def _title(tm):
        t = re.sub(r'font-weight="\d+"', 'font-weight="300"', tm.group(0))
        t = re.sub(r' stroke="[^"]*"', '', t)
        t = re.sub(r' stroke-width="[^"]*"', '', t)
        t = re.sub(r'font-size="\d+"', f'font-size="{_tfs}"', t)   # relative, not absolute
        t = re.sub(r'<text x="\d+"', f'<text x="{_cx}"', t)
        return t
    svg = _sub(r'<text [^>]*stroke-width="0\.3"[^>]*>[^<]*</text>', _title, svg,
               "big centred light title")

    # gantt period bars -> colour BANDS: the emphasised span is a solid BLUE band,
    # ordinary spans are GREY bands, together reading as "time elapsing".
    def _spans(mm):
        blk = mm.group(0).replace("#991B1B", "__EMPH__")
        blk = re.sub(r'fill="#[0-9A-Fa-f]{6}"', 'fill="#C4C4C4"', blk)
        return blk.replace("__EMPH__", IKB)
    svg = re.sub(r'<g data-role="spans">.*?</g>', _spans, svg, flags=re.S)

    def _node(mm):
        b = mm.group(0)
        if "<path" in b:                              # decision hexagon -> blue diamond + white text
            d = re.search(r'<path d="([^"]*)"', b).group(1)
            nums = re.findall(r'(-?\d+\.?\d*),(-?\d+\.?\d*)', d)
            xs = [float(x) for x, _ in nums]; ys = [float(y) for _, y in nums]
            L, R, T, Bt = min(xs), max(xs), min(ys), max(ys); cx = (L + R) / 2; cy = (T + Bt) / 2
            dia = f'M {cx:.1f},{T:.1f} L {R:.1f},{cy:.1f} L {cx:.1f},{Bt:.1f} L {L:.1f},{cy:.1f} Z'
            b = re.sub(r'<path d="[^"]*"[^>]*/>',
                       f'<path d="{dia}" fill="{IKB}" stroke="{IKB}" stroke-width="1.4"/>', b)
            b = re.sub(r'(<text\b[^>]*?) fill="[^"]*"', r'\1 fill="#FFFFFF"', b)
        else:                                         # step / terminal / emphasised
            rxm = re.search(r'rx="([\d.]+)"', b)
            terminal = bool(rxm and float(rxm.group(1)) > 14)   # flow terminal (pill)
            emph = 'data-emph="1"' in b                          # key relation node
            blue = terminal or emph
            fillc = IKB if blue else "#FFFFFF"
            strokec = IKB if blue else BORDER
            def _rect(rm):
                r = re.sub(r' fill="[^"]*"', f' fill="{fillc}"', rm.group(0))
                if "stroke=" in r:
                    r = re.sub(r'stroke="[^"]*"', f'stroke="{strokec}"', r)
                else:
                    r = r[:-2] + f' stroke="{strokec}" stroke-width="1"/>'
                return r
            b = re.sub(r'<rect\b[^>]*?/>', _rect, b)
            if blue:
                b = re.sub(r'(<text\b[^>]*?) fill="[^"]*"', r'\1 fill="#FFFFFF"', b)
            else:
                b = re.sub(r'(<text\b[^>]*font-weight="700"[^>]*?) fill="[^"]*"', r'\1 fill="' + INK + '"', b)
                b = re.sub(r'(<text\b(?![^>]*font-weight="700")[^>]*?) fill="[^"]*"', r'\1 fill="' + SUB + '"', b)
        return b
    svg = _sub(r'<g data-role="node".*?</g>', _node, svg,
               "modules -> white cards / blue diamonds", flags=re.S,
               # The precondition must NOT be the pattern's own marker string:
               # deriving both from '<g data-role="node"' made this self-defeating —
               # rename the marker and the step stops being "required" exactly when
               # it stops working. The LAYOUT decides whether modules exist.
               need=(layout in _MODULE_LAYOUTS if layout else True))

    # gantt period bars -> colour BANDS ("time elapsing"): key span = solid BLUE band,
    # ordinary spans = grey bands; labels grey. Theme colours so nothing washes them out.
    def _span(mm):
        raw = mm.group(0); emph = "#991B1B" in raw
        b = raw.replace('fill="#991B1B"', 'fill="__EMPH__"')
        b = re.sub(r'(<rect\b[^>]*?) fill="#[0-9A-Fa-f]{6}"', r'\1 fill="#E0E0E0"', b)   # lighter grey band
        b = b.replace("__EMPH__", IKB)
        tc = "#FFFFFF" if emph else INK                                                  # white on blue band
        sc = "#FFFFFF" if emph else SUB
        b = re.sub(r'(<text\b[^>]*font-weight="600"[^>]*?) fill="[^"]*"', r'\1 fill="' + tc + '"', b)
        b = re.sub(r'(<text\b(?![^>]*font-weight="600")[^>]*?) fill="[^"]*"', r'\1 fill="' + sc + '"', b)
        return b
    svg = re.sub(r'<g data-role="span" [^>]*?>.*?</g>', _span, svg, flags=re.S)

    # timeline "time band" (axis): LIGHT-GREY + thicker; ticks & years DARK-GREY;
    # connector stems match the band's grey; KEY events are blue blocks with white text.
    axm = re.search(r'<rect data-role="axis"[^>]*y="([\d.]+)"[^>]*height="([\d.]+)"', svg)
    if axm:
        ay0 = float(axm.group(1)); ah = float(axm.group(2)); ay1 = ay0 + ah
        new_h = ah + 8; nb = ay0 + new_h
        svg = _sub(r'(<rect data-role="axis"[^>]*?) fill="[^"]*"', r'\1 fill="#E0E0E0"', svg,
                   "timeline band -> light grey")
        svg = re.sub(r'(<rect data-role="axis"[^>]* height=")[\d.]+(")',
                     lambda m: m.group(1) + f'{new_h:.0f}' + m.group(2), svg)
        def _line(lm):
            ys = [float(v) for v in re.findall(r'y[12]="([\d.]+)"', lm.group(0))]
            on_band = bool(ys) and min(ys) >= ay0 - 3 and max(ys) <= nb + 3   # BOTH ends inside band = tick
            if on_band:                                   # tick: span full band, dark grey
                l = re.sub(r'y1="[\d.]+"', f'y1="{ay0:.1f}"', lm.group(0))
                l = re.sub(r'y2="[\d.]+"', f'y2="{nb:.1f}"', l)
                return re.sub(r'stroke="[^"]*"', 'stroke="#737373"', l)
            return re.sub(r'stroke="[^"]*"', 'stroke="#BDBDBD"', lm.group(0))  # stem: visible light grey
        svg = re.sub(r'<line[^>]*/>', _line, svg)
        def _yr(txm):
            yy = re.search(r'y="([\d.]+)"', txm.group(0))
            if yy and ay0 - 2 <= float(yy.group(1)) <= ay1 + 6:               # year: centre in band
                t = re.sub(r'y="[\d.]+"', f'y="{ay0 + new_h / 2 + 4:.1f}"', txm.group(0))
                return re.sub(r'fill="[^"]*"', f'fill="{INK}"', t)
            return txm.group(0)
        svg = re.sub(r'<text[^>]*>[^<]*</text>', _yr, svg)
        # z-order: redraw the band (+ ticks + years) ON TOP of the stems, so each
        # connector tucks BEHIND the band instead of crossing over it.
        axblk = re.search(r'(<rect data-role="axis".*?)(?=<g data-role="event")', svg, re.S)
        if axblk:
            blk = axblk.group(1)
            svg = svg.replace(blk, "", 1).replace("</svg>", blk + "</svg>", 1)
    def _event(mm):
        b = mm.group(0); emph = "#991B1B" in b
        if emph:                                          # key event -> solid blue + white text
            b = re.sub(r'(<rect\b[^>]*?) fill="[^"]*"', r'\1 fill="' + IKB + '"', b)
            b = re.sub(r'(<rect\b[^>]*?) stroke="[^"]*"', r'\1 stroke="' + IKB + '"', b)
            b = re.sub(r'(<text\b[^>]*?) fill="[^"]*"', r'\1 fill="#FFFFFF"', b)
        else:                                             # normal -> white card + hairline
            b = re.sub(r'(<rect\b[^>]*?) fill="[^"]*"', r'\1 fill="#FFFFFF"', b)
            b = re.sub(r'(<rect\b[^>]*?) stroke="[^"]*"', r'\1 stroke="#D4D4D2"', b)
            b = re.sub(r'(<text\b[^>]*font-weight="600"[^>]*?) fill="[^"]*"', r'\1 fill="' + SUB + '"', b)
            b = re.sub(r'(<text\b(?![^>]*font-weight="600")[^>]*?) fill="[^"]*"', r'\1 fill="' + INK + '"', b)
        return b
    svg = re.sub(r'<g data-role="event".*?</g>', _event, svg, flags=re.S)

    # ALL connectors + arrowheads are soft grey — no blue lines (blue = blocks only)
    svg = re.sub(r'(<path d="[^"]*" fill="none" stroke=")#[0-9A-Fa-f]{6}(" stroke-width="[\d.]+")', r'\1' + LINE + r'\2', svg)
    svg = re.sub(r'(<marker id="a[gr]".*?fill=")[^"]*(")', r'\1' + LINE + r'\2', svg, flags=re.S)
    # remaining source-token colours (doc title, edge labels, red emphasis text)
    svg = svg.replace("#1F2933", INK).replace("#6B7280", INK).replace("#991B1B", INK)

    # numbers / English / labels -> IBM Plex Mono (Guizang's engineered Latin type);
    # CJK stays sans. This is the distinctive "technical" texture.
    # …and they carry TRACKING. Wide letter-spacing on the Latin run is half of
    # what makes this style read as engineered rather than merely sans; without
    # it the numerals look like ordinary body text in a monospace face.
    MONO = "'IBM Plex Mono', ui-monospace, 'SF Mono', Consolas, monospace"
    TRACK = 0.06                     # em, applied to Latin/numeral runs only
    def _mono(tm):
        tag, content = tm.group(1), tm.group(2)
        if content.strip() and not re.search(r'[\u2E80-\u9FFF\uFF00-\uFFEF\u3000-\u303F]', content):
            if "font-family=" in tag:
                tag = re.sub(r'font-family="[^"]*"', f'font-family="{MONO}"', tag)
            else:
                tag = "<text font-family=\"" + MONO + "\"" + tag[5:]
            fsm = re.search(r'font-size="([\d.]+)"', tag)
            if fsm and "letter-spacing" not in tag:
                tag += f' letter-spacing="{float(fsm.group(1)) * TRACK:.2f}"'
        return tag + ">" + content + "</text>"
    svg = re.sub(r'(<text\b[^>]*?)>([^<]*)</text>', _mono, svg, flags=re.S)

    # any element STILL off-palette (timeline / gantt / comparison) -> blue / grey / white
    THEME = {"#FAFAF8", "#333333", "#737373", "#BDBDBD", "#D4D4D2", "#E0E0E0", "#002FA7", "#FFFFFF"}
    def _lum(c):
        r, g, b = int(c[1:3], 16), int(c[3:5], 16), int(c[5:7], 16)
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255
    svg = re.sub(r'(<text\b[^>]*?fill=")(#[0-9A-Fa-f]{6})(")',
                 lambda m: m.group(0) if m.group(2).upper() in THEME
                 else m.group(1) + ("#FFFFFF" if _lum(m.group(2)) < 0.45 else INK) + m.group(3), svg)
    svg = re.sub(r'stroke="(#[0-9A-Fa-f]{6})"',
                 lambda m: m.group(0) if m.group(1).upper() in THEME else f'stroke="{LINE}"', svg)
    svg = re.sub(r'fill="(#[0-9A-Fa-f]{6})"',
                 lambda m: m.group(0) if m.group(1).upper() in THEME
                 else (f'fill="{IKB}"' if _lum(m.group(1)) < 0.45 else 'fill="#FFFFFF"'), svg)

    # reserve a TOP MARGIN (天头) for the big centred title: grow the canvas upward
    # and slide all content down, so the title breathes instead of touching the edge.
    sm = re.search(r'<svg[^>]*width="(\d+)"[^>]*height="(\d+)"[^>]*viewBox="0 0 \d+ (\d+)"', svg)
    if sm:
        Wv, Hv = int(sm.group(1)), int(sm.group(2))
        TOP = TOKENS["tuning"]["guizang_top_margin"]
        newH = Hv + TOP
        svg = svg.replace(f'height="{Hv}"', f'height="{newH}"')          # svg + full-canvas rects
        svg = svg.replace(f'viewBox="0 0 {Wv} {Hv}"', f'viewBox="0 0 {Wv} {newH}"')
        dot_rect = f'<rect width="{Wv}" height="{newH}" fill="url(#gzdot)"/>'
        if dot_rect in svg:
            svg = svg.replace(dot_rect, dot_rect + f'<g transform="translate(0,{TOP})">', 1)
        else:
            svg = re.sub(r'(<rect width="' + str(Wv) + r'" height="' + str(newH) + r'" fill="#FAFAF8"/>)',
                         r'\1' + f'<g transform="translate(0,{TOP})">', svg, count=1)
        svg = svg.replace("</svg>", "</g></svg>", 1)
    _sub_report("歸藏风")
    return svg


def fit_title(svg):
    """Wrap a doc title that is wider than the canvas.

    Every renderer draws the title as one centred line. A long Chinese title on a
    narrow figure (a comparison table, a short timeline) therefore runs off both
    edges and gets clipped. Rather than shrink the type — which breaks the visual
    standard — split it into balanced lines, push the rest of the drawing down by
    the extra height, and grow the canvas to match. Verbatim: only line breaks are
    inserted, never an edited or dropped character."""
    tm = re.search(r'<text [^>]*stroke-width="0\.3"[^>]*>[^<]*</text>', svg)
    if not tm:
        return svg
    tag = tm.group(0)
    txt = re.search(r'>([^<]*)</text>', tag).group(1)
    fsm = re.search(r'font-size="([\d.]+)"', tag)
    wm = re.search(r'<svg[^>]*width="(\d+)"[^>]*height="(\d+)"', svg)
    if not (fsm and wm and txt.strip()):
        return svg
    fs, W, H = float(fsm.group(1)), int(wm.group(1)), int(wm.group(2))
    room = W - 80                                        # keep a margin either side
    cw = lambda s: sum(fs if ord(c) > 0x2E80 else fs * 0.55 for c in s)
    if cw(txt) <= room:
        return svg
    n = math.ceil(cw(txt) / room)                        # balanced lines
    per = math.ceil(len(txt) / n)
    lines, i = [], 0
    while i < len(txt):
        lines.append(txt[i:i + per]); i += per
    ym = re.search(r'\by="([\d.]+)"', tag)
    y0 = float(ym.group(1)) if ym else 44.0
    lh = fs * 1.32
    extra = int(lh * (len(lines) - 1))
    xm = re.search(r'\bx="([-\d.]+)"', tag)
    tx = xm.group(1) if xm else str(W // 2)
    body = "".join(f'<tspan x="{tx}" dy="{0 if k == 0 else lh:.1f}">{ln}</tspan>'
                   for k, ln in enumerate(lines))
    newtag = re.sub(r'>([^<]*)</text>', ">" + body + "</text>", tag)
    svg = svg.replace(tag, newtag, 1)
    # push the drawing down and grow the canvas by the added title height
    svg = svg.replace(f'height="{H}"', f'height="{H + extra}"')
    svg = re.sub(r'(viewBox="0 0 \d+ )' + str(H) + r'"', r'\g<1>' + str(H + extra) + '"', svg)
    head_end = svg.index(newtag) + len(newtag)
    svg = (svg[:head_end] + f'<g transform="translate(0,{extra})">' +
           svg[head_end:].replace("</svg>", "</g></svg>", 1))
    return svg


_GUIZANG_MODES = {"歸藏风", "归藏风", "guizang", "swiss", "ikb"}
# Back-compat only. v1.0.1 shipped the blogger's name mis-spelled (葬 for 藏) and
# used 流 where the mode is now 风. Maps written against v1.0.1 must keep working,
# so these values are still ACCEPTED — but they are never printed, documented or
# emitted anywhere. Do not add to them; do not surface them to users.
_GUIZANG_LEGACY = {"歸葬流", "归葬流", "歸藏流", "归藏流"}
_MONO_MODES = {"白描", "baimiao", "bai-miao", "mono", "monochrome", "print", "court"}


def _wants_mono(m, argv_mode):
    return bool(argv_mode) or str(m.get("visual_mode", "")).strip().lower() in _MONO_MODES \
        or m.get("visual_mode") == "白描"


ALL_FORMATS = ("svg", "png", "drawio", "pptx", "vsdx")


def _draft_base(base, m):
    """Mark an UNCONFIRMED figure in its own filename.

    The checkpoint used to print `>> CHECKPOINT REQUIRED` and then hand over a
    file named exactly like a final one. The failure that guards against is a
    specific and expensive one: a draft read of a judgment being filed as if it
    were settled. A printed warning scrolls away; a filename travels with the
    file, into the folder, the email and the bundle.

    Nothing is blocked — the figure renders either way. It is simply called what
    it is until `checkpoint.confirmed` says otherwise.
    """
    if (m.get("checkpoint") or {}).get("confirmed") is True:
        return base, False
    return base + "-draft", True


def main(mapfile, base="final", strict=False, mono=False, theme=None,
         pptx_fonts="safe", formats=ALL_FORMATS):
    try:
        m = load_map(mapfile)
    except RuntimeError as e:
        print(f"Error: {e}")
        return 1
    # Red is opt-in, and that is enforced HERE rather than requested of the model:
    # an emphasis the map does not attribute to the user is removed before anything
    # is drawn, so no downstream format can carry it either.
    for note in strip_unearned_emphasis(m):
        print(note)
    base, is_draft = _draft_base(base, m)
    if is_draft:
        print("checkpoint: not confirmed — writing *-draft.* so an unconfirmed read "
              "cannot be filed as a final. Set checkpoint.confirmed=true after the user "
              "confirms the structure.")
    mod = choose(m)
    svg_path = base + ".svg"
    vm = str(m.get("visual_mode", "")).strip().lower()
    is_guizang = (theme == "guizang" or m.get("visual_mode") in ("歸藏风", "归藏风")
                  or vm in _GUIZANG_MODES or m.get("visual_mode") in _GUIZANG_LEGACY)
    try:
        mod._THEME = "guizang" if is_guizang else None   # renderers may adapt geometry (roomier boxes)
    except Exception:
        pass
    try:
        validate_map(m)
        svg, w, h = mod.render(m)
    except RuntimeError as e:
        print(f"Error: {e}")
        return 1
    except Exception as e:
        print(f"Error: {type(e).__name__}: {e}")
        return 1
    svg = fit_title(svg)          # long titles wrap before any theme is applied
    if is_guizang:
        svg = to_guizang(svg, m.get("layout"))
        print("mode: 歸藏风 (Guizang Swiss / IKB — online / lecture)")
    elif _wants_mono(m, mono):
        svg = to_monochrome(svg)
        print("mode: 白描 (monochrome print / court)")
    open(svg_path, "w", encoding="utf-8").write(svg)
    print(f"SVG: {svg_path}  {w}x{h}")
    png_path = base + ".png"
    try:
        engine = svg_to_png(svg_path, png_path)
        print(f"PNG: {png_path}  (via {engine})")
    except Exception as e:
        print(f"PNG skipped: {e}")
    # editable draw.io export (additive; NEVER breaks the SVG/PNG deliverable).
    # Node+edge layouts also get an editable .drawio and a .drawio.svg (a valid
    # SVG that additionally opens editable in draw.io). Guarded end-to-end so any
    # failure here is a skipped extra, not a broken render.
    if "drawio" in formats and m.get("layout") in export_drawio.SUPPORTED_LAYOUTS:
        try:
            mxfile, _, _ = export_drawio.build_model(m)
            _dmode = "guizang" if is_guizang else ("baimiao" if _wants_mono(m, mono) else None)
            _hub = None
            if _dmode == "guizang" and m.get("edges") and m.get("nodes"):
                _d = {n["id"]: 0 for n in m["nodes"]}
                for _e in m["edges"]:
                    if _e.get("from") in _d: _d[_e["from"]] += 1
                    if _e.get("to") in _d: _d[_e["to"]] += 1
                if _d:
                    _hid = max(_d, key=lambda i: _d[i])
                    _ids = [n["id"] for n in m["nodes"]]
                    _hub = "c%d" % _ids.index(_hid)      # drawio cells are c0, c1, …
            mxfile = export_drawio.theme_drawio(mxfile, _dmode, _hub)
            drawio_path = base + ".drawio"
            open(drawio_path, "w", encoding="utf-8").write(mxfile)
            print(f"drawio: {drawio_path}  (editable)")
            dsvg_path = base + ".drawio.svg"
            open(dsvg_path, "w", encoding="utf-8").write(
                export_drawio.embed_in_svg(svg, mxfile))
            print(f"drawio: {dsvg_path}  (SVG + embedded editable model)")
        except Exception as e:
            print(f"drawio skipped: {e}")

    # editable .pptx — transcribed from the master SVG, so the deck IS the
    # delivered figure (same geometry, same mode). Every box, colour, word and
    # connector is a native PowerPoint object the lawyer can keep editing.
    try:
        if "pptx" not in formats:
            raise RuntimeError("skipped by --formats")
        pptx_path, n_obj = export_pptx.export(svg_path, base + ".pptx", fonts=pptx_fonts)
        print(f"pptx:   {pptx_path}  ({n_obj} editable objects, fonts: {pptx_fonts})")
    except Exception as e:
        print(f"pptx skipped: {e}")

    # editable .vsdx — the one format on ProcessOn's import list that carries
    # PLACED SHAPES. Its other nine (xmind / mmap / km / mm / opml / md / txt /
    # csv, plus its own private .pos) are outline and mind-map formats: pushing a
    # litigation figure through one would flatten dates, routes and arrow
    # direction into a tree and lose the argument. Opens in Visio / WPS / Edraw too.
    try:
        if "vsdx" not in formats:
            raise RuntimeError("skipped by --formats")
        vsdx_path, n_sh = export_vsdx.export(svg_path, base + ".vsdx")
        print(f"vsdx:   {vsdx_path}  ({n_sh} editable shapes — ProcessOn / Visio / WPS)")
    except Exception as e:
        print(f"vsdx skipped: {e}")

    # semantic audit
    try:
        import audit
        audit.report(m)
    except Exception as e:
        print(f"(audit unavailable: {e})")
    # final-SVG visual lint (read-only)
    try:
        import lint
        warns = lint.lint_svg(svg, w, h)
        if warns:
            print(f"lint: {len(warns)} warning(s)")
            for wn in warns:
                print("  - " + wn)
            if strict:
                return 2
        else:
            print("lint: clean")
    except Exception as e:
        print(f"(lint unavailable: {e})")
    return 0


def _cli(argv):
    """Subcommands: `validate <map>`, `lint <svg>`, or the default render."""
    if argv and argv[0] == "validate":
        try:
            validate_map(load_map(argv[1]))
            print(f"validate: OK — {argv[1]}")
            return 0
        except Exception as e:
            print(f"validate: {e}")
            return 1
    if argv and argv[0] in ("lint", "check"):
        import lint
        return lint.main(argv[1])
    strict = "--strict" in argv
    mono = any(a in ("--baimiao", "--mono", "--print", "--court", "--白描") for a in argv)
    _GZ_FLAGS = ("--guizang", "--swiss", "--ikb", "--歸藏风", "--归藏风",
                 "--歸葬流", "--归葬流")   # last two: deprecated v1.0.1 spelling
    theme = "guizang" if any(a in _GZ_FLAGS for a in argv) else None
    pptx_fonts = "safe" if "--pptx-fonts=safe" in argv else "master"
    # Default is EVERY format. The premise of this skill is that the lawyer's own
    # tool is not ours to guess — draw.io, PowerPoint, ProcessOn, Visio and WPS
    # are all in real use — so all of them are written unless asked to narrow.
    formats = ALL_FORMATS
    fmt_arg = next((a for a in argv if a.startswith("--formats=")), None)
    if fmt_arg:
        formats = tuple(x.strip().lower() for x in fmt_arg.split("=", 1)[1].split(",") if x.strip())
        unknown = [f for f in formats if f not in ALL_FORMATS]
        if unknown:
            print(f"unknown format(s) {unknown}; known: {list(ALL_FORMATS)}")
            return 1
        if "svg" not in formats:
            formats = ("svg",) + formats      # every other format is derived from it
    drop = (("--strict", "--baimiao", "--mono", "--print", "--court", "--白描",
             "--pptx-fonts=master", "--pptx-fonts=safe") + _GZ_FLAGS
            + ((fmt_arg,) if fmt_arg else ()))
    argv = [a for a in argv if a not in drop]
    return main(argv[0], argv[1] if len(argv) > 1 else "final", strict=strict, mono=mono,
                theme=theme, pptx_fonts=pptx_fonts, formats=formats)



def _quiet_broken_pipe():
    """`… | head` closes the pipe early; without this the script ends on a
    traceback, which looks like a crash to anyone reading the terminal."""
    import signal
    try:
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    except (AttributeError, ValueError):
        pass

if __name__ == "__main__":
    _quiet_broken_pipe()
    sys.exit(_cli(sys.argv[1:]) or 0)
