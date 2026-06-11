const path = require('path')
const { app, BrowserWindow, BrowserView, ipcMain, shell, desktopCapturer, screen, clipboard, Menu, globalShortcut } = require('electron')
const { BackendManager } = require('./backend')
const { initLocalFileService } = require('./file-service')


const DEV_SERVER_URL = process.env.CHECKBA_DEV_SERVER_URL || 'http://localhost:5173'
const IS_DEV = process.env.CHECKBA_DESKTOP_DEV === '1'

function escapeHtml(s) {
  return String(s || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}

/** @type {BrowserWindow | null} */
let mainWindow = null
let backend = null

/** @type {Map<string, BrowserView>} */
const views = new Map()

/** @type {Map<string, {x:number,y:number,width:number,height:number}>} */
const viewBounds = new Map()

/** @type {Set<string>} */
const attachedViewIds = new Set()

// 渲染层期望 BrowserView 是否可见（用于避免弹窗/全屏切换导致 onHide/onShow 未触发时“卡死黑屏”）
let viewsVisibleDesired = true

let clipboardWatchTimer = null
let lastClipboardText = ''

/** @type {BrowserWindow | null} */
let ocrSelectWin = null
let ocrSelectWinBound = false
let ocrEscShortcutBound = false

function emitClipboard(text, source) {
  try {
    const t = String(text || '').trim()
    if (!t) return
    if (mainWindow) {
      mainWindow.webContents.send('checkba:clipboard-copied', {
        type: 'TEXT',
        text: t,
        ts: Date.now(),
        source: source || 'system'
      })
    }
  } catch (e) {
    // ignore
  }
}

function closeOcrSelectWin() {
  if (!ocrSelectWin) return
  try {
    if (!ocrSelectWin.isDestroyed()) ocrSelectWin.close()
  } catch (e) {
    // ignore
  }
  ocrSelectWin = null
  ocrSelectWinBound = false
  // 兜底：关闭覆盖窗后解除全局 ESC（避免影响正常使用）
  try {
    if (ocrEscShortcutBound) {
      globalShortcut.unregister('Escape')
      ocrEscShortcutBound = false
    }
  } catch (e) {
    // ignore
  }
}

function restoreViewsVisibility() {
  try {
    setAllViewsVisible(viewsVisibleDesired)
  } catch (e) {
    // ignore
  }
  try {
    layoutAllViews()
  } catch (e) {
    // ignore
  }
}

// 记录上一次剪贴板内容指纹，防止重复推送
let lastClipboardFingerprint = ''

function startClipboardWatcher() {
  if (clipboardWatchTimer) return
  // Init fingerprint
  lastClipboardFingerprint = (clipboard.readText() || '')

  // 系统级：轮询剪贴板内容变化
  clipboardWatchTimer = setInterval(() => {
    try {
      const formats = clipboard.availableFormats()

      const hasImage = formats.some(f => f.includes('image'))
      const hasText = formats.includes('text/plain')

      // LOG formats for debugging
      // console.log('[Clipboard] Formats:', formats) 

      // Relaxed: if hasImage, try it.
      if (hasImage) {
        const img = clipboard.readImage()
        if (img && !img.isEmpty()) {
          const dataUrl = img.toDataURL()
          const fingerprint = 'IMG_' + dataUrl.length + '_' + dataUrl.slice(0, 50)
          if (fingerprint !== lastClipboardFingerprint) {
            console.log('[Clipboard] Image detected, size:', dataUrl.length)
            lastClipboardFingerprint = fingerprint
            if (mainWindow) {
              mainWindow.webContents.send('checkba:clipboard-copied', {
                type: 'IMAGE',
                data: dataUrl,
                ts: Date.now(),
                source: 'system'
              })
            }
          }
          // Return if image handled? 
          // If user copied "Mixed Content", we prefer Image.
          return
        }
      }


      // 2. 检查文件 (File) - macOS public.file-url
      // 暂时仅支持单文件路径读取，需根据操作系统适配
      // user requested: "other files"
      // Electron clipboard usually has 'public.file-url' on Mac
      if (process.platform === 'darwin' && formats.includes('public.file-url')) {
        const filePath = clipboard.read('public.file-url')
        if (filePath) {
          // filePath gets returned as file:// URL usually, need to decode
          let cleanPath = filePath
          try { cleanPath = decodeURIComponent(filePath.replace('file://', '')) } catch (e) { }

          const fingerprint = 'FILE_' + cleanPath
          if (fingerprint !== lastClipboardFingerprint) {
            lastClipboardFingerprint = fingerprint
            if (mainWindow) {
              mainWindow.webContents.send('checkba:clipboard-copied', {
                type: 'FILE',
                filePath: cleanPath, // Front-end needs to read this file or we read it here?
                // Browser/Renderer cannot read arbitrary file path easily without user interaction or enabling nodeIntegration (which we have disabled/isolated)
                // But we can read it here in Main and send buffer? Or simply notify frontend to trigger a logic?
                // Better: Send event, and let frontend decide. 
                // Since frontend is remote (or local server), it can't read local path `filePath` if it is a browser.
                // But here we are in Electron. 
                // Solution: Send 'FILE' type with `filePath`. Frontend `onCopied` will receive it.
                // But frontend `project-overview.vue` runs in Renderer. 
                // If we want to upload, we need the file data.
                // Let's read file here and send as Blob/Buffer? No, too big.
                // Let's send `filePath` and let Frontend invoke `checkbaDesktop.fs.readFile`?
                // We don't have `checkbaDesktop.fs`.
                // We can add `checkbaDesktop.clipboard.readFile(path)`?
                // Or just read tiny files here?
                // For now, let's just send the path. The user requirement is "record OTHER FILES". 
                // If we just record the path text, that's not "recording the file".
                // Let's try to send basic meta first.
                ts: Date.now(),
                source: 'system'
              })
            }
          }
          return
        }
      }

      // 3. 文本 (Text)
      if (hasText) {
        const t = clipboard.readText() || ''
        const tt = String(t || '')
        if (!tt) return
        const fingerprint = 'TXT_' + tt
        if (fingerprint !== lastClipboardFingerprint) {
          lastClipboardFingerprint = fingerprint
          emitClipboard(tt, 'system') // reuse emitClipboard for text to keep compat
        }
      }

    } catch (e) {
      // ignore
    }
  }, 1000) // Increase interval to 1s to save CPU on image processing
}

function stopClipboardWatcher() {
  if (!clipboardWatchTimer) return
  clearInterval(clipboardWatchTimer)
  clipboardWatchTimer = null
}

function attachCopyListener(webContents, sourceLabel) {
  if (!webContents || webContents.__checkbaCopyBound) return
  webContents.__checkbaCopyBound = true
  webContents.on('before-input-event', (_event, input) => {
    try {
      if (!input || input.type !== 'keyDown') return
      const key = (input.key || '').toLowerCase()
      const isCopy = key === 'c' && (input.control || input.meta)
      const isCut = key === 'x' && (input.control || input.meta)
      if (!isCopy && !isCut) return
      // 等系统完成 copy/cut 后再读剪贴板（避免读取到旧值）
      setTimeout(() => {
        try {
          const text = clipboard.readText() || ''
          emitClipboard(text, sourceLabel || (isCopy ? 'copy' : 'cut'))
        } catch (e) {
          // ignore
        }
      }, 40)
    } catch (e) {
      // ignore
    }
  })
}

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    icon: path.join(__dirname, '../../frontend/src/static/icon.png'),
    webPreferences: {
      preload: path.join(__dirname, '../preload/preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // 允许跨域 Cookie（解决 WPS SameSite Cookie 被拦截导致 500 错误）
      webSecurity: false
    }
  })

  // UI：直接复用现有 frontend（开发态用 dev server）
  if (IS_DEV) {
    mainWindow.loadURL(DEV_SERVER_URL)
    mainWindow.webContents.openDevTools({ mode: 'detach' })
  } else {
    // Production Mode: Load from dist
    // (packaged builds carry the frontend via electron-builder extraResources)
    const distPath = app.isPackaged
      ? path.join(process.resourcesPath, 'frontend/dist/build/h5/index.html')
      : path.join(__dirname, '../../frontend/dist/build/h5/index.html')
    mainWindow.loadFile(distPath)
  }

  // 拦截渲染进程里的 window.open（包括 WPS iframe 点击超链接）
  // - 内部协议 checkba://... => 交给渲染层打开“网核中心定位”
  // - 其它 http(s) => 走工作区浏览器新 tab
  try {
    mainWindow.webContents.setWindowOpenHandler(({ url }) => {
      const u = String(url || '')
      if (u.startsWith('checkba:')) {
        try {
          mainWindow.webContents.send('checkba:app-open-internal', { url: u })
        } catch (e) {
          // ignore
        }
        return { action: 'deny' }
      }
      if (/^https?:\/\//i.test(u)) {
        try {
          mainWindow.webContents.send('checkba:browser-open-new-tab', { id: 'renderer', url: u })
        } catch (e) {
          // ignore
        }
        return { action: 'deny' }
      }
      return { action: 'allow' }
    })
    mainWindow.webContents.on('will-navigate', (event, url) => {
      const u = String(url || '')
      if (u.startsWith('checkba:')) {
        event.preventDefault()
        try {
          mainWindow.webContents.send('checkba:app-open-internal', { url: u })
        } catch (e) {
          // ignore
        }
      }
    })
  } catch (e) {
    // ignore
  }

  mainWindow.on('closed', () => {
    mainWindow = null
  })

  mainWindow.on('resize', () => layoutAllViews())
  mainWindow.on('maximize', () => layoutAllViews())
  mainWindow.on('unmaximize', () => layoutAllViews())
  // 全屏切换在 macOS 上不会总触发 resize/maximize：这里补齐，避免 BrowserView bounds 不同步
  mainWindow.on('enter-full-screen', () => {
    layoutAllViews()
    // OCR 覆盖窗跟随（全屏时 bounds 会变化）
    syncOcrSelectWinBounds()
  })
  mainWindow.on('leave-full-screen', () => {
    layoutAllViews()
    syncOcrSelectWinBounds()
  })
  // 某些情况下（弹窗/空间切换）会出现 BrowserView 未重新 attach 导致“黑屏”，focus 时兜底恢复
  mainWindow.on('focus', () => {
    restoreViewsVisibility()
  })

  // 监听渲染层内的 copy/cut（WPS/页面内复制等），统一推送给前端入库
  attachCopyListener(mainWindow.webContents, 'renderer')

  // Handle file downloads: ensure "Safe As" dialog appears
  mainWindow.webContents.session.on('will-download', (event, item, webContents) => {
    // Set options for the save dialog
    item.setSaveDialogOptions({
      title: '保存文件',
      defaultPath: item.getFilename() // Use the default filename suggestion
    })
    // Note: If item.setSavePath() is NOT called, Electron implicitly shows the dialog 
    // (unless global "Always ask..." is disabled, but setSaveDialogOptions helps hint it).
    // To strictly FORCE it, we would need to check existing configuration, but usually this is enough.
  })

  // [Fix WPS 500 Error] Force Accept-Language to zh-CN for WPS domains
  const wpsFilter = {
    urls: [
      '*://*.wps.cn/*',
      '*://*.wps.com/*',
      '*://*.wpsgo.com/*',
      '*://*.wpscdn.cn/*',
      '*://*.kingsoft.com/*'
    ]
  }
  mainWindow.webContents.session.webRequest.onBeforeSendHeaders(wpsFilter, (details, callback) => {
    // console.log('[WPS Fix] Modifying headers for:', details.url)
    details.requestHeaders['Accept-Language'] = 'zh-CN,zh;q=0.9'
    callback({ requestHeaders: details.requestHeaders })
  })

  startClipboardWatcher()
}

