// zetaOfficeEditorEndpoint.js — the webview-side endpoint of the embedded
// LibreOffice editor. Epic #43.
//
// This is the single product entry point for "what runs INSIDE the isolated
// <webview>". It composes the three verified building blocks into one unit:
//   1. bootZetaOffice()            (#46) — boot LOWA, get the office-worker port
//   2. createLibreOfficeExecutor() (executor client) — drive UNO over that port
//   3. serveExecutor()             (#48) — expose the executor to the HOST over a
//                                          caller-supplied transport
// so the host's createRelayExecutor(...).executeCommand(action, params) reaches
// real LibreOffice across the isolation boundary.
//
// Transport-agnostic: the caller passes `transport = {send, subscribe}` (e.g.
// portTransport(port) for a MessageChannel in the spike harness, or an Electron
// <webview> IPC adapter in the product — ipcRenderer.sendToHost / ipcRenderer.on).
// No Electron dependency here, so the endpoint is harness-verifiable.
//
// DORMANT until a webview page imports it (see the build/shell plan in the Epic
// #43 thread): the page is a separate bundle because uni-app's h5 build won't let
// a static/ HTML import src/ modules — it needs its own Vite entry or to inline
// this module. That bundling + the Electron <webview partition="persist:zetaoffice">
// host wiring is the remaining on-device step; this module is its verified core.
//
// IME: user typing in the canvas is a separate concern from agent commands (the
// transparent-overlay IME layer is its own #43 task). When added, it commits
// composed text via the SAME verified path — executor.executeCommand(
// 'insert_at_cursor', {text}) — so it is not duplicated here.

import { bootZetaOffice } from './zetaOfficeBoot.js'
import { createLibreOfficeExecutor } from './libreofficeExecutorClient.js'
import { serveExecutor } from './zetaOfficeRelay.js'

/**
 * Boot ZetaOffice in `canvas` and serve its executor to the host over `transport`.
 *
 * @param {object} opts
 * @param {HTMLCanvasElement} opts.canvas REQUIRED (id="qtcanvas").
 * @param {{send:(m:any)=>void, subscribe:(h:(m:any)=>void)=>(()=>void)}} opts.transport
 *        REQUIRED host transport (e.g. portTransport(port) or an Electron IPC adapter).
 * @param {string} [opts.sofficeBaseUrl] @param {string} [opts.zetaJsUrl]
 * @param {string} [opts.workerScriptUrl] @param {string} [opts.fontUrl]
 * @param {(msg:string)=>void} [opts.onLog] @param {()=>void} [opts.onReady]
 * @returns {Promise<{executor:object, port:MessagePort, dispose:()=>void}>}
 */
export async function startEditorEndpoint(opts = {}) {
  const { canvas, transport, onLog, onReady, ...bootOpts } = opts
  if (!canvas) throw new Error('startEditorEndpoint: canvas is required')
  if (!transport || typeof transport.send !== 'function' || typeof transport.subscribe !== 'function') {
    throw new Error('startEditorEndpoint: transport {send, subscribe} is required')
  }

  const { port, dispose: disposeBoot } = await bootZetaOffice({ canvas, onLog, onReady, ...bootOpts })

  const executor = createLibreOfficeExecutor({ onError: onLog })
  executor.connect(port)

  const served = serveExecutor({ executor, send: transport.send, subscribe: transport.subscribe })

  return {
    executor,
    port,
    dispose() { served.dispose(); disposeBoot() },
  }
}
