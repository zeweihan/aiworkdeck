<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# prompts/ 维护说明

本目录的 `system_prompt*.md` 与 `tools-*.md` 会被 `ContextAssemblerService` **原样**读进系统提示。
本文件不会被加载，维护者说明一律写在这里，不要写进被加载的文件里。

## 文件

| 文件 | 何时加载 |
|---|---|
| `system_prompt.md` / `system_prompt.en.md` | 基底提示，按应用语言二选一（en 缺失时回退中文版） |
| `tools-lowa*.md` | 桌面编辑器（LOWA）会话，含未登记能力的兜底会话 |
| `tools-office-word*.md` / `tools-office-excel*.md` / `tools-office-ppt*.md` | Office / WPS 任务窗格，按宿主分 |
| `tools-none*.md` | 没有文档编辑执行器的纯对话会话 |

能力到片段文件名的唯一映射是 `ContextAssemblerService.toolGuidanceStem`。片段拼在基底里的
`<!-- awd:tool-guidance -->` 占位处（`spliceToolGuidance`）；**ASK 模式不拼任何片段**，占位换成空串。

## 规矩

1. **不要在被加载的文件里写 HTML 注释给维护者看。** 加载时不剥注释，注释会原样发给模型，
   每轮全价计费。只有两处例外：占位标记 `<!-- awd:tool-guidance -->`，以及基底里示例
   `<!-- STOP. Wait for tool_output. ... -->`（那是写给模型看的示例的一部分）。
2. **中英两版逐条对应。** 协议面（XML 标签、工具名、停机条件、输出顺序、编排器契约）两版完全一致，
   只有措辞与法域表述不同：中文版以内地法为默认专长、但「适用法域以文档为准」；英文版
   jurisdiction-neutral，`law_*` 只用于受内地法管辖的事项。改一版必须同步另一版。不用 emoji。
3. **基底只写与客户端能力无关的规则。** doc_* / sheet_* / slide_* / office_* / pptx_* / pdf_* / ref_*
   以及声明了 `@ToolMeta.requiresHost` 的工具，指引只能写进对应的 `tools-*.md` 片段。
4. **基底与 enforcement 不重复。** `ContextAssemblerService` 里的 `# SYSTEM ENFORCEMENT` 段与
   模式约束排在基底之后，本仓实证更靠后的那份才管用；两边都有的规则只留 enforcement 那份。
5. **per-tool 判据写在工具描述里。** 某个工具「什么时候用 / 别怎么用」的判据，先并进它的 `@Tool`
   描述，再从提示里删；提示里只留跨工具的取舍。
6. **一切稳定。** 这些文件整段属于提示缓存的稳定前缀，写进任何每轮会变的内容（时间、id、
   现查的记忆）都会让缓存永久失效且不报错。

## 守着这些文件的测试

- `SystemPromptToolVisibilityContractTest`：片段点名的工具在那一档可见；基底不点名挑客户端的工具；
  基底反引号里的名字要么是真下发的工具，要么在 `NON_TOOL_IDENTIFIERS` 里点名；签名与示例的参数名
  必须在下发 schema 里。
- `ContextAssemblerServiceTest` / `ContextAssemblerAskUserTest`：若干字串按位置钉住（Clarification、
  停机条件、ASK 约束等）。
- `FileToolsBatchMoveTest` / `FileToolsMoveToTrashTest`：两版基底要点名文件树原语与 `move_to_trash`。
- `MatchIndexBaseTest` / `NumberingRemovalContractTest` / `RedlineGranularityContractTest`：`tools-lowa*.md` 的判据句。
- `SystemPromptSizeReportTest`：五档会话 × 两种语言的体量报告，改完对账用。
