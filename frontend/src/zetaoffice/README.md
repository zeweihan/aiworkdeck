# Embedded LibreOffice editor (ZetaOffice) — assembly

Epic #43. This directory is the webview-side editor page; the host-side and
Electron pieces live elsewhere. Everything here is **dormant** — the WPS editor
still ships unchanged until the host renders the `<webview>` and selects
`EDITOR_LIBREOFFICE`.

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
   const editor = useEditorBridge(EDITOR_WPS, {
     libreExecutor: createWebviewEditorExecutor(this.$refs.zetaWebview),
   })
   ```
   Route `handleWpsCommand` through `editor.executeCommand(action, params, { wpsInstance })`
   and call `editor.setEditor(EDITOR_LIBREOFFICE)` behind a gray-rollout flag.
5. **IME overlay** + **perf** (load an existing 50-page docx) — separate #43 tasks.
