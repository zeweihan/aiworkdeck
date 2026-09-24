// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// modalFocusGuard.js — 宿主弹窗开着时，键盘不许溜进被遮住的编辑器（dev-board#883，S1）。
//
// 病灶：本仓的自绘弹窗（.awd-mask / .awd-dialog-mask / *-dialog-mask … 几十处）只有一层
// position:fixed 的遮罩，没有焦点圈。遮罩挡得住鼠标，挡不住键盘：弹窗里的按钮多是
// <view>（不可聚焦），Tab 沿宿主文档顺序走到下一个可聚焦元素——编辑器的 <webview>
// （桌面）/ <iframe>（Web 态），焦点于是进了客体页的 IME 覆盖层，用户以为在操作弹窗，
// 字却带着修订写进了背景文档并被自动保存（真机两次复现，含冷启动）。
// AwdDialog 自己带焦点圈（AwdDialog.vue 的 onKeydown/onFocusIn，window 捕获段），
// 不受影响；其余弹窗都没有。
//
// 为什么不逐个弹窗加焦点圈：几十处、结构各异，漏一处就是同一个 S1。这里只认一个几何
// 事实——「某个可见的 webview/iframe 被一个 position:fixed 的层整块盖住」，与类名无关：
//   · Tab / Shift+Tab：圈在盖住它的那一层里循环；层里没有可聚焦元素就把焦点落在层上。
//   · 焦点闯进被盖住的框体（Tab 之外的途径，如代码 focus()）：拉回那一层。
//   · 弹窗在用户正往编辑器里打字时弹出来：焦点拉出框体；弹窗关掉后还回原来的框体。
// **不动编辑器的可见性**（隐藏 webview 的方案被否决过，#539/#503 的冻结与黑帧都跟它有关）。
// 没有框体被盖住时什么都不做，零干预。
//
// 监听挂在 document 捕获段：window 捕获段的 AwdDialog 先处理并 preventDefault，这里
// 看到 defaultPrevented 就让路，两套焦点圈不会各走一步。

const FRAME_TAGS = { WEBVIEW: 1, IFRAME: 1 }
const FOCUSABLE = 'input:not([type="hidden"]), textarea, select, button, a[href], [tabindex], [contenteditable=""], [contenteditable="true"]'

function isFrame(el) {
  return !!(el && el.tagName && FRAME_TAGS[el.tagName])
}

function isShown(el, win) {
  if (!el.getClientRects().length) return false
  try { return win.getComputedStyle(el).visibility !== 'hidden' } catch (e) { return true }
}

// 框体与视口相交区域里取五个点（中心 + 四角内缩 15%）。
function samplePoints(frame, win) {
  const r = frame.getBoundingClientRect()
  const left = Math.max(r.left, 0)
  const top = Math.max(r.top, 0)
  const right = Math.min(r.right, win.innerWidth)
  const bottom = Math.min(r.bottom, win.innerHeight)
  const w = right - left
  const h = bottom - top
  if (w < 8 || h < 8) return []
  const at = (fx, fy) => ({ x: left + w * fx, y: top + h * fy })
  return [at(0.5, 0.5), at(0.15, 0.15), at(0.85, 0.15), at(0.15, 0.85), at(0.85, 0.85)]
}

/**
 * 盖住整个 frame 的那一层（position:fixed 的遮罩），没被盖住回 null。
 * 五个采样点全部命中框体之外的元素、且这些点都落在同一个 fixed 祖先里才算。
 * pointer-events:none 的浮层 elementFromPoint 本来就穿透，不会误判。
 */
