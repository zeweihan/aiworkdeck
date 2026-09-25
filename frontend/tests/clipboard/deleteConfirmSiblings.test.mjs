// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-63：剪贴板面板的删除确认气泡早已去掉「5 秒自动收起」（dev-board#455），同款气泡的
// 收藏夹面板、设置页全部收藏、变量库仍在 5 秒后自己收起——超时之后用户点「确定」
// 点到的是气泡底下的卡片。这里把三处 <script> 剥出来真跑 requestDelete，
// 把排过的定时器全部触发一遍（等价于等待任意长时间），确认态必须还在。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

function loadComponent(path, deps) {
  const src = readFileSync(new URL(`../../src/components/${path}`, import.meta.url), 'utf8')
  const script = src.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  const names = Object.keys(deps)
  // eslint-disable-next-line no-new-func
  return new Function(...names, script.replace('export default', 'return'))(...names.map((n) => deps[n]))
}

function makeVm(path) {
  const timers = []
  const deps = {
    setTimeout: (fn, ms) => { timers.push([fn, ms]); return timers.length },
    clearTimeout: () => {},
    uni: { showToast: () => {}, $emit: () => {}, $on: () => {}, $off: () => {} },
  }
  const component = loadComponent(path, deps)
  const base = { $t: (k) => k, $emit: () => {}, projectId: 1, query: '' }
  const vm = Object.assign(base, component.data.call(base), component.methods)
  return { vm, timers }
}

for (const [path, arg, field] of [
  ['ProjectFavoritesPanel.vue', 5, 'confirmDeleteId'],
  ['userprofile/PersonalFavoritesPanel.vue', 5, 'confirmDeleteId'],
  ['VariablePanel.vue', { key: 'k5' }, 'confirmDeleteKey'],
]) {
  test(`${path}：删除确认态不自动收起`, () => {
    const { vm, timers } = makeVm(path)
    vm.requestDelete(arg)
    const expected = typeof arg === 'object' ? arg.key : arg
    assert.equal(vm[field], expected, '× 应进入确认态')
    for (const [fn] of timers) fn()
    assert.equal(vm[field], expected, '确认态被定时器自动收起了，之后点「确定」会点到底下的卡片')
    vm.cancelDelete()
    assert.equal(vm[field], null, '「取消」仍然能收起')
  })
}
