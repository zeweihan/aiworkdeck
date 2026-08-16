# 桌面外壳：隐藏原生标题栏 + 命令化菜单栏

2026-08-16 立项。触发：维护者反馈——① Electron 默认标题栏「很丑」；② 菜单栏只有
「文件」等 Electron 模板项，「跟整个项目的相关性比较弱」。

领域：`sidebar-shell`（外壳布局与页面路由）+ `eng-infra`（Electron 主进程与打包）。
相关：`doc-editor`（快捷键与编辑器争抢）、`ai-chat`（AI 菜单接线）。

维护者已定的三条口径（2026-08-16）：

1. **平台范围**：mac 和 Windows 一起做。
2. **深度**：P1 到 P3 一次做完（含 AI 菜单与命令注册表收口）。
3. **快捷键归属**：**编辑器优先**，外壳改用功能键或组合键。

初稿后按维护者要求做了两轮 review（用户四角度 / 架构师），改动七处，其中一处是撤销
初稿自己引入的破坏性变更（§4.1 的 `⌘P`）。受影响的小节：§3.2 §4.1 §4.3 §5.1
§6.1 §6.2 §6.3 §8。

---

## 1. 现状

| 事实 | 位置 |
|---|---|
| 窗口未设 `titleBarStyle`，走系统默认带框标题栏 | `desktop/main/main.js:293` |
| 应用菜单全部实现，95 行 | `desktop/main/app-menu.js` |
| 菜单只有 App / 文件(4 项) / 编辑(role) / 视图(7 项) / 窗口(3 项)，**无帮助菜单** | 同上 `buildTemplate()` |
| 主进程 → 渲染层：`checkba:menu-action`，单向动作字符串 | `desktop/preload/preload.js:158` |
| 渲染层 → 主进程：只有 `checkba:recent-projects` | `desktop/preload/preload.js:165` |
| 菜单动作处理器注册在 App 级（避开页面栈多实例） | `frontend/src/App.vue:47` |
| `.project-header` 42px，已承载项目名/切换器/状态 chip/logo/七个面板开关 | `frontend/src/pages/project-overview/project-overview.scss:46` |
| 渲染层自持键位：⌘P 快速打开、⌘W 关闭标签（有 `isActiveOverviewInstance()` 守卫） | `frontend/src/pages/project-overview/project-overview.vue:2376` |
| 编辑器工具栏已有约 30 条命令（修订/审阅/查找替换/批注/脚注/表格/页眉页脚） | `frontend/src/components/EditorToolbar.vue` |
| 更新器 IPC 已存在 | `desktop/main/main.js:1432` `checkba:update-{status,check,restart}` |
| `ChatInterface` 只 expose 了 4 个方法 | `frontend/src/components/ChatInterface.vue:2191` |
| 13 个页面全部 `navigationStyle: custom`；其中 12 个没有贴顶的横向头部 | `frontend/src/pages.json` |

菜单文案双语走主进程自己的 `t({ zh, en })`（`desktop/main/app-language.js`），
**不是**前端的 i18n locale 文件。语言切换经 `onAppLanguageChange` 整体重建菜单。

---

## 2. 设计原则

**原则一：`.project-header` 就是标题栏。** 不新增一条 chrome，而是把窗口控件让给
已有的顶栏。这是 VS Code 的做法，也是本项目「工作台就是 IDE」定位的延续。

**原则二：菜单的名词必须是产品的名词。** 现在的菜单说的是 Electron 的话（文件/编辑/
视图/窗口）。这个产品的名词是**案卷、文档、AI、面板**。「文档」和「AI」两个菜单是
本次相关性问题的正面回答——律师每天的动词是修订、批注、定稿、回退，这些今天一个
都不在菜单栏里。

**原则三：一切皆命令，菜单只是命令的一种视图。** 菜单、加速键、命令面板三者读同
一张表。不这么做，P3 就要把 P1 拆一遍。

**原则四：编辑器优先。** 见 §4。

---

## 3. P1 · 标题栏并入顶栏

### 3.1 主进程

`desktop/main/main.js:293` 的 `new BrowserWindow` 增加：