export function modalLayerOver(frame, doc = document, win = window) {
  if (!isFrame(frame) || !frame.isConnected || !isShown(frame, win)) return null
  const pts = samplePoints(frame, win)
  if (!pts.length) return null
  const hits = []
  for (const p of pts) {
    const el = doc.elementFromPoint(p.x, p.y)
    // 命中框体本身、框体里面、或框体的祖先（点落在框体外沿）都算没盖住
    if (!el || el === frame || frame.contains(el) || el.contains(frame)) return null
    hits.push(el)
  }
  for (let el = hits[0]; el && el !== doc.body && el !== doc.documentElement; el = el.parentElement) {
    let pos = ''
    try { pos = win.getComputedStyle(el).position } catch (e) { /* ignore */ }
    if (pos !== 'fixed') continue
    if (hits.every((h) => el.contains(h))) return el
  }
  return null
}

/** 当前盖住任一可见编辑器框体的那一层；没有回 null。 */
export function activeModalLayer(doc = document, win = window) {
  for (const f of doc.querySelectorAll('webview, iframe')) {
    const layer = modalLayerOver(f, doc, win)
    if (layer) return layer
  }
  return null
}

function focusablesIn(layer, win) {
  return [...layer.querySelectorAll(FOCUSABLE)].filter((el) =>
    !el.disabled && el.tabIndex >= 0 && !isFrame(el) && isShown(el, win))
}

function focusLayer(layer) {
  if (!layer.hasAttribute('tabindex')) {
    layer.setAttribute('tabindex', '-1')
    layer.style.outline = 'none'
  }
  try { layer.focus({ preventScroll: true }) } catch (e) { /* ignore */ }
}

function pullInto(layer, win) {
  const list = focusablesIn(layer, win)
  if (list.length) { try { list[0].focus({ preventScroll: true }) } catch (e) { /* ignore */ } }
  else focusLayer(layer)
}

let installed = false

export function installModalFocusGuard(doc = typeof document !== 'undefined' ? document : null, win = typeof window !== 'undefined' ? window : null) {
  if (!doc || !win || installed) return
  installed = true
  let restoreTo = null
  let scheduled = false

  doc.addEventListener('keydown', (e) => {
    if (e.key !== 'Tab' || e.defaultPrevented || e.isComposing) return
    const layer = activeModalLayer(doc, win)
    if (!layer) return
    e.preventDefault()
    const list = focusablesIn(layer, win)
    if (!list.length) { focusLayer(layer); return }
    const i = list.indexOf(doc.activeElement)
    const next = e.shiftKey
      ? list[(i <= 0 ? list.length : i) - 1]
      : list[(i + 1) % list.length]
    try { next.focus({ preventScroll: true }) } catch (err) { /* ignore */ }
  }, true)

  doc.addEventListener('focusin', (e) => {
    const t = e.target
    if (!isFrame(t)) return
    const layer = modalLayerOver(t, doc, win)
    if (layer) pullInto(layer, win)
  }, true)

  // 弹窗在焦点已经在编辑器里时弹出（异步弹的确认框、快捷键开的面板）：客体页收着键盘，
  // 宿主一个 keydown 都看不到，只能在 DOM 变化时查一次。只有焦点在框体上时才做几何判断。
  const tick = () => {
    scheduled = false
    const a = doc.activeElement
    if (isFrame(a)) {
      const layer = modalLayerOver(a, doc, win)
      if (layer) { restoreTo = a; pullInto(layer, win) }
      return
    }
    if (!restoreTo) return
    if (!restoreTo.isConnected) { restoreTo = null; return }
    if (modalLayerOver(restoreTo, doc, win)) return
    const f = restoreTo
    restoreTo = null
    // 用户关弹窗后已经把焦点放到别处（聊天框等）就不抢
    if (!a || a === doc.body || a === doc.documentElement) { try { f.focus() } catch (e) { /* ignore */ } }
  }
  const schedule = () => {
    if (scheduled) return
    scheduled = true
    ;(win.requestAnimationFrame || ((cb) => win.setTimeout(cb, 16)))(tick)
  }
  try {
    new win.MutationObserver(schedule).observe(doc.body || doc.documentElement, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class', 'hidden'],
    })
  } catch (e) { /* ignore */ }
}
