---
name: sidebar-shell
description: 侧边栏与工作台外壳领域。任务涉及左侧 rail/左栏、工作台四列布局、面板切换、页面路由与页面栈、设置入口、project-overview.vue 结构、CSS 体系时，先读本文档再动代码。
---

# 侧边栏与工作台外壳 领域地图

职责边界：工作台整体布局、左侧 rail 与左栏、面板切换状态机、页面路由。各面板内部逻辑归 utility-tools / plugin-system；右栏聊天内容归 ai-chat；编辑器归 doc-editor。

## project-overview.vue 内部地图（4939 行，主战场）

> **下面这份 :xxx 行号地图早于「project-overview 分阶段拆分」，多数已漂（实测：
> project-header :3 → :4、left-rail :172 → :210、sidebar-left :354 → :427、
> workbench :595 → :656、data() :1314 → :1560、computed :1557 → :1814、
> methods :2261 → :2671）。结构描述仍然准确，**引用任何具体行号前自己 grep 一遍**。
> 已实测的锚点：`switchToProject` :2743（内部 reLaunch 在 :2747）、`goAllProjects` :2755、
> `goToUserProfile` :3825、`goToSystemSettings` :3828、`isActiveOverviewInstance` :3439、
> `beforeUnmount` :2084、`onLoad` :2203、`onShow` :2287、`mounted` :2348、
> 顶栏切换器的 `.switcher-all`「全部项目…」:41。`toggleLeftPane` **已不在本文件里**，
> 拆到同目录 `panelSwitching.js:7`。

三大块：template :1-1425 / script :1427-4936 / style **已外置**——`:4939` 只有一行
`<style lang="scss" scoped src="./project-overview.scss">`，样式实体在同目录
`project-overview.scss`（4316 行）。逻辑也已拆出九个同目录 .js 模块：
`agentClientActions` / `clipboardBridge` / `fileOpenTabs` / `librePool` / `ocrActions` /
`ocrCapture` / `panelSwitching` / `stagingArea` / `tabDragSplit`（都以 mixin 形式并进页面）。
布局是四列：常驻 rail（Activity Bar）→ 可收起左栏 sidebar-left → 中间 workbench（含底部工具抽屉）→ 右侧 AI 面板。

**template**
- :3-168 project-header 顶部条；:47-165 header-tools（开关：左栏:48、底栏:64、右栏:80、分屏:96、截图OCR:112、浏览器:127、活动记录:142、客户视图:159）。
- :172-295 left-rail：插件按钮 v-for LEFT_SIDEBAR_PLUGINS（:175，@tap toggleLeftPane）、暂存区:209、**系统设置齿轮 :224 goToSystemSettings→admin**、成员堆叠:234、用户头像:288。
- :354-593 sidebar-left：sidebar-header:358（标题=leftPaneTitle）、sidebar-content:515 按 leftPaneKey 分支（files→FileTree:517、dd-files:532、easyvoice:538、desensitize:544、search:551、动态插件→PluginPane:556）、拖拽手柄:594。
- :595-911 workbench：Tab 栏（左:602 / 右:639 仅 splitMode）、编辑器区:676（左窗格:688、右窗格:762）、bottom-panel:833（v-if showToolsPanel，activeToolKey：variables/favorites/clipboard）。
- :912-983 ai-panel（v-if showAiPanel，内容整块交 ChatInterface:924，历史下拉:961）。
- :984-1213 根级弹窗层（AI导出Word/图片预览/截图保存/OCR浮层/文件关联/拖拽蒙层）。

**script**
- :1216-1290 imports（配置 leftSidebarPlugins/fileActions/tools/workbenchActions）；:1291-1313 components。
- :1314-1556 data()：布局状态集中 :1335-1372——sidebarWidth(260)/sidebarCollapsed/leftPaneKey(null)/showAiPanel/aiPanelWidth(360)/showToolsPanel/toolsPanelHeight(260)/activeToolKey('variables')/splitMode/stagingPinned/resizing(统一拖拽对象)/lastActiveIdsByMode；dynamicPlugins :1551。
- :1557-1780 computed：LEFT_SIDEBAR_PLUGINS:1581（合并动态 :1588）、isClientView:1609、leftPaneTitle:1619。
- 生命周期：beforeUnmount:1781（多实例守卫，只清指向自身的活跃指针）、onLoad:1882、onShow:1932（返回时重新接管全局处理器）、mounted:1988（事件监听登记）。注意有个 beforeDestroy 误嵌在 methods 内（~:5344）。
- :2261-6729 methods：**toggleLeftPane :2988**、goToSystemSettings:4498、toggleSidebar:4528、toggleAiPanel:4532、toggleToolsPanel:4544、triggerWorkbenchResize:4550、toggleSplitMode:4817、动态插件加载:6266。

