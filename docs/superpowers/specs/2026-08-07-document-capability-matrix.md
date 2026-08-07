# 文档操作能力全集与双向覆盖矩阵

调研日期：2026-08-07。纯调研 spec，不含代码改动。

## 目的与范围

维护者要求：先建立「插件（Office.js）对文档的操作」能力全集，再据此双向找齐——插件缺的补插件，桌面端（LOWA/UNO）缺的补桌面端。这份矩阵覆盖 Word / Excel / PPT 三个宿主，逐能力项给出插件现状、桌面端现状、补齐判定，供下一轮排期使用。

依据材料：
- `.claude/agents/ai-doc-bridge.md`、`.claude/agents/office-addin.md` 两份领域文档
- 代码实况：`backend/src/main/java/com/checkba/service/ai/tools/DocumentEditTools.java`（doc_*/sheet_* 58 个 @Tool）、`OfficeEditTools.java`（office_* 50 个 @Tool）、`PptxTools.java`（pptx_* 批量文件编辑工具）、`frontend/src/composables/libreofficeExecutorClient.js` 的 `EDITOR_ACTIONS` 白名单
- Microsoft Learn：Word/Excel/PowerPoint JavaScript API 各需求集（WordApi 1.1-1.9 + WordApiDesktop、ExcelApi 1.1-1.15、PowerPointApi 1.1-1.10）实时检索，标注了具体需求集版本号的行均已查证；未查到官方文档佐证的行明确标「待查证」，不冒充结论

## 判定原则

1. **修订与批注是律师核心工作流**：法律文档审阅的本质就是「批注说明 + 修订往返（接受/拒绝）」，这两类能力任何一侧缺失都判高优先级，不论实现成本。
2. **常规排版刚需（页眉页脚、分页分节符、超链接）**：中高优先级，法律文件天天用得到，但不是审阅环节的核心动作。
3. **结构化文档能力（内容控件、域、书签、文档属性、样式库管理）**：多为模板生成/数据绑定场景服务，本产品未走「Word 模板域填空」路线，现有 `apply_standard_format`/`apply_house_style` 已覆盖主要格式化诉求，判低优先级。
4. **演示动画类（PPT 切换动画、备注页动效）低价值**：法律场景的 PPT 是尽调汇报/庭审辅助材料，不是路演演示，动画诉求极弱；且经查证这两项在 PowerPoint JS API（至 1.10）里根本没有对应类，两侧都做不了。
5. **「形态差异不补」**：桌面端检查点机制 vs Word 原生修订面板是典型例子——两边解决同一个安全网诉求，但机制不同，不视为「桌面缺修订安全网」。PPT 尤其突出：桌面端完全没有走 Impress 实时光标编辑路线，而是用 `PptxTools`（python-pptx 批量重写 + AI 图片再生成）操作已导出的 pptx 文件，与插件的 Office.js 实时编辑范式本质不同，见「存疑判定」一节详细讨论。
6. **判定枚举**：该补插件 / 该补桌面端 / 两侧都补 / 形态差异不补 / 低价值不补。

---

## 一、Word 矩阵（23 行）

