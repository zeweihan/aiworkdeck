// zetaoffice-verify.js — dedicated "LibreOffice 验证" window for a packaged
// build. Epic #43. Lets the maintainer install and SEE the embedded LibreOffice
// editor boot + render Chinese + run an AI command (redline) inside the real app,
// WITHOUT touching the WPS document flow (zero blast radius on the shipping
// product). Triggered by a global shortcut (registered in main.js).
//
// LOWA needs cross-origin isolation (SharedArrayBuffer). The main window can't be
// isolated (webSecurity:false + cross-origin WPS/AI), so this opens a SEPARATE
// BrowserWindow on a dedicated partition whose session gets COOP/COEP injected
// (desktop/main/zetaoffice-session.js, #47). The page + LOWA are served by the
// shared local server (desktop/main/zetaoffice-server.js) — same-origin LOWA
// proxy so COEP require-corp is satisfied with no CORP gymnastics.
//
// Dormant until the shortcut fires; nothing imports it at startup.

const { BrowserWindow } = require('electron')
const { installZetaOfficeIsolation, ZETAOFFICE_PARTITION } = require('./zetaoffice-session')
const { startEditorServer, editorUrl } = require('./zetaoffice-server')

let verifyWin = null

/**
 * Open (or focus) the LibreOffice verification window.
 */
async function openZetaOfficeVerifyWindow() {
  if (verifyWin && !verifyWin.isDestroyed()) { verifyWin.focus(); return verifyWin }

  installZetaOfficeIsolation(ZETAOFFICE_PARTITION)
  const { origin } = await startEditorServer()

  verifyWin = new BrowserWindow({
    width: 1280,
    height: 860,
    title: 'AI WorkDeck · LibreOffice 验证 (experimental)',
    webPreferences: {
      partition: ZETAOFFICE_PARTITION,
      contextIsolation: true,
      nodeIntegration: false,
    },
  })
  verifyWin.on('closed', () => { verifyWin = null })
  // Standalone verify panel drives the booted executor directly (no host).
  // 捕获 loadURL 失败，避免成为未处理 rejection
  verifyWin.loadURL(editorUrl(origin, { verify: true })).catch((e) => {
    console.error('[zetaoffice-verify] loadURL failed', e && e.message ? e.message : e)
  })
  verifyWin.webContents.openDevTools({ mode: 'detach' })
  return verifyWin
}

module.exports = { openZetaOfficeVerifyWindow }
