# 登录后置与欢迎标签：启动落点向 VS Code 对齐

2026-09-29。维护者定调（dev-board#1027）：AI WorkDeck 是给律师、乃至所有以 Office 文档为主要工作对象的人的
IDE 类产品，终局与 VS Code 分庭抗礼、用户面可能更大。取舍原则是「尽可能多人用」。由此：

- **登录后置**：打开应用不要求登录。只有真正需要账户的功能在用到时就地登录。
- **标签页中心**：欢迎页是一个标签，日历是一个标签，能放进标签的都放进标签。
- **Agents 四栏布局现阶段不学**（维护者 2026-09-29 拍板，理由见 §9）。

本文推翻 `2026-08-18-desktop-account-required-design.md`「官方桌面版必须账户登录」的启动门，
回到 `2026-08-05-commercialization-redesign.md` §1「桌面端永远不需要登录」的精神。
08-18 那份文档里关于 `local-mode` 的论证（§1）**继续有效**：本期同样不翻 `security.local-mode`。

## 1. 排查结论（改动前的事实基线）

- 拦人的门只有一处：`frontend/src/pages/launch/launch.vue:83` 的 `if (!status.unlocked) reLaunch(unlock)`。
  后端没有任何过滤器、切面或接口以「未解锁」为由拒绝请求；`FeatureCatalog.APP_UNLOCKED` 在前后端都没有调用方。
- 真正需要账户的功能：平台 AI 对话、平台网关服务（OCR / 企查查 / 北大法宝 / Tushare / 联网搜索）、广场付费项下载、
  官方团队案件库、手机端中转、平台档会议转写与语音听写。不需要账户的：项目、文件、编辑、版本记录、广场免费项、
  本地 TTS、自建团队服务器连接。有免费额度的：剪贴板（20 条 / 3 天）、暂存区（20 个文件 / 500MB）。
- 本机数据挂在机器级的本机用户上，与账户正交（`LocalIdentityService`）。先匿名用后登录不产生孤儿项目。
  反向风险：换账户后同一批本机数据归到新账户名下，本机用户名随之改名，断开后不回退。
- 工作台 `project-overview.vue` 不带 `?id=` 打开是一个未设计过的半空壳：不崩，但十来个面板空转、偶发 `/api/projects/null/...`。
- 进项目是整页 `reLaunch`，所有标签销毁；标签列表不持久化，只记按左栏模式的激活 id。
- AI 会话严格按项目隔离（`GET /api/ai/conversations?projectId=` 必填），没有跨项目会话列表。
- `legacy-grace-until` 是 2026-09-30。从当天起存量试用票据 `unlocked:false`；靠预置试用票据冷启动的 e2e 会全红。

## 2. 目标与非目标

**目标**
- 启动直接落到工作台外壳，中央是「欢迎」标签，不经过登录页、解锁页和项目列表页。
- 需要账户的功能在触发点就地弹登录，登录成功回到原动作，绝不踢回启动页。
- 欢迎标签结构对齐 VS Code：Start / Recent / 上手指南 / 「启动时显示欢迎页」。
- 日历成为工作台里的单例标签，无项目态照样可用。
- 标签持久化：重启回到上次的标签集合与激活标签，全局与按项目各一份。
- 三种到期态（离线复验到期、存量试用到期、Key 吊销）一律降级为「未连接账户」，不再锁整机。

**非目标（本期不做）**
- 不翻 `security.local-mode`；不动 `LocalModeAccessFilter` / `LocalModeLoopbackGuard`；不动 `LicenseService` 的状态机与落盘。
- 不重构「进项目 = 整页 reLaunch」。VS Code 打开文件夹同样重载窗口，这是可接受的终态。
- 不给 AI 会话加「无项目」归属；无项目态的 AI 栏本轮隐藏。
- 不做跨项目会话列表、不做 Agents 四栏布局、不做多窗口。
- 不做防篡改。解锁门本来就不是 DRM。
- 不动 Office / WPS 插件与手机端的登录链路（它们打的是云后端，与桌面解锁门无关）。

## 3. 启动分流（`launch.vue`）

```
非桌面环境            -> /pages/login/login（不变，浏览器访问团队服务器）
后端就绪轮询          -> 不变
本机身份 needsSelection -> /pages/identity/identity（不变）
其余                  -> reLaunch /pages/project-overview/project-overview（不带 id）
```