| # | 能力 | 插件现状 | 桌面端现状 | 判定 | 依据 |
|---|---|---|---|---|---|
| 1 | 字符格式（粗体/斜体/下划线/删除线/高亮/字色/字号/字体） | 已做：`office_format_text` | 已做：`doc_format_selection` | 两侧都做 | 现有工具已对齐 |
| 2 | 段落格式（对齐/标题级别/行距/段距/缩进） | 已做：`office_set_paragraph_format` | 已做：`doc_set_paragraph_format` | 两侧都做 | 现有工具已对齐 |
| 3 | 编号/项目符号 | 已做：`office_set_numbering` | 已做：`doc_set_numbering` | 两侧都做 | 现有工具已对齐 |
| 4 | 表格整体格式化（边框/对齐/表头加粗） | 已做：`office_format_table` | 已做：`doc_format_table` | 两侧都做 | 现有工具已对齐 |
| 5 | 表格单元格级读写（读表/改格/增删行列） | 已做：`office_table_*`（6 个） | 已做：`doc_table_*`（6 个） | 两侧都做 | issue #261 两侧同批完成 |
| 6 | 全文标准格式化（律所格式一键套用） | 已做：`office_apply_standard_format` | 已做：`doc_apply_standard_format` | 两侧都做 | HOUSE 常量三处同步 |
| 7 | 查找/替换 | 已做：`office_search`/`office_replace_text` | 已做：`doc_find_text`/`doc_find_replace` | 两侧都做 | 现有工具已对齐 |
| 8 | 批注-添加 | 已做：`office_add_comment` | 已做：`doc_add_comment` | 两侧都做 | PR#191 |
| 9 | 批注-读取/回复/解决 | 已做：`office_get_comments`/`reply_comment`/`resolve_comment`（批次 8） | **未做**：AI 无对应原语。worker 端已有 `list_comments`/`goto_comment`/`set_comment_resolved`/`delete_comment`，但都是 ReviewPanel.vue 的 host-initiated 调用，不在 `doc_*` 工具面里，模型调不到 | **该补桌面端** | 高优先级，见判定原则 1 |
| 10 | 修订-接受/拒绝（单条与全部） | **可做未做**：`Word.TrackedChange.accept()/reject()`（WordApi 1.6）、`TrackedChangeCollection.acceptAll()/rejectAll()`（WordApi 1.6）、`Document.rejectAllRevisions()`（WordApiDesktop 1.4） | **未做**：worker 端 `resolve_revision`/`resolve_all_revisions` 同样是 ReviewPanel.vue 的 host-initiated 调用，无 `doc_*` AI 原语 | **两侧都补** | 高优先级，见判定原则 1 |
| 11 | 页眉页脚 | 已做：`office_edit_header_footer` | **未做**：`doc_*` 无对应工具 | **该补桌面端** | UNO `Section.getHeader/getFooter` 成熟 API |
| 12 | 分页符/分节符 | 已做：`office_insert_break`（`breakType`: page / sectionNext） | **未做**：`doc_*` 无对应工具 | **该补桌面端** | UNO 段落属性 `BreakType`/`PageDescName` 成熟 API |
| 13 | 超链接（插入/读取） | 已做：`office_set_hyperlink` | **未做 AI 原语**：`EDITOR_ACTIONS` 有 `get_selection_hyperlink`/`set_selection_hyperlink`，但注释明确写「Host-initiated（drag-association），not AI-agent commands」，没有对应 `doc_*` 工具 | **该补桌面端** | worker 实现已在，只差包一层 AI 工具面，工作量小 |
| 14 | 图片插入（AI 可调用） | **可做未做**：`Body.insertInlinePictureFromBase64`（WordApi 1.2） | **未做 AI 原语**：`insert_image` 同样标注「Host-initiated (drag-association / evidence-drop / OCR image), not AI agent commands」 | **两侧都补** | 中优先级：需求存在但走特殊通道，非审阅高频动作 |
| 15 | 脚注/尾注 | **可做未做**：`Word.NoteItem`/`Range.insertFootnote`/`insertEndnote`（WordApi 1.5） | **未做**：`doc_*` 无对应工具，UNO `XFootnote` 支持 | **两侧都补** | 中优先级：法律文件引用/释义常用 |
| 16 | 内容控件（Content Control） | **可做未做**：`Word.ContentControl`（WordApi 1.1） | **未做**：LOWA 无原生「内容控件」概念对应物（UNO 有 Text Field 但语义不同） | **形态差异不补** | 本产品未走模板域填空路线，见判定原则 3 |
| 17 | 样式管理（应用已命名样式/新建样式） | **可做未做**：`document.getStyles()`/`paragraph.style`（WordApi 1.3） | **未做**：`doc_*` 无「应用现有样式名」原语，仅有硬编码 `apply_standard_format`；UNO `ParaStyleName`/`CharStyleName` 完全支持 | **两侧都补，低优先级** | 现有标准格式化已覆盖主要诉求，样式库管理是锦上添花 |
| 18 | 域（Field，如页码域/日期域） | **可做未做**：`Range.insertField`（WordApi 1.5） | **未做**：`doc_*` 无对应工具 | 低价值不补 | 特殊需求，法律文件极少依赖动态域 |
| 19 | 书签（Bookmark） | **可做未做**：`Range.insertBookmark`（WordApi 1.4）、`Document.getBookmarkRange(OrNullObject)`（WordApi 1.4） | **未做**：`doc_*` 无对应工具，但内部已有等价的 `anchorId` 稳定锚点机制（`doc_find_text` 返回值） | 低价值不补 | 内部锚点机制已替代书签的主要用途 |
| 20 | 文档属性（内置/自定义） | **可做未做**：`DocumentProperties`/`CustomProperty`（WordApi 1.3） | **未做**：`doc_*` 无对应工具 | 低价值不补 | 元数据管理非审阅工作流一部分 |
| 21 | 节与分栏（Section/Columns） | **可做未做**：`Section.pageSetup`/`SectionStart`（WordApiDesktop 1.3，桌面版 Word 专属） | **未做**：`doc_*` 无对应工具 | 低价值不补 | 法律文件排版极少用多栏；插件侧还受限于 WordApiDesktop（网页版 Word 不支持） |
| 22 | 表格整表插入 | 已做：`office_insert_table` | 已做：`doc_insert_table` | 两侧都做 | 现有工具已对齐 |
| 23 | 撤销/重做 | 未做（Office.js 无跨会话撤销栈 API 可暴露给 AI；Excel/PPT 同理） | 已做：`doc_undo`/`doc_redo` | 形态差异不补 | 插件侧安全网是 `changeTrackingMode` 恢复 + 用户手动撤销，桌面侧是 UNO `XUndoManager`；Office.js 未暴露程序化撤销栈给加载项 |

