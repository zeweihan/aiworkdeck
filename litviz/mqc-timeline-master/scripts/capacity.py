#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""容量查询：给定一份语义地图，算出每个模块能装多少字。

这是前端与后端之间那个唯一的接口。作者反复讲过的核心思路：
**后端按数学算出每个模块的容量，前端据此把材料抽到那个字数** —— 不是随手写几个字然后
看装不装得下，而是先问「这一档能放多少」，再把内容写到那个长度。

我一直没做对的地方：手写四个字的标题塞进能放十八个字的卡片。图上一大片空白，而材料里
的信息被丢掉了。容量既是上限也是**目标** —— 装得下十八个字就该写到接近十八个字，那才
是把材料忠实还原，也才是这套数学模型的用处。

用法：
    python capacity.py <semantic-map.json>
输出每个模块的可用字数，以及图名的可用字数。
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
for _p in (HERE, os.path.join(HERE, "..", "..", "mqc-litigation-visual-redraw", "scripts")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import paper                                                     # noqa: E402
from common import text_w, wrap                                  # noqa: E402


def _chars(width_px, fs):
    """这个宽度里放得下几个汉字。"""
    n = 0
    while text_w("字" * (n + 1), fs) <= width_px:
        n += 1
    return n


def _rows_cap(m, kind=None):
    """该档允许的正文行数上限。**按图种取，不许一把尺子量到底。**

    编号型：行数上限**由剩余高度反解**，不查表。查表那一版返回固定的 3，于是容量报
    18 字，而实际把高度用满能到 60 字 —— 只看宽度不看高度，容量就少算三分之二。

    日期型：它的卡宽是固定的 214px，卡片只能往下长，高度上唯一的限制是纸高 ——
    于是容量报到 126 字（八行）。而**日期型唯一不可替代的能力是让轴上的空白成为证据**，
    卡片越高，轴在图上的占比越小、论点越弱。所以作者看过四行 / 六行 / 八行三档实图之后
    定在**六行**（见 render_dated_v2.MAX_BODY_ROWS 的注释）。这里就取那个数，
    不在这里另写一个 —— 上限只有一个出处，改它只改一处。

    这一处原来的毛病是**借错了尺子**：不论什么图种都按 render_multiband 的高度预算算，
    而那是横向编号型的几何。日期型压根没有自己的行数上限，所以 126 字这个数不是
    「日期型允许 126 字」，而是「没人管它」。

    返回 0 表示不做行数核对（拿不到渲染器时）。
    """
    # **日期型不在这里判。** 我一度在这里加了「日期型取 D.MAX_BODY_ROWS」这一支，
    # 做改坏验证时发现它测不出来：渲染器的 [D14] 门禁已经在拦超行，deliver 随即按阶梯
    # 退回编号型，二分自然就停在六行那一档 —— 这一支在与不在，结果一模一样。
    # 按这个项目的规矩，声明了却不影响产物的东西是死分支，所以撤掉。
    # **行数上限只有一个出处：render_dated_v2.MAX_BODY_ROWS 那道门禁。**
    try:
        import render_multiband as MB
        import paper as PP
        bands = 1                      # 一层优先，见 [M10]
        Rr0 = MB.R_MIN + 2
        hc_max = (PP.LAND_H - MB.TITLE_ZONE_EST - 58 - 2 * Rr0
                  - 2 * (MB.CONNECT + MB.BAND_GAP * (bands - 1))) / (2 * bands)
        return max(1, int((hc_max - MB.PAD_Y * 2 - MB.FS_DATE - MB.DATE_GAP) // MB.LH))
    except Exception:
        return 0


def measured_cap(m, lo=1, hi=140):    # [C7] 容量既是上限也是目标
    """**实测**这份地图每个模块最多几个字：把所有 head 换成 N 个字，二分找上限。

    不用公式估。公式算「每行字数 × 允许行数」得出的是理论值，而真实上限还受行数上限、
    图高、日期那一行的宽度共同约束 —— 13 事项横向公式报 18 字，实测也是 18 字，但
    21 字才被拒；而混用不同长度时某一张卡先超行，整张图就转了纵向。
    所以容量只能试排出来，而且要以**最长的那一条**为准：一张图里只要有一个模块超容量，
    形态就会变。前端拿到这个数之后，每一条都要写在这个数以内。
    """
    import copy
    import tempfile
    import render_figure as RF
    def ok(c):
        # 判据不能只看「形态有没有变」：文字变长时卡片会多长出几行，图仍画得出来，
        # 于是实测报 80 字而卡片理论容量只有 56 字。真正的上限是**行数不超过该档允许的
        # 行数**，超了就是把卡片撑高、把图挤变形，虽然没报错但已经不是设计中的样子。
        t = copy.deepcopy(m)
        for e in t.get("events", []):
            e["head"] = "字" * c
            e.pop("head_short", None)
            e.pop("body", None)
        tmp = tempfile.NamedTemporaryFile(suffix=".svg", delete=False)
        tmp.close()
        try:
            _k, form, _w, _wh = RF.deliver(t, tmp.name)
            # **判据要带上图种，不能只看排布。** 这是 126 字那个数的根子：
            # 字一多，deliver 会按阶梯从日期型退回编号型（[C4] 拒绝之后由入口兜住），
            # 而两者的排布都叫「横向」—— 只比 form 的话二分完全察觉不到图种已经换了，
            # 于是把「换成编号型之后还画得出」当成了「日期型能装这么多字」。
            # 容量必须是**在同一个图种里**的容量。
            form = f"{_k}·{form}"
            # 顺带核对行数：产物里最高的那张卡不许超过该档允许的行数
            svg = open(tmp.name, encoding="utf-8").read()
            import re as _re
            hs = [float(x) for x in _re.findall(
                r'<rect x="[\d.]+" y="[\d.]+" width="[\d.]+" height="([\d.]+)" rx="12"', svg)]
            if hs and _rows_cap(m, _k):
                # 量卡高时也要用**这个图种自己的**内边距与行距。原来一律拿
                # render_multiband 的常数去量日期型的卡片，两套几何不同（日期型
                # PAD_Y / LH 与编号型不是同一个数），量出来的行数是错的。
                import render_multiband as _MB
                _one = _MB.PAD_Y * 2 + _MB.FS_DATE + _MB.DATE_GAP + _MB.LH
                if max(hs) > _one + (_rows_cap(m, _k) - 1) * _MB.LH + 1:
                    return "超行"
            return form
        except Exception:
            return None
        finally:
            os.unlink(tmp.name)
    base_form = ok(lo)
    best, best_form = lo, base_form
    while lo <= hi:
        mid = (lo + hi) // 2
        f = ok(mid)
        if f == base_form:
            best, best_form = mid, f
            lo = mid + 1
        else:
            hi = mid - 1
    return best, best_form


def _style_of(m):
    """这张图用哪种风格。地图里带就用地图里的，否则读 state（第三轮写进去的）。"""
    if (m or {}).get("style"):
        return m["style"]
    try:
        import json as _j
        import os as _o
        if _o.path.exists("state.json"):
            return _j.load(open("state.json", encoding="utf-8")).get("style") or ""
    except Exception:
        pass
    return ""


def _guizang_title_fs(canvas_w):
    """歸藏风的图名字号。**三个参数取 v1 的 TOKENS，公式不抄第二份。**
    抄一遍就会跟着 v1 漂；拿不到 v1 时返回 None，由调用方沿用阶梯字号。"""
    try:
        import render as _V1R
        _T = _V1R.TOKENS["tuning"]
        return max(_T["guizang_title_min"],
                   min(_T["guizang_title_max"],
                       round(canvas_w * _T["guizang_title_ratio"])))
    except Exception:
        return None


def _title_fs_cap(avail, txt, style="", canvas_w=None):
    """图名的（字号, 容量）。**三处原来各算一遍，现在收在这一个出口。**

    起因是真材料：歸藏风把图名从 30 号放大到 42 号（v1 有意为之，字号随画布宽度走，
    免得宽图的标题显得太小），但它**只原地放大、不折行也不量宽度**。33 字的图名在
    42 号下宽 1302px、而整幅只有 1154px —— 图上左右两端的字被切掉了。
    v1 自己的图名最长 26 字、多数 7 到 12 字，那个设定在它的用法下是安全的。

    所以修在**源头**：选了歸藏风就按它实际会用的字号报容量，让前端一开始就写得下。
    等变换之后再折行试过一版，结果把案号里的数字劈成两半（违反 [D10] 原子不许拆）、
    第二行还盖住了第一张卡，已撤回。

    **歸藏风只能按一行算**：阶梯那套容量是「字号 × 两行」，因为 title_fit 会折行；
    歸藏风不折行。实测按两行报 44 字，26 字就溢出画布；按一行报 22 字，
    实测 25 字 1050px 仍在 1154px 之内，所以 22 字是安全的一档。
    """
    if style == "歸藏风":
        gfs = _guizang_title_fs(canvas_w or paper.SHEET_LAND_W)
        if gfs:
            n = 0
            while text_w("字" * (n + 1), gfs) <= avail:
                n += 1
            return gfs, n
    fs = paper.title_fit(txt, avail, text_w, wrap)[0]
    hit = [c for c in paper.title_capacity(avail, text_w) if c[0] == fs]
    return fs, (hit[0][2] if hit else 0)


def _lane_label_cap(kind, form, cw_min):
    """侧标签一行放得下几字 × 两行。只有编号型横向有侧标签的横向余量问题。

    纵向那一档的侧标签在两列之间，横向余量与事项数无关，不受这条限制。
    """
    if kind != "编号型" or form != "横向" or not cw_min:
        return 0
    import render_multiband as MB
    avail = max(1.0, cw_min / 2 - 6)          # 首圆点 x 减左边距，留 6px 净空
    n = 0
    while text_w("字" * (n + 1), MB.FS_DATE) <= avail:
        n += 1
    return n * 2                               # 至多两行


def probe(m):
    """试排一次，返回实际选中的形态与每个模块的容量。

    容量必须**试排之后**才知道，不能查表：形态是自动选的（横向排不下自动转纵向），
    而卡宽由形态与事项数一起决定。所以这里真的调一次渲染器，拿它算出的卡宽回答。
    """
    import tempfile
    import render_figure as RF
    tmp = tempfile.NamedTemporaryFile(suffix=".svg", delete=False)
    tmp.close()
    try:
        kind, form, why, wh = RF.deliver(m, tmp.name)
    finally:
        os.unlink(tmp.name)

    if kind == "期间型":
        # [C8] 期间型的容量**不是一个数，是每一段各一个数**。
        # 条身的长度由那一段的真实天数决定，所以同一张图上各段容量差别极大（实测
        # 从 0 字到 33 字）。这跟编号型根本不同：编号型所有卡片同宽，容量是全图一个数。
        # 三档落位对应三个容量：条内居中（条身宽 − 16）、条右侧（右侧余量，至多两行）、
        # 条下方居中（整幅宽，可多行）。前端要**逐段**问容量，不能问一次就套用全图。
        import render_spans_v2 as G
        from datetime import date as _d
        spans = m.get("spans", [])
        a0 = min(G.parse_date(s["from"]) for s in spans)
        a1 = max(G.parse_date(s["to"]) for s in spans)
        per = []
        for s in spans:
            days = (G.parse_date(s["to"]) - G.parse_date(s["from"])).days
            frac = days / max(1, (a1 - a0).days)
            bw = frac * (paper.LAND_W - G._LEFT - G.RIGHT if hasattr(G, "_LEFT")
                         else paper.LAND_W - 264)
            inner = _chars(max(0, bw - 16), G.FS_LABEL)
            per.append({"id": s.get("id"), "label": s.get("label_text", "")[:12],
                        "days": days, "bar_px": round(bw), "in_bar": inner})
        return {"kind": kind, "form": form, "why": why, "n": len(spans),
                "per_span": per, "fs_body": G.FS_LABEL,
                "right_cap": _chars(300, G.FS_LABEL) * 2,
                "below_cap": _chars(paper.LAND_W - 140, G.FS_LABEL),
                **dict(zip(("title_fs", "title_cap"),
                            _title_fs_cap(paper.LAND_W - 264,
                                          m.get("title_text", ""),
                                          _style_of(m), paper.SHEET_LAND_W))),
                # 侧标签不受横向余量限制（期间型无侧标签；纵向的侧标在两列之间）
                "lane_label_cap": 0}

    if kind == "日期型":
        # [C9] 日期型的卡宽是**固定的** 214px，与时点个数无关（时点多了是轴上挤，
        # 不是卡片变窄）。所以它的容量是全图一个数：每行 14 字 × 允许行数。
        # 行数上限由纸高反解，实测四行时全图仅 460px、纸高 726px 还很宽松，所以
        # 日期型的约束从来不在高度，而在卡宽与轴上的碰撞门禁（D5 / D6）。
        import render_dated_v2 as DD
        per_line = _chars(DD.CARD_W - 2 * DD.PAD_X, DD.FS_BODY)
        return {"kind": kind, "form": form, "why": why,
                "n": len(m.get("events", [])),
                "card_min": DD.CARD_W, "card_elegant": DD.CARD_W,
                "fs_body": DD.FS_BODY, "rows": 4,
                "per_line_min": per_line, "per_line_elegant": per_line,
                "cap_min": per_line * 4, "cap_elegant": per_line * 4,
                **dict(zip(("title_fs", "title_cap"),
                            _title_fs_cap(paper.LAND_W - 264,
                                          m.get("title_text", ""),
                                          _style_of(m), paper.SHEET_LAND_W))),
                # 侧标签不受横向余量限制（期间型无侧标签；纵向的侧标在两列之间）
                "lane_label_cap": 0}

    if form == "横向":
        import render_multiband as MB
        cw_min = MB.CARD_W
        cw_eleg = MB.ELEGANT_W or cw_min
        pad, fs, lh = MB.PAD_X, MB.FS_BODY, MB.LH
        rows = _rows_cap(m) or 3
        per_min = _chars(cw_min - 2 * pad, fs)
        per_eleg = _chars(cw_eleg - 2 * pad, fs)
        avail_title = paper.LAND_W - 2 * MB.MARGIN_X
    else:
        import render_vcolumns as VC
        box = VC.layout(m)
        cw_min = cw_eleg = box["cw"] if isinstance(box, dict) and "cw" in box else 0
        if not cw_min:
            cw_min = cw_eleg = VC._card_w(1)
        pad, fs, lh = VC.CARD_PAD_X, VC.FS_BODY, VC.LH
        rows = 3
        per_min = per_eleg = _chars(cw_min - 2 * pad, fs)
        avail_title = paper.PORT_W - 48

    _cw = paper.SHEET_LAND_W if form == "横向" else paper.SHEET_PORT_W
    tfs, _tcapn = _title_fs_cap(avail_title, m.get("title_text", ""),
                                _style_of(m), _cw)
    _tz = paper.title_zone_h(tfs, 1)
    tcap = [(tfs, 0, _tcapn)]
    return {
        "kind": kind, "form": form, "why": why,
        "n": len(m.get("events") or m.get("spans") or []),
        "card_min": round(cw_min), "card_elegant": round(cw_eleg),
        "fs_body": fs, "rows": rows,
        "per_line_min": per_min, "per_line_elegant": per_eleg,
        "cap_min": per_min * rows, "cap_elegant": per_eleg * rows,
        "title_fs": tfs, "title_cap": tcap[0][2] if tcap else 0,
        # 侧标签的字数容量：它贴左边缘起排，右边第一个障碍是首个圆点，
        # 而首圆点 x = MARGIN_X + 最小卡宽/2（六个档位实测全一致）。
        # 事项越多卡越窄，余量越小：6 项 101px（8 字）、10 项 69px（5 字）、
        # 18 项 31px（2 字）。放不下折两行，所以容量 = 单行字数 × 2。
        # 这个数要报给前端，因为渲染器**不截断也不缩字号**（与图名同一条规矩）：
        # 两行还放不下就该在这一步拦住、让前端换个短名。
        # 作者定的用法：**优先四五个字**，够区分就行，两行只是兜底。
        "lane_label_cap": _lane_label_cap(kind, form, cw_min),
    }


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: capacity.py <semantic-map.json>")
        sys.exit(2)
    m = json.load(open(sys.argv[1], encoding="utf-8"))
    r = probe(m)
    if r["kind"] == "期间型":
        print(f"形态　期间型 · 横向　{r['n']} 段期间　标签 {r['fs_body']}px")
        print()
        print("**逐段容量**（条身长度由真实天数决定，所以每段不同）")
        for s in r["per_span"]:
            place = "条内居中" if s["in_bar"] >= 3 else "放不下，落到条右侧或下方"
            print(f"  {s['id']}　{s['days']:>5} 天　条宽 {s['bar_px']:>4}px　"
                  f"条内 {s['in_bar']:>2} 字　{place}")
        print()
        print(f"  落到条右侧：至多 {r['right_cap']} 字（两行）")
        print(f"  落到条下方：每行至多 {r['below_cap']} 字，可多行")
        print()
        print("前端要**逐段**取容量：短段只能给极短的标签，或者接受它落到条外。")
        sys.exit(0)
    print(f"形态　{r['kind']} · {r['form']}　事项 {r['n']} 个")
    print(f"卡片　最小宽 {r['card_min']}px　优雅宽 {r['card_elegant']}px　"
          f"正文 {r['fs_body']}px　至多 {r['rows']} 行")
    print()
    print(f"**每个模块的字数容量**")
    print(f"  最小宽的卡：每行 {r['per_line_min']} 字 × {r['rows']} 行 = "
          f"{r['cap_min']} 字")
    print(f"  优雅宽的卡：每行 {r['per_line_elegant']} 字 × {r['rows']} 行 = "
          f"{r['cap_elegant']} 字")
    print(f"  图名：{r['title_fs']}px 两行，至多 {r['title_cap']} 字")
    print()
    cap, form2 = measured_cap(m)
    print(f"**实测上限**：每个事项最多 {cap} 字（再多一个字，形态就会从“{form2}”变掉）")
    print()
    print("前端据此把每个事项写到接近这个数，不要只写几个字 —— 容量既是上限也是目标。")


# ------------------------------------------------------------ 前后端的映射
def budget(dates, sides=None, has_spans=False, title="", spans=None):
    """**只给骨架就问出形态与容量**，不需要先把字写好。

    这是前端与后端之间那个映射。此前只有 probe(m)，它要一份完整地图（含已写好的 head）
    才能算容量 —— 而前端需要在**写字之前**知道容量，于是成了鸡生蛋：写字要先知道容量，
    算容量要先有字。

    输入是骨架：
      dates     每个事项的日期（"2024/3/5" 或 None 表示只有先后没有日期）
      sides     每个事项属于哪一方（"P"/"D"），None 表示单侧
      has_spans 材料的争点是不是有长度的期间
      title     图名（用来算图名容量，可以先给个草稿）

    返回：形态、每个模块的字数容量、图名容量、以及这一档的说明。
    前端拿到之后按容量写字，再回来出图 —— 两趟，而不是猜一趟。
    """
    import copy
    import tempfile
    n = len(dates)
    skeleton = {
        "schema_version": 2,
        "layout": "period_gantt" if has_spans else "numbered_point_timeline",
        "title_text": title or "图名草稿",
        "events": [],
    }
    if sides:
        skeleton["lanes"] = [{"id": "P", "label_text": "原告主张"},
                            {"id": "D", "label_text": "被告主张"}]
    if has_spans:
        # 期间型读的是 **spans**，不是 events。此前只把 layout 写成 period_gantt 而内容
        # 仍造进 events，路由自然看不到任何期间，退回编号型 —— 声明与内容不一致时，
        # 走的是内容那条路。骨架里没给起止就按相邻两点当一段，只为算容量。
        sp = spans or [{"from": dates[i], "to": dates[i + 1]}
                       for i in range(len(dates) - 1) if dates[i] and dates[i + 1]]
        skeleton["spans"] = [
            {"id": f"s{i + 1}", "from": s["from"], "to": s["to"],
             "label_text": "期", "unit_type": "fact",
             "source": {"file": "骨架", "locator": f"段{i}"}}
            for i, s in enumerate(sp)]
        skeleton["points"] = []
        skeleton["axis"] = {"start": sp[0]["from"], "end": sp[-1]["to"]} if sp else {}
        skeleton.pop("events", None)
        r = probe(copy.deepcopy(skeleton))
        return {"form": f"{r['kind']} · {r['form']}",
                "cap_per_module": min((x["in_bar"] for x in r.get("per_span", [])),
                                     default=0),
                "cap_title": r.get("title_cap", 0),
        "cap_lane_label": r.get("lane_label_cap", 0),
                "per_span": r.get("per_span", []),
                "note": (f"{len(sp)} 段期间 → {r['kind']}·{r['form']}。"
                         f"**期间型的容量逐段不同**（条身长度由真实天数决定）："
                         f"最短的一段条内只放得下 "
                         f"{min((x['in_bar'] for x in r.get('per_span', [])), default=0)} 字，"
                         f"最长的一段 "
                         f"{max((x['in_bar'] for x in r.get('per_span', [])), default=0)} 字；"
                         f"放不下的落到条右侧（一行）或条下方。"),
                "raw": r, "hint": ""}

    for i, d in enumerate(dates):
        ev = {"id": str(i + 1), "head": "事", "unit_type": "fact",
              "source": {"file": "骨架", "locator": f"句{i}"},
              "time": {"certainty": "exact" if d else "relative",
                       "origin": "extracted", "kind": "occur",
                       "raw": d or "先后可定", "date": d,
                       "date_text": (d.replace("/", ".") if d else "")}}
        if sides:
            ev["lane"] = sides[i]
        skeleton["events"].append(ev)

    # 用骨架试排一次，拿到形态与卡宽；再二分实测这一档的字数上限。
    r = probe(copy.deepcopy(skeleton))
    cap, form2 = measured_cap(copy.deepcopy(skeleton))
    return {
        "form": f"{r['kind']} · {r['form']}",
        "cap_per_module": cap,
        "cap_title": r.get("title_cap", 0),
        "cap_lane_label": r.get("lane_label_cap", 0),
        "note": (f"{n} 个事项 → {r['kind']}·{r['form']}，"
                 f"每个模块至多 {cap} 字，图名至多 {r.get('title_cap', 0)} 字。"
                 f"容量既是上限也是目标：写到接近这个数。"),
        "raw": r,
        "hint": _budget_hint(sides, cap, r),
    }


def _budget_hint(sides, cap, r):
    """容量偏低时，告诉前端原因与可行的改善办法。

    否则前端只看到「每个模块 36 字」，不知道这是材料决定的还是可以改善的。
    实测过：八个事项本可写 120 字，但材料里有连续两个原告事项，升了一层，容量掉到 36 ——
    层数越多每张卡越矮、字越少（M10）。这一档的改善办法是把连续同侧的两项合并成一项，
    或者接受少写一些字，都是前端的选择，代码只把原因摆出来。
    """
    if not sides:
        return ""
    runs, cur, last = 1, 1, None
    for s in sides:
        cur = cur + 1 if s == last else 1
        runs = max(runs, cur)
        last = s
    if runs >= 2 and r.get("form") == "横向":
        return (f"同侧最长连续 {runs} 项，因此需要分层，容量随之下降。"
                f"若把连续同侧的相邻两项合并成一项，容量可显著回升；"
                f"也可以接受当前字数，两者都行。")
    return ""


if __name__ == "__main__" and len(sys.argv) > 2 and sys.argv[1] == "--budget":
    # 用法: capacity.py --budget 2024/3/5,2024/4/18,2024/6/28 [P,D,P]
    _ds = [x if x != "-" else None for x in sys.argv[2].split(",")]
    _sd = sys.argv[3].split(",") if len(sys.argv) > 3 else None
    _b = budget(_ds, _sd)
    print(_b["note"])
    sys.exit(0)
