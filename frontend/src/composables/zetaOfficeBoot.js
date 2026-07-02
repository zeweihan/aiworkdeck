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
    // Optional zh-CN UI langpack manifest (issue #66). Same-origin URL to a
    // manifest.json ({ files: ["program/resource/zh_CN/LC_MESSAGES/sw.mo", ...,
    // "share/registry/Langpack-zh-CN.xcd"] }, paths relative to /instdir). The
    // files are fetched before boot and written into MEMFS in preRun so the
    // LibreOffice UI comes up in Chinese. Missing/failed fetch -> skip -> English.
    langpackUrl,
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
    // Files to write into the LOWA MEMFS in preRun (CJK font + zh-CN langpack).
    // Each { path:'/instdir/...', bytes:Uint8Array }. Fetched here (async) because
    // preRun runs synchronously; written there because /instdir merge-mounts only
    // once boot starts.
    const injections = []

    // --- optional CJK font fetch (injected before fontconfig scan) ---
    if (fontUrl) {
      try {
        const r = await fetch(fontUrl)
        if (r.ok) {
          const cjkBytes = new Uint8Array(await r.arrayBuffer())
          injections.push({ path: '/instdir/share/fonts/truetype/AAA-CJK.ttc', bytes: cjkBytes })
          log('CJK font fetched (' + Math.round(cjkBytes.length / 1024) + ' KB), will inject before boot')
        } else {
          log('CJK font not found at ' + fontUrl + ' (skipping; CJK will be tofu)')
        }
      } catch (e) {
        log('CJK font fetch failed: ' + e + ' (skipping)')
      }
    }

    // --- optional zh-CN UI langpack fetch (#66): manifest + .mo/.xcd, written
    // into /instdir so LibreOffice loads a Chinese UI. Same merge-mount trick as
    // the font. Any failure degrades cleanly to the English UI. ---
    if (langpackUrl) {
      try {
        const baseUrl = langpackUrl.replace(/[^/]*$/, '') // dir of the manifest
        const mr = await fetch(langpackUrl)
        if (!mr.ok) throw new Error('manifest HTTP ' + mr.status)
        const manifest = await mr.json()
        const files = (manifest && manifest.files) || []
        const fetched = await Promise.all(files.map(async (rel) => {
          const fr = await fetch(baseUrl + rel)
          if (!fr.ok) throw new Error(rel + ' HTTP ' + fr.status)
          return { path: '/instdir/' + rel, bytes: new Uint8Array(await fr.arrayBuffer()) }
        }))
        for (const f of fetched) injections.push(f)
        log('zh-CN langpack fetched (' + fetched.length + ' files), will inject before boot')
      } catch (e) {
        log('zh-CN langpack fetch failed: ' + (e && e.message ? e.message : e) + ' (skipping; UI stays English)')
      }
    }

    // The globals `canvas` and `Module` must exist before soffice.js loads.
    const Module = {
      canvas,
      uno_scripts: [zetaJsUrl, workerScriptUrl],
      locateFile: function (path, prefix) { return (prefix || sofficeBaseUrl) + path },
      // ALWAYS an array: LOWA's soffice.js prologue does `if(!("preRun" in
      // Module))Module["preRun"]=[]; Module.preRun.push(...)` — a present-but-
      // undefined preRun key crashes the engine head with "Cannot read
      // properties of undefined (reading 'push')" and the boot never starts.
      preRun: [],
      // Inject font/langpack AFTER the data package is mounted but BEFORE
      // LibreOffice main() runs (so fontconfig's startup scan still sees the
      // font). This CANNOT be a preRun hook: preRun runs before the package
      // loader processes soffice.data, and any injected path that ALSO exists in
      // the package (e.g. a self-built engine with the zh-CN langpack baked in,
      // issue #66) makes the loader's FS_createDataFile throw EEXIST — the
      // preload stalls forever and the editor never boots. At
      // onRuntimeInitialized the package files are in MEMFS and FS.writeFile
      // simply overwrites content, so baked-in engines and runtime injection
      // coexist.
      onRuntimeInitialized: function () {
        if (!injections.length) return
        const FS = globalThis.FS
        const mkdirp = (dir) => {
          const parts = dir.split('/').filter(Boolean)
          let cur = ''
          for (let i = 0; i < parts.length; i++) { cur += '/' + parts[i]; try { FS.mkdir(cur) } catch (e) { /* exists */ } }
        }
        let n = 0
        for (const f of injections) {
          try {
            mkdirp(f.path.replace(/\/[^/]*$/, ''))
            FS.writeFile(f.path, f.bytes)
            n++
          } catch (e) { log('FS write failed for ' + f.path + ': ' + e) }
        }
        log('MEMFS injected ' + n + '/' + injections.length + ' file(s) (font + zh-CN langpack) before main()')
      },
    }
    if (sofficeBaseUrl !== '') {
      // Absolutize: this URL is imported from inside a BLOB worker, where a
      // relative/root path ('/lowa/soffice.js') is invalid — pthread spawn dies
      // with "The URL ... is invalid" and the office thread never starts.
      const absBase = new URL(sofficeBaseUrl, globalThis.location ? globalThis.location.href : undefined).href
      Module.mainScriptUrlOrBlob = new Blob(
        ["importScripts('" + absBase + "soffice.js');"], { type: 'text/javascript' })
    }
    globalThis.Module = Module

    function onMessage(e) {
      const d = (e && e.data) || {}
      if (d.cmd === 'log') log(d.msg)
      else if (d.cmd === 'ui_ready') {
        // Reveal the canvas (CSS keeps it hidden during boot to avoid showing a
        // blank/garbage surface) and kick one repaint. The spike's page did this
        // in its own ui_ready handler; the boot-module extraction (#46) must own
        // it so every consumer gets a visible, painted canvas.
        try { canvas.style.visibility = 'visible'; globalThis.dispatchEvent(new Event('resize')) } catch (err) { /* ignore */ }
        log('UI ready')
        if (onReady) onReady()
      }
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
