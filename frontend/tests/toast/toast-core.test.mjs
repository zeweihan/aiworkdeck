// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 统一 toast 体系（dev-board#891）的纯逻辑。接线在 src/utils/toast.js，
// 视觉宿主在 src/components/AwdToastHost.vue，本文件只测零依赖的 toastCore.js。
// 跑法：npm run test:toast

import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  MAX_STACK,
  normalizeToastOptions,
  normalizeLoadingOptions,
  toastSemantic,
  pushToastItem,
  removeToastItem,
  createShowToastInvoke,
  createHideToastInvoke,
  createShowLoadingInvoke,
  createHideLoadingInvoke,
} from '../../src/utils/toastCore.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SRC = path.resolve(HERE, '../../src')
const read = (rel) => fs.readFileSync(path.join(SRC, rel), 'utf8')

test('icon 缺省或非法值回落 success（对齐 uni-h5 的 elemInArray 取数组第一项）', () => {
  assert.equal(normalizeToastOptions({}).icon, 'success')
  assert.equal(normalizeToastOptions({ icon: 'bogus' }).icon, 'success')
  assert.equal(normalizeToastOptions({ icon: 'none' }).icon, 'none')
  assert.equal(normalizeToastOptions({ icon: 'error' }).icon, 'error')
  assert.equal(normalizeToastOptions({ icon: 'loading' }).icon, 'loading')
})

test('duration 缺省 1500ms（uni ShowToastOptions 同值），非法值一律回落', () => {
  assert.equal(normalizeToastOptions({}).duration, 1500)
  assert.equal(normalizeToastOptions({ duration: 3000 }).duration, 3000)
  assert.equal(normalizeToastOptions({ duration: 0 }).duration, 1500)
  assert.equal(normalizeToastOptions({ duration: -1 }).duration, 1500)
  assert.equal(normalizeToastOptions({ duration: 'x' }).duration, 1500)
})

test('mask 缺省 false，title 非字符串强转', () => {
  const o = normalizeToastOptions({ title: 42 })
  assert.equal(o.mask, false)
  assert.equal(o.title, '42')
  assert.equal(normalizeToastOptions({ mask: true }).mask, true)
  assert.equal(normalizeToastOptions({ mask: 'yes' }).mask, false)
})

test('showLoading 归一化：只有 title/mask，没有 icon/duration', () => {
  const o = normalizeLoadingOptions({ title: '上传中', mask: true })
  assert.deepEqual(o, { title: '上传中', mask: true })
  assert.deepEqual(normalizeLoadingOptions({}), { title: '', mask: false })
})

test('icon → 视觉语义映射：success/error 各自独立，none 与未知值落中性 info', () => {
  assert.equal(toastSemantic('success'), 'success')
  assert.equal(toastSemantic('error'), 'error')
  assert.equal(toastSemantic('none'), 'info')
  assert.equal(toastSemantic('loading'), 'info')
  assert.equal(toastSemantic(undefined), 'info')
})

test('堆叠上限：超过 MAX_STACK 挤掉最旧的一条，不修改入参数组', () => {
  let list = []
  for (let i = 1; i <= MAX_STACK + 2; i++) {
    const prev = list
    list = pushToastItem(list, { id: i })
    assert.notEqual(list, prev, '返回新数组，不原地修改')
  }
  assert.equal(list.length, MAX_STACK)
  // 最旧的两条（id 1、2）被挤掉，留下最新的 MAX_STACK 条
  assert.deepEqual(list.map((it) => it.id), [3, 4, 5, 6])
})

test('pushToastItem 不修改传入的原数组', () => {
  const original = [{ id: 1 }]
  const next = pushToastItem(original, { id: 2 })
  assert.equal(original.length, 1)
  assert.equal(next.length, 2)
})

test('removeToastItem 按 id 移除，不影响其余条目', () => {
  const list = [{ id: 1 }, { id: 2 }, { id: 3 }]
  const next = removeToastItem(list, 2)
  assert.deepEqual(next.map((it) => it.id), [1, 3])
  assert.equal(list.length, 3, '不修改原数组')
})

test('showToast 接管：invoke 返回 false 放弃原生 toast，push 收到归一化后的选项，success/complete 立即回', () => {
  const pushed = []
  const calls = []
  const invoke = createShowToastInvoke((o) => pushed.push(o))
  const ret = invoke({
    title: '已保存',
    icon: 'success',
    success: (r) => calls.push(['success', r]),
    complete: (r) => calls.push(['complete', r]),
  })
  assert.equal(ret, false)
  assert.equal(pushed.length, 1)
  assert.equal(pushed[0].title, '已保存')
  assert.equal(pushed[0].icon, 'success')
  assert.deepEqual(calls, [
    ['success', { errMsg: 'showToast:ok' }],
    ['complete', { errMsg: 'showToast:ok' }],
  ])
})

test('showToast 不等待任何交互——不像 showModal，加入队列后立刻结算（同步，不是等 duration 到时）', () => {
  let settled = false
  const invoke = createShowToastInvoke(() => {})
  invoke({ title: 'x', success: () => { settled = true } })
  assert.equal(settled, true)
})

test('hideToast 接管：清空回调被调用，invoke 返回 false', () => {
  let cleared = false
  let res = null
  const invoke = createHideToastInvoke(() => { cleared = true })
  const ret = invoke({ success: (r) => { res = r } })
  assert.equal(ret, false)
  assert.equal(cleared, true)
  assert.deepEqual(res, { errMsg: 'hideToast:ok' })
})

test('showLoading / hideLoading 配对：各自的回调都被正确调用一次', () => {
  let loadingOpts = null
  let cleared = false
  const showInvoke = createShowLoadingInvoke((o) => { loadingOpts = o })
  const hideInvoke = createHideLoadingInvoke(() => { cleared = true })

  const showRet = showInvoke({ title: '处理中', mask: true, success: () => {} })
  assert.equal(showRet, false)
  assert.deepEqual(loadingOpts, { title: '处理中', mask: true })
  assert.equal(cleared, false, 'showLoading 不应触发 hideLoading 的回调')

  const hideRet = hideInvoke({ success: () => {} })
  assert.equal(hideRet, false)
  assert.equal(cleared, true)
})

test('接线：H5 入口安装 toast 桥接；接管走拦截器（同 dialog.js 的摇树坑）', () => {
  const main = read('main.js')
  assert.match(main, /\/\/ #ifdef H5[\s\S]*installUniToastBridge\(\)[\s\S]*\/\/ #endif/)
  const toast = read('utils/toast.js')
  assert.match(toast, /uni\.addInterceptor\('showToast'/)
  assert.match(toast, /uni\.addInterceptor\('hideToast'/)
  assert.match(toast, /uni\.addInterceptor\('showLoading'/)
  assert.match(toast, /uni\.addInterceptor\('hideLoading'/)
  assert.doesNotMatch(toast, /uni\.showToast\s*=/)
})

test('App.vue 里旧的 uni-toast zfix 与专属样式块已删除', () => {
  const app = read('App.vue')
  assert.doesNotMatch(app, /awd-uni-modal-zfix/)
  assert.doesNotMatch(app, /uni-toast \.uni-toast\b/)
  assert.doesNotMatch(app, /uni-simple-toast__text/)
})
