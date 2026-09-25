// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 工作台顶栏「外观」下拉的无障碍与键盘可达（v0.49.0 真机测试 BUG-53 / F-02）。
 *
 * 真机现象：用无障碍 AXPress 点「浅色」，菜单收起但主题不变；只有真实鼠标点才生效。
 * 菜单项是没有角色的 <view>，辅助技术的「按下」落到了最近一个可点的祖先
 * （触发按钮本身，它的 @tap 是开合开关）——于是菜单关了、选项没选上。键盘也完全够不着。
 *
 * 修法（不改视觉）：触发器 role=button + tabindex + aria-expanded，菜单 role=menu，
 * 菜单项 role=menuitemradio（menuitem 的单选变体）+ tabindex，键盘上下 / Home / End /
 * Enter / 空格 / Esc。
 *
 * 跑法：cd frontend && node --test tests/project-home/theme-menu-a11y.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { themeMenuKeyAction } from '../../src/pages/project-overview/themeMenuKeys.js'

const PAGE = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
const SWITCH = readFileSync(new URL('../../src/pages/project-overview/themeSwitch.js', import.meta.url), 'utf8')

// 顶栏外观按钮那一整块模板
const BLOCK = PAGE.match(/<view class="top-bar-btn theme-btn"[\s\S]*?<view v-if="themeMenuOpen" class="theme-menu-mask"/)[0]

// ---------------- 键位 → 动作（纯函数） ----------------

test('上下键在三项之间循环移动', () => {
  assert.deepEqual(themeMenuKeyAction('ArrowDown', 0, 3), { type: 'move', index: 1 })
  assert.deepEqual(themeMenuKeyAction('ArrowDown', 2, 3), { type: 'move', index: 0 })
  assert.deepEqual(themeMenuKeyAction('ArrowUp', 0, 3), { type: 'move', index: 2 })
  assert.deepEqual(themeMenuKeyAction('Home', 2, 3), { type: 'move', index: 0 })
  assert.deepEqual(themeMenuKeyAction('End', 0, 3), { type: 'move', index: 2 })
})

test('Enter / 空格选中，Esc / Tab 关闭，别的键不管', () => {
  assert.deepEqual(themeMenuKeyAction('Enter', 1, 3), { type: 'pick', index: 1 })
  assert.deepEqual(themeMenuKeyAction(' ', 1, 3), { type: 'pick', index: 1 })
  assert.deepEqual(themeMenuKeyAction('Escape', 1, 3), { type: 'close' })
  assert.deepEqual(themeMenuKeyAction('Tab', 1, 3), { type: 'close', keepDefault: true })
  assert.equal(themeMenuKeyAction('a', 1, 3), null)
})

// ---------------- 模板：角色与键盘接线 ----------------

test('触发器是可聚焦的按钮，报告展开状态，挂键盘处理', () => {
  const trigger = BLOCK.match(/<view class="top-bar-btn theme-btn"[^>]*>/)[0]
  assert.match(trigger, /role="button"/)
  assert.match(trigger, /tabindex="0"/)
  assert.match(trigger, /aria-haspopup="menu"/)
  assert.match(trigger, /:aria-expanded="themeMenuOpen \? 'true' : 'false'"/)
  assert.match(trigger, /@keydown="onThemeTriggerKey"/)
  assert.match(trigger, /:aria-label="\$t\('workbench\.appearance'\)"/)
})

test('菜单 role=menu，菜单项有角色、可聚焦、报告选中态、挂键盘处理，点击仍走 pickTheme', () => {
  assert.match(BLOCK, /class="theme-menu"[^>]*role="menu"/)
  const item = BLOCK.match(/<view\s+v-for="\(opt, i\) in themeOptions"[\s\S]*?>/)
  assert.ok(item, '菜单项要带下标（键盘移动焦点要用）')
  const tag = item[0]
  assert.match(tag, /role="menuitemradio"/)
  assert.match(tag, /tabindex="-1"/)
  assert.match(tag, /:aria-checked="themeMode === opt\.value \? 'true' : 'false'"/)
  assert.match(tag, /@keydown\.stop="onThemeItemKey\(\$event, i\)"/)
  assert.match(tag, /@tap="pickTheme\(opt\.value\)"/)
})

// ---------------- 方法行为 ----------------

function methodsWith(stubs) {
  const body = SWITCH
    .replace(/^import[\s\S]*?from\s+'[^']+'\s*$/gm, '')
    .replace(/export const /g, 'const ')
  // eslint-disable-next-line no-new-func
  return new Function('track', 'getThemeMode', 'getResolvedTheme', 'setThemeMode', 'APP_THEME_EVENT', 'themeMenuKeyAction',
    body + '\nreturn themeSwitchMethods')(
    () => {}, stubs.getThemeMode, () => 'light', stubs.setThemeMode, 'x', themeMenuKeyAction)
}

function makeVm() {
  let mode = 'dark'
  const focused = []
  const set = []
  const m = methodsWith({ getThemeMode: () => mode, setThemeMode: (v) => { set.push(v); mode = v } })
  const vm = {
    themeMode: 'dark', resolvedTheme: 'dark', themeMenuOpen: false,
    themeOptions: [{ value: 'light' }, { value: 'dark' }, { value: 'system' }],
    $nextTick: (fn) => fn(),
    _focused: focused, _set: set,
  }
  for (const [k, fn] of Object.entries(m)) vm[k] = fn.bind(vm)
  vm.focusThemeItem = (i) => focused.push(['item', i])
  vm.focusThemeTrigger = () => focused.push(['trigger'])
  return vm
}
const key = (k) => ({ key: k, preventDefault() { this.prevented = true } })

test('触发器上 Enter / 空格 / 下箭头打开菜单并把焦点放到当前选中项', () => {
  for (const k of ['Enter', ' ', 'ArrowDown']) {
    const vm = makeVm()
    const e = key(k)
    vm.onThemeTriggerKey(e)
    assert.equal(vm.themeMenuOpen, true, k)
    assert.deepEqual(vm._focused.at(-1), ['item', 1], '当前是深色，焦点落在第 2 项')
    assert.equal(e.prevented, true, '空格不许滚页面')
  }
})

test('菜单项上：下箭头移动焦点，Enter 选中并关菜单、焦点回触发器', () => {
  const vm = makeVm()
  vm.onThemeTriggerKey(key('Enter'))
  vm.onThemeItemKey(key('ArrowDown'), 1)
  assert.deepEqual(vm._focused.at(-1), ['item', 2])
  vm.onThemeItemKey(key('Enter'), 0)
  assert.deepEqual(vm._set, ['light'], '键盘选中必须真的切主题')
  assert.equal(vm.themeMenuOpen, false)
  assert.deepEqual(vm._focused.at(-1), ['trigger'])
})

test('Esc 关菜单不改主题，焦点回触发器；触发器上 Esc 也能关', () => {
  const vm = makeVm()
  vm.onThemeTriggerKey(key('Enter'))
  vm.onThemeItemKey(key('Escape'), 1)
  assert.equal(vm.themeMenuOpen, false)
  assert.deepEqual(vm._set, [])
  assert.deepEqual(vm._focused.at(-1), ['trigger'])
  vm.themeMenuOpen = true
  vm.onThemeTriggerKey(key('Escape'))
  assert.equal(vm.themeMenuOpen, false)
})