---

## 二、Excel 矩阵（22 行）

| # | 能力 | 插件现状 | 桌面端现状 | 判定 | 依据 |
|---|---|---|---|---|---|
| 1 | 读取区域 | 已做：`office_excel_get_range` | 已做：`sheet_read_range` | 两侧都做 | |
| 2 | 写入单元格 | 已做：`office_excel_set_values` | 已做：`sheet_write_cells` | 两侧都做 | |
| 3 | 搜索 | 已做：`office_excel_search` | **已做**：`sheet_search`（波次 C，遍历区域比对字符串） | 两侧都做 | 桌面端已补齐专用搜索原语 |
| 4 | 单元格格式（字体/字号/颜色/对齐/数字格式） | 已做：`office_excel_format_cells` | 已做：`sheet_format_cells` | 两侧都做 | |
| 5 | 边框 | 已做：`office_excel_set_borders` | 已做：`sheet_set_borders` | 两侧都做 | |
| 6 | 行高列宽 | 已做：`office_excel_edit_rows_cols`（set_width/set_height） | 已做：`sheet_set_row_col` | 两侧都做 | |
| 7 | 插删行列 | 已做：`office_excel_edit_rows_cols` | 已做：`sheet_edit_rows_cols` | 两侧都做 | |
| 8 | 合并单元格 | 已做：`office_excel_merge_cells` | 已做：`sheet_merge_cells` | 两侧都做 | |
| 9 | 区域排序 | 已做：`office_excel_sort_range` | 已做：`sheet_sort_range` | 两侧都做 | |
| 10 | 自动筛选 | 已做：`office_excel_set_autofilter`（apply/clear/remove，不支持按条件筛值，产品范围决定） | 已做：`sheet_set_autofilter`（同等范围） | 两侧都做 | 两侧口径一致，都是「只做 UI 开关不做条件」 |
| 11 | 冻结窗格 | 已做：`office_excel_freeze_panes` | 已做：`sheet_freeze_panes` | 两侧都做 | |
| 12 | 条件格式 | 已做：`office_excel_conditional_format`（cellValue/colorScale，每次 apply 替换） | 已做：`sheet_conditional_format`（同口径） | 两侧都做 | |
| 13 | 工作表管理（增删改名移动） | 已做：`office_excel_manage_sheets` | 已做：`sheet_manage_sheets` | 两侧都做 | |
| 14 | 公式写入与错误回读 | 已做：`office_excel_set_formulas`（Office.js 原生文法，逗号+`Sheet1!A1`） | 已做（经 `sheet_write_cells` 写入以 `=` 开头的字符串，LOWA `normalizeFormula` 做分号/点号归一）；**错误回读是否有 `formulaErrors` 同款返回值待查证** | 该补桌面端，中优先级（若确认缺失） | 插件侧写入后专门读回 `range.values` 收集 `#` 开头错误进 `formulaErrors`；桌面侧 `sheet_write_cells` 是否做等价回读需看代码确认，本次未逐行核实实现细节 |
| 15 | 图表（Chart） | **可做未做**：`Excel.ChartCollection.add`（ExcelApi 1.1） | **已做**：`sheet_add_chart`（波次 C，`XTableCharts`，起步「建图表+选类型+标题」三件事，不支持自定义位置/多系列） | **两侧都补，中优先级** | 桌面端已补最小可用形态；插件端仍待做 |
| 16 | 透视表（PivotTable） | **可做未做**：`Excel.PivotTableCollection.add`（ExcelApi 1.3，1.8 增强字段控制） | **已做**：`sheet_add_pivot_table`（波次 C，`XDataPilotTables`，仅「行分组+单数据字段求和」基础形态） | **两侧都补，中优先级** | 桌面端已补最小可用形态；插件端仍待做 |
| 17 | Table 对象（结构化表/ListObject） | **可做未做**：`Excel.TableCollection.add`（ExcelApi 1.1） | **未做**：`sheet_*` 无「转为结构化表」原语，UNO `DatabaseRange` 存在 | 低价值不补 | 现有 `sheet_format_cells` + `sheet_set_autofilter` + `sheet_sort_range` 已覆盖视觉与交互效果的大部分诉求，ListObject 主要价值在自动扩展公式，AI 编辑场景用得少 |
| 18 | 命名区域（Named Range） | **可做未做**：`NamedItemCollection.add`（ExcelApi 1.1）、`getRangeOrNullObject`/`scope`（ExcelApi 1.4） | **已做**：`sheet_define_name`（波次 C，工作簿级 `XNamedRanges`） | 低价值不补，但维护者拍板做了 | AI 直接用 `Sheet1!A1:C10` 地址已够用，命名区域是给人读的便利机制；桌面端仍按判定原则补齐，插件端未跟进 |
| 19 | 数据验证（Data Validation） | **可做未做**：`Excel.DataValidation`（ExcelApi 1.8，含下拉列表/数值范围/自定义公式） | **已做**：`sheet_set_data_validation`（波次 C，`Validation` 属性对象，list/wholeNumber/decimal/date/time/textLength/custom） | **两侧都补，中优先级** | 桌面端已补；插件端仍待做 |
| 20 | 单元格批注/备注（Comment/Note） | **可做未做**：`Excel.CommentCollection`（ExcelApi 1.10，含回复线程/解决状态，与 Word 批注同构） | **已做（不同构）**：`sheet_add_comment`/`sheet_get_comments`/`sheet_delete_comment`（波次 C，`XSheetAnnotations`）——**没有线程回复/解决状态**，author/date 只读、无法强制署名，与 Word 批注不同构，工具描述已注明差异 | **两侧都补，高优先级** | 桌面端补的是「增/查/删」三件事，不含 reply/resolve；插件端仍待做 |
| 21 | 工作簿/工作表保护 | **可做未做**：`WorksheetProtection`（ExcelApi 1.2）、`WorkbookProtection`（ExcelApi 1.7） | **已做**：`sheet_protect_sheet`（波次 C，`XProtectable`，工作表级） | 低价值不补，但维护者拍板做了 | 保护多用于分发防误改场景，AI 编辑阶段用处有限；桌面端仍按判定原则补齐，插件端未跟进 |
| 22 | 分组/大纲（Group/Outline） | **可做未做**：`Range.group`/`ungroup`（ExcelApi 1.10） | **已做**：`sheet_group_rows_cols`（波次 C，`XSheetOutline`） | 低价值不补，但维护者拍板做了 | 锦上添花，法律场景使用频率低；桌面端仍按判定原则补齐，插件端未跟进 |