## 面板切换状态机

rail 点击 → toggleLeftPane(key)（:2988）：staging 单独分支 → 把当前 activeFile 存 lastActiveIdsByMode[oldKey]（:3005）→ 同 key 则收/展 sidebarCollapsed（:3011），异 key 则设 leftPaneKey 并展开（:3014）→ 动态插件另在中间开 tab（openFile fileType:'plugin'，:3018）→ 恢复该模式记忆的左右 tab（:3029）→ 持久化到 uni.storage（:3055）。
顶栏三开关都在 $nextTick 调 triggerWorkbenchResize 派发 window resize 让编辑器/iframe 重排；toggleAiPanel 打开时刷新 AI 上下文 + fetchChatHistory。

## 左栏入口（frontend/src/config/leftSidebarPlugins.js，57 行）

固定入口：files(资源管理器→FileTree)、dd-files(尽调文件)、shareholder-meeting(股东大会)、search、easyvoice、desensitize、version(版本记录→VersionPanel，见 `.claude/agents/version-control.md`)；requiresSkill 门控入口：litigation-visual(诉讼可视化)、meeting-recorder(会议录音→MeetingRecordingPanel，skill 启用才出现在 rail；录音本体是页面树外的模块级单例 `utils/meetingRecorder.js` + body 级浮动指示器 `utils/recordingIndicator.js`，见 plugin-system.md)。辅助函数 getLeftSidebarPlugin(key)（找不到回退第一项）、getPluginsForUser(role)（CLIENT 只见尽调文件）。动态插件后端拉取后追加 rail 并用 PluginPane 渲染。rail 齿轮对所有人可见，admin 页/接口后端 requireAdmin（用户名 admin）。
**插件广场入口（2026-08 二改：VS Code 扩展栏形态）**：rail 广场按钮 goToPluginMarket → `toggleLeftPane('market')` 开左栏列表面板（`MarketSidebarPanel`，leftPaneKey='market'，leftPaneTitle 特判）；点列表行 → `openMarketDetail(spec)` 在中栏开详情 tab（`MarketDetailPane`，`tabType:'market-detail'`、单例、isTabVisible 常显、直接 push 进 leftFiles/rightFiles 绕过 isFileTypeSupported——与浏览器 tab 同法）。独立页面路由保留给 admin 入口与直链（薄壳页 + `<MarketPane :standalone="true">`）。详见 plugin-marketplace.md。

## 协作入口（PR-E，2026-08-06）

顶栏项目名区在 `.work-status-chip` 旁新增 `.collab-chip`（`collab-chip-green/-blue/-amber` 三态），底部 `.status-bar` 同源加一格，两处都 `v-if="collabLinked"`——**只有这份案卷真的放进过团队案件库才渲染任何协作元素**，没连案件库的律师在界面上看不到一个协作字样（「以自己工作为主」的定位要求零打扰）。点开的是页面级 `components/collab/CollabDialog.vue`（三 tab：这份案卷 / 案件参与人 / 团队案件库），交稿、取回最新稿、放进案件库、加人、连/退案件库全部收在这里，是**唯一**动作入口；版本面板的 `CloudSyncBar` 只剩一行只读状态 + 一个 `open-collab` 链接。admin 页的「团队案件库」分区保留给多库管理与浏览器端。
没有动 rail 配置、没有动 `leftPaneKey` 状态机、没有拆 `VersionPanel` 组件树——`LEFT_SIDEBAR_PLUGINS` 仍是纯静态数组，rail 上有哪些入口不依赖运行时状态。角色展示文案的唯一来源是 `frontend/src/config/memberRoles.js`（`ROLE_LABELS`/`ASSIGNABLE_ROLES`/`MEMBER_GROUP_LABELS`），`CloudSyncBar`/`InviteMemberDialog`/`groupedMembers` 三处各写各的历史已清；**枚举键名是后端 `ProjectMember.Role` 的值也是接口字段值，只改 label 不改 key**。协作状态口径与刷新机制见 `.claude/agents/version-control.md`。
**「加人」是两条轨，界面上必须说破**：顶栏成员堆叠的 `InviteMemberDialog` 走 `addProjectMember`（本机这份案卷的参与人表），协作抽屉「案件参与人」tab 走 `addCloudMember`（代理到团队案件库那边的成员表，`api.js` 注释已自陈「不是本地项目成员」）。双轨是既有机制、本 PR 不动，但两处现在共用同一套 `memberRoles.js` 标签后界面上再无区别信号——只在一侧加人的律师会让同事白等且毫无提示，所以 `InviteMemberDialog` 的「所里同事」tab 底部常驻一句「这里加的是本机的参与人，案卷进了案件库还要在顶栏协作里再加一次」。改这两个弹窗的文案时别把它删了。
**邀请话术里的每一步都要指向收件人真看得见的入口**：`CollabDialog.inviteText` 是发给一个此刻手上还没有这份案卷的人的，他打开软件停在项目列表页——那里的协作入口只有「从团队案件库取一份案卷」（空项目态与有项目态都渲染），连库也要从那个弹窗里的「去连一个」进。别写「打开左下角设置」：项目列表页左侧的「设置」面板里没有团队案件库（那是 admin 页的分区，且入口叫「系统设置」、只对 `isAdmin` 渲染）。