function syncOcrSelectWinBounds() {
  if (!mainWindow || !ocrSelectWin) return
  try {
    const b = mainWindow.getContentBounds()
    ocrSelectWin.setBounds(b, false)
  } catch (e) {
    // ignore
  }
}

ipcMain.handle('checkba:ocr-start-selection', async (_evt, payload) => {
  if (!mainWindow) return { ok: false, message: 'window not ready' }
  const viewId = payload && payload.viewId ? String(payload.viewId) : ''
  const mode = payload && payload.mode ? String(payload.mode) : ''
  const useWindow = mode === 'window' || !viewId
  const vb = !useWindow && viewId ? (viewBounds.get(viewId) || null) : null
  const view = !useWindow && viewId ? views.get(viewId) : null
  // window 模式：允许在任意内容上框选（包括两边都是文档）
  if (!useWindow && (!viewId || !vb || !view)) return { ok: false, message: 'view not found' }

  // 创建（或复用）透明覆盖窗：始终在最上层，用于框选区域（不隐藏 BrowserView）
  closeOcrSelectWin()
  const contentBounds = mainWindow.getContentBounds()
  const reqId = `ocrsel_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  const resultChannel = `checkba:ocr-selection-done:${reqId}`

  const html = `<!doctype html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <style>
    html, body { margin:0; padding:0; width:100%; height:100%; background: transparent; cursor: crosshair; user-select:none; }
    .layer { position: fixed; inset: 0; }
    .hint { position: fixed; left: 14px; top: 14px; padding: 6px 10px; background: rgba(255,255,255,0.88); border: 1px solid rgba(224,224,224,0.7); border-radius: 10px; font-size: 12px; color:#12344D; }
    .shade { position: fixed; inset: 0; background: rgba(0,0,0,0.12); }
    .rect { position: fixed; border: 2px solid rgba(37,99,235,0.85); background: rgba(37,99,235,0.12); border-radius: 6px; pointer-events:none; display:none; }
  </style>
</head>
<body>
  <div class="layer">
    <div class="shade"></div>
    <div class="hint">拖动框选网页区域 · ESC 退出</div>
    <div id="rect" class="rect"></div>
  </div>
  <script>
    const { ipcRenderer } = require('electron');
    const ch = ${JSON.stringify(resultChannel)};
    const limitToView = ${JSON.stringify(!useWindow)};
    const viewBounds = ${JSON.stringify(useWindow ? { x: 0, y: 0, width: 0, height: 0 } : { x: vb.x, y: vb.y, width: vb.width, height: vb.height })};
    const rectEl = document.getElementById('rect');
    let down = null;
    const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
    const inView = (x, y) => !limitToView || (x >= viewBounds.x && x <= (viewBounds.x + viewBounds.width) && y >= viewBounds.y && y <= (viewBounds.y + viewBounds.height));
    window.addEventListener('mousedown', (e) => {
      if (e.button !== 0) return;
      const x = e.clientX, y = e.clientY;
      // BrowserView 模式：只允许在其区域开始框选；window 模式：全局允许
      if (!inView(x, y)) return;
      down = { x, y };
      rectEl.style.display = 'block';
      rectEl.style.left = x + 'px';
      rectEl.style.top = y + 'px';
      rectEl.style.width = '0px';
      rectEl.style.height = '0px';
      e.preventDefault();
    }, true);
    window.addEventListener('mousemove', (e) => {
      if (!down) return;
      const x2 = limitToView ? clamp(e.clientX, viewBounds.x, viewBounds.x + viewBounds.width) : e.clientX;
      const y2 = limitToView ? clamp(e.clientY, viewBounds.y, viewBounds.y + viewBounds.height) : e.clientY;
      const left = Math.min(down.x, x2);
      const top = Math.min(down.y, y2);
      const w = Math.abs(x2 - down.x);
      const h = Math.abs(y2 - down.y);
      rectEl.style.left = left + 'px';
      rectEl.style.top = top + 'px';
      rectEl.style.width = w + 'px';
      rectEl.style.height = h + 'px';
      e.preventDefault();
    }, true);
    window.addEventListener('mouseup', (e) => {
      if (!down) return;
      const x2 = limitToView ? clamp(e.clientX, viewBounds.x, viewBounds.x + viewBounds.width) : e.clientX;
      const y2 = limitToView ? clamp(e.clientY, viewBounds.y, viewBounds.y + viewBounds.height) : e.clientY;
      const x1 = down.x, y1 = down.y;
      down = null;
      const left = Math.min(x1, x2);
      const top = Math.min(y1, y2);
      const w = Math.abs(x2 - x1);
      const h = Math.abs(y2 - y1);
      if (w < 6 || h < 6) {
        // 视为取消
        ipcRenderer.send(ch, { ok: false, cancelled: true });
        return;
      }
      ipcRenderer.send(ch, { ok: true, selection: { x1, y1, x2, y2 } });
      e.preventDefault();
    }, true);
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        ipcRenderer.send(ch, { ok: false, cancelled: true });
      }
    });
  </script>
