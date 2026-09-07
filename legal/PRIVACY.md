# AI WorkDeck 隐私说明

更新日期：2026-08-17

本说明描述 AI WorkDeck 桌面应用的两件事：

- **第一部分 · 平台服务**：哪些数据会经过我们的服务器、存放多久、何时删除；
- **第二部分 · 使用统计**：采集什么、不采集什么、如何关闭。

英文版见文末（English version below）。

---

# 第一部分：平台服务

## 一、AI 对话不经过我们的服务器

无论选哪一档 AI 提供商，对话内容都由**你的机器直接发往所选模型提供商**：

- **本地 Ollama**：内容只在本机处理，零外发；
- **自备 Key**（OpenRouter 等）：你的机器直连该服务商；
- **「AI WorkDeck 云端」**：我们的服务器只参与密钥签发与用量结算，对话内容仍由你的机器直连模型服务商。

因此文档正文、合同内容、AI 对话文本在任何一档下都不流经 AI WorkDeck 的服务器。选用云端模型时内容会发往该第三方服务商，涉及执业保密义务的场合请据此判断；当前使用的模型与提供商在产品内可见，可随时更换或停用。

## 二、平台代采档下会经过我们服务器的内容

桌面版全新安装时，下列服务默认落在「平台代采」档：由 AI WorkDeck 统一向供应商采购、按 Credits 计费，你不必自备 Key。**该档下，本次调用必需的内容会经我们的服务器转发给对应供应商**：

| 服务 | 经我们服务器的内容 | 供应商 | 我们是否留存 | 删除时点 |
|---|---|---|---|---|
| 会议录音转写 | 录音音频文件、转写结果 | 阿里云听悟 | 音频在对象存储中转 | 转写完成或任务失败即删；另有 24 小时生命周期规则兜底 |
| 图片文字识别 | 待识别的图片 | 阿里云 OCR | 否 | 调用结束即无 |
| 联网搜索 | 搜索关键词 | 博查搜索 | 否 | 调用结束即无 |
| 企业工商信息 | 公司名称或统一社会信用代码 | 企查查 | 否 | 调用结束即无 |
| 证券财务数据 | 证券代码与所查字段 | Tushare | 否 | 调用结束即无 |
| 法律法规检索 | 检索词 | 北大法宝 | 否 | 调用结束即无 |

**只有会议录音这一项会在我们这里落盘。** 音频文件由桌面端直传我们的对象存储（不经过应用服务器），转写完成后由代码删除该对象；同时对象存储配有 24 小时生命周期规则，代码漏删时兜底清理。转写结果回传桌面端后不在我们这里保留。

**其余各项只在调用发生的那一刻透传**：请求内容不写日志、不入库、不用于任何模型训练。

**这六家供应商全部在境内**，平台代采不涉及向境外提供个人信息。

**语音合成不在这张表里**：它只有本机一档，随包内置的引擎在你的机器上合成，不出本机，也没有云端通路。

## 三、会留下的记录：账务流水

平台代采按 Credits 计费，每次调用会在你的账户下留一条账务流水，用于对账与用量展示：

- 记录服务名、操作类型、计量数量（分钟 / 页 / 次 / 千字符）、扣费金额、时间戳、幂等键；
- **不含**请求内容、结果内容与文件名。

这些是财务记录，与账户共存续，在「系统管理 → 账户与用量」可自行查看。

## 四、怎么让它不经过我们

「系统管理 → 平台服务」里每一项都可以改档，改完立即生效：

- **自备 Key**：填入你自己的供应商 Key，桌面端直连该供应商，不经过我们的服务器；
- **本地档**：在本机完成，不出本机（录音转写的本机引擎在后续版本随包发出）；
- **停用**：不使用该项能力，其余功能不受影响。

升级到平台代采档框架的存量安装，凡是已经填过自备 Key 的服务都会保持「自备 Key」，不会被切走。团队自建服务器部署与 Office 插件恒为自备 Key，平台代采档不对它们开放。

---

# 第二部分：使用统计

## 五、使用统计永远不会采集的数据

以下数据不会进入使用统计通道，任何情况下都不会被上报：

- 文档与合同的内容、任何形式的正文片段
- AI 对话的消息文本（你输入的和 AI 回复的）
- 文件名、文件路径、项目名称、客户与当事人名称
- 会话摘要、提取的实体、法律条文引用等一切派生自内容的信息
- 账户凭据、授权密钥

这不是策略承诺，而是代码结构：采集层实现了字段白名单（`TelemetryAttrWhitelist`），只放行动作枚举名与数值，白名单外的字段在源头即被丢弃，并有自动化测试锁定该行为。

本节说的是使用统计这一条通道。你主动调用的平台服务会把该次调用必需的内容发给供应商，口径见第一部分。

