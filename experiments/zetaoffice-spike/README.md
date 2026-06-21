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

### 真机/Electron 验证（权威）

中文 IME 这关**只有真机能下结论**。产品集成时把本 harness 的逻辑搬进 Electron 渲染进程
（记得用 `onHeadersReceived` 注入 COOP/COEP），在 macOS + Windows 各测一遍中文输入法。
本机若 `JDK SIGBUS` 跑不了桌面壳，可先用上面的浏览器流程验证 2/3/4，IME 留真机。

## 现状 / Status

- ✅ 骨架就位：四项验收各有独立测试入口 + 性能埋点 + 事件诊断日志 + 正确的 COOP/COEP 服务器。
- ✅ 对着 zetajs 真实 API 写（`zetajs.uno.com.sun.star`、`Desktop.create`、
  `loadComponentFromURL`、`getController().getModel()`）。
- ⏳ **未下结论**：本骨架尚未在真机/Electron 完整跑出四关 verdict——UNO 选区/redline 的
  确切调用、Qt canvas 的 IME 事件链，都要实跑才能定。这正是 spike 的目的。
- 📌 隔离：本工作全在 `feat/libreoffice-migration` 分支，master 继续发 WPS 稳定版，互不影响。

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
