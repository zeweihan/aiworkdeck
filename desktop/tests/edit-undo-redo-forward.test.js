// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 BUG-30：菜单栏「编辑 > 撤销/重做」在文档标签里无效或置灰。
//
// 根因：LOWA 引擎是画布渲染，Electron `role: 'undo'/'redo'` 只认浏览器原生编辑
// 历史（textarea/contentEditable），对它完全没用；工具栏的撤销走的是
// `.uno:Undo` 这条完全不同的命令通道。文档标签激活时，菜单必须转发给渲染层走
// 同一条通道（wb:undo / wb:redo），而不是继续指望 role 自己找到焦点。
//
// 假 electron 写法照抄 reload-shortcut.test.js 的先例（真 electron 在裸 node 里
// require 不出东西）；ipcMain.on 额外把回调存下来，好在测试里模拟渲染层推送
// `checkba:menu-state`。

const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-undo-test-'))
let builtTemplate = null
const menuStateHandlers = []
let focusedWc = null // webContents.getFocusedWebContents() 的返回值，逐条测试设置

const fakeApp = {
  isPackaged: true,
  getVersion: () => '0.0.0-test',
  getPath: () => userDataDir,
  getLocale: () => 'zh-CN',
  setAboutPanelOptions() {},
}
const electronId = require.resolve('electron')
require.cache[electronId] = {
  id: electronId,
  filename: electronId,
  loaded: true,
  exports: {
    app: fakeApp,
    Menu: {
      buildFromTemplate: (tpl) => { builtTemplate = tpl; return { __template: tpl } },
      setApplicationMenu() {},
    },
    ipcMain: {
      on(channel, fn) { if (channel === 'checkba:menu-state') menuStateHandlers.push(fn) },
    },
    dialog: { showMessageBoxSync: () => 0 },
    webContents: { getFocusedWebContents: () => focusedWc },
  },
}

const { initAppMenu } = require('../main/app-menu')

function fakeWindow() {
  const sent = []
  return { isDestroyed: () => false, webContents: { reload() {}, send: (ch, data) => sent.push({ ch, data }) }, __sent: sent }
}

function flatten(items, out = []) {
  for (const it of items || []) {
    if (!it) continue
    out.push(it)
    if (Array.isArray(it.submenu)) flatten(it.submenu, out)
  }
  return out
}

function pushMenuState(payload) {
  for (const fn of menuStateHandlers) fn({}, payload)
}

function editItem(label) {
  return flatten(builtTemplate).find((it) => it.label === label)
}

const baseMenus = () => [{ id: 'edit', items: [] }] // 编辑菜单没有额外下发项时也要能工作

test('没有文档标签激活时：撤销/重做保留 Electron role，不抢普通输入框的撤销', () => {
  const win = fakeWindow()
  builtTemplate = null
  initAppMenu(() => win)
  pushMenuState({ menus: baseMenus(), flags: { isDocTab: false } })
  const undo = editItem('撤销')
  const redo = editItem('重做')
  assert.equal(undo.role, 'undo', '非文档标签时应保留 role，否则聊天框等普通输入框的撤销会失灵')
  assert.equal(redo.role, 'redo')
})

test('文档标签激活时：撤销/重做转发给渲染层，走跟工具栏撤销同一条通道', () => {
  const win = fakeWindow()
  builtTemplate = null
  initAppMenu(() => win)
  pushMenuState({ menus: baseMenus(), flags: { isDocTab: true } })
  const undo = editItem('撤销')
  const redo = editItem('重做')
  assert.equal(undo.role, undefined, 'role 版撤销对画布渲染的 LOWA 完全无效，文档标签激活时不能再用它')
  assert.equal(typeof undo.click, 'function', '要有 click 才能转发')
  assert.equal(redo.role, undefined)
  assert.equal(typeof redo.click, 'function')

  // 焦点在主窗口自己：交给渲染层，由它按 activeElement 决定撤销输入框还是文档
  focusedWc = win.webContents
  win.__sent.length = 0
  undo.click()
  assert.equal(win.__sent.length, 1)
  assert.deepEqual(win.__sent[0], { ch: 'checkba:menu-action', data: { action: 'edit.undo' } })

  win.__sent.length = 0
  redo.click()
  assert.deepEqual(win.__sent[0], { ch: 'checkba:menu-action', data: { action: 'edit.redo' } })
})

test('焦点在主窗口的 <webview> 客体（编辑器画布）里：同样交给渲染层', () => {
  const win = fakeWindow()
  builtTemplate = null
  initAppMenu(() => win)
  pushMenuState({ menus: baseMenus(), flags: { isDocTab: true } })
  focusedWc = { hostWebContents: win.webContents, undo() { throw new Error('不该在客体上做原生撤销') } }
  win.__sent.length = 0
  editItem('撤销').click()
  assert.deepEqual(win.__sent, [{ ch: 'checkba:menu-action', data: { action: 'edit.undo' } }])
})

test('焦点在主窗口之外（浏览器面板 BrowserView 里的输入框）：对它做原生撤销，不碰文档', () => {
  const win = fakeWindow()
  builtTemplate = null
  initAppMenu(() => win)
  pushMenuState({ menus: baseMenus(), flags: { isDocTab: true } })
  const calls = []
  focusedWc = { hostWebContents: null, undo: () => calls.push('undo'), redo: () => calls.push('redo') }
  win.__sent.length = 0
  editItem('撤销').click()
  editItem('重做').click()
  assert.deepEqual(calls, ['undo', 'redo'])
  assert.equal(win.__sent.length, 0, 'BrowserView 聚焦时把撤销转给渲染层，文档会被撤销、网页输入框反而撤不了')
})

test('转发态下加速键手动补回 ⌘Z / ⇧⌘Z：role 一撤走，Electron 不会再自动绑键', () => {
  const win = fakeWindow()
  builtTemplate = null
  initAppMenu(() => win)
  pushMenuState({ menus: baseMenus(), flags: { isDocTab: true } })
  const undo = editItem('撤销')
  const redo = editItem('重做')
  assert.match(String(undo.accelerator || ''), /CmdOrCtrl\+Z$/i)
  assert.match(String(redo.accelerator || ''), /Shift\+CmdOrCtrl\+Z$/i)
})