</body>
</html>`

  ocrSelectWin = new BrowserWindow({
    x: contentBounds.x,
    y: contentBounds.y,
    width: contentBounds.width,
    height: contentBounds.height,
    parent: mainWindow,
    modal: false,
    show: false,
    frame: false,
    resizable: false,
    transparent: true,
    alwaysOnTop: true,
    hasShadow: false,
    backgroundColor: '#00000000',
    skipTaskbar: true,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false
    }
  })

  // macOS 全屏：确保覆盖窗出现在同一 Space，且不触发新建“全屏窗口”
  try {
    if (process.platform === 'darwin' && ocrSelectWin) {
      ocrSelectWin.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
      ocrSelectWin.setFullScreenable(false)
      // screen-saver 层级在全屏下可能导致无法聚焦/难以关闭；这里降级为 floating，更接近 IDE 体验
      ocrSelectWin.setAlwaysOnTop(true, 'floating')
    }
  } catch (e) {
    // ignore
  }

  // 兜底：覆盖窗期间全局 Esc 强制关闭（解决“覆盖窗置顶但拿不到焦点/关不掉”）
  try {
    if (!ocrEscShortcutBound) {
      const ok = globalShortcut.register('Escape', () => {
        try {
          if (ocrSelectWin && !ocrSelectWin.isDestroyed()) {
            ocrSelectWin.close()
          }
        } catch (e) {
          // ignore
        }
      })
      ocrEscShortcutBound = !!ok
    }
  } catch (e) {
    // ignore
  }

  // 跟随主窗口变化
  if (!ocrSelectWinBound) {
    ocrSelectWinBound = true
    try {
      mainWindow.on('move', syncOcrSelectWinBounds)
      mainWindow.on('resize', syncOcrSelectWinBounds)
    } catch (e) {
      // ignore
    }
  }

  const result = await new Promise((resolve) => {
    const done = (data) => {
      try { ipcMain.removeAllListeners(resultChannel) } catch (e) { }
      resolve(data || { ok: false, cancelled: true })
      closeOcrSelectWin()
      // 兜底：框选窗关闭后，恢复 BrowserView 可见性（避免全屏下 onShow 未触发导致黑屏）
      try { restoreViewsVisibility() } catch (e) { }
    }
    ipcMain.once(resultChannel, (_evt2, data) => done(data))
    ocrSelectWin.on('closed', () => done({ ok: false, cancelled: true }))
    try {
      ocrSelectWin.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`)
      ocrSelectWin.once('ready-to-show', () => {
        try {
          ocrSelectWin.show()
          ocrSelectWin.focus()
        } catch (e) { }
      })
    } catch (e) {
      done({ ok: false, cancelled: true })
    }
  })

  if (!result || result.ok !== true || !result.selection) {
    return { ok: false, cancelled: true }
  }

  // 选择完成：window 模式抓整个工作区；BrowserView 模式抓网页
  try {
    let img = null
    let url = ''
    let title = ''
    if (useWindow) {
      img = await mainWindow.webContents.capturePage()
      url = ''
      title = ''
    } else {
      // 等待网页稳定（减少抓空）
      if (view.webContents && view.webContents.isLoading && view.webContents.isLoading()) {
        await new Promise(r => setTimeout(r, 120))
      }
      img = await view.webContents.capturePage()
      url = view.webContents.getURL ? String(view.webContents.getURL() || '') : ''
      title = view.webContents.getTitle ? String(view.webContents.getTitle() || '') : ''
    }
    const dataUrl = img.toDataURL()
    const payloadOut = {
      viewId,
      dataUrl,
      bounds: useWindow ? null : vb,
      selection: result.selection,
      url,
      title
    }
    // 兼容：仍然发事件（旧逻辑使用），同时把 payload 作为返回值给调用方（更稳）
    try {
      if (mainWindow) mainWindow.webContents.send('checkba:ocr-selection-result', payloadOut)
    } catch (e) {
      // ignore
    }
    return { ok: true, payload: payloadOut }
  } catch (e) {
    const msg = String(e && e.message ? e.message : e)
    try {
      if (mainWindow) mainWindow.webContents.send('checkba:ocr-selection-error', { viewId, message: msg })
    } catch (e2) {
      // ignore
    }
    return { ok: false, message: msg }
  }
})