## 页面路由（frontend/src/pages.json，全部 navigationStyle: custom）

launch（**启动页**）/ unlock / identity / login / newproject / **project-list** / **project-home** / project-overview / variable-library / userprofile / admin / plugin-market / wizard。

**术语表（同名不同物，全篇按此读）**：

| 术语 | 指代 | 路由 |
|---|---|---|
| **工作台** | 现有四列布局的干活界面 | `pages/project-overview/project-overview`（**刻意不改名**，改名要动 9 处硬编码 URL + 九个模块文件 + e2e + 埋点 path 维度） |
| **项目列表页** | 2026-08 从个人中心 projects tab 搬出的独立页 | `pages/project-list/project-list` |
| **项目概览页** | 一页纸卷轴（档案头/统计条/动态/日程/AI 对话） | `pages/project-home/project-home` |

代价是「project-overview」在代码里指工作台、在产品语言里指项目概览页。写代码时以路由为准，写文案时以术语表为准。

**两级导航（2026-08 二改，原三级已收）**：项目列表页 → 工作台；**概览是工作台里的一个标签**。
总规则三条——
① **启动一律落项目列表页**（`launch.vue` 不再读 `checkba_last_project_id` 直达工作台）。理由是维护者的产品口径：开机先看见自己有哪些案卷。**其余四条「直达工作台」的出口一条都不改**（浏览器会话恢复 `login.vue`、应用菜单最近打开 `appMenuBridge.js`、打开本地文件夹/文件 `ideOpen.js`、顶栏最近项目切换器 `switchToProject`）——那几处的用户意图明确指向某一个项目；
② **所有「去我的项目」的落点统一到项目列表页**（`launch.vue` 启动、`login.vue` 四处、`newproject/index.vue` 返回、工作台 `goAllProjects`）；
③ **列表点卡片 `reLaunch` 直达工作台**，中间不再插概览页。

