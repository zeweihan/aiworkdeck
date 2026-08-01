"""
[checkba] text_sanitizer 单元测试：markdown 去除与真格式转换
"""
import pytest

from utils.text_sanitizer import parse_inline, parse_lines, strip_markdown, has_markdown


class TestParseInline:
    def test_plain_text_untouched(self):
        runs = parse_inline("普通文本 plain text")
        assert len(runs) == 1
        assert runs[0].text == "普通文本 plain text"
        assert not runs[0].bold

    def test_bold(self):
        runs = parse_inline("前**加粗**后")
        assert [r.text for r in runs] == ["前", "加粗", "后"]
        assert [r.bold for r in runs] == [False, True, False]

    def test_italic_strike_code_highlight(self):
        runs = parse_inline("*斜* ~~删~~ `码` ==亮==")
        styles = {r.text: r for r in runs}
        assert styles["斜"].italic
        assert styles["删"].strike
        assert styles["码"].code
        assert styles["亮"].highlight

    def test_bold_italic_nested(self):
        runs = parse_inline("***粗斜***")
        assert runs[0].bold and runs[0].italic

    def test_link_keeps_text_only(self):
        runs = parse_inline("见[条款原文](https://example.com)。")
        assert "".join(r.text for r in runs) == "见条款原文。"

    def test_no_markdown_symbols_survive(self):
        text = "# 标题 **粗** *斜* `码` ~~删~~ [链](http://x) ==亮=="
        joined = "".join(r.text for r in parse_inline(text))
        for symbol in ("**", "~~", "`", "==", "]("):
            assert symbol not in joined


class TestParseLines:
    def test_bullet_lines(self):
        specs = parse_lines("- 第一\n* 第二\n· 第三")
        assert all(s.bullet == "bullet" for s in specs)
        assert [s.text for s in specs] == ["第一", "第二", "第三"]

    def test_numbered_lines(self):
        specs = parse_lines("1. 甲\n2）乙\n（3）丙\n4、丁")
        assert all(s.bullet == "number" for s in specs)
        assert [s.number for s in specs] == [1, 2, 3, 4]

    def test_heading_stripped_and_marked(self):
        specs = parse_lines("## 二、核心条款")
        assert specs[0].heading_level == 2
        assert specs[0].text == "二、核心条款"

    def test_horizontal_rule_dropped(self):
        specs = parse_lines("上文\n---\n下文")
        assert [s.text for s in specs] == ["上文", "下文"]

    def test_table_pipes_cleaned(self):
        specs = parse_lines("| 股东 | 比例 |\n| --- | --- |\n| 甲 | 51% |")
        texts = [s.text for s in specs]
        assert texts == ["股东  比例", "甲  51%"]

    def test_plain_multiline(self):
        specs = parse_lines("第一行\n第二行")
        assert [s.bullet for s in specs] == [None, None]


class TestStripMarkdown:
    def test_strip_everything(self):
        dirty = "# 标题\n**结论**：*不构成*重大重组\n- 要点`一`\n1. 编号~~废弃~~"
        clean = strip_markdown(dirty)
        assert not has_markdown(clean)
        assert "结论" in clean and "不构成" in clean and "要点一" in clean

    def test_empty_and_none_safe(self):
        assert strip_markdown("") == ""
        assert strip_markdown(None) is None

    def test_has_markdown_detection(self):
        assert has_markdown("**x**")
        assert has_markdown("- 列表项")
        assert not has_markdown("普通文字，包含 3*4=12 吗")  # 乘号不该误报

    def test_snake_case_not_mangled(self):
        # 下划线变量名不应被当作斜体
        assert strip_markdown("file_name 与 wps_file_id") == "file_name 与 wps_file_id"
