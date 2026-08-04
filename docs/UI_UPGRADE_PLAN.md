# 产品 UI 全面升级：对齐官网原型（2026-08-03）

> 状态（2026-08-04）：Phase 1-6 全部落地并合并。验证：check:emits ✓、build:h5 ✓、
> lowa-e2e 169/169 ✓、app-e2e 100/100 ✓（12 条信号均为 QA 账号访问 admin 接口的
> 预期 403）、dev+puppeteer 六态截图目检 ✓。工具栏精确配色（重烧 r4）仍在不做清单。

目标：把桌面产品的工作台外壳从当前「浅色管理后台」形态升级为官网原型（`prototype_awd`，2026-07-18 版）的「IDE 式法律工作台」形态。视觉权威 = 官网 `aiworkdeckweb/DESIGN.md` v1 + 原型 `assets/aiworkdeck.css` 设计令牌。

## 一、差距分析

### 1. 原型形态（web-lawyer/workdeck.html）

- **整体**：深绿 chrome 包裹浅色纸页画布的 IDE。40px 深色顶栏（品牌 + 面包屑 + 保存态 + 协作头像 + Share）→ 三栏体（240px 项目树 / 编辑器 / 340px AI 面板）→ 32px 底部状态条。
- **左栏**：深色项目树。栏头有项目名 + 属地 flag + AI 助手在线状态卡；树行 24px 高、激活行左侧 2px 绿色指示条、文件类型着色图标、AI/NEW 徽标。
- **中栏**：深色 tab 条（活跃 tab 底部 2px 强调线 + dirty 圆点）→ 浅色 ribbon → 深绿画布（#1D3A29）上漂浮 720px 白纸页（#FCFBF8 + 大阴影），页边悬挂 AI/同事批注卡。
- **右栏（AI 面板）**：助手头部（渐变头像 + 在线点 + 名称 + 能力签名 + 历史/新线程/设置图标）→ Chat/Sources/Plugins 标签 → 消息流（含表格、来源行、thinking pill）→ 底部技能 chips 行（可增删 + 「+ Add skill」直达市场）→ 输入框（@file//skill 占位、附件、模型指示、发送钮）。
- **底部状态条**：左侧 Favorites/Clipboard/Variables 工具（带计数）；中部「Privileged · audit-logged」合规锚点；右侧模型/上下文用量/编码/行列号。等宽字体，VS Code 语法。
- **设计令牌**（aiworkdeck.css，注意变量名沿用旧命名但值已是绿系）：
  - chrome 深绿阶：`#0E2117`（底）/ `#0F2A1C`（顶栏）/ `#0D241A`（侧栏）/ `#1D3A29`（分隔线、画布）/ `#2D5240`（高亮边）
  - 品牌：navy=`#1A5336`（forest）、brass=`#3E8E63`、tiffany/mint=`#5BD197`
  - 中性：ivory `#FAF8F4` / bone `#F1ECE3` / stone `#E2DBCE` / slate `#6B6E73` / charcoal `#2B2C30`
  - 字体：衬线展示（Fraunces→中文对应 Noto Serif SC/宋体栈）、Inter 正文、JetBrains Mono 状态条/元信息
- **原型内部不一致（实施时纠偏）**：workdeck.html 残留早期藏蓝色（hover `#14253D`、文字 `#97A6BD`/`#C9D1E0` 等）与绿系混用；产品实施统一收敛为绿系色阶，蓝灰文字换算为绿灰（如 `#7FA08F`/`#A8BDB2` 系）。
- **导航模型**：原型有两套 chrome——浏览型页面（Projects/Marketplace/Inbox/Calendar）用 56px 浅色 web-nav；工作台用深色 IDE chrome。Marketplace 在原型中是整页。**用户要求 Marketplace 改为 VS Code 式 workbench 内 tab（保留侧栏与标签页），此要求覆盖原型方案**。

### 2. 产品现状（差距）

- 品牌色仍是墨蓝 `#12344D` + 金 `#C8A45D`（uni.scss），与官网绿系不一致；plugin-market 页已在 PR#206 单独对齐绿系，形成一 app 两制。
- 外壳全浅色：顶部 project-header、rail、sidebar-left、workbench、bottom-panel、ai-panel 均为白/浅灰底，无 chrome/画布层次。
- 无衬线展示字、无等宽元信息字，信息密度与「专业工具」气质差距的主要来源。
- 底部是可开关的工具抽屉（variables/favorites/clipboard），无常驻状态条语义。
- Marketplace 从 admin 页整页跳转进入，脱离工作台上下文。
- 编辑器画布：引擎默认浅灰周边 + 白纸，无深底浮纸效果。

### 3. 差距原因

