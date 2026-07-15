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
 * @param {string[]} [options.fontUrls] optional same-origin CJK fonts (one per
 *        typeface category: sans/serif/kai/fangsong); merged with fontUrl.
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
    fontUrls,
    // UI language for the LibreOffice chrome (issue #66 follow-up). The engine
    // derives its locale from navigator.languages (emscripten getEnvStrings ->
    // LANG), so the UI silently follows the BROWSER/Electron language — an
    // en-GB system got an English editor even with the zh-CN engine baked in
    // (v0.3.1 real-machine report). Forcing it makes the product deterministic.
    // Set to ''/null to follow the environment again.
    uiLang = 'zh-CN',
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
    // Files to write into the LOWA MEMFS before main() (CJK font). Each
    // { path:'/instdir/...', bytes:Uint8Array }. Fetched here (async) because
    // preRun runs synchronously; written there because /instdir merge-mounts only
    // once boot starts. (The #66 runtime zh-CN langpack injection was removed in
    // #79 — the self-built engine ships zh-CN baked in, injection was pure boot
    // overhead.)
    const injections = []

    // --- optional CJK fonts fetch (injected before fontconfig scan) ---
    // fontUrls entries are {url, family, category} (or plain URL strings, which
    // inject without contributing to the alias map — spike/?font= back-compat;
    // fontUrl merges in the same way). `family` MUST be the name LibreOffice
    // registers for the file (list_fonts diagnostic; for zh-localized name
    // tables that is the CHINESE name, e.g. 霞鹜文楷 — real-machine verified
    // both zh and en names resolve, but we pin what list_fonts reported).
    const fontList = [].concat(fontUrls || [], fontUrl ? [fontUrl] : [])
      .map((f) => (typeof f === 'string' ? { url: f } : f))
    const availableByCategory = {} // category -> registered family name
    for (let i = 0; i < fontList.length; i++) {
      const f = fontList[i]
      try {
        const r = await fetch(f.url)
        if (r.ok) {
          const bytes = new Uint8Array(await r.arrayBuffer())
          const base = String(f.url).split('?')[0].split('/').pop() || ('font-' + i)
          injections.push({ path: '/instdir/share/fonts/truetype/AAA-' + base, bytes })
          if (f.category && f.family && !availableByCategory[f.category]) availableByCategory[f.category] = f.family
          log('CJK font fetched: ' + base + ' (' + Math.round(bytes.length / 1024) + ' KB)')
        } else {
          log('CJK font not found at ' + f.url + ' (skipping)')
        }
      } catch (e) {
        log('CJK font fetch failed: ' + f.url + ' — ' + e + ' (skipping)')
      }
    }

    // --- CJK font-name aliases (fontconfig conf.d) ---
    // Real-world docx name 宋体/黑体/微软雅黑/仿宋/楷体/…; those are proprietary
    // (can't ship) and the WASM build does NO glyph fallback — every missing
    // family renders tofu even when an injected font has the glyphs (real-
    // machine verified: the same text renders once CharFontName names an
    // existing family). Map each proprietary family onto the bundled open font
    // of the SAME typeface category. Rules are `assign` (hard replace) to the
    // first category font that ACTUALLY fetched this boot — weak `append`
    // chains were real-machine tested and LOST to generic matching (every
    // category rendered sans), so the fallback logic lives HERE, not in
    // fontconfig scoring.
    const pickFamily = (...cats) => { for (const c of cats) { if (availableByCategory[c]) return availableByCategory[c] } return null }
    const CJK_ALIAS_GROUPS = [
      // 黑体类（无衬线）
      { target: pickFamily('sans'), families: [
        '黑体', 'SimHei', '黑体-简', 'Heiti SC', '华文黑体', 'STHeiti', '华文细黑', 'STXihei',
        '微软雅黑', 'Microsoft YaHei', 'Microsoft YaHei UI',
        '等线', 'DengXian', '等线 Light', 'DengXian Light',
        '思源黑体', 'Source Han Sans SC', 'Source Han Sans CN', 'Noto Sans CJK SC',
        'MS Gothic', 'Yu Gothic', 'Malgun Gothic',
      ] },
      // 宋体类（衬线）
      { target: pickFamily('serif', 'sans'), families: [
        '宋体', 'SimSun', '新宋体', 'NSimSun', '宋体-简', 'Songti SC',
        '华文宋体', 'STSong', '华文中宋', 'STZhongsong',
        '思源宋体', 'Source Han Serif SC', 'Source Han Serif CN', 'Noto Serif CJK SC',
        'MS Mincho',
      ] },
      // 楷体类
      { target: pickFamily('kai', 'serif', 'sans'), families: [
        '楷体', 'KaiTi', '楷体_GB2312', 'KaiTi_GB2312', '楷体-简', 'Kaiti SC',
        '华文楷体', 'STKaiti', 'LXGW WenKai',
      ] },
      // 仿宋类（公文常用）
      { target: pickFamily('fangsong', 'serif', 'sans'), families: [
        '仿宋', 'FangSong', '仿宋_GB2312', 'FangSong_GB2312',
        '华文仿宋', 'STFangsong', 'Zhuque Fangsong',
      ] },
    ].filter((g) => g.target)
    if (CJK_ALIAS_GROUPS.length) {
      const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      const rules = []
      let familyCount = 0
      for (const g of CJK_ALIAS_GROUPS) {
        for (const fam of g.families) {
          if (fam === g.target) continue
          familyCount++
          rules.push(
            '  <match target="pattern">\n' +
            '    <test qual="any" name="family"><string>' + esc(fam) + '</string></test>\n' +
            '    <edit name="family" mode="assign" binding="same"><string>' + esc(g.target) + '</string></edit>\n' +
            '  </match>')
        }
      }
      const conf = '<?xml version="1.0"?>\n' +
        '<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">\n' +
        '<fontconfig>\n' + rules.join('\n') + '\n</fontconfig>\n'
      injections.push({
        path: '/instdir/share/fontconfig/conf.d/69-aiworkdeck-cjk-aliases.conf',
        bytes: new TextEncoder().encode(conf),
      })
      log('CJK font-name alias conf queued (' + familyCount + ' families → ' +
        CJK_ALIAS_GROUPS.map((g) => g.target).join(' / ') + ')')
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
      // Inject the font AFTER the data package is mounted but BEFORE
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
        log('MEMFS injected ' + n + '/' + injections.length + ' file(s) (CJK font + alias conf) before main()')
      },
    }
    // Force the engine's locale (LANG) by shimming navigator.languages BEFORE
    // any engine code runs. Must happen on EVERY thread that computes env
    // strings: the page (below) AND each pthread worker (prepended into the
    // bootstrap blob, which we control). No engine rebuild needed.
    const langShim = uiLang
      ? "try{Object.defineProperty(navigator,'languages',{get:function(){return['" + uiLang + "']}});" +
        "Object.defineProperty(navigator,'language',{get:function(){return'" + uiLang + "'}})}catch(e){}"
      : ''
    if (langShim) {
      try { (0, eval)(langShim); log('UI language forced to ' + uiLang + ' (navigator shim)') }
      catch (e) { log('UI language shim failed: ' + e) }
    }
    if (sofficeBaseUrl !== '') {
      // Absolutize: this URL is imported from inside a BLOB worker, where a
      // relative/root path ('/lowa/soffice.js') is invalid — pthread spawn dies
      // with "The URL ... is invalid" and the office thread never starts.
      const absBase = new URL(sofficeBaseUrl, globalThis.location ? globalThis.location.href : undefined).href
      Module.mainScriptUrlOrBlob = new Blob(
        [langShim + "importScripts('" + absBase + "soffice.js');"], { type: 'text/javascript' })
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
