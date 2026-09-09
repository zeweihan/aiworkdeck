// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
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

function libre({ dirty = false, saving = false, ready = true, isError = false, docLoadFailed = false, file = { id: 1 } } = {}) {
  const inst = { dirty, saving, ready, isError, docLoadFailed, file, flushed: 0 }
  inst.flushSave = async () => { inst.flushed++; inst.dirty = false; inst.saving = false }
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
  const broken = libre({ dirty: true, isError: true, docLoadFailed: true })
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


test('保存失败态仍要尝试；返回 false 或仍然 dirty 都属于未保存', async () => {
  const failed = libre({ dirty: true, isError: true })
  let calls = 0
  failed.flushSave = async () => { calls++; return false }
  const pending = libre({ dirty: true })
  pending.flushSave = async () => undefined
  const result = await flushDirtyEditors({ a: failed, b: pending }, {})
  assert.equal(calls, 1, '保存错误不能被当成加载失败而跳过')
  assert.deepEqual(result, { failed: 2, flushed: 0 })
})

for (const name of ['leaveWorkbench', 'handleLogout']) {
  for (const outcome of ['failed', 'throws', 'saved']) {
    test(`${name} ${outcome}：只有全部保存成功才能导航或清会话`, async () => {
      const from = PAGE.indexOf(`    async ${name}(`)
      const end = PAGE.indexOf(name === 'leaveWorkbench' ? '    goAllProjects()' : '    onFileTreeQuickAction(', from)
      const calls = []
      const methods = new Function('flushDirtyEditors', 'uni', 'clearSession',
        'return {' + PAGE.slice(from, end) + '}')(
        async () => { if (outcome === 'throws') throw new Error('offline'); return { failed: outcome === 'failed' ? 1 : 0 } },
        { showToast: () => calls.push('toast'), reLaunch: () => calls.push('navigate') },
        () => calls.push('clear'))
      await methods[name].call({ $t: k => k }, '/target')
      if (outcome === 'saved') assert.deepEqual(calls, name === 'handleLogout' ? ['clear', 'navigate'] : ['navigate'])
      else assert.deepEqual(calls, ['toast'], '失败必须留在工作台并保留会话')
    })
  }
}

for (const change of ['saved-again', 'previously-clean', 'registered-later']) {
  test(`保存另一文档期间 ${change} 的修改必须阻止离开`, async () => {
    const a = libre({ dirty: change === 'saved-again' })
    const b = libre({ dirty: true })
    let finishB, enteredB
    const startedB = new Promise(resolve => { enteredB = resolve })
    const gateB = new Promise(resolve => { finishB = resolve })
    b.flushSave = async () => { enteredB(); await gateB; b.dirty = false; return true }
    const refs = { a, b }
    const pending = flushDirtyEditors(refs, {})
    await startedB
    if (change === 'registered-later') refs.c = libre({ dirty: true })
    else a.dirty = true
    finishB()
    const result = await pending
    assert.equal(result.failed, 1, '最后一次同步检查必须看到保存期间的新改动')
    assert.equal(change === 'registered-later' ? refs.c.dirty : a.dirty, true, '不能清脏标记来假装已保存')
  })
}

for (const name of ['leaveWorkbench', 'handleLogout']) {
  test(`${name} 等待文本保存时首次注册Office实例也必须阻止导航`, async () => {
    const from = PAGE.indexOf(`    async ${name}(`)
    const end = PAGE.indexOf(name === 'leaveWorkbench' ? '    goAllProjects()' : '    onFileTreeQuickAction(', from)
    const calls = []
    const methods = new Function('flushDirtyEditors', 'uni', 'clearSession',
      'return {' + PAGE.slice(from, end) + '}')(
      flushDirtyEditors,
      { showToast: () => calls.push('toast'), reLaunch: () => calls.push('navigate') },
      () => calls.push('clear'))
    const text = libre({ dirty: true })
    const vm = { $t: k => k, _plainTextRefs: { left: text } }
    text.flushSave = async () => {
      if (!vm._libreRefs) vm._libreRefs = {}
      vm._libreRefs['right:2'] = libre({ dirty: true })
      text.dirty = false
      return true
    }
    await methods[name].call(vm, '/target')
    assert.deepEqual(calls, ['toast'])
    assert.equal(vm._libreRefs['right:2'].dirty, true)
  })
}