产品外壳是从管理后台模式（uni-app 默认浅色 + 蓝金品牌）长出来的，逐功能贴装；原型是从「法律刊物编辑排版 + IDE」的定位一次性设计的。两者不是执行质量差距，是设计系统缺位——产品从未引入官网 DESIGN.md 的令牌与版式语言。

## 二、设计原则（对齐 2026 现代工具审美）

参照 Linear / Cursor / VS Code / Arc 一代工具的共识，结合官网 DESIGN.md 硬红线：

1. **Chrome 退后，内容向前**：深色 chrome 收缩视觉噪音，唯一的浅色高亮区就是用户正在编辑的纸页。
2. **一套令牌，两种表面**：dark chrome surface 与 light canvas surface 都从同一绿系令牌派生；禁止逐处硬编码。
3. **字体三轨**：衬线只用于展示位（项目名、助手名、大标题），正文无衬线，元信息/状态一律等宽——这是原型专业感的最大来源之一。
4. **状态可见性**：保存态、AI 在线态、Privileged 合规锚点、模型/上下文用量常驻可见（thin status bar），不塞抽屉。
5. **禁 emoji、禁紫粉渐变、禁大圆角胶囊糊屏**（官网红线延伸到产品）；mint 只做点缀不做大面积底色；深底文字对比度 ≥ 4.5:1。
6. **页面不整体跳转**：市场、设置等次级面走 workbench tab 或浮层，保持工作台空间稳定（VS Code 模式）。

## 三、实施阶段

### Phase 1 · 设计令牌（uni.scss）
新增绿系令牌全集（chrome 五阶、品牌三色、中性阶、语义色、字体三栈、radius/shadow），旧 `$brand-color-primary/gold` 保留为别名指向新值渐进迁移。

### Phase 2 · 外壳 chrome 深色化（project-overview 四列 + FileTree）
- 顶部条改 IDE 深色 chrome：品牌标 + 项目切换器/面包屑 + 保存态 pill + 现有工具开关（图标化、深色 hover 态）。
- rail + sidebar-left 深色化；FileTree 树行/激活条/图标着色对齐原型。
- workbench tab 条深色化（活跃 tab 强调线 + dirty 点）。
- 新增 32px 底部状态条：左＝variables/favorites/clipboard 三工具入口（替代/联动现有抽屉开关，带计数）；中＝Privileged·审计锚点;右＝AI 模型与状态。原抽屉面板保留，从状态条唤起。
- ai-panel 深色化。
- 地雷：每处布局开关后 `triggerWorkbenchResize`；全局订阅守卫模式不动；类名改动禁 sed 子串替换。

### Phase 3 · AI 面板内容对齐（ChatInterface）
助手头部、消息气泡深色化、技能 chips 行（映射已启用 skill，+ 号打开市场 tab）、输入区（附件/模型指示/发送）。只动样式与轻结构，不动编排契约。

### Phase 4 · Marketplace 内嵌（VS Code 式）
plugin-market.vue 主体抽为 `PluginMarketPane` 组件；workbench 支持 `fileType:'market'` 特殊 tab；rail/admin/AI 面板「+」入口统一 openMarketTab()；独立页面路由保留兜底（浏览器直链）。

### Phase 5 · 编辑器画布（免重烧）
- 引擎运行时配置：bootDoc 写 ColorScheme `AppBackground=#1D3A29`、`DocColor=#FCFBF8`（ConfigurationUpdateAccess，同 setRedlineAuthor 机制；先在 ⌘⇧L 验证窗试）。
- 宿主协调：editor.html body、加载面板、只读预览层、窗格外框全部换深绿底，消除 boot/切换闪白。
- 工具栏保留引擎默认浅灰（接近 ivory 观感）；**不重烧引擎**。若日后要钉死 #FAF8F4 工具栏，走 r4 QPalette 小补丁（`source-patches.diff` AfterAppInit 加 QPalette，风险低但缓存全体作废），本期不做。

### Phase 6 · 验证与交付
`check:emits` → `test:lowa-e2e` → `test:app-e2e` → dev Electron+CDP 截图 vs 原型对比 → PR → 合并 → 同 PR 更新 sidebar-shell.md / plugin-marketplace.md / doc-editor.md。

## 四、不做清单（本期）

- 不重烧引擎（r4 留待工具栏配色决策）。
- 不做原型中的协作头像/Share/Firm 视图（无对应后端能力，避免摆设 UI）。
- 不动 login/newproject/admin 等次级页面的深度重设计（跟随令牌自然过渡，后续迭代）。
- 不引入打包字体（衬线用 Noto Serif SC → Songti SC → SimSun 系统栈，等宽用 JetBrains Mono → Menlo/Consolas 栈）。
