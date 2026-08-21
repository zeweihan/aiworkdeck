# 尽调报告「学习团队模板」格式颗粒度盘点与设计（dev-board#100）

日期：2026-08-21。性质：只读盘点 + 设计建议，未改产品代码。
前置文档：`.claude/agents/ai-doc-bridge.md`（HOUSE、格式原语、doc_get_formatting）、`.claude/agents/doc-editor.md`、
`docs/superpowers/specs/2026-08-21-due-diligence-lite-evaluation.md`（三期方案，本文是其 §1.1「模板画像」一项的展开）。

## 0. 结论先行

1. **后端写 docx 的路径是 docx4j，不是 POI。** `write_docx`（`FileTools.java:392`）与 `AiDocxExportService` 都走 flexmark-docx-converter → `org.docx4j.openpackaging.packages.WordprocessingMLPackage`，`DocxStyleHelper.applyStandardFormat()` 改的是 docx4j 的 `Styles/PPr/RPr/Tbl/Tc` 对象。POI 5.2.5 在 classpath 上，但 XWPF 只在 `SensitiveService`（读段落/页眉页脚文本做脱敏）与 `MeetingRecordingService`（写纪要）两处用到，**全仓没有一行读 `styles.xml`/段落属性/表格属性/`numbering.xml` 的代码**。读模板与写报告用同一套 docx4j 对象模型最省事，且 docx4j 自带 `PropertyResolver`（样式继承链 + docDefaults 的有效值解析），POI 没有对应物。
2. **现有读取面只有两个口，都不够**：`extract_file_text` 是 Tika 纯文本（零格式，且 `capToolText` 会截断）；`doc_get_formatting` 只读光标所在处的字符/段落属性，要求模板已在 LOWA 打开，拿不到页面/页眉页脚/编号定义/表格边框/列宽。
3. **绝大部分颗粒度从 XML 直读（docx4j）比 LOWA 更准**，因为 XML 保留了「声明单位」：`w:ind/@firstLineChars`（2 字符）、`w:spacing/@lineRule`（auto 倍数 vs atLeast/exact 磅）、`w:rFonts/@eastAsia` 与 `@asciiTheme`、`w:numFmt=chineseCounting` + `lvlText="%1、"`。LOWA 导入后把这些全折算成 1/100 mm 与枚举，「2 字符」变成 8.47 mm，「一、」变成 NumberingType 常量，倒推不回来。
4. **必须走 LOWA 的只有四类**：(a) 主题字体/缺字体替换后的**实际渲染字体名**；(b) 表格样式条件格式（`tblStylePr` firstRow/band1Horz 斑马纹）叠加直接格式后的**单元格有效值**（docx4j PropertyResolver 不解析表格样式）；(c) 老格式 `.doc/.wps` 模板（POI HWPF 残缺，docx4j 不支持）；(d) **写完后的核验回读**（同一引擎读写闭环）。
5. **写报告两条路都有缺口**，最大的一条是「中文字体」：`doc_format_selection(fontName)` 只设 `CharFontName`（西文），`CharFontNameAsian` 只有 `apply_house_style` 的 HOUSE 常量能设；编辑器路径也改不了样式定义本身、设不了页面边距/页码域/目录/自定义编号文本/单元格底纹/分边框线。docx4j 路径能力上无硬缺口，只是 flexmark 生成的列表与标题要后处理接管。
6. 建议新增一个**后端只读工具 `docx_inspect_template`**（docx4j）产出 `styleProfile` JSON，并让三处写端（`DocxStyleHelper`、worker 的 `HOUSE` 系列、插件端 `HOUSE`）都改成「读 profile、HOUSE 退化为默认 profile」。分期见 §5。

---

## 1. 读模板：现状与覆盖矩阵

### 1.1 现有入口能拿到什么

| 入口 | 位置 | 能拿到 | 拿不到 |
|---|---|---|---|
| `extract_file_text` | `FileTools.java:259` → `DocumentTextService.parse`（Tika） | 纯文本、段落顺序、表格按 tab/换行摊平 | 一切格式；长文被 `ToolFileGuard.capToolText` 截断 |
| `doc_get_document_text` | worker `get_document_text`（`office_thread.js:2451`） | 每段 `index/text`，有 OutlineLevel 的段附 `headingLevel/style` | 字体字号行距缩进；表格只给文本 |
| `doc_get_formatting` | `DocumentEditTools.java:976` → worker `get_formatting`（`:2889`） | **光标处**：CharFontName/CharFontNameAsian/CharHeight/粗斜下删/颜色/高亮；ParaStyleName/对齐/行距(模式+值)/段前后/首行·左·右缩进(磅)/OutlineLevel/是否编号；所在表名与行列数 | 只有一处；缩进已折算成磅（丢了「字符」单位）；无边框/列宽/单元格对齐/页面/页眉页脚/编号格式 |
| `list_styles`（宿主原语，非 AI 工具） | worker `:2075` | 样式名 + 显示名 + inUse | 样式的任何属性 |
| `get_ui_state`（宿主） | worker `:2028` 一带 | 列表种类 bullet/number | 编号文本、层级 |

结论：今天没有任何一条路能把模板「整篇」的格式读成结构化数据。

### 1.2 后端库现状