```js
titleBarStyle: 'hidden',
// mac：精确摆位，13 + 14/2 = 20 = 42px 顶栏的垂直中心。
// 不用 hiddenInset——那个按系统默认高度摆，压不准我们的 42px。
...(process.platform === 'darwin' ? { trafficLightPosition: { x: 18, y: 13 } } : {}),
// win：原生最小化/最大化/关闭覆盖在右上
...(process.platform === 'win32' ? { titleBarOverlay: { color: '#ffffff', symbolColor: '#3c4043', height: 42 } } : {}),
```

全屏切换要回推渲染层（全屏时交通灯消失，左侧留白必须归零）：

```js
mainWindow.on('enter-full-screen', () => send('chrome-fullscreen', { on: true }))
mainWindow.on('leave-full-screen', () => send('chrome-fullscreen', { on: false }))
```

preload 增补 `platform: process.platform`（`host.platform`），App.vue 在 `onLaunch`
往 `documentElement` 挂 `is-desktop is-mac|is-win`。

### 3.2 渲染层

新增 `frontend/src/styles/desktop-chrome.scss`，全局引入：

- `.is-desktop .project-header { -webkit-app-region: drag; }`
- `.is-mac .project-header { padding-left: 88px; }`（三颗灯右缘约 72px，留 16px 呼吸）
- `.is-win .project-header { padding-right: 148px; }`
- `.is-fullscreen .project-header { padding-left: 18px; }`（恢复原值）。`is-fullscreen`
  由 `appMenuBridge` 承接 §3.1 的 `chrome-fullscreen` 动作后写在 `documentElement`
  上——和 `is-mac` / `is-win` 同一个写入方，不散在页面里。
- 顶栏内所有可交互子元素逐个 `-webkit-app-region: no-drag`：`.project-name`、
  `.project-switcher`、`.switcher-menu`、`.work-status-chip`、`.collab-chip`、
  `.trial-chip`、`.header-center`、`.top-bar-btn`、`.icon-btn`。
  **漏一个就是一个点不动的按钮**，这是本节最容易出错的地方。

### 3.3 无顶栏的 12 个页面

`launch / unlock / identity / login / newproject / project-list / project-home /
variable-library / userprofile / admin / plugin-market / wizard`。

做法：body 级单例拖拽条 `frontend/src/utils/windowDragStrip.js`，挂载方式照抄
`mountFeedbackWidget()`（App.vue `onLaunch` 调一次，天然避开页面栈多实例）。

```
position: fixed; top: 0; left: 0; right: 0; height: 38px;
-webkit-app-region: drag; z-index: 1;
```

z-index 1 让它待在所有内容之下：工作台的 `.project-header`（z-index 200）会盖住
它，其余页面它就是最上面那层。**这些页面还要各自加顶部安全区**，否则交通灯会压
在内容上——逐页走查，不做全局 `padding-top`（13 个页面的布局差异太大，全局注入
必然出回归）。

### 3.4 P1 验收

- [ ] mac：三颗灯垂直居中于 42px 顶栏，顶栏空白处可拖窗，所有按钮可点
- [ ] mac：进/出全屏，左侧留白正确切换
- [ ] win：右上原生控件不压 `header-right` 的 chip 与工具按钮
- [ ] 13 个页面逐页截图走查，交通灯不压任何内容与可点元素
- [ ] `npm run test:app-e2e` 重跑（去边框后 webContents 高约 +28px，纵向布局全线
      下移；基线 113，任何偏移要逐条判定是位移还是回归）

---

## 4. 快捷键归属（编辑器优先）

外壳里嵌着一个 Word 编辑器，这是我们和 VS Code 的根本差别。**放进原生菜单的加速键
会被永久从编辑器手里拿走**——macOS 上 NSMenu 的 key equivalent 先于响应链。

### 4.1 三档分类

**A 档 · 语义同构 → 菜单直接承接。** 菜单项做的事和编辑器里那个键做的是同一件事，
菜单只是把它提到 app 层，不算抢占：

