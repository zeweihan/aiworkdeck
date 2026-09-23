// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 应用内对话框 AwdDialog 的桥接层（dev-board#849）。
// 纯逻辑在 src/utils/dialogCore.js（零依赖，node 直接导入）；接线处用源码断言钉住。
// 跑法：npm run test:dialog

import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  isDangerColor,
  normalizeModalOptions,
  normalizeSheetOptions,
  toUniModalResult,
  createModalInvoke,
  createSheetInvoke,
  resolveDialogKey,
} from '../../src/utils/dialogCore.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SRC = path.resolve(HERE, '../../src')
const read = (rel) => fs.readFileSync(path.join(SRC, rel), 'utf8')

const ZH = { 'common.confirm': '确定', 'common.cancel': '取消' }
const EN = { 'common.confirm': 'OK', 'common.cancel': 'Cancel' }
const zh = (k) => ZH[k] || k
const en = (k) => EN[k] || k

const flush = () => new Promise((r) => setTimeout(r, 0))

test('缺省按钮文案走 i18n，不是 uni-h5 写死的英文 OK/Cancel', () => {
  const o = normalizeModalOptions({ title: '标题', content: '正文' }, zh)
  assert.equal(o.confirmText, '确定')
  assert.equal(o.cancelText, '取消')
  const e = normalizeModalOptions({ title: 't' }, en)
  assert.equal(e.confirmText, 'OK')
  assert.equal(e.cancelText, 'Cancel')
})

test('显式文案原样保留；空串按缺省处理', () => {
  const o = normalizeModalOptions({ confirmText: '立即重启', cancelText: '稍后' }, zh)
  assert.equal(o.confirmText, '立即重启')
  assert.equal(o.cancelText, '稍后')
  const blank = normalizeModalOptions({ confirmText: '', cancelText: '  ' }, zh)
  assert.equal(blank.confirmText, '确定')
  assert.equal(blank.cancelText, '取消')
})

test('showCancel / editable / content 的缺省与类型归一', () => {
  const o = normalizeModalOptions({ content: 42 }, zh)
  assert.equal(o.showCancel, true)
  assert.equal(o.editable, false)
  assert.equal(o.content, '42')
  assert.equal(o.title, '')
  assert.equal(normalizeModalOptions({ showCancel: false }, zh).showCancel, false)
})

test('confirmColor 红色字面量映射 danger，大小写与空白不敏感；普通色不算', () => {
  assert.equal(isDangerColor('#DC3545'), true)
  assert.equal(isDangerColor('#b5483c'), true)
  assert.equal(isDangerColor(' #B5483C '), true)
  assert.equal(isDangerColor('#CC6154'), true, '深色主题的 --awd-danger')
  assert.equal(isDangerColor('var(--awd-danger)'), true)
  assert.equal(isDangerColor('#007aff'), false)
  assert.equal(isDangerColor(undefined), false)
  assert.equal(normalizeModalOptions({ confirmColor: '#DC3545' }, zh).danger, true)
  assert.equal(normalizeModalOptions({ danger: true }, zh).danger, true)
  assert.equal(normalizeModalOptions({ confirmColor: '#2E5A50' }, zh).danger, false)
})

test('归一化幂等：showDialog 再过一遍接管层的产物，结果不变', () => {
  const once = normalizeModalOptions({ confirmColor: '#DC3545', title: 'x' }, zh)
  assert.deepEqual(normalizeModalOptions(once, zh), once)
})

test('showModal 接管：invoke 返回 false 以放弃原生弹窗，success 回包与 uni 同形，complete 在后', async () => {
  const calls = []
  let shown = null
  const invoke = createModalInvoke((o) => { shown = o; return Promise.resolve({ confirm: true }) }, zh)
  const ret = invoke({
    title: 'T',
    confirmColor: '#B5483C',
    success: (r) => calls.push(['success', r]),
    fail: (r) => calls.push(['fail', r]),
    complete: (r) => calls.push(['complete', r]),
  })
  assert.equal(ret, false)
  assert.equal(shown.danger, true)
  assert.equal(shown.cancelText, '取消')
  await flush()
  assert.deepEqual(calls.map((c) => c[0]), ['success', 'complete'])
  assert.deepEqual(calls[0][1], { errMsg: 'showModal:ok', confirm: true, cancel: false })
})

test('showModal 取消：{confirm:false, cancel:true}，且不带 content', async () => {
  let res = null
  createModalInvoke(() => Promise.resolve({ confirm: false, cancel: true, content: 'x' }), zh)({
    editable: true,
    success: (r) => { res = r },
  })
  await flush()
  assert.deepEqual(res, { errMsg: 'showModal:ok', confirm: false, cancel: true })
})

test('editable 确认回 content；非 editable 确认不带 content', () => {
  assert.deepEqual(toUniModalResult({ confirm: true, content: '新文件夹' }, true),
    { errMsg: 'showModal:ok', confirm: true, cancel: false, content: '新文件夹' })
  assert.equal('content' in toUniModalResult({ confirm: true, content: 'x' }, false), false)
})