- docx4j 11.3.2（`~/.m2/repository/org/docx4j/docx4j-core/11.3.2`，随 `flexmark-docx-converter 0.64.0` 传入，pom 未直接声明）。已用类：`WordprocessingMLPackage`、`StyleDefinitionsPart`、`Styles/Style/PPr/RPr/RFonts/Tbl/Tr/Tc/TblPr/TblBorders/TcPr`。
- POI 5.2.5 `poi-ooxml`（pom 显式）。`SensitiveService.java:76-95` 遍历 `getParagraphs()/getHeaderList()/getFooterList()` 只取文本。
- **没有**：`XWPFStyles`、`XWPFNumbering`、`PropertyResolver`、`ThemePart`、任何 `CTTblBorders/CTTcPr/CTSectPr` 读取。

### 1.3 目标颗粒度 × 读取手段覆盖矩阵

「XML」= docx4j（或 POI XWPF，两者都能，推荐 docx4j 以与写端同模型）；「LOWA」= 打开模板后走 UNO 读。

| 颗粒度 | XML 能读到 | XML 读不准/读不到 | LOWA 能补什么 | 推荐 |
|---|---|---|---|---|
| 标题有几级、每级样式 | `styles.xml` 中 `w:outlineLvl`、`Heading1-9` 及 basedOn 链；正文中各级标题实例的 `pPr/pStyle` | 模板用自定义样式名（如「报告一级标题」）且无 outlineLvl 时要靠实例启发式（编号前缀「一、」「（一）」） | OutlineLevel 同样缺；无优势 | XML + 启发式 |
| 标题字体（中/西文）字号加粗 | `rFonts/@ascii @hAnsi @eastAsia @cs` 与 `@asciiTheme/@eastAsiaTheme`；`sz/szCs`（半磅）；`b/bCs`；`color` | 主题字体只得到 `minorHAnsi/majorEastAsia` 这种槽位名，要再查 `theme1.xml` 的 `a:minorFont/a:latin`、`a:ea`、`a:font script="Hans"`；docx4j `ThemePart`/`RunFontSelector` 有解析逻辑但 API 面偏内部，需核实 | `CharFontName/CharFontNameAsian` 直接是**渲染用的实际名**（缺字体时是替换后的名） | XML 取「声明名」（模板意图）；LOWA 取「渲染名」作核验 |
| 标题编号样式 | `numbering.xml`：`abstractNum/lvl` 的 `numFmt`（decimal/chineseCounting/chineseCountingThousand/lowerLetter…）、`lvlText`（`%1、` `（%1）` `%1.%2`）、`start`、`suff`、`lvl/pPr/ind`、`lvl/rPr`；段落 `numPr(numId, ilvl)`；`num/lvlOverride` | 无 | `NumberingRules` 只给 NumberingType 枚举 + Prefix/Suffix；读得到但不如 XML 直接 | **XML**（这是最典型的「LOWA 读不准」项） |
| 标题缩进/段前段后 | `ind/@left @firstLine @firstLineChars @hanging @hangingChars`；`spacing/@before @after @beforeLines @afterLines @line @lineRule` | 无；注意 `*Chars/*Lines` 优先级高于磅值属性 | 全部折成 1/100 mm，单位丢失 | **XML** |
| 正文字体字号行距首行缩进对齐 | 同上 + `docDefaults` + `Normal` 样式；**有效值**用 `PropertyResolver.getEffectivePPr/getEffectiveRPr` | PropertyResolver 覆盖 docDefaults→样式链→直接格式，但**不含**编号级别 pPr 与表格样式条件格式 | 有效值（含编号级缩进） | XML 为主，LOWA 核验 |
| 表格边框线型/粗细/颜色 | `tblPr/tblBorders`（val single/double/dashed…，sz 八分之一磅，color）；`tcPr/tcBorders` 单格覆盖；`tblStyle` 指向的表格样式 `tblPr/tblBorders` | 三层叠加（表格样式→表级→单元格级）docx4j 不自动合并，要自己写 | `TableBorder2`（表级）+ 单元格 `TopBorder/...`（`BorderLine2`：LineWidth、LineStyle、Color）是**合并后**的 | XML 读声明 + 自写三层合并；LOWA 可做交叉校验 |
| 表头样式 | `trPr/tblHeader`（重复表头）；首行 tc 的 `shd`、run `b`；表格样式 `tblStylePr[@type=firstRow]`；`tblLook/@firstRow` | 条件格式是否生效要看 `tblLook` 位 | 首行单元格有效属性（已叠加） | XML（声明）+ LOWA（有效） |
| 单元格对齐（水平/垂直） | 段落 `jc`；`tcPr/vAlign` | 无 | `ParaAdjust`、`VertOrient` | XML |
| 数字/文字/日期在单元格里的格式 | 只能按单元格文本分类（`DocxStyleHelper.NUMERIC_CELL` 同款正则）后分别统计 jc/字体；Word 没有「单元格数据类型」概念 | 日期识别要正则（`2024年1月1日`、`2024-01-01`） | 同样只能靠文本分类 | XML，按内容类型聚合统计 |
| 列宽 | `tblGrid/gridCol/@w`（twips）；`tcPr/tcW`（type dxa/pct/auto）；`tblPr/tblW`、`tblLayout` | `autofit` + `tcW type=auto` 时 XML 里是 0，真实宽度由 Word 排版决定 | `TableColumnSeparators`（相对位置）+ `Width` 是排版后的有效值 | XML 优先，autofit 时退 LOWA |
| 斑马纹 | 表格样式 `tblStylePr[@type=band1Horz/band2Horz]/tcPr/shd`；或逐行 `tcPr/shd/@fill` | 要识别「隔行规律」 | 单元格 `BackColor` 有效值 | XML（样式）/ LOWA（直接格式时省事） |
| 页眉页脚文字与格式 | `sectPr/headerReference` → `header1.xml` 段落 runs；`titlePg`（首页不同）；`evenAndOddHeaders` | 无 | `HeaderText/FooterText`（首节） | XML |
| 页码 | `fldSimple[@instr="PAGE"]` / `instrText` 含 `PAGE`、`NUMPAGES`、`SECTIONPAGES`；`sectPr/pgNumType/@fmt @start`；页码周围文字「第 页 共 页」 | 无 | `PageNumber` 文本域可枚举但费事 | XML |
| 页面（纸张/边距/方向/分栏/网格） | `sectPr/pgSz`、`pgMar`、`cols`、`docGrid/@type @linePitch` | 无 | 页面样式 `Width/Height/LeftMargin/...` | XML |
| 目录 | `instrText` 含 `TOC \o "1-3" \h \z \u`（取级数、是否超链接）；目录段落样式 `TOC1/TOC2` 的属性；`sdt[docPartGallery=Table of Contents]` 包裹 | 无 | `ContentIndex` 服务 `Level`、`CreateFromOutline` | XML |
| 字符样式（强调/引用用的） | `styles.xml type=character` | 无 | CharacterStyles 家族 | XML |
| 老格式 .doc/.wps | POI HWPF 只读到部分段落属性，无样式/表格边框 | —— | LOWA 能打开 .doc 并读有效属性；.wps 不支持 | **LOWA**（或提示用户先另存 docx） |

