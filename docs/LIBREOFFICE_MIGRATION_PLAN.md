# WPS WebOffice → LibreOffice 迁移方案（RFC v2）

> 本文档是 issue #13 的技术主文。v2（2026-06-17）基于对现有 AI↔文档交互链路的完整勘察，
> 纠正了 v1 的重心错误：**v1 把文件读写/Electron 壳当主线、把 AI 交互降级为"第四阶段功能对齐"**，
> 这是反的。本项目的命门是"AI 直接操作文档"，迁移的成败由它决定，不由文件 IO 决定。

---

## 0. 核心判断（先读这一节）

### 0.1 换引擎是**必要条件，但不是充分条件**

维护者对编辑器内核的真实诉求不是"换一个开源的壳"，而是三句话：**定位准确、修订优秀、交互丝滑**。
而代码勘察揭示一个反直觉的事实：

> **"定位不准"不是 WPS 独有的缺陷，是当前定位架构的原罪——照搬到 LibreOffice 只会更糟。**

当前 AI 定位文本的链路（`frontend/src/composables/useWpsBridge.js:747` `findTextLocations`）：

1. 取全文 `doc.Content.Text`；
2. 在 **JS 纯文本字符串**里 `indexOf` 求字符 offset；
3. 用整数 offset 调 `doc.Range(start, end)` 去富文本模型里定位、替换。

**病根 = "纯文本字符 offset ↔ 富文本模型坐标"这个映射对不齐**：分页符/隐藏域/表格标记不计入 `Content.Text` 却占模型位置；`\r\n` vs `\n` 偏移；修订态下 Range 边界漂移。这是**任何富文本编辑器都存在**的映射裂缝。LibreOffice WASM 默认暴露的 JS API 比 WPS 更少（v1 自己都退到"靠剪贴板/Uno 中转取选区"）——**若照搬 offset 思路，AI 体验会倒退，不会前进。**

### 0.2 正确的命题是三根支柱，不是"换引擎"

| 支柱 | 现状（WPS） | LibreOffice 做到"优秀"的方式 |
|---|---|---|
| **定位准确** | JS 文本 offset → Range，系统性错位 | 改用**模型原生搜索**（UNO `com.sun.star.util.XSearchable` / `XReplaceable`）+ **文本游标**（`XTextCursor` / `XParagraphCursor`）直接在文档模型里定位；锚点用 **书签/文本字段**（`XBookmarksSupplier` / `XTextFieldsSupplier`），**彻底弃用整数字符 offset** |
| **修订优秀** | AI 改动前**先关修订**（`useWpsBridge.js:232` `disableTrackRevisions`）→ 改动**不留痕**，对律师审阅是反的 | LibreOffice 修订（redline，`RecordChanges=true` + `XRedlinesSupplier`）是**一等公民、开源可控**。**默认开着修订让 AI 改**，每处 AI 改动即一条可接受/拒绝的 revision——这才叫"修订优秀"（是一次默认值纠偏，不只是换引擎） |
| **交互丝滑** | SDK 易取选区，但闭源/依赖云 | **关键未知，必须原型验证**：ZetaOffice 的 **zetajs（JS→UNO 桥）**能否程序化取选区/搜索/写 redline（zetajs 可跑 UNO 脚本，远强于 v1 假设的"剪贴板 hack"）；**WASM canvas 上的中文输入法（IME）**是否可用——这是 WASM 桌面编辑器的经典硬骨头 |

### 0.3 最大的好消息：后端 AI 契约**编辑器无关**，迁移范围可控

后端 agent 与文档之间是一层**抽象命令契约**，不绑定 WPS：

- `WpsTools`（14 个工具，`backend/.../service/ai/tools/WpsTools.java`）→
- `WpsActionService`（`backend/.../service/ai/WpsActionService.java:190`）经 **SSE 下发 `client_action` 事件 → 前端 executor 执行 → 回传 `/api/ai/agent/wps-result`**（30s 超时）。

这套契约不依赖任何 WPS 专有概念。**真正需要重写的只有前端 executor 一层**：`wps-sdk.js` + `useWpsBridge.js` + `WpsEditor.vue`（约 2–3k 行）。
后端 agent/工具定义、SSE 编排、会话管理**基本不动**。

> 结论：这不是"重写一个编辑器"，而是"换一层适配器 + 重做定位/修订模型"。范围被后端契约的稳定性框住了。
> （迁移期建议把工具契约里残留的 WPS 命名抽象为编辑器无关命名，如 `editor_find_text`，但可后置。）

---

## 1. 技术选型

