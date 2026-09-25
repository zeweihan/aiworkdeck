// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 横滚容器「两端还能不能滚」的量取与订阅（编辑器工具栏主命令区在用，v0.49.0 BUG-13/15）。
//
// 为什么单独成文件：uni-h5 把 <scroll-view> 渲染成
//   <uni-scroll-view> > div.uni-scroll-view > div.uni-scroll-view(overflow-x:auto) > div.uni-scroll-view-content
// （node_modules/@dcloudio/uni-h5/dist/uni-h5.es.js 的 ScrollView render），真正滚动、
// 真正派发 scroll 事件的是**倒数第二层**那个 div，不是我们挂 class 的 <uni-scroll-view>。
// scroll 事件不冒泡，挂在外层上一次都收不到；外层自己也从不溢出，scrollLeft/scrollWidth
// 量出来永远是「滚不动」。第一版就是这么写的——渐隐遮罩永不出现、滚动关下拉永不触发，
// 在真实 DOM 里是死代码。这里把「找真正的滚动层」钉成一个函数，jsdom 真渲染测试守着它。

/**
 * uni <scroll-view> 真正滚动的那一层；传进来的已经是普通元素（非 uni 结构）就原样返回。
 * @param {any} root 模板上挂 class 的那个元素（<uni-scroll-view class="etb-scroll">）
 * @returns {any}
 */
export function scrollViewMain(root) {
  if (!root || typeof root.querySelector !== 'function') return root || null
  const content = root.querySelector('.uni-scroll-view-content')
  return (content && content.parentElement) || root
}

/**
 * 两端是否还有可滚余量（1px 容差吃掉亚像素）。
 * @param {any} el 真正滚动的元素
 * @returns {{ left: boolean, right: boolean }}
 */
export function scrollEdges(el) {
  if (!el) return { left: false, right: false }
  const left = el.scrollLeft > 1
  const right = el.scrollLeft + el.clientWidth < el.scrollWidth - 1
  return { left, right }
}

/**
 * 订阅两端余量的变化。
 *  - onEdges({left,right})：滚动、窗口尺寸变化、内容宽度变化（表格组随光标进出表格出现/消失）
 *    之后都重新量一次并回调；
 *  - onScrolled()：**只在 scrollLeft 真的变了**时回调——给「横滚就收下拉」用。内容尺寸变化
 *    不算滚动：打开「插入」下拉会顺手刷新工具栏状态，表格组的出现/消失会改内容宽度，
 *    要是把它也当滚动，下拉刚打开就被自己关掉。
 * @param {any} root 同 scrollViewMain
 * @param {{ onEdges?: Function, onScrolled?: Function, win?: any }} handlers
 * @returns {() => void} 摘除函数
 */
export function watchScrollEdges(root, { onEdges, onScrolled, win } = {}) {
  const el = scrollViewMain(root)
  if (!el || typeof el.addEventListener !== 'function') return () => {}
  const w = win || (typeof window !== 'undefined' ? window : null)
  let lastLeft = el.scrollLeft
  const measure = () => { if (typeof onEdges === 'function') onEdges(scrollEdges(el)) }
  const onScroll = () => {
    const now = el.scrollLeft
    if (now !== lastLeft) {
      lastLeft = now
      if (typeof onScrolled === 'function') onScrolled()
    }
    measure()
  }
  el.addEventListener('scroll', onScroll, { passive: true })
  if (w && typeof w.addEventListener === 'function') w.addEventListener('resize', measure)
  // 内容宽度变化：表格组等按钮组是 v-if 进出的（光标进出表格），不滚动也不改窗口尺寸。
  // 内层 .etb-row 是块级 flex，宽度恒等于容器、溢出的是它的子项，所以 ResizeObserver
  // 盯内容盯不出来——盯 DOM 增删（MutationObserver）+ 容器自身尺寸（ResizeObserver）。
  let ro = null, mo = null
  const RO = (w && w.ResizeObserver) || (typeof ResizeObserver !== 'undefined' ? ResizeObserver : null)
  const MO = (w && w.MutationObserver) || (typeof MutationObserver !== 'undefined' ? MutationObserver : null)
  if (RO) { try { ro = new RO(measure); ro.observe(el) } catch (e) { ro = null } }
  if (MO) { try { mo = new MO(measure); mo.observe(el, { childList: true, subtree: true }) } catch (e) { mo = null } }
  measure()
  return () => {
    el.removeEventListener('scroll', onScroll)
    if (w && typeof w.removeEventListener === 'function') w.removeEventListener('resize', measure)
    if (ro) { try { ro.disconnect() } catch (e) { /* ignore */ } }
    if (mo) { try { mo.disconnect() } catch (e) { /* ignore */ } }
  }
}
