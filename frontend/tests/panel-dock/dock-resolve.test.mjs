// 面板停靠的纯函数（dev-board#180）。停靠位是持久化在本机的用户选择，
// 存量值随时可能指向一个下线的面板或一个这个面板不允许的位置——回落规则要是错了，
// 用户会得到一个「rail 上没有高亮、左栏是加载中占位符」的坏掉的工作台。
//
// 跑法：cd frontend && npm run test:panel-dock
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  DOCKS,
  MOVABLE_PANELS,
  getMovablePanel,
  isDockAllowed,
  isMovablePanel,
  resolveDock,
  resolveDocks,
  sanitizeDockOverrides,
} from '../../src/config/panelRegistry.js'

const keysOf = (list) => list.map((p) => p.key)

test('注册表自洽：key 唯一、defaultDock 在 allowedDocks 里、allowedDocks 都是合法 dock', () => {
  const seen = new Set()
  for (const p of MOVABLE_PANELS) {
    assert.ok(!seen.has(p.key), '重复的面板 key: ' + p.key)
    seen.add(p.key)
    assert.ok(p.labelKey && p.labelKey.includes('.'), p.key + ' 的 labelKey 不像 i18n key')
    assert.ok(Array.isArray(p.allowedDocks) && p.allowedDocks.length, p.key + ' 没有 allowedDocks')
    for (const d of p.allowedDocks) assert.ok(DOCKS.includes(d), p.key + ' 的 allowedDocks 有非法值: ' + d)
    assert.ok(p.allowedDocks.includes(p.defaultDock), p.key + ' 的 defaultDock 不在 allowedDocks 里')
    assert.ok(Array.isArray(p.svgPaths) && p.svgPaths.length, p.key + ' 缺图标（rail 上会是个空按钮）')
  }
})

test('没有 override 时全部落到 defaultDock', () => {
  const docks = resolveDocks({})
  // 'variables' 2026-08-27 从注册表隐藏（dev-board#216），bottom 只剩两项
  assert.deepEqual(keysOf(docks.bottom), ['favorites', 'clipboard'])
  assert.deepEqual(keysOf(docks.left), ['voice'])
  assert.deepEqual(keysOf(docks.right), ['insight'])
  // undefined / null 与空对象等价
  assert.deepEqual(keysOf(resolveDocks(undefined).bottom), keysOf(docks.bottom))
  assert.deepEqual(keysOf(resolveDocks(null).left), keysOf(docks.left))
})

test('合法 override 生效，各档内保持注册表顺序', () => {
  const docks = resolveDocks({ voice: 'right', clipboard: 'left', favorites: 'right' })
  assert.deepEqual(keysOf(docks.right), ['favorites', 'voice', 'insight'], '右档没有按注册表顺序')
  assert.deepEqual(keysOf(docks.left), ['clipboard'])
  assert.deepEqual(keysOf(docks.bottom), [])
})

test('allowedDocks 之外的 override 回落 default（语音不许进底栏）', () => {
  assert.equal(resolveDock('voice', { voice: 'bottom' }), 'left')
  const docks = resolveDocks({ voice: 'bottom' })
  assert.deepEqual(keysOf(docks.left), ['voice'])
  assert.deepEqual(keysOf(docks.bottom), ['favorites', 'clipboard'])
})

test('非法值与未知 key 一律回落 default，不抛异常', () => {
  assert.equal(resolveDock('favorites', { favorites: 'floating' }), 'bottom')
  assert.equal(resolveDock('favorites', { favorites: '' }), 'bottom')
  assert.equal(resolveDock('favorites', { favorites: null }), 'bottom')
  assert.equal(resolveDock('nope', { nope: 'left' }), null)
  // 'variables' 隐藏后就是「存量 override 指向已下线面板」的真实用例
  const docks = resolveDocks({ nope: 'left', variables: 'left' })
  assert.deepEqual(keysOf(docks.bottom), ['favorites', 'clipboard'])
  assert.deepEqual(keysOf(docks.left), ['voice'], '未知 key 混进了左档')
  assert.deepEqual(keysOf(docks.right), ['insight'], '未知 key 混进了右档')
})

test('三个档位恒定存在（宿主直接读 .left/.right/.bottom，不做存在性判断）', () => {
  for (const overrides of [{}, { voice: 'right' }, { favorites: 'left', clipboard: 'left' }]) {
    const docks = resolveDocks(overrides)
    for (const d of DOCKS) assert.ok(Array.isArray(docks[d]), d + ' 档不是数组')
  }
})

test('每个面板恰好落在一个档里', () => {
  const docks = resolveDocks({ voice: 'right', favorites: 'left' })
  const all = [...keysOf(docks.left), ...keysOf(docks.right), ...keysOf(docks.bottom)]
  assert.equal(all.length, MOVABLE_PANELS.length)
  assert.equal(new Set(all).size, MOVABLE_PANELS.length)
})

test('sanitizeDockOverrides 只留下能用的条目', () => {
  assert.deepEqual(
    sanitizeDockOverrides({ voice: 'right', clipboard: 'bottom', variables: 'left', ghost: 'left' }),
    { voice: 'right', clipboard: 'bottom' }
  )
  assert.deepEqual(sanitizeDockOverrides({ voice: 'bottom' }), {}, '不被允许的档没有被清掉')
  // storage 读回来可能是空串/数组/null（隐私模式、老版本写的值）
  for (const junk of [null, undefined, '', 0, [], 'left']) {
    assert.deepEqual(sanitizeDockOverrides(junk), {})
  }
})

test('isMovablePanel / isDockAllowed / getMovablePanel 的口径与注册表一致', () => {
  assert.ok(isMovablePanel('voice'))
  assert.ok(!isMovablePanel('files'), '文件树不是可停靠面板（rail 上不许出现拖拽手柄）')
  assert.ok(isDockAllowed('favorites', 'left'))
  assert.ok(!isMovablePanel('variables'), "'variables' 已隐藏（dev-board#216），不许再是可停靠面板")
  assert.ok(!isDockAllowed('voice', 'bottom'))
  assert.ok(!isDockAllowed('files', 'left'))
  assert.equal(getMovablePanel('favorites').defaultDock, 'bottom')
  assert.equal(getMovablePanel('files'), null)
})
