# Office 插件 + 桌面端 体验问题修订计划（2026-08-07）

来源：维护者真机试用后反馈的 8 条问题（插件设置/激活、桌面端交互与样式、插件运行与性能、
文本修订颗粒度、Word 格式能力、项目必选性）。本文是排期用的工作范围说明，不是实现记录。

涉及领域：office-addin（主）、licensing-billing、plugin-marketplace、sidebar-shell、ai-doc-bridge。

---

## 一、逐条根因核实

### 1. 插件设置复杂 + 需要桌面端 Token，而桌面端没有生成入口

**现状**：`office-addin/taskpane/components/SettingsView.vue` 三张卡片、四个输入动作：
后端地址、awdt_ 设备令牌（手工粘贴）、awdk_ 账户 Key 一键连接、AI 额度刷新。
默认后端地址为空，用户必须自己知道填什么。

**已核实**：桌面端**确实没有**设备令牌生成界面。后端 `/api/auth/device-token`、
`/api/auth/device-tokens`、`/api/auth/device-token/{id}/revoke` 三个端点都在
（`AuthController.java:547/584/602`），但 `frontend/src/` 全库搜不到任何调用点。
插件里那句「可在 AI Workdeck 桌面版的设置中生成」（SettingsView.vue:24）指向的是一条死路。

**判断**：awdt_ 这条路本就是 MVP 期的临时形态（`lib/settings.js:3` 的注释写着
「Phase D 会替换为 awdk_ 桥的体面流程」），现在该收口了。

### 2. 桌面端用官网 API Key 解锁后仍显示「试用版」

**已取证，根因确定**（2026-08-07 维护者机器实测）：

```
license.json  → mode=trial, activatedAt=2026-08-06T11:31:30Z（试用码解锁）
/api/license/status  → {"mode":"trial","plan":"trial","unlocked":true}
/api/account/status  → {"connected":true,"connectedAt":"2026-08-07T06:55:20Z",
                        "keyMasked":"awdk_****BGVg","platformAiAvailable":true}
```

**授权票据与账户连接是两条互不相干的状态，而设置页只读了前者**：
`userprofile.vue:383` 的「当前模式」是 `licenseInfo.mode === 'trial' ? '试用版' : '正式版'`，
完全不看账户连接状态。用户 8-06 用试用码解锁、8-07 才在设置页连的账户，于是这一行永远是试用版。

**方向不对称是根**：解锁页粘 `awdk_` 会顺手 `AccountService.connect()`（LicenseController.java:56/:77），
但反过来在设置页连账户**不会**回头提升授权状态（`AccountController.connect` 完全不碰 LicenseService）。

**修法必须避开一个坑**：不能在连账户时把 license.json 改写成 account 模式——
`activateAccountKey` 里是 `new State()`（LicenseService.java:173），会把原试用码票据抹掉，
用户将来一断开账户就掉回未解锁。**所以只统一展示口径，不动落盘状态**：
- `LicenseService.status()` 增加 `accountConnected` 与 `edition`（trial | paid）两个字段，
  `edition` = 账户已连接 或 mode=account → paid，否则 trial；
- 前端三处展示点统一读 `edition`：userprofile 的「当前模式」行、project-overview 顶栏 chip
  （现有的 accountConnected 优先逻辑正好等价，改成读 edition 后口径唯一）、试用版说明弹窗。

断开账户后自动回落试用版，试用码票据始终完整保留。

**顺带修的一条**（本例未触发，但是真缺陷）：`LicenseController.connectAccountIfKey` 的失败被
完全吞掉（:70-80 catch 后忽略），「解锁成功但账户没连上」用户完全看不见。

### 3. 系统设置里「插件广场」的交互与其他设置项不一致

**已核实**：`admin.vue:1122` 这一项带 `route: '/pages/plugin-market/plugin-market'`，
而 `onNavTap`（:1510）见到 `route` 就 `uni.navigateTo` 整页跳走——其余设置项都是页内切内容区。
于是同一列导航里，别的项是「换右边」，这一项是「换整页且多压一层页面栈」。

**注**：广场的主入口早已改成左栏 VS Code 扩展栏形态（MarketSidebarPanel + MarketDetailPane），
`plugin-market.vue` 整页版是留给 admin 跳转与直链的历史形态。

### 4. 标题栏重复

**已核实**：`office-addin/manifest.xml:18` 的 `<DisplayName DefaultValue="AI Workdeck"/>`
决定了 Office 自绘的任务窗格标题；`App.vue:4` 又画了一条 `.brand` 文本，内容一模一样。

### 5. 消息丢失：切页面后系统消息与后续回复收不到

