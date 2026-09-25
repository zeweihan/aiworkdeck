// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 BUG-52：draw.io 标签激活时菜单栏「文件 > 关闭标签」置灰（测试员真机 1/1）。
//
// 排查结论：draw.io 走的是普通 openFile，activeFileIdLeft 有值，hasTab 为 true，
// 命令表对它与 docx 一视同仁（commands.test.mjs 的 BUG-52 用例）。能让一个开着的标签
// 的「关闭标签」变灰的只有 setMenuPage('')——工作台 onHide 调的 unregisterMenuCommands。
// 而 uni-h5 把 document visibilitychange 也派发成当前页的 onHide：Electron 在窗口最小化、
// 被整块遮挡、锁屏时 visibilityState=hidden。那一幕正是长时间锁屏之后（主会话记录：
// 截图拿到的还是锁屏前的旧帧，窗口仍处于遮挡态）。所以不是 draw.io 特有，是「窗口在
// 后台时工作台菜单全灰」。修法：visibilityState 为 hidden 的 onHide 不交出菜单。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const MC = readFileSync(new URL('../../src/pages/project-overview/menuCommands.js', import.meta.url), 'utf8')
const PO = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')

function loadRelease() {
  const m = MC.match(/\n {2}releaseMenuOnHide\(\) \{\n([\s\S]*?)\n {2}\},/)
  assert.ok(m, 'menuCommands.js 里找不到 releaseMenuOnHide')
  // eslint-disable-next-line no-new-func
  return new Function('document', m[1])
}

test('金丝雀：uni-h5 仍把 visibilitychange=hidden 派发成当前页的 onHide', () => {
  const src = readFileSync(require.resolve('@dcloudio/uni-h5/dist/uni-h5.es.js'), 'utf8')
  assert.match(src, /document\.addEventListener\("visibilitychange", onVisibilityChange\)/)
  assert.match(src, /function onAppEnterBackground\(\) \{[\s\S]{0,200}invokeHook\(\s*getCurrentPage\(\),\s*ON_HIDE\s*\)/)
})

test('窗口在后台（最小化/遮挡/锁屏，visibilityState=hidden）：onHide 不交出菜单', () => {
  const release = loadRelease()
  let released = 0
  release.call({ unregisterMenuCommands: () => { released++ } }, { visibilityState: 'hidden' })
  assert.equal(released, 0, '窗口一到后台工作台菜单就全灰——「关闭标签」置灰就是这个')
})

test('真被别的页面盖住（visibilityState=visible 的 onHide，如 navigateTo 设置页）：照旧交出', () => {
  const release = loadRelease()
  let released = 0
  release.call({ unregisterMenuCommands: () => { released++ } }, { visibilityState: 'visible' })
  assert.equal(released, 1)
})

test('接线：工作台 onHide 走 releaseMenuOnHide，不再直接 unregisterMenuCommands', () => {
  const body = PO.slice(PO.indexOf('  onHide() {'), PO.indexOf('  onHide() {') + 600)
  assert.match(body, /this\.releaseMenuOnHide\(\)/)
  assert.doesNotMatch(body, /this\.unregisterMenuCommands\(\)/)
})
