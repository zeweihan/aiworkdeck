#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""litviz —— 诉讼可视化引擎的机器契约层。

后端 Java（LitigationVisualTools）唯一调用的入口。存在的理由只有一个：
上游 `mqc-litigation-visual-redraw/scripts/render.py` 是给人看的命令行——把进度、审计摘要、lint 警告
一股脑打到 stdout，成功与否只体现在退出码上。Java 那侧要知道**到底生成了哪些
文件、是不是草稿、用了哪种模式**，靠正则去啃那段human-readable 文本迟早出事。

所以这里的契约是硬的：

  * **stdout 只有一行 JSON**，别的什么都没有；
  * 引擎自己的人类输出全部转到 **stderr**（排障时照样看得见）；
  * 成功 `{"ok": true, ...}` 退出码 0；可预期的失败 `{"ok": false, "error": ...}`
    退出码 1。解释器自己崩了才会有非 JSON 的 stderr + 别的退出码。

子命令：

    python3 cli.py doctor
    python3 cli.py validate  --map <map.json>
    python3 cli.py checkpoint --map <map.json> [--suggest N]
    python3 cli.py render    --map <map.json> --out <输出basename> [--mode ...] [--formats ...]
    python3 cli.py timeline  --workdir <目录> --stage <阶段> [--emphasis-source ...] [args...]

`timeline` 驱动的是另一个 vendor 模块 mqc-timeline-master（时间轴大师）的分段管线
（pipeline.py）。那条管线以 cwd 为状态目录、stdout 全是给人看的中文文本，所以这里
用子进程跑它（cwd 指到 --workdir），把文本包进一行 JSON 转达。模型要写的四份 JSON
（verdicts/parts/skeleton/items）由宿主直接写进 workdir，不经过本命令。

