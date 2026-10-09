<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# #1076 证据闭环演示 · 录屏操作清单 + 证据账本调查

> 材料：`fixtures/evidence-demo-1076/`（纯虚构，只导入 4 份 .docx）。脚本 v0.2 由增长维护。
> 录屏由增长负责；录屏人**默认不是 Zewei**，录制环境按 §3 的顺序试。本清单只保证路径可复现。
> ⚠️ 状态：本清单基于 master `c598f27` 的**代码阅读**，未在运行中的应用上跑通（见 §7）。

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

## 3. 录制环境（录屏人默认不是 Zewei）

按顺序试，前一档跑通就停：

1. **总经理电脑的 Linux 桌面，从源码起桌面端**（`./restart-all.sh`：侧车 + 后端 :9696 + H5 :5173 + Electron）。
   注意：CI 只出 macOS / Windows 安装包（`.github/workflows/desktop-build.yml:62`；`desktop/package.json` 只有 dmg / nsis 目标），
   Linux 上没有现成安装包，源码栈在 Linux 上能否起 Electron **未验证**。
2. **H5（Web 服务器版）构建**，按 `deploy/web/README.md`：`build:h5` + `build:zetaoffice` + `fetch-lowa-assets` + 后端 jar，
   nginx 必须下发全站 COOP/COEP（`deploy/web/nginx.conf.example:31-32`），否则 LOWA 起不来。限制见 §6：
   - 拖入导入、按文件夹建项目**仅桌面端**；浏览器里只能用对话框「+」附件把 4 份 .docx 传进项目；
   - 代码注释称工作台 H5 部署已于 2026-08-19 下线（`frontend/src/components/FileTree.vue:3043-3044`），这条路没有日常回归。
3. **以上都不行，确实需要桌面客户端（macOS 安装包）时**，报总经理，由总经理决定是否请 Zewei 录或借机器。不要直接找 Zewei。

通用要求：

- [ ] 与 master 同版本的构建（构建号由产品工程 10/12 前给增长）
- [ ] 演示账号登录；画面中不出现真实 API key、真实当事人、调试面板 / DevTools
- [ ] 勿扰模式；隐藏桌面杂物、浏览器书签栏 / 地址栏（H5 档用浏览器全屏 / kiosk）
- [ ] 界面语言简体中文；录制 1920×1080（或 2560×1440 输出 1080p）；编辑器 100–125%

## 4. 准备示范项目

- [ ] 新建项目「示范 · 设备购销纠纷（虚构）」
- [ ] 只导入 `fixtures/evidence-demo-1076/` 下 4 份 .docx（不要导入 `source-md/`）
      ——桌面端拖进文件树；H5 档用对话框「+」选这 4 份
- [ ] 逐份打开确认显示正常、`第X条` 标题可见
- [ ] 彩排一次：确认 AI 产出报告 docx 并成功建证据链接（§2）；在「底稿」面板核对
      「卖方逾期交货 18 天」那条链接指向 **03 签收单**、引文为「签收日期 2026 年 4 月 18 日」，再正式录

材料定位前提（已在 .docx 里核过，见 §7 证据）：

- 合同「万分之五」只在 **8.2**（乙方逾期交货）；**8.1**（甲方逾期付款）是**万分之三**。引到 8.1 即为错引，一眼可见。
- 签收单「签收日期 2026 年 4 月 18 日」是**一整段**，全文恰好出现 1 次（原稿是表格两格「签收日期」|「2026 年 4 月 18 日」，
  `find_text_locations` 用 UNO 查找，不跨表格单元格，整句会 0 命中——所以演示副本把这一行移出表格，见 fixtures README）。

## 5. 分镜（对齐脚本 v0.2）与核对

