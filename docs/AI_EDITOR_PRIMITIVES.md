# AI ↔ LibreOffice 拟人式动作原语协议

> 目标：让 AI panel 像一个坐在文档前的人类编辑那样操作嵌入式 LibreOffice——移动光标、选中、
> 修改、排版，用户全程看得见。本文是原语集的设计依据与协议契约。
>
> 关联：docs/LIBREOFFICE_MIGRATION_PLAN.md（RFC v2，§0.2 锚点论）、frontend/src/zetaoffice/README.md（管线架构）。

## 1. 为什么 WPS 时代"找不到 / 替换错"

| 病根 | 机制 | 后果 |
|------|------|------|
| 整数偏移定位 | JS 纯文本 `indexOf` 算出的 offset 映射到富文本 Range | 分页符/隐藏字符/表格标记不计入纯文本，映射系统性错位 → **替换错位置** |
| 匹配无上下文 | 查找只返回位置数字 | 5 个相同的"甲方"无法分辨，AI 只能猜 → **改错对象** |
| 无验证回路 | 改完不返回改动处新状态 | AI 盲飞，错了也不知道 → **错误累积** |
| 修订模式冲突 | AI 操作需关闭修订 | 用户分不清 AI 改了什么 |

## 2. 设计原则

1. **锚点，不是偏移（§0.2）**：定位一律用书签锚点（`anchorId`），随文档编辑自动跟随；worker 显式拒绝整数偏移。
2. **拟人式五步循环**：看 → 找 → 选 → 改 → 验。AI 的每一步都对应人类编辑的一个动作。
3. **操作可见**：选中/跳转通过视图光标完成（`gotoRange`），编辑器滚动到目标、选区高亮——用户看得见 AI 的"手"在哪。
4. **修订默认开**：`RecordChanges=true` 在文档 boot/load 时统一设置，所有改动都是可接受/拒绝的修订痕迹。
5. **每次改动带验证快照**：改动类命令返回 `paragraphAfterEdit`（改后所在段落实文），AI 据此核对；错了用 `undo` 退回。
6. **最小原语 + 组合**：不做"在 X 后插入 Y"这类复合命令；用 `选中 X → collapse 到 end → 插入 Y` 组合，语义清晰且可视。

## 3. 原语清单（worker `office_thread.js` EXEC 契约）

### 看（感知）
| action | 参数 | 返回 |
|--------|------|------|
| `get_document_text` | `{startParagraph?, maxParagraphs?}` | 带编号段落列表（含标题级别），长文分页（`truncated`/`nextStartParagraph`），单次 ≤15k 字符 |
| `get_cursor_context` | `{radius?}` | 选中文本、光标前后文、所在段落 |
| `get_outline` | — | 标题层级列表 |
| `get_selection` | — | 当前选区文本 |
| `find_text_locations` | `{keyword, matchCase?}` | 每个匹配：`anchorId` + `contextBefore/After`(40字) + `paragraph`(160字) + `matchIndex`，≤200 个 |

### 移（定位，用户可见）
| action | 参数 | 说明 |
|--------|------|------|
| `set_selection` | `{anchor}` | 选中锚点范围，视图滚动跟随；返回选中文本+上下文 |
| `select_paragraph` | `{index}` | 选中第 N 段（0 起） |
| `collapse_selection` | `{to:'start'\|'end'}` | 光标落到选区边缘（"之前/之后插入"的前置动作） |
| `goto` | `{type:'start'\|'end'}` | 文档头尾 |
| `move_cursor` | `{dir, extend?}` | 单步移动（IME 层也在用） |

### 改（编辑，全部落修订痕迹）
| action | 参数 | 验证返回 |
|--------|------|---------|
| `insert_at_cursor` | `{text}` | `paragraphAfterEdit` |
| `replace_selection` | `{text}` | `paragraphAfterEdit` |
| `replace_at_position` | `{anchor, newText}` | `paragraphAfterEdit` |
| `delete_selection` | — | `deletedText` + `paragraphAfterEdit`；无选区时报错（先选再删） |
| `insert_paragraph` | — | 段落分隔符 |
| `find_replace` / `replace_nth_match` / `delete_match` / `delete_text` / `modify_paragraph` | （沿用） | modify_paragraph 增加 `paragraphAfterEdit` |
| `undo` / `redo` | `{steps?}` | 撤销/重做 + 快照 |

### 饰（格式，先选中再作用于选区）
| action | 参数 | 说明 |
|--------|------|------|
| `format_selection` | `{bold?, italic?, underline?, strikeout?, highlight?, color?, fontSize?, fontName?}` | 只传要改的参数；`highlight` 支持色名/`#RRGGBB`/`none`；CJK 同步设置 `*Asian/*Complex` 属性 |
| `set_paragraph_format` | `{alignment?, styleName?, headingLevel?}` | `headingLevel` 1-9 → `Heading N`（programmatic 名，与 UI 语言无关），0 → `Standard` |

## 4. 全链路

```
LLM @Tool (WpsTools.java)
  → AgentOrchestrator.executeNativeTool（手工分发表）
  → WpsActionService.executeWpsCommand → SSE client_action
  → useAgentStream → ChatInterface → project-overview.handleWpsCommand
  → (LibreOffice 激活) libreofficeExecutorClient → relay → webview
  → office_thread.js EXEC[action]（UNO） → 结果原路回传 → LLM 核对
```

新增后端工具（与 worker action 的映射）：

| @Tool | worker action |
|-------|---------------|
| `wps_get_document_text` | `get_document_text` |
| `wps_get_cursor_context` | `get_cursor_context` |
| `wps_select_anchor` | `set_selection` |
| `wps_select_paragraph` | `select_paragraph` |
| `wps_collapse_cursor` | `collapse_selection` |
| `wps_replace_at_anchor` | `replace_at_position` |
| `wps_delete_selection` | `delete_selection` |
| `wps_format_selection` | `format_selection` |
| `wps_set_paragraph_format` | `set_paragraph_format` |
| `wps_undo` / `wps_redo` | `undo` / `redo` |

提示词（`prompts/system_prompt.md` §7）已整节重写为拟人循环 + 消歧规范 + 验证规范。

## 5. 修订模式下的读数口径（重要）

`RecordChanges=true` 且显示修订时，被"删除"的文本仍留在文档流中（带删除线），因此：
- `get_document_text` / `paragraphAfterEdit` 会同时包含删除痕迹与新文本（与用户屏幕所见一致）；
- 替换后核对时应检查**新文本已出现**，而不是旧文本已消失；
- 用户接受修订后读数即恢复"干净"。

## 6. 自测

自动化端到端验证（真实 LOWA 引擎 + 无头浏览器驱动 `window.__loExecutor`，verify 模式暴露）：
构建 `npm run build:zetaoffice` → COOP/COEP 本地服务（bundle + 自托管引擎）→
`editor.html?verify=1&lowa=/lowa/` → 逐条原语断言。结果见 PR 描述。