`⌘Z` `⇧⌘Z` `⌘X` `⌘C` `⌘V` `⌘A`（Edit roles）、`⌘F` 查找、`⌥⌘F` 查找替换
（LibreOffice 自己的查找替换就是这个键，菜单项转发过去即可）、`⌘O` 打开、
`⌘W` 关闭当前标签、`⌘,` 设置。

`⌘F` 的实现要求：焦点在编辑器内时转发给 `EditorToolbar.toggleFind`；不在时才开全局
搜索面板。**不能无条件走全局搜索**，否则等于把编辑器的查找抢了。

**B 档 · 语义冲突 → 不进菜单，外壳另给一个组合键。**

这里有一个关键区分，第一版 spec 写错了：「让给编辑器」**不等于「改键」**。裸
`⌘+字母` 只要**不进原生菜单**，行为就是今天这样——焦点在 LOWA webview 内时按键被
webview 吞掉交给编辑器，焦点在编辑器外时渲染层的 keydown 收到。这已经是「编辑器
优先」，不需要动它。

所以 B 档的处理是**两份并存**：

| 键 | 编辑器语义 | 外壳同名命令 | 处理 |
|---|---|---|---|
| `⌘P` | 打印 | 快速打开 | 渲染层键位**原样保留**（编辑器外生效）；菜单项另挂 `⌥⌘O` |
| `⇧⌘P` | 上标 | 命令面板 | 只给菜单加速键 `⌥⌘P` |
| `⌘B` | 加粗 | 左栏 | 只给菜单加速键 `⌥⌘B` |
| `⌘J` | 两端对齐 | 底部工具 | 只给菜单加速键 `⌥⌘J` |
| `⌘I` | 斜体 | AI 面板 | 只给菜单加速键 `⌥⌘I` |
| `⌘R` | 右对齐 | 修订模式 | 只给菜单加速键 `⌥⌘R` |
| `⌘M` | 清除格式 | 插入批注 | 只给菜单加速键 `⌥⌘M` |
| `⌘1..5` | 段落样式 | 视图切换 | 不给加速键 |
| `⌘S` | 保存 | 手动保存 | **不接**，见下 |

**结论：本次没有任何破坏性快捷键变更。** `⌘P` 快速打开、`⌘W` 关闭标签这两条现有
渲染层键位原样保留（`project-overview.vue:2376` 那段不动），菜单只是**额外**给出
一条在编辑器内也能用的全局路径。「快捷键速查」里 `⌘P` 要标注「编辑器外生效」。

`⌘S` 单列：现在是自动保存，而 `export_document` 触发 modified 那个死循环刚在
`#364` 修完。**本次不引入任何手动保存路径**，`⌘S` 原样留给编辑器。

**C 档 · 编辑器不用 → 外壳自由使用：** `⌥⌘*`、`⌃⌘*`、`⇧⌘O`、`⇧⌘N`。

### 4.2 两条硬规则

1. **`Esc` / `Enter` / `Tab` 一律不做菜单加速键。** 做了就会吞掉编辑器和所有输入框
   的对应键。「停止当前任务」只给菜单项，不给加速键。
2. **不与 macOS 系统快捷键撞车。** 截图取词不能用 `⇧⌘4`（系统截图，优先级高于
   应用菜单，我们根本收不到），改 `⌥⌘4`。

### 4.3 加速键冲突要变成 CI 断言

口径写在文档里会腐烂。新增单测扫描整张命令表，断言三条：

1. 没有两条命令挂同一个 `accel`；
2. 没有一条 `accel` 落在**编辑器保留键黑名单**里（裸 `CmdOrCtrl+<单字母>`、
   `CmdOrCtrl+<数字>`，`⌘Z/⌘X/⌘C/⌘V/⌘A/⌘F/⌘O/⌘W/⌘,` 这批 A 档白名单除外）；
3. 没有一条 `accel` 用到 `Esc` / `Enter` / `Tab`，也没用到 `Shift+CmdOrCtrl+3/4/5`
   （macOS 系统截图）。

这条测试把 §4 从口径变成可执行，是本节唯一不会腐烂的部分。

### 4.4 待实测项