导航流：launch reLaunch→login（非桌面）|unlock（未解锁）|identity（本机工作区待选定）|wizard（未初始化）|**project-list**（其余一律）；unlock/identity 完成后一律 reLaunch 回 launch 重跑分流，不自己跳工作区；**project-list reLaunch→project-overview**（`goToProject`，`onCloudAccepted` 复用同一方法）；**概览在工作台内是标签**（rail 第一个按钮 `openProjectHomeTab`，`tabType:'project-home'`、单例、`isTabVisible` 常显，内容组件 `components/project-home/ProjectHomePane.vue`；顶栏切换器的 `.switcher-home`「项目概览」调的是同一个方法）；**project-home 薄壳页只留给直链/深链**（`goWorkbench` 仍 reLaunch 进工作台并透传 `openFileId`；点 AI 对话历史带 `conversationId`，工作台 `onLoad` 消费后调 `loadHistoryChat`——它要 `$refs.chatInterface`，只能在 AI 面板已渲染之后调；**在工作台标签里点历史对话不跳页**，走 `openConversationInPanel` 就地切会话）；**project-home →project-list 条件分流**——上一页 route 是 `pages/project-list/project-list` 就 `navigateBack({delta:1})`，否则 `redirectTo`（**不能无脑 navigateTo**：双向 navigateTo 堆实例）；**project-overview reLaunch→project-list**（顶栏切换器里的「全部项目…」`.switcher-all`，工作台参与的跳转一律 reLaunch）；overview navigateTo userprofile/admin（**这两条保持 navigateTo 不动**——它们依赖页面栈保留实例以便 onShow 回流刷新）；**admin ⇄ userprofile 互跳用 redirectTo**（同级页面不压栈；两边都 navigateTo 会互相弹成死循环，来处永远够不着）；admin 内「插件广场」是页内切换（plugin-market 独立页仅直链保留）；newproject reLaunch→overview；退出 reLaunch login。
**全局返回键**：`utils/globalBack.js`，body 级单例（同拖拽条/反馈浮窗），落在各页顶部那条 38px 拖拽条里；可见判据只有「页面栈深度 > 1」，工作台与 project-home 走豁免名单（自带左上角导航）。新页不需要各自补返回按钮。
**启动链只用 reLaunch，不用 navigateTo**——分流页不该留在页面栈里。
**新页 pages.json 注册必须逐条显式写 `navigationStyle: custom`**：globalStyle 里没有这一项（只有 navigationBarTextStyle / TitleText / BackgroundColor / backgroundColor），漏写会得到一个系统导航栏，与全应用自绘顶栏形制冲突。
**个人中心配套三改（已落地，实测行号）**：`userprofile.vue:324` 的 `activeTab` 默认值是 `'work_log'`、`:326` 起的 `tabs` 数组已不含 `{ key: 'projects', label: '我的项目' }`（只剩工作记录/收藏/代办/设置四项）、`onLoad`（:379）里 `$nextTick` 直接调一次 `loadActivityLogs()`（**不是删除**，:409）——工作记录 tab 是懒加载的，另一个触发点是 `switchTab`（:547）里 `key === 'work_log'` 分支（`loadActivityLogs` 定义在 :561）；默认 tab 落在懒加载 tab 上却不在 `onLoad` 里补调一次，就会得到一个默认打开却永远空白的 tab。
**identity（本机工作区选择，2026-08-05）**：单机免登下所有请求解析为同一个「本机用户」，老安装的库里常有多个历史账号（admin 往往是空壳，真实数据在用户自己注册的账号名下）。后端 `LocalIdentityService` 按数据量解析，多个账号都有数据时不猜，`GET /api/local-identity/status` 回 needsSelection，launch 页据此分流到 identity 页；选定经 `POST /api/local-identity/select` 持久化到 SystemSetting，之后不再出现。补救入口在 admin 页「账户与用量」的「本机工作区」卡（候选 >1 才渲染）。
**IDE 化体验对齐第二轮（2026-07-31，同分支）**：① 启动直达——login 页 `tryAutoResume()` 存储会话有效即 reLaunch 上次项目（`utils/recentProjects.js` 的 `checkba_last_project_id`），登录页只在会话失效时出现（**PR-A 去登录后这条只对浏览器访问团队服务器有效**：桌面端启动链已改为 launch 页分流，直达逻辑迁到 `launch.vue`，登录页在桌面端不再出现）；② 桌面应用菜单 `desktop/main/app-menu.js`（文件→打开文件夹 Cmd+O/打开文件/新建项目文件夹/最近打开动态子菜单；编辑菜单是 editMenu role，删了它 mac 输入框 Cmd+C/V 全灭；窗口菜单刻意无 close role——Cmd+W 留给渲染层关标签），动作经 `checkba:menu-action` 到 App.vue 全局处理器（`utils/ideOpen.js` 共用流程）；③ overview 键位 Cmd+P（`QuickOpenPanel.vue` 快速打开，document 捕获段拦键：uni input 不透传 keydown）/Cmd+W 关活跃标签，焦点在 LOWA webview 内收不到属已知边界；④ 顶栏项目名旁最近项目切换器（`.project-switcher`/`.switcher-menu`）与工作状态点（`.work-status-chip`，复用 `checkAdoptConflict` 的 /status，working/onDraft 才渲染）；⑤ 窗口标题「文件 — 项目 — AI Workdeck」（watch activeFileIdLeft/project.name）；⑥ 拖文件夹到窗口（App.vue capture 段 drop，单目录才接管，`fs.getPathForFile` preload helper）与 macOS open-file 事件（main.js `dispatchOpenPath`，窗口未就绪先存后发）都走 open-local。文件树方向键导航有意缓做（全局拦方向键与编辑器输入冲突）。
**newproject 已 IDE 化（2026-07-31）**：桌面态三动作「打开文件夹/新建项目文件夹/打开文件」走 `window.checkbaDesktop.fs.showOpenDialog` + `POST /api/projects/open-local`（同一 localRoot 重复打开复用项目并幂等重扫导入，见 `LocalProjectService`）；浏览器降级为托管空白项目（BLANK）；成功后 reLaunch 进 overview，单文件过渡版带 `openFileId` 查询参数（`fileOpenTabs.js` 的 `openPendingLocalFile`）。项目类型选择表单已删除（`config/projectTypes.js` 仅剩 `getProjectTypeLabel` 供存量项目卡片显示）。FileTree 右键新增「在访达中显示」（`reveal-file` → overview `onRevealFile` → `/local-path` 端点 + `fs.showItemInFolder` IPC）。

