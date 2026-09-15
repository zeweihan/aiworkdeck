#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""纸张预算。四个数只在这里算一次，谁要用谁来取。

以前这些数字散在五个渲染器里各写死一份，结果它们来自三套互不相同的假设：
横向那个 1177px 隐含每边 10mm 页边距，竖版的 722 与 1092 隐含每边 20mm，
而横向的高度上限根本没有人定过，于是存在一种宽度合规、高度出界而不报错的图。
一个数字被抄成五份，就一定会分头漂移，所以这里只留一份。

预算怎么算出来的
----------------
律师把图插进 Word 文书再打印，页边距按 Word 的设置：上下 2.54 厘米、
左右 3.17 厘米、装订线 0。这是作者给的实际设置，不是推测。

图上最小的字号是 12px（`common.FS["note"]`），印出来不许低于 8pt。8pt 等于
2.8222mm，所以一个像素最多只能占 2.8222 / 12 mm。画布上限就是可打印尺寸
除以这个比例：再宽一点，字就跌破 8pt，缩放会替你决定图能不能读。

    横版 A4  可打印 233.6 x 159.2 mm  ->  993 x 676 px
    竖版 A4  可打印 146.6 x 246.2 mm  ->  623 x 1046 px

高度这一维和宽度同样是硬的。横向的图超高，打印时会被整体缩小去迁就纸张，
字号跟着一起掉到 8pt 以下，跟超宽的后果完全一样。