**根因确定，两条同源路径**：
- `App.vue:22-34` 用 `v-if="view === 'settings'"` / `v-else` 切视图 → 进设置时 ChatView
  **被卸载**，`onBeforeUnmount` 关掉 SSE（ChatView.vue:268-270），`messages` 数组一并销毁；
  回到对话是一个全新组件，这一轮的回复此后永远收不到。
- Word 切换文档 / 关闭再开窗口会重建任务窗格，同样丢光内存态——会话 ID 只在
  闭包变量里（ChatView.vue:80），没有任何持久化。

**已有的可复用件**：后端 `GET /api/ai/history?conversationId=`（AiChatController.java:90）
能按会话回灌历史；SSE 重连与 `run_state` 兜底解锁已经在 `sse.js` 里做好了。

### 6. 响应速度慢

**没有实测数据，但结构性问题可见**：`send()` 里是三次串行往返
（ChatView.vue:218-243）：签发会话 ID → 建 SSE 连接 → 读文档正文 → 发消息。
SSE 只在发第一条消息时才开始建连，建连本身要一个 RTT；
「随消息附带当前文档正文」默认勾选，每条消息都把整篇正文重读一遍、重传一遍
（上限 20 万字，officeExecutor.js:25）。对着云后端（北京 ECS）时这几段延迟会叠加。

**做法**：先埋点量出四段耗时（送出 → 后端受理 → 首个 token → 完成），再按数据决定优化顺序。
不先量就改是在猜。

### 7. 修订没有遵循最小修订原则

**根因确定**：`officeExecutor.js:131-133` 的 `replace_text` 直接对整个命中 Range
`insertText(replaceText, replace)`，在 TrackAll 下 Word 记录的就是「整段删除 + 整段插入」。
`insert_text` 走的是同一条路。

**桌面端早就解决过这个问题**：LOWA 侧有字符级最小编辑
（`office_thread.js` 的 `minimalEdits()` / `applyMinimalRedline()`，PR#188），
公共前后缀裁剪 + 有界 LCS，从右到左应用。插件这条桥从来没接过这套口径。

**移植难点**：Word.js 没有「Range 内字符偏移」API，不能像 UNO 那样按下标切。
落地方式是：算出差异段后，在命中 Range 内**二次 search 定位**该差异段再替换；
纯插入（差异段旧文为空）与纯删除各走一条分支。

### 8. Word 格式调整能力弱

**根因确定**：Word 面的 office_* 工具只有六个，**全部是文本面**——
`get_text / get_selection / search / replace_text / insert_text / add_comment`
（OfficeEditTools.java、officeExecutor.js 的 HANDLERS），一个格式工具都没有。
系统提示词里点名的工具清单也只有这六个（ContextAssemblerService.java:270 与 :471），
所以模型连「我能排版」都不知道，让它「美化格式」它只能改文字。

**对照**：桌面端 LOWA 侧格式面早就齐了——`doc_format_selection`（字符）、
`doc_set_paragraph_format`（对齐/标题级别/行距/段距/缩进）、`doc_set_numbering`、
`doc_format_table`、`doc_insert_table`、`doc_get_formatting`、`doc_apply_standard_format`。
插件缺的就是这一整面。

**Office.js 能力核对**（决定哪些能做、要什么版本）：
| 需求 | Office.js 面 | 需求集 |
|---|---|---|
| 字体、字号 | `range.font.name / size` | WordApi 1.1 |
| 下划线（含波浪线） | `range.font.underline` = `Word.UnderlineType.wave/single/double/dotted...` | 1.1 |
| 删除线 | `range.font.strikeThrough` / `doubleStrikeThrough` | 1.1 |
| 行间距、段前段后、缩进 | `paragraph.lineSpacing / spaceBefore / spaceAfter / firstLineIndent / leftIndent` | 1.1 |
| 对齐、标题级别 | `paragraph.alignment`、`paragraph.styleBuiltIn` | 1.1 |
| 自动编号 | `paragraph.startNewList()` + `list.setLevelNumbering(...)` / `attachToList` | 1.3 |
| 表格边框 | `table.getBorder(Word.BorderLocation.all).type/color/width` | 1.3 |
| 表格对齐 | `table.alignment` / `table.horizontalAlignment` | 1.3 |

结论：全部可做，最高只要 WordApi 1.3（比现有的批注功能要求的 1.4 还低），不新增宿主版本门槛。

### 9. 「项目」不应是必选项

**已核实是后端硬要求**：`AiAgentController.startSession` 在 `projectId == null` 时直接 403
（:142），`ConversationIssuanceController` 同样（:44）。插件端 `canSend` 也把 projectId
当作发送前置条件（ChatView.vue:89-90）。所以现在不选项目一个字都发不出去。

