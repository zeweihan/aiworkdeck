---
name: sidebar-shell
description: 侧边栏与工作台外壳领域。任务涉及左侧 rail/左栏、工作台四列布局、面板切换、页面路由与页面栈、设置入口、project-overview.vue 结构、CSS 体系时，先读本文档再动代码。
---

# 侧边栏与工作台外壳 领域地图

职责边界：工作台整体布局、左侧 rail 与左栏、面板切换状态机、页面路由。各面板内部逻辑归 utility-tools / plugin-system；右栏聊天内容归 ai-chat；编辑器归 doc-editor。

## project-overview.vue 内部地图（10638 行，主战场）

三大块：template :1-1213 / script :1215-6729 / style(scss scoped) :6731-10638。
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

固定入口：files(资源管理器→FileTree)、dd-files(尽调文件)、shareholder-meeting(股东大会，**面板区无分支=占位**)、search、easyvoice、desensitize、version(版本记录→VersionPanel，见 `.claude/agents/version-control.md`)。辅助函数 getLeftSidebarPlugin(key)（找不到回退第一项）、getPluginsForUser(role)（CLIENT 只见尽调文件）。动态插件后端拉取后追加 rail 并用 PluginPane 渲染。rail 齿轮对所有人可见，admin 页/接口后端 requireAdmin（用户名 admin）。
**插件广场入口（2026-08 二改：VS Code 扩展栏形态）**：rail 广场按钮 goToPluginMarket → `toggleLeftPane('market')` 开左栏列表面板（`MarketSidebarPanel`，leftPaneKey='market'，leftPaneTitle 特判）；点列表行 → `openMarketDetail(spec)` 在中栏开详情 tab（`MarketDetailPane`，`tabType:'market-detail'`、单例、isTabVisible 常显、直接 push 进 leftFiles/rightFiles 绕过 isFileTypeSupported——与浏览器 tab 同法）。独立页面路由保留给 admin 入口与直链（薄壳页 + `<MarketPane :standalone="true">`）。详见 plugin-marketplace.md。

## 协作入口（PR-E，2026-08-06）

顶栏项目名区在 `.work-status-chip` 旁新增 `.collab-chip`（`collab-chip-green/-blue/-amber` 三态），底部 `.status-bar` 同源加一格，两处都 `v-if="collabLinked"`——**只有这份案卷真的放进过团队案件库才渲染任何协作元素**，没连案件库的律师在界面上看不到一个协作字样（「以自己工作为主」的定位要求零打扰）。点开的是页面级 `components/collab/CollabDialog.vue`（三 tab：这份案卷 / 案件参与人 / 团队案件库），交稿、取回最新稿、放进案件库、加人、连/退案件库全部收在这里，是**唯一**动作入口；版本面板的 `CloudSyncBar` 只剩一行只读状态 + 一个 `open-collab` 链接。admin 页的「团队案件库」分区保留给多库管理与浏览器端。
没有动 rail 配置、没有动 `leftPaneKey` 状态机、没有拆 `VersionPanel` 组件树——`LEFT_SIDEBAR_PLUGINS` 仍是纯静态数组，rail 上有哪些入口不依赖运行时状态。角色展示文案的唯一来源是 `frontend/src/config/memberRoles.js`（`ROLE_LABELS`/`ASSIGNABLE_ROLES`/`MEMBER_GROUP_LABELS`），`CloudSyncBar`/`InviteMemberDialog`/`groupedMembers` 三处各写各的历史已清；**枚举键名是后端 `ProjectMember.Role` 的值也是接口字段值，只改 label 不改 key**。协作状态口径与刷新机制见 `.claude/agents/version-control.md`。
**「加人」是两条轨，界面上必须说破**：顶栏成员堆叠的 `InviteMemberDialog` 走 `addProjectMember`（本机这份案卷的参与人表），协作抽屉「案件参与人」tab 走 `addCloudMember`（代理到团队案件库那边的成员表，`api.js` 注释已自陈「不是本地项目成员」）。双轨是既有机制、本 PR 不动，但两处现在共用同一套 `memberRoles.js` 标签后界面上再无区别信号——只在一侧加人的律师会让同事白等且毫无提示，所以 `InviteMemberDialog` 的「所里同事」tab 底部常驻一句「这里加的是本机的参与人，案卷进了案件库还要在顶栏协作里再加一次」。改这两个弹窗的文案时别把它删了。
**邀请话术里的每一步都要指向收件人真看得见的入口**：`CollabDialog.inviteText` 是发给一个此刻手上还没有这份案卷的人的，他打开软件停在项目列表页——那里的协作入口只有「从团队案件库取一份案卷」（空项目态与有项目态都渲染），连库也要从那个弹窗里的「去连一个」进。别写「打开左下角设置」：项目列表页左侧的「设置」面板里没有团队案件库（那是 admin 页的分区，且入口叫「系统设置」、只对 `isAdmin` 渲染）。