function layoutAllViews() {
  if (!mainWindow) return
  for (const [id, view] of views.entries()) {
    const b = viewBounds.get(id)
    if (!b) continue
    try {
      view.setBounds(b)
      view.setAutoResize({ width: false, height: false })
    } catch (e) {
      // ignore
    }
  }
}

function ensureView(id) {
  if (!mainWindow) throw new Error('mainWindow not ready')
  if (views.has(id)) return views.get(id)

  const view = new BrowserView({
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  })

  // 许多站点会针对 Electron UA 直接断开连接（表现为 SSL handshake failed / ERR_CONNECTION_CLOSED 等）
  // 这里统一使用一个更“正常”的 Chrome UA
  try {
    const chromeUA =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    view.webContents.setUserAgent(chromeUA)
  } catch (e) {
    // ignore
  }

  // 调试：捕获加载失败（用户看到的 ERR_ABORTED/SSL handshake failed 等）
  view.webContents.on('did-fail-load', (_e, errorCode, errorDescription, validatedURL) => {
    // eslint-disable-next-line no-console
    console.warn(`[BrowserView] did-fail-load: code=${errorCode} desc=${errorDescription} url=${validatedURL}`)
  })

  // 页面标题变化：同步给渲染层，用于 tab 标题展示
  view.webContents.on('page-title-updated', (event, title) => {
    try {
      if (event && typeof event.preventDefault === 'function') event.preventDefault()
      if (mainWindow) {
        mainWindow.webContents.send('checkba:browser-title-updated', {
          id,
          title: String(title || ''),
          url: view.webContents.getURL ? String(view.webContents.getURL() || '') : ''
        })
      }
    } catch (e) {
      // ignore
    }
  })

  // 关键：window.open / target=_blank => 交给工作区新 tab
  view.webContents.setWindowOpenHandler(({ url }) => {
    if (mainWindow) {
      mainWindow.webContents.send('checkba:browser-open-new-tab', { id, url })
    }
    return { action: 'deny' }
  })

  // 有些站点通过导航触发新窗口，这里兜底：外部协议交给系统浏览器，其余仍在 app 内
  view.webContents.on('will-navigate', (event, url) => {
    if (!/^https?:\/\//i.test(url)) {
      event.preventDefault()
      shell.openExternal(url)
    }
  })

  attachCopyListener(view.webContents, 'browserview')

  // 网页“选中打标记”入口：右键菜单捕获 selectionText（跨域稳定，不需要注入脚本）
  view.webContents.on('context-menu', async (_event, params) => {
    try {
      const selectionText = (params && params.selectionText ? String(params.selectionText) : '').trim()
      if (!selectionText) return

      const menu = Menu.buildFromTemplate([
        {
          label: '加入网核收藏',
          click: async () => {
            try {
              const url = params.pageURL ? String(params.pageURL) : ''
              const title = view.webContents.getTitle ? (view.webContents.getTitle() || '') : ''
              // 证据：抓取当前网页可视内容截图（不需要屏幕录制权限）
              let imageDataUrl = ''
              try {
                const img = await view.webContents.capturePage()
                imageDataUrl = img ? img.toDataURL() : ''
              } catch (e) {
                // ignore screenshot failure (still save text+url)
              }
              if (mainWindow) {
                mainWindow.webContents.send('checkba:webmark', {
                  viewId: id,
                  url,
                  title,
                  text: selectionText,
                  ts: Date.now(),
                  imageDataUrl
                })
              }
            } catch (e) {
              // ignore
            }
          }
        }
      ])
      // BrowserView 的 x/y 是相对 view 的；popup 需要相对 BrowserWindow 内容区坐标
      try {
        const b = viewBounds.get(id) || { x: 0, y: 0 }
        const x = Math.max(0, Math.floor((b.x || 0) + (params.x || 0)))
        const y = Math.max(0, Math.floor((b.y || 0) + (params.y || 0)))
        menu.popup({ window: mainWindow, x, y })
      } catch (e) {
        menu.popup({ window: mainWindow })
      }
    } catch (e) {
      // ignore
    }
  })

  // 注入 viewport meta 标签，确保页面能感知到 Webview 宽度（User Request: 把webview的宽度传给页面）
  view.webContents.on('dom-ready', () => {
    try {
      const script = `
        (function() {
          if (!document.querySelector('meta[name="viewport"]')) {
            var meta = document.createElement('meta');
            meta.name = "viewport";
            meta.content = "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no";
            document.head.appendChild(meta);
          }
        })();
      `;
      view.webContents.executeJavaScript(script).catch(() => { });
    } catch (e) {
      // ignore
    }
  })

  views.set(id, view)
  return view
}

