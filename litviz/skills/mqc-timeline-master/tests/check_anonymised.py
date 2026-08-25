#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Refuse to ship anything that still carries a real identifier.

Real case files come in un-anonymised and that is fine — they are the user's own
matter. What must never happen is a fragment of one surviving into the
repository, where it becomes public.

This has already gone wrong once. Three example maps had their party names
replaced but their `source.quote` fields copied verbatim from the judgment, so
two litigants' full names, the court's district and the appointed accountancy
firm all sat in files that were about to be committed. Substituting the parties
is not the same as anonymising the file; the quotes are where it leaks.

Patterns, not a name list: a list only catches the names someone remembered.
An 18-digit citizen number is an 18-digit citizen number whatever the case.

It then went wrong a SECOND time, and in a way the patterns above could not see:
a real hotel and a real trading company survived in the step-6 example inside
`references/model-steps.md`, and a procurement project title specific enough to
identify the school survived inside two `source.quote` fields. Neither is a
number, so no amount of number matching would have caught either.

Hence the two structural rules below. Both are whitelists of the anonymisation
CONVENTION rather than blacklists of names, which is what makes them fail
closed: a real name is never spelled 甲 or 乙, so anything real trips the rule
whether or not the person writing it remembered to look.

What they do NOT cover: a proper name with no organisational suffix and no inner
quotation, for instance a bare product model or a place name. Those stay a
matter of reading the diff. Saying so is the point; a guard believed to cover
more than it does is worse than no guard.
"""
import json
import os
import re
import sys

PATTERNS = [
    (r"\d{17}[\dXx]",                    "公民身份号码"),
    (r"9[12]\d{6}[A-Z0-9]{9,10}",        "统一社会信用代码"),
    (r"(?<!\d)1[3-9]\d{9}(?!\d)",        "手机号码"),
    (r"（\s*20\d\d\s*）[^\s，。；]{0,10}民(初|终|再|辖)",  "案号"),
    (r"\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日出生",     "出生日期"),
    # 、and ）end the run: without them this matched the sentence in HANDOVER.md
    # that merely LISTS what the guard looks for（…案号、出生日期、户籍地址、住所地址）,
    # and a guard that reports its own documentation gets switched off.
    (r"户籍地[^\s，。；、）]{4,}",         "户籍地址"),
    (r"住所地?[:：][^\s，。；]{6,}",      "住所地址"),
]

# AUTHOR.md exists in order to name a real person and real institutions, all of
# them the author's own and deliberately public. Everything else is scanned; this
# one file is a stated exception rather than a silent one, and it is a single
# named path so nothing new can slip under it by accident.
EXEMPT_FILES = ("AUTHOR.md",)

# Anonymisation is repo hygiene, not a feature of any one skill, so the gate
# covers everything that ships in the plugin. Called with the plugin root it
# sweeps every skill; called with one skill directory it sweeps just that one.
SKIP_DIRS = {"__pycache__", ".git", "assets"}
SCAN_EXT = (".json", ".md", ".py", ".txt", ".jsonc")

# ------------------------------------------------------- 机构名（结构性规则一）
# A name ending in an organisational tail must carry a placeholder somewhere in
# it. 甲公司 passes, 丁建设有限公司 passes, 目标公司 passes, 年年吉祥公司 does not.
#
# Looking only at the character immediately before the tail was tried first and
# is wrong: it rejects 甲控股集团 and 丁建设有限公司, which are exactly how a
# properly anonymised group company gets written. A guard that cries wolf on
# correct input teaches the reader to skip it, which costs more than the leak it
# was meant to stop. So the window is the whole run of Chinese characters back to
# the nearest punctuation or Latin character, and one placeholder anywhere in it
# is enough.
#
# The tail list holds only endings that appear in names and essentially nowhere
# else. 酒店 on its own was tried and dropped, because 经营酒店 is an ordinary
# verb phrase; 大酒店 does not have that problem.
PLACEHOLDER = (
    "甲乙丙丁戊己庚辛壬癸"      # the party placeholders themselves
    "某X"                       # 某公司、X公司
    "目标总分子母控股顶层"       # role descriptors: 目标公司、总公司、控股公司
    "上下级关联第三方"           # 上级公司、关联公司、第三方单位
    "生产供货采购受托"           # 生产商、供货方、受托机构
    "有限责任股份"              # 有限公司、股份公司 standing alone
    "该本案涉原被告"            # 该公司、本案、案涉公司、原告公司
)
# The window stops at anything that cannot sit INSIDE a Chinese company name.
# Without this, 丙公司为华鼎实业集团 passed: the window reached back far enough to
# find the 丙 of the previous clause, and one placeholder anywhere was enough. A
# planted name being let through is exactly what the plant tests are for.
BOUNDARY = "为与和及或向由在是对从至到把被让给并的了着过将其此该按据经名"
ENTITY_TAIL = r"(?:公司|集团|学院|大学|中学|小学|大酒店|事务所|银行|律师所)"
# 公司法、公司章程、公司治理 are common nouns, not names.
TAIL_NOT_A_NAME = r"(?!法|章程|治理|登记|注销|类型|名称|制度|结构|印章|股东)"
ENTITY = re.compile(r"([\u4e00-\u9fff]{0,10})" + ENTITY_TAIL + TAIL_NOT_A_NAME)

# ------------------------------------------------- 引号内专名（结构性规则二）
# An example quote may name an instrument in 《》, which is a generic type
# (《设备采购合同》). An INNER quotation is different: material puts 引号 around
# proper names, so a quoted span inside a quoted sentence is almost always a
# project, account or product lifted verbatim. That is exactly how the school's
# tender title got in.
INNER_QUOTE = re.compile(r"[“”「」]")


def _entity_hits(text):
    out = []
    for m in ENTITY.finditer(text):
        window = m.group(1)
        for i in range(len(window) - 1, -1, -1):
            if window[i] in BOUNDARY:
                window = window[i + 1:]
                break
        # Nothing Chinese in front of the tail means there is no name here: a
        # bare 公司 or a 甲<公司>&乙 escaping fixture is a common noun.
        if not window:
            continue
        if any(ch in PLACEHOLDER for ch in window):
            continue
        out.append(m.group(0))
    return out


def _quote_hits(path):
    """source.quote in an example map may not carry an inner quotation."""
    try:
        m = json.load(open(path, encoding="utf-8"))
    except (ValueError, OSError):
        return []
    out = []
    for e in m.get("events", []) + m.get("stipulated", []):
        q = ((e.get("source") or {}).get("quote") or "")
        if INNER_QUOTE.search(q):
            out.append(q[:24])
    return out


# A file that carries the guard's own test material necessarily contains the
# very strings the guard hunts for. It may exempt itself by naming this marker,
# but only under tests/, and scan() returns the exempt list so a caller can print
# it. An exemption nobody can see is a hole; one that gets printed every run is a
# decision someone has to keep re-approving.
SELFTEST_MARK = "ANON_GUARD_SELFTEST"


def scan(root, exempt=None):
    hits = []
    for dirpath, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
        for fn in sorted(files):
            if not fn.endswith(SCAN_EXT):
                continue
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, root)
            # the guard's own pattern table is not a finding
            if os.path.abspath(p) == os.path.abspath(__file__):
                continue
            if fn in EXEMPT_FILES:
                if exempt is not None:
                    exempt.append(rel)
                continue
            try:
                t = open(p, encoding="utf-8").read()
            except (UnicodeDecodeError, OSError):
                continue
            if os.path.basename(dirpath) == "tests" and SELFTEST_MARK in t:
                if exempt is not None:
                    exempt.append(rel)
                continue
            for pat, what in PATTERNS:
                for mm in re.finditer(pat, t):
                    hits.append((rel, what, mm.group(0)[:24]))
            for frag in _entity_hits(t):
                hits.append((rel, "非占位符机构名", frag))
            if os.path.basename(dirpath) == "examples" and fn.endswith(".json"):
                for frag in _quote_hits(p):
                    hits.append((rel, "引文内嵌专名", frag))
    return hits


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..")
    exempt = []
    found = scan(root, exempt)
    for f in exempt:
        print(f"  略过（自测样本）{f}")
    for f, what, frag in found:
        print(f"  {f}: 发现{what} {frag!r}")
    print(f"{'未发现未脱敏标识' if not found else str(len(found)) + ' 处未脱敏标识'}")
    sys.exit(1 if found else 0)
