#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫描件与照片：探测、栅格化、转写稿账目核对。

## 为什么有这一条路

律师给的材料**几乎总有扫描件和照片**（对方提交的材料尤其如此）。不读图就永远
只看得见一方，而只看见一方的时间轴恰恰是最危险的产物 —— 它看起来完整，实际是
单方陈述。所以 `HANDOVER` 5.4 定的规矩是：**让模型直接读图，不引 OCR 依赖**，
但图像来源的事项单独标记、不参与逐字核验、交付时必须声明。

## 做法照 Anthropic 官方 pdf-reading skill

那份 skill 对扫描件的原话是：`pdftotext` 什么都不会返回，别跑它；把页面以
150 DPI 栅格化，然后视觉读取。三步：

    ① 探测（本模块 probe）→ ② 栅格化（本模块 rasterize）→ ③ 模型逐页看图

用的全是 poppler-utils 自带的命令（pdffonts / pdftotext / pdfimages / pdftoppm），
**不装模型、不调 API**，所以在 Claude Code、Codex、Cowork、DeepSeek Harness
里都跑得动 —— 读图那一步由 harness 自己的多模态能力提供。模型强就读得准、
弱就读得差，但流程一样。

## 探测的判据要落在「可读字符数」，不是「有没有字体」

官方 skill 说 `pdffonts` 空表即扫描件。真材料上撞出了反例：一份 20 页的扫描
证据，`pdffonts` **不是空表**（第 20 页挂着一个 DengXian，大概是个空文本框），
而 `pdftotext` 逐页抽出的可读字符**全部为 0**。所以本模块逐页量可读字符，
再用 `pdfimages` 的「每页恰好一张整页图」作为印证。

## 转写稿为什么必须存档

这是工业标准做法（LOCUS 法律语料库、GLM-OCR 的最佳实践都是这样）：原始转写与
后续加工分开存，一是便于下游逻辑演进后重新处理，二是**律师要能翻原件逐页核对**。
更要紧的是：转写稿一旦存档成材料，这个项目已有的全套锚定判据（正文必须是原句的
子序列、`raw` 必须逐字可查）**原封不动全部生效** —— 一个判据都不用新写。

## 完备性对账在这条路上会变成自证，所以要外部锚

`HANDOVER` 说「句数 = 事件数 + 非事件数」是整套召回保证的地基。但转写稿的句子
是**模型自己写出来的**，少转一段，对账照样平。所以要拿材料**印在纸上的编号**
当外部锚：证据目录自己编号 1 到 10、维修明细表头写着「序号 1 至 38」。
模型声明每页覆盖哪一段编号 + 材料上印的总数，代码查页数账与缺号。