projectId 不是装饰——它是工具的租户隔离维度（注释在 AiAgentController.java:136-138：
「projectId 完全由请求体给定……按项目隔离的工具反而成了跨租户读写别家文档的入口」），
**不能简单放开为 null**。

**实现选型（已定，2026-08-07）**：走 **(B) 懒建「插件临时项目」**——新增幂等端点，
用户没有任何项目时创建一个名为「插件临时项目」的项目并返回，已有项目则原样返回列表。
插件端逻辑变成「有项目 → 显示下拉可选；没有 → 静默用插件临时项目，不让用户选」。
正好对上「他在别的地方有项目，这个地方才可以选」的要求，且租户隔离模型一字不动。

（被否的方案 A：后端允许 `projectId=null` 并给一条无项目的会话路径——要重做鉴权面与工具注入，风险高。）

---

## 二、工作计划（按 PR 分批）

估时是纯开发 + 自测，不含真机回归与发版。

### 批次 1：接入门槛与界面一致性（约 1 人天）

| 编号 | 内容 | 落点 |
|---|---|---|
| 1-1 | 插件设置单字段化：默认后端地址构建期注入（`VITE_ADDIN_SERVER_URL`，缺省 `https://addin.aiworkdeck.com`），主界面只留一个「官网 API Key（awdk_）」输入框 + 一个「连接」按钮；后端地址与 awdt_ 设备令牌整体收进「高级设置」折叠区（服务律所自建服务器） | SettingsView.vue、settings.js、vite.config.js、build-manifest.mjs |
| 1-2 | 桌面端补设备令牌管理界面（生成/列表/吊销），放设置页「账号安全」分区；后端三端点已存在，纯前端 | userprofile.vue 或 admin.vue、services/api.js |
| 1-3 | 插件标题栏去重：删掉 App.vue 的 brand 文本，header 压成一条 ~32px 控制条（项目下拉 + 设置图标） | App.vue |
| 1-4 | 插件广场入口页内化：admin 导航项去掉 `route`，内容区内嵌 `MarketPane`，与其他设置项同一交互 | admin.vue、plugin-market.vue（保留直链兼容） |
| 1-5 | 授权展示口径统一：`LicenseService.status()` 增加 `accountConnected` + `edition`（trial\|paid），前端三处展示点（userprofile 授权行、顶栏 chip、试用版弹窗）统一读 `edition`。**不改 license.json 落盘状态**（避免抹掉试用码票据） | LicenseService.java、userprofile.vue、project-overview.vue |
| 1-6 | 账户连接失败可见化：`connectAccountIfKey` 的结果并入 activate 响应，解锁页展示「已解锁，但账户未连上」+ 重试 | LicenseController.java、unlock.vue |

验收：sideload 后只填一个 Key 即可对话；桌面端能生成令牌；广场入口与其他设置项行为一致；
任务窗格顶部只有一条标题。

风险：1-1 需要先确认 `addin.aiworkdeck.com` 上 `security.awdk-login-enabled=true`
且 CORS 白名单已含插件正式 Origin（见 office-addin 领域文档的「云后端首次上线必配项清单」）。

### 批次 2：会话不丢 + 项目非必选（约 1.5 人天）

| 编号 | 内容 | 落点 |
|---|---|---|
| 2-1 | 会话状态提出组件：messages / conversationId / streaming / SSE 连接迁到模块级 store，视图切换不再卸载会话；ChatView 只做渲染 | 新增 `taskpane/lib/chatSession.js`、App.vue、ChatView.vue |
| 2-2 | 会话持久化与回灌：conversationId 落 localStorage；任务窗格重建时 `GET /api/ai/history?conversationId=` 回灌 + 重连 SSE（run_state 兜底解锁已有，不动） | chatSession.js、lib/api.js |
| 2-3 | 项目非必选：后端新增幂等端点「取我的项目，空则建『插件临时项目』」；插件端有项目才显示下拉，无项目静默直连 | ProjectController、ChatView.vue、App.vue |

验收：发消息中途切到设置再切回来，流式继续、tool chip 继续走完；关掉任务窗格重开，历史还在；
全新账号不选项目直接能发。

### 批次 3：最小修订（约 1 人天）

| 编号 | 内容 | 落点 |
|---|---|---|
| 3-1 | 把 LOWA 侧的差分口径移植到插件：公共前后缀裁剪 + 有界 LCS，得到最小差异段 | 新增 `taskpane/lib/minimalEdit.js`（与 office_thread.js 的 minimalEdits 同口径） |
| 3-2 | `replace_text` 改为「只对差异段落修订」：命中 Range 内二次 search 定位差异段，从右到左应用；纯插入/纯删除各一条分支；定位失败时回退整段替换并在返回值标注 `via` | officeExecutor.js |

