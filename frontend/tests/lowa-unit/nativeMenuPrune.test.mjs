// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// BUG-33（v0.49.0 真机 C4 观察 3/8）：打开「原生菜单」逃生开关后，
// ① 「文件」菜单里露出引擎自带的「退出 ZetaOffice (Ctrl+Q)」「打开远程文档」「在浏览器中预览」
//    ——内部品牌外露，退出一点就把 webview 里的引擎整个关掉；
// ② 左上角浮着一条「全屏」小工具条（fullscreenbar）压住样式框——set_chrome 的「显示」侧
//    把 CHROME_URLS.toolbars 整张表逐条 showElement，连 fullscreenbar 一起拉了出来。
//
// 这里把 office_thread.js 里的真实现抠出来，用假 LayoutManager / 假菜单容器跑：
// 真引擎那一半（getSettings/setSettings 在本 WASM 构建上是否生效）只有 lowa-e2e / 真机能验。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')

function sliceBlock(src, head) {
  const at = src.indexOf(head)
  assert.ok(at >= 0, 'office_thread.js 里找不到 ' + head)
  let i = src.indexOf('{', at)
  let depth = 0
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') { depth--; if (depth === 0) return src.slice(at, i + 1) }
  }
  throw new Error('花括号不配平: ' + head)
}
const constLine = (name) => {
  const m = SRC.match(new RegExp('^const ' + name + ' = [\\s\\S]*?\\];', 'm'))
  assert.ok(m, 'office_thread.js 里找不到 const ' + name)
  return m[0]
}

// 假菜单：容器 = XIndexContainer，条目 = PropertyValue 数组；子菜单挂在 ItemDescriptorContainer 上。
function container(items) {
  return {
    items,
    getCount() { return this.items.length },
    getByIndex(i) { return this.items[i] },
    removeByIndex(i) { this.items.splice(i, 1) },
  }
}
const item = (cmd, sub) => {
  const p = [{ Name: 'CommandURL', Value: cmd }, { Name: 'Label', Value: cmd }, { Name: 'Type', Value: 0 }]
  if (sub) p.push({ Name: 'ItemDescriptorContainer', Value: sub })
  return p
}
const sep = () => [{ Name: 'Type', Value: 1 }]
const cmds = (c) => c.items.map((p) => (p.find((x) => x.Name === 'CommandURL') || { Value: '---' }).Value)

function loadPrune() {
  const body = [constLine('NATIVE_MENU_BLOCKED'), sliceBlock(SRC, 'function menuEntryProp('),
    sliceBlock(SRC, 'function pruneMenuContainer('), sliceBlock(SRC, 'function pruneNativeMenu(')].join('\n')
  const zetajs = { fromAny: (v) => v }
  return new Function('zetajs', 'CHROME_URLS', body + '\nreturn { pruneMenuContainer, pruneNativeMenu, NATIVE_MENU_BLOCKED };')(
    zetajs, { menubar: 'private:resource/menubar/menubar' })
}

test('要藏的三项：退出 / 打开远程文档 / 在浏览器中预览', () => {
  const { NATIVE_MENU_BLOCKED } = loadPrune()
  for (const c of ['.uno:Quit', '.uno:OpenRemote', '.uno:WebHtml']) assert.ok(NATIVE_MENU_BLOCKED.includes(c), c)
})

test('递归删掉「文件」子菜单里的三项，其余不动，并收拾悬空分隔线', () => {
  const { pruneMenuContainer } = loadPrune()
  const file = container([
    item('.uno:AddDirect'), item('.uno:Open'), item('.uno:OpenRemote'), sep(),
    item('.uno:Save'), sep(), item('.uno:WebHtml'), item('.uno:PrintDefault'), sep(), item('.uno:Quit'),
  ])
  const top = container([item('.uno:PickList', file), item('.uno:EditMenu', container([item('.uno:Undo')]))])
  assert.equal(pruneMenuContainer(top), 3)
  assert.deepEqual(cmds(file), ['.uno:AddDirect', '.uno:Open', '---', '.uno:Save', '---', '.uno:PrintDefault'],
    '「退出」前那条分隔线成了末尾悬空，要一起去掉')
  assert.deepEqual(cmds(top), ['.uno:PickList', '.uno:EditMenu'])
})