## 页面路由（frontend/src/pages.json，全部 navigationStyle: custom）

launch（**启动页**）/ unlock / identity / login / newproject / project-overview / variable-library / userprofile / admin / plugin-market / wizard。
导航流：launch reLaunch→login（非桌面）|unlock（未解锁）|identity（本机工作区待选定）|wizard（未初始化）|project-overview|userprofile；unlock/identity 完成后一律 reLaunch 回 launch 重跑分流，不自己跳工作区；overview navigateTo userprofile/admin；admin→plugin-market；newproject reLaunch→overview；退出 reLaunch login。
**启动链只用 reLaunch，不用 navigateTo**——分流页不该留在页面栈里。
**identity（本机工作区选择，2026-08-05）**：单机免登下所有请求解析为同一个「本机用户」，老安装的库里常有多个历史账号（admin 往往是空壳，真实数据在用户自己注册的账号名下）。后端 `LocalIdentityService` 按数据量解析，多个账号都有数据时不猜，`GET /api/local-identity/status` 回 needsSelection，launch 页据此分流到 identity 页；选定经 `POST /api/local-identity/select` 持久化到 SystemSetting，之后不再出现。补救入口在 admin 页「账户与用量」的「本机工作区」卡（候选 >1 才渲染）。
**IDE 化体验对齐第二轮（2026-07-31，同分支）**：① 启动直达——login 页 `tryAutoResume()` 存储会话有效即 reLaunch 上次项目（`utils/recentProjects.js` 的 `checkba_last_project_id`），登录页只在会话失效时出现（**PR-A 去登录后这条只对浏览器访问团队服务器有效**：桌面端启动链已改为 launch 页分流，直达逻辑迁到 `launch.vue`，登录页在桌面端不再出现）；② 桌面应用菜单 `desktop/main/app-menu.js`（文件→打开文件夹 Cmd+O/打开文件/新建项目文件夹/最近打开动态子菜单；编辑菜单是 editMenu role，删了它 mac 输入框 Cmd+C/V 全灭；窗口菜单刻意无 close role——Cmd+W 留给渲染层关标签），动作经 `checkba:menu-action` 到 App.vue 全局处理器（`utils/ideOpen.js` 共用流程）；③ overview 键位 Cmd+P（`QuickOpenPanel.vue` 快速打开，document 捕获段拦键：uni input 不透传 keydown）/Cmd+W 关活跃标签，焦点在 LOWA webview 内收不到属已知边界；④ 顶栏项目名旁最近项目切换器（`.project-switcher`/`.switcher-menu`）与工作状态点（`.work-status-chip`，复用 `checkAdoptConflict` 的 /status，working/onDraft 才渲染）；⑤ 窗口标题「文件 — 项目 — AI Workdeck」（watch activeFileIdLeft/project.name）；⑥ 拖文件夹到窗口（App.vue capture 段 drop，单目录才接管，`fs.getPathForFile` preload helper）与 macOS open-file 事件（main.js `dispatchOpenPath`，窗口未就绪先存后发）都走 open-local。文件树方向键导航有意缓做（全局拦方向键与编辑器输入冲突）。
**newproject 已 IDE 化（2026-07-31）**：桌面态三动作「打开文件夹/新建项目文件夹/打开文件」走 `window.checkbaDesktop.fs.showOpenDialog` + `POST /api/projects/open-local`（同一 localRoot 重复打开复用项目并幂等重扫导入，见 `LocalProjectService`）；浏览器降级为托管空白项目（BLANK）；成功后 reLaunch 进 overview，单文件过渡版带 `openFileId` 查询参数（`fileOpenTabs.js` 的 `openPendingLocalFile`）。项目类型选择表单已删除（`config/projectTypes.js` 仅剩 `getProjectTypeLabel` 供存量项目卡片显示）。FileTree 右键新增「在访达中显示」（`reveal-file` → overview `onRevealFile` → `/local-path` 端点 + `fs.showItemInFolder` IPC）。