验收：「我爱你」→「我恨你」在 Word 修订面板里只有一处删除「爱」+ 一处插入「恨」；
接受/拒绝单条修订行为正常。

### 批次 4：Word 格式能力（约 2.5–3 人天，建议拆两个 PR）

**4A 字符与段落面**（约 1.5 人天）
- `office_format_text`：字体、字号、加粗/斜体、下划线（含 wave 波浪线）、删除线、字色、底纹
- `office_set_paragraph_format`：对齐、行距、段前段后、首行缩进/左右缩进、标题级别
- `office_get_formatting`：读当前选区/锚点的格式（改格式前的「眼睛」，与 doc_get_formatting 同定位）

**4B 编号与表格面**（约 1–1.5 人天）
- `office_set_numbering`：项目符号 / 阿拉伯数字 / 中文数字 / 多级
- `office_format_table`：边框（全部/外框/内框，线型线宽颜色）、表格对齐、首行加粗、列宽
- `office_apply_standard_format`：律所标准格式全文套用（与桌面端 `HOUSE` 常量、
  后端 `DocxStyleHelper` 同一套规范——**规范改一处就要改三处**）

每个新工具都必须走**三件套 + 宿主标注**（领域文档里的硬规则）：
后端 `OfficeEditTools` 加 `@Tool` → `officeExecutor.js` 的 `HANDLERS` 加实现 →
`COMMAND_DISPLAY_NAMES` 加中文名 → `COMMAND_HOSTS` 标 word。漏任何一环 = 30 秒超时空转。

另外**必须同步改系统提示词两处**（ContextAssemblerService.java:270 与 :471）：
现在点名的工具清单只有六个文本工具，不加进去模型不会知道自己能排版——
这一条比工具本身更关键，是「让它美化格式它没动排版」的直接原因。

验收：对一篇未排版的合同说「按律所标准格式排版」，能看到字体/字号/行距/缩进/编号真实变化；
表格加边框、正文加波浪线下划线均可指令完成。

### 批次 5：性能（约 1 人天，先量后改）

| 编号 | 内容 |
|---|---|
| 5-1 | 埋点：送出 → 后端受理 → SSE 首字节 → 首个 token → 完成，四段耗时打到控制台与遥测 |
| 5-2 | 进面板即建 SSE（不等第一条消息）；会话签发与读文档并行化 |
| 5-3 | 正文按内容哈希去重：未变化不重传；「附带正文」默认改为「变化时才带」 |
| 5-4 | 按 5-1 的数据决定是否还需要后端侧优化（上下文组装、工具轮次） |

验收：同一台机器同一篇文档，改前改后四段耗时对比表。

---

## 三、已定的决策（2026-08-07 维护者确认）

1. **默认后端地址** = `https://addin.aiworkdeck.com`。开工前先核那台机的
   `security.awdk-login-enabled=true` / CORS 白名单含插件正式 Origin / `AWD_PLATFORM_KEY_SECRET` 三项。
2. **项目非必选**走懒建方案，项目名定为「**插件临时项目**」。
3. **格式工具本轮只做 Word 面**，Excel/PPT 的格式面留到下一轮。
4. **「试用版」已取证定位**，见第一节第 2 条，改法为展示口径统一（批次 1-5）。

---

## 四、排期建议

总量约 7 人天。建议顺序 **1 → 2 → 3 → 4A → 4B → 5**：
批次 1、2 是「用不起来」的闸门，先解；批次 3 是律师日常直接踩的痛点；
批次 4 是能力补齐（最大的一块）；批次 5 数据驱动，放最后不影响前面的验收。

批次 1+2 可以合成一个发布小版本（补丁通道，0.11.x）；批次 4 涉及新工具与提示词，
建议单独走一个小版本并跑全量 e2e。

## 五、验证口径

- 后端：`cd backend && mvn test`（JDK 21，默认 25 会 SIGBUS）；
  相关用例 `OfficeEditToolsTest / OfficeBridgeServiceTest / ContextAssemblerServiceTest /
  ToolRegistryCapabilityFilterTest / LicenseServiceTest / AccountServiceTest`
- 插件：`cd office-addin && npm run build` + manifest 校验；sideload 手测清单在 `office-addin/README.md`
- 前端：`cd frontend && npm run check:emits && npm run build:h5`
- e2e：批次 1（广场入口）与批次 2 改动后跑 `npm run test:app-e2e`；批次 4 若同步动了
  LOWA 侧 HOUSE 规范则加跑 `npm run test:lowa-e2e`
