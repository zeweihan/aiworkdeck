// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/components/LitigationVisualPanel.vue', import.meta.url), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')

function panel(states) {
  let installs = 0
  let reloads = 0
  const component = new Function('t', 'packStatus', 'packInstall', script.replace('export default', 'return'))(
    (key) => key,
    async () => ({ status: { state: states.shift() || 'ready' } }),
    async (id) => { assert.equal(id, 'litigation-visual'); installs++ }
  )
  const vm = Object.assign(component.data(), component.methods, {
    reload: async () => { reloads++ },
    startPackPoll: () => {}, stopPackPoll: () => {}
  })
  for (const [key, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, key, { get: () => fn.call(vm) })
  }
  return { vm, installs: () => installs, reloads: () => reloads }
}

test('旧资源包 ready 仍允许主动更新；同步完成后重查时间轴能力', async () => {
  const p = panel(['ready'])
  p.vm.litPackStatus = { state: 'ready' }
  await p.vm.updateTimelinePack()
  assert.equal(p.installs(), 1)
  assert.equal(p.reloads(), 1)
})

test('异步下载完成时刷新引擎可用性，用户不用重启面板', async () => {
  const p = panel(['downloading', 'ready'])
  p.vm.litPackStatus = { state: 'ready' }
  await p.vm.updateTimelinePack()
  assert.equal(p.reloads(), 0)
  await p.vm.refreshPackStatus()
  assert.equal(p.reloads(), 1)
})

test('下载中不重复安装，已有语义地图启动入口保留', async () => {
  const p = panel([])
  p.vm.litPackStatus = { state: 'downloading' }
  await p.vm.updateTimelinePack()
  assert.equal(p.installs(), 0)
  assert.match(source, /status\.timelineAvailable === false/)
  assert.doesNotMatch(componentStart(), /timelineAvailable/)
})

function componentStart() {
  return script.slice(script.indexOf('async start()'), script.indexOf('openDiagram(d)'))
}
