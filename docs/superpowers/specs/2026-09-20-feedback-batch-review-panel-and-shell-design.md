# 2026-09-20 用户反馈批次：审校面板、外壳与 AI 响应（dev-board#722–#729）

维护者 2026-09-20 一次报了八条真机反馈（Windows 桌面端）。本文记录逐条的产品权衡与实现边界，
作为派工与复测的依据。权衡口径：以律师日常起草/审阅的心智为准，不为「看起来智能」而打扰阅读，
不为省资源而降检查质量。

## 1. Windows 非最大化时应用边界与文件管理器难区分（#722）

**现状**：`titleBarStyle: 'hidden'`，Windows 只多了 `titleBarOverlay`（白底），页面 `#F8F9FA`、
顶栏 `#FFFFFF`、边框 `#E9ECEF`，与浅色资源管理器几乎同色；主进程不向渲染层上报最大化状态。

**决定**：不改浅色基调（配色红线）。主进程随 `chrome state` 一并上报 `isMaximized`，
渲染层挂 `html.is-maximized`；`html.is-win:not(.is-maximized)` 用 `--awd-border-strong` 描 1px
内描边，顶栏下边框同样提到 `--awd-border-strong`。最大化与全屏时不描边。

## 2/3. 即时审校：开关、右侧清单、点击跳转、面板合并（#723、#724）

**现状**：默认开、唯一开关埋在正文浮窗底部；每次停顿 1.2s 把全文重读并 POST 到
`/insight/review`，后端无限流；每个保活 Writer 标签各起一份；结果用关不掉的 380px 浮窗压正文。
日常模式不调模型（只跑结构/编号/交叉引用/算术/信用代码规则），「深入审校」才调模型且本就手动。

**决定（维护者 2026-09-20 拍板：默认开但安静）**：
- 默认保持开启；工具栏「审阅」旁新增「审校」开关按钮，命令表加 `doc.inlineReview`；关闭后
  不发任何 worker 命令与 HTTP 请求。偏好键仍为 `awd_inline_review_<userId>`（用户级、跨标签共享）。
- 结果并入编辑器右侧审阅栏 `ReviewPanel` 第五个 tab「审校 {n}」，与修订/批注/底稿/溯源并列。
  分类（全部/待补充/一致性/格式与号码/AI 审校）与计数抽成纯函数 `utils/inlineReviewGrouping.js`，
  guest 与宿主共用。
- 点条目跳正文用 `goto_review_range`（revision + 段落原文 + 引文三重围栏），「采用建议」用
  `apply_review_edit`；禁止 `find_text_locations`（会落书签进 docx）。过期条目显式标「等待重新检查」
  并禁用定位/采用。
- 正文只留一颗浮球：图标 + 未读计数，可拖、靠边吸附、位置持久化；点击打开审阅栏并切到「审校」；
  可在面板里「隐藏浮球」（`hidden` 偏好，与 `enabled` 分开）。光标旁 chip 保留。380px 大浮窗删除。
  「已关闭」状态不再在正文里挂任何提示。
- 降资源三项：只给当前激活（可见）的编辑器标签跑；全文哈希未变化不重发；防抖 1.2s → 2.5s。
- 工具栏死掉的 `toggle-insight` 声明一并清理（保留 prop 兼容不需要）。
- 不做：把编辑器内审阅栏与工作台右栏（AI/依据）合成一套。两者宿主不同（审阅栏是画布上的
  绝对定位浮层，刻意不改 webview 尺寸，dev-board#503 黑帧教训），合并是外壳级重构，本批不动。

## 4. 光标左右横跳（#725）

**根因（按贡献排序，均有代码证据）**：
1. 默认修订视图已是内联「全部修订」（`DEFAULT_REVISION_VIEW = 'all'`），补全（35/80ms）与审校
   （180ms）每次取上下文都经 `runAgentCommandInMarginView` 把视图翻成 final 再翻回并两次
   `xModel.refresh()`，含删除修订的行随之收缩再弹回。dev-board#604 治了纵向回顶，横向未治。
2. 审阅装订线宽度 0↔280 摆动：`all` 视图下 `add()` 不带 `type`，纯插入修订也计入 `pending`，
   `hasItems` 随引擎排版起伏，700ms 轮询六次放大。
3. Windows 分数 DPI 下原生光标几何匹配容差为 ±2 物理像素，失配返回 null 后 IME 覆盖层退回
   上次点击处，候选窗与预览条在两点间跳。

**决定**：无修订文档完全不翻视图；有修订时按 text portion 的 Redline 边界跳过 Delete 段取最终
文本，不再翻视图；pending 只计真正未排版的非插入条目，宽度改动加对称迟滞；容差随 scale 缩放，
失配时优先沿用上一次成功的原生几何。每条修复配「还原病灶即转红」的 lowa-e2e 断言
（`view.caretX` 不变、`sidebarWidth` 取值集合为 1、`nativeCaret` 连续存在）。

