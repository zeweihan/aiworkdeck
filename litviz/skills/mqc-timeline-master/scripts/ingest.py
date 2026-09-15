#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ingestion prototype: normalize -> sentence-split -> locate.

Run against the real judgment to find out where the design breaks.
Zero third-party deps.
"""
import re, sys, json

# ---------------------------------------------------------------- normalize
# Every rule is a WHITELISTED, auditable transform. The model never cleans
# text; only these rules do, and every removal is counted so the audit can
# prove nothing else was touched.
RULES = [
    ("page_marker", re.compile(r'\n*\s*-\s*\d{1,4}\s*-\s*\n*')),   # "- 12 -" footer
    ("form_feed",   re.compile(r'\x0c')),
    # A soft line-break inside a sentence. Legal PDFs break lines mid-number and
    # mid-date constantly ("…数月。」2017\n年 7 月 24 日…"), so the rule must cover
    # CJK<->digit in BOTH directions, not just CJK<->CJK. Missing the digit side
    # split one sentence in two and made a whole date invisible to the scanner.
    ("soft_break",  re.compile(
        r'(?<=[\u4e00-\u9fff\u3001\u3002\uff09\u300b\u201d0-9])\n'
        r'(?=[\u4e00-\u9fff0-9\uff08\u300a\u201c])')),
]


def normalize(raw):
    """Returns (text, page_starts, removals). page_starts maps char offset -> page no."""
    # page boundaries come from the form feeds BEFORE we strip anything
    pages, pos = [], 0
    for i, chunk in enumerate(raw.split('\x0c'), start=1):
        pages.append((pos, i))
        pos += len(chunk)

    removals = {}
    t = raw
    for name, rx in RULES:
        found = rx.findall(t)
        removals[name] = len(found)
        t = rx.sub('' if name != "page_marker" else '', t)
    t = re.sub(r'[ \t]+', ' ', t)
    t = re.sub(r'\n{3,}', '\n\n', t)
    return t, pages, removals


# ------------------------------------------------------------------- split
# Sentence end: 。！？ plus the closing-quote/bracket variants that legal
# Chinese puts AFTER the full stop.
SENT_END = re.compile(r'(?<=[。！？])(?=[^”』）\)])|(?<=[。！？][”』）\)])')


def split_sentences(text):
    out = []
    for para in text.split('\n'):
        para = para.strip()
        if not para:
            continue
        start = 0
        for m in SENT_END.finditer(para):
            seg = para[start:m.start()].strip()
            if seg:
                out.append(seg)
            start = m.start()
        tail = para[start:].strip()
        if tail:
            out.append(tail)
    return out


# ------------------------------------------------------------ time patterns
PAT = [
    ("绝对·阿拉伯", r'\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日'),
    ("绝对·年月",   r'\d{4}\s*年\s*\d{1,2}\s*月(?!\s*\d{1,2}\s*日)'),
    ("绝对·仅年",   r'(?<![\d(（])\d{4}\s*年(?!\s*\d{1,2}\s*月)'),
    ("绝对·中文数字", r'[〇一二三四五六七八九十]{2,}\s*年[〇一二三四五六七八九十]{1,3}\s*月[〇一二三四五六七八九十]{1,3}\s*日'),
    ("期间·自至",   r'自[^，。；]{0,30}?起?至[^，。；]{0,30}?[止日]'),
    ("期间·区间",   r'\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日\s*至'),
    ("相对·日内",   r'\d+\s*个?\s*(?:工作)?日内'),
    ("相对·词",     r'(?:次日|翌日|当日|同日|嗣后|旋即|届时|此前|此后|其后|随后|后经|至今|近\s*\d+\s*年|历时)'),
    ("锚定·后前",   r'[^，。；]{2,14}?(?:之后|以后|后的|届满后|届满前|生效后|生效之日起|送达之日起|收到[^，。；]{0,8}后)'),
    ("模糊",        r'(?:年底|年初|上半年|下半年|月初|月末|月底|前后|左右|近日|同年|当年)'),
]


def scan_times(sentences):
    hits = {}
    per_sent = []
    for i, s in enumerate(sentences):
        found = []
        for name, p in PAT:
            for m in re.finditer(p, s):
                found.append((name, m.group(0).strip()))
                hits.setdefault(name, []).append(m.group(0).strip())
        per_sent.append(found)
    return hits, per_sent


if __name__ == "__main__":
    raw = open(sys.argv[1], encoding="utf-8").read()
    text, pages, removals = normalize(raw)
    sents = split_sentences(text)
    hits, per_sent = scan_times(sents)

    print("=== 规范化 ===")
    print(f"原始 {len(raw)} 字 -> 规范化 {len(text)} 字")
    for k, v in removals.items():
        print(f"  {k}: 移除 {v} 处")
    print(f"  页数（按 form feed）: {len(pages)}")

    print("\n=== 分句 ===")
    print(f"句子总数: {len(sents)}")
    L = sorted(len(s) for s in sents)
    print(f"长度 中位数 {L[len(L)//2]} / 均值 {sum(L)//len(L)} / 最长 {L[-1]}")
    for th in (40, 80, 150, 300, 600):
        print(f"  超过 {th} 字的句子: {sum(1 for x in L if x > th)} 句")

    print("\n=== 时间语命中（按类型）===")
    tot = 0
    for name, _ in PAT:
        n = len(hits.get(name, []))
        tot += n
        if n:
            sample = list(dict.fromkeys(hits[name]))[:4]
            print(f"  {name:12s} {n:4d}   例: {' | '.join(sample)}")
    print(f"  合计命中 {tot} 处")

    withtime = sum(1 for f in per_sent if f)
    print(f"\n=== 句子分布 ===")
    print(f"含时间语的句子: {withtime} / {len(sents)}  ({withtime*100//len(sents)}%)")
    print(f"不含任何时间语的句子: {len(sents)-withtime} 句  <- 搜索式方案会整段漏掉的部分")

    with open("/tmp/sents.json", "w", encoding="utf-8") as f:
        json.dump(sents, f, ensure_ascii=False, indent=1)
    print("\n句子数组已写入 /tmp/sents.json")