test('pruneNativeMenu 改的是本 frame 菜单栏元素的 settings，并写回', () => {
  const { pruneNativeMenu } = loadPrune()
  const settings = container([item('.uno:PickList', container([item('.uno:Open'), item('.uno:Quit')]))])
  let written = null
  const el = { getSettings: (w) => { assert.equal(w, true, '要可写副本'); return settings }, setSettings: (s) => { written = s } }
  const lm = { getElement: (url) => (url === 'private:resource/menubar/menubar' ? el : null) }
  assert.equal(pruneNativeMenu(lm), 1)
  assert.equal(written, settings)
  // 什么都没删就不写回（每次开关都 setSettings 会让菜单栏重建一遍）
  written = null
  assert.equal(pruneNativeMenu(lm), 0)
  assert.equal(written, null)
  // 菜单栏元素还不存在 / 抛异常：不许把 set_chrome 带崩
  assert.equal(pruneNativeMenu({ getElement: () => null }), 0)
  assert.equal(pruneNativeMenu({ getElement: () => { throw new Error('boom') } }), 0)
})

test('set_chrome 显示侧不再把 fullscreenbar 拉出来，且放出菜单栏时顺手裁掉引擎项', () => {
  const setChrome = sliceBlock(SRC, '  set_chrome(p) {').replace(/^  set_chrome\(p\) \{/, 'function set_chrome(p) {')
  const body = [constLine('NATIVE_MENU_BLOCKED'), constLine('CHROME_NEVER_SHOWN'),
    'const CHROME_URLS = ' + sliceBlock(SRC, 'const CHROME_URLS = {').slice('const CHROME_URLS = '.length) + ';',
    'let pruned = 0; function pruneNativeMenu() { pruned++; return 3; }',
    setChrome].join('\n')
  const visible = new Map()
  const shown = []
  const lm = {
    isVisible: () => true, setVisible() {},
    createElement(u) { if (!visible.has(u)) visible.set(u, false) },
    hideElement(u) { visible.set(u, false) },
    showElement(u) { shown.push(u); visible.set(u, true) },
    isElementVisible: (u) => visible.get(u) === true,
  }
  const ctrl = { getFrame: () => ({ getPropertyValue: () => lm }), getViewSettings: () => ({ setPropertyValue() {}, getPropertyValue: () => true }) }
  const fns = new Function('ctrl', 'errStr', body + '\nreturn { set_chrome, prunedCount: () => pruned };')(ctrl, String)
  const out = fns.set_chrome({ menubar: true, statusbar: true, toolbars: true, rulers: true })
  assert.equal(out.success, true)
  assert.equal(out.applied.toolbars.fullscreenbar, false, '「全屏」浮条不许被显示侧拉出来')
  assert.ok(!shown.includes('private:resource/toolbar/fullscreenbar'))
  assert.equal(out.applied.toolbars.standardbar, true, '其余照旧放出来（逃生开关不退步）')
  assert.equal(out.applied.menubar, true)
  assert.equal(fns.prunedCount(), 1)
  assert.equal(out.applied.menuPruned, 3)
  // 藏的一侧不需要裁菜单
  fns.set_chrome({ menubar: false, toolbars: false })
  assert.equal(fns.prunedCount(), 1)
})

test('派发层同样拦住这三条命令（Ctrl+Q 等快捷键、菜单没裁成功时的兜底）', () => {
  const inst = sliceBlock(SRC, 'function installReviewCommentInterceptor(')
  assert.match(inst, /NATIVE_MENU_BLOCKED\.indexOf\(url\.Complete\) >= 0\) return null/)
  assert.match(inst, /getInterceptedURLs\(\) \{ return \[[^\]]*\]\.concat\(NATIVE_MENU_BLOCKED\)/)
})