function attachView(id) {
  if (!mainWindow) return
  const view = views.get(id)
  if (!view) return
  // addBrowserView 重复 add 会抛错，因此先 remove 再 add 以确保置顶
  try {
    mainWindow.removeBrowserView(view)
  } catch (e) {
    // ignore
  }
  try {
    mainWindow.addBrowserView(view)
    attachedViewIds.add(id)
  } catch (e) {
    // ignore
  }
  layoutAllViews()
}

function setAllViewsVisible(visible) {
  if (!mainWindow) return
  if (visible === false) {
    // 不依赖 attachedViewIds：某些情况下 set 可能不同步，导致 remove 不生效
    for (const [_id, view] of views.entries()) {
      if (!view) continue
      try {
        mainWindow.removeBrowserView(view)
      } catch (e) {
        // ignore
      }
    }
    attachedViewIds.clear()
    return
  }
  // visible === true：把所有 view 重新 add 回来（按最后一次的 bounds）
  for (const [id, view] of views.entries()) {
    try {
      mainWindow.addBrowserView(view)
      attachedViewIds.add(id)
    } catch (e) {
      // ignore
    }
  }
  layoutAllViews()
}

ipcMain.handle('checkba:browser-create', async (_evt, payload) => {
  const id = payload && payload.id ? String(payload.id) : `web_${Date.now()}`
  const url = payload && payload.url ? String(payload.url) : 'about:blank'
  ensureView(id)
  attachView(id)
  await views.get(id).webContents.loadURL(url)
  return { id }
})