---

## 三、PPT 矩阵（15 行）

**重要前提**：桌面端目前**没有走 Impress 实时光标编辑路线**。`PptxTools.java`（`pptx_inspect_format`/`pptx_apply_format`/`pptx_edit_outline` 等）是直接操作已导出 pptx 文件的批量工具，改完之后编辑器整体重新加载，不是「光标停在某处、逐字打修订」那套 `doc_*`/`office_ppt_*` 共享的实时编辑范式。这是与 Word/Excel 两个宿主本质不同的架构分岔，见「存疑判定」一节。下表「桌面端现状」按这个批量机制的覆盖面填写，「不适用」表示该机制不覆盖且不打算覆盖（形态使然），不代表可以简单补一个原语了事。

| # | 能力 | 插件现状 | 桌面端现状 | 判定 | 依据 |
|---|---|---|---|---|---|
| 1 | 幻灯片增删/移动顺序 | 已做：`office_ppt_add_slide`/`delete_slide`/`move_slide` | 已做（批量机制）：`pptx_edit_outline` 支持增删改页、调整顺序 | 两侧都做（形态不同） | |
| 2 | 文本替换/读取 | 已做：`office_ppt_replace_text`/`get_slides`/`get_slide_details` | 已做：`pptx_inspect_format`（读）+ `pptx_apply_format` 的 `replace_text`/`set_shape_text`（写） | 两侧都做（形态不同） | |
| 3 | 文本格式化（字体/字号/颜色等） | 已做：`office_ppt_format_text` | 已做：`pptx_apply_format` 的 `set_run_format`/`set_paragraph_format` | 两侧都做（形态不同） | |
| 4 | 文本框插入 | 已做：`office_ppt_add_text_box` | **未做**：`pptx_apply_format` 只能改「已存在形状」的内容，不能新增形状 | **该补桌面端，低优先级** | 批量机制目前设计为改现有版面，新增形状需要扩展 python-pptx 服务端点 |
| 5 | 形状插入（几何图形） | 已做：`office_ppt_add_shape` | **未做**：同上，不能新增形状 | **该补桌面端，低优先级** | 同上 |
| 6 | 形状删除 | 已做：`office_ppt_delete_shape` | **未做**：`pptx_apply_format` 六种 action 里没有删除形状 | **该补桌面端，低优先级** | 同上 |
| 7 | 表格插入（Table shape） | **未做**：`office_ppt_*` 10 个 command 里没有加表格的工具（`PowerPoint.ShapeCollection.addTable` 是 PowerPointApi 1.8，可做未做） | **未做**：`pptx_apply_format` 只能改「已存在表格」的单元格，不能新建表格 | **该补插件，中优先级** | 插件侧明确缺口——已有 Word/Excel 都做了表格增删，PPT 表格插入却是空白，且 API 已就绪（1.8） |
| 8 | 表格单元格读写 | **未做**：`office_ppt_*` 无表格单元格级工具（`PowerPoint.Table`/`TableCell` 是 PowerPointApi 1.9，可做未做） | 已做：`pptx_apply_format` 的 `set_cell_text`/`set_cell_format` | **该补插件，中优先级** | 桌面已有、插件没有的反向缺口，法律 PPT 常见财务对比表 |
| 9 | 幻灯片详情读取 | 已做：`office_ppt_get_slide_details` | 已做：`pptx_inspect_format` | 两侧都做（形态不同） | |
| 10 | 母版/版式（Master/Layout） | **可做未做**：`SlideMaster`/`SlideLayout`（PowerPointApi 1.3）、`Slide.applyLayout`（PowerPointApi 1.8） | **未做**：批量机制不涉及母版切换 | 低价值不补 | 法律 PPT 极少需要程序化切母版，人工在模板阶段定好即可 |
| 11 | 图片插入 | **API 做不了（生产环境）**：`ShapeCollection.addPicture` 目前标注 `PowerPointApi BETA (PREVIEW ONLY)`，官方明确「不要在生产环境使用」 | **形态不同**：`pptx_edit_image`（自然语言描述改图，AI 图片再生成）覆盖的是「改写已有页面图片内容」，不是「插入一张新图片」 | 低价值不补 | 插件侧受限于预览 API 不稳定；桌面侧走生成式路径已部分满足视觉调整诉求，两侧都不建议现在追 |
| 12 | 超链接 | **可做未做**：`HyperlinkCollection.add`（PowerPointApi 1.10，读在 1.5） | **未做**：批量机制不涉及超链接 | **该补插件，低优先级** | PPT 超链接使用频率低于 Word，且需求集门槛新（1.10，覆盖旧版 Office 差） |
| 13 | 备注页（Notes Page / Speaker Notes） | **待查证，倾向 API 做不了**：多次检索 PowerPoint JS API 1.1-1.10 均未见 `NotesPage`/`NotesSlide` 相关读写类 | 同样未见 | 低价值不补 | PowerPoint JS API 迄今没有暴露备注页文本读写能力，两侧都是「API 做不了」，非产品选择 |
| 14 | 切换动画（Transition/Animation） | **API 做不了**：检索确认 PowerPoint JS API 无 Transition/Animation 相关类；这两类效果只在 Open XML 层（`<p:transition>`/`<p:timing>`）和桌面 VBA/OOXML SDK 里可编程，Office.js 从未暴露 | 同样做不了（UNO Impress 虽支持动画对象模型，但复杂度极高，且法律场景无诉求） | 低价值不补 | 见判定原则 4 |
| 15 | AI 图片再生成（banana-slides，桌面独有） | **不适用**：插件走真实 pptx 结构化编辑，没有「整页转图片再用 AI 重绘」这个概念 | 已做：`pptx_edit_image` | 形态差异不补 | 桌面独有能力，源于桌面走生成式 PPT 制作流水线（`pptx_generate`），插件是编辑已有文件，两条产品路径不同 |

