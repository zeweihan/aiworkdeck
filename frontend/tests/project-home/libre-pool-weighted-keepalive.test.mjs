// 尽调模块 P3 稳定性余项 #2（dev-board#100）：LOWA 编辑器保活池固定 LRU=3，与文档体积
// 无关。三个 150 页/6.6MB 大文档同时驻留会把页面内存吃到约 2.4GB（实测基线，见
// docs/superpowers/specs/2026-08-21-due-diligence-module-proposal.md §3）。
//
// 修法：LIBRE_KEEPALIVE_MAX 固定名额改成按文档体积计权（LIBRE_WEIGHT_BUDGET 总权重
// 上限固定，大文档占更多权重），小文档不受影响甚至能同时保活更多。
//
// 抽取套路同 fileOpenTabs.js 的既有测试（pending-local-file-silent-fail.test.mjs）：
// librePool.js 带 @/ 别名 import（isDesktopHost，仅 initLibreSpare 用到，本文件不
// exercise 备胎逻辑），plain node --test 解不了别名，剥掉 import 行后用 Function
// 求值，isDesktopHost 用形参喂个不会被调用的桩。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/librePool.js', import.meta.url), 'utf8')

function loadMethods() {
  const body = SRC
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export const librePoolMethods = \{/, 'return {')
  const factory = new Function('isDesktopHost', body)
  return factory(() => true)
}

const MB = 1024 * 1024

function makeVm(files) {
  const methods = loadMethods()
  const vm = {
    libreLruKeys: [],
    libreSpares: [],
    _libreRefs: {},
    _libreExecMap: {},
    activeFileIdLeft: null,
    activeFileIdRight: null,
    leftFiles: files,
    rightFiles: [],
    useLibreEditor: () => true,
  }
  for (const k of Object.keys(methods)) vm[k] = methods[k].bind(vm)
  return vm
}

function mountable(file) {
  return { file, ready: true, isError: false, flushSave: async () => {} }
}

// 依次激活 files（同一窗格内切标签），每次激活后立刻登记 ref（模拟组件挂载完成，
// 真实时序里下一次激活发生前，上一个实例早已挂载好），并让淘汰用的 flushSave 有
// 机会跑完（LRU 淘汰是 fire-and-forget 的 async 调用，不等待）。
async function activateSequence(vm, pane, files) {
  for (const f of files) {
    vm[pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = f.id
    vm.onActiveOfficeFileChanged(pane, f)
    vm.setLibreRef(pane, f.id, mountable(f))
    await new Promise((r) => setTimeout(r, 0))
  }
}

test('三个 150 页/6.6MB 级大文档依次激活：不应三个同时保活（旧 LRU=3 会全部留住）', async () => {
  const bigFiles = [1, 2, 3].map((id) => ({ id, fileSize: 7 * MB }))
  const vm = makeVm(bigFiles)

  await activateSequence(vm, 'left', bigFiles)

  assert.ok(vm.libreLruKeys.length < 3,
    `三个大文档不该同时保活，实际保活 ${vm.libreLruKeys.length} 个: ${JSON.stringify(vm.libreLruKeys)}`)
  assert.ok(!vm.libreLruKeys.includes('left:1'), '最早激活、体积又大的那份该被挤出去')
  assert.ok(vm.libreLruKeys.includes('left:3'), '当前激活的（最新一份）必须还在池里')
})

test('四个小文档（各 300KB）依次激活：应该都能保活——体积计权不能让小文档体验变差', async () => {
  const smallFiles = [1, 2, 3, 4].map((id) => ({ id, fileSize: 300 * 1024 }))
  const vm = makeVm(smallFiles)

  await activateSequence(vm, 'left', smallFiles)

  assert.equal(vm.libreLruKeys.length, 4, '四个小文档权重合计很小，都应留在池里')
  for (const f of smallFiles) assert.ok(vm.libreLruKeys.includes('left:' + f.id))
})

test('左右两窗格各开一个大文档并互为活动文件：两个都不许被淘汰（活动文件是硬底线）', async () => {
  const left1 = { id: 1, fileSize: 7 * MB }
  const right1 = { id: 2, fileSize: 7 * MB }
  const vm = makeVm([left1])
  vm.rightFiles = [right1]

  vm.activeFileIdLeft = left1.id
  vm.onActiveOfficeFileChanged('left', left1)
  vm.setLibreRef('left', left1.id, mountable(left1))
  await new Promise((r) => setTimeout(r, 0))

  vm.activeFileIdRight = right1.id
  vm.onActiveOfficeFileChanged('right', right1)
  vm.setLibreRef('right', right1.id, mountable(right1))
  await new Promise((r) => setTimeout(r, 0))

  assert.ok(vm.libreLruKeys.includes('left:1'), '左窗格的活动大文档不许被挤掉')
  assert.ok(vm.libreLruKeys.includes('right:2'), '右窗格的活动大文档不许被挤掉')
})

test('体积字段缺失（fileSize 为 undefined）按最小权重 1 处理，不炸也不误伤', async () => {
  const files = [1, 2, 3].map((id) => ({ id })) // 无 fileSize
  const vm = makeVm(files)

  await activateSequence(vm, 'left', files)

  assert.equal(vm.libreLruKeys.length, 3, '缺体积信息时退化成旧的"数量"语义，不该异常淘汰')
})
