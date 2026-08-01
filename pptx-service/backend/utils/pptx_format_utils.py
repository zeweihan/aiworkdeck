"""
[checkba] python-pptx run/段落级格式读写工具。

python-pptx 原生 API 覆盖不到的部分（东亚字体 <a:ea>、删除线 strike、
高亮 <a:highlight>、项目符号 buChar/buAutoNum）在此用 oxml 层补齐。
pptx_builder（生成落字）与 pptx_format_service（存量文件格式操作）共用。

默认字体与 docx 侧律所 HOUSE 规范协调（DocxStyleHelper / office_thread.js）：
西文 Arial、中文楷体_GB2312。可通过环境变量覆盖。
"""
import os
import re
from typing import Any, Dict, Optional

from pptx.util import Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.oxml.ns import qn

# 与 backend/DocxStyleHelper.java 的 HOUSE_FONT_* 同源
HOUSE_LATIN_FONT = os.getenv('PPTX_HOUSE_LATIN_FONT', 'Arial')
HOUSE_EA_FONT = os.getenv('PPTX_HOUSE_EA_FONT', '楷体_GB2312')
CODE_FONT = os.getenv('PPTX_CODE_FONT', 'Consolas')

_ALIGN_MAP = {
    'left': PP_ALIGN.LEFT,
    'center': PP_ALIGN.CENTER,
    'right': PP_ALIGN.RIGHT,
    'justify': PP_ALIGN.JUSTIFY,
}
_ALIGN_NAME = {v: k for k, v in _ALIGN_MAP.items()}

_HEX_COLOR_RE = re.compile(r'^#?([0-9a-fA-F]{6})$')


def _parse_color(value: str) -> Optional[RGBColor]:
    m = _HEX_COLOR_RE.match(value.strip()) if isinstance(value, str) else None
    return RGBColor.from_string(m.group(1).upper()) if m else None


# ==================== run 级：oxml 补齐 ====================

def set_ea_font(run, ea_name: str):
    """设置 run 的东亚字体 <a:ea typeface>（python-pptx 无原生 API）。"""
    rPr = run._r.get_or_add_rPr()
    ea = rPr.find(qn('a:ea'))
    if ea is None:
        ea = rPr.makeelement(qn('a:ea'), {})
        rPr.insert_element_before(
            ea, 'a:cs', 'a:sym', 'a:hlinkClick',
            'a:hlinkMouseOver', 'a:rtl', 'a:extLst')
    ea.set('typeface', ea_name)


def get_ea_font(run) -> Optional[str]:
    rPr = run._r.find(qn('a:rPr'))
    if rPr is None:
        return None
    ea = rPr.find(qn('a:ea'))
    return ea.get('typeface') if ea is not None else None


def set_strike(run, strike: bool):
    """删除线：rPr 的 strike 属性。"""
    rPr = run._r.get_or_add_rPr()
    if strike:
        rPr.set('strike', 'sngStrike')
    elif rPr.get('strike') is not None:
        del rPr.attrib['strike']


def get_strike(run) -> bool:
    rPr = run._r.find(qn('a:rPr'))
    return rPr is not None and rPr.get('strike') in ('sngStrike', 'dblStrike')


def set_highlight(run, color_hex: Optional[str]):
    """高亮 <a:highlight><a:srgbClr/></a:highlight>；color_hex=None 表示清除。"""
    rPr = run._r.get_or_add_rPr()
    old = rPr.find(qn('a:highlight'))
    if old is not None:
        rPr.remove(old)
    if not color_hex:
        return
    color = _parse_color(color_hex)
    if color is None:
        return
    hl = rPr.makeelement(qn('a:highlight'), {})
    clr = rPr.makeelement(qn('a:srgbClr'), {'val': str(color)})
    hl.append(clr)
    rPr.insert_element_before(
        hl, 'a:uLnTx', 'a:uLn', 'a:uFillTx', 'a:uFill',
        'a:latin', 'a:ea', 'a:cs', 'a:sym',
        'a:hlinkClick', 'a:hlinkMouseOver', 'a:rtl', 'a:extLst')


def get_highlight(run) -> Optional[str]:
    rPr = run._r.find(qn('a:rPr'))
    if rPr is None:
        return None
    hl = rPr.find(qn('a:highlight'))
    if hl is None:
        return None
    clr = hl.find(qn('a:srgbClr'))
    return f"#{clr.get('val')}" if clr is not None and clr.get('val') else None


# ==================== 段落级：项目符号 ====================

