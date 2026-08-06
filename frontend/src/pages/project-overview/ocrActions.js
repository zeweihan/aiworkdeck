// project-overview.vue 的 OCR 帧处理与动作：冻结帧的取用/刷新/裁剪，以及识别、
// 复制、插入文档、收藏、生成网页链接、截图另存对话框。
// 不含采集入口与浮层生命周期（那部分在 ./ocrCapture.js）。
// 经展开进组件 methods（纯搬移，Phase 3b 外置），`this` 即 project-overview 页面实例。


import { ocrRecognize, createProjectFavorite, getProjectFiles, createFile, getApiBaseUrl } from '@/services/api.js'
import { getSessionId } from '@/utils/auth.js'
import { host } from '@/services/host.js'

export const ocrActionMethods = {
    async ensureOcrFrozenFrame() {
      // 若底图未就绪：等待 DOM 挂载并重试抓帧，避免松开时报“画面未就绪”
      if (this.ocrFrameCanvas && this.ocrFrameView) return
      if (this.ocrFrameLoading) {
        const start = Date.now()
        while (Date.now() - start < 1200) {
          if (this.ocrFrameCanvas && this.ocrFrameView) return
          await new Promise(r => setTimeout(r, 30))
        }
        throw new Error('截图画面未就绪')
      }
      this.ocrFrameLoading = true
      try {
        // 等 canvas 挂载
        await this.$nextTick()
        await new Promise(r => requestAnimationFrame(r))
        // 最多重试 2 次
        for (let i = 0; i < 2; i++) {
          try {
            await this.ocrRefreshFrame()
            if (this.ocrFrameCanvas && this.ocrFrameView) return
          } catch (e) {
            if (i === 1) throw e
            await new Promise(r => setTimeout(r, 80))
          }
        }
      } finally {
        this.ocrFrameLoading = false
      }
    },

    async ocrRefreshFrame() {
      // 抓一帧作为底图，并绘制到 overlay canvas（用户框选基于该底图）
      // #ifdef H5
      if (this.isDesktopApp && host.ocr) {
        // 桌面端：抓“当前激活网页 Tab 的 BrowserView”（包含网页内容；不需要屏幕录制权限）
        const activeWebTab = this.getActiveWebTab()
        const viewId = activeWebTab && activeWebTab.id ? String(activeWebTab.id) : ''

        // 获取 BrowserView 的 bounds，用于把截图图像铺到网页区域，确保框选坐标正确
        try {
          const api = host.browser
          const b = api && viewId ? await api.getBounds({ id: viewId }) : null
          if (b && b.ok && b.bounds) {
            this.ocrHostRect = {
              x: Number(b.bounds.x) || 0,
              y: Number(b.bounds.y) || 0,
              width: Number(b.bounds.width) || 0,
              height: Number(b.bounds.height) || 0
            }
          } else {
            this.ocrHostRect = null
          }
        } catch (e) {
          this.ocrHostRect = null
        }

        // 若当前没有网页 tab，则回退抓当前窗口（此时 hostRect 为空，图片会铺满全屏）
        const resp = viewId
          ? await host.ocr.captureScreen({ viewId })
          : await host.ocr.captureScreen({ mode: 'window' })
        if (!resp || resp.ok !== true || !resp.dataUrl) {
          const m = resp && resp.message ? String(resp.message) : ''
          throw new Error(m || '截图失败')
        }
        await this.ocrSetFrameFromDataUrl(resp.dataUrl, this.ocrHostRect)
        return
      }
      const video = this.ocrVideo
      if (!video) throw new Error('截图视频未就绪')
      await this.ensureOcrFrameReady()

      const vw = video.videoWidth || 0
      const vh = video.videoHeight || 0
      if (!vw || !vh) throw new Error('截图视频尺寸异常')

      const frame = document.createElement('canvas')
      frame.width = vw
      frame.height = vh
      const fctx = frame.getContext('2d')
      fctx.drawImage(video, 0, 0, vw, vh)
      this.ocrFrameCanvas = frame

      // viewport 内展示：aspectFit（contain）
      const cw = window.innerWidth
      const ch = window.innerHeight
      const scale = Math.min(cw / vw, ch / vh)
      const dw = vw * scale
      const dh = vh * scale
      const dx = (cw - dw) / 2
      const dy = (ch - dh) / 2
      this.ocrFrameView = { vw, vh, cw, ch, dx, dy, scale }

      // 生成可展示的 URL（避免依赖 canvas DOM）
      if (this.ocrFrameUrl) {
        try { URL.revokeObjectURL(this.ocrFrameUrl) } catch (e) { /* ignore */ }
      }
      const blob = await new Promise((resolve) => {
        try {
          frame.toBlob((b) => resolve(b), 'image/png')
        } catch (e) {
          resolve(null)
        }
      })
      if (blob) {
        this.ocrFrameUrl = URL.createObjectURL(blob)
      } else {
        // fallback
        this.ocrFrameUrl = frame.toDataURL('image/png')
      }
      // #endif
    },

    async ocrSetFrameFromDataUrl(dataUrl, hostRect = null) {
      const url = String(dataUrl || '')
      if (!url) throw new Error('截图失败')
      const img = await new Promise((resolve, reject) => {
        const im = new Image()
        im.onload = () => resolve(im)
        im.onerror = () => reject(new Error('截图图片加载失败'))
        im.src = url
      })
      const vw = img.naturalWidth || img.width || 0
      const vh = img.naturalHeight || img.height || 0
      if (!vw || !vh) throw new Error('截图图片尺寸异常')

      const frame = document.createElement('canvas')
      frame.width = vw
      frame.height = vh
      const fctx = frame.getContext('2d')
      fctx.drawImage(img, 0, 0, vw, vh)
      this.ocrFrameCanvas = frame

      // Desktop：如果传入 hostRect（BrowserView bounds），就把画面铺到该区域内；否则回退到全屏
      const cw = hostRect && hostRect.width ? Number(hostRect.width) : window.innerWidth
      const ch = hostRect && hostRect.height ? Number(hostRect.height) : window.innerHeight
      const ox = hostRect && typeof hostRect.x === 'number' ? Number(hostRect.x) : 0
      const oy = hostRect && typeof hostRect.y === 'number' ? Number(hostRect.y) : 0
      const scale = Math.min(cw / vw, ch / vh)
      const dw = vw * scale
      const dh = vh * scale
      const dx = ox + (cw - dw) / 2
      const dy = oy + (ch - dh) / 2
      this.ocrFrameView = { vw, vh, cw, ch, dx, dy, scale }

      // 展示用：直接使用 dataUrl（桌面端无需 objectURL）
      this.ocrFrameUrl = url
    },

    async ensureOcrFrameReady() {
      // 确保 videoWidth/videoHeight 与 cover 已就绪（防止第一下松开太快）
      const videoEl = this.ocrVideo
      if (!videoEl) throw new Error('截图视频未就绪')
      const start = Date.now()
      const timeoutMs = 900
      while (Date.now() - start < timeoutMs) {
        const vw = videoEl.videoWidth || 0
        const vh = videoEl.videoHeight || 0
        if (vw && vh) return
        await new Promise(r => setTimeout(r, 30))
      }
      // 即使超时，也让 crop 自己兜底一次（会检查 vw/vh）
    },

    cropOcrSelection() {
      const left = Math.min(this.ocrSel.x1, this.ocrSel.x2)
      const top = Math.min(this.ocrSel.y1, this.ocrSel.y2)
      const w = Math.abs(this.ocrSel.x2 - this.ocrSel.x1)
      const h = Math.abs(this.ocrSel.y2 - this.ocrSel.y1)

      const frame = this.ocrFrameCanvas
      const view = this.ocrFrameView
      if (!frame || !view) throw new Error('截图画面未就绪')

      // 将用户在 viewport 的框选，映射到“冻结帧”像素坐标
      const clamp = (v, min, max) => Math.max(min, Math.min(max, v))
      const sx = (left - view.dx) / view.scale
      const sy = (top - view.dy) / view.scale
      const sw = w / view.scale
      const sh = h / view.scale

      const csx = clamp(sx, 0, view.vw - 1)
      const csy = clamp(sy, 0, view.vh - 1)
      const csw = clamp(sw, 1, view.vw - csx)
      const csh = clamp(sh, 1, view.vh - csy)

      const out = document.createElement('canvas')
      out.width = Math.max(1, Math.floor(csw))
      out.height = Math.max(1, Math.floor(csh))
      const ctx = out.getContext('2d')
      ctx.drawImage(
        frame,
        Math.floor(csx),
        Math.floor(csy),
        Math.floor(csw),
        Math.floor(csh),
        0,
        0,
        out.width,
        out.height
      )
      return out.toDataURL('image/png')
    },

    // H5：用 window 级事件保证拖拽框选必然可用
    async ocrDoRecognize() {
      if (!this.ocrImageDataUrl || this.ocrLoading) return

      const imageData = this.ocrImageDataUrl // Cache data
      // Close overlay immediately
      this.closeOcrOverlay()

      this.ocrLoading = true // Should I use global loading? closeOcrOverlay resets ocrLoading.
      // Resetting ocrLoading via closeOcrOverlay is correct?
      // Wait, `closeOcrOverlay` resets `ocrText`, `ocrImageDataUrl`.

      // Since UI is gone, I should use `uni.showLoading`
      uni.showLoading({ title: '识别中…' })

      try {
        const res = await ocrRecognize(imageData)
        const text = (res?.data?.text || '').trim()
        if (text) {
          // Auto copy
          await this.insertClipboardAndCopy(text, { saveToHistory: true })
          uni.showToast({ title: '识别并复制成功', icon: 'success' })
        } else {
          uni.showToast({ title: '未识别到文字', icon: 'none' })
        }
      } catch (e) {
        console.error('OCR 识别失败:', e)
        if (e && e.featureNotConfigured) {
          // OCR 未配置：引导去设置而非报"识别失败"（#18 T7）
          promptFeatureNotConfigured(e)
        } else {
          uni.showToast({ title: e.message || '识别失败', icon: 'none' })
        }
      } finally {
        uni.hideLoading()
        this.ocrLoading = false
      }
    },

    // Old ocrDoDownload (Removed/Replaced)
    // ocrDoDownload() { ... }

    async ocrDoCopy() {
      if (!this.ocrImageDataUrl) return

      const dataUrl = this.ocrImageDataUrl

      // Close immediately
      this.closeOcrOverlay()

      // 1. Copy Image to Clipboard
      try {
          const blob = await (await fetch(dataUrl)).blob()

          // Use Clipboard API
          if (navigator.clipboard && navigator.clipboard.write) {
              const item = new ClipboardItem({ [blob.type]: blob })
              await navigator.clipboard.write([item])
              uni.showToast({ title: '已复制图片', icon: 'success' })
          } else {
              throw new Error('Clipboard API unavailable')
          }
      } catch (e) {
          console.error('Copy Image Failed', e)
          uni.showToast({ title: '复制失败', icon: 'none' })
      }
    },

    ocrDoOpenSaveDialog() {
       if (!this.ocrImageDataUrl) return

       // Cache data for dialog
       this.screenshotSaveDataUrl = this.ocrImageDataUrl

       // Close overlay immediately
       this.closeOcrOverlay()

       this.showScreenshotSaveDialog = true
       this.screenshotSaveName = `screenshot_${Date.now()}.png`
       this.screenshotSaveParentId = null // Root
       // Load folders
       this.loadScreenshotFolders()
    },

    async loadScreenshotFolders() {
        this.screenshotFolderTree = []
        try {
            const allFiles = await getProjectFiles(this.projectId, null, true)
            if (this.buildExportFolderTree) {
                 this.screenshotFolderTree = this.buildExportFolderTree(allFiles || [])
            }
        } catch (e) {
            console.error('加载文件夹失败', e)
        }
    },

    selectScreenshotFolder(id) {
        this.screenshotSaveParentId = id
    },

    closeScreenshotSaveDialog() {
        this.showScreenshotSaveDialog = false
        this.screenshotSaveDataUrl = ''
    },

    openImagePreview(url) {
        if (url) this.imagePreviewUrl = url
    },

    closeImagePreview() {
        this.imagePreviewUrl = ''
    },

    async confirmSaveScreenshot() {
        if (!this.screenshotSaveDataUrl) return
        let name = (this.screenshotSaveName || '').trim()
        if (!name) {
            uni.showToast({ title: '请输入文件名', icon: 'none' })
            return
        }
        if (!/\.(png|jpg|jpeg)$/i.test(name)) {
            name = `${name}.png`
        }

        this.screenshotSaveLoading = true
        try {
        const dataUrl = this.screenshotSaveDataUrl
        const res = await fetch(dataUrl)
        const blob = await res.blob()
        const fileSize = blob.size

        // 1. Create File Metadata
        // Auto-generate wpsFileId and proper fileType
        const timestamp = Date.now()
        const randomStr = Math.random().toString(36).substring(2, 9)
        const wpsFileId = `project_${this.projectId}_doc_${timestamp}_${randomStr}`
        const fileType = 'png' // Simplify to png for screenshots
        // Ensure name ends with .png
        if (!name.toLowerCase().endsWith('.png')) {
            name += '.png'
        }

        // Call backend to create metadata
        const metadata = await createFile(
            this.projectId,
            this.screenshotSaveParentId || null,
            name,
            fileType,
            fileSize,
            null, // filePath (backend handles)
            wpsFileId
        )

        if (!metadata || !metadata.id) {
            throw new Error('Failed to create file record')
        }

        // 2. Upload File Content
        const fileToUpload = new File([blob], name, { type: 'image/png' })
        const token = getSessionId()
        const baseUrl = getApiBaseUrl()

        await new Promise((resolve, reject) => {
             uni.uploadFile({
                 url: `${baseUrl}/api/files/${wpsFileId}/upload`,
                 name: 'file', // Param name expected by backend
                 file: fileToUpload,
                 header: {
                     'X-Session-Id': token || ''
                 },
                 success: (res) => {
                     if (res.statusCode >= 200 && res.statusCode < 300) {
                         resolve(res.data)
                     } else {
                         reject(new Error(`Upload failed: ${res.statusCode}`))
                     }
                 },
                 fail: (err) => reject(err)
             })
        })

        uni.showToast({ title: '保存成功', icon: 'success' })
        this.showScreenshotSaveDialog = false
        this.screenshotSaveDataUrl = ''
        // Refresh items: Switch to files pane and reload
        this.leftPaneKey = 'files'
        this.sidebarCollapsed = false
        this.$nextTick(() => {
             const ft = this.$refs.fileTree
             if (ft) {
                 if (this.screenshotSaveParentId) {
                     ft.expandedFolders.add(this.screenshotSaveParentId)
                 }
                 if (ft.loadFiles) {
                     ft.loadFiles()
                 }
             }
        })
    } catch (e) {
        console.error('保存失败', e)
        uni.showToast({ title: '保存失败: ' + (e.message || '未知错误'), icon: 'none' })
    } finally {
        this.screenshotSaveLoading = false
    }
    },

    async ocrDoInsert() {
      if (!this.ocrText) return
      await this.insertPlainTextToWps(this.ocrText)
    },

    async ocrDoRefreshSelection() {
      // 统一为：重新发起一次截图框选（比“刷新底图”更符合用户预期）
      try {
        this.closeOcrOverlay()
      } catch (e) {
        // ignore
      }
      await this.startOcrCapture()
    },

    async ocrDoFavorite() {
      if (!this.ocrImageDataUrl) return

      const imgData = this.ocrImageDataUrl
      const srcUrl = this.ocrSourceUrl
      // Close overlay immediately
      this.closeOcrOverlay()

      try {
        uni.showToast({ title: '正在加入收藏…', icon: 'loading', duration: 1200 })
        const created = await createProjectFavorite(this.projectId, {
          title: srcUrl ? srcUrl : '网页摘录',
          sourceUrl: srcUrl,
          content: '',
          imageBase64: imgData
        })
        const favId = created && created.id ? created.id : null
        // 立即给用户可见反馈：打开收藏夹并高亮新卡片
        this.showToolsPanel = true
        this.activeToolKey = 'favorites'
        this.$nextTick(async () => {
          try {
            const panel = this.$refs.favoritesPanel
            // 先等列表刷新完成，新卡片进入列表后再定位高亮，否则高亮必然落空
            if (panel && typeof panel.refresh === 'function') await panel.refresh()
            if (favId && panel && typeof panel.focusFavorite === 'function') panel.focusFavorite(Number(favId))
          } catch (e) {
            // ignore
          }
        })
        uni.showToast({ title: '收藏成功', icon: 'success' })
      } catch (e) {
        console.error('收藏失败:', e)
        uni.showToast({ title: e.message || '收藏失败', icon: 'none' })
      }
    },

    async ocrDoWebLink() {
      // 目标：将框选截图作为“网核证据”入库，并进入“拖拽关联到文档”模式
      if (this.ocrLoading) return
      if (!this.ocrImageDataUrl) {
        uni.showToast({ title: '请先框选区域', icon: 'none' })
        return
      }
      // 1) Cache State
      const sel = { ...this.ocrSel }
      const imgData = this.ocrImageDataUrl
      const srcUrl = this.ocrSourceUrl
      const lastPtr = this.ocrLastPointer ? { ...this.ocrLastPointer } : { x: 0, y: 0 }

      // 2) Close UI immediately
      this.closeOcrOverlay()

      uni.showLoading({ title: '处理中…' })

      try {
        // 1) 采集网页上下文
        const metaObj = {
          kind: 'webmark',
          capturedAt: new Date().toISOString(),
          sourceUrl: srcUrl || '',
          title: '',
          selection: {
            x1: sel.x1,
            y1: sel.y1,
            x2: sel.x2,
            y2: sel.y2
          }
        }
        // 补齐卡片展示需要的关键元信息（站点 / 关联文档）
        try {
          if (metaObj.sourceUrl) {
            metaObj.sourceHost = (() => { try { return new URL(metaObj.sourceUrl).host } catch (e) { return '' } })()
          }
          const activeDoc = this.focusedPane === 'right' ? this.activeFileRight : this.activeFileLeft
          metaObj.docFileName = activeDoc && this.isEditorOpenableFile && this.isEditorOpenableFile(activeDoc) ? (activeDoc.name || '') : ''
          metaObj.docSide = this.focusedPane || 'left'
        } catch (e) {
          // ignore
        }
        try {
          const active = this.focusedPane === 'right' ? this.activeFileRight : this.activeFileLeft
          const viewId = active && active.tabType === 'web' ? active.id : ''
          if (this.isDesktopApp && viewId && host.browser && host.browser.getSnapshot) {
            const snap = await host.browser.getSnapshot({ id: viewId })
            if (snap && snap.ok) {
              metaObj.sourceUrl = snap.url || metaObj.sourceUrl
              metaObj.title = snap.title || ''
              // 完整页面 HTML 快照
              metaObj.html = snap.html || ''
              if (!metaObj.sourceHost && metaObj.sourceUrl) {
                metaObj.sourceHost = (() => { try { return new URL(metaObj.sourceUrl).host } catch (e) { return '' } })()
              }
            }
          }
        } catch (e) {
          // ignore snapshot failure
        }

        const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        const title = metaObj.title || (metaObj.sourceUrl ? (() => { try { return new URL(metaObj.sourceUrl).host } catch (e) { return '网核' } })() : '网核')

        // 2) 入库
        const res = await createProjectFavorite(pid, {
          title,
          sourceUrl: metaObj.sourceUrl,
          content: '',
          imageBase64: imgData,
          meta: JSON.stringify(metaObj)
        })
        const saved = res && res.id ? res : (res && res.data ? res.data : null)
        const favId = saved && saved.id ? saved.id : null

        if (this.$refs.favoritesPanel && typeof this.$refs.favoritesPanel.refresh === 'function') {
          this.$refs.favoritesPanel.refresh()
        }

        // 3) 进入拖拽模式（把证据块拖到 WPS 插入标记）
        this.startWebLinkDrag({
          favoriteId: favId,
          imageDataUrl: imgData,
          sourceUrl: metaObj.sourceUrl,
          title,
          docFileName: metaObj.docFileName || '',
          x: lastPtr.x,
          y: lastPtr.y
        })
      } catch (e) {
        console.error('网核关联失败:', e)
        uni.showToast({ title: e.message || '网核关联失败', icon: 'none' })
      } finally {
        uni.hideLoading()
      }
    },

}
