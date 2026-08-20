// 离开工作台前必须落盘：否则自动保存防抖窗口内的改动静默丢失。
//
// 病灶：closeFile / evictLibreInstance 都会先 await flushSave 再拆实例，但**离开整个
// 页面**的三条路（切项目 / 返回项目列表 / 退出登录）走的是 uni.reLaunch，页面组件树
// 直接销毁，一次 flush 都没有。LibreOfficeEditor 的 beforeUnmount 自己写着
// 「export 需要活的 webview，从这里保存已经太晚」——Office 文档那几秒的改动就这么没了，
// 而且没有任何提示。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { flushDirtyEditors } from '../../src/pages/project-overview/flushDirtyEditors.js'

const PAGE = readFileSync(
  new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')

const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

function libre({ dirty = false, saving = false, ready = true, isError = false, file = { id: 1 } } = {}) {
  const inst = { dirty, saving, ready, isError, file, flushed: 0 }
  inst.flushSave = async () => { inst.flushed++ }
  return inst
}

test('脏的 Office 文档在离开前被落盘', async () => {
  const a = libre({ dirty: true })
  const b = libre({ saving: true })
  const res = await flushDirtyEditors({ 'left:1': a, 'right:2': b }, {})
  assert.equal(a.flushed, 1)
  assert.equal(b.flushed, 1)
  assert.equal(res.flushed, 2)
})

test('干净的实例不空存一次', async () => {
  const clean = libre({ dirty: false, saving: false })
  const res = await flushDirtyEditors({ 'left:1': clean }, {})
  assert.equal(clean.flushed, 0)
  assert.equal(res.flushed, 0)
})

test('加载失败/未就绪的实例绝不保存——画布是空白原型，存下去等于清空真文件', async () => {
  const broken = libre({ dirty: true, isError: true })
  const notReady = libre({ dirty: true, ready: false })
  await flushDirtyEditors({ 'left:1': broken, 'left:2': notReady }, {})
  assert.equal(broken.flushed, 0, '与 closeFile / evictLibreInstance 同一取舍')
  assert.equal(notReady.flushed, 0)
})

test('纯文本编辑器同样落盘', async () => {
  const text = libre({ dirty: true })
  const res = await flushDirtyEditors({}, { left: text })
  assert.equal(text.flushed, 1)
  assert.equal(res.flushed, 1)
})

test('一个实例保存失败不许拖累其它实例', async () => {
  const bad = libre({ dirty: true })
  bad.flushSave = async () => { throw new Error('boom') }
  const good = libre({ dirty: true })
  const res = await flushDirtyEditors({ 'left:1': bad, 'left:2': good }, {})
  assert.equal(good.flushed, 1, '前一个抛了，后一个还得存')
  assert.equal(res.failed, 1)
  assert.equal(res.flushed, 1)
})

test('空/缺失的注册表不炸', async () => {
  await flushDirtyEditors(undefined, undefined)
  await flushDirtyEditors({}, {})
  await flushDirtyEditors({ 'left:1': null }, { left: undefined })
})

test('工作台离开路径只有一个出口：除 leaveWorkbench / handleLogout 外不许裸 reLaunch', () => {
  const code = stripComments(PAGE)
  const bare = [...code.matchAll(/uni\.reLaunch\(/g)]
  assert.ok(bare.length <= 2,
    `工作台的离开路径必须先落盘。裸 uni.reLaunch 出现 ${bare.length} 处，` +
    '新增离开路径请走 leaveWorkbench()（或像 handleLogout 那样自己先 flush 再清会话）')
  assert.ok(code.includes('async leaveWorkbench(url)'), 'leaveWorkbench 出口必须还在')
  assert.ok(code.includes('flushDirtyEditors'), '离开前必须调用 flushDirtyEditors')
})

test('退出登录时落盘排在 clearSession 之前，否则保存请求已经没有会话', () => {
  const code = stripComments(PAGE)
  const logout = code.slice(code.indexOf('async handleLogout()'))
  const flushAt = logout.indexOf('flushDirtyEditors')
  const clearAt = logout.indexOf('clearSession()')
  assert.ok(flushAt >= 0 && clearAt >= 0, 'handleLogout 里两者都应存在')
  assert.ok(flushAt < clearAt, '会话一清，保存就是未授权——落盘必须排在前面')
})
