// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 应用内对话框（AwdDialog，dev-board#849）的纯逻辑：参数归一化、缺省文案、危险色映射、
// uni 回调形状、键位语义。**本文件刻意零依赖**（不引 vue / uni / @/i18n），
// 好让 tests/dialog/awd-dialog.test.mjs 用 node 直接导入；翻译函数由调用方注入。
//
// 回调形状逐项对齐 uni-h5 自己的实现（node_modules/@dcloudio/uni-h5/dist/uni-h5.es.js
// 的 onModalClose / onActionSheetClose / createAsyncApiCallback）：
//   showModal       → success({ errMsg:'showModal:ok', confirm, cancel, content? })，然后 complete
//                     （content 只在 editable 且点了确认时才有）
//   showActionSheet → 选中：success({ errMsg:'showActionSheet:ok', tapIndex })
//                     取消：fail({ errMsg:'showActionSheet:fail cancel' })，然后 complete
// 不带回调的 Promise 调用法由 uni 的 promisify 负责：它把 resolve/reject 当 success/fail
// 塞进参数里再交给拦截器，所以这里只需要调 success/fail，两种调用法天然一致。

/** 等同「危险动作」的确认色。大小写与空白不敏感。 */
const DANGER_COLORS = new Set([
  '#dc3545', // DdRequestEditor 的历史写法
  '#b5483c', // TagManager 的历史写法 = 浅色 --awd-danger
  '#cc6154', // 深色 --awd-danger
  'var(--awd-danger)',
  'red',
])

export function isDangerColor(color) {
  if (typeof color !== 'string') return false
  return DANGER_COLORS.has(color.replace(/\s+/g, '').toLowerCase())
}

function text(v) {
  if (v === undefined || v === null) return ''
  return String(v)
}

function nonEmpty(v) {
  return typeof v === 'string' && v.trim() !== '' ? v : ''
}

/**
 * showModal 参数 → 对话框属性。幂等：已归一化过的对象再过一遍结果不变。
 * `translate(key)` 取缺省按钮文案（common.confirm / common.cancel）。
 */
export function normalizeModalOptions(opts, translate) {
  const o = opts && typeof opts === 'object' ? opts : {}
  const tr = typeof translate === 'function' ? translate : (k) => k
  return {
    title: text(o.title),
    content: text(o.content),
    showCancel: o.showCancel !== false,
    // uni-h5 在参数缺省时给的是写死的英文 OK / Cancel——这正是要修的病灶。
    // 传了空串也按缺省处理：一个没有字的按钮只会让人以为界面坏了。
    confirmText: nonEmpty(o.confirmText) || tr('common.confirm'),
    cancelText: nonEmpty(o.cancelText) || tr('common.cancel'),
    danger: o.danger === true || isDangerColor(o.confirmColor),
    editable: o.editable === true,
    placeholderText: text(o.placeholderText),
  }
}

/** showActionSheet 参数 → 选项表属性。itemList 不是非空数组时返回 null（参数错误）。 */
export function normalizeSheetOptions(opts, translate) {
  const o = opts && typeof opts === 'object' ? opts : {}
  const tr = typeof translate === 'function' ? translate : (k) => k
  if (!Array.isArray(o.itemList) || o.itemList.length === 0) return null
  const color = typeof o.itemColor === 'string' ? o.itemColor.trim().toLowerCase() : ''
  return {
    title: text(o.title),
    itemList: o.itemList.map(text),
    // uni 的缺省 itemColor 是 #000，照搬会让深色主题下的选项文字看不见；
    // 只有调用方真给了别的颜色才透传。
    itemColor: color && color !== '#000' && color !== '#000000' ? o.itemColor : '',
    cancelText: nonEmpty(o.cancelText) || tr('common.cancel'),
  }
}

/** 对话框关闭结果 → uni showModal 的 success 回包。 */
export function toUniModalResult(result, editable) {
  const confirm = !!(result && result.confirm)
  const res = { errMsg: 'showModal:ok', confirm, cancel: !confirm }
  if (confirm && editable) res.content = text(result && result.content)
  return res
}

/** 选项表关闭结果 → [是否成功, uni 回包]。 */
export function toUniSheetResult(result) {
  const i = result && Number.isInteger(result.tapIndex) ? result.tapIndex : -1
  if (i < 0) return [false, { errMsg: 'showActionSheet:fail cancel' }]
  return [true, { errMsg: 'showActionSheet:ok', tapIndex: i }]
}

/** 按 uni 的顺序派发回调：success 或 fail，然后 complete。 */
export function settleUniCall(args, ok, res) {
  const a = args || {}
  if (ok) {
    if (typeof a.success === 'function') a.success(res)
  } else if (typeof a.fail === 'function') {
    a.fail(res)
  }
  if (typeof a.complete === 'function') a.complete(res)
}

/**
 * uni.addInterceptor('showModal', { invoke }) 的 invoke。
 * 返回 false = 让 uni 放弃原生弹窗（uni-h5 queue()：invoke 返回 false 即中止调用），
 * 回调由这里按原形状补发。`show(normalized)` 须返回 Promise<{confirm, content?}>。
 */
export function createModalInvoke(show, translate) {
  return function invokeShowModal(args) {
    const opts = normalizeModalOptions(args, translate)
    Promise.resolve(show(opts)).then((r) => settleUniCall(args, true, toUniModalResult(r, opts.editable)))
    return false
  }
}

/** uni.addInterceptor('showActionSheet', { invoke }) 的 invoke。`show` 返回 Promise<{tapIndex}>。 */
export function createSheetInvoke(show, translate) {
  return function invokeShowActionSheet(args) {
    const opts = normalizeSheetOptions(args, translate)
    if (!opts) {
      settleUniCall(args, false, { errMsg: 'showActionSheet:fail parameter error: parameter.itemList should be a non-empty Array' })
      return false
    }
    Promise.resolve(show(opts)).then((r) => {
      const [ok, res] = toUniSheetResult(r)
      settleUniCall(args, ok, res)
    })
    return false
  }
}

/**
 * 对话框内的键位语义。返回要执行的动作：
 *   'cancel'   —— Esc（showCancel:false 时也照样关，回包按取消）
 *   'activate' —— Enter 且焦点在对话框内某个按钮上：按那个按钮
 *                 （危险动作打开时焦点在「取消」，回车因此是安全的取消而不是确认）
 *   'confirm'  —— Enter 且焦点不在按钮上（输入框里、或焦点落在面板本身）；选项表没有确认
 *   'trap'     —— Tab / Shift+Tab：由组件在对话框内循环焦点
 *   null       —— 其他键，或输入法正在组字（组字时的回车是上屏，不是提交）
 */
export function resolveDialogKey({ key, isComposing, kind, focusOnButton }) {
  if (isComposing) return null
  if (key === 'Escape' || key === 'Esc') return 'cancel'
  if (key === 'Tab') return 'trap'
  if (key === 'Enter') {
    if (focusOnButton) return 'activate'
    return kind === 'sheet' ? null : 'confirm'
  }
  return null
}
