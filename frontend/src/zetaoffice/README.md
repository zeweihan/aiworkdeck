# Embedded LibreOffice editor (ZetaOffice) — assembly

Epic #43 / #79. This directory is the webview-side editor page; the host-side
and Electron pieces live elsewhere. Since #79 (WPS removal) the embedded
LibreOffice editor is the product's ONLY document editor.

## The six pieces (all landed, dormant)

| Piece | Where | Status |
|---|---|---|
| Editor-agnostic dispatch seam | `composables/useEditorBridge.js` | #45 |
| ZetaOffice boot | `composables/zetaOfficeBoot.js` | #46 ✅ verified vs real LO |
| Office worker UNO commands | `public/office_thread.js` (+ `zeta.js` bridge) | from #44 |
| Executor client | `composables/libreofficeExecutorClient.js` | verified |
| Host↔webview command relay | `composables/zetaOfficeRelay.js` | #48 ✅ verified |
| Webview-side endpoint | `composables/zetaOfficeEditorEndpoint.js` | #49 ✅ verified |
| This page (boot+serve) | `zetaoffice/editor.{html,js}` | #51 |
| Dedicated build | `vite.zetaoffice.config.js` | #51 ✅ self-contained output |
| Electron partition isolation | `desktop/main/zetaoffice-session.js` | #47 |
| Host webview executor | `composables/useZetaOfficeWebview.js` | this PR |

## Build

```
cd frontend && npm run build:zetaoffice      # -> dist/zetaoffice/
```
Output = `editor.html` + the client bundle + `zeta.js` + `office_thread.js`. Only
the LOWA runtime (`soffice.{js,wasm,data}`) + CJK fonts are loaded at runtime
(CDN in dev via `sofficeBaseUrl`; self-host them for the packaged app).

> `public/{zeta.js,office_thread.js}` are currently **copies** of
> `experiments/zetaoffice-spike/`. TODO: unify to a single source (make these
> canonical and have the spike load them) to avoid drift.

## Data flow

```
host (project-overview)                     isolated <webview partition="persist:zetaoffice">
  useEditorBridge(EDITOR_LIBREOFFICE)          editor.html -> startEditorEndpoint
    libreExecutor =                              bootZetaOffice -> office worker (UNO)
      createWebviewEditorExecutor(webviewEl)     serveExecutor  <—— ipcRenderer.sendToHost
        createRelayExecutor  ——webview.send——>   (lo-relay channel)
```

## Remaining on-device wiring (apply on a real machine; needs Electron)

1. **Self-host LOWA + fonts**: download the `zetaoffice_latest` runtime + bake
   Noto/思源 CJK (OFL) into the bundle; point `editor.html?lowa=...&font=...` (or
   the defaults) at the packaged copies. Add `dist/zetaoffice` to
   electron-builder `extraResources`.
2. **Electron main** (`desktop/main/main.js`, once after `app.whenReady()`):
   ```js
   require('./zetaoffice-session').installZetaOfficeIsolation()
   ```
   Serve `dist/zetaoffice` to the webview over **http** (a tiny local server like
   the spike's, or a custom protocol) — NOT file:// (COEP/cross-origin isolation
   needs response headers). Validate on-device that `onHeadersReceived` on the
   `persist:zetaoffice` partition makes the webview cross-origin isolated.
3. **Webview preload**: expose `ipcRenderer` to the editor page so its IPC
   transport works (`ipcRenderer.sendToHost`/`on`); set `webPreferences`
   accordingly on the `<webview>`.
4. **Host (project-overview.vue)**: render
   `<webview partition="persist:zetaoffice" src="http://.../editor.html">`; build
   the bridge once with the webview executor:
   ```js
   const editor = useEditorBridge({
     libreExecutor: createWebviewEditorExecutor(this.$refs.zetaWebview),
   })
   ```
   Route `handleWpsCommand` through `editor.executeCommand(action, params)`
   (the method/event names keep the historical `wps_` prefix — rename debt, #79).
5. **IME overlay** + **perf** (load an existing 50-page docx) — separate #43 tasks.

## AI 拟人式动作原语（2026-07）

`office_thread.js` 的 EXEC 契约扩展为完整的拟人式原语集（看→找→选→改→验），
设计与协议见 **docs/AI_EDITOR_PRIMITIVES.md**：

- 感知：`get_document_text`（编号段落、分页）、`get_cursor_context`、升级版
  `find_text_locations`（锚点 + 前后文 + 所在段落，用于同词多处消歧）
- 定位（用户可见）：`set_selection`（视图跟随滚动）、`select_paragraph`、
  `collapse_selection`
- 编辑（修订默认开，boot/load 时设 `RecordChanges=true`）：`delete_selection`、
  `insert_at_cursor`/`replace_selection`（`\n` 转段落分隔）、`undo`/`redo`
- 格式：`format_selection`（粗/斜/下划线/删除线/高亮/字色/字号/字体，CJK 同步
  Asian/Complex 属性）、`set_paragraph_format`（对齐 + `Heading N` 样式）

改动类命令返回 `paragraphAfterEdit` 验证快照。后端对应 `wps_*` 新工具见
`WpsTools.java`；提示词工作流见 `prompts/system_prompt.md` §7。

全部原语已在真实 LOWA 引擎（自建 zh-CN 24.2.8）+ 无头/有头 Chrome 上端到端
验证（`editor.html?verify=1` 暴露 `window.__loExecutor` 供自动化驱动）。