### 1.4 「必须走 LOWA」清单（归纳）

1. 缺字体替换后的实际字体名（模板声明 `楷体_GB2312` 而机器上没有时，LOWA 会报替换后的名；这决定「AI 起草后看起来像不像」）。
2. 表格样式条件格式 + 直接格式叠加后的单元格有效值（斑马纹/首行底纹/边框），docx4j 不做这一层合并。
3. `.doc` 模板。
4. 写后核验闭环：写完用同引擎 `get_formatting`/新增的 `get_table_format` 读回对比 profile，差异报给模型（与 `sheet_write_cells` 的 `formulaErrors` 自纠同一思路）。

其余全部颗粒度 XML 直读更准、更快（不需要起引擎，云端/插件会话也能用）。

---

## 2. 样式画像 `styleProfile` JSON schema

设计原则：
- **单位随声明走**：每个长度字段都是 `{value, unit}`，unit ∈ `pt | chars | lines | percent | mm | twips`；行距 `{rule: auto|atLeast|exactly, value, unit}`（auto 时 value 是倍数，如 1.5）。这样「2 字符」「段后 0.5 行」「1.5 倍」「最小值 16 磅」都能无损表达。
- **字体分槽**：`font: {eastAsia, ascii, hAnsi, cs, theme: {eastAsia, ascii}}`，`ascii/hAnsi` 通常相同，允许只写 `western`。读到主题字体时 `theme` 记槽位名、`eastAsia/western` 记解析后的实际名。
- **每个叶子可缺省**：缺省=不约束，写端用默认 profile（HOUSE）补。
- **来源与置信度**：每个块带 `source: style|instance|inferred` 与 `samples`（统计自多少实例），多份模板冲突时可裁决。