---

## 四、待办清单

### 4.1 插件侧补齐清单（按优先级排序）

| 优先级 | 能力 | 对应能力表行 | 预估工作量 |
|---|---|---|---|
| 高 | Excel 单元格批注/备注（add/reply/resolve/delete） | Excel #20 | 中（一组 4-5 个 command，参照 Word 批注批次 8 模式） |
| 高 | Word 修订接受/拒绝（单条 + 全部） | Word #10 | 中（`office_accept_revision`/`office_reject_revision`，API 已就绪） |
| 中 | PPT 表格插入 | PPT #7 | 小（`office_ppt_add_table`，PowerPointApi 1.8） |
| 中 | PPT 表格单元格读写 | PPT #8 | 小（`office_ppt_table_read`/`set_cell`，PowerPointApi 1.9） |
| 中 | Word 脚注/尾注插入 | Word #15 | 中（`office_insert_footnote`/`insert_endnote`，WordApi 1.5） |
| 中 | Excel 数据验证 | Excel #19 | 小（`office_excel_set_data_validation`，ExcelApi 1.8） |
| 中 | Excel 图表 | Excel #15 | 中（`office_excel_add_chart`，ExcelApi 1.1，对象模型较大） |
| 中 | Word 图片插入（AI 可调用） | Word #14 | 小（`office_insert_image`，WordApi 1.2） |
| 低 | Excel 透视表 | Excel #16 | 大（PivotTable 层级/字段模型复杂） |
| 低 | Word 样式管理（应用/新建） | Word #17 | 中 |
| 低 | PPT 超链接 | PPT #12 | 小 |
| 低 | Word 内容控件 | Word #16 | 中（判定「形态差异不补」，除非产品决定走模板路线） |
| 低 | Word 文档属性 | Word #20 | 小 |
| 低 | Excel 命名区域 | Excel #18 | 小 |
| 低 | Excel 工作簿/工作表保护 | Excel #21 | 小 |
| 低 | Excel 分组/大纲 | Excel #22 | 小 |