ipcMain.handle('checkba:browser-navigate', async (_evt, payload) => {
  const id = payload && payload.id ? String(payload.id) : null
  const url = payload && payload.url ? String(payload.url) : null
  if (!id || !url) return { ok: false }
  const view = views.get(id)
  if (!view) return { ok: false }
  try {
    await view.webContents.loadURL(url)
    return { ok: true }
  } catch (e) {
    // 避免把 ERR_ABORTED 直接抛到渲染进程造成 unhandled rejection
    return {
      ok: false,
      code: e && e.code ? String(e.code) : '',
      message: e && e.message ? String(e.message) : String(e)
    }
  }
})

ipcMain.handle('checkba:browser-set-active', async (_evt, payload) => {
  const id = payload && payload.id ? String(payload.id) : null
  if (!id) return { ok: false }
  attachView(id)
  return { ok: true }
})

ipcMain.handle('checkba:browser-set-bounds', async (_evt, payload) => {
  const id = payload && payload.id ? String(payload.id) : null
  const bounds = payload && payload.bounds ? payload.bounds : null
  if (!id || !bounds) return { ok: false }
  const view = views.get(id)
  if (!view) return { ok: false }
  const b = {
    x: Math.max(0, Math.floor(bounds.x || 0)),
    y: Math.max(0, Math.floor(bounds.y || 0)),
    width: Math.max(0, Math.floor(bounds.width || 0)),
    height: Math.max(0, Math.floor(bounds.height || 0))
  }
  viewBounds.set(id, b)
  // 仅更新 bounds，避免频繁 remove/add 导致导航被打断（ERR_ABORTED）
  layoutAllViews()
  return { ok: true }
})

ipcMain.handle('checkba:browser-destroy', async (_evt, payload) => {
  const id = payload && payload.id ? String(payload.id) : null
  if (!id) return { ok: false }
  const view = views.get(id)
  if (!view) return { ok: false }
  if (mainWindow) {
    try {
      mainWindow.removeBrowserView(view)
    } catch (e) {
      // ignore
    }
  }
  try {
    view.webContents.destroy()
  } catch (e) {
    // ignore
  }
  views.delete(id)
  viewBounds.delete(id)
  attachedViewIds.delete(id)
  return { ok: true }
})

ipcMain.handle('checkba:browser-set-views-visible', async (_evt, payload) => {
  const visible = payload && typeof payload.visible === 'boolean' ? payload.visible : true
  viewsVisibleDesired = visible
  setAllViewsVisible(visible)
  return { ok: true }
})

ipcMain.handle('checkba:browser-set-ua', async (_evt, payload) => {
  const id = payload && payload.id ? String(payload.id) : null
  const ua = payload && payload.ua ? String(payload.ua) : null
  if (!id) return { ok: false }
  const view = views.get(id)
  if (!view) return { ok: false }
  try {
    if (ua) view.webContents.setUserAgent(ua)
    // 自动刷新以生效
    view.webContents.reload()
    return { ok: true }
  } catch (e) {
    return { ok: false }
  }
})

ipcMain.handle('checkba:browser-get-snapshot', async (_evt, payload) => {
  const id = payload && payload.id ? String(payload.id) : null
  if (!id) return { ok: false, message: 'missing id' }
  const view = views.get(id)
  if (!view) return { ok: false, message: 'view not found' }
  try {
    const url = view.webContents.getURL ? (view.webContents.getURL() || '') : ''
    const title = view.webContents.getTitle ? (view.webContents.getTitle() || '') : ''
    let html = ''
    try {
      html = await view.webContents.executeJavaScript('document.documentElement ? document.documentElement.outerHTML : ""', true)
    } catch (e) {
      html = ''
    }
    return { ok: true, url, title, html: String(html || '') }
  } catch (e) {
    return { ok: false, message: String(e && e.message ? e.message : e) }
  }
})

ipcMain.handle('checkba:browser-get-bounds', async (_evt, payload) => {
  const id = payload && payload.id ? String(payload.id) : null
  if (!id) return { ok: false }
  const b = viewBounds.get(id)
  if (!b) return { ok: false }
  return { ok: true, bounds: b }
})

ipcMain.handle('checkba:fs-read-file', async (_evt, payload) => {
  const p = payload && payload.path ? String(payload.path) : ''
  if (!p) return { ok: false, message: 'path empty' }
  const fs = require('fs')
  try {
    // Safety check: only allow reading if user explicitly copied it? 
    // In this context, it's triggered by clipboard event which contains the path.
    // For local desktop app, reading local file is acceptable if triggered by user action.
    const buf = await fs.promises.readFile(p)
    // Return key info + base64 (since we can't pass Buffer easily over bridge without setup)
    // Actually Electron handles Buffer in invoke? Yes it does serialize.
    // But to be safe and easy for frontend Blob creation:
    return { ok: true, data: buf }
  } catch (e) {
    return { ok: false, message: String(e.message) }
  }
})

