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

固定入口：files(资源管理器→FileTree)、dd-files(尽调文件)、shareholder-meeting(股东大会，**面板区无分支=占位**)、search、easyvoice、desensitize。辅助函数 getLeftSidebarPlugin(key)（找不到回退第一项）、getPluginsForUser(role)（CLIENT 只见尽调文件）。动态插件后端拉取后追加 rail 并用 PluginPane 渲染。rail 齿轮对所有人可见，admin 页/接口后端 requireAdmin（用户名 admin）。

## 页面路由（frontend/src/pages.json，全部 navigationStyle: custom）

login（**启动页**）/ newproject / project-overview / variable-library / userprofile / admin / plugin-market / wizard。
导航流：login reLaunch→wizard|project-overview|userprofile；overview navigateTo userprofile/admin；admin→plugin-market；newproject reLaunch→overview；退出 reLaunch login。

**页面栈地雷（本领域核心机制）**：navigateTo 反复进入 project-overview 不销毁旧实例——页面栈多实例并存，每个都持有全局监听。守卫模式：活跃实例指针 `window.__checkbaActiveOverviewVm` + isActiveOverviewInstance() 判活跃、去重状态挂 window 不挂实例、只清/接管指向自己的指针（beforeUnmount/onShow/mounted 三处配合）。切换项目用 reLaunch 避免堆叠。**外壳里新增任何全局订阅必须套用此模式**（PR#148/#151）。

## CSS 体系

- 主题变量：`frontend/src/uni.scss`（SCSS 变量非 CSS 自定义属性）：$brand-color-primary #12344D、$brand-color-gold #C8A45D、$brand-bg-warm #F7F5F0，映射 $uni-* 系列。无 :root/--var 令牌。
- 全局覆盖：`frontend/src/App.vue`（:15-65 只覆盖 uni-modal/uni-toast）。
- **awd-\* 类名约定**（King IDE 品牌清零后的通用弹窗/按钮样式，PR#171）：awd-dialog/-mask/-header/-title/-body/-footer、awd-btn/-primary/-secondary/-danger、awd-field/awd-input。**没有集中定义**——在 project-overview.vue（~:10180-10300）、ChatInterface.vue（~:2869 起）、FileTree.vue 各自 scoped 重复定义；改样式要多处同步。
- 外壳布局类：.header-tools:6904、.rail-btn:7009、.sidebar-left:7403/7905、.workbench:7455、.bottom-panel:7283/7510、.compact-mode:7478、.is-resizing:7927。

## 相关文件

- `frontend/src/config/tools.js` — 底部工具面板 tab（WORKBENCH_TOOLS）；`fileActions.js` — 文件树批量操作；`workbenchActions.js` — OCR/内链 scheme 常量。
- `frontend/src/components/FileTree.vue`（5195 行）— 左栏文件树。
- 各页面：login.vue(777)、newproject/index.vue(660)、wizard.vue(593，重跑语义见 PR#134)、userprofile.vue(2158)、variable-library.vue(543)、admin.vue(1648，含插件广场入口)、plugin-market.vue(766)。

## 已知地雷

- sed 子串替换改类名会误伤（king-*→awd-* 迁移教训，PR#171）。
- uni @tap 在 e2e 驱动下有陷阱（app-e2e 记录）。
- 布局开关后不调 triggerWorkbenchResize 会导致编辑器/iframe 不重排。
- 全站（官网侧）禁 emoji 红线不适用于本仓库 UI，但品牌截图有红线（marketing-screenshots 记录）。

## 验证

- `cd frontend && npm run check:emits`（死绑定护栏）+ `npm run test:app-e2e`（登录→项目→上传→打开文件→独立页面全旅程）。
- 布局/编辑器联动改动加跑 `npm run test:lowa-e2e`。