`⌃⇥` / `⌃⇧⇥`（上/下一个标签）在 Writer 表格上下文里的占用情况未核实。真机验证后
再定；撞了就退到 `⌥⌘←` / `⌥⌘→`。

---

## 5. P2 · 菜单状态通道

菜单要能打勾（修订模式是否开着）、能置灰（没有打开的标签时「关闭标签」）、能随页面
变化（不在工作台时大半条目无意义）。现有桥接给不了。

### 5.1 新通道

渲染层 → 主进程 `checkba:menu-state`，载荷是**白名单扁平对象**，100ms 去抖，主进程
收到后整体重建菜单（`rebuild()` 已是现成模式，重建很便宜）。

**重建会关掉用户正展开着的菜单**，所以推送前必须做**浅比较，只有真变化才推**。
最危险的触发源是 `aiRunning`——如果它跟着流式 token 抖动就会让菜单栏不可用。
`flags` 里的每一项都必须是布尔或短枚举，**不许放计数器、进度、时间戳**。

```js
{
  page: 'workbench' | 'project-list' | 'login' | ...,
  role: 'LAWYER' | 'CLIENT',
  flags: { hasProject, hasTab, isDocTab, splitMode, sidebarOpen, aiPanelOpen,
           toolsPanelOpen, trackChanges, reviewOpen, aiRunning, recording },
  activeTool: 'variables' | 'favorites' | 'clipboard' | null,
  views: [{ key, label }],        // rail 全量，含 skill 门控与动态插件
  skills: [{ id, label }],        // 已启用技能
  recent: [{ id, name }],         // 现有 checkba:recent-projects 并入此处
}
```

现有 `checkba:recent-projects` 通道**并入本通道后移除**，保持单一数据源。改动点：
`preload.js:165`、`app-menu.js:86`、渲染层推送处。

### 5.2 渲染层收口

新增 `frontend/src/utils/appMenuBridge.js`，App.vue `onLaunch` 注册一次，职责三条：

1. 维护状态快照，变化时去抖推送；
2. 承接 `checkba:menu-action`，查命令表得到 handler；
3. 派发：非工作台命令（打开文件夹、切语言、检查更新）自己执行；工作台命令
   `uni.$emit('awd:command', { id, payload })`，由 `project-overview` 在
   `isActiveOverviewInstance()` 守卫内接收执行。

**不让菜单去够页面实例**——复用已有的活跃实例守卫是避开「navigateTo 页面栈多实例
重复订阅」那个全页面级地雷的唯一正确做法。

---

## 6. P3 · 命令注册表与菜单结构

### 6.1 数据源在渲染层

主进程 `require` 不到前端的 ES 模块，且菜单的 enabled/checked 本来就必须由渲染层
驱动。**结论：整张菜单结构由渲染层推给主进程，主进程只负责把 JSON 变成 NSMenu。**

**主进程恒定持有一份系统骨架，下发只替换业务菜单。** 这不只是冷启动的事——渲染层
白屏或崩溃时，如果菜单也跟着没了，用户连「重新加载」和「开发者工具」都点不到，
彻底没救。所以：

- **恒定不可覆盖**：应用菜单（关于/隐藏/退出）、`编辑` 的 roles、`视图` 里的
  重新加载与开发者工具、`窗口`。
- **可被下发替换**：`文件` `文档` `AI` `转到` `工具` `帮助`，以及 `视图` 里
  面板开关与「打开视图」那几组。

骨架里必须有 `editMenu` 的 roles，否则 mac 上所有输入框的 ⌘C/⌘V 在渲染层就绪前
会失灵（现有代码 `app-menu.js:51` 已有这条警告注释）。

### 6.2 命令表

`frontend/src/config/commands/` —— **按菜单拆 8 个文件**（`file.js` `edit.js`
`document.js` `ai.js` `view.js` `go.js` `tools.js` `help.js`）+ `index.js` 汇总。
九个菜单约 70 条命令，写一个文件必然长到 600 行以上，这个项目已经有过一次
4300 行单文件的教训。

每条命令的形状：

