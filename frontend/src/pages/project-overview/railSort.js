// 左 rail 图标拖动排序（dev-board#204）：rail 数组（含动态插件与停靠到左侧的工具面板）
// 的显示顺序做成用户可拖的状态。与 panelDocking.js 的「跨 dock 停靠拖拽」共存：
// 可停靠面板拖出 rail 落进投放区仍走停靠；只在 rail 内部拖动、松手即为排序。
//
// 持久化键 `awd_rail_order` 与 `awd_panel_docks` 同法是**全局**的：rail 顺序是本机
// 使用习惯，跟着人走不跟着案卷走。存的是 key 数组；没记过的新增项（后装的插件、
// 新功能位）按默认顺序追加在已记项之后。
//
// 经展开进组件 data/methods（同 panelDocking.js 的形制），`this` 即 project-overview 页面实例。

import { track } from '@/utils/telemetryClient.js'

const RAIL_ORDER_STORAGE_KEY = 'awd_rail_order'

export const railSortData = () => ({
  // 用户记住的 rail 顺序（key 数组；空 = 从没拖过，走默认顺序）
  railOrder: [],
  // 拖动过程中的实时顺序草稿（松手时提交进 railOrder；null = 没在拖）
  railOrderDraft: null,
  // 正在拖的 rail 项 key（与 draggingPanelKey 并存：后者只对可停靠面板生效）
  draggingRailKey: null,
  // 整理模式（dev-board#215）：iPhone 编辑主屏幕式抖动编辑态。
  // 开着时可拖项抖动+虚线框，点击不打开面板（防止想拖却点开）。不持久化。
  railEditMode: false,
})

export const railSortMethods = {
  loadRailOrder() {
    try {
      const saved = uni.getStorageSync(RAIL_ORDER_STORAGE_KEY)
      this.railOrder = Array.isArray(saved) ? saved.filter((k) => typeof k === 'string') : []
    } catch (e) {
      this.railOrder = []
    }
  },

  saveRailOrder() {
    try {
      uni.setStorageSync(RAIL_ORDER_STORAGE_KEY, this.railOrder)
    } catch (e) {
      // storage 满/隐私模式：这次的顺序不记住，不影响本次会话
    }
  },

  /**
   * 把用户顺序套在 rail 数组上：记过的项按记住的先后排，没记过的项按默认顺序
   * 排在最后。CLIENT 视图只有一个入口，不参与。
   */
  applyRailOrder(list) {
    if (this.isClientView) return list
    const order = this.railOrderDraft || this.railOrder
    if (!order || !order.length) return list
    const idx = new Map(order.map((k, i) => [k, i]))
    const known = list.filter((p) => idx.has(p.key))
    const unknown = list.filter((p) => !idx.has(p.key))
    known.sort((a, b) => idx.get(a.key) - idx.get(b.key))
    return [...known, ...unknown]
  },

  toggleRailEditMode() {
    this.railEditMode = !this.railEditMode
    if (this.railEditMode) track('ui.railEditMode', {})
  },

  /** rail 项点击：整理模式下不打开面板（iPhone 抖动态下点 app 也不启动）。 */
  onRailBtnTap(key) {
    if (this.railEditMode) return
    this.toggleLeftPane(key)
  },

  onRailDragStart(key, evt) {
    this.draggingRailKey = key
    this.railOrderDraft = null
    // 可停靠面板同时进入停靠拖拽态（投放区高亮那套），两种松手结局都成立
    this.onPanelDragStart(key, evt)
    if (!this.draggingPanelKey) {
      // 不可停靠的项 onPanelDragStart 会直接返回，dataTransfer 自己补上，
      // 否则部分 WebView 里 drag 手势起不来
      try {
        if (evt && evt.dataTransfer) {
          evt.dataTransfer.effectAllowed = 'move'
          evt.dataTransfer.setData('text/plain', 'awd-rail:' + key)
        }
      } catch (e) {
        // dataTransfer 不可用不影响：拖拽状态记在 draggingRailKey 上
      }
    }
  },

  /** 拖着一个 rail 项经过另一个 rail 项：实时把拖动项挪到目标位置（草稿层）。 */
  onRailDragOver(key, evt) {
    // uni 会重建 <view> 事件对象，preventDefault 在这里补做（同 openDockMenu 的地雷）
    const native = typeof window !== 'undefined' ? window.event : null
    try {
      if (evt && typeof evt.preventDefault === 'function') evt.preventDefault()
      else if (native && typeof native.preventDefault === 'function') native.preventDefault()
    } catch (e) { /* 拦不住不影响排序 */ }

    const dragging = this.draggingRailKey
    if (!dragging || dragging === key) return
    const keys = this.LEFT_SIDEBAR_PLUGINS.map((p) => p.key)
    const from = keys.indexOf(dragging)
    const to = keys.indexOf(key)
    if (from < 0 || to < 0 || from === to) return
    keys.splice(from, 1)
    keys.splice(to, 0, dragging)
    this.railOrderDraft = keys
  },

  onRailDragEnd() {
    if (this.railOrderDraft) {
      this.railOrder = this.railOrderDraft
      this.railOrderDraft = null
      this.saveRailOrder()
      track('ui.railReorder', { order: this.railOrder.join(',') })
    }
    this.draggingRailKey = null
    this.onPanelDragEnd()
  },
}