// 等待 BrowserView 导航/渲染稳定后再截图（避免 capturePage 抓到空白）
ipcMain.handle('checkba:browser-wait-ready', async (_evt, payload) => {
  const id = payload && payload.id ? String(payload.id) : null
  const timeoutMs = payload && payload.timeoutMs ? Number(payload.timeoutMs) : 1800
  if (!id) return { ok: false, message: 'missing id' }
  const view = views.get(id)
  if (!view) return { ok: false, message: 'view not found' }
  try {
    const wc = view.webContents
    if (!wc) return { ok: false, message: 'webContents not ready' }
    if (!wc.isLoading || wc.isLoading() === false) return { ok: true, ready: true }
    const start = Date.now()
    await new Promise((resolve) => {
      const done = () => {
        try { wc.removeListener('did-stop-loading', done) } catch (e) { }
        try { wc.removeListener('did-finish-load', done) } catch (e) { }
        resolve()
      }
      try {
        wc.once('did-stop-loading', done)
        wc.once('did-finish-load', done)
      } catch (e) {
        resolve()
      }
      setTimeout(done, Math.max(200, timeoutMs))
    })
    const elapsed = Date.now() - start
    const still = wc.isLoading && wc.isLoading()
    return { ok: true, ready: !still, waitedMs: elapsed }
  } catch (e) {
    return { ok: false, message: String(e && e.message ? e.message : e) }
  }
})

ipcMain.handle('checkba:ocr-capture-screen', async () => {
  // 抓取主屏截图（用于 OCR 框选底图）：不需要浏览器授权
  const display = screen.getPrimaryDisplay()
  const size = display && display.size ? display.size : { width: 1280, height: 720 }
  const sources = await desktopCapturer.getSources({
    types: ['screen'],
    thumbnailSize: { width: size.width, height: size.height }
  })
  const src = sources && sources.length ? sources[0] : null
  if (!src || !src.thumbnail) {
    return { ok: false, message: 'capture failed' }
  }
  const dataUrl = src.thumbnail.toDataURL()
  return { ok: true, dataUrl, width: size.width, height: size.height }
})

// 全桌面截图（用户“任意位置截图”）：优先抓“鼠标所在屏幕”
// 注意：macOS 需要屏幕录制权限，否则会 Failed to get sources.
ipcMain.handle('checkba:ocr-capture-desktop', async () => {
  try {
    const cursor = screen.getCursorScreenPoint()
    const display = screen.getDisplayNearestPoint(cursor) || screen.getPrimaryDisplay()
    const size = display && display.size ? display.size : { width: 1280, height: 720 }
    const sources = await desktopCapturer.getSources({
      types: ['screen'],
      thumbnailSize: { width: size.width, height: size.height }
    })

    let chosen = null
    // 新版 Electron sources 可能带 display_id；优先匹配当前 display
    try {
      const did = display && display.id != null ? String(display.id) : ''
      chosen = sources.find((s) => String(s.display_id || '') === did) || null
    } catch (e) {
      // ignore
    }
    if (!chosen) chosen = sources && sources.length ? sources[0] : null
    if (!chosen || !chosen.thumbnail) {
      return { ok: false, message: 'Failed to get sources.' }
    }
    return { ok: true, dataUrl: chosen.thumbnail.toDataURL(), width: size.width, height: size.height }
  } catch (e) {
    return { ok: false, message: String(e && e.message ? e.message : e) }
  }
})

// 桌面端 OCR 推荐链路：抓“当前窗口/当前 BrowserView”而不是全屏抓屏（避免 macOS 屏幕录制权限）
ipcMain.handle('checkba:ocr-capture-window', async () => {
  if (!mainWindow) return { ok: false, message: 'window not ready' }
  try {
    const img = await mainWindow.webContents.capturePage()
    const dataUrl = img.toDataURL()
    return { ok: true, dataUrl }
  } catch (e) {
    return { ok: false, message: String(e && e.message ? e.message : e) }
  }
})

ipcMain.handle('checkba:ocr-capture-view', async (_evt, payload) => {
  const id = payload && payload.id ? String(payload.id) : null
  if (!id) return { ok: false, message: 'missing view id' }
  const view = views.get(id)
  if (!view) return { ok: false, message: 'view not found' }
  try {
    const img = await view.webContents.capturePage()
    const dataUrl = img.toDataURL()
    return { ok: true, dataUrl }
  } catch (e) {
    return { ok: false, message: String(e && e.message ? e.message : e) }
  }
})

ipcMain.handle('checkba:ping', async () => {
  return { ok: true, pid: process.pid }
})