## 窗口外壳与菜单栏（2026-08-16）

**没有系统标题栏了。** `main.js` 的 `titleBarStyle:'hidden'`：mac 用
`trafficLightPosition:{x:18,y:13}` 把三颗交通灯精确摆进 42px 的 `.project-header`
垂直中心（**不用 `hiddenInset`**——那个按系统默认标题栏高度摆，压不准我们的 42px）；
Windows 用 `titleBarOverlay` 把原生控件覆盖在右上。渲染层一侧在
`utils/windowChrome.js`：往 `documentElement` 挂 `is-desktop / is-mac / is-win /
is-fullscreen`，并在 `<body>` 下补一条 38px 拖拽条给那些没有自己顶栏的页面。

三条改这块必须记得的规矩：
1. **让位规则的选择器一律写 `html.is-xxx`**。组件 scoped 样式带 `[data-v-]`，与
   `.is-mac .project-header` 同权重（0,2,0）但注入更晚会赢，必须靠元素选择器抬到
   (0,2,1) 才压得住 `project-overview.scss` 的 `padding: 0 18px`。
2. **顶栏里每加一个可交互元素，都要在 App.vue 的 no-drag 名单里加一行**。整条
   `.project-header` 是 `-webkit-app-region: drag`，漏一个就是一个点不动的按钮。
3. **只有 login / variable-library / plugin-market 三页左上角压着实体内容**，已逐页
   让位；其余 10 页顶部是空白背景、拖拽条覆盖率 100%（CDP 逐点探测得出，交通灯是
   OS 画的不进截图，别靠肉眼看图判断）。

**菜单栏的数据源在渲染层，主进程只把 JSON 渲染成 NSMenu。**

```
config/commands/{app,file,edit,document,ai,view,go,tools,help}.js  纯数据，可 JSON 序列化
        │  index.js: MENU_ORDER / isEnabled(when) / buildMenuPayload(state, lang)
        ├──> utils/appMenuBridge.js ──IPC checkba:menu-state──> desktop/main/app-menu.js
        ├──> components/CommandPalette.vue（⌥⌘P）
        └──> components/AppMenuBar.vue（Windows 自绘，mac 不渲染）
```

- 命令 `run` 只有两个命名空间：`app:*` 桥自己执行，`wb:*` 经 `uni.$emit('awd:command')`
  交给活跃的工作台实例（`pages/project-overview/menuCommands.js`，套活跃实例守卫）。
- **主进程恒定持有系统骨架**（应用菜单 / 编辑 roles / 视图里的重新加载与开发者工具 /
  窗口），下发只替换业务菜单。渲染层白屏时菜单要是也没了，用户连「重新加载」都点不到。
- 菜单里的应用名用 `APP_DISPLAY_NAME` 常量，**不要用 `app.name`**（PR#370 的结论）。
- 文案里**不要出现 `&`**：Electron 当助记符标记吃掉（`Account & License` 会显示成
  `Account  License`）。
- `checkba:recent-projects` 通道已并入 `checkba:menu-state`，不要再另开单推通道。

**加速键是编辑器优先**。外壳里嵌着 Word 编辑器，放进原生菜单的加速键会被永久从编辑器
手里拿走（NSMenu 的 key equivalent 先于响应链）。裸 `⌘+字母` 只保留语义同构的
`⌘O/⌘W/⌘F/⌘,`，其余外壳命令一律 `Alt+CmdOrCtrl+*`；`Esc/Enter/Tab` 永不做加速键；
不碰 `Shift+Cmd+3/4/5`（系统截图，优先级高于应用菜单）。工作台原有的 `⌘P` 快速打开、
`⌘W` 关闭标签**键位保留不动**——它们在编辑器内被 webview 吞掉正好是「编辑器优先」。
这套口径由 `npm run test:commands` 断言，改表时它会拦你。

