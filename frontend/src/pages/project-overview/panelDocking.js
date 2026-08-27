// 工作台面板停靠（dev-board#180）：把「录音窗格拖到右侧就在右侧开」「变量库拖到左侧就在左侧开」
// 这件事做成状态而不是三份重复的面板实现。
//
// 名词：dock = 'left'（左栏 sidebar-left）/ 'right'（右侧面板 side-panel-ai）/
// 'bottom'（底部抽屉 bottom-panel）。哪些面板可移动、默认停哪、允许停哪，唯一出处是
// config/panelRegistry.js；本文件只管「用户的选择」这一层状态与三个 dock 之间的搬家动作。
//
// 持久化键 `awd_panel_docks` 是**全局**的（不带 projectId）：面板停哪儿是本机使用习惯，
// 跟着人走而不是跟着案卷走，同 `checkba_project_list_view` 的先例。
//
// 经展开进组件 methods（同 panelSwitching.js 的形制），`this` 即 project-overview 页面实例。

import { track } from '@/utils/telemetryClient.js'
import {
  DOCKS,
  isDockAllowed,
  isMovablePanel,
  resolveDock,
  sanitizeDockOverrides,
} from '@/config/panelRegistry.js'

const DOCK_STORAGE_KEY = 'awd_panel_docks'

/** 并进 data() 的停靠状态。 */
export const panelDockingData = () => ({
  // panelKey → dock 的用户覆盖值（只存被移动过的；没动过的走注册表 defaultDock）
  panelDockOverrides: {},
  // 右侧面板当前显示哪一个：'ai'（AI 对话，恒在）或某个停靠到右侧的面板 key
  rightPaneKey: 'ai',
  // 正在拖拽的面板 key（uni 会重建 <view> 的事件对象、dataTransfer 不可靠，
  // 所以拖拽状态记在组件上，见 .claude/agents/sidebar-shell.md 的事件重建地雷）
  draggingPanelKey: null,
  // 拖拽时高亮哪个投放区
  dockDragOver: null,
  // 右键「移到…」小菜单
  dockMenu: { visible: false, panelKey: '', x: 0, y: 0 },
})