```json
{
  "schemaVersion": 1,
  "name": "某某律所尽调报告模板",
  "learnedFrom": [{"fileId": 123, "name": "XX公司法律尽职调查报告.docx", "kind": "docx"}],
  "learnedAt": "2026-08-21T10:00:00+08:00",
  "page": {
    "size": {"width": {"value": 210, "unit": "mm"}, "height": {"value": 297, "unit": "mm"}, "orientation": "portrait"},
    "margins": {"top": {"value": 2.54, "unit": "cm"}, "bottom": {"value": 2.54, "unit": "cm"}, "left": {"value": 3.17, "unit": "cm"}, "right": {"value": 3.17, "unit": "cm"}},
    "docGrid": {"type": "lines", "linePitch": {"value": 15.6, "unit": "pt"}}
  },
  "defaults": {
    "font": {"eastAsia": "宋体", "western": "Times New Roman"},
    "size": {"value": 12, "unit": "pt"}, "color": "#000000", "lang": {"eastAsia": "zh-CN", "western": "en-US"}
  },
  "body": {
    "styleId": "Normal", "source": "style", "samples": 412,
    "font": {"eastAsia": "楷体_GB2312", "western": "Arial"},
    "size": {"value": 12, "unit": "pt"}, "bold": false, "color": "#000000",
    "alignment": "justify",
    "lineSpacing": {"rule": "atLeast", "value": 16, "unit": "pt"},
    "spaceBefore": {"value": 0, "unit": "pt"}, "spaceAfter": {"value": 18, "unit": "pt"},
    "firstLineIndent": {"value": 2, "unit": "chars"},
    "leftIndent": {"value": 0, "unit": "pt"}, "rightIndent": {"value": 0, "unit": "pt"},
    "afterTableSpaceBefore": {"value": 18, "unit": "pt"}
  },
  "headings": [
    {
      "level": 1, "styleId": "Heading1", "styleName": "标题 1", "source": "style", "samples": 9,
      "font": {"eastAsia": "黑体", "western": "Arial"}, "size": {"value": 16, "unit": "pt"},
      "bold": true, "color": "#000000", "alignment": "center",
      "lineSpacing": {"rule": "auto", "value": 1.5}, "spaceBefore": {"value": 12, "unit": "pt"}, "spaceAfter": {"value": 12, "unit": "pt"},
      "firstLineIndent": {"value": 0, "unit": "pt"}, "leftIndent": {"value": 0, "unit": "pt"}, "hangingIndent": null,
      "keepWithNext": true, "pageBreakBefore": false,
      "numbering": {"kind": "auto", "numFmt": "chineseCounting", "lvlText": "%1、", "start": 1, "suffix": "nothing", "numIndent": {"value": 0, "unit": "pt"}}
    },
    {
      "level": 2, "styleId": "Heading2", "source": "style", "samples": 31,
      "font": {"eastAsia": "楷体_GB2312", "western": "Arial"}, "size": {"value": 12, "unit": "pt"}, "bold": true, "alignment": "justify",
      "lineSpacing": {"rule": "atLeast", "value": 16, "unit": "pt"}, "spaceBefore": {"value": 0, "unit": "pt"}, "spaceAfter": {"value": 18, "unit": "pt"},
      "firstLineIndent": {"value": 2, "unit": "chars"},
      "numbering": {"kind": "literal", "numFmt": "chineseCounting", "lvlText": "（%1）", "start": 1, "suffix": "nothing"}
    },
    {
      "level": 3, "styleId": "Heading3", "source": "instance", "samples": 57,
      "font": {"eastAsia": "楷体_GB2312", "western": "Arial"}, "size": {"value": 12, "unit": "pt"}, "bold": true,
      "numbering": {"kind": "literal", "numFmt": "decimal", "lvlText": "%1.", "suffix": "space"}
    }
  ],
  "numbering": {
    "abstractNumId": 3, "multilevelLinked": true,
    "levels": [
      {"ilvl": 0, "numFmt": "chineseCounting", "lvlText": "%1、", "indent": {"left": {"value": 0, "unit": "pt"}, "hanging": {"value": 0, "unit": "pt"}}},
      {"ilvl": 1, "numFmt": "chineseCounting", "lvlText": "（%2）"},
      {"ilvl": 2, "numFmt": "decimal", "lvlText": "%3."}
    ],
    "bodyLists": {"bullet": {"lvlText": "", "font": "Symbol"}, "decimal": {"lvlText": "%1."}}
  },
  "table": {
    "source": "instance", "samples": 14, "tableStyleId": "TableGrid",
    "width": {"value": 100, "unit": "percent"}, "alignment": "center", "layout": "fixed",
    "cellMargins": {"top": {"value": 0, "unit": "pt"}, "left": {"value": 0.19, "unit": "cm"}},
    "borders": {
      "outside": {"style": "single", "width": {"value": 1.5, "unit": "pt"}, "color": "#000000"},
      "insideH": {"style": "single", "width": {"value": 0.5, "unit": "pt"}, "color": "#000000"},
      "insideV": {"style": "single", "width": {"value": 0.5, "unit": "pt"}, "color": "#000000"}
    },
    "header": {
      "rows": 1, "repeatOnEachPage": true, "bold": true, "alignment": "center", "verticalAlign": "center",
      "fill": "#D9D9D9", "font": {"eastAsia": "黑体", "western": "Arial"}, "size": {"value": 10, "unit": "pt"}
    },
    "cell": {
      "font": {"eastAsia": "楷体_GB2312", "western": "Arial"}, "size": {"value": 10, "unit": "pt"},
      "lineSpacing": {"rule": "atLeast", "value": 12, "unit": "pt"},
      "spaceBefore": {"value": 0.2, "unit": "lines"}, "spaceAfter": {"value": 0.2, "unit": "lines"},
      "firstLineIndent": {"value": 0, "unit": "pt"}, "verticalAlign": "center",
      "byContentType": {
        "text":   {"alignment": "left"},
        "number": {"alignment": "right", "thousandsSeparator": true, "decimals": 2},
        "date":   {"alignment": "center", "pattern": "yyyy年M月d日"},
        "serial": {"alignment": "center"}
      }
    },
    "zebra": {"enabled": false, "oddFill": null, "evenFill": "#F2F2F2"},
    "columnWidths": {"mode": "percent", "samples": [[15, 55, 30], [10, 30, 30, 30]]},
    "rowHeight": {"rule": "atLeast", "value": 0.8, "unit": "cm"},
    "caption": {"position": "above", "style": "Caption", "pattern": "表 %1"}
  },
  "headerFooter": {
    "header": {"enabled": true, "text": "XX律师事务所  法律尽职调查报告", "alignment": "right", "font": {"eastAsia": "宋体", "western": "Arial"}, "size": {"value": 9, "unit": "pt"}, "borderBottom": {"style": "single", "width": {"value": 0.5, "unit": "pt"}}},
    "footer": {"enabled": true, "pageNumber": {"enabled": true, "pattern": "第 {PAGE} 页 共 {NUMPAGES} 页", "alignment": "center", "format": "decimal", "start": 1}, "text": null},
    "differentFirstPage": true, "differentOddEven": false
  },
  "toc": {"enabled": true, "levels": 3, "hyperlinks": true, "title": "目  录", "titleStyle": {"size": {"value": 16, "unit": "pt"}, "bold": true, "alignment": "center"}, "entryStyles": {"TOC1": {"bold": true}, "TOC2": {"leftIndent": {"value": 2, "unit": "chars"}}}, "pageBreakAfter": true},
  "characterStyles": {"emphasis": {"styleId": "Strong", "bold": true}, "quote": {"styleId": "Quotations", "italic": false, "leftIndent": {"value": 2, "unit": "chars"}}},
  "notes": ["二级标题编号是手打的字面文本（非自动编号），生成时按 literal 拼接"],
  "confidence": {"headings": 0.92, "table": 0.7, "headerFooter": 1.0}
}
```