def set_bullet(paragraph, bullet: Optional[str], number_start: int = 1):
    """
    设置段落项目符号语义。
    bullet: None/'none' 清除；'bullet' 圆点；'number' 阿拉伯数字编号。
    """
    pPr = paragraph._p.get_or_add_pPr()
    for tag in ('a:buNone', 'a:buChar', 'a:buAutoNum'):
        el = pPr.find(qn(tag))
        if el is not None:
            pPr.remove(el)
    if bullet in (None, 'none'):
        # 文本框默认即无符号；显式写 buNone 保险
        el = pPr.makeelement(qn('a:buNone'), {})
    elif bullet == 'bullet':
        el = pPr.makeelement(qn('a:buChar'), {'char': '•'})
    elif bullet == 'number':
        attrs = {'type': 'arabicPeriod'}
        if number_start and number_start != 1:
            attrs['startAt'] = str(number_start)
        el = pPr.makeelement(qn('a:buAutoNum'), attrs)
    else:
        return
    pPr.insert_element_before(el, 'a:tabLst', 'a:defRPr', 'a:extLst')


def get_bullet(paragraph) -> Optional[str]:
    pPr = paragraph._p.find(qn('a:pPr'))
    if pPr is None:
        return None
    if pPr.find(qn('a:buChar')) is not None:
        return 'bullet'
    if pPr.find(qn('a:buAutoNum')) is not None:
        return 'number'
    return None


# ==================== 规格化读写 ====================

def apply_run_format(run, spec: Dict[str, Any]):
    """
    将格式规格应用到 run。支持键：
    bold/italic/underline/strike: bool
    highlight: '#RRGGBB' | None（清除）
    color: '#RRGGBB'
    font_name: 西文字体名；ea_font: 中文字体名；size_pt: 字号磅值
    code: bool（等宽字体快捷键）
    """
    f = run.font
    if 'bold' in spec:
        f.bold = bool(spec['bold'])
    if 'italic' in spec:
        f.italic = bool(spec['italic'])
    if 'underline' in spec:
        f.underline = bool(spec['underline'])
    if 'strike' in spec:
        set_strike(run, bool(spec['strike']))
    if 'highlight' in spec:
        set_highlight(run, spec['highlight'])
    if 'color' in spec and spec['color']:
        color = _parse_color(spec['color'])
        if color is not None:
            f.color.rgb = color
    if 'size_pt' in spec and spec['size_pt']:
        f.size = Pt(float(spec['size_pt']))
    if spec.get('code'):
        f.name = CODE_FONT
        set_ea_font(run, CODE_FONT)
    else:
        if 'font_name' in spec and spec['font_name']:
            f.name = spec['font_name']
        if 'ea_font' in spec and spec['ea_font']:
            set_ea_font(run, spec['ea_font'])


def read_run_format(run) -> Dict[str, Any]:
    f = run.font
    try:
        # 主题色等非 RGB 类型访问 .rgb 会抛异常
        color = f"#{f.color.rgb}" if f.color.type is not None else None
    except (AttributeError, ValueError):
        color = None
    return {
        'text': run.text,
        'font_name': f.name,
        'ea_font': get_ea_font(run),
        'size_pt': (f.size.pt if f.size is not None else None),
        'bold': f.bold,
        'italic': f.italic,
        'underline': f.underline,
        'strike': get_strike(run),
        'highlight': get_highlight(run),
        'color': color,
    }


def apply_paragraph_format(paragraph, spec: Dict[str, Any]):
    """
    段落级格式。支持键：
    align: left/center/right/justify
    line_spacing: 倍数（如 1.5）或磅值字符串 '28pt'
    space_before_pt / space_after_pt: 段前后距磅值
    bullet: 'none' | 'bullet' | 'number'；number_start: 起始编号
    """
    if 'align' in spec and spec['align'] in _ALIGN_MAP:
        paragraph.alignment = _ALIGN_MAP[spec['align']]
    if 'line_spacing' in spec and spec['line_spacing'] is not None:
        ls = spec['line_spacing']
        if isinstance(ls, str) and ls.endswith('pt'):
            paragraph.line_spacing = Pt(float(ls[:-2]))
        else:
            paragraph.line_spacing = float(ls)
    if 'space_before_pt' in spec and spec['space_before_pt'] is not None:
        paragraph.space_before = Pt(float(spec['space_before_pt']))
    if 'space_after_pt' in spec and spec['space_after_pt'] is not None:
        paragraph.space_after = Pt(float(spec['space_after_pt']))
    if 'bullet' in spec:
        set_bullet(paragraph, spec['bullet'], spec.get('number_start', 1))


def read_paragraph_format(paragraph) -> Dict[str, Any]:
    ls = paragraph.line_spacing
    if hasattr(ls, 'pt'):
        ls = f"{ls.pt}pt"
    return {
        'align': _ALIGN_NAME.get(paragraph.alignment),
        'line_spacing': ls,
        'space_before_pt': (paragraph.space_before.pt
                            if paragraph.space_before is not None else None),
        'space_after_pt': (paragraph.space_after.pt
                           if paragraph.space_after is not None else None),
        'bullet': get_bullet(paragraph),
    }
