// 审计（dev-board#74）MEDIUM：AwdSelect 自己的下拉滚动会立刻把菜单关掉。
//
// 病灶：attachDismiss 在 window 上挂了捕获段（第三参 true）的 scroll 监听器，用来
// 侦测"外部容器滚动导致触发器坐标失效"从而关闭菜单。但捕获段会连菜单自己内部
// （超过 280px 出现的滚动条）的 scroll 事件也一起收到——用户在菜单里往下滚一下，
// 菜单立刻自己把自己关掉，长列表根本没法用。
//
// 修法：滚动事件的 target 落在菜单内部（menuEl.contains(e.target)）时忽略，只有
// 目标在菜单之外时才按原逻辑关闭。
//
// AwdSelect.vue 是 Options API 且零外部依赖（没有 import），照本仓一贯套路抠
// <script> 求值即可，连桩参数都不用传。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/AwdSelect.vue', import.meta.url), 'utf8')

function loadMethods() {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/export default \{/, 'return {')
  const factory = new Function(body)
  return factory().methods
}

const METHODS = loadMethods()

function makeVm(menuEl) {
  const vm = {
    open: true,
    closeCount: 0,
    emitted: [],
    $el: { querySelector: (sel) => (sel === '.awd-select-menu' ? menuEl : null) },
    close() { this.closeCount++; this.open = false; this.emitted.push('cancel') },
  }
  for (const name of ['attachDismiss', 'detachDismiss']) vm[name] = METHODS[name].bind(vm)
  return vm
}

test('滚动事件的 target 落在下拉菜单内部（含子元素）时不许关闭菜单', () => {
  const listeners = {}
  const priorWindow = globalThis.window
  globalThis.window = {
    addEventListener: (type, fn) => { listeners[type] = fn },
    removeEventListener: (type, fn) => { if (listeners[type] === fn) delete listeners[type] },
  }
  try {
    const item = {} // 菜单里的一个选项 DOM 节点
    const menuEl = { contains: (node) => node === item || node === menuEl }
    const vm = makeVm(menuEl)
    vm.attachDismiss()
    assert.equal(typeof listeners.scroll, 'function', 'attachDismiss 必须注册 scroll 监听')

    listeners.scroll({ type: 'scroll', target: item }) // 菜单内部子元素上的滚动
    assert.equal(vm.closeCount, 0, '菜单自己内部的滚动不许把自己关掉——这正是本条缺陷')

    listeners.scroll({ type: 'scroll', target: menuEl }) // 菜单容器自身
    assert.equal(vm.closeCount, 0)
  } finally {
    globalThis.window = priorWindow
  }
})

test('目标不在菜单内部的滚动（比如触发器所在的外部 scroll-view）仍然关闭菜单——回归保护', () => {
  const listeners = {}
  const priorWindow = globalThis.window
  globalThis.window = {
    addEventListener: (type, fn) => { listeners[type] = fn },
    removeEventListener: (type, fn) => { if (listeners[type] === fn) delete listeners[type] },
  }
  try {
    const menuEl = { contains: () => false }
    const vm = makeVm(menuEl)
    vm.attachDismiss()

    const outsideEl = {}
    listeners.scroll({ type: 'scroll', target: outsideEl })
    assert.equal(vm.closeCount, 1, '外部滚动仍然要关闭菜单，不能因为加了这道判断就整体失灵')
  } finally {
    globalThis.window = priorWindow
  }
})

test('resize 事件不受这道判断影响，照常关闭菜单——回归保护', () => {
  const listeners = {}
  const priorWindow = globalThis.window
  globalThis.window = {
    addEventListener: (type, fn) => { listeners[type] = fn },
    removeEventListener: (type, fn) => { if (listeners[type] === fn) delete listeners[type] },
  }
  try {
    const menuEl = { contains: () => false }
    const vm = makeVm(menuEl)
    vm.attachDismiss()
    assert.equal(typeof listeners.resize, 'function')
    listeners.resize({ type: 'resize' })
    assert.equal(vm.closeCount, 1)
  } finally {
    globalThis.window = priorWindow
  }
})

test('菜单元素还没渲染出来（querySelector 返回 null，比如 open 刚翻但 DOM 还没挂载）时不报错，按原逻辑关闭', () => {
  const listeners = {}
  const priorWindow = globalThis.window
  globalThis.window = {
    addEventListener: (type, fn) => { listeners[type] = fn },
    removeEventListener: () => {},
  }
  try {
    const vm = makeVm(null)
    vm.attachDismiss()
    assert.doesNotThrow(() => listeners.scroll({ type: 'scroll', target: {} }))
    assert.equal(vm.closeCount, 1)
  } finally {
    globalThis.window = priorWindow
  }
})
