// project-overview.vue 的剪贴板捕获桥：桌面端走 Electron 主进程推送，H5 走
// copy/paste/keydown 三路兜底监听；TEXT/IMAGE/FILE 三类载荷统一经 recordClipboardOnce 入库。
// 去重状态挂 window 而非组件实例——本页经 navigateTo 反复进入时页面栈里存在多个实例，
// 实例级去重挡不住"一次复制、多实例各入库一条"（见 PR#148/#151）。
// 含 #ifdef H5 条件编译：uni 预处理器对 src/pages/**.js 同样生效（已实测验证）。
// 经展开进组件 methods（纯搬移，Phase 3a 外置），`this` 即 project-overview 页面实例。

import { saveClipboardText, saveClipboardFile } from '@/services/api.js'
import { getCurrentUser } from '@/utils/auth.js'
import { host } from '@/services/host.js'

export const clipboardBridgeMethods = {
    bindClipboardListener() {
      // #ifdef H5
      if (this._clipboardBound) return
      const user = getCurrentUser()
      if (!user) return
      this._clipboardBound = true
      // 统一的“写库 + 立即更新 UI”入口：避免 copy 事件多次触发导致重复入库
      // 去重状态挂 window 而非组件实例：本页经 navigateTo 反复进入时页面栈里会存在
      // 多个实例，每个实例都绑定过监听；实例级去重挡不住“一次复制、多实例各入库一条”
      if (typeof window !== 'undefined' && !window.__checkbaClipLastText) {
        window.__checkbaClipLastText = { ts: 0, text: '' }
      }
      const recordClipboardOnce = async (rawText, source = 'doc') => {
        // 1. Electron Payload Object (IMAGE / FILE)
        if (rawText && typeof rawText === 'object') {
           const payload = rawText
           // 同一事件（ts 相同）会被页面栈里每个实例的监听器各收到一次，只处理第一次；
           // 图片/文件没有下方文本那样的内容去重，全靠这里挡住重复入库
           const evKey = String(payload.type || '') + '_' + String(payload.ts || '')
           if (typeof window !== 'undefined') {
             if (window.__checkbaClipLastEventKey === evKey) return null
             window.__checkbaClipLastEventKey = evKey
           }
           if (payload.type === 'TEXT') {
             return await recordClipboardOnce(payload.text, source)
           } else if (payload.type === 'IMAGE' && payload.data) {
             try {
               const arr = payload.data.split(',')
               const match = arr[0].match(/:(.*?);/)
               const mime = match ? match[1] : 'image/png'
               // Decode base64
               const bstr = atob(arr[1])
               let n = bstr.length
               const u8arr = new Uint8Array(n)
               while (n--) { u8arr[n] = bstr.charCodeAt(n) }
               const blob = new Blob([u8arr], { type: mime })

               // Create File object
               const f = new File([blob], `image_${Date.now()}.png`, { type: mime })

               const res = await saveClipboardFile({ file: f }, 'IMAGE')
               const saved = (res && res.data) ? res.data : res
               this.onClipboardSaved(saved)
               uni.showToast({ title: '已捕获图片', icon: 'success' })
               return saved
             } catch (e) {
               console.error('Image upload failed', e)
               return null
             }
           } else if (payload.type === 'FILE' && payload.filePath) {
             try {
               // Must verify API exists (Electron only)
                // eslint-disable-next-line
               if (host.utils && host.utils.readFile) {
                  // eslint-disable-next-line
                  const resp = await host.utils.readFile(payload.filePath)
                  if (resp && resp.ok && resp.data) {
                     // resp.data is usually Uint8Array or serialized Buffer
                     const u8arr = new Uint8Array(resp.data)

                     const name = payload.filePath.split(/[/\\]/).pop() || 'file'
                     const blob = new Blob([u8arr])
                     const f = new File([blob], name)


                     const res = await saveClipboardFile({ file: f }, 'FILE')
                     const saved = (res && res.data) ? res.data : res
                     this.onClipboardSaved(saved)
                     uni.showToast({ title: '已捕获文件', icon: 'success' })
                     return saved
                  }
               }
             } catch (e) {
               console.error('File upload failed', e)
             }
             return null
           }
           return null
        }

        // 2. Normal Text Logic
        let t = (rawText || '').trim()
        if (
          !t &&
          typeof navigator !== 'undefined' &&
          navigator.clipboard &&
          typeof navigator.clipboard.readText === 'function'
        ) {
          try {
            const latest = await navigator.clipboard.readText()
            t = (latest || '').trim()
          } catch (clipErr) {
            // ignore permission errors
          }
        }
        if (!t) return null

        const now = Date.now()
        // 仅用于防止同一次用户动作被多路监听重复触发（不是业务去重）
        const lastText = (typeof window !== 'undefined' && window.__checkbaClipLastText) || { ts: 0, text: '' }
        if (lastText.text === t && now - (lastText.ts || 0) < 600) {
          return null
        }
        if (typeof window !== 'undefined') {
          window.__checkbaClipLastText = { ts: now, text: t, source }
        }

        try {
          const res = await saveClipboardText(t)
          const saved = (res && res.data) ? res.data : res
          this.onClipboardSaved(saved)
          return saved
        } catch (saveErr) {
          console.error('记录剪贴板失败:', saveErr)
          return null
        }
      }
      this._recordClipboardOnce = recordClipboardOnce

      // Desktop：由 Electron 主进程捕获 copy/cut，并直接推送剪贴板文本（更稳定，不依赖浏览器权限）
      if (this.isDesktopApp && host.clipboard) {
        try {
          if (!this._desktopClipboardUnsub) {
            this._desktopClipboardUnsub = host.clipboard.onCopied(async (payload) => {
              try {
                // Pass full payload object to support IMAGE/FILE
                await recordClipboardOnce(payload, 'desktop')
              } catch (e) {
                // ignore
              }
            })
          }
        } catch (e) {
          // ignore
        }
        return
      }

      this._pasteHandler = async (e) => {
        try {
          const cd = e && e.clipboardData
          const text = cd && typeof cd.getData === 'function' ? (cd.getData('text/plain') || '') : ''
          await recordClipboardOnce(text, 'paste')
        } catch (err) {
          // ignore
        }
      }

      this._copyHandler = async (e) => {
        try {
          let text = ''
          const cd = e && e.clipboardData
          if (cd && typeof cd.getData === 'function') {
            text = cd.getData('text/plain') || ''
          }
          if (!text && typeof window !== 'undefined' && window.getSelection) {
            const selection = window.getSelection()
            text = selection ? selection.toString() : ''
          }
          await recordClipboardOnce(text, 'copy')
        } catch (err) {
          // ignore
        }
      }

      // 键盘兜底：覆盖部分“网页内复制/iframe 内复制”导致外层收不到 copy 事件的场景（best-effort）
      this._clipboardKeydownHandler = async (e) => {
        try {
          const key = e && (e.key || '')
          const isCopy = (key === 'c' || key === 'C') && (e.metaKey || e.ctrlKey)
          if (!isCopy) return
          // 不从事件里取文本，直接尝试读剪贴板（权限失败则忽略）
          await recordClipboardOnce('', 'keydown')
        } catch (err) {
          // ignore
        }
      }

      document.addEventListener('paste', this._pasteHandler)
      document.addEventListener('copy', this._copyHandler, true)
      window.addEventListener('keydown', this._clipboardKeydownHandler, true)
      // #endif
    },
    onClipboardSaved(item) {
      // 1) 面板打开时：立即新增一张卡片（不等刷新）
      if (this.$refs.clipboardPanel && typeof this.$refs.clipboardPanel.prependItem === 'function') {
        this.$refs.clipboardPanel.prependItem(item, 80)
      } else {
        this.pendingClipboardRefresh = true
      }
      // 2) 兜底：如果面板当前可见，做一次 refresh 对齐服务端（避免时间/格式差异）
      this.triggerClipboardRefresh()
    },
    triggerClipboardRefresh() {
      if (!this.pendingClipboardRefresh) return
      // 仅在剪贴板面板已渲染时刷新；否则保持 pending，等用户切到剪贴板再刷新
      const panel = this.$refs.clipboardPanel
      if (panel && typeof panel.refresh === 'function') {
        this.pendingClipboardRefresh = false
        try {
          panel.refresh()
        } catch (e) {
          // ignore
        }
      }
    },
    unbindClipboardListener() {
      // #ifdef H5
      try {
        if (this._desktopClipboardUnsub) this._desktopClipboardUnsub()
      } catch (e) {
        // ignore
      }
      this._desktopClipboardUnsub = null
      if (this._pasteHandler) {
        document.removeEventListener('paste', this._pasteHandler)
      }
      if (this._copyHandler) {
        document.removeEventListener('copy', this._copyHandler, true)
      }
      if (this._clipboardKeydownHandler) {
        window.removeEventListener('keydown', this._clipboardKeydownHandler, true)
      }
      this._pasteHandler = null
      this._copyHandler = null
      this._clipboardKeydownHandler = null
      this._recordClipboardOnce = null
      this._clipboardBound = false
      // #endif
    },
}
