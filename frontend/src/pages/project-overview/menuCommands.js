// project-overview.vue 的菜单栏接线：状态上报 + wb:* 命令执行。
// 经展开进组件 methods（与同目录其它模块同法），`this` 即工作台页面实例。
//
// 分工：命令表在 config/commands/，下发与派发在 utils/appMenuBridge.js（App 级
// 注册一次），这里只做两件事——把工作台的状态报上去、把派发下来的命令执行掉。
//
// **每个入口都要过 isActiveOverviewInstance() 守卫。** navigateTo 会让本页存在
// 多个实例，不守卫就是「点一次菜单执行 N 次」（这个项目在剪贴板订阅上踩过）。

import { patchMenuState, setMenuPage, COMMAND_EVENT } from '@/utils/appMenuBridge.js'

const WORD_EXT = /\.(doc|docx|wps)$/i

export const menuCommandsMethods = {
  // ---- 状态上报 ---------------------------------------------------------

  /** 当前活跃窗格的 LibreOffice 实例。分屏时以右窗格优先（最后聚焦的那个）。 */
  activeLibreEditor() {
    const refs = this._libreRefs || {}
    if (this.splitMode && this.activeFileIdRight) {
      const r = refs['right:' + this.activeFileIdRight]
      if (r) return r
    }
    return refs['left:' + this.activeFileIdLeft] || null
  },

  /**
   * 状态快照。**只放布尔和短枚举**——放进度、计数器会让菜单被反复重建，
   * 而重建会关掉用户正展开着的菜单（spec §5.1）。
   */
  buildMenuFlags() {
    const f = this.activeFileLeft
    const ed = this.activeLibreEditor()
    const edState = ed && ed.menuState ? ed.menuState() : {}
    const chat = this.$refs.chatInterface
    const chatState = chat && chat.menuState ? chat.menuState() : {}
    return {
      hasProject: !!(this.project && this.project.id),
      hasTab: !!(this.activeFileIdLeft || (this.splitMode && this.activeFileIdRight)),
      // 「是不是 Writer 文档」既看文件名也看引擎实例：备胎/未就绪时不该点亮
      // 修订类命令，否则点了没反应。
      isDocTab: !!(f && f.name && WORD_EXT.test(f.name) && ed && ed.menuReady && ed.menuReady()),
      splitMode: !!this.splitMode,
      sidebarOpen: !this.sidebarCollapsed,
      toolsPanelOpen: !!this.showToolsPanel,
      aiPanelOpen: !!this.showAiPanel,
      toolVariables: !!this.showToolsPanel && this.activeToolKey === 'variables',
      toolFavorites: !!this.showToolsPanel && this.activeToolKey === 'favorites',
      toolClipboard: !!this.showToolsPanel && this.activeToolKey === 'clipboard',
      recording: !!this.isRecording,
      trackChanges: !!edState.trackChanges,
      reviewOpen: !!edState.reviewOpen,
      aiRunning: !!chatState.aiRunning,
      aiModeAsk: chatState.aiMode === 'ASK',
      aiModePlan: chatState.aiMode === 'PLAN',
      aiModeAgent: chatState.aiMode === 'AGENT',
    }
  },

  /** 状态变了就调一次。桥那边做浅比较与去抖，这里不必自己节流。 */
  pushMenuState() {
    if (!this.isActiveOverviewInstance()) return
    patchMenuState({
      page: 'workbench',
      role: this.isClientView ? 'CLIENT' : 'LAWYER',
      projectId: (this.project && this.project.id) || null,
      activeView: this.sidebarCollapsed ? null : this.leftPaneKey,
      views: (this.LEFT_SIDEBAR_PLUGINS || []).map((p) => ({ key: p.key, label: p.label })),
      flags: this.buildMenuFlags(),
    })
    // Windows 自绘菜单栏读的是同一份快照，状态推完让它重建一次。
    // mac 上组件不渲染，这个计数器空转，代价可忽略。
    this.menuBarRefreshKey++
  },

  /** onShow / mounted 时接管菜单。 */
  registerMenuCommands() {
    if (this._menuCmdHandler) return
    this._menuCmdHandler = (payload) => {
      if (!this.isActiveOverviewInstance()) return
      this.runMenuCommand(payload)
    }
    try { uni.$on(COMMAND_EVENT, this._menuCmdHandler) } catch (e) { /* ignore */ }
    setMenuPage('workbench', {
      role: this.isClientView ? 'CLIENT' : 'LAWYER',
      projectId: (this.project && this.project.id) || null,
      flags: this.buildMenuFlags(),
    })
    this.pushMenuState()
  },

  /**
   * 交出菜单。**必须连状态一起清掉**——只摘事件监听的话，桥里还留着
   * page:'workbench' 和一整套 flags，用户去了项目列表/设置页，菜单依然全亮，
   * 点下去却没有活跃实例来接 = 点了没反应。清成空页面后，所有
   * when:['workbench'] 的条目自动置灰，「打开文件夹」这类无 when 的照常可用。
   */
  unregisterMenuCommands() {
    if (!this._menuCmdHandler) return
    try { uni.$off(COMMAND_EVENT, this._menuCmdHandler) } catch (e) { /* ignore */ }
    this._menuCmdHandler = null
    setMenuPage('', {})
    this.menuBarRefreshKey++
  },

  // ---- 命令执行 ---------------------------------------------------------

  menuToast(title) {
    try { uni.showToast({ title, icon: 'none' }) } catch (e) { /* ignore */ }
  },

  /** 需要活跃 Writer 实例的命令的统一前置。拿不到就说清楚，别静默。 */
  requireEditor() {
    const ed = this.activeLibreEditor()
    if (!ed || !ed.menuReady || !ed.menuReady()) {
      this.menuToast(this.$t('workbench.menuNeedsDoc'))
      return null
    }
    return ed
  },

  async runMenuCommand(payload) {
    const verb = payload && payload.verb
    const arg = payload && payload.arg
    if (!verb) return
    switch (verb) {
      // —— 视图 / 面板
      case 'toggleSidebar': this.toggleSidebar(); break
      case 'toggleToolsPanel': this.toggleToolsPanel(); break
      case 'toggleAiPanel': this.toggleAiPanel(); break
      case 'toggleSplit': this.toggleSplitMode(); break
      case 'openView': this.toggleLeftPane(arg); break
      case 'openTool':
        if (!this.showToolsPanel) this.toggleToolsPanel()
        this.activeToolKey = arg
        break
      case 'openVersionPanel': this.toggleLeftPane('version'); break
      case 'openPluginMarket': this.goToPluginMarket(); break

      // —— 文件 / 标签
      case 'closeTab':
        if (this.activeFileIdLeft) this.closeFile(this.activeFileIdLeft, 'left')
        else if (this.splitMode && this.activeFileIdRight) this.closeFile(this.activeFileIdRight, 'right')
        break
      case 'nextTab': this.cycleTab(1); break
      case 'prevTab': this.cycleTab(-1); break
      case 'importFiles': this.toggleLeftPane('files'); this.menuToast(this.$t('workbench.menuImportHint')); break
      case 'revealInFinder': await this.menuRevealProject(); break

      // —— 导航
      case 'quickOpen': this.quickOpenVisible = true; break
      case 'commandPalette': this.commandPaletteVisible = true; break
      case 'goProjectHome': this.goProjectHome(); break
      case 'goAllProjects': this.goAllProjects(); break

      // —— 工具
      case 'ocrCapture': this.startOcrCapture(); break
      case 'openBrowserTab': this.openBrowserTab(); break
      case 'toggleRecording': this.toggleRecording(); break

      // —— 文档（都要活跃 Writer 实例）
      case 'find': { const ed = this.requireEditor(); if (ed) ed.menuOpenFind(); break }
      case 'toggleTrackChanges': { const ed = this.requireEditor(); if (ed) await ed.menuToggleTrackChanges(); break }
      case 'toggleReviewPanel': { const ed = this.requireEditor(); if (ed) ed.menuToggleReviewPanel(); break }
      case 'acceptAllRevisions': { const ed = this.requireEditor(); if (ed) await ed.menuResolveAllRevisions('accept'); break }
      case 'rejectAllRevisions': { const ed = this.requireEditor(); if (ed) await ed.menuResolveAllRevisions('reject'); break }
      case 'clearFormatting': { const ed = this.requireEditor(); if (ed) await ed.menuClearFormatting(); break }
      case 'insertComment': {
        const ed = this.requireEditor()
        if (!ed) break
        const r = ed.menuInsertComment()
        if (r && r.ok === false && r.reason === 'no-selection') {
          this.menuToast(this.$t('workbench.menuSelectTextFirst'))
        }
        break
      }

      // —— AI
      case 'newChat': await this.menuWithChat((c) => c.startNewChat()); break
      case 'stopAi': await this.menuWithChat((c) => c.menuStop()); break
      case 'setAiMode': await this.menuWithChat((c) => c.menuSetMode(arg)); break
      case 'openChatHistory': await this.menuWithChat(() => { this.showHistoryDrawer = true; this.fetchChatHistory && this.fetchChatHistory() }); break

      default:
        console.warn('[menu] 工作台未实现的命令:', verb)
    }
    this.$nextTick(() => this.pushMenuState())
  },

  /** AI 相关命令的统一前置：面板没开先开，等它挂上再执行。 */
  async menuWithChat(fn) {
    if (!this.showAiPanel) {
      this.toggleAiPanel()
      await this.$nextTick()
    }
    const chat = this.$refs.chatInterface
    if (!chat) { this.menuToast(this.$t('workbench.menuAiNotReady')); return }
    return fn(chat)
  },

  /** 左右切标签。分屏时只切左窗格——右窗格是「对照」，跳着切会让人失去参照。 */
  cycleTab(step) {
    const files = (this.leftFiles || []).filter((f) => this.isTabVisible(f))
    if (files.length < 2) return
    const i = files.findIndex((f) => f.id === this.activeFileIdLeft)
    const next = files[((i < 0 ? 0 : i) + step + files.length) % files.length]
    if (next) this.activateTab(next, 'left')
  },

  /**
   * 在访达里显示。复用文件树右键那条已有实现（onRevealFile）——物理路径要后端
   * 解析（localRoot 感知），不能在渲染层拼。有活跃文件就揭示文件，否则揭示项目根。
   */
  async menuRevealProject() {
    const f = this.activeFileLeft
    if (f && !f.isFolder) return this.onRevealFile(f)
    return this.onRevealFile({ isFolder: true })
  },
}
