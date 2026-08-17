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

    // IDE 化「打开文件」过渡版：进入项目后按 id 打开指定文件（newproject 页带 openFileId 查询参数进来）
    async openPendingLocalFile(fileId) {
      if (!fileId) return
      try {
        const resp = await getProjectFiles(this.projectId)
        const files = Array.isArray(resp) ? resp : (resp?.data || [])
        const target = files.find(f => f.id === fileId && !f.isFolder)
        if (target) {
          this.openFile(target)
        } else {
          console.warn('[project-overview] openPendingLocalFile: file not found', fileId)
        }
      } catch (e) {
        console.warn('[project-overview] openPendingLocalFile failed', e)
      }
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
          uni.showToast({ title: this.$t('workbenchOps.fileNotFoundNamed', { name }), icon: 'none' })
        }
      } catch (e) {
        console.error('Failed to fetch files for open:', e)
        uni.showToast({ title: this.$t('workbenchOps.fetchFileListFailed'), icon: 'none' })
      }
    },

    openFile(file) {
      // 检查文件类型是否支持打开
      if (!this.isFileTypeSupported(file)) {
        uni.showModal({
          title: this.$t('workbenchOps.cannotOpenFileTitle'),
          content: this.$t('workbenchOps.unsupportedFileContent', {
            name: file.name,
            fileType: file.fileType || this.$t('workbenchOps.noExtension')
          }),
          showCancel: false,
          confirmText: this.$t('workbenchOps.gotIt'),
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
      // 浏览器标签：BrowserPane 卸载时只把 BrowserView 摘下（保活，为的是切标签
      // 不丢网页内容），真正销毁只发生在标签关闭——也就是这里，以及页面卸载。
      // 跨窗格拖拽是「在另一侧也打开同一个标签」（同 id 双开，见 tabDragSplit），
      // 所以另一侧还开着的时候不能销毁——那是把人家正看着的网页拔掉。
      if (this.isBrowserTab(file) && !this.isOpenInOtherPane(fileId, pane)) {
        this.destroyBrowserView(file.id)
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
      if (tabType === 'web') return GLYPHS.web
      if (tabType === 'market-detail') return GLYPHS.blocks
      if (tabType === 'project-home') return GLYPHS.landmark
      return fileGlyph(type)
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
        // 图形源文件（内嵌 draw.io 编辑器）
        'drawio',
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

      // 1b. 非 LOWA 能接的图形源文件。不在这里挡掉的话，下面那条 wpsFileId 兜底
      // 分支会判它们「可编辑」，于是 LOWA（引擎实测仅 Writer + Calc）用 Writer 把
      // XML/二进制当文本导入，满屏乱码。与上面 PDF 那条注释是同一类事故。
      //
      // .drawio 现在有自己的归宿（内嵌 draw.io，见 isDrawioFile / DrawioEditor.vue），
      // 但同样不能进 LOWA，所以仍留在这份名单里。.vsdx/.vsd 没有内嵌编辑器，
      // 走 FilePreview 的下载兜底。
      const externalSourceTypes = ['drawio', 'vsdx', 'vsd']
      if (externalSourceTypes.includes(type)) return false

      // 2. 排除 Markdown 文件，使用专门的 Markdown 预览组件
      if (type === 'md' || type === 'markdown') return false

      // 3. Office 文档格式 —— 仅限自建 LOWA 引擎真正编入的模块（probe_modules
      // 实测：r3 起仅 Writer + Calc；Impress/Draw 随 r4 引擎补齐——见
      // docs/superpowers/specs/2026-08-07-impress-bridge-design.md）。
      // - PDF 仍走 FilePreview 的 Chromium 原生渲染——LOWA 无 Draw 时 Writer 会把
      //   PDF 二进制当文本导入，满屏乱码（2026-07 真机截图证实），这一条与
      //   Impress 是否可用无关，不受本次改动影响。
      const wpsFormats = [
          // Writer
          'wps', 'wpt', 'doc', 'dot', 'docx', 'dotx', 'docm', 'dotm', 'rtf', 'odt',
          // Spreadsheet
          'et', 'ett', 'ets', 'xls', 'xlsx', 'xlt', 'xltx', 'xlsm', 'xltm', 'xlsb', 'csv',
          // Presentation（r4 引擎起：slide_* 原语面，桌面 + 引擎可用时让位给编辑器；
          // web/h5 或引擎不可用仍走 FilePreview 的 pptx-preview 前端渲染，见
          // FilePreview.vue 的 isPptx 分支——本行只影响 useLibreEditor 的判定）
          'pptx', 'ppt', 'pptm', 'potx', 'odp'
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

    // .drawio 走内嵌 draw.io 编辑器（DrawioEditor.vue）。诉讼可视化出的四份产物里
    // 它是唯一的「可继续编辑版」——没有这条分支它就会落进 FilePreview 的
    // 「暂不支持预览」兜底，等于这个格式白出了。
    isDrawioFile(file) {
      if (!file || !file.fileType) return false
      return file.fileType.toLowerCase() === 'drawio'
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

    isVersionCompareTab(file) {
      return file && file.tabType === 'version-compare'
    },

    // 版本对比标签：{projectId, path, name, newRef, oldRef}
    openVersionCompareTab(spec) {
      const id = `vcmp-${spec.newRef.slice(0, 8)}-${Date.now()}`
      const tab = {
        id,
        name: this.$t('workbenchOps.versionCompareTabName', { name: spec.name }),
        tabType: 'version-compare',
        fileType: 'version-compare',
        compareSpec: {
          projectId: spec.projectId, path: spec.path,
          newRef: spec.newRef, oldRef: spec.oldRef,
        },
        createdAt: Date.now(),
      }
      const targetPane = this.splitMode ? this.focusedPane : 'left'
      const targetList = targetPane === 'left' ? this.leftFiles : this.rightFiles
      const targetIdProp = targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'
      targetList.push(tab)
      this[targetIdProp] = tab.id
    },

    // 「对比」入口，两种来源共用：
    // 1) VersionNodeDetail 的「和上一版对比」冒泡上来 {path, sha}——newRef=这一版，
    //    oldRef=它的直接父提交（sha + '^'），标签沿用默认的「上一版/这一版」。
    // 2) AdoptConflictDialog 的「对比」冒泡上来 {path, newRef, oldRef, oldLabel, newLabel}——
    //    newRef/oldRef 已经是主线侧 / 稿侧两个具体 ref，不需要再推导；「上一版/这一版」
    //    在这个场景里说不通（两边不是先后关系），改用调用方传入的标签。
    // 桌面 + docx/doc 走修订稿对比标签（LOWA 渲染）；其余走文本对比标签（DocDiffViewer 降级）。
    onVersionCompareFile({ path, sha, newRef, oldRef, name: givenName, oldLabel, newLabel }) {
      const name = givenName || path.split('/').pop() || path
      const resolvedNewRef = sha || newRef
      const resolvedOldRef = sha ? sha + '^' : oldRef
      const isDocx = /\.docx?$/i.test(name)
      if (this.libreOfficePreferred && isDocx) {
        this.openVersionCompareTab({ projectId: this.projectId, path, name, newRef: resolvedNewRef, oldRef: resolvedOldRef })
      } else {
        this.openVersionTextDiffTab({ projectId: this.projectId, path, name, newRef: resolvedNewRef, oldRef: resolvedOldRef, oldLabel, newLabel })
      }
    },

    isVersionTextDiffTab(file) {
      return file && file.tabType === 'version-text-diff'
    },

    // 版本文本对比标签（DocDiffViewer versionSpec 模式）：{projectId, path, name, newRef, oldRef,
    // oldLabel?, newLabel?}——默认「上一版/这一版」，采纳冲突场景传入更贴切的标签。
    openVersionTextDiffTab(spec) {
      const id = `vtd-${spec.newRef.slice(0, 8)}-${Date.now()}`
      const tab = {
        id, name: this.$t('workbenchOps.versionCompareTabName', { name: spec.name }), tabType: 'version-text-diff', fileType: 'version-text-diff',
        versionSpec: {
          projectId: spec.projectId, path: spec.path, oldRef: spec.oldRef, newRef: spec.newRef,
          oldLabel: spec.oldLabel || this.$t('workbenchOps.previousVersion'), newLabel: spec.newLabel || this.$t('workbenchOps.currentVersion'),
        },
        createdAt: Date.now(),
      }
      const targetPane = this.splitMode ? this.focusedPane : 'left'
      const targetList = targetPane === 'left' ? this.leftFiles : this.rightFiles
      const targetIdProp = targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'
      targetList.push(tab)
      this[targetIdProp] = tab.id
    },

    // --- 文档对比逻辑 ---
    onCompareDocumentsRequest(docs) {
      // FileTree 发起的文档对比请求
      if (!docs || docs.length !== 2) {
        uni.showToast({ title: this.$t('workbenchOps.selectTwoDocsToCompare'), icon: 'none' })
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

    // 版本操作改磁盘的通用重载链（问题 A 数据安全修复的推广）：退回/开稿/切线/采纳/
    // 放弃成功后，VersionPanel 把受影响文件 id 一路冒泡上来（project-overview.vue
    // @reload-files）。响应驱动，不走 SSE——发起动作的就是前端自己，不需要后端另外
    // "通知"。这里对左右两窗格里正打开的、id 命中的 Office 文档标签，复用既有的编辑器
    // 重载例程（agentClientActions.js 的 handleEditorReloadFile，AI 改文档后刷新编辑器
    // 走的同一条路，就地重载不 flush 脏内容）；没打开的文件什么都不做，不能用 openFile
    // 把它硬拉出来。
    //
    // 绝对不能用「关闭标签」实现重载：closeFile 关闭有脏改动的 Office 文档前会先
    // flushSave 落盘——那恰好会把版本操作前编辑器里还端着的旧字节写回去，把律师刚做的
    // 操作冲掉，这正是本次要修的数据安全问题本身。
    // 一次版本操作可能同时改写好几份打开中的文件；逐份 toast「文件已更新: X」会互相
    // 顶掉，律师只看得见最后一条、还以为只更新了一份。多文件时改为静音逐份重载、
    // 最后聚合成一句。单文件语义一个字不改（仍是原来的逐文件 toast）。
    async onVersionReloadFiles(affectedFileIds) {
      if (!Array.isArray(affectedFileIds) || !affectedFileIds.length) return
      const idSet = new Set(affectedFileIds)
      const openIds = new Set()
      for (const f of this.leftFiles) {
        if (idSet.has(f.id) && this.useLibreEditor(f)) openIds.add(f.id)
      }
      for (const f of this.rightFiles) {
        if (idSet.has(f.id) && this.useLibreEditor(f)) openIds.add(f.id)
      }
      const many = openIds.size > 1
      let ok = 0
      for (const fileId of openIds) {
        // forceActive：这条链上的每一种动作（退回/开稿/切线/采纳/放弃/丢弃）都是律师
        // 亲手点出来的，正在显示的那个实例也必须就地换文档——不然下一次 autosave 会把
        // 操作结果冲掉。AI 改文件走的是默认（不强刷）分支，见 handleEditorReloadFile 的注释。
        const r = await this.handleEditorReloadFile(
          { fileId }, { forceActive: true, silentSuccessToast: many })
        if (r) ok++
      }
      // 失败的那几份各自已经报过（静音只吞成功提示），这里只汇报成功的份数。
      if (many && ok) {
        uni.showToast({ title: this.$t('workbenchOps.updatedOpenFiles', { count: ok }), icon: 'success' })
      }
    },
}
