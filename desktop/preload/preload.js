const { contextBridge, ipcRenderer, desktopCapturer } = require('electron')

contextBridge.exposeInMainWorld('checkbaDesktop', {
  app: {
    onOpenInternal: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:app-open-internal', listener)
      return () => ipcRenderer.removeListener('checkba:app-open-internal', listener)
    },
    confirm: (payload) => ipcRenderer.invoke('checkba:ui-confirm', payload)
  },
  browser: {
    create: (payload) => ipcRenderer.invoke('checkba:browser-create', payload),
    navigate: (payload) => ipcRenderer.invoke('checkba:browser-navigate', payload),
    setActive: (payload) => ipcRenderer.invoke('checkba:browser-set-active', payload),
    setBounds: (payload) => ipcRenderer.invoke('checkba:browser-set-bounds', payload),
    setViewsVisible: (payload) => ipcRenderer.invoke('checkba:browser-set-views-visible', payload),
    destroy: (payload) => ipcRenderer.invoke('checkba:browser-destroy', payload),
    getBounds: (payload) => ipcRenderer.invoke('checkba:browser-get-bounds', payload),
    waitReady: (payload) => ipcRenderer.invoke('checkba:browser-wait-ready', payload),
    onOpenNewTab: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:browser-open-new-tab', listener)
      return () => ipcRenderer.removeListener('checkba:browser-open-new-tab', listener)
    },
    onWebMark: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:webmark', listener)
      return () => ipcRenderer.removeListener('checkba:webmark', listener)
    },
    onTitleUpdated: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:browser-title-updated', listener)
      return () => ipcRenderer.removeListener('checkba:browser-title-updated', listener)
    },
    getSnapshot: (payload) => ipcRenderer.invoke('checkba:browser-get-snapshot', payload),
    setUA: (payload) => ipcRenderer.invoke('checkba:browser-set-ua', payload)
  }
  ,
  ocr: {
    startSelection: (payload) => ipcRenderer.invoke('checkba:ocr-start-selection', payload),
    onSelectionResult: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:ocr-selection-result', listener)
      return () => ipcRenderer.removeListener('checkba:ocr-selection-result', listener)
    },
    onSelectionError: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:ocr-selection-error', listener)
      return () => ipcRenderer.removeListener('checkba:ocr-selection-error', listener)
    },
    captureScreen: async (options) => {
      const viewId = options && options.viewId ? String(options.viewId) : ''
      const mode = options && options.mode ? String(options.mode) : ''
      // 优先抓当前 BrowserView/窗口（不需要 macOS 屏幕录制权限）
      try {
        if (mode === 'window') {
          return await ipcRenderer.invoke('checkba:ocr-capture-window')
        }
        // 用户要求“全桌面任意位置截图”：显式走 desktopCapturer（需要系统屏幕录制权限）
        if (mode === 'desktop') {
          return await ipcRenderer.invoke('checkba:ocr-capture-desktop')
        }
        if (viewId) {
          const resp = await ipcRenderer.invoke('checkba:ocr-capture-view', { id: viewId })
          if (resp && resp.ok) return resp
        }
        const win = await ipcRenderer.invoke('checkba:ocr-capture-window')
        if (win && win.ok) return win
        // 再兜底：旧 handler（全屏抓屏）
        return await ipcRenderer.invoke('checkba:ocr-capture-screen')
      } catch (e) {
        const msg = String(e && e.message ? e.message : e)
        // eslint-disable-next-line no-console
        console.warn('[checkbaDesktop] ocr capture via main failed, fallback', msg)
        try {
          const sources = await desktopCapturer.getSources({
            types: ['screen'],
            thumbnailSize: { width: 1920, height: 1080 }
          })
          const src = sources && sources.length ? sources[0] : null
          if (!src || !src.thumbnail) {
            return { ok: false, message: msg }
          }
          return { ok: true, dataUrl: src.thumbnail.toDataURL() }
        } catch (e2) {
          return { ok: false, message: msg }
        }
      }
    }
  },
  clipboard: {
    onCopied: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:clipboard-copied', listener)
      return () => ipcRenderer.removeListener('checkba:clipboard-copied', listener)
    }
  },
  backend: {
    restart: () => ipcRenderer.invoke('checkba:backend-restart'),
    onStatus: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:backend-status', listener)
      return () => ipcRenderer.removeListener('checkba:backend-status', listener)
    }
  },
  utils: {
    readFile: (path) => ipcRenderer.invoke('checkba:fs-read-file', { path })
  },
  fs: {
    readFile: (path) => ipcRenderer.invoke('fs:readFile', path),
    writeFile: (path, data) => ipcRenderer.invoke('fs:writeFile', { filePath: path, data }),
    showOpenDialog: (options) => ipcRenderer.invoke('fs:showOpenDialog', options)
  },
  // Epic #43: embedded LibreOffice editor <webview> wiring. getEditor() returns
  // { url, preload, partition } for the host to mount the webview; onOpenEmbed
  // fires when the ⌘⇧O global shortcut requests the editor overlay.
  zetaoffice: {
    getEditor: () => ipcRenderer.invoke('checkba:zetaoffice-editor'),
    onOpenEmbed: (handler) => {
      const listener = () => handler && handler()
      ipcRenderer.on('checkba:zetaoffice-open-embed', listener)
      return () => ipcRenderer.removeListener('checkba:zetaoffice-open-embed', listener)
    }
  }
})