```js
{
  id: 'view.toggleAiPanel',
  label: { zh: 'AI 面板', en: 'AI Panel' },
  accel: 'Alt+CmdOrCtrl+I',
  menu: 'view', group: 20,          // 菜单归属与分隔组
  type: 'checkbox',                 // normal | checkbox | submenu
  checked: 'aiPanelOpen',           // flags 里的键名，不是函数
  when: ['workbench'],              // 全部满足才 enabled
  run: 'workbench:toggleAiPanel',   // 派发目标
}
```

**每个字段都必须可 JSON 序列化**——整张表要下发给主进程，`checked` 和 `run` 都
只能是字符串键，不能是函数。`run` 的实际实现放在 `appMenuBridge` 的派发表里。

`when` **不做 VS Code 那套表达式引擎**（YAGNI），只用枚举字符串数组对 `flags`
取与。三个消费者：

1. **原生菜单**（mac 全量 / win 见 §6.4）——序列化后随 `menu-state` 下发；
2. **命令面板** `⌥⌘P`——遍历同表，按 `when` 过滤；
3. **快速打开** `⌥⌘O`——文件检索，与命令面板分开（VS Code 语义）。

非菜单键位（面板内局部键）仍由渲染层 keydown 处理，但读同一张表取 `accel`。

### 6.3 菜单结构

