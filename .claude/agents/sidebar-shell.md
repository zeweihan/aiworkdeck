---
name: sidebar-shell
description: 侧边栏与工作台外壳领域。任务涉及左侧 rail/左栏、工作台四列布局、面板切换、页面路由与页面栈、设置入口、project-overview.vue 结构、CSS 体系时，先读本文档再动代码。
---

# 侧边栏与工作台外壳 领域地图

职责边界：工作台整体布局、左侧 rail 与左栏、面板切换状态机、页面路由。各面板内部逻辑归 utility-tools / plugin-system；右栏聊天内容归 ai-chat；编辑器归 doc-editor。

## 离开工作台前必须落盘（已踩）

自动保存是防抖的，用户敲完最后一个字到真正落盘之间有一段窗口。`closeFile`
（fileOpenTabs.js）与 `evictLibreInstance`（librePool.js）都会先 `await inst.flushSave()`
再拆实例，但**离开整个页面**的三条路——切项目 `switchToProject`、返回列表 `goAllProjects`、
退出登录 `handleLogout`——走的是 `uni.reLaunch`，页面组件树直接销毁，一次 flush 都没有。
`LibreOfficeEditor.beforeUnmount` 自己写着「export 需要活的 webview，从这里保存已经太晚」，
所以 Office 文档那几秒的改动**静默丢失且无任何提示**（纯文本有 PlainTextEditor 自己的
beforeUnmount 兜底，Office 没有）。

现在离开工作台只有一个出口 `leaveWorkbench(url)`：先 `flushDirtyEditors`（纯函数，
`pages/project-overview/flushDirtyEditors.js`，零依赖便于单测）再 `uni.reLaunch`。
`handleLogout` 因为要在 `clearSession()` **之前**落盘（会话一清保存就是未授权），
自己显式 flush 而不复用出口。

**新增任何离开工作台的路径都要走 `leaveWorkbench`**；`npm run check:nav` 已把这条钉住
（goAllProjects 允许直接 reLaunch 或走 leaveWorkbench，走出口时会连带校验出口里有
flushDirtyEditors），单测见 `frontend/tests/project-home/flush-dirty-editors.test.mjs`。

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
`project-overview.scss`（4316 行）。逻辑也已拆出十个同目录 .js 模块：
`agentClientActions` / `clipboardBridge` / `evidenceLinkActions`（拖到编辑器建链、
filelink 点击定位、多 target 弹窗、method 小条；契约见 ai-doc-bridge.md「EvidenceLink 契约 → 前端」）/
`fileOpenTabs` / `librePool` / `ocrActions` / `ocrCapture` / `panelSwitching` / `stagingArea` /
`tabDragSplit`（都以 mixin 形式并进页面）。侧栏原 `FileLinkDropZone` 已删，拖拽关联的落点是编辑器画布。
布局是四列：常驻 rail（Activity Bar）→ 可收起左栏 sidebar-left → 中间 workbench（含底部工具抽屉）→ 右侧 AI 面板。

**template**
- project-header 顶部条；header-tools（开关：左栏、底栏、右栏、分屏、截图OCR、浏览器、活动记录、客户视图）。
  **活动记录右侧新增 `.header-account`**（头像 `.avatar-btn` + 下拉 `.avatar-menu`，2026-08-19
  从 rail 底部搬上来）。它刻意挂在 `isClientView` 分支**之外**——rail 上那个头像本来
  就对客户也渲染。2026-08-20 起下拉**只有「设置」一项**（个人中心已并入），
  这一项对客户同样渲染，开中栏 tab。
- left-rail：插件按钮 v-for LEFT_SIDEBAR_PLUGINS（@tap toggleLeftPane）、spacer、暂存区、成员堆叠。
  **齿轮与头像都不在 rail 上了**（2026-08-19）；插件广场按钮也不在了，它升成了
  LEFT_SIDEBAR_PLUGINS 数组里的一项（key `market`）。
- sidebar-left：sidebar-header（标题=leftPaneTitle）、sidebar-content 按 leftPaneKey 分支
  （files→FileTree、dd-files→DdFilesPanel（现在只有 CLIENT 走得到）、
  **home→ProjectHomePane compact**、**voice→内联 tab 宿主**（EasyVoicePane /
  MeetingRecordingPanel）、desensitize、search、version、market→MarketSidebarPanel、
  litigation-visual、动态插件→PluginPane）、拖拽手柄。
- :595-911 workbench：Tab 栏（左:602 / 右:639 仅 splitMode）、编辑器区:676（左窗格:688、右窗格:762）、bottom-panel:833（v-if showToolsPanel **且 bottomToolsList 非空**，activeToolKey：停在 bottom 档的面板，见「面板停靠」一节）。
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

## 左栏入口（frontend/src/config/leftSidebarPlugins.js）

