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


def build(check_only=False):
    from PIL import Image, ImageDraw
    shots_dir = os.path.join(ROOT, "assets", "screenshots")
    modes_dir = os.path.join(ROOT, "assets", "modes")
    os.makedirs(shots_dir, exist_ok=True)
    os.makedirs(modes_dir, exist_ok=True)
    work = tempfile.mkdtemp(prefix="gallery-")
    stale, written = [], 0

    def emit(path, im):
        nonlocal written
        if check_only:
            if not os.path.exists(path):
                stale.append(os.path.basename(path) + " (missing)")
                return
            tmp = os.path.join(work, "cmp.png")
            im.save(tmp)
            if open(tmp, "rb").read() != open(path, "rb").read():
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
    print(f"gallery: wrote {written} image(s)")
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
