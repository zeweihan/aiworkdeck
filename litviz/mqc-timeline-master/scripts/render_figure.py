#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""编号型的排布选择与出图入口。横向排不下就自动改用纵向。

为什么要有这个文件
------------------
排布是内部数学，不是让用户点的菜：模块元素有几个，就该由脚本算出它放在横向还是
纵向、几个泳道、几列。以前这件事分散在两处，而且两处算的不是同一件事：路由器按
事件数查一张表（十三个以内两方对读就说横向多层），渲染器则真的去试排。收窄到 A4
横版 993px 之后两者当场分家 —— 路由器仍然推荐横向，渲染器接着拒绝画它，这是最糟
的那种不一致，用户看到的是一句自相矛盾的建议。

所以这里只留一条路：**真的试排一次，排得下就是排得下。** 路由器也调这个函数，
它报出来的排布就是接下来真的会画出来的那一张。

阶梯（作者定的）
----------------
    元素少          横向，一层交替，卡片最宽
    横向一层不够     横向多泳道，轴上至多三条、轴下至多三条
    横向排不下       转纵向；纵向同样可以承载两侧主张方
    纵向一页装不下   纵向长图，再分页

横向绝不分页。一根时间轴切在两张纸上，读者要把纸并排摊开才读得懂，而分页的前提
本来就是读者不会并排看两页。
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "mqc-litigation-visual-redraw",
                                "scripts"))
import paper                                                   # noqa: E402
import render_multiband                                        # noqa: E402
import render_dated_v2                                         # noqa: E402
import render_spans_v2                                         # noqa: E402
import render_vcolumns                                         # noqa: E402


def _sheet_size(path):
    """读回文件里的真实尺寸。裱过白边之后，返回值必须是**整幅**的尺寸。

    这里出过一次不一致：裱是裱上了，但返回的仍是内容尺寸，于是调用方打印出「整幅
    700x1580」而文件里其实是 784x1638。报出去的数与产物不符，比不报更糟。
    """
    import re as _re
    svg = open(path, encoding="utf-8").read()
    m = _re.search(r'<svg[^>]*width="([\d.]+)"[^>]*height="([\d.]+)"', svg)
    return (float(m.group(1)), float(m.group(2))) if m else (0.0, 0.0)


def _frame_file(path, landscape=True):
    """把已写出的图裱进整幅：四周加白边，长宽比贴着可打印区。

    白边由图自己带，是作者定的：图本身已经有白边，再叠上 Word 的页边距就是在留白之上
    再留白，而且图的长宽比与纸不一致时 Word 会按高度缩放，两侧留出空档（他把图粘进
    Word 量过：一张 991×724 的图只铺到 21.79 厘米宽，左右各空 0.78 厘米）。
    裱在最后一步做，渲染器一律只画内容 —— 让每个渲染器各自留边，那个数就会被抄成
    五份然后开始漂移。
    """
    svg = open(path, encoding="utf-8").read()
    open(path, "w", encoding="utf-8").write(paper.frame(svg, landscape=landscape))


def deliver(m, out_path):
    """走完整条阶梯，返回 (图种, 排布, 说明, 尺寸)。永远给得出一张图。

    图种也在阶梯上。日期型与期间型的做法是**拒绝而不是硬画**：撞点、同侧卡片放不下、
    刻度标签挤住，任一条成立就抛错。这是对的 —— 硬画出来的比例轴会宣称一个材料没有
    的精度。但拒绝必须有人接：八年各一个事件这种教科书式的日期型，在 A4 横版上同侧
    相邻卡片要 230px 而两格只有 182px，照样被拒；此前它就断在那里了。
    现在的顺序是期间型 → 日期型 → 编号型，每一档拒绝都带机械理由，最后一档编号型
    永远收得住（它的间距不承诺任何东西，所以不会错）。
    """
    tried = []
    if m.get("spans"):
        try:
            svg, w, h = render_spans_v2.render(m)
            open(out_path, "w", encoding="utf-8").write(svg)
            _frame_file(out_path, landscape=True)
            return ("期间型", "横向", f"{len(m['spans'])} 段期间，重叠关系是本图的论点。",
                    _sheet_size(out_path))
        except Exception as exc:
            tried.append(f"期间型：{str(exc).splitlines()[0]}")
    evs = m.get("events") or []
    if evs and all((e.get("time") or {}).get("certainty") == "exact" for e in evs):
        try:
            svg, w, h = render_dated_v2.render(m)
            open(out_path, "w", encoding="utf-8").write(svg)
            _frame_file(out_path, landscape=True)
            return ("日期型", "横向", f"{len(evs)} 个时点全部有精确日期，"
                                     f"轴按等长的单位格铺开，距离本身在说话。",
                    _sheet_size(out_path))
        except Exception as exc:
            tried.append(f"日期型：{str(exc).splitlines()[0]}")
    # 只有期间、没有事件的地图，期间型拒绝之后编号型无事可画。以前这里直接崩，
    # 只留一个 traceback。阶梯确实到此为止了，但到此为止也要说成一句话：说清试过
    # 哪几档、各自为什么不行，用户才知道下一步该动取材范围还是动时间跨度。
    if not evs:
        raise RuntimeError(
            "；".join(tried) + "。而这份地图只有期间、没有时点，编号型无从落笔。"
            "请缩小取材的时间范围，或把过短的那一段从取材里去掉。"
            if tried else "这份地图既没有期间也没有事件，无从落笔。")
    form, why, wh = choose_and_render(m, out_path)
    if tried:
        # tried 里每条本身就以句号收尾，直接再拼一个「。」会印出「。。」——
        # 原来这句被调用方截断到 110 字，看不见；不截断之后就露出来了。
        _head = "；".join(tried).rstrip("。；;.")
        why = _head + f"。故改用编号型：{why}"
    return ("编号型", form, why, wh)


