<p align="center">
  <a href="https://www.aiworkdeck.com">
    <img src=".github/assets/icon.png" width="88" alt="AI WorkDeck logo">
  </a>
</p>

<h1 align="center">AI WorkDeck</h1>

<p align="center">
  <strong>让法律人聚焦专业判断 — 面向法律与文档密集型工作的 AI 原生工作台</strong>
</p>

<p align="center">
  <a href="https://github.com/zeweihan/aiworkdeck/stargazers"><img src="https://img.shields.io/github/stars/zeweihan/aiworkdeck?style=social" alt="Stars"></a>
  <a href="https://github.com/zeweihan/aiworkdeck/releases"><img src="https://img.shields.io/github/v/release/zeweihan/aiworkdeck?color=1A5336" alt="Release"></a>
  <a href="https://github.com/zeweihan/aiworkdeck/releases"><img src="https://img.shields.io/badge/platform-macOS%20%7C%20Windows-1A5336" alt="Platform"></a>
  <a href="legal/LICENSE"><img src="https://img.shields.io/badge/license-AGPLv3-blue.svg" alt="License: AGPLv3"></a>
  <a href="legal/COMMERCIAL-LICENSE.md"><img src="https://img.shields.io/badge/%E5%95%86%E4%B8%9A%E8%AE%B8%E5%8F%AF-%E5%8F%AF%E7%94%A8-1A5336.svg" alt="商业许可"></a>
  <a href="https://www.aiworkdeck.com"><img src="https://img.shields.io/badge/%E5%AE%98%E7%BD%91-aiworkdeck.com-1A5336.svg" alt="官网"></a>
</p>

<p align="center">
  <a href="README.md">English</a> · <strong>简体中文</strong>
</p>

<p align="center">
  <a href="https://www.aiworkdeck.com">
    <img src=".github/assets/workspace-ai.png" alt="AI WorkDeck — 项目工作区：文件树 + 文档预览 + AI 智能体面板（真实产品截图）" width="900">
  </a>
</p>

---

> **VS Code** 把文件、扩展、终端、Git 和 AI 编程助手装进了开发者的同一个工作台。
>
> **AI WorkDeck** 想为律师和文档密集型团队做同样的事：项目、文档、智能体、插件、证据与审查，尽在一处。

## 为什么值得 Star

如果你关心下面任何一个问题，欢迎为 AI WorkDeck 点一颗 ⭐：

- 构建 AI 原生的法律 / 专业服务工作流
- 从「聊天机器人外挂」进化为文件、上下文、智能体、插件真正共存的工作台
- 私有化部署文档 AI 基础设施：数据私有、留痕可审计、面向组织级工作流
- 在同一套代码里探索 MCP 式智能体编排、文档解析、内嵌 LibreOffice 编辑、AI PPT、语音合成、OCR 与证据链工作流

## 这是什么

AI WorkDeck 社区版就是完整的工作台，AGPL 开源：与官方安装包相同的桌面端、编辑器、Agent 与插件运行时。商业授权面向需要免除 AGPL 义务的机构，不以功能设卡。开源的目的，是让开发者、律所、法律科技团队和文档 AI 团队可以审查、私有部署、集成和扩展它。

## 下载即用

只是想试试？不需要从源码构建。