| # | 时间 | 操作 | 检查 |
|---|---|---|---|
| 1 | 0–8s | 展示左侧 4 份材料 | 文件名可读 |
| 2 | 8–20s | AI 输入：「梳理对方违约的风险点，每条附证据，写成报告并把每条结论链接到原文」 | 提示词需在彩排时定稿 |
| 3 | 20–40s | 等待报告生成 | 每条 supports 结论都有证据超链接；「缺付款凭证」在正文里写明；**无** contradicts |
| 4 | 40–58s | **只点**「卖方逾期交货 18 天」的证据链接：Ctrl+点击（macOS 为 Cmd+点击；普通单击只放光标）→ 弹出预览浮窗 → 点「03-签收单」 | 另一侧分屏打开 03 签收单，并**选中**「签收日期 2026 年 4 月 18 日」（这句全文唯一，不会跳到别处） |
| 5 | 58–70s | 回到 AI 报告正文，鼠标划过「缺付款凭证」那段文字 | 报告**正文**明说缺付款凭证（合同 5.1 只约定付款义务，材料里无支付记录）；这段**没有链接**、不点；表述为缺证据，不是矛盾 |
| 6 | 70–85s | 收尾卡（后期） | 试用措辞只写「以协议为准」 |

期望命中：

| 结论 | 关系 | 定位（quote 建议） |
|---|---|---|
| 卖方逾期交货 18 天 | supports | **03「签收日期 2026 年 4 月 18 日」**（第 4 镜点这条）；可另挂 02 第二条「2026 年 3 月 31 日前」 |
| 卖方自认延迟 | supports | 04「对此延迟我司表示歉意」 |
| 逾期交货违约金按日万分之五 | supports | 01 **第 8.2 条**「万分之五」（8.1 为万分之三，不得引 8.1） |
| 买方是否已付全款 | 缺证据（正文写明，无链接） | 01 第 5.1 条只约定付款义务；无支付凭证 |

不合格即重录：无证据的 contradicts；supports 结论无链接却上屏；跳转后未选中或跳错文件；引到 8.1；
「缺付款凭证」被做成链接或写成矛盾；画面出现真实信息 / 密钥 / 调试面板。
跳转选中若不稳定：先录可用部分，初版标注后补（增长已同意）。

## 6. H5（浏览器）能否走通：代码调查（master `c598f27`，未实跑）

