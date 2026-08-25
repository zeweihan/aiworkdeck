#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""litviz/cli.py 的契约测试。

守的是**后端 Java 依赖的那几条约定**，不是引擎的画图质量——画图质量由上游自带的
149 项回归守（`engine/tests/run_checks.py`，本文件最后会连带跑一遍）。

    python3 litviz/tests/test_cli.py
    python3 litviz/tests/test_cli.py --skip-engine    # 只跑契约，不跑上游 149 项
"""
import json
import os
import subprocess
import sys
import tempfile

_HERE = os.path.dirname(os.path.abspath(__file__))
_LITVIZ = os.path.dirname(_HERE)
_CLI = os.path.join(_LITVIZ, "cli.py")
_EXAMPLES = os.path.join(_LITVIZ, "skills", "mqc-litigation-visual-redraw", "examples")

# 哪些布局离了 graphviz 就画不出来。实测矩阵（2026-08-08，引擎 v1.0.2）：
# 只有流程图真的要 dot。graphviz_relation 名字里带 graphviz，但 v1.0.2 已经换成
# 确定性的无 graphviz 布局（_layout_nodes），run_dot 在那个文件里是死代码。
# 这条矩阵是我们决定"不装 graphviz 时功能降级到什么程度"的依据，别凭名字猜。
NEEDS_GRAPHVIZ = {"flowchart", "flow-contract-review"}
ALL_EXAMPLES = ["timeline-points", "timeline-dated", "timeline-gantt",
                "relationship", "relation-tree", "comparison-table",
                "flowchart", "flow-contract-review"]

_failures = []


def check(name, cond, detail=""):
    if cond:
        print("  [PASS] %s" % name)
    else:
        print("  [FAIL] %s%s" % (name, ("  → " + detail) if detail else ""))
        _failures.append(name)


def run_cli(args, env=None, python=None):
    """跑一次 CLI，返回 (解析出的 JSON, 退出码, stderr)。

    stdout 必须是**恰好一行 JSON**——这正是要测的东西，所以这里故意用严格解析：
    多打一行日志到 stdout 就会在这里炸，而不是等到 Java 那侧莫名其妙解析失败。"""
    e = dict(os.environ)
    if env:
        e.update(env)
    p = subprocess.run([python or sys.executable, _CLI] + args,
                       capture_output=True, text=True, env=e)
    lines = [ln for ln in p.stdout.splitlines() if ln.strip()]
    if len(lines) != 1:
        return {"_stdout_lines": len(lines), "_raw": p.stdout}, p.returncode, p.stderr
    return json.loads(lines[0]), p.returncode, p.stderr


def bare_path_env():
    """一个不含 graphviz 的 PATH，用来模拟没装 graphviz 的用户机器。"""
    return {"PATH": "/usr/bin:/bin", "LITVIZ_GRAPHVIZ_DIR": ""}


def main():
    skip_engine = "--skip-engine" in sys.argv
    tmp = tempfile.mkdtemp(prefix="litviz-test-")
    have_dot = subprocess.run(["sh", "-c", "command -v dot"],
                              capture_output=True).returncode == 0

    print("\nlitviz · CLI 契约")

    # ---- 1. stdout 只有一行 JSON ---------------------------------------
    out, rc, _ = run_cli(["validate", "--map", os.path.join(_EXAMPLES, "relationship.json")])
    check("validate: stdout 恰好一行 JSON", "_stdout_lines" not in out,
          "实际 %s 行" % out.get("_stdout_lines"))
    check("validate: ok=true 且退出码 0", out.get("ok") is True and rc == 0)
    check("validate: 回报 layout", out.get("layout") == "graphviz_relation")

    # ---- 2. 引擎的人类输出不许污染 stdout -------------------------------
    out, rc, err = run_cli(["render", "--map", os.path.join(_EXAMPLES, "timeline-points.json"),
                            "--out", os.path.join(tmp, "t1"), "--formats", "svg"])
    check("render: stdout 仍然只有一行 JSON", "_stdout_lines" not in out,
          "实际 %s 行" % out.get("_stdout_lines"))
    check("render: 引擎日志走的是 stderr", "SVG:" in err)
    check("render: 产物路径是绝对路径且真实存在",
          out.get("ok") and all(os.path.isabs(f["path"]) and os.path.isfile(f["path"])
                                for f in out.get("files", [])))

    # ---- 3. 草稿闸 ------------------------------------------------------
    # 未确认的地图必须写成 *-draft。这是上游一条刻意的安全设计（未经用户确认的
    # 读法不许当终稿归档进诉讼材料），我们不能在包装层把它抹平。
    src = json.load(open(os.path.join(_EXAMPLES, "timeline-points.json"), encoding="utf-8"))
    src["checkpoint"] = {"confirmed": False}
    unconfirmed = os.path.join(tmp, "unconfirmed.json")
    json.dump(src, open(unconfirmed, "w", encoding="utf-8"), ensure_ascii=False)
    out, rc, _ = run_cli(["render", "--map", unconfirmed,
                          "--out", os.path.join(tmp, "t2"), "--formats", "svg"])
    check("draft: 未确认 → draft=true", out.get("draft") is True)
    check("draft: 文件名带 -draft 后缀", out.get("basename") == "t2-draft")
    check("draft: 报的路径就是真落盘的那个",
          all(os.path.isfile(f["path"]) for f in out.get("files", [])))

    # ---- 4. 三种视觉模式 ------------------------------------------------
    for raw, want in [("奇川风", "奇川风"), ("guizang", "歸藏风"), ("白描", "白描"),
                      ("", "奇川风")]:
        out, _, _ = run_cli(["render", "--map", os.path.join(_EXAMPLES, "timeline-points.json"),
                             "--out", os.path.join(tmp, "m-" + (raw or "default")),
                             "--formats", "svg"] + (["--mode", raw] if raw else []))
        check("mode: %r → %s" % (raw, want), out.get("mode") == want, str(out.get("error", "")))

    out, rc, _ = run_cli(["render", "--map", os.path.join(_EXAMPLES, "timeline-points.json"),
                          "--out", os.path.join(tmp, "m-bad"), "--mode", "赛博朋克"])
    check("mode: 未知模式 → ok=false 且退出码 1", out.get("ok") is False and rc == 1)

    # ---- 5. 错误一律是结构化的，不是 traceback --------------------------
    bad = os.path.join(tmp, "bad.json")
    open(bad, "w").write('{"layout":"nope"}')
    out, rc, _ = run_cli(["validate", "--map", bad])
    check("error: 坏地图 → ok=false", out.get("ok") is False)
    check("error: 退出码 1", rc == 1)
    check("error: 带得上原因", "layout" in str(out.get("error", "")))

    out, rc, _ = run_cli(["validate", "--map", os.path.join(tmp, "不存在.json")])
    check("error: 文件不存在也是结构化的", out.get("ok") is False and rc == 1)

    # ---- 6. graphviz 依赖矩阵 -------------------------------------------
    # 这一组是打包决策的依据：确认"没有 dot 时到底哪些布局还能用"。
    # 名字带 graphviz 的关系图其实不需要它——凭名字猜会把打包范围判错。
    print("\nlitviz · 无 graphviz 时的布局矩阵")
    for ex in ALL_EXAMPLES:
        out, _, _ = run_cli(["render", "--map", os.path.join(_EXAMPLES, ex + ".json"),
                             "--out", os.path.join(tmp, "nodot-" + ex), "--formats", "svg"],
                            env=bare_path_env())
        expect_ok = ex not in NEEDS_GRAPHVIZ
        got_ok = out.get("ok") is True
        check("%s: 无 dot 时 %s" % (ex, "可出图" if expect_ok else "明确报缺 graphviz"),
              got_ok == expect_ok,
              str(out.get("error", ""))[:90])
        if not expect_ok and not got_ok:
            check("  ↳ %s 的报错点名 graphviz" % ex,
                  "graphviz" in str(out.get("error", "")).lower())

    # ---- 7. checkpoint 原样透传 -----------------------------------------
    out, rc, _ = run_cli(["checkpoint", "--map", os.path.join(_EXAMPLES, "timeline-points.json"),
                          "--suggest", "3"])
    q = out.get("questions", "")
    check("checkpoint: 三问齐全", all(k in q for k in ("① 结构", "② 风格", "③ 重点")),
          q[:80])
    check("checkpoint: 候选清单来自地图里的真实元素", "乙停业失联" in q)

    # ---- 8. doctor ------------------------------------------------------
    out, rc, _ = run_cli(["doctor"])
    check("doctor: 如实回报 graphviz 有无", out.get("graphviz") == have_dot)
    check("doctor: 回报解释器版本", bool(out.get("python")))

    # ---- 9. 时间轴大师管线契约 ------------------------------------------
    # 走一遍完整管线：read → pick → span → style → offer → budget → capacity
    # → title → render。模型那一半（verdicts/parts/skeleton/items）由本测试
    # **按 state.json 动态生成**——不写死句子编号，句子切分规则变了测试自动跟上。
    print("\nlitviz · 时间轴大师（timeline）管线契约")
    tl_work = tempfile.mkdtemp(prefix="litviz-tl-")
    os.makedirs(os.path.join(tl_work, "materials"), exist_ok=True)
    fixture = os.path.join(_LITVIZ, "skills", "mqc-timeline-master", "tests", "fixtures", "m7-short.txt")
    with open(fixture, encoding="utf-8") as f:
        material_text = f.read()
    with open(os.path.join(tl_work, "materials", "催告经过.txt"), "w", encoding="utf-8") as f:
        f.write(material_text)

    def run_tl(stage, *stage_args, extra=None):
        return run_cli(["timeline", "--workdir", tl_work, "--stage", stage]
                       + list(extra or []) + list(stage_args))

    out, rc, _ = run_cli(["timeline", "--workdir", tl_work, "--stage", "肯定没有这个阶段"])
    check("timeline: 未知阶段被白名单挡下", out.get("ok") is False and rc == 1
          and "阶段" in str(out.get("error", "")))

    out, rc, _ = run_tl("read", "materials/催告经过.txt")
    check("timeline read: ok 且 stdout 一行 JSON", out.get("ok") is True and rc == 0,
          str(out)[:120])
    check("timeline read: 转达管线文本（含 shape 指引）", "verdicts.json" in out.get("text", ""))
    state = json.load(open(os.path.join(tl_work, "state.json"), encoding="utf-8"))
    sentences = state["sentences"]
    check("timeline read: state.json 落在 workdir", len(sentences) >= 5,
          "句数 %d" % len(sentences))

    for stage, arg in [("pick", "全部"), ("span", "全部"), ("style", "3")]:
        out, rc, _ = run_tl(stage, arg)
        check("timeline %s: ok" % stage, out.get("ok") is True, str(out)[:160])

    # 模型产出：逐句判定 + 单一部分。带日期的是事实句，标题行不是。
    import re as _re
    date_re = _re.compile(r"\d{4}-\d{2}-\d{2}")
    verdicts = [{"i": i, "is_event": bool(date_re.search(s)), "why": "带日期的已发生事实" if date_re.search(s) else "标题/非事实"}
                for i, s in enumerate(sentences)]
    json.dump(verdicts, open(os.path.join(tl_work, "verdicts.json"), "w", encoding="utf-8"),
              ensure_ascii=False)
    parts = [{"id": 1, "name": "催告经过", "sids": list(range(len(sentences))),
              "first": sentences[0], "last": sentences[-1]}]
    json.dump(parts, open(os.path.join(tl_work, "parts.json"), "w", encoding="utf-8"),
              ensure_ascii=False)

    out, rc, _ = run_tl("offer")
    check("timeline offer: 校验模型产出并给勾选清单", out.get("ok") is True
          and "催告经过" in out.get("text", ""), str(out)[:200])
    out, rc, _ = run_tl("budget", "all")
    check("timeline budget: ok", out.get("ok") is True, str(out)[:160])

    skeleton = []
    for i, s in enumerate(sentences):
        m = date_re.search(s)
        if not m:
            continue
        skeleton.append({"id": str(len(skeleton) + 1), "src_sids": [i],
                         "certainty": "exact", "kind": "occur",
                         "raw": m.group(0), "date": m.group(0)})
    json.dump(skeleton, open(os.path.join(tl_work, "skeleton.json"), "w", encoding="utf-8"),
              ensure_ascii=False)
    out, rc, _ = run_tl("capacity")
    check("timeline capacity: 按骨架算出容量", out.get("ok") is True
          and "items.json" in out.get("text", ""), str(out)[:300])

    out, rc, _ = run_tl("title", "催告经过时间轴")
    check("timeline title: ok", out.get("ok") is True, str(out)[:160])

    # items：head = 原句砍掉日期时刻前缀 → 子序列成立
    items = []
    for ent in skeleton:
        s = sentences[ent["src_sids"][0]]
        head = _re.sub(r"^\S+\s+\S+\s+", "", s).strip()
        items.append(dict(ent, head=head))
    json.dump(items, open(os.path.join(tl_work, "items.json"), "w", encoding="utf-8"),
              ensure_ascii=False)

    # 忠实性红线：改写原文必须在出图前被拦（结构化失败，不是 traceback）
    bad_items = [dict(it) for it in items]
    bad_items[0]["head"] = "对方收到了我们的首次催告通知"
    json.dump(bad_items, open(os.path.join(tl_work, "items.json"), "w", encoding="utf-8"),
              ensure_ascii=False)
    out, rc, _ = run_tl("render", "out-bad/催告经过.svg")
    check("timeline render: 改写原文被拦且是结构化失败",
          out.get("ok") is False and rc == 1 and bool(out.get("text")), str(out)[:200])

    json.dump(items, open(os.path.join(tl_work, "items.json"), "w", encoding="utf-8"),
              ensure_ascii=False)
    out, rc, _ = run_tl("render", "out-1/催告经过.svg")
    files = {f["name"]: f for f in out.get("files", [])}
    check("timeline render: ok 且报出产物清单", out.get("ok") is True and bool(files),
          str(out)[:300])
    check("timeline render: SVG 落盘", any(n.endswith(".svg") for n in files),
          str(sorted(files)))
    for ext in (".pptx", ".vsdx", ".drawio"):
        check("timeline render: %s（纯 stdlib 导出）落盘" % ext,
              any(n.endswith(ext) for n in files), str(sorted(files)))
    check("timeline render: 溯源 trace.json 落盘",
          any(n.endswith("-trace.json") for n in files), str(sorted(files)))
    check("timeline render: 产物路径绝对且真实存在",
          all(os.path.isabs(f["path"]) and os.path.isfile(f["path"]) for f in files.values()))

    out, rc, _ = run_tl("next")
    check("timeline next: 中途接手可问进度", out.get("ok") is True and bool(out.get("text")))

    # ---- 10. 上游回归 ----------------------------------------------------
    if not skip_engine:
        print("\n上游时间轴回归（mqc-timeline-master/tests/run_checks.py）")
        tp = subprocess.run([sys.executable, "tests/run_checks.py"],
                            cwd=os.path.join(_LITVIZ, "skills", "mqc-timeline-master"),
                            capture_output=True, text=True)
        # 两类 FAIL 是环境依赖缺席而非回归，指名豁免（与引擎 146/149 同一口径）：
        # 溯源索引守卫要全局 npm docx 包（我们的产品路线用 POI 出 docx，不装它）；
        # 探测判据要 reportlab 造带文字层的 PDF 样本。上游自己的 CI 装了这两样。
        tl_fails = [ln for ln in tp.stdout.splitlines() if "FAIL" in ln]
        tl_env = [ln for ln in tl_fails if "docx@9" in ln or "reportlab" in ln]
        check("时间轴回归: 除环境依赖缺席外全绿",
              len(tl_fails) == len(tl_env)
              and (tp.returncode == 0 or len(tl_fails) > 0),
              "非环境失败 %d 项：%s" % (len(tl_fails) - len(tl_env),
                                        [ln for ln in tl_fails if ln not in tl_env][:3]))
        for ln in tl_env:
            print("  （环境缺席，豁免）" + ln.strip())

        print("\n上游引擎回归（mqc-litigation-visual-redraw/tests/run_checks.py）")
        p = subprocess.run([sys.executable, os.path.join(_LITVIZ, "skills", "mqc-litigation-visual-redraw", "tests", "run_checks.py")],
                           capture_output=True, text=True)
        tail = [ln for ln in p.stdout.splitlines() if "checks passed" in ln]
        summary = tail[-1].strip() if tail else "(没抓到统计行)"
        print("  " + summary)
        # 我们没 vendor 上游的 README.md（那是它的门面文档，与出图链路无关），
        # 于是 3 项 README 文档守卫结构性缺席。除此之外任何一项红都是真回归。
        fails = [ln for ln in p.stdout.splitlines() if "[FAIL]" in ln]
        non_doc = [ln for ln in fails if "docs ·" not in ln]
        check("引擎回归: 只有 README 文档守卫因未 vendor 而缺席",
              len(non_doc) == 0,
              "另有 %d 项失败：%s" % (len(non_doc), non_doc[:2]))
        check("引擎回归: 缺席的正好是那 3 项", len(fails) == 3,
              "实际 %d 项 [FAIL]" % len(fails))

    print("\n%s" % ("全部通过" if not _failures
                    else "%d 项失败：%s" % (len(_failures), _failures)))
    return 1 if _failures else 0


if __name__ == "__main__":
    sys.exit(main())
