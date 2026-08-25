#!/usr/bin/env python3
"""Verify a generated .pptx by RENDERING it and measuring where the text landed.

Why this exists
---------------
Every other check in this repo reasons about the files we write. That is how a
deck shipped with 33%-oversized type, re-wrapped captions, a stacked column of
year labels and a decision node whose question was silently dropped — the XML
said the right things, and nobody could see the result.

This closes that gap without eyes: convert the deck to PDF, pull every word's
real bounding box out with `pdftotext -bbox`, and compare against the master
SVG's own text positions. It catches, mechanically:

  * text that never rendered at all (the empty decision hexagon);
  * text that moved (a missing group transform, a wrong baseline);
  * text that changed size (the px-vs-point bug);
  * labels that collapsed onto one spot (the time-band swallowing the years).

Caveat, stated plainly: the renderer here is LibreOffice, not PowerPoint, and
its font substitutions differ. Positions are therefore compared with a real
tolerance, and a clean run means "nothing is grossly wrong", not "pixel-perfect
in PowerPoint". A human still opens the file. This tool exists so that the human
is checking taste, not hunting for dropped text.

Requires `soffice` and `pdftotext`; skips cleanly when either is missing.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_pptx import parse_svg, _text_w          # noqa: E402

PT_PER_PX = 0.75
TOL_PX = 12.0          # generous: LibreOffice substitutes fonts and metrics drift


def available():
    return bool(shutil.which("pdftotext")) and bool(
        shutil.which("soffice") or shutil.which("libreoffice"))


def _render_words(pptx_path, workdir):
    """(word, xMin, yMin, xMax, yMax) in PX, plus the rendered page size."""
    soffice = shutil.which("soffice") or shutil.which("libreoffice")
    subprocess.run([soffice, "--headless", "-env:UserInstallation=file://" + workdir + "/lo",
                    "--convert-to", "pdf", "--outdir", workdir, pptx_path],
                   capture_output=True, timeout=180)
    # splitext, not [:-5]: this verifier is used on .pptx AND .vsdx, and those
    # happening to share an extension length is a coincidence, not a design.
    pdf = os.path.join(workdir, os.path.splitext(os.path.basename(pptx_path))[0] + ".pdf")
    if not os.path.exists(pdf):
        raise RuntimeError("LibreOffice produced no PDF")
    box = os.path.join(workdir, "words.xml")
    subprocess.run(["pdftotext", "-bbox", pdf, box], capture_output=True, timeout=120)
    xml = open(box, encoding="utf-8", errors="replace").read()
    pg = re.search(r'<page width="([\d.]+)" height="([\d.]+)"', xml)
    W = float(pg.group(1)) / PT_PER_PX if pg else 0
    H = float(pg.group(2)) / PT_PER_PX if pg else 0
    words = []
    for m in re.finditer(r'<word xMin="([\d.]+)" yMin="([\d.]+)" '
                         r'xMax="([\d.]+)" yMax="([\d.]+)">(.*?)</word>', xml):
        x0, y0, x1, y1 = [float(v) / PT_PER_PX for v in m.groups()[:4]]
        t = (m.group(5).replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", '"').replace("&#39;", "'"))
        words.append((t, x0, y0, x1, y1))
    return words, W, H


def verify(svg_path, doc_path):
    """Compare a rendered document against its master SVG.

    Works on any file LibreOffice can convert — .pptx and .vsdx are both in use.
    Returns a list of problems; empty means the render matches the master."""
    svg = open(svg_path, encoding="utf-8").read()
    want = [p for p in parse_svg(svg) if p["k"] == "text"]
    work = tempfile.mkdtemp(prefix="verifyppt-")
    try:
        words, PW, PH = _render_words(doc_path, work)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    # stitch the words back into lines: pdftotext splits on spaces, so
    # "a. 乙签收催款函" arrives as two words on one baseline
    lines = []
    for t, x0, y0, x1, y1 in words:
        for L in lines:
            # the next word must sit to the RIGHT and close by. Without the
            # lower bound a word far to the LEFT satisfied "gap < 22" (the gap
            # being negative) and text from two different cards was glued into
            # one phantom line.
            if abs((L["y0"] + L["y1"]) / 2 - (y0 + y1) / 2) < 4 and 0 <= x0 - L["x1"] < 22:
                L["t"] += t; L["x1"] = max(L["x1"], x1)
                L["y0"] = min(L["y0"], y0); L["y1"] = max(L["y1"], y1)
                break
        else:
            lines.append({"t": t, "x0": x0, "y0": y0, "x1": x1, "y1": y1})

    # Match by GROUPING on the text itself, then pairing within a group by
    # position. Scanning line-by-line let a short label ("2013") be stolen by a
    # card date ("2013.06.20") and reported as a 435px drift that never happened;
    # a verifier that cries wolf is worse than none, because it trains you to
    # ignore it.
    def norm(t):
        return re.sub(r"\s+", "", t)

    by_text = {}
    for L in lines:
        by_text.setdefault(norm(L["t"]), []).append(L)

    bad = []
    for key, group in _group_by(want, lambda p: norm(p["t"])).items():
        pool = by_text.get(key)
        if pool is None:                      # try a containment fallback once
            pool = [L for k, ls in by_text.items() for L in ls
                    if len(key) > 3 and (key in k or k in key)]
        if not pool:
            for p in group:
                bad.append(f"MISSING  {p['t'][:22]!r} never rendered")
            continue
        want_pos, got_pos = [], []
        for p in group:
            tw = _text_w(p["t"], p["fs"], p.get("track", 0))
            cx = p["x"] + (0 if p["anchor"] == "middle" else
                           (tw / 2 if p["anchor"] == "start" else -tw / 2))
            want_pos.append((p, cx, p["y"] - p["fs"] * 0.36))
        for L in pool:
            got_pos.append((L, (L["x0"] + L["x1"]) / 2, (L["y0"] + L["y1"]) / 2))
        want_pos.sort(key=lambda t: (round(t[2]), round(t[1])))
        got_pos.sort(key=lambda t: (round(t[2]), round(t[1])))
        if len(got_pos) < len(want_pos):
            bad.append(f"MISSING  {key[:22]!r} rendered {len(got_pos)}x, "
                       f"master has {len(want_pos)}")
        for (p, cx, cy), (L, gx, gy) in zip(want_pos, got_pos):
            dx, dy = abs(gx - cx), abs(gy - cy)
            if dx > TOL_PX or dy > TOL_PX:
                bad.append(f"MOVED    {p['t'][:22]!r} off by dx={dx:.0f} dy={dy:.0f}px")
                continue
            got_h = L["y1"] - L["y0"]
            if got_h > p["fs"] * 1.6 or got_h < p["fs"] * 0.5:
                bad.append(f"RESIZED  {p['t'][:22]!r} rendered {got_h:.0f}px tall, "
                           f"master says {p['fs']:.0f}px")
    return bad


def _group_by(items, key):
    out = {}
    for it in items:
        out.setdefault(key(it), []).append(it)
    return out



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
    if not available():
        print("verify_pptx: soffice / pdftotext unavailable — skipped"); sys.exit(0)
    problems = verify(sys.argv[1], sys.argv[2])
    for p in problems:
        print("  " + p)
    print(f"{'FAIL' if problems else 'OK'}: {len(problems)} problem(s)")
    sys.exit(1 if problems else 0)
