# 插件生态战略：对标 VS Code 的开放能力路线（dev-board#275）

> 2026-08-29 立项。维护者定位：**IDE for coders, AI WorkDeck for doc-ers**——不止法律，
> 所有以文档为最终输出的知识工作者（律师、会计师、金融分析师、文员……）的工作台，
> 目标是与 VS Code 并驾齐驱的插件生态。本文是生态开放路线的权威源；桥协议细节见
> [PLUGIN_SPEC.md](PLUGIN_SPEC.md)。调研出处：code.visualstudio.com/api（capabilities
> overview / custom-editors / virtual-documents / ai/chat / publishing）、microsoft/vscode
> wiki「Extension API process」、DAP/LSP 官方资料，2026-08-29 查证。

## 1. VS Code 为生态开放了什么（调研结论）

### 1.1 三个真正的核心资产

VS Code 让渡给插件的不是「UI 挂件」，而是三样核心资产——这是生态能长出「与官方
功能同深」的扩展的根源：

1. **文档读写权**：TextDocument 模型 + decorations/CodeLens/hover——插件能在正文里
   画下划线、挂行内按钮、改内容，和内置功能无差别。
2. **存储抽象权**：FileSystemProvider（完整虚拟文件系统：远程/云/内存盘）、
   TextDocumentContentProvider（虚拟只读文档）、CustomEditorProvider（**非文本文件的
   编辑器接管权**——图片/十六进制/Notebook 都是这么来的）。
3. **AI 调用权**（新一代）：`vscode.lm`（插件免带 Key 调用用户已授权的模型）+
   Chat Participant（插件注册 `@参与者` 进对话面板，流式输出、slash command、工具调用）。
   GitHub Copilot Chat 本身就是用这套**公开** API 写的扩展。

### 1.2 四层架构

| 层 | 是什么 | 战略意义 |
|---|---|---|
| 声明层 | package.json 约 30 种 contribution points（commands/menus/themes/languages/snippets…） | 不激活插件即可渲染 UI/主题/语法，启动零成本；主题能覆盖插件 UI 的前提 |
| 运行层 | `vscode.*` 约 15 个命名空间 + activation events 懒加载 | 装了 ≠ 一直跑 |
| 协议层 | **LSP / DAP / Notebook 协议** | 语言服务写一次、全生态编辑器可用——用「开放协议」换来几十种语言的深度支持，反过来巩固自己的生态位。杠杆最大的一层 |
| 宿主层 | Extension Host 独立进程 | 插件崩溃/卡死不碰主 UI、不丢未保存内容 |

### 1.3 生态基建与运营

- **市场**：免费发布、`vsce` 自动扫描为主、无事前人工重审，靠「验证发布者」徽章
  （域名 DNS 验证）与事后下架兜底——**低门槛起量，信誉后置**。
- **开发者体验**：`yo code` 脚手架、官方 samples 仓逐能力给参考实现、每个能力配
  Extension Guide、workspace recommendations、extension packs。
- **稳定性三件套**：proposed API 隔离（禁上市场）→ finalization 评审 → 只加不改 +
  `engines.vscode` 版本声明。十几年不破坏兼容靠的是流程，不是天赋。
- **官方吃自己狗粮**：Python/C++/Copilot 全是走公开 API 的普通市场扩展——不留私有
  超级 API，第三方才相信「官方能做的我也能做」。
- 规模感：10 万+ 扩展、MAU ~3600 万；语言支持/片段/格式化/主题类占大头（轻量声明式
  插件是生态长尾的主力形态）。

## 2. AI WorkDeck 的对位资产与现状

我们的核心资产恰好能一一对位，其中三样是 doc-er 世界里 VS Code 没有的差异化资产：

| VS Code 开放点 | 我们的对位资产 | 现状 |
|---|---|---|
| TextDocument + decorations | **文档编辑原语**（doc_*/sheet_*/slide_*/pdf_* 全集 + 修订/批注/书签） | 已有，但只开放给 AI 编排与自家前端，未开放给插件 |
| lm.* + Chat Participant | **AI 编排 + Skill 体系 + 平台 Credits 通道** | Skill 已是插件形态之一；插件`chat.send`/`tools.invoke` 已通；「插件免带 Key 用平台 AI」的通道未开 |
| FileSystemProvider / LSP | **外部数据源检索**（企查查/法宝/判决书通道，evidence.retrieve.v1 已是 MCP 传输的协议）| 协议雏形在，未开放第三方接入 |
| CustomEditor | 尽调工作台这类结构化面板（Web 插件 iframe） | v2.3 起有，sandbox 桥 12 方法 |
| themes / webview 主题注入 | **v2.6 已对齐**（themeTokens 注入 + theme 推送，本轮落地） | 完成 |
| Extension Host 隔离 | Web 插件 sandbox iframe（权限=真实边界）；JAR 插件同 JVM 同权限 | Web 形态已隔离；JAR 形态无隔离，靠签名 |
| languages/snippets 贡献点 | **文书体裁/模板/格式规范（HOUSE）声明式注册** | 无 |
| 市场 | 广场 + Ed25519 签名 + 双镜像 + dev 免签直装 + 社区基金 20%/Skill 分成 15% | 已有，分发链完整 |
| engines.vscode | manifest 无 minHostVersion | 缺 |
| proposed API | 无实验隔离机制 | 缺 |
| 事件系统（onDid*） | 插件感知不到文件/选区/项目变化 | 缺，最大运行层缺口 |
| evidence.* 底稿链 | —（VS Code 无对应物） | **我们的差异化资产**，已开放 v1 |