约定说明：
- `numbering.kind`：`auto`=文档里用 numPr 自动编号；`literal`=标题文字自带「（一）」。律所模板两种都常见，写端要分别走「套 NumberingRules/numPr」与「拼字符串」两条路，读端必须区分（判据：段落有无 `numPr`，文本是否以编号正则开头）。
- `chars`/`lines` 单位落到 docx 用 `firstLineChars`/`beforeLines`（1/100 字符、1/100 行）；落到 LOWA 用当前字号折算（现有 `set_paragraph_format.firstLineIndentChars` 已这么做）。
- 缺省 profile（HOUSE 的 JSON 化）随后端资源文件发布：`backend/src/main/resources/style-profiles/house-default.json`，三处写端的单测对拍这个文件，替代今天「三处逐字一致」的人工约定。

---

## 3. 写报告：两条路各能落哪些字段

### 3.1 `write_docx`（flexmark → docx4j，`FileTools.java:392` / `AiDocxExportService.java`）

现状：`DocxStyleHelper.applyStandardFormat(pkg)` 在 render 后、save 前改 `Normal/BodyText/ParagraphTextBody/Quotations/Heading1-6` 样式 + 遍历正文表格刷 `tblBorders/tcPr/vAlign/jc/sz/b`。全部数值来自常量。

| profile 字段 | docx4j 能否落 | 落法 | 现状 |
|---|---|---|---|
| body/headings 字体（含 eastAsia） | 能 | `RFonts.setEastAsia/setAscii/setHAnsi/setCs`（已有 `houseRPr`） | 已做，常量 |
| 字号/加粗/颜色 | 能 | `HpsMeasure`/`BooleanDefaultTrue`/`Color`（已有） | 已做，常量 |
| 对齐/段前段后/行距(含 rule)/缩进(含 chars) | 能 | `PPrBase.Spacing` `setLineRule(AUTO/AT_LEAST/EXACT)`、`Ind.setFirstLineChars`（已有 `housePPr`） | 已做，常量；Heading 2-6 与正文同款 |
| keepWithNext/pageBreakBefore | 能 | `PPr.setKeepNext/setPageBreakBefore` | 未做 |
| 标题自动编号（一、（一）1.） | 能 | 建 `NumberingDefinitionsPart`，`Numbering/AbstractNum/Lvl`（numFmt=CHINESE_COUNTING，lvlText），Heading 样式 `PPr.setNumPr` | **未做**；flexmark 不给标题编号，有序列表会生成自己的 numbering，需后处理接管 |
| 标题字面编号（literal） | 能 | 后处理遍历 `P` 按 outlineLvl 计数器拼前缀 | 未做 |
| 表格边框（分 outside/inside，线型/粗细/颜色） | 能 | `TblBorders` 六边各自 `CTBorder(val, sz, color)`（已有 `gridBorder`，六边同值） | 部分 |
| 单元格边框覆盖 | 能 | `TcPr.setTcBorders` | 未做 |
| 表头样式（加粗/底纹/居中/重复表头） | 能 | run `b`、`TcPr.setShd(CTShd fill)`、`jc`、`TrPr` 加 `CTTblHeader` | 加粗居中已做；底纹/重复未做 |
| 单元格按内容类型对齐 | 能 | 现有 `NUMERIC_CELL` 分流 + 加日期/序号正则 | 数字已做 |
| 列宽 | 能 | `TblGrid.getGridCol().setW` + 每 `TcPr.setTcW(type=dxa/pct)`；`TblPr.setTblLayout(FIXED)` | **未做**（flexmark 生成的列宽是自动） |
| 斑马纹 | 能 | 逐行 `TcPr.shd` | 未做 |
| 行高 | 能 | `TrPr` 加 `CTHeight(val, hRule)` | 未做 |
| 页面（纸张/边距/网格） | 能 | `SectPr.getPgSz/getPgMar/getDocGrid`（`MainDocumentPart.getContents().getBody().getSectPr()`） | 未做 |
| 页眉页脚文字 | 能 | `HeaderPart/FooterPart` + `SectPr.getEGHdrFtrReferences()`；`titlePg` | 未做 |
| 页码「第 X 页 共 Y 页」 | 能 | `fldSimple instr="PAGE"`/`NUMPAGES` 或 `FldChar` 三件套 | 未做 |
| 目录 | 能生成**域**，不能生成**条目** | `TocGenerator`（docx4j 有）或手写 `TOC \o "1-3" \h` 域 + `settings.xml updateFields=true`（Word 打开时提示更新）；LOWA 侧打开后可派发 `.uno:UpdateAllIndexes` 真生成 | 未做 |
| 字符样式 | 能 | 样式定义 + run `rStyle` | 未做 |