## 5. 单独按 Ctrl 打开菜单（#726）

**探查**：全仓没有把单键 Ctrl 绑到菜单的代码。能「按一个修饰键就开菜单」的只有 Alt：
自绘菜单栏 `AppMenuBar.vue` 在 keydown Alt 时展开第一个菜单；Electron 在 Windows 上也装了
ApplicationMenu 且未调用 `setMenuBarVisibility(false)`，单键 Alt 会弹出原生菜单栏。
Windows 键盘上 Ctrl 与 Alt 相邻，判定为口误，两处一起取消。

**决定**：删除 AppMenuBar 的 Alt 唤起分支（菜单保留鼠标点开与命令表快捷键）；Windows 主窗口
`setMenuBarVisibility(false)`，ApplicationMenu 本身保留以承载 accelerator，mac 的 edit roles 不动。
复测提示：请维护者分别按 Alt 与 Ctrl 各试一次，若 Ctrl 仍会开菜单，说明是本文未覆盖的路径。

## 6. 左栏收起（#727）

**现状**：`sidebarCollapsed` 已存在，三条入口（顶栏图标、再点同一 rail 图标、Alt+Ctrl+B）
都不在左栏本体上，状态不落盘，收起时不派发 resize。

**决定**：rail 底部（成员堆叠之上）加常驻「收起/展开」按钮，图标双态；状态持久化到
`awd_sidebar_collapsed`（本机习惯，不带 projectId）；三条入口统一走同一方法，切换后
`triggerWorkbenchResize()` 与右栏一致。补纯函数单测与 app-e2e 断言。

## 7. AI 回复后的「用到文档」按钮（#728）

**现状**：只要有正文且流结束就无条件显示；后端无任何信号；AI 本轮已直接改过文档时仍显示。

**决定**：不加每轮必跑的分类模型调用。编排器统计本轮 `doc_*/sheet_*/slide_*` 工具调用，
`bubble_end` 载荷新增 `documentEdited: boolean`（旧客户端忽略未知字段即可）。前端在以下情况隐藏：
本轮已改过文档；回复处于 `awaiting_input`/提问态；正文过短（阈值 80 字且无段落/列表结构）。
其余情况照常显示。

## 8. 三页文档 AI 运行很久（#729）

**实测（本机 telemetry_event 与 backend.log）**：单条消息中位 80s（p90 320s），约 91% 是模型推理，
中位 5 个模型往返；每轮重发约 5 万 token，其中约 2/3 是工具 schema（裁到 16 个工具时 18,854 token、
首轮 6.1s；不裁 202 个时 59,045 token、26.4s）。桌面端打开 docx 仍下发全部 slide_* 与 sheet_* 工具，
它们在 Writer 文档上只会报错。编辑器桥正常往返中位 154ms，但读取类命令吃默认 30s 超时，
12 次超时空等 362s。上下文预算 10 万 / 预留 8 千与真实固定前缀 5 万不符，长会话提前触发首 token
之前的同步摘要调用。每条消息重复全量加载会话两次，文档正文无缓存每次重抽（中位 1.1s）。
前端发送前链路与上下文组装合计不到 2 秒，不是问题。

**决定（不降模型档、不砍有效上下文、不改 reasoning）**：
1. 按活跃文档类型裁工具，同一轮内工具集保持不变（保护提示缓存），本轮切换文档类型后放开全集。
   先把 SlideEditTools 补进回放评测的 RealToolBeans，否则 offeredToolsExclude 断言恒过。
2. 「本项目模板画像：有/无」由服务端写进上下文，不再让模型去列目录猜。
3. 读取类编辑器命令进分级超时表；超时回执不再引导模型连锁读全文。
4. 模型上下文预算从 AllowedModels 派生，预留按实测设置。
5. 会话消息数改 count 查询；文档抽取按 (fileId, mtime, size) 缓存。
6. 补 ai.turn 的 rounds / promptTokensFirstRound 与每轮 TTFT 日志，便于以后归因。
- 不做：「合同审查」skill 的六遍全文流程是有意的质量设计，不动；只把其 prompt 里已失效的
  「30 步暂停」承诺改成与现状一致。

## 验证口径

- 前端：`npm run test:inline-review`、`test:project-home`、`test:commands`、`test:panel-dock`、
  `check:emits`、`check:locales`；真引擎 `test:lowa-inline-review`、`test:lowa-scroll`。
- 后端：`mvn -B test -Dtest='AgentOrchestrator*,DocInsight*'`（JDK 21）。
- 桌面：`desktop npm test`；Windows 描边只能在 CI 截图或真机复测。