**rail 从上到下的顺序就是 `LEFT_SIDEBAR_PLUGINS` 数组的顺序，只有这一个出处。**
2026-08-19 起「项目概览」与「插件中心」也收进了数组——它们走的都是普通的
`toggleLeftPane` 语义，单独硬编码成 rail 按钮只会让顺序有两个出处。
当前顺序：**home(项目概览) → files(资源管理器) → search(搜索) → market(插件中心) →
dev(插件开发，requiresSkill 'plugin-dev' 门控，dev-board#61) → voice(语音) →
desensitize(文件脱敏) → litigation-visual(门控) → calendar(日历)**。
**版本记录同一天又挪出了这个数组**（一进一出）：维护者认为它视觉上该挨着
「暂存区」（都是围绕本机改动/存档的动作），不该跟文件树/搜索这类常驻浏览面板
混排。定义照 `DD_FILES_PLUGIN` 的先例独立导出成 `VERSION_PLUGIN`，进
`OFF_RAIL_PLUGINS`（`getLeftSidebarPlugin('version')` 与 `leftPaneTitle` 兜底照常能查到），
rail 底部（spacer 之后由模板单独渲染）现在是**暂存区 → 版本记录 → 成员堆叠**三项，
版本记录夹在中间；`toggleLeftPane('version')` 语义、面板本身一行未动。

- **home（项目概览）**：内容是 `components/project-home/ProjectHomePane.vue`，传 `compact`。
  此前它是中栏标签（`tabType:'project-home'`），维护者认为「rail 点了开中栏标签」
  与 rail 其余每一项的语义不一致——**`openProjectHomeTab` / `isProjectHomeTabActive`
  已删，`isTabVisible` 里那条 project-home 分支也没了**。顶栏切换器的 `.switcher-home`
  与 rail 第一个按钮是同一个动作（`goProjectHome` → `toggleLeftPane('home')`）。
  **密度机制已改成容器查询三档断点**（前序改动，此前的静态 `.is-compact`
  布尔值 + 子组件 `.profile-field`/`.stat-tile` 靠 `:deep()` 强制归零 min-width
  的写法已作废，仓里搜不到那些 `:deep()` 覆盖了）：`project-home-pane.scss` 给
  `.project-home-pane` 开 `container-type: inline-size; container-name: home-pane`，
  `.is-compact` 现在只管「哪个宿主」的结构性差异（左栏铺满 vs 独立页居中卷轴），
  真正的密度/字号/间距按 `@container home-pane` 的实际渲染宽度分三档——
  微窄 `≤359px`（单列最紧凑）/ 窄中 `360~559px`（两列起步，拖左栏最常落的区间）/
  常规 `>560px`（不写规则，落回默认宽松样式）。子组件（`ProfileHeader.vue`、
  `OverviewStatsBar.vue` 等）各自在自己的 scoped 样式里跟着同一个 `home-pane`
  容器写断点，不再需要外壳 `:deep()` 穿透。两个宿主（工作台左栏、`project-home`
  独立页）共用同一套断点，独立页正常宽度下天然落在「常规」档。
- **voice（语音）**：语音合成 + 会议录音的合并入口。宿主是 `project-overview.vue`
  模板里一段内联的 `.voice-pane`（tab 条 + v-if），**两个面板组件本身一行没改**
  （不做包装组件是为了免掉五个事件的转发与 check:emits 的连带风险）。
  入口常显（语音合成本来就无门控）；「会议录音」tab 由 `meetingRecorderEnabled`
  门控——`enabledSkillIds` **是数组不是 Set**，`null`（还没拉到）按启用处理，
  与 `LEFT_SIDEBAR_PLUGINS` 那处同一个口径。
  它不再占 rail 位，所以 **`PANEL_SKILL_IDS` 里必须手工补上 `'meeting-recorder'`**
  （那张表原本是从数组的 requiresSkill 扫出来的），漏掉会让广场把这个面板型插件
  当成对话型 skill 呈现「生效方式三档」。录音单例 `utils/meetingRecorder.js` 是
  页面树外的模块，不受影响。
- **dd-files(尽调文件) 对律师隐藏**（2026-08-19，同股东大会先例：入口移除即等于
  功能隐藏，`DdFilesPanel.vue` / `/api/dd/*` / 后端全保留）。它**不能从这个文件里
  删掉**——`getPluginsForUser('CLIENT')` 只返回它，所以定义搬成了独立导出的
  `DD_FILES_PLUGIN`，`getLeftSidebarPlugin` 在数组之外再找它一遍（`OFF_RAIL_PLUGINS`）。
  **CLIENT 行为一字未改。**
- **`migrateLeftPaneKey(key)`**：`project_<id>_leftPaneKey` 的存量值映射表
  （easyvoice / meeting-recorder → voice；shareholder-meeting / dd-files → files；
  project-home → home）。工作台 onLoad 恢复时必须过它——落在一个没有面板分支命中的
  key 上，左栏是「加载中…」占位符、rail 上一个高亮按钮都没有，看上去就是坏了。
  **CLIENT 不过这张表**（dd-files→files 对客户是错的，他看不到资源管理器）。
- easyvoice 的展示名沿革：路由键曾是 `easyvoice`（`EasyVoice` 是早已停用的 Docker
  服务代号，不当产品名用），#389 改展示名为「语音合成」，2026-08-19 它成了
  voice 面板里的一个 tab，rail 上的名字是「语音」。

版本记录 version→VersionPanel 见 `.claude/agents/version-control.md`。
**shareholder-meeting(股东大会核查) 已于 2026-08-17 下线**：入口从本数组移除即等于功能隐藏，`ShareholderMeetingPanel.vue` / `api.js` 的 `/api/shareholder-meeting/*` / 后端 controller 与实体全部保留（存量案卷数据还在库里），skill 改成 `enabled_by_default: false`。注意 `SkillRegistry` 的种子化只在「第一次见到这个 id」时生效，**存量安装里它仍是启用状态**，要在插件广场手动停用。`EvalHarness` 里显式 `setEnabled(..., true)` 把它开回去——那条回放用例守的是编排契约，与业务在不在产品里无关。requiresSkill 门控入口：litigation-visual(诉讼可视化)；meeting-recorder(会议录音→MeetingRecordingPanel，**2026-08-19 起不占 rail 位，是「语音」面板里的一个 tab**，skill 启用才出现；录音本体是页面树外的模块级单例 `utils/meetingRecorder.js` + body 级浮动指示器 `utils/recordingIndicator.js`，见 plugin-system.md)。辅助函数 getLeftSidebarPlugin(key)（数组里找不到再找 OFF_RAIL_PLUGINS，都没有才回退第一项）、getPluginsForUser(role)（CLIENT 只见尽调文件，返回 DD_FILES_PLUGIN）。动态插件后端拉取后追加 rail 并用 PluginPane 渲染。**设置入口不在 rail 上了**，见顶栏头像下拉那一节；admin 页/接口后端仍 requireAdmin（用户名 admin）。
**插件广场入口（2026-08 二改：VS Code 扩展栏形态；三改：rail 按钮升成数组里的 market 项，动作不变）**：rail market 项 → `toggleLeftPane('market')` 开左栏列表面板（`MarketSidebarPanel`，leftPaneKey='market'，leftPaneTitle 特判）；点列表行 → `openMarketDetail(spec)` 在中栏开详情 tab（`MarketDetailPane`，`tabType:'market-detail'`、单例、isTabVisible 常显、直接 push 进 leftFiles/rightFiles 绕过 isFileTypeSupported——与浏览器 tab 同法）。独立页面路由保留给 admin 入口与直链（薄壳页 + `<MarketPane :standalone="true">`）。详见 plugin-marketplace.md。

## 日历/任务系统的外壳挂载点（2026-08-20，dev-board #48-#53）

数据模型是后端 `project_task`（`ProjectTaskService`/`TaskController(/api/tasks)`/
`CalendarController(/api/calendar)`，概览页 `GET /api/projects/{id}/tasks` 的 B 期真实现）。
外壳侧三个挂载点 + 一个全局页：

- **全局日历页 `pages/calendar/calendar`**：与 project-list 平级的全局页（列表页顶栏
  「日历」按钮 `navigateTo` 过去，同旧个人中心按钮模式）。FullCalendar v6 组件式集成
  （@fullcalendar/vue3，月历为主/周/listMonth 可切），`chinese-days` 标法定节假日
  「休」/调休「班」（`components/calendar/holidayMarks.js`，带按天 Map 缓存）。
  「进入项目」是工作台跳转，用 reLaunch；返回项目列表按栈深分流（navigateBack /
  栈底 redirectTo）。
- **rail `calendar` 面板**：`ProjectCalendarPane.vue`（listMonth 列表视图，窄栏放不下
  月历网格）。**在 project-overview.vue 里必须保持 defineAsyncComponent 懒加载**——
  静态 import 会把 FullCalendar 整包拖进工作台主 chunk。
- **文件右键「设置截止日」**（FileTree.vue，照「管理标签」弹窗模式，任务锚 fileId）。
- **概览页 TaskSchedule**（B 期真数据，读走 ProjectHomePane 的 loadTasks，写 emit 给宿主）。

共享逻辑单一出处 `components/calendar/taskUtils.js`（isDone/daysUntil/dueBadge/
toEventStart），五个消费组件都从这里拿，别再各写一份。

**地雷：uni-h5 的 `<input>` 把 type 收窄成白名单**（text/number/idcard/digit/password/tel），
`type="date"/"time"` 会静默降级成文本框。日期/时间输入一律用 `components/AwdDatePicker.vue`
（mounted 手工挂真原生 input 绕开 uni 模板劫持；只监听 change 防中间值上抛）。

## 地雷：uni-app H5 把 `<view>` 上的事件重建成普通对象，`button` 一类字段全丢

uni-h5 的 `createNativeEvent` 收到 `<view>`（以及别的内置元素）上的原生事件后，
**重新造一个普通对象**交给回调，只有 `{ type, timeStamp, target, currentTarget,
detail }` 加两个转发方法；随后按类型补字段，而补的分支只有四类——
`click` / `mouse` 开头（含 `contextmenu`）/ `touch` 系 / 键盘——**并且补的只是坐标**
（`normalizeMouseEvent` 抄 pageX/clientY 一类，`button` 谁都没抄）。

后果：`@auxclick`、`@mousedown` 这类靠 `e.button` 分左右中键的写法在 H5/桌面端**恒不成立**。
dev-board#97 的「中键关闭标签」就这么静默失效了一整轮：auxclick 确实派发到了标签上、
`onTabAuxClick` 确实被调用，但 `e.button === undefined`，`e.button !== 1` 恒真一路 return。

判定与修法（`fileOpenTabs.js` 的 `mouseButtonOf`）：键位从**当前正在派发的原生事件**
（`window.event`）上取，回调里带 `button` 时优先用回调的（原生事件没被包装的平台走那条）。
同理，凡是要读 `dataTransfer` / `clientX` / `key` 一类原生字段的 `<view>` 事件处理器，
先确认这一类在不在上面那四个补字段分支里；`tabDragSplit.js` 的 `onTabDragStart`
就是靠 `if (evt && evt.dataTransfer)` 兜住的（拖拽状态记在组件上，不依赖 dataTransfer）。

## 顶栏头像与统一「设置」标签（2026-08-19 立，2026-08-20 并，2026-08-21 撤下拉）

rail 底部的**齿轮与用户头像都撤了**，收进顶栏右上角「活动记录」右侧的
`.header-account`：头像 `.avatar-btn`，**点击直接 `goToSystemSettings` 开设置标签，
没有下拉了**（dev-board#96）。沿革：2026-08-20 个人中心并进系统设置后下拉只剩一项
「设置」——两个入口各开一整块面板、彼此还互相跳（个人中心侧栏给管理员插一条
「系统设置」走 navigateTo，设置页侧栏又有一条「个人中心」）是用户点名抱怨过的形态；
2026-08-21 连那个只有一项的下拉也撤了，`avatarMenuOpen` / `toggleAvatarMenu` /
`.avatar-menu*` 样式 / App.vue 里菜单那两行 no-drag 全删，`check:nav` 现在断言这些名字
一个都不许回来。头像**不按 `isClientView` 收**：客户也有自己的工作记录与账号安全，
「系统」组由面板内部按 isAdmin 自己收。

- **入口**：`goToSystemSettings(opts)` → `openSettingsTab(opts)`，中栏开单例标签
  （`tabType:'admin-settings'`、`isTabVisible` 常显、直接 push 进 leftFiles 绕过
  `isFileTypeSupported`——与 market-detail / 浏览器 tab 同法）。整页跳转会把标签、
  编辑器、AI 会话整个换掉，改个 API Key 的代价是回来重开一遍文件。
  **`goToUserProfile` / `openUserProfileTab` / `tabType:'user-profile'` 全没了**，
  `check-navigation-contract.mjs` 现在守的是「头像直接指向 goToSystemSettings、没有下拉，
  且这些旧名字一个都不许回来」。
- **深链等价物**：`openSettingsTab({ nav, service })` 对应薄壳页的
  `?nav=platform&service=ocr`（网关错误提示的逃生门指着它）。标签是单例，
  已经开着时再带深链进来只改 props——所以 **`AdminPane` 里加了
  `watch: { initialNav, initialService }`**，没有它第二次深链会停在用户上次看的面板上。
  工作台里 `goToAccountPanel()`（顶栏「已连接账户」chip）也改走这条。
- **应用菜单的「设置…」（⌘,）**：命令表**没动**（仍是 `app:openSettings`，
  `test:commands` 因此不受影响）；分流在 `appMenuBridge.js` 的
  `case 'openSettings'`——`state.page === 'workbench'` 时 `uni.$emit(COMMAND_EVENT,
  { verb:'openSettings' })` 交给工作台，否则照旧 navigateTo 薄壳页。
  工作台侧在 `menuCommands.js` 的 `runMenuCommand` 加了一条 `case 'openSettings'`。

**实体是 `frontend/src/components/admin/AdminPane.vue`**（`pages/admin/admin.vue` 退成
~30 行薄壳，只把 `onLoad(query)` 的 `nav` / `service` 转成 props）。侧栏分两组：

| 组 | key | 内容 |
|---|---|---|
| 个人 | `work_log` / `favorites` / `todos` / `personal_settings` | 原个人中心四栏；内容各自成组件，在 `components/userprofile/Personal*Panel.vue` |
| 系统 | `ai` / `platform` / `account` / `components` / `updates` / `cloud` / `memory` / `telemetry` / `feedback` / `plugins` | 原样未动 |

- **个人组的 key 刻意避开 `account`**（系统组的「账户与用量」已经占了这个 key）。
- **可见性只有 `visibleNavItems` 一处**：`desktopOnly && !isDesktop` 收起，
  `group === 'system' && !isAdminUser` 收起。后一条就是原个人中心 `checkAdminTab`
  那条规则（管理员才多出「系统设置」入口），合并后它换了长处。
- **`isAdminUser` 读不到用户时按管理员处理**：桌面单机免登下本来就没有会话缓存，
  按 false 起步会让整个系统组在首帧消失、默认面板也落空。真值由 `loadUserInfo()`
  拉 `/api/auth/me` 覆盖；转成 false 时若当前面板已不可见就落回 `work_log`。
- **`activeNav` 默认值按 `cachedIsAdmin()` 分流**（管理员 `'ai'`、其余 `'work_log'`）；
  **nav 的 v-if/v-else-if 长链链头仍是 `platform`**，个人组四条是追加在链尾的
  `v-else-if`，动链头仍然会得到「v-else/v-else-if has no adjacent v-if」的编译错。
- **系统组那三条初始加载（`loadConfig` / `loadModelCatalog` / `loadTelemetry`）收进了
  `loadAdminSections()` 并按 isAdmin 跳过**：`/api/admin/*` 对普通账号是 403，会弹
  「请用 admin 账号登录」——个人中心并进来之后非管理员也会打开这一页，不能让他一进门就吃这条。
- 侧栏顶部是**用户信息卡**（`.sidebar-user`，头像可点走 `uploadAvatar`），取代了原来的
  纯 logo 头部；`.sidebar-logo-area` 与它那条 is-embedded 隐藏规则一并删了。
- 个人组四栏的**加载时机是各自组件的 `mounted`**（它们只在被选中时渲染）。
  `PersonalSettingsPanel` 的 `beforeUnmount` 必须继续清那两个验证码倒计时——
  设置标签常驻工作台，不清会跨标签泄漏（修过的坑，契约脚本里有断言守着）。

**`pages/userprofile/userprofile.vue` 薄壳页仍在，但挂的是 `<AdminPane initial-nav="work_log" />`**
（选项 A：一条既有链接都不断）。仓里那些 `navigateTo '/pages/userprofile/userprofile'`
（项目列表页的「个人中心」按钮、应用菜单 `appMenuBridge.js` 的 `case 'openAccount'`）
**全部原样保留**，落点就是个人组第一栏。`UserProfilePane.vue` 已删除。
仓里十来处 `navigateTo '/pages/admin/admin?nav=...'`（MarketPane / MarketDetailPane /
MarketSidebarPanel / CloudAcceptDialog / api.js 的 401 兜底）同样原样保留。

## 协作入口（PR-E，2026-08-06）

顶栏项目名区在 `.work-status-chip` 旁新增 `.collab-chip`（`collab-chip-green/-blue/-amber` 三态），底部 `.status-bar` 同源加一格，两处都 `v-if="collabLinked"`——**只有这份案卷真的放进过团队案件库才渲染任何协作元素**，没连案件库的律师在界面上看不到一个协作字样（「以自己工作为主」的定位要求零打扰）。点开的是页面级 `components/collab/CollabDialog.vue`（三 tab：这份案卷 / 案件参与人 / 团队案件库），交稿、取回最新稿、放进案件库、加人、连/退案件库全部收在这里，是**唯一**动作入口；版本面板的 `CloudSyncBar` 只剩一行只读状态 + 一个 `open-collab` 链接。admin 页的「团队案件库」分区保留给多库管理与浏览器端。
没有动 rail 配置、没有动 `leftPaneKey` 状态机、没有拆 `VersionPanel` 组件树——`LEFT_SIDEBAR_PLUGINS` 仍是纯静态数组，rail 上有哪些入口不依赖运行时状态。角色展示文案的唯一来源是 `frontend/src/config/memberRoles.js`（`ROLE_LABELS`/`ASSIGNABLE_ROLES`/`MEMBER_GROUP_LABELS`），`CloudSyncBar`/`InviteMemberDialog`/`groupedMembers` 三处各写各的历史已清；**枚举键名是后端 `ProjectMember.Role` 的值也是接口字段值，只改 label 不改 key**。协作状态口径与刷新机制见 `.claude/agents/version-control.md`。
**「加人」是两条轨，界面上必须说破**：顶栏成员堆叠的 `InviteMemberDialog` 走 `addProjectMember`（本机这份案卷的参与人表），协作抽屉「案件参与人」tab 走 `addCloudMember`（代理到团队案件库那边的成员表，`api.js` 注释已自陈「不是本地项目成员」）。双轨是既有机制、本 PR 不动，但两处现在共用同一套 `memberRoles.js` 标签后界面上再无区别信号——只在一侧加人的律师会让同事白等且毫无提示，所以 `InviteMemberDialog` 的「所里同事」tab 底部常驻一句「这里加的是本机的参与人，案卷进了案件库还要在顶栏协作里再加一次」。改这两个弹窗的文案时别把它删了。
**邀请话术里的每一步都要指向收件人真看得见的入口**：`CollabDialog.inviteText` 是发给一个此刻手上还没有这份案卷的人的，他打开软件停在项目列表页——那里的协作入口只有「从团队案件库取一份案卷」（空项目态与有项目态都渲染），连库也要从那个弹窗里的「去连一个」进。别写「打开左下角设置」：项目列表页左侧的「设置」面板里没有团队案件库（那是设置页的分区，入口叫「设置」、「系统」组只对管理员渲染）。

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

导航流：launch reLaunch→login（非桌面）|unlock（未解锁）|identity（本机工作区待选定）|wizard（未初始化）|**project-list**（其余一律）；unlock/identity 完成后一律 reLaunch 回 launch 重跑分流，不自己跳工作区；**project-list reLaunch→project-overview**（`goToProject`，`onCloudAccepted` 复用同一方法）；**概览在工作台内是左栏面板**（2026-08-19 从中栏标签改过来；rail 第一个按钮 → `toggleLeftPane('home')`，内容组件 `components/project-home/ProjectHomePane.vue` 传 `compact`；顶栏切换器的 `.switcher-home`「项目概览」调的是同一个 `goProjectHome`）；**project-home 薄壳页只留给直链/深链**（`goWorkbench` 仍 reLaunch 进工作台并透传 `openFileId`；点 AI 对话历史带 `conversationId`，工作台 `onLoad` 消费后调 `loadHistoryChat`——它要 `$refs.chatInterface`，只能在 AI 面板已渲染之后调；**在工作台标签里点历史对话不跳页**，走 `openConversationInPanel` 就地切会话）；**project-home →project-list 条件分流**——上一页 route 是 `pages/project-list/project-list` 就 `navigateBack({delta:1})`，否则 `redirectTo`（**不能无脑 navigateTo**：双向 navigateTo 堆实例）；**project-overview reLaunch→project-list**（顶栏切换器里的「全部项目…」`.switcher-all`，工作台参与的跳转一律 reLaunch）；**overview 既不跳 admin 也不跳 userprofile**：设置是中栏标签，个人中心 2026-08-20 并进了它（见下一节）；`pages/userprofile` 与 `pages/admin` 两个薄壳页都还在，只留给直链、浏览器端与仓里既有的 navigateTo（前者现在挂的也是 `AdminPane`，落在个人组的「工作记录」）；admin 内「插件广场」是页内切换（plugin-market 独立页仅直链保留）；newproject reLaunch→overview；退出 reLaunch login。
**全局返回键**：`utils/globalBack.js`，body 级单例（同拖拽条/反馈浮窗），落在各页顶部那条 38px 拖拽条里；可见判据只有「页面栈深度 > 1」，工作台与 project-home 走豁免名单（自带左上角导航）。新页不需要各自补返回按钮。
**启动链只用 reLaunch，不用 navigateTo**——分流页不该留在页面栈里。
**新页 pages.json 注册必须逐条显式写 `navigationStyle: custom`**：globalStyle 里没有这一项（只有 navigationBarTextStyle / TitleText / BackgroundColor / backgroundColor），漏写会得到一个系统导航栏，与全应用自绘顶栏形制冲突。
**个人内容那条老地雷仍然成立，只是换了长处**（2026-08-20 并进统一设置页之后）：四栏都是懒加载的，加载时机现在是各自组件的 `mounted`（它们只在被选中时渲染）。默认落点落在一个没人给它加载数据的栏目上，就会得到一个默认打开却永远空白的页——`check-navigation-contract.mjs` 有一条断言逐个盯着 `PersonalWorkLogPanel` / `PersonalFavoritesPanel` / `PersonalSettingsPanel` 的 mounted。
**identity（本机工作区选择，2026-08-05）**：单机免登下所有请求解析为同一个「本机用户」，老安装的库里常有多个历史账号（admin 往往是空壳，真实数据在用户自己注册的账号名下）。后端 `LocalIdentityService` 按数据量解析，多个账号都有数据时不猜，`GET /api/local-identity/status` 回 needsSelection，launch 页据此分流到 identity 页；选定经 `POST /api/local-identity/select` 持久化到 SystemSetting，之后不再出现。补救入口在 admin 页「账户与用量」的「本机工作区」卡（候选 >1 才渲染）。
**IDE 化体验对齐第二轮（2026-07-31，同分支）**：① 启动直达——login 页 `tryAutoResume()` 存储会话有效即 reLaunch 上次项目（`utils/recentProjects.js` 的 `checkba_last_project_id`），登录页只在会话失效时出现（**PR-A 去登录后这条只对浏览器访问团队服务器有效**：桌面端启动链已改为 launch 页分流，直达逻辑迁到 `launch.vue`，登录页在桌面端不再出现）；② 桌面应用菜单 `desktop/main/app-menu.js`（文件→打开文件夹 Cmd+O/打开文件/新建项目文件夹/最近打开动态子菜单；编辑菜单是 editMenu role，删了它 mac 输入框 Cmd+C/V 全灭；窗口菜单刻意无 close role——Cmd+W 留给渲染层关标签），动作经 `checkba:menu-action` 到 App.vue 全局处理器（`utils/ideOpen.js` 共用流程）；③ overview 键位 Cmd+P（`QuickOpenPanel.vue` 快速打开，document 捕获段拦键：uni input 不透传 keydown）/Cmd+W 关活跃标签，焦点在 LOWA webview 内收不到属已知边界；④ 顶栏项目名旁最近项目切换器（`.project-switcher`/`.switcher-menu`）与工作状态点（`.work-status-chip`，复用 `checkAdoptConflict` 的 /status，working/onDraft 才渲染）；⑤ 窗口标题「文件 — 项目 — AI WorkDeck」（watch activeFileIdLeft/project.name）；⑥ 拖文件夹到窗口（App.vue capture 段 drop，单目录才接管，`fs.getPathForFile` preload helper）与 macOS open-file 事件（main.js `dispatchOpenPath`，窗口未就绪先存后发）都走 open-local。文件树方向键导航有意缓做（全局拦方向键与编辑器输入冲突）。
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

**拖拽区是壳另算的一套，不受 z-index 与 DOM 顺序管（v0.18.0 顶栏死区的根因，2026-08-18）**：
渲染层把所有带 `-webkit-app-region` 的元素按**布局树**顺序交给壳，壳按顺序
`union(drag)` / `difference(no-drag)` 叠出可拖区域——**后来的覆盖先前的**。
`.awd-window-drag-strip` 是 `position:fixed`，fixed 盒子在布局树里恒挂在 LayoutView 下、
排在所有常规流内容之后，于是它那块 38px 全宽 drag **永远最后合成**，把底下顶栏里所有
no-drag 抠洞整片盖回可拖：v0.18.0 工作台顶栏「项目名切换器 + 右上角面板/截图/AI 七个键」
整条点不动，鼠标事件根本进不了页面。三条实测结论：
- **`z-index` 与 `elementFromPoint` 都会骗人**：顶栏 z-index 200 确实盖在拖拽条上，
  `elementFromPoint` 返回的也确实是按钮本身，可 OS 仍把点击当成拖窗口。判「会不会挡住点击」
  只能看 app-region。
- **改 DOM 顺序没用**（`insertBefore` 到 body 最前面实测同样死），只有让它**不存在**才行。
- **fixed 的 no-drag 排在拖拽条之后能赢**——`.awd-global-back` 同为 body 级 fixed、
  在拖拽条之后 append，一直是好的；别照着它推断顶栏里的常规流按钮也没事。

修法在 `utils/windowChrome.js` 的 `OWN_TITLEBAR_ROUTES` + `refreshDragStrip()`：
自带顶栏的三页（工作台 `.project-header` / 项目概览薄壳页 `.home-topbar` / 登录页 `.top-nav`）
把整条拖拽条 `display:none`，拖窗口交给各自的顶栏。判定**以 DOM 为准**
（`OWN_TITLEBAR_SELECTOR` + `getClientRects().length > 0`），路由名单只当快路径——
`getCurrentPages()` 在 hash 直跳（深链/刷新/e2e goto）时滞后一拍，只信它会让让位整整
错开一次导航；`getClientRects` 那一刀同时排掉页面栈里被 `display:none` 压住的旧实例。
重算挂在 App.vue 路由拦截器的 `complete()`（与 `refreshGlobalBack` 同处）+ popstate/hashchange，
补算窗口 rAF/150/450/900/1600ms。**新增任何自带顶栏的页面，要同时进这两处名单。**

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

## 面板停靠（dev-board#180，2026-08-27）

「录音窗格拖到右侧就在右侧开、变量库拖到左侧就在左侧开」。三个 dock 就是工作台
本来就有的三块可收起区域：`left`（左栏 sidebar-left，rail 上一个按钮 = 一个面板）/
`right`（右侧 `.side-panel-ai`，AI 对话之外的 tab 宿主）/ `bottom`（底部抽屉）。

**唯一出处 `frontend/src/config/panelRegistry.js`**：`MOVABLE_PANELS`（key / labelKey /
defaultDock / allowedDocks / svgPaths）+ 纯函数 `resolveDocks(overrides)` →
`{left:[],right:[],bottom:[]}`（非法或 allowedDocks 之外的 override 一律回落 defaultDock）
+ `sanitizeDockOverrides` / `resolveDock` / `isDockAllowed` / `isMovablePanel` / `getMovablePanel`。
v1 收录四个：variables / favorites / clipboard（默认 bottom，三档都行）、voice（默认 left，
left|right，**不给 bottom**——底栏放不下它内部那两个 tab）。
2026-08-27 加第五个：**insight（依据）**，默认 **right**、left|right（同样不给 bottom——
底栏放不下判决书全文）。
**同日 'variables'（变量库）从注册表隐藏（dev-board#216，前端隐藏非拆除）**：现役成员
只剩 favorites / clipboard / voice / insight 四个；变量库的死分支与休眠件清单见
`.claude/agents/utility-tools.md`「变量库」一节，恢复/彻底拆除都先读那里。它是编辑器工具栏「解析」按钮的联动窗格，要和正文并排看，
内容是 `components/InsightPane.vue`（外部检索 / 一致性校验两个 tab），详见
`.claude/agents/doc-insight.md` 的「前端」一节。
**它带来一处视觉变化**：`rightDockPanels` 从此默认非空，AI 面板顶部恒有一条
dock tab 条（「AI 助手 | 依据」）——在它之前那条 tab 条只有用户主动搬过面板才出现。
**这个文件不许 import Vue / uni / `@/i18n`**（照 `config/commands/index.js` 的先例，
它要能被 `node --test` 直接导入），所以只给 `labelKey`，文案由宿主 `$t` 渲染；
图标从同目录 `icons.js` 取（变量库是新加的 `ICONS.braces`）。单测
`frontend/tests/panel-dock/dock-resolve.test.mjs`（`npm run test:panel-dock`）。

**状态与持久化**：`data()` 里 `panelDockOverrides`（panelKey→dock，只存被移动过的）
与 `rightPaneKey`（`'ai'` 或某个右侧面板 key），并进页面的模块是同目录
`pages/project-overview/panelDocking.js`（`panelDockingData()` + `panelDockingMethods`）。
持久化键 **`awd_panel_docks` 是全局的、不带 projectId**——面板停哪儿是本机使用习惯，
跟着人走不跟着案卷走（同 `checkba_project_list_view` 的先例）。
`onLoad` 里 **`loadPanelDocks()` 必须排在恢复 `leftPaneKey` 之前**，紧跟着
`normalizeDockSelections()`：存量 `leftPaneKey` 可能指向一个已经被搬去右/底的面板，
不校正就是「左栏『加载中…』占位符 + rail 上一个高亮都没有」那个老地雷。

**rail 拖动排序（dev-board#204，2026-08-27）**：与停靠平行的另一层状态，模块
`pages/project-overview/railSort.js`（`railSortData()` + `railSortMethods`，同 panelDocking
形制）。全局键 **`awd_rail_order`** 存 key 数组；`applyRailOrder()` 套在
`applyPanelDocks()` **之后**（只改先后、不增删项），没记过的新增项按默认顺序排在
已记项之后。rail 按钮 `draggable="!isClientView"`，`onRailDragStart` 会级联调
`onPanelDragStart`（可停靠面板拖出去落投放区仍走停靠，rail 内松手即排序），
dragover 实时改 `railOrderDraft` 草稿、dragend 提交并持久化。
**整理模式（dev-board#215）**：rail 底部 `.rail-edit-btn`（GLYPHS.sort）切换
`railEditMode`——页面根挂 `.rail-edit-mode`，可拖项（`.rail-btn.is-sortable` 与
两处 `.is-movable` tab）抖动+虚线框（iPhone 编辑主屏幕隐喻，prefers-reduced-motion
下只留框不抖）；编辑态下 `onRailBtnTap` 拦截点击不打开面板。不持久化。
由来：0.26.x 里可拖的 rail 图标只有语音一个、无任何指引，被复测判「根本没实现」。

**顶栏头像下拉（dev-board#205，2026-08-27）**：恢复成两项（设置 / 退出登录，
`avatarMenuOpen` + `.avatar-menu`），退出走 `utils/signOut.js` 唯一编排。
`check-navigation-contract` 钉着「恰好两项」；**别把新方法插在 `goToSystemSettings`
与 `openSettingsTab` 之间**——该脚本的方法提取按「call site 后第一个 `{`」配对，
中间夹方法会截断窗口、报「标签没有带 tabType」的假错。菜单与 mask 都在 App.vue
的 no-drag 名单里。

三处宿主的契约：
- **左栏**：`applyPanelDocks()` 在 `LEFT_SIDEBAR_PLUGINS` 计算属性末尾做两件事——
  把不在 left 档的可移动面板从 rail 摘掉、把搬到 left 的工具面板追加在静态数组之后
  （追加位置与动态插件同法，rail 的**成员**仍然只有 `LEFT_SIDEBAR_PLUGINS` 一个出处；
  **顺序**从 2026-08-27 起由 `applyRailOrder` 按 `awd_rail_order` 重排，见上）。
  CLIENT 视图整个不参与。工具面板在左栏的宿主是 `.dock-tool-pane`（一条紧凑搜索行 +
  面板体）——这三个面板的搜索早就外置给宿主了，不给就等于没有搜索。
  **`isTabVisible` 必须放行这些左栏模式**（`|| this.isMovablePanel(this.leftPaneKey)`）：
  它们的动作全是「往当前文档里插入 / 从当前文档取值」，把编辑器标签藏死等于功能没了
  （同「语音合成要在编辑器里取正文」那条）。
- **右栏**：`rightDockPanels` 非空才渲染 `.right-dock-tabs`（平时右侧只有 AI 对话，
  零视觉回归）。**ChatInterface 那段模板与九个 @event 绑定一行没动，只在外面包了一层
  `.right-dock-body` + `v-show`**——挂 v-if 会丢整段会话状态，`$refs.chatInterface`
  也会消失（`resolveChatInterface()` 靠它，它现在顺带把 `rightPaneKey` 切回 `'ai'`）。
  因为上面那块是 v-show，**下面停靠面板的分支链链头必须是 `v-if` 而不是 `v-else-if`**，
  否则编译期直接报「v-else/v-else-if has no adjacent v-if」。
- **底栏**：`bottomToolsList`（= bottom 档）取代原来的 `toolsList`，底栏 tab 条与
  status-bar 工具入口**同源消费**；空了则底部抽屉、顶栏那个开关、状态条入口一起收起，
  `toggleToolsPanel()` 也会直接 return（不开一个空抽屉）。

**移动的两条路**：右键菜单是保底（`openDockMenu` → `.dock-menu`，只列 allowedDocks、
当前那档置灰），HTML5 拖拽是增强（`.dock-drop-layer` 三块投放区，几何由
`dockZoneStyle(dock)` 按左右栏的**实时宽度**算——两栏都可拖宽，写死常量会让高亮框
和它代表的区域对不上）。拖拽状态记在 `draggingPanelKey` 上、**不依赖 dataTransfer**
（uni 重建 `<view>` 事件对象的老地雷）；`openDockMenu` 的坐标同样先读回调再回退
`window.event`，`preventDefault` 在方法里做而不在模板上写 `.prevent`——rail 上不是每个
按钮都能移动，不能移动的要把右键让回系统默认行为。
搬家动作是 `movePanelToDock(key, dock)`：搬之前开着的，搬完 `openPanelInItsDock` 跟到
新位置继续开着（右栏不可见时顺带 `toggleAiPanel()`），否则只 `triggerWorkbenchResize`。

**加一个可停靠面板的步骤**（后续「依据」面板照此接入）：
1. `panelRegistry.js` 加一条（含 `svgPaths`，图标进 `config/icons.js`，禁 emoji）；
2. `project-overview.vue` 在**每个** allowedDocks 对应的显式 `v-else-if` 分支链里加一条渲染
   （`rightPaneKey === '<key>'` / `leftPaneKey === '<key>'` / `activeToolKey === '<key>'`），
   props 与 `@event` **逐个显式写**——`check-emit-bindings.mjs` 是静态扫描，
   改成 `<component :is>` 会静默失去覆盖，`check-navigation-contract.mjs` 也认字面量；
3. 文案补 `locales/{zh-CN,en-US}` 两份；
4. 跑 `npm run test:panel-dock`（注册表自洽断言会拦下 defaultDock 不在 allowedDocks、
   缺图标一类错误）+ `check:emits` + `check:nav` + 一次真构建。

## 左栏面板的标题与密度（2026-08-17）

**左栏标题只有一个出处**：外壳的 `.sidebar-header`（`project-overview.vue`，渲染
`leftPaneTitle`）。此前各面板还各画各的 header，于是「诉讼可视化」「会议录音」
「股东大会核查」的标题在同一屏里出现两次，搜索面板靠把自己那份 `panel-title`
**注释掉**躲过去，dd-files 则让外壳整个跳过它（`leftPaneKey !== 'dd-files'`）——
四种写法并存。现在一律：**外壳出面板标题，面板自己只画分组头**。
dd-files 那个例外已取消，它 header 里的「＋」挪进了面板内部的分组头。

**密度令牌在 `App.vue` 的 `html { --awd-panel-* }`**，基准是插件广场
`MarketSidebarPanel`（维护者点过名的形态）：`--awd-panel-pad-x:10px` /
`--awd-panel-sec-h:26px`（分组头行高）/ `--awd-panel-row-h:28px` /
`--awd-panel-fs-sec:11px`(配 700 字重) / `--awd-panel-fs:12px` /
`--awd-panel-border:#E9ECEF` / `--awd-panel-accent:#1A5336` 等。
**用 CSS 自定义属性而不是 scss 变量**：各面板的 `<style scoped>` 有的写 scss
有的写纯 css，自定义属性两边都能用且天然穿透 scoped。已套用：SearchPanel /
EasyVoicePane / DesensitizePane / LitigationVisualPanel / MeetingRecordingPanel /
DdFilesPanel / ShareholderMeetingPanel。新面板照抄这套，不要再自定义边距。

分组头的统一形制（各面板类名前缀不同但结构一致）：
`26px 行高 + 11px/700 标题 + 计数徽章（圆角 999px、#F1F3F5 底）+ spacer + 右侧动作`。

## CSS 体系

- **外壳形态（2026-08 IDE 布局升级；配色维持原浅色体系）**：曾整体深绿化（PR#243）但**维护者明确否决深色配色、已回退**——布局件保留：26px 底部状态条 `.status-bar`（等宽字体；左=variables/favorites/clipboard 工具入口 openToolFromStatusBar()，与底部抽屉联动；右=活跃文件/分屏/录制/版本工作状态真实信号）、顶栏/侧栏图标 SVG 化（config/icons.js ICONS + leftSidebarPlugins svgPaths，双态 PNG 不再新增）、插件广场 workbench 内嵌 tab。**配色现状（2026-08 更新）**：PR#243 那轮硬编码深绿 chrome 被否决回退，但 2026-08-26 起走的是另一条路——PR#625（dev-board#223）落地**颜色语义令牌化 + 浅色/深色双主题**：全部颜色收敛到 `App.vue` 里 `html[data-theme]` 上的 `--awd-*` 令牌，`utils/appTheme.js` 管三态切换（light/dark/system，默认 light）。写样式一律用 `var(--awd-*)`，**禁止再写硬编码浅色背景/深色文字**（uni.scss 的 `$uni-*`/`$brand-*` 是编译期静态值，不响应主题，别用在颜色上）；固定彩底（mint 选中条、深色 tooltip 气泡、登录页深色设备插画）的配对文字保持固定值，专用令牌 `--awd-text-on-mint`。新增令牌要同时更新 `appTheme.js` 的 `THEME_TOKEN_NAMES`（插件 iframe 主题注入的名单，dev-board#274）。
- 全局覆盖：`frontend/src/App.vue`（:15-65 只覆盖 uni-modal/uni-toast）。
- **awd-\* 类名约定**（King IDE 品牌清零后的通用弹窗/按钮样式，PR#171）：awd-dialog/-mask/-header/-title/-body/-footer、awd-btn/-primary/-secondary/-danger、awd-field/awd-input。**没有集中定义**——在 project-overview.vue（~:10180-10300）、ChatInterface.vue（~:2869 起）、FileTree.vue 各自 scoped 重复定义；改样式要多处同步。
- 外壳布局类：.header-tools:6904、.rail-btn:7009、.sidebar-left:7403/7905、.workbench:7455、.bottom-panel:7283/7510、.compact-mode:7478、.is-resizing:7927。
- **面板拖拽的跟手守卫是三件一套，删一件拖拽就会退化**（2026-08-20）：① `.is-resizing` 禁 transition；② `.is-resizing :deep(iframe)/:deep(webview)` 关 pointer-events——光标滑进嵌入文档父窗口就收不到 mousemove，拖拽会冻住；③ 桌面端浏览器 BrowserView 是原生层 CSS 管不到，靠 `desktopOverlayActive` 里那条 `resizing.active` 在拖拽期间隐藏。另外 `startResize/stopResize`（tabDragSplit.js）会锁/还原 body 的 cursor 与 user-select。编辑器窗格 `.editor-pane`/`.pane-content` 已拉平成方角（圆角卡片残留会在标签栏下沿与分栏缝露出底色弧口），别再给它们加 border-radius。
- **编辑器标签（`.tab-item`）在 project-overview.scss 里只有一份定义了**。此前有两份：
  靠前那份是 VS Code 式贴合标签，被靠后那份整个覆盖成死代码，实际生效的是一排
  10px 全圆角 + 四面描边 + `min-width:100px` 的「筛选 chip」，与下方编辑器完全断开。
  死代码那 140 行已删（`.tab-icon` 与 close 的 hover/active 配色是其中唯一还活着的
  片段，已折进现存那份）。现在的形制：36px 满高、方角、无四面描边、右侧 1px 分隔线、
  激活态白底 + 2px mint 顶线、12px/500 文件名、× 悬停或激活才显形。
  **`.tab-item` 的高度写死 36px 不用 `height:100%`**：中间隔着 uni `scroll-view`
  的内层包裹元素，那条 100% 链断了标签会塌成 0 高。
- **标签支持鼠标中键关闭（2026-08-21，dev-board#97）**：`.tab-item` 上挂
  `@mousedown="onTabMouseDown"` + `@auxclick="onTabAuxClick"`（两个方法在
  `fileOpenTabs.js`，紧挨 `closeFile`），中键走的就是 `closeFile(file.id, pane)`——
  与点 × 同一条路径，脏改动落盘/BrowserView 销毁等闸门一个不少。mousedown 与 auxclick
  两处都对 `button===1` preventDefault（压掉 Linux/Windows 的中键自动滚动）。
  uni-h5 的 `<view>` 默认 inheritAttrs，原生 `auxclick` 直接落到 `<uni-view>` 上。
  标签没有「固定/不可关」概念，所以没有例外分支；要加固定标签时先在这里加判断。
  app-e2e J6.3 末尾用 `page.mouse.click(..., { button: 'middle' })` 关设置标签兼作覆盖。

## 相关文件

- `frontend/src/services/host.js` — **访问桌面壳能力的唯一出口**（浏览器面板/截图/剪贴板/组件下载/自动更新/本地文件对话框/应用菜单等）。业务代码一律 `import { host } from '@/services/host.js'`，**不要再写 `window.checkbaDesktop`**；「是不是桌面壳」用 `isDesktopHost()`。桌面态逐字段透传、Web 态缺席，所以既有的 `if (host.browser && ...)` 子对象守卫必须保留（守卫就是能力探测）。详见 doc-editor.md 的「宿主能力层与编辑器容器」。
- `frontend/src/config/panelRegistry.js` — 可停靠面板注册表（**取代了原 `config/tools.js` 的 `WORKBENCH_TOOLS`，那个文件已删**，底栏三项现在是「停在 bottom 档的面板」）；`fileActions.js` — 文件树批量操作；`workbenchActions.js` — OCR/内链 scheme 常量。
- `frontend/src/components/FileTree.vue`（5225 行）— 左栏文件树。
- 各页面（行数实测）：login.vue(931)、newproject/index.vue(680)、wizard.vue(1007，重跑语义见 PR#134)、userprofile.vue（**已是薄壳页**，2026-08-20 起挂 `AdminPane initial-nav="work_log"`，个人中心的四栏内容在 `components/userprofile/Personal*Panel.vue`）、variable-library.vue(543)、admin.vue(**已是薄壳页 ~30 行**，实体在 `components/admin/AdminPane.vue`，含插件广场入口与「记忆同步」面板——nav key `memory`、desktopOnly，配置记忆 Git 远端，见 version-control.md)、plugin-market.vue(22，**已是薄壳页**，实体在 `MarketPane`)。
- **项目列表页** `frontend/src/pages/project-list/project-list.vue` + 同目录 `project-list.scss`（样式 `@import` 引入，照 project-overview.vue + .scss 的既有形制）。整块搬自 `userprofile.vue` 的 projects tab，卡片类名 `.project-item-card` 保持不变（e2e 锚点）；页面根 `.page-project-list`。**新建入口在列表下方**（`.create-section`，两张 `.create-card`：打开文件夹 / 新建项目文件夹，走 `utils/ideOpen.js` 的 `openFolderFlow`/`createFolderFlow`，命名弹窗同页）；「单独打开一个文件」已去掉——它造出的是没有归属的临时项目（`openFileFlow` 仍留给应用菜单与拖拽）。浏览器版没有系统文件夹对话框，降级为 navigateTo `newproject` 页填表建托管空白项目。承载 `InviteMemberDialog` 与 `CloudAcceptDialog`（**这两个必须一起搬**，`CloudAcceptDialog` 的两个入口是协作唯一入口，`CollabDialog.vue:271` 的邀请话术还指着它）。CLIENT 隐藏「+ 新建项目」「从团队案件库取一份案卷」与卡片上的删除/重命名/邀请。角色文案唯一来源是 `config/memberRoles.js`（搬迁时把原来硬编码的 `getRoleLabel` 映射表换掉）。**不要搬**「进行中/已完成」那两张统计卡——它们是写死的字面量 0，Project 实体根本没有状态字段。
**双视图（2026-08-18）**：页头右侧 `.view-toggle` 两个按钮切 `viewMode`（`'grid'`/`'list'`），
选择记在 `uni.storage` 的 `checkba_project_list_view`（本机习惯，不进后端）。默认仍是 grid，
`.project-item-card` 因此一直在——**app-e2e 的 J2 就钉在这个类名上，改默认视图会让它红**。
列表视图根 `.project-table`，行 `.ptable-row`，列 `.col-name/.col-client/.col-created/.col-updated/.col-members/.col-ops`。
两个视图承载同一批字段（名称/客户/创建时间/最近修改/成员），没有哪个视图独占信息。
卡片上**「空白项目」标签已不渲染**（`projectType === 'BLANK'` 时留 `.card-top-spacer` 占位，
非 BLANK 的历史项目照旧显示类型），原来那句「通用项目工作区」占位与
`projects.blankWorkspace` 文案一并删除。
**项目档案接进列表了（2026-08-18 二改）**：`ProjectCardDTO.profile` 是
`fieldKey → 值` 的 map，**只含已填的**，键就是 `ProjectProfileService.FIELD_KEYS`
（client / matterType / openedAt / nextStep / counterparty）。取数是
`ProjectProfileFieldRepository.findByProjectIdIn` 一次 IN 后在内存分组——
**别改成逐项目调 `ProjectProfileService.getProfile`**，那条是概览页档案头用的、
每次查五个字段，列表页 N 个项目照着调等于给已经 N+1 的页面再加一层。
（此前这里写着「Project 实体没有客户字段、要另立一件事」——**那句是错的**，
客户一直是既有的档案字段，缺的只是列表没去读它。）
- **客户是一等列，常显**；`clientText()` 先读 `profile.client`（律师手填、`source='user'`
  锁定、AI 抽取只写 pending 永不覆盖），**没填过才**回落到推断：① CLIENT 系角色成员
  ② listedCompanyName ③ targetCompanyName ④ `—`。列表视图给推断值加灰字 +「推断」小标，
  免得律师以为那是自己填的（`clientIsInferred()`）；方块视图里手填值一定显示，
  推断值才被「已单列上市公司」那条躲开（`showClientRow()`）。
- **推断值绝不回写档案**：档案字段的语义是「谁说的算」，把猜的混进去会稀释掉那把手填锁。
- 其余四项收在**页头的「详情」开关**后面（`.detail-toggle`，`checkba_project_list_detail`，
  默认关，两个视图共用同一个开关）。列表视图渲染成主行下面的第二行（`.ptable-detail`，
  因此 `v-for` 挂在外层 `.ptable-item` 上、边框与悬停也上提到那一层，主行只剩高度）；
  方块视图追加成卡片里的 info-row。**一项没填的案卷整条详情行不渲染**——空行只占地方。
  显示顺序是「事项类型 / 对方 / 立项时间 / 下一步」，不照搬 FIELD_KEYS（那是档案头的排版序）：
  先认这是哪一类活、跟谁打，`nextStep` 常常是一整句话，放末位才不会把前面几项挤没。
- 想再加字段：后端不用动（map 是全量的），前端在 `detailFields()` 里加一行 + 补两条 locale。
**「最近修改」读 `lastActivityAt`，不是 `updatedAt`**：`Project.updatedAt` 只在建项目与改项目名时
写过（`ProjectService` 两处），拿它当修改时间会得到一个恒等于创建日期的假列。本次在后端加了
`ProjectCardDTO.lastActivityAt` = 项目下未删除文件的 `MAX(ProjectFile.updatedAt)`
（`ProjectFileRepository.findLastActivityByProjectIds`，一次 group by，不给已经 N+1 的列表页再加一层），
没有文件时回落到 `Project.updatedAt`。**同时补了一处真正的漏记**：
`FileController.uploadFile` 此前只写字节不动文件行，编辑器自动保存一整天 `ProjectFile.updatedAt`
仍停在建文件那一刻——现在挂在 `uploadComplete` 上回写一次（分片上传只写一次，失败只 warn 不影响上传）。
**新建入口现在有两处**：页头主按钮 `.btn-create-primary`（桌面端 `uni.showActionSheet` 两选一：
打开文件夹 / 新建项目文件夹；浏览器端直接进 newproject），列表下方 `.create-section` 两张卡保留
（零项目新用户的落点）。**`.create-card` 的张数是 app-e2e 断言的**（浏览器降级恰好 1 张），
页头那个是 `<button>` 不是 `.create-card`，不影响计数。
- **项目概览**：内容本体 `frontend/src/components/project-home/ProjectHomePane.vue` + 同目录 `project-home-pane.scss`（**两个宿主共用**：工作台中栏标签、`pages/project-home` 薄壳页）；五个子组件在同目录：`ProfileHeader` / `OverviewStatsBar` / `ActivityFeed` / `TaskSchedule` / `ConversationList`。薄壳页 `frontend/src/pages/project-home/project-home.vue` + `project-home.scss` 只剩顶栏与 query 处理。**十个 e2e 稳定锚点类名**：内容根 `.project-home-pane`、薄壳页根 `.page-project-home`、项目列表页根 `.page-project-list`、薄壳页两按钮 `.btn-workbench` / `.btn-project-list`、五个组件根 `.overview-stats-bar` / `.profile-header` / `.activity-feed` / `.task-schedule` / `.conversation-list`——**改这些名字要同步改 `frontend/tests/app-e2e/run.mjs` 的 J2/J3 段**。取数纪律（请求代、绝不调 `/version/status`）随内容一起搬进了 Pane。档案编辑刻意走行内 input、删除确认走 `uni.showModal`，因此两个新页与五个新组件**都不需要自带 awd-\* 样式副本**（awd-\* 没有集中定义，改成弹窗就必须自带一份 scoped 副本，否则渲染成无样式裸框）。
- **admin 的「系统配置」分区（nav key `config`）已整体撤掉**（2026-08-18）。它到最后只剩两样东西，
  各自都有更该待的地方：① OpenRouter 的 Key 与地址 → 「AI 功能设置」的供应商单选下面、
  **选中 OpenRouter 那一档才渲染**（形制同跨境同意块）；② 界面语言 → **设置页「个人 → 账户与安全」**（2026-08-20 前叫「个人中心 → 设置」）
  （语言是每个人自己的偏好：storage 权威源、人人可改、不要 admin 权限，摆在「系统管理」下面
  本身就是错的分类）。`admin.externalTitle` 文案随之改成「OpenRouter 接入参数」，
  七家非 AI 服务的指路条 `externalMovedNote` 挪到 AI 面板底部，`admin.navConfig` 键已删。
  **撤这个分区要一起改的三处**：`navItems` 去掉那一项、`activeNav` 默认值改成 `'ai'`
  （原默认就是 `'config'`，不改会得到一个默认打开却空白的页），以及**把 `v-if` 链头接上**——
  面板是一条 `v-if`/`v-else-if` 长链，删掉链头那块会让编译期直接报
  「v-else/v-else-if has no adjacent v-if」，现在链头是 `platform`。
  app-e2e J7 的 admin 断言文案也从「系统配置」改成了「系统管理」（左侧导航卡标题，不随分区增减变化）。
- admin 的「团队案件库」面板（nav key `cloud`）在**没有任何连接时**多渲染一张说明卡
  （`.cloud-help-card`，`admin.cloudNoServerTitle` 起五条文案）：说清案件库是律所自建的一台
  服务器、三个框分别填什么、账号不是 aiworkdeck.com 那个、以及本机只存令牌不存密码。
  在此之前界面对这些只字未提，没部署过服务器的人打开只会发呆（维护者 2026-08-18 亲自问到）。
- admin 的「AI 功能设置」面板（nav key `ai`）是 AI 模型设置的唯一入口。**2026-08-21 起产品只有
  官方版（dev-board#98）：前端不露 BYOK，后端分支与设置键原样保留**。面板里只剩
  默认/辅助/子 Agent 三个模型下拉（清单来自 `GET /api/ai/models`，前端不许硬编码）、网络区域三选一
  （auto/境内/境外，附判定依据）、跨境传输同意勾选（原来只在 AWD_CLOUD 下显示，现在恒显示）。
  **供应商三选单、OpenRouter Key/Base URL、本地 Ollama 地址/模型名都从 `AdminPane.vue` 删掉了**
  （连同 `aiProviderOptions` / `onPickProvider` / `accountOutOfCredits` 与对应 i18n 键）。
  兜底：后端回来的 `form.ai.activeProvider` 不是 `AWD_CLOUD`（老用户以前切过）时，
  面板顶部渲染 `legacyProvider` 提示条 + 「切换到官方通道」（`switchToOfficialChannel`：
  置 AWD_CLOUD 走既有 `handleSave`；没勾跨境同意先 toast 拦下，后端 `crossBorderBlockReason`
  仍把关）。`handleSave` 因此返回布尔值，失败时把 activeProvider 还原。
  **`form` 不再带 `external` 与 `ollama*` 字段**——`AdminConfigController.toSettingsUpdates`
  对 null/缺省字段跳过不写（`putIfPresent`），老用户库里的 key 不会被清空；空串才是「显式清空」。
  契约与键名见 ai-chat.md 与 licensing-billing.md。
- admin 的「平台服务」面板（nav key `platform`，**非 desktopOnly**）是七项外部服务的档位入口
  （平台服务网关 P5）。**2026-08-21 起只有官方版：「使用自己的 Key（高级）」折叠区与七家 21 个
  BYOK 凭证表单整个删掉**（`platformByokOpen` / `togglePlatformByok` / `hasByokCredentials` 一并走），
  面板里也没有「保存配置」按钮了（只剩即时写库的档位下拉与自带保存的花费提醒）。
  档位只露 platform / local 两档（local 是 ASR 的本地隐私档，要留）；生效值若是 byok
  （老用户、或非 local-mode 下后端把 platform 解析成 byok），`platformServiceRows` 会把它
  补进下拉清单让它显示出来，**不让界面空白**（`platform.tierByok` 键因此保留，向导也在用）。
  `initialService` prop 宿主仍会传，但深链 `?nav=platform&service=ocr` 现在只落到面板本身。
  三处必须一起看的约束：
  ① 当前档位一律读 `GET /api/platform-services` 的 `provider`（后端解析后的**生效值**），
     不要按凭证是否为空去猜——存量机器上这两件事经常对不上；
  ② `platformAvailable=false`（团队服务器/云端实例）时「平台代采」这个选项**整个不出现**并给说明，
     不是摆一个置灰项（决策 D5）；
  ③ 档位切换**立刻写库**（`POST /api/platform-services/{service}/provider`），不跟着「保存配置」按钮走；
     面板里已没有走 `handleSave` 的字段。
  服务的展示元数据（名字/描述/本地档就不就绪）在 `frontend/src/config/platformServices.js`，
  文案在新命名空间 `locales/{zh-CN,en-US}/platform.js`（首启向导步骤 2 与本面板共用）。
- 首启向导（`pages/wizard/wizard.vue`）步骤 2 已从「OCR / 语音 / 企业数据」三组共 9 个输入框
  换成「平台服务总览 + 就地连接账户」，**默认展开**（这一段的意义就是让用户看见「其余七项不用配」）。
  它**不拦提交**：没连账户照样能完成设置，向导只拦 AI 供应商那一项（既有行为）。
  e2e 的 J1 钉死了这个形态（`平台服务` 在、`AccessKey`/`企查查 Key`/`Tushare Token`/`北大法宝 Token` 不在）。

## 已知地雷

- admin 页有「数据统计」分区（nav key `telemetry`）：匿名使用统计两开关 + 本地统计页，
  API 见 api.js 的 getTelemetrySettings/updateTelemetrySettings/getTelemetrySummary。
  页面路由埋点在 App.vue onLaunch 的 uni.addInterceptor（唯一收口，别在 50 处调用点逐个埋）；
  面板切换埋点在 panelSwitching.js 的 toggleLeftPane（区分 staging/收展/切换三分支）。
- sed 子串替换改类名会误伤（king-*→awd-* 迁移教训，PR#171）。
- **左栏面板要给 AI 面板发 prompt，一律走 `resolveChatInterface()`**（工作台 methods）。
  它做三件事：`showAiPanel` 为 false 时先走既有的 `toggleAiPanel()`（顺带刷 AI 上下文 +
  拉历史，不能绕过去直接改标志位）→ 有界轮询 ~3s（30×100ms）等 `$refs.chatInterface`
  暴露 `sendExternalPrompt` → 真等不到才 toast。**别再各写各的
  `if (!$refs.chatInterface) toast('AI 面板未就绪')`**：AI 面板默认收起，那条分支
  在真实使用里几乎必中，而且它不告诉人该怎么办。现有三个调用方：
  `handleShareholderMeetingStart` / `handleMeetingMinutesStart` / `handleLitigationStart`。
- **rail 上加/改 key 之后自查 `panelSwitching.js`**：`toggleLeftPane` 与
  `lastActiveIdsByMode` 全按 key 工作（`data()` 里那两个字面量种子只写了
  `files` / `dd-files`，其余 key 是运行时补的，不用逐个登记）。改 key 集合时
  同时看 `isTabVisible`（哪些左栏模式下普通文件标签可见）与
  `migrateLeftPaneKey`（存量 storage 值）。
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
- 改面板停靠/注册表加跑 `npm run test:panel-dock`（`resolveDocks` 回落规则与注册表自洽）。
- 改导航/入口/设置页结构加跑 `npm run check:nav` 与 `npm run check:nav:full`（导航契约静态护栏，
  含「头像下拉只剩一项」「个人组四栏各自有人加载数据」「薄壳页挂的是统一设置面板」等断言）；
  改文案加跑 `npm run check:locales`（zh/en 键对拍）。
- 布局/编辑器联动改动加跑 `npm run test:lowa-e2e`。
- **app-e2e 里不要拿 `waitText('资源管理器')` 当「工作台起来了」的判据**：那个词是左栏
  标题，`leftPaneKey` 被持久化成别的面板（点过一次「项目概览」就会）之后它根本不出现。
  等 rail 上的 `[title="资源管理器"]`（title 属性与 leftPaneKey 无关）。同理，
  想回到文件树时**不能无脑点一下 rail**——`toggleLeftPane` 对同一个 key 是「收起/展开」，
  已经在那个面板上时点它等于把整条左栏收掉。先探测 `[title="上传文件"]` 在不在，
  需要才点，点完还没有就再点一次（上一次那下是收起）。
- **并行会话共用一棵 worktree 时，先确认 dev server 是不是自己这棵树起的**：
  `lsof -p <pid> | grep cwd`。别的 worktree 起的 5174 会让整套 e2e 测的是别人的代码
  ——本次实测撞上过一次「89 步全红」（其实是端口上有别的进程、整页 404）与一次
  「J1-J3 全绿但测的是旧 rail」。
- 改命令表/菜单加跑 `npm run test:commands`（加速键归属与 when 求值断言，已进 CI）。
  真机验菜单不能只看代码：`osascript -e 'tell application "System Events" to tell
  process "Electron" to get name of every menu bar item of menu bar 1'` 能直接读出
  真实 NSMenu，`click menu item "X" of menu 1 of menu bar item "View"` 能真点。
  **例外：`Toggle Full Screen` 点不动**（需要真实用户交互），全屏相关只能人工走查。