结论：docx4j 路径**没有能力缺口**，全是工作量。风险点：flexmark 把 markdown 标题映射为 `Heading1..6`、列表映射为它自带的 numbering，后处理要在 render 之后整体接管（已有的 `overrideBaseStyles` 就是这个模式）。

### 3.2 编辑器路径（LOWA，`DocumentEditTools` → worker）

| profile 字段 | 可用原语 | 能落 | 缺口 |
|---|---|---|---|
| 正文/标题字体 | `doc_format_selection(fontName)`；`doc_apply_standard_format` | 西文 `CharFontName`；HOUSE 的中西文 | **`fontName` 只设 `CharFontName`，没有 `fontNameAsian` 参数**；profile 的 eastAsia 字体无法按选区落 |
| 字号/粗斜下删/颜色/高亮 | `doc_format_selection` | 全部 | 作用于当前选区，一段一段调，整篇要循环 |
| 对齐/标题级别/行距(6 种模式)/段前后/首行缩进(chars 或 pt)/左右缩进 | `doc_set_paragraph_format` | 全部（行距与缩进的单位语义与 profile 对得上） | keepWithNext/pageBreakBefore 没有 |
| 套既有样式 | `doc_set_style(styleName)` | 套用 | **不能创建/修改样式定义**；模板的 `Heading 1` 属性写不进去，只能逐段直接格式 |
| 编号 | `doc_set_numbering(preset, level)` | bullet/decimal/chinese/multilevel/none 四个预设 | 不能给「一、→（一）→1.」这种混合多级、不能自定义 `lvlText`/起始值/缩进；`chinese` 只是第一级 |
| 整篇套规范 | `doc_apply_standard_format` → `apply_house_style` | 字体中西文/字号/对齐/段距/行距/缩进/标题加粗/表格标准式/表后段前 | **全部来自 HOUSE 常量，不接参数**；首段≤60 字当主标题的启发式 |
| 表格边框 | `doc_format_table(borderWidthPt)` | 六边同宽实线 | worker 收 `borderColor` 但 Java `@P` 没暴露；**无线型（双线/虚线）、无 outside/inside 区分、无单格边框** |
| 表头 | `doc_format_table(firstRowBold)` / `applyStandard` | 首行加粗；标准式含居中 | **无底纹、无重复表头、无表头字体** |
| 单元格对齐 | `doc_format_table(cellVerticalAlign)`；标准式按数字正则居右 | 垂直对齐；水平只在标准式 | 水平对齐不可单独指定；按内容类型/按列不可配置 |
| 列宽/行高 | `doc_format_table(columnWidthsPercent, rowHeightPt, rowHeightRule)` | 百分比列宽、行高 | 绝对列宽（cm）没有 |
| 斑马纹/单元格底纹 | 无 | —— | **缺口**（worker 需加 `BackColor`） |
| 新建表 | `doc_insert_table(rowsJson, headerRow)` → `insertStyledTable` | 按 HOUSE 建 | 不接 profile |
| 流式落字 | `doc_start_stream` → `stream_insert` | 按 HOUSE | 不接 profile |
| 页眉页脚 | `doc_edit_header_footer(target, text, align)` | 文本 + 对齐 | **无页码域、无字体字号、无首页不同、无底边线** |
| 页面设置 | 无 | —— | **缺口**（页面样式 `Width/Height/LeftMargin/TopMargin`、`GridMode`） |
| 目录 | 无 | —— | **缺口**（`com.sun.star.text.ContentIndex` 插入 + `update()`） |
| 样式定义改写 | 无 | —— | **缺口**：`xModel.getStyleFamilies().getByName('ParagraphStyles').getByName('Heading 1').setPropertyValue(...)` 在 UNO 里是标准操作；改样式比逐段直接格式好——后续流式/手打文字自动继承，docx 导出也落到 `styles.xml` |

### 3.3 两条路都写不了的（今日缺口）

1. **中文字体按选区/按样式设定**（docx4j 能，但 write_docx 不接参数；LOWA 没参数）。
2. **自定义编号定义**（numFmt + lvlText + 多级绑定）。
3. **页面设置、页码域、目录**：write_docx 未实现，LOWA 无原语。
4. **表格的线型/分边/底纹/重复表头/绝对列宽**。
5. **样式定义级别的写入**（LOWA 路径）。
6. 把 profile 当参数传进任何写端（根本缺口：三处 HOUSE 都是常量）。

### 3.4 写端设计建议

