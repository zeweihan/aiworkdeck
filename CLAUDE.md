# CLAUDE.md

AI Workdeck（checkba_cloud）：面向法律行业的 AI 工作台。Java Spring 后端（backend/）+ uni-app/Vue3 前端（frontend/）+ Electron 桌面壳（desktop/），文档编辑器为 LibreOffice WASM（代号 LOWA/zetaoffice），另有 pptx/mineru/kokoro/easyvoice 附属服务。

## 领域文档路由表（先读文档，再动代码）

接到任务后，**不要全量扫描代码库**。先按下表判断任务落在哪个领域，读对应的 `.claude/agents/<领域>.md`——里面有该领域的关键文件地图、核心契约、已知地雷和验证命令。跨领域任务读多份。

| 任务涉及 | 领域文档 |
|---|---|
| AI 对话、编排器、工具注册、记忆、SSE 流、评测 | `.claude/agents/ai-chat.md` |
| doc_* 编辑原语、AI 改文档、修订（redline）、检查点、EDITOR_ACTIONS | `.claude/agents/ai-doc-bridge.md` |
| LOWA/zetaoffice 编辑器、字体、IME、保活、自动保存、.uno: 命令 | `.claude/agents/doc-editor.md` |
| 浏览器面板、截图、剪贴板、收藏夹、搜索、下载、语音、文件预览/插入、OCR | `.claude/agents/utility-tools.md` |
| 左侧栏、工作台布局、页面路由、面板切换、设置入口、project-overview.vue 结构 | `.claude/agents/sidebar-shell.md` |
| 具体插件（尽调/股东大会、脱敏等）、skill 定义与注入 | `.claude/agents/plugin-system.md` |
| 诉讼可视化（时间轴/流程图/关系图）、litviz 引擎、semantic-map、graphviz 打包 | `.claude/agents/litigation-visual.md` |
| 插件市场页、registry 同步、skill 安装与启停 | `.claude/agents/plugin-marketplace.md` |
| 授权、计费、账户连接、entitlement、广场付费 | `.claude/agents/licensing-billing.md` |
| Office 插件（Word/Excel/PPT 任务窗格）、manifest、Office.js、sideload | `.claude/agents/office-addin.md` |
| 构建、发版、CI、测试体系、本地开发启动 | `.claude/agents/eng-infra.md` |
| 版本记录、工作段、时间线、退回、Git 仓库 | `.claude/agents/version-control.md` |
| 反馈浮窗、反馈落库、优化者（分诊/开 PR/发邮件）、后台反馈看板 | `.claude/agents/feedback-optimizer.md` |

这些文件同时是可派遣的 sub-agent 定义：需要并行探查或委托领域内工作时，可直接用对应 agent 类型派子任务。

## 维护规则

- 合并的 PR 如果改变了某领域的文件布局、契约或新增了地雷，**同一个 PR 里顺手更新对应领域文档**，防止文档腐烂。
- 新增领域时在本表加一行。

## 全局约定

- `docs/` 目录在 .gitignore 里，向其中添加需入库的文件要 `git add -f`。
- worktree 有独立的 src/，编辑与构建必须在同一棵树内，不要误用主仓库路径。
- 本机跑 `mvn` 必须 JDK 21（系统默认 25 会 SIGBUS）。
- 前端包管理用 npm（不是 pnpm）。
- 版本号单一来源是 `desktop/package.json`。
- **三个 project-\* 路由同名不同物**：`pages/project-overview/project-overview` 在代码里指**工作台**（四列干活界面，刻意不改名）；产品语言里的「项目概览」现在是工作台里的一个标签（内容组件 `components/project-home/ProjectHomePane.vue`），`pages/project-home/project-home` 退成只服务直链的薄壳页；「项目列表页」是 `pages/project-list/project-list`，也是启动的唯一落点。写代码以路由为准，写文案以本条为准。导航总规则：凡是工作台参与的跳转一律 `reLaunch`，工作台之外的页面之间用 `navigateTo`（同级页面如设置⇄个人中心用 `redirectTo`，压栈会互相弹成死循环）。详见 `.claude/agents/sidebar-shell.md` 的术语表。
