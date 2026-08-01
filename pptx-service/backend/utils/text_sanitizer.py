"""
[checkba] Markdown 治理工具：LLM/OCR 输出落入 PPTX 前的最后一道防线。

设计与 docx 侧「写入去 markdown 化（标准格式落字）」同一哲学（主仓 PR#230）：
不是简单剥离标记，而是把行内 markdown 语义转成真实格式（粗体/斜体/删除线/
高亮/等宽），把行首列表标记转成真实项目符号/编号语义，剩余符号才剥离。

纯标准库实现，无第三方依赖，可被 pptx_builder 与 pptx_format_service 复用。
"""
import re
from dataclasses import dataclass, field
from typing import List, Optional


@dataclass
class InlineRun:
    """一段带样式语义的行内文本"""
    text: str
    bold: bool = False
    italic: bool = False
    strike: bool = False
    code: bool = False       # 行内代码 -> 等宽字体
    highlight: bool = False  # ==xx== 扩展语法 -> 高亮


@dataclass
class LineSpec:
    """一行文本的块级语义"""
    runs: List[InlineRun] = field(default_factory=list)
    bullet: Optional[str] = None    # None | 'bullet' | 'number'
    number: Optional[int] = None    # bullet == 'number' 时的原始序号
    heading_level: Optional[int] = None  # '# ' 前缀的级别（1-6），无则 None

    @property
    def text(self) -> str:
        return ''.join(r.text for r in self.runs)


# 行内语法。顺序即优先级：先长定界符再短定界符，避免 ** 被 * 误吃。
_INLINE_RE = re.compile(
    r'\*\*\*(?P<bi>.+?)\*\*\*'          # ***粗斜***
    r'|\*\*(?P<b>.+?)\*\*'              # **粗体**
    r'|__(?P<b2>.+?)__'                 # __粗体__
    r'|~~(?P<s>.+?)~~'                  # ~~删除线~~
    r'|==(?P<hl>.+?)=='                 # ==高亮==（扩展语法）
    r'|`(?P<c>[^`\n]+?)`'               # `行内代码`
    r'|\*(?P<i>[^*\n]+?)\*'             # *斜体*
    r'|!?\[(?P<lk>[^\]\n]*)\]\([^)\n]*\)'  # 链接/图片 -> 只留文字
)

# 行首列表标记
_BULLET_RE = re.compile(r'^\s*[-*+•·➤▪]\s+')
# 中文标点（、） （3））后不要求空格；ASCII '1.'/'1)' 要求空格，避免误吃 3.14 之类
_NUMBER_RE = re.compile(r'^\s*(?:(\d{1,3})[、）]|[（(](\d{1,3})[）)]|(\d{1,3})[.)]\s)\s*')
_HEADING_RE = re.compile(r'^\s*(#{1,6})\s+')

# 表格行 / 分隔线等纯结构行
_HR_RE = re.compile(r'^\s*(?:[-*_]\s*){3,}$')
_TABLE_SEP_RE = re.compile(r'^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$')


def parse_inline(text: str,
                 bold: bool = False,
                 italic: bool = False,
                 strike: bool = False,
                 code: bool = False,
                 highlight: bool = False) -> List[InlineRun]:
    """把一行文本解析为带样式语义的 run 列表（支持一层嵌套递归）。"""
    runs: List[InlineRun] = []
    pos = 0
    for m in _INLINE_RE.finditer(text):
        if m.start() > pos:
            runs.append(InlineRun(text[pos:m.start()], bold, italic, strike, code, highlight))
        if m.group('bi') is not None:
            runs.extend(parse_inline(m.group('bi'), True, True, strike, code, highlight))
        elif m.group('b') is not None:
            runs.extend(parse_inline(m.group('b'), True, italic, strike, code, highlight))
        elif m.group('b2') is not None:
            runs.extend(parse_inline(m.group('b2'), True, italic, strike, code, highlight))
        elif m.group('s') is not None:
            runs.extend(parse_inline(m.group('s'), bold, italic, True, code, highlight))
        elif m.group('hl') is not None:
            runs.extend(parse_inline(m.group('hl'), bold, italic, strike, code, True))
        elif m.group('c') is not None:
            # 代码片段内部不再递归解析
            runs.append(InlineRun(m.group('c'), bold, italic, strike, True, highlight))
        elif m.group('i') is not None:
            runs.extend(parse_inline(m.group('i'), bold, True, strike, code, highlight))
        elif m.group('lk') is not None:
            runs.extend(parse_inline(m.group('lk'), bold, italic, strike, code, highlight))
        pos = m.end()
    if pos < len(text):
        runs.append(InlineRun(text[pos:], bold, italic, strike, code, highlight))
    return [r for r in runs if r.text]


def parse_lines(text: str) -> List[LineSpec]:
    """
    把多行文本解析为 LineSpec 列表：
    - 行首 '- ' / '* ' / '· ' 等 -> bullet 语义（前缀剥离）
    - 行首 '1. ' / '1）' 等 -> number 语义（前缀剥离）
    - 行首 '# ' -> heading 语义（前缀剥离）
    - 纯分隔线/表格分隔行 -> 丢弃
    - 行内 markdown -> InlineRun 样式
    """
    specs: List[LineSpec] = []
    for raw_line in text.split('\n'):
        if _HR_RE.match(raw_line) or _TABLE_SEP_RE.match(raw_line):
            continue
        line = raw_line
        bullet = None
        number = None
        heading_level = None

        hm = _HEADING_RE.match(line)
        if hm:
            heading_level = len(hm.group(1))
            line = line[hm.end():]
        else:
            nm = _NUMBER_RE.match(line)
            if nm:
                bullet = 'number'
                number = int(nm.group(1) or nm.group(2) or nm.group(3))
                line = line[nm.end():]
            else:
                bm = _BULLET_RE.match(line)
                if bm:
                    bullet = 'bullet'
                    line = line[bm.end():]

        # markdown 表格行：去掉管道符，保留单元格文字
        if line.count('|') >= 2:
            stripped = line.strip()
            if stripped.startswith('|') or stripped.endswith('|'):
                line = '  '.join(c.strip() for c in stripped.strip('|').split('|') if c.strip())

        specs.append(LineSpec(runs=parse_inline(line), bullet=bullet,
                              number=number, heading_level=heading_level))
    return specs


def strip_markdown(text: str) -> str:
    """
    纯文本消毒：剥离所有 markdown 标记，只留内容文字。
    用于表格单元格等无 run 级格式的落字场景。
    """
    if not text:
        return text
    lines = []
    for spec in parse_lines(text):
        lines.append(spec.text)
    return '\n'.join(lines)


def has_markdown(text: str) -> bool:
    """快速判断文本是否含 markdown 标记（用于日志/测试断言）。"""
    if not text:
        return False
    if _INLINE_RE.search(text):
        return True
    return any(
        _HEADING_RE.match(line) or _BULLET_RE.match(line) or _NUMBER_RE.match(line)
        for line in text.split('\n')
    )