**页面栈地雷（本领域核心机制）**：navigateTo 反复进入 project-overview 不销毁旧实例——页面栈多实例并存，每个都持有全局监听。守卫模式：活跃实例指针 `window.__checkbaActiveOverviewVm` + isActiveOverviewInstance() 判活跃、去重状态挂 window 不挂实例、只清/接管指向自己的指针（beforeUnmount/onShow/mounted 三处配合）。切换项目用 reLaunch 避免堆叠。**外壳里新增任何全局订阅必须套用此模式**（PR#148/#151）。

## CSS 体系

- **外壳形态（2026-08 IDE 布局升级；配色维持原浅色体系）**：曾整体深绿化（PR#243）但**维护者明确否决深色配色、已回退**——布局件保留：26px 底部状态条 `.status-bar`（等宽字体；左=variables/favorites/clipboard 工具入口 openToolFromStatusBar()，与底部抽屉联动；右=活跃文件/分屏/录制/版本工作状态真实信号）、顶栏/侧栏图标 SVG 化（config/icons.js ICONS + leftSidebarPlugins svgPaths，双态 PNG 不再新增）、插件广场 workbench 内嵌 tab。**配色红线：外壳保持浅色（白/#F8F9FA chrome + 森林绿 #1A5336 与 mint #5BD197 点缀），不要再做深色 chrome**。uni.scss 尾部的 `$awd-*` 令牌保留备用但外壳当前未用；project-overview.scss 头部自有浅色调色板是实际用的。
- 全局覆盖：`frontend/src/App.vue`（:15-65 只覆盖 uni-modal/uni-toast）。
- **awd-\* 类名约定**（King IDE 品牌清零后的通用弹窗/按钮样式，PR#171）：awd-dialog/-mask/-header/-title/-body/-footer、awd-btn/-primary/-secondary/-danger、awd-field/awd-input。**没有集中定义**——在 project-overview.vue（~:10180-10300）、ChatInterface.vue（~:2869 起）、FileTree.vue 各自 scoped 重复定义；改样式要多处同步。
- 外壳布局类：.header-tools:6904、.rail-btn:7009、.sidebar-left:7403/7905、.workbench:7455、.bottom-panel:7283/7510、.compact-mode:7478、.is-resizing:7927。

## 相关文件

- `frontend/src/config/tools.js` — 底部工具面板 tab（WORKBENCH_TOOLS）；`fileActions.js` — 文件树批量操作；`workbenchActions.js` — OCR/内链 scheme 常量。
- `frontend/src/components/FileTree.vue`（5195 行）— 左栏文件树。
- 各页面：login.vue(777)、newproject/index.vue(660)、wizard.vue(593，重跑语义见 PR#134)、userprofile.vue(2158)、variable-library.vue(543)、admin.vue(1648，含插件广场入口)、plugin-market.vue(766)。

## 已知地雷

- admin 页有「数据统计」分区（nav key `telemetry`）：匿名使用统计两开关 + 本地统计页，
  API 见 api.js 的 getTelemetrySettings/updateTelemetrySettings/getTelemetrySummary。
  页面路由埋点在 App.vue onLaunch 的 uni.addInterceptor（唯一收口，别在 50 处调用点逐个埋）；
  面板切换埋点在 panelSwitching.js 的 toggleLeftPane（区分 staging/收展/切换三分支）。
- sed 子串替换改类名会误伤（king-*→awd-* 迁移教训，PR#171）。
- uni @tap 在 e2e 驱动下有陷阱（app-e2e 记录）。
- 布局开关后不调 triggerWorkbenchResize 会导致编辑器/iframe 不重排。
- 全站（官网侧）禁 emoji 红线不适用于本仓库 UI，但品牌截图有红线（marketing-screenshots 记录）。

## 验证

- `cd frontend && npm run check:emits`（死绑定护栏）+ `npm run test:app-e2e`（登录→项目→上传→打开文件→独立页面全旅程）。
- 布局/编辑器联动改动加跑 `npm run test:lowa-e2e`。