## 3. 生态路线（按「开放三个核心资产」组织）

原则：**抄机制不抄规模**——声明式优先、协议层杠杆、只加不改、实验隔离、官方功能
插件化（狗粮）。插件作者画像是「专业人士 + AI 写插件」，API 面要 AI 一次能写对。

### P0 治理地基 ——【已落地，规范 v2.7，dev-board#280】
设计定稿：`docs/superpowers/specs/2026-08-29-plugin-p0-governance-foundation.md`。
- manifest `minHostVersion`（PLUGIN_SPEC §2）：不达标登记不生效 + 装前拦 + 管理页提示升级；
- 实验 API：`x-` 前缀桥方法，运行时只对 dev 免签直装插件放行（§12.4）；
- 「只加不改」章程 + 四处同步纪律固化为 PLUGIN_SPEC §12。

### P1 开放文档读写权 ——【已落地，规范 v2.7，dev-board#281】
设计定稿：`docs/superpowers/specs/2026-08-29-plugin-p1-doc-api-and-events.md`。
- 桥 `doc.exec`/`doc.active`（§8.4）：白名单 = 宿主 SPI DOC_ACTIONS 同一份清单（JAR 与
  Web 插件同一张能力面），写入带修订署名，executor 层 EDITOR_ACTIONS 仍是第二道闸；
- 事件通道（§8.8）：`events.subscribe` + `type:'event'` 推送，首批 `files.changed` /
  `selection.changed` / `project.switched`；尽调工作台插件升级 SDK 1.3.0 后即可接。

### P2 开放 AI 调用权 ——【ai.request 已落地，规范 v2.7，dev-board#282】
设计定稿：`docs/superpowers/specs/2026-08-29-plugin-p2-ai-request.md`。
- `ai.request` 走平台 Credits 辅助模型（§8.4），16000 字符 + 10 次/分钟，权限值 `ai`；
  流式与选模型走 `x-` 实验通道验证后再转正；
- 【未做】插件面板与对话面板双向联动（对话产出 → 面板订阅）：等 P1 事件通道在真实
  插件上跑熟后另开卡。

### P3 开放数据源接入权（对位 FileSystemProvider/LSP，杠杆层）——【已落地，规范 v2.8，dev-board#283】
设计定稿：`docs/superpowers/specs/2026-08-29-plugin-p3-evidence-provider-protocol.md`（dev-board#283）。
- 已落地（PLUGIN_SPEC §13）：JAR SPI（plugin-api 1.2.0 `EvidenceProvider`）+ manifest
  `contributes.evidenceSources`（远程 MCP 声明式接入，本地命令型不受理）两条通道；
  conformance 执行器 `EvidenceProviderConformanceKit`（零依赖）随 plugin-api 发布；
  示例 examples/hello-evidence-plugin 全绿。后续狗粮：判决书通道重构为官方插件（另开卡）。

### P4 声明式长尾（对位 languages/snippets/themes 的生态大头）——【设计已定稿，按贡献点分期实施】
设计定稿：`docs/superpowers/specs/2026-08-29-plugin-p4-declarative-contributions.md`（dev-board#284）。
- `contributes.templates` / `contributes.styleProfiles` / `settings` / l10n 四个贡献点各自独立发布；
  第一个狗粮 = HR 用工模板包 30 份打成纯声明式插件。

### 持续项
- 隔离策略复审：Web 插件（sandbox）保持默认推荐形态；JAR 插件长期看要么进程隔离
  要么收紧为官方审核专属——「劣质插件搞崩主程序/丢工作段」是生态起量后的必然风险；
- 开发者体验：dev 免签直装已有，随 P1 补 samples 仓与脚手架命令；
- 狗粮纪律：新官方功能优先评估「能不能用插件 API 实现」，倒逼 API 补能力。

### 每期动手前的 finalization 三问（照抄 VS Code）
有没有真实插件要用？有没有能跑的示例？API 形状是否过窄/过宽？
