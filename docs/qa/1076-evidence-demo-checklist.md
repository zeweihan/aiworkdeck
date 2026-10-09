<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# #1076 证据闭环演示 · 录屏操作清单 + 证据账本调查

> 材料：`fixtures/evidence-demo-1076/`（纯虚构，只导入 4 份 .docx）。脚本 v0.1 由增长维护。
> 录屏由增长负责；录屏人与机器由增长 / 总经理定。本清单只保证路径可复现。
> ⚠️ 状态：本清单基于 master `c598f27` 的**代码阅读**，未在运行中的应用上跑通（见 §6）。

## 1. 结论先说：导入的项目文件**不会**进入 `retrieve_evidence` 的证据账本

| # | 结论 | 依据（master `c598f27`） | 确认度 |
|---|---|---|---|
| 1 | 本地证据源 `MemoryEvidenceRetriever` 只读记忆条目 `MemoryEntry`，经 `MemoryManager.retrieveMemories` 做关键词 LIKE 检索 | `backend/src/main/java/com/checkba/service/ai/evidence/MemoryEvidenceRetriever.java:58`；`service/ai/memory/MemoryManager.java:202-210, 258-267`；`repository/MemoryEntryRepository.java:73` | 已确认 |
| 2 | `MemoryEntry` 在主代码里只有两个写入者：模型调用 `save_memory`，以及记忆 Git 同步导入 | `service/ai/tools/MemoryTools.java:84, 121-134`；`version/memory/MemorySyncService.java:428-463` | 已确认 |
| 3 | 对话后台自动抽取已不再写 `MemoryEntry`；`MemCellExtractor.extractAndSave` / `ProjectMemoryExtractor.extractMemoryEntries` 在 main 中无调用方 | `service/ai/memory/MemoryPipelineService.java:98-99`（注释「后台不再并行运行正则与 LLM 抽取写者」）；`MemCellExtractor.java:449` | 已确认（grep 无调用方） |
| 4 | 文件上传 / 导入路径不写 `MemoryEntry` | 上面 #2 是全部写入者（grep `MemoryEntry.builder()` / `new MemoryEntry()` / `saveMemory(`） | 已确认（静态） |
| 5 | 即使有条目，定位符也是 `memory_entry#<id>`，不是「第 8.2 条」；`sourceUri=checkba://file/<id>` 仅在 `save_memory` 带了 `sourceFileId` 时出现 | `MemoryEvidenceRetriever.java:90-98, 109` | 已确认 |
| 6 | 前端没有 `checkba://file/<id>` 的点击处理，只处理 `checkba://filelink?k=…`（EvidenceLink） | `frontend/src/utils/evidenceLocator.js:7, 51`；grep `checkba://file/` 在 `frontend/src` 无命中 | 已确认（静态） |
| 7 | `retrieve_evidence` 不在核心工具集，属 `evidence` 类目，需模型查目录 / Skill 收窄后才可见 | `service/ai/ToolDisclosurePolicy.java:188-191` | 已确认 |

**含义**：新项目导入 4 份材料后直接问「梳理违约风险点并附证据」，`retrieve_evidence` 大概率返回「未检索到相关证据」
（`service/ai/tools/EvidenceTools.java:117-131`），不能作为演示主路径。

## 2. 可行的「点回原文高亮」路径（代码层面存在，未实跑）

1. 模型用核心工具读材料：`search_project_content` / `extract_file_text`（`ToolDisclosurePolicy.java:119`）。
2. 模型把风险点写进一份**报告 docx**（`write_docx`），在报告里每条事实后调用 `doc_link_evidence`
   （`service/ai/tools/DocumentEditTools.java:1957-1971`），target 用 `{path:"02-补充协议.docx", locator:{type:"docx", quote:"2026 年 3 月 31 日前"}}`。
   关系只支持 `supports / contradicts / partial`（`DocumentEditTools.java:1987`）——**没有 missing_evidence**。