export const panelDockingMethods = {
  // ——————————————————— 状态读写 ———————————————————
  loadPanelDocks() {
    try {
      this.panelDockOverrides = sanitizeDockOverrides(uni.getStorageSync(DOCK_STORAGE_KEY))
    } catch (e) {
      this.panelDockOverrides = {}
    }
  },

  savePanelDocks() {
    try {
      uni.setStorageSync(DOCK_STORAGE_KEY, this.panelDockOverrides)
    } catch (e) {
      // storage 满/隐私模式：停靠位这次不记住，不影响本次会话
    }
  },

  /**
   * rail 数组的停靠过滤：把已经搬去别的 dock 的面板（如语音移到右侧）从 rail 上摘掉，
   * 再把搬到左侧的工具面板（变量库/收藏夹/剪贴板）追加到静态数组之后、spacer 之前
   * ——追加位置与动态插件同法，rail 顺序仍然只有 LEFT_SIDEBAR_PLUGINS 一个出处。
   *
   * CLIENT 视图不参与：客户只有「尽调文件」一个入口，工具面板对他不存在。
   */
  applyPanelDocks(list) {
    if (this.isClientView) return list
    const leftKeys = this.panelDocks.left.map((p) => p.key)
    const kept = list.filter((p) => !isMovablePanel(p.key) || leftKeys.includes(p.key))
    const known = new Set(kept.map((p) => p.key))
    const extras = this.panelDocks.left
      .filter((p) => !known.has(p.key))
      .map((p) => ({ key: p.key, label: this.$t(p.labelKey), svgPaths: p.svgPaths }))
    return [...kept, ...extras]
  },

  /**
   * 当前选中项落在一个已经搬走的面板上时的兜底：
   * 底栏空了要收起抽屉，右栏回落 AI 对话，左栏回落资源管理器——落在没有分支命中的
   * key 上会得到「加载中…」占位符 + rail 上一个高亮都没有，看上去就是坏了。
   */
  normalizeDockSelections() {
    const docks = this.panelDocks
    const bottomKeys = docks.bottom.map((p) => p.key)
    if (!bottomKeys.includes(this.activeToolKey)) this.activeToolKey = bottomKeys[0] || ''
    if (!bottomKeys.length && this.showToolsPanel) this.showToolsPanel = false

    const rightKeys = docks.right.map((p) => p.key)
    if (this.rightPaneKey !== 'ai' && !rightKeys.includes(this.rightPaneKey)) this.rightPaneKey = 'ai'

    const leftKeys = docks.left.map((p) => p.key)
    if (isMovablePanel(this.leftPaneKey) && !leftKeys.includes(this.leftPaneKey)) {
      this.leftPaneKey = 'files'
    }
  },

  // ——————————————————— 打开 / 搬家 ———————————————————
  /** 这个面板此刻是不是正开着（判据按它所在的 dock 各不相同）。 */
  isPanelOpenIn(key, dock) {
    if (dock === 'left') return this.leftPaneKey === key && !this.sidebarCollapsed
    if (dock === 'right') return this.showAiPanel && this.rightPaneKey === key
    if (dock === 'bottom') return this.showToolsPanel && this.activeToolKey === key
    return false
  },

  /** 不管它现在停在哪，把它打开并显示出来（菜单命令与状态条工具入口共用）。 */
  openPanelInItsDock(key) {
    const dock = resolveDock(key, this.panelDockOverrides)
    if (dock === 'left') {
      if (this.leftPaneKey === key) this.sidebarCollapsed = false
      else this.toggleLeftPane(key)
    } else if (dock === 'right') {
      if (!this.showAiPanel) this.toggleAiPanel()
      this.rightPaneKey = key
    } else if (dock === 'bottom') {
      this.switchToolTab(key)
      if (!this.showToolsPanel) this.toggleToolsPanel()
    }
    this.$nextTick(() => this.triggerWorkbenchResize())
  },

  /** 右键菜单与拖拽投放的落点：把面板搬到另一个 dock。 */
  movePanelToDock(key, dock) {
    this.closeDockMenu()
    if (!isMovablePanel(key) || !isDockAllowed(key, dock)) return
    const from = resolveDock(key, this.panelDockOverrides)
    if (from === dock) return

    const wasOpen = this.isPanelOpenIn(key, from)
    this.panelDockOverrides = { ...this.panelDockOverrides, [key]: dock }
    this.savePanelDocks()
    this.normalizeDockSelections()
    // 搬之前开着的，搬完要跟到新位置继续开着——否则用户点了「移到右侧」之后面板凭空消失
    if (wasOpen) this.openPanelInItsDock(key)
    else this.$nextTick(() => this.triggerWorkbenchResize())
    track('ui.panelDock', { panelKey: String(key), from, to: dock })
  },

  /** 右侧面板 tab 切换（'ai' = 对话本体，它挂在 v-show 上，切走不丢会话状态）。 */
  switchRightPane(key) {
    this.rightPaneKey = key
    this.$nextTick(() => {
      this.triggerWorkbenchResize()
      if (key === 'clipboard') this.triggerClipboardRefresh()
    })
  },

  // ——————————————————— 右键菜单（保底路径） ———————————————————
  openDockMenu(key, evt) {
    // rail 上不是每个按钮都能移动（文件树/搜索不行），不能移动的就把右键让给系统默认行为，
    // 所以 preventDefault 在这里做、不在模板上写 .prevent 修饰符。
    if (!isMovablePanel(key)) return
    // uni 会把 <view> 上的原生事件重建成普通对象，只有 mouse 系补了坐标；
    // 补不到就从正在派发的原生事件上取（同 fileOpenTabs.js 的 mouseButtonOf）。
    const native = typeof window !== 'undefined' ? window.event : null
    try {
      if (evt && typeof evt.preventDefault === 'function') evt.preventDefault()
      else if (native && typeof native.preventDefault === 'function') native.preventDefault()
    } catch (e) {
      // 拦不住系统右键菜单不影响本菜单弹出
    }
    const rawX = (evt && Number(evt.clientX)) || (native && Number(native.clientX)) || 0
    const rawY = (evt && Number(evt.clientY)) || (native && Number(native.clientY)) || 0
    // 菜单是 fixed 的，贴着窗口右/下边缘右键会把它顶出屏外（底栏 tab 与右栏 tab 就在边上）
    const vw = typeof window !== 'undefined' ? window.innerWidth : 0
    const vh = typeof window !== 'undefined' ? window.innerHeight : 0
    const x = vw ? Math.min(rawX, Math.max(0, vw - 168)) : rawX
    const y = vh ? Math.min(rawY, Math.max(0, vh - 132)) : rawY
    this.dockMenu = { visible: true, panelKey: key, x, y }
  },

  closeDockMenu() {
    if (this.dockMenu.visible) this.dockMenu = { visible: false, panelKey: '', x: 0, y: 0 }
  },

  // ——————————————————— HTML5 拖拽（桌面增强） ———————————————————
  onPanelDragStart(key, evt) {
    if (!isMovablePanel(key)) return
    this.draggingPanelKey = key
    this.dockDragOver = null
    this.closeDockMenu()
    try {
      if (evt && evt.dataTransfer) {
        evt.dataTransfer.effectAllowed = 'move'
        evt.dataTransfer.setData('text/plain', 'awd-panel:' + key)
      }
    } catch (e) {
      // dataTransfer 不可用不影响：拖拽状态记在 draggingPanelKey 上
    }
  },

  onPanelDragEnd() {
    this.draggingPanelKey = null
    this.dockDragOver = null
  },

  /**
   * 投放区的几何：跟着三块 dock 的**真实尺寸**走，不写死常量——左栏与右栏都是
   * 用户可拖宽的，写死 220/300 会让高亮框和它代表的区域对不上（收起的 dock
   * 没有尺寸可跟，给一个够看清标签的最小值）。
   */
  dockZoneStyle(dock) {
    const RAIL = 50   // .left-rail
    const HEADER = 42 // .project-header
    const STATUS = 26 // .status-bar
    const left = this.sidebarCollapsed ? 0 : Number(this.sidebarWidth) || 0
    const right = this.showAiPanel ? Number(this.aiPanelWidth) || 0 : 0
    const leftW = Math.max(left, 200)
    const rightW = Math.max(right, 240)
    if (dock === 'left') {
      return { left: RAIL + 'px', top: HEADER + 'px', bottom: STATUS + 'px', width: leftW + 'px' }
    }
    if (dock === 'right') {
      return { right: '0px', top: HEADER + 'px', bottom: STATUS + 'px', width: rightW + 'px' }
    }
    return {
      left: (RAIL + leftW) + 'px',
      right: rightW + 'px',
      bottom: STATUS + 'px',
      height: Math.max(Number(this.toolsPanelHeight) || 0, 140) + 'px',
    }
  },

  onDockZoneDragOver(dock) {
    if (!this.draggingPanelKey) return
    this.dockDragOver = dock
  },

  onDockZoneDragLeave(dock) {
    if (this.dockDragOver === dock) this.dockDragOver = null
  },

  onDockZoneDrop(dock) {
    const key = this.draggingPanelKey
    this.onPanelDragEnd()
    if (!key || !DOCKS.includes(dock)) return
    this.movePanelToDock(key, dock)
  },
}