插件侧共 15 条候选，前三名：Excel 单元格批注（高）、Word 修订接受/拒绝（高）、PPT 表格插入（中）。

### 4.2 桌面端补齐清单（按优先级排序）

| 优先级 | 能力 | 对应能力表行 | 预估工作量 |
|---|---|---|---|
| 高 | 批注读取/回复/解决（`doc_get_comments`/`reply_comment`/`resolve_comment`） | Word #9 | 中（worker 端 `list_comments`/`goto_comment`/`set_comment_resolved`/`delete_comment` 已存在，缺的是包一层 AI 工具面并补齐 reply 能力） |
| 高 | 修订接受/拒绝（`doc_accept_revision`/`reject_revision`/`accept_all`/`reject_all`） | Word #10 | 中（worker `resolve_revision`/`resolve_all_revisions` 已存在，同上，包一层 AI 工具面） |
| 高 | Excel 单元格批注（`sheet_add_comment` 等） | Excel #20 | **已实现**（波次 C）：`sheet_add_comment`/`sheet_get_comments`/`sheet_delete_comment`，`XSheetAnnotations`，无 reply/resolve |
| 中 | 页眉页脚（`doc_edit_header_footer`） | Word #11 | **已实现**（波次 A/B）：`doc_edit_header_footer`→`edit_header_footer` |
| 中 | 分页符/分节符（`doc_insert_break`） | Word #12 | **已实现**（波次 A/B）：`doc_insert_break`→`insert_break` |
| 中 | 超链接 AI 原语（`doc_insert_hyperlink`/`doc_set_hyperlink`） | Word #13 | **已实现**（波次 A/B）：`doc_set_hyperlink`→`set_hyperlink_at_anchor` |
| 中 | 脚注/尾注（`doc_insert_footnote`） | Word #15 | **已实现**（波次 A/B）：`doc_insert_footnote`/`doc_insert_endnote` |
| 中 | Excel 数据验证（`sheet_set_data_validation`） | Excel #19 | **已实现**（波次 C）：list/wholeNumber/decimal/date/time/textLength/custom |
| 中 | Excel 图表（`sheet_add_chart`） | Excel #15 | **已实现**（波次 C）：`XTableCharts`，基础形态（建图表+选类型+标题） |
| 中 | 图片插入 AI 原语（把 `insert_image` 从 host-initiated 扩展出 AI 可调用分支） | Word #14 | **已实现**（波次 A/B）：`doc_insert_image` |
| 中 | Excel 专用搜索原语（`sheet_search`） | Excel #3 | **已实现**（波次 C） |
| 低 | 样式应用（`doc_set_style`） | Word #17 | **已实现**（波次 A/B）：`doc_set_style`→`set_style` |
| 低 | Excel 透视表（`sheet_add_pivot_table`） | Excel #16 | **已实现**（波次 C）：`XDataPilotTables`，仅「行分组+求和」基础形态 |
| 低 | Excel 命名区域/保护/分组 | Excel #18/21/22 | **已实现**（波次 C）：`sheet_define_name`/`sheet_protect_sheet`/`sheet_group_rows_cols` |
| 观察 | PPT 实时编辑桥（新建 `doc_ppt_*`/Impress 桥，对齐插件 10 + 待补 3 = 13 个 command 的能力面） | PPT 全表 | 大（需要新机制：Impress UNO 实时光标编辑桥，参照 doc_*/sheet_* 架构重新做一遍） | 中（产品判断，见存疑判定 3）——**仍未排期，波次 C 未涉及** |