`!status.unlocked` 分支删除。`getMyProjects` 不再是分流条件（失败不阻塞进外壳，欢迎标签的 Recent 自己处理空态与错误）。
`launch.vue:111` 那条过时注释一并清掉。

`sidebar-shell.md` 导航总规则①「启动一律落项目列表页」改为「启动一律落工作台外壳（无项目态），欢迎标签打开」。
`scripts/check-navigation-contract.mjs` 对 launch 落点的断言同步改。

**首启初始化迁移**：`unlock.vue` 的 `completeSetup()`（`submitWizard({activeProvider:'AWD_CLOUD', crossBorderConsent:true})`
+ 协议同意版本记录）原本绑在登录成功上。拆成两半：
- `activeProvider` 默认值改由后端在首启时给（`DataInitializer` 或等价的启动期回填，local-mode 下 `ai.activeProvider` 为空则置 `AWD_CLOUD`），前端不再负责。
- 协议同意与跨境同意挪进登录弹层（§5），因为需要账户的功能正是数据出境的功能。

## 4. 工作台无项目态

`project-overview.vue` 增加 `hasProject` 计算属性（`projectId` 非空）。无项目态下：

| 区域 | 有项目 | 无项目 |
|---|---|---|
| 左栏 rail | 现状 | 只保留全局项：**项目**（新增，见下）、日历、广场、剪贴板；底部：账户入口、设置 |
| 左栏面板 | 现状 | 文件树、搜索、版本、成员、语音、脱敏、诉讼可视化、概览、依据、收藏、开发者面板不挂载 |
| 中央 | 标签 | 标签；空态时自动打开欢迎标签 |
| 右栏 AI | 现状 | 不渲染（不是禁用，是不渲染，避免 `projectId="null"` 请求） |
| 顶栏 | 项目名、协作 chip、成员栏 | 应用名；无协作 chip、无成员栏 |
| 暂存区 / `ensureStagingFolder` | 现状 | 不调用 |

- 存储 key：`project_null_*` 改为 `global_*`。
- **左栏「项目」面板**（新增 rail 项，key `projects`）：项目列表的方块/列表视图、搜索排序、新建 / 打开文件夹、从团队案件库取案卷。
  内容组件从 `pages/project-list/project-list.vue` 抽出为 `components/project-list/ProjectListPane.vue`，两处复用。
  点击项目 → `reLaunch` 进带 id 的工作台（不变）。有项目态下这个面板同样可用（切换项目）。
- **rail 底部账户入口**（对应 VS Code 的 Accounts 图标）：未登录显示「登录」图标与文字，点击打开登录弹层；已登录显示头像，
  点击是现有的头像下拉（个人中心、我的日程、退出登录）。有无项目两态都渲染。现顶栏里的头像下拉与试用 / 宽限 chip
  由此入口承接，顶栏不再放账户态。
- 子面板逐个审 `projectId` 守卫：`:project-id` 为 null 时不发请求。`ProjectCalendarPane.vue:112` 的 `required` 去掉或改为可选。
- 窗口标题、菜单状态、`activityTracker` 在无项目态传空，不传 `null` 字符串。

**项目列表页 `pages/project-list/project-list.vue`** 退成直链薄壳：`redirectTo` 工作台外壳并打开「项目」面板。
路由保留（直链、e2e、`globalBack` 仍可能到达）。`project-home.vue` 薄壳不变。

## 5. 登录就地触发

### 5.1 前端：`utils/requireAccount.js` + 登录弹层

```js
// 返回 Promise<boolean>：已连接账户直接 true；否则弹登录层，登录成功 true，取消 false
await requireAccount({ reason: 'ai' | 'market' | 'team' | 'mobile' | 'meeting' | 'gateway' })
```

- 登录弹层组件 `components/account/AccountLoginDialog.vue`：把 `pages/unlock/unlock.vue` 的登录卡抽成组件复用
  （站点分段控件、手机号 / 邮箱验证码、两枚同意勾选、语言切换沿用），加「取消」出口。弹层挂 body，
  用 `AwdDialog` 同一套宿主机制（见 `2026-09-23-desktop-login-and-dialog-redesign-design.md`）。
- `reason` 决定弹层顶部一句说明（「使用 AI 需要登录账户」等），文案走 i18n 两语言。
- 登录成功后：`awd:account-changed` 广播（复用现有账户状态刷新链）；调用方拿到 `true` 后**继续原动作**，
  不 reLaunch、不刷新页面。