超出预算怎么办
--------------
不分页。横轴分页等于把一根时间轴切在两张纸上，读者要把纸并排摊开才看得懂，
而分页的前提本来就是读者不会并排看两页。横向装不下就改用纵向形态，纵向长图
才分页。这一条是作者定的，不要重新讨论。
"""

# ---- 输入：三个量出来的事实 ---------------------------------------------
A4_SHORT_MM, A4_LONG_MM = 210.0, 297.0
# 页边距改小，白边由图自己带 —— 这是作者定的做法，理由是：图本身已经有白边，再叠上
# Word 的页边距就是在留白之上再留白。所以让 Word 只留 1.27 厘米，图自己带够白边，
# 交付图的长宽比因此贴着可打印区，Word 会按宽度铺满，两侧不再出现空档。
# 这一条是作者把图粘进 Word 量出来的：一张 991×724 的图长宽比 1.369，比纸更方，
# Word 只能按高度缩放，于是宽度只剩 21.79 厘米，左右各空 0.78 厘米。
MARGIN_TB_MM = 12.7         # Word 页边距 上/下 1.27 厘米
MARGIN_LR_MM = 12.7         # Word 页边距 左/右 1.27 厘米
# 图自带的白边。10 / 7 毫米这一档是量出来选的：它恰好让「轴上下各两条泳道」从画不出
# 变成画得出（内容高度 676 → 726，而两层需要 724），而白边仍有一厘米上下，粘进文书
# 不显局促。用户嫌白边多可以自己截掉，截掉比装不下容易。
PAD_LR_MM = 10.0
PAD_TB_MM = 7.0
MIN_PRINT_PT = 8.0           # 印刷字号下限
MIN_FONT_PX = 12             # 图上最小字号，common.FS["note"]

# ---- 由上面算出来的一切 -------------------------------------------------
MM_PER_PT = 25.4 / 72.0
#: 每像素最多可以占多少毫米。再大，12px 的字就跌破 8pt。
MM_PER_PX = MIN_PRINT_PT * MM_PER_PT / MIN_FONT_PX


def _budget(page_w_mm, page_h_mm):
    """可打印区域 -> 画布像素上限。向下取整，宁可少一像素。"""
    return (int((page_w_mm - 2 * MARGIN_LR_MM) / MM_PER_PX),
            int((page_h_mm - 2 * MARGIN_TB_MM) / MM_PER_PX))


#: 交付图的整幅尺寸（含图自带的白边），长宽比贴着可打印区。
SHEET_LAND_W, SHEET_LAND_H = _budget(A4_LONG_MM, A4_SHORT_MM)
SHEET_PORT_W, SHEET_PORT_H = _budget(A4_SHORT_MM, A4_LONG_MM)

#: 白边换算成像素。
PAD_LR = int(PAD_LR_MM / MM_PER_PX)
PAD_TB = int(PAD_TB_MM / MM_PER_PX)

#: 渲染器实际可用的内容画幅 = 整幅减去白边。渲染器只认这两个数，白边由 frame() 加。
LAND_W, LAND_H = SHEET_LAND_W - 2 * PAD_LR, SHEET_LAND_H - 2 * PAD_TB
PORT_W, PORT_H = SHEET_PORT_W - 2 * PAD_LR, SHEET_PORT_H - 2 * PAD_TB


def frame(svg, landscape=True):
    """把渲出来的内容裱进整幅：四周加白边，画布长到整幅尺寸。

    渲染器一律只画内容，不管白边。白边在最后一步统一加，因为它是纸的事、不是排布的事；
    让每个渲染器各自留边，那个数就会被抄成五份然后开始漂移。
    """
    import re as _re
    W = SHEET_LAND_W if landscape else SHEET_PORT_W
    m = _re.search(r'<svg[^>]*?width="([\d.]+)"[^>]*?height="([\d.]+)"', svg)
    if not m:
        return svg
    w0, h0 = float(m.group(1)), float(m.group(2))
    W2, H2 = W, h0 + 2 * PAD_TB
    body = svg.split(">", 1)[1].rsplit("</svg>", 1)[0]
    head = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W2:.0f}" '
            f'height="{H2:.0f}" viewBox="0 0 {W2:.0f} {H2:.0f}">')
    dx = (W2 - w0) / 2
    return (f"{head}\n<rect width=\"{W2:.0f}\" height=\"{H2:.0f}\" fill=\"#FFFFFF\"/>\n"
            f'<g transform="translate({dx:.1f},{PAD_TB})">{body}</g>\n</svg>')

# ---- 清晰度：与尺寸是两件事 ---------------------------------------------
# 画布上限管的是「字够不够大」：993px 宽时最小的字正好 8pt。它管不到「印出来清不清
# 楚」。993px 铺满 233.6mm 的可打印宽度，等于 108dpi；照 v1 的默认放大两倍也只有
# 216dpi。印刷要 300dpi，所以按原尺寸或两倍导出的 PNG 贴进文书打印是发虚的 —— 而
# 律师往 Word 里贴的通常正是 PNG。SVG 是矢量，不在此列。
PRINT_DPI = 300


def raster_scale(canvas_px=None, page_mm=None):
    """PNG 至少要放大几倍，才能在纸上达到 PRINT_DPI。

    默认按横版 A4 的最宽情形算，所以一个倍数对所有图都够用；传参可以按具体画布算。
    向上取整：2.78 倍不是 2 倍，取小了就是发虚。
    """
    import math
    w = canvas_px or LAND_W
    mm = page_mm or (A4_LONG_MM - 2 * MARGIN_LR_MM)
    return math.ceil(PRINT_DPI * (mm / 25.4) / w)


def raster_dpi(canvas_px=None, page_mm=None):
    """按 raster_scale 导出后，实际落在纸上的 dpi。"""
    w = canvas_px or LAND_W
    mm = page_mm or (A4_LONG_MM - 2 * MARGIN_LR_MM)
    return w * raster_scale(canvas_px, page_mm) / (mm / 25.4)


def fits_landscape(w, h):
    return w <= LAND_W and h <= LAND_H


def sheet_ok(w, h, landscape=True):
    """裱好的整幅是否符合交付要求：宽度等于整幅宽，长宽比不比纸更「方」。

    守卫里曾经拿**内容画幅**的预算去量**裱好的整幅**，于是每张图都报「超宽 75px」——
    量错了对象。整幅的宽度是固定的（横版 1154、竖版 784），该检查的是它等不等于那个数；
    高度不设上限（纵向长图本来就长，靠分页解决），但长宽比不许比可打印区更方，否则
    Word 会按高度缩放、两侧留出空档。
    """
    W = SHEET_LAND_W if landscape else SHEET_PORT_W
    pw = (A4_LONG_MM if landscape else A4_SHORT_MM) - 2 * MARGIN_LR_MM
    ph = (A4_SHORT_MM if landscape else A4_LONG_MM) - 2 * MARGIN_TB_MM
    if abs(w - W) > 1:
        return f"整幅宽 {w:.0f}px，应为 {W}px"
    if landscape and h > 1 and w / h < pw / ph - 0.02:
        return (f"长宽比 {w / h:.3f} 比可打印区 {pw / ph:.3f} 更方，"
                f"Word 会按高度缩放并在两侧留出空档")
    return ""


def over_budget(w, h, landscape=True):
    """返回一句能直接给用户看的话，没超就返回空串。

    说清超了多少、以及为什么这是硬的。只说「装不下」会让人以为再挤一挤就行。
    """
    W, H = (LAND_W, LAND_H) if landscape else (PORT_W, PORT_H)
    which = "横版" if landscape else "竖版"
    over = []
    if w > W:
        over.append(f"宽 {w:.0f}px 超出 {w - W:.0f}px")
    if h > H:
        over.append(f"高 {h:.0f}px 超出 {h - H:.0f}px")
    if not over:
        return ""
    return (f"A4 {which}（页边距 上下 {MARGIN_TB_MM / 10:.2f} 厘米、"
            f"左右 {MARGIN_LR_MM / 10:.2f} 厘米）的画布预算是 {W}x{H}px，"
            f"本图 " + "，".join(over) + "。"
            f"再宽或再高，打印时整张图会被缩小，图上最小的字将跌破 8pt。")


if __name__ == "__main__":
    print(f"每像素上限 {MM_PER_PX:.5f} mm（12px 字印出来正好 8pt）")
    print(f"交付整幅 横版 {SHEET_LAND_W} x {SHEET_LAND_H} px（长宽比 "
          f"{SHEET_LAND_W/SHEET_LAND_H:.4f}，可打印区 "
          f"{(A4_LONG_MM-2*MARGIN_LR_MM)/(A4_SHORT_MM-2*MARGIN_TB_MM):.4f}）")
    print(f"图自带白边 左右 {PAD_LR}px / 上下 {PAD_TB}px")
    print(f"内容画幅 横版 {LAND_W} x {LAND_H} px   竖版 {PORT_W} x {PORT_H} px")
    print(f"PNG 导出放大 {raster_scale()} 倍 → 纸上 {raster_dpi():.0f} dpi"
          f"（印刷下限 {PRINT_DPI} dpi）")


# ---- 标题块 -------------------------------------------------------------
# 标题不是「画在图上方的一行字」，而是一个**预留的块**：它有确定的位置、确定的可用宽度、
# 确定的最多行数，以及由此反推出来的字数上限。分页产物的每一页也要留出同一个块（接上页
# 那几张同样带标题），所以这套数不能写在某个渲染器里，要在这里定一次。
#
# 三条规矩（作者定的）：
#   一、标题字号可以调小，但有下限；
#   二、可以折行，但**最多折一次**，也就是最多两行；
#   三、块的高度由实际行数算出，不写死。
#
# 下限取 FS["node_title"]（17px）：再小就比正文（13px）只大四个像素，读起来不像标题了。
# 印在纸上是 11.3pt，也远高于 8pt 的清晰度下限，所以约束在观感不在清晰度。
TITLE_MAX_LINES = 2
TITLE_PAD_TOP = 22          # 块顶到第一行基线
TITLE_PAD_BOTTOM = 26       # 末行基线到块底（也是标题与图形之间的呼吸）
TITLE_LH_RATIO = 1.27       # 标题行距 ÷ 标题字号


def title_ladder():
    """标题可用的字号阶梯，从大到小。全部取自字阶，不另造字号。"""
    from common import FS
    big, floor = FS["doc_title"], FS["node_title"]
    steps = sorted({FS["doc_title"], FS["num"], FS["axis_year"] + 6,
                    FS["node_title"]}, reverse=True)
    return [s for s in steps if floor <= s <= big]


def title_fit(text, avail_w, text_w, wrap_fn):      # [C5] 图名块
    """标题在给定宽度里怎么放。

    返回 (字号, 行列表, 块高)。从最大字号往下试，取**第一个能在两行内放下**的字号。
    放不下最小字号也返回它并把行数截到两行 —— 但那属于「标题超出容量」，由校验器在
    出图之前拦住并要求改短，渲染器不做截断（图上不许出现省略号）。
    """
    ladder = title_ladder()
    for fs in ladder:
        lines = wrap_fn(text, fs, avail_w) or [text]
        if len(lines) <= TITLE_MAX_LINES:
            return fs, lines, title_zone_h(fs, len(lines))
    fs = ladder[-1]
    lines = (wrap_fn(text, fs, avail_w) or [text])[:TITLE_MAX_LINES]
    return fs, lines, title_zone_h(fs, len(lines))


def title_zone_h(fs, nlines):
    """标题块的高度：按实际行数算，不写死。"""
    return round(TITLE_PAD_TOP + fs + (nlines - 1) * fs * TITLE_LH_RATIO
                 + TITLE_PAD_BOTTOM)


def title_capacity(avail_w, text_w):
    """每一档字号下，标题最多几个汉字（两行）。前端据此把图名写到位。"""
    out = []
    for fs in title_ladder():
        n = 0
        while text_w("字" * (n + 1), fs) <= avail_w:
            n += 1
        out.append((fs, n, n * TITLE_MAX_LINES))
    return out
