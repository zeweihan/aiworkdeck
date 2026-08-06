// project-overview.vue 的 OCR 采集入口与浮层生命周期：桌面走主进程 OverlayWindow、
// 浏览器走 getDisplayMedia；浮层开关、ESC 热键、document 级 down/move/up 框选监听与解绑。
// 这组持有跨方法的实例态（_ocrDoc* / _ocrGlobalBound / _ocrKeydownBound），改动需成对检查绑定与解绑。
// 注意：本簇无法在 app-e2e 浏览器目标里驱动（getDisplayMedia 会关掉 target），见 run.mjs J6.6 注释。
// 经展开进组件 methods（纯搬移，Phase 3c 外置），`this` 即 project-overview 页面实例。


import { host } from '@/services/host.js'

export const ocrCaptureMethods = {
    async applyDesktopOcrSelection(payload) {
      if (!payload || !payload.dataUrl || !payload.selection) return
      // 选区完成后再隐藏 BrowserView：此时用户不需要看真实网页了
      try {
        if (host.browser && host.browser.setViewsVisible) {
          await host.browser.setViewsVisible({ visible: false })
        }
      } catch (e) {
        // ignore
      }

      this.ocrText = ''
      this.ocrImageDataUrl = ''
      this.ocrOverlaySelecting = false
      this.ocrActionBar = { visible: false, x: 0, y: 0 }
      this.showOcrOverlay = true
      this.bindOcrHotkeys()
      this.bindOcrGlobalListeners()

      this.ocrSourceUrl = payload.url ? String(payload.url) : (this.ocrSourceUrl || '')
      const b = payload.bounds || null
      const hostRect = b ? { x: Number(b.x) || 0, y: Number(b.y) || 0, width: Number(b.width) || 0, height: Number(b.height) || 0 } : null
      this.ocrHostRect = hostRect
      await this.ocrSetFrameFromDataUrl(String(payload.dataUrl), hostRect)

      const s = payload.selection
      this.ocrSel = { x1: Number(s.x1) || 0, y1: Number(s.y1) || 0, x2: Number(s.x2) || 0, y2: Number(s.y2) || 0 }

      try {
        this.ocrImageDataUrl = this.cropOcrSelection()
        const left = Math.min(this.ocrSel.x1, this.ocrSel.x2)
    const right = Math.max(this.ocrSel.x1, this.ocrSel.x2)
    const bottom = Math.max(this.ocrSel.y1, this.ocrSel.y2)
    const centerX = (left + right) / 2
    this.ocrActionBar = { visible: true, x: centerX, y: bottom + 12 }
      } catch (e) {
        console.error('截图裁剪失败:', e)
      }
    },
    getOcrPoint(e) {
      const te = e && (e.touches && e.touches[0])
      const ce = e && (e.changedTouches && e.changedTouches[0])
      const p = te || ce || e || {}
      return { x: Number(p.clientX || p.pageX || 0), y: Number(p.clientY || p.pageY || 0) }
    },
    getOcrCanvasEl() {
      // uniapp H5 下 ref 可能不是原生 canvas；兜底用 id 取真实 DOM
      let c = this.$refs.ocrCanvas
      if (c && c.$el) c = c.$el
      if (c && typeof c.getContext === 'function') return c
      // #ifdef H5
      const dom = document.getElementById('ocr-overlay-canvas')
      if (dom && typeof dom.getContext === 'function') return dom
      // #endif
      return null
    },
    ocrLog(...args) {
      if (!this.ocrDebug) return
      // eslint-disable-next-line no-console
      console.log('[OCR]', ...args)
    },
    // uniapp H5 下：用 document capture 事件接管拖拽，避免 view 合成事件不触发
    bindOcrGlobalListeners() {
      // #ifdef H5
      if (this._ocrGlobalBound) return
      this._ocrGlobalBound = true
      this._ocrMoveLogTs = 0

      this._ocrDocDown = (ev) => {
        if (!this.showOcrOverlay) return
        const p0 = this.getOcrPoint(ev)
        this.ocrLastPointer = p0
        // 右键不处理
        if (ev && ev.button !== undefined && ev.button !== 0) return
        // actionbar 内点击不触发框选
        if (ev && ev.target && ev.target.closest && ev.target.closest('.ocr-actionbar')) return
        this.onOcrOverlayDown(ev)
        if (ev && ev.cancelable) ev.preventDefault()
      }
      this._ocrDocMove = (ev) => {
        if (!this.showOcrOverlay) return
        const p0 = this.getOcrPoint(ev)
        this.ocrLastPointer = p0
        this.onOcrOverlayMove(ev)
        const now = Date.now()
        if (this.ocrDebug && now - this._ocrMoveLogTs > 250) {
          const p = this.getOcrPoint(ev)
          this.ocrLog('move', p.x, p.y, 'selecting=', this.ocrOverlaySelecting)
          this._ocrMoveLogTs = now
        }
        if (ev && ev.cancelable) ev.preventDefault()
      }
      this._ocrDocUp = (ev) => {
        if (!this.showOcrOverlay) return
        const p0 = this.getOcrPoint(ev)
        this.ocrLastPointer = p0
        this.onOcrOverlayUp(ev)
        if (ev && ev.cancelable) ev.preventDefault()
      }

      document.addEventListener('mousedown', this._ocrDocDown, true)
      document.addEventListener('mousemove', this._ocrDocMove, true)
      document.addEventListener('mouseup', this._ocrDocUp, true)
      document.addEventListener('touchstart', this._ocrDocDown, { capture: true, passive: false })
      document.addEventListener('touchmove', this._ocrDocMove, { capture: true, passive: false })
      document.addEventListener('touchend', this._ocrDocUp, { capture: true, passive: false })
      // #endif
    },
    unbindOcrGlobalListeners() {
      // #ifdef H5
      if (!this._ocrGlobalBound) return
      document.removeEventListener('mousedown', this._ocrDocDown, true)
      document.removeEventListener('mousemove', this._ocrDocMove, true)
      document.removeEventListener('mouseup', this._ocrDocUp, true)
      document.removeEventListener('touchstart', this._ocrDocDown, true)
      document.removeEventListener('touchmove', this._ocrDocMove, true)
      document.removeEventListener('touchend', this._ocrDocUp, true)
      this._ocrDocDown = null
      this._ocrDocMove = null
      this._ocrDocUp = null
      this._ocrGlobalBound = false
      // #endif
    },
    bindOcrHotkeys() {
      if (this._ocrKeydownBound) return
      this._ocrKeydownBound = true
      this._ocrKeydownHandler = (e) => {
        if (!this.showOcrOverlay) return
        if (e.key === 'Escape') {
          e.preventDefault()
          this.closeOcrOverlay()
        }
      }
      window.addEventListener('keydown', this._ocrKeydownHandler)
    },
    unbindOcrHotkeys() {
      if (this._ocrKeydownHandler) {
        window.removeEventListener('keydown', this._ocrKeydownHandler)
      }
      this._ocrKeydownHandler = null
      this._ocrKeydownBound = false
    },
    closeOcrOverlay() {
      // 关闭蒙层：H5 使用屏幕共享时可能涉及授权；Desktop 不需要授权
      // Desktop：BrowserView 的恢复由 desktopOverlayActive watcher 统一处理
      // （若此时还有其它弹窗打开，则保持隐藏，避免弹窗被网页遮挡）
      this.showOcrOverlay = false
      // #ifdef H5
      this.unbindOcrGlobalListeners()
      // #endif
      this.ocrOverlaySelecting = false
      this.ocrSel = { x1: 0, y1: 0, x2: 0, y2: 0 }
      this.ocrActionBar = { visible: false, x: 0, y: 0 }
      this.ocrText = ''
      this.ocrImageDataUrl = ''
      this.ocrFrameCanvas = null
      this.ocrFrameView = null
      if (this.ocrFrameUrl) {
        try { URL.revokeObjectURL(this.ocrFrameUrl) } catch (e) { /* ignore */ }
      }
      this.ocrFrameUrl = ''
      this.unbindOcrHotkeys()
    },
    async startOcrCapture() {
      // #ifdef H5
      try {
        this.ocrLoading = false
        this.ocrText = ''
        this.ocrImageDataUrl = ''
        this.ocrHostRect = null
        this.ocrOverlaySelecting = false
        this.ocrSel = { x1: 0, y1: 0, x2: 0, y2: 0 }
        this.ocrFrameCanvas = null
        this.ocrFrameView = null
        this.ocrFrameLoading = false

        // 尽量绑定当前浏览器 tab 的 URL（用于收藏）
        const active = this.focusedPane === 'right' ? this.activeFileRight : this.activeFileLeft
        this.ocrSourceUrl = active && active.tabType === 'web' ? (active.url || '') : ''

        // Desktop：直接抓屏做底图，不需要浏览器授权
        if (this.isDesktopApp && host.ocr) {
          // 桌面端（方案 B）：使用主进程 OverlayWindow 进行框选（选区期间不隐藏 BrowserView）
          const activeWebTab = this.getActiveWebTab()
          const viewId = activeWebTab && activeWebTab.id ? String(activeWebTab.id) : ''
          if (!host.ocr.startSelection) {
            uni.showToast({ title: '桌面端截图能力不可用', icon: 'none' })
            return
          }
          // 全局截图：无网页 tab 时走 window 模式（两边都是文档也能截图）
          const resp = viewId
            ? await host.ocr.startSelection({ viewId })
            : await host.ocr.startSelection({ mode: 'window' })
          if (!resp || resp.ok !== true) {
            if (resp && resp.cancelled) return
            uni.showToast({ title: (resp && resp.message) ? String(resp.message) : '截图失败', icon: 'none' })
            return
          }
          if (resp && resp.payload) {
            await this.applyDesktopOcrSelection(resp.payload)
          } else {
            // 兼容旧事件回调（但不再依赖）
            uni.showToast({ title: '截图完成，但未收到结果', icon: 'none' })
          }
          return
        }

        if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
          uni.showToast({ title: '当前浏览器不支持屏幕共享', icon: 'none' })
          return
        }

        // 关键：授权一次后保持 stream，用户在全屏浮层上随时框选
        if (!this.ocrStream) {
          this.ocrStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
        }

        this.showOcrOverlay = true
        this.ocrActionBar = { visible: false, x: 0, y: 0 }
        this.bindOcrHotkeys()
        this.bindOcrGlobalListeners()
        // 用 offscreen video + canvas 实时渲染（避免出现“播放器三角/黑屏”）
        if (!this.ocrVideo) {
          this.ocrVideo = document.createElement('video')
          this.ocrVideo.muted = true
          this.ocrVideo.playsInline = true
          this.ocrVideo.autoplay = true
        }
        this.ocrVideo.srcObject = this.ocrStream
        await this.ocrVideo.play()

        // 改为“冻结帧”模式：抓一帧作为底图，用户在底图上框选，裁剪/下载/识别都基于同一帧
        await this.$nextTick()
        await this.ocrRefreshFrame()
      } catch (e) {
        console.error('启动 OCR 截图失败:', e)
        // 桌面端不需要浏览器授权：避免误导
        const title = this.isDesktopApp ? (e.message || '截图失败') : '截图失败（请允许共享标签页/窗口）'
        uni.showToast({ title, icon: 'none' })
        // 失败时统一走 closeOcrOverlay 复位（BrowserView 恢复由 watcher 处理）
        try {
          this.closeOcrOverlay()
        } catch (err) {
          // ignore
        }
      }
      // #endif
      // #ifndef H5
      uni.showToast({ title: '仅 H5 支持截图摘录', icon: 'none' })
      // #endif
    },

    // OCR 不再使用弹窗

    hideOcrOverlay() {
      this.closeOcrOverlay()
    },

    stopOcrCapture() {
      this.closeOcrOverlay()
      try {
        if (this.ocrStream) {
          this.ocrStream.getTracks().forEach(t => t.stop())
        }
      } catch (e) {
        // ignore
      }
      this.ocrStream = null
      this.ocrVideo = null
    },
    onOcrOverlayDown(e) {
      if (!this.showOcrOverlay) return
      // 只处理左键
      if (e && e.button !== 0) return
      // 如果已经有 actionbar，重新开始框选
      this.ocrActionBar.visible = false
      this.ocrOverlaySelecting = true
      const p = this.getOcrPoint(e)
      this.ocrSel = { x1: p.x, y1: p.y, x2: p.x, y2: p.y }
      this.ocrLog('down', p.x, p.y, 'type=', e && e.type)
    },
    onOcrOverlayMove(e) {
      if (!this.ocrOverlaySelecting) return
      const p = this.getOcrPoint(e)
      this.ocrSel = { ...this.ocrSel, x2: p.x, y2: p.y }
    },
    async onOcrOverlayUp(e) {
      if (!this.ocrOverlaySelecting) return
      this.ocrOverlaySelecting = false
      const p = this.getOcrPoint(e)
      this.ocrLog('up', p.x, p.y, 'hasSelection=', this.ocrHasSelection)
      // 单击（无明显拖动）直接退出蒙层
      if (!this.ocrHasSelection) {
        this.closeOcrOverlay()
        return
      }

      // 框选结束：先裁剪生成图片，再显示快捷命令条（不自动识别）
      // #ifdef H5
      try {
        this.ocrText = ''
        await this.ensureOcrFrozenFrame()
        this.ocrImageDataUrl = this.cropOcrSelection()
        const left = Math.min(this.ocrSel.x1, this.ocrSel.x2)
    const right = Math.max(this.ocrSel.x1, this.ocrSel.x2)
    const bottom = Math.max(this.ocrSel.y1, this.ocrSel.y2)

    // Center X: Use the midpoint
    const centerX = (left + right) / 2

    // Y: Below the selection
    const topY = bottom + 12

    // 命令条位置：居中显示在框选区域下方
    this.ocrActionBar = {
      visible: true,
      x: centerX,
      y: topY
    }
      } catch (e) {
        console.error('截图裁剪失败:', e)
        uni.showToast({ title: e.message || '截图失败', icon: 'none' })
      }
      // #endif
    },
}