| 菜单 | 条目 | 来源 |
|---|---|---|
| **AI Workdeck** | 关于 / 检查更新（`checkba:update-check`）/ 设置 `⌘,` / 账户与授权 / 应用语言 / 服务·隐藏·退出 | 补全 |
| **文件** | 新建项目 `⇧⌘N` / 打开文件夹 `⌘O` / 打开文件 `⇧⌘O` / 打开最近 / — / 导入文件到项目 / 导出（PDF·Word）/ — / 在 Finder 中显示 / 关闭标签 `⌘W` / 关闭项目 | 扩充 |
| **编辑** | 撤销·重做·剪切·复制·粘贴·全选（roles）/ — / 查找 `⌘F` / 查找替换 `⌥⌘F` | 扩充 |
| **文档** | 修订模式 `⌥⌘R`(勾选) / 审阅面板(勾选) / — / 接受·拒绝当前修订 / 接受·拒绝全部 / — / 插入批注 `⌥⌘M` / 插入脚注·表格·图片 / — / 应用标准格式 / 清除格式 / — / 版本记录（保存版本·另起一稿·退回到） | **新增** |
| **AI** | 新建对话 `⌃⌘N` / 停止当前任务（无加速键）/ — / 模式（问答·计划·智能体）/ 对话历史 / — / 技能列表 / 插件广场 | **新增** |
| **视图** | 左栏 `⌥⌘B` / 底部工具 `⌥⌘J` / AI 面板 `⌥⌘I` / 分屏 `⌥⌘\` / — / 打开视图（rail 全量）/ 底部工具（变量库·收藏夹·剪贴板）/ — / 实际大小·放大·缩小·全屏 / 开发者工具 | 扩充 |
| **转到** | 快速打开 `⌥⌘O` / 命令面板 `⌥⌘P` / — / 切换项目 / 项目概览 / 全部项目 / — / 上·下一个标签 | **新增** |
| **工具** | 截图取词 OCR `⌥⌘4` / 新建浏览器标签 / 活动记录(勾选) / — / 开始·停止会议录音 / — / 反馈 | **新增** |
| **窗口** | 最小化 / 缩放 / 前置全部 | 保持 |
| **帮助** | 使用手册 / 快捷键速查 / 官网 / 查看日志 / 报告问题 | **新增** |

「AI 面板」的开关只在**视图**菜单里带 `⌥⌘I`，AI 菜单不重复挂加速键——同一个加速键
出现在两个菜单项上，Electron 的行为不确定，也让「快捷键速查」没法自洽。

CLIENT 角色（`isClientView`）只见：AI Workdeck / 文件（只读子集）/ 编辑 / 视图（尽调
文件）/ 窗口 / 帮助。由 `when` 的 `role` 维度过滤，不另建一套模板。

**这是安全边界不是体验偏好。** 客户不该看到「插件广场」「系统设置」「AI」这些
入口的存在。而且过滤必须落到**命令能否执行**这一层，不能只是菜单项不渲染——
`⌥⌘I` 这类加速键在客户视图下按下去必须什么都不发生。`appMenuBridge` 的派发器在
执行前统一走一次 `when` 求值，菜单渲染和键位派发共用这一个判定。

### 6.4 Windows 自绘菜单栏

`titleBarStyle: 'hidden'` 会让 Windows 的原生菜单栏一起消失（Windows 上菜单画在
窗口边框下面，不是全局菜单栏）。三条路：

1. Windows 保留系统边框 —— 与「两个平台一起做」的口径矛盾，否决。
2. `Menu.setApplicationMenu(null)`，命令只走命令面板和顶栏按钮 —— Windows 用户
   失去菜单，否决。
3. **自绘一条紧凑菜单栏进 `.project-header` 左侧** —— 采纳。

选 3 的理由：P3 已经产出了一份命令表，把它渲染成 HTML 下拉是增量很小的事；
配色也能跟着走浅色外壳的既有口径。新增组件
`frontend/src/components/AppMenuBar.vue`，`v-if="isWin"`，读同一张命令表，
键盘可达（`Alt` 唤起、方向键导航）。mac 侧不渲染它。

### 6.5 顺带修掉的缺陷

`project-overview.vue:2376` 的注释自陈：「焦点在 LOWA webview 内时按键被 webview
吞掉收不到，属已知边界」。命令挂进原生菜单后这条**预期自动消失**（NSMenu 的
key equivalent 先于 webview 的响应链）。**这是预期不是结论，要在真机上逐键实测。**

---

## 7. 接线缺口

已核实的、需要新增出口的地方：

| 命令 | 现状 | 缺口 |
|---|---|---|
| AI 新建对话 / 停止 / 切模式 | `ChatInterface.vue:2191` 只 expose 了 `addFile` `loadMessages` `loadConversationMetadata` `sendExternalPrompt` | 需补 expose |
| 修订模式 / 审阅面板 | `EditorToolbar.vue:570` `toggleTrack`、`:218` `$emit('toggle-review')` | 需从工作台可调 |
| 接受·拒绝**全部**修订 | `ReviewPanel.vue:157` `resolveAll(action)` | 出口已有，直接接 |
| 接受·拒绝**当前**修订 | `ReviewPanel.vue:146` `resolveGroup(g, action)` 需要一个已选中的组，面板不跟踪光标 | 走 `.uno:AcceptTrackedChange` / `.uno:RejectTrackedChange`，**要往 `ui_command` 白名单加两条**（见 `.claude/agents/ai-doc-bridge.md`） |
| 检查更新 | `main.js:1438` IPC 已有 | 需确认渲染层是否已有调用点与 UI |

---

## 8. 分期与验证

| 期 | 范围 | 验证 |
|---|---|---|
| P1 | 标题栏并入顶栏；13 页走查 | §3.4 清单；`test:app-e2e` |
| P2 | `menu-state` 通道；`appMenuBridge.js`；勾选/禁用态 | 单测覆盖状态→模板映射；真机切页面看菜单灰化 |
| P3a | 命令表；九个菜单；命令面板（**mac 全通**） | 每条菜单项真机点一遍；加速键冲突单测（§4.3）；穿透 webview 实测（§6.5） |
| P3b | Windows 自绘菜单栏 | Windows 真机；`Alt` 唤起与方向键导航 |

P3b 从 P3 里拆出来单独一轮：它是全新的 UI 组件、风险最高、且与 mac 侧完全解耦。
先让 mac 全通再做 Windows，避免一个组件的问题拖住整条链。

跨期回归：`npm run test:lowa-e2e`（改了编辑器键位归属，编辑器三件套必跑）、
`npm run test:app-e2e`、`mvn test`（后端无改动，兜底）。

---

## 8.5 实施后的实测结果（2026-08-16）

四期全部落地。真机验证用 CDP 逐点探测 + `osascript` 读/点真实 NSMenu
（交通灯与菜单都是 OS 画的，不进网页截图，靠肉眼看图判断不了）：

| 项 | 结果 |
|---|---|
| 菜单栏九项 | `Apple, Electron, File, Edit, Document, AI, View, Go, Tools, Window, Help` |
| 菜单点击回路 | 点 View→Sidebar 真的翻转了 `sidebarCollapsed`（NSMenu→主进程→IPC→桥→when→活跃实例守卫→`toggleSidebar`） |
| 勾选态双向 | 开着真 Word 文档点 Document→Track Changes，勾从 ✓ 变无、再点变回 ✓ |
| 置灰 | 无文档时 Document 全灰；无项目时 Import/Reveal/Close Project 灰；无标签时 Close Tab 灰 |
| app:* 命令 | Help→Keyboard Shortcuts 弹出 12 条加速键，英文态文案正确 |
| 交通灯让位 | 13 页逐点探测：只有 login / variable-library / plugin-market 需让位，已改；其余 10 页拖拽覆盖率 100%；工作台顶栏 72% 面积可拖 |
| Windows 留白 | 模拟 `is-win` 后 padL/R = 18/148，最后一个工具按钮距右边正好 148px |
| Windows 自绘菜单栏 | 模拟 `is-win` 渲染正常，加速键转成 `Ctrl+` 写法，子菜单可展开 |

回归：`test:commands` 17/17、`test:project-home` 72/72、`check:emits`（85 个 .vue）、
`check:locales`（18 个命名空间）、`check:nav:full`、`build:h5`、`desktop npm test` 37/37、
`app-e2e` 95/0（J11 缺 `APP_E2E_JAR` 跳过，既有）、`lowa-e2e` 391/0。后端零改动，未跑 `mvn test`。

实施中自己踩到、已写进领域文档的三条：
1. 让位规则的选择器必须写 `html.is-xxx`——组件 scoped 样式同权重且注入更晚会赢；
2. 一个组件里两个同名 `watch:` 键，后写的把先写的整个覆盖掉（静默失效）；
3. 读 `documentElement.classList` 这种非响应式源不能写成 computed（缓存后永不重算）。

## 9. 未解决 / 需实测

**只能人工走查的**（自动化在本机触发不了）：

- **全屏进出**。`enter-full-screen`/`leave-full-screen` → `checkba:chrome-state` →
  `is-fullscreen` class → 顶栏留白归零。CSS 侧已实测（手动加 class 时 88px→18px
  正确切换），但真实全屏触发不了：`osascript` 点 `Toggle Full Screen` 报成功而窗口
  尺寸不变（该 role 要求真实用户交互），注入按键又需要辅助功能权限。**发版前人工过一遍。**
- **菜单加速键能否穿透 LOWA webview**（§6.5）。这是 P3 价值主张的一半，但同样要真
  按键。写进发版走查清单。

**仍未定的**：

- `⌃⇥` `⌃⇧⇥` 在 Writer 表格上下文的占用（§4.4）。本次改用 `⌥⌘←/→` 绕开了，暂无风险。
- Windows 真机：自绘菜单栏的 `Alt` 唤起、原生控件与 header 在小窗口下的挤压。
  本次只在 mac 上冒充 `is-win` 验过样式与渲染。

**本轮主动缩掉的**：

- 「接受/拒绝**当前**修订」。`ReviewPanel` 不跟踪光标，要走 `.uno:AcceptTrackedChange`
  并往 `ui_command` 白名单加两条；菜单里先只给「接受/拒绝**全部**」，用的是现成的
  `resolveAll` 出口。
- 「插入表格/图片/脚注」。要走工具栏自己的对话框流程，接线成本高而工具栏上本来就有
  入口，价值不抵成本。

## 10. 本次不做

- 手动保存 `⌘S`（§4.1）
- VS Code 式 `when` 表达式引擎（§6.2）
- 自定义快捷键的用户配置界面
- 触摸栏 / Dock 菜单 / 系统托盘

## 11. 顺带发现（不在本次范围）

`frontend/src/components/EditorToolbar 2.vue` 是 `EditorToolbar.vue` 的重复副本，
疑似误拷。属既有死代码，本次不动。
