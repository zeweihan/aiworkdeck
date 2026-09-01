const { contextBridge, ipcRenderer } = require('electron')

// 主进程经 additionalArguments 注入的后端地址（端口是启动时实际分配的，
// 打包态默认 5269，冲突自动降级）。同步可读，供渲染层 api.js 首选。
const apiBaseArg = process.argv.find((a) => a.startsWith('--checkba-api-base='))
const apiBaseUrl = apiBaseArg ? apiBaseArg.slice('--checkba-api-base='.length) : null
// ARM 版 Windows（Mac 虚拟机）转译运行：主进程看门狗超时已放宽 8 倍（dev-board#340），
// 渲染层的等待死线要同步放宽，否则会在后端仍在正常预热时判超时（dev-board#341）
const winEmulated = process.argv.includes('--checkba-win-emulated=1')

contextBridge.exposeInMainWorld('checkbaDesktop', {
  apiBaseUrl,
  winEmulated,
  // 窗口外壳：无边框窗口下渲染层要自己让出交通灯/窗口控件的位置，
  // 得知道跑在哪个平台、以及此刻是不是全屏（全屏时交通灯隐藏）。
  chrome: {
    platform: process.platform,
    onState: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:chrome-state', listener)
      return () => ipcRenderer.removeListener('checkba:chrome-state', listener)
    }
  },
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
    // detach = 面板卸载（切标签），view 保活；destroy = 标签真的关了
    detach: (payload) => ipcRenderer.invoke('checkba:browser-detach', payload),
    destroy: (payload) => ipcRenderer.invoke('checkba:browser-destroy', payload),
    history: (payload) => ipcRenderer.invoke('checkba:browser-history', payload),
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
    // 页内跳转后的当前地址（点链接、搜索、SPA 换路由）——标签靠它才不会停在打开时那一页
    onUrlUpdated: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:browser-url-updated', listener)
      return () => ipcRenderer.removeListener('checkba:browser-url-updated', listener)
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
  // 应用内增量更新（docs/INCREMENTAL_UPDATE_DESIGN.md）：小版本补丁自动下载、
  // 重启生效；大版本引导官网下载全量包。onEvent 返回退订函数——页面栈多实例
  // 场景务必用活跃实例指针消费（见剪贴板去重地雷，PR#151）。
  update: {
    status: () => ipcRenderer.invoke('checkba:update-status'),
    check: () => ipcRenderer.invoke('checkba:update-check'),
    restart: () => ipcRenderer.invoke('checkba:update-restart'),
    onEvent: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:update-event', listener)
      return () => ipcRenderer.removeListener('checkba:update-event', listener)
    }
  },
  utils: {
    readFile: (path) => ipcRenderer.invoke('checkba:fs-read-file', { path })
  },
  // 用系统浏览器打开站外链接（仅 http(s)，主进程侧再校验一次）。
  // 解锁页等未加载工作区浏览器的场景依赖它——window.open 会被
  // setWindowOpenHandler 转成无人消费的事件而静默失效。
  shell: {
    openExternal: (url) => ipcRenderer.invoke('checkba:shell-open-external', { url }),
    // 帮助菜单「查看日志」。路径由主进程固定为 ~/.aiworkdeck/logs，不接受传参。
    revealLogs: () => ipcRenderer.invoke('checkba:reveal-logs')
  },
  fs: {
    // 注：readFile/writeFile 曾暴露任意路径读/写（渲染进程零调用，属死暴露，其中任意写可覆盖
    // ~/.zshrc 等实现代码执行），已移除以缩小攻击面。唯一在用的文件读取走 utils.readFile
    // （checkba:fs-read-file，只放行主进程登记过的剪贴板文件路径，另有大小上限）。
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
  // 应用菜单：动作订阅（点了哪条命令）与整棵菜单树的下发。
  // 菜单结构、文案、enabled/checked 全部由渲染层决定（frontend/src/utils/appMenuBridge.js），
  // 主进程只把 JSON 渲染成 NSMenu；原先单推「最近打开」的 checkba:recent-projects
  // 已并入 setState，不再单开一条通道。
  menu: {
    onAction: (handler) => {
      const listener = (_evt, data) => handler && handler(data)
      ipcRenderer.on('checkba:menu-action', listener)
      return () => ipcRenderer.removeListener('checkba:menu-action', listener)
    },
    setState: (payload) => ipcRenderer.send('checkba:menu-state', payload)
  },
  // 应用语言（zh-CN/en-US）：渲染层是权威源，启动与切换时推给主进程
  // （菜单/原生对话框文案随之重建，见 desktop/main/app-language.js）。
  appLanguage: {
    set: (lang) => ipcRenderer.send('checkba:app-language', lang)
  },
  // 外观主题（light/dark/system）：渲染层是权威源，推给主进程去设 nativeTheme，
  // 原生标题栏/交通灯/右键菜单随之一致（dev-board#218/#223）。
  // 回执带上系统当前是否深色——system 态下渲染层的 matchMedia 可能还没同步。
  theme: {
    set: (mode) => ipcRenderer.invoke('checkba:set-theme', mode)
  },
  // Epic #43: embedded LibreOffice editor <webview> wiring. getEditor() returns
  // { url, preload, partition } for the host to mount the webview.
  // （onOpenEmbed / ⌘⇧O 覆盖层已移除：内联编辑器就是产品默认。）
  zetaoffice: {
    getEditor: () => ipcRenderer.invoke('checkba:zetaoffice-editor')
  },
  // 内嵌 draw.io 编辑器（诉讼可视化出的 .drawio 是唯一可继续编辑的版本）。
  // getEditor() 返回 { available, kind, origin, url }；available=false 表示这次
  // 构建没烙 draw.io 资源，调用点应退回下载。
  drawio: {
    getEditor: () => ipcRenderer.invoke('checkba:drawio-editor')
  }
})


