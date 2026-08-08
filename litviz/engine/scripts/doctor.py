#!/usr/bin/env python3
"""doctor.py — environment self-check for a fresh clone.

A bare `git clone` gives you the code but not the system tools it shells out to,
and the failures that follow ("dot: not found", a PNG that never appears, a title
in the wrong typeface) are hard to read. Run this first:

    python3 scripts/doctor.py

It reports what is present, what is missing, and exactly what to install — and
tells you which features degrade if you skip an optional item. Exit code is 0 when
everything REQUIRED is present, 1 otherwise, so CI can gate on it.
"""
import os
import shutil
import subprocess
import sys

OK, WARN, BAD = "  ok  ", " warn ", " MISS "


def _has(cmd):
    return shutil.which(cmd) is not None


def _fonts():
    """Installed font families, as one newline-separated blob (None if unknown).

    `fc-list` is a fontconfig tool — it exists on Linux and macOS and NEVER on
    Windows, where most of this skill's users are. Reporting "cannot verify
    typefaces" to all of them is not a check, it is a shrug: it leaves the one
    platform whose font situation is least predictable with the least
    information. So Windows is read directly from its font directories and the
    registry, which is where the answer actually lives."""
    if _has("fc-list"):
        try:
            out = subprocess.run(["fc-list", "--format", "%{family}\n"],
                                 capture_output=True, text=True, timeout=20)
            if out.stdout.strip():
                return out.stdout
        except Exception:
            pass

    if sys.platform.startswith("win"):
        names = []
        try:                                    # registered names, incl. CJK aliases
            import winreg
            key = winreg.OpenKey(
                winreg.HKEY_LOCAL_MACHINE,
                r"SOFTWARE\Microsoft\Windows NT\CurrentVersion\Fonts")
            for i in range(winreg.QueryInfoKey(key)[1]):
                try:
                    names.append(winreg.EnumValue(key, i)[0])
                except OSError:
                    break
        except Exception:
            pass
        for d in (os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts"),
                  os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\Windows\Fonts")):
            try:
                names += [os.path.splitext(f)[0] for f in os.listdir(d)]
            except OSError:
                pass
        # A Windows box always has these; naming them lets the checks below
        # recognise the CJK faces the system ships rather than reporting them missing.
        alias = {"simsun": "SimSun 宋体", "msyh": "Microsoft YaHei 微软雅黑",
                 "simhei": "SimHei 黑体", "msjh": "Microsoft JhengHei",
                 "simkai": "KaiTi 楷体", "simfang": "FangSong 仿宋"}
        names += [v for k, v in alias.items()
                  if any(k in n.lower() for n in names)]
        if names:
            return "\n".join(names)
    return None


def install_plex_mono():
    """Fetch IBM Plex Mono and register it for this machine.

    The 歸藏风 numerals are set in IBM Plex Mono, which ships with no OS. It has
    been reinstalled by hand on every fresh clone so far, and forgetting leaves
    the numbers in whatever generic monospace the renderer falls back to — a
    silent, easily-missed degradation of the one mode that depends on them.

    Two things worth knowing, both learned the hard way:
      * @fontsource ships .woff2, and decoding that needs the Brotli extension,
        which is not present in a bare container. The .woff in the same package
        is zlib and fontTools reads it with no extra dependency — so this uses
        the .woff.
      * this is a TOOLING dependency, not a rendering one. Nothing in the render
        path imports fontTools; it is only needed to perform this install.
    """
    import glob
    import subprocess
    import tempfile
    try:
        from fontTools.ttLib import TTFont
    except ImportError:
        print("need fontTools to convert the webfont: pip install fonttools")
        return 1
    dest = os.path.expanduser("~/.fonts")
    os.makedirs(dest, exist_ok=True)
    work = tempfile.mkdtemp(prefix="plex-")
    r = subprocess.run(["npm", "pack", "@fontsource/ibm-plex-mono"],
                       cwd=work, capture_output=True, text=True)
    if r.returncode != 0:
        print("could not fetch the font package (network?):", r.stderr.strip()[:200])
        return 1
    tgz = glob.glob(os.path.join(work, "*.tgz"))
    if not tgz:
        print("no package downloaded")
        return 1
    subprocess.run(["tar", "xzf", os.path.basename(tgz[0])], cwd=work, check=True)
    n = 0
    for weight in ("400", "500", "600", "700"):
        src = os.path.join(work, "package", "files",
                           f"ibm-plex-mono-latin-{weight}-normal.woff")
        if not os.path.exists(src):
            continue
        try:
            f = TTFont(src)
            f.flavor = None
            f.save(os.path.join(dest, f"IBMPlexMono-{weight}.ttf"))
            n += 1
        except Exception as e:
            print(f"  weight {weight} skipped: {e}")
    subprocess.run(["fc-cache", "-f"], capture_output=True)
    print(f"installed {n} weight(s) of IBM Plex Mono into {dest}"
          if n else "nothing installed")
    return 0 if n else 1


def main():
    if "--fix-fonts" in sys.argv[1:]:
        return install_plex_mono()
    rows, fatal = [], False

    # --- required ---------------------------------------------------------
    py_ok = sys.version_info >= (3, 9)
    rows.append((OK if py_ok else BAD, "Python ≥ 3.9",
                 f"found {sys.version.split()[0]}" if py_ok else "upgrade Python"))
    fatal |= not py_ok

    dot = _has("dot")
    rows.append((OK if dot else BAD, "graphviz (dot)",
                 "flowchart node positioning" if dot else
                 "REQUIRED for flowcharts — apt install graphviz / brew install graphviz"))
    fatal |= not dot

    # --- png rasteriser (any one) -----------------------------------------
    raster = [c for c in ("rsvg-convert", "inkscape", "soffice") if _has(c)]
    if raster:
        rows.append((OK, "PNG rasteriser", f"using {raster[0]}"))
    else:
        rows.append((WARN, "PNG rasteriser",
                     "none found — SVG still renders, PNG preview will not. "
                     "Install one: librsvg2-bin / inkscape / libreoffice"))

    # --- fonts (optional, affect looks only) ------------------------------
    # Defined BEFORE the branch: this was set only inside the else, so on any
    # machine without a font list — every Windows box — the later `if not
    # mono_ok` raised UnboundLocalError and doctor died instead of reporting.
    # A variable that only exists on the happy path is not a variable.
    mono_ok = True                    # nothing to offer if we cannot look
    fl = _fonts()
    if fl is None:
        rows.append((WARN, "fonts",
                     "cannot list installed fonts on this system — the figure still "
                     "renders; only the typeface may differ"))
    else:
        song = any(k in fl for k in ("Song", "宋", "SimSun", "Source Han Serif", "Noto Serif CJK"))
        rows.append((OK if song else WARN, "serif CJK (奇川风 titles)",
                     "found" if song else
                     "missing — titles fall back to a default serif; "
                     "install fonts-noto-cjk (or Source Han Serif)"))
        sans = any(k in fl for k in ("Noto Sans CJK", "Noto Sans SC", "Inter",
                                     "PingFang", "Helvetica", "Arial", "DejaVu Sans"))
        rows.append((OK if sans else WARN, "sans CJK (歸藏风 body)",
                     "found" if sans else "missing — install fonts-noto-cjk"))
        mono = "IBM Plex Mono" in fl
        mono_ok = mono
        rows.append((OK if mono else WARN, "IBM Plex Mono (歸藏风 numerals)",
                     "found" if mono else
                     "missing — numbers fall back to a generic monospace; "
                     "install IBM Plex Mono for the intended Swiss texture"))

    # Display faces used by the long-form README graphics. These are NOT needed to
    # render a figure — only to rasterise the repo's own illustrations. They earn a
    # line here because rasterising without them fails SILENTLY: the text is still
    # there, in the wrong typeface, and the giant condensed display word comes out
    # as ordinary sans. Nothing else notices.
    for face, what in (("Anton", "long-form display headings"),
                       ("Inter", "歸藏风 long-form body")):
        have = face.lower() in fl.lower() if fl else False
        rows.append((OK if have else WARN, f"{face} (README long-form art)",
                     "found" if have else
                     f"missing — {what} will silently fall back to a generic sans; "
                     f"`npm pack @fontsource/{face.lower()}` and convert the .woff"))

    # Pillow: TEST-ONLY. Nothing in the render path imports it, so the
    # zero-dependency rule still holds for producing a figure. Without it the
    # handful of guards that MEASURE a rendered image skip themselves — the run
    # stays green, it is simply less thorough, and that is worth saying out loud.
    try:
        import PIL  # noqa: F401
        _pil = True
    except ImportError:
        _pil = False
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import make_gallery as _mg
        _cjk = _mg.has_cjk_font()
    except Exception:
        _cjk = False
    rows.append((OK if _cjk else WARN, "CJK font file (gallery labels)",
                 "found" if _cjk else
                 "missing — the mode labels on comparison images cannot be drawn; "
                 "guards that measure them SKIP. `apt install fonts-noto-cjk`"))

    rows.append((OK if _pil else WARN, "Pillow (image-measuring guards)",
                 "found" if _pil else
                 "missing — guards that measure rendered pixels will SKIP; "
                 "`pip install pillow` to run them"))

    # optional QA tooling: renders a generated .pptx and measures where the text
    # actually landed. Not needed to PRODUCE anything — only to self-check.
    pdftotext = shutil.which("pdftotext")
    rows.append((OK if pdftotext else WARN, "pdftotext (pptx render self-check)",
                 "verify_pptx.py can measure a rendered deck" if pdftotext else
                 "missing — `python3 scripts/verify_pptx.py fig.svg fig.pptx` will skip; "
                 "install poppler-utils to self-check the PPT layout"))

    # --- offer to fix what can be fixed -----------------------------------
    if not mono_ok:
        rows.append((WARN, "  ↳ fixable", "run `python3 scripts/doctor.py --fix-fonts` "
                                          "to install IBM Plex Mono"))

    # --- report -----------------------------------------------------------
    print("\nmqc-litigation-visual-redraw · environment check\n")
    for state, name, note in rows:
        print(f"[{state}] {name:<34} {note}")
    print()
    if fatal:
        print("Result: MISSING REQUIRED TOOLING — install the items marked MISS above.\n")
        return 1
    warns = sum(1 for s, _, _ in rows if s == WARN)
    print(f"Result: ready to render{f' ({warns} optional item(s) degraded)' if warns else ''}.\n")
    print("Next:  python3 scripts/render.py examples/flowchart.json /tmp/out")
    print("       python3 tests/run_checks.py\n")
    return 0



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
    raise SystemExit(main())
