// project-overview.vue 的文件打开与标签页生命周期：从文件树/搜索/AI 对话打开文件、
// 标签激活与 FileTree 选中同步、关闭（Office 文档出池前先落盘）、可打开性判定与文档对比标签。
// 经展开进组件 methods（纯搬移，Phase 2 外置），`this` 即 project-overview 页面实例。

import { getProjectFiles } from '@/services/api.js'
import { activityTracker } from '@/utils/activityTracker.js'
import { ICONS as GLYPHS, fileGlyph } from '@/config/icons.js'

export const fileOpenTabsMethods = {
    handleFileTreeSelect(file) {
      if (!file || file.isFolder) return
      this.openFile(file)
    },

    // Handle file open from SearchPanel
    handleSearchOpenFile(file) {
      if (!file) return
      console.log('[project-overview] Open file from search:', file)
      this.openFile({
        id: file.id,
        wpsFileId: file.wpsFileId,
        name: file.name,
        fileType: file.fileType,
        filePath: file.filePath
      })
    },

    // Handle file open request from ChatInterface (file changes popup)
    async handleOpenFileFromChat({ name }) {
      if (!name) return
      console.log('[project-overview] Open file from chat:', name)

      // Refresh project files first to ensure we have latest
      try {
        const resp = await getProjectFiles(this.projectId)
        // Normalize response: API returns { code: 0, data: [...] } or possibly just array
        const files = Array.isArray(resp) ? resp : (resp?.data || [])
        console.log('[project-overview] Got files for search:', files.length)

        // Find file by name (case-insensitive, match basename)
        const targetFile = files.find(f => {
          if (f.isFolder) return false
          // Match exact name or name without extension
          return f.name === name || f.name.toLowerCase() === name.toLowerCase()
        })

        if (targetFile) {
          console.log('[project-overview] Found file:', targetFile.id, targetFile.name)
          this.openFile(targetFile)
        } else {
          console.warn('File not found:', name, 'in', files.map(f => f.name))
          uni.showToast({ title: '未找到文件: ' + name, icon: 'none' })
        }
      } catch (e) {
        console.error('Failed to fetch files for open:', e)
        uni.showToast({ title: '获取文件列表失败', icon: 'none' })
      }
    },

    openFile(file) {
      // 检查文件类型是否支持打开
      if (!this.isFileTypeSupported(file)) {
        uni.showModal({
          title: '无法打开文件',
          content: `暂不支持打开此类型文件：${file.name}\n\n文件类型：${file.fileType || '无后缀名'}\n\n支持的文件类型：\n• 文档：doc, docx, xls, xlsx, ppt, pptx, pdf\n• 图片：jpg, jpeg, png, gif, bmp, webp, svg\n• 视频：mp4, webm, ogg, mov, mkv, avi\n• 音频：mp3, wav, m4a, flac, aac\n• 文本：txt, md, json, xml, html等`,
          showCancel: false,
          confirmText: '我知道了',
          success: (res) => {
            if (res.confirm) {
              console.log('用户确认无法打开文件')
            }
          }
        })
        return
      }

      const meta = this.project && this.project.name ? `Project: ${this.project.name}` : ''
      // Start session tracking for this file
      activityTracker.trackActivePage('OPEN_FILE', file.id, file.name, meta)

      // 1. 如果已经在某个 pane 打开，则聚焦该 pane
      const existingLeft = this.leftFiles.find(f => f.id === file.id)
      // - 如果当前聚焦窗格未打开该文件，则在当前窗格打开
      // - 若当前窗格已打开，则仅激活
      const targetPane = this.splitMode ? this.focusedPane : 'left'
      const targetList = targetPane === 'left' ? this.leftFiles : this.rightFiles
      const targetIdProp = targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'

      const existing = targetList.find(f => f.id === file.id)
      if (existing) {
        Object.assign(existing, file)
        this[targetIdProp] = file.id
        this.focusedPane = targetPane
      } else {
        targetList.push({ ...file })
        this[targetIdProp] = file.id
      }

      // Persist active ID for current mode
      const mode = this.leftPaneKey || 'files'
      this.lastActiveIdsByMode[targetPane][mode] = file.id
      this.saveActiveIdsByMode()

      // 打开/激活后，给 WPS 一个机会刷新（避免容器尺寸/激活状态不对）
      this.$nextTick(() => this.triggerWorkbenchResize())
    },

    activateTab(file, pane) {
      // 点击 Tab 时，切换对应窗格的激活文件，并聚焦该窗格
      this.focusPane(pane)
      if (pane === 'left') {
        this.activeFileIdLeft = file.id
      } else {
        this.activeFileIdRight = file.id
      }

      // Persist active ID for current mode
      const mode = this.leftPaneKey || 'files'
      this.lastActiveIdsByMode[pane][mode] = file.id
      this.saveActiveIdsByMode()

      // 同步更新 FileTree 的选中状态
      // 如果是文件类型（非浏览器标签、非特殊标签类型），需要更新资源管理器的选中状态
      if (!this.isBrowserTab(file) && file.id && !file.tabType) {
        // 展开侧边栏并切换到文件模式
        if (this.sidebarCollapsed) {
          this.sidebarCollapsed = false
        }
        if (this.leftPaneKey !== 'files') {
          this.leftPaneKey = 'files'
        }

        // 确保在下一个 tick 中执行，此时 FileTree 组件已经更新
        this.$nextTick(() => {
          if (this.$refs.fileTree) {
            // 使用 revealFile 方法来定位并选中文件（会展开父目录并滚动到文件）
            if (this.$refs.fileTree.revealFile) {
              this.$refs.fileTree.revealFile(file.id)
            } else {
              // 如果没有 revealFile 方法，直接设置 selectedFileId
              this.$refs.fileTree.selectedFileId = file.id
            }
          }
        })
      } else {
        // 如果是浏览器标签或其他特殊类型，清空 FileTree 的选中状态
        this.$nextTick(() => {
          if (this.$refs.fileTree) {
            this.$refs.fileTree.selectedFileId = null
            this.$refs.fileTree.multiSelectedIds = []
          }
        })
      }

      // Track switch
      const meta = this.project && this.project.name ? `Project: ${this.project.name}` : ''
      if (this.isBrowserTab(file)) {
          // If browser tab, we need to track URL session
          const url = file.url || ''
          const title = file.name || ''
          const fullMeta = meta + (title ? `. Title: ${title}` : '')
          activityTracker.trackActivePage('OPEN_URL', 0, url, fullMeta)
      } else {
          // File
          activityTracker.trackActivePage('OPEN_FILE', file.id, file.name, meta)
      }
    },

    handleFileDeleted(payload) {
      if (!payload || !payload.ids || !Array.isArray(payload.ids)) return
      const ids = new Set(payload.ids)

      // Close tabs if they match deleted IDs
      // Iterate backwards to avoid index issues when closing (though closeFile uses findIndex, safety first)
      
      // Right Pane
      for (let i = this.rightFiles.length - 1; i >= 0; i--) {
        if (ids.has(this.rightFiles[i].id)) {
           this.closeFile(this.rightFiles[i].id, 'right')
        }
      }
      
      // Left Pane
      for (let i = this.leftFiles.length - 1; i >= 0; i--) {
        if (ids.has(this.leftFiles[i].id)) {
           this.closeFile(this.leftFiles[i].id, 'left')
        }
      }
    },

    async closeFile(fileId, pane) {
      const list = pane === 'left' ? this.leftFiles : this.rightFiles
      const idProp = pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'

      let idx = list.findIndex(f => f.id === fileId)
      if (idx === -1) return

      const file = list[idx]

      // (autosave) Office 文档出池即卸载，卸载后 webview 没了就没法导出——
      // 有未保存修改（或保存在途）的实例先落盘再关。加载失败的实例跳过
      // （画布是空白原型，保存会覆盖真文件，同 evictLibreInstance）。
      if (file && this.useLibreEditor(file)) {
        const inst = (this._libreRefs || {})[pane + ':' + fileId]
        if (inst && inst.ready && !inst.isError && inst.file && (inst.dirty || inst.saving)) {
          try { await inst.flushSave() } catch (e) { console.warn('[ProjectOverview] close flush-save failed:', e) }
        }
        // 落盘期间列表可能已变（并发关闭）——重新定位，已被移除则到此为止
        idx = list.findIndex(f => f.id === fileId)
        if (idx === -1) return
      }
      const activeId = this[idProp]

      // If closing the active file/tab, the activityTracker.trackActivePage in activateTab or openFile will handle the switch.
      // But if we close the *currently active* file and no other file becomes active (e.g. empty list), we should stop session?
      // Actually, if we close active file, we usually switch to another one (logic below).
      // So we don't need to manually stop session here, UNLESS the list becomes empty.

      // const meta = this.project && this.project.name ? `Project: ${this.project.name}` : ''
      // activityTracker.logAction('CLOSE_FILE', file.id, file.name, 0, meta)

      list.splice(idx, 1)

      // 如果关闭的是当前激活的文件，尝试切换到临近的文件
      if (activeId === fileId) {
        const newActiveId = list.length > 0
          ? list[Math.min(idx, list.length - 1)].id
          : null
        this[idProp] = newActiveId

        // Update persisted record
        const mode = this.leftPaneKey || 'files'
        this.lastActiveIdsByMode[pane][mode] = newActiveId
        this.saveActiveIdsByMode()
      }
    },

    /** 标签页图标的 SVG path 集合（界面禁用 emoji，图标一律 stroke 线性 SVG） */
    getFileIconPaths(type, tabType) {
      return tabType === 'web' ? GLYPHS.web : fileGlyph(type)
    },
    isFileTypeSupported(file) {
      if (!file || file.isFolder) return true
      if (!file.fileType) return false

      const type = file.fileType.toLowerCase()

      // 支持的文件类型列表
      const supportedTypes = [
        // Office文档
        'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'pdf',
        // 图片
        'jpg', 'jpeg', 'png', 'gif', 'bmp', 'svg', 'webp',
        // 视频
        'mp4', 'webm', 'ogg', 'mov', 'mkv', 'avi',
        // 音频
        'mp3', 'wav', 'm4a', 'flac', 'aac',
        // 文本文件
        'txt', 'md', 'markdown', 'json', 'xml', 'html', 'css', 'js', 'java', 'py', 'sh', 'sql', 'log',
        // 压缩包（FilePreview 条目预览 + 解压）
        'zip', 'rar', '7z',
        // 尽调清单
        'dd'
      ]

      return supportedTypes.includes(type)
    },
    isEditorOpenableFile(file) {
      // 判断是否为「文档编辑器可打开」的 Office 类文件
      if (!file || file.tabType === 'web' || file.tabType === 'markdown' || !file.fileType) return false

      const type = file.fileType.toLowerCase()

      // 1. Force native preview for media types (Images, Video, Audio)
      const mediaTypes = [
          // Images
          'jpg','jpeg','png','gif','bmp','svg','webp',
          // Video
          'mp4','webm','ogg','mov','mkv','avi',
          // Audio
          'mp3','wav','m4a','flac','aac'
      ]
      if (mediaTypes.includes(type)) return false

      // 2. 排除 Markdown 文件，使用专门的 Markdown 预览组件
      if (type === 'md' || type === 'markdown') return false

      // 3. Office 文档格式 —— 仅限自建 LOWA 引擎真正编入的模块（probe_modules
      // 实测：仅 Writer + Calc；Impress/Draw 已裁）。
      // - Presentation 一律走 FilePreview（pptx = pptx-preview 前端渲染）；
      // - PDF 走 FilePreview 的 Chromium 原生渲染——LOWA 无 Draw 时 Writer 会把
      //   PDF 二进制当文本导入，满屏乱码（2026-07 真机截图证实）。
      const wpsFormats = [
          // Writer
          'wps', 'wpt', 'doc', 'dot', 'docx', 'dotx', 'docm', 'dotm', 'rtf', 'odt',
          // Spreadsheet
          'et', 'ett', 'ets', 'xls', 'xlsx', 'xlt', 'xltx', 'xlsm', 'xltm', 'xlsb', 'csv'
      ]

      // Office 类型或带文件 ID（非媒体/markdown）即视为文档编辑器可打开
      return wpsFormats.includes(type) || (file.wpsFileId && !mediaTypes.includes(type) && type !== 'md' && type !== 'markdown')
    },

    // Epic #43 Track B / #79: should this Office file open in the embedded
    // LibreOffice editor? True only when the desktop embed is available
    // (libreOfficePreferred). On web/h5 or when unavailable, returns false and
    // the file falls through to FilePreview (docx 本地只读渲染).
    useLibreEditor(file) {
      return this.libreOfficePreferred && this.isEditorOpenableFile(file)
    },

    // Check if file is a markdown tab (for AI artifacts or real .md files)
    isMarkdownTab(file) {
      if (!file) return false
      // 1. AI 创建的虚拟 markdown 标签
      if (file.tabType === 'markdown') return true
      // 2. 真正的 .md 文件（从文件树打开）
      if (file.fileType && (file.fileType.toLowerCase() === 'md' || file.fileType.toLowerCase() === 'markdown')) return true
      return false
    },

    isDiffTab(file) {
      return file && file.tabType === 'diff'
    },

    // --- 文档对比逻辑 ---
    onCompareDocumentsRequest(docs) {
      // FileTree 发起的文档对比请求
      if (!docs || docs.length !== 2) {
        uni.showToast({ title: '请选择两个文档进行对比', icon: 'none' })
        return
      }
      this.compareDocuments = docs
      this.showCompareDialog = true
    },

    onCompareDialogConfirm({ source, target }) {
      // 用户确认了源文档和目标文档，打开 diff 标签页
      this.showCompareDialog = false
      this.openDiffTab(source, target)
    },

    openDiffTab(source, target) {
      // 创建 diff 类型的虚拟标签页
      const diffId = `diff-${source.id}-${target.id}-${Date.now()}`
      const diffFile = {
        id: diffId,
        name: `${source.name} ↔ ${target.name}`,
        tabType: 'diff',
        fileType: 'diff',
        diffSource: {
          id: source.id,
          name: source.name
        },
        diffTarget: {
          id: target.id,
          name: target.name
        },
        createdAt: Date.now()
      }

      // 添加到当前窗格
      const targetPane = this.splitMode ? this.focusedPane : 'left'
      const targetList = targetPane === 'left' ? this.leftFiles : this.rightFiles
      const targetIdProp = targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'

      targetList.push(diffFile)
      this[targetIdProp] = diffFile.id

      console.log('[ProjectOverview] 打开文档对比标签:', diffFile.name)
    },
}