- `pages/unlock/unlock.vue` 保留为薄壳页（直链与 e2e 形态断言用），内容换成同一个组件。

**接入点**（缺一项算没做完）：

| 触发点 | 现状 | 改后 |
|---|---|---|
| AI 发送（`ChatInterface.vue`），`activeProvider` 为平台通道且未连接账户 | 后端报错 | 发送前 `requireAccount('ai')`；后端 4011 兜底也弹 |
| 广场付费项（`MarketPane` / `MarketSidebarPanel` / `MarketDetailPane`） | 「需连接账户」跳设置粘 Key | `requireAccount('market')` 后继续下载 |
| 团队（`TeamPanel`、`CollabDialog`、`InviteMemberDialog`） | 「尚未连接账户」空态 | 空态里的按钮改为 `requireAccount('team')` |
| 手机端中转（设置里的手机同步区） | 静默不工作 | 未登录显式提示 + 登录按钮 |
| 会议转写平台档（`MeetingRecordingPanel`） | 平台档不可用 | 选平台档时 `requireAccount('meeting')` |
| 平台网关服务（OCR 等） | 网关 `NOT_CONNECTED` | 统一在 `api.js` 收到 4011 时弹层，成功后由调用方重试（调用方自己决定是否重试） |
| 设置页「账户与用量」（`AdminPane.vue:252`） | 只有粘 `awdk_` Key | 加「登录」按钮开弹层；粘 Key 保留为高级入口 |

### 5.2 后端：统一「需要账户」的失败形状

新增业务码 **4011 `account_required`**（实现前 `grep -rn 4011 backend/` 确认未被占用，被占用则顺延到下一个空号并在本文更正）。
形状：HTTP 200，`{code:4011, kind:"NOT_CONNECTED", reason:"platform_ai"|"gateway"|"market"|"team"|"mobile"|"meeting"|"dictation", message}`。

- `GlobalExceptionHandler` 补 `AccountException` 处理：`kind=NOT_CONNECTED` → 4011；其余 kind 保持现有映射。
- Agent SSE 的 `error` 事件加 `code` 与 `kind` 字段（只增不改）。
- `GatewayException(NOT_CONNECTED)` 的响应加 `code:4011`，`gatewayKind` 等现有字段不动。
- 会议转写、官方案件库、广场付费项、语音听写四处改为抛 `AccountException(NOT_CONNECTED)` 或直接返回 4011 形状；
  听写那条 HTTP 502 纯文本改成与其他一致的 JSON。
- **绝不用 4010**：它会触发前端清会话。`api.js` 的 4010 分支一字不动，另加 4011 分支。

### 5.3 三种到期态降级

`LicenseService.status()` 形状不变。前端不再读 `unlocked` 做分流；`graceKind` / `daysRemaining` 的提醒挪到
账户入口的下拉里（「需联网验证 · 剩 N 天」），到期后账户入口显示「未登录」，需要账户的功能走 §5.1 弹层。
`AccountService.status()` 的 `connected` 是唯一的「已登录」判据。

### 5.4 退出登录（`utils/signOut.js`）

三分支合成一个：`disconnectAccount()`，成功后 `awd:account-changed` 广播，**不 reLaunch**，停在当前页面。
`mode=account` 时不再调 `deactivateLicense()`（票据留着无害，且 08-18 的「mode=trial 绝不 deactivate」红线自然消失）。
「解除授权」高级动作保留在账户与安全里，行为不变。

### 5.5 换账户的数据归属（维护者拍板：本机项目属于这台电脑）

- 登录成功时若本机此前连接过另一个账户（`accountFingerprint` 变化），弹一次说明：「本机项目属于这台电脑，不随账户走」，
  不阻塞。指纹记录落 `SystemSetting`（与 `LocalIdentityService` 的指针同库同生死）。
- `AccountService.disconnect()` 后，`AccountIdentitySync` 把本机用户行的 `displayName` 回退为哨兵「本机用户」、
  清头像地址（读出口的本地化规则不变，见 licensing-billing.md）。
- `/api/account/login` 成功后立即做一次身份同步（与 `/connect` 对齐），不等下一次 `status`。

### 5.6 合规同意点

- 协议同意 + 跨境同意：登录弹层里的两枚勾选，提交前置（现状搬家）。
- 匿名使用统计：默认开不变。欢迎标签底部一行非阻塞提示「已开启匿名使用统计，可在设置中关闭」，点击跳设置；
  提示关闭后不再出现（本机记忆）。这与 VS Code 首启的 telemetry 通知同款。
