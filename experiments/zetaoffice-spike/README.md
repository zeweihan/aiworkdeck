# ZetaOffice (LibreOffice WASM) Spike — Phase 0 prototype for issue #39

> 中文在前 / English below. 这是 issue [#39](https://github.com/zeweihan/aiworkdeck/issues/39)
> 的 Phase 0 原型骨架，是 RFC [#13](https://github.com/zeweihan/aiworkdeck/issues/13) v2
> （`docs/LIBREOFFICE_MIGRATION_PLAN.md`）里**整个 LibreOffice 迁移的唯一前置 gate**。
> 它本身**不是产品代码**，只回答一个问题：ZetaOffice(LibreOffice WASM) 嵌进 Electron，
> 中文 IME / 选区 / 写修订 / 50 页性能这四关到底能不能过。

## 为什么是它 / Why this gates everything

迁移的真正价值不是"换个引擎"，而是 **AI↔文档交互"定位准 + 修订优"**。这要求 app 内
**真正的实时编辑**与**模型原生**的搜索/游标/书签锚点（弃用现状 `useWpsBridge.js` 的
纯文本 offset 原罪）。RFC v2 押在 WASM 路线（ZetaOffice + zetajs，JS→UNO 桥）。
但 WASM 路线有四个**未验证**的高风险点——任何一个过不了，方案就得改：

| # | 验收标准 / Acceptance criterion | 能否自动验证 / Auto-verifiable |
|---|---|---|
| 1 | **中文 IME**：在 canvas 上用系统输入法打中文，候选词与上屏正常 | ❌ 必须真机人工敲键（canvas 上的 IME 是 LibreOffice WASM 用 Qt/emscripten 的已知难点） |
| 2 | **选区/游标**：从 JS 经 UNO 取当前选区文本与位置 | ⚠️ 半自动（UNO 调用可程序化执行并打日志，但需肉眼对照 canvas 选区） |
| 3 | **写 redline**：开 `RecordChanges`，从 JS 经 UNO 改文档并留下修订痕迹 | ⚠️ 半自动（UNO 调用可执行，修订标记需肉眼在 canvas 上确认） |
| 4 | **50 页性能**：加载/编辑 50 页 docx 的耗时与交互流畅度 | ✅ 可量化（本 harness 带性能埋点）+ 主观流畅度需肉眼 |

> 结论形态：**"何时能切 LibreOffice" = 这四关的原型结果，不是一个日期。** 见 RFC v2。

## 关键集成坎（先知道，省得踩）/ Known integration hurdles

1. **SharedArrayBuffer / 跨源隔离**：LibreOffice WASM 用 pthreads，依赖
   `SharedArrayBuffer`，浏览器要求页面**跨源隔离**（`crossOriginIsolated === true`），
   即响应头必须带：
   - `Cross-Origin-Opener-Policy: same-origin`
   - `Cross-Origin-Embedder-Policy: require-corp`
   本目录的 `serve.mjs` 已设好这两个头。**在 Electron 里**要靠
   `session.webRequest.onHeadersReceived` 注入同样的头（产品集成时的必做项，已记入 RFC 待办）。
2. **WASM 体积/拉取**：默认从 `cdn.zetaoffice.net` 拉运行时（首次数百 MB）。产品化要
   自托管进安装包（对标现状 jar+JRE 捆绑），离线可用、数据不出本机。
3. **canvas + IME 事件**：LibreOffice WASM 是 Qt5 emscripten 后端，canvas 元素与
   composition/keydown 事件的对接是中文 IME 成败处——harness 已挂事件日志辅助诊断。
4. **zetajs 仍在 beta**：loader 脚本 URL / API 可能随版本变动。运行前对照
   [allotropia/zetajs](https://github.com/allotropia/zetajs) 最新示例
   （尤其 `examples/letter-address-vuejs3`，与本项目 Vue3 前端同栈）核对
   `ZETA_LOADER_URL`。zetajs 为 **MIT**，LibreOffice 为 **MPL-2.0**（与本项目 AGPL 兼容）。

## 怎么跑 / How to run

```bash
cd experiments/zetaoffice-spike
node serve.mjs            # 起带 COOP/COEP 头的本地服务器（默认 http://localhost:8777）
# 浏览器打开 http://localhost:8777 —— 页面会先自检 crossOriginIsolated 是否为 true
```

页面加载后：
1. 看顶部状态条 `crossOriginIsolated` 是否 ✅（否则 SharedArrayBuffer 不可用，WASM 起不来）。
2. 点 **Boot ZetaOffice** 等运行时下载+初始化（首次慢，看日志面板进度）。
3. 逐项点四个测试按钮，对照 canvas 与日志面板记录结果。
4. **中文 IME**：在 canvas 文档里直接用系统输入法打字，观察候选与上屏；harness 会在
   日志里打 `compositionstart/update/end` 事件帮助判断事件链是否到位。

### 在 Electron 里跑（权威环境 / authoritative）

中文 IME / canvas 渲染 / probe 往返这几关，**只有真机的 Electron 能下结论**。本目录带了一个
**自包含的 Electron 启动器** `electron-main.js`——它不碰产品 `desktop/main/main.js`（spike 是纯
前端、不需要后端，避免本机捆绑 JRE 的 SIGBUS），并用 `session.webRequest.onHeadersReceived`
注入 COOP/COEP，**正是产品将来要用的机制**（内置静态服务器故意不设这俩头，专门验证 Electron 注头这条路）。

复用项目已装的 Electron（无需额外下载）：
```bash
cd experiments/zetaoffice-spike
../../desktop/node_modules/.bin/electron .     # 或 npx electron .
```
窗口打开后：点 **Boot ZetaOffice** → 看 canvas 是否渲染出 Writer 文档 → 用系统输入法在文档里打中文
→ 点 selection / redline / perf 三个探针。devtools 已自动打开，配合右侧日志面板与 console 观察。
请在 **macOS + Windows 各测一遍中文输入法**。

> 浏览器路线（`node serve.mjs`，上一节）只能验证基础设施 + 部分 UNO；canvas 像素与中文 IME 的
> 权威结论必须在这个 Electron 启动器里取。

## 现状 / Status

骨架已**实跑过一轮**（2026-06-21，无头 Chromium 预览经 `serve.mjs` 打开）。基础设施链路
**已验证打通到"文档已加载"**，途中修掉两个真 bug：

**✅ 已验证可行（infrastructure path proven）**：
- `crossOriginIsolated=true`、`SharedArrayBuffer` 可用（`serve.mjs` 的 COOP/COEP 头生效）。
- LOWA 运行时（`soffice.wasm`/`.data`）**从 `cdn.zetaoffice.net` 在 COEP 下成功下载并实例化**。
- Qt 起事件循环（持续重绘）、office 线程启动、主线程 `Module.uno_main` 线程端口连通、
  `loadComponentFromURL` 文档加载完成（`ui_ready` 生效：测试按钮启用、spinner 隐藏、canvas 置 visible）。

**🐞 实跑中发现并修复的两个真 bug**：
1. **`zeta.js` 不在 CDN**：原先从 `cdn.zetaoffice.net/.../zeta.js` 取 → worker `importScripts`
   报 `NetworkError` → 一连串 `emscripten_proxy_async failed`。CDN 只托管 LOWA 构建，不含桥脚本。
   **改为本地 vendored `./zeta.js`**（取自 allotropia/zetajs `source/zeta.js`，MIT，已随仓提交）。
2. **主线程端口 API 用错**：原用 `Module.zetajs.then`（它只在 office worker 里存在）→ 主线程拿不到端口。
   **改为 `Module.uno_main.then(pThrPort=>…)` 且在 `soffice.js` 的 `onload` 里挂**（对照 web-office 示例）。

**⚠️ 此无头预览环境下"不保真"、需真机/Electron 定夺的（这本就是 spike 的目的）**：
- **canvas 像素不出**（黑屏）——无头 Chromium 的 Qt/WebGL 不绘制；需真显示器。
- **中文 IME**——必须人工敲输入法（#39 第一风险）。
- **主↔worker probe 往返 + 页面日志面板**——boot 后我方 `out()` 写 DOM、`thrPort` 往返在此无头
  环境表现异常（疑与 emscripten/Qt 事件循环下的 DOM/消息泵时序有关）。在真机/Electron 复测前，
  ②③④三关**不下结论**。代码已尽量对齐官方示例；`VERIFY` 标记处以真机为准。

**📌 隔离**：全在 `feat/libreoffice-migration` 分支，master 继续发 WPS 稳定版，互不影响。

> **下一步 gate**：在真机（macOS + Windows）/ Electron 渲染进程里跑本 harness（Electron 用
> `session.webRequest.onHeadersReceived` 注 COOP/COEP），逐项确认 canvas 渲染、中文 IME、
> selection/redline/perf 往返。过则迁移继续并自托管 LOWA 进安装包；不过则回退评估 native-headless。

---

**English summary.** Phase 0 prototype harness for issue #39 — the single gate for the whole
LibreOffice migration (RFC #13 v2). It answers one question: can ZetaOffice (LibreOffice WASM)
embedded in Electron pass four unverified, high-risk checks — **Chinese IME on canvas, JS→UNO
selection, JS→UNO tracked-change (redline), and 50-page performance**. Chinese IME can only be
judged on a real device; selection/redline are semi-automatable via UNO with visual confirmation;
perf is instrumented. LibreOffice WASM needs `SharedArrayBuffer`, so the page must be
cross-origin isolated — `serve.mjs` sets `COOP: same-origin` + `COEP: require-corp`; in Electron
the same headers must be injected via `session.webRequest.onHeadersReceived`. Runtime is pulled
from `cdn.zetaoffice.net` (hundreds of MB first load; productize by self-hosting in the installer).
zetajs is MIT, LibreOffice is MPL-2.0 (both compatible with this project's AGPL). Run:
`node serve.mjs` then open `http://localhost:8777`. The verdict — *not a date* — is the deliverable.