保留 v1 的三方案对比，结论不变：路线 A 是目标态，但**必须先过原型门控**。

| 特性 | 原 WPS Web | **A. LibreOffice WASM（ZetaOffice/LOWA，目标）** | B. 唤起原生 LibreOffice |
|---|---|---|---|
| 部署 | 在线/私有化服务端 | **内置桌面客户端** | 用户自装 Office |
| 文件 | 上传服务器 | **直接读写本地磁盘** | 本地磁盘 |
| 编辑体验 | 浏览器内嵌 | **应用内嵌（IDE 感）** | 独立窗口 |
| 网络 | 强依赖 | **完全离线** | 完全离线 |
| **AI 取选区/改 redline** | SDK 易取 | **经 zetajs→UNO（待原型验证）** | 跨进程，难 |
| 中文 IME | 成熟 | **WASM canvas 上待验证（最大风险）** | 原生，无问题 |
| 开发成本 | 低 | **高** | 极低 |

**B/C 作为退路**：若原型证明 A 的中文 IME 或 zetajs 桥不可行，退回 B（唤起原生 `soffice`，牺牲 IDE 嵌入感换确定性），或 C（headless `soffice --convert-to` 做格式转换 + 自研轻量只读/批注层）。

---

## 2. 实施路线（门控式，非时间表）

> "什么时候能"不是一个日期，是一个 **gate**。在原型出数据之前，任何时间表都是编的。

### Phase 0 — 原型 spike（解锁全局，唯一前置）✅ **已完成（2026-06-22）**

目标：用最小代价证明路线 A 的三个生死项。**这三关任一过不了，A 不走，转 B。**