桌面端 15 条候选中 14 条已在波次 A/B（Word）与波次 C（Excel）落地，仅剩 PPT 实时编辑桥（观察项，需产品先拍板架构方向）未排期。

---

## 五、最值得复核的三个判定

1. **PPT 桌面端是否要建实时编辑桥，还是继续走批量文件重写路线**（判定原则 5 / PPT 全表前提）。这不是一个能力项缺口，是架构分岔：`PptxTools` 的批量机制已经覆盖了增删页、文本、格式、表格单元格读写这些核心诉求，代价是每次编辑都要整个文档重新加载，无法像 Word/Excel 那样逐字打修订、光标可见地增量编辑。如果法律场景对 PPT 的诉求主要是「生成初稿 + 偶尔改文字/表格数据」，现状够用；如果未来要支持「AI 逐步优化排版、用户实时看到光标在哪」，那批量机制在架构上做不到，必须新建 Impress 实时编辑桥（大工程，比照 doc_*/sheet_* 两套基建重新做一遍）。这条判定的分量最重，建议维护者先拍板产品方向，再决定 PPT 桌面端待办清单里那条「观察」项要不要真的排期。

2. **Excel 单元格批注是否真的该跟 Word 批注同等优先级**。矩阵里判定「两侧都补，高优先级」，理由是「与 Word 批注类比」。但需要复核的是：法律场景里 Excel 批注的实际使用频率是否真的等同于 Word 批注——尽调底稿类文档确实常见批注核对数字，但如果多数场景数字核对靠的是「改单元格颜色 + 口头沟通」而不是插批注，那这条的优先级可能要往下调，不是稳赢的高优先级判定。

3. **「域」「书签」「文档属性」「命名区域」「内容控件」这一批全部判「低价值不补」是否太武断**。这次判定的共同逻辑是「本产品未走模板域填空路线」，但这个前提本身没有在本次调研范围内验证——如果 HR 模板包（`hr-template-pack-v2`）或未来的「智能填表」类插件场景需要动态域或内容控件绑定数据，这批判定就要整体推翻。建议维护者结合插件系统（plugin-system 领域）里是否有模板填空类需求规划，再确认这批「低价值」判定是否成立。
