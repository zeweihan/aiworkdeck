// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 统一 toast 体系（dev-board#891）的纯逻辑：参数归一化（对齐 uni-h5 自己的默认值，
// node_modules/@dcloudio/uni-h5/dist/uni-h5.es.js 的 ShowToastOptions/ShowLoadingOptions/
// showLoadingDefaultState）、icon→视觉语义映射、堆叠队列的增删。
// **本文件刻意零依赖**（不引 vue / uni / @/i18n），好让 tests/toast/*.test.mjs 用 node
// 直接导入；接线层在同目录 toast.js（照 dialog.js 的先例）。
//
// 与 uni 原生的关键差异：uni 的 showToast/showLoading 共用同一个单例弹层，后一个
// 直接把前一个覆盖掉（node_modules 里的 createToast 只有一个 showToastState）。
// 这里改成多条纵向堆叠、互不覆盖——pushToastItem 就是这份差异的落点。

const TOAST_ICONS = ['success', 'loading', 'none', 'error']
const DEFAULT_DURATION = 1500
/** 堆叠上限：超过时挤掉最旧的一条，保证新提示总能露出来（uni 原生没有这个概念）。 */
export const MAX_STACK = 4

function text(v) {
  return v === undefined || v === null ? '' : String(v)
}

/**
 * showToast 参数 → 归一化后的属性。
 * icon 缺省或非法值一律回落 'success'（对齐 uni-h5 的 elemInArray(type, SHOW_TOAST_ICON)：
 * 不在白名单里就取数组第一项）；duration 缺省 1500ms、mask 缺省 false，同 uni 一致。
 */
export function normalizeToastOptions(opts) {
  const o = opts && typeof opts === 'object' ? opts : {}
  const icon = TOAST_ICONS.includes(o.icon) ? o.icon : 'success'
  const duration = typeof o.duration === 'number' && o.duration > 0 ? o.duration : DEFAULT_DURATION
  return {
    title: text(o.title),
    icon,
    duration,
    mask: o.mask === true,
  }
}

/** showLoading 参数 → 归一化。没有 icon/duration 概念——持续到 hideLoading 才收起。 */
export function normalizeLoadingOptions(opts) {
  const o = opts && typeof opts === 'object' ? opts : {}
  return {
    title: text(o.title),
    mask: o.mask === true,
  }
}

/** icon → 视觉语义三态（决定左侧色条与图标）。'none' 与未知值一律落中性 info。 */
export function toastSemantic(icon) {
  if (icon === 'success') return 'success'
  if (icon === 'error') return 'error'
  return 'info'
}

/** 追加一条 toast；超过 MAX_STACK 时挤掉最旧的一条。不修改入参，返回新数组。 */
export function pushToastItem(list, item) {
  const next = (Array.isArray(list) ? list : []).slice()
  next.push(item)
  while (next.length > MAX_STACK) next.shift()
  return next
}

/** 按 id 移除一条（单条到时自动消失、或整体 hideToast 时用）。 */
export function removeToastItem(list, id) {
  return (Array.isArray(list) ? list : []).filter((it) => it.id !== id)
}

/** 按 uni 的顺序派发回调：showToast/showLoading/hideToast/hideLoading 都不等待
 *  用户交互，加入状态后立刻算成功（这点与 showModal 不同，后者要等对话框关闭）。 */
function settle(args, res) {
  const a = args || {}
  if (typeof a.success === 'function') a.success(res)
  if (typeof a.complete === 'function') a.complete(res)
}

/**
 * uni.addInterceptor('showToast', { invoke }) 的 invoke。
 * `push(normalized)` 把一条 toast 塞进宿主状态。返回 false = 放弃原生 <uni-toast>
 * （摇树后的发行构建里改写 uni.showToast 属性本身无效，原因见 dialog.js 顶部注释，
 * 这里是同一套接管方式）。
 */
export function createShowToastInvoke(push) {
  return function invokeShowToast(args) {
    const opts = normalizeToastOptions(args)
    push(opts)
    settle(args, { errMsg: 'showToast:ok' })
    return false
  }
}

/** uni.addInterceptor('hideToast', { invoke }) 的 invoke。`hide()` 清空当前 toast 队列。 */
export function createHideToastInvoke(hide) {
  return function invokeHideToast(args) {
    hide()
    settle(args, { errMsg: 'hideToast:ok' })
    return false
  }
}

/** uni.addInterceptor('showLoading', { invoke }) 的 invoke。`setLoading(normalized)` 置持久态。 */
export function createShowLoadingInvoke(setLoading) {
  return function invokeShowLoading(args) {
    const opts = normalizeLoadingOptions(args)
    setLoading(opts)
    settle(args, { errMsg: 'showLoading:ok' })
    return false
  }
}

/** uni.addInterceptor('hideLoading', { invoke }) 的 invoke。`clearLoading()` 收起持久态。 */
export function createHideLoadingInvoke(clearLoading) {
  return function invokeHideLoading(args) {
    clearLoading()
    settle(args, { errMsg: 'hideLoading:ok' })
    return false
  }
}