**客户视图过滤是安全边界不是排版偏好**：`when: ['notClient']` 同时决定菜单项 enabled
和命令能否执行，加速键在客户视图下按下去必须什么都不发生。

## 反馈浮窗与外壳的两处接缝（2026-08）

右下角常驻反馈浮窗**不在页面树里**：`App.vue onLaunch` 经 `utils/feedbackWidget.js`
在 `<body>` 下单独 `createApp` 挂一个实例（因此天生免疫下面那条页面栈多实例地雷）。
它与外壳只有两处接缝：① `utils/overlayState.js` 的 `globalOverlayActive` 被
`desktopOverlayActive` 或进去（浮窗自己不调 `setViewsVisible`，否则和那个 watcher 抢
BrowserView 显隐）；② admin 页新增 nav key `feedback`（用户反馈看板）。详见
`.claude/agents/feedback-optimizer.md`。

**页面栈地雷（本领域核心机制）**：navigateTo 反复进入 project-overview 不销毁旧实例——页面栈多实例并存，每个都持有全局监听。守卫模式：活跃实例指针 `window.__checkbaActiveOverviewVm` + isActiveOverviewInstance() 判活跃、去重状态挂 window 不挂实例、只清/接管指向自己的指针（beforeUnmount/onShow/mounted 三处配合）。切换项目用 reLaunch 避免堆叠。**外壳里新增任何全局订阅必须套用此模式**（PR#148/#151）。
**新页同样成立**：`project-home.vue` 套同一套守卫，但**必须用自己的指针名** `window.__checkbaProjectHomeVm`——复用工作台的 `__checkbaActiveOverviewVm`（:2086/:2291/:2352 登记与清理，:3444 判活跃）会让工作台的全局事件被概览页拦掉。`project-home` 的轮询纪律：只在 onLoad 与 onShow 各刷一次，不起定时器；**绝不调 `getVersionStatus` / `/version/status`**（enabled 时会一路走到 `ProjectRepoService` 跑两次 `git add "."`，工作台已有 ≥7 处触发点在喂同一份状态，概览页再打第三次是纯浪费且会与工作台争 per-project 锁）。要「最近修改」时间取 `/version/timeline` 最新一条的 when。

## CSS 体系

- **外壳形态（2026-08 IDE 布局升级；配色维持原浅色体系）**：曾整体深绿化（PR#243）但**维护者明确否决深色配色、已回退**——布局件保留：26px 底部状态条 `.status-bar`（等宽字体；左=variables/favorites/clipboard 工具入口 openToolFromStatusBar()，与底部抽屉联动；右=活跃文件/分屏/录制/版本工作状态真实信号）、顶栏/侧栏图标 SVG 化（config/icons.js ICONS + leftSidebarPlugins svgPaths，双态 PNG 不再新增）、插件广场 workbench 内嵌 tab。**配色红线：外壳保持浅色（白/#F8F9FA chrome + 森林绿 #1A5336 与 mint #5BD197 点缀），不要再做深色 chrome**。uni.scss 尾部的 `$awd-*` 令牌保留备用但外壳当前未用；project-overview.scss 头部自有浅色调色板是实际用的。
- 全局覆盖：`frontend/src/App.vue`（:15-65 只覆盖 uni-modal/uni-toast）。
- **awd-\* 类名约定**（King IDE 品牌清零后的通用弹窗/按钮样式，PR#171）：awd-dialog/-mask/-header/-title/-body/-footer、awd-btn/-primary/-secondary/-danger、awd-field/awd-input。**没有集中定义**——在 project-overview.vue（~:10180-10300）、ChatInterface.vue（~:2869 起）、FileTree.vue 各自 scoped 重复定义；改样式要多处同步。
- 外壳布局类：.header-tools:6904、.rail-btn:7009、.sidebar-left:7403/7905、.workbench:7455、.bottom-panel:7283/7510、.compact-mode:7478、.is-resizing:7927。

## 相关文件