// 桌面端：统一的“应用内确认弹窗”（不依赖 uni.showModal，且不会被 BrowserView/WPS iframe 遮挡）
ipcMain.handle('checkba:ui-confirm', async (_evt, payload) => {
  if (!mainWindow) return { ok: false, confirmed: false, message: 'window not ready' }
  const title = payload && payload.title ? String(payload.title) : '确认'
  const content = payload && payload.content ? String(payload.content) : ''
  const okText = payload && payload.okText ? String(payload.okText) : '确定'
  const cancelText = payload && payload.cancelText ? String(payload.cancelText) : '取消'

  const reqId = `confirm_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  const resultChannel = `checkba:ui-confirm-result:${reqId}`

  const html = `<!doctype html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <style>
    html, body { margin:0; padding:0; width:100%; height:100%; background: transparent; font-family: -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,"PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif; }
    /* 只保留小卡片本身：不要“黑色大框/遮罩” */
    body { display:flex; align-items:center; justify-content:center; }
    .card { width: 420px; max-width: calc(100vw - 24px); background: rgba(255,255,255,0.98); border: 1px solid rgba(226,232,240,0.95); border-radius: 14px; box-shadow: none; overflow: hidden; }
    .head { padding: 14px 16px 10px; font-weight: 800; font-size: 14px; color: #0f172a; }
    .body { padding: 0 16px 14px; font-size: 13px; color: #334155; line-height: 1.55; white-space: pre-wrap; }
    .foot { display:flex; gap:10px; padding: 12px; justify-content:flex-end; background: rgba(248,250,252,0.92); border-top: 1px solid rgba(226,232,240,0.9); }
    button { height: 30px; padding: 0 12px; border-radius: 10px; border: 1px solid rgba(148,163,184,0.35); background: #fff; font-size: 12px; color: #12344D; cursor: pointer; }
    button.primary { background: #12344D; border-color: transparent; color: #fff; }
  </style>
</head>
<body>
  <div class="card">
    <div class="head">${escapeHtml(title)}</div>
    <div class="body">${escapeHtml(content)}</div>
    <div class="foot">
      <button id="cancel">${escapeHtml(cancelText)}</button>
      <button id="ok" class="primary">${escapeHtml(okText)}</button>
    </div>
  </div>
  <script>
    const { ipcRenderer } = require('electron');
    const ch = ${JSON.stringify(resultChannel)};
    const send = (confirmed) => ipcRenderer.send(ch, { confirmed: !!confirmed });
    document.getElementById('ok').addEventListener('click', () => send(true));
    document.getElementById('cancel').addEventListener('click', () => send(false));
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') { e.preventDefault(); send(false); }
      if (e.key === 'Enter') { e.preventDefault(); send(true); }
    });
  </script>
</body>
</html>`

  const confirmWin = new BrowserWindow({
    width: 460,
    height: 190,
    parent: mainWindow,
    // macOS 上 modal 子窗口会自动暗化父窗口（看起来像“黑色背景遮罩”）
    // 这里改为非 modal：依靠 alwaysOnTop + focus 来获得类似效果，但不产生暗化遮罩
    modal: false,
    show: false,
    frame: false,
    resizable: false,
    transparent: true,
    alwaysOnTop: true,
    // 透明窗口在 macOS 上默认 shadow 有时会呈现“黑块残影”
    // 这里关闭系统 shadow，视觉只保留 HTML 卡片自己的 box-shadow
    hasShadow: false,
    backgroundColor: '#00000000',
    skipTaskbar: true,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false
    }
  })

  // macOS 全屏：确保确认窗出现在同一 Space（避免创建新窗口/回到原窗口黑屏）
  try {
    if (process.platform === 'darwin' && confirmWin) {
      confirmWin.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
      confirmWin.setFullScreenable(false)
      confirmWin.setAlwaysOnTop(true, 'floating')
    }
  } catch (e) {
    // ignore
  }

  const result = await new Promise((resolve) => {
    const done = (v) => {
      try { ipcMain.removeAllListeners(resultChannel) } catch (e) { }
      resolve(!!v)
      try { if (!confirmWin.isDestroyed()) confirmWin.close() } catch (e) { }
      // 兜底：确认窗关闭后恢复 BrowserView（全屏下可能触发渲染层 onHide，导致 BrowserView 被隐藏）
      try { restoreViewsVisibility() } catch (e) { }
    }
    ipcMain.once(resultChannel, (_evt2, data) => done(data && data.confirmed === true))
    confirmWin.on('closed', () => done(false))
    try {
      confirmWin.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`)
      confirmWin.once('ready-to-show', () => {
        try {
          confirmWin.center()
          confirmWin.show()
        } catch (e) {
          // ignore
        }
      })
    } catch (e) {
      done(false)
    }
  })

  return { ok: true, confirmed: result }
})

app.whenReady().then(() => {
  initLocalFileService()
  // 桌面端启动时自动拉起本机后端（9696）
  backend = new BackendManager({ projectRoot: path.join(__dirname, '..', '..') })
  backend
    .start()
    .then(() => createMainWindow())
    .catch((e) => {
      // 后端失败也允许打开 UI（方便你调试），但会提示错误
      createMainWindow()
      try {
        if (mainWindow) {
          mainWindow.webContents.send('checkba:backend-status', { ok: false, message: String(e && e.message ? e.message : e) })
        }
      } catch (err) {
        // ignore
      }
    })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) createMainWindow()
})

app.on('before-quit', async (e) => {
  // 尽量在退出时停止我们启动的后端进程
  if (backend) {
    try {
      e.preventDefault()
      await backend.stop()
    } catch (err) {
      // ignore
    }
    backend = null
    stopClipboardWatcher()
    app.exit(0)
  }
})

ipcMain.handle('checkba:backend-restart', async () => {
  if (!backend) backend = new BackendManager({ projectRoot: path.join(__dirname, '..', '..') })
  return backend.restart()
})