- **不要给每个原语加十几个参数**。新增一个动作 `apply_style_profile {profile, scope: document|selection|styles-only}`，worker 把 `HOUSE` 换成 `ACTIVE_PROFILE`（默认=HOUSE JSON），`applyHouseChar/applyHousePara/styleTableStandard/insertStyledTable/stream_insert` 全部读 `ACTIVE_PROFILE`。`doc_open_file` 之后由后端把项目的 profile 下发一次（新 action `set_style_profile`），之后 `doc_insert_table`/`doc_start_stream` 自然按模板走，AI 不必每次传。
- `apply_style_profile` 优先**改样式定义**（ParagraphStyles 的 `Standard`/`Heading N`、表格用 `Table Contents`/`Table Heading`），再对已有段落做最小直接格式（沿用 `keepWeight` 不抹当事人加粗的约定）。
- `doc_format_table` 补 `borderColor`（Java 侧露出）、`borderStyle`、`outsideBorder/insideBorder` 分开、`headerFill`、`zebraFill`、`repeatHeader`、`columnWidthsCm`；或者干脆让它接 `tableProfileJson`。
- `doc_format_selection` 补 `fontNameAsian`。
- 新增 `doc_set_page_setup`、`doc_insert_toc`、`doc_edit_header_footer` 加 `pageNumberPattern/fontSize/fontName`。
- `write_docx(styleProfileJson?)`：`DocxStyleHelper.applyStandardFormat(pkg, profile)` 重载，常量路径改为 `profile == null ? HOUSE_DEFAULT : profile`；`AiDocxExportService` 同步透传。
- 插件端 `officeExecutor.js` 的 `HOUSE` 是第三个消费者（`office_apply_standard_format`），同样改成接 profile；本文不展开，但「三处逐字一致」的地雷在 profile 化之后变成「三处都对拍 `house-default.json`」。

---

## 4. 模板上传 UX 与画像存放

### 4.1 候选入口比较

| 方式 | 优点 | 缺点 |
|---|---|---|
| 项目内 `_模板/` 文件夹（用户把过往报告拖进去 / AI 建夹） | 零新 UI；走现有上传、版本记录、项目共享；团队成员都看得见、能换；与「尽调/」章节文件夹同一心智 | 依赖约定文件夹名；跨项目复用要再拷一次 |
| 设置页上传（`pages/userprofile` 或 admin） | 全局一次、处处生效 | 设置页今天没有文件上传件；个人设置≠团队共识；web/h5 与桌面同步问题 |
| AI 面板拖入（`FileStagingArea.vue` 已支持拖放进会话） | 最顺手，「这是我们的模板，照着学」一句话 | 暂存区文件是会话附件不是项目文件，学完要落地才可复用 |

**建议：以项目 `_模板/` 文件夹为权威存放，AI 面板拖入作为快捷入口**（拖入时 AI 调 `docx_inspect_template` 学习，并把文件 `move_project_file` 进 `_模板/`、画像落 `_模板/画像.json`）。设置页只做「团队默认画像」的查看/重置（第二期）。

### 4.2 画像存放与优先级

| 存放 | 适用 | 说明 |
|---|---|---|
| 项目文件 `_模板/画像.json`（+ AI 写的 `画像.md` 人话摘要） | 项目级，团队共用 | 进版本记录、可手改、`extract_file_text` 能读回（json 属纯文本类型需确认 `PLAIN_TEXT_TYPES` 是否放行 json；不放行就用 `.md` 承载 JSON 代码块） |
| `SystemSetting` `dd.styleProfile.default` | 律所级默认（admin 设） | `SystemSetting(key,value TEXT)` 现成；桌面单机版=本机默认，云后端=团队默认 |
| 记忆（`project_memory`） | 只存指针与偏好（「本所默认用 A 画像」） | 不存 JSON 本体，召回不稳定且不可手改 |
| skill 目录 `backend/skills/due-diligence/` | 出厂默认 profile 与 prompt | 随 skill 分发 |

解析顺序：工具显式 `styleProfileJson` > 当前项目 `_模板/画像.json` > `SystemSetting` 默认 > `house-default.json`。多人共用：项目文件天然共享；律所级靠 admin 设置；冲突时后学的覆盖并在 `画像.md` 里留一行变更记录（版本记录兜底可退回）。

### 4.3 学习流程（用户视角）

1. 用户拖入 1-3 份过往报告，说「学这个模板」。
2. AI 调 `docx_inspect_template(fileIds[])`：多份取众数，置信度写进 `confidence`；.doc 文件提示「请另存为 docx 或在编辑器里打开后再学」（或走 LOWA 兜底）。
3. AI 用 `<question>` 结构化反问一次确认关键项（标题几级、编号自动还是手打、表格样式），写 `_模板/画像.json` + `画像.md`。
4. 起草：`write_docx(..., styleProfileJson)` 或编辑器路径自动 `set_style_profile`。
5. 核验：LOWA 打开后 `get_formatting` 抽样（首段、各级标题首个、首张表）与 profile 比对，差异回报。

---

## 5. 分期与代码面

### 第一期：最小可用（标题 + 正文 + 基础表格），约 4-5 天

目标：学到各级标题与正文的字体（中西文）/字号/加粗/对齐/行距/段前后/缩进 + 编号形态（auto/literal + lvlText）+ 表格的边框粗细颜色/表头加粗底纹/单元格字号对齐/列宽百分比；`write_docx` 与编辑器整篇格式化都按 profile 走。