- `frontend/src/services/host.js` — **访问桌面壳能力的唯一出口**（浏览器面板/截图/剪贴板/组件下载/自动更新/本地文件对话框/应用菜单等）。业务代码一律 `import { host } from '@/services/host.js'`，**不要再写 `window.checkbaDesktop`**；「是不是桌面壳」用 `isDesktopHost()`。桌面态逐字段透传、Web 态缺席，所以既有的 `if (host.browser && ...)` 子对象守卫必须保留（守卫就是能力探测）。详见 doc-editor.md 的「宿主能力层与编辑器容器」。
- `frontend/src/config/tools.js` — 底部工具面板 tab（WORKBENCH_TOOLS）；`fileActions.js` — 文件树批量操作；`workbenchActions.js` — OCR/内链 scheme 常量。
- `frontend/src/components/FileTree.vue`（5225 行）— 左栏文件树。
- 各页面（行数实测）：login.vue(931)、newproject/index.vue(680)、wizard.vue(1007，重跑语义见 PR#134)、userprofile.vue（项目 tab 已搬出，只剩工作记录/收藏/代办/设置四 tab，行数随之变动、不再登记具体数字）、variable-library.vue(543)、admin.vue(4077，含插件广场入口与「记忆同步」面板——nav key `memory`、desktopOnly，配置记忆 Git 远端，见 version-control.md)、plugin-market.vue(22，**已是薄壳页**，实体在 `MarketPane`)。
- **项目列表页** `frontend/src/pages/project-list/project-list.vue` + 同目录 `project-list.scss`（样式 `@import` 引入，照 project-overview.vue + .scss 的既有形制）。整块搬自 `userprofile.vue` 的 projects tab，卡片类名 `.project-item-card` 保持不变（e2e 锚点）；页面根 `.page-project-list`。**新建入口在列表下方**（`.create-section`，两张 `.create-card`：打开文件夹 / 新建项目文件夹，走 `utils/ideOpen.js` 的 `openFolderFlow`/`createFolderFlow`，命名弹窗同页）；「单独打开一个文件」已去掉——它造出的是没有归属的临时项目（`openFileFlow` 仍留给应用菜单与拖拽）。浏览器版没有系统文件夹对话框，降级为 navigateTo `newproject` 页填表建托管空白项目。承载 `InviteMemberDialog` 与 `CloudAcceptDialog`（**这两个必须一起搬**，`CloudAcceptDialog` 的两个入口是协作唯一入口，`CollabDialog.vue:271` 的邀请话术还指着它）。CLIENT 隐藏「+ 新建项目」「从团队案件库取一份案卷」与卡片上的删除/重命名/邀请。角色文案唯一来源是 `config/memberRoles.js`（搬迁时把原来硬编码的 `getRoleLabel` 映射表换掉）。**不要搬**「进行中/已完成」那两张统计卡——它们是写死的字面量 0，Project 实体根本没有状态字段。
- **项目概览**：内容本体 `frontend/src/components/project-home/ProjectHomePane.vue` + 同目录 `project-home-pane.scss`（**两个宿主共用**：工作台中栏标签、`pages/project-home` 薄壳页）；五个子组件在同目录：`ProfileHeader` / `OverviewStatsBar` / `ActivityFeed` / `TaskSchedule` / `ConversationList`。薄壳页 `frontend/src/pages/project-home/project-home.vue` + `project-home.scss` 只剩顶栏与 query 处理。**十个 e2e 稳定锚点类名**：内容根 `.project-home-pane`、薄壳页根 `.page-project-home`、项目列表页根 `.page-project-list`、薄壳页两按钮 `.btn-workbench` / `.btn-project-list`、五个组件根 `.overview-stats-bar` / `.profile-header` / `.activity-feed` / `.task-schedule` / `.conversation-list`——**改这些名字要同步改 `frontend/tests/app-e2e/run.mjs` 的 J2/J3 段**。取数纪律（请求代、绝不调 `/version/status`）随内容一起搬进了 Pane。档案编辑刻意走行内 input、删除确认走 `uni.showModal`，因此两个新页与五个新组件**都不需要自带 awd-\* 样式副本**（awd-\* 没有集中定义，改成弹窗就必须自带一份 scoped 副本，否则渲染成无样式裸框）。
- admin 的「AI 功能设置」面板（nav key `ai`）是 AI 供应商与模型的唯一设置入口：三档供应商单选、
  默认/辅助/子 Agent 三个模型下拉（清单来自 `GET /api/ai/models`，前端不许硬编码）、网络区域三选一
  （auto/境内/境外，附判定依据）、本地 Ollama 的地址与模型名。nav 结构未变，改的是该面板内容；
  契约与键名见 ai-chat.md 与 licensing-billing.md。

## 已知地雷

- admin 页有「数据统计」分区（nav key `telemetry`）：匿名使用统计两开关 + 本地统计页，
  API 见 api.js 的 getTelemetrySettings/updateTelemetrySettings/getTelemetrySummary。
  页面路由埋点在 App.vue onLaunch 的 uni.addInterceptor（唯一收口，别在 50 处调用点逐个埋）；
  面板切换埋点在 panelSwitching.js 的 toggleLeftPane（区分 staging/收展/切换三分支）。
- sed 子串替换改类名会误伤（king-*→awd-* 迁移教训，PR#171）。
- **组件里两个同名 `watch:` / `methods:` 键，后写的会把先写的整个覆盖掉**（本次在
  LibreOfficeEditor 上真踩到，加的 watch 静默失效）。往大组件里加块之前先 grep 一遍。
- **读非响应式源（如 `documentElement.classList`）不能写成 computed**，首次求值后一直
  用缓存（AppMenuBar 的平台判定就这么静默失效过）。要 data + 显式刷新。
- **`rpx` 在 H5 上是会跟着视口缩放的**：uni-h5 把 rpx 编译成 `rem`（比例 10/320），
  运行时按移动端口径设根字号——`窗口宽 <= rpxCalcMaxDeviceWidth(默认960) ? 窗口宽 : 375`
  再除以 23.4375。桌面端窗口拖到 960 以下，根字号从 16px 一路涨到 40px，**全应用
  rpx 尺寸整体放大到 2.5 倍**（FileTree 149 处 rpx，表现最触目：图标与计数变巨大）。
  已在 `pages.json` 的 globalStyle 加 `rpxCalcMaxDeviceWidth: 0` 恒走 375 分支关掉，
  **别把这一行删了**；桌面端新写的固定尺寸一律用 px，不要再引 rpx。
  实现在 `@dcloudio/uni-h5` 的 `useRem()`。
- **padding 简写会把「让位」整条吃掉**：交通灯/窗口控件的保留区不要写成
  `html.is-mac .某顶栏 { padding-left: 88px }`——组件 scoped 样式里任何一条更高
  权重的 `padding:` 简写都会盖掉它（`.compact-mode .project-header { padding: 0 16px }`
  权重 (0,4,0)，窗口窄于 1360px 时必现，交通灯直接压在项目名上）。正确写法是
  在**页面自己的样式表里**、紧跟自己的 padding 简写写一行
  `padding-left: max(自己的边距, var(--awd-titlebar-safe-inline-start))`
  （变量定义在 App.vue，非桌面/全屏/非 mac 时为 0，max() 自动退回原值）。
  **padding 简写在哪出现，让位就得跟到哪**，包括 media query 里的那一份。
- **不要再用 uni 的 `<picker mode="selector">` 与 `<switch>`**：前者在 H5 上弹的是
  移动端底部抽屉（滚轮 + 取消/确定），后者尺寸写死只能靠 `transform: scale()` 硬缩。
  统一用 `components/AwdSelect.vue`（API 与 picker 对齐：range/value，差别是 change
  直接抛下标；菜单 fixed 定位避开 scroll-view 裁剪，下方装不下时向上开）与
  `components/AwdSwitch.vue`（change 直接抛布尔值）。
- uni @tap 在 e2e 驱动下有陷阱（app-e2e 记录）。
- 布局开关后不调 triggerWorkbenchResize 会导致编辑器/iframe 不重排。
- 全站（官网侧）禁 emoji 红线不适用于本仓库 UI，但品牌截图有红线（marketing-screenshots 记录）。

## 验证

- `cd frontend && npm run check:emits`（死绑定护栏）+ `npm run test:app-e2e`（登录→项目→上传→打开文件→独立页面全旅程）。
- 布局/编辑器联动改动加跑 `npm run test:lowa-e2e`。
- 改命令表/菜单加跑 `npm run test:commands`（加速键归属与 when 求值断言，已进 CI）。
  真机验菜单不能只看代码：`osascript -e 'tell application "System Events" to tell
  process "Electron" to get name of every menu bar item of menu bar 1'` 能直接读出
  真实 NSMenu，`click menu item "X" of menu 1 of menu bar item "View"` 能真点。
  **例外：`Toggle Full Screen` 点不动**（需要真实用户交互），全屏相关只能人工走查。