## 六、默认分享的数据（可一键关闭）

「分享匿名使用统计」默认开启。开启时，应用每天向 aiworkdeck.com 上报**一条**聚合记录：

- 随机安装标识（首次启动生成的 UUID，与设备、账户、个人身份无关）
- 应用版本与操作系统平台（如 darwin-arm64）
- 各功能的当日使用次数（AI 对话轮次、工具调用数、编辑动作数、文件变更数等纯计数）
- 模型与供应商的使用分布、skill 使用分布（均为枚举名计数）
- 法律事项类型分布（如「合同审查起草：3 次」——仅类型枚举，见下节）
- token 用量合计

## 七、法律事项类型是怎么得出的

为了发布行业层面的趋势报告（例如「本月合同审查类事务占比上升」），应用会给每个 AI 会话打一个粗粒度的类型标签（11 类枚举：公司治理、资本市场证券、并购交易、争议解决、合同审查起草、合规监管、知识产权、劳动人事、破产重整、其他法律事务、非法律事务）：

- 命中 skill 时直接取 skill 声明的类别，不读取任何内容；
- 未命中时由本机配置的 AI 模型对会话首条消息做一次分类，**只保留标签**，消息原文不落库、不上传；
- 「分享匿名使用统计」关闭时，分类完全不运行。

## 八、可选的明细分享（默认关闭）

「分享脱敏使用明细」默认关闭。开启后会在计数之外上报脱敏后的操作明细（动作枚举名、耗时、成败、时间戳），会话关联使用单向哈希键，无法反推。该开关面向愿意帮助定位体验问题的用户，不开启不影响任何功能。

## 九、本地记录

无论开关状态如何，应用都会在本机记录使用明细（同样只有枚举与数值，明细 90 天滚动清理），用于「设置 - 数据统计」里你自己可见的使用统计。关闭分享开关只停止上传，不影响本地统计。

## 十、如何关闭

设置（左栏齿轮）- 数据统计 - 关闭「分享匿名使用统计」。关闭立即生效，此后使用统计零外发请求。

## 十一、团队使用统计（可选，默认关闭）

律所可以在应用内组建团队，让管理者看到全所的使用情况。这是**另一条通道**，与上面第五至第十节说的匿名使用统计完全分离：不同的开关、不同的接口、不同的存储，两者之间没有任何关联。上面那条通道的随机安装标识仍然与账户无关，这一节不改变它。

**默认关闭。** 每台机器要由使用者本人在「设置 - 团队 - 向团队共享我的使用统计」里显式打开，管理者无法代为打开。关闭状态下这条通道零外发请求。

打开后，应用每天上报**一条**记录，内容全部是计数、日期与不可逆编号：

- 当日投入时长（分钟）、应用打开次数
- AI 对话轮次、工具调用数、AI 编辑动作数、人工编辑动作数、新建项目数
- token 用量合计（按结算口径分两类）
- 按项目分的投入时长与 AI 使用量，项目以**不可逆的匿名编号**出现

与匿名通道不同，这条记录带你的账户身份（用账户密钥鉴权），因为它的用途就是让团队管理者看到「谁用了多少」。

**项目名默认不上传。** 团队面板上项目只显示匿名编号，管理者可以给编号起一个团队内部叫法（这个叫法存在服务器上，与你机器上的真实项目名无关）。只有团队管理者在「团队设置」里显式打开「共享项目名」后，成员的上报才会带上项目名——这是整个团队的设置，会影响全体成员。

**仍然不会上传的**：文档内容、文件片段、AI 对话文本、文件名与路径、工作段标题、客户与当事人名称。第五节的清单在这条通道上同样成立。

**如何关闭**：设置 - 团队 - 关闭「向团队共享我的使用统计」，或直接退出团队。关闭立即生效。

## 十二、联系

对本说明的任何疑问：hi@aiworkdeck.com

---

# AI WorkDeck Privacy Note

Last updated: 2026-08-17

## Part 1 — Platform services

**AI conversations never pass through our servers.** Whichever provider tier you choose, conversation content goes straight from your machine to the model provider: with local Ollama it stays on the device; with your own key you connect to that provider directly; with the "AI WorkDeck cloud" tier our servers only issue the key and settle usage, while the conversation itself still goes from your machine to the model provider. Document text, contract content, and AI conversation text therefore never flow through AI WorkDeck servers under any tier. Content sent to a cloud model does reach that third-party provider — judge accordingly where a duty of confidentiality applies. The model and provider in use are visible in the product and can be changed or disabled at any time.

**What does pass through our servers.** On a fresh desktop install the services below default to the "platform-sourced" tier: AI WorkDeck buys from the vendor on your behalf and bills in Credits, so no key of your own is needed. In that tier, the content each call requires is relayed to the vendor through our servers.