test('Promise 调用法：uni 的 promisify 把 resolve 当 success 塞进来，同样兑现', async () => {
  const invoke = createModalInvoke(() => Promise.resolve({ confirm: true }), zh)
  const res = await new Promise((resolve, reject) => {
    invoke({ title: 'x', success: resolve, fail: reject })
  })
  assert.equal(res.confirm, true)
  assert.equal(res.cancel, false)
})

test('showActionSheet：选中回 success({tapIndex})，取消回 fail(cancel)，两者之后都有 complete', async () => {
  const picked = []
  createSheetInvoke(() => Promise.resolve({ tapIndex: 1 }), zh)({
    itemList: ['A', 'B', 'C'],
    success: (r) => picked.push(['success', r]),
    complete: () => picked.push(['complete']),
  })
  const cancelled = []
  createSheetInvoke(() => Promise.resolve({ tapIndex: -1 }), zh)({
    itemList: ['A'],
    success: (r) => cancelled.push(['success', r]),
    fail: (r) => cancelled.push(['fail', r]),
    complete: () => cancelled.push(['complete']),
  })
  await flush()
  assert.deepEqual(picked, [['success', { errMsg: 'showActionSheet:ok', tapIndex: 1 }], ['complete']])
  assert.deepEqual(cancelled, [['fail', { errMsg: 'showActionSheet:fail cancel' }], ['complete']])
})

test('showActionSheet 参数错误：itemList 缺失即 fail，不弹任何东西', () => {
  let shown = false
  let failed = null
  const ret = createSheetInvoke(() => { shown = true }, zh)({ fail: (r) => { failed = r } })
  assert.equal(ret, false)
  assert.equal(shown, false)
  assert.match(failed.errMsg, /^showActionSheet:fail parameter error/)
})

test('选项表：uni 缺省的 #000 选项色不透传（深色主题下会看不见），缺省取消文案走 i18n', () => {
  assert.equal(normalizeSheetOptions({ itemList: ['A'], itemColor: '#000' }, zh).itemColor, '')
  assert.equal(normalizeSheetOptions({ itemList: ['A'], itemColor: '#B5483C' }, zh).itemColor, '#B5483C')
  assert.equal(normalizeSheetOptions({ itemList: ['A'] }, en).cancelText, 'Cancel')
  assert.equal(normalizeSheetOptions({ itemList: [] }, zh), null)
})

test('键位语义：Esc 取消、Enter 按焦点按钮或确认、Tab 循环、组字中的回车不算', () => {
  assert.equal(resolveDialogKey({ key: 'Escape', kind: 'modal' }), 'cancel')
  assert.equal(resolveDialogKey({ key: 'Enter', kind: 'modal', focusOnButton: false }), 'confirm')
  assert.equal(resolveDialogKey({ key: 'Enter', kind: 'modal', focusOnButton: true }), 'activate')
  assert.equal(resolveDialogKey({ key: 'Enter', kind: 'sheet', focusOnButton: false }), null)
  assert.equal(resolveDialogKey({ key: 'Enter', kind: 'modal', isComposing: true }), null)
  assert.equal(resolveDialogKey({ key: 'Tab', kind: 'modal' }), 'trap')
  assert.equal(resolveDialogKey({ key: 'a', kind: 'modal' }), null)
})

test('接线：H5 入口安装桥接；接管走拦截器（发行构建摇树后改写 uni.showModal 属性无效）', () => {
  const main = read('main.js')
  assert.match(main, /\/\/ #ifdef H5[\s\S]*installUniDialogBridge\(\)[\s\S]*\/\/ #endif/)
  const dialog = read('utils/dialog.js')
  assert.match(dialog, /uni\.addInterceptor\('showModal'/)
  assert.match(dialog, /uni\.addInterceptor\('showActionSheet'/)
  assert.doesNotMatch(dialog, /uni\.showModal\s*=/)
})

test('危险色调用点改传 danger:true；遮罩进了无拖拽名单；uni-modal 颜色覆盖已删', () => {
  assert.doesNotMatch(read('components/DdRequestEditor.vue'), /confirmColor/)
  assert.doesNotMatch(read('components/TagManager.vue'), /confirmColor/)
  const app = read('App.vue')
  assert.match(app, /html\.is-desktop \.awd-dlg-mask,/)
  assert.doesNotMatch(app, /\.uni-modal__btn/)
  assert.match(app, /--awd-font-sans:/)
  assert.match(app, /html,\s*body,\s*uni-modal,\s*uni-toast\s*\{\s*font-family: var\(--awd-font-sans\)/)
})

test('字体令牌与 uni.scss 的三条字栈逐字一致', () => {
  const scss = read('uni.scss')
  const app = read('App.vue')
  for (const name of ['serif', 'sans', 'mono']) {
    const m = scss.match(new RegExp('\\$awd-font-' + name + ':\\s*([^;]+);'))
    assert.ok(m, 'uni.scss 缺 $awd-font-' + name)
    assert.ok(app.includes('--awd-font-' + name + ': ' + m[1].trim() + ';'), '--awd-font-' + name + ' 与 uni.scss 不一致')
  }
})
