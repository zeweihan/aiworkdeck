// zetaoffice-session.js — Electron cross-origin-isolation for the embedded
// LibreOffice (ZetaOffice / LOWA) editor. Epic #43.
//
// WHY a dedicated partition (not the main window):
// LOWA uses pthreads -> SharedArrayBuffer, which the browser only exposes when
// the document is cross-origin isolated (COOP:same-origin + COEP:require-corp).
// But a SUBFRAME can be cross-origin isolated ONLY IF the top-level document is
// too — and the product main window deliberately is NOT isolated: it runs with
// webSecurity:false and loads cross-origin subresources (WPS SDK + cookies, AI
// providers). Forcing COOP/COEP on the main window would break those.
//
// Therefore ZetaOffice cannot boot "in place" in the main window. It must live
// in an Electron <webview> on its OWN partition/session — a separate frame tree
// that can be isolated independently of the host. This helper installs COOP/COEP
// on THAT partition's session only, so the main app's cross-origin loads are
// untouched. The header-injection mechanism is the one proven in the spike's
// standalone launcher (experiments/zetaoffice-spike/electron-main.js), scoped
// from defaultSession to a named partition.
//
// DORMANT: nothing in main.js calls this yet (the WPS editor still ships). The
// LibreOffice editor is behind useEditorBridge's EDITOR_LIBREOFFICE switch.
//
// ACTIVATION (real-machine step):
//   1. In main.js after app.whenReady(), once:
//        require('./zetaoffice-session').installZetaOfficeIsolation()
//   2. Render the editor inside <webview partition="persist:zetaoffice"
//        src="...zetaoffice-editor..."> — it boots ZetaOffice via the product
//        bootZetaOffice() and is cross-origin isolated thanks to this helper.
//   3. The self-hosted LOWA bundle (soffice.{js,wasm,data}) + baked CJK fonts
//      ship in extraResources; the webview loads them same-origin so COEP's
//      require-corp is satisfied. (Until self-hosted, LOWA loads from the CDN,
//      which serves Cross-Origin-Resource-Policy: cross-origin — also fine.)
//
// OPEN (decide on the real machine — needs Electron): how the host (main window)
// reaches the worker that booted inside the isolated webview. Two candidates:
//   (a) per-command relay: host postMessage -> webview -> executor.executeCommand
//       -> postMessage result back. Simple, an extra hop per command.
//   (b) one-time MessagePort transfer: the webview transfers the office-worker
//       thread port to the host (postMessage(port,[port])); the host then
//       connects libreofficeExecutorClient to it directly — IF a MessagePort
//       transfers across the isolated<->non-isolated boundary in Electron
//       (our protocol is plain JSON, no SharedArrayBuffer, so it should). (b) is
//       cleaner but must be validated on the device before we commit.

const { session } = require('electron')

const ZETAOFFICE_PARTITION = 'persist:zetaoffice'

/**
 * Install COOP/COEP on the ZetaOffice webview partition so a <webview> using it
 * becomes cross-origin isolated (SharedArrayBuffer available) WITHOUT touching
 * the main window's session. Idempotent-safe to call once at startup.
 *
 * @param {string} [partition=ZETAOFFICE_PARTITION] the webview partition name.
 * @returns {string} the partition name (use it as the <webview partition>).
 */
function installZetaOfficeIsolation(partition = ZETAOFFICE_PARTITION) {
  const ses = session.fromPartition(partition)
  ses.webRequest.onHeadersReceived((details, callback) => {
    const headers = Object.assign({}, details.responseHeaders)
    headers['Cross-Origin-Opener-Policy'] = ['same-origin']
    headers['Cross-Origin-Embedder-Policy'] = ['require-corp']
    headers['Cross-Origin-Resource-Policy'] = ['cross-origin']
    callback({ responseHeaders: headers })
  })
  return partition
}

module.exports = { installZetaOfficeIsolation, ZETAOFFICE_PARTITION }
