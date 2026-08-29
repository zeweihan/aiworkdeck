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

### P0 治理地基（随下一个插件规范版本，先立规矩再扩面）
- manifest 加 `minHostVersion`（老宿主给明确提示，不静默半残）；
- 实验 API 机制：`x-` 前缀桥方法 + 广场拒收使用实验方法的投稿，转正去前缀；
- 「只加不改」写进 PLUGIN_SPEC 作为章程；每次桥变更四处同步（宿主/SDK/官网模板/模拟器）的纪律固化。

### P1 开放文档读写权（对位 TextDocument，生态的第一根主干）
- 桥新增 `doc.*` 命名空间：把既有编辑原语的**安全子集**（读文本/查找/选区/插入/
  格式化/批注/书签）暴露给 Web 插件，走既有 `editor` 权限与 EDITOR_ACTIONS 白名单，
  AI 管线与插件同闸；
- 事件通道：`{type:'event'}` 推送 + `awd.events.on()`，首批 `files.changed` /
  `selection.changed` / `project.switched`（尽调工作台现在靠轮询，就是第一个用户）。

### P2 开放 AI 调用权（对位 lm.* / Chat Participant）
- `ai.request`：插件经平台 Credits 通道调模型（用户已付费授权，插件免带 Key——
  计费/配额/审计全在宿主，照搬 lm.* 的逻辑）；
- Skill 注册已等价于 Chat Participant 的「@技能」，补齐插件面板与对话面板的双向
  联动（对话里产出 → 插件面板可订阅）。

### P3 开放数据源接入权（对位 FileSystemProvider/LSP，杠杆层）
- 把 evidence.retrieve.v1 升格为**公开 Provider 协议**：第三方按协议接新数据源
  （工商/裁判文书/财务数据库/行业库），插件声明 provider、宿主统一检索与展示；
  这是「协议层杠杆」的直接复刻——数据源接一次，所有依据/尽调/核查场景全能用。

### P4 声明式长尾（对位 languages/snippets/themes 的生态大头）
- 文书体裁/模板/HOUSE 格式画像的声明式贡献点（零代码插件形态，AI 最容易批量产出，
  也最贴近会计师/文员等非开发者作者）；
- 设置贡献点（manifest.settings → 广场渲染表单 → 桥 `settings.get`）；l10n 字符串表。

### 持续项
- 隔离策略复审：Web 插件（sandbox）保持默认推荐形态；JAR 插件长期看要么进程隔离
  要么收紧为官方审核专属——「劣质插件搞崩主程序/丢工作段」是生态起量后的必然风险；
- 开发者体验：dev 免签直装已有，随 P1 补 samples 仓与脚手架命令；
- 狗粮纪律：新官方功能优先评估「能不能用插件 API 实现」，倒逼 API 补能力。

### 每期动手前的 finalization 三问（照抄 VS Code）
有没有真实插件要用？有没有能跑的示例？API 形状是否过窄/过宽？
