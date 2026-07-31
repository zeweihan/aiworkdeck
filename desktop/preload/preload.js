const { contextBridge, ipcRenderer } = require('electron')

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
        console.warn('[checkbaDesktop] ocr capture via main failed', msg)
        // 注：desktopCapturer 是主进程模块，在（sandbox 的）preload 中为 undefined，直接调用只会抛
        // TypeError 掩盖真实错误。截图统一走上面的主进程 IPC，这里如实返回失败。
        return { ok: false, message: msg }
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
  // 组件管理（本地模型下载/状态，Phase 2）：状态查询 + 下载/取消/删除 + 进度订阅
  model: {
    status: () => ipcRenderer.invoke('checkba:model-status'),
    download: (id) => ipcRenderer.invoke('checkba:model-download', { id }),
    cancel: (id) => ipcRenderer.invoke('checkba:model-cancel', { id }),
    remove: (id) => ipcRenderer.invoke('checkba:model-remove', { id }),
    onProgress: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:model-progress', listener)
      return () => ipcRenderer.removeListener('checkba:model-progress', listener)
    }
  },
  services: {
    ensure: (name) => ipcRenderer.invoke('checkba:service-ensure', { name })
  },
  utils: {
    readFile: (path) => ipcRenderer.invoke('checkba:fs-read-file', { path })
  },
  fs: {
    // 注：readFile/writeFile 曾暴露任意路径读/写（渲染进程零调用，属死暴露，其中任意写可覆盖
    // ~/.zshrc 等实现代码执行），已移除以缩小攻击面。唯一在用的文件读取走 utils.readFile
    // （checkba:fs-read-file，已加敏感路径拦截与大小上限）。
    showOpenDialog: (options) => ipcRenderer.invoke('fs:showOpenDialog', options),
    // 在 Finder/资源管理器里高亮一个已有路径（IDE 化项目「在 Finder 中显示」）
    showItemInFolder: (path) => ipcRenderer.invoke('fs:showItemInFolder', { path }),
    // 拖放的 File 对象 → 绝对路径（Electron 32 起 File.path 移除，webUtils 是正途）
    getPathForFile: (file) => {
      try {
        const { webUtils } = require('electron')
        if (webUtils && webUtils.getPathForFile) return webUtils.getPathForFile(file)
      } catch (e) { /* fall through */ }
      return (file && file.path) || ''
    }
  },
  // IDE 化应用菜单：动作订阅（文件菜单点击/最近打开）与「最近打开」子菜单数据推送
  menu: {
    onAction: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:menu-action', listener)
      return () => ipcRenderer.removeListener('checkba:menu-action', listener)
    },
    setRecentProjects: (list) => ipcRenderer.send('checkba:recent-projects', list)
  },
  // Epic #43: embedded LibreOffice editor <webview> wiring. getEditor() returns
  // { url, preload, partition } for the host to mount the webview.
  // （onOpenEmbed / ⌘⇧O 覆盖层已移除：内联编辑器就是产品默认。）
  zetaoffice: {
    getEditor: () => ipcRenderer.invoke('checkba:zetaoffice-editor')
  }
})