**➡️ [下载最新版桌面应用](https://github.com/zeweihan/aiworkdeck/releases/latest)**

| 平台 | 安装包 | 说明 |
|---|---|---|
| macOS（Apple Silicon） | `AI WorkDeck-<version>-arm64.dmg` | 已签名并公证 |
| Windows | `AI WorkDeck Setup <version>.exe` | 暂未代码签名，SmartScreen 可能提示，选择「仍要运行」即可 |

> Intel 芯片 Mac 的构建已停止（上游 Python 依赖不再提供 x86_64 版本），旧版本 Release 中仍保留最后一个 Intel dmg。

双击安装、登录账户即可开始工作。官方桌面版**不需要任何 API Key、不需要任何基础设施**：AI 与外部服务统一走 AI WorkDeck 平台通道、按用量（Credits）计费；后端、精简版 JRE 和本地数据库都已打进安装包，无需 Java、Docker 或 PostgreSQL。（想从源码构建？自部署服务栈保留了可配置的模型提供方，包括用 [Ollama](https://ollama.com) 跑完全本地的模型——见下方[快速开始](#快速开始)。）

> 桌面版是体验 AI WorkDeck 最快的方式。想自部署完整服务栈或参与开发，请看下方[快速开始](#快速开始)。

## 获取与解锁

桌面端首次启动会要求登录账户。在[官网](https://www.aiworkdeck.com/start)用手机号收一条验证码即可完成注册与登录——没有单独的注册步骤，未注册的手机号会直接建号。

登录之后，桌面端在本机保存一枚账户 Key，之后每次打开都直接进入工作台，**不需要每次联网**：授权有 30 天离线宽限，只要一个月内联网启动过一次就会自动续上。飞机上、律所内网都能照常开文档、编辑、保存。

**已有账户 Key 的用户**（团队服务器、私有部署，或在官网账户页自行生成过 `awdk_` 前缀的 Key）：在解锁页切到「账户 Key」标签粘贴即可，这条路一字未变。

两点供拟部署的机构参考：授权是按机器的，状态存在本机 `~/.aiworkdeck/` 目录下；部署为团队服务器（同事用浏览器访问共用服务端）时不设解锁门。

### 自行构建：恢复离线试用码

试用码这条离线解锁路仍在代码里，只是官方发布的二进制默认关闭。自行构建时把
`backend/src/main/resources/application-desktop.yml` 里的

```yaml
security:
  license:
    trial-code:
      enabled: false
```

改成 `true` 即完全恢复原行为（离线验签、不联网、不上报任何本机信息）。这是一个默认值，不是防篡改机制。

## 演示

| | |
|---|---|
| 官网 | [aiworkdeck.com](https://www.aiworkdeck.com/zh) |
| 产品视频 | [功能演示视频](https://www.aiworkdeck.com/videos/intro.mp4) |
| 功能演练 | [AI WorkDeck 功能演练](https://www.aiworkdeck.com/zh/showcase) |

## 界面截图

全部为**真实产品截图**（演示项目，人名与文档内容均为虚构）。下面两张：应用内插件 / Skill 广场（列表为在线 registry 实拉的真实条目），以及带 AI 署名修订与审阅面板的文档工作台。

<p align="center">
  <img src=".github/assets/marketplace-live.png" alt="应用内插件与 Skill 广场（真实产品截图）" width="900">
</p>
<p align="center">
  <img src=".github/assets/workdeck-redline.png" alt="文档工作台：AI 署名修订与审阅面板（真实产品截图）" width="900">
</p>

## 核心能力

| 领域 | 社区版提供什么 |
|---|---|
| **工作区** | 项目 / 文件树、文档暂存、收藏、剪贴板记忆、工作记录、日历与任务管理、浅色 / 深色主题 |
| **AI 文档工作** | 起草、审查、要素抽取、脱敏、Markdown 与文档预览 |
| **智能体层** | 主智能体界面、流式回复、上下文文件标签、MCP 式工具编排 |
| **文档编辑** | 内嵌 LibreOffice（WASM）编辑器（原生中文界面）、本地 DOCX 修订编辑、审阅面板（逐条接受 / 拒绝修订与批注）、AI 编辑器原语（docx/xlsx/pptx/pdf）、文档内链、差异对比 |
| **版本记录** | 基于 Git 的项目级版本系统：时间线、逐版本差异、退回、里程碑、多稿并行 |
| **文档解析与依据** | 实体抽取（企业 / 法规 / 案例）、外部库检索、文档内部一致性校验、依据窗格 |
| **法律工作流** | 尽职调查工作台、股东会核查、诉讼可视化（时间轴 / 流程图 / 当事人关系图） |
| **解析与生成** | MinerU 文档解析、AI PPT 生成、本机语音合成、会议录音转写 |
| **插件面** | 应用内插件 / Skill 广场、左栏插件、公开插件 SDK 与示例（`sdk/`、`examples/`）、垂直工作流专属面板 |
| **伴生端** | Microsoft Office 插件（Word/Excel/PPT 任务窗格）、手机端拍摄与项目同步中转 |
| **部署** | Java/Spring 后端、Vue/uni-app 前端、Electron 桌面壳、Docker 化服务 |
| **治理** | 私有化部署路径、可审计的工作记录、商业授权路径 |

## 架构

```mermaid
flowchart TB
  User["用户工作区"] --> IDE["IDE 交互层"]
  IDE --> LO["内嵌 LibreOffice 编辑器"]
  IDE --> Agent["智能体与对话界面"]
  Agent --> MCP["MCP / 工具编排"]
  MCP --> Skills["文档技能与插件"]
  Skills --> Data["PostgreSQL、对象存储、文件上下文"]
  Skills --> Services["MinerU、PPTX 服务、TTS、OCR"]
  Data --> Security["私有部署与审计控制"]
  Services --> Security
```

## 数据处理与隐私

AI WorkDeck 为**自托管、私有化部署**而设计。下图标注了哪些组件在本地处理数据、哪些走外部服务：

```
┌─────────────────────────────────────────────────────────────────┐
│  你的基础设施（内网）                                             │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────┐  │
│  │ AI 智能体 │  │  MinerU  │  │   PPTX   │  │    敏感信息     │  │
│  │ (Ollama) │  │ (Docker) │  │ (Docker) │  │     脱敏        │  │
│  │  本地    │  │  本地    │  │  本地     │  │     本地        │  │
│  └──────────┘  └──────────┘  └──────────┘  └────────────────┘  │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────────────┐  │
│  │PostgreSQL│  │   RAG    │  │        本地文件存储            │  │
│  │  本地    │  │  本地    │  │           本地                │  │
│  └──────────┘  └──────────┘  └──────────────────────────────┘  │
│                                                                 │
├───────────────────────────── 可选外部服务 ───────────────────────┤
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐   │
│  │   OCR    │  │ 会议转写  │  │  企业数据  │  │  Gemini /    │   │
│  │ (阿里云)  │  │ (听悟)    │  │ (企查查)  │  │  OpenRouter  │   │
│  │  外部    │  │  外部     │  │  外部     │  │   可配置      │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

| 组件 | 默认位置 | 可完全本地？ | 说明 |
|---|---|---|---|
| AI 推理（对话 / 智能体） | 自部署构建默认本地（Ollama） | ✅ 可以 | 官方桌面版走平台通道（Credits）；自部署默认 localhost:11434 |
| RAG / 向量化 | 本地（Apache Tika） | ✅ 可以 | InMemoryEmbeddingStore |
| 文档解析（MinerU） | 本地（Docker） | ✅ 可以 | 无外部调用 |
| PPTX 生成 | 本地（Docker） | ✅ 可以 | 无外部调用 |
| 敏感信息脱敏 | 本地（正则） | ✅ 可以 | 中文 PII 模式，无外部调用 |
| 文档存储 | 本地文件系统 | ✅ 可以 | 可配置：本地 / OSS / S3 |
| OCR | **平台代采**（经我们的服务器转阿里云） | 无本地回退 | 全新安装的默认档；可改自备 Key 直连或停用 |
| 语音合成 | 本地引擎（随包 Kokoro） | 可以 | 只有本机一档，无云端通路 |
| 企业数据查询 | **平台代采**（经我们的服务器转企查查） | 无本地回退 | 全新安装的默认档；可改自备 Key 直连或停用 |
| 会议录音转写 | **平台代采**（音频经我们的对象存储中转，转写完成即删） | 后续版本随包发出本机引擎 | 全新安装的默认档；可改自备 Key 直连或停用 |
| 云端大模型 | **外部**（Gemini/OpenRouter） | ✅ 可换 Ollama | 提供方可配置 |
| 匿名使用统计 | 本地账本 + 每日聚合计数上报 | ✅ 可关闭 | 仅计数与枚举值，无内容；设置一键关，见 legal/PRIVACY.md |

**离线 / 内网隔离部署**：Ollama + 本地存储 + MinerU + PPTX 服务的组合可以让所有文档完全不出内网。关闭 OCR、企业数据、会议转写与云端模型后，核心工作区、文档编辑、智能体编排与尽调工作流均可正常使用（语音合成本来就在本机，不必关）。

## 证据链与审计现状

> **现状**：版本记录与关系型审计日志已就位，密码学级溯源在路线图上。

社区版目前提供：

- **版本记录**：基于 Git 的项目级版本系统（`com.checkba.version`），含时间线、逐版本文件差异、退回、里程碑与多稿并行
- **行为日志**：`UserActivityLog` 记录每个用户动作（登录、打开文件、页面访问等），带 Hibernate `@CreationTimestamp` 时间戳与 JSON 元数据
- **尽调追踪**：`DdItem` 记录状态流转（待上传 → 已上传 → 通过/退回），含 `uploadedAt` 与 `uploadedBy`
- **会话审计**：`ConversationFileChange` 按会话记录文档新增与修改
- **文件元数据**：`ProjectFile` 在 PostgreSQL 中存储 `createdAt`/`updatedAt` 与文件路径

**尚未实现**（在路线图上）：
- 文档密码学哈希（SHA-256 校验和）
- 防篡改审计链（Merkle 链或签名日志）
- 不可变的只追加证据日志

插件架构在设计上已为这些能力预留了位置。如果你的合规或诉讼支持场景需要密码学级溯源，欢迎开 issue 描述需求——这会直接影响我们的优先级排序。

## 律所使用许可 FAQ

### 律所内部使用 AI WorkDeck，需要公开我们的修改吗？

**通常不需要——但有一个值得理解的细节。** AGPLv3 保障你运行软件、以及为自用而修改软件的权利。内部运行*未修改*的副本不产生任何源码披露义务。如果你*修改*了 AI WorkDeck，并通过网络把修改版提供给他人使用——这可能包括你所内律师和员工把它当内部 Web 应用使用——AGPLv3 第 13 条可能要求你向**这些用户**提供修改版的对应源码。这些源码留在所内即可：你不需要向公众或向我们公开。如果你希望修改、插件或集成保持专有、完全不受 AGPLv3 约束，商业许可可以彻底移除 copyleft 义务。

### 网络使用条款（AGPLv3 第 13 条）怎么理解？

第 13 条由**与修改版的网络交互**触发，有权获得对应源码的是**修改版的用户**。两种常见情形：

- **防火墙内的内部工具（有修改）**：用户是你的员工。你可能需要向他们提供对应源码，但不需要向公众或向我们披露。未修改的部署没有此义务。
- **面向客户的门户或对外服务（有修改）**：用户是你的客户，AGPLv3 会要求向他们提供修改版源码。如果不想这样做，商业许可是干净的路径。

### 专有插件和扩展呢？

当前架构中插件在进程内运行。在 AGPLv3 下，copyleft *可能*延伸到专有插件。如果贵所计划构建专有工作流扩展，**商业许可**为以下场景提供干净的法律基础：
- 闭源插件与集成
- 不披露源码的专有本地化部署
- 基于 AI WorkDeck 内核构建的商业 SaaS 产品

详见 [`legal/COMMERCIAL-LICENSE.md`](legal/COMMERCIAL-LICENSE.md)，或联系 [hi@aiworkdeck.com](mailto:hi@aiworkdeck.com)。

### 我们的数据去哪了？

**AI 对话不经过我们的服务器。** 对话内容由你的机器直接发往所选模型提供商：选本地 Ollama 时只在本机处理，选云端提供商时发往该第三方；即便用「AI WorkDeck 云端」这一档，我们的服务器也只参与密钥签发与用量结算。因此文档与合同内容、AI 对话文本、文件名、项目与客户信息不流经我们的服务器；在 Ollama + 本地存储 + Docker 服务的自托管部署中，**它们根本不离开你的网络**。

**平台代采档会经过我们的服务器。** 桌面版全新安装时，图片文字识别、联网搜索、企业工商信息、证券财务数据、法律法规检索与会议录音转写默认落在「平台代采」档——由 AI WorkDeck 统一向供应商采购、按 Credits 计费，不必自备 Key；该档下本次调用必需的内容会经我们的服务器转给对应供应商。其中只有会议录音的音频文件会在我们的对象存储中转，转写完成即删除，另有 24 小时生命周期规则兜底；其余各项只在调用当时透传，不留存请求内容。这六家供应商全部在境内。语音合成不在此列——它只有本机一档，随包的引擎在你的机器上合成，不出本机。逐项口径（经过什么、存放多久、何时删除）见 [`legal/PRIVACY.md`](legal/PRIVACY.md)。

**每一项都能改档。** 「系统管理 → 平台服务」里可把任一项切成自备 Key（桌面端直连该供应商，不经过我们）或本地档，也可整项停用；已经填过自备 Key 的存量安装不会被切走。团队自建服务器部署与 Office 插件恒为自备 Key，平台代采档不对它们开放。

应用默认分享匿名聚合使用统计（仅每日功能使用计数，关联随机安装标识），用于改进产品；可在设置中一键关闭，采集口径同见 [`legal/PRIVACY.md`](legal/PRIVACY.md)。

## 快速开始

> 面向想自部署完整服务栈或参与开发的开发者。只想试用应用请直接看上方[下载即用](#下载即用)。

### 环境要求

| 依赖 | 版本 |
|---|---|
| Docker Desktop | 最新版（用于 MinerU、PPTX、TTS 服务） |
| Java | 17+（也支持 JDK 21） |
| Node.js | 18+ |
| PostgreSQL | 14+ |

### 步骤

```bash
# 1. 克隆
git clone https://github.com/zeweihan/aiworkdeck.git
cd aiworkdeck

# 2. 配置环境变量
cp backend/.env.example backend/.env.production
cp pptx-service/.env.example pptx-service/.env

# 3. 创建数据库
# 创建名为 `checkba` 的 PostgreSQL 数据库
# 或修改后端环境变量指向你自己的数据库

# 4. 启动全部服务
chmod +x restart-all.sh
./restart-all.sh
```

### 服务地址

| 服务 | 地址 |
|---|---|
| 前端 | `http://localhost:5173` |
| 后端 | `http://localhost:9696` |
| PPTX 服务 | `http://localhost:5001` |
| MinerU 服务 | `http://localhost:8001` |

语音合成在本机运行（随包 Kokoro 引擎）；`restart-all.sh` 已不再启动旧的 EasyVoice Docker 服务（定义仍保留在 `docker-compose.yml` 中）。

常见可选提供方：OpenRouter、Gemini、企查查、Tushare、北大法宝、阿里云（OCR 与听悟转写）与对象存储。审查代码或运行基础工作台并不要求配齐所有提供方。

## 文档

- [架构](docs/architecture.md) — 系统怎么拆、各层职责
- [上手](docs/getting-started.md) — 从源码跑本地栈
- [插件指南](docs/plugin-guide.md) — JAR / Web 插件、SDK、上架

内部设计稿、PRD 和实现记录在 [docs/dev-notes/](docs/dev-notes/)。

## 仓库结构

| 路径 | 用途 |
|---|---|
| `backend/` | Spring Boot 后端，智能体/工具 API，文档服务 |
| `frontend/` | Vue/uni-app 工作台前端 |
| `desktop/` | Electron 桌面壳 |
| `pptx-service/` | AI 原生 PPT 生成服务 |
| `mineru-service/` | 基于 MinerU 的文档解析服务 |
| `easyvoice/` | 语音合成服务 |
| `docs/` | 面向贡献者的文档（架构、上手、插件）；内部笔记在 `docs/dev-notes/` |
| `legal/` | AGPLv3 许可、CLA、商业许可、商标条款 |

## 路线图

这份清单最初写下后已交付的：公开插件 SDK 与示例插件、尽职调查与股东会核查工作流、诉讼可视化、基于 Git 的版本历史与差异。仍在前方的：

- [ ] 更干净的一键本地演示（带示例数据）
- [ ] 密码学级溯源：文档哈希、防篡改审计链
- [ ] 本机会议转写引擎（当前默认档经平台服务器中转）
- [ ] 更多法律文书工作流：合同审查、独立的证据时间线
- [ ] 面向律所与企业私有化部署的更好的自托管指南
- [ ] 社区版双语文档

## 治理

AI WorkDeck 以开放方式开发：贡献者 → 评审者 → 维护者的晋升阶梯、重大变更走 RFC 流程、每月一次社区例会——详见 [GOVERNANCE.md](GOVERNANCE.md) 与 [MAINTAINERS.md](MAINTAINERS.md)。项目由北京京微资易科技有限公司作为 steward 运营（海外发行由真善美承泽有限公司 Zhen Shan Mei Grace Legacy Limited 承担），商标与商业授权集中于公司；治理角色承载技术权限，不附带任何经济权利。

### 社区基金

我们承诺将**商业授权净收入的 20%** 注入社区基金，用于 issue 悬赏（[BOUNTIES.md](BOUNTIES.md)）、Skill 创作补贴与社区活动。这是公司单方面的公开政策，不构成对任何个人的合同承诺；比例与规则可能随项目发展前瞻性调整。

## 参与贡献

欢迎 issue、讨论、文档改进、集成笔记和目标明确的 PR。提交 PR 前请先阅读 [CONTRIBUTING.md](.github/CONTRIBUTING.md)。代码贡献需要一次性签署 [CLA](legal/CLA.md)，首个 PR 上由机器人自动引导完成。带 `bounty` 标签的 issue 合并即有现金悬赏——见 [BOUNTIES.md](BOUNTIES.md)。

适合上手的第一批贡献：

- 在不同操作系统上复现并记录本地部署路径
- 改进自托管文档与 `.env` 示例
- 添加插件示例
- 为文档解析、智能体工具调用和前端工作流补测试
- 改进中英文文档

### 投稿 Skill、上架插件——不用提 PR

参与共建最快的方式是走广场，你发布的内容会直达每一台桌面端：

- **投稿 Skill**（可复用的提示词工作流：审合同、写文书、做核查）：[aiworkdeck.com/zh/skills](https://www.aiworkdeck.com/zh/skills)。登录即发布，表单里的 AI 辅助能把一句话想法扩写成完整 Skill。
- **上架插件**（基于[插件 SDK](sdk/plugin-sdk/README.md) 的 JAR 或 Web 插件，可从 [`examples/`](examples/) 起步）：在[插件广场](https://www.aiworkdeck.com/zh/plugins)提交。每个插件都经人工审核并以 Ed25519 签名后上架，付费插件与作者分成。
- **提需求**：开一个 [GitHub issue](https://github.com/zeweihan/aiworkdeck/issues)，或用官网的[需求提交表单](https://www.aiworkdeck.com/zh/feature-request)——提交会直接进入我们的分诊队列。

## 许可

AI WorkDeck 社区版基于 GNU Affero General Public License v3.0 发布。

如果你修改了本项目并将其作为网络服务提供，AGPLv3 通常要求你向该服务的用户提供对应源码。

以下场景可获得商业许可：

- 闭源 SaaS 交付
- 专有本地化交付
- 需要集成内核但不公开专有修改的商业产品
- 企业级支持与实施协助

详见 [LICENSE](legal/LICENSE) 与 [COMMERCIAL-LICENSE.md](legal/COMMERCIAL-LICENSE.md)。商业许可请联系 [hi@aiworkdeck.com](mailto:hi@aiworkdeck.com)。

**AI WorkDeck 标识**（K 形图形）已在中国注册为商标（第 9、35、42 类，注册号 89857424、89857389、89857390）。「**AI WorkDeck**」作为商号与未注册文字商标使用（标 ™），与 aiworkdeck.com 域名一起受反不正当竞争法保护。欢迎在上述许可下基于内核构建产品，但将 **AI WorkDeck** 名称或标识用于商业产品的营销需要品牌或认证协议——见 [TRADEMARKS.md](legal/TRADEMARKS.md) 与 [COMMERCIAL-LICENSE.md](legal/COMMERCIAL-LICENSE.md) 中的**品牌与认证计划**。

## 背景

产品理念与创始故事见 [WHY.md](WHY.md)。

---

## ⭐ Star 趋势

<p align="center">
  <a href="https://github.com/zeweihan/aiworkdeck/stargazers">
    <img src="https://raw.githubusercontent.com/zeweihan/aiworkdeck/star-history/star-history.svg" alt="Star 历史曲线" width="760">
  </a>
</p>
<p align="center">
  <sub>自托管曲线图，由<a href=".github/workflows/star-history.yml">定时工作流</a>每周自动刷新。</sub>
</p>

<p align="center">
  如果你认同这个方向，请 <a href="https://github.com/zeweihan/aiworkdeck/stargazers">⭐ Star 本仓库</a>，并分享给正在做法律 AI、文档 AI 或专业服务基础设施的朋友。
</p>