- `legal/PRIVACY.md` 核对：本期不新增任何出站请求。

## 6. 欢迎标签

`tabType:'welcome'`，id `welcome`，单例。内容组件 `components/welcome/WelcomePane.vue`。

```
AI WorkDeck                                   [右侧] 上手指南
让工作，回到一处。                                  · 开始使用 AI WorkDeck
                                                    · 让 AI 修改一份文档
Start                                               · 安装第一个插件
  新建项目文件夹
  打开已有文件夹
  从团队案件库取一份案卷
  连接团队服务器
  凭访问码进入案卷（客户）
Recent
  <项目名>   <路径>        （最多 8 条，按最近打开；无项目时一句提示）
  更多… → 打开左栏「项目」面板

[ ] 启动时显示欢迎页                 已开启匿名使用统计，可在设置中关闭
```

- 标题与 lead 文案取 `design/copy/brand-copy.json`，不自创。
- Start 五项分别接现有动作：新建 / 打开文件夹（`config/commands/file.js` 同一套）、取案卷（列表页现有入口）、
  连接团队服务器（设置里的连接动作）、**凭访问码进入案卷**（dev-board#1026 协同项：落点仍走 `clientLogin` API，
  CLIENT 角色进入后落到该案卷；实现时与 `login.vue:112` 的客户入口共用一个组件，客户输码连的是案件库服务器，
  不是本机回环后端）。
- 上手指南三张卡指向官网现有文档页与启动视频（`2026-08-20-launch-video-design.md`），本期不新写教程内容。
  卡片不做进度追踪。
- 「启动时显示欢迎页」：本机记忆，默认开。关掉后启动进无项目态外壳但不自动开欢迎标签（中央空态显示一行提示 +
  「打开欢迎页」链接）；菜单「帮助 → 欢迎」随时可开。
- 有项目态下同样可以打开欢迎标签（菜单入口），Recent 与 Start 行为一致。
- 欢迎标签不能当活跃文档，不能拖进 AI 上下文（`NON_FILE_TAB_TYPES` 加 `welcome`，id 非数字自然满足 `isContextEligibleTab`）。

## 7. 日历标签

- `pages/calendar/calendar.vue` 主体抽成 `components/calendar/CalendarPane.vue`（FullCalendar 引入随之搬家），
  页面退成薄壳（直链、提醒 `taskReminders.js` 的落点仍可用，进入后 `redirectTo` 工作台并开日历标签）。
- `tabType:'calendar'`，id `calendar`，单例；`NON_FILE_TAB_TYPES` 加一行；图标补一个。
- 四处 `leaveWorkbench('/pages/calendar/calendar')`（`goCalendar`、`ProjectCalendarPane.openGlobalCalendar`、
  头像菜单「我的日程」、列表页入口）改为 `openCalendarTab()`。
- 标签内「进入项目」「打开文件」：同项目就地打开，跨项目才 `reLaunch`。
- 无项目态可用（数据本就走 `loadGlobal`）。
- 左栏「日历」面板（`ProjectCalendarPane`，议程式）保持不变。

## 8. 标签持久化

- 快照内容：标签数组（左右两窗格）每项的 `{id, name, fileType, tabType, 以及各类型自己的定位字段}` + 激活 id + 分栏状态。
- 两份：全局（无项目态，key `global_tabs`）与按项目（`project_${id}_tabs`），存 `uni.storage`。
- 恢复时机：`onLoad` 完成项目信息加载后；文件类标签逐个校验文件仍存在，不存在的静默丢弃；
  单例标签（welcome / calendar / settings / market-detail）按 id 去重。
- 现有 `project_${projectId}_activeTabsByMode` 逻辑保留，与快照合并时以快照为准。
- 写入时机：标签增删改、激活切换、`leaveWorkbench` 前，节流 300ms。

## 9. 关于 VS Code Agents 四栏布局（维护者 2026-09-29 拍板：现阶段不学）

Agents 窗口是「会话中心」，单位是 agent 任务，服务于把整块活交给 agent 再审 diff 的开发者。我们的单位是文档与案件，
人在读写、AI 在旁；现有「文件左 / 编辑器中 / AI 右」正是 VS Code 经典布局加 Copilot 侧栏。前置条件也不具备：
会话按项目隔离、AI 不能跨项目跑后台任务、没有多窗口。