`--out` 传的是**不带扩展名的路径前缀**，引擎按 `<out>.svg` / `.png` / `.pptx` /
`.vsdx` / `.drawio` / `.drawio.svg` 写出。地图里 `checkpoint.confirmed` 不为 true 时，
引擎会强制把前缀改成 `<out>-draft`——这是它的设计（未确认的读法不许当终稿归档），
不要绕过，JSON 里的 `draft` 字段如实转达这件事。
"""
import argparse
import contextlib
import io
import json
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_ENGINE = os.path.join(_HERE, "skills", "mqc-litigation-visual-redraw")
_SCRIPTS = os.path.join(_ENGINE, "scripts")

# 引擎模块之间是平铺 import（`from common import ...`），必须让 scripts/ 在 sys.path 上。
# 放最前面：宿主环境里若有同名模块（common 这种名字很容易撞），必须我们的赢。
if _SCRIPTS not in sys.path:
    sys.path.insert(0, _SCRIPTS)

# 流程图与关系网络图两种布局要 graphviz 的 `dot` 在 PATH 上。打包态它在应用资源
# 目录里，不在系统 PATH，由宿主用 LITVIZ_GRAPHVIZ_DIR 指过来。
_GV_DIR = os.environ.get("LITVIZ_GRAPHVIZ_DIR", "").strip()
if _GV_DIR and os.path.isdir(_GV_DIR):
    os.environ["PATH"] = _GV_DIR + os.pathsep + os.environ.get("PATH", "")
    # GVBINDIR 必须显式给。graphviz 把插件目录**编译期焊死**在 libgvc 里，
    # 指向构建机的安装路径（如 /opt/homebrew/Cellar/graphviz/<ver>/lib/graphviz）。
    # 用户机器上没有那个路径，dot 会报 'Format: "plain" not recognized'——
    # 而构建机自测永远是绿的，因为那儿的路径真的存在。
    if not os.environ.get("GVBINDIR"):
        _plugins = os.path.join(os.path.dirname(_GV_DIR.rstrip(os.sep)), "lib", "graphviz")
        if os.path.isdir(_plugins):
            os.environ["GVBINDIR"] = _plugins        # mac：bin/ 与 lib/graphviz/ 并列
        elif os.path.isdir(_GV_DIR):
            os.environ["GVBINDIR"] = _GV_DIR         # win：插件与 dot.exe 同在 bin/

# 引擎写出的全部扩展名。顺序即呈现顺序：svg 是母版，png 是预览，其余三个是
# 交给律师继续编辑的原生源文件。
_OUTPUT_EXTS = (".svg", ".png", ".drawio", ".drawio.svg", ".pptx", ".vsdx")

_MODES = {
    "qichuan": [],                 # 奇川风：引擎默认，不加旗标
    "guizang": ["--guizang"],
    "baimiao": ["--baimiao"],
}
_MODE_ALIASES = {
    "奇川风": "qichuan", "qichuan": "qichuan", "default": "qichuan", "": "qichuan",
    "歸藏风": "guizang", "归藏风": "guizang", "guizang": "guizang", "swiss": "guizang",
    "白描": "baimiao", "baimiao": "baimiao", "mono": "baimiao", "print": "baimiao",
}


def _emit(payload, code=0):
    """唯一的出口：一行 JSON 到 stdout，然后退出。"""
    json.dump(payload, sys.stdout, ensure_ascii=False)
    sys.stdout.write("\n")
    sys.stdout.flush()
    sys.exit(code)


def _fail(message, **extra):
    _emit(dict({"ok": False, "error": str(message)}, **extra), code=1)


@contextlib.contextmanager
def _engine_stdout():
    """引擎的人类输出改道 stderr，腾空 stdout 给 JSON。

    引擎里到处是裸 print()，改它等于分叉上游；重定向是唯一不动引擎的办法。
    捕获下来的文本仍然原样进 stderr，排障时不会丢信息。"""
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            yield buf
    finally:
        text = buf.getvalue()
        if text:
            sys.stderr.write(text)
            sys.stderr.flush()


def _resolve_mode(raw):
    key = _MODE_ALIASES.get(str(raw or "").strip().lower())
    if key is None:
        key = _MODE_ALIASES.get(str(raw or "").strip())      # 中文别名不做 lower
    if key is None:
        raise ValueError("未知的视觉模式 %r，可选：奇川风 / 歸藏风 / 白描" % raw)
    return key


def _collect_outputs(base):
    """按约定的扩展名收产物。

    不用 glob 通配 `base.*`：同一个输出目录里可能已经躺着别的同名文件（用户手动
    放的、上一轮别的模式留下的），通配会把它们一并当成本次产物报给用户。
    只认白名单扩展名，且必须是本次跑完之后存在的。"""
    out = []
    for ext in _OUTPUT_EXTS:
        fp = base + ext
        if os.path.isfile(fp):
            out.append({
                "format": ext.lstrip(".").replace("drawio.svg", "drawio-svg"),
                "path": os.path.abspath(fp),
                "bytes": os.path.getsize(fp),
            })
    return out


def cmd_doctor(_args):
    import doctor as engine_doctor
    with _engine_stdout() as buf:
        try:
            engine_doctor.main()
        except SystemExit:
            pass                                   # doctor 用退出码表达"缺依赖"，这里只取文本
    report = buf.getvalue()
    import shutil
    return {
        "ok": True,
        "python": sys.version.split()[0],
        "graphviz": bool(shutil.which("dot")),     # 只有它是硬依赖（流程图/关系网络图）
        "rasteriser": bool(shutil.which("rsvg-convert") or shutil.which("inkscape")
                           or shutil.which("soffice")) or _has_cairosvg(),
        "report": report.strip(),
    }


def _has_cairosvg():
    try:
        import cairosvg  # noqa: F401
        return True
    except Exception:
        return False


def cmd_validate(args):
    from common import load_map, validate_map
    m = load_map(args.map)
    # validate_map 会把逐条诊断 print 出来（"[validate] missing ..."）。不裹住的话
    # 那几行会跟 JSON 一起进 stdout，Java 那侧就不是"读一行 JSON"而是要猜哪行是。
    with _engine_stdout():
        validate_map(m)
    return {
        "ok": True,
        "layout": m.get("layout", ""),
        "diagram_type": m.get("diagram_type", ""),
        "title": m.get("title_text", ""),
        "confirmed": bool((m.get("checkpoint") or {}).get("confirmed")),
    }


def cmd_checkpoint(args):
    """生成出图前的三问。

    问题由引擎的 checkpoint.py 确定性生成——这是上游一条刻意的设计：三个答案的
    后果（没授权的红标不出现、未确认的图只叫 *-draft）本来就是脚本强制的，那么
    提问本身如果还靠模型现编，就成了整条链上唯一的软环节。所以这里原样透传，
    不要让模型改写措辞。"""
    import checkpoint as engine_checkpoint
    argv = [args.map]
    if args.suggest:
        argv.append("--suggest=%s" % args.suggest)
    with _engine_stdout() as buf:
        rc = engine_checkpoint.main(argv) if hasattr(engine_checkpoint, "main") else None
        if rc is None:
            # checkpoint.py 只有 __main__ 分支时的兜底：直接按脚本跑一遍
            import runpy
            saved = sys.argv
            sys.argv = ["checkpoint.py"] + argv
            try:
                runpy.run_path(os.path.join(_SCRIPTS, "checkpoint.py"), run_name="__main__")
            except SystemExit:
                pass
            finally:
                sys.argv = saved
    return {"ok": True, "questions": buf.getvalue().rstrip()}


def cmd_render(args):
    import render as engine_render

    mode = _resolve_mode(args.mode)
    out_base = os.path.abspath(args.out)
    out_dir = os.path.dirname(out_base)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    formats = engine_render.ALL_FORMATS
    if args.formats:
        formats = tuple(f.strip().lower() for f in args.formats.split(",") if f.strip())
        unknown = [f for f in formats if f not in engine_render.ALL_FORMATS]
        if unknown:
            raise ValueError("未知输出格式 %s，可选 %s"
                             % (unknown, list(engine_render.ALL_FORMATS)))
        if "svg" not in formats:
            formats = ("svg",) + formats            # 其余格式都是从 svg 转录的

    # 草稿前缀由引擎决定（checkpoint.confirmed 不为 true 就强制 -draft）。
    # 先问一遍，才知道该去哪儿收文件。
    from common import load_map
    with _engine_stdout():                       # load_map 也会 print 诊断，别漏进 stdout
        m = load_map(args.map)
    actual_base, is_draft = engine_render._draft_base(out_base, m)

    with _engine_stdout() as buf:
        rc = engine_render.main(
            args.map, out_base,
            strict=bool(args.strict),
            mono=(mode == "baimiao"),
            theme=("guizang" if mode == "guizang" else None),
            pptx_fonts="master",
            formats=formats,
        )
    engine_log = buf.getvalue()

    if rc != 0:
        # 引擎把失败原因打在文本里（"Error: ..."），退出码只有 0/1/2。捞出来当错误信息，
        # 比只回一个数字有用得多。
        reason = next((ln for ln in engine_log.splitlines() if ln.startswith("Error")),
                      "渲染失败（退出码 %s）" % rc)
        raise RuntimeError(reason)

    files = _collect_outputs(actual_base)
    if not files:
        raise RuntimeError("引擎报告成功但没有产物落盘，检查输出目录是否可写：%s" % out_dir)

    return {
        "ok": True,
        "draft": bool(is_draft),
        "mode": {"qichuan": "奇川风", "guizang": "歸藏风", "baimiao": "白描"}[mode],
        "layout": m.get("layout", ""),
        "title": m.get("title_text", ""),
        "basename": os.path.basename(actual_base),
        "files": files,
        "audit": engine_log.strip(),
    }


# ==================== 时间轴大师（mqc-timeline-master） ====================

_TIMELINE_DIR = os.path.join(_HERE, "skills", "mqc-timeline-master")
_PIPELINE = os.path.join(_TIMELINE_DIR, "scripts", "pipeline.py")

#: pipeline.py 认识的全部子命令（pipeline.py:1338-1371）。白名单挡两类东西：
#: 拼错的阶段名（pipeline 对未知子命令 exit 2、提示不友好），以及任何"把别的脚本
#: 当阶段跑"的注入面。
_TL_STAGES = frozenset({
    "read", "pick", "span", "style", "offer", "budget", "capacity",
    "title", "mark", "render", "next", "steps", "shape",
})

#: 管线单阶段的墙钟上限。要卡在 Java 侧 90s 总闸之内，超时要死在这里——这样还能
#: 回一行说清楚超时的 JSON，而不是被 Java 掐掉后只剩"引擎没有返回结果"。
_TL_TIMEOUT_S = 75

#: render 产物目录里按扩展名认领的产物类型（时间轴大师一次 render 可能落多页
#: -pageN.svg、异风格 -<风格>.svg、五种格式与 trace.json / 溯源索引 docx）。
_TL_PRODUCT_EXTS = (".svg", ".png", ".pptx", ".vsdx", ".drawio", ".json", ".docx", ".jpg")


def cmd_timeline(args):
    if not os.path.isfile(_PIPELINE):
        raise RuntimeError("时间轴大师模块未就位（缺 %s）" % _PIPELINE)
    stage = (args.stage or "").strip()
    if stage not in _TL_STAGES:
        raise ValueError("未知的管线阶段 %r，可选：%s" % (stage, "、".join(sorted(_TL_STAGES))))
    workdir = os.path.abspath(args.workdir)
    if not os.path.isdir(workdir):
        raise RuntimeError("工作目录不存在：%s" % workdir)

    # pipeline 不替调用方建输出目录（裸跑会 FileNotFoundError 且横/纵两档都归咎于此）。
    if stage == "render" and args.stage_args:
        out_parent = os.path.dirname(os.path.join(workdir, args.stage_args[0]))
        if out_parent:
            os.makedirs(out_parent, exist_ok=True)

    import subprocess
    cmd = [sys.executable, _PIPELINE, stage] + list(args.stage_args or [])
    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    try:
        proc = subprocess.run(
            cmd, cwd=workdir, env=env,
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=_TL_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        return {"ok": False, "stage": stage, "exit": -1,
                "error": "管线阶段 %s 超时（%d 秒）" % (stage, _TL_TIMEOUT_S), "text": ""}

    text = (proc.stdout or "").strip()
    if proc.stderr and proc.stderr.strip():
        # 管线的正文全在 stdout；stderr 只有解释器级故障（traceback）才有内容。
        sys.stderr.write(proc.stderr)

    payload = {"ok": proc.returncode == 0, "stage": stage,
               "exit": proc.returncode, "text": text}
    if proc.returncode != 0 and not text:
        payload["error"] = "管线阶段 %s 异常退出（退出码 %s），无输出。stderr 摘要：%s" % (
            stage, proc.returncode, (proc.stderr or "").strip()[-800:])

    # mark 的「模型代挑」在上游 CLI 上不可达（空参 exit 1）：上游语义里
    # emphasis_source 应如实记录是谁挑的红。宿主声明是模型挑的时，这里在
    # mark 成功后补写 state.json——只动这一个键，别的字节不碰。
    if stage == "mark" and proc.returncode == 0 and args.emphasis_source:
        src = args.emphasis_source.strip()
        if src not in ("user", "model", "none"):
            raise ValueError("emphasis-source 只认 user/model/none，收到 %r" % src)
        state_path = os.path.join(workdir, "state.json")
        if os.path.isfile(state_path):
            with open(state_path, encoding="utf-8") as f:
                st = json.load(f)
            if "emphasis" in st:
                st["emphasis_source"] = src
                with open(state_path, "w", encoding="utf-8") as f:
                    json.dump(st, f, ensure_ascii=False, indent=1)
                payload["emphasis_source"] = src

    # render 成功后收产物：宿主保证每次 render 用一个新的输出子目录
    # （首个位置参数形如 out-3/图名.svg），目录里的一切都是本次产物。
    if stage == "render" and proc.returncode == 0 and args.stage_args:
        out_dir = os.path.dirname(os.path.join(workdir, args.stage_args[0]))
        files = []
        if os.path.isdir(out_dir):
            for name in sorted(os.listdir(out_dir)):
                fp = os.path.join(out_dir, name)
                if os.path.isfile(fp) and name.endswith(_TL_PRODUCT_EXTS):
                    files.append({"name": name, "path": os.path.abspath(fp),
                                  "bytes": os.path.getsize(fp)})
        payload["files"] = files

    # 契约：可预期的失败（管线校验不过/缺前置文件）也走一行 JSON，但退出码 1，
    # 与 validate/render 的失败语义一致。_emit 会直接退出。
    _emit(payload, code=0 if payload["ok"] else 1)


def main(argv=None):
    p = argparse.ArgumentParser(prog="litviz", add_help=True)
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("doctor").set_defaults(fn=cmd_doctor)

    v = sub.add_parser("validate"); v.add_argument("--map", required=True)
    v.set_defaults(fn=cmd_validate)

    c = sub.add_parser("checkpoint")
    c.add_argument("--map", required=True)
    c.add_argument("--suggest", default="")
    c.set_defaults(fn=cmd_checkpoint)

    r = sub.add_parser("render")
    r.add_argument("--map", required=True)
    r.add_argument("--out", required=True, help="输出路径前缀，不带扩展名")
    r.add_argument("--mode", default="奇川风")
    r.add_argument("--formats", default="", help="逗号分隔；留空 = 全部五种格式")
    r.add_argument("--strict", action="store_true")
    r.set_defaults(fn=cmd_render)

    t = sub.add_parser("timeline")
    t.add_argument("--workdir", required=True, help="管线状态目录（一个案件一个，宿主管理）")
    t.add_argument("--stage", required=True, help="pipeline.py 子命令名")
    t.add_argument("--emphasis-source", dest="emphasis_source", default="",
                   help="仅 mark 阶段：user/model/none，如实记录深红是谁挑的")
    t.add_argument("stage_args", nargs="*", help="透传给管线阶段的位置参数")
    t.set_defaults(fn=cmd_timeline)

    args = p.parse_args(argv)
    try:
        _emit(args.fn(args))
    except SystemExit:
        raise
    except FileNotFoundError as e:
        _fail("文件不存在：%s" % e)
    except Exception as e:
        _fail("%s: %s" % (type(e).__name__, e))


if __name__ == "__main__":
    main()