| 代码面 | 改动 |
|---|---|
| `backend/.../tools/TemplateTools.java`（新） | `@Tool docx_inspect_template(fileIds, options)`：docx4j 打开 → `PropertyResolver` 取 Normal/Heading 有效值 → 遍历正文实例按 outlineLvl/编号正则聚合众数 → 表格聚合 → 输出 profile v1（§2 子集：page 略、headerFooter 略、toc 略）。注册进 `RealToolBeans`、`toolDisplayNames.js` |
| `backend/.../util/StyleProfile.java`（新） | profile 的 Java 记录 + Jackson 解析 + 单位换算（chars/lines/pt/twips）+ `house-default.json` 加载 |
| `backend/src/main/resources/style-profiles/house-default.json`（新） | HOUSE 的 JSON 化 |
| `DocxStyleHelper.java` | `applyStandardFormat(pkg, StyleProfile)`；常量改读 profile；表格分 outside/inside 边框、表头底纹、列宽 `tblGrid/tcW` |
| `FileTools.java` / `AiDocxExportService.java` | `write_docx` 加可选 `styleProfileJson`；缺省时按 §4.2 顺序解析项目 `_模板/画像.json` |
| `office_thread.js` | `HOUSE` → `ACTIVE_PROFILE`；新 action `set_style_profile`、`apply_style_profile`（先改 ParagraphStyles 定义再扫段落）；`format_selection` 加 `fontNameAsian`；`format_table` 露出 `borderColor/borderStyle/headerFill/outside-inside` |
| `libreofficeExecutorClient.js` | EDITOR_ACTIONS 加两个 action |
| `DocumentEditTools.java` | `doc_apply_style_profile`、`doc_format_selection` 加参、`doc_format_table` 加参；`doc_open_file` 成功后由 `EditorBridgeService` 追发 `set_style_profile` |
| `ContextAssemblerService.java` | docx 分支提示：项目有画像时用 `doc_apply_style_profile`/`write_docx(styleProfileJson)` |
| `backend/skills/due-diligence/prompt.md` | 学习→确认→落画像→起草的工作流 |
| 测试 | `TemplateToolsTest`（fixtures：一份带三级标题+表格的 docx，断言 profile 字段）；`DocxStyleHelperTest`（profile 落盘后重新读回对拍）；lowa-e2e 新组（apply_style_profile 后 `get_formatting` 对拍）；三处默认值对拍 `house-default.json` 的单测 |

### 第二期：完整颗粒度，约 8-10 天（在一期之上）

| 代码面 | 改动 |
|---|---|
| `TemplateTools` | 补 page/headerFooter（含页码域模式识别）/toc/numbering 完整定义/字符样式/斑马纹/按内容类型（数字千分位、日期样式）/行高/重复表头；多模板众数与冲突报告；表格样式三层合并；主题字体解析（`ThemePart`） |
| `DocxStyleHelper` | 页面 `SectPr`、`HeaderPart/FooterPart` + PAGE/NUMPAGES 域、`NumberingDefinitionsPart`（标题多级自动编号）、literal 编号拼接、TOC 域 + `updateFields`、斑马纹、行高、重复表头、keepWithNext |
| `office_thread.js` | 新 action：`set_page_setup`、`insert_toc`（ContentIndex + update）、`edit_header_footer` 加页码域/字体、`set_numbering` 接自定义 `levels[]`（NumberingRules 的 NumberingType/Prefix/Suffix/StartWith）、`format_table` 加 `zebraFill/repeatHeader/columnWidthsCm`、`get_table_format`（核验用读回）|
| `DocumentEditTools` | 对应新工具 `doc_set_page_setup`、`doc_insert_toc`、`doc_get_table_format`、`doc_set_numbering` 新签名（注意位置参数映射对旧会话回放的影响）|
| LOWA 兜底读取 | `inspect_template_lowa`（worker）：.doc 模板或需要渲染字体名时，打开后整篇扫描输出同 schema 的 profile，后端合并 |
| `officeExecutor.js` | 插件端 `office_apply_standard_format` 接 profile（同一 schema） |
| 前端 | `_模板/` 文件夹识别（文件树图标/说明）；设置页「团队默认画像」查看/重置；admin `SystemSetting` 项 |
| 测试 | fixtures 扩到含页眉页脚/目录/多级编号/斑马纹的模板；e2e 走「拖入模板→学→起草→核验」全链路 |

### 工期外的风险

- docx4j `PropertyResolver` 对 `firstLineChars` 等「字符单位」属性是否原样保留需实测（它的目标是渲染，可能已折算）；读声明值时建议绕过 resolver 直接读 `Style.getPPr()` 链，resolver 只用来补缺省。
- flexmark 生成的 `Heading` 样式 id 与中文 Word 模板的样式 id（`1`、`2`，显示名「标题 1」）不同，profile 里同时记 `styleId` 与 `styleName`，写端按 outlineLvl 而不是按 id 对位。
- LOWA 改样式定义会触发 `modified` → 自动保存，属预期；但 `RecordChanges=true` 下属性变更不进修订，安全网仍是检查点。