3. 律师点报告里的证据超链接或证据面板「查看底稿」→ `openFileLinkTarget`
   （`frontend/src/pages/project-overview/evidenceLinkActions.js:166-195`）→ 目标标签挂 `pendingLocator`
   → `LibreOfficeEditor.consumeLocator`（`frontend/src/components/LibreOfficeEditor.vue:629-647`）：
   docx 定位为 `find_text_locations(quote)` 取**第一处**命中后 `set_selection`。
   所以 quote 必须在目标文件中唯一——这也是把合同 8.1 改成万分之三的原因（「万分之五」在合同中只剩 8.2）。
4. 「缺付款凭证」只能以报告正文写明（不建链接），不是结构化 `missing_evidence` 对象。

## 3. 前置

- [ ] 与 master 同版本的桌面构建（构建号由产品工程 10/12 前给增长；见 §6）
- [ ] 演示账号登录；画面中不出现真实 API key、真实当事人、调试面板 / DevTools
- [ ] 勿扰模式；隐藏桌面杂物
- [ ] 界面语言简体中文；录制 1920×1080（或 2560×1440 输出 1080p）；编辑器 100–125%

## 4. 准备示范项目

- [ ] 新建项目「示范 · 设备购销纠纷（虚构）」
- [ ] 只导入 `fixtures/evidence-demo-1076/` 下 4 份 .docx（不要导入 `source-md/`）
- [ ] 逐份打开确认显示正常、`第X条` 标题可见
- [ ] 彩排一次：确认 AI 产出报告 docx 并成功建 4 条以内证据链接（§2），再正式录

## 5. 分镜（对齐脚本 v0.1）与核对

| # | 时间 | 操作 | 检查 |
|---|---|---|---|
| 1 | 0–8s | 展示左侧 4 份材料 | 文件名可读 |
| 2 | 8–20s | AI 输入：「梳理对方违约的风险点，每条附证据，写成报告并把每条结论链接到原文」 | 提示词需在彩排时定稿 |
| 3 | 20–40s | 等待报告生成 | 每条 supports 结论都有证据超链接；「缺付款凭证」明确写出；**无** contradicts |
| 4 | 40–58s | 点「卖方逾期交货 18 天」的证据链接 | 打开 02 补充协议并选中「2026 年 3 月 31 日前」，或 03 签收单「2026 年 4 月 18 日」 |
| 5 | 58–70s | 指向「买方是否已付全款 · 缺付款凭证」 | 表述为缺证据，不是矛盾 |
| 6 | 70–85s | 收尾卡（后期） | 试用措辞只写「以协议为准」 |

期望命中：

| 结论 | 关系 | 定位（quote 建议） |
|---|---|---|
| 卖方逾期交货 18 天 | supports | 02 第二条「2026 年 3 月 31 日前」；03「2026 年 4 月 18 日」 |
| 卖方自认延迟 | supports | 04「对此延迟我司表示歉意」 |
| 逾期交货违约金按日万分之五 | supports | 01 **第 8.2 条**「万分之五」（8.1 已改为万分之三，引 8.1 即为错） |
| 买方是否已付全款 | 缺证据（正文写明） | 01 第 5.1 条只约定付款义务；无支付凭证 |

不合格即重录：无证据的 contradicts；结论无链接却上屏；跳转后未选中或跳错文件；引到 8.1；画面出现真实信息 / 密钥 / 调试面板。
跳转选中若不稳定：先录可用部分，初版标注后补（增长已同意）。

## 6. 未确认（录屏前必须在运行中的应用上关闭）

1. 当前 master 构建上，模型是否会自发调用 `doc_link_evidence`（需查目录）——或需要一个 Skill / 提示词显式要求。现有 Skill 未声明 `evidence.retrieve.v1`。
2. `find_text_locations` + `set_selection` 是否有可见高亮（选区）且滚动到位。
3. pandoc 生成的 .docx 在 LOWA 中的显示与 `extract_file_text` 抽取是否正常。
4. 可录构建：仓内无可直接录屏的发布构建记录；需从 master 打包或用与 master 同版本的 release。