**总数必须单独声明，不许从区间反推** —— 试过从声明的区间取最大值当上限，
结果模型只转到第 30 项时缺号检查照样通过：那等于自己给自己划及格线（验过）。
"""
import json
import os
import re
import subprocess
import sys


def _run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=180)


def probe(path):
    """这份 PDF 有没有可用的文字层。返回逐页可读字符数与判定。

    判定不看 `pdffonts` 有没有字体（有反例），而是**逐页量可读字符**。
    """
    info = _run(["pdfinfo", path]).stdout
    m = re.search(r"^Pages:\s*(\d+)", info, re.M)
    pages = int(m.group(1)) if m else 0
    per_page = []
    for i in range(1, pages + 1):
        t = _run(["pdftotext", "-f", str(i), "-l", str(i), path, "-"]).stdout
        per_page.append(len(re.sub(r"\s", "", t)))
    # 印证：每页恰好一张整页图，是扫描件的典型形状
    img = _run(["pdfimages", "-list", path]).stdout.splitlines()[2:]
    full_page_imgs = len([l for l in img if l.strip()])
    readable = sum(1 for n in per_page if n >= 20)
    return {
        "pages": pages,
        "chars_per_page": per_page,
        "readable_pages": readable,
        "images": full_page_imgs,
        # 一页都读不出 → 纯扫描件；部分读得出 → 混合，两条路各走各的
        "verdict": ("scanned" if readable == 0 else
                    "mixed" if readable < pages else "text_layer"),
    }


def rasterize(path, outdir, dpi=150):
    """把每页栅格化成 JPEG 供模型看图。150 DPI 是官方 skill 给的档位
    （约 1600 token/页），足够读清小字与手写。"""
    os.makedirs(outdir, exist_ok=True)
    r = _run(["pdftoppm", "-jpeg", "-r", str(dpi), path, os.path.join(outdir, "p")])
    if r.returncode != 0:
        raise RuntimeError(f"栅格化失败：{(r.stderr or r.stdout)[:200]}")
    return sorted(f for f in os.listdir(outdir) if f.endswith(".jpg"))


def check_transcript(decl, totals, total_pages):
    """核对转写稿的账。decl 是模型给的逐页声明，totals 是材料上印着的编号总数。

    decl:   [{"pages": "1" 或 "3-12", "series": "证据", "from": 1, "to": 6}, ...]
            没有编号的页只给 pages。
    totals: {"证据": 10, "维修明细序号": 38}   ← 材料上印着的数，不是算出来的
    """
    errs = []
    seen = set()
    for d in decl:
        p = str(d.get("pages", ""))
        if "-" in p:
            a, b = [int(x) for x in p.split("-")[:2]]
            seen |= set(range(a, b + 1))
        elif p.isdigit():
            seen.add(int(p))
    miss = [i for i in range(1, total_pages + 1) if i not in seen]
    if miss:
        errs.append(f"这些页没有交代：{miss} —— 每一页都要有转写或明写读不出，"
                    f"少一页就是无声地漏掉一段材料")
    got = {}
    for d in decl:
        s = d.get("series")
        if s and d.get("from") and d.get("to"):
            got.setdefault(s, set()).update(range(int(d["from"]), int(d["to"]) + 1))
    for name, total in (totals or {}).items():
        have = got.get(name, set())
        gap = [i for i in range(1, int(total) + 1) if i not in have]
        if gap:
            errs.append(f"{name} 共 {total} 项（材料上印的数），转写稿缺 "
                        f"{gap[:8]}{'…' if len(gap) > 8 else ''} —— 缺号即漏转")
    return errs


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    cmd = sys.argv[1]
    if cmd == "probe":
        r = probe(sys.argv[2])
        print(f"共 {r['pages']} 页，可读出文字的 {r['readable_pages']} 页，"
              f"整页图 {r['images']} 张 → {r['verdict']}")
        print(f"逐页可读字符：{r['chars_per_page']}")
        if r["verdict"] != "text_layer":
            est = r["pages"] * 1600
            print()
            print(f"这是{'纯扫描件' if r['verdict']=='scanned' else '混合材料'}，"
                  f"要靠读图。栅格化后约 {est} token"
                  f"（{r['pages']} 页 × 约 1600）。")
            if est > 150000:
                print(f"  **页数偏多**：{r['pages']} 页读完会占掉很大一块上下文。"
                      f"要不要全部读、还是先给关键的几页，请让律师定 —— "
                      f"代码不替他挑页，挑漏了没人知道。")
            print(f"下一步：python read_image.py rasterize {sys.argv[2]} pages/")
    elif cmd == "rasterize":
        files = rasterize(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "pages")
        print(f"栅格化 {len(files)} 页到 {sys.argv[3] if len(sys.argv)>3 else 'pages'}/："
              f"{files[:3]} …")
        print("下一步：逐页看图，写转写稿（每页一节、标页码），"
              "并声明每页覆盖的编号区间与材料上印的编号总数。")
    elif cmd == "check":
        d = json.load(open(sys.argv[2], encoding="utf-8"))
        errs = check_transcript(d.get("pages_declared") or [],
                                d.get("series_totals") or {},
                                int(d.get("total_pages") or 0))
        if errs:
            for e in errs:
                print(f"  {e}")
            sys.exit(1)
        print("转写稿账目核对通过：每页都有交代，材料自带编号无缺号。")
    else:
        print(__doc__)
        sys.exit(2)
