// zetaOfficeBoot.js — framework-agnostic ZetaOffice (LibreOffice WASM) boot core.
//
// Epic #43. Extracted verbatim (parameterized) from the proven Phase 0 spike
// boot sequence (experiments/zetaoffice-spike/index.html). Plain ES module (NO
// Vue) so BOTH the spike harness (loaded via the dev server's /shared/ route,
// driving product code against a real LibreOffice) AND the app's editor
// component import the SAME boot code. This is the heart of "embed ZetaOffice in
// the app": it sets up the emscripten Module, loads the LOWA runtime, injects
// CJK fonts before fontconfig's startup scan, and resolves the office-worker
// thread port that useEditorBridge.connectLibreOffice(port) / the executor
// client connect to.
//
// Environment-specific values are parameters, NOT hardcoded, so the spike passes
// CDN + local paths and the product passes self-hosted-bundle paths:
//   - sofficeBaseUrl : where soffice.{js,wasm,data} live (CDN for the spike;
//                      a self-hosted bundle inside the installer for the product)
//   - zetaJsUrl      : the zetajs bridge — NOT on the CDN, must be vendored
//                      (allotropia/zetajs source/zeta.js, MIT). Loading it from
//                      the CDN fails with a worker importScripts NetworkError.
//   - workerScriptUrl: the office worker (office_thread.js) injected into the
//                      LO office thread via Module.uno_scripts.
//   - fontUrl        : optional CJK font fetched same-origin and written into the
//                      LOWA font dir in preRun (the product bakes Noto/思源 CJK
//                      OFL into the self-hosted bundle's font dir at build time).
//
// COOP/COEP: the page MUST be cross-origin isolated (SharedArrayBuffer). That
// comes ONLY from HTTP response headers (spike: serve.mjs; product Electron:
// session.webRequest.onHeadersReceived on a dedicated partition). boot() throws
// early with a clear message if it is not.

const DEFAULT_SOFFICE_BASE_URL = 'https://cdn.zetaoffice.net/zetaoffice_latest/'

/**
 * Boot ZetaOffice into the given canvas and resolve with the office-worker
 * thread port (Module.uno_main's value). Connect an executor / the editor bridge
 * to the returned port to drive UNO commands.
 *
 * @param {object} options
 * @param {HTMLCanvasElement} options.canvas REQUIRED. MUST have id="qtcanvas"
 *        and no border/padding (Qt requirement; outline is fine).
 * @param {string}   [options.sofficeBaseUrl] LOWA runtime base URL.
 * @param {string}   [options.zetaJsUrl='./zeta.js'] vendored zetajs bridge URL.
 * @param {string}   [options.workerScriptUrl='./office_thread.js'] office worker URL.
 * @param {string}   [options.fontUrl] optional same-origin CJK font to inject.
 * @param {(msg:string)=>void}     [options.onLog] worker 'log' lines + milestones.
 * @param {()=>void}                [options.onReady] fired on worker 'ui_ready'
 *        (document loaded, canvas interactive).
 * @param {(data:any)=>void}        [options.onWorkerMessage] raw worker messages.
 * @returns {Promise<{port: MessagePort, dispose: ()=>void}>}
 */
export function bootZetaOffice(options = {}) {
  const {
    canvas,
    sofficeBaseUrl = DEFAULT_SOFFICE_BASE_URL,
    zetaJsUrl = './zeta.js',
    workerScriptUrl = './office_thread.js',
    fontUrl,
    onLog,
    onReady,
    onWorkerMessage,
  } = options

  const log = (m) => { if (onLog) onLog(m) }

  if (!canvas) return Promise.reject(new Error('bootZetaOffice: canvas is required'))
  if (typeof self !== 'undefined' && self.crossOriginIsolated === false) {
    return Promise.reject(new Error(
      'bootZetaOffice: page is not cross-origin isolated (SharedArrayBuffer unavailable). ' +
      'Serve with COOP:same-origin + COEP:require-corp headers ' +
      '(spike: node serve.mjs; product: Electron onHeadersReceived).'))
  }

  return new Promise(async (resolve, reject) => {
    // --- optional CJK font fetch (injected in preRun, before fontconfig scan) ---
    let cjkBytes = null
    if (fontUrl) {
      try {
        const r = await fetch(fontUrl)
        if (r.ok) {
          cjkBytes = new Uint8Array(await r.arrayBuffer())
          log('CJK font fetched (' + Math.round(cjkBytes.length / 1024) + ' KB), will inject before boot')
        } else {
          log('CJK font not found at ' + fontUrl + ' (skipping; CJK will be tofu)')
        }
      } catch (e) {
        log('CJK font fetch failed: ' + e + ' (skipping)')
      }
    }

    // The globals `canvas` and `Module` must exist before soffice.js loads.
    const Module = {
      canvas,
      uno_scripts: [zetaJsUrl, workerScriptUrl],
      locateFile: function (path, prefix) { return (prefix || sofficeBaseUrl) + path },
      preRun: cjkBytes ? [function () {
        // Runs before LibreOffice init. /instdir is NOT mounted yet at this point
        // (FS / = tmp,home,dev,proc) but creating the dir tree and writing the
        // font here WORKS: the LOWA data mount MERGES into MEMFS rather than
        // replacing, so the font is present when fontconfig scans at startup.
        // PROVEN in the spike: tofu (口口) -> real Chinese glyphs. The product
        // bakes the font into the self-hosted LOWA bundle's font dir at build time.
        const FS = globalThis.FS
        const dir = '/instdir/share/fonts/truetype'
        const parts = dir.split('/').filter(Boolean)
        let cur = ''
        for (let i = 0; i < parts.length; i++) { cur += '/' + parts[i]; try { FS.mkdir(cur) } catch (e) { /* exists */ } }
        try { FS.writeFile(dir + '/AAA-CJK.ttc', cjkBytes); log('CJK font written to ' + dir + ' (before fontconfig scan)') }
        catch (e) { log('CJK font write to FS failed: ' + e) }
      }] : undefined,
    }
    if (sofficeBaseUrl !== '') {
      Module.mainScriptUrlOrBlob = new Blob(
        ["importScripts('" + sofficeBaseUrl + "soffice.js');"], { type: 'text/javascript' })
    }
    globalThis.Module = Module

    function onMessage(e) {
      const d = (e && e.data) || {}
      if (d.cmd === 'log') log(d.msg)
      else if (d.cmd === 'ui_ready') { log('UI ready'); if (onReady) onReady() }
      if (onWorkerMessage) onWorkerMessage(d)
    }

    // Keep the embedded Qt window sized to the canvas.
    const resizeTimer = setInterval(function () {
      try { globalThis.dispatchEvent(new Event('resize')) } catch (e) { /* ignore */ }
    }, 1000)

    const dispose = () => { clearInterval(resizeTimer) }

    const s = document.createElement('script')
    s.src = sofficeBaseUrl + 'soffice.js'
    s.onerror = () => { dispose(); reject(new Error('failed to load soffice.js from ' + sofficeBaseUrl)) }
    // On the MAIN thread the office-thread port is Module.uno_main (NOT
    // Module.zetajs, which exists only inside the office worker) and is available
    // only after soffice.js has run — so wire it in onload. (Verified against the
    // allotropia/zetajs web-office example.)
    s.onload = function () {
      log('soffice.js loaded — initializing office thread…')
      Module.uno_main.then(function (port) {
        port.onmessage = onMessage
        log('thread port ready')
        resolve({ port, dispose })
      }, function (err) { dispose(); reject(new Error('uno_main rejected: ' + err)) })
    }
    document.body.appendChild(s)
  })
}
