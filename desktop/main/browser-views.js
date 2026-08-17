'use strict'

// 内嵌浏览器面板（BrowserPane）的 BrowserView 注册表。
//
// 从 main.js 抽出来不是分层洁癖：这里的记账正是「切走标签再切回来就变回默认地址」
// 的病灶。原实现在渲染层组件卸载时直接 destroy 掉 BrowserView——切个标签就把整个
// 网页（页内跳转、滚动位置、填了一半的表单、页面里的登录态）连根拔掉，切回来只能
// 按渲染层记着的那个旧地址重新 loadURL。现在改成：
//   面板卸载 = 摘下（detach，view 还活着）；标签关闭 = 销毁（destroy）。
//
// 因此注册表必须同时记两件事：
//   wanted   —— 渲染层希望它出现在窗口上（= 这个标签正被某个窗格显示）
//   attached —— 它此刻真的挂在窗口上
// 分开记是因为「全局隐藏」（弹窗/蒙层、离开工作台、截图框选）会把所有 view 从窗口
// 摘下，恢复时**只能把 wanted 的挂回去**。恢复时无脑挂回全部，后台保活着的标签就会
// 一起浮到最上层盖住界面——保活改动最容易在这里翻车。
//
// 依赖注入（createView / getWindow）只为可测：单测喂假 view 与假窗口即可验证记账，
// 不需要真的起 Electron。

/**
 * @param {{ createView: (id: string) => any, getWindow: () => any }} deps
 */
function createBrowserViewRegistry({ createView, getWindow }) {
  /** @type {Map<string, any>} */
  const views = new Map()
  /** @type {Map<string, {x:number,y:number,width:number,height:number}>} */
  const bounds = new Map()
  // 渲染层希望可见的 view，按「有几个面板正端着它」计数。计数而不是布尔，是因为
  // 同一个网页标签可以被拖成左右双开（tabDragSplit 跨窗格拖是复制、id 相同），
  // 那时两个面板各挂一份 BrowserPane、各自 attach。布尔的话，关掉其中一个就把
  // 另一个还在看的网页从窗口上摘走了。
  /** @type {Map<string, number>} */
  const wanted = new Map()
  /** 此刻真的挂在窗口上的 view @type {Set<string>} */
  const attached = new Set()
  /** 全局可见性：弹窗/蒙层/离开工作台期间为 false */
  let visible = true

  function layoutAll() {
    if (!getWindow()) return
    for (const [id, view] of views.entries()) {
      const b = bounds.get(id)
      if (!b) continue
      try {
        view.setBounds(b)
        view.setAutoResize({ width: false, height: false })
      } catch (e) {
        // ignore
      }
    }
  }

  function addToWindow(id) {
    const win = getWindow()
    const view = views.get(id)
    if (!win || !view) return
    // addBrowserView 重复 add 会抛错，因此先 remove 再 add 以确保置顶
    try { win.removeBrowserView(view) } catch (e) { /* ignore */ }
    try {
      win.addBrowserView(view)
      attached.add(id)
    } catch (e) {
      // ignore
    }
  }

  function removeFromWindow(id) {
    const win = getWindow()
    const view = views.get(id)
    attached.delete(id)
    if (!win || !view) return
    try { win.removeBrowserView(view) } catch (e) { /* ignore */ }
  }

  return {
    /** 取（必要时建）一个 view。created=false 说明是保活着的旧 view，调用方不要重新 loadURL。 */
    ensure(id) {
      if (views.has(id)) return { view: views.get(id), created: false }
      const view = createView(id)
      views.set(id, view)
      return { view, created: true }
    },

    /** 面板挂载/激活：登记为期望可见，全局可见时立刻挂上窗口并置顶。 */
    attach(id) {
      if (!views.has(id)) return false
      wanted.set(id, (wanted.get(id) || 0) + 1)
      if (visible) addToWindow(id)
      layoutAll()
      return true
    },

    /** 面板卸载：还有别的面板端着它就只减计数；没人要了才从窗口摘下。view 始终活着。 */
    detach(id) {
      if (!views.has(id)) return false
      const left = (wanted.get(id) || 0) - 1
      if (left > 0) { wanted.set(id, left); return true }
      wanted.delete(id)
      removeFromWindow(id)
      return true
    },

    /** 标签真正关闭：销毁 view。这是唯一会丢页面状态的入口。 */
    destroy(id) {
      const view = views.get(id)
      if (!view) return false
      removeFromWindow(id)
      try { view.webContents.destroy() } catch (e) { /* ignore */ }
      views.delete(id)
      bounds.delete(id)
      wanted.delete(id)
      return true
    },

    /**
     * 全局显示/隐藏。隐藏时把所有 view 从窗口摘下但保留 wanted；
     * 恢复时只挂回 wanted 的那些（后台保活的标签不许跟着冒出来）。
     */
    setAllVisible(next) {
      visible = next !== false
      if (!visible) {
        // 不依赖 attached：某些情况下它可能与真实状态不同步，导致 remove 不生效
        for (const id of views.keys()) removeFromWindow(id)
        return
      }
      for (const id of wanted.keys()) addToWindow(id)
      layoutAll()
    },

    /** 窗口 show/focus 后的兜底重挂（沿用当前可见性）。 */
    restoreVisibility() {
      this.setAllVisible(visible)
      layoutAll()
    },

    setBounds(id, b) {
      if (!views.has(id)) return false
      bounds.set(id, b)
      // 仅更新 bounds，避免频繁 remove/add 导致导航被打断（ERR_ABORTED）
      layoutAll()
      return true
    },

    getBounds(id) { return bounds.get(id) || null },
    get(id) { return views.get(id) || null },
    has(id) { return views.has(id) },
    ids() { return [...views.keys()] },
    layoutAll,

    // 仅供测试与排查
    _state() {
      return { visible, wanted: [...wanted.keys()], attached: [...attached] }
    },
  }
}

module.exports = { createBrowserViewRegistry }