def choose_and_render(m, out_path):
    """试排横向，不成就纵向。返回 (形态, 说明, 尺寸)。

    说明这一句是要给用户看的，所以它必须带上机械依据：换了形态而不说为什么，
    等于替律师做了一个他不知道的决定。
    """
    try:
        w, h, bu, bd, _fits, pitch = render_multiband.render(m, out_path)
    except Exception as exc:
        why = str(exc).splitlines()[0]
    else:
        over = paper.over_budget(w, h, landscape=True)
        if not over:
            _frame_file(out_path, landscape=True)
            bands = f"轴上 {bu} 条泳道、轴下 {bd} 条" if (bu + bd) > 2 else "一层上下交替"
            return ("横向", f"{len(m.get('events', []))} 个元素在 A4 横版上排得下："
                            f"{bands}，卡宽 {render_multiband.CARD_W:.0f}px。",
                    _sheet_size(out_path))
        why = over
    # 横向排不下：自动改用纵向，不停下来问。形态选择是算出来的，不是点菜。
    # 纵向这一步也要兜住。它抛错时整条阶梯就断在最后一级台阶上，而按设计这条阶梯
    # 不该有尽头。抛错本身也不能是裸的 traceback：那样调用方（含守卫）只拿到一句
    # NameError，看不出是哪一档没走通。
    try:
        w, h, k, cw = render_vcolumns.render(m, out_path)[:4]
        _frame_file(out_path, landscape=False)
    except Exception as exc2:
        raise RuntimeError(
            f"横向与纵向都没走通。横向：{why}；纵向：{exc2}") from exc2
    # 纵向长图超过一页就**真的分页**，不是只报一句「分 N 页」。
    # 这里原来只算出页数写进说明，产物仍是一张长图；而律师要的是能直接打印的那几页。
    # 阶梯的最后一档是「纵向一直延续下去、分很多张图」，所以这一步不做，那一档就是空的。
    pages = int(h // paper.PORT_H) + 1
    if pages > 1:
        import paginate
        outdir = os.path.dirname(os.path.abspath(out_path)) or "."
        stem = os.path.splitext(os.path.basename(out_path))[0]
        try:
            files, npages = paginate.paginate(m, outdir, prefix=stem + "-page")
            tail = f"，纵向长图 {h:.0f}px，已另出 {npages} 页可直接打印"
        except Exception as exc3:
            tail = f"，纵向长图 {h:.0f}px 需分 {pages} 页，但分页未成（{exc3}）"
    else:
        tail = "，一页装得下"
    return ("纵向", f"横向排不下（{why}）故改用纵向：每侧 {k} 列，"
                    f"卡宽 {cw:.0f}px{tail}。", _sheet_size(out_path))


def predict(m):
    """只要结论，不出图。路由器用它，保证建议与真正画出来的一致。"""
    import tempfile
    tmp = tempfile.NamedTemporaryFile(suffix=".svg", delete=False)
    tmp.close()
    try:
        form, why, _wh = choose_and_render(m, tmp.name)
        return form, why
    finally:
        os.unlink(tmp.name)


if __name__ == "__main__":
    src, out = sys.argv[1], sys.argv[2]
    m = json.load(open(src, encoding="utf-8"))
    kind, form, why, (w, h) = deliver(m, out)
    print(f"[{kind} · {form}] {w:.0f}x{h:.0f}  {why}")
    print(f"PNG 导出请放大 {paper.raster_scale(w)} 倍（纸上 "
          f"{paper.raster_dpi(w):.0f} dpi，印刷下限 {paper.PRINT_DPI}）")
    print(f"已写入 {out}")