| Service | What passes through our servers | Vendor | Retained by us | Deleted |
|---|---|---|---|---|
| Meeting transcription | Audio file, transcript | Aliyun Tingwu | Audio staged in object storage | On completion or failure; 24-hour lifecycle rule as backstop |
| Image text recognition | The image | Aliyun OCR | No | Nothing kept past the call |
| Web search | The query | Bocha Search | No | Nothing kept past the call |
| Company registry data | Company name or unified social credit code | Qichacha | No | Nothing kept past the call |
| Securities and financial data | Ticker and requested fields | Tushare | No | Nothing kept past the call |
| Statute and case law search | The query | PKULaw | No | Nothing kept past the call |

**Meeting audio is the only item written to disk on our side.** The desktop app uploads it directly to our object storage (bypassing the application server); the object is deleted by code once transcription finishes or the task fails, and a 24-hour lifecycle rule on the bucket clears anything the code misses. The transcript is not retained after it is returned to the desktop app. Every other service passes content through only at call time: request content is not logged, not stored, and not used to train any model. All six vendors are inside mainland China, so the platform-sourced tier involves no transfer of personal information abroad. Speech synthesis is absent from the table: it has an on-device tier only, synthesizing in the bundled engine with no cloud path at all.

**What is kept: billing entries.** Each platform-sourced call leaves one ledger entry under your account recording the service name, operation, quantity (minutes / pages / calls / thousand characters), amount charged, timestamp, and idempotency key. It contains no request content, no result content, and no file names. These are financial records, kept for the life of the account, and visible to you under Settings → Account and Usage.

**How to keep it off our servers.** Under Settings → Platform Services each item can be switched to your own key (the desktop app then connects to that vendor directly), to an on-device tier where one exists (on-device meeting transcription ships in a later version), or disabled entirely. Existing installs that already have a vendor key configured stay on their own key and are not moved. Team-hosted server deployments and the Office add-in are always own-key; the platform-sourced tier is not offered to them.

## Part 2 — Usage statistics

**What usage statistics never collect:** document and contract content, AI conversation text, file names and paths, project/client names, conversation summaries and extracted entities, credentials. This is enforced structurally by an attribute whitelist in the collection layer, locked by automated tests. This covers the statistics channel only; platform services you invoke send what that call requires to the vendor, per Part 1.

**What is shared by default (one switch to turn off):** one aggregated record per day to aiworkdeck.com containing a random install UUID (unrelated to device or identity), app version and OS platform, daily counts of feature usage, model/provider/skill distribution as enum counts, a coarse legal-matter-type distribution (11 fixed categories; derived from skill category or a local AI classification of which only the label is kept), and token totals.

**Optional detail sharing (off by default):** de-identified action-level events (enum action names, durations, outcomes) with one-way hashed session keys.

**Local ledger:** regardless of the switches, the app keeps a local-only usage ledger (enums and numbers only, 90-day rolling retention) powering the personal statistics page in Settings. Turning sharing off stops uploads immediately; local statistics keep working.

**How to opt out:** Settings - Usage Statistics - turn off "Share anonymous usage statistics". Effective immediately.

## Part 3 — Team usage statistics (optional, off by default)

A firm can form a team inside the app so that whoever runs it can see firm-wide usage. This is a **separate channel**, fully split from the anonymous statistics of Part 2: different switch, different endpoint, different storage, no link between the two. The random install identifier of Part 2 remains unrelated to any account; nothing here changes that.

**Off by default.** Each machine's own user must turn it on explicitly under Settings - Team - "Share my usage stats with the team"; a manager cannot turn it on for someone else. While it is off this channel makes zero outbound requests.

When on, the app uploads **one record a day**, made entirely of counts, a date, and irreversible codes: minutes invested and app launches; AI turns, tool calls, AI edit actions, manual edit actions, projects created; token totals split by billing source; and per-project minutes and AI usage where each project appears as an **irreversible anonymous code**.

Unlike the anonymous channel, this record carries your account identity (authenticated with your account key) — showing a manager who used how much is precisely its purpose.

**Project names are not uploaded by default.** The team panel shows anonymous codes, and a manager may attach an internal label to a code (that label lives on the server and is unrelated to the real project name on your machine). Only after a team manager explicitly turns on "Share project names" in team settings do members' uploads carry project names; that is a team-wide setting affecting everyone.

**Still never uploaded:** document content, text fragments, AI conversation text, file names and paths, work-session titles, client and counterparty names. The Part 2 exclusion list holds on this channel too.

**How to opt out:** Settings - Team - turn off "Share my usage stats with the team", or leave the team. Effective immediately.

Questions: hi@aiworkdeck.com
