// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-07（v0.49.0 真机批次 H）：项目列表页 navigateTo 进「新建项目」向导后，
// 点向导页「返回项目列表」若也用 navigateTo，会在页面栈里堆出第二个列表实例
// （双向 navigateTo 页面栈多实例地雷）。全局返回键（globalBack.js）的可见性
// 判据只看「栈深度 > 1」，堆出的多余实例会让它在真正回到列表后仍然显示，
// 点击又用 navigateBack 弹回向导页——与真机复现一致。
//
// 断言：上一页是项目列表时必须用 navigateBack 弹出本页（回到唯一的列表实例，
// 栈深度降到 1，返回键随之消失）；不是从列表进来时（真机路径：菜单「文件 > 新建项目…」
// 经 appMenuBridge.js reLaunch 进向导，栈只有向导一页）用 redirectTo 同级替换，不压栈。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('../../src/pages/newproject/index.vue', import.meta.url), 'utf8')

function extractBody(name) {
  const head = src.indexOf(`${name}() {`)
  assert.ok(head > 0, `找不到 ${name}`)
  const start = src.indexOf('{', head)
  let depth = 0
  for (let i = start; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1)
  }
  throw new Error(`${name} 的括号没配上`)
}

function build(pages) {
  const calls = { navigateTo: [], navigateBack: [], redirectTo: [] }
  const uni = {
    navigateTo: (o) => calls.navigateTo.push(o.url),
    redirectTo: (o) => calls.redirectTo.push(o.url),
    navigateBack: (o) => calls.navigateBack.push(o && o.delta),
  }
  const getCurrentPages = () => pages
  const fn = new Function(
    'uni', 'getCurrentPages',
    `return function goToProjectList() ${extractBody('goToProjectList')}`,
  )(uni, getCurrentPages)
  return { fn, calls }
}

test('从项目列表页进入向导后「返回项目列表」用 navigateBack 弹出本页，不再 navigateTo 堆栈', () => {
  const pages = [
    { route: 'pages/project-list/project-list' },
    { route: 'pages/newproject/index' },
  ]
  const { fn, calls } = build(pages)

  fn()

  assert.deepEqual(calls.navigateBack, [1], '上一页是列表时必须 navigateBack 弹出本页')
  assert.deepEqual(calls.navigateTo, [], '不应该再 navigateTo 堆出第二个列表实例')
})

test('菜单「文件 > 新建项目…」reLaunch 进向导（栈只有本页）时用 redirectTo 替换，不压栈', () => {
  // appMenuBridge.js 用 uni.reLaunch 打开向导：页面栈 = [newproject]
  const pages = [{ route: 'pages/newproject/index' }]
  const { fn, calls } = build(pages)

  fn()

  assert.deepEqual(calls.redirectTo, ['/pages/project-list/project-list'], '同级替换，栈深度保持 1')
  assert.deepEqual(calls.navigateTo, [], 'navigateTo 会把栈压成两层，全局返回键随之出现')
  assert.deepEqual(calls.navigateBack, [])
})

test('菜单源码确实是 reLaunch 进向导（上一条测试的前提）', () => {
  const bridge = readFileSync(new URL('../../src/utils/appMenuBridge.js', import.meta.url), 'utf8')
  assert.match(bridge, /uni\.reLaunch\(\{ url: '\/pages\/newproject\/index' \}\)/)
})
