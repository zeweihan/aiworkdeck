// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#968：AwdDialog 开着时必须持有全局浮层（overlayState 的 'awd-dialog'），
// 否则桌面端的 BrowserView（原生层）盖在对话框上：框看不见、遮罩点不到、Esc 进不来，
// 等结果的调用方（例如结束录音前的付费确认）永远挂着。
// 真跑 utils/dialog.js：把它的 @/ 依赖改写成本文件旁的临时替身，dialogCore.js 用真的。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const log = []
globalThis.__overlayLog = log
globalThis.document = {
  body: { appendChild() {} },
  getElementById: () => null,
  createElement: () => ({}),
}

function load() {
  const srcPath = process.env.DIALOG_SRC || new URL('../../src/utils/dialog.js', import.meta.url)
  const src = readFileSync(srcPath, 'utf8')
  const stub = join(tmpdir(), `dialog-overlay-stub-${process.pid}.mjs`)
  writeFileSync(stub, `
    export const createApp = () => ({ mount() {} })
    export const reactive = (o) => (globalThis.__dialogState = o)
    export const t = (k) => k
    export default {}
    export function setGlobalOverlay(active, holder) { globalThis.__overlayLog.push([active, holder]) }
  `, 'utf8')
  const stubUrl = 'file://' + stub
  const out = join(tmpdir(), `dialog-under-test-${process.pid}.mjs`)
  writeFileSync(out, src
    .replace(/from 'vue'/, `from '${stubUrl}'`)
    .replace(/from '@\/components\/AwdDialogHost\.vue'/, `from '${stubUrl}'`)
    .replace(/from '@\/i18n'/, `from '${stubUrl}'`)
    .replace(/from '@\/utils\/overlayState\.js'/, `from '${stubUrl}'`)
    .replace(/from '\.\/dialogCore\.js'/, `from '${new URL('../../src/utils/dialogCore.js', import.meta.url).href}'`),
  'utf8')
  return import(out)
}

// 模拟 AwdDialogHost.onClose：先出队，再 resolve
function closeFirst(state, result) {
  const item = state.queue.shift()
  item.resolve(result)
}

test('对话框排队期间持有 awd-dialog 浮层，全部关闭后才释放，且只动自己的键', async () => {
  const mod = await load()
  // dialog.js 不导出队列：替身 reactive 把它挂到 globalThis.__dialogState 上
  const p1 = mod.showDialog({ title: 'a' })
  const p2 = mod.showDialog({ title: 'b' })
  assert.deepEqual(log[0], [true, 'awd-dialog'], '第一个框入队时就要持有浮层，实际 ' + JSON.stringify(log))
  assert.ok(log.every(([, h]) => h === 'awd-dialog'), '只能操作 awd-dialog 这一个持有者键')
  const state = globalThis.__dialogState
  assert.ok(state, '需要拿到队列')
  closeFirst(state, { confirm: true })
  await p1
  assert.equal(log.filter(([a]) => a === false).length, 0, '队列里还有一个框，不能提前释放')
  closeFirst(state, { confirm: false })
  await p2
  // 释放推迟一个宏任务（防连续两个框之间闪一帧），等它跑完再看
  await new Promise((r) => setTimeout(r, 5))
  assert.deepEqual(log[log.length - 1], [false, 'awd-dialog'], '队列清空后释放浮层')
})

test('上一个框关掉后同步弹下一个：中间不释放浮层（否则网页视图会闪一帧）', async () => {
  const mod = await load()
  const state = globalThis.__dialogState
  const from = log.length
  const p1 = mod.showDialog({ title: 'first' })
  let p2 = null
  // 调用方在上一个框的结果里立刻弹下一个：resolve 回调与再入队在同一个宏任务里
  const item = state.queue.shift()
  item.resolve({ confirm: true })
  p2 = mod.showDialog({ title: 'second' })
  await p1
  await new Promise((r) => setTimeout(r, 5))
  assert.ok(!log.slice(from).some(([a, h]) => a === false && h === 'awd-dialog'),
    '两个框之间不该出现释放，实际 ' + JSON.stringify(log.slice(from)))
  closeFirst(state, { confirm: false })
  await p2
  await new Promise((r) => setTimeout(r, 5))
  assert.deepEqual(log[log.length - 1], [false, 'awd-dialog'], '最后一个框关掉后仍要释放')
})