借两样：欢迎标签（本期）；「按项目归类的跨项目会话列表」（后续单开卡，与「无项目态 AI 栏」同一件事）。
等 AI 能接长任务、会话跨项目后再评估「对话中心视图」。

## 10. 测试与验证

**后端 `mvn test`（JDK 21）**
- `GlobalExceptionHandlerTest`：`AccountException(NOT_CONNECTED)` → 4011 形状；其他 kind 不变。
- 四处改抛 `AccountException` 的服务各补一条「未连接账户 → 4011」。
- `AccountIdentitySync`：disconnect 后本机用户行回退哨兵名与空头像。
- `DataInitializer` / 启动回填：local-mode 空库 `ai.activeProvider` 置 `AWD_CLOUD`。

**前端单测**
- `requireAccount`：已连接直接 true；未连接弹层；取消 false；成功 true 且广播。
- `tests/unlock`：登录卡组件化后形态断言迁移到组件。
- 标签快照：序列化 / 反序列化 / 去重 / 丢弃不存在的文件标签。
- `check:nav`：launch 落工作台外壳；列表页与日历页薄壳的 redirect 合规。
- `check:locales`、`check:emits`、`check:brand-copy`、`build:h5`。

**e2e**
- app-e2e J1 重写为「启动 → 外壳 → 欢迎标签」，断言 Start 五项、Recent、账户入口「登录」态；
  新增「AI 发送触发登录弹层」旅程（未连接账户的隔离后端）。
- desktop-e2e / feedback-e2e / meeting-e2e 的 setup 不再 `ensureUnlocked`，从 `mode=none` 起跑；
  `tests/_lib/license-gate.mjs` 保留给 fork 路径（`trialCodeEnabled=true`）用，默认套件不再调用。
- desktop-e2e 补：日历标签打开 / 关闭 / 重启恢复；欢迎标签勾选关闭后启动不自动开。
- UI 真渲染走查（截图）：无项目态外壳、欢迎标签、登录弹层三态（手机号 / 邮箱 / 取消）、日历标签。

**过渡（与本规格独立，2026-09-30 前必须落）**：隔离后端的 e2e 冷启动改为注入
`-Dsecurity.license.trial-code.legacy-grace-until=<未来日期>` 跑宽限态（08-18 §11 本来就要求用过去 / 未来两个日期各跑一次），
不出 v0.50.1 补丁版。本规格落地后这条注入随 `ensureUnlocked` 一起退出默认套件。

## 11. 文档与口径

- `licensing-billing.md`：「判据只有 launch 一处」改为「启动不设门；需要账户的功能以 4011 触发登录弹层」；
  「退出登录两层」表改为单动作；新增 4011 契约。
- `sidebar-shell.md`：导航总规则①改口径；标签类型表加 `welcome` / `calendar`；无项目态区域表；标签快照契约；
  rail 新增 `projects` 与底部账户入口。
- `README.md` / `README.zh-CN.md`：「注册账号即可使用」改为「下载即用，使用 AI 与广场付费内容时登录」。
- 官网 `/updates` 版本记录随下一个大版本写。

## 12. 不做的事（复述，防止实施时漂移）

不翻 `local-mode`；不动 `LicenseService` 状态机与 `license.json`；不重构进项目的 reLaunch；不做跨项目会话；
不做 Agents 布局；不做多窗口；不动插件与手机端登录链；不新增出站请求；不用 4010；不删 `unlock.vue` 与
`project-list.vue` 的路由。

## 13. 卡片拆分（dev-board）

| 卡 | 范围 | 产品线:模块 |
|---|---|---|
| #1027（本卡） | 规格与总协调、最终收口 | p/aiworkdeck:账户与计费 |
| A | e2e 宽限日期注入保 CI（2026-09-30 前） | p/aiworkdeck:工程与稳定性 |
| B | 登录就地触发：requireAccount + 登录弹层 + 后端 4011 + 到期态降级 + signOut + 同意点与首启初始化迁移 + 换账户提示与断开回退 | p/aiworkdeck:账户与计费 |
| C | 无项目态外壳 + 欢迎标签 + 左栏「项目」面板 + rail 账户入口 + 列表页薄壳 + 客户访问码入口 | p/aiworkdeck:工作台界面 |
| D | 日历标签（CalendarPane 抽取 + 标签接线 + 四处入口改造） | p/aiworkdeck:工作台界面 |
| E | 标签持久化（全局 + 按项目快照） | p/aiworkdeck:工作台界面 |
