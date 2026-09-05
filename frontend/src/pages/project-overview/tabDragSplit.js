// project-overview.vue 的标签页拖拽与分栏布局：tab 跨窗格拖拽（跨窗格是"双开"不是"移动"）、
// 三向面板拖拽改尺寸（rAF 节流 + 直接改 DOM，停手时才同步回 Vue 状态）、分屏开关与窗格聚焦。
// 经展开进组件 methods（纯搬移，Phase 2 外置），`this` 即 project-overview 页面实例。

import { activityTracker } from '@/utils/activityTracker.js'
import { rightPanelMaxWidth, leftPanelMaxWidth } from './panelWidthLimits.js'

export const tabDragSplitMethods = {
    onTabDragStart(evt, file, fromPane) {
      this.draggingTab = { fileId: file.id, fromPane }
      this.tabDragOver = null
      try {
        if (evt && evt.dataTransfer) {
          evt.dataTransfer.effectAllowed = 'move'
          evt.dataTransfer.setData('application/json', JSON.stringify({ fileId: file.id, fromPane }))
        }
      } catch (e) {
        // ignore
      }
    },

    onTabDragOver(_evt, file, pane) {
      this.tabDragOver = { fileId: file.id, pane }
    },

    onTabDropZoneDragOver(pane) {
      // 空白区域的 hover 提示（只记录 pane，避免 tab-drag-over 误高亮）
      if (!this.draggingTab) return
      this.tabDragOver = null
      // 预激活：拖到右侧区域时，先聚焦右侧，避免“未激活窗格无法接收拖拽”的体验
      if (pane === 'right' && this.splitMode) {
        this.focusedPane = 'right'
      }
    },

    onTabDropOnZone(evt, targetPane) {
      const payload = this.getTabDragPayload(evt) || this.draggingTab
      if (!payload || !payload.fileId) return
      // drop 到空白区域：插入到末尾
      this.moveTabTo(payload.fileId, payload.fromPane, targetPane, null)
      this.onTabDragEnd()
    },

    onTabDropOnItem(evt, targetFile, targetPane) {
      const payload = this.getTabDragPayload(evt) || this.draggingTab
      if (!payload || !payload.fileId) return

      this.moveTabTo(payload.fileId, payload.fromPane, targetPane, targetFile.id)
      this.onTabDragEnd()
    },

    onTabsWheel(evt) {
      // VS Code 式标签栏：滚动条整条隐藏，纵向滚轮映射为横向滚动。
      // scroll-view 真正 overflow 的是 uni-h5 渲染出的内层元素，不是根元素本身，按 scrollWidth 找。
      const root = evt?.currentTarget
      if (!root || typeof root.querySelectorAll !== 'function') return
      let scroller = null
      if (root.scrollWidth > root.clientWidth) scroller = root
      if (!scroller) {
        for (const el of root.querySelectorAll('*')) {
          if (el.scrollWidth > el.clientWidth + 1) { scroller = el; break }
        }
      }
      if (!scroller) return
      const delta = Math.abs(evt.deltaX) > Math.abs(evt.deltaY) ? evt.deltaX : evt.deltaY
      scroller.scrollLeft += delta
    },

    getTabDragPayload(evt) {
      try {
        const raw = evt?.dataTransfer?.getData('application/json')
        if (!raw) return null
        return JSON.parse(raw)
      } catch (e) {
        return null
      }
    },

    moveTabTo(fileId, fromPane, toPane, beforeFileId) {
      if (!fileId || !fromPane || !toPane) return
      if (!this.splitMode && toPane === 'right') return

      const fromList = fromPane === 'left' ? this.leftFiles : this.rightFiles
      const toList = toPane === 'left' ? this.leftFiles : this.rightFiles

      const fromIdx = fromList.findIndex(f => f.id === fileId)
      if (fromIdx < 0) return

      const source = fromList[fromIdx]
      // 关键修复：跨窗格拖拽不“移动”，而是“在另一侧打开同一文件”（允许左右双开）
      const isCrossPane = fromPane !== toPane
      const moved = isCrossPane ? { ...source } : fromList.splice(fromIdx, 1)[0]

      // 目标索引：插入到 beforeFileId 前面（如果没有则追加末尾）
      let toIdx = -1
      if (beforeFileId) {
        toIdx = toList.findIndex(f => f.id === beforeFileId)
      }
      // 若目标窗格已存在同文件，则仅激活，不重复插入
      const existedIdx = toList.findIndex(f => f.id === moved.id)
      if (existedIdx >= 0) {
        // 如果目标还指定了 beforeFileId 且是同窗格换序，可以做排序调整
        if (!isCrossPane && beforeFileId && existedIdx !== toIdx && toIdx >= 0) {
          const [existing] = toList.splice(existedIdx, 1)
          toList.splice(toIdx, 0, existing)
        }
      } else {
        if (toIdx < 0) {
          toList.push(moved)
        } else {
          toList.splice(toIdx, 0, moved)
        }
      }

      // 激活态跟随：如果是跨窗格移动，更新 activeFileId
      if (toPane === 'left') {
        this.activeFileIdLeft = moved.id
        this.focusedPane = 'left'
      } else {
        this.activeFileIdRight = moved.id
        this.focusedPane = 'right'
      }

      // 拖拽换序/跨窗格会改变容器尺寸分配，给 WPS 一个 resize
      this.$nextTick(() => this.triggerWorkbenchResize())
    },

    onTabDragEnd() {
      this.draggingTab = null
      this.tabDragOver = null
    },

    isOpenInOtherPane(fileId, pane) {
      if (!fileId) return false
      if (pane === 'left') return this.rightFiles.some(f => f.id === fileId)
      return this.leftFiles.some(f => f.id === fileId)
    },

    startResize(target, evt) {
      // target: 'left' | 'right' | 'bottom'
      this.resizing.active = true
      this.resizing.target = target
      const e = evt && evt.touches && evt.touches[0] ? evt.touches[0] : evt
      this.resizing.startX = e?.clientX || 0
      this.resizing.startY = e?.clientY || 0
      this.resizing.startSidebarWidth = this.sidebarWidth
      this.resizing.startAiWidth = this.aiPanelWidth
      this.resizing.startToolsHeight = this.toolsPanelHeight

      // Cache DOM element for direct manipulation
      this.resizing.element = null
      if (target === 'left') {
        this.resizing.element = this.$refs.sidebarLeft ? (this.$refs.sidebarLeft.$el || this.$refs.sidebarLeft) : null
      } else if (target === 'right') {
        this.resizing.element = this.$refs.aiPanel ? (this.$refs.aiPanel.$el || this.$refs.aiPanel) : null
      } else if (target === 'bottom') {
         this.resizing.element = this.$refs.bottomPanel ? (this.$refs.bottomPanel.$el || this.$refs.bottomPanel) : null
      }

      // 拖拽上限按面板真正所在的容器算（dev-board#459），几何在这里实测一次：
      // 右侧面板的父容器是 .workbench-main（编辑区 + 面板），左栏的父容器是
      // .main-layout（rail + 左栏 + workbench）。不写 rail 宽这类常量——边框、
      // 紧凑模式、右侧 dock 都会让常量漂。
      this.resizing.containerWidth = 0
      this.resizing.railWidth = 0
      this.resizing.otherPanelWidth = 0
      const container = this.resizing.element ? this.resizing.element.parentElement : null
      if (container) {
        this.resizing.containerWidth = container.clientWidth || 0
        if (target === 'left' && typeof container.querySelector === 'function') {
          const rail = container.querySelector('.left-rail')
          this.resizing.railWidth = rail ? rail.offsetWidth : 0
          const aiPanel = container.querySelector('.side-panel-ai')
          this.resizing.otherPanelWidth = aiPanel ? aiPanel.offsetWidth : 0
        }
      }

      if (evt && typeof evt.preventDefault === 'function') {
        evt.preventDefault()
      }

      // 拖拽期间锁全局光标与选区：不锁的话光标滑过文本/按钮会闪回默认形状，
      // 且快速拖动会顺手拖出一片文本选区
      if (typeof document !== 'undefined' && document.body) {
        document.body.style.cursor = target === 'bottom' ? 'row-resize' : 'col-resize'
        document.body.style.userSelect = 'none'
        document.body.style.webkitUserSelect = 'none'
      }

      if (typeof window !== 'undefined') {
        if (!this.boundResizeMove) this.boundResizeMove = (e2) => this.onResizeMove(e2)
        if (!this.boundStopResize) this.boundStopResize = () => this.stopResize()

        window.addEventListener('mousemove', this.boundResizeMove, { passive: false })
        window.addEventListener('mouseup', this.boundStopResize, { passive: true })
        window.addEventListener('touchmove', this.boundResizeMove, { passive: false })
        window.addEventListener('touchend', this.boundStopResize, { passive: true })
        // 兜底：鼠标移出窗口/窗口失焦时 mouseup 可能丢失，避免 is-resizing 卡死
        window.addEventListener('blur', this.boundStopResize, { passive: true })
      }
    },

    onResizeMove(evt) {
      if (!this.resizing.active) return
      const e = evt && evt.touches && evt.touches[0] ? evt.touches[0] : evt
      const clientX = e?.clientX || 0
      const clientY = e?.clientY || 0

      // 防止页面滚动
      if (evt && typeof evt.preventDefault === 'function') {
        evt.preventDefault()
      }

      // rAF 节流：让拖拽跟手更稳（避免 move 事件过密导致抖动/延迟）
      this._resizePendingX = clientX
      this._resizePendingY = clientY
      if (this._resizeRaf) return
      this._resizeRaf = requestAnimationFrame(() => {
        this._resizeRaf = null
        this.applyResizeFrame()
      })
    },

    // rAF 帧体：把 pending 的光标位置落到面板尺寸上。单独成方法是因为 stopResize
    // 也要调它——mouseup 常常赶在 rAF 回调前到达，不冲刷这最后一拍，快速拖动
    // 松手时最后一段位移会被整个丢掉（表现就是「不跟手、松手回弹一截」）。
    applyResizeFrame() {
      if (!this.resizing.target) return
      {
        const vw = typeof window !== 'undefined' ? window.innerWidth : 1200
        const vh = typeof window !== 'undefined' ? window.innerHeight : 800

        // Cursor 体验：拖动范围尽量大，只给编辑区留最后一点可用宽（dev-board#459）。
        // 上限按 startResize 实测到的容器宽算，不按整窗宽算——整窗里还有 rail、左栏
        // 与另一侧面板，按 window.innerWidth 限宽会把面板推出容器右缘，被
        // .workbench{overflow:hidden} 裁在窗口外面。量不到容器时退回整窗宽兜底。
        // 「遮挡就遮挡」的既有取向保留：这里不做窗口变窄时的回夹。
        const containerW = this.resizing.containerWidth || vw
        const leftMin = 160
        const leftMax = leftPanelMaxWidth(
          containerW, this.resizing.railWidth, this.resizing.otherPanelWidth, leftMin)
        const rightMin = 240
        const rightMax = rightPanelMaxWidth(containerW, rightMin)
        const headerH = 56
        const tabsH = 40
        const bottomMin = 140
        const bottomMax = Math.max(bottomMin, Math.floor((vh - headerH - tabsH) * 0.85))

        const dx = (this._resizePendingX || 0) - this.resizing.startX
        const dy = (this._resizePendingY || 0) - this.resizing.startY

        let finalValue = 0

        if (this.resizing.target === 'left') {
          const next = this.resizing.startSidebarWidth + dx
          finalValue = Math.max(leftMin, Math.min(leftMax, next))
          // Direct DOM update
          if (this.resizing.element) {
              this.resizing.element.style.width = finalValue + 'px'
          }
          // Store for sync on stop
          this.resizing.currentValue = finalValue
        } else if (this.resizing.target === 'right') {
          const next = this.resizing.startAiWidth - dx
          finalValue = Math.max(rightMin, Math.min(rightMax, next))
           // Direct DOM update
           if (this.resizing.element) {
              this.resizing.element.style.width = finalValue + 'px'
          }
          this.resizing.currentValue = finalValue
        } else if (this.resizing.target === 'bottom') {
          const next = this.resizing.startToolsHeight - dy
          finalValue = Math.max(bottomMin, Math.min(bottomMax, next))
           // Direct DOM update
           if (this.resizing.element) {
              this.resizing.element.style.height = finalValue + 'px'
          }
           this.resizing.currentValue = finalValue
        }
      }
    },

    stopResize() {
      if (!this.resizing.active) return

      // 冲刷最后一拍：mouseup 赶在 rAF 前到时，pending 位移还没落上去
      if (this._resizeRaf) {
        cancelAnimationFrame(this._resizeRaf)
        this._resizeRaf = null
        this.applyResizeFrame()
      }

      // Save target and currentValue BEFORE nullifying
      const target = this.resizing.target
      const finalValue = this.resizing.currentValue

      this.resizing.active = false
      this.resizing.target = null
      this.resizing.element = null
      this.resizing.currentValue = null

      if (typeof document !== 'undefined' && document.body) {
        document.body.style.cursor = ''
        document.body.style.userSelect = ''
        document.body.style.webkitUserSelect = ''
      }

      if (typeof window !== 'undefined') {
        if (this.boundResizeMove) {
          window.removeEventListener('mousemove', this.boundResizeMove)
          window.removeEventListener('touchmove', this.boundResizeMove)
        }
        if (this.boundStopResize) {
          window.removeEventListener('mouseup', this.boundStopResize)
          window.removeEventListener('touchend', this.boundStopResize)
          window.removeEventListener('blur', this.boundStopResize)
        }
      }

      // Sync final value to Vue state using saved target
      if (finalValue) {
          if (target === 'left') {
              this.sidebarWidth = finalValue
          } else if (target === 'right') {
              this.aiPanelWidth = finalValue
          } else if (target === 'bottom') {
              this.toolsPanelHeight = finalValue
          }
      }

      this.$nextTick(() => this.triggerWorkbenchResize())
    },

    toggleSplitMode() {
      this.splitMode = !this.splitMode

      // 关键修复：触发 resize 事件通知 WPS SDK 调整布局
      // WPS SDK 监听 window resize 来调整内部 iframe 大小
      this.$nextTick(() => this.triggerWorkbenchResize())

      if (!this.splitMode) {
        // 关闭分屏时，重置 focus 到左侧
        this.focusedPane = 'left'
      } else {
        // 开启分屏时，默认聚焦右侧，方便用户立即选择文件
        this.focusedPane = 'right'
      }
    },
    focusPane(pane) {
      // 只有在分屏模式下才允许聚焦右侧
      if (!this.splitMode && pane === 'right') return

      const oldPane = this.focusedPane
      this.focusedPane = pane

      if (oldPane !== pane) {
          // Switch active tracking to the file in the new pane
          const file = pane === 'left' ? this.activeFileLeft : this.activeFileRight
          if (file) {
              const meta = this.project && this.project.name ? `Project: ${this.project.name}` : ''
              if (this.isBrowserTab(file)) {
                   const url = file.url || ''
                   const title = file.name || ''
                   const fullMeta = meta + (title ? `. Title: ${title}` : '')
                   activityTracker.trackActivePage('OPEN_URL', 0, url, this.project && this.project.id, fullMeta)
              } else {
                   activityTracker.trackActivePage('OPEN_FILE', file.id, file.name, this.project && this.project.id, meta)
              }
          }
      }
    },
}
