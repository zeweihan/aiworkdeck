// project-overview.vue 的文件打开与标签页生命周期：从文件树/搜索/AI 对话打开文件、
// 标签激活与 FileTree 选中同步、关闭（Office 文档出池前先落盘）、可打开性判定与文档对比标签。
// 经展开进组件 methods（纯搬移，Phase 2 外置），`this` 即 project-overview 页面实例。

import { getProjectFiles } from '@/services/api.js'
import { activityTracker } from '@/utils/activityTracker.js'
import { ICONS as GLYPHS, fileGlyph } from '@/config/icons.js'

// 轻量文本编辑器（PlainTextEditor.vue）承接的扩展名（dev-board#37）。
// dev-board#61 插件开发形态起收纳代码文件（js/json/html/css 等），供律师直改插件源码。
// 必须与后端 TextFileEditTools.PLAIN_TEXT_TYPES 完全一致，改这里要同步改那边。
const PLAIN_TEXT_TYPES = ['txt', 'md', 'markdown', 'json', 'js', 'mjs', 'css', 'html', 'htm', 'yml', 'yaml']

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
          // 新建项目流程带 openFileId 直接落地工作台，用户期待那份刚建好的文件
          // 已经打开——找不到时以前只 console.warn，界面上什么反应都没有，用户
          // 分不清"文件确实不存在"还是"后端还没写完/网络抖了一下"。同名方法
          // handleOpenFileFromChat 找不到文件时已经会弹这句提示，这里补齐同款。
          console.warn('[project-overview] openPendingLocalFile: file not found', fileId)
          uni.showToast({ title: this.$t('workbenchOps.fileNotFound'), icon: 'none' })
        }
      } catch (e) {
        console.warn('[project-overview] openPendingLocalFile failed', e)
        uni.showToast({ title: this.$t('workbenchOps.fetchFileListFailed'), icon: 'none' })
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
        let targetFile = files.find(f => {
          if (f.isFolder) return false
          // Match exact name or name without extension
          return f.name === name || f.name.toLowerCase() === name.toLowerCase()
        })

        // 精确名找不到时按「基名 + 扩展名」再找一轮。
        // 有些工具报上来的"变更文件名"其实是一组产物的基名而不是某一个文件：
        // 诉讼可视化的 file_change 带的是图名（litigation_render 的 diagramName），
        // 项目里真正存在的是同名文件夹下的 <图名>.drawio / .svg / .png。
        // 没有这条兜底，对话里的文件卡点了只会弹"文件不存在"——一个死掉的入口。
        // 也认 -draft：语义地图未确认时引擎按设计给产物加这个后缀（草稿闸），
        // 而工具报上来的名字里没有它——第一次出图必然走这一支。
        if (!targetFile) {
          const bases = [name.toLowerCase() + '.', name.toLowerCase() + '-draft.']
          const candidates = files.filter(f =>
            !f.isFolder && bases.some(b => f.name.toLowerCase().startsWith(b)))
          // 一组产物里优先给可继续编辑的那份，其次是能看的母版。
          const rank = ['drawio', 'svg', 'png']
          targetFile = candidates.sort((a, b) => {
            const ra = rank.indexOf((a.fileType || '').toLowerCase())
            const rb = rank.indexOf((b.fileType || '').toLowerCase())
            return (ra < 0 ? rank.length : ra) - (rb < 0 ? rank.length : rb)
          })[0]
        }

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
      activityTracker.trackActivePage('OPEN_FILE', file.id, file.name, this.project && this.project.id, meta)

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
          activityTracker.trackActivePage('OPEN_URL', 0, url, this.project && this.project.id, fullMeta)
      } else {
          // File
          activityTracker.trackActivePage('OPEN_FILE', file.id, file.name, this.project && this.project.id, meta)
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

    /**
     * 标签条的鼠标中键（dev-board#97）：中键单击 = 点 ×，走同一条 closeFile
     * （含 Office/文本脏改动先落盘、浏览器标签销毁 BrowserView 等既有闸门）。
     * 中键在 Linux/Windows 上 mousedown 会起「自动滚动」光标，auxclick 前就得拦，
     * 所以 mousedown 与 auxclick 两处都 preventDefault；左/右键一律放行
     * （左键是 @tap 激活，右键没有菜单）。标签没有「固定」概念，全部可关。
     */
    onTabMouseDown(e) {
      if (e && e.button === 1) e.preventDefault()
    },
    onTabAuxClick(e, file, pane) {
      if (!e || e.button !== 1) return
      e.preventDefault()
      e.stopPropagation()
      this.closeFile(file.id, pane)
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
      // 文本标签（PlainTextEditor）的平行分支：v-if 单实例，只有"正激活显示"的
      // 标签才有组件实例；非激活标签在切走时已由组件 beforeUnmount 兜底落盘。
      else if (file && this.isPlainTextFile(file)) {
        const inst = (this._plainTextRefs || {})[pane]
        if (inst && inst.file && inst.file.id === fileId && (inst.dirty || inst.saving)) {
          try { await inst.flushSave() } catch (e) { console.warn('[ProjectOverview] close flush-save (text) failed:', e) }
        }
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
      // 项目概览 2026-08-19 起在左栏展示，不再有 project-home 标签
      // 个人中心 2026-08-20 并进了「设置」标签，不再有 user-profile 标签
      if (tabType === 'admin-settings') return GLYPHS.settings
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

      // 2. 纯文本走轻量文本编辑器（PlainTextEditor.vue，dev-board#37），不进 LOWA。
      // 必须排在下面 wpsFileId 兜底之前——上传的 txt 都被 FileTree 合成了 wpsFileId，
      // 不拦就会被兜底分支判成"可编辑"送进 150MB 的 WASM 引擎。
      if (PLAIN_TEXT_TYPES.includes(type)) return false

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

      // Office 类型或带文件 ID（非媒体，纯文本已在上面拦下）即视为文档编辑器可打开
      return wpsFormats.includes(type) || (file.wpsFileId && !mediaTypes.includes(type))
    },

    // Epic #43 Track B / #79: should this Office file open in the embedded
    // LibreOffice editor? True only when the desktop embed is available
    // (libreOfficePreferred). On web/h5 or when unavailable, returns false and
    // the file falls through to FilePreview (docx 本地只读渲染).
    useLibreEditor(file) {
      return this.libreOfficePreferred && this.isEditorOpenableFile(file)
    },

    // 纯文本文件（txt/md/markdown）走轻量文本编辑器（PlainTextEditor.vue，dev-board#37）。
    // tabType 有值的都是虚拟标签（web/markdown/diff…），不归这里。
    isPlainTextFile(file) {
      if (!file || file.tabType || file.isFolder || !file.fileType) return false
      return PLAIN_TEXT_TYPES.includes(file.fileType.toLowerCase())
    },

    // PlainTextEditor 实例登记（v-if 单实例，每窗格至多一个；对齐 _libreRefs 的
    // 非响应式口径）。closeFile 落盘、版本重载、AI text_reload_file 都从这里取实例。
    setPlainTextRef(pane, el) {
      if (!this._plainTextRefs) this._plainTextRefs = {}
      if (el) this._plainTextRefs[pane] = el
      else delete this._plainTextRefs[pane]
    },

    /**
     * 让正在显示 fileId 的文本编辑器实例就地重载（版本退回 / AI text_* 直改后调用）。
     * 未激活的文本标签没有组件实例（v-if 单实例，切走即销毁），下次激活时挂载
     * 自然拉取新内容，无需处理。返回是否全部成功（没有命中的实例也算成功）。
     */
    async reloadPlainTextInstances(fileId) {
      let ok = true
      for (const pane of ['left', 'right']) {
        const inst = (this._plainTextRefs || {})[pane]
        if (inst && inst.file && inst.file.id === fileId) {
          try {
            const r = await inst.reloadFromBackend()
            if (!r) ok = false
          } catch (e) {
            console.warn('[ProjectOverview] plain text reload failed:', e)
            ok = false
          }
        }
      }
      return ok
    },

    // .drawio 走内嵌 draw.io 编辑器（DrawioEditor.vue）。诉讼可视化出的四份产物里
    // 它是唯一的「可继续编辑版」——没有这条分支它就会落进 FilePreview 的
    // 「暂不支持预览」兜底，等于这个格式白出了。
    isDrawioFile(file) {
      if (!file || !file.fileType) return false
      return file.fileType.toLowerCase() === 'drawio'
    },

    // AI 虚拟 markdown 产物标签（tabType='markdown'，无真实 fileId）才走只读
    // MarkdownPreview；真正的 .md 文件自 dev-board#37 起走 PlainTextEditor（可编辑）。
    isMarkdownTab(file) {
      return !!(file && file.tabType === 'markdown')
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

      // 文本标签（PlainTextEditor）的平行分支：正在显示的实例必须就地重载并丢弃
      // 本地未保存态（版本操作以后端为准），否则画面不变、下一次自动保存还会把
      // 退回前的旧内容写回去——与上面 forceActive 是同一类数据事故。未激活的文本
      // 标签没有实例（v-if 单实例），下次激活挂载即拉新内容。失败时组件自己转入
      // 错误相位并封死保存，这里不再叠加提示。
      for (const fileId of idSet) {
        await this.reloadPlainTextInstances(fileId)
      }
    },
}
