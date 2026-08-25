#!/usr/bin/env python3
"""Regenerate the README's gallery from examples/.

Why this is a script and not a folder of hand-made files
--------------------------------------------------------
The gallery had gone stale without anyone noticing: it was showing flat
arrowheads, a blue dot-grid, a stadium-shaped time band and 12px 白描 corners —
all of which had been changed. Nothing regenerates a PNG when behaviour changes,
so the front page kept advertising last month's output while every guard passed.

Now the gallery is DERIVED, and a guard asserts that regenerating it produces the
files that are checked in. If a change alters what the figures look like, the
test suite says so and this script is the one-line fix.

    python3 scripts/make_gallery.py            # rebuild everything
    python3 scripts/make_gallery.py --check    # fail if the checked-in files are stale

Labels are drawn with a CJK-capable face and the glyph coverage is VERIFIED
before drawing. A previous release shipped comparison images whose Chinese
labels were empty boxes, because the font in use had no CJK glyphs and nothing
complained — the renderer happily draws tofu.
"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
sys.path.insert(0, HERE)

GAP = 40
LABEL_H = 178            # the label block plus REAL air before the figure.
                         # 16px of gap made the two read as one muddle — a
                         # caption bolted onto the artwork rather than a
                         # heading standing over it.
MAX_SIDE = 3400          # nothing in a README should be wider than this
# A portrait figure tiles side by side; a landscape one stacks. Forcing a common
# HEIGHT on three landscape panels produced a 13951px ribbon — technically three
# figures, practically unreadable.
PORTRAIT_H = 1400        # common height when tiling horizontally
LANDSCAPE_W = 1100       # common width when stacking vertically

# layout name -> example file
SHOTS = ["timeline-points", "timeline-dated", "timeline-gantt", "flowchart",
         "relationship", "relation-tree", "comparison-table"]
# the three-mode comparisons, including the dense stress case
MODES = SHOTS + ["flow-contract-review"]
DENSE = ("relation-dense", os.path.join(ROOT, "tests", "fixtures", "edge_relation_dense.json"))

MODE_SPECS = [("奇川风", []), ("歸藏风", ["--guizang"]), ("白描", ["--baimiao"])]


# The mode names are set in a HEAVY SONG — the stand-in for 方正小标宋简体, which
# is commercial and cannot ship here. It is the same face 奇川风 asks for in its
# own titles (方正小标宋简体 → 思源宋体 → 华文中宋). A sans label under a Song
# figure reads as a caption bolted on afterwards; a Song label belongs to the work.
#
# The INDEX matters as much as the file. These are .ttc collections and face 0 is
# the JAPANESE cut: 「歸藏风」 set from it differs from the Simplified form by two
# thousand pixels, and nothing would have complained. Simplified Chinese is face 2.
SONG_FILES = ["/usr/share/fonts/opentype/noto/NotoSerifCJK-Black.ttc",
              "/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc"]
SANS_FILES = ["/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
              "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"]


def _face_index(path, want="SC"):
    """The Simplified-Chinese face inside a .ttc, not whichever one comes first."""
    from PIL import ImageFont
    for i in range(8):
        try:
            name = ImageFont.truetype(path, 12, index=i).getname()[0]
        except Exception:
            break
        if name.endswith(want):
            return i
    return 0


def _font(paths, size, probe="奇"):
    """A face that actually HAS the glyphs we are about to draw, in the right cut.

    Checked, not assumed: a release once shipped comparison images whose Chinese
    labels were blank boxes because the font had no CJK coverage — a renderer
    draws tofu perfectly happily — and this one shipped Japanese glyph forms for
    the same reason, silently."""
    from PIL import ImageFont
    for path in paths:
        if not os.path.exists(path):
            continue
        f = ImageFont.truetype(path, size, index=_face_index(path))
        if f.getbbox(probe)[2] > 0 and f.getbbox(probe) != f.getbbox("\u25a1"):
            return f
    raise RuntimeError(f"no font with real glyphs among {paths}")


def _cjk_font(size):
    return _font(SONG_FILES + SANS_FILES, size)


def has_cjk_font():
    """Whether a CJK face is present at all.

    Callers that only WANT to measure typography need to be able to ask, rather
    than find out by catching the exception `_font` raises. A bare CI runner has
    no Noto CJK installed, and a guard that dies there is reporting the runner's
    package list, not a defect in this repo."""
    return any(os.path.exists(p) for p in SONG_FILES + SANS_FILES)


def _render(map_path, out_base, flags):
    """Render and hand back the PNG, whatever it ended up being called.

    A map without a confirmed checkpoint is written as `<name>-draft.*` — that is
    the delivery rule working, not a failure — so the stress fixture, which has no
    checkpoint block, lands under the draft name."""
    subprocess.run([sys.executable, os.path.join(HERE, "render.py"), map_path,
                    out_base, "--formats=svg,png"] + flags,
                   capture_output=True, check=True)
    for candidate in (out_base + ".png", out_base + "-draft.png"):
        if os.path.exists(candidate):
            return candidate
    raise RuntimeError(f"no PNG produced for {map_path}")


def renderer_id():
    """当前机器用什么渲染器、什么版本。gallery 的图是**光栅化产物**，
    换一个渲染器（或同一个的不同版本）就会有像素差异 —— 与代码改没改无关。

    CI 上撞过两次：本机 LibreOffice 24.2 渲出来与签进仓库的图一致，
    CI 的 LibreOffice（Ubuntu 源，版本不同）字体光栅化结果不同，于是三张全判过期。
    第一次我以为是字节差异、改成按像素比，没解决 —— 差异本来就在像素层面。

    所以记下身份：换了渲染器就跳过比对并说明，不假装检查过。
    在生成 gallery 的那台机器上，这条门禁照旧是真的。
    """
    import shutil
    import subprocess
    for exe, args in (("rsvg-convert", ["--version"]), ("resvg", ["--version"]),
                      ("inkscape", ["--version"]), ("soffice", ["--version"])):
        if shutil.which(exe):
            try:
                out = subprocess.run([exe] + args, capture_output=True, text=True,
                                     timeout=60)
                ver = (out.stdout or out.stderr).strip().splitlines()[0][:60]
            except Exception:
                ver = "unknown"
            return f"{exe} {ver}"
    return "none"


def font_id():
    """签进仓库的 gallery 图，它上面的字是**哪一份字体文件**画的。

    与 renderer_id 同一个道理：`ImageFont.getbbox` 的结果随字体版本变化，
    而有一条守卫拿它与图上那条深红标线的宽度比（容差只有 8px）——
    换一台机器、字体版本不同，那 8px 兜不住，必然假红。
    """
    try:
        f = _cjk_font(54)
        import os as _os
        path = getattr(f, "path", "") or ""
        size = _os.path.getsize(path) if path and _os.path.exists(path) else 0
        return f"{_os.path.basename(path)}:{size}"
    except Exception:
        return "none"


def stamp_path():
    return os.path.join(ROOT, "assets", "screenshots", ".renderer")


def build(check_only=False):
    from PIL import Image, ImageDraw
    shots_dir = os.path.join(ROOT, "assets", "screenshots")
    modes_dir = os.path.join(ROOT, "assets", "modes")
    os.makedirs(shots_dir, exist_ok=True)
    os.makedirs(modes_dir, exist_ok=True)
    work = tempfile.mkdtemp(prefix="gallery-")
    stale, written = [], 0

    # 换了渲染器就不比 —— 比了必然假红（见 renderer_id 的说明）。
    #
    # **能确定同机才比；只要不确定就不比。** 第一版写成
    # `if _was and _was != _rid`：身份戳缺席时 `_was` 是空串，条件不成立，
    # 于是**照旧比对** —— 而身份戳缺席恰恰说明「无从判断是不是同一台机器」，
    # 那时最该跳过。CI 上第三次假红就是这个口子
    # （点号开头的文件在 Windows 上拷贝时容易漏，`.github` 已经漏过一次）。
    #
    # 判据反过来写：**只有身份戳存在且与本机一致，才真的比对。**
    # 身份戳是**两行**：第一行渲染器、第二行字体（见下方写入处）。
    # 而这里原来拿整份内容去跟**一行**的 renderer_id 比 —— 两者永远不相等，
    # 于是 gallery 比对在任何机器上都从不执行，包括生成它的那一台。
    # 实测：本机的 renderer_id 与 font_id 与文件里的两行逐字相同，却仍然打印
    # 「略过 gallery 比对」。一道从不执行的门禁，比没有这道门禁更坏，
    # 因为它让人以为图被检查过。判据补齐成两行对两行之后，门禁真的跑起来并通过。
    _rid = renderer_id()
    if check_only:
        _stamp = stamp_path()
        _was = ""
        try:
            if os.path.exists(_stamp):
                _was = open(_stamp, encoding="utf-8").read().strip()
        except OSError:
            _was = ""
        _want = _rid + "\n" + font_id()
        if _was != _want:
            if not _was:
                print(f"  略过 gallery 比对：没有渲染器身份戳"
                      f"（assets/screenshots/.renderer），无从判断是不是同一台机器")
            else:
                print(f"  略过 gallery 比对：签进仓库的图由\n    「{_was}」\n"
                      f"    渲出，本机是\n    「{_want}」")
            print(f"    gallery 是光栅化产物，换渲染器必有像素差异，"
                  f"与代码改没改无关。")
            print(f"    要在本机重生成并认领身份：python3 scripts/make_gallery.py")
            return []

    def emit(path, im):
        nonlocal written
        if check_only:
            if not os.path.exists(path):
                stale.append(os.path.basename(path) + " (missing)")
                return
            # **按像素比，不按字节比。** 字节比会在任何异机环境下误报：
            # PNG 的字节受编码器版本与压缩参数影响，同一份 SVG 在本机
            # （soffice 24.2）与 CI（libreoffice-draw，版本不同）渲出的 PNG
            # 像素完全相同、字节却不同 —— CI 上实测三张全部误报，
            # 而逐像素比对显示 diff.getbbox() 是 None（完全一致）。
            # 这条检查要抓的是「代码改了、图没跟着更新」，那是**内容**差异；
            # 按字节比会把编码差异也算进来，成了一条会误报的检查。
            # 「有反例的检查一条不留」—— 所以改判据，不是删检查。
            from PIL import ImageChops
            _a = im.convert("RGB")
            try:
                _b = Image.open(path).convert("RGB")
            except Exception:
                stale.append(os.path.basename(path) + " (unreadable)")
                return
            if _a.size != _b.size:
                stale.append(f"{os.path.basename(path)} (size {_a.size} != {_b.size})")
            elif ImageChops.difference(_a, _b).getbbox() is not None:
                stale.append(os.path.basename(path))
        else:
            im.save(path)
            written += 1

    try:
        f_name = _cjk_font(54)                     # heavy Song, the figure's own voice
        f_tag = _font(SANS_FILES, 19)                # small letter-spaced Latin tag
        for name in SHOTS:
            src = os.path.join(ROOT, "examples", name + ".json")
            png = _render(src, os.path.join(work, name), [])
            emit(os.path.join(shots_dir, name + ".png"), Image.open(png))

        jobs = [(n, os.path.join(ROOT, "examples", n + ".json")) for n in MODES]
        jobs.append(DENSE)
        for name, src in jobs:
            raw = []
            for label, flags in MODE_SPECS:
                png = _render(src, os.path.join(work, f"{name}-{label}"), flags)
                raw.append((label, Image.open(png).convert("RGB")))
            landscape = raw[0][1].width > raw[0][1].height * 1.1

            if landscape:
                panels = [(l, im.resize((LANDSCAPE_W,
                                         max(1, int(im.height * LANDSCAPE_W / im.width))),
                                        Image.LANCZOS)) for l, im in raw]
                W = LANDSCAPE_W
                H = sum(p.height + LABEL_H for _, p in panels) + GAP * (len(panels) - 1)
            else:
                panels = [(l, im.resize((max(1, int(im.width * PORTRAIT_H / im.height)),
                                         PORTRAIT_H), Image.LANCZOS)) for l, im in raw]
                W = sum(p.width for _, p in panels) + GAP * (len(panels) - 1)
                H = PORTRAIT_H + LABEL_H

            canvas = Image.new("RGB", (W, H), "white")
            d = ImageDraw.Draw(canvas)

            def head(cx, top, label, tag, width):
                """A heading, not a decorated caption.

                The rule is the author's deep red, and exactly as wide as the
                name. The width is the part that had been wrong: it was
                `panel_width * 0.24`, a number picked because one was needed, so
                the same label block changed proportion between a 900px panel and
                a 2800px one. Tying it to the text keeps the block's proportions
                fixed whatever the figure beside it happens to be."""
                tw = d.textlength(tag, font=f_tag) + 3 * (len(tag) - 1)
                tx = cx - tw / 2
                for ch in tag:                      # tracking, drawn by hand
                    d.text((tx, top), ch, fill=(156, 163, 175), font=f_tag)
                    tx += d.textlength(ch, font=f_tag) + 3
                bb = d.textbbox((0, 0), label, font=f_name)
                nw = bb[2] - bb[0]
                d.text((cx - nw / 2, top + 36), label, fill=(31, 41, 51), font=f_name)
                ry = top + 36 + (bb[3] - bb[1]) + 22
                d.line([(cx - nw / 2, ry), (cx + nw / 2, ry)],
                       fill=(153, 27, 27), width=3)

            TAGS = {"奇川风": "QICHUAN", "歸藏风": "GUIZANG", "白描": "BAIMIAO"}
            x = y = 0
            for label, p in panels:
                if landscape:
                    head(W / 2, y + 14, label, TAGS.get(label, ""), W)
                    canvas.paste(p, (0, y + LABEL_H))
                    y += p.height + LABEL_H + GAP
                else:
                    head(x + p.width / 2, 14, label, TAGS.get(label, ""), p.width)
                    canvas.paste(p, (x, LABEL_H))
                    x += p.width + GAP

            if canvas.width > MAX_SIDE:
                k = MAX_SIDE / canvas.width
                canvas = canvas.resize((MAX_SIDE, max(1, int(canvas.height * k))),
                                       Image.LANCZOS)
            emit(os.path.join(modes_dir, name + "-3modes.png"), canvas)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    if check_only:
        return stale
    # 生成成功之后记下是谁渲的 —— 下次比对时据此判断能不能比（见 renderer_id）
    try:
        with open(stamp_path(), "w", encoding="utf-8") as _fh:
            _fh.write(_rid + "\n" + font_id() + "\n")
    except OSError:
        pass
    print(f"gallery: wrote {written} image(s)　（渲染器：{_rid}）")
    return []



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
    # Say what is missing instead of ending on a traceback. Regenerating the
    # gallery needs a CJK face to draw the mode labels, and a Windows box has
    # no Noto CJK by default — a stack trace tells that user nothing they can act on.
    if not has_cjk_font():
        print("cannot build the gallery: no CJK font found.\n"
              "  the figures themselves render fine — this only affects the\n"
              "  labelled comparison images in assets/modes/.\n"
              "  install one (Linux: apt install fonts-noto-cjk · macOS: it ships with\n"
              "  the system · Windows: Noto Serif CJK / Source Han Serif), then re-run.")
        raise SystemExit(1)
    if "--check" in sys.argv[1:]:
        bad = build(check_only=True)
        if bad:
            print("gallery is STALE — run `python3 scripts/make_gallery.py`:")
            for b in bad:
                print("  ·", b)
            raise SystemExit(1)
        print("gallery is current")
    else:
        build()