| 环节 | 结论 | 依据 | 确认度 |
|---|---|---|---|
| 编辑器容器 | 浏览器挂同源 `<iframe>`（桌面是 Electron `<webview>`）；relay / executor / 自动保存共用 | `frontend/src/services/host.js:36-43, 80-85`；`frontend/src/components/LibreOfficeEditor.vue:1065-1080, 1123-1134` | 已确认（静态） |
| 编辑器是否启用 | 先 HEAD 探 `/zetaoffice/editor.html`，没部署就退回只读预览 | `host.js:48-56`；`frontend/src/pages/project-overview/project-overview.vue:4120-4131` | 已确认（静态） |
| LOWA 跑起来的前提 | 全站 COOP/COEP（SharedArrayBuffer）；dev server 上没有 `/zetaoffice/` | `deploy/web/nginx.conf.example:4-32`；`host.js:31-34` | 已确认（静态）；能否渲染这 4 份 docx **未知** |
| 文档字节 | 宿主用 HTTP 拉 `getFileBytesUrl`，与壳无关 | `LibreOfficeEditor.vue:1584` | 已确认（静态） |
| (a) 导入 .docx | 文件树拖入走 `host.fs.getPathForFile` + import-local，浏览器里 `host.fs` 缺席 → 提示「拖入导入仅桌面端支持」；对话框「+」在纯浏览器走「建行 + 字节直传」`POST /api/files/{id}/upload` | `FileTree.vue:3058-3064, 3082-3085`；`frontend/src/components/ChatInterface.vue:3441-3456, 3684-3703, 3789`；`backend/.../controller/FileController.java:365` | 已确认（静态）；实际上传结果**未知** |
| (b) 浏览器内 LOWA 渲染 | 代码通路存在 | 同上「编辑器容器 / 前提」 | **未知**（未部署未实跑） |
| (c1) AI 建链 | `doc_link_evidence` 经 `EditorBridgeService` 发 SSE 让前端在线编辑器执行 `find_text_locations` / `bookmark_selection` / `set_selection_hyperlink`，报告必须在编辑器里打开；SSE 通路与壳无关 | `backend/.../service/ai/tools/DocumentEditTools.java:1957-2015`；`backend/.../service/evidence/EvidenceAnchorService.java:52-83`；`backend/.../service/ai/EditorBridgeService.java:28-33` | 已确认（静态） |
| (c2) 点链接跳转 | 画布里 Ctrl/Cmd+点击 → `get_hyperlink_at_cursor` → relay `open-url` → 预览浮窗 → 选目标 → `openFileLinkTarget` 分屏打开并挂 `pendingLocator` | `frontend/src/composables/zetaOfficeLinkClick.js:20-46`；`frontend/src/zetaoffice/editor-main.js:183-196`；`frontend/src/pages/project-overview/librePool.js:411-412`；`documentLinkPreview.js:6-29, 41-50`；`evidenceLinkActions.js:166-195` | 已确认（静态） |
| (c3) 选中引文 | `consumeLocator`：书签优先，否则 `find_text_locations(quote)` 取**第一处**命中 → `set_selection`；UNO 查找不跨表格单元格 | `LibreOfficeEditor.vue:629-650`；`frontend/src/zetaoffice/public/office_thread.js:4144-4153` | 已确认（静态）；选区是否可见、是否滚动到位**未知** |
| relay 传输 | `pickTransport`：有 `zetaHostBridge`（webview preload）用它，否则走 `postMessage` 且钉死同源 | `editor-main.js:47-80` | 已确认（静态） |

这条路上的 **Electron 专属 API**：`host.fs.getPathForFile` / import-local（拖入导入）、`host.fs.showOpenDialog`（按文件夹建项目）、
`<webview>` + `window.zetaHostBridge` preload（浏览器里换成 iframe + postMessage）、LOWA 备胎预热只在桌面
（`librePool.js:177`，浏览器首次开文档更慢，引擎冷启动 90 秒级）。**跳转 / 选中 / 建链本身没有 Electron 专属调用。**

**H5 结论**：代码上 (a) 只能经对话框「+」、(b)(c) 有通路，但 H5 工作台已不在日常回归内，且
`deploy/web/README.md` 仍写「iframe bridge 尚未实现、编辑器在 Web 版不可用」，与现有代码不符（文档陈旧，或有未知缺口）。
**不能凭代码承诺 H5 可录，必须实跑一次。**

## 7. 未确认（录屏前必须在运行中的应用上关闭）

1. 当前 master 构建上，模型是否会自发调用 `doc_link_evidence`（需查目录）——或需要一个 Skill / 提示词显式要求。现有 Skill 未声明 `evidence.retrieve.v1`。
2. `find_text_locations` + `set_selection` 是否有可见高亮（选区）且滚动到位。
3. pandoc 生成的 .docx 在 LOWA 中的显示与 `extract_file_text` 抽取是否正常。
4. 可录构建：仓内无可直接录屏的发布构建记录；需从 master 打包或从源码起。
5. H5 档：部署 + 登录 + 「+」上传 4 份 docx + LOWA 渲染 + Ctrl+点击跳转选中，整条未实跑（§6）。
6. Linux 上从源码起 Electron 桌面端未验证。

已核（.docx 抽段落文本计数，`fixtures/evidence-demo-1076/*.docx`）：01「万分之三」1 / 「万分之五」1；02「2026 年 3 月 31 日前」1；
03「签收日期 2026 年 4 月 18 日」1、「2026 年 4 月 18 日」1；04「对此延迟我司表示歉意」1。
