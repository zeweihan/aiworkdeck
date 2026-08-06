# AI Workdeck 隐私说明（使用统计）

更新日期：2026-08-06

本说明描述 AI Workdeck 桌面应用的匿名使用统计机制：采集什么、不采集什么、数据如何流转、如何关闭。英文版见文末（English version below）。

## 一、永远不会离开你电脑的数据

以下数据只存在于你的本机，任何情况下都不会被上传：

- 文档与合同的内容、任何形式的正文片段
- AI 对话的消息文本（你输入的和 AI 回复的）
- 文件名、文件路径、项目名称、客户与当事人名称
- 会话摘要、提取的实体、法律条文引用等一切派生自内容的信息
- 账户凭据、授权密钥

这不是策略承诺，而是代码结构：采集层实现了字段白名单（`TelemetryAttrWhitelist`），只放行动作枚举名与数值，白名单外的字段在源头即被丢弃，并有自动化测试锁定该行为。

## 二、默认分享的数据（可一键关闭）

「分享匿名使用统计」默认开启。开启时，应用每天向 aiworkdeck.com 上报**一条**聚合记录：

- 随机安装标识（首次启动生成的 UUID，与设备、账户、个人身份无关）
- 应用版本与操作系统平台（如 darwin-arm64）
- 各功能的当日使用次数（AI 对话轮次、工具调用数、编辑动作数、文件变更数等纯计数）
- 模型与供应商的使用分布、skill 使用分布（均为枚举名计数）
- 法律事项类型分布（如「合同审查起草：3 次」——仅类型枚举，见下节）
- token 用量合计

## 三、法律事项类型是怎么得出的

为了发布行业层面的趋势报告（例如「本月合同审查类事务占比上升」），应用会给每个 AI 会话打一个粗粒度的类型标签（11 类枚举：公司治理、资本市场证券、并购交易、争议解决、合同审查起草、合规监管、知识产权、劳动人事、破产重整、其他法律事务、非法律事务）：

- 命中 skill 时直接取 skill 声明的类别，不读取任何内容；
- 未命中时由本机配置的 AI 模型对会话首条消息做一次分类，**只保留标签**，消息原文不落库、不上传；
- 「分享匿名使用统计」关闭时，分类完全不运行。

## 四、可选的明细分享（默认关闭）

「分享脱敏使用明细」默认关闭。开启后会在计数之外上报脱敏后的操作明细（动作枚举名、耗时、成败、时间戳），会话关联使用单向哈希键，无法反推。该开关面向愿意帮助定位体验问题的用户，不开启不影响任何功能。

## 五、本地记录

无论开关状态如何，应用都会在本机记录使用明细（同样只有枚举与数值，明细 90 天滚动清理），用于「设置 - 数据统计」里你自己可见的使用统计。关闭分享开关只停止上传，不影响本地统计。

## 六、如何关闭

设置（左栏齿轮）- 数据统计 - 关闭「分享匿名使用统计」。关闭立即生效，此后零外发请求。

## 七、联系

对采集口径有任何疑问：hi@aiworkdeck.com

---

# AI Workdeck Privacy Note (Usage Statistics)

Last updated: 2026-08-06

**What never leaves your machine:** document and contract content, AI conversation text, file names and paths, project/client names, conversation summaries and extracted entities, credentials. This is enforced structurally by an attribute whitelist in the collection layer, locked by automated tests.

**What is shared by default (one switch to turn off):** one aggregated record per day to aiworkdeck.com containing a random install UUID (unrelated to device or identity), app version and OS platform, daily counts of feature usage, model/provider/skill distribution as enum counts, a coarse legal-matter-type distribution (11 fixed categories; derived from skill category or a local AI classification of which only the label is kept), and token totals.

**Optional detail sharing (off by default):** de-identified action-level events (enum action names, durations, outcomes) with one-way hashed session keys.

**Local ledger:** regardless of the switches, the app keeps a local-only usage ledger (enums and numbers only, 90-day rolling retention) powering the personal statistics page in Settings. Turning sharing off stops uploads immediately; local statistics keep working.

**How to opt out:** Settings - Usage Statistics - turn off "Share anonymous usage statistics". Effective immediately.

Questions: hi@aiworkdeck.com
