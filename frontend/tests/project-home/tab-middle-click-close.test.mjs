// dev-board#97 的「中键单击标签关闭」在 H5/桌面端从来没生效过：
// uni-app H5 把 <view> 上的原生事件重新包装成普通对象（uni-h5 createNativeEvent），
// 只给 click / mouse 系 / touch / keyboard 几类补字段，补的还只是坐标——`button`
// 一类都没有，`auxclick` 连补都不补。于是 onTabAuxClick 里的 `e.button !== 1`
// 恒真，一路 return，标签纹丝不动（app-e2e「鼠标中键单击标签关闭它」实测卡死在这）。
//
// 修法：键位改从当前正在派发的原生事件（window.event）上取，回调里带 button 时优先用回调的。
// 本文件钉住三件事：包装过的 auxclick 能关标签、右键的 auxclick 不能关、
// 中键 mousedown 仍然 preventDefault（不然 Chrome 的自动滚动会把 auxclick 吃掉）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/fileOpenTabs.js', import.meta.url), 'utf8')

function loadMethods() {
  const body = SRC
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export const fileOpenTabsMethods = \{/, 'return {')
  const factory = new Function('getProjectFiles', 'activityTracker', 'GLYPHS', 'fileGlyph', 'uni', 'window', body)
  return (win) => factory(async () => [], { track: () => {} }, {}, () => '', {}, win)
}
const load = loadMethods()

// uni-app H5 交给回调的事件对象：没有 button，只有 type/target 与两个转发方法。
function uniWrappedEvent(type, native) {
  let prevented = false
  return {
    ev: {
      type,
      timeStamp: 0,
      detail: {},
      target: {},
      currentTarget: {},
      preventDefault() { prevented = true; native.defaultPrevented = true },
      stopPropagation() {},
    },
    get prevented() { return prevented },
  }
}

function makeVm(methods) {
  const closed = []
  const vm = { closeFile(id, pane) { closed.push([id, pane]) } }
  vm.onTabAuxClick = methods.onTabAuxClick.bind(vm)
  vm.onTabMouseDown = methods.onTabMouseDown.bind(vm)
  return { vm, closed }
}

test('中键 auxclick：uni 包装掉 button 也要关掉标签', () => {
  const native = { button: 1, defaultPrevented: false }
  const methods = load({ event: native })
  const { vm, closed } = makeVm(methods)
  const w = uniWrappedEvent('auxclick', native)
  vm.onTabAuxClick(w.ev, { id: 'admin-settings' }, 'left')
  assert.deepEqual(closed, [['admin-settings', 'left']])
  assert.equal(w.prevented, true, 'auxclick 也要 preventDefault')
})

test('右键 auxclick 不关标签', () => {
  const native = { button: 2, defaultPrevented: false }
  const methods = load({ event: native })
  const { vm, closed } = makeVm(methods)
  vm.onTabAuxClick(uniWrappedEvent('auxclick', native).ev, { id: 'f1' }, 'right')
  assert.deepEqual(closed, [], '右键不该关标签')
})

test('回调里带 button 时以回调为准（原生事件未被包装的平台）', () => {
  const methods = load({ event: { button: 2 } }) // window.event 故意给成右键
  const { vm, closed } = makeVm(methods)
  vm.onTabAuxClick({ button: 1, preventDefault() {}, stopPropagation() {} }, { id: 'f2' }, 'left')
  assert.deepEqual(closed, [['f2', 'left']])
})

test('中键 mousedown 仍然 preventDefault（挡住自动滚动，否则 auxclick 不会来）', () => {
  const native = { button: 1, defaultPrevented: false }
  const methods = load({ event: native })
  const { vm } = makeVm(methods)
  const w = uniWrappedEvent('mousedown', native)
  vm.onTabMouseDown(w.ev)
  assert.equal(w.prevented, true)

  const native2 = { button: 0, defaultPrevented: false }
  const m2 = load({ event: native2 })
  const w2 = uniWrappedEvent('mousedown', native2)
  makeVm(m2).vm.onTabMouseDown(w2.ev)
  assert.equal(w2.prevented, false, '左键不许拦（拦了就选不中标签）')
})