**验收结果（go/no-go）→ 结论：GO，路线 A 走**（harness 在 `experiments/zetaoffice-spike/`，分支 `feat/libreoffice-migration`；实测见 [issue #39](https://github.com/zeweihan/aiworkdeck/issues/39)）：
- [x] **中文 IME + 字体** → ✅ **解决，但两层各需一项工程**：①**渲染**——CDN 默认 LOWA 构建无 CJK 字体（中文显示豆腐块），PoC 证实**往字体目录注入 CJK 字体即正确渲染**（简繁皆可）；②**输入**——Qt5-WASM 上游无 IME（候选框不弹），PoC 证实**自建"真 input 承接系统 IME → `compositionend` → UNO 在光标处 `insertString` 插入"的输入桥可正常连续中文输入**（含焦点交还、追加不替换/不选中）。
- [x] **zetajs 程序化能力** → ✅ **全部通过**：取选区（`XSelectionSupplier.getSelection`）、模型原生搜索定位（`createSearchDescriptor`/`findFirst`/`findNext`）、`RecordChanges=true` 下替换留修订痕——**RFC 0.2 的"弃 offset、用模型原生"主张功能性验证通过**。
- [~] **性能** → ⚠️ **部分**：UNO 批量生成 50 页 ≈ 26.5s（仅程序化写入，**非真实编辑**）；**待补测**"加载既有 50 页 docx"打开/保存耗时与编辑流畅度（更接近真实场景）。
- [~] **包体** → ⏳ **待测**：当前从 `cdn.zetaoffice.net` 拉运行时（首次数百 MB）；自托管进安装包的体积增量待评估（与现 ~560MB 安装包叠加）。

**关键工程事实（接手者必读）**：① LOWA 运行时＝`cdn.zetaoffice.net/zetaoffice_latest/` 的 `soffice.{js,wasm,data}`，但 **`zeta.js` 桥不在 CDN，必须本地 vendored**；② 主线程线程端口＝`Module.uno_main`（非 `Module.zetajs`，后者只在 office worker 里），且须在 `soffice.js` 的 `onload` 里挂；③ WASM 需 `SharedArrayBuffer` → 页面须跨源隔离（COOP `same-origin` + COEP `require-corp`），Electron 里用 `session.webRequest.onHeadersReceived` 注入；④ emscripten 全局 `out`（stdout）会覆盖页面全局 `out`，勿用 `out` 当全局名；⑤ 字体注入要在 fontconfig 启动扫描前（`Module.preRun`，LOWA data 挂载 merge 进 MEMFS 故有效）。

产出：可运行 harness（`experiments/zetaoffice-spike/`：浏览器 `serve.mjs` + 真机 `electron-main.js`）+ 实测截图/日志（#39）。**四个生死项除"性能/包体"两项量化补测外，机制全部跑通 → 立项 Phase 1。**

### Phase 1 — 桥接层按模型重写（核心工作量）

原型过关后，重写前端 executor，**对齐后端既有命令契约**（后端不动）：

- 定位：`findTextLocations`/`getParagraph` → UNO `XSearchable` + `XParagraphCursor`，**输出锚点（书签/段落游标）而非整数 offset**。
- 修订：`ensureTrackRevisions` → `RecordChanges=true`，**AI 改动默认走 redline**；移除"AI 操作前关修订"的旧默认。
- 选区/插入/替换：`replaceAtPosition`/`insertAtCursor` → UNO 游标 + `XReplaceable`。
- 变量锚点：书签/文档域 → UNO `Bookmarks`/`TextFields`。
- PPT：Impress UNO（架构差异大，**单列、可后置**；现状靠【】文本标记，迁移时保持降级或延后）。

### Phase 2 — 文件 IO / Electron 壳（v1 已覆盖，降为支撑项）

- 主进程 `fs` 直接读写本地 `.docx`；渲染进程 WASM 编辑；`ArrayBuffer` 双向桥（v1 第二、三阶段方案可复用）。
- 与"双击可用"单机模式（Epic #18 已交付）对齐：文档不出本机，呼应"数据不出本机"叙事。

### Phase 3 — 灰度

- WPS 与 LibreOffice executor 并存、按开关切换；用真实合同回归"定位/修订"准确率；稳定后默认切换、移除 WPS SDK 依赖。

---

## 3. 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| **WASM canvas 中文 IME 不可用** | 路线 A 致命 | Phase 0 首验；不行则转 B |
| zetajs JS→UNO 桥能力不足以取选区/写 redline | AI 交互无法实现 | Phase 0 第二验收项；不行则转 B/C |
| WASM 冷加载慢 / 包体大 | 体验与安装包膨胀 | 预热、按需加载、与 B 权衡 |
| ZetaOffice 仍在快速迭代 | 接口不稳 | 锁版本、封装适配层隔离 |
| 桥接层重写期 AI 功能回归 | 用户可感知退化 | Phase 3 双 executor 灰度 + 真实合同回归集 |

---

## 4. 下一步 — Phase 1 已立项（2026-06-22）

Phase 0 GO，正式立项 Phase 1。执行任务拆解（可认领，见 **Epic 立项 issue**）：

1. **字体焙进自托管 LOWA bundle**（解 CJK 渲染）：自托管 `cdn.zetaoffice.net` 的运行时到安装包，并把 **Noto Sans CJK / 思源（OFL）** 放进 `/instdir/share/fonts/truetype/`，构建期由 fontconfig 原生扫到（替代 PoC 的运行时注入）。
2. **IME 输入桥工程化**（解中文输入）：把 PoC 的"真 input 承接 IME → UNO insertString"做成**覆盖在 canvas 光标处的透明可编辑层**（跟随 LO 光标、合成中预览、Backspace/方向键/Enter 转 UNO 命令），用户感知＝"直接在文档里打字"。
3. **后端工具契约改名（小重构、先行、后端不动逻辑）**：`WpsTools`/`WpsActionService`/`useWpsBridge` 等 WPS 命名 → 编辑器无关命名，降低耦合（RFC 0.3：后端命令契约编辑器无关，迁移只动前端 executor）。
4. **前端 executor 按模型重写**（核心 ~2–3k 行，见 Phase 1）：定位→`XSearchable`+游标+书签锚点（**弃整数 offset**）；修订→`RecordChanges` 默认开；选区/插入/替换→UNO 游标+`XReplaceable`。
5. **性能/包体量化补测**：加载既有 50 页 docx 的打开/保存耗时 + 编辑流畅度；WASM+data 对安装包的体积增量。
6. **Electron 集成**：把 spike 的 `onHeadersReceived` COOP/COEP 注入 + WASM 渲染窗，按独立 session/partition 接进 `desktop/main/main.js`（不影响主应用现有 webSecurity 设置）。
7. **Phase 3 灰度**：WPS 与 LibreOffice executor 并存、开关切换、真实合同回归"定位/修订"准确率，稳定后默认切换、移除 WPS SDK。

> 维护原则：本文档随实现进展持续更新；Phase 1 各任务欢迎以评论或 PR 认领（见 Epic 立项 issue）。

---

## 附录：v1 历史方案（存档）

v1 以"文件 IO + Electron 壳"为主线，其 Electron 主/渲染进程分工、`ipcMain` 文件桥、WASM 虚拟文件系统加载/保存流程在本 v2 的 **Phase 2** 仍然适用，作为支撑项保留。v2 的核心改动是把 **AI 交互（定位/修订/选区）从"第四阶段附属"提升为决定迁移成败的主轴，并以 Phase 0 原型门控前置**。
